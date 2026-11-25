package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.AgentManifest
import com.galiais.agent.engine.AgentRuntimeStatus
import kotlinx.coroutines.launch

@Composable
fun AgentsScreen(
    app: GaliaisApplication,
    modifier: Modifier,
    onOpenAgent: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val statuses by app.conversations.hub.statuses.collectAsState()
    val cliStates by app.conversations.cliUpdates.states.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showAcceleration by remember { mutableStateOf(false) }

    LaunchedEffect(runtime.ready) {
        if (runtime.ready && statuses.isEmpty()) {
            refreshing = true
            try { app.conversations.hub.configureAndProbe() } finally { refreshing = false }
        }
    }

    val installedAgents = AgentCatalog.all()

    Column(
        modifier
            .fillMaxSize()
            .galiaisCanvasBackground()
            .let { if (onBack != null) it.statusBarsPadding().navigationBarsPadding() else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (D.material) 64.dp else 56.dp)
                .padding(start = if (onBack != null) 4.dp else 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Agent 管理", style = MaterialTheme.typography.titleLarge, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("内置三大原生编码 Agent", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TagPill("检查更新", fg = G.accent, bg = G.accentContainer, onClick = { app.conversations.cliUpdates.check() })
            IconButton(
                enabled = runtime.ready && !refreshing,
                modifier = Modifier.size(38.dp),
                onClick = {
                    scope.launch {
                        refreshing = true
                        try { app.conversations.hub.refreshStatuses() }
                        catch (e: Exception) { app.conversations.notify("刷新 Agent 状态失败：${e.message}") }
                        finally { refreshing = false }
                    }
                },
            ) {
                Icon(GaliaisIcons.Refresh, contentDescription = "刷新 Agent 状态", tint = if (runtime.ready && !refreshing) G.inkMuted else G.inkSubtle, modifier = Modifier.size(19.dp))
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
                            Text("启动后可使用 Codex、Claude Code 与 Antigravity。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                        PrimaryPillButton("启动", onClick = app.runtime::start, enabled = runtime.bound && !runtime.busy)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("已内置（${installedAgents.size}）")
                Spacer(Modifier.weight(1f))
                TagPill("下载镜像与代理", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = { showAcceleration = true })
            }
            installedAgents.forEach { agent ->
                val status = statuses[agent.id]
                val cliVer = cliStates[agent.id]?.currentVersion?.takeIf { it.isNotBlank() }
                val provName = settings.provider(settings.defaultProviderIdFor(agent.engineKind))?.name
                val modelName = settings.defaultModelFor(agent.engineKind)
                InstalledAgentCard(
                    app = app,
                    manifest = agent,
                    status = status,
                    versionFallback = cliVer,
                    runtimeReady = runtime.ready,
                    providerSummary = buildString {
                        append(provName ?: when (agent.id) {
                            "codex" -> "OpenAI 官方"
                            "claude" -> "Anthropic 官方"
                            "antigravity" -> "Google 官方"
                            else -> "Agent 自带配置"
                        })
                        if (!modelName.isNullOrBlank()) append(" · $modelName")
                    },
                    onOpenDetail = { onOpenAgent(agent.id) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAcceleration) {
        DownloadAccelerationSheet(app = app, onDismiss = { showAcceleration = false })
    }
}

@Composable
private fun InstalledAgentCard(
    app: GaliaisApplication,
    manifest: AgentManifest,
    status: AgentRuntimeStatus?,
    versionFallback: String?,
    runtimeReady: Boolean,
    providerSummary: String,
    onOpenDetail: () -> Unit,
) {
    val accent = agentAccent(manifest.engineKind)
    val tint = agentTint(manifest.engineKind)
    CrispCard(onClick = onOpenDetail, padding = PaddingValues(D.space(14.dp))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                AgentAppIcon(engine = manifest.engineKind, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(manifest.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TagPill(if (manifest.id == "antigravity") "Antigravity 原生" else manifest.protocol.badge, fg = accent, bg = tint)
                        if (manifest.preinstalled) TagPill("内置", fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                    Text(providerSummary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val stateLabel = when {
                    !runtimeReady -> "待启动"
                    status?.installing == true -> "安装中…"
                    status?.error != null -> "启动失败"
                    manifest.protocol.isNative || manifest.id == "antigravity" -> if (status?.running == true) "运行中" else "已连接"
                    status?.running == true -> "运行中"
                    else -> "就绪"
                }
                TagPill(stateLabel, fg = if (status?.error != null) G.danger else if (runtimeReady) G.success else G.inkMuted,
                    bg = if (status?.error != null) G.dangerTint else if (runtimeReady) G.successTint else G.surfaceSubtle)
                (status?.version?.takeIf { it.isNotBlank() } ?: versionFallback)?.let { TagPill(it.take(28), fg = G.inkMuted, bg = G.surfaceSubtle) }
                if (manifest.capabilities.approval) TagPill("操作审批", fg = G.inkMuted, bg = G.surfaceSubtle)
                if (manifest.capabilities.steer) TagPill("插话", fg = G.inkMuted, bg = G.surfaceSubtle)
                if (manifest.capabilities.modes) TagPill("模式切换", fg = G.inkMuted, bg = G.surfaceSubtle)
                if (manifest.capabilities.customProvider) TagPill("支持自定义服务商", fg = G.inkMuted, bg = G.surfaceSubtle)
            }
            status?.let { AgentInstallProgressView(it) }
            (status?.installError ?: status?.error)?.let {
                Text(it.lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = G.danger)
            }
            CliUpdateControls(app, manifest.id, showCheck = false)
        }
    }
}

