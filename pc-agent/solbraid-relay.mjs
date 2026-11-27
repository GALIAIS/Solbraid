#!/usr/bin/env node
// Solbraid 轻量公网反连中继服务器（零外部依赖，支持无公网 IP / NAT 后电脑反向连接）
// 中继密钥验证通道身份。命令和结果的签名分别由电脑和手机验证。
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import {createHmac, randomUUID, timingSafeEqual} from "node:crypto";

const args = process.argv.slice(2);
let host = "0.0.0.0";
let port = 29845;
let secret = process.env.SOLBRAID_RELAY_SECRET || "";
let tlsEnabled = false;
let certFile = "";
let keyFile = "";
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--host") host = args[++i] || host;
  else if (args[i] === "--port") port = Number(args[++i]) || port;
  else if (args[i] === "--secret") secret = args[++i] || "";
  else if (args[i] === "--tls") tlsEnabled = true;
  else if (args[i] === "--cert") certFile = args[++i] || "";
  else if (args[i] === "--key") keyFile = args[++i] || "";
}

const MAX_PENDING_PER_CHANNEL = 32;
const MAX_CHANNELS = 512;
const CHANNEL_IDLE_TTL_MS = 10 * 60_000;
const NONCE_TTL_MS = 180_000;

function readBody(req, maxBytes = 32 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", c => {
      total += c.length;
      if (total > maxBytes) { req.destroy(new Error("Payload too large")); return; }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

export function verifyRelayChannelAuth(headers, relaySecret, pairId, endpoint, seenNonces = null) {
  if (!relaySecret) return {ok: false, error: "中继服务器未配置密钥"};
  const tsRaw = String(headers["x-solbraid-relay-ts"] || "").trim();
  const nonce = String(headers["x-solbraid-relay-nonce"] || "").trim();
  const sig = String(headers["x-solbraid-relay-auth"] || "").trim().toLowerCase();
  if (!tsRaw || !nonce || !sig) return {ok: false, error: "中继认证信息不完整，请更新电脑代理并检查密钥"};
  const ts = Number(tsRaw);
  const now = Date.now();
  if (!Number.isFinite(ts) || Math.abs(now - ts) > 120_000) {
    return {ok: false, error: "中继鉴权时间戳超出窗口（±120s）"};
  }
  const msg = `${tsRaw}.${nonce}.${pairId}.${endpoint}`;
  const expected = createHmac("sha256", relaySecret).update(msg).digest("hex");
  const a = Buffer.from(sig, "utf8");
  const b = Buffer.from(expected, "utf8");
  if (a.length !== b.length || !timingSafeEqual(a, b)) {
    return {ok: false, error: "中继通道鉴权签名无效"};
  }
  if (seenNonces) {
    const key = `${pairId}:${endpoint}:${nonce}`;
    if (seenNonces.has(key)) {
      return {ok: false, error: "检测到中继重放请求（重复 nonce）"};
    }
    if (seenNonces.size > 10_000) {
      for (const [k, seenAt] of seenNonces) {
        if (now - seenAt > NONCE_TTL_MS) seenNonces.delete(k);
      }
    }
    seenNonces.set(key, now);
  }
  return {ok: true};
}

export function createRelayServer(options = {}) {
  const relaySecret = String(options.secret ?? "").trim();
  if (!relaySecret) throw new Error("请通过 --secret 或 SOLBRAID_RELAY_SECRET 配置中继密钥");
  const channels = new Map(); // pairId -> { pollRes, pendingJobs: [], waitingReplies: Map<reqId, {res, timer}>, lastSeen }
  const seenNonces = new Map();

  function getChannel(pairId) {
    let ch = channels.get(pairId);
    if (!ch) {
      if (channels.size >= MAX_CHANNELS) return null;
      ch = {pollRes: null, pendingJobs: [], waitingReplies: new Map(), lastSeen: Date.now()};
      channels.set(pairId, ch);
    }
    return ch;
  }

  function flushNextJob(ch) {
    if (!ch.pollRes || ch.pendingJobs.length === 0) return;
    const res = ch.pollRes;
    ch.pollRes = null;
    const job = ch.pendingJobs.shift();
    try {
      res.writeHead(200, {"content-type": "application/json"});
      res.end(JSON.stringify(job));
    } catch {}
  }

  const gcTimer = setInterval(() => {
    const now = Date.now();
    for (const [id, ch] of channels) {
      if (!ch.pollRes && ch.waitingReplies.size === 0 && ch.pendingJobs.length === 0 && now - ch.lastSeen > CHANNEL_IDLE_TTL_MS) {
        channels.delete(id);
      }
    }
    for (const [k, seenAt] of seenNonces) {
      if (now - seenAt > NONCE_TTL_MS) seenNonces.delete(k);
    }
  }, 60_000);
  gcTimer.unref?.();

  const handler = async (req, res) => {
    const url = new URL(req.url || "/", "http://127.0.0.1");
    const m = /^\/relay\/([^/]+)\/(poll|reply|rpc|health)$/.exec(url.pathname);
    if (!m) {
      res.writeHead(404, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: false, error: "Not Found"}));
      return;
    }
    let pairId;
    try {
      pairId = decodeURIComponent(m[1]);
    } catch {
      res.writeHead(400, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: false, error: "Invalid pairId"}));
      return;
    }
    const endpoint = m[2];

    const auth = verifyRelayChannelAuth(req.headers, relaySecret, pairId, endpoint, seenNonces);
    if (!auth.ok) {
      res.writeHead(401, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: false, error: auth.error}));
      return;
    }

    if (endpoint === "health") {
      const existing = channels.get(pairId);
      const online = Boolean(existing && (existing.pollRes || (Date.now() - existing.lastSeen < 35_000)));
      res.writeHead(200, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: true, pairId, agentOnline: online, authRequired: Boolean(relaySecret)}));
      return;
    }

    const ch = getChannel(pairId);
    if (!ch) {
      res.writeHead(503, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: false, error: "中继服务器活动频道数已达上限，请稍后重试"}));
      return;
    }

    if (endpoint === "poll" && req.method === "GET") {
      ch.lastSeen = Date.now();
      if (ch.pollRes) {
        try { ch.pollRes.writeHead(204); ch.pollRes.end(); } catch {}
      }
      ch.pollRes = res;
      const timer = setTimeout(() => {
        if (ch.pollRes === res) {
          ch.pollRes = null;
          try { res.writeHead(204); res.end(); } catch {}
        }
      }, 25_000);
      res.once("close", () => {
        clearTimeout(timer);
        if (ch.pollRes === res) ch.pollRes = null;
      });
      flushNextJob(ch);
      return;
    }

    if (endpoint === "reply" && req.method === "POST") {
      ch.lastSeen = Date.now();
      const raw = await readBody(req).catch(() => "");
      let payload;
      try { payload = JSON.parse(raw); } catch { res.writeHead(400); res.end(); return; }
      const waiter = ch.waitingReplies.get(payload.reqId);
      if (waiter) {
        ch.waitingReplies.delete(payload.reqId);
        clearTimeout(waiter.timer);
        try {
          waiter.res.writeHead(payload.status || 200, {"content-type": "application/json; charset=utf-8"});
          waiter.res.end(payload.body || "{}");
        } catch {}
      }
      res.writeHead(200, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: true}));
      return;
    }

    if (endpoint === "rpc" && req.method === "POST") {
      if (ch.waitingReplies.size >= MAX_PENDING_PER_CHANNEL) {
        res.writeHead(429, {"content-type": "application/json"});
        res.end(JSON.stringify({ok: false, error: "中继队列已满，请稍后重试"}));
        return;
      }
      const body = await readBody(req).catch(() => "");
      if (ch.waitingReplies.size >= MAX_PENDING_PER_CHANNEL) {
        res.writeHead(429); res.end(); return;
      }
      const reqId = randomUUID();
      const forwardHeaders = {};
      for (const k of ["x-solbraid-timestamp", "x-solbraid-nonce", "x-solbraid-signature"]) {
        if (req.headers[k]) forwardHeaders[k] = String(req.headers[k]);
      }
      const dropPending = () => {
        ch.waitingReplies.delete(reqId);
        const index = ch.pendingJobs.findIndex(job => job.reqId === reqId);
        if (index >= 0) ch.pendingJobs.splice(index, 1);
      };
      let waitMs = 45_000;
      try {
        const rpc = JSON.parse(body);
        if (rpc.method === "pc/shell") waitMs = Math.max(waitMs, Math.min(180, Number(rpc.params?.timeoutSec) || 30) * 1000 + 5_000);
      } catch { res.writeHead(400); res.end(); return; }
      if (res.destroyed) return;
      const timer = setTimeout(() => {
        dropPending();
        try {
          res.writeHead(504, {"content-type": "application/json"});
          res.end(JSON.stringify({ok: false, error: "等待电脑代理通过中继响应超时（请确认电脑端正在运行 --relay）"}));
        } catch {}
      }, waitMs);
      ch.waitingReplies.set(reqId, {res, timer});
      res.once("close", () => {
        clearTimeout(timer);
        dropPending();
      });
      ch.pendingJobs.push({reqId, headers: forwardHeaders, body});
      flushNextJob(ch);
      return;
    }

    res.writeHead(405, {"content-type": "application/json"});
    res.end(JSON.stringify({ok: false, error: "Method Not Allowed"}));
  };

  let srv;
  if (options.tls) {
    if (!options.certFile || !options.keyFile) throw new Error("开启中继 TLS 需要同时指定 --cert 和 --key");
    srv = https.createServer({cert: fs.readFileSync(options.certFile), key: fs.readFileSync(options.keyFile)}, handler);
  } else {
    srv = http.createServer(handler);
  }
  srv.on("close", () => clearInterval(gcTimer));
  return srv;
}

const isMain = process.argv[1] && process.argv[1].endsWith("solbraid-relay.mjs");
if (isMain) {
  const srv = createRelayServer({secret, tls: tlsEnabled, certFile, keyFile});
  srv.listen(port, host, () => {
    const scheme = tlsEnabled && certFile && keyFile ? "https" : "http";
    console.log(`中继服务已启动：${scheme}://${host}:${port}`);
  });
}
