import fs from "node:fs/promises";
import path from "node:path";
import {spawn} from "node:child_process";
import {createInterface} from "node:readline";
import {
  computeUnifiedDiff,
  safeReadTextFile,
  atomicWriteTextFile,
  safeEditFile,
  safeCreateFile,
  streamSearchFile,
  captureFileCheckpoint,
  listFileCheckpoints,
  revertFileCheckpoint,
  pruneCheckpointRoot,
  defaultCheckpointRoot,
} from "./safe-file-io.mjs";

export {
  computeUnifiedDiff,
  safeReadTextFile,
  atomicWriteTextFile,
  safeEditFile,
  safeCreateFile,
  streamSearchFile,
  captureFileCheckpoint,
  listFileCheckpoints,
  revertFileCheckpoint,
  pruneCheckpointRoot,
};

/**
 * ACP 元工具归一化与抗断网重试辅助函数（供 agent-hub 与自动化测试共用）。
 */
const ACP_META_TOOLS = new Set([
  "update_steps",
  "update_focus",
  "task_boundary",
  "update_plan",
  "todowrite",
  "todo_write",
  "set_plan",
]);

export function normalizeAcpMetaToolName(title) {
  return String(title || "")
    .trim()
    .toLowerCase()
    .replace(/[-\s]+/g, "_")
    .replace(/^(?:run|call|tool)_+/, "");
}

export function isAcpMetaToolName(title) {
  return ACP_META_TOOLS.has(normalizeAcpMetaToolName(title));
}

export class AcpStreamStallError extends Error {
  constructor(stallTimeoutMs) {
    const sec = Math.max(1, Math.round((Number(stallTimeoutMs) || 120_000) / 1000));
    super(`模型响应超过 ${sec} 秒无输出，连接可能已中断`);
    this.name = "AcpStreamStallError";
    this.code = "ACP_STREAM_STALL";
    this.retryable = true;
  }
}

export function isSoftNetworkReply(text) {
  const clean = String(text || "").trim();
  if (!clean || clean.length > 420) return false;
  if (/there\s+was\s+a\s+network\s+issue\s+connecting\s+to\s+the\s+server/i.test(clean)) return true;
  if (/(?:⚠|network|connection|server|unavailable|timeout|stream|eof|overloaded|rate\s*limit|temporarily)/i.test(clean) &&
      /please\s+try\s+again|try\s+again\s+later/i.test(clean)) {
    return true;
  }
  if (/^(?:⚠\s*)?(?:network\s+(?:error|issue)|connection\s+(?:reset|aborted|refused|closed|lost|error)|stream\s+reading\s+error|unexpected\s+eof|service\s+unavailable|bad\s+gateway|gateway\s+timeout)/i.test(clean)) {
    return true;
  }
  return false;
}

export function isRetryableAcpError(errorOrMessage) {
  if (!errorOrMessage) return false;
  if (typeof errorOrMessage === "object") {
    if (errorOrMessage.code === "ACP_STREAM_STALL" || errorOrMessage.code === "ACP_INTERNAL_RETRY_LOOP") return true;
    if (errorOrMessage.retryable === true) return true;
    if (errorOrMessage.retryable === false) return false;
  }
  const msg = String(typeof errorOrMessage === "string" ? errorOrMessage : (errorOrMessage.message || errorOrMessage)).trim();
  if (!msg) return false;
  if (/not\s+logged\s+in|unauthenticated|unauthorized|\b401\b|\b403\b|invalid\s+api\s+key|用户中断|cancelled|canceled|会话已停止|会话已删除|invalid\s+argument|context\s+length|token\s+limit|prompt\s+is\s+too\s+long/i.test(msg)) {
    return false;
  }
  return /ACP_STREAM_STALL|ECONNRESET|ECONNABORTED|ECONNREFUSED|ETIMEDOUT|EPIPE|EAI_AGAIN|ENOTFOUND|ENETUNREACH|EHOSTUNREACH|unexpected\s+eof|\beof\b|socket\s+hang\s+up|software\s+caused\s+connection\s+abort|write:\s*operation\s+not\s+permitted|i\/o\s+timeout|context\s+deadline\s+exceeded|deadline\s+exceeded|broken\s+pipe|ACP\s*请求超时|已退出|进程未运行|stream\s+reading\s+error|network\s+issue|temporarily\s+unavailable|overloaded|resource\s+exhausted|\b429\b|\b500\b|\b502\b|\b503\b|\b504\b|bad\s+gateway|service\s+unavailable|gateway\s+timeout|响应超过\s*\d+\s*秒无输出|连接可能已中断/i.test(msg);
}

export function formatAcpNetworkReason(raw) {
  const s = String(raw || "").trim();
  if (!s) return "网络波动";
  if (/operation\s+not\s+permitted/i.test(s)) return "后台网络受限";
  if (/software\s+caused\s+connection\s+abort|ECONNABORTED/i.test(s)) return "网络连接被系统中断";
  if (/unexpected\s+eof|\beof\b|stream\s+reading\s+error/i.test(s)) return "流式连接意外断开";
  if (/ECONNRESET|socket\s+hang\s+up|broken\s+pipe/i.test(s)) return "远端重置连接";
  if (/i\/o\s+timeout|deadline\s+exceeded|ETIMEDOUT|超时/i.test(s)) return "连接超时";
  if (/ENOTFOUND|EAI_AGAIN|lookup\s+/i.test(s)) return "DNS 解析失败";
  if (/ACP_STREAM_STALL|无输出/i.test(s)) return "响应长时间无输出";
  if (/\b429\b|rate\s*limit|resource\s+exhausted|overloaded/i.test(s)) return "服务端限流或繁忙";
  if (/\b50[0234]\b|unavailable|bad\s+gateway/i.test(s)) return "服务端暂时不可用";
  return "网络波动";
}

const AGY_INTERNAL_RETRY_RE = /run\.go:\d+\]\s*Run:\s*attempt\s+(\d+)\s+failed\s*\((.+?)\),\s*retrying\s+in\s+([0-9.]+[a-z]+)/i;

export function parseAgyInternalRetryLine(line) {
  const m = AGY_INTERNAL_RETRY_RE.exec(String(line || ""));
  if (!m) return null;
  const attempt = Number(m[1]) || 1;
  const reason = m[2].trim();
  const retryIn = m[3].trim();
  const isSocketPermissionError = /operation\s+not\s+permitted|software\s+caused\s+connection\s+abort/i.test(reason);
  return {attempt, reason, retryIn, isSocketPermissionError};
}

export function computeRetryDelayMs(attemptIndex, baseDelays = [2000, 5000, 10000], jitterMs = 600) {
  const idx = Math.max(0, Math.min(attemptIndex, baseDelays.length - 1));
  const base = Number(baseDelays[idx]) || 2000;
  const jitter = jitterMs > 0 ? Math.floor(Math.random() * jitterMs) : 0;
  return base + jitter;
}

export function filterDuplicateAcpMcpServers(managedServers, globalConfigNames) {
  if (!Array.isArray(managedServers) || managedServers.length === 0) return [];
  const skip = globalConfigNames instanceof Set
    ? globalConfigNames
    : new Set(Array.isArray(globalConfigNames) ? globalConfigNames : []);
  if (skip.size === 0) return managedServers;
  return managedServers.filter(srv => {
    const name = String(srv?.name || "").trim();
    return !name || !skip.has(name);
  });
}

function distributeIntegerProportionally(total, weights, caps = null) {
  const n = weights.length;
  const result = new Array(n).fill(0);
  let remaining = Math.max(0, Math.round(Number(total || 0)));
  if (remaining <= 0 || n === 0) return result;
  const maxCap = caps ? caps.map(c => Math.max(0, Math.round(Number(c || 0)))) : null;
  // 多轮按权重与最大余数法分配，确保受容量上限约束时仍 100% 精确守恒（Σresult == min(total, Σcaps)）
  for (let pass = 0; pass < 4 && remaining > 0; pass++) {
    let wSum = 0;
    const effWeights = new Array(n).fill(0);
    for (let i = 0; i < n; i++) {
      const avail = maxCap ? Math.max(0, maxCap[i] - result[i]) : Infinity;
      if (avail <= 0) continue;
      const w = pass === 0 ? Math.max(0, Number(weights[i] || 0)) : (avail === Infinity ? 1 : avail);
      effWeights[i] = w;
      wSum += w;
    }
    if (wSum <= 0) {
      for (let i = 0; i < n; i++) {
        const avail = maxCap ? Math.max(0, maxCap[i] - result[i]) : Infinity;
        if (avail > 0) { effWeights[i] = 1; wSum += 1; }
      }
    }
    if (wSum <= 0) break;
    const fracs = [];
    let allocated = 0;
    for (let i = 0; i < n; i++) {
      if (effWeights[i] <= 0) continue;
      const avail = maxCap ? Math.max(0, maxCap[i] - result[i]) : Infinity;
      const exact = (remaining * effWeights[i]) / wSum;
      const floorVal = Math.min(avail, Math.floor(exact));
      result[i] += floorVal;
      allocated += floorVal;
      if (floorVal < avail) fracs.push({i, frac: exact - floorVal, avail: avail - floorVal});
    }
    remaining -= allocated;
    if (remaining > 0 && fracs.length > 0) {
      fracs.sort((a, b) => b.frac - a.frac);
      for (const item of fracs) {
        if (remaining <= 0) break;
        if (item.avail > 0) {
          result[item.i] += 1;
          remaining -= 1;
        }
      }
    }
  }
  return result;
}

/**
 * 将单轮内观测到的多次 LLM 步骤（来自逐次 usage_update）按官方精确汇总 exactUsage 守恒拆分为逐条请求账本项，
 * 保证每条记录都有真实耗时/TTFT/上下文峰值，且所有步骤的 in/out/cacheRead/cacheWrite/reasoning 总和精确守恒。
 */
export function buildPerStepUsageRecords({
  steps = [],
  inTok = 0,
  outTok = 0,
  cacheRead = 0,
  cacheWrite = 0,
  reasoning = 0,
  usageStart = 0,
  turnStartMs = 0,
  doneAt = Date.now(),
  apiDurationMs = 0,
  ttftMs = 0,
  genMs = 0,
  streamed = false,
  hasExactUsage = false,
  contextUsed = 0,
  contextWindow = 0,
} = {}) {
  const validSteps = Array.isArray(steps) ? steps.filter(s => s && Number(s.used || 0) > 0) : [];
  if (validSteps.length <= 1) {
    const single = validSteps[0] || null;
    return [{
      t: single?.endedAt || doneAt,
      in: Math.max(0, Math.round(Number(inTok || 0))),
      out: Math.max(0, Math.round(Number(outTok || 0))),
      cacheRead: Math.max(0, Math.round(Number(cacheRead || 0))),
      cacheWrite: Math.max(0, Math.round(Number(cacheWrite || 0))),
      reasoning: Math.max(0, Math.round(Number(reasoning || 0))),
      ms: Math.max(1, Math.round(Number(single?.apiMs || apiDurationMs || 1))),
      ttftMs: Math.max(0, Math.round(Number(single ? single.ttftMs : ttftMs || 0))),
      genMs: Math.max(0, Math.round(Number(single ? single.genMs : genMs || 0))),
      streamed: single ? Boolean(single.streamed) : Boolean(streamed),
      exact: Boolean(hasExactUsage),
      contextUsed: Math.max(0, Math.round(Number(single?.used || contextUsed || (inTok + outTok)))),
      contextWindow: Math.max(0, Math.round(Number(contextWindow || 0))),
    }];
  }
  const n = validSteps.length;
  const totalIn = Math.max(0, Math.round(Number(inTok || 0)));
  const totalOut = Math.max(0, Math.round(Number(outTok || 0)));
  const totalCr = Math.min(totalIn, Math.max(0, Math.round(Number(cacheRead || 0))));
  const totalCw = Math.min(Math.max(0, totalIn - totalCr), Math.max(0, Math.round(Number(cacheWrite || 0))));
  const totalReason = Math.min(totalOut, Math.max(0, Math.round(Number(reasoning || 0))));

  const outWeights = validSteps.map(s => Math.max(1, Number(s.outChars || 0) || Math.round(Number(s.used || 0) * 0.02) || 1));
  const outs = distributeIntegerProportionally(totalOut, outWeights);

  const reasonWeights = validSteps.map(s => Math.max(0, Number(s.thoughtChars || 0)));
  const reasons = distributeIntegerProportionally(totalReason, reasonWeights.some(w => w > 0) ? reasonWeights : outWeights, outs);

  const rawInWeights = validSteps.map((s, idx) => Math.max(1, Math.round(Number(s.used || 0)) - outs[idx]));
  const ins = distributeIntegerProportionally(totalIn, rawInWeights);

  // 缓存读优先按“上一请求上下文”容量分配（新会话首轮通常为 0，续会话首轮参考 usageStart）
  const crPriorityWeights = validSteps.map((s, idx) => {
    const prevCtx = idx === 0 ? Math.max(0, Number(usageStart || 0)) : Math.max(0, Number(validSteps[idx - 1].used || 0));
    return Math.min(ins[idx], prevCtx);
  });
  const cacheReads = distributeIntegerProportionally(totalCr, crPriorityWeights, ins);
  const uncachedCaps = ins.map((v, idx) => Math.max(0, v - cacheReads[idx]));
  const cacheWrites = distributeIntegerProportionally(totalCw, uncachedCaps, uncachedCaps);

  return validSteps.map((s, idx) => {
    const stepMs = Math.max(1, Math.round(Number(s.apiMs || Math.max(1, Math.round(apiDurationMs / n)))));
    const stepTtft = s.streamed ? Math.min(stepMs, Math.max(0, Math.round(Number(s.ttftMs || 0)))) : 0;
    const stepGen = s.streamed ? Math.min(Math.max(0, stepMs - stepTtft), Math.max(0, Math.round(Number(s.genMs || 0)))) : 0;
    return {
      t: Number(s.endedAt) || (turnStartMs + Math.round(((idx + 1) / n) * Math.max(1, doneAt - turnStartMs))),
      in: ins[idx],
      out: outs[idx],
      cacheRead: cacheReads[idx],
      cacheWrite: cacheWrites[idx],
      reasoning: reasons[idx],
      ms: stepMs,
      ttftMs: stepTtft,
      genMs: stepGen,
      streamed: Boolean(s.streamed),
      exact: Boolean(hasExactUsage),
      contextUsed: Math.max(0, Math.round(Number(s.used || (ins[idx] + outs[idx])))),
      contextWindow: Math.max(0, Math.round(Number(s.contextWindow || contextWindow || 0))),
    };
  });
}

/**
 * 标准 ACP（Agent Client Protocol，JSON-RPC 2.0 over stdio）客户端驱动。
 * 兼容 Antigravity CLI (`agy acp`)、Gemini CLI (`gemini --acp`)、OpenCode (`opencode acp`)、Goose 及所有 ACP Agent。
 */
export class AcpDriver {
  constructor({agentId, manifest, env, cwd, sessionId = "", checkpointRoot = "", onEvent, onPermissionRequest, onUserInputRequest, onElicitationRequest, onExit}) {
    this.agentId = agentId;
    this.manifest = manifest;
    this.env = env;
    this.cwd = cwd;
    this._sessionId = sessionId;
    this.checkpointRoot = checkpointRoot || defaultCheckpointRoot();
    this.onEvent = onEvent;
    this.onPermissionRequest = onPermissionRequest;
    this.onUserInputRequest = onUserInputRequest;
    this.onElicitationRequest = onElicitationRequest;
    this.onExit = onExit;
    this.child = null;
    this.seq = 0;
    this.pending = new Map();
    this.promptWatchers = new Map();
    this.activeClientRequests = 0;
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

  clearPromptWatcher(id) {
    const watcher = this.promptWatchers.get(id);
    if (!watcher) return;
    if (watcher.stallTimer) clearInterval(watcher.stallTimer);
    this.promptWatchers.delete(id);
  }

  touchPromptWatchers(sessionId = "", update = null) {
    const now = Date.now();
    for (const watcher of this.promptWatchers.values()) {
      if (sessionId && watcher.sessionId && watcher.sessionId !== sessionId) continue;
      watcher.lastProgressAt = now;
      if (update && typeof update === "object") {
        const su = update.sessionUpdate;
        if (su === "tool_call" && update.toolCallId && !isAcpMetaToolName(update.title)) {
          watcher.activeTools.add(String(update.toolCallId));
        } else if (su === "tool_call_update") {
          const st = String(update.status || "").toLowerCase();
          if (st === "completed" || st === "failed" || st === "cancelled" || st === "canceled") {
            if (update.toolCallId) watcher.activeTools.delete(String(update.toolCallId));
            else watcher.activeTools.clear();
          }
        }
      }
    }
  }

  failAll(err) {
    for (const id of [...this.promptWatchers.keys()]) this.clearPromptWatcher(id);
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
      this.clearPromptWatcher(msg.id);
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
      this.activeClientRequests++;
      try {
        const decision = await this.onPermissionRequest(this.agentId, params);
        if (decision?.outcome?.outcome === "selected" && !params.options?.some(option => option.optionId === decision.outcome.optionId)) {
          throw new Error("Selected permission option is not offered by the agent");
        }
        this.respond(id, decision);
      } finally {
        this.activeClientRequests = Math.max(0, this.activeClientRequests - 1);
        this.touchPromptWatchers(params.sessionId || "");
      }
      return;
    }
    if (["session/request_user_input", "session/ask_question", "ask_question", "user/ask_question", "tools/ask_question"].includes(method)) {
      if (!this.onUserInputRequest) {
        this.respondError(id, -32601, `User input handler unavailable for ${method}`);
        return;
      }
      this.activeClientRequests++;
      try {
        const decision = await this.onUserInputRequest(this.agentId, params);
        this.respond(id, decision ?? {cancelled: true, answers: {}});
      } finally {
        this.activeClientRequests = Math.max(0, this.activeClientRequests - 1);
        this.touchPromptWatchers(params.sessionId || "");
      }
      return;
    }
    if (["elicitation/create", "elicitation/request", "session/elicitation", "session/request_elicitation", "mcp/elicitation", "mcpServer/elicitation/request"].includes(method)) {
      if (!this.onElicitationRequest) {
        this.respondError(id, -32601, `Elicitation handler unavailable for ${method}`);
        return;
      }
      this.activeClientRequests++;
      try {
        const decision = await this.onElicitationRequest(this.agentId, params);
        this.respond(id, decision ?? {action: "decline"});
      } finally {
        this.activeClientRequests = Math.max(0, this.activeClientRequests - 1);
        this.touchPromptWatchers(params.sessionId || "");
      }
      return;
    }
    if (method === "fs/read_text_file") {
      const target = await this.safePath(params.path);
      const res = await safeReadTextFile(target, {
        line: params.line,
        limit: params.limit,
      });
      this.respond(id, {content: res.content});
      return;
    }
    if (method === "fs/write_text_file") {
      const target = await this.safePath(params.path, true);
      if (typeof params.content !== "string" || Buffer.byteLength(params.content, "utf8") > 4 * 1024 * 1024) {
        throw new Error("File content must be text within 4 MB");
      }
      await fs.mkdir(path.dirname(target), {recursive: true});
      const checkedTarget = await this.safePath(target, true);
      await atomicWriteTextFile(checkedTarget, params.content, {
        sessionId: params.sessionId || this._sessionId || "default",
        checkpointRoot: this.checkpointRoot,
      });
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
      this.touchPromptWatchers(sessionId, update);
      this.onEvent({agentId: this.agentId, sessionId, kind: "acp-update", update});
    } else {
      this.touchPromptWatchers(params.sessionId || "");
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

  async prompt(sessionId, promptBlocks, options = {}) {
    await this.start();
    const timeoutMs = Number(options.timeoutMs) > 0 ? Number(options.timeoutMs) : 1_500_000;
    const stallTimeoutMs = options.stallTimeoutMs === 0
      ? 0
      : (Number(options.stallTimeoutMs) > 0 ? Number(options.stallTimeoutMs) : 120_000);
    const id = ++this.seq;
    return await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.clearPromptWatcher(id);
        this.pending.delete(id);
        reject(new Error("ACP 请求超时：session/prompt"));
      }, timeoutMs);
      this.pending.set(id, {resolve, reject, timer});
      if (stallTimeoutMs > 0) {
        const watcher = {
          sessionId: String(sessionId || ""),
          lastProgressAt: Date.now(),
          activeTools: new Set(),
          stallTimer: null,
        };
        const checkIntervalMs = Math.min(5_000, Math.max(40, Math.floor(stallTimeoutMs / 4)));
        watcher.stallTimer = setInterval(() => {
          if (!this.pending.has(id)) {
            this.clearPromptWatcher(id);
            return;
          }
          // 等待用户审批/提问或正在执行工具（如编译/命令）时不判定为模型流卡死
          if (this.activeClientRequests > 0 || watcher.activeTools.size > 0) {
            watcher.lastProgressAt = Date.now();
            return;
          }
          if (Date.now() - watcher.lastProgressAt >= stallTimeoutMs) {
            this.clearPromptWatcher(id);
            const entry = this.pending.get(id);
            if (!entry) return;
            this.pending.delete(id);
            clearTimeout(entry.timer);
            try { this.notify("session/cancel", {sessionId}); } catch {}
            entry.reject(new AcpStreamStallError(stallTimeoutMs));
          }
        }, checkIntervalMs);
        watcher.stallTimer.unref?.();
        this.promptWatchers.set(id, watcher);
      }
      try {
        this.write({jsonrpc: "2.0", id, method: "session/prompt", params: {sessionId, prompt: promptBlocks}});
      } catch (err) {
        clearTimeout(timer);
        this.clearPromptWatcher(id);
        this.pending.delete(id);
        reject(err);
      }
    });
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
