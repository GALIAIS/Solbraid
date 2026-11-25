// Solbraid 用量账本引擎（对齐 Magpie internal/usage）：
// 以只追加 JSONL (~/.galiais/usage/ledger.jsonl) 记录网关与直连 CLI 的 Token、缓存、思维链、TTFT、吞吐率与估算费用，
// 支持 12 周热力图、多维筛选（周期/日期/Agent/服务商/模型/仅异常/关键词）、明细追踪、CSV 导出与按维度清空。
import fs from "node:fs/promises";
import path from "node:path";

const HOME = process.env.HOME || "/home/agent";
export const USAGE_DIR = path.join(HOME, ".galiais", "usage");
export const LEDGER_FILE = path.join(USAGE_DIR, "ledger.jsonl");
export const LEDGER_LOCK_FILE = path.join(USAGE_DIR, "ledger.lock");

const LOCK_STALE_MS = 10_000;
const LOCK_WAIT_TIMEOUT_MS = 6_000;

/** 跨进程原子文件锁：串行化 provider-gateway 与 agent-hub 对 ledger.jsonl 的并发追加与条件清理，防止丢行。 */
async function withCrossProcessLedgerLock(fn) {
  await fs.mkdir(USAGE_DIR, {recursive: true, mode: 0o700});
  const deadline = Date.now() + LOCK_WAIT_TIMEOUT_MS;
  let handle = null;
  while (!handle) {
    try {
      handle = await fs.open(LEDGER_LOCK_FILE, "wx", 0o600);
      await handle.writeFile(`${process.pid}\n${Date.now()}\n`, "utf8").catch(() => {});
      await handle.close().catch(() => {});
      break;
    } catch (err) {
      if (err?.code !== "EEXIST") throw err;
      const st = await fs.stat(LEDGER_LOCK_FILE).catch(() => null);
      if (st && (Date.now() - st.mtimeMs) > LOCK_STALE_MS) {
        await fs.rm(LEDGER_LOCK_FILE, {force: true}).catch(() => {});
        continue;
      }
      if (Date.now() >= deadline) {
        // 超时后强行清理陈旧锁并重试一次，避免死锁永久阻塞统计
        await fs.rm(LEDGER_LOCK_FILE, {force: true}).catch(() => {});
        handle = await fs.open(LEDGER_LOCK_FILE, "wx", 0o600).catch(() => null);
        if (handle) {
          await handle.writeFile(`${process.pid}\n${Date.now()}\n`, "utf8").catch(() => {});
          await handle.close().catch(() => {});
          break;
        }
        throw new Error("用量账本跨进程锁获取超时");
      }
      await new Promise(r => setTimeout(r, 12 + Math.floor(Math.random() * 12)));
    }
  }
  try {
    return await fn();
  } finally {
    await fs.rm(LEDGER_LOCK_FILE, {force: true}).catch(() => {});
  }
}

// 常见主流模型参考单价（USD / 1M tokens）：[input, output, cacheRead, cacheWriteRatio]
const MODEL_PRICES = [
  [/claude-opus-5|claude-opus-4-6|claude-opus-4-5/i, [5, 25, 0.5, 1.25]],
  [/claude-opus-4/i, [15, 75, 1.5, 1.25]],
  [/claude-sonnet-5|claude-sonnet-4-6|claude-sonnet-4-5|claude-sonnet-4/i, [3, 15, 0.3, 1.25]],
  [/claude-haiku-4-5/i, [1, 5, 0.1, 1.25]],
  [/claude-3-5-haiku/i, [0.8, 4, 0.08, 1.25]],
  [/gpt-6/i, [2.5, 10, 0.25, 1.0]],
  [/gpt-5\.4|gpt-5\.3|gpt-5\.2|gpt-5\.1/i, [2.5, 10, 0.25, 1.0]],
  [/gpt-5(?!-mini|-nano)/i, [1.25, 10, 0.125, 1.0]],
  [/gpt-5-mini|o4-mini|o3-mini/i, [0.25, 2.0, 0.025, 1.0]],
  [/gpt-5-nano/i, [0.05, 0.4, 0.005, 1.0]],
  [/gpt-4o-mini/i, [0.15, 0.6, 0.075, 1.0]],
  [/gpt-4\.1-mini/i, [0.4, 1.6, 0.1, 1.0]],
  [/gpt-4\.1/i, [2.0, 8.0, 0.5, 1.0]],
  [/gpt-oss/i, [0.25, 1.0, 0.05, 1.0]],
  [/gpt-4o|o3|o1/i, [2.5, 10, 0.25, 1.0]],
  [/gemini-.*flash-lite/i, [0.1, 0.4, 0.025, 1.0]],
  [/gemini-.*flash/i, [0.3, 2.5, 0.075, 1.0]],
  [/gemini-4|gemini-pro|gemini-2\.5-pro|gemini-3/i, [1.25, 10, 0.3125, 1.0]],
  [/deepseek-reasoner|deepseek-r1|deepseek-v4-pro/i, [0.55, 2.19, 0.14, 1.0]],
  [/deepseek/i, [0.27, 1.1, 0.07, 1.0]],
  [/kimi|moonshot/i, [0.6, 2.5, 0.15, 1.0]],
  [/glm-5|glm-4/i, [0.6, 2.2, 0.15, 1.0]],
  [/minimax/i, [0.4, 1.6, 0.1, 1.0]],
  [/qwen3-max|qwen3-coder/i, [0.6, 2.4, 0.15, 1.0]],
  [/qwen/i, [0.3, 1.2, 0.08, 1.0]],
  [/grok-4|grok-3/i, [3, 15, 0.75, 1.0]],
  [/doubao/i, [0.3, 1.2, 0.08, 1.0]],
];

function lookupModelPrice(model) {
  const m = String(model || "");
  for (const [re, p] of MODEL_PRICES) {
    if (re.test(m)) return {prices: p, matched: true};
  }
  return {prices: [1.0, 4.0, 0.1, 1.25], matched: false};
}

export function estimateCostUsd(model, usage = {}, priceRate = 1.0, billingKind = "") {
  if (billingKind === "free" || billingKind === "plan") return 0;
  const rate = Number.isFinite(Number(priceRate)) && Number(priceRate) >= 0 ? Number(priceRate) : 1.0;
  const {prices} = lookupModelPrice(model);
  const inTok = Math.max(0, Number(usage.in || 0));
  const outTok = Math.max(0, Number(usage.out || 0));
  const cacheRead = Math.max(0, Number(usage.cacheRead || 0));
  const cacheWrite = Math.max(0, Number(usage.cacheWrite || 0));
  // 统一口径：inTok 为总输入（未缓存输入 + cacheRead + cacheWrite），扣除两者避免缓存部分被重复计费
  const uncachedIn = Math.max(0, inTok - cacheRead - cacheWrite);
  const raw = (uncachedIn * prices[0] + outTok * prices[1] + cacheRead * prices[2] + cacheWrite * prices[0] * prices[3]) / 1_000_000;
  return Number((raw * rate).toFixed(6));
}

/** 纯生成解码吞吐（tok/s）：仅在具备真实流式生成窗口（>=100ms 且输出>=2 tok）时计算，杜绝首包/非流式畸变。 */
export function calcDecodeTps(outTok, genMs) {
  const out = Number(outTok || 0);
  const gen = Number(genMs || 0);
  if (out < 2 || gen < 100) return 0;
  return Number(((out * 1000) / gen).toFixed(1));
}

/** 端到端输出速率（tok/s）：总输出 Token / 总 API 耗时（含首字等待）。 */
export function calcE2eTps(outTok, ms) {
  const out = Number(outTok || 0);
  const dur = Number(ms || 0);
  if (out <= 0 || dur < 50) return 0;
  return Number(((out * 1000) / dur).toFixed(1));
}

function resolveGenMs(entry) {
  const explicitGen = Math.max(0, Math.round(Number(entry.genMs || 0)));
  if (explicitGen > 0) return explicitGen;
  const ms = Math.max(0, Math.round(Number(entry.ms || 0)));
  const ttftMs = Math.max(0, Math.round(Number(entry.ttftMs || 0)));
  const isStreamed = entry.streamed !== false;
  if (isStreamed && ttftMs > 0 && ms > ttftMs + 100) return ms - ttftMs;
  return 0;
}

let appendTail = Promise.resolve();

export function recordUsageEvent(entry) {
  const cacheRead = Math.max(0, Math.round(Number(entry.cacheRead || 0)));
  const cacheWrite = Math.max(0, Math.round(Number(entry.cacheWrite || 0)));
  const rawIn = Math.max(0, Math.round(Number(entry.in || 0)));
  // 若未显式归一化且 rawIn < cacheRead + cacheWrite，则 rawIn 必为未命中缓存增量输入，应相加而非截断覆盖
  const cacheSum = cacheRead + cacheWrite;
  const inTok = (!entry.inNormalized && rawIn > 0 && rawIn < cacheSum)
    ? (rawIn + cacheSum)
    : Math.max(rawIn, cacheSum);
  const outTok = Math.max(0, Math.round(Number(entry.out || 0)));
  const ms = Math.max(0, Math.round(Number(entry.ms || 0)));
  const streamed = entry.streamed !== undefined ? Boolean(entry.streamed) : (Number(entry.ttftMs || 0) > 0 && ms > Number(entry.ttftMs || 0));
  const ttftMs = streamed ? Math.max(0, Math.round(Number(entry.ttftMs || 0))) : 0;
  const genMs = resolveGenMs({ms, ttftMs, genMs: entry.genMs, streamed});
  const priceRate = Number.isFinite(Number(entry.priceRate)) && Number(entry.priceRate) >= 0 ? Number(entry.priceRate) : 1.0;
  const billingKind = String(entry.billingKind || "");
  const exact = entry.exact !== false;
  const {matched: priceMatched} = lookupModelPrice(entry.model);
  const normalizedUsage = {in: inTok, out: outTok, cacheRead, cacheWrite};
  const explicitCost = entry.cost !== undefined && entry.cost !== null && Number.isFinite(Number(entry.cost)) && Number(entry.cost) >= 0;
  const rec = {
    t: entry.t || Date.now(),
    agent: String(entry.agent || "gateway"),
    providerId: String(entry.providerId || "official"),
    providerName: String(entry.providerName || entry.providerId || "官方直连"),
    model: String(entry.model || ""),
    requestedModel: entry.requestedModel ? String(entry.requestedModel) : "",
    in: inTok,
    inNormalized: true,
    out: outTok,
    cacheRead,
    cacheWrite,
    reasoning: Math.max(0, Math.round(Number(entry.reasoning || 0))),
    ms,
    ttftMs,
    genMs,
    streamed,
    exact,
    tps: calcDecodeTps(outTok, genMs),
    e2eTps: calcE2eTps(outTok, ms),
    status: Number(entry.status || 200),
    err: entry.err ? String(entry.err).slice(0, 480) : "",
    billingKind,
    priceRate,
    priceMatched: explicitCost ? true : priceMatched,
    cost: explicitCost ? Number(Number(entry.cost).toFixed(6)) : estimateCostUsd(entry.model, normalizedUsage, priceRate, billingKind),
    sessionId: entry.sessionId ? String(entry.sessionId).slice(0, 96) : "",
    contextUsed: Math.max(0, Math.round(Number(entry.contextUsed || 0))),
    contextWindow: Math.max(0, Math.round(Number(entry.contextWindow || 0))),
    source: entry.source ? String(entry.source) : (String(entry.providerId || "").startsWith("official_") ? "direct" : "gateway"),
    keyIdx: Number.isInteger(entry.keyIdx) ? entry.keyIdx : 0,
  };
  appendTail = appendTail.then(async () => {
    await withCrossProcessLedgerLock(async () => {
      await fs.mkdir(USAGE_DIR, {recursive: true, mode: 0o700});
      await fs.appendFile(LEDGER_FILE, JSON.stringify(rec) + "\n", {encoding: "utf8", mode: 0o600});
    });
  }).catch(err => {
    try { process.stderr.write(`[usage-ledger] write failed: ${err?.message || err}\n`); } catch {}
  });
  return appendTail;
}

export async function readAllUsageRecords(sinceMs = 0) {
  let raw = "";
  try { raw = await fs.readFile(LEDGER_FILE, "utf8"); }
  catch (e) { if (e.code === "ENOENT") return []; throw e; }
  const out = [];
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue;
    try {
      const r = JSON.parse(line);
      if (r && typeof r.t === "number" && r.t >= sinceMs) {
        if (!r.source) r.source = String(r.providerId || "").startsWith("official_") ? "direct" : "gateway";
        const wasLegacyRow = r.streamed === undefined;
        r.cacheRead = Math.max(0, Math.round(Number(r.cacheRead || 0)));
        r.cacheWrite = Math.max(0, Math.round(Number(r.cacheWrite || 0)));
        const rawIn = Math.max(0, Math.round(Number(r.in || 0)));
        const cacheSum = r.cacheRead + r.cacheWrite;
        // 兼容历史旧账本：旧版 Antigravity 直连记录把未命中缓存输入直接写入 in 字段（未含 cacheRead/cacheWrite），按统一总输入口径还原
        if (!r.inNormalized && cacheSum > 0 && ((rawIn > 0 && rawIn < cacheSum) || (wasLegacyRow && r.agent === "antigravity" && r.source === "direct" && rawIn > 0))) {
          r.in = rawIn + cacheSum;
        } else {
          r.in = Math.max(rawIn, cacheSum);
        }
        r.out = Math.max(0, Math.round(Number(r.out || 0)));
        r.reasoning = Math.max(0, Math.round(Number(r.reasoning || 0)));
        r.ms = Math.max(0, Math.round(Number(r.ms || 0)));
        // 订阅/免费账本行统一将实际账单费用归零，避免早期版本残留的估算金额与按量计费混淆
        if (r.billingKind === "plan" || r.billingKind === "free") {
          r.cost = 0;
        }
        // 兼容历史旧账本：旧版本非流式响应曾把 ttftMs 误置等于 ms，统一归零以免污染首字与吞吐统计
        if (wasLegacyRow) {
          r.streamed = r.ttftMs > 0 && r.ms > r.ttftMs + 50;
          if (!r.streamed && r.ttftMs > 0 && r.ttftMs >= r.ms) r.ttftMs = 0;
        }
        r.ttftMs = r.streamed ? Math.max(0, Math.round(Number(r.ttftMs || 0))) : 0;
        r.genMs = resolveGenMs(r);
        r.exact = r.exact !== false;
        r.tps = calcDecodeTps(r.out, r.genMs);
        r.e2eTps = calcE2eTps(r.out, r.ms);
        r.contextUsed = Math.max(0, Math.round(Number(r.contextUsed || 0)));
        r.contextWindow = Math.max(0, Math.round(Number(r.contextWindow || 0)));
        out.push(r);
      }
    } catch {}
  }
  return out;
}

function dayKey(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function periodSinceMs(period) {
  const now = Date.now();
  if (period === "today") {
    const start = new Date(); start.setHours(0, 0, 0, 0);
    return start.getTime();
  }
  const msMap = {"24h": 24 * 3600_000, "7d": 7 * 24 * 3600_000, "30d": 30 * 24 * 3600_000, "all": 0};
  const windowMs = msMap[period] ?? msMap["7d"];
  return windowMs > 0 ? now - windowMs : 0;
}

function isCancelled(r) {
  return Number(r.status) === 499;
}

function isFailed(r) {
  return (Number(r.status || 200) >= 400 || Boolean(r.err)) && !isCancelled(r);
}

function matchesFilters(r, filters = {}) {
  if (filters.day && dayKey(r.t) !== filters.day) return false;
  if (filters.agent && r.agent !== filters.agent) return false;
  if (filters.providerId && r.providerId !== filters.providerId) return false;
  if (filters.model && r.model !== filters.model) return false;
  if (filters.sessionId && (r.sessionId || "") !== filters.sessionId) return false;
  if (filters.source && (r.source || "gateway") !== filters.source) return false;
  if (filters.failedOnly && !isFailed(r)) return false;
  if (filters.search) {
    const q = String(filters.search).trim().toLowerCase();
    if (q) {
      const hay = `${r.model || ""} ${r.requestedModel || ""} ${r.providerName || ""} ${r.providerId || ""} ${r.agent || ""} ${r.sessionId || ""} ${r.err || ""}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
  }
  return true;
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function buildHeatmap(allRecords, days = 84) {
  const byDay = new Map();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  // 使用日历日加减而非固定 86400_000ms 乘法，彻底消除跨夏令时日期漂移
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1));
  const startMs = start.getTime();
  for (const r of allRecords) {
    if (r.t < startMs) continue;
    const dk = dayKey(r.t);
    let cell = byDay.get(dk);
    if (!cell) { cell = {day: dk, requests: 0, tokens: 0, costUsd: 0, errors: 0}; byDay.set(dk, cell); }
    cell.requests++;
    if (isFailed(r)) cell.errors++;
    cell.tokens += (r.in || 0) + (r.out || 0);
    cell.costUsd = Number((cell.costUsd + (r.cost || 0)).toFixed(6));
  }
  let maxTok = 0;
  for (const c of byDay.values()) if (c.tokens > maxTok) maxTok = c.tokens;
  const cells = [];
  for (let i = 0; i < days; i++) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1) + i);
    const dk = dayKey(d.getTime());
    const c = byDay.get(dk) || {day: dk, requests: 0, tokens: 0, costUsd: 0, errors: 0};
    let level = 0;
    if (c.requests > 0) {
      const ratio = maxTok > 0 ? c.tokens / maxTok : 0.25;
      level = ratio > 0.72 ? 4 : ratio > 0.42 ? 3 : ratio > 0.16 ? 2 : 1;
    }
    cells.push({...c, level});
  }
  return cells;
}

function aggregate(records) {
  const sum = {
    requests: 0, succeeded: 0, errors: 0, cancelled: 0, successRate: 100,
    inputTokens: 0, uncachedInputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 0, totalTokens: 0,
    cacheHitRate: 0,
    costUsd: 0, directCount: 0, gatewayCount: 0, estimatedCount: 0,
    avgLatencyMs: 0, p50LatencyMs: 0, p95LatencyMs: 0,
    avgTtftMs: 0, p50TtftMs: 0, p95TtftMs: 0, ttftSampleCount: 0,
    avgTps: 0, medianTps: 0, peakTps: 0, tpsSampleCount: 0, e2eAvgTps: 0,
    peakContextUsed: 0, peakContextWindow: 0, uniqueSessions: 0,
  };
  const latSamples = [], ttftSamples = [], tpsSamples = [];
  let latTotal = 0, ttftTotal = 0;
  let decodeOutTotal = 0, decodeGenMsTotal = 0;
  let e2eOutTotal = 0, e2eMsTotal = 0;
  const sessionIds = new Set();
  const byAgent = new Map(), byProvider = new Map(), byModel = new Map(), byDay = new Map(), bySession = new Map();

  const bump = (map, key, label, r) => {
    let g = map.get(key);
    if (!g) {
      g = {
        id: key, name: label, requests: 0, errors: 0, cancelled: 0,
        inputTokens: 0, uncachedInputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 0,
        costUsd: 0, _lats: [], _ttft: 0, _ttftN: 0, _decOut: 0, _decMs: 0, _e2eOut: 0, _e2eMs: 0,
      };
      map.set(key, g);
    }
    const failed = isFailed(r);
    const cancelled = isCancelled(r);
    g.requests++;
    if (failed) g.errors++;
    else if (cancelled) g.cancelled++;
    const uncached = Math.max(0, (r.in || 0) - (r.cacheRead || 0) - (r.cacheWrite || 0));
    g.inputTokens += r.in || 0;
    g.uncachedInputTokens += uncached;
    g.outputTokens += r.out || 0;
    g.cacheReadTokens += r.cacheRead || 0;
    g.cacheWriteTokens += r.cacheWrite || 0;
    g.reasoningTokens += r.reasoning || 0;
    g.costUsd = Number((g.costUsd + (r.cost || 0)).toFixed(6));
    if (r.ms > 0) g._lats.push(r.ms);
    if (r.streamed && r.ttftMs > 0) { g._ttft += r.ttftMs; g._ttftN++; }
    if (!failed && !cancelled && r.out >= 2 && r.genMs >= 100) {
      g._decOut += r.out;
      g._decMs += r.genMs;
    }
    if (!failed && !cancelled && r.out > 0 && r.ms >= 50) {
      g._e2eOut += r.out;
      g._e2eMs += r.ms;
    }
  };

  for (const r of records) {
    sum.requests++;
    const failed = isFailed(r);
    const cancelled = isCancelled(r);
    if (failed) sum.errors++;
    else if (cancelled) sum.cancelled++;
    else sum.succeeded++;
    if (r.source === "direct") sum.directCount++; else sum.gatewayCount++;
    if (r.exact === false) sum.estimatedCount++;

    const uncached = Math.max(0, (r.in || 0) - (r.cacheRead || 0) - (r.cacheWrite || 0));
    sum.inputTokens += r.in || 0;
    sum.uncachedInputTokens += uncached;
    sum.outputTokens += r.out || 0;
    sum.cacheReadTokens += r.cacheRead || 0;
    sum.cacheWriteTokens += r.cacheWrite || 0;
    sum.reasoningTokens += r.reasoning || 0;
    sum.costUsd = Number((sum.costUsd + (r.cost || 0)).toFixed(6));

    const ctxUsed = r.contextUsed > 0 ? r.contextUsed : ((r.in || 0) + (r.out || 0));
    if (ctxUsed > sum.peakContextUsed) {
      sum.peakContextUsed = ctxUsed;
      if (r.contextWindow > 0) sum.peakContextWindow = r.contextWindow;
    }

    if (r.ms > 0) { latTotal += r.ms; latSamples.push(r.ms); }
    if (r.streamed && r.ttftMs > 0) { ttftTotal += r.ttftMs; ttftSamples.push(r.ttftMs); }
    if (!failed && !cancelled && r.out >= 2 && r.genMs >= 100) {
      decodeOutTotal += r.out;
      decodeGenMsTotal += r.genMs;
      const singleTps = calcDecodeTps(r.out, r.genMs);
      if (singleTps > 0) tpsSamples.push(singleTps);
    }
    if (!failed && !cancelled && r.out > 0 && r.ms >= 50) {
      e2eOutTotal += r.out;
      e2eMsTotal += r.ms;
    }

    bump(byAgent, r.agent || "other", r.agent || "other", r);
    bump(byProvider, r.providerId || "official", r.providerName || r.providerId || "官方直连", r);
    bump(byModel, r.model || "unknown", r.model || "unknown", r);
    bump(byDay, dayKey(r.t), dayKey(r.t), r);

    const sid = r.sessionId ? String(r.sessionId) : "";
    if (sid) sessionIds.add(sid);
    const sKey = sid || "_unassigned";
    let sg = bySession.get(sKey);
    if (!sg) {
      sg = {
        id: sid,
        key: sKey,
        agent: r.agent || "other",
        providerId: r.providerId || "official",
        providerName: r.providerName || r.providerId || "官方直连",
        _models: new Set(),
        requests: 0, errors: 0,
        inputTokens: 0, uncachedInputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 0,
        costUsd: 0, _lat: 0, _latN: 0, _ttft: 0, _ttftN: 0, _decOut: 0, _decMs: 0, _e2eOut: 0, _e2eMs: 0,
        maxContextUsed: 0, contextWindow: 0, firstAt: r.t, lastAt: r.t,
      };
      bySession.set(sKey, sg);
    }
    sg.requests++;
    if (failed) sg.errors++;
    if (r.model) sg._models.add(r.model);
    sg.inputTokens += r.in || 0;
    sg.uncachedInputTokens += uncached;
    sg.outputTokens += r.out || 0;
    sg.cacheReadTokens += r.cacheRead || 0;
    sg.cacheWriteTokens += r.cacheWrite || 0;
    sg.reasoningTokens += r.reasoning || 0;
    sg.costUsd = Number((sg.costUsd + (r.cost || 0)).toFixed(6));
    if (r.ms > 0) { sg._lat += r.ms; sg._latN++; }
    if (r.streamed && r.ttftMs > 0) { sg._ttft += r.ttftMs; sg._ttftN++; }
    if (!failed && !cancelled && r.out >= 2 && r.genMs >= 100) { sg._decOut += r.out; sg._decMs += r.genMs; }
    if (!failed && !cancelled && r.out > 0 && r.ms >= 50) { sg._e2eOut += r.out; sg._e2eMs += r.ms; }
    if (ctxUsed > sg.maxContextUsed) sg.maxContextUsed = ctxUsed;
    if (r.contextWindow > sg.contextWindow) sg.contextWindow = r.contextWindow;
    if (r.t < sg.firstAt) sg.firstAt = r.t;
    if (r.t >= sg.lastAt) {
      sg.lastAt = r.t;
      sg.agent = r.agent || sg.agent;
      sg.providerId = r.providerId || sg.providerId;
      sg.providerName = r.providerName || sg.providerName;
    }
  }

  sum.totalTokens = sum.inputTokens + sum.outputTokens;
  sum.cacheHitRate = sum.inputTokens > 0 ? Number(((sum.cacheReadTokens * 100) / sum.inputTokens).toFixed(1)) : 0;
  sum.avgLatencyMs = latSamples.length > 0 ? Math.round(latTotal / latSamples.length) : 0;
  sum.p50LatencyMs = Math.round(percentile(latSamples, 50));
  sum.p95LatencyMs = Math.round(percentile(latSamples, 95));
  sum.avgTtftMs = ttftSamples.length > 0 ? Math.round(ttftTotal / ttftSamples.length) : 0;
  sum.p50TtftMs = Math.round(percentile(ttftSamples, 50));
  sum.p95TtftMs = Math.round(percentile(ttftSamples, 95));
  sum.ttftSampleCount = ttftSamples.length;
  sum.avgTps = decodeGenMsTotal > 0 ? Number(((decodeOutTotal * 1000) / decodeGenMsTotal).toFixed(1)) : 0;
  sum.medianTps = Number(percentile(tpsSamples, 50).toFixed(1));
  sum.peakTps = tpsSamples.length > 0 ? Number(Math.max(...tpsSamples).toFixed(1)) : 0;
  sum.tpsSampleCount = tpsSamples.length;
  sum.e2eAvgTps = e2eMsTotal > 0 ? Number(((e2eOutTotal * 1000) / e2eMsTotal).toFixed(1)) : 0;
  const completedCount = sum.requests - sum.cancelled;
  sum.successRate = completedCount > 0 ? Number(((sum.succeeded * 100) / completedCount).toFixed(1)) : 100;
  sum.uniqueSessions = sessionIds.size;

  const finalize = map => [...map.values()]
    .map(({_lats, _ttft, _ttftN, _decOut, _decMs, _e2eOut, _e2eMs, ...rest}) => ({
      ...rest,
      totalTokens: rest.inputTokens + rest.outputTokens,
      cacheHitRate: rest.inputTokens > 0 ? Number(((rest.cacheReadTokens * 100) / rest.inputTokens).toFixed(1)) : 0,
      avgLatencyMs: _lats.length > 0 ? Math.round(_lats.reduce((a, b) => a + b, 0) / _lats.length) : 0,
      p95LatencyMs: Math.round(percentile(_lats, 95)),
      avgTtftMs: _ttftN > 0 ? Math.round(_ttft / _ttftN) : 0,
      avgTps: _decMs > 0 ? Number(((_decOut * 1000) / _decMs).toFixed(1)) : 0,
      e2eAvgTps: _e2eMs > 0 ? Number(((_e2eOut * 1000) / _e2eMs).toFixed(1)) : 0,
    }))
    .sort((a, b) => b.totalTokens - a.totalTokens);

  const sessions = [...bySession.values()]
    .map(({_models, _lat, _latN, _ttft, _ttftN, _decOut, _decMs, _e2eOut, _e2eMs, ...rest}) => ({
      ...rest,
      models: [..._models].slice(0, 6),
      totalTokens: rest.inputTokens + rest.outputTokens,
      cacheHitRate: rest.inputTokens > 0 ? Number(((rest.cacheReadTokens * 100) / rest.inputTokens).toFixed(1)) : 0,
      avgLatencyMs: _latN > 0 ? Math.round(_lat / _latN) : 0,
      avgTtftMs: _ttftN > 0 ? Math.round(_ttft / _ttftN) : 0,
      avgTps: _decMs > 0 ? Number(((_decOut * 1000) / _decMs).toFixed(1)) : 0,
      e2eAvgTps: _e2eMs > 0 ? Number(((_e2eOut * 1000) / _e2eMs).toFixed(1)) : 0,
    }))
    .sort((a, b) => b.lastAt - a.lastAt)
    .slice(0, 80);

  const daily = finalize(byDay).sort((a, b) => a.id.localeCompare(b.id));
  return {
    totals: sum,
    byAgent: finalize(byAgent),
    byProvider: finalize(byProvider),
    byModel: finalize(byModel).slice(0, 50),
    bySession: sessions,
    daily: daily.slice(-30),
  };
}

export async function queryUsageSummary(params = {}) {
  const period = params.period || "7d";
  const allRecords = await readAllUsageRecords(0);
  const sinceMs = periodSinceMs(period);
  const inPeriod = sinceMs > 0 ? allRecords.filter(r => r.t >= sinceMs) : allRecords;

  // Facets computed from inPeriod so filter pills stay switchable even after narrowing!
  const facetAgents = [...new Set(inPeriod.map(r => r.agent).filter(Boolean))];
  const facetProviderMap = new Map();
  for (const r of inPeriod) {
    if (r.providerId && !facetProviderMap.has(r.providerId)) {
      facetProviderMap.set(r.providerId, {id: r.providerId, name: r.providerName || r.providerId});
    }
  }
  const facetModels = [...new Set(inPeriod.map(r => r.model).filter(Boolean))].slice(0, 40);

  // Heatmap respects agent/provider/model filters (ignoring single-day filter so user can click between days!)
  const heatmapRecords = allRecords.filter(r => matchesFilters(r, {...params, day: ""}));
  const filtered = inPeriod.filter(r => matchesFilters(r, params));
  const limit = Math.min(250, Math.max(20, Number(params.limit || 120)));

  return {
    period,
    ...aggregate(filtered),
    heatmap: buildHeatmap(heatmapRecords, 84),
    facets: {
      agents: facetAgents,
      providers: [...facetProviderMap.values()],
      models: facetModels,
    },
    totalMatched: filtered.length,
    recent: filtered.slice(-limit).reverse(),
  };
}

function csvCell(v) {
  const s = String(v ?? "");
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export async function exportUsageCsv(params = {}) {
  const period = params.period || "all";
  const sinceMs = periodSinceMs(period);
  const records = (await readAllUsageRecords(sinceMs)).filter(r => matchesFilters(r, params)).reverse();
  const header = [
    "timestamp", "agent", "provider_id", "provider_name", "model", "requested_model", "source",
    "status", "input_tokens", "uncached_input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens",
    "reasoning_tokens", "ttft_ms", "gen_ms", "latency_ms", "decode_tps", "e2e_tps", "streamed", "exact",
    "context_used", "context_window", "billing_kind", "price_rate", "cost_usd", "session_id", "error",
  ];
  const lines = [header.join(",")];
  for (const r of records) {
    const uncachedIn = Math.max(0, (r.in || 0) - (r.cacheRead || 0) - (r.cacheWrite || 0));
    lines.push([
      new Date(r.t).toISOString(), r.agent, r.providerId, r.providerName, r.model, r.requestedModel || "",
      r.source || "gateway", r.status, r.in, uncachedIn, r.out, r.cacheRead, r.cacheWrite, r.reasoning,
      r.ttftMs, r.genMs || 0, r.ms, r.tps ?? calcDecodeTps(r.out, r.genMs), r.e2eTps ?? calcE2eTps(r.out, r.ms),
      r.streamed ? "1" : "0", r.exact === false ? "0" : "1", r.contextUsed || 0, r.contextWindow || 0,
      r.billingKind, r.priceRate ?? 1.0, r.cost, r.sessionId || "", r.err || "",
    ].map(csvCell).join(","));
  }
  return {
    csv: lines.join("\n") + "\n",
    count: records.length,
    filename: `solbraid-usage-${period}-${dayKey(Date.now())}.csv`,
  };
}

export async function clearUsageLedger(params = {}) {
  const period = params?.period && params.period !== "all" ? params.period : "";
  const sinceMs = period ? periodSinceMs(period) : 0;
  const hasFilter = Boolean(
    params?.providerId || params?.model || params?.agent || params?.day || params?.sessionId || params?.source ||
    params?.failedOnly || (params?.search && String(params.search).trim()) || sinceMs > 0
  );
  const run = async () => withCrossProcessLedgerLock(async () => {
    if (!hasFilter) {
      await fs.rm(LEDGER_FILE, {force: true}).catch(() => {});
      return {ok: true, clearedAll: true};
    }
    const all = await readAllUsageRecords(0);
    const kept = all.filter(r => !((sinceMs <= 0 || r.t >= sinceMs) && matchesFilters(r, params)));
    const removed = all.length - kept.length;
    await fs.mkdir(USAGE_DIR, {recursive: true, mode: 0o700});
    const tmp = `${LEDGER_FILE}.${process.pid}.${Date.now()}.tmp`;
    await fs.writeFile(tmp, kept.map(r => JSON.stringify(r)).join("\n") + (kept.length ? "\n" : ""), {encoding: "utf8", mode: 0o600});
    await fs.rename(tmp, LEDGER_FILE);
    return {ok: true, clearedAll: false, removed, remaining: kept.length};
  });
  const resultPromise = appendTail.then(run, run);
  appendTail = resultPromise.then(() => {}, () => {});
  return await resultPromise;
}
