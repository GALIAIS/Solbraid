import http from "node:http";
import {isIP} from "node:net";
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

/** 安全上游请求：禁止盲目跟随跨源/非法重定向（防 SSRF 与鉴权头泄露），并同步代理配置。 */
async function safeFetch(url, init = {}, maxRedirects = 3) {
  await syncGatewayProxyEnv();
  let currentUrl = String(url);
  let headers = {...(init.headers || {})};
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const resp = await fetch(currentUrl, {...init, headers, redirect: "manual"});
    if (resp.status >= 300 && resp.status < 400) {
      const loc = resp.headers.get("location");
      resp.body?.cancel?.().catch(() => {});
      if (!loc || hop === maxRedirects) return resp;
      const prev = new URL(currentUrl);
      const next = new URL(loc, prev);
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
  if (!PROTOCOLS.has(raw.protocol)) throw new Error("Unsupported protocol: " + raw.protocol);
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

function resolveMappedModel(provider, requestedModel) {
  const req = String(requestedModel || "").trim();
  if (!req || !provider?.modelMap) return req;
  if (provider.modelMap[req]) return provider.modelMap[req];
  const lower = req.toLowerCase();
  for (const [pattern, target] of Object.entries(provider.modelMap)) {
    const p = pattern.toLowerCase();
    if (p === lower) return target;
    if (p === "*") continue;
    if (p.includes("*")) {
      const re = new RegExp("^" + p.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$");
      if (re.test(lower)) return target;
    }
  }
  if (provider.modelMap["*"]) return provider.modelMap["*"];
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

/** 带多密钥池自动故障转移与 Anthropic 双认证头自适应重放的上游请求。 */
async function fetchWithKeyPool(provider, url, payloadObj, extraHeaders, signal, {isAnthropic = false, stripStreamOptions = false} = {}) {
  const entries = orderedKeyEntries(provider);
  let lastResp = null;
  let lastErr = null;
  const bodyJson = JSON.stringify(applyExtraBody(payloadObj, provider));
  for (let attempt = 0; attempt < entries.length; attempt++) {
    const {idx, key} = entries[attempt];
    const preferredAuth = isAnthropic ? (learnedAnthropicAuth.get(provider.id) || provider.authMode) : provider.authMode;
    try {
      let resp = await safeFetch(url, {method: "POST", headers: buildHeaders(provider, extraHeaders, key, preferredAuth), body: bodyJson, signal});
      // Magpie RetryOneAuth：第三方 Anthropic 兼容端点常只认 Bearer 或只认 x-api-key，401/403 自动切换一次
      if (isAnthropic && provider.anthropicAuthFallback && (resp.status === 401 || resp.status === 403) && key && preferredAuth !== "none") {
        resp.body?.cancel?.().catch(() => {});
        const alt = preferredAuth === "x-api-key" ? "bearer" : "x-api-key";
        const retry = await safeFetch(url, {method: "POST", headers: buildHeaders(provider, extraHeaders, key, alt), body: bodyJson, signal});
        if (retry.ok) { learnedAnthropicAuth.set(provider.id, alt); return retry; }
        resp = retry;
      }
      if (stripStreamOptions && resp.status === 400 && payloadObj?.stream_options) {
        resp.body?.cancel?.().catch(() => {});
        const clone = applyExtraBody({...payloadObj}, provider);
        delete clone.stream_options;
        resp = await safeFetch(url, {method: "POST", headers: buildHeaders(provider, extraHeaders, key, preferredAuth), body: JSON.stringify(clone), signal});
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
  const cacheRead = u.input_tokens_details?.cached_tokens ?? u.prompt_tokens_details?.cached_tokens;
  const reasoning = u.output_tokens_details?.reasoning_tokens ?? u.completion_tokens_details?.reasoning_tokens;
  if (typeof inTok === "number" && inTok > acc.in) acc.in = inTok;
  if (typeof outTok === "number" && outTok > acc.out) acc.out = outTok;
  if (typeof cacheRead === "number" && cacheRead > acc.cacheRead) acc.cacheRead = cacheRead;
  if (typeof reasoning === "number" && reasoning > acc.reasoning) acc.reasoning = reasoning;
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
    res.writeHead(status, {"Content-Type": ct, "Cache-Control": "no-cache"});
    res.end(raw);
    void recordUsageEvent({...meta, ...finalized, ms: Date.now() - meta.startedAt, status, err: errText});
    return;
  }
  usage.streamed = true;
  res.writeHead(upstream.status, {"Content-Type": ct, "Cache-Control": "no-cache"});
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
  void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, meta.startedAt), ms: Date.now() - meta.startedAt, status: upstream.status});
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

function resolveRouteCandidates(targetId, requestedModel) {
  if (targetId.startsWith("group_")) {
    const gid = targetId.slice("group_".length);
    const group = routingGroups.get(gid);
    if (!group || !group.targets.length) return [];
    let ordered = group.targets
      .map(t => ({provider: providers.get(t.providerId), model: t.model, weight: Math.max(1, Math.min(100, Number(t.weight) || 1))}))
      .filter(x => x.provider && x.provider.enabled);
    if (!ordered.length) return [];
    if (group.strategy === "round_robin" && ordered.length > 1) {
      // 按权重展开轮询池，避免忽略 RoutingGroupTarget.weight
      const expanded = [];
      ordered.forEach((item, idx) => { for (let w = 0; w < item.weight; w++) expanded.push(idx); });
      const cursor = groupCursors.get(gid) || 0;
      groupCursors.set(gid, cursor + 1);
      const startIdx = expanded[cursor % expanded.length];
      ordered = [...ordered.slice(startIdx), ...ordered.slice(0, startIdx)];
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
      };
    });
  }
  const p = providers.get(targetId);
  return (p && p.enabled) ? [{provider: p, model: requestedModel, fromGroup: false}] : [];
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
  const mappedGemini = route === "gemini" ? resolveMappedModel(provider, model) : model;
  const effectiveRequested = reqMetaExtra.requestedModel || (mappedGemini !== model ? model : "");
  model = mappedGemini;
  const startedAt = Date.now();
  const meta = {startedAt, agent: agentLabel, providerId: provider.id, providerName: provider.name, model, requestedModel: effectiveRequested, sessionId: reqMetaExtra.sessionId || "", source: "gateway", billingKind: provider.billingKind, priceRate: provider.priceRate};
  const desired = route === "gemini" ? "google_gemini" : "openai_chat";
  const target = resolveNativeTarget(provider, desired);

  let upstream;
  if (route === "openai-chat" && target.protocol === "openai_chat") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), body, {}, signal, {stripStreamOptions: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (route === "gemini" && target.protocol === "google_gemini") {
    const query = {...provider.queryParams, ...(stream ? {alt: "sse"} : {})};
    const geminiPayload = {...body};
    delete geminiPayload.model;
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(model)}:${action}`, query), geminiPayload, {}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (action === "countTokens") { sendError(res, route, 501, "Exact Gemini token counting requires a Gemini upstream provider"); return; }

  let normalized;
  try { normalized = route === "gemini" ? geminiToResponsesRequest(body, model) : chatToResponsesRequest(body); }
  catch (error) { sendError(res, route, 400, error.message); return; }
  const writer = new CompatibleResponseWriter(res, model, route, stream);
  if (target.protocol === "openai_responses") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), normalized, {Accept: "text/event-stream"}, signal);
  } else if (target.protocol === "openai_chat") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), responsesToChatRequest(normalized).req, {Accept: "text/event-stream"}, signal, {stripStreamOptions: true});
  } else if (target.protocol === "anthropic_messages") {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), responsesToAnthropicRequest(normalized).req, {"anthropic-version": "2023-06-01", Accept: "text/event-stream"}, signal, {isAnthropic: true});
  } else {
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(model.replace(/^models\//, ""))}:streamGenerateContent`, {...provider.queryParams, alt: "sse"}), responsesToGeminiRequest(normalized).req, {Accept: "text/event-stream"}, signal);
  }
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, route, upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  if (target.protocol === "openai_responses") await pipeResponsesToCompatible(wrapped, writer);
  else if (target.protocol === "openai_chat") await pipeChatToResponses(wrapped, res, model, new Map(), new Set(), writer);
  else if (target.protocol === "anthropic_messages") await pipeAnthropicToResponses(wrapped, res, model, new Map(), new Set(), writer);
  else await pipeGeminiToResponses(wrapped, res, model, new Map(), new Set(), writer);
  void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
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
    upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), antReq, {"anthropic-version": "2023-06-01"}, signal, {isAnthropic: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const data = await upstream.json();
    summaryText = Array.isArray(data.content) ? data.content.filter(b => b?.type === "text").map(b => b.text).join("\n") : "";
    inTok = (data.usage?.input_tokens || 0) + (data.usage?.cache_read_input_tokens || 0) + (data.usage?.cache_creation_input_tokens || 0);
    outTok = data.usage?.output_tokens || 0;
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

  if (target.protocol === "openai_responses") {
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), body, {Accept: "text/event-stream"}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    await passthroughWithUsage(upstream, res, meta, allowFailover);
    return;
  }
  if (target.protocol === "openai_chat") {
    const {req: chatReq, namespaceMap, customSet} = responsesToChatRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/chat/completions", provider.queryParams), chatReq, {Accept: "text/event-stream"}, signal, {stripStreamOptions: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await pipeChatToResponses(wrapped, res, body.model, namespaceMap, customSet);
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
    return;
  }
  if (target.protocol === "anthropic_messages") {
    const {req: antReq, namespaceMap, customSet} = responsesToAnthropicRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/v1/messages", provider.queryParams), antReq, {"anthropic-version": "2023-06-01", Accept: "text/event-stream"}, signal, {isAnthropic: true});
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await pipeAnthropicToResponses(wrapped, res, body.model, namespaceMap, customSet);
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
    return;
  }
  const {req: gemReq, namespaceMap, customSet} = responsesToGeminiRequest(body);
  const modelName = String(body.model || "").replace(/^models\//, "");
  const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(modelName)}:streamGenerateContent`, {alt: "sse", ...provider.queryParams}), gemReq, {Accept: "text/event-stream"}, signal);
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "openai", upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  await pipeGeminiToResponses(wrapped, res, body.model, namespaceMap, customSet);
  void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
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

  if (target.protocol === "anthropic_messages") {
    const extra = {"anthropic-version": req.headers["anthropic-version"] || "2023-06-01", Accept: "text/event-stream"};
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
    await pipeChatToAnthropic(wrapped, res, body.model);
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
    return;
  }
  if (target.protocol === "openai_responses") {
    const respReq = anthropicToResponsesRequest(body);
    const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, "/responses", provider.queryParams), respReq, {Accept: "text/event-stream"}, signal);
    if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
    const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
    await pipeResponsesToAnthropic(wrapped, res, body.model);
    void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
    return;
  }
  const gemReq = anthropicToGeminiRequest(body);
  const modelName = String(body.model || "").replace(/^models\//, "");
  const upstream = await fetchWithKeyPool(provider, joinUrl(target.baseUrl, `/models/${encodeURIComponent(modelName)}:streamGenerateContent`, {alt: "sse", ...provider.queryParams}), gemReq, {Accept: "text/event-stream"}, signal);
  if (!upstream.ok) { const err = await readErrorText(upstream); failOrSend(res, "anthropic", upstream.status, err, meta, startedAt, allowFailover); return; }
  const {wrapped, usage} = wrapUpstreamForUsage(upstream, startedAt);
  await pipeGeminiToAnthropic(wrapped, res, body.model);
  void recordUsageEvent({...meta, ...finalizeUsageAccumulator(usage, startedAt), ms: Date.now() - startedAt, status: 200});
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
    if (cleanPath !== "/models") { sendError(res, route, 404, "Not found"); return; }
    const cands = resolveRouteCandidates(targetId, null);
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

  let body;
  try { body = await readJsonBody(req); } catch (e) { sendError(res, ingress, 400, e.message); return; }
  const geminiPathModel = route === "gemini"
    ? (/^\/(?:v1|v1beta)\/models\/([^/]+):|^\/models\/([^/]+):/.exec(subPath)?.[1] || /^\/(?:v1|v1beta)\/models\/([^/]+):|^\/models\/([^/]+):/.exec(subPath)?.[2] || "")
    : "";
  const initialRequestedModel = body.model || (geminiPathModel ? decodeURIComponent(geminiPathModel) : "");
  const candidates = resolveRouteCandidates(targetId, initialRequestedModel);
  if (!candidates.length) { sendError(res, ingress, 404, "该服务商或路由组已删除、已停用或未同步，请在服务商设置中检查"); return; }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error("Provider request timed out")), 30 * 60 * 1000);
  res.once("close", () => { if (!res.writableEnded) controller.abort(); });
  try {
    for (let ci = 0; ci < candidates.length; ci++) {
      const picked = candidates[ci];
      const allowFailover = ci + 1 < candidates.length;
      const requestedModel = picked.model || initialRequestedModel || "";
      const mappedModel = resolveMappedModel(picked.provider, requestedModel);
      const effectiveBody = (route !== "gemini" && mappedModel) ? {...body, model: mappedModel} : body;
      const inferredSessionId = urlSessionId
        || String(req.headers["x-solbraid-session-id"] || req.headers["session_id"] || "")
        || (typeof body?.prompt_cache_key === "string" ? body.prompt_cache_key : "");
      const reqMetaExtra = {
        allowFailover,
        sessionId: inferredSessionId,
        ...(picked.fromGroup && mappedModel ? {targetModel: mappedModel} : {}),
        ...(initialRequestedModel && mappedModel && initialRequestedModel !== mappedModel ? {requestedModel: initialRequestedModel} : {}),
      };
      const attemptStarted = Date.now();
      try {
        if (route === "openai-responses") await handleCodexIngress(picked.provider, subPath, effectiveBody, req, res, controller.signal, reqMetaExtra);
        else if (route === "anthropic") await handleClaudeIngress(picked.provider, subPath, url.searchParams, effectiveBody, req, res, controller.signal, reqMetaExtra);
        else await handleCompatibleIngress(picked.provider, route, subPath, effectiveBody, req, res, controller.signal, route === "gemini" ? "antigravity" : "chat", reqMetaExtra);
        return;
      } catch (err) {
        if (allowFailover && !res.headersSent && !controller.signal.aborted) {
          if (!err?.isFailover) {
            void recordUsageEvent({
              startedAt: attemptStarted,
              agent: route === "anthropic" ? "claude" : (route === "openai-responses" ? "codex" : (route === "gemini" ? "antigravity" : "chat")),
              providerId: picked.provider.id,
              providerName: picked.provider.name,
              model: mappedModel || requestedModel,
              requestedModel: requestedModel && mappedModel && requestedModel !== mappedModel ? requestedModel : "",
              source: "gateway",
              billingKind: picked.provider.billingKind,
              priceRate: picked.provider.priceRate,
              ms: Date.now() - attemptStarted,
              status: 502,
              err: err?.message || String(err),
            });
          }
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
