(() => {
  "use strict";
  const bridge = window.TerminalBridge;
  window.addEventListener("error", event => {
    try { bridge && bridge.jsError && bridge.jsError(String(event.message || "Terminal script error")); } catch {}
  });
  window.addEventListener("unhandledrejection", event => {
    try { bridge && bridge.jsError && bridge.jsError(String(event.reason || "Terminal promise error")); } catch {}
  });
  const params = new URLSearchParams(window.location.search);
  const initBg = /^#[0-9a-f]{6}$/i.test(params.get("bg") || "") ? params.get("bg") : "#0b0e14";
  const initFg = /^#[0-9a-f]{6}$/i.test(params.get("fg") || "") ? params.get("fg") : "#e6edf3";
  const rawFont = Number(params.get("font"));
  const initFont = Number.isFinite(rawFont) && rawFont >= 10 && rawFont <= 26 ? rawFont : 14;
  document.documentElement.style.backgroundColor = initBg;
  document.body.style.backgroundColor = initBg;
  const TermCtor = typeof Terminal === "function" ? Terminal : (Terminal && Terminal.Terminal);
  const FitCtor = typeof FitAddon === "function" ? FitAddon : (FitAddon && FitAddon.FitAddon);
  if (typeof TermCtor !== "function" || typeof FitCtor !== "function") {
    if (bridge && bridge.jsError) bridge.jsError("xterm.js 模块未能正确初始化");
    return;
  }
  const terminal = new TermCtor({
    cols: 80, rows: 24, scrollback: 4000, fontSize: initFont, lineHeight: 1.24, letterSpacing: 0,
    fontFamily: '"JetBrains Mono", "Fira Code", "Cascadia Mono", "Roboto Mono", ui-monospace, monospace',
    cursorBlink: true, cursorStyle: "bar", cursorWidth: 2,
    allowProposedApi: true, convertEol: false, scrollOnUserInput: true,
    theme: {
      background: initBg, foreground: initFg, cursor: "#7aa2f7", cursorAccent: "#0b0e14",
      selectionBackground: "rgba(122, 162, 247, 0.30)",
      black: "#15161e", red: "#f7768e", green: "#9ece6a", yellow: "#e0af68",
      blue: "#7aa2f7", magenta: "#bb9af7", cyan: "#7dcfff", white: "#c0caf5",
      brightBlack: "#565f89", brightRed: "#ff7a93", brightGreen: "#b9f27c", brightYellow: "#ff9e64",
      brightBlue: "#7da6ff", brightMagenta: "#c0a3ff", brightCyan: "#0db9d7", brightWhite: "#f0f6fc",
    },
  });
  const fit = new FitCtor();
  terminal.loadAddon(fit);
  const container = document.getElementById("terminal");
  terminal.open(container);
  let activeId = null, sequence = 0, restoring = false, waitingForSnapshot = false, control = false, alt = false;
  let pending = [], resizeTimer, restoreGeneration = 0, pinchStartDist = 0, pinchStartFont = 14;
  const bytes = base64 => Uint8Array.from(atob(base64), character => character.charCodeAt(0));
  const requestSnapshot = () => {
    if (activeId && !waitingForSnapshot) {
      waitingForSnapshot = true;
      bridge.resync(activeId);
    }
  };
  for (const prefix of ["", "?", ">"]) {
    terminal.parser.registerCsiHandler({prefix, final: "c"}, () => true);
    terminal.parser.registerCsiHandler({prefix, final: "n"}, () => true);
  }
  terminal.parser.registerEscHandler({final: "Z"}, () => true);
  function input(data) {
    if (!activeId || restoring || waitingForSnapshot) return;
    const navigation = /^\x1b(?:\[|O)[ABCDHF]$/.test(data);
    if (navigation) {
      if (control || alt) data = "\x1b[1;" + (1 + (alt ? 2 : 0) + (control ? 4 : 0)) + data.at(-1);
      else if (terminal.modes.applicationCursorKeysMode) data = "\x1bO" + data.at(-1);
    } else if (control && /^[a-z]$/i.test(data)) data = String.fromCharCode(data.toUpperCase().charCodeAt(0) & 31);
    else if (control && data === " ") data = "\x00";
    if (alt && !navigation) data = "\x1b" + data;
    control = false; alt = false;
    bridge.modifiers(false, false);
    bridge.input(activeId, data, false);
  }
  terminal.onData(input);
  terminal.onBinary(data => { if (activeId && !restoring && !waitingForSnapshot) bridge.input(activeId, btoa(data), true); });
  function fitTerminal() {
    if (!container || container.clientWidth < 16 || container.clientHeight < 16) return;
    const size = fit.proposeDimensions();
    if (!size || !Number.isFinite(size.cols) || !Number.isFinite(size.rows)) return;
    const cols = Math.max(2, Math.min(500, size.cols));
    const rows = Math.max(1, Math.min(300, size.rows));
    if (cols !== terminal.cols || rows !== terminal.rows) {
      terminal.resize(cols, rows);
      if (activeId && !restoring) bridge.resize(activeId, cols, rows);
    }
    try { bridge && bridge.grid && bridge.grid(terminal.cols, terminal.rows); } catch {}
  }
  const scheduleFit = () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(fitTerminal, 50);
  };
  new ResizeObserver(scheduleFit).observe(container);
  window.addEventListener("resize", scheduleFit);

  // 双指缩放平滑调整字号
  container.addEventListener("touchstart", e => {
    if (e.touches.length === 2) {
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      pinchStartDist = Math.hypot(dx, dy);
      pinchStartFont = terminal.options.fontSize || 14;
    }
  }, {passive: true});
  container.addEventListener("touchmove", e => {
    if (e.touches.length === 2 && pinchStartDist > 10) {
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      const ratio = Math.hypot(dx, dy) / pinchStartDist;
      const next = Math.max(10, Math.min(26, Math.round(pinchStartFont * ratio)));
      if (next !== terminal.options.fontSize) {
        terminal.options.fontSize = next;
        bridge.fontChanged(next);
        scheduleFit();
      }
    }
  }, {passive: true});
  container.addEventListener("touchend", () => { pinchStartDist = 0; }, {passive: true});

  function push(packet) {
    if (packet.ptyId !== activeId || packet.sequence <= sequence) return;
    if (restoring || waitingForSnapshot) {
      pending.push(packet);
      if (pending.length > 128) pending = pending.slice(-64);
      return;
    }
    if (packet.sequence !== sequence + 1) { pending.push(packet); requestSnapshot(); return; }
    sequence = packet.sequence;
    terminal.write(bytes(packet.dataBase64));
  }
  window.TerminalApp = {
    prepare(id) {
      restoreGeneration++;
      if (activeId !== id) { pending = []; sequence = 0; }
      activeId = id;
      waitingForSnapshot = true;
      terminal.options.disableStdin = true;
    },
    configure(options) {
      const nextFont = options.fontSize || 14;
      const fontChanged = terminal.options.fontSize !== nextFont;
      if (fontChanged) terminal.options.fontSize = nextFont;
      if (options.theme) terminal.options.theme = options.theme;
      document.documentElement.style.backgroundColor = terminal.options.theme.background;
      document.body.style.backgroundColor = terminal.options.theme.background;
      if (fontChanged) scheduleFit();
    },
    clearScreen() {
      restoreGeneration++;
      activeId = null;
      sequence = 0;
      restoring = false;
      waitingForSnapshot = false;
      pending = [];
      terminal.options.disableStdin = true;
      terminal.reset();
    },
    restore(payload) {
      const generation = ++restoreGeneration;
      const id = payload.session.ptyId;
      if (activeId !== id) pending = [];
      activeId = id;
      restoring = true;
      waitingForSnapshot = false;
      sequence = payload.session.sequence;
      if (terminal.cols !== payload.session.cols || terminal.rows !== payload.session.rows) {
        terminal.resize(payload.session.cols, payload.session.rows);
      }
      terminal.reset();
      const finishRestore = () => {
        if (generation !== restoreGeneration) return;
        restoring = false;
        terminal.options.disableStdin = false;
        const queued = pending;
        pending = [];
        for (const packet of queued) push(packet);
        requestAnimationFrame(() => {
          if (generation !== restoreGeneration) return;
          fitTerminal();
          terminal.scrollToBottom();
          terminal.focus();
          try { bridge && bridge.restored && bridge.restored(id); } catch {}
        });
      };
      if (payload.snapshot) terminal.write(payload.snapshot, finishRestore);
      else finishRestore();
    },
    push,
    focus() { terminal.focus(); },
    selectAll() { terminal.selectAll(); },
    clear() { terminal.clear(); },
    copy() {
      if (!terminal.hasSelection()) terminal.selectAll();
      bridge.copy(terminal.getSelection());
    },
    paste(text) { terminal.paste(text); terminal.focus(); },
    key(data) { input(data); terminal.focus(); },
    modifiers(ctrl, meta) { control = ctrl; alt = meta; terminal.focus(); },
    scrollBottom() { terminal.scrollToBottom(); },
    font(delta) {
      terminal.options.fontSize = Math.max(10, Math.min(26, Math.round((terminal.options.fontSize || 14) + delta)));
      bridge.fontChanged(terminal.options.fontSize);
      scheduleFit();
    },
  };
  requestAnimationFrame(() => {
    fitTerminal();
    bridge.ready(terminal.cols, terminal.rows);
  });
})();
