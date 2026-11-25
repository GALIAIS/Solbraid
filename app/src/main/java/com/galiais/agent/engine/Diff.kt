package com.galiais.agent.engine

/** 生成带 3 行上下文的统一 diff（基于 LCS）；内容过大时退化为整体替换。 */
fun unifiedDiff(path: String, before: String, after: String, context: Int = 3): String {
    val old = if (before.isEmpty()) emptyList() else before.removeSuffix("\n").split('\n')
    val new = if (after.isEmpty()) emptyList() else after.removeSuffix("\n").split('\n')
    val header = "--- a/$path\n+++ b/$path\n"
    if (old.size.toLong() * new.size > 4_000_000L) {
        return header + "@@ -1,${old.size} +1,${new.size} @@\n" + old.joinToString("") { "-$it\n" } + new.joinToString("") { "+$it\n" }
    }
    // LCS 表（从尾部计算），再顺序回溯生成编辑序列。
    val table = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed())
        table[i][j] = if (old[i] == new[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
    data class Edit(val type: Char, val text: String, val oldLine: Int, val newLine: Int)
    val edits = ArrayList<Edit>()
    var i = 0; var j = 0
    while (i < old.size || j < new.size) {
        when {
            i < old.size && j < new.size && old[i] == new[j] -> { edits += Edit(' ', old[i], i, j); i++; j++ }
            j < new.size && (i == old.size || table[i][j + 1] >= table[i + 1][j]) -> { edits += Edit('+', new[j], i, j); j++ }
            else -> { edits += Edit('-', old[i], i, j); i++ }
        }
    }
    val changed = edits.indices.filter { edits[it].type != ' ' }
    if (changed.isEmpty()) return header
    val out = StringBuilder(header)
    var index = 0
    while (index < changed.size) {
        val start = maxOf(0, changed[index] - context)
        var end = minOf(edits.size - 1, changed[index] + context)
        while (index + 1 < changed.size && changed[index + 1] - context <= end + 1) { index++; end = minOf(edits.size - 1, changed[index] + context) }
        val hunk = edits.subList(start, end + 1)
        val oldCount = hunk.count { it.type != '+' }
        val newCount = hunk.count { it.type != '-' }
        out.append("@@ -${hunk.first().oldLine + if (oldCount == 0) 0 else 1},$oldCount +${hunk.first().newLine + if (newCount == 0) 0 else 1},$newCount @@\n")
        hunk.forEach { out.append(it.type).append(it.text).append('\n') }
        index++
    }
    return out.toString()
}
