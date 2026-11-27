// Solbraid 统一多版本编译器与工具链管理引擎（兼容 Root chroot 与免 Root PRoot，零第三方依赖）。
// 架构特性：versions/<ver> 多版本并存 + current 原子软链切换 + 安装前预检与 SHA-256 校验 + 切换前冒烟验证 + 并发锁与可取消执行。
import fs from "node:fs/promises";
import fss from "node:fs";
import path from "node:path";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {createHash, randomUUID} from "node:crypto";
import {downloadEnvironment, githubDownloadUrl, readDownloadNetwork, saveDownloadNetwork} from "./download-network.mjs";
import {assertSafeVersion, fetchAvailableVersions, resolveVersionDownload} from "./toolchain-versions.mjs";

const execFileAsync = promisify(execFile);
const BUNDLED_NODE_DIR = "/opt/galiais-codex/usr/lib/chatgpt/resources";

export const TOOLCHAIN_CATALOG = [
  {
    id: "go", name: "Go", category: "compiler", rootless: true, multiVersion: true,
    summary: "官方 Go 编译器，支持多版本并存与 6 平台交叉编译（Windows / macOS / Linux）",
    binaries: ["go", "gofmt"], approxSizeMB: 72,
    probe: {cmd: "go", args: ["version"]}, binSubdir: "bin",
  },
  {
    id: "zig", name: "Zig (含 C/C++ 交叉编译器)", category: "compiler", rootless: true, multiVersion: true,
    summary: "免 Root 单包自带 zig cc / zig c++，支持 C / C++ / Zig 多版本与多平台交叉编译",
    binaries: ["zig"], approxSizeMB: 48,
    probe: {cmd: "zig", args: ["version"]}, binSubdir: "",
  },
  {
    id: "rust", name: "Rust & Cargo", category: "compiler", rootless: true, multiVersion: true,
    summary: "基于 rustup 管理多版本工具链与交叉编译目标（含 WASM / Linux-musl）",
    binaries: ["rustc", "cargo", "rustup"], approxSizeMB: 260,
    probe: {cmd: "rustc", args: ["--version"]}, binSubdir: "cargo/bin",
  },
  {
    id: "cpp-apt", name: "C/C++ 原生构建套件", category: "compiler", rootless: false, multiVersion: false,
    summary: "Ubuntu APT 软件包：GCC / G++ / Clang / CMake / Ninja / pkg-config / GDB",
    binaries: ["gcc", "g++", "clang", "cmake", "make", "ninja"], approxSizeMB: 320,
    probe: {cmd: "gcc", args: ["--version"]}, binSubdir: "",
  },
  {
    id: "python", name: "Python & uv", category: "runtime", rootless: true, multiVersion: true,
    summary: "免 Root 托管多版本便携 CPython（3.8–3.13）与 uv 虚拟环境/依赖管理",
    binaries: ["uv", "python3"], approxSizeMB: 45,
    probe: {cmd: "python3", args: ["--version"]}, binSubdir: "bin",
  },
  {
    id: "node", name: "Node.js & npm", category: "runtime", rootless: true, multiVersion: true,
    summary: "默认内置 Node.js，亦支持多版本 LTS/历史版本并存与切换",
    binaries: ["node", "npm"], approxSizeMB: 42,
    probe: {cmd: "node", args: ["-v"]}, binSubdir: "bin",
  },
  {
    id: "pnpm", name: "pnpm", category: "package-manager", rootless: true, multiVersion: true,
    summary: "内容寻址硬链接 Node.js 依赖管理器，支持多版本并存切换",
    binaries: ["pnpm"], approxSizeMB: 14,
    probe: {cmd: "pnpm", args: ["--version"]}, binSubdir: "bin",
  },
  {
    id: "bun", name: "Bun", category: "runtime", rootless: true, multiVersion: true,
    summary: "高性能 JavaScript / TypeScript 运行时、打包与测试工具，支持多版本切换",
    binaries: ["bun", "bunx"], approxSizeMB: 36,
    probe: {cmd: "bun", args: ["--version"]}, binSubdir: "bin",
  },
  {
    id: "java", name: "OpenJDK (Temurin)", category: "compiler", rootless: true, multiVersion: true,
    summary: "Java 编译器与 JVM 运行时，支持 JDK 8 / 11 / 17 / 21 / 25 多版本切换",
    binaries: ["java", "javac"], approxSizeMB: 190,
    probe: {cmd: "javac", args: ["-version"]}, binSubdir: "bin",
  },
  {
    id: "android-sdk", name: "Android SDK", category: "sdk", rootless: true, multiVersion: true, requiresJdk: true,
    summary: "自动接管 ~/android-sdk，管理 cmdline-tools、多版本 API Platforms、Build-Tools 与 ARM64 原生 aapt2",
    binaries: ["sdkmanager", "adb", "aapt2"], approxSizeMB: 185,
    probe: {cmd: "sdkmanager", args: ["--version"]}, binSubdir: "cmdline-tools/latest/bin",
  },
  {
    id: "gradle", name: "Gradle", category: "sdk", rootless: true, multiVersion: true, requiresJdk: true,
    summary: "JVM / Android 构建系统，支持 7.x / 8.x / 9.x 官方历史版本并存与 SHA-256 校验",
    binaries: ["gradle"], approxSizeMB: 135,
    probe: {cmd: "gradle", args: ["--version"]}, binSubdir: "bin",
  },
  {
    id: "kotlin", name: "Kotlin SDK (kotlinc)", category: "sdk", rootless: true, multiVersion: true, requiresJdk: true,
    summary: "JetBrains 官方独立 Kotlin 编译器，支持历史版本并存与免 Gradle 直接编译 .kt",
    binaries: ["kotlinc", "kotlin"], approxSizeMB: 86,
    probe: {cmd: "kotlinc", args: ["-version"]}, binSubdir: "bin",
  },
  {
    id: "maven", name: "Apache Maven", category: "sdk", rootless: true, multiVersion: true, requiresJdk: true,
    summary: "Java 生态标准构建与依赖管理 SDK，支持 3.8 / 3.9 / 3.10 历史版本与 SHA-512 校验",
    binaries: ["mvn"], approxSizeMB: 9,
    probe: {cmd: "mvn", args: ["-v"]}, binSubdir: "bin",
  },
  {
    id: "dotnet", name: ".NET SDK", category: "sdk", rootless: true, multiVersion: true,
    summary: "微软官方原生 Linux ARM64/x64 .NET SDK，支持 .NET 8 / 9 / 10 / 11 多版本并存与 SHA-512 校验",
    binaries: ["dotnet"], approxSizeMB: 215,
    probe: {cmd: "dotnet", args: ["--version"]}, binSubdir: "",
  },
  {
    id: "dart", name: "Dart SDK", category: "sdk", rootless: true, multiVersion: true,
    summary: "Google 官方原生 ARM64 Dart SDK，支持历史版本与 SHA-256 校验（注：Flutter Linux 上游暂仅提供 x64）",
    binaries: ["dart"], approxSizeMB: 250,
    probe: {cmd: "dart", args: ["--version"]}, binSubdir: "bin",
  },
  {
    id: "wasi-sdk", name: "WASI / WebAssembly SDK", category: "sdk", rootless: true, multiVersion: true,
    summary: "原生 ARM64 Clang/LLVM + WASI sysroot，可将 C/C++ 编译为跨平台 .wasm 模块",
    binaries: ["clang", "wasm-ld"], approxSizeMB: 184,
    probe: {cmd: "clang", args: ["--version"]}, binSubdir: "bin",
  },
];

export const MIRROR_PRESETS = {
  cn: {
    id: "cn", label: "中国大陆镜像源",
    npmRegistry: "https://registry.npmmirror.com",
    pypiIndexUrl: "https://pypi.tuna.tsinghua.edu.cn/simple",
    goProxy: "https://goproxy.cn,direct",
    rustupDistServer: "https://rsproxy.cn",
    rustupUpdateRoot: "https://rsproxy.cn/rustup",
  },
  official: {
    id: "official", label: "全球官方直连源",
    npmRegistry: "", pypiIndexUrl: "",
    goProxy: "https://proxy.golang.org,direct",
    rustupDistServer: "", rustupUpdateRoot: "",
  },
};

// 并发锁与活跃任务取消控制表（防止并发损坏 dpkg / 托管目录）
const activeOps = new Map(); // opKey -> { token, abortController, child, startedAt, id }

export function cancelActiveOperations() {
  let cancelled = 0;
  for (const [, op] of activeOps) {
    cancelled++;
    try { op.abortController?.abort(); } catch {}
    if (op.child && op.child.exitCode === null) {
      try { op.child.kill("SIGTERM"); } catch {}
      setTimeout(() => { try { if (op.child.exitCode === null) op.child.kill("SIGKILL"); } catch {} }, 1500).unref?.();
    }
  }
  return {ok: true, cancelled};
}

function toolchainsRoot(home) { return path.join(home, ".galiais", "toolchains"); }
function toolchainProfilePath(home) { return path.join(home, ".galiais", "env", "toolchain-profile.sh"); }
function mirrorMetaPath(home) { return path.join(toolchainsRoot(home), "mirrors.json"); }
function statePath(home) { return path.join(toolchainsRoot(home), "state.json"); }

async function readJsonSafe(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); } catch { return fallback; }
}

async function writeJsonAtomic(file, obj) {
  await fs.mkdir(path.dirname(file), {recursive: true, mode: 0o700});
  const tmp = `${file}.${randomUUID()}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(obj, null, 2) + "\n", {mode: 0o600});
  await fs.rename(tmp, file);
}

function shaFile(file, algo = "sha256") {
  return new Promise((resolve, reject) => {
    const hash = createHash(algo);
    const s = fss.createReadStream(file, {highWaterMark: 128 * 1024});
    s.on("data", chunk => hash.update(chunk));
    s.once("error", reject);
    s.once("end", () => resolve(hash.digest("hex")));
  });
}

export function detectAndroidSdkHome(home, baseEnv = process.env) {
  for (const cand of [baseEnv.ANDROID_HOME, baseEnv.ANDROID_SDK_ROOT, path.join(home, "android-sdk"), "/opt/android-sdk"]) {
    if (cand && fss.existsSync(cand)) return cand;
  }
  return path.join(home, "android-sdk");
}

async function inspectAndroidSdk(home, env) {
  const sdkHome = detectAndroidSdkHome(home, env);
  if (!fss.existsSync(sdkHome)) {
    return {installed: false, sdkHome, platforms: [], buildTools: [], hasSdkManager: false, aapt2Path: "", activeVersion: "", version: ""};
  }
  const platformsDir = path.join(sdkHome, "platforms");
  const platforms = fss.existsSync(platformsDir)
    ? (await fs.readdir(platformsDir, {withFileTypes: true}).catch(() => []))
      .filter(d => d.isDirectory() && /^android-\d+/.test(d.name))
      .map(d => d.name)
      .sort((a, b) => b.localeCompare(a, undefined, {numeric: true}))
    : [];
  const btDir = path.join(sdkHome, "build-tools");
  const buildTools = fss.existsSync(btDir)
    ? (await fs.readdir(btDir, {withFileTypes: true}).catch(() => []))
      .filter(d => d.isDirectory())
      .map(d => d.name)
      .sort((a, b) => b.localeCompare(a, undefined, {numeric: true}))
    : [];
  const sdkMgr = path.join(sdkHome, "cmdline-tools", "latest", "bin", "sdkmanager");
  const hasSdkManager = fss.existsSync(sdkMgr);
  let aapt2Path = "";
  const aapt2Cands = [
    ...buildTools.map(v => path.join(btDir, v, "aapt2")),
    path.join(sdkHome, "arm64-tools", "aapt2"),
    "/usr/bin/aapt2",
  ];
  for (const c of aapt2Cands) {
    if (!fss.existsSync(c)) continue;
    const ok = await execFileAsync(c, ["version"], {env, timeout: 4000}).then(() => true).catch(() => false);
    if (ok) { aapt2Path = c; break; }
  }
  const st = await readJsonSafe(statePath(home), {});
  const savedActive = st.active?.["android-sdk"] || "";
  const activeVersion = (savedActive && platforms.includes(savedActive)) ? savedActive : (platforms[0] || (hasSdkManager ? "cmdline-tools" : ""));
  const installed = platforms.length > 0 || buildTools.length > 0 || hasSdkManager;
  const parts = [];
  if (platforms.length) parts.push(platforms.slice(0, 3).join(", "));
  if (buildTools.length) parts.push(`build-tools ${buildTools[0]}`);
  if (aapt2Path) parts.push("aapt2 OK");
  else if (process.arch === "arm64" && installed) parts.push("缺 ARM64 aapt2");
  return {installed, sdkHome, platforms, buildTools, hasSdkManager, aapt2Path, activeVersion, version: parts.join(" · ")};
}

async function dirSizeBytes(dir, maxDepth = 5) {
  if (!dir || !fss.existsSync(dir)) return 0;
  let total = 0;
  async function walk(p, d) {
    if (d > maxDepth) return;
    let list;
    try { list = await fs.readdir(p, {withFileTypes: true}); } catch { return; }
    for (const e of list) {
      const full = path.join(p, e.name);
      try {
        if (e.isSymbolicLink()) continue;
        if (e.isDirectory()) await walk(full, d + 1);
        else total += (await fs.stat(full)).size;
      } catch {}
    }
  }
  await walk(dir, 0);
  return total;
}

/** 构造包含所有激活版本 bin/、内置 Node 路径及镜像变量的统一环境（兼容新多版本 current 与旧扁平目录）。 */
export function buildManagedToolchainEnv(home, baseEnv = process.env, mirrorCfg = {}) {
  const root = toolchainsRoot(home);
  const extraBins = [];
  const env = {};
  const resolveActiveRoot = id => {
    const cur = path.join(root, id, "current");
    if (fss.existsSync(cur)) return cur;
    const legacy = path.join(root, id);
    if (fss.existsSync(path.join(legacy, "bin")) || (id === "zig" && fss.existsSync(path.join(legacy, "zig")))) return legacy;
    return "";
  };

  const goRoot = resolveActiveRoot("go");
  if (goRoot) {
    extraBins.push(path.join(goRoot, "bin"), path.join(root, "go-work", "bin"));
    env.GOROOT = goRoot;
    env.GOPATH = path.join(root, "go-work");
  }
  const zigRoot = resolveActiveRoot("zig");
  if (zigRoot) extraBins.push(zigRoot);

  const rustRoot = path.join(root, "rust");
  if (fss.existsSync(path.join(rustRoot, "cargo", "bin"))) {
    extraBins.push(path.join(rustRoot, "cargo", "bin"));
    env.CARGO_HOME = path.join(rustRoot, "cargo");
    env.RUSTUP_HOME = path.join(rustRoot, "rustup");
  }
  const pyRoot = path.join(root, "python");
  if (fss.existsSync(path.join(pyRoot, "bin"))) {
    extraBins.push(path.join(pyRoot, "bin"));
    env.UV_PYTHON_INSTALL_DIR = path.join(pyRoot, "pythons");
    env.UV_TOOL_BIN_DIR = path.join(pyRoot, "bin");
  }
  const nodeCur = path.join(root, "node", "current", "bin");
  if (fss.existsSync(nodeCur)) extraBins.push(nodeCur);
  const pnpmRoot = resolveActiveRoot("pnpm");
  if (pnpmRoot && fss.existsSync(path.join(pnpmRoot, "bin"))) {
    extraBins.push(path.join(pnpmRoot, "bin"));
    env.PNPM_HOME = path.join(pnpmRoot, "bin");
  }
  const bunRoot = resolveActiveRoot("bun");
  if (bunRoot && fss.existsSync(path.join(bunRoot, "bin"))) {
    extraBins.push(path.join(bunRoot, "bin"));
    env.BUN_INSTALL = bunRoot;
  }
  const javaCur = path.join(root, "java", "current");
  const javaLegacy = path.join(root, "jdk");
  const javaRoot = fss.existsSync(path.join(javaCur, "bin")) ? javaCur : (fss.existsSync(path.join(javaLegacy, "bin")) ? javaLegacy : "");
  if (javaRoot) {
    extraBins.push(path.join(javaRoot, "bin"));
    env.JAVA_HOME = javaRoot;
  }

  // SDK 环境注入（Android SDK / Gradle / Kotlin / Maven / .NET / Dart / WASI SDK）
  const androidSdk = detectAndroidSdkHome(home, baseEnv);
  if (fss.existsSync(androidSdk)) {
    env.ANDROID_HOME = androidSdk;
    env.ANDROID_SDK_ROOT = androidSdk;
    for (const sub of [path.join("cmdline-tools", "latest", "bin"), "platform-tools", "arm64-tools"]) {
      const p = path.join(androidSdk, sub);
      if (fss.existsSync(p)) extraBins.push(p);
    }
  }
  const gradleRoot = resolveActiveRoot("gradle");
  if (gradleRoot && fss.existsSync(path.join(gradleRoot, "bin"))) {
    extraBins.push(path.join(gradleRoot, "bin"));
    env.GRADLE_HOME = gradleRoot;
  }
  const kotlinRoot = resolveActiveRoot("kotlin");
  if (kotlinRoot && fss.existsSync(path.join(kotlinRoot, "bin"))) {
    extraBins.push(path.join(kotlinRoot, "bin"));
    env.KOTLIN_HOME = kotlinRoot;
  }
  const mavenRoot = resolveActiveRoot("maven");
  if (mavenRoot && fss.existsSync(path.join(mavenRoot, "bin"))) {
    extraBins.push(path.join(mavenRoot, "bin"));
    env.MAVEN_HOME = mavenRoot;
  }
  const dotnetRoot = resolveActiveRoot("dotnet");
  if (dotnetRoot && fss.existsSync(path.join(dotnetRoot, "dotnet"))) {
    extraBins.push(dotnetRoot);
    env.DOTNET_ROOT = dotnetRoot;
    env.DOTNET_CLI_TELEMETRY_OPTOUT = "1";
  }
  const dartRoot = resolveActiveRoot("dart");
  if (dartRoot && fss.existsSync(path.join(dartRoot, "bin"))) {
    extraBins.push(path.join(dartRoot, "bin"));
    env.DART_SDK = dartRoot;
  }
  const wasiRoot = resolveActiveRoot("wasi-sdk");
  if (wasiRoot && fss.existsSync(path.join(wasiRoot, "bin"))) {
    env.WASI_SDK_PATH = wasiRoot;
  }

  if (mirrorCfg.goProxy) env.GOPROXY = mirrorCfg.goProxy;
  if (mirrorCfg.rustupDistServer) env.RUSTUP_DIST_SERVER = mirrorCfg.rustupDistServer;
  if (mirrorCfg.rustupUpdateRoot) env.RUSTUP_UPDATE_ROOT = mirrorCfg.rustupUpdateRoot;

  // 兜底把 Solbraid 内置 node/npm 放在受管版本之后，确保系统缺 /usr/bin/node 时所有工具依然可用
  const tailBins = fss.existsSync(BUNDLED_NODE_DIR) ? [BUNDLED_NODE_DIR] : [];
  const sep = process.platform === "win32" ? ";" : ":";
  const merged = [...extraBins, ...(baseEnv.PATH || "").split(sep), ...tailBins].filter(Boolean);
  env.PATH = [...new Set(merged)].join(sep);
  return {env, extraBins};
}

export async function writeToolchainProfile(home) {
  const mirrorCfg = await readJsonSafe(mirrorMetaPath(home), {});
  const {env, extraBins} = buildManagedToolchainEnv(home, {PATH: ""}, mirrorCfg);
  const lines = ["# Auto-generated by Solbraid Toolchain & Dependency Studio"];
  for (const [k, v] of Object.entries(env)) {
    if (k === "PATH" || !v) continue;
    lines.push(`export ${k}='${String(v).replace(/'/g, "'\\''")}'`);
  }
  for (let i = extraBins.length - 1; i >= 0; i--) {
    const q = `'${extraBins[i].replace(/'/g, "'\\''")}'`;
    lines.push(`case ":$PATH:" in *:${q}:*) ;; *) export PATH=${q}:"$PATH" ;; esac`);
  }
  const target = toolchainProfilePath(home);
  await fs.mkdir(path.dirname(target), {recursive: true, mode: 0o700});
  const tmp = `${target}.${randomUUID()}.tmp`;
  await fs.writeFile(tmp, lines.join("\n") + "\n", {mode: 0o600});
  await fs.rename(tmp, target);
  return {profilePath: target, binCount: extraBins.length};
}

async function probeBinary(cmd, args, env) {
  const isWin = process.platform === "win32";
  try {
    const {stdout, stderr} = await execFileAsync(cmd, args, {env, timeout: 6000});
    const first = (String(stdout || "") + "\n" + String(stderr || "")).trim().split(/\r?\n/).map(s => s.trim()).find(Boolean) || "";
    const resolved = await execFileAsync(isWin ? "where" : "which", [cmd], {env, timeout: 3000})
      .then(r => String(r.stdout || "").trim().split(/\r?\n/)[0] || cmd)
      .catch(() => cmd);
    return {installed: true, version: first.slice(0, 80), path: resolved};
  } catch {
    return {installed: false, version: "", path: ""};
  }
}

async function listInstalledVersionsFor(home, id, env) {
  const root = toolchainsRoot(home);
  if (id === "rust") {
    const rustupBin = path.join(root, "rust", "cargo", "bin", "rustup");
    if (!fss.existsSync(rustupBin)) return {versions: [], activeVersion: ""};
    try {
      const {stdout} = await execFileAsync(rustupBin, ["toolchain", "list"], {env, timeout: 8000});
      const versions = [];
      let activeVersion = "";
      for (const line of stdout.trim().split(/\r?\n/).filter(Boolean)) {
        const isDef = line.includes("(default)") || line.includes("(active");
        const name = line.split(/\s+/)[0].replace(/-(aarch64|x86_64)-.*$/, "");
        if (name) {
          versions.push(name);
          if (isDef) activeVersion = name;
        }
      }
      return {versions, activeVersion};
    } catch { return {versions: [], activeVersion: ""}; }
  }
  if (id === "python") {
    const pyDir = path.join(root, "python", "pythons");
    const versions = [];
    if (fss.existsSync(pyDir)) {
      for (const e of await fs.readdir(pyDir).catch(() => [])) {
        const m = /cpython-(\d+\.\d+\.\d+)/.exec(e);
        if (m) versions.push(m[1]);
      }
    }
    const state = await readJsonSafe(statePath(home), {});
    const sorted = [...new Set(versions)].sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
    return {versions: sorted, activeVersion: state.active?.python || sorted[0] || ""};
  }
  const vDir = path.join(root, id, "versions");
  const versions = fss.existsSync(vDir)
    ? (await fs.readdir(vDir, {withFileTypes: true}).catch(() => [])).filter(d => d.isDirectory()).map(d => d.name).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}))
    : [];
  let activeVersion = "";
  const curLink = path.join(root, id, "current");
  if (fss.existsSync(curLink)) {
    try { activeVersion = path.basename(await fs.readlink(curLink)); } catch {}
  }
  return {versions, activeVersion};
}

export async function isAptDirectlyWritable() {
  if (process.platform !== "linux") return false;
  if (typeof process.getuid === "function" && process.getuid() === 0) return true;
  try { await fs.access("/var/lib/dpkg/lock-frontend", fss.constants.W_OK); return true; } catch { return false; }
}

/** 扫描全部 9 套编译器/运行时、已装多版本清单、当前激活版本、APT 写权限与镜像配置。 */
export async function listToolchains(home, {hasHostAptBridge = false} = {}) {
  const mirrorCfg = await readJsonSafe(mirrorMetaPath(home), {});
  const dlNet = await readDownloadNetwork().catch(() => ({}));
  const {env: tcEnv} = buildManagedToolchainEnv(home, process.env, mirrorCfg);
  const mergedEnv = {...process.env, ...tcEnv};
  const root = toolchainsRoot(home);
  const directApt = await isAptDirectlyWritable();

  const items = await Promise.all(TOOLCHAIN_CATALOG.map(async spec => {
    if (spec.id === "android-sdk") {
      const info = await inspectAndroidSdk(home, mergedEnv);
      const diskBytes = info.installed ? await dirSizeBytes(info.sdkHome, 3) : 0;
      return {
        ...spec,
        installed: info.installed,
        version: info.version,
        activeVersion: info.activeVersion,
        installedVersions: info.platforms,
        binPath: info.hasSdkManager ? path.join(info.sdkHome, "cmdline-tools", "latest", "bin", "sdkmanager") : info.sdkHome,
        managed: info.installed,
        diskBytes,
        canUninstall: info.platforms.length > 0,
      };
    }
    let probe;
    if (spec.id === "wasi-sdk") {
      const wasiClang = path.join(root, "wasi-sdk", "current", "bin", "clang");
      probe = fss.existsSync(wasiClang) ? await probeBinary(wasiClang, spec.probe.args, mergedEnv) : {installed: false, version: "", path: ""};
    } else {
      probe = await probeBinary(spec.probe.cmd, spec.probe.args, mergedEnv);
      if (!probe.installed && spec.id === "python") {
        const uvProbe = await probeBinary("uv", ["--version"], mergedEnv);
        if (uvProbe.installed) probe = uvProbe;
      }
    }
    const {versions: installedVersions, activeVersion} = await listInstalledVersionsFor(home, spec.id, mergedEnv);
    const managedRoot = path.join(root, spec.id);
    const managed = installedVersions.length > 0 || Boolean(probe.path && path.resolve(probe.path).startsWith(path.resolve(root)));
    const diskBytes = managed ? await dirSizeBytes(managedRoot, 4) : 0;
    return {
      ...spec,
      installed: probe.installed || installedVersions.length > 0,
      version: probe.version,
      activeVersion,
      installedVersions,
      binPath: probe.path,
      managed,
      diskBytes,
      canUninstall: managed && spec.id !== "cpp-apt",
    };
  }));

  const isCnMirror = Boolean(
    (dlNet.npmRegistry || "").includes("npmmirror") ||
    (dlNet.pypiIndexUrl || "").includes("tuna.tsinghua") ||
    (mirrorCfg.goProxy || "").includes("goproxy.cn"),
  );
  return {
    ok: true,
    platform: process.platform,
    arch: process.arch,
    isRoot: typeof process.getuid === "function" ? process.getuid() === 0 : false,
    aptWritable: directApt || hasHostAptBridge,
    aptViaHostRoot: !directApt && hasHostAptBridge,
    toolchainsRoot: root,
    mirrorPreset: isCnMirror ? "cn" : "official",
    mirrors: {...mirrorCfg, npmRegistry: dlNet.npmRegistry || "", pypiIndexUrl: dlNet.pypiIndexUrl || "", githubMirrorPrefix: dlNet.githubMirrorPrefix || ""},
    toolchains: items,
  };
}

function runCommandStream(cmd, args, {env, cwd, opKey = "", stdinText = "", onLine = () => {}, timeoutMs = 600_000} = {}) {
  return new Promise((resolve, reject) => {
    const op = opKey ? activeOps.get(opKey) : null;
    if (op?.abortController?.signal?.aborted) { reject(new Error("操作已取消")); return; }
    const child = spawn(cmd, args, {env, cwd, stdio: [stdinText ? "pipe" : "ignore", "pipe", "pipe"]});
    if (stdinText && child.stdin) {
      try { child.stdin.write(stdinText); child.stdin.end(); } catch {}
    }
    if (op) op.child = child;
    let output = "";
    const push = chunk => {
      const text = chunk.toString("utf8");
      if (output.length < 32_768) output += text;
      for (const line of text.split(/\r?\n/)) { const t = line.trim(); if (t) onLine(t); }
    };
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch {}
      reject(new Error(`命令超时 (${Math.round(timeoutMs / 1000)}s): ${cmd}`));
    }, timeoutMs);
    child.stdout?.on("data", push);
    child.stderr?.on("data", push);
    child.on("error", err => { clearTimeout(timer); reject(err); });
    child.on("close", (code, sig) => {
      clearTimeout(timer);
      if (op?.abortController?.signal?.aborted || sig === "SIGTERM") reject(new Error("操作已由用户终止"));
      else if (code === 0) resolve(output);
      else reject(new Error(output.trim().split(/\r?\n/).slice(-8).join("\n") || `${cmd} exit ${code}`));
    });
  });
}

async function fetchChecksumFromUrl(checksumUrl) {
  if (!checksumUrl) return {algo: "", hash: ""};
  try {
    const res = await fetch(checksumUrl, {headers: {"user-agent": "Solbraid-Toolchain-Studio/2.0"}, signal: AbortSignal.timeout(12_000)});
    if (!res.ok) return {algo: "", hash: ""};
    const text = (await res.text()).trim();
    const m512 = /\b([a-f0-9]{128})\b/i.exec(text);
    if (m512) return {algo: "sha512", hash: m512[1].toLowerCase()};
    const m256 = /\b([a-f0-9]{64})\b/i.exec(text);
    if (m256) return {algo: "sha256", hash: m256[1].toLowerCase()};
  } catch {}
  return {algo: "", hash: ""};
}

async function fetchNodeSha256(shasumsUrl, filename) {
  if (!shasumsUrl) return "";
  try {
    const res = await fetch(shasumsUrl, {signal: AbortSignal.timeout(12_000)});
    if (!res.ok) return "";
    const text = await res.text();
    for (const line of text.split(/\r?\n/)) {
      const m = /^([a-f0-9]{64})\s+\*?(.+)$/i.exec(line.trim());
      if (m && m[2].trim() === filename) return m[1].toLowerCase();
    }
  } catch {}
  return "";
}

async function extractTarXz(archivePath, destDir, env, opKey) {
  // 优先系统 xz/tar；缺少 xz 时自动降级使用 python3 内置 lzma + tarfile 解包
  const hasXz = await execFileAsync("which", ["xz"], {env, timeout: 3000}).then(() => true).catch(() => false);
  if (hasXz) {
    await runCommandStream("tar", ["-xJf", archivePath, "-C", destDir], {env, opKey});
    return;
  }
  const hasPy = await execFileAsync("which", ["python3"], {env, timeout: 3000}).then(() => true).catch(() => false);
  if (hasPy) {
    await runCommandStream("python3", ["-c", `import tarfile,sys; tarfile.open(sys.argv[1],'r:xz').extractall(sys.argv[2])`, archivePath, destDir], {env, opKey});
    return;
  }
  throw new Error("当前系统缺少 xz 解压工具（亦未安装 Python 3）。请先在「系统包」安装 xz-utils，或先安装 Python 工具链后再安装 Zig。");
}

async function setActiveSymlink(home, id, version) {
  const base = path.join(toolchainsRoot(home), id);
  const targetRel = path.join("versions", version);
  const absTarget = path.join(base, targetRel);
  if (!fss.existsSync(absTarget)) throw new Error(`未找到已安装的 ${id} 版本：${version}`);
  const linkPath = path.join(base, "current");
  const tmpLink = `${linkPath}.${randomUUID()}.tmp`;
  await fs.symlink(targetRel, tmpLink);
  await fs.rename(tmpLink, linkPath);
}

/** 严谨安装指定编译器与指定版本（支持历史版本、SHA-256 校验、切换前冒烟自检、并发锁与中止清理）。 */
export async function installToolchain({
  home, id, version = "", setDefault = true, extraTargets = [], aptComponents = [],
  onProgress = () => {}, onAptExec = null,
} = {}) {
  const spec = TOOLCHAIN_CATALOG.find(t => t.id === id);
  if (!spec) throw new Error(`未知工具链：${id}`);
  if (activeOps.has(id)) throw new Error(`${spec.name} 正在执行安装或切换任务，请等待完成或先终止当前任务`);

  const opKey = id;
  const opToken = randomUUID();
  const abortController = new AbortController();
  const assertNotAborted = () => { if (abortController.signal.aborted) throw new Error("操作已由用户终止"); };
  activeOps.set(opKey, {token: opToken, abortController, child: null, startedAt: Date.now(), id});
  const emit = (percent, message, logLine = "") => onProgress({id, stage: "install", percent, message, logLine});

  try {
    const root = toolchainsRoot(home);
    await fs.mkdir(root, {recursive: true, mode: 0o700});
    const dlNet = await readDownloadNetwork().catch(() => ({}));
    const mirrorCfg = await readJsonSafe(mirrorMetaPath(home), {});
    const {env: tcEnv} = buildManagedToolchainEnv(home, process.env, mirrorCfg);
    const env = {...process.env, ...downloadEnvironment(dlNet, process.env), ...tcEnv};
    const gnuArch = process.arch === "arm64" ? "aarch64" : "x86_64";

    if (id === "cpp-apt") {
      const pkgs = (Array.isArray(aptComponents) && aptComponents.length > 0 ? aptComponents : ["build-essential", "clang", "cmake", "ninja-build", "pkg-config"])
        .filter(p => /^[a-z0-9][a-z0-9.+-]{0,80}$/i.test(p));
      const direct = await isAptDirectlyWritable();
      if (!direct && typeof onAptExec === "function") {
        emit(20, `正在通过 Root 宿主通道更新索引并安装：${pkgs.join(" ")}…`);
        assertNotAborted();
        const updated = await onAptExec({action: "update"});
        assertNotAborted();
        if (!updated?.ok) throw new Error(updated?.stderr || updated?.error || "APT 索引更新失败");
        const res = await onAptExec({action: "install", packages: pkgs});
        assertNotAborted();
        if (!res?.ok) throw new Error(res?.stderr || res?.error || "APT 安装失败");
      } else if (direct) {
        const aptEnv = {...env, DEBIAN_FRONTEND: "noninteractive"};
        emit(20, "正在刷新 Ubuntu APT 索引…");
        await runCommandStream("apt-get", ["update", "-qq"], {env: aptEnv, opKey, onLine: l => emit(35, "apt-get update…", l)});
        emit(45, `正在安装 ${pkgs.join(" ")}…`);
        await runCommandStream("apt-get", ["install", "-y", "--no-install-recommends", ...pkgs], {env: aptEnv, opKey, onLine: l => emit(80, "安装系统构建套件…", l)});
      } else {
        throw new Error("当前免 Root 运行模式无法写入系统 APT 目录；如需免 Root 编译 C/C++，请安装 Zig（自带 zig cc / zig c++）。");
      }
      emit(100, "C/C++ 构建套件安装完成");
      return {ok: true, id, version: "apt", status: await listToolchains(home, {hasHostAptBridge: typeof onAptExec === "function"})};
    }

    emit(5, `正在解析 ${spec.name} 版本信息…`);
    const dl = await resolveVersionDownload({home, id, version, dlNet});
    const resolvedVer = assertSafeVersion(dl.version);
    const verDir = path.join(root, id, "versions", resolvedVer);
    const stageDir = path.join(root, id, `.stage-${resolvedVer}-${randomUUID().slice(0, 6)}`);

    // 需要 JVM 的 SDK 先前置检测 Java，避免白下载上百 MB 后无法执行
    if (spec.requiresJdk) {
      const hasJava = await execFileAsync("java", ["-version"], {env, timeout: 6000}).then(() => true).catch(() => false);
      if (!hasJava) {
        throw new Error(`${spec.name} 依赖 Java 运行时，请先在工具链中心安装 OpenJDK (Temurin) 17 或 21。`);
      }
    }

    // Zig 安装前先预检 xz / python3，避免白下 48MB
    if (id === "zig") {
      const hasXz = await execFileAsync("which", ["xz"], {env, timeout: 3000}).then(() => true).catch(() => false);
      const hasPy = await execFileAsync("which", ["python3"], {env, timeout: 3000}).then(() => true).catch(() => false);
      if (!hasXz && !hasPy) {
        throw new Error("解压 Zig (.tar.xz) 需要 xz 或 python3。请先在「系统包」安装 xz-utils，或先安装 Python。");
      }
    }
    if ((dl.filename && dl.filename.endsWith(".zip")) || id === "android-sdk") {
      const hasUnzip = await execFileAsync("which", ["unzip"], {env, timeout: 3000}).then(() => true).catch(() => false);
      if (!hasUnzip) {
        throw new Error("解压该安装包 (.zip) 需要 unzip 工具，请先在「系统包」安装 unzip。");
      }
    }
    assertNotAborted();

    if (id === "android-sdk") {
      const sdkHome = detectAndroidSdkHome(home, env);
      await fs.mkdir(sdkHome, {recursive: true});
      const sdkMgrBin = path.join(sdkHome, "cmdline-tools", "latest", "bin", "sdkmanager");
      if (!fss.existsSync(sdkMgrBin)) {
        const tmpZip = path.join(sdkHome, `.cmdline-${randomUUID().slice(0, 6)}.zip.part`);
        const tmpDir = path.join(sdkHome, `.cmdline-stage-${randomUUID().slice(0, 6)}`);
        emit(18, "正在下载 Android Command-line Tools (sdkmanager)…");
        try {
          await runCommandStream("curl", ["-fL", "--retry", "2", "--connect-timeout", "20", "-o", tmpZip, dl.cmdlineToolsUrl || dl.url], {
            env, opKey, onLine: l => emit(35, "下载 Android cmdline-tools…", l),
          });
          await fs.mkdir(tmpDir, {recursive: true});
          await runCommandStream("unzip", ["-oq", tmpZip, "-d", tmpDir], {env, opKey});
          const srcDir = fss.existsSync(path.join(tmpDir, "cmdline-tools")) ? path.join(tmpDir, "cmdline-tools") : tmpDir;
          const latestDir = path.join(sdkHome, "cmdline-tools", "latest");
          await fs.mkdir(path.dirname(latestDir), {recursive: true});
          await fs.rm(latestDir, {recursive: true, force: true});
          await fs.rename(srcDir, latestDir);
          await fs.chmod(sdkMgrBin, 0o755).catch(() => {});
        } finally {
          await fs.rm(tmpZip, {force: true}).catch(() => {});
          await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
        }
      }
      const androidEnv = {...env, ANDROID_HOME: sdkHome, ANDROID_SDK_ROOT: sdkHome};
      emit(48, "正在接受 Android SDK 官方许可协议…");
      await runCommandStream(sdkMgrBin, [`--sdk_root=${sdkHome}`, "--licenses"], {
        env: androidEnv, opKey, stdinText: "y\n".repeat(48), timeoutMs: 120_000,
      }).catch(() => {});
      const selectedExtras = Array.isArray(extraTargets) ? extraTargets : [];
      const wantArm64Aapt2 = selectedExtras.includes("arm64-aapt2");
      const sdkPkgs = [`platforms;${resolvedVer}`];
      for (const comp of selectedExtras) {
        if (comp === "arm64-aapt2") continue;
        if (comp === "build-tools") {
          sdkPkgs.push(`build-tools;${dl.buildToolsVersion || `${resolvedVer.replace(/^android-/, "")}.0.0`}`);
        } else if (/^[a-z0-9;._-]+$/i.test(comp)) {
          sdkPkgs.push(comp);
        }
      }
      emit(60, `正在通过 sdkmanager 安装：${sdkPkgs.join(" ")}…`);
      await runCommandStream(sdkMgrBin, [`--sdk_root=${sdkHome}`, ...sdkPkgs], {
        env: androidEnv, opKey, stdinText: "y\n".repeat(20),
        onLine: l => emit(78, `安装 Android SDK (${resolvedVer})…`, l),
      });
      if (process.arch === "arm64" && (wantArm64Aapt2 || !(await inspectAndroidSdk(home, androidEnv)).aapt2Path)) {
        emit(88, "正在配置 ARM64 原生 aapt2（用于本机 Gradle 编译）…");
        const aapt2Dir = path.join(sdkHome, "arm64-tools");
        await fs.mkdir(aapt2Dir, {recursive: true});
        const aapt2Tmp = path.join(aapt2Dir, `aapt2.${randomUUID().slice(0, 6)}.tmp`);
        const aapt2Url = githubDownloadUrl("https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.1.0/aapt2", dlNet);
        try {
          await runCommandStream("curl", ["-fL", "--retry", "2", "-o", aapt2Tmp, aapt2Url], {env, opKey});
          await fs.chmod(aapt2Tmp, 0o755);
          await execFileAsync(aapt2Tmp, ["version"], {env, timeout: 6000});
          await fs.rename(aapt2Tmp, path.join(aapt2Dir, "aapt2"));
        } catch (err) {
          await fs.rm(aapt2Tmp, {force: true}).catch(() => {});
          emit(90, `ARM64 aapt2 下载跳过：${err?.message || err}`);
        }
      }
      if (setDefault) {
        const st = await readJsonSafe(statePath(home), {active: {}});
        st.active = {...(st.active || {}), "android-sdk": resolvedVer};
        await writeJsonAtomic(statePath(home), st);
      }
    } else if (id === "rust") {
      const rustDir = path.join(root, "rust");
      const cargoHome = path.join(rustDir, "cargo");
      const rustupHome = path.join(rustDir, "rustup");
      await fs.mkdir(rustDir, {recursive: true});
      const rustEnv = {
        ...env, CARGO_HOME: cargoHome, RUSTUP_HOME: rustupHome,
        RUSTUP_DIST_SERVER: mirrorCfg.rustupDistServer || env.RUSTUP_DIST_SERVER || "https://rsproxy.cn",
        RUSTUP_UPDATE_ROOT: mirrorCfg.rustupUpdateRoot || env.RUSTUP_UPDATE_ROOT || "https://rsproxy.cn/rustup",
      };
      const rustupBin = path.join(cargoHome, "bin", "rustup");
      if (!fss.existsSync(rustupBin)) {
        const initScript = path.join(rustDir, `rustup-init.${randomUUID().slice(0, 6)}.sh`);
        emit(18, "正在初始化 rustup 管理器…");
        try {
          await runCommandStream("curl", ["-fL", "--retry", "2", "-o", initScript, "https://rsproxy.cn/rustup-init.sh"], {env: rustEnv, opKey})
            .catch(() => runCommandStream("curl", ["-fL", "--retry", "2", "-o", initScript, "https://sh.rustup.rs"], {env: rustEnv, opKey}));
          await fs.chmod(initScript, 0o755);
          await runCommandStream("sh", [initScript, "-y", "--no-modify-path", "--profile", "minimal", "--default-toolchain", resolvedVer], {
            env: rustEnv, opKey, onLine: l => emit(60, `安装 Rust ${resolvedVer}…`, l),
          });
        } finally { await fs.rm(initScript, {force: true}).catch(() => {}); }
      } else {
        emit(35, `正在通过 rustup 安装 Rust ${resolvedVer}…`);
        await runCommandStream(rustupBin, ["toolchain", "install", resolvedVer, "--profile", "minimal"], {
          env: rustEnv, opKey, onLine: l => emit(70, `安装 Rust ${resolvedVer}…`, l),
        });
        if (setDefault) await runCommandStream(rustupBin, ["default", resolvedVer], {env: rustEnv, opKey});
      }
      if (Array.isArray(extraTargets) && extraTargets.length > 0) {
        const cleanTargets = extraTargets.filter(t => /^[a-z0-9_-]+$/i.test(t));
        if (cleanTargets.length) {
          emit(85, `追加交叉编译目标：${cleanTargets.join(", ")}…`);
          await runCommandStream(rustupBin, ["target", "add", "--toolchain", resolvedVer, ...cleanTargets], {env: rustEnv, opKey});
        }
      }
    } else if (id === "python") {
      const pyDir = path.join(root, "python");
      const pyBin = path.join(pyDir, "bin");
      await fs.mkdir(pyBin, {recursive: true});
      const uvBin = path.join(pyBin, "uv");
      if (!fss.existsSync(uvBin)) {
        const uvUrl = githubDownloadUrl(`https://github.com/astral-sh/uv/releases/latest/download/uv-${gnuArch}-unknown-linux-gnu.tar.gz`, dlNet);
        const tmp = path.join(pyDir, `uv-${randomUUID().slice(0, 6)}.tar.gz.part`);
        emit(20, `正在下载 uv (${gnuArch})…`);
        try {
          await runCommandStream("curl", ["-fL", "--retry", "2", "-o", tmp, uvUrl], {env, opKey});
          await fs.mkdir(stageDir, {recursive: true});
          await runCommandStream("tar", ["-xzf", tmp, "-C", stageDir], {env, opKey});
          const sub = (await fs.readdir(stageDir))[0];
          for (const b of ["uv", "uvx"]) {
            if (fss.existsSync(path.join(stageDir, sub, b))) {
              await fs.copyFile(path.join(stageDir, sub, b), path.join(pyBin, b));
              await fs.chmod(path.join(pyBin, b), 0o755);
            }
          }
        } finally {
          await fs.rm(tmp, {force: true}).catch(() => {});
          await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {});
        }
      }
      emit(55, `正在通过 uv 安装独立便携 Python ${resolvedVer}…`);
      const pyEnv = {...env, UV_PYTHON_INSTALL_DIR: path.join(pyDir, "pythons")};
      await runCommandStream(uvBin, ["python", "install", resolvedVer], {env: pyEnv, opKey, onLine: l => emit(80, `安装 Python ${resolvedVer}…`, l)});
      if (setDefault) await switchPythonActive(home, resolvedVer, pyEnv);
    } else if (id === "pnpm") {
      emit(25, `正在安装 pnpm@${resolvedVer}…`);
      await fs.mkdir(stageDir, {recursive: true});
      const npmCmd = fss.existsSync(path.join(BUNDLED_NODE_DIR, "npm")) ? path.join(BUNDLED_NODE_DIR, "npm") : "npm";
      try {
        await runCommandStream(npmCmd, ["install", "-g", `pnpm@${resolvedVer}`, "--prefix", stageDir], {env, opKey, onLine: l => emit(65, `安装 pnpm@${resolvedVer}…`, l)});
        await execFileAsync(path.join(stageDir, "bin", "pnpm"), ["--version"], {env, timeout: 8000});
        await fs.mkdir(path.dirname(verDir), {recursive: true});
        await fs.rm(verDir, {recursive: true, force: true});
        await fs.rename(stageDir, verDir);
        if (setDefault) await setActiveSymlink(home, id, resolvedVer);
      } finally { await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {}); }
    } else {
      // go / zig / node / java / bun / gradle / kotlin / maven / dotnet / dart / wasi-sdk 统一版本化归档安装流水线
      const tmpArchive = path.join(root, id, `${dl.filename || resolvedVer}.${randomUUID().slice(0, 6)}.part`);
      await fs.mkdir(path.dirname(tmpArchive), {recursive: true});
      emit(20, `正在下载 ${spec.name} ${resolvedVer}…`);
      try {
        await runCommandStream("curl", ["-fL", "--retry", "2", "--connect-timeout", "20", "-o", tmpArchive, dl.url], {
          env, opKey, onLine: l => emit(45, `下载 ${spec.name} ${resolvedVer}…`, l),
        });
        assertNotAborted();
        let checkAlgo = dl.sha512 ? "sha512" : (dl.sha256 ? "sha256" : "");
        let expectedHash = dl.sha512 || dl.sha256 || "";
        if (!expectedHash && id === "node" && dl.shasumsUrl) {
          expectedHash = await fetchNodeSha256(dl.shasumsUrl, dl.filename);
          if (expectedHash) checkAlgo = "sha256";
          else throw new Error("无法获取 Node.js 官方 SHASUMS256.txt 校验清单，已中止安装以防下载损坏或篡改");
        } else if (!expectedHash && dl.checksumUrl) {
          const fetched = await fetchChecksumFromUrl(dl.checksumUrl);
          checkAlgo = fetched.algo;
          expectedHash = fetched.hash;
          if (!expectedHash) throw new Error(`无法获取 ${spec.name} 官方校验文件，已中止安装以防下载损坏或篡改`);
        }
        if (expectedHash && checkAlgo) {
          emit(68, `正在执行 ${checkAlgo.toUpperCase()} 完整性校验…`);
          const actual = await shaFile(tmpArchive, checkAlgo);
          if (actual.toLowerCase() !== expectedHash.toLowerCase()) {
            throw new Error(`${checkAlgo.toUpperCase()} 校验失败（期望 ${expectedHash.slice(0, 12)}，实际 ${actual.slice(0, 12)}），已丢弃安装包`);
          }
        }
        assertNotAborted();
        emit(78, `正在解压 ${spec.name} ${resolvedVer}…`);
        await fs.mkdir(stageDir, {recursive: true});
        if (dl.filename.endsWith(".tar.xz")) {
          await extractTarXz(tmpArchive, stageDir, env, opKey);
        } else if (dl.filename.endsWith(".zip")) {
          await runCommandStream("unzip", ["-oq", tmpArchive, "-d", stageDir], {env, opKey});
        } else {
          await runCommandStream("tar", ["-xzf", tmpArchive, "-C", stageDir], {env, opKey});
        }
        assertNotAborted();
        const entries = await fs.readdir(stageDir);
        const extractedRoot = entries.length === 1 && fss.statSync(path.join(stageDir, entries[0])).isDirectory()
          ? path.join(stageDir, entries[0]) : stageDir;

        if (id === "bun") {
          emit(88, `正在验证 Bun ${resolvedVer} 可执行性…`);
          const bunStageRoot = path.join(root, id, `.bun-stage-${resolvedVer}-${randomUUID().slice(0, 6)}`);
          const bunBinDir = path.join(bunStageRoot, "bin");
          try {
            await fs.mkdir(bunBinDir, {recursive: true});
            await fs.copyFile(path.join(extractedRoot, "bun"), path.join(bunBinDir, "bun"));
            await fs.chmod(path.join(bunBinDir, "bun"), 0o755);
            await fs.symlink("bun", path.join(bunBinDir, "bunx"));
            await execFileAsync(path.join(bunBinDir, "bun"), ["--version"], {env: {...env, BUN_INSTALL: bunStageRoot}, timeout: 10_000});
            assertNotAborted();
            await fs.mkdir(path.dirname(verDir), {recursive: true});
            await fs.rm(verDir, {recursive: true, force: true});
            await fs.rename(bunStageRoot, verDir);
          } finally {
            await fs.rm(bunStageRoot, {recursive: true, force: true}).catch(() => {});
          }
        } else {
          // 切换前冒烟自检：使用隔离的新版本环境变量，确保探测的是刚解包的版本而非旧 current
          emit(88, `正在验证 ${spec.name} ${resolvedVer} 可执行性…`);
          const probeBinDir = spec.binSubdir ? path.join(extractedRoot, spec.binSubdir) : extractedRoot;
          const probeBin = path.join(probeBinDir, spec.probe.cmd);
          const sep = process.platform === "win32" ? ";" : ":";
          const probeEnv = {
            ...env,
            PATH: `${probeBinDir}${sep}${env.PATH || ""}`,
            ...(id === "go" ? {GOROOT: extractedRoot} : {}),
            ...(id === "java" ? {JAVA_HOME: extractedRoot} : {}),
            ...(id === "gradle" ? {GRADLE_HOME: extractedRoot} : {}),
            ...(id === "kotlin" ? {KOTLIN_HOME: extractedRoot} : {}),
            ...(id === "maven" ? {MAVEN_HOME: extractedRoot} : {}),
            ...(id === "dotnet" ? {DOTNET_ROOT: extractedRoot} : {}),
            ...(id === "dart" ? {DART_SDK: extractedRoot} : {}),
            ...(id === "wasi-sdk" ? {WASI_SDK_PATH: extractedRoot} : {}),
          };
          await execFileAsync(probeBin, spec.probe.args, {env: probeEnv, timeout: 10_000});
          assertNotAborted();
          await fs.mkdir(path.dirname(verDir), {recursive: true});
          await fs.rm(verDir, {recursive: true, force: true});
          await fs.rename(extractedRoot, verDir);
        }
        if (setDefault) await setActiveSymlink(home, id, resolvedVer);
      } finally {
        await fs.rm(tmpArchive, {force: true}).catch(() => {});
        await fs.rm(stageDir, {recursive: true, force: true}).catch(() => {});
      }
    }

    emit(95, "正在更新全局工具链环境投影…");
    await writeToolchainProfile(home);
    emit(100, `${spec.name} ${resolvedVer} 安装完成`);
    return {ok: true, id, version: resolvedVer, status: await listToolchains(home, {hasHostAptBridge: typeof onAptExec === "function"})};
  } finally {
    if (activeOps.get(opKey)?.token === opToken) activeOps.delete(opKey);
  }
}

async function switchPythonActive(home, version, env) {
  const pyDir = path.join(toolchainsRoot(home), "python");
  const uvBin = path.join(pyDir, "bin", "uv");
  const pyEnv = {...env, UV_PYTHON_INSTALL_DIR: path.join(pyDir, "pythons")};
  const {stdout} = await execFileAsync(uvBin, ["python", "find", version], {env: pyEnv, timeout: 10_000});
  const pyPath = stdout.trim();
  if (!pyPath || !fss.existsSync(pyPath)) throw new Error(`未找到已安装的 Python ${version}`);
  await fs.rm(path.join(pyDir, "bin", "python3"), {force: true});
  await fs.rm(path.join(pyDir, "bin", "python"), {force: true});
  await fs.symlink(pyPath, path.join(pyDir, "bin", "python3"));
  await fs.symlink(pyPath, path.join(pyDir, "bin", "python"));
  const st = await readJsonSafe(statePath(home), {active: {}});
  st.active = {...(st.active || {}), python: version};
  await writeJsonAtomic(statePath(home), st);
}

/** 秒级切换已安装的多版本工具链默认激活版本（原子切换 current 软链）。 */
export async function useToolchainVersion({home, id, version, hasHostAptBridge = false} = {}) {
  const spec = TOOLCHAIN_CATALOG.find(t => t.id === id);
  if (!spec) throw new Error(`未知工具链：${id}`);
  if (activeOps.has(id)) throw new Error(`${spec.name} 正在安装中，请等待完成后再切换版本`);
  const ver = assertSafeVersion(version);
  const {env: tcEnv} = buildManagedToolchainEnv(home, process.env, await readJsonSafe(mirrorMetaPath(home), {}));
  const env = {...process.env, ...tcEnv};
  if (id === "android-sdk") {
    const sdkHome = detectAndroidSdkHome(home, env);
    if (!fss.existsSync(path.join(sdkHome, "platforms", ver))) {
      throw new Error(`未在 ${sdkHome}/platforms 中找到 ${ver}`);
    }
    const st = await readJsonSafe(statePath(home), {active: {}});
    st.active = {...(st.active || {}), "android-sdk": ver};
    await writeJsonAtomic(statePath(home), st);
  } else if (id === "rust") {
    const rustupBin = path.join(toolchainsRoot(home), "rust", "cargo", "bin", "rustup");
    await execFileAsync(rustupBin, ["default", ver], {env, timeout: 15_000});
  } else if (id === "python") {
    await switchPythonActive(home, ver, env);
  } else {
    await setActiveSymlink(home, id, ver);
  }
  await writeToolchainProfile(home);
  return {ok: true, id, version: ver, activeVersion: ver, status: await listToolchains(home, {hasHostAptBridge})};
}

/** 卸载单个指定版本，或卸载该工具链全部托管版本。 */
export async function uninstallToolchain({home, id, version = "", hasHostAptBridge = false} = {}) {
  const spec = TOOLCHAIN_CATALOG.find(t => t.id === id);
  if (!spec || id === "cpp-apt") throw new Error(`工具链 ${id} 不支持托管卸载`);
  if (activeOps.has(id)) throw new Error(`${spec.name} 正在执行安装任务，请先终止后再卸载`);
  const root = toolchainsRoot(home);
  const {env: tcEnv} = buildManagedToolchainEnv(home, process.env, await readJsonSafe(mirrorMetaPath(home), {}));
  const env = {...process.env, ...tcEnv};

  if (version) {
    const ver = assertSafeVersion(version);
    if (id === "android-sdk") {
      const sdkHome = detectAndroidSdkHome(home, env);
      await fs.rm(path.join(sdkHome, "platforms", ver), {recursive: true, force: true});
    } else if (id === "rust") {
      const rustupBin = path.join(root, "rust", "cargo", "bin", "rustup");
      await execFileAsync(rustupBin, ["toolchain", "uninstall", ver], {env, timeout: 30_000});
    } else if (id === "python") {
      const uvBin = path.join(root, "python", "bin", "uv");
      const pyEnv = {...env, UV_PYTHON_INSTALL_DIR: path.join(root, "python", "pythons")};
      await execFileAsync(uvBin, ["python", "uninstall", ver], {env: pyEnv, timeout: 30_000});
      const st = await readJsonSafe(statePath(home), {active: {}});
      const {versions: remPy} = await listInstalledVersionsFor(home, "python", pyEnv);
      if (st.active?.python === ver || !fss.existsSync(path.join(root, "python", "bin", "python3"))) {
        if (remPy.length > 0) {
          await switchPythonActive(home, remPy[0], pyEnv).catch(() => {});
        } else {
          await fs.rm(path.join(root, "python", "bin", "python3"), {force: true}).catch(() => {});
          await fs.rm(path.join(root, "python", "bin", "python"), {force: true}).catch(() => {});
          if (st.active) delete st.active.python;
          await writeJsonAtomic(statePath(home), st);
        }
      }
    } else {
      await fs.rm(path.join(root, id, "versions", ver), {recursive: true, force: true});
      const curLink = path.join(root, id, "current");
      // 直接使用 readlink 而非 existsSync：目标刚被删除后 current 为悬空软链，existsSync 会误判为 false
      const linkedTarget = await fs.readlink(curLink).catch(() => "");
      if (linkedTarget && path.basename(linkedTarget) === ver) {
        await fs.rm(curLink, {force: true});
        const remaining = await fs.readdir(path.join(root, id, "versions")).catch(() => []);
        if (remaining.length > 0) {
          remaining.sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
          await setActiveSymlink(home, id, remaining[0]);
        }
      }
    }
  } else {
    if (id === "android-sdk") throw new Error("请指定要移除的 Android SDK API 版本（如 android-35），避免误删整个 android-sdk 目录");
    await fs.rm(path.join(root, id), {recursive: true, force: true});
    if (id === "java") await fs.rm(path.join(root, "jdk"), {recursive: true, force: true});
  }
  await writeToolchainProfile(home);
  return {ok: true, id, version, status: await listToolchains(home, {hasHostAptBridge})};
}

export async function applyMirrorPreset({home, preset = "cn"} = {}) {
  const p = MIRROR_PRESETS[preset];
  if (!p) throw new Error(`不支持的镜像预设：${preset}`);
  const curNet = await readDownloadNetwork().catch(() => ({}));
  await saveDownloadNetwork({...curNet, npmRegistry: p.npmRegistry, pypiIndexUrl: p.pypiIndexUrl});
  await writeJsonAtomic(mirrorMetaPath(home), {preset: p.id, goProxy: p.goProxy, rustupDistServer: p.rustupDistServer, rustupUpdateRoot: p.rustupUpdateRoot});
  await fs.rm(path.join(toolchainsRoot(home), "version-cache"), {recursive: true, force: true}).catch(() => {});
  await writeToolchainProfile(home);
  return {ok: true, preset: p.id, label: p.label};
}

export async function getCacheStats(home) {
  const buckets = [
    {id: "npm", label: "npm 缓存", path: path.join(home, ".npm")},
    {id: "pnpm", label: "pnpm 存储", path: path.join(home, ".local", "share", "pnpm")},
    {id: "go", label: "Go 编译/模块缓存", path: path.join(home, ".cache", "go-build")},
    {id: "cu-go", label: "跨端编译 Go 缓存", path: path.join(home, ".galiais", "computer-use", "go-cache")},
    {id: "cargo", label: "Cargo 索引缓存", path: path.join(toolchainsRoot(home), "rust", "cargo", "registry", "cache")},
    {id: "uv", label: "uv 缓存", path: path.join(home, ".cache", "uv")},
    {id: "pip", label: "pip 缓存", path: path.join(home, ".cache", "pip")},
    {id: "gradle", label: "Gradle 缓存", path: path.join(home, ".gradle", "caches")},
    {id: "maven", label: "Maven 本地仓库", path: path.join(home, ".m2", "repository")},
    {id: "dart", label: "Dart Pub 缓存", path: path.join(home, ".pub-cache")},
    {id: "nuget", label: ".NET NuGet 缓存", path: path.join(home, ".nuget", "packages")},
    {id: "apt", label: "APT 下载包缓存", path: "/var/cache/apt/archives"},
  ];
  const entries = [];
  let totalBytes = 0;
  for (const b of buckets) {
    const bytes = await dirSizeBytes(b.path, 4);
    totalBytes += bytes;
    entries.push({...b, bytes});
  }
  return {ok: true, totalBytes, entries};
}

export async function cleanCaches({home, ids = [], onAptExec = null} = {}) {
  const stats = await getCacheStats(home);
  const selected = ids.length ? stats.entries.filter(e => ids.includes(e.id)) : stats.entries.filter(e => e.id !== "gradle");
  let freedBytes = 0;
  for (const e of selected) {
    if (e.bytes <= 0) continue;
    try {
      if (e.id === "apt") {
        if (await isAptDirectlyWritable()) await execFileAsync("apt-get", ["clean"], {timeout: 20_000}).catch(() => {});
        else if (typeof onAptExec === "function") await onAptExec({action: "clean"}).catch(() => {});
      } else {
        await fs.rm(e.path, {recursive: true, force: true});
      }
      freedBytes += e.bytes;
    } catch {}
  }
  return {ok: true, freedBytes, after: await getCacheStats(home)};
}

export {fetchAvailableVersions};
