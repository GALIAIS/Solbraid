"use strict";
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");
const {spawn} = require("node:child_process");
const session = require("./vm-session.cjs");
const config = session.load(), base = session.directory;
const qmpPath = path.join(base, "qmp.sock");
const daemonSockPath = path.join(base, "daemon.sock");
const daemonPidPath = path.join(base, "daemon.pid");
const daemonMode = process.argv.includes("--daemon");
const persistArg = process.argv.find(arg => arg.startsWith("--persist="));
let persist = daemonMode ? (persistArg ? persistArg.slice("--persist=".length) !== "0" : true) : false;

let child, qmp, engineCheck, endpointCheck, engineVersion, daemonServer = null, orphanTimer = null;
let stopping = false, ready = false, configured = false, trustEstablished = false, monitorReady = false, checking = false, next = 0;
let bootPhase = "", startupDiagnostic = "";
const pending = new Map(), timers = new Set(), subscribers = new Set();

function sendTo(socket, packet) {
  if (socket.destroyed || !socket.writable) return;
  if (socket.writableLength > 1048576) {
    subscribers.delete(socket);
    socket.destroy();
    return;
  }
  try { socket.write(JSON.stringify(packet) + "\n"); } catch { subscribers.delete(socket); socket.destroy(); }
}

function output(packet) {
  if (daemonMode) {
    for (const socket of Array.from(subscribers)) sendTo(socket, packet);
    return;
  }
  if (process.stdout.writableLength > 1048576) { shutdown("Android VM status channel is blocked"); return; }
  process.stdout.write(JSON.stringify(packet) + "\n");
}

function later(callback, delay) {
  const timer = setTimeout(() => { timers.delete(timer); callback(); }, delay);
  timers.add(timer);
  return timer;
}

function clearOrphanTimer() {
  if (!orphanTimer) return;
  clearTimeout(orphanTimer);
  timers.delete(orphanTimer);
  orphanTimer = null;
}

function scheduleOrphanShutdownIfNeeded() {
  if (!daemonMode || persist || stopping || subscribers.size > 0) return;
  clearOrphanTimer();
  orphanTimer = later(() => {
    orphanTimer = null;
    if (!persist && !stopping && subscribers.size === 0) shutdown();
  }, 3000);
}

function command(execute) {
  if (!qmp || qmp.destroyed) return Promise.reject(new Error("VM monitor is disconnected"));
  const id = ++next;
  return new Promise((resolve, reject) => {
    const timeout = later(() => { pending.delete(id); reject(new Error("VM monitor request timed out")); }, 10000);
    pending.set(id, {resolve, reject, timeout});
    qmp.write(JSON.stringify({execute, id}) + "\n");
  });
}

function cleanupDaemonFiles() {
  if (!daemonMode) return;
  try { daemonServer?.close(); } catch {}
  daemonServer = null;
  for (const socket of Array.from(subscribers)) {
    try { socket.end(); } catch {}
  }
  subscribers.clear();
  try { if (fs.existsSync(daemonSockPath)) fs.unlinkSync(daemonSockPath); } catch {}
  try { if (fs.existsSync(daemonPidPath)) fs.unlinkSync(daemonPidPath); } catch {}
}

function shutdown(reason) {
  if (stopping) return;
  stopping = true;
  clearOrphanTimer();
  engineCheck?.kill("SIGTERM");
  endpointCheck?.destroy();
  if (reason) { process.exitCode = 1; output({kind: "vm-error", message: reason}); }
  output({kind: "vm-stopping"});
  command("system_powerdown").catch(() => child?.kill("SIGTERM"));
  later(() => child?.kill("SIGTERM"), 30000);
  later(() => child?.kill("SIGKILL"), 35000);
}

function checkEngine() {
  if (stopping || ready || checking || !monitorReady || !fs.existsSync(path.join(base, "known_hosts"))) return;
  checking = true;
  const socket = net.createConnection({host: "127.0.0.1", port: config.sshPort});
  endpointCheck = socket;
  let banner = "", complete = false;
  function failed(error) {
    if (complete) return;
    complete = true;
    socket.destroy();
    endpointCheck = null;
    checking = false;
    if (stopping) return;
    if (["ECONNREFUSED", "ECONNRESET", "ETIMEDOUT"].includes(error.code)) later(checkEngine, 2000);
    else shutdown("VM SSH endpoint failed: " + (error.code ?? "invalid-protocol"));
  }
  socket.setTimeout(2000, () => failed(Object.assign(new Error("VM SSH startup timed out"), {code: "ETIMEDOUT"})));
  socket.once("error", failed);
  socket.once("end", () => failed(Object.assign(new Error("VM SSH startup ended"), {code: "ECONNRESET"})));
  socket.on("data", chunk => {
    banner += chunk.toString("ascii");
    if (banner.length > 4096) { failed(new Error("Invalid SSH banner")); return; }
    if (!banner.split(/\r?\n/).some(line => line.startsWith("SSH-2.0-"))) return;
    complete = true;
    socket.destroy();
    endpointCheck = null;
    const proc = session.runCodex(["--version"], ["ignore", "pipe", "pipe"]);
    engineCheck = proc;
    let version = "", diagnosticBytes = 0;
    const timeout = later(() => proc.kill("SIGTERM"), 20000);
    proc.stdout.on("data", data => { version += data.toString("utf8"); if (version.length > 2048) proc.kill("SIGTERM"); });
    proc.stderr.on("data", data => { diagnosticBytes += data.length; if (diagnosticBytes > 65536) proc.kill("SIGTERM"); });
    proc.once("error", error => { checking = false; shutdown(error.message); });
    proc.once("exit", code => {
      clearTimeout(timeout);
      timers.delete(timeout);
      engineCheck = null;
      checking = false;
      if (stopping) return;
      if (code !== 0) { shutdown("VM engine startup failed, SSH exit code " + code); return; }
      if (version.trim() !== "codex-cli 0.171.0-alpha.13") { shutdown("VM engine version does not match the selected frontend"); return; }
      ready = true;
      engineVersion = version.trim();
      output({kind: "vm-ready", engineVersion});
    });
  });
}

async function ensureSocketFree(socketPath, busyError) {
  if (!fs.existsSync(socketPath)) return;
  if (!fs.lstatSync(socketPath).isSocket()) throw new Error("VM socket path is not a socket: " + path.basename(socketPath));
  await new Promise((resolve, reject) => {
    const old = net.createConnection(socketPath);
    old.setTimeout(2000, () => { old.destroy(); reject(new Error(busyError)); });
    old.once("connect", () => { old.destroy(); reject(new Error(busyError)); });
    old.once("error", error => {
      if (error.code === "ECONNREFUSED" || error.code === "ENOENT") resolve();
      else reject(error);
    });
  });
  if (fs.existsSync(socketPath)) fs.unlinkSync(socketPath);
}

/** 等待本机 SSH 转发端口就绪（避免快速重启时旧套接字短暂释放导致的 hostfwd 失败）。 */
async function waitPortAvailable(port, attempts = 25, delayMs = 400) {
  for (let i = 0; i < attempts; i++) {
    const ok = await new Promise(resolve => {
      const probe = net.createServer();
      probe.once("error", () => resolve(false));
      probe.listen({host: "127.0.0.1", port, backlog: 1, exclusive: true}, () => {
        probe.close(() => resolve(true));
      });
    });
    if (ok) return;
    await new Promise(resolve => setTimeout(resolve, delayMs));
  }
}

function startDaemonServer() {
  return new Promise((resolve, reject) => {
    const server = net.createServer(socket => {
      socket.setEncoding("utf8");
      let input = "";
      socket.on("data", chunk => {
        input += chunk;
        if (input.length > 8192) { socket.destroy(); return; }
        let end;
        while ((end = input.indexOf("\n")) >= 0) {
          const line = input.slice(0, end).trim();
          input = input.slice(end + 1);
          if (!line) continue;
          let packet;
          try { packet = JSON.parse(line); } catch { socket.destroy(); return; }
          if (packet.kind === "probe") {
            sendTo(socket, {
              kind: "probe-result",
              alive: true,
              ready,
              stopping,
              persist,
              bootPhase,
              engineVersion: engineVersion ?? null,
              pid: process.pid,
            });
            socket.end();
            return;
          } else if (packet.kind === "subscribe") {
            subscribers.add(socket);
            if (typeof packet.persist === "boolean") persist = packet.persist;
            clearOrphanTimer();
            if (stopping) sendTo(socket, {kind: "vm-stopping"});
            else if (ready) sendTo(socket, {kind: "vm-ready", engineVersion, reattached: true});
            else {
              if (monitorReady) sendTo(socket, {kind: "vm-starting"});
              if (bootPhase) sendTo(socket, {kind: "vm-boot-progress", phase: bootPhase});
              if (configured) sendTo(socket, {kind: "vm-configured"});
            }
          } else if (packet.kind === "set-persist") {
            persist = Boolean(packet.persist);
            if (persist) clearOrphanTimer();
            else scheduleOrphanShutdownIfNeeded();
            sendTo(socket, {kind: "persist-updated", persist});
          } else if (packet.kind === "status") {
            sendTo(socket, ready ? {kind: "vm-ready", engineVersion} : {kind: "vm-starting"});
          } else if (packet.kind === "shutdown") {
            shutdown();
          } else {
            sendTo(socket, {kind: "vm-error", message: "Unsupported VM control message"});
            socket.end();
            return;
          }
        }
      });
      const onGone = () => {
        if (subscribers.delete(socket)) scheduleOrphanShutdownIfNeeded();
      };
      socket.once("close", onGone);
      socket.once("error", onGone);
    });
    server.once("error", reject);
    server.listen(daemonSockPath, () => {
      try {
        fs.chmodSync(daemonSockPath, 0o600);
        fs.writeFileSync(daemonPidPath, String(process.pid) + "\n", {mode: 0o640});
      } catch (error) {
        server.close();
        reject(error);
        return;
      }
      daemonServer = server;
      resolve();
    });
  });
}

async function start() {
  await ensureSocketFree(qmpPath, "A VM is already running");
  if (daemonMode) {
    await ensureSocketFree(daemonSockPath, "A VM daemon is already running");
    // 先开放控制通道，Android 端可立即挂接并接收后续全部启动事件。
    await startDaemonServer();
  }
  await waitPortAvailable(config.sshPort);
  const prootMode = process.env.GALIAIS_RUNTIME_MODE === "proot";
  const isArm64 = process.arch === "arm64";
  const qemuBin = isArm64 ? "/usr/bin/qemu-system-aarch64" : "/usr/bin/qemu-system-x86_64";
  let kvmReady = false;
  if (isArm64 && !prootMode) {
    try { fs.accessSync("/dev/kvm", fs.constants.R_OK | fs.constants.W_OK); kvmReady = true; } catch {}
  }
  const archArgs = isArm64
    ? ["-machine", "virt", "-accel", kvmReady ? "kvm" : "tcg,thread=multi", "-cpu", kvmReady ? "host" : "max", "-bios", "/usr/share/AAVMF/AAVMF_CODE.fd"]
    : ["-machine", "q35,dump-guest-core=off", "-accel", "tcg,thread=multi", "-cpu", "max"];
  const args = ["-name", "galiais-linux", ...archArgs, "-smp", String(config.cpus), "-m", String(config.memoryMiB),
    "-nodefaults", "-display", "none", "-serial", "stdio", "-monitor", "none", "-no-reboot",
    "-drive", `if=virtio,file=${base}/system.qcow2,format=qcow2`,
    "-drive", `if=virtio,file=${base}/${config.seedFile ?? "seed.iso"},format=raw,readonly=on`,
    "-fsdev", `local,id=codex,path=/opt/galiais-codex,security_model=${prootMode ? "none" : "mapped-xattr"},readonly=on`,
    "-device", "virtio-9p-pci,fsdev=codex,mount_tag=galiais-codex",
    "-netdev", `user,id=net0,hostfwd=tcp:127.0.0.1:${config.sshPort}-:22`, "-device", "virtio-net-pci,netdev=net0,romfile=",
    "-object", "rng-random,id=rng0,filename=/dev/urandom", "-device", "virtio-rng-pci,rng=rng0",
    "-qmp", `unix:${qmpPath},server=on,wait=off`];
  if (!prootMode) {
    args.push("-sandbox", "on,obsolete=deny,elevateprivileges=deny,spawn=deny,resourcecontrol=deny");
  }
  child = spawn("/usr/bin/setpriv", ["--pdeathsig", "TERM", "--", qemuBin, ...args], {stdio: ["pipe", "pipe", "pipe"]});
  child.once("error", error => {
    stopping = true;
    process.exitCode = 1;
    for (const timer of timers) clearTimeout(timer);
    qmp?.destroy();
    endpointCheck?.destroy();
    engineCheck?.kill("SIGTERM");
    if (!daemonMode) { process.stdin.pause(); process.stdin.unref?.(); }
    output({kind: "vm-error", message: error.message});
    cleanupDaemonFiles();
  });
  child.once("exit", (code, signal) => {
    const requested = stopping;
    stopping = true;
    endpointCheck?.destroy();
    engineCheck?.kill("SIGTERM");
    for (const timer of timers) clearTimeout(timer);
    for (const request of pending.values()) request.reject(new Error("VM exited"));
    pending.clear();
    qmp?.destroy();
    if (!daemonMode) { process.stdin.pause(); process.stdin.unref?.(); }
    if (!requested || code !== 0) process.exitCode = code || 1;
    if (!ready && code !== 0 && startupDiagnostic) output({kind: "vm-error", message: startupDiagnostic.trim()});
    output({kind: "vm-stopped", code, signal});
    cleanupDaemonFiles();
  });
  let serial = "", diagnosticBytes = 0;
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", chunk => {
    serial += chunk;
    if (serial.length > 524288) { shutdown("VM console exceeded the message limit"); return; }
    let end;
    while ((end = serial.indexOf("\n")) >= 0) {
      const line = serial.slice(0, end).replaceAll("\r", "").trim();
      serial = serial.slice(end + 1);
      if (!ready) {
        let phase;
        if (line.includes("Linux version ")) phase = "kernel";
        else if (line.includes("Cloud-init") && line.includes("running")) phase = "configuration";
        else if (line.includes("Setting up ") || line.includes("Get:")) phase = "packages";
        if (phase && phase !== bootPhase) { bootPhase = phase; output({kind: "vm-boot-progress", phase}); }
        if (line.includes("Failed to run module package_update_upgrade_install")) {
          shutdown("VM package installation failed; the guest configuration must be recovered before starting an engine");
          return;
        }
      }
      const trust = /^GALIAIS_VM_TRUST ([a-f0-9-]{36}) (ssh-ed25519 [A-Za-z0-9+/=]+)$/.exec(line);
      try {
        if (trust) { session.trust(trust[1], trust[2]); trustEstablished = true; output({kind: "vm-trusted"}); checkEngine(); }
        else if (line === "GALIAIS_VM_SETUP_READY " + config.id) {
          if (!trustEstablished) throw new Error("VM setup completed without an authenticated host key");
          configured = true;
          output({kind: "vm-configured"});
          checkEngine();
        } else if (line.startsWith("GALIAIS_VM_SETUP_FAILED " + config.id + " ")) {
          throw new Error("VM setup failed: " + line.split(" ").at(-1));
        }
      } catch (error) { shutdown(error.message); }
    }
  });
  child.stderr.on("data", chunk => {
    diagnosticBytes += chunk.length;
    if (!ready) startupDiagnostic = (startupDiagnostic + chunk.toString("utf8")).slice(-16384);
    if (diagnosticBytes > 1048576) shutdown("VM diagnostic channel exceeded its limit");
    // 不转发可能带工作区内容的原始诊断，只报告进程状态。
  });
  later(() => { if (!ready) shutdown("VM did not finish setup within 20 minutes"); }, 1200000);
  let attempts = 0;
  function connectMonitor() {
    if (stopping) return;
    const connection = net.createConnection(qmpPath);
    let buffer = "", negotiated = false;
    connection.setEncoding("utf8");
    connection.once("connect", () => { qmp = connection; });
    connection.on("data", chunk => {
      buffer += chunk;
      if (buffer.length > 1048576) { shutdown("VM monitor exceeded its limit"); return; }
      let end;
      while ((end = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, end);
        buffer = buffer.slice(end + 1);
        try {
          const packet = JSON.parse(line);
          if (packet.QMP && !negotiated) {
            negotiated = true;
            command("qmp_capabilities").then(() => { monitorReady = true; output({kind: "vm-starting"}); checkEngine(); })
              .catch(error => shutdown(error.message));
          }
          const request = pending.get(packet.id);
          if (request) {
            pending.delete(packet.id);
            clearTimeout(request.timeout);
            timers.delete(request.timeout);
            packet.error ? request.reject(new Error(packet.error.desc)) : request.resolve(packet.return);
          }
        } catch (error) { shutdown(error.message); }
      }
    });
    connection.once("error", error => {
      if (!qmp && ["ENOENT", "ECONNREFUSED"].includes(error.code) && ++attempts < 40) later(connectMonitor, 250);
      else if (!stopping) shutdown("VM monitor connection failed");
    });
    connection.once("close", () => { if (qmp === connection && !stopping) shutdown("VM monitor disconnected"); });
  }
  connectMonitor();
  if (!daemonMode) {
    let input = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", chunk => {
      input += chunk;
      if (input.length > 4096) { shutdown("Invalid VM control message size"); return; }
      let end;
      while ((end = input.indexOf("\n")) >= 0) {
        const line = input.slice(0, end);
        input = input.slice(end + 1);
        try {
          const packet = JSON.parse(line);
          if (packet.kind === "shutdown") shutdown();
          else if (packet.kind === "status") output(ready ? {kind: "vm-ready", engineVersion} : {kind: "vm-starting"});
          else throw new Error("Unsupported VM control message");
        } catch (error) { shutdown(error.message); }
      }
    });
    process.stdin.once("end", () => shutdown());
    process.stdin.once("error", () => shutdown("Android VM control channel disconnected"));
    process.stdout.once("error", () => shutdown());
  }
  for (const signal of ["SIGTERM", "SIGINT"]) process.once(signal, () => shutdown());
}

start().catch(error => {
  output({kind: "vm-error", message: error.message});
  cleanupDaemonFiles();
  console.error(error.message);
  process.exitCode = 1;
});
