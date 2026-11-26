// Solbraid 核心内核服务集合：
// 将 7 大核心子系统（含自进化控制内核）以标准 core 插件形态注册到 PluginKernel 服务总线，
// 享有统一健康诊断、安全模式常驻、防覆写锁定与 Generation 生命周期；默认不预装冗余示范扩展插件。

/** 将 Solbraid 核心内核服务注册到 PluginKernel。 */
export function registerBuiltinHostPlugins(kernel, coreRefs) {
  const coreSpecs = [
    {
      id: "solbraid.core.agent-runtime",
      name: "Agent 会话管理",
      description: "管理 Agent 会话、驱动和实时输出。",
      serviceName: "agentRuntime",
      impl: coreRefs.agentRuntime,
    },
    {
      id: "solbraid.core.self-evolution",
      name: "插件管理",
      description: "检查和加载插件，管理权限与快照恢复。",
      serviceName: "selfEvolution",
      impl: coreRefs.selfEvolution || {
        getStatus: () => ({
          generationId: kernel.generationId,
          agentControlAvailable: Boolean(kernel.controlInfo?.socketPath),
          safeMode: Boolean(kernel.state?.safeMode),
        }),
      },
    },
    {
      id: "solbraid.core.usage-ledger",
      name: "用量统计",
      description: "记录请求的 Token 用量、延迟和估算费用。",
      serviceName: "usageLedger",
      impl: coreRefs.usageLedger,
    },
    {
      id: "solbraid.core.extensions",
      name: "扩展同步",
      description: "将 MCP 服务、技能、指令、子代理和钩子同步到适用的 CLI。",
      serviceName: "extensions",
      impl: coreRefs.extSync,
    },
    {
      id: "solbraid.core.dev-tools",
      name: "开发工具与 PTY 终端",
      description: "提供终端会话、端口转发、Git 克隆和离线 LaTeX 编译。",
      serviceName: "devTools",
      impl: coreRefs.devTools,
    },
    {
      id: "solbraid.core.env-ssh",
      name: "环境与 SSH",
      description: "管理环境变量、SSH 密钥、隧道和 SFTP 文件传输。",
      serviceName: "envSsh",
      impl: coreRefs.envSsh,
    },
    {
      id: "solbraid.core.worktree",
      name: "Git 工作树与工单",
      description: "为工单创建独立工作树，查看差异并合并更改。",
      serviceName: "worktree",
      impl: coreRefs.worktreeMgr,
    },
  ];

  for (const spec of coreSpecs) {
    const isAgentCore = spec.id === "solbraid.core.agent-runtime";
    kernel.registerBuiltin(
      {
        manifestVersion: "1.0.0",
        apiVersion: ">=1.0.0 <2.0.0",
        id: spec.id,
        name: spec.name,
        version: "1.0.0",
        description: spec.description,
        author: "Solbraid Core",
        kind: "core",
        capabilities: {
          required: isAgentCore ? ["services.provide", "agent.driver"] : ["services.provide"],
          optional: [],
        },
      },
      ctx => {
        ctx.services.provide(spec.serviceName, spec.impl);
        if (isAgentCore && ctx.drivers) {
          ctx.drivers.register({id: "codex", name: "Codex", protocol: "codex-app-server", builtin: true, description: "OpenAI Codex 官方 App Server 驱动"});
          ctx.drivers.register({id: "claude", name: "Claude Code", protocol: "claude-sdk", builtin: true, description: "Anthropic Claude Agent SDK 驱动"});
          ctx.drivers.register({id: "antigravity", name: "Antigravity", protocol: "acp", builtin: true, description: "Google Antigravity ACP 原生驱动"});
        }
        ctx.log.info(`核心服务 ${spec.serviceName} 已就绪`);
      },
    );
  }
}
