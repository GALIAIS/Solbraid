import fs from "node:fs/promises";
import {constants, createReadStream, createWriteStream} from "node:fs";
import crypto from "node:crypto";
import path from "node:path";
import {createInterface} from "node:readline";

const DEFAULT_CHECKPOINT_MAX_PER_SESSION = 30;
const DEFAULT_CHECKPOINT_MAX_TOTAL_BYTES = 20 * 1024 * 1024;
const DEFAULT_READ_HARD_FULL_BYTES = 20 * 1024 * 1024;
const DEFAULT_READ_SOFT_BYTES = 256 * 1024;
const DEFAULT_READ_FALLBACK_LINES = 500;
const DEFAULT_READ_MAX_LINES = 4000;
const DEFAULT_STREAM_EDIT_THRESHOLD = 4 * 1024 * 1024;

// ── 1. Myers / Patience 紧凑行级 Unified Diff ───────────────────────────────

function splitNormalizedLines(text) {
  if (!text) return [];
  const norm = String(text).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const trimmed = norm.endsWith("\n") ? norm.slice(0, -1) : norm;
  return trimmed.length ? trimmed.split("\n") : [];
}

function myersLineOps(a, aStart, aEnd, b, bStart, bEnd, maxD = 2048) {
  const n = aEnd - aStart;
  const m = bEnd - bStart;
  if (n === 0 && m === 0) return [];
  if (n === 0) {
    const out = [];
    for (let j = bStart; j < bEnd; j++) out.push({op: "+", line: b[j]});
    return out;
  }
  if (m === 0) {
    const out = [];
    for (let i = aStart; i < aEnd; i++) out.push({op: "-", line: a[i]});
    return out;
  }
  const limit = Math.min(n + m, maxD);
  const offset = limit;
  const v = new Int32Array(2 * limit + 1);
  const trace = [];
  let foundD = -1;

  for (let d = 0; d <= limit; d++) {
    trace.push(v.slice(offset - d, offset + d + 1));
    for (let k = -d; k <= d; k += 2) {
      let x;
      if (k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])) {
        x = v[offset + k + 1];
      } else {
        x = v[offset + k - 1] + 1;
      }
      let y = x - k;
      while (x < n && y < m && a[aStart + x] === b[bStart + y]) {
        x++;
        y++;
      }
      v[offset + k] = x;
      if (x >= n && y >= m) {
        foundD = d;
        break;
      }
    }
    if (foundD >= 0) break;
  }

  if (foundD < 0) return null;

  const rev = [];
  let x = n;
  let y = m;
  for (let d = foundD; d > 0; d--) {
    const snap = trace[d];
    const k = x - y;
    const prevK = (k === -d || (k !== d && snap[k - 1 + d] < snap[k + 1 + d])) ? (k + 1) : (k - 1);
    const prevX = snap[prevK + d];
    const prevY = prevX - prevK;
    while (x > prevX && y > prevY) {
      rev.push({op: " ", line: a[aStart + x - 1]});
      x--;
      y--;
    }
    if (x === prevX) {
      rev.push({op: "+", line: b[bStart + y - 1]});
      y--;
    } else {
      rev.push({op: "-", line: a[aStart + x - 1]});
      x--;
    }
  }
  while (x > 0 && y > 0) {
    rev.push({op: " ", line: a[aStart + x - 1]});
    x--;
    y--;
  }
  while (x > 0) {
    rev.push({op: "-", line: a[aStart + x - 1]});
    x--;
  }
  while (y > 0) {
    rev.push({op: "+", line: b[bStart + y - 1]});
    y--;
  }
  rev.reverse();
  return rev;
}

function diffRangeWithAnchors(a, aStart, aEnd, b, bStart, bEnd, depth = 0) {
  let as = aStart;
  let ae = aEnd;
  let bs = bStart;
  let be = bEnd;
  const prefix = [];
  const suffix = [];
  while (as < ae && bs < be && a[as] === b[bs]) {
    prefix.push({op: " ", line: a[as]});
    as++;
    bs++;
  }
  while (as < ae && bs < be && a[ae - 1] === b[be - 1]) {
    suffix.push({op: " ", line: a[ae - 1]});
    ae--;
    be--;
  }
  suffix.reverse();

  let mid = myersLineOps(a, as, ae, b, bs, be, 2048);
  if (mid === null) {
    if (depth < 8 && (ae - as) > 0 && (be - bs) > 0) {
      const aCounts = new Map();
      const bCounts = new Map();
      for (let i = as; i < ae; i++) aCounts.set(a[i], (aCounts.get(a[i]) || 0) + 1);
      for (let j = bs; j < be; j++) bCounts.set(b[j], (bCounts.get(b[j]) || 0) + 1);
      const bUniquePos = new Map();
      for (let j = bs; j < be; j++) {
        if (bCounts.get(b[j]) === 1) bUniquePos.set(b[j], j);
      }
      const pairs = [];
      for (let i = as; i < ae; i++) {
        if (aCounts.get(a[i]) === 1 && bUniquePos.has(a[i])) {
          pairs.push([i, bUniquePos.get(a[i])]);
        }
      }
      if (pairs.length > 0) {
        const tails = [];
        const prev = new Int32Array(pairs.length).fill(-1);
        for (let idx = 0; idx < pairs.length; idx++) {
          const val = pairs[idx][1];
          let lo = 0;
          let hi = tails.length;
          while (lo < hi) {
            const m = (lo + hi) >>> 1;
            if (pairs[tails[m]][1] < val) lo = m + 1;
            else hi = m;
          }
          if (lo > 0) prev[idx] = tails[lo - 1];
          tails[lo] = idx;
        }
        const anchors = [];
        let cur = tails.length ? tails[tails.length - 1] : -1;
        while (cur >= 0) {
          anchors.push(pairs[cur]);
          cur = prev[cur];
        }
        anchors.reverse();
        if (anchors.length > 0) {
          mid = [];
          let curA = as;
          let curB = bs;
          for (const [ai, bj] of anchors) {
            if (ai > curA || bj > curB) {
              mid.push(...diffRangeWithAnchors(a, curA, ai, b, curB, bj, depth + 1));
            }
            mid.push({op: " ", line: a[ai]});
            curA = ai + 1;
            curB = bj + 1;
          }
          if (curA < ae || curB < be) {
            mid.push(...diffRangeWithAnchors(a, curA, ae, b, curB, be, depth + 1));
          }
        }
      }
    }
    if (mid === null) {
      mid = [];
      for (let i = as; i < ae; i++) mid.push({op: "-", line: a[i]});
      for (let j = bs; j < be; j++) mid.push({op: "+", line: b[j]});
    }
  }
  return prefix.concat(mid, suffix);
}

/**
 * 基于完整旧/新文本在源头直接计算紧凑 Unified Diff（仅保留变更 hunk ±3 行上下文），
 * 彻底杜绝整文件替换、前 32KB 截断丢失变更与超大 JSON 膨胀。
 */
export function computeUnifiedDiff(filePath, oldText, newText, contextOrOpts = 3, maxChars = 48_000) {
  const opts = typeof contextOrOpts === "object" && contextOrOpts !== null
    ? contextOrOpts
    : {context: contextOrOpts, maxOutputChars: maxChars};
  const context = Number.isFinite(Number(opts.context)) ? Math.max(0, Number(opts.context)) : 3;
  const maxOutputChars = Number(opts.maxOutputChars) > 0 ? Number(opts.maxOutputChars) : 48_000;
  const a = splitNormalizedLines(oldText);
  const b = splitNormalizedLines(newText);
  const clean = String(filePath || "file").replace(/^\/+/, "") || "file";
  const headerLine = `--- a/${clean}\n+++ b/${clean}\n`;
  if (!a.length && !b.length) return headerLine;
  const ops = diffRangeWithAnchors(a, 0, a.length, b, 0, b.length, 0);
  const changedIndices = [];
  for (let i = 0; i < ops.length; i++) {
    if (ops[i].op !== " ") changedIndices.push(i);
  }
  if (changedIndices.length === 0) return headerLine;

  const parts = [headerLine];
  let totalLen = headerLine.length;
  let index = 0;

  while (index < changedIndices.length) {
    const start = Math.max(0, changedIndices[index] - context);
    let end = Math.min(ops.length - 1, changedIndices[index] + context);
    while (index + 1 < changedIndices.length && changedIndices[index + 1] - context <= end + 1) {
      index++;
      end = Math.min(ops.length - 1, changedIndices[index] + context);
    }
    let oldLine = 1;
    let newLine = 1;
    for (let i = 0; i < start; i++) {
      if (ops[i].op !== "+") oldLine++;
      if (ops[i].op !== "-") newLine++;
    }
    let oldCount = 0;
    let newCount = 0;
    const body = [];
    for (let i = start; i <= end; i++) {
      const {op, line} = ops[i];
      if (op !== "+") oldCount++;
      if (op !== "-") newCount++;
      body.push(`${op}${line}\n`);
    }
    const header = `@@ -${oldLine},${oldCount} +${newLine},${newCount} @@\n`;
    const block = header + body.join("");
    if (totalLen + block.length > maxOutputChars && parts.length > 1) {
      parts.push(`@@ ... 其余变更已省略（共 ${changedIndices.length} 处行变动） @@\n`);
      break;
    }
    parts.push(block);
    totalLen += block.length;
    index++;
  }
  return parts.join("");
}

export function countUnifiedDiffStats(diffText) {
  let added = 0;
  let removed = 0;
  let inHunk = false;
  for (const line of String(diffText || "").split("\n")) {
    if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("diff --git ")) {
      inHunk = false;
      continue;
    }
    if (line.startsWith("@@ ")) {
      inHunk = true;
      continue;
    }
    if (!inHunk) continue;
    if (line.startsWith("+")) added++;
    else if (line.startsWith("-")) removed++;
  }
  return {added, removed};
}

// ── 2. 路径边界守卫、二进制魔数与三段编码无损编解码 ─────────────────────────

export async function resolveSafeHomePath(rawPath, {
  cwd = "",
  allowedRoot = "",
  allowMissing = false,
} = {}) {
  if (typeof rawPath !== "string" || !rawPath.trim()) throw new Error("文件路径不能为空");
  if (rawPath.includes("\0")) throw new Error("文件路径包含非法 NUL 字符");
  const rootDir = path.resolve(allowedRoot || process.env.HOME || "/home/agent");
  const baseDir = cwd ? path.resolve(cwd) : path.join(rootDir, "workspace");
  const resolved = path.resolve(baseDir, rawPath.trim());
  const rootPrefix = rootDir.endsWith(path.sep) ? rootDir : (rootDir + path.sep);
  if (resolved !== rootDir && !resolved.startsWith(rootPrefix)) {
    throw new Error(`路径超出安全工作目录范围（拒绝访问）：${rawPath}`);
  }
  let ancestor = resolved;
  while (true) {
    try {
      const real = await fs.realpath(ancestor);
      const realRoot = await fs.realpath(rootDir).catch(() => rootDir);
      const realRootPrefix = realRoot.endsWith(path.sep) ? realRoot : (realRoot + path.sep);
      if (real !== realRoot && !real.startsWith(realRootPrefix)) {
        throw new Error(`符号链接指向工作目录之外（拒绝访问）：${rawPath}`);
      }
      return path.join(real, path.relative(ancestor, resolved));
    } catch (error) {
      if (!allowMissing || error.code !== "ENOENT") throw error;
      const entry = await fs.lstat(ancestor).catch(cause => {
        if (cause.code !== "ENOENT") throw cause;
        return null;
      });
      if (entry?.isSymbolicLink()) throw new Error(`目标为悬空符号链接，已拒绝覆盖：${rawPath}`);
      const parent = path.dirname(ancestor);
      if (ancestor === rootDir || parent === ancestor) throw error;
      ancestor = parent;
    }
  }
}

export function looksBinaryBuffer(buf) {
  if (!buf || buf.length === 0) return false;
  // UTF-16 LE / BE BOM 属于文本而非二进制
  if (buf.length >= 2 && ((buf[0] === 0xff && buf[1] === 0xfe) || (buf[0] === 0xfe && buf[1] === 0xff))) {
    return false;
  }
  const len = Math.min(buf.length, 8192);
  let nonText = 0;
  for (let i = 0; i < len; i++) {
    const b = buf[i];
    if (b === 0) return true;
    if (b < 0x09 || (b >= 0x0e && b <= 0x1f && b !== 0x1b)) nonText++;
  }
  return nonText > len * 0.15;
}

export function detectMagicDescription(buf) {
  if (!buf || buf.length === 0) return "空文件";
  const headAscii = (s, off = 0) => buf.length >= off + s.length && buf.subarray(off, off + s.length).toString("ascii") === s;
  if (headAscii("%PDF-")) return "PDF 文档";
  if (headAscii("SQLite format 3")) return "SQLite 3 数据库";
  if (buf.length >= 4 && buf[0] === 0x7f && buf[1] === 0x45 && buf[2] === 0x4c && buf[3] === 0x46) {
    return buf.length > 4 && buf[4] === 2 ? "ELF 64-bit 可执行/动态库" : "ELF 32-bit 可执行/动态库";
  }
  if (buf.length >= 4 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return "PNG 图像";
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return "JPEG 图像";
  if (headAscii("GIF8")) return "GIF 动图";
  if (buf.length >= 4 && buf[0] === 0x50 && buf[1] === 0x4b && buf[2] === 0x03 && buf[3] === 0x04) return "ZIP / APK / JAR 归档";
  if (buf.length >= 2 && buf[0] === 0x1f && buf[1] === 0x8b) return "GZIP 压缩流";
  if (headAscii("dex\n")) return "Android DEX 字节码";
  if (looksBinaryBuffer(buf)) return "二进制数据";
  return "纯文本";
}

function isValidUtf8Slice(buf, rawStart, rawLen) {
  if (!buf || rawLen <= 0 || rawStart >= buf.length) return true;
  let start = Math.max(0, Math.min(rawStart, buf.length));
  const end = Math.min(buf.length, rawStart + rawLen);
  if (start > 0) {
    let skipped = 0;
    while (start < end && skipped < 3 && (buf[start] & 0xc0) === 0x80) {
      start++;
      skipped++;
    }
  }
  let trimEnd = end;
  if (trimEnd < buf.length && trimEnd > start) {
    let back = 0;
    while (trimEnd > start && back < 4 && (buf[trimEnd - 1] & 0x80) !== 0) {
      const b = buf[trimEnd - 1];
      trimEnd--;
      back++;
      if ((b & 0xc0) !== 0x80) break;
    }
  }
  if (trimEnd <= start) return true;
  try {
    new TextDecoder("utf-8", {fatal: true}).decode(buf.subarray(start, trimEnd));
    return true;
  } catch {
    return false;
  }
}

/** 三段抽样（头 64KB + 中 32KB + 尾 32KB）检测编码：支持 utf-8 / utf-8-bom / utf-16le / utf-16be / gb18030。 */
export function detectBufferEncoding(buf) {
  if (!buf || buf.length === 0) return "utf-8";
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) return "utf-8-bom";
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) return "utf-16le";
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) return "utf-16be";
  if (!isValidUtf8Slice(buf, 0, Math.min(buf.length, 65_536))) return "gb18030";
  if (buf.length > 65_536) {
    const mid = Math.max(65_536, Math.floor(buf.length / 2) - 16_384);
    if (!isValidUtf8Slice(buf, mid, 32_768)) return "gb18030";
    const tail = Math.max(0, buf.length - 32_768);
    if (!isValidUtf8Slice(buf, tail, 32_768)) return "gb18030";
  }
  return "utf-8";
}

function normalizeEncodingName(raw) {
  const s = String(raw || "").trim().toLowerCase().replace(/_/g, "-");
  if (!s) return "";
  if (s === "utf8" || s === "utf-8") return "utf-8";
  if (s === "utf8-bom" || s === "utf-8-bom") return "utf-8-bom";
  if (s === "gbk" || s === "gb2312" || s === "gb18030") return "gb18030";
  if (s === "utf16le" || s === "utf-16le") return "utf-16le";
  if (s === "utf16be" || s === "utf-16be") return "utf-16be";
  if (s === "latin1" || s === "iso-8859-1") return "iso-8859-1";
  return s;
}

let gbkEncodeMap = null;
function getGbkEncodeMap() {
  if (gbkEncodeMap) return gbkEncodeMap;
  const dec = new TextDecoder("gb18030", {fatal: false});
  const map = new Map();
  const pair = Buffer.alloc(2);
  for (let b1 = 0x81; b1 <= 0xfe; b1++) {
    pair[0] = b1;
    for (let b2 = 0x40; b2 <= 0xfe; b2++) {
      if (b2 === 0x7f) continue;
      pair[1] = b2;
      const ch = dec.decode(pair);
      if (ch.length === 1 && ch !== "\uFFFD" && !map.has(ch)) {
        map.set(ch, (b1 << 8) | b2);
      }
    }
  }
  gbkEncodeMap = map;
  return map;
}

export function decodeTextBuffer(buf, encodingOverride = "") {
  const enc = normalizeEncodingName(encodingOverride) || detectBufferEncoding(buf);
  let payload = buf;
  if (enc === "utf-8-bom" && buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    payload = buf.subarray(3);
  } else if ((enc === "utf-16le" || enc === "utf-16be") && buf.length >= 2 &&
      ((buf[0] === 0xff && buf[1] === 0xfe) || (buf[0] === 0xfe && buf[1] === 0xff))) {
    payload = buf.subarray(2);
  }
  let rawText;
  if (enc === "utf-8" || enc === "utf-8-bom") {
    rawText = payload.toString("utf8");
  } else if (enc === "utf-16le") {
    rawText = payload.toString("utf16le");
  } else if (enc === "utf-16be") {
    const swapped = Buffer.from(payload);
    swapped.swap16();
    rawText = swapped.toString("utf16le");
  } else if (enc === "iso-8859-1") {
    rawText = payload.toString("latin1");
  } else {
    try {
      rawText = new TextDecoder(enc, {fatal: false}).decode(payload);
    } catch {
      rawText = payload.toString("utf8");
    }
  }

  let crlfCount = 0;
  let lfOnlyCount = 0;
  let crOnlyCount = 0;
  for (let i = 0; i < rawText.length; i++) {
    const c = rawText.charCodeAt(i);
    if (c === 13) {
      if (i + 1 < rawText.length && rawText.charCodeAt(i + 1) === 10) {
        crlfCount++;
        i++;
      } else {
        crOnlyCount++;
      }
    } else if (c === 10) {
      lfOnlyCount++;
    }
  }
  const lineEnding = crlfCount > lfOnlyCount && crlfCount > 0 ? "CRLF" : "LF";
  const mixedLineEndings = (crlfCount > 0 && lfOnlyCount > 0) || crOnlyCount > 0;
  const text = rawText.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const hasTrailingNewline = text.endsWith("\n");
  const sha256 = crypto.createHash("sha256").update(buf).digest("hex");
  return {text, encoding: enc, lineEnding, hasTrailingNewline, mixedLineEndings, sha256};
}

export function encodeTextBuffer(text, encoding = "utf-8", lineEnding = "LF", preserveTrailingNewline = null) {
  const enc = normalizeEncodingName(encoding) || "utf-8";
  let norm = String(text ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  if (preserveTrailingNewline === true && norm.length > 0 && !norm.endsWith("\n")) {
    norm += "\n";
  }
  const finalStr = String(lineEnding || "LF").toUpperCase() === "CRLF" ? norm.replace(/\n/g, "\r\n") : norm;

  if (enc === "utf-8") return Buffer.from(finalStr, "utf8");
  if (enc === "utf-8-bom") return Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(finalStr, "utf8")]);
  if (enc === "iso-8859-1") return Buffer.from(finalStr, "latin1");
  if (enc === "utf-16le") return Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(finalStr, "utf16le")]);
  if (enc === "utf-16be") {
    const body = Buffer.from(finalStr, "utf16le");
    body.swap16();
    return Buffer.concat([Buffer.from([0xfe, 0xff]), body]);
  }
  if (enc === "gb18030") {
    const map = getGbkEncodeMap();
    const out = [];
    for (const ch of finalStr) {
      const code = ch.charCodeAt(0);
      if (ch.length === 1 && code < 0x80) {
        out.push(code);
      } else {
        const mapped = map.get(ch);
        if (mapped !== undefined) {
          out.push((mapped >> 8) & 0xff, mapped & 0xff);
        } else {
          // 非 GBK 罕见字符回退为 '?' 避免静默截断整个缓冲
          out.push(0x3f);
        }
      }
    }
    return Buffer.from(out);
  }
  return Buffer.from(finalStr, "utf8");
}

async function probeFileMetadata(targetPath) {
  const st = await fs.stat(targetPath);
  if (st.isDirectory()) throw new Error(`目标路径为目录而非文件：${targetPath}`);
  const size = Number(st.size) || 0;
  const fh = await fs.open(targetPath, "r");
  try {
    const headLen = Math.min(65_536, size);
    const head = Buffer.alloc(headLen);
    let headRead = 0;
    if (headLen > 0) {
      const res = await fh.read(head, 0, headLen, 0);
      headRead = res.bytesRead;
    }
    const headSlice = head.subarray(0, headRead);
    if (looksBinaryBuffer(headSlice)) {
      const magic = detectMagicDescription(headSlice);
      throw new Error(`Binary file cannot be read as text: ${path.basename(targetPath)} (${magic}, ${size} bytes)`);
    }
    let encoding = detectBufferEncoding(headSlice);
    if (encoding === "utf-8" && size > 65_536) {
      const tailLen = Math.min(32_768, size - 65_536);
      const tail = Buffer.alloc(tailLen);
      const tRes = await fh.read(tail, 0, tailLen, size - tailLen);
      if (!isValidUtf8Slice(tail.subarray(0, tRes.bytesRead), 0, tRes.bytesRead)) {
        encoding = "gb18030";
      }
    }
    // 从头部样本快速判断换行风格（CRLF/LF）
    const sampleStr = (encoding === "utf-8" || encoding === "utf-8-bom")
      ? headSlice.toString("utf8")
      : decodeTextBuffer(headSlice, encoding).text;
    const rawSample = (encoding === "utf-8" || encoding === "utf-8-bom") ? sampleStr : headSlice.toString("latin1");
    let crlf = 0;
    let lf = 0;
    for (let i = 0; i < rawSample.length; i++) {
      if (rawSample.charCodeAt(i) === 13 && rawSample.charCodeAt(i + 1) === 10) { crlf++; i++; }
      else if (rawSample.charCodeAt(i) === 10) lf++;
    }
    const lineEnding = crlf > lf && crlf > 0 ? "CRLF" : "LF";
    return {st, size, encoding, lineEnding};
  } finally {
    await fh.close();
  }
}

// ── 3. 流式安全读取与流式搜索（支持 UTF-8/GBK/UTF-16、二进制拦截、SHA-256 修订锁）──

async function sha256OfFile(targetPath) {
  const hash = crypto.createHash("sha256");
  await new Promise((resolve, reject) => {
    const s = createReadStream(targetPath, {highWaterMark: 65_536});
    s.on("data", chunk => hash.update(chunk));
    s.once("error", reject);
    s.once("end", resolve);
  });
  return hash.digest("hex");
}

function formatNumberedLines(lines, startLine) {
  return lines.map((line, i) => `${startLine + i}: ${line}`).join("\n");
}

/**
 * 统一安全读取（供 ACP `fs/read_text_file` 与 Agent `solbraid_file_read` 共用）：
 * - 8KB 头部魔数与二进制拦截；
 * - 自动识别 UTF-8 / UTF-8 BOM / GB18030(GBK) / UTF-16 LE·BE 及 CRLF/LF；
 * - UTF-8 大文件全程流式按行读取（>20MB 未传 limit 自动软限 500 行），零 OOM；
 * - 返回精确内容、行号版本、编码/换行元信息与 `sha256` 乐观锁锚点。
 */
export async function safeReadTextFile(targetPath, options = {}) {
  const meta = await probeFileMetadata(targetPath);
  const enc = normalizeEncodingName(options.encoding) || meta.encoding;
  const startLine = Math.max(1, Math.floor(Number(options.line || options.startLine) || 1));
  const explicitLimit = Number(options.limit) > 0 ? Math.floor(Number(options.limit)) : 0;
  const explicitEndLine = Number(options.endLine) > 0 ? Math.floor(Number(options.endLine)) : 0;
  const maxFullBytes = Number(options.maxFullReadBytes) || DEFAULT_READ_HARD_FULL_BYTES;
  const maxReturnBytes = Number(options.maxBytes) || DEFAULT_READ_SOFT_BYTES;
  const requestedLimit = explicitEndLine >= startLine
    ? (explicitEndLine - startLine + 1)
    : explicitLimit;
  const maxLines = requestedLimit > 0
    ? Math.min(requestedLimit, Number(options.maxLines) || DEFAULT_READ_MAX_LINES)
    : (meta.size > maxFullBytes ? DEFAULT_READ_FALLBACK_LINES : (Number(options.maxLines) || DEFAULT_READ_MAX_LINES));

  // 非 UTF-8 编码（GB18030 / UTF-16 等）≤20MB 无损解码切片
  if (enc !== "utf-8" && enc !== "utf-8-bom") {
    if (meta.size > maxFullBytes) {
      throw new Error(`非 UTF-8 编码文件超过 ${(maxFullBytes / (1024 * 1024)).toFixed(0)} MB，请先转换编码或使用专用工具：${path.basename(targetPath)}`);
    }
    const buf = await fs.readFile(targetPath);
    const decoded = decodeTextBuffer(buf, enc);
    const allLines = splitNormalizedLines(decoded.text);
    const totalLines = allLines.length;
    const outLines = [];
    let outBytes = 0;
    let truncated = false;
    let hitByteCap = false;
    for (let idx = startLine - 1; idx < totalLines; idx++) {
      if (outLines.length >= maxLines) { truncated = true; break; }
      const ln = allLines[idx];
      const lb = Buffer.byteLength(ln, "utf8") + 1;
      if (outLines.length > 0 && outBytes + lb > maxReturnBytes) { truncated = true; hitByteCap = true; break; }
      outLines.push(ln);
      outBytes += lb;
    }
    let content = outLines.join("\n");
    if (truncated) {
      const nextLine = startLine + outLines.length;
      const reason = hitByteCap ? `reached ${Math.round(maxReturnBytes / 1024)} KB read limit` : `reached ${maxLines}-line read limit`;
      content += `\n[Solbraid: output truncated (${reason}); showing lines ${startLine}-${nextLine - 1} of ${totalLines}. Pass line=${nextLine} and limit to read more.]`;
    }
    return {
      content,
      numberedContent: formatNumberedLines(outLines, startLine),
      startLine,
      endLine: outLines.length > 0 ? startLine + outLines.length - 1 : startLine,
      lineCount: outLines.length,
      totalLines,
      truncated,
      totalBytes: meta.size,
      encoding: decoded.encoding,
      lineEnding: decoded.lineEnding,
      hasTrailingNewline: decoded.hasTrailingNewline,
      mixedLineEndings: decoded.mixedLineEndings,
      sha256: decoded.sha256,
    };
  }

  // UTF-8 / UTF-8-BOM 全程流式按行读取
  const outLines = [];
  let outBytes = 0;
  let lineNo = 0;
  let truncated = false;
  let hitByteCap = false;
  const stripBom = enc === "utf-8-bom";

  await new Promise((resolve, reject) => {
    const stream = createReadStream(targetPath, {encoding: "utf8", highWaterMark: 65_536});
    const rl = createInterface({input: stream, crlfDelay: Infinity});
    let finished = false;
    const done = err => {
      if (finished) return;
      finished = true;
      try { rl.close(); } catch {}
      try { stream.destroy(); } catch {}
      if (err) reject(err);
      else resolve();
    };
    stream.once("error", done);
    rl.once("error", done);
    rl.on("line", rawLine => {
      lineNo++;
      const line = (lineNo === 1 && stripBom && rawLine.charCodeAt(0) === 0xfeff) ? rawLine.slice(1) : rawLine;
      if (lineNo < startLine) return;
      if (outLines.length >= maxLines) {
        truncated = true;
        done();
        return;
      }
      const lineBytes = Buffer.byteLength(line, "utf8") + 1;
      if (outLines.length > 0 && outBytes + lineBytes > maxReturnBytes) {
        truncated = true;
        hitByteCap = true;
        done();
        return;
      }
      outLines.push(line);
      outBytes += lineBytes;
    });
    rl.once("close", () => done());
  });

  let content = outLines.join("\n");
  if (truncated) {
    const nextLine = startLine + outLines.length;
    const reason = meta.size > maxFullBytes && !requestedLimit
      ? `file is ${(meta.size / (1024 * 1024)).toFixed(1)} MB`
      : (hitByteCap ? `reached ${Math.round(maxReturnBytes / 1024)} KB read limit` : `reached ${maxLines}-line read limit`);
    content += `\n[Solbraid: output truncated (${reason}); showing lines ${startLine}-${nextLine - 1}. Pass line=${nextLine} and limit to read more.]`;
  }
  const sha256 = meta.size <= 4 * 1024 * 1024
    ? await sha256OfFile(targetPath).catch(() => "")
    : `${meta.size}:${Math.floor(meta.st.mtimeMs || 0)}`;
  return {
    content,
    numberedContent: formatNumberedLines(outLines, startLine),
    startLine,
    endLine: outLines.length > 0 ? startLine + outLines.length - 1 : startLine,
    lineCount: outLines.length,
    totalLines: truncated ? null : lineNo,
    truncated,
    totalBytes: meta.size,
    encoding: enc,
    lineEnding: meta.lineEnding,
    hasTrailingNewline: true,
    mixedLineEndings: false,
    sha256,
  };
}

/** 零 OOM 流式检索单个大文件（支持普通子串/正则、GBK/UTF-16 与二进制拦截）。 */
export async function streamSearchFile(targetPath, {
  query = "",
  useRegex = false,
  caseSensitive = false,
  maxHits = 80,
} = {}) {
  const q = String(query || "");
  if (!q) throw new Error("搜索关键词不能为空");
  const meta = await probeFileMetadata(targetPath);
  const regex = useRegex ? new RegExp(q, caseSensitive ? "" : "i") : null;
  const needle = caseSensitive ? q : q.toLowerCase();
  const limit = Math.max(1, Math.min(200, Math.floor(Number(maxHits) || 80)));
  const hits = [];

  if (meta.encoding !== "utf-8" && meta.encoding !== "utf-8-bom") {
    if (meta.size > DEFAULT_READ_HARD_FULL_BYTES) {
      throw new Error("非 UTF-8 编码大文件超过 20 MB，不支持直接搜索");
    }
    const decoded = decodeTextBuffer(await fs.readFile(targetPath), meta.encoding);
    const lines = splitNormalizedLines(decoded.text);
    for (let i = 0; i < lines.length && hits.length < limit; i++) {
      const line = lines[i];
      const ok = regex ? regex.test(line) : (caseSensitive ? line.includes(needle) : line.toLowerCase().includes(needle));
      if (ok) hits.push({lineNumber: i + 1, preview: line.trim().slice(0, 200)});
    }
    return {filePath: targetPath, encoding: meta.encoding, totalBytes: meta.size, hitCount: hits.length, truncated: hits.length >= limit, hits};
  }

  let lineNo = 0;
  let truncated = false;
  await new Promise((resolve, reject) => {
    const stream = createReadStream(targetPath, {encoding: "utf8", highWaterMark: 65_536});
    const rl = createInterface({input: stream, crlfDelay: Infinity});
    let finished = false;
    const done = err => {
      if (finished) return;
      finished = true;
      try { rl.close(); } catch {}
      try { stream.destroy(); } catch {}
      if (err) reject(err);
      else resolve();
    };
    stream.once("error", done);
    rl.once("error", done);
    rl.on("line", line => {
      lineNo++;
      const ok = regex ? regex.test(line) : (caseSensitive ? line.includes(needle) : line.toLowerCase().includes(needle));
      if (ok) {
        if (hits.length >= limit) {
          truncated = true;
          done();
          return;
        }
        hits.push({lineNumber: lineNo, preview: line.trim().slice(0, 200)});
      }
    });
    rl.once("close", () => done());
  });
  return {filePath: targetPath, encoding: meta.encoding, totalBytes: meta.size, hitCount: hits.length, truncated, hits};
}

// ── 4. 轻量化文件检查点库（全引擎共享，支持可逆单文件回滚） ──────────────────

function sanitizeCheckpointSessionId(sessionId) {
  const clean = String(sessionId || "default").replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 80);
  return clean || "default";
}

export function defaultCheckpointRoot() {
  return path.join(process.env.HOME || "/home/agent", ".galiais", "checkpoints");
}

export async function pruneCheckpointRoot(
  checkpointRoot = defaultCheckpointRoot(),
  {maxPerSession = DEFAULT_CHECKPOINT_MAX_PER_SESSION, maxTotalBytes = DEFAULT_CHECKPOINT_MAX_TOTAL_BYTES} = {},
) {
  let sessionDirs;
  try { sessionDirs = await fs.readdir(checkpointRoot, {withFileTypes: true}); } catch { return; }
  const allEntries = [];
  for (const d of sessionDirs) {
    if (!d.isDirectory()) continue;
    const sDir = path.join(checkpointRoot, d.name);
    let files;
    try { files = await fs.readdir(sDir); } catch { continue; }
    const metas = [];
    for (const f of files) {
      if (!f.endsWith(".meta.json")) continue;
      const metaPath = path.join(sDir, f);
      const dataPath = metaPath.replace(/\.meta\.json$/, ".bak");
      try {
        const meta = JSON.parse(await fs.readFile(metaPath, "utf8"));
        const st = await fs.stat(dataPath).catch(() => null);
        const item = {metaPath, dataPath, ts: Number(meta.ts || 0), bytes: (st?.size || 0) + 512};
        metas.push(item);
        allEntries.push(item);
      } catch {
        await fs.rm(metaPath, {force: true}).catch(() => {});
        await fs.rm(dataPath, {force: true}).catch(() => {});
      }
    }
    metas.sort((a, b) => b.ts - a.ts);
    for (const stale of metas.slice(maxPerSession)) {
      await fs.rm(stale.metaPath, {force: true}).catch(() => {});
      await fs.rm(stale.dataPath, {force: true}).catch(() => {});
      stale.deleted = true;
    }
  }
  const survivors = allEntries.filter(e => !e.deleted).sort((a, b) => b.ts - a.ts);
  let total = survivors.reduce((acc, e) => acc + e.bytes, 0);
  for (let i = survivors.length - 1; i >= 0 && total > maxTotalBytes; i--) {
    const victim = survivors[i];
    await fs.rm(victim.metaPath, {force: true}).catch(() => {});
    await fs.rm(victim.dataPath, {force: true}).catch(() => {});
    total -= victim.bytes;
  }
}

export async function captureFileCheckpoint(targetPath, {
  checkpointRoot = defaultCheckpointRoot(),
  sessionId = "default",
  maxPerSession = DEFAULT_CHECKPOINT_MAX_PER_SESSION,
  maxTotalBytes = DEFAULT_CHECKPOINT_MAX_TOTAL_BYTES,
  originalContent = null,
} = {}) {
  const sDir = path.join(checkpointRoot, sanitizeCheckpointSessionId(sessionId));
  await fs.mkdir(sDir, {recursive: true});
  const id = `cp-${Date.now()}-${crypto.randomBytes(4).toString("hex")}`;
  const metaPath = path.join(sDir, `${id}.meta.json`);
  const dataPath = path.join(sDir, `${id}.bak`);

  let existed = false;
  let mode = 0o644;
  let sizeBytes = 0;
  let sha256 = "";

  if (typeof originalContent === "string") {
    existed = true;
    const buf = Buffer.from(originalContent, "utf8");
    sizeBytes = buf.length;
    sha256 = crypto.createHash("sha256").update(buf).digest("hex");
    await fs.writeFile(dataPath, buf, {mode: 0o600});
  } else {
    try {
      const st = await fs.stat(targetPath);
      if (st.isFile() && st.size <= 4 * 1024 * 1024) {
        existed = true;
        mode = st.mode & 0o7777;
        const buf = await fs.readFile(targetPath);
        sizeBytes = buf.length;
        sha256 = crypto.createHash("sha256").update(buf).digest("hex");
        await fs.writeFile(dataPath, buf, {mode: 0o600});
      }
    } catch (e) {
      if (e.code !== "ENOENT") throw e;
    }
  }

  const meta = {
    id,
    sessionId: sanitizeCheckpointSessionId(sessionId),
    filePath: path.resolve(targetPath),
    existed,
    mode,
    sizeBytes,
    sha256,
    ts: Date.now(),
  };
  await fs.writeFile(metaPath, JSON.stringify(meta), {mode: 0o600});
  pruneCheckpointRoot(checkpointRoot, {maxPerSession, maxTotalBytes}).catch(() => {});
  return meta;
}

export async function listFileCheckpoints({
  checkpointRoot = defaultCheckpointRoot(),
  sessionId = "default",
  filePath = "",
} = {}) {
  const wantedPath = filePath ? path.resolve(filePath) : "";
  const readOneDir = async dirPath => {
    let files;
    try { files = await fs.readdir(dirPath); } catch { return []; }
    const items = [];
    for (const f of files) {
      if (!f.endsWith(".meta.json")) continue;
      try {
        const meta = JSON.parse(await fs.readFile(path.join(dirPath, f), "utf8"));
        if (wantedPath && path.resolve(meta.filePath || "") !== wantedPath) continue;
        items.push(meta);
      } catch {}
    }
    return items;
  };
  // 优先查找当前 sessionId；若未指定具体 sessionId 或本会话未命中且传入了 filePath，跨会话兜底匹配同一文件的最新快照（兼容 MCP 工具与聊天会话 ID 不同）
  const ownDir = path.join(checkpointRoot, sanitizeCheckpointSessionId(sessionId));
  let out = await readOneDir(ownDir);
  if (out.length === 0 && wantedPath) {
    let dirs = [];
    try { dirs = await fs.readdir(checkpointRoot, {withFileTypes: true}); } catch {}
    for (const d of dirs) {
      if (!d.isDirectory()) continue;
      out.push(...await readOneDir(path.join(checkpointRoot, d.name)));
    }
  }
  return out.sort((a, b) => Number(b.ts || 0) - Number(a.ts || 0));
}

export async function revertFileCheckpoint({
  checkpointRoot = defaultCheckpointRoot(),
  sessionId = "default",
  checkpointId = "",
  filePath = "",
} = {}) {
  const items = await listFileCheckpoints({checkpointRoot, sessionId, filePath});
  const chosen = checkpointId ? items.find(c => c.id === checkpointId) : items[0];
  if (!chosen) {
    throw new Error(`未找到可回滚的文件检查点：${filePath || checkpointId}`);
  }
  const sDir = path.join(checkpointRoot, sanitizeCheckpointSessionId(chosen.sessionId || sessionId));
  const dataPath = path.join(sDir, `${chosen.id}.bak`);
  const target = path.resolve(chosen.filePath);

  const undoCheckpoint = await captureFileCheckpoint(target, {checkpointRoot, sessionId: chosen.sessionId || sessionId}).catch(() => null);

  if (!chosen.existed) {
    await fs.rm(target, {force: true});
    return {reverted: true, action: "deleted", filePath: target, checkpointId: chosen.id, undoCheckpointId: undoCheckpoint?.id || null};
  }
  const restoredBuf = await fs.readFile(dataPath);
  const targetMode = Number.isInteger(chosen.mode) ? chosen.mode : 0o644;
  await writeBufferAtomic(target, restoredBuf, targetMode);
  return {reverted: true, action: "restored", filePath: target, checkpointId: chosen.id, undoCheckpointId: undoCheckpoint?.id || null};
}

// ── 5. 原子写入、精准局部编辑（含超大文件零 OOM 区间补丁）与独占创建 ─────────

async function writeBufferAtomic(resolvedTarget, buf, mode) {
  await fs.mkdir(path.dirname(resolvedTarget), {recursive: true});
  const tmpName = `.${path.basename(resolvedTarget)}.solbraid.${process.pid}.${crypto.randomBytes(4).toString("hex")}.tmp`;
  const tmpPath = path.join(path.dirname(resolvedTarget), tmpName);
  const fh = await fs.open(tmpPath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, mode);
  try {
    await fh.writeFile(buf);
    await fh.sync();
  } catch (e) {
    await fh.close().catch(() => {});
    await fs.rm(tmpPath, {force: true}).catch(() => {});
    throw e;
  }
  await fh.close();
  await fs.chmod(tmpPath, mode).catch(() => {});
  try {
    await fs.rename(tmpPath, resolvedTarget);
  } catch (e) {
    await fs.rm(tmpPath, {force: true}).catch(() => {});
    throw e;
  }
}

async function resolveExistingTargetMeta(targetPath) {
  let resolvedTarget = targetPath;
  let origMode = null;
  let existed = false;
  try {
    const lst = await fs.lstat(targetPath);
    if (lst.isSymbolicLink()) {
      resolvedTarget = await fs.realpath(targetPath);
    }
    const st = await fs.stat(resolvedTarget);
    if (st.isDirectory()) throw new Error(`Target is a directory: ${resolvedTarget}`);
    origMode = st.mode & 0o7777;
    existed = true;
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
  }
  return {resolvedTarget, origMode, existed};
}

function defaultModeForPath(targetPath, content = "", explicitMode = null) {
  if (Number.isInteger(explicitMode)) return explicitMode;
  if (typeof explicitMode === "string" && /^[0-7]{3,4}$/.test(explicitMode.trim())) {
    return parseInt(explicitMode.trim(), 8);
  }
  const lower = path.basename(targetPath).toLowerCase();
  if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") || String(content || "").startsWith("#!")) {
    return 0o755;
  }
  return 0o644;
}

/**
 * 原子保权保编码写入文本文件（供 ACP `fs/write_text_file` 与内部源码更新共用）：
 * - 若目标已存在：透传软链、自动保留原 POSIX 权限位、原编码/BOM（如 GB18030/UTF-16）与原换行风格（CRLF/LF）；
 * - 写入同目录临时文件 + `fsync` + `rename`，断电/崩溃零截断损坏；
 * - 写前自动生成检查点以支持单文件一键回滚。
 */
export async function atomicWriteTextFile(targetPath, content, options = {}) {
  if (typeof content !== "string" || Buffer.byteLength(content, "utf8") > (options.maxBytes || 4 * 1024 * 1024)) {
    throw new Error("File content must be text within 4 MB");
  }
  await fs.mkdir(path.dirname(targetPath), {recursive: true});
  const {resolvedTarget, origMode, existed} = await resolveExistingTargetMeta(targetPath);

  let encoding = normalizeEncodingName(options.encoding) || "";
  let lineEnding = options.lineEnding ? String(options.lineEnding).toUpperCase() : "";
  if (existed && options.preserveMetadata !== false && (!encoding || !lineEnding)) {
    const meta = await probeFileMetadata(resolvedTarget).catch(() => null);
    if (meta) {
      if (!encoding) encoding = meta.encoding;
      if (!lineEnding) lineEnding = meta.lineEnding;
    }
  }
  encoding = encoding || "utf-8";
  lineEnding = lineEnding || "LF";

  let checkpoint = null;
  if (options.captureCheckpoint !== false && options.sessionId) {
    checkpoint = await captureFileCheckpoint(resolvedTarget, {
      checkpointRoot: options.checkpointRoot || defaultCheckpointRoot(),
      sessionId: options.sessionId,
    }).catch(() => null);
  }

  const targetMode = origMode != null ? origMode : defaultModeForPath(resolvedTarget, content, options.defaultMode);
  const outBuf = encodeTextBuffer(content, encoding, lineEnding, options.preserveTrailingNewline ?? null);
  await writeBufferAtomic(resolvedTarget, outBuf, targetMode);
  const sha256 = crypto.createHash("sha256").update(outBuf).digest("hex");
  return {path: resolvedTarget, mode: targetMode, encoding, lineEnding, sha256, checkpoint};
}

/**
 * 原子独占创建文件（防重名静默清空）：
 * - 默认 `conflictPolicy = "fail_if_exists"` 使用底层 `O_CREAT | O_EXCL` 原子判重；
 * - 支持 `auto_rename` 自动编号重命名，与 `overwrite_with_backup` 留存检查点与 `.bak` 后覆盖；
 * - `.sh` 或含 Shebang 的新脚本默认自动赋予 `0755`。
 */
export async function safeCreateFile(targetPath, content = "", options = {}) {
  if (typeof content !== "string") throw new Error("文件内容必须为字符串");
  const policy = String(options.conflictPolicy || "fail_if_exists").toLowerCase();
  const encoding = normalizeEncodingName(options.encoding) || "utf-8";
  const lineEnding = String(options.lineEnding || "LF").toUpperCase();
  const mode = defaultModeForPath(targetPath, content, options.mode);
  const outBuf = encodeTextBuffer(content, encoding, lineEnding);

  await fs.mkdir(path.dirname(targetPath), {recursive: true});

  const pickUnique = async base => {
    const dir = path.dirname(base);
    const ext = path.extname(base);
    const stem = path.basename(base, ext);
    let idx = 1;
    let cand = base;
    while (await fs.lstat(cand).then(() => true).catch(() => false)) {
      cand = path.join(dir, `${stem} (${idx})${ext}`);
      idx++;
    }
    return cand;
  };

  let finalPath = targetPath;
  if (policy === "auto_rename") {
    finalPath = await pickUnique(targetPath);
  } else if (policy === "overwrite_with_backup") {
    const exists = await fs.lstat(targetPath).then(() => true).catch(() => false);
    if (exists) {
      const bakPath = await pickUnique(`${targetPath}.bak`);
      const oldBuf = await fs.readFile(targetPath);
      await writeBufferAtomic(bakPath, oldBuf, 0o644);
    }
    const w = await atomicWriteTextFile(targetPath, content, {
      encoding,
      lineEnding,
      defaultMode: mode,
      preserveMetadata: false,
      sessionId: options.sessionId || "agent-fs",
      checkpointRoot: options.checkpointRoot || defaultCheckpointRoot(),
    });
    return {created: true, overwritten: exists, path: w.path, mode: w.mode, encoding, lineEnding, sha256: w.sha256, checkpointId: w.checkpoint?.id || null};
  }

  // fail_if_exists 或 auto_rename 均使用底层 O_CREAT | O_EXCL 原子独占创建
  let fh;
  try {
    fh = await fs.open(finalPath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, mode);
  } catch (e) {
    if (e.code === "EEXIST") {
      throw new Error(`EEXIST: 目标文件已存在，为防覆写原内容已阻止创建（可改用 safeEditFile 或传入 conflictPolicy="auto_rename" / "overwrite_with_backup"）：${finalPath}`);
    }
    throw e;
  }
  try {
    if (outBuf.length > 0) await fh.writeFile(outBuf);
    await fh.sync();
  } catch (e) {
    await fh.close().catch(() => {});
    await fs.rm(finalPath, {force: true}).catch(() => {});
    throw e;
  }
  await fh.close();
  await fs.chmod(finalPath, mode).catch(() => {});

  let checkpoint = null;
  if (options.captureCheckpoint !== false) {
    // 为新建文件记录 existed=false 的检查点，支持单文件撤回时干净删除
    const sDir = path.join(options.checkpointRoot || defaultCheckpointRoot(), sanitizeCheckpointSessionId(options.sessionId || "agent-fs"));
    await fs.mkdir(sDir, {recursive: true});
    const cpId = `cp-${Date.now()}-${crypto.randomBytes(4).toString("hex")}`;
    const cpMeta = {
      id: cpId,
      sessionId: sanitizeCheckpointSessionId(options.sessionId || "agent-fs"),
      filePath: path.resolve(finalPath),
      existed: false,
      mode,
      sizeBytes: 0,
      sha256: "",
      ts: Date.now(),
    };
    await fs.writeFile(path.join(sDir, `${cpId}.meta.json`), JSON.stringify(cpMeta), {mode: 0o600}).catch(() => {});
    checkpoint = cpMeta;
  }
  const sha256 = crypto.createHash("sha256").update(outBuf).digest("hex");
  return {created: true, path: finalPath, mode, encoding, lineEnding, sha256, checkpointId: checkpoint?.id || null};
}

/**
 * 超大 UTF-8 文件（>4MB）零 OOM 流式按行区间补丁替换：
 * 流式转发 1..(startLine-1) → 写入替换文本 → 流式转发 (endLine+1)..EOF → fsync + 保权原子 rename。
 */
async function streamPatchLargeLineRange(resolvedTarget, {
  startLine,
  endLine,
  newText,
  origMode,
  lineEnding,
  encoding,
}) {
  const nlSeq = lineEnding === "CRLF" ? "\r\n" : "\n";
  const tmpName = `.${path.basename(resolvedTarget)}.solbraid.patch.${process.pid}.${crypto.randomBytes(4).toString("hex")}.tmp`;
  const tmpPath = path.join(path.dirname(resolvedTarget), tmpName);
  const removedLines = [];
  const addedLines = splitNormalizedLines(newText);
  const normalizedReplacement = String(newText ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const replacementChunk = normalizedReplacement.length === 0
    ? ""
    : (normalizedReplacement.endsWith("\n") ? normalizedReplacement : normalizedReplacement + "\n").replace(/\n/g, nlSeq);

  let lineNo = 0;
  let replaced = false;
  const outStream = createWriteStream(tmpPath, {flags: "wx", mode: origMode});
  try {
    await new Promise((resolve, reject) => {
      const inStream = createReadStream(resolvedTarget, {encoding: "utf8", highWaterMark: 65_536});
      const rl = createInterface({input: inStream, crlfDelay: Infinity});
      let failed = false;
      const fail = err => {
        if (failed) return;
        failed = true;
        try { rl.close(); } catch {}
        try { inStream.destroy(); } catch {}
        try { outStream.destroy(); } catch {}
        reject(err);
      };
      inStream.once("error", fail);
      rl.once("error", fail);
      outStream.once("error", fail);
      if (encoding === "utf-8-bom") outStream.write(Buffer.from([0xef, 0xbb, 0xbf]));
      rl.on("line", rawLine => {
        lineNo++;
        const line = (lineNo === 1 && encoding === "utf-8-bom" && rawLine.charCodeAt(0) === 0xfeff) ? rawLine.slice(1) : rawLine;
        if (lineNo < startLine || lineNo > endLine) {
          outStream.write(line + nlSeq);
        } else {
          if (removedLines.length < 200) removedLines.push(line);
          if (!replaced) {
            replaced = true;
            if (replacementChunk) outStream.write(replacementChunk);
          }
        }
      });
      rl.once("close", () => {
        if (failed) return;
        if (!replaced && startLine === lineNo + 1 && replacementChunk) {
          replaced = true;
          outStream.write(replacementChunk);
        }
        outStream.end(() => resolve());
      });
    });
    if (!replaced) {
      throw new Error(`行号范围超出文件总行数（文件共 ${lineNo} 行，请求 L${startLine}..L${endLine}）`);
    }
    const fh = await fs.open(tmpPath, "r+");
    try { await fh.sync(); } finally { await fh.close(); }
    await fs.chmod(tmpPath, origMode).catch(() => {});
    await fs.rename(tmpPath, resolvedTarget);
  } catch (e) {
    await fs.rm(tmpPath, {force: true}).catch(() => {});
    throw e;
  }

  const diff = computeUnifiedDiff(resolvedTarget, removedLines.join("\n") + "\n", addedLines.join("\n") + "\n");
  const stats = countUnifiedDiffStats(diff);
  const stAfter = await fs.stat(resolvedTarget);
  return {
    edited: true,
    streamingPatch: true,
    path: resolvedTarget,
    mode: origMode,
    encoding,
    lineEnding,
    totalBytes: stAfter.size,
    sha256: `${stAfter.size}:${Math.floor(stAfter.mtimeMs || 0)}`,
    added: stats.added,
    removed: stats.removed,
    diff,
    checkpointId: null,
  };
}

/**
 * 精准局部编辑文件（供 Agent `solbraid_file_edit` 与运行时工具共用）：
 * - 支持多段原子替换 `edits: [{oldText, newText, replaceAll?}]`（或单段 `oldText`/`newText`）与行号区间替换 `startLine..endLine`；
 * - 支持 `expectedSha256` 乐观锁并发防踩踏；
 * - 完整保留原编码/BOM（含 GB18030/UTF-16）、原换行符（CRLF/LF）、文件尾换行、POSIX 权限与软链；
 * - >4MB UTF-8 文件行区间替换自动切换零 OOM 流式补丁；
 * - 写前自动生成可逆检查点并返回紧凑 Myers Unified Diff。
 */
export async function safeEditFile(targetPath, options = {}) {
  const {resolvedTarget, origMode, existed} = await resolveExistingTargetMeta(targetPath);
  if (!existed) throw new Error(`待编辑文件不存在：${targetPath}`);
  const meta = await probeFileMetadata(resolvedTarget);

  const hasRange = Number(options.startLine) >= 1 && Number(options.endLine) >= Number(options.startLine);
  const editsList = Array.isArray(options.edits) && options.edits.length > 0
    ? options.edits
    : (typeof options.oldText === "string" ? [{oldText: options.oldText, newText: String(options.newText ?? ""), replaceAll: Boolean(options.replaceAll)}] : []);

  if (!hasRange && editsList.length === 0) {
    throw new Error("请提供 edits / oldText+newText 或 startLine+endLine+newText");
  }

  // 超大 UTF-8 文件行区间补丁直接走零 OOM 流式通道
  if (hasRange && meta.size > DEFAULT_STREAM_EDIT_THRESHOLD && (meta.encoding === "utf-8" || meta.encoding === "utf-8-bom")) {
    const curAnchor = `${meta.size}:${Math.floor(meta.st.mtimeMs || 0)}`;
    if (options.expectedSha256 && options.expectedSha256 !== curAnchor && options.expectedSha256 !== await sha256OfFile(resolvedTarget)) {
      throw new Error(`CONFLICT: 文件自上次读取后已变化（当前修订 ${curAnchor} ≠ 预期 ${options.expectedSha256}），请重新读取后再编辑`);
    }
    return await streamPatchLargeLineRange(resolvedTarget, {
      startLine: Math.floor(Number(options.startLine)),
      endLine: Math.floor(Number(options.endLine)),
      newText: String(options.newText ?? ""),
      origMode: origMode ?? 0o644,
      lineEnding: meta.lineEnding,
      encoding: meta.encoding,
    });
  }

  if (meta.size > DEFAULT_READ_HARD_FULL_BYTES) {
    throw new Error(`文件超过 20 MB，请使用 startLine/endLine 行区间补丁模式编辑：${path.basename(resolvedTarget)}`);
  }

  const rawBuf = await fs.readFile(resolvedTarget);
  const decoded = decodeTextBuffer(rawBuf, options.encoding || "");
  const readRevision = meta.size > DEFAULT_STREAM_EDIT_THRESHOLD && (meta.encoding === "utf-8" || meta.encoding === "utf-8-bom")
    ? `${meta.size}:${Math.floor(meta.st.mtimeMs || 0)}`
    : decoded.sha256;
  if (options.expectedSha256 && options.expectedSha256 !== readRevision && options.expectedSha256 !== decoded.sha256) {
    throw new Error(`CONFLICT: 文件内容自上次读取后已变化（磁盘 sha256=${decoded.sha256.slice(0, 12)} ≠ 预期 ${String(options.expectedSha256).slice(0, 12)}），为防覆盖并发修改已中止，请重新读取`);
  }

  let nextText = decoded.text;
  if (hasRange) {
    const sLine = Math.floor(Number(options.startLine));
    const eLine = Math.floor(Number(options.endLine));
    const lines = splitNormalizedLines(nextText);
    if (sLine > lines.length + 1 || eLine > lines.length) {
      throw new Error(`行号范围越界：文件共 ${lines.length} 行，请求 L${sLine}..L${eLine}`);
    }
    const repLines = options.newText === "" ? [] : splitNormalizedLines(String(options.newText ?? ""));
    lines.splice(sLine - 1, eLine - sLine + 1, ...repLines);
    nextText = lines.join("\n") + (decoded.hasTrailingNewline && lines.length > 0 ? "\n" : "");
  } else {
    for (let i = 0; i < editsList.length; i++) {
      const ed = editsList[i] || {};
      const oldNorm = String(ed.oldText ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
      const newNorm = String(ed.newText ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
      if (!oldNorm) throw new Error(`第 ${i + 1} 条编辑缺少非空 oldText`);
      const firstIdx = nextText.indexOf(oldNorm);
      if (firstIdx < 0) {
        throw new Error(`第 ${i + 1} 条编辑未在文件中找到匹配的 oldText（请确认上下文或先重新读取）`);
      }
      if (ed.replaceAll) {
        nextText = nextText.split(oldNorm).join(newNorm);
      } else {
        const secondIdx = nextText.indexOf(oldNorm, firstIdx + 1);
        if (secondIdx >= 0) {
          throw new Error(`第 ${i + 1} 条编辑的 oldText 在文件中命中多处，请补充更多前后文确保唯一匹配，或设置 replaceAll=true`);
        }
        nextText = nextText.slice(0, firstIdx) + newNorm + nextText.slice(firstIdx + oldNorm.length);
      }
    }
  }

  if (nextText === decoded.text) {
    return {
      edited: false,
      unchanged: true,
      path: resolvedTarget,
      mode: origMode ?? 0o644,
      encoding: decoded.encoding,
      lineEnding: decoded.lineEnding,
      sha256: readRevision,
      added: 0,
      removed: 0,
      diff: "",
      checkpointId: null,
    };
  }

  let checkpoint = null;
  if (options.captureCheckpoint !== false) {
    checkpoint = await captureFileCheckpoint(resolvedTarget, {
      checkpointRoot: options.checkpointRoot || defaultCheckpointRoot(),
      sessionId: options.sessionId || "agent-fs",
    }).catch(() => null);
  }

  const outBuf = encodeTextBuffer(nextText, decoded.encoding, decoded.lineEnding, decoded.hasTrailingNewline);
  const mode = origMode ?? 0o644;
  await writeBufferAtomic(resolvedTarget, outBuf, mode);
  const writtenStat = await fs.stat(resolvedTarget);
  const newSha256 = outBuf.length > DEFAULT_STREAM_EDIT_THRESHOLD && (decoded.encoding === "utf-8" || decoded.encoding === "utf-8-bom")
    ? `${writtenStat.size}:${Math.floor(writtenStat.mtimeMs || 0)}`
    : crypto.createHash("sha256").update(outBuf).digest("hex");
  const diff = computeUnifiedDiff(resolvedTarget, decoded.text, nextText);
  const stats = countUnifiedDiffStats(diff);
  return {
    edited: true,
    path: resolvedTarget,
    mode,
    encoding: decoded.encoding,
    lineEnding: decoded.lineEnding,
    sha256: newSha256,
    added: stats.added,
    removed: stats.removed,
    diff,
    checkpointId: checkpoint?.id || null,
  };
}
