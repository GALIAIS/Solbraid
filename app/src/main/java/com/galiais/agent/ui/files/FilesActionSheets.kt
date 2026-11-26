package com.galiais.agent.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.fs.FileNode
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.FileTab
import com.galiais.agent.engine.fs.formatFileSize
import com.galiais.agent.ui.CrispCard
import com.galiais.agent.ui.D
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisIcons
import com.galiais.agent.ui.GaliaisSheet
import com.galiais.agent.ui.Hairline
import com.galiais.agent.ui.Mono
import com.galiais.agent.ui.PrimaryPillButton
import com.galiais.agent.ui.SectionLabel
import com.galiais.agent.ui.TagPill

/**
 * 1. 单文件/文件夹操作底部面板（替代旧版 12 项细长下拉菜单）：
 * 顶部文件摘要 + 4 格高频大图标动作 + 分组清晰列表，单手拇指友好。
 */
@Composable
internal fun FileActionSheet(
    node: FileNode,
    isTextFile: Boolean = true,
    onPrimaryOpen: (() -> Unit)? = null,
    onOpenEditor: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onExtract: (() -> Unit)?,
    onProperties: () -> Unit,
    onReference: (() -> Unit)?,
    onStartMultiSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (icon, tint, bg) = iconAndColorForNode(node)
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 文件摘要头
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(D.shape(11.dp)).background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = buildString {
                        append(node.scope.shortLabel)
                        if (!node.isDirectory) append(" · ").append(formatFileSize(node.size))
                        if (node.permissions.isNotBlank()) append(" · ").append(node.permissions)
                    }
                    Text(meta, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 第一排 4 格高频动作
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!node.isDirectory) {
                    if (isTextFile || onPrimaryOpen == null) {
                        QuickTile("编辑代码", GaliaisIcons.FilePen, G.accent, G.accentContainer, Modifier.weight(1f)) { onDismiss(); onOpenEditor() }
                    } else {
                        QuickTile("查看预览", GaliaisIcons.Image, G.accent, G.accentContainer, Modifier.weight(1f)) { onDismiss(); onPrimaryOpen() }
                    }
                    QuickTile("导出保存", GaliaisIcons.Upload, G.success, G.successTint, Modifier.weight(1f)) { onDismiss(); onExport() }
                } else {
                    QuickTile("属性权限", GaliaisIcons.Info, G.accent, G.accentContainer, Modifier.weight(1f)) { onDismiss(); onProperties() }
                    QuickTile("压缩 ZIP", GaliaisIcons.Archive, G.warning, G.warningTint, Modifier.weight(1f)) { onDismiss(); onCompress() }
                }
                QuickTile("复制", GaliaisIcons.Copy, G.ink, G.surfaceSubtle, Modifier.weight(1f)) { onDismiss(); onCopy() }
                QuickTile("移动", GaliaisIcons.Scissors, G.ink, G.surfaceSubtle, Modifier.weight(1f)) { onDismiss(); onCut() }
            }

            // 详细操作分组卡
            CrispCard(padding = PaddingValues(0.dp)) {
                if (!node.isDirectory && !isTextFile && onPrimaryOpen != null) {
                    SheetActionRow("以代码编辑器打开", GaliaisIcons.FilePen) { onDismiss(); onOpenEditor() }
                    Hairline(Modifier.padding(start = 46.dp))
                }
                SheetActionRow("重命名", GaliaisIcons.Edit) { onDismiss(); onRename() }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("创建副本", GaliaisIcons.FilePlus) { onDismiss(); onDuplicate() }
                if (!node.isDirectory) {
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("压缩为 ZIP", GaliaisIcons.Archive) { onDismiss(); onCompress() }
                }
                onExtract?.let { ext ->
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("解压到同名目录", GaliaisIcons.FolderPlus) { onDismiss(); ext() }
                }
                if (!node.isDirectory) {
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("属性 · 权限 · SELinux · 哈希", GaliaisIcons.Info) { onDismiss(); onProperties() }
                }
                onReference?.let { ref ->
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("在 AI 会话中引用", GaliaisIcons.AtSign, tint = G.accent) { onDismiss(); ref() }
                }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("进入多选模式", GaliaisIcons.ListCheck) { onDismiss(); onStartMultiSelect() }
            }

            CrispCard(padding = PaddingValues(0.dp)) {
                SheetActionRow("删除", GaliaisIcons.Trash, tint = G.danger) { onDismiss(); onDelete() }
            }
        }
    }
}

/**
 * 2. 右下角 `+` FAB 弹出的「新建、上传与目录工具」底部面板：
 * 将旧版堆叠在顶部的 6 颗杂乱药丸按「导入 / 新建 / 目录工具」清晰分层收束。
 */
@Composable
internal fun CreateAndImportSheet(
    scope: FileScope,
    path: String,
    runtimeReady: Boolean,
    onUploadMultiFiles: () -> Unit,
    onUploadFolderTree: () -> Unit,
    onUploadZipExtract: () -> Unit,
    onMountSafTree: () -> Unit,
    onCreateFile: () -> Unit,
    onCreateDir: () -> Unit,
    onCreateSymlink: () -> Unit,
    onBookmarkDir: () -> Unit,
    onExportDirZip: () -> Unit,
    onUseAsProject: (() -> Unit)?,
    onOpenTerminal: (() -> Unit)?,
    onOpenGit: (() -> Unit)?,
    onOpenDiskUsage: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("新建与导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink)
                Text("${scope.shortLabel} · $path", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // 第一排 3 格核心高频动作
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("上传文件", GaliaisIcons.Upload, G.onAccent, G.accent, Modifier.weight(1f)) { onDismiss(); onUploadMultiFiles() }
                QuickTile("新建文件", GaliaisIcons.FilePlus, G.accent, G.accentContainer, Modifier.weight(1f)) { onDismiss(); onCreateFile() }
                QuickTile("新建目录", GaliaisIcons.FolderPlus, G.codex, G.codexTint, Modifier.weight(1f)) { onDismiss(); onCreateDir() }
            }

            SectionLabel("外部存储导入与挂载")
            CrispCard(padding = PaddingValues(0.dp)) {
                SheetActionRow("上传整个文件夹（递归保留结构）", GaliaisIcons.Folders) { onDismiss(); onUploadFolderTree() }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("选择 ZIP 并自动解压导入", GaliaisIcons.Archive) { onDismiss(); onUploadZipExtract() }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("挂载外部手机目录 (SAF 免拷贝直读)", GaliaisIcons.FolderSearch) { onDismiss(); onMountSafTree() }
            }

            SectionLabel("当前目录工具")
            CrispCard(padding = PaddingValues(0.dp)) {
                SheetActionRow("创建符号链接 (ln -s)", GaliaisIcons.Link) { onDismiss(); onCreateSymlink() }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("收藏当前目录到书签", GaliaisIcons.BookOpen) { onDismiss(); onBookmarkDir() }
                Hairline(Modifier.padding(start = 46.dp))
                SheetActionRow("打包导出当前目录为 ZIP", GaliaisIcons.Compress) { onDismiss(); onExportDirZip() }
                onOpenDiskUsage?.let { du ->
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("磁盘空间占用分析", GaliaisIcons.BarChart) { onDismiss(); du() }
                }
                onUseAsProject?.let { useProj ->
                    Hairline(Modifier.padding(start = 46.dp))
                    SheetActionRow("添加为 AI 项目并设为默认目录", GaliaisIcons.Sparkles, tint = G.accent) { onDismiss(); useProj() }
                }
                if (runtimeReady) {
                    onOpenTerminal?.let { term ->
                        Hairline(Modifier.padding(start = 46.dp))
                        SheetActionRow("在此目录打开终端", GaliaisIcons.Terminal) { onDismiss(); term() }
                    }
                    onOpenGit?.let { git ->
                        Hairline(Modifier.padding(start = 46.dp))
                        SheetActionRow("Git 改动与提交工作台", GaliaisIcons.GitBranch) { onDismiss(); git() }
                    }
                }
            }
        }
    }
}

/**
 * 3. 标签页管理器抽屉（点击顶栏浏览器式 `[N]` 徽章打开，支持多标签自由新建、切换、关闭）。
 */
@Composable
internal fun TabManagerSheet(
    tabs: List<FileTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNewTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("多标签页 (${tabs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.ink, modifier = Modifier.weight(1f))
                TagPill("+ 新建标签", fg = G.onAccent, bg = G.accent, onClick = { onOpenNewTab(); onDismiss() })
            }
            CrispCard(padding = PaddingValues(0.dp)) {
                tabs.forEachIndexed { idx, tab ->
                    val active = tab.id == activeTabId
                    if (idx > 0) Hairline(Modifier.padding(start = 14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (active) G.accentContainer.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { onSelectTab(tab.id); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    tab.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = G.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                TagPill(tab.scope.shortLabel, fg = if (active) G.accent else G.inkMuted, bg = if (active) G.accentContainer else G.surfaceSubtle)
                            }
                            Text(tab.path, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (tabs.size > 1) {
                            TagPill("关闭", fg = G.danger, bg = G.dangerTint, onClick = { onCloseTab(tab.id) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            PrimaryPillButton("+ 在当前位置打开新标签页", modifier = Modifier.fillMaxWidth(), onClick = { onOpenNewTab(); onDismiss() })
        }
    }
}

@Composable
private fun QuickTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    bg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = D.shape(12.dp),
        color = bg,
        border = if (bg == G.surfaceSubtle) BorderStroke(0.5.dp, G.border) else null,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SheetActionRow(
    label: String,
    icon: ImageVector,
    tint: Color = G.ink,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (tint == G.ink) G.inkMuted else tint, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, modifier = Modifier.weight(1f))
    }
}
