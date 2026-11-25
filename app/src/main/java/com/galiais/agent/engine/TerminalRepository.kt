package com.galiais.agent.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class TerminalSession(
    val id: String,
    val cwd: String,
    val sshHostId: String?,
    val remoteCwd: String = "",
    val title: String,
    val cols: Int,
    val rows: Int,
    val status: String,
    val sequence: Long,
    val exitCode: Int?,
    val error: String?,
) {
    val running: Boolean get() = status == "running"
    companion object {
        fun fromJson(value: JSONObject) = TerminalSession(
            id = value.getString("ptyId"), cwd = value.getString("cwd"),
            sshHostId = value.str("sshHostId"), remoteCwd = value.optString("remoteCwd", ""),
            title = value.optString("title", "终端"),
            cols = value.getInt("cols"), rows = value.getInt("rows"), status = value.getString("status"),
            sequence = value.optLong("sequence"), exitCode = value.optInt("exitCode").takeUnless { value.isNull("exitCode") },
            error = value.str("error"),
        )
    }
}

data class TerminalOutput(val id: String, val sequence: Long, val dataBase64: String) {
    fun toJson() = jsonOf("ptyId" to id, "sequence" to sequence, "dataBase64" to dataBase64)
}

/** 后台 PTY 持有屏幕状态，界面离开或输出丢帧后可按序号恢复完整屏幕。 */
class TerminalRepository(
    private val runtime: RuntimeClient,
    private val scope: CoroutineScope,
    private val prepare: suspend (Boolean) -> Unit,
) {
    private val sessionsFlow = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions = sessionsFlow.asStateFlow()
    private val outputsFlow = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outputs = outputsFlow.asSharedFlow()
    private val inputMutex = Mutex()
    private var pendingInput = 0

    fun onPacket(method: String, params: JSONObject) {
        when (method) {
            "pty/sessionsChanged" -> sessionsFlow.value = params.arr("sessions").objects().map(TerminalSession::fromJson)
            "pty/output" -> {
                val data = params.getString("dataBase64")
                require(data.length <= 256 * 1024) { "终端数据超过单帧上限" }
                outputsFlow.tryEmit(TerminalOutput(params.getString("ptyId"), params.getLong("sequence"), data))
            }
            "pty/closed" -> {
                val ended = TerminalSession.fromJson(params)
                sessionsFlow.value = sessionsFlow.value.map { if (it.id == ended.id) ended else it }
            }
        }
    }

    suspend fun refresh() {
        check(runtime.hubAvailable) { "请先启动运行环境" }
        val response = runtime.hubRequest("pty/list", JSONObject(), timeoutMs = 10_000)
        sessionsFlow.value = response.arr("sessions").objects().map(TerminalSession::fromJson)
    }

    suspend fun open(cwd: String, host: SshHostConfig? = null, cols: Int = 80, rows: Int = 24): String = scope.async {
        prepare(host != null)
        val safeCols = cols.coerceIn(20, 500)
        val safeRows = rows.coerceIn(6, 300)
        val response = runtime.hubRequest("pty/open", jsonOf(
            "cwd" to cwd, "cols" to safeCols, "rows" to safeRows,
            "sshHostId" to host?.id, "sshAlias" to host?.alias, "remoteCwd" to host?.defaultPath,
        ), timeoutMs = 25_000)
        val session = TerminalSession.fromJson(response.getJSONObject("session"))
        sessionsFlow.value = sessionsFlow.value.filterNot { it.id == session.id } + session
        session.id
    }.await()

    suspend fun attach(id: String): JSONObject =
        runtime.hubRequest("pty/attach", jsonOf("ptyId" to id), timeoutMs = 10_000)

    suspend fun write(id: String, data: String, binary: Boolean = false) {
        require(data.length <= 256 * 1024) { "粘贴内容过长，请分段输入" }
        check(pendingInput < 128) { "终端正在处理输入，请稍后重试" }
        pendingInput++
        try {
            inputMutex.withLock {
                runtime.hubRequest("pty/write", jsonOf("ptyId" to id, (if (binary) "dataBase64" else "data") to data), timeoutMs = 10_000)
            }
        } finally { pendingInput-- }
    }

    suspend fun resize(id: String, cols: Int, rows: Int) {
        require(cols in 2..500 && rows in 1..300) { "终端尺寸无效" }
        runtime.hubRequest("pty/resize", jsonOf("ptyId" to id, "cols" to cols, "rows" to rows), timeoutMs = 5000)
    }

    suspend fun close(id: String) = scope.async {
        check(runtime.hubAvailable) { "运行环境未连接，无法确认终端已结束" }
        runtime.hubRequest("pty/close", jsonOf("ptyId" to id), timeoutMs = 10_000)
        sessionsFlow.value = sessionsFlow.value.filterNot { it.id == id }
    }.await()

    suspend fun closeAll() = scope.async {
        check(runtime.hubAvailable) { "运行环境未连接，无法确认终端已结束" }
        val ids = sessionsFlow.value.map { it.id }
        for (id in ids) {
            runCatching { runtime.hubRequest("pty/close", jsonOf("ptyId" to id), timeoutMs = 10_000) }
        }
        sessionsFlow.value = emptyList()
    }.await()

    fun disconnected() {
        sessionsFlow.value = sessionsFlow.value.map { it.copy(status = "disconnected") }
    }
}
