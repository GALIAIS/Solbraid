// Solbraid 多生态项目依赖、版本约束体检、Ubuntu 系统包与本地/交叉编译引擎（零第三方依赖）。
// 严谨安全：严格包名校验防参数注入、Python 自动隔离 .venv 避开 PEP 668、无 sh -c 拼接、Root 宿主 APT 桥接。
import fs from "node:fs/promises";
import fss from "node:fs";
import path from "node:path";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {downloadEnvironment, readDownloadNetwork} from "./download-network.mjs";
import {buildManagedToolchainEnv, isAptDirectlyWritable, listToolchains} from "./toolchain-hub.mjs";
import {resolveSafeHomePath} from "./safe-file-io.mjs";

const execFileAsync = promisify(execFile);
const SAFE_PKG_RE = /^[A-Za-z0-9@][A-Za-z0-9@._/:^~>=<+,-]{0,127}$/;
const SAFE_APT_RE = /^[a-z0-9][a-z0-9.+-]{0,80}$/i;
const PROTECTED_APT_PKGS = new Set([
  "apt", "apt-utils", "dpkg", "bash", "dash", "libc6", "libgcc-s1", "libstdc++6",
  "util-linux", "coreutils", "base-files", "base-passwd", "python3", "python3-minimal",
  "curl", "ca-certificates", "git", "openssh-client", "libssl3", "libssl3t64",
  "unzip", "tar", "gzip", "findutils", "grep", "sed", "gawk", "passwd", "login",
  "init-system-helpers", "debianutils", "diffutils",
]);
const ALLOWED_COMPILE_CMDS = new Set([
  "go", "cargo", "rustc", "zig", "gcc", "g++", "clang", "clang++", "cmake", "make", "ninja",
  "npm", "pnpm", "bun", "yarn", "mvn", "gradle", "./gradlew", "javac", "kotlinc", "dart", "dotnet", "python3", "uv",
]);
const ALLOWED_TARGET_OS = new Set(["", "linux", "darwin", "windows"]);
const ALLOWED_TARGET_ARCH = new Set(["", "amd64", "arm64"]);

const NODE_LOCKFILES = new Map([
  ["pnpm-lock.yaml", "pnpm"], ["bun.lock", "bun"], ["bun.lockb", "bun"],
  ["npm-shrinkwrap.json", "npm"], ["package-lock.json", "npm"], ["yarn.lock", "yarn"],
]);

async function nodePackageManager(dir, home, pkg) {
  const root = await fs.realpath(home);
  // 子包通常复用工作区根目录的锁文件，独立 Git 仓库不能继承上级项目的配置。
  for (let current = dir; ; current = path.dirname(current)) {
    if (current !== dir) {
      try {
        pkg = JSON.parse(await fs.readFile(path.join(current, "package.json"), "utf8"));
      } catch (err) {
        if (err.code !== "ENOENT") throw err;
        pkg = {};
      }
    }
    const locks = [...NODE_LOCKFILES.keys()].filter(file => fss.existsSync(path.join(current, file)));
    const declared = pkg.packageManager;
    let manager;
    if (declared != null) {
      manager = typeof declared === "string" && /^(npm|pnpm|bun|yarn)@[^\s]+$/.exec(declared)?.[1];
      if (!manager) throw new Error("package.json 的 packageManager 无效或不受支持");
    } else if (locks.length) {
      const managers = new Set(locks.map(file => NODE_LOCKFILES.get(file)));
      if (managers.size > 1) throw new Error("检测到多种包管理器的锁文件，请在 package.json 中指定 packageManager");
      manager = NODE_LOCKFILES.get(locks[0]);
    }
    if (manager) return {manager, root: current, lockfile: locks.find(file => NODE_LOCKFILES.get(file) === manager) || ""};
    if (current === root || path.dirname(current) === current || fss.existsSync(path.join(current, ".git"))) break;
  }
  return {manager: "npm", root: dir, lockfile: ""};
}

async function buildExecutionEnv(home) {
  const dlNet = await readDownloadNetwork().catch(() => ({}));
  let mirrorCfg = {};
  try { mirrorCfg = JSON.parse(await fs.readFile(path.join(home, ".galiais", "toolchains", "mirrors.json"), "utf8")); } catch {}
  const {env: tcEnv} = buildManagedToolchainEnv(home, process.env, mirrorCfg);
  return {...process.env, ...downloadEnvironment(dlNet, process.env), ...tcEnv};
}

function runProcess(cmd, args, {cwd, env, timeoutMs = 300_000, onLine = () => {}} = {}) {
  const started = Date.now();
  return new Promise(resolve => {
    const child = spawn(cmd, args, {cwd, env, stdio: ["ignore", "pipe", "pipe"]});
    let stdout = "", stderr = "";
    const feed = (buf, isErr) => {
      const s = buf.toString("utf8");
      if (isErr) { if (stderr.length < 32_000) stderr += s; }
      else { if (stdout.length < 64_000) stdout += s; }
      for (const l of s.split(/\r?\n/)) { const t = l.trim(); if (t) onLine(t); }
    };
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch {}
      resolve({ok: false, exitCode: -1, timedOut: true, ms: Date.now() - started, stdout, stderr: stderr + "\n[执行超时]"});
    }, timeoutMs);
    child.stdout?.on("data", d => feed(d, false));
    child.stderr?.on("data", d => feed(d, true));
    child.on("error", err => {
      clearTimeout(timer);
      const message = err.code === "ENOENT" ? `找不到 ${cmd}，请确认已安装并可通过 PATH 运行` : err.message;
      resolve({ok: false, exitCode: -1, ms: Date.now() - started, stdout, stderr: message});
    });
    child.on("close", code => {
      clearTimeout(timer);
      resolve({ok: code === 0, exitCode: code ?? -1, ms: Date.now() - started, stdout, stderr});
    });
  });
}

function parseGoMod(text) {
  const deps = [];
  let inBlock = false;
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.replace(/\/\/.*$/, "").trim();
    if (!l) continue;
    if (/^require\s*\($/.test(l)) { inBlock = true; continue; }
    if (inBlock && l === ")") { inBlock = false; continue; }
    const m = inBlock ? /^(\S+)\s+(\S+)/.exec(l) : /^require\s+(\S+)\s+(\S+)/.exec(l);
    if (m) deps.push({name: m[1], version: m[2], dev: raw.includes("// indirect")});
  }
  return {
    moduleName: /^module\s+(\S+)/m.exec(text)?.[1] || "",
    goVersion: /^go\s+(\S+)/m.exec(text)?.[1] || "",
    deps,
  };
}

function parseCargoToml(text) {
  const deps = [];
  let section = "";
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.replace(/#.*$/, "").trim();
    if (!l) continue;
    const sec = /^\[([^\]]+)\]$/.exec(l);
    if (sec) { section = sec[1].trim(); continue; }
    if (section === "dependencies" || section === "dev-dependencies") {
      const kv = /^([A-Za-z0-9._-]+)\s*=\s*(.+)$/.exec(l);
      if (kv) {
        const ver = /version\s*=\s*"([^"]+)"/.exec(kv[2])?.[1] || kv[2].replace(/^"|"$/g, "");
        deps.push({name: kv[1], version: ver.slice(0, 48), dev: section === "dev-dependencies"});
      }
    }
  }
  return deps;
}

function parsePythonDeps(reqText, pyprojectText) {
  const deps = [];
  if (reqText) {
    for (const raw of reqText.split(/\r?\n/)) {
      const l = raw.replace(/#.*$/, "").trim();
      if (!l || l.startsWith("-")) continue;
      const m = /^([A-Za-z0-9._-]+)(.*)$/.exec(l);
      if (m) deps.push({name: m[1], version: (m[2] || "*").trim(), dev: false});
    }
  }
  if (pyprojectText) {
    const arr = /dependencies\s*=\s*\[([\s\S]*?)\]/.exec(pyprojectText)?.[1] || "";
    for (const m of arr.matchAll(/"([^"]+)"|'([^']+)'/g)) {
      const spec = (m[1] || m[2] || "").trim();
      const p = /^([A-Za-z0-9._-]+)(.*)$/.exec(spec);
      if (p && !deps.some(d => d.name.toLowerCase() === p[1].toLowerCase())) {
        deps.push({name: p[1], version: (p[2] || "*").trim(), dev: false});
      }
    }
  }
  return deps;
}

/** 扫描项目根目录的版本约束文件（.tool-versions / .nvmrc / .python-version / rust-toolchain.toml）。 */
async function collectVersionHints(dir) {
  const hints = [];
  const addHint = (toolchainId, required, source) => {
    const r = String(required || "").trim();
    if (r && !hints.some(h => h.toolchainId === toolchainId && h.source === source)) {
      hints.push({toolchainId, required: r, source});
    }
  };
  const readTrim = async name => {
    try { return (await fs.readFile(path.join(dir, name), "utf8")).trim(); } catch { return ""; }
  };
  const toolVers = await readTrim(".tool-versions");
  if (toolVers) {
    for (const line of toolVers.split(/\r?\n/)) {
      const m = /^([a-zA-Z0-9_-]+)\s+(\S+)/.exec(line.replace(/#.*$/, "").trim());
      if (!m) continue;
      const map = {
        golang: "go", go: "go", nodejs: "node", node: "node", python: "python", rust: "rust", zig: "zig",
        java: "java", pnpm: "pnpm", bun: "bun", gradle: "gradle", kotlin: "kotlin", maven: "maven", dotnet: "dotnet", dart: "dart",
      };
      if (map[m[1]]) addHint(map[m[1]], m[2], ".tool-versions");
    }
  }
  const nvmrc = await readTrim(".nvmrc") || await readTrim(".node-version");
  if (nvmrc) addHint("node", nvmrc.replace(/^v/, ""), fss.existsSync(path.join(dir, ".nvmrc")) ? ".nvmrc" : ".node-version");
  const pyVer = await readTrim(".python-version");
  if (pyVer) addHint("python", pyVer, ".python-version");
  const goVerFile = await readTrim(".go-version");
  if (goVerFile) addHint("go", goVerFile.replace(/^go/, ""), ".go-version");
  const rustTc = await readTrim("rust-toolchain.toml");
  if (rustTc) {
    const ch = /channel\s*=\s*"([^"]+)"/.exec(rustTc)?.[1];
    if (ch) addHint("rust", ch, "rust-toolchain.toml");
  }
  const globalJson = await readTrim("global.json");
  if (globalJson) {
    try {
      const parsed = JSON.parse(globalJson);
      if (parsed?.sdk?.version) addHint("dotnet", String(parsed.sdk.version), "global.json");
    } catch {}
  }
  const gradleWrap = await readTrim(path.join("gradle", "wrapper", "gradle-wrapper.properties"));
  if (gradleWrap) {
    const gm = /gradle-(\d+\.\d+(?:\.\d+)?)-(?:bin|all)\.zip/.exec(gradleWrap);
    if (gm) addHint("gradle", gm[1], "gradle-wrapper.properties");
  }
  for (const gf of [path.join("app", "build.gradle.kts"), path.join("app", "build.gradle"), "build.gradle.kts", "build.gradle"]) {
    const txt = await readTrim(gf);
    const cm = /compileSdk(?:Version)?\s*[=(]?\s*(\d{2})/.exec(txt);
    if (cm) { addHint("android-sdk", `android-${cm[1]}`, gf.replace(/\\/g, "/")); break; }
  }
  return hints;
}

/** 深度扫描目标项目目录，识别全部语言生态、锁文件/虚拟环境状态、版本约束与构建目标。 */
export async function scanProjectDeps({home, cwd} = {}) {
  const dir = await resolveSafeHomePath(String(cwd || path.join(home, "workspace")), {cwd: home, allowedRoot: home, allowMissing: false});
  if (!fss.existsSync(dir)) throw new Error(`项目目录不存在：${dir}`);

  const ecosystems = [];
  const compileTargets = [];
  const versionHints = await collectVersionHints(dir);
  const checkDirs = [dir];
  try {
    for (const e of await fs.readdir(dir, {withFileTypes: true})) {
      if (e.isDirectory() && !e.name.startsWith(".") && !["node_modules", "build", "target", "dist"].includes(e.name)) {
        const sub = path.join(dir, e.name);
        if (["package.json", "go.mod", "Cargo.toml", "pyproject.toml", "CMakeLists.txt", "build.zig", "pom.xml", "pubspec.yaml"].some(f => fss.existsSync(path.join(sub, f)))) {
          checkDirs.push(sub);
        }
      }
    }
  } catch {}

  for (const d of checkDirs) {
    const rel = d === dir ? "." : path.relative(dir, d).replace(/\\/g, "/");
    const suffix = rel === "." ? "" : ` (${rel})`;

    const pkgPath = path.join(d, "package.json");
    if (fss.existsSync(pkgPath)) {
      try {
        const pkg = JSON.parse(await fs.readFile(pkgPath, "utf8"));
        const {manager, root, lockfile} = await nodePackageManager(d, home, pkg);
        const requiredToolchain = ["npm", "yarn"].includes(manager) ? "node" : manager;
        if (pkg.engines?.node) versionHints.push({toolchainId: "node", required: String(pkg.engines.node), source: `package.json${suffix}`});
        const deps = [
          ...Object.entries(pkg.dependencies || {}).map(([name, version]) => ({name, version: String(version), dev: false})),
          ...Object.entries(pkg.devDependencies || {}).map(([name, version]) => ({name, version: String(version), dev: true})),
        ];
        ecosystems.push({
          id: `node:${rel}`, ecosystem: "node", label: `Node.js${suffix}`, manager,
          cwd: d, manifest: "package.json", lockfile, envReady: fss.existsSync(path.join(d, "node_modules"))
            || fss.existsSync(path.join(root, "node_modules")) || (manager === "yarn" && fss.existsSync(path.join(root, ".pnp.cjs"))),
          requiredToolchain,
          depCount: deps.length, deps: deps.slice(0, 120),
        });
        for (const scriptName of ["build", "test", "typecheck", "lint"]) {
          if (pkg.scripts?.[scriptName]) {
            compileTargets.push({
              id: `node:${rel}:${scriptName}`, label: `Node ${scriptName}${suffix}`,
              ecosystem: "node", cwd: d, command: manager, args: ["run", scriptName],
              requiredToolchain, crossCompile: false,
            });
          }
        }
      } catch (err) { throw new Error(`无法读取 ${pkgPath} 的项目配置：${err.message}`, {cause: err}); }
    }

    const goModPath = path.join(d, "go.mod");
    if (fss.existsSync(goModPath)) {
      const parsed = parseGoMod(await fs.readFile(goModPath, "utf8").catch(() => ""));
      if (parsed.goVersion) versionHints.push({toolchainId: "go", required: parsed.goVersion, source: `go.mod${suffix}`});
      ecosystems.push({
        id: `go:${rel}`, ecosystem: "go", label: `Go${suffix}`, manager: "go",
        cwd: d, manifest: "go.mod", lockfile: fss.existsSync(path.join(d, "go.sum")) ? "go.sum" : "", envReady: true,
        requiredToolchain: "go", depCount: parsed.deps.length, deps: parsed.deps.slice(0, 120),
      });
      compileTargets.push({id: `go:${rel}:build`, label: `Go 编译${suffix}`, ecosystem: "go", cwd: d, command: "go", args: ["build", "-trimpath", "-ldflags=-s -w", "."], requiredToolchain: "go", crossCompile: true});
      compileTargets.push({id: `go:${rel}:test`, label: `Go 测试${suffix}`, ecosystem: "go", cwd: d, command: "go", args: ["test", "./..."], requiredToolchain: "go", crossCompile: false});
    }

    const cargoPath = path.join(d, "Cargo.toml");
    if (fss.existsSync(cargoPath)) {
      const deps = parseCargoToml(await fs.readFile(cargoPath, "utf8").catch(() => ""));
      ecosystems.push({
        id: `rust:${rel}`, ecosystem: "rust", label: `Rust${suffix}`, manager: "cargo",
        cwd: d, manifest: "Cargo.toml", lockfile: fss.existsSync(path.join(d, "Cargo.lock")) ? "Cargo.lock" : "", envReady: fss.existsSync(path.join(d, "target")),
        requiredToolchain: "rust", depCount: deps.length, deps: deps.slice(0, 120),
      });
      compileTargets.push({id: `rust:${rel}:build`, label: `Cargo Release${suffix}`, ecosystem: "rust", cwd: d, command: "cargo", args: ["build", "--release"], requiredToolchain: "rust", crossCompile: true});
      compileTargets.push({id: `rust:${rel}:test`, label: `Cargo 测试${suffix}`, ecosystem: "rust", cwd: d, command: "cargo", args: ["test"], requiredToolchain: "rust", crossCompile: false});
    }

    const pyProj = path.join(d, "pyproject.toml");
    const reqTxt = path.join(d, "requirements.txt");
    if (fss.existsSync(pyProj) || fss.existsSync(reqTxt)) {
      const deps = parsePythonDeps(
        fss.existsSync(reqTxt) ? await fs.readFile(reqTxt, "utf8").catch(() => "") : "",
        fss.existsSync(pyProj) ? await fs.readFile(pyProj, "utf8").catch(() => "") : "",
      );
      ecosystems.push({
        id: `python:${rel}`, ecosystem: "python", label: `Python${suffix}`, manager: "uv",
        cwd: d, manifest: fss.existsSync(pyProj) ? "pyproject.toml" : "requirements.txt",
        lockfile: fss.existsSync(path.join(d, "uv.lock")) ? "uv.lock" : "", envReady: fss.existsSync(path.join(d, ".venv")),
        requiredToolchain: "python", depCount: deps.length, deps: deps.slice(0, 120),
      });
    }

    if (fss.existsSync(path.join(d, "build.gradle.kts")) || fss.existsSync(path.join(d, "build.gradle"))) {
      ecosystems.push({
        id: `gradle:${rel}`, ecosystem: "gradle", label: `Gradle / JVM${suffix}`, manager: "gradle",
        cwd: d, manifest: fss.existsSync(path.join(d, "build.gradle.kts")) ? "build.gradle.kts" : "build.gradle",
        lockfile: fss.existsSync(path.join(d, "gradle.lockfile")) ? "gradle.lockfile" : "", envReady: true,
        requiredToolchain: "java", depCount: 0, deps: [],
      });
      const gradlew = fss.existsSync(path.join(d, "gradlew")) ? "./gradlew" : "gradle";
      compileTargets.push({id: `gradle:${rel}:test`, label: `Gradle 测试${suffix}`, ecosystem: "gradle", cwd: d, command: gradlew, args: ["test", "--no-daemon", "--console=plain"], requiredToolchain: "java", crossCompile: false});
    }

    if (fss.existsSync(path.join(d, "CMakeLists.txt"))) {
      ecosystems.push({
        id: `cmake:${rel}`, ecosystem: "cpp", label: `CMake C/C++${suffix}`, manager: "cmake",
        cwd: d, manifest: "CMakeLists.txt", lockfile: "", envReady: fss.existsSync(path.join(d, "build")),
        requiredToolchain: "cpp-apt", depCount: 0, deps: [],
      });
      compileTargets.push({id: `cmake:${rel}:build`, label: `CMake 构建${suffix}`, ecosystem: "cpp", cwd: d, command: "cmake", args: ["--build", "build"], requiredToolchain: "cpp-apt", crossCompile: false});
    }
    if (fss.existsSync(path.join(d, "build.zig"))) {
      ecosystems.push({
        id: `zig:${rel}`, ecosystem: "zig", label: `Zig${suffix}`, manager: "zig",
        cwd: d, manifest: "build.zig", lockfile: fss.existsSync(path.join(d, "build.zig.zon")) ? "build.zig.zon" : "", envReady: true,
        requiredToolchain: "zig", depCount: 0, deps: [],
      });
      compileTargets.push({id: `zig:${rel}:build`, label: `Zig ReleaseSafe${suffix}`, ecosystem: "zig", cwd: d, command: "zig", args: ["build", "-Doptimize=ReleaseSafe"], requiredToolchain: "zig", crossCompile: true});
    }
    if (fss.existsSync(path.join(d, "pom.xml"))) {
      ecosystems.push({
        id: `maven:${rel}`, ecosystem: "maven", label: `Maven / JVM${suffix}`, manager: "mvn",
        cwd: d, manifest: "pom.xml", lockfile: "", envReady: true,
        requiredToolchain: "maven", depCount: 0, deps: [],
      });
      compileTargets.push({id: `maven:${rel}:test`, label: `Maven 测试${suffix}`, ecosystem: "maven", cwd: d, command: "mvn", args: ["-q", "test"], requiredToolchain: "maven", crossCompile: false});
      compileTargets.push({id: `maven:${rel}:pkg`, label: `Maven 打包${suffix}`, ecosystem: "maven", cwd: d, command: "mvn", args: ["-q", "package", "-DskipTests"], requiredToolchain: "maven", crossCompile: false});
    }
    if (fss.existsSync(path.join(d, "pubspec.yaml"))) {
      ecosystems.push({
        id: `dart:${rel}`, ecosystem: "dart", label: `Dart${suffix}`, manager: "dart pub",
        cwd: d, manifest: "pubspec.yaml", lockfile: fss.existsSync(path.join(d, "pubspec.lock")) ? "pubspec.lock" : "",
        envReady: fss.existsSync(path.join(d, ".dart_tool")),
        requiredToolchain: "dart", depCount: 0, deps: [],
      });
      compileTargets.push({id: `dart:${rel}:test`, label: `Dart 测试${suffix}`, ecosystem: "dart", cwd: d, command: "dart", args: ["test"], requiredToolchain: "dart", crossCompile: false});
    }
    const dirFiles = await fs.readdir(d).catch(() => []);
    const dotnetManifest = dirFiles.find(f => f.endsWith(".sln") || f.endsWith(".csproj") || f.endsWith(".fsproj"));
    if (dotnetManifest) {
      ecosystems.push({
        id: `dotnet:${rel}`, ecosystem: "dotnet", label: `.NET${suffix}`, manager: "dotnet",
        cwd: d, manifest: dotnetManifest, lockfile: fss.existsSync(path.join(d, "packages.lock.json")) ? "packages.lock.json" : "",
        envReady: fss.existsSync(path.join(d, "obj")),
        requiredToolchain: "dotnet", depCount: 0, deps: [],
      });
      compileTargets.push({id: `dotnet:${rel}:build`, label: `.NET 构建${suffix}`, ecosystem: "dotnet", cwd: d, command: "dotnet", args: ["build", "-c", "Release"], requiredToolchain: "dotnet", crossCompile: false});
      compileTargets.push({id: `dotnet:${rel}:test`, label: `.NET 测试${suffix}`, ecosystem: "dotnet", cwd: d, command: "dotnet", args: ["test"], requiredToolchain: "dotnet", crossCompile: false});
    }
  }

  return {ok: true, cwd: dir, ecosystems, compileTargets, versionHints};
}

/** 对指定项目生态执行依赖操作（含严格包名校验与 Python .venv 自动隔离）。 */
export async function runProjectDepAction({home, cwd, ecosystem, action = "install", packages = [], dev = false, onProgress = () => {}} = {}) {
  const dir = await resolveSafeHomePath(String(cwd || path.join(home, "workspace")), {cwd: home, allowedRoot: home, allowMissing: false});
  const env = await buildExecutionEnv(home);
  const rawPkgs = (Array.isArray(packages) ? packages : String(packages || "").split(/\s+/)).map(s => s.trim()).filter(Boolean);
  for (const p of rawPkgs) {
    if (!SAFE_PKG_RE.test(p)) throw new Error(`包名格式非法：${p}`);
  }
  const eco = String(ecosystem || "").toLowerCase();
  const act = String(action || "install").toLowerCase();

  let cmd = "", args = [];
  if (eco === "node") {
    const pkg = JSON.parse(await fs.readFile(path.join(dir, "package.json"), "utf8"));
    const selection = await nodePackageManager(dir, home, pkg);
    cmd = selection.manager;
    if (act === "install") args = ["install"];
    else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供包名"); args = [cmd === "npm" ? "install" : "add", ...(dev ? ["-D"] : []), ...rawPkgs]; }
    else if (act === "remove") { if (!rawPkgs.length) throw new Error("请提供包名"); args = [cmd === "npm" ? "uninstall" : "remove", ...rawPkgs]; }
    else if (act === "outdated") {
      args = ["outdated"];
      if (cmd === "yarn") {
        const ver = await runProcess(cmd, ["--version"], {cwd: dir, env});
        if (!ver.ok) throw new Error(ver.stderr || "无法读取 Yarn 版本");
        if (Number.parseInt(ver.stdout, 10) >= 2) {
          const names = Object.entries({...pkg.dependencies, ...pkg.devDependencies})
            .filter(([, spec]) => !/^(workspace|link|portal|file):/.test(spec)).map(([name]) => name);
          if (!names.length) return {ok: true, exitCode: 0, stdout: "没有需要查询的依赖", stderr: "", cwd: dir, ecosystem: eco, action: act};
          args = ["npm", "info", "--fields", "name,version", "--", ...names];
        }
      }
    } else throw new Error(`不支持的 Node.js 依赖操作：${act}`);
  } else if (eco === "go") {
    cmd = "go";
    if (act === "install") args = ["mod", "download"];
    else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供 Go 模块路径"); args = ["get", ...rawPkgs]; }
    else if (act === "remove" || act === "tidy") args = ["mod", "tidy"];
    else if (act === "outdated") args = ["list", "-m", "-u", "all"];
  } else if (eco === "rust") {
    cmd = "cargo";
    if (act === "install") args = ["fetch"];
    else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供 Crate 名"); args = ["add", ...(dev ? ["--dev"] : []), ...rawPkgs]; }
    else if (act === "remove") { if (!rawPkgs.length) throw new Error("请提供 Crate 名"); args = ["remove", ...rawPkgs]; }
    else if (act === "outdated") args = ["update", "--dry-run"];
  } else if (eco === "python") {
    const venvDir = path.join(dir, ".venv");
    const venvPy = process.platform === "win32" ? path.join(venvDir, "Scripts", "python.exe") : path.join(venvDir, "bin", "python");
    const venvPip = process.platform === "win32" ? path.join(venvDir, "Scripts", "pip.exe") : path.join(venvDir, "bin", "pip");
    const uvBin = path.join(home, ".galiais", "toolchains", "python", "bin", "uv");
    const hasUv = fss.existsSync(uvBin);
    if (!fss.existsSync(venvPy)) {
      onProgress({id: "python", stage: "deps", percent: 15, message: "正在初始化项目隔离虚拟环境 (.venv)…"});
      const mk = hasUv
        ? await runProcess(uvBin, ["venv", venvDir], {cwd: dir, env})
        : await runProcess("python3", ["-m", "venv", venvDir], {cwd: dir, env});
      if (!mk.ok) throw new Error(`创建 Python .venv 失败：${mk.stderr || mk.stdout}`);
    }
    if (hasUv) {
      cmd = uvBin;
      if (act === "install") args = fss.existsSync(path.join(dir, "pyproject.toml")) ? ["sync"] : ["pip", "install", "--python", venvPy, "-r", "requirements.txt"];
      else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供 Python 包名"); args = fss.existsSync(path.join(dir, "pyproject.toml")) ? ["add", ...(dev ? ["--dev"] : []), ...rawPkgs] : ["pip", "install", "--python", venvPy, ...rawPkgs]; }
      else if (act === "remove") { if (!rawPkgs.length) throw new Error("请提供 Python 包名"); args = fss.existsSync(path.join(dir, "pyproject.toml")) ? ["remove", ...rawPkgs] : ["pip", "uninstall", "--python", venvPy, ...rawPkgs]; }
      else if (act === "outdated") args = ["pip", "list", "--python", venvPy, "--outdated"];
    } else {
      cmd = venvPip;
      if (act === "install") args = ["install", "-r", "requirements.txt"];
      else if (act === "add") args = ["install", ...rawPkgs];
      else if (act === "remove") args = ["uninstall", "-y", ...rawPkgs];
      else if (act === "outdated") args = ["list", "--outdated"];
    }
  } else if (eco === "maven") {
    cmd = "mvn";
    if (act === "install") args = ["dependency:resolve"];
    else if (act === "outdated") args = ["versions:display-dependency-updates"];
    else throw new Error("Maven 生态仅支持同步依赖（install）与检查更新（outdated）");
  } else if (eco === "dart") {
    cmd = "dart";
    if (act === "install") args = ["pub", "get"];
    else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供 Dart 包名"); args = ["pub", "add", ...(dev ? ["--dev"] : []), ...rawPkgs]; }
    else if (act === "remove") { if (!rawPkgs.length) throw new Error("请提供 Dart 包名"); args = ["pub", "remove", ...rawPkgs]; }
    else if (act === "outdated") args = ["pub", "outdated"];
  } else if (eco === "dotnet") {
    cmd = "dotnet";
    if (act === "install") args = ["restore"];
    else if (act === "add") { if (!rawPkgs.length) throw new Error("请提供 NuGet 包名"); args = ["add", "package", rawPkgs[0]]; }
    else if (act === "remove") { if (!rawPkgs.length) throw new Error("请提供 NuGet 包名"); args = ["remove", "package", rawPkgs[0]]; }
    else if (act === "outdated") args = ["list", "package", "--outdated"];
  } else {
    throw new Error(`暂不支持该生态依赖动作：${eco}`);
  }

  onProgress({id: eco, stage: "deps", percent: 30, message: `执行 ${path.basename(cmd)} ${args.join(" ")}`});
  const res = await runProcess(cmd, args, {cwd: dir, env, onLine: l => onProgress({id: eco, stage: "deps", percent: 65, message: "依赖处理中…", logLine: l})});
  onProgress({id: eco, stage: "deps", percent: 100, message: res.ok ? `完成 (${res.ms}ms)` : `退出码 ${res.exitCode}`});
  return {...res, command: `${path.basename(cmd)} ${args.join(" ")}`, cwd: dir, ecosystem: eco, action: act};
}

/** Ubuntu APT / DPKG 系统包管理（只读查询全模式通用；写操作支持直接写入或 Root 宿主安全桥接）。 */
export async function runAptAction({home, action = "list", query = "", packages = [], onProgress = () => {}, onAptExec = null} = {}) {
  const act = String(action || "list").toLowerCase();
  const env = {...await buildExecutionEnv(home), DEBIAN_FRONTEND: "noninteractive"};
  const pkgs = (Array.isArray(packages) ? packages : String(packages || "").split(/\s+/)).map(s => s.trim()).filter(s => SAFE_APT_RE.test(s));

  if (act === "list") {
    const q = String(query || "").trim().toLowerCase();
    const {stdout} = await execFileAsync("dpkg-query", ["-W", "-f=${Package}\t${Version}\t${Installed-Size}\t${binary:Summary}\n"], {env, timeout: 15_000, maxBuffer: 8 * 1024 * 1024});
    const list = stdout.trim().split("\n").filter(Boolean).map(line => {
      const [name = "", version = "", kb = "0", summary = ""] = line.split("\t");
      return {name, version, sizeKB: Number(kb) || 0, summary, installed: true};
    }).filter(p => !q || p.name.toLowerCase().includes(q) || p.summary.toLowerCase().includes(q)).slice(0, 120);
    return {ok: true, packages: list};
  }
  if (act === "search") {
    const q = String(query || "").trim();
    if (!q) return {ok: true, packages: []};
    const {stdout} = await execFileAsync("apt-cache", ["search", "--", q], {env, timeout: 15_000, maxBuffer: 4 * 1024 * 1024});
    const list = stdout.trim().split("\n").filter(Boolean).slice(0, 80).map(line => {
      const idx = line.indexOf(" - ");
      return {name: idx > 0 ? line.slice(0, idx).trim() : line.trim(), version: "", sizeKB: 0, summary: idx > 0 ? line.slice(idx + 3).trim() : "", installed: false};
    });
    return {ok: true, packages: list};
  }
  if ((act === "install" || act === "remove") && !pkgs.length) throw new Error("请提供合法的系统包名");
  if (act === "remove") {
    const blocked = pkgs.filter(p => PROTECTED_APT_PKGS.has(p.toLowerCase()));
    if (blocked.length) {
      throw new Error(`安全拦截：禁止卸载系统基础运行包（${blocked.join(", ")}），以免破坏容器运行环境`);
    }
  }
  if (!["update", "install", "remove"].includes(act)) throw new Error(`未知 APT 动作：${act}`);

  const direct = await isAptDirectlyWritable();
  if (!direct) {
    if (typeof onAptExec !== "function") {
      throw new Error("当前免 Root 模式无系统 APT 写权限，仅支持查询；请使用免 Root 托管编译器（Go / Zig / Rust / Python / Node / JDK）");
    }
    onProgress({id: "apt", stage: "apt", percent: 25, message: `通过 Root 通道执行 apt-get ${act} ${pkgs.join(" ")}…`});
    const hostRes = await onAptExec({action: act, packages: pkgs});
    onProgress({id: "apt", stage: "apt", percent: 100, message: hostRes?.ok ? "APT 操作完成" : "APT 操作失败"});
    return {
      ok: Boolean(hostRes?.ok),
      exitCode: Number(hostRes?.exitCode ?? (hostRes?.ok ? 0 : 1)),
      ms: Number(hostRes?.ms || 0),
      command: `apt-get ${act} ${pkgs.join(" ")}`.trim(),
      stdout: String(hostRes?.stdout || ""),
      stderr: String(hostRes?.stderr || hostRes?.error || ""),
    };
  }

  const aptArgs = act === "update" ? ["update", "-qq"] : act === "install" ? ["install", "-y", "--no-install-recommends", ...pkgs] : ["remove", "-y", ...pkgs];
  onProgress({id: "apt", stage: "apt", percent: 25, message: `执行 apt-get ${act} ${pkgs.join(" ")}…`});
  const r = await runProcess("apt-get", aptArgs, {env, onLine: l => onProgress({id: "apt", stage: "apt", percent: 65, message: `apt-get ${act}…`, logLine: l})});
  onProgress({id: "apt", stage: "apt", percent: 100, message: r.ok ? "APT 操作完成" : "APT 操作失败"});
  return {...r, command: `apt-get ${aptArgs.join(" ")}`};
}

/** 统一编译执行器：严格白名单编译器 + HOME 边界限制 + 无 sh -c 拼接，支持工程构建、CMake 两段式、Go/Zig 跨平台交叉编译与单文件编译。 */
export async function runCompile({home, cwd, ecosystem = "auto", command = "", args = [], targetOs = "", targetArch = "", outputPath = "", file = "", onProgress = () => {}} = {}) {
  const dir = await resolveSafeHomePath(String(cwd || path.join(home, "workspace")), {cwd: home, allowedRoot: home, allowMissing: false});
  const normOs = String(targetOs || "").trim().toLowerCase();
  const normArch = String(targetArch || "").trim().toLowerCase();
  if (!ALLOWED_TARGET_OS.has(normOs) || !ALLOWED_TARGET_ARCH.has(normArch)) {
    throw new Error(`不支持的交叉编译目标：${targetOs}/${targetArch}`);
  }
  const env = await buildExecutionEnv(home);
  let cmd = String(command || "").trim();
  let cmdArgs = Array.isArray(args) ? args.map(String) : [];
  let resolvedOut = outputPath
    ? await resolveSafeHomePath(String(outputPath), {cwd: dir, allowedRoot: home, allowMissing: true})
    : "";

  if (!cmd && file) {
    const absFile = await resolveSafeHomePath(String(file), {cwd: dir, allowedRoot: home, allowMissing: false});
    const ext = path.extname(absFile).toLowerCase();
    const baseOut = resolvedOut || path.join(dir, path.basename(absFile, ext) + (normOs === "windows" ? ".exe" : ""));
    if (ext === ".c" || ext === ".cpp" || ext === ".cc") {
      const zigCur = path.join(home, ".galiais", "toolchains", "zig", "current", "zig");
      if (fss.existsSync(zigCur)) {
        cmd = "zig";
        const zigTarget = normOs && normArch
          ? ["-target", `${normArch === "arm64" ? "aarch64" : "x86_64"}-${normOs === "darwin" ? "macos" : normOs}`]
          : [];
        cmdArgs = [ext === ".c" ? "cc" : "c++", "-O2", ...zigTarget, absFile, "-o", baseOut];
      } else {
        cmd = ext === ".c" ? "gcc" : "g++";
        cmdArgs = ["-O2", absFile, "-o", baseOut];
      }
      resolvedOut = baseOut;
    } else if (ext === ".go") {
      cmd = "go";
      cmdArgs = ["build", "-trimpath", "-ldflags=-s -w", "-o", baseOut, absFile];
      resolvedOut = baseOut;
    } else if (ext === ".rs") {
      cmd = "rustc";
      cmdArgs = ["-O", absFile, "-o", baseOut];
      resolvedOut = baseOut;
    } else if (ext === ".java") {
      cmd = "javac";
      cmdArgs = [absFile];
    } else if (ext === ".kt") {
      cmd = "kotlinc";
      const jarOut = resolvedOut || path.join(dir, path.basename(absFile, ext) + ".jar");
      cmdArgs = [absFile, "-include-runtime", "-d", jarOut];
      resolvedOut = jarOut;
    } else if (ext === ".dart") {
      cmd = "dart";
      cmdArgs = ["compile", "exe", absFile, "-o", baseOut];
      resolvedOut = baseOut;
    } else {
      throw new Error(`暂不支持单文件编译扩展名：${ext}`);
    }
  }

  if (!cmd) {
    const scanned = await scanProjectDeps({home, cwd: dir});
    const picked = (ecosystem && ecosystem !== "auto")
      ? scanned.compileTargets.find(t => t.ecosystem === ecosystem)
      : scanned.compileTargets[0];
    if (!picked) throw new Error(`目录 ${dir} 中未检测到可识别的构建工程`);
    cmd = picked.command;
    cmdArgs = [...picked.args];
  }
  if (!ALLOWED_COMPILE_CMDS.has(cmd)) {
    throw new Error(`安全拦截：编译命令不在允许的编译器白名单内（${cmd}）`);
  }
  if (cmdArgs.some(a => a.includes("\0"))) {
    throw new Error("编译参数包含非法 NUL 字符");
  }

  // CMake 两段式：先 configure（如尚未生成 build 目录），再 build，零 sh -c 风险
  if (cmd === "cmake" && !fss.existsSync(path.join(dir, "build", "CMakeCache.txt"))) {
    onProgress({id: "compile", stage: "compile", percent: 20, message: "正在执行 CMake Configure (cmake -B build)…"});
    const cfgRes = await runProcess("cmake", ["-B", "build"], {cwd: dir, env, onLine: l => onProgress({id: "compile", stage: "compile", percent: 35, message: "CMake 配置中…", logLine: l})});
    if (!cfgRes.ok) return {...cfgRes, command: "cmake -B build", cwd: dir, artifactPath: "", artifactSize: 0};
  }

  if (cmd === "go" && normOs && normArch) {
    env.CGO_ENABLED = "0";
    env.GOOS = normOs;
    env.GOARCH = normArch;
    if (!cmdArgs.includes("-o") && cmdArgs[0] === "build") {
      const outName = `${path.basename(dir)}-${normOs}-${normArch}${normOs === "windows" ? ".exe" : ""}`;
      resolvedOut = path.join(dir, "dist", outName);
      await fs.mkdir(path.dirname(resolvedOut), {recursive: true});
      cmdArgs = ["build", "-trimpath", "-ldflags=-s -w", "-o", resolvedOut, "."];
    }
  }

  onProgress({id: "compile", stage: "compile", percent: 45, message: `正在执行：${cmd} ${cmdArgs.join(" ")}`});
  const res = await runProcess(cmd, cmdArgs, {cwd: dir, env, onLine: l => onProgress({id: "compile", stage: "compile", percent: 75, message: "编译进行中…", logLine: l})});
  let artifactSize = 0;
  if (resolvedOut && fss.existsSync(resolvedOut)) {
    try { artifactSize = (await fs.stat(resolvedOut)).size; } catch {}
  }
  onProgress({id: "compile", stage: "compile", percent: 100, message: res.ok ? `编译通过 (${res.ms}ms)` : `编译失败 (exit ${res.exitCode})`});
  return {...res, command: `${cmd} ${cmdArgs.join(" ")}`, cwd: dir, artifactPath: artifactSize > 0 ? resolvedOut : "", artifactSize};
}
