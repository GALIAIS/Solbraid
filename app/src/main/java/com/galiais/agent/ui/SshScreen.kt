package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshTunnelPreset
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.ssh.TermSessionStatus
import kotlinx.coroutines.launch

/** 底部「SSH」主工作台：主机簿、原生 SSH 交互终端、端口转发隧道、SFTP 远端文件浏览器与多密钥管理（不依赖本地 Linux 运行时）。 */
@Composable
fun SshScreen(
    app: GaliaisApplication,
    modifier: Modifier = Modifier,
    onOpenEnvironmentHub: () -> Unit,
    onOpenTerminal: (cwd: String, sshHost: SshHostConfig?) -> Unit = { _, _ -> },
    onOpenTerminalSession: (sessionId: String) -> Unit = {},
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val hosts by app.envSsh.sshHosts.collectAsState()
    val keys by app.envSsh.sshKeys.collectAsState()
    val savedTunnels by app.envSsh.savedTunnels.collectAsState()
    val activeTunnels by app.envSsh.activeTunnels.collectAsState()
    val liveSessions by app.envSsh.nativeTerminals.sessions.collectAsState()
    val hostTests by app.envSsh.hostTests.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }

    var section by rememberSaveable { mutableStateOf("hosts") } // "hosts" | "tunnels" | "keys"
    var refreshing by remember { mutableStateOf(false) }
    var creatingHost by remember { mutableStateOf(false) }
    var showQuickConnect by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var deletingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var deletingKey by remember { mutableStateOf<String?>(null) }
    var deletingTunnel by remember { mutableStateOf<SshTunnelPreset?>(null) }
    var execHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var sftpHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var creatingTunnel by remember { mutableStateOf(false) }
    var previewPort by remember { mutableStateOf<Int?>(null) }
    var hostGroupFilter by remember { mutableStateOf("") }
    var hostSearchQuery by rememberSaveable { mutableStateOf("") }
    var showGenerateKey by remember { mutableStateOf(false) }
    var showImportKey by remember { mutableStateOf(false) }
    var showKnownHosts by remember { mutableStateOf(false) }
    var passphraseKey by remember { mutableStateOf<String?>(null) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, com.galiais.agent.engine.ChatArtifact>?>(null) }
    val testingIds = remember { mutableStateMapOf<String, Boolean>() }
    val busyTunnelIds = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        runCatching { app.envSsh.refreshSshKeys() }
    }
    LaunchedEffect(runtime.ready) {
        if (runtime.ready) {
            runCatching { app.envSsh.refreshSshKeys() }
            runCatching { app.envSsh.refreshActiveTunnels() }
        }
    }

    Column(modifier.fillMaxSize().galiaisCanvasBackground()) {
        // 1. 紧凑单排顶栏（标题 + 实时概览副标 + 本地终端/环境中心/刷新）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (D.material) 64.dp else 56.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("SSH", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = G.ink)
                val summary = buildString {
                    append("${hosts.size} 主机 · ${keys.size} 密钥")
                    if (activeTunnels.isNotEmpty()) append(" · ${activeTunnels.size} 活跃隧道")
                }
                Text(summary, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (runtime.ready) {
                PluginSlotActionBar(
                    app = app,
                    slotName = "ssh.toolbar",
                    cwd = settings.defaultCwd,
                    onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                    onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                )
                CompactActionButton("本地终端", icon = GaliaisIcons.Terminal, onClick = { onOpenTerminal(settings.defaultCwd, null) })
            }
            TagPill("环境中心", fg = G.accent, bg = G.accentContainer, onClick = onOpenEnvironmentHub)
            IconButton(
                enabled = !refreshing,
                modifier = Modifier.size(36.dp),
                onClick = {
                    scope.launch {
                        refreshing = true
                        try {
                            app.envSsh.refreshSshKeys()
                            app.envSsh.refreshActiveTunnels()
                            if (runtime.ready && app.envSsh.syncAllOnReady()) {
                                app.conversations.notify("已同步本地 SSH 配置与原生密钥")
                            } else if (!runtime.ready) {
                                app.conversations.notify("已刷新原生密钥与隧道状态")
                            }
                        } catch (e: Exception) {
                            app.conversations.notify("刷新失败：${e.message}")
                        } finally {
                            refreshing = false
                        }
                    }
                },
            ) {
                Icon(GaliaisIcons.Refresh, contentDescription = "刷新 SSH", tint = if (!refreshing) G.inkMuted else G.inkSubtle, modifier = Modifier.size(19.dp))
            }
        }
        HeaderProgressSlot(refreshing)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 2. 极简活跃终端保活条（仅在后台有运行会话时展示，单排横向紧凑胶囊）
            if (liveSessions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(G.surface)
                        .border(0.75.dp, G.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "活跃 ${liveSessions.size}/8",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = G.inkMuted,
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(liveSessions, key = { it.id }) { sess ->
                            val st by sess.status.collectAsState()
                            val dotColor = when (st) {
                                TermSessionStatus.CONNECTING -> G.warning
                                TermSessionStatus.RUNNING -> G.success
                                else -> G.danger
                            }
                            val statusVisual = when (st) {
                                TermSessionStatus.CONNECTING -> StatusVisual.RUNNING
                                TermSessionStatus.RUNNING -> StatusVisual.SUCCESS
                                else -> StatusVisual.DANGER
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(G.surfaceSubtle)
                                    .border(0.75.dp, G.border, RoundedCornerShape(8.dp))
                                    .clickable { onOpenTerminalSession(sess.id) }
                                    .padding(start = 7.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                StatusGlyph(visual = statusVisual, color = dotColor, size = 12.5.dp)
                                Text(if (sess.isSsh) "SSH" else "LOCAL", fontFamily = Mono, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = if (sess.isSsh) G.accent else G.success)
                                Text(sess.title, fontFamily = Mono, fontSize = 11.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                                Box(
                                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { app.envSsh.nativeTerminals.closeSession(sess.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(GaliaisIcons.Close, contentDescription = "关闭会话", tint = G.inkMuted, modifier = Modifier.size(11.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (!runtime.ready) {
                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("原生 SSH 引擎已就绪", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = G.ink)
                            Text("SSH 终端、SFTP、-L/-R 隧道与密钥可直接运行；本地终端需启动 Linux 环境。", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                        CompactActionButton("启动环境", primary = true, enabled = runtime.bound && !runtime.busy, onClick = app.runtime::start)
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
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (activeSection) {
                "hosts" -> {
                    // 融合工具条：内联搜索框 + 快捷连 + 添加主机（消除冗余多排标题栏，首屏可见 5–6 台主机）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(G.surface)
                                .border(0.75.dp, G.border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(GaliaisIcons.Search, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(14.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (hostSearchQuery.isEmpty()) {
                                    Text("搜索主机、IP 或别名…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 1)
                                }
                                BasicTextField(
                                    value = hostSearchQuery,
                                    onValueChange = { hostSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = G.ink, fontSize = 12.5.sp),
                                    cursorBrush = SolidColor(G.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (hostSearchQuery.isNotEmpty()) {
                                Icon(
                                    GaliaisIcons.Close,
                                    contentDescription = "清除搜索",
                                    tint = G.inkMuted,
                                    modifier = Modifier.size(14.dp).clickable { hostSearchQuery = "" },
                                )
                            }
                        }
                        CompactActionButton("快捷连", onClick = { showQuickConnect = true })
                        CompactActionButton("+ 主机", primary = true, onClick = { creatingHost = true })
                    }

                    if (hosts.isEmpty()) {
                        CrispCard(padding = PaddingValues(14.dp)) {
                            Text("添加主机后即可连接终端、浏览远程文件或设置端口转发。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        val groups = hosts.mapNotNull { it.group.takeIf(String::isNotBlank) }.distinct()
                        if (groups.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                item {
                                    SelectableChip("全部 (${hosts.size})", selected = hostGroupFilter.isEmpty(), onClick = { hostGroupFilter = "" })
                                }
                                items(groups) { grp ->
                                    SelectableChip(grp, selected = hostGroupFilter == grp, onClick = { hostGroupFilter = if (hostGroupFilter == grp) "" else grp })
                                }
                            }
                        }
                        val q = hostSearchQuery.trim().lowercase()
                        val visibleHosts = hosts.filter { h ->
                            val grpOk = hostGroupFilter.isEmpty() || h.group == hostGroupFilter
                            val qOk = q.isEmpty() || h.name.lowercase().contains(q) || h.alias.lowercase().contains(q) ||
                                h.host.lowercase().contains(q) || h.user.lowercase().contains(q)
                            grpOk && qOk
                        }
                        if (visibleHosts.isEmpty()) {
                            CrispCard(padding = PaddingValues(14.dp)) {
                                Text("未找到匹配「$hostSearchQuery」的主机。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                        } else {
                            visibleHosts.forEach { host ->
                                val test = hostTests[host.id]
                                val testing = testingIds[host.id] == true
                                val liveHostSession = liveSessions.firstOrNull { it.sshHost?.id == host.id && it.isActive }
                                var hostMenu by remember(host.id) { mutableStateOf(false) }

                                // 精致高密度单层主机卡片（整卡直点即连终端，右侧 32dp 紧凑动作组，告别臃肿双大按钮）
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(G.surface)
                                        .border(
                                            if (liveHostSession != null) 1.dp else 0.75.dp,
                                            if (liveHostSession != null) G.accent.copy(alpha = 0.55f) else G.border,
                                            RoundedCornerShape(13.dp),
                                        )
                                        .clickable {
                                            if (liveHostSession != null) onOpenTerminalSession(liveHostSession.id)
                                            else onOpenTerminal(settings.defaultCwd, host)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            // 左侧 36dp 图标徽标 + 在线/连通状态指示点
                                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(if (liveHostSession != null) G.accentContainer else G.surfaceSubtle)
                                                        .border(0.5.dp, if (liveHostSession != null) G.accent.copy(alpha = 0.4f) else G.border, RoundedCornerShape(10.dp)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(GaliaisIcons.Server, contentDescription = null, tint = G.accent, modifier = Modifier.size(17.dp))
                                                }
                                                val statusDotColor = when {
                                                    liveHostSession != null -> G.success
                                                    test?.ok == true -> G.success
                                                    test?.ok == false -> G.danger
                                                    else -> null
                                                }
                                                if (statusDotColor != null) {
                                                    val badgeVisual = if (liveHostSession != null || test?.ok == true) StatusVisual.SUCCESS else StatusVisual.DANGER
                                                    Box(
                                                        Modifier
                                                            .align(Alignment.TopEnd)
                                                            .size(14.dp)
                                                            .clip(CircleShape)
                                                            .background(G.surface),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        StatusGlyph(visual = badgeVisual, color = statusDotColor, size = 12.dp)
                                                    }
                                                }
                                            }

                                            // 中部紧凑两行核心信息（名称 + 别名 + 延迟/在线；地址 + 鉴权）
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    Text(
                                                        host.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = G.ink,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(5.dp))
                                                            .background(G.accentContainer)
                                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                                    ) {
                                                        Text(host.alias, fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = G.accent, maxLines = 1)
                                                    }
                                                    if (test?.ok == true) {
                                                        Text("${test.latencyMs}ms", fontFamily = Mono, fontSize = 9.5.sp, color = G.success, fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                                val authLabel = if (host.authType == "password") "密码" else host.keyName.ifBlank { "默认密钥" }
                                                val metaLine = buildString {
                                                    append("${host.user}@${host.host}:${host.port}")
                                                    append(" · ")
                                                    append(authLabel)
                                                    if (host.proxyJump.isNotBlank()) append(" · 跳板 ${host.proxyJump}")
                                                }
                                                Text(
                                                    metaLine,
                                                    fontFamily = Mono,
                                                    fontSize = 11.sp,
                                                    color = G.inkMuted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }

                                            // 右侧对齐 32dp 紧凑动作组（连接终端 + SFTP + 溢出菜单）
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(G.accent)
                                                        .clickable {
                                                            if (liveHostSession != null) onOpenTerminalSession(liveHostSession.id)
                                                            else onOpenTerminal(settings.defaultCwd, host)
                                                        }
                                                        .padding(horizontal = 9.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Icon(GaliaisIcons.Terminal, contentDescription = null, tint = G.onAccent, modifier = Modifier.size(13.dp))
                                                        Text(if (liveHostSession != null) "切回" else "连接", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.onAccent)
                                                    }
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(G.surfaceSubtle)
                                                        .border(0.75.dp, G.border, RoundedCornerShape(8.dp))
                                                        .clickable { sftpHost = host },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(GaliaisIcons.Folder, contentDescription = "SFTP 文件", tint = G.ink, modifier = Modifier.size(15.dp))
                                                }
                                                Box {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(30.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { hostMenu = true },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多主机操作", tint = G.inkMuted, modifier = Modifier.size(17.dp))
                                                    }
                                                    GaliaisDropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                                                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            GaliaisMenuRow(
                                                                title = "新建独立终端",
                                                                icon = GaliaisIcons.Terminal,
                                                                onClick = { hostMenu = false; onOpenTerminal(settings.defaultCwd, host) },
                                                            )
                                                            GaliaisMenuRow(
                                                                title = "快捷命令",
                                                                icon = GaliaisIcons.Code,
                                                                onClick = { hostMenu = false; execHost = host },
                                                            )
                                                            GaliaisMenuRow(
                                                                title = if (testing) "测速中…" else "连通测速",
                                                                icon = GaliaisIcons.Activity,
                                                                enabled = !testing,
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
                                                            if (keys.isNotEmpty()) {
                                                                GaliaisMenuRow(
                                                                    title = "部署公钥到主机",
                                                                    icon = GaliaisIcons.Key,
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
                                        if (test != null && !test.ok) {
                                            Text(
                                                "连接失败：${test.error}",
                                                fontFamily = Mono,
                                                fontSize = 10.5.sp,
                                                color = G.danger,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 46.dp),
                                            )
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
                        trailing = { CompactActionButton("+ 新建隧道", primary = true, enabled = hosts.isNotEmpty(), onClick = { creatingTunnel = true }) },
                    )
                    if (hosts.isEmpty()) {
                        CrispCard(padding = PaddingValues(13.dp)) {
                            Text("请先在「主机」页添加至少一台 SSH 服务器，即可创建本地转发（-L）、远端转发（-R）或 SOCKS5 动态代理隧道（-D）。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else if (savedTunnels.isEmpty()) {
                        CrispCard(padding = PaddingValues(13.dp)) {
                            Text("暂无隧道配置。创建 -L 本地转发后，可直接配合内置预览在手机打开远端 Web 服务或数据库端口。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        savedTunnels.forEach { preset ->
                            val active = activeTunnels.find { it.id == preset.id }
                            val running = active?.status == "running"
                            val busy = busyTunnelIds[preset.id] == true || active?.status in listOf("starting", "stopping")
                            CrispCard(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val tunnelTint = when {
                                        busy -> G.warning
                                        running -> G.success
                                        else -> G.inkSubtle
                                    }
                                    val tunnelBg = when {
                                        busy -> G.warningTint
                                        running -> G.successTint
                                        else -> G.surfaceSubtle
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(tunnelBg)
                                            .border(0.75.dp, tunnelTint.copy(alpha = if (running || busy) 0.28f else 0.16f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (busy) StatusProgressArc(G.warning, size = 17.dp, strokeWidth = 2.dp)
                                        else Icon(GaliaisIcons.TunnelLink, contentDescription = null, tint = tunnelTint, modifier = Modifier.size(18.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(preset.name, style = MaterialTheme.typography.bodyMedium, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(G.accentContainer).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                                Text(preset.alias, fontFamily = Mono, fontSize = 9.5.sp, color = G.accent)
                                            }
                                        }
                                        Text(preset.summary(), fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (running) {
                                            if (preset.mode == "local") {
                                                TagPill("预览 :${preset.localPort}", fg = G.accent, bg = G.accentContainer, onClick = { previewPort = preset.localPort })
                                            }
                                            CompactActionButton(if (busy) "停止中" else "停止", enabled = !busy, onClick = {
                                                busyTunnelIds[preset.id] = true
                                                scope.launch {
                                                    try { app.envSsh.stopTunnel(preset.id) }
                                                    catch (e: Exception) { app.conversations.notify("停止失败：${e.message}") }
                                                    finally { busyTunnelIds.remove(preset.id) }
                                                }
                                            })
                                        } else {
                                            val canStart = !busy && (preset.mode != "dynamic" || runtime.ready)
                                            CompactActionButton(if (busy) "启动中" else "启动", primary = true, enabled = canStart, onClick = {
                                                busyTunnelIds[preset.id] = true
                                                scope.launch {
                                                    try { app.envSsh.startTunnel(preset) }
                                                    catch (e: Exception) { app.conversations.notify("隧道启动失败：${e.message}") }
                                                    finally { busyTunnelIds.remove(preset.id) }
                                                }
                                            })
                                        }
                                        IconButton(enabled = !busy, onClick = { deletingTunnel = preset }, modifier = Modifier.size(30.dp)) {
                                            Icon(GaliaisIcons.Trash, contentDescription = "删除隧道", tint = G.danger, modifier = Modifier.size(15.dp))
                                        }
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
                            onClick = { showKnownHosts = true },
                        )
                    }
                    SectionHeader(
                        title = "SSH 密钥库（Keystore + ~/.ssh/）",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactActionButton("导入", onClick = { showImportKey = true })
                                CompactActionButton("+ 生成", primary = true, onClick = { showGenerateKey = true })
                            }
                        },
                    )
                    if (keys.isEmpty()) {
                        CrispCard(padding = PaddingValues(13.dp)) {
                            Text("暂无 SSH 密钥。可生成密钥对或导入已有私钥。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    } else {
                        keys.forEach { key ->
                            CrispCard(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(G.surfaceSubtle)
                                            .border(0.5.dp, G.border, RoundedCornerShape(9.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(GaliaisIcons.Key, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(key.name, fontFamily = Mono, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            TagPill("${key.keyType}${if (key.bits > 0) " ${key.bits}" else ""}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                            if (key.isDefault) TagPill("默认", fg = G.success, bg = G.successTint)
                                        }
                                        if (key.fingerprint.isNotBlank()) {
                                            Text(key.fingerprint, fontFamily = Mono, fontSize = 10.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (key.publicKey.isNotBlank()) {
                                            TagPill("公钥", fg = G.accent, bg = G.accentContainer, onClick = {
                                                scope.launch {
                                                    clipboard.setClipEntry(ClipData.newPlainText("ssh-pub", key.publicKey).toClipEntry())
                                                    app.conversations.notify("已复制公钥：${key.name}.pub")
                                                }
                                            })
                                        }
                                        TagPill("口令", fg = G.ink, bg = G.surfaceSubtle, onClick = { passphraseKey = key.name })
                                        IconButton(onClick = { deletingKey = key.name }, modifier = Modifier.size(28.dp)) {
                                            Icon(GaliaisIcons.Trash, contentDescription = "删除密钥", tint = G.danger, modifier = Modifier.size(14.dp))
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
            }
            Spacer(Modifier.height(20.dp + LocalFloatingNavBottomInset.current))
        }
    }

    if (showQuickConnect) {
        QuickConnectSshDialog(
            availableKeys = keys,
            onDismiss = { showQuickConnect = false },
            onConnect = { host, save, pwd ->
                showQuickConnect = false
                if (save) runCatching { app.envSsh.upsertSshHost(host, pwd) }
                else if (!pwd.isNullOrEmpty()) app.envSsh.nativeSsh.putEphemeralPassword(host.id, pwd)
                onOpenTerminal(settings.defaultCwd, host)
            },
        )
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
