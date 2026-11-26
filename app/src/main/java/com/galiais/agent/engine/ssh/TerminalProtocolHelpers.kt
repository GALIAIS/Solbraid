package com.galiais.agent.engine.ssh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.galiais.agent.engine.SshHostConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** 本地终端专用二进制流帧类型（与 `runtime/terminal-hub.mjs` 严格对齐）。 */
object PtyStreamCodec {
    const val FRAME_DATA: Byte = 0x01
    const val FRAME_RESIZE: Byte = 0x02
    const val FRAME_EXIT: Byte = 0x03
    const val MAX_FRAME_BYTES: Int = 2 * 1024 * 1024

    fun writeDataFrame(out: DataOutputStream, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length <= 0) return
        out.writeByte(FRAME_DATA.toInt())
        out.writeInt(length)
        out.write(bytes, offset, length)
        out.flush()
    }

    fun writeResizeFrame(out: DataOutputStream, cols: Int, rows: Int) {
        val c = cols.coerceIn(2, 500)
        val r = rows.coerceIn(1, 300)
        out.writeByte(FRAME_RESIZE.toInt())
        out.writeInt(4)
        out.writeShort(c)
        out.writeShort(r)
        out.flush()
    }

    /** 读取后端二进制流鉴权响应行（预期 `"OK"`，失败时抛出错误）。 */
    fun readHandshakeLine(input: DataInputStream): String {
        val sb = StringBuilder(32)
        while (sb.length < 256) {
            val b = input.read()
            if (b < 0) throw EOFException("终端流握手前连接已断开")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    /** 读取一帧；连接正常关闭时返回 null。 */
    fun readFrame(input: DataInputStream): Pair<Byte, ByteArray>? {
        val type = try {
            input.readByte()
        } catch (_: EOFException) {
            return null
        }
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IllegalStateException("终端流帧长度异常：$length")
        }
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return type to payload
    }
}

/** 修饰键三态：关闭 -> 单次生效（发完一字自动复位）-> 锁定常开（再次点击关闭）。 */
enum class ModifierKeyState { OFF, TRANSIENT, LOCKED }

/** Termux / PowerShell 风格粘滞+锁定修饰键控制器（纯自研，零第三方库依赖）。 */
class StickyModifierManager {
    var ctrlState by mutableStateOf(ModifierKeyState.OFF)
        private set
    var altState by mutableStateOf(ModifierKeyState.OFF)
        private set
    var shiftState by mutableStateOf(ModifierKeyState.OFF)
        private set

    fun tapCtrl() {
        ctrlState = nextState(ctrlState)
    }

    fun tapAlt() {
        altState = nextState(altState)
    }

    fun tapShift() {
        shiftState = nextState(shiftState)
    }

    fun resetAll() {
        ctrlState = ModifierKeyState.OFF
        altState = ModifierKeyState.OFF
        shiftState = ModifierKeyState.OFF
    }

    private fun nextState(cur: ModifierKeyState): ModifierKeyState = when (cur) {
        ModifierKeyState.OFF -> ModifierKeyState.TRANSIENT
        ModifierKeyState.TRANSIENT -> ModifierKeyState.LOCKED
        ModifierKeyState.LOCKED -> ModifierKeyState.OFF
    }

    fun isCtrlActive(): Boolean = ctrlState != ModifierKeyState.OFF
    fun isAltActive(): Boolean = altState != ModifierKeyState.OFF
    fun isShiftActive(): Boolean = shiftState != ModifierKeyState.OFF

    fun clearTransients() {
        if (ctrlState == ModifierKeyState.TRANSIENT) ctrlState = ModifierKeyState.OFF
        if (altState == ModifierKeyState.TRANSIENT) altState = ModifierKeyState.OFF
        if (shiftState == ModifierKeyState.TRANSIENT) shiftState = ModifierKeyState.OFF
    }
}

/** 解析快捷连接表达式（支持 `user@host:port`、`host:port`、`user@[::1]:2222`、`ssh -p 2222 user@host`）。 */
fun parseQuickConnectSpec(raw: String, fallbackKeyName: String = "id_ed25519"): SshHostConfig {
    var s = raw.trim()
    require(s.isNotEmpty()) { "请输入连接地址，例如 root@192.168.1.10 或 user@host:2222" }
    var explicitPort: Int? = null
    if (s.startsWith("ssh ", ignoreCase = true)) {
        val tokens = s.split(Regex("\\s+")).drop(1)
        val rest = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            when {
                tokens[i] == "-p" && i + 1 < tokens.size -> {
                    explicitPort = tokens[i + 1].toIntOrNull()
                    i += 2
                }
                tokens[i].startsWith("-p") && tokens[i].length > 2 -> {
                    explicitPort = tokens[i].substring(2).toIntOrNull()
                    i++
                }
                tokens[i].startsWith("-") -> {
                    i += if (tokens[i] in setOf("-i", "-J", "-l", "-o") && i + 1 < tokens.size) 2 else 1
                }
                else -> {
                    rest += tokens[i]
                    i++
                }
            }
        }
        s = rest.firstOrNull().orEmpty()
    }
    s = s.removePrefix("ssh://").trim()
    val userPart = if ('@' in s) s.substringBefore('@').trim() else "root"
    val hostPort = if ('@' in s) s.substringAfter('@').trim() else s
    val host: String
    val port: Int
    if (hostPort.startsWith("[")) {
        val endBracket = hostPort.indexOf(']')
        require(endBracket > 1) { "IPv6 地址格式无效" }
        host = hostPort.substring(1, endBracket).trim()
        val after = hostPort.substring(endBracket + 1)
        port = explicitPort ?: if (after.startsWith(":")) {
            after.substring(1).toIntOrNull() ?: throw IllegalArgumentException("端口号无效")
        } else 22
    } else if (hostPort.count { it == ':' } == 1) {
        host = hostPort.substringBefore(':').trim()
        port = explicitPort ?: (hostPort.substringAfter(':').trim().toIntOrNull() ?: throw IllegalArgumentException("端口号无效"))
    } else {
        host = hostPort
        port = explicitPort ?: 22
    }
    require(host.isNotBlank() && !host.any { it.isWhitespace() }) { "主机地址无效" }
    require(port in 1..65535) { "端口号须在 1..65535 之间" }
    val cleanUser = userPart.ifBlank { "root" }
    val alias = host.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "quick" }.take(32)
    return SshHostConfig(
        id = "quick-${cleanUser}@${host}:${port}",
        alias = alias,
        name = if (port == 22) "$cleanUser@$host" else "$cleanUser@$host:$port",
        host = host,
        port = port,
        user = cleanUser,
        authType = "key",
        keyName = fallbackKeyName,
    )
}

/** 终端常用命令片段（支持自定义增删与一键发送）。 */
data class TerminalSnippet(
    val id: String,
    val title: String,
    val command: String,
    val autoEnter: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("command", command)
        .put("autoEnter", autoEnter)

    companion object {
        fun fromJson(obj: JSONObject) = TerminalSnippet(
            id = obj.getString("id"),
            title = obj.getString("title"),
            command = obj.getString("command"),
            autoEnter = obj.optBoolean("autoEnter", true),
        )

        fun defaults(): List<TerminalSnippet> = listOf(
            TerminalSnippet("builtin-tmux", "恢复 tmux", "tmux attach || tmux", true),
            TerminalSnippet("builtin-htop", "系统资源", "htop 2>/dev/null || top", true),
            TerminalSnippet("builtin-df", "磁盘占用", "df -h", true),
            TerminalSnippet("builtin-free", "内存占用", "free -h", true),
            TerminalSnippet("builtin-ports", "监听端口", "ss -tulpn 2>/dev/null || netstat -tulpn", true),
            TerminalSnippet("builtin-docker", "Docker 容器", "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'", true),
            TerminalSnippet("builtin-logs", "最近系统日志", "journalctl -n 50 --no-pager", true),
            TerminalSnippet("builtin-git", "Git 状态", "git status -sb", true),
        )

        fun parseList(raw: String?): List<TerminalSnippet> {
            if (raw.isNullOrBlank()) return defaults()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
            }.getOrDefault(defaults()).ifEmpty { defaults() }
        }
    }
}
