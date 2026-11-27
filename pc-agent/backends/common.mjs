// 跨平台通用文件操作、Shell 执行与主机系统信息模块（Windows / macOS / Linux 共享，零外部依赖）
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawn} from "node:child_process";

const MAX_READ_BYTES = 512 * 1024;
const MAX_TRANSFER_BYTES = 15 * 1024 * 1024;
const MAX_SHELL_OUTPUT = 64 * 1024;

export function collectSystemInfo() {
  const cpus = os.cpus() || [];
  const ifaces = os.networkInterfaces() || {};
  const nets = [];
  for (const [name, addrs] of Object.entries(ifaces)) {
    for (const a of addrs || []) {
      if (a.internal || a.family !== "IPv4") continue;
      nets.push({name, address: a.address, mac: a.mac, netmask: a.netmask});
    }
  }
  return {
    hostname: os.hostname(),
    platform: process.platform,
    release: os.release(),
    arch: process.arch,
    user: os.userInfo().username,
    homeDir: os.homedir(),
    cpuModel: cpus[0]?.model || "",
    cpuCores: cpus.length,
    totalMemMB: Math.round(os.totalmem() / (1024 * 1024)),
    freeMemMB: Math.round(os.freemem() / (1024 * 1024)),
    uptimeSec: Math.round(os.uptime()),
    networks: nets,
    nodeVersion: process.version,
  };
}

export async function runShellCommand(params = {}) {
  const command = String(params.command || "").trim();
  if (!command) throw new Error("command 不能为空");
  const cwd = params.cwd ? path.resolve(String(params.cwd)) : os.homedir();
  const timeoutSec = Math.max(1, Math.min(180, Number(params.timeoutSec) || 30));
  const shellPref = String(params.shell || "auto").toLowerCase();

  let bin;
  let args;
  if (process.platform === "win32") {
    if (shellPref === "cmd") {
      bin = process.env.COMSPEC || "cmd.exe";
      args = ["/d", "/s", "/c", command];
    } else {
      bin = "powershell.exe";
      const wrapped = `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8; ${command}`;
      args = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", wrapped];
    }
  } else {
    if (shellPref === "zsh") { bin = "/bin/zsh"; args = ["-lc", command]; }
    else if (shellPref === "sh") { bin = "/bin/sh"; args = ["-lc", command]; }
    else { bin = "/bin/bash"; args = ["-lc", command]; }
  }

  const started = Date.now();
  return await new Promise(resolve => {
    let stdout = "";
    let stderr = "";
    let settled = false;
    const child = spawn(bin, args, {cwd, env: {...process.env, PYTHONUTF8: "1"}, stdio: ["ignore", "pipe", "pipe"], windowsHide: true});
    const finish = (exitCode, extraErr = "") => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const errOut = extraErr ? `${stderr}${stderr ? "\n" : ""}${extraErr}` : stderr;
      resolve({
        ok: exitCode === 0,
        exitCode,
        shell: path.basename(bin),
        cwd,
        latencyMs: Date.now() - started,
        stdout,
        stderr: errOut,
      });
    };
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch {}
      finish(124, `命令执行超时 (${timeoutSec}s)`);
    }, timeoutSec * 1000);
    child.on("error", err => finish(-1, err.message));
    child.stdout.on("data", d => {
      if (stdout.length < MAX_SHELL_OUTPUT) stdout += d.toString("utf8");
    });
    child.stderr.on("data", d => {
      if (stderr.length < MAX_SHELL_OUTPUT) stderr += d.toString("utf8");
    });
    child.on("close", code => finish(typeof code === "number" ? code : -1));
  });
}

export async function handleFilesAction(params = {}) {
  const act = String(params.action || "list").trim().toLowerCase();
  const rawPath = String(params.path || "~");
  const target = rawPath === "~" ? os.homedir()
    : rawPath.startsWith("~/") || rawPath.startsWith("~\\") ? path.resolve(os.homedir(), rawPath.slice(2))
    : path.resolve(rawPath);

  if (act === "stat") {
    const st = await fs.stat(target);
    return {
      path: target,
      isDirectory: st.isDirectory(),
      isFile: st.isFile(),
      size: st.size,
      mtimeMs: Math.round(st.mtimeMs),
    };
  }

  if (act === "list") {
    const entries = await fs.readdir(target, {withFileTypes: true});
    const items = [];
    for (const e of entries.slice(0, 200)) {
      const full = path.join(target, e.name);
      const st = await fs.stat(full).catch(() => null);
      items.push({
        name: e.name,
        path: full,
        type: e.isDirectory() ? "dir" : e.isFile() ? "file" : "other",
        size: st?.size || 0,
        mtimeMs: st ? Math.round(st.mtimeMs) : 0,
      });
    }
    items.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === "dir" ? -1 : 1));
    return {path: target, total: entries.length, truncated: entries.length > 200, items};
  }

  if (act === "read") {
    const maxBytes = Math.max(1024, Math.min(MAX_READ_BYTES, Number(params.maxBytes) || 131072));
    const st = await fs.stat(target);
    if (!st.isFile()) throw new Error(`目标不是普通文件：${target}`);
    const fh = await fs.open(target, "r");
    try {
      const len = Math.min(st.size, maxBytes);
      const buf = Buffer.alloc(len);
      await fh.read(buf, 0, len, 0);
      return {path: target, size: st.size, truncated: st.size > maxBytes, content: buf.toString("utf8")};
    } finally {
      await fh.close();
    }
  }

  if (act === "read_base64") {
    const st = await fs.stat(target);
    if (!st.isFile()) throw new Error(`目标不是普通文件：${target}`);
    if (st.size > MAX_TRANSFER_BYTES) throw new Error(`文件过大 (${st.size}B)，单次传输上限 15MB`);
    const buf = await fs.readFile(target);
    return {path: target, size: st.size, base64: buf.toString("base64")};
  }

  if (act === "write") {
    const content = String(params.content ?? "");
    await fs.mkdir(path.dirname(target), {recursive: true});
    await fs.writeFile(target, content, "utf8");
    return {ok: true, path: target, bytes: Buffer.byteLength(content, "utf8")};
  }

  if (act === "write_base64") {
    const buf = Buffer.from(String(params.base64 || ""), "base64");
    if (buf.length > MAX_TRANSFER_BYTES) throw new Error("单次写入文件上限 15MB");
    await fs.mkdir(path.dirname(target), {recursive: true});
    await fs.writeFile(target, buf);
    return {ok: true, path: target, bytes: buf.length};
  }

  if (act === "delete") {
    await fs.rm(target, {recursive: true, force: false});
    return {ok: true, deleted: target};
  }

  if (act === "search") {
    const q = String(params.query || "").trim().toLowerCase();
    if (!q) throw new Error("search 需要提供 query 关键词");
    const hits = [];
    async function walk(dir, depth) {
      if (depth > 4 || hits.length >= 60) return;
      const entries = await fs.readdir(dir, {withFileTypes: true}).catch(() => []);
      for (const e of entries) {
        if (hits.length >= 60) return;
        if (e.name === "node_modules" || e.name === ".git" || e.name === "$RECYCLE.BIN") continue;
        const full = path.join(dir, e.name);
        if (e.name.toLowerCase().includes(q)) {
          hits.push({path: full, type: e.isDirectory() ? "dir" : "file"});
        }
        if (e.isDirectory()) await walk(full, depth + 1);
      }
    }
    await walk(target, 0);
    return {root: target, query: params.query, hits};
  }

  throw new Error(`未知文件动作：${act}`);
}
