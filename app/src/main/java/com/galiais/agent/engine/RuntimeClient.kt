package com.galiais.agent.engine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.galiais.agent.CliRpcException
import com.galiais.agent.EnvironmentStatus
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.LinuxRuntime
import com.galiais.agent.RootAccessResult
import com.galiais.agent.RootAccessStatus
import com.galiais.agent.VmRuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 开放 Agent 标识：内置 Codex/Claude 为常量，第三方 ACP / CLI Bridge Agent 动态扩展。 */
data class EngineKind(val id: String, val label: String) {
    override fun equals(other: Any?): Boolean = other is EngineKind && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        val CODEX = EngineKind("codex", "Codex")
        val CLAUDE = EngineKind("claude", "Claude Code")
        val HUB = EngineKind("agent-hub", "Agent Hub")
        val entries: List<EngineKind> get() = AgentCatalog.allEngineKinds()
        fun of(id: String): EngineKind = AgentCatalog.engineKindOf(id)
    }
}

/** 运行环境快照；含运行模式、配置阶段与确定性进度（0f..1f）。 */
data class RuntimeSnapshot(
    val state: String,
    val message: String,
    val engineInfo: JSONObject? = null,
    val bound: Boolean = false,
    val reattached: Boolean = false,
    val mode: String = RUNTIME_MODE_ROOT,
    val progress: Float? = null,
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val stepLabel: String = "",
    val firstTimeSetup: Boolean = false,
) {
    val ready get() = state == "ready"
    val busy get() = state in setOf("preparing", "downloading", "starting-vm", "starting-engine", "backing-up", "stopping", "maintenance")
    val maintenance get() = state in setOf("maintenance", "backing-up")
    val idle get() = state == "stopped" || state == "error"
}

/** 引擎发来的通知（无 id）或服务端请求（有 id，需要 respond/reject）。 */
data class EngineEvent(val engine: EngineKind, val data: JSONObject) {
    val method: String get() = data.optString("method")
    val params: JSONObject get() = data.optJSONObject("params") ?: JSONObject()
    val isRequest: Boolean get() = data.has("id") && data.has("method")
}

class EngineException(val code: Int, message: String) : Exception(message)

/**
 * 应用级的运行服务客户端：绑定 VmRuntimeService、管理双模式（Root 优先 / 免 Root PRoot）、
 * 首次进入 Root 探测、环境巡检与维护操作。
 */
class RuntimeClient(val app: Application) {
    private val snapshotFlow = MutableStateFlow(RuntimeSnapshot("connecting", "正在连接本地运行服务"))
    val snapshot: StateFlow<RuntimeSnapshot> = snapshotFlow.asStateFlow()
    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.SUSPEND)
    val events: SharedFlow<EngineEvent> = eventFlow.asSharedFlow()
    private val hubEventFlow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    val hubEvents: SharedFlow<JSONObject> = hubEventFlow.asSharedFlow()
    private val environmentFlow = MutableStateFlow(EnvironmentStatus())
    val environment: StateFlow<EnvironmentStatus> = environmentFlow.asStateFlow()
    private val rootAvailableFlow = MutableStateFlow<Boolean?>(null)
    /** 设备 Root 可用性探测结果：null 表示探测中。 */
    val rootAvailable: StateFlow<Boolean?> = rootAvailableFlow.asStateFlow()
    private val rootAccessFlow = MutableStateFlow<RootAccessResult?>(null)
    val rootAccess: StateFlow<RootAccessResult?> = rootAccessFlow.asStateFlow()
    private val requestingRootFlow = MutableStateFlow(false)
    val requestingRoot: StateFlow<Boolean> = requestingRootFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inspectJob: Job? = null
    private var probeJob: Job? = null
    private val rootCheckMutex = Mutex()
    @Volatile private var service: VmRuntimeService? = null
    private var observer: AutoCloseable? = null
    @Volatile private var autoStartRequested = false
    @Volatile private var autoStartHandled = false

    private val settingsState get() = (app as GaliaisApplication).settings.state.value

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val runtime = (binder as VmRuntimeService.LocalBinder).runtime
            service = runtime
            observer?.close()
            observer = runtime.observe(::receive)
            maybeAutoStart()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            observer?.close(); observer = null; service = null
            snapshotFlow.value = RuntimeSnapshot("error", "运行服务已断开，任务未自动重试。请重新连接后核对任务状态。")
        }
    }

    init {
        scope.launch {
            snapshot.map { it.state }.distinctUntilChanged().collect { state ->
                if (state in setOf("stopped", "ready", "error")) refreshEnvironment()
            }
        }
    }

    fun bind() {
        if (!app.bindService(Intent(app, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            snapshotFlow.value = RuntimeSnapshot("error", "无法连接本地运行服务")
    }

    private fun receive(text: String) {
        val packet = try { JSONObject(text) } catch (error: Exception) { Log.e("GALIAIS", "运行服务消息无效", error); return }
        when (packet.optString("kind")) {
            "runtime-state" -> {
                val p = if (packet.isNull("progress")) null else packet.optDouble("progress", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                snapshotFlow.value = RuntimeSnapshot(
                    state = packet.getString("state"),
                    message = packet.getString("message"),
                    engineInfo = packet.optJSONObject("engineInfo"),
                    bound = true,
                    reattached = packet.optBoolean("reattached"),
                    mode = packet.optString("mode", settingsState.effectiveRuntimeMode),
                    progress = p,
                    stepIndex = packet.optInt("stepIndex", 0),
                    stepTotal = packet.optInt("stepTotal", 0),
                    stepLabel = packet.optString("stepLabel", ""),
                    firstTimeSetup = packet.optBoolean("firstTimeSetup", false),
                )
            }
            "engine-event" -> {
                val engId = packet.getString("engine")
                val data = packet.getJSONObject("data")
                if (engId == "agent-hub") {
                    if (!hubEventFlow.tryEmit(data)) {
                        Log.e("GALIAIS", "Agent Hub 事件队列已满，停止运行环境以免丢失审批或传输数据")
                        stop()
                    }
                } else {
                    val event = EngineEvent(EngineKind.of(engId), data)
                    if (!eventFlow.tryEmit(event)) {
                        Log.e("GALIAIS", "界面事件队列已满，停止运行环境以免状态不一致")
                        stop()
                    }
                }
            }
        }
    }

    /** 异步探测设备 Root 权限（8 秒超时，失败不会抛出异常）。 */
    fun probeRoot(force: Boolean = false) {
        if (!force && rootAvailableFlow.value != null) return
        if (probeJob?.isActive == true || requestingRootFlow.value) return
        probeJob = scope.launch {
            rootCheckMutex.withLock {
                rootAvailableFlow.value = null
                rootAccessFlow.value = null
                checkRoot(8)
            }
        }
    }

    private suspend fun checkRoot(timeoutSeconds: Long): Boolean {
        val result = try { runInterruptible { LinuxRuntime.checkRootAccess(timeoutSeconds) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            Log.w("GALIAIS", "检查 Root 运行能力失败", error)
            RootAccessResult(RootAccessStatus.UNCONFIRMED)
        }
        rootAccessFlow.value = result
        rootAvailableFlow.value = result.available
        return result.available
    }

    /** 主动申请授权并核对运行能力；取消尚未结束的探测，避免重叠的授权请求。 */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        probeJob?.cancelAndJoin()
        rootCheckMutex.withLock {
            requestingRootFlow.value = true
            rootAvailableFlow.value = null
            rootAccessFlow.value = null
            try { checkRoot(25) }
            finally { requestingRootFlow.value = false }
        }
    }

    /**
     * 由前台界面调用：用户选定运行模式后，已完整安装时按设置自动启动；
     * 若后台仍有常驻虚拟机则直接重新挂接。尚未选择模式或尚未安装时绝不自动下载。
     */
    fun requestAutoStart() {
        autoStartRequested = true
        autoStartHandled = false
        maybeAutoStart()
    }

    private fun maybeAutoStart() {
        val runtime = service ?: return
        if (!autoStartRequested || autoStartHandled) return
        val chosenMode = settingsState.runtimeMode ?: return
        autoStartHandled = true
        scope.launch {
            val linux = LinuxRuntime(app, chosenMode)
            val installed = linux.ready && linux.vmProvisioned
            val detachedVm = installed && runCatching { android.system.Os.lstat(linux.daemonSocketFile.absolutePath); true }.getOrDefault(false)
            val autoStart = settingsState.autoStartRuntime
            if (installed && (autoStart || detachedVm) && runtime.state == "stopped") {
                withContext(Dispatchers.Main) {
                    try { start(restartUpdatedRuntime = true) } catch (error: Exception) { Log.w("GALIAIS", "自动启动运行环境失败", error) }
                }
            }
        }
    }

    /** 后台刷新当前运行模式的环境状态与磁盘占用（只读，无需 Root）。 */
    fun refreshEnvironment(modeOverride: String? = null) {
        val targetMode = modeOverride ?: settingsState.effectiveRuntimeMode
        inspectJob?.cancel()
        inspectJob = scope.launch {
            environmentFlow.value = environmentFlow.value.copy(mode = targetMode, loading = true)
            environmentFlow.value = try { LinuxRuntime(app, targetMode).inspect() } catch (error: Exception) {
                Log.w("GALIAIS", "环境巡检失败", error); environmentFlow.value.copy(mode = targetMode, loading = false)
            }
        }
    }

    /**
     * 切换运行模式（Root / 免 Root PRoot）：
     * 默认全自动完成「停止旧模式 → 校验 Root 权限（若需）→ 自动安装离线内置环境（若未安装）→ 启动全部引擎」，
     * 无需用户切换后再手动点击「安装运行环境」或「启动运行环境」。
     */
    fun switchMode(nextMode: String, autoStart: Boolean = true) {
        val galiais = app as GaliaisApplication
        val settings = galiais.settings
        val sameMode = settings.state.value.runtimeMode == nextMode
        if (sameMode) {
            if (autoStart && snapshotFlow.value.idle) start()
            return
        }
        settings.setRuntimeMode(nextMode)
        refreshEnvironment(nextMode)
        if (!autoStart) {
            if (!snapshotFlow.value.idle) stop()
            if (nextMode == RUNTIME_MODE_ROOT && rootAvailableFlow.value != true) {
                scope.launch { requestRoot() }
            }
            return
        }
        if (nextMode == RUNTIME_MODE_ROOT && rootAvailableFlow.value != true) {
            scope.launch {
                val granted = requestRoot()
                withContext(Dispatchers.Main) {
                    if (granted) {
                        restart(nextMode)
                    } else {
                        if (!snapshotFlow.value.idle) stop()
                        galiais.conversations.notify(
                            rootAccessFlow.value?.message ?: "未获得 Root 权限，请授权后重试或切换回免 Root 模式",
                        )
                    }
                }
            }
        } else {
            restart(nextMode)
        }
    }

    suspend fun clearDownloadCache(): Long {
        val state = snapshotFlow.value.state
        check(state in setOf("stopped", "error", "ready")) { "正在准备或维护运行环境，请稍后再清理缓存" }
        val freed = withContext(Dispatchers.IO) { LinuxRuntime(app, settingsState.effectiveRuntimeMode).clearDownloadCache() }
        refreshEnvironment()
        return freed
    }

    suspend fun clearAppCache(): Long {
        val state = snapshotFlow.value.state
        check(state in setOf("stopped", "error", "ready")) { "正在准备或维护运行环境，请稍后再清理缓存" }
        val freed = withContext(Dispatchers.IO) { LinuxRuntime(app, settingsState.effectiveRuntimeMode).clearAppCache() }
        refreshEnvironment()
        return freed
    }

    fun maintain(operation: String, deleteCache: Boolean, restart: Boolean) {
        require(operation in setOf(VmRuntimeService.OP_RESET_VM, VmRuntimeService.OP_DELETE_ALL, VmRuntimeService.OP_RESTORE_LEGACY)) { "未知维护操作" }
        app.startForegroundService(Intent(app, VmRuntimeService::class.java).setAction(VmRuntimeService.MAINTAIN)
            .putExtra(VmRuntimeService.EXTRA_OPERATION, operation)
            .putExtra(VmRuntimeService.EXTRA_DELETE_CACHE, deleteCache)
            .putExtra(VmRuntimeService.EXTRA_RESTART, restart)
            .putExtra(VmRuntimeService.EXTRA_MODE, settingsState.effectiveRuntimeMode))
    }

    fun applyKeepAlive(keep: Boolean) { service?.applyKeepAlive(keep) }

    fun setActiveTurnRunning(active: Boolean) { service?.setActiveTurnRunning(active) }

    private fun connected(): VmRuntimeService = service ?: throw EngineException(-32000, "本地运行服务未连接")

    suspend fun request(engine: EngineKind, method: String, params: JSONObject? = null): Any? = try {
        connected().request(method, params, engine.id).await()
    } catch (error: CliRpcException) {
        throw EngineException(error.code, error.message ?: "引擎请求失败")
    } catch (error: java.util.concurrent.CompletionException) {
        val cause = error.cause
        if (cause is CliRpcException) throw EngineException(cause.code, cause.message ?: "引擎请求失败")
        throw cause ?: error
    }

    suspend fun requestObject(engine: EngineKind, method: String, params: JSONObject? = null): JSONObject =
        request(engine, method, params) as? JSONObject ?: throw EngineException(-32603, "${engine.label} 返回了无效响应：$method")

    suspend fun gatewayRequest(method: String, params: JSONObject? = null): JSONObject = try {
        connected().request(method, params, "gateway").await() as? JSONObject ?: throw EngineException(-32603, "协议网关返回了无效响应：$method")
    } catch (error: CliRpcException) {
        throw EngineException(error.code, error.message ?: "协议网关请求失败")
    } catch (error: java.util.concurrent.CompletionException) {
        val cause = error.cause
        if (cause is CliRpcException) throw EngineException(cause.code, cause.message ?: "协议网关请求失败")
        throw cause ?: error
    }

    suspend fun hubRequest(method: String, params: JSONObject? = null, timeoutMs: Long = 120_000): JSONObject = try {
        connected().request(method, params, "agent-hub", timeoutMs).await() as? JSONObject ?: throw EngineException(-32603, "Agent Hub 返回了无效响应：$method")
    } catch (error: CliRpcException) {
        throw EngineException(error.code, error.message ?: "Agent 服务请求失败")
    } catch (error: java.util.concurrent.CompletionException) {
        val cause = error.cause
        if (cause is CliRpcException) throw EngineException(cause.code, cause.message ?: "Agent 服务请求失败")
        throw cause ?: error
    }

    fun hubRespond(id: Any, result: Any?) = connected().respond(id, result, "agent-hub")
    fun hubReject(id: Any, code: Int, message: String) = connected().reject(id, code, message, "agent-hub")

    val hubAvailable: Boolean
        get() = snapshotFlow.value.engineInfo?.optJSONObject("agentHub")?.has("error") == false

    /** 实时读取底层前台服务状态，消除异步快照延迟窗口下的误判。 */
    val isServiceReady: Boolean
        get() = service?.state == "ready"

    val gatewayEndpoint: Pair<Int, String>?
        get() {
            val gw = snapshotFlow.value.engineInfo?.optJSONObject("gateway") ?: return null
            val port = gw.optInt("port", 0)
            val token = gw.optString("token")
            return if (port in 1..65535 && token.isNotEmpty()) port to token else null
        }

    fun respond(engine: EngineKind, id: Any, result: Any?) = connected().respond(id, result, engine.id)
    fun reject(engine: EngineKind, id: Any, code: Int, message: String) = connected().reject(id, code, message, engine.id)

    fun start() = start(restartUpdatedRuntime = true)

    private fun start(restartUpdatedRuntime: Boolean) {
        app.startForegroundService(Intent(app, VmRuntimeService::class.java).setAction(VmRuntimeService.START)
            .putExtra(VmRuntimeService.EXTRA_MODE, settingsState.effectiveRuntimeMode)
            .putExtra(VmRuntimeService.EXTRA_RESTART_UPDATED_RUNTIME, restartUpdatedRuntime))
    }
    fun stop() {
        app.startService(Intent(app, VmRuntimeService::class.java).setAction(VmRuntimeService.STOP))
    }

    fun restart() = restart(modeOverride = null)

    fun restart(modeOverride: String?) {
        app.startForegroundService(
            Intent(app, VmRuntimeService::class.java)
                .setAction(VmRuntimeService.RESTART)
                .putExtra(VmRuntimeService.EXTRA_MODE, modeOverride ?: settingsState.effectiveRuntimeMode),
        )
    }
}
