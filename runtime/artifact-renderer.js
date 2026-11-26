// Solbraid Offline Artifact Studio Controller
// Supports: HTML (auto-height + live console), Real Offline React/JSX + Hooks, SVG, Mermaid, Chart, Math/LaTeX, Markdown, CSV
(function (global) {
  "use strict";

  const stage = document.getElementById("stage");
  let lastPayload = null;
  let heightTimer = null;

  const PALETTE = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#ec4899", "#84cc16"];

  function esc(s) {
    return String(s || "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function emitConsole(level, message) {
    try {
      if (global.SolbraidStudioBridge && typeof global.SolbraidStudioBridge.onConsole === "function") {
        global.SolbraidStudioBridge.onConsole(String(level || "log"), String(message || "").slice(0, 600));
      }
    } catch {}
  }

  function setTheme(dark, inlineMode, viewportMode, palette) {
    document.documentElement.classList.toggle("dark", Boolean(dark));
    document.body.classList.toggle("dark", Boolean(dark));
    document.body.classList.toggle("inline", Boolean(inlineMode));
    stage.classList.remove("vp-mobile", "vp-desktop");
    if (!inlineMode && viewportMode === "mobile") stage.classList.add("vp-mobile");
    else if (!inlineMode && viewportMode === "desktop") stage.classList.add("vp-desktop");
    if (!inlineMode && palette && typeof palette === "object") {
      const rootStyle = document.documentElement.style;
      if (palette.bg) rootStyle.setProperty("--bg", String(palette.bg));
      if (palette.paper) rootStyle.setProperty("--paper", String(palette.paper));
      if (palette.surface) rootStyle.setProperty("--surface", String(palette.surface));
      if (palette.ink) rootStyle.setProperty("--ink", String(palette.ink));
      if (palette.muted) rootStyle.setProperty("--muted", String(palette.muted));
      if (palette.border) rootStyle.setProperty("--border", String(palette.border));
      if (palette.accent) rootStyle.setProperty("--accent", String(palette.accent));
    }
  }

  function reportHeight(explicitPx) {
    if (!global.SolbraidInlineBridge || typeof global.SolbraidInlineBridge.onHeight !== "function") return;
    clearTimeout(heightTimer);
    heightTimer = setTimeout(() => {
      try {
        const h = explicitPx
          ? Math.ceil(Number(explicitPx))
          : Math.ceil(Math.max(stage.scrollHeight, stage.getBoundingClientRect().height, document.body.scrollHeight));
        if (h > 0) global.SolbraidInlineBridge.onHeight(h);
      } catch {}
    }, 30);
  }

  window.addEventListener("message", ev => {
    const d = ev && ev.data;
    if (!d || typeof d !== "object") return;
    if (d.type === "solbraid:height" && Number.isFinite(d.height) && d.height > 20) {
      const iframe = stage.querySelector("iframe.sandbox-frame");
      if (iframe) {
        if (document.body.classList.contains("inline")) {
          iframe.style.height = Math.min(480, Math.max(120, Math.ceil(d.height) + 8)) + "px";
        } else {
          const minH = Math.max(window.innerHeight || 0, document.documentElement.clientHeight || 0, 560);
          iframe.style.height = Math.max(minH, Math.ceil(d.height) + 20) + "px";
        }
      }
      reportHeight(d.height + 12);
    } else if (d.type === "solbraid:console") {
      emitConsole(d.level, d.message);
    } else if (d.type === "solbraid:plugin_rpc" && d.reqId && d.method) {
      try {
        if (global.SolbraidStudioBridge && typeof global.SolbraidStudioBridge.onPluginRpc === "function") {
          global.SolbraidStudioBridge.onPluginRpc(String(d.reqId), String(d.method), JSON.stringify(d.params || {}));
        } else {
          const iframe = stage.querySelector("iframe.sandbox-frame");
          iframe?.contentWindow?.postMessage({type: "solbraid:plugin_rpc_reply", reqId: d.reqId, ok: false, payload: "当前宿主未启用插件 RPC 桥"}, "*");
        }
      } catch (err) {
        const iframe = stage.querySelector("iframe.sandbox-frame");
        iframe?.contentWindow?.postMessage({type: "solbraid:plugin_rpc_reply", reqId: d.reqId, ok: false, payload: String(err && err.message || err)}, "*");
      }
    }
  });

  function sandboxBridgeScript(pluginId) {
    const pluginShim = pluginId
      ? `var _rpcSeq=0,_rpcWait={};
      window.addEventListener("message",function(ev){var d=ev&&ev.data;if(!d||d.type!=="solbraid:plugin_rpc_reply"||!d.reqId)return;var w=_rpcWait[d.reqId];if(!w)return;delete _rpcWait[d.reqId];if(d.ok)w.resolve(d.payload);else w.reject(new Error(typeof d.payload==="string"?d.payload:JSON.stringify(d.payload||"RPC 失败")));});
      window.SolbraidPlugin=Object.freeze({pluginId:${JSON.stringify(String(pluginId))},invoke:function(method,params){return new Promise(function(resolve,reject){var id="rpc-"+(++_rpcSeq)+"-"+Date.now();_rpcWait[id]={resolve:resolve,reject:reject};post({type:"solbraid:plugin_rpc",reqId:id,method:String(method||""),params:params||{}});setTimeout(function(){if(_rpcWait[id]){delete _rpcWait[id];reject(new Error("插件 RPC 超时"));}},16000);});}});`
      : "";
    return `<script>(function(){
      function post(o){try{parent.postMessage(o,"*");}catch(e){}}
      function sendH(){var h=Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);if(h>0)post({type:"solbraid:height",height:h});}
      ${pluginShim}
      ["log","warn","error","info"].forEach(function(lv){
        var orig=console[lv];
        console[lv]=function(){
          try{var msg=[].slice.call(arguments).map(function(a){return typeof a==="object"?JSON.stringify(a):String(a);}).join(" ");post({type:"solbraid:console",level:lv,message:msg});}catch(e){}
          if(orig)return orig.apply(console,arguments);
        };
      });
      window.addEventListener("error",function(e){post({type:"solbraid:console",level:"error",message:(e.message||"Runtime Error")+(e.lineno?" (line "+e.lineno+")":"")});});
      window.addEventListener("unhandledrejection",function(e){post({type:"solbraid:console",level:"error",message:"Promise rejection: "+(e.reason&&e.reason.message?e.reason.message:String(e.reason))});});
      window.addEventListener("load",function(){sendH();setTimeout(sendH,120);});
      if(typeof ResizeObserver!=="undefined")new ResizeObserver(sendH).observe(document.documentElement);
      setTimeout(sendH,60);
    })();<\/script>`;
  }

  function renderHtml(content, dark, inlineMode, pluginId) {
    stage.className = stage.className.replace(/\bpad\b/g, "").trim();
    const iframe = document.createElement("iframe");
    iframe.className = "sandbox-frame";
    iframe.setAttribute("sandbox", "allow-scripts allow-forms allow-modals");
    const themeVars = dark
      ? '<style>:root{--bg:#11141c;--surface:#191e2b;--ink:#eceff4;--muted:#94a3b8;--border:#2b3245;--accent:#60a5fa;}html{color-scheme:dark;}</style>'
      : '<style>:root{--bg:#ffffff;--surface:#f7f8fa;--ink:#14171f;--muted:#64748b;--border:#dfe3eb;--accent:#2563eb;}</style>';
    const bridge = sandboxBridgeScript(pluginId);
    let html;
    if (/<html[\s>]/i.test(content)) {
      html = /<\/head>/i.test(content) ? content.replace(/<\/head>/i, themeVars + bridge + "</head>") : bridge + content;
    } else {
      html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${themeVars}${bridge}<style>*{box-sizing:border-box;}body{margin:${inlineMode ? "10px" : "16px"};background:var(--bg);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;line-height:1.55;}</style></head><body>${content}</body></html>`;
    }
    iframe.srcdoc = html;
    iframe.onload = () => reportHeight();
    stage.innerHTML = "";
    stage.appendChild(iframe);
  }

  const DANGEROUS_SVG_TAGS = new Set(["script", "foreignobject", "iframe", "object", "embed", "applet", "meta", "link", "base", "form"]);

  /** 严格白名单净化 SVG，彻底阻断 onload/onerror/foreignObject/javascript: XSS。 */
  function sanitizeSvgMarkup(raw) {
    const input = String(raw || "");
    if (typeof DOMParser !== "undefined") {
      try {
        const doc = new DOMParser().parseFromString(`<div>${input}</div>`, "text/html");
        const container = doc.body.firstElementChild;
        if (container) {
          const walk = (node) => {
            for (const child of [...node.childNodes]) {
              if (child.nodeType !== 1) continue;
              const tag = child.tagName.toLowerCase();
              if (DANGEROUS_SVG_TAGS.has(tag)) { child.remove(); continue; }
              if (tag === "style") {
                const css = child.textContent || "";
                if (/@import|expression\s*\(|javascript:|url\s*\(\s*["']?\s*javascript:/i.test(css)) {
                  child.remove();
                  continue;
                }
              }
              for (const attr of [...child.attributes]) {
                const name = attr.name.toLowerCase();
                const val = String(attr.value || "").trim();
                if (name.startsWith("on") || name === "srcdoc") {
                  child.removeAttribute(attr.name);
                  continue;
                }
                if (name === "href" || name === "xlink:href" || name === "src" || name === "action" || name === "formaction") {
                  if (/^\s*(?:javascript|vbscript|data\s*:\s*text\/html)/i.test(val)) {
                    child.removeAttribute(attr.name);
                  }
                }
              }
              walk(child);
            }
          };
          walk(container);
          return container.innerHTML;
        }
      } catch {}
    }
    return input
      .replace(/<(script|foreignObject|iframe|object|embed|applet|meta|link|base)[\s\S]*?<\/\1>/gi, "")
      .replace(/<(script|foreignObject|iframe|object|embed|applet|meta|link|base)\b[^>]*\/?>/gi, "")
      .replace(/\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, "")
      .replace(/\b(?:href|xlink:href|src)\s*=\s*(?:"\s*(?:javascript|vbscript|data:text\/html)[^"]*"|'\s*(?:javascript|vbscript|data:text\/html)[^']*')/gi, "");
  }

  function safeHref(url) {
    const u = String(url || "").trim();
    if (/^(?:https?:\/\/|mailto:|#)/i.test(u)) return u;
    return "";
  }

  function renderSvg(content) {
    stage.classList.add("pad");
    const clean = sanitizeSvgMarkup(content);
    stage.innerHTML = `<div class="svg-wrap">${clean}</div>`;
  }

  function renderMermaid(content, dark) {
    stage.classList.add("pad");
    const svg = global.SolbraidMermaid.render(content, dark);
    stage.innerHTML = `<div class="svg-wrap">${svg}</div>`;
  }

  function renderLatex(content, inlineMode) {
    stage.classList.add("pad");
    const raw = String(content || "");
    const hasDoc = /\\(documentclass|begin\{document\}|section|subsection|begin\{tabular\}|begin\{theorem\}|begin\{itemize\}|begin\{enumerate\})/.test(raw);
    stage.innerHTML = (inlineMode && !hasDoc)
      ? global.SolbraidLatex.renderMathBlock(raw)
      : global.SolbraidLatex.renderDocument(raw);
  }

  function renderMath(content) {
    stage.classList.add("pad");
    stage.innerHTML = global.SolbraidLatex.renderMathBlock(content);
  }

  // ── Real Zero-CDN Offline JSX Compiler + Mini React Hooks Runtime ──
  function transpileJsx(code) {
    // Strip ES imports/exports & simple TS annotations so JSX components execute cleanly in-browser
    let src = String(code || "")
      .replace(/^\s*import\s+[\s\S]*?from\s+["'][^"']+["'];?\s*$/gm, "")
      .replace(/^\s*import\s+["'][^"']+["'];?\s*$/gm, "")
      .replace(/export\s+default\s+function\s+([A-Za-z0-9_]+)/g, "const __DefaultExport__ = function $1")
      .replace(/export\s+default\s+/g, "const __DefaultExport__ = ")
      .replace(/\bexport\s+(?=(?:const|let|var|function|class)\b)/g, "")
      .replace(/interface\s+\w+\s*\{[\s\S]*?\}\s*/g, "")
      .replace(/type\s+\w+\s*=\s*[^;]+;/g, "");

    // Recursive-descent JSX element transformer -> React.createElement(...)
    let i = 0, n = src.length;
    function skipStr(q) {
      let s = src[i++];
      while (i < n) {
        if (src[i] === "\\" && i + 1 < n) { s += src[i] + src[i + 1]; i += 2; continue; }
        s += src[i];
        if (src[i++] === q) break;
      }
      return s;
    }
    function skipBalanced(open, close) {
      let depth = 1, out = "";
      i++;
      while (i < n && depth > 0) {
        const ch = src[i];
        if (ch === '"' || ch === "'" || ch === "`") { out += skipStr(ch); continue; }
        if (ch === "/" && src[i + 1] === "/") { while (i < n && src[i] !== "\n") out += src[i++]; continue; }
        if (ch === "/" && src[i + 1] === "*") { out += "/*"; i += 2; while (i < n && !(src[i] === "*" && src[i + 1] === "/")) out += src[i++]; if (i < n) { out += "*/"; i += 2; } continue; }
        if (ch === open) { depth++; out += src[i++]; }
        else if (ch === close) { depth--; if (depth > 0) out += src[i]; i++; }
        else if (ch === "<" && isJsxStart(i)) { out += parseJsxElement(); }
        else { out += src[i++]; }
      }
      return out;
    }
    function isJsxStart(pos) {
      if (src[pos] !== "<") return false;
      const next = src[pos + 1];
      if (!next) return false;
      if (next === ">") return true;
      if (!/[A-Za-z_]/.test(next)) return false;
      let k = pos - 1;
      while (k >= 0 && /\s/.test(src[k])) k--;
      const prev = k >= 0 ? src[k] : "";
      if (prev === "" || "=(,:?[&|!{;>".includes(prev)) return true;
      const word = src.slice(Math.max(0, k - 7), k + 1);
      return /\b(return|case|yield|await|else|=>)$/.test(word);
    }
    function parseJsxElement() {
      i++; // '<'
      if (src[i] === ">") {
        i++;
        const children = parseJsxChildren("");
        return `React.createElement(React.Fragment,null${children.length ? "," + children.join(",") : ""})`;
      }
      let tag = "";
      while (i < n && /[A-Za-z0-9_.$:-]/.test(src[i])) tag += src[i++];
      const tagExpr = /^[a-z]/.test(tag) ? JSON.stringify(tag) : tag;
      const props = [];
      while (i < n) {
        while (i < n && /\s/.test(src[i])) i++;
        if (src[i] === "/" && src[i + 1] === ">") { i += 2; return `React.createElement(${tagExpr},${props.length ? "{" + props.join(",") + "}" : "null"})`; }
        if (src[i] === ">") { i++; break; }
        if (src[i] === "{" && src.slice(i, i + 4) === "{...") {
          i += 3;
          const spread = skipBalanced("{", "}");
          props.push("..." + spread);
          continue;
        }
        let attr = "";
        while (i < n && /[A-Za-z0-9_:-]/.test(src[i])) attr += src[i++];
        if (!attr) { i++; continue; }
        while (i < n && /\s/.test(src[i])) i++;
        if (src[i] === "=") {
          i++;
          while (i < n && /\s/.test(src[i])) i++;
          if (src[i] === '"' || src[i] === "'") props.push(`${JSON.stringify(attr)}:${skipStr(src[i])}`);
          else if (src[i] === "{") props.push(`${JSON.stringify(attr)}:(${skipBalanced("{", "}")})`);
        } else {
          props.push(`${JSON.stringify(attr)}:true`);
        }
      }
      const children = parseJsxChildren(tag);
      return `React.createElement(${tagExpr},${props.length ? "{" + props.join(",") + "}" : "null"}${children.length ? "," + children.join(",") : ""})`;
    }
    function parseJsxChildren(closeTag) {
      const children = [];
      let textBuf = "";
      const flushText = () => {
        const cleaned = textBuf.replace(/\r?\n\s*/g, " ");
        if (cleaned.trim()) children.push(JSON.stringify(cleaned));
        textBuf = "";
      };
      while (i < n) {
        if (src[i] === "<" && src[i + 1] === "/") {
          flushText();
          i += 2;
          while (i < n && src[i] !== ">") i++;
          if (src[i] === ">") i++;
          break;
        }
        if (src[i] === "<" && (src[i + 1] === ">" || /[A-Za-z_]/.test(src[i + 1] || ""))) {
          flushText();
          children.push(parseJsxElement());
          continue;
        }
        if (src[i] === "{") {
          flushText();
          const expr = skipBalanced("{", "}").trim();
          if (expr && !/^\/\*[\s\S]*\*\/$/.test(expr)) children.push(`(${expr})`);
          continue;
        }
        textBuf += src[i++];
      }
      return children;
    }
    let out = "";
    while (i < n) {
      const ch = src[i];
      if (ch === '"' || ch === "'" || ch === "`") { out += skipStr(ch); continue; }
      if (ch === "/" && src[i + 1] === "/") { while (i < n && src[i] !== "\n") out += src[i++]; continue; }
      if (ch === "/" && src[i + 1] === "*") { out += "/*"; i += 2; while (i < n && !(src[i] === "*" && src[i + 1] === "/")) out += src[i++]; if (i < n) { out += "*/"; i += 2; } continue; }
      if (ch === "<" && isJsxStart(i)) { out += parseJsxElement(); continue; }
      out += src[i++];
    }
    return out;
  }

  function renderReact(content, dark, inlineMode) {
    stage.className = stage.className.replace(/\bpad\b/g, "").trim();
    const compiled = transpileJsx(content);
    const fnMatch = /(?:function|const|let)\s+([A-Z][A-Za-z0-9_]*)\b/.exec(compiled);
    const entryName = /__DefaultExport__/.test(compiled) ? "__DefaultExport__" : (fnMatch ? fnMatch[1] : "App");
    const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${sandboxBridgeScript()}<style>
      :root{--bg:${dark ? "#11141c" : "#ffffff"};--surface:${dark ? "#191e2b" : "#f7f8fa"};--ink:${dark ? "#eceff4" : "#14171f"};--muted:${dark ? "#94a3b8" : "#64748b"};--border:${dark ? "#2b3245" : "#dfe3eb"};--accent:${dark ? "#60a5fa" : "#2563eb"};}
      *{box-sizing:border-box;}
      body{margin:${inlineMode ? "10px" : "16px"};font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;background:var(--bg);color:var(--ink);line-height:1.5;}
      button{padding:7px 14px;border-radius:8px;border:1px solid var(--border);background:var(--surface);color:var(--ink);cursor:pointer;font-size:13px;font-weight:500;}
      button:active{opacity:.8;}
      input,select,textarea{padding:7px 10px;border-radius:8px;border:1px solid var(--border);background:var(--surface);color:var(--ink);font-size:13px;}
      .flex{display:flex}.flex-col{flex-direction:column}.items-center{align-items:center}.justify-between{justify-content:space-between}.gap-2{gap:8px}.gap-3{gap:12px}.gap-4{gap:16px}
      .grid{display:grid}.grid-cols-2{grid-template-columns:repeat(2,minmax(0,1fr))}.grid-cols-3{grid-template-columns:repeat(3,minmax(0,1fr))}
      .p-2{padding:8px}.p-3{padding:12px}.p-4{padding:16px}.p-6{padding:24px}.px-3{padding-left:12px;padding-right:12px}.py-2{padding-top:8px;padding-bottom:8px}
      .rounded,.rounded-lg{border-radius:10px}.rounded-xl{border-radius:14px}.rounded-full{border-radius:9999px}.border{border:1px solid var(--border)}
      .font-bold{font-weight:700}.font-semibold{font-weight:600}.text-sm{font-size:13px}.text-xs{font-size:11.5px}.text-lg{font-size:17px}.text-xl{font-size:20px}.text-2xl{font-size:24px}
      .w-full{width:100%}.space-y-2>*+*{margin-top:8px}.space-y-3>*+*{margin-top:12px}.space-y-4>*+*{margin-top:16px}
    </style></head><body><div id="root"></div><script>
    (function(){
      const Fragment = Symbol("Fragment");
      let hookStore = new Map(), curKey = "0", hookIdx = 0, pendingEffects = [];
      function createElement(type, props, ...children) {
        return {type, props: props || {}, children: children.flat(Infinity).filter(c => c !== null && c !== undefined && c !== false && c !== true)};
      }
      function useState(init) {
        const key = curKey, idx = hookIdx++;
        let arr = hookStore.get(key); if (!arr) { arr = []; hookStore.set(key, arr); }
        if (!(idx in arr)) arr[idx] = typeof init === "function" ? init() : init;
        const set = v => { arr[idx] = typeof v === "function" ? v(arr[idx]) : v; scheduleRender(); };
        return [arr[idx], set];
      }
      function useRef(init) {
        const key = curKey, idx = hookIdx++;
        let arr = hookStore.get(key); if (!arr) { arr = []; hookStore.set(key, arr); }
        if (!(idx in arr)) arr[idx] = {current: init};
        return arr[idx];
      }
      function useMemo(fn, deps) {
        const key = curKey, idx = hookIdx++;
        let arr = hookStore.get(key); if (!arr) { arr = []; hookStore.set(key, arr); }
        const prev = arr[idx];
        if (prev && deps && prev.deps && deps.length === prev.deps.length && deps.every((d, k) => Object.is(d, prev.deps[k]))) return prev.val;
        const val = fn(); arr[idx] = {val, deps}; return val;
      }
      function useCallback(fn, deps) { return useMemo(() => fn, deps); }
      function useEffect(fn, deps) {
        const key = curKey, idx = hookIdx++;
        let arr = hookStore.get(key); if (!arr) { arr = []; hookStore.set(key, arr); }
        const prev = arr[idx];
        const changed = !prev || !deps || !prev.deps || deps.length !== prev.deps.length || deps.some((d, k) => !Object.is(d, prev.deps[k]));
        if (changed) { pendingEffects.push(() => { try { if (prev && prev.clean) prev.clean(); } catch {} const c = fn(); arr[idx] = {deps, clean: typeof c === "function" ? c : null}; }); }
      }
      const React = {createElement, Fragment, useState, useRef, useMemo, useCallback, useEffect};
      window.React = React;
      function mount(vnode, path) {
        if (vnode === null || vnode === undefined || typeof vnode === "boolean") return document.createTextNode("");
        if (typeof vnode === "string" || typeof vnode === "number") return document.createTextNode(String(vnode));
        if (Array.isArray(vnode)) { const f = document.createDocumentFragment(); vnode.forEach((c, k) => f.appendChild(mount(c, path + "." + k))); return f; }
        if (vnode.type === Fragment) { const f = document.createDocumentFragment(); (vnode.children || []).forEach((c, k) => f.appendChild(mount(c, path + "." + k))); return f; }
        if (typeof vnode.type === "function") {
          const prevK = curKey, prevI = hookIdx;
          curKey = path + ":" + (vnode.props?.key ?? vnode.type.name ?? "fn"); hookIdx = 0;
          const out = vnode.type({...vnode.props, children: vnode.children});
          curKey = prevK; hookIdx = prevI;
          return mount(out, path + "/0");
        }
        const isSvg = ["svg","path","circle","rect","line","polygon","polyline","g","text","defs","linearGradient","stop"].includes(vnode.type);
        const el = isSvg ? document.createElementNS("http://www.w3.org/2000/svg", vnode.type) : document.createElement(vnode.type);
        for (const [k, v] of Object.entries(vnode.props || {})) {
          if (k === "key" || k === "children" || v === undefined || v === null) continue;
          if (k === "ref" && typeof v === "object") { v.current = el; continue; }
          if (k === "className") el.setAttribute("class", String(v));
          else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
          else if (k === "dangerouslySetInnerHTML" && v.__html) el.innerHTML = v.__html;
          else if (/^on[A-Z]/.test(k) && typeof v === "function") {
            const evName = k === "onChange" && (el.tagName === "INPUT" || el.tagName === "TEXTAREA") && el.type !== "checkbox" && el.type !== "radio" ? "input" : k.slice(2).toLowerCase();
            el.addEventListener(evName, v);
          } else if (k === "value" && ("value" in el)) el.value = v;
          else if (k === "checked" && ("checked" in el)) el.checked = Boolean(v);
          else if (typeof v === "boolean") { if (v) el.setAttribute(k, ""); }
          else el.setAttribute(k, String(v));
        }
        (vnode.children || []).forEach((c, idx) => el.appendChild(mount(c, path + "." + idx)));
        return el;
      }
      let RootComp = null;
      let queued = false;
      function doRender() {
        queued = false;
        const root = document.getElementById("root");
        const focusInfo = (() => {
          const ae = document.activeElement;
          if (!ae || (ae.tagName !== "INPUT" && ae.tagName !== "TEXTAREA" && ae.tagName !== "SELECT")) return null;
          const all = [...root.querySelectorAll(ae.tagName)];
          return {
            tag: ae.tagName,
            index: all.indexOf(ae),
            name: ae.getAttribute("name") || ae.getAttribute("id") || "",
            start: typeof ae.selectionStart === "number" ? ae.selectionStart : null,
            end: typeof ae.selectionEnd === "number" ? ae.selectionEnd : null,
          };
        })();
        pendingEffects = [];
        try {
          const dom = mount(createElement(RootComp, {}), "r");
          root.innerHTML = "";
          root.appendChild(dom);
          if (focusInfo && focusInfo.index >= 0) {
            const target = (focusInfo.name && root.querySelector(focusInfo.tag + '[name="' + focusInfo.name + '"], #' + focusInfo.name))
              || root.querySelectorAll(focusInfo.tag)[focusInfo.index];
            if (target && typeof target.focus === "function") {
              target.focus({preventScroll: true});
              if (focusInfo.start !== null && typeof target.setSelectionRange === "function") {
                try { target.setSelectionRange(focusInfo.start, focusInfo.end ?? focusInfo.start); } catch {}
              }
            }
          }
          pendingEffects.forEach(fn => fn());
        } catch (err) {
          console.error(err.message || String(err));
          root.innerHTML = '<pre style="color:#ef4444;white-space:pre-wrap">React 渲染错误：' + String(err.message || err) + '</pre>';
        }
      }
      function scheduleRender() { if (!queued) { queued = true; queueMicrotask(doRender); } }
      try {
        const fn = new Function("React","Fragment","useState","useEffect","useMemo","useRef","useCallback", ${JSON.stringify(compiled)} + "\\n;return typeof " + ${JSON.stringify(entryName)} + " !== 'undefined' ? " + ${JSON.stringify(entryName)} + " : null;");
        RootComp = fn(React, Fragment, useState, useEffect, useMemo, useRef, useCallback);
        if (typeof RootComp === "function") doRender();
        else throw new Error("未找到导出的 React 组件函数");
      } catch (err) {
        console.error(err.message || String(err));
        document.getElementById("root").innerHTML = '<pre style="color:#ef4444;white-space:pre-wrap">React 编译错误：' + String(err.message || err) + '</pre>';
      }
    })();
    <\/script></body></html>`;
    const iframe = document.createElement("iframe");
    iframe.className = "sandbox-frame";
    iframe.setAttribute("sandbox", "allow-scripts allow-forms");
    iframe.srcdoc = html;
    iframe.onload = () => reportHeight();
    stage.innerHTML = "";
    stage.appendChild(iframe);
  }


  function normalizeChartSpec(raw) {
    if (!raw || typeof raw !== "object") return null;
    if (Array.isArray(raw.series) && raw.series.length > 0) {
      const labels = raw.labels || raw.categories || (raw.xAxis && raw.xAxis.data) || raw.series[0].data?.map((_, i) => `#${i + 1}`) || [];
      return {
        type: String(raw.type || raw.series[0].type || "bar").toLowerCase(),
        title: raw.title && typeof raw.title === "object" ? (raw.title.text || "") : String(raw.title || ""),
        unit: String(raw.unit || ""),
        labels: labels.map(String),
        series: raw.series.map((s, idx) => ({
          name: String(s.name || `系列 ${idx + 1}`),
          color: s.color || PALETTE[idx % PALETTE.length],
          values: (s.values || s.data || []).map(v => typeof v === "object" && v !== null ? Number(v.value ?? v.y ?? 0) : Number(v)),
          points: Array.isArray(s.points) ? s.points : null,
        })),
      };
    }
    if (raw.data && Array.isArray(raw.data.datasets)) {
      const labels = (raw.data.labels || []).map(String);
      return {
        type: String(raw.type || "bar").toLowerCase(),
        title: String(raw.options?.plugins?.title?.text || raw.title || ""),
        unit: String(raw.unit || ""),
        labels,
        series: raw.data.datasets.map((ds, idx) => ({
          name: String(ds.label || `系列 ${idx + 1}`),
          color: Array.isArray(ds.backgroundColor) ? ds.backgroundColor[0] : (ds.borderColor || ds.backgroundColor || PALETTE[idx % PALETTE.length]),
          values: (ds.data || []).map(Number),
        })),
      };
    }
    if (Array.isArray(raw.data) && raw.data.length > 0 && typeof raw.data[0] === "object") {
      const labels = raw.data.map((d, i) => String(d.label ?? d.name ?? d.x ?? `#${i + 1}`));
      const values = raw.data.map(d => Number(d.value ?? d.y ?? 0));
      return {
        type: String(raw.type || "bar").toLowerCase(),
        title: String(raw.title || ""),
        unit: String(raw.unit || ""),
        labels,
        series: [{name: String(raw.seriesName || "数值"), color: PALETTE[0], values}],
      };
    }
    return null;
  }

  function formatCompactNum(v) {
    if (!Number.isFinite(v)) return "0";
    const abs = Math.abs(v);
    if (abs >= 1e6) return (v / 1e6).toFixed(1).replace(/\.0$/, "") + "M";
    if (abs >= 1e3) return (v / 1e3).toFixed(1).replace(/\.0$/, "") + "k";
    return Number.isInteger(v) ? String(v) : v.toFixed(1);
  }

  function buildChartSvg(spec, hiddenSet, dark) {
    const fg = dark ? "#e2e8f0" : "#1e293b";
    const muted = dark ? "#94a3b8" : "#64748b";
    const grid = dark ? "#2a3142" : "#e2e8f0";
    const activeSeries = spec.series.filter((_, i) => !hiddenSet.has(i));
    const type = spec.type;
    if (!activeSeries.length) {
      return `<svg viewBox="0 0 420 120" width="100%" height="120"><text x="210" y="64" text-anchor="middle" font-size="13" fill="${muted}">点击上方图例重新显示数据系列</text></svg>`;
    }

    if (type === "pie" || type === "donut" || type === "doughnut") {
      const vals = activeSeries[0].values.map(v => Math.max(0, Number(v) || 0));
      const total = vals.reduce((a, b) => a + b, 0) || 1;
      const cx = 135, cy = 118, r = 84, ir = type === "pie" ? 0 : 48;
      let start = -Math.PI / 2;
      const slices = [];
      const legends = [];
      vals.forEach((v, i) => {
        const frac = v / total;
        const ang = frac * Math.PI * 2;
        const end = start + ang;
        const color = PALETTE[i % PALETTE.length];
        const lbl = spec.labels[i] || `项 ${i + 1}`;
        const tipAttr = esc(lbl + ": " + formatCompactNum(v) + spec.unit + " (" + (frac * 100).toFixed(1) + "%)");
        if (frac >= 0.9999) {
          // 单项 100% 时 SVG 首尾重合弧会退化为空，改用整圆/圆环绘制
          if (ir > 0) {
            const ringR = (r + ir) / 2;
            slices.push(`<circle class="chart-hit" data-tip="${tipAttr}" cx="${cx}" cy="${cy}" r="${ringR.toFixed(1)}" fill="none" stroke="${color}" stroke-width="${(r - ir).toFixed(1)}"/>`);
          } else {
            slices.push(`<circle class="chart-hit" data-tip="${tipAttr}" cx="${cx}" cy="${cy}" r="${r}" fill="${color}"/>`);
          }
        } else if (frac > 0) {
          const large = ang > Math.PI ? 1 : 0;
          const x1 = cx + r * Math.cos(start), y1 = cy + r * Math.sin(start);
          const x2 = cx + r * Math.cos(end), y2 = cy + r * Math.sin(end);
          let d;
          if (ir > 0) {
            const ix1 = cx + ir * Math.cos(end), iy1 = cy + ir * Math.sin(end);
            const ix2 = cx + ir * Math.cos(start), iy2 = cy + ir * Math.sin(start);
            d = `M ${x1.toFixed(1)} ${y1.toFixed(1)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} L ${ix1.toFixed(1)} ${iy1.toFixed(1)} A ${ir} ${ir} 0 ${large} 0 ${ix2.toFixed(1)} ${iy2.toFixed(1)} Z`;
          } else {
            d = `M ${cx} ${cy} L ${x1.toFixed(1)} ${y1.toFixed(1)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} Z`;
          }
          slices.push(`<path class="chart-hit" data-tip="${tipAttr}" d="${d}" fill="${color}" stroke="${dark ? "#161a24" : "#ffffff"}" stroke-width="1.5"/>`);
        }
        if (i < 10) {
          const ly = 36 + i * 21;
          legends.push(`<rect x="248" y="${ly - 9}" width="10" height="10" rx="2" fill="${color}"/><text x="264" y="${ly}" font-size="11.5" fill="${fg}">${esc(lbl.slice(0, 14))}</text><text x="448" y="${ly}" text-anchor="end" font-size="11.5" fill="${muted}">${(frac * 100).toFixed(1)}%</text>`);
        }
        start = end;
      });
      const centerText = ir > 0 ? `<text x="${cx}" y="${cy - 2}" text-anchor="middle" font-size="16" font-weight="700" fill="${fg}">${formatCompactNum(total)}</text><text x="${cx}" y="${cy + 14}" text-anchor="middle" font-size="10.5" fill="${muted}">合计</text>` : "";
      return `<svg viewBox="0 0 460 238" width="100%" style="max-height:260px">${slices.join("")}${centerText}${legends.join("")}</svg>`;
    }

    if (type === "radar") {
      const axes = spec.labels.length || activeSeries[0].values.length || 3;
      const maxVal = Math.max(1, ...activeSeries.flatMap(s => s.values.map(v => Number(v) || 0)));
      const cx = 210, cy = 128, r = 84;
      const parts = [];
      for (let ring = 1; ring <= 4; ring++) {
        const rr = (r * ring) / 4;
        const pts = [];
        for (let k = 0; k < axes; k++) {
          const a = -Math.PI / 2 + (k * Math.PI * 2) / axes;
          pts.push(`${(cx + rr * Math.cos(a)).toFixed(1)},${(cy + rr * Math.sin(a)).toFixed(1)}`);
        }
        parts.push(`<polygon points="${pts.join(" ")}" fill="none" stroke="${grid}" stroke-width="1"/>`);
      }
      for (let k = 0; k < axes; k++) {
        const a = -Math.PI / 2 + (k * Math.PI * 2) / axes;
        parts.push(`<line x1="${cx}" y1="${cy}" x2="${(cx + r * Math.cos(a)).toFixed(1)}" y2="${(cy + r * Math.sin(a)).toFixed(1)}" stroke="${grid}" stroke-width="1"/>`);
        parts.push(`<text x="${(cx + (r + 18) * Math.cos(a)).toFixed(1)}" y="${(cy + (r + 18) * Math.sin(a) + 4).toFixed(1)}" text-anchor="middle" font-size="11" fill="${fg}">${esc((spec.labels[k] || `#${k + 1}`).slice(0, 10))}</text>`);
      }
      activeSeries.forEach(s => {
        const pts = [];
        for (let k = 0; k < axes; k++) {
          const v = Math.max(0, Number(s.values[k]) || 0);
          const rr = (v / maxVal) * r;
          const a = -Math.PI / 2 + (k * Math.PI * 2) / axes;
          pts.push(`${(cx + rr * Math.cos(a)).toFixed(1)},${(cy + rr * Math.sin(a)).toFixed(1)}`);
        }
        parts.push(`<polygon points="${pts.join(" ")}" fill="${s.color}" fill-opacity="0.22" stroke="${s.color}" stroke-width="2"/>`);
      });
      return `<svg viewBox="0 0 420 256" width="100%" style="max-height:270px">${parts.join("")}</svg>`;
    }

    const W = 480, H = 236, padL = 46, padR = 16, padT = 18, padB = 34;
    const plotW = W - padL - padR, plotH = H - padT - padB;
    const allVals = activeSeries.flatMap(s => s.values.filter(Number.isFinite));
    const minV = Math.min(0, ...(allVals.length ? allVals : [0]));
    const maxV = Math.max(1, ...(allVals.length ? allVals : [1]));
    const range = (maxV - minV) || 1;
    const yOf = v => padT + plotH - ((v - minV) / range) * plotH;
    const count = Math.max(spec.labels.length, ...activeSeries.map(s => s.values.length), 1);

    const parts = [];
    for (let t = 0; t <= 4; t++) {
      const val = minV + (range * t) / 4;
      const y = yOf(val);
      parts.push(`<line x1="${padL}" y1="${y.toFixed(1)}" x2="${W - padR}" y2="${y.toFixed(1)}" stroke="${grid}" stroke-dasharray="${t === 0 ? "" : "3,3"}"/>`);
      parts.push(`<text x="${padL - 6}" y="${(y + 4).toFixed(1)}" text-anchor="end" font-size="10.5" fill="${muted}">${formatCompactNum(val)}</text>`);
    }
    const stepX = plotW / Math.max(1, type === "bar" ? count : Math.max(1, count - 1));
    const labelStep = count > 8 ? Math.ceil(count / 7) : 1;
    for (let i = 0; i < count; i += labelStep) {
      const x = type === "bar" ? padL + (i + 0.5) * (plotW / count) : padL + (count === 1 ? plotW / 2 : i * stepX);
      const lbl = (spec.labels[i] || `#${i + 1}`).slice(0, 10);
      parts.push(`<text x="${x.toFixed(1)}" y="${H - 12}" text-anchor="middle" font-size="10.5" fill="${muted}">${esc(lbl)}</text>`);
    }

    if (type === "bar") {
      const groupW = (plotW / count) * 0.74;
      const barW = Math.max(4, groupW / activeSeries.length - 2);
      const baseY = yOf(0);
      activeSeries.forEach((s, si) => {
        s.values.forEach((v, i) => {
          const x = padL + i * (plotW / count) + ((plotW / count) - groupW) / 2 + si * (barW + 2);
          const y = yOf(v);
          const bh = Math.max(1, Math.abs(baseY - y));
          const top = Math.min(y, baseY);
          const tip = `${spec.labels[i] || `#${i + 1}`} · ${s.name}: ${formatCompactNum(v)}${spec.unit}`;
          parts.push(`<rect class="chart-hit" data-tip="${esc(tip)}" x="${x.toFixed(1)}" y="${top.toFixed(1)}" width="${barW.toFixed(1)}" height="${bh.toFixed(1)}" rx="3" fill="${s.color}" fill-opacity="0.88"/>`);
        });
      });
    } else if (type === "scatter") {
      activeSeries.forEach(s => {
        s.values.forEach((v, i) => {
          const x = padL + (count === 1 ? plotW / 2 : i * stepX);
          const y = yOf(v);
          const tip = `${spec.labels[i] || `#${i + 1}`} · ${s.name}: ${formatCompactNum(v)}${spec.unit}`;
          parts.push(`<circle class="chart-hit" data-tip="${esc(tip)}" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="5" fill="${s.color}" fill-opacity="0.85" stroke="${dark ? "#11141c" : "#ffffff"}" stroke-width="1.5"/>`);
        });
      });
    } else {
      activeSeries.forEach(s => {
        const pts = s.values.map((v, i) => [padL + (count === 1 ? plotW / 2 : i * stepX), yOf(v), v, i]);
        if (!pts.length) return;
        const d = pts.map((p, idx) => `${idx === 0 ? "M" : "L"} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(" ");
        if (type === "area") {
          const base = yOf(minV);
          const ad = `${d} L ${pts[pts.length - 1][0].toFixed(1)} ${base.toFixed(1)} L ${pts[0][0].toFixed(1)} ${base.toFixed(1)} Z`;
          parts.push(`<path d="${ad}" fill="${s.color}" fill-opacity="0.18"/>`);
        }
        parts.push(`<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>`);
        pts.forEach(p => {
          const tip = `${spec.labels[p[3]] || `#${p[3] + 1}`} · ${s.name}: ${formatCompactNum(p[2])}${spec.unit}`;
          parts.push(`<circle class="chart-hit" data-tip="${esc(tip)}" cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="3.6" fill="${s.color}" stroke="${dark ? "#141821" : "#ffffff"}" stroke-width="1.5"/>`);
        });
      });
    }
    return `<svg viewBox="0 0 ${W} ${H}" width="100%" style="max-height:260px">${parts.join("")}</svg>`;
  }

  function mountInteractiveChart(container, spec, dark) {
    const hidden = new Set();
    const wrap = document.createElement("div");
    wrap.className = "chart-card";
    const head = document.createElement("div");
    head.className = "chart-head";
    const titleEl = document.createElement("div");
    titleEl.className = "chart-title";
    titleEl.textContent = spec.title || "数据可视化图表";
    const legendEl = document.createElement("div");
    legendEl.className = "chart-legend";
    head.appendChild(titleEl);
    head.appendChild(legendEl);
    const tipEl = document.createElement("div");
    tipEl.className = "chart-tip-bar";
    tipEl.textContent = "点击数据柱/点查看精确数值 · 点击图例可显隐系列";
    const svgBox = document.createElement("div");
    wrap.appendChild(head);
    wrap.appendChild(svgBox);
    wrap.appendChild(tipEl);
    container.appendChild(wrap);

    function draw() {
      legendEl.innerHTML = "";
      spec.series.forEach((s, idx) => {
        const chip = document.createElement("span");
        chip.className = "chart-legend-item" + (hidden.has(idx) ? " off" : "");
        chip.innerHTML = `<span class="chart-dot" style="background:${s.color}"></span>${esc(s.name)}`;
        chip.onclick = () => { if (hidden.has(idx)) hidden.delete(idx); else hidden.add(idx); draw(); };
        legendEl.appendChild(chip);
      });
      svgBox.innerHTML = buildChartSvg(spec, hidden, dark);
      svgBox.querySelectorAll(".chart-hit").forEach(el => {
        const show = () => {
          tipEl.textContent = el.getAttribute("data-tip") || "";
          tipEl.classList.add("active");
        };
        el.addEventListener("click", show);
        el.addEventListener("mouseenter", show);
      });
      reportHeight();
    }
    draw();
  }

  function renderChart(content, dark) {
    stage.className = "pad";
    stage.innerHTML = "";
    try {
      const cleaned = String(content || "").trim().replace(/^```[a-zA-Z0-9_-]*\s*/i, "").replace(/```$/, "").trim();
      const raw = JSON.parse(cleaned);
      const spec = normalizeChartSpec(raw);
      if (!spec) throw new Error("缺少有效的 series 或 data 字段");
      mountInteractiveChart(stage, spec, dark);
    } catch (err) {
      stage.innerHTML = `<div class="err-banner">图表 JSON 解析失败：${esc(err.message)}</div><pre class="code-box" style="margin-top:8px">${esc(content)}</pre>`;
    }
    reportHeight();
  }


  function parseCsvRows(rawText) {
    const src = String(rawText || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").trim();
    if (!src) return [];
    const firstLine = src.split("\n", 1)[0] || "";
    const sep = firstLine.includes("\t") && !firstLine.includes(",") ? "\t" : ",";
    const rows = [];
    let row = [], cur = "", inQ = false;
    for (let i = 0; i < src.length; i++) {
      const ch = src[i];
      if (inQ) {
        if (ch === '"' && src[i + 1] === '"') { cur += '"'; i++; }
        else if (ch === '"') { inQ = false; }
        else { cur += ch; }
      } else if (ch === '"') {
        inQ = true;
      } else if (ch === sep) {
        row.push(cur.trim()); cur = "";
      } else if (ch === "\n") {
        row.push(cur.trim()); rows.push(row); row = []; cur = "";
      } else {
        cur += ch;
      }
    }
    if (cur.length > 0 || row.length > 0) { row.push(cur.trim()); rows.push(row); }
    return rows.filter(r => r.some(c => c.length > 0));
  }

  function renderCsv(content, dark) {
    stage.className = "pad";
    const parsedRows = parseCsvRows(content);
    if (!parsedRows.length) { stage.innerHTML = `<div class="err-banner">空 CSV 数据</div>`; return; }
    const headers = parsedRows[0];
    const rawRows = parsedRows.slice(1);
    let sortCol = -1, sortAsc = true;

    stage.innerHTML = "";
    const numCols = [];
    for (let c = 1; c < headers.length; c++) {
      const vals = rawRows.map(r => parseFloat(r[c]));
      if (vals.filter(Number.isFinite).length >= Math.max(1, rawRows.length * 0.6)) {
        numCols.push({idx: c, name: headers[c], values: vals.map(v => Number.isFinite(v) ? v : 0)});
      }
    }
    if (numCols.length && rawRows.length >= 2) {
      const chartBox = document.createElement("div");
      stage.appendChild(chartBox);
      mountInteractiveChart(chartBox, {
        type: "bar",
        title: `${headers[0] || "分类"} 指标对比`,
        unit: "",
        labels: rawRows.slice(0, 20).map(r => r[0] || ""),
        series: numCols.slice(0, 4).map((nc, i) => ({name: nc.name, color: PALETTE[i % PALETTE.length], values: nc.values.slice(0, 20)})),
      }, dark);
    }

    const tableBox = document.createElement("div");
    tableBox.className = "csv-table-box";
    stage.appendChild(tableBox);
    function drawTable() {
      const rows = sortCol < 0 ? rawRows : [...rawRows].sort((a, b) => {
        const va = a[sortCol] || "", vb = b[sortCol] || "";
        const na = parseFloat(va), nb = parseFloat(vb);
        const cmp = Number.isFinite(na) && Number.isFinite(nb) ? na - nb : va.localeCompare(vb);
        return sortAsc ? cmp : -cmp;
      });
      tableBox.innerHTML = `<table class="csv-table"><thead><tr>${headers.map((h, i) => `<th class="sortable" data-col="${i}">${esc(h)}${sortCol === i ? (sortAsc ? " ↑" : " ↓") : ""}</th>`).join("")}</tr></thead><tbody>${rows.slice(0, 200).map(r => `<tr>${headers.map((_, i) => `<td>${esc(r[i] || "")}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
      tableBox.querySelectorAll("th[data-col]").forEach(th => {
        th.onclick = () => {
          const c = Number(th.getAttribute("data-col"));
          if (sortCol === c) sortAsc = !sortAsc; else { sortCol = c; sortAsc = true; }
          drawTable();
        };
      });
      reportHeight();
    }
    drawTable();
  }

  function renderMarkdown(content, dark) {
    stage.className = "pad";
    const lines = String(content || "").replace(/\r\n/g, "\n").split("\n");
    const out = [];
    const inlineFmt = s => {
      const tokens = [];
      const hold = html => { tokens.push(html); return `\u0000T${tokens.length - 1}\u0000`; };
      let work = String(s || "");
      // 先保护行内代码与行内公式，避免后续转义/斜体破坏反斜杠与下划线
      work = work.replace(/`([^`]+)`/g, (_, c) => hold(`<code>${esc(c)}</code>`));
      if (global.SolbraidLatex) {
        work = work.replace(/\\\(([^)\n]+?)\\\)|(?<!\\)\$(?!\s)([^$\n]+?)(?<!\s)\$/g, (_, a, b) =>
          hold(global.SolbraidLatex.renderMath(a || b, false)));
      }
      work = work.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_, alt, url) => {
        const u = String(url || "").trim();
        return /^data:image\/(?:png|jpeg|jpg|webp|gif);base64,/i.test(u)
          ? hold(`<img alt="${esc(alt)}" src="${esc(u)}" style="max-width:100%;border-radius:6px"/>`)
          : hold(`<span>${esc(alt || "[图片]")}</span>`);
      });
      work = work.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_, text, url) => {
        const href = safeHref(url);
        return href ? hold(`<a href="${esc(href)}" rel="noopener noreferrer">${esc(text)}</a>`) : esc(text);
      });
      let h = esc(work)
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/\*([^*]+)\*/g, "<em>$1</em>")
        .replace(/~~([^~]+)~~/g, "<del>$1</del>");
      return h.replace(/\u0000T(\d+)\u0000/g, (_, idx) => tokens[Number(idx)] || "");
    };
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      const fence = /^```([a-zA-Z0-9_+-]*)\s*$/.exec(line.trim());
      if (fence) {
        const lang = fence[1].toLowerCase();
        const buf = []; i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i].trim())) { buf.push(lines[i]); i++; }
        i++;
        const code = buf.join("\n");
        if ((lang === "mermaid" || lang === "mmd") && global.SolbraidMermaid) {
          out.push(`<div class="svg-wrap" style="margin:10px 0">${global.SolbraidMermaid.render(code, dark) || `<pre class="code-box">${esc(code)}</pre>`}</div>`);
        } else if (lang === "svg") {
          out.push(`<div class="svg-wrap" style="margin:10px 0">${sanitizeSvgMarkup(code)}</div>`);
        } else if ((lang === "math" || lang === "latex" || lang === "tex") && global.SolbraidLatex) {
          out.push(`<div class="math-block">${global.SolbraidLatex.renderMath(code, true)}</div>`);
        } else if (lang === "chart" || lang === "echarts" || lang === "chartjs") {
          const cid = "mdchart-" + i;
          out.push(`<div class="md-chart-slot" data-spec="${esc(code)}" id="${cid}"></div>`);
        } else {
          const langBadge = lang ? `<div class="md-code-head"><span>${esc(lang)}</span></div>` : "";
          out.push(`<div class="md-code-card">${langBadge}<pre><code>${esc(code)}</code></pre></div>`);
        }
        continue;
      }
      if (/^\$\$/.test(line.trim())) {
        const single = /^\$\$(.+?)\$\$$/.exec(line.trim());
        let math = "";
        if (single) { math = single[1]; i++; }
        else { const buf = []; i++; while (i < lines.length && !/^\$\$/.test(lines[i].trim())) { buf.push(lines[i]); i++; } i++; math = buf.join("\n"); }
        out.push(global.SolbraidLatex ? `<div class="math-block">${global.SolbraidLatex.renderMath(math, true)}</div>` : `<pre class="code-box">${esc(math)}</pre>`);
        continue;
      }
      const hm = /^(#{1,6})\s+(.+)$/.exec(line);
      if (hm) { const lv = hm[1].length; out.push(`<h${lv}>${inlineFmt(hm[2])}</h${lv}>`); i++; continue; }
      if (/^---+$|^\*\*\*+$/.test(line.trim())) { out.push("<hr>"); i++; continue; }
      // Markdown Table (带对齐解析与圆角滚动外框)
      if (line.includes("|") && i + 1 < lines.length && /^\|?[\s:-]+\|[\s|:-]*$/.test(lines[i + 1].trim())) {
        const splitRow = r => r.trim().replace(/^\||\|$/g, "").split("|").map(c => c.trim());
        const ths = splitRow(line);
        const aligns = splitRow(lines[i + 1]).map(sep => {
          const s = sep.trim();
          if (s.startsWith(":") && s.endsWith(":")) return "center";
          if (s.endsWith(":")) return "right";
          return "left";
        });
        i += 2;
        const rows = [];
        while (i < lines.length && lines[i].includes("|") && lines[i].trim()) { rows.push(splitRow(lines[i])); i++; }
        out.push(`<div class="md-table-wrap"><table><thead><tr>${ths.map((h, k) => `<th style="text-align:${aligns[k] || "left"}">${inlineFmt(h)}</th>`).join("")}</tr></thead><tbody>${rows.map(r => `<tr>${ths.map((_, k) => `<td style="text-align:${aligns[k] || "left"}">${inlineFmt(r[k] || "")}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`);
        continue;
      }
      if (/^>\s?/.test(line)) {
        const qLines = [];
        while (i < lines.length && /^>\s?/.test(lines[i])) { qLines.push(lines[i].replace(/^>\s?/, "")); i++; }
        const calloutMatch = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*(.*)$/i.exec(qLines[0] || "");
        if (calloutMatch) {
          const kind = calloutMatch[1].toLowerCase();
          const metaMap = {
            note: {label: "说明", svg: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'},
            tip: {label: "提示", svg: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18h6"/><path d="M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/></svg>'},
            important: {label: "重要", svg: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 22 12 18.56 5.82 22 7 14.14l-5-4.87 6.91-1.01z"/></svg>'},
            warning: {label: "注意", svg: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>'},
            caution: {label: "警告", svg: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>'},
          };
          const meta = metaMap[kind] || metaMap.note;
          const bodyLines = [calloutMatch[2].trim(), ...qLines.slice(1)].filter(l => l.trim().length > 0);
          out.push(`<div class="md-callout md-callout-${kind}"><div class="md-callout-head">${meta.svg}<span>${meta.label}</span></div><div class="md-callout-body">${bodyLines.map(inlineFmt).join("<br>")}</div></div>`);
        } else {
          out.push(`<blockquote>${qLines.map(inlineFmt).join("<br>")}</blockquote>`);
        }
        continue;
      }
      if (/^\s*[-*+]\s+/.test(line)) {
        const items = [];
        while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) { items.push(lines[i].replace(/^\s*[-*+]\s+/, "")); i++; }
        const hasTask = items.some(it => /^\[[ xX]\](\s|$)/.test(it));
        if (hasTask) {
          out.push(`<ul class="md-task-list">${items.map(it => {
            const tm = /^\[([ xX])\](?:\s+(.*)|$)/.exec(it);
            if (!tm) return `<li>${inlineFmt(it)}</li>`;
            const checked = tm[1].toLowerCase() === "x";
            const box = checked
              ? '<span class="md-checkbox checked"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg></span>'
              : '<span class="md-checkbox"></span>';
            return `<li class="md-task-item">${box}<span class="${checked ? "md-task-done" : ""}">${inlineFmt(tm[2] || "")}</span></li>`;
          }).join("")}</ul>`);
        } else {
          out.push(`<ul>${items.map(it => `<li>${inlineFmt(it)}</li>`).join("")}</ul>`);
        }
        continue;
      }
      if (/^\s*\d+\.\s+/.test(line)) {
        const items = [];
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) { items.push(lines[i].replace(/^\s*\d+\.\s+/, "")); i++; }
        out.push(`<ol>${items.map(it => `<li>${inlineFmt(it)}</li>`).join("")}</ol>`);
        continue;
      }
      if (line.trim()) out.push(`<p>${inlineFmt(line)}</p>`);
      i++;
    }
    stage.innerHTML = `<article class="md-doc">${out.join("\n")}</article>`;
    stage.querySelectorAll(".md-chart-slot").forEach(el => {
      try {
        const spec = normalizeChartSpec(JSON.parse(el.getAttribute("data-spec") || "{}"));
        if (spec) mountInteractiveChart(el, spec, dark);
      } catch {}
    });
    reportHeight();
  }

  function renderCode(content) {
    stage.className = "pad";
    stage.innerHTML = `<pre class="code-box">${esc(content)}</pre>`;
    reportHeight();
  }

  function notifyRendered(seq) {
    if (!global.SolbraidStudioBridge || typeof global.SolbraidStudioBridge.onRendered !== "function") return;
    let sent = false;
    const fire = () => {
      if (sent) return;
      sent = true;
      requestAnimationFrame(() => requestAnimationFrame(() => {
        try { global.SolbraidStudioBridge.onRendered(Number(seq || 0)); } catch {}
      }));
    };
    const iframe = stage.querySelector("iframe.sandbox-frame");
    if (iframe) {
      const prevOnload = iframe.onload;
      iframe.onload = () => {
        if (typeof prevOnload === "function") prevOnload();
        fire();
      };
      setTimeout(fire, 900);
    } else {
      fire();
    }
  }

  function render(payload) {
    if (!payload) return;
    const prevId = lastPayload && lastPayload.id;
    lastPayload = payload;
    const kind = String(payload.kind || "code").toLowerCase();
    const content = String(payload.content || "");
    const dark = Boolean(payload.dark);
    const inlineMode = Boolean(payload.inline);
    const viewportMode = String(payload.viewport || "auto");
    setTheme(dark, inlineMode, viewportMode, payload.palette);
    if (kind === "html") renderHtml(content, dark, inlineMode, payload.pluginId || "");
    else if (kind === "svg") renderSvg(content);
    else if (kind === "mermaid") renderMermaid(content, dark);
    else if (kind === "chart") renderChart(content, dark);
    else if (kind === "math") renderMath(content);
    else if (kind === "latex") renderLatex(content, inlineMode);
    else if (kind === "react") renderReact(content, dark, inlineMode);
    else if (kind === "csv") renderCsv(content, dark);
    else if (kind === "markdown") renderMarkdown(content, dark);
    else renderCode(content);
    if (payload.id && prevId && payload.id !== prevId) {
      window.scrollTo(0, 0);
    }
    reportHeight();
    notifyRendered(payload.renderSeq || 0);
  }

  if (typeof ResizeObserver !== "undefined" && stage) {
    new ResizeObserver(() => reportHeight()).observe(stage);
  }

  global.SolbraidStudio = {
    render,
    setTheme(dark) {
      if (lastPayload) render({...lastPayload, dark});
      else setTheme(dark, false, "auto");
    },
    setViewport(viewport) {
      const vp = String(viewport || "auto");
      if (lastPayload) lastPayload = {...lastPayload, viewport: vp};
      setTheme(Boolean(lastPayload && lastPayload.dark), Boolean(lastPayload && lastPayload.inline), vp, lastPayload && lastPayload.palette);
      reportHeight();
    },
    resolvePluginRpc(reqId, ok, payload) {
      const iframe = stage.querySelector("iframe.sandbox-frame");
      iframe?.contentWindow?.postMessage({type: "solbraid:plugin_rpc_reply", reqId, ok: Boolean(ok), payload}, "*");
    },
    transpileJsx,
    sanitizeSvgMarkup,
  };
})(window);
