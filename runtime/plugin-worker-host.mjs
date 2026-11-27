// Solbraid 隔离工作线程插件宿主（基于 Node.js worker_threads）。
// 为声明 executionTier: "worker-isolated" 的插件提供独立线程与硬超时强杀（worker.terminate()），
// 防止插件同步死循环（如 while(true){}）或重度 CPU 运算卡死 agent-hub 主事件循环。
import {Worker, isMainThread, parentPort, workerData} from "node:worker_threads";
import {randomUUID} from "node:crypto";

const DEFAULT_CALL_TIMEOUT_MS = 15_000;

if (isMainThread) {
  // 主线程侧工厂由文件底部 export function spawnIsolatedPluginWorker 提供
} else {
  runWorkerRuntime(workerData);
}

/** 在独立 Worker 线程内运行插件入口并代理全部 Solbraid Plugin SDK 能力。 */
function runWorkerRuntime(init) {
  const {pluginId, entryUrl, hostInfo, grantedCapabilities, settings: initialSettings} = init;
  const granted = new Set(grantedCapabilities || []);
  let currentSettings = {...(initialSettings || {})};
  const settingsListeners = new Set();
  const effects = [];
  const commands = new Map();
  const panels = new Map();
  const surfaces = new Map();
  const slots = new Map();
  const rpcs = new Map();
  const drivers = new Map();
  const translators = new Map();

  const pendingHostCalls = new Map();
  function callHost(op, payload = {}) {
    return new Promise((resolve, reject) => {
      const id = randomUUID();
      pendingHostCalls.set(id, {resolve, reject});
      parentPort.postMessage({type: "hostCall", id, op, payload});
    });
  }

  function registerEffect(fn, label = "cleanup") {
    if (typeof fn === "function") effects.push({fn, label});
  }

  const abortController = new AbortController();
  const ctx = {
    host: Object.freeze({...hostInfo, executionTier: "worker-isolated"}),
    pluginId,
    signal: abortController.signal,
    log: Object.freeze({
      info: msg => parentPort.postMessage({type: "log", level: "info", message: String(msg ?? "")}),
      warn: msg => parentPort.postMessage({type: "log", level: "warn", message: String(msg ?? "")}),
      error: msg => parentPort.postMessage({type: "log", level: "error", message: String(msg ?? "")}),
    }),
    effect: registerEffect,
    settings: Object.freeze({
      get: () => ({...currentSettings}),
      onChange: fn => {
        if (typeof fn !== "function") return () => {};
        settingsListeners.add(fn);
        const off = () => settingsListeners.delete(fn);
        registerEffect(off, "settings.onChange");
        return off;
      },
    }),
  };

  if (granted.has("storage.local")) {
    ctx.storage = Object.freeze({
      get: key => callHost("storage.get", {key}),
      set: (key, value) => callHost("storage.set", {key, value}),
      delete: key => callHost("storage.delete", {key}),
      list: () => callHost("storage.list", {}),
    });
  }

  if (granted.has("workspace.read") || granted.has("workspace.write")) {
    const ws = {};
    if (granted.has("workspace.read")) {
      ws.readDir = targetDir => callHost("workspace.readDir", {targetDir});
      ws.readText = (filePath, maxBytes) => callHost("workspace.readText", {filePath, maxBytes});
    }
    if (granted.has("workspace.write")) {
      ws.writeText = (filePath, text) => callHost("workspace.writeText", {filePath, text});
    }
    ctx.workspace = Object.freeze(ws);
  }

  if (granted.has("commands")) {
    ctx.commands = Object.freeze({
      register(name, spec = {}) {
        const clean = String(name || "").replace(/^\/+/, "").trim();
        commands.set(clean, spec);
        parentPort.postMessage({
          type: "register",
          kind: "command",
          spec: {name: clean, description: String(spec.description || ""), promptTemplate: spec.promptTemplate || null, hasHandler: typeof spec.handler === "function"},
        });
      },
    });
  }

  if (granted.has("ui.panel")) {
    ctx.panels = Object.freeze({
      register(spec = {}) {
        const id = String(spec.id || "").trim();
        panels.set(id, spec);
        parentPort.postMessage({
          type: "register",
          kind: "panel",
          spec: {id, title: String(spec.title || id), description: String(spec.description || ""), htmlFile: spec.htmlFile || null, hasRender: typeof spec.render === "function"},
        });
      },
    });
  }

  if (granted.has("ui.composerAction")) {
    ctx.composer = Object.freeze({
      registerAction(spec = {}) {
        parentPort.postMessage({type: "register", kind: "composerAction", spec});
      },
    });
  }

  if (granted.has("ui.sdui") || granted.has("ui.slot")) {
    const uiApi = {};
    if (granted.has("ui.sdui")) {
      uiApi.registerSurface = (spec = {}) => {
        const id = String(spec.id || "").trim();
        surfaces.set(id, spec);
        parentPort.postMessage({
          type: "register",
          kind: "surface",
          spec: {id, title: String(spec.title || id), description: String(spec.description || "")},
        });
      };
    }
    if (granted.has("ui.slot")) {
      uiApi.registerSlot = (spec = {}) => {
        const id = String(spec.id || "").trim();
        slots.set(id, spec);
        parentPort.postMessage({
          type: "register",
          kind: "slot",
          spec: {
            id,
            slot: String(spec.slot || ""),
            label: String(spec.label || ""),
            description: String(spec.description || ""),
            badge: String(spec.badge || ""),
            surfaceId: spec.surfaceId || null,
            panelId: spec.panelId || null,
            command: spec.command || null,
            routes: Array.isArray(spec.routes) ? spec.routes.map(String) : [],
            alignment: spec.alignment || "bottomEnd",
            offsetX: Number.isFinite(Number(spec.offsetX)) ? Number(spec.offsetX) : undefined,
            offsetY: Number.isFinite(Number(spec.offsetY)) ? Number(spec.offsetY) : undefined,
            draggable: spec.draggable,
            displayMode: spec.displayMode || "",
            hasRender: typeof spec.render === "function",
          },
        });
      };
    }
    ctx.ui = Object.freeze(uiApi);
  }

  if (granted.has("rpc")) {
    ctx.rpc = Object.freeze({
      register(method, handler) {
        const clean = String(method || "").trim();
        rpcs.set(clean, handler);
        parentPort.postMessage({type: "register", kind: "rpc", spec: {method: clean}});
      },
    });
  }

  const hooks = {beforePrompt: new Map(), afterResponse: new Map()};
  const activeDriverAborts = new Map();
  if (granted.has("hooks.prompt")) {
    ctx.hooks = Object.freeze({
      onBeforePrompt(fn) {
        if (typeof fn !== "function") return () => {};
        const hookId = randomUUID().slice(0, 12);
        hooks.beforePrompt.set(hookId, fn);
        parentPort.postMessage({type: "register", kind: "hook", spec: {stage: "beforePrompt", hookId}});
        return () => { hooks.beforePrompt.delete(hookId); };
      },
      onAfterResponse(fn) {
        if (typeof fn !== "function") return () => {};
        const hookId = randomUUID().slice(0, 12);
        hooks.afterResponse.set(hookId, fn);
        parentPort.postMessage({type: "register", kind: "hook", spec: {stage: "afterResponse", hookId}});
        return () => { hooks.afterResponse.delete(hookId); };
      },
    });
  }

  if (granted.has("agent.driver")) {
    ctx.drivers = Object.freeze({
      register(spec = {}) {
        const id = String(spec.id || "").trim().toLowerCase();
        drivers.set(id, spec);
        parentPort.postMessage({
          type: "register",
          kind: "driver",
          spec: {
            id, name: String(spec.name || id), protocol: String(spec.protocol || "plugin-driver"),
            builtin: false, description: String(spec.description || ""), executable: typeof spec.execute === "function",
          },
        });
      },
      list: () => [...drivers.keys()],
    });
  }

  if (granted.has("provider.translator")) {
    ctx.providers = Object.freeze({
      registerTranslator(spec = {}) {
        const id = String(spec.id || "").trim().toLowerCase();
        translators.set(id, spec);
        parentPort.postMessage({
          type: "register",
          kind: "translator",
          spec: {id, name: String(spec.name || id), description: String(spec.description || ""), baseProtocol: String(spec.baseProtocol || "openai_chat")},
        });
      },
    });
  }

  parentPort.on("message", async msg => {
    if (msg?.type === "cancel") {
      const ac = activeDriverAborts.get(msg.callId);
      if (ac && !ac.signal.aborted) {
        ac.abort(new Error(msg.reason || "用户中断了会话"));
      }
      return;
    }
    if (msg?.type === "hostReply") {
      const waiter = pendingHostCalls.get(msg.id);
      if (!waiter) return;
      pendingHostCalls.delete(msg.id);
      if (msg.ok) waiter.resolve(msg.data);
      else waiter.reject(new Error(msg.error || "Host call failed"));
      return;
    }
    if (msg?.type !== "invoke") return;
    const {id, op, payload} = msg;
    try {
      let data = null;
      if (op === "activate") {
        const mod = await import(entryUrl);
        const fn = mod.activate || mod.apply || mod.default?.activate || mod.default;
        if (typeof fn === "function") {
          const ret = await Promise.resolve(fn(Object.freeze(ctx)));
          if (typeof ret === "function") registerEffect(ret, "activate-return");
        }
        data = {ok: true};
      } else if (op === "runCommand") {
        const spec = commands.get(payload.command);
        if (!spec) throw new Error(`Worker 未注册命令：${payload.command}`);
        data = typeof spec.handler === "function" ? await Promise.resolve(spec.handler(payload.ctx)) : {kind: "prompt", prompt: spec.promptTemplate || ""};
      } else if (op === "renderPanel") {
        const spec = panels.get(payload.panelId);
        if (!spec || typeof spec.render !== "function") throw new Error(`Worker 未注册面板渲染器：${payload.panelId}`);
        data = await Promise.resolve(spec.render(payload.ctx));
      } else if (op === "renderSurface") {
        const spec = surfaces.get(payload.surfaceId);
        if (!spec || typeof spec.render !== "function") throw new Error(`Worker 未注册原生页面：${payload.surfaceId}`);
        data = await Promise.resolve(spec.render(payload.ctx));
      } else if (op === "renderSlot") {
        const spec = slots.get(payload.slotId);
        if (!spec || typeof spec.render !== "function") throw new Error(`Worker 未注册槽位渲染器：${payload.slotId}`);
        data = await Promise.resolve(spec.render(payload.ctx));
      } else if (op === "invokeRpc") {
        const handler = rpcs.get(payload.method);
        if (typeof handler !== "function") throw new Error(`Worker 未注册 RPC：${payload.method}`);
        data = await Promise.resolve(handler(payload.params || {}));
      } else if (op === "beforePrompt") {
        let text = String(payload.text ?? "");
        const notices = [];
        const targets = payload.hookId
          ? (hooks.beforePrompt.has(payload.hookId) ? [hooks.beforePrompt.get(payload.hookId)] : [])
          : [...hooks.beforePrompt.values()];
        for (const fn of targets) {
          const r = await Promise.resolve(fn({...payload, text}));
          if (r && typeof r === "object") {
            if (typeof r.text === "string") text = r.text;
            if (typeof r.prepend === "string" && r.prepend.trim()) text = `${r.prepend.trim()}\n\n${text}`;
            if (typeof r.append === "string" && r.append.trim()) text = `${text}\n\n${r.append.trim()}`;
            if (typeof r.notice === "string" && r.notice.trim()) notices.push(r.notice.trim());
          }
        }
        data = {text, notices};
      } else if (op === "afterResponse") {
        const notices = [];
        const targets = payload.hookId
          ? (hooks.afterResponse.has(payload.hookId) ? [hooks.afterResponse.get(payload.hookId)] : [])
          : [...hooks.afterResponse.values()];
        for (const fn of targets) {
          const r = await Promise.resolve(fn({...payload}));
          if (r && typeof r.notice === "string" && r.notice.trim()) notices.push(r.notice.trim());
        }
        data = {notices};
      } else if (op === "invokeDriver") {
        const drv = drivers.get(payload.driverId);
        if (!drv || typeof drv.execute !== "function") throw new Error(`Worker 未注册可执行驱动：${payload.driverId}`);
        const callAbort = new AbortController();
        activeDriverAborts.set(id, callAbort);
        const emit = event => {
          if (!callAbort.signal.aborted) parentPort.postMessage({type: "driverEvent", callId: id, event});
        };
        try {
          data = await new Promise((resolve, reject) => {
            const onAbort = () => resolve({text: "", stopReason: "cancelled"});
            if (callAbort.signal.aborted) { onAbort(); return; }
            callAbort.signal.addEventListener("abort", onAbort, {once: true});
            Promise.resolve(drv.execute({
              ...payload.ctx,
              signal: callAbort.signal,
              emitChunk: text => emit({kind: "chunk", text: String(text ?? "")}),
              emitThought: text => emit({kind: "thought", text: String(text ?? "")}),
              emitTool: tool => emit({kind: "tool", tool}),
              emitPlan: plan => emit({kind: "plan", plan}),
            })).then(
              v => { callAbort.signal.removeEventListener("abort", onAbort); resolve(v); },
              e => { callAbort.signal.removeEventListener("abort", onAbort); reject(e); },
            );
          });
        } finally {
          activeDriverAborts.delete(id);
        }
      } else if (op === "listDriverModels") {
        const drv = drivers.get(payload.driverId);
        data = typeof drv?.listModels === "function" ? await Promise.resolve(drv.listModels()) : [];
      } else if (op === "translateRequest") {
        const tr = translators.get(payload.translatorId);
        if (!tr || typeof tr.toUpstream !== "function") throw new Error(`Worker 未注册协议请求转换器：${payload.translatorId}`);
        data = await Promise.resolve(tr.toUpstream(payload.request, payload.context || {}));
      } else if (op === "translateResponse") {
        const tr = translators.get(payload.translatorId);
        if (!tr || typeof tr.fromUpstream !== "function") throw new Error(`Worker 未注册协议响应转换器：${payload.translatorId}`);
        data = await Promise.resolve(tr.fromUpstream(payload.response, payload.context || {}));
      } else if (op === "updateSettings") {
        currentSettings = {...(payload.settings || {})};
        for (const fn of settingsListeners) { try { fn({...currentSettings}); } catch {} }
        data = {ok: true};
      } else if (op === "dispose") {
        abortController.abort();
        for (const item of effects.splice(0).reverse()) { try { await Promise.resolve(item.fn()); } catch {} }
        data = {ok: true};
      } else {
        throw new Error(`未知 Worker 操作：${op}`);
      }
      parentPort.postMessage({type: "invokeReply", id, ok: true, data});
    } catch (err) {
      parentPort.postMessage({type: "invokeReply", id, ok: false, error: err?.message || String(err)});
    }
  });
}

/** 在主线程启动受硬超时强杀保护的插件 Worker 线程。 */
export function spawnIsolatedPluginWorker({pluginId, entryUrl, hostInfo, grantedCapabilities, settings, onLog, onRegister, onHostCall, onCrash}) {
  const worker = new Worker(new URL(import.meta.url), {
    workerData: {pluginId, entryUrl, hostInfo, grantedCapabilities, settings},
    resourceLimits: {
      maxOldGenerationSizeMb: 128,
      maxYoungGenerationSizeMb: 32,
      codeRangeSizeMb: 32,
    },
  });
  let dead = false;
  const pending = new Map();

  function failAll(reason) {
    if (dead) return;
    dead = true;
    for (const [, waiter] of pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(reason));
    }
    pending.clear();
    if (typeof onCrash === "function") onCrash(reason);
  }

  worker.on("message", msg => {
    if (msg?.type === "log") {
      onLog?.(msg.level || "info", msg.message || "");
    } else if (msg?.type === "register") {
      try {
        onRegister?.(msg.kind, msg.spec);
      } catch (err) {
        onLog?.("error", `注册 ${msg.kind} 失败：${err?.message || err}`);
      }
    } else if (msg?.type === "driverEvent") {
      pending.get(msg.callId)?.onEvent?.(msg.event);
    } else if (msg?.type === "hostCall") {
      Promise.resolve()
        .then(() => onHostCall(msg.op, msg.payload || {}))
        .then(data => { if (!dead) worker.postMessage({type: "hostReply", id: msg.id, ok: true, data}); })
        .catch(err => { if (!dead) worker.postMessage({type: "hostReply", id: msg.id, ok: false, error: err?.message || String(err)}); });
    } else if (msg?.type === "invokeReply") {
      const waiter = pending.get(msg.id);
      if (!waiter) return;
      pending.delete(msg.id);
      clearTimeout(waiter.timer);
      if (msg.ok) waiter.resolve(msg.data);
      else waiter.reject(new Error(msg.error || "Worker 执行失败"));
    }
  });
  worker.on("error", err => failAll(`插件隔离线程异常：${err?.message || err}`));
  worker.on("exit", code => { if (!dead) failAll(`插件隔离线程意外退出（code=${code}）`); });

  function invoke(op, payload = {}, timeoutMs = DEFAULT_CALL_TIMEOUT_MS, onEvent = null, signal = null) {
    if (dead) return Promise.reject(new Error("插件隔离工作线程已终止"));
    if (signal?.aborted) return Promise.resolve({text: "", stopReason: "cancelled"});
    return new Promise((resolve, reject) => {
      const id = randomUUID();
      let onAbort = null;
      const cleanup = () => {
        clearTimeout(timer);
        if (signal && onAbort) signal.removeEventListener("abort", onAbort);
      };
      const timer = setTimeout(() => {
        pending.delete(id);
        cleanup();
        const msg = `插件 ${pluginId} 执行 ${op} 超时（>${timeoutMs}ms），已强制终止隔离线程`;
        void worker.terminate().catch(() => {});
        failAll(msg);
        reject(new Error(msg));
      }, timeoutMs);
      if (signal) {
        onAbort = () => {
          if (!pending.has(id)) return;
          pending.delete(id);
          cleanup();
          try { if (!dead) worker.postMessage({type: "cancel", callId: id, reason: "用户中断了会话"}); } catch {}
          resolve({text: "", stopReason: "cancelled"});
        };
        signal.addEventListener("abort", onAbort, {once: true});
      }
      pending.set(id, {
        resolve: v => { cleanup(); resolve(v); },
        reject: e => { cleanup(); reject(e); },
        timer,
        onEvent,
      });
      try {
        worker.postMessage({type: "invoke", id, op, payload});
      } catch (postErr) {
        pending.delete(id);
        cleanup();
        reject(postErr instanceof Error ? postErr : new Error(String(postErr)));
      }
    });
  }

  async function terminate() {
    if (dead) return;
    try { await invoke("dispose", {}, 1500); } catch {}
    dead = true;
    for (const [, w] of pending) { clearTimeout(w.timer); w.reject(new Error("插件已卸载")); }
    pending.clear();
    await worker.terminate().catch(() => {});
  }

  return {invoke, terminate, isDead: () => dead};
}
