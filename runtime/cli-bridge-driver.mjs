import {spawn} from "node:child_process";
import {createInterface} from "node:readline";

const ANSI_RE = /\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])/g;

function stripAnsi(s) {
  return String(s || "").replace(ANSI_RE, "");
}

function expandHome(raw) {
  if (typeof raw !== "string") return "";
  const home = process.env.HOME || "/home/agent";
  return raw.replaceAll("$HOME", home).replace(/^~(?=\/|$)/, home);
}

function signalProcess(child, signal) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  try {
    if (process.platform === "linux" && child.pid) process.kill(-child.pid, signal);
    else child.kill(signal);
  } catch (error) { if (error.code !== "ESRCH") throw error; }
}

/**
 * 通用非 ACP CLI 桥接驱动：支持 `ndjson`（如 `--output-format stream-json`）与 `exec-stream`（任意命令行 Agent）。
 * 将子进程流式输出转译为统一的 ACP 风格 `acp-update` 事件，使任意 CLI 都能直接渲染在 GALIAIS 对话时间线中。
 */
export class CliBridgeDriver {
  constructor({agentId, manifest, env, onEvent}) {
    this.agentId = agentId;
    this.manifest = manifest;
    this.env = env;
    this.onEvent = onEvent;
    this.activeProcs = new Map();
    this.lastActivity = Date.now();
    this.closed = false;
  }

  buildCommand(text, {cwd, model, sessionId, remoteSessionId}) {
    const launch = this.manifest.launch || {};
    const cmd = expandHome(launch.command || "");
    if (!cmd) throw new Error(`Agent ${this.agentId} 缺少执行命令`);
    const rawArgs = Array.isArray(launch.args) ? launch.args.map(String) : [];
    const usesPromptToken = rawArgs.some(a => a.includes("{{prompt}}"));
    const args = [];
    for (const token of rawArgs) {
      if (token.includes("{{model}}") && !model) continue;
      if (token.includes("{{sessionId}}") && !(remoteSessionId || sessionId)) continue;
      const expanded = expandHome(token)
        .replaceAll("{{prompt}}", text)
        .replaceAll("{{cwd}}", cwd || "/home/agent/workspace")
        .replaceAll("{{model}}", model || "")
        .replaceAll("{{sessionId}}", remoteSessionId || sessionId || "");
      args.push(expanded);
    }
    const mode = this.manifest.protocol || "exec-stream";
    if (!usesPromptToken && launch.promptFlag) {
      args.push(String(launch.promptFlag), text);
    }
    if (model && launch.modelFlag && !rawArgs.some(a => a.includes("{{model}}"))) {
      args.push(String(launch.modelFlag), model);
    }
    const writeStdin = !usesPromptToken && !launch.promptFlag;
    return {cmd, args, writeStdin, mode};
  }

  async prompt(sessionId, text, opts = {}) {
    if (this.closed || this.activeProcs.has(sessionId)) throw new Error("Agent 已停止或该会话仍有任务运行");
    this.lastActivity = Date.now();
    const {cmd, args, writeStdin, mode} = this.buildCommand(text, {...opts, sessionId});
    const cwd = opts.cwd || "/home/agent/workspace";
    const child = spawn(cmd, args, {cwd, env: this.env, detached: process.platform === "linux", stdio: ["pipe", "pipe", "pipe"]});
    this.activeProcs.set(sessionId, child);

    let fullText = "";
    let stderrBuf = "";
    let capturedRemoteId = opts.remoteSessionId || null;
    let protocolError = null;
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; void this.cancel(sessionId); }, 24 * 60 * 1000);
    let outputBytes = 0;
    child.stdout.on("data", chunk => {
      outputBytes += Buffer.byteLength(chunk);
      if (outputBytes > 16 * 1024 * 1024 && !protocolError) {
        protocolError = new Error("Agent 输出超过 16 MB，已停止任务");
        void this.cancel(sessionId);
      }
    });

    const emitChunk = (delta) => {
      if (!delta || protocolError) return;
      fullText += delta;
      this.onEvent({
        agentId: this.agentId,
        sessionId,
        kind: "acp-update",
        update: {sessionUpdate: "agent_message_chunk", content: {type: "text", text: delta}},
      });
    };

    const emitThought = (delta) => {
      if (!delta) return;
      this.onEvent({
        agentId: this.agentId,
        sessionId,
        kind: "acp-update",
        update: {sessionUpdate: "agent_thought_chunk", content: {type: "text", text: delta}},
      });
    };

    const emitTool = (toolCallId, title, status, rawInput, rawOutput) => {
      this.onEvent({
        agentId: this.agentId,
        sessionId,
        kind: "acp-update",
        update: {
          sessionUpdate: status === "in_progress" ? "tool_call" : "tool_call_update",
          toolCallId: String(toolCallId || `tool-${Date.now()}`),
          title: String(title || "工具调用"),
          status: status || "completed",
          rawInput,
          rawOutput,
        },
      });
    };

    const handleNdjsonLine = (line) => {
      const trimmed = line.trim();
      if (!trimmed) return;
      if (!trimmed.startsWith("{")) {
        emitChunk(stripAnsi(line) + "\n");
        return;
      }
      let obj;
      try { obj = JSON.parse(trimmed); } catch {
        emitChunk(stripAnsi(line) + "\n");
        return;
      }
      if (typeof obj.session_id === "string" && obj.session_id) capturedRemoteId = obj.session_id;
      else if (typeof obj.sessionId === "string" && obj.sessionId) capturedRemoteId = obj.sessionId;

      const t = String(obj.type || obj.event || "").toLowerCase();
      if (t === "assistant" && Array.isArray(obj.message?.content)) {
        for (const block of obj.message.content) {
          if (block.type === "text" && block.text) emitChunk(block.text);
          else if (block.type === "thinking" && block.thinking) emitThought(block.thinking);
          else if (block.type === "tool_use") emitTool(block.id, block.name, "in_progress", block.input, null);
        }
        return;
      }
      if (t === "content_block_delta" || t === "text_delta" || t === "message" || t === "delta") {
        const delta = obj.delta?.text ?? obj.delta?.thinking ?? obj.text ?? obj.content ?? "";
        if (obj.delta?.type === "thinking_delta" || obj.delta?.thinking) emitThought(String(delta));
        else if (typeof delta === "string") emitChunk(delta);
        return;
      }
      if (t === "thought" || t === "reasoning" || t === "thinking") {
        emitThought(String(obj.text ?? obj.delta ?? obj.content ?? ""));
        return;
      }
      if (t === "tool_use" || t === "tool_call") {
        emitTool(obj.id || obj.tool_id, obj.name || obj.tool_name, "in_progress", obj.input || obj.parameters, null);
        return;
      }
      if (t === "tool_result") {
        emitTool(obj.id || obj.tool_id || obj.tool_use_id, obj.name || "工具结果", obj.is_error ? "failed" : "completed", null, obj.output ?? obj.content ?? obj.result);
        return;
      }
      if (t === "result" || t === "done" || t === "final") {
        if (obj.is_error === true) protocolError = new Error(typeof obj.result === "string" ? obj.result : "Agent reported a failed result");
        if (!fullText && typeof obj.result === "string") emitChunk(obj.result);
        else if (!fullText && typeof obj.response === "string") emitChunk(obj.response);
        return;
      }
      if (t === "error") {
        const msg = obj.error?.message || obj.message || JSON.stringify(obj);
        protocolError = new Error(msg);
        stderrBuf += msg + "\n";
        return;
      }
      // 通用兜底字段抽取
      const generic = obj.text ?? obj.delta ?? obj.output ?? (typeof obj.content === "string" ? obj.content : null);
      if (typeof generic === "string" && generic) emitChunk(generic);
    };

    if (mode === "ndjson") {
      const rl = createInterface({input: child.stdout, crlfDelay: Infinity});
      rl.on("line", handleNdjsonLine);
    } else {
      child.stdout.setEncoding("utf8");
      child.stdout.on("data", chunk => {
        this.lastActivity = Date.now();
        emitChunk(stripAnsi(chunk));
      });
    }

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", chunk => {
      stderrBuf = (stderrBuf + stripAnsi(chunk)).slice(-8192);
    });
    child.stdin.on("error", error => {
      if (writeStdin) { protocolError = error; void this.cancel(sessionId); }
    });

    if (writeStdin) {
      child.stdin.write(text + "\n");
    }
    child.stdin.end();

    return await new Promise((resolve, reject) => {
      child.once("error", err => {
        clearTimeout(timer);
        this.activeProcs.delete(sessionId);
        reject(new Error(`启动 ${this.manifest.name || this.agentId} 失败：${err.message}`));
      });
      child.once("close", (code, signal) => {
        clearTimeout(timer);
        this.activeProcs.delete(sessionId);
        this.lastActivity = Date.now();
        if (timedOut || protocolError) { reject(protocolError || new Error("Agent 执行超时，已停止任务")); return; }
        if (signal === "SIGTERM" || signal === "SIGINT") {
          resolve({stopReason: "cancelled", text: fullText, remoteSessionId: capturedRemoteId});
          return;
        }
        if (code !== 0) {
          const errText = stderrBuf.trim() || `退出码 ${code ?? signal}`;
          this.onEvent({
            agentId: this.agentId,
            sessionId,
            kind: "notice",
            text: `进程非零退出（${code}）：${errText.slice(-300)}`,
            error: true,
          });
          reject(new Error(errText));
          return;
        }
        resolve({stopReason: "end_turn", text: fullText, remoteSessionId: capturedRemoteId});
      });
    });
  }

  async cancel(sessionId) {
    const child = this.activeProcs.get(sessionId);
    if (!child) return;
    signalProcess(child, "SIGTERM");
    setTimeout(() => signalProcess(child, "SIGKILL"), 2000).unref();
  }

  close() {
    this.closed = true;
    for (const id of this.activeProcs.keys()) void this.cancel(id);
  }
}
