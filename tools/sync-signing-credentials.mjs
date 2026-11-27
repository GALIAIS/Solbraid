#!/usr/bin/env node
// Solbraid 统一应用程序签名凭证同步工具（PC Debug/Release + 手机 Root chroot + 免 Root PRoot 三端统一证书）
// 用法：
//   node tools/sync-signing-credentials.mjs [--release] [--keystore <path>] [--alias <alias>] [--store-pass <pwd>] [--key-pass <pwd>] [--device <adb-serial>] [--adb <adb-path>]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");

function parseArgs(argv) {
  const opts = {
    release: false,
    keystore: "",
    alias: "",
    storePass: "",
    keyPass: "",
    device: "",
    adb: process.env.ADB_PATH || "",
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--release") opts.release = true;
    else if (a === "--keystore") opts.keystore = argv[++i] || "";
    else if (a === "--alias") opts.alias = argv[++i] || "";
    else if (a === "--store-pass") opts.storePass = argv[++i] || "";
    else if (a === "--key-pass") opts.keyPass = argv[++i] || "";
    else if (a === "--device" || a === "-s") opts.device = argv[++i] || "";
    else if (a === "--adb") opts.adb = argv[++i] || "";
  }
  return opts;
}

function readProperties(file) {
  const map = {};
  if (!fs.existsSync(file)) return map;
  for (const raw of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq > 0) map[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return map;
}

function formatProperties(obj) {
  const esc = v => String(v).replace(/\\/g, "/");
  return [
    "# Managed by tools/sync-signing-credentials.mjs (DO NOT COMMIT)",
    `storeFile=${esc(obj.storeFile)}`,
    `storePassword=${obj.storePassword}`,
    `keyAlias=${obj.keyAlias}`,
    `keyPassword=${obj.keyPassword}`,
    "",
  ].join("\n");
}

function findKeytool() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "keytool.exe" : "keytool"));
  candidates.push(process.platform === "win32" ? "keytool.exe" : "keytool");
  if (process.platform === "win32") {
    for (const base of ["C:\\Program Files\\Eclipse Adoptium", "C:\\Program Files\\Java", "C:\\Program Files\\Android\\Android Studio\\jbr"]) {
      if (!fs.existsSync(base)) continue;
      for (const entry of fs.readdirSync(base)) {
        const p = path.join(base, entry, "bin", "keytool.exe");
        if (fs.existsSync(p)) candidates.push(p);
      }
      const direct = path.join(base, "bin", "keytool.exe");
      if (fs.existsSync(direct)) candidates.push(direct);
    }
  }
  for (const c of candidates) {
    const r = spawnSync(c, ["-help"], {encoding: "utf8"});
    if (!r.error) return c;
  }
  return null;
}

function findAdb(explicit) {
  const candidates = [explicit];
  if (process.env.ANDROID_HOME) candidates.push(path.join(process.env.ANDROID_HOME, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb"));
  const localProps = readProperties(path.join(repoRoot, "local.properties"));
  if (localProps["sdk.dir"]) {
    const sdk = localProps["sdk.dir"].replace(/\\:/g, ":").replace(/\\\\/g, "\\");
    candidates.push(path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb"));
  }
  candidates.push(process.platform === "win32" ? "adb.exe" : "adb");
  for (const c of candidates.filter(Boolean)) {
    const r = spawnSync(c, ["version"], {encoding: "utf8"});
    if (!r.error && r.status === 0) return c;
  }
  return null;
}

function inspectCertSha256(keytool, ksPath, storePass, alias) {
  if (!keytool) return "";
  const r = spawnSync(keytool, ["-list", "-v", "-keystore", ksPath, "-storepass", storePass, "-alias", alias], {encoding: "utf8"});
  const out = `${r.stdout || ""}\n${r.stderr || ""}`;
  const m = /SHA256:\s*([0-9A-Fa-f:]+)/.exec(out);
  return m ? m[1].toUpperCase() : "";
}

const opts = parseArgs(process.argv.slice(2));
const userHome = os.homedir();
const pcGaliaisDir = path.join(userHome, ".galiais");
const isRelease = opts.release || (opts.alias && opts.alias !== "androiddebugkey");
const pcUnifiedKs = path.join(pcGaliaisDir, isRelease ? "solbraid-release.keystore" : "solbraid-signing.keystore");
const pcUserProps = path.join(pcGaliaisDir, isRelease ? "release-signing.properties" : "self-build-signing.properties");
const repoProps = path.join(repoRoot, isRelease ? "release-signing.properties" : "signing.properties");

// 1. 解析源密钥：显式参数 > 已有统一配置 > (仅 debug 模式) 本机默认 ~/.android/debug.keystore
const existingProps = fs.existsSync(repoProps) ? readProperties(repoProps) : readProperties(pcUserProps);
let srcKeystore = opts.keystore
  || (existingProps.storeFile && fs.existsSync(existingProps.storeFile) ? existingProps.storeFile : "")
  || (fs.existsSync(pcUnifiedKs) ? pcUnifiedKs : "")
  || (isRelease ? "" : path.join(userHome, ".android", "debug.keystore"));

if (!srcKeystore || !fs.existsSync(srcKeystore)) {
  console.error(`ERROR: 未找到源 keystore：${srcKeystore || pcUnifiedKs}`);
  process.exit(1);
}
const storePassword = opts.storePass || existingProps.storePassword || (isRelease ? "" : "android");
const keyAlias = opts.alias || existingProps.keyAlias || (isRelease ? "solbraid-release" : "androiddebugkey");
const keyPassword = opts.keyPass || existingProps.keyPassword || storePassword;
if (!storePassword || !keyPassword || !keyAlias) {
  console.error("ERROR: 缺少签名口令或 alias（Release 模式必须提供强口令）");
  process.exit(1);
}
if (isRelease && (keyAlias === "androiddebugkey" || storePassword === "android" || storePassword.length < 12)) {
  console.error("ERROR: Release 签名证书拒绝使用 debug 别名或弱口令");
  process.exit(1);
}

fs.mkdirSync(pcGaliaisDir, {recursive: true});
if (path.resolve(srcKeystore) !== path.resolve(pcUnifiedKs)) {
  fs.copyFileSync(srcKeystore, pcUnifiedKs);
  try { fs.chmodSync(pcUnifiedKs, 0o600); } catch {}
}

const keytool = findKeytool();
const certSha256 = inspectCertSha256(keytool, pcUnifiedKs, storePassword, keyAlias);
const ksFileSha256 = crypto.createHash("sha256").update(fs.readFileSync(pcUnifiedKs)).digest("hex");

// 2. 写入电脑端统一凭证（用户级 + 项目根目录，均已加入 .gitignore）
const pcPropsContent = formatProperties({storeFile: pcUnifiedKs, storePassword, keyAlias, keyPassword});
fs.writeFileSync(pcUserProps, pcPropsContent, {mode: 0o600});
fs.writeFileSync(repoProps, pcPropsContent, {mode: 0o600});

const summary = {
  ok: true,
  mode: isRelease ? "release" : "debug",
  pcKeystore: pcUnifiedKs,
  pcUserProps,
  repoProps,
  keyAlias,
  keystoreFileSha256: ksFileSha256,
  certSha256,
  phoneSynced: false,
  phoneTargets: [],
};

// 3. 如指定 --device，通过 adb shell stdin 管道直传（零 /sdcard 公共中转、零本地明文临时文件），以 0600 落盘到手机私有容器目录
if (opts.device) {
  const adb = findAdb(opts.adb);
  if (!adb) {
    console.error("ERROR: 未找到 adb，请通过 --adb 指定路径或设置 ANDROID_HOME");
    process.exit(1);
  }
  const adbArgs = ["-s", opts.device];
  const ksName = path.basename(pcUnifiedKs);
  const propsName = isRelease ? "release-signing.properties" : "self-build-signing.properties";
  const ksB64 = fs.readFileSync(pcUnifiedKs).toString("base64");
  const phoneProps = formatProperties({
    storeFile: `/home/agent/.galiais/${ksName}`,
    storePassword,
    keyAlias,
    keyPassword,
  });
  const propsB64 = Buffer.from(phoneProps, "utf8").toString("base64");

  const remoteScript = [
    `set -eu`,
    `APP_UID=$(stat -c %u /data/user/0/com.galiais.agent/files 2>/dev/null || echo 0)`,
    `APP_GID=$(stat -c %g /data/user/0/com.galiais.agent/files 2>/dev/null || echo 0)`,
    `IFS= read -r KS_B64`,
    `IFS= read -r PROPS_B64`,
    `KS_NAME=${JSON.stringify(ksName)}`,
    `PROPS_NAME=${JSON.stringify(propsName)}`,
    `IS_DEBUG=${isRelease ? "0" : "1"}`,
    `for ROOT in /data/user/0/com.galiais.agent/files/linux /data/user/0/com.galiais.agent/files/linux-proot; do`,
    `  if [ -d "$ROOT/home/agent" ]; then`,
    `    mkdir -p "$ROOT/home/agent/.galiais" "$ROOT/home/agent/.android"`,
    `    umask 077`,
    `    printf '%s' "$KS_B64" | base64 -d > "$ROOT/home/agent/.galiais/$KS_NAME.tmp"`,
    `    chmod 600 "$ROOT/home/agent/.galiais/$KS_NAME.tmp"`,
    `    mv -f "$ROOT/home/agent/.galiais/$KS_NAME.tmp" "$ROOT/home/agent/.galiais/$KS_NAME"`,
    `    if [ "$IS_DEBUG" = "1" ]; then`,
    `      cp -f "$ROOT/home/agent/.galiais/$KS_NAME" "$ROOT/home/agent/.android/debug.keystore"`,
    `      chmod 600 "$ROOT/home/agent/.android/debug.keystore"`,
    `      chown "$APP_UID:$APP_GID" "$ROOT/home/agent/.android/debug.keystore" 2>/dev/null || true`,
    `    fi`,
    `    printf '%s' "$PROPS_B64" | base64 -d > "$ROOT/home/agent/.galiais/$PROPS_NAME.tmp"`,
    `    chmod 600 "$ROOT/home/agent/.galiais/$PROPS_NAME.tmp"`,
    `    mv -f "$ROOT/home/agent/.galiais/$PROPS_NAME.tmp" "$ROOT/home/agent/.galiais/$PROPS_NAME"`,
    `    chown "$APP_UID:$APP_GID" "$ROOT/home/agent/.galiais/$KS_NAME" "$ROOT/home/agent/.galiais/$PROPS_NAME" 2>/dev/null || true`,
    `    echo "SYNCED:$ROOT"`,
    `  fi`,
    `done`,
  ].join("\n");
  const scriptB64 = Buffer.from(remoteScript, "utf8").toString("base64");
  const stdinData = `${ksB64}\n${propsB64}\n`;
  const execRes = spawnSync(
    adb,
    [...adbArgs, "shell", `su -c 'eval "$(echo ${scriptB64} | base64 -d)"'`],
    {input: stdinData, encoding: "utf8", maxBuffer: 4 * 1024 * 1024},
  );
  if (execRes.status !== 0) {
    console.error("ERROR: 手机端安全写入签名凭证失败:", execRes.stderr || execRes.stdout);
    process.exit(1);
  }
  const syncedRoots = (execRes.stdout || "").split(/\r?\n/).filter(l => l.startsWith("SYNCED:")).map(l => l.slice("SYNCED:".length));
  summary.phoneSynced = syncedRoots.length > 0;
  summary.phoneTargets = syncedRoots;
}

console.log(JSON.stringify(summary, null, 2));
