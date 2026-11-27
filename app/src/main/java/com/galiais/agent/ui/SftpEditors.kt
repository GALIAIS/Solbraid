package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshRemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SftpTransferPanel(app: GaliaisApplication, hostId: String, onCompleted: () -> Unit) {
    val transfers by app.envSsh.transfers.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val transfer = transfers.lastOrNull { it.hostId == hostId }
    // 记录进入面板前已完成的传输 ID，避免打开面板时因历史完成记录触发多余刷新
    var notifiedCompletedId by remember(hostId) {
        mutableStateOf(transfer?.takeIf { it.phase == "completed" }?.id)
    }
    LaunchedEffect(hostId, runtime.ready) {
        if (runtime.ready) try { app.envSsh.refreshTransfers() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "读取传输状态失败" }
    }
    LaunchedEffect(transfer?.id, transfer?.phase) {
        if (transfer != null && transfer.phase == "completed" && transfer.id != notifiedCompletedId) {
            notifiedCompletedId = transfer.id
            onCompleted()
        }
    }
    if (transfer != null) {
        CrispCard(padding = PaddingValues(10.dp)) {
            val label = when (transfer.phase) {
                "running" -> if (transfer.direction == "upload") "正在上传" else "正在下载"
                "publishing" -> "正在保存文件"
                "cancelling" -> "正在取消"
                "cancelled" -> "已取消传输"
                "completed" -> "传输完成"
                else -> "传输失败"
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = if (transfer.phase == "failed") G.danger else G.ink)
            if (transfer.active) {
                if (transfer.progressLabel.isNotEmpty()) Text(transfer.progressLabel, style = MaterialTheme.typography.bodySmall)
                GaliaisProgressBar(progress = transfer.progressPercent?.let { it / 100f })
                if (transfer.progressPercent != null) Text("当前进度 ${transfer.progressPercent}%", style = MaterialTheme.typography.labelSmall)
                SecondaryPillButton("取消传输", enabled = transfer.phase == "running", onClick = {
                    scope.launch {
                        try { app.envSsh.cancelTransfer(transfer.id) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { error = e.message ?: "取消失败" }
                    }
                })
            } else if (transfer.phase == "failed" || transfer.phase == "cancelled") {
                transfer.error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
                SecondaryPillButton("重新传输", enabled = transfers.none { it.active }, onClick = {
                    scope.launch {
                        try {
                            val host = app.envSsh.sshHosts.value.firstOrNull { it.id == hostId } ?: error("主机配置已删除")
                            app.envSsh.startTransfer(host, transfer.direction, transfer.localPath, transfer.remotePath)
                            error = null
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { error = e.message ?: "重试失败" }
                    }
                })
            } else transfer.resultPath?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
}

private fun octalPermissions(permissions: String): String {
    if (permissions.length != 10) return "644"
    val groups = (0..2).map { group ->
        val p = permissions.substring(1 + group * 3, 4 + group * 3)
        (if (p[0] == 'r') 4 else 0) + (if (p[1] == 'w') 2 else 0) + (if (p[2] in "xst") 1 else 0)
    }.joinToString("")
    val special = (if (permissions[3] in "sS") 4 else 0) + (if (permissions[6] in "sS") 2 else 0) + (if (permissions[9] in "tT") 1 else 0)
    return (if (special == 0) "" else special.toString()) + groups
}

@Composable
fun SftpEntryActionDialog(app: GaliaisApplication, host: SshHostConfig, entry: SshRemoteEntry, action: String, onDismiss: () -> Unit, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(if (action == "chmod") octalPermissions(entry.permissions) else entry.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = when (action) { "rename" -> "重命名"; "chmod" -> "修改权限"; else -> "删除" }
    GaliaisAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = title + " · " + entry.name,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action == "delete") {
                    Text(
                        if (entry.directory) "将递归删除此目录及其全部内容，删除后无法恢复。" else "删除后无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                } else OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = !busy,
                    label = { Text(if (action == "chmod") "八进制权限，例如 644 / 755" else "新名称") },
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        when (action) {
                            "rename" -> app.envSsh.sftpRename(host, entry.path, value)
                            "chmod" -> app.envSsh.sftpChmod(host, entry.path, value.trim())
                            else -> app.envSsh.sftpDelete(host, entry.path, entry.directory)
                        }
                        onComplete()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { error = e.message ?: "操作失败" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "处理中" else title, color = if (action == "delete") G.danger else G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = G.inkMuted) } },
    )
}

/** 全屏零闪烁远端 SFTP 文本编辑器（带语法高亮、未保存标记、乐观锁防覆盖与快捷键栏）。 */
@Composable
fun SftpTextEditor(app: GaliaisApplication, host: SshHostConfig, path: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var reloadAfterDiscard by remember { mutableStateOf(false) }
    val dirty = text != original
    val lang = remember(path) { SyntaxHighlight.languageFromPath(path) }
    val dark = G.dark
    val palette = remember(dark) { if (dark) CodePalettes.githubDark else CodePalettes.githubLight }
    val highlightTransform = remember(lang, palette) {
        if (lang == null) VisualTransformation.None
        else VisualTransformation { input ->
            val raw = input.text
            if (raw.length > 48_000) TransformedText(input, OffsetMapping.Identity)
            else TransformedText(SyntaxHighlight.highlight(raw, lang, palette), OffsetMapping.Identity)
        }
    }

    suspend fun read() {
        busy = true
        error = null
        try {
            val document = app.envSsh.sftpReadText(host, path)
            text = document.content
            original = document.content
            revision = document.revision
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "读取失败" }
        finally { busy = false }
    }

    fun dismiss() {
        if (!busy) {
            if (dirty) confirmDiscard = true else onDismiss()
        }
    }

    LaunchedEffect(host.id, path) { read() }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SyncFullscreenDialogWindow(G.canvas)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(G.canvas)
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            // 顶部紧凑工具栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(G.surface)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = ::dismiss, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "关闭", tint = G.ink, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (dirty) Icon(GaliaisIcons.Edit, contentDescription = "未保存", tint = G.warning, modifier = Modifier.size(13.dp))
                        Text(
                            path.substringAfterLast('/').ifBlank { path },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = G.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${host.alias}:$path · ${text.count { it == '\n' } + 1} 行",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        color = G.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("远端文件", text).toClipEntry())
                            app.conversations.notify("已复制远端文件内容")
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(GaliaisIcons.Copy, contentDescription = "复制", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    enabled = !busy,
                    onClick = {
                        if (dirty) { reloadAfterDiscard = true; confirmDiscard = true } else scope.launch { read() }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(GaliaisIcons.Refresh, contentDescription = "重新读取", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                }
                PrimaryPillButton(
                    text = if (busy) "保存中" else "保存",
                    enabled = !busy && revision != null && dirty,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                revision = app.envSsh.sftpWriteText(host, path, text, requireNotNull(revision))
                                original = text
                                app.conversations.notify("已保存远端文件")
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (e: Exception) { error = e.message ?: "保存失败" }
                            finally { busy = false }
                        }
                    },
                )
            }
            HeaderProgressSlot(busy)
            error?.let {
                Box(Modifier.fillMaxWidth().background(G.dangerTint).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(G.codeBg)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (busy && revision == null) {
                    Text("正在通过 SFTP 读取远端文本…", fontFamily = Mono, fontSize = 12.5.sp, color = G.inkMuted)
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !busy && revision != null,
                        textStyle = TextStyle(
                            color = G.codeInk,
                            fontFamily = Mono,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(G.accent),
                        visualTransformation = highlightTransform,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        GaliaisConfirmDialog(
            title = "放弃未保存的修改？",
            message = "当前文件有未保存的编辑内容，放弃后将无法恢复。",
            confirmText = "放弃修改",
            dismissText = "保留",
            danger = true,
            onConfirm = {
                confirmDiscard = false
                if (reloadAfterDiscard) { reloadAfterDiscard = false; scope.launch { read() } } else onDismiss()
            },
            onDismiss = { confirmDiscard = false; reloadAfterDiscard = false },
        )
    }
}
