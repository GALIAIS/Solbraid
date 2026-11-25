package com.galiais.agent

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

enum class RootAccessStatus(val label: String, val message: String) {
    AVAILABLE("Root 环境权限可用", "Root 权限与运行环境要求一致"),
    UNAVAILABLE("无法启动 su", "无法启动 su，请检查 Root 管理器是否正常安装并为 Solbraid 授权"),
    UNCONFIRMED("Root 启动未确认", "su 未确认启动，请检查管理器中的 Solbraid 授权及 su 兼容性"),
    TIMED_OUT("Root 授权超时", "Root 授权或启动超时，请在管理器中确认授权后重试"),
    PROFILE_RESTRICTED("Root 配置权限不足", "Root 配置缺少运行环境所需权限，请为 Solbraid 恢复默认 Root 配置后重试"),
    NAMESPACE_UNAVAILABLE("Root 挂载空间不可用", "无法确认一致的 Root 挂载空间，请检查管理器的挂载配置并重新打开应用"),
    MISSING_TOOLS("系统命令不完整", "系统缺少运行环境所需命令，无法启动 Root 环境"),
    INVALID_RESPONSE("Root 响应无效", "Root 启动响应无效，请检查 su 或启动脚本是否修改了标准输入输出"),
}

data class RootAccessResult(val status: RootAccessStatus, val detail: String? = null) {
    val available: Boolean get() = status == RootAccessStatus.AVAILABLE
    val message: String get() = detail?.let { "${status.message}（$it）" } ?: status.message
}

internal class RootAccessException(val result: RootAccessResult) : IOException(result.message)

internal object RootShell {
    private const val MAX_HANDSHAKE_CHARS = 16_384
    private const val SYSTEM_PATH = "/system/bin:/system/xbin"
    private val requiredTools = listOf("sh", "env", "id", "readlink", "stat", "setsid", "timeout", "chroot", "mount", "umount", "mknod", "grep", "tr")
    private val requiredCapabilities = mapOf(
        "CAP_CHOWN" to 0, "CAP_DAC_OVERRIDE" to 1, "CAP_FOWNER" to 3,
        "CAP_SETGID" to 6, "CAP_SETUID" to 7, "CAP_SYS_CHROOT" to 18,
        "CAP_SYS_ADMIN" to 21, "CAP_MKNOD" to 27,
    ).values.fold(0uL) { mask, bit -> mask or (1uL shl bit) }
    private val mountNamespace = AtomicReference<String?>(null)

    // Magisk、KernelSU 及其分支、APatch 共用这些参数；不申请终端、不覆盖 Root 配置中的身份。
    fun process(command: String): ProcessBuilder =
        ProcessBuilder("su", "--mount-master", "-s", "/system/bin/sh", "-c", command)

    fun cleanCommand(body: String, launchKey: String? = null, ownGroup: Boolean = false): String {
        // Android 9-10 (API 28-29) 的 Toybox 不支持 `setsid -w`，改按系统版本动态选择以防启动失败
        val group = if (ownGroup && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            "exec /system/bin/setsid -w "
        } else {
            "exec "
        }
        val identity = launchKey?.let { " GALIAIS_LAUNCH_ID=${quote(it)}" }.orEmpty()
        return "$group/system/bin/env -i PATH=${quote(SYSTEM_PATH)}$identity /system/bin/sh -c ${quote(body)}"
    }

    fun metadataCommand(key: String): String {
        val format = "{\"galiaisRoot\":1,\"galiaisProcessGroup\":%s,\"launchKey\":\"$key\",\"uid\":%s,\"mountNamespace\":\"%s\",\"capEff\":\"%s\",\"missingTools\":\"%s\"}\\n"
        return """
            root_uid=${'$'}(/system/bin/id -u 2>/dev/null) || root_uid=-1
            root_mnt=${'$'}(/system/bin/readlink /proc/${'$'}${'$'}/ns/mnt 2>/dev/null) || root_mnt=
            root_caps=
            while read -r root_name root_value root_rest; do
                case "${'$'}root_name" in CapEff:) root_caps=${'$'}root_value; break;; esac
            done < /proc/${'$'}${'$'}/status
            root_missing=
            for root_tool in ${requiredTools.joinToString(" ")}; do
                command -v "${'$'}root_tool" >/dev/null 2>&1 || root_missing="${'$'}root_missing ${'$'}root_tool"
            done
            printf ${quote(format)} "${'$'}${'$'}" "${'$'}root_uid" "${'$'}root_mnt" "${'$'}root_caps" "${'$'}root_missing"
        """.trimIndent()
    }

    fun readHandshake(reader: BufferedReader, key: String): JSONObject {
        val line = StringBuilder()
        repeat(MAX_HANDSHAKE_CHARS) {
            val value = reader.read()
            if (value < 0) throw RootAccessException(RootAccessResult(RootAccessStatus.UNCONFIRMED))
            if (value == 10) {
                val packet = try { JSONObject(line.toString()) } catch (_: org.json.JSONException) { null }
                if (packet?.optInt("galiaisRoot") == 1 && packet.optString("launchKey") == key) return packet
                // 部分 su 会输出提示；仅接受带本次随机标识的握手，不将提示当作协议或写入日志。
                line.setLength(0)
            } else line.append(value.toChar())
        }
        throw RootAccessException(RootAccessResult(RootAccessStatus.INVALID_RESPONSE))
    }

    fun validate(packet: JSONObject) {
        val missing = packet.optString("missingTools").trim().split(' ').filter { it.isNotBlank() }
        if (missing.any { it !in requiredTools }) throw RootAccessException(RootAccessResult(RootAccessStatus.INVALID_RESPONSE))
        if (missing.isNotEmpty()) throw RootAccessException(RootAccessResult(RootAccessStatus.MISSING_TOOLS, missing.joinToString("、")))
        val capabilities = packet.optString("capEff").takeIf { it.matches(Regex("[0-9a-fA-F]{1,16}")) }?.toULongOrNull(16)
        if (packet.optLong("uid", -1L) != 0L || capabilities == null || (capabilities and requiredCapabilities) != requiredCapabilities) {
            throw RootAccessException(RootAccessResult(RootAccessStatus.PROFILE_RESTRICTED))
        }
        val actualNamespace = packet.optString("mountNamespace")
        if (!actualNamespace.matches(Regex("mnt:\\[[0-9]+]"))) {
            throw RootAccessException(RootAccessResult(RootAccessStatus.NAMESPACE_UNAVAILABLE))
        }
        // 更新当前活跃的挂载命名空间记录，允许用户在 Root 管理器切换策略后无需强杀 App 恢复
        mountNamespace.set(actualNamespace)
    }

    fun check(timeoutSeconds: Long): RootAccessResult {
        require(timeoutSeconds in 1..60)
        val key = UUID.randomUUID().toString()
        val child = try { process(cleanCommand(metadataCommand(key))).redirectErrorStream(true).start() }
        catch (_: IOException) { return RootAccessResult(RootAccessStatus.UNAVAILABLE) }
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "root-check-output").apply { isDaemon = true } }
        try {
            child.outputStream.close()
            val response = reader.submit<JSONObject> {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { readHandshake(it, key) }
            }
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return RootAccessResult(RootAccessStatus.TIMED_OUT)
            if (child.exitValue() != 0) return RootAccessResult(RootAccessStatus.UNCONFIRMED, "退出码 ${child.exitValue()}")
            validate(response.get(1, TimeUnit.SECONDS))
            return RootAccessResult(RootAccessStatus.AVAILABLE)
        } catch (error: RootAccessException) {
            return error.result
        } catch (error: ExecutionException) {
            return (error.cause as? RootAccessException)?.result ?: RootAccessResult(RootAccessStatus.INVALID_RESPONSE)
        } catch (_: TimeoutException) {
            return RootAccessResult(RootAccessStatus.TIMED_OUT)
        } catch (_: IOException) {
            return RootAccessResult(RootAccessStatus.UNCONFIRMED)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            try { child.inputStream.close() }
            finally {
                try { child.errorStream.close() }
                finally { reader.shutdownNow() }
            }
        }
    }
}
