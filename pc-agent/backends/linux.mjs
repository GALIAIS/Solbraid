// Linux 桌面操控后端（自动适配 X11 与 Wayland：grim/scrot/import 截屏、xdotool/ydotool/wtype 键鼠、wmctrl 窗口、AT-SPI2 控件树、wl-copy/xclip 剪贴板）
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {execFile, spawn} from "node:child_process";
import {promisify} from "node:util";
import {randomUUID} from "node:crypto";
import {collectSystemInfo} from "./common.mjs";

const execFileAsync = promisify(execFile);
const isWayland = () => Boolean(process.env.WAYLAND_DISPLAY);

async function hasCmd(cmd) {
  try { await execFileAsync("which", [cmd], {timeout: 2000}); return true; } catch { return false; }
}

export async function screenshot(params = {}) {
  const tmpPng = path.join(os.tmpdir(), `solbraid-lin-cap-${randomUUID()}.png`);
  try {
    if (isWayland() && await hasCmd("grim")) {
      const args = [];
      if (params.region?.width > 0 && params.region?.height > 0) {
        const r = params.region;
        args.push("-g", `${r.x},${r.y} ${r.width}x${r.height}`);
      }
      args.push(tmpPng);
      await execFileAsync("grim", args, {timeout: 15_000});
    } else if (await hasCmd("scrot")) {
      await execFileAsync("scrot", ["-o", tmpPng], {timeout: 15_000});
    } else if (await hasCmd("import")) {
      await execFileAsync("import", ["-window", "root", tmpPng], {timeout: 15_000});
    } else {
      throw new Error("未检测到截屏工具，请安装 grim（Wayland）或 scrot / imagemagick（X11）");
    }
    const buf = await fs.readFile(tmpPng);
    return {ok: true, mimeType: "image/png", imageBase64: buf.toString("base64")};
  } finally {
    await fs.rm(tmpPng, {force: true}).catch(() => {});
  }
}

export async function uiTree(params = {}) {
  const maxNodes = Math.max(10, Math.min(200, Number(params.maxNodes) || 100));
  const query = String(params.query || "").toLowerCase();
  const py = `
import json
try:
    import pyatspi
except Exception as e:
    print(json.dumps({"ok": False, "error": "需要安装 python3-pyatspi 以启用 Linux AT-SPI2 控件树: " + str(e)}))
    raise SystemExit(0)
desktop = pyatspi.Registry.getDesktop(0)
nodes = []
def walk(acc, depth):
    if depth > 5 or len(nodes) >= ${maxNodes}: return
    try:
        name = acc.name or ""
        role = acc.getRoleName() or ""
        if name:
            comp = acc.queryComponent()
            ext = comp.getExtents(pyatspi.DESKTOP_COORDS)
            if ext.width > 0 and ext.height > 0:
                nodes.append({"id": f"at-{len(nodes)}", "name": name, "controlType": role, "x": ext.x, "y": ext.y, "width": ext.width, "height": ext.height, "cx": ext.x + ext.width // 2, "cy": ext.y + ext.height // 2})
    except Exception:
        pass
    try:
        for i in range(min(acc.childCount, 40)):
            walk(acc.getChildAtIndex(i), depth + 1)
    except Exception:
        pass
for app in desktop:
    walk(app, 0)
print(json.dumps({"ok": True, "nodes": nodes}))
`;
  const {stdout} = await execFileAsync("python3", ["-c", py], {timeout: 15_000});
  const parsed = JSON.parse(stdout.trim() || "{}");
  if (!parsed.ok) throw new Error(parsed.error || "AT-SPI2 读取失败");
  const filtered = (parsed.nodes || []).filter(n => !query || n.name.toLowerCase().includes(query) || n.controlType.toLowerCase().includes(query));
  return {ok: true, nodes: filtered};
}

export async function windowAction(params = {}) {
  const act = String(params.action || "list").toLowerCase();
  if (!(await hasCmd("wmctrl"))) {
    throw new Error("Linux 窗口管理需要安装 wmctrl（如 sudo apt install wmctrl）");
  }
  if (act === "list" || act === "active") {
    const {stdout} = await execFileAsync("wmctrl", ["-lGp"], {timeout: 8000});
    const windows = stdout.trim().split("\n").filter(Boolean).map(line => {
      const m = /^(\S+)\s+(-?\d+)\s+(\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(\d+)\s+\S+\s+(.*)$/.exec(line);
      return m ? {hwnd: m[1], pid: Number(m[3]), x: Number(m[4]), y: Number(m[5]), width: Number(m[6]), height: Number(m[7]), title: m[8]} : null;
    }).filter(Boolean);
    return {ok: true, windows};
  }
  const target = params.hwnd || params.title || "";
  if (!target) throw new Error("需提供 hwnd 或 title");
  const flag = params.hwnd ? ["-i", "-a", target] : ["-a", target];
  if (act === "focus") await execFileAsync("wmctrl", flag);
  else if (act === "close") await execFileAsync("wmctrl", params.hwnd ? ["-i", "-c", target] : ["-c", target]);
  else if (act === "maximize") await execFileAsync("wmctrl", [...(params.hwnd ? ["-i"] : []), "-r", target, "-b", "add,maximized_vert,maximized_horz"]);
  else if (act === "restore") await execFileAsync("wmctrl", [...(params.hwnd ? ["-i"] : []), "-r", target, "-b", "remove,maximized_vert,maximized_horz"]);
  else if (act === "move") await execFileAsync("wmctrl", [...(params.hwnd ? ["-i"] : []), "-r", target, "-e", `0,${params.x ?? -1},${params.y ?? -1},${params.width ?? -1},${params.height ?? -1}`]);
  return {ok: true, action: act, target};
}

export async function mouseAction(params = {}) {
  const act = String(params.action || "click").toLowerCase();
  const x = Math.round(Number(params.x) || 0);
  const y = Math.round(Number(params.y) || 0);
  if (isWayland() && await hasCmd("ydotool")) {
    await execFileAsync("ydotool", ["mousemove", "-a", "-x", String(x), "-y", String(y)]);
    if (act === "click") await execFileAsync("ydotool", ["click", "0xC0"]);
    else if (act === "right_click") await execFileAsync("ydotool", ["click", "0xC1"]);
    else if (act === "double_click") { await execFileAsync("ydotool", ["click", "0xC0"]); await execFileAsync("ydotool", ["click", "0xC0"]); }
    return {ok: true, backend: "ydotool", action: act, x, y};
  }
  if (!(await hasCmd("xdotool"))) throw new Error("未检测到鼠标注入工具，请安装 xdotool（X11）或 ydotool（Wayland）");
  if (act === "move") await execFileAsync("xdotool", ["mousemove", String(x), String(y)]);
  else if (act === "click") await execFileAsync("xdotool", ["mousemove", String(x), String(y), "click", "1"]);
  else if (act === "double_click") await execFileAsync("xdotool", ["mousemove", String(x), String(y), "click", "--repeat", "2", "1"]);
  else if (act === "right_click") await execFileAsync("xdotool", ["mousemove", String(x), String(y), "click", "3"]);
  else if (act === "middle_click") await execFileAsync("xdotool", ["mousemove", String(x), String(y), "click", "2"]);
  else if (act === "drag") await execFileAsync("xdotool", ["mousemove", String(x), String(y), "mousedown", "1", "mousemove", String(Math.round(Number(params.toX) || x)), String(Math.round(Number(params.toY) || y)), "mouseup", "1"]);
  else if (act === "scroll") {
    const btn = (Number(params.deltaY) || -120) > 0 ? "4" : "5";
    const steps = Math.max(1, Math.min(10, Math.round(Math.abs(Number(params.deltaY) || 120) / 80)));
    await execFileAsync("xdotool", ["mousemove", String(x), String(y), "click", "--repeat", String(steps), btn]);
  }
  return {ok: true, backend: "xdotool", action: act, x, y};
}

export async function keyboardAction(params = {}) {
  const act = String(params.action || "type").toLowerCase();
  if (isWayland() && await hasCmd("wtype")) {
    if (act === "type") await execFileAsync("wtype", [String(params.text ?? "")]);
    else if (act === "key") await execFileAsync("wtype", ["-k", String(params.key || "Return")]);
    return {ok: true, backend: "wtype", action: act};
  }
  if (!(await hasCmd("xdotool"))) throw new Error("未检测到键盘注入工具，请安装 xdotool（X11）或 wtype（Wayland）");
  if (act === "type") {
    await execFileAsync("xdotool", ["type", "--delay", "5", "--", String(params.text ?? "")]);
    if (params.pressEnter) await execFileAsync("xdotool", ["key", "Return"]);
  } else if (act === "key") {
    await execFileAsync("xdotool", ["key", String(params.key || "Return")]);
  } else if (act === "hotkey") {
    const combo = (Array.isArray(params.keys) ? params.keys : []).map(k => k.replace(/^Ctrl$/i, "ctrl").replace(/^Win$/i, "Super_L")).join("+");
    await execFileAsync("xdotool", ["key", combo]);
  }
  return {ok: true, backend: "xdotool", action: act};
}

export async function processAction(params = {}) {
  const act = String(params.action || "list").toLowerCase();
  if (act === "list") {
    const {stdout} = await execFileAsync("ps", ["-eo", "pid,pcpu,pmem,comm", "--sort=-pcpu"], {timeout: 10_000});
    const q = String(params.query || "").toLowerCase();
    const limit = Math.max(5, Math.min(100, Number(params.limit) || 40));
    const procs = stdout.trim().split("\n").slice(1).map(l => {
      const m = /^\s*(\d+)\s+([\d.]+)\s+([\d.]+)\s+(.+)$/.exec(l);
      return m ? {pid: Number(m[1]), cpu: Number(m[2]), memPct: Number(m[3]), name: m[4].trim()} : null;
    }).filter(Boolean).filter(p => !q || p.name.toLowerCase().includes(q)).slice(0, limit);
    return {ok: true, processes: procs};
  }
  if (act === "launch") {
    const child = spawn(String(params.target || ""), Array.isArray(params.args) ? params.args : [], {detached: true, stdio: "ignore"});
    child.unref();
    return {ok: true, launched: params.target, pid: child.pid};
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
    if (await hasCmd("pactl")) await execFileAsync("pactl", ["set-sink-volume", "@DEFAULT_SINK@", `${lvl}%`]);
    else await execFileAsync("amixer", ["sset", "Master", `${lvl}%`]);
    return {ok: true, level: lvl};
  }
  if (act === "mute") {
    const m = params.muted !== false ? "1" : "0";
    if (await hasCmd("pactl")) await execFileAsync("pactl", ["set-sink-mute", "@DEFAULT_SINK@", m]);
    else await execFileAsync("amixer", ["sset", "Master", m === "1" ? "mute" : "unmute"]);
    return {ok: true, muted: m === "1"};
  }
  if (act === "lock") { await execFileAsync("loginctl", ["lock-session"]); return {ok: true}; }
  if (act === "sleep") { await execFileAsync("systemctl", ["suspend"]); return {ok: true}; }
  if (act === "restart") { await execFileAsync("systemctl", ["reboot"]); return {ok: true}; }
  if (act === "shutdown") { await execFileAsync("systemctl", ["poweroff"]); return {ok: true}; }
  throw new Error(`未知系统动作：${act}`);
}

export async function clipboardAction(params = {}) {
  const act = String(params.action || "get").toLowerCase();
  if (act === "get") {
    if (isWayland() && await hasCmd("wl-paste")) return {ok: true, text: (await execFileAsync("wl-paste", ["-n"])).stdout};
    if (await hasCmd("xclip")) return {ok: true, text: (await execFileAsync("xclip", ["-selection", "clipboard", "-o"])).stdout};
    throw new Error("请安装 wl-clipboard 或 xclip");
  }
  if (act === "set") {
    const text = String(params.text ?? "");
    const cmd = isWayland() && await hasCmd("wl-copy") ? ["wl-copy", []] : ["xclip", ["-selection", "clipboard"]];
    await new Promise((resolve, reject) => {
      const p = execFile(cmd[0], cmd[1], err => (err ? reject(err) : resolve()));
      p.stdin.end(text, "utf8");
    });
    return {ok: true, length: text.length};
  }
  throw new Error(`未知剪贴板动作：${act}`);
}
