package com.galiais.agent.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class CliUpdateState(
    val id: String,
    val currentVersion: String,
    val installedVersion: String,
    val latestVersion: String?,
    val phase: String,
    val updateAvailable: Boolean,
    val pendingRestart: Boolean,
    val bundled: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val checkedAt: Long?,
    val error: String?,
    val canRestore: Boolean,
) {
    val working: Boolean get() = phase in setOf("checking", "downloading", "verifying", "publishing", "cancelling")
    val installing: Boolean get() = phase in setOf("downloading", "verifying", "publishing", "cancelling")
    val label: String get() = when (phase) {
        "checking" -> "正在检查更新"
        "downloading" -> "正在下载"
        "verifying" -> "正在校验"
        "publishing" -> "正在安装"
        "cancelling" -> "正在取消"
        "cancelled" -> "已取消更新"
        "failed" -> "更新失败"
        else -> ""
    }
    companion object {
        fun fromJson(value: JSONObject): CliUpdateState {
            val id = value.getString("id")
            require(id in setOf("codex", "claude", "antigravity")) { "更新返回了未知 CLI" }
            return CliUpdateState(
                id, value.optString("currentVersion"), value.optString("installedVersion"),
                value.str("latestVersion"), value.optString("phase", "idle"),
                value.optBoolean("updateAvailable"), value.optBoolean("pendingRestart"), value.optBoolean("bundled", true),
                value.optLong("downloadedBytes"), value.optLong("totalBytes").takeUnless { value.isNull("totalBytes") },
                value.optLong("checkedAt").takeUnless { value.isNull("checkedAt") }, value.str("error"),
                value.optBoolean("canRestore"),
            )
        }
    }
}

class CliUpdateRepository(private val repo: ConversationRepository) {
    private val runtime get() = repo.runtime
    private val statesFlow = MutableStateFlow<Map<String, CliUpdateState>>(emptyMap())
    val states = statesFlow.asStateFlow()
    private val restartingFlow = MutableStateFlow(false)
    val restarting = restartingFlow.asStateFlow()
    private val reloadingEngineFlow = MutableStateFlow<String?>(null)
    /** 正在执行单引擎热重载的引擎 ID（"codex" / "claude"），null 表示空闲。 */
    val reloadingEngine = reloadingEngineFlow.asStateFlow()
    private val deferredReloadIds = mutableSetOf<String>()
    private val reloadMutex = kotlinx.coroutines.sync.Mutex()

    init {
        repo.scope.launch {
            runtime.hubEvents.collect { packet ->
                if (packet.optString("method") == "cli/status") {
                    try { accept(packet.getJSONObject("params")) }
                    catch (error: Exception) { repo.notify("读取 CLI 更新状态失败：" + (error.message ?: "响应无效")) }
                }
            }
        }
        // 监听各引擎任务完成状态：若此前因任务进行中延后了热重载，本轮结束后自动完成无感切换
        repo.scope.launch {
            repo.conversations.collect { convMap ->
                if (deferredReloadIds.isEmpty() || !runtime.isServiceReady) return@collect
                val readyIds = synchronized(deferredReloadIds) {
                    deferredReloadIds.filter { id ->
                        convMap.values.none { it.summary.key.engine.id == id && it.running }
                    }.toList()
                }
                for (id in readyIds) {
                    synchronized(deferredReloadIds) { deferredReloadIds.remove(id) }
                    repo.scope.launch {
                        try { performSingleEngineHotReload(id, automatic = true) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { repo.notify("自动热重载 ${engineLabel(id)} 失败：${error.message ?: "请手动点击热重载"}") }
                    }
                }
            }
        }
    }
    private fun engineLabel(id: String): String = when (id) {
        "codex" -> "Codex"
        "claude" -> "Claude Code"
        "antigravity" -> "Antigravity"
        else -> id
    }
    private fun isEngineBusy(id: String): Boolean =
        repo.conversations.value.values.any { it.summary.key.engine.id == id && it.running }
    private fun accept(value: JSONObject) {
        val next = CliUpdateState.fromJson(value)
        statesFlow.value = statesFlow.value + (next.id to next)
    }
    suspend fun refresh() {
        if (!runtime.hubAvailable) return
        val response = runtime.hubRequest("cli/status", JSONObject(), timeoutMs = 15_000)
        response.arr("updates").objects().forEach(::accept)
    }
    fun check(agentId: String? = null) = repo.launchOperation("检查 CLI 更新失败") {
        check(runtime.hubAvailable) { "请先启动运行环境" }
        repo.envSsh.syncBeforeLaunch()
        val response = runtime.hubRequest("cli/check", jsonOf("agentId" to agentId), timeoutMs = 100_000)
        response.arr("updates").objects().forEach(::accept)
    }
    fun install(id: String, version: String) = repo.scope.launch {
        try {
            val response = runtime.hubRequest("cli/install", jsonOf("agentId" to id, "version" to version), timeoutMs = 1_500_000)
            val updated = CliUpdateState.fromJson(response.getJSONObject("update"))
            statesFlow.value = statesFlow.value + (updated.id to updated)
            if (!updated.pendingRestart) {
                repo.notify("${engineLabel(id)} ${updated.installedVersion} 已生效")
            } else if (id in setOf("codex", "claude")) {
                if (isEngineBusy(id)) {
                    synchronized(deferredReloadIds) { deferredReloadIds.add(id) }
                    repo.notify("${engineLabel(id)} ${updated.installedVersion} 已安装，本轮结束后生效")
                } else {
                    performSingleEngineHotReload(id, automatic = true)
                }
            } else {
                repo.notify("更新已安装（${updated.installedVersion}），待重启生效；请在合适时机点击「重启生效」")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (statesFlow.value[id]?.phase !in setOf("cancelled", "cancelling")) repo.notify("CLI 更新失败：" + (error.message ?: "请重试"))
        }
    }
    fun cancel(id: String) = repo.launchOperation("取消更新失败") {
        runtime.hubRequest("cli/cancel", jsonOf("agentId" to id), timeoutMs = 10_000)
    }
    fun restore(id: String) = repo.launchOperation("恢复内置版本失败") {
        val response = runtime.hubRequest("cli/restore", jsonOf("agentId" to id), timeoutMs = 15_000)
        val updated = CliUpdateState.fromJson(response.getJSONObject("update"))
        statesFlow.value = statesFlow.value + (updated.id to updated)
        if (!updated.pendingRestart) {
            repo.notify("已热切回内置版本 ${engineLabel(id)} ${updated.installedVersion}，无需重启")
        } else if (id in setOf("codex", "claude")) {
            if (isEngineBusy(id)) {
                synchronized(deferredReloadIds) { deferredReloadIds.add(id) }
                repo.notify("已选择内置版本 ${updated.installedVersion}，当前任务结束后切换")
            } else {
                performSingleEngineHotReload(id, automatic = true)
            }
        } else {
            repo.notify("已选择内置版本（${updated.installedVersion}），待重启生效；请在合适时机点击「重启生效」")
        }
    }

    /** 立即对单个引擎（codex / claude）执行无感热重载，不重启运行环境、不中断终端 PTY 与 SSH。 */
    fun reloadEngine(id: String) = repo.launchOperation("热重载引擎失败") {
        require(id in setOf("codex", "claude")) { "仅支持 Codex 与 Claude Code 单引擎热重载" }
        check(runtime.isServiceReady) { "请先启动运行环境" }
        check(statesFlow.value.values.none { it.installing }) { "请等待更新完成" }
        check(!isEngineBusy(id)) { "${engineLabel(id)} 当前有任务正在执行，请等待本轮结束或中断后再热重载" }
        synchronized(deferredReloadIds) { deferredReloadIds.remove(id) }
        performSingleEngineHotReload(id, automatic = false)
    }

    private suspend fun performSingleEngineHotReload(id: String, automatic: Boolean) = reloadMutex.withLock {
      repo.withEngineOperation(id) {
        if (!runtime.isServiceReady) return@withEngineOperation
        if (isEngineBusy(id)) {
            if (automatic) synchronized(deferredReloadIds) { deferredReloadIds.add(id) }
            return@withEngineOperation
        }
        reloadingEngineFlow.value = id
        try {
            val res = runtime.reloadSingleEngine(id)
            when (id) {
                "codex" -> {
                    repo.codex.reset()
                    runCatching { repo.syncProvidersNow() }
                }
                "claude" -> {
                    repo.claude.reset()
                }
            }
            val appliedResp = runtime.hubRequest("cli/markApplied", jsonOf("agentId" to id), timeoutMs = 15_000)
            val applied = CliUpdateState.fromJson(appliedResp.getJSONObject("update"))
            statesFlow.value = statesFlow.value + (applied.id to applied)
            val rolledBack = res.optBoolean("rolledBack", false)
            val warn = res.str("warning")
            if (rolledBack) {
                repo.notify(warn ?: "${engineLabel(id)} 启动失败，已恢复内置版本 ${applied.currentVersion}")
            } else {
                repo.notify("${engineLabel(id)} ${applied.currentVersion} 已生效")
            }
        } finally {
            reloadingEngineFlow.value = null
        }
      }
    }

    fun restart() = repo.launchOperation("重启运行环境失败") {
        check(!restartingFlow.value) { "正在重启运行环境" }
        check(statesFlow.value.values.none { it.installing }) { "请等待更新完成" }
        restartingFlow.value = true
        try {
            val wasReady = runtime.snapshot.value.ready
            runtime.restart()
            if (wasReady) {
                withTimeout(15_000) { runtime.snapshot.first { !it.ready } }
            }
            val settled = withTimeout(120_000) { runtime.snapshot.first { it.ready || it.state == "error" } }
            check(settled.ready) { settled.message.ifBlank { "重启运行环境未就绪，请在运行环境页重试" } }
        } finally { restartingFlow.value = false }
    }
    fun disconnected() {
        synchronized(deferredReloadIds) { deferredReloadIds.clear() }
        reloadingEngineFlow.value = null
        statesFlow.value = emptyMap()
    }
}
