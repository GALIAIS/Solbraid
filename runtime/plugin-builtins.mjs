// Solbraid 内置 Host 插件集合：
// 1. 将现有 6 大核心子系统以标准 core 插件形态注册到 PluginKernel 服务总线，享有统一健康诊断与 Generation 生命周期；
// 2. 提供默认内置可选运行时插件 `solbraid.builtin.workspace-inspector`，示范四层插件能力（Schema 设置、/ 命令、事件观测、WebView 隔离面板）。
import fs from "node:fs/promises";
import path from "node:path";

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;"}[c]));
}

async function scanWorkspaceSummary(rootDir, {maxDepth = 2, showHidden = false} = {}) {
  const counts = {dirs: 0, files: 0, byExt: {}};
  const topEntries = [];
  async function walk(dir, depth) {
    let entries;
    try { entries = await fs.readdir(dir, {withFileTypes: true}); } catch { return; }
    for (const e of entries) {
      if (!showHidden && e.name.startsWith(".")) continue;
      if (e.name === "node_modules" || e.name === ".git" || e.name === "build" || e.name === "target") continue;
      if (depth === 0 && topEntries.length < 30) {
        topEntries.push({name: e.name, isDir: e.isDirectory()});
      }
      if (e.isDirectory()) {
        counts.dirs += 1;
        if (depth + 1 < maxDepth) await walk(path.join(dir, e.name), depth + 1);
      } else if (e.isFile()) {
        counts.files += 1;
        const ext = path.extname(e.name).toLowerCase() || "(无后缀)";
        counts.byExt[ext] = (counts.byExt[ext] || 0) + 1;
      }
    }
  }
  await walk(rootDir, 0);
  const topExts = Object.entries(counts.byExt).sort((a, b) => b[1] - a[1]).slice(0, 8);
  return {rootDir, dirs: counts.dirs, files: counts.files, topExts, topEntries};
}

/** 将 Solbraid 核心子系统与内置示范插件注册到 PluginKernel。 */
export function registerBuiltinHostPlugins(kernel, coreRefs) {
  const coreSpecs = [
    {
      id: "solbraid.core.agent-runtime",
      name: "多引擎 Agent 调度内核",
      description: "统一驱动 Antigravity / ACP / CLI Bridge 会话生命周期与流式观测事件。",
      serviceName: "agentRuntime",
      impl: coreRefs.agentRuntime,
    },
    {
      id: "solbraid.core.usage-ledger",
      name: "精确用量账本",
      description: "记录三引擎官方直连与网关请求 Token、延迟、TTFT 与费用账单。",
      serviceName: "usageLedger",
      impl: coreRefs.usageLedger,
    },
    {
      id: "solbraid.core.extensions",
      name: "统一扩展同步器",
      description: "将 MCP、Skills、指令集、子代理与钩子无侵入投影至三大 CLI。",
      serviceName: "extensions",
      impl: coreRefs.extSync,
    },
    {
      id: "solbraid.core.dev-tools",
      name: "开发工具与 PTY 终端",
      description: "提供多会话 PTY 终端、端口转发、Git 克隆与离线 LaTeX 编译能力。",
      serviceName: "devTools",
      impl: coreRefs.devTools,
    },
    {
      id: "solbraid.core.env-ssh",
      name: "环境中心与 SSH / SFTP",
      description: "管理环境变量、.env、SSH 密钥簿、隧道与远程 SFTP 文件传输。",
      serviceName: "envSsh",
      impl: coreRefs.envSsh,
    },
    {
      id: "solbraid.core.worktree",
      name: "Git Worktree 与工单协同",
      description: "支撑多智能体隔离分支执行、Diff 审查与合并回收。",
      serviceName: "worktree",
      impl: coreRefs.worktreeMgr,
    },
    {
      id: "solbraid.core.community-registry",
      name: "共享资源仓库与 AI 审查",
      description: "托管用户上传分享的全类型资源，内置静态规则与 AI 语义安全门禁。",
      serviceName: "communityRegistry",
      impl: coreRefs.communityRegistry,
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

  // 内置可选运行时插件：完整示范声明式 Schema 设置 + /inspect 指令 + 事件观测 + 本地存储 + WebView 隔离看板
  kernel.registerBuiltin(
    {
      manifestVersion: "1.0.0",
      apiVersion: ">=1.0.0 <2.0.0",
      id: "solbraid.builtin.workspace-inspector",
      name: "工作区体检看板",
      version: "1.0.0",
      description: "提供 /inspect 快捷命令与隔离 WebView 工作区结构/会话观测看板，支持原生 Schema 配置。",
      author: "Solbraid",
      kind: "builtin",
      capabilities: {
        required: ["commands", "settings.schema", "ui.panel", "workspace.read", "storage.local"],
        optional: ["events.observe", "rpc"],
      },
      settingsSchema: {
        type: "object",
        properties: {
          maxDepth: {type: "integer", title: "扫描最大深度", description: "限制目录递归层数（1~4）", default: 2},
          showHidden: {type: "boolean", title: "包含隐藏文件（以 . 开头）", default: false},
          accentTheme: {
            type: "string",
            title: "看板强调色主题",
            enum: ["ocean", "amber", "emerald"],
            enumNames: ["深海蓝", "琥珀金", "翡翠绿"],
            default: "ocean",
          },
        },
        required: ["maxDepth"],
      },
    },
    async ctx => {
      ctx.log.info("工作区体检看板插件激活");
      const getDepth = () => Math.max(1, Math.min(4, Number(ctx.settings.get().maxDepth) || 2));

      ctx.events?.observe("session.completed", async ev => {
        const count = (Number(await ctx.storage.get("completedTurns")) || 0) + 1;
        await ctx.storage.set("completedTurns", count);
        await ctx.storage.set("lastTurn", {agentId: ev.agentId, sessionId: ev.sessionId, ts: Date.now()});
      });

      const renderHtml = async cwd => {
        const cfg = ctx.settings.get();
        const summary = await scanWorkspaceSummary(cwd, {maxDepth: getDepth(), showHidden: Boolean(cfg.showHidden)});
        const completedTurns = Number(await ctx.storage.get("completedTurns")) || 0;
        const accentMap = {ocean: "#38bdf8", amber: "#f59e0b", emerald: "#10b981"};
        const accent = accentMap[cfg.accentTheme] || "#38bdf8";
        const extBars = summary.topExts.map(([ext, cnt]) => {
          const pct = summary.files > 0 ? Math.max(8, Math.round((cnt / summary.files) * 100)) : 0;
          return `<div class="bar-row"><span class="ext">${escapeHtml(ext)}</span><div class="track"><div class="fill" style="width:${pct}%;background:${accent}"></div></div><span class="cnt">${cnt}</span></div>`;
        }).join("") || `<div class="muted">当前目录暂无文件</div>`;
        const entryChips = summary.topEntries.map(e => `<span class="chip">${e.isDir ? "📁 " : "📄 "}${escapeHtml(e.name)}</span>`).join("") || `<span class="muted">空目录</span>`;
        return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
          html{min-height:100%;background:#0f172a}
          body{margin:0;min-height:100vh;padding:16px;font-family:-apple-system,BlinkMacSystemFont,system-ui,sans-serif;background:#0f172a;color:#e2e8f0;line-height:1.45;box-sizing:border-box}
          h1{font-size:18px;margin:0 0 4px;display:flex;align-items:center;gap:8px}
          .sub{color:#94a3b8;font-size:12px;word-break:break-all;margin-bottom:14px}
          .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}
          .kpi{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:12px}
          .kpi .v{font-size:20px;font-weight:700;color:${accent}}
          .kpi .l{font-size:11px;color:#94a3b8;margin-top:2px}
          .card{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:14px;margin-bottom:12px}
          .card h2{font-size:13px;color:#cbd5e1;margin:0 0 10px;text-transform:uppercase;letter-spacing:.04em}
          .bar-row{display:flex;align-items:center;gap:8px;margin-bottom:7px;font-size:12px}
          .ext{width:70px;font-family:monospace;color:#cbd5e1;overflow:hidden;text-overflow:ellipsis}
          .track{flex:1;height:8px;background:#0f172a;border-radius:999px;overflow:hidden}
          .fill{height:100%;border-radius:999px}
          .cnt{width:34px;text-align:right;font-family:monospace;color:#94a3b8}
          .chips{display:flex;flex-wrap:wrap;gap:6px}
          .chip{background:#0f172a;border:1px solid #334155;border-radius:8px;padding:4px 8px;font-size:12px;font-family:monospace}
          .muted{color:#64748b;font-size:12px}
          .head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:4px}
          .btn{background:${accent};color:#0b1220;border:0;border-radius:999px;padding:6px 12px;font-size:12px;font-weight:600;cursor:pointer}
          .btn:disabled{opacity:.6}
        </style></head><body>
          <div class="head"><h1>工作区体检看板</h1><button id="rescanBtn" class="btn" type="button">重新扫描</button></div>
          <div class="sub">${escapeHtml(summary.rootDir)} · 深度 ≤ ${getDepth()} · ${escapeHtml(ctx.host.generationId)}</div>
          <div class="grid">
            <div class="kpi"><div id="kFiles" class="v">${summary.files}</div><div class="l">扫描文件</div></div>
            <div class="kpi"><div id="kDirs" class="v">${summary.dirs}</div><div class="l">子目录</div></div>
            <div class="kpi"><div class="v">${completedTurns}</div><div class="l">已观测完成轮次</div></div>
          </div>
          <div class="card"><h2>文件类型分布</h2><div id="extBox">${extBars}</div></div>
          <div class="card"><h2>顶层条目</h2><div id="chipBox" class="chips">${entryChips}</div></div>
          <script>
            (function(){
              var btn=document.getElementById("rescanBtn");
              var cwd=${JSON.stringify(summary.rootDir)};
              var accent=${JSON.stringify(accent)};
              function esc(s){return String(s||"").replace(/[&<>"]/g,function(c){return({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]);});}
              if(!btn||!window.SolbraidPlugin){if(btn)btn.style.display="none";return;}
              btn.addEventListener("click",function(){
                btn.disabled=true;btn.textContent="扫描中…";
                window.SolbraidPlugin.invoke("summary",{cwd:cwd}).then(function(r){
                  document.getElementById("kFiles").textContent=String(r.files||0);
                  document.getElementById("kDirs").textContent=String(r.dirs||0);
                  var bars=(r.topExts||[]).map(function(pair){
                    var pct=r.files>0?Math.max(8,Math.round((pair[1]/r.files)*100)):0;
                    return '<div class="bar-row"><span class="ext">'+esc(pair[0])+'</span><div class="track"><div class="fill" style="width:'+pct+'%;background:'+accent+'"></div></div><span class="cnt">'+pair[1]+'</span></div>';
                  }).join("")||'<div class="muted">当前目录暂无文件</div>';
                  document.getElementById("extBox").innerHTML=bars;
                  var chips=(r.topEntries||[]).map(function(e){return '<span class="chip">'+(e.isDir?'📁 ':'📄 ')+esc(e.name)+'</span>';}).join("")||'<span class="muted">空目录</span>';
                  document.getElementById("chipBox").innerHTML=chips;
                }).catch(function(err){
                  console.error("插件 RPC 刷新失败：",err&&err.message?err.message:err);
                }).finally(function(){btn.disabled=false;btn.textContent="重新扫描";});
              });
            })();
          </script>
        </body></html>`;
      };

      ctx.panels.register({
        id: "overview",
        title: "工作区体检看板",
        description: "离线统计项目目录结构、语言占比与会话观测计数",
        render: async ({cwd}) => ({title: "工作区体检看板", kind: "html", content: await renderHtml(cwd)}),
      });

      ctx.commands.register("inspect", {
        description: "生成并打开当前工作区体检看板（内置运行时插件）",
        async handler({cwd}) {
          return {
            kind: "artifact",
            title: "工作区体检看板",
            artifactKind: "html",
            content: await renderHtml(cwd),
          };
        },
      });

      ctx.rpc?.register("summary", async ({cwd}) => await scanWorkspaceSummary(cwd || path.join(ctx.host.home, "workspace"), {
        maxDepth: getDepth(),
        showHidden: Boolean(ctx.settings.get().showHidden),
      }));
    },
  );
}
