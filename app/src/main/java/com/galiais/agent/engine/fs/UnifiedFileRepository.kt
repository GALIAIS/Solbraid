package com.galiais.agent.engine.fs

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.galiais.agent.engine.AppSettings
import com.galiais.agent.engine.DEFAULT_WORKSPACE
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import com.galiais.agent.engine.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Solbraid 统一多域虚拟文件系统仓库：
 * - 管理四域存储（Linux 工作区 / Linux Root / 手机宿主 Root / 手机外部存储与 SAF 目录）；
 * - 容器内遇到权限受限目录时（Root 模式下）自动透明提权至常驻 `RootFsDaemon`，杜绝无权限报错；
 * - 提供多标签页、跨存储域剪贴板、后台流式传输队列、书签、回收站、编码读写、归档、全文搜索与磁盘分析。
 */
class UnifiedFileRepository(
    val app: Application,
    val runtime: RuntimeClient,
    val settings: AppSettings,
) {
    private val rootWriteUnlockedFlow = MutableStateFlow(false)
    val rootWriteUnlocked: StateFlow<Boolean> = rootWriteUnlockedFlow.asStateFlow()

    internal val rootDaemon = RootFsDaemon(app) { rootWriteUnlockedFlow.value }
    private val prefs = app.getSharedPreferences("solbraid-files-v2", Context.MODE_PRIVATE)
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private val tabsFlow = MutableStateFlow(
        listOf(FileTab(scope = FileScope.LINUX_WORKSPACE, path = settings.state.value.defaultCwd)),
    )
    val tabs: StateFlow<List<FileTab>> = tabsFlow.asStateFlow()

    private val activeTabIdFlow = MutableStateFlow(tabsFlow.value.first().id)
    val activeTabId: StateFlow<String> = activeTabIdFlow.asStateFlow()

    private val clipboardFlow = MutableStateFlow<FileClipboard?>(null)
    val clipboard: StateFlow<FileClipboard?> = clipboardFlow.asStateFlow()

    private val transfersFlow = MutableStateFlow<List<TransferJob>>(emptyList())
    val transfers: StateFlow<List<TransferJob>> = transfersFlow.asStateFlow()

    private val bookmarksFlow = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<FileBookmark>> = bookmarksFlow.asStateFlow()

    private val recentFilesFlow = MutableStateFlow<List<FileRef>>(emptyList())
    val recentFiles: StateFlow<List<FileRef>> = recentFilesFlow.asStateFlow()

    private val trashFlow = MutableStateFlow(loadTrash())
    val trashItems: StateFlow<List<TrashEntry>> = trashFlow.asStateFlow()

    private val safTreesFlow = MutableStateFlow(loadSafTrees())
    val mountedSafTrees: StateFlow<List<MountedSafTree>> = safTreesFlow.asStateFlow()

    private val pendingSharedUrisFlow = MutableStateFlow<List<Uri>>(emptyList())
    val pendingSharedUris: StateFlow<List<Uri>> = pendingSharedUrisFlow.asStateFlow()

    private val sortFieldFlow = MutableStateFlow(
        SortField.entries.firstOrNull { it.name == prefs.getString("sort.field", null) } ?: SortField.NAME,
    )
    val sortField: StateFlow<SortField> = sortFieldFlow.asStateFlow()

    private val sortAscFlow = MutableStateFlow(prefs.getBoolean("sort.asc", true))
    val sortAsc: StateFlow<Boolean> = sortAscFlow.asStateFlow()

    private val viewModeFlow = MutableStateFlow(
        ViewMode.entries.firstOrNull { it.name == prefs.getString("view.mode", null) } ?: ViewMode.DETAILED,
    )
    val viewMode: StateFlow<ViewMode> = viewModeFlow.asStateFlow()

    private val showHiddenFlow = MutableStateFlow(prefs.getBoolean("show.hidden", true))
    val showHidden: StateFlow<Boolean> = showHiddenFlow.asStateFlow()

    private val backupOnRootEditFlow = MutableStateFlow(prefs.getBoolean("root.backup", true))
    val backupOnRootEdit: StateFlow<Boolean> = backupOnRootEditFlow.asStateFlow()

    private val runtimeMode: String get() = settings.state.value.effectiveRuntimeMode
    internal val hasRoot: Boolean get() = runtime.rootAvailable.value == true
    val activeTab: FileTab get() = tabsFlow.value.firstOrNull { it.id == activeTabIdFlow.value } ?: tabsFlow.value.first()

    // ── 标签页与导航管理 ──────────────────────────────────────────────────

    fun selectTab(tabId: String) {
        if (tabsFlow.value.any { it.id == tabId }) activeTabIdFlow.value = tabId
    }

    fun openTab(scope: FileScope = activeTab.scope, path: String = scope.defaultRoot) {
        val norm = normalizeVfsPath(path)
        val tab = FileTab(scope = scope, path = norm)
        tabsFlow.value = (tabsFlow.value + tab).takeLast(8)
        activeTabIdFlow.value = tab.id
    }

    fun closeTab(tabId: String) {
        val current = tabsFlow.value
        if (current.size <= 1) return
        val next = current.filterNot { it.id == tabId }
        tabsFlow.value = next
        if (activeTabIdFlow.value == tabId) activeTabIdFlow.value = next.last().id
    }

    fun navigate(scope: FileScope, path: String) {
        val norm = normalizeVfsPath(path)
        updateActiveTab { cur ->
            if (cur.scope == scope && cur.path == norm) cur
            else cur.copy(
                scope = scope,
                path = norm,
                backStack = (cur.backStack + "${cur.scope.id}|${cur.path}").takeLast(32),
                forwardStack = emptyList(),
            )
        }
    }

    fun switchScope(scope: FileScope) {
        val targetPath = when (scope) {
            FileScope.LINUX_WORKSPACE -> settings.state.value.defaultCwd
            FileScope.ANDROID_STORAGE -> Environment.getExternalStorageDirectory().absolutePath
            else -> scope.defaultRoot
        }
        navigate(scope, targetPath)
    }

    fun goBack(): Boolean {
        val cur = activeTab
        val prev = cur.backStack.lastOrNull() ?: return false
        val prevScope = FileScope.of(prev.substringBefore('|'))
        val prevPath = prev.substringAfter('|', "/")
        updateActiveTab {
            it.copy(
                scope = prevScope,
                path = prevPath,
                backStack = it.backStack.dropLast(1),
                forwardStack = (it.forwardStack + "${it.scope.id}|${it.path}").takeLast(32),
            )
        }
        return true
    }

    fun goForward(): Boolean {
        val cur = activeTab
        val next = cur.forwardStack.lastOrNull() ?: return false
        val nextScope = FileScope.of(next.substringBefore('|'))
        val nextPath = next.substringAfter('|', "/")
        updateActiveTab {
            it.copy(
                scope = nextScope,
                path = nextPath,
                backStack = (it.backStack + "${it.scope.id}|${it.path}").takeLast(32),
                forwardStack = it.forwardStack.dropLast(1),
            )
        }
        return true
    }

    private fun updateActiveTab(transform: (FileTab) -> FileTab) {
        val id = activeTabIdFlow.value
        tabsFlow.value = tabsFlow.value.map { if (it.id == id) transform(it) else it }
    }

    // ── 视图偏好 / 剪贴板 / 书签 / SAF / 分享接收 ─────────────────────────

    fun setSort(field: SortField, ascending: Boolean = if (field == sortFieldFlow.value) !sortAscFlow.value else true) {
        sortFieldFlow.value = field
        sortAscFlow.value = ascending
        prefs.edit().putString("sort.field", field.name).putBoolean("sort.asc", ascending).apply()
    }

    fun setViewMode(mode: ViewMode) {
        viewModeFlow.value = mode
        prefs.edit().putString("view.mode", mode.name).apply()
    }

    fun setShowHidden(show: Boolean) {
        showHiddenFlow.value = show
        prefs.edit().putBoolean("show.hidden", show).apply()
    }

    fun setRootWriteUnlocked(unlocked: Boolean) { rootWriteUnlockedFlow.value = unlocked }

    fun setBackupOnRootEdit(enabled: Boolean) {
        backupOnRootEditFlow.value = enabled
        prefs.edit().putBoolean("root.backup", enabled).apply()
    }

    fun setClipboard(mode: ClipboardMode, items: List<FileRef>) {
        if (items.isEmpty()) return
        clipboardFlow.value = FileClipboard(mode, items.distinct())
    }

    fun clearClipboard() { clipboardFlow.value = null }

    fun recordRecent(ref: FileRef) {
        recentFilesFlow.value = (listOf(ref) + recentFilesFlow.value.filterNot { it == ref }).take(24)
    }

    fun enqueueSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingSharedUrisFlow.value = (pendingSharedUrisFlow.value + uris).distinct()
    }

    fun consumeSharedUris(): List<Uri> {
        val cur = pendingSharedUrisFlow.value
        pendingSharedUrisFlow.value = emptyList()
        return cur
    }

    fun addBookmark(title: String, scope: FileScope, path: String) {
        val norm = normalizeVfsPath(path)
        val existing = bookmarksFlow.value.filterNot { it.builtIn }
        if (existing.any { it.scope == scope && it.path == norm }) return
        val next = existing + FileBookmark(UUID.randomUUID().toString().take(8), title.ifBlank { norm.substringAfterLast('/').ifEmpty { "/" } }, scope, norm, false)
        saveCustomBookmarks(next)
    }

    fun removeBookmark(id: String) {
        saveCustomBookmarks(bookmarksFlow.value.filterNot { it.builtIn || it.id == id })
    }

    fun mountSafTree(uri: Uri, displayName: String): MountedSafTree {
        val id = UUID.randomUUID().toString().take(8)
        val entry = MountedSafTree(id, uri.toString(), displayName.ifBlank { "外部目录" })
        val next = safTreesFlow.value.filterNot { it.uriString == entry.uriString } + entry
        safTreesFlow.value = next
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        prefs.edit().putString("saf.trees", arr.toString()).apply()
        return entry
    }

    fun unmountSafTree(id: String) {
        val next = safTreesFlow.value.filterNot { it.id == id }
        safTreesFlow.value = next
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        prefs.edit().putString("saf.trees", arr.toString()).apply()
    }

    fun cancelTransfer(jobId: String) {
        cancelFlags[jobId]?.set(true)
    }

    fun dismissTransfer(jobId: String) {
        transfersFlow.value = transfersFlow.value.filterNot { it.id == jobId && !it.active }
    }

    internal fun physicalPathFor(scope: FileScope, virtualPath: String): String =
        LocalFsHelper.resolvePhysicalFile(app, scope, virtualPath, runtimeMode).absolutePath

    internal fun usesRootBackend(scope: FileScope): Boolean = when (scope) {
        FileScope.HOST_ROOT -> true
        FileScope.LINUX_ROOT -> runtimeMode == RUNTIME_MODE_ROOT
        FileScope.LINUX_WORKSPACE -> false
        FileScope.ANDROID_STORAGE -> false
    }

    internal fun assertWriteAllowed(scope: FileScope, virtualPath: String) {
        val norm = normalizeVfsPath(virtualPath)
        if (scope == FileScope.HOST_ROOT && rootDaemon.isProtectedSystemPath(norm) && !rootWriteUnlockedFlow.value) {
            throw IllegalStateException("系统保护分区（$norm）当前处于只读保护状态，请先在顶部保护条解锁「允许写入系统区」后再操作")
        }
        if (!isSafPath(scope, norm)) {
            val phys = File(physicalPathFor(scope, norm))
            val canon = runCatching { phys.canonicalPath }.getOrNull()
            if (canon != null && rootDaemon.isProtectedSystemPath(canon) &&
                !(scope == FileScope.HOST_ROOT && rootWriteUnlockedFlow.value)
            ) {
                throw IllegalStateException("目标规范路径落入系统保护分区（$canon），已拦截写入")
            }
        }
    }

    private fun builtInBookmarks(): List<FileBookmark> {
        val sdcard = Environment.getExternalStorageDirectory().absolutePath
        return listOf(
            FileBookmark("bi_ws", "默认工作区", FileScope.LINUX_WORKSPACE, DEFAULT_WORKSPACE, true),
            FileBookmark("bi_home", "Linux Home", FileScope.LINUX_WORKSPACE, "/home/agent", true),
            FileBookmark("bi_lroot", "Linux 根目录 /", FileScope.LINUX_ROOT, "/", true),
            FileBookmark("bi_sd", "手机内部存储", FileScope.ANDROID_STORAGE, sdcard, true),
            FileBookmark("bi_dl", "手机下载 Download", FileScope.ANDROID_STORAGE, "$sdcard/Download", true),
            FileBookmark("bi_dcim", "手机相册 DCIM", FileScope.ANDROID_STORAGE, "$sdcard/DCIM", true),
            FileBookmark("bi_hroot", "手机宿主 Root /", FileScope.HOST_ROOT, "/", true),
            FileBookmark("bi_adb", "Root 模块 /data/adb", FileScope.HOST_ROOT, "/data/adb", true),
        )
    }

    private fun loadBookmarks(): List<FileBookmark> {
        val custom = runCatching {
            val raw = prefs.getString("bookmarks.custom", null) ?: return@runCatching emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { FileBookmark.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return builtInBookmarks() + custom
    }

    private fun saveCustomBookmarks(custom: List<FileBookmark>) {
        val arr = JSONArray().apply { custom.forEach { put(it.toJson()) } }
        prefs.edit().putString("bookmarks.custom", arr.toString()).apply()
        bookmarksFlow.value = builtInBookmarks() + custom
    }

    private fun loadSafTrees(): List<MountedSafTree> = runCatching {
        val raw = prefs.getString("saf.trees", null) ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { MountedSafTree.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadTrash(): List<TrashEntry> = runCatching {
        val raw = prefs.getString("trash.entries", null) ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { TrashEntry.fromJson(arr.getJSONObject(it)) }
            .filterNot { Files.notExists(File(it.trashFilePath).toPath(), LinkOption.NOFOLLOW_LINKS) }
    }.getOrDefault(emptyList())

    internal fun saveTrash(entries: List<TrashEntry>) {
        trashFlow.value = entries
        val arr = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        prefs.edit().putString("trash.entries", arr.toString()).apply()
    }

    internal suspend fun <T> runTrackedTransfer(
        title: String,
        totalItems: Int = 1,
        totalBytes: Long = -1L,
        block: suspend (
            cancelled: AtomicBoolean,
            report: (item: String, doneItems: Int, bytesCopied: Long) -> Unit,
        ) -> T,
    ): T {
        val job = TransferJob(title = title, totalItems = totalItems, totalBytes = totalBytes)
        val cancel = AtomicBoolean(false)
        cancelFlags[job.id] = cancel
        transfersFlow.value = (transfersFlow.value + job).takeLast(12)
        val startedAt = System.currentTimeMillis()
        try {
            val result = block(cancel) { item, done, bytes ->
                val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.1)
                val speed = (bytes / elapsedSec).toLong()
                updateTransfer(job.id) {
                    it.copy(currentItem = item, doneItems = done, bytesCopied = bytes, speedBytesPerSec = speed)
                }
            }
            updateTransfer(job.id) { it.copy(phase = TransferPhase.COMPLETED, doneItems = it.totalItems) }
            return result
        } catch (e: InterruptedException) {
            updateTransfer(job.id) { it.copy(phase = TransferPhase.CANCELLED, error = "已取消") }
            throw e
        } catch (e: Exception) {
            updateTransfer(job.id) { it.copy(phase = TransferPhase.FAILED, error = e.message ?: "传输失败") }
            throw e
        } finally {
            cancelFlags.remove(job.id)
        }
    }

    private fun updateTransfer(id: String, transform: (TransferJob) -> TransferJob) {
        transfersFlow.value = transfersFlow.value.map { if (it.id == id) transform(it) else it }
    }

    // ── 核心目录枚举与元数据（自动透明提权，永不误报无权限） ────────────────

    suspend fun list(scope: FileScope, path: String, includeHidden: Boolean = false): List<FileNode> = withContext(Dispatchers.IO) {
        val norm = normalizeVfsPath(path)
        if (scope == FileScope.ANDROID_STORAGE && norm.startsWith("saf://")) {
            return@withContext listSafPath(norm, includeHidden)
        }
        val phys = physicalPathFor(scope, norm)
        val rawNodes = when {
            usesRootBackend(scope) -> rootDaemon.listDirectory(phys, scope, norm)
            scope == FileScope.ANDROID_STORAGE && !LocalFsHelper.hasAllFilesAccess(app) && hasRoot ->
                rootDaemon.listDirectory(phys, scope, norm)
            else -> {
                val local = LocalFsHelper.listLocalDirectoryOrNull(File(phys), scope, norm)
                when {
                    local != null && !(scope == FileScope.ANDROID_STORAGE && !LocalFsHelper.hasAllFilesAccess(app) && local.isEmpty()) -> local
                    hasRoot -> rootDaemon.listDirectory(phys, scope, norm)
                    scope == FileScope.ANDROID_STORAGE && !LocalFsHelper.hasAllFilesAccess(app) ->
                        throw IllegalStateException("尚未授予「所有文件访问权限」，请点击上方按钮授权或挂载外部 SAF 目录")
                    else -> throw IllegalStateException("无权访问目录或目录不存在：$norm")
                }
            }
        }
        if (includeHidden) rawNodes else applySortAndFilter(rawNodes)
    }

    internal fun parseSafRef(safPath: String): Triple<MountedSafTree, Uri, String> {
        val withoutScheme = safPath.removePrefix("saf://")
        val treeId = withoutScheme.substringBefore('/')
        val tree = safTreesFlow.value.firstOrNull { it.id == treeId }
            ?: throw IllegalStateException("外部目录挂载已失效，请重新选择目录")
        val treeUri = Uri.parse(tree.uriString)
        val docIdEncoded = withoutScheme.substringAfter('/', "")
        val docId = if (docIdEncoded.isEmpty()) DocumentsContract.getTreeDocumentId(treeUri) else Uri.decode(docIdEncoded)
        return Triple(tree, treeUri, docId)
    }

    internal fun isSafPath(scope: FileScope, path: String): Boolean =
        scope == FileScope.ANDROID_STORAGE && path.startsWith("saf://")

    private fun listSafPath(safPath: String, includeHidden: Boolean = false): List<FileNode> {
        val (tree, treeUri, docId) = parseSafRef(safPath)
        val children = LocalFsHelper.listSafChildren(app, treeUri, docId, "saf://${tree.id}")
        return if (includeHidden) children else applySortAndFilter(children)
    }

    private fun applySortAndFilter(nodes: List<FileNode>): List<FileNode> {
        val hidden = showHiddenFlow.value
        val filtered = if (hidden) nodes else nodes.filterNot { it.name.startsWith(".") }
        val field = sortFieldFlow.value
        val asc = sortAscFlow.value
        val cmp = Comparator<FileNode> { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            val raw = when (field) {
                SortField.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                SortField.SIZE -> a.size.compareTo(b.size)
                SortField.MODIFIED -> a.lastModifiedMs.compareTo(b.lastModifiedMs)
                SortField.EXTENSION -> a.extension.compareTo(b.extension).let { if (it != 0) it else a.name.lowercase().compareTo(b.name.lowercase()) }
            }
            if (asc) raw else -raw
        }
        return filtered.sortedWith(cmp)
    }

    suspend fun stat(ref: FileRef): FileNode = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            val (_, treeUri, docId) = parseSafRef(norm)
            return@withContext LocalFsHelper.statSafDocument(app, treeUri, docId, norm)
        }
        val phys = physicalPathFor(ref.scope, norm)
        if (usesRootBackend(ref.scope)) {
            rootDaemon.statNode(phys, ref.scope, norm)
        } else {
            runCatching { LocalFsHelper.statLocalFile(File(phys), ref.scope, norm) }
                .getOrElse { if (hasRoot) rootDaemon.statNode(phys, ref.scope, norm) else throw it }
        }
    }

    /** 快速判断文件或相对路径目标是否已存在（完整兼容四域与 saf://）。 */
    suspend fun exists(ref: FileRef): Boolean = withContext(Dispatchers.IO) {
        runCatching { stat(ref); true }.getOrDefault(false)
    }

    /** 判断目录下某个相对路径（支持 a/b/c.kt 与 saf://）是否已存在，若存在返回对应 FileRef。 */
    suspend fun findExistingInDirectory(scope: FileScope, parentDir: String, relativePath: String): FileRef? = withContext(Dispatchers.IO) {
        val segments = runCatching { splitValidatedRelativePath(relativePath) }.getOrNull() ?: return@withContext null
        val normDir = normalizeVfsPath(parentDir)
        if (isSafPath(scope, normDir)) {
            val (tree, treeUri, startDocId) = runCatching { parseSafRef(normDir) }.getOrNull() ?: return@withContext null
            var curDocId = startDocId
            for (idx in segments.indices) {
                val found = LocalFsHelper.findSafChildByName(app, treeUri, curDocId, segments[idx]) ?: return@withContext null
                if (idx < segments.lastIndex && !found.second) return@withContext null
                curDocId = found.first
            }
            return@withContext FileRef(scope, "saf://${tree.id}/${Uri.encode(curDocId)}")
        }
        val full = normalizeVfsPath(if (normDir == "/") "/${segments.joinToString("/")}" else "$normDir/${segments.joinToString("/")}")
        val ref = FileRef(scope, full)
        if (exists(ref)) ref else null
    }

    /** 仅读取文件前 maxBytes（默认 8KB）用于快速魔数/二进制守卫，避免盲读整个大文件。 */
    suspend fun readHeaderBytes(ref: FileRef, maxBytes: Int = 8192): ByteArray = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            val (_, treeUri, docId) = parseSafRef(norm)
            LocalFsHelper.openSafInput(app, treeUri, docId).use { input ->
                val buf = ByteArray(maxBytes)
                var got = 0
                while (got < maxBytes) {
                    val n = input.read(buf, got, maxBytes - got)
                    if (n <= 0) break
                    got += n
                }
                return@withContext buf.copyOf(got)
            }
        }
        val phys = File(physicalPathFor(ref.scope, norm))
        if (!usesRootBackend(ref.scope) && phys.isFile && phys.canRead()) {
            phys.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                var got = 0
                while (got < maxBytes) {
                    val n = input.read(buf, got, maxBytes - got)
                    if (n <= 0) break
                    got += n
                }
                return@withContext buf.copyOf(got)
            }
        }
        val staged = stageReadableFile(ref, maxBytes = maxBytes.toLong())
        staged.readBytes().let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }

    suspend fun queryMountInfo(scope: FileScope, path: String): MountInfo? = withContext(Dispatchers.IO) {
        if (!hasRoot && usesRootBackend(scope)) return@withContext null
        val phys = physicalPathFor(scope, path)
        if (hasRoot) runCatching { rootDaemon.queryMountInfo(phys) }.getOrNull() else null
    }

    suspend fun computeDirStats(ref: FileRef): DirStats = withContext(Dispatchers.IO) {
        val phys = physicalPathFor(ref.scope, ref.normalizedPath)
        if (usesRootBackend(ref.scope) || !File(phys).canRead()) {
            rootDaemon.computeDirStats(phys)
        } else {
            var bytes = 0L; var files = 0; var dirs = 0
            val visitedInodes = HashSet<Pair<Long, Long>>()
            val visitedPaths = HashSet<String>()
            fun walk(f: File, depth: Int) {
                if (depth > 64) return
                val (fLink, fInode) = LocalFsHelper.lstatIdentity(f)
                if (depth > 0 && fLink) return
                if (fInode != null && !visitedInodes.add(fInode)) return
                if (fInode == null) {
                    val canon = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                    if (!visitedPaths.add(canon)) return
                }
                val list = f.listFiles() ?: return
                for (c in list) {
                    val (cLink, _) = LocalFsHelper.lstatIdentity(c)
                    if (cLink) continue
                    if (c.isDirectory) { dirs++; walk(c, depth + 1) }
                    else if (c.isFile) { files++; bytes += c.length() }
                }
            }
            walk(File(phys), 0)
            DirStats(bytes, files, dirs)
        }
    }

    /** 将任意存储域的文件准备为本地可直接读的 File（普通文件零拷贝返回原路径，Root/SAF/伪文件高速暂存到缓存）。 */
    suspend fun stageReadableFile(ref: FileRef, maxBytes: Long = -1L): File = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            val (_, treeUri, docId) = parseSafRef(norm)
            val viewerDir = File(app.cacheDir, "file-viewer").apply { mkdirs() }
            val safeName = ExternalStorageBridge.sanitizeFilename(docId.substringAfterLast('/').ifBlank { "saf_file" })
            val dst = File(viewerDir, "${UUID.randomUUID().toString().take(6)}_$safeName")
            LocalFsHelper.openSafInput(app, treeUri, docId).use { input ->
                dst.outputStream().use { out ->
                    if (maxBytes > 0L) copyAtMost(input, out, maxBytes) else input.copyTo(out)
                }
            }
            com.galiais.agent.LinuxRuntime.pruneViewerCacheDir(app, keepFile = dst)
            return@withContext dst
        }
        val phys = File(physicalPathFor(ref.scope, norm))
        val isPseudoFs = norm.startsWith("/proc/") || norm.startsWith("/sys/") ||
            phys.absolutePath.startsWith("/proc/") || phys.absolutePath.startsWith("/sys/")
        if (!usesRootBackend(ref.scope) && !isPseudoFs && phys.isFile && phys.canRead() && (maxBytes <= 0L || phys.length() <= maxBytes)) {
            return@withContext phys
        }
        val viewerDir = File(app.cacheDir, "file-viewer").apply { mkdirs() }
        val safeName = ExternalStorageBridge.sanitizeFilename(ref.name)
        val outFile = File(viewerDir, "${UUID.randomUUID().toString().take(6)}_$safeName")
        val effectiveCap = when {
            maxBytes > 0L -> maxBytes
            isPseudoFs -> 1_048_576L
            else -> -1L
        }
        if (!usesRootBackend(ref.scope) && phys.exists() && !phys.isDirectory && phys.canRead()) {
            phys.inputStream().use { input ->
                outFile.outputStream().use { out ->
                    if (effectiveCap > 0L) copyAtMost(input, out, effectiveCap) else input.copyTo(out)
                }
            }
            com.galiais.agent.LinuxRuntime.pruneViewerCacheDir(app, keepFile = outFile)
            outFile
        } else if (hasRoot) {
            rootDaemon.stageFileToReadable(phys.absolutePath, outFile, effectiveCap)
            com.galiais.agent.LinuxRuntime.pruneViewerCacheDir(app, keepFile = outFile)
            outFile
        } else {
            throw IllegalStateException("无权读取文件：${ref.path}")
        }
    }

    private fun copyAtMost(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buf = ByteArray(65_536)
        var remain = maxBytes
        while (remain > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
            if (n <= 0) break
            output.write(buf, 0, n)
            remain -= n
        }
    }

    /**
     * 将本地暂存文件原子写入任意目标路径（四域全覆盖）：
     * - `saf://`：通过 DocumentsContract 流式写回；
     * - Root 域：通过 RootFsDaemon 透传软链、保留 mode/uid:gid/SELinux 并原子替换（保护分区先检只读挂载）；
     * - 本地普通域：透传软链不摧毁链接、保留原 POSIX 权限（不丢 `+x`）、同目录临时文件 `fsync + rename`。
     */
    suspend fun writeLocalFileTo(ref: FileRef, stagedSrc: File, createModeOctal: String? = null) = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            val (_, treeUri, docId) = parseSafRef(norm)
            LocalFsHelper.openSafOutput(app, treeUri, docId).use { out ->
                stagedSrc.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            }
            return@withContext
        }
        assertWriteAllowed(ref.scope, norm)
        val phys = File(physicalPathFor(ref.scope, norm))
        if (ref.scope == FileScope.HOST_ROOT && hasRoot) {
            val mount = runCatching { rootDaemon.queryMountInfo(phys.absolutePath) }.getOrNull()
            if (mount?.readOnly == true) {
                throw IllegalStateException("目标分区（${mount.mountPoint}）当前为只读挂载（ro），无法直接写入")
            }
        }
        val needRoot = usesRootBackend(ref.scope) ||
            (!phys.parentFile.let { it != null && it.canWrite() } && hasRoot) ||
            (phys.exists() && !phys.canWrite() && hasRoot)
        if (needRoot) {
            rootDaemon.commitStagedFileAtomic(
                stagedSrc = stagedSrc,
                physicalDst = phys.absolutePath,
                createBackup = backupOnRootEditFlow.value && ref.scope.isRootElevated,
                createModeOctal = createModeOctal ?: "644",
            )
        } else {
            val (targetFile, origMode) = LocalFsHelper.resolveWritableTargetPreservingSymlink(phys)
            targetFile.parentFile?.mkdirs()
            val tmp = File(targetFile.parentFile, ".${targetFile.name}.solbraid.${UUID.randomUUID().toString().take(6)}")
            val modeToApply = origMode ?: createModeOctal?.trim()?.toIntOrNull(8)
            try {
                stagedSrc.inputStream().use { input ->
                    tmp.outputStream().use { out ->
                        input.copyTo(out)
                        out.flush()
                        out.fd.sync()
                    }
                }
                if (modeToApply != null) {
                    runCatching { android.system.Os.chmod(tmp.absolutePath, modeToApply) }
                }
                if (!tmp.renameTo(targetFile)) {
                    stagedSrc.inputStream().use { input ->
                        targetFile.outputStream().use { out ->
                            input.copyTo(out)
                            out.flush()
                            out.fd.sync()
                        }
                    }
                    if (modeToApply != null) {
                        runCatching { android.system.Os.chmod(targetFile.absolutePath, modeToApply) }
                    }
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
    }

    suspend fun readTextDocument(ref: FileRef, encodingOverride: TextEncoding? = null, maxBytes: Long = 2L * 1024 * 1024): Pair<DecodedTextDocument, String> =
        withContext(Dispatchers.IO) {
            val node = runCatching { stat(ref) }.getOrNull()
            if (node?.isSpecialFile == true) {
                throw IllegalStateException("不支持以文本方式打开特殊设备或管道文件：${ref.name}")
            }
            val safeCap = maxBytes.coerceAtLeast(1L)
            val totalSize = node?.size ?: -1L
            val staged = stageReadableFile(ref, maxBytes = safeCap + 1L)
            val rawBytes = staged.inputStream().use { input ->
                val limit = (safeCap + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val out = java.io.ByteArrayOutputStream(minOf(limit, 65_536))
                val buf = ByteArray(32_768)
                var readTotal = 0
                while (readTotal < limit) {
                    val n = input.read(buf, 0, minOf(buf.size, limit - readTotal))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    readTotal += n
                }
                out.toByteArray()
            }
            val truncated = rawBytes.size > safeCap || totalSize > safeCap
            val bytes = if (rawBytes.size > safeCap) rawBytes.copyOf(safeCap.toInt()) else rawBytes
            val sha = TextCodec.sha256Hex(bytes)
            val effectiveSize = if (totalSize > 0L) totalSize else bytes.size.toLong()
            val revision = FileRevision(
                mtimeMs = node?.lastModifiedMs ?: staged.lastModified(),
                sizeBytes = effectiveSize,
                sha256 = sha,
            )
            val doc = TextCodec.decodeBytes(
                bytes = bytes,
                encodingOverride = encodingOverride,
                totalBytes = effectiveSize,
                truncated = truncated,
                revision = revision,
            )
            doc to revision.encode()
        }

    suspend fun writeTextDocument(
        ref: FileRef,
        text: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
        preserveTrailingNewline: Boolean? = null,
        createModeOctal: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val bytes = TextCodec.encodeText(text, encoding, lineEnding, preserveTrailingNewline)
        val tmp = rootDaemon.newStageFile("edit-")
        try {
            tmp.writeBytes(bytes)
            writeLocalFileTo(ref, tmp, createModeOctal = createModeOctal)
        } finally {
            tmp.delete()
        }
        val node = runCatching { stat(ref) }.getOrNull()
        val rev = FileRevision(
            mtimeMs = node?.lastModifiedMs ?: System.currentTimeMillis(),
            sizeBytes = node?.size ?: bytes.size.toLong(),
            sha256 = TextCodec.sha256Hex(bytes),
        )
        rev.encode()
    }

    /**
     * 超大文件零 OOM 局部字节窗口替换：
     * 流式拼装 `[0..startOffset) + replacementBytes + [endOffset..EOF)` 到临时文件后原子写回（完整保留权限/软链/SAF）。
     */
    suspend fun patchFileByteRange(
        ref: FileRef,
        startOffset: Long,
        endOffset: Long,
        replacementBytes: ByteArray,
        expectedSizeBytes: Long? = null,
        expectedMtimeMs: Long? = null,
    ): FileNode = withContext(Dispatchers.IO) {
        require(startOffset >= 0L && endOffset >= startOffset) { "无效的字节编辑区间：$startOffset..$endOffset" }
        val currentNode = runCatching { stat(ref) }.getOrNull()
        if (currentNode != null && expectedSizeBytes != null && expectedSizeBytes >= 0L && currentNode.size != expectedSizeBytes) {
            throw IllegalStateException("编辑期间文件大小已变更（磁盘 ${currentNode.size} B ≠ 预期 $expectedSizeBytes B），请重新加载该页后再保存")
        }
        if (currentNode != null && expectedMtimeMs != null && expectedMtimeMs > 0L && currentNode.lastModifiedMs > 0L && currentNode.lastModifiedMs != expectedMtimeMs) {
            throw IllegalStateException("编辑期间磁盘文件已被其他进程修改，请重新加载该页后再保存")
        }
        val stagedSrc = stageReadableFile(ref)
        val total = stagedSrc.length()
        val safeStart = startOffset.coerceIn(0L, total)
        val safeEnd = endOffset.coerceIn(safeStart, total)
        val patchedTmp = rootDaemon.newStageFile("patch-")
        try {
            java.io.RandomAccessFile(stagedSrc, "r").use { raf ->
                patchedTmp.outputStream().use { out ->
                    val buf = ByteArray(65_536)
                    // 1) 拷贝前缀 [0..safeStart)
                    var remain = safeStart
                    while (remain > 0L) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remain -= n
                    }
                    // 2) 写入替换段
                    if (replacementBytes.isNotEmpty()) out.write(replacementBytes)
                    // 3) 拷贝后缀 [safeEnd..total)
                    if (safeEnd < total) {
                        raf.seek(safeEnd)
                        while (true) {
                            val n = raf.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            writeLocalFileTo(ref, patchedTmp)
        } finally {
            patchedTmp.delete()
        }
        runCatching { stat(ref) }.getOrElse {
            FileNode(ref.scope, ref.normalizedPath, ref.name, isDirectory = false, size = total - (safeEnd - safeStart) + replacementBytes.size, lastModifiedMs = System.currentTimeMillis())
        }
    }

    // ── 基本目录/文件/权限/链接/回收站操作 ─────────────────────────────────

    suspend fun mkdir(scope: FileScope, path: String) = withContext(Dispatchers.IO) {
        val norm = normalizeVfsPath(path)
        assertWriteAllowed(scope, norm)
        val phys = File(physicalPathFor(scope, norm))
        if (usesRootBackend(scope) || !phys.mkdirs()) {
            if (phys.isDirectory) return@withContext
            if (hasRoot) rootDaemon.mkdir(phys.absolutePath) else error("无法创建文件夹：$norm")
        }
    }

    /** 在指定目录下创建单级或多级文件夹（支持 `a/b/c` 与 `saf://` 外部挂载目录）。 */
    suspend fun createDirectoryIn(scope: FileScope, parentDir: String, relativePath: String): FileRef = withContext(Dispatchers.IO) {
        val segments = splitValidatedRelativePath(relativePath)
        val normDir = normalizeVfsPath(parentDir)
        if (isSafPath(scope, normDir)) {
            val (tree, treeUri, startDocId) = parseSafRef(normDir)
            var curDocId = startDocId
            for (seg in segments) {
                val existing = LocalFsHelper.findSafChildByName(app, treeUri, curDocId, seg)
                curDocId = when {
                    existing != null && existing.second -> existing.first
                    existing != null -> throw IllegalStateException("路径冲突：「$seg」已是普通文件")
                    else -> LocalFsHelper.createSafDocument(app, treeUri, curDocId, DocumentsContract.Document.MIME_TYPE_DIR, seg)
                }
            }
            return@withContext FileRef(scope, "saf://${tree.id}/${Uri.encode(curDocId)}")
        }
        val fullPath = normalizeVfsPath(if (normDir == "/") "/${segments.joinToString("/")}" else "$normDir/${segments.joinToString("/")}")
        mkdir(scope, fullPath)
        FileRef(scope, fullPath)
    }

    /**
     * 安全创建文件（全四域 + `saf://` + 多级路径级联建目录 + 防重名清空三选一策略 + 初始内容与权限位预设）。
     * 默认采用 [CreateConflictPolicy.FAIL_IF_EXISTS]，遇到已有目标时抛出 [FileAlreadyExistsVfsException]，绝不静默截断原文件。
     */
    suspend fun createFileInDirectory(
        scope: FileScope,
        parentDir: String,
        relativePath: String,
        initialBytes: ByteArray = ByteArray(0),
        octalMode: String = "644",
        conflictPolicy: CreateConflictPolicy = CreateConflictPolicy.FAIL_IF_EXISTS,
    ): FileRef = withContext(Dispatchers.IO) {
        val segments = splitValidatedRelativePath(relativePath)
        val normDir = normalizeVfsPath(parentDir)
        val leafName = segments.last()
        val parentSegments = segments.dropLast(1)

        if (isSafPath(scope, normDir)) {
            val (tree, treeUri, startDocId) = parseSafRef(normDir)
            var curDocId = startDocId
            for (seg in parentSegments) {
                val existing = LocalFsHelper.findSafChildByName(app, treeUri, curDocId, seg)
                curDocId = when {
                    existing != null && existing.second -> existing.first
                    existing != null -> throw IllegalStateException("路径冲突：「$seg」已是普通文件")
                    else -> LocalFsHelper.createSafDocument(app, treeUri, curDocId, DocumentsContract.Document.MIME_TYPE_DIR, seg)
                }
            }
            val existingLeaf = LocalFsHelper.findSafChildByName(app, treeUri, curDocId, leafName)
            var targetDocId: String
            if (existingLeaf != null) {
                val existingRef = FileRef(scope, "saf://${tree.id}/${Uri.encode(existingLeaf.first)}")
                if (existingLeaf.second) throw IllegalStateException("同名文件夹已存在：$leafName")
                when (conflictPolicy) {
                    CreateConflictPolicy.FAIL_IF_EXISTS -> throw FileAlreadyExistsVfsException(existingRef)
                    CreateConflictPolicy.AUTO_RENAME -> {
                        val freeName = pickUniqueSafName(treeUri, curDocId, leafName)
                        targetDocId = LocalFsHelper.createSafDocument(app, treeUri, curDocId, TextCodec.guessMimeType(freeName), freeName)
                    }
                    CreateConflictPolicy.OVERWRITE_WITH_BACKUP -> {
                        val bakName = pickUniqueSafName(treeUri, curDocId, "$leafName.bak")
                        val bakDocId = LocalFsHelper.createSafDocument(app, treeUri, curDocId, "application/octet-stream", bakName)
                        LocalFsHelper.openSafInput(app, treeUri, existingLeaf.first).use { input ->
                            LocalFsHelper.openSafOutput(app, treeUri, bakDocId).use { out -> input.copyTo(out); out.flush() }
                        }
                        targetDocId = existingLeaf.first
                    }
                }
            } else {
                targetDocId = LocalFsHelper.createSafDocument(app, treeUri, curDocId, TextCodec.guessMimeType(leafName), leafName)
            }
            if (initialBytes.isNotEmpty() || conflictPolicy == CreateConflictPolicy.OVERWRITE_WITH_BACKUP) {
                LocalFsHelper.openSafOutput(app, treeUri, targetDocId).use { out ->
                    if (initialBytes.isNotEmpty()) out.write(initialBytes)
                    out.flush()
                }
            }
            return@withContext FileRef(scope, "saf://${tree.id}/${Uri.encode(targetDocId)}")
        }

        val targetParentDir = if (parentSegments.isEmpty()) normDir else {
            val sub = normalizeVfsPath(if (normDir == "/") "/${parentSegments.joinToString("/")}" else "$normDir/${parentSegments.joinToString("/")}")
            mkdir(scope, sub)
            sub
        }
        val baseRef = FileRef(scope, joinVfsPath(targetParentDir, leafName))
        createFile(baseRef, initialBytes, octalMode = octalMode, conflictPolicy = conflictPolicy)
    }

    private fun pickUniqueSafName(treeUri: Uri, parentDocId: String, name: String): String {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it == name || it.isEmpty()) "" else ".$it" }
        var idx = 1
        var candidate = name
        while (LocalFsHelper.findSafChildByName(app, treeUri, parentDocId, candidate) != null) {
            candidate = "$stem ($idx)$ext"
            idx++
        }
        return candidate
    }

    /** 独占/策略化创建指定路径文件（绝不静默清空已有文件）。 */
    suspend fun createFile(
        ref: FileRef,
        initialBytes: ByteArray = ByteArray(0),
        octalMode: String = "644",
        conflictPolicy: CreateConflictPolicy = CreateConflictPolicy.FAIL_IF_EXISTS,
    ): FileRef = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            throw IllegalStateException("在外部 SAF 目录新建文件请使用 createFileInDirectory")
        }
        assertWriteAllowed(ref.scope, norm)
        val safeMode = octalMode.trim().takeIf { Regex("^[0-7]{3,4}$").matches(it) } ?: "644"
        val modeInt = safeMode.toInt(8)

        fun buildUniqueRef(baseRef: FileRef): FileRef {
            val baseName = baseRef.name
            val stem = baseName.substringBeforeLast('.', baseName)
            val ext = baseName.substringAfterLast('.', "").let { if (it == baseName || it.isEmpty()) "" else ".$it" }
            var idx = 1
            var candidate = baseRef
            while (runCatching {
                    val p = File(physicalPathFor(candidate.scope, candidate.normalizedPath))
                    if (usesRootBackend(candidate.scope)) {
                        runCatching { rootDaemon.statNode(p.absolutePath, candidate.scope, candidate.normalizedPath); true }.getOrDefault(false)
                    } else p.exists()
                }.getOrDefault(false)
            ) {
                candidate = FileRef(baseRef.scope, joinVfsPath(baseRef.parentPath, "$stem ($idx)$ext"))
                idx++
            }
            return candidate
        }

        val targetRef = when (conflictPolicy) {
            CreateConflictPolicy.AUTO_RENAME -> buildUniqueRef(ref)
            else -> ref
        }
        val phys = File(physicalPathFor(targetRef.scope, targetRef.normalizedPath))
        val needRoot = usesRootBackend(targetRef.scope) ||
            (!phys.parentFile.let { it != null && it.canWrite() } && hasRoot) ||
            (phys.exists() && !phys.canWrite() && hasRoot)

        if (conflictPolicy == CreateConflictPolicy.OVERWRITE_WITH_BACKUP) {
            val alreadyExists = runCatching { stat(targetRef); true }.getOrDefault(false)
            if (alreadyExists) {
                val bakRef = buildUniqueRef(FileRef(targetRef.scope, joinVfsPath(targetRef.parentPath, "${targetRef.name}.bak")))
                val stagedOld = stageReadableFile(targetRef)
                writeLocalFileTo(bakRef, stagedOld)
            }
            val tmp = rootDaemon.newStageFile("create-")
            try {
                tmp.writeBytes(initialBytes)
                writeLocalFileTo(targetRef, tmp, createModeOctal = safeMode)
            } finally {
                tmp.delete()
            }
            return@withContext targetRef
        }

        // FAIL_IF_EXISTS 与 AUTO_RENAME 均使用底层原子独占创建（O_EXCL / Root EEXIST）
        if (needRoot) {
            val tmp = rootDaemon.newStageFile("create-")
            try {
                tmp.writeBytes(initialBytes)
                rootDaemon.commitStagedFileAtomic(
                    stagedSrc = tmp,
                    physicalDst = phys.absolutePath,
                    createBackup = false,
                    createModeOctal = safeMode,
                    failIfExists = true,
                )
            } catch (e: IllegalStateException) {
                if (e.message == "EEXIST") throw FileAlreadyExistsVfsException(targetRef)
                throw e
            } finally {
                tmp.delete()
            }
        } else {
            try {
                LocalFsHelper.createFileExclusiveLocal(phys, initialBytes, modeInt)
            } catch (_: FileAlreadyExistsException) {
                throw FileAlreadyExistsVfsException(targetRef)
            }
        }
        targetRef
    }

    suspend fun createEmptyFile(ref: FileRef): FileRef =
        createFile(ref, ByteArray(0), octalMode = "644", conflictPolicy = CreateConflictPolicy.FAIL_IF_EXISTS)

    suspend fun rename(ref: FileRef, newName: String): FileRef = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        if (isSafPath(ref.scope, norm)) {
            val (tree, treeUri, docId) = parseSafRef(norm)
            val newDocId = LocalFsHelper.renameSafDocument(app, treeUri, docId, newName.trim())
            return@withContext FileRef(ref.scope, "saf://${tree.id}/${Uri.encode(newDocId)}")
        }
        val dstPath = joinVfsPath(ref.parentPath, newName)
        assertWriteAllowed(ref.scope, norm)
        assertWriteAllowed(ref.scope, dstPath)
        require(!rootDaemon.isUnremovablePath(ref.scope, norm)) { "不允许重命名受保护的系统或工作区根目录" }
        val srcFile = File(physicalPathFor(ref.scope, norm))
        val srcCanon = runCatching { srcFile.canonicalPath }.getOrNull()
        if (srcCanon != null && ref.scope == FileScope.HOST_ROOT) {
            require(!rootDaemon.isUnremovablePath(FileScope.HOST_ROOT, srcCanon)) { "不允许重命名受保护的系统或工作区根目录" }
        }
        val dstFile = File(physicalPathFor(ref.scope, dstPath))
        if (usesRootBackend(ref.scope) || !srcFile.renameTo(dstFile)) {
            if (hasRoot) rootDaemon.rename(srcFile.absolutePath, dstFile.absolutePath)
            else error("重命名失败，请检查目标是否已存在或权限不足")
        }
        FileRef(ref.scope, dstPath)
    }

    suspend fun duplicate(ref: FileRef, parentDir: String = ref.parentPath): FileRef = withContext(Dispatchers.IO) {
        if (isSafPath(ref.scope, ref.path)) {
            val node = stat(ref)
            val stem = node.name.substringBeforeLast('.', node.name)
            val ext = node.name.removePrefix(stem)
            val target = prepareTransferTarget(ref.scope, parentDir, "${stem}_copy$ext", node.isDirectory, ConflictPolicy.RENAME)
                ?: error("无法创建副本")
            copySingleRef(ref, target, AtomicBoolean(false)) {}
            return@withContext target
        }
        val parent = ref.parentPath
        val base = ref.name
        val stem = base.substringBeforeLast('.', base)
        val ext = base.substringAfterLast('.', "").let { if (it == base || it.isEmpty()) "" else ".$it" }
        var idx = 1
        var candidate = joinVfsPath(parent, "${stem}_copy$ext")
        while (runCatching { stat(FileRef(ref.scope, candidate)); true }.getOrDefault(false)) {
            idx++
            candidate = joinVfsPath(parent, "${stem}_copy$idx$ext")
        }
        val dstRef = FileRef(ref.scope, candidate)
        copySingleRef(ref, dstRef, AtomicBoolean(false)) {}
        dstRef
    }

    suspend fun delete(ref: FileRef, moveToTrash: Boolean = false) = withContext(Dispatchers.IO) {
        val norm = ref.normalizedPath
        require(!moveToTrash || supportsTrash(ref)) { "此位置不支持回收站" }
        if (isSafPath(ref.scope, norm)) {
            val (_, treeUri, docId) = parseSafRef(norm)
            check(LocalFsHelper.deleteSafDocument(app, treeUri, docId)) { "删除外部 SAF 文档失败" }
            return@withContext
        }
        assertWriteAllowed(ref.scope, norm)
        require(!rootDaemon.isUnremovablePath(ref.scope, norm)) { "不允许删除系统核心目录或默认工作区根目录：$norm" }
        val phys = File(physicalPathFor(ref.scope, norm))
        val physCanon = runCatching { phys.canonicalPath }.getOrNull()
        if (physCanon != null && ref.scope == FileScope.HOST_ROOT) {
            require(!rootDaemon.isUnremovablePath(FileScope.HOST_ROOT, physCanon)) { "不允许删除系统核心目录或默认工作区根目录：$norm" }
        }
        if (moveToTrash) {
            val base = if (ref.scope == FileScope.ANDROID_STORAGE) {
                app.getExternalFilesDir(null) ?: error("外部存储不可用")
            } else app.filesDir
            val trashDir = File(base, "file-trash").apply { check(isDirectory || mkdirs()) { "无法创建回收站目录" } }
            val id = UUID.randomUUID().toString()
            val trashTarget = File(trashDir, "${id}_${ExternalStorageBridge.sanitizeFilename(ref.name)}")
            val isDir = phys.isDirectory
            val size = if (isDir) 0L else phys.length()
            check(phys.renameTo(trashTarget)) { "无法移入回收站，文件已保留" }
            val entry = TrashEntry(id, ref.scope, norm, ref.name, isDir, size, System.currentTimeMillis(), trashTarget.absolutePath)
            saveTrash(listOf(entry) + trashFlow.value)
            return@withContext
        }
        if (usesRootBackend(ref.scope)) {
            rootDaemon.remove(phys.absolutePath)
        } else {
            try {
                deleteLocalTree(phys)
            } catch (error: IOException) {
                if (hasRoot) rootDaemon.remove(phys.absolutePath) else throw error
            }
        }
    }

    internal fun supportsTrash(ref: FileRef): Boolean = !ref.scope.isRootElevated && !isSafPath(ref.scope, ref.normalizedPath)

    private fun deleteLocalTree(file: File) {
        val target = file.toPath()
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return
        // 不跟随软链接，避免删除目录时进入其他项目或存储位置。
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }

    suspend fun restoreTrash(entry: TrashEntry) = withContext(Dispatchers.IO) {
        val src = File(entry.trashFilePath)
        check(Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) { "回收站文件已不存在" }
        val dst = File(physicalPathFor(entry.originalScope, entry.originalPath))
        dst.parentFile?.mkdirs()
        val finalDst = if (dst.exists()) ExternalStorageBridge.uniqueFile(dst.parentFile!!, dst.name) else dst
        Files.move(src.toPath(), finalDst.toPath())
        saveTrash(trashFlow.value.filterNot { it.id == entry.id })
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val failed = mutableListOf<TrashEntry>()
        var failure: IOException? = null
        trashFlow.value.forEach { entry ->
            try { deleteLocalTree(File(entry.trashFilePath)) }
            catch (error: IOException) { failed += entry; failure = error }
        }
        saveTrash(failed)
        failure?.let { throw it }
    }

    suspend fun chmod(ref: FileRef, octal: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        assertWriteAllowed(ref.scope, ref.normalizedPath)
        val phys = physicalPathFor(ref.scope, ref.normalizedPath)
        if (usesRootBackend(ref.scope) || hasRoot) {
            rootDaemon.chmod(phys, octal, recursive)
        } else {
            val mode = octal.trim().toInt(8)
            android.system.Os.chmod(phys, mode)
        }
    }

    suspend fun chown(ref: FileRef, ownerSpec: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        assertWriteAllowed(ref.scope, ref.normalizedPath)
        check(hasRoot) { "修改文件属主（chown）需要 Root 权限" }
        rootDaemon.chown(physicalPathFor(ref.scope, ref.normalizedPath), ownerSpec, recursive)
    }

    suspend fun chcon(ref: FileRef, contextSpec: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        assertWriteAllowed(ref.scope, ref.normalizedPath)
        check(hasRoot) { "修改 SELinux 上下文（chcon）需要 Root 权限" }
        rootDaemon.chcon(physicalPathFor(ref.scope, ref.normalizedPath), contextSpec, recursive)
    }

    suspend fun createSymlink(scope: FileScope, dirPath: String, linkName: String, target: String) = withContext(Dispatchers.IO) {
        val linkVPath = joinVfsPath(dirPath, linkName)
        assertWriteAllowed(scope, linkVPath)
        val phys = physicalPathFor(scope, linkVPath)
        if (usesRootBackend(scope)) rootDaemon.createSymlink(target, phys)
        else runCatching { android.system.Os.symlink(target, phys) }.getOrElse {
            if (hasRoot) rootDaemon.createSymlink(target, phys) else throw it
        }
    }

    suspend fun computeHashes(ref: FileRef): FileHashes = withContext(Dispatchers.IO) {
        val f = stageReadableFile(ref)
        TextCodec.computeHashes(f)
    }
}
