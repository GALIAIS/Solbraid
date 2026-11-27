import {randomUUID} from "node:crypto";

// 协议转换工具：支持 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Google Gemini 四种协议双向互通。

export function textOfContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return content == null ? "" : String(content);
  return content.map((part) => {
    if (typeof part === "string") return part;
    if (["text", "input_text", "output_text", "summary_text"].includes(part?.type)) return part.text ?? "";
    return "";
  }).filter(Boolean).join("\n");
}

function parseDataUrl(url) {
  const m = typeof url === "string" && /^data:([^;,]+);base64,(.+)$/s.exec(url);
  return m ? {mediaType: m[1], data: m[2]} : null;
}

function anthropicInputPart(block) {
  if (typeof block === "string") return {type: "input_text", text: block};
  if (block?.type === "text") return {type: "input_text", text: block.text ?? ""};
  const source = block?.source;
  if (block?.type === "document" && source?.type === "text") return {type: "input_text", text: source.data};
  if (block?.type !== "image" && block?.type !== "document") throw new Error("当前协议不支持此工具结果类型");
  if (source?.type === "url" && source.url) {
    return block.type === "image" ? {type: "input_image", image_url: source.url} : {type: "input_file", file_url: source.url};
  }
  if (source?.type !== "base64" || !source.media_type || !source.data) throw new Error("附件缺少可转换的数据，请使用原生 Anthropic 服务商");
  const data = `data:${source.media_type};base64,${source.data}`;
  return block.type === "image" ? {type: "input_image", image_url: data}
    : {type: "input_file", filename: block.title || (source.media_type === "application/pdf" ? "document.pdf" : "document"), file_data: data};
}

function chatInputPart(part) {
  if (part.type === "input_image" && part.image_url) return {type: "image_url", image_url: {url: part.image_url}};
  if (part.type === "input_file" && part.file_data) return {type: "file", file: {filename: part.filename, file_data: part.file_data}};
  if (part.type === "input_text" || part.type === "output_text" || part.type === "text") return {type: "text", text: part.text};
  throw new Error("Chat Completions 无法接收此附件，请改用支持该附件的服务商");
}

function anthropicToolOutput(block) {
  const parts = (Array.isArray(block.content) ? block.content : [block.content ?? ""]).map(anthropicInputPart);
  if (parts.every(p => p.type === "input_text")) {
    const text = textOfContent(parts);
    return block.is_error ? `[ERROR] ${text}` : text;
  }
  return block.is_error ? [{type: "input_text", text: "[ERROR]"}, ...parts] : parts;
}

// Chat 的 tool 消息只接受文本，不能把截图改作用户消息而丢失工具来源。
function chatToolOutput(output) {
  if (Array.isArray(output) && output.some(p => typeof p !== "string" && !["text", "input_text", "output_text"].includes(p?.type))) {
    throw new Error("Chat Completions 无法传递此工具附件，请改用 Responses 或支持多媒体工具结果的服务商");
  }
  return textOfContent(output);
}

function anthropicPartFromInput(p) {
  if (typeof p === "string") return {type: "text", text: p};
  if (p?.type === "input_image" || p?.type === "input_file") {
    const type = p.type === "input_image" ? "image" : "document";
    const url = p.image_url || p.file_url;
    const parsed = parseDataUrl(p.file_data || url);
    if (parsed) return {type, source: {type: "base64", media_type: parsed.mediaType, data: parsed.data}};
    if (url) return {type, source: {type: "url", url}};
    throw new Error("Anthropic 无法读取此附件，请提供文件内容或 URL");
  }
  if (["input_text", "output_text", "text"].includes(p?.type)) return {type: "text", text: p.text};
  throw new Error("Anthropic 无法转换此消息内容");
}

function geminiPartFromInput(p) {
  if (typeof p === "string" || ["input_text", "output_text", "text"].includes(p?.type)) return {text: typeof p === "string" ? p : p.text};
  const url = p?.image_url || p?.file_url;
  const parsed = parseDataUrl(p?.file_data || url);
  if (parsed) return {inlineData: {mimeType: parsed.mediaType, data: parsed.data}};
  if (url) return {fileData: {fileUri: url}};
  throw new Error("Gemini 无法读取此附件，请提供文件内容或 URL");
}

function geminiToolResponse(name, output, isError = false) {
  const parts = Array.isArray(output) ? output.map(geminiPartFromInput) : [];
  const media = parts.filter(p => !Object.hasOwn(p, "text"));
  return {functionResponse: {name, response: {output: textOfContent(output), ...(isError ? {isError: true} : {})},
    ...(media.length ? {parts: media} : {})}};
}

function inputFromGeminiMedia(part) {
  const mime = part.inlineData?.mimeType || part.fileData?.mimeType || "";
  const data = part.inlineData?.data;
  const url = part.fileData?.fileUri;
  if (url && !/^https?:\/\//i.test(url)) throw new Error("此附件地址仅适用于 Gemini 服务商");
  if (!url && !(data && mime)) throw new Error("Gemini 附件缺少类型或内容");
  const source = url || `data:${mime};base64,${data}`;
  if (mime.startsWith("image/")) return {type: "input_image", image_url: source};
  if (url) return {type: "input_file", file_url: url};
  return {type: "input_file", filename: part.inlineData.displayName || (mime === "application/pdf" ? "document.pdf" : "attachment"), file_data: source};
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
  let syntheticCallSeq = 0;
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
      const hasMedia = parts.some(p => p?.type === "input_image" || p?.type === "input_file");
      const content = hasMedia
        ? parts.map(chatInputPart)
        : parts.map((p) => p?.text ?? "").join("\n");
      if (role === "assistant") {
        if (pendingAssistant) pendingAssistant.content = [pendingAssistant.content, content].filter(Boolean).join("\n");
        else pendingAssistant = {role: "assistant", content};
      } else {
        messages.push({role, content});
      }
    } else if (item.type === "reasoning") {
      const thought = textOfContent(item.summary);
      if (thought) {
        if (!pendingAssistant) pendingAssistant = {role: "assistant", content: null};
        pendingAssistant.reasoning_content = [pendingAssistant.reasoning_content, thought].filter(Boolean).join("\n");
      }
    } else if (item.type === "function_call" || item.type === "custom_tool_call") {
      if (!pendingAssistant) pendingAssistant = {role: "assistant", content: null};
      if (!pendingAssistant.tool_calls) pendingAssistant.tool_calls = [];
      const args = item.type === "custom_tool_call" ? JSON.stringify({input: item.input ?? ""}) : (item.arguments ?? "{}");
      pendingAssistant.tool_calls.push({
        id: item.call_id ?? item.id ?? ("call_rc_" + (++syntheticCallSeq)),
        type: "function",
        function: {name: encodeToolName(item.namespace, item.name), arguments: args},
      });
    } else if (item.type === "function_call_output" || item.type === "custom_tool_call_output") {
      flushAssistant();
      messages.push({role: "tool", tool_call_id: item.call_id, content: chatToolOutput(item.output)});
    }
  }
  flushAssistant();
  const isReasoningModel = /^(?:o[1-9]|gpt-5)/i.test(String(body.model || ""));
  const req = {model: body.model, messages, stream: true, stream_options: {include_usage: true}};
  if (tools.length) {
    req.tools = tools.map((t) => ({type: "function", function: {name: t.name, description: t.description, parameters: t.parameters}}));
    if (typeof body.tool_choice === "string") req.tool_choice = body.tool_choice;
    else if (body.tool_choice?.type === "function" && body.tool_choice.name) {
      req.tool_choice = {type: "function", function: {name: encodeToolName(body.tool_choice.namespace, body.tool_choice.name)}};
    }
  }
  if (body.max_output_tokens) {
    if (isReasoningModel) req.max_completion_tokens = body.max_output_tokens;
    else req.max_tokens = body.max_output_tokens;
  }
  if (body.temperature != null && !isReasoningModel) req.temperature = body.temperature;
  if (body.top_p != null && !isReasoningModel) req.top_p = body.top_p;
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
  let syntheticCallSeq = 0;
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
      const blocks = parts.map(anthropicPartFromInput).filter(p => p.type !== "text" || p.text);
      pushMsg(role, blocks);
    } else if (item.type === "function_call" || item.type === "custom_tool_call") {
      let parsedInput = {};
      if (item.type === "custom_tool_call") parsedInput = {input: item.input ?? ""};
      else { try { parsedInput = JSON.parse(item.arguments || "{}"); } catch { parsedInput = {}; } }
      pushMsg("assistant", [{type: "tool_use", id: item.call_id ?? item.id ?? ("toolu_ra_" + (++syntheticCallSeq)), name: encodeToolName(item.namespace, item.name), input: parsedInput}]);
    } else if (item.type === "function_call_output" || item.type === "custom_tool_call_output") {
      const content = Array.isArray(item.output) ? item.output.map(anthropicPartFromInput) : textOfContent(item.output);
      pushMsg("user", [{type: "tool_result", tool_use_id: item.call_id, content}]);
    }
  }
  if (!messages.length) messages.push({role: "user", content: [{type: "text", text: "Continue"}]});
  // 为跨协议转入 Anthropic Messages 的请求注入 Prompt Caching 断点（最多 3 个 <= Anthropic 上限 4 个），
  // 使 Codex / Antigravity 通过 Claude 上游时能命中 90% 前缀缓存。
  if (messages.length >= 2) {
    const prevBlocks = messages[messages.length - 2]?.content;
    if (Array.isArray(prevBlocks)) {
      for (let i = prevBlocks.length - 1; i >= 0; i--) {
        const b = prevBlocks[i];
        if (b && ["text", "tool_use", "tool_result", "image", "document"].includes(b.type) && (b.type !== "text" || Boolean(b.text))) {
          prevBlocks[i] = {...b, cache_control: {type: "ephemeral"}};
          break;
        }
      }
    }
  }
  const req = {model: body.model, max_tokens: body.max_output_tokens ?? 8192, messages, stream: true};
  if (systemParts.length) req.system = [{type: "text", text: systemParts.join("\n\n"), cache_control: {type: "ephemeral"}}];
  if (tools.length) {
    req.tools = tools.map((t, idx) => ({
      name: t.name,
      description: t.description,
      input_schema: t.parameters,
      ...(idx === tools.length - 1 ? {cache_control: {type: "ephemeral"}} : {}),
    }));
  }
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
      const parts = raw.map(geminiPartFromInput);
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
      pushContent("user", [geminiToolResponse(name, item.output)]);
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
          messages.push({role: "tool", tool_call_id: b.tool_use_id, content: chatToolOutput(anthropicToolOutput(b))});
        } else if (["text", "document", "image"].includes(b?.type)) {
          userParts.push(chatInputPart(anthropicInputPart(b)));
        }
      }
      if (userParts.length) {
        messages.push({role: "user", content: userParts.some(p => p.type !== "text") ? userParts : textOfContent(userParts)});
      }
    } else if (msg.role === "assistant") {
      const texts = [];
      const thoughts = [];
      const toolCalls = [];
      for (const b of blocks) {
        if (b?.type === "text" && b.text) texts.push(b.text);
        else if (b?.type === "thinking" && b.thinking) thoughts.push(b.thinking);
        else if (b?.type === "tool_use") {
          toolCalls.push({id: b.id, type: "function", function: {name: b.name, arguments: JSON.stringify(b.input ?? {})}});
        }
      }
      const entry = {role: "assistant", content: texts.length ? texts.join("\n") : null};
      if (thoughts.length) entry.reasoning_content = thoughts.join("\n");
      if (toolCalls.length) entry.tool_calls = toolCalls;
      if (entry.content !== null || entry.tool_calls || entry.reasoning_content) messages.push(entry);
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

export function anthropicToResponsesRequest(body, opts = {}) {
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
          input.push({type: "function_call_output", call_id: b.tool_use_id, output: anthropicToolOutput(b)});
        } else if (["text", "document", "image"].includes(b?.type)) {
          parts.push(anthropicInputPart(b));
        }
      }
      if (parts.length) input.push({type: "message", role: "user", content: parts});
    } else if (msg.role === "assistant") {
      const parts = [];
      for (const b of blocks) {
        if (b?.type === "thinking" && b.thinking) {
          if (parts.length) { input.push({type: "message", role: "assistant", content: parts.splice(0)}); }
          input.push({type: "reasoning", summary: [{type: "summary_text", text: b.thinking}]});
        } else if (b?.type === "text" && b.text) {
          parts.push({type: "output_text", text: b.text});
        } else if (b?.type === "tool_use") {
          if (parts.length) { input.push({type: "message", role: "assistant", content: parts.splice(0)}); }
          input.push({type: "function_call", call_id: b.id, name: b.name, arguments: JSON.stringify(b.input ?? {})});
        }
      }
      if (parts.length) input.push({type: "message", role: "assistant", content: parts});
    }
  }
  const req = {model: body.model, input, stream: true};
  const cacheKey = opts?.sessionId || body.metadata?.user_id;
  if (cacheKey) req.prompt_cache_key = String(cacheKey).slice(0, 64);
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
      if (b?.type === "thinking" && b.thinking) parts.push({text: b.thinking, thought: true});
      else if (b?.type === "text" && b.text) parts.push({text: b.text});
      else if (b?.type === "document" && b.source) {
        if (b.source.type === "base64" && b.source.media_type) parts.push({inlineData: {mimeType: b.source.media_type, data: b.source.data}});
        else if (b.source.type === "text") parts.push({text: b.source.data || ""});
      } else if (b?.type === "image" && b.source?.type === "base64") parts.push({inlineData: {mimeType: b.source.media_type, data: b.source.data}});
      else if (b?.type === "image" && b.source?.url) parts.push({fileData: {mimeType: b.source.media_type || "image/png", fileUri: b.source.url}});
      else if (b?.type === "tool_use") {
        toolNames.set(b.id, b.name);
        parts.push({functionCall: {name: b.name, args: b.input ?? {}}});
      } else if (b?.type === "tool_result") {
        parts.push(geminiToolResponse(toolNames.get(b.tool_use_id) ?? "tool", anthropicToolOutput(b), b.is_error));
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

function safeParseToolJson(raw) {
  const s = String(raw || "").trim();
  if (!s) return {json: "{}", obj: {}};
  try {
    const parsed = JSON.parse(s);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return {json: s, obj: parsed};
    }
    return {json: JSON.stringify({value: parsed}), obj: {value: parsed}};
  } catch {
    return {json: "{}", obj: {}};
  }
}

// 统一生成 Codex 所需的 OpenAI Responses SSE 事件流（stream=false 时聚合返回单条 JSON）。
export class ResponsesSseWriter {
  constructor(res, model, namespaceMap = new Map(), customSet = new Set(), stream = true) {
    this.res = res;
    this.model = model;
    this.namespaceMap = namespaceMap;
    this.customSet = customSet;
    this.stream = stream !== false;
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
    if (!this.stream) return;
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
      if (this.stream) {
        writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: {id, type: "reasoning", summary: []}});
        writeSse(this.res, "response.reasoning_summary_part.added", {type: "response.reasoning_summary_part.added", item_id: id, output_index: index, summary_index: 0, part: {type: "summary_text", text: ""}});
      }
    }
    this.reasoning.text += delta;
    if (this.stream) writeSse(this.res, "response.reasoning_summary_text.delta", {type: "response.reasoning_summary_text.delta", item_id: this.reasoning.id, output_index: this.reasoning.index, summary_index: 0, delta});
  }
  closeReasoning() {
    if (!this.reasoning) return;
    const r = this.reasoning;
    this.reasoning = null;
    const item = {id: r.id, type: "reasoning", summary: [{type: "summary_text", text: r.text}]};
    this.completedOutput.push(item);
    if (this.stream) writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: r.index, item});
  }
  textDelta(delta) {
    if (!delta) return;
    this.start();
    this.closeReasoning();
    if (!this.message) {
      const id = "msg_" + randomUUID().slice(0, 12);
      const index = this.nextIndex++;
      this.message = {id, index, text: ""};
      if (this.stream) {
        writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: {id, type: "message", role: "assistant", status: "in_progress", content: []}});
        writeSse(this.res, "response.content_part.added", {type: "response.content_part.added", item_id: id, output_index: index, content_index: 0, part: {type: "output_text", text: ""}});
      }
    }
    this.message.text += delta;
    if (this.stream) writeSse(this.res, "response.output_text.delta", {type: "response.output_text.delta", item_id: this.message.id, output_index: this.message.index, content_index: 0, delta});
  }
  closeMessage() {
    if (!this.message) return;
    const m = this.message;
    this.message = null;
    const item = {id: m.id, type: "message", role: "assistant", status: "completed", content: [{type: "output_text", text: m.text}]};
    this.completedOutput.push(item);
    if (this.stream) {
      writeSse(this.res, "response.output_text.done", {type: "response.output_text.done", item_id: m.id, output_index: m.index, content_index: 0, text: m.text});
      writeSse(this.res, "response.content_part.done", {type: "response.content_part.done", item_id: m.id, output_index: m.index, content_index: 0, part: {type: "output_text", text: m.text}});
      writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: m.index, item});
    }
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
      if (this.stream) {
        const added = isCustom
          ? {id, type: "custom_tool_call", call_id: call.callId, name: call.name, input: "", status: "in_progress"}
          : {id, type: "function_call", call_id: call.callId, name: call.name, ...(call.namespace ? {namespace: call.namespace} : {}), arguments: "", status: "in_progress"};
        writeSse(this.res, "response.output_item.added", {type: "response.output_item.added", output_index: index, item: added});
      }
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
      if (this.stream && !call.isCustom) {
        writeSse(this.res, "response.function_call_arguments.delta", {type: "response.function_call_arguments.delta", item_id: call.id, output_index: call.index, delta: argsDelta});
      }
    }
  }
  setUsage(input, output, {cachedTokens = null, cacheWriteTokens = null, reasoningTokens = null} = {}) {
    if (input != null) this.usage.input_tokens = input;
    if (output != null) this.usage.output_tokens = output;
    if (cachedTokens != null && cachedTokens > 0) {
      this.usage.input_tokens_details = {...(this.usage.input_tokens_details || {}), cached_tokens: cachedTokens};
    }
    if (cacheWriteTokens != null && cacheWriteTokens > 0) {
      this.usage.input_tokens_details = {...(this.usage.input_tokens_details || {}), cache_write_tokens: cacheWriteTokens};
    }
    if (reasoningTokens != null && reasoningTokens > 0) {
      this.usage.output_tokens_details = {...(this.usage.output_tokens_details || {}), reasoning_tokens: reasoningTokens};
    }
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
      if (this.stream) writeSse(this.res, "response.output_item.done", {type: "response.output_item.done", output_index: call.index, item});
    }
    this.calls.clear();
    const limited = ["length", "max_tokens", "MAX_TOKENS"].includes(stopReason);
    const responseObj = {
      id: this.responseId,
      object: "response",
      status: limited ? "incomplete" : "completed",
      model: this.model,
      ...(limited ? {incomplete_details: {reason: "max_output_tokens"}} : {}),
      output: this.completedOutput,
      usage: this.usage,
    };
    if (!this.stream) {
      responseObj.output_text = this.completedOutput
        .filter(it => it.type === "message")
        .flatMap(it => Array.isArray(it.content) ? it.content.map(c => c.text || "") : [])
        .join("");
      this.res.writeHead(200, {"Content-Type": "application/json; charset=utf-8"});
      this.res.end(JSON.stringify(responseObj));
      return;
    }
    const type = limited ? "response.incomplete" : "response.completed";
    writeSse(this.res, type, {type, response: responseObj});
    this.res.end();
  }
}

// 统一生成 Claude Code 所需的 Anthropic Messages SSE 事件流（stream=false 时聚合返回单条 JSON）。
export class AnthropicSseWriter {
  constructor(res, model, stream = true) {
    this.res = res;
    this.model = model;
    this.stream = stream !== false;
    this.messageId = "msg_" + randomUUID().replaceAll("-", "").slice(0, 20);
    this.nextIndex = 0;
    this.openBlock = null;
    this.completedBlocks = [];
    this.toolBlocks = new Map();
    this.sawTool = false;
    this.totalInputTokens = 0;
    this.cacheReadTokens = 0;
    this.cacheWriteTokens = 0;
    this.outputTokens = 0;
    this.started = false;
  }
  get inputTokens() { return this.totalInputTokens; }
  set inputTokens(v) { this.totalInputTokens = Math.max(0, Number(v) || 0); }
  setUsage(totalInput, output, {cacheRead = null, cacheWrite = null} = {}) {
    if (totalInput != null) this.totalInputTokens = Math.max(0, Number(totalInput) || 0);
    if (output != null) this.outputTokens = Math.max(0, Number(output) || 0);
    if (cacheRead != null) this.cacheReadTokens = Math.max(0, Number(cacheRead) || 0);
    if (cacheWrite != null) this.cacheWriteTokens = Math.max(0, Number(cacheWrite) || 0);
  }
  buildUsage(initial = false) {
    // Anthropic Messages 协议要求 input_tokens 仅为未命中缓存部分，否则 Claude Code 会把 input_tokens + cache_read 重复叠加
    const uncachedIn = Math.max(0, this.totalInputTokens - this.cacheReadTokens - this.cacheWriteTokens);
    return {
      input_tokens: uncachedIn,
      output_tokens: initial ? 1 : this.outputTokens,
      ...(this.cacheReadTokens > 0 ? {cache_read_input_tokens: this.cacheReadTokens} : {}),
      ...(this.cacheWriteTokens > 0 ? {cache_creation_input_tokens: this.cacheWriteTokens} : {}),
    };
  }
  start(inputTokens = 0, details = {}) {
    if (this.started) return;
    this.started = true;
    if (inputTokens > 0 || details?.cacheRead || details?.cacheWrite) {
      this.setUsage(inputTokens > 0 ? inputTokens : null, null, details);
    }
    if (!this.stream) return;
    this.res.writeHead(200, {"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache", "Connection": "keep-alive"});
    writeSse(this.res, "message_start", {type: "message_start", message: {id: this.messageId, type: "message", role: "assistant", content: [], model: this.model, stop_reason: null, stop_sequence: null, usage: this.buildUsage(true)}});
  }
  closeCurrent() {
    if (!this.openBlock) return;
    if (this.openBlock.type === "thinking") {
      this.completedBlocks.push({type: "thinking", thinking: this.openBlock.buf});
    } else if (this.openBlock.type === "text") {
      this.completedBlocks.push({type: "text", text: this.openBlock.buf});
    }
    if (this.stream) writeSse(this.res, "content_block_stop", {type: "content_block_stop", index: this.openBlock.index});
    this.openBlock = null;
  }
  thinkingDelta(delta) {
    if (!delta) return;
    this.start();
    if (this.openBlock?.type !== "thinking") {
      this.closeCurrent();
      const index = this.nextIndex++;
      this.openBlock = {type: "thinking", index, buf: ""};
      if (this.stream) writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "thinking", thinking: ""}});
    }
    this.openBlock.buf += delta;
    if (this.stream) writeSse(this.res, "content_block_delta", {type: "content_block_delta", index: this.openBlock.index, delta: {type: "thinking_delta", thinking: delta}});
  }
  textDelta(delta) {
    if (!delta) return;
    this.start();
    if (this.openBlock?.type !== "text") {
      this.closeCurrent();
      const index = this.nextIndex++;
      this.openBlock = {type: "text", index, buf: ""};
      if (this.stream) writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "text", text: ""}});
    }
    this.openBlock.buf += delta;
    if (this.stream) writeSse(this.res, "content_block_delta", {type: "content_block_delta", index: this.openBlock.index, delta: {type: "text_delta", text: delta}});
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
    const safeArgs = safeParseToolJson(block.json);
    this.closeCurrent();
    this.completedBlocks.push({type: "tool_use", id: block.id, name: block.name, input: safeArgs.obj});
    if (this.stream) {
      const index = this.nextIndex++;
      writeSse(this.res, "content_block_start", {type: "content_block_start", index, content_block: {type: "tool_use", id: block.id, name: block.name, input: {}}});
      writeSse(this.res, "content_block_delta", {type: "content_block_delta", index, delta: {type: "input_json_delta", partial_json: safeArgs.json}});
      writeSse(this.res, "content_block_stop", {type: "content_block_stop", index});
    }
    block.emitted = true;
  }
  finish(stopReason = null) {
    this.start();
    this.closeCurrent();
    for (const slot of this.toolBlocks.keys()) this.closeTool(slot);
    const reason = stopReason === "max_tokens" ? stopReason : this.sawTool ? "tool_use" : (stopReason || "end_turn");
    if (!this.stream) {
      this.res.writeHead(200, {"Content-Type": "application/json; charset=utf-8"});
      this.res.end(JSON.stringify({
        id: this.messageId,
        type: "message",
        role: "assistant",
        model: this.model,
        content: this.completedBlocks,
        stop_reason: reason,
        stop_sequence: null,
        usage: this.buildUsage(false),
      }));
      return;
    }
    writeSse(this.res, "message_delta", {type: "message_delta", delta: {stop_reason: reason, stop_sequence: null}, usage: this.buildUsage(false)});
    writeSse(this.res, "message_stop", {type: "message_stop"});
    this.res.end();
  }
}

export async function pipeChatToResponses(upstream, res, model, namespaceMap, customSet, writer = null, stream = true) {
  writer = writer || new ResponsesSseWriter(res, model, namespaceMap, customSet, stream);
  const applyChatUsage = (u) => {
    if (!u) return;
    writer.setUsage(u.prompt_tokens, u.completion_tokens, {
      cachedTokens: u.prompt_tokens_details?.cached_tokens ?? u.prompt_cache_hit_tokens ?? null,
      reasoningTokens: u.completion_tokens_details?.reasoning_tokens ?? null,
    });
  };
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    const choice = data.choices?.[0]?.message;
    if (data.error || !choice) throw new Error(data.error?.message || "Upstream returned no completion");
    if (choice?.reasoning_content || choice?.reasoning) writer.reasoningDelta(choice.reasoning_content || choice.reasoning);
    if (choice?.content) writer.textDelta(choice.content);
    for (const [i, tc] of (choice?.tool_calls ?? []).entries()) writer.toolDelta(i, tc.id, tc.function?.name, tc.function?.arguments ?? "{}");
    if (data.usage) applyChatUsage(data.usage);
    writer.finish(data.choices?.[0]?.finish_reason);
    return;
  }
  let completed = false;
  let stopReason = "stop";
  for await (const ev of parseSse(upstream.body)) {
    if (ev.data === "[DONE]") { completed = true; break; }
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    if (chunk.usage) applyChatUsage(chunk.usage);
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

export async function pipeAnthropicToResponses(upstream, res, model, namespaceMap, customSet, writer = null, stream = true) {
  writer = writer || new ResponsesSseWriter(res, model, namespaceMap, customSet, stream);
  if (!(upstream.headers.get("content-type") || "").includes("text/event-stream")) {
    const data = await upstream.json();
    if (data.error || !Array.isArray(data.content)) throw new Error(data.error?.message || "Invalid Anthropic response");
    for (const [index, block] of data.content.entries()) {
      if (block.type === "text") writer.textDelta(block.text);
      else if (block.type === "thinking") writer.reasoningDelta(block.thinking);
      else if (block.type === "tool_use") writer.toolDelta(index, block.id, block.name, JSON.stringify(block.input ?? {}));
    }
    if (data.usage) {
      const cacheRead = data.usage.cache_read_input_tokens ?? 0;
      const cacheWrite = data.usage.cache_creation_input_tokens ?? 0;
      const totalIn = (data.usage.input_tokens ?? 0) + cacheRead + cacheWrite;
      writer.setUsage(totalIn, data.usage.output_tokens, {cachedTokens: cacheRead || null, cacheWriteTokens: cacheWrite || null});
    }
    writer.finish(data.stop_reason);
    return;
  }
  let completed = false;
  let stopReason = "end_turn";
  let anthIn = 0, anthCacheRead = 0, anthCacheWrite = 0, anthOut = 0;
  const applyAnthUsage = (u) => {
    if (!u || typeof u !== "object") return;
    if (typeof u.input_tokens === "number") anthIn = Math.max(anthIn, u.input_tokens);
    if (typeof u.cache_read_input_tokens === "number") anthCacheRead = Math.max(anthCacheRead, u.cache_read_input_tokens);
    if (typeof u.cache_creation_input_tokens === "number") anthCacheWrite = Math.max(anthCacheWrite, u.cache_creation_input_tokens);
    if (typeof u.output_tokens === "number") anthOut = Math.max(anthOut, u.output_tokens);
    writer.setUsage(anthIn + anthCacheRead + anthCacheWrite, anthOut, {cachedTokens: anthCacheRead || null, cacheWriteTokens: anthCacheWrite || null});
  };
  for await (const ev of parseSse(upstream.body)) {
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    const type = chunk.type || ev.event;
    if (type === "message_start" && chunk.message?.usage) applyAnthUsage(chunk.message.usage);
    else if (type === "content_block_start" && chunk.content_block?.type === "tool_use") writer.toolDelta(chunk.index, chunk.content_block.id, chunk.content_block.name, "");
    else if (type === "content_block_delta") {
      if (chunk.delta?.type === "thinking_delta") writer.reasoningDelta(chunk.delta.thinking);
      else if (chunk.delta?.type === "text_delta") writer.textDelta(chunk.delta.text);
      else if (chunk.delta?.type === "input_json_delta") writer.toolDelta(chunk.index, null, null, chunk.delta.partial_json);
    } else if (type === "message_delta") {
      if (chunk.usage) applyAnthUsage(chunk.usage);
      stopReason = chunk.delta?.stop_reason || stopReason;
    } else if (type === "message_stop") completed = true;
  }
  if (!completed) throw new Error("Anthropic stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeGeminiToResponses(upstream, res, model, namespaceMap, customSet, writer = null, stream = true) {
  writer = writer || new ResponsesSseWriter(res, model, namespaceMap, customSet, stream);
  let callIndex = 0;
  let stopReason = null;
  const handle = (chunk) => {
    if (chunk.error || chunk.promptFeedback?.blockReason) throw new Error(chunk.error?.message || "Gemini blocked the prompt: " + chunk.promptFeedback.blockReason);
    if (chunk.candidates?.[0]?.finishReason) stopReason = chunk.candidates[0].finishReason;
    if (chunk.usageMetadata) {
      const um = chunk.usageMetadata;
      const thoughts = um.thoughtsTokenCount ?? 0;
      const cand = um.candidatesTokenCount ?? 0;
      writer.setUsage(um.promptTokenCount, cand + thoughts, {
        cachedTokens: um.cachedContentTokenCount ?? null,
        reasoningTokens: thoughts || null,
      });
    }
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

export async function pipeChatToAnthropic(upstream, res, model, stream = true) {
  const writer = new AnthropicSseWriter(res, model, stream);
  let stopReason = "end_turn";
  const applyChatUsage = (u) => {
    if (!u) return;
    const cacheRead = u.prompt_tokens_details?.cached_tokens ?? u.prompt_cache_hit_tokens ?? 0;
    writer.setUsage(u.prompt_tokens ?? writer.totalInputTokens, u.completion_tokens ?? writer.outputTokens, {cacheRead});
    writer.start();
  };
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const data = await upstream.json();
    if (data.error || !data.choices?.[0]?.message) throw new Error(data.error?.message || "Upstream returned no completion");
    if (data.usage) applyChatUsage(data.usage);
    else { writer.start(0); writer.outputTokens = 1; }
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
    if (chunk.usage) applyChatUsage(chunk.usage);
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

export async function pipeResponsesToAnthropic(upstream, res, model, stream = true) {
  const writer = new AnthropicSseWriter(res, model, stream);
  const ct = upstream.headers.get("content-type") || "";
  if (!ct.includes("text/event-stream")) {
    const response = await upstream.json();
    if (response.error || response.status === "failed") throw new Error(response.error?.message || "Responses upstream failed");
    const u = response.usage;
    if (u) {
      writer.setUsage(u.input_tokens ?? 0, u.output_tokens ?? 0, {cacheRead: u.input_tokens_details?.cached_tokens ?? 0});
    }
    writer.start();
    for (const [index, item] of (response.output ?? []).entries()) {
      if (item.type === "reasoning") writer.thinkingDelta(textOfContent(item.summary));
      else if (item.type === "message") writer.textDelta(textOfContent(item.content));
      else if (item.type === "function_call") {
        writer.toolDelta(index, item.call_id, item.name, item.arguments || "{}");
        writer.closeTool(index);
      }
    }
    writer.finish(response.status === "incomplete" ? "max_tokens" : "end_turn");
    return;
  }
  let completed = false;
  let stopReason = "end_turn";
  for await (const ev of parseSse(upstream.body)) {
    if (ev.data === "[DONE]") continue;
    let chunk;
    try { chunk = JSON.parse(ev.data); } catch { continue; }
    const type = chunk.type || ev.event;
    if (type === "response.created") writer.start();
    else if (type === "response.reasoning_summary_text.delta" || type === "response.reasoning_text.delta") writer.thinkingDelta(chunk.delta);
    else if (type === "response.output_text.delta") writer.textDelta(chunk.delta);
    else if (type === "response.output_item.added" && chunk.item?.type === "function_call") writer.toolDelta(chunk.output_index, chunk.item.call_id, chunk.item.name, "");
    else if (type === "response.function_call_arguments.delta") writer.toolDelta(chunk.output_index, null, null, chunk.delta);
    else if (type === "response.output_item.done" && chunk.item?.type === "function_call") {
      const existing = writer.toolBlocks.get(chunk.output_index);
      if (!existing || !existing.json) {
        writer.toolDelta(chunk.output_index, chunk.item.call_id, chunk.item.name, chunk.item.arguments || "{}");
      }
      writer.closeTool(chunk.output_index);
    }
    else if (type === "response.completed" || type === "response.incomplete") {
      completed = true;
      stopReason = type === "response.incomplete" ? "max_tokens" : "end_turn";
      const u = chunk.response?.usage;
      if (u) {
        writer.setUsage(u.input_tokens ?? writer.totalInputTokens, u.output_tokens ?? writer.outputTokens, {
          cacheRead: u.input_tokens_details?.cached_tokens ?? 0,
        });
      }
    }
  }
  if (!completed) throw new Error("Responses stream ended before completion");
  writer.finish(stopReason);
}

export async function pipeGeminiToAnthropic(upstream, res, model, stream = true) {
  const writer = new AnthropicSseWriter(res, model, stream);
  let callIndex = 0;
  let stopReason = "end_turn";
  let completed = false;
  const handle = (chunk) => {
    if (chunk.error || chunk.promptFeedback?.blockReason) throw new Error(chunk.error?.message || "Gemini blocked the prompt: " + chunk.promptFeedback.blockReason);
    if (chunk.usageMetadata) {
      const um = chunk.usageMetadata;
      const outTok = (um.candidatesTokenCount ?? 0) + (um.thoughtsTokenCount ?? 0);
      writer.setUsage(um.promptTokenCount ?? writer.totalInputTokens, outTok || writer.outputTokens, {
        cacheRead: um.cachedContentTokenCount ?? 0,
      });
      writer.start();
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

export function chatToResponsesRequest(body, opts = {}) {
  if (!Array.isArray(body.messages)) throw new Error("Chat messages must be an array");
  if (body.n != null && body.n !== 1) throw new Error("Cross-protocol requests support one completion at a time");
  const input = [];
  for (const message of body.messages) {
    if (message.role === "tool") {
      input.push({type: "function_call_output", call_id: message.tool_call_id, output: chatToolOutput(message.content)});
      continue;
    }
    if (!["system", "developer", "user", "assistant"].includes(message.role)) throw new Error("Unsupported chat role");
    const parts = typeof message.content === "string" ? [{type: "text", text: message.content}] : (message.content ?? []);
    const content = parts.map(part => {
      if (part.type === "image_url") return {type: "input_image", image_url: part.image_url?.url};
      if (part.type === "file" && part.file?.file_data) return {type: "input_file", filename: part.file.filename, file_data: part.file.file_data};
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
  const cacheKey = body.prompt_cache_key || opts?.sessionId;
  if (cacheKey) result.prompt_cache_key = String(cacheKey).slice(0, 64);
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

export function geminiToResponsesRequest(body, model, opts = {}) {
  if (!Array.isArray(body.contents)) throw new Error("Gemini contents must be an array");
  const input = [];
  const calls = new Map();
  let syntheticCallSeq = 0;
  for (const content of body.contents) {
    const role = content.role === "model" ? "assistant" : "user";
    for (const part of content.parts ?? []) {
      if (part.functionCall) {
        if (part.thoughtSignature) throw new Error("Gemini signed tool history requires a Gemini upstream provider");
        const call = part.functionCall;
        // 确定性生成缺失的历史工具调用 ID，避免每轮随机 UUID 破坏上游 Prompt Cache 前缀匹配
        const id = call.id || ("call_gem_" + (++syntheticCallSeq));
        const pending = calls.get(call.name) || [];
        pending.push(id); calls.set(call.name, pending);
        input.push({type: "function_call", call_id: id, name: call.name, arguments: JSON.stringify(call.args ?? {})});
      } else if (part.functionResponse) {
        const response = part.functionResponse;
        const pending = calls.get(response.name) || [];
        const id = response.id || pending[0];
        if (!id) throw new Error("Gemini tool result has no matching tool call");
        const index = pending.indexOf(id);
        if (index >= 0) pending.splice(index, 1);
        const text = JSON.stringify(response.response ?? {});
        const output = response.parts?.length ? [{type: "input_text", text}, ...response.parts.map(inputFromGeminiMedia)] : text;
        input.push({type: "function_call_output", call_id: id, output});
      } else if (part.inlineData || part.fileData) {
        input.push({type: "message", role, content: [inputFromGeminiMedia(part)]});
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
  if (opts?.sessionId) result.prompt_cache_key = String(opts.sessionId).slice(0, 64);
  const system = (body.systemInstruction?.parts ?? []).map(part => part.text || "").join("\n");
  if (system) result.instructions = system;
  const normalizeSchema = value => {
    if (Array.isArray(value)) return value.map(normalizeSchema);
    if (!value || typeof value !== "object") return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, key === "type" && typeof item === "string" ? item.toLowerCase() : normalizeSchema(item)]));
  };
  let mappedTools = (body.tools ?? []).flatMap(tool => {
    if (!Array.isArray(tool.functionDeclarations)) throw new Error("Cross-protocol Gemini requests only support function tools");
    return tool.functionDeclarations.map(fn => ({type: "function", name: fn.name, description: fn.description || "", parameters: normalizeSchema(fn.parametersJsonSchema ?? fn.parameters ?? {type: "object", properties: {}})}));
  });
  const calling = body.toolConfig?.functionCallingConfig;
  if (calling?.allowedFunctionNames?.length) mappedTools = mappedTools.filter(tool => calling.allowedFunctionNames.includes(tool.name));
  if (mappedTools.length) {
    result.tools = mappedTools;
    if (calling?.mode === "NONE") result.tool_choice = "none";
    else if (calling?.mode === "ANY") result.tool_choice = "required";
  }
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
    this.inputTokens = 0; this.outputTokens = 0; this.cachedTokens = 0; this.reasoningTokens = 0; this.bytes = 0;
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
  setUsage(input, output, {cachedTokens = null, reasoningTokens = null} = {}) {
    if (input != null) this.inputTokens = input;
    if (output != null) this.outputTokens = output;
    if (cachedTokens != null && cachedTokens > 0) this.cachedTokens = cachedTokens;
    if (reasoningTokens != null && reasoningTokens > 0) this.reasoningTokens = reasoningTokens;
  }
  finish(stopReason = "stop") {
    const limited = ["length", "max_tokens", "MAX_TOKENS"].includes(stopReason);
    if (this.protocol === "openai-chat") {
      const reason = limited ? "length" : this.calls.size ? "tool_calls" : "stop";
      const usage = {
        prompt_tokens: this.inputTokens,
        completion_tokens: this.outputTokens,
        total_tokens: this.inputTokens + this.outputTokens,
        ...(this.cachedTokens > 0 ? {prompt_tokens_details: {cached_tokens: this.cachedTokens}} : {}),
        ...(this.reasoningTokens > 0 ? {completion_tokens_details: {reasoning_tokens: this.reasoningTokens}} : {}),
      };
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
      for (const call of this.calls.values()) parts.push({functionCall: {id: call.id, name: call.name, args: safeParseToolJson(call.arguments).obj}});
      const candOut = this.reasoningTokens > 0 && this.outputTokens >= this.reasoningTokens
        ? (this.outputTokens - this.reasoningTokens)
        : this.outputTokens;
      const usageMetadata = {
        promptTokenCount: this.inputTokens,
        candidatesTokenCount: candOut,
        ...(this.cachedTokens > 0 ? {cachedContentTokenCount: this.cachedTokens} : {}),
        ...(this.reasoningTokens > 0 ? {thoughtsTokenCount: this.reasoningTokens} : {}),
        totalTokenCount: this.inputTokens + this.outputTokens,
      };
      const data = {candidates: [{index: 0, content: {role: "model", parts}, finishReason: limited ? "MAX_TOKENS" : "STOP"}], usageMetadata};
      if (this.stream) { this.emit(data); this.res.end(); }
      else { this.res.writeHead(200, {"Content-Type": "application/json"}); this.res.end(JSON.stringify(data)); }
    }
  }
}

export async function pipeResponsesToCompatible(upstream, writer) {
  const applyRespUsage = (u) => {
    if (!u) return;
    writer.setUsage(u.input_tokens, u.output_tokens, {
      cachedTokens: u.input_tokens_details?.cached_tokens ?? null,
      reasoningTokens: u.output_tokens_details?.reasoning_tokens ?? null,
    });
  };
  if (!(upstream.headers.get("content-type") || "").includes("text/event-stream")) {
    const response = await upstream.json();
    if (response.error || response.status === "failed") throw new Error(response.error?.message || "Model response failed");
    for (const [index, item] of (response.output ?? []).entries()) {
      if (item.type === "message") writer.textDelta(textOfContent(item.content));
      else if (item.type === "reasoning") writer.reasoningDelta(textOfContent(item.summary));
      else if (item.type === "function_call") writer.toolDelta(index, item.call_id, item.name, item.arguments || "{}");
    }
    applyRespUsage(response.usage);
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
      applyRespUsage(chunk.response?.usage);
      writer.finish(chunk.type === "response.incomplete" ? "length" : "stop");
      completed = true;
      break;
    }
  }
  if (!completed) throw new Error("Model response stream ended before completion");
}
