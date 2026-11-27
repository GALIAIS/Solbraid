package com.galiais.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.PendingRequest
import com.galiais.agent.engine.RoleRouteConfig
import com.galiais.agent.engine.TicketStage
import com.galiais.agent.engine.WorkTicket
import com.galiais.agent.engine.roleDisplayName
import com.galiais.agent.engine.splitMultiFileDiff
import kotlinx.coroutines.launch

/**
 * 首页「需要你处理」注意力收件箱（遵循 dsh-superagent 000-philosophy.md）：
 * 无待办、无活跃工单时占 0 像素；有待审批 / 待验收 / 阻塞项时按优先级置顶展示。
 */
@Composable
fun AttentionInboxStrip(
    app: GaliaisApplication,
    requests: List<PendingRequest>,
    tickets: List<WorkTicket>,
    onOpenConversation: (ConversationKey) -> Unit,
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val busyTickets by app.superAgent.busyTickets.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val attentionTickets = remember(tickets) { tickets.filter { it.stage.needsAttention } }
    val workingTickets = remember(tickets) { tickets.filter { it.stage == TicketStage.WORKING || it.stage == TicketStage.QUEUED } }
    val standaloneReqs = remember(requests, tickets) {
        val ticketConvKeys = tickets.mapNotNull { it.childConversation }.toSet()
        requests.filter { it.conversation != null && it.conversation !in ticketConvKeys }
    }
    if (standaloneReqs.isEmpty() && attentionTickets.isEmpty() && workingTickets.isEmpty()) return

    var diffTicket by remember { mutableStateOf<WorkTicket?>(null) }

    CrispCard(
        modifier = modifier,
        padding = PaddingValues(0.dp),
        borderColor = if (standaloneReqs.isNotEmpty() || attentionTickets.isNotEmpty()) G.warning.copy(alpha = 0.42f) else G.border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBoard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                GaliaisIcons.ShieldCheck,
                contentDescription = null,
                tint = if (standaloneReqs.isNotEmpty() || attentionTickets.isNotEmpty()) G.warning else G.accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (standaloneReqs.isNotEmpty() || attentionTickets.isNotEmpty())
                    "需要你处理 (${standaloneReqs.size + attentionTickets.size})"
                else "后台工单运行中 (${workingTickets.size})",
                style = MaterialTheme.typography.titleSmall,
                color = G.ink,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("工单看板 →", style = MaterialTheme.typography.labelSmall, color = G.accent, fontWeight = FontWeight.Medium)
        }

        if (standaloneReqs.isNotEmpty() || attentionTickets.isNotEmpty()) {
            Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                standaloneReqs.take(2).forEach { req ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { req.conversation?.let(onOpenConversation) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TagPill("待审批", fg = G.warning, bg = G.warningTint)
                        val desc = when (req) {
                            is PendingRequest.CommandApproval -> req.command
                            is PendingRequest.FileApproval -> "文件修改 (${req.changes.size})"
                            is PendingRequest.ToolApproval -> req.toolName
                            is PendingRequest.UserInput -> req.questions.firstOrNull()?.text ?: "需要回答问题"
                            is PendingRequest.Delegation -> "委派 → ${req.target.label}"
                            is PendingRequest.Elicitation -> req.message
                            is PendingRequest.PermissionGrant -> req.reason ?: "请求额外权限"
                        }
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(GaliaisIcons.ChevronRight, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
                    }
                }
                attentionTickets.take(3).forEach { ticket ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ticket.childConversation?.let(onOpenConversation) ?: onOpenBoard() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val (badgeFg, badgeBg) = ticketStageColors(ticket.stage)
                        TagPill(ticket.stage.label, fg = badgeFg, bg = badgeBg)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(ticket.title, style = MaterialTheme.typography.bodySmall, color = G.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ticket.stage == TicketStage.AWAITING_REVIEW && (ticket.addedLines > 0 || ticket.removedLines > 0)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${ticket.filesChanged.size} 文件", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                    Text("+${ticket.addedLines}", fontFamily = Mono, fontSize = 10.5.sp, color = G.success)
                                    Text("-${ticket.removedLines}", fontFamily = Mono, fontSize = 10.5.sp, color = G.danger)
                                }
                            }
                            if (!ticket.error.isNullOrBlank()) {
                                Text(ticket.error, style = MaterialTheme.typography.labelSmall, color = G.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ticket.diffWarning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = G.warning) }
                        }
                        if (ticket.stage == TicketStage.AWAITING_REVIEW) {
                            if (ticket.diffText.isNotBlank()) {
                                TagPill("Diff", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { diffTicket = ticket })
                            }
                            TagPill(
                                if (ticket.id in busyTickets) "处理中…" else "合并",
                                fg = G.success,
                                bg = G.successTint,
                                onClick = if (runtime.ready && ticket.id !in busyTickets) ({
                                    scope.launch { app.superAgent.mergeTicket(ticket.id) }
                                }) else null,
                            )
                        }
                    }
                }
            }
        }

        if (workingTickets.isNotEmpty() && (standaloneReqs.isNotEmpty() || attentionTickets.isNotEmpty())) {
            Hairline()
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBoard).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulsingDot(G.accent, size = 6.dp)
                Text(
                    "另有 ${workingTickets.size} 个工单在后台静默执行中",
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                )
            }
        }
    }

    diffTicket?.let { t ->
        val parsed = remember(t.diffText) { splitMultiFileDiff(t.diffText) }
        DiffOverviewSheet(
            turnDiff = if (parsed.isEmpty()) t.diffText else null,
            edits = parsed,
            cwd = t.worktreePath,
            onDismiss = { diffTicket = null },
        )
    }
}

@Composable
private fun ticketStageColors(stage: TicketStage) = when (stage) {
    TicketStage.AWAITING_ANSWER -> G.warning to G.warningTint
    TicketStage.AWAITING_REVIEW -> G.success to G.successTint
    TicketStage.BLOCKED_FAILED -> G.danger to G.dangerTint
    TicketStage.WORKING -> G.accent to G.accentContainer
    TicketStage.MERGED -> G.success to G.successTint
    else -> G.inkMuted to G.surfaceSubtle
}

private data class DraftTicketSpec(
    val title: String = "",
    val task: String = "",
    val role: String = RoleRouteConfig.ROLE_WORKER,
)

private data class ProjectTrustAction(val cwd: String, val execute: () -> Unit)

/** Mobile Super-Agent 工单看板与并行批量派发抽屉。 */
@Composable
fun SuperAgentBoardSheet(
    app: GaliaisApplication,
    defaultCwd: String,
    onOpenConversation: (ConversationKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by app.settings.state.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val tickets by app.superAgent.tickets.collectAsState()
    val busyTickets by app.superAgent.busyTickets.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(if (tickets.any { it.active }) "board" else "dispatch") }
    var targetCwd by remember { mutableStateOf(defaultCwd) }
    var isolateWorktree by remember { mutableStateOf(true) }
    val draftList = remember { mutableStateListOf(DraftTicketSpec()) }
    var diffTicket by remember { mutableStateOf<WorkTicket?>(null) }
    var trustAction by remember { mutableStateOf<ProjectTrustAction?>(null) }
    var confirmDiscardTicket by remember { mutableStateOf<WorkTicket?>(null) }
    var confirmMergeTicket by remember { mutableStateOf<WorkTicket?>(null) }

    fun executeWithTrust(cwd: String, usesClaude: Boolean, action: () -> Unit) {
        if (usesClaude && cwd !in app.settings.state.value.trustedProjects) {
            trustAction = ProjectTrustAction(cwd, action)
        } else {
            action()
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Super-Agent 工单编排", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    Text(
                        "独立 Git Worktree 并行隔离 · 最大并发 ${settings.maxParallelWorkers} 槽",
                        style = MaterialTheme.typography.labelSmall,
                        color = G.inkMuted,
                    )
                }
                SecondaryPillButton("关闭", onClick = onDismiss)
            }

            SegmentedPillBar(
                options = listOf("board" to "工单看板 (${tickets.size})", "dispatch" to "+ 批量派发"),
                selected = tab,
                onSelect = { tab = it },
            )

            if (tab == "dispatch") {
                if (settings.projects.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.projects.forEach { proj ->
                            SelectableChip(
                                text = projectDisplayName(proj),
                                selected = targetCwd == proj,
                                onClick = { targetCwd = proj },
                            )
                        }
                    }
                }
                SettingsToggleRow(
                    title = "独立 Git Worktree 隔离",
                    subtitle = "每个工单独占分支与工作目录，并行修改互不冲突，验收后合并回主分支",
                    checked = isolateWorktree,
                    onCheckedChange = { isolateWorktree = it },
                )

                draftList.forEachIndexed { idx, spec ->
                    CrispCard(padding = PaddingValues(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("工单 #${idx + 1}", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (draftList.size > 1) {
                                    TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = { draftList.removeAt(idx) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    RoleRouteConfig.ROLE_WORKER to "实现工匠",
                                    RoleRouteConfig.ROLE_REVIEWER to "代码审查",
                                    RoleRouteConfig.ROLE_ORCHESTRATOR to "总控规划",
                                ).forEach { (roleId, label) ->
                                    val route = settings.roleRoutes[roleId]
                                    SelectableChip(
                                        text = "$label · ${EngineKind.of(route?.engineId ?: "codex").label}",
                                        selected = spec.role == roleId,
                                        onClick = { draftList[idx] = spec.copy(role = roleId) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = spec.title,
                                onValueChange = { draftList[idx] = spec.copy(title = it) },
                                label = { Text("工单标题（可选）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = spec.task,
                                onValueChange = { draftList[idx] = spec.copy(task = it) },
                                label = { Text("任务目标、修改范围与验收要求") },
                                minLines = 2,
                                maxLines = 5,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (draftList.size < 4) {
                    SecondaryPillButton(
                        text = "+ 再加一个并行工单",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { draftList.add(DraftTicketSpec()) },
                    )
                }
                PrimaryPillButton(
                    text = "派发执行 (${draftList.count { it.task.isNotBlank() }})",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = runtime.ready && targetCwd.isNotBlank() && draftList.any { it.task.isNotBlank() },
                    onClick = {
                        val cwd = targetCwd.trim()
                        val specs = draftList.filter { it.task.isNotBlank() }.map { Triple(it.title, it.task, it.role) }
                        val isolated = isolateWorktree
                        val usesClaude = specs.any { settings.roleRoutes[it.third]?.engineId == "claude" }
                        executeWithTrust(cwd, usesClaude) {
                            app.superAgent.dispatchBatch(repoCwd = cwd, specs = specs, isolatedWorktree = isolated)
                            draftList.clear()
                            draftList.add(DraftTicketSpec())
                            tab = "board"
                        }
                    },
                )
            } else {
                if (tickets.isEmpty()) {
                    CrispCard {
                        Text("暂无工单。切换到「+ 批量派发」可一次让多个 Agent 在隔离的 Git Worktree 中并行开发或交叉审查。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                } else {
                    tickets.forEach { t ->
                        val (fg, bg) = ticketStageColors(t.stage)
                        val busy = t.id in busyTickets
                        CrispCard(padding = PaddingValues(13.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TagPill(t.stage.label, fg = fg, bg = bg)
                                    TagPill("${roleDisplayName(t.role)} · ${EngineKind.of(t.engineId).label}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                    Spacer(Modifier.weight(1f))
                                    if (t.isolated && t.branch != null) {
                                        Text(
                                            t.branch.removePrefix("solbraid/"),
                                            fontFamily = Mono,
                                            fontSize = 10.sp,
                                            color = G.inkSubtle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                    }
                                }
                                Text(t.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (t.summary.isNotBlank()) {
                                    Text(t.summary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (!t.error.isNullOrBlank()) {
                                    Text(t.error, style = MaterialTheme.typography.bodySmall, color = G.danger)
                                }
                                t.diffWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.warning) }
                                if (t.filesChanged.isNotEmpty() || t.addedLines > 0 || t.removedLines > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("已改 ${t.filesChanged.size} 文件", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                        Text("+${t.addedLines}", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.success)
                                        Text("-${t.removedLines}", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.danger)
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    t.childConversation?.let { child ->
                                        TagPill("打开会话", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                                            onDismiss()
                                            onOpenConversation(child)
                                        })
                                    }
                                    if (t.stage == TicketStage.AWAITING_REVIEW || t.diffText.isNotBlank()) {
                                        TagPill("查看变更", fg = G.accent, bg = G.accentContainer, onClick = {
                                            scope.launch {
                                                val refreshed = app.superAgent.refreshTicketDiff(t.id) ?: t
                                                diffTicket = refreshed
                                            }
                                        })
                                    }
                                    if (t.stage == TicketStage.AWAITING_REVIEW) {
                                        TagPill(
                                            text = if (busy) "处理中…" else "合并分支",
                                            fg = G.success,
                                            bg = G.successTint,
                                            onClick = if (!busy && runtime.ready) ({ confirmMergeTicket = t }) else null,
                                        )
                                    }
                                    if (t.stage in setOf(TicketStage.WORKING, TicketStage.QUEUED, TicketStage.AWAITING_ANSWER)) {
                                        TagPill(if (busy) "处理中…" else "中断", fg = G.danger, bg = G.dangerTint, onClick = if (!busy) ({
                                            scope.launch { app.superAgent.cancelTicket(t.id) }
                                        }) else null)
                                    }
                                    if (t.stage == TicketStage.BLOCKED_FAILED) {
                                        TagPill("重试", fg = G.accent, bg = G.accentContainer, onClick = if (!busy && runtime.ready) ({
                                            executeWithTrust(t.repoCwd, t.engineId == "claude") { app.superAgent.retryTicket(t.id) }
                                        }) else null)
                                    }
                                    if (t.active) {
                                        TagPill("丢弃", fg = G.danger, bg = G.dangerTint, onClick = if (!busy && runtime.ready) ({
                                            confirmDiscardTicket = t
                                        }) else null)
                                    } else {
                                        TagPill(if (t.cleanupPending) "清理并移除记录" else "清除记录", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = if (!busy && runtime.ready) ({
                                            scope.launch { app.superAgent.removeTicket(t.id) }
                                        }) else null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    diffTicket?.let { t ->
        val parsed = remember(t.diffText) { splitMultiFileDiff(t.diffText) }
        DiffOverviewSheet(
            turnDiff = if (parsed.isEmpty()) t.diffText else null,
            edits = parsed,
            cwd = t.worktreePath,
            onDismiss = { diffTicket = null },
        )
    }
    confirmMergeTicket?.let { t ->
        GaliaisConfirmDialog(
            title = "合并工单改动？",
            message = "将把工单「${t.title}」的改动合并回主工作区（${t.filesChanged.size} 文件，+${t.addedLines}/-${t.removedLines}）。",
            confirmText = "确认合并",
            onConfirm = {
                confirmMergeTicket = null
                scope.launch { app.superAgent.mergeTicket(t.id) }
            },
            onDismiss = { confirmMergeTicket = null },
        )
    }
    confirmDiscardTicket?.let { t ->
        GaliaisConfirmDialog(
            title = "丢弃工单与隔离改动？",
            message = "将永久删除工单「${t.title}」的隔离分支及所有未合并代码改动，无法撤销。",
            confirmText = "确认丢弃",
            danger = true,
            onConfirm = {
                confirmDiscardTicket = null
                scope.launch { app.superAgent.discardTicket(t.id) }
            },
            onDismiss = { confirmDiscardTicket = null },
        )
    }
    trustAction?.let { pending ->
        GaliaisConfirmDialog(
            title = "信任此项目？",
            message = "Claude 将加载此项目及其工单隔离目录中的配置和钩子。请确认项目来源可信。\n\n${pending.cwd}",
            confirmText = "信任并执行",
            confirmEnabled = runtime.ready,
            onConfirm = {
                app.settings.trust(pending.cwd)
                trustAction = null
                pending.execute()
            },
            onDismiss = { trustAction = null },
        )
    }
}

/** Super-Agent 编排设置子页面：配置并发 Worker 槽位（1..3）及按角色路由的引擎/服务商/模型。 */
@Composable
fun SuperAgentSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    val hubStatuses by app.conversations.hub.statuses.collectAsState()
    val installedAgents = remember(hubStatuses, settings.customAgents) {
        AgentCatalog.all().filter { it.preinstalled || hubStatuses[it.id]?.installed == true }
    }

    SubPageScaffold(title = "Super-Agent 编排", onBack = onBack, snackbar = snackbar) {
        CrispCard(padding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("并行 Worker 槽位上限", style = MaterialTheme.typography.titleSmall, color = G.ink)
                Text(
                    "控制同时在后台独立 Git Worktree 中运行的工单数量。默认 2 槽兼顾速度与手机内存发热，超出槽位的工单会自动排队。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                )
                SegmentedPillBar(
                    options = listOf(1 to "1 槽 · 省电", 2 to "2 槽 · 推荐", 3 to "3 槽 · 高并发"),
                    selected = settings.maxParallelWorkers,
                    onSelect = { app.settings.setMaxParallelWorkers(it) },
                )
            }
        }

        SectionLabel("按角色路由引擎与模型（不影响单聊默认配置）")
        listOf(
            Triple(RoleRouteConfig.ROLE_ORCHESTRATOR, "总控规划（Orchestrator）", "负责任务拆解、风险评估与全局验收，建议强推理模型"),
            Triple(RoleRouteConfig.ROLE_WORKER, "实现工匠（Worker）", "在独立 Git Worktree 中写代码、跑命令与自测，建议 Codex / Sonnet"),
            Triple(RoleRouteConfig.ROLE_REVIEWER, "代码审查（Reviewer）", "交叉审计改动与边界缺陷，建议与实现工匠采用异构引擎"),
        ).forEach { (roleId, title, desc) ->
            val route = settings.roleRoutes[roleId] ?: RoleRouteConfig(engineId = "codex")
            var modelDraft by remember(roleId, route.model) { mutableStateOf(route.model ?: "") }
            CrispCard(padding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        installedAgents.forEach { agent ->
                            SelectableChip(
                                text = agent.name,
                                selected = route.engineId == agent.id,
                                onClick = {
                                    app.settings.setRoleRoute(roleId, route.copy(engineId = agent.id, model = null, effort = null))
                                },
                            )
                        }
                    }
                    if (settings.providers.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectableChip(
                                text = "引擎官方默认",
                                selected = route.providerId == null,
                                onClick = { app.settings.setRoleRoute(roleId, route.copy(providerId = null)) },
                            )
                            settings.providers.forEach { p ->
                                SelectableChip(
                                    text = p.name,
                                    selected = route.providerId == p.id,
                                    onClick = { app.settings.setRoleRoute(roleId, route.copy(providerId = p.id)) },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = modelDraft,
                            onValueChange = { modelDraft = it.trim() },
                            label = { Text("指定模型 ID（留空遵循该引擎默认）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryPillButton("应用", onClick = {
                            app.settings.setRoleRoute(roleId, route.copy(model = modelDraft.ifBlank { null }))
                            app.conversations.notify("已更新「${roleDisplayName(roleId)}」模型路由")
                        })
                    }
                }
            }
        }
    }
}
