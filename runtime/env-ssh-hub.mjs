import fs from "node:fs/promises";
import path from "node:path";
import {randomUUID, createHash} from "node:crypto";
import {constants} from "node:fs";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";

const execFileAsync = promisify(execFile);
const HOME = process.env.HOME || "/home/agent";
const ENV_ROOT = path.join(HOME, ".galiais", "env");
const ENV_CONFIG_FILE = path.join(ENV_ROOT, "config.json");
const ENV_SECRETS_FILE = path.join(ENV_ROOT, "secrets.json");
const ENV_PROFILE_FILE = path.join(ENV_ROOT, "profile.sh");

const SSH_DIR = path.join(HOME, ".ssh");
const SSH_CONFIG_FILE = path.join(SSH_DIR, "config");
const SSH_STATE_ROOT = path.join(HOME, ".galiais", "ssh");
const SSH_HOSTS_FILE = path.join(SSH_STATE_ROOT, "hosts.json");
const SSH_PASSWORDS_FILE = path.join(SSH_STATE_ROOT, "passwords.json");
const SSH_KEY_PASSPHRASES_FILE = path.join(SSH_STATE_ROOT, "key-passphrases.json");

const ENV_BLOCK_START = "# >>> solbraid-env-start >>>";
const ENV_BLOCK_END = "# <<< solbraid-env-end <<<";
const SSH_BLOCK_START = "# >>> solbraid-managed-ssh-start >>>";
const SSH_BLOCK_END = "# <<< solbraid-managed-ssh-end <<<";

const VALID_ENV_KEY = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;
const VALID_ALIAS = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const VALID_KEY_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$/;
const PROTECTED_ENV_KEYS = new Set(["HOME", "USER", "LOGNAME", "SHELL", "PWD", "SHLVL", "GALIAIS_DELEGATE_SOCKET", "GALIAIS_DELEGATE_TOKEN", "SOLBRAID_PLUGIN_CTL_SOCKET", "SOLBRAID_PLUGIN_CTL_TOKEN"]);
const SENSITIVE_KEY_RE = /(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|PRIVATE_KEY|CREDENTIAL|AUTH)/i;
const MAX_DOTENV_BYTES = 1024 * 1024;
const mutations = new Map();

function serializeMutation(key, action) {
  const current = (mutations.get(key) || Promise.resolve()).then(action);
  const tail = current.catch(() => {});
  mutations.set(key, tail);
  void tail.then(() => { if (mutations.get(key) === tail) mutations.delete(key); });
  return current;
}

async function exists(file) {
  try { await fs.lstat(file); return true; }
  catch (error) { if (error.code === "ENOENT") return false; throw error; }
}

const activeTunnels = new Map(); // tunnelId -> { spec, child, status, startedAt, error }
const transfers = new Map();
let tunnelNotify = () => {};

export function setSshNotifier(fn) {
  if (typeof fn === "function") tunnelNotify = fn;
}

function shellQuote(val) {
  return `'${String(val ?? "").replace(/'/g, "'\\''")}'`;
}

async function atomicWrite(filePath, content, mode = 0o600) {
  await fs.mkdir(path.dirname(filePath), {recursive: true, mode: 0o700});
  const tmp = `${filePath}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, content, {mode, flag: "wx"});
    await fs.rename(tmp, filePath);
  } finally {
    await fs.rm(tmp, {force: true}).catch(() => {});
  }
}

async function readJsonSafe(filePath, fallback) {
  try { return JSON.parse(await fs.readFile(filePath, "utf8")); }
  catch (error) {
    if (error.code === "ENOENT") return fallback;
    if (error instanceof SyntaxError) throw new Error(`配置文件格式无效：${path.basename(filePath)}`, {cause: error});
    throw error;
  }
}

async function upsertManagedBlock(filePath, startMark, endMark, blockBody, mode = 0o644) {
  let existing = "";
  try { existing = await fs.readFile(filePath, "utf8"); }
  catch (error) { if (error.code !== "ENOENT") throw error; }
  if (existing.includes(startMark) !== existing.includes(endMark)) throw new Error(`配置托管段不完整：${path.basename(filePath)}`);
  const re = new RegExp(`${startMark.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[\\s\\S]*?${endMark.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\n?`, "g");
  const stripped = existing.replace(re, "").trimEnd();
  const block = blockBody ? `${startMark}\n${blockBody.trimEnd()}\n${endMark}\n` : "";
  const merged = stripped ? (block ? `${stripped}\n\n${block}` : `${stripped}\n`) : block;
  await atomicWrite(filePath, merged, mode);
}

// ── Part 1: 完整开发环境中心（环境变量 / Secret / PATH / 别名 / Git 身份 / .env） ──

export function syncEnvironment(params = {}) {
  return serializeMutation("environment", () => syncEnvironmentNow(params));
}

async function syncEnvironmentNow({config = {}, secrets = {}}) {
  const vars = Array.isArray(config.vars) ? config.vars.map(v => {
    if (!v || !VALID_ENV_KEY.test(v.key || "") || PROTECTED_ENV_KEYS.has(v.key)) throw new Error("环境变量名无效或由运行环境管理：" + (v?.key || ""));
    if (typeof v.value !== "string" || v.value.includes("\0") || (v.secret && typeof secrets[v.id] !== "undefined" && (typeof secrets[v.id] !== "string" || secrets[v.id].includes("\0")))) throw new Error("环境变量值无效：" + v.key);
    if (v.scope !== "global" && (typeof v.scope !== "string" || !v.scope.startsWith("project:/") || /[\r\n\0]/.test(v.scope))) throw new Error("环境变量范围无效：" + v.key);
    return {...v, scope: v.scope === "global" ? "global" : "project:" + path.resolve(v.scope.slice(8))};
  }) : [];
  const unique = new Set();
  for (const v of vars) {
    const key = v.scope + ":" + v.key;
    if (unique.has(key)) throw new Error("同一范围内存在重复变量：" + v.key);
    unique.add(key);
  }
  const extraPaths = Array.isArray(config.extraPaths)
    ? config.extraPaths.map(p => String(p || "").trim()).filter(p => p && !/[\r\n\0]/.test(p))
    : [];
  const aliases = Array.isArray(config.aliases)
    ? config.aliases.filter(a => a && /^[A-Za-z0-9_.-]{1,40}$/.test(String(a.name || "")) && String(a.command || "").trim() && !/[\r\n\0]/.test(a.command))
    : [];
  const shellSnippet = typeof config.shellSnippet === "string" ? config.shellSnippet : "";
  if (Buffer.byteLength(shellSnippet) > 8192) throw new Error("Shell 启动片段不能超过 8 KB");
  if (extraPaths.length !== (config.extraPaths || []).length || extraPaths.some(p => p.includes(":"))) throw new Error("PATH 中包含无效目录");
  if (aliases.length !== (config.aliases || []).length || new Set(aliases.map(a => a.name)).size !== aliases.length) throw new Error("Shell 别名无效或重复");
  await validateShellSnippet({shellSnippet});
  const gitCfg = config.git && typeof config.git === "object" ? config.git : {};
  if (Object.values(gitCfg).some(v => typeof v !== "string" || /[\r\n\0]/.test(v)) || !["", "true", "false", "input"].includes(gitCfg.autoCrlf || "")) throw new Error("Git 配置无效");
  const previous = await readJsonSafe(ENV_CONFIG_FILE, {});

  const normalized = {vars, extraPaths, aliases, shellSnippet, git: gitCfg, secrets};

  // 生成 POSIX 兼容 profile.sh 供终端、Codex 及所有登录 Shell 自动加载（同一 Shell PID 内幂等防重入，且对 PATH 去重）
  const lines = [
    "# Managed by Solbraid Environment Hub",
    `if [ "\${_SOLBRAID_PROFILE_PID:-}" = "$$" ]; then return 0 2>/dev/null || true; fi`,
    `_SOLBRAID_PROFILE_PID="$$"`,
  ];
  for (const v of vars) {
    if (v.enabled === false) continue;
    if (v.scope && v.scope !== "global") continue;
    const val = v.secret ? (secrets[v.id] ?? secrets[v.key] ?? "") : String(v.value ?? "");
    if (v.secret && !val) continue;
    lines.push(`export ${v.key}=${shellQuote(val)}`);
  }
  for (const v of vars.slice().sort((a, b) => a.scope.length - b.scope.length)) {
    if (v.enabled === false || typeof v.scope !== "string" || !v.scope.startsWith("project:")) continue;
    const target = path.resolve(v.scope.slice("project:".length));
    const value = v.secret ? (secrets[v.id] ?? secrets[v.key] ?? "") : String(v.value ?? "");
    if (v.secret && !value) continue;
    lines.push(`case "$PWD/" in ${shellQuote(target.replace(/\/+$/, "") + "/")}*) export ${v.key}=${shellQuote(value)} ;; esac`);
  }
  if (extraPaths.length > 0) {
    for (let i = extraPaths.length - 1; i >= 0; i--) {
      const expanded = extraPaths[i].replace(/^~(?=\/|$)/, HOME).replaceAll("$HOME", HOME);
      const q = shellQuote(expanded);
      lines.push(`case ":$PATH:" in *:${q}:*) ;; *) export PATH=${q}:"$PATH" ;; esac`);
    }
  }
  for (const a of aliases) {
    lines.push(`alias ${a.name}=${shellQuote(a.command)}`);
  }
  if (shellSnippet.trim()) {
    lines.push("", "# User startup snippet", shellSnippet.trim());
  }
  await atomicWrite(ENV_PROFILE_FILE, lines.join("\n") + "\n", 0o600);

  const sourceLine = `[ "\${_SOLBRAID_PROFILE_PID:-}" != "$$" ] && [ -f "${ENV_PROFILE_FILE}" ] && . "${ENV_PROFILE_FILE}"`;
  await upsertManagedBlock(path.join(HOME, ".bashrc"), ENV_BLOCK_START, ENV_BLOCK_END, sourceLine, 0o644);
  await upsertManagedBlock(path.join(HOME, ".profile"), ENV_BLOCK_START, ENV_BLOCK_END, sourceLine, 0o644);

  // 同步 Git 全局身份配置
  const gitPairs = [
    ["user.name", "userName"],
    ["user.email", "userEmail"],
    ["init.defaultBranch", "defaultBranch"],
    ["core.autocrlf", "autoCrlf"],
  ];
  for (const [k, field] of gitPairs) {
    const v = String(gitCfg[field] || "").trim();
    if (v) {
      await execFileAsync("/usr/bin/git", ["config", "--global", "--", k, v], {timeout: 5000});
    } else if (previous.git?.[field]) {
      try {
        const {stdout} = await execFileAsync("/usr/bin/git", ["config", "--global", "--get", k], {timeout: 5000});
        if (stdout.trim() === previous.git[field]) await execFileAsync("/usr/bin/git", ["config", "--global", "--unset-all", k], {timeout: 5000});
      } catch (error) { if (error.code !== 1 && error.code !== 5) throw error; }
    }
  }
  // 变量与密钥放在同一个原子快照中，跨进程读取不会混用两个版本。
  await atomicWrite(ENV_CONFIG_FILE, JSON.stringify(normalized, null, 2), 0o600);
  return {ok: true, globalVarCount: vars.filter(v => v.enabled !== false && (!v.scope || v.scope === "global")).length, pathCount: extraPaths.length, aliasCount: aliases.length};
}

export async function validateShellSnippet({shellSnippet = ""}) {
  if (typeof shellSnippet !== "string" || shellSnippet.includes("\0") || Buffer.byteLength(shellSnippet) > 8192) throw new Error("Shell 启动片段无效或超过 8 KB");
  const stage = await fs.mkdtemp(path.join("/tmp", "galiais-shell-"));
  try {
    const file = path.join(stage, "profile.sh");
    await fs.writeFile(file, shellSnippet, {mode: 0o600});
    try { await execFileAsync("/bin/bash", ["--noprofile", "--norc", "-n", file], {timeout: 5000}); }
    catch (error) {
      const line = String(error.stderr || "").match(/line (\d+)/)?.[1];
      throw new Error("Shell 语法错误" + (line ? "，请检查第 " + line + " 行" : "，请检查引号与语句是否闭合"));
    }
    return {ok: true};
  } finally { await fs.rm(stage, {recursive: true, force: true}); }
}

/** 供 agent-hub.mjs 与 claude-host.mjs 在会话启动时加载全局 + 当前项目环境变量与自定义 PATH。 */
export async function loadEffectiveUserEnv(cwd = null, basePath = "") {
  await mutations.get("environment");
  const cfg = await readJsonSafe(ENV_CONFIG_FILE, null);
  if (!cfg) return {};
  const secrets = cfg.secrets ?? await readJsonSafe(ENV_SECRETS_FILE, {});
  const out = {};
  const normCwd = cwd ? path.resolve(cwd) : null;
  const applyVar = (v) => {
    if (!v || v.enabled === false || !VALID_ENV_KEY.test(String(v.key || "")) || PROTECTED_ENV_KEYS.has(v.key)) return;
    const val = v.secret ? (secrets[v.id] ?? secrets[v.key] ?? "") : String(v.value ?? "");
    if (v.secret && !val) return;
    out[v.key] = val;
  };
  for (const v of (cfg.vars || [])) {
    if (!v.scope || v.scope === "global") applyVar(v);
  }
  if (normCwd) {
    for (const v of (cfg.vars || []).slice().sort((a, b) => String(a.scope).length - String(b.scope).length)) {
      if (typeof v.scope === "string" && v.scope.startsWith("project:")) {
        const target = path.resolve(v.scope.slice("project:".length));
        if (normCwd === target || normCwd.startsWith(target.replace(/\/+$/, "") + "/")) applyVar(v);
      }
    }
  }
  if (Array.isArray(cfg.extraPaths) && cfg.extraPaths.length > 0) {
    const expanded = cfg.extraPaths.map(p => String(p).replace(/^~(?=\/|$)/, HOME).replaceAll("$HOME", HOME)).filter(Boolean);
    const merged = [...expanded, ...(out.PATH || basePath || process.env.PATH || "").split(":")].filter(Boolean);
    out.PATH = [...new Set(merged)].join(":");
  }
  return out;
}

export async function getRuntimeEnvSnapshot({cwd, extraEnv = {}} = {}) {
  const userEnv = await loadEffectiveUserEnv(cwd);
  const cfg = await readJsonSafe(ENV_CONFIG_FILE, {vars: []});
  const secretKeys = new Set((cfg.vars || []).filter(v => v.secret).map(v => v.key));
  const merged = {...process.env, ...extraEnv, ...userEnv};
  const entries = Object.keys(merged).sort().map(key => {
    const raw = String(merged[key] ?? "");
    const masked = secretKeys.has(key) || SENSITIVE_KEY_RE.test(key);
    return {key, value: masked ? (raw ? "••••••••" : "") : raw.slice(0, 300), masked};
  });
  return {entries};
}

async function assertHomeDir(dir, allowMissing = false) {
  if (typeof dir !== "string" || !path.isAbsolute(dir) || /[\r\n\0]/.test(dir)) throw new Error("请提供完整目录路径");
  const requested = path.resolve(dir);
  let ancestor = requested;
  let resolved;
  while (true) {
    try { resolved = path.join(await fs.realpath(ancestor), path.relative(ancestor, requested)); break; }
    catch (error) {
      if (!allowMissing || error.code !== "ENOENT" || ancestor === path.dirname(ancestor)) throw error;
      if (await exists(ancestor)) throw new Error("目录包含无效符号链接");
      ancestor = path.dirname(ancestor);
    }
  }
  const home = await fs.realpath(HOME);
  if (resolved !== home && !resolved.startsWith(home + "/")) {
    throw new Error("仅允许访问 /home/agent 目录下的文件");
  }
  return resolved;
}

async function dotEnvTarget(cwd) {
  const dir = await assertHomeDir(cwd);
  const target = path.join(dir, ".env");
  if (await exists(target)) {
    const entry = await fs.lstat(target);
    if (!entry.isFile()) throw new Error(".env 必须是普通文件，不能是目录或符号链接");
  }
  return target;
}

async function readDotEnvFile(target) {
  let file;
  try { file = await fs.open(target, constants.O_RDONLY | constants.O_NOFOLLOW); }
  catch (error) { if (error.code === "ENOENT") return {exists: false, path: target, content: "", revision: "missing"}; throw error; }
  try {
    const data = Buffer.alloc(MAX_DOTENV_BYTES + 1);
    let used = 0;
    while (used < data.length) {
      const {bytesRead} = await file.read(data, used, data.length - used, used);
      if (!bytesRead) break;
      used += bytesRead;
    }
    if (used > MAX_DOTENV_BYTES) throw new Error(".env 超过 1 MB，请使用文件编辑器处理");
    const bytes = data.subarray(0, used);
    let content;
    try { content = new TextDecoder("utf-8", {fatal: true, ignoreBOM: true}).decode(bytes); }
    catch (error) { throw new Error(".env 不是有效的 UTF-8 文本，未修改文件", {cause: error}); }
    return {exists: true, path: target, content, revision: createHash("sha256").update(bytes).digest("hex")};
  } finally { await file.close(); }
}

export async function readProjectDotEnv({cwd}) {
  const target = await dotEnvTarget(cwd);
  await mutations.get(target);
  return readDotEnvFile(target);
}

export async function writeProjectDotEnv({cwd, content, expectedRevision}) {
  if (typeof content !== "string" || Buffer.byteLength(content) > MAX_DOTENV_BYTES) throw new Error(".env 内容必须为不超过 1 MB 的文本");
  if (typeof expectedRevision !== "string") throw new Error("请先重新读取 .env，再保存修改");
  const target = await dotEnvTarget(cwd);
  return serializeMutation(target, async () => {
    const current = await readDotEnvFile(target);
    if (current.revision !== expectedRevision) throw new Error(".env 已被其他操作修改，请重新读取后合并改动");
    await atomicWrite(target, content, 0o600);
    return {ok: true, path: target, revision: createHash("sha256").update(content).digest("hex")};
  });
}

// ── Part 2A: SSH 多密钥管理（~/.ssh/） ────────────────────────────────────────

async function inspectKeyFile(name) {
  const keyPath = path.join(SSH_DIR, name);
  const pubPath = `${keyPath}.pub`;
  const stat = await fs.stat(keyPath).catch(() => null);
  if (!stat?.isFile()) return null;
  let publicKey = "";
  try { publicKey = (await fs.readFile(pubPath, "utf8")).trim(); } catch {}
  let fingerprint = "", keyType = "", bits = 0;
  try {
    const {stdout} = await execFileAsync("/usr/bin/ssh-keygen", ["-lf", publicKey ? pubPath : keyPath], {timeout: 8000});
    const parts = stdout.trim().split(/\s+/);
    bits = Number(parts[0]) || 0;
    fingerprint = parts[1] || "";
    const last = parts[parts.length - 1] || "";
    keyType = last.startsWith("(") && last.endsWith(")") ? last.slice(1, -1) : (publicKey.split(/\s+/)[0] || "");
  } catch {}
  return {
    name,
    path: keyPath,
    publicKey,
    fingerprint,
    keyType: keyType || "SSH",
    bits,
    isDefault: name === "id_ed25519",
  };
}

export async function listSshKeys() {
  await fs.mkdir(SSH_DIR, {recursive: true, mode: 0o700});
  const names = await fs.readdir(SSH_DIR).catch(() => []);
  const skip = new Set(["config", "known_hosts", "known_hosts.old", "authorized_keys", "environment"]);
  const candidates = names.filter(n => !skip.has(n) && !n.endsWith(".pub") && !n.endsWith(".tmp") && !n.startsWith("cm-"));
  const keys = [];
  for (const name of candidates.sort()) {
    const full = path.join(SSH_DIR, name);
    const head = await fs.readFile(full, "utf8").then(s => s.slice(0, 64)).catch(() => "");
    if (!head.includes("PRIVATE KEY")) continue;
    const info = await inspectKeyFile(name);
    if (info) keys.push(info);
  }
  return {keys};
}

function validateKeyName(raw) {
  const name = String(raw || "").trim();
  if (!VALID_KEY_NAME.test(name) || ["config", "known_hosts", "known_hosts.old", "authorized_keys", "environment"].includes(name) || name.endsWith(".pub")) {
    throw new Error("密钥文件名无效或属于 SSH 保留文件");
  }
  return name;
}

async function ensureNewKey(name) {
  await fs.mkdir(SSH_DIR, {recursive: true, mode: 0o700});
  const keyPath = path.join(SSH_DIR, name);
  if (await exists(keyPath) || await exists(`${keyPath}.pub`)) throw new Error(`密钥 ${name} 已存在，请使用新名称`);
  return keyPath;
}

async function installKeyPair(stagedKey, name) {
  const keyPath = await ensureNewKey(name);
  let ownedKey = null;
  try {
    await fs.copyFile(stagedKey, keyPath, constants.COPYFILE_EXCL);
    ownedKey = await fs.lstat(keyPath);
    await fs.copyFile(`${stagedKey}.pub`, `${keyPath}.pub`, constants.COPYFILE_EXCL);
  } catch (error) {
    if (ownedKey) {
      const current = await fs.lstat(keyPath).catch(() => null);
      if (current?.ino === ownedKey.ino && current?.dev === ownedKey.dev) await fs.unlink(keyPath);
    }
    throw error;
  }
  const key = await inspectKeyFile(name);
  if (!key) throw new Error("密钥已写入，但无法读取元数据，请刷新后检查");
  return {key};
}

export function generateSshKey(params = {}) {
  return serializeMutation("keys", () => generateSshKeyNow(params));
}

async function generateSshKeyNow({name = "id_ed25519", type = "ed25519", comment = "solbraid-mobile", passphrase = ""}) {
  const safeName = validateKeyName(name);
  if (!["rsa", "ed25519"].includes(type)) throw new Error("不支持的 SSH 密钥类型");
  if (typeof passphrase !== "string" || /[\r\n\0]/.test(passphrase) || Buffer.byteLength(passphrase) > 4096) throw new Error("密钥口令无效");
  await ensureNewKey(safeName);
  const stage = await fs.mkdtemp(path.join(SSH_DIR, ".key-generate-"));
  try {
    const stagedKey = path.join(stage, "key");
    // 先生成空口令密钥；若设置了口令则优先通过 PTY 管道由环境变量写入口令，避免在 /proc/<pid>/cmdline 暴露 -N 明文。
    await execFileAsync("/usr/bin/ssh-keygen", [
      "-q", "-t", type, ...(type === "rsa" ? ["-b", "4096"] : []),
      "-N", "",
      "-C", String(comment).slice(0, 80),
      "-f", stagedKey,
    ], {timeout: 25_000});
    if (passphrase) {
      let encryptedViaPty = false;
      try {
        const pyScript = [
          "import os, pty, select, subprocess, sys",
          "pw = os.environ.get('SOLBRAID_KEY_PASS', '').encode('utf-8') + b'\\n'",
          "m, s = pty.openpty()",
          "p = subprocess.Popen(['/usr/bin/ssh-keygen', '-p', '-f', sys.argv[1]], stdin=s, stdout=s, stderr=s, close_fds=True)",
          "os.close(s)",
          "buf = b''; sent = 0",
          "while True:",
          "  r, _, _ = select.select([m], [], [], 15)",
          "  if not r: break",
          "  try: chunk = os.read(m, 4096)",
          "  except OSError: break",
          "  if not chunk: break",
          "  buf += chunk",
          "  low = buf.lower()",
          "  if sent == 0 and b'new passphrase' in low: os.write(m, pw); buf = b''; sent = 1",
          "  elif sent == 1 and b'again' in low: os.write(m, pw); buf = b''; sent = 2",
          "os.close(m)",
          "sys.exit(p.wait(timeout=15))",
        ].join("\n");
        await execFileAsync("/usr/bin/python3", ["-c", pyScript, stagedKey], {
          timeout: 25_000,
          env: {...process.env, SOLBRAID_KEY_PASS: passphrase, LC_ALL: "C"},
        });
        encryptedViaPty = true;
      } catch {}
      if (!encryptedViaPty) {
        await execFileAsync("/usr/bin/ssh-keygen", ["-q", "-p", "-P", "", "-N", passphrase, "-f", stagedKey], {timeout: 25_000});
      }
    }
    return await installKeyPair(stagedKey, safeName);
  } finally { await fs.rm(stage, {recursive: true, force: true}); }
}

export function importSshKey(params) {
  return serializeMutation("keys", () => importSshKeyNow(params));
}

async function importSshKeyNow({name, privateKey, publicKey = "", passphrase = ""}) {
  const safeName = validateKeyName(name);
  if (typeof privateKey !== "string" || Buffer.byteLength(privateKey) > 64 * 1024) throw new Error("私钥内容无效或超过 64 KB");
  await ensureNewKey(safeName);
  const stage = await fs.mkdtemp(path.join(SSH_DIR, ".key-import-"));
  try {
    const stagedKey = path.join(stage, "key");
    await fs.writeFile(stagedKey, privateKey.replace(/\r\n/g, "\n").trim() + "\n", {mode: 0o600, flag: "wx"});
    let derived;
    try {
      const {stdout} = await withKeyPassphrase(passphrase, env => execFileAsync("/usr/bin/ssh-keygen", ["-y", "-f", stagedKey], {timeout: 10_000, env}));
      derived = stdout.trim();
    } catch (error) { throw new Error("私钥校验失败，请检查格式与口令", {cause: error}); }
    const supplied = String(publicKey).trim();
    if (supplied && (/[\r\n]/.test(supplied) || supplied.split(/\s+/).slice(0, 2).join(" ") !== derived.split(/\s+/).slice(0, 2).join(" "))) {
      throw new Error("提供的公钥与私钥不匹配");
    }
    await fs.writeFile(`${stagedKey}.pub`, (supplied || derived) + "\n", {mode: 0o644, flag: "wx"});
    return await installKeyPair(stagedKey, safeName);
  } finally { await fs.rm(stage, {recursive: true, force: true}); }
}

async function withKeyPassphrase(passphrase, action) {
  if (typeof passphrase !== "string" || /[\r\n\0]/.test(passphrase) || Buffer.byteLength(passphrase) > 4096) throw new Error("密钥口令无效");
  const stage = await fs.mkdtemp("/tmp/galiais-key-auth-");
  const askpass = path.join(stage, "askpass");
  try {
    await fs.writeFile(askpass, "#!/bin/sh\nprintf '%s\\n' " + shellQuote(passphrase) + "\n", {mode: 0o700});
    return await action({...process.env, SSH_ASKPASS: askpass, SSH_ASKPASS_REQUIRE: "force", DISPLAY: "solbraid:0"});
  } finally { await fs.rm(stage, {recursive: true, force: true}); }
}

export async function verifySshKeyPassphrase({name, passphrase}) {
  const keyPath = path.join(SSH_DIR, validateKeyName(name));
  try { await withKeyPassphrase(passphrase, env => execFileAsync("/usr/bin/ssh-keygen", ["-y", "-f", keyPath], {timeout: 10_000, env})); }
  catch { throw new Error("密钥口令不正确，或私钥不可读"); }
  return {ok: true};
}

export function deleteSshKey({name}) {
  return serializeMutation("keys", async () => {
    const safeName = validateKeyName(name);
    await fs.rm(path.join(SSH_DIR, safeName), {force: true});
    await fs.rm(path.join(SSH_DIR, `${safeName}.pub`), {force: true});
    return {ok: true};
  });
}

// ── Part 2B: SSH 主机簿与 ~/.ssh/config 无损投影 ──────────────────────────────

function sanitizeHostSpec(raw) {
  const alias = String(raw?.alias || "").trim();
  const host = String(raw?.host || "").trim();
  const user = String(raw?.user || "root").trim();
  const port = raw?.port === undefined ? 22 : Number(raw.port);
  if (!VALID_ALIAS.test(alias)) throw new Error(`无效的 SSH 别名：${alias}`);
  if (!host || /[\s\0"'`$;&|]/.test(host)) throw new Error("SSH 主机地址无效");
  if (!user || /[\s\0"'`$;&|]/.test(user)) throw new Error("SSH 用户名无效");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`无效端口：${port}`);
  const keyName = String(raw?.keyName || "").trim();
  const proxyJump = String(raw?.proxyJump || "").trim();
  if (keyName && !VALID_KEY_NAME.test(keyName)) throw new Error(`无效密钥名称：${keyName}`);
  if (proxyJump && /[\s\r\n"'`$;&|]/.test(proxyJump)) throw new Error(`无效跳板机配置：${proxyJump}`);
  const rawKeepAlive = raw?.keepAliveSec === undefined || raw?.keepAliveSec === null || raw?.keepAliveSec === "" ? 30 : Number(raw.keepAliveSec);
  const keepAliveSec = Number.isFinite(rawKeepAlive) ? Math.max(0, Math.min(300, Math.floor(rawKeepAlive))) : 30;
  return {
    id: String(raw?.id || alias),
    alias,
    name: String(raw?.name || alias),
    host,
    port,
    user,
    authType: raw?.authType === "password" ? "password" : "key",
    keyName,
    proxyJump,
    forwardAgent: Boolean(raw?.forwardAgent),
    keepAliveSec,
    defaultPath: String(raw?.defaultPath || "").trim(),
    startupCommand: String(raw?.startupCommand || "").trim(),
    group: String(raw?.group || "").trim(),
  };
}

export function syncSshHosts(params = {}) {
  return serializeMutation("ssh", () => syncSshHostsNow(params));
}

async function syncSshHostsNow({hosts = [], passwords = {}, keyPassphrases = {}}) {
  await fs.mkdir(SSH_DIR, {recursive: true, mode: 0o700});
  if (!Array.isArray(hosts)) throw new Error("SSH 主机列表无效");
  const normalized = hosts.map(sanitizeHostSpec);
  const aliases = new Set(), ids = new Set();
  for (const host of normalized) {
    const alias = host.alias.toLowerCase();
    if (aliases.has(alias) || ids.has(host.id)) throw new Error("SSH 别名或主机标识重复，请修改后重试");
    aliases.add(alias);
    ids.add(host.id);
  }

  const blocks = normalized.map(h => {
    const lines = [
      `Host ${h.alias}`,
      `  HostName ${h.host}`,
      `  Port ${h.port}`,
      `  User ${h.user}`,
      `  StrictHostKeyChecking accept-new`,
    ];
    if (h.authType === "key" && h.keyName) {
      lines.push(`  IdentityFile ~/.ssh/${h.keyName}`);
      lines.push(`  IdentitiesOnly yes`);
    } else if (h.authType === "password") {
      lines.push(`  PreferredAuthentications password,keyboard-interactive,publickey`);
    }
    if (h.proxyJump) lines.push(`  ProxyJump ${h.proxyJump}`);
    if (h.forwardAgent) lines.push(`  ForwardAgent yes`);
    if (h.keepAliveSec > 0) {
      lines.push(`  ServerAliveInterval ${h.keepAliveSec}`);
      lines.push(`  ServerAliveCountMax 3`);
    }
    lines.push(`  ControlMaster auto`);
    lines.push(`  ControlPersist 120`);
    lines.push(`  ControlPath ~/.ssh/cm-%r@%h:%p`);
    return lines.join("\n");
  });
  await upsertManagedBlock(SSH_CONFIG_FILE, SSH_BLOCK_START, SSH_BLOCK_END, blocks.join("\n\n"), 0o600);
  // 密码与密钥口令统一留存在 Android Keystore 中由原生 SSH 引擎按需内存读取，不再向容器磁盘写入明文密码文件
  await fs.rm(SSH_PASSWORDS_FILE, {force: true}).catch(() => {});
  await fs.rm(SSH_KEY_PASSPHRASES_FILE, {force: true}).catch(() => {});
  await atomicWrite(SSH_HOSTS_FILE, JSON.stringify(normalized, null, 2), 0o600);
  return {ok: true, count: normalized.length};
}

async function resolveHostAndSecret({hostId, alias}) {
  await mutations.get("ssh");
  const hosts = await readJsonSafe(SSH_HOSTS_FILE, []);
  const host = hosts.find(h => hostId ? h.id === hostId : alias && h.alias === alias);
  if (!host) throw new Error("未找到对应的 SSH 主机配置，请先同步");
  const passwords = await readJsonSafe(SSH_PASSWORDS_FILE, {});
  const password = host.authType === "password" ? String(passwords[host.id] || "") : "";
  return {host, password};
}

function shellCdRemote(dir) {
  const d = String(dir || "").trim();
  if (!d) return "";
  if (d === "~") return "cd ~";
  if (d.startsWith("~/")) return `cd ~/${shellQuote(d.slice(2))}`;
  return `cd ${shellQuote(d)}`;
}

/** 仅解析当前主机及其显式 ProxyJump 跳板链关联的主机与密钥，杜绝跨主机凭证泄露。 */
function collectScopedSshChain(host, allHosts) {
  const byAlias = new Map(allHosts.map(h => [String(h.alias || "").toLowerCase(), h]));
  const chain = [];
  const seen = new Set();
  const visit = h => {
    if (!h || seen.has(h.id)) return;
    seen.add(h.id);
    chain.push(h);
    if (!h.proxyJump) return;
    for (const rawHop of String(h.proxyJump).split(",")) {
      const hop = rawHop.trim().replace(/^[^@]+@/, "").replace(/:[0-9]+$/, "").toLowerCase();
      if (!hop) continue;
      const matched = byAlias.get(hop);
      if (matched) visit(matched);
    }
  };
  visit(host);
  return chain;
}

async function prepareSshAuth(host, password, baseEnvObj, interactive = false) {
  await mutations.get("ssh");
  const phrases = await readJsonSafe(SSH_KEY_PASSPHRASES_FILE, {});
  const hosts = await readJsonSafe(SSH_HOSTS_FILE, []);
  const passwords = await readJsonSafe(SSH_PASSWORDS_FILE, {});
  const scopedChain = collectScopedSshChain(host, hosts);
  const scopedKeyNames = new Set(scopedChain.map(h => h.keyName).filter(Boolean));
  const hasScopedPassword = scopedChain.some(h => Boolean(h.id === host.id ? password : passwords[h.id]));
  const hasScopedPhrase = [...scopedKeyNames].some(k => Boolean(phrases[k]));
  const hasSaved = hasScopedPassword || hasScopedPhrase;
  if (interactive && !hasSaved) return {env: {...baseEnvObj}, cleanup: async () => {}};
  const stage = await fs.mkdtemp("/tmp/galiais-ssh-auth-");
  const askpassPath = path.join(stage, "askpass");
  try {
    const lines = ["#!/bin/sh", 'case "$1" in'];
    for (const name of scopedKeyNames) {
      const phrase = phrases[name];
      if (!phrase) continue;
      validateKeyName(name);
      const fullPath = path.join(SSH_DIR, name);
      lines.push("*" + shellQuote(fullPath) + "*) printf '%s\\n' " + shellQuote(phrase) + ";;");
    }
    for (const h of scopedChain) {
      const secret = h.id === host.id ? password : passwords[h.id];
      if (!secret) continue;
      const targets = [h.user + "@" + h.host, h.user + "@" + h.alias].filter(Boolean);
      const pattern = targets.map(t => "*" + shellQuote(t) + "*[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]*").join("|");
      lines.push(`${pattern}) printf '%s\\n' ${shellQuote(secret)};;`);
    }
    if (password && scopedChain.length === 1) {
      lines.push("*[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]*) printf '%s\\n' " + shellQuote(password) + ";;");
    }
    lines.push("*) exit 1;;", "esac");
    await fs.writeFile(askpassPath, lines.join("\n") + "\n", {mode: 0o700});
    return {env: {...baseEnvObj, LC_ALL: "C", SSH_ASKPASS: askpassPath, SSH_ASKPASS_REQUIRE: "force", DISPLAY: "solbraid:0"},
      cleanup: () => fs.rm(stage, {recursive: true, force: true})};
  } catch (error) { await fs.rm(stage, {recursive: true, force: true}); throw error; }
}

async function withSshAuthEnv(host, password, baseEnvObj, fn) {
  if (host.authType === "password" && !password) throw new Error("此操作需要已保存的 SSH 密码，请填写密码或改用密钥认证");
  const auth = await prepareSshAuth(host, password, baseEnvObj);
  try {
    return await fn(auth);
  } finally { await auth.cleanup(); }
}

function commonSshArgs(host, {batch = false} = {}) {
  const args = [
    "-F", SSH_CONFIG_FILE,
    "-o", "ConnectTimeout=12",
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", `HostName=${host.host}`,
    "-o", `Port=${host.port}`,
    "-o", `User=${host.user}`,
  ];
  if (batch || host.authType === "password") args.push("-o", "BatchMode=no", "-o", "NumberOfPasswordPrompts=1");
  if (host.authType === "key" && host.keyName) args.push("-o", `IdentityFile=${path.join(SSH_DIR, host.keyName)}`, "-o", "IdentitiesOnly=yes");
  if (host.proxyJump) args.push("-J", host.proxyJump);
  args.push("-o", `ForwardAgent=${host.forwardAgent ? "yes" : "no"}`);
  if (host.authType === "password") args.push("-o", "PreferredAuthentications=password,keyboard-interactive,publickey");
  return args;
}

export async function testSshHost({hostId, alias, env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const started = Date.now();
  return await withSshAuthEnv(host, password, baseEnvObj, async ({env}) => {
    try {
      const args = [...commonSshArgs(host, {batch: true}), host.alias, "uname -srm && hostname && pwd"];
      const {stdout} = await execFileAsync("/usr/bin/ssh", args, {timeout: 18_000, env});
      const lines = stdout.trim().split(/\r?\n/).map(s => s.trim()).filter(Boolean);
      return {
        ok: true,
        latencyMs: Date.now() - started,
        remoteInfo: lines.join(" · ").slice(0, 200),
      };
    } catch (err) {
      return {
        ok: false,
        latencyMs: Date.now() - started,
        error: String(err.stderr || err.message || "SSH 连接失败").trim().slice(0, 260),
      };
    }
  });
}

export async function deploySshKey({hostId, alias, keyName = "id_ed25519", env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const safeKey = String(keyName || "id_ed25519").trim();
  if (!VALID_KEY_NAME.test(safeKey)) throw new Error("无效密钥名称");
  const pubPath = path.join(SSH_DIR, `${safeKey}.pub`);
  const pubKey = (await fs.readFile(pubPath, "utf8").catch(() => "")).trim();
  if (!pubKey) throw new Error(`未找到公钥 ${safeKey}.pub，请先在「密钥」页生成`);
  const remoteScript = [
    "mkdir -p ~/.ssh && chmod 700 ~/.ssh",
    `KEY=${shellQuote(pubKey)}`,
    'touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys',
    'grep -qxF "$KEY" ~/.ssh/authorized_keys || printf "%s\\n" "$KEY" >> ~/.ssh/authorized_keys',
    'echo "SOLBRAID_KEY_DEPLOYED"',
  ].join(" && ");
  return await withSshAuthEnv(host, password, baseEnvObj, async ({env}) => {
    const args = [...commonSshArgs(host, {batch: true}), host.alias, remoteScript];
    const {stdout} = await execFileAsync("/usr/bin/ssh", args, {timeout: 22_000, env});
    if (!stdout.includes("SOLBRAID_KEY_DEPLOYED")) throw new Error("远端未确认公钥写入");
    return {ok: true, keyName: safeKey};
  });
}

export async function execSshCommand({hostId, alias, command, remoteCwd = "", env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const cmd = String(command || "").trim();
  if (!cmd) throw new Error("请输入要执行的远端命令");
  const fullCmd = remoteCwd ? `${shellCdRemote(remoteCwd)} && ${cmd}` : cmd;
  const started = Date.now();
  return await withSshAuthEnv(host, password, baseEnvObj, async ({env}) => {
    try {
      const args = [...commonSshArgs(host, {batch: true}), host.alias, fullCmd];
      const {stdout, stderr} = await execFileAsync("/usr/bin/ssh", args, {timeout: 60_000, maxBuffer: 2 * 1024 * 1024, env});
      return {exitCode: 0, stdout: stdout.slice(-40_000), stderr: stderr.slice(-20_000), durationMs: Date.now() - started};
    } catch (err) {
      return {
        exitCode: typeof err.code === "number" ? err.code : 1,
        stdout: String(err.stdout || "").slice(-40_000),
        stderr: String(err.stderr || err.message || "").slice(-20_000),
        durationMs: Date.now() - started,
      };
    }
  });
}

/** 为 dev-tools-hub.mjs 的交互 PTY 准备一键直连 SSH 启动命令与安全临时 Askpass 环境。 */
export async function prepareSshPtyLaunch({hostId, alias, remoteCwd = "", baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const {env: extraEnv, cleanup} = await prepareSshAuth(host, password, baseEnvObj, true);
  const targetDir = remoteCwd || host.defaultPath || "";
  const sshParts = ["/usr/bin/ssh", "-tt", ...commonSshArgs(host)].map(shellQuote);
  sshParts.push(shellQuote(host.alias));
  if (targetDir || host.startupCommand) {
    const remoteSteps = [];
    if (targetDir) remoteSteps.push(`${shellCdRemote(targetDir)} || exit 1`);
    if (host.startupCommand) remoteSteps.push(host.startupCommand);
    remoteSteps.push("exec ${SHELL:-/bin/bash} -l");
    sshParts.push(shellQuote(remoteSteps.join("; ")));
  }
  return {command: sshParts.join(" "), env: extraEnv, host, cleanup};
}

// ── Part 2C: SSH 端口转发隧道（-L 本地 / -R 远端 / -D SOCKS5） ────────────────

function serializeTunnel(entry) {
  return {
    id: entry.spec.id,
    hostId: entry.spec.hostId,
    alias: entry.spec.alias,
    mode: entry.spec.mode,
    localPort: entry.spec.localPort,
    remoteHost: entry.spec.remoteHost,
    remotePort: entry.spec.remotePort,
    status: entry.status,
    startedAt: entry.startedAt,
    error: entry.error || null,
  };
}

export function listSshTunnels() {
  return {tunnels: [...activeTunnels.values()].map(serializeTunnel)};
}

export function startSshTunnel(params) {
  const id = String(params.id || randomUUID().slice(0, 10));
  return serializeMutation("tunnel:" + id, () => startSshTunnelNow({...params, id}));
}

async function waitForTunnelClose(entry, timeoutMs) {
  let timer;
  try {
    return await Promise.race([
      entry.closed.then(() => true),
      new Promise(resolve => { timer = setTimeout(() => resolve(false), timeoutMs); }),
    ]);
  } finally { clearTimeout(timer); }
}

async function stopTunnelProcess(entry) {
  entry.status = "stopping";
  entry.child.kill("SIGTERM");
  if (!await waitForTunnelClose(entry, 1500)) {
    entry.child.kill("SIGKILL");
    if (!await waitForTunnelClose(entry, 1500)) throw new Error("SSH 隧道尚未退出，请稍后重试");
  }
}

async function startSshTunnelNow({id, hostId, alias, mode = "local", localPort, remoteHost = "127.0.0.1", remotePort = 0, env: baseEnvObj = process.env}) {
  const existing = activeTunnels.get(id);
  if (existing?.status === "running") return {tunnel: serializeTunnel(existing)};
  if (existing) await stopTunnelProcess(existing);
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const lp = Number(localPort), rp = Number(remotePort);
  if (!["local", "remote", "dynamic"].includes(mode)) throw new Error("无效隧道模式");
  if (!Number.isInteger(lp) || lp < (mode === "remote" ? 1 : 1024) || lp > 65535) throw new Error("本地端口超出有效范围");
  if (mode !== "dynamic" && (!Number.isInteger(rp) || rp < 1 || rp > 65535)) throw new Error("远端端口须在 1..65535");
  const targetHost = String(remoteHost || "127.0.0.1").trim();
  if (!/^[A-Za-z0-9_.:[\]-]+$/.test(targetHost)) throw new Error("转发目标主机格式无效");
  const target = targetHost.includes(":") && !targetHost.startsWith("[") ? "[" + targetHost + "]" : targetHost;
  const forwardArgs = mode === "local"
    ? ["-L", "127.0.0.1:" + lp + ":" + target + ":" + rp]
    : mode === "remote"
      ? ["-R", "127.0.0.1:" + rp + ":" + target + ":" + lp]
      : ["-D", "127.0.0.1:" + lp];
  return withSshAuthEnv(host, password, baseEnvObj, async ({env}) => {
    // 控制套接字只会在认证完成后建立，转发请求成功后才能报告运行中。
    const controlDir = await fs.mkdtemp("/tmp/solbraid-ssh-");
    const controlPath = path.join(controlDir, "ctl");
    const keepAlive = Math.max(10, Math.min(300, Number(host.keepAliveSec) || 30));
    const args = ["-N", "-M", "-S", controlPath, ...commonSshArgs(host, {batch: true}),
      "-o", "ControlPersist=no", "-o", `ServerAliveInterval=${keepAlive}`, "-o", "ServerAliveCountMax=3", host.alias];
    let child;
    try { child = spawn("/usr/bin/ssh", args, {env, stdio: ["ignore", "ignore", "pipe"]}); }
    catch (error) { await fs.rm(controlDir, {recursive: true, force: true}); throw error; }
    const spec = {id, hostId: host.id, alias: host.alias, mode, localPort: lp, remoteHost: targetHost, remotePort: rp};
    const entry = {spec, child, status: "starting", startedAt: Date.now(), error: "", closed: null};
    activeTunnels.set(id, entry);
    child.stderr.setEncoding("utf8").on("data", chunk => { entry.error = (entry.error + chunk).slice(-2000); });
    child.once("error", error => { entry.error = error.message; });
    entry.closed = new Promise(resolve => child.once("close", (code, signal) => {
      if (activeTunnels.get(id) === entry) activeTunnels.delete(id);
      tunnelNotify("ssh/tunnelChanged", {tunnels: listSshTunnels().tunnels, closedId: id,
        error: entry.status === "running" && (code || signal) ? entry.error.trim() || "SSH 隧道连接已断开" : null});
      fs.rm(controlDir, {recursive: true, force: true}).finally(resolve).catch(() => {});
    }));
    tunnelNotify("ssh/tunnelChanged", {tunnels: listSshTunnels().tunnels});
    const deadline = Date.now() + 16_000;
    try {
      while (!await exists(controlPath)) {
        if (child.exitCode !== null || child.signalCode !== null || Date.now() >= deadline) {
          throw new Error(entry.error.trim() || "SSH 隧道启动超时");
        }
        await waitForTunnelClose(entry, 100);
      }
      await execFileAsync("/usr/bin/ssh", ["-F", "/dev/null", "-S", controlPath, "-O", "forward",
        "-o", "ExitOnForwardFailure=yes", ...forwardArgs, host.alias], {env, timeout: Math.max(1, deadline - Date.now())});
      if (child.exitCode !== null || child.signalCode !== null) throw new Error(entry.error.trim() || "SSH 隧道已退出");
      entry.status = "running";
      tunnelNotify("ssh/tunnelChanged", {tunnels: listSshTunnels().tunnels});
      return {tunnel: serializeTunnel(entry)};
    } catch (error) {
      await stopTunnelProcess(entry);
      throw new Error(String(error.stderr || error.message || error).trim(), {cause: error});
    }
  });
}

export function stopSshTunnel({id}) {
  return serializeMutation("tunnel:" + id, async () => {
    const entry = activeTunnels.get(String(id || ""));
    if (!entry) return {ok: true, stopped: false};
    await stopTunnelProcess(entry);
    return {ok: true, stopped: true};
  });
}

export async function closeAllTunnels() {
  await Promise.all([...activeTunnels.values()].map(stopTunnelProcess));
}

// ── Part 2D: SFTP 远端文件浏览器与上传/下载 ───────────────────────────────────

export async function listKnownHosts() {
  const file = path.join(SSH_DIR, "known_hosts");
  let content = "";
  try { content = await fs.readFile(file, "utf8"); }
  catch (error) { if (error.code !== "ENOENT") throw error; }
  if (Buffer.byteLength(content) > 2 * 1024 * 1024) throw new Error("known_hosts 超过 2 MB，请在终端管理");
  const entries = [];
  for (const [index, line] of content.split(/\r?\n/).entries()) {
    const parts = line.trim().split(/\s+/);
    if (!line.trim() || line.trim().startsWith("#")) continue;
    const marked = parts[0].startsWith("@");
    const [hosts, keyType, key] = parts.slice(marked ? 1 : 0);
    if (!key || !/^[A-Za-z0-9+/]+={0,2}$/.test(key)) continue;
    entries.push({id: createHash("sha256").update(line).digest("hex"), line: index + 1,
      hosts, keyType, marker: marked ? parts[0] : "",
      fingerprint: "SHA256:" + createHash("sha256").update(Buffer.from(key, "base64")).digest("base64").replace(/=+$/, "")});
  }
  return {entries};
}

export function removeKnownHost({id}) {
  if (!/^[a-f0-9]{64}$/.test(id || "")) throw new Error("主机指纹标识无效");
  const file = path.join(SSH_DIR, "known_hosts");
  return serializeMutation(file, async () => {
    const content = await fs.readFile(file, "utf8");
    const lines = content.split(/\r?\n/);
    const next = lines.filter(line => createHash("sha256").update(line).digest("hex") !== id);
    if (next.length === lines.length) throw new Error("此指纹已变化，请刷新后重试");
    await atomicWrite(file, next.join("\n"), 0o600);
    return {ok: true};
  });
}

function parseSftpLs(raw, basePath) {
  const entries = [];
  const normBase = basePath === "/" ? "" : basePath.replace(/\/+$/, "");
  for (const line of String(raw || "").split(/\r?\n/)) {
    const trimmed = line.trimStart();
    if (!trimmed || trimmed.startsWith("sftp>") || trimmed.startsWith("total ")) continue;
    // 支持 SELinux/ACL 尾部标记（./+/@）、SFTP v3 的 ? 链接数、ISO 日期与软链接 -> 目标。
    const m = trimmed.match(/^([dlcbps-][rwxstST-]{9})[+.@]?\s+[\d?]+\s+\S+\s+\S+\s+(\d+)\s+([A-Za-z]{3}\s+\d+\s+[\d:]+|\d{4}-\d{2}-\d{2}\s+[\d:.]+(?:\s+[+-]\d{4})?)\s+(.+)$/);
    if (!m) continue;
    const perms = m[1];
    const size = Number(m[2]) || 0;
    const modified = m[3];
    const rawName = m[4];
    const isSymlink = perms.startsWith("l");
    const arrowIdx = isSymlink ? rawName.indexOf(" -> ") : -1;
    const name = arrowIdx > 0 ? rawName.slice(0, arrowIdx) : rawName;
    const linkTarget = arrowIdx > 0 ? rawName.slice(arrowIdx + 4) : "";
    if (!name || name === "." || name === "..") continue;
    const directory = perms.startsWith("d");
    entries.push({
      name,
      path: `${normBase}/${name}`,
      directory,
      symlink: isSymlink,
      ...(linkTarget ? {linkTarget} : {}),
      size,
      permissions: perms,
      modified,
    });
  }
  entries.sort((a, b) => (a.directory === b.directory ? a.name.localeCompare(b.name) : a.directory ? -1 : 1));
  return entries;
}

async function runSftpBatch(host, password, batchCommands, baseEnvObj, timeout = 30_000, job = null) {
  return await withSshAuthEnv(host, password, baseEnvObj, async ({env}) => {
    if (job?.cancelled) throw new Error("已取消传输");
    const stage = job ? await fs.mkdtemp("/tmp/galiais-sftp-") : null;
    const batchFile = stage ? path.join(stage, "batch") : "-";
    if (stage) await fs.writeFile(batchFile, ["progress", ...batchCommands].join("\n") + "\n", {mode: 0o600});
    const args = [
      ...commonSshArgs(host, {batch: true}),
      ...(job ? ["-N"] : []), "-b", batchFile,
      host.alias,
    ];
    try { return await new Promise((resolve, reject) => {
      const child = job
        ? spawn("/usr/bin/script", ["-q", "-e", "-f", "-c", "/usr/bin/stty cols 160; exec " + ["/usr/bin/sftp", ...args].map(shellQuote).join(" "), "/dev/null"],
          {env: {...env, LC_ALL: "C", TERM: "xterm"}, detached: true, stdio: ["ignore", "pipe", "pipe"]})
        : spawn("/usr/bin/sftp", args, {env: {...env, LC_ALL: "C"}, stdio: ["pipe", "pipe", "pipe"]});
      if (job) job.child = child;
      let stdout = "", stderr = "", failure = null, forcedStop;
      let outputBytes = 0;
      const fail = error => {
        if (failure) return;
        failure = error;
        child.kill("SIGTERM");
        forcedStop = setTimeout(() => {
          try { if (job) process.kill(-child.pid, "SIGKILL"); else child.kill("SIGKILL"); }
          catch (error) { if (error.code !== "ESRCH") child.kill("SIGKILL"); }
        }, 1500);
      };
      if (job) { job.stop = () => fail(new Error("已取消传输")); if (job.cancelled) job.stop(); }
      const timer = setTimeout(() => fail(new Error("SFTP 操作超时")), timeout);
      child.stdout.setEncoding("utf8").on("data", data => {
        if (job) {
          stdout = (stdout + data).slice(-16_000);
          const updates = [...stdout.matchAll(/([^\r\n]+?)\s+(\d{1,3})%\s+[^\r\n]+/g)];
          const last = updates.at(-1);
          if (last) updateTransfer(job, {progressPercent: Math.min(100, Number(last[2])), progressLabel: last[1].trim().slice(0, 160)});
          return;
        }
        outputBytes += Buffer.byteLength(data);
        if (outputBytes > 4 * 1024 * 1024) fail(new Error("远端目录列表超过 4 MB，请在终端查看"));
        else if (!failure) stdout += data;
      });
      child.stderr.setEncoding("utf8").on("data", d => { stderr = (stderr + d).slice(-10_000); });
      child.stdin?.on("error", fail);
      child.once("error", fail);
      child.once("close", code => {
        clearTimeout(timer);
        clearTimeout(forcedStop);
        if (job) { job.child = null; job.stop = null; }
        if (failure) reject(failure);
        else if (code !== 0) reject(new Error((stderr || (job ? stdout : "")).trim().slice(-1500) || `SFTP 退出码 ${code}`));
        else resolve({stdout, stderr});
      });
      if (!job) child.stdin.end(batchCommands.join("\n") + "\n");
    }); } finally { if (stage) await fs.rm(stage, {recursive: true, force: true}); }
  });
}

function sftpQuote(p) {
  if (typeof p !== "string" || /[\r\n\0]/.test(p)) throw new Error("SFTP 路径不能包含换行或空字符");
  return `"${p.replace(/["\\]/g, "\\$&")}"`;
}

export async function sftpList({hostId, alias, remotePath = ".", env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const target = String(remotePath || ".");
  const cmds = target === "." ? ["pwd", "ls -lan"] : [`cd ${sftpQuote(target)}`, "pwd", "ls -lan"];
  const {stdout} = await runSftpBatch(host, password, cmds, baseEnvObj, 25_000);
  const pwdMatch = stdout.match(/Remote working directory:\s*(.+)/);
  const resolvedPath = (pwdMatch ? pwdMatch[1] : (target === "." ? "/" : target));
  return {path: resolvedPath, entries: parseSftpLs(stdout, resolvedPath)};
}

export async function sftpUpload({hostId, alias, localPath, remoteDir, env: baseEnvObj = process.env, job = null}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const absLocal = await assertHomeDir(localPath);
  const st = await fs.stat(absLocal);
  if (!st.isFile() && !st.isDirectory()) throw new Error("仅支持上传普通文件或目录");
  const destDir = typeof remoteDir === "string" && remoteDir ? remoteDir : ".";
  const fileName = path.basename(absLocal);
  const remoteTarget = destDir === "/" ? "/" + fileName : destDir.replace(/\/+$/, "") + "/" + fileName;
  return serializeMutation(host.id + ":" + remoteTarget, async () => {
    const listing = await sftpList({hostId: host.id, remotePath: destDir, env: baseEnvObj});
    if (listing.entries.some(entry => entry.name === fileName)) throw new Error("远端已有同名项目，请重命名后上传");
    const staged = destDir.replace(/\/+$/, "") + "/.galiais-upload-" + randomUUID();
    try {
      await runSftpBatch(host, password, ["put " + (st.isDirectory() ? "-R " : "") + sftpQuote(absLocal) + " " + sftpQuote(staged)], baseEnvObj, 1800_000, job);
      if (job?.cancelled) throw new Error("已取消传输");
      if (job) updateTransfer(job, {phase: "publishing"});
      const current = await sftpList({hostId: host.id, remotePath: destDir, env: baseEnvObj});
      if (current.entries.some(entry => entry.name === fileName)) throw new Error("上传期间远端出现同名项目，未覆盖");
      await runSftpBatch(host, password, ["rename " + sftpQuote(staged) + " " + sftpQuote(remoteTarget)], baseEnvObj);
      return {ok: true, remotePath: remoteTarget};
    } catch (error) {
      try { await removeStagedRemote(host, password, staged, st.isDirectory(), baseEnvObj); }
      catch { throw new Error(error.message + "；临时内容清理失败，可在远端删除 " + staged); }
      throw error;
    }
  });
}

export async function sftpDownload({hostId, alias, remotePath, localDir, env: baseEnvObj = process.env, job = null}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const absDir = await assertHomeDir(localDir || path.join(HOME, "workspace"), true);
  await fs.mkdir(absDir, {recursive: true});
  if (typeof remotePath !== "string" || !remotePath || /[\r\n\0]/.test(remotePath)) throw new Error("请指定有效的远端文件路径");
  const fileName = path.basename(remotePath.replace(/\/+$/, ""));
  if (!fileName || fileName === "." || fileName === "..") throw new Error("不能下载远端根目录");
  const localTarget = path.join(absDir, fileName);
  return serializeMutation(localTarget, async () => {
    if (await exists(localTarget)) throw new Error("本地已有同名文件，请先重命名或移走后重试");
    const stage = await fs.mkdtemp(path.join(absDir, ".sftp-download-"));
    const staged = path.join(stage, fileName);
    try {
      await runSftpBatch(host, password, ["get -R " + sftpQuote(remotePath) + " " + sftpQuote(staged)], baseEnvObj, 1800_000, job);
      if (job?.cancelled) throw new Error("已取消传输");
      if (job) updateTransfer(job, {phase: "publishing"});
      const stat = await fs.lstat(staged);
      if (stat.isFile()) await fs.copyFile(staged, localTarget, constants.COPYFILE_EXCL);
      else if (stat.isDirectory()) {
        if (await exists(localTarget)) throw new Error("下载期间本地已出现同名目录，请检查后重试");
        await fs.rename(staged, localTarget);
      } else throw new Error("远端内容不是普通文件或目录");
      return {ok: true, localPath: localTarget};
    } finally { await fs.rm(stage, {recursive: true, force: true}); }
  });
}

export async function sftpMkdir({hostId, alias, remotePath, env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  await runSftpBatch(host, password, [`mkdir ${sftpQuote(remotePath)}`], baseEnvObj, 20_000);
  return {ok: true};
}

export async function sftpDelete({hostId, alias, remotePath, directory = false, env: baseEnvObj = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const cmd = directory ? `rmdir ${sftpQuote(remotePath)}` : `rm ${sftpQuote(remotePath)}`;
  await runSftpBatch(host, password, [cmd], baseEnvObj, 20_000);
  return {ok: true};
}

async function removeStagedRemote(host, password, target, directory, env) {
  if (!path.basename(target).startsWith(".galiais-upload-")) throw new Error("临时目录标识无效");
  const commands = [];
  async function collect(remote, isDirectory, depth = 0) {
    if (depth > 64 || commands.length > 20_000) throw new Error("临时目录过大，请手动清理");
    if (isDirectory) {
      const listing = await sftpList({hostId: host.id, remotePath: remote, env});
      for (const entry of listing.entries) await collect(entry.path, entry.directory, depth + 1);
      commands.push("rmdir " + sftpQuote(remote));
    } else commands.push("rm " + sftpQuote(remote));
  }
  const parent = await sftpList({hostId: host.id, remotePath: path.posix.dirname(target), env});
  if (!parent.entries.some(entry => entry.name === path.posix.basename(target))) return;
  await collect(target, directory);
  await runSftpBatch(host, password, commands, env, 120_000);
}

function transferView(job) {
  return {id: job.id, hostId: job.hostId, direction: job.direction, localPath: job.localPath, remotePath: job.remotePath,
    phase: job.phase, progressPercent: job.progressPercent, progressLabel: job.progressLabel, error: job.error,
    startedAt: job.startedAt, resultPath: job.resultPath || null};
}
function updateTransfer(job, patch) {
  Object.assign(job, patch);
  tunnelNotify("ssh/transferChanged", {transfer: transferView(job)});
}
export function listSftpTransfers() { return {transfers: [...transfers.values()].map(transferView)}; }
export function startSftpTransfer(params) {
  if (!["upload", "download"].includes(params.direction) || typeof params.hostId !== "string") throw new Error("传输参数无效");
  if ([...transfers.values()].some(job => ["running", "publishing", "cancelling"].includes(job.phase))) throw new Error("请等待当前传输完成，或先取消");
  for (const [id, job] of transfers) {
    if (transfers.size < 20) break;
    if (!["running", "publishing", "cancelling"].includes(job.phase)) transfers.delete(id);
  }
  const job = {id: randomUUID(), hostId: params.hostId, direction: params.direction, localPath: params.localPath,
    remotePath: params.remotePath, phase: "running", progressPercent: null, progressLabel: "", error: null, startedAt: Date.now(), cancelled: false};
  transfers.set(job.id, job);
  updateTransfer(job, {});
  job.done = (async () => {
    try {
      const result = params.direction === "upload"
        ? await sftpUpload({...params, remoteDir: params.remotePath, job})
        : await sftpDownload({...params, localDir: params.localPath, job});
      updateTransfer(job, {phase: "completed", progressPercent: 100, resultPath: result.localPath || result.remotePath});
    } catch (error) {
      updateTransfer(job, {phase: job.cancelled ? "cancelled" : "failed", error: error.message});
    }
  })();
  return {transfer: transferView(job)};
}
export function cancelSftpTransfer({id}) {
  const job = transfers.get(id);
  if (!job) throw new Error("传输任务不存在");
  if (job.phase === "publishing") throw new Error("正在保存传输结果，请稍候");
  if (job.phase !== "running" && job.phase !== "cancelling") return {ok: true};
  job.cancelled = true;
  updateTransfer(job, {phase: "cancelling"});
  job.stop?.();
  return {ok: true};
}
export async function closeSftpTransfers() {
  for (const job of transfers.values()) {
    if (job.phase === "running" || job.phase === "cancelling") { job.cancelled = true; job.stop?.(); }
  }
  await Promise.all([...transfers.values()].map(job => job.done));
}

export async function sftpRename({hostId, alias, remotePath, newName, env = process.env}) {
  if (typeof newName !== "string" || !newName || newName === "." || newName === ".." || /[\/\r\n\0]/.test(newName)) throw new Error("名称无效");
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const parent = path.posix.dirname(remotePath);
  const listing = await sftpList({hostId: host.id, remotePath: parent, env});
  if (listing.entries.some(entry => entry.name === newName)) throw new Error("同名项目已存在");
  const target = path.posix.join(parent, newName);
  await runSftpBatch(host, password, ["rename " + sftpQuote(remotePath) + " " + sftpQuote(target)], env);
  return {ok: true, remotePath: target};
}
export async function sftpChmod({hostId, alias, remotePath, mode, env = process.env}) {
  if (typeof mode !== "string" || !/^[0-7]{3,4}$/.test(mode)) throw new Error("权限应为 3 或 4 位八进制数，例如 644");
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  await runSftpBatch(host, password, ["chmod " + mode + " " + sftpQuote(remotePath)], env);
  return {ok: true};
}

async function remoteText(host, password, remotePath, stage, env) {
  const listing = await sftpList({hostId: host.id, remotePath: path.posix.dirname(remotePath), env});
  const entry = listing.entries.find(item => item.name === path.posix.basename(remotePath));
  if (!entry || !entry.permissions.startsWith("-")) throw new Error("只能编辑普通文件");
  if (entry.size > MAX_DOTENV_BYTES) throw new Error("文件超过 1 MB，请下载后编辑");
  const local = path.join(stage, randomUUID());
  await runSftpBatch(host, password, ["get " + sftpQuote(remotePath) + " " + sftpQuote(local)], env, 30_000);
  const stat = await fs.stat(local);
  if (stat.size > MAX_DOTENV_BYTES) throw new Error("文件已增大到 1 MB 以上，请下载后编辑");
  const data = await fs.readFile(local);
  let content;
  try { content = new TextDecoder("utf-8", {fatal: true, ignoreBOM: true}).decode(data); }
  catch { throw new Error("文件不是 UTF-8 文本，请下载后编辑"); }
  if (content.includes("\0")) throw new Error("不能以文本方式编辑二进制文件");
  const mode = [0, 1, 2].map(group => {
    const p = entry.permissions.slice(1 + group * 3, 4 + group * 3);
    return (p[0] === "r" ? 4 : 0) + (p[1] === "w" ? 2 : 0) + (/[xst]/.test(p[2]) ? 1 : 0);
  }).join("");
  const special = (/[sS]/.test(entry.permissions[3]) ? 4 : 0) + (/[sS]/.test(entry.permissions[6]) ? 2 : 0) + (/[tT]/.test(entry.permissions[9]) ? 1 : 0);
  return {content, revision: createHash("sha256").update(data).digest("hex"), mode: special + mode};
}
export async function sftpReadText({hostId, alias, remotePath, env = process.env}) {
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  const stage = await fs.mkdtemp("/tmp/galiais-sftp-edit-");
  try { return await remoteText(host, password, remotePath, stage, env); }
  finally { await fs.rm(stage, {recursive: true, force: true}); }
}
export async function sftpWriteText({hostId, alias, remotePath, content, expectedRevision, env = process.env}) {
  if (typeof content !== "string" || content.includes("\0") || Buffer.byteLength(content) > MAX_DOTENV_BYTES) throw new Error("文本内容无效或超过 1 MB");
  if (!/^[a-f0-9]{64}$/.test(expectedRevision || "")) throw new Error("缺少原文件版本，请重新读取");
  const {host, password} = await resolveHostAndSecret({hostId, alias});
  return serializeMutation(host.id + ":" + remotePath, async () => {
    const stage = await fs.mkdtemp("/tmp/galiais-sftp-edit-");
    const remoteStage = path.posix.join(path.posix.dirname(remotePath), ".galiais-upload-" + randomUUID());
    let uploaded = false;
    try {
      const current = await remoteText(host, password, remotePath, stage, env);
      if (current.revision !== expectedRevision) throw new Error("远端文件已变化，未覆盖；请复制当前草稿后重新读取");
      const local = path.join(stage, "edited");
      await fs.writeFile(local, content, {mode: 0o600});
      uploaded = true;
      await runSftpBatch(host, password, ["put " + sftpQuote(local) + " " + sftpQuote(remoteStage),
        "chmod " + current.mode + " " + sftpQuote(remoteStage)], env);
      const latest = await remoteText(host, password, remotePath, stage, env);
      if (latest.revision !== expectedRevision) throw new Error("保存期间远端文件已变化，未覆盖；请复制草稿后重新读取");
      await runSftpBatch(host, password, ["rename " + sftpQuote(remoteStage) + " " + sftpQuote(remotePath)], env);
      uploaded = false;
      return {ok: true, revision: createHash("sha256").update(content).digest("hex")};
    } finally {
      await fs.rm(stage, {recursive: true, force: true});
      if (uploaded) await removeStagedRemote(host, password, remoteStage, false, env);
    }
  });
}
