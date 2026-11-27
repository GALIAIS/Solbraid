#!/usr/bin/env node
// Solbraid PC Agent 跨平台开机自启服务安装器（Windows Scheduled Task / macOS LaunchAgent / Linux systemd --user）
// 用法：node pc-agent/install-service.mjs {install|uninstall|status} [--host 127.0.0.1] [--port 29840] [--permission operate] [--relay <url>]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const agentPath = path.join(__dirname, "solbraid-pc-agent.mjs");
const action = process.argv[2] || "status";
const rest = process.argv.slice(3);

let host = "127.0.0.1", port = 29840, permission = "operate", relay = "";
for (let i = 0; i < rest.length; i++) {
  if (rest[i] === "--host") host = rest[++i] || host;
  else if (rest[i] === "--port") port = Number(rest[++i]) || port;
  else if (rest[i] === "--permission") permission = rest[++i] || permission;
  else if (rest[i] === "--relay") relay = rest[++i] || "";
}

const agentArgs = [agentPath, "--host", host, "--port", String(port), "--permission", permission, ...(relay ? ["--relay", relay] : [])];

if (process.platform === "win32") {
  const taskName = "SolbraidPcAgent";
  if (action === "install") {
    const tr = `"${process.execPath}" ${agentArgs.map(a => `"${a}"`).join(" ")}`;
    const r = spawnSync("schtasks.exe", ["/Create", "/TN", taskName, "/SC", "ONLOGON", "/RL", "HIGHEST", "/F", "/TR", tr], {encoding: "utf8"});
    if (r.status !== 0) {
      // 非管理员降级为普通用户登录任务
      const r2 = spawnSync("schtasks.exe", ["/Create", "/TN", taskName, "/SC", "ONLOGON", "/F", "/TR", tr], {encoding: "utf8"});
      if (r2.status !== 0) { console.error(r2.stderr || r2.stdout); process.exit(1); }
    }
    console.log(`已安装 Windows 登录自启任务 ${taskName}（可运行 schtasks /Run /TN ${taskName} 立即启动）`);
  } else if (action === "uninstall") {
    spawnSync("schtasks.exe", ["/Delete", "/TN", taskName, "/F"], {stdio: "inherit"});
  } else {
    spawnSync("schtasks.exe", ["/Query", "/TN", taskName], {stdio: "inherit"});
  }
} else if (process.platform === "darwin") {
  const plistDir = path.join(os.homedir(), "Library", "LaunchAgents");
  const plist = path.join(plistDir, "com.galiais.solbraid.pc-agent.plist");
  if (action === "install") {
    fs.mkdirSync(plistDir, {recursive: true});
    const xmlArgs = [process.execPath, ...agentArgs].map(a => `    <string>${a}</string>`).join("\n");
    fs.writeFileSync(plist, `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.galiais.solbraid.pc-agent</string>
  <key>ProgramArguments</key><array>
${xmlArgs}
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>\n`, "utf8");
    spawnSync("launchctl", ["unload", plist], {stdio: "ignore"});
    spawnSync("launchctl", ["load", "-w", plist], {stdio: "inherit"});
    console.log(`已安装 macOS LaunchAgent：${plist}`);
  } else if (action === "uninstall") {
    spawnSync("launchctl", ["unload", "-w", plist], {stdio: "ignore"});
    fs.rmSync(plist, {force: true});
    console.log("已卸载 macOS LaunchAgent");
  } else {
    console.log(fs.existsSync(plist) ? `已安装：${plist}` : "未安装");
  }
} else {
  const unitDir = path.join(os.homedir(), ".config", "systemd", "user");
  const unit = path.join(unitDir, "solbraid-pc-agent.service");
  if (action === "install") {
    fs.mkdirSync(unitDir, {recursive: true});
    const execStart = [process.execPath, ...agentArgs].map(a => `"${a}"`).join(" ");
    fs.writeFileSync(unit, `[Unit]\nDescription=Solbraid PC Computer Use Agent\nAfter=graphical-session.target\n\n[Service]\nExecStart=${execStart}\nRestart=always\nRestartSec=3\n\n[Install]\nWantedBy=default.target\n`, "utf8");
    spawnSync("systemctl", ["--user", "daemon-reload"], {stdio: "inherit"});
    spawnSync("systemctl", ["--user", "enable", "--now", "solbraid-pc-agent.service"], {stdio: "inherit"});
    console.log(`已安装 Linux systemd 用户服务：${unit}`);
  } else if (action === "uninstall") {
    spawnSync("systemctl", ["--user", "disable", "--now", "solbraid-pc-agent.service"], {stdio: "ignore"});
    fs.rmSync(unit, {force: true});
    spawnSync("systemctl", ["--user", "daemon-reload"], {stdio: "ignore"});
    console.log("已卸载 Linux systemd 服务");
  } else {
    spawnSync("systemctl", ["--user", "status", "solbraid-pc-agent.service"], {stdio: "inherit"});
  }
}
