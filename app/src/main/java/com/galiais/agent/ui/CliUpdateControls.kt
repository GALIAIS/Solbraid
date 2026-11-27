package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CliUpdateControls(
    app: GaliaisApplication,
    agentId: String,
    showCheck: Boolean = true,
    showVersionLine: Boolean = true,
    onlyWhenActionable: Boolean = false,
) {
    val updater = app.conversations.cliUpdates
    val states by updater.states.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val restarting by updater.restarting.collectAsState()
    val reloadingEngine by updater.reloadingEngine.collectAsState()
    val isThisReloading = reloadingEngine == agentId
    val supportsHotReload = agentId in setOf("codex", "claude")
    val state = states[agentId]
    val anyInstall = states.values.any { it.installing }
    val scope = rememberCoroutineScope()
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf<String?>(null) }
    var loadingChangelog by remember { mutableStateOf(false) }
    LaunchedEffect(runtime.ready, agentId) {
        if (runtime.ready && state == null) {
            try { updater.refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { app.conversations.notify("读取版本状态失败：" + (error.message ?: "请重试")) }
        }
    }
    val hasActionable = state != null && (
        state.updateAvailable || state.pendingRestart || state.working || state.error != null || state.canRestore || isThisReloading
    )
    if (onlyWhenActionable && !hasActionable && !showCheck) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state?.let {
            if (showVersionLine) {
                Text("当前版本 " + it.currentVersion, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
            if (isThisReloading) {
                Text("正在加载 ${it.installedVersion}…", style = MaterialTheme.typography.bodySmall, color = G.accent)
            } else if (it.pendingRestart) {
                Text(
                    if (supportsHotReload) "已安装 ${it.installedVersion}，重新加载引擎后生效" else "已安装 ${it.installedVersion}，重启后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.accent,
                )
            } else if (it.latestVersion != null && it.error == null) {
                if (it.updateAvailable || showVersionLine) {
                    Text(
                        if (it.latestVersion == it.installedVersion) "已是最新版本" else "最新发布 " + it.latestVersion,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.updateAvailable) G.accent else G.inkMuted,
                    )
                }
            }
            if (it.working) {
                Text(
                    it.label + if (it.phase == "downloading") " · " + String.format(Locale.ROOT, "%.1f MB", it.downloadedBytes / 1048576.0) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.accent,
                )
                GaliaisProgressBar(
                    progress = if (it.phase == "downloading" && (it.totalBytes ?: 0) > 0)
                        (it.downloadedBytes.toFloat() / it.totalBytes!!.toFloat()).coerceIn(0f, 1f) else null,
                )
            }
            it.error?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = G.danger) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showCheck) SecondaryPillButton("检查更新", enabled = runtime.ready && state?.working != true && !isThisReloading, onClick = { updater.check(agentId) })
            if (state?.updateAvailable == true && !state.pendingRestart) {
                PrimaryPillButton("安装更新", enabled = runtime.ready && !anyInstall && !state.working && !isThisReloading, onClick = { confirmInstall = true })
            }
            if (state?.installing == true && state.phase != "publishing") {
                SecondaryPillButton(
                    if (state.phase == "cancelling") "取消中…" else "取消",
                    enabled = state.phase != "cancelling",
                    onClick = { updater.cancel(agentId) },
                )
            }
            if (state?.pendingRestart == true) {
                if (supportsHotReload) {
                    PrimaryPillButton(
                        if (isThisReloading) "热重载中…" else "立即热重载",
                        enabled = runtime.ready && !anyInstall && !restarting && reloadingEngine == null,
                        onClick = { updater.reloadEngine(agentId) },
                    )
                } else {
                    PrimaryPillButton(
                        if (restarting) "重启中…" else "重启生效",
                        enabled = runtime.ready && !anyInstall && !restarting,
                        onClick = { confirmRestart = true },
                    )
                }
            }
            if (state?.canRestore == true && !state.working && !isThisReloading) {
                SecondaryPillButton("恢复内置", enabled = runtime.ready && !anyInstall && reloadingEngine == null, onClick = { confirmRestore = true })
            }
            if (showCheck && agentId == "antigravity" && runtime.ready) {
                SecondaryPillButton(
                    if (loadingChangelog) "读取日志…" else "更新日志",
                    enabled = !loadingChangelog,
                    onClick = {
                        loadingChangelog = true
                        scope.launch {
                            runCatching { app.conversations.hub.fetchAgyChangelog() }
                                .onSuccess { changelogText = it.ifBlank { "暂无更新日志输出" } }
                                .onFailure { app.conversations.notify("读取更新日志失败：${it.message}") }
                            loadingChangelog = false
                        }
                    },
                )
            }
        }
    }
    changelogText?.let { logBody ->
        AlertDialog(
            onDismissRequest = { changelogText = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("Antigravity CLI 更新日志", color = G.ink) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(logBody.take(12_000), fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp, color = G.inkMuted)
                }
            },
            confirmButton = { TextButton(onClick = { changelogText = null }) { Text("关闭", color = G.accent) } },
        )
    }
    if (confirmInstall) {
        GaliaisConfirmDialog(
            title = "安装组件更新？",
            message = "从官方来源下载并校验 " + state?.latestVersion.orEmpty() + "，安装完成后生效。",
            confirmText = "安装",
            onConfirm = { confirmInstall = false; state?.latestVersion?.let { updater.install(agentId, it) } },
            onDismiss = { confirmInstall = false },
        )
    }
    if (confirmRestore) {
        GaliaisConfirmDialog(
            title = "恢复内置版本？",
            message = "恢复使用安装包内的内置版本，保留配置和会话。",
            confirmText = "恢复",
            onConfirm = { confirmRestore = false; updater.restore(agentId) },
            onDismiss = { confirmRestore = false },
        )
    }
    if (confirmRestart) {
        GaliaisConfirmDialog(
            title = "重启本地运行环境？",
            message = "将结束正在运行的任务和终端，使新版本组件立即生效。",
            confirmText = "重启",
            danger = true,
            onConfirm = { confirmRestart = false; updater.restart() },
            onDismiss = { confirmRestart = false },
        )
    }
}
