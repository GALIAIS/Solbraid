package com.galiais.agent.ui.files

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.galiais.agent.engine.computeMyersLineDiff

private val PAIR_OPEN_TO_CLOSE = mapOf(
    "(" to ")",
    "[" to "]",
    "{" to "}",
    "\"" to "\"",
    "'" to "'",
    "`" to "`",
    "<" to ">",
)

/**
 * 计算当前缓冲区相对已保存版本的行级 Gutter 修改标记：
 * - `'+'`：新增行（绿色边条）
 * - `'~'`：修改行（蓝色边条，由相邻 '-' 与 '+' 折叠识别）
 * - `'-'`：删除锚点（红色标记，锚定在删除块紧邻的当前行）
 */
internal fun computeGutterDiffMarkers(savedText: String, currentText: String, maxDiffLines: Int = 4000): Map<Int, Char> {
    if (savedText == currentText) return emptyMap()
    val ops = computeMyersLineDiff(savedText, currentText, maxLines = maxDiffLines)
    val markers = HashMap<Int, Char>()
    var newLine = 1
    var i = 0
    while (i < ops.size) {
        val (kind, _) = ops[i]
        when (kind) {
            ' ' -> {
                newLine++
                i++
            }
            '-' -> {
                var delCount = 0
                while (i < ops.size && ops[i].first == '-') {
                    delCount++
                    i++
                }
                var addCount = 0
                while (i < ops.size && ops[i].first == '+') {
                    val mark = if (addCount < delCount) '~' else '+'
                    markers[newLine] = mark
                    newLine++
                    addCount++
                    i++
                }
                if (addCount == 0) {
                    val anchor = (newLine - 1).coerceAtLeast(1)
                    if (markers[anchor] == null) markers[anchor] = '-'
                }
            }
            '+' -> {
                markers[newLine] = '+'
                newLine++
                i++
            }
            else -> i++
        }
    }
    return markers
}

/**
 * 回车自动延续缩进：
 * 当用户输入单个换行符时，自动沿用上一行缩进；若上一行去除尾空白后以 `{`、`:`、`(`、`[` 结尾，则额外缩进一层。
 */
internal fun applySmartEnterIndent(prev: TextFieldValue, next: TextFieldValue, tabSize: Int): TextFieldValue {
    if (next.text.length != prev.text.length + 1) return next
    if (!next.selection.collapsed) return next
    val cursor = next.selection.start
    if (cursor <= 0 || cursor > next.text.length) return next
    if (next.text[cursor - 1] != '\n') return next
    // 验证确实是在 prev 的同一位置单插了一个 '\n'
    val prefixOk = next.text.regionMatches(0, prev.text, 0, cursor - 1)
    val suffixOk = next.text.regionMatches(cursor, prev.text, cursor - 1, next.text.length - cursor)
    if (!prefixOk || !suffixOk) return next

    val prevLineStart = next.text.lastIndexOf('\n', (cursor - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val prevLine = next.text.substring(prevLineStart, cursor - 1)
    val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val trimmedPrev = prevLine.trimEnd()
    val extraIndent = if (trimmedPrev.endsWith("{") || trimmedPrev.endsWith(":") || trimmedPrev.endsWith("(") || trimmedPrev.endsWith("[")) {
        if (baseIndent.contains('\t')) "\t" else " ".repeat(tabSize.coerceIn(2, 8))
    } else ""
    val indent = baseIndent + extraIndent
    if (indent.isEmpty()) return next
    val updated = next.text.substring(0, cursor) + indent + next.text.substring(cursor)
    return next.copy(text = updated, selection = TextRange(cursor + indent.length))
}

/**
 * 辅助符号条点击：
 * - 若当前有非空选区且点击了开括号/引号（`(`, `[`, `{`, `"`, `'`, `` ` ``, `<`），自动用成对符号包裹选区而非覆写删除；
 * - 否则在光标处直接插入符号。
 */
internal fun applySymbolOrWrap(current: TextFieldValue, sym: String): TextFieldValue {
    val t = current.text
    val s = current.selection.min.coerceIn(0, t.length)
    val e = current.selection.max.coerceIn(s, t.length)
    val closing = PAIR_OPEN_TO_CLOSE[sym]
    if (s < e && closing != null) {
        val selected = t.substring(s, e)
        val wrapped = sym + selected + closing
        val next = t.substring(0, s) + wrapped + t.substring(e)
        return TextFieldValue(next, TextRange(s + sym.length, s + sym.length + selected.length))
    }
    val next = t.substring(0, s) + sym + t.substring(e)
    return TextFieldValue(next, TextRange(s + sym.length))
}

/** 去除每行末尾多余空格与制表符，无损保留文件尾换行状态。 */
internal fun trimTrailingWhitespace(text: String): String {
    if (text.isEmpty()) return text
    val hadTrailing = text.endsWith("\n")
    val body = if (hadTrailing) text.dropLast(1) else text
    val cleaned = body.split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }
    return if (hadTrailing) "$cleaned\n" else cleaned
}

/** 轻量 XML / HTML 缩进整理（零第三方依赖，仅在标签闭合合法时返回格式化文本）。 */
internal fun formatXmlBasic(raw: String, indentSpaces: Int = 2): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("<") || !trimmed.endsWith(">")) return null
    val compact = trimmed.replace(Regex(">\\s*<"), ">\n<")
    val padUnit = " ".repeat(indentSpaces.coerceIn(2, 4))
    var depth = 0
    val out = StringBuilder()
    for (line in compact.lineSequence()) {
        val s = line.trim()
        if (s.isEmpty()) continue
        val isClosing = s.startsWith("</")
        val isSelfClosing = s.endsWith("/>") || s.startsWith("<?") || s.startsWith("<!") ||
            (s.startsWith("<") && s.contains("</"))
        if (isClosing) depth = (depth - 1).coerceAtLeast(0)
        if (out.isNotEmpty()) out.append('\n')
        out.append(padUnit.repeat(depth)).append(s)
        if (!isClosing && !isSelfClosing && s.startsWith("<") && s.endsWith(">")) {
            depth++
        }
    }
    return out.toString()
}

internal fun formatDraftAge(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffSec = ((nowMs - updatedAtMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3600L -> "${diffSec / 60L} 分钟前"
        diffSec < 86_400L -> "${diffSec / 3600L} 小时前"
        else -> "${diffSec / 86_400L} 天前"
    }
}
