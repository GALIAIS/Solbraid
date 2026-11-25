package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.SettingsState
import kotlinx.coroutines.delay

private object ComposerPatterns {
    val fencedBlock = Regex("""```[^\n]*\n[\s\S]*?```""")
    val inlineCode = Regex("""`[^`\n]+`""")
    val displayMath = Regex("""\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]""")
    val inlineMath = Regex("""\\\([^)\n]+?\\\)|(?<![\\$])\$(?!\s)(?!\d+(?:[.,]\d+)?(?:\s|$|[.,;?!]))[^$\n]+?(?<!\s)\$(?!\d)""")
    val heading = Regex("""(?m)^(#{1,6})(\s+.+)$""")
    val blockquote = Regex("""(?m)^(>\s?)(.*)$""")
    val listMarker = Regex("""(?m)^(\s*(?:[-*+]|\d+\.)\s)""")
    val bold = Regex("""\*\*([^*\n]+?)\*\*""")
    val italic = Regex("""(?<!\*)\*([^*\n]+?)\*(?!\*)""")
    val strike = Regex("""~~([^~\n]+?)~~""")
    val mention = Regex("""(?<=^|\s)@[^\s@]+""")
    val slashLead = Regex("""^/[a-zA-Z0-9_-]+""")
    val mdLink = Regex("""\[([^\]\n]+)]\(([^)\n]+)\)""")
    val bareUrl = Regex("""https?://[^\s)\]>]+""")
    val mdTable = Regex("""(?m)^\|?.+\|.+\n\|?[\s:-]+\|[\s|:-]+$""")
}

/**
 * 聊天输入框实时语法与富文本着色转换器。
 * 严格使用 [OffsetMapping.Identity] 保持 1:1 字符索引，绝不改变文本长度与光标位置，100% 兼容中文 IME 组合态。
 */
internal class ComposerRichVisualTransformation(
    private val colors: GaliaisColors,
    private val renderMath: Boolean,
    private val fallbackThreshold: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty() || (fallbackThreshold > 0 && raw.length > fallbackThreshold)) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val dimColor = colors.inkSubtle.copy(alpha = 0.55f)
        val codeBg = if (colors.dark) colors.surfaceElevated else colors.surfaceSubtle
        val mathBg = colors.claudeTint.copy(alpha = if (colors.dark) 0.30f else 0.55f)
        val mentionBg = colors.accentContainer.copy(alpha = 0.65f)
        val occupied = mutableListOf<IntRange>()

        fun overlaps(r: IntRange): Boolean = occupied.any { it.first <= r.last && r.first <= it.last }
        fun AnnotatedString.Builder.safeStyle(style: SpanStyle, start: Int, end: Int) {
            if (start >= 0 && end <= raw.length && start < end) addStyle(style, start, end)
        }

        val styled = buildAnnotatedString {
            append(raw)
            // 1. 围栏代码块（优先占用区间，防止内部 Markdown/数学符号误匹配）
            ComposerPatterns.fencedBlock.findAll(raw).forEach { m ->
                val r = m.range
                occupied += r
                safeStyle(SpanStyle(fontFamily = Mono, background = codeBg, color = colors.ink), r.first, r.last + 1)
                val firstNl = raw.indexOf('\n', r.first).takeIf { it in r.first..r.last }
                if (firstNl != null) safeStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Medium), r.first, firstNl)
                safeStyle(SpanStyle(color = colors.accent), (r.last - 2).coerceAtLeast(r.first), r.last + 1)
            }
            // 2. 块级与行内数学公式
            if (renderMath && (raw.contains('$') || raw.contains("\\[") || raw.contains("\\("))) {
                ComposerPatterns.displayMath.findAll(raw).forEach { m ->
                    if (!overlaps(m.range)) {
                        occupied += m.range
                        safeStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = colors.claude, background = mathBg), m.range.first, m.range.last + 1)
                    }
                }
                ComposerPatterns.inlineMath.findAll(raw).forEach { m ->
                    if (!overlaps(m.range)) {
                        occupied += m.range
                        safeStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = colors.claude, background = mathBg), m.range.first, m.range.last + 1)
                    }
                }
            }
            // 3. 行内代码
            ComposerPatterns.inlineCode.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    occupied += m.range
                    safeStyle(SpanStyle(fontFamily = Mono, background = codeBg, color = colors.ink), m.range.first, m.range.last + 1)
                    safeStyle(SpanStyle(color = dimColor), m.range.first, m.range.first + 1)
                    safeStyle(SpanStyle(color = dimColor), m.range.last, m.range.last + 1)
                }
            }
            // 4. 标题、引用、列表
            ComposerPatterns.heading.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    val hashes = m.groups[1]!!.range
                    val body = m.groups[2]!!.range
                    safeStyle(SpanStyle(color = dimColor, fontWeight = FontWeight.Bold), hashes.first, hashes.last + 1)
                    safeStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold), body.first, body.last + 1)
                }
            }
            ComposerPatterns.blockquote.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    val mark = m.groups[1]!!.range
                    val body = m.groups[2]!!.range
                    safeStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold), mark.first, mark.last + 1)
                    safeStyle(SpanStyle(color = colors.inkMuted, fontStyle = FontStyle.Italic), body.first, body.last + 1)
                }
            }
            ComposerPatterns.listMarker.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) safeStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
            }
            // 5. 粗体 / 斜体 / 删除线
            ComposerPatterns.bold.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    safeStyle(SpanStyle(color = dimColor), m.range.first, m.range.first + 2)
                    safeStyle(SpanStyle(fontWeight = FontWeight.Bold), m.range.first + 2, m.range.last - 1)
                    safeStyle(SpanStyle(color = dimColor), m.range.last - 1, m.range.last + 1)
                }
            }
            ComposerPatterns.italic.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    safeStyle(SpanStyle(color = dimColor), m.range.first, m.range.first + 1)
                    safeStyle(SpanStyle(fontStyle = FontStyle.Italic), m.range.first + 1, m.range.last)
                    safeStyle(SpanStyle(color = dimColor), m.range.last, m.range.last + 1)
                }
            }
            ComposerPatterns.strike.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    safeStyle(SpanStyle(color = dimColor), m.range.first, m.range.first + 2)
                    safeStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.inkMuted), m.range.first + 2, m.range.last - 1)
                    safeStyle(SpanStyle(color = dimColor), m.range.last - 1, m.range.last + 1)
                }
            }
            // 6. @文件引用、/斜杠指令与链接
            ComposerPatterns.slashLead.find(raw)?.let { m ->
                if (!overlaps(m.range)) safeStyle(SpanStyle(fontFamily = Mono, color = colors.accent, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
            }
            ComposerPatterns.mention.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) safeStyle(SpanStyle(fontFamily = Mono, color = colors.accent, background = mentionBg), m.range.first, m.range.last + 1)
            }
            ComposerPatterns.mdLink.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) {
                    val label = m.groups[1]!!.range
                    val url = m.groups[2]!!.range
                    safeStyle(SpanStyle(color = colors.codex, textDecoration = TextDecoration.Underline), label.first, label.last + 1)
                    safeStyle(SpanStyle(color = dimColor), url.first, url.last + 1)
                }
            }
            ComposerPatterns.bareUrl.findAll(raw).forEach { m ->
                if (!overlaps(m.range)) safeStyle(SpanStyle(color = colors.codex, textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/** 判断文本是否包含可渲染富内容（Markdown 语法 / 代码块 / 数学公式 / SVG / 表格），避免纯口语输入弹出多余预览条。 */
internal fun hasRichRenderableContent(text: String, renderMath: Boolean = true): Boolean {
    val s = text.trim()
    if (s.length < 3) return false
    if (s.contains("```") || s.contains("<svg", ignoreCase = true) || s.contains("<antArtifact", ignoreCase = true)) return true
    if (renderMath && (ComposerPatterns.displayMath.containsMatchIn(s) || ComposerPatterns.inlineMath.containsMatchIn(s))) return true
    return ComposerPatterns.heading.containsMatchIn(s) ||
        ComposerPatterns.mdTable.containsMatchIn(s) ||
        ComposerPatterns.blockquote.containsMatchIn(s) ||
        ComposerPatterns.listMarker.containsMatchIn(s) ||
        ComposerPatterns.inlineCode.containsMatchIn(s) ||
        ComposerPatterns.bold.containsMatchIn(s) ||
        ComposerPatterns.strike.containsMatchIn(s) ||
        ComposerPatterns.mdLink.containsMatchIn(s)
}

/** 轻量中英文混合估算 Token 数。 */
internal fun estimatePromptTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.code in 0x2E80..0x9FFF || ch.code in 0xFF00..0xFFEF) cjk++ else other++
    }
    return ((cjk * 1.4f) + (other / 3.8f)).toInt().coerceAtLeast(1)
}

private val AUTO_PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '`' to '`', '$' to '$')

/** 非纯文本模式且开启自动配对时处理单字符括号/引号闭合与空对退格。 */
internal fun applyAutoPairEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.composition != null || !new.selection.collapsed || !old.selection.collapsed) return new
    val delta = new.text.length - old.text.length
    val pos = new.selection.start
    if (delta == 1 && pos > 0 && pos <= new.text.length) {
        val inserted = new.text[pos - 1]
        // 若用户直接键入光标右侧已有的自动闭合符（如 `)` / `"` / `$` / `` ` ``），直接跳过避免双写
        if (inserted in AUTO_PAIRS.values && pos < new.text.length && new.text[pos] == inserted) {
            val skipped = new.text.removeRange(pos - 1, pos)
            return new.copy(text = skipped, selection = TextRange(pos))
        }
        val closer = AUTO_PAIRS[inserted] ?: return new
        // 连续输入多个 `$` 或 `` ` ``（如 `$$` 公式 / ``` 代码围栏）时不自动插入多余闭合符
        if (inserted == closer && pos >= 2 && new.text[pos - 2] == inserted) return new
        val nextText = buildString(new.text.length + 1) {
            append(new.text, 0, pos)
            append(closer)
            append(new.text, pos, new.text.length)
        }
        return new.copy(text = nextText, selection = TextRange(pos))
    }
    // 仅在真正向前退格（pos == old.selection.start - 1）时删除紧邻的自动配对闭合符，避免 Forward-Delete 误删
    if (delta == -1 && pos == old.selection.start - 1 && old.selection.start > 0 && old.selection.start < old.text.length) {
        val removed = old.text[old.selection.start - 1]
        val expectedCloser = AUTO_PAIRS[removed]
        if (expectedCloser != null && old.text[old.selection.start] == expectedCloser && pos < new.text.length && new.text[pos] == expectedCloser) {
            val nextText = new.text.removeRange(pos, pos + 1)
            return new.copy(text = nextText, selection = TextRange(pos))
        }
    }
    return new
}

/** 用前缀/后缀包裹当前选区，或在光标处插入模板并将光标定位到内部文本（不包含后缀，支持连续嵌套格式化）。 */
internal fun wrapComposerSelection(
    value: TextFieldValue,
    prefix: String,
    suffix: String = "",
    placeholder: String = "",
): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
    val inner = selected.ifEmpty { placeholder }
    val nextText = buildString {
        append(value.text, 0, start)
        append(prefix)
        append(inner)
        append(suffix)
        append(value.text, end, value.text.length)
    }
    val cursorStart = start + prefix.length
    val cursorEnd = cursorStart + inner.length
    return TextFieldValue(nextText, selection = TextRange(cursorStart, cursorEnd))
}


/** 聊天输入框快捷格式栏（仅在非纯文本模式且开启「显示快捷格式工具栏」时展示）。 */
@Composable
internal fun ComposerFormatToolbar(
    value: TextFieldValue,
    indentStr: String,
    onValueChange: (TextFieldValue) -> Unit,
    onInsertMention: (() -> Unit)? = null,
    onExpandEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chipBg = G.surfaceSubtle
        val chipBorder = G.border
        TagPill("B", fg = G.ink, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "**", "**", "粗体"))
        })
        TagPill("I", fg = G.ink, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "*", "*", "斜体"))
        })
        TagPill("`代码`", fg = G.ink, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "`", "`", "code"))
        })
        TagPill("代码块", fg = G.ink, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "```\n", "\n```", ""))
        })
        TagPill("\$公式\$", fg = G.claude, bg = G.claudeTint, onClick = {
            onValueChange(wrapComposerSelection(value, "$", "$", "x"))
        })
        TagPill("引用", fg = G.inkMuted, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "> ", "", ""))
        })
        TagPill("列表", fg = G.inkMuted, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, "- ", "", ""))
        })
        TagPill("缩进", fg = G.inkMuted, bg = chipBg, border = chipBorder, onClick = {
            onValueChange(wrapComposerSelection(value, indentStr, "", ""))
        })
        if (onInsertMention != null) {
            TagPill("@文件", fg = G.accent, bg = G.accentContainer, onClick = onInsertMention)
        }
        TagPill("全屏", fg = G.accent, bg = G.accentContainer, onClick = onExpandEditor)
    }
}

/** 输入框上方自动实时渲染预览条（仅在关闭纯文本编辑器 + 开启自动实时渲染预览 + 检测到富内容时自动出现）。 */
@Composable
internal fun ComposerLivePreviewStrip(
    draft: String,
    onExpandEditor: () -> Unit,
    onOpenArtifact: (ChatArtifact) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember { mutableStateOf(false) }
    var debounced by remember { mutableStateOf(draft) }
    LaunchedEffect(draft) {
        delay(140)
        debounced = draft
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        shape = D.shape(14.dp),
        color = G.surface,
        border = BorderStroke(0.75.dp, G.accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(G.surfaceSubtle.copy(alpha = 0.72f))
                    .clickable { collapsed = !collapsed }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(12.5.dp))
                Text(
                    "实时渲染预览",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = G.inkMuted,
                    modifier = Modifier.weight(1f, fill = false),
                )
                AnimatedChevron(expanded = !collapsed, tint = G.inkSubtle, size = 12.5.dp)
                Spacer(Modifier.weight(1f))
                TagPill("大屏编辑", fg = G.accent, bg = G.accentContainer, onClick = onExpandEditor)
            }
            AnimatedExpandable(expanded = !collapsed) {
                Hairline()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp, max = 148.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Markdown(
                        text = debounced,
                        compact = false,
                        sourceItemId = "composer-preview",
                        onOpenArtifact = onOpenArtifact,
                    )
                }
            }
        }
    }
}

/** 全屏提示词编辑器弹窗：纯文本模式下为无干扰纯文本大屏；非纯文本模式下提供「编辑 / 实时预览」切换与格式栏。 */
@Composable
internal fun ExpandedComposerDialog(
    value: TextFieldValue,
    settings: SettingsState,
    canSend: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onOpenArtifact: (ChatArtifact) -> Unit,
    onDismiss: () -> Unit,
) {
    val isPlain = settings.plainTextEditor
    var tab by remember(isPlain) { mutableIntStateOf(0) } // 0=编辑 1=实时预览
    val colors = G
    val overLimit = settings.editorPlainFallbackThreshold > 0 && value.text.length > settings.editorPlainFallbackThreshold
    val visualTransformation = remember(isPlain, settings.editorSyntaxHighlight, settings.renderMathFormulas, settings.editorPlainFallbackThreshold, colors) {
        if (!isPlain && settings.editorSyntaxHighlight) {
            ComposerRichVisualTransformation(colors, settings.renderMathFormulas, settings.editorPlainFallbackThreshold)
        } else VisualTransformation.None
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            color = G.canvas,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(GaliaisIcons.Close, contentDescription = "关闭全屏编辑器", tint = G.ink, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isPlain) "纯文本编辑器" else "富文本编辑器",
                            style = MaterialTheme.typography.titleSmall,
                            color = G.ink,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val lines = if (value.text.isEmpty()) 0 else value.text.count { it == '\n' } + 1
                        val statusMeta = buildString {
                            append("${value.text.length} 字 · $lines 行 · ≈${estimatePromptTokens(value.text)} tok")
                            if (isPlain) append(" · 零渲染")
                            else if (overLimit) append(" · 超长纯文本保护")
                        }
                        Text(statusMeta, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isPlain) {
                        SegmentedPillBar(
                            options = listOf(0 to "编辑", 1 to "预览"),
                            selected = tab,
                            onSelect = { tab = it },
                            fillWidth = false,
                            compact = true,
                        )
                    }
                }
                Hairline()

                if (!isPlain && tab == 0) {
                    ComposerFormatToolbar(
                        value = value,
                        indentStr = settings.tabIndentString(),
                        onValueChange = onValueChange,
                        onExpandEditor = onDismiss,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Hairline()
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
                    if (isPlain || tab == 0) {
                        if (value.text.isEmpty()) {
                            Text(
                                if (isPlain) "纯文本输入（不做任何格式渲染与自动改写）…" else "支持 Markdown、代码块、数学公式、@文件引用…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = G.inkSubtle,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = { next ->
                                onValueChange(if (!isPlain && settings.editorAutoPair) applyAutoPairEdit(value, next) else next)
                            },
                            visualTransformation = visualTransformation,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = G.ink,
                                fontFamily = if (settings.editorMonospace) Mono else MaterialTheme.typography.bodyLarge.fontFamily,
                                lineHeight = 23.sp,
                            ),
                            cursorBrush = SolidColor(G.accent),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        )
                    } else {
                        if (value.text.isBlank()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无内容可预览", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                            }
                        } else {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Markdown(
                                    text = value.text,
                                    compact = false,
                                    sourceItemId = "expanded-preview",
                                    onOpenArtifact = onOpenArtifact,
                                )
                            }
                        }
                    }
                }

                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isPlain) {
                        TagPill("插入缩进", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                            onValueChange(wrapComposerSelection(value, settings.tabIndentString()))
                        })
                    }
                    if (value.text.isNotEmpty()) {
                        TagPill("清空", fg = G.danger, bg = G.dangerTint, onClick = { onValueChange(TextFieldValue("")) })
                    }
                    Spacer(Modifier.weight(1f))
                    SecondaryPillButton("收起", onClick = onDismiss)
                    PrimaryPillButton("发送", enabled = canSend, onClick = { onDismiss(); onSend() })
                }
            }
        }
    }
}
