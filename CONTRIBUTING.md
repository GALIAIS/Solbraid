# 参与贡献

## 报告问题

在 [Issues](https://github.com/GALIAIS/Solbraid/issues) 搜索已有记录。报告故障时，提供应用版本、Android 版本、运行模式和复现步骤。涉及 Agent 时，附上名称和版本。日志只需保留第一条相关错误，并移除令牌、密钥和私人会话内容。

功能建议请说明使用场景、当前遇到的限制和期望结果。涉及账号、权限或数据泄露的问题，请使用[私密安全报告](https://github.com/GALIAIS/Solbraid/security/advisories/new)。

设备适配、Agent 集成或其他跨模块合作，可先在 Issue 中说明目标、涉及的模块和验收方式，便于维护者协调分工。

## 提交代码

从 `main` 创建工作分支，每个 Pull Request 聚焦一项变更。说明用户可见的变化、影响范围和已完成的验证。涉及设备兼容性时，注明设备架构、Android 版本及 Root 或 PRoot 模式。

提交说明使用 Conventional Commits，例如 `fix(chat): 修复恢复会话后草稿丢失`。构建步骤见 [README](README.md#构建)。签名文件、私钥和本机离线组件保存在仓库外或已忽略的目录中。
