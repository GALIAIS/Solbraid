# Solbraid PC Agent（跨平台 Computer Use 守护代理）

零第三方依赖，纯 Node.js（>= 18，浏览器 CDP 推荐 >= 22），供手机端 Solbraid（Root `chroot` 与免 Root `PRoot` 双模式通用）通过 13 个 `solbraid_pc_*` MCP 工具完整操控 Windows / macOS / Linux 电脑。

## 1. 快速启动

### Windows USB 连接
```powershell
powershell -ExecutionPolicy Bypass -File pc-agent/start-windows.ps1
```
- 默认自动执行 `adb reverse tcp:29840 tcp:29840` 并监听 `127.0.0.1:29840`。
- 如需同 Wi-Fi 局域网直连，传入 `-BindHost 0.0.0.0`；如需开启管理员档位（允许电源/结束进程/删除文件），传入 `-Permission admin`。

### macOS / Linux
```bash
node pc-agent/solbraid-pc-agent.mjs --host 0.0.0.0 --port 29840 --permission operate
```
- **macOS 首次权限**：在 `系统设置 → 隐私与安全性` 中为运行 Node 的终端开启「屏幕录制」与「辅助功能」。
- **Linux 依赖**：X11 推荐安装 `scrot xdotool wmctrl xclip`；Wayland 推荐安装 `grim ydotool wtype wl-clipboard`。

## 2. 四种连接模式

| 连接模式 | 适用场景 | 启动与配对要点 |
| :--- | :--- | :--- |
| **USB 直连 (`usb`)** | 手机连线电脑、零网络、最低延迟 | 电脑执行 `adb reverse tcp:29840 tcp:29840`，手机配对 `transport="usb", address="127.0.0.1", port=29840` |
| **局域网直连 (`lan`)** | 同 Wi-Fi 无线控制 | 电脑启动 `--host 0.0.0.0`（可选 `--tls --cert ... --key ...`），手机配对电脑局域网 IP + 令牌 |
| **SSH 隧道 (`ssh`)** | 复用 Solbraid SSH 密钥、零额外端口 | 电脑仅监听 `127.0.0.1:29840`，手机配对 `transport="ssh", sshAlias="<你的 SSH 主机>"` 自动维护本地转发 |
| **公网中继 (`relay`)** | 电脑位于 NAT 后 | 为中继服务器设置 `SOLBRAID_RELAY_SECRET`，运行 `node pc-agent/solbraid-relay.mjs --port 29845` 并通过 HTTPS 提供服务。在电脑代理设置 `--relay`、`--pair-id` 和 `--relay-secret`，在手机填写相同的中继地址、配对 ID 和密钥。 |

## 3. 开机自启与安全急停

```bash
# 安装系统自启服务（Windows 任务计划 / macOS LaunchAgent / Linux systemd --user）
node pc-agent/install-service.mjs install --host 127.0.0.1 --port 29840 --permission operate
```

- 手机与电脑使用配对令牌验证请求和响应签名。升级手机应用后，也需要更新电脑代理。中继密钥与配对令牌用途不同。
- **三档权限**：`observe`（只读观察） / `operate`（常规键鼠与命令） / `admin`（电源/强杀/删除，且需显式 `confirmHighRisk: true`）。
- **双端急停**：手机端调用 `solbraid_pc_hosts(action="emergency_stop", enabled=true)`，或电脑端创建 `~/.solbraid-pc-agent/STOP` 文件即刻切断控制权。
