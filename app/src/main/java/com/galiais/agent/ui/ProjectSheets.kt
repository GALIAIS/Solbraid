package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.DEFAULT_WORKSPACE
import com.galiais.agent.engine.FileEntry
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.launch

/** 格式化虚拟机内项目路径的显示名与短路径。 */
fun projectDisplayName(path: String): String =
    if (path == UNASSIGNED_PROJECT) "未指定目录"
    else path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }

fun projectShortPath(path: String): String =
    if (path == UNASSIGNED_PROJECT) "暂无工作目录"
    else path.replaceFirst("/home/agent", "~")

const val UNASSIGNED_PROJECT = "__unassigned__"

/** 会话列表中的可折叠项目文件夹头栏：点击整行展开/折叠，右侧支持一键新建该目录会话与目录管理菜单。 */
@Composable
fun ProjectGroupHeader(
    path: String,
    sessionCount: Int,
    expanded: Boolean,
    hasRunning: Boolean,
    hasPending: Boolean,
    isDefault: Boolean,
    canRemove: Boolean,
    runtimeReady: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    onBrowseFiles: (() -> Unit)?,
    onExportZip: (() -> Unit)? = null,
    onSetDefault: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val isRealDir = path != UNASSIGNED_PROJECT

    val headerBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (expanded) G.surface else G.surfaceSubtle,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "projectHeaderBg",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = headerBg,
        border = BorderStroke(D.borderWidth, if (hasPending) G.warning.copy(alpha = 0.5f) else G.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedChevron(expanded = expanded, tint = G.inkMuted, size = 16.dp)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(D.shape(8.dp))
                    .background(if (isDefault) G.accentContainer else G.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GaliaisIcons.Folder,
                    contentDescription = null,
                    tint = if (isDefault) G.accent else G.inkMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        projectDisplayName(path),
                        style = MaterialTheme.typography.titleSmall,
                        color = G.ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasRunning) StatusProgressArc(G.warning, size = 12.5.dp, strokeWidth = 1.7.dp)
                    else if (hasPending) StatusGlyph(StatusVisual.WARNING, G.warning, size = 13.5.dp)
                }
                Text(
                    projectShortPath(path),
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = G.inkSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(
                text = "$sessionCount",
                fg = if (sessionCount > 0) G.inkMuted else G.inkSubtle,
                bg = G.surfaceSubtle,
            )
            if (runtimeReady && isRealDir) {
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(GaliaisIcons.Add, contentDescription = "在 ${projectDisplayName(path)} 新建会话", tint = G.accent, modifier = Modifier.size(17.dp))
                }
            }
            if (isRealDir) {
                ConversationOverflowMenu("项目操作", menu, { menu = it }) {
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = G.inkMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Hairline()
                    if (runtimeReady) {
                        GaliaisMenuRow(title = "在此项目新建会话", icon = GaliaisIcons.Add, onClick = { menu = false; onNewChat() })
                    }
                    if (onBrowseFiles != null) {
                        GaliaisMenuRow(title = "在文件页浏览目录", icon = GaliaisIcons.Folder, onClick = { menu = false; onBrowseFiles() })
                    }
                    if (runtimeReady && onExportZip != null) {
                        GaliaisMenuRow(title = "导出项目为 ZIP", icon = GaliaisIcons.Archive, onClick = { menu = false; onExportZip() })
                    }
                    if (!isDefault && onSetDefault != null) {
                        GaliaisMenuRow(title = "设为默认工作目录", icon = GaliaisIcons.Check, onClick = { menu = false; onSetDefault() })
                    }
                    if (canRemove && onRemove != null) {
                        Hairline(Modifier.padding(vertical = 2.dp))
                        GaliaisMenuRow(title = "从项目列表移除", icon = GaliaisIcons.Close, danger = true, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}

/** 添加项目文件夹抽屉：支持在虚拟机内浏览本地目录、新建子目录，或通过 HTTPS/SSH 一键克隆 Git 仓库。 */
@Composable
fun AddProjectSheet(
    app: GaliaisApplication,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val cloneLogs by app.conversations.hub.cloneLogs.collectAsState()
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var mode by remember { mutableStateOf("local") } // "local" | "git"
    var browseDir by remember { mutableStateOf("/home/agent") }
    var pathInput by remember { mutableStateOf(DEFAULT_WORKSPACE) }
    var subdirs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var creatingSubdir by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    var repoUrl by remember { mutableStateOf("") }
    var cloneTarget by remember { mutableStateOf("/home/agent/my-repo") }
    var cloneTargetEdited by remember { mutableStateOf(false) }
    var cloneBranch by remember { mutableStateOf("") }
    var httpsToken by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(browseDir, runtime.ready, reload, mode) {
        if (!runtime.ready || mode != "local") return@LaunchedEffect
        loading = true
        error = null
        try {
            subdirs = files.list(browseDir).filter { it.directory && !it.name.startsWith(".") }
        } catch (e: Exception) {
            error = e.message
            subdirs = emptyList()
        }
        loading = false
    }

    val parentDir = browseDir.substringBeforeLast('/', "").ifEmpty { "/" }

    GaliaisSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("添加项目", style = MaterialTheme.typography.titleLarge, color = G.ink, modifier = Modifier.weight(1f))
                if (runtime.ready && mode == "local") {
                    SecondaryPillButton("新建文件夹", onClick = { creatingSubdir = true })
                }
            }

            SegmentedPillBar(
                options = listOf("local" to "本地目录", "git" to "克隆仓库"),
                selected = mode,
                onSelect = { mode = it; error = null },
                enabled = !saving,
            )

            if (mode == "git") {
                OutlinedTextField(
                    value = repoUrl,
                    onValueChange = { url ->
                        repoUrl = url
                        error = null
                        if (!cloneTargetEdited) {
                            val guessed = url.trim().removeSuffix("/").removeSuffix(".git")
                                .substringAfterLast('/').substringAfterLast(':')
                                .replace(Regex("[^a-zA-Z0-9._-]"), "")
                            if (guessed.isNotBlank()) cloneTarget = "/home/agent/$guessed"
                        }
                    },
                    label = { Text("仓库地址（HTTPS 或 SSH）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloneTarget,
                    onValueChange = { cloneTarget = it; cloneTargetEdited = true; error = null },
                    label = { Text("克隆目录") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloneBranch,
                    onValueChange = { cloneBranch = it },
                    label = { Text("分支（可选，留空为默认分支）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (repoUrl.trim().startsWith("https://")) {
                    OutlinedTextField(
                        value = httpsToken,
                        onValueChange = { httpsToken = it },
                        label = { Text("HTTPS 访问令牌（可选，不保存）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagPill("复制 SSH 公钥", fg = G.accent, bg = G.accentContainer, onClick = {
                        scope.launch {
                            try {
                                val key = app.conversations.hub.getGitSshKey()
                                sshKey = key
                                clipboard.setClipEntry(ClipData.newPlainText("ssh-pub", key).toClipEntry())
                                app.conversations.notify("已复制 SSH 公钥")
                            } catch (e: Exception) {
                                error = e.message ?: "读取 SSH 公钥失败"
                            }
                        }
                    })
                    Text("将公钥添加到代码托管平台后，可通过 SSH 访问仓库。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
                sshKey?.let { pub ->
                    Surface(Modifier.fillMaxWidth(), shape = D.codeShape, color = G.codeBg, border = BorderStroke(1.dp, G.codeBorder)) {
                        SelectionContainer {
                            Text(pub, fontFamily = Mono, fontSize = 11.sp, color = G.codeInk, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
                if (saving && cloneLogs.isNotEmpty()) {
                    Surface(Modifier.fillMaxWidth(), shape = D.codeShape, color = G.codeBg, border = BorderStroke(1.dp, G.codeBorder)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                            cloneLogs.takeLast(12).forEach { line ->
                                Text(line, fontFamily = Mono, fontSize = 11.sp, color = G.codeInk)
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it; error = null },
                    label = { Text("工作目录（Linux 路径）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )

            if (runtime.ready) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = D.shape(12.dp),
                    color = G.surfaceSubtle,
                    border = BorderStroke(0.75.dp, G.border),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (browseDir != "/") {
                                IconButton(
                                    onClick = { browseDir = parentDir; pathInput = parentDir },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(GaliaisIcons.ArrowBack, contentDescription = "上级目录", tint = G.inkMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                browseDir,
                                fontFamily = Mono,
                                fontSize = 11.5.sp,
                                color = G.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = if (browseDir == "/") 6.dp else 0.dp),
                            )
                            TagPill("选择当前目录", fg = G.accent, bg = G.accentContainer, onClick = { pathInput = browseDir })
                        }
                        HeaderProgressSlot(loading)
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        ) {
                            if (!loading && subdirs.isEmpty()) {
                                Text(
                                    "暂无子文件夹。可新建文件夹或选择当前目录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = G.inkSubtle,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                            subdirs.forEachIndexed { idx, entry ->
                                val full = browseDir.trimEnd('/') + "/" + entry.name
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(role = Role.Button) {
                                            browseDir = full
                                            pathInput = full
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(GaliaisIcons.Folder, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Icon(GaliaisIcons.ChevronRight, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(15.dp))
                                }
                                if (idx < subdirs.lastIndex) Hairline(Modifier.padding(start = 36.dp))
                            }
                        }
                    }
                }
            }
            }

            error?.let {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.dangerTint) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.padding(10.dp))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryPillButton("取消", onClick = onDismiss, enabled = !saving)
                val canSubmit = if (mode == "git") {
                    runtime.ready && repoUrl.isNotBlank() && cloneTarget.trim().startsWith("/home/agent/")
                } else {
                    pathInput.trim().startsWith("/")
                }
                PrimaryPillButton(
                    text = when {
                        saving && mode == "git" -> "正在克隆…"
                        saving -> "添加中…"
                        mode == "git" -> "克隆并添加项目"
                        else -> "添加为项目"
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !saving && canSubmit,
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                val finalPath = if (mode == "git") {
                                    app.conversations.hub.cloneGitRepo(
                                        url = repoUrl,
                                        targetDir = cloneTarget,
                                        branch = cloneBranch,
                                        token = httpsToken,
                                    )
                                } else {
                                    val normalized = pathInput.trim().trimEnd('/').ifEmpty { "/" }
                                    if (runtime.ready && normalized != "/") {
                                        runCatching { files.createDirectory(normalized) }
                                    }
                                    normalized
                                }
                                app.settings.addProject(finalPath)
                                onAdded(finalPath)
                            } catch (e: Exception) {
                                error = e.message ?: "操作失败"
                                saving = false
                            }
                        }
                    },
                )
            }
        }
    }

    if (creatingSubdir) {
        TextInputDialog("在 ${projectDisplayName(browseDir)} 新建文件夹", "", onDismiss = { creatingSubdir = false }) { folderName ->
            creatingSubdir = false
            val clean = folderName.trim().replace("/", "")
            if (clean.isEmpty()) return@TextInputDialog
            val target = browseDir.trimEnd('/') + "/" + clean
            scope.launch {
                try {
                    files.createDirectory(target)
                    browseDir = target
                    pathInput = target
                    reload++
                } catch (e: Exception) {
                    error = "创建文件夹失败：${e.message}"
                }
            }
        }
    }
}
