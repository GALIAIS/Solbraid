package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.Decision
import com.galiais.agent.engine.ElicitationField
import com.galiais.agent.engine.PendingRequest
import com.galiais.agent.engine.extractPlanTitle
import com.galiais.agent.engine.parseRecommendedLabel
import com.galiais.agent.engine.pretty
import org.json.JSONArray
import org.json.JSONObject

/**
 * 审批与高风险操作卡片容器：完整响应 6 种 `D.approvalStyle`，无外框香蕉变形。
 */
@Composable
fun ApprovalCardContainer(
    modifier: Modifier = Modifier,
    badgeText: String = "需要确认",
    icon: ImageVector = GaliaisIcons.ShieldCheck,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = G
    val d = D
    val style = d.approvalStyle
    val shape = d.cardShape
    val pad = d.space(14.dp)

    val bg = if (style == "tonal_banner") g.warningTint else g.surface
    val borderColor = when (style) {
        "soft_halo" -> g.warning.copy(alpha = 0.55f)
        "badge_ring" -> g.warning.copy(alpha = 0.42f)
        "tonal_banner" -> g.warning.copy(alpha = 0.34f)
        "top_glow" -> g.warning.copy(alpha = 0.32f)
        "hazard_header" -> g.borderStrong
        else -> g.border
    }
    val shadow = when (style) {
        "soft_halo" -> if (g.dark) 3.dp else 6.dp
        else -> if (d.cardStyle == "elevated" && !g.dark) 3.dp else 0.dp
    }

    val haloMod = if (style == "soft_halo") {
        modifier
            .fillMaxWidth()
            .border(3.dp, g.warning.copy(alpha = 0.14f), d.shape(d.cardRadius + 2.dp))
    } else modifier.fillMaxWidth()

    Surface(
        modifier = haloMod,
        shape = shape,
        color = bg,
        border = BorderStroke(if (style == "hazard_header") 1.25.dp else 1.dp, borderColor),
        shadowElevation = shadow,
    ) {
        val innerDrawMod = when (style) {
            "top_glow" -> Modifier.drawBehind {
                val glowRadius = size.width.coerceAtLeast(1f) * 0.72f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(g.warning.copy(alpha = if (g.dark) 0.15f else 0.11f), Color.Transparent),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = glowRadius,
                    ),
                )
                val inset = size.width * 0.14f
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, g.warning, Color.Transparent),
                        startX = inset,
                        endX = size.width - inset,
                    ),
                    start = Offset(inset, 1.dp.toPx()),
                    end = Offset(size.width - inset, 1.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            "accent_rail" -> Modifier.drawBehind {
                val railW = 3.2.dp.toPx()
                val insetX = 9.dp.toPx()
                val insetY = 12.dp.toPx()
                val railH = (size.height - insetY * 2f).coerceAtLeast(12.dp.toPx())
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(g.warning, g.warning.copy(alpha = 0.45f)),
                        startY = insetY,
                        endY = insetY + railH,
                    ),
                    topLeft = Offset(insetX, insetY),
                    size = Size(railW, railH),
                    cornerRadius = CornerRadius(railW, railW),
                )
            }
            else -> Modifier
        }

        Column(Modifier.fillMaxWidth().then(innerDrawMod)) {
            if (style == "hazard_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(g.warning)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "▲ APPROVAL REQUIRED",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF141210),
                        letterSpacing = 0.6.sp,
                    )
                    Text(
                        badgeText,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF141210),
                    )
                }
            }
            val startPad = if (style == "accent_rail") pad + 9.dp else pad
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = startPad, end = pad, top = pad, bottom = pad),
                verticalArrangement = Arrangement.spacedBy(d.space(9.dp)),
            ) {
                if (style != "hazard_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(g.warningTint)
                                .border(1.dp, g.warning.copy(alpha = 0.30f), RoundedCornerShape(7.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = g.warning,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            badgeText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = g.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        headerTrailing?.invoke()
                    }
                } else if (headerTrailing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        headerTrailing()
                    }
                }
                content()
            }
        }
    }
}

/**
 * 底部停靠式 Codex 审批面板：固定停靠在输入框正上方，不随消息流滚走；
 * 多条审批并发时提供 `1 / N` 队列切换，确保任何时刻触手可及。
 */
@Composable
fun DockedApprovalPanel(
    requests: List<PendingRequest>,
    onDecision: (PendingRequest, Decision) -> Unit,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (requests.isEmpty()) return
    var activeRequestId by remember { mutableStateOf<Any?>(null) }
    val safeIndex = requests.indexOfFirst { it.requestId == activeRequestId }.takeIf { it >= 0 } ?: 0
    val active = requests[safeIndex]
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        RequestCard(
            request = active,
            queuePosition = if (requests.size > 1) "${safeIndex + 1}/${requests.size}" else null,
            onPrevQueue = if (requests.size > 1) ({
                val prev = (safeIndex - 1 + requests.size) % requests.size
                activeRequestId = requests[prev].requestId
            }) else null,
            onNextQueue = if (requests.size > 1) ({
                val next = (safeIndex + 1) % requests.size
                activeRequestId = requests[next].requestId
            }) else null,
            onOpenArtifact = onOpenArtifact,
            onDecision = { onDecision(active, it) },
        )
    }
}

/** 审批与提问卡片：完整适配 Codex 6 级决策、Claude Code 建议规则/计划退出/多选提问、Elicitation Schema 表单与 ACP 外部 Agent。 */
@Composable
fun RequestCard(
    request: PendingRequest,
    queuePosition: String? = null,
    onPrevQueue: (() -> Unit)? = null,
    onNextQueue: (() -> Unit)? = null,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onDecision: (Decision) -> Unit,
) {
    var denying by remember(request.requestId) { mutableStateOf(false) }
    var reason by remember(request.requestId) { mutableStateOf("") }

    val badge = when (request) {
        is PendingRequest.UserInput -> if (request.isClaudeAskUserQuestion) "需要你选择" else "需要回答"
        is PendingRequest.ToolApproval -> if (request.isExitPlanMode) "方案确认" else "需要确认"
        is PendingRequest.PermissionGrant -> "权限申请"
        else -> "需要确认"
    }
    val headerIcon = when (request) {
        is PendingRequest.CommandApproval -> GaliaisIcons.Terminal
        is PendingRequest.FileApproval -> GaliaisIcons.FilePen
        is PendingRequest.ToolApproval -> when {
            request.isExitPlanMode -> GaliaisIcons.ListCheck
            request.changes.isNotEmpty() -> GaliaisIcons.FilePen
            else -> describeToolCall(request.toolName, request.input.toString()).icon()
        }
        is PendingRequest.PermissionGrant -> GaliaisIcons.ShieldCheck
        is PendingRequest.UserInput -> GaliaisIcons.Comments
        is PendingRequest.Delegation -> GaliaisIcons.Bot
        is PendingRequest.Elicitation -> GaliaisIcons.Plug
    }

    ApprovalCardContainer(
        badgeText = badge,
        icon = headerIcon,
        headerTrailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (queuePosition != null && onPrevQueue != null && onNextQueue != null) {
                    TagPill("‹", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = onPrevQueue)
                    TagPill(queuePosition, fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border)
                    TagPill("›", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = onNextQueue)
                }
                EngineLabel(request.engine)
            }
        },
    ) {
        Text(request.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
        val hasSchemaForm = request is PendingRequest.Elicitation && request.schemaFields.isNotEmpty()
        val bodyMaxHeight = if (request is PendingRequest.UserInput || hasSchemaForm || (request is PendingRequest.ToolApproval && request.isExitPlanMode)) 360.dp else 260.dp
        Column(Modifier.heightIn(max = bodyMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (request) {
                is PendingRequest.CommandApproval -> {
                    CodeBlock(request.command, request.cwd ?: "sh")
                    if (!request.networkHost.isNullOrBlank()) {
                        val proto = request.networkProtocol?.uppercase()?.let { "$it · " } ?: ""
                        TagPill("网络访问：$proto${request.networkHost}", fg = G.accent, bg = G.accentContainer)
                    }
                    request.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
                }
                is PendingRequest.FileApproval -> {
                    request.grantRoot?.takeIf { it.isNotBlank() }?.let {
                        Text("目录授权：$it", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                    }
                    request.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
                    if (request.changes.isEmpty()) Text("引擎未提供文件改动预览", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    request.changes.forEach { DiffView(it) }
                }
                is PendingRequest.ToolApproval -> {
                    request.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.ink) }
                    request.blockedPath?.takeIf { it.isNotBlank() }?.let {
                        Text("涉及路径：$it", fontFamily = Mono, fontSize = 11.5.sp, color = G.warning)
                    }
                    request.reason?.takeIf { it.isNotBlank() && it != request.description }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    if (request.isExitPlanMode) {
                        request.planFilePath?.takeIf { it.isNotBlank() }?.let { path ->
                            Text("方案文件：${path.replaceFirst("/home/agent", "~")}", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle)
                        }
                        if (!request.planMarkdown.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = D.shape(10.dp),
                                color = G.surfaceSubtle,
                                border = BorderStroke(0.75.dp, G.border),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                        Markdown(request.planMarkdown, compact = true, onOpenArtifact = onOpenArtifact)
                                    }
                                    if (onOpenArtifact != null) {
                                        val planArt = remember(request.requestId, request.planMarkdown, request.planFilePath) {
                                            ChatArtifact(
                                                id = "exit-plan-${request.requestId}",
                                                identifier = request.planFilePath?.substringAfterLast('/')?.let { "plan-$it" } ?: "plan-${request.requestId}",
                                                title = extractPlanTitle(request.planMarkdown, request.planFilePath),
                                                kind = ArtifactKind.MARKDOWN,
                                                language = "markdown",
                                                content = request.planMarkdown,
                                            )
                                        }
                                        TagPill("工作台全屏查看方案", fg = G.accent, bg = G.accentContainer, onClick = { onOpenArtifact(planArt) })
                                    }
                                }
                            }
                        }
                        if (request.allowedPrompts.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                request.allowedPrompts.take(6).forEach { prompt ->
                                    TagPill("预授权：$prompt", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border)
                                }
                            }
                        }
                    } else {
                        if (request.changes.isNotEmpty()) request.changes.forEach { DiffView(it) }
                        else if (request.toolName == "Bash") CodeBlock(request.input.optString("command"), "bash")
                        else if (request.input.length() > 0) CodeBlock(request.input.pretty(), "json")
                    }
                }
                is PendingRequest.PermissionGrant -> {
                    request.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
                    CodeBlock(request.permissions.pretty(), "json")
                }
                is PendingRequest.Elicitation -> {
                    if (request.schemaFields.isNotEmpty()) {
                        ElicitationSchemaForm(request, onDecision)
                        return@ApprovalCardContainer
                    }
                    val uriHandler = LocalUriHandler.current
                    Text(request.message.ifBlank { request.raw.pretty(1000) }, style = MaterialTheme.typography.bodyMedium, color = G.ink)
                    request.url?.takeIf { it.isNotBlank() }?.let { u ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(D.shape(10.dp))
                                .clickable { runCatching { uriHandler.openUri(u) } },
                            shape = D.shape(10.dp),
                            color = G.accentContainer.copy(alpha = if (G.dark) 0.28f else 0.55f),
                            border = BorderStroke(0.75.dp, G.accent.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(GaliaisIcons.Globe, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("点击在浏览器中打开外部链接", style = MaterialTheme.typography.labelMedium, color = G.accent, fontWeight = FontWeight.SemiBold)
                                    Text(u, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TagPill("打开", fg = G.onAccent, bg = G.accent)
                            }
                        }
                    }
                }
                is PendingRequest.Delegation -> {
                    Text("工作目录：${request.cwd ?: "默认工作区"}", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                    Markdown(request.task)
                    Text(
                        "由 ${request.target.label} 创建子会话执行任务，结果返回 ${request.engine.label}。" +
                            if (request.target.label == "Claude Code") "允许即信任该工作目录，项目配置和钩子可能执行命令。" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }
                is PendingRequest.UserInput -> { QuestionForm(request, onDecision); return@ApprovalCardContainer }
            }
        }
        if (request is PendingRequest.UserInput || hasSchemaForm) return@ApprovalCardContainer

        if (denying) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(500) },
                label = { Text("告诉 Agent 原因或改用其他做法（选填）") },
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryPillButton("拒绝并让其继续", onClick = { onDecision(Decision.Deny(reason.ifBlank { "用户拒绝" }, interrupt = false)) })
                SecondaryPillButton("拒绝并中断本轮", onClick = { onDecision(Decision.Deny(reason.ifBlank { "用户中断" }, interrupt = true)) }, danger = true)
                SecondaryPillButton("返回", onClick = { denying = false })
            }
        } else {
            DecisionButtonsRow(request = request, onDecision = onDecision, onOpenDeny = { denying = true })
        }
    }
}

@Composable
private fun DecisionButtonsRow(
    request: PendingRequest,
    onDecision: (Decision) -> Unit,
    onOpenDeny: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when (request) {
            is PendingRequest.CommandApproval -> {
                PrimaryPillButton("允许本次", onClick = { onDecision(Decision.Allow) })
                if (request.proposedExecPolicy.isNotEmpty()) {
                    val ruleHint = request.proposedExecPolicy.joinToString(" ").take(20)
                    SecondaryPillButton("记住规则：$ruleHint", onClick = {
                        onDecision(Decision.AllowWithRule(execPolicy = request.proposedExecPolicy))
                    })
                } else if (request.allowNetworkAmendment && !request.networkHost.isNullOrBlank()) {
                    SecondaryPillButton("始终允许 ${request.networkHost.take(22)}", onClick = {
                        onDecision(Decision.AllowWithRule(networkHost = request.networkHost, networkAction = "allow"))
                    })
                }
                SecondaryPillButton("本会话允许", onClick = { onDecision(Decision.AllowForSession) })
                SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
            }
            is PendingRequest.FileApproval -> {
                PrimaryPillButton("允许本次", onClick = { onDecision(Decision.Allow) })
                SecondaryPillButton("本会话允许", onClick = { onDecision(Decision.AllowForSession) })
                SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
            }
            is PendingRequest.PermissionGrant -> {
                PrimaryPillButton("仅本轮允许", onClick = { onDecision(Decision.Allow) })
                SecondaryPillButton("本会话允许", onClick = { onDecision(Decision.AllowForSession) })
                SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
            }
            is PendingRequest.ToolApproval -> {
                if (request.engine.id !in setOf("codex", "claude") && request.permissionOptions.isNotEmpty()) {
                    request.permissionOptions.filter { it.kind in setOf("allow_once", "allow_always") }.forEachIndexed { idx, option ->
                        val label = if (option.kind == "allow_always") "始终允许：${option.name}" else option.name
                        if (idx == 0) PrimaryPillButton(label, onClick = { onDecision(Decision.Answers(mapOf("optionId" to listOf(option.id)))) })
                        else SecondaryPillButton(label, onClick = { onDecision(Decision.Answers(mapOf("optionId" to listOf(option.id)))) })
                    }
                    SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
                } else {
                    val allowLabel = if (request.isExitPlanMode) "批准方案" else "允许本次"
                    if (request.defaultToNo) {
                        SecondaryPillButton(allowLabel, onClick = { onDecision(Decision.Allow) })
                    } else {
                        PrimaryPillButton(allowLabel, onClick = { onDecision(Decision.Allow) })
                    }
                    if (!request.suppressAlwaysAllowRule && !request.claudeSuggestionsJson.isNullOrBlank()) {
                        val ruleLabel = if (request.isExitPlanMode) "批准并自动编辑" else "始终允许"
                        SecondaryPillButton(ruleLabel, onClick = {
                            onDecision(Decision.AllowWithRule(claudePermissionsJson = request.claudeSuggestionsJson))
                        })
                    }
                    SecondaryPillButton(if (request.isExitPlanMode) "继续修改" else "拒绝", onClick = onOpenDeny, danger = true)
                }
            }
            is PendingRequest.Elicitation -> {
                val uriHandler = LocalUriHandler.current
                val extUrl = request.url?.takeIf { it.isNotBlank() }
                if (extUrl != null) {
                    PrimaryPillButton("打开链接", onClick = { runCatching { uriHandler.openUri(extUrl) } })
                    SecondaryPillButton("我已完成", onClick = { onDecision(Decision.Allow) })
                } else {
                    PrimaryPillButton("接受", onClick = { onDecision(Decision.Allow) })
                }
                SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
            }
            is PendingRequest.Delegation -> {
                PrimaryPillButton("允许委派", onClick = { onDecision(Decision.Allow) })
                SecondaryPillButton("拒绝", onClick = onOpenDeny, danger = true)
            }
            is PendingRequest.UserInput -> Unit
        }
    }
}

@Composable
private fun QuestionForm(request: PendingRequest.UserInput, onDecision: (Decision) -> Unit) {
    val selectedMulti = remember(request.requestId) { mutableStateMapOf<String, List<String>>() }
    val customText = remember(request.requestId) { mutableStateMapOf<String, String>() }
    var activeStep by remember(request.requestId) { mutableIntStateOf(0) }
    val multiStep = request.questions.size > 1
    val safeStep = activeStep.coerceIn(0, (request.questions.size - 1).coerceAtLeast(0))

    fun resolvedAnswers(): Map<String, List<String>> = buildMap {
        request.questions.forEach { q ->
            val vals = buildList {
                addAll(selectedMulti[q.id].orEmpty())
                customText[q.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }.distinct()
            if (vals.isNotEmpty()) put(q.id, vals)
        }
    }

    val current = resolvedAnswers()

    if (multiStep) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            request.questions.forEachIndexed { idx, q ->
                val answered = !current[q.id].isNullOrEmpty()
                val active = idx == safeStep
                val title = q.header.takeIf { it.isNotBlank() }?.take(12) ?: "问题 ${idx + 1}"
                TagPill(
                    text = if (answered) "✓ $title" else "${idx + 1}. $title",
                    fg = when {
                        active -> G.onAccent
                        answered -> G.success
                        else -> G.inkMuted
                    },
                    bg = when {
                        active -> G.accent
                        answered -> G.successTint
                        else -> G.surfaceSubtle
                    },
                    border = if (active) null else G.border,
                    onClick = { activeStep = idx },
                )
            }
        }
    }

    val displayed = if (multiStep) listOf(request.questions[safeStep]) else request.questions
    displayed.forEach { question ->
        val picked = selectedMulti[question.id].orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (question.header.isNotBlank()) {
                    TagPill(question.header, fg = G.accent, bg = G.accentContainer)
                }
                if (question.multiSelect) {
                    val countLabel = if (picked.isNotEmpty()) "可多选 · 已选 ${picked.size} 项" else "可多选"
                    TagPill(
                        countLabel,
                        fg = if (picked.isNotEmpty()) G.accent else G.inkMuted,
                        bg = if (picked.isNotEmpty()) G.accentContainer else G.surfaceSubtle,
                        border = if (picked.isNotEmpty()) null else G.border,
                    )
                }
            }
            Text(question.text, style = MaterialTheme.typography.bodyMedium, color = G.ink, fontWeight = FontWeight.Medium)
            if (question.options.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    question.options.forEachIndexed { idx, option ->
                        val isPicked = option in picked
                        val desc = question.optionDescriptions.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        val preview = question.optionPreviews.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        val (displayLabel, isRecommended) = parseRecommendedLabel(option)
                        val rowShape = D.shape(10.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(if (isPicked) G.accentContainer.copy(alpha = 0.55f) else G.surfaceSubtle)
                                .border(1.dp, if (isPicked) G.accent else G.border, rowShape)
                                .clickable {
                                    selectedMulti[question.id] = if (question.multiSelect) {
                                        if (isPicked) picked - option else picked + option
                                    } else {
                                        listOf(option)
                                    }
                                    if (!question.multiSelect) customText[question.id] = ""
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.padding(top = 2.dp)) {
                                if (question.multiSelect) GaliaisCheckbox(isPicked) else GaliaisRadio(isPicked)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        displayLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isPicked) G.accent else G.ink,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isRecommended) {
                                        TagPill("推荐", fg = G.accent, bg = G.accentContainer)
                                    }
                                }
                                if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                if (preview != null) {
                                    Surface(
                                        shape = D.shape(7.dp),
                                        color = G.codeBg,
                                        border = BorderStroke(0.5.dp, G.codeBorder),
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    ) {
                                        Text(
                                            preview.take(400),
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = G.codeInk,
                                            modifier = Modifier.padding(7.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (question.options.isEmpty() || question.allowOther) {
                OutlinedTextField(
                    value = customText[question.id] ?: "",
                    onValueChange = { v ->
                        customText[question.id] = v
                        if (!question.multiSelect && v.isNotBlank()) selectedMulti[question.id] = emptyList()
                    },
                    label = { Text(if (question.options.isEmpty()) "输入回答" else "自定义输入（选填）") },
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                )
            }
        }
    }

    val allAnswered = request.questions.all { !current[it.id].isNullOrEmpty() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (multiStep && safeStep > 0) {
            SecondaryPillButton("上一题", onClick = { activeStep = safeStep - 1 })
        }
        if (multiStep && safeStep < request.questions.lastIndex) {
            val curAnswered = !current[request.questions[safeStep].id].isNullOrEmpty()
            if (allAnswered) {
                PrimaryPillButton("提交全部（${request.questions.size}）", onClick = { onDecision(Decision.Answers(current)) })
                SecondaryPillButton("下一题", onClick = { activeStep = safeStep + 1 })
            } else {
                PrimaryPillButton("下一题", enabled = curAnswered, onClick = { activeStep = safeStep + 1 })
            }
        } else {
            PrimaryPillButton(
                text = if (multiStep) "提交全部（${request.questions.size}）" else "提交",
                enabled = allAnswered,
                onClick = { onDecision(Decision.Answers(current)) },
            )
        }
        SecondaryPillButton("跳过", onClick = {
            if (request.isClaudeAskUserQuestion) onDecision(Decision.Deny("用户跳过选择", interrupt = false))
            else onDecision(Decision.Answers(emptyMap()))
        })
    }
}

/** 原生 MCP / Claude / Codex / ACP Elicitation JSON Schema 动态表单（复用通用 JsonSchemaForm）。 */
@Composable
private fun ElicitationSchemaForm(request: PendingRequest.Elicitation, onDecision: (Decision) -> Unit) {
    if (request.message.isNotBlank()) {
        Text(request.message, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
    }
    JsonSchemaForm(
        formKey = request.requestId,
        fields = request.schemaFields,
        initialValues = null,
        submitLabel = "提交",
        cancelLabel = "拒绝",
        cancelDanger = true,
        onSubmit = { obj -> onDecision(Decision.FormSubmit(obj)) },
        onCancel = { onDecision(Decision.Deny("用户拒绝提供输入", interrupt = false)) },
    )
}

/** 通用声明式 JSON Schema 原生表单引擎：同时驱动 Elicitation 交互提问与 Host 插件设置界面。 */
@Composable
fun JsonSchemaForm(
    formKey: Any,
    fields: List<ElicitationField>,
    initialValues: JSONObject? = null,
    submitLabel: String = "保存",
    cancelLabel: String = "取消",
    cancelDanger: Boolean = false,
    onSubmit: (JSONObject) -> Unit,
    onCancel: () -> Unit,
) {
    val textValues = remember(formKey) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { f ->
                if (f.type != "boolean" && f.type != "multi_enum") {
                    val init = initialValues?.opt(f.name)?.takeUnless { it == JSONObject.NULL }?.toString()
                        ?: f.defaultValue
                    if (!init.isNullOrBlank()) put(f.name, init)
                }
            }
        }
    }
    val boolValues = remember(formKey) {
        mutableStateMapOf<String, Boolean>().apply {
            fields.forEach { f ->
                if (f.type == "boolean") {
                    val hasInit = initialValues?.has(f.name) == true && !initialValues.isNull(f.name)
                    put(f.name, if (hasInit) initialValues.optBoolean(f.name) else f.defaultValue.equals("true", ignoreCase = true))
                }
            }
        }
    }
    val multiValues = remember(formKey) {
        mutableStateMapOf<String, List<String>>().apply {
            fields.forEach { f ->
                if (f.type == "multi_enum") {
                    val arr = initialValues?.optJSONArray(f.name)
                    val list = if (arr != null) {
                        buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }.filter { it.isNotEmpty() }
                    } else f.defaultMultiValues
                    if (list.isNotEmpty()) put(f.name, list)
                }
            }
        }
    }

    fields.forEach { field ->
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            when (field.type) {
                "boolean" -> {
                    val checked = boolValues[field.name] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(D.shape(10.dp))
                            .background(G.surfaceSubtle)
                            .border(0.75.dp, G.border, D.shape(10.dp))
                            .clickable { boolValues[field.name] = !checked }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(field.title, style = MaterialTheme.typography.labelLarge, color = G.ink)
                            if (field.description.isNotBlank()) {
                                Text(field.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                        }
                        Switch(checked = checked, onCheckedChange = { boolValues[field.name] = it })
                    }
                }
                "multi_enum" -> {
                    val pickedList = multiValues[field.name].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            field.title + if (field.required) " *" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = G.ink,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TagPill(
                            text = if (pickedList.isNotEmpty()) "可多选 · 已选 ${pickedList.size} 项" else "可多选",
                            fg = if (pickedList.isNotEmpty()) G.accent else G.inkMuted,
                            bg = if (pickedList.isNotEmpty()) G.accentContainer else G.surfaceSubtle,
                            border = if (pickedList.isNotEmpty()) null else G.border,
                        )
                    }
                    if (field.description.isNotBlank()) {
                        Text(field.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        field.enumValues.forEachIndexed { idx, value ->
                            val rawLabel = field.enumNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: value
                            val (displayLabel, isRecommended) = parseRecommendedLabel(rawLabel)
                            val optDesc = field.enumDescriptions.getOrNull(idx)?.takeIf { it.isNotBlank() }
                            val picked = value in pickedList
                            val rowShape = D.shape(10.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (picked) G.accentContainer.copy(alpha = 0.55f) else G.surfaceSubtle)
                                    .border(1.dp, if (picked) G.accent else G.border, rowShape)
                                    .clickable {
                                        multiValues[field.name] = if (picked) pickedList - value else pickedList + value
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.padding(top = 2.dp)) { GaliaisCheckbox(picked) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            displayLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (picked) G.accent else G.ink,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (isRecommended) {
                                            TagPill("推荐", fg = G.accent, bg = G.accentContainer)
                                        }
                                    }
                                    if (optDesc != null) {
                                        Text(optDesc, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                    }
                                }
                            }
                        }
                    }
                }
                "enum" -> {
                    Text(
                        field.title + if (field.required) " *" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = G.ink,
                        fontWeight = FontWeight.Medium,
                    )
                    if (field.description.isNotBlank()) {
                        Text(field.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    val selected = textValues[field.name]
                    val useRadioRows = field.enumDescriptions.any { it.isNotBlank() } ||
                        field.enumNames.any { parseRecommendedLabel(it).second || it.length > 14 } ||
                        field.enumValues.any { parseRecommendedLabel(it).second || it.length > 14 }
                    if (useRadioRows) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            field.enumValues.forEachIndexed { idx, value ->
                                val rawLabel = field.enumNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: value
                                val (displayLabel, isRecommended) = parseRecommendedLabel(rawLabel)
                                val optDesc = field.enumDescriptions.getOrNull(idx)?.takeIf { it.isNotBlank() }
                                val picked = selected == value
                                val rowShape = D.shape(10.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(rowShape)
                                        .background(if (picked) G.accentContainer.copy(alpha = 0.55f) else G.surfaceSubtle)
                                        .border(1.dp, if (picked) G.accent else G.border, rowShape)
                                        .clickable { textValues[field.name] = value }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(Modifier.padding(top = 2.dp)) { GaliaisRadio(picked) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                displayLabel,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (picked) G.accent else G.ink,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (isRecommended) {
                                                TagPill("推荐", fg = G.accent, bg = G.accentContainer)
                                            }
                                        }
                                        if (optDesc != null) {
                                            Text(optDesc, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            field.enumValues.forEachIndexed { idx, value ->
                                val rawLabel = field.enumNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: value
                                val (displayLabel, _) = parseRecommendedLabel(rawLabel)
                                val picked = selected == value
                                TagPill(
                                    text = displayLabel,
                                    fg = if (picked) G.onAccent else G.ink,
                                    bg = if (picked) G.accent else G.surfaceSubtle,
                                    border = if (picked) null else G.border,
                                    onClick = { textValues[field.name] = value },
                                )
                            }
                        }
                    }
                }
                "string_array" -> {
                    OutlinedTextField(
                        value = textValues[field.name] ?: field.defaultMultiValues.joinToString("\n"),
                        onValueChange = { textValues[field.name] = it },
                        label = { Text(field.title + if (field.required) " *（每行或逗号分隔）" else "（每行或逗号分隔）") },
                        supportingText = if (field.description.isNotBlank()) ({ Text(field.description) }) else null,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    val isNumeric = field.type == "number" || field.type == "integer"
                    val isSecret = field.format.equals("password", ignoreCase = true) ||
                        field.name.contains("secret", ignoreCase = true) ||
                        field.name.contains("token", ignoreCase = true) ||
                        field.name.contains("password", ignoreCase = true)
                    OutlinedTextField(
                        value = textValues[field.name] ?: "",
                        onValueChange = { textValues[field.name] = it },
                        label = { Text(field.title + if (field.required) " *" else "") },
                        supportingText = if (field.description.isNotBlank()) ({ Text(field.description) }) else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                field.type == "integer" -> KeyboardType.Number
                                field.type == "number" -> KeyboardType.Decimal
                                field.format.equals("email", ignoreCase = true) -> KeyboardType.Email
                                field.format.equals("uri", ignoreCase = true) -> KeyboardType.Uri
                                isSecret -> KeyboardType.Password
                                else -> KeyboardType.Text
                            },
                        ),
                        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                        singleLine = isNumeric || isSecret,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    val valid = fields.all { field ->
        when (field.type) {
            "boolean" -> true
            "multi_enum" -> !field.required || !multiValues[field.name].isNullOrEmpty()
            "string_array" -> !field.required || textValues[field.name].orEmpty().split('\n', ',', '，').any { it.isNotBlank() }
            "integer" -> {
                val raw = textValues[field.name]?.trim().orEmpty()
                if (raw.isEmpty()) !field.required else raw.toLongOrNull() != null
            }
            "number" -> {
                val raw = textValues[field.name]?.trim().orEmpty()
                if (raw.isEmpty()) !field.required else raw.toDoubleOrNull()?.isFinite() == true
            }
            else -> !field.required || !textValues[field.name].isNullOrBlank()
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryPillButton(
            text = submitLabel,
            enabled = valid,
            onClick = {
                val obj = JSONObject()
                fields.forEach { field ->
                    when (field.type) {
                        "boolean" -> obj.put(field.name, boolValues[field.name] == true)
                        "multi_enum" -> obj.put(field.name, JSONArray(multiValues[field.name].orEmpty()))
                        "string_array" -> {
                            val list = textValues[field.name].orEmpty().split('\n', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                            if (list.isNotEmpty() || field.required) obj.put(field.name, JSONArray(list))
                        }
                        "integer" -> textValues[field.name]?.trim()?.toLongOrNull()?.let { obj.put(field.name, it) }
                        "number" -> textValues[field.name]?.trim()?.toDoubleOrNull()?.let { obj.put(field.name, it) }
                        else -> textValues[field.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { obj.put(field.name, it) }
                    }
                }
                onSubmit(obj)
            },
        )
        SecondaryPillButton(cancelLabel, danger = cancelDanger, onClick = onCancel)
    }
}
