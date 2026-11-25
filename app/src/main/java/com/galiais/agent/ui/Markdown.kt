package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLDecoder
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.FileDiff
import com.galiais.agent.engine.MessageSegment
import com.galiais.agent.engine.splitMessageSegments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

/** 本地 `file:///` 链接点击处理器（由 ChatScreen 通过 CompositionLocalProvider 注入以直达工作台/文件预览）。 */
val LocalFileLinkHandler = staticCompositionLocalOf<((path: String, lineSpec: String?) -> Unit)?> { null }

/** 解析 Markdown 中的 `file:///` URI（含 `#L10-L20` 行号锚点与 URL 编码中文路径）。 */
internal fun parseFileLinkTarget(url: String): Pair<String, String?>? {
    val trimmed = url.trim()
    val withoutScheme = when {
        trimmed.startsWith("file://localhost/", ignoreCase = true) -> trimmed.substring("file://localhost".length)
        trimmed.startsWith("file://", ignoreCase = true) -> trimmed.substring("file://".length)
        else -> return null
    }
    val hashIdx = withoutScheme.indexOf('#')
    val rawPath = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
    val fragment = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1).takeIf { it.isNotBlank() } else null
    val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
    val normalized = if (decoded.startsWith("/")) decoded else "/$decoded"
    if (normalized.length <= 1 || normalized.contains('\u0000')) return null
    return normalized to fragment
}

/** 识别 GFM 任务列表项前缀 `[ ] ` / `[x] ` / `[X] `，返回 `(checked, prefixLength)`。 */
internal fun extractTaskListMarker(item: ListItem): Pair<Boolean, Int>? {
    val para = item.firstChild as? Paragraph ?: return null
    val firstText = para.firstChild as? org.commonmark.node.Text ?: return null
    val lit = firstText.literal ?: return null
    return when {
        lit.startsWith("[ ] ") -> false to 4
        lit.startsWith("[x] ", ignoreCase = true) -> true to 4
        lit == "[ ]" -> false to 3
        lit.equals("[x]", ignoreCase = true) -> true to 3
        else -> null
    }
}

private fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

private val displayMathBlockRe = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]""")
private val calloutHeaderRe = Regex("""^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*(.*)""", RegexOption.IGNORE_CASE)

/**
 * 流式长回复增量切分：把已完整闭合的前置段落与正在逐字增长的尾部段落分离，
 * 使前置段落（含已闭合代码块、公式、图表）完全命中 `remember` 缓存，流式期间只重解析尾部小段文本。
 */
private fun splitStableMarkdownPrefix(text: String): Pair<String, String> {
    if (text.length < 240) return "" to text
    var inFence = false
    var inMath = false
    var inArtifact = false
    var lastSafeIdx = 0
    var pos = 0
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
        if (trimmed.isEmpty() && !inFence && !inMath && !inArtifact && nextPos < len) {
            val peekEnd = text.indexOf('\n', nextPos).let { if (it < 0) len else it }
            val nextTrim = text.substring(nextPos, peekEnd).trim()
            if (nextTrim.isNotEmpty() && !nextTrim.startsWith("|")) {
                lastSafeIdx = nextPos
            }
        }
        pos = nextPos
    }
    if (lastSafeIdx <= 0 || lastSafeIdx >= len) return "" to text
    return text.substring(0, lastSafeIdx) to text.substring(lastSafeIdx)
}

/** Compose 原生 Markdown 渲染：支持流式增量冻结、标题、列表、Callout 引用、语法高亮代码块、表格、Diff、可排序 CSV、行内/块级数学公式及离线交互可视化组件。 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    sourceItemId: String = "",
    streaming: Boolean = false,
    overrideTextColor: Color? = null,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
) {
    if (streaming && text.length >= 240) {
        val (stablePrefix, liveTail) = remember(text) { splitStableMarkdownPrefix(text) }
        if (stablePrefix.isNotEmpty()) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp)) {
                MarkdownBody(
                    text = stablePrefix,
                    compact = compact,
                    sourceItemId = sourceItemId,
                    streaming = false,
                    overrideTextColor = overrideTextColor,
                    onOpenArtifact = onOpenArtifact,
                )
                if (liveTail.isNotEmpty()) {
                    MarkdownBody(
                        text = liveTail,
                        compact = compact,
                        sourceItemId = sourceItemId,
                        streaming = true,
                        overrideTextColor = overrideTextColor,
                        onOpenArtifact = onOpenArtifact,
                    )
                }
            }
            return
        }
    }
    MarkdownBody(
        text = text,
        modifier = modifier,
        compact = compact,
        sourceItemId = sourceItemId,
        streaming = streaming,
        overrideTextColor = overrideTextColor,
        onOpenArtifact = onOpenArtifact,
    )
}

@Composable
private fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    sourceItemId: String = "",
    streaming: Boolean = false,
    overrideTextColor: Color? = null,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
) {
    val prefs = RPrefs
    val segments = remember(text, sourceItemId, streaming) { splitMessageSegments(sourceItemId, text, streaming) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.Artifact -> {
                    if (!compact && prefs.inlineVisualArtifacts && seg.artifact.kind == ArtifactKind.CSV && !seg.artifact.streaming) {
                        InlineCsvTableCard(
                            csvText = seg.artifact.content,
                            onOpenStudio = onOpenArtifact?.let { open -> { open(seg.artifact) } },
                        )
                    } else if (!compact && prefs.inlineVisualArtifacts && seg.artifact.kind != ArtifactKind.CODE && seg.artifact.kind != ArtifactKind.MARKDOWN) {
                        InlineVisualArtifactCard(artifact = seg.artifact, onOpenStudio = onOpenArtifact)
                    } else if (onOpenArtifact != null) {
                        InlineArtifactCard(artifact = seg.artifact, onOpen = onOpenArtifact)
                    } else {
                        CodeBlock(seg.artifact.content, seg.artifact.language)
                    }
                }
                is MessageSegment.Text -> {
                    val document = remember(seg.markdown) { parser.parse(seg.markdown) }
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp)) {
                        document.children().forEach { Block(it, compact, sourceItemId, streaming, overrideTextColor, onOpenArtifact) }
                    }
                }
            }
        }
    }
}

private fun rawParagraphText(node: Node): String {
    val sb = StringBuilder()
    fun walk(n: Node) {
        when (n) {
            is org.commonmark.node.Text -> sb.append(n.literal)
            is Code -> sb.append('`').append(n.literal).append('`')
            is SoftLineBreak -> sb.append('\n')
            is HardLineBreak -> sb.append('\n')
            else -> n.children().forEach(::walk)
        }
    }
    walk(node)
    return sb.toString()
}

@Composable
private fun Block(
    node: Node,
    compact: Boolean = false,
    sourceItemId: String = "",
    streaming: Boolean = false,
    overrideTextColor: Color? = null,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
) {
    val prefs = RPrefs
    val bodyStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val textColor = overrideTextColor ?: if (compact) G.inkMuted else G.ink
    when (node) {
        is Paragraph -> {
            val images = remember(node) { node.children().filterIsInstance<MdImage>() }
            val raw = remember(node) { rawParagraphText(node) }
            val hasDisplayMath = remember(raw, prefs.renderMathFormulas) { prefs.renderMathFormulas && (raw.contains("$$") || raw.contains("\\[")) }
            val mathMatches = if (hasDisplayMath) remember(raw) { displayMathBlockRe.findAll(raw).toList() } else emptyList()
            if (images.isNotEmpty() && !compact) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { img ->
                        val alt = rawParagraphText(img).ifBlank { img.title.orEmpty() }
                        InlineImageCard(destination = img.destination.orEmpty(), alt = alt)
                    }
                    val nonImgText = node.children().filterNot { it is MdImage }
                    if (nonImgText.isNotEmpty()) {
                        SelectionContainer { Text(inline(node, overrideTextColor), style = bodyStyle, color = textColor) }
                    }
                }
            } else if (mathMatches.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var cursor = 0
                    mathMatches.forEachIndexed { idx, m ->
                        if (m.range.first > cursor) {
                            val before = raw.substring(cursor, m.range.first).trim()
                            if (before.isNotEmpty()) {
                                val subDoc = remember(before) { parser.parse(before) }
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        subDoc.children().forEach { child ->
                                            if (child is Paragraph) Text(inline(child, overrideTextColor), style = bodyStyle, color = textColor)
                                        }
                                    }
                                }
                            }
                        }
                        val tex = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                        if (compact || !prefs.inlineVisualArtifacts || overrideTextColor != null) {
                            Surface(
                                shape = D.shape(8.dp),
                                color = overrideTextColor?.copy(alpha = 0.14f) ?: G.surfaceSubtle,
                                border = BorderStroke(0.5.dp, overrideTextColor?.copy(alpha = 0.28f) ?: G.border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    InlineMathFormatter.toPrettyUnicode(tex),
                                    style = bodyStyle,
                                    color = textColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        } else {
                            val mathArt = remember(tex, sourceItemId, idx, streaming) {
                                ChatArtifact(
                                    id = "$sourceItemId:math:$idx:${tex.hashCode()}",
                                    identifier = "math-${tex.hashCode()}",
                                    title = "数学公式",
                                    kind = ArtifactKind.MATH,
                                    language = "latex",
                                    content = tex,
                                    sourceItemId = sourceItemId,
                                    streaming = streaming,
                                )
                            }
                            InlineVisualArtifactCard(artifact = mathArt, onOpenStudio = onOpenArtifact)
                        }
                        cursor = m.range.last + 1
                    }
                    if (cursor < raw.length) {
                        val after = raw.substring(cursor).trim()
                        if (after.isNotEmpty()) {
                            val subDoc = remember(after) { parser.parse(after) }
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    subDoc.children().forEach { child ->
                                        if (child is Paragraph) Text(inline(child, overrideTextColor), style = bodyStyle, color = textColor)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                SelectionContainer { Text(inline(node, overrideTextColor), style = bodyStyle, color = textColor) }
            }
        }
        is Heading -> SelectionContainer {
            val headingStyle = if (compact) {
                when (node.level) {
                    1 -> MaterialTheme.typography.titleSmall
                    2 -> MaterialTheme.typography.bodyLarge
                    else -> MaterialTheme.typography.bodyMedium
                }
            } else {
                when (node.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    3 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.bodyLarge
                }
            }
            val headingColor = overrideTextColor ?: if (node.level >= 4) G.inkMuted else G.ink
            Text(
                inline(node, overrideTextColor),
                style = headingStyle,
                color = headingColor,
                fontWeight = if (node.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.padding(top = if (node.level <= 2) 6.dp else 3.dp, bottom = 1.dp),
            )
        }
        is FencedCodeBlock -> {
            val code = node.literal.removeSuffix("\n")
            val lang = node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
            val lowerLang = lang?.lowercase()
            if (!compact && (lowerLang == "diff" || lowerLang == "patch") && code.isNotBlank()) {
                DiffView(FileDiff(path = "代码变更", kind = "update", diff = code), inlineCompact = true)
            } else if (!compact && prefs.inlineVisualArtifacts && (lowerLang == "csv" || lowerLang == "tsv") && code.lines().size >= 2) {
                val csvArt = remember(code, sourceItemId) {
                    ChatArtifact(
                        id = "$sourceItemId:csv:${code.hashCode()}",
                        identifier = "csv-${code.hashCode()}",
                        title = "数据图表",
                        kind = ArtifactKind.CSV,
                        language = lowerLang,
                        content = code,
                        sourceItemId = sourceItemId,
                    )
                }
                InlineCsvTableCard(
                    csvText = code,
                    onOpenStudio = onOpenArtifact?.let { open -> { open(csvArt) } },
                )
            } else {
                val previewKind = remember(lowerLang, code) {
                    when (lowerLang) {
                        "html", "htm" -> if (code.length >= 30) ArtifactKind.HTML else null
                        "svg" -> ArtifactKind.SVG
                        "mermaid", "mmd" -> ArtifactKind.MERMAID
                        "chart", "echarts", "chartjs", "plot", "vega", "vega-lite" -> ArtifactKind.CHART
                        "math", "katex" -> ArtifactKind.MATH
                        "latex", "tex" -> ArtifactKind.LATEX
                        "jsx", "tsx" -> if (code.length >= 50) ArtifactKind.REACT else null
                        else -> null
                    }
                }
                if (!compact && prefs.inlineVisualArtifacts && overrideTextColor == null && previewKind != null) {
                    val visualArt = remember(previewKind, code, sourceItemId, streaming) {
                        ChatArtifact(
                            id = "$sourceItemId:vis:${previewKind.wire}:${code.hashCode()}",
                            identifier = "vis-${previewKind.wire}-${code.hashCode()}",
                            title = previewKind.label,
                            kind = previewKind,
                            language = lang ?: previewKind.extension,
                            content = code,
                            sourceItemId = sourceItemId,
                            streaming = streaming,
                        )
                    }
                    InlineVisualArtifactCard(artifact = visualArt, onOpenStudio = onOpenArtifact)
                } else {
                    val onPreview = if (previewKind != null && onOpenArtifact != null) {
                        {
                            onOpenArtifact(
                                ChatArtifact(
                                    id = "$sourceItemId:code:${code.hashCode()}",
                                    identifier = "code-${previewKind.wire}-${code.hashCode()}",
                                    title = "${previewKind.label} 预览",
                                    kind = previewKind,
                                    language = lang ?: previewKind.extension,
                                    content = code,
                                    sourceItemId = sourceItemId,
                                ),
                            )
                        }
                    } else null
                    CodeBlock(code, lang, onPreview = onPreview, previewLabel = if (previewKind == ArtifactKind.LATEX) "编译预览" else "预览")
                }
            }
        }
        is IndentedCodeBlock -> CodeBlock(node.literal.removeSuffix("\n"), null)
        is HtmlBlock -> CodeBlock(node.literal.removeSuffix("\n"), "html")
        is BulletList -> ListBlock(node, ordered = false, start = 1, compact = compact, sourceItemId = sourceItemId, streaming = streaming, overrideTextColor = overrideTextColor, onOpenArtifact = onOpenArtifact)
        is OrderedList -> ListBlock(node, ordered = true, start = node.markerStartNumber ?: 1, compact = compact, sourceItemId = sourceItemId, streaming = streaming, overrideTextColor = overrideTextColor, onOpenArtifact = onOpenArtifact)
        is BlockQuote -> {
            val firstParaText = remember(node) { (node.firstChild as? Paragraph)?.let(::rawParagraphText).orEmpty() }
            val callout = remember(firstParaText) { calloutHeaderRe.find(firstParaText.lineSequence().firstOrNull().orEmpty()) }
            if (callout != null && overrideTextColor == null) {
                val tag = callout.groupValues[1].uppercase()
                val accent = when (tag) {
                    "TIP" -> G.success
                    "IMPORTANT" -> G.claude
                    "WARNING" -> G.warning
                    "CAUTION" -> G.danger
                    else -> G.accent
                }
                val tint = when (tag) {
                    "TIP" -> G.successTint
                    "IMPORTANT" -> G.claudeTint
                    "WARNING" -> G.warningTint
                    "CAUTION" -> G.dangerTint
                    else -> G.accentContainer
                }
                val label = when (tag) {
                    "TIP" -> "提示"
                    "IMPORTANT" -> "重要"
                    "WARNING" -> "注意"
                    "CAUTION" -> "警告"
                    else -> "说明"
                }
                val icon = when (tag) {
                    "TIP" -> GaliaisIcons.Lightbulb
                    "IMPORTANT" -> GaliaisIcons.Sparkles
                    "WARNING" -> GaliaisIcons.AlertTriangle
                    "CAUTION" -> GaliaisIcons.ShieldCheck
                    else -> GaliaisIcons.Info
                }
                val rest = firstParaText.lineSequence().drop(1).joinToString("\n").trim()
                val extraLine = callout.groupValues[2].trim()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = D.shape(12.dp),
                    color = tint.copy(alpha = if (G.dark) 0.24f else 0.44f),
                    border = BorderStroke(0.75.dp, accent.copy(alpha = 0.28f)),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                        }
                        val firstClean = listOf(extraLine, rest).filter { it.isNotBlank() }.joinToString("\n")
                        if (firstClean.isNotBlank()) {
                            val subDoc = remember(firstClean) { parser.parse(firstClean) }
                            subDoc.children().forEach { Block(it, compact, sourceItemId, streaming, null, onOpenArtifact) }
                        }
                        node.children().drop(1).forEach { Block(it, compact, sourceItemId, streaming, null, onOpenArtifact) }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = D.shape(11.dp),
                    color = overrideTextColor?.copy(alpha = 0.1f) ?: G.surfaceSubtle.copy(alpha = if (G.dark) 0.55f else 0.68f),
                    border = BorderStroke(0.75.dp, overrideTextColor?.copy(alpha = 0.24f) ?: G.border.copy(alpha = 0.72f)),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        node.children().forEach {
                            Block(it, compact, sourceItemId, streaming, overrideTextColor ?: G.inkMuted, onOpenArtifact)
                        }
                    }
                }
            }
        }
        is ThematicBreak -> Hairline(Modifier.padding(vertical = 6.dp))
        is TableBlock -> TableView(node)
        else -> node.children().forEach { Block(it, compact, sourceItemId, streaming, overrideTextColor, onOpenArtifact) }
    }
}

@Composable
private fun ListBlock(
    node: Node,
    ordered: Boolean,
    start: Int,
    compact: Boolean = false,
    sourceItemId: String = "",
    streaming: Boolean = false,
    overrideTextColor: Color? = null,
    onOpenArtifact: ((ChatArtifact) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
        node.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
            val taskMarker = if (!ordered) extractTaskListMarker(item) else null
            Row(Modifier.fillMaxWidth()) {
                if (taskMarker != null) {
                    val checked = taskMarker.first
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp, end = 7.dp)
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (checked) G.success else Color.Transparent)
                            .border(1.25.dp, if (checked) G.success else G.borderStrong, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) {
                            Icon(GaliaisIcons.Check, contentDescription = "已完成", tint = Color.White, modifier = Modifier.size(10.dp))
                        }
                    }
                } else if (ordered) {
                    Text(
                        "${start + index}.",
                        modifier = Modifier.width(24.dp).padding(end = 4.dp),
                        style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        color = overrideTextColor?.copy(alpha = 0.72f) ?: G.inkSubtle,
                    )
                } else {
                    Box(
                        modifier = Modifier.width(16.dp).padding(top = if (compact) 8.dp else 9.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(overrideTextColor?.copy(alpha = 0.6f) ?: G.inkSubtle.copy(alpha = 0.72f)),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val itemChildren = item.children()
                    itemChildren.forEachIndexed { childIdx, child ->
                        if (childIdx == 0 && taskMarker != null && child is Paragraph) {
                            val checked = taskMarker.first
                            SelectionContainer {
                                Text(
                                    text = inline(
                                        node = child,
                                        overrideTextColor = if (checked) (overrideTextColor?.copy(alpha = 0.65f) ?: G.inkMuted) else overrideTextColor,
                                        stripLeadingChars = taskMarker.second,
                                        strikethrough = checked,
                                    ),
                                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                    color = if (checked) (overrideTextColor?.copy(alpha = 0.65f) ?: G.inkMuted) else (overrideTextColor ?: G.ink),
                                )
                            }
                        } else {
                            Block(child, compact, sourceItemId, streaming, overrideTextColor, onOpenArtifact)
                        }
                    }
                }
            }
        }
    }
}

internal data class ReasoningPreview(val title: String?, val body: String)

private val reasoningBoldTitle = Regex("^\\*\\*([^\\n]*?)\\*\\*")
private val reasoningHeading = Regex("^#{1,6}[ \\t]+[^\\r\\n]+(?:\\r?\\n|$)")

internal fun reasoningPreview(text: String, streaming: Boolean): ReasoningPreview {
    val trimmed = text.trimStart()
    val bold = reasoningBoldTitle.find(trimmed)
    // 标题尚未传完时不显示半个 Markdown 标记。
    if (streaming && trimmed.startsWith("**") && bold == null) return ReasoningPreview(null, "")
    val leading = bold ?: reasoningHeading.find(trimmed)
    fun plain(node: Node): String = when (node) {
        is org.commonmark.node.Text -> node.literal
        is Code -> node.literal
        is SoftLineBreak, is HardLineBreak -> " "
        else -> node.children().joinToString("") { plain(it) }
    }
    val title = parser.parse(trimmed).children().lastOrNull { node ->
        node is Heading || (node is Paragraph && node.firstChild is StrongEmphasis && node.firstChild.next == null)
    }?.let(::plain)?.trim()?.takeIf { it.isNotEmpty() } ?: bold?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    return ReasoningPreview(title, if (leading == null) trimmed else trimmed.substring(leading.range.last + 1).trimStart())
}

@Composable
private fun TableView(table: TableBlock) {
    val rows = table.children().flatMap { section -> section.children() }.filterIsInstance<TableRow>()
    if (rows.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale.coerceIn(0.85f, 1.5f)
    val colWidths = remember(rows, fontScale) {
        val maxLens = mutableListOf<Int>()
        for (row in rows) {
            row.children().filterIsInstance<TableCell>().forEachIndexed { colIdx, cell ->
                val txt = rawParagraphText(cell)
                // 中文与宽字符按 2 宽估算，并随系统/应用 fontScale 等比缩放列宽
                val visualLen = txt.sumOf { ch -> if (ch.code > 0x7F) 2 else 1 }
                while (maxLens.size <= colIdx) maxLens += 0
                if (visualLen > maxLens[colIdx]) maxLens[colIdx] = visualLen
            }
        }
        maxLens.map { len -> ((len * 7.4f * fontScale) + 28f).coerceIn(82f * fontScale, 256f * fontScale).dp }
    }
    val totalTableWidth = remember(colWidths) { colWidths.fold(0.dp) { acc, w -> acc + w } }
    Surface(shape = D.shape(12.dp), color = G.surface, border = BorderStroke(0.75.dp, G.border)) {
        SelectionContainer {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                rows.forEachIndexed { index, row ->
                    val rowBg = when {
                        index == 0 -> G.surfaceSubtle
                        index % 2 == 0 -> G.surfaceSubtle.copy(alpha = if (G.dark) 0.28f else 0.36f)
                        else -> G.surface
                    }
                    Row(Modifier.background(rowBg)) {
                        row.children().filterIsInstance<TableCell>().forEachIndexed { colIdx, cell ->
                            val cellWidth = colWidths.getOrElse(colIdx) { (128f * fontScale).dp }
                            val align = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            }
                            Text(
                                inline(cell),
                                modifier = Modifier.width(cellWidth).padding(horizontal = 12.dp, vertical = 9.dp),
                                style = if (cell.isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                                fontWeight = if (cell.isHeader) FontWeight.SemiBold else null,
                                textAlign = align,
                                color = G.ink,
                            )
                        }
                    }
                    if (index < rows.lastIndex) HorizontalDivider(modifier = Modifier.width(totalTableWidth), thickness = 0.5.dp, color = G.border.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** 深色 IDE 风格语法高亮代码块，带语言标签、可视化预览与复制反馈。 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null,
    previewLabel: String = "预览",
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val palette = D.code
    val highlighted = remember(code, language, palette) { SyntaxHighlight.highlight(code, language, palette) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = D.codeShape,
        color = G.codeBg,
        border = BorderStroke(1.dp, G.codeBorder),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(G.codeHeader).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(GaliaisIcons.Code, contentDescription = null, tint = G.codeMuted, modifier = Modifier.size(13.dp))
                Text(
                    language?.lowercase() ?: "text",
                    fontFamily = Mono,
                    fontSize = 11.5.sp,
                    color = G.codeMuted,
                    modifier = Modifier.weight(1f),
                )
                if (onPreview != null) {
                    Row(
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .background(G.accent.copy(alpha = 0.16f))
                            .clickable(onClick = onPreview)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(11.5.dp))
                        Text(previewLabel, fontSize = 11.sp, color = G.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                val copyTint = if (copied) (if (palette.dark) Color(0xFF7EE787) else Color(0xFF116329)) else G.codeMuted
                Row(
                    modifier = Modifier.clip(D.shape(6.dp)).clickable {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("code", code).toClipEntry())
                            copied = true
                        }
                    }.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(if (copied) GaliaisIcons.Check else GaliaisIcons.Copy, contentDescription = null, tint = copyTint, modifier = Modifier.size(12.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        fontSize = 11.5.sp,
                        color = copyTint,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            CodeContent(highlighted)
        }
    }
}

@Composable
private fun inline(
    node: Node,
    overrideTextColor: Color? = null,
    stripLeadingChars: Int = 0,
    strikethrough: Boolean = false,
): AnnotatedString {
    val renderMath = RPrefs.renderMathFormulas
    val fileLinkHandler = LocalFileLinkHandler.current
    val codeBg = overrideTextColor?.copy(alpha = 0.16f) ?: if (G.dark) G.surfaceElevated else G.surfaceSubtle
    val codeFg = overrideTextColor ?: G.ink
    val mathBg = overrideTextColor?.copy(alpha = 0.16f) ?: if (G.dark) G.claudeTint.copy(alpha = 0.25f) else G.claudeTint.copy(alpha = 0.45f)
    val mathFg = overrideTextColor ?: G.ink
    val linkColor = overrideTextColor ?: if (D.material) G.accent else G.codex
    return remember(node, codeBg, codeFg, mathBg, mathFg, linkColor, renderMath, fileLinkHandler, stripLeadingChars, strikethrough) {
        buildAnnotatedString {
            var remainingStrip = stripLeadingChars
            fun appendBody(current: Node) {
                when (current) {
                    is org.commonmark.node.Text -> {
                        val rawLit = current.literal
                        val lit = if (remainingStrip > 0) {
                            val drop = minOf(remainingStrip, rawLit.length)
                            remainingStrip -= drop
                            rawLit.substring(drop)
                        } else rawLit
                        if (lit.isNotEmpty()) {
                            if (renderMath && (lit.contains('$') || lit.contains("\\("))) {
                                InlineMathFormatter.appendWithMath(this, lit, mathFg, mathBg)
                            } else {
                                append(lit)
                            }
                        }
                    }
                    is Code -> withStyle(SpanStyle(fontFamily = Mono, background = codeBg, color = codeFg, fontSize = 12.5.sp)) { append("\u00A0${current.literal}\u00A0") }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { current.children().forEach(::appendBody) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { current.children().forEach(::appendBody) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { current.children().forEach(::appendBody) }
                    is Link -> {
                        val url = current.destination
                        val fileTarget = parseFileLinkTarget(url)
                        when {
                            url.startsWith("https://") || url.startsWith("http://") -> {
                                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                                    current.children().forEach(::appendBody)
                                }
                            }
                            fileTarget != null && fileLinkHandler != null -> {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "file:$url",
                                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)),
                                        linkInteractionListener = { fileLinkHandler(fileTarget.first, fileTarget.second) },
                                    ),
                                ) {
                                    current.children().forEach(::appendBody)
                                }
                            }
                            else -> withStyle(SpanStyle(color = linkColor)) { current.children().forEach(::appendBody) }
                        }
                    }
                    is SoftLineBreak -> append(" ")
                    is HardLineBreak -> append("\n")
                    is HtmlInline -> append(current.literal)
                    is MdImage -> {
                        val alt = rawParagraphText(current).ifBlank { current.title.orEmpty() }.ifBlank { "图片" }
                        withStyle(SpanStyle(color = linkColor)) { append("[$alt]") }
                    }
                    else -> current.children().forEach(::appendBody)
                }
            }
            if (strikethrough) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    node.children().forEach(::appendBody)
                }
            } else {
                node.children().forEach(::appendBody)
            }
        }
    }
}
