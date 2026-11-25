// Solbraid 统一社区共享资源仓库客户端 & 内置自托管仓库管理模块
// 支持 3 种仓库来源：
//   1. builtin-local（本地/内置共享仓库，零配置可用，支持上传/预检/下架/下载验真）
//   2. http（连接远端或团队自托管 registry-server.mjs HTTP 仓库）
//   3. github（对接 GitHub 社区仓库如 GALIAIS/Solbraid，支持一键自动建分支/Fork/发 PR 触发仓库 AI 审查）
import fs from "node:fs/promises";
import path from "node:path";
import {randomUUID} from "node:crypto";
import {
  RESOURCE_KINDS,
  STATIC_SECURITY_RULES_COUNT,
  computeBundleSha256,
  scanStaticSecurity,
  reviewResourceBundle,
} from "./ai-reviewer.mjs";

const DEFAULT_CONFIG = Object.freeze({
  mode: "builtin-local", // "builtin-local" | "http" | "github"
  serverUrl: "",
  serverToken: "",
  githubRepo: "GALIAIS/Solbraid",
  githubBranch: "main",
  githubToken: "",
  githubApiBase: "https://api.github.com",
  githubRawBase: "https://raw.githubusercontent.com",
  authorName: "Solbraid 用户",
  reviewerBaseUrl: "",
  reviewerApiKey: "",
  reviewerModel: "",
  reviewerWireApi: "openai",
  requireLlmReview: false,
});

async function readJsonSafe(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); }
  catch (err) { if (err?.code === "ENOENT") return fallback; throw err; }
}

async function writeJsonAtomic(file, data, mode = 0o600) {
  await fs.mkdir(path.dirname(file), {recursive: true});
  const tmp = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(data, null, 2) + "\n", {mode, flag: "wx"});
    await fs.rename(tmp, file);
  } finally {
    await fs.rm(tmp, {force: true});
  }
}

function normalizeBundle(rawBundle, authorFallback) {
  const id = String(rawBundle?.id || "").trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  return {
    id,
    kind: String(rawBundle?.kind || "").trim().toLowerCase(),
    name: String(rawBundle?.name || id).trim().slice(0, 80),
    version: String(rawBundle?.version || "1.0.0").trim(),
    author: String(rawBundle?.author || authorFallback || "Solbraid 用户").trim().slice(0, 48),
    description: String(rawBundle?.description || "").trim().slice(0, 500),
    tags: Array.isArray(rawBundle?.tags)
      ? rawBundle.tags.map(t => String(t).trim()).filter(Boolean).slice(0, 8)
      : String(rawBundle?.tags || "").split(/[,，\s]+/).map(s => s.trim()).filter(Boolean).slice(0, 8),
    payload: rawBundle?.payload && typeof rawBundle.payload === "object" ? rawBundle.payload : {},
  };
}

export class CommunityRegistryClient {
  constructor({home}) {
    this.home = home;
    this.root = path.join(home, ".galiais", "community-registry");
    this.configFile = path.join(this.root, "config.json");
    this.catalogFile = path.join(this.root, "registry.json");
    this.submissionsFile = path.join(this.root, "submissions.json");
    this.itemsDir = path.join(this.root, "items");
  }

  async loadConfig() {
    const raw = await readJsonSafe(this.configFile, {});
    return {...DEFAULT_CONFIG, ...(raw && typeof raw === "object" ? raw : {})};
  }

  async saveConfig(patch = {}) {
    const cur = await this.loadConfig();
    const mode = ["builtin-local", "http", "github"].includes(patch.mode) ? patch.mode : cur.mode;
    const next = {
      ...cur,
      mode,
      serverUrl: typeof patch.serverUrl === "string" ? patch.serverUrl.trim().replace(/\/+$/, "") : cur.serverUrl,
      serverToken: typeof patch.serverToken === "string" ? patch.serverToken.trim() : cur.serverToken,
      githubRepo: typeof patch.githubRepo === "string"
        ? patch.githubRepo.trim().replace(/^https?:\/\/github\.com\//i, "").replace(/\.git$/i, "").replace(/^\/+|\/+$/g, "")
        : cur.githubRepo,
      githubBranch: typeof patch.githubBranch === "string" && patch.githubBranch.trim() ? patch.githubBranch.trim() : cur.githubBranch,
      githubToken: typeof patch.githubToken === "string" ? patch.githubToken.trim() : cur.githubToken,
      githubApiBase: typeof patch.githubApiBase === "string" && patch.githubApiBase.trim() ? patch.githubApiBase.trim().replace(/\/+$/, "") : cur.githubApiBase,
      githubRawBase: typeof patch.githubRawBase === "string" && patch.githubRawBase.trim() ? patch.githubRawBase.trim().replace(/\/+$/, "") : cur.githubRawBase,
      authorName: typeof patch.authorName === "string" && patch.authorName.trim() ? patch.authorName.trim().slice(0, 48) : cur.authorName,
      reviewerBaseUrl: typeof patch.reviewerBaseUrl === "string" ? patch.reviewerBaseUrl.trim().replace(/\/+$/, "") : cur.reviewerBaseUrl,
      reviewerApiKey: typeof patch.reviewerApiKey === "string" ? patch.reviewerApiKey.trim() : cur.reviewerApiKey,
      reviewerModel: typeof patch.reviewerModel === "string" ? patch.reviewerModel.trim() : cur.reviewerModel,
      reviewerWireApi: ["openai", "anthropic"].includes(patch.reviewerWireApi) ? patch.reviewerWireApi : cur.reviewerWireApi,
      requireLlmReview: typeof patch.requireLlmReview === "boolean" ? patch.requireLlmReview : cur.requireLlmReview,
    };
    await writeJsonAtomic(this.configFile, next);
    return this.sanitizeConfig(next);
  }

  sanitizeConfig(cfg) {
    return {
      mode: cfg.mode,
      serverUrl: cfg.serverUrl,
      hasServerToken: Boolean(cfg.serverToken),
      githubRepo: cfg.githubRepo,
      githubBranch: cfg.githubBranch,
      hasGithubToken: Boolean(cfg.githubToken),
      authorName: cfg.authorName,
      reviewerBaseUrl: cfg.reviewerBaseUrl,
      hasReviewerApiKey: Boolean(cfg.reviewerApiKey),
      reviewerModel: cfg.reviewerModel,
      reviewerWireApi: cfg.reviewerWireApi,
      requireLlmReview: cfg.requireLlmReview,
      staticRulesCount: STATIC_SECURITY_RULES_COUNT,
    };
  }

  #llmConfigFrom(cfg, override = null) {
    if (override) return override;
    if (!cfg.reviewerBaseUrl) return null;
    return {
      baseUrl: cfg.reviewerBaseUrl,
      apiKey: cfg.reviewerApiKey,
      model: cfg.reviewerModel,
      wireApi: cfg.reviewerWireApi,
    };
  }

  async #readLocalCatalog() {
    const data = await readJsonSafe(this.catalogFile, {schemaVersion: "1.0.0", updatedAt: 0, items: []});
    if (!Array.isArray(data.items)) data.items = [];
    return data;
  }

  async #readLocalSubmissions() {
    const data = await readJsonSafe(this.submissionsFile, []);
    return Array.isArray(data) ? data : [];
  }

  async #recordSubmission(rec) {
    const subs = await this.#readLocalSubmissions();
    subs.unshift(rec);
    await writeJsonAtomic(this.submissionsFile, subs.slice(0, 100));
  }

  /** 拉取共享仓库资源目录与最近 AI 审查日志。 */
  async list({kind = "", query = ""} = {}) {
    const cfg = await this.loadConfig();
    const q = String(query || "").trim().toLowerCase();
    const k = String(kind || "").trim().toLowerCase();

    const filterItems = items => items
      .filter(it => it?.audit?.verdict === "approved")
      .filter(it => !k || k === "all" || it.kind === k)
      .filter(it => {
        if (!q) return true;
        const hay = [it.id, it.name, it.description, it.author, ...(it.tags || [])].join(" ").toLowerCase();
        return hay.includes(q);
      })
      .sort((a, b) => (b.updatedAt || b.publishedAt || 0) - (a.updatedAt || a.publishedAt || 0));

    if (cfg.mode === "http" && cfg.serverUrl) {
      const qs = new URLSearchParams();
      if (k && k !== "all") qs.set("kind", k);
      if (q) qs.set("q", q);
      const [res, subRes] = await Promise.all([
        fetch(`${cfg.serverUrl}/api/v1/resources?${qs.toString()}`),
        fetch(`${cfg.serverUrl}/api/v1/submissions`).catch(() => null),
      ]);
      if (!res.ok) throw new Error(`共享仓库连接失败（HTTP ${res.status}）`);
      const body = await res.json();
      const subBody = subRes && subRes.ok ? await subRes.json().catch(() => ({submissions: []})) : {submissions: []};
      return {
        config: this.sanitizeConfig(cfg),
        sourceLabel: cfg.serverUrl,
        items: filterItems(body.items || []),
        submissions: (subBody.submissions || []).slice(0, 30),
        aiReviewerActive: true,
      };
    }

    if (cfg.mode === "github" && cfg.githubRepo) {
      const rawBase = cfg.githubRawBase || "https://raw.githubusercontent.com";
      const rawUrl = `${rawBase}/${cfg.githubRepo}/${encodeURIComponent(cfg.githubBranch)}/registry/registry.json`;
      const res = await fetch(rawUrl);
      if (!res.ok) throw new Error(`拉取 GitHub 共享仓库索引失败（HTTP ${res.status}）`);
      const body = await res.json();
      const localSubs = await this.#readLocalSubmissions();
      return {
        config: this.sanitizeConfig(cfg),
        sourceLabel: `github:${cfg.githubRepo}@${cfg.githubBranch}`,
        items: filterItems(body.items || []),
        submissions: localSubs.slice(0, 20),
        aiReviewerActive: true,
      };
    }

    const catalog = await this.#readLocalCatalog();
    const submissions = await this.#readLocalSubmissions();
    return {
      config: this.sanitizeConfig(cfg),
      sourceLabel: "内置本地共享仓库（~/.galiais/community-registry）",
      items: filterItems(catalog.items),
      submissions: submissions.slice(0, 30),
      aiReviewerActive: true,
    };
  }

  /** 上传前干跑安全预检（不发布，实时返回静态规则 + AI 语义审查结论）。 */
  async previewAudit(rawBundle, {reviewerOverride = null} = {}) {
    const cfg = await this.loadConfig();
    const bundle = normalizeBundle(rawBundle, cfg.authorName);
    if (!RESOURCE_KINDS.includes(bundle.kind)) throw new Error(`不支持的资源类型：${bundle.kind}`);
    if (cfg.mode === "http" && cfg.serverUrl) {
      const resp = await fetch(`${cfg.serverUrl}/api/v1/preview-audit`, {
        method: "POST",
        headers: {"content-type": "application/json"},
        body: JSON.stringify(bundle),
      }).catch(() => null);
      if (resp && resp.ok) return await resp.json();
    }
    const audit = await reviewResourceBundle(bundle, {
      llmConfig: this.#llmConfigFrom(cfg, reviewerOverride),
      requireLlm: cfg.requireLlmReview,
    });
    return {status: audit.verdict, audit};
  }

  /** 测试配置的仓库 AI 审查模型连通性与 Fail-Closed JSON 响应能力。 */
  async testReviewer() {
    const cfg = await this.loadConfig();
    const llmConfig = this.#llmConfigFrom(cfg);
    if (!llmConfig) throw new Error("请先填写审查 AI 的 Base URL 与模型名称");
    const t0 = Date.now();
    const audit = await reviewResourceBundle({
      id: "health.ping",
      kind: "instruction",
      name: "连通性探测指令",
      version: "1.0.0",
      description: "用于验证 AI 安全审查模型响应的良性样例",
      payload: {title: "编码风格", content: "保持代码简洁、添加必要注释并运行单元测试。"},
    }, {llmConfig, requireLlm: true});
    return {
      ok: audit.reviewerMode === "static+ai",
      latencyMs: Date.now() - t0,
      verdict: audit.verdict,
      model: audit.reviewerModel,
      summary: audit.summary,
    };
  }

  async #ghJson(apiBase, token, endpoint, opts = {}) {
    const resp = await fetch(`${apiBase}${endpoint}`, {
      method: opts.method || "GET",
      headers: {
        accept: "application/vnd.github+json",
        authorization: `Bearer ${token}`,
        "user-agent": "Solbraid-Registry-Client",
        "x-github-api-version": "2022-11-28",
        ...(opts.body ? {"content-type": "application/json"} : {}),
      },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    const text = await resp.text();
    let json = {};
    try { json = text ? JSON.parse(text) : {}; } catch {}
    if (!resp.ok) {
      if (opts.allow404 && resp.status === 404) return null;
      throw new Error(`GitHub API ${opts.method || "GET"} ${endpoint} 失败（HTTP ${resp.status}）：${json.message || text.slice(0, 140)}`);
    }
    return json;
  }

  /** 通过 GitHub REST API 自动完成（如需自动 Fork）→ 建分支 → 提交文件 → 创建 PR 触发仓库 AI 安全审查。 */
  async #submitToGitHubPr(cfg, bundle, audit) {
    const parts = cfg.githubRepo.split("/");
    if (parts.length !== 2 || !parts[0] || !parts[1]) throw new Error(`无效 GitHub 仓库格式：${cfg.githubRepo}`);
    const [owner, repo] = parts;
    const apiBase = cfg.githubApiBase || "https://api.github.com";
    const token = cfg.githubToken;

    const upstream = await this.#ghJson(apiBase, token, `/repos/${owner}/${repo}`);
    const baseBranch = cfg.githubBranch || upstream.default_branch || "main";
    const refData = await this.#ghJson(apiBase, token, `/repos/${owner}/${repo}/git/ref/heads/${encodeURIComponent(baseBranch)}`);
    const baseSha = refData?.object?.sha;
    if (!baseSha) throw new Error(`无法读取上游分支 ${baseBranch} 的提交 SHA`);

    let pushOwner = owner;
    let pushRepo = repo;
    if (!upstream.permissions?.push) {
      const me = await this.#ghJson(apiBase, token, "/user");
      pushOwner = me.login;
      const existingFork = await this.#ghJson(apiBase, token, `/repos/${pushOwner}/${repo}`, {allow404: true});
      if (!existingFork) {
        await this.#ghJson(apiBase, token, `/repos/${owner}/${repo}/forks`, {method: "POST", body: {}});
        for (let i = 0; i < 6; i++) {
          await new Promise(r => setTimeout(r, 1000));
          const ready = await this.#ghJson(apiBase, token, `/repos/${pushOwner}/${repo}`, {allow404: true});
          if (ready) break;
        }
      }
    }

    const branchName = `submit/${bundle.kind}-${bundle.id}-${randomUUID().slice(0, 6)}`;
    await this.#ghJson(apiBase, token, `/repos/${pushOwner}/${pushRepo}/git/refs`, {
      method: "POST",
      body: {ref: `refs/heads/${branchName}`, sha: baseSha},
    });

    const filePath = `registry/submissions/${bundle.kind}/${bundle.id}.json`;
    const existingFile = await this.#ghJson(
      apiBase, token,
      `/repos/${pushOwner}/${pushRepo}/contents/${filePath}?ref=${encodeURIComponent(branchName)}`,
      {allow404: true},
    );
    const fileContent = JSON.stringify({...bundle, sha256: audit.sha256, preAudit: audit}, null, 2) + "\n";
    await this.#ghJson(apiBase, token, `/repos/${pushOwner}/${pushRepo}/contents/${filePath}`, {
      method: "PUT",
      body: {
        message: `submit(${bundle.kind}): ${bundle.name} v${bundle.version}`,
        content: Buffer.from(fileContent, "utf8").toString("base64"),
        branch: branchName,
        ...(existingFile?.sha ? {sha: existingFile.sha} : {}),
      },
    });

    const prBody = [
      `## Solbraid 共享资源提交`,
      `- **名称**：${bundle.name} (\`${bundle.id}\`)`,
      `- **类型**：\`${bundle.kind}\` · **版本**：\`v${bundle.version}\` · **作者**：${bundle.author}`,
      `- **简介**：${bundle.description || "无"}`,
      `- **SHA-256**：\`${audit.sha256}\``,
      `- **客户端本地预检**：\`${audit.verdict}\`（${audit.score}/100 · ${audit.reviewerMode}）`,
      ``,
      `> 本 PR 将自动触发仓库 **Mandatory AI Security Gatekeeper** 审查；审查通过并合并后，自动重建公开 \`registry/registry.json\`。`,
    ].join("\n");

    const pr = await this.#ghJson(apiBase, token, `/repos/${owner}/${repo}/pulls`, {
      method: "POST",
      body: {
        title: `submit(${bundle.kind}): ${bundle.name} v${bundle.version}`,
        head: pushOwner === owner ? branchName : `${pushOwner}:${branchName}`,
        base: baseBranch,
        body: prBody,
      },
    });

    await this.#recordSubmission({
      submissionId: `pr-${pr.number || randomUUID().slice(0, 6)}`,
      resourceId: bundle.id,
      kind: bundle.kind,
      name: bundle.name,
      version: bundle.version,
      author: bundle.author,
      status: "pr_submitted",
      submittedAt: Date.now(),
      audit,
    });
    return {
      status: "pr_submitted",
      prUrl: pr.html_url || "",
      prNumber: Number(pr.number || 0),
      audit,
      prHint: `已自动创建 PR #${pr.number}，仓库 AI 安全审查流水线已触发，审查通过合并后所有用户即可下载。`,
    };
  }

  /** 上传分享资源：必须通过仓库 AI 安全审查，仅 approved 允许入库展示与下载。 */
  async submit(rawBundle, {reviewerOverride = null} = {}) {
    const cfg = await this.loadConfig();
    const bundle = normalizeBundle(rawBundle, cfg.authorName);
    if (!RESOURCE_KINDS.includes(bundle.kind)) throw new Error(`不支持上传的资源类型：${bundle.kind}`);

    if (cfg.mode === "http" && cfg.serverUrl) {
      const headers = {"content-type": "application/json"};
      if (cfg.serverToken) headers.authorization = `Bearer ${cfg.serverToken}`;
      const resp = await fetch(`${cfg.serverUrl}/api/v1/submissions`, {
        method: "POST",
        headers,
        body: JSON.stringify(bundle),
      });
      const data = await resp.json().catch(() => ({}));
      if (!resp.ok && resp.status !== 422 && resp.status !== 202) {
        throw new Error(data.error || `上传失败（HTTP ${resp.status}）`);
      }
      return data;
    }

    const audit = await reviewResourceBundle(bundle, {
      llmConfig: this.#llmConfigFrom(cfg, reviewerOverride),
      requireLlm: cfg.requireLlmReview,
    });

    if (cfg.mode === "github" && cfg.githubRepo) {
      if (audit.verdict !== "approved") return {status: audit.verdict, audit, item: null};
      if (cfg.githubToken) return await this.#submitToGitHubPr(cfg, bundle, audit);
      const outFile = path.join(this.root, "pr-bundles", `${bundle.kind}-${bundle.id}.json`);
      await writeJsonAtomic(outFile, {...bundle, sha256: audit.sha256, audit}, 0o644);
      return {
        status: "pr_bundle_ready",
        audit,
        bundlePath: outFile,
        prHint: `已通过本地预检并导出至 ${outFile}。在「配置仓库」填入 GitHub PAT 后，即可一键直接向 ${cfg.githubRepo} 发起 PR 并触发仓库 AI 审查。`,
      };
    }

    await this.#recordSubmission({
      submissionId: `sub-${randomUUID().slice(0, 8)}`,
      resourceId: bundle.id,
      kind: bundle.kind,
      name: bundle.name,
      version: bundle.version,
      author: bundle.author,
      status: audit.verdict,
      submittedAt: Date.now(),
      audit,
    });
    if (audit.verdict !== "approved") return {status: audit.verdict, audit, item: null};

    const now = Date.now();
    const itemFile = path.join(this.itemsDir, bundle.kind, `${bundle.id}.json`);
    await writeJsonAtomic(itemFile, {...bundle, sha256: audit.sha256, audit, publishedAt: now, updatedAt: now}, 0o644);

    const catalog = await this.#readLocalCatalog();
    const prev = catalog.items.find(x => x.id === bundle.id && x.kind === bundle.kind);
    const catalogEntry = {
      id: bundle.id, kind: bundle.kind, name: bundle.name, version: bundle.version,
      author: bundle.author, description: bundle.description, tags: bundle.tags,
      sha256: audit.sha256, downloads: prev?.downloads || 0,
      publishedAt: prev?.publishedAt || now, updatedAt: now, audit,
    };
    catalog.items = [catalogEntry, ...catalog.items.filter(x => !(x.id === bundle.id && x.kind === bundle.kind))];
    catalog.updatedAt = now;
    await writeJsonAtomic(this.catalogFile, catalog, 0o644);
    return {status: "approved", audit, item: catalogEntry};
  }

  /** 下架/移除共享仓库中已发布的资源（支持本地仓库与 HTTP 服务端）。 */
  async remove({id, kind}) {
    const cfg = await this.loadConfig();
    if (cfg.mode === "github") throw new Error("GitHub 仓库资源请通过 PR 维护下架");
    if (cfg.mode === "http" && cfg.serverUrl) {
      const headers = {};
      if (cfg.serverToken) headers.authorization = `Bearer ${cfg.serverToken}`;
      const resp = await fetch(`${cfg.serverUrl}/api/v1/resources/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`, {
        method: "DELETE",
        headers,
      });
      if (!resp.ok) {
        const errData = await resp.json().catch(() => ({}));
        throw new Error(errData.error || `下架资源失败（HTTP ${resp.status}）`);
      }
      return await resp.json();
    }
    const catalog = await this.#readLocalCatalog();
    const before = catalog.items.length;
    catalog.items = catalog.items.filter(x => !(x.id === id && x.kind === kind));
    if (catalog.items.length === before) throw new Error(`未找到该资源：${id}`);
    catalog.updatedAt = Date.now();
    await writeJsonAtomic(this.catalogFile, catalog, 0o644);
    await fs.rm(path.join(this.itemsDir, kind, `${id}.json`), {force: true});
    return {ok: true, removed: {id, kind}};
  }

  /** 下载并执行端到端验真（强制校验 approved 状态 + SHA-256 指纹 + 客户端二次静态防恶意复检）。 */
  async downloadVerified({id, kind}) {
    const cfg = await this.loadConfig();
    let bundle = null;
    let expectedSha = "";

    if (cfg.mode === "http" && cfg.serverUrl) {
      const resp = await fetch(`${cfg.serverUrl}/api/v1/resources/${encodeURIComponent(id)}/download`, {
        method: "POST",
        headers: {"content-type": "application/json"},
        body: JSON.stringify({kind}),
      });
      if (!resp.ok) throw new Error(`下载共享资源失败（HTTP ${resp.status}）`);
      const data = await resp.json();
      bundle = data.bundle || data;
      expectedSha = String(data.sha256 || bundle?.sha256 || "");
    } else if (cfg.mode === "github" && cfg.githubRepo) {
      const catalog = await this.list({kind});
      const entry = catalog.items.find(x => x.id === id && (!kind || x.kind === kind));
      if (!entry) throw new Error(`共享仓库中未找到已过审资源：${id}`);
      expectedSha = String(entry.sha256 || "");
      const payloadPath = entry.payloadPath || `registry/items/${entry.kind}/${entry.id}.json`;
      const rawBase = cfg.githubRawBase || "https://raw.githubusercontent.com";
      const rawUrl = `${rawBase}/${cfg.githubRepo}/${encodeURIComponent(cfg.githubBranch)}/${payloadPath.replace(/^\/+/, "")}`;
      const resp = await fetch(rawUrl);
      if (!resp.ok) throw new Error(`拉取 GitHub 资源包失败（HTTP ${resp.status}）`);
      bundle = await resp.json();
    } else {
      const catalog = await this.#readLocalCatalog();
      const entry = catalog.items.find(x => x.id === id && (!kind || x.kind === kind));
      if (!entry) throw new Error(`共享仓库中未找到资源：${id}`);
      expectedSha = String(entry.sha256 || "");
      bundle = await readJsonSafe(path.join(this.itemsDir, entry.kind, `${entry.id}.json`), null);
      if (!bundle) throw new Error("资源载荷文件缺失");
      entry.downloads = (entry.downloads || 0) + 1;
      await writeJsonAtomic(this.catalogFile, catalog, 0o644);
    }

    if (bundle?.audit?.verdict !== "approved") throw new Error("安全拦截：该资源未通过仓库 AI 安全审查，禁止下载安装");
    const wantId = String(id || "").trim().toLowerCase();
    const wantKind = String(kind || "").trim().toLowerCase();
    const actualId = String(bundle?.id || "").trim().toLowerCase();
    const actualKind = String(bundle?.kind || "").trim().toLowerCase();
    if (!actualId || actualId !== wantId || (wantKind && actualKind !== wantKind)) {
      throw new Error(`安全拦截：返回资源身份与请求不匹配（期望 ${wantKind || "*"}/${wantId}，实际 ${actualKind || "?"}/${actualId || "?"}）`);
    }
    const normalizedExpectedSha = String(expectedSha || "").trim().toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(normalizedExpectedSha)) {
      throw new Error("安全拦截：共享资源缺少有效的 SHA-256 摘要，禁止安装");
    }
    const actualSha = computeBundleSha256(bundle);
    if (actualSha !== normalizedExpectedSha) throw new Error("安全拦截：资源 SHA-256 校验不匹配（可能被篡改），已中止安装");
    const localRecheck = scanStaticSecurity(bundle);
    if (!localRecheck.ok) throw new Error(`客户端二次安全复检拦截：${localRecheck.findings[0]?.title || "检测到高危模式"}`);
    return {bundle, sha256: actualSha, audit: bundle.audit};
  }
}
