import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import {randomUUID} from "node:crypto";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {createInterface} from "node:readline";
import {createTerminalHub} from "./terminal-hub.mjs";

const execFileAsync = promisify(execFile);

/**
 * 手机版 Agent Harness 开发者工具集（运行在隔离 Ubuntu 虚拟机内）：
 * 1. 真内核伪终端（PTY，基于 util-linux `script -qfc` 分配真实 `/dev/pts/N`）；
 * 2. Git SSH 密钥生成与 HTTPS/SSH 仓库克隆（凭据走内存参数，不写入 `.git/config`）；
 * 3. 虚拟机监听端口自动探测（读取 `/proc/net/tcp*`）与高速多路复用本地 TCP 隧道；
 * 4. 外部 Agent 轮次结束自动提取 `git diff` 供手机端改动抽屉查看。
 */
export function createDevToolsHub({home, baseEnv, notify, prepareSshPtyLaunch = null, preparePtyEnv = null}) {
  const terminals = createTerminalHub({home, baseEnv, notify, prepareSshPtyLaunch, preparePtyEnv});
  const tcpConns = new Map(); // connId -> net.Socket
  const clones = new Set();


  async function ensureSshKey() {
    const sshDir = path.join(home, ".ssh");
    const keyPath = path.join(sshDir, "id_ed25519");
    const pubPath = keyPath + ".pub";
    await fs.mkdir(sshDir, {recursive: true, mode: 0o700});
    try {
      await fs.stat(pubPath);
    } catch {
      await execFileAsync("/usr/bin/ssh-keygen", ["-q", "-t", "ed25519", "-N", "", "-C", "galiais-vm-git", "-f", keyPath], {timeout: 15_000});
      const cfgPath = path.join(sshDir, "config");
      try { await fs.stat(cfgPath); } catch {
        await fs.writeFile(cfgPath, "Host *\n  StrictHostKeyChecking accept-new\n  IdentityFile ~/.ssh/id_ed25519\n", {mode: 0o600});
      }
    }
    const publicKey = (await fs.readFile(pubPath, "utf8")).trim();
    return {publicKey};
  }

  async function cloneRepo({url, targetDir, branch, token}) {
    const cleanUrl = String(url || "").trim();
    if (!cleanUrl || (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("git@") && !cleanUrl.startsWith("ssh://"))) {
      throw new Error("仅支持 https://、git@ 或 ssh:// 格式的 Git 仓库地址");
    }
    const parsedUrl = cleanUrl.startsWith("git@") ? null : new URL(cleanUrl);
    if (parsedUrl && (parsedUrl.password || parsedUrl.search || parsedUrl.hash || (parsedUrl.protocol === "https:" && parsedUrl.username))) {
      throw new Error("仓库地址不能包含密码或查询参数，请在令牌栏填写凭据");
    }
    const dest = path.resolve(home, String(targetDir || "").trim());
    if (!dest.startsWith(home + "/") || dest === home) {
      throw new Error("目标目录必须位于 /home/agent/ 下");
    }
    await fs.mkdir(path.dirname(dest), {recursive: true});

    const args = [];
    const env = {...baseEnv(null, null), GIT_TERMINAL_PROMPT: "0"};
    if (token && cleanUrl.startsWith("https://")) {
      const basic = Buffer.from(`x-access-token:${String(token).trim()}`, "utf8").toString("base64");
      env.GIT_CONFIG_COUNT = "1";
      env.GIT_CONFIG_KEY_0 = `http.${parsedUrl.origin}/.extraHeader`;
      env.GIT_CONFIG_VALUE_0 = `Authorization: Basic ${basic}`;
    }
    args.push("clone", "--progress");
    if (branch && String(branch).trim()) args.push("--branch", String(branch).trim());
    args.push("--", cleanUrl, dest);

    if (cleanUrl.startsWith("git@") || cleanUrl.startsWith("ssh://")) {
      await ensureSshKey();
    }

    const child = spawn("/usr/bin/git", args, {cwd: home, env, stdio: ["ignore", "pipe", "pipe"]});
    clones.add(child);
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill("SIGTERM"); }, 5 * 60 * 1000);
    const forward = (stream) => {
      const rl = createInterface({input: stream, crlfDelay: Infinity});
      rl.on("line", line => notify("git/cloneLog", {line: line.replace(/\r/g, " ").slice(0, 400)}));
    };
    forward(child.stdout);
    forward(child.stderr);

    const code = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("close", c => resolve(c ?? 1));
    }).finally(() => { clearTimeout(timer); clones.delete(child); });
    if (timedOut) throw new Error("git clone 超时，已停止；目标目录可能包含未完成的下载");
    if (code !== 0) throw new Error(`git clone 失败（退出码 ${code}）`);
    return {ok: true, path: dest};
  }

  async function listListeningPorts(excludePorts = []) {
    const excluded = new Set([22, ...excludePorts.map(Number)]);
    const found = new Set();
    for (const procFile of ["/proc/net/tcp", "/proc/net/tcp6"]) {
      let text = "";
      try { text = await fs.readFile(procFile, "utf8"); } catch { continue; }
      const lines = text.trim().split("\n").slice(1);
      for (const line of lines) {
        const cols = line.trim().split(/\s+/);
        if (cols.length < 4 || cols[3] !== "0A") continue; // 0A = TCP_LISTEN
        const local = cols[1];
        const portHex = local.split(":")[1];
        const port = Number.parseInt(portHex, 16);
        if (port >= 1024 && port <= 65535 && !excluded.has(port)) found.add(port);
      }
    }
    return {ports: [...found].sort((a, b) => a - b)};
  }

  async function openTcp({connId, port}) {
    const p = Number(port);
    if (typeof connId !== "string" || !/^[a-zA-Z0-9-]{1,100}$/.test(connId) || !Number.isInteger(p) || p < 1024 || p > 65535) throw new Error("无效连接标识或端口");
    if (tcpConns.has(connId)) throw new Error("连接标识已被占用");
    if (tcpConns.size >= 64) throw new Error("预览连接过多，请关闭不用的页面");
    const connectHost = (host) => new Promise((resolve, reject) => {
      const s = net.createConnection({host, port: p});
      let done = false;
      s.setTimeout(5_000, () => { if (!done) { done = true; s.destroy(); reject(new Error("连接超时")); } });
      s.once("connect", () => { if (!done) { done = true; s.setTimeout(0); resolve(s); } });
      s.once("error", err => { if (!done) { done = true; reject(err); } });
    });
    let sock;
    try {
      sock = await connectHost("127.0.0.1");
    } catch (err4) {
      try {
        sock = await connectHost("::1");
      } catch {
        throw new Error(`连接 localhost:${p} 失败：${err4.message}`);
      }
    }
    tcpConns.set(connId, sock);
    const MAX_CHUNK = 32 * 1024;
    let pumping = false;
    const queue = [];
    const flushQueue = () => {
      if (pumping) return;
      pumping = true;
      const step = () => {
        if (!tcpConns.has(connId) || sock.destroyed) { queue.length = 0; pumping = false; return; }
        if (queue.length === 0) {
          pumping = false;
          if (sock.isPaused()) sock.resume();
          return;
        }
        const slice = queue.shift();
        try {
          notify("tcp/data", {connId, dataBase64: slice.toString("base64")});
        } catch {
          sock.destroy();
          queue.length = 0;
          pumping = false;
          return;
        }
        if (queue.length > 0) setImmediate(step);
        else {
          pumping = false;
          if (sock.isPaused()) sock.resume();
        }
      };
      step();
    };
    sock.on("data", chunk => {
      for (let off = 0; off < chunk.length; off += MAX_CHUNK) {
        queue.push(chunk.subarray(off, Math.min(off + MAX_CHUNK, chunk.length)));
      }
      if (queue.length >= 4) sock.pause();
      flushQueue();
    });
    const finish = () => {
      queue.length = 0;
      if (tcpConns.delete(connId)) notify("tcp/closed", {connId});
    };
    sock.once("close", finish);
    sock.once("error", finish);
    return {ok: true};
  }

  async function writeTcp({connId, dataBase64}) {
    const sock = tcpConns.get(connId);
    if (!sock || sock.destroyed) return {ok: false};
    if (typeof dataBase64 !== "string" || dataBase64.length > 1024 * 1024) throw new Error("TCP 数据超过传输上限");
    await new Promise((resolve, reject) => sock.write(Buffer.from(dataBase64, "base64"), error => error ? reject(error) : resolve()));
    return {ok: true};
  }

  function closeTcp({connId}) {
    const sock = tcpConns.get(connId);
    if (sock) {
      tcpConns.delete(connId);
      try { sock.destroy(); } catch {}
    }
    return {ok: true};
  }

  function endTcp({connId}) {
    const sock = tcpConns.get(connId);
    if (sock && !sock.destroyed) sock.end();
    return {ok: true};
  }

  async function captureGitDiff(cwd) {
    try {
      const {stdout} = await execFileAsync("/usr/bin/git", ["diff", "HEAD"], {
        cwd,
        timeout: 6000,
        maxBuffer: 1024 * 1024,
      });
      return stdout.trim() ? stdout.slice(0, 200_000) : null;
    } catch {
      return null;
    }
  }

  async function detectLatexEngine() {
    const candidates = [
      {id: "tectonic", paths: [path.join(home, ".local", "bin", "tectonic"), "/usr/local/bin/tectonic", "/usr/bin/tectonic"]},
      {id: "xelatex", paths: ["/usr/local/bin/xelatex", "/usr/bin/xelatex"]},
      {id: "lualatex", paths: ["/usr/local/bin/lualatex", "/usr/bin/lualatex"]},
      {id: "pdflatex", paths: ["/usr/local/bin/pdflatex", "/usr/bin/pdflatex"]},
    ];
    for (const c of candidates) {
      for (const p of c.paths) {
        try {
          const st = await fs.stat(p);
          if (st.isFile() && (st.mode & 0o111)) return {id: c.id, path: p};
        } catch {}
      }
    }
    return null;
  }

  async function latexStatus() {
    const native = await detectLatexEngine();
    return {
      offlineEngine: "solbraid-latex-v1",
      nativeAvailable: Boolean(native),
      nativeEngine: native?.id || null,
      nativePath: native?.path || null,
    };
  }

  async function compileLatex({source, cwd, filename}) {
    const texSource = String(source || "");
    if (!texSource.trim() || Buffer.byteLength(texSource, "utf8") > 2 * 1024 * 1024) {
      throw new Error("LaTeX 源码为空或超过 2 MB 上限");
    }
    const native = await detectLatexEngine();
    if (!native) {
      return {
        ok: false,
        nativeAvailable: false,
        engine: "solbraid-latex-v1",
        log: "Linux 环境未安装 tectonic/xelatex/pdflatex，已使用内置离线 LaTeX 引擎渲染并支持直接导出 PDF。",
      };
    }
    const baseName = String(filename || "document").replace(/[^a-zA-Z0-9._-]+/g, "_").replace(/\.tex$/i, "").slice(0, 64) || "document";
    const tmpDir = await fs.mkdtemp("/tmp/solbraid-tex-");
    try {
      const texFile = path.join(tmpDir, `${baseName}.tex`);
      const fullTex = /\\documentclass\b/.test(texSource)
        ? texSource
        : `\\documentclass[11pt]{article}\n\\usepackage{amsmath,amssymb}\n\\begin{document}\n${texSource}\n\\end{document}\n`;
      await fs.writeFile(texFile, fullTex, "utf8");
      const args = native.id === "tectonic"
        ? ["-X", "compile", texFile, "--outdir", tmpDir]
        : ["-interaction=nonstopmode", "-halt-on-error", "-no-shell-escape", "-output-directory", tmpDir, texFile];
      let logText = "";
      try {
        const {stdout, stderr} = await execFileAsync(native.path, args, {
          cwd: tmpDir,
          timeout: 60_000,
          maxBuffer: 2 * 1024 * 1024,
          env: baseEnv(null, null),
        });
        logText = (stdout + "\n" + stderr).trim();
      } catch (err) {
        logText = `${err.stdout || ""}\n${err.stderr || ""}\n${err.message || ""}`.trim();
        return {ok: false, nativeAvailable: true, engine: native.id, log: logText.slice(-8000)};
      }
      const pdfTmp = path.join(tmpDir, `${baseName}.pdf`);
      const pdfBytes = await fs.readFile(pdfTmp);
      let savedPath = null;
      if (typeof cwd === "string" && path.isAbsolute(cwd)) {
        const resolved = path.resolve(cwd);
        if (resolved === home || resolved.startsWith(home + "/")) {
          const outDir = path.join(resolved, "artifacts");
          await fs.mkdir(outDir, {recursive: true});
          savedPath = path.join(outDir, `${baseName}.pdf`);
          await fs.writeFile(savedPath, pdfBytes);
        }
      }
      return {
        ok: true,
        nativeAvailable: true,
        engine: native.id,
        pdfPath: savedPath,
        pdfSize: pdfBytes.length,
        pdfBase64: pdfBytes.length <= 4 * 1024 * 1024 ? pdfBytes.toString("base64") : null,
        log: logText.slice(-4000),
      };
    } finally {
      await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => {});
    }
  }

  async function closeAll() {
    for (const s of tcpConns.values()) { try { s.destroy(); } catch {} }
    tcpConns.clear();
    for (const child of clones) child.kill("SIGTERM");
    clones.clear();
    await terminals.closeAll();
  }

  return {
    ...terminals,
    ensureSshKey, cloneRepo,
    listListeningPorts, openTcp, writeTcp, endTcp, closeTcp,
    captureGitDiff, latexStatus, compileLatex, closeAll,
  };
}
