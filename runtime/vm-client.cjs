"use strict";
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");
const crypto = require("node:crypto");
const {directory, load} = require("./vm-session.cjs");
const {control} = require("./vm-control.cjs");

const daemonSockPath = path.join(directory, "daemon.sock");
const daemonPidPath = path.join(directory, "daemon.pid");
const qmpPath = path.join(directory, "qmp.sock");

function checkUnixSocket(socketPath, timeoutMs = 1500) {
  return new Promise(resolve => {
    if (!fs.existsSync(socketPath)) { resolve(false); return; }
    const s = net.createConnection(socketPath);
    let done = false;
    const finish = ok => { if (done) return; done = true; s.destroy(); resolve(ok); };
    s.setTimeout(timeoutMs, () => finish(false));
    s.once("connect", () => finish(true));
    s.once("error", () => finish(false));
  });
}

function sendSingleRequest(payload, timeoutMs = 2500) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(daemonSockPath);
    let buffer = "", settled = false;
    const finish = (err, value) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (err) reject(err); else resolve(value);
    };
    socket.setEncoding("utf8");
    socket.setTimeout(timeoutMs, () => finish(Object.assign(new Error("VM daemon timed out"), {code: "ETIMEDOUT"})));
    socket.once("error", err => finish(err));
    socket.once("connect", () => {
      socket.write(JSON.stringify(payload) + "\n");
    });
    socket.on("data", chunk => {
      buffer += chunk;
      const end = buffer.indexOf("\n");
      if (end >= 0) {
        try { finish(null, JSON.parse(buffer.slice(0, end))); }
        catch (err) { finish(err); }
      }
    });
    socket.once("end", () => finish(new Error("VM daemon closed before responding")));
  });
}

async function probe() {
  try {
    const reply = await sendSingleRequest({kind: "probe"}, 2000);
    process.stdout.write(JSON.stringify(reply) + "\n");
    return;
  } catch (err) {
    const qmpAlive = await checkUnixSocket(qmpPath, 1500);
    if (err && (err.code === "ECONNREFUSED" || err.code === "ENOENT") && !qmpAlive) {
      try { if (fs.existsSync(daemonSockPath)) fs.unlinkSync(daemonSockPath); } catch {}
      try { if (fs.existsSync(daemonPidPath)) fs.unlinkSync(daemonPidPath); } catch {}
    }
    if (qmpAlive) {
      process.stdout.write(JSON.stringify({kind: "probe-result", alive: true, legacyQmp: true, ready: false}) + "\n");
      return;
    }
    process.stdout.write(JSON.stringify({kind: "probe-result", alive: false}) + "\n");
  }
}

async function setPersist(value) {
  const reply = await sendSingleRequest({kind: "set-persist", persist: value}, 3000);
  process.stdout.write(JSON.stringify(reply) + "\n");
}

function setMemory(rawMiB) {
  const mib = Number(rawMiB);
  if (![2048, 3072, 4096, 6144].includes(mib)) throw new Error("Unsupported VM memory profile: " + rawMiB);
  const cfgPath = path.join(directory, "config.json");
  const cfg = load();
  if (cfg.memoryMiB !== mib) {
    cfg.memoryMiB = mib;
    const tmp = path.join(directory, "config-mem-" + crypto.randomUUID() + ".json");
    fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2), {mode: 0o640, flag: "wx"});
    const fd = fs.openSync(tmp, "r");
    try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    fs.renameSync(tmp, cfgPath);
  }
  process.stdout.write(JSON.stringify({kind: "memory-updated", memoryMiB: mib}) + "\n");
}

async function shutdownDaemon() {
  const daemonAlive = await checkUnixSocket(daemonSockPath, 1500);
  if (daemonAlive) {
    await new Promise((resolve, reject) => {
      const socket = net.createConnection(daemonSockPath);
      let buffer = "", done = false;
      const timer = setTimeout(() => { if (!done) { done = true; socket.destroy(); reject(new Error("VM shutdown timed out")); } }, 42000);
      const finish = err => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        socket.destroy();
        if (err) reject(err); else resolve();
      };
      socket.setEncoding("utf8");
      socket.once("connect", () => {
        socket.write(JSON.stringify({kind: "subscribe"}) + "\n");
        socket.write(JSON.stringify({kind: "shutdown"}) + "\n");
      });
      socket.on("data", chunk => {
        buffer += chunk;
        let end;
        while ((end = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, end).trim();
          buffer = buffer.slice(end + 1);
          if (!line) continue;
          try {
            const packet = JSON.parse(line);
            if (packet.kind === "vm-stopped") { finish(null); return; }
          } catch {}
        }
      });
      socket.once("close", () => finish(null));
      socket.once("error", err => {
        if (["ECONNREFUSED", "ENOENT", "ECONNRESET"].includes(err.code)) finish(null);
        else finish(err);
      });
    });
    return;
  }
  const qmpAlive = await checkUnixSocket(qmpPath, 1500);
  if (qmpAlive) {
    try { await control("shutdown"); } catch {}
    for (let i = 0; i < 70; i++) {
      await new Promise(resolve => setTimeout(resolve, 500));
      if (!(await checkUnixSocket(qmpPath, 1000))) break;
    }
  }
}

async function connectWithRetry(maxAttempts = 50, delayMs = 200) {
  let lastError;
  for (let i = 0; i < maxAttempts; i++) {
    try {
      return await new Promise((resolve, reject) => {
        const socket = net.createConnection(daemonSockPath);
        socket.once("connect", () => resolve(socket));
        socket.once("error", reject);
      });
    } catch (err) {
      lastError = err;
      if (!["ENOENT", "ECONNREFUSED"].includes(err.code)) throw err;
      await new Promise(resolve => setTimeout(resolve, delayMs));
    }
  }
  throw lastError ?? new Error("Unable to connect to VM daemon");
}

async function attach(persist) {
  const socket = await connectWithRetry();
  socket.setEncoding("utf8");
  let closed = false;
  let shutdownRequested = false;
  let buffer = "";
  socket.write(JSON.stringify({kind: "subscribe", persist}) + "\n");

  socket.on("data", chunk => {
    buffer += chunk;
    if (buffer.length > 1048576) { socket.destroy(); process.exitCode = 1; return; }
    let end;
    while ((end = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, end);
      buffer = buffer.slice(end + 1);
      if (line.trim()) process.stdout.write(line + "\n");
    }
  });
  socket.once("close", () => {
    if (closed) return;
    closed = true;
    process.stdin.pause();
    process.stdin.unref?.();
  });
  socket.once("error", error => {
    if (closed) return;
    closed = true;
    process.stdout.write(JSON.stringify({kind: "vm-error", message: error.message}) + "\n");
    process.exitCode = 1;
    process.stdin.pause();
    process.stdin.unref?.();
  });

  let input = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", chunk => {
    input += chunk;
    if (input.length > 8192) { socket.destroy(); process.exitCode = 1; return; }
    let end;
    while ((end = input.indexOf("\n")) >= 0) {
      const line = input.slice(0, end).trim();
      input = input.slice(end + 1);
      if (!line || socket.destroyed) continue;
      try { if (JSON.parse(line).kind === "shutdown") shutdownRequested = true; } catch {}
      socket.write(line + "\n");
    }
  });
  const detach = () => {
    if (closed) return;
    closed = true;
    socket.destroy();
    process.stdin.pause();
    process.stdin.unref?.();
  };
  process.stdin.once("end", () => {
    if (!shutdownRequested) detach();
  });
  process.stdin.once("error", () => {
    if (!shutdownRequested) detach();
  });
  process.stdout.once("error", detach);
  for (const signal of ["SIGTERM", "SIGINT"]) process.once(signal, detach);
}

async function main() {
  load();
  const [mode, arg] = process.argv.slice(2);
  if (mode === "--probe") await probe();
  else if (mode === "--set-persist") await setPersist(arg !== "0");
  else if (mode === "--set-memory") setMemory(arg);
  else if (mode === "--shutdown") await shutdownDaemon();
  else if (mode === "--attach" || !mode) await attach(arg !== "0");
  else throw new Error("Unsupported vm-client mode: " + mode);
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
