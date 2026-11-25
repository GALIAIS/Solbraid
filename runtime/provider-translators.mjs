import {randomUUID} from "node:crypto";

// 协议转换工具：支持 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Google Gemini 四种协议双向互通。

export function textOfContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return content == null ? "" : String(content);
  return content.map((part) => {
    if (typeof part === "string") return part;
    if (part?.type === "text" || part?.type === "input_text" || part?.type === "output_text") return part.text ?? "";
    return "";
  }).filter(Boolean).join("\n");
}

function parseDataUrl(url) {
  const m = typeof url === "string" && /^data:([^;,]+);base64,(.+)$/s.exec(url);
  return m ? {mediaType: m[1], data: m[2]} : null;
}

export function sanitizeGeminiSchema(schema) {
  if (!schema || typeof schema !== "object") return {type: "object", properties: {}};
  if (Array.isArray(schema)) return schema.map(sanitizeGeminiSchema);
  const out = {};
  for (const [key, val] of Object.entries(schema)) {
    if (["$schema", "$id", "$defs", "definitions", "additionalProperties", "strict", "title", "default", "examples", "const", "propertyNames"].includes(key)) continue;
    if (key === "type" && Array.isArray(val)) {
      const nonNull = val.filter((t) => t !== "null");
      out.type = nonNull[0] ?? "string";
      if (val.includes("null")) out.nullable = true;
    } else if (key === "anyOf" || key === "oneOf") {
      const choices = Array.isArray(val) ? val.filter((v) => v?.type !== "null") : [];
      if (choices.length === 1) Object.assign(out, sanitizeGeminiSchema(choices[0]));
      else if (choices.length > 1) out.type = "string";
      if (Array.isArray(val) && val.some((v) => v?.type === "null")) out.nullable = true;
    } else if (key === "properties" && val && typeof val === "object") {
      const props = {};
      for (const [pk, pv] of Object.entries(val)) props[pk] = sanitizeGeminiSchema(pv);
      out.properties = props;
    } else if (key === "items") {
      out.items = sanitizeGeminiSchema(val);
    } else if (key === "enum" && Array.isArray(val)) {
      out.enum = val.map((v) => String(v));
      if (!out.type) out.type = "string";
    } else {
      out[key] = val;
    }
  }
  if (out.type === "object" && !out.properties) out.properties = {};
  return out;
}

// 规范化 Responses 请求里的工具列表（展开 namespace、包装 custom 工具、忽略 OpenAI 内置非函数工具）
export function extractResponsesTools(rawTools) {
  const tools = [];
  const namespaceMap = new Map();
  const customSet = new Set();
  for (const t of rawTools ?? []) {
    if (!t || typeof t !== "object") continue;
    if (t.type === "function" && t.name) {
      tools.push({name: t.name, description: t.description ?? "", parameters: t.parameters ?? {type: "object", properties: {}}});
    } else if (t.type === "namespace" && t.name && Array.isArray(t.tools)) {
      for (const sub of t.tools) {
        if (sub?.type === "function" && sub.name) {
          const flat = `${t.name}__${sub.name}`;
          namespaceMap.set(flat, {namespace: t.name, name: sub.name});
          tools.push({name: flat, description: sub.description ?? "", parameters: sub.parameters ?? {type: "object", properties: {}}});
        }
      }
    } else if (t.type === "custom" && t.name) {
      customSet.add(t.name);
      tools.push({
        name: t.name,
        description: t.description ?? "",
        parameters: {type: "object", properties: {input: {type: "string", description: "Tool input"}}, required: ["input"]},
      });
    }
  }
  return {tools, namespaceMap, customSet};
}

function encodeToolName(namespace, name) {
  return namespace ? `${namespace}__${name}` : name;
}

export function responsesToChatRequest(body) {
  const {tools, namespaceMap, customSet} = extractResponsesTools(body.tools);
  const messages = [];
  if (typeof body.instructions === "string" && body.instructions.trim()) {
    messages.push({role: "system", content: body.instructions});
  }
  let pendingAssistant = null;
  const flushAssistant = () => { if (pendingAssistant) { messages.push(pendingAssistant); pendingAssistant = null; } };
  const input = typeof body.input === "string"
    ? [{type: "message", role: "user", content: [{type: "input_text", text: body.input}]}]
    : (Array.isArray(body.input) ? body.input : []);
  for (const item of input) {
    if (!item || typeof item !== "object") continue;
    if (item.type === "message" || (!item.type && item.role)) {
      const role = item.role === "developer" ? "system" : (item.role ?? "user");
      if (role !== "assistant") flushAssistant();
      const parts = Array.isArray(item.content) ? item.content : [{type: "input_text", text: String(item.content ?? "")}];
      const hasImage = parts.some((p) => p?.type === "input_image");
      const content = hasImage
        ? parts.map((p) => p?.type === "input_image" ? {type: "image_url", image_url: {url: p.image_url}} : {type: "text", text: p?.text ?? ""})
        : parts.map((p) => p?.text ?? "").join("\n");
      if (role === "assistant") {
        if (pendingAssistant) pendingAssistant.content = [pendingAssistant.content, content].filter(Boolean).join("\n");
        else pendingAssistant = {role: "assistant", content};
      } else {
        messages.push({role, content});
      }
    } else if (item.type === "function_call" || item.type === "custom_tool_call") {
      if (!pendingAssistant) pendingAssistant = {role: "assistant", content: null};
      if (!pendingAssistant.tool_calls) pendingAssistant.tool_calls = [];
      const args = item.type === "custom_tool_call" ? JSON.stringify({input: item.input ?? ""}) : (item.arguments ?? "{}");
      pendingAssistant.tool_calls.push({
        id: item.call_id ?? item.id ?? ("call_" + randomUUID().slice(0, 8)),
        type: "function",
        function: {name: encodeToolName(item.namespace, item.name), arguments: args},
      });
    } else if (item.type === "function_call_output" || item.type === "custom_tool_call_output") {
      flushAssistant();
      messages.push({role: "tool", tool_call_id: item.call_id, content: textOfContent(item.output)});
    }
  }
  flushAssistant();
  const req = {model: body.model, messages, stream: true, stream_options: {include_usage: true}};
  if (tools.length) {
    req.tools = tools.map((t) => ({type: "function", function: {name: t.name, description: t.description, parameters: t.parameters}}));
    if (typeof body.tool_choice === "string") req.tool_choice = body.tool_choice;
    else if (body.tool_choice?.type === "function" && body.tool_choice.name) req.tool_choice = {type: "function", function: {name: body.tool_choice.name}};
  }
  if (body.max_output_tokens) req.max_tokens = body.max_output_tokens;
  if (body.temperature != null) req.temperature = body.temperature;
  if (body.top_p != null) req.top_p = body.top_p;
  if (body.stop != null) req.stop = body.stop;
  if (body.reasoning?.effort) req.reasoning_effort = body.reasoning.effort;
  if (body.text?.format) req.response_format = body.text.format.type === "json_schema"
    ? {type: "json_schema", json_schema: {name: body.text.format.name || "response", schema: body.text.format.schema, strict: body.text.format.strict}}
    : body.text.format;
  return {req, namespaceMap, customSet};
}

export function responsesToAnthropicRequest(body) {
  const {tools, namespaceMap, customSet} = extractResponsesTools(body.tools);
  const systemParts = [];
  if (typeof body.instructions === "string" && body.instructions.trim()) systemParts.push(body.instructions);
  const messages = [];
  const pushMsg = (role, blocks) => {
    if (!blocks.length) return;
    const prev = messages[messages.length - 1];
    if (prev && prev.role === role) prev.content.push(...blocks);
    else messages.push({role, content: [...blocks]});
  };
  const input = typeof body.input === "string"
    ? [{type: "message", role: "user", content: [{type: "input_text", text: body.input}]}]
    : (Array.isArray(body.input) ? body.input : []);
  for (const item of input) {
    if (!item || typeof item !== "object") continue;
    if (item.type === "message" || (!item.type && item.role)) {
      if (item.role === "system" || item.role === "developer") {
        const text = textOfContent(item.content);
        if (text) systemParts.push(text);
        continue;
      }
      const role = item.role === "assistant" ? "assistant" : "user";
      const parts = Array.isArray(item.content) ? item.content : [{type: "input_text", text: String(item.content ?? "")}];
      const blocks = [];
      for (const p of parts) {
        if (p?.type === "input_image") {
          const parsed = parseDataUrl(p.image_url);
          if (parsed) blocks.push({type: "image", source: {type: "base64", media_type: parsed.mediaType, data: parsed.data}});
          else if (p.image_url) blocks.push({type: "image", source: {type: "url", url: p.image_url}});
        } else {
          const text = p?.text ?? "";
          if (text) blocks.push({type: "text", text});
        }
      }
      pushMsg(role, blocks);
    } else if (item.type === "function_call" || item.type === "custom_tool_call") {
      let parsedInput = {};
      if (item.type === "custom_tool_call") parsedInput = {input: item.input ?? ""};
      else { try { parsedInput = JSON.parse(item.arguments || "{}"); } catch { parsedInput = {}; } }
      pushMsg("assistant", [{type: "tool_use", id: item.call_id ?? item.id ?? ("toolu_" + randomUUID().slice(0, 8)), name: encodeToolName(item.namespace, item.name), input: parsedInput}]);
    } else if (item.type === "function_call_output" || item.type === "custom_tool_call_output") {
      pushMsg("user", [{type: "tool_result", tool_use_id: item.call_id, content: textOfContent(item.output)}]);
    }
  }
  if (!messages.length) messages.push({role: "user", content: [{type: "text", text: "Continue"}]});
  const req = {model: body.model, max_tokens: body.max_output_tokens ?? 8192, messages, stream: true};
  if (systemParts.length) req.system = systemParts.join("\n\n");
  if (tools.length) req.tools = tools.map((t) => ({name: t.name, description: t.description, input_schema: t.parameters}));
  if (body.tool_choice === "required") req.tool_choice = {type: "any"};
  else if (body.tool_choice === "none" || body.tool_choice === "auto") req.tool_choice = {type: body.tool_choice};
  else if (body.tool_choice?.type === "function") req.tool_choice = {type: "tool", name: encodeToolName(body.tool_choice.namespace, body.tool_choice.name)};
  if (body.stop != null) req.stop_sequences = Array.isArray(body.stop) ? body.stop : [body.stop];
  if (body.text?.format?.type === "json_schema") req.output_config = {format: {type: "json_schema", schema: body.text.format.schema}};
  if (body.temperature != null) req.temperature = body.temperature;
  if (body.top_p != null) req.top_p = body.top_p;
  return {req, namespaceMap, customSet};
}

export function responsesToGeminiRequest(body) {
  const {tools, namespaceMap, customSet} = extractResponsesTools(body.tools);
  const systemParts = [];
  if (typeof body.instructions === "string" && body.instructions.trim()) systemParts.push(body.instructions);
  const contents = [];
  const callNames = new Map();
  const pushContent = (role, parts) => {
    if (!parts.length) return;
    const prev = contents[contents.length - 1];
    if (prev && prev.role === role) prev.parts.push(...parts);
    else contents.push({role, parts: [...parts]});
  };
  const input = typeof body.input === "string"
    ? [{type: "message", role: "user", content: [{type: "input_text", text: body.input}]}]
    : (Array.isArray(body.input) ? body.input : []);
  for (const item of input) {
    if (!item || typeof item !== "object") continue;
    if (item.type === "message" || (!item.type && item.role)) {
      if (item.role === "system" || item.role === "developer") {
        const text = textOfContent(item.content);
        if (text) systemParts.push(text);
        continue;
      }
      const role = item.role === "assistant" ? "model" : "user";
      const raw = Array.isArray(item.content) ? item.content : [{type: "input_text", text: String(item.content ?? "")}];
      const parts = [];
      for (const p of raw) {
        if (p?.type === "input_image") {
          const parsed = parseDataUrl(p.image_url);
          if (parsed) parts.push({inlineData: {mimeType: parsed.mediaType, data: parsed.data}});
        } else if (p?.text) {
          parts.push({text: p.text});
        }
      }
      pushContent(role, parts);
    } else if (item.type === "function_call" || item.type === "custom_tool_call") {
      const name = encodeToolName(item.namespace, item.name);
      if (item.call_id) callNames.set(item.call_id, name);
      let args = {};
      if (item.type === "custom_tool_call") args = {input: item.input ?? ""};
      else { try { args = JSON.parse(item.arguments || "{}"); } catch { args = {}; } }
      pushContent("model", [{functionCall: {name, args}}]);
    } else if (item.type === "function_call_output" || item.type === "custom_tool_call_output") {
      const name = callNames.get(item.call_id) ?? "tool";
      pushContent("user", [{functionResponse: {name, response: {output: textOfContent(item.output)}}}]);
    }
  }
  if (!contents.length) contents.push({role: "user", parts: [{text: "Continue"}]});
  const req = {contents};
  if (systemParts.length) req.systemInstruction = {parts: [{text: systemParts.join("\n\n")}]};
  if (tools.length) req.tools = [{functionDeclarations: tools.map((t) => ({name: t.name, description: t.description, parameters: sanitizeGeminiSchema(t.parameters)}))}];
  const gen = {};
  if (body.max_output_tokens) gen.maxOutputTokens = body.max_output_tokens;
  if (body.temperature != null) gen.temperature = body.temperature;
  if (body.top_p != null) gen.topP = body.top_p;
  if (body.stop != null) gen.stopSequences = Array.isArray(body.stop) ? body.stop : [body.stop];
  if (body.text?.format && body.text.format.type !== "text") {
    gen.responseMimeType = "application/json";
    if (body.text.format.schema) gen.responseSchema = sanitizeGeminiSchema(body.text.format.schema);
  }
  if (body.tool_choice === "required" || body.tool_choice === "none" || body.tool_choice === "auto") {
    req.toolConfig = {functionCallingConfig: {mode: body.tool_choice === "required" ? "ANY" : body.tool_choice.toUpperCase()}};
  } else if (body.tool_choice?.type === "function") {
    req.toolConfig = {functionCallingConfig: {mode: "ANY", allowedFunctionNames: [encodeToolName(body.tool_choice.namespace, body.tool_choice.name)]}};
  }
  if (Object.keys(gen).length) req.generationConfig = gen;
  return {req, namespaceMap, customSet};
}

export function anthropicToChatRequest(body) {
  const messages = [];
  const systemText = textOfContent(body.system);
  if (systemText) messages.push({role: "system", content: systemText});
  for (const msg of body.messages ?? []) {
    if (!msg) continue;
    if (typeof msg.content === "string") {
      messages.push({role: msg.role, content: msg.content});
      continue;
    }
    const blocks = Array.isArray(msg.content) ? msg.content : [];
    if (msg.role === "user") {
      const userParts = [];
      for (const b of blocks) {
        if (b?.type === "tool_result") {
          const contentText = textOfContent(b.content);
          messages.push({role: "tool", tool_call_id: b.tool_use_id, content: b.is_error ? `[ERROR] ${contentText}` : contentText});
        } else if (b?.type === "text") {
          userParts.push({type: "text", text: b.text ?? ""});
        } else if (b?.type === "document" && b.source) {
          const docText = b.source.type === "text" ? (b.source.data || "") : `[附带文档: ${b.title || b.source.media_type || "document"}]`;
          userParts.push({type: "text", text: docText});
        } else if (b?.type === "image" && b.source) {
          const url = b.source.type === "base64" ? `data:${b.source.media_type};base64,${b.source.data}` : b.source.url;
          if (url) userParts.push({type: "image_url", image_url: {url}});
        }
      }
      if (userParts.length) {
        const hasImg = userParts.some((p) => p.type === "image_url");
        messages.push({role: "user", content: hasImg ? userParts : userParts.map((p) => p.text).join("\n")});
      }
    } else if (msg.role === "assistant") {
      const texts = [];
      const toolCalls = [];
      for (const b of blocks) {
        if (b?.type === "text" && b.text) texts.push(b.text);
        else if (b?.type === "tool_use") {
          toolCalls.push({id: b.id, type: "function", function: {name: b.name, arguments: JSON.stringify(b.input ?? {})}});
        }
      }
      const entry = {role: "assistant", content: texts.length ? texts.join("\n") : null};
      if (toolCalls.length) entry.tool_calls = toolCalls;
      if (entry.content !== null || entry.tool_calls) messages.push(entry);
    }
  }
  const isReasoningModel = /^(?:o[1-9]|gpt-5)/i.test(String(body.model || ""));
  const req = {model: body.model, messages, stream: true, stream_options: {include_usage: true}};
  if (body.max_tokens) {
    if (isReasoningModel) req.max_completion_tokens = body.max_tokens;
    else req.max_tokens = body.max_tokens;
  }
  if (body.temperature != null && !isReasoningModel) req.temperature = body.temperature;
  if (body.top_p != null && !isReasoningModel) req.top_p = body.top_p;
  if (Array.isArray(body.stop_sequences) && body.stop_sequences.length) req.stop = body.stop_sequences;
  const tools = (body.tools ?? []).filter((t) => t && t.name && t.input_schema);
  if (tools.length) {
    req.tools = tools.map((t) => ({type: "function", function: {name: t.name, description: t.description ?? "", parameters: t.input_schema}}));
    const tc = body.tool_choice;
    if (tc?.type === "auto") req.tool_choice = "auto";
    else if (tc?.type === "any") req.tool_choice = "required";
    else if (tc?.type === "none") req.tool_choice = "none";
    else if (tc?.type === "tool" && tc.name) req.tool_choice = {type: "function", function: {name: tc.name}};
  }
  return req;
}

export function anthropicToResponsesRequest(body) {
  const input = [];
  for (const msg of body.messages ?? []) {
    if (!msg) continue;
    if (typeof msg.content === "string") {
      input.push({type: "message", role: msg.role, content: [{type: msg.role === "assistant" ? "output_text" : "input_text", text: msg.content}]});
      continue;
    }
    const blocks = Array.isArray(msg.content) ? msg.content : [];
    if (msg.role === "user") {
      const parts = [];
      for (const b of blocks) {
        if (b?.type === "tool_result") {
          const contentText = textOfContent(b.content);
          input.push({type: "function_call_output", call_id: b.tool_use_id, output: b.is_error ? `[ERROR] ${contentText}` : contentText});
        } else if (b?.type === "text") {
          parts.push({type: "input_text", text: b.text ?? ""});
        } else if (b?.type === "document" && b.source) {
          const docText = b.source.type === "text" ? (b.source.data || "") : `[附带文档: ${b.title || b.source.media_type || "document"}]`;
          parts.push({type: "input_text", text: docText});
        } else if (b?.type === "image" && b.source) {
          const url = b.source.type === "base64" ? `data:${b.source.media_type};base64,${b.source.data}` : b.source.url;
          if (url) parts.push({type: "input_image", image_url: url});
        }
      }
      if (parts.length) input.push({type: "message", role: "user", content: parts});
    } else if (msg.role === "assistant") {
      const parts = [];
      for (const b of blocks) {
        if (b?.type === "text" && b.text) parts.push({type: "output_text", text: b.text});
        else if (b?.type === "tool_use") {
          if (parts.length) { input.push({type: "message", role: "assistant", content: parts.splice(0)}); }
          input.push({type: "function_call", call_id: b.id, name: b.name, arguments: JSON.stringify(b.input ?? {})});
        }
      }
      if (parts.length) input.push({type: "message", role: "assistant", content: parts});
    }
  }
  const req = {model: body.model, input, stream: true};
  const systemText = textOfContent(body.system);
  if (systemText) req.instructions = systemText;
  if (body.max_tokens) req.max_output_tokens = body.max_tokens;
  if (body.temperature != null) req.temperature = body.temperature;
  if (body.top_p != null) req.top_p = body.top_p;
  if (Array.isArray(body.stop_sequences) && body.stop_sequences.length) req.stop = body.stop_sequences;
  if (body.thinking?.type === "enabled" && body.thinking.budget_tokens) {
    const budget = Number(body.thinking.budget_tokens);
    req.reasoning = {effort: budget >= 16000 ? "high" : budget >= 6000 ? "medium" : "low", summary: "auto"};
  }
  const tools = (body.tools ?? []).filter((t) => t && t.name && t.input_schema);
  if (tools.length) {
    req.tools = tools.map((t) => ({type: "function", name: t.name, description: t.description ?? "", parameters: t.input_schema}));
    const tc = body.tool_choice;
    if (tc?.type === "auto") req.tool_choice = "auto";
    else if (tc?.type === "any") req.tool_choice = "required";
    else if (tc?.type === "none") req.tool_choice = "none";
    else if (tc?.type === "tool" && tc.name) req.tool_choice = {type: "function", name: tc.name};
  }
  return req;
}

export function anthropicToGeminiRequest(body) {
  const contents = [];
  const toolNames = new Map();
  const push = (role, parts) => {
    if (!parts.length) return;
    const prev = contents[contents.length - 1];
    if (prev && prev.role === role) prev.parts.push(...parts);
    else contents.push({role, parts: [...parts]});
  };
  for (const msg of body.messages ?? []) {
    if (!msg) continue;
    const role = msg.role === "assistant" ? "model" : "user";
    if (typeof msg.content === "string") { push(role, [{text: msg.content}]); continue; }
    const parts = [];
    for (const b of Array.isArray(msg.content) ? msg.content : []) {
      if (b?.type === "text" && b.text) parts.push({text: b.text});
      else if (b?.type === "document" && b.source) {
        if (b.source.type === "base64" && b.source.media_type) parts.push({inlineData: {mimeType: b.source.media_type, data: b.source.data}});
        else if (b.source.type === "text") parts.push({text: b.source.data || ""});
      } else if (b?.type === "image" && b.source?.type === "base64") parts.push({inlineData: {mimeType: b.source.media_type, data: b.source.data}});
      else if (b?.type === "image" && b.source?.url) parts.push({fileData: {mimeType: b.source.media_type || "image/png", fileUri: b.source.url}});
      else if (b?.type === "tool_use") {
        toolNames.set(b.id, b.name);
        parts.push({functionCall: {name: b.name, args: b.input ?? {}}});
      } else if (b?.type === "tool_result") {
        parts.push({functionResponse: {name: toolNames.get(b.tool_use_id) ?? "tool", response: {output: textOfContent(b.content), ...(b.is_error ? {isError: true} : {})}}});
      }
    }
    push(role, parts);
  }
  const req = {contents: contents.length ? contents : [{role: "user", parts: [{text: "Continue"}]}]};
  const systemText = textOfContent(body.system);
  if (systemText) req.systemInstruction = {parts: [{text: systemText}]};
  const tools = (body.tools ?? []).filter((t) => t && t.name && t.input_schema);
  if (tools.length) {
    req.tools = [{functionDeclarations: tools.map((t) => ({name: t.name, description: t.description ?? "", parameters: sanitizeGeminiSchema(t.input_schema)}))}];
    const tc = body.tool_choice;
    if (tc?.type === "auto") req.toolConfig = {functionCallingConfig: {mode: "AUTO"}};
    else if (tc?.type === "any") req.toolConfig = {functionCallingConfig: {mode: "ANY"}};
    else if (tc?.type === "none") req.toolConfig = {functionCallingConfig: {mode: "NONE"}};
    else if (tc?.type === "tool" && tc.name) req.toolConfig = {functionCallingConfig: {mode: "ANY", allowedFunctionNames: [tc.name]}};
  }
  const gen = {};
  if (body.max_tokens) gen.maxOutputTokens = body.max_tokens;
  if (body.temperature != null) gen.temperature = body.temperature;
  if (body.top_p != null) gen.topP = body.top_p;
  if (Array.isArray(body.stop_sequences) && body.stop_sequences.length) gen.stopSequences = body.stop_sequences;
  if (body.thinking?.type === "enabled" && body.thinking.budget_tokens) {
    gen.thinkingConfig = {thinkingBudget: Number(body.thinking.budget_tokens), includeThoughts: true};
  }
  if (Object.keys(gen).length) req.generationConfig = gen;
  return req;
}

export async function* parseSse(stream) {
  const decoder = new TextDecoder("utf-8", {fatal: true});
  let buf = "";
  let eventName = "";
  let dataLines = [];
  let frameSize = 0;
  const frame = () => {
    const data = dataLines.join("\n");
    if (data !== "[DONE]") {
      const value = JSON.parse(data);
      if (value.error || value.type === "error" || value.type === "response.failed" || value.response?.status === "failed") {
        throw new Error(value.error?.message || value.response?.error?.message || value.message || "Upstream model stream failed");
      }
    }
    return {event: eventName, data};
  };
  for await (const chunk of stream) {
    buf += decoder.decode(chunk, {stream: true});
    if (buf.length + frameSize > 8 * 1024 * 1024) throw new Error("Upstream SSE frame exceeded 8 MB");
    let idx;
    while ((idx = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, "");
      buf = buf.slice(idx + 1);
      if (line === "") {
        if (dataLines.length) {
          yield frame();
          eventName = "";
          dataLines = [];
          frameSize = 0;
        }
      } else if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trimStart());
        frameSize += line.length;
      }
    }
  }
  buf += decoder.decode();
  if (buf.startsWith("data:")) dataLines.push(buf.slice(5).trimStart());
  else if (buf.trim()) throw new Error("Upstream SSE stream was truncated");
  if (dataLines.length) yield frame();
}

function writeSse(res, event, payload) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`);
}

// 统一生成 Codex 所需的 OpenAI Responses SSE 事件流。
export class ResponsesSseWriter {
  constructor(res, model, namespaceMap = new Map(), customSet = new Set()) {
    this.res = res;
    this.model = model;
    this.namespaceMap = namespaceMap;
    this.customSet = customSet;
    this.responseId = "resp_" + randomUUID().replaceAll("-", "").slice(0, 20);
    this.nextIndex = 0;
    this.reasoning = null;
    this.message = null;
    this.calls = new Map();
    this.completedOutput = [];
    this.usage = {input_tokens: 0, output_tokens: 0, total_tokens: 0};
    this.started = false;
  }
  start() {
    if (this.started) return;
    this.started = true;
    this.res.writeHead(200, {"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive"});
    writeSse(this.res, "response.created", {type: "response.created", response: {id: this.responseId, object: "response", status: "in_progress", model: this.model, output: []}});
  }
  reasoningDelta(delta) {
    if (!delta) return;
    this.start();
    if (!this.reasoning) {
      const id = "rs_" + randomUUID().slice(0, 12);
      const index = this.nextIndex++;
      this.reasoning = {id, index, text: ""};
      writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: {id, type: "reasoning", summary: []}});
      writeSse(this.res, "response.reasoning_summary_part.added", {type: "response.reasoning_summary_part.added", item_id: id, output_index: index, summary_index: 0, part: {type: "summary_text", text: ""}});
    }
    this.reasoning.text += delta;
    writeSse(this.res, "response.reasoning_summary_text.delta", {type: "response.reasoning_summary_text.delta", item_id: this.reasoning.id, output_index: this.reasoning.index, summary_index: 0, delta});
  }
  closeReasoning() {
    if (!this.reasoning) return;
    const r = this.reasoning;
    this.reasoning = null;
    const item = {id: r.id, type: "reasoning", summary: [{type: "summary_text", text: r.text}]};
    this.completedOutput.push(item);
    writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: r.index, item});
  }
  textDelta(delta) {
    if (!delta) return;
    this.start();
    this.closeReasoning();
    if (!this.message) {
      const id = "msg_" + randomUUID().slice(0, 12);
      const index = this.nextIndex++;
      this.message = {id, index, text: ""};
      writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: {id, type: "message", role: "assistant", status: "in_progress", content: []}});
      writeSse(this.res, "response.content_part.added", {type: "response.content_part.added", item_id: id, output_index: index, content_index: 0, part: {type: "output_text", text: ""}});
    }
    this.message.text += delta;
    writeSse(this.res, "response.output_text.delta", {type: "response.output_text.delta", item_id: this.message.id, output_index: this.message.index, content_index: 0, delta});
  }
  closeMessage() {
    if (!this.message) return;
    const m = this.message;
    this.message = null;
    writeSse(this.res, "response.output_text.done", {type: "response.output_text.done", item_id: m.id, output_index: m.index, content_index: 0, text: m.text});
    writeSse(this.res, "response.content_part.done", {type: "response.content_part.done", item_id: m.id, output_index: m.index, content_index: 0, part: {type: "output_text", text: m.text}});
    const item = {id: m.id, type: "message", role: "assistant", status: "completed", content: [{type: "output_text", text: m.text}]};
    this.completedOutput.push(item);
    writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: m.index, item});
  }
  toolDelta(slot, callId, rawName, argsDelta) {
    this.start();
    this.closeReasoning();
    this.closeMessage();
    let call = this.calls.get(slot);
    if (!call) {
      const id = "fc_" + randomUUID().slice(0, 12);
      const index = this.nextIndex++;
      const ns = this.namespaceMap.get(rawName);
      const isCustom = this.customSet.has(rawName);
      call = {id, index, callId: callId || ("call_" + randomUUID().slice(0, 12)), name: ns ? ns.name : (rawName || ""), namespace: ns ? ns.namespace : null, isCustom, arguments: ""};
      this.calls.set(slot, call);
      const added = isCustom
        ? {id, type: "custom_tool_call", call_id: call.callId, name: call.name, input: "", status: "in_progress"}
        : {id, type: "function_call", call_id: call.callId, name: call.name, ...(call.namespace ? {namespace: call.namespace} : {}), arguments: "", status: "in_progress"};
      writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: added});
    } else {
      if (callId && !call.callId) call.callId = callId;
      if (rawName && !call.name) {
        const ns = this.namespaceMap.get(rawName);
        call.name = ns ? ns.name : rawName;
        call.namespace = ns ? ns.namespace : null;
      }
    }
    if (argsDelta) {
      call.arguments += argsDelta;
      if (!call.isCustom) {
        writeSse(this.res, "response.function_call_arguments.delta", {type: "response.function_call_arguments.delta", item_id: call.id, output_index: call.index, delta: argsDelta});
      }
    }
  }
  setUsage(input, output) {
    if (input != null) this.usage.input_tokens = input;
    if (output != null) this.usage.output_tokens = output;
    this.usage.total_tokens = (this.usage.input_tokens || 0) + (this.usage.output_tokens || 0);
  }
  finish(stopReason = "stop") {
    this.start();
    this.closeReasoning();
    this.closeMessage();
    for (const call of this.calls.values()) {
      let item;
      if (call.isCustom) {
        let inputText = call.arguments;
        try { const parsed = JSON.parse(call.arguments); if (typeof parsed?.input === "string") inputText = parsed.input; } catch {}
        item = {id: call.id, type: "custom_tool_call", call_id: call.callId, name: call.name, input: inputText, status: "completed"};
      } else {
        item = {id: call.id, type: "function_call", call_id: call.callId, name: call.name, ...(call.namespace ? {namespace: call.namespace} : {}), arguments: call.arguments || "{}", status: "completed"};
      }
      this.completedOutput.push(item);
      writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: call.index, item});
    }
    this.calls.clear();
    const limited = ["length", "max_tokens", "MAX_TOKENS"].includes(stopReason);
    const type = limited ? "response.incomplete" : "response.completed";
    writeSse(this.res, type, {type, response: {id: this.responseId, object: "response", status: limited ? "incomplete" : "completed", model: this.model,
      ...(limited ? {incomplete_details: {reason: "max_output_tokens"}} : {}), output: this.completedOutput, usage: this.usage}});
    this.res.end();
  }
}

// 统一生成 Claude Code 所需的 Anthropic Messages SSE 事件流。
export class AnthropicSseWriter {
  constructor(res, model) {
    this.res = res;
    this.model = model;
    this.messageId = "msg_" + randomUUID().replaceAll("-", "").slice(0, 20);
    this.nextIndex = 0;
    this.openBlock = null;
    this.toolBlocks = new Map();
    this.sawTool = false;
    this.inputTokens = 0;
    this.outputTokens = 0;
    this.started = false;
  }
  start(inputTokens = 0) {
    if (this.started) return;
    this.started = true;
    this.inputTokens = inputTokens;
    this.res.writeHead(200, {"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive"});
    writeSse(this.res, "message_start", {type: "message_start", message: {id: this.messageId, type: "message", role: "assistant", content: [], model: this.model, stop_reason: null, stop_sequence: null, usage: {input_tokens: inputTokens, output_tokens: 1}}});
  }
  closeCurrent() {
    if (!this.openBlock) return;
    writeSse(this.res, "content_block_stop", {type: "content_block_stop", index: this.openBlock.index});
    this.openBlock = null;
  }
  thinkingDelta(delta) {
    if (!delta) return;
    this.start();
    if (this.openBlock?.type !== "thinking") {
      this.closeCurrent();
      const index = this.nextIndex++;
      this.openBlock = {type: "thinking", index};
      writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "thinking", thinking: ""}});
    }
    writeSse(this.res, "content_block_delta", {type: "content_block_delta", index: this.openBlock.index, delta: {type: "thinking_delta", thinking: delta}});
  }
  textDelta(delta) {
    if (!delta) return;
    this.start();
    if (this.openBlock?.type !== "text") {
      this.closeCurrent();
      const index = this.nextIndex++;
      this.openBlock = {type: "text", index};
      writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "text", text: ""}});
    }
    writeSse(this.res, "content_block_delta", {type: "content_block_delta", index: this.openBlock.index, delta: {type: "text_delta", text: delta}});
  }
  toolDelta(slot, callId, name, partialJson) {
    this.start();
    this.sawTool = true;
    let block = this.toolBlocks.get(slot);
    if (!block) {
      block = {id: callId || ("toolu_" + randomUUID().slice(0, 12)), name: name || "", json: "", emitted: false};
      this.toolBlocks.set(slot, block);
    }
    if (name) block.name = name;
    if (partialJson) block.json += partialJson;
  }
  closeTool(slot) {
    const block = this.toolBlocks.get(slot);
    if (!block || block.emitted) return;
    if (!block.name) throw new Error("Upstream tool call is missing its name");
    JSON.parse(block.json || "{}");
    this.closeCurrent();
    const index = this.nextIndex++;
    writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "tool_use", id: block.id, name: block.name, input: {}}});
    writeSse(this.res, "content_block_delta", {type: "content_block_delta", index, delta: {type: "input_json_delta", partial_json: block.json || "{}"}});
    writeSse(this.res, "content_block_stop", {type: "content_block_stop", index});
    block.emitted = true;
  }
  finish(stopReason = null) {
    this.start();
    this.closeCurrent();
    for (const slot of this.toolBlocks.keys()) this.closeTool(slot);
    const reason = stopReason === "max_tokens" ? stopReason : this.sawTool ? "tool_use" : (stopReason || "end_turn");
    writeSse(this.res, "message_delta", {type: "message_delta", delta: {stop_reason: reason, stop_sequence: null}, usage: {input_tokens: this.inputTokens, output_tokens: this.outputTokens}});
    writeSse(this.res, "message_stop", {type: "message_stop"});
    this.res.end();
  }
}

export async function pipeChatToResponses(upstream, res, model, namespaceMap, customSet, writer = new ResponsesSseWriter(res, model, namespaceMap, customSet)) {
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    const choice = data.choices?.[0]?.message;
    if (data.error || !choice) throw new Error(data.error?.message || "Upstream returned no completion");
    if (choice?.reasoning_content || choice?.reasoning) writer.reasoningDelta(choice.reasoning_content || choice.reasoning);
    if (choice?.content) writer.textDelta(choice.content);
    for (const [i, tc] of (choice?.tool_calls ?? []).entries()) writer.toolDelta(i, tc.id, tc.function?.name, tc.function?.arguments ?? "{}");
    if (data.usage) writer.setUsage(data.usage.prompt_tokens, data.usage.completion_tokens);
    writer.finish(data.choices?.[0]?.finish_reason);
    return;
  }
  let completed = false;
  let stopReason = "stop";
  for await (const ev of parseSse(upstream.body)) {
    if (ev.data === "[DONE]") { completed = true; break; }
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    if (chunk.usage) writer.setUsage(chunk.usage.prompt_tokens, chunk.usage.completion_tokens);
    if (chunk.choices?.[0]?.finish_reason) { completed = true; stopReason = chunk.choices[0].finish_reason; }
    const delta = chunk.choices?.[0]?.delta;
    if (!delta) continue;
    if (delta.reasoning_content) writer.reasoningDelta(delta.reasoning_content);
    else if (delta.reasoning) writer.reasoningDelta(delta.reasoning);
    if (delta.content) writer.textDelta(delta.content);
    if (Array.isArray(delta.tool_calls)) {
      for (const tc of delta.tool_calls) writer.toolDelta(tc.index ?? 0, tc.id, tc.function?.name, tc.function?.arguments ?? "");
    }
  }
  if (!completed) throw new Error("Chat stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeAnthropicToResponses(upstream, res, model, namespaceMap, customSet, writer = new ResponsesSseWriter(res, model, namespaceMap, customSet)) {
  if (!(upstream.headers.get("content-type") || "").includes("text/event-stream")) {
    const data = await upstream.json();
    if (data.error || !Array.isArray(data.content)) throw new Error(data.error?.message || "Invalid Anthropic response");
    for (const [index, block] of data.content.entries()) {
      if (block.type === "text") writer.textDelta(block.text);
      else if (block.type === "thinking") writer.reasoningDelta(block.thinking);
      else if (block.type === "tool_use") writer.toolDelta(index, block.id, block.name, JSON.stringify(block.input ?? {}));
    }
    writer.setUsage(data.usage?.input_tokens, data.usage?.output_tokens);
    writer.finish(data.stop_reason);
    return;
  }
  let completed = false;
  let stopReason = "end_turn";
  for await (const ev of parseSse(upstream.body)) {
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    const type = chunk.type || ev.event;
    if (type === "message_start" && chunk.message?.usage) writer.setUsage(chunk.message.usage.input_tokens, chunk.message.usage.output_tokens);
    else if (type === "content_block_start" && chunk.content_block?.type === "tool_use") writer.toolDelta(chunk.index, chunk.content_block.id, chunk.content_block.name, "");
    else if (type === "content_block_delta") {
      if (chunk.delta?.type === "thinking_delta") writer.reasoningDelta(chunk.delta.thinking);
      else if (chunk.delta?.type === "text_delta") writer.textDelta(chunk.delta.text);
      else if (chunk.delta?.type === "input_json_delta") writer.toolDelta(chunk.index, null, null, chunk.delta.partial_json);
    } else if (type === "message_delta") {
      if (chunk.usage) writer.setUsage(null, chunk.usage.output_tokens);
      stopReason = chunk.delta?.stop_reason || stopReason;
    } else if (type === "message_stop") completed = true;
  }
  if (!completed) throw new Error("Anthropic stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeGeminiToResponses(upstream, res, model, namespaceMap, customSet, writer = new ResponsesSseWriter(res, model, namespaceMap, customSet)) {
  let callIndex = 0;
  let stopReason = null;
  const handle = (chunk) => {
    if (chunk.error || chunk.promptFeedback?.blockReason) throw new Error(chunk.error?.message || "Gemini blocked the prompt: " + chunk.promptFeedback.blockReason);
    if (chunk.candidates?.[0]?.finishReason) stopReason = chunk.candidates[0].finishReason;
    if (chunk.usageMetadata) writer.setUsage(chunk.usageMetadata.promptTokenCount, chunk.usageMetadata.candidatesTokenCount);
    for (const part of chunk.candidates?.[0]?.content?.parts ?? []) {
      if (part.thought && part.text) writer.reasoningDelta(part.text);
      else if (part.text) writer.textDelta(part.text);
      else if (part.functionCall) writer.toolDelta(callIndex++, "call_" + randomUUID().slice(0, 12), part.functionCall.name, JSON.stringify(part.functionCall.args ?? {}));
    }
  };
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    for (const item of Array.isArray(data) ? data : [data]) handle(item);
    if (!stopReason) throw new Error("Gemini response ended before completion");
    writer.finish(stopReason);
    return;
  }
  for await (const ev of parseSse(upstream.body)) {
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    handle(chunk);
  }
  if (!stopReason) throw new Error("Gemini stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeChatToAnthropic(upstream, res, model) {
  const writer = new AnthropicSseWriter(res, model);
  let stopReason = "end_turn";
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    if (data.error || !data.choices?.[0]?.message) throw new Error(data.error?.message || "Upstream returned no completion");
    writer.start(data.usage?.prompt_tokens ?? 0);
    writer.outputTokens = data.usage?.completion_tokens ?? 1;
    const msg = data.choices?.[0]?.message;
    if (msg?.reasoning_content || msg?.reasoning) writer.thinkingDelta(msg.reasoning_content || msg.reasoning);
    if (msg?.content) writer.textDelta(msg.content);
    for (const [i, tc] of (msg?.tool_calls ?? []).entries()) {
      writer.toolDelta(i, tc.id, tc.function?.name, tc.function?.arguments ?? "{}");
      writer.closeTool(i);
    }
    writer.finish(data.choices?.[0]?.finish_reason === "length" ? "max_tokens" : "end_turn");
    return;
  }
  let completed = false;
  for await (const ev of parseSse(upstream.body)) {
    if (ev.data === "[DONE]") { completed = true; break; }
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    if (chunk.usage) {
      writer.start(chunk.usage.prompt_tokens ?? 0);
      writer.outputTokens = chunk.usage.completion_tokens ?? writer.outputTokens;
    }
    const choice = chunk.choices?.[0];
    if (!choice) continue;
    if (choice.finish_reason) completed = true;
    if (choice.finish_reason === "length") stopReason = "max_tokens";
    else if (choice.finish_reason === "tool_calls") stopReason = "tool_use";
    const delta = choice.delta;
    if (!delta) continue;
    if (delta.reasoning_content) writer.thinkingDelta(delta.reasoning_content);
    else if (delta.reasoning) writer.thinkingDelta(delta.reasoning);
    if (delta.content) writer.textDelta(delta.content);
    if (Array.isArray(delta.tool_calls)) {
      for (const tc of delta.tool_calls) writer.toolDelta(tc.index ?? 0, tc.id, tc.function?.name, tc.function?.arguments ?? "");
    }
  }
  if (!completed) throw new Error("Chat stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeResponsesToAnthropic(upstream, res, model) {
  const writer = new AnthropicSseWriter(res, model);
  let completed = false;
  let stopReason = "end_turn";
  for await (const ev of parseSse(upstream.body)) {
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    const type = chunk.type || ev.event;
    if (type === "response.created") writer.start();
    else if (type === "response.reasoning_summary_text.delta" || type === "response.reasoning_text.delta") writer.thinkingDelta(chunk.delta);
    else if (type === "response.output_text.delta") writer.textDelta(chunk.delta);
    else if (type === "response.output_item.added" && chunk.item?.type === "function_call") writer.toolDelta(chunk.output_index, chunk.item.call_id, chunk.item.name, "");
    else if (type === "response.function_call_arguments.delta") writer.toolDelta(chunk.output_index, null, null, chunk.delta);
    else if (type === "response.output_item.done" && chunk.item?.type === "function_call") writer.closeTool(chunk.output_index);
    else if (type === "response.completed" || type === "response.incomplete") {
      completed = true;
      stopReason = type === "response.incomplete" ? "max_tokens" : "end_turn";
      writer.inputTokens = chunk.response?.usage?.input_tokens ?? writer.inputTokens;
      writer.outputTokens = chunk.response?.usage?.output_tokens ?? writer.outputTokens;
    }
  }
  if (!completed) throw new Error("Responses stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeGeminiToAnthropic(upstream, res, model) {
  const writer = new AnthropicSseWriter(res, model);
  let callIndex = 0;
  let stopReason = "end_turn";
  let completed = false;
  const handle = (chunk) => {
    if (chunk.error || chunk.promptFeedback?.blockReason) throw new Error(chunk.error?.message || "Gemini blocked the prompt: " + chunk.promptFeedback.blockReason);
    if (chunk.usageMetadata) {
      writer.start(chunk.usageMetadata.promptTokenCount ?? 0);
      writer.outputTokens = chunk.usageMetadata.candidatesTokenCount ?? writer.outputTokens;
    }
    const cand = chunk.candidates?.[0];
    if (cand?.finishReason) completed = true;
    if (cand?.finishReason === "MAX_TOKENS") stopReason = "max_tokens";
    for (const part of cand?.content?.parts ?? []) {
      if (part.thought && part.text) writer.thinkingDelta(part.text);
      else if (part.text) writer.textDelta(part.text);
      else if (part.functionCall) {
        const slot = callIndex++;
        writer.toolDelta(slot, "toolu_" + randomUUID().slice(0, 12), part.functionCall.name, JSON.stringify(part.functionCall.args ?? {}));
        writer.closeTool(slot);
      }
    }
  };
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    for (const item of Array.isArray(data) ? data : [data]) handle(item);
    if (!completed) throw new Error("Gemini response ended before completion");
    writer.finish(stopReason);
    return;
  }
  for await (const ev of parseSse(upstream.body)) {
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    handle(chunk);
  }
  if (!completed) throw new Error("Gemini stream ended before completion");
  writer.finish(stopReason);
}

export function chatToResponsesRequest(body) {
  if (!Array.isArray(body.messages)) throw new Error("Chat messages must be an array");
  if (body.n != null && body.n !== 1) throw new Error("Cross-protocol requests support one completion at a time");
  const input = [];
  for (const message of body.messages) {
    if (message.role === "tool") {
      input.push({type: "function_call_output", call_id: message.tool_call_id, output: textOfContent(message.content)});
      continue;
    }
    if (!["system", "developer", "user", "assistant"].includes(message.role)) throw new Error("Unsupported chat role");
    const parts = typeof message.content === "string" ? [{type: "text", text: message.content}] : (message.content ?? []);
    const content = parts.map(part => {
      if (part.type === "image_url") return {type: "input_image", image_url: part.image_url?.url};
      if (part.type !== "text") throw new Error("Unsupported cross-protocol chat content: " + part.type);
      return {type: message.role === "assistant" ? "output_text" : "input_text", text: part.text};
    });
    if (content.length) input.push({type: "message", role: message.role, content});
    for (const call of message.tool_calls ?? []) {
      if (call.type !== "function") throw new Error("Unsupported chat tool type");
      input.push({type: "function_call", call_id: call.id, name: call.function.name, arguments: call.function.arguments || "{}"});
    }
  }
  const result = {model: body.model, input, stream: true};
  if (body.tools?.length) result.tools = body.tools.map(tool => {
    if (tool.type !== "function" || !tool.function?.name) throw new Error("Unsupported chat tool");
    return {type: "function", ...tool.function};
  });
  if (typeof body.tool_choice === "string") result.tool_choice = body.tool_choice;
  else if (body.tool_choice?.function?.name) result.tool_choice = {type: "function", name: body.tool_choice.function.name};
  if (body.max_completion_tokens != null || body.max_tokens != null) result.max_output_tokens = body.max_completion_tokens ?? body.max_tokens;
  if (body.temperature != null) result.temperature = body.temperature;
  if (body.top_p != null) result.top_p = body.top_p;
  if (body.stop != null) result.stop = body.stop;
  if (body.reasoning_effort) result.reasoning = {effort: body.reasoning_effort};
  if (body.response_format) result.text = {format: body.response_format.type === "json_schema"
    ? {type: "json_schema", ...body.response_format.json_schema} : body.response_format};
  return result;
}

export function geminiToResponsesRequest(body, model) {
  if (!Array.isArray(body.contents)) throw new Error("Gemini contents must be an array");
  const input = [];
  const calls = new Map();
  for (const content of body.contents) {
    const role = content.role === "model" ? "assistant" : "user";
    for (const part of content.parts ?? []) {
      if (part.functionCall) {
        if (part.thoughtSignature) throw new Error("Gemini signed tool history requires a Gemini upstream provider");
        const call = part.functionCall;
        const id = call.id || "call_" + randomUUID();
        const pending = calls.get(call.name) || [];
        pending.push(id); calls.set(call.name, pending);
        input.push({type: "function_call", call_id: id, name: call.name, arguments: JSON.stringify(call.args ?? {})});
      } else if (part.functionResponse) {
        const response = part.functionResponse;
        const id = response.id || calls.get(response.name)?.shift();
        if (!id) throw new Error("Gemini tool result has no matching tool call");
        input.push({type: "function_call_output", call_id: id, output: JSON.stringify(response.response ?? {})});
      } else if (part.inlineData?.mimeType?.startsWith("image/")) {
        input.push({type: "message", role, content: [{type: "input_image", image_url: `data:${part.inlineData.mimeType};base64,${part.inlineData.data}`}]});
      } else if (typeof part.text === "string") {
        if (!part.thought) input.push({type: "message", role, content: [{type: role === "assistant" ? "output_text" : "input_text", text: part.text}]});
      } else {
        throw new Error("Unsupported cross-protocol Gemini content");
      }
    }
  }
  const config = body.generationConfig || {};
  if (config.candidateCount != null && config.candidateCount !== 1) throw new Error("Cross-protocol requests support one candidate at a time");
  const result = {model, input, stream: true};
  const system = (body.systemInstruction?.parts ?? []).map(part => part.text || "").join("\n");
  if (system) result.instructions = system;
  const normalizeSchema = value => {
    if (Array.isArray(value)) return value.map(normalizeSchema);
    if (!value || typeof value !== "object") return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, key === "type" && typeof item === "string" ? item.toLowerCase() : normalizeSchema(item)]));
  };
  result.tools = (body.tools ?? []).flatMap(tool => {
    if (!Array.isArray(tool.functionDeclarations)) throw new Error("Cross-protocol Gemini requests only support function tools");
    return tool.functionDeclarations.map(fn => ({type: "function", name: fn.name, description: fn.description || "", parameters: normalizeSchema(fn.parametersJsonSchema ?? fn.parameters ?? {type: "object", properties: {}})}));
  });
  const calling = body.toolConfig?.functionCallingConfig;
  if (calling?.mode === "NONE") result.tool_choice = "none";
  else if (calling?.mode === "ANY") result.tool_choice = "required";
  if (calling?.allowedFunctionNames?.length) result.tools = result.tools.filter(tool => calling.allowedFunctionNames.includes(tool.name));
  if (config.maxOutputTokens != null) result.max_output_tokens = config.maxOutputTokens;
  if (config.temperature != null) result.temperature = config.temperature;
  if (config.topP != null) result.top_p = config.topP;
  if (config.stopSequences) result.stop = config.stopSequences;
  if (config.responseMimeType === "application/json") result.text = {format: config.responseSchema
    ? {type: "json_schema", name: "response", schema: normalizeSchema(config.responseSchema)} : {type: "json_object"}};
  return result;
}

/** 复用上游流解析，向第三方 CLI 返回其实际使用的 Chat 或 Gemini 协议。 */
export class CompatibleResponseWriter {
  constructor(res, model, protocol, stream) {
    this.res = res; this.model = model; this.protocol = protocol; this.stream = stream;
    this.id = "chatcmpl-" + randomUUID(); this.created = Math.floor(Date.now() / 1000);
    this.text = ""; this.thought = ""; this.calls = new Map(); this.started = false;
    this.inputTokens = 0; this.outputTokens = 0; this.bytes = 0;
  }
  account(text) {
    this.bytes += Buffer.byteLength(text || "");
    if (this.bytes > 16 * 1024 * 1024 || this.res.writableLength > 16 * 1024 * 1024) throw new Error("Model response exceeded the transport limit");
  }
  emit(data) {
    if (!this.stream) return;
    if (!this.started) {
      this.res.writeHead(200, {"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache"});
      this.started = true;
    }
    this.res.write("data: " + JSON.stringify(data) + "\n\n");
  }
  chat(delta, finishReason = null) {
    this.emit({id: this.id, object: "chat.completion.chunk", created: this.created, model: this.model,
      choices: [{index: 0, delta, finish_reason: finishReason}]});
  }
  textDelta(delta) {
    if (!delta) return;
    this.account(delta); this.text += delta;
    if (this.protocol === "openai-chat") this.chat({content: delta});
    else this.emit({candidates: [{index: 0, content: {role: "model", parts: [{text: delta}]}}]});
  }
  reasoningDelta(delta) {
    if (!delta) return;
    this.account(delta); this.thought += delta;
    if (this.protocol === "openai-chat") this.chat({reasoning_content: delta});
    else this.emit({candidates: [{index: 0, content: {role: "model", parts: [{text: delta, thought: true}]}}]});
  }
  toolDelta(slot, id, name, delta) {
    this.account(delta);
    let call = this.calls.get(slot);
    if (!call) {
      call = {index: this.calls.size, id: id || "call_" + randomUUID(), name: name || "", arguments: ""};
      this.calls.set(slot, call);
      if (this.protocol === "openai-chat") this.chat({tool_calls: [{index: call.index, id: call.id, type: "function", function: {name: call.name, arguments: ""}}]});
    } else if (name && !call.name) {
      call.name = name;
      if (this.protocol === "openai-chat") this.chat({tool_calls: [{index: call.index, function: {name}}]});
    }
    if (delta) {
      call.arguments += delta;
      if (this.protocol === "openai-chat") this.chat({tool_calls: [{index: call.index, function: {arguments: delta}}]});
    }
  }
  setUsage(input, output) {
    if (input != null) this.inputTokens = input;
    if (output != null) this.outputTokens = output;
  }
  finish(stopReason = "stop") {
    const limited = ["length", "max_tokens", "MAX_TOKENS"].includes(stopReason);
    if (this.protocol === "openai-chat") {
      const reason = limited ? "length" : this.calls.size ? "tool_calls" : "stop";
      const usage = {prompt_tokens: this.inputTokens, completion_tokens: this.outputTokens, total_tokens: this.inputTokens + this.outputTokens};
      if (this.stream) {
        this.chat({}, reason);
        this.emit({id: this.id, object: "chat.completion.chunk", created: this.created, model: this.model, choices: [], usage});
        this.res.end("data: [DONE]\n\n");
        return;
      }
      const message = {role: "assistant", content: this.text || null};
      if (this.thought) message.reasoning_content = this.thought;
      if (this.calls.size) message.tool_calls = [...this.calls.values()].map(call => ({id: call.id, type: "function", function: {name: call.name, arguments: call.arguments || "{}"}}));
      this.res.writeHead(200, {"Content-Type": "application/json"});
      this.res.end(JSON.stringify({id: this.id, object: "chat.completion", created: this.created, model: this.model, choices: [{index: 0, message, finish_reason: reason}], usage}));
    } else {
      const parts = [];
      if (!this.stream && this.thought) parts.push({text: this.thought, thought: true});
      if (!this.stream && this.text) parts.push({text: this.text});
      for (const call of this.calls.values()) parts.push({functionCall: {id: call.id, name: call.name, args: JSON.parse(call.arguments || "{}")}});
      const data = {candidates: [{index: 0, content: {role: "model", parts}, finishReason: limited ? "MAX_TOKENS" : "STOP"}],
        usageMetadata: {promptTokenCount: this.inputTokens, candidatesTokenCount: this.outputTokens, totalTokenCount: this.inputTokens + this.outputTokens}};
      if (this.stream) { this.emit(data); this.res.end(); }
      else { this.res.writeHead(200, {"Content-Type": "application/json"}); this.res.end(JSON.stringify(data)); }
    }
  }
}

export async function pipeResponsesToCompatible(upstream, writer) {
  if (!(upstream.headers.get("content-type") || "").includes("text/event-stream")) {
    const response = await upstream.json();
    if (response.error || response.status === "failed") throw new Error(response.error?.message || "Model response failed");
    for (const [index, item] of (response.output ?? []).entries()) {
      if (item.type === "message") writer.textDelta(textOfContent(item.content));
      else if (item.type === "reasoning") writer.reasoningDelta(textOfContent(item.summary));
      else if (item.type === "function_call") writer.toolDelta(index, item.call_id, item.name, item.arguments || "{}");
    }
    writer.setUsage(response.usage?.input_tokens, response.usage?.output_tokens);
    writer.finish(response.status === "incomplete" ? "length" : "stop");
    return;
  }
  let completed = false;
  for await (const event of parseSse(upstream.body)) {
    if (event.data === "[DONE]") continue;
    const chunk = JSON.parse(event.data);
    if (chunk.type === "response.output_text.delta") writer.textDelta(chunk.delta);
    else if (chunk.type === "response.reasoning_summary_text.delta") writer.reasoningDelta(chunk.delta);
    else if (chunk.type === "response.output_item.added" && chunk.item?.type === "function_call") writer.toolDelta(chunk.output_index, chunk.item.call_id, chunk.item.name, "");
    else if (chunk.type === "response.function_call_arguments.delta") writer.toolDelta(chunk.output_index, null, null, chunk.delta);
    else if (chunk.type === "response.output_item.done" && chunk.item?.type === "function_call") {
      const existing = writer.calls.get(chunk.output_index);
      if (!existing?.arguments) writer.toolDelta(chunk.output_index, chunk.item.call_id, chunk.item.name, chunk.item.arguments || "{}");
    }
    else if (chunk.type === "response.completed" || chunk.type === "response.incomplete") {
      writer.setUsage(chunk.response?.usage?.input_tokens, chunk.response?.usage?.output_tokens);
      writer.finish(chunk.type === "response.incomplete" ? "length" : "stop");
      completed = true;
      break;
    }
  }
  if (!completed) throw new Error("Model response stream ended before completion");
}
