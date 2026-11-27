package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import kotlinx.coroutines.launch

/** 三端原生超低延迟程序本机交叉编译与 SSH 自动部署面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComputerUseBuildTab(
    app: GaliaisApplication,
    runtimeReady: Boolean,
    sshAliases: List<String>,
    onDeployed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val buildStatus by app.computerUse.buildStatus.collectAsState()
    val progress by app.computerUse.buildProgress.collectAsState()

    var busyOp by remember { mutableStateOf(false) }
    var selectedSsh by remember(sshAliases) { mutableStateOf(sshAliases.firstOrNull() ?: "") }
    var deployName by remember { mutableStateOf("") }
    var deployTransport by remember { mutableStateOf("ssh") }
    var deployPerm by remember { mutableStateOf("operate") }
    var deployAutoTls by remember { mutableStateOf(false) }
    var deployService by remember { mutableStateOf(true) }
    var lastDeploySummary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runtimeReady) {
        if (runtimeReady) runCatching { app.computerUse.refreshBuildStatus() }
    }

    // 1. 实时构建/部署进度条
    progress?.let { p ->
        CrispCard(background = G.accentContainer.copy(alpha = 0.45f), borderColor = G.accent.copy(alpha = 0.4f), padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.message.ifBlank { p.stage }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = G.ink, modifier = Modifier.weight(1f))
                    Text("${p.percent.coerceIn(0, 100)}%", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.accent)
                }
                LinearProgressIndicator(
                    progress = { (p.percent.coerceIn(0, 100) / 100f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = G.accent,
                    trackColor = G.surface,
                )
            }
        }
    }

    // 2. 手机端交叉编译工具链状态卡
    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本机跨平台编译引擎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f))
                val ready = buildStatus?.toolchainInstalled == true
                TagPill(
                    text = if (ready) "已就绪" else "未安装 Go",
                    fg = if (ready) G.success else G.inkMuted,
                    bg = if (ready) G.successTint else G.surfaceSubtle,
                )
            }
            Text(
                if (buildStatus?.toolchainInstalled == true) {
                    "${buildStatus!!.goVersion} · 源码指纹 ${buildStatus!!.sourceHash.ifBlank { "未知" }}"
                } else {
                    "安装 Go 后，即可在手机上为 Windows、macOS 或 Linux 构建电脑代理。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (buildStatus?.toolchainInstalled != true) {
                    PrimaryPillButton(
                        text = if (busyOp) "安装中…" else "安装 Go 交叉编译工具链",
                        enabled = runtimeReady && !busyOp,
                        onClick = {
                            busyOp = true
                            scope.launch {
                                try { app.computerUse.installGoToolchain() }
                                catch (e: Exception) { app.conversations.notify("安装 Go 失败：${e.message}") }
                                finally { busyOp = false }
                            }
                        },
                    )
                } else {
                    PrimaryPillButton(
                        text = if (busyOp) "编译中…" else "编译全部 6 架构",
                        enabled = runtimeReady && !busyOp,
                        onClick = {
                            busyOp = true
                            scope.launch {
                                try { app.computerUse.buildNativeTargets(all = true) }
                                catch (e: Exception) { app.conversations.notify("编译失败：${e.message}") }
                                finally { busyOp = false }
                            }
                        },
                    )
                }
                SecondaryPillButton("刷新状态", enabled = runtimeReady && !busyOp, onClick = {
                    scope.launch { runCatching { app.computerUse.refreshBuildStatus() } }
                })
            }
        }
    }

    // 3. SSH 探测目标系统 -> 交叉编译 -> 上传安装 -> 自动配对上线
    SectionHeader("连接电脑并自动安装")
    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "选择 SSH 主机后，将为该电脑构建、安装并配对代理。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            if (sshAliases.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sshAliases.forEach { alias ->
                        SelectableChip(alias, selected = selectedSsh == alias, onClick = { selectedSsh = alias })
                    }
                }
            }
            OutlinedTextField(
                value = selectedSsh,
                onValueChange = { selectedSsh = it },
                label = { Text("目标 SSH 主机别名或 user@host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deployName,
                onValueChange = { deployName = it },
                label = { Text("显示名称（可选，留空自动使用主机名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接方式", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                SelectableChip("SSH 隧道", selected = deployTransport == "ssh", onClick = { deployTransport = "ssh" })
                SelectableChip("局域网直连", selected = deployTransport == "lan", onClick = { deployTransport = "lan" })
            }
            SegmentedPillBar(
                options = listOf("observe" to "只读观察", "operate" to "常规操控", "admin" to "完全管理"),
                selected = deployPerm,
                onSelect = { deployPerm = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动生成自签 TLS 证书并固定指纹", style = MaterialTheme.typography.bodySmall, color = G.ink)
                }
                Switch(checked = deployAutoTls, onCheckedChange = { deployAutoTls = it }, colors = SwitchDefaults.colors(checkedTrackColor = G.accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在目标电脑注册开机自启服务", style = MaterialTheme.typography.bodySmall, color = G.ink)
                }
                Switch(checked = deployService, onCheckedChange = { deployService = it }, colors = SwitchDefaults.colors(checkedTrackColor = G.accent))
            }
            lastDeploySummary?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = G.success, fontWeight = FontWeight.Medium)
            }
            PrimaryPillButton(
                text = if (busyOp) "正在自动部署…" else "自动探测系统 · 编译 · 安装 · 配对",
                enabled = runtimeReady && !busyOp && selectedSsh.isNotBlank(),
                onClick = {
                    busyOp = true
                    lastDeploySummary = null
                    scope.launch {
                        try {
                            val res = app.computerUse.deployOverSsh(
                                sshAlias = selectedSsh.trim(),
                                name = deployName.trim(),
                                permissionLevel = deployPerm,
                                transport = deployTransport,
                                autoTls = deployAutoTls,
                                installService = deployService,
                            )
                            val pingMs = res.optJSONObject("ping")?.optInt("latencyMs", 0) ?: 0
                            lastDeploySummary = "已部署 ${res.optString("remoteOs")}/${res.optString("remoteArch")} 原生程序至 $selectedSsh" + if (pingMs > 0) "（在线 ${pingMs}ms）" else ""
                            onDeployed()
                        } catch (e: Exception) {
                            app.conversations.notify("自动部署失败：${e.message}")
                        } finally {
                            busyOp = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 4. 三端 6 架构原生产物清单
    SectionHeader("构建目标")
    val targets = buildStatus?.targets.orEmpty()
    if (targets.isEmpty()) {
        CrispCard(background = G.surfaceSubtle) {
            Text("运行环境就绪后展示 Windows / macOS / Linux 各架构产物状态。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
        }
    } else {
        CrispCard(padding = PaddingValues(0.dp)) {
            targets.forEachIndexed { idx, t ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = G.ink)
                            if (t.built) {
                                TagPill(if (t.upToDate) "最新" else "需更新", fg = if (t.upToDate) G.success else G.accent, bg = if (t.upToDate) G.successTint else G.accentContainer)
                            }
                        }
                        Text(
                            if (t.built) "${t.filename} · ${String.format("%.2f MB", t.size / 1048576.0)} · ${t.sha256.take(10)}"
                            else "${t.os}/${t.arch} · 尚未编译",
                            fontFamily = Mono,
                            style = MaterialTheme.typography.labelSmall,
                            color = G.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CompactActionButton(
                        text = if (t.built) "重编" else "编译",
                        primary = !t.built,
                        enabled = runtimeReady && !busyOp && buildStatus?.toolchainInstalled == true,
                        onClick = {
                            busyOp = true
                            scope.launch {
                                try { app.computerUse.buildNativeTargets(os = t.os, arch = t.arch, force = t.built) }
                                catch (e: Exception) { app.conversations.notify("编译 ${t.label} 失败：${e.message}") }
                                finally { busyOp = false }
                            }
                        },
                    )
                }
                if (idx < targets.lastIndex) Hairline()
            }
        }
    }
}
