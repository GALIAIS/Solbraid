#!/usr/bin/env node
// Solbraid 跨平台电脑端 Computer Use 守护代理（Windows 10/11 + macOS + Linux，零第三方依赖）
// 支持：LAN 直连 / USB adb reverse / SSH 端口转发 / 公网 TLS / 反连中继（--relay）
// 安全：256-bit 配对令牌 + HMAC-SHA256 时间戳/Nonce 防重放 + 三档权限护栏 + 急停文件 STOP + 审计日志
import fs from "node:fs";
import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import https from "node:https";
import {createHash, createHmac, randomBytes, timingSafeEqual} from "node:crypto";
import {collectSystemInfo, handleFilesAction, runShellCommand} from "./backends/common.mjs";
import {handleBrowserCdp} from "./backends/browser-cdp.mjs";
import * as winBackend from "./backends/windows.mjs";
import * as macBackend from "./backends/macos.mjs";
import * as linuxBackend from "./backends/linux.mjs";

const CONFIG_DIR = path.join(os.homedir(), ".solbraid-pc-agent");
const CONFIG_FILE = path.join(CONFIG_DIR, "config.json");
const STOP_FILE = path.join(CONFIG_DIR, "STOP");
const AUDIT_FILE = path.join(CONFIG_DIR, "audit.jsonl");

const VALID_PERMS = new Set(["observe", "operate", "admin"]);
const HIGH_RISK_SHELL_RE = /\b(rm\s+-rf|rmdir\s+\/s|del\s+\/[fq]|format\s+[a-z]:|diskpart|shutdown|reboot|poweroff|halt|mkfs|dd\s+if=|reg\s+delete|bcdedit|Remove-Item\s+.*-Recurse\s+.*-Force|Stop-Computer|Restart-Computer|Clear-Disk)\b/i;

function parseCliArgs(argv) {
  const opts = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--host") opts.host = argv[++i];
    else if (a === "--port") opts.port = Number(argv[++i]);
    else if (a === "--token") opts.token = argv[++i];
    else if (a === "--permission") opts.permissionLevel = argv[++i];
    else if (a === "--tls") opts.tls = true;
    else if (a === "--cert") opts.certFile = argv[++i];
    else if (a === "--key") opts.keyFile = argv[++i];
    else if (a === "--relay") opts.relayUrl = argv[++i];
    else if (a === "--relay-secret") opts.relaySecret = argv[++i];
    else if (a === "--pair-id") opts.pairId = argv[++i];
    else if (a === "--cdp-port") opts.cdpPort = Number(argv[++i]);
    else if (a === "--config-dir") opts.configDir = argv[++i];
    else if (a === "--ephemeral") opts.ephemeralConfig = true;
  }
  return opts;
}

function selectPlatformBackend() {
  if (process.platform === "win32") return winBackend;
  if (process.platform === "darwin") return macBackend;
  return linuxBackend;
}

function tierRank(t) {
  return t === "admin" ? 3 : t === "operate" ? 2 : 1;
}

function requiredTierFor(method, params = {}) {
  const act = String(params?.action || "").toLowerCase();
  if (method === "agent/ping") return {tier: "observe", highRisk: false};
  if (method === "pc/screenshot") return {tier: "observe", highRisk: false};
  if (method === "pc/ui_tree") return {tier: params?.clickName ? "operate" : "observe", highRisk: false};
  if (method === "pc/window") return {tier: (!act || act === "list" || act === "active") ? "observe" : "operate", highRisk: false};
  if (method === "pc/mouse" || method === "pc/keyboard") return {tier: "operate", highRisk: false};
  if (method === "pc/clipboard") return {tier: act === "set" ? "operate" : "observe", highRisk: false};
  if (method === "pc/process") {
    if (!act || act === "list") return {tier: "observe", highRisk: false};
    if (act === "kill") return {tier: "admin", highRisk: true};
    return {tier: "operate", highRisk: false};
  }
  if (method === "pc/system") {
    if (!act || act === "info" || act === "monitors") return {tier: "observe", highRisk: false};
    if (["lock", "sleep", "restart", "shutdown"].includes(act)) return {tier: "admin", highRisk: true};
    return {tier: "operate", highRisk: false};
  }
  if (method === "pc/files") {
    if (!act || ["list", "stat", "read", "read_base64", "search"].includes(act)) return {tier: "observe", highRisk: false};
    if (act === "delete") return {tier: "admin", highRisk: true};
    return {tier: "operate", highRisk: false};
  }
  if (method === "pc/shell") {
    if (params?.elevated === true || HIGH_RISK_SHELL_RE.test(String(params?.command || ""))) {
      return {tier: "admin", highRisk: true};
    }
    return {tier: "operate", highRisk: false};
  }
  if (method === "pc/browser") {
    return {tier: (!act || ["tabs", "read", "screenshot"].includes(act)) ? "observe" : "operate", highRisk: false};
  }
  return {tier: "operate", highRisk: false};
}

export function createPcAgentApp(options = {}) {
  const cfgDir = options.configDir || CONFIG_DIR;
  const cfgFile = path.join(cfgDir, "config.json");
  const stopFile = path.join(cfgDir, "STOP");
  const auditFile = path.join(cfgDir, "audit.jsonl");

  fs.mkdirSync(cfgDir, {recursive: true, mode: 0o700});
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(cfgFile, "utf8")); } catch {}

  const config = {
    host: options.host || saved.host || "127.0.0.1",
    port: Number(options.port ?? saved.port ?? 29840),
    token: options.token || saved.token || randomBytes(32).toString("hex"),
    permissionLevel: VALID_PERMS.has(options.permissionLevel)
      ? options.permissionLevel
      : (VALID_PERMS.has(saved.permissionLevel) ? saved.permissionLevel : "operate"),
    pairId: options.pairId || saved.pairId || `${os.hostname().toLowerCase().replace(/[^a-z0-9-]+/g, "-")}-${randomBytes(3).toString("hex")}`,
    cdpPort: Number(options.cdpPort || saved.cdpPort || 9222),
    relayUrl: options.relayUrl ?? saved.relayUrl ?? "",
    relaySecret: options.relaySecret ?? saved.relaySecret ?? "",
    tls: Boolean(options.tls ?? saved.tls),
    certFile: options.certFile || saved.certFile || "",
    keyFile: options.keyFile || saved.keyFile || "",
  };
  if (config.relayUrl && !config.relaySecret.trim()) throw new Error("公网中继需要 --relay-secret，请填写中继服务器使用的密钥");
  if (!options.ephemeralConfig) {
    fs.writeFileSync(cfgFile, JSON.stringify(config, null, 2) + "\n", {mode: 0o600});
  }

  const backend = options.backend || selectPlatformBackend();
  const seenNonces = new Map(); // nonce -> ts
  const pruneNonces = now => {
    for (const [k, ts] of seenNonces) {
      if (now - ts > 180_000) seenNonces.delete(k);
    }
  };

  function verifyAuth(headers, rawBody) {
    const tsRaw = String(headers["x-solbraid-timestamp"] || "").trim();
    const nonce = String(headers["x-solbraid-nonce"] || "").trim();
    const sig = String(headers["x-solbraid-signature"] || "").trim().toLowerCase();
    if (!tsRaw || !nonce || !sig) return {ok: false, status: 401, error: "缺少 Solbraid HMAC 认证头"};
    const ts = Number(tsRaw);
    const now = Date.now();
    if (!Number.isFinite(ts) || Math.abs(now - ts) > 120_000) {
      return {ok: false, status: 401, error: "请求时间戳超出允许窗口（±120s）"};
    }
    pruneNonces(now);
    if (seenNonces.has(nonce)) {
      return {ok: false, status: 401, error: "检测到重放请求（重复 nonce）"};
    }
    const bodyHash = createHash("sha256").update(rawBody, "utf8").digest("hex");
    const expected = createHmac("sha256", config.token).update(`${tsRaw}.${nonce}.${bodyHash}`).digest("hex");
    const a = Buffer.from(sig, "utf8");
    const b = Buffer.from(expected, "utf8");
    if (a.length !== b.length || !timingSafeEqual(a, b)) {
      return {ok: false, status: 401, error: "HMAC 签名校验失败"};
    }
    seenNonces.set(nonce, now);
    return {ok: true};
  }

  async function logAudit(entry) {
    if (options.ephemeralConfig) return;
    try {
      await fsp.appendFile(auditFile, JSON.stringify({ts: Date.now(), ...entry}) + "\n", {mode: 0o600});
    } catch {}
  }

  async function dispatchRpc(method, params = {}) {
    if (fs.existsSync(stopFile) && method !== "agent/ping") {
      throw Object.assign(new Error(`电脑端急停文件生效中（${stopFile}），删除该文件后恢复操控`), {status: 403});
    }
    const reqPolicy = requiredTierFor(method, params);
    if (tierRank(config.permissionLevel) < tierRank(reqPolicy.tier)) {
      throw Object.assign(
        new Error(`电脑端当前权限档位为 ${config.permissionLevel}，拒绝执行需要 ${reqPolicy.tier} 权限的 ${method}`),
        {status: 403},
      );
    }
    if (reqPolicy.highRisk && params?.confirmHighRisk !== true) {
      throw Object.assign(new Error(`电脑端拒绝未确认的高危操作：${method}`), {status: 403});
    }

    switch (method) {
      case "agent/ping":
        return {
          ok: true,
          version: "1.0.0",
          platform: process.platform,
          arch: process.arch,
          hostname: os.hostname(),
          user: os.userInfo().username,
          permissionLevel: config.permissionLevel,
          emergencyStopActive: fs.existsSync(stopFile),
          pairId: config.pairId,
        };
      case "pc/screenshot":
        return await backend.screenshot(params);
      case "pc/ui_tree":
        return await backend.uiTree(params);
      case "pc/window":
        return await backend.windowAction(params);
      case "pc/mouse":
        return await backend.mouseAction(params);
      case "pc/keyboard":
        return await backend.keyboardAction(params);
      case "pc/shell":
        return await runShellCommand(params);
      case "pc/process":
        return await backend.processAction(params);
      case "pc/system":
        return await backend.systemAction(params);
      case "pc/files":
        return await handleFilesAction(params);
      case "pc/clipboard":
        return await backend.clipboardAction(params);
      case "pc/browser":
        return await handleBrowserCdp(params, config.cdpPort);
      default:
        throw Object.assign(new Error(`未知电脑代理方法：${method}`), {status: 404});
    }
  }

  async function handleSignedRpcPayload(headers, rawBody) {
    const auth = verifyAuth(headers, rawBody);
    if (!auth.ok) return {status: auth.status, body: JSON.stringify({ok: false, error: auth.error})};
    let req;
    try { req = JSON.parse(rawBody || "{}"); }
    catch { return {status: 400, body: JSON.stringify({ok: false, error: "无效 JSON 请求体"})}; }
    const method = String(req.method || "");
    const params = req.params && typeof req.params === "object" ? req.params : {};
    const reply = (status, result) => {
      const payload = JSON.stringify(result);
      if (req.responseAuth !== 1) return {status, body: payload};
      const signature = createHmac("sha256", config.token)
        .update(`response.${String(headers["x-solbraid-nonce"] || "").trim()}.${status}.${payload}`, "utf8").digest("hex");
      return {status, body: JSON.stringify({payload, signature})};
    };
    const started = Date.now();
    try {
      const data = await dispatchRpc(method, params);
      await logAudit({method, ok: true, ms: Date.now() - started});
      return reply(200, {ok: true, id: req.id, data});
    } catch (err) {
      const status = Number(err?.status) || 500;
      await logAudit({method, ok: false, status, ms: Date.now() - started, error: err?.message || String(err)});
      return reply(status, {ok: false, id: req.id, error: err?.message || String(err)});
    }
  }

  const handler = async (req, res) => {
    const url = new URL(req.url || "/", "http://127.0.0.1");
    if (req.method === "GET" && url.pathname === "/health") {
      res.writeHead(200, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: true, platform: process.platform, hostname: os.hostname(), permissionLevel: config.permissionLevel}));
      return;
    }
    if (req.method !== "POST" || url.pathname !== "/rpc") {
      res.writeHead(404, {"content-type": "application/json"});
      res.end(JSON.stringify({ok: false, error: "Not Found"}));
      return;
    }
    const chunks = [];
    let total = 0;
    req.on("data", c => {
      total += c.length;
      if (total > 24 * 1024 * 1024) { req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", async () => {
      const rawBody = Buffer.concat(chunks).toString("utf8");
      const result = await handleSignedRpcPayload(req.headers, rawBody);
      res.writeHead(result.status, {"content-type": "application/json; charset=utf-8"});
      res.end(result.body);
    });
  };

  let server;
  let tlsFingerprint = "";
  if (config.tls && config.certFile && config.keyFile) {
    const cert = fs.readFileSync(config.certFile);
    const key = fs.readFileSync(config.keyFile);
    server = https.createServer({cert, key}, handler);
    const pemBody = cert.toString("utf8").replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
    tlsFingerprint = createHash("sha256").update(Buffer.from(pemBody, "base64")).digest("hex").toUpperCase();
  } else {
    server = http.createServer(handler);
  }

  let relayStopped = false;
  function relayAuthHeaders(endpoint) {
    if (!config.relaySecret) return {};
    const ts = String(Date.now());
    const nonce = randomBytes(12).toString("hex");
    const sig = createHmac("sha256", config.relaySecret).update(`${ts}.${nonce}.${config.pairId}.${endpoint}`).digest("hex");
    return {"x-solbraid-relay-ts": ts, "x-solbraid-relay-nonce": nonce, "x-solbraid-relay-auth": sig};
  }
  async function startRelayLoop() {
    if (!config.relayUrl) return;
    const base = config.relayUrl.replace(/\/+$/, "");
    const pairEnc = encodeURIComponent(config.pairId);
    while (!relayStopped) {
      try {
        const res = await fetch(`${base}/relay/${pairEnc}/poll`, {
          headers: relayAuthHeaders("poll"),
          signal: AbortSignal.timeout(30_000),
        });
        if (res.status === 204) continue;
        if (!res.ok) { await new Promise(r => setTimeout(r, 2000)); continue; }
        const job = await res.json();
        if (!job?.reqId) continue;
        const result = await handleSignedRpcPayload(job.headers || {}, String(job.body || ""));
        await fetch(`${base}/relay/${pairEnc}/reply`, {
          method: "POST",
          headers: {"content-type": "application/json", ...relayAuthHeaders("reply")},
          body: JSON.stringify({reqId: job.reqId, status: result.status, body: result.body}),
          signal: AbortSignal.timeout(15_000),
        });
      } catch {
        if (!relayStopped) await new Promise(r => setTimeout(r, 2000));
      }
    }
  }

  return {
    config,
    stopFile,
    tlsFingerprint,
    server,
    start: () => new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(config.port, config.host, () => {
        const addr = server.address();
        const actualPort = typeof addr === "object" && addr ? addr.port : config.port;
        config.port = actualPort;
        if (config.relayUrl) void startRelayLoop();
        resolve({host: config.host, port: actualPort, tlsFingerprint, config});
      });
    }),
    stop: () => {
      relayStopped = true;
      return new Promise(resolve => server.close(() => resolve()));
    },
  };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
if (isMain) {
  const cli = parseCliArgs(process.argv.slice(2));
  const app = createPcAgentApp(cli);
  app.start().then(({host, port, tlsFingerprint, config}) => {
    const sys = collectSystemInfo();
    const lanIp = sys.networks[0]?.address || "127.0.0.1";
    console.log("========================================");
    console.log("Solbraid PC Agent 已启动");
    console.log(`平台: ${process.platform} (${process.arch}) | 主机: ${os.hostname()}`);
    console.log(`监听: ${config.tls ? "https" : "http"}://${host}:${port} | 权限档位: ${config.permissionLevel}`);
    if (tlsFingerprint) console.log(`TLS SHA-256 指纹: ${tlsFingerprint}`);
    if (config.relayUrl) console.log(`公网中继: ${config.relayUrl} (pairId=${config.pairId})`);
    console.log(`配对令牌: ${config.token}`);
    if (sys.networks[0]?.mac) console.log(`局域网 IP / MAC (WoL): ${lanIp} / ${sys.networks[0].mac}`);
    console.log(`急停方式: 创建 ${app.stopFile} 或按 Ctrl+C`);
    console.log("----------------------------------------");
    const pairPayload = {
      action: "add",
      id: os.hostname().toLowerCase().replace(/[^a-z0-9-]+/g, "-"),
      name: os.hostname(),
      port,
      mac: sys.networks[0]?.mac || "",
      permissionLevel: config.permissionLevel,
      token: config.token,
    };
    if (config.relayUrl) {
      pairPayload.transport = "relay";
      pairPayload.relayUrl = config.relayUrl;
      pairPayload.pairId = config.pairId;
      if (config.relaySecret) pairPayload.relaySecret = config.relaySecret;
    } else if (host === "127.0.0.1" || host === "localhost") {
      pairPayload.transport = "usb";
      pairPayload.address = "127.0.0.1";
    } else {
      pairPayload.transport = "lan";
      pairPayload.address = lanIp;
    }
    if (config.tls && !config.relayUrl) {
      pairPayload.tls = true;
      if (tlsFingerprint) pairPayload.tlsFingerprint = tlsFingerprint;
    }
    console.log("手机端一键配对参数:");
    console.log(JSON.stringify(pairPayload));
    console.log("========================================");
  }).catch(err => {
    console.error("Solbraid PC Agent 启动失败:", err.message);
    process.exit(1);
  });
}
