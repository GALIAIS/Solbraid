package com.galiais.agent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.STREAM_ANIM_BLUR_SLIDE
import com.galiais.agent.engine.STREAM_ANIM_INK_BLOOM
import com.galiais.agent.engine.STREAM_ANIM_INSTANT
import com.galiais.agent.engine.STREAM_ANIM_LUMINOUS_SWEEP
import com.galiais.agent.engine.STREAM_ANIM_SMOOTH_FADE
import com.galiais.agent.engine.STREAM_ANIM_TYPEWRITER
import com.galiais.agent.engine.STREAM_SPEED_BALANCED
import com.galiais.agent.engine.STREAM_SPEED_FAST
import com.galiais.agent.engine.STREAM_SPEED_GENTLE
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 流式输出动画风格描述，供设置页选项卡片与实时预览复用。 */
@Immutable
data class StreamAnimationOption(
    val id: String,
    val title: String,
    val badge: String,
    val subtitle: String,
)

val STREAM_ANIMATION_OPTIONS: List<StreamAnimationOption> = listOf(
    StreamAnimationOption(
        id = STREAM_ANIM_SMOOTH_FADE,
        title = "柔光渐现",
        badge = "默认",
        subtitle = "逐词柔和透明度淡入，配合微呼吸光点，阅读最自然。",
    ),
    StreamAnimationOption(
        id = STREAM_ANIM_TYPEWRITER,
        title = "极客打字机",
        badge = "韵律",
        subtitle = "利落逐字敲击与标点微顿，搭配终端风格竖块光标。",
    ),
    StreamAnimationOption(
        id = STREAM_ANIM_LUMINOUS_SWEEP,
        title = "流光扫过",
        badge = "高光",
        subtitle = "新出文字由主题强调色高光扫过并沉降为墨色。",
    ),
    StreamAnimationOption(
        id = STREAM_ANIM_INK_BLOOM,
        title = "水墨晕染",
        badge = "温润",
        subtitle = "尾部新字伴随淡雅微晕由浅转浓洇实，耐看不刺眼。",
    ),
    StreamAnimationOption(
        id = STREAM_ANIM_BLUR_SLIDE,
        title = "轻盈浮现",
        badge = "层次",
        subtitle = "新段落轻微上浮滑入并叠加柔光渐显与波浪指示。",
    ),
    StreamAnimationOption(
        id = STREAM_ANIM_INSTANT,
        title = "极速直出",
        badge = "零延迟",
        subtitle = "跳过字符缓动与尾部特效，收到增量即刻全量上屏。",
    ),
)

/** 跨虚拟切片共享单条消息已揭示的最大全局字符偏移，确保长文切块或重组时绝不倒放重播。 */
internal object StreamRevealProgressRegistry {
    private const val MAX_ENTRIES = 128
    private val revealedOffsets = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun maxRevealed(messageId: String): Int = revealedOffsets[messageId] ?: 0

    @Synchronized
    fun record(messageId: String, globalOffset: Int) {
        if (messageId.isBlank()) return
        val prev = revealedOffsets[messageId] ?: 0
        if (globalOffset > prev) revealedOffsets[messageId] = globalOffset
    }

    @Synchronized
    fun clear(messageId: String) {
        if (messageId.isNotBlank()) revealedOffsets.remove(messageId)
    }
}

/** 校正字符切分边界，绝不截断 UTF-16 代理对（Emoji/扩展汉字）或 `\r\n` 换行对。 */
internal fun safeCharBoundary(text: String, targetIndex: Int): Int {
    val len = text.length
    if (targetIndex <= 0) return 0
    if (targetIndex >= len) return len
    var idx = targetIndex
    if (idx < len && Character.isHighSurrogate(text[idx - 1]) && Character.isLowSurrogate(text[idx])) {
        idx++
    } else if (idx < len && text[idx - 1] == '\r' && text[idx] == '\n') {
        idx++
    }
    return idx.coerceAtMost(len)
}

/**
 * 自适应 VSync 流速推进计算：
 * - 常规积压平滑匀速释放，消除网络 SSE 分包抖动；
 * - 超高速模型（大积压）自动按比例线性加速追赶，永不掉队；
 * - 流式结束时进入 sprint 冲刺收尾，~180ms 内丝滑落定不硬跳。
 */
internal fun computePacerStep(
    backlog: Int,
    dtMs: Float,
    speed: String,
    style: String,
    finishing: Boolean,
    nextChar: Char?,
): Int {
    if (backlog <= 0) return 0
    if (style == STREAM_ANIM_INSTANT) return backlog
    val clampedDt = dtMs.coerceIn(4f, 48f)
    val baseCharsPerSec = when (speed) {
        STREAM_SPEED_GENTLE -> 46f
        STREAM_SPEED_FAST -> 135f
        else -> 82f
    }
    val styleFactor = when (style) {
        STREAM_ANIM_TYPEWRITER -> 0.88f
        STREAM_ANIM_BLUR_SLIDE -> 1.12f
        STREAM_ANIM_INK_BLOOM -> 0.94f
        else -> 1f
    }
    val catchUpSec = when {
        finishing -> 0.14f
        speed == STREAM_SPEED_FAST -> 0.14f
        speed == STREAM_SPEED_GENTLE -> 0.34f
        else -> 0.22f
    }
    val targetRate = maxOf(baseCharsPerSec * styleFactor, backlog / catchUpSec)
    val rawStep = targetRate * (clampedDt / 1000f)
    val punctuationScale = if (style == STREAM_ANIM_TYPEWRITER && !finishing && backlog < 40 && nextChar != null) {
        when (nextChar) {
            '。', '！', '？', '.', '!', '?', '\n' -> 0.52f
            '，', '、', '；', '：', ',', ';', ':' -> 0.72f
            else -> 1f
        }
    } else 1f
    val step = (rawStep * punctuationScale).roundToInt().coerceAtLeast(1)
    return step.coerceAtMost(backlog)
}

@Immutable
internal data class StreamingMarkdownSplit(
    val frozenBlocks: List<String>,
    val liveTail: String,
)

/**
 * 块级增量冻结切分：
 * 将流式 Markdown 按安全顶层空行边界拆分为多个不可变已完成块（frozenBlocks）+ 单一活跃尾块（liveTail）。
 * 每个已完成块在后续流式刷新中字符串恒定不变，100% 命中 Compose `key(idx)` 跳过与 CommonMark LRU 缓存。
 */
internal fun splitStreamingMarkdownBlocks(text: String, targetFrozenBlockChars: Int = 360): StreamingMarkdownSplit {
    if (text.length < 180) return StreamingMarkdownSplit(emptyList(), text)
    val frozen = ArrayList<String>()
    var blockStart = 0
    var pos = 0
    var inFence = false
    var fenceMarker = ""
    var inMath = false
    var inArtifact = false
    val len = text.length
    while (pos < len) {
        val nl = text.indexOf('\n', pos)
        val end = if (nl < 0) len else nl
        val line = text.substring(pos, end)
        val trimmed = line.trim()
        if (!inFence) {
            when {
                trimmed.startsWith("```") -> { inFence = true; fenceMarker = "```" }
                trimmed.startsWith("~~~") -> { inFence = true; fenceMarker = "~~~" }
            }
        } else if (trimmed.startsWith(fenceMarker)) {
            inFence = false
            fenceMarker = ""
        }
        if (!inFence) {
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
        if (trimmed.isEmpty() && !inFence && !inMath && !inArtifact && nextPos < len && (nextPos - blockStart) >= targetFrozenBlockChars) {
            val peekEnd = text.indexOf('\n', nextPos).let { if (it < 0) len else it }
            val nextTrim = text.substring(nextPos, peekEnd).trim()
            // 表格后续行、缩进续行不切开，保证表格与嵌套列表完整
            if (nextTrim.isNotEmpty() && !nextTrim.startsWith("|")) {
                val slice = text.substring(blockStart, nextPos).trimEnd()
                if (slice.isNotEmpty()) frozen += slice
                blockStart = nextPos
            }
        }
        pos = nextPos
    }
    if (frozen.isEmpty()) return StreamingMarkdownSplit(emptyList(), text)
    val tail = if (blockStart < len) text.substring(blockStart) else ""
    return StreamingMarkdownSplit(frozen, tail)
}

/**
 * 流式活跃尾段安全补全：
 * 仅作用于正在逐字增长的 liveTail，自动闭合尚未传完的围栏代码块、块级数学公式及末尾行未闭合行内标记（`**` / `~~` / `` ` ``），
 * 消除流式期间裸 Markdown 符号闪现再瞬间重排的视觉跳动。
 */
internal fun repairStreamingMarkdownTail(tail: String): String {
    if (tail.isEmpty()) return tail
    var inFence = false
    var fenceMarker = ""
    var inDollarMath = false
    var inBracketMath = false
    var pos = 0
    val len = tail.length
    while (pos < len) {
        val nl = tail.indexOf('\n', pos)
        val end = if (nl < 0) len else nl
        val trimmed = tail.substring(pos, end).trim()
        if (!inFence) {
            when {
                trimmed.startsWith("```") -> { inFence = true; fenceMarker = "```" }
                trimmed.startsWith("~~~") -> { inFence = true; fenceMarker = "~~~" }
            }
        } else if (trimmed.startsWith(fenceMarker)) {
            inFence = false
            fenceMarker = ""
        }
        if (!inFence) {
            if (trimmed == "$$" || (trimmed.startsWith("$$") && !trimmed.drop(2).contains("$$"))) {
                inDollarMath = !inDollarMath
            } else if (trimmed.startsWith("\\[") && !trimmed.contains("\\]")) {
                inBracketMath = true
            } else if (inBracketMath && trimmed.contains("\\]")) {
                inBracketMath = false
            }
        }
        pos = if (nl < 0) len else nl + 1
    }
    if (inFence) {
        val needsNewline = !tail.endsWith('\n')
        return if (needsNewline) "$tail\n$fenceMarker" else "$tail$fenceMarker"
    }
    if (inDollarMath) {
        return if (tail.endsWith('\n')) "$tail$$" else "$tail\n$$"
    }
    if (inBracketMath) {
        return if (tail.endsWith('\n')) "$tail\\]" else "$tail\n\\]"
    }
    // 仅对最后一个非空行修复常见未闭合行内格式，避免影响已完成历史行
    val lastNl = tail.lastIndexOf('\n')
    val prefix = if (lastNl >= 0) tail.substring(0, lastNl + 1) else ""
    val lastLine = if (lastNl >= 0) tail.substring(lastNl + 1) else tail
    if (lastLine.isBlank()) return tail
    var fixed = lastLine
    // 1. 行内代码反引号（单反引号成对检查，忽略围栏行）
    val backticks = fixed.count { it == '`' }
    if (backticks % 2 == 1 && !fixed.trimStart().startsWith("```")) {
        if (fixed.endsWith("`")) {
            // 刚打出起始单个 `，尚未输入代码正文时不补闭合，免得出现空代码底块
            fixed = fixed.dropLast(1)
        } else {
            fixed += "`"
        }
    }
    // 若当前不在行内代码内，继续修复粗体 ** 与删除线 ~~
    val effectiveBackticks = fixed.count { it == '`' }
    if (effectiveBackticks % 2 == 0) {
        // 统计非行内代码区的 ** 与 ~~ 数量
        var inInlineCode = false
        var boldCount = 0
        var strikeCount = 0
        var i = 0
        while (i < fixed.length) {
            val ch = fixed[i]
            if (ch == '`') {
                inInlineCode = !inInlineCode
                i++
                continue
            }
            if (!inInlineCode && i + 1 < fixed.length) {
                if (ch == '*' && fixed[i + 1] == '*') {
                    boldCount++
                    i += 2
                    continue
                }
                if (ch == '~' && fixed[i + 1] == '~') {
                    strikeCount++
                    i += 2
                    continue
                }
            }
            i++
        }
        if (boldCount % 2 == 1) {
            fixed = if (fixed.endsWith("**")) fixed.dropLast(2) else "$fixed**"
        }
        if (strikeCount % 2 == 1) {
            fixed = if (fixed.endsWith("~~")) fixed.dropLast(2) else "$fixed~~"
        }
    }
    return if (fixed == lastLine) tail else prefix + fixed
}

@Immutable
internal data class PacedStreamState(
    val visibleText: String,
    val visualStreaming: Boolean,
)

/**
 * 60/120Hz VSync 驱动的流式字符缓动器：
 * - 首次加载已完成历史消息（streaming=false 且无流式进度记录）直接零开销全量直出；
 * - 流式期间按设备刷新率平滑释放积压字符，自动随模型吐字速率自适应变速；
 * - 流式刚结束若仍有少量未释放尾字，进入 ~180ms 冲刺平滑收尾，消除收尾硬跳。
 */
@Composable
internal fun rememberPacedStreamingText(
    sourceId: String,
    fullText: String,
    streaming: Boolean,
    style: String,
    speed: String,
    globalOffset: Int = 0,
): PacedStreamState {
    val latestFullText by rememberUpdatedState(fullText)
    val latestStreaming by rememberUpdatedState(streaming)
    val latestStyle by rememberUpdatedState(style)
    val latestSpeed by rememberUpdatedState(speed)

    val initialRevealed = remember(sourceId, globalOffset) {
        if (!streaming) {
            val recorded = StreamRevealProgressRegistry.maxRevealed(sourceId)
            if (recorded > 0 && recorded < globalOffset + fullText.length) {
                safeCharBoundary(fullText, (recorded - globalOffset).coerceIn(0, fullText.length))
            } else {
                fullText.length
            }
        } else {
            val recorded = StreamRevealProgressRegistry.maxRevealed(sourceId)
            if (recorded > globalOffset) {
                safeCharBoundary(fullText, (recorded - globalOffset).coerceIn(0, fullText.length))
            } else {
                0
            }
        }
    }

    var revealedLen by remember(sourceId, globalOffset) { mutableIntStateOf(initialRevealed) }
    var everStreamed by remember(sourceId, globalOffset) { mutableStateOf(streaming || initialRevealed < fullText.length) }
    if (streaming && !everStreamed) everStreamed = true

    // 当目标文本被缩短（如重写/回退）或切至极速直出时立即对齐边界
    if (revealedLen > fullText.length) {
        revealedLen = fullText.length
    }
    if (style == STREAM_ANIM_INSTANT || !everStreamed) {
        if (revealedLen != fullText.length) {
            revealedLen = fullText.length
        }
        if (sourceId.isNotBlank() && streaming) {
            StreamRevealProgressRegistry.record(sourceId, globalOffset + fullText.length)
        }
        return PacedStreamState(visibleText = fullText, visualStreaming = streaming)
    }

    val needsAdvance = revealedLen < fullText.length
    LaunchedEffect(sourceId, globalOffset, streaming, needsAdvance, style, speed) {
        if (!needsAdvance) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            val target = latestFullText
            val cur = revealedLen
            if (cur >= target.length) break
            val curStyle = latestStyle
            if (curStyle == STREAM_ANIM_INSTANT) {
                revealedLen = target.length
                StreamRevealProgressRegistry.record(sourceId, globalOffset + target.length)
                break
            }
            val dtMs = withFrameNanos { frameNanos ->
                val delta = if (lastFrameNanos == 0L) 16f else (frameNanos - lastFrameNanos) / 1_000_000f
                lastFrameNanos = frameNanos
                delta
            }
            val backlog = target.length - cur
            val nextChar = target.getOrNull(cur)
            val step = computePacerStep(
                backlog = backlog,
                dtMs = dtMs,
                speed = latestSpeed,
                style = curStyle,
                finishing = !latestStreaming,
                nextChar = nextChar,
            )
            val nextLen = safeCharBoundary(target, cur + step)
            revealedLen = nextLen
            StreamRevealProgressRegistry.record(sourceId, globalOffset + nextLen)
        }
    }

    val safeLen = safeCharBoundary(fullText, revealedLen)
    val visible = if (safeLen >= fullText.length) fullText else fullText.substring(0, safeLen)
    val visualActive = streaming || safeLen < fullText.length
    return PacedStreamState(visibleText = visible, visualStreaming = visualActive)
}

private data class RevealSpan(
    val start: Int,
    val end: Int,
    val startNanos: Long,
)

private const val STREAM_CURSOR_INLINE_ID = "solbraid_stream_cursor"

/**
 * 渲染空间尾部流式动效文本组件：
 * 直接基于已排版的 `AnnotatedString` 字符增量记录独立淡入时间轴，
 * 完整保留粗体/斜体/行内代码/数学公式/超链接样式，同时支持 6 种差异化流式动效与行内基线对齐 GPU 光标。
 */
@Composable
internal fun StreamingTailText(
    annotated: AnnotatedString,
    style: String,
    baseColor: Color,
    textStyle: TextStyle,
    streaming: Boolean,
    effectKey: Any,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val accentColor = G.accent
    val darkTheme = G.dark
    val spans = remember(effectKey, style) { ArrayDeque<RevealSpan>() }
    var prevLen by remember(effectKey, style) {
        mutableIntStateOf(if (streaming) 0 else annotated.length)
    }
    var spanActivationTick by remember(effectKey, style) { mutableIntStateOf(0) }
    var frameClockNanos by remember(effectKey, style) { mutableLongStateOf(0L) }

    val fadeDurationMs = when (style) {
        STREAM_ANIM_TYPEWRITER -> 115f
        STREAM_ANIM_SMOOTH_FADE -> 310f
        STREAM_ANIM_LUMINOUS_SWEEP -> 390f
        STREAM_ANIM_INK_BLOOM -> 440f
        STREAM_ANIM_BLUR_SLIDE -> 320f
        else -> 0f
    }
    val fadeDurationNanos = (fadeDurationMs * 1_000_000f).toLong()

    // 记录渲染后文本的实际增长区间；若文本因 Markdown 符号收敛略微变短则安全裁剪
    val curLen = annotated.length
    if (!streaming && spans.isEmpty()) {
        prevLen = curLen
    } else if (curLen < prevLen) {
        prevLen = curLen
        while (spans.isNotEmpty() && spans.last().start >= curLen) spans.removeLast()
    } else if (curLen > prevLen && fadeDurationNanos > 0L) {
        val now = System.nanoTime()
        val wasEmpty = spans.isEmpty()
        val start = prevLen
        // 同一帧（<10ms）内的相邻增量合并，保持 Span 数量极小
        val last = spans.lastOrNull()
        if (last != null && last.end == start && (now - last.startNanos) < 10_000_000L) {
            spans.removeLast()
            spans.addLast(RevealSpan(last.start, curLen, last.startNanos))
        } else {
            spans.addLast(RevealSpan(start, curLen, now))
            while (spans.size > 28) spans.removeFirst()
        }
        prevLen = curLen
        if (wasEmpty) {
            spanActivationTick++
            frameClockNanos = now
        }
    } else if (curLen > prevLen) {
        prevLen = curLen
    }

    LaunchedEffect(effectKey, style, spanActivationTick) {
        if (fadeDurationNanos <= 0L || spans.isEmpty()) return@LaunchedEffect
        while (spans.isNotEmpty()) {
            withFrameNanos { it }
            val monoNow = System.nanoTime()
            while (spans.isNotEmpty() && (monoNow - spans.first().startNanos) >= fadeDurationNanos) {
                spans.removeFirst()
            }
            frameClockNanos = monoNow
            if (spans.isEmpty()) break
        }
    }

    // 轻盈浮现：新块初次出现在活跃流式尾部时执行一次极轻量 GPU 上浮滑入
    val entryLift = remember(effectKey, style) {
        androidx.compose.animation.core.Animatable(if (streaming && style == STREAM_ANIM_BLUR_SLIDE) 1f else 0f)
    }
    LaunchedEffect(effectKey, style) {
        if (entryLift.value > 0f) {
            entryLift.animateTo(0f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    val nowNanos = if (spans.isNotEmpty()) System.nanoTime() else 0L
    // 读取 frameClockNanos 仅在仍有活跃淡入 Span 时驱动重绘；停顿时零重组！
    @Suppress("UNUSED_VARIABLE")
    val tick = frameClockNanos

    val styledText = remember(annotated, streaming, style, baseColor, accentColor, darkTheme, tick, spans.size) {
        if (!streaming && spans.isEmpty()) {
            annotated
        } else {
            buildAnnotatedString {
                append(annotated)
                if (spans.isNotEmpty() && fadeDurationNanos > 0L) {
                    val textStr = annotated.text
                    for (sp in spans) {
                        val s = safeCharBoundary(textStr, sp.start).coerceIn(0, length)
                        val e = safeCharBoundary(textStr, sp.end).coerceIn(s, length)
                        if (s >= e) continue
                        val rawT = ((nowNanos - sp.startNanos).toFloat() / fadeDurationNanos.toFloat()).coerceIn(0f, 1f)
                        val ease = FastOutSlowInEasing.transform(rawT)
                        val spanStyle = when (style) {
                            STREAM_ANIM_SMOOTH_FADE -> {
                                val alpha = 0.08f + 0.92f * ease
                                SpanStyle(color = baseColor.copy(alpha = baseColor.alpha * alpha))
                            }
                            STREAM_ANIM_TYPEWRITER -> {
                                val alpha = 0.55f + 0.45f * ease
                                val col = lerp(accentColor, baseColor, (ease * 1.4f).coerceIn(0f, 1f))
                                SpanStyle(color = col.copy(alpha = col.alpha * alpha))
                            }
                            STREAM_ANIM_LUMINOUS_SWEEP -> {
                                val col = lerp(accentColor, baseColor, ease)
                                val alpha = 0.25f + 0.75f * ease
                                val bgAlpha = (1f - ease) * (if (darkTheme) 0.20f else 0.14f)
                                SpanStyle(
                                    color = col.copy(alpha = col.alpha * alpha),
                                    background = if (bgAlpha > 0.01f) accentColor.copy(alpha = bgAlpha) else Color.Unspecified,
                                )
                            }
                            STREAM_ANIM_INK_BLOOM -> {
                                val col = lerp(accentColor.copy(alpha = 0.78f), baseColor, ease)
                                val alpha = 0.14f + 0.86f * ease
                                val bgAlpha = (1f - ease) * (1f - ease) * (if (darkTheme) 0.16f else 0.11f)
                                SpanStyle(
                                    color = col.copy(alpha = col.alpha * alpha),
                                    background = if (bgAlpha > 0.008f) accentColor.copy(alpha = bgAlpha) else Color.Unspecified,
                                )
                            }
                            STREAM_ANIM_BLUR_SLIDE -> {
                                val alpha = 0.10f + 0.90f * ease
                                SpanStyle(color = baseColor.copy(alpha = baseColor.alpha * alpha))
                            }
                            else -> null
                        }
                        if (spanStyle != null) addStyle(spanStyle, s, e)
                    }
                }
                if (streaming) {
                    appendInlineContent(STREAM_CURSOR_INLINE_ID, "\u200B")
                }
            }
        }
    }

    val inlineContent = remember(streaming, style, baseColor, accentColor) {
        if (!streaming) {
            emptyMap()
        } else {
            val widthSp = when (style) {
                STREAM_ANIM_BLUR_SLIDE -> 24.sp
                STREAM_ANIM_TYPEWRITER -> 10.sp
                STREAM_ANIM_LUMINOUS_SWEEP -> 11.sp
                STREAM_ANIM_INK_BLOOM -> 14.sp
                STREAM_ANIM_INSTANT -> 9.sp
                else -> 13.sp
            }
            val heightSp = when (style) {
                STREAM_ANIM_TYPEWRITER, STREAM_ANIM_LUMINOUS_SWEEP -> 17.sp
                else -> 14.sp
            }
            mapOf(
                STREAM_CURSOR_INLINE_ID to InlineTextContent(
                    Placeholder(
                        width = widthSp,
                        height = heightSp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    StreamingInlineCursor(style = style, inkColor = baseColor, accentColor = accentColor)
                },
            )
        }
    }

    Text(
        text = styledText,
        inlineContent = inlineContent,
        style = textStyle,
        color = baseColor,
        fontWeight = fontWeight,
        modifier = if (style == STREAM_ANIM_BLUR_SLIDE) {
            modifier.graphicsLayer {
                val p = entryLift.value
                translationY = p * 5.dp.toPx()
                alpha = 1f - p * 0.25f
            }
        } else modifier,
    )
}

/** 纯 GPU 绘制阶段驱动的行内流式光标，停顿闪烁期间触发 0 次 Compose 重组。 */
@Composable
internal fun StreamingInlineCursor(
    style: String,
    inkColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "streamCursor_$style")
    when (style) {
        STREAM_ANIM_TYPEWRITER -> {
            val blink = transition.animateFloat(
                initialValue = 1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        1f at 0
                        1f at 420
                        0.12f at 500
                        0.12f at 820
                        1f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "typewriterCaret",
            )
            Canvas(modifier.fillMaxSize()) {
                val barW = (size.width * 0.44f).coerceIn(2.2.dp.toPx(), 5.dp.toPx())
                val barH = (size.height * 0.88f).coerceAtLeast(10.dp.toPx())
                val x = (size.width - barW) * 0.45f
                val y = (size.height - barH) * 0.5f
                drawRoundRect(
                    color = accentColor.copy(alpha = blink.value),
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(1.2.dp.toPx()),
                )
            }
        }
        STREAM_ANIM_LUMINOUS_SWEEP -> {
            val phase = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(920, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "luminousNeedle",
            )
            Canvas(modifier.fillMaxSize()) {
                val p = phase.value
                val cx = size.width * 0.52f
                val h = size.height * 0.92f
                val topY = (size.height - h) * 0.5f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.40f * (0.6f + 0.4f * p)), Color.Transparent),
                        center = Offset(cx, size.height * 0.5f),
                        radius = size.minDimension * 0.65f,
                    ),
                    radius = size.minDimension * 0.65f,
                    center = Offset(cx, size.height * 0.5f),
                )
                val needleW = 2.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.25f),
                            accentColor.copy(alpha = 0.85f + 0.15f * p),
                            accentColor.copy(alpha = 0.25f),
                        ),
                        startY = topY,
                        endY = topY + h,
                    ),
                    topLeft = Offset(cx - needleW / 2f, topY),
                    size = Size(needleW, h),
                    cornerRadius = CornerRadius(needleW / 2f),
                )
            }
        }
        STREAM_ANIM_INK_BLOOM -> {
            val pulse = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "inkBloomDot",
            )
            Canvas(modifier.fillMaxSize()) {
                val p = pulse.value
                val center = Offset(size.width * 0.55f, size.height * 0.54f)
                val coreR = (2.3f + 0.55f * p).dp.toPx()
                val haloR = (4.2f + 1.8f * p).dp.toPx()
                drawCircle(
                    color = accentColor.copy(alpha = 0.22f * (1f - p * 0.45f)),
                    radius = haloR,
                    center = center,
                )
                drawCircle(
                    color = lerp(inkColor, accentColor, 0.58f).copy(alpha = 0.72f + 0.28f * p),
                    radius = coreR,
                    center = center,
                )
            }
        }
        STREAM_ANIM_BLUR_SLIDE -> {
            val wave = transition.animateFloat(
                initialValue = 0f,
                targetValue = (2.0 * PI).toFloat(),
                animationSpec = infiniteRepeatable(tween(960, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
                label = "slideWave",
            )
            Canvas(modifier.fillMaxSize()) {
                val r = 1.85.dp.toPx()
                val gap = 5.4.dp.toPx()
                val startX = (size.width - gap * 2f) * 0.5f + 1.dp.toPx()
                val baseY = size.height * 0.58f
                val amp = 2.1.dp.toPx()
                for (i in 0 until 3) {
                    val phase = wave.value - i * 0.85f
                    val dy = -sin(phase).coerceAtLeast(0f) * amp
                    val alpha = 0.38f + 0.62f * ((cos(phase - PI.toFloat() / 2f) + 1f) * 0.5f)
                    drawCircle(
                        color = accentColor.copy(alpha = alpha),
                        radius = r,
                        center = Offset(startX + i * gap, baseY + dy),
                    )
                }
            }
        }
        STREAM_ANIM_INSTANT -> {
            val pulse = transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse),
                label = "instantDot",
            )
            Canvas(modifier.fillMaxSize()) {
                drawCircle(
                    color = accentColor.copy(alpha = pulse.value),
                    radius = 2.1.dp.toPx(),
                    center = Offset(size.width * 0.58f, size.height * 0.55f),
                )
            }
        }
        else -> {
            // smooth_fade：柔光呼吸圆点
            val breath = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1020, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "smoothOrb",
            )
            Canvas(modifier.fillMaxSize()) {
                val p = breath.value
                val center = Offset(size.width * 0.55f, size.height * 0.54f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.34f * (0.55f + 0.45f * p)), Color.Transparent),
                        center = center,
                        radius = 5.6.dp.toPx(),
                    ),
                    radius = 5.6.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.72f + 0.28f * p),
                    radius = (2.5f + 0.45f * p).dp.toPx(),
                    center = center,
                )
            }
        }
    }
}

private val STREAM_PREVIEW_CHUNKS = listOf(
    "正在分析 ",
    "**流式输出链路**，",
    "已完成三项关键优化：\n\n",
    "- **首字直通**：取消新消息首个增量的人为等待，",
    "同时将合帧降至 `24ms`\n",
    "- **块级冻结**：历史段落命中 `LRU` 缓存，",
    "仅重排版活跃尾部\n",
    "- **VSync 缓动**：按屏幕刷新率平滑释放分包，",
    "消除卡顿与跳行。",
)

/** 常规设置页内嵌实时流式动效沙盒预览卡：模拟真实 SSE 分包到达，支持切换动效/流速即刻重播对比。 */
@Composable
fun StreamingAnimationPreviewCard(
    style: String,
    speed: String,
    modifier: Modifier = Modifier,
) {
    var replayEpoch by remember { mutableIntStateOf(0) }
    var arrivedText by remember(style, speed, replayEpoch) { mutableStateOf("") }
    var isStreaming by remember(style, speed, replayEpoch) { mutableStateOf(true) }
    val previewSourceId = remember(style, speed, replayEpoch) { "preview-$style-$speed-$replayEpoch" }

    LaunchedEffect(style, speed, replayEpoch) {
        StreamRevealProgressRegistry.clear(previewSourceId)
        val sb = StringBuilder()
        while (true) {
            sb.setLength(0)
            arrivedText = ""
            isStreaming = true
            StreamRevealProgressRegistry.clear(previewSourceId)
            delay(180L)
            for ((idx, chunk) in STREAM_PREVIEW_CHUNKS.withIndex()) {
                sb.append(chunk)
                arrivedText = sb.toString()
                val burstDelay = when {
                    style == STREAM_ANIM_INSTANT -> 165L
                    idx % 3 == 0 -> 150L
                    else -> 95L
                }
                delay(burstDelay)
            }
            isStreaming = false
            delay(2200L)
            // 循环重播时更换内部 sourceId 清理已揭示记录，确保完整展示下一轮动效
            replayEpoch++
            break
        }
    }

    val paced = rememberPacedStreamingText(
        sourceId = previewSourceId,
        fullText = arrivedText,
        streaming = isStreaming,
        style = style,
        speed = speed,
    )
    val option = remember(style) {
        STREAM_ANIMATION_OPTIONS.firstOrNull { it.id == style } ?: STREAM_ANIMATION_OPTIONS.first()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = G.surfaceSubtle.copy(alpha = if (G.dark) 0.56f else 0.72f),
        border = BorderStroke(0.75.dp, G.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(13.dp))
                Text(
                    "实时预览",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = G.ink,
                )
                TagPill(option.title, fg = G.accent, bg = G.accentContainer)
                Box(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(D.shape(6.dp))
                        .background(G.surface)
                        .clickable { replayEpoch++ }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(GaliaisIcons.Refresh, contentDescription = "重播预览", tint = G.inkMuted, modifier = Modifier.size(11.dp))
                    Text("重播", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp),
            ) {
                if (paced.visibleText.isNotEmpty()) {
                    Markdown(
                        text = paced.visibleText,
                        compact = true,
                        sourceItemId = previewSourceId,
                        streaming = paced.visualStreaming,
                        streamStyleOverride = style,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TypingWaveDots(G.accent, dotSize = 4.5.dp)
                        Text("正在生成…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                }
            }
        }
    }
}
