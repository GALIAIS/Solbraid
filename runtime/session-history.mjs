import {randomUUID} from "node:crypto";

export function historyWindow(messages, cursor, limit, isPrompt, messageId) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new Error("历史分页大小无效");
  let end = messages.length;
  if (cursor != null) {
    const separator = typeof cursor === "string" ? cursor.indexOf(":") : -1;
    end = separator > 0 ? Number(cursor.slice(0, separator)) : -1;
    if (!Number.isSafeInteger(end) || end < 1 || end >= messages.length ||
        String(messageId(messages[end]) ?? "") !== cursor.slice(separator + 1)) {
      throw new Error("会话历史已变更，请重新打开会话");
    }
  }
  let start = end;
  let prompts = 0;
  while (start > 0) {
    start--;
    if (isPrompt(messages[start]) && ++prompts === limit) break;
  }
  return {start, end, nextCursor: start > 0 ? `${start}:${messageId(messages[start]) ?? ""}` : null};
}

// 单轮可能包含很长的工具输出。分块传输同一份快照，保留文本及工具调用和结果的对应关系。
const chunkChars = 256 * 1024;
const transfers = new Map();

export function historyResponse(owner, value) {
  const json = JSON.stringify(value);
  if (json.length <= chunkChars) return value;
  if (transfers.size >= 8) throw new Error("正在读取多个历史页面，请稍后重试");
  const transfer = randomUUID();
  const timer = setTimeout(() => transfers.delete(transfer), 120_000);
  timer.unref();
  transfers.set(transfer, {owner, json, timer});
  return readHistoryChunk(owner, transfer, 0);
}

export function readHistoryChunk(owner, transfer, offset) {
  const entry = transfers.get(transfer);
  if (!entry || entry.owner !== owner) throw new Error("历史分页已过期，请重试");
  if (!Number.isSafeInteger(offset) || offset < 0 || offset >= entry.json.length) throw new Error("历史分块位置无效");
  let end = Math.min(offset + chunkChars, entry.json.length);
  const last = entry.json.charCodeAt(end - 1);
  if (end < entry.json.length && last >= 0xd800 && last <= 0xdbff) end--;
  const chunk = entry.json.slice(offset, end);
  const done = end === entry.json.length;
  if (done) {
    clearTimeout(entry.timer);
    transfers.delete(transfer);
  } else {
    entry.timer.refresh();
  }
  return {transfer, offset, chunk, done};
}
