package com.galiais.agent.engine

/**
 * 基于「换行归一化 + O(N) 公共前后缀裁剪 + Myers O((N+M)D) + 唯一行锚点分治」的统一 Diff 生成器：
 * - 即使在 10,000+ 行大文件中仅改 1~50 行，也只输出精准的最小 hunk，杜绝原先 old.size * new.size > 4_000_000 直接退化为整文件数千行替换的问题；
 * - 内存与耗时只与实际编辑距离 D 相关，不再分配 N×M 全量二维表。
 */
fun unifiedDiff(path: String, before: String, after: String, context: Int = 3): String {
    val old = splitNormalizedLines(before)
    val new = splitNormalizedLines(after)
    val header = "--- a/$path\n+++ b/$path\n"
    if (old.isEmpty() && new.isEmpty()) return header
    val edits = computeDiffOps(old, new)
    val changed = edits.indices.filter { edits[it].type != ' ' }
    if (changed.isEmpty()) return header
    val out = StringBuilder(header)
    var index = 0
    while (index < changed.size) {
        val start = maxOf(0, changed[index] - context)
        var end = minOf(edits.size - 1, changed[index] + context)
        while (index + 1 < changed.size && changed[index + 1] - context <= end + 1) {
            index++
            end = minOf(edits.size - 1, changed[index] + context)
        }
        val hunk = edits.subList(start, end + 1)
        val oldCount = hunk.count { it.type != '+' }
        val newCount = hunk.count { it.type != '-' }
        val oldStart = hunk.first().oldLine + if (oldCount == 0) 0 else 1
        val newStart = hunk.first().newLine + if (newCount == 0) 0 else 1
        out.append("@@ -").append(oldStart).append(',').append(oldCount)
            .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
        for (e in hunk) {
            out.append(e.type).append(e.text).append('\n')
        }
        index++
    }
    return out.toString()
}

/** 供编辑器 3-Way 冲突对比与文件对比弹窗复用的行级 Diff（带精准对齐与最大行数上限）。 */
fun computeMyersLineDiff(before: String, after: String, maxLines: Int = 400): List<Pair<Char, String>> {
    val old = splitNormalizedLines(before)
    val new = splitNormalizedLines(after)
    val ops = computeDiffOps(old, new)
    if (ops.size <= maxLines) return ops.map { it.type to it.text }
    // 超过上限时优先保留变更行及前后 3 行上下文，避免整篇被前置不变行占满
    val keep = BooleanArray(ops.size)
    for (idx in ops.indices) {
        if (ops[idx].type != ' ') {
            val lo = maxOf(0, idx - 3)
            val hi = minOf(ops.lastIndex, idx + 3)
            for (k in lo..hi) keep[k] = true
        }
    }
    val out = ArrayList<Pair<Char, String>>(minOf(ops.size, maxLines))
    for (idx in ops.indices) {
        if (keep[idx]) {
            out += ops[idx].type to ops[idx].text
            if (out.size >= maxLines) break
        }
    }
    if (out.isEmpty()) {
        for (i in 0 until minOf(ops.size, maxLines)) out += ops[i].type to ops[i].text
    }
    return out
}

private data class DiffOp(val type: Char, val text: String, val oldLine: Int, val newLine: Int)

private fun splitNormalizedLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val norm = if (text.indexOf('\r') >= 0) text.replace("\r\n", "\n").replace('\r', '\n') else text
    return norm.removeSuffix("\n").split('\n')
}

private fun computeDiffOps(old: List<String>, new: List<String>): List<DiffOp> {
    val out = ArrayList<DiffOp>(maxOf(old.size, new.size) + 16)
    diffRange(old, 0, old.size, new, 0, new.size, out, depth = 0)
    return out
}

private fun diffRange(
    old: List<String>,
    oStart: Int,
    oEnd: Int,
    new: List<String>,
    nStart: Int,
    nEnd: Int,
    out: MutableList<DiffOp>,
    depth: Int,
) {
    var a0 = oStart
    var a1 = oEnd
    var b0 = nStart
    var b1 = nEnd

    // 1. O(N) 公共前缀裁剪
    while (a0 < a1 && b0 < b1 && old[a0] == new[b0]) {
        out += DiffOp(' ', old[a0], a0, b0)
        a0++
        b0++
    }

    // 2. O(N) 公共后缀裁剪（暂存末尾位置，处理完核心后再顺序追加）
    var suffixLen = 0
    while (a0 < a1 && b0 < b1 && old[a1 - 1] == new[b1 - 1]) {
        a1--
        b1--
        suffixLen++
    }

    val n = a1 - a0
    val m = b1 - b0
    when {
        n == 0 -> {
            for (j in b0 until b1) out += DiffOp('+', new[j], a0, j)
        }
        m == 0 -> {
            for (i in a0 until a1) out += DiffOp('-', old[i], i, b0)
        }
        else -> {
            // 3. 优先使用 Myers O((N+M)D) 算法（仅存储 [-d..d] 紧凑轨迹切片）
            val maxD = minOf(n + m, 1200)
            val myersOps = tryMyersDiff(old, a0, n, new, b0, m, maxD)
            if (myersOps != null) {
                out.addAll(myersOps)
            } else if (depth < 4) {
                // 4. 大跨度改动（如万行文件同时改动多处）：按唯一共同行锚点做耐心分治，避免退化为整文件替换
                val anchors = findUniqueAnchors(old, a0, a1, new, b0, b1)
                if (anchors.isNotEmpty()) {
                    var curA = a0
                    var curB = b0
                    for ((ai, bi) in anchors) {
                        if (ai > curA || bi > curB) {
                            diffRange(old, curA, ai, new, curB, bi, out, depth + 1)
                        }
                        out += DiffOp(' ', old[ai], ai, bi)
                        curA = ai + 1
                        curB = bi + 1
                    }
                    if (curA < a1 || curB < b1) {
                        diffRange(old, curA, a1, new, curB, b1, out, depth + 1)
                    }
                } else {
                    for (i in a0 until a1) out += DiffOp('-', old[i], i, b0)
                    for (j in b0 until b1) out += DiffOp('+', new[j], a1, j)
                }
            } else {
                for (i in a0 until a1) out += DiffOp('-', old[i], i, b0)
                for (j in b0 until b1) out += DiffOp('+', new[j], a1, j)
            }
        }
    }

    for (k in 0 until suffixLen) {
        out += DiffOp(' ', old[a1 + k], a1 + k, b1 + k)
    }
}

/**
 * 标准 Myers 最短编辑脚本（SES）：仅保存每轮 d 实际访问的 [-d..d] 切片（长度 2d+1），
 * 常见编辑（D <= 200）仅需数十 KB 内存与 <1ms 计算时间；超过 maxD 返回 null 交由锚点分治。
 */
private fun tryMyersDiff(
    old: List<String>,
    a0: Int,
    n: Int,
    new: List<String>,
    b0: Int,
    m: Int,
    maxD: Int,
): List<DiffOp>? {
    val offset = maxD
    val v = IntArray(2 * maxD + 1)
    val trace = ArrayList<IntArray>()
    var foundD = -1

    outer@ for (d in 0..maxD) {
        // 仅保存 [-d..d] 切片（2d + 1 个 Int）
        val slice = IntArray(2 * d + 1)
        System.arraycopy(v, offset - d, slice, 0, 2 * d + 1)
        trace.add(slice)

        var k = -d
        while (k <= d) {
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                v[offset + k + 1]
            } else {
                v[offset + k - 1] + 1
            }
            var y = x - k
            while (x < n && y < m && old[a0 + x] == new[b0 + y]) {
                x++
                y++
            }
            v[offset + k] = x
            if (x >= n && y >= m) {
                foundD = d
                break@outer
            }
            k += 2
        }
    }
    if (foundD < 0) return null

    // 反向回溯还原精确操作序列
    val reversed = ArrayList<DiffOp>(n + m)
    var x = n
    var y = m
    for (d in foundD downTo 0) {
        val slice = trace[d]
        val k = x - y
        fun prevV(idx: Int): Int = slice[idx + d]
        val prevK = if (d == 0) {
            0
        } else if (k == -d || (k != d && prevV(k - 1) < prevV(k + 1))) {
            k + 1
        } else {
            k - 1
        }
        val prevX = if (d == 0) 0 else prevV(prevK)
        val prevY = prevX - prevK

        while (x > prevX && y > prevY) {
            x--
            y--
            reversed += DiffOp(' ', old[a0 + x], a0 + x, b0 + y)
        }
        if (d > 0) {
            if (x == prevX) {
                y--
                reversed += DiffOp('+', new[b0 + y], a0 + x, b0 + y)
            } else {
                x--
                reversed += DiffOp('-', old[a0 + x], a0 + x, b0 + y)
            }
        }
    }
    reversed.reverse()
    return reversed
}

/** 找出 old[a0..a1) 与 new[b0..b1) 中各仅出现 1 次的共同行，再取最长递增子序列（Patience 锚点）。 */
private fun findUniqueAnchors(
    old: List<String>,
    a0: Int,
    a1: Int,
    new: List<String>,
    b0: Int,
    b1: Int,
): List<Pair<Int, Int>> {
    val oldPos = HashMap<String, Int>((a1 - a0) * 2)
    for (i in a0 until a1) {
        val line = old[i]
        if (line.isBlank()) continue
        oldPos[line] = if (oldPos.containsKey(line)) -1 else i
    }
    val newPos = HashMap<String, Int>((b1 - b0) * 2)
    for (j in b0 until b1) {
        val line = new[j]
        if (line.isBlank()) continue
        newPos[line] = if (newPos.containsKey(line)) -1 else j
    }
    val matches = ArrayList<Pair<Int, Int>>()
    for (i in a0 until a1) {
        val line = old[i]
        val oi = oldPos[line] ?: continue
        if (oi < 0) continue
        val nj = newPos[line] ?: continue
        if (nj >= 0) matches += oi to nj
    }
    if (matches.isEmpty()) return emptyList()
    // 对匹配对按 nj 求 O(K log K) 最长递增子序列
    val tails = IntArray(matches.size)
    val prev = IntArray(matches.size) { -1 }
    var len = 0
    for (idx in matches.indices) {
        val target = matches[idx].second
        var lo = 0
        var hi = len
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (matches[tails[mid]].second < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0) prev[idx] = tails[lo - 1]
        tails[lo] = idx
        if (lo == len) len++
    }
    val result = ArrayList<Pair<Int, Int>>(len)
    var cur = tails[len - 1]
    while (cur >= 0) {
        result += matches[cur]
        cur = prev[cur]
    }
    result.reverse()
    return result
}
