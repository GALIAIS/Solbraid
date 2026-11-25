package com.galiais.agent.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    init {
        repo.scope.launch {
            runtime.hubEvents.collect { packet ->
                if (packet.optString("method") == "cli/status") {
                    try { accept(packet.getJSONObject("params")) }
                    catch (error: Exception) { repo.notify("读取 CLI 更新状态失败：" + (error.message ?: "响应无效")) }
                }
            }
        }
    }
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
                repo.notify("已更新并热加载 ${updated.installedVersion}，无需重启")
            } else if (repo.conversations.value.values.none { it.running } && !restartingFlow.value) {
                repo.notify("更新已安装，正在自动重启运行环境使其生效…")
                restart()
            } else {
                repo.notify("更新已安装，当前有会话运行中，完成后点「重启生效」即可")
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
            repo.notify("已热切回内置版本 ${updated.installedVersion}，无需重启")
        } else if (repo.conversations.value.values.none { it.running } && !restartingFlow.value) {
            repo.notify("已选择内置版本，正在自动重启运行环境使其生效…")
            restart()
        } else {
            repo.notify("已选择内置版本，当前有会话运行中，完成后点「重启生效」即可")
        }
    }
    fun restart() = repo.launchOperation("重启运行环境失败") {
        check(!restartingFlow.value) { "正在重启运行环境" }
        check(statesFlow.value.values.none { it.installing }) { "请等待更新完成" }
        restartingFlow.value = true
        try {
            runtime.stop()
            val stopped = withTimeout(60_000) { runtime.snapshot.first { it.state == "stopped" || it.state == "error" } }
            check(stopped.state == "stopped") { "无法确认旧环境已停止，请在运行环境页重试" }
            runtime.start()
        } finally { restartingFlow.value = false }
    }
    fun disconnected() { statesFlow.value = emptyMap() }
}
