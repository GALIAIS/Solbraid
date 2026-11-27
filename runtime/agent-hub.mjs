import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import {createHash, randomBytes, randomUUID} from "node:crypto";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {createInterface} from "node:readline";
import {resolveSafeHomePath} from "./safe-file-io.mjs";
import {
  AcpDriver,
  AcpStreamStallError,
  normalizeAcpMetaToolName,
  isAcpMetaToolName,
  isAgyModelCatalogError,
  isSocketPermissionOrNetworkBlocked,
  isSoftNetworkReply,
  isRetryableAcpError,
  formatAcpNetworkReason,
  parseAgyInternalRetryLine,
  computeRetryDelayMs,
  filterDuplicateAcpMcpServers,
  buildAcpEmbeddedContextBlocks,
  computeUnifiedDiff,
  buildPerStepUsageRecords,
  captureFileCheckpoint,
  listFileCheckpoints,
  revertFileCheckpoint,
} from "./acp-driver.mjs";
import {CliBridgeDriver} from "./cli-bridge-driver.mjs";
import {createDevToolsHub} from "./dev-tools-hub.mjs";
import {createCliUpdater, resolveCliInstallation, bundledCliInstallation, cliPathDirectories} from "./cli-components.mjs";
import * as extSync from "./extension-sync.mjs";
import * as worktreeMgr from "./worktree-manager.mjs";
import * as envSsh from "./env-ssh-hub.mjs";
import * as usageLedger from "./usage-ledger.mjs";
import {PluginKernel, startPluginControlServer} from "./plugin-kernel.mjs";
import {registerBuiltinHostPlugins} from "./plugin-builtins.mjs";
import {normalizeDownloadNetwork, saveDownloadNetwork, downloadEnvironment, githubDownloadUrl, redactNetworkText} from "./download-network.mjs";

const execFileAsync = promisify(execFile);
const HOME = process.env.HOME || "/home/agent";
const SESSIONS_ROOT = path.join(HOME, ".galiais", "agent-sessions");
const NPM_PREFIX = path.join(HOME, ".galiais", "npm-global");
const NPM_CACHE = path.join(HOME, ".galiais", "npm-cache");
const EXTRA_BINS = [
  path.join(HOME, ".local", "bin"),
  path.join(NPM_PREFIX, "bin"),
  "/opt/galiais-codex/usr/lib/chatgpt/resources",
  "/usr/local/bin",
  "/usr/bin",
  "/bin",
];
// 给 Antigravity 内置 prompt_cache_keep_alive 留足空闲窗口，避免 10 分钟过早休眠强杀导致服务端缓存冷重建
const IDLE_HIBERNATE_MS = 30 * 60 * 1000;
const MAXIMUM_FRAME = 8 * 1024 * 1024 - 1024;
const SESSION_MUTATIONS = new Set(["sessions/prompt", "sessions/revertBefore", "sessions/delete", "sessions/rename", "sessions/setModel", "sessions/setMode", "sessions/setConfigOption", "sessions/setProvider"]);
const NPM_PACKAGE = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*(?:@[a-zA-Z0-9._~^+*-]+)?$/;

if (typeof process.getuid === "function" && process.getuid() === 0) throw new Error("Agent Hub must not run as root");

let initialized = false;
let closing = false;
let gatewayConfig = {port: 0, token: ""};
let networkConfig = normalizeDownloadNetwork();
async function resolveAntigravityWithFallback() {
  const inst = await resolveCliInstallation("antigravity");
  if (inst.bundled) return inst;
  try {
    await execFileAsync(inst.binary, ["--version"], {timeout: 15_000, maxBuffer: 65_536});
    return inst;
  } catch (err) {
    const fallback = bundledCliInstallation("antigravity");
    return {...fallback, warning: `已更新的 Antigravity CLI (${inst.version}) 自检失败，已自动回退至内置版本 (${fallback.version})：${err?.message || err}`};
  }
}

let antigravityInstallation = await resolveAntigravityWithFallback();
let CLI_DIRECTORIES = await cliPathDirectories();

async function hotReloadAntigravityCli() {
  antigravityInstallation = await resolveAntigravityWithFallback();
  CLI_DIRECTORIES = await cliPathDirectories();
  if (manifests.has("antigravity")) {
    const m = manifests.get("antigravity");
    manifests.set("antigravity", {
      ...m,
      install: {...m.install, binaryPath: antigravityInstallation.binary},
      launch: {
        ...m.launch,
        command: antigravityInstallation.binary,
        authLoginCommand: "'" + antigravityInstallation.binary.replace(/'/g, "'\\''") + "' acp --login",
      },
    });
  }
  // 回收所有空闲的 Antigravity 驱动进程，使新版本无需重启整个运行环境即刻生效
  for (const [key, drv] of [...drivers.entries()]) {
    if (drv.agentId !== "antigravity") continue;
    const busy = drv.activeProcs ? drv.activeProcs.size > 0 : (drv.pending && drv.pending.size > 0);
    if (!busy && !busySessions.has(key)) stopSessionDriver(key);
  }
  return await cliUpdater.markApplied("antigravity");
}
const cliUpdater = createCliUpdater({notify, getNetwork: () => networkConfig, baseEnv: () => baseEnv(null, null)});
let userGlobalEnv = {};
let userGlobalEnvError = null;
envSsh.setSshNotifier(notify);
const initialUserEnvReady = envSsh.loadEffectiveUserEnv().then(
  env => { userGlobalEnv = env; },
  error => { userGlobalEnvError = error; },
);
const manifests = new Map();       // agentId -> manifest JSON
const drivers = new Map();         // 会话标识 -> 独立驱动，避免切换服务商或目录中断其他会话
const sessions = new Map();        // `${agentId}:${sessionId}` -> session state
const busySessions = new Set();
const activeTurns = new Map();
const pluginDriverAborts = new Map(); // `${agentId}:${sessionId}` -> AbortController
let packageOperation = null;
const installationFailures = new Map();
const installationStates = new Map();
const authProcs = new Map();       // agentId -> {child, shimDir}
const sessionApiKeys = new Map();  // `${agentId}:${sessionId}` -> ephemeral API key (memory only)
const pendingClientReqs = new Map(); // reqId -> {resolve, reject}
let reverseSeq = 0;

function output(packet) {
  if (closing) return;
  const frame = JSON.stringify(packet) + "\n";
  if (Buffer.byteLength(frame) > MAXIMUM_FRAME || process.stdout.writableLength + Buffer.byteLength(frame) > 16 * 1024 * 1024) {
    throw new Error("Agent Hub transport output limit exceeded");
  }
  process.stdout.write(frame);
}

function notify(method, params) {
  output({method, params});
}

function defaultClientTimeoutMs(method) {
  const m = String(method || "");
  if (m === "toolchains/aptExec" || m === "selfBuild/installApk") return 10 * 60 * 1000;
  if (m.includes("permission") || m.includes("approve") || m.includes("elicitation") || m.includes("dialog") || m.includes("delegate")) {
    return 15 * 60 * 1000;
  }
  return 90 * 1000;
}

function askClient(method, params, timeoutMs = defaultClientTimeoutMs(method)) {
  if (pendingClientReqs.size >= 64) throw new Error("待处理审批过多，请先处理已有请求");
  const id = `hub-${++reverseSeq}`;
  return new Promise((resolve, reject) => {
    const finish = (error, result) => {
      if (!pendingClientReqs.delete(id)) return;
      clearTimeout(timer);
      try { notify("galiais/requestResolved", {id}); } catch (transportError) { error ??= transportError; }
      if (error) reject(error); else resolve(result);
    };
    const timer = setTimeout(() => finish(new Error(`宿主请求等待超时 (${method})`)), timeoutMs);
    pendingClientReqs.set(id, {resolve: result => finish(null, result), reject: error => finish(error), params});
    try { output({id, method, params}); } catch (error) { finish(error); }
  });
}

function expandHome(raw) {
  if (typeof raw !== "string") return "";
  return raw.replaceAll("$HOME", HOME).replace(/^~(?=\/|$)/, HOME);
}

let networkConfigTail = Promise.resolve();

function updateNetworkConfig(raw) {
  const pending = networkConfigTail.then(async () => {
    const next = await saveDownloadNetwork(raw);
    networkConfig = next;
    return next;
  });
  networkConfigTail = pending.catch(() => {});
  return pending;
}

async function prepareInstallCurlHome(config) {
  if (!config.proxyUrl && config.proxyMode !== "none" && !config.preferIpv4) return null;
  await fs.mkdir(path.join(HOME, ".galiais"), {recursive: true});
  const curlHome = await fs.mkdtemp(path.join(HOME, ".galiais", "install-curl-"));
  try {
    const lines = [];
    const originalConfig = path.join(process.env.CURL_HOME || HOME, ".curlrc");
    try {
      if ((await fs.stat(originalConfig)).isFile()) lines.push(`config = ${JSON.stringify(originalConfig)}`);
    } catch (error) { if (error.code !== "ENOENT") throw error; }
    if (config.preferIpv4) lines.push("ipv4");
    if (config.proxyUrl || config.proxyMode === "none") lines.push(`proxy = ${JSON.stringify(config.proxyUrl)}`);
    await fs.writeFile(path.join(curlHome, ".curlrc"), lines.join("\n") + "\n", {mode: 0o600});
    return curlHome;
  } catch (error) {
    await fs.rm(curlHome, {recursive: true, force: true});
    throw error;
  }
}

function baseEnv(manifest, providerInfo, config = networkConfig, userEnv = userGlobalEnv, extraApiKey = null, sessionId = null) {
  const safeUserEnv = (userEnv && typeof userEnv === "object") ? userEnv : {};
  const userPathParts = typeof safeUserEnv.PATH === "string" ? safeUserEnv.PATH.split(":") : [];
  const pathList = [...userPathParts, ...CLI_DIRECTORIES, ...EXTRA_BINS, ...(process.env.PATH || "").split(":")].filter(Boolean);
  const env = {
    ...process.env,
    ...safeUserEnv,
    ...downloadEnvironment(config, {...process.env, ...safeUserEnv}),
    HOME,
    PATH: [...new Set(pathList)].join(":"),
    NPM_CONFIG_PREFIX: NPM_PREFIX,
    NO_COLOR: "1",
    TERM: "dumb",
  };
  if (manifest?.launch?.env && typeof manifest.launch.env === "object") {
    for (const [k, v] of Object.entries(manifest.launch.env)) {
      if (typeof k === "string" && typeof v === "string") env[k] = expandHome(v);
    }
  }
  // 将 GALIAIS 本地多协议回环网关注入到支持环境变量的第三方 CLI（API Key 不落盘）
  const bridge = manifest?.providerBridge;
  if (providerInfo && providerInfo.id && gatewayConfig.port > 0 && gatewayConfig.token && bridge?.envMapping) {
    // 自定义网关服务商隔离宿主机云认证/OAuth 变量，防止抢占本地回环网关令牌
    delete env.GOOGLE_APPLICATION_CREDENTIALS;
    delete env.GOOGLE_CLOUD_PROJECT;
    delete env.GOOGLE_CLOUD_LOCATION;
    delete env.GOOGLE_GENAI_USE_VERTEXAI;
    delete env.CLAUDE_CODE_OAUTH_TOKEN;
    delete env.ANTHROPIC_AUTH_TOKEN;
    const route = bridge.gatewayRoute || "openai-chat";
    const sidSeg = sessionId ? `/sid/${encodeURIComponent(String(sessionId).slice(0, 80))}` : "";
    const baseUrl = route === "anthropic"
      ? `http://127.0.0.1:${gatewayConfig.port}/anthropic/${providerInfo.id}${sidSeg}`
      : route === "gemini"
        ? `http://127.0.0.1:${gatewayConfig.port}/gemini/${providerInfo.id}${sidSeg}`
        : `http://127.0.0.1:${gatewayConfig.port}/openai-chat/${providerInfo.id}${sidSeg}`;
    const map = bridge.envMapping;
    if (map.baseUrl) env[map.baseUrl] = baseUrl;
    if (map.apiKey) env[map.apiKey] = gatewayConfig.token;
    if (map.model && providerInfo.model) env[map.model] = providerInfo.model;
    if (manifest?.id === "antigravity") {
      const geminiUrl = `http://127.0.0.1:${gatewayConfig.port}/gemini/${providerInfo.id}${sidSeg}`;
      env.AGY_LLM_GATEWAY_URL = baseUrl;
      env.AGY_LLM_GATEWAY_API_KEY = gatewayConfig.token;
      if (providerInfo.model) env.AGY_LLM_GATEWAY_MODELS = providerInfo.model;
      env.GOOGLE_GEMINI_BASE_URL = geminiUrl;
      env.GEMINI_API_BASE_URL = geminiUrl;
      // 经逆向 agy 1.5.16 实证：同时设置 GOOGLE_API_KEY 与 GEMINI_API_KEY 会触发冲突警告并优先取 GOOGLE_API_KEY
      env.GEMINI_API_KEY = gatewayConfig.token;
      delete env.GOOGLE_API_KEY;
    }
  } else if (manifest?.id === "antigravity" && typeof extraApiKey === "string" && extraApiKey.trim()) {
    env.GEMINI_API_KEY = extraApiKey.trim();
    delete env.GOOGLE_API_KEY;
  }
  return env;
}

async function resolveBinary(manifest) {
  const candidates = [];
  if (manifest?.install?.binaryPath) candidates.push(expandHome(manifest.install.binaryPath));
  if (manifest?.launch?.command) candidates.push(expandHome(manifest.launch.command));
  const env = baseEnv(manifest, null);
  for (const cand of candidates) {
    if (!cand) continue;
    if (path.isAbsolute(cand)) {
      try {
        const st = await fs.stat(cand);
        if (st.isFile() && (st.mode & 0o111)) return cand;
      } catch (error) { if (!["ENOENT", "ENOTDIR"].includes(error.code)) throw error; }
    } else {
      for (const dir of env.PATH.split(":")) {
        if (!dir) continue;
        const full = path.join(dir, cand);
        try {
          const st = await fs.stat(full);
          if (st.isFile() && (st.mode & 0o111)) return full;
        } catch (error) { if (!["ENOENT", "ENOTDIR"].includes(error.code)) throw error; }
      }
    }
  }
  return null;
}

async function probeAgent(manifest) {
  const id = manifest.id;
  const bin = await resolveBinary(manifest);
  const running = [...drivers.values()].some(drv => drv.agentId === id && !drv.closed && (drv.child || drv.activeProcs?.size > 0));
  let activeSessions = 0;
  for (const s of sessions.values()) if (s.agentId === id) activeSessions++;
  if (!bin) {
    return {id, installed: false, version: null, binaryPath: null, running, activeSessions, error:null, ...installationStatus(id)};
  }
  let version = null;
  let error = null;
  const verArgs = Array.isArray(manifest?.install?.versionArgs) ? manifest.install.versionArgs.map(String) : ["--version"];
  try {
    const {stdout, stderr} = await execFileAsync(bin, verArgs, {timeout: 15000, maxBuffer:65536, env: baseEnv(manifest, null)});
    const raw = (stdout || stderr || "").trim().split(/\r?\n/)[0] || "";
    if (!raw) throw new Error("版本命令没有返回输出");
    version = cleanInstallOutput(raw).slice(0, 80);
  } catch (failure) {
    error = "启动检查失败：" + cleanInstallOutput(failure.stderr || failure.message).trim().slice(-800);
  }
  return {id, installed: true, version, binaryPath: bin, running, activeSessions, error, ...installationStatus(id)};
}

function installationStatus(id) {
  const active = packageOperation?.agentId === id;
  const installation = active && packageOperation.method === "agents/uninstall" ? null : installationStates.get(id) || null;
  return {installing:active && !["completed", "failed", "cancelled"].includes(installation?.phase),
    installError:installationFailures.get(id) || null, installation};
}

function updateInstallProgress(id, phase, step) {
  const operationId = packageOperation?.operationId;
  if (!operationId || packageOperation.agentId !== id) throw new Error("安装任务已结束");
  const installation = {operationId, phase, step};
  installationStates.set(id, installation);
  notify("agents/installProgress", {agentId:id, installation, error:installationFailures.get(id) || null});
}

const SAFE_AGENT_ID_RE = /^[a-z0-9][a-z0-9._-]{0,47}$/;

function validateAgentId(agentId) {
  if (typeof agentId !== "string" || !SAFE_AGENT_ID_RE.test(agentId) || agentId.includes("..")) {
    throw new Error("Agent 标识无效");
  }
  return agentId;
}

function sessionFile(agentId, sessionId) {
  validateAgentId(agentId);
  if (typeof sessionId !== "string" || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(sessionId)) {
    throw new Error("会话标识无效");
  }
  const resolved = path.resolve(SESSIONS_ROOT, agentId, `${sessionId}.json`);
  const rootPrefix = path.resolve(SESSIONS_ROOT) + path.sep;
  if (!resolved.startsWith(rootPrefix)) throw new Error("会话路径越界");
  return resolved;
}

const MAX_CACHED_SESSIONS = 12;

function touchSessionCache(key, record) {
  if (sessions.has(key)) sessions.delete(key);
  sessions.set(key, record);
  if (sessions.size <= MAX_CACHED_SESSIONS) return;
  for (const candidateKey of [...sessions.keys()]) {
    if (sessions.size <= MAX_CACHED_SESSIONS) break;
    if (candidateKey === key || activeTurns.has(candidateKey) || drivers.has(candidateKey)) continue;
    sessions.delete(candidateKey);
  }
}

function compactRecordDiffsInPlace(record) {
  if (!record || !Array.isArray(record.messages)) return;
  for (const msg of record.messages) {
    if (msg?.role !== "tool" || !Array.isArray(msg.diffs) || !msg.diffs.length) continue;
    msg.diffs = msg.diffs.map(d => {
      if (!d || typeof d !== "object") return d;
      if (typeof d.diff === "string" && d.diff.length > 0 && d.oldText === undefined && d.newText === undefined) return d;
      const diffText = typeof d.diff === "string" && d.diff.length > 0
        ? d.diff.slice(0, 48_000)
        : computeUnifiedDiff(d.path || "file", d.oldText || "", d.newText || "");
      return {
        path: String(d.path || ""),
        kind: String(d.kind || "update"),
        diff: diffText,
      };
    });
  }
}

async function pruneAgyLogs(logDir, keepLatest = 20) {
  try {
    const names = await fs.readdir(logDir);
    const candidates = names.filter(n => n.endsWith(".log") && /^(?:solbraid-acp-|acp-server-|acp-login-|cli-)/.test(n));
    if (candidates.length <= keepLatest) return;
    const activeFiles = new Set([...drivers.values()].map(d => d?._agyLogFile).filter(Boolean));
    const stats = await Promise.all(candidates.map(async name => {
      const full = path.join(logDir, name);
      const st = await fs.stat(full).catch(() => null);
      return st ? {full, mtimeMs: st.mtimeMs} : null;
    }));
    const sorted = stats.filter(Boolean).sort((a, b) => b.mtimeMs - a.mtimeMs);
    for (const item of sorted.slice(keepLatest)) {
      if (activeFiles.has(item.full)) continue;
      await fs.rm(item.full, {force: true}).catch(() => {});
    }
  } catch {}
}

const deletedSessions = new Set();

function markSessionDeleted(key) {
  deletedSessions.add(key);
  if (deletedSessions.size > 256) {
    const oldest = deletedSessions.values().next().value;
    if (oldest !== undefined) deletedSessions.delete(oldest);
  }
}

async function saveSession(record) {
  const key = `${record.agentId}:${record.id}`;
  if (deletedSessions.has(key)) return;
  compactRecordDiffsInPlace(record);
  const dir = path.join(SESSIONS_ROOT, record.agentId);
  await fs.mkdir(dir, {recursive: true});
  const target = sessionFile(record.agentId, record.id);
  const tmp = target + "." + randomUUID() + ".part";
  try {
    await fs.writeFile(tmp, JSON.stringify(record), {encoding: "utf8", mode: 0o600});
    if (deletedSessions.has(key)) return;
    await fs.rename(tmp, target);
  } finally { await fs.rm(tmp, {force: true}); }
  if (!deletedSessions.has(key)) touchSessionCache(key, record);
}

async function listStoredSessions(agentId) {
  const dir = path.join(SESSIONS_ROOT, agentId);
  let names;
  try { names = await fs.readdir(dir); } catch { return []; }
  const result = [];
  for (const name of names) {
    if (!name.endsWith(".json")) continue;
    try {
      const data = JSON.parse(await fs.readFile(path.join(dir, name), "utf8"));
      if (data && data.id) result.push(data);
    } catch {}
  }
  return result.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

async function loadStoredSession(agentId, sessionId) {
  const file = sessionFile(agentId, sessionId);
  const key = `${agentId}:${sessionId}`;
  if (sessions.has(key)) {
    const cached = sessions.get(key);
    touchSessionCache(key, cached);
    return cached;
  }
  try {
    const data = JSON.parse(await fs.readFile(file, "utf8"));
    if (data?.agentId !== agentId || data?.id !== sessionId || !Array.isArray(data.messages)) throw new Error("会话记录无效，未覆盖原文件");
    touchSessionCache(key, data);
    return data;
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

function cleanInstallOutput(value) {
  return redactNetworkText(value);
}

class InstallCancelledError extends Error {
  constructor() { super("已取消安装"); }
}

function checkInstallActive(operation) {
  if (!operation || operation !== packageOperation || closing || operation.cancelled) throw new InstallCancelledError();
}

function signalInstaller(child, signal) {
  if (!child?.pid) return;
  try {
    if (process.platform === "win32") child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) { if (error.code !== "ESRCH") throw error; }
}

async function runInstallCommand(manifest, command, args, extraEnv = {}, config = networkConfig) {
  const operation = packageOperation;
  checkInstallActive(operation);
  const remaining = operation.deadline - Date.now();
  if (remaining <= 0) throw new Error("安装超时，请检查网络后重试");
  const child = spawn(command, args, {cwd:HOME, env:{...baseEnv(manifest, null, config), ...extraEnv},
    detached:process.platform !== "win32", stdio:["ignore", "pipe", "pipe"]});
  operation.child = child;
  let stderr = "", stdout = "", timedOut = false, spawnError = null, forcedStop;
  const readers = [child.stdout, child.stderr].map((stream, index) => {
    const reader = createInterface({input:stream, crlfDelay:Infinity});
    reader.on("line", raw => {
      const line = cleanInstallOutput(raw).slice(0, 500);
      if (index === 1) stderr = (stderr + line + "\n").slice(-3000);
      else stdout = (stdout + line + "\n").slice(-3000);
      notify("agents/installLog", {agentId:manifest.id, line, done:false});
    });
    return reader;
  });
  const timeout = setTimeout(() => {
    timedOut = true;
    signalInstaller(child, "SIGTERM");
    forcedStop = setTimeout(() => signalInstaller(child, "SIGKILL"), 4000);
  }, remaining);
  try {
    const exit = await new Promise(resolve => {
      child.once("error", error => { spawnError = error; });
      // 安装器已退出时清理其后台子进程，防止它们占住管道使 close 永不返回。
      child.once("exit", () => signalInstaller(child, "SIGKILL"));
      child.once("close", (code, signal) => resolve({code, signal}));
    });
    checkInstallActive(operation);
    if (timedOut) throw new Error("安装超时，安装进程已停止。建议在「运行环境」配置镜像或代理后重试");
    if (spawnError) throw new Error(`无法启动安装命令 ${command}：${spawnError.message}`, {cause:spawnError});
    if (exit.code !== 0) throw new Error(`安装命令失败（${exit.signal || "退出码 " + exit.code}）：${(stderr || stdout).trim() || "没有错误输出"}`);
  } finally {
    clearTimeout(timeout); clearTimeout(forcedStop);
    readers.forEach(reader => reader.close());
    if (operation.child === child) operation.child = null;
  }
}

async function runInstall(manifest) {
  const id = manifest.id;
  const operation = packageOperation;
  const config = networkConfig;
  installationFailures.delete(id);
  let status = null;
  let curlHome = null;
  try {
    checkInstallActive(operation);
    updateInstallProgress(id, "preparing", 1);
    const spec = manifest.install || {};
    const strategy = spec.strategy || "custom";
    const required = spec.requiredCommands ?? [];
    if (!Array.isArray(required) || required.length > 16 || required.some(name => typeof name !== "string" || !/^[a-zA-Z0-9._+-]{1,80}$/.test(name))) throw new Error("安装依赖清单无效");
    for (const name of [...new Set([...required, ...(strategy === "npm" ? ["node", "npm"] : ["bash"])])]) {
      const binary = await resolveBinary({launch:{...manifest.launch, command:name}, install:{}});
      checkInstallActive(operation);
      if (!binary) throw new Error(`运行环境缺少安装依赖：${name}。请更新应用并重新启动运行环境`);
    }
    await fs.mkdir(path.join(HOME, ".local", "bin"), {recursive:true});
    await fs.mkdir(NPM_PREFIX, {recursive:true});
    await fs.mkdir(NPM_CACHE, {recursive:true});
    curlHome = await prepareInstallCurlHome(config);
    const extraEnv = {NPM_CONFIG_CACHE: NPM_CACHE, ...(curlHome ? {CURL_HOME: curlHome} : {})};
    let command, args, preview;
    if (strategy === "npm") {
      const pkg = String(spec.package || "").trim();
      if (!NPM_PACKAGE.test(pkg)) throw new Error("无效的 npm 包名或版本");
      command = "npm";
      args = [
        "install",
        "--prefix", NPM_PREFIX,
        "--global",
        "--no-audit",
        "--no-fund",
        "--prefer-offline",
        "--loglevel=http",
        "--fetch-retries=5",
        "--fetch-retry-mintimeout=3000",
        "--fetch-retry-maxtimeout=20000",
      ];
      if (config.npmRegistry) args.push("--registry", config.npmRegistry);
      args.push("--", pkg);
      preview = `npm install --prefix "${NPM_PREFIX}" -g ${config.npmRegistry ? `--registry=${config.npmRegistry} ` : ""}${pkg}`;
    } else if (strategy === "script" || strategy === "custom") {
      preview = String(spec.command || "").trim();
      if (!preview) throw new Error("缺少安装命令");
      command = "/bin/bash";
      args = ["--noprofile", "--norc", "-e", "-o", "pipefail", "-c", preview];
    } else throw new Error(`不支持的安装方式：${strategy}`);
    const netSummary = [
      `npm=${config.npmRegistry || "沿用原配置"}`,
      config.proxyMode === "none" ? "代理=禁用" : config.proxyMode === "manual" ? "代理=手动" : "代理=跟随环境",
      config.preferIpv4 ? "IPv4 兼容模式" : "自动选择 IP 协议",
    ].filter(Boolean).join(" · ");
    notify("agents/installLog", {agentId:id, line:cleanInstallOutput(`[下载网络] ${netSummary}`), done:false});
    notify("agents/installLog", {agentId:id, line:"$ " + cleanInstallOutput(preview), done:false});
    updateInstallProgress(id, "installing", 2);
    await runInstallCommand(manifest, command, args, extraEnv, config);
    updateInstallProgress(id, "verifying", 3);
    status = await probeAgent(manifest);
    checkInstallActive(operation);
    if (!status.installed) throw new Error("安装程序已退出，但未找到 Agent 可执行文件，请检查安装日志和可执行文件路径");
    if (status.error) throw new Error(status.error);
    updateInstallProgress(id, "completed", 3);
    status.installing = false;
    status.installation = installationStates.get(id);
    notify("agents/installLog", {agentId:id, line:`安装完成：${status.version}`, done:true, ok:true, status});
    return status;
  } catch (error) {
    if (error instanceof InstallCancelledError) {
      updateInstallProgress(id, "cancelled", installationStates.get(id)?.step ?? 1);
      if (status) { status.installing = false; status.installError = null; status.installation = installationStates.get(id); }
      notify("agents/installLog", {agentId:id, line:"已取消安装", done:true, ok:false, cancelled:true, status});
      return {id, ...status, installing:false, cancelled:true, installation:installationStates.get(id)};
    }
    const message = cleanInstallOutput(error.message).slice(-3500);
    installationFailures.set(id, message);
    updateInstallProgress(id, "failed", installationStates.get(id)?.step ?? 1);
    if (status) { status.installing = false; status.installError = message; status.installation = installationStates.get(id); }
    notify("agents/installLog", {agentId:id, line:message, done:true, ok:false, status});
    throw new Error(message, {cause:error});
  } finally {
    if (curlHome) await fs.rm(curlHome, {recursive:true, force:true});
  }
}

async function probeEndpointWithCurl(url, config, headOnly = false) {
  const started = Date.now();
  const curlBin = await resolveBinary({launch: {command: "curl"}, install: {}});
  if (!curlBin) return {ok: false, ms: 0, error: "缺少 curl"};
  try {
    const args = ["-fsSL", ...(headOnly ? ["-I"] : []), ...(config.preferIpv4 ? ["--ipv4"] : []),
      ...(config.proxyUrl || config.proxyMode === "none" ? ["--proxy", config.proxyUrl] : []),
      "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "12", "--", url];
    const {stdout} = await execFileAsync(
      curlBin,
      args,
      {timeout: 15_000, env: baseEnv(null, null, config)},
    );
    const code = Number(String(stdout || "").trim()) || 0;
    return {ok: code >= 200 && code < 400, ms: Date.now() - started, statusCode: code};
  } catch (err) {
    return {ok: false, ms: Date.now() - started, error: cleanInstallOutput(err.stderr || err.message || "连接失败").slice(0, 160)};
  }
}

async function runUninstall(manifest) {
  const id = manifest.id;
  stopDriver(id);
  const spec = manifest.install || {};
  if (spec.strategy === "npm") {
    if (!NPM_PACKAGE.test(spec.package || "")) throw new Error("无效的 npm 包名");
    await runInstallCommand(manifest, "npm", ["uninstall", "--prefix", NPM_PREFIX, "--global", "--", spec.package]);
  }
  const allowedPrefixes = [
    path.join(HOME, ".local", "bin"),
    path.join(HOME, ".galiais"),
    path.join(HOME, ".gemini"),
    path.join(HOME, ".config"),
    path.join(HOME, ".opencode"),
    path.join(HOME, ".cache"),
  ];
  const targets = Array.isArray(spec.uninstallPaths) ? spec.uninstallPaths : [];
  for (const raw of targets) {
    const full = path.resolve(expandHome(String(raw)));
    const safe = allowedPrefixes.some(p => full.startsWith(p + "/"));
    const shared = ["npm-global", "agent-sessions", "mux", "vm"].map(name => path.join(HOME, ".galiais", name));
    if (!safe || shared.includes(full)) throw new Error("拒绝卸载共享目录或工作区：" + full);
    const entry = await fs.lstat(full).catch(error => { if (error.code !== "ENOENT") throw error; return null; });
    if (!entry) continue;
    const parent = await fs.realpath(path.dirname(full));
    if (!allowedPrefixes.some(prefix => parent === prefix || parent.startsWith(prefix + "/"))) {
      throw new Error("卸载目录的父路径越界，未删除：" + full);
    }
    await fs.rm(full, {recursive: true, force: true});
  }
  const status = await probeAgent(manifest);
  if (status.installed) throw new Error("卸载后仍能找到 Agent，请检查清单中的卸载路径");
  installationFailures.delete(id);
  installationStates.delete(id);
  return {...status, installing:false, installError:null, installation:null};
}

function stopDriver(agentId) {
  for (const [key, drv] of drivers) {
    if (drv.agentId === agentId) stopSessionDriver(key);
  }
}

function stopSessionDriver(key) {
  const drv = drivers.get(key);
  if (!drv) return;
  drivers.delete(key);
  drv.close();
  for (const waiter of pendingClientReqs.values()) {
    if (`${waiter.params.agentId}:${waiter.params.sessionId}` === key) waiter.reject(new Error("会话已停止"));
  }
  for (const [token, context] of hubDelegateTokenMap) {
    if (`${context.agentId}:${context.sessionId}` === key) hubDelegateTokenMap.delete(token);
  }
}

const ANSI_RE = /\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\x07\x1B]*(?:\x07|\x1B\\))/g;
function stripAnsi(text) { return String(text || "").replace(ANSI_RE, "").replace(/\r/g, ""); }

async function resolveAuthCommand(agentId, manifest, mode = "oauth") {
  if (agentId === "claude") {
    const inst = await resolveCliInstallation("claude");
    const bin = "'" + inst.binary.replace(/'/g, "'\\''") + "'";
    return mode === "setup-token" ? `${bin} setup-token` : `${bin} auth login`;
  }
  if (agentId === "antigravity") {
    const inst = await resolveCliInstallation("antigravity");
    const bin = "'" + inst.binary.replace(/'/g, "'\\''") + "'";
    return `${bin} acp --login`;
  }
  if (agentId === "codex") {
    const inst = await resolveCliInstallation("codex");
    const bin = "'" + inst.binary.replace(/'/g, "'\\''") + "'";
    return mode === "device" ? `${bin} login --device-auth` : `${bin} login`;
  }
  const cmd = String(manifest?.launch?.authLoginCommand || "").trim();
  if (!cmd) throw new Error(`${manifest?.name || agentId} 未配置交互登录命令`);
  return cmd;
}

async function startAuth(manifest, mode = "oauth") {
  const id = manifest.id;
  stopAuth(id);
  const cmd = await resolveAuthCommand(id, manifest, mode);
  // 创建临时 $BROWSER / xdg-open 拦截脚本，确保无头环境下可靠捕获 OAuth 授权链接。
  const shimDir = await fs.mkdtemp("/tmp/solbraid-auth-");
  const shimPath = path.join(shimDir, "xdg-open");
  const shimScript = "#!/bin/sh\nprintf '\\nSOLBRAID_AUTH_URL:%s\\n' \"$1\"\nexit 0\n";
  await fs.writeFile(shimPath, shimScript, {mode: 0o700});

  const env = {
    ...baseEnv(manifest, null),
    TERM: "xterm-256color",
    BROWSER: shimPath,
    XDG_OPEN: shimPath,
    PATH: `${shimDir}:${baseEnv(manifest, null).PATH || ""}`,
  };
  delete env.NO_COLOR;

  // 通过真实 PTY（script）启动交互式 OAuth 流程并开放 stdin，支持粘贴 OOB 授权码与按键确认。
  const child = spawn("/usr/bin/script", ["-q", "-e", "-f", "-c", cmd, "/dev/null"], {
    cwd: HOME,
    env,
    detached: process.platform !== "win32",
    stdio: ["pipe", "pipe", "pipe"],
  });
  authProcs.set(id, {child, shimDir});

  const urlRe = /https:\/\/[^\s"'<>)\]]+/g;
  const codeRe = /\b([A-Z0-9]{4}-[A-Z0-9]{4,5})\b/;
  const inputPromptRe = /(paste\s+the\s+authorization\s+code|enter\s+(?:the\s+)?(?:code|token)|authorization\s+code|callback\s+url|press\s+enter|y\/n)/i;

  let lineBuf = "";
  const emitLine = (rawLine) => {
    const clean = stripAnsi(rawLine).trim();
    if (!clean) return;
    let url = null;
    if (clean.startsWith("SOLBRAID_AUTH_URL:")) {
      url = clean.slice("SOLBRAID_AUTH_URL:".length).trim();
      notify("agents/authEvent", {agentId: id, line: `已捕获授权链接：${url}`, url, deviceCode: null, needsInput: false, done: false});
      return;
    }
    const urls = clean.match(urlRe) || [];
    if (urls.length) url = urls[0].replace(/[.,;]+$/, "");
    const codeMatch = codeRe.exec(clean);
    const deviceCode = codeMatch ? codeMatch[1] : null;
    const needsInput = inputPromptRe.test(clean);
    notify("agents/authEvent", {
      agentId: id,
      line: clean.slice(0, 600),
      url,
      deviceCode,
      needsInput,
      done: false,
    });
  };

  const handleChunk = chunk => {
    lineBuf += chunk.toString("utf8");
    const parts = lineBuf.split(/\r?\n/);
    lineBuf = parts.pop() || "";
    for (const p of parts) emitLine(p);
    // 无换行的交互提示（如 "Paste the authorization code below: "）立即通知前端弹出输入框。
    const pendingClean = stripAnsi(lineBuf).trim();
    if (pendingClean && (inputPromptRe.test(pendingClean) || pendingClean.startsWith("SOLBRAID_AUTH_URL:"))) {
      emitLine(pendingClean);
      lineBuf = "";
    }
  };
  child.stdout.on("data", handleChunk);
  child.stderr.on("data", handleChunk);

  const cleanupShim = () => fs.rm(shimDir, {recursive: true, force: true}).catch(() => {});
  child.once("error", error => {
    if (authProcs.get(id)?.child === child) authProcs.delete(id);
    void cleanupShim();
    notify("agents/authEvent", {agentId: id, line: `无法启动登录：${error.message}`, url: null, done: true, ok: false});
  });
  child.once("exit", code => {
    if (lineBuf.trim()) emitLine(lineBuf);
    if (authProcs.get(id)?.child !== child) { void cleanupShim(); return; }
    authProcs.delete(id);
    void cleanupShim();
    notify("agents/authEvent", {agentId: id, line: code === 0 ? "登录授权已完成" : `登录进程已结束（退出码 ${code}）`, url: null, done: true, ok: code === 0});
  });
  return {started: true};
}

function writeAuthInput(agentId, text) {
  const entry = authProcs.get(agentId);
  if (!entry?.child?.stdin?.writable) throw new Error("当前没有进行中的交互登录进程");
  const clean = String(text ?? "").replace(/\r?\n$/, "");
  entry.child.stdin.write(clean + "\n");
  return {ok: true};
}

function stopAuth(agentId) {
  const entry = authProcs.get(agentId);
  if (entry) {
    authProcs.delete(agentId);
    const child = entry.child;
    try {
      if (process.platform !== "win32" && child?.pid) process.kill(-child.pid, "SIGTERM");
      else child?.kill("SIGTERM");
    } catch {}
    if (entry.shimDir) fs.rm(entry.shimDir, {recursive: true, force: true}).catch(() => {});
  }
}

async function queryAuthStatus(agentId) {
  const env = baseEnv(manifests.get(agentId) || null, null);
  if (agentId === "claude") {
    const inst = await resolveCliInstallation("claude");
    let loggedIn = false, authMethod = "none", email = null, subscriptionType = null, orgName = null, raw = "";
    try {
      const {stdout, stderr} = await execFileAsync(inst.binary, ["auth", "status"], {timeout: 12_000, env});
      raw = stripAnsi((stdout || "") + "\n" + (stderr || "")).trim();
      try {
        const j = JSON.parse(stdout.trim());
        loggedIn = Boolean(j.loggedIn ?? j.authenticated);
        authMethod = j.authMethod || (loggedIn ? "oauth" : "none");
        email = j.email || j.account?.email || null;
        subscriptionType = j.subscriptionType || j.plan || null;
        orgName = j.orgName || j.organizationName || null;
      } catch {
        loggedIn = /logged\s*in|authenticated|claude\.ai/i.test(raw) && !/not\s+logged\s+in/i.test(raw);
        const mailMatch = raw.match(/[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/);
        if (mailMatch) email = mailMatch[0];
        if (/oauth|claude\.ai|subscription|pro|max|team|enterprise/i.test(raw)) authMethod = "claude.ai";
        else if (/api\s*key/i.test(raw)) authMethod = "apiKey";
      }
    } catch (err) {
      raw = stripAnsi(err.stdout || err.stderr || err.message || "").trim();
      // 某些版本的 claude auth status 在未登录时返回非 0 退出码
      try {
        const j = JSON.parse(String(err.stdout || "").trim());
        loggedIn = Boolean(j.loggedIn);
        authMethod = j.authMethod || "none";
        email = j.email || null;
        subscriptionType = j.subscriptionType || null;
      } catch {}
    }
    if (!loggedIn) {
      const credPath = path.join(HOME, ".claude", ".credentials.json");
      const cred = await fs.readFile(credPath, "utf8").then(JSON.parse).catch(() => null);
      if (cred?.claudeAiOauth?.accessToken) {
        loggedIn = true;
        authMethod = "claude.ai";
        subscriptionType = subscriptionType || cred.claudeAiOauth.subscriptionType || null;
      }
    }
    return {agentId, loggedIn, authMethod, email, subscriptionType, orgName, raw: raw.slice(0, 600)};
  }
  if (agentId === "antigravity") {
    const agySettingsPath = path.join(HOME, ".gemini", "antigravity-cli", "settings.json");
    const agySettings = await fs.readFile(agySettingsPath, "utf8").then(JSON.parse).catch(() => ({}));
    const oauthFiles = [
      path.join(HOME, ".gemini", "antigravity-cli", "antigravity-oauth-token"),
      path.join(HOME, ".gemini", "antigravity", "oauth_creds.json"),
      path.join(HOME, ".gemini", "oauth_creds.json"),
      path.join(HOME, ".config", "antigravity", "auth.json"),
      path.join(HOME, ".gemini", "antigravity-cli", "auth.json"),
    ];
    let hasOauthFile = false;
    let email = agySettings?.email || agySettings?.account?.email || null;
    for (const f of oauthFiles) {
      const st = await fs.stat(f).catch(() => null);
      if (!st || !st.isFile() || st.size === 0) continue;
      hasOauthFile = true;
      const raw = await fs.readFile(f, "utf8").catch(() => "");
      try {
        const data = JSON.parse(raw);
        email = email || data?.email || data?.account_email || data?.user?.email || null;
      } catch {
        const mail = raw.match(/[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/);
        if (mail) email = email || mail[0];
      }
      break;
    }
    // 也扫描 ~/.gemini/antigravity/ 与 ~/.gemini/antigravity-cli/ 下任意非空 oauth/token/cred/auth 文件（含无后缀文件）
    if (!hasOauthFile) {
      for (const dir of [path.join(HOME, ".gemini", "antigravity-cli"), path.join(HOME, ".gemini", "antigravity")]) {
        const names = await fs.readdir(dir).catch(() => []);
        for (const n of names) {
          if (!/oauth|token|cred|auth/i.test(n)) continue;
          const st = await fs.stat(path.join(dir, n)).catch(() => null);
          if (st?.isFile() && st.size > 0) {
            hasOauthFile = true;
            break;
          }
        }
        if (hasOauthFile) break;
      }
    }
    const hasApiKey = Boolean(env.GEMINI_API_KEY || env.GOOGLE_API_KEY);
    const hasGateway = Boolean(env.AGY_LLM_GATEWAY_URL);
    const authMethod = hasGateway ? "llm-gateway" : hasOauthFile ? "oauth-personal" : hasApiKey ? "gemini-api-key" : (agySettings?.selectedAuthType || "none");
    const loggedIn = hasOauthFile || hasApiKey || hasGateway;
    return {
      agentId,
      loggedIn,
      authMethod,
      email,
      hasOauthFile,
      hasApiKey,
      hasGateway,
      selectedAuthType: agySettings?.selectedAuthType || null,
    };
  }
  return {agentId, loggedIn: false, authMethod: "unknown"};
}

async function logoutAuth(agentId) {
  stopAuth(agentId);
  const env = baseEnv(manifests.get(agentId) || null, null);
  if (agentId === "claude") {
    const inst = await resolveCliInstallation("claude");
    try { await execFileAsync(inst.binary, ["auth", "logout"], {timeout: 15_000, env}); } catch {}
    await fs.rm(path.join(HOME, ".claude", ".credentials.json"), {force: true}).catch(() => {});
    return {ok: true};
  }
  if (agentId === "antigravity") {
    stopDriver("antigravity");
    const targets = [
      path.join(HOME, ".gemini", "antigravity-cli", "antigravity-oauth-token"),
      path.join(HOME, ".gemini", "antigravity", "oauth_creds.json"),
      path.join(HOME, ".gemini", "oauth_creds.json"),
      path.join(HOME, ".gemini", "antigravity-cli", "auth.json"),
      path.join(HOME, ".config", "antigravity", "auth.json"),
    ];
    for (const f of targets) await fs.rm(f, {force: true}).catch(() => {});
    const settingsPath = path.join(HOME, ".gemini", "antigravity-cli", "settings.json");
    const cur = await fs.readFile(settingsPath, "utf8").then(JSON.parse).catch(() => null);
    if (cur && typeof cur === "object") {
      delete cur.selectedAuthType;
      await fs.writeFile(settingsPath, JSON.stringify(cur, null, 2) + "\n", {mode: 0o600}).catch(() => {});
    }
    return {ok: true};
  }
  return {ok: true};
}

async function getDriver(manifest, cwd, providerInfo, sessionId, extraApiKey = null, launchOverrides = null) {
  const id = manifest.id;
  const key = `${id}:${sessionId}`;
  const existing = drivers.get(key);
  const userEnv = await envSsh.loadEffectiveUserEnv(cwd, process.env.PATH || "").catch(() => ({}));
  const effectiveKey = extraApiKey ?? sessionApiKeys.get(key) ?? null;
  const effectiveKeyHash = effectiveKey ? createHash("sha256").update(String(effectiveKey)).digest("hex").slice(0, 16) : "";
  const launchOpts = launchOverrides || {};
  const envKey = JSON.stringify([providerInfo || null, gatewayConfig, manifest.protocol, manifest.launch, userEnv, effectiveKeyHash, launchOpts]);
  if (existing && !existing.closed && (existing._envKey === envKey || existing.pending?.size > 0 || existing.activeProcs?.size > 0)) {
    existing.lastActivity = Date.now();
    return existing;
  }
  stopSessionDriver(key);
  if (drivers.size >= 8) throw new Error("同时运行的外部 Agent 会话已达 8 个，请先停止空闲 Agent");
  const env = baseEnv(manifest, providerInfo, networkConfig, userEnv, effectiveKey, sessionId);
  let effectiveManifest = manifest;
  let agyLogFile = null;
  const useAgyStreamJson = id === "antigravity" && (launchOpts.transport === "stream-json" || Boolean(providerInfo?.id));
  if (id === "antigravity" && !useAgyStreamJson) {
    const baseArgs = Array.isArray(manifest?.launch?.args) ? [...manifest.launch.args] : ["acp"];
    const logDir = path.join(HOME, ".gemini", "antigravity-cli", "log");
    await fs.mkdir(logDir, {recursive: true}).catch(() => {});
    void pruneAgyLogs(logDir, 20);
    const safeSid = String(sessionId || "sess").replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 16);
    agyLogFile = path.join(logDir, `solbraid-acp-${safeSid}-${Date.now()}.log`);
    if (!baseArgs.includes("--disable-telemetry")) baseArgs.push("--disable-telemetry");
    if (!baseArgs.includes("--gemini-dir")) baseArgs.push("--gemini-dir", path.join(HOME, ".gemini"));
    if (!baseArgs.includes("--log-file")) baseArgs.push("--log-file", agyLogFile);
    if (launchOpts.bypassPermissions === true) baseArgs.push("--dangerously-skip-permissions");
    if (launchOpts.sandbox === true) baseArgs.push("--sandbox");
    if (typeof launchOpts.agentName === "string" && launchOpts.agentName.trim()) {
      baseArgs.push("--agent", launchOpts.agentName.trim());
    }
    effectiveManifest = {...manifest, launch: {...manifest.launch, args: baseArgs}};
  }
  const proto = useAgyStreamJson ? "agy-stream-json" : (effectiveManifest.protocol || "exec-stream");
  const onEvent = evt => {
    // 恢复历史会话期间抑制 ACP 历史重放，防止前端重复拼接整段聊天记录
    if (replayingSessions.has(key)) return;
    const turn = activeTurns.get(key);
    const upd = evt.update;
    if (turn && upd) {
      if (upd.sessionUpdate === "agent_thought_chunk") {
        const chunk = typeof upd.content?.text === "string" ? upd.content.text : (typeof upd.text === "string" ? upd.text : "");
        if (chunk) {
          const now = Date.now();
          turn.serverAccepted = true;
          if (!turn.firstTokenAt) turn.firstTokenAt = now;
          turn.lastTokenAt = now;
          if (!turn.stepFirstTokenAt) turn.stepFirstTokenAt = now;
          turn.stepLastTokenAt = now;
          turn.stepThoughtChars = (turn.stepThoughtChars || 0) + chunk.length;
          turn.stepOutChars = (turn.stepOutChars || 0) + chunk.length;
          if (!turn.currentThought) {
            turn.currentThought = {text: "", startedAtMs: now, completedAtMs: null};
            turn.thoughts.push(turn.currentThought);
            turn.timelineOrder.push({type: "thought", ref: turn.currentThought});
          }
          turn.currentThought.text += chunk;
        }
      } else if (upd.sessionUpdate === "agent_message_chunk") {
        const now = Date.now();
        turn.serverAccepted = true;
        if (turn.currentThought && !turn.currentThought.completedAtMs) {
          turn.currentThought.completedAtMs = now;
          turn.currentThought = null;
        }
        if (typeof upd.content?.text === "string" && upd.content.text.length > 0) {
          if (!turn.firstTokenAt) turn.firstTokenAt = now;
          turn.lastTokenAt = now;
          if (!turn.stepFirstTokenAt) turn.stepFirstTokenAt = now;
          turn.stepLastTokenAt = now;
          turn.stepOutChars = (turn.stepOutChars || 0) + upd.content.text.length;
          turn.text += upd.content.text;
        }
      } else if (upd.sessionUpdate === "tool_call" || upd.sessionUpdate === "tool_call_update") {
        const now = Date.now();
        turn.serverAccepted = true;
        const isStart = upd.sessionUpdate === "tool_call";
        const tid = String(upd.toolCallId || `anon-${turn.toolMap.size}`);
        if (!turn.metaToolIds) turn.metaToolIds = new Set();
        const isMetaTool = turn.metaToolIds.has(tid) || isAcpMetaToolName(upd.title);
        if (isMetaTool) {
          turn.metaToolIds.add(tid);
          recordTurnToolUpdate(turn, tid, upd, now);
          if (turn.focusText && normalizeAcpMetaToolName(upd.title) === "update_focus") {
            notify("agent/event", {agentId: id, sessionId, kind: "acp-update", update: {sessionUpdate: "focus_update", focus: turn.focusText}});
          }
        } else {
          if (isStart && turn.currentThought && !turn.currentThought.completedAtMs) {
            turn.currentThought.completedAtMs = now;
            turn.currentThought = null;
          }
          const st = String(upd.status || (isStart ? "in_progress" : "")).toLowerCase();
          if (isStart) {
            if (turn.activeTools.size === 0 && !turn.toolActiveSince) turn.toolActiveSince = now;
            turn.activeTools.add(tid);
          } else if (st === "completed" || st === "failed") {
            if (upd.toolCallId) turn.activeTools.delete(String(upd.toolCallId));
            else turn.activeTools.clear();
            if (turn.activeTools.size === 0 && turn.toolActiveSince > 0) {
              const deltaToolMs = Math.max(0, Date.now() - turn.toolActiveSince);
              turn.toolElapsedMs += deltaToolMs;
              turn.stepToolElapsedMs = (turn.stepToolElapsedMs || 0) + deltaToolMs;
              turn.toolActiveSince = 0;
              // 工具执行完毕后下一轮 LLM API 请求即将发出，更新步骤起始时间基准
              if (turn.awaitingNextStepStart) {
                turn.stepStartedAt = Date.now();
                turn.stepToolElapsedMs = 0;
                turn.awaitingNextStepStart = false;
              }
            }
          }
          // 记录工具调用轨迹、实时步骤与焦点状态，确保重开会话后完整还原时间线
          recordTurnToolUpdate(turn, tid, upd, now);
          if (!isStart && (st === "completed" || st === "failed") && typeof turn.scanBrainArtifacts === "function") {
            void turn.scanBrainArtifacts();
          }
        }
      } else if (upd.sessionUpdate === "plan") {
        turn.serverAccepted = true;
        if (Array.isArray(upd.entries) && (upd.entries.length > 0 || upd.clear === true)) {
          turn.planSteps = upd.entries.map(e => ({
            step: String(e?.content || e?.title || e?.step || "").trim(),
            status: normalizeHubStepStatus(e?.status),
            id: e?.id ? String(e.id) : undefined,
            activeForm: e?.activeForm ? String(e.activeForm) : undefined,
            priority: e?.priority ? String(e.priority) : undefined,
          })).filter(e => e.step);
          if (upd.explanation || upd.title) turn.planExplanation = String(upd.explanation || upd.title);
        }
        const planMd = typeof upd.markdown === "string" ? upd.markdown : (typeof upd.text === "string" ? upd.text : (typeof upd.plan === "string" ? upd.plan : ""));
        if (planMd.trim()) {
          upsertTurnPlan(turn, {
            id: String(upd.id || `plan-${Date.now()}`),
            title: String(upd.title || extractHubPlanTitle(planMd, upd.path)),
            text: planMd,
            filePath: upd.path ? String(upd.path) : undefined,
            status: upd.requestFeedback ? "running" : "completed",
            requestFeedback: Boolean(upd.requestFeedback),
            summary: upd.summary ? String(upd.summary) : undefined,
            artifactType: upd.artifactType ? String(upd.artifactType) : "plan",
          });
        }
      } else if (upd.sessionUpdate === "usage_update" && typeof upd.used === "number") {
        const now = Date.now();
        if (turn.usageStart === null) turn.usageStart = upd.used;
        turn.usageEnd = upd.used;
        if (typeof upd.size === "number" && upd.size > 0) turn.contextWindow = upd.size;
        // agy acp 每次模型 API 请求完成后都会下发一条 used > 0 的 usage_update，记录单步耗时/TTFT/上下文供逐请求入账
        if (upd.used > 0) {
          const sStart = turn.stepStartedAt || now;
          const wallMs = Math.max(1, now - sStart);
          const sToolMs = Math.min(wallMs, turn.stepToolElapsedMs || 0);
          const apiMs = Math.max(1, wallMs - sToolMs);
          const sFirst = turn.stepFirstTokenAt || 0;
          const sLast = turn.stepLastTokenAt || 0;
          const sTtft = sFirst > sStart ? Math.min(apiMs, Math.max(1, sFirst - sStart)) : 0;
          const sGenRaw = sFirst > 0 && sLast > sFirst ? (sLast - sFirst) : 0;
          const sGen = Math.max(0, Math.min(apiMs - sTtft, sGenRaw - Math.min(sGenRaw, sToolMs)));
          if (!Array.isArray(turn.llmSteps)) turn.llmSteps = [];
          turn.llmSteps.push({
            endedAt: now,
            used: upd.used,
            contextWindow: typeof upd.size === "number" && upd.size > 0 ? upd.size : (turn.contextWindow || 0),
            apiMs,
            ttftMs: sTtft,
            genMs: sGen,
            streamed: sFirst > 0,
            outChars: turn.stepOutChars || 0,
            thoughtChars: turn.stepThoughtChars || 0,
          });
          turn.stepStartedAt = now;
          turn.stepFirstTokenAt = 0;
          turn.stepLastTokenAt = 0;
          turn.stepToolElapsedMs = 0;
          turn.stepOutChars = 0;
          turn.stepThoughtChars = 0;
          turn.awaitingNextStepStart = true;
        }
      }
    }
    // 将 ACP diff 块中庞大的整文件 oldText/newText 预计算为紧凑 unified diff 后再转发给前端，避免 Socket 传输与主线程卡顿；
    // 同时在首次观测到某文件 diff 时捕获写前检查点，使直接修改磁盘的 Agent 同样支持聊天页单文件一键撤回。
    let forwardEvt = evt;
    if (upd && (upd.sessionUpdate === "tool_call" || upd.sessionUpdate === "tool_call_update") && Array.isArray(upd.content) && upd.content.some(b => String(b?.type || "").toLowerCase() === "diff" && (b.oldText !== undefined || b.newText !== undefined) && !b.diff)) {
      const turn = activeTurns.get(key);
      if (turn && !turn.capturedCheckpoints) turn.capturedCheckpoints = new Set();
      const baseCwd = cwd || path.join(HOME, "workspace");
      const compactContent = upd.content.map(b => {
        if (String(b?.type || "").toLowerCase() !== "diff" || !b.path || b.diff) return b;
        const oldStr = String(b.oldText || "");
        const newStr = String(b.newText || "");
        const resolvedFile = path.resolve(baseCwd, String(b.path));
        const cpKey = `${upd.toolCallId || "diff"}:${resolvedFile}`;
        if ((resolvedFile === HOME || resolvedFile.startsWith(HOME + "/")) && (!turn || !turn.capturedCheckpoints.has(cpKey))) {
          if (turn) turn.capturedCheckpoints.add(cpKey);
          const existedBefore = b.oldText !== null && b.oldText !== undefined && !(oldStr === "" && newStr.length > 0 && String(b.kind || "").toLowerCase() === "add");
          captureFileCheckpoint(resolvedFile, {
            sessionId,
            originalContent: existedBefore ? oldStr : null,
          }).catch(() => {});
        }
        return {
          type: "diff",
          path: String(b.path),
          kind: !oldStr && newStr ? "add" : (oldStr && !newStr ? "delete" : "update"),
          diff: computeUnifiedDiff(b.path, oldStr, newStr),
        };
      });
      forwardEvt = {...evt, update: {...upd, content: compactContent}};
    }
    notify("agent/event", {...forwardEvt, agentId: id, sessionId});
  };
  let drv;
  if (proto === "agy-stream-json") {
    drv = new AgyStreamJsonDriver({
      agentId: id,
      binary: antigravityInstallation.binary,
      env,
      cwd: cwd || path.join(HOME, "workspace"),
      launchOpts,
      providerInfo,
      onEvent,
    });
  } else if (proto === "acp") {
    drv = new AcpDriver({
      agentId: id,
      manifest: effectiveManifest,
      env,
      cwd: cwd || path.join(HOME, "workspace"),
      sessionId,
      onEvent,
      onPermissionRequest: async (agentId, params) => {
        try {
          return await askClient("agent/requestPermission", {...params, agentId, sessionId});
        } catch {
          return {outcome: {outcome: "cancelled"}};
        }
      },
      onUserInputRequest: async (agentId, params) => {
        try {
          const decision = await askClient("agent/requestUserInput", {...params, agentId, sessionId});
          const turn = activeTurns.get(key);
          if (turn && Array.isArray(turn.timelineOrder)) {
            const entries = [];
            const answers = decision?.answers && typeof decision.answers === "object" ? decision.answers : {};
            const qs = Array.isArray(params?.questions) ? params.questions : [];
            for (const [qi, q] of qs.entries()) {
              const qid = String(q?.id || q?.question || `q_${qi}`);
              const label = String(q?.header || q?.question || q?.title || qid).slice(0, 60);
              const val = answers[qid];
              if (val != null) {
                entries.push({label, value: Array.isArray(val) ? val.join("、") : String(val)});
              }
            }
            if (!entries.length) {
              for (const [k, v] of Object.entries(answers)) {
                entries.push({label: k, value: Array.isArray(v) ? v.join("、") : String(v)});
              }
            }
            turn.timelineOrder.push({
              type: "receipt",
              ref: {
                id: `receipt-q-${Date.now()}`,
                kind: "question",
                title: decision?.cancelled ? "已跳过提问" : `已回答 ${entries.length || qs.length || 1} 项问题`,
                entries,
                status: decision?.cancelled ? "declined" : "completed",
                ts: Date.now(),
              },
            });
          }
          return decision;
        } catch {
          return {cancelled: true, answers: {}};
        }
      },
      onElicitationRequest: async (agentId, params) => {
        try {
          const decision = await askClient("agent/requestElicitation", {...params, agentId, sessionId});
          const turn = activeTurns.get(key);
          if (turn && Array.isArray(turn.timelineOrder)) {
            const action = String(decision?.action || "decline");
            const content = decision?.content && typeof decision.content === "object" ? decision.content : {};
            const schemaProps = params?.requestedSchema?.properties || params?.requested_schema?.properties || params?.schema?.properties || {};
            const entries = Object.entries(content).map(([k, v]) => ({
              label: String(schemaProps[k]?.title || k).slice(0, 60),
              value: Array.isArray(v) ? v.join("、") : String(v),
            }));
            turn.timelineOrder.push({
              type: "receipt",
              ref: {
                id: `receipt-e-${Date.now()}`,
                kind: "elicitation",
                title: action === "accept"
                  ? (params?.serverName ? `已提交 ${params.serverName} 表单` : "已提交补充信息")
                  : "已拒绝补充信息",
                entries,
                status: action === "accept" ? "completed" : "declined",
                ts: Date.now(),
              },
            });
          }
          return decision;
        } catch {
          return {action: "decline"};
        }
      },
      onExit: () => {
        if (drivers.get(key) === drv) stopSessionDriver(key);
      },
    });
  } else {
    drv = new CliBridgeDriver({agentId: id, manifest: effectiveManifest, env, onEvent});
  }
  drv._envKey = envKey;
  drv._sessionId = sessionId;
  drv._agyLogFile = agyLogFile;
  drivers.set(key, drv);
  return drv;
}

// 只休眠可恢复历史的空闲 ACP 进程，避免自动休眠使不支持恢复的会话失效。
setInterval(() => {
  const now = Date.now();
  for (const [key, drv] of drivers.entries()) {
    const busy = drv.activeProcs ? drv.activeProcs.size > 0 : (drv.pending && drv.pending.size > 0);
    const resumable = !drv.agentCapabilities || drv.agentCapabilities.loadSession === true;
    if (!busy && resumable && !busySessions.has(key) && now - (drv.lastActivity || 0) > IDLE_HIBERNATE_MS) {
      stopSessionDriver(key);
      notify("agents/hibernated", {agentId: drv.agentId, sessionId: drv._sessionId});
    }
  }
}, 60_000).unref();

function resolveManifestOrNull(agentId) {
  validateAgentId(agentId);
  const m = manifests.get(agentId);
  if (m) return m;
  const plugDrv = pluginKernel?.drivers?.get(agentId);
  if (plugDrv && plugDrv.executable) {
    return {
      id: plugDrv.id,
      name: plugDrv.name,
      protocol: "plugin-driver",
      driverId: plugDrv.id,
      pluginId: plugDrv.pluginId,
      preinstalled: true,
      description: plugDrv.description || "",
    };
  }
  return null;
}

function requireManifest(agentId, actionLabel = "") {
  const m = resolveManifestOrNull(agentId);
  if (m) return m;
  const suffix = actionLabel ? `，无法${actionLabel}` : "";
  throw new Error(`Agent 或插件驱动「${agentId}」已卸载、停用或处于安全模式${suffix}（历史会话仍可浏览、导出或删除）`);
}

const devTools = createDevToolsHub({
  home: HOME, baseEnv, notify, prepareSshPtyLaunch: envSsh.prepareSshPtyLaunch,
  preparePtyEnv: async cwd => baseEnv(null, null, networkConfig, await envSsh.loadEffectiveUserEnv(cwd, process.env.PATH || "")),
});

const pluginKernel = new PluginKernel({home: HOME, onNotify: notify});
let pluginControlServer = null;
registerBuiltinHostPlugins(pluginKernel, {
  agentRuntime: {
    listManifests: () => [...manifests.keys()],
    activeSessionCount: () => sessions.size,
  },
  selfEvolution: {
    getControlInfo: () => pluginControlServer ? {socketPath: pluginControlServer.socketPath} : null,
    listSnapshots: id => pluginKernel.listSourceSnapshots(id),
    getSelfBuildConfig: () => pluginKernel.getSelfBuildConfig(),
  },
  usageLedger,
  extSync,
  devTools,
  envSsh,
  worktreeMgr,
});

const DELEGATE_MCP_SCRIPT = "/opt/galiais-codex/android/delegate-mcp.mjs";
const hubDelegateTokenMap = new Map(); // token -> {agentId, sessionId, cwd}
let hubDelegateSockPath = null;
let hubDelegateServer = null;
let hubDelegateOpening = null;

function ensureHubDelegateServer() {
  if (hubDelegateServer && hubDelegateSockPath) return Promise.resolve(hubDelegateSockPath);
  if (!hubDelegateOpening) hubDelegateOpening = startHubDelegateServer().finally(() => { hubDelegateOpening = null; });
  return hubDelegateOpening;
}

async function startHubDelegateServer() {
  hubDelegateSockPath = `/tmp/galiais-hub-del-${process.pid}.sock`;
  await fs.rm(hubDelegateSockPath, {force: true}).catch(() => {});
  const srv = net.createServer(conn => {
    let buf = "";
    conn.setEncoding("utf8");
    conn.on("error", () => {});
    conn.on("data", chunk => {
      buf += chunk;
      if (buf.length > 65536) { conn.destroy(); return; }
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      let req;
      try { req = JSON.parse(buf.slice(0, nl)); } catch { conn.destroy(); return; }
      buf = "";
      const ctx = hubDelegateTokenMap.get(req.token);
      const reply = v => { if (!conn.destroyed) conn.end(JSON.stringify(v) + "\n"); };
      if (!ctx || typeof req.task !== "string" || !req.task.trim()) {
        reply({ok: false, text: "Invalid delegation request"});
        return;
      }
      Promise.resolve().then(() => askClient("agent/delegate", {
        agentId: ctx.agentId,
        sessionId: ctx.sessionId,
        targetAgent: req.targetAgent || "codex",
        task: req.task,
        cwd: ctx.cwd,
      })).then(res => reply(res), err => reply({ok: false, text: err.message}));
    });
  });
  await new Promise((resolve, reject) => { srv.once("error", reject); srv.listen(hubDelegateSockPath, resolve); });
  try { await fs.chmod(hubDelegateSockPath, 0o600); }
  catch (error) { srv.close(); throw error; }
  hubDelegateServer = srv;
  return hubDelegateSockPath;
}

async function mcpServersForSession(agentId, sessionId, cwd, allowDelegation) {
  const list = [];
  try {
    let managed = await extSync.loadMcpServersForAcp(agentId, cwd);
    // Antigravity 已自动读取 ~/.gemini/config/mcp_config.json，去除全局重复项避免每次会话重复预热同名 stdio MCP 进程
    if (agentId === "antigravity" && Array.isArray(managed) && managed.length) {
      const agyMcpPath = path.join(HOME, ".gemini", "config", "mcp_config.json");
      const rawCfg = await fs.readFile(agyMcpPath, "utf8").then(JSON.parse).catch(() => null);
      const globalNames = rawCfg?.mcpServers && typeof rawCfg.mcpServers === "object" ? Object.keys(rawCfg.mcpServers) : [];
      managed = filterDuplicateAcpMcpServers(managed, globalNames);
    }
    if (Array.isArray(managed) && managed.length) {
      managed = [...managed].sort((a, b) => String(a?.name || "").localeCompare(String(b?.name || "")));
      list.push(...managed);
    }
  } catch {}
  if (!allowDelegation) return list;
  for (const [token, context] of hubDelegateTokenMap) {
    if (context.agentId === agentId && context.sessionId === sessionId) hubDelegateTokenMap.delete(token);
  }
  const sock = await ensureHubDelegateServer();
  const tok = randomBytes(24).toString("hex");
  hubDelegateTokenMap.set(tok, {agentId, sessionId, cwd});
  list.push({
    name: "galiais",
    command: process.execPath,
    args: [DELEGATE_MCP_SCRIPT],
    env: [
      {name: "GALIAIS_DELEGATE_SOCKET", value: sock},
      {name: "GALIAIS_DELEGATE_TOKEN", value: tok},
    ],
  });
  return list;
}

const replayingSessions = new Set();

async function attachAcpSession(driver, record, options = {}) {
  await driver.start();
  if (driver._attachedSessionId === record.remoteSessionId && record.remoteSessionId) {
    if (record.agentId !== "antigravity" || driver._modelCatalogLoaded !== false) return;
  }
  const servers = await mcpServersForSession(record.agentId, record.id, record.cwd, record.allowDelegation === true);
  const extraDirs = Array.isArray(record.additionalDirectories) ? record.additionalDirectories : [];
  let meta = null;
  let createdFresh = false;
  if (record.remoteSessionId) {
    const canResume = Boolean(driver.agentCapabilities?.sessionCapabilities?.resume || driver.agentCapabilities?.loadSession);
    if (!canResume) {
      const created = await driver.newSession(record.cwd, servers, extraDirs);
      if (!created?.sessionId) throw new Error("Agent 未返回有效会话标识");
      record.remoteSessionId = created.sessionId;
      if (Array.isArray(record.messages) && record.messages.length > 0) record.pendingContextBridge = true;
      meta = created;
      createdFresh = true;
    } else {
      const sessKey = `${record.agentId}:${record.id}`;
      replayingSessions.add(sessKey);
      try {
        meta = await driver.resumeSession(record.remoteSessionId, record.cwd, servers, extraDirs);
      } catch (resumeErr) {
        const msg = String(resumeErr?.message || resumeErr || "");
        // 远端 ACP 会话已过期、未落盘或版本变更导致无法恢复时（含 agy 1.5.16 标准的 -32002），平滑重建并桥接历史上下文而不卡死；
        // 注意显式排除 "unknown model key ...: model not found" 这类断网冷启模型表缺失错误，避免误毁有效会话 ID。
        if (!isAgyModelCatalogError(msg) && (resumeErr?.code === -32002 || /not found|no such|invalid session|unknown session|enoent|does not exist/i.test(msg))) {
          const created = await driver.newSession(record.cwd, servers, extraDirs);
          if (!created?.sessionId) throw new Error("Agent 未返回有效会话标识");
          record.remoteSessionId = created.sessionId;
          if (Array.isArray(record.messages) && record.messages.length > 0) record.pendingContextBridge = true;
          meta = created;
          createdFresh = true;
        } else {
          throw resumeErr;
        }
      } finally {
        replayingSessions.delete(sessKey);
      }
    }
  } else {
    const created = await driver.newSession(record.cwd, servers, extraDirs);
    if (!created?.sessionId) throw new Error("Agent 未返回有效会话标识");
    record.remoteSessionId = created.sessionId;
    meta = created;
    createdFresh = true;
  }
  const loadedModelCount = Array.isArray(meta?.models?.availableModels) ? meta.models.availableModels.length : 0;
  const loadedModelCfgCount = Array.isArray(meta?.configOptions)
    ? (meta.configOptions.find(opt => opt?.id === "model")?.options?.length || 0)
    : 0;
  const hasModelCatalog = loadedModelCount > 0 || loadedModelCfgCount > 0;
  driver._modelCatalogLoaded = hasModelCatalog;
  // Antigravity 在锁屏/后台断网期间冷启动时，session/resume 虽能从本地 SQLite 成功返回，
  // 但后端 loadCodeAssist/fetchAvailableModels 因 EPERM 失败导致内存模型表为空，继续发 prompt 必然触发
  // "failed to construct executor: unknown model key MODEL_PLACEHOLDER_...: model not found"。
  // 此处直接拦截并交由上层等待网络恢复后干净重建。
  if (record.agentId === "antigravity" && options.requireModelCatalog !== false && !hasModelCatalog) {
    const err = new Error("Antigravity 模型目录初始化失败（后台网络受限，模型配置未加载）");
    err.code = "ACP_MODEL_CATALOG_UNAVAILABLE";
    err.retryable = true;
    err.needsNetworkWait = true;
    throw err;
  }
  if (loadedModelCount > 0) {
    if (record.agentId === "antigravity") {
      cachedAgyRawModels = meta.models.availableModels;
      record.availableModels = groupAgyModelsList(meta.models.availableModels);
    } else {
      record.availableModels = meta.models.availableModels;
    }
  }
  if (Array.isArray(meta?.modes?.availableModes) && meta.modes.availableModes.length > 0) record.availableModes = meta.modes.availableModes;
  if (Array.isArray(meta?.configOptions) && meta.configOptions.length > 0) record.configOptions = meta.configOptions;
  // 恢复既有远端会话时仅在远端值与本地记录不一致（或离线修改过设置）时才下发 setModel/setMode/setConfigOption，
  // 避免无谓重放设置变更破坏 Antigravity 服务端 Prompt Cache 与注入多余的 USER_SETTINGS_CHANGE。
  const remoteModel = meta?.models?.currentModelId || null;
  const remoteMode = meta?.modes?.currentModeId || null;
  const remoteThought = Array.isArray(meta?.configOptions)
    ? (meta.configOptions.find(opt => opt?.id === "thought_level")?.currentValue || null)
    : null;
  let activeRemoteModel = remoteModel;
  let modelSwitchedToTarget = false;
  if (record.model) {
    const concrete = record.agentId === "antigravity"
      ? resolveAgyConcreteModel(record.model, record.effort, null, record.availableModels)
      : record.model;
    const targetModel = concrete || record.model;
    const needModelSync = remoteModel !== null
      ? remoteModel !== targetModel
      : (createdFresh || record._pendingModelSync === true);
    if (needModelSync) {
      const setOk = await driver.setModel(record.remoteSessionId, targetModel).then(() => true, () => false);
      if (setOk) {
        activeRemoteModel = targetModel;
        modelSwitchedToTarget = true;
        if (record.agentId === "antigravity") {
          const targetSplit = splitAgyModelEffort(targetModel);
          record.configOptions = updateConfigOptionValues(record.configOptions, {
            model: targetSplit.baseId || record.model,
            ...(targetSplit.effort ? {thought_level: targetSplit.effort} : {}),
          });
        } else {
          record.configOptions = updateConfigOptionValues(record.configOptions, {model: targetModel});
        }
      } else if (typeof driver.setConfigOption === "function") {
        const fallbackVal = record.agentId === "antigravity"
          ? (splitAgyModelEffort(targetModel).baseId || record.model)
          : targetModel;
        const cfgRes = await driver.setConfigOption(record.remoteSessionId, "model", fallbackVal).catch(() => null);
        if (Array.isArray(cfgRes?.configOptions)) record.configOptions = cfgRes.configOptions;
        else if (cfgRes) record.configOptions = updateConfigOptionValues(record.configOptions, {model: fallbackVal});
      }
    }
  }
  if (record.mode && record.mode !== "bypass") {
    const needModeSync = remoteMode !== null
      ? remoteMode !== record.mode
      : (createdFresh || record._pendingModeSync === true);
    if (needModeSync) {
      const modeOk = await driver.setMode(record.remoteSessionId, record.mode).then(() => true, () => false);
      if (modeOk) {
        record.configOptions = updateConfigOptionValues(record.configOptions, {mode: record.mode});
      }
    }
  }
  if (record.effort) {
    // Antigravity 的 concrete model（如 gemini-4-neon-xhigh）已内嵌推理档位，setModel 成功切换后无需再重复调用 setConfigOption(thought_level)
    const effortCoveredByModel = modelSwitchedToTarget
      && agyConcreteModelCoversEffort(record.agentId, activeRemoteModel, record.effort);
    const currentThought = Array.isArray(record.configOptions)
      ? (record.configOptions.find(opt => opt?.id === "thought_level")?.currentValue || null)
      : remoteThought;
    const needEffortSync = !effortCoveredByModel && (
      currentThought !== null
        ? currentThought !== record.effort
        : (createdFresh || record._pendingEffortSync === true)
    );
    if (needEffortSync) {
      const cfgRes = await driver.setConfigOption(record.remoteSessionId, "thought_level", record.effort).catch(() => null);
      if (Array.isArray(cfgRes?.configOptions)) {
        record.configOptions = cfgRes.configOptions;
      } else if (cfgRes) {
        record.configOptions = updateConfigOptionValues(record.configOptions, {thought_level: record.effort});
      }
    }
  }
  delete record._pendingModelSync;
  delete record._pendingModeSync;
  delete record._pendingEffortSync;
  driver._attachedSessionId = record.remoteSessionId;
}

const AGY_EFFORT_SUFFIX = /-(low|medium|high|xhigh|max)$/i;
const AGY_EFFORT_ORDER = ["low", "medium", "high", "xhigh", "max"];
let cachedAgyRawModels = [];

function updateConfigOptionValues(configOptions, patch) {
  if (!Array.isArray(configOptions) || !patch || typeof patch !== "object") return configOptions || [];
  return configOptions.map(opt => {
    if (!opt || !Object.hasOwn(patch, opt.id)) return opt;
    const nextVal = patch[opt.id];
    return nextVal ? {...opt, currentValue: nextVal} : opt;
  });
}

function agyConcreteModelCoversEffort(agentId, concreteModel, desiredEffort) {
  if (agentId !== "antigravity" || !concreteModel || !desiredEffort) return false;
  const {effort} = splitAgyModelEffort(concreteModel);
  return Boolean(effort) && effort === String(desiredEffort).trim().toLowerCase();
}

function splitAgyModelEffort(rawId) {
  const clean = String(rawId || "").trim();
  if (!clean) return {baseId: "", effort: null};
  const m = AGY_EFFORT_SUFFIX.exec(clean);
  if (!m) return {baseId: clean, effort: null};
  return {baseId: clean.slice(0, -m[0].length), effort: m[1].toLowerCase()};
}

function groupAgyModelsList(rawList) {
  const grouped = new Map();
  for (const item of rawList || []) {
    const id = String(item?.modelId || item?.id || "").trim();
    if (!id) continue;
    const rawName = String(item?.name || id).trim();
    const {baseId, effort} = splitAgyModelEffort(id);
    const cleanName = effort ? rawName.replace(/\s*\((?:low|medium|high|xhigh|extra|max)\)\s*$/i, "").trim() : rawName;
    if (!grouped.has(baseId)) {
      grouped.set(baseId, {
        id: baseId,
        modelId: baseId,
        name: cleanName || baseId,
        efforts: [],
        variants: [],
        variantMap: {},
      });
    }
    const entry = grouped.get(baseId);
    if (effort) {
      if (!entry.efforts.includes(effort)) entry.efforts.push(effort);
      entry.variantMap[effort] = id;
    } else {
      entry.variantMap.default = id;
    }
    if (!entry.variants.includes(id)) entry.variants.push(id);
  }
  return [...grouped.values()].map(entry => ({
    ...entry,
    efforts: entry.efforts.sort((a, b) => AGY_EFFORT_ORDER.indexOf(a) - AGY_EFFORT_ORDER.indexOf(b)),
  }));
}

function resolveAgyConcreteModel(modelId, effort, knownModels = null, fallbackGroupedModels = null) {
  const clean = String(modelId || "").trim();
  if (!clean) return null;
  const source = Array.isArray(knownModels) && knownModels.length ? knownModels : cachedAgyRawModels;
  const rawIds = new Set(source.map(m => String(m?.modelId || m?.id || "").trim()).filter(Boolean));
  // 进程重启后内存 cachedAgyRawModels 为空时，回退读取持久化在会话中的 grouped availableModels 变体表
  if (rawIds.size === 0 && Array.isArray(fallbackGroupedModels)) {
    for (const g of fallbackGroupedModels) {
      if (Array.isArray(g?.variants)) {
        for (const v of g.variants) {
          const s = String(v || "").trim();
          if (s) rawIds.add(s);
        }
      }
      if (g?.variantMap && typeof g.variantMap === "object") {
        for (const v of Object.values(g.variantMap)) {
          const s = String(v || "").trim();
          if (s) rawIds.add(s);
        }
      }
    }
  }
  const {baseId, effort: embeddedEffort} = splitAgyModelEffort(clean);
  const desiredEffort = String(effort || embeddedEffort || "").trim().toLowerCase();
  if (desiredEffort) {
    const candidate = `${baseId}-${desiredEffort}`;
    if (rawIds.has(candidate)) return candidate;
  }
  if (rawIds.has(clean)) return clean;
  // 若传入的是分组基名（如 gemini-4-neon），优先挑选默认 high -> medium -> 任意可用变体
  for (const pref of ["high", "medium", "low", "xhigh", "max"]) {
    const c = `${baseId}-${pref}`;
    if (rawIds.has(c)) return c;
  }
  return clean;
}

/** 为每个自定义服务商会话生成独立隔离 settings 文件，消除多并发会话竞争同一文件，且不污染用户 settings.json。 */
async function writeAgyCustomSettings(providerInfo, model, sessionId = "default") {
  if (!providerInfo?.id) return null;
  const dir = path.join(HOME, ".gemini", "antigravity-cli", "solbraid-sessions");
  await fs.mkdir(dir, {recursive: true, mode: 0o700});
  const safeSid = String(sessionId || "default").replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 64) || "default";
  const settingsPath = path.join(dir, `settings-${safeSid}.json`);
  const tmpPath = `${settingsPath}.${randomBytes(4).toString("hex")}.tmp`;
  const modelName = String(model || providerInfo.model || "gemini-2.5-pro").trim();
  const payload = {
    modelProvider: "gemini",
    model: modelName,
    customModelsConfig: {
      customModels: [{apiProvider: "gemini", modelName}],
    },
  };
  await fs.writeFile(tmpPath, JSON.stringify(payload, null, 2) + "\n", {mode: 0o600});
  await fs.rename(tmpPath, settingsPath);
  return settingsPath;
}

function inferModelContextWindow(model, isCustomProvider = false) {
  const m = String(model || "").trim().toLowerCase();
  if (m.includes("[1m]") || m.includes("1m-context")) return 1_000_000;
  if (m.startsWith("gemini") || m.includes("gemini-")) return 1_048_576;
  if (m.startsWith("gpt-4.1")) return 1_047_576;
  if (m.startsWith("gpt-6") || m.startsWith("gpt-5") || m.includes("codex")) return 272_000;
  if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return 200_000;
  if (m.startsWith("claude") || m.includes("claude-")) return 200_000;
  if (m.includes("qwen3") || m.includes("qwen-3")) return 262_144;
  if (m.includes("kimi") || m.includes("moonshot") || m.includes("grok-4") || m.includes("grok-3") || m.includes("doubao")) return 256_000;
  if (m.includes("glm-5") || m.includes("minimax")) return 200_000;
  if (m.includes("deepseek") || m.includes("glm-4") || m.includes("gpt-4") || m.includes("gpt-oss") || m.includes("qwen")) return 128_000;
  return isCustomProvider ? 128_000 : 1_048_576;
}

/** 将各驱动返回的 input/cache/output 归一化为统一总输入口径（totalIn = uncached + cacheRead + cacheWrite）。 */
function resolveNormalizedTotalInput(rawIn, outTok, cacheR, cacheW, rawTotal, exclusiveByDefault = false, reasoningTok = 0) {
  const inNum = Math.max(0, Number(rawIn || 0));
  const cSum = Math.max(0, Number(cacheR || 0)) + Math.max(0, Number(cacheW || 0));
  const outNum = Math.max(0, Number(outTok || 0));
  const reasonNum = Math.max(0, Number(reasoningTok || 0));
  const totNum = Math.max(0, Number(rawTotal || 0));
  if (cSum <= 0) return inNum;
  if (inNum > 0 && inNum < cSum) return inNum + cSum;
  if (totNum > 0) {
    const diffExcl = Math.min(
      Math.abs(totNum - (inNum + cSum + outNum)),
      Math.abs(totNum - (inNum + cSum + outNum + reasonNum)),
    );
    const diffIncl = Math.min(
      Math.abs(totNum - (inNum + outNum)),
      Math.abs(totNum - (inNum + outNum + reasonNum)),
    );
    if (diffExcl < diffIncl) return inNum + cSum;
    if (diffIncl < diffExcl) return inNum;
  }
  return exclusiveByDefault ? (inNum + cSum) : inNum;
}

function stepContextTokensOf(u, exclusiveByDefault = false) {
  if (!u || typeof u !== "object") return 0;
  const rawIn = Number(u.input_tokens ?? u.inputTokens ?? u.prompt_tokens ?? u.promptTokenCount ?? 0);
  const outTok = Number(u.output_tokens ?? u.outputTokens ?? u.completion_tokens ?? u.candidatesTokenCount ?? 0);
  const cacheR = Number(u.cache_read_tokens ?? u.cached_read_tokens ?? u.cachedReadTokens ?? u.cacheReadTokens ?? u.cachedContentTokenCount ?? 0);
  const cacheW = Number(u.cache_write_tokens ?? u.cached_write_tokens ?? u.cachedWriteTokens ?? u.cacheWriteTokens ?? 0);
  const reasoning = Number(u.thinking_tokens ?? u.thoughtTokens ?? u.reasoningTokens ?? u.thoughtsTokenCount ?? 0);
  const total = Number(u.total_tokens ?? u.totalTokens ?? u.totalTokenCount ?? 0);
  if (rawIn > 0 || outTok > 0 || cacheR > 0 || cacheW > 0) {
    const totalIn = resolveNormalizedTotalInput(rawIn, outTok, cacheR, cacheW, total, exclusiveByDefault, reasoning);
    return totalIn + Math.max(0, outTok);
  }
  return total;
}

/** Antigravity CLI 原生 Stream-JSON 双工流式驱动（与 Claude Code stream-json 对等，支持 --settings 自定义网关与精准 Token 统计）。 */
class AgyStreamJsonDriver {
  constructor({agentId, binary, env, cwd, launchOpts, providerInfo, onEvent}) {
    this.agentId = agentId;
    this.binary = binary;
    this.env = env;
    this.cwd = cwd;
    this.launchOpts = launchOpts || {};
    this.providerInfo = providerInfo || null;
    this.onEvent = onEvent;
    this.activeProcs = new Set();
    this.settingsFiles = new Set();
    this.closed = false;
    this.lastActivity = Date.now();
  }

  async prompt(sessionId, text, {cwd, model, effort, mode, remoteSessionId, availableModels} = {}) {
    this.lastActivity = Date.now();
    const args = ["--output-format", "stream-json"];
    // 自定义服务商模型名不走官方 agy 变体后缀拼接，避免给第三方模型名错误追加 -high 等后缀
    const effectiveModel = this.providerInfo?.id
      ? (String(model || "").trim() || null)
      : resolveAgyConcreteModel(model, effort, null, availableModels);
    if (this.providerInfo?.id) {
      const settingsFile = await writeAgyCustomSettings(this.providerInfo, effectiveModel || model, sessionId);
      if (settingsFile) {
        this.settingsFiles.add(settingsFile);
        args.push("--settings", settingsFile);
      }
    }
    if (effectiveModel) args.push("--model", effectiveModel);
    if (effort && ["low", "medium", "high", "xhigh", "max"].includes(effort)) args.push("--effort", effort);
    if (this.launchOpts.bypassPermissions || mode === "bypass") args.push("--dangerously-skip-permissions");
    else if (mode && ["accept-edits", "auto", "plan"].includes(mode)) args.push("--mode", mode);
    if (this.launchOpts.sandbox) args.push("--sandbox");
    if (this.launchOpts.agentName) args.push("--agent", this.launchOpts.agentName);
    if (remoteSessionId) args.push("--conversation", remoteSessionId);
    args.push("-p", text);

    return await new Promise((resolve, reject) => {
      const child = spawn(this.binary, args, {
        cwd: cwd || this.cwd || path.join(HOME, "workspace"),
        env: this.env,
        detached: process.platform === "linux",
        stdio: ["ignore", "pipe", "pipe"],
      });
      this.activeProcs.add(child);
      let fullText = "";
      let convId = remoteSessionId || null;
      let usage = null;
      let lastStepUsage = null;
      let emittedStepUsage = false;
      const ctxWindowSize = inferModelContextWindow(effectiveModel || model, Boolean(this.providerInfo?.id));
      let stderrTail = "";
      let settled = false;
      const seenTools = new Set();
      const activeTools = new Set();
      let lastProgressAt = Date.now();
      let stalledError = null;
      const stallTimeoutMs = 120_000;
      const killChild = () => {
        try {
          if (process.platform !== "win32" && child.pid) process.kill(-child.pid, "SIGTERM");
          else child.kill("SIGTERM");
        } catch {}
      };
      const timeoutTimer = setTimeout(killChild, 20 * 60 * 1000);
      const stallTimer = setInterval(() => {
        if (settled) return;
        if (activeTools.size > 0) { lastProgressAt = Date.now(); return; }
        if (Date.now() - lastProgressAt >= stallTimeoutMs) {
          stalledError = new AcpStreamStallError(stallTimeoutMs);
          killChild();
        }
      }, 5_000);
      stallTimer.unref?.();
      const rl = createInterface({input: child.stdout, crlfDelay: Infinity});
      rl.on("line", line => {
        lastProgressAt = Date.now();
        const trimmed = line.trim();
        if (!trimmed.startsWith("{")) return;
        let obj;
        try { obj = JSON.parse(trimmed); } catch { return; }
        if (obj.conversation_id) convId = String(obj.conversation_id);
        if (obj.event === "step_update" && obj.step_update) {
          const su = obj.step_update;
          if (su.usage) {
            usage = su.usage;
            lastStepUsage = su.usage;
            const stepCtx = stepContextTokensOf(su.usage, true);
            if (stepCtx > 0) {
              emittedStepUsage = true;
              this.onEvent({kind: "acp-update", update: {sessionUpdate: "usage_update", used: stepCtx, size: ctxWindowSize}});
            }
          }
          const thoughtChunk = su.thinking_delta ?? su.thought_delta ?? su.reasoning_delta
            ?? ((su.step_type === "thinking" || su.step_type === "thought") ? (su.thinking ?? su.thought ?? su.text_delta ?? su.text) : null);
          if (thoughtChunk) {
            this.onEvent({kind: "acp-update", update: {sessionUpdate: "agent_thought_chunk", content: {type: "text", text: String(thoughtChunk)}}});
          }
          if (su.text_delta && su.step_type !== "thinking" && su.step_type !== "thought") {
            fullText += String(su.text_delta);
            this.onEvent({kind: "acp-update", update: {sessionUpdate: "agent_message_chunk", content: {type: "text", text: String(su.text_delta)}}});
          }
          const planEntries = Array.isArray(su.steps) ? su.steps
            : Array.isArray(su.todos) ? su.todos
            : Array.isArray(su.plan) ? su.plan
            : null;
          const planMarkdown = typeof su.plan_markdown === "string" ? su.plan_markdown
            : (su.step_type === "plan" && typeof (su.text || su.content) === "string" ? (su.text || su.content) : null);
          if (planEntries || planMarkdown) {
            this.onEvent({
              kind: "acp-update",
              update: {
                sessionUpdate: "plan",
                ...(planEntries ? {entries: planEntries.map(e => ({
                  content: String(e.title || e.content || e.step || ""),
                  status: String(e.status || "pending"),
                  ...(e.activeForm ? {activeForm: String(e.activeForm)} : {}),
                  ...(e.priority ? {priority: String(e.priority)} : {}),
                })).filter(e => e.content)} : {}),
                ...(planMarkdown ? {markdown: planMarkdown} : {}),
                ...(su.explanation ? {explanation: String(su.explanation)} : {}),
              },
            });
          }
          if (su.step_type && !["user_input", "agent_response", "thinking", "thought", "plan"].includes(su.step_type)) {
            const st = su.state === "DONE" ? "completed" : su.state === "ERROR" ? "failed" : "in_progress";
            const tid = `agy-step-${su.step_index ?? 0}`;
            const kind = seenTools.has(tid) ? "tool_call_update" : "tool_call";
            seenTools.add(tid);
            if (st === "in_progress") activeTools.add(tid);
            else activeTools.delete(tid);
            this.onEvent({
              kind: "acp-update",
              update: {
                sessionUpdate: kind,
                toolCallId: tid,
                title: String(su.tool_name || su.step_type),
                status: st,
                rawInput: su.tool_input || undefined,
                rawOutput: su.tool_output || undefined,
              },
            });
          }
        } else if (obj.event === "result" && obj.result) {
          if (obj.result.conversation_id) convId = String(obj.result.conversation_id);
          if (obj.result.usage) usage = obj.result.usage;
          if (!fullText && typeof obj.result.response === "string") {
            fullText = obj.result.response;
            this.onEvent({kind: "acp-update", update: {sessionUpdate: "agent_message_chunk", content: {type: "text", text: fullText}}});
          }
          // 若本轮 step_update 尚未上报过 usage_update，再用 result 兜底上报，避免重复发射一条虚增的 llmSteps 记录
          if (!emittedStepUsage) {
            const ctxUsed = stepContextTokensOf(lastStepUsage || usage, true);
            if (ctxUsed > 0) {
              this.onEvent({kind: "acp-update", update: {sessionUpdate: "usage_update", used: ctxUsed, size: ctxWindowSize}});
            }
          }
        }
      });
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", d => { stderrTail = (stderrTail + d).slice(-4096); });
      child.once("error", err => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutTimer);
        clearInterval(stallTimer);
        this.activeProcs.delete(child);
        reject(err);
      });
      // 等待 stdout/readline 与子进程完全关闭（close 而非 exit），防止尾部 result/usage 行被截断
      let rlClosed = false;
      let procClosed = false;
      let exitCode = null, exitSignal = null;
      const maybeFinish = () => {
        if (settled || !rlClosed || !procClosed) return;
        settled = true;
        clearTimeout(timeoutTimer);
        clearInterval(stallTimer);
        this.activeProcs.delete(child);
        if (stalledError) { reject(stalledError); return; }
        const ctxUsed = stepContextTokensOf(lastStepUsage || usage, true);
        const contextUsage = ctxUsed > 0 ? {used: ctxUsed, size: ctxWindowSize} : null;
        if (exitCode === 0) resolve({text: fullText, stopReason: "end_turn", remoteSessionId: convId, usage, contextUsage});
        else if (exitSignal === "SIGTERM" || exitSignal === "SIGINT") resolve({text: fullText, stopReason: "cancelled", remoteSessionId: convId, usage, contextUsage});
        else reject(new Error(stderrTail.trim().slice(-400) || `Antigravity Stream-JSON 退出（${exitCode ?? exitSignal}）`));
      };
      rl.once("close", () => { rlClosed = true; maybeFinish(); });
      child.once("close", (code, signal) => { exitCode = code; exitSignal = signal; procClosed = true; maybeFinish(); });
    });
  }

  async cancel() {
    for (const child of this.activeProcs) {
      try {
        if (process.platform !== "win32" && child.pid) process.kill(-child.pid, "SIGTERM");
        else child.kill("SIGTERM");
      } catch {}
    }
  }

  close() {
    this.closed = true;
    void this.cancel();
    for (const f of this.settingsFiles) {
      fs.rm(f, {force: true}).catch(() => {});
    }
    this.settingsFiles.clear();
  }
}

async function queryAgyCatalog(extraApiKey = null) {
  const env = baseEnv(manifests.get("antigravity") || null, null, networkConfig, userGlobalEnv, extraApiKey);
  const bin = antigravityInstallation.binary;
  const models = [];
  let rawModels = "";
  try {
    const {stdout, stderr} = await execFileAsync(bin, ["models"], {timeout: 20_000, maxBuffer: 512 * 1024, env});
    rawModels = stripAnsi((stdout || "") + "\n" + (stderr || ""));
    for (const line of rawModels.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || /^fetching|^available|^no\s+models/i.test(trimmed)) continue;
      const parts = trimmed.split(/\t+|\s{2,}/);
      const id = (parts[0] || "").trim();
      if (!id || !/^[a-z0-9][a-z0-9._:/-]{1,120}$/i.test(id)) continue;
      const name = (parts[1] || id).trim();
      models.push({modelId: id, name});
    }
    if (models.length) cachedAgyRawModels = models;
  } catch (err) {
    rawModels = stripAnsi(err.stdout || err.stderr || err.message || "").slice(0, 600);
  }
  const groupedModels = groupAgyModelsList(models);
  const agents = [];
  try {
    const {stdout} = await execFileAsync(bin, ["agents"], {timeout: 12_000, maxBuffer: 256 * 1024, env});
    for (const line of stripAnsi(stdout || "").split(/\r?\n/)) {
      const t = line.trim();
      if (!t || /^available|^no\s+agents|^name\b/i.test(t) || /^[-=]+$/.test(t)) continue;
      const parts = t.split(/\t+|\s{2,}/);
      const id = (parts[0] || "").replace(/^[*•-]\s*/, "").trim();
      if (id && /^[a-z0-9][a-z0-9._:/-]{0,80}$/i.test(id)) {
        agents.push({id, description: (parts[1] || "").trim()});
      }
    }
  } catch {}
  return {
    models: groupedModels,
    rawModels: models,
    groupedModels,
    agents,
    modes: [
      {id: "default", name: "默认询问", description: "编辑和执行命令前先询问确认"},
      {id: "accept-edits", name: "自动允许编辑", description: "自动放行工作区文件修改，命令仍询问"},
      {id: "auto", name: "智能自动", description: "自动放行编辑与安全命令，高风险操作前询问"},
      {id: "plan", name: "计划模式", description: "先分析制定执行计划再实施"},
      {id: "bypass", name: "完全访问", description: "启动 --dangerously-skip-permissions 跳过全部确认"},
    ],
    efforts: ["low", "medium", "high", "xhigh", "max"],
    raw: rawModels.slice(0, 1000),
  };
}

async function handleAgyPlugins(params = {}) {
  const action = String(params.action || "list");
  const env = baseEnv(manifests.get("antigravity") || null, null);
  const bin = antigravityInstallation.binary;
  if (action === "list") {
    try {
      const {stdout, stderr} = await execFileAsync(bin, ["plugin", "list", "--json"], {timeout: 15_000, env});
      const text = stripAnsi((stdout || "") + "\n" + (stderr || "")).trim();
      const plugins = [];
      try {
        const parsed = JSON.parse(stdout.trim());
        const arr = Array.isArray(parsed) ? parsed : (Array.isArray(parsed?.plugins) ? parsed.plugins : []);
        for (const p of arr) {
          if (p && p.name) plugins.push({name: String(p.name), enabled: p.enabled !== false, description: String(p.description || ""), version: String(p.version || "")});
        }
      } catch {
        for (const line of text.split(/\r?\n/)) {
          const clean = line.trim();
          if (!clean || /^installed|^no\s+plugins|^name\s+/i.test(clean) || /^[-=]+$/.test(clean)) continue;
          const m = /^(?:[✓✗●○*-]\s*)?([a-zA-Z0-9._/@-]+)(?:\s+\(([^)]+)\))?(?:\s*[-–:]\s*(.*))?$/.exec(clean);
          if (m) {
            const disabled = /\bdisabled\b|停用|禁用/i.test(clean);
            plugins.push({name: m[1], enabled: !disabled, version: m[2] || "", description: (m[3] || "").trim()});
          }
        }
      }
      return {plugins, raw: text.slice(0, 2000)};
    } catch (err) {
      return {plugins: [], raw: stripAnsi(err.stdout || err.stderr || err.message || "").slice(0, 1000)};
    }
  }
  if (action === "import") {
    const source = String(params.source || "").trim();
    const args = source && ["claude", "gemini"].includes(source) ? ["plugin", "import", source] : ["plugin", "import"];
    const {stdout, stderr} = await execFileAsync(bin, args, {timeout: 30_000, env});
    return {ok: true, output: stripAnsi((stdout || "") + "\n" + (stderr || "")).trim().slice(0, 1500)};
  }
  const name = String(params.name || "").trim();
  if (!name || !/^[a-zA-Z0-9._/@:~-]{1,180}$/.test(name)) throw new Error("插件名或目标无效");
  if (["enable", "disable", "install", "uninstall"].includes(action)) {
    const {stdout, stderr} = await execFileAsync(bin, ["plugin", action, name], {timeout: action === "install" ? 120_000 : 25_000, env});
    return {ok: true, output: stripAnsi((stdout || "") + "\n" + (stderr || "")).trim().slice(0, 1500)};
  }
  throw new Error("不支持的 Antigravity 插件操作：" + action);
}

async function listAgyArtifacts() {
  const dirs = [
    path.join(HOME, ".gemini", "antigravity-cli", "brain"),
    path.join(HOME, ".gemini", "antigravity", "brain"),
  ];
  const out = [];
  const seen = new Set();
  for (const brainDir of dirs) {
    const convDirs = await fs.readdir(brainDir, {withFileTypes: true}).catch(() => []);
    for (const d of convDirs) {
      if (!d.isDirectory()) continue;
      const fullConv = path.join(brainDir, d.name);
      const files = await fs.readdir(fullConv, {withFileTypes: true}).catch(() => []);
      for (const f of files) {
        if (!f.isFile() || f.name.startsWith(".")) continue;
        if (!/\.(md|html|svg|tex|txt|json|csv|png|jpg|webp|pdf)$/i.test(f.name)) continue;
        const fullPath = path.join(fullConv, f.name);
        if (seen.has(fullPath)) continue;
        seen.add(fullPath);
        const st = await fs.stat(fullPath).catch(() => null);
        if (!st) continue;
        out.push({
          id: `${d.name}/${f.name}`,
          name: f.name,
          path: fullPath,
          size: st.size,
          updatedAt: st.mtimeMs,
          sessionDir: d.name,
        });
      }
    }
  }
  out.sort((a, b) => b.updatedAt - a.updatedAt);
  return {artifacts: out.slice(0, 60)};
}

const BRAIN_MD_EXT_RE = /\.(md|markdown)$/i;
const BRAIN_IGNORE_FILE_RE = /(?:^|\.)metadata\.json$|\.resolved(?:\.\d+)?$/i;

function normalizeHubStepStatus(raw) {
  const s = String(raw || "").trim().toLowerCase();
  if (["done", "completed", "complete", "finished", "success"].includes(s)) return "completed";
  if (["in_progress", "inprogress", "running", "active", "working", "started"].includes(s)) return "in_progress";
  if (["failed", "error", "blocked"].includes(s)) return "failed";
  if (["skipped", "cancelled", "canceled", "deleted"].includes(s)) return "skipped";
  return "pending";
}

function extractHubPlanTitle(markdown, fallbackPath = "") {
  const lines = String(markdown || "").split(/\r?\n/);
  for (const raw of lines) {
    const t = raw.trim();
    if (t.startsWith("#")) {
      const cleaned = t.replace(/^#+/, "").trim().slice(0, 48);
      if (cleaned) return cleaned;
    }
  }
  const base = path.basename(String(fallbackPath || "")).replace(/\.(md|markdown)$/i, "").toLowerCase();
  if (base === "implementation_plan") return "实施计划";
  if (base === "walkthrough") return "实施总结";
  if (base === "task") return "任务清单";
  return base ? base.slice(0, 40) : "执行计划";
}

function getBrainDirsForSession(remoteSessionId) {
  const sid = String(remoteSessionId || "").trim();
  if (!sid || !/^[a-zA-Z0-9._-]{4,120}$/.test(sid)) return [];
  return [
    path.join(HOME, ".gemini", "antigravity-cli", "brain", sid),
    path.join(HOME, ".gemini", "antigravity", "brain", sid),
  ];
}

function inferBrainArtifactMeta(fileName, content, companionMeta = null) {
  const lower = String(fileName || "").toLowerCase();
  const metaType = String(companionMeta?.artifactType || companionMeta?.ArtifactType || "").toLowerCase();
  let artifactType = "artifact";
  if (lower === "implementation_plan.md" || lower === "plan.md" || metaType.includes("implementation_plan")) {
    artifactType = "implementation_plan";
  } else if (lower === "walkthrough.md" || metaType.includes("walkthrough")) {
    artifactType = "walkthrough";
  } else if (lower === "task.md" || metaType.includes("task")) {
    artifactType = "task";
  }
  const explicitFeedback = companionMeta?.requestFeedback ?? companionMeta?.RequestFeedback;
  const requestFeedback = typeof explicitFeedback === "boolean"
    ? explicitFeedback
    : artifactType === "implementation_plan";
  const summary = String(companionMeta?.summary || companionMeta?.Summary || "").trim();
  return {
    artifactType,
    requestFeedback,
    summary,
    title: extractHubPlanTitle(content, fileName),
  };
}

async function snapshotBrainArtifacts(remoteSessionId) {
  const mtimes = new Map();
  for (const dir of getBrainDirsForSession(remoteSessionId)) {
    const entries = await fs.readdir(dir, {withFileTypes: true}).catch(() => []);
    for (const ent of entries) {
      if (!ent.isFile() || ent.name.startsWith(".") || BRAIN_IGNORE_FILE_RE.test(ent.name)) continue;
      if (!BRAIN_MD_EXT_RE.test(ent.name)) continue;
      const fullPath = path.join(dir, ent.name);
      const st = await fs.stat(fullPath).catch(() => null);
      if (st) mtimes.set(fullPath, st.mtimeMs);
    }
  }
  return mtimes;
}

async function collectSessionBrainPlans(remoteSessionId, baselineMtimes = null) {
  const results = [];
  const seen = new Set();
  for (const dir of getBrainDirsForSession(remoteSessionId)) {
    const entries = await fs.readdir(dir, {withFileTypes: true}).catch(() => []);
    for (const ent of entries) {
      if (!ent.isFile() || ent.name.startsWith(".") || BRAIN_IGNORE_FILE_RE.test(ent.name)) continue;
      if (!BRAIN_MD_EXT_RE.test(ent.name)) continue;
      const fullPath = path.join(dir, ent.name);
      if (seen.has(fullPath)) continue;
      seen.add(fullPath);
      const st = await fs.stat(fullPath).catch(() => null);
      if (!st || st.size === 0 || st.size > 262_144) continue;
      if (baselineMtimes && st.mtimeMs <= (baselineMtimes.get(fullPath) || 0)) continue;
      const content = await fs.readFile(fullPath, "utf8").catch(() => "");
      if (!content.trim()) continue;
      if (baselineMtimes) baselineMtimes.set(fullPath, st.mtimeMs);
      let companionMeta = null;
      const metaRaw = await fs.readFile(`${fullPath}.metadata.json`, "utf8").catch(() => "");
      if (metaRaw) {
        try { companionMeta = JSON.parse(metaRaw); } catch {}
      }
      const meta = inferBrainArtifactMeta(ent.name, content, companionMeta);
      results.push({
        id: `brain-${ent.name.replace(/[^a-zA-Z0-9._-]/g, "_")}`,
        path: fullPath,
        title: meta.title,
        markdown: content.slice(0, 160_000),
        requestFeedback: meta.requestFeedback,
        summary: meta.summary,
        artifactType: meta.artifactType,
        updatedAt: st.mtimeMs,
      });
    }
  }
  results.sort((a, b) => a.updatedAt - b.updatedAt);
  return results;
}

function upsertTurnPlan(turn, planObj) {
  const key = planObj.filePath || planObj.id;
  const existing = turn.planMap.get(key);
  if (existing) {
    Object.assign(existing, planObj);
  } else {
    const rec = {...planObj, ts: Date.now()};
    turn.planMap.set(key, rec);
    turn.timelineOrder.push({type: "plan", ref: rec});
  }
}

function recordTurnToolUpdate(turn, tid, upd, now) {
  const title = String(upd.title || "");
  const norm = normalizeAcpMetaToolName(title);
  if (!turn.metaToolIds) turn.metaToolIds = new Set();
  const rawInput = upd.rawInput && typeof upd.rawInput === "object" ? upd.rawInput : null;
  if (turn.metaToolIds.has(tid) || isAcpMetaToolName(title)) {
    turn.metaToolIds.add(tid);
    if (rawInput) {
      if (["update_steps", "update_plan", "todowrite", "todo_write", "set_plan"].includes(norm)) {
        const arr = rawInput.steps || rawInput.plan || rawInput.todos || rawInput.entries;
        if (Array.isArray(arr)) {
          turn.planSteps = arr.map(s => ({
            step: String(s?.title || s?.content || s?.step || "").trim(),
            status: normalizeHubStepStatus(s?.status),
            id: s?.id ? String(s.id) : undefined,
            activeForm: s?.activeForm ? String(s.activeForm) : undefined,
            priority: s?.priority ? String(s.priority) : undefined,
          })).filter(s => s.step);
          if (rawInput.explanation || rawInput.summary) turn.planExplanation = String(rawInput.explanation || rawInput.summary);
        }
      } else if (norm === "update_focus") {
        const f = typeof rawInput.focus === "string" ? rawInput.focus : (typeof rawInput.status === "string" ? rawInput.status : "");
        if (f.trim()) turn.focusText = f.trim();
      } else if (norm === "task_boundary" && typeof rawInput.TaskName === "string") {
        const list = Array.isArray(turn.planSteps) ? [...turn.planSteps] : [];
        const idx = list.findIndex(s => s.step === rawInput.TaskName);
        const entry = {
          step: rawInput.TaskName,
          status: "in_progress",
          activeForm: rawInput.TaskStatus ? String(rawInput.TaskStatus) : undefined,
          priority: rawInput.Mode ? String(rawInput.Mode) : undefined,
        };
        if (idx >= 0) list[idx] = entry;
        else {
          for (const item of list) {
            if (item.status === "in_progress") item.status = "completed";
          }
          list.push(entry);
        }
        turn.planSteps = list;
        if (rawInput.TaskStatus || rawInput.Mode) turn.planExplanation = String(rawInput.TaskStatus || rawInput.Mode);
      }
    }
    return;
  }
  const kindStr = String(upd.kind || "").toLowerCase();
  const status = String(upd.status || "").toLowerCase() === "failed"
    ? "failed"
    : (String(upd.status || "").toLowerCase() === "completed" ? "completed" : "running");
  const contentBlocks = Array.isArray(upd.content) ? upd.content : [];
  const diffs = [];
  for (const b of contentBlocks) {
    if (String(b?.type || "").toLowerCase() === "diff" && b.path) {
      const oldStr = String(b.oldText || "");
      const newStr = String(b.newText || "");
      const diffText = typeof b.diff === "string" && b.diff.length > 0
        ? b.diff.slice(0, 48_000)
        : computeUnifiedDiff(b.path, oldStr, newStr);
      diffs.push({
        path: String(b.path),
        kind: b.kind ? String(b.kind) : (!oldStr && newStr ? "add" : (oldStr && !newStr ? "delete" : "update")),
        diff: diffText,
      });
    }
  }
  const textBlocks = contentBlocks.map(b => {
    const t = String(b?.type || "").toLowerCase();
    if (t === "content") return String(b?.content?.text || b?.text || "");
    if (t === "text") return String(b?.text || "");
    return "";
  }).filter(Boolean).join("\n");
  let outputText = textBlocks;
  if (!outputText && upd.rawOutput != null) {
    if (typeof upd.rawOutput === "string") outputText = upd.rawOutput;
    else if (typeof upd.rawOutput === "object") {
      outputText = String(upd.rawOutput.stdout || upd.rawOutput.output || upd.rawOutput.text || JSON.stringify(upd.rawOutput));
    }
  }
  const existing = turn.toolMap.get(tid);
  if (existing) {
    if (title) existing.name = title;
    if (kindStr) existing.kind = kindStr;
    if (rawInput) existing.input = JSON.stringify(rawInput).slice(0, 12_000);
    if (outputText) existing.result = outputText.slice(0, 24_000);
    if (diffs.length) existing.diffs = diffs;
    existing.status = status;
    if (status === "completed" || status === "failed") {
      existing.durationMs = Math.max(0, now - (existing.startedAtMs || now));
    }
    if (upd.rawOutput && typeof upd.rawOutput === "object") {
      const ec = upd.rawOutput.exitCode ?? upd.rawOutput.exit_code;
      if (typeof ec === "number") existing.exitCode = ec;
    }
  } else {
    const cmd = rawInput?.command || rawInput?.cmd || "";
    const rec = {
      id: tid,
      name: title || (kindStr === "execute" ? "Bash" : (kindStr || "工具调用")),
      kind: kindStr,
      command: cmd ? String(cmd).slice(0, 4000) : undefined,
      cwd: rawInput?.cwd ? String(rawInput.cwd) : undefined,
      input: rawInput ? JSON.stringify(rawInput).slice(0, 12_000) : "",
      result: outputText ? outputText.slice(0, 24_000) : "",
      diffs,
      status,
      startedAtMs: now,
      ts: now,
    };
    turn.toolMap.set(tid, rec);
    turn.timelineOrder.push({type: "tool", ref: rec});
  }
}

function buildContextBridgedPrompt(record, text, userMsgId) {
  const prior = (record.messages || [])
    .filter(msg => (msg.role === "user" || msg.role === "agent") && msg.id !== userMsgId && typeof msg.text === "string" && msg.text.trim())
    .slice(-16);
  if (prior.length === 0) return text;
  let budget = 20_000;
  const picked = [];
  for (let i = prior.length - 1; i >= 0 && budget > 0; i--) {
    const msg = prior[i];
    const chunk = msg.text.trim().slice(0, Math.min(2000, budget));
    picked.unshift(`${msg.role === "user" ? "用户" : "助手"}：${chunk}`);
    budget -= chunk.length;
  }
  return `[以下为此会话此前的对话上下文，请自然承接继续回答，勿提及此系统提示]\n${picked.join("\n\n")}\n\n[当前用户消息]\n${text}`;
}

const networkWaiters = new Set();

function wakeNetworkWaiters() {
  let count = 0;
  for (const wake of [...networkWaiters]) {
    try { wake(); count++; } catch {}
  }
  return count;
}

function parseProxyTarget(env) {
  const raw = env?.https_proxy || env?.HTTPS_PROXY || env?.http_proxy || env?.HTTP_PROXY || env?.all_proxy || env?.ALL_PROXY || "";
  const trimmed = String(raw || "").trim();
  if (!trimmed) return null;
  try {
    const normalized = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
    const u = new URL(normalized);
    if (!u.hostname) return null;
    const protocol = u.protocol.toLowerCase();
    const port = Number(u.port) || (protocol === "https:" ? 443 : protocol.startsWith("socks") ? 1080 : 80);
    const host = u.hostname.replace(/^\[|\]$/g, "");
    const isLoopback = host === "127.0.0.1" || host === "localhost" || host === "::1";
    const auth = (u.username || u.password)
      ? Buffer.from(`${decodeURIComponent(u.username)}:${decodeURIComponent(u.password)}`).toString("base64")
      : "";
    return {protocol, host, port, isLoopback, auth};
  } catch {
    return null;
  }
}

function probeTcpConnect(host, port, timeoutMs = 3500) {
  return new Promise(resolve => {
    let settled = false;
    const finish = res => {
      if (settled) return;
      settled = true;
      try { sock.destroy(); } catch {}
      resolve(res);
    };
    const sock = net.createConnection({host, port});
    sock.setTimeout(timeoutMs, () => finish({ok: false, code: "ETIMEDOUT", message: `connect timeout ${host}:${port}`}));
    sock.once("connect", () => finish({ok: true}));
    sock.once("error", err => finish({ok: false, code: err?.code || "ERR", message: err?.message || String(err)}));
  });
}

function probeHttpProxyConnect(proxyHost, proxyPort, targetHost, targetPort, authBase64 = "", timeoutMs = 3500) {
  return new Promise(resolve => {
    let settled = false;
    let buf = "";
    const finish = res => {
      if (settled) return;
      settled = true;
      try { sock.destroy(); } catch {}
      resolve(res);
    };
    const sock = net.createConnection({host: proxyHost, port: proxyPort});
    sock.setTimeout(timeoutMs, () => finish({ok: false, code: "ETIMEDOUT", message: "proxy connect timeout"}));
    sock.once("connect", () => {
      const authLine = authBase64 ? `Proxy-Authorization: Basic ${authBase64}\r\n` : "";
      sock.write(`CONNECT ${targetHost}:${targetPort} HTTP/1.1\r\nHost: ${targetHost}:${targetPort}\r\n${authLine}Proxy-Connection: close\r\n\r\n`);
    });
    sock.on("data", chunk => {
      buf += chunk.toString("utf8");
      const nl = buf.indexOf("\r\n");
      if (nl < 0 && buf.length < 256) return;
      const firstLine = (nl >= 0 ? buf.slice(0, nl) : buf).trim();
      if (/^HTTP\/\d\.\d\s+200\b/i.test(firstLine)) finish({ok: true});
      else finish({ok: false, code: "EPROXY", message: firstLine || "proxy rejected"});
    });
    sock.once("error", err => finish({ok: false, code: err?.code || "ERR", message: err?.message || String(err)}));
    sock.once("close", () => finish({ok: false, code: "ECONNRESET", message: "proxy closed"}));
  });
}

async function probeOutboundNetwork(env, timeoutMs = 3500) {
  const override = String(process.env.GALIAIS_NET_PROBE_TARGET || "").trim();
  if (override.toLowerCase() === "skip") return {ok: true};
  if (override) {
    const idx = override.lastIndexOf(":");
    const host = idx > 0 ? override.slice(0, idx) : override;
    const port = idx > 0 ? (Number(override.slice(idx + 1)) || 443) : 443;
    return await probeTcpConnect(host, port, timeoutMs);
  }
  const targetHost = "daily-cloudcode-pa.googleapis.com";
  const targetPort = 443;
  const proxy = parseProxyTarget(env);
  if (proxy) {
    if (proxy.isLoopback && (proxy.protocol === "http:" || proxy.protocol === "https:")) {
      return await probeHttpProxyConnect(proxy.host, proxy.port, targetHost, targetPort, proxy.auth, timeoutMs);
    }
    return await probeTcpConnect(proxy.host, proxy.port, timeoutMs);
  }
  return await probeTcpConnect(targetHost, targetPort, timeoutMs);
}

async function waitForNetworkRecovery(turn, {agentId, sessionId, env, initialReason = ""}) {
  const maxWaitMs = Number(process.env.GALIAIS_NET_WAIT_MAX_MS) > 0
    ? Number(process.env.GALIAIS_NET_WAIT_MAX_MS)
    : 10 * 60 * 1000;
  const customPollMs = Number(process.env.GALIAIS_NET_WAIT_POLL_MS) > 0
    ? Number(process.env.GALIAIS_NET_WAIT_POLL_MS)
    : 0;
  const startedAt = Date.now();
  let lastError = initialReason;
  let lastNoticeAt = 0;
  let pollIdx = 0;

  while (!turn.cancelled) {
    const probeTimeout = customPollMs ? Math.min(2000, Math.max(300, customPollMs * 2)) : 3500;
    const probe = await probeOutboundNetwork(env, probeTimeout);
    if (turn.cancelled) return {recovered: false, cancelled: true, lastError};
    if (probe.ok) {
      notify("agent/event", {
        agentId,
        sessionId,
        kind: "acp-update",
        update: {sessionUpdate: "focus_update", focus: "网络已恢复，正在重新连接 Antigravity…"},
      });
      return {recovered: true, cancelled: false};
    }
    lastError = probe.message || probe.code || lastError;
    const elapsed = Date.now() - startedAt;
    if (elapsed >= maxWaitMs) {
      return {recovered: false, cancelled: false, lastError};
    }
    const now = Date.now();
    if (now - lastNoticeAt >= 8000 || pollIdx === 0) {
      lastNoticeAt = now;
      const isPerm = /EPERM|operation\s+not\s+permitted/i.test(`${probe.code || ""} ${probe.message || ""} ${initialReason}`);
      const waitFocus = isPerm
        ? "锁屏或后台网络受限（系统省电/防火墙拦截），等待解锁或网络恢复后自动继续…"
        : "网络暂时不可用，等待连接恢复后自动继续…";
      notify("agent/event", {
        agentId,
        sessionId,
        kind: "acp-update",
        update: {sessionUpdate: "focus_update", focus: waitFocus},
      });
    }
    const baseDelay = customPollMs || [1500, 2000, 3000, 4000][Math.min(pollIdx, 3)];
    pollIdx++;
    const remain = Math.max(40, Math.min(baseDelay, maxWaitMs - (Date.now() - startedAt)));
    await new Promise(resolve => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        networkWaiters.delete(finish);
        if (turn.abortRetry === finish) turn.abortRetry = null;
        resolve();
      };
      networkWaiters.add(finish);
      turn.abortRetry = finish;
      const timer = setTimeout(finish, remain);
      timer.unref?.();
    });
  }
  return {recovered: false, cancelled: true, lastError};
}

function sleepCancellable(turn, delayMs) {
  if (!delayMs || delayMs <= 0) return Promise.resolve(!turn.cancelled);
  if (turn.cancelled) return Promise.resolve(false);
  return new Promise(resolve => {
    let done = false;
    const finish = ok => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (turn.abortRetry === onAbort) turn.abortRetry = null;
      resolve(ok);
    };
    const onAbort = () => finish(false);
    turn.abortRetry = onAbort;
    const timer = setTimeout(() => finish(true), delayMs);
    timer.unref?.();
  });
}

function startAgyLogMonitor(logFile, onInternalRetry) {
  if (!logFile || typeof onInternalRetry !== "function") return {stop() {}};
  let stopped = false;
  let offset = 0;
  let remainder = "";
  let polling = false;
  const initPromise = fs.stat(logFile).then(st => { offset = st.size; }, () => { offset = 0; });
  const poll = async () => {
    if (stopped || polling) return;
    polling = true;
    try {
      await initPromise;
      if (stopped) return;
      const st = await fs.stat(logFile).catch(() => null);
      if (!st || st.size <= offset) return;
      const readLen = Math.min(st.size - offset, 65_536);
      const fh = await fs.open(logFile, "r");
      let chunk = "";
      try {
        const buf = Buffer.allocUnsafe(readLen);
        const {bytesRead} = await fh.read(buf, 0, readLen, offset);
        offset += bytesRead;
        chunk = buf.subarray(0, bytesRead).toString("utf8");
      } finally {
        await fh.close();
      }
      if (stopped || !chunk) return;
      const combined = remainder + chunk;
      const lines = combined.split(/\r?\n/);
      remainder = lines.pop() || "";
      for (const line of lines) {
        if (stopped) break;
        const parsed = parseAgyInternalRetryLine(line);
        if (parsed) onInternalRetry(parsed);
      }
    } catch {}
    finally { polling = false; }
  };
  const timer = setInterval(() => { void poll(); }, 1200);
  timer.unref?.();
  return {
    stop() {
      stopped = true;
      clearInterval(timer);
    },
  };
}

async function handleRequest(method, params = {}) {
  if (method === "agents/install" || method === "agents/uninstall") {
    if (packageOperation) throw new Error("已有安装或卸载任务，请完成后再试");
    const id = params.manifest?.id ?? params.agentId;
    if (typeof id !== "string" || !/^[a-z0-9][a-z0-9_-]{0,47}$/.test(id) || (params.agentId && params.agentId !== id)) throw new Error("Agent 标识无效");
    const operationId = params.operationId ?? randomUUID();
    if (typeof operationId !== "string" || !/^[a-zA-Z0-9._-]{1,100}$/.test(operationId)) throw new Error("安装任务标识无效");
    packageOperation = {agentId:id, method, operationId, child:null, deadline:Date.now() + (method === "agents/install" ? 9 * 60_000 : 90_000)};
    try { return await dispatchRequest(method, params); }
    finally { packageOperation = null; }
  }
  if (!SESSION_MUTATIONS.has(method)) return dispatchRequest(method, params);
  validateAgentId(params.agentId);
  sessionFile(params.agentId, params.sessionId);
  const key = `${params.agentId}:${params.sessionId}`;
  if (method !== "sessions/delete" && busySessions.has(key)) throw new Error("该会话正在处理请求，请先等待完成或中断");
  busySessions.add(key);
  try { return await dispatchRequest(method, params); }
  finally { busySessions.delete(key); }
}

async function dispatchRequest(method, params = {}) {
  if (method === "initialize") {
    await initialUserEnvReady;
    if (!pluginKernel.generationId) {
      await pluginKernel.boot();
    }
    if (!pluginControlServer) {
      try {
        const ctlOpts = {
          ...(process.env.SOLBRAID_PLUGIN_CTL_SOCKET ? {socketPath: process.env.SOLBRAID_PLUGIN_CTL_SOCKET} : {}),
          onMobileUse: (action, actionParams = {}) => askClient("mobileUse/execute", {action, params: actionParams}),
          onHostInstallApk: apkPath => askClient("selfBuild/installApk", {apkPath}),
          onHostStatus: () => askClient("selfBuild/hostStatus", {}),
          onHostRollbackRequest: () => askClient("selfBuild/requestRollback", {}),
        };
        pluginControlServer = await startPluginControlServer(pluginKernel, ctlOpts);
      } catch (ctlErr) {
        console.error("插件控制通道启动失败：", ctlErr?.message || ctlErr);
      }
    }
    initialized = true;
    return {version: "guap-1.0", protocols: ["acp", "ndjson", "exec-stream"], pluginGeneration: pluginKernel.generationId};
  }
  if (!initialized) throw new Error("Agent Hub 尚未初始化");

  switch (method) {
    case "hub/configure": {
      if (params.gateway) {
        gatewayConfig = {port: Number(params.gateway.port) || 0, token: String(params.gateway.token || "")};
      }
      if (params.network) {
        await updateNetworkConfig(params.network);
      }
      if (Array.isArray(params.manifests)) {
        if (params.manifests.some(manifest => manifest?.id !== "antigravity")) throw new Error("仅支持内置的 Antigravity");
        manifests.clear();
        for (const m of params.manifests) {
          manifests.set(m.id, {...m, protocol: "acp", preinstalled: true,
            install: {...m.install, strategy: "builtin", binaryPath: antigravityInstallation.binary},
            launch: {...m.launch, command: antigravityInstallation.binary, args: ["acp"],
              authLoginCommand: "'" + antigravityInstallation.binary.replace(/'/g, "'\\''") + "' acp --login"}});
        }
      }
      return {ok: true, count: manifests.size};
    }

    case "network/configure": {
      await updateNetworkConfig(params.network || params);
      wakeNetworkWaiters();
      return {ok: true};
    }

    case "network/poke": {
      return {ok: true, woken: wakeNetworkWaiters()};
    }

    case "network/test": {
      const config = params.network ? normalizeDownloadNetwork(params.network) : networkConfig;
      const npmBin = await resolveBinary({launch:{command:"npm"}, install:{}});
      if (!npmBin) throw new Error("缺少 npm，无法检查下载来源");
      const {stdout} = await execFileAsync(npmBin, ["config", "get", "registry"], {timeout:5_000, env:baseEnv(null, null, config)});
      const registry = normalizeDownloadNetwork({npmRegistry:stdout.trim()}).npmRegistry;
      if (!registry) throw new Error("npm 未返回下载来源");
      const npmUrl = registry + "/-/ping";
      const ghUrl = githubDownloadUrl("https://github.com/", config);
      const [npmRes, ghRes] = await Promise.all([
        probeEndpointWithCurl(npmUrl, config, false),
        probeEndpointWithCurl(ghUrl, config, true),
      ]);
      return {
        ok: npmRes.ok && ghRes.ok,
        npm: npmRes,
        github: ghRes,
      };
    }

    case "agents/cancelInstall": {
      const targetId = params.agentId;
      const operation = packageOperation;
      if (!operation || operation.method !== "agents/install" || operation.agentId !== targetId || operation.operationId !== params.operationId ||
          ["completed", "failed", "cancelled"].includes(installationStates.get(targetId)?.phase)) {
        return {ok: true, cancelled: false};
      }
      operation.cancelled = true;
      const child = operation.child;
      if (child) {
        signalInstaller(child, "SIGTERM");
        const timer = setTimeout(() => {
          if (operation.child === child) signalInstaller(child, "SIGKILL");
        }, 2000);
        child.once("close", () => clearTimeout(timer));
      }
      updateInstallProgress(targetId, "cancelling", installationStates.get(targetId)?.step ?? 1);
      return {ok: true, cancelled: true, agentId: targetId, operationId: operation.operationId};
    }

    case "agents/status": {
      const targetId = params.agentId;
      const list = targetId ? (manifests.has(targetId) ? [manifests.get(targetId)] : []) : [...manifests.values()];
      const statuses = await Promise.all(list.map(m => probeAgent(m)));
      return {statuses};
    }

    case "agents/install":
    case "agents/uninstall":
      throw new Error("CLI 已内置，请使用检查更新或恢复内置版本");

    case "cli/status": return cliUpdater.status();
    case "cli/check": return cliUpdater.check(params);
    case "cli/install": {
      const res = await cliUpdater.install(params);
      if (params?.agentId === "antigravity") {
        const updated = await hotReloadAntigravityCli();
        return {update: updated};
      }
      return res;
    }
    case "cli/cancel": return cliUpdater.cancel(params);
    case "cli/restore": {
      const res = await cliUpdater.restore(params);
      if (params?.agentId === "antigravity") {
        const updated = await hotReloadAntigravityCli();
        return {update: updated};
      }
      return res;
    }
    case "cli/markApplied": {
      const id = String(params?.agentId || "").trim();
      CLI_DIRECTORIES = await cliPathDirectories();
      const updated = await cliUpdater.markApplied(id);
      return {update: updated};
    }

    case "agents/stop": {
      stopDriver(params.agentId);
      return {ok: true};
    }

    case "agents/authStart": {
      const m = params.manifest || manifests.get(params.agentId) || {id: params.agentId, name: params.agentId, launch: {}};
      return await startAuth(m, params.mode || "oauth");
    }

    case "agents/authInput": {
      return writeAuthInput(params.agentId, params.text);
    }

    case "agents/authStatus": {
      return await queryAuthStatus(params.agentId);
    }

    case "agents/authLogout": {
      return await logoutAuth(params.agentId);
    }

    case "agents/authCancel": {
      stopAuth(params.agentId);
      return {ok: true};
    }

    case "sessions/list": {
      const agentId = params.agentId;
      if (agentId) validateAgentId(agentId);
      const pluginDriverIds = [...pluginKernel.drivers.values()].filter(d => d.executable).map(d => d.id);
      const diskAgentIds = [];
      if (!agentId) {
        try {
          const entries = await fs.readdir(SESSIONS_ROOT, {withFileTypes: true});
          for (const e of entries) {
            if (e.isDirectory() && SAFE_AGENT_ID_RE.test(e.name) && !e.name.includes("..")) {
              diskAgentIds.push(e.name);
            }
          }
        } catch {}
      }
      const allIds = [...new Set([...manifests.keys(), ...pluginDriverIds, ...diskAgentIds])];
      const includeArchived = params.includeArchived === true;
      const all = (agentId
        ? await listStoredSessions(agentId)
        : (await Promise.all(allIds.map(id => listStoredSessions(id)))).flat()
      ).filter(s => includeArchived || !s.archived);
      return {
        sessions: all.map(s => {
          const resolved = resolveManifestOrNull(s.agentId);
          return {
            agentId: s.agentId,
            driverName: s.driverName || resolved?.name || null,
            driverAvailable: Boolean(resolved),
            id: s.id,
            remoteSessionId: s.remoteSessionId || null,
            archived: Boolean(s.archived),
            title: s.title || "新会话",
            preview: s.preview || "",
            cwd: s.cwd || path.join(HOME, "workspace"),
            updatedAt: s.updatedAt || Date.now(),
            model: s.model || null,
            mode: s.mode || null,
            providerId: s.providerId || null,
            running: activeTurns.has(`${s.agentId}:${s.id}`),
          };
        }),
      };
    }

    case "sessions/create": {
      const m = requireManifest(params.agentId);
      const requestedCwd = params.cwd || path.join(HOME, "workspace");
      if (typeof requestedCwd !== "string" || !path.isAbsolute(requestedCwd)) throw new Error("工作目录必须是绝对路径");
      const cwd = await fs.realpath(requestedCwd);
      if (!(await fs.stat(cwd)).isDirectory()) throw new Error("工作目录不存在");
      const id = randomUUID();
      let remoteSessionId = null;
      let availableModels = [];
      let availableModes = [];
      let configOptions = [];
      let currentModel = params.model || null;
      let defaultModel = null;
      let currentMode = null;
      const key = `${m.id}:${id}`;
      const requestedMode = typeof params.mode === "string" && params.mode.trim() ? params.mode.trim() : null;
      const requestedEffort = typeof params.effort === "string" && params.effort.trim() ? params.effort.trim() : null;
      const parsedParamModel = (m.id === "antigravity" && params.model) ? splitAgyModelEffort(params.model) : null;
      const explicitEffort = requestedEffort || parsedParamModel?.effort || null;
      const launchOpts = {
        bypassPermissions: requestedMode === "bypass" || params.bypassPermissions === true,
        sandbox: params.sandbox === true,
        agentName: typeof params.agentName === "string" ? params.agentName.trim() : "",
        transport: typeof params.transport === "string" ? params.transport.trim() : "acp",
      };
      const useAgyStreamJson = m.id === "antigravity" && (launchOpts.transport === "stream-json" || Boolean(params.provider?.id));
      if (typeof params.apiKey === "string" && params.apiKey.trim()) sessionApiKeys.set(key, params.apiKey.trim());
      try {
        if (m.protocol === "acp" && !useAgyStreamJson) {
          const drv = await getDriver(m, cwd, params.provider || null, id, params.apiKey || null, launchOpts);
          const mcpServers = await mcpServersForSession(m.id, id, cwd, params.allowDelegation === true);
          const created = await drv.newSession(cwd, mcpServers);
          if (!created?.sessionId) throw new Error("Agent 未返回有效会话标识");
          remoteSessionId = created.sessionId;
          drv._attachedSessionId = remoteSessionId;
          const createdModelCount = Array.isArray(created?.models?.availableModels) ? created.models.availableModels.length : 0;
          const createdModelCfgCount = Array.isArray(created?.configOptions)
            ? (created.configOptions.find(opt => opt?.id === "model")?.options?.length || 0)
            : 0;
          drv._modelCatalogLoaded = createdModelCount > 0 || createdModelCfgCount > 0;
          if (createdModelCount > 0) {
            if (m.id === "antigravity") {
              cachedAgyRawModels = created.models.availableModels;
              availableModels = groupAgyModelsList(created.models.availableModels);
            } else {
              availableModels = created.models.availableModels;
            }
          }
          const rawCurrentModel = created?.models?.currentModelId || null;
          if (Array.isArray(created?.modes?.availableModes)) availableModes = created.modes.availableModes;
          if (created?.modes?.currentModeId) currentMode = created.modes.currentModeId;
          if (Array.isArray(created?.configOptions)) configOptions = created.configOptions;
          let remoteCurrentEffort = Array.isArray(configOptions)
            ? (configOptions.find(opt => opt?.id === "thought_level")?.currentValue || null)
            : null;
          if (m.id === "antigravity" && rawCurrentModel) {
            const split = splitAgyModelEffort(rawCurrentModel);
            currentModel = split.baseId;
            if (!remoteCurrentEffort && split.effort) remoteCurrentEffort = split.effort;
            if (!explicitEffort && split.effort) params.effort = split.effort;
          } else {
            currentModel = rawCurrentModel;
          }
          defaultModel = currentModel;
          const desiredEffort = explicitEffort || (typeof params.effort === "string" ? params.effort : null);
          let activeRemoteModel = rawCurrentModel;
          let modelAppliedEffort = false;
          if (params.model) {
            const concrete = m.id === "antigravity" ? resolveAgyConcreteModel(params.model, desiredEffort, null, availableModels) : params.model;
            const targetModel = concrete || params.model;
            if (targetModel !== activeRemoteModel) {
              const setOk = await drv.setModel(remoteSessionId, targetModel).then(() => true, () => false);
              if (setOk) {
                activeRemoteModel = targetModel;
                if (m.id === "antigravity") {
                  const targetSplit = splitAgyModelEffort(targetModel);
                  configOptions = updateConfigOptionValues(configOptions, {
                    model: targetSplit.baseId || parsedParamModel.baseId,
                    ...(targetSplit.effort ? {thought_level: targetSplit.effort} : {}),
                  });
                  modelAppliedEffort = agyConcreteModelCoversEffort(m.id, targetModel, desiredEffort);
                } else {
                  configOptions = updateConfigOptionValues(configOptions, {model: targetModel});
                }
              } else {
                const fallbackVal = m.id === "antigravity"
                  ? (splitAgyModelEffort(targetModel).baseId || parsedParamModel.baseId)
                  : targetModel;
                const cfgRes = await drv.setConfigOption(remoteSessionId, "model", fallbackVal).catch(() => null);
                if (Array.isArray(cfgRes?.configOptions)) {
                  configOptions = cfgRes.configOptions;
                  activeRemoteModel = fallbackVal;
                } else if (cfgRes) {
                  configOptions = updateConfigOptionValues(configOptions, {model: fallbackVal});
                  activeRemoteModel = fallbackVal;
                }
              }
            }
            currentModel = m.id === "antigravity" ? parsedParamModel.baseId : params.model;
          }
          if (requestedMode && requestedMode !== "bypass" && requestedMode !== currentMode) {
            const modeOk = await drv.setMode(remoteSessionId, requestedMode).then(() => true, () => false);
            if (modeOk) configOptions = updateConfigOptionValues(configOptions, {mode: requestedMode});
            currentMode = requestedMode;
          } else if (requestedMode === "bypass") {
            currentMode = "bypass";
          }
          const syncedEffort = Array.isArray(configOptions)
            ? (configOptions.find(opt => opt?.id === "thought_level")?.currentValue || remoteCurrentEffort)
            : remoteCurrentEffort;
          if (desiredEffort && !modelAppliedEffort && desiredEffort !== syncedEffort) {
            const cfgRes = await drv.setConfigOption(remoteSessionId, "thought_level", desiredEffort).catch(() => null);
            if (Array.isArray(cfgRes?.configOptions)) configOptions = cfgRes.configOptions;
            else if (cfgRes) configOptions = updateConfigOptionValues(configOptions, {thought_level: desiredEffort});
          }
          // 若新建会话时因断网导致 Antigravity 未拉取到模型目录，立即回收该 0 模型进程，避免首条消息触发 unknown model key
          if (m.id === "antigravity" && !params.provider?.id && !drv._modelCatalogLoaded) {
            stopSessionDriver(key);
          }
        } else if (m.protocol === "plugin-driver") {
          const rawModels = await pluginKernel.listPluginDriverModels(m.driverId || m.id).catch(() => []);
          availableModels = (Array.isArray(rawModels) ? rawModels : []).map(item =>
            typeof item === "string" ? {modelId: item, name: item} : {modelId: String(item.id || item.modelId || ""), name: String(item.label || item.name || item.id || "")},
          ).filter(item => item.modelId);
          currentModel = params.model || availableModels[0]?.modelId || null;
          defaultModel = currentModel;
          currentMode = requestedMode || "default";
        } else if (useAgyStreamJson) {
          currentMode = requestedMode || "default";
          if (params.model) currentModel = splitAgyModelEffort(params.model).baseId;
        }
        const record = {
          agentId: m.id,
          driverName: m.name || m.id,
          id,
          remoteSessionId,
          title: "新会话",
          preview: "",
          cwd,
          updatedAt: Date.now(),
          providerId: params.provider?.id || null,
          allowDelegation: params.allowDelegation === true,
          model: currentModel,
          defaultModel,
          mode: currentMode,
          effort: explicitEffort || (typeof params.effort === "string" ? params.effort : null),
          launchOpts,
          availableModels,
          availableModes,
          configOptions,
          messages: [],
        };
        sessions.set(key, record);
        await saveSession(record);
        return {session: record};
      } catch (error) {
        stopSessionDriver(key);
        sessionApiKeys.delete(key);
        sessions.delete(key);
        throw error;
      }
    }

    case "sessions/load": {
      const agentId = validateAgentId(params.agentId);
      const m = resolveManifestOrNull(agentId);
      const record = await loadStoredSession(agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      let brainPlans = [];
      if (agentId === "antigravity" && record.remoteSessionId) {
        const existingPaths = new Set(
          (record.messages || []).filter(msg => msg.role === "plan" && msg.filePath).map(msg => msg.filePath),
        );
        const found = await collectSessionBrainPlans(record.remoteSessionId).catch(() => []);
        brainPlans = found.filter(p => !existingPaths.has(p.path));
      }
      return {
        session: {
          ...record,
          driverName: record.driverName || m?.name || null,
          driverAvailable: Boolean(m),
          running: activeTurns.has(`${agentId}:${record.id}`),
          brainPlans,
        },
      };
    }

    case "sessions/prompt": {
      const m = requireManifest(params.agentId, "继续对话");
      const record = await loadStoredSession(m.id, params.sessionId);
      if (!record) throw new Error("会话不存在");
      if (typeof params.text !== "string") throw new Error("消息内容无效");
      const text = params.text;
      const images = Array.isArray(params.images) ? params.images : [];
      if (!text.trim() && !images.length) throw new Error("消息不能为空");
      if (images.length && m.protocol !== "acp") throw new Error("此 CLI 驱动不支持图片输入");
      const sessKey = `${m.id}:${record.id}`;
      if (typeof params.apiKey === "string" && params.apiKey.trim()) sessionApiKeys.set(sessKey, params.apiKey.trim());
      let drv = null;
      let isAgyStreamJson = m.id === "antigravity" && (record.launchOpts?.transport === "stream-json" || Boolean(params.provider?.id));
      const now = Date.now();
      const userMsgId = (typeof params.clientMessageId === "string" && params.clientMessageId.trim())
        ? params.clientMessageId.trim().slice(0, 128)
        : `user-${now}`;
      record.messages.push({id: userMsgId, role: "user", text, imageCount: images.length, ts: now});
      if (record.title === "新会话" && text.trim()) {
        record.title = text.trim().split(/\r?\n/)[0].slice(0, 60);
      }
      record.preview = text.trim().slice(0, 120);
      record.updatedAt = now;
      await saveSession(record);
      const key = `${m.id}:${record.id}`;
      const brainBaseline = m.id === "antigravity" && record.remoteSessionId
        ? await snapshotBrainArtifacts(record.remoteSessionId).catch(() => new Map())
        : null;
      const turn = {
        text: "",
        thoughts: [],
        currentThought: null,
        timelineOrder: [],
        toolMap: new Map(),
        metaToolIds: new Set(),
        planMap: new Map(),
        planSteps: null,
        planExplanation: null,
        focusText: null,
        brainBaseline,
        _scanningBrain: false,
        usageStart: record.lastContextUsed ?? null,
        initialContextUsed: Math.max(0, Number(record.lastContextUsed || 0)),
        usageEnd: null,
        contextWindow: record.lastContextWindow ?? null,
        firstTokenAt: 0,
        lastTokenAt: 0,
        activeTools: new Set(),
        toolActiveSince: 0,
        toolElapsedMs: 0,
        llmSteps: [],
        stepStartedAt: now,
        stepFirstTokenAt: 0,
        stepLastTokenAt: 0,
        stepToolElapsedMs: 0,
        stepOutChars: 0,
        stepThoughtChars: 0,
        awaitingNextStepStart: false,
        cancelled: false,
        abortRetry: null,
        serverAccepted: false,
      };
      if (m.id === "antigravity") {
        turn.scanBrainArtifacts = async () => {
          if (turn._scanningBrain || !record.remoteSessionId) return;
          turn._scanningBrain = true;
          try {
            if (!turn.brainBaseline) turn.brainBaseline = new Map();
            const fresh = await collectSessionBrainPlans(record.remoteSessionId, turn.brainBaseline);
            for (const plan of fresh) {
              upsertTurnPlan(turn, {
                id: plan.id,
                title: plan.title,
                text: plan.markdown,
                filePath: plan.path,
                status: plan.requestFeedback ? "running" : "completed",
                requestFeedback: plan.requestFeedback,
                summary: plan.summary,
                artifactType: plan.artifactType,
              });
              notify("agent/event", {
                agentId: m.id,
                sessionId: record.id,
                kind: "acp-update",
                update: {
                  sessionUpdate: "plan",
                  id: plan.id,
                  path: plan.path,
                  title: plan.title,
                  markdown: plan.markdown,
                  requestFeedback: plan.requestFeedback,
                  summary: plan.summary,
                  artifactType: plan.artifactType,
                },
              });
            }
          } catch {}
          finally { turn._scanningBrain = false; }
        };
      }
      activeTurns.set(key, turn);
      notify("sessions/started", {agentId: m.id, sessionId: record.id});
      pluginKernel.emitObserve("session.started", {agentId: m.id, sessionId: record.id, cwd: record.cwd});
      let replyText = "";
      let stopReason = "end_turn";
      let replySaved = false;
      let exactUsage = null;
      let driverContextUsage = null;
      let bridgedDuringTurn = false;
      const turnStartMs = Date.now();
      try {
        let promptTextForDriver = text;
        const consumedContextBridge = Boolean(record.pendingContextBridge);
        bridgedDuringTurn = consumedContextBridge;
        if (consumedContextBridge) {
          promptTextForDriver = buildContextBridgedPrompt(record, text, userMsgId);
          record.pendingContextBridge = false;
        }
        const beforeHook = await pluginKernel.runBeforePromptHooks({
          agentId: m.id,
          sessionId: record.id,
          cwd: record.cwd,
          text: promptTextForDriver,
        });
        promptTextForDriver = beforeHook.text;
        for (const notice of beforeHook.notices) {
          notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "notice", text: `[插件:${notice.pluginId}] ${notice.message}`, error: false});
        }
        if (m.protocol === "plugin-driver") {
          const driverId = m.driverId || m.id;
          const driverAbort = new AbortController();
          pluginDriverAborts.set(key, driverAbort);
          let res;
          try {
            res = await pluginKernel.invokePluginDriver({
              driverId,
              sessionId: record.id,
              prompt: promptTextForDriver,
              cwd: record.cwd,
              model: record.model,
              history: (record.messages || []).slice(-16),
              signal: driverAbort.signal,
              onEvent: ev => {
                if (driverAbort.signal.aborted) return;
                if (ev?.kind === "chunk" && ev.text) {
                  turn.text += ev.text;
                  if (!turn.firstTokenAt) turn.firstTokenAt = Date.now();
                  turn.lastTokenAt = Date.now();
                  notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "acp-update", update: {sessionUpdate: "agent_message_chunk", content: {type: "text", text: ev.text}}});
                } else if (ev?.kind === "thought" && ev.text) {
                  if (!turn.currentThought) {
                    turn.currentThought = {text: "", startedAtMs: Date.now(), completedAtMs: 0};
                    turn.thoughts.push(turn.currentThought);
                    turn.timelineOrder.push({type: "thought", ref: turn.currentThought});
                  }
                  turn.currentThought.text += ev.text;
                  notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "acp-update", update: {sessionUpdate: "agent_thought_chunk", content: {type: "text", text: ev.text}}});
                } else if (ev?.kind === "tool" && ev.tool) {
                  const tid = String(ev.tool.toolCallId || ev.tool.id || `tool-${Date.now()}`);
                  const rec = {
                    id: tid,
                    name: String(ev.tool.title || ev.tool.name || "插件工具"),
                    kind: String(ev.tool.kind || "other"),
                    command: ev.tool.command ? String(ev.tool.command) : undefined,
                    cwd: record.cwd,
                    input: typeof ev.tool.rawInput === "string" ? ev.tool.rawInput : JSON.stringify(ev.tool.rawInput ?? ""),
                    result: typeof ev.tool.rawOutput === "string" ? ev.tool.rawOutput : "",
                    status: String(ev.tool.status || "completed"),
                    ts: Date.now(),
                  };
                  turn.toolMap.set(tid, rec);
                  turn.timelineOrder.push({type: "tool", ref: rec});
                  notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "acp-update", update: {sessionUpdate: "tool_call", ...ev.tool}});
                } else if (ev?.kind === "plan" && ev.plan) {
                  if (typeof ev.plan.markdown === "string" || typeof ev.plan.text === "string") {
                    upsertTurnPlan(turn, {
                      id: String(ev.plan.id || `plan-${Date.now()}`),
                      title: String(ev.plan.title || "执行计划"),
                      text: String(ev.plan.markdown || ev.plan.text || ""),
                      status: String(ev.plan.status || "completed"),
                      requestFeedback: Boolean(ev.plan.requestFeedback),
                    });
                  }
                  notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "acp-update", update: {sessionUpdate: "plan", ...ev.plan}});
                }
              },
            });
          } finally {
            if (pluginDriverAborts.get(key) === driverAbort) pluginDriverAborts.delete(key);
          }
          replyText = res.text || turn.text || "";
          stopReason = res.stopReason || "end_turn";
          exactUsage = res.usage || null;
        } else if (isAgyStreamJson || m.protocol === "acp") {
          const maxRetries = 5;
          let activePromptText = promptTextForDriver;
          let includeImages = true;
          let lastAttemptRemoteId = record.remoteSessionId;
          let lastAttemptHadAccepted = false;
          for (let attempt = 0; attempt <= maxRetries; attempt++) {
            let internalCircuitError = null;
            let rejectInternalCircuit = null;
            const circuitPromise = new Promise((_, reject) => { rejectInternalCircuit = reject; });
            circuitPromise.catch(() => {});
            let logMonitor = {stop() {}};
            try {
              if (!drv || drv.closed) {
                drv = await getDriver(m, record.cwd, params.provider || null, record.id, params.apiKey || null, record.launchOpts || null);
                isAgyStreamJson = drv instanceof AgyStreamJsonDriver;
              }
              if (m.protocol === "acp" && !isAgyStreamJson && drv) {
                const prevRemoteId = record.remoteSessionId;
                await attachAcpSession(drv, record, {requireModelCatalog: m.id === "antigravity" && !params.provider?.id});
                if (m.id === "antigravity" && record.remoteSessionId && (!turn.brainBaseline || record.remoteSessionId !== prevRemoteId)) {
                  turn.brainBaseline = await snapshotBrainArtifacts(record.remoteSessionId).catch(() => new Map());
                }
                if (record.remoteSessionId && record.remoteSessionId !== prevRemoteId) {
                  await saveSession(record).catch(() => {});
                }
              }
              if (attempt > 0) {
                notify("agent/event", {
                  agentId: m.id,
                  sessionId: record.id,
                  kind: "acp-update",
                  update: {sessionUpdate: "focus_update", focus: `连接已重建，正在继续生成（第 ${attempt}/${maxRetries} 次重试）…`},
                });
                if (!isAgyStreamJson && record.remoteSessionId && record.remoteSessionId === lastAttemptRemoteId && lastAttemptHadAccepted) {
                  activePromptText = "上一轮请求因网络波动中断，请直接从中断处继续完成上一条用户请求，无需重复已完成步骤。";
                  includeImages = false;
                } else if (record.pendingContextBridge) {
                  bridgedDuringTurn = true;
                  activePromptText = buildContextBridgedPrompt(record, text, userMsgId);
                  record.pendingContextBridge = false;
                  includeImages = true;
                } else {
                  activePromptText = promptTextForDriver;
                  includeImages = true;
                }
              } else if (record.pendingContextBridge) {
                bridgedDuringTurn = true;
                activePromptText = buildContextBridgedPrompt(record, text, userMsgId);
                record.pendingContextBridge = false;
                includeImages = true;
              }
              lastAttemptRemoteId = record.remoteSessionId;
              turn.serverAccepted = false;
              logMonitor = (!isAgyStreamJson && m.id === "antigravity" && drv?._agyLogFile)
                ? startAgyLogMonitor(drv._agyLogFile, info => {
                  if (turn.cancelled) return;
                  const reasonLabel = formatAcpNetworkReason(info.reason);
                  const focusMsg = `网络波动（${reasonLabel}），Antigravity 正在重试（第 ${info.attempt} 次）…`;
                  if (turn.currentThought && !turn.currentThought.completedAtMs) {
                    turn.currentThought.completedAtMs = Date.now();
                    turn.currentThought = null;
                  }
                  notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "thought-reset", focus: focusMsg});
                  // 若底层连续失败 3 次（或后台网络受限 EPERM 连续 2 次），熔断挂起重试并转入网络恢复等待，
                  // 避免锁屏期间死等 10+ 分钟，或离线冷启导致 0 模型目录触发 unknown model key 崩溃
                  if (!internalCircuitError && (info.attempt >= 3 || (info.isSocketPermissionError && info.attempt >= 2))) {
                    internalCircuitError = new Error(info.reason || "网络连续重试失败");
                    internalCircuitError.code = "ACP_INTERNAL_RETRY_LOOP";
                    internalCircuitError.retryable = true;
                    internalCircuitError.needsNetworkWait = Boolean(info.isSocketPermissionError);
                    drv?.cancel?.(record.remoteSessionId).catch(() => {});
                    rejectInternalCircuit?.(internalCircuitError);
                  }
                })
                : {stop() {}};
              if (isAgyStreamJson) {
                const res = await drv.prompt(record.id, activePromptText, {
                  cwd: record.cwd,
                  model: record.model,
                  effort: record.effort,
                  mode: record.mode,
                  remoteSessionId: record.remoteSessionId,
                  availableModels: record.availableModels,
                });
                replyText = res.text || turn.text || "";
                stopReason = res.stopReason || "end_turn";
                if (res.remoteSessionId) record.remoteSessionId = res.remoteSessionId;
                exactUsage = res.usage || null;
                driverContextUsage = res.contextUsage || null;
              } else {
                const blocks = [];
                if (activePromptText) blocks.push({type: "text", text: activePromptText});
                if (activePromptText && drv.agentCapabilities?.promptCapabilities?.embeddedContext === true) {
                  const embedded = await buildAcpEmbeddedContextBlocks(activePromptText, record.cwd, {allowedRoot: HOME}).catch(() => []);
                  if (embedded.length) blocks.push(...embedded);
                }
                if (includeImages) {
                  for (const img of images) {
                    if (img?.base64) blocks.push({type: "image", mimeType: img.mimeType || "image/jpeg", data: img.base64});
                  }
                }
                const res = await Promise.race([
                  drv.prompt(record.remoteSessionId, blocks, {stallTimeoutMs: 120_000}),
                  circuitPromise,
                ]);
                stopReason = res?.stopReason || "end_turn";
                replyText = turn.text;
                exactUsage = res?.usage || null;
              }
              logMonitor.stop();
              if (turn.cancelled || stopReason === "cancelled") {
                stopReason = "cancelled";
                break;
              }
              const softErr = isSoftNetworkReply(replyText);
              const silentEmpty = stopReason === "end_turn" && !replyText.trim() && turn.toolMap.size === 0 && turn.planMap.size === 0;
              if (softErr || silentEmpty) {
                const err = new Error(softErr ? replyText.trim() : "服务端未返回任何内容，连接可能已中断");
                err.retryable = true;
                err.isSoftNetwork = true;
                if (isAgyModelCatalogError(err.message)) {
                  err.code = "ACP_MODEL_CATALOG_UNAVAILABLE";
                  err.needsNetworkWait = true;
                }
                throw err;
              }
              break;
            } catch (attemptErr) {
              logMonitor.stop();
              if (turn.cancelled || stopReason === "cancelled") {
                stopReason = "cancelled";
                replyText = isSoftNetworkReply(turn.text) ? "" : turn.text;
                break;
              }
              const canRetry = attempt < maxRetries && isRetryableAcpError(attemptErr);
              if (!canRetry) {
                if (attemptErr?.isSoftNetwork || attemptErr?.code === "ACP_INTERNAL_RETRY_LOOP" || attemptErr?.code === "ACP_MODEL_CATALOG_UNAVAILABLE" || isAgyModelCatalogError(attemptErr?.message)) {
                  const reasonLabel = formatAcpNetworkReason(attemptErr.message);
                  throw new Error(`网络连接异常（${reasonLabel}），已自动重试 ${attempt} 次仍失败，请检查网络或代理后重试`);
                }
                throw attemptErr;
              }
              // 记录自动重试前失败的那次直连请求，避免用量中心漏掉重试消耗
              if (!params.provider?.id) {
                const retryFailMs = Math.max(1, Date.now() - (turn.stepStartedAt || turnStartMs));
                void usageLedger.recordUsageEvent({
                  agent: m.id,
                  providerId: `official_${m.id}`,
                  providerName: m.id === "antigravity" ? "Google 官方" : `${m.name} 官方`,
                  model: record.model || m.id,
                  ms: retryFailMs,
                  status: 502,
                  err: `自动重试前失败：${attemptErr.message || "网络异常"}`.slice(0, 300),
                  billingKind: "plan",
                  sessionId: record.id,
                  source: "direct",
                });
              }
              turn.stepStartedAt = Date.now();
              turn.stepFirstTokenAt = 0;
              turn.stepLastTokenAt = 0;
              turn.stepToolElapsedMs = 0;
              turn.stepOutChars = 0;
              turn.stepThoughtChars = 0;
              turn.awaitingNextStepStart = false;
              lastAttemptHadAccepted = Boolean(turn.serverAccepted);
              const hadTools = turn.toolMap.size > 0;
              if (turn.currentThought && !turn.currentThought.completedAtMs) {
                turn.currentThought.completedAtMs = Date.now();
                turn.currentThought = null;
              }
              turn.text = "";
              if (!hadTools) {
                turn.thoughts = [];
                turn.timelineOrder = turn.timelineOrder.filter(entry => entry.type !== "thought");
              }
              const needsNetworkWait = m.id === "antigravity" && !params.provider?.id && isSocketPermissionOrNetworkBlocked(attemptErr);
              const delayMs = computeRetryDelayMs(attempt);
              const reasonLabel = formatAcpNetworkReason(attemptErr.message);
              const delaySec = Math.max(1, Math.round(delayMs / 1000));
              const retryFocus = needsNetworkWait
                ? `${reasonLabel}，等待锁屏解除或网络恢复后自动重连（第 ${attempt + 1}/${maxRetries} 次）…`
                : `${reasonLabel}，${delaySec}秒后重建连接并重试（第 ${attempt + 1}/${maxRetries} 次）…`;
              notify("agent/event", {
                agentId: m.id,
                sessionId: record.id,
                kind: "turn-retry",
                attempt: attempt + 1,
                maxAttempts: maxRetries,
                delayMs: needsNetworkWait ? 0 : delayMs,
                reason: reasonLabel,
                focus: retryFocus,
                clearPartialThought: !hadTools,
                discardSoftReply: true,
              });
              // 主动停止失效进程以释放旧 Go HTTP/2 连接池与 0 模型缓存，并等待网络真正恢复后再冷启新进程
              stopSessionDriver(key);
              drv = null;
              if (needsNetworkWait) {
                const probeEnv = baseEnv(m, null, networkConfig, userGlobalEnv, null, record.id);
                const waitRes = await waitForNetworkRecovery(turn, {
                  agentId: m.id,
                  sessionId: record.id,
                  env: probeEnv,
                  initialReason: attemptErr?.message || reasonLabel,
                });
                if (waitRes.cancelled || turn.cancelled) {
                  stopReason = "cancelled";
                  replyText = "";
                  break;
                }
                if (!waitRes.recovered) {
                  throw new Error(`锁屏或后台网络受限超过等待上限（${reasonLabel}），请解锁屏幕或检查网络/代理后重试`);
                }
                const settleMs = Number(process.env.GALIAIS_NET_WAIT_POLL_MS) > 0 ? 50 : 350;
                const keepGoing = await sleepCancellable(turn, settleMs);
                if (!keepGoing || turn.cancelled) {
                  stopReason = "cancelled";
                  replyText = "";
                  break;
                }
              } else {
                const keepGoing = await sleepCancellable(turn, delayMs);
                if (!keepGoing || turn.cancelled) {
                  stopReason = "cancelled";
                  replyText = "";
                  break;
                }
              }
            }
          }
        } else {
          if (!drv || drv.closed) {
            drv = await getDriver(m, record.cwd, params.provider || null, record.id, params.apiKey || null, record.launchOpts || null);
          }
          let effectivePrompt = promptTextForDriver;
          if (record.messages.length <= 1) {
            const skillIndex = await extSync.buildSkillsPromptIndex(m.id, record.cwd).catch(() => "");
            if (skillIndex) effectivePrompt = `${skillIndex}\n\n${promptTextForDriver}`;
          }
          const res = await drv.prompt(record.id, effectivePrompt, {
            cwd: record.cwd,
            model: record.model,
            remoteSessionId: record.remoteSessionId,
          });
          replyText = res.text || "";
          stopReason = res.stopReason || "end_turn";
          if (res.remoteSessionId) record.remoteSessionId = res.remoteSessionId;
        }
        if (turn.currentThought && !turn.currentThought.completedAtMs) {
          turn.currentThought.completedAtMs = Date.now();
          turn.currentThought = null;
        }
        if (turn.usageEnd !== null) record.lastContextUsed = turn.usageEnd;
        const effectiveWindow = turn.contextWindow || driverContextUsage?.size || inferModelContextWindow(record.model, Boolean(params.provider?.id));
        if (effectiveWindow > 0) record.lastContextWindow = effectiveWindow;
        const effectiveContextUsed = turn.usageEnd ?? driverContextUsage?.used ?? null;
        const isCompactCommand = text.trim().toLowerCase().startsWith("/compact");
        // Antigravity 在自定义服务商或 Stream-JSON 模式下达到 85% 真实上下文窗口时自动触发 /compact，防止后续请求超窗；
        // 官方 agy acp 已有内置上下文管理，且 ACP session/prompt 会把 "/compact" 当普通文本处理，故仅限 Stream-JSON/自定义服务商。
        if (
          m.id === "antigravity" &&
          (isAgyStreamJson || Boolean(params.provider?.id)) &&
          stopReason === "end_turn" &&
          !isCompactCommand &&
          effectiveContextUsed !== null &&
          effectiveWindow > 0 &&
          effectiveContextUsed >= Math.floor(effectiveWindow * 0.85)
        ) {
          const pct = Math.min(100, Math.round((effectiveContextUsed / effectiveWindow) * 100));
          notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "notice", text: `上下文占用已达 ${pct}%，正在自动压缩历史上下文…`, error: false});
          try {
            if (isAgyStreamJson) {
              const cmpRes = await drv.prompt(record.id, "/compact", {
                cwd: record.cwd, model: record.model, effort: record.effort, mode: record.mode, remoteSessionId: record.remoteSessionId, availableModels: record.availableModels,
              });
              if (cmpRes?.remoteSessionId) record.remoteSessionId = cmpRes.remoteSessionId;
              if (cmpRes?.contextUsage?.used > 0) {
                turn.usageEnd = cmpRes.contextUsage.used;
                record.lastContextUsed = cmpRes.contextUsage.used;
              }
            } else if (m.protocol === "acp" && record.remoteSessionId) {
              await drv.prompt(record.remoteSessionId, [{type: "text", text: "/compact"}]);
              if (turn.usageEnd !== null) record.lastContextUsed = turn.usageEnd;
            }
            notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "notice", text: "上下文已自动压缩", error: false});
          } catch (cmpErr) {
            notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "notice", text: `自动压缩未完成：${cmpErr.message}`, error: false});
          }
        }
        if (typeof turn.scanBrainArtifacts === "function") {
          await turn.scanBrainArtifacts();
        }
        const finalContextUsage = (record.lastContextUsed != null || effectiveWindow > 0)
          ? {used: record.lastContextUsed ?? -1, size: effectiveWindow}
          : null;
        const doneAt = Date.now();
        let thoughtIdx = 0;
        for (const entry of turn.timelineOrder) {
          if (entry.type === "thought") {
            const th = entry.ref;
            const cleaned = String(th.text || "").trim();
            if (cleaned) {
              record.messages.push({
                id: `thought-${doneAt}-${thoughtIdx++}`,
                role: "thought",
                text: cleaned,
                startedAtMs: th.startedAtMs || turnStartMs,
                completedAtMs: th.completedAtMs || doneAt,
                ts: th.startedAtMs || doneAt,
              });
            }
          } else if (entry.type === "tool") {
            const t = entry.ref;
            record.messages.push({
              id: t.id,
              role: "tool",
              name: t.name,
              kind: t.kind || "",
              command: t.command,
              cwd: t.cwd,
              input: t.input || "",
              result: t.result || "",
              diffs: Array.isArray(t.diffs) && t.diffs.length ? t.diffs : undefined,
              exitCode: typeof t.exitCode === "number" ? t.exitCode : undefined,
              status: t.status === "running" ? "completed" : t.status,
              durationMs: t.durationMs,
              ts: t.ts || doneAt,
            });
          } else if (entry.type === "plan") {
            const p = entry.ref;
            if (p.text && p.text.trim()) {
              record.messages.push({
                id: p.id,
                role: "plan",
                title: p.title,
                text: p.text,
                filePath: p.filePath,
                status: p.status || "completed",
                requestFeedback: Boolean(p.requestFeedback),
                summary: p.summary || undefined,
                artifactType: p.artifactType || "plan",
                ts: p.ts || doneAt,
              });
            }
          } else if (entry.type === "receipt") {
            const r = entry.ref;
            record.messages.push({
              id: r.id,
              role: "receipt",
              kind: r.kind || "question",
              title: r.title || "交互记录",
              entries: Array.isArray(r.entries) ? r.entries : [],
              status: r.status || "completed",
              ts: r.ts || doneAt,
            });
          }
        }
        if (Array.isArray(turn.planSteps)) {
          record.plan = turn.planSteps;
          record.planExplanation = turn.planExplanation || record.planExplanation || null;
        }
        if (replyText) {
          record.messages.push({id: `agent-${doneAt}`, role: "agent", text: replyText, ts: doneAt});
          replySaved = true;
          record.preview = replyText.trim().slice(0, 120);
        }
        record.updatedAt = doneAt;
        await saveSession(record);
        const wallDurationMs = Math.max(0, doneAt - turnStartMs);
        if (turn.toolActiveSince > 0) {
          turn.toolElapsedMs += Math.max(0, doneAt - turn.toolActiveSince);
          turn.toolActiveSince = 0;
        }
        const apiDurationMs = Math.max(1, wallDurationMs - Math.min(wallDurationMs, turn.toolElapsedMs));
        const ttftMs = turn.firstTokenAt > turnStartMs ? Math.max(1, turn.firstTokenAt - turnStartMs) : 0;
        const rawGenMs = turn.firstTokenAt > 0 && turn.lastTokenAt > turn.firstTokenAt ? (turn.lastTokenAt - turn.firstTokenAt) : 0;
        const genMs = Math.max(0, rawGenMs - Math.min(rawGenMs, turn.toolElapsedMs));
        const hasExactUsage = Boolean(
          exactUsage && (
            exactUsage.input_tokens !== undefined || exactUsage.inputTokens !== undefined ||
            exactUsage.output_tokens !== undefined || exactUsage.outputTokens !== undefined
          )
        );
        const estIn = Math.max(1, Math.ceil(text.length / 3.2));
        const estOut = Math.max(1, Math.ceil(replyText.length / 3.2));
        const acpDelta = (turn.usageEnd !== null && turn.usageStart !== null)
          ? Math.max(0, turn.usageEnd - turn.usageStart)
          : (turn.usageEnd || 0);
        const rawInTok = exactUsage?.input_tokens ?? exactUsage?.inputTokens ?? (acpDelta > estOut ? acpDelta - estOut : Math.max(acpDelta, estIn));
        const outTok = exactUsage?.output_tokens ?? exactUsage?.outputTokens ?? estOut;
        const cacheRead = exactUsage?.cache_read_tokens ?? exactUsage?.cached_read_tokens ?? exactUsage?.cachedReadTokens ?? exactUsage?.cacheReadTokens ?? exactUsage?.cachedContentTokenCount ?? 0;
        const cacheWrite = exactUsage?.cache_write_tokens ?? exactUsage?.cached_write_tokens ?? exactUsage?.cachedWriteTokens ?? exactUsage?.cacheWriteTokens ?? 0;
        const reasoning = exactUsage?.thinking_tokens ?? exactUsage?.thoughtTokens ?? exactUsage?.reasoningTokens ?? exactUsage?.thoughtsTokenCount ?? 0;
        const rawTotal = exactUsage?.total_tokens ?? exactUsage?.totalTokens ?? exactUsage?.totalTokenCount ?? 0;
        // Antigravity (agy) 返回的 inputTokens 为未命中缓存的增量输入，需合并 cacheRead + cacheWrite 为统一总输入口径。
        const inTok = hasExactUsage
          ? resolveNormalizedTotalInput(rawInTok, outTok, cacheRead, cacheWrite, rawTotal, m.id === "antigravity", reasoning)
          : Math.max(rawInTok, cacheRead + cacheWrite);
        const normalizedUsage = {
          inputTokens: inTok,
          outputTokens: outTok,
          cachedReadTokens: cacheRead,
          cachedWriteTokens: cacheWrite,
          thoughtTokens: reasoning,
          totalTokens: rawTotal > 0 ? Math.max(rawTotal, inTok + outTok) : (inTok + outTok),
        };
        // 直连账号（未经过本地 provider-gateway）在此按观测到的逐次 LLM 步骤精确守恒拆分入账，避免多步回合被压成 1 条
        if (!params.provider?.id) {
          const isTurnCancelled = Boolean(turn.cancelled || stopReason === "cancelled");
          const stepRows = buildPerStepUsageRecords({
            steps: turn.llmSteps,
            inTok,
            outTok,
            cacheRead,
            cacheWrite,
            reasoning,
            usageStart: turn.initialContextUsed ?? 0,
            turnStartMs,
            doneAt,
            apiDurationMs,
            ttftMs,
            genMs,
            streamed: turn.firstTokenAt > 0,
            hasExactUsage,
            contextUsed: record.lastContextUsed || (inTok + outTok),
            contextWindow: effectiveWindow || 0,
          });
          for (let si = 0; si < stepRows.length; si++) {
            const row = stepRows[si];
            const isLastCancelled = isTurnCancelled && si === stepRows.length - 1;
            void usageLedger.recordUsageEvent({
              t: row.t,
              agent: m.id,
              providerId: `official_${m.id}`,
              providerName: m.id === "antigravity" ? "Google 官方" : `${m.name} 官方`,
              model: record.model || m.id,
              in: row.in,
              out: row.out,
              cacheRead: row.cacheRead,
              cacheWrite: row.cacheWrite,
              reasoning: row.reasoning,
              inNormalized: true,
              ms: row.ms,
              ttftMs: row.ttftMs,
              genMs: row.genMs,
              streamed: row.streamed,
              exact: row.exact,
              contextUsed: row.contextUsed,
              contextWindow: row.contextWindow,
              status: isLastCancelled ? 499 : 200,
              err: isLastCancelled ? "用户中断" : "",
              billingKind: "plan",
              sessionId: record.id,
              source: "direct",
            });
          }
        }
        const turnDiff = await devTools.captureGitDiff(record.cwd);
        notify("sessions/completed", {agentId: m.id, sessionId: record.id, stopReason, turnDiff, usage: normalizedUsage, contextUsage: finalContextUsage});
        pluginKernel.emitObserve("session.completed", {agentId: m.id, sessionId: record.id, stopReason, cwd: record.cwd});
        const afterHook = await pluginKernel.runAfterResponseHooks({
          agentId: m.id,
          sessionId: record.id,
          cwd: record.cwd,
          text: replyText,
          stopReason,
        });
        for (const notice of afterHook.notices) {
          notify("agent/event", {agentId: m.id, sessionId: record.id, kind: "notice", text: `[插件:${notice.pluginId}] ${notice.message}`, error: false});
        }
        return {stopReason, text: replyText, turnDiff, usage: normalizedUsage, contextUsage: finalContextUsage};
      } catch (error) {
        stopSessionDriver(key);
        if (bridgedDuringTurn && !replySaved) {
          record.remoteSessionId = null;
          record.pendingContextBridge = true;
        }
        if (!params.provider?.id) {
          // 中途异常或取消前已完成的模型步骤同样补记入账，杜绝长回合中途失败后已耗 Token 归零
          const completedSteps = Array.isArray(turn.llmSteps) ? turn.llmSteps.filter(s => s && s.used > 0) : [];
          for (const st of completedSteps) {
            const estOutStep = Math.max(0, Math.round((st.outChars || 0) / 3.2));
            const estInStep = Math.max(0, st.used - estOutStep);
            void usageLedger.recordUsageEvent({
              t: st.endedAt || Date.now(),
              agent: m.id,
              providerId: `official_${m.id}`,
              providerName: m.id === "antigravity" ? "Google 官方" : `${m.name} 官方`,
              model: record.model || m.id,
              in: estInStep,
              out: estOutStep,
              inNormalized: true,
              ms: Math.max(1, st.apiMs || 1),
              ttftMs: st.ttftMs || 0,
              genMs: st.genMs || 0,
              streamed: Boolean(st.streamed),
              exact: false,
              contextUsed: st.used,
              contextWindow: st.contextWindow || turn.contextWindow || 0,
              status: 200,
              billingKind: "plan",
              sessionId: record.id,
              source: "direct",
            });
          }
          const isCancelledErr = Boolean(turn.cancelled) || /用户中断|cancelled|canceled/i.test(String(error?.message || ""));
          void usageLedger.recordUsageEvent({
            agent: m.id,
            providerId: `official_${m.id}`,
            providerName: m.id === "antigravity" ? "Google 官方" : `${m.name} 官方`,
            model: record.model || m.id,
            ms: Math.max(1, Date.now() - (turn.stepStartedAt || turnStartMs)),
            status: isCancelledErr ? 499 : 500,
            err: isCancelledErr ? "用户中断" : (error.message || "Execution failed"),
            billingKind: "plan",
            sessionId: record.id,
            source: "direct",
          });
        }
        if (turn.text && !replySaved && !isSoftNetworkReply(turn.text)) {
          record.messages.push({id: `agent-${Date.now()}`, role: "agent", text: turn.text, ts: Date.now()});
        }
        record.messages.push({id: `error-${Date.now()}`, role: "notice", text: error.message, error: true, ts: Date.now()});
        record.updatedAt = Date.now();
        notify("sessions/failed", {agentId: m.id, sessionId: record.id, message: error.message});
        pluginKernel.emitObserve("session.failed", {agentId: m.id, sessionId: record.id, message: error.message});
        await saveSession(record);
        throw error;
      } finally { activeTurns.delete(key); }
    }

    case "sessions/cancel": {
      const agentId = validateAgentId(params.agentId);
      const m = resolveManifestOrNull(agentId);
      const record = await loadStoredSession(agentId, params.sessionId).catch(() => null);
      const key = `${agentId}:${params.sessionId}`;
      const activeTurn = activeTurns.get(key);
      if (activeTurn) {
        activeTurn.cancelled = true;
        try { activeTurn.abortRetry?.(); } catch {}
      }
      for (const waiter of pendingClientReqs.values()) {
        if (`${waiter.params.agentId}:${waiter.params.sessionId}` === key) waiter.reject(new Error("用户中断了会话"));
      }
      const pAbort = pluginDriverAborts.get(key);
      if (pAbort) {
        pluginDriverAborts.delete(key);
        try { pAbort.abort(new Error("用户中断了会话")); } catch {}
      }
      pluginKernel.cancelPluginDriver(m?.driverId || agentId, params.sessionId);
      const drv = drivers.get(key);
      if (drv) await drv.cancel(m?.protocol === "acp" ? (record?.remoteSessionId || params.sessionId) : params.sessionId).catch(() => {});
      return {ok: true};
    }

    case "sessions/revertBefore": {
      const agentId = validateAgentId(params.agentId);
      const record = await loadStoredSession(agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const key = `${agentId}:${record.id}`;
      if (activeTurns.has(key)) throw new Error("当前会话正在运行任务，请先停止后再编辑重发");
      const targetId = typeof params.messageId === "string" && params.messageId.trim() ? params.messageId.trim() : null;
      const userTurnIndex = Number.isInteger(params.userTurnIndex) && params.userTurnIndex >= 0 ? params.userTurnIndex : -1;
      if (!targetId && userTurnIndex < 0) throw new Error("缺少待回退的用户消息标识");
      const msgs = Array.isArray(record.messages) ? record.messages : [];
      let idx = targetId ? msgs.findIndex(msg => msg?.role === "user" && msg?.id === targetId) : -1;
      if (idx < 0 && userTurnIndex >= 0) {
        let seen = 0;
        for (let i = 0; i < msgs.length; i++) {
          if (msgs[i]?.role === "user") {
            if (seen === userTurnIndex) { idx = i; break; }
            seen++;
          }
        }
      }
      if (idx >= 0) {
        record.messages = msgs.slice(0, idx);
        stopSessionDriver(key);
        record.remoteSessionId = null;
        record.plan = [];
        record.planExplanation = null;
        record.lastContextUsed = null;
        const hasPrior = record.messages.some(msg => (msg?.role === "user" || msg?.role === "agent") && typeof msg.text === "string" && msg.text.trim());
        record.pendingContextBridge = hasPrior;
        const lastTextMsg = [...record.messages].reverse().find(msg => (msg?.role === "user" || msg?.role === "agent") && typeof msg.text === "string" && msg.text.trim());
        record.preview = lastTextMsg ? lastTextMsg.text.trim().slice(0, 120) : "";
        if (record.messages.length === 0) record.title = "新会话";
        record.updatedAt = Date.now();
        await saveSession(record);
      }
      return {
        ok: true,
        retainedCount: (record.messages || []).length,
        hasContextBridge: Boolean(record.pendingContextBridge),
      };
    }

    case "sessions/rename": {
      const record = await loadStoredSession(params.agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      record.title = String(params.title || "").trim().slice(0, 120) || record.title;
      record.updatedAt = Date.now();
      await saveSession(record);
      return {ok: true};
    }

    case "sessions/delete": {
      const agentId = validateAgentId(params.agentId);
      const key = `${agentId}:${params.sessionId}`;
      markSessionDeleted(key);
      for (const waiter of pendingClientReqs.values()) {
        if (`${waiter.params.agentId}:${waiter.params.sessionId}` === key) waiter.reject(new Error("会话已删除"));
      }
      const pAbort = pluginDriverAborts.get(key);
      if (pAbort) {
        pluginDriverAborts.delete(key);
        try { pAbort.abort(new Error("会话已删除")); } catch {}
      }
      pluginKernel.cancelPluginDriver(agentId, params.sessionId);
      const deletingTurn = activeTurns.get(key);
      if (deletingTurn) {
        deletingTurn.cancelled = true;
        try { deletingTurn.abortRetry?.(); } catch {}
      }
      activeTurns.delete(key);
      const existingRec = sessions.get(key) || await loadStoredSession(agentId, params.sessionId).catch(() => null);
      const existingDrv = drivers.get(key);
      if (existingDrv && existingRec?.remoteSessionId && typeof existingDrv.closeSession === "function") {
        await existingDrv.closeSession(existingRec.remoteSessionId).catch(() => {});
      }
      stopSessionDriver(key);
      sessionApiKeys.delete(key);
      await fs.rm(sessionFile(agentId, params.sessionId), {force: true});
      sessions.delete(key);
      if (agentId === "antigravity") {
        const safeSid = String(params.sessionId || "").replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 64);
        if (safeSid) {
          await fs.rm(path.join(HOME, ".gemini", "antigravity-cli", "solbraid-sessions", `settings-${safeSid}.json`), {force: true}).catch(() => {});
        }
      }
      // 若该已卸载插件驱动目录下不再有任何残留会话文件，顺带回收空目录
      await fs.rmdir(path.join(SESSIONS_ROOT, agentId)).catch(() => {});
      return {ok: true};
    }

    case "sessions/archive": {
      const agentId = validateAgentId(params.agentId);
      const record = await loadStoredSession(agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const key = `${agentId}:${record.id}`;
      if (params.archived !== false && activeTurns.has(key)) throw new Error("当前会话正在运行任务，请先停止后再归档");
      record.archived = params.archived !== false;
      record.updatedAt = Date.now();
      if (record.archived) stopSessionDriver(key);
      await saveSession(record);
      return {ok: true, archived: record.archived};
    }

    case "sessions/fork": {
      const agentId = validateAgentId(params.agentId);
      const source = await loadStoredSession(agentId, params.sessionId);
      if (!source) throw new Error("源会话不存在");
      const newId = randomUUID();
      const rawMsgs = Array.isArray(source.messages) ? source.messages : [];
      let clonedMsgs = rawMsgs.map(m => ({...m}));
      if (typeof params.upToMessageId === "string" && params.upToMessageId.trim()) {
        const idx = clonedMsgs.findIndex(m => m?.id === params.upToMessageId.trim());
        if (idx >= 0) clonedMsgs = clonedMsgs.slice(0, idx + 1);
      }
      const hasHistory = clonedMsgs.some(m => (m?.role === "user" || m?.role === "agent") && typeof m.text === "string" && m.text.trim());
      const cloned = {
        ...source,
        id: newId,
        remoteSessionId: null,
        archived: false,
        title: (typeof params.title === "string" && params.title.trim())
          ? params.title.trim().slice(0, 120)
          : `${source.title || "会话"}（分支）`.slice(0, 120),
        updatedAt: Date.now(),
        messages: clonedMsgs,
        pendingContextBridge: hasHistory,
        additionalDirectories: Array.isArray(source.additionalDirectories) ? [...source.additionalDirectories] : [],
      };
      sessions.set(`${agentId}:${newId}`, cloned);
      await saveSession(cloned);
      return {session: cloned};
    }

    case "sessions/addDir": {
      const agentId = validateAgentId(params.agentId);
      const record = await loadStoredSession(agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const key = `${agentId}:${record.id}`;
      if (activeTurns.has(key)) throw new Error("请先等待本轮结束后再附加工作区目录");
      const rawDir = String(params.dir || "").trim();
      if (!rawDir || !path.isAbsolute(rawDir)) throw new Error("附加目录必须是绝对路径");
      const resolved = await fs.realpath(rawDir);
      if (!(await fs.stat(resolved)).isDirectory()) throw new Error("附加目录不存在");
      const prev = Array.isArray(record.additionalDirectories) ? record.additionalDirectories : [];
      record.additionalDirectories = [...new Set([...prev, resolved])].slice(0, 16);
      record.updatedAt = Date.now();
      stopSessionDriver(key);
      await saveSession(record);
      return {ok: true, additionalDirectories: record.additionalDirectories};
    }

    case "sessions/syncAcp": {
      const agentId = validateAgentId(params.agentId || "antigravity");
      const m = requireManifest(agentId);
      if (m.protocol !== "acp") return {imported: 0, total: 0};
      const cwd = params.cwd || path.join(HOME, "workspace");
      const probeKey = `${agentId}:__sync_acp__`;
      const drv = await getDriver(m, cwd, null, "__sync_acp__", null, null);
      try {
        const listed = await drv.listSessions(params.cwd || null);
        const remoteList = Array.isArray(listed?.sessions) ? listed.sessions : (Array.isArray(listed?.data) ? listed.data : []);
        const existing = await listStoredSessions(agentId);
        const knownRemote = new Set(existing.map(s => s.remoteSessionId).filter(Boolean));
        let imported = 0;
        for (const item of remoteList) {
          const rId = String(item?.sessionId || item?.id || "").trim();
          if (!rId || knownRemote.has(rId)) continue;
          const localId = randomUUID();
          const rec = {
            agentId,
            driverName: m.name || agentId,
            id: localId,
            remoteSessionId: rId,
            title: String(item?.title || item?.name || "原生终端会话").slice(0, 100),
            preview: String(item?.preview || item?.summary || "").slice(0, 140),
            cwd: item?.cwd || cwd,
            updatedAt: Number(item?.updatedAt || item?.lastModified || Date.now()),
            model: item?.model || null,
            mode: item?.mode || null,
            providerId: null,
            messages: [],
            additionalDirectories: [],
          };
          sessions.set(`${agentId}:${localId}`, rec);
          await saveSession(rec);
          knownRemote.add(rId);
          imported++;
        }
        return {imported, total: remoteList.length};
      } finally {
        stopSessionDriver(probeKey);
      }
    }

    case "agy/changelog": {
      const bin = antigravityInstallation.binary;
      const env = baseEnv(manifests.get("antigravity") || null, null);
      const {stdout, stderr} = await execFileAsync(bin, ["changelog"], {env, timeout: 20_000, maxBuffer: 512 * 1024}).catch(e => ({stdout: e.stdout || "", stderr: e.stderr || e.message}));
      return {text: String(stdout || stderr || "").trim().slice(0, 64_000), version: antigravityInstallation.version};
    }

    case "agy/doctor": {
      const bin = antigravityInstallation.binary;
      const env = baseEnv(manifests.get("antigravity") || null, null);
      const runSub = async (args) => {
        try {
          const {stdout, stderr} = await execFileAsync(bin, args, {env, timeout: 15_000, maxBuffer: 256 * 1024});
          return {ok: true, out: String(stdout || stderr || "").trim()};
        } catch (err) {
          return {ok: false, out: String(err?.stdout || err?.stderr || err?.message || err).trim()};
        }
      };
      const [ver, auth, mcp, plugins, agents] = await Promise.all([
        runSub(["--version"]),
        queryAuthStatus("antigravity").catch(e => ({loggedIn: false, authMethod: "error", raw: e.message})),
        runSub(["mcp", "list"]),
        runSub(["plugin", "list", "--json"]),
        runSub(["agents"]),
      ]);
      return {
        ok: ver.ok,
        version: antigravityInstallation.version,
        binary: bin,
        auth,
        mcpOutput: mcp.out,
        pluginsOutput: plugins.out,
        agentsOutput: agents.out,
        versionOutput: ver.out,
      };
    }

    case "agy/authLogout": {
      const m = manifests.get("antigravity");
      if (m) {
        const probeKey = "antigravity:__logout__";
        try {
          const drv = await getDriver(m, path.join(HOME, "workspace"), null, "__logout__", null, null);
          await drv.logout().catch(() => {});
        } catch {} finally {
          stopSessionDriver(probeKey);
        }
      }
      return await logoutAuth("antigravity");
    }

    case "agy/authenticate": {
      const allowed = new Set(["oauth-personal", "oauth-business", "gemini-api-key", "agent-platform"]);
      const methodId = String(params?.methodId || "").trim();
      if (!allowed.has(methodId)) throw new Error(`不支持的 Antigravity 认证方式：${methodId}`);
      const m = requireManifest("antigravity");
      const probeKey = "antigravity:__auth_method__";
      const drv = await getDriver(m, path.join(HOME, "workspace"), null, "__auth_method__", params?.apiKey || null, null);
      try {
        await drv.start();
        const result = await drv.authenticate(methodId);
        return {ok: true, methodId, authMethods: drv.authMethods || [], result: result ?? {}};
      } finally {
        stopSessionDriver(probeKey);
      }
    }

    case "sessions/setModel": {
      const m = requireManifest(params.agentId, "切换模型");
      const record = await loadStoredSession(m.id, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const previousModel = record.model;
      const previousConfigOptions = record.configOptions;
      const rawSelected = params.model || (m.protocol === "acp" ? record.defaultModel : null);
      if (m.protocol === "acp" && !rawSelected) throw new Error("此会话未记录默认模型，请选择具体模型");
      const normalizedModel = m.id === "antigravity" && rawSelected ? splitAgyModelEffort(rawSelected).baseId : rawSelected;
      const concreteModel = m.id === "antigravity" && normalizedModel
        ? (resolveAgyConcreteModel(normalizedModel, record.effort, null, record.availableModels) || normalizedModel)
        : normalizedModel;
      let appliedLive = false;
      if (m.protocol === "acp" && record.remoteSessionId && concreteModel) {
        const drv = drivers.get(`${m.id}:${record.id}`);
        if (drv && !drv.closed && drv._attachedSessionId === record.remoteSessionId && typeof drv.setModel === "function") {
          const setOk = await drv.setModel(record.remoteSessionId, concreteModel).then(() => true, () => false);
          if (setOk) {
            appliedLive = true;
          } else if (typeof drv.setConfigOption === "function") {
            const fallbackVal = m.id === "antigravity" ? normalizedModel : concreteModel;
            const cfgRes = await drv.setConfigOption(record.remoteSessionId, "model", fallbackVal).catch(() => null);
            if (cfgRes) {
              if (Array.isArray(cfgRes.configOptions)) record.configOptions = cfgRes.configOptions;
              appliedLive = true;
            }
          }
        }
      }
      record.model = normalizedModel;
      if (Array.isArray(record.configOptions) && record.configOptions.length && normalizedModel) {
        const concreteSplit = m.id === "antigravity" ? splitAgyModelEffort(concreteModel) : {baseId: normalizedModel, effort: null};
        record.configOptions = updateConfigOptionValues(record.configOptions, {
          model: concreteSplit.baseId || normalizedModel,
          ...(concreteSplit.effort ? {thought_level: concreteSplit.effort} : {}),
        });
      }
      if (m.protocol === "acp" && normalizedModel && normalizedModel !== previousModel) {
        if (appliedLive) delete record._pendingModelSync;
        else record._pendingModelSync = true;
      }
      try { await saveSession(record); } catch (error) { record.model = previousModel; record.configOptions = previousConfigOptions; stopSessionDriver(`${m.id}:${record.id}`); throw error; }
      return {ok: true, model: record.model};
    }

    case "sessions/setMode": {
      const m = requireManifest(params.agentId, "切换会话模式");
      const record = await loadStoredSession(m.id, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const previousMode = record.mode;
      const previousConfigOptions = record.configOptions;
      const nextMode = params.mode || null;
      const wantBypass = nextMode === "bypass";
      const wasBypass = Boolean(record.launchOpts?.bypassPermissions);
      let appliedLive = false;
      if (m.protocol === "acp" && m.id === "antigravity" && wantBypass !== wasBypass) {
        record.launchOpts = {...(record.launchOpts || {}), bypassPermissions: wantBypass};
        stopSessionDriver(`${m.id}:${record.id}`);
      } else if (m.protocol === "acp" && record.remoteSessionId && nextMode && !wantBypass) {
        const drv = drivers.get(`${m.id}:${record.id}`);
        if (drv && !drv.closed && drv._attachedSessionId === record.remoteSessionId && typeof drv.setMode === "function") {
          await drv.setMode(record.remoteSessionId, nextMode);
          appliedLive = true;
        }
      }
      record.mode = nextMode;
      if (nextMode && !wantBypass && Array.isArray(record.configOptions) && record.configOptions.length) {
        record.configOptions = updateConfigOptionValues(record.configOptions, {mode: nextMode});
      }
      if (m.protocol === "acp" && nextMode && !wantBypass && nextMode !== previousMode) {
        if (appliedLive) delete record._pendingModeSync;
        else record._pendingModeSync = true;
      } else if (wantBypass) {
        delete record._pendingModeSync;
      }
      try { await saveSession(record); } catch (error) { record.mode = previousMode; record.configOptions = previousConfigOptions; stopSessionDriver(`${m.id}:${record.id}`); throw error; }
      return {ok: true};
    }

    case "sessions/setConfigOption": {
      const m = requireManifest(params.agentId, "更新配置项");
      const record = await loadStoredSession(m.id, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const configId = String(params.configId || "").trim();
      const value = String(params.value || "").trim();
      if (!configId || !value) throw new Error("配置参数不能为空");
      let updatedOptions = record.configOptions || [];
      let appliedLive = false;
      if (m.protocol === "acp" && record.remoteSessionId) {
        const drv = drivers.get(`${m.id}:${record.id}`);
        if (drv && !drv.closed && drv._attachedSessionId === record.remoteSessionId && typeof drv.setConfigOption === "function") {
          const res = await drv.setConfigOption(record.remoteSessionId, configId, value).catch(() => null);
          if (Array.isArray(res?.configOptions)) updatedOptions = res.configOptions;
          else {
            updatedOptions = updateConfigOptionValues(updatedOptions, {[configId]: value});
          }
          // 仅当 setConfigOption 失败或返回配置未确认目标 thought_level/model 时才回退补发 setModel，避免 agy 重复切换同一模型两次
          const confirmedThought = Array.isArray(res?.configOptions)
            ? res.configOptions.find(opt => opt?.id === "thought_level")?.currentValue
            : null;
          const confirmedModel = Array.isArray(res?.configOptions)
            ? res.configOptions.find(opt => opt?.id === "model")?.currentValue
            : null;
          const expectedBaseModel = record.model ? splitAgyModelEffort(record.model).baseId : null;
          const thoughtAlreadyApplied = Boolean(res)
            && confirmedThought === value
            && (!confirmedModel || !expectedBaseModel || confirmedModel === expectedBaseModel);
          if (m.id === "antigravity" && configId === "thought_level" && record.model && typeof drv.setModel === "function" && !thoughtAlreadyApplied) {
            const concrete = resolveAgyConcreteModel(record.model, value, null, record.availableModels);
            const setOk = concrete ? await drv.setModel(record.remoteSessionId, concrete).then(() => true, () => false) : false;
            appliedLive = Boolean(res) || setOk;
          } else {
            appliedLive = Boolean(res);
          }
        }
      }
      if (!appliedLive) {
        updatedOptions = updateConfigOptionValues(updatedOptions, {[configId]: value});
      }
      record.configOptions = updatedOptions;
      if (configId === "thought_level") {
        record.effort = value;
        if (m.protocol === "acp") {
          if (appliedLive) delete record._pendingEffortSync;
          else {
            record._pendingEffortSync = true;
            if (m.id === "antigravity" && record.model) record._pendingModelSync = true;
          }
        }
      }
      if (configId === "model") {
        record.model = m.id === "antigravity" ? (splitAgyModelEffort(value).baseId || value) : value;
        if (m.protocol === "acp") {
          if (appliedLive) delete record._pendingModelSync;
          else record._pendingModelSync = true;
        }
      }
      await saveSession(record);
      return {ok: true, configOptions: updatedOptions};
    }

    case "agy/catalog":
      return await queryAgyCatalog(params.apiKey || null);
    case "agy/plugins":
      return await handleAgyPlugins(params);
    case "agy/artifacts":
      return await listAgyArtifacts();
    case "usage/summary":
      return await usageLedger.queryUsageSummary(params);
    case "usage/exportCsv":
      return await usageLedger.exportUsageCsv(params);
    case "usage/record":
      await usageLedger.recordUsageEvent(params);
      return {ok: true};
    case "usage/clear":
      return await usageLedger.clearUsageLedger(params);
    case "latex/status":
      return await devTools.latexStatus();
    case "latex/compile":
      return await devTools.compileLatex(params);

    case "sessions/setProvider": {
      const record = await loadStoredSession(params.agentId, params.sessionId);
      if (!record) throw new Error("会话不存在");
      const key = `${params.agentId}:${record.id}`;
      if (activeTurns.has(key)) throw new Error("请先结束当前任务再切换服务商");
      const previousProvider = record.providerId || null;
      const previousModel = record.model;
      const previousRemote = record.remoteSessionId;
      const previousBridge = record.pendingContextBridge;
      const nextProvider = params.providerId || null;
      record.providerId = nextProvider;
      if (params.model !== undefined) record.model = params.model || null;
      if (previousProvider !== nextProvider) {
        record.availableModels = [];
        record.configOptions = [];
        if (params.agentId === "antigravity" && Boolean(previousProvider) !== Boolean(nextProvider)) {
          record.remoteSessionId = null;
          if (Array.isArray(record.messages) && record.messages.length > 0) {
            record.pendingContextBridge = true;
          }
        }
      }
      try { await saveSession(record); } catch (error) {
        record.providerId = previousProvider;
        record.model = previousModel;
        record.remoteSessionId = previousRemote;
        record.pendingContextBridge = previousBridge;
        throw error;
      }
      stopSessionDriver(key);
      return {ok: true};
    }

    case "pty/open":
      return devTools.openPty(params);
    case "pty/write":
      return devTools.writePty(params);
    case "pty/close":
      return devTools.closePty(params);
    case "pty/resize":
      return devTools.resizePty(params);
    case "pty/attach":
      return devTools.attachPty(params);
    case "pty/list":
      return devTools.listPtys();

    case "git/sshKey":
      return await devTools.ensureSshKey();
    case "git/clone":
      return await devTools.cloneRepo(params);

    case "ports/list":
      return await devTools.listListeningPorts(gatewayConfig.port > 0 ? [gatewayConfig.port] : []);
    case "tcp/open":
      return await devTools.openTcp(params);
    case "tcp/write":
      return devTools.writeTcp(params);
    case "tcp/end":
      return devTools.endTcp(params);
    case "tcp/close":
      return devTools.closeTcp(params);

    case "extensions/sync":
      return await extSync.syncExtensions(params);
    case "extensions/probeMcp":
      return await extSync.probeMcpServer(params);
    case "extensions/listSkills":
      return await extSync.listDiscoveredSkills(params);
    case "extensions/saveSkill":
      return await extSync.saveLocalSkill(params);
    case "extensions/deleteSkill":
      return await extSync.deleteLocalSkill(params);
    case "extensions/importSkillGitHub":
      return await extSync.importSkillFromGitHub({
        ...params,
        env: baseEnv(null, null),
        githubMirrorPrefix: networkConfig.githubMirrorPrefix,
      });
    case "extensions/importPluginGitHub":
      return await extSync.importPluginFromGitHub({
        ...params,
        env: baseEnv(null, null),
        githubMirrorPrefix: networkConfig.githubMirrorPrefix,
      });
    case "extensions/testHook":
      return await extSync.testHookCommand(params);
    case "extensions/listSkillFiles":
      return await extSync.listSkillCompanionFiles(params);
    case "extensions/installBuiltinPlugin":
      return await extSync.installBuiltinPluginFiles(params);
    case "extensions/uninstallPlugin":
      return await extSync.uninstallPluginFiles(params);

    case "worktree/prepare":
      return await worktreeMgr.prepareWorktree(params);
    case "worktree/diff":
      return await worktreeMgr.diffWorktree(params);
    case "worktree/merge":
      return await worktreeMgr.mergeWorktree(params);
    case "worktree/discard":
      return await worktreeMgr.discardWorktree(params);
    case "tickets/save":
      return await worktreeMgr.saveTicketRecord(params.ticket || params);
    case "tickets/list":
      return {tickets: await worktreeMgr.listTicketRecords()};
    case "tickets/delete":
      return await worktreeMgr.deleteTicketRecord(params.ticketId);

    case "env/validateShell":
      return await envSsh.validateShellSnippet(params);
    case "env/sync": {
      const res = await envSsh.syncEnvironment(params);
      userGlobalEnv = await envSsh.loadEffectiveUserEnv();
      userGlobalEnvError = null;
      await extSync.refreshCodexEnvironment();
      return res;
    }
    case "env/effective":
      return {env: await envSsh.loadEffectiveUserEnv(params.cwd, process.env.PATH || "")};
    case "env/snapshot":
      return await envSsh.getRuntimeEnvSnapshot({cwd: params.cwd, extraEnv: baseEnv(null, null)});
    case "env/readDotEnv":
      return await envSsh.readProjectDotEnv(params);
    case "env/writeDotEnv":
      return await envSsh.writeProjectDotEnv(params);

    case "ssh/sync":
      return await envSsh.syncSshHosts(params);
    case "ssh/listKeys":
      return await envSsh.listSshKeys();
    case "ssh/generateKey":
      return await envSsh.generateSshKey(params);
    case "ssh/importKey":
      return await envSsh.importSshKey(params);
    case "ssh/deleteKey":
      return await envSsh.deleteSshKey(params);
    case "ssh/test":
      return await envSsh.testSshHost({...params, env: baseEnv(null, null)});
    case "ssh/deployKey":
      return await envSsh.deploySshKey({...params, env: baseEnv(null, null)});
    case "ssh/exec":
      return await envSsh.execSshCommand({...params, env: baseEnv(null, null)});
    case "ssh/listTunnels":
      return envSsh.listSshTunnels();
    case "ssh/startTunnel":
      return await envSsh.startSshTunnel({...params, env: baseEnv(null, null)});
    case "ssh/stopTunnel":
      return envSsh.stopSshTunnel(params);
    case "ssh/verifyKeyPassphrase":
      return await envSsh.verifySshKeyPassphrase(params);
    case "ssh/knownHosts":
      return await envSsh.listKnownHosts();
    case "ssh/removeKnownHost":
      return await envSsh.removeKnownHost(params);
    case "ssh/sftpList":
      return await envSsh.sftpList({...params, env: baseEnv(null, null)});
    case "ssh/transfers":
      return envSsh.listSftpTransfers();
    case "ssh/startTransfer":
      return envSsh.startSftpTransfer({...params, env: baseEnv(null, null)});
    case "ssh/cancelTransfer":
      return envSsh.cancelSftpTransfer(params);
    case "ssh/sftpRename":
      return await envSsh.sftpRename({...params, env: baseEnv(null, null)});
    case "ssh/sftpChmod":
      return await envSsh.sftpChmod({...params, env: baseEnv(null, null)});
    case "ssh/sftpReadText":
      return await envSsh.sftpReadText({...params, env: baseEnv(null, null)});
    case "ssh/sftpWriteText":
      return await envSsh.sftpWriteText({...params, env: baseEnv(null, null)});
    case "ssh/sftpUpload":
      return await envSsh.sftpUpload({...params, env: baseEnv(null, null)});
    case "ssh/sftpDownload":
      return await envSsh.sftpDownload({...params, env: baseEnv(null, null)});
    case "ssh/sftpMkdir":
      return await envSsh.sftpMkdir({...params, env: baseEnv(null, null)});
    case "ssh/sftpDelete":
      return await envSsh.sftpDelete({...params, env: baseEnv(null, null)});

    case "plugins/list":
      return pluginKernel.snapshot();
    case "plugins/toggle":
      return await pluginKernel.togglePlugin(params.pluginId, params.enabled);
    case "plugins/updateSettings":
      return await pluginKernel.updateSettings(params.pluginId, params.settings);
    case "plugins/updateGrants":
      return await pluginKernel.updateGrants(params.pluginId, params.grantedCapabilities);
    case "plugins/setSafeMode":
      return await pluginKernel.setSafeMode(params.enabled);
    case "plugins/rollback":
      return await pluginKernel.rollbackLastKnownGood();
    case "plugins/reload":
      return await pluginKernel.boot();
    case "plugins/runCommand":
      return await pluginKernel.runCommand(params);
    case "plugins/renderPanel":
      return await pluginKernel.renderPanel(params);
    case "plugins/createLocal":
      return await pluginKernel.createLocalPlugin(params);
    case "plugins/removeLocal":
      return await pluginKernel.removeLocalPlugin(params.pluginId);
    case "plugins/installBundle":
      return await pluginKernel.installPluginBundle(params);
    case "plugins/exportBundle":
      return await pluginKernel.exportPluginBundle(params.pluginId);
    case "plugins/reloadPlugin":
      return await pluginKernel.reloadPlugin(params.pluginId);
    case "plugins/readSource":
      return await pluginKernel.readPluginSource(params.pluginId);
    case "plugins/validate":
      return await pluginKernel.validatePluginCandidate(params || {});
    case "plugins/applySource":
      return await pluginKernel.applyPluginSourceUpdate(params || {});
    case "plugins/snapshots":
      return {snapshots: await pluginKernel.listSourceSnapshots(params.pluginId)};
    case "plugins/rollbackSnapshot":
      return await pluginKernel.rollbackSourceSnapshot(params.pluginId, params.snapshotId || null);
    case "plugins/restoreBuiltin":
      return await pluginKernel.restoreBuiltinPlugin(params.pluginId);
    case "plugins/test":
      return await pluginKernel.testPlugin(params || {});
    case "plugins/renderSurface":
      return await pluginKernel.renderSurface(params || {});
    case "plugins/renderSlot":
      return await pluginKernel.renderSlot(params || {});
    case "plugins/testSdui":
      return await pluginKernel.testPluginSdui(params || {});
    case "plugins/installDeps":
      return await pluginKernel.installPluginDependencies(params || {});
    case "plugins/studioOverride":
      return await pluginKernel.manageStudioOverride(params || {});
    case "plugins/testDriver":
      return await pluginKernel.testPluginDriverOrTranslator(params || {});
    case "plugins/invokeDriver":
      return await pluginKernel.invokePluginDriver({
        ...(params || {}),
        onEvent: ev => notify("plugins/driverEvent", {driverId: params?.driverId || "", sessionId: params?.sessionId || "", event: ev}),
      });
    case "plugins/driverModels":
      return {models: await pluginKernel.listPluginDriverModels(params.driverId)};

    case "selfBuild/getConfig":
      return await pluginKernel.getSelfBuildConfig();
    case "selfBuild/saveConfig":
      return await pluginKernel.saveSelfBuildConfig(params || {});
    case "selfBuild/status":
      return await pluginKernel.manageSelfBuild({...(params || {}), action: "status", source: "ui",
        onHostStatus: () => askClient("selfBuild/hostStatus", {}),
      });
    case "selfBuild/bootstrap":
      return await pluginKernel.manageSelfBuild({...(params || {}), action: "bootstrap", source: "ui"});
    case "selfBuild/build":
      return await pluginKernel.manageSelfBuild({...(params || {}), action: "build", source: "ui"});
    case "selfBuild/apply":
      return await pluginKernel.manageSelfBuild({
        ...(params || {}),
        action: "apply",
        source: "ui",
        onHostInstallApk: apkPath => askClient("selfBuild/installApk", {apkPath}),
      });
    case "selfBuild/rollback":
      return await pluginKernel.manageSelfBuild({...(params || {}), action: "rollback", source: "ui",
        onHostRollbackApk: () => askClient("selfBuild/hostRollback", {}),
      });
    case "mobileUse/execute":
      return await askClient("mobileUse/execute", {
        action: String(params?.action || "status"),
        params: params?.params && typeof params.params === "object" ? params.params : {},
      });

    case "files/listCheckpoints": {
      const baseCwd = await resolveSafeHomePath(String(params?.cwd || path.join(HOME, "workspace")), {
        cwd: HOME, allowedRoot: HOME, allowMissing: true,
      });
      const rawPath = String(params?.filePath || "").trim();
      const target = rawPath
        ? await resolveSafeHomePath(rawPath, {cwd: baseCwd, allowedRoot: HOME, allowMissing: true})
        : "";
      const checkpoints = await listFileCheckpoints({
        sessionId: String(params?.sessionId || "default"),
        filePath: target,
      });
      return {checkpoints};
    }

    case "files/revertCheckpoint": {
      const baseCwd = await resolveSafeHomePath(String(params?.cwd || path.join(HOME, "workspace")), {
        cwd: HOME, allowedRoot: HOME, allowMissing: false,
      });
      const rawPath = String(params?.filePath || "").trim();
      if (!rawPath) throw new Error("缺少要回滚的文件路径");
      const target = await resolveSafeHomePath(rawPath, {cwd: baseCwd, allowedRoot: HOME, allowMissing: true});
      const sid = String(params?.sessionId || "default");
      try {
        return await revertFileCheckpoint({
          sessionId: sid,
          checkpointId: String(params?.checkpointId || ""),
          filePath: target,
        });
      } catch (cpErr) {
        // 无独立文件检查点时（例如 Codex/Claude 通过 CLI 直接修改），尝试在 Git 工作区回退该单文件（先备份当前版保证可逆）
        const rel = path.relative(baseCwd, target);
        if (!rel || rel === "." || rel.startsWith("..") || path.isAbsolute(rel)) {
          throw new Error(`未找到「${path.basename(target)}」的修改前快照且文件不在当前项目工作目录内：${cpErr.message}`);
        }
        try {
          await execFileAsync("git", ["rev-parse", "--is-inside-work-tree"], {cwd: baseCwd, timeout: 4000});
          const undoCp = await captureFileCheckpoint(target, {sessionId: sid}).catch(() => null);
          await execFileAsync("git", ["checkout", "--", rel], {cwd: baseCwd, timeout: 8000});
          return {
            reverted: true,
            action: "git_restored",
            filePath: target,
            undoCheckpointId: undoCp?.id || null,
          };
        } catch {
          throw new Error(`未找到「${path.basename(target)}」的修改前快照且当前目录非 Git 跟踪文件：${cpErr.message}`);
        }
      }
    }

    default: {
      if (method.startsWith("plugin.")) {
        const hit = await pluginKernel.invokeRpc(method, params);
        if (hit.handled) return hit.result;
      }
      throw new Error(`Agent Hub 未知方法：${method}`);
    }
  }
}

const rl = createInterface({input: process.stdin, crlfDelay: Infinity});
rl.on("line", line => {
  if (!line.trim()) return;
  if (Buffer.byteLength(line) > MAXIMUM_FRAME) { shutdown(); return; }
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  // 接收来自 Android 对反向请求（如权限审批）的响应
  if (Object.hasOwn(msg, "id") && !Object.hasOwn(msg, "method")) {
    const waiter = pendingClientReqs.get(msg.id);
    if (!waiter) return;
    if (msg.error) waiter.reject(new Error(msg.error.message || "Client rejected"));
    else waiter.resolve(msg.result);
    return;
  }
  if (typeof msg.method !== "string") return;
  if (!Object.hasOwn(msg, "id")) return; // notifications like `initialized`
  handleRequest(msg.method, msg.params || {})
    .then(result => output({id: msg.id, result: result ?? {}}))
    .catch(err => output({id: msg.id, error: {code: -32000, message: cleanInstallOutput(err?.message || String(err))}}));
});

function shutdown() {
  if (closing) return;
  closing = true;
  void pluginControlServer?.close?.().catch(() => {});
  void pluginKernel.dispose().catch(error => console.error("插件内核清理失败：", error.message));
  cliUpdater.close();
  void devTools.closeAll().catch(error => console.error("终端清理失败：", error.message));
  void envSsh.closeAllTunnels().catch(error => console.error("SSH 隧道清理失败：", error.message));
  void envSsh.closeSftpTransfers().catch(error => console.error("SFTP 清理失败：", error.message));
  for (const key of [...drivers.keys()]) stopSessionDriver(key);
  for (const id of [...authProcs.keys()]) stopAuth(id);
  if (packageOperation?.child) {
    const child = packageOperation.child;
    signalInstaller(child, "SIGTERM");
    setTimeout(() => { if (child.exitCode === null && child.signalCode === null) signalInstaller(child, "SIGKILL"); }, 2000);
  }
  for (const waiter of pendingClientReqs.values()) waiter.reject(new Error("Agent Hub 已停止"));
  hubDelegateServer?.close();
  if (hubDelegateSockPath) fs.rm(hubDelegateSockPath, {force: true}).catch(() => {});
  setTimeout(() => process.exit(0), 3000);
}
rl.once("close", shutdown);
for (const sig of ["SIGTERM", "SIGINT"]) process.once(sig, shutdown);
process.stdin.once("error", shutdown);
process.stdout.once("error", shutdown);
