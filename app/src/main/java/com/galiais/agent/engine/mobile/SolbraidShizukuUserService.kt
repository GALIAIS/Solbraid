package com.galiais.agent.engine.mobile

import android.os.Process
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 特权进程（无线调试 shell uid=2000 或 root uid=0）内的 UserService。
 * 为 PRoot 免 Root 环境提供与 ADB Shell 等级的命令执行能力（零反射，官方 AIDL 通道）。
 */
@Keep
class SolbraidShizukuUserService : ISolbraidShizukuService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

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
            val baos = ByteArrayOutputStream()
            val readerThread = Thread {
                val buf = ByteArray(8192)
                var total = 0
                val maxBytes = 2 * 1024 * 1024
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
        } catch (e: Exception) {
            JSONObject()
                .put("exitCode", 1)
                .put("stdout", e.message ?: "Shizuku 命令执行异常")
                .toString()
        }
    }
}
