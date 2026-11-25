#!/usr/bin/env node
// Solbraid 共享资源仓库独立服务端（零外部依赖，支持 VPS / Docker / 局域网自托管）
// 所有上传资源必须经过强制仓库 AI 安全审查（静态规则引擎 + LLM 语义门禁），未通过绝不进入下载目录。
import fs from "node:fs/promises";
import fsSync from "node:fs";
import http from "node:http";
import path from "node:path";
import {createHash, randomBytes, randomUUID, timingSafeEqual} from "node:crypto";
import {fileURLToPath} from "node:url";
import {RESOURCE_KINDS, STATIC_SECURITY_RULES_COUNT, reviewResourceBundle} from "../runtime/ai-reviewer.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MAX_BODY_BYTES = 600 * 1024;
const SAFE_RESOURCE_ID_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;

function safeTokenEquals(a, b) {
  const ba = Buffer.from(String(a || ""), "utf8");
  const bb = Buffer.from(String(b || ""), "utf8");
  return ba.length > 0 && ba.length === bb.length && timingSafeEqual(ba, bb);
}

function hashOwnerToken(token) {
  return "tok:" + createHash("sha256").update(String(token || ""), "utf8").digest("hex").slice(0, 16);
}

function parseBearerToken(req) {
  const raw = String(req.headers?.authorization || "").trim();
  const m = raw.match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : "";
}

async function readJsonSafe(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); }
  catch (err) { if (err?.code === "ENOENT") return fallback; throw err; }
}

async function writeJsonAtomic(file, data) {
  await fs.mkdir(path.dirname(file), {recursive: true});
  const tmp = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(data, null, 2) + "\n", {flag: "wx"});
    await fs.rename(tmp, file);
  } finally {
    await fs.rm(tmp, {force: true});
  }
}

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
    "access-control-allow-headers": "content-type,authorization",
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", c => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error("请求体超过 600KB 上限"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {}); }
      catch { reject(new Error("请求体不是合法 JSON")); }
    });
    req.on("error", reject);
  });
}

export function createRegistryServer({
  dataDir,
  llmConfig = null,
  requireLlm = false,
  adminToken = null,
  writeTokens = null, // 可选 Map/Object: { [token]: ownerId } 或 string[]
} = {}) {
  const root = path.resolve(dataDir || path.join(__dirname, "data"));
  const catalogFile = path.join(root, "registry.json");
  const submissionsFile = path.join(root, "submissions.json");
  const itemsDir = path.join(root, "items");
  const quarantineDir = path.join(root, "quarantine");
  const adminTokenFile = path.join(root, "admin.token");

  let resolvedAdminToken = String(adminToken || process.env.SOLBRAID_REGISTRY_ADMIN_TOKEN || "").trim();
  if (!resolvedAdminToken) {
    try {
      resolvedAdminToken = fsSync.readFileSync(adminTokenFile, "utf8").trim();
    } catch {
      resolvedAdminToken = randomBytes(24).toString("hex");
      fsSync.mkdirSync(root, {recursive: true});
      fsSync.writeFileSync(adminTokenFile, resolvedAdminToken + "\n", {encoding: "utf8", mode: 0o600});
    }
  }

  // 解析额外写入令牌（环境变量 SOLBRAID_REGISTRY_TOKENS="ownerA:tokA,ownerB:tokB" 或入参 writeTokens）
  const userTokens = [];
  const envTokRaw = String(process.env.SOLBRAID_REGISTRY_TOKENS || "").trim();
  if (envTokRaw) {
    for (const pair of envTokRaw.split(",")) {
      const idx = pair.indexOf(":");
      if (idx > 0) {
        const ownerId = pair.slice(0, idx).trim();
        const tok = pair.slice(idx + 1).trim();
        if (ownerId && tok) userTokens.push({ownerId, token: tok});
      }
    }
  }
  if (Array.isArray(writeTokens)) {
    for (const tok of writeTokens) {
      const clean = String(tok || "").trim();
      if (clean) userTokens.push({ownerId: hashOwnerToken(clean), token: clean});
    }
  } else if (writeTokens && typeof writeTokens === "object") {
    for (const [tok, owner] of Object.entries(writeTokens)) {
      const clean = String(tok || "").trim();
      const oid = String(owner || "").trim() || hashOwnerToken(clean);
      if (clean) userTokens.push({ownerId: oid, token: clean});
    }
  }

  function authenticateMutation(req) {
    const token = parseBearerToken(req);
    if (!token) return null;
    if (safeTokenEquals(token, resolvedAdminToken)) {
      return {isAdmin: true, ownerId: "admin"};
    }
    for (const entry of userTokens) {
      if (safeTokenEquals(token, entry.token)) {
        return {isAdmin: false, ownerId: entry.ownerId};
      }
    }
    return null;
  }

  const server = http.createServer(async (req, res) => {
    try {
      if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }
      const url = new URL(req.url || "/", "http://127.0.0.1");
      const pathname = url.pathname;

      if (req.method === "GET" && pathname === "/api/v1/health") {
        const catalog = await readJsonSafe(catalogFile, {items: []});
        return sendJson(res, 200, {
          ok: true,
          service: "solbraid-community-registry",
          approvedCount: (catalog.items || []).length,
          staticRulesCount: STATIC_SECURITY_RULES_COUNT,
          llmConfigured: Boolean(llmConfig?.baseUrl || process.env.SOLBRAID_REVIEWER_API_URL),
          requireLlm,
        });
      }

      if (req.method === "GET" && pathname === "/api/v1/resources") {
        const catalog = await readJsonSafe(catalogFile, {schemaVersion: "1.0.0", items: []});
        const kind = (url.searchParams.get("kind") || "").trim().toLowerCase();
        const q = (url.searchParams.get("q") || "").trim().toLowerCase();
        const items = (catalog.items || [])
          .filter(it => it?.audit?.verdict === "approved")
          .filter(it => !kind || kind === "all" || it.kind === kind)
          .filter(it => !q || [it.id, it.name, it.description, it.author, ...(it.tags || [])].join(" ").toLowerCase().includes(q))
          .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
        return sendJson(res, 200, {schemaVersion: "1.0.0", items});
      }

      if (req.method === "GET" && pathname === "/api/v1/submissions") {
        const subs = await readJsonSafe(submissionsFile, []);
        return sendJson(res, 200, {submissions: subs.slice(0, 50)});
      }

      const dlMatch = pathname.match(/^\/api\/v1\/resources\/([^/]+)\/download$/);
      if (req.method === "POST" && dlMatch) {
        const id = decodeURIComponent(dlMatch[1]);
        const body = await readBody(req).catch(() => ({}));
        const catalog = await readJsonSafe(catalogFile, {schemaVersion: "1.0.0", items: []});
        const entry = (catalog.items || []).find(it => it.id === id && (!body.kind || it.kind === body.kind));
        if (!entry || entry.audit?.verdict !== "approved") {
          return sendJson(res, 404, {error: `未找到已过审资源：${id}`});
        }
        const bundle = await readJsonSafe(path.join(itemsDir, entry.kind, `${entry.id}.json`), null);
        if (!bundle) return sendJson(res, 404, {error: "资源包文件缺失"});
        entry.downloads = (entry.downloads || 0) + 1;
        await writeJsonAtomic(catalogFile, catalog);
        return sendJson(res, 200, {sha256: entry.sha256, audit: entry.audit, bundle});
      }

      if (req.method === "POST" && pathname === "/api/v1/preview-audit") {
        const raw = await readBody(req);
        const audit = await reviewResourceBundle(raw, {llmConfig, requireLlm});
        return sendJson(res, 200, {status: audit.verdict, audit});
      }

      const delMatch = pathname.match(/^\/api\/v1\/resources\/([^/]+)\/([^/]+)$/);
      if (req.method === "DELETE" && delMatch) {
        const actor = authenticateMutation(req);
        if (!actor) return sendJson(res, 401, {error: "未认证：删除资源需要有效的 Bearer Token"});
        const kind = decodeURIComponent(delMatch[1]).trim().toLowerCase();
        const id = decodeURIComponent(delMatch[2]).trim().toLowerCase();
        if (!RESOURCE_KINDS.includes(kind) || !SAFE_RESOURCE_ID_RE.test(id) || id.includes("..")) {
          return sendJson(res, 400, {error: "无效的资源类型或标识"});
        }
        const catalog = await readJsonSafe(catalogFile, {schemaVersion: "1.0.0", updatedAt: 0, items: []});
        const existing = (catalog.items || []).find(x => x.id === id && x.kind === kind);
        if (!existing) return sendJson(res, 404, {error: "未找到该资源"});
        if (!actor.isAdmin && (!existing.ownerId || existing.ownerId !== actor.ownerId)) {
          return sendJson(res, 403, {error: "无权删除他人发布的资源（仅资源所有者或管理员可删除）"});
        }
        catalog.items = (catalog.items || []).filter(x => !(x.id === id && x.kind === kind));
        catalog.updatedAt = Date.now();
        await writeJsonAtomic(catalogFile, catalog);
        await fs.rm(path.join(itemsDir, kind, `${id}.json`), {force: true});
        return sendJson(res, 200, {ok: true, removed: {id, kind}});
      }

      if (req.method === "POST" && pathname === "/api/v1/submissions") {
        const actor = authenticateMutation(req);
        if (!actor) return sendJson(res, 401, {error: "未认证：提交或更新资源需要有效的 Bearer Token"});
        const raw = await readBody(req);
        const id = String(raw?.id || "").trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
        const kind = String(raw?.kind || "").trim().toLowerCase();
        if (!RESOURCE_KINDS.includes(kind)) return sendJson(res, 400, {error: `不支持的资源类型：${kind}`});
        if (!SAFE_RESOURCE_ID_RE.test(id) || id.includes("..")) return sendJson(res, 400, {error: `无效的资源标识：${id || "(空)"}`});

        const existingCatalog = await readJsonSafe(catalogFile, {schemaVersion: "1.0.0", updatedAt: 0, items: []});
        const existingEntry = (existingCatalog.items || []).find(x => x.id === id && x.kind === kind);
        if (existingEntry && !actor.isAdmin && (!existingEntry.ownerId || existingEntry.ownerId !== actor.ownerId)) {
          return sendJson(res, 403, {error: `资源 ${kind}/${id} 已归属其他作者，无权覆盖更新`});
        }
        const bundle = {
          id,
          kind,
          name: String(raw?.name || id).trim().slice(0, 80),
          version: String(raw?.version || "1.0.0").trim(),
          author: String(raw?.author || "匿名贡献者").trim().slice(0, 48),
          description: String(raw?.description || "").trim().slice(0, 500),
          tags: Array.isArray(raw?.tags) ? raw.tags.map(String).slice(0, 8) : [],
          payload: raw?.payload && typeof raw.payload === "object" ? raw.payload : {},
        };

        const audit = await reviewResourceBundle(bundle, {llmConfig, requireLlm});
        const subId = `sub-${randomUUID().slice(0, 8)}`;
        const subs = await readJsonSafe(submissionsFile, []);
        subs.unshift({
          submissionId: subId,
          resourceId: bundle.id,
          kind: bundle.kind,
          name: bundle.name,
          version: bundle.version,
          author: bundle.author,
          status: audit.verdict,
          submittedAt: Date.now(),
          audit,
        });
        await writeJsonAtomic(submissionsFile, subs.slice(0, 200));

        if (audit.verdict !== "approved") {
          await writeJsonAtomic(path.join(quarantineDir, `${subId}-${bundle.id || "invalid"}.json`), {bundle, audit});
          const status = audit.verdict === "rejected" ? 422 : 202;
          return sendJson(res, status, {status: audit.verdict, submissionId: subId, audit, item: null});
        }

        const now = Date.now();
        await writeJsonAtomic(path.join(itemsDir, bundle.kind, `${bundle.id}.json`), {
          ...bundle,
          sha256: audit.sha256,
          audit,
          publishedAt: now,
          updatedAt: now,
        });
        const catalog = await readJsonSafe(catalogFile, {schemaVersion: "1.0.0", updatedAt: 0, items: []});
        const prev = (catalog.items || []).find(x => x.id === bundle.id && x.kind === bundle.kind);
        const ownerId = prev?.ownerId || actor.ownerId;
        const entry = {
          id: bundle.id,
          kind: bundle.kind,
          name: bundle.name,
          version: bundle.version,
          author: bundle.author,
          ownerId,
          description: bundle.description,
          tags: bundle.tags,
          sha256: audit.sha256,
          downloads: prev?.downloads || 0,
          publishedAt: prev?.publishedAt || now,
          updatedAt: now,
          audit,
        };
        catalog.items = [entry, ...(catalog.items || []).filter(x => !(x.id === bundle.id && x.kind === bundle.kind))];
        catalog.updatedAt = now;
        await writeJsonAtomic(catalogFile, catalog);
        return sendJson(res, 201, {status: "approved", submissionId: subId, audit, item: entry});
      }

      sendJson(res, 404, {error: "Not Found"});
    } catch (err) {
      sendJson(res, 500, {error: err?.message || String(err)});
    }
  });
  server.adminToken = resolvedAdminToken;
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const getArg = (flag, def) => {
    const idx = args.indexOf(flag);
    return idx >= 0 && args[idx + 1] ? args[idx + 1] : def;
  };
  const port = Number(getArg("--port", process.env.PORT || "8787"));
  const host = getArg("--host", process.env.HOST || "127.0.0.1");
  const dataDir = getArg("--dir", process.env.SOLBRAID_REGISTRY_DIR || path.join(__dirname, "data"));
  const requireLlm = args.includes("--require-llm") || process.env.SOLBRAID_REQUIRE_LLM === "1";
  const srv = createRegistryServer({dataDir, requireLlm});
  srv.listen(port, host, () => {
    console.log(`Solbraid Community Registry listening on http://${host}:${port} (data=${dataDir}, requireLlm=${requireLlm}, auth=required)`);
  });
}
