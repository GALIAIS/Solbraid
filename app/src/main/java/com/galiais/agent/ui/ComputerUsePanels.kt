package com.galiais.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.PcDiscoveredHost
import com.galiais.agent.engine.PcHostInfo
import com.galiais.agent.engine.PcProcessItem
import com.galiais.agent.engine.PcRemoteFileEntry
import com.galiais.agent.engine.PcSystemInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 局域网 UDP (:29841) 自动发现桌面主机弹窗。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcLanDiscoverySheet(
    app: GaliaisApplication,
    discovered: List<PcDiscoveredHost>,
    scanning: Boolean,
    onRescan: () -> Unit,
    onPickHost: (PcDiscoveredHost) -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheetScaffold(
        title = "扫描局域网电脑",
        subtitle = "UDP :29841 广播发现同局域网内运行的 Solbraid 原生桌面程序",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("关闭", onClick = onDismiss)
            PrimaryPillButton(if (scanning) "扫描中…" else "重新扫描", enabled = !scanning, onClick = onRescan, modifier = Modifier.weight(1f))
        },
    ) {
        if (scanning) {
            CrispCard(background = G.surfaceSubtle) {
                Text("正在广播扫描局域网内的 Solbraid 桌面代理…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
        } else if (discovered.isEmpty()) {
            CrispCard(background = G.surfaceSubtle) {
                Text(
                    "未发现局域网桌面主机。请确认电脑与手机在同一 Wi-Fi，且电脑运行了原生程序（solbraid-desktop --host 0.0.0.0）并放行 UDP 29841 端口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                )
            }
        } else {
            CrispCard(padding = PaddingValues(0.dp)) {
                discovered.forEachIndexed { idx, d ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPickHost(d) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(d.name.ifBlank { d.address }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TagPill(d.os.uppercase(), fg = G.inkMuted, bg = G.surfaceSubtle)
                            if (d.tls) TagPill("TLS", fg = G.success, bg = G.successTint)
                            TagPill(if (d.engine == "native-go") "原生引擎" else "Node", fg = G.accent, bg = G.accentContainer)
                        }
                        Text("${d.address}:${d.port}${if (d.arch.isNotBlank()) " · ${d.arch}" else ""}${if (d.mac.isNotBlank()) " · ${d.mac}" else ""}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                    if (idx < discovered.lastIndex) Hairline()
                }
            }
        }
    }
}

/** 远程电脑文件浏览与手机工作区双向互传面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcRemoteFilesSheet(
    app: GaliaisApplication,
    host: PcHostInfo,
    defaultPhoneDir: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("~") }
    var entries by remember { mutableStateOf<List<PcRemoteFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var uploadLocalPath by remember { mutableStateOf(defaultPhoneDir.trimEnd('/') + "/") }
    var pendingDelete by remember { mutableStateOf<PcRemoteFileEntry?>(null) }

    fun loadDir(path: String) {
        loading = true
        errorMsg = null
        scope.launch {
            try {
                val (resolved, list) = app.computerUse.listRemoteFiles(host.id, path)
                currentPath = resolved
                entries = list
            } catch (e: Exception) {
                errorMsg = e.message ?: "读取目录失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(host.id) { loadDir("~") }

    fun parentOf(p: String): String {
        val norm = p.trimEnd('/', '\\')
        val idx = maxOf(norm.lastIndexOf('/'), norm.lastIndexOf('\\'))
        return if (idx > 0) norm.substring(0, idx) else if (norm.endsWith(":")) "$norm\\" else "/"
    }

    GaliaisSheetScaffold(
        title = "远程文件 · ${host.name}",
        subtitle = "双向推拉支持最大 15–32MB 文件，自动落盘至手机工作区",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("上一级", enabled = !loading, onClick = { loadDir(parentOf(currentPath)) })
            SecondaryPillButton("刷新", enabled = !loading, onClick = { loadDir(currentPath) })
            PrimaryPillButton("关闭", onClick = onDismiss, modifier = Modifier.weight(1f))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = currentPath,
                onValueChange = { currentPath = it },
                label = { Text("电脑目录路径") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            CompactActionButton("进入", primary = true, enabled = !loading, onClick = { loadDir(currentPath) })
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = uploadLocalPath,
                onValueChange = { uploadLocalPath = it },
                label = { Text("上传手机文件路径 → 当前电脑目录") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            CompactActionButton("上传", enabled = !loading && uploadLocalPath.isNotBlank() && !uploadLocalPath.endsWith("/"), onClick = {
                val fname = uploadLocalPath.substringAfterLast('/')
                val sep = if (currentPath.contains('\\')) "\\" else "/"
                val remoteTarget = currentPath.trimEnd('/', '\\') + sep + fname
                scope.launch {
                    runCatching {
                        val bytes = app.computerUse.pushRemoteFile(host.id, uploadLocalPath.trim(), remoteTarget)
                        app.conversations.notify("已上传 $fname (${formatFileBytes(bytes)}) 到电脑")
                        loadDir(currentPath)
                    }.onFailure { app.conversations.notify("上传失败：${it.message}") }
                }
            })
        }

        if (errorMsg != null) {
            Text(errorMsg!!, style = MaterialTheme.typography.bodySmall, color = G.danger)
        }

        CrispCard(padding = PaddingValues(0.dp)) {
            if (loading) {
                Text("正在读取远程目录…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
            } else if (entries.isEmpty()) {
                Text("空目录", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    entries.take(80).forEachIndexed { idx, e ->
                        Column(
                            Modifier.fillMaxWidth().clickable { if (e.isDir) loadDir(e.path) }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill(if (e.isDir) "目录" else "文件", fg = if (e.isDir) G.accent else G.inkMuted, bg = if (e.isDir) G.accentContainer else G.surfaceSubtle)
                                Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (!e.isDir) {
                                    Text(formatFileBytes(e.size), fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                }
                            }
                            if (!e.isDir) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TagPill("下载到手机", fg = G.accent, bg = G.accentContainer, onClick = {
                                        val localTarget = defaultPhoneDir.trimEnd('/') + "/pc-downloads/" + e.name
                                        scope.launch {
                                            runCatching {
                                                val bytes = app.computerUse.pullRemoteFile(host.id, e.path, localTarget)
                                                app.conversations.notify("已下载 ${e.name} (${formatFileBytes(bytes)}) → $localTarget")
                                            }.onFailure { app.conversations.notify("下载失败：${it.message}") }
                                        }
                                    })
                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { pendingDelete = e })
                                }
                            }
                        }
                        if (idx < entries.take(80).lastIndex) Hairline()
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        GaliaisConfirmDialog(
            title = "删除电脑端文件？",
            message = "此操作不可逆，确定删除电脑上的「${target.path}」吗？",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    runCatching {
                        app.computerUse.deleteRemoteFile(host.id, target.path)
                        app.conversations.notify("已删除 ${target.name}")
                        loadDir(currentPath)
                    }.onFailure { app.conversations.notify("删除失败：${it.message}") }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 远程进程管理 + 系统音量/静音/电源控制面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcSystemProcessesSheet(
    app: GaliaisApplication,
    host: PcHostInfo,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sysInfo by remember { mutableStateOf<PcSystemInfo?>(null) }
    var processes by remember { mutableStateOf<List<PcProcessItem>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(50f) }
    var pendingPower by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingKill by remember { mutableStateOf<PcProcessItem?>(null) }

    fun refreshAll() {
        loading = true
        scope.launch {
            runCatching { sysInfo = app.computerUse.getSystemInfo(host.id) }
            runCatching { processes = app.computerUse.listProcesses(host.id, search.trim()) }
                .onFailure { app.conversations.notify("读取进程失败：${it.message}") }
            loading = false
        }
    }

    LaunchedEffect(host.id) { refreshAll() }

    GaliaisSheetScaffold(
        title = "进程与系统 · ${host.name}",
        subtitle = "硬件信息、实时音量、电源控制与远程进程管理",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("刷新", enabled = !loading, onClick = ::refreshAll)
            PrimaryPillButton("关闭", onClick = onDismiss, modifier = Modifier.weight(1f))
        },
    ) {
        sysInfo?.let { info ->
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(info.hostname.ifBlank { host.name }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                        TagPill("${info.platform}/${info.arch}", fg = G.inkMuted, bg = G.surface)
                        if (info.engine.isNotBlank()) TagPill(info.engine, fg = G.accent, bg = G.accentContainer)
                    }
                    Text("CPU ${info.cpus} 核 · 用户 ${info.user.ifBlank { "-" }} · 显示器 ${info.monitors.size.coerceAtLeast(1)} 台", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            }
        }

        // 音量与电源控制
        CrispCard(padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统音量 ${volume.roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = G.ink)
                    Spacer(Modifier.weight(1f))
                    TagPill("静音切换", fg = G.ink, bg = G.surfaceSubtle, onClick = {
                        scope.launch {
                            runCatching { app.computerUse.toggleSystemMute(host.id, true); app.conversations.notify("已切换电脑静音") }
                                .onFailure { app.conversations.notify("操作失败：${it.message}") }
                        }
                    })
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        scope.launch {
                            runCatching { app.computerUse.setSystemVolume(host.id, volume.roundToInt()) }
                                .onFailure { app.conversations.notify("调节音量失败：${it.message}") }
                        }
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = G.accent, activeTrackColor = G.accent),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("lock" to "锁屏", "sleep" to "休眠", "restart" to "重启", "shutdown" to "关机").forEach { (act, label) ->
                        TagPill(label, fg = G.danger, bg = G.dangerTint, onClick = { pendingPower = act to label })
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("筛选进程名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            CompactActionButton("筛选", primary = true, enabled = !loading, onClick = ::refreshAll)
        }

        CrispCard(padding = PaddingValues(0.dp)) {
            if (processes.isEmpty()) {
                Text(if (loading) "正在加载进程…" else "无匹配进程", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    processes.take(50).forEachIndexed { idx, p ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${p.pid}", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                            Text(p.name, style = MaterialTheme.typography.bodySmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TagPill("结束", fg = G.danger, bg = G.dangerTint, onClick = { pendingKill = p })
                        }
                        if (idx < processes.take(50).lastIndex) Hairline()
                    }
                }
            }
        }
    }

    pendingPower?.let { (act, label) ->
        GaliaisConfirmDialog(
            title = "确认对电脑执行「$label」？",
            message = "将立即向「${host.name}」发送 $label 指令（需要完全管理权限）。",
            confirmText = "确认$label",
            danger = true,
            onConfirm = {
                pendingPower = null
                scope.launch {
                    runCatching { app.computerUse.systemPower(host.id, act); app.conversations.notify("已发送 $label 指令") }
                        .onFailure { app.conversations.notify("$label 失败：${it.message}") }
                }
            },
            onDismiss = { pendingPower = null },
        )
    }

    pendingKill?.let { proc ->
        GaliaisConfirmDialog(
            title = "强制结束远程进程？",
            message = "确定强制结束 ${proc.name} (PID ${proc.pid}) 吗？可能导致未保存数据丢失。",
            confirmText = "强制结束",
            danger = true,
            onConfirm = {
                pendingKill = null
                scope.launch {
                    runCatching {
                        app.computerUse.killProcess(host.id, proc.pid)
                        app.conversations.notify("已结束进程 ${proc.name}")
                        refreshAll()
                    }.onFailure { app.conversations.notify("结束进程失败：${it.message}") }
                }
            },
            onDismiss = { pendingKill = null },
        )
    }
}

private fun formatFileBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "${bytes} B"
}
