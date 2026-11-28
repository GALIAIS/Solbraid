<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="108" height="108" alt="Solbraid Logo" />

# Solbraid

[![Android](https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](app/src/main/java/com/galiais/agent)
[![Linux](https://img.shields.io/badge/Linux-Ubuntu%2024.04%20(Root%20%2F%20PRoot)-E95420?style=flat-square&logo=ubuntu&logoColor=white)](runtime/components.json)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)](LICENSE)

</div>

Solbraid 是 Android 上的 AI 编程客户端，集成 Codex、Claude Code 和 Antigravity CLI。内置 Ubuntu 24.04，可在手机上管理代码、运行终端命令，并通过 SSH 和 SFTP 访问远程主机。

## 使用要求

- **设备**　Android 9 或更高版本，ARM64（`arm64-v8a`）。
- **运行环境**　普通设备使用 PRoot。已获得 Root 权限的设备可使用 chroot。两种模式分别保存工作区和会话。
- **模型服务**　本地环境可从 APK 离线安装。使用在线模型需要网络，以及相应服务商的账号或 API 密钥。

## 主要功能

- 在会话中选择 Agent 和服务商，查看命令输出与文件差异，编辑重发或创建会话分支。
- 使用本地终端、文件编辑器、SSH、SFTP 和端口转发处理开发任务。
- 管理 MCP 服务、技能和插件，按会话查看模型用量与估算费用。
- 授权 Agent 操作手机，或通过配对的代理连接电脑。[电脑代理使用说明](pc-agent/README.md)

## 构建

需要 JDK 17、Android SDK 36 和 Node.js 24。在 `downloads/offline/arm64-v8a/` 放置清单中该架构标记为 `offline` 的组件，在 `downloads/runtime/` 放置 SDK、Plan 和终端组件归档。文件名与校验值见[组件清单](runtime/components.json)。

| 平台 | arm64 调试包构建命令 |
| --- | --- |
| Windows PowerShell | `.\gradlew.bat :app:assembleDebug -PsolbraidAbis=arm64-v8a` |
| Linux / macOS | `bash gradlew :app:assembleDebug -PsolbraidAbis=arm64-v8a` |

APK 输出到 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。构建会校验包内的原生组件和运行时文件。

## 自动构建

推送以 `v` 开头的 SemVer 标签（如 `v1.0.0` 或 `v1.1.0-rc.1`）时，GitHub Actions 构建 arm64 调试 APK，并生成 SHA-256 校验文件。产物保留 7 天。

首次构建前，在仓库 Actions 变量中设置 `SOLBRAID_COMPONENTS_URL`，指向包含 `offline/` 和 `runtime/` 的离线组件 HTTPS 根目录。[构建配置](.github/workflows/android.yml)

## 参与贡献

通过 [Issues](https://github.com/GALIAIS/Solbraid/issues) 报告故障或提出功能建议。提交修改前请阅读[贡献指南](CONTRIBUTING.md)。安全问题请使用[私密报告入口](https://github.com/GALIAIS/Solbraid/security/advisories/new)。

## 许可证

Solbraid 项目代码采用 [AGPL-3.0](LICENSE)。第三方组件适用各自的许可条款。
