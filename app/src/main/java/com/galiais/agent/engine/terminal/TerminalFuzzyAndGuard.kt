package com.galiais.agent.engine.terminal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模糊匹配结果（评分与高亮字符下标，参照 Warp `crates/fuzzy_match/src/lib.rs`）。 */
data class TerminalFuzzyHit(
    val score: Int,
    val matchedIndices: IntArray,
)

/**
 * Warp 风格智能大小写模糊匹配 + 通配符（`*`/`?`）引擎：
 * - 若查询全小写则不区分大小写，含大写则自动切换精确大小写（Smart Case）；
 * - 奖励词边界命中、连续命中与前缀精确命中，用于 `Ctrl+R` 历史面板与 PSReadLine 预测排序。
 */
object TerminalFuzzyMatcher {
    fun containsWildcards(query: String): Boolean = '*' in query || '?' in query

    fun match(text: String, query: String): TerminalFuzzyHit? {
        val q = query.trim()
        if (q.isEmpty()) return TerminalFuzzyHit(0, IntArray(0))
        val caseSensitive = q.any { it.isUpperCase() }
        if (containsWildcards(q)) {
            return matchWildcard(text, q, caseSensitive)
        }
        return matchFuzzy(text, q, caseSensitive)
    }

    private fun matchFuzzy(text: String, query: String, caseSensitive: Boolean): TerminalFuzzyHit? {
        val tLen = text.length
        val qLen = query.length
        if (qLen > tLen) return null
        val tNorm = if (caseSensitive) text else text.lowercase(Locale.ROOT)
        val qNorm = if (caseSensitive) query else query.lowercase(Locale.ROOT)

        // 先检测子序列可达性并贪心寻找最优词边界匹配
        val exactIdx = tNorm.indexOf(qNorm)
        if (exactIdx >= 0) {
            var score = 120 + qLen * 16 - exactIdx * 2
            if (exactIdx == 0) score += 80
            else if (isBoundary(text[exactIdx - 1])) score += 45
            return TerminalFuzzyHit(score, IntArray(qLen) { exactIdx + it })
        }

        val indices = IntArray(qLen)
        var ti = 0
        var qi = 0
        var score = 0
        var prevMatch = -2
        while (ti < tLen && qi < qLen) {
            if (tNorm[ti] == qNorm[qi]) {
                indices[qi] = ti
                score += 12
                if (ti == prevMatch + 1) score += 18
                if (ti == 0 || isBoundary(text[ti - 1]) || (text[ti].isUpperCase() && !text[ti - 1].isUpperCase())) {
                    score += 24
                }
                if (caseSensitive && text[ti] == query[qi]) score += 4
                prevMatch = ti
                qi++
            }
            ti++
        }
        if (qi < qLen) return null
        score -= (tLen - qLen).coerceAtMost(60)
        return TerminalFuzzyHit(score, indices)
    }

    private fun matchWildcard(text: String, pattern: String, caseSensitive: Boolean): TerminalFuzzyHit? {
        val t = if (caseSensitive) text else text.lowercase(Locale.ROOT)
        val p = if (caseSensitive) pattern else pattern.lowercase(Locale.ROOT)
        val regexSrc = buildString {
            for (ch in p) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        val m = runCatching { Regex(regexSrc).find(t) }.getOrNull() ?: return null
        val range = m.range
        val len = (range.last - range.first + 1).coerceAtLeast(0)
        return TerminalFuzzyHit(100 - range.first, IntArray(len) { range.first + it })
    }

    private fun isBoundary(ch: Char): Boolean =
        ch == ' ' || ch == '/' || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '|' || ch == ';'
}

/** 高危命令二次确认告警。 */
data class TerminalDangerWarning(
    val title: String,
    val reason: String,
    val matchedRule: String,
)

/**
 * 终端高危命令防护（Safe Execute Guard）：
 * 在粘贴、片段执行或直发栏提交前拦截常见不可逆破坏操作，防止手机触屏误触。
 */
object TerminalSafeGuard {
    private data class DangerRule(val name: String, val reason: String, val regex: Regex, val sshOnly: Boolean = false)

    private val RULES = listOf(
        DangerRule("强制递归删除根目录", "可能清空整个系统或主目录文件", Regex("""(^|[;&|]\s*)(sudo\s+)?rm\s+[^\n]*-[^\s]*r[^\s]*f[^\s]*\s+(/(\*|\s|$)|~/?(\s|$)|/\.\.)""")),
        DangerRule("底层块设备写入 (dd)", "直接覆写磁盘分区或块设备不可恢复", Regex("""(^|[;&|]\s*)(sudo\s+)?dd\s+[^\n]*of=/dev/""")),
        DangerRule("格式化磁盘 (mkfs)", "将格式化目标设备并清空全部数据", Regex("""(^|[;&|]\s*)(sudo\s+)?mkfs(\.[a-z0-9]+)?\s+""")),
        DangerRule("全盘权限破坏 (chmod 777 /)", "递归开放根目录全部权限会破坏系统安全与 SSH 服务", Regex("""(^|[;&|]\s*)(sudo\s+)?chmod\s+[^\n]*-R[^\n]*\s+777\s+/""")),
        DangerRule("Fork 炸弹", "会迅速耗尽进程表导致系统卡死", Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""")),
        DangerRule("管道直连脚本执行", "直接把远端下载脚本管道喂给 Shell 执行存在高风险", Regex("""(curl|wget)\s+[^\n|]+\|\s*(sudo\s+)?(ba)?sh\b""")),
        DangerRule("远端主机关机/重启", "会立即断开当前 SSH 主机并重启/关闭服务器", Regex("""(^|[;&|]\s*)(sudo\s+)?(shutdown|reboot|poweroff|halt|init\s+[06])\b"""), sshOnly = true),
    )

    fun inspect(command: String, isRemoteSsh: Boolean): TerminalDangerWarning? {
        val trimmed = command.trim()
        if (trimmed.length < 4) return null
        for (r in RULES) {
            if (r.sshOnly && !isRemoteSsh) continue
            if (r.regex.containsMatchIn(trimmed)) {
                return TerminalDangerWarning(title = r.name, reason = r.reason, matchedRule = r.name)
            }
        }
        return null
    }
}

/**
 * 源自 PowerShell 7 `StringDecorated.cs` 与 `ConsoleHostTranscript.cs` 的 ANSI 剥离与标准会话录制头尾工具。
 */
object TerminalAnsiUtils {
    private val ANSI_STRIP_REGEX = Regex(
        """\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)|\u001b_[ -~]*\u001b\\|\u001bP[^\u001b]*\u001b\\|\u001b\[[0-?]*[ -/]*[@-~]|\u001b[@-Z\\-_]"""
    )

    fun stripAnsi(input: String): String {
        if ('\u001b' !in input) return input
        return ANSI_STRIP_REGEX.replace(input, "")
    }

    fun formatTranscriptPrologue(sessionTitle: String, hostInfo: String, cwd: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("**********************")
            appendLine("Solbraid Terminal Transcript Start")
            appendLine("Start time: $ts")
            appendLine("Session: $sessionTitle")
            appendLine("Target: $hostInfo")
            appendLine("Working Directory: $cwd")
            appendLine("**********************")
        }
    }

    fun formatTranscriptEpilogue(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine()
            appendLine("**********************")
            appendLine("Solbraid Terminal Transcript End")
            appendLine("End time: $ts")
            appendLine("**********************")
        }
    }
}
