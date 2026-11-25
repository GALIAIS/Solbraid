import fs from "node:fs/promises";
import path from "node:path";
import {randomUUID} from "node:crypto";
import {spawn, execFile} from "node:child_process";
import {promisify} from "node:util";
import {createRequire} from "node:module";

const execFileAsync = promisify(execFile);
const require = createRequire(import.meta.url);
const MAX_SESSIONS = 8;
const SCROLLBACK = 2000;

function quote(value) { return "'" + String(value).replace(/'/g, "'\\''") + "'"; }
function dimensions(cols, rows) {
  if (!Number.isInteger(cols) || cols < 2 || cols > 500 || !Number.isInteger(rows) || rows < 1 || rows > 300) throw new Error("终端尺寸无效");
  return {cols, rows};
}

export function createTerminalHub({home, baseEnv, notify, prepareSshPtyLaunch, preparePtyEnv}) {
  const sessions = new Map();
  let opening = 0;
  const describe = entry => ({
    ptyId: entry.id, cwd: entry.cwd, sshHostId: entry.sshHostId, remoteCwd: entry.remoteCwd || "", title: entry.title,
    cols: entry.cols, rows: entry.rows, status: entry.status, sequence: entry.sequence,
    exitCode: entry.exitCode, error: entry.error || null,
  });
  const changed = () => notify("pty/sessionsChanged", {sessions: [...sessions.values()].map(describe)});
  const get = id => {
    const entry = sessions.get(id);
    if (!entry) throw new Error("终端会话不存在，请重新连接");
    return entry;
  };

  async function openPty({cwd, cols = 80, rows = 24, sshHostId = null, sshAlias = null, remoteCwd = ""} = {}) {
    dimensions(cols, rows);
    if (sessions.size + opening >= MAX_SESSIONS) throw new Error("终端已达 8 个，请先结束不用的会话");
    opening++;
    let entry, tempDir, cleanup = () => {};
    try {
      const target = await fs.realpath(cwd || path.join(home, "workspace"));
      if (!(await fs.stat(target)).isDirectory()) throw new Error("终端工作目录不存在");
      const {Terminal} = require("./terminal-headless.cjs");
      const {SerializeAddon} = require("./terminal-serialize.cjs");
      const terminal = new Terminal({cols, rows, scrollback: SCROLLBACK, allowProposedApi: true});
      const serializer = new SerializeAddon();
      terminal.loadAddon(serializer);
      let env = {...(preparePtyEnv ? await preparePtyEnv(target) : baseEnv(null, null)), TERM: "xterm-256color", COLORTERM: "truecolor", COLUMNS: String(cols), LINES: String(rows)};
      delete env.NO_COLOR;
      tempDir = await fs.mkdtemp("/tmp/solbraid-pty-");
      const profileFile = path.join(home, ".galiais", "env", "profile.sh");
      let command = "/bin/bash -i";
      let sshRemoteCwd = "";
      if (sshHostId || sshAlias) {
        if (!prepareSshPtyLaunch) throw new Error("SSH 终端未配置");
        sshRemoteCwd = String(remoteCwd || "").trim();
        const launch = await prepareSshPtyLaunch({hostId: sshHostId, alias: sshAlias, remoteCwd: sshRemoteCwd, baseEnvObj: env});
        command = launch.command;
        env = launch.env;
        cleanup = launch.cleanup;
      } else {
        const rcFile = path.join(tempDir, "bashrc");
        const userRc = path.join(home, ".bashrc");
        const rcBody = [
          `[ -f ${quote(userRc)} ] && . ${quote(userRc)}`,
          `[ -f ${quote(profileFile)} ] && . ${quote(profileFile)}`,
          `[ -z "\${PS1:-}" ] && export PS1='\\u@\\h:\\w\\$ '`,
        ].join("\n") + "\n";
        await fs.writeFile(rcFile, rcBody, {mode: 0o600});
        command = `/bin/bash --rcfile ${quote(rcFile)} -i`;
      }
      const ttyFile = path.join(tempDir, "tty");
      const launch = "(/usr/bin/tty > " + quote(ttyFile) + " 2>/dev/null || true); (/usr/bin/stty cols " + cols + " rows " + rows + " 2>/dev/null || true); exec " + command;
      const child = spawn("/usr/bin/script", ["-q", "-e", "-f", "-c", launch, "/dev/null"], {
        cwd: target, env, detached: true, stdio: ["pipe", "pipe", "pipe"],
      });
      entry = {id: randomUUID(), cwd: target, sshHostId, remoteCwd: sshRemoteCwd, title: sshAlias ? (sshRemoteCwd ? `${sshAlias}:${path.posix.basename(sshRemoteCwd) || "/"}` : sshAlias) : (path.basename(target) || "终端"),
        cols, rows, status: "running", sequence: 0, exitCode: null, error: "", child, terminal, serializer,
        tempDir, ttyFile, ttyPath: null, outputTail: Promise.resolve(), closed: null};
      sessions.set(entry.id, entry);
      terminal.onData(data => { if (child.stdin.writable && entry.status === "running") child.stdin.write(data); });
      terminal.onTitleChange(title => {
        entry.title = String(title).replace(/[\x00-\x1f\x7f]/g, "").slice(0, 100) || entry.title;
        changed();
      });
      const consume = stream => stream.on("data", chunk => {
        stream.pause();
        entry.outputTail = entry.outputTail.then(() => new Promise(resolve => terminal.write(chunk, resolve))).then(() => {
          entry.sequence++;
          notify("pty/output", {ptyId: entry.id, sequence: entry.sequence, dataBase64: chunk.toString("base64")});
          // PTY 管道提供回压，避免大量输出淹没 Android 的 RPC 事件队列。
          setTimeout(() => stream.resume(), 4);
        }).catch(error => {
          entry.error = error.message;
          child.kill("SIGTERM");
        });
      });
      consume(child.stdout);
      consume(child.stderr);
      child.stdin.on("error", error => { entry.error = error.message; child.kill("SIGTERM"); });
      child.once("error", error => { entry.error = error.message; });
      entry.closed = new Promise(resolve => child.once("close", (code, signal) => {
        void entry.outputTail.finally(async () => {
          entry.status = "exited";
          entry.exitCode = code;
          if (signal && !entry.error) entry.error = "终端已结束（" + signal + "）";
          await cleanup();
          await fs.rm(tempDir, {recursive: true, force: true});
          notify("pty/closed", {...describe(entry), signal});
          changed();
        }).finally(resolve).catch(error => { entry.error = error.message; });
      }));
      await new Promise((resolve, reject) => {
        child.once("spawn", resolve);
        child.once("error", reject);
      });
      changed();
      return {ptyId: entry.id, cwd: target, session: describe(entry)};
    } catch (error) {
      if (entry) await closePty({ptyId: entry.id});
      else { await cleanup(); if (tempDir) await fs.rm(tempDir, {recursive: true, force: true}); }
      throw error;
    } finally { opening--; }
  }

  async function writePty({ptyId, data, dataBase64}) {
    const entry = get(ptyId);
    if (entry.status !== "running" || !entry.child.stdin.writable) throw new Error("终端已退出，请重新连接");
    if (dataBase64 !== undefined && (typeof dataBase64 !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(dataBase64))) throw new Error("终端输入编码无效");
    if (dataBase64 === undefined && typeof data !== "string") throw new Error("终端输入无效");
    const bytes = dataBase64 === undefined ? Buffer.from(data, "utf8") : Buffer.from(dataBase64, "base64");
    if (bytes.length > 256 * 1024 || entry.child.stdin.writableLength > 256 * 1024) throw new Error("终端输入过长，请分段粘贴");
    await new Promise((resolve, reject) => entry.child.stdin.write(bytes, error => error ? reject(error) : resolve()));
    return {ok: true};
  }

  async function resizePty({ptyId, cols, rows}) {
    dimensions(cols, rows);
    const entry = get(ptyId);
    if (entry.status !== "running") return {ok: false, exited: true};
    if (entry.cols === cols && entry.rows === rows) return {ok: true};
    if (!entry.ttyPath) {
      const deadline = Date.now() + 2500;
      while (!entry.ttyPath) {
        try {
          const raw = (await fs.readFile(entry.ttyFile, "utf8")).trim();
          if (/^\/dev\/pts\/\d+$/.test(raw)) entry.ttyPath = raw;
        } catch (error) { if (error.code !== "ENOENT") throw error; }
        if (entry.ttyPath) break;
        if (entry.status !== "running" || Date.now() >= deadline) break;
        await new Promise(resolve => setTimeout(resolve, 40));
      }
    }
    await entry.outputTail;
    if (entry.ttyPath) {
      try {
        await execFileAsync("/usr/bin/stty", ["-F", entry.ttyPath, "cols", String(cols), "rows", String(rows)], {timeout: 3000});
      } catch {}
    }
    try { if (entry.child?.pid) process.kill(-entry.child.pid, "SIGWINCH"); } catch {}
    entry.terminal.resize(cols, rows);
    entry.cols = cols; entry.rows = rows;
    changed();
    return {ok: true};
  }

  async function attachPty({ptyId}) {
    const entry = get(ptyId);
    await entry.outputTail;
    let scrollback = SCROLLBACK, snapshot;
    do {
      snapshot = entry.serializer.serialize({scrollback});
      if (Buffer.byteLength(JSON.stringify(snapshot)) < 6 * 1024 * 1024) break;
      scrollback = Math.floor(scrollback / 2);
    } while (scrollback > 0);
    if (scrollback === 0) snapshot = entry.serializer.serialize({scrollback: 0});
    if (Buffer.byteLength(JSON.stringify(snapshot)) >= 6 * 1024 * 1024) throw new Error("终端屏幕过大，请缩小窗口后重试");
    return {session: describe(entry), snapshot};
  }

  function listPtys() { return {sessions: [...sessions.values()].map(describe)}; }

  async function closePty({ptyId}) {
    const entry = sessions.get(ptyId);
    if (!entry) return {ok: true};
    if (!entry.closeTask) entry.closeTask = closeEntry(entry);
    return entry.closeTask;
  }

  async function closeEntry(entry) {
    if (entry.status === "running") {
      entry.status = "closing";
      entry.child.kill("SIGTERM");
      let timer;
      try {
        await Promise.race([entry.closed, new Promise(resolve => { timer = setTimeout(resolve, 1500); })]);
      } finally { clearTimeout(timer); }
      if (entry.child.exitCode === null && entry.child.signalCode === null) {
        try { process.kill(-entry.child.pid, "SIGKILL"); }
        catch (error) { if (error.code !== "ESRCH") throw error; }
      }
      await entry.closed;
    }
    sessions.delete(entry.id);
    entry.terminal.dispose();
    changed();
    return {ok: true};
  }

  async function closeAll() { await Promise.all([...sessions.keys()].map(ptyId => closePty({ptyId}))); }
  return {openPty, writePty, resizePty, attachPty, listPtys, closePty, closeAll};
}
