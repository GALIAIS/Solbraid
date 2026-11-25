// Solbraid Host 插件微内核（借鉴 Cordis Generation 生命周期 + Community Fabric Capability Broker）。
// 职责：静态 Manifest 校验、能力协商与授权、资源归属（effect/dispose）、故障隔离、安全模式与 Last-Known-Good 回滚。
import fs from "node:fs/promises";
import path from "node:path";
import {randomUUID} from "node:crypto";
import {pathToFileURL} from "node:url";

export const HOST_API_VERSION = "1.0.0";
export const MANIFEST_FILENAME = "solbraid-plugin.json";

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
  "services.provide",
  "services.consume",
  "workspace.read",
  "agent.driver",
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
      executionTier: "trusted-in-process",
      entrypoint,
      capabilities: {required, optional},
      settingsSchema,
      contributes: {commands, panels},
    } : null,
  };
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
  constructor({home = process.env.HOME || "/home/agent", onNotify = () => {}} = {}) {
    this.home = home;
    this.pluginsRoot = path.join(home, ".galiais", "plugins");
    this.dataRoot = path.join(home, ".galiais", "plugins-data");
    this.configRoot = path.join(home, ".galiais", "plugins-config");
    this.stateFile = path.join(home, ".galiais", "plugins-state.json");
    this.lkgFile = path.join(home, ".galiais", "plugins-last-known-good.json");
    this.onNotify = onNotify;

    this.generationSeq = 0;
    this.generationId = "";
    this.builtins = []; // [{manifest, activate}]
    this.state = {safeMode: false, enabled: {}, grantedCaps: {}, updatedAt: 0};
    this.hasLastKnownGood = false;

    this.records = new Map();     // pluginId -> runtime record
    this.services = new Map();    // serviceName -> {pluginId, impl}
    this.commands = new Map();    // `${pluginId}:${name}` -> {pluginId, name, description, promptTemplate, handler}
    this.rpcs = new Map();        // `plugin.${pluginId}.${method}` -> {pluginId, handler}
    this.panels = new Map();      // `${pluginId}:${panelId}` -> {pluginId, id, title, description, htmlFile, render}
    this.observers = new Map();   // topic -> Set<{pluginId, fn}>
    this.drivers = new Map();     // driverId -> {pluginId, id, name, protocol, builtin, description}
  }

  registerBuiltin(rawManifest, activate) {
    const checked = validatePluginManifest(rawManifest, {allowCore: true});
    if (!checked.ok) throw new Error(`内置插件清单无效：${checked.errors.join("; ")}`);
    this.builtins.push({manifest: checked.manifest, activate});
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
      if (!entry.isDirectory()) continue;
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
      if (this.records.has(m.id)) continue;
      const enabled = this.state.enabled[m.id] ?? true;
      const rec = this.#createRecord(m, {dir: item.dir, activate: null, enabled});
      this.records.set(m.id, rec);
      if (this.state.safeMode) {
        rec.status = "safe_mode_skipped";
      } else if (enabled) {
        await this.#activateRecord(rec);
        if (rec.status === "failed") userFailureCount += 1;
      }
    }

    if (!this.state.safeMode && userFailureCount === 0) {
      await this.commitLastKnownGood().catch(() => {});
    }
    this.onNotify("plugins/changed", {generationId: this.generationId, generationSeq: this.generationSeq, safeMode: this.state.safeMode});
    return this.snapshot();
  }

  #createRecord(manifest, {dir, activate, enabled}) {
    return {
      manifest, dir, activate, enabled,
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

    if (granted.has("workspace.read")) {
      const homeRoot = path.resolve(this.home);
      ctx.workspace = Object.freeze({
        async readDir(targetDir) {
          const resolved = path.resolve(targetDir || path.join(homeRoot, "workspace"));
          if (resolved !== homeRoot && !resolved.startsWith(homeRoot + path.sep)) throw new Error("禁止越界读取工作区之外路径");
          const list = await fs.readdir(resolved, {withFileTypes: true});
          return list.map(e => ({name: e.name, isDirectory: e.isDirectory(), isFile: e.isFile()}));
        },
        async readText(filePath, maxBytes = 128 * 1024) {
          const resolved = path.resolve(filePath);
          if (resolved !== homeRoot && !resolved.startsWith(homeRoot + path.sep)) throw new Error("禁止越界读取工作区之外路径");
          const buf = await fs.readFile(resolved);
          return buf.subarray(0, maxBytes).toString("utf8");
        },
      });
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
            protocol: String(spec?.protocol || "acp").trim(),
            builtin: Boolean(spec?.builtin),
            description: String(spec?.description || "").trim(),
          };
          kernel.drivers.set(driverId, entry);
          registerEffect(() => {
            if (kernel.drivers.get(driverId)?.pluginId === pluginId) kernel.drivers.delete(driverId);
          }, `driver:${driverId}`);
        },
        list() {
          return [...kernel.drivers.values()];
        },
      });
    }

    return Object.freeze(ctx);
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
        const modUrl = `${pathToFileURL(entryReal).href}?gen=${this.generationSeq}&t=${Date.now()}`;
        const mod = await withTimeout(import(modUrl), ACTIVATE_TIMEOUT_MS, `导入插件 ${m.id}`);
        activateFn = mod.activate || mod.apply || mod.default?.activate || mod.default;
      }
      if (typeof activateFn === "function") {
        const ctx = this.#buildContext(rec, neg.granted);
        const ret = await withTimeout(Promise.resolve(activateFn(ctx)), ACTIVATE_TIMEOUT_MS, `激活插件 ${m.id}`);
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
    this.observers.clear();
    this.drivers.clear();
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
    return await this.boot();
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
    return await this.boot();
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
    const rec = this.records.get(pluginId);
    if (!rec) throw new Error(`未找到插件：${pluginId}`);
    if (!rec.dir || rec.manifest.kind === "core" || rec.manifest.kind === "builtin") {
      throw new Error("仅可卸载本地或社区安装的运行时插件");
    }
    await this.deactivate(pluginId);
    await fs.rm(rec.dir, {recursive: true, force: true});
    await fs.rm(path.join(this.dataRoot, `${pluginId}.json`), {force: true});
    await fs.rm(path.join(this.configRoot, `${pluginId}.json`), {force: true});
    delete this.state.enabled[pluginId];
    delete this.state.grantedCaps[pluginId];
    await this.saveState();
    return await this.boot();
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
        executionTier: m.executionTier,
        status: rec.status,
        enabled: rec.enabled,
        error: rec.error,
        dirPath: rec.dir || "",
        capabilities: m.capabilities,
        grantedCapabilities: rec.grantedCapabilities,
        degradedOptional: rec.degradedOptional,
        settingsSchema: m.settingsSchema,
        settings: rec.settings,
        commands: cmds,
        panels,
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
      pluginsRoot: this.pluginsRoot,
      drivers: [...this.drivers.values()],
      plugins,
    };
  }
}
