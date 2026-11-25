package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    LaunchedEffect(hostId, runtime.ready) {
        if (runtime.ready) try { app.envSsh.refreshTransfers() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "读取传输状态失败" }
    }
    LaunchedEffect(transfer?.id, transfer?.phase) { if (transfer?.phase == "completed") onCompleted() }
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
                if (transfer.progressPercent != null) Text("当前文件 ${transfer.progressPercent}%", style = MaterialTheme.typography.labelSmall)
                SecondaryPillButton("取消传输", enabled = transfer.phase == "running", onClick = {
                    scope.launch {
                        try { app.envSsh.cancelTransfer(transfer.id) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { error = e.message ?: "取消失败" }
                    }
                })
            } else if (transfer.phase == "failed" || transfer.phase == "cancelled") {
                transfer.error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
                SecondaryPillButton("重新传输", enabled = runtime.ready && transfers.none { it.active }, onClick = {
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
                if (action == "delete") Text(if (entry.directory) "仅可删除空目录，删除后无法恢复。" else "删除后无法恢复。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                else OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = !busy,
                    label = { Text(if (action == "chmod") "八进制权限，例如 644" else "新名称") },
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
    fun dismiss() { if (!busy) { if (text != original) confirmDiscard = true else onDismiss() } }
    LaunchedEffect(host.id, path) { read() }
    GaliaisSheet(onDismissRequest = ::dismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(path, fontFamily = Mono, style = MaterialTheme.typography.titleSmall, color = G.ink)
            HeaderProgressSlot(busy)
            error?.let { Text(it, color = G.danger, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy && revision != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                label = { Text("UTF-8 文本") },
                minLines = 8,
                maxLines = 18,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryPillButton("保存", enabled = !busy && revision != null && text != original, onClick = {
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
                })
                SecondaryPillButton("复制内容", onClick = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("远端文件", text).toClipEntry()) } })
                SecondaryPillButton("重新读取", enabled = !busy, onClick = {
                    if (text != original) { reloadAfterDiscard = true; confirmDiscard = true } else scope.launch { read() }
                })
                SecondaryPillButton("关闭", enabled = !busy, onClick = ::dismiss)
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
