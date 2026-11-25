import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import {randomUUID} from "node:crypto";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {loadMcpServersForClaude} from "./extension-sync.mjs";
import {loadEffectiveUserEnv} from "./env-ssh-hub.mjs";
import {resolveCliInstallation} from "./cli-components.mjs";
const installation = await resolveCliInstallation("claude");
const sdkDirectory = installation.sdkDirectory;
const sdk = await import(`${sdkDirectory}/sdk.mjs`);

const executable = installation.binary;
const delegateServer = "/opt/galiais-codex/android/delegate-mcp.mjs";
const delegateTools = new Set(["mcp__galiais__delegate_to_codex", "mcp__galiais__delegate_to_agent"]);
const ARTIFACTS_APPEND_PROMPT = [
  "You are running inside Solbraid mobile workspace with an offline Artifact Studio renderer.",
  "When the user asks for an interactive app, UI mockup, dashboard, chart, diagram, SVG illustration, React widget, or formatted LaTeX document, wrap the complete deliverable in an <antArtifact> tag:",
  '<antArtifact identifier="short-kebab-id" type="MIME_TYPE" title="Descriptive Title">...complete self-contained content...</antArtifact>',
  "Supported MIME_TYPE values: text/html (offline single-file HTML/CSS/JS, zero external CDN links), image/svg+xml (clean standalone SVG with viewBox), application/vnd.ant.mermaid (Mermaid flowchart/sequence/state/class/er/pie/gantt/mindmap/xychart-beta), text/latex (complete LaTeX document or formula sheet), application/vnd.ant.react (self-contained JSX component), text/markdown, text/csv.",
  "Reuse the same identifier when revising an existing artifact so Solbraid tracks versions cleanly. For normal code edits in the repository, continue using file tools as usual.",
].join("\n");
const maximumFrame = 8 * 1024 * 1024 - 1024;
const sessions = new Map(), prompts = new Map(), opening = new Set(), operations = new Set();
let initialized = false, closing = false;
let cachedProbeModels = null, cachedProbeAgents = null;
if (process.getuid() === 0) throw new Error("Claude Code must not run as root");

function object(value, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(label + " must be an object");
  return value;
}
function string(value, label, maximum = 4096) {
  if (typeof value !== "string" || !value.length || value.length > maximum || value.includes("\0")) throw new Error("Invalid " + label);
  return value;
}
function uuid(value) {
  if (typeof value !== "string" || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value)) throw new Error("Invalid session identity");
  return value;
}
async function directory(value) {
  const requested = string(value, "working directory");
  if (!path.isAbsolute(requested)) throw new Error("Working directory must be absolute");
  const actual = await fs.realpath(requested);
  if (!(await fs.stat(actual)).isDirectory()) throw new Error("Working directory is not a directory");
  return actual;
}
function output(packet) {
  if (closing) return;
  const frame = JSON.stringify(packet) + "\n";
  if (Buffer.byteLength(frame) > maximumFrame || process.stdout.writableLength + Buffer.byteLength(frame) > 16 * 1024 * 1024)
    throw new Error("Claude Code transport output limit exceeded; the operation was not replayed");
  process.stdout.write(frame);
}
function event(method, params) { output({method, params}); }
function inputQueue() {
  const values = [];
  let wake, ended = false;
  return {
    push(value) {
      if (ended) throw new Error("Claude Code input has closed");
      if (values.length >= 16) throw new Error("Claude Code input queue is full");
      if (wake) { const resolve = wake; wake = undefined; resolve({value,done:false}); } else values.push(value);
    },
    close() { ended = true; values.length = 0; wake?.({done:true}); wake = undefined; },
    [Symbol.asyncIterator]() {return this;},
    next() {return values.length ? Promise.resolve({value:values.shift(),done:false}) : ended ? Promise.resolve({done:true}) : new Promise(resolve=>{wake=resolve;});}
  };
}
function ask(session, kind, details, signal) {
  if (signal.aborted || session.closed) return Promise.reject(new Error("Claude Code request was cancelled"));
  if (prompts.size >= 64) return Promise.reject(new Error("Too many unanswered Claude Code requests"));
  const id = "claude-" + randomUUID();
  return new Promise((resolve,reject)=>{
    const abort = ()=>finish(undefined,new Error("Claude Code request was cancelled"));
    function finish(result, error) {
      if (!prompts.delete(id)) return;
      signal.removeEventListener("abort",abort);
      event("galiais/requestResolved",{id,sessionId:session.id});
      error ? reject(error) : resolve(result);
    }
    prompts.set(id,{session,kind,finish});
    signal.addEventListener("abort",abort,{once:true});
    try { output({id,method:"galiais/userRequest",params:{sessionId:session.id,kind,...details}}); }
    catch(error) {finish(undefined,error);}
  });
}
function answer(packet) {
  const pending = prompts.get(packet.id);
  if (!pending) throw new Error("Claude Code request is no longer pending; no answer was sent");
  if (packet.error) {pending.finish(undefined,new Error("The user cancelled the request"));return;}
  const result = object(packet.result,"User response");
  if (pending.kind === "permission") {
    if (result.behavior === "allow") {
      if (result.updatedInput !== undefined) object(result.updatedInput,"Tool input");
      let updatedPermissions;
      if (result.updatedPermissions !== undefined) {
        if (!Array.isArray(result.updatedPermissions)) throw new Error("updatedPermissions must be an array");
        const allowedTypes = new Set(["addRules","replaceRules","removeRules","setMode","addDirectories","removeDirectories"]);
        for (const u of result.updatedPermissions) {
          object(u,"Permission update");
          if (!allowedTypes.has(u.type)) throw new Error("Unsupported permission update type: " + u.type);
        }
        updatedPermissions = result.updatedPermissions;
      }
      const classification = ["user_temporary","user_permanent"].includes(result.decisionClassification)
        ? result.decisionClassification : undefined;
      pending.finish({
        behavior:"allow",
        ...(result.updatedInput === undefined ? {} : {updatedInput:result.updatedInput}),
        ...(updatedPermissions === undefined ? {} : {updatedPermissions}),
        ...(classification === undefined ? {} : {decisionClassification:classification}),
      });
    } else if (result.behavior === "deny") {
      pending.finish({
        behavior:"deny",
        message:string(result.message,"denial reason"),
        interrupt:result.interrupt === true,
        decisionClassification:"user_reject",
      });
    } else throw new Error("Invalid permission decision");
  } else if (pending.kind === "delegate") {
    if (result.behavior !== "completed" && result.behavior !== "failed") throw new Error("Invalid delegation result");
    pending.finish({ok:result.behavior === "completed",text:typeof result.text === "string" ? result.text.slice(0,maximumFrame / 2) : ""});
  } else if (pending.kind === "elicitation") {
    if (!["accept","decline","cancel"].includes(result.action)) throw new Error("Invalid input decision");
    if (result.content !== undefined) object(result.content,"Input response");
    pending.finish({action:result.action,...(result.content === undefined ? {} : {content:result.content})});
  } else {
    if (result.behavior !== "completed" && result.behavior !== "cancelled") throw new Error("Invalid dialog result");
    pending.finish(result.behavior === "cancelled" ? {behavior:"cancelled"} : {behavior:"completed",result:result.result});
  }
}
function closeSession(session) {
  if (session.closed) return;
  session.closed = true;
  session.input.close();
  for (const prompt of [...prompts.values()]) if (prompt.session === session) prompt.finish(undefined,new Error("Claude Code session was closed"));
  try {
    if (session.query) session.query.close(); else session.warm?.close();
  } finally {
    session.controller.abort();sessions.delete(session.id);
    if (session.delegation) {session.delegation.server.close();fs.rm(session.delegation.path,{force:true}).catch(()=>{});}
  }
}
function getSession(params) {
  const session = sessions.get(uuid(params.sessionId));
  if (!session || session.closed || !session.query) throw new Error("Claude Code session is not connected");
  return session;
}
// 每个允许委派的会话一个仅限本用户的 Unix socket；委派 MCP 服务器凭随机令牌把工具调用交回这里。
async function delegationBridge(session) {
  const token = randomUUID(), path = `/tmp/galiais-delegate-${randomUUID()}.sock`;
  const server = net.createServer(connection=>{
    let buffer = "";
    connection.setEncoding("utf8");
    connection.on("error",()=>{});
    connection.on("data",chunk=>{
      buffer += chunk;
      if (buffer.length > 64 * 1024) {connection.destroy();return;}
      const end = buffer.indexOf("\n");
      if (end < 0) return;
      let request;
      try {request = JSON.parse(buffer.slice(0,end));} catch {connection.destroy();return;}
      buffer = "";
      const reply = value=>{if (!connection.destroyed) connection.end(JSON.stringify(value)+"\n");};
      if (request.token !== token || typeof request.task !== "string" || !request.task.trim() || request.task.length > 20000) {reply({ok:false,text:"Invalid delegation request"});return;}
      const targetAgent = typeof request.targetAgent === "string" && /^[a-z0-9_-]{1,48}$/.test(request.targetAgent) ? request.targetAgent : "codex";
      ask(session,"delegate",{targetAgent,task:request.task,cwd:session.cwd},session.controller.signal)
        .then(result=>reply(result),error=>reply({ok:false,text:error.message}));
    });
  });
  await new Promise((resolve,reject)=>{server.once("error",reject);server.listen(path,resolve);});
  await fs.chmod(path,0o600);
  return {server,path,token};
}
function inferCustomContextWindow(model) {
  const m = String(model || "").trim().toLowerCase();
  if (!m) return 200_000;
  if (m.includes("[1m]") || m.includes("1m-context") || m.startsWith("gemini") || m.includes("gemini-") || m.startsWith("gpt-4.1")) return 1_000_000;
  if (m.startsWith("gpt-5") || m.includes("codex")) return 272_000;
  if (m.includes("qwen3") || m.includes("qwen-3")) return 262_144;
  if (m.includes("kimi") || m.includes("moonshot") || m.includes("grok-4") || m.includes("grok-3") || m.includes("doubao")) return 256_000;
  if (m.includes("deepseek") || m.includes("glm-4") || m.includes("gpt-4") || m.includes("gpt-oss") || m.includes("qwen")) return 128_000;
  return 200_000;
}

async function openSession(params) {
  if (sessions.size + opening.size >= 4) {
    // 超过 4 会话上限时优先按 LRU 回收最久未活跃的空闲会话，避免永久报错卡死。
    const idleVictim = [...sessions.values()]
      .filter(s => !s.busy && !s.closed)
      .sort((a, b) => (a.lastActivity || 0) - (b.lastActivity || 0))[0];
    if (idleVictim) {
      closeSession(idleVictim);
    } else {
      throw new Error("同时运行中的 Claude Code 会话已达上限（4 个），请先停止其中一个任务");
    }
  }
  if (params.trustProject !== true) throw new Error("Confirm that you trust this project before loading its Claude Code configuration");
  const id = params.resume === undefined ? randomUUID() : uuid(params.resume);
  if (sessions.has(id) || opening.has(id)) throw new Error("Claude Code session is already connected");
  opening.add(id);
  let session;
  try {
    const cwd = await directory(params.cwd);
    const userEnv = await loadEffectiveUserEnv(cwd, process.env.PATH || "");
    const env = {...process.env, ...userEnv, DISABLE_AUTOUPDATER: "1"};
    if (params.apiKey !== undefined) {
      const key = string(params.apiKey,"API key");
      if (/[\r\n]/.test(key)) throw new Error("API key contains an invalid newline");
      env.ANTHROPIC_API_KEY = key;
    }
    if (params.oauthToken !== undefined) {
      const token = string(params.oauthToken,"OAuth token");
      if (/[\r\n]/.test(token)) throw new Error("OAuth token contains an invalid newline");
      env.CLAUDE_CODE_OAUTH_TOKEN = token;
    }
    if (params.baseUrl !== undefined) {
      const endpoint = new URL(string(params.baseUrl,"provider URL"));
      const localGateway = endpoint.protocol === "http:" && endpoint.hostname === "127.0.0.1";
      if ((endpoint.protocol !== "https:" && !localGateway) || endpoint.username || endpoint.password || endpoint.search || endpoint.hash)
        throw new Error("Provider URL must use HTTPS (or local 127.0.0.1 gateway) without embedded credentials or query parameters");
      let base = endpoint.toString().replace(/\/$/,"");
      if (localGateway && /^\/anthropic\/[a-z0-9_-]+$/i.test(endpoint.pathname)) {
        base = `${base}/sid/${encodeURIComponent(id)}`;
      }
      env.ANTHROPIC_BASE_URL = base;
    }
    if (params.customProvider === true && params.model !== undefined) {
      const customModel = string(params.model,"model",256);
      env.ANTHROPIC_MODEL = customModel;
      env.ANTHROPIC_SMALL_FAST_MODEL = customModel;
      env.ANTHROPIC_DEFAULT_HAIKU_MODEL = customModel;
      env.ANTHROPIC_DEFAULT_SONNET_MODEL = customModel;
      env.ANTHROPIC_DEFAULT_OPUS_MODEL = customModel;
      // 非 Anthropic 128k 等小窗口模型在自定义服务商下按比例下调自动压缩触发线，确保在真正触顶前自动压缩。
      if (!env.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE) {
        const inferredWin = inferCustomContextWindow(customModel);
        if (inferredWin < 200_000) {
          const pct = Math.max(40, Math.min(82, Math.floor((inferredWin / 200_000) * 85)));
          env.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE = String(pct);
        }
      }
    }
    if (!Object.prototype.hasOwnProperty.call(userEnv, "DISABLE_AUTO_COMPACT")) {
      delete env.DISABLE_AUTO_COMPACT;
    }
    session = {id,cwd,input:inputQueue(),controller:new AbortController(),closed:false,busy:false,lastActivity:Date.now()};
    if (closing) throw new Error("Claude Code startup was cancelled");
    sessions.set(id,session);
    opening.delete(id);
    const mcpServers = {};
    Object.assign(mcpServers, await loadMcpServersForClaude(cwd));
    if (params.allowDelegation === true) {
      session.delegation = await delegationBridge(session);
      mcpServers.galiais = {type:"stdio",command:process.execPath,args:[delegateServer],
        env:{GALIAIS_DELEGATE_SOCKET:session.delegation.path,GALIAIS_DELEGATE_TOKEN:session.delegation.token}};
      env.MCP_TOOL_TIMEOUT = String(2 * 60 * 60 * 1000);
    }
    const effortLevels = ["low","medium","high","xhigh","max"];
    if (params.effort !== undefined && params.effort !== null && !effortLevels.includes(params.effort))
      throw new Error("Invalid Claude Code effort level");
    const allowedPermissionModes = ["default","acceptEdits","plan","auto","bypassPermissions"];
    const permissionMode = params.permissionMode ?? "default";
    if (!allowedPermissionModes.includes(permissionMode)) throw new Error("Invalid Claude Code permission mode");
    const thinkingDisplay = params.thinkingDisplay ?? "summarized";
    if (!["summarized","omitted"].includes(thinkingDisplay)) throw new Error("Invalid thinking display mode");
    if (params.fastMode !== undefined && params.fastMode !== null && typeof params.fastMode !== "boolean") throw new Error("Invalid fast mode setting");
    const systemPrompt = params.artifactsEnabled === false
      ? {type:"preset",preset:"claude_code"}
      : {type:"preset",preset:"claude_code",append:ARTIFACTS_APPEND_PROMPT};
    if (params.promptCacheTtl && ["5m", "1h"].includes(params.promptCacheTtl)) {
      env.ANTHROPIC_PROMPT_CACHE_TTL = params.promptCacheTtl;
    }
    const extraSettings = {};
    if (params.fastMode !== undefined && params.fastMode !== null) extraSettings.fastMode = params.fastMode;
    if (params.commitAttribution === false) extraSettings.attribution = {commit: ""};
    const options = {
      cwd,env,pathToClaudeCodeExecutable:executable,abortController:session.controller,
      permissionMode,allowDangerouslySkipPermissions:true,
      settingSources:["user","project","local"],systemPrompt,
      sandbox:{enabled:false},
      includePartialMessages:true,enableFileCheckpointing:true,mcpServers,
      ...(params.resume === undefined ? {sessionId:id} : {resume:id}),
      ...(params.model === undefined ? {} : {model:string(params.model,"model",256)}),
      ...(params.effort === undefined || params.effort === null ? {} : {effort:params.effort}),
      ...(Object.keys(extraSettings).length ? {settings:extraSettings} : {}),
      canUseTool:(toolName,input,request)=>{
        const {signal,...details}=request;
        // 委派工具本身会再向用户发起确认，这里不重复询问。
        if (delegateTools.has(toolName) && session.delegation) return Promise.resolve({behavior:"allow"});
        return ask(session,"permission",{toolName,input,...details},signal);
      },
      onElicitation:(request,{signal})=>ask(session,"elicitation",{request},signal),
      onUserDialog:(request,{signal})=>ask(session,"dialog",{request},signal)
    };
    session.warm = await sdk.startup({options,initializeTimeoutMs:120000});
    if (closing || session.closed) throw new Error("Claude Code startup was cancelled");
    session.query = session.warm.query(session.input);
    session.warm = undefined;
    session.reading = (async()=>{
      try {
        for await (const message of session.query) {
          if (message.type === "result") session.busy = false;
          event("claude/message",{sessionId:id,message});
        }
        if (!session.closed) event("claude/sessionClosed",{sessionId:id,reason:"Claude Code exited; no task was replayed"});
      } catch(error) {if (!session.closed) event("claude/sessionError",{sessionId:id,message:error.message});}
      finally {closeSession(session);}
    })();
    const initialization = await session.query.initializationResult();
    await session.query.setMaxThinkingTokens(null, thinkingDisplay);
    return {sessionId:id,cwd,initialization};
  } catch(error) {if (session) closeSession(session);throw error;}
  finally {opening.delete(id);}
}
async function dispatch(method, params) {
  if (method === "initialize") {
    if (initialized) throw new Error("Claude Code bridge has already initialized");
    const packageInfo = JSON.parse(await fs.readFile(`${sdkDirectory}/package.json`,"utf8"));
    if (packageInfo.version !== installation.sdkVersion || packageInfo.claudeCodeVersion !== installation.version) throw new Error("Claude Code SDK version mismatch");
    const {stdout} = await promisify(execFile)(executable,["--version"],{timeout:30000,maxBuffer:4096});
    if (stdout.trim() !== `${installation.version} (Claude Code)`) throw new Error("Claude Code executable version mismatch");
    initialized = true;
    return {sdkVersion:packageInfo.version,cliVersion:stdout.trim()};
  }
  if (!initialized) throw new Error("Claude Code bridge has not initialized");
  switch(method) {
    case "session/open": return openSession(params);
    case "session/close": {const session=getSession(params);closeSession(session);return {};}
    case "session/list": {
      const limit=params.limit ?? 50;
      if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error("Invalid session list limit");
      const offset=params.offset ?? 0;
      if (!Number.isSafeInteger(offset) || offset < 0) throw new Error("Invalid session list offset");
      return {sessions:await sdk.listSessions({...(params.cwd === undefined ? {} : {dir:await directory(params.cwd)}),limit,offset})};
    }
    case "session/read": {
      const id=uuid(params.sessionId), limit=params.limit ?? 50, offset=params.offset ?? 0;
      if (!Number.isInteger(limit) || limit<1 || limit>100 || !Number.isInteger(offset) || offset<0) throw new Error("Invalid session history range");
      const options={...(params.cwd === undefined ? {} : {dir:await directory(params.cwd)})};
      return {info:await sdk.getSessionInfo(id,options),messages:await sdk.getSessionMessages(id,{...options,limit,offset})};
    }
    case "session/delete": {
      const id=uuid(params.sessionId);
      if (sessions.has(id) || opening.has(id)) throw new Error("Close the Claude Code session before deleting it");
      await sdk.deleteSession(id,{...(params.cwd === undefined ? {} : {dir:await directory(params.cwd)})});
      return {};
    }
    case "session/rename": await sdk.renameSession(uuid(params.sessionId),string(params.title,"session title",200),{...(params.cwd === undefined ? {} : {dir:await directory(params.cwd)})});return {};
    case "session/fork": {
      const id = uuid(params.sessionId);
      const opts = {
        ...(params.cwd === undefined ? {} : {dir:await directory(params.cwd)}),
        ...(params.upToMessageId === undefined || params.upToMessageId === null ? {} : {upToMessageId:string(params.upToMessageId,"message id",200)}),
        ...(params.title === undefined || params.title === null ? {} : {title:string(params.title,"session title",200)}),
      };
      const forked = await sdk.forkSession(id, opts);
      return {sessionId: forked.sessionId};
    }
    case "session/setPermissionMode": {
      const mode = string(params.mode,"permission mode",32);
      if (!["default","acceptEdits","plan","auto","bypassPermissions"].includes(mode)) throw new Error("Unsupported Claude Code permission mode: " + mode);
      await getSession(params).query.setPermissionMode(mode);
      return {mode};
    }
    case "session/contextUsage": {
      const detail = params.detail === "full" ? "full" : "summary";
      return await getSession(params).query.getContextUsage({detail});
    }
    case "session/rewindFiles": {
      const msgId = string(params.userMessageId,"user message id",200);
      const dryRun = params.dryRun === true;
      return await getSession(params).query.rewindFiles(msgId, {dryRun});
    }
    case "mcp/list": {
      const target = params.sessionId ? getSession(params) : [...sessions.values()].find(s => !s.closed && s.query);
      if (!target) return {servers:[]};
      return {servers: await target.query.mcpServerStatus()};
    }
    case "mcp/toggle": {
      const name = string(params.serverName,"MCP server name",200);
      const target = params.sessionId ? getSession(params) : [...sessions.values()].find(s => !s.closed && s.query);
      if (!target) throw new Error("No active Claude Code session");
      await target.query.toggleMcpServer(name, params.enabled === true);
      return {};
    }
    case "mcp/reconnect": {
      const name = string(params.serverName,"MCP server name",200);
      const target = params.sessionId ? getSession(params) : [...sessions.values()].find(s => !s.closed && s.query);
      if (!target) throw new Error("No active Claude Code session");
      await target.query.reconnectMcpServer(name);
      return {};
    }
    case "agent/list": {
      const target = params.sessionId ? getSession(params) : [...sessions.values()].find(s => !s.closed && s.query);
      if (!target) return {agents:[]};
      return {agents: await target.query.supportedAgents()};
    }
    case "turn/start": {
      const session=getSession(params);
      if (session.busy) throw new Error("A Claude Code turn is already running; interrupt it before sending another prompt");
      const content=params.content;
      if (typeof content === "string") string(content,"prompt",maximumFrame);
      else if (!Array.isArray(content) || !content.length || content.some(block=>!block || !["text","image","document"].includes(block.type))) throw new Error("Invalid Claude Code prompt content");
      const messageId=randomUUID();
      session.input.push({type:"user",session_id:session.id,uuid:messageId,parent_tool_use_id:null,message:{role:"user",content}});
      session.busy=true;
      return {messageId};
    }
    case "turn/interrupt": return await getSession(params).query.interrupt() ?? {};
    case "session/setModel": {
      const model = params.model === undefined || params.model === null ? undefined : string(params.model,"model",256);
      await getSession(params).query.setModel(model);
      return {};
    }
    case "session/setEffort": {
      const effort = params.effort === undefined || params.effort === null ? null : string(params.effort,"effort",32);
      if (effort !== null && !["low","medium","high","xhigh","max"].includes(effort)) throw new Error("Invalid Claude Code effort level");
      await getSession(params).query.applyFlagSettings({effortLevel:effort});
      return {};
    }
    case "session/setThinkingDisplay": {
      const display = string(params.display,"thinking display",32);
      if (!["summarized","omitted"].includes(display)) throw new Error("Invalid thinking display mode");
      await getSession(params).query.setMaxThinkingTokens(null,display);
      return {};
    }
    case "session/setFastMode": {
      if (params.enabled !== null && typeof params.enabled !== "boolean") throw new Error("Invalid fast mode setting");
      await getSession(params).query.applyFlagSettings({fastMode:params.enabled});
      return {};
    }
    case "model/list": {
      const target = params.sessionId ? getSession(params) : [...sessions.values()].find(s => !s.closed && s.query);
      if (target) {
        const models = await target.query.supportedModels();
        cachedProbeModels = models;
        return {models};
      }
      if (Array.isArray(cachedProbeModels) && cachedProbeModels.length && params.refresh !== true) {
        return {models: cachedProbeModels};
      }
      // 无活跃会话时使用轻量级预热查询拉取当前账号/API Key 真实可用模型列表，随后立即关闭临时进程
      const cwd = await directory(params.cwd || "/home/agent/workspace").catch(() => "/home/agent");
      const userEnv = await loadEffectiveUserEnv(cwd, process.env.PATH || "").catch(() => ({}));
      const env = {...process.env, ...userEnv, DISABLE_AUTOUPDATER: "1"};
      if (params.apiKey) env.ANTHROPIC_API_KEY = string(params.apiKey, "API key");
      if (params.oauthToken) env.CLAUDE_CODE_OAUTH_TOKEN = string(params.oauthToken, "OAuth token");
      if (params.baseUrl) env.ANTHROPIC_BASE_URL = string(params.baseUrl, "provider URL").replace(/\/$/, "");
      const probeCtrl = new AbortController();
      const probeInput = inputQueue();
      let warm = null, q = null;
      try {
        warm = await sdk.startup({
          options: {
            cwd, env, pathToClaudeCodeExecutable: executable, abortController: probeCtrl,
            permissionMode: "plan", settingSources: ["user"], sandbox: {enabled: false},
          },
          initializeTimeoutMs: 45_000,
        });
        q = warm.query(probeInput);
        await q.initializationResult();
        const models = await q.supportedModels();
        cachedProbeModels = models;
        cachedProbeAgents = await q.supportedAgents().catch(() => cachedProbeAgents);
        return {models};
      } finally {
        probeInput.close();
        try { q?.close(); } catch {}
        try { warm?.close(); } catch {}
        probeCtrl.abort();
      }
    }
    case "command/list": return {commands:await getSession(params).query.supportedCommands()};
    default: throw new Error("Unsupported Claude Code bridge method: " + method);
  }
}
async function handle(packet) {
  object(packet,"RPC packet");
  if (typeof packet.id !== "string" && !Number.isSafeInteger(packet.id)) throw new Error("Invalid RPC identity");
  if (!Object.hasOwn(packet,"method")) {
    try { answer(packet); } catch {}
    return;
  }
  if (operations.has(packet.id)) throw new Error("Duplicate Claude Code request identity");
  if (operations.size>=64) throw new Error("Too many pending Claude Code operations");
  operations.add(packet.id);
  try { output({id:packet.id,result:await dispatch(string(packet.method,"method",200),object(packet.params ?? {},"RPC parameters"))}); }
  catch(error) {output({id:packet.id,error:{code:-32000,message:error.message}});}
  finally {operations.delete(packet.id);}
}
function shutdown() {
  if (closing) return;
  closing=true;
  for (const session of [...sessions.values()]) closeSession(session);
  process.stdin.pause();
  process.stdin.unref?.();
}
let buffered=Buffer.alloc(0);
process.stdin.on("data",chunk=>{
  try {
    buffered=Buffer.concat([buffered,chunk]);
    let end;
    while ((end=buffered.indexOf(10))>=0) {
      if (end>maximumFrame) throw new Error("Claude Code transport input limit exceeded");
      const line=buffered.subarray(0,end);buffered=buffered.subarray(end+1);
      const packet=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(line));
      if (packet.method === "initialized" && packet.id === undefined) continue;
      handle(packet).catch(()=>{process.exitCode=1;shutdown();});
    }
    if (buffered.length>maximumFrame) throw new Error("Claude Code transport input limit exceeded");
  } catch {process.exitCode=1;shutdown();}
});
process.stdin.once("end",shutdown);
process.stdin.once("error",shutdown);
process.stdout.once("error",shutdown);
for (const signal of ["SIGTERM","SIGINT"]) process.once(signal,shutdown);
