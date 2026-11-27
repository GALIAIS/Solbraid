// Solbraid Computer Use 三端原生程序交叉编译、SSH 自动远程部署与局域网发现引擎（兼容 Root chroot 与免 Root PRoot）
import fs from "node:fs/promises";
import fss from "node:fs";
import os from "node:os";
import path from "node:path";
import dgram from "node:dgram";
import zlib from "node:zlib";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {createHash, randomBytes} from "node:crypto";
import {fileURLToPath} from "node:url";

const execFileAsync = promisify(execFile);
const __dirname = path.dirname(fileURLToPath(import.meta.url));

export const NATIVE_TARGETS = [
  {os: "windows", arch: "amd64", label: "Windows x64", ext: ".exe"},
  {os: "windows", arch: "arm64", label: "Windows ARM64", ext: ".exe"},
  {os: "darwin", arch: "arm64", label: "macOS Apple Silicon", ext: ""},
  {os: "darwin", arch: "amd64", label: "macOS Intel x64", ext: ""},
  {os: "linux", arch: "amd64", label: "Linux x64", ext: ""},
  {os: "linux", arch: "arm64", label: "Linux ARM64", ext: ""},
];

function validTarget(targetOs, targetArch) {
  return NATIVE_TARGETS.find(t => t.os === targetOs && t.arch === targetArch) || null;
}

function sha256Buffer(buf) {
  return createHash("sha256").update(buf).digest("hex");
}

async function sha256File(file) {
  return sha256Buffer(await fs.readFile(file));
}

function goBinaryFilename(t) {
  return `solbraid-desktop-${t.os}-${t.arch}${t.ext}`;
}

async function computeSourceHash(srcDir) {
  const entries = (await fs.readdir(srcDir))
    .filter(f => (f.endsWith(".go") && !f.endsWith("_test.go")) || f === "go.mod")
    .sort();
  const h = createHash("sha256");
  for (const name of entries) {
    h.update(name + "\n");
    h.update(await fs.readFile(path.join(srcDir, name)));
  }
  return h.digest("hex");
}

/** 定位或从 APK 内置压缩包解压三端原生 Go 源码目录。 */
export async function resolveNativeSourceDir(home) {
  const candidates = [
    path.resolve(__dirname, "..", "pc-agent-native"),
    path.join(__dirname, "pc-agent-native"),
  ];
  for (const dir of candidates) {
    if (fss.existsSync(path.join(dir, "go.mod")) && fss.existsSync(path.join(dir, "main.go"))) {
      return {srcDir: dir, sourceHash: await computeSourceHash(dir), origin: "workspace"};
    }
  }
  const tarPath = path.join(__dirname, "pc-agent-native.tgz");
  if (!fss.existsSync(tarPath)) {
    throw new Error("未找到内置的 pc-agent-native 源码包（pc-agent-native.tgz）");
  }
  const tarHash = await sha256File(tarPath);
  const extractDir = path.join(home, ".galiais", "computer-use", "src-native");
  const stampFile = path.join(extractDir, ".tar.sha256");
  let currentStamp = "";
  try { currentStamp = (await fs.readFile(stampFile, "utf8")).trim(); } catch {}
  if (currentStamp !== tarHash || !fss.existsSync(path.join(extractDir, "go.mod"))) {
    await fs.rm(extractDir, {recursive: true, force: true});
    await fs.mkdir(extractDir, {recursive: true, mode: 0o700});
    const tarBuf = zlib.gunzipSync(await fs.readFile(tarPath));
    let off = 0;
    while (off + 512 <= tarBuf.length) {
      const hdr = tarBuf.subarray(off, off + 512);
      if (hdr.every(b => b === 0)) break;
      const name = hdr.subarray(0, 100).toString("utf8").replace(/\0.*$/, "").trim();
      const sizeOct = hdr.subarray(124, 136).toString("ascii").replace(/\0.*$/, "").trim();
      const size = parseInt(sizeOct, 8) || 0;
      const typeflag = hdr[156];
      const isRegularFile = typeflag === 0 || typeflag === 0x30; // '\0' or '0'
      off += 512;
      if (isRegularFile && name && !name.includes("..") && !path.isAbsolute(name)) {
        const dst = path.join(extractDir, path.basename(name));
        await fs.writeFile(dst, tarBuf.subarray(off, off + size));
      }
      off += Math.ceil(size / 512) * 512;
    }
    await fs.writeFile(stampFile, tarHash + "\n", "utf8");
  }
  return {srcDir: extractDir, sourceHash: await computeSourceHash(extractDir), origin: "bundled"};
}

/** 检测 Go 交叉编译工具链（优先使用免 Root 托管目录 ~/.galiais/toolchains/go）。 */
export async function detectGoToolchain(home) {
  const exe = process.platform === "win32" ? "go.exe" : "go";
  const managedRoot = path.join(home, ".galiais", "toolchains", "go");
  const candidates = [
    path.join(managedRoot, "current", "bin", exe),
    path.join(managedRoot, "bin", exe),
    "/usr/local/go/bin/" + exe,
    exe,
  ];
  for (const bin of candidates) {
    try {
      const {stdout} = await execFileAsync(bin, ["version"], {timeout: 8000});
      const ver = String(stdout || "").trim();
      if (ver.startsWith("go version")) {
        return {
          installed: true,
          goBin: bin,
          goVersion: ver.replace(/^go version\s+/i, ""),
          managed: bin.startsWith(managedRoot),
          managedRoot,
        };
      }
    } catch {}
  }
  return {installed: false, goBin: "", goVersion: "", managed: false, managedRoot};
}

/** 免 Root 自动下载并校验官方 Go 工具链到 ~/.galiais/toolchains/go（chroot & PRoot 通用）。 */
export async function installGoToolchain({home, onProgress = () => {}} = {}) {
  const existing = await detectGoToolchain(home);
  if (existing.installed) {
    onProgress({stage: "toolchain", percent: 100, message: `Go 工具链已就绪：${existing.goVersion}`});
    return {ok: true, alreadyInstalled: true, ...existing};
  }
  const goOs = process.platform === "darwin" ? "darwin" : process.platform === "win32" ? "windows" : "linux";
  const goArch = process.arch === "arm64" ? "arm64" : process.arch === "x64" ? "amd64" : process.arch;
  if (goOs !== "linux" && goOs !== "darwin") {
    throw new Error(`当前平台 ${goOs}/${goArch} 请手动安装 Go 工具链后重试`);
  }
  onProgress({stage: "toolchain", percent: 10, message: "正在查询官方 Go 最新稳定版校验清单…"});
  const mirrors = ["https://golang.google.cn/dl", "https://go.dev/dl"];
  let selected = null;
  let activeMirror = mirrors[0];
  for (const base of mirrors) {
    try {
      const res = await fetch(`${base}/?mode=json`, {signal: AbortSignal.timeout(15_000)});
      if (!res.ok) continue;
      const releases = await res.json();
      for (const rel of releases) {
        if (!rel?.stable || !Array.isArray(rel.files)) continue;
        const f = rel.files.find(x => x.os === goOs && x.arch === goArch && x.kind === "archive" && x.filename.endsWith(".tar.gz"));
        if (f) { selected = {version: rel.version, ...f}; activeMirror = base; break; }
      }
      if (selected) break;
    } catch {}
  }
  if (!selected) {
    throw new Error("无法获取 Go 官方发布清单，请检查手机网络连接或代理设置");
  }

  const tcBase = path.join(home, ".galiais", "toolchains");
  await fs.mkdir(tcBase, {recursive: true, mode: 0o700});
  const tmpTar = path.join(tcBase, `${selected.filename}.part`);
  const dlUrl = `${activeMirror}/${selected.filename}`;
  onProgress({stage: "toolchain", percent: 25, message: `正在下载 ${selected.filename}（约 ${Math.round((selected.size || 72_000_000) / 1048576)}MB）…`});

  try {
    await new Promise((resolve, reject) => {
      const child = spawn("curl", ["-fL", "--retry", "2", "--connect-timeout", "20", "-o", tmpTar, dlUrl], {stdio: ["ignore", "ignore", "pipe"]});
      let err = "";
      child.stderr?.on("data", d => { if (err.length < 1024) err += d.toString("utf8"); });
      child.on("error", reject);
      child.on("close", code => (code === 0 ? resolve() : reject(new Error(err.trim() || `curl exit ${code}`))));
    });

    onProgress({stage: "toolchain", percent: 75, message: "正在校验 SHA-256 完整性…"});
    const actualHash = await sha256File(tmpTar);
    if (selected.sha256 && actualHash.toLowerCase() !== String(selected.sha256).toLowerCase()) {
      throw new Error(`Go 安装包 SHA-256 校验失败（期望 ${selected.sha256.slice(0, 12)}，实际 ${actualHash.slice(0, 12)}）`);
    }

    onProgress({stage: "toolchain", percent: 85, message: "正在解压 Go 交叉编译工具链（免 Root）…"});
    const staging = path.join(tcBase, "go.staging");
    const finalDir = path.join(tcBase, "go");
    await fs.rm(staging, {recursive: true, force: true});
    await fs.mkdir(staging, {recursive: true});
    await execFileAsync("tar", ["-xzf", tmpTar, "-C", staging], {timeout: 120_000});
    const unpackedGo = path.join(staging, "go");
    if (!fss.existsSync(path.join(unpackedGo, "bin", "go"))) {
      throw new Error("解压后的 Go 工具链缺少 bin/go");
    }
    await fs.rm(finalDir, {recursive: true, force: true});
    await fs.rename(unpackedGo, finalDir);
    await fs.rm(staging, {recursive: true, force: true});
  } finally {
    await fs.rm(tmpTar, {force: true}).catch(() => {});
  }

  const verified = await detectGoToolchain(home);
  if (!verified.installed) throw new Error("Go 工具链解压完成但无法执行 go version");
  onProgress({stage: "toolchain", percent: 100, message: `Go 工具链安装完成：${verified.goVersion}`});
  return {ok: true, alreadyInstalled: false, ...verified};
}

/** 查询当前工具链状态、源码指纹与 6 平台已构建二进制清单。 */
export async function getBuildStatus(home) {
  const tc = await detectGoToolchain(home);
  let srcInfo = {srcDir: "", sourceHash: "", origin: "missing"};
  try { srcInfo = await resolveNativeSourceDir(home); } catch {}
  const buildsDir = path.join(home, ".galiais", "computer-use", "builds");
  const targets = [];
  for (const t of NATIVE_TARGETS) {
    const file = path.join(buildsDir, goBinaryFilename(t));
    const metaFile = `${file}.meta.json`;
    let built = false, size = 0, sha256 = "", builtAt = 0, upToDate = false;
    try {
      const st = await fs.stat(file);
      built = st.isFile() && st.size > 0;
      size = st.size;
      const meta = JSON.parse(await fs.readFile(metaFile, "utf8"));
      sha256 = meta.sha256 || "";
      builtAt = meta.builtAt || st.mtimeMs;
      upToDate = Boolean(srcInfo.sourceHash && meta.sourceHash === srcInfo.sourceHash);
    } catch {}
    targets.push({...t, filename: goBinaryFilename(t), path: file, built, size, sha256, builtAt, upToDate});
  }
  return {
    ok: true,
    toolchain: tc,
    sourceReady: Boolean(srcInfo.srcDir),
    sourceOrigin: srcInfo.origin,
    sourceHash: srcInfo.sourceHash.slice(0, 12),
    buildsDir,
    targets,
  };
}

/** 在本机（手机 chroot/PRoot 或 PC）交叉编译单个或全部 6 平台原生桌面程序。 */
export async function buildNativeAgent({home, os: targetOs, arch: targetArch, all = false, force = false, onProgress = () => {}} = {}) {
  const tc = await detectGoToolchain(home);
  if (!tc.installed) {
    throw new Error("尚未安装 Go 交叉编译工具链，请先点击「安装 Go 工具链」或传入 autoInstallToolchain=true");
  }
  const {srcDir, sourceHash} = await resolveNativeSourceDir(home);
  const buildsDir = path.join(home, ".galiais", "computer-use", "builds");
  const goCache = path.join(home, ".galiais", "computer-use", "go-cache");
  const goPath = path.join(home, ".galiais", "computer-use", "gopath");
  await fs.mkdir(buildsDir, {recursive: true, mode: 0o700});
  await fs.mkdir(goCache, {recursive: true, mode: 0o700});

  const queue = all ? NATIVE_TARGETS : [validTarget(String(targetOs || "").toLowerCase(), String(targetArch || "").toLowerCase())];
  if (!queue[0]) {
    throw new Error(`不支持的目标架构：${targetOs}/${targetArch}（支持 windows/darwin/linux × amd64/arm64）`);
  }

  const results = [];
  for (let i = 0; i < queue.length; i++) {
    const t = queue[i];
    const outPath = path.join(buildsDir, goBinaryFilename(t));
    const metaPath = `${outPath}.meta.json`;
    if (!force && fss.existsSync(outPath) && fss.existsSync(metaPath)) {
      try {
        const meta = JSON.parse(await fs.readFile(metaPath, "utf8"));
        if (meta.sourceHash === sourceHash) {
          onProgress({stage: "build", target: `${t.os}/${t.arch}`, percent: Math.round(((i + 1) / queue.length) * 100), message: `命中缓存 ${t.label}`});
          results.push({...t, path: outPath, cached: true, size: meta.size, sha256: meta.sha256, ms: 0});
          continue;
        }
      } catch {}
    }

    onProgress({stage: "build", target: `${t.os}/${t.arch}`, percent: Math.round((i / queue.length) * 100), message: `正在编译 ${t.label} (${t.os}/${t.arch})…`});
    const tmpOut = `${outPath}.building`;
    const started = Date.now();
    await new Promise((resolve, reject) => {
      const child = spawn(tc.goBin, ["build", "-trimpath", "-ldflags=-s -w", "-o", tmpOut, "."], {
        cwd: srcDir,
        env: {
          ...process.env,
          CGO_ENABLED: "0",
          GOOS: t.os,
          GOARCH: t.arch,
          GOCACHE: goCache,
          GOPATH: goPath,
          GOPROXY: "off",
        },
        stdio: ["ignore", "pipe", "pipe"],
      });
      let out = "";
      child.stdout?.on("data", d => { if (out.length < 4096) out += d.toString("utf8"); });
      child.stderr?.on("data", d => { if (out.length < 4096) out += d.toString("utf8"); });
      child.on("error", reject);
      child.on("close", code => (code === 0 ? resolve() : reject(new Error(`编译 ${t.os}/${t.arch} 失败: ${out.trim() || "exit " + code}`))));
    });
    await fs.rename(tmpOut, outPath);
    if (!t.ext) await fs.chmod(outPath, 0o755).catch(() => {});
    const st = await fs.stat(outPath);
    const hash = await sha256File(outPath);
    const meta = {os: t.os, arch: t.arch, size: st.size, sha256: hash, sourceHash, goVersion: tc.goVersion, builtAt: Date.now()};
    await fs.writeFile(metaPath, JSON.stringify(meta, null, 2) + "\n", "utf8");
    const ms = Date.now() - started;
    onProgress({stage: "build", target: `${t.os}/${t.arch}`, percent: Math.round(((i + 1) / queue.length) * 100), message: `编译完成 ${t.label} (${(st.size / 1048576).toFixed(2)} MB, ${ms}ms)`});
    results.push({...t, path: outPath, cached: false, size: st.size, sha256: hash, ms});
  }
  return {ok: true, results};
}

/** 局域网 UDP 广播扫描（:29841）自动发现运行中的 Solbraid 原生桌面代理。 */
export async function discoverLanDesktops({timeoutMs = 1600, port = 29841} = {}) {
  const targets = new Set(["255.255.255.255"]);
  for (const list of Object.values(os.networkInterfaces() || {})) {
    for (const info of list || []) {
      if (info.family === "IPv4" && !info.internal && info.address && info.netmask) {
        const a = info.address.split(".").map(Number);
        const m = info.netmask.split(".").map(Number);
        if (a.length === 4 && m.length === 4) {
          targets.add(a.map((v, idx) => (v | (~m[idx] & 255))).join("."));
        }
      }
    }
  }
  return await new Promise(resolve => {
    const found = new Map();
    const sock = dgram.createSocket({type: "udp4", reuseAddr: true});
    const finish = () => {
      try { sock.close(); } catch {}
      resolve([...found.values()]);
    };
    const timer = setTimeout(finish, Math.max(500, Math.min(5000, Number(timeoutMs) || 1600)));
    sock.on("error", () => { clearTimeout(timer); finish(); });
    sock.on("message", (msg, rinfo) => {
      try {
        const obj = JSON.parse(msg.toString("utf8"));
        if (obj?.magic !== "SOLBRAID_DESKTOP_V1") return;
        const key = `${rinfo.address}:${obj.port || 29840}`;
        found.set(key, {
          address: rinfo.address,
          port: Number(obj.port) || 29840,
          name: obj.name || obj.hostname || rinfo.address,
          hostname: obj.hostname || "",
          os: obj.os || "auto",
          arch: obj.arch || "",
          tls: Boolean(obj.tls),
          tlsFingerprint: obj.tlsFingerprint || "",
          pairId: obj.pairId || "",
          permissionLevel: obj.permissionLevel || "operate",
          mac: obj.mac || "",
          engine: obj.engine || "native-go",
          version: obj.version || "",
        });
      } catch {}
    });
    sock.bind(0, () => {
      try { sock.setBroadcast(true); } catch {}
      const probe = Buffer.from("SOLBRAID_DISCOVER_V1", "utf8");
      for (const ip of targets) {
        sock.send(probe, 0, probe.length, port, ip, () => {});
      }
    });
  });
}

function parseRemoteOsArch(raw) {
  const s = String(raw || "").trim();
  if (/WIN:/i.test(s) || /mingw|msys|cygwin|windows/i.test(s)) {
    const arch = /arm64|aarch64/i.test(s) ? "arm64" : "amd64";
    return {os: "windows", arch};
  }
  const lower = s.toLowerCase();
  const targetOs = lower.includes("darwin") ? "darwin" : "linux";
  const targetArch = (lower.includes("arm64") || lower.includes("aarch64")) ? "arm64" : "amd64";
  return {os: targetOs, arch: targetArch};
}

const SAFE_SSH_ALIAS_RE = /^[A-Za-z0-9][A-Za-z0-9._@:-]{0,127}$/;
const VALID_DEPLOY_PERMISSIONS = new Set(["observe", "operate", "admin"]);
const VALID_DEPLOY_TRANSPORTS = new Set(["lan", "usb", "ssh", "public", "relay"]);

function execFileWithStdin(bin, args, stdinText, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    const child = spawn(bin, args, {stdio: ["pipe", "pipe", "pipe"]});
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill("SIGTERM"); } catch {}
      reject(new Error(`${bin} 执行超时 (${Math.round(timeoutMs / 1000)}s)`));
    }, timeoutMs);
    child.stdout?.on("data", d => { if (stdout.length < 65536) stdout += d.toString("utf8"); });
    child.stderr?.on("data", d => { if (stderr.length < 16384) stderr += d.toString("utf8"); });
    child.on("error", err => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });
    child.on("close", code => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0) resolve({stdout, stderr});
      else reject(new Error(stderr.trim() || `${bin} exit ${code}`));
    });
    child.stdin.on("error", () => {});
    child.stdin.end(stdinText, "utf8");
  });
}

/** 连接 SSH 主机 -> 自动探测 OS/架构 -> 本机交叉编译原生二进制 -> SCP 推送安装并自启 -> 自动配对入库。 */
export async function deployNativeOverSsh({
  home,
  sshAlias,
  name = "",
  port = 29840,
  permissionLevel = "operate",
  transport = "ssh",
  autoTls = false,
  installService = true,
  forceRebuild = false,
  autoInstallToolchain = true,
  onProgress = () => {},
  registerHost,
  pingHost,
} = {}) {
  const alias = String(sshAlias || "").trim();
  if (!alias) throw new Error("必须指定 SSH 主机别名（sshAlias）");
  if (!SAFE_SSH_ALIAS_RE.test(alias)) {
    throw new Error(`SSH 主机别名包含非法字符或以连字符开头：${alias}`);
  }
  const safePort = Number(port);
  if (!Number.isInteger(safePort) || safePort < 1 || safePort > 65535) {
    throw new Error(`无效的监听端口：${port}（需为 1~65535 整数）`);
  }
  const safePerm = String(permissionLevel || "operate").trim().toLowerCase();
  if (!VALID_DEPLOY_PERMISSIONS.has(safePerm)) {
    throw new Error(`不支持的权限档位：${permissionLevel}`);
  }
  const safeTransport = String(transport || "ssh").trim().toLowerCase();
  if (!VALID_DEPLOY_TRANSPORTS.has(safeTransport)) {
    throw new Error(`不支持的传输模式：${transport}`);
  }
  const sshCommon = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=12"];

  onProgress({stage: "probe", percent: 10, message: `正在通过 SSH 连接 ${alias} 探测操作系统与 CPU 架构…`});
  const {stdout: probeOut} = await execFileAsync("ssh", [
    ...sshCommon, "--", alias,
    "uname -sm 2>/dev/null || echo WIN:%PROCESSOR_ARCHITECTURE%",
  ], {timeout: 20_000});
  const {os: remoteOs, arch: remoteArch} = parseRemoteOsArch(probeOut);
  onProgress({stage: "probe", percent: 22, message: `识别目标系统：${remoteOs}/${remoteArch}`});

  const tc = await detectGoToolchain(home);
  if (!tc.installed) {
    if (!autoInstallToolchain) throw new Error("手机端尚未安装 Go 交叉编译工具链，请先在「工具链中心」安装");
    await installGoToolchain({home, onProgress});
  }

  onProgress({stage: "build", percent: 35, message: `正在为 ${remoteOs}/${remoteArch} 构建原生单文件守护程序…`});
  const built = await buildNativeAgent({home, os: remoteOs, arch: remoteArch, force: forceRebuild, onProgress});
  const artifact = built.results[0];

  const token = randomBytes(32).toString("hex");
  const listenHost = safeTransport === "ssh" ? "127.0.0.1" : "0.0.0.0";
  const remoteBinName = remoteOs === "windows" ? "solbraid-desktop.exe" : "solbraid-desktop";

  onProgress({stage: "upload", percent: 65, message: `正在上传原生程序 (${(artifact.size / 1048576).toFixed(2)} MB) 到 ${alias}…`});
  let pairInfo = {};
  if (remoteOs === "windows") {
    await execFileAsync("ssh", [
      ...sshCommon, "--", alias,
      `powershell -NoProfile -Command "New-Item -ItemType Directory -Force -Path '$HOME\\.solbraid-pc-agent\\bin' | Out-Null; schtasks.exe /End /TN SolbraidDesktopAgent 2>$null | Out-Null; Stop-Process -Name solbraid-desktop -Force -ErrorAction SilentlyContinue"`,
    ], {timeout: 25_000});
    await execFileAsync("scp", [...sshCommon, "--", artifact.path, `${alias}:.solbraid-pc-agent/bin/${remoteBinName}`], {timeout: 120_000});
    onProgress({stage: "install", percent: 82, message: "正在 Windows 端安全写入令牌并启动原生守护程序…"});
    const tlsPsArg = autoTls ? ",'--auto-tls'" : "";
    const tlsCliFlag = autoTls ? "--auto-tls" : "";
    const startBlock = installService
      ? `& $exe --host '${listenHost}' --port ${safePort} --permission '${safePerm}' ${tlsCliFlag} --install-service | Out-Null; schtasks.exe /Run /TN SolbraidDesktopAgent 2>$null | Out-Null; if ($LASTEXITCODE -ne 0) { Start-Process -FilePath $exe -ArgumentList @('--host','${listenHost}','--port','${safePort}','--permission','${safePerm}'${tlsPsArg}) -WindowStyle Hidden }`
      : `Start-Process -FilePath $exe -ArgumentList @('--host','${listenHost}','--port','${safePort}','--permission','${safePerm}'${tlsPsArg}) -WindowStyle Hidden`;
    const psStart = `$exe = Join-Path $HOME '.solbraid-pc-agent\\bin\\solbraid-desktop.exe'; $pair = ($input | & $exe --host '${listenHost}' --port ${safePort} --token-stdin --permission '${safePerm}' ${tlsCliFlag} --print-pair-json); ${startBlock}; Write-Output $pair`;
    const {stdout: pairOut} = await execFileWithStdin(
      "ssh",
      [...sshCommon, "--", alias, `powershell -NoProfile -Command "${psStart.replace(/"/g, '\\"')}"`],
      `${token}\n`,
      30_000,
    );
    pairInfo = parseLastJsonLine(pairOut);
  } else {
    const preStop = [
      "mkdir -p ~/.solbraid-pc-agent/bin",
      "systemctl --user stop solbraid-desktop.service >/dev/null 2>&1 || true",
      'launchctl unload "$HOME/Library/LaunchAgents/com.galiais.solbraid.desktop.plist" >/dev/null 2>&1 || true',
      "pkill -x solbraid-desktop >/dev/null 2>&1 || true",
      "sleep 0.3",
    ].join("; ");
    await execFileAsync("ssh", [...sshCommon, "--", alias, preStop], {timeout: 20_000});
    await execFileAsync("scp", [...sshCommon, "--", artifact.path, `${alias}:~/.solbraid-pc-agent/bin/${remoteBinName}`], {timeout: 120_000});
    onProgress({stage: "install", percent: 82, message: `正在 ${remoteOs} 端安全配置并启动原生守护程序…`});
    const tlsFlag = autoTls ? "--auto-tls" : "";
    const launchCmd = installService
      ? `if ! "$BIN" --host '${listenHost}' --port ${safePort} --permission '${safePerm}' ${tlsFlag} --install-service </dev/null >/dev/null 2>&1; then nohup "$BIN" --host '${listenHost}' --port ${safePort} --permission '${safePerm}' ${tlsFlag} >"$HOME/.solbraid-pc-agent/agent.log" 2>&1 </dev/null & fi`
      : `nohup "$BIN" --host '${listenHost}' --port ${safePort} --permission '${safePerm}' ${tlsFlag} >"$HOME/.solbraid-pc-agent/agent.log" 2>&1 </dev/null &`;
    const shStart = `BIN="$HOME/.solbraid-pc-agent/bin/${remoteBinName}"; chmod 755 "$BIN"; PAIR=$("$BIN" --host '${listenHost}' --port ${safePort} --token-stdin --permission '${safePerm}' ${tlsFlag} --print-pair-json); ${launchCmd}; sleep 1; echo "$PAIR"`;
    const {stdout: pairOut} = await execFileWithStdin(
      "ssh",
      [...sshCommon, "--", alias, shStart],
      `${token}\n`,
      30_000,
    );
    pairInfo = parseLastJsonLine(pairOut);
  }

  onProgress({stage: "pair", percent: 92, message: "正在自动写入配对主机并执行连通性测速…"});
  const hostSpec = {
    action: "add",
    id: `ssh-${alias.toLowerCase().replace(/[^a-z0-9._-]+/g, "-")}`,
    name: name.trim() || pairInfo?.name || alias,
    os: remoteOs,
    transport: safeTransport,
    address: safeTransport === "lan" ? (pairInfo?.address || "") : "127.0.0.1",
    port: safePort,
    sshAlias: alias,
    tls: Boolean(autoTls || pairInfo?.tls),
    tlsFingerprint: pairInfo?.tlsFingerprint || "",
    mac: pairInfo?.mac || "",
    permissionLevel: safePerm,
    token,
    engine: "native-go",
    setDefault: true,
  };
  const added = await registerHost(hostSpec);
  let ping = null;
  try { ping = await pingHost(added.host.id); } catch (e) { ping = {ok: false, error: e.message}; }
  onProgress({stage: "done", percent: 100, message: ping?.ok ? `部署并配对成功（延迟 ${ping.latencyMs}ms）` : "已部署并保存主机，等待目标主机响应"});
  return {
    ok: true,
    remoteOs,
    remoteArch,
    binarySize: artifact.size,
    binarySha256: artifact.sha256,
    cachedBuild: artifact.cached,
    host: added.host,
    ping,
  };
}

function parseLastJsonLine(text) {
  const lines = String(text || "").trim().split(/\r?\n/).reverse();
  for (const l of lines) {
    const t = l.trim();
    if (t.startsWith("{") && t.endsWith("}")) {
      try { return JSON.parse(t); } catch {}
    }
  }
  return {};
}
