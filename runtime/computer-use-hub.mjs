// Solbraid Computer Use 手机端控制中枢（100% 兼容 Root chroot 与免 Root PRoot 双模式，零第三方依赖）。
// 支持多台电脑管理（Windows / macOS / Linux）、多传输模式（LAN直连 / USB adb reverse / SSH 端口转发隧道 / 公网中继）、
// HMAC-SHA256 挑战认证 + 重放防护、三档权限与高危操作拦截、双端急停、Wake-on-LAN 唤醒与完整审计日志。
import fs from "node:fs/promises";
import path from "node:path";
import http from "node:http";
import https from "node:https";
import tls from "node:tls";
import net from "node:net";
import dgram from "node:dgram";
import {spawn} from "node:child_process";
import {createHash, createHmac, randomBytes, randomUUID, timingSafeEqual} from "node:crypto";
import {resolveSafeHomePath} from "./safe-file-io.mjs";
import {
  buildNativeAgent,
  deployNativeOverSsh,
  discoverLanDesktops,
  getBuildStatus,
  installGoToolchain,
} from "./computer-use-builder.mjs";

const VALID_PERMISSIONS = new Set(["observe", "operate", "admin"]);
const VALID_TRANSPORTS = new Set(["lan", "usb", "ssh", "public", "relay"]);
const SAFE_SSH_TARGET_RE = /^[A-Za-z0-9][A-Za-z0-9._@:-]{0,127}$/;
const MCP_FORBIDDEN_HOST_ACTIONS = new Set([
  "add", "update", "remove", "set_default", "set_permission",
  "install_toolchain", "build_native", "deploy_ssh",
]);
const HIGH_RISK_SHELL_RE = /\b(rm\s+-rf|rmdir\s+\/s|del\s+\/[fq]|format\s+[a-z]:|diskpart|shutdown|reboot|poweroff|halt|mkfs|dd\s+if=|reg\s+delete|bcdedit|Remove-Item\s+.*-Recurse\s+.*-Force|Stop-Computer|Restart-Computer|Clear-Disk)\b/i;

function safeId(raw) {
  return String(raw || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48);
}

async function readJsonSafe(file, fallback) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch (err) {
    if (err?.code === "ENOENT") return fallback;
    throw err;
  }
}

async function writeJsonAtomic(file, data, mode = 0o600) {
  await fs.mkdir(path.dirname(file), {recursive: true, mode: 0o700});
  const tmp = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(data, null, 2) + "\n", {mode, flag: "wx"});
    await fs.rename(tmp, file);
  } finally {
    await fs.rm(tmp, {force: true}).catch(() => {});
  }
}

function redactHost(h) {
  if (!h) return null;
  const {token, relaySecret, ...rest} = h;
  return {
    ...rest,
    hasToken: Boolean(token),
    tokenPreview: token ? `${token.slice(0, 4)}…${token.slice(-4)}` : "",
    hasRelaySecret: Boolean(relaySecret),
  };
}

function redactParamsForAudit(params) {
  if (!params || typeof params !== "object") return {};
  const out = {};
  for (const [k, v] of Object.entries(params)) {
    if (/token|password|secret|key|base64|content/i.test(k)) {
      out[k] = typeof v === "string" ? `[redacted:${v.length}B]` : "[redacted]";
    } else if (typeof v === "string" && v.length > 240) {
      out[k] = v.slice(0, 240) + "…";
    } else {
      out[k] = v;
    }
  }
  return out;
}

/** 根据动作和参数计算所需最低权限档位与是否属于高危操作。 */
export function classifyPcActionPolicy(toolAction, params = {}) {
  const act = String(params?.action || "").trim().toLowerCase();
  switch (toolAction) {
    case "screenshot":
    case "ui_tree":
      return {
        tier: params?.clickNodeId || params?.clickName ? "operate" : "observe",
        highRisk: false,
      };
    case "window":
      if (!act || act === "list" || act === "active") return {tier: "observe", highRisk: false};
      if (act === "close") return {tier: "operate", highRisk: false};
      return {tier: "operate", highRisk: false};
    case "mouse":
    case "keyboard":
      return {tier: "operate", highRisk: false};
    case "clipboard":
      return {tier: act === "set" ? "operate" : "observe", highRisk: false};
    case "process":
      if (!act || act === "list" || act === "top") return {tier: "observe", highRisk: false};
      if (act === "kill") return {tier: "admin", highRisk: true, reason: "强制结束电脑进程可能导致未保存数据丢失"};
      return {tier: "operate", highRisk: false};
    case "system":
      if (!act || act === "info" || act === "monitors" || act === "battery") return {tier: "observe", highRisk: false};
      if (act === "volume" || act === "mute") return {tier: "operate", highRisk: false};
      if (["lock", "sleep", "restart", "shutdown"].includes(act)) {
        return {tier: "admin", highRisk: true, reason: `系统电源/锁屏操作 (${act}) 会中断电脑当前会话`};
      }
      return {tier: "operate", highRisk: false};
    case "files":
      if (!act || ["list", "read", "stat", "search", "pull"].includes(act)) return {tier: "observe", highRisk: false};
      if (act === "delete") return {tier: "admin", highRisk: true, reason: "删除电脑端文件/目录不可逆"};
      return {tier: "operate", highRisk: false};
    case "shell": {
      const cmd = String(params?.command || "");
      const elevated = params?.elevated === true;
      if (elevated || HIGH_RISK_SHELL_RE.test(cmd)) {
        return {tier: "admin", highRisk: true, reason: "检测到高危或提权 Shell 命令"};
      }
      return {tier: "operate", highRisk: false};
    }
    case "browser":
      if (!act || ["tabs", "read", "screenshot"].includes(act)) return {tier: "observe", highRisk: false};
      return {tier: "operate", highRisk: false};
    default:
      return {tier: "operate", highRisk: false};
  }
}

function tierRank(tier) {
  return tier === "admin" ? 3 : tier === "operate" ? 2 : 1;
}

/** 生成 HMAC-SHA256 请求认证头（防未授权调用与重放攻击）。 */
export function signPcRequest(token, bodyStr, nowMs = Date.now(), nonce = randomBytes(16).toString("hex")) {
  const ts = String(nowMs);
  const bodyHash = createHash("sha256").update(bodyStr, "utf8").digest("hex");
  const sig = createHmac("sha256", String(token || ""))
    .update(`${ts}.${nonce}.${bodyHash}`)
    .digest("hex");
  return {
    "x-solbraid-timestamp": ts,
    "x-solbraid-nonce": nonce,
    "x-solbraid-signature": sig,
  };
}

/** 构造标准 Wake-on-LAN 魔术包（6 字节 0xFF + 16 遍 6 字节 MAC 地址）。 */
export function buildWolPacket(macAddress) {
  const hex = String(macAddress || "").replace(/[^0-9a-fA-F]/g, "");
  if (hex.length !== 12) throw new Error("无效的 MAC 地址（需 12 位十六进制，如 AA:BB:CC:DD:EE:FF）");
  const macBytes = Buffer.from(hex, "hex");
  const packet = Buffer.alloc(6 + 16 * 6, 0xff);
  for (let i = 0; i < 16; i++) {
    macBytes.copy(packet, 6 + i * 6);
  }
  return packet;
}

/** 发送局域网 Wake-on-LAN UDP 广播魔术包。 */
export async function sendWakeOnLan({mac, broadcast = "255.255.255.255", port = 9, repeat = 3}) {
  const packet = buildWolPacket(mac);
  const dstPort = Math.max(1, Math.min(65535, Number(port) || 9));
  const count = Math.max(1, Math.min(5, Number(repeat) || 3));
  const sock = dgram.createSocket("udp4");
  try {
    await new Promise((resolve, reject) => {
      sock.once("error", reject);
      sock.bind(0, () => {
        try { sock.setBroadcast(true); resolve(); } catch (e) { reject(e); }
      });
    });
    for (let i = 0; i < count; i++) {
      await new Promise((resolve, reject) => {
        sock.send(packet, 0, packet.length, dstPort, broadcast, err => (err ? reject(err) : resolve()));
      });
    }
    return {ok: true, mac: String(mac).toUpperCase(), broadcast, port: dstPort, packetsSent: count};
  } finally {
    try { sock.close(); } catch {}
  }
}

function findFreeLocalPort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      srv.close(() => resolve(port));
    });
  });
}

function waitTcpReady(host, port, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const sock = net.createConnection({host, port});
      const timer = setTimeout(() => { sock.destroy(); retry(); }, 600);
      sock.once("connect", () => { clearTimeout(timer); sock.destroy(); resolve(); });
      sock.once("error", () => { clearTimeout(timer); sock.destroy(); retry(); });
    };
    const retry = () => {
      if (Date.now() >= deadline) return reject(new Error(`等待 SSH 隧道本地端口 ${host}:${port} 就绪超时`));
      setTimeout(attempt, 200);
    };
    attempt();
  });
}

function httpJsonRequest(urlStr, {method = "POST", headers = {}, body = "", timeoutMs = 30_000, tlsFingerprint = "", rejectUnauthorized = true, beforeSend} = {}) {
  return new Promise((resolve, reject) => {
    let parsed;
    try { parsed = new URL(urlStr); } catch { return reject(new Error(`无效的电脑代理地址：${urlStr}`)); }
    const isHttps = parsed.protocol === "https:";
    const mod = isHttps ? https : http;
    const pinnedFp = String(tlsFingerprint || "").replace(/:/g, "").toUpperCase();
    if (pinnedFp && (!isHttps || !/^[A-F0-9]{64}$/.test(pinnedFp))) {
      return reject(new Error("固定证书需要 HTTPS 地址和有效的 SHA-256 指纹"));
    }
    const reqOpts = {
      protocol: parsed.protocol,
      hostname: parsed.hostname,
      port: parsed.port || (isHttps ? 443 : 80),
      path: `${parsed.pathname}${parsed.search}`,
      method,
      headers: {
        ...headers,
        ...(body ? {"content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(body, "utf8")} : {}),
      },
      timeout: timeoutMs,
      ...(isHttps ? {rejectUnauthorized: pinnedFp ? false : rejectUnauthorized} : {}),
    };
    if (isHttps && pinnedFp) {
      reqOpts.agent = null;
      reqOpts.createConnection = (_opts, onCreate) => {
        const sock = tls.connect({
          host: parsed.hostname,
          port: Number(parsed.port) || 443,
          servername: net.isIP(parsed.hostname) ? undefined : parsed.hostname,
          rejectUnauthorized: false,
        });
        let settled = false;
        const fail = err => {
          if (settled) return;
          settled = true;
          try { sock.destroy(); } catch {}
          onCreate(err);
        };
        sock.setTimeout(timeoutMs, () => fail(new Error(`TLS 握手超时 (${Math.round(timeoutMs / 1000)}s)`)));
        sock.once("error", fail);
        sock.once("secureConnect", () => {
          if (settled) return;
          const cert = sock.getPeerCertificate();
          const actualFp = String(cert?.fingerprint256 || "").replace(/:/g, "").toUpperCase();
          if (!actualFp || actualFp !== pinnedFp) {
            fail(new Error(`TLS 证书指纹校验失败（预期 ${pinnedFp.slice(0, 16)}…，实际 ${actualFp.slice(0, 16) || "空"}）`));
            return;
          }
          settled = true;
          sock.removeListener("error", fail);
          sock.setTimeout(0);
          onCreate(null, sock);
        });
      };
    }
    const req = mod.request(reqOpts, res => {
      res.once("error", reject);
      if (isHttps && pinnedFp) {
        const cert = res.socket?.getPeerCertificate?.();
        const actualFp = String(cert?.fingerprint256 || "").replace(/:/g, "").toUpperCase();
        if (!actualFp || actualFp !== pinnedFp) {
          req.destroy();
          return reject(new Error(`TLS 证书指纹校验失败（预期 ${pinnedFp.slice(0, 16)}…，实际 ${actualFp.slice(0, 16) || "空"}）`));
        }
      }
      const chunks = [];
      let total = 0;
      res.on("data", c => {
        total += c.length;
        if (total > 24 * 1024 * 1024) { req.destroy(new Error("电脑代理响应超过 24MB 上限")); return; }
        chunks.push(c);
      });
      res.on("end", () => {
        const raw = Buffer.concat(chunks).toString("utf8");
        let json = null;
        try { json = raw ? JSON.parse(raw) : {}; } catch {}
        resolve({status: res.statusCode || 0, raw, json});
      });
    });
    req.on("timeout", () => req.destroy(new Error(`请求电脑代理超时 (${Math.round(timeoutMs / 1000)}s)`)));
    req.on("error", err => reject(new Error(`无法连接电脑代理 (${parsed.host})：${err.message}`)));
    const send = () => {
      try {
        beforeSend?.();
        req.end(body || undefined);
      } catch (err) { req.destroy(err); }
    };
    req.once("socket", socket => {
      if (isHttps && socket.secureConnecting) socket.once("secureConnect", send);
      else if (socket.connecting) socket.once("connect", send);
      else send();
    });
  });
}

/** 创建手机端 Computer Use 中枢（Root chroot 与免 Root PRoot 通用）。 */
export function createComputerUseHub({home = process.env.HOME || "/home/agent", onNotify = () => {}, onApproveHighRisk = null} = {}) {
  const rootDir = path.join(home, ".galiais", "computer-use");
  const hostsFile = path.join(rootDir, "hosts.json");
  const auditFile = path.join(rootDir, "audit.jsonl");
  const sshTunnels = new Map(); // hostId -> { child, localPort, remotePort, sshAlias, startedAt }
  let stateCache = null;
  let stopGeneration = 0;
  let stateWrite = Promise.resolve();

  async function loadState() {
    if (stateCache) return stateCache;
    const raw = await readJsonSafe(hostsFile, {hosts: [], defaultHostId: "", emergencyStop: false});
    if (stateCache) return stateCache;
    stateCache = {
      hosts: Array.isArray(raw.hosts) ? raw.hosts : [],
      defaultHostId: typeof raw.defaultHostId === "string" ? raw.defaultHostId : "",
      emergencyStop: Boolean(raw.emergencyStop),
    };
    return stateCache;
  }

  async function saveState(st) {
    stateCache = st;
    // 按操作顺序落盘，避免较早的主机设置覆盖较新的急停状态。
    const snapshot = {...st, hosts: st.hosts.map(h => ({...h}))};
    const write = () => writeJsonAtomic(hostsFile, snapshot, 0o600);
    stateWrite = stateWrite.then(write, write);
    await stateWrite;
  }

  async function appendAudit(entry) {
    try {
      await fs.mkdir(rootDir, {recursive: true, mode: 0o700});
      await fs.appendFile(auditFile, JSON.stringify({ts: Date.now(), ...entry}) + "\n", {mode: 0o600});
    } catch {}
  }

  async function readAudit(limit = 50) {
    try {
      const text = await fs.readFile(auditFile, "utf8");
      const lines = text.trim().split("\n").filter(Boolean);
      return lines.slice(-Math.max(1, Math.min(200, Number(limit) || 50))).map(l => {
        try { return JSON.parse(l); } catch { return {raw: l}; }
      }).reverse();
    } catch {
      return [];
    }
  }

  function stopSshTunnel(hostId) {
    const existing = sshTunnels.get(hostId);
    if (!existing) return false;
    sshTunnels.delete(hostId);
    try { existing.child.kill("SIGTERM"); } catch {}
    return true;
  }

  async function ensureSshTunnel(host) {
    const existing = sshTunnels.get(host.id);
    if (existing && existing.child && existing.child.exitCode === null && !existing.child.killed) {
      return existing.localPort;
    }
    const sshTarget = String(host.sshAlias || host.sshHost || "").trim();
    if (!sshTarget) throw new Error(`主机 ${host.name} 使用 ssh 传输模式但未配置 sshAlias / sshHost`);
    if (!SAFE_SSH_TARGET_RE.test(sshTarget)) {
      throw new Error(`SSH 主机名包含非法字符或以连字符开头：${sshTarget}`);
    }
    const remotePort = Number(host.port) || 29840;
    if (!Number.isInteger(remotePort) || remotePort < 1 || remotePort > 65535) {
      throw new Error(`无效的目标端口：${host.port}`);
    }
    const localPort = await findFreeLocalPort();
    const sshArgs = [
      "-N",
      "-o", "ExitOnForwardFailure=yes",
      "-o", "ServerAliveInterval=20",
      "-o", "ServerAliveCountMax=3",
      "-o", "BatchMode=yes",
      "-o", "StrictHostKeyChecking=accept-new",
      "-L", `127.0.0.1:${localPort}:127.0.0.1:${remotePort}`,
    ];
    if (host.sshPort && Number(host.sshPort) !== 22) {
      const sp = Number(host.sshPort);
      if (!Number.isInteger(sp) || sp < 1 || sp > 65535) {
        throw new Error(`无效的 SSH 端口：${host.sshPort}`);
      }
      sshArgs.push("-p", String(sp));
    }
    if (host.sshKeyPath) {
      const safeKeyPath = await resolveSafeHomePath(String(host.sshKeyPath), {cwd: home, allowedRoot: home, allowMissing: false});
      sshArgs.push("-i", safeKeyPath);
    }
    sshArgs.push("--", sshTarget);

    let stderr = "";
    const child = spawn("ssh", sshArgs, {stdio: ["ignore", "ignore", "pipe"]});
    child.stderr?.on("data", d => { if (stderr.length < 2048) stderr += d.toString("utf8"); });
    const entry = {child, localPort, remotePort, sshAlias: sshTarget, startedAt: Date.now()};
    sshTunnels.set(host.id, entry);
    child.once("exit", () => {
      if (sshTunnels.get(host.id) === entry) sshTunnels.delete(host.id);
    });
    try {
      await waitTcpReady("127.0.0.1", localPort, 8000);
      if (child.exitCode !== null) throw new Error(stderr.trim() || `ssh 进程提前退出 (${child.exitCode})`);
      return localPort;
    } catch (err) {
      stopSshTunnel(host.id);
      throw new Error(`建立 SSH 隧道到 ${sshTarget} 失败：${stderr.trim() || err.message}`);
    }
  }

  async function resolveEndpoint(host) {
    const transport = host.transport || "lan";
    const port = Number(host.port) || 29840;
    const scheme = host.tls ? "https" : "http";
    if (transport === "relay") {
      const relayUrl = String(host.relayUrl || "").trim().replace(/\/+$/, "");
      if (!relayUrl) throw new Error(`请为主机「${host.name}」填写中继地址`);
      if (!host.relaySecret) throw new Error("请在主机配置中填写中继密钥");
      const pairId = encodeURIComponent(String(host.pairId || host.id));
      return {rpcUrl: `${relayUrl}/relay/${pairId}/rpc`, healthUrl: `${relayUrl}/relay/${pairId}/health`, via: "relay"};
    }
    if (transport === "usb") {
      const base = `${scheme}://127.0.0.1:${port}`;
      return {rpcUrl: `${base}/rpc`, healthUrl: `${base}/health`, via: "usb(adb-reverse)"};
    }
    if (transport === "ssh") {
      const localPort = await ensureSshTunnel(host);
      const base = `${scheme}://127.0.0.1:${localPort}`;
      return {rpcUrl: `${base}/rpc`, healthUrl: `${base}/health`, via: `ssh(${host.sshAlias || host.sshHost})`};
    }
    if (host.url && /^https?:\/\//i.test(host.url)) {
      const base = host.url.replace(/\/+$/, "");
      return {rpcUrl: `${base}/rpc`, healthUrl: `${base}/health`, via: transport};
    }
    const addr = String(host.address || "127.0.0.1").trim();
    const base = `${scheme}://${addr}:${port}`;
    return {rpcUrl: `${base}/rpc`, healthUrl: `${base}/health`, via: transport};
  }

  async function resolveHost(hostId) {
    const st = await loadState();
    const id = String(hostId || "").trim();
    const found = id
      ? st.hosts.find(h => h.id === id || h.name === id)
      : (st.hosts.find(h => h.id === st.defaultHostId) || st.hosts[0]);
    if (!found) {
      throw new Error(
        st.hosts.length === 0
          ? "尚未添加电脑。请启动电脑代理，并在手机的「电脑操控」中配对。"
          : `未找到电脑主机：${id}`,
      );
    }
    return found;
  }

  async function callPcRpc(host, method, params = {}, timeoutMs = 35_000, beforeSend = () => {}) {
    const ep = await resolveEndpoint(host);
    const requestId = randomUUID();
    const bodyStr = JSON.stringify({id: requestId, method, params, responseAuth: 1});
    const authHeaders = signPcRequest(host.token, bodyStr);
    if (host.transport === "relay" && host.relaySecret) {
      const rts = String(Date.now());
      const rnonce = randomBytes(12).toString("hex");
      const pid = String(host.pairId || host.id);
      authHeaders["x-solbraid-relay-ts"] = rts;
      authHeaders["x-solbraid-relay-nonce"] = rnonce;
      authHeaders["x-solbraid-relay-auth"] = createHmac("sha256", host.relaySecret).update(`${rts}.${rnonce}.${pid}.rpc`).digest("hex");
    }
    const res = await httpJsonRequest(ep.rpcUrl, {
      method: "POST",
      headers: authHeaders,
      body: bodyStr,
      beforeSend,
      timeoutMs,
      tlsFingerprint: host.tlsFingerprint || "",
      rejectUnauthorized: host.tlsInsecure === true ? false : true,
    });
    if (!res.json?.signature && (res.status === 401 || res.status === 403)) {
      throw new Error("认证失败，请检查配对令牌、中继密钥和设备时间");
    }
    const envelope = res.json;
    if (typeof envelope?.payload !== "string" || typeof envelope.signature !== "string") {
      throw new Error("电脑代理未返回签名响应，请更新电脑代理并检查中继配置");
    }
    const expected = createHmac("sha256", host.token)
      .update(`response.${authHeaders["x-solbraid-nonce"]}.${res.status}.${envelope.payload}`, "utf8").digest("hex");
    const actual = Buffer.from(envelope.signature, "utf8");
    const signature = Buffer.from(expected, "utf8");
    if (actual.length !== signature.length || !timingSafeEqual(actual, signature)) {
      throw new Error("电脑代理响应签名无效，已拒绝该结果");
    }
    try { res.json = JSON.parse(envelope.payload); }
    catch { throw new Error("电脑代理响应格式无效"); }
    if (res.json?.id !== requestId) throw new Error("电脑代理响应与请求不匹配");
    if (res.status === 401 || res.status === 403) {
      throw new Error(`电脑代理鉴权被拒绝 (HTTP ${res.status})：${res.json?.error || "请检查配对令牌或电脑端权限档位"}`);
    }
    if (res.status < 200 || res.status >= 300 || !res.json) {
      throw new Error(`电脑代理返回异常 (HTTP ${res.status})：${res.json?.error || res.raw.slice(0, 300)}`);
    }
    if (res.json.ok !== true) {
      throw new Error(res.json.error || "电脑端执行失败");
    }
    return {via: ep.via, data: res.json.data};
  }

  async function resolveSafeTransferPath(rawLocalPath, {allowMissing}) {
    const resolved = await resolveSafeHomePath(String(rawLocalPath || ""), {
      cwd: home,
      allowedRoot: home,
      allowMissing,
    });
    const realHome = await fs.realpath(home).catch(() => path.resolve(home));
    const rel = path.relative(realHome, resolved);
    if (!rel || rel === "." || rel.startsWith("..")) {
      throw new Error(`安全限制：手机端传输路径必须指向 ${home} 内的具体文件`);
    }
    const firstSeg = rel.split(path.sep)[0];
    if (firstSeg === ".galiais" || firstSeg === ".ssh") {
      throw new Error(`安全限制：禁止经由电脑控制通道读写敏感配置目录（~/${firstSeg}）`);
    }
    return resolved;
  }

  async function hostsAction(params = {}, {origin = "ui"} = {}) {
    const act = String(params.action || "list").trim().toLowerCase();
    const st = await loadState();

    if (origin === "mcp") {
      if (MCP_FORBIDDEN_HOST_ACTIONS.has(act)) {
        await appendAudit({action: `host_${act}`, origin, blocked: "mcp_forbidden", ok: false});
        throw new Error(`安全拦截：AI 工具调用无权执行主机配置、部署或权限变更 (${act})，请由用户在 App 界面手动操作`);
      }
      if (act === "emergency_stop" && params.enabled === false) {
        await appendAudit({action: "emergency_stop", origin, enabled: false, blocked: "mcp_forbidden", ok: false});
        throw new Error("安全拦截：AI 工具调用无权解除急停，请由用户在 App 界面手动关闭急停开关");
      }
    }

    if (st.emergencyStop && ["ping", "ssh_tunnel_start", "deploy_ssh", "discover_lan"].includes(act)) {
      await appendAudit({action: `host_${act}`, origin, blocked: "emergency_stop", ok: false});
      throw new Error("Computer Use 急停开关处于激活状态，已拦截远程连接操作。确认安全后请在 App 界面关闭急停开关。");
    }

    if (act === "list") {
      return {
        defaultHostId: st.defaultHostId,
        emergencyStop: st.emergencyStop,
        hosts: st.hosts.map(h => ({
          ...redactHost(h),
          isDefault: h.id === st.defaultHostId,
          sshTunnelActive: sshTunnels.has(h.id),
        })),
      };
    }

    if (act === "emergency_stop") {
      const enabled = params.enabled !== false;
      if (enabled) stopGeneration++;
      st.emergencyStop = enabled;
      await saveState(st);
      await appendAudit({action: "emergency_stop", enabled, ok: true});
      onNotify("computerUse/emergencyStop", {enabled});
      return {ok: true, emergencyStop: st.emergencyStop};
    }

    if (act === "audit") {
      return {ok: true, entries: await readAudit(params.limit || 50)};
    }

    if (act === "add" || act === "update") {
      const id = safeId(params.id || params.name) || `pc-${randomUUID().slice(0, 6)}`;
      const existingIdx = st.hosts.findIndex(h => h.id === id);
      const prev = existingIdx >= 0 ? st.hosts[existingIdx] : null;
      const transport = String(params.transport || prev?.transport || "lan").toLowerCase();
      if (!VALID_TRANSPORTS.has(transport)) throw new Error(`不支持的传输模式：${transport}（可选 lan / usb / ssh / public / relay）`);
      const permissionLevel = String(params.permissionLevel || prev?.permissionLevel || "operate").toLowerCase();
      if (!VALID_PERMISSIONS.has(permissionLevel)) throw new Error(`不支持的权限档位：${permissionLevel}（可选 observe / operate / admin）`);
      const token = String(params.token || prev?.token || "").trim();
      if (!token) throw new Error("必须提供电脑端生成的配对令牌 token（运行 solbraid-pc-agent.mjs 时会输出）");

      const hostObj = {
        id,
        name: String(params.name || prev?.name || id).trim(),
        os: String(params.os || prev?.os || "auto").trim().toLowerCase(),
        transport,
        address: String(params.address ?? prev?.address ?? (transport === "usb" ? "127.0.0.1" : "")).trim(),
        port: Number(params.port || prev?.port || 29840),
        url: String(params.url ?? prev?.url ?? "").trim(),
        relayUrl: String(params.relayUrl ?? prev?.relayUrl ?? "").trim(),
        relaySecret: String(params.relaySecret ?? prev?.relaySecret ?? "").trim(),
        pairId: String(params.pairId ?? prev?.pairId ?? id).trim(),
        engine: String(params.engine ?? prev?.engine ?? "").trim(),
        sshAlias: String(params.sshAlias ?? prev?.sshAlias ?? "").trim(),
        sshHost: String(params.sshHost ?? prev?.sshHost ?? "").trim(),
        sshPort: Number(params.sshPort || prev?.sshPort || 22),
        sshKeyPath: String(params.sshKeyPath ?? prev?.sshKeyPath ?? "").trim(),
        tls: params.tls !== undefined ? Boolean(params.tls) : Boolean(prev?.tls),
        tlsFingerprint: String(params.tlsFingerprint ?? prev?.tlsFingerprint ?? "").trim(),
        tlsInsecure: params.tlsInsecure !== undefined ? Boolean(params.tlsInsecure) : Boolean(prev?.tlsInsecure),
        mac: String(params.mac ?? prev?.mac ?? "").trim(),
        broadcast: String(params.broadcast ?? prev?.broadcast ?? "255.255.255.255").trim(),
        permissionLevel,
        token,
        updatedAt: Date.now(),
      };
      if (transport === "relay" && !hostObj.relaySecret) throw new Error("请填写中继密钥，并确保手机、电脑和中继服务器使用相同密钥");
      if (existingIdx >= 0) st.hosts[existingIdx] = hostObj;
      else st.hosts.push(hostObj);
      if (!st.defaultHostId || params.setDefault === true) st.defaultHostId = hostObj.id;
      await saveState(st);
      await appendAudit({action: `host_${act}`, hostId: hostObj.id, transport, permissionLevel, ok: true});
      onNotify("computerUse/changed", {reason: `host_${act}`, hostId: hostObj.id});
      return {ok: true, host: redactHost(hostObj), defaultHostId: st.defaultHostId};
    }

    if (act === "remove") {
      const host = await resolveHost(params.hostId || params.id);
      stopSshTunnel(host.id);
      st.hosts = st.hosts.filter(h => h.id !== host.id);
      if (st.defaultHostId === host.id) st.defaultHostId = st.hosts[0]?.id || "";
      await saveState(st);
      await appendAudit({action: "host_remove", hostId: host.id, ok: true});
      onNotify("computerUse/changed", {reason: "host_remove", hostId: host.id});
      return {ok: true, removedId: host.id, defaultHostId: st.defaultHostId};
    }

    if (act === "set_default") {
      const host = await resolveHost(params.hostId || params.id);
      st.defaultHostId = host.id;
      await saveState(st);
      onNotify("computerUse/changed", {reason: "set_default", hostId: host.id});
      return {ok: true, defaultHostId: st.defaultHostId};
    }

    if (act === "set_permission") {
      const host = await resolveHost(params.hostId || params.id);
      const lvl = String(params.permissionLevel || "").trim().toLowerCase();
      if (!VALID_PERMISSIONS.has(lvl)) throw new Error("权限档位必须是 observe / operate / admin");
      host.permissionLevel = lvl;
      host.updatedAt = Date.now();
      await saveState(st);
      await appendAudit({action: "host_set_permission", hostId: host.id, permissionLevel: lvl, ok: true});
      onNotify("computerUse/changed", {reason: "set_permission", hostId: host.id});
      return {ok: true, host: redactHost(host)};
    }

    if (act === "ssh_tunnel_start") {
      const host = await resolveHost(params.hostId || params.id);
      const localPort = await ensureSshTunnel(host);
      return {ok: true, hostId: host.id, localPort};
    }

    if (act === "ssh_tunnel_stop") {
      const host = await resolveHost(params.hostId || params.id);
      return {ok: true, hostId: host.id, stopped: stopSshTunnel(host.id)};
    }

    if (act === "ping") {
      const host = await resolveHost(params.hostId || params.id);
      const started = Date.now();
      const {via, data} = await callPcRpc(host, "agent/ping", {}, 10_000);
      let dirty = false;
      if (data?.platform && host.os === "auto") {
        host.os = data.platform;
        dirty = true;
      }
      if (data?.engine && host.engine !== data.engine) {
        host.engine = data.engine;
        dirty = true;
      }
      if (dirty) await saveState(st);
      return {ok: true, hostId: host.id, via, latencyMs: Date.now() - started, remote: data};
    }

    if (act === "discover_lan") {
      const discovered = await discoverLanDesktops({timeoutMs: params.timeoutMs || 1600, port: params.discoveryPort || 29841});
      return {ok: true, hosts: discovered};
    }

    if (act === "build_status") {
      return await getBuildStatus(home);
    }

    if (act === "install_toolchain") {
      return await installGoToolchain({
        home,
        onProgress: evt => onNotify("computerUse/buildProgress", evt),
      });
    }

    if (act === "build_native") {
      const res = await buildNativeAgent({
        home,
        os: params.os,
        arch: params.arch,
        all: params.all === true,
        force: params.force === true,
        onProgress: evt => onNotify("computerUse/buildProgress", evt),
      });
      await appendAudit({action: "build_native", targets: res.results.map(r => `${r.os}/${r.arch}`), ok: true});
      return res;
    }

    if (act === "deploy_ssh") {
      const res = await deployNativeOverSsh({
        home,
        sshAlias: params.sshAlias,
        name: params.name,
        port: params.port || 29840,
        permissionLevel: params.permissionLevel || "operate",
        transport: params.transport || "ssh",
        autoTls: params.autoTls === true,
        installService: params.installService !== false,
        forceRebuild: params.force === true,
        autoInstallToolchain: params.autoInstallToolchain !== false,
        onProgress: evt => onNotify("computerUse/buildProgress", evt),
        registerHost: spec => hostsAction(spec),
        pingHost: hid => hostsAction({action: "ping", hostId: hid}),
      });
      await appendAudit({action: "deploy_ssh", sshAlias: params.sshAlias, remoteOs: res.remoteOs, remoteArch: res.remoteArch, ok: true});
      return res;
    }

    throw new Error(`未知 hosts 动作：${act}`);
  }

  async function execute(toolAction, params = {}, {origin = "ui"} = {}) {
    if (toolAction === "hosts") return await hostsAction(params, {origin});
    if (toolAction === "wake") {
      const st = await loadState();
      if (st.emergencyStop) throw new Error("电脑操控已暂停，请在手机上恢复后重试");
      let mac = String(params.mac || "").trim();
      let broadcast = String(params.broadcast || "255.255.255.255").trim();
      if (!mac && (params.hostId || st.defaultHostId)) {
        const host = await resolveHost(params.hostId);
        mac = host.mac;
        if (host.broadcast) broadcast = host.broadcast;
      }
      if (!mac) throw new Error("请提供电脑网卡 MAC 地址（或在主机配置中预填 mac 字段）");
      const res = await sendWakeOnLan({mac, broadcast, port: params.port || 9, repeat: params.repeat || 3});
      await appendAudit({action: "wake", mac, broadcast, ok: true});
      return res;
    }

    const st = await loadState();
    if (st.emergencyStop) throw new Error("电脑操控已暂停，请在手机上恢复后重试");
    const generation = stopGeneration;

    const host = await resolveHost(params.hostId);
    const policy = classifyPcActionPolicy(toolAction, params);
    const assertControlActive = () => {
      if (stateCache?.emergencyStop || generation !== stopGeneration) throw new Error("电脑操控已停止，请重新发起操作");
      const currentHost = stateCache?.hosts.find(item => item.id === host.id);
      if (currentHost !== host || tierRank(currentHost.permissionLevel || "operate") < tierRank(policy.tier)) {
        throw new Error("主机配置或权限已变更，请重新发起操作");
      }
    };
    if (tierRank(host.permissionLevel || "operate") < tierRank(policy.tier)) {
      await appendAudit({action: toolAction, hostId: host.id, origin, blocked: "permission", required: policy.tier, current: host.permissionLevel, params: redactParamsForAudit(params)});
      throw new Error(`权限不足：主机「${host.name}」当前档位为 ${host.permissionLevel}，此操作需要 ${policy.tier} 权限。`);
    }
    let effectiveParams = params && typeof params === "object" ? {...params} : {};
    if (origin === "mcp") {
      delete effectiveParams.confirmHighRisk;
    }
    if (policy.highRisk && effectiveParams.confirmHighRisk !== true) {
      if (typeof onApproveHighRisk === "function") {
        const summary = toolAction === "shell"
          ? String(effectiveParams.command || "").slice(0, 200)
          : toolAction === "files"
            ? `${effectiveParams.action}: ${effectiveParams.path || ""}`
            : toolAction === "process"
              ? `kill pid=${effectiveParams.pid || ""} name=${effectiveParams.name || ""}`
              : `${toolAction}.${effectiveParams.action || ""}`;
        const decision = await onApproveHighRisk({
          hostId: host.id,
          hostName: host.name,
          action: toolAction,
          subAction: String(effectiveParams.action || ""),
          reason: policy.reason || "高危电脑操作",
          summary,
        }).catch(err => ({approved: false, error: err?.message || "审批已取消"}));
        if (decision?.approved === true) {
          effectiveParams = {...effectiveParams, confirmHighRisk: true};
          await appendAudit({action: toolAction, hostId: host.id, origin, approvedByUser: true, summary});
        } else {
          await appendAudit({action: toolAction, hostId: host.id, origin, blocked: "high_risk_denied", reason: policy.reason, params: redactParamsForAudit(effectiveParams)});
          throw new Error(`用户拒绝高危电脑操作：${policy.reason || toolAction}`);
        }
      } else {
        await appendAudit({action: toolAction, hostId: host.id, origin, blocked: "high_risk_unconfirmed", reason: policy.reason, params: redactParamsForAudit(effectiveParams)});
        throw new Error(`高危操作已拦截：${policy.reason || "该操作具有高风险"}。请由用户在 App 界面审批确认后再执行。`);
      }
    }

    const started = Date.now();
    // 手机工作区 <-> 电脑文件双向同步支持（pull 下载到手机 / push 上传到电脑）
    if (toolAction === "files" && String(effectiveParams.action || "").toLowerCase() === "pull") {
      const localPath = await resolveSafeTransferPath(effectiveParams.localPath, {allowMissing: true});
      const {via, data} = await callPcRpc(host, "pc/files", {...effectiveParams, action: "read_base64"}, 60_000, assertControlActive);
      await fs.mkdir(path.dirname(localPath), {recursive: true});
      const buf = Buffer.from(String(data.base64 || ""), "base64");
      assertControlActive();
      await fs.writeFile(localPath, buf);
      await appendAudit({action: "files.pull", hostId: host.id, via, bytes: buf.length, ms: Date.now() - started, ok: true});
      return {ok: true, hostId: host.id, via, remotePath: data.path, localPath, bytes: buf.length};
    }
    if (toolAction === "files" && String(effectiveParams.action || "").toLowerCase() === "push") {
      const localPath = await resolveSafeTransferPath(effectiveParams.localPath, {allowMissing: false});
      const stat = await fs.stat(localPath);
      if (stat.size > 15 * 1024 * 1024) throw new Error("单次推送文件上限 15MB");
      const base64 = (await fs.readFile(localPath)).toString("base64");
      const {via, data} = await callPcRpc(host, "pc/files", {...effectiveParams, action: "write_base64", base64}, 60_000, assertControlActive);
      await appendAudit({action: "files.push", hostId: host.id, via, bytes: stat.size, ms: Date.now() - started, ok: true});
      return {ok: true, hostId: host.id, via, localPath, remotePath: data.path, bytes: stat.size};
    }

    const rpcTimeout = toolAction === "shell"
      ? Math.max(15_000, Math.min(180, Number(effectiveParams.timeoutSec) || 30) * 1000 + 5_000)
      : 45_000;
    try {
      const {hostId: _hid, ...forwardParams} = effectiveParams || {};
      const {via, data} = await callPcRpc(host, `pc/${toolAction}`, forwardParams, rpcTimeout, assertControlActive);
      await appendAudit({action: toolAction, hostId: host.id, via, params: redactParamsForAudit(forwardParams), ms: Date.now() - started, ok: true});
      if (data && typeof data === "object" && typeof data.imageBase64 === "string" && data.imageBase64.length > 0) {
        const {imageBase64, mimeType, ...rest} = data;
        return {
          ...rest,
          hostId: host.id,
          via,
          _mcpImageBase64: imageBase64,
          _mcpMimeType: mimeType || "image/jpeg",
        };
      }
      return {hostId: host.id, via, ...(data && typeof data === "object" ? data : {result: data})};
    } catch (err) {
      await appendAudit({action: toolAction, hostId: host.id, params: redactParamsForAudit(params), ms: Date.now() - started, ok: false, error: err.message});
      throw err;
    }
  }

  return {
    hosts: hostsAction,
    execute,
    wake: params => execute("wake", params),
    dispose: () => {
      for (const hid of [...sshTunnels.keys()]) stopSshTunnel(hid);
    },
  };
}
