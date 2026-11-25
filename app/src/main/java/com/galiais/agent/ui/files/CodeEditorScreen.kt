package com.galiais.agent.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.LineEnding
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.TextEncoding
import com.galiais.agent.engine.fs.TextPageChunk
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.ui.ArtifactStudioSheet
import com.galiais.agent.ui.CodePalette
import com.galiais.agent.ui.CompactActionButton
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisAlertDialog
import com.galiais.agent.ui.GaliaisDropdownMenu
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisMenuRow
import com.galiais.agent.ui.GaliaisSheet
import com.galiais.agent.ui.Hairline
import com.galiais.agent.ui.HeaderDivider
import com.galiais.agent.ui.HeaderProgressSlot
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.RPrefs
import com.galiais.agent.ui.SecondaryPillButton
import com.galiais.agent.ui.SyntaxHighlight
import com.galiais.agent.ui.TagPill
import com.galiais.agent.ui.galiaisTextFieldColors
import com.galiais.agent.ui.visualArtifactKindForFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val MAX_INLINE_HIGHLIGHT_CHARS = 65_000
private const val LARGE_FILE_THRESHOLD_BYTES = 1_500_000L

private val ACCESSORY_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", "=", ":", ";", "\"", "'", "/", "\\", "|", "&", "_", "-", "->", "=>", "$", "`",
)

private data class CodeSymbolItem(val line: Int, val label: String, val kind: String)

private fun extractCodeSymbols(text: String, lang: String?): List<CodeSymbolItem> {
    val out = mutableListOf<CodeSymbolItem>()
    val regexes = listOf(
        "fn" to Regex("""^\s*(?:public|private|internal|protected|suspend|inline|override|async|export|default|static|final|pub|\s)*(?:fun|function|def|fn|func)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "cls" to Regex("""^\s*(?:public|private|internal|protected|data|sealed|enum|abstract|export|default|pub|\s)*(?:class|interface|object|struct|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "md" to Regex("""^(#{1,4})\s+(.+)$"""),
        "json" to Regex("""^\s{0,2}"([^"]{1,48})"\s*:\s*[\{\[]"""),
    )
    text.lineSequence().forEachIndexed { idx, line ->
        if (out.size >= 150) return@forEachIndexed
        for ((kind, re) in regexes) {
            if (kind == "md" && lang != "markdown") continue
            if (kind == "json" && lang != "json") continue
            val m = re.find(line) ?: continue
            val label = if (kind == "md") "${m.groupValues[1]} ${m.groupValues[2].trim()}" else m.groupValues[1]
            out += CodeSymbolItem(idx + 1, label, kind)
            break
        }
    }
    return out
}

/** 计算查找命中区间（支持普通与正则）。 */
private fun findMatchRanges(text: String, query: String, useRegex: Boolean, caseSensitive: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    if (useRegex) {
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val re = runCatching { Regex(query, opts) }.getOrNull() ?: return emptyList()
        return re.findAll(text).take(500).map { it.range }.filter { !it.isEmpty() }.toList()
    }
    val out = mutableListOf<IntRange>()
    var idx = text.indexOf(query, 0, ignoreCase = !caseSensitive)
    while (idx >= 0 && out.size < 500) {
        out += idx until (idx + query.length)
        idx = text.indexOf(query, idx + query.length, ignoreCase = !caseSensitive)
    }
    return out
}

/** 编辑态实时语法高亮 + 搜索命中着色转换器（严格 1:1 OffsetMapping.Identity，中文 IME 零错位）。 */
private class CodeEditorVisualTransformation(
    private val lang: String?,
    private val palette: CodePalette,
    private val matches: List<IntRange>,
    private val activeMatchIdx: Int,
    private val matchBg: androidx.compose.ui.graphics.Color,
    private val activeMatchBg: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val base = if (raw.length <= MAX_INLINE_HIGHLIGHT_CHARS) {
            SyntaxHighlight.highlight(raw, lang, palette)
        } else {
            AnnotatedString(raw, SpanStyle(color = palette.ink))
        }
        if (matches.isEmpty()) return TransformedText(base, OffsetMapping.Identity)
        val combined = buildAnnotatedString {
            append(base)
            matches.forEachIndexed { i, r ->
                val s = r.first.coerceIn(0, raw.length)
                val e = (r.last + 1).coerceIn(s, raw.length)
                if (s < e) {
                    addStyle(SpanStyle(background = if (i == activeMatchIdx) activeMatchBg else matchBg), s, e)
                }
            }
        }
        return TransformedText(combined, OffsetMapping.Identity)
    }
}

/** 构造磁盘最新版本与本地草稿的简易行级 Diff，用于 3-Way 冲突解决。 */
internal fun computeLineDiffLines(diskText: String, localText: String, maxLines: Int = 240): List<Pair<Char, String>> {
    val a = diskText.lines()
    val b = localText.lines()
    val out = mutableListOf<Pair<Char, String>>()
    var i = 0; var j = 0
    while ((i < a.size || j < b.size) && out.size < maxLines) {
        if (i < a.size && j < b.size && a[i] == b[j]) {
            out += ' ' to a[i]; i++; j++
        } else {
            val lookA = (i until minOf(a.size, i + 6)).firstOrNull { j < b.size && a[it] == b[j] }
            val lookB = (j until minOf(b.size, j + 6)).firstOrNull { i < a.size && a[i] == b[it] }
            when {
                lookA != null && (lookB == null || lookA - i <= lookB - j) -> {
                    while (i < lookA && out.size < maxLines) { out += '-' to a[i]; i++ }
                }
                lookB != null -> {
                    while (j < lookB && out.size < maxLines) { out += '+' to b[j]; j++ }
                }
                else -> {
                    if (i < a.size) { out += '-' to a[i]; i++ }
                    if (j < b.size && out.size < maxLines) { out += '+' to b[j]; j++ }
                }
            }
        }
    }
    return out
}

/** 专业级全屏代码与文本编辑器（含实时语法高亮、行号、Undo/Redo、查找替换、符号大纲、编码/换行符切换、大文件分页与 3-Way 冲突 Diff）。 */
@Composable
fun CodeEditorDialog(
    app: GaliaisApplication,
    fileRef: FileRef,
    initialLine: Int? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    onSendToAgent: ((cwd: String, reference: String) -> Unit)? = null,
    onSaveExternal: ((FileRef) -> Unit)? = null,
) {
    val fm = app.fileManager
    val scope = rememberCoroutineScope()
    val prefs = RPrefs
    val lang = remember(fileRef.path) { SyntaxHighlight.languageFromPath(fileRef.path) }
    val visualKind = remember(fileRef.path) { visualArtifactKindForFile(fileRef.path) }

    var loading by remember(fileRef) { mutableStateOf(true) }
    var saving by remember(fileRef) { mutableStateOf(false) }
    var error by remember(fileRef) { mutableStateOf<String?>(null) }

    // 普通可编辑文档态
    var field by remember(fileRef) { mutableStateOf(TextFieldValue("")) }
    var savedText by remember(fileRef) { mutableStateOf("") }
    var diskRevision by remember(fileRef) { mutableStateOf("") }
    var encoding by remember(fileRef) { mutableStateOf(TextEncoding.UTF_8) }
    var lineEnding by remember(fileRef) { mutableStateOf(LineEnding.LF) }
    var totalFileBytes by remember(fileRef) { mutableStateOf(0L) }

    // 大文件分页模式（>1.5MB，避免 OOM）
    var largeFileMode by remember(fileRef) { mutableStateOf(false) }
    var largeStagedFile by remember(fileRef) { mutableStateOf<File?>(null) }
    var largePage by remember(fileRef) { mutableStateOf<TextPageChunk?>(null) }
    var largePageIdx by remember(fileRef) { mutableIntStateOf(0) }

    // Undo / Redo 历史栈
    val undoStack = remember(fileRef) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(fileRef) { mutableStateListOf<TextFieldValue>() }
    var lastEditMs by remember(fileRef) { mutableStateOf(0L) }

    // 工具浮层态
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var findRegex by remember { mutableStateOf(false) }
    var findCase by remember { mutableStateOf(false) }
    var activeMatchIdx by remember { mutableIntStateOf(0) }
    var showOutline by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showEncodingSheet by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var conflictDiskText by remember { mutableStateOf<String?>(null) }
    var previewArtifact by remember { mutableStateOf<ChatArtifact?>(null) }
    var fontScale by remember { mutableStateOf(1.0f) }
    var showEditorMenu by remember { mutableStateOf(false) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val editorFontSize = D.codeSize(12.5f * fontScale)
    val editorLineHeight = D.codeSize(19.5f * fontScale)
    val editorLineHeightPx = with(density) { editorLineHeight.toPx() }

    val dirty = !largeFileMode && field.text != savedText
    val matches = remember(field.text, findQuery, findRegex, findCase, showFindBar) {
        if (!showFindBar || findQuery.isEmpty()) emptyList()
        else findMatchRanges(field.text, findQuery, findRegex, findCase)
    }
    LaunchedEffect(matches.size) {
        if (matches.isEmpty()) activeMatchIdx = 0
        else activeMatchIdx = activeMatchIdx.coerceIn(0, matches.lastIndex)
    }

    fun applyEdit(next: TextFieldValue, forcePushUndo: Boolean = false) {
        val now = System.currentTimeMillis()
        if (next.text != field.text) {
            if (forcePushUndo || undoStack.isEmpty() || now - lastEditMs > 650L ||
                kotlin.math.abs(next.text.length - field.text.length) > 12 || next.text.endsWith("\n")
            ) {
                undoStack.add(field)
                if (undoStack.size > 180) undoStack.removeAt(0)
            }
            redoStack.clear()
            lastEditMs = now
        }
        field = next
    }

    fun jumpToLine(lineNo: Int) {
        val text = field.text
        var cur = 1
        var off = 0
        while (cur < lineNo && off < text.length) {
            val nl = text.indexOf('\n', off)
            if (nl < 0) { off = text.length; break }
            off = nl + 1
            cur++
        }
        field = field.copy(selection = TextRange(off))
        val targetPx = ((lineNo - 3).coerceAtLeast(0) * editorLineHeightPx).toInt()
        scope.launch { vScroll.animateScrollTo(targetPx) }
    }

    fun insertAtCursor(snippet: String) {
        val t = field.text
        val s = field.selection.min.coerceIn(0, t.length)
        val e = field.selection.max.coerceIn(s, t.length)
        val next = t.substring(0, s) + snippet + t.substring(e)
        applyEdit(TextFieldValue(next, TextRange(s + snippet.length)), forcePushUndo = true)
    }

    fun indentSelectedLines(outdent: Boolean) {
        val t = field.text
        val s = field.selection.min.coerceIn(0, t.length)
        val e = field.selection.max.coerceIn(s, t.length)
        val lineStart = t.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = t.indexOf('\n', e).let { if (it < 0) t.length else it }
        val pad = " ".repeat(prefs.editorTabSize)
        val block = t.substring(lineStart, lineEnd)
        val updated = block.lines().joinToString("\n") { line ->
            if (!outdent) pad + line
            else when {
                line.startsWith(pad) -> line.removePrefix(pad)
                line.startsWith("\t") -> line.removePrefix("\t")
                else -> line.dropWhile { it == ' ' }.let { rest ->
                    val stripped = (line.length - rest.length).coerceAtMost(prefs.editorTabSize)
                    line.substring(stripped)
                }
            }
        }
        val next = t.substring(0, lineStart) + updated + t.substring(lineEnd)
        applyEdit(TextFieldValue(next, TextRange(lineStart, lineStart + updated.length)), forcePushUndo = true)
    }

    fun toggleLineComment() {
        val prefix = when (lang) {
            "python", "bash", "yaml", "toml", "conf", "dockerfile", "makefile", "ruby" -> "# "
            "sql", "lua" -> "-- "
            else -> "// "
        }
        val t = field.text
        val s = field.selection.min.coerceIn(0, t.length)
        val lineStart = t.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = t.indexOf('\n', s).let { if (it < 0) t.length else it }
        val line = t.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()
        val indent = line.substring(0, line.length - trimmed.length)
        val replaced = if (trimmed.startsWith(prefix.trim())) {
            indent + trimmed.removePrefix(prefix).removePrefix(prefix.trim())
        } else {
            indent + prefix + trimmed
        }
        val next = t.substring(0, lineStart) + replaced + t.substring(lineEnd)
        applyEdit(TextFieldValue(next, TextRange(lineStart + replaced.length)), forcePushUndo = true)
    }

    fun loadDocument(encodingOverride: TextEncoding? = null) {
        scope.launch {
            loading = true; error = null
            try {
                val node = runCatching { fm.stat(fileRef) }.getOrNull()
                totalFileBytes = node?.size ?: 0L
                if (totalFileBytes > LARGE_FILE_THRESHOLD_BYTES && encodingOverride == null) {
                    val staged = fm.stageReadableFile(fileRef)
                    largeStagedFile = staged
                    largeFileMode = true
                    largePageIdx = 0
                    largePage = withContext(Dispatchers.IO) { TextCodec.readTextPage(staged, 0, encoding = encoding) }
                } else {
                    largeFileMode = false
                    val (doc, rev) = fm.readTextDocument(fileRef, encodingOverride = encodingOverride, maxBytes = 8L * 1024 * 1024)
                    field = TextFieldValue(doc.text)
                    savedText = doc.text
                    diskRevision = rev
                    encoding = doc.encoding
                    lineEnding = doc.lineEnding
                    totalFileBytes = doc.totalBytes
                    undoStack.clear(); redoStack.clear()
                    if (initialLine != null && initialLine > 1) jumpToLine(initialLine)
                }
            } catch (e: Exception) {
                error = "打开文件失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun performSave(force: Boolean = false) {
        scope.launch {
            saving = true; error = null
            try {
                if (!force && diskRevision.isNotEmpty()) {
                    val latest = runCatching { fm.readTextDocument(fileRef, encodingOverride = encoding) }.getOrNull()
                    if (latest != null && latest.second != diskRevision && latest.first.text != savedText && latest.first.text != field.text) {
                        conflictDiskText = latest.first.text
                        saving = false
                        return@launch
                    }
                }
                val newRev = fm.writeTextDocument(fileRef, field.text, encoding, lineEnding)
                savedText = field.text
                diskRevision = newRev
                conflictDiskText = null
                app.conversations.notify("已保存 ${fileRef.name}")
                onSaved()
            } catch (e: Exception) {
                error = "保存失败：${e.message}"
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(fileRef) { loadDocument() }

    val requestClose = { if (dirty) confirmDiscard = true else onDismiss() }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(color = G.canvas, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                // 单层融合顶栏（56/64dp）：保留高频操作（撤销 / 查找 / 保存），其余收入「⋯」菜单，释放 40dp 编码区
                Row(
                    modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = requestClose) {
                        Icon(GaliaisIcons.ArrowBack, contentDescription = "关闭编辑器", tint = G.ink, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                (if (dirty) "● " else "") + fileRef.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (dirty) G.accent else G.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (fileRef.scope.isRootElevated) {
                                TagPill("ROOT", fg = G.warning, bg = G.warningTint)
                            }
                        }
                        Text(fileRef.path, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!largeFileMode) {
                        IconButton(
                            enabled = undoStack.isNotEmpty(),
                            onClick = {
                                val prev = undoStack.removeAt(undoStack.lastIndex)
                                redoStack.add(field)
                                field = prev
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(GaliaisIcons.Undo, contentDescription = "撤销", tint = if (undoStack.isNotEmpty()) G.ink else G.inkSubtle, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { showFindBar = !showFindBar }, modifier = Modifier.size(36.dp)) {
                            Icon(GaliaisIcons.Search, contentDescription = "查找替换", tint = if (showFindBar) G.accent else G.inkMuted, modifier = Modifier.size(18.dp))
                        }
                        CompactActionButton(
                            text = if (saving) "保存中" else "保存",
                            primary = dirty && !saving,
                            enabled = !saving && dirty,
                            onClick = { performSave(force = false) },
                        )
                    }
                    Box {
                        IconButton(onClick = { showEditorMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "编辑器工具", tint = G.inkMuted, modifier = Modifier.size(20.dp))
                        }
                        GaliaisDropdownMenu(expanded = showEditorMenu, onDismissRequest = { showEditorMenu = false }) {
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (!largeFileMode) {
                                    if (redoStack.isNotEmpty()) {
                                        GaliaisMenuRow("重做编辑", icon = GaliaisIcons.Refresh, onClick = {
                                            showEditorMenu = false
                                            val next = redoStack.removeAt(redoStack.lastIndex)
                                            undoStack.add(field)
                                            field = next
                                        })
                                    }
                                    GaliaisMenuRow("符号与标题大纲", icon = GaliaisIcons.ListCheck, onClick = { showEditorMenu = false; showOutline = true })
                                    GaliaisMenuRow("跳转至指定行", icon = GaliaisIcons.Search, onClick = { showEditorMenu = false; showGoToLine = true })
                                    GaliaisMenuRow(
                                        title = if (prefs.fileEditorWordWrap) "关闭自动换行（显示行号）" else "开启自动换行",
                                        icon = GaliaisIcons.FileLines,
                                        onClick = { showEditorMenu = false; app.settings.setFileEditorWordWrap(!prefs.fileEditorWordWrap) },
                                    )
                                    GaliaisMenuRow(
                                        title = "切换字号（当前 ${(fontScale * 100).toInt()}%）",
                                        icon = GaliaisIcons.Edit,
                                        onClick = {
                                            showEditorMenu = false
                                            fontScale = when {
                                                fontScale < 0.95f -> 1.0f
                                                fontScale < 1.15f -> 1.2f
                                                else -> 0.85f
                                            }
                                        },
                                    )
                                    if (lang == "json") {
                                        GaliaisMenuRow("格式化 JSON", icon = GaliaisIcons.Code, onClick = {
                                            showEditorMenu = false
                                            val t = field.text.trim()
                                            val formatted = runCatching {
                                                if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
                                            }.getOrElse { e -> error = "JSON 语法错误：${e.message}"; null }
                                            if (formatted != null) {
                                                error = null
                                                applyEdit(TextFieldValue(formatted), forcePushUndo = true)
                                            }
                                        })
                                    }
                                    if (visualKind != null) {
                                        GaliaisMenuRow(
                                            title = if (visualKind == ArtifactKind.LATEX) "离线 LaTeX 预览" else "实时可视化预览",
                                            icon = GaliaisIcons.Sparkles,
                                            tintOverride = G.artifact,
                                            onClick = {
                                                showEditorMenu = false
                                                previewArtifact = ChatArtifact(
                                                    id = "edit:${fileRef.path}",
                                                    identifier = fileRef.name.substringBeforeLast('.').ifBlank { "file" },
                                                    title = fileRef.name,
                                                    kind = visualKind,
                                                    language = fileRef.name.substringAfterLast('.', visualKind.extension),
                                                    content = field.text,
                                                )
                                            },
                                        )
                                    }
                                    if (onSendToAgent != null) {
                                        GaliaisMenuRow("引用选区发给 AI", icon = GaliaisIcons.AtSign, tintOverride = G.codex, onClick = {
                                            showEditorMenu = false
                                            val t = field.text
                                            val s = field.selection.min.coerceIn(0, t.length)
                                            val e = field.selection.max.coerceIn(s, t.length)
                                            val startLine = t.substring(0, s).count { it == '\n' } + 1
                                            val endLine = t.substring(0, e).count { it == '\n' } + 1
                                            val refText = if (e > s) "${fileRef.name}:L$startLine-L$endLine" else fileRef.name
                                            onDismiss()
                                            onSendToAgent(fileRef.parentPath, refText)
                                        })
                                    }
                                } else {
                                    GaliaisMenuRow("跳至日志末尾", icon = GaliaisIcons.ArrowDown, onClick = {
                                        showEditorMenu = false
                                        val sf = largeStagedFile ?: return@GaliaisMenuRow
                                        val lastIdx = ((largePage?.totalPages ?: 1) - 1).coerceAtLeast(0)
                                        largePageIdx = lastIdx
                                        scope.launch { largePage = withContext(Dispatchers.IO) { TextCodec.readTextPage(sf, lastIdx, encoding = encoding) } }
                                    })
                                    GaliaisMenuRow("切换为完整编辑（谨慎）", icon = GaliaisIcons.AlertTriangle, tintOverride = G.warning, onClick = {
                                        showEditorMenu = false
                                        loadDocument(encoding)
                                    })
                                }
                                if (onSaveExternal != null) {
                                    Hairline(Modifier.padding(vertical = 2.dp))
                                    GaliaisMenuRow("导出 / 另存到外部存储", icon = GaliaisIcons.Download, onClick = { showEditorMenu = false; onSaveExternal(fileRef) })
                                }
                            }
                        }
                    }
                }
                HeaderProgressSlot(loading || saving)

                // 查找与替换浮层（宽输入框 + 独立匹配控制条，窄屏清晰输入）
                if (showFindBar && !largeFileMode) {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = D.shape(10.dp),
                        color = G.surface,
                        border = BorderStroke(0.75.dp, G.border),
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = findQuery,
                                    onValueChange = { findQuery = it },
                                    placeholder = { Text("查找内容…", fontSize = 12.sp) },
                                    singleLine = true,
                                    shape = D.fieldShape,
                                    colors = galiaisTextFieldColors(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill("↑", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = if (matches.isNotEmpty()) ({
                                    activeMatchIdx = if (activeMatchIdx <= 0) matches.lastIndex else activeMatchIdx - 1
                                    val r = matches[activeMatchIdx]
                                    val targetLine = field.text.substring(0, r.first).count { it == '\n' } + 1
                                    field = field.copy(selection = TextRange(r.first, r.last + 1))
                                    scope.launch { vScroll.animateScrollTo(((targetLine - 3).coerceAtLeast(0) * editorLineHeightPx).toInt()) }
                                }) else null)
                                TagPill("↓", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = if (matches.isNotEmpty()) ({
                                    activeMatchIdx = if (activeMatchIdx >= matches.lastIndex) 0 else activeMatchIdx + 1
                                    val r = matches[activeMatchIdx]
                                    val targetLine = field.text.substring(0, r.first).count { it == '\n' } + 1
                                    field = field.copy(selection = TextRange(r.first, r.last + 1))
                                    scope.launch { vScroll.animateScrollTo(((targetLine - 3).coerceAtLeast(0) * editorLineHeightPx).toInt()) }
                                }) else null)
                                IconButton(onClick = { showFindBar = false }, modifier = Modifier.size(30.dp)) {
                                    Icon(GaliaisIcons.Close, contentDescription = "关闭查找", tint = G.inkMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    placeholder = { Text("替换为…", fontSize = 12.sp) },
                                    singleLine = true,
                                    shape = D.fieldShape,
                                    colors = galiaisTextFieldColors(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill("替换", fg = G.accent, bg = G.accentContainer, onClick = if (matches.isNotEmpty()) ({
                                    val r = matches[activeMatchIdx]
                                    val next = field.text.substring(0, r.first) + replaceQuery + field.text.substring(r.last + 1)
                                    applyEdit(TextFieldValue(next, TextRange(r.first + replaceQuery.length)), forcePushUndo = true)
                                }) else null)
                                TagPill("全部", fg = G.onAccent, bg = G.accent, onClick = if (matches.isNotEmpty()) ({
                                    val next = if (findRegex) {
                                        val opts = if (findCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
                                        runCatching { Regex(findQuery, opts).replace(field.text, replaceQuery) }.getOrDefault(field.text)
                                    } else field.text.replace(findQuery, replaceQuery, ignoreCase = !findCase)
                                    applyEdit(TextFieldValue(next, TextRange(0)), forcePushUndo = true)
                                    app.conversations.notify("已替换 ${matches.size} 处")
                                }) else null)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill(".* 正则", fg = if (findRegex) G.onAccent else G.inkMuted, bg = if (findRegex) G.accent else G.surfaceSubtle, border = if (findRegex) null else G.border, onClick = { findRegex = !findRegex })
                                TagPill("Aa 区分大小写", fg = if (findCase) G.onAccent else G.inkMuted, bg = if (findCase) G.accent else G.surfaceSubtle, border = if (findCase) null else G.border, onClick = { findCase = !findCase })
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (matches.isEmpty()) "0 / 0 处匹配" else "第 ${activeMatchIdx + 1} / ${matches.size} 处",
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    color = G.inkMuted,
                                )
                            }
                        }
                    }
                }

                error?.let {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = D.shape(8.dp), color = G.dangerTint) {
                        Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }

                // 主体编辑区（普通编辑态带行号与实时语法高亮 / 超大文件分页只读态）
                if (largeFileMode) {
                    val page = largePage
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.warningTint) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("超大文件分页浏览（${formatFileSize(totalFileBytes)} · 第 ${(page?.pageIndex ?: 0) + 1}/${page?.totalPages ?: 1} 页）", style = MaterialTheme.typography.labelSmall, color = G.ink, modifier = Modifier.weight(1f))
                                TagPill("上一页", fg = G.ink, bg = G.surface, onClick = if (largePageIdx > 0 && largeStagedFile != null) ({
                                    largePageIdx--
                                    val sf = largeStagedFile!!
                                    scope.launch { largePage = withContext(Dispatchers.IO) { TextCodec.readTextPage(sf, largePageIdx, encoding = encoding) } }
                                }) else null)
                                TagPill("下一页", fg = G.ink, bg = G.surface, onClick = if (largePage != null && largePageIdx < largePage!!.totalPages - 1 && largeStagedFile != null) ({
                                    largePageIdx++
                                    val sf = largeStagedFile!!
                                    scope.launch { largePage = withContext(Dispatchers.IO) { TextCodec.readTextPage(sf, largePageIdx, encoding = encoding) } }
                                }) else null)
                            }
                        }
                        Surface(Modifier.weight(1f).fillMaxWidth(), shape = D.codeShape, color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                                Text(
                                    page?.content.orEmpty(),
                                    fontFamily = Mono,
                                    fontSize = D.codeSize(12f * fontScale),
                                    lineHeight = D.codeSize(18f * fontScale),
                                    color = G.codeInk,
                                )
                            }
                        }
                    }
                } else {
                    val palette = D.code
                    val matchBg = G.warning.copy(alpha = 0.28f)
                    val activeMatchBg = G.accent.copy(alpha = 0.45f)
                    val visualTransformation = remember(lang, palette, matches, activeMatchIdx, matchBg, activeMatchBg) {
                        CodeEditorVisualTransformation(lang, palette, matches, activeMatchIdx, matchBg, activeMatchBg)
                    }
                    val lineCount = remember(field.text) { field.text.count { it == '\n' } + 1 }
                    val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }

                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = D.codeShape,
                        color = G.codeBg,
                        border = BorderStroke(0.75.dp, if (dirty) G.accent.copy(alpha = 0.55f) else G.codeBorder),
                    ) {
                        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                            // 仅在不自动换行时显示固定行号栏，避免长行软折行后行号与代码行错位
                            if (D.codeLineNumbers && !prefs.fileEditorWordWrap) {
                                Text(
                                    gutterText,
                                    fontFamily = Mono,
                                    fontSize = editorFontSize,
                                    lineHeight = editorLineHeight,
                                    color = G.codeMuted.copy(alpha = 0.65f),
                                    textAlign = TextAlign.End,
                                    modifier = Modifier
                                        .background(G.codeHeader.copy(alpha = 0.5f))
                                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 48.dp)
                                        .widthIn(min = 28.dp),
                                )
                            }
                            BasicTextField(
                                value = field,
                                onValueChange = { applyEdit(it) },
                                visualTransformation = visualTransformation,
                                textStyle = TextStyle(fontFamily = Mono, fontSize = editorFontSize, lineHeight = editorLineHeight, color = G.codeInk),
                                cursorBrush = SolidColor(G.accent),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .let { m -> if (prefs.fileEditorWordWrap) m else m.horizontalScroll(hScroll) }
                                    .padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 48.dp),
                            )
                        }
                    }

                    // 键盘上方编码辅助符号条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(G.surface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TagPill("Tab", fg = G.accent, bg = G.accentContainer, onClick = { indentSelectedLines(outdent = false) })
                        TagPill("反缩进", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { indentSelectedLines(outdent = true) })
                        TagPill("注释", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { toggleLineComment() })
                        ACCESSORY_SYMBOLS.forEach { sym ->
                            Surface(
                                onClick = { insertAtCursor(sym) },
                                shape = D.shape(7.dp),
                                color = G.surfaceSubtle,
                                border = BorderStroke(0.5.dp, G.border),
                            ) {
                                Text(sym, fontFamily = Mono, fontSize = 13.sp, color = G.ink, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                            }
                        }
                    }
                }

                // 底部状态栏（行:列 · 编码 · 换行符 · 语言）
                HeaderDivider()
                val cursorInfo = remember(field.text, field.selection) {
                    val pos = field.selection.start.coerceIn(0, field.text.length)
                    val prefix = field.text.substring(0, pos)
                    val line = prefix.count { it == '\n' } + 1
                    val col = pos - prefix.lastIndexOf('\n')
                    val total = field.text.count { it == '\n' } + 1
                    "L$line:C$col · $total 行"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(G.surfaceSubtle).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (largeFileMode) formatFileSize(totalFileBytes) else cursorInfo, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        encoding.label,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = G.accent,
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .background(G.surface)
                            .clickable { showEncodingSheet = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        lineEnding.label,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = G.accent,
                        modifier = Modifier
                            .clip(D.shape(6.dp))
                            .background(G.surface)
                            .clickable {
                                lineEnding = if (lineEnding == LineEnding.LF) LineEnding.CRLF else LineEnding.LF
                                app.conversations.notify("换行符已切换为 ${lineEnding.label}（保存后生效）")
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text((lang ?: "text").uppercase(), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                }
            }
        }
    }

    if (confirmDiscard) {
        GaliaisAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = "放弃未保存的修改？",
            text = { Text("${fileRef.name} 有未保存的更改。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("不保存退出", color = G.danger) }
                    TextButton(onClick = { confirmDiscard = false; performSave(force = false) }) { Text("立即保存", color = G.accent, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑", color = G.inkMuted) } },
        )
    }

    // 3-Way 冲突行级 Diff 对话框
    conflictDiskText?.let { disk ->
        val diffLines = remember(disk, field.text) { computeLineDiffLines(disk, field.text) }
        GaliaisAlertDialog(
            onDismissRequest = { conflictDiskText = null },
            title = "检测到外部修改冲突",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("编辑期间磁盘文件已变化（红色 - 为磁盘最新，绿色 + 为你的修改）：", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    Surface(Modifier.fillMaxWidth().heightIn(max = 260.dp), shape = D.codeShape, color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            diffLines.forEach { (prefix, content) ->
                                val col = when (prefix) { '+' -> G.diffAddedInk; '-' -> G.diffRemovedInk; else -> G.codeMuted }
                                val bg = when (prefix) { '+' -> G.diffAddedBg; '-' -> G.diffRemovedBg; else -> androidx.compose.ui.graphics.Color.Transparent }
                                Text("$prefix $content", fontFamily = Mono, fontSize = 11.sp, color = col, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        field = TextFieldValue(disk); savedText = disk; conflictDiskText = null
                        app.conversations.notify("已加载磁盘最新内容")
                    }) { Text("加载最新", color = G.accent) }
                    TextButton(onClick = { conflictDiskText = null; performSave(force = true) }) { Text("强制覆盖", color = G.danger, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = { TextButton(onClick = { conflictDiskText = null }) { Text("取消", color = G.inkMuted) } },
        )
    }

    if (showOutline) {
        val symbols = remember(field.text, lang) { extractCodeSymbols(field.text, lang) }
        GaliaisSheet(onDismissRequest = { showOutline = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("符号与标题大纲 (${symbols.size})", style = MaterialTheme.typography.titleMedium, color = G.ink)
                if (symbols.isEmpty()) {
                    Text("当前文件未提取到类、函数或标题符号。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        symbols.forEach { sym ->
                            Surface(
                                onClick = { showOutline = false; jumpToLine(sym.line) },
                                shape = D.shape(8.dp),
                                color = G.surfaceSubtle,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TagPill(sym.kind.uppercase(), fg = G.accent, bg = G.accentContainer)
                                    Text(sym.label, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("L${sym.line}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGoToLine) {
        var lineInput by remember { mutableStateOf("") }
        GaliaisAlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = "跳转至指定行",
            text = {
                OutlinedTextField(
                    value = lineInput,
                    onValueChange = { lineInput = it.filter(Char::isDigit) },
                    label = { Text("行号 (1..${field.text.count { it == '\n' } + 1})") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = lineInput.isNotBlank(), onClick = {
                    lineInput.toIntOrNull()?.let { jumpToLine(it) }
                    showGoToLine = false
                }) { Text("跳转", color = G.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("取消", color = G.inkMuted) } },
        )
    }

    if (showEncodingSheet) {
        GaliaisSheet(onDismissRequest = { showEncodingSheet = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择字符编码", style = MaterialTheme.typography.titleMedium, color = G.ink)
                Text("可切换编码重新解码查看，或直接按新编码保存。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                TextEncoding.entries.forEach { enc ->
                    Surface(
                        onClick = { showEncodingSheet = false; encoding = enc; loadDocument(encodingOverride = enc) },
                        shape = D.shape(10.dp),
                        color = if (enc == encoding) G.accentContainer else G.surfaceSubtle,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(enc.label, style = MaterialTheme.typography.bodyMedium, color = if (enc == encoding) G.accent else G.ink, modifier = Modifier.weight(1f))
                            if (enc == encoding) Text("当前", style = MaterialTheme.typography.labelSmall, color = G.accent)
                        }
                    }
                }
            }
        }
    }

    previewArtifact?.let { art ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(art),
            initialArtifactId = art.id,
            cwd = fileRef.parentPath,
            savePath = if (fileRef.scope.id.startsWith("linux")) fileRef.path else null,
            onDismiss = { previewArtifact = null },
        )
    }
}
