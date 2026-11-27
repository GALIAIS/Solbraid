// GALIAIS 通用多边委派 MCP 服务器（stdio，零第三方依赖）。
// 由 claude-host.mjs 与 agent-hub.mjs 为允许委派的会话启动，向模型提供：
//   - delegate_to_agent(agent_id, task)：委派给任意已安装 Agent（codex / claude / antigravity / gemini / opencode / qwen / goose / aider 等）
//   - delegate_to_codex(task)：向后兼容快捷委派给 OpenAI Codex
import net from "node:net";

const socketPath = process.env.GALIAIS_DELEGATE_SOCKET;
const token = process.env.GALIAIS_DELEGATE_TOKEN;
if (!socketPath || !token) throw new Error("Delegation bridge is not configured");

const tools = [
  {
    name: "delegate_to_agent",
    description: "把一个独立、边界清晰的子任务委派给指定的本地 Agent（如 codex、claude、antigravity）在同一工作区完成并返回最终结果。需要用户确认后才会执行。",
    inputSchema: {
      type: "object",
      properties: {
        agent_id: {
          type: "string",
          description: "目标 Agent 标识，例如 codex、claude、antigravity",
        },
        task: {
          type: "string",
          description: "完整、可独立执行的任务描述，包括目标、约束和验收方式",
        },
      },
      required: ["agent_id", "task"],
      additionalProperties: false,
    },
  },
  {
    name: "delegate_to_codex",
    description: "把一个独立、边界清晰的子任务交给 OpenAI Codex 在同一工作区完成，并返回它的最终回答。需要用户确认后才会执行；不要用于简单问题。",
    inputSchema: {
      type: "object",
      properties: {task: {type: "string", description: "完整、可独立执行的任务描述，包括目标、约束和验收方式"}},
      required: ["task"],
      additionalProperties: false,
    },
  },
];

function send(packet) { process.stdout.write(JSON.stringify(packet) + "\n"); }

const DELEGATE_TIMEOUT_MS = Math.max(30_000, Number(process.env.GALIAIS_DELEGATE_TIMEOUT_MS) || 30 * 60_000);

function delegate(targetAgent, task) {
  return new Promise((resolve) => {
    const connection = net.createConnection(socketPath);
    let buffer = "";
    let settled = false;
    const finish = (value) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        connection.destroy();
        resolve(value);
      }
    };
    const timer = setTimeout(() => finish({ok: false, text: "委派等待超时，已中止本次等待"}), DELEGATE_TIMEOUT_MS);
    connection.setEncoding("utf8");
    connection.once("connect", () => connection.write(JSON.stringify({token, targetAgent, task}) + "\n"));
    connection.on("data", (chunk) => {
      buffer += chunk;
      if (buffer.length > 1024 * 1024) { finish({ok: false, text: "委派结果超过限制"}); return; }
      const end = buffer.indexOf("\n");
      if (end < 0) return;
      try { const reply = JSON.parse(buffer.slice(0, end)); finish({ok: reply.ok === true, text: String(reply.text ?? "")}); }
      catch { finish({ok: false, text: "委派结果格式无效"}); }
    });
    connection.once("error", (error) => finish({ok: false, text: "无法连接委派通道：" + error.message}));
    connection.once("close", () => finish({ok: false, text: "委派通道已关闭"}));
  });
}

async function handle(message) {
  const {id, method, params} = message;
  if (id === undefined) return;
  try {
    if (method === "initialize") {
      send({jsonrpc: "2.0", id, result: {protocolVersion: params?.protocolVersion ?? "2025-06-18", capabilities: {tools: {}}, serverInfo: {name: "galiais", version: "1.1.0"}}});
    } else if (method === "ping") {
      send({jsonrpc: "2.0", id, result: {}});
    } else if (method === "tools/list") {
      send({jsonrpc: "2.0", id, result: {tools}});
    } else if (method === "tools/call") {
      const name = params?.name;
      if (name !== "delegate_to_codex" && name !== "delegate_to_agent") {
        throw Object.assign(new Error("Unknown tool"), {code: -32602});
      }
      const targetAgent = name === "delegate_to_agent" ? String(params?.arguments?.agent_id ?? "").trim().toLowerCase() : "codex";
      const task = params?.arguments?.task;
      if (!targetAgent || !/^[a-z0-9_-]{1,48}$/.test(targetAgent)) throw Object.assign(new Error("Invalid agent_id"), {code: -32602});
      if (typeof task !== "string" || !task.trim() || task.length > 20000) throw Object.assign(new Error("Invalid task"), {code: -32602});
      const result = await delegate(targetAgent, task);
      send({jsonrpc: "2.0", id, result: {content: [{type: "text", text: result.text || "（没有返回文字结果）"}], isError: !result.ok}});
    } else {
      throw Object.assign(new Error("Method not found"), {code: -32601});
    }
  } catch (error) {
    send({jsonrpc: "2.0", id, error: {code: error.code ?? -32603, message: error.message}});
  }
}

let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  input += chunk;
  if (input.length > 8 * 1024 * 1024) process.exit(1);
  let end;
  while ((end = input.indexOf("\n")) >= 0) {
    const line = input.slice(0, end).trim();
    input = input.slice(end + 1);
    if (!line) continue;
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    handle(message);
  }
});
process.stdin.once("end", () => process.exit(0));
