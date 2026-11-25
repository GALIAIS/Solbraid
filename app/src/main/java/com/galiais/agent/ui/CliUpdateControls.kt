package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
fun CliUpdateControls(app: GaliaisApplication, agentId: String, showCheck: Boolean = true) {
    val updater = app.conversations.cliUpdates
    val states by updater.states.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val restarting by updater.restarting.collectAsState()
    val state = states[agentId]
    val anyInstall = states.values.any { it.installing }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    LaunchedEffect(runtime.ready, agentId) {
        if (runtime.ready && state == null) {
            try { updater.refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { app.conversations.notify("读取版本状态失败：" + (error.message ?: "请重试")) }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state?.let {
            Text("当前版本 " + it.currentVersion, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            if (it.pendingRestart) Text("已安装 " + it.installedVersion + "，重启后生效", style = MaterialTheme.typography.bodySmall, color = G.accent)
            else if (it.latestVersion != null && it.error == null) {
                Text(if (it.latestVersion == it.installedVersion) "已是最新版本" else "最新发布 " + it.latestVersion,
                    style = MaterialTheme.typography.bodySmall, color = if (it.updateAvailable) G.accent else G.inkMuted)
            }
            if (it.working) {
                Text(it.label + if (it.phase == "downloading") " · " + String.format(Locale.ROOT, "%.1f MB", it.downloadedBytes / 1048576.0) else "",
                    style = MaterialTheme.typography.bodySmall, color = G.accent)
                GaliaisProgressBar(progress = if (it.phase == "downloading" && (it.totalBytes ?: 0) > 0)
                    (it.downloadedBytes.toFloat() / it.totalBytes!!.toFloat()).coerceIn(0f, 1f) else null)
            }
            it.error?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = G.danger) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showCheck) SecondaryPillButton("检查更新", enabled = runtime.ready && state?.working != true, onClick = { updater.check(agentId) })
            if (state?.updateAvailable == true && !state.pendingRestart) {
                PrimaryPillButton("安装更新", enabled = runtime.ready && !anyInstall && !state.working, onClick = { confirmInstall = true })
            }
            if (state?.installing == true && state.phase != "publishing") {
                SecondaryPillButton(if (state.phase == "cancelling") "取消中…" else "取消", enabled = state.phase != "cancelling",
                    onClick = { updater.cancel(agentId) })
            }
            if (state?.pendingRestart == true) {
                PrimaryPillButton(if (restarting) "重启中…" else "重启生效", enabled = runtime.ready && !anyInstall && !restarting,
                    onClick = { confirmRestart = true })
            }
            if (state?.canRestore == true && !state.working) {
                SecondaryPillButton("恢复内置", enabled = runtime.ready && !anyInstall, onClick = { confirmRestore = true })
            }
        }
    }
    if (confirmInstall) AlertDialog(
        onDismissRequest = { confirmInstall = false },
        title = { Text("安装组件更新？") },
        text = { Text("从官方来源下载并校验 " + state?.latestVersion.orEmpty() + "，安装完成后生效。") },
        confirmButton = { TextButton(onClick = { confirmInstall = false; state?.latestVersion?.let { updater.install(agentId, it) } }) { Text("安装") } },
        dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("取消") } },
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("恢复内置版本？") },
        text = { Text("恢复使用安装包内的内置版本，保留配置和会话。") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; updater.restore(agentId) }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
    )
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("重启本地运行环境？") },
        text = { Text("将结束正在运行的任务和终端。") },
        confirmButton = { TextButton(onClick = { confirmRestart = false; updater.restart() }) { Text("重启") } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("取消") } },
    )
}
