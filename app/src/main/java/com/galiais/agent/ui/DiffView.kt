package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.FileDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DiffLine(val kind: Char, val text: String, val oldNumber: Int?, val newNumber: Int?)

private val hunkHeader = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")

private fun parseDiff(diff: String): List<DiffLine> {
    val lines = ArrayList<DiffLine>()
    var old = 0; var new = 0
    for (raw in diff.lineSequence()) {
        if (raw.startsWith("--- ") || raw.startsWith("+++ ") || raw.startsWith("diff ") || raw.startsWith("index ")) continue
        val header = hunkHeader.matchEntire(raw)
        when {
            header != null -> { old = header.groupValues[1].toInt(); new = header.groupValues[2].toInt(); lines += DiffLine('@', raw, null, null) }
            raw.startsWith("+") -> lines += DiffLine('+', raw.substring(1), null, new++)
            raw.startsWith("-") -> lines += DiffLine('-', raw.substring(1), old++, null)
            raw.startsWith("\\") -> lines += DiffLine('\\', raw, null, null)
            raw.isEmpty() && lines.isEmpty() -> {}
            else -> lines += DiffLine(' ', raw.removePrefix(" "), old++, new++)
        }
    }
    return lines
}

fun diffStats(diff: String): Pair<Int, Int> {
    var added = 0; var removed = 0
    var inHunk = false
    for (line in diff.lineSequence()) {
        if (line.startsWith("diff --git ") || line.startsWith("--- ") || line.startsWith("+++ ")) {
            inHunk = false
            continue
        }
        if (hunkHeader.matches(line)) {
            inHunk = true
            continue
        }
        if (!inHunk) continue
        if (line.startsWith("+")) added++
        else if (line.startsWith("-")) removed++
    }
    // 兼容无 @@ 头的极简片段回退
    if (added == 0 && removed == 0 && !diff.contains("@@ ")) {
        for (line in diff.lineSequence()) {
            if (line.startsWith("+") && !line.startsWith("+++ ")) added++
            else if (line.startsWith("-") && !line.startsWith("--- ")) removed++
        }
    }
    return added to removed
}

/** 现代 IDE 风格 Diff 查看器：带状态色条、双行号分隔栏、增删徽章与内联有界高度保护。 */
@Composable
fun DiffView(
    change: FileDiff,
    modifier: Modifier = Modifier,
    inlineCompact: Boolean = false,
) {
    val lines = remember(change.diff) { parseDiff(change.diff) }
    val initialLimit = if (inlineCompact) 28 else 320
    var expanded by remember(change.diff, inlineCompact) { mutableStateOf(false) }
    val visible = if (expanded) lines else lines.take(initialLimit)
    val (added, removed) = remember(change.diff) { diffStats(change.diff) }
    val addedBg = G.diffAddedBg; val addedInk = G.diffAddedInk
    val removedBg = G.diffRemovedBg; val removedInk = G.diffRemovedInk
    val hunkBg = G.diffHunkBg; val hunkInk = G.diffHunkInk
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1300); copied = false } }

    val cleanPath = change.path.trimStart('/')
    val slash = cleanPath.lastIndexOf('/')
    val dir = if (slash >= 0) cleanPath.substring(0, slash + 1) else ""
    val fileName = if (slash >= 0) cleanPath.substring(slash + 1) else cleanPath

    Surface(
        modifier.fillMaxWidth(),
        shape = D.shape(11.dp),
        color = G.surface,
        border = BorderStroke(0.75.dp, G.border),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (G.dark) G.surfaceElevated else G.surfaceSubtle)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val badge = when (change.kind) { "add" -> "新增"; "delete" -> "删除"; else -> "修改" }
                val kindFg = when (change.kind) { "add" -> G.success; "delete" -> G.danger; else -> G.accent }
                val kindBg = when (change.kind) { "add" -> G.successTint; "delete" -> G.dangerTint; else -> G.accentContainer }
                Icon(fileDiffIcon(change.kind), contentDescription = null, tint = kindFg, modifier = Modifier.size(13.5.dp))
                TagPill(badge, fg = kindFg, bg = kindBg)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (dir.isNotEmpty()) {
                        Text(
                            dir,
                            fontFamily = Mono,
                            fontSize = 10.5.sp,
                            color = G.inkSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(
                        fileName,
                        fontFamily = Mono,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = G.ink,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (added > 0) {
                    Text(
                        "+$added",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = addedInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(addedBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (removed > 0) {
                    Text(
                        "-$removed",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = removedInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(removedBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (change.diff.isNotBlank()) {
                    Row(
                        modifier = Modifier.clip(D.shape(5.dp)).clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("diff", change.diff).toClipEntry())
                                copied = true
                            }
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(if (copied) GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copied) G.success else G.inkMuted, modifier = Modifier.size(11.5.dp))
                        Text(if (copied) "已复制" else "复制", fontSize = 10.5.sp, color = if (copied) G.success else G.inkMuted)
                    }
                }
            }
            Hairline()
            val bodyModifier = if (inlineCompact) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (expanded) 440.dp else 240.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            }
            val dividerColor = G.border.copy(alpha = 0.65f)
            Column(bodyModifier.padding(vertical = 4.dp)) {
                for (line in visible) {
                    val (bg, fg, stripe) = when (line.kind) {
                        '+' -> Triple(addedBg, addedInk, G.success)
                        '-' -> Triple(removedBg, removedInk, G.danger)
                        '@' -> Triple(hunkBg, hunkInk, G.accent)
                        else -> Triple(Color.Transparent, G.ink, Color.Transparent)
                    }
                    // 使用 drawBehind 绘制左侧状态色条与行号分隔线，消除逐行 IntrinsicSize.Min 双次测量卡顿。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .drawBehind {
                                if (stripe != Color.Transparent) {
                                    drawRect(color = stripe, size = Size(3.dp.toPx(), size.height))
                                }
                                drawRect(
                                    color = dividerColor,
                                    topLeft = Offset(63.dp.toPx(), 0f),
                                    size = Size(0.75.dp.toPx(), size.height),
                                )
                            }
                            .padding(start = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            line.oldNumber?.toString() ?: "",
                            fontFamily = Mono,
                            fontSize = D.codeSize(10f),
                            color = G.inkSubtle,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(30.dp).padding(horizontal = 3.dp, vertical = 1.dp),
                        )
                        Text(
                            line.newNumber?.toString() ?: "",
                            fontFamily = Mono,
                            fontSize = D.codeSize(10f),
                            color = G.inkSubtle,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(30.dp).padding(end = 5.dp, top = 1.dp, bottom = 1.dp),
                        )
                        val marker = when (line.kind) {
                            '+' -> "+"
                            '-' -> "-"
                            ' ' -> " "
                            else -> ""
                        }
                        Text(
                            marker + line.text,
                            fontFamily = Mono,
                            fontSize = D.codeSize(11.5f),
                            lineHeight = D.codeSize(16.5f),
                            color = fg,
                            softWrap = false,
                            modifier = Modifier.padding(start = 7.75.dp, end = 14.dp, top = 1.dp, bottom = 1.dp),
                        )
                    }
                }
            }
            if (lines.size > initialLimit) {
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(G.surfaceSubtle.copy(alpha = 0.7f))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (expanded) "收起至前 $initialLimit 行" else "展开全部 ${lines.size} 行差异（剩余 ${lines.size - initialLimit} 行）",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = G.accent,
                    )
                }
            }
            if (lines.isEmpty()) Text("（没有可显示的改动内容）", fontSize = 12.sp, color = G.inkMuted, modifier = Modifier.padding(10.dp))
        }
    }
}
