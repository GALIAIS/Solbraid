import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import {randomBytes, randomUUID} from "node:crypto";
import {spawn, execFile} from "node:child_process";
import {promisify} from "node:util";
import {createRequire} from "node:module";

const execFileAsync = promisify(execFile);
const require = createRequire(import.meta.url);
const MAX_SESSIONS = 8;
const SCROLLBACK = 2000;
const RING_BUFFER_MAX = 192 * 1024;
const FRAME_DATA = 1;
const FRAME_RESIZE = 2;
const FRAME_EXIT = 3;

function quote(value) { return "'" + String(value).replace(/'/g, "'\\''") + "'"; }
function dimensions(cols, rows) {
  if (!Number.isInteger(cols) || cols < 2 || cols > 500 || !Number.isInteger(rows) || rows < 1 || rows > 300) throw new Error("终端尺寸无效");
  return {cols, rows};
}

/** 轻量真 PTY 宿主脚本：通过 Linux 内核 pty.openpty() + TIOCSWINSZ 原生派发 SIGWINCH 给前台进程组，退出即发 SIGHUP 杜绝孤儿进程。 */
const PYTHON_PTY_HELPER = `
import fcntl, os, pty, select, signal, struct, sys, termios

cols = int(sys.argv[1])
rows = int(sys.argv[2])
cmd = sys.argv[3:]
master, slave = pty.openpty()
try:
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
except Exception:
    pass
pid = os.fork()
if pid == 0:
    os.close(master)
    os.setsid()
    try:
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
    except Exception:
        pass
    os.dup2(slave, 0)
    os.dup2(slave, 1)
    os.dup2(slave, 2)
    if slave > 2:
        os.close(slave)
    try:
        os.close(3)
    except Exception:
        pass
    os.execvp(cmd[0], cmd)
    os._exit(127)
os.close(slave)
ctrl_fd = 3
try:
    os.set_blocking(ctrl_fd, False)
except Exception:
    ctrl_fd = -1
os.set_blocking(master, False)
os.set_blocking(0, False)
ctrl_buf = b""
write_buf = bytearray()
stdin_open = True
exit_code = 0

def drain_stdout(chunk):
    view = memoryview(chunk)
    written = 0
    while written < len(chunk):
        try:
            n = os.write(1, view[written:])
            if n <= 0:
                break
            written += n
        except BlockingIOError:
            select.select([], [1], [], 0.05)

while True:
    rfds = [master]
    wfds = []
    if stdin_open and len(write_buf) < 65536:
        rfds.append(0)
    if ctrl_fd >= 0:
        rfds.append(ctrl_fd)
    if write_buf:
        wfds.append(master)
    try:
        r, w, _ = select.select(rfds, wfds, [], 0.25)
    except InterruptedError:
        continue
    if master in w and write_buf:
        try:
            n = os.write(master, write_buf)
            if n > 0:
                del write_buf[:n]
        except BlockingIOError:
            pass
        except OSError:
            write_buf.clear()
            stdin_open = False
    if master in r:
        try:
            data = os.read(master, 32768)
            if not data:
                break
            drain_stdout(data)
        except BlockingIOError:
            pass
        except OSError:
            break
    if stdin_open and 0 in r:
        try:
            inp = os.read(0, 16384)
            if not inp:
                stdin_open = False
            else:
                write_buf.extend(inp)
                try:
                    n = os.write(master, write_buf)
                    if n > 0:
                        del write_buf[:n]
                except BlockingIOError:
                    pass
        except BlockingIOError:
            pass
        except OSError:
            stdin_open = False
    if ctrl_fd >= 0 and ctrl_fd in r:
        try:
            chunk = os.read(ctrl_fd, 4096)
            if not chunk:
                ctrl_fd = -1
            else:
                ctrl_buf += chunk
                while b"\\n" in ctrl_buf:
                    line, ctrl_buf = ctrl_buf.split(b"\\n", 1)
                    parts = line.decode("utf-8", "ignore").strip().split()
                    if len(parts) == 3 and parts[0] == "R":
                        nc, nr = int(parts[1]), int(parts[2])
                        try:
                            # Linux 内核 TIOCSWINSZ 会自动向前台进程组派发一次 SIGWINCH，避免重复发信号触发二次重绘
                            fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", nr, nc, 0, 0))
                        except Exception:
                            pass
        except BlockingIOError:
            pass
        except OSError:
            ctrl_fd = -1
    try:
        wpid, status = os.waitpid(pid, os.WNOHANG)
        if wpid == pid:
            exit_code = os.waitstatus_to_exitcode(status) if hasattr(os, "waitstatus_to_exitcode") else (status >> 8)
            # 退出前排干 master 中残余输出，避免最后一行日志被截断
            for _ in range(32):
                try:
                    rr, _, _ = select.select([master], [], [], 0.02)
                    if master not in rr:
                        break
                    tail = os.read(master, 32768)
                    if not tail:
                        break
                    drain_stdout(tail)
                except OSError:
                    break
            break
    except ChildProcessError:
        break
try:
    os.close(master)
except Exception:
    pass
sys.exit(exit_code & 0xFF)
`.trim();

function encodeFrame(type, payloadBuf) {
  const header = Buffer.allocUnsafe(5);
  header.writeUInt8(type, 0);
  header.writeUInt32BE(payloadBuf.length, 1);
  return Buffer.concat([header, payloadBuf]);
}

function appendRing(entry, chunk) {
  entry.ringChunks.push(chunk);
  entry.ringBytes += chunk.length;
  while (entry.ringBytes > RING_BUFFER_MAX && entry.ringChunks.length > 1) {
    const dropped = entry.ringChunks.shift();
    entry.ringBytes -= dropped.length;
  }
}

export function createTerminalHub({home, baseEnv, notify, prepareSshPtyLaunch, preparePtyEnv}) {
  const sessions = new Map();
  let opening = 0;
  let streamServer = null;
  let streamPort = 0;
  const streamToken = randomBytes(24).toString("hex");
  let hasPython3 = null;

  async function detectPython3() {
    if (hasPython3 !== null) return hasPython3;
    try {
      await execFileAsync("/usr/bin/python3", ["--version"], {timeout: 2000});
      hasPython3 = true;
    } catch {
      hasPython3 = false;
    }
    return hasPython3;
  }

  async function ensureStreamServer() {
    if (streamServer && streamPort > 0) return {streamPort, streamToken};
    streamServer = net.createServer(socket => {
      socket.setNoDelay(true);
      let authed = false;
      let attachedEntry = null;
      let buf = Buffer.alloc(0);

      const detach = () => {
        if (attachedEntry) {
          attachedEntry.clients.delete(socket);
          attachedEntry = null;
        }
      };

      socket.on("close", detach);
      socket.on("error", detach);
      socket.on("data", chunk => {
        buf = buf.length === 0 ? chunk : Buffer.concat([buf, chunk]);
        if (!authed) {
          const nl = buf.indexOf(10);
          if (nl < 0) {
            if (buf.length > 4096) socket.destroy();
            return;
          }
          const line = buf.subarray(0, nl).toString("utf8").trim();
          buf = buf.subarray(nl + 1);
          let hello;
          try { hello = JSON.parse(line); } catch { socket.destroy(); return; }
          if (!hello || hello.token !== streamToken) { socket.destroy(); return; }
          const entry = sessions.get(hello.ptyId);
          if (!entry) { socket.write("ERR not_found\n"); socket.destroy(); return; }
          authed = true;
          attachedEntry = entry;
          entry.clients.add(socket);
          socket.write("OK\n");
          if (Number.isInteger(hello.cols) && Number.isInteger(hello.rows)) {
            void resizePty({ptyId: entry.id, cols: hello.cols, rows: hello.rows}).catch(() => {});
          }
          if (entry.ringChunks.length > 0) {
            socket.write(encodeFrame(FRAME_DATA, Buffer.concat(entry.ringChunks)));
          }
          if (entry.status !== "running") {
            socket.write(encodeFrame(FRAME_EXIT, Buffer.from(JSON.stringify({exitCode: entry.exitCode, error: entry.error || ""}), "utf8")));
          }
        }
        while (authed && buf.length >= 5) {
          const frameType = buf.readUInt8(0);
          const payloadLen = buf.readUInt32BE(1);
          if (payloadLen > 512 * 1024) { socket.destroy(); return; }
          if (buf.length < 5 + payloadLen) break;
          const payload = buf.subarray(5, 5 + payloadLen);
          buf = buf.subarray(5 + payloadLen);
          if (!attachedEntry || attachedEntry.status !== "running") continue;
          if (frameType === FRAME_DATA && attachedEntry.child.stdin.writable) {
            attachedEntry.child.stdin.write(payload);
          } else if (frameType === FRAME_RESIZE && payload.length >= 4) {
            const c = payload.readUInt16BE(0);
            const r = payload.readUInt16BE(2);
            void resizePty({ptyId: attachedEntry.id, cols: c, rows: r}).catch(() => {});
          }
        }
      });
    });
    await new Promise((resolve, reject) => {
      streamServer.once("error", reject);
      streamServer.listen(0, "127.0.0.1", () => {
        const addr = streamServer.address();
        streamPort = addr && typeof addr === "object" ? addr.port : 0;
        resolve();
      });
    });
    return {streamPort, streamToken};
  }

  const describe = entry => ({
    ptyId: entry.id, cwd: entry.cwd, sshHostId: entry.sshHostId, remoteCwd: entry.remoteCwd || "", title: entry.title,
    cols: entry.cols, rows: entry.rows, status: entry.status, sequence: entry.sequence,
    exitCode: entry.exitCode, error: entry.error || null, streamOnly: Boolean(entry.streamOnly),
    streamPort, streamToken,
  });
  const changed = () => notify("pty/sessionsChanged", {sessions: [...sessions.values()].map(describe), streamPort, streamToken});
  const get = id => {
    const entry = sessions.get(id);
    if (!entry) throw new Error("终端会话不存在，请重新连接");
    return entry;
  };

  async function openPty({cwd, cols = 80, rows = 24, sshHostId = null, sshAlias = null, remoteCwd = "", streamOnly = false} = {}) {
    dimensions(cols, rows);
    if (sessions.size + opening >= MAX_SESSIONS) throw new Error("终端已达 8 个，请先结束不用的会话");
    opening++;
    let entry, tempDir, cleanup = () => {};
    try {
      await ensureStreamServer();
      const target = await fs.realpath(cwd || path.join(home, "workspace"));
      if (!(await fs.stat(target)).isDirectory()) throw new Error("终端工作目录不存在");
      let terminal = null, serializer = null;
      if (!streamOnly) {
        const {Terminal} = require("./terminal-headless.cjs");
        const {SerializeAddon} = require("./terminal-serialize.cjs");
        terminal = new Terminal({cols, rows, scrollback: SCROLLBACK, allowProposedApi: true});
        serializer = new SerializeAddon();
        terminal.loadAddon(serializer);
      }
      let env = {...(preparePtyEnv ? await preparePtyEnv(target) : baseEnv(null, null)), TERM: "xterm-256color", COLORTERM: "truecolor", COLUMNS: String(cols), LINES: String(rows)};
      delete env.NO_COLOR;
      tempDir = await fs.mkdtemp("/tmp/solbraid-pty-");
      const profileFile = path.join(home, ".galiais", "env", "profile.sh");
      let argv = ["/bin/bash", "-i"];
      let commandStr = "/bin/bash -i";
      let sshRemoteCwd = "";
      if (sshHostId || sshAlias) {
        if (!prepareSshPtyLaunch) throw new Error("SSH 终端未配置");
        sshRemoteCwd = String(remoteCwd || "").trim();
        const launch = await prepareSshPtyLaunch({hostId: sshHostId, alias: sshAlias, remoteCwd: sshRemoteCwd, baseEnvObj: env});
        commandStr = launch.command;
        argv = ["/bin/sh", "-c", launch.command];
        env = launch.env;
        cleanup = launch.cleanup;
      } else {
        const rcFile = path.join(tempDir, "bashrc");
        const userRc = path.join(home, ".bashrc");
        const rcBody = [
          `[ -f ${quote(userRc)} ] && . ${quote(userRc)}`,
          `[ "\${_SOLBRAID_PROFILE_PID:-}" != "$$" ] && [ -f ${quote(profileFile)} ] && . ${quote(profileFile)}`,
          `shopt -s checkwinsize 2>/dev/null || true`,
          `[ -z "\${PS1:-}" ] && export PS1='\\u@\\h:\\w\\$ '`,
        ].join("\n") + "\n";
        await fs.writeFile(rcFile, rcBody, {mode: 0o600});
        argv = ["/bin/bash", "--rcfile", rcFile, "-i"];
        commandStr = `/bin/bash --rcfile ${quote(rcFile)} -i`;
      }

      const usePythonPty = await detectPython3();
      const ttyFile = path.join(tempDir, "tty");
      let child, ctrlStream = null;
      if (usePythonPty) {
        const helperFile = path.join(tempDir, "pty-helper.py");
        await fs.writeFile(helperFile, PYTHON_PTY_HELPER + "\n", {mode: 0o600});
        child = spawn("/usr/bin/python3", [helperFile, String(cols), String(rows), ...argv], {
          cwd: target, env, detached: true, stdio: ["pipe", "pipe", "pipe", "pipe"],
        });
        ctrlStream = child.stdio[3];
      } else {
        const launch = "(/usr/bin/tty > " + quote(ttyFile) + " 2>/dev/null || true); (/usr/bin/stty cols " + cols + " rows " + rows + " 2>/dev/null || true); exec " + commandStr;
        child = spawn("/usr/bin/script", ["-q", "-e", "-f", "-c", launch, "/dev/null"], {
          cwd: target, env, detached: true, stdio: ["pipe", "pipe", "pipe"],
        });
      }

      entry = {
        id: randomUUID(), cwd: target, sshHostId, remoteCwd: sshRemoteCwd,
        title: sshAlias ? (sshRemoteCwd ? `${sshAlias}:${path.posix.basename(sshRemoteCwd) || "/"}` : sshAlias) : (path.basename(target) || "终端"),
        cols, rows, status: "running", sequence: 0, exitCode: null, error: "", child, ctrlStream, usePythonPty,
        terminal, serializer, streamOnly: Boolean(streamOnly), clients: new Set(), ringChunks: [], ringBytes: 0,
        tempDir, ttyFile, ttyPath: null, outputTail: Promise.resolve(), closed: null,
      };
      sessions.set(entry.id, entry);
      if (terminal) {
        terminal.onData(data => { if (child.stdin.writable && entry.status === "running") child.stdin.write(data); });
        terminal.onTitleChange(title => {
          entry.title = String(title).replace(/[\x00-\x1f\x7f]/g, "").slice(0, 100) || entry.title;
          changed();
        });
      }

      const broadcastRaw = chunk => {
        appendRing(entry, chunk);
        if (entry.clients.size > 0) {
          const frame = encodeFrame(FRAME_DATA, chunk);
          for (const sock of entry.clients) {
            if (!sock.destroyed && sock.writable) sock.write(frame);
          }
        }
      };

      const consume = stream => stream.on("data", chunk => {
        if (entry.streamOnly) {
          broadcastRaw(chunk);
          return;
        }
        stream.pause();
        entry.outputTail = entry.outputTail.then(() => new Promise(resolve => terminal.write(chunk, resolve))).then(() => {
          broadcastRaw(chunk);
          // 兼容旧 RPC 消费端时按 64KB 切片发送，绝不触发 256KB base64 上限异常
          for (let off = 0; off < chunk.length; off += 65536) {
            const slice = chunk.subarray(off, Math.min(off + 65536, chunk.length));
            entry.sequence++;
            notify("pty/output", {ptyId: entry.id, sequence: entry.sequence, dataBase64: slice.toString("base64")});
          }
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
          const exitFrame = encodeFrame(FRAME_EXIT, Buffer.from(JSON.stringify({exitCode: code, signal: signal || null, error: entry.error || ""}), "utf8"));
          for (const sock of entry.clients) {
            if (!sock.destroyed && sock.writable) sock.write(exitFrame);
          }
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
      return {ptyId: entry.id, cwd: target, streamPort, streamToken, session: describe(entry)};
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
    entry.cols = cols;
    entry.rows = rows;
    entry.resizeSeq = (entry.resizeSeq || 0) + 1;
    const seq = entry.resizeSeq;
    if (entry.usePythonPty && entry.ctrlStream && entry.ctrlStream.writable) {
      entry.ctrlStream.write(`R ${cols} ${rows}\n`);
    } else {
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
      if (entry.resizeSeq !== seq) return {ok: true};
      if (entry.ttyPath) {
        try {
          await execFileAsync("/usr/bin/stty", ["-F", entry.ttyPath, "cols", String(entry.cols), "rows", String(entry.rows)], {timeout: 3000});
        } catch {}
      }
      try { if (entry.child?.pid) process.kill(-entry.child.pid, "SIGWINCH"); } catch {}
    }
    if (entry.terminal) entry.terminal.resize(entry.cols, entry.rows);
    changed();
    return {ok: true};
  }

  async function attachPty({ptyId}) {
    const entry = get(ptyId);
    await entry.outputTail;
    if (!entry.serializer) {
      return {session: describe(entry), snapshot: "", streamPort, streamToken};
    }
    let scrollback = SCROLLBACK, snapshot;
    do {
      snapshot = entry.serializer.serialize({scrollback});
      if (Buffer.byteLength(JSON.stringify(snapshot)) < 6 * 1024 * 1024) break;
      scrollback = Math.floor(scrollback / 2);
    } while (scrollback > 0);
    if (scrollback === 0) snapshot = entry.serializer.serialize({scrollback: 0});
    if (Buffer.byteLength(JSON.stringify(snapshot)) >= 6 * 1024 * 1024) throw new Error("终端屏幕过大，请缩小窗口后重试");
    return {session: describe(entry), snapshot, streamPort, streamToken};
  }

  function listPtys() { return {sessions: [...sessions.values()].map(describe), streamPort, streamToken}; }

  async function closePty({ptyId}) {
    const entry = sessions.get(ptyId);
    if (!entry) return {ok: true};
    if (!entry.closeTask) entry.closeTask = closeEntry(entry);
    return entry.closeTask;
  }

  async function closeEntry(entry) {
    if (entry.status === "running") {
      entry.status = "closing";
      try { entry.ctrlStream?.end(); } catch {}
      try { entry.child.stdin?.end(); } catch {}
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
    for (const sock of entry.clients) {
      try { sock.destroy(); } catch {}
    }
    entry.clients.clear();
    sessions.delete(entry.id);
    if (entry.terminal) entry.terminal.dispose();
    changed();
    return {ok: true};
  }

  async function closeAll() {
    await Promise.all([...sessions.keys()].map(ptyId => closePty({ptyId})));
    if (streamServer) {
      try { streamServer.close(); } catch {}
      streamServer = null;
      streamPort = 0;
    }
  }
  return {openPty, writePty, resizePty, attachPty, listPtys, closePty, closeAll};
}
