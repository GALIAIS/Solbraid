import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import tls from "node:tls";
import net, {isIP} from "node:net";
import path from "node:path";
import {Readable} from "node:stream";
import {randomBytes, timingSafeEqual} from "node:crypto";
import {
  responsesToChatRequest, responsesToAnthropicRequest, responsesToGeminiRequest,
  anthropicToChatRequest, anthropicToResponsesRequest, anthropicToGeminiRequest,
  pipeChatToResponses, pipeAnthropicToResponses, pipeGeminiToResponses,
  pipeChatToAnthropic, pipeResponsesToAnthropic, pipeGeminiToAnthropic,
  chatToResponsesRequest, geminiToResponsesRequest, CompatibleResponseWriter, pipeResponsesToCompatible,
} from "./provider-translators.mjs";
import {recordUsageEvent} from "./usage-ledger.mjs";
import {readDownloadNetwork} from "./download-network.mjs";

if (typeof process.getuid === "function" && process.getuid() === 0) throw new Error("Provider gateway must not run as root");

const MAX_RPC = 8 * 1024 * 1024;
const MAX_HTTP = 32 * 1024 * 1024;
const PROTOCOLS = new Set(["openai_responses", "openai_chat", "anthropic_messages", "google_gemini"]);
const PLUGIN_PROTOCOL_RE = /^plugin:([a-z0-9][a-z0-9._-]{1,47})$/i;

function callPluginCtlBridge(action, params = {}) {
  const home = process.env.HOME || "/home/agent";
  const socketPath = process.env.SOLBRAID_PLUGIN_CTL_SOCKET || path.join(home, ".galiais", "run", "plugin-ctl.sock");
  let token = process.env.SOLBRAID_PLUGIN_CTL_TOKEN || "";
  if (!token) {
    try { token = fs.readFileSync(path.join(home, ".galiais", "run", "plugin-ctl.token"), "utf8").trim(); } catch {}
  }
  if (!token) return Promise.reject(new Error("插件控制通道未就绪，无法调用插件协议转换器"));
  return new Promise((resolve, reject) => {
    const conn = net.createConnection(socketPath);
    let buf = "";
    let settled = false;
    const finish = (err, val) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.destroy();
      if (err) reject(err); else resolve(val);
    };
    const timer = setTimeout(() => finish(new Error("插件协议转换调用超时")), 8_000);
    conn.setEncoding("utf8");
    conn.once("connect", () => conn.write(JSON.stringify({token, action, params}) + "\n"));
    conn.on("data", chunk => {
      buf += chunk;
      if (buf.length > 4 * 1024 * 1024) { finish(new Error("插件协议转换响应过大")); return; }
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      try {
        const reply = JSON.parse(buf.slice(0, nl));
        if (reply?.ok) finish(null, reply.data);
        else finish(new Error(reply?.error || "插件协议转换失败"));
      } catch { finish(new Error("插件协议转换返回格式无效")); }
    });
    conn.once("error", err => finish(err));
    conn.once("close", () => finish(new Error("插件控制通道连接已关闭")));
  });
}

async function runPluginProtocolUpstream(provider, target, ingressBody, route, signal) {
  const m = PLUGIN_PROTOCOL_RE.exec(String(target.protocol || ""));
  if (!m) throw new Error(`无效插件协议：${target.protocol}`);
  const translatorId = m[1].toLowerCase();
  let translated;
  try {
    translated = await callPluginCtlBridge("translateRequest", {
      translatorId,
      request: ingressBody,
      context: {baseUrl: target.baseUrl, route, model: ingressBody?.model || ""},
    });
  } catch (err) {
    const raw = err?.message || String(err);
    const wrapped = new Error(`插件协议「plugin:${translatorId}」不可用（所属插件可能已卸载、停用或处于安全模式）：${raw}`);
    wrapped.isFailover = true;
    wrapped.status = 503;
    throw wrapped;
  }
  const url = translated?.url
    ? validateUrl(translated.url, true)
    : joinUrl(target.baseUrl, translated?.path || "/chat/completions", provider.queryParams);
  const reqBody = translated?.body !== undefined ? translated.body : translated;
  const extraHeaders = translated?.headers && typeof translated.headers === "object" ? translated.headers : {};
  const upstream = await fetchWithKeyPool(provider, url, reqBody, extraHeaders, signal);
  return {translatorId, upstream};
}

function extractTranslatedPayload(out) {
  if (typeof out === "string") return {text: out, toolCalls: [], stopReason: "end_turn", usage: {inputTokens: 0, outputTokens: 0}};
  if (!out || typeof out !== "object") return {text: "", toolCalls: [], stopReason: "end_turn", usage: {inputTokens: 0, outputTokens: 0}};

  let text = "";
  const toolCalls = [];
  const pushTool = (id, name, rawArgs) => {
    const cleanName = String(name || "").trim();
    if (!cleanName) return;
    const callId = String(id || `call_${randomBytes(6).toString("hex")}`);
    let argsStr = "{}";
    let argsObj = {};
    if (typeof rawArgs === "string") {
      argsStr = rawArgs.trim() || "{}";
      try { const p = JSON.parse(argsStr); if (p && typeof p === "object") argsObj = p; } catch {}
    } else if (rawArgs && typeof rawArgs === "object") {
      argsObj = rawArgs;
      argsStr = JSON.stringify(rawArgs);
    }
    toolCalls.push({id: callId, name: cleanName, arguments: argsStr, input: argsObj});
  };

  if (typeof out.text === "string") text = out.text;
  else if (typeof out.output_text === "string") text = out.output_text;

  // OpenAI Chat Completions 风格
  const chatMsg = out.choices?.[0]?.message;
  if (chatMsg) {
    if (!text && typeof chatMsg.content === "string") text = chatMsg.content;
    if (Array.isArray(chatMsg.tool_calls)) {
      for (const tc of chatMsg.tool_calls) {
        pushTool(tc?.id, tc?.function?.name || tc?.name, tc?.function?.arguments ?? tc?.arguments ?? tc?.input);
      }
    }
  }
  // OpenAI Responses 风格（output 数组含 message / function_call）
  if (Array.isArray(out.output)) {
    for (const item of out.output) {
      if (item?.type === "function_call") {
        pushTool(item.call_id || item.id, item.name, item.arguments);
      } else if (item?.type === "message" && Array.isArray(item.content)) {
        const joined = item.content.filter(c => (c?.type === "output_text" || c?.type === "text") && typeof c.text === "string").map(c => c.text).join("");
        if (!text && joined) text = joined;
      }
    }
  }
  // Anthropic Messages 风格（content 数组含 text / tool_use）
  if (Array.isArray(out.content)) {
    const joined = out.content.filter(b => b?.type === "text" && typeof b.text === "string").map(b => b.text).join("");
    if (!text && joined) text = joined;
    for (const b of out.content) {
      if (b?.type === "tool_use") pushTool(b.id, b.name, b.input);
    }
  }
  // 顶层直接携带 tool_calls / toolCalls
  const directCalls = Array.isArray(out.tool_calls) ? out.tool_calls : (Array.isArray(out.toolCalls) ? out.toolCalls : []);
  if (toolCalls.length === 0 && directCalls.length > 0) {
    for (const tc of directCalls) {
      pushTool(tc?.id || tc?.call_id, tc?.function?.name || tc?.name, tc?.function?.arguments ?? tc?.arguments ?? tc?.input);
    }
  }

  const rawStop = String(out.stop_reason || out.finish_reason || out.choices?.[0]?.finish_reason || "").toLowerCase();
  const stopReason = toolCalls.length > 0
    ? "tool_use"
    : (rawStop === "max_tokens" || rawStop === "length") ? "max_tokens"
      : (rawStop === "stop_sequence") ? "stop_sequence"
        : "end_turn";

  const u = out.usage && typeof out.usage === "object" ? out.usage : {};
  const inputTokens = Math.max(0, Number(u.input_tokens ?? u.prompt_tokens ?? u.inputTokens ?? 0) || 0);
  const outputTokens = Math.max(0, Number(u.output_tokens ?? u.completion_tokens ?? u.outputTokens ?? 0) || 0);
  return {text, toolCalls, stopReason, usage: {inputTokens, outputTokens}};
}

function extractTranslatedText(out) {
  return extractTranslatedPayload(out).text;
}

function writeSynthesizedResponsesSse(res, model, translated) {
  const parsed = extractTranslatedPayload(translated);
  const respId = `resp_plug_${randomBytes(6).toString("hex")}`;
  const sendEv = (event, data) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  res.writeHead(200, {"Content-Type": "text/event-stream", "Cache-Control": "no-cache"});
  sendEv("response.created", {type: "response.created", response: {id: respId, status: "in_progress", model, output: []}});
  const finalOutput = [];
  let outIdx = 0;

  if (parsed.text || parsed.toolCalls.length === 0) {
    const msgId = `msg_plug_${randomBytes(6).toString("hex")}`;
    sendEv("response.output_item.added", {type: "response.output_item.added", output_index: outIdx, item: {id: msgId, type: "message", status: "in_progress", role: "assistant", content: []}});
    sendEv("response.content_part.added", {type: "response.content_part.added", item_id: msgId, output_index: outIdx, content_index: 0, part: {type: "output_text", text: "", annotations: []}});
    if (parsed.text) sendEv("response.output_text.delta", {type: "response.output_text.delta", item_id: msgId, output_index: outIdx, content_index: 0, delta: parsed.text});
    sendEv("response.output_text.done", {type: "response.output_text.done", item_id: msgId, output_index: outIdx, content_index: 0, text: parsed.text});
    const finalMsg = {id: msgId, type: "message", status: "completed", role: "assistant", content: [{type: "output_text", text: parsed.text, annotations: []}]};
    sendEv("response.output_item.done", {type: "response.output_item.done", output_index: outIdx, item: finalMsg});
    finalOutput.push(finalMsg);
    outIdx += 1;
  }

  for (const tc of parsed.toolCalls) {
    const itemId = `fc_plug_${randomBytes(6).toString("hex")}`;
    const fcInit = {id: itemId, type: "function_call", status: "in_progress", call_id: tc.id, name: tc.name, arguments: ""};
    sendEv("response.output_item.added", {type: "response.output_item.added", output_index: outIdx, item: fcInit});
    if (tc.arguments) {
      sendEv("response.function_call_arguments.delta", {type: "response.function_call_arguments.delta", item_id: itemId, output_index: outIdx, delta: tc.arguments});
    }
    sendEv("response.function_call_arguments.done", {type: "response.function_call_arguments.done", item_id: itemId, output_index: outIdx, arguments: tc.arguments});
    const fcDone = {id: itemId, type: "function_call", status: "completed", call_id: tc.id, name: tc.name, arguments: tc.arguments};
    sendEv("response.output_item.done", {type: "response.output_item.done", output_index: outIdx, item: fcDone});
    finalOutput.push(fcDone);
    outIdx += 1;
  }

  const outTok = parsed.usage.outputTokens || Math.max(1, Math.ceil((parsed.text.length + parsed.toolCalls.reduce((n, t) => n + t.arguments.length, 0)) / 3));
  const status = parsed.stopReason === "max_tokens" ? "incomplete" : "completed";
  const usageObj = {input_tokens: parsed.usage.inputTokens, output_tokens: outTok, total_tokens: parsed.usage.inputTokens + outTok};
  sendEv("response.completed", {
    type: "response.completed",
    response: {id: respId, status, model, output: finalOutput, usage: usageObj},
  });
  res.end();
  return parsed;
}

function writeSynthesizedAnthropicSse(res, model, translated) {
  const parsed = extractTranslatedPayload(translated);
  const msgId = `msg_plug_${randomBytes(6).toString("hex")}`;
  const sendEv = (event, data) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  res.writeHead(200, {"Content-Type": "text/event-stream", "Cache-Control": "no-cache"});
  sendEv("message_start", {
    type: "message_start",
    message: {id: msgId, type: "message", role: "assistant", model, content: [], stop_reason: null, usage: {input_tokens: parsed.usage.inputTokens, output_tokens: 0}},
  });
  let blockIdx = 0;

  if (parsed.text || parsed.toolCalls.length === 0) {
    sendEv("content_block_start", {type: "content_block_start", index: blockIdx, content_block: {type: "text", text: ""}});
    if (parsed.text) sendEv("content_block_delta", {type: "content_block_delta", index: blockIdx, delta: {type: "text_delta", text: parsed.text}});
    sendEv("content_block_stop", {type: "content_block_stop", index: blockIdx});
    blockIdx += 1;
  }

  for (const tc of parsed.toolCalls) {
    sendEv("content_block_start", {type: "content_block_start", index: blockIdx, content_block: {type: "tool_use", id: tc.id, name: tc.name, input: {}}});
    if (tc.arguments) {
      sendEv("content_block_delta", {type: "content_block_delta", index: blockIdx, delta: {type: "input_json_delta", partial_json: tc.arguments}});
    }
    sendEv("content_block_stop", {type: "content_block_stop", index: blockIdx});
    blockIdx += 1;
  }

  const outTok = parsed.usage.outputTokens || Math.max(1, Math.ceil((parsed.text.length + parsed.toolCalls.reduce((n, t) => n + t.arguments.length, 0)) / 3));
  sendEv("message_delta", {type: "message_delta", delta: {stop_reason: parsed.stopReason, stop_sequence: null}, usage: {output_tokens: outTok}});
  sendEv("message_stop", {type: "message_stop"});
  res.end();
  return parsed;
}
const AUTH_MODES = new Set(["bearer", "x-api-key", "x-goog-api-key", "api-key", "none"]);
const AUTH_HEADER_KEYS = new Set(["authorization", "x-api-key", "x-goog-api-key", "api-key"]);

let cachedNetConfig = null;
let cachedNetConfigAt = 0;

async function syncGatewayProxyEnv() {
  const now = Date.now();
  if (cachedNetConfig && now - cachedNetConfigAt < 10_000) return cachedNetConfig;
  try {
    const cfg = await readDownloadNetwork();
    cachedNetConfig = cfg;
    cachedNetConfigAt = now;
    if (cfg.proxyMode === "none") {
      delete process.env.HTTP_PROXY;
      delete process.env.HTTPS_PROXY;
      delete process.env.http_proxy;
      delete process.env.https_proxy;
    } else if (cfg.proxyUrl) {
      process.env.HTTP_PROXY = cfg.proxyUrl;
      process.env.HTTPS_PROXY = cfg.proxyUrl;
      process.env.http_proxy = cfg.proxyUrl;
      process.env.https_proxy = cfg.proxyUrl;
    }
    return cfg;
  } catch {
    return null;
  }
}

function resolveEffectiveProxyUrl(targetUrl, cfg) {
  if (cfg?.proxyMode === "none") return "";
  if (isPrivateHost(targetUrl.hostname)) return "";
  const raw = String(
    cfg?.proxyUrl ||
    (targetUrl.protocol === "https:" ? (process.env.HTTPS_PROXY || process.env.https_proxy) : (process.env.HTTP_PROXY || process.env.http_proxy)) ||
    process.env.ALL_PROXY || process.env.all_proxy || "",
  ).trim();
  if (!raw) return "";
  const noProxy = String(process.env.NO_PROXY || process.env.no_proxy || "").trim();
  if (noProxy) {
    const host = targetUrl.hostname.toLowerCase();
    for (const rule of noProxy.split(",").map(s => s.trim().toLowerCase()).filter(Boolean)) {
      if (rule === "*" || host === rule || (rule.startsWith(".") && host.endsWith(rule)) || host.endsWith("." + rule)) {
        return "";
      }
    }
  }
  return raw;
}

function readSocketBytes(sock, count, signal) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let got = 0;
    const onAbort = () => { cleanup(); sock.destroy(); reject(signal?.reason || Object.assign(new Error("Aborted"), {name: "AbortError"})); };
    const onData = chunk => {
      chunks.push(chunk);
      got += chunk.length;
      if (got >= count) {
        const buf = Buffer.concat(chunks);
        cleanup();
        if (buf.length > count) sock.unshift(buf.subarray(count));
        resolve(buf.subarray(0, count));
      }
    };
    const onErr = err => { cleanup(); reject(err); };
    const onClose = () => { cleanup(); reject(new Error("Proxy connection closed unexpectedly")); };
    const cleanup = () => {
      sock.off("data", onData);
      sock.off("error", onErr);
      sock.off("close", onClose);
      signal?.removeEventListener?.("abort", onAbort);
    };
    if (signal?.aborted) { onAbort(); return; }
    signal?.addEventListener?.("abort", onAbort, {once: true});
    sock.on("data", onData);
    sock.once("error", onErr);
    sock.once("close", onClose);
  });
}

async function openSocks5Tunnel(proxyUrl, targetHost, targetPort, signal) {
  const pHost = proxyUrl.hostname;
  const pPort = Number(proxyUrl.port) || 1080;
  const sock = await new Promise((resolve, reject) => {
    const s = net.connect({host: pHost, port: pPort});
    const onAbort = () => { s.destroy(); reject(signal?.reason || Object.assign(new Error("Aborted"), {name: "AbortError"})); };
    const cleanup = () => { s.off("error", onErr); signal?.removeEventListener?.("abort", onAbort); };
    const onErr = err => { cleanup(); reject(err); };
    if (signal?.aborted) { onAbort(); return; }
    signal?.addEventListener?.("abort", onAbort, {once: true});
    s.once("error", onErr);
    s.once("connect", () => { cleanup(); resolve(s); });
  });
  try {
    const user = proxyUrl.username ? decodeURIComponent(proxyUrl.username) : "";
    const pass = proxyUrl.password ? decodeURIComponent(proxyUrl.password) : "";
    const hasAuth = Boolean(user || pass);
    sock.write(Buffer.from(hasAuth ? [0x05, 0x02, 0x00, 0x02] : [0x05, 0x01, 0x00]));
    const greet = await readSocketBytes(sock, 2, signal);
    if (greet[0] !== 0x05) throw new Error("SOCKS5 代理协议响应无效");
    if (greet[1] === 0x02) {
      const uBuf = Buffer.from(user, "utf8").subarray(0, 255);
      const pBuf = Buffer.from(pass, "utf8").subarray(0, 255);
      sock.write(Buffer.concat([Buffer.from([0x01, uBuf.length]), uBuf, Buffer.from([pBuf.length]), pBuf]));
      const authRep = await readSocketBytes(sock, 2, signal);
      if (authRep[1] !== 0x00) throw new Error("SOCKS5 代理鉴权失败");
    } else if (greet[1] !== 0x00) {
      throw new Error(`SOCKS5 代理拒绝认证方式 (0x${greet[1].toString(16)})`);
    }
    const hostBuf = Buffer.from(targetHost, "utf8");
    if (hostBuf.length > 255) throw new Error("目标域名过长");
    const reqBuf = Buffer.alloc(7 + hostBuf.length);
    reqBuf[0] = 0x05; reqBuf[1] = 0x01; reqBuf[2] = 0x00; reqBuf[3] = 0x03;
    reqBuf[4] = hostBuf.length;
    hostBuf.copy(reqBuf, 5);
    reqBuf.writeUInt16BE(targetPort, 5 + hostBuf.length);
    sock.write(reqBuf);
    const repHead = await readSocketBytes(sock, 4, signal);
    if (repHead[0] !== 0x05 || repHead[1] !== 0x00) {
      throw new Error(`SOCKS5 代理建连失败 (code=${repHead[1]})`);
    }
    if (repHead[3] === 0x01) await readSocketBytes(sock, 6, signal);
    else if (repHead[3] === 0x04) await readSocketBytes(sock, 18, signal);
    else if (repHead[3] === 0x03) {
      const lenB = await readSocketBytes(sock, 1, signal);
      await readSocketBytes(sock, lenB[0] + 2, signal);
    }
    return sock;
  } catch (err) {
    sock.destroy();
    throw err;
  }
}

async function openHttpConnectTunnel(proxyUrl, targetHost, targetPort, signal) {
  const isHttpsProxy = proxyUrl.protocol === "https:";
  const pHost = proxyUrl.hostname;
  const pPort = Number(proxyUrl.port) || (isHttpsProxy ? 443 : 8080);
  const proxyAuth = (proxyUrl.username || proxyUrl.password)
    ? "Basic " + Buffer.from(`${decodeURIComponent(proxyUrl.username)}:${decodeURIComponent(proxyUrl.password)}`, "utf8").toString("base64")
    : "";
  return await new Promise((resolve, reject) => {
    let settled = false;
    const finish = (err, sock) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener?.("abort", onAbort);
      if (err) reject(err); else resolve(sock);
    };
    const mod = isHttpsProxy ? https : http;
    const headers = {Host: `${targetHost}:${targetPort}`};
    if (proxyAuth) headers["Proxy-Authorization"] = proxyAuth;
    const req = mod.request({host: pHost, port: pPort, method: "CONNECT", path: `${targetHost}:${targetPort}`, headers, agent: false});
    const onAbort = () => { req.destroy(); finish(signal?.reason || Object.assign(new Error("Aborted"), {name: "AbortError"})); };
    if (signal?.aborted) { onAbort(); return; }
    signal?.addEventListener?.("abort", onAbort, {once: true});
    req.on("connect", (res, socket, head) => {
      if (res.statusCode !== 200) {
        socket.destroy();
        finish(new Error(`HTTP 代理 CONNECT 失败 (HTTP ${res.statusCode})`));
        return;
      }
      if (head && head.length > 0) socket.unshift(head);
      finish(null, socket);
    });
    req.on("error", err => finish(err));
    req.end();
  });
}

async function proxiedFetch(urlStr, init, proxyRaw) {
  const target = new URL(urlStr);
  const proxy = new URL(proxyRaw);
  const proto = proxy.protocol.toLowerCase();
  const isTargetHttps = target.protocol === "https:";
  const targetPort = Number(target.port) || (isTargetHttps ? 443 : 80);
  const method = String(init.method || "GET").toUpperCase();
  const headers = {...(init.headers || {})};
  const bodyBuf = init.body == null ? null : (Buffer.isBuffer(init.body) ? init.body : Buffer.from(String(init.body), "utf8"));
  if (bodyBuf && !Object.keys(headers).some(k => k.toLowerCase() === "content-length")) {
    headers["Content-Length"] = String(bodyBuf.length);
  }
  const signal = init.signal;
  if (signal?.aborted) throw (signal.reason || Object.assign(new Error("Aborted"), {name: "AbortError"}));

  let reqMod;
  let reqOpts;
  if (proto === "socks5:" || proto === "socks5h:" || proto === "socks:") {
    const rawSock = await openSocks5Tunnel(proxy, target.hostname, targetPort, signal);
    const transportSock = isTargetHttps
      ? tls.connect({socket: rawSock, host: target.hostname, servername: isIP(target.hostname) ? undefined : target.hostname})
      : rawSock;
    reqMod = isTargetHttps ? https : http;
    reqOpts = {
      method,
      hostname: target.hostname,
      port: targetPort,
      path: `${target.pathname}${target.search}`,
      headers,
      createConnection: () => transportSock,
    };
  } else if (proto === "http:" || proto === "https:") {
    if (isTargetHttps) {
      const rawSock = await openHttpConnectTunnel(proxy, target.hostname, targetPort, signal);
      const tlsSock = tls.connect({socket: rawSock, host: target.hostname, servername: isIP(target.hostname) ? undefined : target.hostname});
      reqMod = https;
      reqOpts = {
        method,
        hostname: target.hostname,
        port: targetPort,
        path: `${target.pathname}${target.search}`,
        headers,
        createConnection: () => tlsSock,
      };
    } else {
      const isHttpsProxy = proto === "https:";
      reqMod = isHttpsProxy ? https : http;
      const pHeaders = {...headers, Host: target.host};
      if (proxy.username || proxy.password) {
        pHeaders["Proxy-Authorization"] = "Basic " + Buffer.from(`${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`, "utf8").toString("base64");
      }
      reqOpts = {
        method,
        hostname: proxy.hostname,
        port: Number(proxy.port) || (isHttpsProxy ? 443 : 8080),
        path: target.toString(),
        headers: pHeaders,
        agent: false,
      };
    }
  } else {
    throw new Error(`不支持的代理协议：${proxy.protocol}`);
  }

  return await new Promise((resolve, reject) => {
    let settled = false;
    const req = reqMod.request(reqOpts, res => {
      if (settled) return;
      settled = true;
      const status = res.statusCode || 502;
      const respHeaders = new Headers();
      for (const [k, v] of Object.entries(res.headers)) {
        if (Array.isArray(v)) {
          for (const item of v) respHeaders.append(k, item);
        } else if (v !== undefined) {
          respHeaders.set(k, String(v));
        }
      }
      const noBody = status === 204 || status === 205 || status === 304 || method === "HEAD";
      if (noBody) res.resume();
      const onAbortStream = () => { res.destroy(signal?.reason || Object.assign(new Error("Aborted"), {name: "AbortError"})); };
      signal?.addEventListener?.("abort", onAbortStream, {once: true});
      res.once("close", () => signal?.removeEventListener?.("abort", onAbortStream));
      resolve(new Response(noBody ? null : Readable.toWeb(res), {status, statusText: res.statusMessage || "", headers: respHeaders}));
    });
    const onAbortReq = () => {
      if (settled) return;
      settled = true;
      req.destroy();
      reject(signal?.reason || Object.assign(new Error("Aborted"), {name: "AbortError"}));
    };
    signal?.addEventListener?.("abort", onAbortReq, {once: true});
    req.once("error", err => {
      signal?.removeEventListener?.("abort", onAbortReq);
      if (settled) return;
      settled = true;
      reject(err);
    });
    if (bodyBuf && bodyBuf.length > 0) req.write(bodyBuf);
    req.end();
  });
}

/** 安全上游请求：支持 HTTP/HTTPS/SOCKS5 代理隧道，禁止跨源/降级/内网重定向（防 SSRF 与鉴权头泄露）。 */
async function safeFetch(url, init = {}, maxRedirects = 3) {
  const netCfg = await syncGatewayProxyEnv();
  let currentUrl = String(url);
  const initialParsed = new URL(currentUrl);
  const initialWasPrivate = isPrivateHost(initialParsed.hostname);
  let headers = {...(init.headers || {})};
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const parsedNow = new URL(currentUrl);
    const proxyUrl = resolveEffectiveProxyUrl(parsedNow, netCfg);
    const resp = proxyUrl
      ? await proxiedFetch(currentUrl, {...init, headers}, proxyUrl)
      : await fetch(currentUrl, {...init, headers, redirect: "manual"});
    if (resp.status >= 300 && resp.status < 400) {
      const loc = resp.headers.get("location");
      resp.body?.cancel?.().catch(() => {});
      if (!loc || hop === maxRedirects) return resp;
      const prev = new URL(currentUrl);
      const next = new URL(loc, prev);
      if (next.username || next.password) {
        throw new Error("Upstream redirect must not contain credentials");
      }
      if (prev.protocol === "https:" && next.protocol !== "https:") {
        throw new Error("Upstream redirect must not downgrade HTTPS to HTTP");
      }
      if (!initialWasPrivate && isPrivateHost(next.hostname)) {
        throw new Error("Upstream redirect to private/loopback host is blocked");
      }
      validateUrl(next.origin + next.pathname, true);
      if (next.origin !== prev.origin) {
        const stripped = {};
        for (const [k, v] of Object.entries(headers)) {
          if (!AUTH_HEADER_KEYS.has(k.toLowerCase())) stripped[k] = v;
        }
        headers = stripped;
      }
      currentUrl = next.toString();
      continue;
    }
    return resp;
  }
  throw new Error("Upstream redirect limit exceeded");
}

const providers = new Map();
const routingGroups = new Map();
const keyCooldowns = new Map();       // `${providerId}:${keyIdx}` -> untilMs
const keyCursors = new Map();         // providerId -> next index
const groupCursors = new Map();       // groupId -> next index
const sessionRouteAffinity = new Map(); // `${groupId}:${sessionId}` -> pinned target index (LRU <= 512)
const learnedAnthropicAuth = new Map(); // providerId -> "bearer" | "x-api-key"

const localToken = randomBytes(32).toString("hex");
const localTokenBuf = Buffer.from(localToken, "utf8");
let server = null;
let port = 0;
let closing = false;
let initialized = false;

function isPrivateHost(host) {
  const lower = String(host || "").toLowerCase();
  if (lower === "localhost" || lower === "::1" || lower === "[::1]" || lower.endsWith(".local")) return true;
  if (isIP(lower) !== 4) return false;
  const [first, second] = lower.split(".").map(Number);
  return first === 127 || first === 10 || (first === 192 && second === 168) || (first === 172 && second >= 16 && second <= 31) || (first === 100 && second >= 64 && second <= 127);
}

function validateUrl(raw, required = false) {
  const s = String(raw ?? "").trim();
  if (!s) {
    if (required) throw new Error("Provider URL is required");
    return "";
  }
  const url = new URL(s);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && isPrivateHost(url.hostname))) {
    throw new Error("Remote provider URL must use HTTPS");
  }
  if (url.username || url.password || url.hash || url.search) throw new Error("Provider URL must not contain credentials, query or fragment");
  return url.toString().replace(/\/+$/, "");
}

function normalizeProvider(raw) {
  if (!raw || typeof raw !== "object") throw new Error("Invalid provider");
  const id = String(raw.id ?? "");
  if (!/^[a-z0-9_-]{1,64}$/i.test(id)) throw new Error("Invalid provider id");
  if (!PROTOCOLS.has(raw.protocol) && !PLUGIN_PROTOCOL_RE.test(String(raw.protocol || ""))) {
    throw new Error("Unsupported protocol: " + raw.protocol);
  }
  const authMode = AUTH_MODES.has(raw.authMode) ? raw.authMode : "bearer";
  const baseUrl = validateUrl(raw.baseUrl, true);
  const headers = {};
  if (raw.headers && typeof raw.headers === "object") {
    for (const [k, v] of Object.entries(raw.headers)) {
      if (typeof k === "string" && typeof v === "string" && k.trim() && !/[\r\n]/.test(k + v)) headers[k.trim()] = v.trim();
    }
  }
  const queryParams = {};
  if (raw.queryParams && typeof raw.queryParams === "object") {
    for (const [k, v] of Object.entries(raw.queryParams)) {
      if (typeof k === "string" && typeof v === "string" && k.trim()) queryParams[k.trim()] = v;
    }
  }
  const apiKeys = [];
  if (Array.isArray(raw.apiKeys)) {
    for (const k of raw.apiKeys) {
      if (typeof k === "string" && k.trim() && !/[\r\n]/.test(k) && !apiKeys.includes(k.trim())) apiKeys.push(k.trim());
    }
  }
  const singleKey = typeof raw.apiKey === "string" ? raw.apiKey.trim() : "";
  if (/[\r\n]/.test(singleKey)) throw new Error("API key contains invalid newline");
  if (singleKey && !apiKeys.includes(singleKey)) apiKeys.unshift(singleKey);
  let extraBody = null;
  if (typeof raw.extraBodyJson === "string" && raw.extraBodyJson.trim()) {
    try {
      const parsed = JSON.parse(raw.extraBodyJson);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) extraBody = parsed;
    } catch {}
  }
  const modelMap = {};
  if (raw.modelMap && typeof raw.modelMap === "object") {
    for (const [k, v] of Object.entries(raw.modelMap)) {
      if (typeof k === "string" && typeof v === "string" && k.trim() && v.trim()) modelMap[k.trim()] = v.trim();
    }
  }
  const models = Array.isArray(raw.models) ? raw.models.map(m => String(m || "").trim()).filter(Boolean) : [];
  return {
    id,
    name: String(raw.name ?? id),
    protocol: raw.protocol,
    baseUrl,
    chatUrl: validateUrl(raw.chatUrl, false),
    responsesUrl: validateUrl(raw.responsesUrl, false),
    anthropicUrl: validateUrl(raw.anthropicUrl, false),
    geminiUrl: validateUrl(raw.geminiUrl, false),
    authMode,
    apiKey: apiKeys[0] || "",
    apiKeys,
    models,
    modelMap,
    headers,
    queryParams,
    extraBody,
    billingKind: String(raw.billingKind || ""),
    priceRate: Number.isFinite(Number(raw.priceRate)) && Number(raw.priceRate) >= 0 ? Number(raw.priceRate) : 1.0,
    anthropicAuthFallback: raw.anthropicAuthFallback !== false,
    enabled: raw.enabled !== false,
  };
}

const CONTEXT_WINDOW_SUFFIX_RE = /\[\d+(?:\.\d+)?[km]\]$/i;

function stripModelContextSuffix(model) {
  return String(model || "").trim().replace(CONTEXT_WINDOW_SUFFIX_RE, "").trim();
}

function matchModelMap(modelMap, candidate) {
  if (!candidate || !modelMap) return null;
  if (modelMap[candidate]) return modelMap[candidate];
  const lower = candidate.toLowerCase();
  for (const [pattern, target] of Object.entries(modelMap)) {
    const p = pattern.toLowerCase();
    if (p === lower) return target;
    if (p === "*") continue;
    if (p.includes("*")) {
      const re = new RegExp("^" + p.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$");
      if (re.test(lower)) return target;
    }
  }
  return null;
}

function resolveMappedModel(provider, requestedModel, targetProtocol = null) {
  const req = String(requestedModel || "").trim();
  if (!req) return req;
  const stripped = stripModelContextSuffix(req);
  if (provider?.modelMap) {
    const direct = matchModelMap(provider.modelMap, req);
    if (direct) return direct;
    if (stripped && stripped !== req) {
      const mappedStripped = matchModelMap(provider.modelMap, stripped);
      if (mappedStripped) return mappedStripped;
    }
    if (provider.modelMap["*"]) return provider.modelMap["*"];
  }
  // Claude CLI 2.1+ 会为默认模型附加 [1m]/[200k] 上下文后缀；转发给非 Anthropic 协议或服务商显式列出裸名时自动剥离，避免上游 400 Invalid model
  if (stripped && stripped !== req) {
    const proto = String(targetProtocol || provider?.protocol || "");
    const listedExact = Array.isArray(provider?.models) && provider.models.includes(req);
    const listedStripped = Array.isArray(provider?.models) && provider.models.includes(stripped);
    if ((proto && proto !== "anthropic_messages") || (listedStripped && !listedExact)) {
      return stripped;
    }
  }
  return req;
}

function resolveNativeTarget(provider, desiredProtocol) {
  if (desiredProtocol === "openai_responses" && provider.responsesUrl) return {protocol: "openai_responses", baseUrl: provider.responsesUrl};
  if (desiredProtocol === "anthropic_messages" && provider.anthropicUrl) return {protocol: "anthropic_messages", baseUrl: provider.anthropicUrl};
  if (desiredProtocol === "openai_chat" && provider.chatUrl) return {protocol: "openai_chat", baseUrl: provider.chatUrl};
  if (desiredProtocol === "google_gemini" && provider.geminiUrl) return {protocol: "google_gemini", baseUrl: provider.geminiUrl};
  return {protocol: provider.protocol, baseUrl: provider.baseUrl};
}

function orderedKeyEntries(provider) {
  const keys = provider.apiKeys.length > 0 ? provider.apiKeys : [""];
  const now = Date.now();
  const start = (keyCursors.get(provider.id) || 0) % keys.length;
  const healthy = [], cooling = [];
  for (let i = 0; i < keys.length; i++) {
    const idx = (start + i) % keys.length;
    const until = keyCooldowns.get(`${provider.id}:${idx}`) || 0;
    const item = {idx, key: keys[idx]};
    if (until > now) cooling.push(item); else healthy.push(item);
  }
  return [...healthy, ...cooling];
}

function markKeyCooldown(provider, idx, status) {
  const ms = status === 429 ? 45_000 : (status === 401 || status === 403) ? 180_000 : 20_000;
  keyCooldowns.set(`${provider.id}:${idx}`, Date.now() + ms);
  if (provider.apiKeys.length > 1) keyCursors.set(provider.id, (idx + 1) % provider.apiKeys.length);
}

function joinUrl(base, subPath, extraQuery = {}) {
  let cleanBase = base.replace(/\/+$/, "");
  let cleanSub = subPath.startsWith("/") ? subPath : "/" + subPath;
  if (cleanBase.endsWith("/v1") && cleanSub.startsWith("/v1/")) cleanSub = cleanSub.slice(3);
  const u = new URL(cleanBase + cleanSub);
  for (const [k, v] of Object.entries(extraQuery)) {
    if (v != null && !u.searchParams.has(k)) u.searchParams.set(k, String(v));
  }
  return u.toString();
}

function buildHeaders(provider, extra = {}, keyOverride = null, authModeOverride = null) {
  const headers = {"Content-Type": "application/json", ...provider.headers, ...extra};
  const key = keyOverride !== null ? keyOverride : provider.apiKey;
  const mode = authModeOverride || provider.authMode;
  if (key && mode !== "none") {
    if (mode === "bearer") headers["Authorization"] = "Bearer " + key;
    else if (mode === "x-api-key") headers["x-api-key"] = key;
    else if (mode === "x-goog-api-key") headers["x-goog-api-key"] = key;
    else if (mode === "api-key") headers["api-key"] = key;
  }
  return headers;
}

function applyExtraBody(body, provider) {
  if (!provider.extraBody || !body || typeof body !== "object") return body;
  return {...provider.extraBody, ...body};
}

function tokenValid(req) {
  const candidate = req.headers["x-galiais-token"] || req.headers["x-api-key"] || req.headers["x-goog-api-key"] ||
    (typeof req.headers["authorization"] === "string" && req.headers["authorization"].startsWith("Bearer ")
      ? req.headers["authorization"].slice(7) : new URL(req.url || "/", "http://127.0.0.1").searchParams.get("key") || "");
  if (typeof candidate !== "string") return false;
  const buf = Buffer.from(candidate, "utf8");
  return buf.length === localTokenBuf.length && timingSafeEqual(buf, localTokenBuf);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", (c) => {
      total += c.length;
      if (total > MAX_HTTP) { req.destroy(); reject(new Error("Request body too large")); return; }
      chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")); }
      catch { reject(new Error("Invalid JSON request body")); }
    });
    req.on("error", reject);
  });
}

function anthropicErrorType(status) {
  if (status === 400) return "invalid_request_error";
  if (status === 401) return "authentication_error";
  if (status === 403) return "permission_error";
  if (status === 404) return "not_found_error";
  if (status === 429) return "rate_limit_error";
  if (status === 529) return "overloaded_error";
  return "api_error";
}

function sendError(res, ingress, status, message) {
  if (res.headersSent) {
    res.write("event: error\ndata: " + JSON.stringify({type: "error", error: {message, type: "api_error"}}) + "\n\n");
    res.end();
    return;
  }
  const payload = ingress === "anthropic"
    ? {type: "error", error: {type: anthropicErrorType(status), message}}
    : {error: {message, type: "upstream_error", code: String(status)}};
  const headers = {"Content-Type": "application/json; charset=utf-8"};
  if (status >= 400 && status < 500 && status !== 429) headers["x-should-retry"] = "false";
  res.writeHead(status, headers);
  res.end(JSON.stringify(payload));
}

async function readErrorText(upstream) {
  try {
    const text = (await upstream.text()).slice(0, 1500);
    try {
      const parsed = JSON.parse(text);
      return parsed?.error?.message || parsed?.msg || parsed?.message || text;
    } catch { return text || `HTTP ${upstream.status}`; }
  } catch { return `HTTP ${upstream.status}`; }
}

function stripCacheControlFields(payload) {
  if (!payload || typeof payload !== "object") return payload;
  const out = {...payload};
  if (Array.isArray(out.system)) {
    out.system = out.system.map(b => (b && typeof b === "object" && "cache_control" in b ? (({cache_control, ...rest}) => rest)(b) : b));
  }
  if (Array.isArray(out.tools)) {
    out.tools = out.tools.map(t => (t && typeof t === "object" && "cache_control" in t ? (({cache_control, ...rest}) => rest)(t) : t));
  }
  if (Array.isArray(out.messages)) {
    out.messages = out.messages.map(m => {
      if (!m || !Array.isArray(m.content)) return m;
      return {
        ...m,
        content: m.content.map(b => (b && typeof b === "object" && "cache_control" in b ? (({cache_control, ...rest}) => rest)(b) : b)),
      };
    });
  }
  return out;
}

/** 带多密钥池自动故障转移与 Anthropic 双认证头自适应重放的上游请求。 */
async function fetchWithKeyPool(provider, url, payloadObj, extraHeaders, signal, {isAnthropic = false, stripStreamOptions = false, stripCacheControl = false, method = "POST"} = {}) {
  const entries = orderedKeyEntries(provider);
  let lastResp = null;
  let lastErr = null;
  const hasBody = payloadObj != null && method !== "GET" && method !== "DELETE";
  const bodyJson = hasBody ? JSON.stringify(applyExtraBody(payloadObj, provider)) : undefined;
  const makeHeaders = (key, authMode) => {
    const h = buildHeaders(provider, extraHeaders, key, authMode);
    if (!hasBody) delete h["Content-Type"];
    return h;
  };
  for (let attempt = 0; attempt < entries.length; attempt++) {
    const {idx, key} = entries[attempt];
    const preferredAuth = isAnthropic ? (learnedAnthropicAuth.get(provider.id) || provider.authMode) : provider.authMode;
    let activeAuth = preferredAuth;
    try {
      let resp = await safeFetch(url, {method, headers: makeHeaders(key, activeAuth), ...(hasBody ? {body: bodyJson} : {}), signal});
      // Magpie RetryOneAuth：第三方 Anthropic 兼容端点常只认 Bearer 或只认 x-api-key，401/403 自动切换一次
      if (isAnthropic && provider.anthropicAuthFallback && (resp.status === 401 || resp.status === 403) && key && activeAuth !== "none") {
        resp.body?.cancel?.().catch(() => {});
        const alt = activeAuth === "x-api-key" ? "bearer" : "x-api-key";
        const retry = await safeFetch(url, {method, headers: makeHeaders(key, alt), ...(hasBody ? {body: bodyJson} : {}), signal});
        if (retry.ok) { learnedAnthropicAuth.set(provider.id, alt); return retry; }
        if (retry.status !== 401 && retry.status !== 403) {
          activeAuth = alt;
          learnedAnthropicAuth.set(provider.id, alt);
        }
        resp = retry;
      }
      if (hasBody && stripStreamOptions && resp.status === 400 && payloadObj?.stream_options) {
        resp.body?.cancel?.().catch(() => {});
        const clone = applyExtraBody({...payloadObj}, provider);
        delete clone.stream_options;
        resp = await safeFetch(url, {method, headers: makeHeaders(key, activeAuth), body: JSON.stringify(clone), signal});
      }
      if (hasBody && stripCacheControl && resp.status === 400) {
        const errPreview = await resp.clone().text().catch(() => "");
        if (/cache_control|ephemeral/i.test(errPreview)) {
          resp.body?.cancel?.().catch(() => {});
          const stripped = applyExtraBody(stripCacheControlFields(payloadObj), provider);
          resp = await safeFetch(url, {method, headers: makeHeaders(key, activeAuth), body: JSON.stringify(stripped), signal});
        }
      }
      if (resp.ok) return resp;
      lastResp = resp;
      if ([401, 403, 429, 500, 502, 503, 529].includes(resp.status)) {
        markKeyCooldown(provider, idx, resp.status);
        if (attempt + 1 < entries.length) {
          resp.body?.cancel?.().catch(() => {});
          continue;
        }
      }
      return resp;
    } catch (err) {
      lastErr = err;
      markKeyCooldown(provider, idx, 503);
      if (attempt + 1 < entries.length && !signal?.aborted) continue;
      throw err;
    }
  }
  if (lastResp) return lastResp;
  throw lastErr || new Error("No available API key");
}

function createUsageAccumulator() {
  return {in: 0, out: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, ttftMs: 0, genMs: 0, streamed: false, _anthIn: 0, _firstTokAt: 0, _lastTokAt: 0, _firstChunkAt: 0};
}

function finalizeUsageAccumulator(acc, startedAt) {
  const streamed = Boolean(acc.streamed);
  const firstAt = acc._firstTokAt || (streamed && acc.out > 0 ? acc._firstChunkAt : 0);
  const lastAt = acc._lastTokAt || (streamed && acc.out > 0 ? Date.now() : 0);
  const ttftMs = streamed && firstAt > startedAt ? Math.max(1, firstAt - startedAt) : 0;
  const genMs = streamed && firstAt > 0 && lastAt > firstAt ? (lastAt - firstAt) : 0;
  return {
    in: acc.in,
    out: acc.out,
    cacheRead: acc.cacheRead,
    cacheWrite: acc.cacheWrite,
    reasoning: acc.reasoning,
    ttftMs,
    genMs,
    streamed,
    exact: true,
    contextUsed: acc.in + acc.out,
  };
}

function isContentDeltaObject(obj) {
  if (!obj || typeof obj !== "object") return false;
  if (obj.type === "content_block_delta") return true;
  if (typeof obj.type === "string" && (obj.type.endsWith(".delta") || obj.type === "response.output_text.delta")) return true;
  if (Array.isArray(obj.choices)) {
    for (const c of obj.choices) {
      const d = c?.delta;
      if (d && (d.content || d.reasoning_content || d.reasoning || (Array.isArray(d.tool_calls) && d.tool_calls.length > 0))) return true;
    }
  }
  if (Array.isArray(obj.candidates)) {
    for (const c of obj.candidates) {
      if (Array.isArray(c?.content?.parts) && c.content.parts.some(p => p?.text || p?.functionCall || p?.thought)) return true;
    }
  }
  return false;
}

function extractUsageFromObject(obj, acc) {
  if (!obj || typeof obj !== "object") return;
  const u = obj.usage || obj.response?.usage || obj.message?.usage || obj.usageMetadata;
  if (!u || typeof u !== "object") return;
  // Anthropic Messages API 的 input_tokens 仅包含未缓存输入，需加上 cache_read 与 cache_creation 才等于总输入
  const isAnthropic = u.cache_read_input_tokens !== undefined || u.cache_creation_input_tokens !== undefined ||
    obj.type === "message_start" || obj.type === "message_delta" || obj.type === "message";
  const isGemini = obj.usageMetadata !== undefined || u.promptTokenCount !== undefined ||
    u.candidatesTokenCount !== undefined || u.thoughtsTokenCount !== undefined;
  if (isAnthropic) {
    if (typeof u.input_tokens === "number" && u.input_tokens > acc._anthIn) acc._anthIn = u.input_tokens;
    if (typeof u.cache_read_input_tokens === "number" && u.cache_read_input_tokens > acc.cacheRead) acc.cacheRead = u.cache_read_input_tokens;
    if (typeof u.cache_creation_input_tokens === "number" && u.cache_creation_input_tokens > acc.cacheWrite) acc.cacheWrite = u.cache_creation_input_tokens;
    const totalIn = acc._anthIn + acc.cacheRead + acc.cacheWrite;
    if (totalIn > acc.in) acc.in = totalIn;
    if (typeof u.output_tokens === "number" && u.output_tokens > acc.out) acc.out = u.output_tokens;
    return;
  }
  if (isGemini) {
    const inTok = Number(u.promptTokenCount || 0);
    const candTok = Number(u.candidatesTokenCount || 0);
    const thoughtTok = Number(u.thoughtsTokenCount || 0);
    const cacheRead = Number(u.cachedContentTokenCount || 0);
    if (inTok > acc.in) acc.in = inTok;
    if (cacheRead > acc.cacheRead) acc.cacheRead = cacheRead;
    if (thoughtTok > acc.reasoning) acc.reasoning = thoughtTok;
    // Gemini candidatesTokenCount 不含 thoughtsTokenCount，合并为总生成输出以对齐 OpenAI 口径
    const totalOut = candTok + thoughtTok;
    if (totalOut > acc.out) acc.out = totalOut;
    return;
  }
  const inTok = u.input_tokens ?? u.prompt_tokens;
  const outTok = u.output_tokens ?? u.completion_tokens;
  const cacheRead = u.input_tokens_details?.cached_tokens ?? u.prompt_tokens_details?.cached_tokens ?? u.prompt_cache_hit_tokens;
  const cacheWrite = u.input_tokens_details?.cache_write_tokens ?? u.prompt_tokens_details?.cache_write_tokens;
  const reasoning = u.output_tokens_details?.reasoning_tokens ?? u.completion_tokens_details?.reasoning_tokens;
  if (typeof inTok === "number" && inTok > acc.in) acc.in = inTok;
  if (typeof outTok === "number" && outTok > acc.out) acc.out = outTok;
  if (typeof cacheRead === "number" && cacheRead > acc.cacheRead) acc.cacheRead = cacheRead;
  if (typeof cacheWrite === "number" && cacheWrite > acc.cacheWrite) acc.cacheWrite = cacheWrite;
  if (typeof reasoning === "number" && reasoning > acc.reasoning) acc.reasoning = reasoning;
}

const FORWARDED_RESPONSE_HEADERS = [
  "x-codex-turn-state",
  "x-reasoning-included",
  "openai-model",
  "x-request-id",
  "request-id",
  "x-codex-safety-buffering-faster-model",
  "anthropic-ratelimit-requests-remaining",
  "anthropic-ratelimit-tokens-remaining",
  "retry-after",
];

function buildPassthroughResponseHeaders(upstream, contentType) {
  const headers = {"Content-Type": contentType, "Cache-Control": "no-cache"};
  if (upstream?.headers?.get) {
    for (const h of FORWARDED_RESPONSE_HEADERS) {
      const val = upstream.headers.get(h);
      if (val) headers[h] = val;
    }
  }
  return headers;
}

/** 透明直通并实时嗅探 SSE / JSON 中的 Token 用量、真实首字延迟（TTFT）与纯生成窗口，写入用量账本。 */
async function passthroughWithUsage(upstream, res, meta, allowFailover = false) {
  const ct = upstream.headers.get("content-type") || "text/event-stream";
  const usage = createUsageAccumulator();
  if (ct.includes("application/json")) {
    const raw = await upstream.text();
    let status = upstream.status;
    let errText = "";
    try {
      const parsed = JSON.parse(raw);
      extractUsageFromObject(parsed, usage);
      if (parsed?.error?.message || (typeof parsed?.code === "number" && parsed.code >= 400 && parsed.msg)) {
        errText = String(parsed?.error?.message || parsed?.msg || "Upstream error");
        if (status === 200) status = 502;
      }
    } catch {}
    const finalized = finalizeUsageAccumulator(usage, meta.startedAt);
    if (errText && allowFailover && !res.headersSent) {
      void recordUsageEvent({...meta, ...finalized, ms: Date.now() - meta.startedAt, status, err: errText});
      throw new FailoverError(status, errText, meta.providerName);
    }
    res.writeHead(status, buildPassthroughResponseHeaders(upstream, ct));
    res.end(raw);
    void recordUsageEvent({...meta, ...finalized, ms: Date.now() - meta.startedAt, status, err: errText});
    return;
  }
  usage.streamed = true;
  res.writeHead(upstream.status, buildPassthroughResponseHeaders(upstream, ct));
  if (!upstream.body) {
    res.end();
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, meta.startedAt), ms: Date.now() - meta.startedAt, status: upstream.status});
    return;
  }
  let tail = "";
  const dec = new TextDecoder("utf-8");
  const sniffLine = (line) => {
    const trimmed = line.startsWith("data:") ? line.slice(5).trim() : line.trim();
    if (!trimmed || trimmed === "[DONE]" || !trimmed.startsWith("{")) return;
    try {
      const parsed = JSON.parse(trimmed);
      if (isContentDeltaObject(parsed)) {
        const now = Date.now();
        if (!usage._firstTokAt) usage._firstTokAt = now;
        usage._lastTokAt = now;
      }
      if (trimmed.includes("usage") || trimmed.includes("tokens") || trimmed.includes("TokenCount")) {
        extractUsageFromObject(parsed, usage);
      }
    } catch {}
  };
  let streamErr = null;
  try {
    for await (const chunk of upstream.body) {
      if (!usage._firstChunkAt) usage._firstChunkAt = Date.now();
      res.write(chunk);
      const text = tail + dec.decode(chunk, {stream: true});
      const lines = text.split("\n");
      tail = lines.pop() || "";
      for (const line of lines) sniffLine(line);
    }
    const rest = tail + dec.decode();
    if (rest.trim()) sniffLine(rest);
    res.end();
  } catch (err) {
    streamErr = err;
    throw err;
  } finally {
    const aborted = Boolean(res.destroyed || streamErr?.name === "AbortError");
    const status = streamErr ? (aborted ? 499 : 502) : (res.destroyed && !res.writableEnded ? 499 : upstream.status);
    const errText = streamErr ? (aborted ? "用户中断或连接取消" : String(streamErr.message || streamErr)) : (status === 499 ? "客户端断开连接" : "");
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, meta.startedAt), ms: Date.now() - meta.startedAt, status, err: errText});
    if (streamErr && typeof streamErr === "object") streamErr._usageRecorded = true;
  }
}

/** 统一保护所有协议转换流：无论成功、客户端中止(499)还是上游断流(502)，都精确记且仅记一条账本记录。 */
async function runPipeWithUsage(meta, usage, startedAt, res, signal, pipeFn) {
  let pipeErr = null;
  try {
    await pipeFn();
  } catch (err) {
    pipeErr = err;
    throw err;
  } finally {
    const aborted = Boolean(signal?.aborted || res.destroyed || pipeErr?.name === "AbortError");
    const status = pipeErr ? (aborted ? 499 : 502) : (aborted && !res.writableEnded ? 499 : 200);
    const errText = pipeErr ? (aborted ? "用户中断或连接取消" : String(pipeErr.message || pipeErr)) : (status === 499 ? "客户端断开连接" : "");
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status, err: errText});
    if (pipeErr && typeof pipeErr === "object") pipeErr._usageRecorded = true;
  }
}

/** 为跨协议转换流套一层零拷贝用法与真实 TTFT/生成耗时嗅探器，安全支持流式与非 SSE JSON 回退。 */
function wrapUpstreamForUsage(upstream, startedAt) {
  const usage = createUsageAccumulator();
  const ct = upstream?.headers?.get?.("content-type") || "";
  usage.streamed = !ct.includes("application/json");
  if (!upstream?.body) return {wrapped: upstream, usage};
  let tail = "";
  const dec = new TextDecoder("utf-8");
  let reader = null;
  const sniffLine = (line) => {
    const trimmed = line.startsWith("data:") ? line.slice(5).trim() : line.trim();
    if (!trimmed || trimmed === "[DONE]" || !trimmed.startsWith("{")) return;
    try {
      const parsed = JSON.parse(trimmed);
      if (usage.streamed && isContentDeltaObject(parsed)) {
        const now = Date.now();
        if (!usage._firstTokAt) usage._firstTokAt = now;
        usage._lastTokAt = now;
      }
      if (trimmed.includes("usage") || trimmed.includes("tokens") || trimmed.includes("TokenCount")) {
        extractUsageFromObject(parsed, usage);
      }
    } catch {}
  };
  const spyBody = new ReadableStream({
    async pull(controller) {
      try {
        if (!reader) reader = upstream.body.getReader();
        const {done, value} = await reader.read();
        if (done) {
          const rest = tail + dec.decode();
          if (rest.trim()) sniffLine(rest);
          controller.close();
          return;
        }
        if (!usage._firstChunkAt) usage._firstChunkAt = Date.now();
        controller.enqueue(value);
        const text = tail + dec.decode(value, {stream: true});
        const lines = text.split("\n");
        tail = lines.pop() || "";
        for (const line of lines) sniffLine(line);
      } catch (err) {
        controller.error(err);
      }
    },
    cancel(reason) {
      return reader ? reader.cancel(reason) : upstream.body.cancel(reason);
    },
  });
  const readAllText = async () => {
    const chunks = [];
    for await (const chunk of spyBody) chunks.push(Buffer.from(chunk));
    return Buffer.concat(chunks).toString("utf8");
  };
  const wrapped = {
    ok: upstream.ok,
    status: upstream.status,
    headers: upstream.headers,
    body: spyBody,
    text: readAllText,
    json: async () => JSON.parse(await readAllText()),
  };
  return {wrapped, usage};
}

function setSessionRouteAffinity(gid, sessionId, providerId) {
  if (!gid || !sessionId || !providerId) return;
  const key = `${gid}:${sessionId}`;
  sessionRouteAffinity.delete(key);
  sessionRouteAffinity.set(key, providerId);
  if (sessionRouteAffinity.size > 512) {
    const oldest = sessionRouteAffinity.keys().next().value;
    if (oldest !== undefined) sessionRouteAffinity.delete(oldest);
  }
}

function resolveRouteCandidates(targetId, requestedModel, sessionId = "") {
  if (targetId.startsWith("group_")) {
    const gid = targetId.slice("group_".length);
    const group = routingGroups.get(gid);
    if (!group || !group.targets.length) return [];
    let ordered = group.targets
      .map(t => ({provider: providers.get(t.providerId), model: t.model, weight: Math.max(1, Math.min(100, Number(t.weight) || 1))}))
      .filter(x => x.provider && x.provider.enabled);
    if (!ordered.length) return [];
    if (group.strategy === "round_robin" && ordered.length > 1) {
      // 同会话优先粘滞到已命中的上游节点，避免 round_robin 逐轮切换服务商导致服务端 Prompt Cache 归零；新会话仍按权重轮询。
      const stickyKey = sessionId ? `${gid}:${sessionId}` : "";
      const stickyPid = stickyKey ? sessionRouteAffinity.get(stickyKey) : null;
      const stickyIdx = stickyPid ? ordered.findIndex(x => x.provider.id === stickyPid) : -1;
      if (stickyIdx >= 0) {
        setSessionRouteAffinity(gid, sessionId, ordered[stickyIdx].provider.id);
        ordered = [...ordered.slice(stickyIdx), ...ordered.slice(0, stickyIdx)];
      } else {
        // 按权重展开轮询池，避免忽略 RoutingGroupTarget.weight
        const expanded = [];
        ordered.forEach((item, idx) => { for (let w = 0; w < item.weight; w++) expanded.push(idx); });
        const cursor = groupCursors.get(gid) || 0;
        groupCursors.set(gid, cursor + 1);
        const startIdx = expanded[cursor % expanded.length];
        ordered = [...ordered.slice(startIdx), ...ordered.slice(0, startIdx)];
        if (sessionId) setSessionRouteAffinity(gid, sessionId, ordered[0].provider.id);
      }
    } else if (group.strategy === "cheapest") {
      const rank = (p) => p.billingKind === "free" ? -2 : (p.billingKind === "plan" ? -1 : p.priceRate);
      ordered.sort((a, b) => rank(a.provider) - rank(b.provider));
    }
    return ordered.map((x, idx) => {
      const reqMatchesTarget = requestedModel && requestedModel !== "auto" &&
        (idx === 0 && (!x.model || x.provider.models.includes(requestedModel)));
      return {
        provider: x.provider,
        model: reqMatchesTarget ? requestedModel : (x.model || requestedModel),
        fromGroup: true,
        groupId: gid,
      };
    });
  }
  const p = providers.get(targetId);
  return (p && p.enabled) ? [{provider: p, model: requestedModel, fromGroup: false, groupId: ""}] : [];
}

class FailoverError extends Error {
  constructor(status, message, providerName) {
    super(message);
    this.status = status;
    this.providerName = providerName;
    this.isFailover = true;
  }
}

const FAILOVER_STATUSES = new Set([401, 403, 408, 429, 500, 502, 503, 504, 529]);

function failOrSend(res, protocol, status, errText, meta, startedAt, allowFailover) {
  void recordUsageEvent({...meta, ms: Date.now() - startedAt, status, err: errText});
  if (allowFailover && !res.headersSent && FAILOVER_STATUSES.has(status)) {
    throw new FailoverError(status, errText, meta.providerName);
  }
  sendError(res, protocol, status, errText);
}

async function handleCompatibleIngress(provider, route, subPath, body, req, res, signal, agentLabel, reqMetaExtra = {}) {
  const allowFailover = Boolean(reqMetaExtra.allowFailover);
  const cleanPath = subPath.replace(/^\/(v1|v1beta)(?=\/)/, "");
  if (route === "gemini" && /^\/cachedContents(?:\/[^/]+)?$/.test(cleanPath)) {
    const target = resolveNativeTarget(provider, "google_gemini");
    if (target.protocol !== "google_gemini") {
      sendError(res, route, 501, "Gemini Context Caching requires a native Google Gemini upstream provider");
      return;
    }
    const query = {...provider.queryParams};
    if (reqMetaExtra.searchParams) {
      for (const [k, v] of reqMetaExtra.searchParams.entries()) {
        if (k === "key" || k === "solbraid_sid") continue;
        if (!(k in query)) query[k] = v;
      }
    }
    const extraHeaders = {};
    if (req.headers["x-goog-api-client"]) extraHeaders["x-goog-api-client"] = String(req.headers["x-goog-api-client"]);
    let cachePayload = (req.method === "POST" || req.method === "PATCH") ? {...body} : null;
    if (cachePayload && typeof cachePayload.model === "string" && cachePayload.model.trim()) {
      const rawM = cachePayload.model.replace(/^models\//, "");
      const mappedM = resolveMappedModel(provider, rawM, "google_gemini");
      cachePayload.model = mappedM.startsWith("models/") ? mappedM : `models/${mappedM}`;
    }
    const upstream = await fetchWithKeyPool(
      provider,
      joinUrl(target.baseUrl, cleanPath, query),
      cachePayload,
      extraHeaders,
      signal,
      {method: req.method},
    );
    if (!upstream.ok) { const err = await readErrorText(upstream); sendError(res, route, upstream.status, err); return; }
    const raw = await upstream.text();
    res.writeHead(upstream.status, {"Content-Type": upstream.headers.get("content-type") || "application/json"});
    res.end(raw);
    return;
  }
  let model = body.model;
  let stream = body.stream === true;
  let action = null;
  if (route === "gemini") {
    const endpoint = /^\/models\/([^/]+):(generateContent|streamGenerateContent|countTokens)$/.exec(cleanPath);
    if (!endpoint) { sendError(res, route, 404, "Unknown Gemini endpoint"); return; }
    model = reqMetaExtra.targetModel || decodeURIComponent(endpoint[1]);
    action = endpoint[2];
    stream = action === "streamGenerateContent";
  } else if (cleanPath !== "/chat/completions") {
    sendError(res, route, 404, "Unknown Chat endpoint"); return;
  }
  if (typeof model !== "string" || !model.trim()) { sendError(res, route, 400, "A model is required"); return; }
  const mappedGemini = (route === "gemini" && !reqMetaExtra.modelAlreadyMapped) ? resolveMappedModel(provider, model, "google_gemini") : model;
  const effectiveRequested = reqMetaExtra.requestedModel || (mappedGemini !== model ? model : "");
  model = mappedGemini;
  const startedAt = Date.now();
  const meta = {startedAt, agent: agentLabel, providerId: provider.id, providerName: provider.name, model, requestedModel: effectiveRequested, sessionId: reqMetaExtra.sessionId || "", source: "gateway", billingKind: provider.billingKind, priceRate: provider.priceRate};
  const desired = route === "gemini" ? "google_gemini" : "openai_chat";
  const target = resolveNativeTarget(provider, desired);

  if (PLUGIN_PROTOCOL_RE.test(String(target.protocol || ""))) {
    const {translatorId, upstream: plugUp} = await runPluginProtocolUpstream(provider, target, body, route, signal);
    if (!plugUp.ok) { const err = await readErrorText(plugUp); failOrSend(res, route, plugUp.status, err, meta, startedAt, allowFailover); return; }
    const rawText = await plugUp.text();
    let parsedUp = rawText;
    try { parsedUp = JSON.parse(rawText); } catch {}
    const out = await callPluginCtlBridge("translateResponse", {
      translatorId,
      response: parsedUp,
      context: {route, model, stream},
    });
    const parsedOut = extractTranslatedPayload(out);
    void recordUsageEvent({...meta, in: parsedOut.usage.inputTokens, out: parsedOut.usage.outputTokens, ms: Date.now() - startedAt, status: 200});
    if (stream) {
      const writer = new CompatibleResponseWriter(res, model, route, true);
      if (parsedOut.text) writer.textDelta(parsedOut.text);
      parsedOut.toolCalls.forEach((tc, idx) => writer.toolDelta(idx, tc.id, tc.name, tc.arguments));
      writer.setUsage(parsedOut.usage.inputTokens, parsedOut.usage.outputTokens);
      writer.finish(parsedOut.stopReason === "max_tokens" ? "length" : "stop");
    } else {
      res.writeHead(200, {"Content-Type": "application/json"});
      res.end(typeof out === "string" ? out : JSON.stringify(out));
    }
    return;
  }

  let upstream;
  if (route === "openai-chat" && target.protocol === "openai_chat") {
    const chatBody = stream && !body.stream_options ? {...body, stream_options: {include_usage: true}} : body;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), chatBody, {}, signal, {stripStreamOptions: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (route === "gemini" && target.protocol === "google_gemini") {
    const query = {...provider.queryParams, ...(stream ? {alt: "sse"} : {})};
    if (reqMetaExtra.searchParams) {
      for (const [k, v] of reqMetaExtra.searchParams.entries()) {
        if (k === "key" || k === "solbraid_sid") continue;
        if (!(k in query)) query[k] = v;
      }
    }
    const extraHeaders = {};
    if (req.headers["x-goog-api-client"]) extraHeaders["x-goog-api-client"] = String(req.headers["x-goog-api-client"]);
    const geminiPayload = {...body};
    delete geminiPayload.model;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(model)}:${action}`, query), geminiPayload, extraHeaders, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (action === "countTokens") { sendError(res, route, 501, "Exact Gemini token counting requires a Gemini upstream provider"); return; }

  let normalized;
  try {
    normalized = route === "gemini"
      ? geminiToResponsesRequest(body, model, {sessionId: reqMetaExtra.sessionId})
      : chatToResponsesRequest(body, {sessionId: reqMetaExtra.sessionId});
  }
  catch (error) { sendError(res, route, 400, error.message); return; }
  const writer = new CompatibleResponseWriter(res, model, route, stream);
  if (target.protocol === "openai_responses") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), normalized, {Accept: "text/event-stream"}, signal);
  } else if (target.protocol === "openai_chat") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), responsesToChatRequest(normalized).req, {Accept: "text/event-stream"}, signal, {stripStreamOptions: true});
  } else if (target.protocol === "anthropic_messages") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), responsesToAnthropicRequest(normalized).req, {"anthropic-version": "2023-06-01", Accept: "text/event-stream"}, signal, {isAnthropic: true, stripCacheControl: true});
  } else {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(model.replace(/^models\//, ""))}:streamGenerateContent`, {...provider.queryParams, alt: "sse"}), responsesToGeminiRequest(normalized).req, {Accept: "text/event-stream"}, signal);
  }
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  await runPipeWithUsage(meta, usage, startedAt, res, signal, async () => {
    if (target.protocol === "openai_responses") await pipeResponsesToCompatible(wrapped, writer);
    else if (target.protocol === "openai_chat") await pipeChatToResponses(wrapped, res, model, new Map(), new Set(), writer);
    else if (target.protocol === "anthropic_messages") await pipeAnthropicToResponses(wrapped, res, model, new Map(), new Set(), writer);
    else await pipeGeminiToResponses(wrapped, res, model, new Map(), new Set(), writer);
  });
}

/** 为不支持原生 /responses/compact 的自定义服务商/协议合成兼容的 OpenAI Responses 上下文压缩响应。 */
async function handleCompatibleResponsesCompact(provider, target, body, res, signal, meta, startedAt, allowFailover) {
  const compactInstruction = [
    "You are compacting a coding agent's conversation history so work can continue smoothly in a fresh context window.",
    "Write a concise, information-dense handoff summary preserving:",
    "1. User goal, requirements, and constraints",
    "2. Key files inspected/modified (exact paths) and technical decisions made",
    "3. Current state, test/build results, errors encountered, and remaining next steps.",
    "Do not call any tools. Reply with the structured summary directly.",
  ].join("\n");
  const rawInput = Array.isArray(body.input) ? body.input : (typeof body.input === "string" ? [{type: "message", role: "user", content: [{type: "input_text", text: body.input}]}] : []);
  const compactReqBody = {
    model: body.model,
    instructions: body.instructions ? `${body.instructions}\n\n${compactInstruction}` : compactInstruction,
    input: [
      ...rawInput,
      {type: "message", role: "user", content: [{type: "input_text", text: "Please summarize the conversation above into a compact handoff state so we can continue seamlessly."}]},
    ],
    stream: false,
    max_output_tokens: Math.min(4096, Number(body.max_output_tokens) || 4096),
  };

  let upstream;
  let summaryText = "";
  let inTok = 0, outTok = 0;
  const modelName = String(body.model || "").replace(/^models\//, "");

  if (target.protocol === "openai_responses") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), compactReqBody, {}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const data = await upstream.json();
    summaryText = data.output_text || (Array.isArray(data.output) ? data.output.flatMap(o => Array.isArray(o?.content) ? o.content.map(c => c?.text || "") : []).join("\n") : "");
    inTok = data.usage?.input_tokens || 0;
    outTok = data.usage?.output_tokens || 0;
  } else if (target.protocol === "openai_chat") {
    const {req: chatReq} = responsesToChatRequest(compactReqBody);
    chatReq.stream = false;
    delete chatReq.stream_options;
    delete chatReq.tools;
    delete chatReq.tool_choice;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), chatReq, {}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const data = await upstream.json();
    summaryText = data.choices?.[0]?.message?.content || "";
    inTok = data.usage?.prompt_tokens || 0;
    outTok = data.usage?.completion_tokens || 0;
  } else if (target.protocol === "anthropic_messages") {
    const {req: antReq} = responsesToAnthropicRequest(compactReqBody);
    antReq.stream = false;
    delete antReq.tools;
    delete antReq.tool_choice;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), antReq, {"anthropic-version": "2023-06-01"}, signal, {isAnthropic: true, stripCacheControl: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const data = await upstream.json();
    summaryText = Array.isArray(data.content) ? data.content.filter(b => b?.type === "text").map(b => b.text).join("\n") : "";
    inTok = (data.usage?.input_tokens || 0) + (data.usage?.cache_read_input_tokens || 0) + (data.usage?.cache_creation_input_tokens || 0);
    outTok = data.usage?.output_tokens || 0;
  } else if (PLUGIN_PROTOCOL_RE.test(String(target.protocol || ""))) {
    const {translatorId, upstream: plugUp} = await runPluginProtocolUpstream(provider, target, compactReqBody, "openai-responses-compact", signal);
    if (!plugUp.ok) { const err = await readErrorText(plugUp); failOrSend(res, "openai", plugUp.status, err, meta, startedAt, allowFailover); return; }
    const rawText = await plugUp.text();
    let parsedUp = rawText;
    try { parsedUp = JSON.parse(rawText); } catch {}
    const out = await callPluginCtlBridge("translateResponse", {
      translatorId,
      response: parsedUp,
      context: {route: "openai-responses-compact", model: modelName, stream: false},
    });
    summaryText = extractTranslatedText(out);
  } else {
    const {req: gemReq} = responsesToGeminiRequest(compactReqBody);
    delete gemReq.tools;
    delete gemReq.toolConfig;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(modelName)}:generateContent`, provider.queryParams), gemReq, {}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const data = await upstream.json();
    summaryText = (data.candidates?.[0]?.content?.parts || []).filter(p => typeof p?.text === "string").map(p => p.text).join("\n");
    inTok = data.usageMetadata?.promptTokenCount || 0;
    outTok = data.usageMetadata?.candidatesTokenCount || 0;
  }

  const finalSummary = String(summaryText || "").trim() || "Conversation history compacted.";
  const nowSec = Math.floor(Date.now() / 1000);
  const compactPayload = {
    id: `resp_compact_${randomBytes(8).toString("hex")}`,
    object: "response.compaction",
    created_at: nowSec,
    output: [
      {
        type: "message",
        id: `msg_compact_${randomBytes(6).toString("hex")}`,
        role: "assistant",
        status: "completed",
        content: [{type: "output_text", text: finalSummary, annotations: []}],
      },
    ],
    usage: {
      input_tokens: inTok,
      output_tokens: outTok,
      total_tokens: inTok + outTok,
    },
  };
  void recordUsageEvent({...meta, in: inTok, out: outTok, ms: Date.now() - startedAt, status: 200});
  res.writeHead(200, {"Content-Type": "application/json"});
  res.end(JSON.stringify(compactPayload));
}

async function handleCodexIngress(provider, subPath, body, req, res, signal, reqMetaExtra = {}) {
  const allowFailover = Boolean(reqMetaExtra.allowFailover);
  const startedAt = Date.now();
  const meta = {startedAt, agent: "codex", providerId: provider.id, providerName: provider.name, model: body.model || "", requestedModel: reqMetaExtra.requestedModel || "", sessionId: reqMetaExtra.sessionId || "", source: "gateway", billingKind: provider.billingKind, priceRate: provider.priceRate};
  const target = resolveNativeTarget(provider, "openai_responses");
  if (subPath === "/responses/compact") {
    if (target.protocol === "openai_responses") {
      const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses/compact", provider.queryParams), body, {}, signal);
      if (upstream.ok) {
        await passthroughWithUsage(upstream, res, meta, allowFailover);
        return;
      }
      // 许多第三方 Responses 中转不支持 /responses/compact（返回 404/405/501），自动降级为网关兼容摘要压缩。
      if (![404, 405, 501].includes(upstream.status)) {
        const err = await readErrorText(upstream);
        failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover);
        return;
      }
      upstream.body?.cancel?.().catch(() => {});
    }
    await handleCompatibleResponsesCompact(provider, target, body, res, signal, meta, startedAt, allowFailover);
    return;
  }
  if (subPath !== "/responses") { sendError(res, "openai", 404, "Unknown Codex endpoint"); return; }

  if (PLUGIN_PROTOCOL_RE.test(String(target.protocol || ""))) {
    const {translatorId, upstream: plugUp} = await runPluginProtocolUpstream(provider, target, body, "openai-responses", signal);
    if (!plugUp.ok) { const err = await readErrorText(plugUp); failOrSend(res, "openai", plugUp.status, err, meta, startedAt, allowFailover); return; }
    const rawText = await plugUp.text();
    let parsedUp = rawText;
    try { parsedUp = JSON.parse(rawText); } catch {}
    const out = await callPluginCtlBridge("translateResponse", {
      translatorId,
      response: parsedUp,
      context: {route: "openai-responses", model: body.model || "", stream: body.stream !== false},
    });
    const parsedOut = extractTranslatedPayload(out);
    void recordUsageEvent({...meta, in: parsedOut.usage.inputTokens, out: parsedOut.usage.outputTokens, ms: Date.now() - startedAt, status: 200});
    if (body.stream !== false) {
      writeSynthesizedResponsesSse(res, body.model || "", out);
    } else {
      res.writeHead(200, {"Content-Type": "application/json"});
      res.end(typeof out === "string" ? out : JSON.stringify(out));
    }
    return;
  }

  const wantStream = body.stream !== false;
  if (target.protocol === "openai_responses") {
    const extra = {Accept: wantStream ? "text/event-stream" : "application/json"};
    // 透传 Codex 0.172 粘滞路由与会话亲和请求头（含 x-codex-turn-state / x-codex-window-id 等）
    for (const h of [
      "session_id",
      "openai-beta",
      "x-client-request-id",
      "x-codex-turn-state",
      "x-codex-window-id",
      "x-codex-turn-metadata",
      "x-codex-installation-id",
      "x-codex-parent-thread-id",
      "x-codex-beta-features",
      "x-openai-subagent",
      "x-openai-internal-codex-responses-lite",
    ]) {
      if (req.headers[h]) extra[h] = String(req.headers[h]);
    }
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), body, extra, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (target.protocol === "openai_chat") {
    const {req: chatReq, namespaceMap, customSet} = responsesToChatRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), chatReq, {Accept: "text/event-stream"}, signal, {stripStreamOptions: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeChatToResponses(wrapped, res, body.model, namespaceMap, customSet, undefined, wantStream));
    return;
  }
  if (target.protocol === "anthropic_messages") {
    const {req: antReq, namespaceMap, customSet} = responsesToAnthropicRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), antReq, {"anthropic-version": "2023-06-01", Accept: "text/event-stream"}, signal, {isAnthropic: true, stripCacheControl: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeAnthropicToResponses(wrapped, res, body.model, namespaceMap, customSet, undefined, wantStream));
    return;
  }
  const {req: gemReq, namespaceMap, customSet} = responsesToGeminiRequest(body);
  const modelName = String(body.model || "").replace(/^models\//, "");
  const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(modelName)}:streamGenerateContent`, {alt: "sse", ...provider.queryParams}), gemReq, {Accept: "text/event-stream"}, signal);
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeGeminiToResponses(wrapped, res, body.model, namespaceMap, customSet, undefined, wantStream));
}

async function handleClaudeIngress(provider, subPath, query, body, req, res, signal, reqMetaExtra = {}) {
  const allowFailover = Boolean(reqMetaExtra.allowFailover);
  const startedAt = Date.now();
  const meta = {startedAt, agent: "claude", providerId: provider.id, providerName: provider.name, model: body.model || "", requestedModel: reqMetaExtra.requestedModel || "", sessionId: reqMetaExtra.sessionId || "", source: "gateway", billingKind: provider.billingKind, priceRate: provider.priceRate};
  const target = resolveNativeTarget(provider, "anthropic_messages");
  if (subPath === "/v1/messages/count_tokens" || subPath === "/messages/count_tokens") {
    if (target.protocol === "anthropic_messages") {
      const extra = {"anthropic-version": req.headers["anthropic-version"] || "2023-06-01"};
      if (req.headers["anthropic-beta"]) extra["anthropic-beta"] = req.headers["anthropic-beta"];
      const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages/count_tokens", provider.queryParams), body, extra, signal, {isAnthropic: true});
      if (!upstream.ok) { sendError(res, "anthropic", upstream.status, await readErrorText(upstream)); return; }
      const raw = await upstream.text();
      res.writeHead(upstream.status, {"Content-Type": upstream.headers.get("content-type") || "application/json"});
      res.end(raw);
      return;
    }
    sendError(res, "anthropic", 501, "Exact Anthropic token counting requires an Anthropic upstream provider");
    return;
  }
  if (subPath !== "/v1/messages" && subPath !== "/messages") { sendError(res, "anthropic", 404, "Unknown Anthropic endpoint"); return; }

  if (PLUGIN_PROTOCOL_RE.test(String(target.protocol || ""))) {
    const {translatorId, upstream: plugUp} = await runPluginProtocolUpstream(provider, target, body, "anthropic", signal);
    if (!plugUp.ok) { const err = await readErrorText(plugUp); failOrSend(res, "anthropic", plugUp.status, err, meta, startedAt, allowFailover); return; }
    const rawText = await plugUp.text();
    let parsedUp = rawText;
    try { parsedUp = JSON.parse(rawText); } catch {}
    const out = await callPluginCtlBridge("translateResponse", {
      translatorId,
      response: parsedUp,
      context: {route: "anthropic", model: body.model || "", stream: body.stream === true},
    });
    const parsedOut = extractTranslatedPayload(out);
    void recordUsageEvent({...meta, in: parsedOut.usage.inputTokens, out: parsedOut.usage.outputTokens, ms: Date.now() - startedAt, status: 200});
    if (body.stream === true) {
      writeSynthesizedAnthropicSse(res, body.model || "", out);
    } else {
      const contentBlocks = [];
      if (parsedOut.text || parsedOut.toolCalls.length === 0) contentBlocks.push({type: "text", text: parsedOut.text});
      for (const tc of parsedOut.toolCalls) {
        contentBlocks.push({type: "tool_use", id: tc.id, name: tc.name, input: tc.input});
      }
      const outTok = parsedOut.usage.outputTokens || Math.max(1, Math.ceil(parsedOut.text.length / 3));
      const payload = (out && typeof out === "object" && Array.isArray(out.content))
        ? out
        : {
          id: `msg_plug_${randomBytes(6).toString("hex")}`,
          type: "message",
          role: "assistant",
          model: body.model || "",
          content: contentBlocks,
          stop_reason: parsedOut.stopReason,
          usage: {input_tokens: parsedOut.usage.inputTokens, output_tokens: outTok},
        };
      res.writeHead(200, {"Content-Type": "application/json"});
      res.end(JSON.stringify(payload));
    }
    return;
  }

  const wantStream = body.stream === true;
  if (target.protocol === "anthropic_messages") {
    const extra = {"anthropic-version": req.headers["anthropic-version"] || "2023-06-01", Accept: wantStream ? "text/event-stream" : "application/json"};
    if (req.headers["anthropic-beta"]) extra["anthropic-beta"] = req.headers["anthropic-beta"];
    const mergedQuery = {...provider.queryParams};
    for (const [k, v] of query.entries()) if (!(k in mergedQuery)) mergedQuery[k] = v;
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", mergedQuery), body, extra, signal, {isAnthropic: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (target.protocol === "openai_chat") {
    const chatReq = anthropicToChatRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), chatReq, {Accept: "text/event-stream"}, signal, {stripStreamOptions: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeChatToAnthropic(wrapped, res, body.model, wantStream));
    return;
  }
  if (target.protocol === "openai_responses") {
    const respReq = anthropicToResponsesRequest(body, {sessionId: reqMetaExtra.sessionId});
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), respReq, {Accept: "text/event-stream"}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeResponsesToAnthropic(wrapped, res, body.model, wantStream));
    return;
  }
  const gemReq = anthropicToGeminiRequest(body);
  const modelName = String(body.model || "").replace(/^models\//, "");
  const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(modelName)}:streamGenerateContent`, {alt: "sse", ...provider.queryParams}), gemReq, {Accept: "text/event-stream"}, signal);
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  await runPipeWithUsage(meta, usage, startedAt, res, signal, () => pipeGeminiToAnthropic(wrapped, res, body.model, wantStream));
}

async function handleHttp(req, res) {
  const url = new URL(req.url || "/", "http://127.0.0.1");
  const match = /^\/(openai-responses|anthropic|openai-chat|gemini)\/([a-z0-9_-]+)(?:\/sid\/([^/]+))?(\/.*)?$/i.exec(url.pathname);
  const ingress = match?.[1] === "anthropic" ? "anthropic" : "openai";
  if (!tokenValid(req)) { sendError(res, ingress, 401, "Unauthorized local gateway request"); return; }
  if (!match || (req.method !== "POST" && !["openai-chat", "gemini"].includes(match[1]))) { sendError(res, ingress, 404, "Not found"); return; }
  const route = match[1];
  const targetId = match[2];
  const urlSessionId = match[3] ? decodeURIComponent(match[3]) : (url.searchParams.get("solbraid_sid") || "");
  if (url.searchParams.has("solbraid_sid")) url.searchParams.delete("solbraid_sid");
  const subPath = match[4] || "";

  if (req.method === "GET") {
    const cleanPath = subPath.replace(/^\/(v1|v1beta)(?=\/)/, "");
    if (cleanPath === "/models") {
      const cands = resolveRouteCandidates(targetId, null, urlSessionId);
      if (!cands.length) { sendError(res, route, 404, "该服务商或路由组已删除或未同步"); return; }
      const models = targetId.startsWith("group_")
        ? ["auto", ...new Set(cands.map(c => c.model).filter(Boolean))]
        : await fetchModels(cands[0].provider);
      res.writeHead(200, {"Content-Type": "application/json"});
      res.end(JSON.stringify(route === "gemini"
        ? {models: models.map(id => ({name: "models/" + id, displayName: id, supportedGenerationMethods: ["generateContent", "streamGenerateContent"]}))}
        : {object: "list", data: models.map(id => ({id, object: "model", owned_by: cands[0].provider.name}))}));
      return;
    }
    if (!(route === "gemini" && /^\/cachedContents(?:\/[^/]+)?$/.test(cleanPath))) {
      sendError(res, route, 404, "Not found");
      return;
    }
  } else if (req.method !== "POST") {
    const cleanPath = subPath.replace(/^\/(v1|v1beta)(?=\/)/, "");
    if (!(route === "gemini" && ["PATCH", "DELETE"].includes(req.method) && /^\/cachedContents\/[^/]+$/.test(cleanPath))) {
      sendError(res, ingress, 405, "Method not allowed");
      return;
    }
  }

  let body = {};
  if (req.method === "POST" || req.method === "PATCH") {
    try { body = await readJsonBody(req); } catch (e) { sendError(res, ingress, 400, e.message); return; }
  }
  const geminiPathModel = route === "gemini"
    ? (/^\/(?:v1|v1beta)\/models\/([^/]+):|^\/models\/([^/]+):/.exec(subPath)?.[1] || /^\/(?:v1|v1beta)\/models\/([^/]+):|^\/models\/([^/]+):/.exec(subPath)?.[2] || "")
    : "";
  const initialRequestedModel = body.model || (geminiPathModel ? decodeURIComponent(geminiPathModel) : "");
  const inferredSessionId = urlSessionId
    || String(req.headers["x-solbraid-session-id"] || req.headers["session_id"] || "")
    || (typeof body?.prompt_cache_key === "string" ? body.prompt_cache_key : "");
  const candidates = resolveRouteCandidates(targetId, initialRequestedModel, inferredSessionId);
  if (!candidates.length) { sendError(res, ingress, 404, "该服务商或路由组已删除、已停用或未同步，请在服务商设置中检查"); return; }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error("Provider request timed out")), 30 * 60 * 1000);
  res.once("close", () => { if (!res.writableEnded) controller.abort(); });
  try {
    for (let ci = 0; ci < candidates.length; ci++) {
      const picked = candidates[ci];
      const allowFailover = ci + 1 < candidates.length;
      const requestedModel = picked.model || initialRequestedModel || "";
      const desiredProto = route === "openai-responses" ? "openai_responses" : route === "anthropic" ? "anthropic_messages" : route === "gemini" ? "google_gemini" : "openai_chat";
      const targetProto = resolveNativeTarget(picked.provider, desiredProto).protocol;
      const mappedModel = resolveMappedModel(picked.provider, requestedModel, targetProto);
      const effectiveBody = (route !== "gemini" && mappedModel) ? {...body, model: mappedModel} : body;
      const reqMetaExtra = {
        allowFailover,
        sessionId: inferredSessionId,
        searchParams: url.searchParams,
        modelAlreadyMapped: Boolean(mappedModel),
        ...((picked.fromGroup || route === "gemini") && mappedModel ? {targetModel: mappedModel} : {}),
        ...(initialRequestedModel && mappedModel && initialRequestedModel !== mappedModel ? {requestedModel: initialRequestedModel} : {}),
      };
      const attemptStarted = Date.now();
      try {
        if (route === "openai-responses") await handleCodexIngress(picked.provider, subPath, effectiveBody, req, res, controller.signal, reqMetaExtra);
        else if (route === "anthropic") await handleClaudeIngress(picked.provider, subPath, url.searchParams, effectiveBody, req, res, controller.signal, reqMetaExtra);
        else await handleCompatibleIngress(picked.provider, route, subPath, effectiveBody, req, res, controller.signal, route === "gemini" ? "antigravity" : "chat", reqMetaExtra);
        if (picked.fromGroup && picked.groupId && inferredSessionId) {
          setSessionRouteAffinity(picked.groupId, inferredSessionId, picked.provider.id);
        }
        return;
      } catch (err) {
        // 无论是否配置故障转移，非 FailoverError 且尚未记账的异常/取消都必须落盘，彻底杜绝单服务商失败请求在用量中心凭空消失
        if (!err?.isFailover && !err?._usageRecorded) {
          const aborted = Boolean(controller.signal.aborted || res.destroyed || err?.name === "AbortError");
          void recordUsageEvent({
            startedAt: attemptStarted,
            agent: route === "anthropic" ? "claude" : (route === "openai-responses" ? "codex" : (route === "gemini" ? "antigravity" : "chat")),
            providerId: picked.provider.id,
            providerName: picked.provider.name,
            model: mappedModel || requestedModel,
            requestedModel: requestedModel && mappedModel && requestedModel !== mappedModel ? requestedModel : "",
            sessionId: inferredSessionId,
            source: "gateway",
            billingKind: picked.provider.billingKind,
            priceRate: picked.provider.priceRate,
            ms: Date.now() - attemptStarted,
            status: aborted ? 499 : 502,
            err: aborted ? "用户中断或连接取消" : (err?.message || String(err)),
          });
          if (err && typeof err === "object") err._usageRecorded = true;
        }
        if (allowFailover && !res.headersSent && !controller.signal.aborted) {
          continue;
        }
        throw err;
      }
    }
  } catch (error) {
    if (!res.destroyed && !res.writableEnded) {
      const status = error?.isFailover && error.status ? error.status : 502;
      const msg = error?.isFailover ? error.message : `服务商 ${candidates[candidates.length - 1].provider.name} 请求失败：${error.message}`;
      sendError(res, ingress, status, msg);
    }
  } finally {
    clearTimeout(timer);
    controller.abort();
    if (!res.destroyed && !res.writableEnded) {
      try { res.end(); } catch {}
    }
  }
}

async function fetchModels(provider) {
  const plugMatch = PLUGIN_PROTOCOL_RE.exec(String(provider.protocol || ""));
  if (plugMatch) {
    const translatorId = plugMatch[1].toLowerCase();
    let activeList;
    try {
      activeList = await callPluginCtlBridge("list", {});
    } catch (err) {
      throw new Error(`无法检查插件协议「plugin:${translatorId}」状态：${err?.message || err}`);
    }
    const found = Array.isArray(activeList?.translators) && activeList.translators.some(t => String(t?.id || "").toLowerCase() === translatorId);
    if (!found) {
      throw new Error(`插件协议「plugin:${translatorId}」未加载或所属插件已卸载/停用，请先启用对应插件或切换协议`);
    }
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    let url, extra = {};
    const proto = provider.chatUrl ? "openai_chat" : provider.protocol;
    const base = provider.chatUrl || provider.baseUrl;
    if (proto === "anthropic_messages") {
      url = joinUrl(base, "/v1/models", provider.queryParams);
      extra = {"anthropic-version": "2023-06-01"};
    } else if (proto === "google_gemini") {
      url = joinUrl(base, "/models", {pageSize: "200", ...provider.queryParams});
    } else {
      url = joinUrl(base, "/models", provider.queryParams);
    }
    const entries = orderedKeyEntries(provider);
    let resp = null;
    for (const {key} of entries) {
      const preferredAuth = proto === "anthropic_messages" ? (learnedAnthropicAuth.get(provider.id) || provider.authMode) : provider.authMode;
      const headers = buildHeaders(provider, extra, key, preferredAuth);
      delete headers["Content-Type"];
      resp = await safeFetch(url, {method: "GET", headers, signal: controller.signal});
      if (resp.ok) break;
      if (proto === "anthropic_messages" && provider.anthropicAuthFallback && (resp.status === 401 || resp.status === 403) && key && preferredAuth !== "none") {
        resp.body?.cancel?.().catch(() => {});
        const alt = preferredAuth === "x-api-key" ? "bearer" : "x-api-key";
        const h2 = buildHeaders(provider, extra, key, alt);
        delete h2["Content-Type"];
        resp = await safeFetch(url, {method: "GET", headers: h2, signal: controller.signal});
        if (resp.ok) {
          learnedAnthropicAuth.set(provider.id, alt);
          break;
        }
      }
      resp.body?.cancel?.().catch(() => {});
    }
    if (!resp || !resp.ok) throw new Error(`HTTP ${resp?.status || 0}: ${resp ? await readErrorText(resp) : "无响应"}`);
    const data = await resp.json();
    if (proto === "google_gemini") {
      const list = (data.models ?? [])
        .filter((m) => !Array.isArray(m.supportedGenerationMethods) || m.supportedGenerationMethods.includes("generateContent"))
        .map((m) => String(m.name || "").replace(/^models\//, ""))
        .filter(Boolean);
      return [...new Set(list)].sort();
    }
    const rawList = Array.isArray(data.data) ? data.data : (Array.isArray(data.models) ? data.models : []);
    return [...new Set(rawList.map((m) => typeof m === "string" ? m : (m.id || m.name || "")).filter(Boolean))].sort();
  } finally { clearTimeout(timer); }
}

async function pingProviderCompletion(provider, modelHint) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error("推理实测超时（25s）")), 25_000);
  const started = Date.now();
  try {
    let model = String(modelHint || provider.models?.[0] || "").trim();
    if (!model) {
      const list = await fetchModels(provider).catch(() => []);
      model = list[0] || "";
    }
    if (!model) throw new Error("请先填写或获取至少 1 个模型 ID 再进行推理实测");
    const mapped = resolveMappedModel(provider, model);
    const target = resolveNativeTarget(provider, provider.protocol);
    if (PLUGIN_PROTOCOL_RE.test(String(target.protocol || ""))) {
      const {translatorId, upstream: plugUp} = await runPluginProtocolUpstream(
        provider,
        target,
        {model: mapped, max_tokens: 16, stream: false, messages: [{role: "user", content: "Reply OK"}]},
        "ping",
        controller.signal,
      );
      const ttftMs = Date.now() - started;
      if (!plugUp.ok) throw new Error(`HTTP ${plugUp.status}: ${await readErrorText(plugUp)}`);
      const raw = await plugUp.text();
      let parsedUp = raw;
      try { parsedUp = JSON.parse(raw); } catch {}
      const translated = await callPluginCtlBridge("translateResponse", {
        translatorId,
        response: parsedUp,
        context: {route: "ping", model: mapped, stream: false},
      });
      const latencyMs = Date.now() - started;
      const preview = extractTranslatedText(translated) || "OK";
      return {
        ok: true,
        status: plugUp.status,
        model,
        mappedModel: mapped,
        protocol: target.protocol,
        ttftMs,
        latencyMs,
        preview: String(preview).trim().replace(/\s+/g, " ").slice(0, 60),
      };
    }
    let resp;
    if (target.protocol === "anthropic_messages") {
      resp = await fetchWithKeyPool(
        provider,
        joinUrl(target.baseUrl, "/v1/messages", provider.queryParams),
        {model: mapped, max_tokens: 16, messages: [{role: "user", content: "Reply OK"}]},
        {"anthropic-version": "2023-06-01"},
        controller.signal,
        {isAnthropic: true},
      );
    } else if (target.protocol === "google_gemini") {
      resp = await fetchWithKeyPool(
        provider,
        joinUrl(target.baseUrl, `/models/${encodeURIComponent(mapped.replace(/^models\//, ""))}:generateContent`, provider.queryParams),
        {contents: [{role: "user", parts: [{text: "Reply OK"}]}], generationConfig: {maxOutputTokens: 16}},
        {},
        controller.signal,
      );
    } else if (target.protocol === "openai_responses") {
      resp = await fetchWithKeyPool(
        provider,
        joinUrl(target.baseUrl, "/responses", provider.queryParams),
        {model: mapped, input: "Reply OK", max_output_tokens: 16},
        {},
        controller.signal,
      );
    } else {
      resp = await fetchWithKeyPool(
        provider,
        joinUrl(target.baseUrl, "/chat/completions", provider.queryParams),
        {model: mapped, max_tokens: 16, messages: [{role: "user", content: "Reply OK"}]},
        {},
        controller.signal,
      );
    }
    const ttftMs = Date.now() - started;
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await readErrorText(resp)}`);
    const raw = await resp.text();
    const latencyMs = Date.now() - started;
    let preview = "";
    try {
      const j = JSON.parse(raw);
      if (j?.error?.message || (typeof j?.code === "number" && j.code >= 400 && j.msg)) {
        throw new Error(j?.error?.message || j.msg);
      }
      preview = j?.choices?.[0]?.message?.content
        || j?.content?.[0]?.text
        || j?.output_text
        || j?.output?.[0]?.content?.[0]?.text
        || j?.candidates?.[0]?.content?.parts?.[0]?.text
        || "OK";
    } catch (e) {
      if (e instanceof SyntaxError) preview = raw.slice(0, 60);
      else throw e;
    }
    return {
      ok: true,
      status: resp.status,
      model,
      mappedModel: mapped,
      protocol: target.protocol,
      ttftMs,
      latencyMs,
      preview: String(preview).trim().replace(/\s+/g, " ").slice(0, 60),
    };
  } finally { clearTimeout(timer); }
}

function output(packet) {
  if (closing) return;
  process.stdout.write(JSON.stringify(packet) + "\n");
}

async function dispatch(method, params) {
  if (method === "initialize") {
    if (initialized) throw new Error("Provider gateway already initialized");
    server = http.createServer((req, res) => { handleHttp(req, res).catch(() => {}); });
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    port = server.address().port;
    initialized = true;
    return {port, token: localToken, protocols: [...PROTOCOLS]};
  }
  if (!initialized) throw new Error("Provider gateway not initialized");
  if (method === "providers/sync") {
    const list = Array.isArray(params.providers) ? params.providers : [];
    const next = new Map();
    for (const item of list) {
      const normalized = normalizeProvider(item);
      next.set(normalized.id, normalized);
    }
    providers.clear();
    for (const [k, v] of next) providers.set(k, v);
    for (const k of [...keyCooldowns.keys()]) {
      const pid = k.split(":")[0];
      if (!providers.has(pid)) keyCooldowns.delete(k);
    }
    for (const pid of [...keyCursors.keys()]) if (!providers.has(pid)) keyCursors.delete(pid);
    for (const pid of [...learnedAnthropicAuth.keys()]) if (!providers.has(pid)) learnedAnthropicAuth.delete(pid);
    routingGroups.clear();
    for (const g of (Array.isArray(params.routingGroups) ? params.routingGroups : [])) {
      if (g && typeof g.id === "string" && g.id.trim() && Array.isArray(g.targets)) {
        routingGroups.set(g.id.trim(), {
          id: g.id.trim(),
          name: String(g.name || g.id),
          strategy: String(g.strategy || "failover"),
          targets: g.targets.filter(t => t && typeof t.providerId === "string" && typeof t.model === "string"),
        });
      }
    }
    for (const gid of [...groupCursors.keys()]) if (!routingGroups.has(gid)) groupCursors.delete(gid);
    for (const [k, pid] of [...sessionRouteAffinity.entries()]) {
      const gid = k.split(":")[0];
      if (!routingGroups.has(gid) || !providers.has(pid)) sessionRouteAffinity.delete(k);
    }
    return {count: providers.size, groupCount: routingGroups.size};
  }
  if (method === "providers/health") {
    const now = Date.now();
    const statuses = {};
    for (const [pid, p] of providers) {
      const keyCount = p.apiKeys.length;
      let coolingCount = 0;
      let maxCooldownSec = 0;
      for (let i = 0; i < Math.max(1, keyCount); i++) {
        const until = keyCooldowns.get(`${pid}:${i}`) || 0;
        if (until > now) {
          coolingCount++;
          maxCooldownSec = Math.max(maxCooldownSec, Math.ceil((until - now) / 1000));
        } else if (until > 0) {
          keyCooldowns.delete(`${pid}:${i}`);
        }
      }
      statuses[pid] = {
        enabled: p.enabled,
        keyCount,
        coolingCount,
        maxCooldownSec,
        learnedAnthropicAuth: learnedAnthropicAuth.get(pid) || null,
      };
    }
    return {
      ok: true,
      port,
      providerCount: providers.size,
      groupCount: routingGroups.size,
      providers: statuses,
    };
  }
  if (method === "providers/listModels") {
    const p = params.provider ? normalizeProvider(params.provider) : providers.get(String(params.providerId ?? ""));
    if (!p) throw new Error("Provider not found");
    return {models: await fetchModels(p)};
  }
  if (method === "providers/test") {
    const p = params.provider ? normalizeProvider(params.provider) : providers.get(String(params.providerId ?? ""));
    if (!p) throw new Error("Provider not found");
    const started = Date.now();
    const models = await fetchModels(p);
    return {ok: true, latencyMs: Date.now() - started, modelCount: models.length, models, sampleModels: models.slice(0, 5)};
  }
  if (method === "providers/ping") {
    const p = params.provider ? normalizeProvider(params.provider) : providers.get(String(params.providerId ?? ""));
    if (!p) throw new Error("Provider not found");
    return await pingProviderCompletion(p, params.model || null);
  }
  if (method === "providers/resetCooldowns") {
    const pid = params.providerId ? String(params.providerId) : "";
    let cleared = 0;
    for (const k of [...keyCooldowns.keys()]) {
      if (!pid || k.startsWith(pid + ":")) { keyCooldowns.delete(k); cleared++; }
    }
    if (pid) learnedAnthropicAuth.delete(pid); else learnedAnthropicAuth.clear();
    return {ok: true, cleared};
  }
  throw new Error("Unsupported gateway method: " + method);
}

function shutdown() {
  if (closing) return;
  closing = true;
  providers.clear();
  routingGroups.clear();
  server?.close();
  process.stdin.pause();
  process.stdin.unref?.();
}

let buffered = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  try {
    buffered = Buffer.concat([buffered, chunk]);
    let end;
    while ((end = buffered.indexOf(10)) >= 0) {
      if (end > MAX_RPC) throw new Error("Gateway RPC input exceeded");
      const line = buffered.subarray(0, end);
      buffered = buffered.subarray(end + 1);
      const packet = JSON.parse(new TextDecoder("utf-8", {fatal: true}).decode(line));
      if (packet.method === "initialized" && packet.id === undefined) continue;
      if (packet.id === undefined) continue;
      dispatch(String(packet.method ?? ""), packet.params ?? {})
        .then((result) => output({id: packet.id, result}), (error) => output({id: packet.id, error: {code: -32000, message: error.message}}));
    }
    if (buffered.length > MAX_RPC) throw new Error("Gateway RPC input exceeded");
  } catch { process.exitCode = 1; shutdown(); }
});
process.stdin.once("end", shutdown);
process.stdin.once("error", shutdown);
process.stdout.once("error", shutdown);
for (const sig of ["SIGTERM", "SIGINT"]) process.once(sig, shutdown);
