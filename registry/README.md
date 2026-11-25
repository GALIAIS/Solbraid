# Solbraid 统一共享资源仓库（Community Registry）

用于托管各用户上传并分享的 Solbraid 全类型资源（`instruction` 指令、`mcp`、`skill` 技能、`subagent` 子代理、`hook` 钩子、`host_plugin` 运行时插件、`plugin_pack` 插件包）。

## 安全模型（强制两段式 AI 审查，严格 Fail-Closed）

1. **第一层（确定性静态恶意规则引擎，`runtime/ai-reviewer.mjs`）**
   - 拦截反弹 Shell、`curl | sh` 管道执行、高危破坏性删除命令、SSH/密钥外传、Unicode Bidi 源码混淆、`eval`/`new Function`/Base64 解码执行、未声明权限调用 `child_process`/`fs`/底层网络、路径穿越以及提示词越权注入。
   - 命中任何 `critical` / `high` 规则将**无条件拒绝入库**。
2. **第二层（仓库 AI 语义安全审计）**
   - 配置 `SOLBRAID_REVIEWER_API_URL` / `SOLBRAID_REVIEWER_API_KEY` / `SOLBRAID_REVIEWER_MODEL` 后，对全部提交执行结构化语义安全审计。
   - 开启 `--require-llm` 或 `SOLBRAID_REQUIRE_LLM=1` 时，若 LLM 不可用或返回异常将按 Fail-Closed 策略挂起待审，绝不静默放行。
3. **下载端二次验真**
   - Solbraid 客户端下载每个资源时强制校验 `audit.verdict === "approved"`、规范化载荷 `SHA-256` 防篡改指纹，并执行客户端二次静态复检。

## 部署方式

### 方式一：独立 HTTP 共享仓库服务端（推荐团队/社区使用）

```bash
SOLBRAID_REVIEWER_API_URL=https://api.openai.com/v1 \
SOLBRAID_REVIEWER_API_KEY=sk-xxx \
SOLBRAID_REVIEWER_MODEL=gpt-4o-mini \
SOLBRAID_REQUIRE_LLM=1 \
node registry/registry-server.mjs --port 8787 --dir ./registry/data
```

随后在 Solbraid App「资源库 → 共享仓库 → 配置仓库」中切换为 **远程 HTTP 仓库** 并填入服务地址即可。

### 方式二：GitHub 社区仓库 + GitHub Actions 自动 AI 审查与发版（默认：`GALIAIS/Solbraid`）

1. 本仓库已内置：
   - `registry/registry.json`：公开过审资源索引；
   - `.github/workflows/registry-ai-review.yml`：每次用户提交资源 PR 时自动执行 AI 安全审查并在 PR 下发表完整审查报告评论，未过审自动阻断合并；
   - `.github/workflows/registry-publish.yml` + `registry/publish-index.mjs`：审查通过并合入 `main` 后，自动将 `registry/submissions/` 归档至 `registry/items/` 并重建 `registry/registry.json`。
2. 在 GitHub 仓库 **Settings → Secrets and variables → Actions** 中配置 `SOLBRAID_REVIEWER_API_URL` 与 `SOLBRAID_REVIEWER_API_KEY`（可选变量 `SOLBRAID_REQUIRE_LLM=1`）。
3. 用户在 Solbraid App「资源库 → 共享仓库 → 配置仓库」切换为 **GitHub 社区仓库**（一键填入 `GALIAIS/Solbraid`）并配置个人 GitHub PAT 后，即可在手机端**一键自动 Fork / 建分支 / 提交资源 / 发起 PR**，并在浏览器直达查看仓库 AI 审查结果。
