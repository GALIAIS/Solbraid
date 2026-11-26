// Solbraid Host 插件微内核（借鉴 Cordis Generation 生命周期 + Community Fabric Capability Broker）。
// 职责：静态 Manifest 校验、能力协商与授权、资源归属（effect/dispose）、故障隔离、安全模式与 Last-Known-Good 回滚。
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {randomBytes, randomUUID, timingSafeEqual} from "node:crypto";
import {pathToFileURL, fileURLToPath} from "node:url";
import {scanStaticSecurity} from "./ai-reviewer.mjs";
import {spawnIsolatedPluginWorker} from "./plugin-worker-host.mjs";
import {readDownloadNetwork, downloadEnvironment} from "./download-network.mjs";
import {
  safeReadTextFile,
  safeEditFile,
  safeCreateFile,
  streamSearchFile,
  revertFileCheckpoint,
  atomicWriteTextFile,
  resolveSafeHomePath,
} from "./safe-file-io.mjs";

const execFileAsync = promisify(execFile);

export const HOST_API_VERSION = "1.0.0";
export const MANIFEST_FILENAME = "solbraid-plugin.json";
export const ALLOWED_UI_SLOTS = Object.freeze([
  "nav.tab",
  "global.overlay",
  "home.topbar",
  "home.banner",
  "home.footer",
  "home.replace",
  "chat.topbar",
  "chat.banner",
  "chat.composerBar",
  "chat.messageFooter",
  "chat.replace",
  "files.toolbar",
  "files.banner",
  "files.replace",
  "ssh.toolbar",
  "ssh.banner",
  "ssh.replace",
  "terminal.toolbar",
  "settings.section",
  "settings.replace",
  "studio.toolbar",
]);
const ALLOWED_OVERLAY_ALIGNMENTS = new Set([
  "topStart", "topCenter", "topEnd",
  "centerStart", "center", "centerEnd",
  "bottomStart", "bottomCenter", "bottomEnd",
]);
export const ALLOWED_STUDIO_OVERRIDE_FILES = Object.freeze([
  "artifact-viewer.html",
  "artifact-renderer.js",
  "artifact-latex.js",
  "artifact-mermaid.js",
]);
const ALLOWED_SDUI_TYPES = new Set([
  "column", "row", "flow", "card", "section", "divider", "spacer",
  "text", "badge", "metric", "progress", "kv", "code", "list",
  "button", "toggle", "input",
]);
const ALLOWED_SDUI_ACTIONS = new Set(["rpc", "command", "panel", "surface", "navigate", "prompt", "copy", "refresh"]);
const NPM_PKG_SPEC_RE = /^(?:@[a-z0-9][a-z0-9._-]{0,63}\/)?[a-z0-9][a-z0-9._-]{0,63}(?:@[a-zA-Z0-9._^~>=<+-]{1,32})?$/;
const SNAPSHOT_ID_RE = /^[0-9]{10,16}-[a-f0-9]{6,16}$/;

export const KNOWN_CAPABILITIES = Object.freeze([
  "host.info",
  "log",
  "lifecycle",
  "storage.local",
  "settings.schema",
  "commands",
  "rpc",
  "events.observe",
  "ui.panel",
  "ui.composerAction",
  "ui.sdui",
  "ui.slot",
  "hooks.prompt",
  "services.provide",
  "services.consume",
  "workspace.read",
  "workspace.write",
  "agent.driver",
  "provider.translator",
  "deps.npm",
]);

export const ALWAYS_GRANTED_CAPABILITIES = new Set(["host.info", "log", "lifecycle"]);

export const HOST_DESCRIPTOR = Object.freeze({
  hostId: "solbraid-host",
  apiVersion: HOST_API_VERSION,
  manifestVersion: "1.0.0",
  executionTier: "trusted-in-process",
  capabilities: KNOWN_CAPABILITIES,
  note: "Host 内核插件运行于同进程受信任 Node.js 环境；capability 用于兼容协商、用户授权与审计，不宣称操作系统级沙箱隔离。",
});

const PLUGIN_ID_RE = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const SEMVER_RE = /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$/;
const STORAGE_MAX_BYTES = 256 * 1024;
const ACTIVATE_TIMEOUT_MS = 8_000;

async function readJsonSafe(file, fallback) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch (err) {
    if (err?.code === "ENOENT") return fallback;
    throw err;
  }
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

/** 递归解析真实路径（包含不存在的末级文件及悬空符号链接），彻底杜绝符号链接逃逸。 */
async function canonicalizePath(targetPath, depth = 0) {
  if (depth > 16) throw new Error("路径符号链接嵌套层级过深");
  const abs = path.resolve(targetPath);
  try {
    return await fs.realpath(abs);
  } catch (err) {
    if (err?.code !== "ENOENT") throw err;
    const lst = await fs.lstat(abs).catch(() => null);
    if (lst && lst.isSymbolicLink()) {
      const linkTarget = await fs.readlink(abs);
      return await canonicalizePath(path.resolve(path.dirname(abs), linkTarget), depth + 1);
    }
    const parent = path.dirname(abs);
    if (parent === abs) return abs;
    const realParent = await canonicalizePath(parent, depth + 1);
    return path.join(realParent, path.basename(abs));
  }
}

function isWithinRealRoot(candidateReal, rootReal) {
  if (candidateReal === rootReal) return true;
  const prefix = rootReal.endsWith(path.sep) ? rootReal : rootReal + path.sep;
  return candidateReal.startsWith(prefix);
}

async function resolveContainedRealPath(targetPath, rootPath, errorMessage) {
  const rootReal = await canonicalizePath(rootPath);
  const candidateReal = await canonicalizePath(targetPath);
  if (!isWithinRealRoot(candidateReal, rootReal)) {
    throw new Error(errorMessage);
  }
  return candidateReal;
}

/** 静态校验 solbraid-plugin.json 清单（无需执行任何插件代码即可完成检查）。 */
export function validatePluginManifest(raw, {allowCore = false} = {}) {
  const errors = [];
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return {ok: false, errors: ["Manifest 必须是 JSON 对象"], manifest: null};
  }
  const id = String(raw.id || "").trim();
  if (!PLUGIN_ID_RE.test(id)) errors.push(`无效插件 ID：${id || "(空)"}`);
  const name = String(raw.name || "").trim();
  if (!name || name.length > 80) errors.push("插件名称必填且不超过 80 字符");
  const version = String(raw.version || "1.0.0").trim();
  if (!SEMVER_RE.test(version)) errors.push(`无效 SemVer 版本号：${version}`);
  const manifestVersion = String(raw.manifestVersion || "1.0.0").trim();
  if (!manifestVersion.startsWith("1.")) errors.push(`不支持的 manifestVersion：${manifestVersion}`);
  const apiVersion = String(raw.apiVersion || ">=1.0.0 <2.0.0").trim();
  if (!apiVersion.includes("1.")) errors.push(`不兼容的 apiVersion：${apiVersion}（宿主当前为 ${HOST_API_VERSION}）`);

  const kindRaw = String(raw.kind || "local").trim().toLowerCase();
  const allowedKinds = allowCore ? ["core", "builtin", "community", "local"] : ["builtin", "community", "local"];
  const kind = allowedKinds.includes(kindRaw) ? kindRaw : "local";

  let entrypoint = null;
  if (raw.entrypoint !== undefined && raw.entrypoint !== null && String(raw.entrypoint).trim() !== "") {
    const ep = String(raw.entrypoint).trim().replace(/\\/g, "/");
    if (path.isAbsolute(ep) || ep.split("/").includes("..") || !/\.(?:mjs|js|cjs)$/.test(ep)) {
      errors.push(`非法入口路径：${ep}`);
    } else {
      entrypoint = ep;
    }
  }

  const capsObj = raw.capabilities && typeof raw.capabilities === "object" ? raw.capabilities : {};
  const required = Array.isArray(capsObj.required) ? [...new Set(capsObj.required.map(String))] : [];
  const optional = Array.isArray(capsObj.optional) ? [...new Set(capsObj.optional.map(String))] : [];
  const settingsSchema = raw.settingsSchema && typeof raw.settingsSchema === "object" && !Array.isArray(raw.settingsSchema)
    ? raw.settingsSchema
    : null;
  const contributes = raw.contributes && typeof raw.contributes === "object" ? raw.contributes : {};
  const commands = Array.isArray(contributes.commands)
    ? contributes.commands.map(c => ({
        name: String(c?.name || "").replace(/^\/+/, "").trim(),
        description: String(c?.description || "").trim(),
        promptTemplate: c?.promptTemplate ? String(c.promptTemplate) : null,
      })).filter(c => /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,39}$/.test(c.name))
    : [];
  const panels = Array.isArray(contributes.panels)
    ? contributes.panels.map(p => ({
        id: String(p?.id || "").trim(),
        title: String(p?.title || "").trim(),
        description: String(p?.description || "").trim(),
        htmlFile: p?.htmlFile ? String(p.htmlFile).trim().replace(/\\/g, "/") : null,
      })).filter(p => p.id && p.title && (!p.htmlFile || (!path.isAbsolute(p.htmlFile) && !p.htmlFile.split("/").includes(".."))))
    : [];

  const tierRaw = String(raw.executionTier || "trusted-in-process").trim();
  const executionTier = (kind !== "core" && tierRaw === "worker-isolated") ? "worker-isolated" : "trusted-in-process";

  return {
    ok: errors.length === 0,
    errors,
    manifest: errors.length === 0 ? {
      manifestVersion,
      apiVersion,
      id,
      name,
      version,
      description: String(raw.description || "").trim().slice(0, 280),
      author: String(raw.author || "").trim().slice(0, 80),
      kind,
      executionTier,
      entrypoint,
      capabilities: {required, optional},
      settingsSchema,
      contributes: {commands, panels},
    } : null,
  };
}

/** 校验声明式原生 Compose SDUI AST，防止越界深度或非法节点影响 Android 宿主稳定性。 */
export function validateSduiTree(tree) {
  const errors = [];
  const warnings = [];
  let nodeCount = 0;
  if (!tree || typeof tree !== "object" || Array.isArray(tree)) {
    return {ok: false, errors: ["SDUI 渲染结果必须是 JSON 对象"], warnings, nodeCount: 0};
  }
  const nodes = Array.isArray(tree.nodes) ? tree.nodes : (Array.isArray(tree.children) ? tree.children : null);
  if (!nodes) {
    return {ok: false, errors: ["SDUI 对象必须包含 nodes 数组"], warnings, nodeCount: 0};
  }
  const checkAction = (act, loc) => {
    if (!act) return;
    if (typeof act !== "object" || Array.isArray(act)) { errors.push(`${loc}.action 必须是对象`); return; }
    if (!ALLOWED_SDUI_ACTIONS.has(String(act.type || ""))) {
      errors.push(`${loc}.action.type 不受支持：${act.type}`);
    }
  };
  const walk = (node, depth, loc) => {
    nodeCount += 1;
    if (nodeCount > 240) { errors.push("SDUI 节点总数超过 240 上限"); return; }
    if (depth > 10) { errors.push(`${loc} 嵌套深度超过 10 层上限`); return; }
    if (!node || typeof node !== "object" || Array.isArray(node)) { errors.push(`${loc} 必须是节点对象`); return; }
    const t = String(node.type || "");
    if (!ALLOWED_SDUI_TYPES.has(t)) {
      errors.push(`${loc} 存在未知节点类型：${t || "(空)"}`);
      return;
    }
    checkAction(node.action, loc);
    if (t === "list" && Array.isArray(node.items)) {
      node.items.forEach((it, idx) => checkAction(it?.action, `${loc}.items[${idx}]`));
    }
    if (Array.isArray(node.children)) {
      node.children.forEach((c, idx) => walk(c, depth + 1, `${loc}.children[${idx}]`));
    }
  };
  nodes.forEach((n, i) => walk(n, 1, `nodes[${i}]`));
  return {ok: errors.length === 0, errors, warnings, nodeCount};
}

/** 能力协商（Fail-Closed）：required 能力缺失或未获授权时直接拒载，不伪装成功。 */
export function negotiateCapabilities(manifest, grantedSet = null) {
  const supported = new Set(HOST_DESCRIPTOR.capabilities);
  const missingRequired = [];
  const deniedRequired = [];
  const granted = new Set(ALWAYS_GRANTED_CAPABILITIES);
  const degradedOptional = [];

  for (const cap of manifest.capabilities.required) {
    if (!supported.has(cap)) {
      missingRequired.push(cap);
    } else if (grantedSet && !ALWAYS_GRANTED_CAPABILITIES.has(cap) && !grantedSet.has(cap)) {
      deniedRequired.push(cap);
    } else {
      granted.add(cap);
    }
  }
  for (const cap of manifest.capabilities.optional) {
    if (!supported.has(cap)) {
      degradedOptional.push({capability: cap, reason: "unsupported"});
    } else if (grantedSet && !ALWAYS_GRANTED_CAPABILITIES.has(cap) && !grantedSet.has(cap)) {
      degradedOptional.push({capability: cap, reason: "denied"});
    } else {
      granted.add(cap);
    }
  }
  return {
    compatible: missingRequired.length === 0 && deniedRequired.length === 0,
    missingRequired,
    deniedRequired,
    degradedOptional,
    granted: [...granted],
  };
}

function defaultSettingsFromSchema(schema) {
  const out = {};
  const props = schema?.properties;
  if (!props || typeof props !== "object") return out;
  for (const [k, def] of Object.entries(props)) {
    if (def && Object.prototype.hasOwnProperty.call(def, "default")) out[k] = def.default;
  }
  return out;
}

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const v of Object.values(value)) deepFreeze(v);
  return value;
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} 超时（>${ms}ms）`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Solbraid Host 插件微内核：管理单代 Generation 生命周期、服务总线、事件观察流、
 * 插件隔离容错、安全模式与 Last-Known-Good 自动快照回滚。
 */
export class PluginKernel {
  constructor({home = process.env.HOME || "/home/agent", onNotify = () => {}, activateTimeoutMs = ACTIVATE_TIMEOUT_MS} = {}) {
    this.home = home;
    this.activateTimeoutMs = Number(activateTimeoutMs) > 0 ? Number(activateTimeoutMs) : ACTIVATE_TIMEOUT_MS;
    this.pluginsRoot = path.join(home, ".galiais", "plugins");
    this.snapshotsRoot = path.join(home, ".galiais", "plugins-snapshots");
    this.studioOverridesRoot = path.join(home, ".galiais", "studio-overrides");
    this.cacheRoot = path.join(home, ".galiais", "plugins-cache");
    this.dataRoot = path.join(home, ".galiais", "plugins-data");
    this.configRoot = path.join(home, ".galiais", "plugins-config");
    this.stateFile = path.join(home, ".galiais", "plugins-state.json");
    this.lkgFile = path.join(home, ".galiais", "plugins-last-known-good.json");
    this.selfBuildConfigFile = path.join(home, ".galiais", "self-build-config.json");
    this.onNotify = onNotify;

    this.generationSeq = 0;
    this.generationId = "";
    this.builtins = []; // [{manifest, activate}]
    this.builtinMap = new Map(); // pluginId -> {manifest, activate}
    this.state = {safeMode: false, enabled: {}, grantedCaps: {}, updatedAt: 0};
    this.hasLastKnownGood = false;
    this.agentControlAvailable = false;
    this.studioOverrides = [];

    this.records = new Map();         // pluginId -> runtime record
    this.snapshotCounts = new Map();  // pluginId -> cached snapshot count
    this.services = new Map();        // serviceName -> {pluginId, impl}
    this.commands = new Map();        // `${pluginId}:${name}` -> {pluginId, name, description, promptTemplate, handler}
    this.rpcs = new Map();            // `plugin.${pluginId}.${method}` -> {pluginId, handler}
    this.panels = new Map();          // `${pluginId}:${panelId}` -> {pluginId, id, title, description, htmlFile, render}
    this.surfaces = new Map();        // `${pluginId}:${surfaceId}` -> {pluginId, id, title, description, render}
    this.uiSlots = new Map();         // `${pluginId}:${slotId}` -> {pluginId, id, slot, label, description, badge, surfaceId, panelId, command, render}
    this.composerActions = new Map(); // `${pluginId}:${actionId}` -> {pluginId, id, label, description, command, panelId}
    this.promptHooks = new Map();     // "beforePrompt" | "afterResponse" -> Set<{pluginId, fn}>
    this.observers = new Map();       // topic -> Set<{pluginId, fn}>
    this.drivers = new Map();         // driverId -> {pluginId, id, name, protocol, builtin, description, executable, execute, listModels}
    this.activeDriverRuns = new Map();// `${driverId}:${sessionId}` -> Set<AbortController>
    this.translators = new Map();     // translatorId -> {pluginId, id, name, description, baseProtocol, toUpstream, fromUpstream}
  }

  registerBuiltin(rawManifest, activate) {
    const checked = validatePluginManifest(rawManifest, {allowCore: true});
    if (!checked.ok) throw new Error(`内置插件清单无效：${checked.errors.join("; ")}`);
    const entry = {manifest: checked.manifest, activate};
    this.builtins.push(entry);
    this.builtinMap.set(checked.manifest.id, entry);
  }

  async loadState() {
    const raw = await readJsonSafe(this.stateFile, null);
    this.state = {
      safeMode: Boolean(raw?.safeMode),
      enabled: raw?.enabled && typeof raw.enabled === "object" ? {...raw.enabled} : {},
      grantedCaps: raw?.grantedCaps && typeof raw.grantedCaps === "object" ? {...raw.grantedCaps} : {},
      updatedAt: Number(raw?.updatedAt || 0),
    };
    const lkg = await readJsonSafe(this.lkgFile, null);
    this.hasLastKnownGood = Boolean(lkg && typeof lkg === "object");
  }

  async saveState() {
    this.state.updatedAt = Date.now();
    await writeJsonAtomic(this.stateFile, this.state);
  }

  async commitLastKnownGood() {
    const snapshot = {
      safeMode: false,
      enabled: {...this.state.enabled},
      grantedCaps: {...this.state.grantedCaps},
      committedAt: Date.now(),
      generationSeq: this.generationSeq,
    };
    await writeJsonAtomic(this.lkgFile, snapshot);
    this.hasLastKnownGood = true;
  }

  async discoverDiskPlugins() {
    const discovered = [];
    let entries = [];
    try {
      entries = await fs.readdir(this.pluginsRoot, {withFileTypes: true});
    } catch (err) {
      if (err?.code === "ENOENT") return discovered;
      throw err;
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
      const dir = path.join(this.pluginsRoot, entry.name);
      const manifestPath = path.join(dir, MANIFEST_FILENAME);
      let raw;
      try {
        raw = JSON.parse(await fs.readFile(manifestPath, "utf8"));
      } catch (err) {
        if (err?.code === "ENOENT") continue;
        discovered.push({dir, invalid: true, id: entry.name, error: `清单 JSON 解析失败：${err.message}`});
        continue;
      }
      const checked = validatePluginManifest(raw, {allowCore: false});
      if (!checked.ok) {
        discovered.push({dir, invalid: true, id: raw?.id || entry.name, error: checked.errors.join("; ")});
      } else {
        discovered.push({dir, invalid: false, manifest: checked.manifest});
      }
    }
    return discovered;
  }

  async boot() {
    await this.dispose();
    await this.loadState();
    this.generationSeq += 1;
    this.generationId = `gen-${this.generationSeq}-${randomUUID().slice(0, 8)}`;

    // 1. 先注册内置 core 与 builtin 插件（core 永远启用，保证宿主基本盘）
    for (const item of this.builtins) {
      const m = item.manifest;
      const defaultEnabled = m.kind === "core" ? true : (this.state.enabled[m.id] ?? true);
      const rec = this.#createRecord(m, {dir: null, activate: item.activate, enabled: m.kind === "core" ? true : defaultEnabled});
      this.records.set(m.id, rec);
      if (rec.enabled && (!this.state.safeMode || m.kind === "core")) {
        await this.#activateRecord(rec);
      } else if (this.state.safeMode && m.kind !== "core") {
        rec.status = "safe_mode_skipped";
      }
    }

    // 2. 扫描磁盘第三方/本地插件（安全模式下只做静态发现不激活）
    const diskList = await this.discoverDiskPlugins();
    let userFailureCount = 0;
    for (const item of diskList) {
      if (item.invalid) {
        userFailureCount += 1;
        this.records.set(item.id, {
          manifest: {
            id: item.id, name: item.id, version: "0.0.0", description: "", kind: "local",
            executionTier: "trusted-in-process", capabilities: {required: [], optional: []},
            settingsSchema: null, contributes: {commands: [], panels: []},
          },
          dir: item.dir, enabled: false, status: "invalid", error: item.error,
          grantedCapabilities: [], degradedOptional: [], logs: [], effects: [], settings: {},
          settingsListeners: new Set(), abortController: null,
        });
        continue;
      }
      const m = item.manifest;
      const existing = this.records.get(m.id);
      let overriddenBuiltin = false;
      if (existing) {
        // 核心服务绝对禁止磁盘覆写；非 core 的内置可选插件允许磁盘覆写（支持 Agent 热升级并可一键恢复原版）
        if (existing.manifest.kind === "core") continue;
        if (existing.manifest.kind === "builtin") {
          await this.#runEffects(existing);
          overriddenBuiltin = true;
        } else {
          continue;
        }
      }
      const enabled = this.state.enabled[m.id] ?? true;
      const rec = this.#createRecord(m, {dir: item.dir, activate: null, enabled, overriddenBuiltin});
      this.records.set(m.id, rec);
      if (this.state.safeMode) {
        rec.status = "safe_mode_skipped";
      } else if (enabled) {
        await this.#activateRecord(rec);
        if (rec.status === "failed") userFailureCount += 1;
      }
    }
    await this.#refreshSnapshotCounts().catch(() => {});
    await this.#refreshStudioOverrides().catch(() => {});

    if (!this.state.safeMode && userFailureCount === 0) {
      await this.commitLastKnownGood().catch(() => {});
    }
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, safeMode: this.state.safeMode});
    return this.snapshot();
  }

  #createRecord(manifest, {dir, activate, enabled, overriddenBuiltin = false}) {
    return {
      manifest, dir, activate, enabled,
      overriddenBuiltin: Boolean(overriddenBuiltin),
      status: enabled ? "pending" : "disabled",
      error: null,
      grantedCapabilities: [],
      degradedOptional: [],
      logs: [],
      effects: [],
      settings: defaultSettingsFromSchema(manifest.settingsSchema),
      settingsListeners: new Set(),
      abortController: null,
    };
  }

  #pushLog(rec, level, message) {
    rec.logs.push({ts: Date.now(), level, message: String(message ?? "").slice(0, 400)});
    if (rec.logs.length > 80) rec.logs.shift();
  }

  async #loadPluginSettings(rec) {
    const defaults = defaultSettingsFromSchema(rec.manifest.settingsSchema);
    const saved = await readJsonSafe(path.join(this.configRoot, `${rec.manifest.id}.json`), {});
    rec.settings = {...defaults, ...(saved && typeof saved === "object" ? saved : {})};
  }

  #buildContext(rec, grantedList) {
    const granted = new Set(grantedList);
    const pluginId = rec.manifest.id;
    const kernel = this;
    const storageFile = path.join(this.dataRoot, `${pluginId}.json`);
    const registerEffect = (cleanup, label = "cleanup") => {
      if (typeof cleanup === "function") rec.effects.push({cleanup, label});
    };

    const ctx = {
      host: Object.freeze({
        hostId: HOST_DESCRIPTOR.hostId,
        apiVersion: HOST_API_VERSION,
        generationId: this.generationId,
        generationSeq: this.generationSeq,
        executionTier: HOST_DESCRIPTOR.executionTier,
        home: this.home,
      }),
      pluginId,
      signal: rec.abortController.signal,
      log: Object.freeze({
        info: msg => this.#pushLog(rec, "info", msg),
        warn: msg => this.#pushLog(rec, "warn", msg),
        error: msg => this.#pushLog(rec, "error", msg),
      }),
      effect: registerEffect,
      settings: Object.freeze({
        get: () => ({...rec.settings}),
        onChange: fn => {
          if (typeof fn !== "function") return () => {};
          rec.settingsListeners.add(fn);
          const off = () => rec.settingsListeners.delete(fn);
          registerEffect(off, "settings.onChange");
          return off;
        },
      }),
    };

    if (granted.has("storage.local")) {
      ctx.storage = Object.freeze({
        async get(key) {
          const data = await readJsonSafe(storageFile, {});
          return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : undefined;
        },
        async set(key, value) {
          const data = await readJsonSafe(storageFile, {});
          data[String(key)] = value;
          if (Buffer.byteLength(JSON.stringify(data), "utf8") > STORAGE_MAX_BYTES) {
            throw new Error(`插件 ${pluginId} 本地存储超出 256 KB 配额`);
          }
          await writeJsonAtomic(storageFile, data);
        },
        async delete(key) {
          const data = await readJsonSafe(storageFile, {});
          delete data[String(key)];
          await writeJsonAtomic(storageFile, data);
        },
        async list() {
          return Object.keys(await readJsonSafe(storageFile, {}));
        },
      });
    }

    if (granted.has("commands")) {
      ctx.commands = Object.freeze({
        register(name, spec = {}) {
          const clean = String(name || "").replace(/^\/+/, "").trim();
          if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,39}$/.test(clean)) throw new Error(`非法命令名：${name}`);
          const key = `${pluginId}:${clean}`;
          kernel.commands.set(key, {
            pluginId, name: clean,
            description: String(spec.description || "").trim(),
            promptTemplate: spec.promptTemplate ? String(spec.promptTemplate) : null,
            handler: typeof spec.handler === "function" ? spec.handler : null,
          });
          registerEffect(() => kernel.commands.delete(key), `command:${clean}`);
        },
      });
    }

    if (granted.has("rpc")) {
      ctx.rpc = Object.freeze({
        register(method, handler) {
          const clean = String(method || "").trim();
          if (!/^[a-zA-Z0-9._-]{1,48}$/.test(clean) || typeof handler !== "function") throw new Error(`非法 RPC 注册：${method}`);
          const full = `plugin.${pluginId}.${clean}`;
          kernel.rpcs.set(full, {pluginId, handler});
          registerEffect(() => kernel.rpcs.delete(full), `rpc:${full}`);
        },
      });
    }

    if (granted.has("events.observe")) {
      ctx.events = Object.freeze({
        observe(topic, listener) {
          const t = String(topic || "").trim();
          if (!t || typeof listener !== "function") return () => {};
          let set = kernel.observers.get(t);
          if (!set) { set = new Set(); kernel.observers.set(t, set); }
          const entry = {pluginId, fn: listener};
          set.add(entry);
          const off = () => set.delete(entry);
          registerEffect(off, `observe:${t}`);
          return off;
        },
      });
    }

    if (granted.has("ui.panel")) {
      ctx.panels = Object.freeze({
        register(spec) {
          const pid = String(spec?.id || "").trim();
          if (!pid) throw new Error("面板 ID 不能为空");
          const key = `${pluginId}:${pid}`;
          kernel.panels.set(key, {
            pluginId, id: pid,
            title: String(spec.title || pid),
            description: String(spec.description || ""),
            htmlFile: spec.htmlFile || null,
            render: typeof spec.render === "function" ? spec.render : null,
          });
          registerEffect(() => kernel.panels.delete(key), `panel:${pid}`);
        },
      });
    }

    if (granted.has("services.provide") || granted.has("services.consume")) {
      ctx.services = Object.freeze({
        provide(name, impl) {
          if (!granted.has("services.provide")) throw new Error(`插件 ${pluginId} 未获 services.provide 授权`);
          if (kernel.services.has(name)) throw new Error(`服务 ${name} 已被 ${kernel.services.get(name).pluginId} 注册`);
          kernel.services.set(name, {pluginId, impl});
          registerEffect(() => {
            if (kernel.services.get(name)?.pluginId === pluginId) kernel.services.delete(name);
          }, `service:${name}`);
        },
        get(name) {
          if (!granted.has("services.consume") && !granted.has("services.provide")) return undefined;
          return kernel.services.get(name)?.impl;
        },
      });
    }

    if (granted.has("ui.composerAction")) {
      ctx.composer = Object.freeze({
        registerAction(spec) {
          const aid = String(spec?.id || "").trim();
          const label = String(spec?.label || "").trim().slice(0, 32);
          if (!aid || !label) throw new Error("输入栏动作需要 id 与 label");
          const key = `${pluginId}:${aid}`;
          kernel.composerActions.set(key, {
            pluginId,
            id: aid,
            label,
            description: String(spec?.description || "").trim().slice(0, 120),
            command: spec?.command ? String(spec.command).replace(/^\/+/, "").trim() : null,
            panelId: spec?.panelId ? String(spec.panelId).trim() : null,
          });
          registerEffect(() => kernel.composerActions.delete(key), `composerAction:${aid}`);
        },
      });
    }

    if (granted.has("hooks.prompt")) {
      const registerHook = (stage, fn) => {
        if (typeof fn !== "function") return () => {};
        let set = kernel.promptHooks.get(stage);
        if (!set) { set = new Set(); kernel.promptHooks.set(stage, set); }
        const entry = {pluginId, fn};
        set.add(entry);
        const off = () => set.delete(entry);
        registerEffect(off, `hook:${stage}`);
        return off;
      };
      ctx.hooks = Object.freeze({
        onBeforePrompt: fn => registerHook("beforePrompt", fn),
        onAfterResponse: fn => registerHook("afterResponse", fn),
      });
    }

    if (granted.has("workspace.read") || granted.has("workspace.write")) {
      const homeRoot = path.resolve(this.home);
      const workspaceRoot = path.resolve(path.join(homeRoot, "workspace"));
      const wsApi = {};
      if (granted.has("workspace.read")) {
        wsApi.readDir = async targetDir => {
          const realTarget = await resolveContainedRealPath(
            targetDir || workspaceRoot,
            homeRoot,
            "禁止越界读取工作区之外路径（含符号链接重定向）",
          );
          const list = await fs.readdir(realTarget, {withFileTypes: true});
          return list.map(e => ({name: e.name, isDirectory: e.isDirectory(), isFile: e.isFile()}));
        };
        wsApi.readText = async (filePath, maxBytes = 128 * 1024) => {
          const realTarget = await resolveContainedRealPath(
            filePath,
            homeRoot,
            "禁止越界读取工作区之外路径（含符号链接重定向）",
          );
          const buf = await fs.readFile(realTarget);
          return buf.subarray(0, maxBytes).toString("utf8");
        };
      }
      if (granted.has("workspace.write")) {
        wsApi.writeText = async (filePath, text) => {
          const realTarget = await resolveContainedRealPath(
            filePath,
            workspaceRoot,
            "workspace.write 仅允许写入 ~/workspace 目录内真实路径（禁止符号链接越界）",
          );
          const content = String(text ?? "");
          if (Buffer.byteLength(content, "utf8") > 512 * 1024) throw new Error("单次写入超过 512 KB 上限");
          await fs.mkdir(path.dirname(realTarget), {recursive: true});
          // 创建父目录后再次校验真实父路径，防范 TOCTOU 竞态与链接替换
          await resolveContainedRealPath(
            realTarget,
            workspaceRoot,
            "workspace.write 仅允许写入 ~/workspace 目录内真实路径（禁止符号链接越界）",
          );
          await fs.writeFile(realTarget, content, "utf8");
          return {ok: true, path: realTarget, bytes: Buffer.byteLength(content, "utf8")};
        };
      }
      ctx.workspace = Object.freeze(wsApi);
    }

    if (granted.has("ui.sdui") || granted.has("ui.slot")) {
      const uiApi = {};
      if (granted.has("ui.sdui")) {
        uiApi.registerSurface = spec => {
          const sid = String(spec?.id || "").trim();
          if (!sid) throw new Error("原生页面 surface.id 不能为空");
          const key = `${pluginId}:${sid}`;
          kernel.surfaces.set(key, {
            pluginId,
            id: sid,
            title: String(spec?.title || sid).trim().slice(0, 60),
            description: String(spec?.description || "").trim().slice(0, 160),
            render: typeof spec?.render === "function" ? spec.render : null,
          });
          registerEffect(() => kernel.surfaces.delete(key), `surface:${sid}`);
        };
      }
      if (granted.has("ui.slot")) {
        uiApi.registerSlot = spec => {
          const slotName = String(spec?.slot || "").trim();
          if (!ALLOWED_UI_SLOTS.includes(slotName)) {
            throw new Error(`不支持的原生挂载槽位：${slotName}（可选：${ALLOWED_UI_SLOTS.join(", ")}）`);
          }
          const sid = String(spec?.id || `${slotName}-${randomUUID().slice(0, 6)}`).trim();
          const label = String(spec?.label || "").trim().slice(0, 40);
          const rawRoutes = Array.isArray(spec?.routes)
            ? spec.routes.map(r => String(r || "").trim()).filter(Boolean).slice(0, 12)
            : [];
          const rawAlign = String(spec?.alignment || "bottomEnd").trim();
          const alignment = ALLOWED_OVERLAY_ALIGNMENTS.has(rawAlign) ? rawAlign : "bottomEnd";
          const clampInt = (v, min, max, fallback) => {
            const n = Number(v);
            return Number.isFinite(n) ? Math.max(min, Math.min(max, Math.round(n))) : fallback;
          };
          const offsetX = clampInt(spec?.offsetX, -300, 300, 0);
          const offsetY = clampInt(spec?.offsetY, -600, 600, slotName === "global.overlay" && alignment.startsWith("bottom") ? -96 : 0);
          const draggable = spec?.draggable === undefined ? slotName === "global.overlay" : Boolean(spec.draggable);
          const displayModeRaw = String(spec?.displayMode || "").trim().toLowerCase();
          const displayMode = ["pill", "card", "fab"].includes(displayModeRaw) ? displayModeRaw : "";
          const key = `${pluginId}:${sid}`;
          kernel.uiSlots.set(key, {
            pluginId,
            id: sid,
            slot: slotName,
            label,
            description: String(spec?.description || "").trim().slice(0, 140),
            badge: String(spec?.badge || "").trim().slice(0, 24),
            surfaceId: spec?.surfaceId ? String(spec.surfaceId).trim() : null,
            panelId: spec?.panelId ? String(spec.panelId).trim() : null,
            command: spec?.command ? String(spec.command).replace(/^\/+/, "").trim() : null,
            routes: rawRoutes,
            alignment,
            offsetX,
            offsetY,
            draggable,
            displayMode,
            render: typeof spec?.render === "function" ? spec.render : null,
          });
          registerEffect(() => kernel.uiSlots.delete(key), `uiSlot:${sid}`);
        };
      }
      ctx.ui = Object.freeze(uiApi);
    }

    if (granted.has("agent.driver")) {
      ctx.drivers = Object.freeze({
        register(spec) {
          const driverId = String(spec?.id || "").trim().toLowerCase();
          if (!/^[a-z0-9][a-z0-9._-]{1,47}$/.test(driverId)) throw new Error(`无效 Agent 驱动 ID：${driverId}`);
          const entry = {
            pluginId,
            id: driverId,
            name: String(spec?.name || driverId).trim(),
            protocol: String(spec?.protocol || (typeof spec?.execute === "function" ? "plugin-driver" : "acp")).trim(),
            builtin: Boolean(spec?.builtin),
            description: String(spec?.description || "").trim(),
            executable: typeof spec?.execute === "function",
            execute: typeof spec?.execute === "function" ? spec.execute : null,
            listModels: typeof spec?.listModels === "function" ? spec.listModels : null,
          };
          kernel.drivers.set(driverId, entry);
          registerEffect(() => {
            if (kernel.drivers.get(driverId)?.pluginId === pluginId) kernel.drivers.delete(driverId);
          }, `driver:${driverId}`);
        },
        list() {
          return [...kernel.drivers.values()].map(({execute: _e, listModels: _m, ...rest}) => rest);
        },
      });
    }

    if (granted.has("provider.translator")) {
      ctx.providers = Object.freeze({
        registerTranslator(spec) {
          const tid = String(spec?.id || "").trim().toLowerCase();
          if (!/^[a-z0-9][a-z0-9._-]{1,47}$/.test(tid)) throw new Error(`无效协议转换器 ID：${tid}`);
          if (typeof spec?.toUpstream !== "function") throw new Error("协议转换器必须提供 toUpstream(request, context) 函数");
          const entry = {
            pluginId,
            id: tid,
            name: String(spec?.name || tid).trim(),
            description: String(spec?.description || "").trim().slice(0, 160),
            baseProtocol: String(spec?.baseProtocol || "openai_chat").trim(),
            toUpstream: spec.toUpstream,
            fromUpstream: typeof spec?.fromUpstream === "function" ? spec.fromUpstream : null,
          };
          kernel.translators.set(tid, entry);
          registerEffect(() => {
            if (kernel.translators.get(tid)?.pluginId === pluginId) kernel.translators.delete(tid);
          }, `translator:${tid}`);
        },
      });
    }

    return Object.freeze(ctx);
  }

  /** 在独立 worker_threads 中激活插件，并通过代理将全部 SDK 注册与调用安全桥接到主微内核。 */
  async #activateInWorker(rec, entryReal, grantedList) {
    const m = rec.manifest;
    const pluginId = m.id;
    const kernel = this;
    const ctx = this.#buildContext(rec, grantedList);
    const modUrl = `${pathToFileURL(entryReal).href}?gen=${this.generationSeq}&t=${Date.now()}`;
    const workerHandle = spawnIsolatedPluginWorker({
      pluginId,
      entryUrl: modUrl,
      hostInfo: ctx.host,
      grantedCapabilities: grantedList,
      settings: rec.settings,
      onLog: (level, message) => this.#pushLog(rec, level, message),
      onCrash: reason => {
        if (rec.status === "active") {
          rec.status = "failed";
          rec.error = reason;
          this.#pushLog(rec, "error", reason);
          void this.#runEffects(rec).catch(() => {}).finally(() => {
            this.onNotify("plugins/changed", {generationId: this.generationId, pluginId, status: "failed", error: reason});
          });
        }
      },
      onHostCall: async (op, payload) => {
        if (op === "storage.get") return ctx.storage ? await ctx.storage.get(payload.key) : undefined;
        if (op === "storage.set") { if (!ctx.storage) throw new Error("缺少 storage.local 权限"); return await ctx.storage.set(payload.key, payload.value); }
        if (op === "storage.delete") { if (!ctx.storage) throw new Error("缺少 storage.local 权限"); return await ctx.storage.delete(payload.key); }
        if (op === "storage.list") return ctx.storage ? await ctx.storage.list() : [];
        if (op === "workspace.readDir") { if (!ctx.workspace?.readDir) throw new Error("缺少 workspace.read 权限"); return await ctx.workspace.readDir(payload.targetDir); }
        if (op === "workspace.readText") { if (!ctx.workspace?.readText) throw new Error("缺少 workspace.read 权限"); return await ctx.workspace.readText(payload.filePath, payload.maxBytes); }
        if (op === "workspace.writeText") { if (!ctx.workspace?.writeText) throw new Error("缺少 workspace.write 权限"); return await ctx.workspace.writeText(payload.filePath, payload.text); }
        throw new Error(`未知宿主代理调用：${op}`);
      },
      onRegister: (kind, spec) => {
        if (kind === "command" && ctx.commands) {
          ctx.commands.register(spec.name, {
            description: spec.description,
            promptTemplate: spec.promptTemplate,
            handler: spec.hasHandler ? (cmdCtx => workerHandle.invoke("runCommand", {command: spec.name, ctx: cmdCtx}, 15_000)) : null,
          });
        } else if (kind === "panel" && ctx.panels) {
          ctx.panels.register({
            id: spec.id, title: spec.title, description: spec.description, htmlFile: spec.htmlFile,
            render: spec.hasRender ? (pCtx => workerHandle.invoke("renderPanel", {panelId: spec.id, ctx: pCtx}, 15_000)) : null,
          });
        } else if (kind === "composerAction" && ctx.composer) {
          ctx.composer.registerAction(spec);
        } else if (kind === "surface" && ctx.ui?.registerSurface) {
          ctx.ui.registerSurface({
            id: spec.id, title: spec.title, description: spec.description,
            render: sCtx => workerHandle.invoke("renderSurface", {surfaceId: spec.id, ctx: sCtx}, 10_000),
          });
        } else if (kind === "slot" && ctx.ui?.registerSlot) {
          ctx.ui.registerSlot({
            ...spec,
            render: spec.hasRender ? (sCtx => workerHandle.invoke("renderSlot", {slotId: spec.id, ctx: sCtx}, 8_000)) : null,
          });
        } else if (kind === "rpc" && ctx.rpc) {
          ctx.rpc.register(spec.method, params => workerHandle.invoke("invokeRpc", {method: spec.method, params}, 15_000));
        } else if (kind === "hook" && ctx.hooks) {
          if (spec.stage === "beforePrompt") {
            ctx.hooks.onBeforePrompt(async payload => {
              const r = await workerHandle.invoke("beforePrompt", {...payload, hookId: spec.hookId}, 3_000);
              return {text: r?.text, notice: Array.isArray(r?.notices) && r.notices.length ? r.notices.join("；") : undefined};
            });
          } else if (spec.stage === "afterResponse") {
            ctx.hooks.onAfterResponse(async payload => {
              const r = await workerHandle.invoke("afterResponse", {...payload, hookId: spec.hookId}, 3_000);
              return {notice: Array.isArray(r?.notices) && r.notices.length ? r.notices.join("；") : undefined};
            });
          }
        } else if (kind === "driver" && ctx.drivers) {
          ctx.drivers.register({
            ...spec,
            execute: spec.executable ? (drvCtx => {
              const {emitChunk, emitThought, emitTool, emitPlan, signal, ...plainCtx} = drvCtx;
              return workerHandle.invoke("invokeDriver", {driverId: spec.id, ctx: plainCtx}, 120_000, ev => {
                if (ev?.kind === "chunk") emitChunk?.(ev.text);
                else if (ev?.kind === "thought") emitThought?.(ev.text);
                else if (ev?.kind === "tool") emitTool?.(ev.tool);
                else if (ev?.kind === "plan") emitPlan?.(ev.plan);
              }, signal || null);
            }) : null,
            listModels: () => workerHandle.invoke("listDriverModels", {driverId: spec.id}, 8_000),
          });
        } else if (kind === "translator" && ctx.providers) {
          ctx.providers.registerTranslator({
            ...spec,
            toUpstream: (request, context) => workerHandle.invoke("translateRequest", {translatorId: spec.id, request, context}, 5_000),
            fromUpstream: (response, context) => workerHandle.invoke("translateResponse", {translatorId: spec.id, response, context}, 5_000),
          });
        }
      },
    });
    rec.effects.push({cleanup: () => workerHandle.terminate(), label: `worker:${pluginId}`});
    const onSettings = next => { void workerHandle.invoke("updateSettings", {settings: next}, 3_000).catch(() => {}); };
    rec.settingsListeners.add(onSettings);
    rec.effects.push({cleanup: () => rec.settingsListeners.delete(onSettings), label: `worker-settings:${pluginId}`});
    await workerHandle.invoke("activate", {}, this.activateTimeoutMs);
  }

  /** 为同进程磁盘插件镜像当前代源码至独立缓存目录，彻底清除所有相对 import（如 ./helper.mjs）的 Node ESM 模块缓存。 */
  async #stageInProcessModuleDir(pluginId, dirReal, entryRel) {
    const pluginCacheDir = path.join(this.cacheRoot, pluginId);
    const stageName = `gen-${this.generationSeq}-${randomUUID().slice(0, 6)}`;
    const stageDir = path.join(pluginCacheDir, stageName);
    await fs.mkdir(stageDir, {recursive: true});

    // 清理上一代残留缓存目录，防止磁盘累积
    const prevDirs = await fs.readdir(pluginCacheDir, {withFileTypes: true}).catch(() => []);
    for (const e of prevDirs) {
      if (e.name !== stageName) {
        await fs.rm(path.join(pluginCacheDir, e.name), {recursive: true, force: true}).catch(() => {});
      }
    }

    const copyRecursive = async (srcDir, dstDir) => {
      const items = await fs.readdir(srcDir, {withFileTypes: true});
      for (const item of items) {
        if (item.name === "node_modules" || item.name === ".git") continue;
        const srcPath = path.join(srcDir, item.name);
        const dstPath = path.join(dstDir, item.name);
        if (item.isDirectory()) {
          await fs.mkdir(dstPath, {recursive: true});
          await copyRecursive(srcPath, dstPath);
        } else if (item.isFile()) {
          await fs.copyFile(srcPath, dstPath);
        } else if (item.isSymbolicLink()) {
          const resolved = await fs.realpath(srcPath).catch(() => null);
          if (resolved && isWithinRealRoot(resolved, dirReal)) {
            const st = await fs.stat(resolved).catch(() => null);
            if (st?.isFile()) await fs.copyFile(resolved, dstPath);
          }
        }
      }
    };
    await copyRecursive(dirReal, stageDir);

    // 若插件拥有 node_modules，以符号链接/联接方式复用，避免大体积复制且保持 ESM 包解析一致
    const nmSrc = path.join(dirReal, "node_modules");
    if (await fs.stat(nmSrc).then(s => s.isDirectory()).catch(() => false)) {
      const nmDst = path.join(stageDir, "node_modules");
      await fs.symlink(nmSrc, nmDst, process.platform === "win32" ? "junction" : "dir").catch(() => {});
    }
    const stagedEntry = path.resolve(stageDir, entryRel);
    if (!isWithinRealRoot(stagedEntry, path.resolve(stageDir))) {
      throw new Error("插件镜像入口路径越界");
    }
    return {stageDir, stagedEntry};
  }

  async #activateRecord(rec) {
    const m = rec.manifest;
    const savedGrant = Array.isArray(this.state.grantedCaps[m.id]) ? new Set(this.state.grantedCaps[m.id]) : null;
    const neg = negotiateCapabilities(m, m.kind === "core" ? null : savedGrant);
    rec.grantedCapabilities = neg.granted;
    rec.degradedOptional = neg.degradedOptional;
    if (!neg.compatible) {
      rec.status = "incompatible";
      rec.error = [
        neg.missingRequired.length ? `宿主不支持能力：${neg.missingRequired.join(", ")}` : "",
        neg.deniedRequired.length ? `未授权必需能力：${neg.deniedRequired.join(", ")}` : "",
      ].filter(Boolean).join("；");
      return;
    }

    rec.abortController = new AbortController();
    rec.effects = [];
    rec.error = null;
    await this.#loadPluginSettings(rec);

    // 注册静态声明的 commands 与 panels（即便插件没有 JS 代码也能作为纯声明式插件工作）
    for (const cmd of m.contributes.commands) {
      const key = `${m.id}:${cmd.name}`;
      this.commands.set(key, {pluginId: m.id, ...cmd, handler: null});
      rec.effects.push({cleanup: () => this.commands.delete(key), label: `manifest-cmd:${cmd.name}`});
    }
    for (const p of m.contributes.panels) {
      const key = `${m.id}:${p.id}`;
      this.panels.set(key, {pluginId: m.id, ...p, render: null});
      rec.effects.push({cleanup: () => this.panels.delete(key), label: `manifest-panel:${p.id}`});
    }

    try {
      let activateFn = rec.activate;
      if (!activateFn && m.entrypoint && rec.dir) {
        const entryAbs = path.resolve(rec.dir, m.entrypoint);
        const dirReal = await fs.realpath(rec.dir);
        const entryReal = await fs.realpath(entryAbs);
        if (!entryReal.startsWith(dirReal + path.sep)) throw new Error("插件入口符号链接越界，已拒绝加载");
        if (m.executionTier === "worker-isolated") {
          await this.#activateInWorker(rec, entryReal, neg.granted);
          rec.status = "active";
          this.#pushLog(rec, "info", `已在隔离线程（${this.generationId}）激活`);
          return;
        }
        const entryRel = path.relative(dirReal, entryReal);
        const {stageDir, stagedEntry} = await this.#stageInProcessModuleDir(m.id, dirReal, entryRel);
        rec.effects.push({
          cleanup: () => fs.rm(stageDir, {recursive: true, force: true}).catch(() => {}),
          label: `module-cache:${m.id}`,
        });
        const modUrl = pathToFileURL(stagedEntry).href;
        const mod = await withTimeout(import(modUrl), this.activateTimeoutMs, `导入插件 ${m.id}`);
        activateFn = mod.activate || mod.apply || mod.default?.activate || mod.default;
      }
      if (typeof activateFn === "function") {
        const ctx = this.#buildContext(rec, neg.granted);
        const ret = await withTimeout(Promise.resolve(activateFn(ctx)), this.activateTimeoutMs, `激活插件 ${m.id}`);
        if (typeof ret === "function") rec.effects.push({cleanup: ret, label: "activate-return"});
      }
      rec.status = "active";
      this.#pushLog(rec, "info", `已在 ${this.generationId} 激活`);
    } catch (err) {
      rec.status = "failed";
      rec.error = err?.message || String(err);
      this.#pushLog(rec, "error", `激活失败：${rec.error}`);
      await this.#runEffects(rec);
    }
  }

  async #runEffects(rec) {
    try { rec.abortController?.abort(); } catch {}
    rec.abortController = null;
    rec.settingsListeners.clear();
    const list = rec.effects.splice(0).reverse();
    for (const item of list) {
      try { await Promise.resolve(item.cleanup()); }
      catch (err) { this.#pushLog(rec, "warn", `清理 ${item.label} 异常：${err?.message || err}`); }
    }
  }

  async deactivate(pluginId) {
    const rec = this.records.get(pluginId);
    if (!rec) return;
    await this.#runEffects(rec);
    if (rec.status === "active") rec.status = "disabled";
  }

  async dispose() {
    for (const rec of [...this.records.values()].reverse()) {
      await this.#runEffects(rec);
    }
    this.records.clear();
    this.services.clear();
    this.commands.clear();
    this.rpcs.clear();
    this.panels.clear();
    this.surfaces.clear();
    this.uiSlots.clear();
    this.composerActions.clear();
    this.promptHooks.clear();
    this.observers.clear();
    this.drivers.clear();
    this.translators.clear();
  }

  /** 执行对话发送前受控钩子（异常与超时 3s 严格隔离，绝不阻塞主会话）。 */
  async runBeforePromptHooks(payload) {
    const set = this.promptHooks.get("beforePrompt");
    let text = String(payload?.text ?? "");
    const notices = [];
    if (!set || set.size === 0 || this.state.safeMode) return {text, notices};
    for (const entry of [...set]) {
      try {
        const res = await withTimeout(
          Promise.resolve(entry.fn(deepFreeze({...payload, text}))),
          3_000,
          `beforePrompt:${entry.pluginId}`,
        );
        if (res && typeof res === "object") {
          if (typeof res.text === "string") text = res.text;
          if (typeof res.prepend === "string" && res.prepend.trim()) text = `${res.prepend.trim()}\n\n${text}`;
          if (typeof res.append === "string" && res.append.trim()) text = `${text}\n\n${res.append.trim()}`;
          if (typeof res.notice === "string" && res.notice.trim()) notices.push({pluginId: entry.pluginId, message: res.notice.trim()});
        }
      } catch (err) {
        const rec = this.records.get(entry.pluginId);
        if (rec) this.#pushLog(rec, "warn", `beforePrompt 钩子异常：${err?.message || err}`);
      }
    }
    return {text, notices};
  }

  /** 执行对话回复后受控钩子（单插件异常隔离）。 */
  async runAfterResponseHooks(payload) {
    const set = this.promptHooks.get("afterResponse");
    const notices = [];
    if (!set || set.size === 0 || this.state.safeMode) return {notices};
    for (const entry of [...set]) {
      try {
        const res = await withTimeout(
          Promise.resolve(entry.fn(deepFreeze({...payload}))),
          3_000,
          `afterResponse:${entry.pluginId}`,
        );
        if (res && typeof res.notice === "string" && res.notice.trim()) notices.push({pluginId: entry.pluginId, message: res.notice.trim()});
      } catch (err) {
        const rec = this.records.get(entry.pluginId);
        if (rec) this.#pushLog(rec, "warn", `afterResponse 钩子异常：${err?.message || err}`);
      }
    }
    return {notices};
  }

  getService(name) {
    return this.services.get(name)?.impl;
  }

  /** 向所有订阅者投递不可变观察事件（单插件异常绝不影响核心流程）。 */
  emitObserve(topic, payload) {
    const set = this.observers.get(topic);
    if (!set || set.size === 0) return;
    const frozen = deepFreeze(structuredClone(payload ?? {}));
    for (const entry of [...set]) {
      try {
        const res = entry.fn(frozen);
        if (res && typeof res.catch === "function") {
          res.catch(err => {
            const rec = this.records.get(entry.pluginId);
            if (rec) this.#pushLog(rec, "warn", `观察事件 ${topic} 异步异常：${err?.message || err}`);
          });
        }
      } catch (err) {
        const rec = this.records.get(entry.pluginId);
        if (rec) this.#pushLog(rec, "warn", `观察事件 ${topic} 异常：${err?.message || err}`);
      }
    }
  }

  async togglePlugin(pluginId, enabled) {
    const rec = this.records.get(pluginId);
    if (!rec) throw new Error(`未找到插件：${pluginId}`);
    if (rec.manifest.kind === "core") throw new Error("核心内核插件不可停用");
    const want = Boolean(enabled);
    rec.enabled = want;
    this.state.enabled[pluginId] = want;
    await this.saveState();
    if (want) {
      if (this.state.safeMode) {
        rec.status = "safe_mode_skipped";
      } else {
        await this.deactivate(pluginId);
        await this.#activateRecord(rec);
        if (rec.status === "active") await this.commitLastKnownGood().catch(() => {});
      }
    } else {
      await this.deactivate(pluginId);
      rec.status = "disabled";
    }
    this.onNotify("plugins/changed", {generationId: this.generationId, pluginId, status: rec.status});
    return this.snapshot();
  }

  async updateSettings(pluginId, nextSettings) {
    const rec = this.records.get(pluginId);
    if (!rec) throw new Error(`未找到插件：${pluginId}`);
    if (!nextSettings || typeof nextSettings !== "object" || Array.isArray(nextSettings)) {
      throw new Error("插件配置必须是 JSON 对象");
    }
    const merged = {...defaultSettingsFromSchema(rec.manifest.settingsSchema), ...nextSettings};
    await writeJsonAtomic(path.join(this.configRoot, `${pluginId}.json`), merged);
    rec.settings = merged;
    for (const fn of [...rec.settingsListeners]) {
      try { fn({...merged}); } catch (err) { this.#pushLog(rec, "warn", `配置变更回调异常：${err?.message || err}`); }
    }
    return {ok: true, settings: merged};
  }

  async updateGrants(pluginId, grantedCapabilities) {
    const rec = this.records.get(pluginId);
    if (!rec) throw new Error(`未找到插件：${pluginId}`);
    if (rec.manifest.kind === "core") throw new Error("核心内核插件无需修改授权");
    this.state.grantedCaps[pluginId] = [...new Set([...ALWAYS_GRANTED_CAPABILITIES, ...(grantedCapabilities || []).map(String)])];
    await this.saveState();
    if (rec.enabled && !this.state.safeMode) {
      await this.deactivate(pluginId);
      await this.#activateRecord(rec);
    }
    return this.snapshot();
  }

  async setSafeMode(enabled) {
    this.state.safeMode = Boolean(enabled);
    await this.saveState();
    const snap = await this.boot();
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, safeMode: this.state.safeMode});
    return snap;
  }

  async rollbackLastKnownGood() {
    const lkg = await readJsonSafe(this.lkgFile, null);
    if (!lkg) throw new Error("暂无可回滚的 Last-Known-Good 插件快照");
    this.state = {
      safeMode: false,
      enabled: lkg.enabled && typeof lkg.enabled === "object" ? {...lkg.enabled} : {},
      grantedCaps: lkg.grantedCaps && typeof lkg.grantedCaps === "object" ? {...lkg.grantedCaps} : {},
      updatedAt: Date.now(),
    };
    await this.saveState();
    const snap = await this.boot();
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, rolledBack: true});
    return snap;
  }

  async runCommand({pluginId, command, args = "", cwd = null, sessionId = null}) {
    const key = `${pluginId}:${String(command || "").replace(/^\/+/, "")}`;
    const entry = this.commands.get(key);
    if (!entry) throw new Error(`未找到插件命令：${key}`);
    if (entry.handler) {
      return await withTimeout(
        Promise.resolve(entry.handler({args: String(args || ""), cwd: cwd || path.join(this.home, "workspace"), sessionId})),
        15_000,
        `执行插件命令 /${entry.name}`,
      );
    }
    if (entry.promptTemplate) {
      return {kind: "prompt", prompt: args ? `${entry.promptTemplate}\n${args}` : entry.promptTemplate};
    }
    return {kind: "notice", message: `命令 /${entry.name} 已执行`};
  }

  async renderPanel({pluginId, panelId, cwd = null}) {
    const key = `${pluginId}:${panelId}`;
    const panel = this.panels.get(key);
    if (!panel) throw new Error(`未找到插件面板：${key}`);
    if (panel.render) {
      const rendered = await withTimeout(
        Promise.resolve(panel.render({cwd: cwd || path.join(this.home, "workspace")})),
        15_000,
        `渲染插件面板 ${panel.title}`,
      );
      if (typeof rendered === "string") return {title: panel.title, kind: "html", content: rendered};
      return {title: rendered?.title || panel.title, kind: rendered?.kind || "html", content: String(rendered?.content || rendered?.html || "")};
    }
    const rec = this.records.get(pluginId);
    if (panel.htmlFile && rec?.dir) {
      const abs = path.resolve(rec.dir, panel.htmlFile);
      const dirReal = await fs.realpath(rec.dir);
      const fileReal = await fs.realpath(abs);
      if (!fileReal.startsWith(dirReal + path.sep)) throw new Error("面板文件越界");
      const html = await fs.readFile(fileReal, "utf8");
      return {title: panel.title, kind: "html", content: html};
    }
    throw new Error("面板未提供渲染器或静态 HTML");
  }

  async invokeRpc(method, params) {
    const entry = this.rpcs.get(method);
    if (!entry) return {handled: false};
    const result = await withTimeout(Promise.resolve(entry.handler(params || {})), 20_000, `调用插件 RPC ${method}`);
    return {handled: true, result};
  }

  async createLocalPlugin({id, name, description = ""}) {
    const cleanId = String(id || "").trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error("插件 ID 仅支持小写字母、数字、点、短横线或下划线（2~64位）");
    if (this.records.has(cleanId)) throw new Error(`插件 ID 已存在：${cleanId}`);
    const cleanName = String(name || cleanId).trim().slice(0, 60);
    const dir = path.join(this.pluginsRoot, cleanId);
    await fs.mkdir(dir, {recursive: true});

    const manifest = {
      manifestVersion: "1.0.0",
      apiVersion: ">=1.0.0 <2.0.0",
      id: cleanId,
      name: cleanName,
      version: "0.1.0",
      description: String(description || "本地 Solbraid 扩展插件").trim(),
      kind: "local",
      entrypoint: "index.mjs",
      capabilities: {
        required: ["commands", "settings.schema", "ui.panel", "storage.local"],
        optional: ["events.observe"],
      },
      settingsSchema: {
        type: "object",
        properties: {
          greeting: {type: "string", title: "问候语", description: "执行命令与面板展示时使用的提示文本", default: `你好，来自 ${cleanName}`},
          showTimestamp: {type: "boolean", title: "显示时间戳", default: true},
        },
        required: ["greeting"],
      },
    };

    const cmdName = cleanId.split(".").pop().replace(/[^a-z0-9_-]/g, "").slice(0, 24) || "hello";
    const entryCode = [
      `export async function activate(ctx) {`,
      `  ctx.log.info("${cleanName} 已启动");`,
      `  ctx.commands.register("${cmdName}", {`,
      `    description: "运行 ${cleanName} 示例命令",`,
      `    async handler({cwd}) {`,
      `      const cfg = ctx.settings.get();`,
      `      const count = (Number(await ctx.storage.get("runs")) || 0) + 1;`,
      `      await ctx.storage.set("runs", count);`,
      `      const ts = cfg.showTimestamp ? \` (\${new Date().toLocaleTimeString()})\` : "";`,
      `      return {kind: "notice", message: \`\${cfg.greeting}\${ts} · 第 \${count} 次调用 · \${cwd}\`};`,
      `    },`,
      `  });`,
      `  ctx.panels.register({`,
      `    id: "main",`,
      `    title: "${cleanName} 面板",`,
      `    description: "插件自带隔离 WebView 交互面板",`,
      `    async render({cwd}) {`,
      `      const cfg = ctx.settings.get();`,
      `      const runs = Number(await ctx.storage.get("runs")) || 0;`,
      `      const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));`,
      `      return \`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html{min-height:100%;background:#0f172a}body{margin:0;min-height:100vh;font-family:system-ui,sans-serif;padding:18px;background:#0f172a;color:#e2e8f0;box-sizing:border-box}h1{font-size:18px;margin:0 0 8px}.card{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:14px;margin-top:12px}code{color:#38bdf8}</style></head><body><h1>\${esc("${cleanName}")}</h1><p>\${esc(cfg.greeting)}</p><div class="card"><div>工作区：<code>\${esc(cwd)}</code></div><div>命令累计调用：<strong>\${runs}</strong></div><div>Generation：<code>\${esc(ctx.host.generationId)}</code></div></div></body></html>\`;`,
      `    },`,
      `  });`,
      `  ctx.events?.observe("session.completed", ev => {`,
      `    ctx.log.info(\`观测到会话完成：\${ev.agentId}:\${ev.sessionId}\`);`,
      `  });`,
      `}`,
      ``,
    ].join("\n");

    await writeJsonAtomic(path.join(dir, MANIFEST_FILENAME), manifest, 0o644);
    await fs.writeFile(path.join(dir, "index.mjs"), entryCode, {encoding: "utf8", mode: 0o644});
    this.state.enabled[cleanId] = true;
    await this.saveState();
    return await this.boot();
  }

  async removeLocalPlugin(pluginId) {
    const cleanId = String(pluginId || "").trim();
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error(`无效插件 ID：${pluginId}`);
    const rec = this.records.get(cleanId);
    const diskDir = rec?.dir || path.join(this.pluginsRoot, cleanId);
    const hasDisk = await fs.stat(diskDir).then(s => s.isDirectory()).catch(() => false);
    if (!rec && !hasDisk) throw new Error(`未找到插件：${cleanId}`);
    if (rec && (!rec.dir || rec.manifest.kind === "core" || rec.manifest.kind === "builtin")) {
      throw new Error("仅可卸载本地或社区安装的运行时插件");
    }
    if (this.builtinMap.get(cleanId)?.manifest?.kind === "core") {
      throw new Error("核心内核插件不可卸载");
    }
    if (rec) await this.deactivate(cleanId);
    await fs.rm(diskDir, {recursive: true, force: true});
    await fs.rm(path.join(this.dataRoot, `${cleanId}.json`), {force: true});
    await fs.rm(path.join(this.configRoot, `${cleanId}.json`), {force: true});
    await fs.rm(path.join(this.snapshotsRoot, cleanId), {recursive: true, force: true});
    await fs.rm(path.join(this.cacheRoot, cleanId), {recursive: true, force: true});
    delete this.state.enabled[cleanId];
    delete this.state.grantedCaps[cleanId];
    await this.saveState();
    const snap = await this.boot();
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, pluginId: cleanId, removed: true});
    return snap;
  }

  /** 安装来自共享仓库的社区 Host 插件包（静态清单校验 + 防路径穿越写入 + 热激活）。 */
  async installPluginBundle({manifest: rawManifest, files = {}}) {
    const checked = validatePluginManifest({...rawManifest, kind: "community"}, {allowCore: false});
    if (!checked.ok) throw new Error(`插件清单校验失败：${checked.errors.join("; ")}`);
    const m = checked.manifest;
    if (this.records.get(m.id)?.manifest?.kind === "core" || this.records.get(m.id)?.manifest?.kind === "builtin") {
      throw new Error(`禁止覆盖核心或内置插件：${m.id}`);
    }
    const dir = path.join(this.pluginsRoot, m.id);
    await fs.rm(dir, {recursive: true, force: true});
    await fs.mkdir(dir, {recursive: true});
    await writeJsonAtomic(path.join(dir, MANIFEST_FILENAME), {...rawManifest, id: m.id, kind: "community"}, 0o644);
    for (const [relRaw, content] of Object.entries(files || {})) {
      const rel = String(relRaw || "").replace(/\\/g, "/").replace(/^\/+/, "");
      if (!rel || rel.split("/").includes("..") || rel === MANIFEST_FILENAME) continue;
      const abs = path.resolve(dir, rel);
      if (!abs.startsWith(path.resolve(dir) + path.sep)) throw new Error(`插件文件越界：${rel}`);
      await fs.mkdir(path.dirname(abs), {recursive: true});
      await fs.writeFile(abs, String(content ?? ""), {encoding: "utf8", mode: 0o644});
    }
    this.state.enabled[m.id] = true;
    await this.saveState();
    return await this.boot();
  }

  /** 将已安装的本地/社区 Host 插件导出为标准可上传分享包（用于提交至共享资源仓库 AI 审查）。 */
  async exportPluginBundle(pluginId) {
    const rec = this.records.get(pluginId);
    if (!rec || !rec.dir) throw new Error(`未找到可导出的本地插件：${pluginId}`);
    const manifest = JSON.parse(await fs.readFile(path.join(rec.dir, MANIFEST_FILENAME), "utf8"));
    const files = {};
    async function walk(curDir, prefix = "") {
      const entries = await fs.readdir(curDir, {withFileTypes: true});
      for (const e of entries) {
        const rel = prefix ? `${prefix}/${e.name}` : e.name;
        const abs = path.join(curDir, e.name);
        if (e.isDirectory()) {
          if (e.name === "node_modules" || e.name.startsWith(".")) continue;
          await walk(abs, rel);
        } else if (e.isFile() && rel !== MANIFEST_FILENAME) {
          const text = await fs.readFile(abs, "utf8");
          if (Buffer.byteLength(text, "utf8") <= 128 * 1024) files[rel] = text;
        }
      }
    }
    await walk(rec.dir);
    return {
      id: rec.manifest.id,
      kind: "host_plugin",
      name: rec.manifest.name,
      version: rec.manifest.version,
      description: rec.manifest.description,
      payload: {manifest, files},
    };
  }

  async #refreshSnapshotCounts() {
    const counts = new Map();
    const dirs = await fs.readdir(this.snapshotsRoot, {withFileTypes: true}).catch(() => []);
    for (const d of dirs) {
      if (!d.isDirectory()) continue;
      const snaps = await fs.readdir(path.join(this.snapshotsRoot, d.name), {withFileTypes: true}).catch(() => []);
      counts.set(d.name, snaps.filter(s => s.isDirectory()).length);
    }
    this.snapshotCounts = counts;
  }

  async #readDirFiles(dir) {
    const files = {};
    async function walk(cur, prefix = "") {
      const entries = await fs.readdir(cur, {withFileTypes: true}).catch(() => []);
      for (const e of entries) {
        const rel = prefix ? `${prefix}/${e.name}` : e.name;
        const abs = path.join(cur, e.name);
        if (e.isDirectory()) {
          if (e.name === "node_modules" || e.name.startsWith(".")) continue;
          await walk(abs, rel);
        } else if (e.isFile() && rel !== MANIFEST_FILENAME) {
          const buf = await fs.readFile(abs);
          if (buf.length <= 256 * 1024) files[rel] = buf.toString("utf8");
        }
      }
    }
    await walk(dir);
    return files;
  }

  /** 读取磁盘插件完整 Manifest 与全部源码文件，供 Agent 检查与增量升级。 */
  async readPluginSource(pluginId) {
    const cleanId = String(pluginId || "").trim();
    const rec = this.records.get(cleanId);
    if (!rec) throw new Error(`未找到插件：${cleanId}`);
    if (rec.manifest.kind === "core") throw new Error("核心内核插件已锁定，不可读取覆写");
    if (!rec.dir) {
      return {
        pluginId: cleanId,
        isBuiltin: rec.manifest.kind === "builtin",
        overriddenBuiltin: false,
        manifest: rec.manifest,
        files: {},
        note: "当前为内置原版插件；如需由 Agent 覆写升级，可直接调用 apply 传入新的 manifest 与 index.mjs。",
      };
    }
    const manifest = JSON.parse(await fs.readFile(path.join(rec.dir, MANIFEST_FILENAME), "utf8"));
    const files = await this.#readDirFiles(rec.dir);
    return {
      pluginId: cleanId,
      dirPath: rec.dir,
      isBuiltin: this.builtinMap.has(cleanId),
      overriddenBuiltin: Boolean(rec.overriddenBuiltin),
      manifest,
      files,
    };
  }

  /** 列出指定插件的历史源码快照（按时间倒序）。 */
  async listSourceSnapshots(pluginId) {
    const cleanId = String(pluginId || "").trim();
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error(`无效插件 ID：${pluginId}`);
    const baseDir = path.join(this.snapshotsRoot, cleanId);
    const entries = await fs.readdir(baseDir, {withFileTypes: true}).catch(() => []);
    const list = [];
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      const meta = await readJsonSafe(path.join(baseDir, e.name, "meta.json"), null);
      if (meta) {
        list.push({
          id: e.name,
          pluginId: cleanId,
          version: String(meta.version || "0.0.0"),
          createdAt: Number(meta.createdAt || 0),
          note: String(meta.note || ""),
          fileCount: Number(meta.fileCount || 0),
        });
      }
    }
    list.sort((a, b) => b.createdAt - a.createdAt);
    this.snapshotCounts.set(cleanId, list.length);
    return list;
  }

  /** 清理插件目录源码但保留 node_modules 与受管依赖清单，防止热升级/回滚破坏已安装的第三方 npm 包。 */
  async #clearPluginSourceFiles(dir, {preservePackageJson = true} = {}) {
    const entries = await fs.readdir(dir, {withFileTypes: true}).catch(() => []);
    for (const e of entries) {
      if (e.name === "node_modules" || e.name === "package-lock.json") continue;
      if (preservePackageJson && e.name === "package.json") continue;
      await fs.rm(path.join(dir, e.name), {recursive: true, force: true}).catch(() => {});
    }
  }

  /** 在修改/升级前归档插件完整源码目录为可回滚快照（单插件最多保留 10 份）。 */
  async createSourceSnapshot(pluginId, note = "自动快照") {
    const cleanId = String(pluginId || "").trim();
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error(`无效插件 ID：${pluginId}`);
    const dir = path.join(this.pluginsRoot, cleanId);
    const exists = await fs.stat(dir).then(s => s.isDirectory()).catch(() => false);
    if (!exists) return null;
    const manifestRaw = await readJsonSafe(path.join(dir, MANIFEST_FILENAME), null);
    const files = await this.#readDirFiles(dir);
    const createdAt = Date.now();
    const snapId = `${createdAt}-${randomUUID().slice(0, 6)}`;
    const snapDir = path.join(this.snapshotsRoot, cleanId, snapId);
    const filesDir = path.join(snapDir, "files");
    await fs.mkdir(filesDir, {recursive: true});
    if (manifestRaw) {
      await writeJsonAtomic(path.join(filesDir, MANIFEST_FILENAME), manifestRaw, 0o644);
    }
    for (const [rel, content] of Object.entries(files)) {
      const abs = path.resolve(filesDir, rel);
      if (!abs.startsWith(path.resolve(filesDir) + path.sep)) continue;
      await fs.mkdir(path.dirname(abs), {recursive: true});
      await atomicWriteTextFile(abs, content, {captureCheckpoint: false, preserveMetadata: false, defaultMode: 0o644});
    }
    await writeJsonAtomic(path.join(snapDir, "meta.json"), {
      id: snapId,
      pluginId: cleanId,
      version: String(manifestRaw?.version || "0.0.0"),
      createdAt,
      note: String(note || "自动快照").slice(0, 140),
      fileCount: Object.keys(files).length + (manifestRaw ? 1 : 0),
    }, 0o644);

    // 清理超量旧快照，保留最近 10 份
    const all = await this.listSourceSnapshots(cleanId);
    if (all.length > 10) {
      for (const old of all.slice(10)) {
        await fs.rm(path.join(this.snapshotsRoot, cleanId, old.id), {recursive: true, force: true}).catch(() => {});
      }
      this.snapshotCounts.set(cleanId, 10);
    }
    return snapId;
  }

  /** 预检门禁：校验非核心边界 + Manifest + 路径安全 + 静态安全审计 + `node --check` 语法校验。 */
  async validatePluginCandidate({pluginId, manifest: rawManifest, files = {}}) {
    const errors = [];
    const warnings = [];
    const checkedFiles = [];

    const id = String(pluginId || rawManifest?.id || "").trim();
    if (!PLUGIN_ID_RE.test(id)) errors.push(`无效插件 ID：${id || "(空)"}`);
    const existingBuiltin = this.builtinMap.get(id);
    if (existingBuiltin?.manifest?.kind === "core" || String(rawManifest?.kind || "").toLowerCase() === "core") {
      errors.push(`安全红线拦截：核心内核插件不可被覆写或伪造（${id}）`);
    }

    const effectiveRaw = {...(rawManifest && typeof rawManifest === "object" ? rawManifest : {}), id};
    if (!effectiveRaw.kind || effectiveRaw.kind === "core") {
      effectiveRaw.kind = existingBuiltin ? "local" : (rawManifest?.kind || "local");
    }
    const mCheck = validatePluginManifest(effectiveRaw, {allowCore: false});
    if (!mCheck.ok) errors.push(...mCheck.errors);

    const fileEntries = Object.entries(files && typeof files === "object" ? files : {});
    if (fileEntries.length > 32) errors.push("插件文件数量超过上限（最多 32 个文件）");

    const cleanFiles = {};
    for (const [rawRel, rawContent] of fileEntries) {
      const rel = String(rawRel || "").replace(/\\/g, "/").replace(/^\/+/, "");
      if (!rel || rel.split("/").includes("..") || rel === MANIFEST_FILENAME) {
        errors.push(`非法文件路径：${rawRel}`);
        continue;
      }
      const text = String(rawContent ?? "");
      if (Buffer.byteLength(text, "utf8") > 256 * 1024) {
        errors.push(`文件 ${rel} 超过 256 KB 上限`);
        continue;
      }
      cleanFiles[rel] = text;
    }

    if (mCheck.manifest?.entrypoint && !Object.prototype.hasOwnProperty.call(cleanFiles, mCheck.manifest.entrypoint)) {
      errors.push(`缺少声明的入口文件：${mCheck.manifest.entrypoint}`);
    }

    // 静态安全审计（复用 ai-reviewer.mjs 14 条恶意代码与越权规则）
    const sec = scanStaticSecurity({
      id: id || "invalid.plugin",
      kind: "host_plugin",
      name: String(effectiveRaw.name || id || "plugin"),
      version: String(effectiveRaw.version || "1.0.0"),
      description: String(effectiveRaw.description || ""),
      payload: {manifest: effectiveRaw, files: cleanFiles},
    });
    for (const f of sec.findings || []) {
      if (f.severity === "critical" || f.severity === "high") {
        errors.push(`[安全拦截:${f.ruleId}] ${f.title}（${f.location}）`);
      } else {
        warnings.push(`[安全提示:${f.ruleId}] ${f.title}（${f.location}）`);
      }
    }

    // 对所有 JS / MJS / CJS 源码执行真实 `node --check` 语法校验
    const jsEntries = Object.entries(cleanFiles).filter(([rel]) => /\.(?:mjs|js|cjs)$/i.test(rel));
    if (jsEntries.length > 0 && errors.length === 0) {
      const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "solbraid-plugchk-"));
      try {
        for (const [rel, code] of jsEntries) {
          const abs = path.resolve(tmpDir, rel);
          await fs.mkdir(path.dirname(abs), {recursive: true});
          await fs.writeFile(abs, code, "utf8");
          try {
            await execFileAsync(process.execPath, ["--check", abs], {timeout: 6_000});
            checkedFiles.push(rel);
          } catch (synErr) {
            const msg = String(synErr?.stderr || synErr?.message || "语法检查失败").trim().slice(0, 320);
            errors.push(`语法错误（${rel}）：${msg}`);
          }
        }
      } finally {
        await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
      }
    }

    return {
      ok: errors.length === 0,
      errors,
      warnings,
      securityFindings: sec.findings || [],
      checkedFiles,
      normalizedManifest: mCheck.manifest,
      cleanFiles,
    };
  }

  /** 单插件增量热重载：仅反激活并重新导入目标插件，不干扰其他正常插件与运行中会话。 */
  async reloadPlugin(pluginId) {
    const cleanId = String(pluginId || "").trim();
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error(`无效插件 ID：${pluginId}`);
    const existing = this.records.get(cleanId);
    if (existing?.manifest?.kind === "core") throw new Error("核心内核插件不可单独热重载");

    await this.loadState();
    this.generationSeq += 1;
    this.generationId = `gen-${this.generationSeq}-${randomUUID().slice(0, 8)}`;

    if (existing) await this.#runEffects(existing);

    const diskDir = path.join(this.pluginsRoot, cleanId);
    const manifestPath = path.join(diskDir, MANIFEST_FILENAME);
    const hasDisk = await fs.stat(manifestPath).then(s => s.isFile()).catch(() => false);
    const builtinEntry = this.builtinMap.get(cleanId);

    let rec;
    if (hasDisk) {
      const raw = JSON.parse(await fs.readFile(manifestPath, "utf8"));
      const checked = validatePluginManifest(raw, {allowCore: false});
      if (!checked.ok) {
        rec = {
          manifest: {
            id: cleanId, name: cleanId, version: "0.0.0", description: "", kind: "local",
            executionTier: "trusted-in-process", capabilities: {required: [], optional: []},
            settingsSchema: null, contributes: {commands: [], panels: []},
          },
          dir: diskDir, enabled: false, overriddenBuiltin: Boolean(builtinEntry),
          status: "invalid", error: checked.errors.join("; "),
          grantedCapabilities: [], degradedOptional: [], logs: [], effects: [], settings: {},
          settingsListeners: new Set(), abortController: null,
        };
      } else {
        const enabled = this.state.enabled[cleanId] ?? true;
        rec = this.#createRecord(checked.manifest, {
          dir: diskDir, activate: null, enabled, overriddenBuiltin: Boolean(builtinEntry),
        });
      }
    } else if (builtinEntry) {
      const enabled = this.state.enabled[cleanId] ?? true;
      rec = this.#createRecord(builtinEntry.manifest, {
        dir: null, activate: builtinEntry.activate, enabled, overriddenBuiltin: false,
      });
    } else {
      this.records.delete(cleanId);
      this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, pluginId: cleanId, removed: true});
      return {plugin: null, generationId: this.generationId, generationSeq: this.generationSeq};
    }

    this.records.set(cleanId, rec);
    if (rec.status !== "invalid") {
      if (this.state.safeMode) {
        rec.status = "safe_mode_skipped";
      } else if (rec.enabled) {
        await this.#activateRecord(rec);
      } else {
        rec.status = "disabled";
      }
    }
    await this.listSourceSnapshots(cleanId).catch(() => {});
    if (!this.state.safeMode && rec.status === "active") {
      await this.commitLastKnownGood().catch(() => {});
    }
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, pluginId: cleanId, status: rec.status});
    const snap = this.snapshot();
    return {
      plugin: snap.plugins.find(p => p.id === cleanId) || null,
      generationId: this.generationId,
      generationSeq: this.generationSeq,
      snapshot: snap,
    };
  }

  /** 将已完整写好的暂存目录原子替换到目标插件目录，并在失败时尽量恢复原目录（保留 node_modules）。 */
  async #atomicSwapPluginDir(dir, stageDir, {existedBefore, preservePackageJson}) {
    const preserveNames = ["node_modules", "package-lock.json", ...(preservePackageJson ? ["package.json"] : [])];
    const backupDir = existedBefore ? path.join(this.pluginsRoot, `.${path.basename(dir)}.bak-${randomUUID().slice(0, 8)}`) : null;
    const movedIntoDir = [];
    let movedOldToBackup = false;
    let movedStageToDir = false;
    try {
      if (backupDir) {
        await fs.rename(dir, backupDir);
        movedOldToBackup = true;
      }
      await fs.rename(stageDir, dir);
      movedStageToDir = true;
      if (backupDir) {
        for (const name of preserveNames) {
          const src = path.join(backupDir, name);
          const dst = path.join(dir, name);
          const exists = await fs.lstat(src).then(() => true).catch(() => false);
          if (!exists) continue;
          const dstExists = await fs.lstat(dst).then(() => true).catch(() => false);
          if (dstExists) continue;
          await fs.rename(src, dst);
          movedIntoDir.push(name);
        }
      }
      return {backupDir, movedIntoDir};
    } catch (swapErr) {
      // 发生任何文件系统异常时尝试还原旧目录，并如实抛出恢复状态
      const restoreErrors = [];
      for (const name of movedIntoDir) {
        await fs.rename(path.join(dir, name), path.join(backupDir, name)).catch(e => restoreErrors.push(`还原 ${name} 失败：${e?.message || e}`));
      }
      if (movedStageToDir) {
        await fs.rm(dir, {recursive: true, force: true}).catch(e => restoreErrors.push(`清理半成品目录失败：${e?.message || e}`));
      }
      if (movedOldToBackup) {
        await fs.rename(backupDir, dir).catch(e => restoreErrors.push(`恢复备份目录失败：${e?.message || e}`));
      }
      await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {});
      const err = new Error(swapErr?.message || String(swapErr));
      err.rollbackSucceeded = restoreErrors.length === 0;
      err.rollbackError = restoreErrors.length ? restoreErrors.join("；") : null;
      throw err;
    }
  }

  /** Agent 自升级原子流水线：预检 → 归档源码快照 → 完整写暂存目录 → 原子目录切换 → 单插件增量热重载 → 激活失败真回滚并如实报告结果。 */
  async applyPluginSourceUpdate({pluginId, manifest: rawManifest, files = {}, note = "", dryRun = false}) {
    const cleanId = String(pluginId || rawManifest?.id || "").trim();
    const validation = await this.validatePluginCandidate({pluginId: cleanId, manifest: rawManifest, files});
    if (!validation.ok) {
      return {ok: false, rolledBack: false, validation, error: validation.errors.join("；")};
    }
    if (dryRun) {
      return {ok: true, dryRun: true, rolledBack: false, validation};
    }

    await fs.mkdir(this.pluginsRoot, {recursive: true});
    const dir = path.join(this.pluginsRoot, cleanId);
    const existedBefore = await fs.stat(dir).then(s => s.isDirectory()).catch(() => false);
    const snapshotId = existedBefore ? await this.createSourceSnapshot(cleanId, note || "自动快照（升级前）") : null;

    const hasExplicitPkgJson = Object.prototype.hasOwnProperty.call(validation.cleanFiles, "package.json");
    const manifestToWrite = {
      ...rawManifest,
      id: cleanId,
      kind: rawManifest?.kind === "community" ? "community" : "local",
    };

    // 第一阶段：全部写入独立暂存目录，写入失败绝不破坏线上插件目录
    const stageDir = path.join(this.pluginsRoot, `.${cleanId}.stage-${randomUUID().slice(0, 8)}`);
    try {
      await fs.mkdir(stageDir, {recursive: true});
      await writeJsonAtomic(path.join(stageDir, MANIFEST_FILENAME), manifestToWrite, 0o644);
      for (const [rel, content] of Object.entries(validation.cleanFiles)) {
        const abs = path.resolve(stageDir, rel);
        if (!isWithinRealRoot(abs, path.resolve(stageDir))) {
          throw new Error(`非法文件路径越界：${rel}`);
        }
        await fs.mkdir(path.dirname(abs), {recursive: true});
        await atomicWriteTextFile(abs, content, {captureCheckpoint: false, preserveMetadata: false, defaultMode: 0o644});
      }
    } catch (writeErr) {
      await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {});
      return {
        ok: false,
        rolledBack: false,
        snapshotId,
        error: `写入暂存目录失败（原插件未受影响）：${writeErr?.message || writeErr}`,
        validation,
      };
    }

    // 第二阶段：同盘原子目录切换
    let swapInfo;
    try {
      swapInfo = await this.#atomicSwapPluginDir(dir, stageDir, {
        existedBefore,
        preservePackageJson: !hasExplicitPkgJson,
      });
    } catch (swapErr) {
      return {
        ok: false,
        rolledBack: Boolean(swapErr?.rollbackSucceeded),
        rollbackError: swapErr?.rollbackError || null,
        snapshotId,
        error: `原子切换插件目录失败：${swapErr?.message || swapErr}`,
        validation,
      };
    }

    const prevEnabledExisted = Object.prototype.hasOwnProperty.call(this.state.enabled, cleanId);
    const prevEnabledValue = this.state.enabled[cleanId];
    this.state.enabled[cleanId] = true;
    await this.saveState();
    const reloaded = await this.reloadPlugin(cleanId);

    // 安全模式下跳过了扩展激活属于预期行为，不误判为激活失败
    if (this.state.safeMode && reloaded.plugin?.status === "safe_mode_skipped") {
      if (swapInfo.backupDir) await fs.rm(swapInfo.backupDir, {recursive: true, force: true}).catch(() => {});
      return {
        ok: true,
        rolledBack: false,
        safeModeSkipped: true,
        snapshotId,
        plugin: reloaded.plugin,
        generationId: reloaded.generationId,
        validation,
      };
    }

    // 激活失败：从同盘备份目录原子还原（或清理新建坏目录），如实报告回滚是否成功
    if (!reloaded.plugin || reloaded.plugin.status !== "active") {
      const failError = reloaded.plugin?.error || `插件未成功激活（status=${reloaded.plugin?.status || "unknown"}）`;
      const failLogs = reloaded.plugin?.recentLogs || [];
      let rolledBack = false;
      let rollbackError = null;
      try {
        if (prevEnabledExisted) this.state.enabled[cleanId] = prevEnabledValue;
        else delete this.state.enabled[cleanId];
        await this.saveState();

        if (swapInfo.backupDir) {
          for (const name of swapInfo.movedIntoDir) {
            const cur = path.join(dir, name);
            if (await fs.lstat(cur).then(() => true).catch(() => false)) {
              await fs.rename(cur, path.join(swapInfo.backupDir, name));
            }
          }
          await fs.rm(dir, {recursive: true, force: true});
          await fs.rename(swapInfo.backupDir, dir);
          const restored = await this.reloadPlugin(cleanId);
          if (restored.plugin && (restored.plugin.status === "active" || restored.plugin.status === "disabled" || restored.plugin.status === "safe_mode_skipped")) {
            rolledBack = true;
          } else {
            rollbackError = restored.plugin?.error || `回滚后插件状态异常（status=${restored.plugin?.status || "unknown"}）`;
          }
        } else if (snapshotId) {
          const rb = await this.rollbackSourceSnapshot(cleanId, snapshotId);
          rolledBack = Boolean(rb?.ok && rb?.plugin && rb.plugin.status === "active");
          if (!rolledBack) rollbackError = rb?.plugin?.error || "回滚快照后插件未能激活";
        } else {
          await fs.rm(dir, {recursive: true, force: true});
          await this.reloadPlugin(cleanId);
          rolledBack = true;
        }
      } catch (rbErr) {
        rolledBack = false;
        rollbackError = rbErr?.message || String(rbErr);
        // 若直接备份还原失败且存在持久化快照，做一次兜底快照恢复（同样不吞错误）
        if (snapshotId && swapInfo.backupDir) {
          try {
            const rb = await this.rollbackSourceSnapshot(cleanId, snapshotId);
            if (rb?.ok && rb?.plugin?.status === "active") {
              rolledBack = true;
              rollbackError = null;
              await fs.rm(swapInfo.backupDir, {recursive: true, force: true}).catch(() => {});
            }
          } catch (snapErr) {
            rollbackError = `${rollbackError}；快照兜底恢复失败：${snapErr?.message || snapErr}`;
          }
        }
      }
      return {
        ok: false,
        rolledBack,
        ...(rollbackError ? {rollbackError} : {}),
        snapshotId,
        error: failError,
        logs: failLogs,
        validation,
      };
    }

    if (swapInfo.backupDir) await fs.rm(swapInfo.backupDir, {recursive: true, force: true}).catch(() => {});
    return {
      ok: true,
      rolledBack: false,
      snapshotId,
      plugin: reloaded.plugin,
      generationId: reloaded.generationId,
      validation,
    };
  }

  /** 将插件源码恢复至指定历史快照并立即增量热重载（先写暂存目录再原子切换，保留已安装的 node_modules）。 */
  async rollbackSourceSnapshot(pluginId, snapshotId = null) {
    const cleanId = String(pluginId || "").trim();
    if (!PLUGIN_ID_RE.test(cleanId)) throw new Error(`无效插件 ID：${pluginId}`);
    if (snapshotId !== null && snapshotId !== undefined && String(snapshotId).trim() !== "" && !SNAPSHOT_ID_RE.test(String(snapshotId).trim())) {
      throw new Error(`非法快照 ID：${snapshotId}`);
    }
    const snaps = await this.listSourceSnapshots(cleanId);
    if (!snaps.length) throw new Error(`插件 ${cleanId} 暂无可回滚的源码快照`);
    const target = snapshotId ? snaps.find(s => s.id === String(snapshotId).trim()) : snaps[0];
    if (!target) throw new Error(`未找到快照：${snapshotId}`);
    const srcFilesDir = path.join(this.snapshotsRoot, cleanId, target.id, "files");
    const dstDir = path.join(this.pluginsRoot, cleanId);
    const snapHasPkg = await fs.stat(path.join(srcFilesDir, "package.json")).then(s => s.isFile()).catch(() => false);
    const existedBefore = await fs.stat(dstDir).then(s => s.isDirectory()).catch(() => false);
    const stageDir = path.join(this.pluginsRoot, `.${cleanId}.rollback-${randomUUID().slice(0, 8)}`);
    await fs.mkdir(this.pluginsRoot, {recursive: true});
    try {
      await fs.cp(srcFilesDir, stageDir, {recursive: true});
    } catch (cpErr) {
      await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {});
      throw cpErr;
    }
    const swapInfo = await this.#atomicSwapPluginDir(dstDir, stageDir, {
      existedBefore,
      preservePackageJson: !snapHasPkg,
    });
    if (swapInfo.backupDir) await fs.rm(swapInfo.backupDir, {recursive: true, force: true}).catch(() => {});
    const reloaded = await this.reloadPlugin(cleanId);
    return {ok: true, snapshotId: target.id, restoredVersion: target.version, plugin: reloaded.plugin, snapshot: reloaded.snapshot};
  }

  /** 移除非 core 内置插件的磁盘覆写目录并恢复 APK 原版内置插件。 */
  async restoreBuiltinPlugin(pluginId) {
    const cleanId = String(pluginId || "").trim();
    if (!this.builtinMap.has(cleanId) || this.builtinMap.get(cleanId)?.manifest?.kind === "core") {
      throw new Error(`插件 ${cleanId} 不是可恢复的内置可选插件`);
    }
    const dir = path.join(this.pluginsRoot, cleanId);
    const existed = await fs.stat(dir).then(s => s.isDirectory()).catch(() => false);
    if (existed) {
      await this.createSourceSnapshot(cleanId, "恢复内置原版前自动备份").catch(() => {});
      await fs.rm(dir, {recursive: true, force: true});
    }
    const reloaded = await this.reloadPlugin(cleanId);
    return {ok: true, plugin: reloaded.plugin, snapshot: reloaded.snapshot};
  }

  /** 现场烟测插件命令、面板渲染或自定义 RPC，供 Agent 改完立刻自验。 */
  async testPlugin({pluginId, kind = "command", target = "", args = "", params = {}, cwd = null}) {
    const cleanId = String(pluginId || "").trim();
    const rec = this.records.get(cleanId);
    if (!rec) throw new Error(`未找到插件：${cleanId}`);
    if (rec.status !== "active") {
      return {ok: false, kind, target, durationMs: 0, error: `插件当前未激活（status=${rec.status}）：${rec.error || ""}`, recentLogs: rec.logs.slice(-15)};
    }
    const started = Date.now();
    try {
      let result;
      if (kind === "panel") {
        const panelId = target || [...this.panels.values()].find(p => p.pluginId === cleanId)?.id;
        if (!panelId) throw new Error("插件未注册任何面板");
        result = await this.renderPanel({pluginId: cleanId, panelId, cwd});
        result = {title: result.title, kind: result.kind, contentLength: result.content.length, preview: result.content.slice(0, 4000)};
      } else if (kind === "rpc") {
        const method = target.startsWith("plugin.") ? target : `plugin.${cleanId}.${target}`;
        const hit = await this.invokeRpc(method, params || {});
        if (!hit.handled) throw new Error(`未注册的 RPC：${method}`);
        result = hit.result;
      } else {
        const cmdName = String(target || [...this.commands.values()].find(c => c.pluginId === cleanId)?.name || "").replace(/^\/+/, "");
        if (!cmdName) throw new Error("插件未注册任何命令");
        result = await this.runCommand({pluginId: cleanId, command: cmdName, args, cwd});
      }
      return {ok: true, kind, target, durationMs: Date.now() - started, result, recentLogs: rec.logs.slice(-10)};
    } catch (err) {
      return {ok: false, kind, target, durationMs: Date.now() - started, error: err?.message || String(err), recentLogs: rec.logs.slice(-15)};
    }
  }

  async #refreshStudioOverrides() {
    const active = [];
    for (const name of ALLOWED_STUDIO_OVERRIDE_FILES) {
      const p = path.join(this.studioOverridesRoot, name);
      if (await fs.stat(p).then(s => s.isFile()).catch(() => false)) active.push(name);
    }
    this.studioOverrides = active;
    return active;
  }

  /** 渲染插件声明式原生 Compose SDUI 页面，并进行严格 AST 安全校验。 */
  async renderSurface({pluginId, surfaceId, cwd = null, params = {}}) {
    const cleanId = String(pluginId || "").trim();
    const key = `${cleanId}:${String(surfaceId || "").trim()}`;
    const entry = this.surfaces.get(key);
    if (!entry || typeof entry.render !== "function") throw new Error(`未找到原生页面：${key}`);
    const tree = await withTimeout(
      Promise.resolve(entry.render({cwd: cwd || path.join(this.home, "workspace"), params: params || {}})),
      10_000,
      `渲染原生页面 ${entry.title}`,
    );
    const check = validateSduiTree(tree);
    if (!check.ok) throw new Error(`SDUI 结构无效：${check.errors.join("；")}`);
    return {
      pluginId: cleanId,
      surfaceId: entry.id,
      title: String(tree.title || entry.title),
      subtitle: String(tree.subtitle || ""),
      nodes: Array.isArray(tree.nodes) ? tree.nodes : (Array.isArray(tree.children) ? tree.children : []),
      nodeCount: check.nodeCount,
    };
  }

  /** 渲染动态原生槽位（例如 home.banner 卡片，返回 null 或空 nodes 时宿主自动隐藏）。 */
  async renderSlot({pluginId, slotId, cwd = null, context = {}}) {
    const cleanId = String(pluginId || "").trim();
    const key = `${cleanId}:${String(slotId || "").trim()}`;
    const entry = this.uiSlots.get(key);
    if (!entry) throw new Error(`未找到原生槽位：${key}`);
    if (typeof entry.render !== "function") {
      return {pluginId: cleanId, slotId: entry.id, slot: entry.slot, label: entry.label, badge: entry.badge, nodes: []};
    }
    const tree = await withTimeout(
      Promise.resolve(entry.render({cwd: cwd || path.join(this.home, "workspace"), context: context || {}})),
      8_000,
      `渲染槽位 ${entry.slot}:${entry.id}`,
    );
    if (!tree) return {pluginId: cleanId, slotId: entry.id, slot: entry.slot, hidden: true, nodes: []};
    const check = validateSduiTree(tree);
    if (!check.ok) throw new Error(`槽位 SDUI 结构无效：${check.errors.join("；")}`);
    return {
      pluginId: cleanId,
      slotId: entry.id,
      slot: entry.slot,
      title: String(tree.title || entry.label || ""),
      subtitle: String(tree.subtitle || ""),
      nodes: Array.isArray(tree.nodes) ? tree.nodes : (Array.isArray(tree.children) ? tree.children : []),
      hidden: false,
    };
  }

  /** 烟测插件原生 SDUI 页面，校验全部节点与动作契约。 */
  async testPluginSdui({pluginId, surfaceId = "", cwd = null, params = {}}) {
    const cleanId = String(pluginId || "").trim();
    const sid = String(surfaceId || "").trim() || [...this.surfaces.values()].find(s => s.pluginId === cleanId)?.id;
    if (!sid) return {ok: false, error: `插件 ${cleanId} 未注册任何原生 SDUI 页面`};
    const started = Date.now();
    try {
      const rendered = await this.renderSurface({pluginId: cleanId, surfaceId: sid, cwd, params});
      return {ok: true, durationMs: Date.now() - started, ...rendered};
    } catch (err) {
      return {ok: false, durationMs: Date.now() - started, error: err?.message || String(err)};
    }
  }

  /** 调用插件注册的可执行动态 Agent 驱动（支持实时流式 chunk / thought / tool / plan 以及中途取消）。 */
  async invokePluginDriver({driverId, sessionId = "", prompt = "", cwd = null, model = null, history = [], onEvent = null, signal = null}) {
    const cleanId = String(driverId || "").trim().toLowerCase();
    const entry = this.drivers.get(cleanId);
    if (!entry || typeof entry.execute !== "function") {
      throw new Error(`未找到可执行动态 Agent 驱动：${cleanId}`);
    }
    const runKey = `${cleanId}:${String(sessionId || "")}`;
    const runAbort = new AbortController();
    let runSet = this.activeDriverRuns.get(runKey);
    if (!runSet) { runSet = new Set(); this.activeDriverRuns.set(runKey, runSet); }
    runSet.add(runAbort);

    const onExternalAbort = () => {
      if (!runAbort.signal.aborted) runAbort.abort(signal?.reason || new Error("用户中断了会话"));
    };
    if (signal) {
      if (signal.aborted) onExternalAbort();
      else signal.addEventListener("abort", onExternalAbort, {once: true});
    }

    const chunks = [];
    const thoughts = [];
    const tools = [];
    const plans = [];
    try {
      const execPromise = new Promise((resolve, reject) => {
        const onRunAbort = () => resolve({text: "", stopReason: "cancelled"});
        if (runAbort.signal.aborted) { onRunAbort(); return; }
        runAbort.signal.addEventListener("abort", onRunAbort, {once: true});
        Promise.resolve(entry.execute({
          sessionId: String(sessionId || ""),
          prompt: String(prompt || ""),
          cwd: cwd || path.join(this.home, "workspace"),
          model: model || null,
          history: Array.isArray(history) ? history : [],
          signal: runAbort.signal,
          emitChunk: t => { if (runAbort.signal.aborted) return; const s = String(t ?? ""); if (s) { chunks.push(s); onEvent?.({kind: "chunk", text: s}); } },
          emitThought: t => { if (runAbort.signal.aborted) return; const s = String(t ?? ""); if (s) { thoughts.push(s); onEvent?.({kind: "thought", text: s}); } },
          emitTool: tool => { if (runAbort.signal.aborted) return; if (tool) { tools.push(tool); onEvent?.({kind: "tool", tool}); } },
          emitPlan: plan => { if (runAbort.signal.aborted) return; if (plan) { plans.push(plan); onEvent?.({kind: "plan", plan}); } },
        })).then(
          v => { runAbort.signal.removeEventListener("abort", onRunAbort); resolve(v); },
          e => { runAbort.signal.removeEventListener("abort", onRunAbort); reject(e); },
        );
      });
      const res = await withTimeout(execPromise, 120_000, `执行动态 Agent 驱动 ${cleanId}`);
      const cancelled = runAbort.signal.aborted || res?.stopReason === "cancelled";
      const finalText = (typeof res === "string" ? res : (typeof res?.text === "string" ? res.text : "")) || chunks.join("");
      return {
        text: finalText,
        stopReason: cancelled ? "cancelled" : (res?.stopReason || "end_turn"),
        usage: res?.usage || null,
        chunks,
        thoughts,
        tools,
        plans,
      };
    } finally {
      if (signal) signal.removeEventListener("abort", onExternalAbort);
      const set = this.activeDriverRuns.get(runKey);
      if (set) {
        set.delete(runAbort);
        if (set.size === 0) this.activeDriverRuns.delete(runKey);
      }
    }
  }

  /** 主动中断指定动态 Agent 驱动会话的执行（通知同进程驱动与隔离 Worker 停止输出）。 */
  cancelPluginDriver(driverId, sessionId = "") {
    const cleanId = String(driverId || "").trim().toLowerCase();
    const sid = String(sessionId || "");
    let cancelledCount = 0;
    for (const [k, set] of this.activeDriverRuns.entries()) {
      const match = sid ? k === `${cleanId}:${sid}` : k.startsWith(`${cleanId}:`);
      if (!match) continue;
      for (const ac of [...set]) {
        if (!ac.signal.aborted) {
          ac.abort(new Error("用户中断了会话"));
          cancelledCount++;
        }
      }
    }
    return {ok: true, cancelledCount};
  }

  async listPluginDriverModels(driverId) {
    const cleanId = String(driverId || "").trim().toLowerCase();
    const entry = this.drivers.get(cleanId);
    if (!entry) throw new Error(`未找到 Agent 驱动：${cleanId}`);
    if (typeof entry.listModels !== "function") return [];
    const list = await withTimeout(Promise.resolve(entry.listModels()), 8_000, `列出驱动 ${cleanId} 模型`);
    return Array.isArray(list) ? list : [];
  }

  /** 执行热插拔 LLM 协议转换器（供 provider-gateway 动态接入新服务商协议）。 */
  async translateProviderRequest({translatorId, request, context = {}}) {
    const tid = String(translatorId || "").trim().toLowerCase();
    const tr = this.translators.get(tid);
    if (!tr || typeof tr.toUpstream !== "function") throw new Error(`未找到协议转换器：${tid}`);
    return await withTimeout(Promise.resolve(tr.toUpstream(request, context)), 5_000, `协议请求转换 ${tid}`);
  }

  async translateProviderResponse({translatorId, response, context = {}}) {
    const tid = String(translatorId || "").trim().toLowerCase();
    const tr = this.translators.get(tid);
    if (!tr) throw new Error(`未找到协议转换器：${tid}`);
    if (typeof tr.fromUpstream !== "function") return response;
    return await withTimeout(Promise.resolve(tr.fromUpstream(response, context)), 5_000, `协议响应转换 ${tid}`);
  }

  /** 烟测动态 Agent 驱动或协议转换器。 */
  async testPluginDriverOrTranslator({kind = "driver", id = "", prompt = "ping", request = {}, response = {}, cwd = null}) {
    const started = Date.now();
    try {
      if (kind === "translator") {
        const translatedReq = await this.translateProviderRequest({translatorId: id, request, context: {dryRun: true}});
        const translatedRes = response && Object.keys(response).length
          ? await this.translateProviderResponse({translatorId: id, response, context: {dryRun: true}})
          : null;
        return {ok: true, kind, id, durationMs: Date.now() - started, translatedRequest: translatedReq, translatedResponse: translatedRes};
      }
      const models = await this.listPluginDriverModels(id).catch(() => []);
      const out = await this.invokePluginDriver({driverId: id, sessionId: "smoke-test", prompt, cwd});
      return {ok: true, kind: "driver", id, durationMs: Date.now() - started, models, result: out};
    } catch (err) {
      return {ok: false, kind, id, durationMs: Date.now() - started, error: err?.message || String(err)};
    }
  }

  /** 受控单插件 npm 依赖安装（严格校验包名 + 强制 --ignore-scripts 防安装期恶意脚本 + 遵循镜像源设置）。 */
  async installPluginDependencies({pluginId, packages = [], dryRun = false}) {
    const cleanId = String(pluginId || "").trim();
    const rec = this.records.get(cleanId);
    if (!rec || !rec.dir || rec.manifest.kind === "core") {
      throw new Error(`仅可为磁盘本地/社区/覆写插件安装依赖：${cleanId}`);
    }
    if (!rec.grantedCapabilities.includes("deps.npm")) {
      throw new Error(`插件 ${cleanId} 未获得 deps.npm 有效授权，拒绝安装依赖`);
    }
    const cleanPkgs = [];
    for (const raw of (Array.isArray(packages) ? packages : [])) {
      const spec = String(raw || "").trim();
      if (!NPM_PKG_SPEC_RE.test(spec)) throw new Error(`非法 npm 包名规范：${spec}`);
      cleanPkgs.push(spec);
    }
    if (cleanPkgs.length === 0) throw new Error("请至少指定一个 npm 包名");
    if (cleanPkgs.length > 12) throw new Error("单次最多安装 12 个依赖包");
    if (dryRun) return {ok: true, dryRun: true, pluginId: cleanId, packages: cleanPkgs, dir: rec.dir};

    const pkgJsonPath = path.join(rec.dir, "package.json");
    const hasPkgJson = await fs.stat(pkgJsonPath).then(s => s.isFile()).catch(() => false);
    if (!hasPkgJson) {
      await writeJsonAtomic(pkgJsonPath, {name: cleanId.replace(/[^a-z0-9._-]/g, "-"), private: true, type: "module"}, 0o644);
    }
    const netCfg = await readDownloadNetwork().catch(() => null);
    const env = {...process.env, ...(netCfg ? downloadEnvironment(netCfg, process.env) : {})};
    const npmBin = process.platform === "win32" ? "npm.cmd" : "/opt/galiais-codex/usr/lib/chatgpt/resources/npm";
    const resolvedNpm = (process.platform !== "win32" && !(await fs.stat(npmBin).then(s => s.isFile()).catch(() => false))) ? "npm" : npmBin;
    const started = Date.now();
    const {stdout, stderr} = await execFileAsync(
      resolvedNpm,
      ["install", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", ...cleanPkgs],
      {cwd: rec.dir, env, timeout: 60_000, maxBuffer: 2 * 1024 * 1024},
    );
    this.#pushLog(rec, "info", `已安装依赖：${cleanPkgs.join(", ")}`);
    return {
      ok: true,
      pluginId: cleanId,
      packages: cleanPkgs,
      durationMs: Date.now() - started,
      output: String(stdout || stderr || "").trim().slice(0, 1000),
    };
  }

  /** 规范化并校验本机 ARM64 APK 自迭代配置（默认关闭 enabled=false）。 */
  #normalizeSelfBuildConfig(raw = {}, base = null) {
    const defaults = {
      enabled: false,
      allowAgentBuild: false,
      allowAgentApply: false,
      runUnitTests: true,
      watchdogEnabled: true,
      watchdogTimeoutSec: 20,
      repoDir: "/home/agent/workspace/Solbraid",
      androidHome: "/home/agent/android-sdk",
      javaHome: "",
      aapt2Path: "",
      signingPropertiesPath: "/home/agent/.galiais/self-build-signing.properties",
      targetAbis: "arm64-v8a",
      variant: "debug",
      gradleHeapMb: 4096,
      customGradleArgs: "",
    };
    const merged = {...defaults, ...(base || {}), ...(raw && typeof raw === "object" ? raw : {})};
    const cleanPath = (val, fallback = "") => {
      const s = String(val ?? fallback).trim();
      if (!s) return "";
      if (/[\0\r\n]/.test(s) || !s.startsWith("/")) throw new Error(`无效绝对路径：${s}`);
      return s.replace(/\/+$/, "") || "/";
    };
    const allowedAbis = new Set(["arm64-v8a", "arm64-v8a,x86_64"]);
    const allowedHeap = new Set([2048, 3072, 4096, 6144]);
    const abis = allowedAbis.has(String(merged.targetAbis || "")) ? String(merged.targetAbis) : "arm64-v8a";
    const variant = merged.variant === "release" ? "release" : "debug";
    const heapRaw = Number(merged.gradleHeapMb);
    const gradleHeapMb = allowedHeap.has(heapRaw) ? heapRaw : 4096;
    const timeoutRaw = Number(merged.watchdogTimeoutSec);
    const watchdogTimeoutSec = Number.isFinite(timeoutRaw) ? Math.max(10, Math.min(120, Math.round(timeoutRaw))) : 20;
    const customGradleArgs = String(merged.customGradleArgs || "").trim();
    if (customGradleArgs.length > 512) throw new Error("附加 Gradle 参数不能超过 512 字符");
    if (/[;&|`$()<>\r\n\0]/.test(customGradleArgs)) {
      throw new Error("附加 Gradle 参数包含不允许的特殊字符");
    }
    if (customGradleArgs) {
      for (const tok of customGradleArgs.split(/\s+/)) {
        if (!/^-[A-Za-z0-9._:=/-]+$/.test(tok)) {
          throw new Error(`附加 Gradle 参数格式无效：${tok}`);
        }
      }
    }
    return {
      enabled: merged.enabled === true,
      allowAgentBuild: merged.allowAgentBuild === true,
      allowAgentApply: merged.allowAgentApply === true,
      runUnitTests: merged.runUnitTests !== false,
      watchdogEnabled: merged.watchdogEnabled !== false,
      watchdogTimeoutSec,
      repoDir: cleanPath(merged.repoDir, defaults.repoDir) || defaults.repoDir,
      androidHome: cleanPath(merged.androidHome, defaults.androidHome) || defaults.androidHome,
      javaHome: cleanPath(merged.javaHome, ""),
      aapt2Path: cleanPath(merged.aapt2Path, ""),
      signingPropertiesPath: cleanPath(merged.signingPropertiesPath, defaults.signingPropertiesPath) || defaults.signingPropertiesPath,
      targetAbis: abis,
      variant,
      gradleHeapMb,
      customGradleArgs,
    };
  }

  async getSelfBuildConfig() {
    const saved = await readJsonSafe(this.selfBuildConfigFile, null);
    try {
      return this.#normalizeSelfBuildConfig(saved || {});
    } catch {
      return this.#normalizeSelfBuildConfig({});
    }
  }

  async saveSelfBuildConfig(patch = {}) {
    const current = await this.getSelfBuildConfig();
    const next = this.#normalizeSelfBuildConfig(patch, current);
    await writeJsonAtomic(this.selfBuildConfigFile, next);
    return next;
  }

  /** 方案 1：本机纯本地 ARM64 APK 自迭代流水线控制（默认关闭，完全遵循设置开关与路径/构建参数配置）。 */
  async manageSelfBuild({
    action = "status",
    source = "agent",
    repoDir = "",
    variant = "",
    abis = "",
    skipTests = undefined,
    apkPath = "",
    confirmInstall = false,
    confirmRollback = false,
    onHostInstallApk = null,
    onHostRollbackApk = null,
    onHostStatus = null,
  } = {}) {
    const act = String(action || "status").trim().toLowerCase();
    const cfg = await this.getSelfBuildConfig();
    const runtimeDir = path.dirname(fileURLToPath(import.meta.url));
    const scriptPath = path.join(runtimeDir, "self-build-apk.sh");
    const watchdogPath = path.join(runtimeDir, "self-update-watchdog.sh");
    const effectiveRepo = String(repoDir || "").trim() || cfg.repoDir;
    const effectiveVariant = (variant === "release" || variant === "debug") ? variant : cfg.variant;
    const effectiveAbis = (abis === "arm64-v8a" || abis === "arm64-v8a,x86_64") ? abis : cfg.targetAbis;
    const effectiveSkipTests = source === "agent"
      ? !cfg.runUnitTests
      : (typeof skipTests === "boolean" ? skipTests : !cfg.runUnitTests);

    const buildEnv = {
      ...process.env,
      HOME: this.home,
      SOLBRAID_REPO_DIR: effectiveRepo,
      ANDROID_HOME: cfg.androidHome,
      ANDROID_SDK_ROOT: cfg.androidHome,
      SOLBRAID_GRADLE_HEAP_MB: String(cfg.gradleHeapMb),
      SOLBRAID_SIGNING_PROPERTIES: cfg.signingPropertiesPath,
      SOLBRAID_WATCHDOG_ENABLED: cfg.watchdogEnabled ? "true" : "false",
      SOLBRAID_WATCHDOG_TIMEOUT: String(cfg.watchdogTimeoutSec),
      ...(cfg.javaHome ? {JAVA_HOME: cfg.javaHome} : {}),
      ...(cfg.aapt2Path ? {SOLBRAID_AAPT2_BIN: cfg.aapt2Path} : {}),
      ...(cfg.customGradleArgs ? {SOLBRAID_EXTRA_GRADLE_ARGS: cfg.customGradleArgs} : {}),
    };

    if (act === "status") {
      const hasScript = await fs.stat(scriptPath).then(s => s.isFile()).catch(() => false);
      const hasWatchdog = await fs.stat(watchdogPath).then(s => s.isFile()).catch(() => false);
      if (process.platform === "win32" || !hasScript) {
        return {
          ok: true,
          enabled: cfg.enabled,
          config: cfg,
          platform: process.platform,
          arch: process.arch,
          scriptPath,
          watchdogPath,
          scriptAvailable: hasScript,
          watchdogAvailable: hasWatchdog,
          readyForLocalBuild: false,
          note: cfg.enabled
            ? "本机 APK 自迭代脚本运行于手机端 Linux (ARM64) 容器内"
            : "本机 APK 自迭代当前已关闭（默认关闭），可在「设置 → 本机 APK 自迭代」中启用与配置",
        };
      }
      const args = [scriptPath, "status", ...(effectiveRepo ? [effectiveRepo] : [])];
      const {stdout} = await execFileAsync("bash", args, {
        env: buildEnv,
        timeout: 15_000,
        maxBuffer: 1024 * 1024,
      });
      const parsed = JSON.parse(String(stdout || "{}").trim() || "{}");
      const hostStatus = typeof onHostStatus === "function" ? await onHostStatus() : {};
      return {
        ok: true,
        enabled: cfg.enabled,
        config: cfg,
        platform: process.platform,
        scriptPath,
        watchdogPath,
        scriptAvailable: hasScript,
        watchdogAvailable: hasWatchdog,
        ...parsed,
        ...hostStatus,
      };
    }

    if (!cfg.enabled) {
      throw new Error("本机 ARM64 APK 自迭代功能当前已关闭（默认关闭），请先在「设置 → 本机 APK 自迭代」中启用");
    }

    if (act === "bootstrap") {
      if (source === "agent") {
        throw new Error("初始化编译工具链请在「设置 → 本机 APK 自迭代」中由用户手动触发");
      }
      if (process.platform === "win32") throw new Error("self-build-apk.sh 仅在 Android 端 Linux 容器内执行");
      const started = Date.now();
      const {stdout, stderr} = await execFileAsync("bash", [scriptPath, "bootstrap", effectiveRepo], {
        env: buildEnv,
        timeout: 8 * 60 * 1000,
        maxBuffer: 4 * 1024 * 1024,
      });
      const lines = String(stdout || "").trim().split(/\r?\n/);
      let parsed = null;
      for (let i = lines.length - 1; i >= 0; i--) {
        if (lines[i].startsWith("{")) {
          try { parsed = JSON.parse(lines[i]); break; } catch {}
        }
      }
      return {
        ok: true,
        enabled: cfg.enabled,
        config: cfg,
        durationMs: Date.now() - started,
        ...(parsed || {}),
        outputTail: (String(stdout || "") + "\n" + String(stderr || "")).trim().slice(-2400),
      };
    }

    if (act === "build") {
      if (source === "agent" && !cfg.allowAgentBuild) {
        throw new Error("当前未允许 Agent 触发本机 APK 编译，请在「设置 → 本机 APK 自迭代」中开启「允许 Agent 触发本地编译」");
      }
      if (process.platform === "win32") throw new Error("self-build-apk.sh 仅在 Android 端 Linux 容器内执行");
      const args = [scriptPath, "build", "--variant", effectiveVariant, "--abis", effectiveAbis];
      if (effectiveRepo) args.push("--repo", effectiveRepo);
      if (effectiveSkipTests) args.push("--skip-tests");
      const started = Date.now();
      const {stdout, stderr} = await execFileAsync("bash", args, {
        env: buildEnv,
        timeout: 15 * 60 * 1000,
        maxBuffer: 8 * 1024 * 1024,
      });
      const text = String(stdout || "");
      const match = /SOLBRAID_SELF_BUILD_RESULT:(\{.*\})/.exec(text);
      const meta = match ? JSON.parse(match[1]) : {ok: true};
      return {
        ...meta,
        durationMs: Date.now() - started,
        outputTail: (text + "\n" + String(stderr || "")).trim().slice(-2400),
      };
    }

    if (act === "apply") {
      if (source === "agent" && !cfg.allowAgentApply) {
        throw new Error("当前未允许 Agent 触发本机 APK 安装，请在「设置 → 本机 APK 自迭代」中开启「允许 Agent 触发看门狗安装」");
      }
      if (confirmInstall !== true) {
        throw new Error("安全护栏：安装新 APK 将重启 Solbraid 并中断当前会话，请由用户明确确认后传入 confirmInstall: true");
      }
      const cleanApk = String(apkPath || "").trim();
      if (!cleanApk || !cleanApk.endsWith(".apk")) throw new Error("必须提供有效的 .apk 文件路径");
      const realApk = await fs.realpath(cleanApk).catch(() => null);
      if (!realApk) throw new Error(`候选 APK 不存在：${cleanApk}`);
      const st = await fs.stat(realApk).catch(() => null);
      if (!st?.isFile()) throw new Error(`候选路径不是有效文件：${cleanApk}`);
      if (process.platform === "win32") throw new Error("自动启动看门狗安装仅支持 Android Root 环境");
      if (typeof onHostInstallApk !== "function") throw new Error("Android 安装服务未连接，请重新连接应用");
      return await onHostInstallApk(realApk);
    }

    if (act === "rollback") {
      if (source === "agent") {
        throw new Error("版本回滚请在「设置 → 本机 APK 自迭代」中由用户手动触发");
      }
      if (confirmRollback !== true) {
        throw new Error("安全护栏：回滚上一稳定版本将重启 Solbraid，请确认后传入 confirmRollback: true");
      }
      if (process.platform === "win32") throw new Error("看门狗回滚仅支持 Android Root 环境");
      if (typeof onHostRollbackApk !== "function") throw new Error("Android 回滚服务未连接，请重新连接应用");
      return await onHostRollbackApk();
    }

    throw new Error(`不支持的自构建动作：${act}（可选 status | bootstrap | build | apply | rollback）`);
  }

  /** 安全管理 Artifact Studio 渲染器热覆写（~/.galiais/studio-overrides/）：白名单 + 语法校验 + 自动快照与一键恢复原版。 */
  async manageStudioOverride({action = "list", fileName = "", content = "", snapshotId = ""}) {
    const snapDirRoot = path.join(this.studioOverridesRoot, ".snapshots");
    if (action === "list") {
      const active = await this.#refreshStudioOverrides();
      const snaps = await fs.readdir(snapDirRoot, {withFileTypes: true}).catch(() => []);
      return {
        ok: true,
        allowedFiles: ALLOWED_STUDIO_OVERRIDE_FILES,
        activeOverrides: active,
        safeMode: this.state.safeMode,
        snapshotIds: snaps.filter(s => s.isDirectory()).map(s => s.name).sort().reverse().slice(0, 10),
      };
    }
    const cleanName = String(fileName || "").trim();
    if (!ALLOWED_STUDIO_OVERRIDE_FILES.includes(cleanName)) {
      throw new Error(`仅允许覆写白名单渲染器文件：${ALLOWED_STUDIO_OVERRIDE_FILES.join(", ")}`);
    }
    const targetPath = path.join(this.studioOverridesRoot, cleanName);
    if (action === "read") {
      const exists = await fs.stat(targetPath).then(s => s.isFile()).catch(() => false);
      return {ok: true, fileName: cleanName, exists, content: exists ? await fs.readFile(targetPath, "utf8") : ""};
    }
    if (action === "reset") {
      await fs.rm(targetPath, {force: true}).catch(() => {});
      await this.#refreshStudioOverrides();
      this.onNotify("plugins/changed", {generationId: this.generationId, studioOverrides: this.studioOverrides});
      return {ok: true, reset: true, fileName: cleanName, activeOverrides: this.studioOverrides};
    }
    if (action === "rollback") {
      const cleanSnapId = String(snapshotId || "").trim();
      if (cleanSnapId && !SNAPSHOT_ID_RE.test(cleanSnapId)) {
        throw new Error(`非法 Studio 快照 ID：${snapshotId}`);
      }
      const snaps = (await fs.readdir(snapDirRoot, {withFileTypes: true}).catch(() => []))
        .filter(s => s.isDirectory() && SNAPSHOT_ID_RE.test(s.name)).map(s => s.name).sort().reverse();
      const pick = cleanSnapId || snaps[0];
      if (!pick || !snaps.includes(pick)) throw new Error("暂无匹配的可回滚 Studio 覆写快照");
      const src = path.resolve(snapDirRoot, pick, cleanName);
      if (!src.startsWith(path.resolve(snapDirRoot) + path.sep)) throw new Error("快照路径越界");
      const exists = await fs.stat(src).then(s => s.isFile()).catch(() => false);
      if (!exists) {
        await fs.rm(targetPath, {force: true}).catch(() => {});
      } else {
        await fs.mkdir(this.studioOverridesRoot, {recursive: true});
        await fs.copyFile(src, targetPath);
      }
      await this.#refreshStudioOverrides();
      this.onNotify("plugins/changed", {generationId: this.generationId, studioOverrides: this.studioOverrides});
      return {ok: true, rolledBack: true, snapshotId: pick, fileName: cleanName, activeOverrides: this.studioOverrides};
    }
    if (action === "apply") {
      const text = String(content ?? "");
      if (!text.trim()) throw new Error("覆写内容不能为空");
      if (Buffer.byteLength(text, "utf8") > 2 * 1024 * 1024) throw new Error("Studio 覆写文件超过 2 MB 上限");
      if (/<script[^>]+src\s*=\s*["']https?:/i.test(text) || /@import\s+(?:url\(\s*)?["']?https?:/i.test(text)) {
        throw new Error("安全拦截：Studio 离线渲染器禁止注入远程外部脚本或样式链接");
      }
      if (cleanName.endsWith(".js")) {
        const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "solbraid-studiochk-"));
        try {
          const tmpFile = path.join(tmpDir, cleanName);
          await fs.writeFile(tmpFile, text, "utf8");
          await execFileAsync(process.execPath, ["--check", tmpFile], {timeout: 6_000});
        } catch (synErr) {
          throw new Error(`Studio JS 语法校验失败：${String(synErr?.stderr || synErr?.message || "").trim().slice(0, 320)}`);
        } finally {
          await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
        }
      }
      await fs.mkdir(this.studioOverridesRoot, {recursive: true});
      let createdSnapId = null;
      if (await fs.stat(targetPath).then(s => s.isFile()).catch(() => false)) {
        createdSnapId = `${Date.now()}-${randomUUID().slice(0, 6)}`;
        const dir = path.join(snapDirRoot, createdSnapId);
        await fs.mkdir(dir, {recursive: true});
        await fs.copyFile(targetPath, path.join(dir, cleanName));
      }
      const tmpOut = `${targetPath}.${randomUUID().slice(0, 6)}.tmp`;
      await fs.writeFile(tmpOut, text, {encoding: "utf8", mode: 0o644});
      await fs.rename(tmpOut, targetPath);
      await this.#refreshStudioOverrides();
      this.onNotify("plugins/changed", {generationId: this.generationId, studioOverrides: this.studioOverrides});
      return {ok: true, applied: true, fileName: cleanName, snapshotId: createdSnapId, bytes: Buffer.byteLength(text, "utf8"), activeOverrides: this.studioOverrides};
    }
    throw new Error(`未知 Studio 覆写动作：${action}`);
  }

  snapshot() {
    const plugins = [];
    for (const rec of this.records.values()) {
      const m = rec.manifest;
      const cmds = [...this.commands.values()]
        .filter(c => c.pluginId === m.id)
        .map(({name, description, promptTemplate}) => ({name, description, hasHandler: !promptTemplate}));
      const panels = [...this.panels.values()]
        .filter(p => p.pluginId === m.id)
        .map(({id, title, description}) => ({id, title, description}));
      const surfaces = [...this.surfaces.values()]
        .filter(s => s.pluginId === m.id)
        .map(({id, title, description}) => ({pluginId: m.id, id, title, description}));
      const uiSlots = [...this.uiSlots.values()]
        .filter(s => s.pluginId === m.id)
        .map(({id, slot, label, description, badge, surfaceId, panelId, command, routes, alignment, offsetX, offsetY, draggable, displayMode, render}) => ({
          pluginId: m.id, id, slot, label, description, badge, surfaceId, panelId, command,
          routes: routes || [], alignment: alignment || "bottomEnd", offsetX: offsetX ?? 0, offsetY: offsetY ?? 0,
          draggable: Boolean(draggable), displayMode: displayMode || "", hasRender: typeof render === "function",
        }));
      const actions = [...this.composerActions.values()]
        .filter(a => a.pluginId === m.id)
        .map(({id, label, description, command, panelId}) => ({id, label, description, command, panelId}));
      const translators = [...this.translators.values()]
        .filter(t => t.pluginId === m.id)
        .map(({id, name, description, baseProtocol}) => ({pluginId: m.id, id, name, description, baseProtocol}));
      const providedServices = [...this.services.entries()]
        .filter(([, v]) => v.pluginId === m.id)
        .map(([k]) => k);
      plugins.push({
        id: m.id,
        name: m.name,
        version: m.version,
        description: m.description,
        author: m.author || "Solbraid",
        kind: m.kind,
        overriddenBuiltin: Boolean(rec.overriddenBuiltin),
        executionTier: m.executionTier,
        status: rec.status,
        enabled: rec.enabled,
        error: rec.error,
        dirPath: rec.dir || "",
        snapshotCount: this.snapshotCounts.get(m.id) || 0,
        capabilities: m.capabilities,
        grantedCapabilities: rec.grantedCapabilities,
        degradedOptional: rec.degradedOptional,
        settingsSchema: m.settingsSchema,
        settings: rec.settings,
        commands: cmds,
        panels,
        surfaces,
        uiSlots,
        composerActions: actions,
        translators,
        services: providedServices,
        recentLogs: rec.logs.slice(-20),
      });
    }
    return {
      hostDescriptor: HOST_DESCRIPTOR,
      generationId: this.generationId,
      generationSeq: this.generationSeq,
      safeMode: this.state.safeMode,
      hasLastKnownGood: this.hasLastKnownGood,
      agentControlAvailable: Boolean(this.agentControlAvailable),
      pluginsRoot: this.pluginsRoot,
      studioOverridesRoot: this.studioOverridesRoot,
      studioOverrides: [...this.studioOverrides],
      drivers: [...this.drivers.values()].map(({execute: _e, listModels: _m, ...rest}) => rest),
      translators: [...this.translators.values()].map(({toUpstream: _t, fromUpstream: _f, ...rest}) => rest),
      surfaces: [...this.surfaces.values()].map(({render: _r, ...rest}) => rest),
      uiSlots: [...this.uiSlots.values()].map(({render, ...rest}) => ({...rest, hasRender: typeof render === "function"})),
      composerActions: [...this.composerActions.values()],
      plugins,
    };
  }
}

/**
 * 启动本地受保护插件控制通道（Unix Socket + 随机令牌文件，权限 0600）。
 * 供 `plugin-mcp.mjs` 将 Codex / Claude Code / Antigravity 的 MCP 工具调用安全桥接到 PluginKernel。
 */
export async function startPluginControlServer(kernel, options = {}) {
  const socketPath = options.socketPath || path.join(kernel.home, ".galiais", "run", "plugin-ctl.sock");
  const tokenFile = options.tokenFile || path.join(kernel.home, ".galiais", "run", "plugin-ctl.token");
  const token = options.token || randomBytes(24).toString("hex");
  const tokenBuf = Buffer.from(token, "utf8");
  const isWinPipe = process.platform === "win32" && socketPath.startsWith("\\\\.\\pipe\\");

  await fs.mkdir(path.dirname(tokenFile), {recursive: true, mode: 0o700});
  const tmpTok = `${tokenFile}.${randomUUID().slice(0, 8)}.tmp`;
  await fs.writeFile(tmpTok, token + "\n", {encoding: "utf8", mode: 0o600});
  await fs.rename(tmpTok, tokenFile);

  if (!isWinPipe) {
    await fs.mkdir(path.dirname(socketPath), {recursive: true, mode: 0o700});
    await fs.rm(socketPath, {force: true}).catch(() => {});
  }

  let mutationQueue = Promise.resolve();
  const serialize = fn => {
    const run = mutationQueue.then(fn, fn);
    mutationQueue = run.catch(() => {});
    return run;
  };

  async function dispatch(action, params = {}) {
    switch (action) {
      case "list":
        return kernel.snapshot();
      case "read":
        return await kernel.readPluginSource(params.pluginId);
      case "scaffold":
        return await serialize(async () => {
          const snap = await kernel.createLocalPlugin({
            id: params.id,
            name: params.name,
            description: params.description || "",
          });
          const cleanId = String(params.id || "").trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
          return {
            ok: true,
            plugin: snap.plugins.find(p => p.id === cleanId) || null,
            generationId: snap.generationId,
          };
        });
      case "validate":
        return await kernel.validatePluginCandidate(params);
      case "apply":
        return await serialize(() => kernel.applyPluginSourceUpdate(params));
      case "reload":
        return await serialize(async () => {
          if (params.pluginId) {
            const res = await kernel.reloadPlugin(params.pluginId);
            return {plugin: res.plugin, generationId: res.generationId, generationSeq: res.generationSeq};
          }
          const snap = await kernel.boot();
          return {
            generationId: snap.generationId,
            generationSeq: snap.generationSeq,
            activeCount: snap.plugins.filter(p => p.status === "active").length,
          };
        });
      case "test":
        return await kernel.testPlugin(params);
      case "logs": {
        const snap = kernel.snapshot();
        const found = snap.plugins.find(p => p.id === String(params.pluginId || "").trim());
        if (!found) throw new Error(`未找到插件：${params.pluginId}`);
        return {
          pluginId: found.id,
          status: found.status,
          enabled: found.enabled,
          error: found.error,
          overriddenBuiltin: found.overriddenBuiltin,
          grantedCapabilities: found.grantedCapabilities,
          degradedOptional: found.degradedOptional,
          recentLogs: found.recentLogs,
        };
      }
      case "snapshots":
        return {
          pluginId: String(params.pluginId || "").trim(),
          snapshots: await kernel.listSourceSnapshots(params.pluginId),
        };
      case "rollback":
        return await serialize(async () => {
          if (params.restoreBuiltin) {
            const res = await kernel.restoreBuiltinPlugin(params.pluginId);
            return {ok: true, restoredBuiltin: true, plugin: res.plugin};
          }
          const res = await kernel.rollbackSourceSnapshot(params.pluginId, params.snapshotId || null);
          return {ok: true, snapshotId: res.snapshotId, restoredVersion: res.restoredVersion, plugin: res.plugin};
        });
      case "installDeps":
        return await serialize(() => kernel.installPluginDependencies(params));
      case "testSdui":
        return await kernel.testPluginSdui(params);
      case "studioOverride":
        return await serialize(() => kernel.manageStudioOverride(params));
      case "testDriver":
        return await kernel.testPluginDriverOrTranslator(params);
      case "translateRequest":
        return await kernel.translateProviderRequest(params);
      case "translateResponse":
        return await kernel.translateProviderResponse(params);
      case "listTranslators":
        return {translators: kernel.snapshot().translators};
      case "selfBuild":
        return await serialize(() => kernel.manageSelfBuild({
          ...params,
          source: "agent",
          onHostInstallApk: options.onHostInstallApk || null,
          onHostStatus: options.onHostStatus || null,
        }));
      case "selfBuildRequestRollback": {
        if (typeof options.onHostRollbackRequest !== "function") throw new Error("Android 回滚服务未连接");
        return await options.onHostRollbackRequest();
      }
      case "fileRead": {
        const target = await resolveSafeHomePath(params.path, {cwd: params.cwd, allowedRoot: kernel.home});
        const res = await safeReadTextFile(target, {
          startLine: params.startLine ?? params.line,
          endLine: params.endLine,
          limit: params.limit,
          encoding: params.encoding,
        });
        return {
          path: target,
          encoding: res.encoding,
          lineEnding: res.lineEnding,
          startLine: res.startLine,
          endLine: res.endLine,
          lineCount: res.lineCount,
          totalLines: res.totalLines,
          totalBytes: res.totalBytes,
          truncated: res.truncated,
          sha256: res.sha256,
          content: res.numberedContent,
        };
      }
      case "fileEdit":
        return await serialize(async () => {
          const target = await resolveSafeHomePath(params.path, {cwd: params.cwd, allowedRoot: kernel.home});
          return await safeEditFile(target, {
            oldText: params.oldText,
            newText: params.newText,
            replaceAll: params.replaceAll,
            edits: params.edits,
            startLine: params.startLine,
            endLine: params.endLine,
            expectedSha256: params.expectedSha256,
            sessionId: params.sessionId || "agent-fs",
          });
        });
      case "fileCreate":
        return await serialize(async () => {
          const target = await resolveSafeHomePath(params.path, {cwd: params.cwd, allowedRoot: kernel.home, allowMissing: true});
          return await safeCreateFile(target, String(params.content ?? ""), {
            conflictPolicy: params.conflictPolicy || "fail_if_exists",
            encoding: params.encoding || "utf-8",
            lineEnding: params.lineEnding || "LF",
            mode: params.mode,
            sessionId: params.sessionId || "agent-fs",
          });
        });
      case "fileSearch": {
        const target = await resolveSafeHomePath(params.path, {cwd: params.cwd, allowedRoot: kernel.home});
        return await streamSearchFile(target, {
          query: params.query,
          useRegex: Boolean(params.useRegex),
          caseSensitive: Boolean(params.caseSensitive),
          maxHits: params.maxHits,
        });
      }
      case "fileRevert":
        return await serialize(async () => {
          const target = await resolveSafeHomePath(params.path, {cwd: params.cwd, allowedRoot: kernel.home, allowMissing: true});
          return await revertFileCheckpoint({
            sessionId: params.sessionId || "agent-fs",
            checkpointId: String(params.checkpointId || ""),
            filePath: target,
          });
        });
      case "mobileStatus":
      case "mobileScreenshot":
      case "mobileUiTree":
      case "mobileTap":
      case "mobileSwipe":
      case "mobileType":
      case "mobileKey":
      case "mobileApp":
      case "mobileClipboard":
      case "mobileDevice":
      case "mobileIntent":
      case "mobileNotification":
      case "mobileFiles":
      case "mobileHost": {
        if (typeof options.onMobileUse !== "function") {
          throw new Error("Android 手机操控桥接通道未就绪，请确认运行环境已连接到 Solbraid 宿主");
        }
        const mobileActionMap = {
          mobileStatus: "status",
          mobileScreenshot: "screenshot",
          mobileUiTree: "ui_tree",
          mobileTap: "tap",
          mobileSwipe: "swipe",
          mobileType: "type",
          mobileKey: "key",
          mobileApp: "app",
          mobileClipboard: "clipboard",
          mobileDevice: "device",
          mobileIntent: "intent",
          mobileNotification: "notification",
          mobileFiles: "files",
          mobileHost: "host",
        };
        const mobileAction = mobileActionMap[action];
        const call = () => options.onMobileUse(mobileAction, params || {});
        return mobileAction === "status" ? await call() : await serialize(call);
      }
      default:
        throw new Error(`未知插件控制动作：${action}`);
    }
  }

  const server = net.createServer(conn => {
    let buf = "";
    conn.setEncoding("utf8");
    conn.on("error", () => {});
    conn.on("data", chunk => {
      buf += chunk;
      if (buf.length > 4 * 1024 * 1024) { conn.destroy(); return; }
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      const line = buf.slice(0, nl);
      buf = "";
      let req;
      try { req = JSON.parse(line); } catch { conn.destroy(); return; }
      const reply = val => { if (!conn.destroyed) conn.end(JSON.stringify(val) + "\n"); };
      const reqTokenBuf = typeof req?.token === "string" ? Buffer.from(req.token, "utf8") : null;
      if (!reqTokenBuf || reqTokenBuf.length !== tokenBuf.length || !timingSafeEqual(reqTokenBuf, tokenBuf)) {
        reply({ok: false, error: "插件控制通道令牌无效"});
        return;
      }
      Promise.resolve()
        .then(() => dispatch(String(req.action || ""), req.params && typeof req.params === "object" ? req.params : {}))
        .then(data => reply({ok: true, data}))
        .catch(err => reply({ok: false, error: err?.message || String(err)}));
    });
  });

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(socketPath, resolve);
  });
  if (!isWinPipe && process.platform !== "win32") {
    try { await fs.chmod(socketPath, 0o600); }
    catch (err) { server.close(); throw err; }
  }
  kernel.agentControlAvailable = true;

  return {
    socketPath,
    tokenFile,
    token,
    async close() {
      kernel.agentControlAvailable = false;
      await new Promise(resolve => server.close(() => resolve()));
      if (!isWinPipe) await fs.rm(socketPath, {force: true}).catch(() => {});
      await fs.rm(tokenFile, {force: true}).catch(() => {});
    },
  };
}
