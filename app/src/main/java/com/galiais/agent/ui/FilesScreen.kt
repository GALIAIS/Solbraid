package com.galiais.agent.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.fs.ClipboardMode
import com.galiais.agent.engine.fs.ExternalStorageBridge
import com.galiais.agent.engine.fs.FileNode
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.LocalFsHelper
import com.galiais.agent.engine.fs.MediaFilter
import com.galiais.agent.engine.fs.MountInfo
import com.galiais.agent.engine.fs.SearchHit
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.ViewMode
import com.galiais.agent.engine.fs.ViewerCategory
import com.galiais.agent.engine.fs.compressInPlace
import com.galiais.agent.engine.fs.compressToZipUri
import com.galiais.agent.engine.fs.exportSingleFileToUri
import com.galiais.agent.engine.fs.extractArchive
import com.galiais.agent.engine.fs.importExternalFolderTree
import com.galiais.agent.engine.fs.importExternalUris
import com.galiais.agent.engine.fs.joinVfsPath
import com.galiais.agent.engine.fs.normalizeVfsPath
import com.galiais.agent.engine.fs.pasteClipboard
import com.galiais.agent.engine.fs.search
import com.galiais.agent.ui.files.BatchRenameDialog
import com.galiais.agent.ui.files.CodeEditorDialog
import com.galiais.agent.ui.files.CreateFileDialog
import com.galiais.agent.ui.files.CompactRootStatusBar
import com.galiais.agent.ui.files.CreateAndImportSheet
import com.galiais.agent.ui.files.DiskUsageAnalyzerSheet
import com.galiais.agent.ui.files.ExportFileSheet
import com.galiais.agent.ui.files.FileActionSheet
import com.galiais.agent.ui.files.FileBookmarksAndTrashSheet
import com.galiais.agent.ui.files.FileListRow
import com.galiais.agent.ui.files.FilePropertiesSheet
import com.galiais.agent.ui.files.FilterAndSortTray
import com.galiais.agent.ui.files.MediaGridTile
import com.galiais.agent.ui.files.MultiSelectActionBar
import com.galiais.agent.ui.files.PathAndUtilityBar
import com.galiais.agent.ui.files.ScopeSegmentedHeader
import com.galiais.agent.ui.files.TabCounterButton
import com.galiais.agent.ui.files.TabManagerSheet
import com.galiais.agent.ui.files.TransferDock
import com.galiais.agent.ui.files.TwoFileDiffSheet
import com.galiais.agent.ui.files.UniversalFileViewerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 全功能四域文件管理器主页面（Editorial Craft 轻盈分层架构 + 单手拇指 FAB + 全格式查看与专业代码编辑）。 */
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
    val fm = app.fileManager
    val runtime by app.runtime.snapshot.collectAsState()
    val rootAvailable by app.runtime.rootAvailable.collectAsState()
    val tabs by fm.tabs.collectAsState()
    val activeTabId by fm.activeTabId.collectAsState()
    val clipboard by fm.clipboard.collectAsState()
    val transfers by fm.transfers.collectAsState()
    val sortField by fm.sortField.collectAsState()
    val sortAsc by fm.sortAsc.collectAsState()
    val viewMode by fm.viewMode.collectAsState()
    val showHidden by fm.showHidden.collectAsState()
    val rootWriteUnlocked by fm.rootWriteUnlocked.collectAsState()
    val backupOnRootEdit by fm.backupOnRootEdit.collectAsState()
    val pendingSharedUris by fm.pendingSharedUris.collectAsState()

    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    val activeScope = activeTab.scope
    val path = activeTab.path
    val hasRoot = rootAvailable == true

    val legacyFiles = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var entries by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var gitStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mountInfo by remember { mutableStateOf<MountInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var mediaFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var showFilterTray by remember { mutableStateOf(false) }

    // 多选态
    var selectedPaths by remember(activeTabId, path) { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedPaths.isNotEmpty()

    // 搜索态（文件名搜索 + 全文 Grep 搜索）
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchContentMode by remember { mutableStateOf(false) }
    var searchRegex by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }

    // 编辑器与全格式查看器路由态
    var editorTarget by remember { mutableStateOf<Pair<FileRef, Int?>?>(null) }
    var viewerTarget by remember { mutableStateOf<FileRef?>(null) }

    // 各类抽屉与弹窗态
    var showHeaderMore by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showTabSheet by remember { mutableStateOf(false) }
    var actionSheetNode by remember { mutableStateOf<FileNode?>(null) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showDiskUsage by remember { mutableStateOf(false) }
    var showGit by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showManualPath by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var creatingDir by remember { mutableStateOf(false) }
    var creatingSymlink by remember { mutableStateOf(false) }
    var renamingNode by remember { mutableStateOf<FileNode?>(null) }
    var propertiesNode by remember { mutableStateOf<FileNode?>(null) }
    var exportTarget by remember { mutableStateOf<FileRef?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileRef>?>(null) }
    var batchRenameTargets by remember { mutableStateOf<List<FileRef>?>(null) }
    var compressTargets by remember { mutableStateOf<List<FileRef>?>(null) }
    var diffTargets by remember { mutableStateOf<Pair<FileRef, FileRef>?>(null) }
    var pendingSaveAsRef by remember { mutableStateOf<FileRef?>(null) }
    var pendingZipExportRefs by remember { mutableStateOf<List<FileRef>?>(null) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }

    // SAF 启动器
    val uploadFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fm.importExternalUris(uris, activeScope, path, extractZip = false) }
                .onSuccess { app.conversations.notify(it); reload++ }
                .onFailure { error = "上传失败：${it.message}" }
        }
    }
    val uploadZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fm.importExternalUris(listOf(uri), activeScope, path, extractZip = true) }
                .onSuccess { app.conversations.notify(it); reload++ }
                .onFailure { error = "ZIP 导入失败：${it.message}" }
        }
    }
    val uploadFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fm.importExternalFolderTree(treeUri, activeScope, path) }
                .onSuccess { app.conversations.notify(it); reload++ }
                .onFailure { error = "文件夹上传失败：${it.message}" }
        }
    }
    val mountSafLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val name = ExternalStorageBridge.queryDisplayName(context, treeUri)
        val mounted = fm.mountSafTree(treeUri, name)
        app.conversations.notify("已挂载外部目录：$name")
        fm.navigate(FileScope.ANDROID_STORAGE, "saf://${mounted.id}")
    }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = pendingSaveAsRef
        pendingSaveAsRef = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fm.exportSingleFileToUri(target, uri) }
                .onSuccess { app.conversations.notify("已另存为：${target.name}") }
                .onFailure { app.conversations.notify("另存为失败：${it.message}") }
        }
    }
    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val refs = pendingZipExportRefs
        pendingZipExportRefs = null
        if (uri == null || refs.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { fm.compressToZipUri(refs, uri, excludeBuildDirs = refs.size == 1 && refs.first().path == path) }
                .onSuccess { cnt -> app.conversations.notify("已打包导出 $cnt 个文件为 ZIP") }
                .onFailure { app.conversations.notify("导出 ZIP 失败：${it.message}") }
        }
    }

    LaunchedEffect(Unit) { app.runtime.probeRoot() }
    LaunchedEffect(requestedPath) {
        if (!requestedPath.isNullOrBlank() && requestedPath.startsWith("/")) {
            fm.navigate(FileScope.LINUX_WORKSPACE, requestedPath)
            onConsumeRequestedPath()
        }
    }

    // 加载目录内容、挂载点信息与 Git 角标（切换域时先清空上一域旧列表，避免短暂残影）
    var lastLoadedScope by remember { mutableStateOf(activeScope) }
    LaunchedEffect(activeScope, path, sortField, sortAsc, showHidden, reload, hasRoot) {
        if (lastLoadedScope != activeScope) {
            entries = emptyList()
            mountInfo = null
            gitStatuses = emptyMap()
            lastLoadedScope = activeScope
        }
        loading = true; error = null
        try {
            entries = fm.list(activeScope, path)
            mountInfo = if (activeScope.isRootElevated) fm.queryMountInfo(activeScope, path) else null
            gitStatuses = if (runtime.ready && activeScope in setOf(FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT)) {
                runCatching {
                    val res = legacyFiles.exec(listOf("git", "-c", "core.quotepath=false", "status", "--porcelain", "-uall"), path, timeoutMs = 4_000)
                    if (res.exitCode != 0) emptyMap()
                    else buildMap {
                        res.stdout.lineSequence().forEach { line ->
                            if (line.length >= 4) {
                                val code = line.substring(0, 2).trim().take(1)
                                val rel = line.substring(3).trim().removeSurrounding("\"").substringBefore('/')
                                if (rel.isNotBlank() && code.isNotBlank()) put(rel, code)
                            }
                        }
                    }
                }.getOrDefault(emptyMap())
            } else emptyMap()
        } catch (e: Exception) {
            error = e.message ?: "读取目录失败"
            entries = emptyList()
        } finally {
            loading = false
        }
    }

    // 双模搜索防抖
    LaunchedEffect(searching, searchQuery, searchContentMode, searchRegex, activeScope, path) {
        if (!searching || searchQuery.isBlank()) { searchResults = emptyList(); return@LaunchedEffect }
        delay(220)
        searchBusy = true
        searchResults = runCatching { fm.search(activeScope, path, searchQuery, contentSearch = searchContentMode, useRegex = searchRegex) }
            .getOrDefault(emptyList())
        searchBusy = false
    }

    fun openNodeSmart(node: FileNode) {
        if (node.isDirectory) {
            fm.navigate(node.scope, node.path)
            return
        }
        if (node.isSpecialFile) {
            app.conversations.notify("特殊设备或管道文件无法直接打开：${node.name}")
            return
        }
        fm.recordRecent(node.ref)
        val quickCat = TextCodec.classifyFile(node.name, ByteArray(0))
        if (quickCat != ViewerCategory.TEXT_CODE) {
            viewerTarget = node.ref
            return
        }
        // 无扩展名或未知后缀文件：先仅读 8KB 文件头执行魔数/二进制守卫，避免将 ELF/SQLite/压缩包强解码为文本
        val hasKnownTextExt = SyntaxHighlight.languageFromPath(node.name) != null || node.extension in setOf("txt", "log", "md", "markdown", "csv", "tsv")
        if (hasKnownTextExt || node.size == 0L) {
            editorTarget = node.ref to null
            return
        }
        scope.launch {
            val header = runCatching { fm.readHeaderBytes(node.ref, 8192) }.getOrDefault(ByteArray(0))
            val resolved = TextCodec.classifyFile(node.name, header)
            if (resolved == ViewerCategory.TEXT_CODE) editorTarget = node.ref to null else viewerTarget = node.ref
        }
    }

    val parentPath = remember(path) {
        if (path.startsWith("saf://")) path.substringBeforeLast('/') else normalizeVfsPath(path).substringBeforeLast('/').ifEmpty { "/" }
    }
    val canGoParent = path != "/" && !path.matches(Regex("^saf://[^/]+$"))
    BackHandler(enabled = selectionMode || searching || activeTab.backStack.isNotEmpty() || canGoParent) {
        when {
            selectionMode -> selectedPaths = emptySet()
            searching -> { searching = false; searchQuery = "" }
            activeTab.backStack.isNotEmpty() -> fm.goBack()
            canGoParent -> fm.navigate(activeScope, parentPath)
        }
    }

    val visibleEntries = remember(entries, mediaFilter, gitStatuses) {
        entries
            .filter { TextCodec.matchesMediaFilter(it, mediaFilter) }
            .map { n -> val st = gitStatuses[n.name]; if (st != null) n.copy(gitStatus = st) else n }
    }
    val dirEntries = remember(visibleEntries) { visibleEntries.filter { it.isDirectory } }
    val fileEntries = remember(visibleEntries) { visibleEntries.filter { !it.isDirectory } }
    val siblingImages = remember(entries) {
        entries.filter { !it.isDirectory && it.extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif") }.map { it.ref }
    }

    Box(modifier.fillMaxSize().galiaisCanvasBackground()) {
        Column(Modifier.fillMaxSize()) {
            // ── 第 1 层：极简对齐页头（统一 56/64dp，与会话/SSH 页视觉节奏完全一致） ──
            Row(
                modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(start = if (selectionMode || activeTab.backStack.isNotEmpty() || canGoParent) 6.dp else 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (selectionMode) {
                    IconButton(onClick = { selectedPaths = emptySet() }, modifier = Modifier.size(38.dp)) {
                        Icon(GaliaisIcons.Close, contentDescription = "退出多选", tint = G.ink, modifier = Modifier.size(18.dp))
                    }
                    Text("已选 ${selectedPaths.size} 项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.weight(1f))
                    TagPill("全选", fg = G.accent, bg = G.accentContainer, onClick = { selectedPaths = visibleEntries.map { it.path }.toSet() })
                    Spacer(Modifier.size(4.dp))
                    TagPill("反选", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = {
                        val all = visibleEntries.map { it.path }.toSet()
                        selectedPaths = all - selectedPaths
                    })
                    Spacer(Modifier.size(6.dp))
                } else {
                    if (activeTab.backStack.isNotEmpty() || canGoParent) {
                        IconButton(
                            onClick = { if (activeTab.backStack.isNotEmpty()) fm.goBack() else if (canGoParent) fm.navigate(activeScope, parentPath) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(GaliaisIcons.ArrowBack, contentDescription = "返回上级", tint = G.ink, modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("文件", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = G.ink)
                        if (visibleEntries.isNotEmpty()) {
                            Text("${visibleEntries.size} 项", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }, modifier = Modifier.size(36.dp)) {
                        Icon(if (searching) GaliaisIcons.Close else GaliaisIcons.Search, contentDescription = "搜索", tint = if (searching) G.accent else G.inkMuted, modifier = Modifier.size(18.dp))
                    }
                    TabCounterButton(count = tabs.size, onClick = { showTabSheet = true })
                    IconButton(onClick = { showBookmarksSheet = true }, modifier = Modifier.size(36.dp)) {
                        Icon(GaliaisIcons.BookOpen, contentDescription = "书签与回收站", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                    }
                    Box {
                        IconButton(onClick = { showHeaderMore = true }, modifier = Modifier.size(36.dp)) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多工具", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                        }
                        GaliaisDropdownMenu(expanded = showHeaderMore, onDismissRequest = { showHeaderMore = false }) {
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                GaliaisMenuRow("刷新当前目录", icon = GaliaisIcons.Refresh, onClick = { showHeaderMore = false; reload++ })
                                GaliaisMenuRow(if (showHidden) "隐藏 .点文件" else "显示 .点文件", icon = GaliaisIcons.Info, onClick = { showHeaderMore = false; fm.setShowHidden(!showHidden) })
                                GaliaisMenuRow("磁盘空间占用分析", icon = GaliaisIcons.Activity, onClick = { showHeaderMore = false; showDiskUsage = true })
                                GaliaisMenuRow("挂载外部手机目录 (SAF)", icon = GaliaisIcons.FolderSearch, onClick = { showHeaderMore = false; mountSafLauncher.launch(null) })
                                if (runtime.ready && activeScope in setOf(FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT)) {
                                    GaliaisMenuRow("在此打开终端", icon = GaliaisIcons.Terminal, onClick = { showHeaderMore = false; onOpenTerminal(path) { reload++ } })
                                    GaliaisMenuRow("Git 改动与提交", icon = GaliaisIcons.GitBranch, onClick = { showHeaderMore = false; showGit = true })
                                }
                                GaliaisMenuRow("SSH 远程主机", icon = GaliaisIcons.Server, onClick = { showHeaderMore = false; onOpenSsh() })
                                if (runtime.ready) {
                                    GaliaisMenuRow("实时 Web 预览", icon = GaliaisIcons.Globe, onClick = { showHeaderMore = false; showPreview = true })
                                }
                            }
                        }
                    }
                }
            }
            HeaderProgressSlot(loading || searchBusy)

            // ── 第 2 层：统一四域分段条（单标签时不占额外行） ──────────────────
            Spacer(Modifier.height(4.dp))
            ScopeSegmentedHeader(
                tabs = tabs,
                activeTabId = activeTabId,
                activeScope = activeScope,
                onSwitchScope = { sc ->
                    if (sc == FileScope.HOST_ROOT && !hasRoot) {
                        app.runtime.probeRoot()
                        app.conversations.notify("正在检测手机 Root 权限（su）…")
                    }
                    fm.switchScope(sc)
                },
                onSelectTab = fm::selectTab,
                onOpenNewTab = { fm.openTab(activeScope, path) },
                onCloseTab = fm::closeTab,
            )

            // ── 传输进度与上下文提示（全部按需显现，常态 0dp 零负担） ──────────
            TransferDock(jobs = transfers, onCancel = fm::cancelTransfer, onDismiss = fm::dismissTransfer)

            if (pendingSharedUris.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                    shape = D.shape(10.dp),
                    color = G.successTint,
                    border = BorderStroke(0.75.dp, G.success.copy(alpha = 0.5f)),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("收到 ${pendingSharedUris.size} 个分享文件，保存到此处？", style = MaterialTheme.typography.labelSmall, color = G.ink, modifier = Modifier.weight(1f))
                        TagPill("保存", fg = G.onAccent, bg = G.success, onClick = {
                            val uris = fm.consumeSharedUris()
                            scope.launch {
                                runCatching { fm.importExternalUris(uris, activeScope, path) }
                                    .onSuccess { app.conversations.notify(it); reload++ }
                                    .onFailure { app.conversations.notify("保存分享失败：${it.message}") }
                            }
                        })
                        TagPill("忽略", fg = G.inkMuted, bg = G.surface, onClick = { fm.consumeSharedUris() })
                    }
                }
            }

            // ── 双模搜索态 vs 正常目录浏览态 ──────────────────────────────────
            if (searching) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = if (searchContentMode) "在当前目录全文 Grep 搜索代码/正文…" else "按文件名递归搜索当前目录…",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectableChip("文件名", selected = !searchContentMode, onClick = { searchContentMode = false })
                        SelectableChip("全文 Grep", selected = searchContentMode, onClick = { searchContentMode = true })
                        SelectableChip("正则 .*", selected = searchRegex, onClick = { searchRegex = !searchRegex })
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (searchResults.isEmpty() && searchQuery.isNotBlank() && !searchBusy) {
                        item {
                            Text("未找到与「$searchQuery」匹配的结果。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(16.dp))
                        }
                    }
                    itemsIndexed(searchResults, key = { idx, h -> "${h.path}:${h.lineNumber}:$idx" }) { _, hit ->
                        val hitRef = FileRef(hit.scope, hit.path)
                        CrispCard(
                            padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                            onClick = {
                                if (hit.isDirectory) {
                                    searching = false; searchQuery = ""
                                    fm.navigate(hit.scope, hit.path)
                                } else if (hit.lineNumber != null) {
                                    editorTarget = hitRef to hit.lineNumber
                                } else {
                                    openNodeSmart(FileNode(hit.scope, hit.path, hit.name, isDirectory = false, size = hit.size))
                                }
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(hit.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                hit.lineNumber?.let { TagPill("L$it", fg = G.accent, bg = G.accentContainer) }
                            }
                            Text(hit.path, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            hit.previewLine?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, color = G.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            } else {
                // ── 第 2.5 层：一体化路径面包屑 + 筛选/视图工具栏 ──────────────
                PathAndUtilityBar(
                    path = path,
                    viewMode = viewMode,
                    filterActive = mediaFilter != MediaFilter.ALL,
                    filterExpanded = showFilterTray,
                    onNavigate = { fm.navigate(activeScope, it) },
                    onManualPath = { showManualPath = true },
                    onToggleFilterTray = { showFilterTray = !showFilterTray },
                    onCycleViewMode = {
                        val next = when (viewMode) {
                            ViewMode.DETAILED -> ViewMode.COMPACT
                            ViewMode.COMPACT -> ViewMode.GRID
                            ViewMode.GRID -> ViewMode.DETAILED
                        }
                        fm.setViewMode(next)
                    },
                )

                FilterAndSortTray(
                    visible = showFilterTray,
                    mediaFilter = mediaFilter,
                    sortField = sortField,
                    sortAsc = sortAsc,
                    showHidden = showHidden,
                    onSelectFilter = { mediaFilter = it },
                    onSelectSort = { fm.setSort(it) },
                    onToggleHidden = { fm.setShowHidden(!showHidden) },
                )
                if (!showFilterTray && mediaFilter != MediaFilter.ALL) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TagPill(
                            text = "已筛选：${mediaFilter.label}  ×",
                            fg = G.accent,
                            bg = G.accentContainer,
                            onClick = { mediaFilter = MediaFilter.ALL },
                        )
                    }
                }

                if (activeScope.isRootElevated) {
                    CompactRootStatusBar(
                        scope = activeScope,
                        mountInfo = mountInfo,
                        writeUnlocked = rootWriteUnlocked,
                        backupOnEdit = backupOnRootEdit,
                        onToggleUnlock = { fm.setRootWriteUnlocked(!rootWriteUnlocked) },
                        onToggleBackup = { fm.setBackupOnRootEdit(!backupOnRootEdit) },
                    )
                }

                if (activeScope == FileScope.ANDROID_STORAGE && !LocalFsHelper.hasAllFilesAccess(context) && !hasRoot && !path.startsWith("saf://")) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                        shape = D.shape(10.dp),
                        color = G.accentContainer.copy(alpha = 0.55f),
                        border = BorderStroke(0.75.dp, G.accent.copy(alpha = 0.4f)),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("授予所有文件权限或挂载 SAF 目录即可免 Root 读写", style = MaterialTheme.typography.labelSmall, color = G.ink, modifier = Modifier.weight(1f))
                            TagPill("去授权", fg = G.onAccent, bg = G.accent, onClick = { ExternalStorageBridge.openAllFilesPermissionSettings(context) })
                            TagPill("SAF", fg = G.accent, bg = G.surface, border = G.accent.copy(alpha = 0.4f), onClick = { mountSafLauncher.launch(null) })
                        }
                    }
                }

                if (activeScope == FileScope.LINUX_WORKSPACE && !runtime.ready && entries.isEmpty() && !loading && error != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                        shape = D.shape(10.dp),
                        color = G.surfaceSubtle,
                        border = BorderStroke(0.75.dp, G.border),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Linux 工作区尚未初始化，可一键启动运行环境", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(1f))
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = { app.runtime.start() })
                        }
                    }
                }

                PluginSlotActionBar(
                    app = app,
                    slotName = "files.toolbar",
                    cwd = path,
                    scrollable = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                    onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                )
                PluginSlotBannerColumn(
                    app = app,
                    slotName = "files.banner",
                    cwd = path,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                    onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                    onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                )

                error?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        shape = D.shape(10.dp),
                        color = G.dangerTint,
                        border = BorderStroke(0.75.dp, G.danger.copy(alpha = 0.45f)),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.weight(1f))
                            TagPill("重试", fg = G.onAccent, bg = G.danger, onClick = { app.runtime.probeRoot(); reload++ })
                        }
                    }
                }

                // ── 主文件区域（占屏幕 78%+ 黄金空间，懒加载 120fps 分组卡片） ───
                if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleEntries, key = { it.path }) { node ->
                            val sel = node.path in selectedPaths
                            MediaGridTile(
                                app = app,
                                node = node,
                                selectionMode = selectionMode,
                                selected = sel,
                                onClick = {
                                    if (selectionMode) selectedPaths = if (sel) selectedPaths - node.path else selectedPaths + node.path
                                    else openNodeSmart(node)
                                },
                                onLongClick = { selectedPaths = if (sel) selectedPaths - node.path else selectedPaths + node.path },
                                onOpenActions = { actionSheetNode = node },
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(activeTabId, activeScope, path) { listState.scrollToItem(0) }
                    val showSectionHeaders = dirEntries.isNotEmpty() && fileEntries.isNotEmpty()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
                    ) {
                        if (visibleEntries.isEmpty() && !loading && error == null) {
                            item(key = "empty") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(GaliaisIcons.Folder, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(34.dp))
                                    Text(if (mediaFilter == MediaFilter.ALL) "当前目录为空" else "该分类下暂无文件", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                                    Text("点击右下角 + 可新建文件、目录或从外部上传", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                                }
                            }
                        }
                        if (showSectionHeaders) {
                            item(key = "sec_dirs") { SectionLabel("文件夹 · ${dirEntries.size}") }
                        }
                        itemsIndexed(dirEntries, key = { _, n -> "d:${n.path}" }) { idx, node ->
                            GroupedFileItemWrapper(
                                isFirst = idx == 0,
                                isLast = idx == dirEntries.lastIndex,
                                showDividerBelow = idx < dirEntries.lastIndex,
                                compact = viewMode == ViewMode.COMPACT,
                            ) {
                                val sel = node.path in selectedPaths
                                FileListRow(
                                    node = node,
                                    viewMode = viewMode,
                                    selectionMode = selectionMode,
                                    selected = sel,
                                    onClick = { openNodeSmart(node) },
                                    onLongClick = { selectedPaths = if (sel) selectedPaths - node.path else selectedPaths + node.path },
                                    onOpenActions = { actionSheetNode = node },
                                )
                            }
                        }
                        if (showSectionHeaders) {
                            item(key = "sec_files") { SectionLabel("文件 · ${fileEntries.size}", modifier = Modifier.padding(top = 6.dp)) }
                        }
                        itemsIndexed(fileEntries, key = { _, n -> "f:${n.path}" }) { idx, node ->
                            GroupedFileItemWrapper(
                                isFirst = idx == 0,
                                isLast = idx == fileEntries.lastIndex,
                                showDividerBelow = idx < fileEntries.lastIndex,
                                compact = viewMode == ViewMode.COMPACT,
                            ) {
                                val sel = node.path in selectedPaths
                                FileListRow(
                                    node = node,
                                    viewMode = viewMode,
                                    selectionMode = selectionMode,
                                    selected = sel,
                                    onClick = { openNodeSmart(node) },
                                    onLongClick = { selectedPaths = if (sel) selectedPaths - node.path else selectedPaths + node.path },
                                    onOpenActions = { actionSheetNode = node },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 底部拇指黄金区：多选操作坞 OR 智能剪贴板粘贴条 + 悬浮 + FAB ──────
        if (selectionMode) {
            val chosenRefs = remember(selectedPaths, visibleEntries) {
                visibleEntries.filter { it.path in selectedPaths }.map { it.ref }
            }
            val chosenFiles = remember(selectedPaths, visibleEntries) {
                visibleEntries.filter { it.path in selectedPaths && !it.isDirectory }.map { it.ref }
            }
            MultiSelectActionBar(
                count = chosenRefs.size,
                canDiffTwo = chosenFiles.size == 2,
                onCopy = { fm.setClipboard(ClipboardMode.COPY, chosenRefs); selectedPaths = emptySet(); app.conversations.notify("已复制 ${chosenRefs.size} 项") },
                onCut = { fm.setClipboard(ClipboardMode.CUT, chosenRefs); selectedPaths = emptySet(); app.conversations.notify("已剪切 ${chosenRefs.size} 项") },
                onCompress = { compressTargets = chosenRefs; selectedPaths = emptySet() },
                onBatchRename = { batchRenameTargets = chosenRefs; selectedPaths = emptySet() },
                onDiffTwo = { if (chosenFiles.size == 2) { diffTargets = chosenFiles[0] to chosenFiles[1]; selectedPaths = emptySet() } },
                onExportZip = {
                    pendingZipExportRefs = chosenRefs
                    selectedPaths = emptySet()
                    exportZipLauncher.launch("solbraid_selection.zip")
                },
                onDelete = { deleteTargets = chosenRefs; selectedPaths = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (!searching) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                clipboard?.let { cb ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = G.success,
                        shadowElevation = 5.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val verb = if (cb.mode == ClipboardMode.CUT) "移动到此" else "粘贴到此"
                            Text(
                                "$verb (${cb.items.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = G.onAccent,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        runCatching { fm.pasteClipboard(activeScope, path) }
                                            .onSuccess { cnt -> app.conversations.notify("已粘贴 $cnt 项"); reload++ }
                                            .onFailure { app.conversations.notify("粘贴失败：${it.message}") }
                                    }
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(G.onAccent.copy(alpha = 0.2f))
                                    .clickable { fm.clearClipboard() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(GaliaisIcons.Close, contentDescription = "清空剪贴板", tint = G.onAccent, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
                Surface(
                    onClick = { showCreateSheet = true },
                    shape = D.fabShape,
                    color = G.accent,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(52.dp).semantics { contentDescription = "新建与上传" },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(GaliaisIcons.Add, contentDescription = null, tint = G.onAccent, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    // ── 单文件操作抽屉 / 新建导入抽屉 / 多标签管理抽屉 ────────────────────────

    actionSheetNode?.let { node ->
        val isZip = !node.isDirectory && node.extension in setOf("zip", "jar", "apk", "aar")
        val isTextFile = !node.isDirectory && TextCodec.classifyFile(node.name, ByteArray(0)) == ViewerCategory.TEXT_CODE
        FileActionSheet(
            node = node,
            isTextFile = isTextFile,
            onPrimaryOpen = { openNodeSmart(node) },
            onOpenEditor = { editorTarget = node.ref to null },
            onExport = { exportTarget = node.ref },
            onCopy = { fm.setClipboard(ClipboardMode.COPY, listOf(node.ref)); app.conversations.notify("已复制：${node.name}") },
            onCut = { fm.setClipboard(ClipboardMode.CUT, listOf(node.ref)); app.conversations.notify("已剪切：${node.name}") },
            onDuplicate = {
                scope.launch {
                    runCatching { fm.duplicate(node.ref, parentDir = path) }
                        .onSuccess { app.conversations.notify("已创建副本：${it.name}"); reload++ }
                        .onFailure { app.conversations.notify("创建副本失败：${it.message}") }
                }
            },
            onRename = { renamingNode = node },
            onCompress = { compressTargets = listOf(node.ref) },
            onExtract = if (isZip) ({
                scope.launch {
                    val subName = node.name.substringBeforeLast('.', "").ifBlank { "${node.name}_extracted" }
                    val targetDir = joinVfsPath(path, subName)
                    runCatching { fm.extractArchive(node.ref, activeScope, targetDir) }
                        .onSuccess { cnt -> app.conversations.notify("已解压 $cnt 个文件到 $subName"); reload++ }
                        .onFailure { app.conversations.notify("解压失败：${it.message}") }
                }
            }) else null,
            onProperties = { propertiesNode = node },
            onReference = if (activeScope in setOf(FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT)) ({
                onReferenceFile(path, node.name)
            }) else null,
            onStartMultiSelect = { selectedPaths = selectedPaths + node.path },
            onDelete = { deleteTargets = listOf(node.ref) },
            onDismiss = { actionSheetNode = null },
        )
    }

    if (showCreateSheet) {
        CreateAndImportSheet(
            scope = activeScope,
            path = path,
            runtimeReady = runtime.ready,
            onUploadMultiFiles = { uploadFilesLauncher.launch(arrayOf("*/*")) },
            onUploadFolderTree = { uploadFolderLauncher.launch(null) },
            onUploadZipExtract = { uploadZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
            onMountSafTree = { mountSafLauncher.launch(null) },
            onCreateFile = { creatingFile = true },
            onCreateDir = { creatingDir = true },
            onCreateSymlink = { creatingSymlink = true },
            onBookmarkDir = {
                fm.addBookmark(path.substringAfterLast('/').ifEmpty { activeScope.shortLabel }, activeScope, path)
                app.conversations.notify("已收藏当前目录")
            },
            onExportDirZip = {
                pendingZipExportRefs = listOf(FileRef(activeScope, path))
                val dirName = path.substringAfterLast('/').ifBlank { activeScope.id }
                exportZipLauncher.launch("$dirName.zip")
            },
            onUseAsProject = if (activeScope in setOf(FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT)) ({ onUseDirectory(path) }) else null,
            onOpenTerminal = null,
            onOpenGit = null,
            onOpenDiskUsage = null,
            onDismiss = { showCreateSheet = false },
        )
    }

    if (showTabSheet) {
        TabManagerSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = fm::selectTab,
            onCloseTab = fm::closeTab,
            onOpenNewTab = { fm.openTab(activeScope, path) },
            onDismiss = { showTabSheet = false },
        )
    }

    // ── 编辑器 / 查看器 / 属性 / 书签 / 磁盘分析 / 导出 / 插件浮层 ────────────

    editorTarget?.let { (ref, initLine) ->
        CodeEditorDialog(
            app = app,
            fileRef = ref,
            initialLine = initLine,
            onDismiss = { editorTarget = null },
            onSaved = { reload++ },
            onSendToAgent = if (ref.scope in setOf(FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT)) ({ cwd, reference ->
                editorTarget = null
                onReferenceFile(cwd, reference)
            }) else null,
            onSaveExternal = { r -> exportTarget = r },
        )
    }

    viewerTarget?.let { ref ->
        UniversalFileViewerDialog(
            app = app,
            fileRef = ref,
            siblingImages = siblingImages.ifEmpty { listOf(ref) },
            onOpenInEditor = { r -> viewerTarget = null; editorTarget = r to null },
            onSaveExternal = { r -> exportTarget = r },
            onExtracted = { reload++ },
            onDismiss = { viewerTarget = null },
        )
    }

    propertiesNode?.let { node ->
        FilePropertiesSheet(
            app = app,
            node = node,
            onJumpToPath = { sc, targetPath -> fm.navigate(sc, targetPath) },
            onChanged = { reload++ },
            onDismiss = { propertiesNode = null },
        )
    }

    if (showBookmarksSheet) {
        FileBookmarksAndTrashSheet(
            app = app,
            onNavigate = { sc, targetPath -> fm.navigate(sc, targetPath) },
            onOpenRecentFile = { ref -> openNodeSmart(FileNode(ref.scope, ref.path, ref.name, isDirectory = false)) },
            onMountSafTree = { mountSafLauncher.launch(null) },
            onRestored = { reload++ },
            onDismiss = { showBookmarksSheet = false },
        )
    }

    if (showDiskUsage) {
        DiskUsageAnalyzerSheet(
            app = app,
            scope = activeScope,
            path = path,
            onOpenDir = { sc, targetPath -> fm.navigate(sc, targetPath) },
            onDismiss = { showDiskUsage = false },
        )
    }

    batchRenameTargets?.let { refs ->
        BatchRenameDialog(app = app, refs = refs, onDone = { reload++ }, onDismiss = { batchRenameTargets = null })
    }

    diffTargets?.let { (a, b) ->
        TwoFileDiffSheet(app = app, first = a, second = b, onDismiss = { diffTargets = null })
    }

    exportTarget?.let { ref ->
        ExportFileSheet(
            app = app,
            fileRef = ref,
            onSaveAsDocument = { r -> pendingSaveAsRef = r; saveAsLauncher.launch(r.name) },
            onDismiss = { exportTarget = null },
        )
    }

    if (showGit) {
        GitOverviewSheet(files = legacyFiles, cwd = path, onNotify = app.conversations::notify, onDismiss = { showGit = false; reload++ })
    }
    if (showPreview) {
        LivePreviewSheet(app = app, onDismiss = { showPreview = false })
    }

    PluginSlotOverlayHost(
        app = app,
        cwd = path,
        openingSurface = openingPluginSurface,
        openingPanel = openingPluginPanel,
        onOpenSurface = { openingPluginSurface = it },
        onOpenPanel = { openingPluginPanel = it },
    )

    // ── 基础文本输入与确认弹窗 ──────────────────────────────────────────────

    if (creatingFile) {
        CreateFileDialog(
            app = app,
            fileScope = activeScope,
            currentDir = path,
            onCreated = { createdRef, openInEditor ->
                creatingFile = false
                reload++
                app.conversations.notify("已创建文件：${createdRef.name}")
                if (openInEditor) editorTarget = createdRef to null
            },
            onOpenExisting = { existingRef ->
                creatingFile = false
                editorTarget = existingRef to null
            },
            onDismiss = { creatingFile = false },
        )
    }

    if (creatingDir) {
        SingleInputDialog("新建文件夹", "文件夹名或相对路径（如 src/utils）", confirmLabel = "创建", onConfirm = { name ->
            scope.launch {
                runCatching { fm.createDirectoryIn(activeScope, path, name) }
                    .onSuccess { app.conversations.notify("已创建目录：$name"); reload++ }
                    .onFailure { app.conversations.notify("创建失败：${it.message}") }
            }
        }) { creatingDir = false }
    }

    if (creatingSymlink) {
        var linkName by remember { mutableStateOf("") }
        var targetPath by remember { mutableStateOf("") }
        GaliaisAlertDialog(
            onDismissRequest = { creatingSymlink = false },
            title = "创建符号链接 (ln -s)",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = linkName, onValueChange = { linkName = it }, label = { Text("链接名称") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = targetPath, onValueChange = { targetPath = it }, label = { Text("指向目标路径") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = linkName.isNotBlank() && targetPath.isNotBlank(), onClick = {
                    val ln = linkName.trim(); val tg = targetPath.trim(); creatingSymlink = false
                    scope.launch {
                        runCatching { fm.createSymlink(activeScope, path, ln, tg) }
                            .onSuccess { app.conversations.notify("已创建软链接：$ln"); reload++ }
                            .onFailure { app.conversations.notify("创建软链接失败：${it.message}") }
                    }
                }) { Text("创建", color = G.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { creatingSymlink = false }) { Text("取消", color = G.inkMuted) } },
        )
    }

    renamingNode?.let { node ->
        SingleInputDialog("重命名", "新名称", initialValue = node.name, onConfirm = { next ->
            scope.launch {
                runCatching { fm.rename(node.ref, next) }
                    .onSuccess { app.conversations.notify("已重命名为 $next"); reload++ }
                    .onFailure { app.conversations.notify("重命名失败：${it.message}") }
            }
        }) { renamingNode = null }
    }

    deleteTargets?.let { refs ->
        val canTrash = !activeScope.isRootElevated
        var moveToTrash by remember(refs) { mutableStateOf(canTrash) }
        GaliaisAlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = "确认删除 (${refs.size} 项)",
            titleColor = G.danger,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (refs.size == 1) "即将删除「${refs.first().name}」。" else "即将删除选中的 ${refs.size} 个项目（含目录内容）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.ink,
                    )
                    if (canTrash) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { moveToTrash = !moveToTrash },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GaliaisCheckbox(moveToTrash)
                            Text("移入回收站（可在书签 → 回收站中一键还原）", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        Text("Root 域删除会直接从文件系统永久移除，请谨慎确认。", style = MaterialTheme.typography.labelSmall, color = G.danger)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = refs; val trash = moveToTrash; deleteTargets = null
                    scope.launch {
                        var ok = 0; var lastErr: String? = null
                        toDelete.forEach { r ->
                            runCatching { fm.delete(r, moveToTrash = trash) }.onSuccess { ok++ }.onFailure { lastErr = it.message }
                        }
                        if (lastErr != null) app.conversations.notify("已删除 $ok 项，失败：$lastErr")
                        else app.conversations.notify(if (trash) "已移入回收站（$ok 项）" else "已永久删除 $ok 项")
                        reload++
                    }
                }) { Text(if (moveToTrash) "移入回收站" else "永久删除", color = G.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("取消", color = G.inkMuted) } },
        )
    }

    compressTargets?.let { refs ->
        val defaultName = if (refs.size == 1) "${refs.first().name}.zip" else "archive.zip"
        SingleInputDialog("压缩为 ZIP", "压缩包文件名", initialValue = defaultName, confirmLabel = "开始压缩", onConfirm = { zipName ->
            scope.launch {
                runCatching { fm.compressInPlace(refs, activeScope, path, zipName) }
                    .onSuccess { created -> app.conversations.notify("已压缩为：${created.name}"); reload++ }
                    .onFailure { app.conversations.notify("压缩失败：${it.message}") }
            }
        }) { compressTargets = null }
    }

    if (showManualPath) {
        SingleInputDialog("跳转到指定路径", "输入绝对路径（当前域：${activeScope.shortLabel}）", initialValue = path, confirmLabel = "前往", onConfirm = { target ->
            fm.navigate(activeScope, target)
        }) { showManualPath = false }
    }
}

/** 懒加载分组卡片行容器：响应主题 cardStyle，呈现无双倍粗边框的一体化分组卡片与动态内缩极细分割线。 */
@Composable
private fun GroupedFileItemWrapper(
    isFirst: Boolean,
    isLast: Boolean,
    showDividerBelow: Boolean,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val r = D.cardRadius
    val borderColor = G.border
    val borderWidth = D.borderWidth
    val showBorder = D.cardStyle !in setOf("filled", "elevated")
    val bgColor = when (D.cardStyle) {
        "filled" -> G.surfaceFilled
        "elevated" -> G.surfaceElevated
        else -> G.surface
    }
    val shape = RoundedCornerShape(
        topStart = if (isFirst) r else 0.dp,
        topEnd = if (isFirst) r else 0.dp,
        bottomStart = if (isLast) r else 0.dp,
        bottomEnd = if (isLast) r else 0.dp,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .let { mod ->
                if (!showBorder) mod else mod.drawWithContent {
                    drawContent()
                    val strokePx = borderWidth.toPx()
                    val half = strokePx / 2f
                    val rPx = r.toPx()
                    val topExt = if (isFirst) 0f else rPx + strokePx
                    val botExt = if (isLast) 0f else rPx + strokePx
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(half, half - topExt),
                        size = Size(size.width - strokePx, size.height - strokePx + topExt + botExt),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = strokePx),
                    )
                }
            },
        shape = shape,
        color = bgColor,
    ) {
        Column(Modifier.fillMaxWidth()) {
            content()
            if (showDividerBelow) {
                Hairline(Modifier.padding(start = if (compact) 52.dp else 58.dp, end = 12.dp))
            }
        }
    }
}

@Composable
private fun SingleInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth().imePadding(),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { val v = value.trim(); onDismiss(); onConfirm(v) }) {
                Text(confirmLabel, color = G.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) } },
    )
}

/** 识别可进入可视化画布预览的文件后缀（供文件页、编辑器与 Agent 文件预览共用）。 */
internal fun visualArtifactKindForFile(path: String): ArtifactKind? {
    val ext = path.substringAfterLast('/', path).substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> ArtifactKind.HTML
        "svg" -> ArtifactKind.SVG
        "mmd", "mermaid" -> ArtifactKind.MERMAID
        "tex", "latex" -> ArtifactKind.LATEX
        "md", "markdown" -> ArtifactKind.MARKDOWN
        else -> null
    }
}
