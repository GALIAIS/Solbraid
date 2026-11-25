// Solbraid 统一扩展中心运行时引擎：管理单源 MCP、Skills 与 Plugin 插件包，
// 自动无侵入投影到 Codex (~/.codex/config.toml & ~/.agents/skills)、
// Claude Code (query mcpServers & ~/.claude/skills) 及全部外部 ACP / CLI Agent。
import fs from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {randomUUID} from "node:crypto";
import {readDownloadNetwork, downloadEnvironment, githubDownloadUrl, redactNetworkText} from "./download-network.mjs";
import {loadEffectiveUserEnv} from "./env-ssh-hub.mjs";

const execFileAsync = promisify(execFile);
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN_MCP_SCRIPT = path.join(THIS_DIR, "plugin-mcp.mjs");
const HOME = process.env.HOME || "/home/agent";
export const EXT_ROOT = path.join(HOME, ".galiais", "extensions");
const BUILTIN_SKILLS_DIR = path.join(EXT_ROOT, "builtin-skills");
const REGISTRY_FILE = path.join(EXT_ROOT, "registry.json");
const SECRETS_FILE = path.join(EXT_ROOT, "runtime-secrets.json");
const SKILLS_DIR = path.join(EXT_ROOT, "skills");
const PLUGINS_DIR = path.join(EXT_ROOT, "plugins");
const CODEX_CONFIG = path.join(HOME, ".codex", "config.toml");
const CODEX_SKILLS_DIR = path.join(HOME, ".agents", "skills");
const CLAUDE_SKILLS_DIR = path.join(HOME, ".claude", "skills");
const AGY_SKILLS_DIR = path.join(HOME, ".gemini", "antigravity", "skills");
const AGY_SETTINGS_FILE = path.join(HOME, ".gemini", "antigravity-cli", "settings.json");
const AGY_MCP_CONFIG_FILE = path.join(HOME, ".gemini", "config", "mcp_config.json");
const CLAUDE_MD_FILE = path.join(HOME, ".claude", "CLAUDE.md");
const CODEX_MD_FILE = path.join(HOME, ".codex", "AGENTS.md");
const AGY_MD_FILE = path.join(HOME, ".gemini", "config", "GEMINI.md");
const CLAUDE_AGENTS_DIR = path.join(HOME, ".claude", "agents");
const CODEX_AGENTS_DIR = path.join(HOME, ".codex", "agents");
const SHARED_AGENTS_DIR = path.join(HOME, ".agents", "agents");
const CODEX_HOOKS_FILE = path.join(HOME, ".codex", "hooks.json");
const SHARED_HOOKS_FILE = path.join(HOME, ".agents", "hooks.json");
const CLAUDE_SETTINGS_FILE = path.join(HOME, ".claude", "settings.json");

const TOML_START = "# >>> solbraid-managed-mcp-start >>>";
const TOML_END = "# <<< solbraid-managed-mcp-end <<<";
const MD_START = "<!-- >>> solbraid-managed-instructions-start >>> -->";
const MD_END = "<!-- <<< solbraid-managed-instructions-end <<< -->";
const SAFE_ID = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/;

function sanitizeId(raw) {
  const s = String(raw || "").trim().replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60);
  if (!s || !SAFE_ID.test(s)) throw new Error(`无效的扩展标识：${raw}`);
  return s;
}

function tomlKey(id) {
  return "solbraid_" + String(id).replace(/[^a-zA-Z0-9_]/g, "_");
}

function tomlString(v) {
  return JSON.stringify(String(v ?? ""));
}

async function readJsonSafe(file, fallback) {
  try {
    const raw = await fs.readFile(file, "utf8");
    if (!raw.trim()) return fallback;
    return JSON.parse(raw);
  } catch (err) {
    if (err.code === "ENOENT") return fallback;
    throw err;
  }
}

async function writeJsonAtomic(file, value, mode = 0o600) {
  await writeAtomic(file, JSON.stringify(value, null, 2) + "\n", mode);
}

async function writeAtomic(file, content, mode = 0o600) {
  await fs.mkdir(path.dirname(file), {recursive: true});
  const tmp = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, content, {mode, flag: "wx"});
    await fs.rename(tmp, file);
  } finally {
    await fs.rm(tmp, {force: true});
  }
}

function matchesTarget(targets, agentId) {
  if (!Array.isArray(targets) || targets.length === 0) return true;
  return targets.includes("all") || targets.includes(agentId);
}

function matchesScope(scope, cwd) {
  if (!scope || scope === "global") return true;
  if (!cwd) return false;
  const prefix = scope.startsWith("project:") ? scope.slice("project:".length) : scope;
  const normPrefix = prefix.replace(/\/+$/, "");
  const normCwd = cwd.replace(/\/+$/, "");
  return normCwd === normPrefix || normCwd.startsWith(normPrefix + "/");
}

export function parseSkillFrontmatter(rawText, fallbackName = "skill") {
  const text = String(rawText || "").replace(/\r\n/g, "\n");
  const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text);
  let name = fallbackName;
  let description = "";
  let body = text;
  if (m) {
    const fm = m[1];
    body = m[2].trim();
    for (const line of fm.split("\n")) {
      const idx = line.indexOf(":");
      if (idx <= 0) continue;
      const k = line.slice(0, idx).trim().toLowerCase();
      const v = line.slice(idx + 1).trim().replace(/^["']|["']$/g, "");
      if (k === "name" && v) name = v;
      if (k === "description" && v) description = v;
    }
  }
  return {name, description, body, raw: text};
}

function buildSkillMarkdown(name, description, body) {
  const cleanName = String(name || "").trim().replace(/[\r\n]/g, " ");
  const cleanDesc = String(description || "").trim().replace(/[\r\n]/g, " ");
  const cleanBody = String(body || "").replace(/\r\n/g, "\n").trim();
  return `---\nname: ${cleanName}\ndescription: ${cleanDesc}\n---\n\n${cleanBody}\n`;
}

async function resolveEffectiveServers(registry, secrets, agentId, cwd) {
  const network = await readDownloadNetwork();
  const userEnv = await loadEffectiveUserEnv(cwd);
  const secretMap = secrets || {};
  const enabledPluginIds = new Set(
    (registry.plugins || []).filter(p => p && p.enabled !== false).map(p => p.id),
  );
  const allServers = Array.isArray(registry.mcpServers) ? registry.mcpServers : [];
  const out = [];
  for (const srv of allServers) {
    if (!srv || srv.enabled === false) continue;
    if (srv.pluginId && !enabledPluginIds.has(srv.pluginId)) continue;
    if (!matchesTarget(srv.targets, agentId)) continue;
    if (!matchesScope(srv.scope, cwd)) continue;

    const srvSecrets = secretMap[srv.id] || (srv.pluginId ? secretMap[`plugin:${srv.pluginId}`] : null) || {};
    const env = {...userEnv, ...downloadEnvironment(network, {...process.env, ...userEnv}), ...(srv.env || {})};
    for (const k of (srv.secretEnvKeys || [])) {
      const val = srvSecrets[k] ?? secretMap[k];
      if (val !== undefined && val !== null && String(val).length > 0) env[k] = String(val);
    }
    const headers = {...(srv.headers || {})};
    for (const k of (srv.secretHeaderKeys || [])) {
      const val = srvSecrets[k] ?? secretMap[k];
      if (val !== undefined && val !== null && String(val).length > 0) headers[k] = String(val);
    }
    out.push({...srv, resolvedEnv: env, resolvedHeaders: headers});
  }
  // 自动向三大引擎暴露 Solbraid 内置插件自进化运维 MCP（免手动配置）
  const hasPluginMcp = await fs.stat(PLUGIN_MCP_SCRIPT).then(s => s.isFile()).catch(() => false);
  if (hasPluginMcp && !out.some(s => s.id === "plugin_ctl")) {
    out.push({
      id: "plugin_ctl",
      name: "Solbraid 插件自进化运维",
      transport: "stdio",
      command: process.execPath,
      args: [PLUGIN_MCP_SCRIPT],
      scope: "global",
      targets: ["all"],
      enabled: true,
      resolvedEnv: {HOME},
      resolvedHeaders: {},
    });
  }
  return out;
}

/** 为 Claude Code SDK query({ mcpServers }) 读取当前生效的统一 MCP 服务器映射。 */
export async function loadMcpServersForClaude(cwd) {
  const registry = await readJsonSafe(REGISTRY_FILE, {mcpServers: [], skills: [], plugins: []});
  const secrets = await readJsonSafe(SECRETS_FILE, {});
  const list = await resolveEffectiveServers(registry, secrets, "claude", cwd);
  const result = {};
  for (const srv of list) {
    const key = tomlKey(srv.id);
    if (srv.transport === "http" && srv.url) {
      result[key] = {
        type: "http",
        url: srv.url,
        ...(Object.keys(srv.resolvedHeaders).length ? {headers: srv.resolvedHeaders} : {}),
      };
    } else if (srv.command) {
      result[key] = {
        type: "stdio",
        command: srv.command,
        args: Array.isArray(srv.args) ? srv.args.map(String) : [],
        ...(Object.keys(srv.resolvedEnv).length ? {env: srv.resolvedEnv} : {}),
      };
    }
  }
  return result;
}

/** 为外部 ACP Agent session/new & loadSession 读取当前生效的统一 MCP 服务器数组。 */
export async function loadMcpServersForAcp(agentId, cwd) {
  const registry = await readJsonSafe(REGISTRY_FILE, {mcpServers: [], skills: [], plugins: []});
  const secrets = await readJsonSafe(SECRETS_FILE, {});
  const list = await resolveEffectiveServers(registry, secrets, agentId, cwd);
  const result = [];
  for (const srv of list) {
    if (srv.transport === "http" && srv.url) {
      result.push({
        name: tomlKey(srv.id),
        type: "http",
        url: srv.url,
        headers: Object.entries(srv.resolvedHeaders).map(([name, value]) => ({name, value: String(value)})),
      });
    } else if (srv.command) {
      result.push({
        name: tomlKey(srv.id),
        command: srv.command,
        args: Array.isArray(srv.args) ? srv.args.map(String) : [],
        env: Object.entries(srv.resolvedEnv).map(([name, value]) => ({name, value: String(value)})),
      });
    }
  }
  return result;
}

/** 仅替换 ~/.codex/config.toml 中 Solbraid 托管标记段，完整保留用户手写配置。 */
async function syncCodexConfigToml(registry, secrets) {
  const servers = await resolveEffectiveServers(registry, secrets, "codex", null);
  const lines = [];
  for (const srv of servers) {
    if (srv.scope && srv.scope !== "global") continue;
    const key = tomlKey(srv.id);
    lines.push(`[mcp_servers.${key}]`);
    if (srv.transport === "http" && srv.url) {
      lines.push(`url = ${tomlString(srv.url)}`);
      if (Object.keys(srv.resolvedHeaders).length > 0) {
        const pairs = Object.entries(srv.resolvedHeaders).map(([k, v]) => `${tomlString(k)} = ${tomlString(v)}`).join(", ");
        lines.push(`http_headers = { ${pairs} }`);
      }
    } else if (srv.command) {
      lines.push(`command = ${tomlString(srv.command)}`);
      const args = Array.isArray(srv.args) ? srv.args.map(tomlString).join(", ") : "";
      lines.push(`args = [${args}]`);
      if (Object.keys(srv.resolvedEnv).length > 0) {
        const pairs = Object.entries(srv.resolvedEnv).map(([k, v]) => `${tomlString(k)} = ${tomlString(v)}`).join(", ");
        lines.push(`env = { ${pairs} }`);
      }
    }
    lines.push("");
  }
  const managedBlock = lines.length > 0 ? `${TOML_START}\n${lines.join("\n").trimEnd()}\n${TOML_END}\n` : "";
  await fs.mkdir(path.dirname(CODEX_CONFIG), {recursive: true});
  let existing = "";
  try {
    existing = await fs.readFile(CODEX_CONFIG, "utf8");
  } catch (err) {
    if (err.code !== "ENOENT") throw err;
  }
  const blockRe = new RegExp(`${TOML_START}[\\s\\S]*?${TOML_END}\\r?\\n?`, "g");
  const stripped = existing.replace(blockRe, "").trimEnd();
  const next = managedBlock
    ? (stripped ? `${stripped}\n\n${managedBlock}` : managedBlock)
    : (stripped ? `${stripped}\n` : "");
  await writeAtomic(CODEX_CONFIG, next);
  return servers.length;
}

async function syncSkillSymlinkDir(targetDir, entries) {
  await fs.mkdir(targetDir, {recursive: true});
  const desired = new Map();
  for (const e of entries) desired.set(`solbraid-${e.safeId}`, e.dirPath);
  const existing = await fs.readdir(targetDir).catch(() => []);
  for (const name of existing) {
    if (!name.startsWith("solbraid-")) continue;
    const full = path.join(targetDir, name);
    const st = await fs.lstat(full).catch(() => null);
    if (!st || !st.isSymbolicLink()) continue;
    const expected = desired.get(name);
    if (!expected) {
      await fs.unlink(full).catch(() => {});
    } else {
      const linkTarget = await fs.readlink(full).catch(() => "");
      if (linkTarget !== expected) {
        await fs.unlink(full).catch(() => {});
      } else {
        desired.delete(name);
      }
    }
  }
  for (const [name, srcDir] of desired) {
    const ok = await fs.stat(path.join(srcDir, "SKILL.md")).then(s => s.isFile()).catch(() => false);
    if (!ok) continue;
    await fs.symlink(srcDir, path.join(targetDir, name)).catch(() => {});
  }
}

async function syncAntigravityMcpConfig(registry, secrets) {
  const servers = await resolveEffectiveServers(registry, secrets, "antigravity", null);
  // 清理旧版本误写入 settings.json 的 solbraid_* MCP 条目，避免污染认证与信任配置
  const oldSettings = await readJsonSafe(AGY_SETTINGS_FILE, null);
  if (oldSettings?.mcpServers && typeof oldSettings.mcpServers === "object") {
    let touched = false;
    const cleaned = {...oldSettings.mcpServers};
    for (const k of Object.keys(cleaned)) {
      if (k.startsWith("solbraid_")) { delete cleaned[k]; touched = true; }
    }
    if (touched) {
      const nextOld = {...oldSettings};
      if (Object.keys(cleaned).length) nextOld.mcpServers = cleaned;
      else delete nextOld.mcpServers;
      await writeJsonAtomic(AGY_SETTINGS_FILE, nextOld, 0o600).catch(() => {});
    }
  }
  // Antigravity CLI 官方 MCP 配置文件为 ~/.gemini/config/mcp_config.json
  const existing = await readJsonSafe(AGY_MCP_CONFIG_FILE, {});
  const next = existing && typeof existing === "object" && !Array.isArray(existing) ? {...existing} : {};
  const mcpServers = next.mcpServers && typeof next.mcpServers === "object" && !Array.isArray(next.mcpServers)
    ? {...next.mcpServers}
    : {};
  for (const key of Object.keys(mcpServers)) {
    if (key.startsWith("solbraid_")) delete mcpServers[key];
  }
  let count = 0;
  for (const srv of servers) {
    if (srv.scope && srv.scope !== "global") continue;
    const key = tomlKey(srv.id);
    if (srv.transport === "http" && srv.url) {
      mcpServers[key] = {
        serverUrl: srv.url,
        ...(Object.keys(srv.resolvedHeaders).length ? {headers: srv.resolvedHeaders} : {}),
      };
      count++;
    } else if (srv.command) {
      mcpServers[key] = {
        command: srv.command,
        args: Array.isArray(srv.args) ? srv.args.map(String) : [],
        ...(Object.keys(srv.resolvedEnv).length ? {env: srv.resolvedEnv} : {}),
      };
      count++;
    }
  }
  if (Object.keys(mcpServers).length > 0 || existing?.mcpServers) {
    next.mcpServers = mcpServers;
    await writeJsonAtomic(AGY_MCP_CONFIG_FILE, next, 0o600);
  }
  return count;
}

async function syncMarkdownInstructions(filePath, items) {
  let existing = "";
  try { existing = await fs.readFile(filePath, "utf8"); }
  catch (err) { if (err.code !== "ENOENT") throw err; }
  const blockRe = new RegExp(`${MD_START.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[\\s\\S]*?${MD_END.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\r?\\n?`, "g");
  const stripped = existing.replace(blockRe, "").trimEnd();
  if (!items.length) {
    if (existing !== stripped && (stripped || existing.includes(MD_START))) {
      await writeAtomic(filePath, stripped ? `${stripped}\n` : "", 0o644);
    }
    return 0;
  }
  const sections = items.map(it => `## ${String(it.title || it.id).trim()}\n\n${String(it.content || "").trim()}`).join("\n\n");
  const managedBlock = `${MD_START}\n${sections}\n${MD_END}\n`;
  const next = stripped ? `${stripped}\n\n${managedBlock}` : managedBlock;
  await writeAtomic(filePath, next, 0o644);
  return items.length;
}

async function syncAllInstructions(registry) {
  const all = (registry.instructions || []).filter(i => i && i.enabled !== false && String(i.content || "").trim());
  const forTarget = agentId => all.filter(i => matchesTarget(i.targets, agentId));
  const c1 = await syncMarkdownInstructions(CLAUDE_MD_FILE, forTarget("claude"));
  const c2 = await syncMarkdownInstructions(CODEX_MD_FILE, forTarget("codex"));
  const c3 = await syncMarkdownInstructions(AGY_MD_FILE, forTarget("antigravity"));
  return {claudeInstructions: c1, codexInstructions: c2, antigravityInstructions: c3};
}

async function syncSubagentDir(dir, items) {
  await fs.mkdir(dir, {recursive: true});
  const desired = new Set();
  for (const ag of items) {
    const safeId = sanitizeId(ag.id || ag.name);
    const fname = `solbraid-${safeId}.md`;
    desired.add(fname);
    const fmLines = [
      `name: ${String(ag.name || safeId).replace(/[\r\n]/g, " ")}`,
      `description: ${String(ag.description || "").replace(/[\r\n]/g, " ")}`,
    ];
    if (ag.model && String(ag.model).trim()) fmLines.push(`model: ${String(ag.model).trim()}`);
    const body = `---\n${fmLines.join("\n")}\n---\n\n${String(ag.prompt || "").trim()}\n`;
    await writeAtomic(path.join(dir, fname), body, 0o644);
  }
  const existing = await fs.readdir(dir).catch(() => []);
  for (const f of existing) {
    if (f.startsWith("solbraid-") && f.endsWith(".md") && !desired.has(f)) {
      await fs.rm(path.join(dir, f), {force: true}).catch(() => {});
    }
  }
  return items.length;
}

async function syncAllSubagents(registry) {
  const all = (registry.subagents || []).filter(a => a && a.enabled !== false && String(a.name || "").trim());
  const forTarget = agentId => all.filter(a => matchesTarget(a.targets, agentId));
  const c1 = await syncSubagentDir(CLAUDE_AGENTS_DIR, forTarget("claude"));
  const c2 = await syncSubagentDir(CODEX_AGENTS_DIR, forTarget("codex"));
  const c3 = await syncSubagentDir(SHARED_AGENTS_DIR, forTarget("antigravity"));
  return {claudeSubagents: c1, codexSubagents: c2, antigravitySubagents: c3};
}

async function syncHooksJsonFile(filePath, items) {
  const existing = await readJsonSafe(filePath, {});
  const hooksObj = existing?.hooks && typeof existing.hooks === "object" ? {...existing.hooks} : {};
  for (const ev of Object.keys(hooksObj)) {
    if (Array.isArray(hooksObj[ev])) {
      hooksObj[ev] = hooksObj[ev].filter(entry => !entry?._solbraidManaged);
      if (hooksObj[ev].length === 0) delete hooksObj[ev];
    }
  }
  for (const h of items) {
    const ev = String(h.event || "PreToolUse").trim();
    if (!Array.isArray(hooksObj[ev])) hooksObj[ev] = [];
    hooksObj[ev].push({
      _solbraidManaged: true,
      matcher: String(h.matcher || "*"),
      hooks: [{type: "command", command: String(h.command || "")}],
    });
  }
  if (Object.keys(hooksObj).length > 0 || existing?.hooks) {
    await writeJsonAtomic(filePath, {...existing, hooks: hooksObj}, 0o600);
  }
  return items.length;
}

async function syncAllHooks(registry) {
  const all = (registry.hooks || []).filter(h => h && h.enabled !== false && String(h.command || "").trim());
  const c1 = await syncHooksJsonFile(CODEX_HOOKS_FILE, all.filter(h => matchesTarget(h.targets, "codex")));
  const c2 = await syncHooksJsonFile(SHARED_HOOKS_FILE, all.filter(h => matchesTarget(h.targets, "antigravity")));
  const c3 = await syncHooksJsonFile(CLAUDE_SETTINGS_FILE, all.filter(h => matchesTarget(h.targets, "claude")));
  return {codexHooks: c1, antigravityHooks: c2, claudeHooks: c3};
}

const BUILTIN_PLUGIN_SDK_SKILL = `---
name: solbraid-plugin-sdk
description: Solbraid Host 插件微内核开发与自进化升级指南（Phase 4 全能力版）。当用户要求创建、修改、升级、调试或回滚 Solbraid 程序插件、命令、WebView 面板、原生 Compose SDUI 界面、多槽位挂载、自定义 Agent 驱动、协议转换器或工作台渲染器时使用。
---

# Solbraid Host 插件自进化开发与升级指南（20 能力 · 14 MCP 工具）

## 1. 标准自进化闭环（必须按序执行）
1. **查现状**：\`solbraid_plugin_list\` 查看全部插件、SDUI 页面、UI 槽位、动态驱动、协议转换器与 Studio 覆写；\`solbraid_plugin_read\` 读取目标源码。
2. **预检门禁**：\`solbraid_plugin_validate\`（校验非核心红线、Manifest、路径安全、14 条静态安全规则、\`node --check\`）。
3. **原子热升级**：\`solbraid_plugin_apply\`（自动归档快照 → 原子写入 → 单插件增量热载 → 激活失败自动回滚；支持覆写非 \`core\` 内置插件）。
4. **受控装依赖（可选）**：如插件需第三方包，声明 \`deps.npm\` 后调用 \`solbraid_plugin_install_deps\`（强制 \`--ignore-scripts\`）。
5. **现场烟测**：
   - \`solbraid_plugin_test\`（\`command | panel | rpc\`）
   - \`solbraid_plugin_test_sdui\`（校验并预览原生 Compose SDUI AST）
   - \`solbraid_plugin_test_driver\`（烟测动态 \`agent.driver\` 或 \`provider.translator\`）
   - \`solbraid_plugin_logs\` 核查运行日志无异常。
6. **工作台热覆写**：调用 \`solbraid_studio_override\`（\`list | read | apply | rollback | reset\`）安全升级离线渲染器。
7. **一键回滚**：\`solbraid_plugin_snapshots\` + \`solbraid_plugin_rollback\`（覆写内置插件可传 \`restoreBuiltin: true\`）。

## 2. 安全边界与隔离执行层
- 7 个 \`kind: "core"\` 核心服务严格锁定，严禁覆写或停用。
- 推荐为含复杂计算或不受信任逻辑的插件在 Manifest 中设置 \`"executionTier": "worker-isolated"\`，运行于独立 \`worker_threads\`，超时或死循环将被宿主强杀，绝不卡死主进程。
- 禁止在插件代码中使用 \`child_process\`、\`eval\`、\`new Function\`、越界路径访问。

## 3. 20 项宿主能力与常用 SDK 接口
- 全部能力：\`host.info\`、\`log\`、\`lifecycle\`、\`storage.local\`、\`settings.schema\`、\`commands\`、\`rpc\`、\`events.observe\`、\`ui.panel\`、\`ui.composerAction\`、\`ui.sdui\`、\`ui.slot\`、\`hooks.prompt\`、\`services.provide\`、\`services.consume\`、\`workspace.read\`、\`workspace.write\`、\`agent.driver\`、\`provider.translator\`、\`deps.npm\`。
- **原生 Compose SDUI（零 WebView）**：
  - \`ctx.ui.registerSurface({id, title, description, render: async ({cwd, params}) => ({title, subtitle, nodes: [...]})})\`
  - 节点类型：\`column | row | flow | card | section | divider | spacer | text | badge | metric | progress | kv | code | list | button | toggle | input\`
  - 动作类型：\`{type: "rpc", method, params}\`、\`{type: "command", command, args}\`、\`{type: "panel", panelId}\`、\`{type: "surface", surfaceId, fullScreen?}\`、\`{type: "navigate", route: "plugin://<pluginId>/<surfaceId>"}\`、\`{type: "prompt", text}\`、\`{type: "copy", text}\`、\`{type: "refresh"}\`
- **全应用 21 个原生 UI 槽位（默认零占位，有插件才渲染）**：\`ctx.ui.registerSlot({id, slot, label, badge?, surfaceId?, panelId?, command?, render?, routes?, alignment?, offsetX?, offsetY?, draggable?, displayMode?})\`
  - **全局自由悬浮层（任意页面 × 任意坐标 × 可手势拖拽）**：\`"global.overlay"\`（\`routes\`: \`["*"]\` 或 \`["home","chat","files","ssh","terminal","settings"]\`；\`alignment\`: 九宫格 \`topStart..bottomEnd\`；\`offsetX/offsetY\`；\`draggable: true\`；\`displayMode: "pill"|"fab"|"card"\`）
  - **底部导航动态 Tab**：\`"nav.tab"\`（最多 2 个独立整页原生 SDUI/面板 Tab）
  - **整页可接管槽位（带一键切回原版安全条）**：\`"home.replace"\`、\`"chat.replace"\`、\`"files.replace"\`、\`"ssh.replace"\`、\`"settings.replace"\`
  - **首页**：\`"home.topbar"\`、\`"home.banner"\`、\`"home.footer"\`
  - **聊天页**：\`"chat.topbar"\`、\`"chat.banner"\`、\`"chat.composerBar"\`、\`"chat.messageFooter"\`
  - **文件与终端**：\`"files.toolbar"\`、\`"files.banner"\`、\`"ssh.toolbar"\`、\`"ssh.banner"\`、\`"terminal.toolbar"\`
  - **设置与工作台**：\`"settings.section"\`、\`"studio.toolbar"\`
- **动态可执行 Agent 驱动**：\`ctx.drivers.register({id, name, protocol: "plugin-driver", listModels, execute: async ({prompt, cwd, emitChunk, emitThought, emitTool, emitPlan}) => ({text})})\`
- **热插拔 LLM 协议转换器**：\`ctx.providers.registerTranslator({id, name, baseProtocol, toUpstream: (req, ctx) => ({path, headers, body}), fromUpstream: (res, ctx) => ...})\`（在服务商中以 \`protocol: "plugin:<id>"\` 挂载）
- **其他常用**：\`ctx.commands.register\`、\`ctx.panels.register\`、\`ctx.composer.registerAction\`、\`ctx.hooks.onBeforePrompt / onAfterResponse\`、\`ctx.rpc.register\`、\`ctx.storage\`、\`ctx.workspace\`。
`;

async function ensureBuiltinPluginSdkSkill() {
  const dir = path.join(BUILTIN_SKILLS_DIR, "plugin-sdk");
  await writeAtomic(path.join(dir, "SKILL.md"), BUILTIN_PLUGIN_SDK_SKILL, 0o644);
  return {safeId: "plugin-sdk", dirPath: dir};
}

async function syncSkillSymlinks(registry) {
  const enabledPluginIds = new Set((registry.plugins || []).filter(p => p && p.enabled !== false).map(p => p.id));
  const skills = (registry.skills || []).filter(s => s && s.enabled !== false && (!s.pluginId || enabledPluginIds.has(s.pluginId)));
  const sdkSkill = await ensureBuiltinPluginSdkSkill().catch(() => null);
  const codexEntries = sdkSkill ? [sdkSkill] : [];
  const claudeEntries = sdkSkill ? [sdkSkill] : [];
  const agyEntries = sdkSkill ? [sdkSkill] : [];
  for (const sk of skills) {
    if (!sk.dirPath) continue;
    const safeId = sanitizeId(sk.id);
    if (matchesTarget(sk.targets, "codex") || matchesTarget(sk.targets, "all")) codexEntries.push({safeId, dirPath: sk.dirPath});
    if (matchesTarget(sk.targets, "claude") || matchesTarget(sk.targets, "all")) claudeEntries.push({safeId, dirPath: sk.dirPath});
    if (matchesTarget(sk.targets, "antigravity") || matchesTarget(sk.targets, "all")) agyEntries.push({safeId, dirPath: sk.dirPath});
  }
  await syncSkillSymlinkDir(CODEX_SKILLS_DIR, codexEntries);
  await syncSkillSymlinkDir(CLAUDE_SKILLS_DIR, claudeEntries);
  await syncSkillSymlinkDir(AGY_SKILLS_DIR, agyEntries);
  return {codexSkills: codexEntries.length, claudeSkills: claudeEntries.length, antigravitySkills: agyEntries.length};
}

let extensionSyncTail = Promise.resolve();

/** 串行写入各配置文件；单次失败仍向调用方报告，并允许后续同步重试。 */
export function syncExtensions(payload) {
  const current = extensionSyncTail.then(() => syncExtensionsNow(payload));
  extensionSyncTail = current.catch(() => {});
  return current;
}

export function refreshCodexEnvironment() {
  const current = extensionSyncTail.then(async () => {
    const registry = await readJsonSafe(REGISTRY_FILE, {mcpServers: [], skills: [], plugins: [], instructions: [], subagents: [], hooks: []});
    const secrets = await readJsonSafe(SECRETS_FILE, {});
    const count = await syncCodexConfigToml(registry, secrets);
    await syncAntigravityMcpConfig(registry, secrets).catch(() => {});
    return count;
  });
  extensionSyncTail = current.catch(() => {});
  return current;
}

async function syncExtensionsNow({registry, secrets}) {
  const cleanReg = {
    mcpServers: Array.isArray(registry?.mcpServers) ? registry.mcpServers : [],
    skills: Array.isArray(registry?.skills) ? registry.skills : [],
    plugins: Array.isArray(registry?.plugins) ? registry.plugins : [],
    instructions: Array.isArray(registry?.instructions) ? registry.instructions : [],
    subagents: Array.isArray(registry?.subagents) ? registry.subagents : [],
    hooks: Array.isArray(registry?.hooks) ? registry.hooks : [],
  };
  await writeJsonAtomic(REGISTRY_FILE, cleanReg, 0o600);
  if (secrets && typeof secrets === "object") {
    await writeJsonAtomic(SECRETS_FILE, secrets, 0o600);
  }
  const effectiveSecrets = secrets || (await readJsonSafe(SECRETS_FILE, {}));
  const codexMcpCount = await syncCodexConfigToml(cleanReg, effectiveSecrets);
  const antigravityMcpCount = await syncAntigravityMcpConfig(cleanReg, effectiveSecrets).catch(() => 0);
  const skillStats = await syncSkillSymlinks(cleanReg);
  const instrStats = await syncAllInstructions(cleanReg).catch(() => ({}));
  const subagentStats = await syncAllSubagents(cleanReg).catch(() => ({}));
  const hookStats = await syncAllHooks(cleanReg).catch(() => ({}));
  return {ok: true, codexMcpCount, antigravityMcpCount, ...skillStats, ...instrStats, ...subagentStats, ...hookStats};
}

/** 供不支持原生 Skill 发现的外部 CLI Agent 注入精简技能索引。 */
export async function buildSkillsPromptIndex(agentId, cwd) {
  const registry = await readJsonSafe(REGISTRY_FILE, {skills: [], plugins: []});
  const enabledPlugins = new Set((registry.plugins || []).filter(p => p && p.enabled !== false).map(p => p.id));
  const active = (registry.skills || []).filter(s =>
    s && s.enabled !== false && (!s.pluginId || enabledPlugins.has(s.pluginId)) &&
    matchesTarget(s.targets, agentId) && matchesScope(s.scope, cwd),
  );
  if (active.length === 0) return "";
  const lines = active.slice(0, 16).map(s => `- ${s.name}: ${s.description || "无描述"} (路径: ${s.dirPath}/SKILL.md)`);
  return `<available_skills>\n${lines.join("\n")}\n</available_skills>`;
}

/** 探测单个 MCP 服务器（支持 Stdio 与 HTTP）的可用性、延迟与工具清单。 */
export async function probeMcpServer({server, secrets}) {
  const started = Date.now();
  const srvSecrets = secrets || {};
  const cwd = typeof server.scope === "string" && server.scope.startsWith("project:") ? server.scope.slice(8) : HOME;
  const userEnv = await loadEffectiveUserEnv(cwd);
  const env = {...process.env, ...userEnv, ...downloadEnvironment(await readDownloadNetwork(), {...process.env, ...userEnv}), ...(server.env || {})};
  for (const k of (server.secretEnvKeys || [])) {
    if (srvSecrets[k]) env[k] = String(srvSecrets[k]);
  }
  const headers = {...(server.headers || {})};
  for (const k of (server.secretHeaderKeys || [])) {
    if (srvSecrets[k]) headers[k] = String(srvSecrets[k]);
  }
  if (server.transport === "http") {
    const parseJsonRpcPayload = (rawText) => {
      const trimmed = String(rawText || "").trim();
      if (!trimmed) return null;
      try { return JSON.parse(trimmed); } catch {}
      // Streamable HTTP / SSE 响应中提取 data: JSON 行
      let last = null;
      for (const line of trimmed.split(/\r?\n/)) {
        if (line.startsWith("data:")) {
          const chunk = line.slice(5).trim();
          if (chunk && chunk !== "[DONE]") {
            try { last = JSON.parse(chunk); } catch {}
          }
        }
      }
      return last;
    };
    try {
      const baseHeaders = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", ...headers};
      const resp = await fetch(server.url, {
        method: "POST",
        headers: baseHeaders,
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          method: "initialize",
          params: {protocolVersion: "2025-06-18", capabilities: {}, clientInfo: {name: "solbraid-probe", version: "1.0.0"}},
        }),
        signal: AbortSignal.timeout(10_000),
      });
      const latencyMs = Date.now() - started;
      if (!resp.ok) return {ok: false, latencyMs, tools: [], error: `HTTP ${resp.status} ${resp.statusText}`};
      const sessionId = resp.headers.get("mcp-session-id") || resp.headers.get("Mcp-Session-Id");
      await resp.text().catch(() => "");
      const reqHeaders = sessionId ? {...baseHeaders, "Mcp-Session-Id": sessionId} : baseHeaders;
      // 尝试继续执行 notifications/initialized 与 tools/list，获取真实工具清单
      let tools = [];
      try {
        await fetch(server.url, {
          method: "POST",
          headers: reqHeaders,
          body: JSON.stringify({jsonrpc: "2.0", method: "notifications/initialized"}),
          signal: AbortSignal.timeout(4_000),
        }).then(r => r.text().catch(() => {})).catch(() => {});
        const toolsResp = await fetch(server.url, {
          method: "POST",
          headers: reqHeaders,
          body: JSON.stringify({jsonrpc: "2.0", id: 2, method: "tools/list", params: {}}),
          signal: AbortSignal.timeout(6_000),
        });
        if (toolsResp.ok) {
          const parsed = parseJsonRpcPayload(await toolsResp.text());
          if (Array.isArray(parsed?.result?.tools)) {
            tools = parsed.result.tools.map(t => String(t?.name || "")).filter(Boolean);
          }
        }
      } catch {}
      return {
        ok: true,
        latencyMs,
        tools,
        message: tools.length > 0 ? `HTTP ${resp.status} 已连接，发现 ${tools.length} 个工具` : `HTTP ${resp.status} 连通正常`,
      };
    } catch (err) {
      return {ok: false, latencyMs: Date.now() - started, tools: [], error: redactNetworkText(err.message || String(err))};
    }
  }
  if (!server.command) return {ok: false, latencyMs: 0, tools: [], error: "缺少启动命令"};
  return await new Promise(resolve => {
    let settled = false;
    const child = spawn(server.command, Array.isArray(server.args) ? server.args.map(String) : [], {
      env,
      cwd,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let buf = "";
    let errBuf = "";
    const finish = (res) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { child.kill("SIGTERM"); } catch {}
      setTimeout(() => { try { child.kill("SIGKILL"); } catch {} }, 600);
      resolve({...res, latencyMs: Date.now() - started});
    };
    const timer = setTimeout(() => finish({ok: false, tools: [], error: errBuf.trim().slice(0, 240) || "MCP 握手超时 (10s)"}), 10_000);
    child.on("error", err => finish({ok: false, tools: [], error: err.message}));
    child.stdin.on("error", err => finish({ok: false, tools: [], error: err.message}));
    child.stderr.on("data", d => { errBuf = redactNetworkText(errBuf + d.toString("utf8")).slice(-1000); });
    child.stdout.on("data", chunk => {
      buf += chunk.toString("utf8");
      if (buf.length > 4 * 1024 * 1024) { finish({ok: false, tools: [], error: "MCP 响应超过 4 MB"}); return; }
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith("{")) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.error && (msg.id === 1 || msg.id === 2)) {
          finish({ok: false, tools: [], error: msg.error.message || "MCP 返回错误"});
        } else if (msg.id === 1 && msg.result) {
          child.stdin.write(JSON.stringify({jsonrpc: "2.0", method: "notifications/initialized"}) + "\n");
          child.stdin.write(JSON.stringify({jsonrpc: "2.0", id: 2, method: "tools/list", params: {}}) + "\n");
        } else if (msg.id === 2 && Array.isArray(msg.result?.tools)) {
          const tools = msg.result.tools.map(t => String(t?.name || "")).filter(Boolean);
          finish({ok: true, tools, message: `已连接，发现 ${tools.length} 个工具`});
        } else if (msg.id === 1 || msg.id === 2) {
          finish({ok: false, tools: [], error: "MCP 响应格式无效"});
        }
        if (settled) return;
      }
    });
    child.on("exit", code => {
      if (!settled) finish({ok: false, tools: [], error: errBuf.trim().slice(0, 240) || `进程退出 (${code})`});
    });
    child.stdin.write(JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {protocolVersion: "2025-06-18", capabilities: {}, clientInfo: {name: "solbraid-probe", version: "1.0.0"}},
    }) + "\n");
  });
}

async function scanSkillsInDir(baseDir, source) {
  const out = [];
  const entries = await fs.readdir(baseDir, {withFileTypes: true}).catch(() => []);
  for (const e of entries) {
    if (!e.isDirectory() && !e.isSymbolicLink()) continue;
    if (e.name.startsWith("solbraid-") && source === "workspace") continue;
    const dirPath = path.join(baseDir, e.name);
    const skillMd = path.join(dirPath, "SKILL.md");
    try {
      const raw = await fs.readFile(skillMd, "utf8");
      const parsed = parseSkillFrontmatter(raw, e.name);
      out.push({id: e.name, name: parsed.name, description: parsed.description, body: parsed.body, dirPath, source});
    } catch {}
  }
  return out;
}

export async function listDiscoveredSkills({cwd}) {
  const managed = await scanSkillsInDir(SKILLS_DIR, "local");
  const workspace = [];
  if (cwd) {
    for (const rel of [".agents/skills", ".claude/skills", "skills"]) {
      const found = await scanSkillsInDir(path.join(cwd, rel), "workspace");
      workspace.push(...found);
    }
  }
  return {managed, workspace};
}

export async function saveLocalSkill({id, name, description, body}) {
  const safeId = sanitizeId(id || name);
  const dirPath = path.join(SKILLS_DIR, safeId);
  await fs.mkdir(dirPath, {recursive: true});
  await fs.writeFile(path.join(dirPath, "SKILL.md"), buildSkillMarkdown(name || safeId, description || "", body || ""), "utf8");
  return {id: safeId, name: name || safeId, description: description || "", dirPath};
}

export async function deleteLocalSkill({id}) {
  const safeId = sanitizeId(id);
  const dirPath = path.join(SKILLS_DIR, safeId);
  await fs.rm(dirPath, {recursive: true, force: true});
  return {ok: true};
}

async function copySafeDir(src, dst) {
  await fs.mkdir(dst, {recursive: true});
  const entries = await fs.readdir(src, {withFileTypes: true});
  for (const e of entries) {
    if (e.name === ".git") continue;
    const s = path.join(src, e.name);
    const d = path.join(dst, e.name);
    const lst = await fs.lstat(s);
    if (lst.isSymbolicLink()) continue;
    if (lst.isDirectory()) await copySafeDir(s, d);
    else if (lst.isFile()) await fs.copyFile(s, d);
  }
}

async function importSourceDirectory(cloneDir, subPath, allowSkillFile = false) {
  let candidate = path.resolve(cloneDir, subPath || ".");
  const real = await fs.realpath(candidate);
  const root = await fs.realpath(cloneDir);
  if (real !== root && !real.startsWith(root + path.sep)) throw new Error("导入路径指向仓库外，已停止导入");
  const stat = await fs.stat(real);
  if (allowSkillFile && stat.isFile() && path.basename(candidate) === "SKILL.md") candidate = path.dirname(candidate);
  else if (!stat.isDirectory()) throw new Error("导入路径必须是目录或 SKILL.md");
  return candidate;
}

const publishingDirectories = new Set();

async function publishImportedDirectory(staged, destination) {
  if (publishingDirectories.has(destination)) throw new Error("此扩展正在更新，请稍后重试");
  publishingDirectories.add(destination);
  try { await replaceImportedDirectory(staged, destination); }
  finally { publishingDirectories.delete(destination); }
}

async function replaceImportedDirectory(staged, destination) {
  await fs.mkdir(path.dirname(destination), {recursive: true});
  const backup = destination + ".previous-" + randomUUID();
  let backedUp = false;
  try {
    try { await fs.rename(destination, backup); backedUp = true; }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    await fs.rename(staged, destination);
  } catch (error) {
    if (backedUp) await fs.rename(backup, destination);
    throw error;
  }
  if (backedUp) {
    try { await fs.rm(backup, {recursive: true, force: true}); }
    catch (error) { throw new Error("扩展已安装，但旧版本清理失败，请检查扩展目录", {cause: error}); }
  }
}

function parseGitHubUrl(rawUrl) {
  const u = new URL(String(rawUrl || "").trim());
  if (u.protocol !== "https:" || !/^(?:www\.)?github\.com$/i.test(u.hostname)) {
    throw new Error("目前仅支持 https://github.com/... 仓库链接");
  }
  const parts = u.pathname.replace(/^\/+|\/+$/g, "").split("/");
  if (parts.length < 2) throw new Error("GitHub 链接格式不完整");
  const owner = parts[0];
  const repo = parts[1].replace(/\.git$/i, "");
  let branch = null;
  let subPath = "";
  if (parts[2] === "tree" || parts[2] === "blob") {
    branch = parts[3] || null;
    subPath = parts.slice(4).join("/");
  }
  return {cloneUrl: `https://github.com/${owner}/${repo}.git`, owner, repo, branch, subPath};
}

/** 从 GitHub 仓库或子路径安全浅克隆导入 SKILL.md。 */
export async function importSkillFromGitHub({url, skillId, env, githubMirrorPrefix}) {
  const {cloneUrl, repo, branch, subPath} = parseGitHubUrl(url);
  const effectiveUrl = githubDownloadUrl(cloneUrl, {githubMirrorPrefix});
  const tmpDir = path.join(EXT_ROOT, `.tmp-skill-${randomUUID()}`);
  const stagedDir = path.join(EXT_ROOT, `.stage-skill-${randomUUID()}`);
  try {
    const args = ["clone", "--depth", "1", ...(branch ? ["--branch", branch] : []), effectiveUrl, tmpDir];
    await execFileAsync("git", args, {timeout: 90_000, env: {...(env || process.env), GIT_TERMINAL_PROMPT: "0"}});
    const srcDir = await importSourceDirectory(tmpDir, subPath, true);
    await copySafeDir(srcDir, stagedDir);
    const raw = await fs.readFile(path.join(stagedDir, "SKILL.md"), "utf8");
    const parsed = parseSkillFrontmatter(raw, path.basename(srcDir) || repo);
    const safeId = sanitizeId(skillId || parsed.name || repo);
    const dstDir = path.join(SKILLS_DIR, safeId);
    await publishImportedDirectory(stagedDir, dstDir);
    return {id: safeId, name: parsed.name, description: parsed.description, body: parsed.body, dirPath: dstDir, sourceUrl: url};
  } finally {
    await fs.rm(stagedDir, {recursive: true, force: true});
    await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
  }
}

/** 安装或转换 GitHub 插件仓库（兼容 plugin.json、.claude-plugin/plugin.json、.mcp.json 与 skills/）。 */
export async function importPluginFromGitHub({url, env, githubMirrorPrefix}) {
  const {cloneUrl, repo, branch, subPath} = parseGitHubUrl(url);
  const effectiveUrl = githubDownloadUrl(cloneUrl, {githubMirrorPrefix});
  const tmpDir = path.join(EXT_ROOT, `.tmp-plugin-${randomUUID()}`);
  const stagedDir = path.join(EXT_ROOT, `.stage-plugin-${randomUUID()}`);
  try {
    const args = ["clone", "--depth", "1", ...(branch ? ["--branch", branch] : []), effectiveUrl, tmpDir];
    await execFileAsync("git", args, {timeout: 120_000, env: {...(env || process.env), GIT_TERMINAL_PROMPT: "0"}});
    const rootDir = await importSourceDirectory(tmpDir, subPath);
    await copySafeDir(rootDir, stagedDir);
    const manifestRaw = await readJsonSafe(path.join(stagedDir, "plugin.json"), null)
      || await readJsonSafe(path.join(stagedDir, ".claude-plugin", "plugin.json"), null);
    const mcpRaw = await readJsonSafe(path.join(stagedDir, ".mcp.json"), null);
    const safeId = sanitizeId(manifestRaw?.id || manifestRaw?.name || repo);
    const dstDir = path.join(PLUGINS_DIR, safeId);

    // 扫描插件包内技能
    const skills = [];
    const scannedSkills = await scanSkillsInDir(path.join(stagedDir, "skills"), "plugin");
    for (const sk of scannedSkills) {
      skills.push({id: `${safeId}-${sanitizeId(sk.id)}`, name: sk.name, description: sk.description,
        dirPath: path.join(dstDir, path.relative(stagedDir, sk.dirPath)), pluginId: safeId});
    }
    const rootSkill = await fs.readFile(path.join(stagedDir, "SKILL.md"), "utf8").catch(() => null);
    if (rootSkill && skills.length === 0) {
      const p = parseSkillFrontmatter(rootSkill, safeId);
      skills.push({id: safeId, name: p.name, description: p.description, dirPath: dstDir, pluginId: safeId});
    }

    // 解析插件包内 MCP
    const mcpServers = [];
    if (Array.isArray(manifestRaw?.mcpServers)) {
      for (const m of manifestRaw.mcpServers) {
        if (!m?.id) continue;
        mcpServers.push({...m, id: `${safeId}-${sanitizeId(m.id)}`, pluginId: safeId});
      }
    } else if (mcpRaw?.mcpServers && typeof mcpRaw.mcpServers === "object") {
      for (const [k, v] of Object.entries(mcpRaw.mcpServers)) {
        if (!v || typeof v !== "object") continue;
        mcpServers.push({
          id: `${safeId}-${sanitizeId(k)}`,
          name: k,
          transport: v.url ? "http" : "stdio",
          command: v.command || null,
          args: Array.isArray(v.args) ? v.args : [],
          url: v.url || null,
          env: v.env || {},
          headers: v.headers || {},
          pluginId: safeId,
        });
      }
    }

    await publishImportedDirectory(stagedDir, dstDir);
    return {
      id: safeId,
      name: manifestRaw?.name || repo,
      version: manifestRaw?.version || "1.0.0",
      description: manifestRaw?.description || `从 ${url} 导入的插件包`,
      sourceUrl: url,
      dirPath: dstDir,
      requiredSecrets: Array.isArray(manifestRaw?.requiredSecrets) ? manifestRaw.requiredSecrets : [],
      mcpServers,
      skills,
      commands: Array.isArray(manifestRaw?.commands) ? manifestRaw.commands : [],
    };
  } finally {
    await fs.rm(stagedDir, {recursive: true, force: true});
    await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
  }
}

/** 安装内置插件包（将捆绑的技能落盘到 plugins/<id>/skills/）。 */
export async function installBuiltinPluginFiles({pluginId, skills}) {
  const safeId = sanitizeId(pluginId);
  const dstDir = path.join(PLUGINS_DIR, safeId);
  await fs.mkdir(dstDir, {recursive: true});
  const installedSkills = [];
  for (const sk of (skills || [])) {
    const skId = sanitizeId(sk.id || sk.name);
    const skDir = path.join(dstDir, "skills", skId);
    await fs.mkdir(skDir, {recursive: true});
    await fs.writeFile(path.join(skDir, "SKILL.md"), buildSkillMarkdown(sk.name, sk.description, sk.body), "utf8");
    installedSkills.push({id: `${safeId}-${skId}`, name: sk.name, description: sk.description, dirPath: skDir, pluginId: safeId});
  }
  return {ok: true, dirPath: dstDir, skills: installedSkills};
}

export async function uninstallPluginFiles({pluginId}) {
  const safeId = sanitizeId(pluginId);
  await fs.rm(path.join(PLUGINS_DIR, safeId), {recursive: true, force: true});
  return {ok: true};
}
