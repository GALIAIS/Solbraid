import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {spawn} from "node:child_process";
import {createInterface} from "node:readline";
import {resolveCliInstallation, cliPathDirectories} from "./cli-components.mjs";

/**
 * 虚拟机内常驻多路复用守护器（galiais-mux）：
 * 使 codex / claude / gateway / agent-hub 脱离单次 SSH 会话常驻运行；
 * 当 Android UI 或前台服务重连时，不断任务、不丢网关端口令牌，并自动重放断线期间的流式事件与待处理审批。
 */
const HOME = process.env.HOME || "/home/agent";
const MUX_DIR = process.env.GALIAIS_MUX_DIR || path.join(HOME, ".galiais", "mux");
const RUNTIME_GENERATION = process.env.GALIAIS_RUNTIME_GENERATION || "";
const MAX_RING_ITEMS = 512;
const MAX_RING_BYTES = 4 * 1024 * 1024;

const CHANNELS = {
  "codex": {
    cmd: "/opt/galiais-codex/usr/lib/chatgpt/resources/codex",
    // Android 使用原生执行，初始化时也必须覆盖沙箱默认值，避免检查 bubblewrap。
    args: ["-c", "sandbox_mode=\"danger-full-access\"", "app-server", "--listen", "stdio://"],
  },
  "claude": {
    cmd: "/opt/galiais-codex/usr/lib/chatgpt/resources/node",
    args: ["/opt/galiais-codex/android/claude-host.mjs"],
  },
  "gateway": {
    cmd: "/opt/galiais-codex/usr/lib/chatgpt/resources/node",
    args: ["/opt/galiais-codex/android/provider-gateway.mjs"],
  },
  "agent-hub": {
    cmd: "/opt/galiais-codex/usr/lib/chatgpt/resources/node",
    args: ["/opt/galiais-codex/android/agent-hub.mjs"],
  },
  ...(process.env.GALIAIS_MUX_TEST_SCRIPT ? {
    "test": {cmd: process.execPath, args: [process.env.GALIAIS_MUX_TEST_SCRIPT]},
  } : {}),
};

function sockPathFor(channel) {
  if (!Object.hasOwn(CHANNELS, channel)) throw new Error(`Unsupported mux channel: ${channel}`);
  if (process.platform === "win32") return `\\\\.\\pipe\\galiais-mux-${process.pid === Number(process.env.GALIAIS_MUX_OWNER_PID) ? process.pid : (process.env.GALIAIS_MUX_PIPE_TAG || "default")}-${channel}`;
  return path.join(MUX_DIR, `${channel}.sock`);
}

function metaPathFor(channel, ext) {
  return path.join(MUX_DIR, `${channel}.${ext}`);
}

function canConnect(sockPath) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(sockPath);
    const done = (ok, error) => {
      s.removeAllListeners();
      s.destroy();
      if (error) reject(error); else resolve(ok);
    };
    s.once("connect", () => done(true));
    s.once("error", error => done(false, ["ENOENT", "ECONNREFUSED"].includes(error.code) ? null : error));
    s.setTimeout(1200, () => done(false, new Error("连接后台引擎超时，未替换现有进程")));
  });
}

async function runDaemon(channel) {
  const spec = channel === "codex" ? {...CHANNELS[channel], cmd: (await resolveCliInstallation("codex")).binary} : CHANNELS[channel];
  const sockPath = sockPathFor(channel);
  fs.mkdirSync(MUX_DIR, {recursive: true, mode: 0o700});
  if (await canConnect(sockPath)) process.exit(0);
  try { fs.unlinkSync(sockPath); } catch {}

  const cwd = fs.existsSync(path.join(HOME, "workspace")) ? path.join(HOME, "workspace") : process.cwd();
  const cliPath = [...await cliPathDirectories(), process.env.PATH || ""].join(":");
  const child = spawn(spec.cmd, spec.args, {
    cwd,
    env: {...process.env, PATH: cliPath, DISABLE_AUTOUPDATER: "1"},
    stdio: ["pipe", "pipe", "pipe"],
    detached: process.platform !== "win32",
  });
  function signalChild(signal) {
    if (!child.pid) return;
    try {
      if (process.platform === "win32") child.kill(signal);
      else process.kill(-child.pid, signal);
    } catch (error) { if (error.code !== "ESRCH") process.stderr.write(`停止 ${channel} 子进程失败：${error.message}\n`); }
  }

  let activeClient = null;
  let initReqId = null;
  const queuedInitWaiters = [];
  let cachedInitResult = null;
  let keepAlive = true;
  let orphanTimer = null;
  let ringBytes = 0;
  const ring = []; // JSON strings buffered while client is detached
  const pendingServerReqs = new Map(); // String(id) -> raw JSON line

  function scheduleOrphanStop() {
    clearTimeout(orphanTimer);
    if (keepAlive || activeClient) return;
    orphanTimer = setTimeout(() => process.kill(process.pid, "SIGTERM"), 10_000);
  }

  function pushRing(line) {
    const bytes = Buffer.byteLength(line);
    if (bytes > MAX_RING_BYTES) return;
    while (ring.length >= MAX_RING_ITEMS || ringBytes + bytes > MAX_RING_BYTES) {
      const dropped = ring.shift();
      if (dropped) ringBytes -= Buffer.byteLength(dropped);
    }
    ring.push(line);
    ringBytes += bytes;
  }

  function sendClient(socket, line) {
    if (!socket || socket.destroyed || !socket.writable) return false;
    try {
      socket.write(line + "\n");
      return true;
    } catch {
      return false;
    }
  }

  const rl = createInterface({input: child.stdout, crlfDelay: Infinity});
  rl.on("line", line => {
    if (!line.trim()) return;
    let msg;
    try { msg = JSON.parse(line); } catch { return; }

    // 缓存首次 initialize 握手响应，并唤醒并发等待的初始化请求
    if (initReqId !== null && msg.id === initReqId && msg.result) {
      cachedInitResult = msg.result;
      initReqId = null;
      while (queuedInitWaiters.length > 0) {
        const waiter = queuedInitWaiters.shift();
        sendClient(waiter.socket, JSON.stringify({jsonrpc: "2.0", id: waiter.id, result: cachedInitResult}));
      }
    }

    // 跟踪未决反向审批请求，以便重连客户端仍能收到并处理
    if (Object.hasOwn(msg, "id") && typeof msg.method === "string") {
      pendingServerReqs.set(String(msg.id), line);
    } else if (msg.method === "serverRequest/resolved" && msg.params?.requestId !== undefined) {
      pendingServerReqs.delete(String(msg.params.requestId));
    } else if (msg.method === "galiais/requestResolved" && msg.params?.id !== undefined) {
      pendingServerReqs.delete(String(msg.params.id));
    }

    if (!sendClient(activeClient, line)) {
      // 无活跃客户端时仅缓存通知流（反向审批已独立保存在 pendingServerReqs）
      if (!Object.hasOwn(msg, "id") && typeof msg.method === "string") {
        pushRing(line);
      }
    }
  });

  child.stderr.on("data", chunk => {
    const text = chunk.toString("utf8");
    if (text.includes("No usable sandbox")) {
      const warn = JSON.stringify({method: "galiais/platformWarning", params: {channel, message: "引擎仍要求当前设备不支持的沙箱，请检查引擎配置"}});
      if (!sendClient(activeClient, warn)) pushRing(warn);
    }
  });

  const server = net.createServer(socket => {
    const clientRl = createInterface({input: socket, crlfDelay: Infinity});

    clientRl.on("line", line => {
      if (!line.trim()) return;
      let msg;
      try { msg = JSON.parse(line); } catch { return; }

      // 连通性探测不接管会话；只有正式握手才能替换当前客户端。
      if (msg.method === "initialize" && Object.hasOwn(msg, "id")) {
        if (activeClient && activeClient !== socket && !activeClient.destroyed) activeClient.destroy();
        activeClient = socket;
        clearTimeout(orphanTimer);
        if (typeof msg.params?.galiaisKeepAlive === "boolean") {
          keepAlive = msg.params.galiaisKeepAlive;
          delete msg.params.galiaisKeepAlive;
          line = JSON.stringify(msg);
        }
      } else if (activeClient !== socket) {
        return;
      }

      if (msg.method === "galiais/keepAlive" && Object.hasOwn(msg, "id")) {
        if (typeof msg.params?.keep !== "boolean") {
          sendClient(socket, JSON.stringify({id: msg.id, error: {code: -32602, message: "Invalid keep-alive setting"}}));
          return;
        }
        keepAlive = msg.params.keep;
        scheduleOrphanStop();
        sendClient(socket, JSON.stringify({id: msg.id, result: {keepAlive}}));
        return;
      }

      // 二次重连时直接复用已初始化引擎，避免重复 initialize 报错并冲刷断线期间积压通知
      if (msg.method === "initialize" && Object.hasOwn(msg, "id")) {
        if (cachedInitResult !== null) {
          sendClient(socket, JSON.stringify({jsonrpc: "2.0", id: msg.id, result: cachedInitResult}));
          while (ring.length > 0) {
            const buffered = ring.shift();
            ringBytes -= Buffer.byteLength(buffered);
            sendClient(socket, buffered);
          }
          for (const pendingLine of pendingServerReqs.values()) {
            sendClient(socket, pendingLine);
          }
          return;
        }
        if (initReqId !== null) {
          queuedInitWaiters.push({socket, id: msg.id});
          return;
        }
        initReqId = msg.id;
      } else if (msg.method === "initialized" && cachedInitResult !== null) {
        return;
      }

      // 客户端回复了反向审批请求 -> 从未决表移除
      if (Object.hasOwn(msg, "id") && !Object.hasOwn(msg, "method")) {
        pendingServerReqs.delete(String(msg.id));
      }

      if (child.stdin.writable) {
        child.stdin.write(line + "\n");
      }
    });

    const detach = () => {
      if (activeClient === socket) { activeClient = null; scheduleOrphanStop(); }
    };
    socket.once("close", detach);
    socket.once("error", detach);
  });

  const cleanup = () => {
    clearTimeout(orphanTimer);
    try { server.close(); } catch {}
    // 旧进程退出时不能删除后来启动的同名通道。
    let ownsChannel = false;
    try { ownsChannel = fs.readFileSync(metaPathFor(channel, "pid"), "utf8").trim() === String(process.pid); } catch {}
    if (ownsChannel) {
      for (const file of [sockPath, ...["pid", "generation", "sha256"].map(ext => metaPathFor(channel, ext)), metaPathFor(channel, `generation.${process.pid}.next`)]) {
        try { fs.unlinkSync(file); } catch (error) { if (error.code !== "ENOENT") process.stderr.write(`清理 ${channel} 失败：${error.message}\n`); }
      }
    }
  };

  child.once("exit", code => {
    signalChild("SIGKILL");
    cleanup();
    process.exit(code ?? 1);
  });
  child.once("error", error => {
    cleanup();
    process.stderr.write(`无法启动 ${channel}：${error.message}\n`);
    process.exit(1);
  });
  server.once("error", error => {
    signalChild("SIGTERM");
    process.stderr.write(`无法监听 ${channel}：${error.message}\n`);
    process.exit(1);
  });

  server.listen(sockPath, () => {
    try {
      if (process.platform !== "win32") fs.chmodSync(sockPath, 0o600);
      fs.writeFileSync(metaPathFor(channel, "pid"), String(process.pid), {mode: 0o600});
      const generationFile = metaPathFor(channel, "generation");
      const pending = generationFile + `.${process.pid}.next`;
      fs.writeFileSync(pending, RUNTIME_GENERATION, {mode: 0o600});
      fs.renameSync(pending, generationFile);
    } catch (error) {
      cleanup();
      signalChild("SIGTERM");
      process.stderr.write(`无法记录 ${channel} 运行组件版本：${error.message}\n`);
      process.exit(1);
    }
  });

  let terminating = false;
  for (const sig of ["SIGTERM", "SIGINT"]) {
    process.once(sig, () => {
      if (terminating) return;
      terminating = true;
      clearTimeout(orphanTimer);
      try { server.close(); } catch {}
      signalChild("SIGTERM");
      // 等子进程完全退出（或超时强杀）后再清理 sock/pid，避免并发重启产生双实例
      setTimeout(() => signalChild("SIGKILL"), 3500).unref();
      setTimeout(() => { cleanup(); process.exit(0); }, 4800).unref();
    });
  }
}

async function stopChannelDaemon(channel) {
  const sockPath = sockPathFor(channel);
  const pidFile = metaPathFor(channel, "pid");
  const metaFiles = [sockPath, pidFile, metaPathFor(channel, "generation"), metaPathFor(channel, "sha256")];
  const unlinkAll = () => {
    for (const file of metaFiles) {
      try { fs.unlinkSync(file); } catch (error) { if (error.code !== "ENOENT") throw error; }
    }
  };
  const socketAlive = await canConnect(sockPath).catch(() => false);
  if (!fs.existsSync(pidFile)) {
    if (socketAlive) throw new Error(`${channel} 缺少进程身份，未关闭`);
    unlinkAll();
    return;
  }
  const pid = Number(fs.readFileSync(pidFile, "utf8").trim());
  if (!Number.isInteger(pid) || pid <= 1) {
    if (!socketAlive) { unlinkAll(); return; }
    throw new Error(`${channel} 进程身份无效`);
  }
  let present = true;
  let isOurDaemon = true;
  try { process.kill(pid, 0); } catch (error) {
    if (error.code === "ESRCH") present = false;
    else if (error.code === "EPERM") isOurDaemon = false;
    else throw error;
  }
  if (present && isOurDaemon && process.platform === "linux") {
    try {
      const argv = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8").split("\0");
      const entry = argv.indexOf("--daemon");
      if (!argv.some(arg => arg.endsWith("/guest-mux.mjs")) || entry < 0 || argv[entry + 1] !== channel) {
        isOurDaemon = false;
      }
    } catch {
      isOurDaemon = false;
    }
  }
  if (!isOurDaemon) {
    if (!socketAlive) { unlinkAll(); return; }
    throw new Error(`${channel} 进程身份已变化，未发送信号`);
  }
  if (present) {
    try { process.kill(pid, "SIGTERM"); } catch (e) { if (e.code !== "ESRCH") throw e; }
    for (let i = 0; i < 50 && present; i++) {
      await new Promise(resolve => setTimeout(resolve, 100));
      try { process.kill(pid, 0); } catch (error) {
        if (error.code === "ESRCH") present = false;
        else throw error;
      }
    }
    if (present) {
      try { process.kill(pid, "SIGKILL"); } catch (e) { if (e.code !== "ESRCH") throw e; }
      for (let i = 0; i < 15 && present; i++) {
        await new Promise(resolve => setTimeout(resolve, 100));
        try { process.kill(pid, 0); } catch (error) {
          if (error.code === "ESRCH") present = false;
          else throw error;
        }
      }
    }
    if (present) throw new Error(`${channel} 进程尚未退出，未确认停止`);
  }
  if (await canConnect(sockPath).catch(() => false)) throw new Error(`${channel} 仍在运行，未确认停止`);
  unlinkAll();
}

async function runProxy(channel) {
  const sockPath = sockPathFor(channel);
  fs.mkdirSync(MUX_DIR, {recursive: true, mode: 0o700});

  // 若检测到旧版 APK 遗留的跨代守护进程，自动平稳替换而不再阻塞要求用户手动重启。
  if (RUNTIME_GENERATION && await canConnect(sockPath).catch(() => false)) {
    const generationFile = metaPathFor(channel, "generation");
    const generation = fs.existsSync(generationFile) ? fs.readFileSync(generationFile, "utf8") : "";
    if (generation !== RUNTIME_GENERATION) {
      await stopChannelDaemon(channel);
    }
  }

  if (!(await canConnect(sockPath))) {
    const selfPath = fileURLToPath(import.meta.url);
    const cwd = fs.existsSync(path.join(HOME, "workspace")) ? path.join(HOME, "workspace") : process.cwd();
    const daemon = spawn(process.execPath, [selfPath, "--daemon", channel], {
      cwd,
      env: process.env,
      detached: true,
      stdio: "ignore",
    });
    let spawnError = null;
    daemon.once("error", error => { spawnError = error; });
    daemon.unref();

    let connected = false;
    const genFile = metaPathFor(channel, "generation");
    for (let i = 0; i < 60; i++) {
      await new Promise(r => setTimeout(r, 120));
      if (spawnError) throw spawnError;
      if (await canConnect(sockPath) && (!RUNTIME_GENERATION || fs.existsSync(genFile))) { connected = true; break; }
    }
    if (!connected) throw new Error(`无法启动常驻守护通道：${channel}`);
  }

  if (RUNTIME_GENERATION) {
    const generationFile = metaPathFor(channel, "generation");
    const generation = fs.existsSync(generationFile) ? fs.readFileSync(generationFile, "utf8") : "";
    if (generation !== RUNTIME_GENERATION) throw new Error("运行组件版本未能自动切换，请重试启动");
  }

  const sock = net.createConnection(sockPath);
  process.stdin.pipe(sock);
  sock.pipe(process.stdout);

  const done = (code = 0) => {
    try { sock.destroy(); } catch {}
    process.exit(code);
  };
  sock.once("end", () => done(0));
  sock.once("close", () => done(0));
  sock.once("error", err => {
    process.stderr.write(`Mux connection error (${channel}): ${err.message}\n`);
    done(1);
  });
  process.stdin.once("end", () => sock.end());
  for (const sig of ["SIGTERM", "SIGINT"]) process.once(sig, () => done(0));
}

async function stopAllDaemons() {
  const failures = [];
  for (const channel of ["agent-hub", "claude", "codex", "gateway"]) {
    try {
      await stopChannelDaemon(channel);
    } catch (error) { failures.push(error.message); }
  }
  if (failures.length) throw new Error(failures.join("；"));
}

const args = process.argv.slice(2);
if (args[0] === "--stop-all") {
  stopAllDaemons().catch(error => { process.stderr.write(error.message + "\n"); process.exitCode = 1; });
} else if (args[0] === "--daemon") {
  runDaemon(args[1]).catch(err => {
    process.stderr.write((err?.message || String(err)) + "\n");
    process.exit(1);
  });
} else {
  runProxy(args[0]).catch(err => {
    process.stderr.write((err?.message || String(err)) + "\n");
    process.exit(1);
  });
}
