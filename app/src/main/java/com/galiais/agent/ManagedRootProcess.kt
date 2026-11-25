package com.galiais.agent

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 受管理的平台子进程：Root 模式通过 su 托管，免 Root 模式直接由应用 UID 启动 PRoot。 */
interface ManagedProcess : AutoCloseable {
    val process: Process
    fun acknowledge(reader: BufferedReader)
    fun close(graceSeconds: Long)
    override fun close() = close(2)
}

fun launchPlatformProcess(context: Context, mode: String, script: File, arguments: List<String> = emptyList()): ManagedProcess =
    if (mode == RUNTIME_MODE_PROOT) ManagedLocalProcess(context, script, arguments)
    else ManagedRootProcess(context, script, arguments)

class ManagedRootProcess(context: Context, script: File, arguments: List<String> = emptyList()) : ManagedProcess {
    private val key = UUID.randomUUID().toString()
    private val lease = File(context.filesDir, "root-session-$key.lease")
    private val ownership = CompletableFuture<Long>()
    private val closed = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var executionAcknowledged = false
    override val process: Process

    init {
        lease.outputStream().use { output ->
            Os.fchmod(output.fd, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            output.write((key + "\n").toByteArray(Charsets.UTF_8))
        }
        val body = RootShell.metadataCommand(key) + "; IFS= read -r ack; test \"${'$'}ack\" = ${quote(key)} || exit 125; " +
            "IFS= read -r lease < ${quote(lease.absolutePath)} && test \"${'$'}lease\" = ${quote(key)} || exit 125; " +
            "exec /system/bin/sh ${quote(script.absolutePath)} " + arguments.joinToString(" ", transform = ::quote)
        val command = RootShell.cleanCommand(body, launchKey = key, ownGroup = true)
        try {
            process = RootShell.process(command).start()
        } catch (error: Exception) {
            lease.delete(); timer.shutdownNow(); throw error
        }
        timer.schedule({
            if (ownership.completeExceptionally(RootAccessException(RootAccessResult(RootAccessStatus.TIMED_OUT)))) {
                lease.delete()
                process.destroy()
            }
        }, 30, TimeUnit.SECONDS)
    }

    override fun acknowledge(reader: BufferedReader) {
        val metadata = RootShell.readHandshake(reader, key)
        val group = metadata.getLong("galiaisProcessGroup")
        check(group in 2..Int.MAX_VALUE.toLong()) { "Root 进程组无效" }
        check(ownership.complete(group)) { "Root 启动操作已失效" }
        timer.shutdownNow()
        RootShell.validate(metadata)
        executionAcknowledged = true
        process.outputStream.write((key + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    override fun close(graceSeconds: Long) {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        var failure: Exception? = null
        try {
            if (lease.exists() && !lease.delete()) failure = IllegalStateException("无法清理 Root 启动标记")
            try { process.outputStream.close() } catch (error: java.io.IOException) { if (process.isAlive) failure = error }
            process.waitFor(graceSeconds, TimeUnit.SECONDS)
            val group = if (ownership.isDone && !ownership.isCompletedExceptionally) ownership.getNow(null) else null
            // 仅在进程尚未退出或仍需清理进程组时调用 su，并支持 TERM -> KILL 升级，避免已正常退出的短命令空起 su 进程
            if (group != null && process.isAlive) {
                val verify = "if test -d /proc/$group; then " +
                    "if /system/bin/tr '\\000' '\\n' < /proc/$group/environ 2>/dev/null | /system/bin/grep -qFx ${quote("GALIAIS_LAUNCH_ID=$key")}; then " +
                    "kill -TERM -- -$group 2>/dev/null || true; sleep 1; " +
                    "test -d /proc/$group && kill -KILL -- -$group 2>/dev/null || true; " +
                    "fi; fi"
                val stop = RootShell.process(RootShell.cleanCommand(verify)).start()
                try {
                    if (!stop.waitFor(6, TimeUnit.SECONDS)) { stop.destroyForcibly(); failure = IllegalStateException("停止 Root 进程组超时") }
                } finally { stop.inputStream.close(); stop.errorStream.close(); stop.outputStream.close() }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); failure = IllegalStateException("无法确认 Root 启动器已经退出") }
        } finally {
            process.inputStream.close(); process.errorStream.close()
        }
        failure?.let { throw it }
    }
}

/** 免 Root 模式的受管理子进程：直接以应用自身 UID 运行 PRoot 脚本，不调用 su。 */
class ManagedLocalProcess(context: Context, script: File, arguments: List<String> = emptyList()) : ManagedProcess {
    private val key = UUID.randomUUID().toString()
    private val ownership = CompletableFuture<Long>()
    private val closed = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    override val process: Process

    init {
        val metadata = "{\"galiaisLocalGroup\":%s,\"launchKey\":\"$key\"}\\n"
        val body = "printf ${quote(metadata)} \"${'$'}${'$'}\"; IFS= read -r ack; test \"${'$'}ack\" = ${quote(key)} || exit 125; " +
            "exec /system/bin/sh ${quote(script.absolutePath)} " + arguments.joinToString(" ", transform = ::quote)
        val builder = ProcessBuilder("/system/bin/setsid", "-w", "/system/bin/sh", "-c", body)
        builder.environment().apply {
            put("GALIAIS_APP_FILES", context.filesDir.absolutePath)
            put("GALIAIS_APP_CACHE", context.cacheDir.absolutePath)
            put("GALIAIS_NATIVE_LIB", context.applicationInfo.nativeLibraryDir)
            put("GALIAIS_RUNTIME_MODE", RUNTIME_MODE_PROOT)
            put("GALIAIS_LAUNCH_ID", key)
        }
        try {
            process = builder.start()
        } catch (error: Exception) {
            timer.shutdownNow(); throw error
        }
        timer.schedule({
            if (ownership.completeExceptionally(java.util.concurrent.TimeoutException("PRoot 启动确认超时"))) {
                process.destroy()
            }
        }, 30, TimeUnit.SECONDS)
    }

    override fun acknowledge(reader: BufferedReader) {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            check(value >= 0) { "PRoot 启动器在确认前退出" }
            if (value == 10) break
            check(line.length < 4096) { "PRoot 启动状态超过限制" }
            line.append(value.toChar())
        }
        val metadata = JSONObject(line.toString())
        check(metadata.getString("launchKey") == key) { "PRoot 启动器身份不匹配" }
        val group = metadata.getLong("galiaisLocalGroup")
        check(group in 2..Int.MAX_VALUE.toLong()) { "PRoot 进程组无效" }
        check(ownership.complete(group)) { "PRoot 启动操作已失效" }
        timer.shutdownNow()
        process.outputStream.write((key + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    override fun close(graceSeconds: Long) {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        var failure: Exception? = null
        try {
            try { process.outputStream.close() } catch (error: java.io.IOException) { if (process.isAlive) failure = error }
            process.waitFor(graceSeconds, TimeUnit.SECONDS)
            val group = if (ownership.isDone && !ownership.isCompletedExceptionally) ownership.getNow(null) else null
            if (group != null && process.isAlive) {
                try { Os.kill(-group.toInt(), OsConstants.SIGTERM) } catch (_: Exception) { }
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    try { Os.kill(-group.toInt(), OsConstants.SIGKILL) } catch (_: Exception) { }
                    process.destroyForcibly()
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); failure = IllegalStateException("无法确认 PRoot 进程已经退出") }
        } finally {
            process.inputStream.close(); process.errorStream.close()
        }
        failure?.let { throw it }
    }
}
