// Solbraid Offline LaTeX Compiler (Zero-CDN Math + Full Academic Document Engine)
(function (global) {
  "use strict";

  const SYMBOLS = {
    alpha: "α", beta: "β", gamma: "γ", delta: "δ", epsilon: "ϵ", varepsilon: "ε",
    zeta: "ζ", eta: "η", theta: "θ", vartheta: "ϑ", iota: "ι", kappa: "κ",
    lambda: "λ", mu: "μ", nu: "ν", xi: "ξ", pi: "π", varpi: "ϖ", rho: "ρ",
    sigma: "σ", varsigma: "ς", tau: "τ", upsilon: "υ", phi: "ϕ", varphi: "φ",
    chi: "χ", psi: "ψ", omega: "ω",
    Gamma: "Γ", Delta: "Δ", Theta: "Θ", Lambda: "Λ", Xi: "Ξ", Pi: "Π",
    Sigma: "Σ", Upsilon: "Υ", Phi: "Φ", Psi: "Ψ", Omega: "Ω",
    infty: "∞", partial: "∂", nabla: "∇", forall: "∀", exists: "∃", nexists: "∄",
    emptyset: "∅", varnothing: "∅", in: "∈", notin: "∉", ni: "∋",
    subset: "⊂", supset: "⊃", subseteq: "⊆", supseteq: "⊇", cup: "∪", cap: "∩",
    setminus: "∖", pm: "±", mp: "∓", times: "×", div: "÷", cdot: "·", ast: "∗",
    star: "⋆", circ: "∘", bullet: "•", oplus: "⊕", otimes: "⊗", odot: "⊙",
    leq: "≤", le: "≤", geq: "≥", ge: "≥", neq: "≠", ne: "≠", approx: "≈",
    equiv: "≡", sim: "∼", simeq: "≃", cong: "≅", propto: "∝", ll: "≪", gg: "≫",
    to: "→", rightarrow: "→", leftarrow: "←", leftrightarrow: "↔", mapsto: "↦",
    Rightarrow: "⇒", Leftarrow: "⇐", Leftrightarrow: "⇔", iff: "⟺", implies: "⟹",
    uparrow: "↑", downarrow: "↓", land: "∧", wedge: "∧", lor: "∨", vee: "∨",
    neg: "¬", lnot: "¬", top: "⊤", bot: "⊥", perp: "⊥", parallel: "∥",
    angle: "∠", triangle: "△", square: "□", qed: "∎", hbar: "ℏ", ell: "ℓ",
    Re: "ℜ", Im: "ℑ", aleph: "ℵ", wp: "℘", dots: "…", ldots: "…", cdots: "⋯",
    vdots: "⋮", ddots: "⋱", sum: "∑", prod: "∏", coprod: "∐", int: "∫",
    iint: "∬", iiint: "∭", oint: "∮", bigcup: "⋃", bigcap: "⋂", bigoplus: "⨁",
    langle: "⟨", rangle: "⟩", lceil: "⌈", rceil: "⌉", lfloor: "⌊", rfloor: "⌋",
    quad: "\u2001", qquad: "\u2001\u2001", ",": "\u2009", ";": "\u2005", "!": "", " ": "\u00a0",
    "%": "%", "$": "$", "&": "&", "#": "#", "_": "_", "{": "{", "}": "}",
  };

  const FUNCS = new Set([
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "log", "ln", "exp", "lim", "limsup", "liminf",
    "sup", "inf", "max", "min", "arg", "det", "dim", "gcd", "hom", "ker",
    "deg", "Pr", "mod", "bmod", "pmod", "Var", "Cov", "Tr", "rank", "diag",
  ]);

  const BB = {
    A: "𝔸", B: "𝔹", C: "ℂ", D: "𝔻", E: "𝔼", F: "𝔽", G: "𝔾", H: "ℍ", I: "𝕀",
    J: "𝕁", K: "𝕂", L: "𝕃", M: "𝕄", N: "ℕ", O: "𝕆", P: "ℙ", Q: "ℚ", R: "ℝ",
    S: "𝕊", T: "𝕋", U: "𝕌", V: "𝕍", W: "𝕎", X: "𝕏", Y: "𝕐", Z: "ℤ",
  };
  const BIG_OPS = new Set(["sum", "prod", "coprod", "int", "iint", "iiint", "oint", "bigcup", "bigcap", "bigoplus", "lim", "limsup", "liminf", "sup", "inf", "max", "min", "argmin", "argmax"]);

  function esc(s) {
    return String(s || "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function readGroup(src, i) {
    while (i < src.length && /\s/.test(src[i])) i++;
    if (i >= src.length) return ["", i];
    if (src[i] === "{") {
      let depth = 1, j = i + 1;
      while (j < src.length && depth > 0) {
        if (src[j] === "\\" && j + 1 < src.length) { j += 2; continue; }
        if (src[j] === "{") depth++;
        else if (src[j] === "}") depth--;
        j++;
      }
      return [src.slice(i + 1, j - 1), j];
    }
    if (src[i] === "\\") {
      let j = i + 1;
      if (j < src.length && /[a-zA-Z]/.test(src[j])) {
        while (j < src.length && /[a-zA-Z]/.test(src[j])) j++;
      } else if (j < src.length) {
        j++;
      }
      return [src.slice(i, j), j];
    }
    return [src[i], i + 1];
  }

  function readOptional(src, i) {
    let j = i;
    while (j < src.length && /\s/.test(src[j])) j++;
    if (src[j] !== "[") return [null, i];
    let depth = 1, k = j + 1;
    while (k < src.length && depth > 0) {
      if (src[k] === "[") depth++;
      else if (src[k] === "]") depth--;
      k++;
    }
    return [src.slice(j + 1, k - 1), k];
  }

  function renderMatrix(envName, body) {
    const delims = {
      pmatrix: ["(", ")"], bmatrix: ["[", "]"], Bmatrix: ["{", "}"],
      vmatrix: ["|", "|"], Vmatrix: ["‖", "‖"], cases: ["{", ""],
      matrix: ["", ""], smallmatrix: ["", ""], array: ["", ""],
    };
    const [L, R] = delims[envName] || ["", ""];
    const rows = body.trim().split(/\\\\/).filter(r => r.trim().length > 0);
    const trs = rows.map(row => {
      const cols = row.split("&").map(c => `<td>${renderMath(c.trim())}</td>`).join("");
      return `<tr>${cols}</tr>`;
    }).join("");
    const leftHtml = L ? `<span class="m-delim">${esc(L)}</span>` : "";
    const rightHtml = R ? `<span class="m-delim">${esc(R)}</span>` : "";
    return `${leftHtml}<table class="m-mat">${trs}</table>${rightHtml}`;
  }

  function renderMath(src) {
    let s = String(src || "").trim();
    let out = "";
    let i = 0;
    while (i < s.length) {
      const ch = s[i];
      if (ch === "%") {
        while (i < s.length && s[i] !== "\n") i++;
        continue;
      }
      if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
        i++;
        continue;
      }
      if (ch === "{") {
        const [grp, next] = readGroup(s, i);
        out += renderMath(grp);
        i = next;
        continue;
      }
      if (ch === "^" || ch === "_") {
        const [grp, next] = readGroup(s, i + 1);
        out += ch === "^" ? `<sup>${renderMath(grp)}</sup>` : `<sub>${renderMath(grp)}</sub>`;
        i = next;
        continue;
      }
      if (ch === "\\") {
        if (s[i + 1] === "\\") { out += "<br/>"; i += 2; continue; }
        let j = i + 1;
        let cmd = "";
        if (j < s.length && /[a-zA-Z]/.test(s[j])) {
          while (j < s.length && /[a-zA-Z]/.test(s[j])) j++;
          cmd = s.slice(i + 1, j);
        } else if (j < s.length) {
          cmd = s[j];
          j++;
        }
        i = j;
        if (cmd === "frac" || cmd === "dfrac" || cmd === "tfrac" || cmd === "cfrac") {
          const [a, n1] = readGroup(s, i);
          const [b, n2] = readGroup(s, n1);
          out += `<span class="m-frac"><span>${renderMath(a)}</span><span>${renderMath(b)}</span></span>`;
          i = n2;
          continue;
        }
        if (cmd === "binom" || cmd === "dbinom" || cmd === "tbinom") {
          const [a, n1] = readGroup(s, i);
          const [b, n2] = readGroup(s, n1);
          out += `<span class="m-delim">(</span><span class="m-frac"><span style="border:0">${renderMath(a)}</span><span>${renderMath(b)}</span></span><span class="m-delim">)</span>`;
          i = n2;
          continue;
        }
        if (cmd === "boxed") {
          const [body, n1] = readGroup(s, i);
          out += `<span class="m-boxed">${renderMath(body)}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "cancel" || cmd === "bcancel") {
          const [body, n1] = readGroup(s, i);
          out += `<span class="m-cancel">${renderMath(body)}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "textcolor" || cmd === "color") {
          const [clr, n1] = readGroup(s, i);
          const safeClr = /^[#a-zA-Z0-9(),.\s-]+$/.test(clr) ? clr : "inherit";
          if (cmd === "textcolor") {
            const [body, n2] = readGroup(s, n1);
            out += `<span style="color:${esc(safeClr)}">${renderMath(body)}</span>`;
            i = n2;
          } else {
            i = n1;
          }
          continue;
        }
        if (cmd === "underset" || cmd === "overset") {
          const [mark, n1] = readGroup(s, i);
          const [base, n2] = readGroup(s, n1);
          out += cmd === "overset"
            ? `<span class="m-bigop"><span class="m-lim-top">${renderMath(mark)}</span><span>${renderMath(base)}</span></span>`
            : `<span class="m-bigop"><span>${renderMath(base)}</span><span class="m-lim-bot">${renderMath(mark)}</span></span>`;
          i = n2;
          continue;
        }
        if (cmd === "underbrace" || cmd === "overbrace") {
          const [base, n1] = readGroup(s, i);
          out += `<span class="m-bigop">${cmd === "overbrace" ? '<span class="m-lim-top">⏞</span>' : ""}<span>${renderMath(base)}</span>${cmd === "underbrace" ? '<span class="m-lim-bot">⏟</span>' : ""}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "xrightarrow" || cmd === "xleftarrow") {
          const [opt, n0] = readOptional(s, i);
          const [top, n1] = readGroup(s, n0);
          const arr = cmd === "xrightarrow" ? "⟶" : "⟵";
          out += `<span class="m-bigop">${top ? `<span class="m-lim-top">${renderMath(top)}</span>` : ""}<span>${arr}</span>${opt ? `<span class="m-lim-bot">${renderMath(opt)}</span>` : ""}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "sqrt") {
          const [opt, n0] = readOptional(s, i);
          const [body, n1] = readGroup(s, n0);
          const root = opt ? `<sup>${renderMath(opt)}</sup>` : "";
          out += `<span class="m-sqrt">${root}√<span class="m-sqrt-bar">${renderMath(body)}</span></span>`;
          i = n1;
          continue;
        }
        if (cmd === "text" || cmd === "textrm" || cmd === "mathrm" || cmd === "operatorname") {
          const [body, n1] = readGroup(s, i);
          out += `<span style="font-style:normal">${esc(body)}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "mathbf" || cmd === "boldsymbol" || cmd === "bm") {
          const [body, n1] = readGroup(s, i);
          out += `<strong>${renderMath(body)}</strong>`;
          i = n1;
          continue;
        }
        if (cmd === "mathbb") {
          const [body, n1] = readGroup(s, i);
          out += `<span class="m-bb">${body.split("").map(c => BB[c] || esc(c)).join("")}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "mathcal" || cmd === "mathscr") {
          const [body, n1] = readGroup(s, i);
          out += `<span class="m-cal">${esc(body)}</span>`;
          i = n1;
          continue;
        }
        if (cmd === "hat" || cmd === "bar" || cmd === "overline" || cmd === "vec" || cmd === "tilde" || cmd === "dot" || cmd === "ddot") {
          const [body, n1] = readGroup(s, i);
          const mark = {hat: "^", bar: "¯", overline: "¯", vec: "→", tilde: "~", dot: "·", ddot: "··"}[cmd];
          out += `<span class="m-accent"><span class="m-top">${mark}</span><span>${renderMath(body)}</span></span>`;
          i = n1;
          continue;
        }
        if (cmd === "left" || cmd === "right") {
          while (i < s.length && /\s/.test(s[i])) i++;
          if (i < s.length) {
            if (s[i] === ".") { i++; continue; }
            if (s[i] === "\\") {
              const [tok, n1] = readGroup(s, i);
              out += `<span class="m-delim">${esc(SYMBOLS[tok.slice(1)] || tok.slice(1))}</span>`;
              i = n1;
            } else {
              out += `<span class="m-delim">${esc(s[i])}</span>`;
              i++;
            }
          }
          continue;
        }
        if (cmd === "begin") {
          const [envName, n1] = readGroup(s, i);
          const endTok = `\\end{${envName}}`;
          const endIdx = s.indexOf(endTok, n1);
          if (endIdx >= 0) {
            let body = s.slice(n1, endIdx);
            if (envName === "array") { const [, nextB] = readGroup(body, 0); body = body.slice(nextB); }
            if (envName === "aligned" || envName === "align" || envName === "align*" || envName === "gather" || envName === "split") {
              out += renderMatrix("matrix", body.replace(/\\label\{[^}]*\}/g, ""));
            } else {
              out += renderMatrix(envName, body);
            }
            i = endIdx + endTok.length;
            continue;
          }
        }
        if (cmd === "label" || cmd === "tag" || cmd === "displaystyle" || cmd === "limits" || cmd === "nolimits" || cmd === "big" || cmd === "Big" || cmd === "bigg" || cmd === "Bigg") {
          if (cmd === "label" || cmd === "tag") { const [, n1] = readGroup(s, i); i = n1; }
          continue;
        }
        if (BIG_OPS.has(cmd)) {
          // Consume optional \limits / \nolimits and attached _ / ^ for crisp vertical stacked limits!
          let k = i;
          while (k < s.length && /\s/.test(s[k])) k++;
          if (s.startsWith("\\limits", k)) k += 7;
          else if (s.startsWith("\\nolimits", k)) k += 9;
          let sub = null, sup = null;
          for (let pass = 0; pass < 2; pass++) {
            while (k < s.length && /\s/.test(s[k])) k++;
            if (s[k] === "_" && sub === null) { const [g, nk] = readGroup(s, k + 1); sub = g; k = nk; }
            else if (s[k] === "^" && sup === null) { const [g, nk] = readGroup(s, k + 1); sup = g; k = nk; }
          }
          const sym = Object.prototype.hasOwnProperty.call(SYMBOLS, cmd) ? SYMBOLS[cmd] : cmd;
          const isWord = /^[a-zA-Z]+$/.test(sym);
          const opSpan = isWord ? `<span style="font-style:normal">${esc(sym)}</span>` : `<span class="m-op">${sym}</span>`;
          if (sub !== null || sup !== null) {
            out += `<span class="m-bigop">${sup !== null ? `<span class="m-lim-top">${renderMath(sup)}</span>` : ""}${opSpan}${sub !== null ? `<span class="m-lim-bot">${renderMath(sub)}</span>` : ""}</span>`;
            i = k;
          } else {
            out += opSpan + (isWord ? " " : "");
          }
          continue;
        }
        if (FUNCS.has(cmd)) {
          out += `<span style="font-style:normal">${esc(cmd)}</span> `;
          continue;
        }
        if (Object.prototype.hasOwnProperty.call(SYMBOLS, cmd)) {
          out += SYMBOLS[cmd];
          continue;
        }
        out += esc(cmd);
        continue;
      }
      if (/[a-zA-Z]/.test(ch)) {
        out += `<span class="m-var">${esc(ch)}</span>`;
      } else if (/[=+\-<>]/.test(ch)) {
        out += ` ${esc(ch === "-" ? "−" : ch)} `;
      } else {
        out += esc(ch);
      }
      i++;
    }
    return out;
  }

  function renderInlineText(text) {
    let s = String(text || "");
    const mathSlots = [];
    s = s.replace(/\\\(([\s\S]+?)\\\)|\$([^$\n]+?)\$/g, (_, a, b) => {
      const idx = mathSlots.length;
      mathSlots.push(`<span class="math-inline">${renderMath(a || b)}</span>`);
      return `\u0001M${idx}\u0001`;
    });
    s = esc(s);
    s = s.replace(/\\textbf\{([^{}]*)\}/g, "<strong>$1</strong>")
      .replace(/\\textit\{([^{}]*)\}|\\emph\{([^{}]*)\}/g, (_, a, b) => `<em>${a || b}</em>`)
      .replace(/\\texttt\{([^{}]*)\}/g, '<code class="m-tt">$1</code>')
      .replace(/\\underline\{([^{}]*)\}/g, "<u>$1</u>")
      .replace(/\\href\{([^{}]*)\}\{([^{}]*)\}/g, "$2")
      .replace(/\\cite\{([^{}]*)\}/g, "[$1]")
      .replace(/\\ref\{([^{}]*)\}|\\eqref\{([^{}]*)\}/g, (_, a, b) => a ? `[${a}]` : `(${b})`)
      .replace(/\\footnote\{([^{}]*)\}/g, ' <span style="color:var(--muted);font-size:0.86em">[$1]</span>')
      .replace(/\\(?:noindent|centering|newline|par)\b/g, "")
      .replace(/\\\\/g, "<br/>")
      .replace(/``|''/g, '"')
      .replace(/---/g, "—").replace(/--/g, "–")
      .replace(/\\([%$&#_{}])/g, "$1");
    return s.replace(/\u0001M(\d+)\u0001/g, (_, idx) => mathSlots[Number(idx)] || "");
  }

  function renderTabular(body) {
    const clean = body.replace(/^\{[^}]*\}/, "").trim();
    const rawRows = clean.split(/\\\\/).map(r => r.trim()).filter(Boolean);
    let trs = "";
    for (const row of rawRows) {
      const cls = /\\toprule/.test(row) ? ' class="toprule"' : /\\bottomrule/.test(row) ? ' class="bottomrule"' : "";
      const stripped = row.replace(/\\(?:hline|toprule|midrule|bottomrule|cline\{[^}]*\})/g, "").trim();
      if (!stripped) continue;
      const cells = stripped.split("&").map(c => `<td>${renderInlineText(c.trim())}</td>`).join("");
      trs += `<tr${cls}>${cells}</tr>`;
    }
    return `<table><tbody>${trs}</tbody></table>`;
  }

  function renderDocument(raw) {
    let src = String(raw || "").replace(/\r\n/g, "\n");
    src = src.split("\n").map(line => line.replace(/(^|[^\\])%.*/, "$1")).join("\n");
    const hasDoc = /\\begin\{document\}/.test(src);
    let title = "", author = "", date = "";
    const grabCmd = name => {
      const m = new RegExp(`\\\\${name}\\s*\\{`).exec(src);
      if (!m) return "";
      return readGroup(src, m.index + m[0].length - 1)[0];
    };
    title = grabCmd("title");
    author = grabCmd("author");
    date = grabCmd("date");

    if (hasDoc) {
      const start = src.indexOf("\\begin{document}") + "\\begin{document}".length;
      const end = src.indexOf("\\end{document}");
      src = end > start ? src.slice(start, end) : src.slice(start);
    } else if (!/\\(section|subsection|begin\{itemize\}|begin\{enumerate\}|begin\{tabular\}|begin\{theorem\}|begin\{abstract\})/.test(src)) {
      const stripped = src.trim().replace(/^\$\$|\$\$$/g, "").replace(/^\\\[|\\\]$/g, "");
      return `<article class="tex-page"><div class="math-block" style="font-size:1.25em">${renderMath(stripped)}</div></article>`;
    }

    let eqNum = 0, secNum = 0, subNum = 0, thmNum = 0;
    const blocks = [];
    const stash = html => { const id = blocks.length; blocks.push(html); return `\n\n\u0002B${id}\u0002\n\n`; };

    src = src.replace(/\\maketitle\b/g, () => {
      const meta = [author, date].filter(Boolean).map(renderInlineText).join(" · ");
      return stash((title ? `<h1 class="tex-title">${renderInlineText(title)}</h1>` : "") + (meta ? `<div class="tex-meta">${meta}</div>` : ""));
    });

    src = src.replace(/\\begin\{(equation\*?|align\*?|gather\*?|multline\*?)\}([\s\S]*?)\\end\{\1\}/g, (_, env, body) => {
      const numbered = !env.endsWith("*");
      const numHtml = numbered ? `<span class="eq-num">(${++eqNum})</span>` : "";
      const inner = env.startsWith("align") ? renderMatrix("matrix", body.replace(/\\label\{[^}]*\}/g, "")) : renderMath(body);
      return stash(`<div class="math-block"><span>${inner}</span>${numHtml}</div>`);
    });
    src = src.replace(/\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]/g, (_, a, b) =>
      stash(`<div class="math-block"><span>${renderMath(a || b)}</span></div>`),
    );
    src = src.replace(/\\begin\{abstract\}([\s\S]*?)\\end\{abstract\}/g, (_, body) =>
      stash(`<div class="tex-abstract"><strong>摘要 / Abstract.</strong> ${renderInlineText(body.trim())}</div>`),
    );
    src = src.replace(/\\begin\{(theorem|lemma|proposition|corollary|definition|remark|example)\}(?:\[([^\]]+)\])?([\s\S]*?)\\end\{\1\}/g, (_, kind, note, body) => {
      const labelMap = {theorem: "定理", lemma: "引理", proposition: "命题", corollary: "推论", definition: "定义", remark: "注", example: "例"};
      const head = `${labelMap[kind] || kind} ${++thmNum}${note ? `（${renderInlineText(note)}）` : ""}.`;
      return stash(`<div class="tex-thm"><span class="tex-thm-head">${head}</span>${renderInlineText(body.trim())}</div>`);
    });
    src = src.replace(/\\begin\{proof\}([\s\S]*?)\\end\{proof\}/g, (_, body) =>
      stash(`<div class="tex-proof"><em>证明.</em> ${renderInlineText(body.trim())}<span class="tex-qed">∎</span></div>`),
    );
    src = src.replace(/\\begin\{tabular\}([\s\S]*?)\\end\{tabular\}/g, (_, body) => stash(renderTabular(body)));
    src = src.replace(/\\begin\{(itemize|enumerate)\}([\s\S]*?)\\end\{\1\}/g, (_, kind, body) => {
      const tag = kind === "enumerate" ? "ol" : "ul";
      const items = body.split(/\\item\b/).slice(1).map(it => `<li>${renderInlineText(it.trim())}</li>`).join("");
      return stash(`<${tag}>${items}</${tag}>`);
    });
    src = src.replace(/\\begin\{verbatim\}([\s\S]*?)\\end\{verbatim\}/g, (_, body) =>
      stash(`<pre style="background:var(--code-bg);padding:10px;border-radius:8px;overflow:auto"><code>${esc(body)}</code></pre>`),
    );
    src = src.replace(/\\section\*?\{([^{}]*)\}/g, (_, t) => { secNum++; subNum = 0; return stash(`<h2>${secNum}. ${renderInlineText(t)}</h2>`); });
    src = src.replace(/\\subsection\*?\{([^{}]*)\}/g, (_, t) => { subNum++; return stash(`<h3>${secNum}.${subNum} ${renderInlineText(t)}</h3>`); });
    src = src.replace(/\\subsubsection\*?\{([^{}]*)\}/g, (_, t) => stash(`<h4>${renderInlineText(t)}</h4>`));

    const bodyHtml = src.split(/\n{2,}/).map(chunk => {
      const t = chunk.trim();
      if (!t) return "";
      if (/^\u0002B\d+\u0002$/.test(t)) return t;
      return `<p>${renderInlineText(t)}</p>`;
    }).join("\n").replace(/\u0002B(\d+)\u0002/g, (_, idx) => blocks[Number(idx)] || "");

    const headerFallback = (!hasDoc || /\\maketitle\b/.test(raw)) ? "" :
      (title ? `<h1 class="tex-title">${renderInlineText(title)}</h1>` : "") +
      ([author, date].filter(Boolean).length ? `<div class="tex-meta">${[author, date].filter(Boolean).map(renderInlineText).join(" · ")}</div>` : "");
    return `<article class="tex-page">${headerFallback}${bodyHtml}</article>`;
  }

  function renderMathBlock(raw) {
    const stripped = String(raw || "").trim()
      .replace(/^\$\$\s*|\s*\$\$$/g, "")
      .replace(/^\\\[\s*|\s*\\\]$/g, "")
      .trim();
    return `<div class="math-inline-stage"><span>${renderMath(stripped)}</span></div>`;
  }

  global.SolbraidLatex = {renderMath, renderMathBlock, renderDocument};
})(window);
