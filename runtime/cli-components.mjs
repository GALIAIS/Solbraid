import fs from "node:fs/promises";
import {createReadStream, createWriteStream} from "node:fs";
import path from "node:path";
import {randomUUID, createHash} from "node:crypto";
import {execFile, spawn} from "node:child_process";
import {pipeline} from "node:stream/promises";
import {Readable, Transform} from "node:stream";
import {gunzipSync} from "node:zlib";
import {pathToFileURL} from "node:url";
import {downloadEnvironment, githubDownloadUrl, redactNetworkText} from "./download-network.mjs";

const CURL_CANDIDATES = [
  "/opt/galiais-codex/usr/lib/chatgpt/resources/curl",
  "/usr/bin/curl",
  "/bin/curl",
  "/usr/local/bin/curl",
];
const TAR_CANDIDATES = ["/usr/bin/tar", "/bin/tar"];

async function resolveToolBinary(candidates) {
  for (const candidate of candidates) {
    try {
      const st = await fs.stat(candidate);
      if (st.isFile() && (st.mode & 0o111)) return candidate;
    } catch {}
  }
  return null;
}

export const cliComponents = JSON.parse(await fs.readFile(new URL("./components.json", import.meta.url), "utf8"));
const AGENT_HOME = process.env.HOME || "/home/agent";
const CLI_ROOT = path.join(AGENT_HOME, ".galiais", "cli");
const IDS = ["codex", "claude", "antigravity"];
const VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const RELEASE_DIR = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?-[a-f0-9]{8}$/;
const CLAUDE_DOWNLOADS = "https://storage.googleapis.com/claude-code-dist-86c565f3-f756-42ad-8dfa-d59b1c096819/claude-code-releases";
const AGY_DOWNLOADS = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/";
const BIN_NAMES = {codex: "codex", claude: "claude", antigravity: "agy"};

function assertId(id) { if (!IDS.includes(id)) throw new Error("未知 CLI"); return id; }
export function bundledCliInstallation(id) {
  assertId(id);
  return {
    id, version: cliComponents.engines[id], bundled: true, release: null,
    binary: id === "claude" ? "/opt/galiais-codex/claude/current/claude" : "/opt/galiais-codex/usr/lib/chatgpt/resources/" + BIN_NAMES[id],
    ...(id === "claude" ? {sdkVersion: cliComponents.sdk.version, sdkDirectory: "/opt/galiais-codex/claude-sdk-" + cliComponents.sdk.version} : {}),
  };
}
const bundled = bundledCliInstallation;
async function readJson(file, fallback = null) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); }
  catch (error) { if (error.code === "ENOENT") return fallback; throw error; }
}
async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), {recursive: true, mode: 0o700});
  const temporary = file + "." + randomUUID() + ".tmp";
  try { await fs.writeFile(temporary, JSON.stringify(value), {mode: 0o600, flag: "wx"}); await fs.rename(temporary, file); }
  finally { await fs.rm(temporary, {force: true}); }
}
function activeFile(id) { return path.join(CLI_ROOT, assertId(id), "active.json"); }

export async function resolveCliInstallation(id) {
  const fallback = bundled(id);
  try {
    const active = await readJson(activeFile(id));
    if (!active) return fallback;
    if (!VERSION.test(active.version) || !RELEASE_DIR.test(active.release)) throw new Error("更新记录格式无效");
    // 若 APK 升级携带了更高版本的内置 CLI，优先采用内置版本，避免静默降级到旧覆写
    if (compareCliVersions(active.version, fallback.version) < 0) return fallback;
    const directory = path.join(CLI_ROOT, id, "versions", active.release);
    const actual = await fs.realpath(directory);
    if (actual !== directory) throw new Error("更新目录不能是符号链接");
    const complete = await readJson(path.join(directory, "release.json"));
    if (complete?.id !== id || complete.version !== active.version) throw new Error("更新组件不完整");
    const binary = path.join(directory, BIN_NAMES[id]);
    if (!(await fs.lstat(binary)).isFile()) throw new Error("更新的执行文件缺失");
    if (id === "codex" && !(await fs.lstat(path.join(directory, "codex-code-mode-host"))).isFile()) throw new Error("Code Mode Host 缺失");
    const sdkDirectory = id === "claude" ? path.join(directory, "sdk") : null;
    if (sdkDirectory) {
      if (await fs.realpath(sdkDirectory) !== sdkDirectory || !(await fs.lstat(path.join(sdkDirectory, "sdk.mjs"))).isFile()) throw new Error("Claude SDK 缺失");
      const metadata = await readJson(path.join(sdkDirectory, "package.json"));
      if (!VERSION.test(complete.sdkVersion || "") || metadata?.version !== complete.sdkVersion || metadata?.claudeCodeVersion !== active.version) throw new Error("Claude CLI 与 SDK 不匹配");
    }
    return {...fallback, version: active.version, bundled: false, binary, release: active.release,
      ...(sdkDirectory ? {sdkDirectory, sdkVersion: complete.sdkVersion} : {})};
  } catch (error) {
    return {...fallback, warning: "更新版本不可用，已使用内置版本：" + error.message};
  }
}

export function compareCliVersions(left, right) {
  if (!VERSION.test(left) || !VERSION.test(right)) throw new Error("CLI 版本号无效");
  const parse = value => { const [core, ...suffix] = value.split("-"); return {core: core.split(".").map(Number), pre: suffix.join("-")}; };
  const a = parse(left), b = parse(right);
  for (let i = 0; i < 3; i++) if (a.core[i] !== b.core[i]) return Math.sign(a.core[i] - b.core[i]);
  if (!a.pre || !b.pre) return a.pre === b.pre ? 0 : a.pre ? -1 : 1;
  const aa = a.pre.split("."), bb = b.pre.split(".");
  for (let i = 0; i < Math.max(aa.length, bb.length); i++) {
    if (aa[i] === bb[i]) continue;
    if (aa[i] === undefined || bb[i] === undefined) return aa[i] === undefined ? -1 : 1;
    if (/^\d+$/.test(aa[i]) && /^\d+$/.test(bb[i])) return Math.sign(Number(aa[i]) - Number(bb[i]));
    if (/^\d+$/.test(aa[i]) !== /^\d+$/.test(bb[i])) return /^\d+$/.test(aa[i]) ? -1 : 1;
    return aa[i] < bb[i] ? -1 : 1;
  }
  return 0;
}

export async function cliPathDirectories() {
  const selected = await Promise.all(IDS.map(resolveCliInstallation));
  return [...new Set(selected.map(item => path.dirname(item.binary)))];
}

async function hashFile(file, algorithm, encoding = "hex") {
  const digest = createHash(algorithm);
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest(encoding);
}

export function createCliUpdater({notify, getNetwork, baseEnv}) {
  const statuses = new Map(), candidates = new Map(), children = new Set(), checks = new Map();
  const boot = Promise.all(IDS.map(resolveCliInstallation)).then(items => new Map(items.map(item => [item.id, item])));
  let operation = null;
  let installationReserved = false;
  let initialization = null;
  let closed = false;

  function publish(id, patch) {
    if (patch.phase && operation?.id === id) operation.phase = patch.phase;
    const next = {...statuses.get(id), id, ...patch};
    statuses.set(id, next);
    notify("cli/status", next);
    return next;
  }
  function cancelled() { if (closed || operation?.cancelled) throw new Error("已取消更新"); }
  function signal(child) { if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGTERM"); }
  function run(command, args, options = {}, owned = null) {
    if (closed) return Promise.reject(new Error("运行环境正在关闭"));
    return new Promise((resolve, reject) => {
      const child = execFile(command, args, {timeout: 30_000, maxBuffer: 4 * 1024 * 1024, killSignal: "SIGKILL", ...options},
        (error, stdout, stderr) => {
          children.delete(child);
          if (owned?.child === child) owned.child = null;
          if (error) reject(new Error(redactNetworkText(stderr || error.message).trim(), {cause: error}));
          else resolve({stdout, stderr});
        });
      children.add(child);
      if (owned) { owned.child = child; if (owned.cancelled) signal(child); }
    });
  }
  function environment() {
    const original = baseEnv();
    return {...original, ...downloadEnvironment(getNetwork(), original)};
  }
  function curlArgs() { return ["--fail", "--silent", "--show-error", "--location", "--connect-timeout", "15", ...(getNetwork().preferIpv4 ? ["--ipv4"] : [])]; }
  async function textAt(url) {
    if (!url.startsWith("https://")) throw new Error("更新元数据必须使用 HTTPS");
    const curlBin = await resolveToolBinary(CURL_CANDIDATES);
    if (curlBin) {
      return (await run(curlBin, [...curlArgs(), "--proto", "=https", "--proto-redir", "=https", "--max-time", "25", "--max-filesize", "4194304", url], {env: environment()})).stdout.trim();
    }
    const resp = await fetch(url, {headers: {"User-Agent": "Solbraid-CLI-Updater/1.0"}, signal: AbortSignal.timeout(25_000)});
    if (!resp.ok) throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
    const body = await resp.text();
    if (Buffer.byteLength(body, "utf8") > 4 * 1024 * 1024) throw new Error("更新元数据超过大小上限");
    return body.trim();
  }
  async function jsonAt(url) { return JSON.parse(await textAt(url)); }
  function initialize() {
    if (!initialization) initialization = boot.then(async running => {
      for (const id of IDS) {
        const current = running.get(id);
        if (!statuses.has(id)) publish(id, {currentVersion: current.version, installedVersion: current.version, bundled: current.bundled, canRestore: !current.bundled || Boolean(current.warning),
          latestVersion: null, phase: "idle", checkedAt: null, updateAvailable: false, pendingRestart: false, error: current.warning || null});
        if (current.warning) continue;
        const active = await readJson(activeFile(id));
        const directory = path.join(CLI_ROOT, id, "versions");
        let entries;
        try { entries = await fs.readdir(directory, {withFileTypes: true}); }
        catch (error) { if (error.code === "ENOENT") continue; throw error; }
        if (await fs.realpath(directory) !== directory) throw new Error("CLI 版本目录不能是符号链接");
        const retained = new Set([active?.release, active?.previous]);
        for (const entry of entries) {
          if (!entry.isDirectory() || !RELEASE_DIR.test(entry.name) || retained.has(entry.name)) continue;
          const target = path.join(directory, entry.name);
          const metadata = await readJson(path.join(target, "release.json"));
          if (metadata?.id === id && VERSION.test(metadata.version)) await fs.rm(target, {recursive: true});
        }
      }
      return running;
    }).catch(error => { initialization = null; throw error; });
    return initialization;
  }

  async function latest(id) {
    const arm = process.arch === "arm64";
    if (!arm && process.arch !== "x64") throw new Error("当前架构不受支持");
    if (id === "codex") {
      const release = await jsonAt("https://api.github.com/repos/openai/codex/releases/latest");
      const version = String(release.tag_name || "").replace(/^rust-v/, "");
      if (!VERSION.test(version)) throw new Error("Codex 发布版本无效");
      const target = arm ? "aarch64" : "x86_64";
      const files = ["codex", "codex-code-mode-host"].map(name => {
        const assetName = name + "-" + target + "-unknown-linux-musl.tar.gz";
        const asset = release.assets?.find(item => item.name === assetName);
        if (!asset || !/^sha256:[a-f0-9]{64}$/.test(asset.digest || "") || !asset.browser_download_url?.startsWith("https://github.com/openai/codex/releases/download/")) throw new Error("Codex 发布缺少经过校验的 " + name);
        return {name, url: asset.browser_download_url, digest: asset.digest.slice(7), algorithm: "sha256", archive: true, size: asset.size};
      });
      return {id, version, files, sourceUrl: release.html_url};
    }
    if (id === "claude") {
      const version = await textAt(CLAUDE_DOWNLOADS + "/latest");
      if (!VERSION.test(version)) throw new Error("Claude 发布版本无效");
      const manifest = await jsonAt(CLAUDE_DOWNLOADS + "/" + version + "/manifest.json");
      const platform = arm ? "linux-arm64" : "linux-x64";
      const binary = manifest.platforms?.[platform];
      if (manifest.version !== version || !/^[a-f0-9]{64}$/.test(binary?.checksum || "") || binary.binary !== "claude") throw new Error("Claude 发布缺少当前架构的校验信息");
      const sdk = await jsonAt("https://registry.npmjs.org/@anthropic-ai/claude-agent-sdk/latest");
      if (sdk.claudeCodeVersion !== version || !VERSION.test(sdk.version) || !/^sha512-[A-Za-z0-9+/]{86}==$/.test(sdk.dist?.integrity || "") ||
          !sdk.dist.tarball?.startsWith("https://registry.npmjs.org/@anthropic-ai/claude-agent-sdk/")) throw new Error("官方 CLI 与 SDK 尚无匹配版本，请稍后检查");
      return {id, version, sdkVersion: sdk.version, sourceUrl: "https://code.claude.com/docs/en/setup",
        files: [
          {name: "claude", url: CLAUDE_DOWNLOADS + "/" + version + "/" + platform + "/claude", digest: binary.checksum, algorithm: "sha256", size: binary.size},
          {name: "sdk", url: sdk.dist.tarball, digest: sdk.dist.integrity.slice(7), algorithm: "sha512", encoding: "base64", sdk: true},
        ]};
    }
    const manifest = await jsonAt(AGY_DOWNLOADS + (arm ? "linux_arm64" : "linux_amd64") + ".json");
    if (!VERSION.test(manifest.version) || !/^[a-f0-9]{128}$/.test(manifest.sha512 || "") || !manifest.url?.startsWith("https://storage.googleapis.com/antigravity-public/antigravity-cli/")) throw new Error("Antigravity 发布清单无效");
    return {id, version: manifest.version, sourceUrl: "https://antigravity.google/cli",
      files: [{name: "agy", url: manifest.url, digest: manifest.sha512, algorithm: "sha512", archive: manifest.url.endsWith(".tar.gz")}]};
  }

  function checkOne(id) {
    assertId(id);
    if (checks.has(id)) return checks.get(id);
    const pending = checkOneNow(id).finally(() => checks.delete(id));
    checks.set(id, pending);
    return pending;
  }
  async function checkOneNow(id) {
    assertId(id);
    const running = await initialize();
    if (operation?.id === id) return statuses.get(id);
    publish(id, {phase: "checking", error: null});
    try {
      const release = await latest(id);
      candidates.set(id, {...release, checkedAt: Date.now()});
      const installed = await resolveCliInstallation(id);
      return publish(id, {phase: "idle", checkedAt: Date.now(), latestVersion: release.version,
        installedVersion: installed.version, updateAvailable: compareCliVersions(release.version, installed.version) > 0,
        bundled: installed.bundled, canRestore: !installed.bundled || Boolean(installed.warning), pendingRestart: installed.release !== running.get(id).release,
        sourceUrl: release.sourceUrl, error: installed.warning || null});
    } catch (error) {
      candidates.delete(id);
      return publish(id, {phase: "failed", error: redactNetworkText(error.message), updateAvailable: false});
    }
  }
  async function check({agentId} = {}) {
    if (agentId) return {updates: [await checkOne(agentId)]};
    const results = await Promise.allSettled(IDS.map(checkOne));
    for (const result of results) if (result.status === "rejected") throw result.reason;
    return {updates: results.map(result => result.value)};
  }
  async function status() { await initialize(); return {updates: [...statuses.values()]}; }

  async function download(file, destination, progress, owned) {
    cancelled();
    const source = githubDownloadUrl(file.url, getNetwork());
    let tracking = false;
    const timer = setInterval(() => {
      if (tracking) return;
      tracking = true;
      fs.stat(destination).then(stat => progress(stat.size), error => {
        if (error.code !== "ENOENT") { owned.failure = error; signal(owned.child); }
      }).finally(() => { tracking = false; });
    }, 400);
    try {
      const curlBin = await resolveToolBinary(CURL_CANDIDATES);
      const maxSize = file.size || 700 * 1024 * 1024;
      if (curlBin) {
        await run(curlBin, [...curlArgs(), "--proto", "=http,https", "--proto-redir", "=http,https", "--retry", "2",
          "--max-filesize", String(maxSize), "--max-time", "600", "--output", destination, source], {env: environment(), timeout: 610_000}, owned);
      } else {
        const resp = await fetch(source, {headers: {"User-Agent": "Solbraid-CLI-Updater/1.0"}, signal: AbortSignal.timeout(600_000)});
        if (!resp.ok || !resp.body) throw new Error(`下载失败 HTTP ${resp.status}`);
        let received = 0;
        const guard = new Transform({transform(chunk, encoding, done) {
          received += chunk.length;
          if (received > maxSize) done(new Error("下载文件超过大小上限"));
          else done(null, chunk);
        }});
        await pipeline(Readable.fromWeb(resp.body), guard, createWriteStream(destination, {flags: "w", mode: 0o600}));
      }
      cancelled();
      if (owned.failure) throw owned.failure;
      progress((await fs.stat(destination)).size);
      if (await hashFile(destination, file.algorithm, file.encoding || "hex") !== file.digest) throw new Error("下载校验失败，未修改现有版本");
    } finally { clearInterval(timer); }
  }

  async function extractNative(archive, destination, name, owned) {
    const tarBin = (await resolveToolBinary(TAR_CANDIDATES)) || "/usr/bin/tar";
    const listed = await run(tarBin, ["-tzf", archive], {env: environment()}, owned);
    const entries = listed.stdout.trim().split(/\r?\n/);
    const expected = name === "agy" ? /^antigravity$/ : new RegExp("^" + name + "-(?:aarch64|x86_64)-unknown-linux-musl$");
    if (entries.length !== 1 || !expected.test(entries[0])) throw new Error("CLI 归档结构不符合发布格式");
    cancelled();
    const child = spawn(tarBin, ["-xzOf", archive, "--", entries[0]], {stdio: ["ignore", "pipe", "pipe"], env: environment()});
    children.add(child); owned.child = child;
    const timeout = setTimeout(() => { if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL"); }, 60_000);
    let stderr = "", size = 0;
    child.stderr.on("data", data => { stderr = (stderr + data).slice(-1500); });
    const ended = new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("close", code => code === 0 ? resolve() : reject(new Error(stderr || "CLI 解包失败")));
    });
    const guard = new Transform({transform(chunk, encoding, done) {
      size += chunk.length;
      if (size > 700 * 1024 * 1024) done(new Error("CLI 解包超过大小上限")); else done(null, chunk);
    }});
    try {
      await Promise.all([ended, pipeline(child.stdout, guard, createWriteStream(destination, {flags: "wx", mode: 0o700}))]);
    } finally {
      clearTimeout(timeout);
      signal(child);
      await ended.catch(() => {});
      children.delete(child);
      if (owned.child === child) owned.child = null;
    }
  }

  async function extractSdk(archive, directory, release) {
    const bytes = gunzipSync(await fs.readFile(archive), {maxOutputLength: 64 * 1024 * 1024});
    await fs.mkdir(directory, {recursive: true, mode: 0o700});
    const written = new Set();
    for (let offset = 0; offset + 512 <= bytes.length;) {
      const header = bytes.subarray(offset, offset + 512);
      if (header.every(value => value === 0)) break;
      const prefix = header.subarray(345, 500).toString("utf8").split("\0")[0];
      const name = (prefix ? prefix + "/" : "") + header.subarray(0, 100).toString("utf8").split("\0")[0];
      const storedChecksum = Number.parseInt(header.subarray(148, 156).toString("ascii").replace(/\0/g, "").trim(), 8);
      const checksum = header.reduce((sum, value, index) => sum + (index >= 148 && index < 156 ? 32 : value), 0);
      if (checksum !== storedChecksum) throw new Error("SDK 归档头校验失败");
      const sizeText = header.subarray(124, 136).toString("ascii").replace(/\0/g, "").trim();
      if (!/^[0-7]+$/.test(sizeText)) throw new Error("SDK 归档大小无效");
      const size = Number.parseInt(sizeText, 8), next = offset + 512 + Math.ceil(size / 512) * 512;
      if (next > bytes.length || !name.startsWith("package/")) throw new Error("SDK 归档结构无效");
      const relative = name.slice(8);
      if (relative.includes("\\") || path.isAbsolute(relative) || relative.split("/").some(part => part === ".." || part === ".")) throw new Error("SDK 归档路径无效");
      if ([0, 48].includes(header[156])) {
        if (!relative || written.has(relative)) throw new Error("SDK 文件重复");
        written.add(relative);
        const target = path.join(directory, relative);
        await fs.mkdir(path.dirname(target), {recursive: true});
        await fs.writeFile(target, bytes.subarray(offset + 512, offset + 512 + size), {flag: "wx", mode: 0o600});
      } else if (header[156] !== 53) throw new Error("SDK 归档包含不支持的链接");
      offset = next;
    }
    const metadata = await readJson(path.join(directory, "package.json"));
    if (metadata?.version !== release.sdkVersion || metadata.claudeCodeVersion !== release.version ||
        !written.has("sdk.mjs") || !written.has("core.mjs")) throw new Error("SDK 与 Claude CLI 版本不一致");
    const sdk = await import(pathToFileURL(path.join(directory, "sdk.mjs")).href);
    if (typeof sdk.query !== "function") throw new Error("SDK 未提供所需的会话接口");
  }

  async function verifyBinary(file) {
    const handle = await fs.open(file, "r");
    try {
      const bytes = Buffer.alloc(20);
      await handle.read(bytes, 0, bytes.length, 0);
      if (bytes.readUInt32BE(0) !== 0x7f454c46 || bytes[4] !== 2 || bytes[5] !== 1 ||
          bytes.readUInt16LE(18) !== (process.arch === "arm64" ? 183 : 62)) throw new Error("CLI 文件架构不匹配");
    } finally { await handle.close(); }
    await fs.chmod(file, 0o700);
  }

  async function install(params) {
    if (closed) throw new Error("运行环境正在关闭");
    if (installationReserved || operation) throw new Error("已有 CLI 更新任务");
    installationReserved = true;
    try { return await installNow(params); }
    finally { installationReserved = false; }
  }

  async function installNow({agentId, version}) {
    const id = assertId(agentId);
    const running = await initialize();
    if (operation) throw new Error("已有 CLI 更新任务");
    const before = await resolveCliInstallation(id);
    if (before.release !== running.get(id).release) throw new Error("请先重启环境，使已安装的版本生效");
    await checks.get(id);
    let release = candidates.get(id);
    if (!release || Date.now() - release.checkedAt > 10 * 60_000) { await checkOne(id); release = candidates.get(id); }
    if (!release || release.version !== version || !statuses.get(id).updateAvailable) throw new Error("可用版本已变化，请重新检查更新");
    const owned = {id, child: null, cancelled: false, phase: "downloading"};
    operation = owned;
    let stage, publishedDir, committed = false;
    try {
      const stateRoot = path.join(AGENT_HOME, ".galiais");
      await fs.mkdir(stateRoot, {recursive: true, mode: 0o700});
      if (await fs.realpath(stateRoot) !== stateRoot) throw new Error("CLI 配置目录不能是符号链接");
      const root = path.join(CLI_ROOT, id);
      await fs.mkdir(path.join(root, "versions"), {recursive: true, mode: 0o700});
      if (await fs.realpath(root) !== root) throw new Error("CLI 更新目录不能是符号链接");
      if (await fs.realpath(path.join(root, "versions")) !== path.join(root, "versions")) throw new Error("CLI 版本目录不能是符号链接");
      const space = await fs.statfs(root);
      const required = Math.max(1024 * 1024 * 1024, release.files.reduce((sum, file) => sum + (file.size || 150 * 1024 * 1024), 0) * 4);
      if (space.bavail * space.bsize < required) throw new Error("存储空间不足，更新至少需要 " + Math.ceil(required / 1024 / 1024) + " MB");
      stage = await fs.mkdtemp(path.join(root, ".staging-"));
      publish(id, {phase: "downloading", error: null, downloadedBytes: 0, totalBytes: release.files.every(file => file.size) ? release.files.reduce((sum, file) => sum + file.size, 0) : null});
      let downloaded = 0;
      for (const file of release.files) {
        const archive = path.join(stage, file.name + ".download");
        const completedBefore = downloaded;
        await download(file, archive, bytes => publish(id, {downloadedBytes: completedBefore + bytes}), owned);
        downloaded += (await fs.stat(archive)).size;
        cancelled();
        if (file.sdk) await extractSdk(archive, path.join(stage, "sdk"), release);
        else if (file.archive) await extractNative(archive, path.join(stage, file.name), file.name, owned);
        else await fs.rename(archive, path.join(stage, file.name));
        await fs.rm(archive, {force: true});
      }
      cancelled();
      publish(id, {phase: "verifying"});
      for (const name of id === "codex" ? ["codex", "codex-code-mode-host"] : [BIN_NAMES[id]]) await verifyBinary(path.join(stage, name));
      const validationHome = path.join(stage, ".validation");
      await fs.mkdir(validationHome);
      const env = {...environment(), HOME: validationHome, CODEX_HOME: path.join(validationHome, ".codex"), CLAUDE_CONFIG_DIR: path.join(validationHome, ".claude"), DISABLE_AUTOUPDATER: "1"};
      const versionResult = await run(path.join(stage, BIN_NAMES[id]), ["--version"], {env, cwd: stage, timeout: 20_000}, owned);
      const actual = (versionResult.stdout || versionResult.stderr).match(/\b\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?/)?.[0];
      if (actual !== release.version) throw new Error("CLI 实际版本与发布清单不一致");
      await fs.rm(validationHome, {recursive: true, force: true});
      cancelled();
      owned.phase = "publishing";
      publish(id, {phase: "publishing"});
      await writeJson(path.join(stage, "release.json"), {id, version: release.version, sdkVersion: release.sdkVersion || null, sourceUrl: release.sourceUrl});
      const directoryName = release.version + "-" + randomUUID().slice(0, 8);
      publishedDir = path.join(root, "versions", directoryName);
      await fs.rename(stage, publishedDir); stage = null;
      await writeJson(activeFile(id), {version: release.version, release: directoryName, previous: before.release, installedAt: Date.now()});
      committed = true;
      return {update: publish(id, {phase: "completed", installedVersion: release.version, bundled: false, canRestore: true, updateAvailable: false, pendingRestart: true, error: null})};
    } catch (error) {
      publish(id, {phase: owned.cancelled ? "cancelled" : "failed", error: owned.cancelled ? null : redactNetworkText(error.message)});
      throw error;
    } finally {
      try {
        if (stage) await fs.rm(stage, {recursive: true, force: true});
        if (publishedDir && !committed) await fs.rm(publishedDir, {recursive: true, force: true});
      } finally { operation = null; }
    }
  }

  function cancel({agentId}) {
    if (!operation || operation.id !== agentId) return {ok: true, cancelled: false};
    if (operation.phase === "publishing") throw new Error("正在完成更新，请等待结果");
    operation.cancelled = true;
    publish(agentId, {phase: "cancelling"});
    const child = operation.child;
    signal(child);
    setTimeout(() => { if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGKILL"); }, 2000).unref();
    return {ok: true, cancelled: true};
  }

  async function restore({agentId}) {
    const id = assertId(agentId);
    if (operation || installationReserved) throw new Error("请先结束当前更新任务");
    installationReserved = true;
    try {
      const running = await initialize();
      await checks.get(id);
      const root = path.dirname(activeFile(id));
      try { if (await fs.realpath(root) !== root) throw new Error("CLI 更新目录不能是符号链接"); }
      catch (error) { if (error.code !== "ENOENT") throw error; }
      await fs.rm(activeFile(id), {force: true});
      return {update: publish(id, {phase: "completed", installedVersion: cliComponents.engines[id], bundled: true, canRestore: false,
        pendingRestart: !running.get(id).bundled, updateAvailable: false, error: null})};
    } finally { installationReserved = false; }
  }

  async function markApplied(id) {
    const valid = assertId(id);
    const running = await initialize();
    const resolved = await resolveCliInstallation(valid);
    running.set(valid, resolved);
    return publish(valid, {
      currentVersion: resolved.version,
      installedVersion: resolved.version,
      bundled: resolved.bundled,
      canRestore: !resolved.bundled || Boolean(resolved.warning),
      pendingRestart: false,
      error: resolved.warning || null,
    });
  }

  function close() {
    closed = true;
    if (operation) operation.cancelled = true;
    for (const child of children) signal(child);
    setTimeout(() => {
      for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    }, 2000).unref();
  }
  return {check, status, install, cancel, restore, markApplied, close};
}
