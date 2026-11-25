package com.galiais.agent.ui

import android.content.ClipData
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.DEFAULT_WORKSPACE
import com.galiais.agent.engine.FileEntry
import com.galiais.agent.engine.FilePreview
import com.galiais.agent.engine.FuzzyMatch
import com.galiais.agent.engine.WorkspaceArchive
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    app: GaliaisApplication,
    modifier: Modifier,
    requestedPath: String? = null,
    onConsumeRequestedPath: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    onOpenTerminal: (cwd: String, onClosed: () -> Unit) -> Unit = { _, _ -> },
    onUseDirectory: (String) -> Unit,
    onReferenceFile: (cwd: String, file: String) -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val clipboard = LocalClipboard.current
    var path by rememberSaveable { mutableStateOf(DEFAULT_WORKSPACE) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var preview by remember { mutableStateOf<Pair<String, FilePreview>?>(null) }
    var editingText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var creatingDir by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var studioTarget by remember { mutableStateOf<Pair<ChatArtifact, String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FuzzyMatch>>(emptyList()) }
    var showGit by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showHeaderMore by remember { mutableStateOf(false) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; error = null
            try {
                val msg = WorkspaceArchive.importUriToDirectory(context, uri, path, files)
                app.conversations.notify(msg)
                reload++
            } catch (e: Exception) {
                error = "导入失败：${e.message}"
            }
            loading = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; error = null
            try {
                val count = WorkspaceArchive.exportDirectoryToZipUri(context, uri, path, files)
                app.conversations.notify("已导出 $count 个文件为 ZIP")
            } catch (e: Exception) {
                error = "导出失败：${e.message}"
            }
            loading = false
        }
    }

    LaunchedEffect(requestedPath) {
        if (!requestedPath.isNullOrBlank() && requestedPath.startsWith("/")) {
            path = requestedPath
            preview = null
            editingText = null
            onConsumeRequestedPath()
        }
    }

    LaunchedEffect(path, runtime.ready, reload) {
        if (!runtime.ready) return@LaunchedEffect
        loading = true; error = null
        try { entries = files.list(path) } catch (failure: Exception) { error = failure.message; entries = emptyList() }
        loading = false
    }

    LaunchedEffect(searching, searchQuery, path, runtime.ready) {
        if (!searching || !runtime.ready || searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        searchResults = try { files.fuzzySearch(searchQuery.trim(), path) } catch (_: Exception) { emptyList() }
    }

    var confirmDiscardEdit by remember { mutableStateOf(false) }
    var saveConflict by remember { mutableStateOf<Pair<String, String>?>(null) }
    val origPreviewText = (preview?.second as? FilePreview.Text)?.content
    val hasUnsavedEdit = editingText != null && editingText != origPreviewText
    val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
    val goBack: () -> Unit = {
        when {
            editingText != null -> if (hasUnsavedEdit) confirmDiscardEdit = true else editingText = null
            preview != null -> preview = null
            searching -> { searching = false; searchQuery = "" }
            path != "/" -> path = parent
        }
    }
    BackHandler(enabled = editingText != null || preview != null || searching || path != "/") { goBack() }

    fun performSave(filePath: String, content: String, force: Boolean = false) {
        saving = true; error = null
        scope.launch {
            try {
                if (!force && origPreviewText != null) {
                    val latest = try { files.read(filePath) } catch (_: Exception) { null }
                    when (latest) {
                        is FilePreview.Text -> {
                            if (latest.content != origPreviewText && latest.content != content) {
                                saveConflict = filePath to latest.content
                                saving = false
                                return@launch
                            }
                        }
                        is FilePreview.Image, is FilePreview.Binary -> {
                            error = "保存中止：文件已被外部修改为非文本格式，请返回后重新打开"
                            saving = false
                            return@launch
                        }
                        null -> Unit
                    }
                }
                files.write(filePath, content)
                preview = filePath to FilePreview.Text(content, truncated = false)
                editingText = null
                saveConflict = null
                app.conversations.notify("已保存 ${filePath.substringAfterLast('/')}")
            } catch (e: Exception) { error = "保存失败：${e.message}" }
            saving = false
        }
    }

    fun openFile(fullPath: String, startEdit: Boolean = false) {
        scope.launch {
            loading = true; error = null
            try {
                val read = files.read(fullPath)
                preview = fullPath to read
                editingText = if (startEdit && read is FilePreview.Text && !read.truncated) read.content else null
            } catch (failure: Exception) { error = failure.message }
            loading = false
        }
    }

    Column(modifier.fillMaxSize().galiaisCanvasBackground().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp)
                .padding(start = if (preview != null || searching || path != "/") 4.dp else 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (preview != null || searching || path != "/") {
                IconButton(onClick = goBack) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "返回上一级", tint = G.ink, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                preview?.first?.substringAfterLast('/') ?: "工作区文件",
                style = MaterialTheme.typography.titleLarge,
                color = G.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (preview == null && runtime.ready) {
                IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.Search, contentDescription = "搜索文件", tint = if (searching) G.accent else G.inkMuted, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { onOpenTerminal(path) { reload++ } }, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.Terminal, contentDescription = "终端", tint = G.inkMuted, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { reload++ }, enabled = !loading, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.Refresh, contentDescription = "刷新文件", tint = if (!loading) G.inkMuted else G.inkSubtle, modifier = Modifier.size(19.dp))
                }
                Box {
                    IconButton(onClick = { showHeaderMore = true }, modifier = Modifier.size(36.dp)) {
                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多工具", tint = G.inkMuted, modifier = Modifier.size(19.dp))
                    }
                    GaliaisDropdownMenu(expanded = showHeaderMore, onDismissRequest = { showHeaderMore = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow(title = "Git 改动与提交", icon = GaliaisIcons.GitBranch, onClick = { showHeaderMore = false; showGit = true })
                            GaliaisMenuRow(title = "SSH 工作台", icon = GaliaisIcons.Server, onClick = { showHeaderMore = false; onOpenSsh() })
                            GaliaisMenuRow(title = "本地网页预览", icon = GaliaisIcons.Globe, onClick = { showHeaderMore = false; showPreview = true })
                        }
                    }
                }
            }
        }
        HeaderProgressSlot(loading || saving)

        if (!runtime.ready) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                CrispCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (runtime.busy) "正在准备运行环境" else "本地运行环境未就绪",
                            style = MaterialTheme.typography.titleSmall,
                            color = G.ink,
                        )
                        Text(
                            if (runtime.busy || runtime.state == "error") runtime.message
                            else "启动运行环境后即可浏览、搜索和编辑工作区项目文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (runtime.state == "error") G.danger else G.inkMuted,
                        )
                        if (runtime.busy) {
                            GaliaisProgressBar(thin = false, progress = runtime.progress)
                        } else {
                            PrimaryPillButton(
                                text = if (runtime.state == "error") "重新启动" else "立即启动运行环境",
                                enabled = runtime.bound,
                                onClick = app.runtime::start,
                            )
                        }
                    }
                }
            }
            return@Column
        }

        val shown = preview
        if (shown != null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(shown.first, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val draft = editingText
                if (draft != null) {
                    val prefs = RPrefs
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryPillButton("保存文件", enabled = !saving, onClick = {
                            performSave(shown.first, draft, force = false)
                        })
                        SecondaryPillButton("取消编辑", enabled = !saving, onClick = {
                            if (hasUnsavedEdit) confirmDiscardEdit = true else editingText = null
                        })
                        TagPill(
                            text = "缩进 +${prefs.editorTabSize}",
                            fg = G.inkMuted,
                            bg = G.surfaceSubtle,
                            border = G.border,
                            onClick = { editingText = draft + " ".repeat(prefs.editorTabSize) },
                        )
                        TagPill(
                            text = if (prefs.fileEditorWordWrap) "换行:开" else "换行:关",
                            fg = G.inkMuted,
                            bg = G.surfaceSubtle,
                            border = G.border,
                            onClick = { app.settings.setFileEditorWordWrap(!prefs.fileEditorWordWrap) },
                        )
                    }
                    error?.let {
                        Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                            Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = D.codeShape,
                        color = G.codeBg,
                        border = BorderStroke(1.dp, G.accent.copy(alpha = 0.6f)),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { editingText = it },
                            textStyle = TextStyle(
                                fontFamily = Mono,
                                fontSize = D.codeSize(12.5f),
                                lineHeight = D.codeSize(19f),
                                color = G.codeInk,
                            ),
                            cursorBrush = SolidColor(G.accent),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .let { m -> if (prefs.fileEditorWordWrap) m else m.horizontalScroll(rememberScrollState()) }
                                .padding(14.dp),
                        )
                    }
                } else {
                    val visualKind = remember(shown.first) { visualArtifactKindForFile(shown.first) }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (shown.second is FilePreview.Text && !(shown.second as FilePreview.Text).truncated) {
                            TagPill(
                                text = "编辑",
                                fg = G.onAccent,
                                bg = G.accent,
                                onClick = { editingText = (shown.second as FilePreview.Text).content },
                            )
                        }
                        if (shown.second is FilePreview.Text && visualKind != null) {
                            TagPill(
                                text = if (visualKind == ArtifactKind.LATEX) "离线编译预览" else "可视化预览",
                                fg = G.claude,
                                bg = G.claudeTint,
                                onClick = {
                                    val txt = (shown.second as FilePreview.Text).content
                                    val fname = shown.first.substringAfterLast('/')
                                    studioTarget = ChatArtifact(
                                        id = "file:${shown.first}",
                                        identifier = fname.substringBeforeLast('.').ifBlank { "file" },
                                        title = fname,
                                        kind = visualKind,
                                        language = fname.substringAfterLast('.', visualKind.extension),
                                        content = txt,
                                    ) to shown.first
                                },
                            )
                        }
                        TagPill(
                            text = "在新会话中引用",
                            fg = G.ink,
                            bg = G.surface,
                            border = G.borderStrong,
                            onClick = {
                                val dir = shown.first.substringBeforeLast('/', "").ifEmpty { "/" }
                                onReferenceFile(dir, shown.first.substringAfterLast('/'))
                            },
                        )
                        TagPill(
                            text = "复制路径",
                            fg = G.ink,
                            bg = G.surface,
                            border = G.borderStrong,
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipData.newPlainText("path", shown.first).toClipEntry())
                                    app.conversations.notify("已复制路径")
                                }
                            },
                        )
                    }
                    when (val content = shown.second) {
                        is FilePreview.Image -> {
                            val bmp = remember(content.bytes) {
                                runCatching {
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                                    BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                                }.getOrNull()
                            }
                            if (bmp == null) {
                                CrispCard(padding = PaddingValues(20.dp)) {
                                    Text("图片解码失败（${content.bytes.size} 字节）。", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    shape = D.codeShape,
                                    color = G.surface,
                                    border = BorderStroke(1.dp, G.border),
                                ) {
                                    Column(Modifier.fillMaxSize()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().background(G.surfaceSubtle).padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(content.mime.substringAfter('/').uppercase(), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.inkMuted)
                                            Spacer(Modifier.weight(1f))
                                            Text("${bmp.width} × ${bmp.height} · ${(content.bytes.size / 1024).coerceAtLeast(1)} KB", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                                        }
                                        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = shown.first.substringAfterLast('/'),
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is FilePreview.Binary -> {
                            CrispCard(padding = PaddingValues(20.dp)) {
                                Text("二进制文件，${content.bytes} 字节，无法预览文本。", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                            }
                        }
                        is FilePreview.Text -> {
                            val lang = remember(shown.first) { detectLanguage(shown.first) }
                            val palette = D.code
                            val highlighted = remember(content.content, lang, palette) { SyntaxHighlight.highlight(content.content, lang, palette) }
                            val lineCount = remember(content.content) { content.content.count { it == '\n' } + 1 }
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                shape = D.codeShape,
                                color = G.codeBg,
                                border = BorderStroke(1.dp, G.codeBorder),
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().background(G.codeHeader).padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text((lang ?: "text").uppercase(), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.codeMuted)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "$lineCount 行" + if (content.truncated) " · 仅显示前 256 KB" else "",
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = G.codeMuted,
                                        )
                                    }
                                    SelectionContainer {
                                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                            CodeContent(highlighted, contentPadding = 14.dp, softWrap = RPrefs.fileEditorWordWrap)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        if (searching) {
            SearchBar(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "在 $path 下搜索文件名…",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (searchQuery.isNotBlank() && searchResults.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("未找到匹配文件", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                        }
                    }
                }
                itemsIndexed(searchResults, key = { idx, match -> "${match.path}#$idx" }) { _, match ->
                    CrispCard(
                        onClick = {
                            val full = if (match.path.startsWith("/")) match.path else path.trimEnd('/') + "/" + match.path
                            openFile(full)
                        },
                        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(match.fileName.ifBlank { match.path.substringAfterLast('/') }, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(match.path, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@Column
        }

        BreadcrumbBar(path = path, onNavigate = { path = it })
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagPill("添加为项目", fg = G.accent, bg = G.accentContainer, onClick = { onUseDirectory(path) })
            TagPill("导入文件或 ZIP", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = { importLauncher.launch(arrayOf("*/*")) })
            TagPill("导出 ZIP", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = {
                val baseName = path.trimEnd('/').substringAfterLast('/').ifBlank { "workspace" }
                exportLauncher.launch("$baseName.zip")
            })
            TagPill("新建文件", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = { creatingFile = true })
            TagPill("新建文件夹", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = { creatingDir = true })
            PluginSlotActionBar(
                app = app,
                slotName = "files.toolbar",
                cwd = path,
                onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
            )
        }

        PluginSlotBannerColumn(
            app = app,
            slotName = "files.banner",
            cwd = path,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
            onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
        )

        error?.let {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), shape = D.shape(10.dp), color = G.dangerTint) {
                Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (path != "/") {
                item(key = "parent") {
                    FileRow(name = "..", badge = "↑", isDir = true, isFirst = true, isLast = entries.isEmpty(), onClick = { path = parent })
                }
            }
            itemsIndexed(entries, key = { _, e -> e.path }) { index, entry ->
                val isFirst = path == "/" && index == 0
                val isLast = index == entries.lastIndex
                FileRow(
                    name = entry.name,
                    badge = if (entry.directory) "DIR" else fileExtBadge(entry.name),
                    isDir = entry.directory,
                    isFirst = isFirst,
                    isLast = isLast,
                    onClick = {
                        if (entry.directory) path = entry.path
                        else openFile(entry.path)
                    },
                    onRename = { renaming = entry },
                    onDelete = { deleting = entry },
                    onReference = if (entry.directory) null else ({ onReferenceFile(path, entry.name) }),
                )
            }
        }
    }

    if (creatingFile) {
        TextInputDialog("新建文件", "", onDismiss = { creatingFile = false }) { name ->
            creatingFile = false
            val clean = name.trim()
            if (clean.isBlank() || clean.contains('/') || clean == "." || clean == "..") { error = "文件名无效"; return@TextInputDialog }
            if (entries.any { it.name == clean }) { error = "「$clean」已存在，请更换名称以免覆盖"; return@TextInputDialog }
            val full = path.trimEnd('/') + "/" + clean
            scope.launch {
                try {
                    files.write(full, "")
                    reload++
                    openFile(full, startEdit = true)
                } catch (failure: Exception) { error = failure.message }
            }
        }
    }

    if (confirmDiscardEdit) {
        AlertDialog(
            onDismissRequest = { confirmDiscardEdit = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("放弃未保存的更改？", color = G.ink) },
            text = { Text("当前文件编辑内容尚未保存，离开后修改将丢失。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDiscardEdit = false; editingText = null }) {
                    Text("放弃更改", color = G.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardEdit = false }) {
                    Text("继续编辑", color = G.inkMuted)
                }
            },
        )
    }

    if (creatingDir) {
        TextInputDialog("新建文件夹", "", onDismiss = { creatingDir = false }) { name ->
            creatingDir = false
            if (name.contains('/') || name == "." || name == "..") { error = "文件夹名称无效"; return@TextInputDialog }
            scope.launch {
                try { files.createDirectory(path.trimEnd('/') + "/" + name); reload++ } catch (failure: Exception) { error = failure.message }
            }
        }
    }

    renaming?.let { target ->
        TextInputDialog("重命名", target.name, onDismiss = { renaming = null }) { nextName ->
            renaming = null
            val clean = nextName.trim()
            if (clean.isBlank() || clean.contains('/') || clean == "." || clean == "..") {
                error = "名称无效"
                return@TextInputDialog
            }
            if (clean == target.name) return@TextInputDialog
            val dir = target.path.substringBeforeLast('/', "").ifEmpty { "/" }
            val dst = if (dir == "/") "/$clean" else "$dir/$clean"
            scope.launch {
                try {
                    files.rename(target.path, dst)
                    app.conversations.notify("已重命名为 $clean")
                    reload++
                } catch (failure: Exception) {
                    error = "重命名失败：${failure.message}"
                }
            }
        }
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("删除${if (target.directory) "文件夹" else "文件"}？", color = G.ink) },
            text = { Text("将删除 ${target.path}。此操作无法撤销。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = target; deleting = null
                    scope.launch {
                        try { files.remove(toDelete.path, toDelete.directory); reload++ } catch (failure: Exception) { error = failure.message }
                    }
                }) { Text("删除", color = G.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = G.inkMuted) } },
        )
    }

    saveConflict?.let { (conflictPath, latestDiskContent) ->
        AlertDialog(
            onDismissRequest = { saveConflict = null },
            containerColor = G.surface,
            title = { Text("文件已被外部修改", color = G.ink) },
            text = {
                Text(
                    "在你编辑期间，Agent 或终端已修改了 ${conflictPath.substringAfterLast('/')}。直接覆盖可能会丢失新写入的内容。",
                    color = G.inkMuted,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        saveConflict = null
                        preview = conflictPath to FilePreview.Text(latestDiskContent, truncated = false)
                        editingText = latestDiskContent
                        app.conversations.notify("已重新加载磁盘最新内容")
                    }) { Text("加载最新", color = G.accent, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = {
                        val draftToWrite = editingText
                        saveConflict = null
                        if (draftToWrite != null) performSave(conflictPath, draftToWrite, force = true)
                    }) { Text("强制覆盖", color = G.danger, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { saveConflict = null }) { Text("取消", color = G.inkMuted) }
            },
        )
    }

    PluginSlotOverlayHost(
        app = app,
        cwd = path,
        openingSurface = openingPluginSurface,
        openingPanel = openingPluginPanel,
        onOpenSurface = { openingPluginSurface = it },
        onOpenPanel = { openingPluginPanel = it },
    )
    if (showGit) GitOverviewSheet(files, path, onNotify = app.conversations::notify, onDismiss = { showGit = false; reload++ })
    if (showPreview) LivePreviewSheet(app, onDismiss = { showPreview = false })
    studioTarget?.let { (art, filePath) ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(art),
            initialArtifactId = art.id,
            cwd = path,
            savePath = filePath,
            onDismiss = {
                studioTarget = null
                openFile(filePath)
            },
        )
    }
}

@Composable
private fun BreadcrumbBar(path: String, onNavigate: (String) -> Unit) {
    val parts = remember(path) { path.split('/').filter { it.isNotEmpty() } }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BreadcrumbSegment("/", active = parts.isEmpty()) { onNavigate("/") }
        var acc = ""
        parts.forEachIndexed { idx, segment ->
            acc += "/$segment"
            val target = acc
            if (idx > 0) Text("/", fontFamily = Mono, fontSize = 12.sp, color = G.inkSubtle, modifier = Modifier.padding(horizontal = 2.dp))
            BreadcrumbSegment(segment, active = idx == parts.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun BreadcrumbSegment(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = Mono,
        fontSize = 12.5.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) G.ink else G.inkMuted,
        modifier = Modifier.clip(D.shape(6.dp)).clickable(enabled = !active, onClick = onClick).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    badge: String,
    isDir: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onReference: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(
        topStart = if (isFirst) D.radius(14.dp) else 0.dp,
        topEnd = if (isFirst) D.radius(14.dp) else 0.dp,
        bottomStart = if (isLast) D.radius(14.dp) else 0.dp,
        bottomEnd = if (isLast) D.radius(14.dp) else 0.dp,
    )
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = if (onDelete != null || onReference != null || onRename != null) ({ menu = true }) else null),
            shape = shape,
            color = G.surface,
            border = BorderStroke(0.5.dp, G.border),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = D.space(12.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val entryIcon = when {
                    isDir && name == ".." -> GaliaisIcons.ArrowBack
                    isDir -> GaliaisIcons.Folder
                    badge in setOf("KT", "JAVA", "JS", "MJS", "CJS", "TS", "TSX", "PY", "SH", "JSON", "TOML", "YAML", "YML", "XML", "HTML", "CSS", "RS", "GO", "C", "CPP", "MD") -> GaliaisIcons.Code
                    else -> GaliaisIcons.File
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(D.shape(8.dp))
                        .background(if (isDir) G.codexTint else G.surfaceSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        entryIcon,
                        contentDescription = null,
                        tint = if (isDir) G.codex else G.inkMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!isDir && badge != "FILE") {
                    TagPill(badge, fg = G.inkMuted, bg = G.surfaceSubtle)
                }
                if (isDir && name != "..") {
                    Icon(GaliaisIcons.ChevronRight, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(16.dp))
                }
            }
        }
        GaliaisDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                onReference?.let { GaliaisMenuRow(title = "在新会话中引用", icon = GaliaisIcons.AtSign, onClick = { menu = false; it() }) }
                onRename?.let { GaliaisMenuRow(title = "重命名", icon = GaliaisIcons.FilePen, onClick = { menu = false; it() }) }
                onDelete?.let { GaliaisMenuRow(title = "删除", icon = GaliaisIcons.Close, danger = true, onClick = { menu = false; it() }) }
            }
        }
    }
}

internal fun visualArtifactKindForFile(path: String): ArtifactKind? = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> ArtifactKind.HTML
    "svg" -> ArtifactKind.SVG
    "md", "markdown" -> ArtifactKind.MARKDOWN
    "tex", "latex" -> ArtifactKind.LATEX
    "mmd", "mermaid" -> ArtifactKind.MERMAID
    "csv", "tsv" -> ArtifactKind.CSV
    "jsx", "tsx" -> ArtifactKind.REACT
    else -> null
}

private fun fileExtBadge(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when {
        name.startsWith(".") && !name.drop(1).contains('.') -> "CFG"
        ext.isEmpty() || ext.length > 4 -> "FILE"
        else -> ext
    }
}

private fun detectLanguage(path: String): String? {
    val base = path.substringAfterLast('/')
    if (base in setOf(".bashrc", ".bash_logout", ".profile", ".zshrc", "Makefile", "Dockerfile")) return "sh"
    return SyntaxHighlight.languageFromPath(path)
}
