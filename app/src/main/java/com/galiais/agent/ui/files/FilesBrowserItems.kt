package com.galiais.agent.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.fs.FileNode
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.FileTab
import com.galiais.agent.engine.fs.MediaFilter
import com.galiais.agent.engine.fs.MountInfo
import com.galiais.agent.engine.fs.SortField
import com.galiais.agent.engine.fs.TransferJob
import com.galiais.agent.engine.fs.TransferPhase
import com.galiais.agent.engine.fs.ViewMode
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisCheckbox
import com.galiais.agent.ui.GaliaisDropdownMenu
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisMenuRow
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.SegmentedPillBar
import com.galiais.agent.ui.SelectableChip
import com.galiais.agent.ui.StatusGlyph
import com.galiais.agent.ui.StatusVisual
import com.galiais.agent.ui.TagPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun iconAndColorForNode(node: FileNode): Triple<ImageVector, Color, Color> {
    if (node.isDirectory) return Triple(GaliaisIcons.Folder, G.codex, G.codexTint)
    val ext = node.extension
    return when {
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif", "svg", "ico") ->
            Triple(GaliaisIcons.Image, G.success, G.successTint)
        ext in setOf("mp4", "mkv", "webm", "mov", "3gp", "mp3", "aac", "m4a", "flac", "ogg", "opus", "wav") ->
            Triple(GaliaisIcons.Play, G.artifact, G.artifactTint)
        ext in setOf("zip", "tar", "gz", "tgz", "xz", "7z", "apk", "jar", "aar") ->
            Triple(GaliaisIcons.Archive, G.warning, G.warningTint)
        ext in setOf("kt", "kts", "java", "js", "mjs", "ts", "tsx", "py", "rs", "go", "c", "cpp", "h", "sh", "json", "yaml", "yml", "toml", "xml", "html", "css", "sql", "md") ->
            Triple(GaliaisIcons.Code, G.accent, G.accentContainer)
        else -> Triple(GaliaisIcons.File, G.inkMuted, G.surfaceSubtle)
    }
}

private val ROW_TIME_FMT = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

/** 顶栏浏览器风格标签页计数徽章（单标签时不额外占行，点击管理/新建标签）。 */
@Composable
internal fun TabCounterButton(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "标签页 ($count)" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.5.dp))
                .border(1.5.dp, if (count > 1) G.accent else G.inkMuted, RoundedCornerShape(4.5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.coerceAtMost(9).toString(),
                color = if (count > 1) G.accent else G.inkMuted,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                ),
            )
        }
    }
}

/** 统一四存储域分段控件（复用应用原生 SegmentedPillBar），仅在开启 ≥2 个标签时自动展开极简标签栏。 */
@Composable
internal fun ScopeSegmentedHeader(
    tabs: List<FileTab>,
    activeTabId: String,
    activeScope: FileScope,
    onSwitchScope: (FileScope) -> Unit,
    onSelectTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val scopeOptions = remember {
        listOf(
            FileScope.LINUX_WORKSPACE to "工作区",
            FileScope.LINUX_ROOT to "容器Root",
            FileScope.HOST_ROOT to "手机Root",
            FileScope.ANDROID_STORAGE to "手机存储",
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SegmentedPillBar(
            options = scopeOptions,
            selected = activeScope,
            onSelect = onSwitchScope,
            compact = true,
        )
        AnimatedVisibility(
            visible = tabs.size > 1,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val active = tab.id == activeTabId
                    val label = if (tab.title == tab.scope.shortLabel) "${tab.scope.shortLabel}:/" else "${tab.scope.shortLabel}:${tab.title}"
                    Surface(
                        onClick = { onSelectTab(tab.id) },
                        shape = D.shape(8.dp),
                        color = if (active) G.surface else Color.Transparent,
                        border = BorderStroke(if (active) 0.85.dp else 0.5.dp, if (active) G.accent.copy(alpha = 0.65f) else G.border),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(label, fontFamily = Mono, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) G.ink else G.inkMuted, maxLines = 1)
                            Box(
                                modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onCloseTab(tab.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(GaliaisIcons.Close, contentDescription = "关闭标签", tint = G.inkSubtle, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(D.shape(8.dp))
                        .background(G.accentContainer)
                        .clickable(onClick = onOpenNewTab),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GaliaisIcons.Add, contentDescription = "新建标签", tint = G.accent, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/** 一体化路径面包屑 + 筛选/排序/视图切换工具条（单行 36dp，取代多层分散按钮）。 */
@Composable
internal fun PathAndUtilityBar(
    path: String,
    viewMode: ViewMode,
    filterActive: Boolean,
    filterExpanded: Boolean,
    onNavigate: (String) -> Unit,
    onManualPath: () -> Unit,
    onToggleFilterTray: () -> Unit,
    onCycleViewMode: () -> Unit,
) {
    val segments = remember(path) {
        if (path.startsWith("saf://")) {
            listOf(path.substringAfter("saf://").substringBefore('/') to path)
        } else {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            buildList {
                add("/" to "/")
                var acc = ""
                parts.forEach { p -> acc += "/$p"; add(p to acc) }
            }
        }
    }
    val breadcrumbScroll = rememberScrollState()
    LaunchedEffect(path) {
        breadcrumbScroll.scrollTo(breadcrumbScroll.maxValue)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(D.shape(8.dp))
                .background(G.surfaceSubtle.copy(alpha = 0.72f))
                .clickable(onClick = onManualPath)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(breadcrumbScroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                segments.forEachIndexed { idx, (label, target) ->
                    val isLast = idx == segments.lastIndex
                    Text(
                        label,
                        fontFamily = Mono,
                        fontSize = 11.5.sp,
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isLast) G.ink else G.inkMuted,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(D.shape(4.dp))
                            .clickable { if (!isLast) onNavigate(target) else onManualPath() }
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                    if (!isLast && label != "/") {
                        Text("/", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle)
                    }
                }
            }
            Icon(GaliaisIcons.Edit, contentDescription = "跳转路径", tint = G.inkSubtle, modifier = Modifier.size(12.dp))
        }
        // 筛选与排序抽屉开关
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(D.shape(8.dp))
                .background(if (filterActive || filterExpanded) G.accentContainer else Color.Transparent)
                .semantics { contentDescription = "筛选与排序" }
                .clickable(onClick = onToggleFilterTray),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GaliaisIcons.Filter, contentDescription = null, tint = if (filterActive || filterExpanded) G.accent else G.inkMuted, modifier = Modifier.size(16.dp))
        }
        // 视图循环切换（详细 / 紧凑 / 缩略图网格）
        val viewIcon = when (viewMode) {
            ViewMode.DETAILED -> GaliaisIcons.ListView
            ViewMode.COMPACT -> GaliaisIcons.Layers
            ViewMode.GRID -> GaliaisIcons.LayoutGrid
        }
        val viewDesc = when (viewMode) {
            ViewMode.DETAILED -> "当前：详细列表，点击切换紧凑"
            ViewMode.COMPACT -> "当前：紧凑列表，点击切换网格"
            ViewMode.GRID -> "当前：缩略图网格，点击切换详细"
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(D.shape(8.dp))
                .background(if (viewMode == ViewMode.GRID) G.accentContainer else Color.Transparent)
                .semantics { contentDescription = viewDesc }
                .clickable(onClick = onCycleViewMode),
            contentAlignment = Alignment.Center,
        ) {
            Icon(viewIcon, contentDescription = null, tint = if (viewMode == ViewMode.GRID) G.accent else G.inkMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** 可折叠的轻量筛选与排序托盘（平时隐藏不占位，展开后提供类型筛选、排序字段与隐藏文件开关）。 */
@Composable
internal fun FilterAndSortTray(
    visible: Boolean,
    mediaFilter: MediaFilter,
    sortField: SortField,
    sortAsc: Boolean,
    showHidden: Boolean,
    onSelectFilter: (MediaFilter) -> Unit,
    onSelectSort: (SortField) -> Unit,
    onToggleHidden: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(D.shape(10.dp))
                .background(G.surfaceSubtle.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaFilter.entries.forEach { mf ->
                    SelectableChip(mf.label, selected = mediaFilter == mf, onClick = { onSelectFilter(mf) })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortField.entries.forEach { sf ->
                    val sel = sortField == sf
                    val label = if (sel) "${sf.label}${if (sortAsc) " ↑" else " ↓"}" else sf.label
                    SelectableChip(label, selected = sel, onClick = { onSelectSort(sf) })
                }
                SelectableChip(if (showHidden) "含点文件" else "隐藏点文件", selected = showHidden, onClick = onToggleHidden)
            }
        }
    }
}

/** 紧凑单行 Root 状态细条（32dp，取代旧版 68dp 厚重双行大卡，同时保留完整只读锁与 .bak 开关）。 */
@Composable
internal fun CompactRootStatusBar(
    scope: FileScope,
    mountInfo: MountInfo?,
    writeUnlocked: Boolean,
    backupOnEdit: Boolean,
    onToggleUnlock: () -> Unit,
    onToggleBackup: () -> Unit,
) {
    val accent = if (writeUnlocked) G.danger else G.warning
    val bg = if (writeUnlocked) G.dangerTint else G.warningTint
    val summary = buildString {
        append("uid=0")
        mountInfo?.let { m -> append(" · ${m.mountPoint} (${m.fsType} ${if (m.readOnly) "ro" else "rw"})") }
            ?: append(if (scope == FileScope.HOST_ROOT) " · 宿主 Root" else " · 容器 Root")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(D.shape(8.dp))
            .background(bg.copy(alpha = 0.78f))
            .border(0.6.dp, accent.copy(alpha = 0.35f), D.shape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(
            visual = if (writeUnlocked) StatusVisual.WARNING else StatusVisual.SUCCESS,
            color = accent,
            size = 13.5.dp,
        )
        Text(
            summary,
            fontFamily = Mono,
            fontSize = 10.5.sp,
            color = if (writeUnlocked) G.danger else G.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (scope == FileScope.HOST_ROOT) {
            Box(
                modifier = Modifier
                    .clip(D.shape(6.dp))
                    .background(if (writeUnlocked) G.danger else G.surface)
                    .clickable(onClick = onToggleUnlock)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(if (writeUnlocked) "系统可写" else "只读锁", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (writeUnlocked) G.onAccent else G.warning)
            }
        }
        Box(
            modifier = Modifier
                .clip(D.shape(6.dp))
                .background(G.surface.copy(alpha = 0.9f))
                .clickable(onClick = onToggleBackup)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(if (backupOnEdit) ".bak 开" else ".bak 关", fontSize = 10.sp, color = G.inkMuted)
        }
    }
}

/** 后台传输进度浮条（支持取消与完成后一键关闭）。 */
@Composable
internal fun TransferDock(
    jobs: List<TransferJob>,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val visible = jobs.filter { it.active || it.phase == TransferPhase.FAILED }.takeLast(2)
    if (visible.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        visible.forEach { job ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = D.shape(10.dp),
                color = if (job.phase == TransferPhase.FAILED) G.dangerTint else G.surface,
                border = BorderStroke(0.75.dp, if (job.phase == TransferPhase.FAILED) G.danger else G.accent.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(job.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (job.active) {
                            if (job.speedBytesPerSec > 0L) Text("${formatFileSize(job.speedBytesPerSec)}/s", fontFamily = Mono, fontSize = 10.5.sp, color = G.accent)
                            TagPill("取消", fg = G.danger, bg = G.dangerTint, onClick = { onCancel(job.id) })
                        } else {
                            TagPill("关闭", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { onDismiss(job.id) })
                        }
                    }
                    if (job.active) {
                        val p = job.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(3.dp), color = G.accent)
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = G.accent)
                        if (job.currentItem.isNotBlank()) {
                            Text("${job.doneItems}/${job.totalItems} · ${job.currentItem}", fontFamily = Mono, fontSize = 10.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        job.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = G.danger) }
                    }
                }
            }
        }
    }
}

/** 极简编辑风文件列表行（无堆叠边框，配合外层统一卡片与 0.5dp 内缩分割线；右侧提供显式 `⋯` 打开操作面板，长按直接进入多选）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileListRow(
    node: FileNode,
    viewMode: ViewMode,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val (icon, iconTint, iconBg) = iconAndColorForNode(node)
    val compact = viewMode == ViewMode.COMPACT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) G.accentContainer.copy(alpha = 0.42f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selectionMode) onLongClick() else onClick() },
                onLongClick = onLongClick,
            )
            .padding(start = 12.dp, end = 4.dp, top = if (compact) 8.dp else 10.dp, bottom = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (selectionMode) {
            GaliaisCheckbox(selected)
        }
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 36.dp)
                .clip(D.shape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(if (compact) 15.dp else 17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (node.isBrokenSymlink) G.danger else G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (node.isSymlink) {
                    Text(
                        if (node.isBrokenSymlink) "断链" else "软链",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (node.isBrokenSymlink) G.danger else G.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (node.isBrokenSymlink) G.dangerTint else G.accentContainer)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                node.gitStatus?.let { st ->
                    val (fg, bg) = when (st) {
                        "M" -> G.warning to G.warningTint
                        "A", "?" -> G.success to G.successTint
                        else -> G.danger to G.dangerTint
                    }
                    Text(
                        st,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (!compact) {
                val sub = buildString {
                    if (node.isSymlink && !node.symlinkTarget.isNullOrBlank()) {
                        append("→ ").append(node.symlinkTarget)
                    } else {
                        if (!node.isDirectory) append(formatFileSize(node.size)).append(" · ")
                        if (node.lastModifiedMs > 0L) append(ROW_TIME_FMT.get()?.format(Date(node.lastModifiedMs)) ?: "")
                        if (node.permissions.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(node.permissions.drop(1))
                        }
                        if (node.owner.isNotBlank() && node.scope.isRootElevated) append(" ").append(node.owner)
                    }
                }
                if (sub.isNotBlank()) {
                    Text(sub, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!selectionMode) {
            IconButton(
                onClick = onOpenActions,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(GaliaisIcons.MoreHoriz, contentDescription = "操作 ${node.name}", tint = G.inkSubtle, modifier = Modifier.size(16.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** 缩略图网格卡片（异步生成图片/视频缩略图，零主线程卡顿）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaGridTile(
    app: GaliaisApplication,
    node: FileNode,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val (icon, iconTint, iconBg) = iconAndColorForNode(node)
    val isImg = node.extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
    val isVid = node.extension in setOf("mp4", "mkv", "webm", "mov", "3gp")
    var thumb by remember(node.path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(node.path, isImg, isVid) {
        if (!isImg && !isVid) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                val f = app.fileManager.stageReadableFile(node.ref, maxBytes = if (isImg) 8L * 1024 * 1024 else -1L)
                if (isImg) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 280 || opts.outHeight / sample > 280) sample *= 2
                    BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                } else {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(f.absolutePath)
                    val frame = r.getFrameAtTime(1_000_000L)
                    r.release()
                    frame
                }
            }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(D.shape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = D.shape(12.dp),
        color = if (selected) G.accentContainer.copy(alpha = 0.5f) else G.surface,
        border = BorderStroke(if (selected) 1.25.dp else 0.5.dp, if (selected) G.accent else G.border),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.08f).clip(D.shape(8.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(bitmap = thumb!!.asImageBitmap(), contentDescription = node.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
                }
                if (selectionMode) {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { GaliaisCheckbox(selected) }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(onClick = onOpenActions),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "操作", tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
                if (isVid) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { TagPill("视频", fg = Color.White, bg = Color.Black.copy(alpha = 0.65f)) }
                }
            }
            Text(node.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (node.isDirectory) "文件夹" else formatFileSize(node.size), fontFamily = Mono, fontSize = 9.5.sp, color = G.inkMuted, maxLines = 1)
        }
    }
}

/** 多选模式底部悬浮操作胶囊坞：高频操作固定可见（复制/移动/删除），次级批量操作收入更多菜单防 360dp 屏溢出。 */
@Composable
internal fun MultiSelectActionBar(
    count: Int,
    canDiffTwo: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onCompress: () -> Unit,
    onBatchRename: () -> Unit,
    onDiffTwo: () -> Unit,
    onExportZip: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        shape = D.shape(18.dp),
        color = G.surfaceElevated,
        shadowElevation = 6.dp,
        border = BorderStroke(0.75.dp, G.borderStrong.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TagPill("复制 ($count)", fg = G.onAccent, bg = G.accent, onClick = onCopy)
            TagPill("移动", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = onCut)
            if (canDiffTwo) {
                TagPill("对比差异", fg = G.artifact, bg = G.artifactTint, onClick = onDiffTwo)
            }
            Spacer(Modifier.weight(1f))
            TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = onDelete)
            Box {
                IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多批量操作", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                }
                GaliaisDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        GaliaisMenuRow("压缩为 ZIP", icon = GaliaisIcons.Archive, onClick = { moreOpen = false; onCompress() })
                        GaliaisMenuRow("批量重命名", icon = GaliaisIcons.Edit, onClick = { moreOpen = false; onBatchRename() })
                        GaliaisMenuRow("打包导出 ZIP", icon = GaliaisIcons.Download, onClick = { moreOpen = false; onExportZip() })
                    }
                }
            }
        }
    }
}
