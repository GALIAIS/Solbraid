// 跨平台 Chrome / Edge DevTools Protocol (CDP) 浏览器深度接管桥（零外部依赖，使用 Node 原生 WebSocket）
import {spawn} from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

async function fetchCdpTargets(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/list`, {signal: AbortSignal.timeout(4000)});
  if (!res.ok) throw new Error(`CDP 端口 ${port} 响应异常 (${res.status})`);
  const list = await res.json();
  return Array.isArray(list) ? list.filter(t => t.type === "page") : [];
}

function sendCdpCommand(wsUrl, method, params = {}, timeoutMs = 15_000) {
  if (typeof WebSocket !== "function") {
    return Promise.reject(new Error("当前 Node.js 版本不支持全局 WebSocket，请升级至 Node.js >= 22"));
  }
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let settled = false;
    const finish = (err, val) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { ws.close(); } catch {}
      if (err) reject(err); else resolve(val);
    };
    const timer = setTimeout(() => finish(new Error(`CDP 命令 ${method} 超时`)), timeoutMs);
    ws.addEventListener("open", () => {
      ws.send(JSON.stringify({id: 1, method, params}));
    });
    ws.addEventListener("message", ev => {
      let msg;
      try { msg = JSON.parse(String(ev.data || "")); } catch { return; }
      if (msg.id !== 1) return;
      if (msg.error) finish(new Error(msg.error.message || "CDP 执行失败"));
      else finish(null, msg.result || {});
    });
    ws.addEventListener("error", () => finish(new Error("无法建立 CDP WebSocket 连接")));
    ws.addEventListener("close", () => finish(new Error("CDP 连接提前关闭")));
  });
}

function findBrowserExecutable(pref = "auto") {
  const candidates = [];
  if (process.platform === "win32") {
    const pf = process.env["ProgramFiles"] || "C:\\Program Files";
    const pf86 = process.env["ProgramFiles(x86)"] || "C:\\Program Files (x86)";
    const localApp = process.env.LOCALAPPDATA || "";
    const edge = [
      path.join(pf86, "Microsoft\\Edge\\Application\\msedge.exe"),
      path.join(pf, "Microsoft\\Edge\\Application\\msedge.exe"),
    ];
    const chrome = [
      path.join(pf, "Google\\Chrome\\Application\\chrome.exe"),
      path.join(pf86, "Google\\Chrome\\Application\\chrome.exe"),
      localApp ? path.join(localApp, "Google\\Chrome\\Application\\chrome.exe") : "",
    ];
    candidates.push(...(pref === "edge" ? [...edge, ...chrome] : [...chrome, ...edge]));
  } else if (process.platform === "darwin") {
    const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    const edge = "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
    candidates.push(...(pref === "edge" ? [edge, chrome] : [chrome, edge]));
  } else {
    candidates.push("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge");
  }
  return candidates.find(p => p && fs.existsSync(p)) || null;
}

export async function handleBrowserCdp(params = {}, defaultPort = 9222) {
  const act = String(params.action || "tabs").trim().toLowerCase();
  const port = Math.max(1024, Math.min(65535, Number(params.cdpPort) || defaultPort || 9222));

  if (act === "launch") {
    const exe = findBrowserExecutable(params.browser || "auto");
    if (!exe) throw new Error("未在系统常规目录找到 Chrome 或 Edge，请手动使用 --remote-debugging-port=9222 启动浏览器");
    const profileDir = path.join(os.homedir(), ".solbraid-pc-agent", `cdp-profile-${port}`);
    fs.mkdirSync(profileDir, {recursive: true});
    const args = [`--remote-debugging-port=${port}`, `--user-data-dir=${profileDir}`, "--no-first-run"];
    if (params.url) args.push(String(params.url));
    const child = spawn(exe, args, {detached: true, stdio: "ignore"});
    child.unref();
    return {ok: true, launched: exe, cdpPort: port, profileDir};
  }

  let tabs;
  try {
    tabs = await fetchCdpTargets(port);
  } catch (err) {
    throw new Error(`未检测到本机 CDP 浏览器（127.0.0.1:${port}）：${err.message}。可先调用 solbraid_pc_browser(action="launch") 启动带调试端口的 Chrome/Edge。`);
  }

  if (act === "tabs") {
    return {
      cdpPort: port,
      tabs: tabs.map(t => ({id: t.id, title: t.title, url: t.url})),
    };
  }

  if (act === "open") {
    const url = String(params.url || "about:blank");
    const r = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(url)}`, {method: "PUT", signal: AbortSignal.timeout(6000)});
    const tab = await r.json().catch(() => ({}));
    return {ok: true, id: tab.id, url: tab.url || url, title: tab.title || ""};
  }

  const target = (params.targetId ? tabs.find(t => t.id === params.targetId) : tabs[0]);
  if (!target || !target.webSocketDebuggerUrl) throw new Error("未找到可用的浏览器页面标签");
  const wsUrl = target.webSocketDebuggerUrl;

  if (act === "navigate") {
    const url = String(params.url || "").trim();
    if (!url) throw new Error("navigate 需提供 url");
    const res = await sendCdpCommand(wsUrl, "Page.navigate", {url});
    return {ok: true, targetId: target.id, url, frameId: res.frameId || ""};
  }

  if (act === "eval") {
    const expression = String(params.expression || "").trim();
    if (!expression) throw new Error("eval 需提供 expression");
    const res = await sendCdpCommand(wsUrl, "Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
    if (res.exceptionDetails) {
      throw new Error(res.exceptionDetails.exception?.description || res.exceptionDetails.text || "JS 执行异常");
    }
    return {ok: true, targetId: target.id, value: res.result?.value};
  }

  if (act === "read") {
    const expr = `(() => ({ title: document.title, url: location.href, text: (document.body?.innerText || "").slice(0, 16000), links: Array.from(document.querySelectorAll("a[href]")).slice(0, 40).map(a => ({ text: (a.innerText || "").trim().slice(0, 80), href: a.href })) }))()`;
    const res = await sendCdpCommand(wsUrl, "Runtime.evaluate", {expression: expr, returnByValue: true});
    return {ok: true, targetId: target.id, ...(res.result?.value || {})};
  }

  if (act === "click") {
    const sel = String(params.selector || "").trim();
    if (!sel) throw new Error("click 需提供 CSS selector");
    const expr = `(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return {ok:false, error:"未匹配到元素"}; el.scrollIntoView({block:"center"}); el.click(); return {ok:true, tag:el.tagName, text:(el.innerText||"").trim().slice(0,80)}; })()`;
    const res = await sendCdpCommand(wsUrl, "Runtime.evaluate", {expression: expr, returnByValue: true});
    if (!res.result?.value?.ok) throw new Error(res.result?.value?.error || "点击失败");
    return {targetId: target.id, ...res.result.value};
  }

  if (act === "type") {
    const sel = String(params.selector || "").trim();
    const txt = String(params.text ?? "");
    if (!sel) throw new Error("type 需提供 CSS selector");
    const expr = `(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return {ok:false, error:"未匹配到输入框"}; el.focus(); const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value")?.set; if (setter) setter.call(el, ${JSON.stringify(txt)}); else el.value = ${JSON.stringify(txt)}; el.dispatchEvent(new Event("input", {bubbles:true})); el.dispatchEvent(new Event("change", {bubbles:true})); return {ok:true, tag:el.tagName}; })()`;
    const res = await sendCdpCommand(wsUrl, "Runtime.evaluate", {expression: expr, returnByValue: true});
    if (!res.result?.value?.ok) throw new Error(res.result?.value?.error || "输入失败");
    return {targetId: target.id, ...res.result.value};
  }

  if (act === "screenshot") {
    const res = await sendCdpCommand(wsUrl, "Page.captureScreenshot", {format: "jpeg", quality: 75});
    return {
      ok: true,
      targetId: target.id,
      title: target.title,
      url: target.url,
      mimeType: "image/jpeg",
      imageBase64: res.data || "",
    };
  }

  throw new Error(`未知 CDP 浏览器动作：${act}`);
}
