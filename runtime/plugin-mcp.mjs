// Solbraid 插件自进化运维 MCP 服务器（stdio，零第三方依赖）。
// 向 Codex / Claude Code / Antigravity 暴露 10 个标准化工具，通过本地受保护 Unix Socket (~/.galiais/run/plugin-ctl.sock)
// 驱动 PluginKernel 完成：查询、读取、脚手架创建、语法与安全预检、自动快照写入、单插件增量热载、烟测与源码回滚。
import fs from "node:fs";
import net from "node:net";
import path from "node:path";

const HOME = process.env.HOME || "/home/agent";
const DEFAULT_SOCK = path.join(HOME, ".galiais", "run", "plugin-ctl.sock");
const DEFAULT_TOKEN_FILE = path.join(HOME, ".galiais", "run", "plugin-ctl.token");

function resolveBridgeConfig() {
  const socketPath = process.env.SOLBRAID_PLUGIN_CTL_SOCKET || DEFAULT_SOCK;
  let token = process.env.SOLBRAID_PLUGIN_CTL_TOKEN || "";
  if (!token) {
    try {
      token = fs.readFileSync(DEFAULT_TOKEN_FILE, "utf8").trim();
    } catch {}
  }
  return {socketPath, token};
}

const TOOLS = [
  {
    name: "solbraid_plugin_list",
    description: "列出 Solbraid 当前全部 Host 插件（核心/内置/本地/社区）、运行代际、状态、能力授权、命令、面板、输入栏动作槽位、覆写标记与快照数量。",
    inputSchema: {type: "object", properties: {}, additionalProperties: false},
  },
  {
    name: "solbraid_plugin_read",
    description: "读取指定本地/社区/覆写插件的 solbraid-plugin.json 清单与全部源码文件；核心内核插件禁止覆写。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID，例如 local.hello.kit 或 solbraid.builtin.workspace-inspector"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_scaffold",
    description: "一键创建一个新的 Solbraid 本地运行时插件脚手架并立即热激活。",
    inputSchema: {
      type: "object",
      properties: {
        id: {type: "string", description: "插件 ID（小写字母、数字、点、横线，如 local.git.summary）"},
        name: {type: "string", description: "插件展示名称"},
        description: {type: "string", description: "插件功能简介"},
      },
      required: ["id", "name"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_validate",
    description: "预检候选插件清单与源码（不写入磁盘）：校验非核心红线、Manifest 契约、路径防穿越、14 条静态安全审计与真实 node --check 语法校验。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        manifest: {type: "object", description: "完整 solbraid-plugin.json 对象"},
        files: {type: "object", description: "相对路径到文本源码的映射，如 {\"index.mjs\": \"export async function activate(ctx){...}\"}"},
      },
      required: ["pluginId", "manifest", "files"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_apply",
    description: "安全热升级插件（原子流水线）：先执行语法与静态安全预检 → 自动归档旧源码快照 → 原子写入 → 单插件增量热重载；若激活失败自动回滚到升级前快照并返回报错堆栈。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID（支持本地/社区插件及非 core 内置插件覆写）"},
        manifest: {type: "object", description: "完整 solbraid-plugin.json 对象"},
        files: {type: "object", description: "相对路径到文本源码的映射（至少包含 entrypoint 声明的入口文件）"},
        note: {type: "string", description: "本次修改/升级说明（写入源码快照元数据）"},
        dryRun: {type: "boolean", description: "为 true 时仅执行预检不落盘"},
      },
      required: ["pluginId", "manifest", "files"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_reload",
    description: "触发单插件增量热重载（传 pluginId）或整代重载（不传 pluginId），免重启 App 立即生效。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "可选；指定时仅增量重载该插件"}},
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test",
    description: "现场烟测已激活插件的命令（command）、交互面板渲染（panel）或自定义 RPC（rpc），返回耗时、实测输出与最近日志。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        kind: {type: "string", enum: ["command", "panel", "rpc"], description: "测试目标类型"},
        target: {type: "string", description: "命令名、面板 ID 或 RPC 方法名"},
        args: {type: "string", description: "command 模式的文本参数"},
        params: {type: "object", description: "rpc 模式的参数对象"},
        cwd: {type: "string", description: "可选工作区目录"},
      },
      required: ["pluginId", "kind"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_logs",
    description: "获取指定插件最近运行日志、当前状态、激活错误与授权能力明细。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_snapshots",
    description: "列出指定插件的历史源码快照（按时间倒序），可用于选择回滚目标。",
    inputSchema: {
      type: "object",
      properties: {pluginId: {type: "string", description: "目标插件 ID"}},
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_rollback",
    description: "将插件一键回滚至指定源码快照（或最近一次快照），或将已覆写的内置插件恢复为 APK 原版。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        snapshotId: {type: "string", description: "可选；指定历史快照 ID，留空则回滚至最新快照"},
        restoreBuiltin: {type: "boolean", description: "为 true 时移除内置插件的磁盘覆写并恢复原版"},
      },
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_install_deps",
    description: "为指定磁盘插件受控安装第三方 npm 依赖（强制 --ignore-scripts 防安装期恶意脚本，遵循 Solbraid 镜像与代理配置；需插件声明 deps.npm 能力）。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        packages: {type: "array", items: {type: "string"}, description: "待安装的 npm 包列表，如 [\"zod@3\", \"marked\"]"},
        dryRun: {type: "boolean", description: "为 true 时仅校验包名与权限，不执行安装"},
      },
      required: ["pluginId", "packages"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test_sdui",
    description: "现场渲染并校验插件的声明式原生 Jetpack Compose SDUI 页面（ctx.ui.registerSurface），返回节点数、AST 校验结果与完整结构。",
    inputSchema: {
      type: "object",
      properties: {
        pluginId: {type: "string", description: "目标插件 ID"},
        surfaceId: {type: "string", description: "可选；目标 SDUI 页面 ID，留空则自动测试首个页面"},
        cwd: {type: "string", description: "可选工作区目录"},
        params: {type: "object", description: "可选页面渲染参数"},
      },
      required: ["pluginId"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_studio_override",
    description: "安全管理 Artifact Studio 离线渲染器热覆写（~/.galiais/studio-overrides/）：白名单文件列表、读取、语法校验后自动快照覆写、回滚或重置为 APK 原版（安全模式下自动禁用覆写）。",
    inputSchema: {
      type: "object",
      properties: {
        action: {type: "string", enum: ["list", "read", "apply", "rollback", "reset"], description: "操作类型"},
        fileName: {type: "string", enum: ["artifact-viewer.html", "artifact-renderer.js", "artifact-latex.js", "artifact-mermaid.js"], description: "目标渲染器文件名"},
        content: {type: "string", description: "action=apply 时的完整文件内容"},
        snapshotId: {type: "string", description: "action=rollback 时可选指定历史快照 ID"},
      },
      required: ["action"],
      additionalProperties: false,
    },
  },
  {
    name: "solbraid_plugin_test_driver",
    description: "现场烟测插件注册的可执行动态 Agent 驱动（kind=driver）或热插拔 LLM 协议转换器（kind=translator），返回流式输出或翻译后的协议载荷。",
    inputSchema: {
      type: "object",
      properties: {
        kind: {type: "string", enum: ["driver", "translator"], description: "测试目标类型"},
        id: {type: "string", description: "驱动 ID 或协议转换器 ID"},
        prompt: {type: "string", description: "kind=driver 时的测试提示词"},
        request: {type: "object", description: "kind=translator 时的模拟入站请求对象"},
        response: {type: "object", description: "kind=translator 时可选的模拟上游响应对象"},
        cwd: {type: "string", description: "可选工作区目录"},
      },
      required: ["kind", "id"],
      additionalProperties: false,
    },
  },
];

function send(packet) {
  process.stdout.write(JSON.stringify(packet) + "\n");
}

const ACTION_TIMEOUT_MS = Object.freeze({
  installDeps: 75_000,
  testDriver: 130_000,
});

function callPluginCtl(action, params = {}) {
  const {socketPath, token} = resolveBridgeConfig();
  if (!token) return Promise.resolve({ok: false, error: "Solbraid 插件控制通道令牌未就绪，请确认 agent-hub 正在运行"});
  const timeoutMs = ACTION_TIMEOUT_MS[action] || 30_000;
  return new Promise(resolve => {
    const conn = net.createConnection(socketPath);
    let buf = "";
    let settled = false;
    const timer = setTimeout(() => finish({ok: false, error: `插件控制通道请求超时（>${Math.round(timeoutMs / 1000)}s）`}), timeoutMs);
    const finish = val => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.destroy();
      resolve(val);
    };
    conn.setEncoding("utf8");
    conn.once("connect", () => conn.write(JSON.stringify({token, action, params}) + "\n"));
    conn.on("data", chunk => {
      buf += chunk;
      if (buf.length > 4 * 1024 * 1024) { finish({ok: false, error: "响应超过大小限制"}); return; }
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      try {
        const reply = JSON.parse(buf.slice(0, nl));
        finish(reply);
      } catch {
        finish({ok: false, error: "插件控制通道返回格式无效"});
      }
    });
    conn.once("error", err => finish({ok: false, error: `无法连接插件控制通道 (${socketPath})：${err.message}`}));
    conn.once("close", () => finish({ok: false, error: "插件控制通道已关闭"}));
  });
}

const TOOL_ACTION_MAP = Object.freeze({
  solbraid_plugin_list: "list",
  solbraid_plugin_read: "read",
  solbraid_plugin_scaffold: "scaffold",
  solbraid_plugin_validate: "validate",
  solbraid_plugin_apply: "apply",
  solbraid_plugin_reload: "reload",
  solbraid_plugin_test: "test",
  solbraid_plugin_logs: "logs",
  solbraid_plugin_snapshots: "snapshots",
  solbraid_plugin_rollback: "rollback",
  solbraid_plugin_install_deps: "installDeps",
  solbraid_plugin_test_sdui: "testSdui",
  solbraid_studio_override: "studioOverride",
  solbraid_plugin_test_driver: "testDriver",
});

async function handle(message) {
  const {id, method, params} = message;
  if (id === undefined) return;
  try {
    if (method === "initialize") {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: params?.protocolVersion ?? "2025-06-18",
          capabilities: {tools: {}},
          serverInfo: {name: "solbraid-plugin-ctl", version: "1.0.0"},
        },
      });
    } else if (method === "ping") {
      send({jsonrpc: "2.0", id, result: {}});
    } else if (method === "tools/list") {
      send({jsonrpc: "2.0", id, result: {tools: TOOLS}});
    } else if (method === "tools/call") {
      const name = String(params?.name || "");
      const action = TOOL_ACTION_MAP[name];
      if (!action) throw Object.assign(new Error(`未知插件运维工具：${name}`), {code: -32602});
      const reply = await callPluginCtl(action, params?.arguments || {});
      const isErr = reply?.ok !== true;
      const text = JSON.stringify(reply?.ok ? reply.data : {error: reply?.error || "操作失败", details: reply?.data}, null, 2);
      send({jsonrpc: "2.0", id, result: {content: [{type: "text", text}], isError: isErr}});
    } else {
      throw Object.assign(new Error(`Method not found: ${method}`), {code: -32601});
    }
  } catch (error) {
    send({jsonrpc: "2.0", id, error: {code: error.code ?? -32603, message: error.message}});
  }
}

let input = "";
let inFlight = 0;
let stdinEnded = false;
function maybeExit() {
  if (stdinEnded && inFlight === 0) process.exit(0);
}
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  input += chunk;
  if (input.length > 8 * 1024 * 1024) process.exit(1);
  let end;
  while ((end = input.indexOf("\n")) >= 0) {
    const line = input.slice(0, end).trim();
    input = input.slice(end + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    inFlight += 1;
    void handle(msg).finally(() => {
      inFlight -= 1;
      maybeExit();
    });
  }
});
process.stdin.once("end", () => {
  stdinEnded = true;
  maybeExit();
});
