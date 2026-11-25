package com.galiais.agent

import android.content.Context
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CliRpcException(val code: Int, message: String, val data: Any?) : Exception(message)

class CliRpcConnection(private val context: Context, private val event: (JSONObject) -> Unit,
    private val stopped: (String) -> Unit, private val launcher: String = "launch-engine.sh",
    private val mode: String = RUNTIME_MODE_ROOT) : AutoCloseable {
    private val sequence = AtomicLong()
    private val connectionId = newId()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Any?>>()
    private val closeCause = AtomicReference<Exception?>(null)
    private val isClosed get() = closeCause.get() != null
    private val failureReported = AtomicBoolean(false)
    private val launched = CompletableFuture<Unit>()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val reader = Executors.newFixedThreadPool(2)
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val queuedBytes = AtomicLong()
    @Volatile private var process: Process? = null
    @Volatile private var ownedProcess: ManagedProcess? = null

    @Synchronized fun start(): JSONObject {
        ensureOpen()
        check(process == null) { "引擎已经启动" }
        require(launcher in setOf(
            "launch-engine.sh", "launch-claude.sh", "launch-gateway.sh", "launch-agent-hub.sh",
            "launch-engine-proot.sh", "launch-claude-proot.sh", "launch-gateway-proot.sh", "launch-agent-hub-proot.sh",
        )) { "未知引擎启动器" }
        val managed = launchPlatformProcess(context, mode, File(context.filesDir, "vm-runtime/$launcher"))
        ownedProcess = managed
        val child = managed.process
        process = child
        reader.execute {
            try {
                val input = java.io.InputStreamReader(child.inputStream, strictUtf8Decoder()).buffered()
                managed.acknowledge(input)
                launched.complete(Unit)
                while (!isClosed) {
                    val line = boundedTextLine(input) ?: break
                    if (line.isNotBlank()) receive(JSONObject(line))
                }
                if (!isClosed) failure("引擎连接结束，退出码 ${child.waitFor()}")
            } catch (error: Exception) { if (!isClosed) failure(error.message ?: "读取引擎失败") }
        }
        reader.execute {
            try {
                BufferedInputStream(child.errorStream).use { input ->
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    val readerErr = java.io.BufferedReader(java.io.InputStreamReader(input, decoder))
                    while (!isClosed) {
                        val line = readerErr.readLine() ?: break
                        // stderr 可能含用户内容，不复制到 Android 日志或构建产物。
                        if (line.contains("No usable sandbox")) event(JSONObject().put("type", "platform-warning").put("message", "引擎仍要求当前设备不支持的沙箱，请检查引擎配置"))
                    }
                }
            } catch (_: java.io.IOException) {
                if (!isClosed) failure("引擎诊断通道断开")
            } catch (_: Exception) {
                // 诊断流异常不导致引擎进程崩溃
            }
        }
        try {
            launched.get(35, TimeUnit.SECONDS)
            val result = request("initialize", JSONObject().put("clientInfo", JSONObject()
                .put("name", "solbraid_android").put("title", "Solbraid Android").put("version", BuildConfig.VERSION_NAME))
                .put("capabilities", JSONObject().put("experimentalApi", true))
                .put("galiaisKeepAlive", (context.applicationContext as GaliaisApplication).settings.state.value.keepRuntimeOnExit), 30_000).get(35, TimeUnit.SECONDS)
            notify("initialized", JSONObject())
            return result as? JSONObject ?: error("引擎返回了无效启动状态")
        } catch (error: Exception) {
            val cause = (error as? ExecutionException)?.cause as? Exception ?: error
            try { close(cause) } catch (cleanup: Exception) { cause.addSuppressed(cleanup) }
            throw cause
        }
    }
    @Synchronized fun request(method: String, params: JSONObject? = null, timeoutMs: Long = 120_000): CompletableFuture<Any?> {
        ensureOpen()
        check(process != null) { "引擎尚未启动" }
        require(method.isNotBlank() && method.length <= 200) { "引擎方法无效" }
        require(timeoutMs in 1..1_800_000) { "引擎超时范围无效" }
        check(pending.size < 64) { "等待中的引擎请求过多，请稍后再试" }
        val id = "android-$connectionId-${sequence.incrementAndGet()}"
        val future = CompletableFuture<Any?>()
        pending[id] = future
        try {
            val timeout = timers.schedule({ pending.remove(id)?.completeExceptionally(java.util.concurrent.TimeoutException("引擎请求超时：$method；执行结果未知，未自动重试。请先核对任务状态")) }, timeoutMs, TimeUnit.MILLISECONDS)
            future.whenComplete { _, _ -> timeout.cancel(false); pending.remove(id, future) }
            send(JSONObject().put("id", id).put("method", method).apply { if (params != null) put("params", params) })
        } catch (error: Exception) { pending.remove(id); future.completeExceptionally(closeCause.get() ?: error) }
        return future
    }
    fun notify(method: String, params: JSONObject) = send(JSONObject().put("method", method).put("params", params))
    fun respond(id: Any, result: Any?) = send(JSONObject().put("id", id).put("result", result ?: JSONObject.NULL))
    fun reject(id: Any, code: Int, message: String) = send(JSONObject().put("id", id).put("error", JSONObject().put("code", code).put("message", message)))
    private fun send(packet: JSONObject) {
        ensureOpen()
        val bytes = (packet.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "引擎消息超过 16 MB" }
        val size = bytes.size.toLong()
        if (queuedBytes.addAndGet(size) > 16 * 1024 * 1024) {
            queuedBytes.addAndGet(-size)
            error("请求未发送：发送队列已满，请稍后重试")
        }
        try {
            writer.execute {
                try {
                    ensureOpen()
                    val output = process?.outputStream ?: error("引擎尚未启动")
                    output.write(bytes); output.flush()
                } catch (error: Exception) { if (!isClosed) failure(error.message ?: "发送引擎消息失败") }
                finally { queuedBytes.addAndGet(-size) }
            }
        } catch (error: Exception) { queuedBytes.addAndGet(-size); throw error }
    }
    private fun receive(packet: JSONObject) {
        if (packet.has("method")) { event(packet); return }
        val id = packet.opt("id")?.toString() ?: error("引擎响应缺少 ID")
        val future = pending.remove(id) ?: return
        val error = packet.optJSONObject("error")
        if (error != null) future.completeExceptionally(CliRpcException(error.getInt("code"), error.getString("message"), error.opt("data")))
        else if (packet.has("result")) future.complete(packet.opt("result").takeUnless { it == JSONObject.NULL })
        else future.completeExceptionally(IllegalStateException("引擎响应既无结果也无错误"))
    }
    private fun boundedLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else error("引擎消息被截断")
            if (value == 10) return strictUtf8(line.toByteArray())
            check(line.size() < 16 * 1024 * 1024) { "引擎消息超过 16 MB" }
            line.write(value)
        }
    }
    private fun boundedTextLine(input: java.io.Reader): String? {
        val line = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.isEmpty()) null else error("引擎消息被截断")
            if (value == 10) return line.toString()
            check(line.length < 16 * 1024 * 1024) { "引擎消息超过 16 MB" }
            line.append(value.toChar())
        }
    }
    private fun failure(message: String) {
        if (!failureReported.compareAndSet(false, true) || isClosed) return
        var reason = message
        try { close(IllegalStateException(message)) } catch (error: Exception) { reason += "；" + (error.message ?: "无法确认引擎进程清理结果") }
        stopped(reason)
    }
    private fun ensureOpen() { closeCause.get()?.let { throw it } }

    override fun close() = close(IllegalStateException("引擎连接已关闭；操作未自动重试，请核对任务状态"))

    private fun close(cause: Exception) {
        // 先记录首个失败原因再解除等待，防止启动线程抢先用通用关闭错误覆盖根因。
        if (!closeCause.compareAndSet(null, cause)) return
        launched.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }; pending.clear()
        var cleanupError: Exception? = null
        try {
            try { ownedProcess?.close() } catch (error: Exception) { cleanupError = error }
        } finally {
            timers.shutdownNow(); writer.shutdownNow(); reader.shutdownNow()
        }
        cleanupError?.let { throw it }
    }
}
