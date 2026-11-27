// macOS 原生桌面操控后端（screencapture + sips / osascript AX 控件树与窗口 / Quartz CGEvent 键鼠 / 剪贴板 / 进程 / 系统控制）
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {randomUUID} from "node:crypto";
import {collectSystemInfo} from "./common.mjs";

const execFileAsync = promisify(execFile);

async function osascript(script, timeout = 15_000) {
  const {stdout} = await execFileAsync("osascript", ["-e", script], {timeout});
  return stdout.trim();
}

export async function screenshot(params = {}) {
  const tmpRaw = path.join(os.tmpdir(), `solbraid-mac-cap-${randomUUID()}.jpg`);
  try {
    const args = ["-x", "-t", "jpg"];
    if (Number.isInteger(params.monitor) && params.monitor >= 0) args.push("-D", String(params.monitor + 1));
    if (params.region && params.region.width > 0 && params.region.height > 0) {
      const r = params.region;
      args.push("-R", `${r.x},${r.y},${r.width},${r.height}`);
    }
    args.push(tmpRaw);
    await execFileAsync("screencapture", args, {timeout: 15_000});
    const maxWidth = Math.max(320, Math.min(2560, Number(params.maxWidth) || 1280));
    await execFileAsync("sips", ["--resampleWidth", String(maxWidth), tmpRaw], {timeout: 10_000}).catch(() => {});
    const buf = await fs.readFile(tmpRaw);
    const dimOut = await execFileAsync("sips", ["-g", "pixelWidth", "-g", "pixelHeight", tmpRaw], {timeout: 5000})
      .then(r => r.stdout).catch(() => "");
    const width = Number(/pixelWidth:\s*(\d+)/.exec(dimOut)?.[1] || maxWidth);
    const height = Number(/pixelHeight:\s*(\d+)/.exec(dimOut)?.[1] || 0);
    return {ok: true, width, height, mimeType: "image/jpeg", imageBase64: buf.toString("base64")};
  } finally {
    await fs.rm(tmpRaw, {force: true}).catch(() => {});
  }
}

export async function uiTree(params = {}) {
  const maxNodes = Math.max(10, Math.min(200, Number(params.maxNodes) || 80));
  const query = String(params.query || "").toLowerCase();
  const script = `
tell application "System Events"
  set frontProc to first application process whose frontmost is true
  set outList to {}
  try
    set win to front window of frontProc
    set elems to entire contents of win
    repeat with e in elems
      if (count of outList) >= ${maxNodes} then exit repeat
      try
        set nm to (name of e as string)
        set rl to (role of e as string)
        set pos to position of e
        set sz to size of e
        set end of outList to rl & "||" & nm & "||" & (item 1 of pos) & "," & (item 2 of pos) & "," & (item 1 of sz) & "," & (item 2 of sz)
      end try
    end repeat
  end try
  return outList
end tell`;
  const raw = await osascript(script, 20_000).catch(e => {
    throw new Error(`读取 macOS 辅助功能树失败（请在 系统设置 → 隐私与安全性 → 辅助功能 中授权终端/Node）：${e.message}`);
  });
  const nodes = raw.split(", ").filter(Boolean).map((entry, idx) => {
    const [role = "", name = "", rect = ""] = entry.split("||");
    const [x = 0, y = 0, w = 0, h = 0] = rect.split(",").map(Number);
    return {id: `ax-${idx}`, controlType: role, name, x, y, width: w, height: h, cx: Math.round(x + w / 2), cy: Math.round(y + h / 2)};
  }).filter(n => !query || n.name.toLowerCase().includes(query) || n.controlType.toLowerCase().includes(query));
  return {ok: true, nodes};
}

export async function windowAction(params = {}) {
  const act = String(params.action || "list").toLowerCase();
  if (act === "list" || act === "active") {
    const out = await osascript(`
tell application "System Events"
  set rows to {}
  repeat with p in (every application process whose background only is false)
    set pname to name of p
    set isFront to frontmost of p
    repeat with w in (windows of p)
      try
        set pos to position of w
        set sz to size of w
        set end of rows to pname & "||" & (name of w) & "||" & isFront & "||" & (item 1 of pos) & "," & (item 2 of pos) & "," & (item 1 of sz) & "," & (item 2 of sz)
      end try
    end repeat
  end repeat
  return rows
end tell`);
    const windows = out.split(", ").filter(Boolean).map(r => {
      const [proc = "", title = "", active = "false", rect = ""] = r.split("||");
      const [x = 0, y = 0, width = 0, height = 0] = rect.split(",").map(Number);
      return {process: proc, title, active: active === "true", x, y, width, height};
    });
    return {ok: true, windows: act === "active" ? windows.filter(w => w.active) : windows};
  }
  const title = String(params.title || "").replace(/"/g, '\\"');
  if (act === "focus" && title) {
    await osascript(`tell application "${title}" to activate`).catch(async () => {
      await osascript(`tell application "System Events" to set frontmost of (first process whose name contains "${title}") to true`);
    });
    return {ok: true, action: act, title};
  }
  if (act === "close" && title) {
    await osascript(`tell application "System Events" to click button 1 of front window of (first process whose name contains "${title}")`);
    return {ok: true, action: act, title};
  }
  return {ok: true, action: act, note: "macOS 窗口操作已提交"};
}

export async function mouseAction(params = {}) {
  const act = String(params.action || "click").toLowerCase();
  const x = Math.round(Number(params.x) || 0);
  const y = Math.round(Number(params.y) || 0);
  const py = `
import Quartz, time, sys
act = ${JSON.stringify(act)}
x, y = ${x}, ${y}
tx, ty = ${Math.round(Number(params.toX) || x)}, ${Math.round(Number(params.toY) || y)}
def post(ev): Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)
if act == "move":
    post(Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventMouseMoved, (x, y), 0))
elif act in ("click", "double_click", "right_click"):
    down = Quartz.kCGEventRightMouseDown if act == "right_click" else Quartz.kCGEventLeftMouseDown
    up = Quartz.kCGEventRightMouseUp if act == "right_click" else Quartz.kCGEventLeftMouseUp
    btn = Quartz.kCGMouseButtonRight if act == "right_click" else Quartz.kCGMouseButtonLeft
    clicks = 2 if act == "double_click" else 1
    for i in range(1, clicks + 1):
        e1 = Quartz.CGEventCreateMouseEvent(None, down, (x, y), btn)
        Quartz.CGEventSetIntegerValueField(e1, Quartz.kCGMouseEventClickState, i)
        post(e1)
        e2 = Quartz.CGEventCreateMouseEvent(None, up, (x, y), btn)
        Quartz.CGEventSetIntegerValueField(e2, Quartz.kCGMouseEventClickState, i)
        post(e2)
elif act == "drag":
    post(Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventLeftMouseDown, (x, y), 0))
    time.sleep(0.05)
    post(Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventLeftMouseDragged, (tx, ty), 0))
    time.sleep(0.05)
    post(Quartz.CGEventCreateMouseEvent(None, Quartz.kCGEventLeftMouseUp, (tx, ty), 0))
elif act == "scroll":
    dy = ${Math.round((Number(params.deltaY) || -120) / 40)}
    post(Quartz.CGEventCreateScrollWheelEvent(None, Quartz.kCGScrollEventUnitLine, 1, dy))
`;
  await execFileAsync("python3", ["-c", py], {timeout: 10_000});
  return {ok: true, action: act, x, y};
}

export async function keyboardAction(params = {}) {
  const act = String(params.action || "type").toLowerCase();
  if (act === "type") {
    const text = String(params.text ?? "");
    const prev = await execFileAsync("pbpaste").then(r => r.stdout).catch(() => "");
    await new Promise((resolve, reject) => {
      const p = execFile("pbcopy", err => (err ? reject(err) : resolve()));
      p.stdin.end(text, "utf8");
    });
    await osascript(`tell application "System Events" to keystroke "v" using command down`);
    if (params.pressEnter) await osascript(`tell application "System Events" to key code 36`);
    if (prev) {
      setTimeout(() => { try { const p = execFile("pbcopy"); p.stdin.end(prev, "utf8"); } catch {} }, 250);
    }
    return {ok: true, action: "type", length: text.length};
  }
  if (act === "key") {
    const map = {enter: 36, return: 36, tab: 48, space: 49, backspace: 51, escape: 53, esc: 53, left: 123, right: 124, down: 125, up: 126};
    const k = String(params.key || "").toLowerCase();
    if (k in map) await osascript(`tell application "System Events" to key code ${map[k]}`);
    else await osascript(`tell application "System Events" to keystroke ${JSON.stringify(k)}`);
    return {ok: true, action: "key", key: params.key};
  }
  if (act === "hotkey") {
    const keys = (Array.isArray(params.keys) ? params.keys : []).map(k => String(k).toLowerCase());
    const mods = [];
    let mainKey = "";
    for (const k of keys) {
      if (["cmd", "command", "meta", "win"].includes(k)) mods.push("command down");
      else if (["ctrl", "control"].includes(k)) mods.push("control down");
      else if (["alt", "option"].includes(k)) mods.push("option down");
      else if (k === "shift") mods.push("shift down");
      else mainKey = k;
    }
    const usingPart = mods.length ? ` using {${mods.join(", ")}}` : "";
    await osascript(`tell application "System Events" to keystroke ${JSON.stringify(mainKey)}${usingPart}`);
    return {ok: true, action: "hotkey", keys};
  }
  throw new Error(`未知键盘动作：${act}`);
}

export async function processAction(params = {}) {
  const act = String(params.action || "list").toLowerCase();
  if (act === "list") {
    const {stdout} = await execFileAsync("ps", ["-axo", "pid,pcpu,pmem,comm"], {timeout: 10_000});
    const q = String(params.query || "").toLowerCase();
    const limit = Math.max(5, Math.min(100, Number(params.limit) || 40));
    const procs = stdout.trim().split("\n").slice(1).map(l => {
      const m = /^\s*(\d+)\s+([\d.]+)\s+([\d.]+)\s+(.+)$/.exec(l);
      return m ? {pid: Number(m[1]), cpu: Number(m[2]), memPct: Number(m[3]), name: m[4].trim()} : null;
    }).filter(Boolean).filter(p => !q || p.name.toLowerCase().includes(q)).sort((a, b) => b.cpu - a.cpu).slice(0, limit);
    return {ok: true, processes: procs};
  }
  if (act === "launch") {
    await execFileAsync("open", [String(params.target || ""), ...(Array.isArray(params.args) ? params.args : [])], {timeout: 10_000});
    return {ok: true, launched: params.target};
  }
  if (act === "kill") {
    if (params.pid) await execFileAsync("kill", ["-9", String(params.pid)]);
    else if (params.name) await execFileAsync("pkill", ["-f", String(params.name)]);
    else throw new Error("kill 需提供 pid 或 name");
    return {ok: true, killed: params.pid || params.name};
  }
  throw new Error(`未知进程动作：${act}`);
}

export async function systemAction(params = {}) {
  const act = String(params.action || "info").toLowerCase();
  if (act === "info" || act === "monitors") return {ok: true, ...collectSystemInfo()};
  if (act === "volume") {
    const lvl = Math.max(0, Math.min(100, Number(params.level) || 50));
    await osascript(`set volume output volume ${lvl}`);
    return {ok: true, level: lvl};
  }
  if (act === "mute") {
    await osascript(`set volume output muted ${params.muted !== false ? "true" : "false"}`);
    return {ok: true, muted: params.muted !== false};
  }
  if (act === "lock") { await execFileAsync("pmset", ["displaysleepnow"]); return {ok: true}; }
  if (act === "sleep") { await execFileAsync("pmset", ["sleepnow"]); return {ok: true}; }
  if (act === "restart") { await osascript(`tell application "System Events" to restart`); return {ok: true}; }
  if (act === "shutdown") { await osascript(`tell application "System Events" to shut down`); return {ok: true}; }
  throw new Error(`未知系统动作：${act}`);
}

export async function clipboardAction(params = {}) {
  const act = String(params.action || "get").toLowerCase();
  if (act === "get") {
    const {stdout} = await execFileAsync("pbpaste", [], {timeout: 5000});
    return {ok: true, text: stdout};
  }
  if (act === "set") {
    const text = String(params.text ?? "");
    await new Promise((resolve, reject) => {
      const p = execFile("pbcopy", err => (err ? reject(err) : resolve()));
      p.stdin.end(text, "utf8");
    });
    return {ok: true, length: text.length};
  }
  throw new Error(`未知剪贴板动作：${act}`);
}
