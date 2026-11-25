// Solbraid Complete Offline Mermaid-to-SVG Renderer
// Supports: Flowchart/Graph (with Subgraphs, styles, CJK width), Sequence (alt/loop/opt/Note),
// Class Diagram, ER Diagram, State Diagram, Gantt Chart, Mindmap, Pie, XYChart.
(function (global) {
  "use strict";

  function esc(s) {
    return String(s || "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // CJK-aware text pixel width estimator (prevents Chinese text overflow in SVG boxes)
  function textPx(s, fontSize = 13) {
    let units = 0;
    for (const ch of String(s || "")) {
      const code = ch.codePointAt(0) || 0;
      units += (code >= 0x2E80 && code <= 0x9FFF) || (code >= 0xFF00 && code <= 0xFFEF) ? 1.05 : 0.62;
    }
    return Math.ceil(units * fontSize);
  }

  function themePalette(dark) {
    return dark ? {
      bg: "#141821", nodeFill: "#1e2536", nodeStroke: "#60a5fa", nodeText: "#eceff4",
      muted: "#94a3b8", edge: "#94a3b8", edgeLabelBg: "#171a23", accent: "#60a5fa",
      subFill: "#171d2a", subStroke: "#334155", noteFill: "#2a2416", noteStroke: "#eab308",
      colors: ["#60a5fa", "#34d399", "#fbbf24", "#f472b6", "#a78bfa", "#fb7185", "#2dd4bf", "#f97316"],
    } : {
      bg: "#ffffff", nodeFill: "#eff6ff", nodeStroke: "#2563eb", nodeText: "#14171f",
      muted: "#64748b", edge: "#64748b", edgeLabelBg: "#ffffff", accent: "#2563eb",
      subFill: "#f8fafc", subStroke: "#cbd5e1", noteFill: "#fefce8", noteStroke: "#ca8a04",
      colors: ["#2563eb", "#059669", "#d97706", "#db2777", "#7c3aed", "#dc2626", "#0d9488", "#ea580c"],
    };
  }

  function renderMultiLineText(cx, cy, label, fontSize, fill, weight = "500") {
    const parts = String(label || "").split(/<br\s*\/?>|\\n/i).map(s => s.trim()).filter(Boolean);
    if (parts.length <= 1) {
      return `<text x="${cx}" y="${cy + fontSize * 0.35}" text-anchor="middle" font-size="${fontSize}" font-weight="${weight}" fill="${fill}">${esc(parts[0] || "")}</text>`;
    }
    const lineH = fontSize + 4;
    const startY = cy - ((parts.length - 1) * lineH) / 2 + fontSize * 0.35;
    return parts.map((p, i) => `<text x="${cx}" y="${(startY + i * lineH).toFixed(1)}" text-anchor="middle" font-size="${fontSize}" font-weight="${weight}" fill="${fill}">${esc(p)}</text>`).join("");
  }

  function parseNodeToken(raw) {
    const s = raw.trim().replace(/:::[A-Za-z0-9_-]+$/, "").trim();
    const patterns = [
      [/^([A-Za-z0-9_.-]+)\s*\(\[([^\]]+)\]\)$/, "stadium"],
      [/^([A-Za-z0-9_.-]+)\s*\(\(([^)]+)\)\)$/, "circle"],
      [/^([A-Za-z0-9_.-]+)\s*\[\(([^)]+)\)\]$/, "cylinder"],
      [/^([A-Za-z0-9_.-]+)\s*\(([^)]+)\)$/, "round"],
      [/^([A-Za-z0-9_.-]+)\s*\{\{([^}]+)\}\}$/, "hex"],
      [/^([A-Za-z0-9_.-]+)\s*\{([^}]+)\}$/, "diamond"],
      [/^([A-Za-z0-9_.-]+)\s*\[\[([^\]]+)\]\]$/, "sub"],
      [/^([A-Za-z0-9_.-]+)\s*\[\/([^/\]]+)\/\]$/, "para"],
      [/^([A-Za-z0-9_.-]+)\s*\[([^\]]+)\]$/, "rect"],
    ];
    for (const [re, shape] of patterns) {
      const m = re.exec(s);
      if (m) return {id: m[1], label: m[2].replace(/^["']|["']$/g, ""), shape};
    }
    const id = s.replace(/[^A-Za-z0-9_.-]/g, "") || ("n" + Math.random().toString(36).slice(2, 6));
    return {id, label: s.replace(/^["']|["']$/g, "") || id, shape: "rect"};
  }

  function renderFlowchart(input, dark) {
    const lines = Array.isArray(input) ? input : String(input || "").split(/\r?\n/);
    const pal = themePalette(dark);
    const header = lines[0] || "";
    const dirMatch = /\b(TD|TB|BT|LR|RL)\b/i.exec(header);
    const dir = (dirMatch ? dirMatch[1] : "TD").toUpperCase();
    const horiz = dir === "LR" || dir === "RL";
    const nodes = new Map();
    const edges = [];
    const subgraphs = [];
    const subStack = [];
    const styles = new Map();

    function upsert(tok) {
      const prev = nodes.get(tok.id);
      if (!prev || (prev.label === prev.id && tok.label !== tok.id)) nodes.set(tok.id, tok);
      if (subStack.length) subStack[subStack.length - 1].nodeIds.add(tok.id);
      return tok.id;
    }

    const edgeMidRe = /^(.+?)\s*(?:--\s+([^-]+?)\s+-->|-\.\s+([^.]+?)\s+\.->|==\s+([^=]+?)\s+==>)\s*(.+)$/;
    const edgeRe = /^(.+?)\s*(<-->|-->|-.->|==>|---|--)\s*(?:\|([^|]+)\|\s*)?(.+)$/;
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i].trim().replace(/;$/, "");
      if (!line || line.startsWith("%%") || /^(classDef|class|linkStyle|click|direction)\b/i.test(line)) continue;
      const sgM = /^subgraph\s+(?:([A-Za-z0-9_.-]+)\s*\[([^\]]+)\]|"?([^"]+)"?)$/i.exec(line);
      if (sgM) {
        const sg = {title: (sgM[2] || sgM[3] || sgM[1] || "").replace(/^["']|["']$/g, ""), nodeIds: new Set()};
        subgraphs.push(sg);
        subStack.push(sg);
        continue;
      }
      if (/^end$/i.test(line)) { subStack.pop(); continue; }
      const stM = /^style\s+([A-Za-z0-9_.-]+)\s+(.+)$/i.exec(line);
      if (stM) {
        const kv = {};
        stM[2].split(",").forEach(p => { const [k, v] = p.split(":").map(x => x && x.trim()); if (k && v) kv[k] = v; });
        styles.set(stM[1], kv);
        continue;
      }
      let m = edgeMidRe.exec(line);
      if (m) {
        const label = (m[2] || m[3] || m[4] || "").trim();
        const dashed = Boolean(m[3]), thick = Boolean(m[4]);
        const lefts = m[1].split("&").map(s => upsert(parseNodeToken(s)));
        const rights = m[5].split("&").map(s => upsert(parseNodeToken(s)));
        for (const a of lefts) for (const b of rights) edges.push({from: a, to: b, label, dashed, thick, bi: false});
        continue;
      }
      m = edgeRe.exec(line);
      if (m) {
        const op = m[2];
        const label = (m[3] || "").trim();
        const lefts = m[1].split("&").map(s => upsert(parseNodeToken(s)));
        const rights = m[4].split("&").map(s => upsert(parseNodeToken(s)));
        const noArrow = op === "---" || op === "-.-" || op === "===";
        for (const a of lefts) for (const b of rights) {
          edges.push({from: a, to: b, label, dashed: op.includes("-."), thick: op.includes("=="), bi: op === "<-->", noArrow});
        }
        continue;
      }
      if (/^[A-Za-z0-9_.-]+\s*[\[\(\{]/.test(line)) upsert(parseNodeToken(line));
    }
    if (!nodes.size) return `<div class="err-banner">未解析到 Flowchart 节点</div>`;

    const inDeg = new Map(), adj = new Map();
    for (const id of nodes.keys()) { inDeg.set(id, 0); adj.set(id, []); }
    for (const e of edges) { adj.get(e.from)?.push(e.to); inDeg.set(e.to, (inDeg.get(e.to) || 0) + 1); }
    const level = new Map();
    const relaxCount = new Map();
    const queue = [...nodes.keys()].filter(k => (inDeg.get(k) || 0) === 0);
    if (!queue.length) queue.push([...nodes.keys()][0]);
    for (const q of queue) { level.set(q, 0); relaxCount.set(q, 0); }
    const maxOps = Math.max(64, nodes.size * 6);
    let head = 0;
    while (head < queue.length && head < maxOps) {
      const u = queue[head++];
      const lu = level.get(u) || 0;
      for (const v of (adj.get(u) || [])) {
        const rc = relaxCount.get(v) || 0;
        if (!level.has(v) || (level.get(v) < lu + 1 && lu < 20 && rc < 3)) {
          level.set(v, lu + 1);
          relaxCount.set(v, rc + 1);
          queue.push(v);
        }
      }
    }
    for (const id of nodes.keys()) if (!level.has(id)) level.set(id, 0);

    const layers = new Map();
    for (const [id, lv] of level.entries()) {
      if (!layers.has(lv)) layers.set(lv, []);
      layers.get(lv).push(nodes.get(id));
    }
    const sortedLevels = [...layers.keys()].sort((a, b) => (dir === "BT" || dir === "RL") ? b - a : a - b);
    const dims = new Map();
    for (const n of nodes.values()) {
      const segs = n.label.split(/<br\s*\/?>|\\n/i);
      const maxLinePx = Math.max(...segs.map(s => textPx(s, 13)), 44);
      const w = Math.min(260, Math.max(94, maxLinePx + 30 + (n.shape === "diamond" ? 28 : 0)));
      const h = Math.max(n.shape === "diamond" ? 58 : 44, segs.length * 18 + 24);
      dims.set(n.id, {w, h});
    }

    const pos = new Map();
    const pad = 38, gapMain = 58, gapCross = 32;
    let cursorMain = pad;
    let maxW = 0, maxH = 0;
    sortedLevels.forEach(lv => {
      const items = layers.get(lv);
      const layerMain = Math.max(...items.map(n => horiz ? dims.get(n.id).w : dims.get(n.id).h));
      let cursorCross = pad;
      items.forEach(n => {
        const {w, h} = dims.get(n.id);
        const x = horiz ? cursorMain + (layerMain - w) / 2 : cursorCross;
        const y = horiz ? cursorCross : cursorMain + (layerMain - h) / 2;
        pos.set(n.id, {x, y, w, h, cx: x + w / 2, cy: y + h / 2, node: n});
        cursorCross += (horiz ? h : w) + gapCross;
        maxW = Math.max(maxW, x + w + pad);
        maxH = Math.max(maxH, y + h + pad);
      });
      cursorMain += layerMain + gapMain;
    });

    const parts = [
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${maxW} ${maxH}" width="${maxW}" height="${maxH}" style="background:${pal.bg};border-radius:10px">`,
      `<defs><marker id="m-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 1 L 10 5 L 0 9 z" fill="${pal.edge}"/></marker></defs>`,
    ];
    // Render subgraphs behind edges & nodes
    subgraphs.forEach((sg, idx) => {
      const boxes = [...sg.nodeIds].map(id => pos.get(id)).filter(Boolean);
      if (!boxes.length) return;
      const x1 = Math.min(...boxes.map(b => b.x)) - 16;
      const y1 = Math.min(...boxes.map(b => b.y)) - 28;
      const x2 = Math.max(...boxes.map(b => b.x + b.w)) + 16;
      const y2 = Math.max(...boxes.map(b => b.y + b.h)) + 14;
      const clr = pal.colors[idx % pal.colors.length];
      parts.push(`<rect x="${x1}" y="${y1}" width="${x2 - x1}" height="${y2 - y1}" rx="10" fill="${pal.subFill}" stroke="${clr}" stroke-opacity="0.45" stroke-dasharray="4,3" stroke-width="1.3"/>`);
      if (sg.title) parts.push(`<text x="${x1 + 10}" y="${y1 + 17}" font-size="11.5" font-weight="600" fill="${clr}">${esc(sg.title)}</text>`);
    });
    for (const e of edges) {
      const a = pos.get(e.from), b = pos.get(e.to);
      if (!a || !b) continue;
      const x1 = horiz ? (b.cx >= a.cx ? a.x + a.w : a.x) : a.cx;
      const y1 = horiz ? a.cy : (b.cy >= a.cy ? a.y + a.h : a.y);
      const x2 = horiz ? (b.cx >= a.cx ? b.x : b.x + b.w) : b.cx;
      const y2 = horiz ? b.cy : (b.cy >= a.cy ? b.y : b.y + b.h);
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      const d = horiz ? `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}` : `M ${x1} ${y1} C ${x1} ${my}, ${x2} ${my}, ${x2} ${y2}`;
      const dash = e.dashed ? ` stroke-dasharray="5,4"` : "";
      const sw = e.thick ? "2.6" : "1.8";
      const startMark = e.bi ? ` marker-start="url(#m-arr)"` : "";
      const endMark = e.noArrow ? "" : ` marker-end="url(#m-arr)"`;
      parts.push(`<path d="${d}" fill="none" stroke="${pal.edge}" stroke-width="${sw}"${dash}${startMark}${endMark}/>`);
      if (e.label) {
        const lw = Math.max(32, textPx(e.label, 11.5) + 16);
        parts.push(`<rect x="${mx - lw / 2}" y="${my - 11}" width="${lw}" height="21" rx="5" fill="${pal.edgeLabelBg}" stroke="${pal.edge}" stroke-opacity="0.35"/>`);
        parts.push(`<text x="${mx}" y="${my + 3.5}" text-anchor="middle" font-size="11.5" fill="${pal.nodeText}">${esc(e.label)}</text>`);
      }
    }
    for (const p of pos.values()) {
      const {x, y, w, h, cx, cy, node} = p;
      const st = styles.get(node.id) || {};
      const fill = st.fill || pal.nodeFill;
      const stroke = st.stroke || pal.nodeStroke;
      const txt = st.color || pal.nodeText;
      if (node.shape === "diamond") {
        parts.push(`<polygon points="${cx},${y} ${x + w},${cy} ${cx},${y + h} ${x},${cy}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
      } else if (node.shape === "circle") {
        const r = Math.max(24, Math.min(w, h) / 2);
        parts.push(`<circle cx="${cx}" cy="${cy}" r="${r}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
      } else if (node.shape === "cylinder") {
        parts.push(`<path d="M ${x} ${y + 8} A ${w / 2} 8 0 0 1 ${x + w} ${y + 8} L ${x + w} ${y + h - 8} A ${w / 2} 8 0 0 1 ${x} ${y + h - 8} Z" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
        parts.push(`<ellipse cx="${cx}" cy="${y + 8}" rx="${w / 2}" ry="8" fill="${fill}" stroke="${stroke}" stroke-width="1.4"/>`);
      } else if (node.shape === "hex") {
        const dx = 14;
        parts.push(`<polygon points="${x + dx},${y} ${x + w - dx},${y} ${x + w},${cy} ${x + w - dx},${y + h} ${x + dx},${y + h} ${x},${cy}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
      } else if (node.shape === "sub") {
        parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="4" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
        parts.push(`<line x1="${x + 10}" y1="${y}" x2="${x + 10}" y2="${y + h}" stroke="${stroke}" stroke-width="1.4"/>`);
        parts.push(`<line x1="${x + w - 10}" y1="${y}" x2="${x + w - 10}" y2="${y + h}" stroke="${stroke}" stroke-width="1.4"/>`);
      } else if (node.shape === "para") {
        const skew = 12;
        parts.push(`<polygon points="${x + skew},${y} ${x + w},${y} ${x + w - skew},${y + h} ${x},${y + h}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
      } else {
        const rx = node.shape === "stadium" ? h / 2 : node.shape === "round" ? 12 : 7;
        parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"/>`);
      }
      parts.push(renderMultiLineText(cx, cy, node.label, 13, txt, "500"));
    }
    parts.push("</svg>");
    return parts.join("");
  }


  function renderSequence(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%") && !/^sequenceDiagram/i.test(l));
    const actors = [];
    const actorLabels = new Map();
    const events = [];
    const blockStack = [];
    function ensureActor(id, label) {
      const clean = id.trim();
      if (!actors.includes(clean)) actors.push(clean);
      if (label) actorLabels.set(clean, label.trim());
    }
    for (const line of lines) {
      const pm = /^(?:participant|actor)\s+([A-Za-z0-9_.-]+)(?:\s+as\s+(.+))?$/i.exec(line);
      if (pm) { ensureActor(pm[1], pm[2] || pm[1]); continue; }
      const bm = /^(alt|opt|loop|par|rect|critical|break)\s*(.*)$/i.exec(line);
      if (bm) {
        const blk = {kind: bm[1].toLowerCase(), label: bm[2].trim(), startRow: events.length, elses: [], endRow: -1};
        blockStack.push(blk);
        events.push({type: "blockStart", blk});
        continue;
      }
      const em = /^(else|and)\s*(.*)$/i.exec(line);
      if (em && blockStack.length) {
        blockStack[blockStack.length - 1].elses.push({row: events.length, label: em[2].trim()});
        events.push({type: "blockElse"});
        continue;
      }
      if (/^end$/i.test(line)) {
        const blk = blockStack.pop();
        if (blk) blk.endRow = events.length;
        continue;
      }
      const nm = /^Note\s+(right of|left of|over)\s+([^:]+):\s*(.*)$/i.exec(line);
      if (nm) {
        const targets = nm[2].split(",").map(s => s.trim()).filter(Boolean);
        targets.forEach(t => ensureActor(t));
        events.push({type: "note", pos: nm[1].toLowerCase(), targets, text: nm[3].trim()});
        continue;
      }
      const mm = /^([^:\-]+?)\s*(-->>|->>|-->|->|--x|-x|--\)|-\))\s*[+-]?([^:]+?)\s*:\s*(.*)$/.exec(line);
      if (mm) {
        const from = mm[1].trim(), op = mm[2], to = mm[3].trim(), label = mm[4].trim();
        ensureActor(from); ensureActor(to);
        events.push({type: "msg", from, to, label, dashed: op.startsWith("--")});
      }
    }
    if (!actors.length) return null;

    const actorW = actors.map(a => Math.max(96, textPx(actorLabels.get(a) || a, 13) + 28));
    const colGap = 56;
    const xPos = new Map();
    let curX = 34;
    actors.forEach((a, i) => {
      xPos.set(a, curX + actorW[i] / 2);
      curX += actorW[i] + colGap;
    });
    const width = Math.max(360, curX - colGap + 34);
    const topY = 24, boxH = 38, rowH = 44;
    const bodyStart = topY + boxH + 34;
    const totalRows = Math.max(1, events.length);
    const height = bodyStart + totalRows * rowH + 62;

    const parts = [
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" style="background:${pal.bg};border-radius:10px">`,
      `<defs><marker id="s-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M 0 1 L 10 5 L 0 9 z" fill="${pal.edge}"/></marker></defs>`,
    ];
    // Lifelines & headers/footers
    actors.forEach((a, i) => {
      const cx = xPos.get(a), w = actorW[i];
      const lbl = actorLabels.get(a) || a;
      parts.push(`<line x1="${cx}" y1="${topY + boxH}" x2="${cx}" y2="${height - 46}" stroke="${pal.edge}" stroke-dasharray="4,4" stroke-opacity="0.5"/>`);
      parts.push(`<rect x="${cx - w / 2}" y="${topY}" width="${w}" height="${boxH}" rx="8" fill="${pal.nodeFill}" stroke="${pal.nodeStroke}" stroke-width="1.6"/>`);
      parts.push(`<text x="${cx}" y="${topY + 23}" text-anchor="middle" font-size="12.5" font-weight="600" fill="${pal.nodeText}">${esc(lbl)}</text>`);
      parts.push(`<rect x="${cx - w / 2}" y="${height - 46}" width="${w}" height="32" rx="7" fill="${pal.nodeFill}" stroke="${pal.nodeStroke}" stroke-width="1.2" opacity="0.85"/>`);
      parts.push(`<text x="${cx}" y="${height - 26}" text-anchor="middle" font-size="11.5" fill="${pal.nodeText}">${esc(lbl)}</text>`);
    });
    // Blocks (alt/loop/opt)
    events.forEach(ev => {
      if (ev.type !== "blockStart") return;
      const b = ev.blk;
      const endR = b.endRow > b.startRow ? b.endRow : events.length;
      const y1 = bodyStart + b.startRow * rowH - 16;
      const y2 = bodyStart + endR * rowH - 8;
      parts.push(`<rect x="18" y="${y1}" width="${width - 36}" height="${Math.max(36, y2 - y1)}" rx="7" fill="${pal.subFill}" fill-opacity="0.55" stroke="${pal.accent}" stroke-opacity="0.5" stroke-width="1.2"/>`);
      const badgeW = Math.max(46, textPx(b.kind, 11) + 16);
      parts.push(`<rect x="18" y="${y1}" width="${badgeW}" height="20" rx="4" fill="${pal.accent}"/>`);
      parts.push(`<text x="${18 + badgeW / 2}" y="${y1 + 14}" text-anchor="middle" font-size="11" font-weight="700" fill="#ffffff">${esc(b.kind)}</text>`);
      if (b.label) parts.push(`<text x="${26 + badgeW}" y="${y1 + 14}" font-size="11.5" font-weight="600" fill="${pal.nodeText}">[${esc(b.label)}]</text>`);
      b.elses.forEach(el => {
        const ey = bodyStart + el.row * rowH - 12;
        parts.push(`<line x1="18" y1="${ey}" x2="${width - 18}" y2="${ey}" stroke="${pal.accent}" stroke-dasharray="5,4" stroke-opacity="0.5"/>`);
        if (el.label) parts.push(`<text x="28" y="${ey + 14}" font-size="11" fill="${pal.muted}">[${esc(el.label)}]</text>`);
      });
    });
    // Messages & notes
    events.forEach((ev, idx) => {
      const y = bodyStart + idx * rowH + 8;
      if (ev.type === "note") {
        const xs = ev.targets.map(t => xPos.get(t)).filter(v => v != null);
        const minX = Math.min(...xs), maxX = Math.max(...xs);
        const nw = Math.max(100, textPx(ev.text, 11.5) + 22);
        let nx = (minX + maxX) / 2 - nw / 2;
        if (ev.pos === "right of") nx = maxX + 14;
        else if (ev.pos === "left of") nx = minX - nw - 14;
        nx = Math.max(16, Math.min(width - nw - 16, nx));
        parts.push(`<rect x="${nx}" y="${y - 16}" width="${nw}" height="28" rx="6" fill="${pal.noteFill}" stroke="${pal.noteStroke}" stroke-width="1.2"/>`);
        parts.push(`<text x="${nx + nw / 2}" y="${y + 2}" text-anchor="middle" font-size="11.5" fill="${pal.nodeText}">${esc(ev.text)}</text>`);
      } else if (ev.type === "msg") {
        const x1 = xPos.get(ev.from), x2 = xPos.get(ev.to);
        const dash = ev.dashed ? ` stroke-dasharray="5,4"` : "";
        if (x1 === x2) {
          parts.push(`<path d="M ${x1} ${y - 6} H ${x1 + 42} V ${y + 12} H ${x1 + 2}" fill="none" stroke="${pal.edge}" stroke-width="1.6"${dash} marker-end="url(#s-arr)"/>`);
          parts.push(`<text x="${x1 + 48}" y="${y + 4}" font-size="11.5" fill="${pal.nodeText}">${esc(ev.label)}</text>`);
        } else {
          parts.push(`<line x1="${x1}" y1="${y}" x2="${x2}" y2="${y}" stroke="${pal.edge}" stroke-width="1.6"${dash} marker-end="url(#s-arr)"/>`);
          const lw = textPx(ev.label, 11.5) + 12;
          parts.push(`<rect x="${(x1 + x2) / 2 - lw / 2}" y="${y - 18}" width="${lw}" height="16" rx="4" fill="${pal.edgeLabelBg}" opacity="0.92"/>`);
          parts.push(`<text x="${(x1 + x2) / 2}" y="${y - 6}" text-anchor="middle" font-size="11.5" fill="${pal.nodeText}">${esc(ev.label)}</text>`);
        }
      }
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function renderClassDiagram(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%") && !/^classDiagram/i.test(l));
    const classes = new Map();
    const rels = [];
    let curClass = null;
    function ensure(name) {
      const k = name.trim();
      if (!classes.has(k)) classes.set(k, {name: k, fields: [], methods: []});
      return classes.get(k);
    }
    for (const line of lines) {
      if (line === "}") { curClass = null; continue; }
      const cm = /^class\s+([A-Za-z0-9_.-]+)(?:\s*\{)?$/.exec(line);
      if (cm) { const c = ensure(cm[1]); if (line.endsWith("{")) curClass = c; continue; }
      if (curClass) {
        if (line.includes("(")) curClass.methods.push(line);
        else curClass.fields.push(line);
        continue;
      }
      const rm = /^([A-Za-z0-9_.-]+)\s*(<\|--|--\|>|\*--|--\*|o--|--o|<--|-->|\.\.>|\.\.\|>|--)\s*([A-Za-z0-9_.-]+)(?:\s*:\s*(.*))?$/.exec(line);
      if (rm) {
        ensure(rm[1]); ensure(rm[3]);
        rels.push({from: rm[1], op: rm[2], to: rm[3], label: (rm[4] || "").trim()});
        continue;
      }
      const mm = /^([A-Za-z0-9_.-]+)\s*:\s*(.+)$/.exec(line);
      if (mm) {
        const c = ensure(mm[1]);
        if (mm[2].includes("(")) c.methods.push(mm[2].trim());
        else c.fields.push(mm[2].trim());
      }
    }
    const list = [...classes.values()];
    if (!list.length) return null;
    const cols = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(list.length))));
    const pad = 28, gapX = 54, gapY = 48;
    const boxes = new Map();
    let maxW = 0, maxH = 0;
    const colWidths = new Array(cols).fill(170);
    const rowHeights = new Array(Math.ceil(list.length / cols)).fill(80);
    list.forEach((c, idx) => {
      const allLines = [c.name, ...c.fields, ...c.methods];
      const bw = Math.max(156, ...allLines.map(s => textPx(s, 12) + 26));
      const bh = 34 + Math.max(1, c.fields.length) * 18 + (c.methods.length ? 10 + c.methods.length * 18 : 8);
      c._w = bw; c._h = bh;
      colWidths[idx % cols] = Math.max(colWidths[idx % cols], bw);
      rowHeights[Math.floor(idx / cols)] = Math.max(rowHeights[Math.floor(idx / cols)], bh);
    });
    list.forEach((c, idx) => {
      const col = idx % cols, row = Math.floor(idx / cols);
      let x = pad; for (let k = 0; k < col; k++) x += colWidths[k] + gapX;
      let y = pad; for (let k = 0; k < row; k++) y += rowHeights[k] + gapY;
      boxes.set(c.name, {x, y, w: c._w, h: c._h, cx: x + c._w / 2, cy: y + c._h / 2, c});
      maxW = Math.max(maxW, x + c._w + pad);
      maxH = Math.max(maxH, y + c._h + pad);
    });
    const parts = [
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${maxW} ${maxH}" width="${maxW}" height="${maxH}" style="background:${pal.bg};border-radius:10px">`,
      `<defs><marker id="c-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M 0 1 L 10 5 L 0 9 z" fill="${pal.edge}"/></marker></defs>`,
    ];
    rels.forEach(r => {
      const a = boxes.get(r.from), b = boxes.get(r.to);
      if (!a || !b) return;
      const dash = r.op.includes("..") ? ` stroke-dasharray="5,4"` : "";
      parts.push(`<line x1="${a.cx}" y1="${a.cy}" x2="${b.cx}" y2="${b.cy}" stroke="${pal.edge}" stroke-width="1.6"${dash} marker-end="url(#c-arr)"/>`);
      if (r.label) {
        const mx = (a.cx + b.cx) / 2, my = (a.cy + b.cy) / 2;
        parts.push(`<text x="${mx}" y="${my - 5}" text-anchor="middle" font-size="11" fill="${pal.muted}">${esc(r.label)}</text>`);
      }
    });
    boxes.forEach(({x, y, w, h, c}) => {
      parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="8" fill="${pal.nodeFill}" stroke="${pal.nodeStroke}" stroke-width="1.6"/>`);
      parts.push(`<rect x="${x}" y="${y}" width="${w}" height="28" rx="8" fill="${pal.nodeStroke}" fill-opacity="0.16"/>`);
      parts.push(`<text x="${x + w / 2}" y="${y + 18}" text-anchor="middle" font-size="12.5" font-weight="700" fill="${pal.nodeText}">${esc(c.name)}</text>`);
      let cy = y + 44;
      c.fields.forEach(f => { parts.push(`<text x="${x + 10}" y="${cy}" font-size="11.5" font-family="monospace" fill="${pal.nodeText}">${esc(f)}</text>`); cy += 18; });
      if (c.methods.length) {
        if (c.fields.length) parts.push(`<line x1="${x}" y1="${cy - 10}" x2="${x + w}" y2="${cy - 10}" stroke="${pal.nodeStroke}" stroke-opacity="0.35"/>`);
        c.methods.forEach(m => { parts.push(`<text x="${x + 10}" y="${cy}" font-size="11.5" font-family="monospace" fill="${pal.accent}">${esc(m)}</text>`); cy += 18; });
      }
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function renderErDiagram(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%") && !/^erDiagram/i.test(l));
    const entities = new Map();
    const rels = [];
    let cur = null;
    function ensure(name) {
      const k = name.trim();
      if (!entities.has(k)) entities.set(k, {name: k, attrs: []});
      return entities.get(k);
    }
    for (const line of lines) {
      if (line === "}") { cur = null; continue; }
      const em = /^([A-Za-z0-9_-]+)\s*\{$/.exec(line);
      if (em) { cur = ensure(em[1]); continue; }
      if (cur) { cur.attrs.push(line); continue; }
      const rm = /^([A-Za-z0-9_-]+)\s+([|o}][|o]--[|o][|o{]|[|o}][|o]\.\.[|o][|o{]|[|o}\.{-]+)\s+([A-Za-z0-9_-]+)(?:\s*:\s*(.*))?$/.exec(line);
      if (rm) {
        ensure(rm[1]); ensure(rm[3]);
        rels.push({from: rm[1], card: rm[2], to: rm[3], label: (rm[4] || "").replace(/^"|"$/g, "").trim()});
      }
    }
    const list = [...entities.values()];
    if (!list.length) return null;
    const cols = Math.min(3, Math.max(1, Math.ceil(Math.sqrt(list.length))));
    const pad = 30, gapX = 72, gapY = 54;
    const boxes = new Map();
    const colW = new Array(cols).fill(160), rowH = new Array(Math.ceil(list.length / cols)).fill(64);
    list.forEach((e, i) => {
      e._w = Math.max(148, textPx(e.name, 13) + 28, ...e.attrs.map(a => textPx(a, 11.5) + 26));
      e._h = 32 + Math.max(1, e.attrs.length) * 18 + 8;
      colW[i % cols] = Math.max(colW[i % cols], e._w);
      rowH[Math.floor(i / cols)] = Math.max(rowH[Math.floor(i / cols)], e._h);
    });
    let maxW = 0, maxH = 0;
    list.forEach((e, i) => {
      const c = i % cols, r = Math.floor(i / cols);
      let x = pad; for (let k = 0; k < c; k++) x += colW[k] + gapX;
      let y = pad; for (let k = 0; k < r; k++) y += rowH[k] + gapY;
      boxes.set(e.name, {x, y, w: e._w, h: e._h, cx: x + e._w / 2, cy: y + e._h / 2, e});
      maxW = Math.max(maxW, x + e._w + pad);
      maxH = Math.max(maxH, y + e._h + pad);
    });
    const parts = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${maxW} ${maxH}" width="${maxW}" height="${maxH}" style="background:${pal.bg};border-radius:10px">`];
    rels.forEach(r => {
      const a = boxes.get(r.from), b = boxes.get(r.to);
      if (!a || !b) return;
      parts.push(`<line x1="${a.cx}" y1="${a.cy}" x2="${b.cx}" y2="${b.cy}" stroke="${pal.edge}" stroke-width="1.6"/>`);
      const mx = (a.cx + b.cx) / 2, my = (a.cy + b.cy) / 2;
      const lbl = r.label || r.card;
      const lw = textPx(lbl, 11) + 14;
      parts.push(`<rect x="${mx - lw / 2}" y="${my - 10}" width="${lw}" height="19" rx="5" fill="${pal.edgeLabelBg}" stroke="${pal.edge}" stroke-opacity="0.35"/>`);
      parts.push(`<text x="${mx}" y="${my + 3}" text-anchor="middle" font-size="11" fill="${pal.nodeText}">${esc(lbl)}</text>`);
    });
    boxes.forEach(({x, y, w, h, e}) => {
      parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7" fill="${pal.nodeFill}" stroke="${pal.nodeStroke}" stroke-width="1.6"/>`);
      parts.push(`<rect x="${x}" y="${y}" width="${w}" height="28" rx="7" fill="${pal.nodeStroke}" fill-opacity="0.18"/>`);
      parts.push(`<text x="${x + w / 2}" y="${y + 18}" text-anchor="middle" font-size="12.5" font-weight="700" fill="${pal.nodeText}">${esc(e.name)}</text>`);
      e.attrs.forEach((a, idx) => {
        parts.push(`<text x="${x + 10}" y="${y + 44 + idx * 18}" font-size="11.5" font-family="monospace" fill="${pal.nodeText}">${esc(a)}</text>`);
      });
    });
    parts.push("</svg>");
    return parts.join("");
  }


  function renderGantt(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%") && !/^gantt/i.test(l));
    let title = "项目进度甘特图";
    let curSection = "阶段";
    const tasks = [];
    let autoStart = 0;
    const endById = new Map();
    for (const line of lines) {
      if (/^title\s+/i.test(line)) { title = line.replace(/^title\s+/i, "").trim(); continue; }
      if (/^(?:dateFormat|axisFormat|excludes|todayMarker|tickInterval)\b/i.test(line)) continue;
      if (/^section\s+/i.test(line)) { curSection = line.replace(/^section\s+/i, "").trim(); continue; }
      const cm = /^([^:]+):\s*(.+)$/.exec(line);
      if (!cm) continue;
      const name = cm[1].trim();
      const parts = cm[2].split(",").map(s => s.trim()).filter(Boolean);
      let id = "", status = "", start = autoStart, dur = 3;
      for (const p of parts) {
        if (/^(done|active|crit|milestone)$/i.test(p)) { status = p.toLowerCase(); continue; }
        const am = /^after\s+([A-Za-z0-9_-]+)$/i.exec(p);
        if (am && endById.has(am[1])) { start = endById.get(am[1]); continue; }
        const dm = /^(\d+)\s*([dwmh])$/i.exec(p);
        if (dm) { dur = Math.max(1, Number(dm[1]) * (dm[2].toLowerCase() === "w" ? 7 : 1)); continue; }
        if (/^\d{4}-\d{2}-\d{2}$/.test(p)) { continue; }
        if (!id && /^[A-Za-z][A-Za-z0-9_-]*$/.test(p)) id = p;
      }
      if (status === "milestone") dur = 0;
      const end = start + dur;
      if (id) endById.set(id, end);
      autoStart = Math.max(autoStart, end);
      tasks.push({section: curSection, name, status, start, dur: Math.max(0.5, dur), end});
    }
    if (!tasks.length) return null;
    const totalUnits = Math.max(10, ...tasks.map(t => t.end));
    const labelW = Math.min(200, Math.max(110, ...tasks.map(t => textPx(t.name, 11.5) + 22)));
    const W = Math.max(520, labelW + 340);
    const barArea = W - labelW - 36;
    const rowH = 30, topY = 46;
    const H = topY + tasks.length * rowH + 28;
    const sections = [...new Set(tasks.map(t => t.section))];
    const parts = [
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" style="background:${pal.bg};border-radius:10px">`,
      `<text x="18" y="28" font-size="14" font-weight="700" fill="${pal.nodeText}">${esc(title)}</text>`,
    ];
    for (let g = 0; g <= 5; g++) {
      const gx = labelW + (barArea * g) / 5;
      parts.push(`<line x1="${gx}" y1="${topY - 6}" x2="${gx}" y2="${H - 20}" stroke="${pal.subStroke}" stroke-dasharray="3,3"/>`);
    }
    tasks.forEach((t, idx) => {
      const y = topY + idx * rowH;
      const clr = t.status === "crit" ? "#ef4444" : pal.colors[sections.indexOf(t.section) % pal.colors.length];
      if (idx % 2 === 1) parts.push(`<rect x="10" y="${y}" width="${W - 20}" height="${rowH}" rx="4" fill="${pal.subFill}" opacity="0.55"/>`);
      parts.push(`<text x="16" y="${y + 19}" font-size="11.5" fill="${pal.nodeText}">${esc(t.name.slice(0, 22))}</text>`);
      const bx = labelW + (t.start / totalUnits) * barArea;
      const bw = Math.max(12, (t.dur / totalUnits) * barArea);
      if (t.status === "milestone") {
        const my = y + rowH / 2;
        parts.push(`<polygon points="${bx},${my - 7} ${bx + 7},${my} ${bx},${my + 7} ${bx - 7},${my}" fill="${clr}"/>`);
      } else {
        const op = t.status === "done" ? "0.55" : "0.9";
        parts.push(`<rect x="${bx.toFixed(1)}" y="${y + 6}" width="${bw.toFixed(1)}" height="${rowH - 12}" rx="5" fill="${clr}" fill-opacity="${op}"/>`);
      }
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function renderMindmap(src, dark) {
    const pal = themePalette(dark);
    const rawLines = src.split(/\r?\n/).filter(l => l.trim() && !l.trim().startsWith("%%") && !/^mindmap\b/i.test(l.trim()));
    if (!rawLines.length) return null;
    const nodes = [];
    const stack = [];
    for (const raw of rawLines) {
      const indent = (raw.match(/^(\s*)/)?.[1] || "").replace(/\t/g, "  ").length;
      const label = raw.trim().replace(/^(?:\(\(|\(\[|\{\{|\(|\[|\{)|(?:\)\)|\]\)|\}\}|\)|\]|\})$/g, "").trim();
      const node = {label, indent, children: [], parent: null, depth: 0};
      while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
      if (stack.length) {
        const p = stack[stack.length - 1];
        node.parent = p; node.depth = p.depth + 1;
        p.children.push(node);
      }
      stack.push(node);
      nodes.push(node);
    }
    const root = nodes.find(n => !n.parent) || nodes[0];
    let leafCursor = 0;
    function layout(node, branchIdx) {
      node.branchIdx = node.depth === 1 ? branchIdx : (node.parent ? node.parent.branchIdx : 0);
      node.w = Math.max(88, textPx(node.label, node.depth === 0 ? 14 : 12.5) + 26);
      node.h = node.depth === 0 ? 38 : 30;
      if (!node.children.length) {
        node.y = 32 + leafCursor * 44;
        leafCursor++;
      } else {
        node.children.forEach((c, idx) => layout(c, node.depth === 0 ? idx : node.branchIdx));
        const first = node.children[0].y, last = node.children[node.children.length - 1].y;
        node.y = (first + last) / 2;
      }
    }
    layout(root, 0);
    const depthX = new Map();
    function calcX(node, x) {
      node.x = x;
      depthX.set(node.depth, Math.max(depthX.get(node.depth) || 0, x + node.w));
      node.children.forEach(c => calcX(c, x + node.w + 46));
    }
    calcX(root, 26);
    let maxW = 340, maxH = 120;
    nodes.forEach(n => {
      maxW = Math.max(maxW, (n.x || 0) + (n.w || 100) + 30);
      maxH = Math.max(maxH, (n.y || 0) + (n.h || 32) + 30);
    });
    const parts = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${maxW} ${maxH}" width="${maxW}" height="${maxH}" style="background:${pal.bg};border-radius:10px">`];
    nodes.forEach(n => {
      if (!n.parent) return;
      const p = n.parent;
      const clr = pal.colors[n.branchIdx % pal.colors.length];
      const x1 = p.x + p.w, y1 = p.y + p.h / 2, x2 = n.x, y2 = n.y + n.h / 2;
      const mx = (x1 + x2) / 2;
      parts.push(`<path d="M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}" fill="none" stroke="${clr}" stroke-width="1.8" stroke-opacity="0.75"/>`);
    });
    nodes.forEach(n => {
      const clr = n.depth === 0 ? pal.accent : pal.colors[n.branchIdx % pal.colors.length];
      const fill = n.depth === 0 ? clr : pal.nodeFill;
      const txt = n.depth === 0 ? "#ffffff" : pal.nodeText;
      parts.push(`<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" rx="${n.depth === 0 ? 19 : 9}" fill="${fill}" stroke="${clr}" stroke-width="${n.depth === 0 ? 0 : 1.5}"/>`);
      parts.push(`<text x="${n.x + n.w / 2}" y="${n.y + n.h / 2 + 4.5}" text-anchor="middle" font-size="${n.depth === 0 ? 13.5 : 12}" font-weight="${n.depth === 0 ? "700" : "500"}" fill="${txt}">${esc(n.label)}</text>`);
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function renderPie(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%") && !/^pie\b/i.test(l));
    let title = "";
    const items = [];
    for (const line of lines) {
      if (/^title\s+/i.test(line)) { title = line.replace(/^title\s+/i, "").trim(); continue; }
      const m = /^"?([^":]+)"?\s*:\s*([0-9.]+)/.exec(line);
      if (m) items.push({label: m[1].trim(), value: parseFloat(m[2])});
    }
    const total = items.reduce((s, x) => s + x.value, 0);
    if (!items.length || total <= 0) return null;
    const cx = 135, cy = title ? 155 : 132, r = 92;
    const width = 460, height = Math.max(268, (title ? 72 : 42) + items.length * 24 + 24);
    const parts = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" style="background:${pal.bg};border-radius:10px">`];
    if (title) parts.push(`<text x="${width / 2}" y="28" text-anchor="middle" font-size="14" font-weight="600" fill="${pal.nodeText}">${esc(title)}</text>`);
    let angle = -Math.PI / 2;
    items.forEach((it, idx) => {
      const slice = (it.value / total) * Math.PI * 2;
      const x1 = cx + r * Math.cos(angle), y1 = cy + r * Math.sin(angle);
      const x2 = cx + r * Math.cos(angle + slice), y2 = cy + r * Math.sin(angle + slice);
      const large = slice > Math.PI ? 1 : 0;
      const color = pal.colors[idx % pal.colors.length];
      parts.push(`<path d="M ${cx} ${cy} L ${x1.toFixed(1)} ${y1.toFixed(1)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} Z" fill="${color}" stroke="${pal.bg}" stroke-width="1.6"/>`);
      const ly = (title ? 68 : 44) + idx * 24;
      const pct = ((it.value / total) * 100).toFixed(1);
      parts.push(`<rect x="258" y="${ly - 10}" width="12" height="12" rx="3" fill="${color}"/>`);
      parts.push(`<text x="276" y="${ly}" font-size="12" fill="${pal.nodeText}">${esc(it.label)} (${pct}%)</text>`);
      angle += slice;
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function renderXyChart(src, dark) {
    const pal = themePalette(dark);
    const lines = src.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith("%%"));
    let title = "";
    let xLabels = [];
    const series = [];
    for (const line of lines) {
      if (/^title\s+/i.test(line)) title = line.replace(/^title\s+/i, "").replace(/^"|"$/g, "").trim();
      else if (/^x-axis\s+\[/i.test(line)) {
        const m = /\[([^\]]+)\]/.exec(line);
        if (m) xLabels = m[1].split(",").map(s => s.trim().replace(/^"|"$/g, ""));
      } else if (/^(bar|line)\s+\[/i.test(line)) {
        const m = /^(bar|line)\s+\[([^\]]+)\]/i.exec(line);
        if (m) series.push({type: m[1].toLowerCase(), values: m[2].split(",").map(v => parseFloat(v.trim())).filter(Number.isFinite)});
      }
    }
    if (!series.length) return null;
    const allV = series.flatMap(s => s.values);
    const maxV = Math.max(1, ...allV);
    const W = 480, H = 250, padL = 44, padR = 18, padT = title ? 40 : 20, padB = 36;
    const plotW = W - padL - padR, plotH = H - padT - padB;
    const count = Math.max(xLabels.length, ...series.map(s => s.values.length), 1);
    const parts = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" style="background:${pal.bg};border-radius:10px">`];
    if (title) parts.push(`<text x="${W / 2}" y="24" text-anchor="middle" font-size="14" font-weight="600" fill="${pal.nodeText}">${esc(title)}</text>`);
    for (let t = 0; t <= 4; t++) {
      const y = padT + plotH - (plotH * t) / 4;
      parts.push(`<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" stroke="${pal.subStroke}" stroke-dasharray="${t === 0 ? "" : "3,3"}"/>`);
      parts.push(`<text x="${padL - 6}" y="${y + 4}" text-anchor="end" font-size="10.5" fill="${pal.muted}">${Math.round((maxV * t) / 4)}</text>`);
    }
    for (let i = 0; i < count; i++) {
      const x = padL + (i + 0.5) * (plotW / count);
      parts.push(`<text x="${x}" y="${H - 12}" text-anchor="middle" font-size="11" fill="${pal.muted}">${esc(xLabels[i] || `#${i + 1}`)}</text>`);
    }
    series.forEach((s, si) => {
      const clr = pal.colors[si % pal.colors.length];
      if (s.type === "bar") {
        const bw = Math.max(12, (plotW / count) * 0.56);
        s.values.forEach((v, i) => {
          const x = padL + (i + 0.5) * (plotW / count) - bw / 2;
          const bh = (v / maxV) * plotH;
          parts.push(`<rect x="${x.toFixed(1)}" y="${(padT + plotH - bh).toFixed(1)}" width="${bw.toFixed(1)}" height="${bh.toFixed(1)}" rx="4" fill="${clr}" fill-opacity="0.85"/>`);
        });
      } else {
        const pts = s.values.map((v, i) => `${(padL + (i + 0.5) * (plotW / count)).toFixed(1)},${(padT + plotH - (v / maxV) * plotH).toFixed(1)}`);
        parts.push(`<polyline points="${pts.join(" ")}" fill="none" stroke="${clr}" stroke-width="2.2"/>`);
      }
    });
    parts.push("</svg>");
    return parts.join("");
  }

  function render(source, dark) {
    const src = String(source || "").trim();
    const first = (src.split(/\r?\n/).find(l => l.trim() && !l.trim().startsWith("%%")) || "").trim().toLowerCase();
    if (first.startsWith("sequencediagram")) return renderSequence(src, dark);
    if (first.startsWith("classdiagram")) return renderClassDiagram(src, dark);
    if (first.startsWith("erdiagram")) return renderErDiagram(src, dark);
    if (first.startsWith("gantt")) return renderGantt(src, dark);
    if (first.startsWith("mindmap")) return renderMindmap(src, dark);
    if (first.startsWith("pie")) return renderPie(src, dark);
    if (first.startsWith("xychart")) return renderXyChart(src, dark);
    if (first.startsWith("statediagram")) {
      const normalized = src
        .replace(/^stateDiagram(?:-v2)?/i, "flowchart TD")
        .replace(/\[\*\]/g, "START_END((●))");
      return renderFlowchart(normalized, dark);
    }
    return renderFlowchart(src, dark);
  }

  global.SolbraidMermaid = {render};
})(window);
