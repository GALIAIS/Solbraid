package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.HostPluginUiSlot
import com.galiais.agent.engine.ItemStatus
import com.galiais.agent.engine.PlanStep
import com.galiais.agent.engine.TimelineItem
import com.galiais.agent.engine.computeStats
import com.galiais.agent.engine.extractArtifactsFromItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

val LocalPluginMessageFooter = compositionLocalOf<Pair<List<HostPluginUiSlot>, (HostPluginUiSlot, TimelineItem.AgentMessage) -> Unit>?> { null }

@Composable
fun TimelineItemView(
    item: TimelineItem,
    onOpenConversation: (ConversationKey) -> Unit,
    onCancelDelegation: () -> Unit = {},
    onQuote: ((String) -> Unit)? = null,
    onEditResend: (() -> Unit)? = null,
    onRevert: ((String) -> Unit)? = null,
    onForkAt: (() -> Unit)? = null,
    isEditing: Boolean = false,
    expandThinking: Boolean = false,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onInspectSubagent: ((String?) -> Unit)? = null,
) {
    when (item) {
        is TimelineItem.UserMessage -> UserMessageBubble(item, onQuote, onEditResend, onRevert, onForkAt, isEditing)
        is TimelineItem.AgentMessage -> AgentMessageView(item, onQuote, onOpenArtifact = onOpenArtifact)
        is TimelineItem.Reasoning -> ThinkingBlock(item, initiallyOpen = expandThinking)
        is TimelineItem.Plan -> PlanArtifactCard(item = item, onOpenArtifact = onOpenArtifact)
        is TimelineItem.CommandRun -> CommandCard(item)
        is TimelineItem.FileEdit -> FileEditCard(item)
        is TimelineItem.ToolCall -> SmartToolCallCard(item, onOpenArtifact = onOpenArtifact, onInspectSubagent = onInspectSubagent)
        is TimelineItem.Delegation -> DelegationCard(item, onOpenConversation, onCancelDelegation)
        is TimelineItem.InteractionReceipt -> InteractionReceiptCard(item)
        is TimelineItem.Notice -> Surface(
            Modifier.fillMaxWidth(),
            shape = D.shape(10.dp),
            color = if (item.error) G.dangerTint else G.surfaceSubtle,
            border = BorderStroke(0.75.dp, if (item.error) G.danger.copy(alpha = 0.35f) else G.border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (item.error) GaliaisIcons.AlertTriangle else GaliaisIcons.Info,
                    contentDescription = null,
                    tint = if (item.error) G.danger else G.inkMuted,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.error) G.danger else G.inkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun UserMessageBubble(
    item: TimelineItem.UserMessage,
    onQuote: ((String) -> Unit)?,
    onEditResend: (() -> Unit)? = null,
    onRevert: ((String) -> Unit)? = null,
    onForkAt: (() -> Unit)? = null,
    isEditing: Boolean = false,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showActions by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (isEditing) {
            Row(
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(GaliaisIcons.Edit, contentDescription = null, tint = G.accent, modifier = Modifier.size(11.dp))
                Text("正在编辑此消息", style = MaterialTheme.typography.labelSmall, color = G.accent, fontWeight = FontWeight.SemiBold)
            }
        }
        val big = D.radius(18.dp)
        val bubbleBorder = when {
            isEditing -> BorderStroke(1.5.dp, G.accent)
            G.userBubbleBorder == Color.Transparent -> null
            else -> BorderStroke(0.75.dp, G.userBubbleBorder)
        }
        Surface(
            modifier = Modifier
                .widthIn(max = 316.dp)
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startPos = down.position
                        val startTime = System.currentTimeMillis()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - startPos).getDistance() > slop) moved = true
                            if (!change.pressed) {
                                if (!moved && System.currentTimeMillis() - startTime < 350L) {
                                    showActions = !showActions
                                }
                                break
                            }
                        }
                    }
                },
            shape = RoundedCornerShape(big, big, D.radius(5.dp), big),
            color = G.userBubble,
            border = bubbleBorder,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.imageCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(GaliaisIcons.Image, contentDescription = null, tint = G.onUserBubble.copy(alpha = 0.78f), modifier = Modifier.size(14.dp))
                        Text("附带 ${item.imageCount} 张图片", style = MaterialTheme.typography.labelSmall, color = G.onUserBubble.copy(alpha = 0.78f))
                    }
                }
                if (item.text.isNotEmpty()) {
                    val prefs = RPrefs
                    val shouldRender = remember(item.text, prefs.shouldRenderUserMessages, prefs.renderMathFormulas) {
                        prefs.shouldRenderUserMessages && hasRichRenderableContent(item.text, prefs.renderMathFormulas)
                    }
                    if (shouldRender) {
                        Markdown(
                            text = item.text,
                            compact = false,
                            sourceItemId = item.id,
                            overrideTextColor = G.onUserBubble,
                        )
                    } else {
                        SelectionContainer {
                            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = G.onUserBubble)
                        }
                    }
                }
            }
        }
        AnimatedExpandable(expanded = showActions) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp, top = 6.dp),
            ) {
                if (onEditResend != null) {
                    Row(
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .background(G.accentContainer.copy(alpha = 0.65f))
                            .clickable {
                                showActions = false
                                onEditResend()
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(GaliaisIcons.Edit, contentDescription = null, tint = G.accent, modifier = Modifier.size(12.dp))
                        Text("编辑重发", style = MaterialTheme.typography.labelSmall, color = G.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (item.text.isNotBlank()) {
                    Row(
                        modifier = Modifier.clip(D.shape(6.dp)).clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("user", item.text).toClipEntry())
                                copied = true
                            }
                        }.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(if (copied) GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copied) G.success else G.inkSubtle, modifier = Modifier.size(12.dp))
                        Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall, color = if (copied) G.success else G.inkSubtle)
                    }
                }
                if (onQuote != null && item.text.isNotBlank()) {
                    Row(
                        modifier = Modifier.clip(D.shape(6.dp)).clickable {
                            val snippet = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: item.text.take(120)
                            onQuote("> $snippet\n\n")
                            showActions = false
                        }.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(GaliaisIcons.Quote, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(12.dp))
                        Text("引用", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                    }
                }
                if (onForkAt != null) {
                    Row(
                        modifier = Modifier.clip(D.shape(6.dp)).clickable {
                            showActions = false
                            onForkAt()
                        }.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(GaliaisIcons.GitBranch, contentDescription = null, tint = G.accent, modifier = Modifier.size(12.dp))
                        Text("从此轮分支", style = MaterialTheme.typography.labelSmall, color = G.accent)
                    }
                }
                if (onRevert != null) {
                    Row(
                        modifier = Modifier.clip(D.shape(6.dp)).clickable {
                            showActions = false
                            onRevert(item.turnId ?: item.id)
                        }.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(GaliaisIcons.Undo, contentDescription = null, tint = G.warning, modifier = Modifier.size(12.dp))
                        Text("回退到此轮前", style = MaterialTheme.typography.labelSmall, color = G.warning)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartToolCallCard(
    item: TimelineItem.ToolCall,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onInspectSubagent: ((String?) -> Unit)? = null,
) {
    val parsed = remember(item.name, item.input, item.kind) { describeToolCall(item.name, item.input, item.kind) }
    val widgetArtifact = remember(item.id, item.name, item.input) { extractArtifactsFromItems(listOf(item)).firstOrNull() }
    if (widgetArtifact != null && item.status != ItemStatus.RUNNING) {
        InlineVisualArtifactCard(artifact = widgetArtifact, onOpenStudio = onOpenArtifact)
        return
    }
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = G.surface,
        border = BorderStroke(1.dp, G.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.status == ItemStatus.RUNNING) {
                    PulsingDot(G.warning, size = 7.dp)
                }
                Icon(
                    parsed.icon(),
                    contentDescription = null,
                    tint = if (item.status == ItemStatus.FAILED) G.danger else parsed.fg(),
                    modifier = Modifier.size(15.dp),
                )
                TagPill(parsed.badge, fg = parsed.fg(), bg = parsed.bg())
                Text(
                    parsed.summary,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                AnimatedChevron(expanded = open)
                if (widgetArtifact != null && onOpenArtifact != null) {
                    TagPill("预览组件", fg = G.accent, bg = G.accentContainer, onClick = { onOpenArtifact(widgetArtifact) })
                }
                if (parsed.kind == ToolCallKind.SUBAGENT && onInspectSubagent != null) {
                    val hintId = remember(item.result, item.id) {
                        Regex("""agentId["']?\s*[:=]\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                            .find(item.result.orEmpty())?.groupValues?.getOrNull(1)
                            ?: item.id.takeIf { it.isNotBlank() }
                    }
                    TagPill("查看子代理", fg = G.claude, bg = G.claudeTint, onClick = { onInspectSubagent(hintId) })
                }
                if (item.status == ItemStatus.FAILED || item.status == ItemStatus.DECLINED) {
                    StatusMark(item.status)
                }
            }
            AnimatedExpandable(expanded = open) {
                Hairline()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                    if (item.input.isNotBlank()) TerminalOutput(item.input, language = "json")
                    item.result?.takeIf { it.isNotBlank() }?.let {
                        Text("输出", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(top = 4.dp))
                        TerminalOutput(it)
                    }
                }
            }
        }
    }
}

internal enum class ToolCallKind {
    READ_FILE,
    LIST_DIR,
    EDIT_FILE,
    DELETE_FILE,
    SEARCH_CODE,
    FIND_FILES,
    WEB_SEARCH,
    WEB_FETCH,
    TERMINAL,
    SUBAGENT,
    SKILL,
    PLAN_TODO,
    IMAGE,
    MOBILE,
    MCP,
    GENERIC,
}

internal data class ToolCallMeta(
    val badge: String,
    val summary: String,
    val accent: String,
    val kind: ToolCallKind = ToolCallKind.GENERIC,
) {
    fun icon(): ImageVector = toolKindIcon(kind)

    @Composable fun fg(): Color = when (accent) {
        "codex" -> G.codex
        "claude" -> G.claude
        "warning" -> G.warning
        else -> G.accent
    }
    @Composable fun bg(): Color = when (accent) {
        "codex" -> G.codexTint
        "claude" -> G.claudeTint
        "warning" -> G.warningTint
        else -> G.accentContainer
    }
}

internal fun toolKindIcon(kind: ToolCallKind): ImageVector = when (kind) {
    ToolCallKind.READ_FILE -> GaliaisIcons.FileLines
    ToolCallKind.LIST_DIR -> GaliaisIcons.Folder
    ToolCallKind.EDIT_FILE -> GaliaisIcons.FilePen
    ToolCallKind.DELETE_FILE -> GaliaisIcons.FileMinus
    ToolCallKind.SEARCH_CODE -> GaliaisIcons.Search
    ToolCallKind.FIND_FILES -> GaliaisIcons.FolderSearch
    ToolCallKind.WEB_SEARCH, ToolCallKind.WEB_FETCH -> GaliaisIcons.Globe
    ToolCallKind.TERMINAL -> GaliaisIcons.Terminal
    ToolCallKind.SUBAGENT -> GaliaisIcons.Bot
    ToolCallKind.SKILL -> GaliaisIcons.BookOpen
    ToolCallKind.PLAN_TODO -> GaliaisIcons.ListCheck
    ToolCallKind.IMAGE -> GaliaisIcons.Image
    ToolCallKind.MOBILE -> GaliaisIcons.Smartphone
    ToolCallKind.MCP -> GaliaisIcons.Plug
    ToolCallKind.GENERIC -> GaliaisIcons.Wrench
}

internal fun fileDiffIcon(kind: String): ImageVector = when (kind.lowercase()) {
    "add", "create" -> GaliaisIcons.FilePlus
    "delete", "remove" -> GaliaisIcons.FileMinus
    else -> GaliaisIcons.FilePen
}

internal fun classifyCommandRun(item: TimelineItem.CommandRun): ToolCallKind {
    val action = item.actionSummary?.trim().orEmpty()
    if (action.startsWith("读取")) return ToolCallKind.READ_FILE
    if (action.startsWith("列出目录")) return ToolCallKind.LIST_DIR
    if (action.startsWith("搜索")) return ToolCallKind.SEARCH_CODE

    val cmd = item.command.trim()
    val hasCompound = cmd.contains("&&") || cmd.contains("||") || cmd.contains(';') || cmd.contains('>')
    if (!hasCompound && cmd.isNotEmpty()) {
        val stripped = cmd.removePrefix("bash -lc ").removePrefix("sh -c ").trim('\'', '"').trim()
        val first = stripped.substringBefore(' ').substringAfterLast('/')
        when (first) {
            "cat", "head", "tail", "nl", "bat" -> return ToolCallKind.READ_FILE
            "sed" -> if (stripped.contains(" -n") || stripped.startsWith("sed -n")) return ToolCallKind.READ_FILE
            "ls", "tree", "dir" -> return ToolCallKind.LIST_DIR
            "rg", "grep", "egrep", "fgrep", "ag" -> return ToolCallKind.SEARCH_CODE
            "find", "fd" -> return ToolCallKind.FIND_FILES
        }
    }
    return ToolCallKind.TERMINAL
}

internal fun commandRunIcon(item: TimelineItem.CommandRun): ImageVector {
    val kind = classifyCommandRun(item)
    if (kind != ToolCallKind.TERMINAL) return toolKindIcon(kind)
    val stripped = item.command.trim().removePrefix("bash -lc ").removePrefix("sh -c ").trim('\'', '"').trim()
    val first = stripped.substringBefore(' ').substringAfterLast('/')
    return if (first == "git") GaliaisIcons.GitBranch else GaliaisIcons.Terminal
}

private val TOOL_RUN_PREFIX_RE = Regex("^(?:run|call|tool)\\s+", RegexOption.IGNORE_CASE)
private val TOOL_ACTION_PREFIX_RE = Regex("^(?:file|read|view|edit|write|delete|remove|search|find|list|fetch|open)\\s+", RegexOption.IGNORE_CASE)

private fun formatMcpToolLabel(cleanName: String): String {
    val body = when {
        cleanName.startsWith("mcp__", ignoreCase = true) -> cleanName.substring(5)
        cleanName.startsWith("mcp_", ignoreCase = true) -> cleanName.substring(4)
        cleanName.startsWith("mcp/", ignoreCase = true) -> cleanName.substring(4)
        else -> cleanName
    }
    return body
        .replace("__", " · ")
        .replace("/", " · ")
        .replace("solbraid_plugin_ctl_solbraid_", "solbraid · ")
        .replace("solbraid_plugin_ctl_", "solbraid · ")
        .ifBlank { cleanName }
}

internal fun describeToolCall(rawName: String, input: String, kindHint: String? = null): ToolCallMeta {
    val name = rawName.trim()
    val cleanName = name.replace(TOOL_RUN_PREFIX_RE, "").trim().ifBlank { name }
    val lower = cleanName.lowercase()
    val kindLower = kindHint?.trim()?.lowercase().orEmpty()
    val obj = runCatching { JSONObject(input) }.getOrNull()
    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        obj?.optString(k)?.takeIf { it.isNotBlank() }
    }
    val plainFallback = input.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("[") }?.lineSequence()?.firstOrNull()?.take(80)
    val strippedSubject = cleanName.replace(TOOL_ACTION_PREFIX_RE, "").trim().ifBlank { cleanName }
    fun pathSummary(): String {
        val fromInput = str("file_path", "path", "Path", "TargetFile", "file", "target_file", "notebook_path")
        if (fromInput != null) return fromInput.substringAfterLast('/')
        if (strippedSubject != cleanName && strippedSubject.isNotBlank()) return strippedSubject.substringAfterLast('/')
        return plainFallback ?: cleanName
    }
    // Solbraid 统一安全文件工具（即使通过 MCP 通道暴露，在时间线中也按原生文件工具呈现，绝不误标成生硬的「MCP」）
    when {
        lower.endsWith("solbraid_file_read") ->
            return ToolCallMeta("读取文件", pathSummary(), "codex", ToolCallKind.READ_FILE)
        lower.endsWith("solbraid_file_edit") ->
            return ToolCallMeta("编辑文件", pathSummary(), "codex", ToolCallKind.EDIT_FILE)
        lower.endsWith("solbraid_file_create") ->
            return ToolCallMeta("创建文件", pathSummary(), "codex", ToolCallKind.EDIT_FILE)
        lower.endsWith("solbraid_file_search") ->
            return ToolCallMeta("搜索文件", str("query", "pattern") ?: pathSummary(), "accent", ToolCallKind.SEARCH_CODE)
        lower.endsWith("solbraid_file_revert") ->
            return ToolCallMeta("撤回文件", pathSummary(), "warning", ToolCallKind.EDIT_FILE)
        // Solbraid 双模式 Mobile-Use 手机操控工具：按原生手机操控卡片呈现，不误标成「MCP」
        lower.endsWith("solbraid_mobile_status") ->
            return ToolCallMeta("手机状态", "查询 Mobile-Use 驱动状态", "accent", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_screenshot") ->
            return ToolCallMeta("手机截屏", "捕获当前手机画面", "claude", ToolCallKind.IMAGE)
        lower.endsWith("solbraid_mobile_ui_tree") ->
            return ToolCallMeta("读取界面", str("query") ?: "解析当前 UI 节点树", "accent", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_tap") -> {
            val target = str("text", "contentDesc", "resourceId")
                ?: if (obj?.has("x") == true && obj.has("y")) "(${obj.optInt("x")}, ${obj.optInt("y")})" else "屏幕坐标"
            return ToolCallMeta(if (obj?.optBoolean("longPress") == true) "长按屏幕" else "点击屏幕", target, "codex", ToolCallKind.MOBILE)
        }
        lower.endsWith("solbraid_mobile_swipe") ->
            return ToolCallMeta("滑动屏幕", str("direction") ?: "手势滑动", "codex", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_type") ->
            return ToolCallMeta("输入文本", str("text")?.take(48) ?: "输入文字", "codex", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_key") ->
            return ToolCallMeta("系统按键", str("key") ?: "按键事件", "codex", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_app") -> {
            val op = str("operation") ?: "list"
            val subj = str("name", "packageName", "query") ?: op
            return ToolCallMeta("手机应用", "$op · $subj", "accent", ToolCallKind.MOBILE)
        }
        lower.endsWith("solbraid_mobile_clipboard") ->
            return ToolCallMeta("剪贴板", str("operation") ?: "read", "accent", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_device") ->
            return ToolCallMeta("设备控制", str("operation") ?: "telemetry", "accent", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_intent") ->
            return ToolCallMeta("跨应用直达", str("url", "page", "operation") ?: "intent", "claude", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_notification") ->
            return ToolCallMeta("手机通知", str("operation", "query") ?: "list", "accent", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_files") ->
            return ToolCallMeta("手机文件", str("hostPath", "guestPath", "operation") ?: "list", "codex", ToolCallKind.MOBILE)
        lower.endsWith("solbraid_mobile_host") ->
            return ToolCallMeta("宿主底层", str("command", "service", "operation") ?: "host", "warning", ToolCallKind.TERMINAL)
    }
    val isTrueMcp = lower.startsWith("mcp__") ||
        lower.startsWith("mcp_") ||
        lower.startsWith("mcp/") ||
        lower == "call_mcp_tool" ||
        lower == "list_resources" ||
        lower == "read_resource" ||
        kindLower == "mcp"
    if (isTrueMcp) {
        val clean = formatMcpToolLabel(cleanName)
        return ToolCallMeta("MCP", str("tool", "name", "query", "path", "url", "command")?.let { "$clean · $it" } ?: clean, "claude", ToolCallKind.MCP)
    }
    when (kindLower) {
        "read" -> return ToolCallMeta("读取文件", pathSummary(), "codex", ToolCallKind.READ_FILE)
        "edit", "write" -> return ToolCallMeta("编辑文件", pathSummary(), "codex", ToolCallKind.EDIT_FILE)
        "delete" -> return ToolCallMeta("删除文件", pathSummary(), "warning", ToolCallKind.DELETE_FILE)
        "search" -> return ToolCallMeta("搜索代码", str("pattern", "query", "Query", "regex") ?: strippedSubject, "accent", ToolCallKind.SEARCH_CODE)
        "fetch" -> return ToolCallMeta("网页读取", str("url", "uri", "Url") ?: strippedSubject, "claude", ToolCallKind.WEB_FETCH)
        "execute" -> return ToolCallMeta("执行命令", str("command", "cmd", "CommandLine") ?: cleanName, "codex", ToolCallKind.TERMINAL)
    }
    return when {
        lower == "read" || lower == "cat" || lower.startsWith("file ") || lower.startsWith("read ") || lower.startsWith("view ") || lower.contains("read_file") || lower.contains("read_many") || lower.contains("view_file") || lower.contains("open_file") || lower.contains("get_file") || name.startsWith("读取") ->
            ToolCallMeta("读取文件", pathSummary(), "codex", ToolCallKind.READ_FILE)
        lower == "ls" || lower == "tree" || lower.startsWith("list ") || lower.contains("list_dir") || lower.contains("list_files") || lower.contains("directory") || name.startsWith("列出") ->
            ToolCallMeta("浏览目录", str("path", "dir", "directory", "dir_path", "DirectoryPath") ?: strippedSubject, "codex", ToolCallKind.LIST_DIR)
        lower.contains("delete_file") || lower.contains("remove_file") || lower.startsWith("delete ") ->
            ToolCallMeta("删除文件", pathSummary(), "warning", ToolCallKind.DELETE_FILE)
        lower == "edit" || lower == "write" || lower == "multiedit" || lower.startsWith("edit ") || lower.startsWith("write ") || lower.contains("edit_file") || lower.contains("write_file") || lower.contains("create_file") || lower.contains("replace") || lower.contains("patch") || lower.contains("notebookedit") ->
            ToolCallMeta("编辑文件", pathSummary(), "codex", ToolCallKind.EDIT_FILE)
        lower == "websearch" || lower.contains("web_search") || lower.contains("search_web") || lower.contains("google_search") || name == "网页搜索" ->
            ToolCallMeta("网页搜索", str("query", "q") ?: plainFallback ?: cleanName, "claude", ToolCallKind.WEB_SEARCH)
        lower == "webfetch" || lower.contains("web_fetch") || lower.contains("fetch") || lower.contains("read_url") || lower.contains("browser") ->
            ToolCallMeta("网页读取", str("url", "uri") ?: plainFallback ?: cleanName, "claude", ToolCallKind.WEB_FETCH)
        lower == "grep" || lower == "rg" || (lower.contains("search") && !lower.contains("web")) ->
            ToolCallMeta("搜索代码", str("pattern", "query", "Query", "regex") ?: plainFallback ?: strippedSubject, "accent", ToolCallKind.SEARCH_CODE)
        lower == "glob" || lower.contains("find") ->
            ToolCallMeta("查找文件", str("pattern", "path", "glob") ?: plainFallback ?: strippedSubject, "accent", ToolCallKind.FIND_FILES)
        lower == "bash" || lower == "shell" || lower.contains("exec") || lower.contains("terminal") || lower.contains("run_command") ->
            ToolCallMeta("执行命令", str("command", "cmd", "CommandLine") ?: plainFallback ?: cleanName, "codex", ToolCallKind.TERMINAL)
        lower == "agent" || lower == "task" || lower.contains("subagent") || lower.contains("collab") || name.startsWith("子代理") ->
            ToolCallMeta("子代理", str("description", "subagent_type", "prompt") ?: plainFallback ?: cleanName.removePrefix("子代理").trim().ifBlank { cleanName }, "claude", ToolCallKind.SUBAGENT)
        lower == "skill" || lower.contains("skill") ->
            ToolCallMeta("技能", str("skill", "name") ?: plainFallback ?: cleanName, "warning", ToolCallKind.SKILL)
        lower == "todowrite" || lower.contains("todo") || lower == "update_plan" || lower == "update_steps" || lower == "update_focus" || lower == "task_boundary" || lower == "plan" ->
            ToolCallMeta("任务清单", str("focus", "explanation", "title") ?: "更新执行计划", "accent", ToolCallKind.PLAN_TODO)
        lower.contains("image") || lower.contains("screenshot") ->
            ToolCallMeta("查看图片", str("path", "file_path", "url")?.substringAfterLast('/') ?: plainFallback ?: cleanName, "claude", ToolCallKind.IMAGE)
        else -> ToolCallMeta("工具", str("file_path", "path", "command", "query", "pattern", "description") ?: plainFallback ?: cleanName, "accent", ToolCallKind.GENERIC)
    }
}

@Composable
internal fun AgentMessageView(
    item: TimelineItem.AgentMessage,
    onQuote: ((String) -> Unit)?,
    showFooterActions: Boolean = true,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.text.isNotEmpty()) {
            if (RPrefs.assistantRichRender) {
                Markdown(item.text, sourceItemId = item.id, streaming = item.streaming, onOpenArtifact = onOpenArtifact)
            } else {
                SelectionContainer {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, color = G.ink)
                }
            }
        }
        if (item.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypingWaveDots(G.accent, dotSize = 5.5.dp)
                if (item.text.isEmpty()) Text("正在生成…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
        } else if (showFooterActions && item.text.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(D.shape(6.dp))
                        .clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("reply", item.text).toClipEntry())
                                copied = true
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AnimatedContent(
                        targetState = copied,
                        transitionSpec = { (fadeIn(tween(120)) togetherWith fadeOut(tween(90))).using(null) },
                        label = "copyReplyState",
                    ) { isCopied ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                if (isCopied) GaliaisIcons.Check else GaliaisIcons.Copy,
                                contentDescription = null,
                                tint = if (isCopied) G.success else G.inkSubtle,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                if (isCopied) "已复制" else "复制全文",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCopied) G.success else G.inkSubtle,
                            )
                        }
                    }
                }
                if (onQuote != null) {
                    Row(
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .clickable {
                                val snippet = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: item.text.take(120)
                                onQuote("> $snippet\n\n")
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(GaliaisIcons.Quote, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(13.dp))
                        Text("引用", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                    }
                }
                val pluginFooter = LocalPluginMessageFooter.current
                pluginFooter?.first?.take(3)?.forEach { slot ->
                    Row(
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .clickable { pluginFooter.second(slot, item) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(13.dp))
                        Text(slot.label.ifBlank { slot.id }, style = MaterialTheme.typography.labelSmall, color = G.accent)
                    }
                }
            }
        }
    }
}

/** 三引擎统一的顶层计划产物卡：支持流式实时更新、折叠预览、全屏产物工作台打开、一键复制、审批执行与提出修改意见。 */
@Composable
fun PlanArtifactCard(
    item: TimelineItem.Plan,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onApprovePlan: (() -> Unit)? = null,
    onRevisePlan: ((String) -> Unit)? = null,
) {
    var open by rememberSaveable(item.id) { mutableStateOf(true) }
    var revising by remember(item.id) { mutableStateOf(false) }
    var reviseText by remember(item.id) { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }
    val planArtifact = remember(item.id, item.text, item.title, item.filePath, item.streaming) {
        ChatArtifact(
            id = "${item.id}:plan",
            identifier = item.filePath?.substringAfterLast('/')?.let { "plan-$it" } ?: "plan-${item.id}",
            title = item.title.ifBlank { "执行计划" },
            kind = ArtifactKind.MARKDOWN,
            language = "markdown",
            content = item.text.trim(),
            sourceItemId = item.id,
            streaming = item.streaming,
        )
    }

    val needsReview = !item.streaming && (item.requestFeedback || item.status == ItemStatus.RUNNING)
    val isWalkthrough = item.artifactType == "walkthrough"
    val accentTint = when {
        isWalkthrough -> G.successTint.copy(alpha = if (G.dark) 0.28f else 0.52f)
        needsReview -> G.warningTint.copy(alpha = if (G.dark) 0.25f else 0.5f)
        else -> G.accentContainer.copy(alpha = if (G.dark) 0.22f else 0.42f)
    }
    val borderCol = when {
        item.streaming -> G.accent.copy(alpha = 0.55f)
        needsReview -> G.warning.copy(alpha = 0.55f)
        isWalkthrough -> G.success.copy(alpha = 0.42f)
        else -> G.borderStrong
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = D.cardShape,
        color = G.surface,
        border = BorderStroke(1.dp, borderCol),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accentTint)
                    .clickable { open = !open }
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (item.streaming) {
                    StatusProgressArc(G.accent, size = 14.dp, strokeWidth = 1.9.dp)
                } else {
                    Icon(
                        if (isWalkthrough) GaliaisIcons.CheckCircle else GaliaisIcons.ListCheck,
                        contentDescription = null,
                        tint = if (isWalkthrough) G.success else if (needsReview) G.warning else G.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        item.title.ifBlank { "执行计划" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = G.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AnimatedChevron(expanded = open, tint = G.inkMuted, size = 14.dp)
                }
                val badge = when {
                    item.streaming -> "规划中…"
                    needsReview -> "待审批"
                    item.status == ItemStatus.DECLINED -> "待修改"
                    isWalkthrough -> "实施总结"
                    item.artifactType == "task" -> "任务清单"
                    else -> "计划产物"
                }
                TagPill(
                    badge,
                    fg = when {
                        needsReview -> G.warning
                        item.status == ItemStatus.DECLINED -> G.danger
                        isWalkthrough -> G.success
                        else -> G.accent
                    },
                    bg = when {
                        needsReview -> G.warningTint
                        item.status == ItemStatus.DECLINED -> G.dangerTint
                        isWalkthrough -> G.successTint
                        else -> G.surface
                    },
                )
            }
            AnimatedExpandable(expanded = open) {
                Column(Modifier.fillMaxWidth()) {
                    Hairline()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        item.filePath?.takeIf { it.isNotBlank() }?.let { path ->
                            val fileHandler = LocalFileLinkHandler.current
                            Text(
                                path.replaceFirst("/home/agent", "~"),
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = if (fileHandler != null) G.accent else G.inkSubtle,
                                textDecoration = if (fileHandler != null) TextDecoration.Underline else TextDecoration.None,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (fileHandler != null) Modifier.clickable { fileHandler(path, null) } else Modifier,
                            )
                        }
                        if (item.text.isNotBlank()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Markdown(item.text, compact = true, streaming = item.streaming, onOpenArtifact = onOpenArtifact)
                            }
                        } else if (item.streaming) {
                            Text("正在生成执行方案…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                        AnimatedExpandable(expanded = revising) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedTextField(
                                    value = reviseText,
                                    onValueChange = { reviseText = it.take(1000) },
                                    label = { Text("输入希望调整的细节或修改建议") },
                                    shape = D.fieldShape,
                                    colors = galiaisTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PrimaryPillButton(
                                        text = "发送修改意见",
                                        enabled = reviseText.isNotBlank(),
                                        onClick = {
                                            val fb = reviseText.trim()
                                            if (fb.isNotEmpty()) {
                                                onRevisePlan?.invoke(fb)
                                                reviseText = ""
                                                revising = false
                                            }
                                        },
                                    )
                                    SecondaryPillButton("取消", onClick = { revising = false })
                                }
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            if (onApprovePlan != null && !item.streaming && item.text.isNotBlank()) {
                                TagPill(
                                    "批准并执行",
                                    fg = G.onAccent,
                                    bg = G.accent,
                                    onClick = {
                                        revising = false
                                        onApprovePlan()
                                    },
                                )
                            }
                            if (onRevisePlan != null && !item.streaming && item.text.isNotBlank() && !revising) {
                                TagPill(
                                    "提出修改意见",
                                    fg = G.warning,
                                    bg = G.warningTint,
                                    border = G.warning.copy(alpha = 0.35f),
                                    onClick = { revising = true },
                                )
                            }
                            if (onOpenArtifact != null && item.text.isNotBlank()) {
                                TagPill(
                                    "工作台全屏",
                                    fg = G.accent,
                                    bg = G.accentContainer,
                                    onClick = { onOpenArtifact(planArtifact) },
                                )
                            }
                            if (item.text.isNotBlank()) {
                                TagPill(
                                    if (copied) "已复制" else "复制方案",
                                    fg = if (copied) G.success else G.inkMuted,
                                    bg = G.surfaceSubtle,
                                    border = G.border,
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipData.newPlainText("plan", item.text).toClipEntry())
                                            copied = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 结构化实时任务执行步骤清单（统一承载 Codex turn/plan/updated、Claude TodoWrite/TaskCreate/TaskUpdate、Antigravity ACP plan/update_steps/task_boundary）。 */
@Composable
fun PlanChecklistCard(
    steps: List<PlanStep>,
    explanation: String?,
    modifier: Modifier = Modifier,
    focusText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val stats = remember(steps) { steps.computeStats() }
    val activeStep = remember(steps) {
        steps.firstOrNull {
            val s = it.status.lowercase()
            s == "in_progress" || s == "inprogress" || s == "running"
        }
    }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(stats.allSettled) {
        if (stats.allSettled) open = false
    }
    val animatedProgress by animateFloatAsState(
        targetValue = stats.progress,
        animationSpec = tween(220),
        label = "planProgress",
    )
    val barColor by animateColorAsState(
        targetValue = when {
            stats.failed > 0 -> G.danger
            stats.allSettled -> G.success
            else -> G.accent
        },
        animationSpec = tween(220),
        label = "planBarColor",
    )
    val badgeText = when {
        stats.failed > 0 -> "步骤 ${stats.settled}/${stats.total} · 失败 ${stats.failed}"
        stats.allSettled && stats.skipped > 0 -> "完成 ${stats.completed} · 跳过 ${stats.skipped}"
        stats.allSettled -> "完成 ${stats.completed}/${stats.total}"
        else -> "步骤 ${stats.settled}/${stats.total}"
    }
    val headerText = activeStep?.activeForm?.takeIf { it.isNotBlank() }
        ?: focusText?.takeIf { !stats.allSettled && it.isNotBlank() }
        ?: activeStep?.step?.takeIf { !open && it.isNotBlank() }
        ?: explanation?.takeIf { it.isNotBlank() }
        ?: if (stats.allSettled) "全部步骤已结束" else "执行步骤"

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = D.cardShape,
        color = G.surface,
        border = BorderStroke(
            1.dp,
            when {
                stats.failed > 0 -> G.danger.copy(alpha = 0.45f)
                activeStep != null -> G.accent.copy(alpha = 0.42f)
                stats.allSettled -> G.success.copy(alpha = 0.35f)
                else -> G.border
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (activeStep != null) {
                    StatusProgressArc(G.accent, size = 14.dp, strokeWidth = 1.9.dp)
                } else {
                    Icon(
                        when {
                            stats.failed > 0 -> GaliaisIcons.AlertTriangle
                            stats.allSettled -> GaliaisIcons.CheckCircle
                            else -> GaliaisIcons.ListCheck
                        },
                        contentDescription = null,
                        tint = when {
                            stats.failed > 0 -> G.danger
                            stats.allSettled -> G.success
                            else -> G.accent
                        },
                        modifier = Modifier.size(15.dp),
                    )
                }
                TagPill(
                    badgeText,
                    fg = when {
                        stats.failed > 0 -> G.danger
                        stats.allSettled -> G.success
                        else -> G.accent
                    },
                    bg = when {
                        stats.failed > 0 -> G.dangerTint
                        stats.allSettled -> G.successTint
                        else -> G.accentContainer
                    },
                )
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        headerText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (activeStep != null) G.ink else G.inkMuted,
                        fontWeight = if (activeStep != null) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AnimatedChevron(expanded = open)
                }
                if (stats.allSettled && onDismiss != null) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GaliaisIcons.Close, contentDescription = "隐藏清单", tint = G.inkSubtle, modifier = Modifier.size(13.dp))
                    }
                }
            }
            // 细进度条：平滑反映整体执行进度（含 skipped 终态统计）
            Box(Modifier.fillMaxWidth().height(2.dp).background(G.surfaceSubtle)) {
                Box(
                    Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(2.dp)
                        .background(barColor),
                )
            }
            AnimatedExpandable(expanded = open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        val s = step.status.lowercase()
                        val isActive = s == "in_progress" || s == "inprogress" || s == "running"
                        val isDone = s == "completed" || s == "done"
                        val isFailed = s == "failed" || s == "error"
                        val isSkipped = s == "skipped" || s == "cancelled" || s == "canceled"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(D.shape(8.dp))
                                .background(
                                    when {
                                        isActive -> G.accentContainer.copy(alpha = if (G.dark) 0.25f else 0.45f)
                                        isFailed -> G.dangerTint.copy(alpha = 0.45f)
                                        else -> Color.Transparent
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = if (isActive || isFailed) 5.dp else 2.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.padding(top = 2.dp).size(15.dp), contentAlignment = Alignment.Center) {
                                when {
                                    isDone -> StatusGlyph(StatusVisual.SUCCESS, G.success, size = 14.5.dp)
                                    isActive -> StatusGlyph(StatusVisual.RUNNING, G.accent, size = 14.5.dp)
                                    isFailed -> StatusGlyph(StatusVisual.DANGER, G.danger, size = 14.5.dp)
                                    isSkipped -> StatusGlyph(StatusVisual.SKIPPED, G.inkSubtle, size = 14.5.dp)
                                    else -> StatusGlyph(StatusVisual.PENDING, G.borderStrong, size = 14.5.dp)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    step.step,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    textDecoration = if (isDone || isSkipped) TextDecoration.LineThrough else TextDecoration.None,
                                    color = when {
                                        isFailed -> G.danger
                                        isDone || isSkipped -> G.inkMuted
                                        else -> G.ink
                                    },
                                )
                                if (isActive && !step.activeForm.isNullOrBlank() && step.activeForm != step.step) {
                                    Text(
                                        step.activeForm,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (isSkipped) {
                                TagPill("已跳过", fg = G.inkSubtle, bg = G.surfaceSubtle, border = G.border)
                            } else if (isFailed) {
                                TagPill("失败", fg = G.danger, bg = G.dangerTint)
                            } else {
                                step.priority?.takeIf { it.isNotBlank() }?.let { prio ->
                                    val prioLabel = when (prio.lowercase()) {
                                        "high", "urgent" -> "高"
                                        "medium", "normal" -> "中"
                                        "low" -> "低"
                                        else -> prio.take(10)
                                    }
                                    TagPill(
                                        prioLabel,
                                        fg = if (prio.equals("high", true)) G.warning else G.inkSubtle,
                                        bg = if (prio.equals("high", true)) G.warningTint else G.surfaceSubtle,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 结构化提问、Elicitation 表单与方案审批的时间线回执卡：确保用户提交选择后在对话流中清晰留痕。 */
@Composable
fun InteractionReceiptCard(item: TimelineItem.InteractionReceipt) {
    var open by rememberSaveable(item.id) { mutableStateOf(item.entries.size <= 3 && item.entries.isNotEmpty()) }
    val declined = item.status == ItemStatus.DECLINED
    val icon = when (item.kind) {
        "elicitation" -> GaliaisIcons.Plug
        "plan_decision" -> GaliaisIcons.ListCheck
        else -> GaliaisIcons.Comments
    }
    val accent = if (declined) G.warning else G.success
    val tint = if (declined) G.warningTint else G.successTint

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = D.shape(11.dp),
        color = G.surfaceSubtle.copy(alpha = 0.65f),
        border = BorderStroke(0.75.dp, if (declined) G.warning.copy(alpha = 0.35f) else G.border),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .clip(D.shape(11.dp))
                    .let { m -> if (item.entries.isNotEmpty()) m.clickable { open = !open } else m }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.5.dp))
                TagPill(
                    when (item.kind) {
                        "elicitation" -> "表单回执"
                        "plan_decision" -> "方案确认"
                        else -> "已选答案"
                    },
                    fg = accent,
                    bg = tint,
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = G.ink,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.entries.isNotEmpty()) {
                    AnimatedChevron(expanded = open, tint = G.inkSubtle, size = 13.dp)
                }
            }
            if (item.entries.isNotEmpty()) {
                AnimatedExpandable(expanded = open) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            item.entries.forEach { (k, v) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        k,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.inkMuted,
                                        modifier = Modifier.widthIn(min = 52.dp, max = 120.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        v,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.ink,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 独立叶节点思考计时标签：避免每 500ms 计时刷新重组整个 ThinkingBlock 与内部 Markdown。 */
@Composable
private fun ThinkingHeaderText(
    streaming: Boolean,
    startedAtMs: Long?,
    previewTitle: String?,
    doneElapsed: String?,
    modifier: Modifier = Modifier,
) {
    var liveElapsedMs by remember(streaming, startedAtMs) {
        mutableStateOf(startedAtMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L)
    }
    LaunchedEffect(streaming, startedAtMs) {
        if (!streaming || startedAtMs == null) return@LaunchedEffect
        while (true) {
            liveElapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val label = if (streaming) {
        val head = previewTitle?.take(26) ?: "正在思考"
        val liveSec = liveElapsedMs.takeIf { it >= 1000L }?.let(::formatTurnDuration)
        if (liveSec != null) "$head · $liveSec" else "$head…"
    } else {
        val base = doneElapsed?.let { "已思考 $it" } ?: "推理过程"
        val titleHint = previewTitle?.take(22)
        if (!titleHint.isNullOrBlank()) "$base · $titleHint" else base
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (streaming) FontWeight.SemiBold else FontWeight.Medium,
        color = if (streaming) G.ink else G.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun ThinkingBlock(item: TimelineItem.Reasoning, initiallyOpen: Boolean) {
    val summary = item.summaryText.trim()
    if (summary.isBlank() && !item.streaming) return
    val preview = remember(summary, item.streaming) { reasoningPreview(summary, item.streaming) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(initiallyOpen) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }

    val effectiveBody = preview.body.ifBlank { summary }
    val hasBody = effectiveBody.isNotBlank()
    val showBody = hasBody && (item.streaming || expanded)
    val scroll = rememberScrollState()
    LaunchedEffect(item.streaming, scroll.maxValue) {
        if (item.streaming && !scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue)
        else if (!item.streaming && !scroll.isScrollInProgress) scroll.scrollTo(0)
    }

    val doneElapsed = item.durationMs?.takeIf { it >= 1000L }?.let(::formatTurnDuration)

    Column(Modifier.fillMaxWidth()) {
        // 与顶层折叠条保持一致：包裹内容宽度且箭头紧跟文字后，无大块留白且展开前后标题长度恒定不横跳。
        Row(
            Modifier
                .clip(D.shape(8.dp))
                .clickable(enabled = !item.streaming && hasBody, role = Role.Button) { expanded = !expanded }
                .semantics { if (!item.streaming && hasBody) stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (item.streaming) {
                TypingWaveDots(if (item.summaryParts != null) G.codex else G.accent, dotSize = 4.5.dp)
            } else {
                Icon(GaliaisIcons.Lightbulb, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(14.dp))
            }
            ThinkingHeaderText(
                streaming = item.streaming,
                startedAtMs = item.startedAtMs,
                previewTitle = preview.title,
                doneElapsed = doneElapsed,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!item.streaming && hasBody) {
                AnimatedChevron(expanded = expanded, tint = G.inkSubtle, size = 14.dp)
            }
        }
        AnimatedExpandable(expanded = showBody) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                shape = D.shape(11.dp),
                color = G.surfaceSubtle.copy(alpha = if (G.dark) 0.52f else 0.62f),
                border = BorderStroke(
                    0.5.dp,
                    if (item.streaming) G.accent.copy(alpha = 0.35f) else G.border.copy(alpha = 0.65f),
                ),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .let { mod ->
                                if (item.streaming) mod.heightIn(max = 172.dp).verticalScroll(scroll)
                                else mod.heightIn(max = 320.dp).verticalScroll(scroll)
                            },
                    ) {
                        Markdown(effectiveBody, compact = true, streaming = item.streaming)
                    }
                    if (!item.streaming && hasBody) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .clip(D.shape(5.dp))
                                .clickable {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipData.newPlainText("reasoning", summary).toClipEntry())
                                        copied = true
                                    }
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(if (copied) GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copied) G.success else G.inkSubtle, modifier = Modifier.size(11.dp))
                            Text(if (copied) "已复制" else "复制思考", fontSize = 10.5.sp, color = if (copied) G.success else G.inkSubtle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandCard(item: TimelineItem.CommandRun) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    var expandFullCommand by remember(item.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedLabel) { if (copiedLabel != null) { delay(1200); copiedLabel = null } }
    val cmdLines = remember(item.command) { item.command.lines() }
    val isLongCommand = remember(item.command) { cmdLines.size > 2 || item.command.length > 140 }
    val oneLineTitle = remember(item.command, item.actionSummary) {
        item.actionSummary?.takeIf { it.isNotBlank() }
            ?: item.command.replace(Regex("\\s+"), " ").trim().ifBlank { "执行命令" }
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = D.codeShape,
        color = G.codeBg,
        border = BorderStroke(1.dp, G.codeBorder),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(G.codeHeader).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(commandRunIcon(item), contentDescription = null, tint = G.codex, modifier = Modifier.size(14.dp))
                Text(
                    oneLineTitle,
                    fontFamily = Mono,
                    fontSize = 12.5.sp,
                    color = G.codeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                AnimatedChevron(expanded = open, tint = G.codeMuted, size = 14.dp)
                item.exitCode?.let { code ->
                    TagPill("exit $code", fg = if (code == 0) G.success else G.danger, bg = if (code == 0) G.successTint else G.dangerTint)
                }
                StatusMark(item.status)
            }
            AnimatedExpandable(expanded = open) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.command.isNotBlank()) {
                        Surface(shape = D.shape(7.dp), color = G.codeHeader, border = BorderStroke(0.5.dp, G.codeBorder)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("$", fontFamily = Mono, fontSize = 11.sp, color = G.codex, fontWeight = FontWeight.Bold)
                                Box(Modifier.weight(1f).let { m -> if (expandFullCommand) m.heightIn(max = 124.dp).verticalScroll(rememberScrollState()) else m }) {
                                    Text(
                                        item.command,
                                        fontFamily = Mono,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        color = G.codeInk,
                                        maxLines = if (expandFullCommand) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isLongCommand) {
                                    Text(
                                        if (expandFullCommand) "收起" else "展开(${cmdLines.size}行)",
                                        fontSize = 10.5.sp,
                                        color = G.accent,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clip(D.shape(4.dp)).clickable { expandFullCommand = !expandFullCommand }.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.cwd?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = G.codeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                            ?: Spacer(Modifier.weight(1f))
                        if (item.command.isNotBlank()) {
                            Row(
                                modifier = Modifier.clip(D.shape(4.dp)).clickable {
                                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("cmd", item.command).toClipEntry()); copiedLabel = "cmd" }
                                }.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(if (copiedLabel == "cmd") GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copiedLabel == "cmd") G.codex else G.codeMuted, modifier = Modifier.size(12.dp))
                                Text(if (copiedLabel == "cmd") "已复制" else "复制命令", fontSize = 11.sp, color = if (copiedLabel == "cmd") G.codex else G.codeMuted)
                            }
                        }
                        if (item.output.isNotBlank()) {
                            Row(
                                modifier = Modifier.clip(D.shape(4.dp)).clickable {
                                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText("output", item.output).toClipEntry()); copiedLabel = "out" }
                                }.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(if (copiedLabel == "out") GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copiedLabel == "out") G.codex else G.codeMuted, modifier = Modifier.size(12.dp))
                                Text(if (copiedLabel == "out") "已复制" else "复制输出", fontSize = 11.sp, color = if (copiedLabel == "out") G.codex else G.codeMuted)
                            }
                        }
                    }
                    if (item.output.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                item.output.takeLast(20_000),
                                fontFamily = Mono,
                                fontSize = D.codeSize(12f),
                                lineHeight = D.codeSize(17f),
                                color = G.codeInk,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                            )
                        }
                    } else if (item.status == ItemStatus.RUNNING) {
                        Text("等待输出…", fontFamily = Mono, fontSize = 11.5.sp, color = G.codeMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEditCard(item: TimelineItem.FileEdit) {
    val totals = remember(item.changes) {
        var a = 0; var r = 0
        for (c in item.changes) { val (ca, cr) = diffStats(c.diff); a += ca; r += cr }
        a to r
    }
    val names = item.changes.joinToString("，") { it.path.substringAfterLast('/') }
    ActivityCard(
        itemId = item.id,
        title = if (item.changes.size == 1) names else "${item.changes.size} 个文件 · $names",
        status = item.status,
        trailing = "+${totals.first} -${totals.second}",
        icon = if (item.changes.size == 1) fileDiffIcon(item.changes.first().kind) else GaliaisIcons.FilePen,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { item.changes.forEach { DiffView(it, inlineCompact = true) } }
    }
}

@Composable
private fun DelegationCard(item: TimelineItem.Delegation, onOpenConversation: (ConversationKey) -> Unit, onCancelDelegation: () -> Unit) {
    val accent = agentAccent(item.target)
    val tint = agentTint(item.target)
    Surface(
        Modifier.fillMaxWidth(),
        shape = D.cardShape,
        color = G.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentAppIcon(engine = item.target, size = 20.dp)
                TagPill("委派 → ${item.target.label}", fg = accent, bg = tint)
                Spacer(Modifier.weight(1f))
                StatusMark(item.status)
            }
            Text(item.task, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = G.ink)
            item.result?.let { Collapsible("查看结果", initiallyOpen = item.status == ItemStatus.FAILED) { Markdown(it) } }
            if (item.child != null || item.status == ItemStatus.RUNNING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.child?.let { child -> TagPill("查看子会话", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { onOpenConversation(child) }) }
                    if (item.status == ItemStatus.RUNNING) TagPill("取消委派", fg = G.danger, bg = G.dangerTint, onClick = onCancelDelegation)
                }
            }
        }
    }
}

@Composable
private fun TerminalOutput(text: String, language: String? = null) {
    val palette = D.code
    val content = remember(text, language, palette) {
        val clipped = text.takeLast(20_000)
        if (language != null) SyntaxHighlight.highlight(clipped, language, palette) else null
    }
    SelectionContainer {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(D.shape(8.dp)).background(G.codeBg)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp),
        ) {
            if (content != null) Text(content, fontFamily = Mono, fontSize = D.codeSize(12f), lineHeight = D.codeSize(17f))
            else Text(text.takeLast(20_000), fontFamily = Mono, fontSize = D.codeSize(12f), lineHeight = D.codeSize(17f), color = G.codeInk)
        }
    }
}

@Composable
fun StatusMark(status: ItemStatus) {
    when (status) {
        ItemStatus.RUNNING -> StatusGlyph(StatusVisual.RUNNING, G.warning, size = 15.dp)
        ItemStatus.COMPLETED -> StatusGlyph(StatusVisual.SUCCESS, G.success, size = 15.dp)
        ItemStatus.FAILED -> StatusGlyph(StatusVisual.DANGER, G.danger, size = 15.dp)
        ItemStatus.DECLINED -> StatusGlyph(StatusVisual.SKIPPED, G.inkSubtle, size = 15.dp)
    }
}

@Composable
private fun ActivityCard(
    itemId: String,
    title: String,
    status: ItemStatus,
    trailing: String?,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(itemId) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = G.surface,
        border = BorderStroke(1.dp, G.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    if (status == ItemStatus.RUNNING) PulsingDot(G.warning, size = 7.dp)
                    Icon(icon, contentDescription = null, tint = if (status == ItemStatus.FAILED) G.danger else G.inkMuted, modifier = Modifier.size(15.dp))
                } else {
                    StatusMark(status)
                }
                Text(
                    title,
                    fontFamily = Mono,
                    fontSize = 12.5.sp,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                AnimatedChevron(expanded = open)
                trailing?.let { Text(it, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted) }
            }
            AnimatedExpandable(expanded = open) {
                Hairline()
                Column(Modifier.padding(12.dp)) { content() }
            }
        }
    }
}

@Composable
fun Collapsible(title: String, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(D.shape(6.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            AnimatedChevron(expanded = open, tint = G.inkMuted, size = 14.dp)
        }
        AnimatedExpandable(expanded = open) {
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
