package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.FileDiff
import com.galiais.agent.engine.ItemStatus
import com.galiais.agent.engine.TimelineItem
import com.galiais.agent.engine.extractArtifactsFromItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 单文件变更汇总（按路径去重合并）。 */
@Immutable
data class FileChangeSummary(
    val path: String,
    val added: Int,
    val removed: Int,
    val diffs: List<FileDiff>,
)

/** Codex 风格轮次分组模型：将一轮的用户消息、可折叠执行轨迹、内联交互组件、最终答复与变更文件汇总打包。 */
@Immutable
data class TurnBlock(
    val id: String,
    val userMessage: TimelineItem.UserMessage?,
    val traceItems: List<TimelineItem>,
    val planItems: List<TimelineItem.Plan> = emptyList(),
    val receipts: List<TimelineItem.InteractionReceipt> = emptyList(),
    val visualToolCalls: List<TimelineItem.ToolCall> = emptyList(),
    val finalMessage: TimelineItem.AgentMessage?,
    val trailingNotices: List<TimelineItem.Notice>,
    val editedFiles: List<FileChangeSummary>,
    val durationMs: Long?,
    val inProgress: Boolean,
)

private val VISUAL_TOOL_NAMES = setOf("show_widget", "visualize", "render_artifact", "create_artifact")

private fun isVisualToolCallWithArtifact(item: TimelineItem): Boolean =
    item is TimelineItem.ToolCall && item.name in VISUAL_TOOL_NAMES && extractArtifactsFromItems(listOf(item)).isNotEmpty()

/** 供 LazyColumn 使用的稳定回调聚合体，避免在非 Composable 的 LazyListScope 中反复分配闭包触发整屏重组。 */
@Stable
class ChatTimelineActions(
    val onOpenConversation: (ConversationKey) -> Unit,
    val onCancelDelegation: () -> Unit,
    val onQuote: ((String) -> Unit)?,
    val onEditResend: ((TimelineItem.UserMessage) -> Unit)? = null,
    val onRegenerate: ((TimelineItem.UserMessage) -> Unit)? = null,
    val onUndoTurn: ((TimelineItem.UserMessage) -> Unit)? = null,
    val onRewindFiles: ((TimelineItem.UserMessage) -> Unit)? = null,
    val onFork: (() -> Unit)? = null,
    val onForkAt: ((TimelineItem.UserMessage) -> Unit)? = null,
    val onInspectDiffs: (List<FileDiff>) -> Unit,
    val onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    val onInspectSubagent: ((String?) -> Unit)? = null,
    val onApprovePlan: ((TimelineItem.Plan) -> Unit)? = null,
    val onRevisePlan: ((TimelineItem.Plan, String) -> Unit)? = null,
)

/**
 * 轮次增量分组缓存：已完成的历史轮次按 (user, body, durationMs) 复用不可变 [TurnBlock]，
 * 流式输出期间仅重算最后一轮，彻底消除每 48ms 扫描全历史 Diff 与工具调用的主线程开销。
 */
class TurnBlockIncrementalCache {
    private data class CachedTurn(
        val user: TimelineItem.UserMessage?,
        val body: List<TimelineItem>,
        val durationMs: Long?,
        val inProgress: Boolean,
        val block: TurnBlock,
    )

    private var previous: List<CachedTurn> = emptyList()

    fun group(
        items: List<TimelineItem>,
        turnDurations: Map<String, Long>,
        running: Boolean,
    ): List<TurnBlock> {
        if (items.isEmpty()) {
            previous = emptyList()
            return emptyList()
        }
        val rawGroups = ArrayList<Pair<TimelineItem.UserMessage?, ArrayList<TimelineItem>>>()
        for (item in items) {
            if (item is TimelineItem.UserMessage) {
                rawGroups += item to ArrayList()
            } else {
                if (rawGroups.isEmpty()) rawGroups += null to ArrayList()
                rawGroups.last().second += item
            }
        }
        val nextCache = ArrayList<CachedTurn>(rawGroups.size)
        val out = ArrayList<TurnBlock>(rawGroups.size)
        rawGroups.forEachIndexed { idx, (user, body) ->
            val isLast = idx == rawGroups.lastIndex
            val inProgress = running && isLast
            val durMs = user?.durationMs
                ?: user?.turnId?.let { turnDurations[it] }
                ?: user?.id?.let { turnDurations[it] }
            val prev = previous.getOrNull(idx)
            val reused = prev != null &&
                !inProgress &&
                !prev.inProgress &&
                prev.user == user &&
                prev.durationMs == durMs &&
                prev.body == body
            val block = if (reused) prev.block else buildSingleTurnBlock(idx, user, body, durMs, inProgress)
            nextCache += CachedTurn(user, body, durMs, inProgress, block)
            out += block
        }
        previous = nextCache
        return out
    }
}

private fun buildSingleTurnBlock(
    idx: Int,
    user: TimelineItem.UserMessage?,
    body: List<TimelineItem>,
    durMs: Long?,
    inProgress: Boolean,
): TurnBlock {
    // 清理空推理摘要块（GPT/Claude 省略摘要时常产生空占位，不应展示空白折叠行）
    val cleaned = body.filterNot { it is TimelineItem.Reasoning && it.summaryText.isBlank() && !it.streaming }

    val finalCandidate: TimelineItem.AgentMessage? = run {
        val msgs = cleaned.filterIsInstance<TimelineItem.AgentMessage>()
        val explicitFinal = msgs.lastOrNull { it.phase == "final_answer" && (it.text.isNotBlank() || it.streaming) }
        if (explicitFinal != null) explicitFinal
        else {
            val lastMsg = msgs.lastOrNull { it.text.isNotBlank() || it.streaming }
            if (lastMsg == null) null
            else {
                val lastMsgIdx = cleaned.indexOfLast { it.id == lastMsg.id }
                val hasWorkAfter = cleaned.drop(lastMsgIdx + 1).any {
                    it is TimelineItem.CommandRun || it is TimelineItem.FileEdit || it is TimelineItem.ToolCall || it is TimelineItem.Delegation
                }
                if (!hasWorkAfter && lastMsg.phase != "commentary") lastMsg
                else if (!inProgress) lastMsg
                else null
            }
        }
    }

    val trailingNotices = cleaned.filterIsInstance<TimelineItem.Notice>().filter { it.error }
    val planItems = cleaned.filterIsInstance<TimelineItem.Plan>().filter { it.text.isNotBlank() || it.streaming }
    val receipts = cleaned.filterIsInstance<TimelineItem.InteractionReceipt>()
    val visualTools = cleaned.filter(::isVisualToolCallWithArtifact).filterIsInstance<TimelineItem.ToolCall>()
    val excludedIds = buildSet {
        finalCandidate?.id?.let(::add)
        trailingNotices.forEach { add(it.id) }
        planItems.forEach { add(it.id) }
        receipts.forEach { add(it.id) }
        visualTools.forEach { add(it.id) }
    }
    val traceItems = cleaned.filterNot { it.id in excludedIds }

    val byPath = linkedMapOf<String, MutableList<FileDiff>>()
    for (it in cleaned) {
        if (it is TimelineItem.FileEdit) {
            for (c in it.changes) {
                byPath.getOrPut(c.path) { mutableListOf() } += c
            }
        }
    }
    val editedFiles = byPath.map { (path, diffs) ->
        var add = 0
        var rem = 0
        for (d in diffs) {
            val (a, r) = diffStats(d.diff)
            add += a
            rem += r
        }
        FileChangeSummary(path = path, added = add, removed = rem, diffs = diffs)
    }

    return TurnBlock(
        id = user?.id ?: "turn-head-$idx",
        userMessage = user,
        traceItems = traceItems,
        planItems = planItems,
        receipts = receipts,
        visualToolCalls = visualTools,
        finalMessage = finalCandidate,
        trailingNotices = trailingNotices,
        editedFiles = editedFiles,
        durationMs = durMs,
        inProgress = inProgress,
    )
}

/** 将平铺的时间线条目归组为 Codex 风格轮次块，自动区分过程旁白（commentary）与最终答复（final_answer）。 */
fun groupIntoTurnBlocks(
    items: List<TimelineItem>,
    turnDurations: Map<String, Long>,
    running: Boolean,
): List<TurnBlock> {
    if (items.isEmpty()) return emptyList()
    return TurnBlockIncrementalCache().group(items, turnDurations, running)
}

fun formatTurnDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(1L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return when {
        min >= 60L -> "${min / 60L}小时 ${min % 60L}分钟"
        min > 0L -> if (sec > 0L) "${min}分钟 ${sec}秒" else "${min}分钟"
        else -> "${sec}秒"
    }
}

/**
 * 将长篇已定稿 Markdown 文本按安全块边界（不截断围栏代码块、数学块、表格与 `<antArtifact>`）切分为 ~1.2KB 虚拟化块，
 * 供 `LazyColumn` 按屏懒加载与回收，彻底消除长回复单 item 全量排版卡顿。
 */
internal fun splitMarkdownVirtualChunks(text: String, targetChunkChars: Int = 1200): List<String> {
    if (text.length <= targetChunkChars + 600) return listOf(text)
    val chunks = ArrayList<String>()
    var chunkStart = 0
    var pos = 0
    var inFence = false
    var inMath = false
    var inArtifact = false
    val len = text.length
    while (pos < len) {
        val nl = text.indexOf('\n', pos)
        val end = if (nl < 0) len else nl
        val line = text.substring(pos, end)
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
        } else if (!inFence) {
            if (trimmed == "$$" || (trimmed.startsWith("$$") && !trimmed.drop(2).contains("$$"))) {
                inMath = !inMath
            } else if (trimmed.startsWith("\\[") && !trimmed.contains("\\]")) {
                inMath = true
            } else if (inMath && trimmed.contains("\\]")) {
                inMath = false
            }
            if (line.contains("<antArtifact", ignoreCase = true) && !line.contains("</antArtifact>", ignoreCase = true)) {
                inArtifact = true
            } else if (inArtifact && line.contains("</antArtifact>", ignoreCase = true)) {
                inArtifact = false
            }
        }
        val nextPos = if (nl < 0) len else nl + 1
        if (trimmed.isEmpty() && !inFence && !inMath && !inArtifact && (nextPos - chunkStart) >= targetChunkChars && nextPos < len) {
            val peekEnd = text.indexOf('\n', nextPos).let { if (it < 0) len else it }
            val nextTrim = text.substring(nextPos, peekEnd).trim()
            if (nextTrim.isNotEmpty() && !nextTrim.startsWith("|")) {
                val slice = text.substring(chunkStart, nextPos).trimEnd()
                if (slice.isNotEmpty()) chunks += slice
                chunkStart = nextPos
            }
        }
        pos = nextPos
    }
    if (chunkStart < len) {
        val tail = text.substring(chunkStart).trimEnd()
        if (tail.isNotEmpty()) chunks += tail
    }
    return if (chunks.isEmpty()) listOf(text) else chunks
}

/**
 * 细粒度铺平单轮 LazyColumn 项：
 * 将用户气泡、执行轨迹、内联组件、长回复虚拟化切片、变更汇总拆分为独立 key 的 LazyColumn 子项，
 * 并通过 [ChatTimelineActions] 将每项闭包收敛到 @Composable 内部，杜绝非 Composable 作用域闭包抖动。
 */
fun LazyListScope.turnBlockItems(
    block: TurnBlock,
    activeTurnStartedAt: Long?,
    expandByDefault: Boolean,
    cwd: String? = null,
    focusText: String? = null,
    editingUserMessageId: String? = null,
    enablePlanActions: Boolean = false,
    actions: ChatTimelineActions,
) {
    val editableUser = block.userMessage?.takeIf { !it.id.startsWith("steer-") }
    block.userMessage?.let { user ->
        val canEdit = editableUser != null
        item(key = "u-${block.id}-${user.id}", contentType = "user_msg") {
            val editCb = remember(canEdit, user, actions.onEditResend) {
                if (canEdit && actions.onEditResend != null) ({ actions.onEditResend.invoke(user) }) else null
            }
            val revertCb: ((String) -> Unit)? = remember(canEdit, user, actions.onUndoTurn) {
                if (canEdit && actions.onUndoTurn != null) ({ _: String -> actions.onUndoTurn.invoke(user) }) else null
            }
            val forkAtCb: (() -> Unit)? = remember(canEdit, user, actions.onForkAt) {
                if (canEdit && actions.onForkAt != null) ({ actions.onForkAt.invoke(user) }) else null
            }
            UserMessageBubble(
                item = user,
                onQuote = actions.onQuote,
                onEditResend = editCb,
                onRevert = revertCb,
                onForkAt = forkAtCb,
                isEditing = editingUserMessageId == user.id,
            )
        }
    }

    if (block.traceItems.isNotEmpty() || block.inProgress) {
        item(key = "t-${block.id}", contentType = "turn_trace") {
            CodexTurnTrace(
                block = block,
                activeTurnStartedAt = activeTurnStartedAt,
                expandByDefault = expandByDefault,
                cwd = cwd,
                focusText = if (block.inProgress) focusText else null,
                onOpenConversation = actions.onOpenConversation,
                onCancelDelegation = actions.onCancelDelegation,
                onOpenArtifact = actions.onOpenArtifact,
                onInspectSubagent = actions.onInspectSubagent,
            )
        }
    }

    block.receipts.forEach { receipt ->
        item(key = "r-${receipt.id}", contentType = "interaction_receipt") {
            InteractionReceiptCard(receipt)
        }
    }

    block.planItems.forEachIndexed { idx, plan ->
        val isLatestPlan = idx == block.planItems.lastIndex
        val actionable = enablePlanActions && !block.inProgress && (isLatestPlan || plan.requestFeedback || plan.status == ItemStatus.RUNNING)
        item(key = "p-${plan.id}", contentType = "plan_artifact") {
            val approveCb = remember(actionable, plan, actions.onApprovePlan) {
                if (actionable && actions.onApprovePlan != null) ({ actions.onApprovePlan.invoke(plan) }) else null
            }
            val reviseCb = remember(actionable, plan, actions.onRevisePlan) {
                if (actionable && actions.onRevisePlan != null) ({ fb: String -> actions.onRevisePlan.invoke(plan, fb) }) else null
            }
            PlanArtifactCard(
                item = plan,
                onOpenArtifact = actions.onOpenArtifact,
                onApprovePlan = approveCb,
                onRevisePlan = reviseCb,
            )
        }
    }

    block.visualToolCalls.forEach { tc ->
        item(key = "v-${tc.id}", contentType = "visual_widget") {
            val widget = remember(tc.id, tc.input) { extractArtifactsFromItems(listOf(tc)).firstOrNull() }
            if (widget != null) {
                InlineVisualArtifactCard(artifact = widget, onOpenStudio = actions.onOpenArtifact)
            }
        }
    }

    block.finalMessage?.let { msg ->
        if (!msg.streaming && msg.text.length > 1800) {
            val chunks = splitMarkdownVirtualChunks(msg.text)
            chunks.forEachIndexed { cIdx, chunk ->
                val isLastChunk = cIdx == chunks.lastIndex
                item(key = "m-${block.id}-${msg.id}-c$cIdx", contentType = "agent_msg_chunk") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Markdown(
                            text = chunk,
                            sourceItemId = "${msg.id}:c$cIdx",
                            streaming = false,
                            onOpenArtifact = actions.onOpenArtifact,
                        )
                        if (isLastChunk) {
                            val pluginFooter = LocalPluginMessageFooter.current
                            if (pluginFooter != null && pluginFooter.first.isNotEmpty() && msg.text.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    pluginFooter.first.forEach { slot ->
                                        TagPill(
                                            text = slot.badge.ifBlank { slot.label.ifBlank { slot.id } },
                                            fg = G.accent,
                                            bg = G.accentContainer,
                                            onClick = { pluginFooter.second(slot, msg) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "m-${block.id}-${msg.id}", contentType = "agent_msg") {
                AgentMessageView(item = msg, onQuote = actions.onQuote, showFooterActions = false, onOpenArtifact = actions.onOpenArtifact)
            }
        }
    }

    block.trailingNotices.forEach { notice ->
        item(key = "n-${notice.id}", contentType = "notice") {
            TimelineItemView(
                item = notice,
                onOpenConversation = actions.onOpenConversation,
                onCancelDelegation = actions.onCancelDelegation,
            )
        }
    }

    if (block.editedFiles.isNotEmpty() && !block.inProgress) {
        item(key = "f-${block.id}", contentType = "edited_files") {
            val undoFilesAction = remember(editableUser, actions.onRewindFiles, actions.onUndoTurn) {
                editableUser?.let { u -> (actions.onRewindFiles ?: actions.onUndoTurn)?.let { cb -> ({ cb(u) }) } }
            }
            val viewAllCb = remember(block.editedFiles, actions.onInspectDiffs) {
                { actions.onInspectDiffs(block.editedFiles.flatMap { it.diffs }) }
            }
            val viewFileCb = remember(actions.onInspectDiffs) {
                { file: FileChangeSummary -> actions.onInspectDiffs(file.diffs) }
            }
            EditedFilesSummaryCard(
                files = block.editedFiles,
                cwd = cwd,
                onUndo = undoFilesAction,
                onViewAll = viewAllCb,
                onViewFile = viewFileCb,
            )
        }
    }

    val answerText = block.finalMessage?.takeIf { !it.streaming && it.text.isNotBlank() }?.text
    val hasFooterTrigger = answerText != null || block.editedFiles.isNotEmpty() || block.trailingNotices.isNotEmpty()
    if (!block.inProgress && hasFooterTrigger) {
        item(key = "a-${block.id}", contentType = "turn_footer") {
            val quoteCb = remember(answerText, actions.onQuote) {
                if (answerText != null && actions.onQuote != null) ({
                    val snippet = answerText.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: answerText.take(120)
                    actions.onQuote.invoke("> $snippet\n\n")
                }) else null
            }
            val editCb = remember(editableUser, actions.onEditResend) {
                editableUser?.let { u -> actions.onEditResend?.let { cb -> ({ cb(u) }) } }
            }
            val regenCb = remember(editableUser, actions.onRegenerate) {
                editableUser?.takeIf { it.text.isNotBlank() }?.let { u -> actions.onRegenerate?.let { cb -> ({ cb(u) }) } }
            }
            val viewDiffsCb = remember(block.editedFiles, actions.onInspectDiffs) {
                if (block.editedFiles.isNotEmpty()) ({ actions.onInspectDiffs(block.editedFiles.flatMap { it.diffs }) }) else null
            }
            val undoCb = remember(editableUser, actions.onUndoTurn) {
                editableUser?.let { u -> actions.onUndoTurn?.let { cb -> ({ cb(u) }) } }
            }
            val rewindCb = remember(block.editedFiles, editableUser, actions.onRewindFiles) {
                if (block.editedFiles.isNotEmpty()) editableUser?.let { u -> actions.onRewindFiles?.let { cb -> ({ cb(u) }) } } else null
            }
            val forkCb = remember(editableUser, actions.onForkAt, actions.onFork) {
                editableUser?.let { u -> actions.onForkAt?.let { cb -> ({ cb(u) }) } } ?: actions.onFork
            }
            TurnFooterActionBar(
                copyText = answerText,
                onQuote = quoteCb,
                onEditResend = editCb,
                onRegenerate = regenCb,
                onViewDiffs = viewDiffsCb,
                onUndo = undoCb,
                onRewindFiles = rewindCb,
                onFork = forkCb,
            )
        }
    }
}

/** Codex 风格单轮渲染：用户气泡 → 可折叠执行轨迹（用时 X分Y秒 >）→ 最终回复 → 轮末文件变更汇总卡 → 底部轻量操作栏。 */
@Composable
fun TurnBlockView(
    block: TurnBlock,
    activeTurnStartedAt: Long?,
    expandByDefault: Boolean,
    cwd: String? = null,
    editingUserMessageId: String? = null,
    onOpenConversation: (ConversationKey) -> Unit,
    onCancelDelegation: () -> Unit,
    onQuote: ((String) -> Unit)?,
    onEditResend: ((TimelineItem.UserMessage) -> Unit)? = null,
    onRegenerate: ((TimelineItem.UserMessage) -> Unit)? = null,
    onUndoTurn: ((TimelineItem.UserMessage) -> Unit)?,
    onRewindFiles: ((TimelineItem.UserMessage) -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    onInspectDiffs: (List<FileDiff>) -> Unit,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onApprovePlan: ((TimelineItem.Plan) -> Unit)? = null,
    onRevisePlan: ((TimelineItem.Plan, String) -> Unit)? = null,
) {
    val editableUser = block.userMessage?.takeIf { !it.id.startsWith("steer-") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
        block.userMessage?.let { user ->
            val canEdit = editableUser != null
            UserMessageBubble(
                item = user,
                onQuote = onQuote,
                onEditResend = if (canEdit && onEditResend != null) ({ onEditResend(user) }) else null,
                onRevert = if (canEdit && onUndoTurn != null) ({ onUndoTurn(user) }) else null,
                isEditing = editingUserMessageId == user.id,
            )
        }

        if (block.traceItems.isNotEmpty() || block.inProgress) {
            CodexTurnTrace(
                block = block,
                activeTurnStartedAt = activeTurnStartedAt,
                expandByDefault = expandByDefault,
                cwd = cwd,
                onOpenConversation = onOpenConversation,
                onCancelDelegation = onCancelDelegation,
                onOpenArtifact = onOpenArtifact,
            )
        }

        block.receipts.forEach { receipt ->
            InteractionReceiptCard(receipt)
        }

        block.planItems.forEachIndexed { idx, plan ->
            val isLatestPlan = idx == block.planItems.lastIndex
            val actionable = !block.inProgress && (isLatestPlan || plan.requestFeedback || plan.status == ItemStatus.RUNNING)
            PlanArtifactCard(
                item = plan,
                onOpenArtifact = onOpenArtifact,
                onApprovePlan = if (actionable && onApprovePlan != null) ({ onApprovePlan(plan) }) else null,
                onRevisePlan = if (actionable && onRevisePlan != null) ({ fb -> onRevisePlan(plan, fb) }) else null,
            )
        }

        block.visualToolCalls.forEach { tc ->
            val widget = remember(tc.id, tc.input) { extractArtifactsFromItems(listOf(tc)).firstOrNull() }
            if (widget != null) {
                InlineVisualArtifactCard(artifact = widget, onOpenStudio = onOpenArtifact)
            }
        }

        block.finalMessage?.let { msg ->
            AgentMessageView(item = msg, onQuote = onQuote, showFooterActions = false, onOpenArtifact = onOpenArtifact)
        }

        block.trailingNotices.forEach { notice ->
            TimelineItemView(
                item = notice,
                onOpenConversation = onOpenConversation,
                onCancelDelegation = onCancelDelegation,
            )
        }

        if (block.editedFiles.isNotEmpty() && !block.inProgress) {
            EditedFilesSummaryCard(
                files = block.editedFiles,
                cwd = cwd,
                onUndo = editableUser?.let { u -> (onRewindFiles ?: onUndoTurn)?.let { cb -> ({ cb(u) }) } },
                onViewAll = { onInspectDiffs(block.editedFiles.flatMap { it.diffs }) },
                onViewFile = { file -> onInspectDiffs(file.diffs) },
            )
        }

        val answerText = block.finalMessage?.takeIf { !it.streaming && it.text.isNotBlank() }?.text
        val hasFooterTrigger = answerText != null || block.editedFiles.isNotEmpty() || block.trailingNotices.isNotEmpty()
        if (!block.inProgress && hasFooterTrigger) {
            TurnFooterActionBar(
                copyText = answerText,
                onQuote = if (answerText != null && onQuote != null) ({
                    val snippet = answerText.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: answerText.take(120)
                    onQuote("> $snippet\n\n")
                }) else null,
                onEditResend = editableUser?.let { u -> onEditResend?.let { cb -> ({ cb(u) }) } },
                onRegenerate = editableUser?.takeIf { it.text.isNotBlank() }?.let { u -> onRegenerate?.let { cb -> ({ cb(u) }) } },
                onViewDiffs = if (block.editedFiles.isNotEmpty()) ({ onInspectDiffs(block.editedFiles.flatMap { it.diffs }) }) else null,
                onUndo = editableUser?.let { u -> onUndoTurn?.let { cb -> ({ cb(u) }) } },
                onRewindFiles = if (block.editedFiles.isNotEmpty()) editableUser?.let { u -> onRewindFiles?.let { cb -> ({ cb(u) }) } } else null,
                onFork = onFork,
            )
        }
    }
}

private sealed interface TraceSegment {
    data class Single(val item: TimelineItem) : TraceSegment
    data class ActivityCluster(val id: String, val items: List<TimelineItem>) : TraceSegment
}

private fun buildTraceSegments(items: List<TimelineItem>): List<TraceSegment> {
    val out = mutableListOf<TraceSegment>()
    var buffer = mutableListOf<TimelineItem>()
    fun flush() {
        if (buffer.isEmpty()) return
        out += TraceSegment.ActivityCluster("cluster-${buffer.first().id}", buffer.toList())
        buffer = mutableListOf()
    }
    for (it in items) {
        when {
            it is TimelineItem.ToolCall && it.name in VISUAL_TOOL_NAMES -> {
                flush()
                out += TraceSegment.Single(it)
            }
            it is TimelineItem.CommandRun || it is TimelineItem.FileEdit || it is TimelineItem.ToolCall -> buffer += it
            else -> {
                flush()
                out += TraceSegment.Single(it)
            }
        }
    }
    flush()
    return out
}

/** 独立叶节点计时标题，避免每秒计时刷新触发整个执行轨迹与子项重组。 */
@Composable
private fun LiveTraceHeaderText(
    inProgress: Boolean,
    activeTurnStartedAt: Long?,
    liveAction: String?,
    staticLabel: String,
    modifier: Modifier = Modifier,
) {
    var liveElapsedMs by remember(inProgress, activeTurnStartedAt) {
        mutableLongStateOf(activeTurnStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L)
    }
    LaunchedEffect(inProgress, activeTurnStartedAt) {
        if (!inProgress || activeTurnStartedAt == null) return@LaunchedEffect
        while (true) {
            liveElapsedMs = (System.currentTimeMillis() - activeTurnStartedAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val text = if (inProgress) "${liveAction ?: "正在执行"} · ${formatTurnDuration(liveElapsedMs)}" else staticLabel
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = G.inkMuted,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Codex 风格可折叠执行轨迹：完成后默认折叠为「用时 X分Y秒 >」，尊重用户手动展开/折叠意图且每秒计时零父级重组。 */
@Composable
private fun CodexTurnTrace(
    block: TurnBlock,
    activeTurnStartedAt: Long?,
    expandByDefault: Boolean,
    cwd: String?,
    focusText: String? = null,
    onOpenConversation: (ConversationKey) -> Unit,
    onCancelDelegation: () -> Unit,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onInspectSubagent: ((String?) -> Unit)? = null,
) {
    val autoKeepOpen = block.finalMessage == null && !block.inProgress
    var userOverride by rememberSaveable(block.id) { mutableStateOf<Boolean?>(null) }
    val defaultOpen = block.inProgress || expandByDefault || autoKeepOpen
    val open = userOverride ?: defaultOpen

    val cmdCount = block.traceItems.count { it is TimelineItem.CommandRun }
    val editCount = block.traceItems.count { it is TimelineItem.FileEdit }
    val toolCount = block.traceItems.count { it is TimelineItem.ToolCall }
    val liveAction = remember(block.traceItems, block.inProgress, focusText) {
        if (!block.inProgress) null
        else {
            val cleanFocus = focusText?.trim()?.takeIf { it.isNotEmpty() }?.take(34)
            when (val latest = block.traceItems.lastOrNull()) {
                is TimelineItem.CommandRun -> if (latest.status == ItemStatus.RUNNING) {
                    cleanFocus ?: when (classifyCommandRun(latest)) {
                        ToolCallKind.READ_FILE -> "正在读取文件"
                        ToolCallKind.LIST_DIR -> "正在浏览目录"
                        ToolCallKind.SEARCH_CODE -> "正在搜索代码"
                        ToolCallKind.FIND_FILES -> "正在查找文件"
                        else -> "正在运行命令"
                    }
                } else cleanFocus ?: "正在执行"
                is TimelineItem.FileEdit -> if (latest.status == ItemStatus.RUNNING) cleanFocus ?: "正在编辑文件" else cleanFocus ?: "正在执行"
                is TimelineItem.ToolCall -> if (latest.status == ItemStatus.RUNNING) {
                    cleanFocus ?: "正在${describeToolCall(latest.name, latest.input, latest.kind).badge}"
                } else cleanFocus ?: "正在执行"
                is TimelineItem.Reasoning -> if (latest.streaming) {
                    if (cleanFocus != null) cleanFocus
                    else {
                        val hint = reasoningPreview(latest.summaryText, true).title?.take(16)
                        if (!hint.isNullOrBlank()) "正在思考 · $hint" else "正在思考"
                    }
                } else cleanFocus ?: "正在执行"
                else -> cleanFocus ?: "正在执行"
            }
        }
    }
    val durationMs = block.durationMs
    val hasDurationHeader = !block.inProgress && durationMs != null && durationMs >= 1000L
    val staticHeaderLabel = when {
        durationMs != null && durationMs >= 1000L -> "用时 ${formatTurnDuration(durationMs)}"
        else -> {
            val parts = buildList {
                if (editCount > 0) add("编辑了文件")
                if (cmdCount > 0) add("运行了命令")
                if (toolCount > 0) add("调用了工具")
            }
            if (parts.isNotEmpty()) parts.joinToString(" · ") else "执行过程"
        }
    }

    val segments = remember(block.traceItems) { buildTraceSegments(block.traceItems) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(D.shape(8.dp))
                .clickable(enabled = block.traceItems.isNotEmpty()) { userOverride = !open }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (block.inProgress) {
                TypingWaveDots(G.accent, dotSize = 4.5.dp)
            } else {
                Icon(
                    if (hasDurationHeader) GaliaisIcons.Clock else GaliaisIcons.Sparkles,
                    contentDescription = null,
                    tint = G.inkSubtle,
                    modifier = Modifier.size(13.5.dp),
                )
            }
            LiveTraceHeaderText(
                inProgress = block.inProgress,
                activeTurnStartedAt = activeTurnStartedAt,
                liveAction = liveAction,
                staticLabel = staticHeaderLabel,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (block.traceItems.isNotEmpty()) {
                AnimatedChevron(expanded = open, tint = G.inkSubtle, size = 14.dp)
            }
        }

        AnimatedExpandable(expanded = open && segments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                segments.forEach { seg ->
                    key(when (seg) {
                        is TraceSegment.Single -> seg.item.id
                        is TraceSegment.ActivityCluster -> seg.id
                    }) {
                        when (seg) {
                            is TraceSegment.Single -> when (val item = seg.item) {
                                is TimelineItem.AgentMessage -> if (item.text.isNotBlank()) {
                                    Markdown(item.text, sourceItemId = item.id, streaming = item.streaming, onOpenArtifact = onOpenArtifact)
                                }
                                is TimelineItem.Reasoning -> ThinkingBlock(item, initiallyOpen = expandByDefault)
                                else -> TimelineItemView(
                                    item = item,
                                    onOpenConversation = onOpenConversation,
                                    onCancelDelegation = onCancelDelegation,
                                    expandThinking = expandByDefault,
                                    onOpenArtifact = onOpenArtifact,
                                    onInspectSubagent = onInspectSubagent,
                                )
                            }
                            is TraceSegment.ActivityCluster -> FramelessActivityCluster(
                                cluster = seg,
                                cwd = cwd,
                                onOpenArtifact = onOpenArtifact,
                                onInspectSubagent = onInspectSubagent,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun compactClusterItemHint(item: TimelineItem, cwd: String?): String = when (item) {
    is TimelineItem.FileEdit -> item.changes.firstOrNull()?.path?.let { stripCwdPrefix(it, cwd).substringAfterLast('/') }
        ?.takeIf { it.isNotBlank() } ?: "文件"
    is TimelineItem.CommandRun -> item.actionSummary?.takeIf { it.isNotBlank() }
        ?: item.command.replace(Regex("\\s+"), " ").trim().take(36)
    is TimelineItem.ToolCall -> stripCwdPrefix(describeToolCall(item.name, item.input, item.kind).summary, cwd).take(36)
    else -> ""
}

/** 轻量无框活动聚合行（如 `[📄] 读取了 3 个文件 ⌄` / `[>_] 运行了命令 ⌄`）：默认始终折叠，点击后展开逐项明细。 */
@Composable
private fun FramelessActivityCluster(
    cluster: TraceSegment.ActivityCluster,
    cwd: String?,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onInspectSubagent: ((String?) -> Unit)? = null,
) {
    // 始终默认折叠（尤其是读取/搜索/目录浏览等高频只读工具），仅响应用户主动点击展开，不再因运行中自动撑开刷屏
    var open by rememberSaveable(cluster.id) { mutableStateOf(false) }
    val kinds = remember(cluster.items) {
        cluster.items.map { item ->
            when (item) {
                is TimelineItem.FileEdit -> ToolCallKind.EDIT_FILE
                is TimelineItem.CommandRun -> classifyCommandRun(item)
                is TimelineItem.ToolCall -> describeToolCall(item.name, item.input, item.kind).kind
                else -> ToolCallKind.GENERIC
            }
        }.distinct()
    }
    val runningItem = cluster.items.lastOrNull {
        (it as? TimelineItem.CommandRun)?.status == ItemStatus.RUNNING ||
            (it as? TimelineItem.FileEdit)?.status == ItemStatus.RUNNING ||
            (it as? TimelineItem.ToolCall)?.status == ItemStatus.RUNNING
    }
    val anyRunning = runningItem != null
    val count = cluster.items.size
    val summaryText = remember(cluster.items, kinds, runningItem, cwd) {
        if (runningItem != null) {
            val activeLabel = when (runningItem) {
                is TimelineItem.FileEdit -> "正在编辑"
                is TimelineItem.CommandRun -> when (classifyCommandRun(runningItem)) {
                    ToolCallKind.READ_FILE -> "正在读取"
                    ToolCallKind.LIST_DIR -> "正在浏览目录"
                    ToolCallKind.SEARCH_CODE -> "正在搜索"
                    ToolCallKind.FIND_FILES -> "正在查找文件"
                    else -> "正在运行命令"
                }
                is TimelineItem.ToolCall -> "正在${describeToolCall(runningItem.name, runningItem.input, runningItem.kind).badge}"
                else -> "正在执行"
            }
            val hint = compactClusterItemHint(runningItem, cwd)
            val base = if (hint.isNotBlank() && !activeLabel.contains(hint)) "$activeLabel · $hint" else activeLabel
            if (count > 1) "$base（共 $count 项）" else base
        } else if (count == 1) {
            val only = cluster.items.first()
            val action = when (kinds.firstOrNull()) {
                ToolCallKind.EDIT_FILE -> "编辑了文件"
                ToolCallKind.DELETE_FILE -> "删除了文件"
                ToolCallKind.TERMINAL -> "运行了命令"
                ToolCallKind.READ_FILE -> "读取了文件"
                ToolCallKind.LIST_DIR -> "浏览了目录"
                ToolCallKind.SEARCH_CODE -> "搜索了代码"
                ToolCallKind.FIND_FILES -> "查找了文件"
                ToolCallKind.WEB_SEARCH, ToolCallKind.WEB_FETCH -> "检索了网页"
                ToolCallKind.MCP -> "调用了 MCP"
                ToolCallKind.SUBAGENT -> "派发了子代理"
                ToolCallKind.SKILL -> "调用了技能"
                ToolCallKind.PLAN_TODO -> "更新了任务清单"
                ToolCallKind.IMAGE -> "查看了图片"
                else -> "调用了工具"
            }
            val hint = compactClusterItemHint(only, cwd)
            if (hint.isNotBlank() && hint != action) "$action · $hint" else action
        } else {
            val base = buildList {
                if (ToolCallKind.EDIT_FILE in kinds || ToolCallKind.DELETE_FILE in kinds) add("编辑了文件")
                if (ToolCallKind.TERMINAL in kinds) add("运行了命令")
                if (ToolCallKind.READ_FILE in kinds) add("读取了文件")
                if (ToolCallKind.LIST_DIR in kinds) add("浏览了目录")
                if (ToolCallKind.SEARCH_CODE in kinds || ToolCallKind.FIND_FILES in kinds) add("搜索了代码")
                if (ToolCallKind.WEB_SEARCH in kinds || ToolCallKind.WEB_FETCH in kinds) add("检索了网页")
                if (ToolCallKind.MCP in kinds) add("调用了 MCP")
                if (ToolCallKind.SUBAGENT in kinds) add("派发了子代理")
                if (ToolCallKind.SKILL in kinds) add("调用了技能")
                if (ToolCallKind.PLAN_TODO in kinds) add("更新了任务清单")
                if (ToolCallKind.IMAGE in kinds) add("查看了图片")
                if (isEmpty() && ToolCallKind.GENERIC in kinds) add("调用了工具")
            }.take(2).joinToString(" · ").ifBlank { "执行了操作" }
            "$base（$count 项）"
        }
    }

    val primaryKind = when {
        ToolCallKind.EDIT_FILE in kinds && ToolCallKind.TERMINAL !in kinds -> ToolCallKind.EDIT_FILE
        ToolCallKind.TERMINAL in kinds -> ToolCallKind.TERMINAL
        else -> kinds.firstOrNull() ?: ToolCallKind.GENERIC
    }
    val icon = toolKindIcon(primaryKind)

    val clusterBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (open) G.surfaceSubtle.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "clusterBg",
    )
    val clusterBorder by androidx.compose.animation.animateColorAsState(
        targetValue = if (open) G.border.copy(alpha = 0.8f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "clusterBorder",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = D.shape(11.dp),
        color = clusterBg,
        border = BorderStroke(0.75.dp, clusterBorder),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(D.shape(10.dp))
                    .clickable { open = !open }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (anyRunning) {
                    PulsingDot(G.warning, size = 6.dp)
                }
                Icon(icon, contentDescription = null, tint = if (anyRunning) G.warning else G.inkMuted, modifier = Modifier.size(14.dp))
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (open) G.ink else G.inkMuted,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AnimatedChevron(expanded = open, tint = G.inkSubtle, size = 13.5.dp)
            }

            AnimatedExpandable(expanded = open) {
                Column(Modifier.fillMaxWidth()) {
                    Hairline()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        cluster.items.forEach { item ->
                            key(item.id) {
                                when (item) {
                                    is TimelineItem.CommandRun -> CompactCommandRow(item, cwd)
                                    is TimelineItem.FileEdit -> CompactFileEditRow(item, cwd)
                                    is TimelineItem.ToolCall -> CompactToolRow(item, cwd, onOpenArtifact, onInspectSubagent)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCommandRow(item: TimelineItem.CommandRun, sessionCwd: String?) {
    var showOutput by rememberSaveable(item.id) { mutableStateOf(false) }
    var expandFullCommand by remember(item.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copiedKind by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedKind) { if (copiedKind != null) { delay(1200); copiedKind = null } }

    val cmdLines = remember(item.command) { item.command.lines() }
    val isLongCommand = remember(item.command) { cmdLines.size > 2 || item.command.length > 140 }
    val oneLinePreview = remember(item.command, item.actionSummary) {
        item.actionSummary?.takeIf { it.isNotBlank() }
            ?: item.command.replace(Regex("\\s+"), " ").trim().ifBlank { "执行命令" }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(D.shape(7.dp))
                .background(if (showOutput) G.surface.copy(alpha = 0.8f) else Color.Transparent)
                .clickable { showOutput = !showOutput }
                .padding(vertical = 5.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val failed = item.status == ItemStatus.FAILED || (item.exitCode != null && item.exitCode != 0)
            if (item.status == ItemStatus.RUNNING) PulsingDot(G.warning, size = 6.dp)
            Icon(
                commandRunIcon(item),
                contentDescription = null,
                tint = if (failed) G.danger else if (item.status == ItemStatus.RUNNING) G.warning else G.inkSubtle,
                modifier = Modifier.size(13.5.dp),
            )
            Text(
                text = oneLinePreview,
                fontFamily = Mono,
                fontSize = 11.5.sp,
                color = if (showOutput) G.ink else G.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.durationMs?.takeIf { it >= 200L }?.let { ms ->
                Text(formatCompactDuration(ms), fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
            }
            item.exitCode?.let { code ->
                if (code != 0) {
                    TagPill("exit $code", fg = G.danger, bg = G.dangerTint)
                }
            }
            AnimatedChevron(expanded = showOutput, tint = G.inkSubtle, size = 12.5.dp)
        }
        AnimatedExpandable(expanded = showOutput) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                shape = D.codeShape,
                color = G.codeBg,
                border = BorderStroke(0.75.dp, G.codeBorder),
            ) {
                Column {
                    // 命令预览区：默认有界最多 2 行省略，长命令仅在用户主动点「展开命令」后在有界滚动框内查看，彻底防止刷屏。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(G.codeHeader)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("$", fontFamily = Mono, fontSize = 11.sp, color = G.codex, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .let { m -> if (expandFullCommand) m.heightIn(max = 132.dp).verticalScroll(rememberScrollState()) else m },
                        ) {
                            Text(
                                text = item.command,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                lineHeight = 15.5.sp,
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
                                modifier = Modifier
                                    .clip(D.shape(4.dp))
                                    .clickable { expandFullCommand = !expandFullCommand }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.clip(D.shape(4.dp)).clickable {
                                scope.launch {
                                    clipboard.setClipEntry(ClipData.newPlainText("cmd", item.command).toClipEntry())
                                    copiedKind = "cmd"
                                }
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(if (copiedKind == "cmd") GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copiedKind == "cmd") G.success else G.codeMuted, modifier = Modifier.size(11.dp))
                            Text(if (copiedKind == "cmd") "已复制" else "复制", fontSize = 10.5.sp, color = if (copiedKind == "cmd") G.success else G.codeMuted)
                        }
                    }
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val showMetaRow = (!item.cwd.isNullOrBlank() && item.cwd != sessionCwd) || item.output.isNotBlank() || item.exitCode != null
                        if (showMetaRow) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val cwdHint = item.cwd?.takeIf { it.isNotBlank() && it != sessionCwd }?.let { stripCwdPrefix(it, sessionCwd) }
                                if (cwdHint != null) {
                                    Text(cwdHint, fontFamily = Mono, fontSize = 10.sp, color = G.codeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                item.exitCode?.let { code ->
                                    Text(
                                        "exit $code",
                                        fontFamily = Mono,
                                        fontSize = 10.sp,
                                        color = if (code == 0) G.success else G.danger,
                                    )
                                }
                                if (item.output.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.clip(D.shape(4.dp)).clickable {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipData.newPlainText("output", item.output).toClipEntry())
                                                copiedKind = "out"
                                            }
                                        }.padding(horizontal = 5.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(if (copiedKind == "out") GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = if (copiedKind == "out") G.success else G.codeMuted, modifier = Modifier.size(11.dp))
                                        Text(if (copiedKind == "out") "已复制" else "复制输出", fontSize = 10.5.sp, color = if (copiedKind == "out") G.success else G.codeMuted)
                                    }
                                }
                            }
                        }
                        if (item.output.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    item.output.takeLast(12_000),
                                    fontFamily = Mono,
                                    fontSize = D.codeSize(11.5f),
                                    lineHeight = D.codeSize(16f),
                                    color = G.codeMuted,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                                )
                            }
                        } else if (item.status == ItemStatus.RUNNING) {
                            Text("等待输出…", fontFamily = Mono, fontSize = 11.sp, color = G.codeMuted)
                        } else {
                            Text("（无标准输出）", fontFamily = Mono, fontSize = 10.5.sp, color = G.codeMuted.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactFileEditRow(item: TimelineItem.FileEdit, cwd: String?) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val names = remember(item.changes, cwd) {
        item.changes.joinToString("、") { stripCwdPrefix(it.path, cwd).substringAfterLast('/') }.ifBlank { "文件修改" }
    }
    val stats = remember(item.changes) {
        var a = 0; var r = 0
        for (c in item.changes) { val (ca, cr) = diffStats(c.diff); a += ca; r += cr }
        a to r
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(D.shape(7.dp))
                .background(if (open) G.surface.copy(alpha = 0.85f) else Color.Transparent)
                .clickable { open = !open }
                .padding(vertical = 5.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val rowIcon = if (item.changes.size == 1) fileDiffIcon(item.changes.first().kind) else GaliaisIcons.FilePen
            if (item.status == ItemStatus.RUNNING) PulsingDot(G.warning, size = 6.dp)
            Icon(
                rowIcon,
                contentDescription = null,
                tint = if (item.status == ItemStatus.FAILED) G.danger else if (item.status == ItemStatus.RUNNING) G.warning else G.accent,
                modifier = Modifier.size(13.5.dp),
            )
            Text(names, fontFamily = Mono, fontSize = 11.5.sp, color = if (open) G.ink else G.inkMuted, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (stats.first > 0) {
                Text("+${stats.first}", fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = G.diffAddedInk, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(G.diffAddedBg).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (stats.second > 0) {
                Text("-${stats.second}", fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = G.diffRemovedInk, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(G.diffRemovedBg).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            AnimatedChevron(expanded = open, tint = G.inkSubtle, size = 12.5.dp)
        }
        AnimatedExpandable(expanded = open) {
            Column(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.changes.forEach { DiffView(it, inlineCompact = true) }
            }
        }
    }
}

@Composable
private fun CompactToolRow(
    item: TimelineItem.ToolCall,
    cwd: String?,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
    onInspectSubagent: ((String?) -> Unit)? = null,
) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val meta = remember(item.name, item.input, item.kind) { describeToolCall(item.name, item.input, item.kind) }
    val widgetArtifact = remember(item.id, item.name, item.input) { extractArtifactsFromItems(listOf(item)).firstOrNull() }
    val summary = remember(meta.summary, cwd) { stripCwdPrefix(meta.summary, cwd) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(D.shape(7.dp))
                .background(if (open) G.surface.copy(alpha = 0.85f) else Color.Transparent)
                .clickable { open = !open }
                .padding(vertical = 5.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (item.status == ItemStatus.RUNNING) PulsingDot(G.warning, size = 6.dp)
            Icon(
                meta.icon(),
                contentDescription = null,
                tint = when (item.status) {
                    ItemStatus.FAILED -> G.danger
                    ItemStatus.RUNNING -> G.warning
                    else -> G.inkSubtle
                },
                modifier = Modifier.size(13.5.dp),
            )
            Text(meta.badge, style = MaterialTheme.typography.labelSmall, color = G.inkSubtle, fontWeight = FontWeight.Medium)
            Text(summary, fontFamily = Mono, fontSize = 11.5.sp, color = if (open) G.ink else G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (widgetArtifact != null && onOpenArtifact != null) {
                TagPill("预览", fg = G.accent, bg = G.accentContainer, onClick = { onOpenArtifact(widgetArtifact) })
            }
            if (meta.kind == ToolCallKind.SUBAGENT && onInspectSubagent != null) {
                val hintId = remember(item.result, item.id) {
                    Regex("""agentId["']?\s*[:=]\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(item.result.orEmpty())?.groupValues?.getOrNull(1)
                        ?: item.id.takeIf { it.isNotBlank() }
                }
                TagPill("查看记录", fg = G.claude, bg = G.claudeTint, onClick = { onInspectSubagent(hintId) })
            }
            if (item.status == ItemStatus.FAILED) {
                Icon(GaliaisIcons.AlertTriangle, contentDescription = null, tint = G.danger, modifier = Modifier.size(12.dp))
            }
            AnimatedChevron(expanded = open, tint = G.inkSubtle, size = 12.5.dp)
        }
        AnimatedExpandable(expanded = open) {
            Column(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.input.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                        CodeBlock(item.input.take(4000), "json")
                    }
                }
                item.result?.takeIf { it.isNotBlank() }?.let {
                    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        CodeBlock(it.take(4000), "txt")
                    }
                }
            }
        }
    }
}

/** 图 1 风格的轮末「已编辑 N 个文件 +A -B」汇总卡，内置撤销与查看变更直达入口；长路径优先保留完整文件名。 */
@Composable
fun EditedFilesSummaryCard(
    files: List<FileChangeSummary>,
    cwd: String? = null,
    onUndo: (() -> Unit)?,
    onViewAll: () -> Unit,
    onViewFile: (FileChangeSummary) -> Unit,
) {
    var expanded by remember(files.size) { mutableStateOf(false) }
    val totalAdded = remember(files) { files.sumOf { it.added } }
    val totalRemoved = remember(files) { files.sumOf { it.removed } }
    val primaryFiles = remember(files) { files.take(3) }
    val overflowFiles = remember(files) { if (files.size > 3) files.drop(3) else emptyList() }

    CrispCard(padding = PaddingValues(0.dp)) {
        // 顶栏：± 图标盒 + 已编辑 N 个文件 +A -B + 撤销 + 查看变更
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(G.surfaceSubtle)
                    .border(0.75.dp, G.border, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.FileDiff, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "已编辑 ${files.size} 个文件",
                    style = MaterialTheme.typography.titleSmall,
                    color = G.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+$totalAdded", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.success)
                    Text("-$totalRemoved", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.danger)
                }
            }
            if (onUndo != null) {
                Row(
                    modifier = Modifier
                        .clip(D.shape(8.dp))
                        .clickable(onClick = onUndo)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(GaliaisIcons.Undo, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(13.5.dp))
                    Text("撤销", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, fontWeight = FontWeight.Medium)
                }
            }
            TagPill("查看变更", fg = G.ink, bg = G.surfaceSubtle, border = G.borderStrong, onClick = onViewAll)
        }

        Hairline()

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            primaryFiles.forEach { file ->
                EditedFileSummaryRow(file = file, cwd = cwd, onViewFile = onViewFile)
            }
            if (overflowFiles.isNotEmpty()) {
                AnimatedExpandable(expanded = expanded) {
                    overflowFiles.forEach { file ->
                        EditedFileSummaryRow(file = file, cwd = cwd, onViewFile = onViewFile)
                    }
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(D.shape(8.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        if (expanded) "收起文件列表" else "再显示 ${overflowFiles.size} 个文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = G.inkMuted,
                    )
                    AnimatedChevron(expanded = expanded, tint = G.inkMuted, size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun EditedFileSummaryRow(
    file: FileChangeSummary,
    cwd: String?,
    onViewFile: (FileChangeSummary) -> Unit,
) {
    val rel = stripCwdPrefix(file.path, cwd).trimStart('/')
    val slash = rel.lastIndexOf('/')
    val dir = if (slash >= 0) compactDirPrefix(rel.substring(0, slash + 1)) else ""
    val name = if (slash >= 0) rel.substring(slash + 1) else rel
    val primaryDiffKind = file.diffs.firstOrNull()?.kind ?: "update"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onViewFile(file) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            fileDiffIcon(primaryDiffKind),
            contentDescription = null,
            tint = when (primaryDiffKind) {
                "add" -> G.success
                "delete" -> G.danger
                else -> G.inkSubtle
            },
            modifier = Modifier.size(14.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dir.isNotEmpty()) {
                Text(
                    text = dir,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = G.inkSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = name,
                fontFamily = Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = G.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("+${file.added}", fontFamily = Mono, fontSize = 11.5.sp, color = G.success)
        Text("-${file.removed}", fontFamily = Mono, fontSize = 11.5.sp, color = G.danger)
    }
}

/** 图 1 底部风格的轻量单轮操作栏（位于文件变更卡下方：复制 / 引用 / 编辑重发 / 重新生成 / 更多浮窗）。 */
@Composable
private fun TurnFooterActionBar(
    copyText: String?,
    onQuote: (() -> Unit)?,
    onEditResend: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onViewDiffs: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    onRewindFiles: (() -> Unit)?,
    onFork: (() -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    val hasMore = onViewDiffs != null || onUndo != null || onRewindFiles != null || onFork != null || onEditResend != null || onRegenerate != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (copyText != null) {
            Row(
                modifier = Modifier
                    .clip(D.shape(8.dp))
                    .semantics { contentDescription = if (copied) "已复制" else "复制回复" }
                    .clickable(role = Role.Button) {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("reply", copyText).toClipEntry())
                            copied = true
                        }
                    }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = { (fadeIn(tween(120)) togetherWith fadeOut(tween(90))).using(null) },
                    label = "turnFooterCopy",
                ) { isCopied ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            if (isCopied) GaliaisIcons.Check else GaliaisIcons.Copy,
                            contentDescription = null,
                            tint = if (isCopied) G.success else G.inkSubtle,
                            modifier = Modifier.size(15.dp),
                        )
                        if (isCopied) {
                            Text("已复制", style = MaterialTheme.typography.labelSmall, color = G.success)
                        }
                    }
                }
            }
        }

        if (onQuote != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(D.shape(8.dp))
                    .semantics { contentDescription = "引用到输入框" }
                    .clickable(role = Role.Button, onClick = onQuote),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.Quote, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
            }
        }

        if (onEditResend != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(D.shape(8.dp))
                    .semantics { contentDescription = "编辑并重发" }
                    .clickable(role = Role.Button, onClick = onEditResend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.Edit, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
            }
        }

        if (onRegenerate != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(D.shape(8.dp))
                    .semantics { contentDescription = "重新生成回复" }
                    .clickable(role = Role.Button, onClick = onRegenerate),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.Refresh, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
            }
        }

        if (hasMore) {
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(D.shape(8.dp))
                        .background(if (moreOpen) G.surfaceSubtle else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics { contentDescription = "本轮更多操作" }
                        .clickable(role = Role.Button) { moreOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GaliaisIcons.MoreHoriz, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
                }
                GaliaisAnchoredPopover(
                    expanded = moreOpen,
                    onDismissRequest = { moreOpen = false },
                    preferAbove = true,
                    alignEnd = false,
                    minWidth = 188.dp,
                    maxWidth = 236.dp,
                ) {
                    if (onEditResend != null) {
                        GaliaisMenuRow(
                            title = "编辑此轮并重发",
                            icon = GaliaisIcons.Edit,
                            onClick = { moreOpen = false; onEditResend() },
                        )
                    }
                    if (onRegenerate != null) {
                        GaliaisMenuRow(
                            title = "重新生成此轮回复",
                            icon = GaliaisIcons.Refresh,
                            onClick = { moreOpen = false; onRegenerate() },
                        )
                    }
                    if (copyText != null) {
                        GaliaisMenuRow(
                            title = "复制完整答复",
                            icon = GaliaisIcons.Copy,
                            onClick = {
                                moreOpen = false
                                scope.launch {
                                    clipboard.setClipEntry(ClipData.newPlainText("reply", copyText).toClipEntry())
                                    copied = true
                                }
                            },
                        )
                    }
                    if (onQuote != null) {
                        GaliaisMenuRow(
                            title = "引用到输入框",
                            icon = GaliaisIcons.Quote,
                            onClick = { moreOpen = false; onQuote() },
                        )
                    }
                    if (onViewDiffs != null) {
                        GaliaisMenuRow(
                            title = "查看本轮文件变更",
                            icon = GaliaisIcons.FileDiff,
                            onClick = { moreOpen = false; onViewDiffs() },
                        )
                    }
                    if (onFork != null) {
                        GaliaisMenuRow(
                            title = "创建会话分支",
                            icon = GaliaisIcons.GitBranch,
                            onClick = { moreOpen = false; onFork() },
                        )
                    }
                    if (onRewindFiles != null) {
                        GaliaisMenuRow(
                            title = "撤销本轮文件修改",
                            icon = GaliaisIcons.Undo,
                            tintOverride = G.warning,
                            onClick = { moreOpen = false; onRewindFiles() },
                        )
                    }
                    if (onUndo != null) {
                        Hairline(Modifier.padding(vertical = 2.dp))
                        GaliaisMenuRow(
                            title = "回退到此轮前",
                            icon = GaliaisIcons.Undo,
                            tintOverride = G.warning,
                            onClick = { moreOpen = false; onUndo() },
                        )
                    }
                }
            }
        }
        val pluginFooter = LocalPluginMessageFooter.current
        if (!copyText.isNullOrBlank() && pluginFooter != null) {
            pluginFooter.first.take(2).forEach { slot ->
                Row(
                    modifier = Modifier
                        .clip(D.shape(8.dp))
                        .clickable(role = Role.Button) {
                            pluginFooter.second(slot, TimelineItem.AgentMessage("turn-footer", copyText))
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(13.dp))
                    Text(slot.label.ifBlank { slot.id }, style = MaterialTheme.typography.labelSmall, color = G.accent, maxLines = 1)
                }
            }
        }
    }
}

internal fun stripCwdPrefix(path: String, cwd: String?): String {
    if (cwd.isNullOrBlank()) return path
    val normCwd = cwd.trimEnd('/')
    return when {
        path == normCwd -> "."
        path.startsWith("$normCwd/") -> path.removePrefix("$normCwd/")
        else -> path
    }
}

private fun compactDirPrefix(dir: String): String {
    if (dir.length <= 28) return dir
    val parts = dir.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) dir else "${parts.first()}/…/${parts.last()}/"
}

private fun formatCompactDuration(ms: Long): String = when {
    ms >= 60_000L -> "${ms / 60_000L}m${(ms % 60_000L) / 1000L}s"
    ms >= 10_000L -> "${ms / 1000L}s"
    ms >= 1_000L -> "%.1fs".format(ms / 1000.0)
    else -> "${ms}ms"
}
