#!/usr/bin/env node
// 合并到 main 后自动运行：校验 registry/submissions/ 内过审提交 -> 归档至 registry/items/ -> 重建公开索引 registry/registry.json
import fs from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {computeBundleSha256, reviewResourceBundle} from "../runtime/ai-reviewer.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SUBMISSIONS_DIR = path.join(__dirname, "submissions");
const ITEMS_DIR = path.join(__dirname, "items");
const INDEX_FILE = path.join(__dirname, "registry.json");

async function readJsonSafe(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); }
  catch (err) { if (err?.code === "ENOENT") return fallback; throw err; }
}

async function collectJsonFiles(dir) {
  const out = [];
  let entries;
  try { entries = await fs.readdir(dir, {withFileTypes: true}); } catch { return out; }
  for (const e of entries) {
    const abs = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...await collectJsonFiles(abs));
    else if (e.isFile() && e.name.endsWith(".json")) out.push(abs);
  }
  return out;
}

export async function publishApprovedSubmissions({
  rootDir = __dirname,
  requireLlm = process.env.SOLBRAID_REQUIRE_LLM === "1",
  llmConfig = null,
} = {}) {
  const subsDir = path.join(rootDir, "submissions");
  const itemsDir = path.join(rootDir, "items");
  const indexFile = path.join(rootDir, "registry.json");

  const catalog = await readJsonSafe(indexFile, {schemaVersion: "1.0.0", repository: "GALIAIS/Solbraid", updatedAt: 0, items: []});
  if (!Array.isArray(catalog.items)) catalog.items = [];

  const subFiles = await collectJsonFiles(subsDir);
  const published = [];
  const rejected = [];

  for (const file of subFiles) {
    const raw = await readJsonSafe(file, null);
    if (!raw || typeof raw !== "object") {
      rejected.push({file, reason: "无效 JSON"});
      continue;
    }
    const audit = await reviewResourceBundle(raw, {requireLlm, llmConfig});
    if (audit.verdict !== "approved") {
      rejected.push({file, id: raw.id, reason: audit.summary});
      continue;
    }
    const now = Date.now();
    const sha256 = computeBundleSha256(raw);
    const stored = {
      id: raw.id,
      kind: raw.kind,
      name: raw.name,
      version: raw.version || "1.0.0",
      author: raw.author || "Solbraid 贡献者",
      description: raw.description || "",
      tags: Array.isArray(raw.tags) ? raw.tags : [],
      payload: raw.payload || {},
      sha256,
      audit,
      publishedAt: raw.publishedAt || now,
      updatedAt: now,
    };
    const destRel = `registry/items/${stored.kind}/${stored.id}.json`;
    const destAbs = path.join(itemsDir, stored.kind, `${stored.id}.json`);
    await fs.mkdir(path.dirname(destAbs), {recursive: true});
    await fs.writeFile(destAbs, JSON.stringify(stored, null, 2) + "\n", "utf8");
    await fs.rm(file, {force: true});

    const prev = catalog.items.find(x => x.id === stored.id && x.kind === stored.kind);
    const entry = {
      id: stored.id,
      kind: stored.kind,
      name: stored.name,
      version: stored.version,
      author: stored.author,
      description: stored.description,
      tags: stored.tags,
      payloadPath: destRel,
      sha256,
      downloads: prev?.downloads || 0,
      publishedAt: prev?.publishedAt || now,
      updatedAt: now,
      audit,
    };
    catalog.items = [entry, ...catalog.items.filter(x => !(x.id === stored.id && x.kind === stored.kind))];
    published.push(entry);
  }

  catalog.updatedAt = Date.now();
  await fs.writeFile(indexFile, JSON.stringify(catalog, null, 2) + "\n", "utf8");
  return {published, rejected, totalItems: catalog.items.length};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const res = await publishApprovedSubmissions();
  console.log(JSON.stringify({published: res.published.length, rejected: res.rejected.length, totalItems: res.totalItems}));
  if (res.rejected.length > 0) {
    console.error("Rejected submissions:", res.rejected);
    process.exit(1);
  }
}
