package com.galiais.agent.engine.mobile

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 特权进程（无线调试 shell uid=2000 或 root uid=0）内的 UserService。
 * 为 PRoot 免 Root 环境提供与 ADB Shell 等级的命令执行能力（零反射，官方 AIDL 通道，大二进制流经 FD 管道传输规避 Binder 1MB 上限）。
 */
@Keep
class SolbraidShizukuUserService : ISolbraidShizukuService.Stub() {
    private val currentProcess = AtomicReference<java.lang.Process?>(null)

    override fun destroy() {
        cancelCurrent()
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun cancelCurrent() {
        currentProcess.getAndSet(null)?.destroyForcibly()
    }

    override fun execToFd(command: String?, timeoutSec: Int, outFd: ParcelFileDescriptor?): Int {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty() || outFd == null) {
            runCatching { outFd?.close() }
            return 2
        }
        val sec = timeoutSec.coerceIn(1, 120)
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()
            currentProcess.set(proc)
            val errDrain = Thread {
                runCatching { proc.errorStream.use { it.readBytes() } }
            }.apply { isDaemon = true; start() }
            val pumpThread = Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(outFd).use { out ->
                        proc.inputStream.use { ins ->
                            val buf = ByteArray(32_768)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                            out.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val finished = proc.waitFor(sec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    runCatching { pumpThread.join(1000) }
                    runCatching { errDrain.join(500) }
                    124
                } else {
                    runCatching { pumpThread.join(3000) }
                    runCatching { errDrain.join(500) }
                    proc.exitValue()
                }
            } finally {
                currentProcess.compareAndSet(proc, null)
                if (proc.isAlive) proc.destroyForcibly()
                runCatching { outFd.close() }
            }
        } catch (_: Exception) {
            runCatching { outFd.close() }
            1
        }
    }

    override fun exec(command: String?, timeoutSec: Int): String {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) {
            return JSONObject().put("exitCode", 2).put("stdout", "命令不能为空").toString()
        }
        val sec = timeoutSec.coerceIn(1, 120)
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            currentProcess.set(proc)
            val baos = ByteArrayOutputStream()
            val readerThread = Thread {
                val buf = ByteArray(8192)
                var total = 0
                // 严格控制在 Binder 1MB 事务缓冲安全阈值以下，防止 TransactionTooLargeException
                val maxBytes = 512 * 1024
                runCatching {
                    proc.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            val take = minOf(n, maxBytes - total)
                            if (take > 0) {
                                baos.write(buf, 0, take)
                                total += take
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val finished = proc.waitFor(sec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    readerThread.join(500)
                    return JSONObject()
                        .put("exitCode", 124)
                        .put("stdout", baos.toString("UTF-8") + "\n[TIMEOUT >${sec}s]")
                        .toString()
                }
                readerThread.join(1500)
                JSONObject()
                    .put("exitCode", proc.exitValue())
                    .put("stdout", baos.toString("UTF-8"))
                    .toString()
            } finally {
                currentProcess.compareAndSet(proc, null)
                if (proc.isAlive) proc.destroyForcibly()
            }
        } catch (e: Exception) {
            JSONObject()
                .put("exitCode", 1)
                .put("stdout", e.message ?: "Shizuku 命令执行异常")
                .toString()
        }
    }
}
