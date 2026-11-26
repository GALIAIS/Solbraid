package com.galiais.agent.engine.terminal

/** Warp 风格敏感信息视觉脱敏模式（源自 `safe_mode_settings.rs`）。 */
enum class SecretRedactionMode(val id: String, val label: String) {
    OFF("off", "关闭"),
    ASTERISKS("asterisks", "星号遮罩"),
    STRIKETHROUGH("strikethrough", "删除线");

    companion object {
        fun fromId(id: String?): SecretRedactionMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** 单行内命中的敏感凭据物理列区间。 */
data class TerminalSecretSpan(
    val startCol: Int,
    val endCol: Int,
    val ruleName: String,
    val key: String,
)

/**
 * 完整移植 Warp `crates/secret_redaction/src/lib.rs` 的 20 条敏感凭据识别规则：
 * - 覆盖 OpenAI / Anthropic / Generic sk- / AWS / GitHub / Google / Stripe / Slack / Fireworks /
 *   Warp / Firebase / JWT / IPv4 / IPv6 / MAC / Phone；
 * - 支持按物理列区间返回供画布实时遮罩（`****` 或灰色删除线），并支持点击单条临时显隐。
 */
object TerminalSecretRedactor {
    private data class Rule(val name: String, val regex: Regex)

    private val RULES: List<Rule> = listOf(
        Rule("Anthropic API Key", Regex("""\bsk-ant-api\d{0,2}-[a-zA-Z0-9\-]{80,120}\b""")),
        Rule("OpenAI API Key", Regex("""\bsk-[a-zA-Z0-9]{48}\b""")),
        Rule("Generic SK API Key", Regex("""\bsk-[a-zA-Z0-9\-]{10,100}\b""")),
        Rule("Google API Key", Regex("""\bAIza[0-9A-Za-z\-_]{35}\b""")),
        Rule("AWS Access ID", Regex("""\b(AKIA|A3T|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{12,}\b""")),
        Rule("GitHub Fine-Grained PAT", Regex("""\bgithub_pat_[A-Za-z0-9_]{82}\b""")),
        Rule("GitHub Classic PAT", Regex("""\bghp_[A-Za-z0-9_]{36}\b""")),
        Rule("GitHub OAuth Token", Regex("""\bgho_[A-Za-z0-9_]{36}\b""")),
        Rule("GitHub User Token", Regex("""\bghu_[A-Za-z0-9_]{36}\b""")),
        Rule("GitHub Server Token", Regex("""\bghs_[A-Za-z0-9_]{36}\b""")),
        Rule("Stripe Key", Regex("""\b(?:r|s)k_(test|live)_[0-9a-zA-Z]{24}\b""")),
        Rule("Slack App Token", Regex("""\bxapp-[0-9]+-[A-Za-z0-9_]+-[0-9]+-[a-f0-9]+\b""")),
        Rule("Fireworks API Key", Regex("""\bfw_[a-zA-Z0-9]{24}\b""")),
        Rule("Warp API Key", Regex("""\bwk-[0-9]+\.[A-Fa-f0-9.\-]+\b""")),
        Rule("Firebase Auth Domain", Regex("""\b[a-z0-9\-]{1,30}\.firebaseapp\.com\b""")),
        Rule("JWT", Regex("""\b(ey[a-zA-Z0-9_\-=]{10,}\.){2}[a-zA-Z0-9_\-=]{10,}\b""")),
        Rule("IPv4 Address", Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""")),
        Rule("IPv6 Address", Regex("""\b(?:(?:[0-9A-Fa-f]{1,4}:){1,6}:|(?:[0-9A-Fa-f]{1,4}:){7})[0-9A-Fa-f]{1,4}\b""")),
        Rule("MAC Address", Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")),
        Rule("Phone Number", Regex("""\b(?:\+\d{1,2}\s)?\(?\d{3}\)?[\s.\-]\d{3}[\s.\-]\d{4}\b""")),
    )

    fun findInRow(absRow: Int, row: TerminalRow): List<TerminalSecretSpan> {
        val (text, startCols, endCols) = row.toStringWithColMap()
        if (text.length < 7) return emptyList()
        val raw = ArrayList<TerminalSecretSpan>(2)
        for (rule in RULES) {
            for (m in rule.regex.findAll(text)) {
                val sc = startCols[m.range.first.coerceIn(0, startCols.lastIndex)]
                val ec = endCols[m.range.last.coerceIn(0, endCols.lastIndex)]
                raw += TerminalSecretSpan(sc, ec, rule.name, "$absRow:$sc:$ec")
            }
        }
        if (raw.size <= 1) return raw
        raw.sortWith(compareBy<TerminalSecretSpan> { it.startCol }.thenByDescending { it.endCol })
        val merged = ArrayList<TerminalSecretSpan>(raw.size)
        var cur = raw[0]
        for (i in 1 until raw.size) {
            val nxt = raw[i]
            if (nxt.startCol <= cur.endCol) {
                if (nxt.endCol > cur.endCol) {
                    cur = cur.copy(endCol = nxt.endCol, key = "$absRow:${cur.startCol}:${nxt.endCol}")
                }
            } else {
                merged += cur
                cur = nxt
            }
        }
        merged += cur
        return merged
    }

    fun redactPlainText(input: String): String {
        if (input.length < 7) return input
        val ranges = ArrayList<IntRange>()
        for (rule in RULES) {
            for (m in rule.regex.findAll(input)) ranges += m.range
        }
        if (ranges.isEmpty()) return input
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>(ranges.size)
        var cur = ranges[0]
        for (i in 1 until ranges.size) {
            val nxt = ranges[i]
            if (nxt.first <= cur.last + 1) {
                cur = cur.first..maxOf(cur.last, nxt.last)
            } else {
                merged += cur
                cur = nxt
            }
        }
        merged += cur
        val sb = StringBuilder(input.length)
        var pos = 0
        for (r in merged) {
            if (r.first > pos) sb.append(input, pos, r.first)
            repeat(r.last - r.first + 1) { sb.append('*') }
            pos = r.last + 1
        }
        if (pos < input.length) sb.append(input, pos, input.length)
        return sb.toString()
    }
}
