package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshTunnelPreset
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.launch

/** 底部「SSH」主工作台：主机簿、一键 SSH 交互终端、端口转发隧道、SFTP 远端文件浏览器与多密钥管理。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshScreen(
    app: GaliaisApplication,
    modifier: Modifier = Modifier,
    onOpenEnvironmentHub: () -> Unit,
    onOpenTerminal: (cwd: String, sshHost: SshHostConfig?) -> Unit = { _, _ -> },
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val hosts by app.envSsh.sshHosts.collectAsState()
    val keys by app.envSsh.sshKeys.collectAsState()
    val savedTunnels by app.envSsh.savedTunnels.collectAsState()
    val activeTunnels by app.envSsh.activeTunnels.collectAsState()
    val hostTests by app.envSsh.hostTests.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }

    var section by rememberSaveable { mutableStateOf("hosts") } // "hosts" | "tunnels" | "keys"
    var refreshing by remember { mutableStateOf(false) }
    var creatingHost by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var deletingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var deletingKey by remember { mutableStateOf<String?>(null) }
    var deletingTunnel by remember { mutableStateOf<SshTunnelPreset?>(null) }
    var execHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var sftpHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var creatingTunnel by remember { mutableStateOf(false) }
    var previewPort by remember { mutableStateOf<Int?>(null) }
    var hostGroupFilter by remember { mutableStateOf("") }
    var showGenerateKey by remember { mutableStateOf(false) }
    var showImportKey by remember { mutableStateOf(false) }
    var showKnownHosts by remember { mutableStateOf(false) }
    var passphraseKey by remember { mutableStateOf<String?>(null) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, com.galiais.agent.engine.ChatArtifact>?>(null) }
    val testingIds = remember { mutableStateMapOf<String, Boolean>() }
    val busyTunnelIds = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(runtime.ready) {
        if (runtime.ready) {
            runCatching { app.envSsh.refreshSshKeys() }
            runCatching { app.envSsh.refreshActiveTunnels() }
        }
    }

    Column(modifier.fillMaxSize().galiaisCanvasBackground()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("SSH", style = MaterialTheme.typography.titleLarge, color = G.ink)
                Text("主机簿 · 隧道 · SFTP · 多密钥", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (runtime.ready) {
                PluginSlotActionBar(
                    app = app,
                    slotName = "ssh.toolbar",
                    cwd = settings.defaultCwd,
                    onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                    onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                )
                TagPill("本地终端", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { onOpenTerminal(settings.defaultCwd, null) })
            }
            TagPill("环境中心", fg = G.accent, bg = G.accentContainer, onClick = onOpenEnvironmentHub)
            IconButton(
                enabled = runtime.ready && !refreshing,
                onClick = {
                    scope.launch {
                        refreshing = true
                        try {
                            if (app.envSsh.syncAllOnReady()) app.conversations.notify("已同步 SSH 配置与状态")
                        } catch (e: Exception) {
                            app.conversations.notify("刷新失败：${e.message}")
                        } finally {
                            refreshing = false
                        }
                    }
                },
            ) {
                Icon(GaliaisIcons.Refresh, contentDescription = "同步 SSH", tint = if (runtime.ready && !refreshing) G.inkMuted else G.inkSubtle, modifier = Modifier.size(19.dp))
            }
        }
        HeaderProgressSlot(refreshing)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            if (!runtime.ready) {
                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("本地运行环境未就绪", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            Text("启动运行环境后可直连 SSH、开启端口隧道、传输 SFTP 文件或管理密钥。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                        PrimaryPillButton("启动", onClick = app.runtime::start, enabled = runtime.bound && !runtime.busy)
                    }
                }
            }

            PluginSlotBannerColumn(
                app = app,
                slotName = "ssh.banner",
                cwd = settings.defaultCwd,
                onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
            )

            SegmentedPillBar(
                options = listOf(
                    "hosts" to "主机 (${hosts.size})",
                    "tunnels" to "隧道 (${activeTunnels.size}/${savedTunnels.size})",
                    "keys" to "密钥 (${keys.size})",
                ),
                selected = section,
                onSelect = { section = it },
            )

            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 280.dp)) {
                Crossfade(targetState = section, animationSpec = tween(120), label = "sshSection") { activeSection ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
                        when (activeSection) {
                "hosts" -> {
                    SectionHeader(
                        title = "SSH 服务器（自动同步 ~/.ssh/config）",
                        trailing = { PrimaryPillButton("+ 添加主机", onClick = { creatingHost = true }) },
                    )
                    if (hosts.isEmpty()) {
                        CrispCard {
                            Text("尚未添加远程主机。添加后不仅可在此一键连接交互终端、浏览 SFTP 文件和开端口隧道，本地终端与 Codex/Claude 也可直接执行 ssh <别名>。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        val groups = hosts.mapNotNull { it.group.takeIf(String::isNotBlank) }.distinct()
                        if (groups.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SelectableChip("全部 (${hosts.size})", selected = hostGroupFilter.isEmpty(), onClick = { hostGroupFilter = "" })
                                groups.forEach { grp ->
                                    SelectableChip(grp, selected = hostGroupFilter == grp, onClick = { hostGroupFilter = if (hostGroupFilter == grp) "" else grp })
                                }
                            }
                        }
                        val visibleHosts = if (hostGroupFilter.isEmpty()) hosts else hosts.filter { it.group == hostGroupFilter }
                        visibleHosts.forEach { host ->
                            val test = hostTests[host.id]
                            val testing = testingIds[host.id] == true
                            var hostMenu by remember(host.id) { mutableStateOf(false) }
                            CrispCard(padding = PaddingValues(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(GaliaisIcons.Server, contentDescription = null, tint = G.accent, modifier = Modifier.size(18.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    host.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = G.ink,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                TagPill("ssh ${host.alias}", fg = G.accent, bg = G.accentContainer)
                                            }
                                            Text(
                                                "${host.user}@${host.host}:${host.port}" +
                                                    if (host.proxyJump.isNotBlank()) " · 跳板 ${host.proxyJump}" else "",
                                                fontFamily = Mono,
                                                fontSize = 11.5.sp,
                                                color = G.inkMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TagPill(
                                            if (host.authType == "password") "密码" else host.keyName.ifBlank { "默认密钥" },
                                            fg = G.inkMuted,
                                            bg = G.surfaceSubtle,
                                        )
                                    }
                                    if (test != null) {
                                        Text(
                                            if (test.ok) "连通正常 ${test.latencyMs}ms · ${test.remoteInfo}" else "连接失败：${test.error}",
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = if (test.ok) G.success else G.danger,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PrimaryPillButton("连接终端", enabled = runtime.ready, onClick = { onOpenTerminal(settings.defaultCwd, host) })
                                        SecondaryPillButton("SFTP 文件", enabled = runtime.ready, onClick = { sftpHost = host })
                                        Spacer(Modifier.weight(1f))
                                        Box {
                                            IconButton(onClick = { hostMenu = true }, modifier = Modifier.size(36.dp)) {
                                                Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多主机操作", tint = G.inkMuted, modifier = Modifier.size(19.dp))
                                            }
                                            GaliaisDropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                                                Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    GaliaisMenuRow(
                                                        title = "快捷命令",
                                                        icon = GaliaisIcons.Terminal,
                                                        enabled = runtime.ready,
                                                        onClick = { hostMenu = false; execHost = host },
                                                    )
                                                    GaliaisMenuRow(
                                                        title = if (testing) "测速中…" else "连通测速",
                                                        icon = GaliaisIcons.Activity,
                                                        enabled = runtime.ready && !testing,
                                                        onClick = {
                                                            hostMenu = false
                                                            testingIds[host.id] = true
                                                            scope.launch {
                                                                try {
                                                                    val r = app.envSsh.testSshHost(host)
                                                                    app.conversations.notify(if (r.ok) "${host.name} 连通正常（${r.latencyMs}ms）" else "${host.name} 连接失败")
                                                                } catch (e: Exception) {
                                                                    app.conversations.notify("测速失败：${e.message}")
                                                                } finally {
                                                                    testingIds.remove(host.id)
                                                                }
                                                            }
                                                        },
                                                    )
                                                    if (runtime.ready && keys.isNotEmpty()) {
                                                        GaliaisMenuRow(
                                                            title = "部署公钥到主机",
                                                            icon = GaliaisIcons.ShieldCheck,
                                                            onClick = {
                                                                hostMenu = false
                                                                scope.launch {
                                                                    try {
                                                                        val targetKey = host.keyName.ifBlank { keys.first().name }
                                                                        app.envSsh.deployKeyToHost(host, targetKey)
                                                                    } catch (e: Exception) {
                                                                        app.conversations.notify("部署公钥失败：${e.message}")
                                                                    }
                                                                }
                                                            },
                                                        )
                                                    }
                                                    GaliaisMenuRow(
                                                        title = "编辑主机",
                                                        icon = GaliaisIcons.Edit,
                                                        onClick = { hostMenu = false; editingHost = host },
                                                    )
                                                    Hairline(Modifier.padding(vertical = 2.dp))
                                                    GaliaisMenuRow(
                                                        title = "删除主机",
                                                        icon = GaliaisIcons.Trash,
                                                        danger = true,
                                                        onClick = { hostMenu = false; deletingHost = host },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                "tunnels" -> {
                    SectionHeader(
                        title = "端口转发隧道（-L / -R / -D）",
                        trailing = { PrimaryPillButton("+ 新建隧道", enabled = hosts.isNotEmpty(), onClick = { creatingTunnel = true }) },
                    )
                    if (hosts.isEmpty()) {
                        CrispCard {
                            Text("请先在「主机」页添加至少一台 SSH 服务器，即可创建本地转发（-L）、远端转发（-R）或 SOCKS5 动态代理隧道（-D）。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else if (savedTunnels.isEmpty()) {
                        CrispCard {
                            Text("暂无隧道配置。创建 -L 本地转发后，可直接配合内置预览在手机打开远端 Web 服务或数据库端口。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        savedTunnels.forEach { preset ->
                            val active = activeTunnels.find { it.id == preset.id }
                            val running = active?.status == "running"
                            val busy = busyTunnelIds[preset.id] == true || active?.status in listOf("starting", "stopping")
                            CrispCard(padding = PaddingValues(13.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (running) PulsingDot(G.success, size = 7.dp)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    preset.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = G.ink,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                TagPill(preset.alias, fg = G.accent, bg = G.accentContainer)
                                            }
                                            Text(preset.summary(), fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted)
                                        }
                                        TagPill(
                                            when (active?.status) {
                                                "starting" -> "连接中"
                                                "stopping" -> "停止中"
                                                "running" -> "运行中"
                                                else -> "已停止"
                                            },
                                            fg = if (running) G.success else G.inkMuted,
                                            bg = if (running) G.successTint else G.surfaceSubtle,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (running) {
                                            SecondaryPillButton(if (busy) "停止中…" else "停止", danger = true, enabled = !busy, onClick = {
                                                busyTunnelIds[preset.id] = true
                                                scope.launch {
                                                    try { app.envSsh.stopTunnel(preset.id) }
                                                    catch (e: Exception) { app.conversations.notify("停止失败：${e.message}") }
                                                    finally { busyTunnelIds.remove(preset.id) }
                                                }
                                            })
                                            if (preset.mode == "local") {
                                                TagPill("打开实时预览 :${preset.localPort}", fg = G.accent, bg = G.accentContainer, onClick = { previewPort = preset.localPort })
                                            }
                                        } else {
                                            PrimaryPillButton(if (busy) "启动中…" else "启动隧道", enabled = runtime.ready && !busy, onClick = {
                                                busyTunnelIds[preset.id] = true
                                                scope.launch {
                                                    try { app.envSsh.startTunnel(preset) }
                                                    catch (e: Exception) { app.conversations.notify("隧道启动失败：${e.message}") }
                                                    finally { busyTunnelIds.remove(preset.id) }
                                                }
                                            })
                                        }
                                        Spacer(Modifier.weight(1f))
                                        TagPill(
                                            "删除",
                                            fg = G.danger,
                                            bg = G.dangerTint,
                                            onClick = if (busy) null else ({ deletingTunnel = preset }),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        SettingsNavRow(
                            icon = GaliaisIcons.ShieldCheck,
                            title = "已知主机指纹 (known_hosts)",
                            subtitle = "查看或移除已信任的 SSH 服务器密钥指纹",
                            onClick = { if (runtime.ready) showKnownHosts = true },
                        )
                    }
                    SectionHeader(
                        title = "SSH 密钥库（~/.ssh/）",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactActionButton("导入", enabled = runtime.ready, onClick = { showImportKey = true })
                                CompactActionButton("+ 生成", primary = true, enabled = runtime.ready, onClick = { showGenerateKey = true })
                            }
                        },
                    )
                    if (keys.isEmpty()) {
                        CrispCard {
                            Text("暂未检测到 SSH 密钥。点击「+ 生成」可一键创建 Ed25519 或 RSA 4096 密钥对，或导入已有私钥。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        keys.forEach { key ->
                            CrispCard(padding = PaddingValues(13.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(GaliaisIcons.ShieldCheck, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                                        Text(key.name, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f))
                                        TagPill("${key.keyType}${if (key.bits > 0) " ${key.bits}" else ""}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                        if (key.isDefault) TagPill("默认", fg = G.success, bg = G.successTint)
                                    }
                                    if (key.fingerprint.isNotBlank()) {
                                        Text(key.fingerprint, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (key.publicKey.isNotBlank()) {
                                            TagPill("复制公钥", fg = G.accent, bg = G.accentContainer, onClick = {
                                                scope.launch {
                                                    clipboard.setClipEntry(ClipData.newPlainText("ssh-pub", key.publicKey).toClipEntry())
                                                    app.conversations.notify("已复制公钥：${key.name}.pub")
                                                }
                                            })
                                        }
                                        TagPill("口令", fg = G.ink, bg = G.surfaceSubtle, onClick = { passphraseKey = key.name })
                                        Spacer(Modifier.weight(1f))
                                        TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                            deletingKey = key.name
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (creatingHost || editingHost != null) {
        SshHostEditorSheet(
            app = app,
            initial = editingHost,
            availableKeys = keys,
            availableHosts = hosts,
            onDismiss = { creatingHost = false; editingHost = null },
        )
    }
    sftpHost?.let { host ->
        SftpBrowserSheet(
            app = app,
            host = host,
            files = files,
            localDefaultDir = settings.defaultCwd,
            onOpenTerminalAt = { remotePath ->
                sftpHost = null
                onOpenTerminal(settings.defaultCwd, host.copy(defaultPath = remotePath))
            },
            onDismiss = { sftpHost = null },
        )
    }
    if (creatingTunnel) {
        SshTunnelEditorSheet(
            hosts = hosts,
            onSave = { preset ->
                app.envSsh.saveTunnelPreset(preset)
                creatingTunnel = false
            },
            onDismiss = { creatingTunnel = false },
        )
    }
    execHost?.let { host ->
        SshQuickExecSheet(app = app, host = host, onDismiss = { execHost = null })
    }
    previewPort?.let { port ->
        LivePreviewSheet(app = app, initialPort = port, onDismiss = { previewPort = null })
    }
    if (showGenerateKey) {
        GenerateSshKeyDialog(
            onDismiss = { showGenerateKey = false },
            onConfirm = { name, type, comment, passphrase ->
                app.envSsh.generateSshKey(name, type, comment, passphrase)
                showGenerateKey = false
            },
        )
    }
    if (showImportKey) {
        ImportSshKeyDialog(
            onDismiss = { showImportKey = false },
            onConfirm = { name, priv, passphrase ->
                app.envSsh.importSshKey(name, priv, passphrase = passphrase)
                showImportKey = false
            },
        )
    }
    if (showKnownHosts) KnownHostsSheet(app, onDismiss = { showKnownHosts = false })
    passphraseKey?.let { name -> SshKeyPassphraseDialog(app, name, onDismiss = { passphraseKey = null }) }
    deletingTunnel?.let { target ->
        GaliaisConfirmDialog(
            title = "删除端口转发隧道？",
            message = "将删除隧道预设「${target.name}（${target.summary()}）」。如正在运行则会先断开转发。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                app.envSsh.deleteTunnelPreset(target.id)
                deletingTunnel = null
            },
            onDismiss = { deletingTunnel = null },
        )
    }
    deletingHost?.let { target ->
        GaliaisConfirmDialog(
            title = "删除 SSH 主机？",
            message = "将删除主机「${target.name} (${target.alias})」、关联隧道预设及其加密保存的密码。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                app.envSsh.deleteSshHost(target.id)
                deletingHost = null
            },
            onDismiss = { deletingHost = null },
        )
    }
    deletingKey?.let { keyName ->
        GaliaisConfirmDialog(
            title = "删除 SSH 密钥？",
            message = "将永久删除 ~/.ssh/$keyName 与对应公钥，并清除保存的口令。此操作无法撤销。",
            confirmText = "永久删除",
            danger = true,
            onConfirm = {
                val name = keyName
                deletingKey = null
                scope.launch {
                    runCatching { app.envSsh.deleteSshKey(name) }
                        .onFailure { app.conversations.notify("删除失败：${it.message}") }
                }
            },
            onDismiss = { deletingKey = null },
        )
    }
    PluginSlotOverlayHost(
        app = app,
        cwd = settings.defaultCwd,
        openingSurface = openingPluginSurface,
        openingPanel = openingPluginPanel,
        onOpenSurface = { openingPluginSurface = it },
        onOpenPanel = { openingPluginPanel = it },
    )
}
