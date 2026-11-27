package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.BackgroundTaskEntry
import com.galiais.agent.engine.ContextGridCell
import com.galiais.agent.engine.ContextUsageBreakdown
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.EngineDiagnosticsReport
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.EngineUsageReport
import com.galiais.agent.engine.MemoryStatusSummary
import com.galiais.agent.engine.PluginReloadResult
import com.galiais.agent.engine.QueuedSubmission
import com.galiais.agent.engine.SubagentMessageEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun GaliaisTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = G.inkSubtle, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        shape = D.shape(10.dp),
        colors = galiaisTextFieldColors(),
    )
}

/** 三端统一 `/usage` 实时配额与会话用量面板。 */
@Composable
fun EngineUsageSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    onOpenNativeTui: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<EngineUsageReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                report = app.conversations.usageReport(key)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "读取配额失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(key) { reload() }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${key.engine.label} · 用量与配额 (/usage)", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    report?.let { r ->
                        val meta = listOfNotNull(r.planLabel?.uppercase(), r.accountLabel).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                }
                SecondaryPillButton("刷新", enabled = !loading, onClick = { reload() })
            }

            if (loading) {
                GaliaisProgressBar(progress = null)
                Text("正在查询官方实时配额与会话用量…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            } else if (error != null) {
                CrispCard {
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = G.danger)
                }
            } else {
                val r = report
                if (r != null) {
                    if (r.windows.isNotEmpty()) {
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("官方订阅限额窗口", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                r.windows.forEach { w ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(w.label, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                                            Text(
                                                "${w.usedPercent}%",
                                                fontFamily = Mono,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (w.usedPercent >= 85) G.danger else G.accent,
                                            )
                                        }
                                        GaliaisProgressBar(progress = (w.usedPercent / 100f).coerceIn(0f, 1f))
                                        w.resetsAt?.let { rst ->
                                            Text("重置时间：$rst", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                                        }
                                    }
                                }
                                r.ordinaryUsageAllowed?.let { allowed ->
                                    Text(
                                        if (allowed) "常规额度状态：可用" else "常规额度状态：已触顶或受限",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allowed) G.success else G.danger,
                                    )
                                }
                            }
                        }
                    }

                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("当前会话统计", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            r.sessionCostUsd?.let { cost ->
                                Text("估算会话费用：$${String.format(Locale.ROOT, "%.4f", cost)}", fontFamily = Mono, fontSize = 12.5.sp, color = G.ink)
                            }
                            r.sessionTokens?.let { tok ->
                                Text("当前/累计 Token：${formatTokenCount(tok)}", fontFamily = Mono, fontSize = 12.5.sp, color = G.ink)
                            }
                            if (r.linesAdded > 0 || r.linesRemoved > 0) {
                                Text("代码变更行数：+${r.linesAdded} / -${r.linesRemoved}", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                            }
                            if (r.apiDurationMs > 0 || r.totalDurationMs > 0) {
                                Text(
                                    "耗时：API ${r.apiDurationMs / 1000}s / 总计 ${r.totalDurationMs / 1000}s",
                                    fontFamily = Mono,
                                    fontSize = 12.sp,
                                    color = G.inkMuted,
                                )
                            }
                            if (r.modelBreakdown.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("分模型明细", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                                r.modelBreakdown.forEach { (m, detail) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(m, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(8.dp))
                                        Text(detail, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                                    }
                                }
                            }
                        }
                    }

                    r.note?.let { n ->
                        Text(n, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    r.tuiHint?.let { hint ->
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(hint, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                PrimaryPillButton("打开原生 TUI 查看实时配额", onClick = {
                                    onDismiss()
                                    onOpenNativeTui()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveCategoryColor(id: String, kind: String, colorHex: String?): Color {
    colorHex?.trim()?.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()?.let { return it }
    }
    return when {
        kind == "free" -> Color(0xFF94A3B8).copy(alpha = 0.25f)
        kind == "buffer" -> Color(0xFFF59E0B)
        kind == "deferred" -> Color(0xFF64748B)
        id.contains("system_prompt") -> Color(0xFF6366F1)
        id.contains("system_tools") -> Color(0xFF8B5CF6)
        id.contains("mcp") -> Color(0xFF0EA5E9)
        id.contains("memory") -> Color(0xFF10B981)
        id.contains("skill") -> Color(0xFF14B8A6)
        id.contains("agent") -> Color(0xFFEC4899)
        id.contains("uncached") -> Color(0xFF3B82F6)
        id.contains("cached") -> Color(0xFF10B981)
        id.contains("reasoning") -> Color(0xFF8B5CF6)
        id.contains("output") -> Color(0xFFF97316)
        else -> Color(0xFFD97757)
    }
}

private fun buildProportionalGrid(d: ContextUsageBreakdown): List<List<ContextGridCell>> {
    if (d.gridRows.isNotEmpty()) return d.gridRows
    if (d.maxTokens <= 0L || d.categories.isEmpty()) return emptyList()
    val totalCells = 50
    val flat = mutableListOf<ContextGridCell>()
    d.categories.filter { it.tokens > 0L }.forEach { row ->
        val share = ((row.tokens.toDouble() / d.maxTokens.toDouble()) * totalCells).toInt()
            .coerceAtLeast(if (row.kind != "free" && row.tokens > 0L) 1 else 0)
        repeat(share) {
            if (flat.size < totalCells) flat += ContextGridCell(row.id, row.colorHex, row.kind)
        }
    }
    while (flat.size < totalCells) flat += ContextGridCell("free_space", null, "free")
    return flat.chunked(10)
}

/** 三端统一 `/context` 上下文窗口分类拆解面板。 */
@Composable
fun ContextBreakdownSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    onCompact: () -> Unit,
    onOpenNativeTui: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<ContextUsageBreakdown?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        scope.launch {
            loading = true
            error = null
            try {
                data = app.conversations.contextBreakdown(key)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "读取上下文拆解失败"
            } finally {
                loading = false
            }
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("上下文窗口拆解 (/context)", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    data?.model?.let { Text(it, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted) }
                }
                SecondaryPillButton("压缩上下文", onClick = { onDismiss(); onCompact() })
            }

            if (loading) {
                GaliaisProgressBar(progress = null)
                Text("正在统计系统提示、工具定义、记忆文件与历史消息占比…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            } else if (error != null) {
                CrispCard { Text(error!!, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            } else {
                val d = data
                if (d != null) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${formatTokenCount(d.totalTokens)} / ${formatTokenCount(d.maxTokens)}",
                                    fontFamily = Mono,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = G.ink,
                                )
                                Text(
                                    String.format(Locale.ROOT, "%.1f%%", d.percentage),
                                    fontFamily = Mono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (d.percentage >= 80.0) G.danger else G.accent,
                                )
                            }
                            GaliaisProgressBar(progress = (d.percentage / 100.0).toFloat().coerceIn(0f, 1f))
                            val grid = remember(d) { buildProportionalGrid(d) }
                            if (grid.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    grid.forEach { rowCells ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            rowCells.forEach { cell ->
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(10.dp)
                                                        .clip(D.shape(3.dp))
                                                        .background(resolveCategoryColor(cell.categoryId, cell.kind, cell.colorHex)),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (d.categories.isNotEmpty()) {
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("分类占用明细", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                d.categories.forEach { row ->
                                    val frac = if (d.maxTokens > 0) (row.tokens * 100.0 / d.maxTokens) else 0.0
                                    val dotColor = resolveCategoryColor(row.id, row.kind, row.colorHex)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ColorSwatchPill(dotColor)
                                        Text(row.name, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                                        Text(
                                            "${formatTokenCount(row.tokens)} (${String.format(Locale.ROOT, "%.1f%%", frac)})",
                                            fontFamily = Mono,
                                            fontSize = 11.5.sp,
                                            color = G.inkMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (d.memoryFiles.isNotEmpty()) {
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("已加载记忆文件 (${d.memoryFiles.size})", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                d.memoryFiles.forEach { (p, type, tok) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("$p ($type)", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(8.dp))
                                        Text(formatTokenCount(tok), fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                                    }
                                }
                            }
                        }
                    }

                    if (d.mcpTools.isNotEmpty()) {
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("MCP 工具占用 (Top ${minOf(d.mcpTools.size, 12)})", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                d.mcpTools.sortedByDescending { it.third }.take(12).forEach { (srv, tool, tok) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("$srv:$tool", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(8.dp))
                                        Text(formatTokenCount(tok), fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                                    }
                                }
                            }
                        }
                    }

                    d.note?.let { n ->
                        CrispCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(n, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                SecondaryPillButton("切入原生 TUI 查看 /context", onClick = { onDismiss(); onOpenNativeTui() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 三端统一 `/doctor` 环境与协议健康体检面板。 */
@Composable
fun DiagnosticsSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<EngineDiagnosticsReport?>(null) }

    LaunchedEffect(key) {
        scope.launch {
            loading = true
            report = runCatching { app.conversations.diagnosticsReport(key) }
                .getOrElse { EngineDiagnosticsReport(key.engine, false, summary = "体检异常：${it.message}") }
            loading = false
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${key.engine.label} · 健康诊断 (/doctor)", style = MaterialTheme.typography.titleLarge, color = G.ink)
            if (loading) {
                GaliaisProgressBar(progress = null)
                Text("正在执行官方诊断检查…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            } else {
                val r = report
                if (r != null) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TagPill(if (r.ok) "正常" else "需关注", fg = if (r.ok) G.success else G.danger, bg = if (r.ok) G.successTint else G.dangerTint)
                                Text(r.summary, style = MaterialTheme.typography.titleSmall, color = G.ink)
                            }
                            r.version?.let { Text("版本：$it", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted) }
                            r.metrics.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(k, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                    Text(v, fontFamily = Mono, fontSize = 12.sp, color = G.ink)
                                }
                            }
                        }
                    }
                    if (r.rawOutput.isNotBlank()) {
                        CrispCard {
                            Text(r.rawOutput.take(6000), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                        }
                    }
                }
            }
        }
    }
}

/** `/add-dir` 为当前会话附加额外工作区目录弹窗。 */
@Composable
fun AddDirectoryDialog(
    currentDirs: List<String>,
    defaultCwd: String,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(defaultCwd) }
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("附加工作区目录 (/add-dir)", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text("添加后 Agent 可在当前会话中同时读写该目录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            if (currentDirs.isNotEmpty()) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已附加目录 (${currentDirs.size})", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                        currentDirs.forEach { d -> Text(d, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink) }
                    }
                }
            }
            GaliaisTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "/home/agent/workspace/other-repo",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryPillButton("取消", modifier = Modifier.weight(1f), onClick = onDismiss)
                PrimaryPillButton("添加目录", modifier = Modifier.weight(1f), enabled = input.trim().startsWith("/"), onClick = {
                    onAdd(input.trim())
                    onDismiss()
                })
            }
        }
    }
}

/** Codex 原生队列与后台终端 / Claude Ctrl+B 转后台任务治理面板（`/queue` · `/tasks`）。 */
@Composable
fun QueueAndTasksSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    queued: List<QueuedSubmission>,
    claudeTasks: List<BackgroundTaskEntry> = emptyList(),
    running: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newQueuedText by remember { mutableStateOf("") }
    var editingQueued by remember { mutableStateOf<QueuedSubmission?>(null) }
    var editingQueuedText by remember { mutableStateOf("") }
    var bgTasks by remember { mutableStateOf<List<BackgroundTaskEntry>>(emptyList()) }
    var loadingTasks by remember { mutableStateOf(false) }
    var stopTaskIdInput by remember { mutableStateOf("") }

    fun refreshAll() {
        scope.launch {
            loadingTasks = true
            try {
                if (key.engine == EngineKind.CODEX) {
                    runCatching { app.conversations.codex.listQueue(key) }
                    bgTasks = runCatching { app.conversations.codex.listBackgroundTerminals(key) }.getOrDefault(emptyList())
                }
            } finally {
                loadingTasks = false
            }
        }
    }

    LaunchedEffect(key) { refreshAll() }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (key.engine == EngineKind.CODEX) "消息队列与后台终端 (/queue · /tasks)" else "后台任务控制 (/tasks · Ctrl+B)",
                    style = MaterialTheme.typography.titleLarge,
                    color = G.ink,
                    modifier = Modifier.weight(1f),
                )
                SecondaryPillButton("刷新", enabled = !loadingTasks, onClick = { refreshAll() })
            }

            if (key.engine == EngineKind.CODEX) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("原生消息队列 (${queued.size})", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                            if (queued.isNotEmpty() && !running) {
                                TagPill("立即执行队首", fg = G.onAccent, bg = G.accent, onClick = {
                                    scope.launch {
                                        runCatching { app.conversations.codex.startQueuedNow(key) }
                                            .onFailure { app.conversations.notify("启动队列失败：${it.message}") }
                                    }
                                })
                            }
                        }
                        if (queued.isEmpty()) {
                            Text("队列为空。任务执行期间可先将后续指令加入队列，按顺序自动接力执行。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        } else {
                            queued.forEachIndexed { idx, item ->
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TagPill("#${idx + 1}", fg = G.accent, bg = G.accentContainer)
                                        Text(item.text, style = MaterialTheme.typography.bodySmall, color = G.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (idx > 0) {
                                            TagPill("上移", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                                val ids = queued.map { it.id }.toMutableList()
                                                ids[idx] = ids[idx - 1].also { ids[idx - 1] = ids[idx] }
                                                scope.launch {
                                                    runCatching { app.conversations.codex.reorderQueuedMessages(key, ids) }
                                                        .onFailure { app.conversations.notify("重排失败：${it.message}") }
                                                }
                                            })
                                        }
                                        if (idx < queued.lastIndex) {
                                            TagPill("下移", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                                val ids = queued.map { it.id }.toMutableList()
                                                ids[idx] = ids[idx + 1].also { ids[idx + 1] = ids[idx] }
                                                scope.launch {
                                                    runCatching { app.conversations.codex.reorderQueuedMessages(key, ids) }
                                                        .onFailure { app.conversations.notify("重排失败：${it.message}") }
                                                }
                                            })
                                        }
                                        TagPill("编辑", fg = G.accent, bg = G.accentContainer, onClick = {
                                            editingQueued = item
                                            editingQueuedText = item.text
                                        })
                                        TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = {
                                            scope.launch {
                                                runCatching { app.conversations.codex.deleteQueuedMessage(key, item.id) }
                                                    .onFailure { app.conversations.notify("移除失败：${it.message}") }
                                            }
                                        })
                                    }
                                }
                            }
                            editingQueued?.let { target ->
                                Column(
                                    Modifier.fillMaxWidth().clip(D.shape(10.dp)).background(G.surfaceSubtle).padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("编辑队列指令", style = MaterialTheme.typography.labelMedium, color = G.accent)
                                    GaliaisTextField(value = editingQueuedText, onValueChange = { editingQueuedText = it }, placeholder = "修改队列指令内容…")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SecondaryPillButton("取消", onClick = { editingQueued = null })
                                        PrimaryPillButton("保存修改", enabled = editingQueuedText.isNotBlank(), onClick = {
                                            val updatedText = editingQueuedText.trim()
                                            editingQueued = null
                                            scope.launch {
                                                runCatching { app.conversations.codex.updateQueuedMessage(key, target.id, updatedText) }
                                                    .onSuccess { app.conversations.notify("已更新队列指令") }
                                                    .onFailure { app.conversations.notify("更新队列失败：${it.message}") }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        GaliaisTextField(value = newQueuedText, onValueChange = { newQueuedText = it }, placeholder = "输入要排队执行的后续指令…")
                        PrimaryPillButton(
                            "加入队列",
                            enabled = newQueuedText.isNotBlank(),
                            onClick = {
                                val text = newQueuedText.trim()
                                newQueuedText = ""
                                scope.launch {
                                    runCatching { app.conversations.codex.enqueueMessage(key, text) }
                                        .onSuccess { app.conversations.notify("已加入 Codex 原生队列") }
                                        .onFailure { app.conversations.notify("入队失败：${it.message}") }
                                }
                            },
                        )
                    }
                }

                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("后台终端进程 (${bgTasks.size})", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                            if (bgTasks.isNotEmpty()) {
                                TagPill("清理已退出", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                    scope.launch {
                                        runCatching { app.conversations.codex.cleanBackgroundTerminals(key) }
                                        refreshAll()
                                    }
                                })
                            }
                        }
                        if (bgTasks.isEmpty()) {
                            Text("当前会话没有托管中的后台终端进程。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        } else {
                            bgTasks.forEach { task ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(task.label, style = MaterialTheme.typography.bodySmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${task.status} · ${task.cwd ?: ""}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TagPill("终止", fg = G.danger, bg = G.dangerTint, onClick = {
                                        scope.launch {
                                            runCatching { app.conversations.codex.terminateBackgroundTerminal(key, task.id) }
                                            refreshAll()
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            } else if (key.engine == EngineKind.CLAUDE) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("前景任务转后台（Ctrl+B）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        Text("当 Bash 命令或子代理耗时较长时，可将其立即转入后台运行并继续对话，完成后会以 task_notification 回报。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        PrimaryPillButton(
                            "将当前前景任务转入后台",
                            enabled = running,
                            onClick = {
                                scope.launch {
                                    runCatching { app.conversations.claude.backgroundTasks(key) }
                                        .onSuccess { moved ->
                                            app.conversations.notify(if (moved) "已将前景任务转入后台运行" else "当前没有可转入后台的阻塞前景任务")
                                        }
                                        .onFailure { app.conversations.notify("转入后台失败：${it.message}") }
                                }
                            },
                        )
                    }
                }
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("活跃后台任务 (${claudeTasks.size})", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        if (claudeTasks.isEmpty()) {
                            Text("当前未追踪到后台任务。将任务转入后台后会自动在此显示进度与一键停止按钮。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        } else {
                            claudeTasks.forEach { task ->
                                val active = task.status == "running" || task.status == "in_progress"
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(task.label, style = MaterialTheme.typography.bodySmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${task.status} · ${task.id.take(18)}" + (task.command.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = G.inkMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (active) {
                                        TagPill("停止", fg = G.danger, bg = G.dangerTint, onClick = {
                                            scope.launch {
                                                runCatching { app.conversations.claude.stopTask(key, task.id) }
                                                    .onSuccess { app.conversations.notify("已请求停止后台任务 ${task.id}") }
                                                    .onFailure { app.conversations.notify("停止任务失败：${it.message}") }
                                            }
                                        })
                                    } else {
                                        TagPill(task.status, fg = G.inkMuted, bg = G.surfaceSubtle)
                                    }
                                }
                            }
                        }
                    }
                }
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("按 Task ID 手动停止（兜底）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        GaliaisTextField(value = stopTaskIdInput, onValueChange = { stopTaskIdInput = it }, placeholder = "输入 taskId（来自任务通知）")
                        SecondaryPillButton(
                            "停止任务",
                            enabled = stopTaskIdInput.isNotBlank(),
                            onClick = {
                                val tid = stopTaskIdInput.trim()
                                scope.launch {
                                    runCatching { app.conversations.claude.stopTask(key, tid) }
                                        .onSuccess { stopTaskIdInput = ""; app.conversations.notify("已请求停止后台任务 $tid") }
                                        .onFailure { app.conversations.notify("停止任务失败：${it.message}") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Codex 长期记忆与会话目标面板（`/memory` · `/goal`）。 */
@Composable
fun MemoryAndGoalSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    currentGoal: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var goalInput by remember(currentGoal) { mutableStateOf(currentGoal.orEmpty()) }
    var memory by remember { mutableStateOf<MemoryStatusSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        scope.launch {
            loading = true
            if (key.engine == EngineKind.CODEX) {
                runCatching { app.conversations.codex.getGoal(key) }.getOrNull()?.let { if (goalInput.isBlank()) goalInput = it }
                memory = runCatching { app.conversations.codex.memoryStatus(key) }.getOrNull()
            }
            loading = false
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("长期记忆与目标锚点 (/memory · /goal)", style = MaterialTheme.typography.titleLarge, color = G.ink)
            if (key.engine == EngineKind.CODEX) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("会话目标锚点", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        GaliaisTextField(value = goalInput, onValueChange = { goalInput = it }, placeholder = "为当前长任务设定明确目标…")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryPillButton("保存目标", enabled = goalInput.isNotBlank(), onClick = {
                                scope.launch {
                                    runCatching { app.conversations.codex.setGoal(key, goalInput) }
                                        .onSuccess { app.conversations.notify("已更新会话目标") }
                                        .onFailure { app.conversations.notify("设置目标失败：${it.message}") }
                                }
                            })
                            if (!currentGoal.isNullOrBlank()) {
                                SecondaryPillButton("清除目标", onClick = {
                                    scope.launch {
                                        runCatching { app.conversations.codex.clearGoal(key) }
                                            .onSuccess { goalInput = ""; app.conversations.notify("已清除会话目标") }
                                            .onFailure { app.conversations.notify("清除失败：${it.message}") }
                                    }
                                })
                            }
                        }
                    }
                }

                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Codex 长期记忆状态", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        if (loading) {
                            GaliaisProgressBar(progress = null)
                        } else {
                            val m = memory
                            if (m != null) {
                                Text(
                                    "状态：${if (m.ready) "已就绪" else "未就绪"} · 已整合线程：${m.consolidatedThreads}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = G.inkMuted,
                                )
                                m.mode?.let { Text("当前线程记忆模式：$it", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted) }
                                if (m.content.isNotBlank()) {
                                    Text(m.content.take(2500), fontFamily = Mono, fontSize = 11.5.sp, color = G.ink)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SecondaryPillButton("启用记忆", onClick = {
                                        scope.launch {
                                            runCatching { app.conversations.codex.setMemoryMode(key, "enabled") }
                                                .onSuccess { app.conversations.notify("已启用本线程记忆") }
                                                .onFailure { app.conversations.notify("切换失败：${it.message}") }
                                        }
                                    })
                                    SecondaryPillButton("禁用记忆", onClick = {
                                        scope.launch {
                                            runCatching { app.conversations.codex.setMemoryMode(key, "disabled") }
                                                .onSuccess { app.conversations.notify("已禁用本线程记忆") }
                                                .onFailure { app.conversations.notify("切换失败：${it.message}") }
                                        }
                                    })
                                    SecondaryPillButton("重置全部记忆", onClick = { confirmReset = true })
                                }
                            } else {
                                Text("暂无法读取记忆状态（请确认 Codex 实验性功能 memories 已开启）。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmReset) {
        GaliaisConfirmDialog(
            title = "重置 Codex 长期记忆？",
            message = "此操作将清空所有已整合的跨会话记忆索引，不会删除对话记录。",
            confirmText = "确认重置",
            danger = true,
            onConfirm = {
                confirmReset = false
                scope.launch {
                    runCatching { app.conversations.codex.resetMemory() }
                        .onSuccess { app.conversations.notify("已重置 Codex 长期记忆") }
                        .onFailure { app.conversations.notify("重置失败：${it.message}") }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** Claude Code 子代理独立时间线查看器（`listSubagents` + `getSubagentMessages`）。 */
@Composable
fun SubagentsInspectorSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    initialAgentId: String? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var subagentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedAgent by remember(initialAgentId) { mutableStateOf<String?>(null) }
    var transcript by remember { mutableStateOf<List<SubagentMessageEntry>>(emptyList()) }
    var loadingTranscript by remember { mutableStateOf(false) }

    LaunchedEffect(key, initialAgentId) {
        scope.launch {
            loading = true
            val list = if (key.engine == EngineKind.CLAUDE) {
                runCatching { app.conversations.claude.listSubagents(key) }.getOrDefault(emptyList())
            } else emptyList()
            subagentIds = list
            loading = false
            val targetId = initialAgentId?.takeIf { it.isNotBlank() }?.let { raw ->
                list.firstOrNull { it == raw || it.contains(raw, ignoreCase = true) } ?: if (list.size == 1) list.first() else null
            }
            if (targetId != null) {
                selectedAgent = targetId
                loadingTranscript = true
                transcript = runCatching { app.conversations.claude.subagentMessages(key, targetId) }.getOrDefault(emptyList())
                loadingTranscript = false
            }
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectedAgent != null) {
                    IconButton(onClick = { selectedAgent = null; transcript = emptyList() }) {
                        Icon(GaliaisIcons.ArrowBack, contentDescription = "返回列表", tint = G.ink, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    if (selectedAgent != null) "子代理 · $selectedAgent" else "子代理执行记录 (/agents)",
                    style = MaterialTheme.typography.titleLarge,
                    color = G.ink,
                )
            }

            if (loading) {
                GaliaisProgressBar(progress = null)
            } else if (selectedAgent == null) {
                if (subagentIds.isEmpty()) {
                    CrispCard { Text("当前会话尚未产生子代理（Subagent）执行记录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
                } else {
                    subagentIds.forEach { aid ->
                        Surface(
                            onClick = {
                                selectedAgent = aid
                                scope.launch {
                                    loadingTranscript = true
                                    transcript = runCatching { app.conversations.claude.subagentMessages(key, aid) }.getOrDefault(emptyList())
                                    loadingTranscript = false
                                }
                            },
                            shape = D.shape(12.dp),
                            color = G.surface,
                            border = BorderStroke(1.dp, G.border),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(aid, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, modifier = Modifier.weight(1f))
                                TagPill("查看详情", fg = G.accent, bg = G.accentContainer)
                            }
                        }
                    }
                }
            } else if (loadingTranscript) {
                GaliaisProgressBar(progress = null)
            } else if (transcript.isEmpty()) {
                CrispCard { Text("未读取到该子代理的详细记录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
            } else {
                transcript.forEach { entry ->
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.labelMedium, color = G.accent)
                            Text(entry.body, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink)
                        }
                    }
                }
            }
        }
    }
}

/** Claude Code 会话内热重载、输出风格与 Ultracode 面板（`/reload`）。 */
@Composable
fun ClaudeSessionControlSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    currentStyle: String?,
    availableStyles: List<String>,
    ultracode: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var heldResult by remember { mutableStateOf<PluginReloadResult?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("会话内热重载与输出风格 (/reload)", style = MaterialTheme.typography.titleLarge, color = G.ink)

            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会话内热重载（不关闭当前会话）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrimaryPillButton("热重载插件", enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val res = app.conversations.claude.reloadPlugins(key, holdOnCacheImpact = true)
                                    if (res.held) heldResult = res
                                    else app.conversations.notify("已热重载插件（${res.pluginCount} 插件 · ${res.commandCount} 命令）")
                                } catch (e: Exception) {
                                    app.conversations.notify("热重载插件失败：${e.message}")
                                } finally { busy = false }
                            }
                        })
                        SecondaryPillButton("热重载技能", enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                runCatching { app.conversations.claude.reloadSkills(key) }
                                    .onSuccess { app.conversations.notify("已热重载 Skills") }
                                    .onFailure { app.conversations.notify("热重载技能失败：${it.message}") }
                                busy = false
                            }
                        })
                        SecondaryPillButton("刷新输出风格", enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                runCatching { app.conversations.claude.reloadOutputStyles(key) }
                                    .onSuccess { app.conversations.notify("已刷新输出风格（${it.size} 项）") }
                                    .onFailure { app.conversations.notify("刷新失败：${it.message}") }
                                busy = false
                            }
                        })
                    }
                    heldResult?.let { h ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(D.shape(10.dp))
                                .background(G.warningTint)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("检测到工具列表变更将导致当前会话 Prompt Cache 失效", style = MaterialTheme.typography.labelMedium, color = G.warning)
                            if (h.addedMcps.isNotEmpty()) Text("新增 MCP：${h.addedMcps.joinToString()}", fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                            if (h.removedMcps.isNotEmpty()) Text("移除 MCP：${h.removedMcps.joinToString()}", fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                            h.lspChange?.let { Text("LSP 工具变更：$it", fontFamily = Mono, fontSize = 11.sp, color = G.ink) }
                            PrimaryPillButton("确认仍要应用热重载", onClick = {
                                scope.launch {
                                    busy = true
                                    heldResult = null
                                    runCatching { app.conversations.claude.reloadPlugins(key, holdOnCacheImpact = false) }
                                        .onSuccess { app.conversations.notify("已强制应用插件热重载（${it.pluginCount} 插件）") }
                                        .onFailure { app.conversations.notify("应用失败：${it.message}") }
                                    busy = false
                                }
                            })
                        }
                    }
                }
            }

            if (availableStyles.isNotEmpty()) {
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("官方输出风格 (Output Style)", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            availableStyles.forEach { st ->
                                val selected = st == currentStyle
                                TagPill(
                                    text = st,
                                    fg = if (selected) G.onAccent else G.ink,
                                    bg = if (selected) G.accent else G.surfaceSubtle,
                                    onClick = {
                                        scope.launch {
                                            runCatching { app.conversations.claude.setOutputStyle(key, st) }
                                                .onSuccess { app.conversations.notify("输出风格已切换为 $st") }
                                                .onFailure { app.conversations.notify("切换失败：${it.message}") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            CrispCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ultracode 深度编码模式", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        Text("会话级启用 Claude Code Ultracode 标志设置。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    GaliaisSwitch(checked = ultracode, onCheckedChange = { enabled ->
                        scope.launch {
                            runCatching { app.conversations.claude.setUltracode(key, enabled) }
                                .onSuccess { app.conversations.notify(if (enabled) "已开启 Ultracode" else "已关闭 Ultracode") }
                                .onFailure { app.conversations.notify("设置失败：${it.message}") }
                        }
                    })
                }
            }
        }
    }
}
