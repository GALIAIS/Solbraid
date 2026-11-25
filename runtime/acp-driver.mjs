import fs from "node:fs/promises";
import {constants} from "node:fs";
import path from "node:path";
import {spawn} from "node:child_process";
import {createInterface} from "node:readline";

/**
 * 标准 ACP（Agent Client Protocol，JSON-RPC 2.0 over stdio）客户端驱动。
 * 兼容 Antigravity CLI (`agy acp`)、Gemini CLI (`gemini --acp`)、OpenCode (`opencode acp`)、Goose 及所有 ACP Agent。
 */
export class AcpDriver {
  constructor({agentId, manifest, env, cwd, onEvent, onPermissionRequest, onUserInputRequest, onElicitationRequest, onExit}) {
    this.agentId = agentId;
    this.manifest = manifest;
    this.env = env;
    this.cwd = cwd;
    this.onEvent = onEvent;
    this.onPermissionRequest = onPermissionRequest;
    this.onUserInputRequest = onUserInputRequest;
    this.onElicitationRequest = onElicitationRequest;
    this.onExit = onExit;
    this.child = null;
    this.seq = 0;
    this.pending = new Map();
    this.initialized = false;
    this.agentCapabilities = {};
    this.agentInfo = {};
    this.authMethods = [];
    this.terminals = new Map();
    this.termSeq = 0;
    this.closed = false;
    this.stderrTail = "";
    this.lastActivity = Date.now();
  }

  expandHome(raw) {
    if (typeof raw !== "string") return "";
    const home = process.env.HOME || "/home/agent";
    return raw.replaceAll("$HOME", home).replace(/^~(?=\/|$)/, home);
  }

  async start() {
    if (this.child && !this.closed) return;
    const launch = this.manifest.launch || {};
    const cmd = this.expandHome(launch.command || "");
    if (!cmd) throw new Error(`Agent ${this.agentId} 缺少启动命令`);
    const args = Array.isArray(launch.args) ? launch.args.map(a => this.expandHome(String(a))) : [];
    const child = spawn(cmd, args, {
      cwd: this.cwd || "/home/agent/workspace",
      env: this.env,
      detached: process.platform === "linux",
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.child = child;
    this.closed = false;
    this.lastActivity = Date.now();

    const rl = createInterface({input: child.stdout, crlfDelay: Infinity});
    rl.on("line", line => {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith("{")) return;
      let msg;
      try { msg = JSON.parse(trimmed); } catch { return; }
      this.handleMessage(msg);
    });

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", chunk => {
      this.stderrTail = (this.stderrTail + chunk).slice(-8192);
    });
    child.stdin.once("error", error => { this.failAll(error); this.close(); });

    child.once("error", err => {
      this.closed = true;
      this.child = null;
      this.failAll(new Error(`无法启动 ${this.manifest.name || this.agentId}：${err.message}`));
      this.onExit?.(null, null);
    });
    child.once("exit", (code, signal) => {
      this.closed = true;
      this.child = null;
      const tail = this.stderrTail.trim();
      this.failAll(new Error(tail ? `${this.manifest.name} 已退出（${code ?? signal}）：${tail.slice(-400)}` : `${this.manifest.name} 已退出（${code ?? signal}）`));
      if (this.onExit) this.onExit(code, signal);
    });

    const init = await this.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: {
        fs: {readTextFile: true, writeTextFile: true},
        terminal: true,
        elicitation: {form: {}, url: {}},
      },
      clientInfo: {name: "galiais_android", title: "Solbraid Agent Harness", version: "1.0.0"},
    }, 30_000);

    this.initialized = true;
    this.agentCapabilities = init?.agentCapabilities || {};
    this.agentInfo = init?.agentInfo || {};
    this.authMethods = Array.isArray(init?.authMethods) ? init.authMethods : [];
    return init;
  }

  write(obj) {
    if (!this.child || this.closed || !this.child.stdin.writable) {
      throw new Error(`${this.manifest.name || this.agentId} 进程未运行`);
    }
    this.lastActivity = Date.now();
    this.child.stdin.write(JSON.stringify(obj) + "\n");
  }

  request(method, params, timeoutMs = 120_000) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`ACP 请求超时：${method}`));
      }, timeoutMs);
      this.pending.set(id, {resolve, reject, timer});
      try {
        this.write({jsonrpc: "2.0", id, method, ...(params !== undefined ? {params} : {})});
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(err);
      }
    });
  }

  notify(method, params) {
    this.write({jsonrpc: "2.0", method, ...(params !== undefined ? {params} : {})});
  }

  respond(id, result) {
    this.write({jsonrpc: "2.0", id, result: result ?? {}});
  }

  respondError(id, code, message) {
    this.write({jsonrpc: "2.0", id, error: {code, message}});
  }

  failAll(err) {
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(err);
    }
    this.pending.clear();
  }

  handleMessage(msg) {
    this.lastActivity = Date.now();
    if (Object.hasOwn(msg, "id") && !Object.hasOwn(msg, "method")) {
      const entry = this.pending.get(msg.id);
      if (!entry) return;
      this.pending.delete(msg.id);
      clearTimeout(entry.timer);
      if (msg.error) entry.reject(new Error(msg.error.message || `ACP error ${msg.error.code}`));
      else entry.resolve(msg.result);
      return;
    }
    if (Object.hasOwn(msg, "id") && typeof msg.method === "string") {
      this.handleAgentRequest(msg).catch(err => {
        try { this.respondError(msg.id, -32000, err.message || "Client request failed"); } catch {}
      });
      return;
    }
    if (typeof msg.method === "string") {
      this.handleNotification(msg.method, msg.params || {});
    }
  }

  async handleAgentRequest(msg) {
    const {id, method, params = {}} = msg;
    if (method === "session/request_permission") {
      const decision = await this.onPermissionRequest(this.agentId, params);
      if (decision?.outcome?.outcome === "selected" && !params.options?.some(option => option.optionId === decision.outcome.optionId)) {
        throw new Error("Selected permission option is not offered by the agent");
      }
      this.respond(id, decision);
      return;
    }
    if (["session/request_user_input", "session/ask_question", "ask_question", "user/ask_question", "tools/ask_question"].includes(method)) {
      if (!this.onUserInputRequest) {
        this.respondError(id, -32601, `User input handler unavailable for ${method}`);
        return;
      }
      const decision = await this.onUserInputRequest(this.agentId, params);
      this.respond(id, decision ?? {cancelled: true, answers: {}});
      return;
    }
    if (["elicitation/create", "elicitation/request", "session/elicitation", "session/request_elicitation", "mcp/elicitation", "mcpServer/elicitation/request"].includes(method)) {
      if (!this.onElicitationRequest) {
        this.respondError(id, -32601, `Elicitation handler unavailable for ${method}`);
        return;
      }
      const decision = await this.onElicitationRequest(this.agentId, params);
      this.respond(id, decision ?? {action: "decline"});
      return;
    }
    if (method === "fs/read_text_file") {
      const target = await this.safePath(params.path);
      const raw = await fs.readFile(target, "utf8");
      const lines = raw.split("\n");
      const start = Math.max(0, (Number(params.line) || 1) - 1);
      const limit = Number(params.limit) || lines.length;
      this.respond(id, {content: lines.slice(start, start + limit).join("\n")});
      return;
    }
    if (method === "fs/write_text_file") {
      const target = await this.safePath(params.path, true);
      if (typeof params.content !== "string" || Buffer.byteLength(params.content) > 4 * 1024 * 1024) {
        throw new Error("File content must be text within 4 MB");
      }
      await fs.mkdir(path.dirname(target), {recursive: true});
      const checkedTarget = await this.safePath(target, true);
      const file = await fs.open(checkedTarget, constants.O_WRONLY | constants.O_CREAT | constants.O_TRUNC | constants.O_NOFOLLOW, 0o600);
      try { await file.writeFile(params.content, "utf8"); }
      finally { await file.close(); }
      this.respond(id, {});
      return;
    }
    if (method === "terminal/create") {
      const term = await this.createTerminal(params);
      this.respond(id, {terminalId: term.id});
      return;
    }
    if (method === "terminal/output") {
      const term = this.terminals.get(String(params.terminalId || ""));
      if (!term) throw new Error("Terminal not found");
      this.respond(id, {
        output: term.output,
        truncated: term.truncated,
        ...(term.exited ? {exitStatus: {exitCode: term.exitCode, signal: term.signal}} : {}),
      });
      return;
    }
    if (method === "terminal/wait_for_exit") {
      const term = this.terminals.get(String(params.terminalId || ""));
      if (!term) throw new Error("Terminal not found");
      await term.exitPromise;
      this.respond(id, {exitCode: term.exitCode, signal: term.signal});
      return;
    }
    if (method === "terminal/kill") {
      const term = this.terminals.get(String(params.terminalId || ""));
      if (term && !term.exited) { try { term.child.kill("SIGTERM"); } catch {} }
      this.respond(id, {});
      return;
    }
    if (method === "terminal/release") {
      const tid = String(params.terminalId || "");
      const term = this.terminals.get(tid);
      if (term) {
        this.terminals.delete(tid);
        if (!term.exited) { try { term.child.kill("SIGTERM"); } catch {} }
      }
      this.respond(id, {});
      return;
    }
    this.respondError(id, -32601, `Unsupported ACP client method: ${method}`);
  }

  async createTerminal(params = {}) {
    if (this.terminals.size >= 8) throw new Error("Too many active ACP terminals (max 8)");
    const command = typeof params.command === "string" ? params.command.trim() : "";
    if (!command) throw new Error("Invalid terminal command");
    const args = Array.isArray(params.args) ? params.args.map(String) : [];
    const cwd = params.cwd ? await this.safePath(params.cwd) : (this.cwd || "/home/agent/workspace");
    const limit = Math.min(Math.max(Number(params.outputByteLimit) || 262_144, 4096), 1024 * 1024);
    const extraEnv = {};
    if (Array.isArray(params.env)) {
      for (const e of params.env) {
        if (e && typeof e.name === "string" && typeof e.value === "string") extraEnv[e.name] = e.value;
      }
    }
    const id = `term-${++this.termSeq}-${Date.now()}`;
    const child = spawn(command, args, {
      cwd,
      env: {...this.env, ...extraEnv},
      stdio: ["ignore", "pipe", "pipe"],
    });
    const entry = {id, child, output: "", truncated: false, exited: false, exitCode: null, signal: null, exitPromise: null};
    const append = chunk => {
      const str = chunk.toString("utf8");
      entry.output += str;
      if (Buffer.byteLength(entry.output, "utf8") > limit) {
        entry.output = entry.output.slice(-Math.floor(limit * 0.85));
        entry.truncated = true;
      }
    };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    entry.exitPromise = new Promise(resolve => {
      child.once("error", err => {
        entry.output += `\n${err.message}`;
        entry.exited = true;
        entry.exitCode = 1;
        resolve();
      });
      child.once("exit", (code, sig) => {
        entry.exited = true;
        entry.exitCode = typeof code === "number" ? code : null;
        entry.signal = sig || null;
        resolve();
      });
    });
    this.terminals.set(id, entry);
    return entry;
  }

  async safePath(rawPath, allowMissing = false) {
    if (typeof rawPath !== "string" || !rawPath.length) throw new Error("Invalid path");
    const home = "/home/agent";
    const resolved = path.resolve(this.cwd || "/home/agent/workspace", rawPath);
    if (!resolved.startsWith(home + "/") && resolved !== home) {
      throw new Error("Path outside guest agent home is denied");
    }
    let ancestor = resolved;
    while (true) {
      try {
        const real = await fs.realpath(ancestor);
        if (!real.startsWith(home + "/") && real !== home) throw new Error("Symlink outside guest agent home is denied");
        return path.join(real, path.relative(ancestor, resolved));
      } catch (error) {
        if (!allowMissing || error.code !== "ENOENT") throw error;
        // 悬空符号链接不能按“尚未创建的普通路径”处理。
        const entry = await fs.lstat(ancestor).catch(cause => {
          if (cause.code !== "ENOENT") throw cause;
          return null;
        });
        if (entry?.isSymbolicLink()) throw new Error("Dangling symlink is denied");
        if (ancestor === home) throw error;
        ancestor = path.dirname(ancestor);
      }
    }
  }

  handleNotification(method, params) {
    if (method === "session/update") {
      const sessionId = params.sessionId || "";
      const update = params.update || params;
      this.onEvent({agentId: this.agentId, sessionId, kind: "acp-update", update});
    } else {
      this.onEvent({agentId: this.agentId, sessionId: params.sessionId || "", kind: "raw", method, params});
    }
  }

  async authenticate(methodId) {
    await this.start();
    return await this.request("authenticate", {methodId}, 30_000);
  }

  async newSession(cwd, mcpServers = []) {
    await this.start();
    return await this.request("session/new", {cwd: cwd || this.cwd, mcpServers}, 60_000);
  }

  async resumeSession(sessionId, cwd, mcpServers = []) {
    await this.start();
    if (this.agentCapabilities?.sessionCapabilities?.resume) {
      try {
        return await this.request("session/resume", {sessionId, cwd: cwd || this.cwd, mcpServers}, 60_000);
      } catch {
        // 回退到 session/load
      }
    }
    return await this.loadSession(sessionId, cwd, mcpServers);
  }

  async loadSession(sessionId, cwd, mcpServers = []) {
    await this.start();
    if (this.agentCapabilities?.loadSession) {
      return await this.request("session/load", {sessionId, cwd: cwd || this.cwd, mcpServers}, 60_000);
    }
    return null;
  }

  async prompt(sessionId, promptBlocks) {
    await this.start();
    return await this.request("session/prompt", {sessionId, prompt: promptBlocks}, 1_500_000);
  }

  async cancel(sessionId) {
    if (!this.child || this.closed) return;
    try { this.notify("session/cancel", {sessionId}); } catch {}
  }

  async setMode(sessionId, modeId) {
    await this.start();
    return await this.request("session/set_mode", {sessionId, modeId}, 30_000);
  }

  async setModel(sessionId, modelId) {
    await this.start();
    return await this.request("session/set_model", {sessionId, modelId}, 30_000);
  }

  async setConfigOption(sessionId, configId, value) {
    await this.start();
    return await this.request("session/set_config_option", {sessionId, configId, value}, 30_000);
  }

  close() {
    this.closed = true;
    for (const term of this.terminals.values()) {
      if (!term.exited) { try { term.child.kill("SIGTERM"); } catch {} }
    }
    this.terminals.clear();
    const c = this.child;
    this.child = null;
    if (c) {
      const signal = value => {
        if (c.exitCode !== null || c.signalCode !== null) return;
        try {
          if (process.platform === "linux" && c.pid) process.kill(-c.pid, value);
          else c.kill(value);
        } catch (error) { if (error.code !== "ESRCH") this.failAll(error); }
      };
      signal("SIGTERM");
      setTimeout(() => signal("SIGKILL"), 2500).unref();
    }
    this.failAll(new Error("Agent 已停止"));
  }
}
