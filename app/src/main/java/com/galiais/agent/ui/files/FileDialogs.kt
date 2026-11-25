package com.galiais.agent.ui.files

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.fs.DirStats
import com.galiais.agent.engine.fs.DiskUsageEntry
import com.galiais.agent.engine.fs.ExternalStorageBridge
import com.galiais.agent.engine.fs.FileHashes
import com.galiais.agent.engine.fs.FileNode
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.MountInfo
import com.galiais.agent.engine.fs.analyzeDiskUsage
import com.galiais.agent.engine.fs.batchRename
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.engine.fs.saveFileToDownloads
import com.galiais.agent.ui.CrispCard
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisAlertDialog
import com.galiais.agent.ui.GaliaisCheckbox
import com.galiais.agent.ui.GaliaisConfirmDialog
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisSheet
import com.galiais.agent.ui.Hairline
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.SecondaryPillButton
import com.galiais.agent.ui.SectionLabel
import com.galiais.agent.ui.SegmentedPillBar
import com.galiais.agent.ui.SelectableChip
import com.galiais.agent.ui.SettingsNavRow
import com.galiais.agent.ui.TagPill
import com.galiais.agent.ui.galiaisTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1. 文件属性、权限矩阵（chmod/chown/SELinux）、软链跳转与哈希校验弹窗。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilePropertiesSheet(
    app: GaliaisApplication,
    node: FileNode,
    onJumpToPath: (FileScope, String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fm = app.fileManager
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var dirStats by remember(node) { mutableStateOf<DirStats?>(null) }
    var statsLoading by remember(node) { mutableStateOf(false) }
    var mountInfo by remember(node) { mutableStateOf<MountInfo?>(null) }
    var hashes by remember(node) { mutableStateOf<FileHashes?>(null) }
    var hashing by remember(node) { mutableStateOf(false) }
    var expectedHash by remember { mutableStateOf("") }

    // 权限矩阵（12 位：SUID/SGID/Sticky + User rwx + Group rwx + Other rwx）
    val initialBits = remember(node.octalMode) { node.octalMode.toIntOrNull(8) ?: if (node.isDirectory) 0x1ED else 0x1A4 }
    var modeBits by remember(node) { mutableIntStateOf(initialBits) }
    var ownerDraft by remember(node) { mutableStateOf(if (node.owner.isNotBlank() && node.group.isNotBlank()) "${node.owner}:${node.group}" else node.owner) }
    var selinuxDraft by remember(node) { mutableStateOf(node.selinuxContext.orEmpty()) }
    var recursive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val octalString = remember(modeBits) { "%04o".format(modeBits and 0xFFF) }

    LaunchedEffect(node) {
        mountInfo = fm.queryMountInfo(node.scope, node.path)
        if (node.isDirectory) {
            statsLoading = true
            dirStats = runCatching { fm.computeDirStats(node.ref) }.getOrNull()
            statsLoading = false
        }
    }

    val fmtTime = remember(node.lastModifiedMs) {
        if (node.lastModifiedMs <= 0L) "—"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(node.lastModifiedMs))
    }

    GaliaisSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TagPill(node.scope.shortLabel, fg = if (node.scope.isRootElevated) G.warning else G.accent, bg = if (node.scope.isRootElevated) G.warningTint else G.accentContainer)
            }

            // 基本元数据卡片
            CrispCard(padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PropRow("路径", node.path)
                    PropRow(
                        "大小",
                        if (node.isDirectory) {
                            when {
                                statsLoading -> "统计中…"
                                dirStats != null -> "${formatFileSize(dirStats!!.totalBytes)}（${dirStats!!.fileCount} 文件 · ${dirStats!!.dirCount} 子目录）"
                                else -> "目录"
                            }
                        } else "${formatFileSize(node.size)}（${node.size} B）",
                    )
                    PropRow("修改时间", fmtTime)
                    PropRow("类型 / MIME", node.mimeType.ifBlank { if (node.isDirectory) "目录" else "文件" })
                    if (node.permissions.isNotBlank()) PropRow("当前权限", "${node.permissions} (${node.octalMode})")
                    if (node.owner.isNotBlank()) PropRow("属主 / 属组", "${node.owner}:${node.group}")
                    node.selinuxContext?.let { PropRow("SELinux", it) }
                    mountInfo?.let { m -> PropRow("挂载点", "${m.mountPoint} (${m.fsType} · ${if (m.readOnly) "只读 ro" else "读写 rw"})") }
                    node.symlinkTarget?.let { tgt ->
                        PropRow("链接目标", tgt + if (node.isBrokenSymlink) "（断链）" else "")
                        if (!node.isBrokenSymlink) {
                            TagPill("跳转到目标", fg = G.accent, bg = G.accentContainer, onClick = {
                                val resolved = if (tgt.startsWith("/")) tgt else "${node.ref.parentPath.trimEnd('/')}/$tgt"
                                onDismiss()
                                onJumpToPath(node.scope, if (node.isDirectory) resolved else resolved.substringBeforeLast('/').ifEmpty { "/" })
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagPill("复制路径", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("path", node.path).toClipEntry())
                                app.conversations.notify("已复制路径")
                            }
                        })
                    }
                }
            }

            // 权限矩阵与属主/SELinux 编辑器
            SectionLabel("权限与安全属性（chmod / chown / SELinux）")
            CrispCard(padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("八进制：$octalString", fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = G.accent)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("0644" to 0x1A4, "0755" to 0x1ED, "0600" to 0x180, "0777" to 0x1FF).forEach { (label, bits) ->
                                SelectableChip(label, selected = (modeBits and 0xFFF) == bits, onClick = { modeBits = bits })
                            }
                        }
                    }
                    PermBitRow("所有者 (U)", modeBits, 0x100, 0x080, 0x040) { modeBits = it }
                    PermBitRow("用户组 (G)", modeBits, 0x020, 0x010, 0x008) { modeBits = it }
                    PermBitRow("其他人 (O)", modeBits, 0x004, 0x002, 0x001) { modeBits = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PermToggle("SUID", modeBits and 0x800 != 0) { modeBits = modeBits xor 0x800 }
                        PermToggle("SGID", modeBits and 0x400 != 0) { modeBits = modeBits xor 0x400 }
                        PermToggle("Sticky", modeBits and 0x200 != 0) { modeBits = modeBits xor 0x200 }
                    }
                    if (node.isDirectory) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clickable { recursive = !recursive }) {
                            GaliaisCheckbox(recursive)
                            Text("递归应用到所有子目录与文件", style = MaterialTheme.typography.bodySmall, color = G.ink)
                        }
                    }
                    OutlinedTextField(
                        value = ownerDraft,
                        onValueChange = { ownerDraft = it },
                        label = { Text("属主:属组 (chown，例如 root:root)") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (node.scope.isRootElevated || node.selinuxContext != null) {
                        OutlinedTextField(
                            value = selinuxDraft,
                            onValueChange = { selinuxDraft = it },
                            label = { Text("SELinux 上下文 (chcon)") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrimaryPillButton("应用权限", enabled = !busy, onClick = {
                            scope.launch {
                                busy = true; error = null
                                runCatching { fm.chmod(node.ref, octalString, recursive) }
                                    .onSuccess { app.conversations.notify("已更新权限为 $octalString"); onChanged() }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        })
                        if (ownerDraft.isNotBlank() && ownerDraft != "${node.owner}:${node.group}") {
                            SecondaryPillButton("应用属主", enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true; error = null
                                    runCatching { fm.chown(node.ref, ownerDraft, recursive) }
                                        .onSuccess { app.conversations.notify("已修改属主为 $ownerDraft"); onChanged() }
                                        .onFailure { error = it.message }
                                    busy = false
                                }
                            })
                        }
                        if (selinuxDraft.isNotBlank() && selinuxDraft != node.selinuxContext.orEmpty()) {
                            SecondaryPillButton("应用 SELinux", enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true; error = null
                                    runCatching { fm.chcon(node.ref, selinuxDraft, recursive) }
                                        .onSuccess { app.conversations.notify("已更新 SELinux 上下文"); onChanged() }
                                        .onFailure { error = it.message }
                                    busy = false
                                }
                            })
                        }
                    }
                }
            }

            // 哈希校验（仅文件）
            if (!node.isDirectory && !node.isSpecialFile) {
                SectionLabel("文件完整性哈希校验")
                CrispCard(padding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryPillButton(if (hashing) "计算中…" else "计算文件哈希 (MD5 / SHA-256)", enabled = !hashing, onClick = {
                            scope.launch {
                                hashing = true
                                hashes = runCatching { fm.computeHashes(node.ref) }.onFailure { error = it.message }.getOrNull()
                                hashing = false
                            }
                        })
                        hashes?.let { h ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("MD5:    ${h.md5}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted)
                                    Text("SHA1:   ${h.sha1}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted)
                                    Text("SHA256: ${h.sha256}", fontFamily = Mono, fontSize = 10.5.sp, color = G.ink)
                                    Text("SHA512: ${h.sha512.take(64)}…", fontFamily = Mono, fontSize = 10.sp, color = G.inkSubtle)
                                }
                            }
                            OutlinedTextField(
                                value = expectedHash,
                                onValueChange = { expectedHash = it.trim() },
                                label = { Text("粘贴预期哈希自动比对") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (expectedHash.isNotBlank()) {
                                val exp = expectedHash.lowercase()
                                val match = exp == h.md5 || exp == h.sha1 || exp == h.sha256 || exp == h.sha512
                                TagPill(
                                    text = if (match) "✓ 校验一致（文件完整无篡改）" else "✕ 校验不匹配",
                                    fg = if (match) G.success else G.danger,
                                    bg = if (match) G.successTint else G.dangerTint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(0.32f))
        SelectionContainer(Modifier.weight(0.68f)) {
            Text(value, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink)
        }
    }
}

@Composable
private fun PermBitRow(label: String, bits: Int, rMask: Int, wMask: Int, xMask: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(1f))
        PermToggle("读 R", bits and rMask != 0) { onChange(bits xor rMask) }
        PermToggle("写 W", bits and wMask != 0) { onChange(bits xor wMask) }
        PermToggle("执 X", bits and xMask != 0) { onChange(bits xor xMask) }
    }
}

@Composable
private fun PermToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    TagPill(
        text = label,
        fg = if (checked) G.onAccent else G.inkMuted,
        bg = if (checked) G.accent else G.surfaceSubtle,
        border = if (checked) null else G.border,
        onClick = onToggle,
    )
}

/** 2. 书签、挂载的 SAF 外部目录、最近打开与回收站统一抽屉。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileBookmarksAndTrashSheet(
    app: GaliaisApplication,
    onNavigate: (FileScope, String) -> Unit,
    onOpenRecentFile: (FileRef) -> Unit,
    onMountSafTree: () -> Unit,
    onRestored: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fm = app.fileManager
    val bookmarks by fm.bookmarks.collectAsState()
    val recent by fm.recentFiles.collectAsState()
    val trash by fm.trashItems.collectAsState()
    val safTrees by fm.mountedSafTrees.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("bookmarks") }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("快捷导航 · 书签 · 回收站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
            SegmentedPillBar(
                options = listOf(
                    "bookmarks" to "书签 (${bookmarks.size})",
                    "recent" to "最近 (${recent.size})",
                    "trash" to "回收站 (${trash.size})",
                ),
                selected = tab,
                onSelect = { tab = it },
            )
            when (tab) {
                "bookmarks" -> {
                    bookmarks.forEach { bm ->
                        Surface(
                            onClick = { onDismiss(); onNavigate(bm.scope, bm.path) },
                            shape = D.shape(10.dp),
                            color = G.surfaceSubtle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(bm.title, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${bm.scope.shortLabel} · ${bm.path}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!bm.builtIn) {
                                    TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = { fm.removeBookmark(bm.id) })
                                }
                            }
                        }
                    }
                    SectionLabel("已挂载外部 SAF 目录（免 Root 跨区读写）")
                    safTrees.forEach { tree ->
                        Surface(
                            onClick = { onDismiss(); onNavigate(FileScope.ANDROID_STORAGE, "saf://${tree.id}") },
                            shape = D.shape(10.dp),
                            color = G.surfaceSubtle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tree.displayName, style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                                TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = { fm.unmountSafTree(tree.id) })
                            }
                        }
                    }
                    SecondaryPillButton("+ 挂载外部手机目录 (SAF)", onClick = { onDismiss(); onMountSafTree() })
                }
                "recent" -> {
                    if (recent.isEmpty()) {
                        Text("暂无最近打开的文件记录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    } else {
                        recent.forEach { ref ->
                            Surface(
                                onClick = { onDismiss(); onOpenRecentFile(ref) },
                                shape = D.shape(10.dp),
                                color = G.surfaceSubtle,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    Text(ref.name, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${ref.scope.shortLabel} · ${ref.path}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                "trash" -> {
                    if (trash.isEmpty()) {
                        Text("回收站为空。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            SecondaryPillButton("清空回收站", danger = true, onClick = { confirmEmptyTrash = true })
                        }
                        trash.forEach { item ->
                            Surface(shape = D.shape(10.dp), color = G.surfaceSubtle, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${item.originalPath} · ${formatFileSize(item.size)}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TagPill("还原", fg = G.accent, bg = G.accentContainer, onClick = {
                                        scope.launch {
                                            runCatching { fm.restoreTrash(item) }
                                                .onSuccess { app.conversations.notify("已还原 ${item.name}"); onRestored() }
                                                .onFailure { app.conversations.notify("还原失败：${it.message}") }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmEmptyTrash) {
        GaliaisConfirmDialog(
            title = "永久清空回收站？",
            message = "将彻底删除回收站中的 ${trash.size} 个文件/目录，操作不可撤销。",
            confirmText = "永久清空",
            danger = true,
            onConfirm = {
                confirmEmptyTrash = false
                scope.launch { fm.emptyTrash(); app.conversations.notify("已清空回收站") }
            },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

/** 3. 磁盘空间占用分析器（直观定位大文件夹与大文件）。 */
@Composable
fun DiskUsageAnalyzerSheet(
    app: GaliaisApplication,
    scope: FileScope,
    path: String,
    onOpenDir: (FileScope, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember(scope, path) { mutableStateOf(true) }
    var items by remember(scope, path) { mutableStateOf<List<DiskUsageEntry>>(emptyList()) }
    var error by remember(scope, path) { mutableStateOf<String?>(null) }

    LaunchedEffect(scope, path) {
        loading = true; error = null
        runCatching { app.fileManager.analyzeDiskUsage(scope, path) }
            .onSuccess { items = it }
            .onFailure { error = it.message }
        loading = false
    }

    val total = remember(items) { items.sumOf { it.bytes }.coerceAtLeast(1L) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("磁盘空间分析 · ${path.substringAfterLast('/').ifEmpty { "/" }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
            Text("总占用约 ${formatFileSize(items.sumOf { it.bytes })}（按大小降序，点击目录直接进入）", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
            items.take(40).forEach { entry ->
                val ratio = (entry.bytes.toFloat() / total.toFloat()).coerceIn(0.01f, 1f)
                Surface(
                    onClick = { if (entry.isDirectory) { onDismiss(); onOpenDir(scope, entry.path) } },
                    shape = D.shape(10.dp),
                    color = G.surfaceSubtle,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (entry.isDirectory) GaliaisIcons.Folder else GaliaisIcons.File,
                                contentDescription = null,
                                tint = if (entry.isDirectory) G.codex else G.inkMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(entry.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatFileSize(entry.bytes)} (${(ratio * 100).toInt()}%)", fontFamily = Mono, fontSize = 11.sp, color = G.accent)
                        }
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(5.dp), color = G.accent, trackColor = G.border)
                    }
                }
            }
        }
    }
}

/** 4. 批量重命名器（支持前后缀、查找替换/正则、序号递增与实时预览）。 */
@Composable
fun BatchRenameDialog(
    app: GaliaisApplication,
    refs: List<FileRef>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var addNumbering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val preview = remember(refs, findText, replaceText, prefix, suffix, useRegex, addNumbering) {
        val re = if (useRegex && findText.isNotEmpty()) runCatching { Regex(findText) }.getOrNull() else null
        refs.take(6).mapIndexed { idx, ref ->
            val old = ref.name
            val stem = old.substringBeforeLast('.', old)
            val ext = old.substringAfterLast('.', "").let { if (it == old || it.isEmpty()) "" else ".$it" }
            var nextStem = when {
                re != null -> stem.replace(re, replaceText)
                findText.isNotEmpty() -> stem.replace(findText, replaceText)
                else -> stem
            }
            nextStem = "$prefix$nextStem$suffix" + if (addNumbering) "_%03d".format(idx + 1) else ""
            old to ((nextStem.trim() + ext).ifBlank { old })
        }
    }

    GaliaisAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "批量重命名 (${refs.size} 项)",
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("前缀") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = suffix, onValueChange = { suffix = it }, label = { Text("后缀") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = findText, onValueChange = { findText = it }, label = { Text("查找文本") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = replaceText, onValueChange = { replaceText = it }, label = { Text("替换为") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip("正则", selected = useRegex, onClick = { useRegex = !useRegex })
                    SelectableChip("末尾递增序号 _001", selected = addNumbering, onClick = { addNumbering = !addNumbering })
                }
                Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("预览：", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        preview.forEach { (old, next) ->
                            Text("$old → $next", fontFamily = Mono, fontSize = 11.sp, color = if (old != next) G.accent else G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = null
                    runCatching { app.fileManager.batchRename(refs, findText, replaceText, prefix, suffix, useRegex, addNumbering) }
                        .onSuccess { cnt -> app.conversations.notify("已重命名 $cnt 项"); onDone(); onDismiss() }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text(if (busy) "执行中…" else "应用", color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = G.inkMuted) } },
    )
}

/** 5. 双文件文本行级 Diff 对比弹窗。 */
@Composable
fun TwoFileDiffSheet(app: GaliaisApplication, first: FileRef, second: FileRef, onDismiss: () -> Unit) {
    var diffLines by remember(first, second) { mutableStateOf<List<Pair<Char, String>>>(emptyList()) }
    var loading by remember(first, second) { mutableStateOf(true) }
    var error by remember(first, second) { mutableStateOf<String?>(null) }

    LaunchedEffect(first, second) {
        loading = true
        runCatching {
            val a = app.fileManager.readTextDocument(first).first.text
            val b = app.fileManager.readTextDocument(second).first.text
            computeLineDiffLines(a, b, maxLines = 500)
        }.onSuccess { diffLines = it }.onFailure { error = it.message }
        loading = false
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文件差异对比", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
            Text("- ${first.name}   /   + ${second.name}", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
            Surface(Modifier.fillMaxWidth().heightIn(max = 440.dp), shape = D.codeShape, color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    diffLines.forEach { (prefix, line) ->
                        val c = when (prefix) { '+' -> G.diffAddedInk; '-' -> G.diffRemovedInk; else -> G.codeMuted }
                        val bg = when (prefix) { '+' -> G.diffAddedBg; '-' -> G.diffRemovedBg; else -> androidx.compose.ui.graphics.Color.Transparent }
                        Text("$prefix $line", fontFamily = Mono, fontSize = 11.sp, color = c, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }
}

/** 6. 外部保存与系统分享弹窗（分组列表，一键存手机下载 / 存相册 / 另存为 / 分享 / 用其他应用打开）。 */
@Composable
fun ExportFileSheet(
    app: GaliaisApplication,
    fileRef: FileRef,
    onSaveAsDocument: (FileRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = fileRef.name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")
    val isVideo = ext in setOf("mp4", "mkv", "webm", "mov", "3gp")

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("保存到外部存储", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
                Text(fileRef.name, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CrispCard(padding = PaddingValues(0.dp)) {
                SettingsNavRow(
                    icon = GaliaisIcons.Download,
                    title = "保存到手机下载目录",
                    subtitle = "Download/Solbraid（一键直存，无需选目录）",
                    onClick = {
                        onDismiss()
                        scope.launch {
                            runCatching { app.fileManager.saveFileToDownloads(fileRef) }
                                .onSuccess { app.conversations.notify("已保存到手机：$it") }
                                .onFailure { app.conversations.notify("保存失败：${it.message}") }
                        }
                    },
                )
                if (isImage || isVideo) {
                    Hairline(Modifier.padding(start = 52.dp))
                    SettingsNavRow(
                        icon = GaliaisIcons.Image,
                        title = "保存到系统相册",
                        subtitle = "${if (isVideo) "Movies" else "Pictures"}/Solbraid",
                        onClick = {
                            onDismiss()
                            scope.launch {
                                runCatching {
                                    val staged = app.fileManager.stageReadableFile(fileRef)
                                    withContext(Dispatchers.IO) { ExternalStorageBridge.saveToGallery(context, staged, fileRef.name, isVideo) }
                                }.onSuccess { app.conversations.notify("已保存到相册：$it") }
                                    .onFailure { app.conversations.notify("保存相册失败：${it.message}") }
                            }
                        },
                    )
                }
                Hairline(Modifier.padding(start = 52.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.FolderSearch,
                    title = "另存为到指定位置",
                    subtitle = "通过 Android 系统文件选择器自定义路径",
                    onClick = { onDismiss(); onSaveAsDocument(fileRef) },
                )
            }
            CrispCard(padding = PaddingValues(0.dp)) {
                SettingsNavRow(
                    icon = GaliaisIcons.Share,
                    title = "系统分享",
                    subtitle = "发送到微信、邮件或其他应用",
                    onClick = {
                        onDismiss()
                        scope.launch {
                            runCatching {
                                val staged = app.fileManager.stageReadableFile(fileRef)
                                ExternalStorageBridge.shareFile(context, staged, fileRef.name)
                            }.onFailure { app.conversations.notify("分享失败：${it.message}") }
                        }
                    },
                )
                Hairline(Modifier.padding(start = 52.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Globe,
                    title = "用其他应用打开",
                    subtitle = "调起系统默认关联应用查看",
                    onClick = {
                        onDismiss()
                        scope.launch {
                            runCatching {
                                val staged = app.fileManager.stageReadableFile(fileRef)
                                ExternalStorageBridge.openWithExternalApp(context, staged, fileRef.name)
                            }.onFailure { app.conversations.notify("打开失败：${it.message}") }
                        }
                    },
                )
            }
        }
    }
}

/** 7. 外部上传方式选择弹窗（多文件、整文件夹、ZIP 解压导入、挂载外部 SAF 目录）。 */
@Composable
fun UploadOptionsSheet(
    targetDir: String,
    onPickMultiFiles: () -> Unit,
    onPickZipExtract: () -> Unit,
    onPickFolderTree: () -> Unit,
    onMountSafTree: () -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("从外部上传到当前目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
            Text("目标：$targetDir（流式写入，无 4MB 上限）", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PrimaryPillButton("上传单个或多个文件（原样保存）", modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); onPickMultiFiles() })
            SecondaryPillButton("上传整个文件夹（递归保留目录树）", modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); onPickFolderTree() })
            SecondaryPillButton("选择 ZIP 并自动解压导入", modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); onPickZipExtract() })
            Hairline()
            SecondaryPillButton("挂载手机外部文件夹到书签（SAF 免拷贝直读）", modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); onMountSafTree() })
        }
    }
}
