package com.galiais.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.HostPluginInfo
import com.galiais.agent.engine.HostPluginSnapshot
import com.galiais.agent.engine.HostPluginSourceSnapshot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Solbraid Host 插件微内核管理区（Generation 生命周期、安全模式、LKG 回滚、Schema 设置、WebView 面板与本地脚手架）。 */
@Composable
fun HostPluginsSection(
    app: GaliaisApplication,
    hostPlugins: HostPluginSnapshot,
    runtimeReady: Boolean,
    defaultCwd: String,
    onSharePlugin: (String) -> Unit = {},
    onBusy: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showCorePlugins by remember { mutableStateOf(false) }
    var editingSettings by remember { mutableStateOf<HostPluginInfo?>(null) }
    var editingGrants by remember { mutableStateOf<HostPluginInfo?>(null) }
    var viewingLogs by remember { mutableStateOf<HostPluginInfo?>(null) }
    var viewingSnapshots by remember { mutableStateOf<HostPluginInfo?>(null) }
    var snapshotList by remember { mutableStateOf<List<HostPluginSourceSnapshot>>(emptyList()) }
    var loadingSnapshots by remember { mutableStateOf(false) }
    var creatingLocal by remember { mutableStateOf(false) }
    var openingPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }
    var openingSurface by remember { mutableStateOf<Pair<String, String>?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Solbraid 插件内核（Host Microkernel）")
        Spacer(Modifier.weight(1f))
        PrimaryPillButton("+ 本地插件", enabled = runtimeReady, onClick = { creatingLocal = true })
    }

    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "运行代际 ${hostPlugins.generationId.ifBlank { "未启动" }}",
                    style = MaterialTheme.typography.titleSmall,
                    color = G.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                TagPill(
                    text = if (hostPlugins.safeMode) "安全模式" else "运行中 ${hostPlugins.activeCount}/${hostPlugins.plugins.size}",
                    fg = if (hostPlugins.safeMode) G.warning else G.success,
                    bg = if (hostPlugins.safeMode) G.warningTint else G.successTint,
                )
                if (hostPlugins.agentControlAvailable) {
                    TagPill(
                        text = "Agent 自进化 MCP 已连接",
                        fg = G.accent,
                        bg = G.accentContainer,
                    )
                }
                if (hostPlugins.studioOverrides.isNotEmpty()) {
                    TagPill(
                        text = "工作台热覆写 (${hostPlugins.studioOverrides.size})",
                        fg = G.warning,
                        bg = G.warningTint,
                    )
                }
            }
            Text(
                "微内核双执行层（同进程受信任 + Worker 线程强杀隔离 · 20 项能力 · 14 工具 MCP），支持 Agent 热升级插件、渲染原生 Compose SDUI、扩展动态驱动/协议转换及热覆写工作台。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            if (hostPlugins.studioOverrides.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hostPlugins.studioOverrides.forEach { fileName ->
                        TagPill(
                            text = "重置 $fileName",
                            fg = G.warning,
                            bg = G.warningTint,
                            onClick = {
                                scope.launch {
                                    onBusy("正在恢复原版 $fileName…")
                                    try { app.extensions.resetStudioOverride(fileName) }
                                    catch (e: Exception) { app.conversations.notify("重置失败：${e.message}") }
                                    finally { onBusy(null) }
                                }
                            },
                        )
                    }
                }
            }
            SettingsToggleRow(
                title = "安全模式（Safe Mode）",
                subtitle = "仅启动 ${hostPlugins.corePlugins.size.coerceAtLeast(1)} 个核心内核服务，跳过所有扩展插件以快速排障",
                checked = hostPlugins.safeMode,
                enabled = runtimeReady,
                onCheckedChange = { enable ->
                    scope.launch {
                        onBusy(if (enable) "正在切换至插件安全模式…" else "正在退出安全模式并重载代际…")
                        try { app.extensions.setHostPluginSafeMode(enable) }
                        catch (e: Exception) { app.conversations.notify("安全模式切换失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryPillButton("重载代际", enabled = runtimeReady, onClick = {
                    scope.launch {
                        onBusy("正在重新扫描并激活 Host 插件…")
                        try { app.extensions.reloadHostPlugins() }
                        catch (e: Exception) { app.conversations.notify("重载失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                })
                if (hostPlugins.hasLastKnownGood) {
                    SecondaryPillButton("回滚快照", enabled = runtimeReady, onClick = {
                        scope.launch {
                            onBusy("正在回滚至上次正常快照…")
                            try { app.extensions.rollbackHostPlugins() }
                            catch (e: Exception) { app.conversations.notify("回滚失败：${e.message}") }
                            finally { onBusy(null) }
                        }
                    })
                }
            }
        }
    }

    if (hostPlugins.extensionPlugins.isEmpty()) {
        CrispCard {
            Text(
                if (runtimeReady) "暂无运行时扩展插件，可点击右上角「+ 本地插件」生成脚手架。" else "运行环境就绪后将自动加载 Host 内置与本地插件。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
        }
    } else {
        CrispCard(padding = PaddingValues(0.dp)) {
            hostPlugins.extensionPlugins.forEachIndexed { idx, plugin ->
                val (statusLabel, statusFg, statusBg) = hostPluginStatusStyle(plugin.status)
                val kindLabel = when (plugin.kind) {
                    "builtin" -> "内置"
                    "community" -> "社区"
                    else -> "本地"
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                TagPill("v${plugin.version}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                TagPill(kindLabel, fg = G.inkMuted, bg = G.surfaceSubtle)
                                if (plugin.overriddenBuiltin) {
                                    TagPill("已覆写内置", fg = G.warning, bg = G.warningTint)
                                }
                                if (plugin.isWorkerIsolated) {
                                    TagPill("线程隔离", fg = G.success, bg = G.successTint)
                                }
                                TagPill(statusLabel, fg = statusFg, bg = statusBg)
                            }
                            if (plugin.description.isNotBlank()) {
                                Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                        }
                        GaliaisSwitch(
                            checked = plugin.enabled,
                            enabled = runtimeReady && !hostPlugins.safeMode,
                            onCheckedChange = { want ->
                                scope.launch {
                                    try { app.extensions.toggleHostPlugin(plugin.id, want) }
                                    catch (e: Exception) { app.conversations.notify("切换失败：${e.message}") }
                                }
                            },
                        )
                    }
                    if (!plugin.error.isNullOrBlank()) {
                        Text(plugin.error, style = MaterialTheme.typography.labelSmall, color = G.danger)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (plugin.commands.isNotEmpty()) {
                            TagPill(plugin.commands.joinToString(" ") { "/${it.name}" }, fg = G.accent, bg = G.accentContainer)
                        }
                        if (plugin.composerActions.isNotEmpty()) {
                            TagPill("输入栏 +${plugin.composerActions.size}", fg = G.accent, bg = G.accentContainer)
                        }
                        if (plugin.uiSlots.isNotEmpty()) {
                            TagPill("槽位 +${plugin.uiSlots.size}", fg = G.accent, bg = G.accentContainer)
                        }
                        if (plugin.status == "active") {
                            plugin.surfaces.forEach { surface ->
                                TagPill(
                                    text = "原生：${surface.title}",
                                    fg = G.success,
                                    bg = G.successTint,
                                    onClick = { openingSurface = plugin.id to surface.id },
                                )
                            }
                        }
                        plugin.translators.forEach { tr ->
                            TagPill("协议：${tr.name}", fg = G.inkMuted, bg = G.surfaceSubtle)
                        }
                        hostPlugins.drivers.filter { it.pluginId == plugin.id }.forEach { drv ->
                            TagPill(
                                text = if (drv.executable) "动态驱动：${drv.name}" else "驱动：${drv.name}",
                                fg = G.accent,
                                bg = G.accentContainer,
                            )
                        }
                        if (plugin.requiredCapabilities.isNotEmpty() || plugin.optionalCapabilities.isNotEmpty()) {
                            TagPill(
                                text = "权限 ${plugin.grantedCapabilities.size} 项",
                                fg = G.inkMuted,
                                bg = G.surfaceSubtle,
                                onClick = { editingGrants = plugin },
                            )
                        }
                        if (plugin.settingsFields.isNotEmpty()) {
                            TagPill("配置", fg = G.accent, bg = G.accentContainer, onClick = { editingSettings = plugin })
                        }
                        if (plugin.status == "active") {
                            plugin.panels.forEach { panel ->
                                TagPill(
                                    text = "面板：${panel.title}",
                                    fg = G.accent,
                                    bg = G.accentContainer,
                                    onClick = {
                                        scope.launch {
                                            onBusy("正在渲染插件面板…")
                                            try {
                                                val art = app.extensions.renderHostPluginPanel(plugin.id, panel.id, defaultCwd)
                                                openingPanel = plugin.id to art
                                            } catch (e: Exception) {
                                                app.conversations.notify("打开面板失败：${e.message}")
                                            } finally {
                                                onBusy(null)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (runtimeReady && !hostPlugins.safeMode) {
                            TagPill("热载", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                scope.launch {
                                    onBusy("正在热载 ${plugin.name}…")
                                    try { app.extensions.reloadSingleHostPlugin(plugin.id) }
                                    catch (e: Exception) { app.conversations.notify("热载失败：${e.message}") }
                                    finally { onBusy(null) }
                                }
                            })
                        }
                        if (plugin.snapshotCount > 0) {
                            TagPill("快照 (${plugin.snapshotCount})", fg = G.accent, bg = G.accentContainer, onClick = {
                                viewingSnapshots = plugin
                                snapshotList = emptyList()
                                loadingSnapshots = true
                                scope.launch {
                                    try { snapshotList = app.extensions.listHostPluginSnapshots(plugin.id) }
                                    catch (e: Exception) { app.conversations.notify("读取快照失败：${e.message}") }
                                    finally { loadingSnapshots = false }
                                }
                            })
                        }
                        if (plugin.overriddenBuiltin) {
                            TagPill("恢复原版", fg = G.warning, bg = G.warningTint, onClick = {
                                scope.launch {
                                    onBusy("正在恢复原版内置插件…")
                                    try { app.extensions.restoreBuiltinHostPlugin(plugin.id) }
                                    catch (e: Exception) { app.conversations.notify("恢复失败：${e.message}") }
                                    finally { onBusy(null) }
                                }
                            })
                        }
                        TagPill("日志 (${plugin.recentLogs.size})", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { viewingLogs = plugin })
                        if (plugin.isRemovable) {
                            TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { onSharePlugin(plugin.id) })
                            TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = {
                                scope.launch {
                                    try { app.extensions.removeLocalHostPlugin(plugin.id) }
                                    catch (e: Exception) { app.conversations.notify("卸载失败：${e.message}") }
                                }
                            })
                        }
                    }
                }
                if (idx < hostPlugins.extensionPlugins.lastIndex) Hairline()
            }
        }
    }

    if (hostPlugins.corePlugins.isNotEmpty()) {
        CrispCard(padding = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCorePlugins = !showCorePlugins }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "核心内核插件（${hostPlugins.corePlugins.size} 个常驻服务）",
                        style = MaterialTheme.typography.titleSmall,
                        color = G.ink,
                        fontWeight = FontWeight.Medium,
                    )
                    AnimatedChevron(expanded = showCorePlugins, tint = G.inkMuted)
                }
                AnimatedExpandable(expanded = showCorePlugins) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        hostPlugins.corePlugins.forEachIndexed { idx, core ->
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(core.name, style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f, fill = false))
                                    TagPill("核心", fg = G.accent, bg = G.accentContainer)
                                    if (core.status == "active") TagPill("运行中", fg = G.success, bg = G.successTint)
                                }
                                Text(core.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                if (core.services.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        core.services.forEach { svc -> TagPill("service:$svc", fg = G.inkMuted, bg = G.surfaceSubtle) }
                                    }
                                }
                                val coreDrivers = hostPlugins.drivers.filter { it.pluginId == core.id }
                                if (coreDrivers.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        coreDrivers.forEach { d -> TagPill("driver:${d.name} (${d.protocol})", fg = G.accent, bg = G.accentContainer) }
                                    }
                                }
                            }
                            if (idx < hostPlugins.corePlugins.lastIndex) Hairline()
                        }
                    }
                }
            }
        }
    }

    editingSettings?.let { plugin ->
        GaliaisSheet(onDismissRequest = { editingSettings = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${plugin.name} · 插件配置", style = MaterialTheme.typography.titleLarge, color = G.ink)
                Text("由插件声明的 JSON Schema 自动渲染，保存后即时生效。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                JsonSchemaForm(
                    formKey = "${plugin.id}:${hostPlugins.generationId}",
                    fields = plugin.settingsFields,
                    initialValues = plugin.settingsValues,
                    submitLabel = "保存配置",
                    cancelLabel = "取消",
                    onSubmit = { obj ->
                        editingSettings = null
                        scope.launch {
                            try { app.extensions.updateHostPluginSettings(plugin.id, obj) }
                            catch (e: Exception) { app.conversations.notify("保存配置失败：${e.message}") }
                        }
                    },
                    onCancel = { editingSettings = null },
                )
            }
        }
    }

    if (creatingLocal) {
        LocalHostPluginCreateSheet(
            onDismiss = { creatingLocal = false },
            onCreate = { id, name, desc ->
                creatingLocal = false
                scope.launch {
                    onBusy("正在生成并激活本地插件脚手架…")
                    try { app.extensions.createLocalHostPlugin(id, name, desc) }
                    catch (e: Exception) { app.conversations.notify("创建失败：${e.message}") }
                    finally { onBusy(null) }
                }
            },
        )
    }

    viewingLogs?.let { plugin ->
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { viewingLogs = null },
            containerColor = G.surface,
            titleContentColor = G.ink,
            textContentColor = G.ink,
            title = { Text("${plugin.name} · 诊断与日志") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("ID: ${plugin.id}  ·  执行层: ${plugin.executionTier}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                    if (plugin.dirPath.isNotBlank()) {
                        Text("目录: ${plugin.dirPath}", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (plugin.grantedCapabilities.isNotEmpty()) {
                        Text("已授能力: ${plugin.grantedCapabilities.joinToString(", ")}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                    }
                    Hairline()
                    if (plugin.recentLogs.isEmpty()) {
                        Text("暂无运行日志", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                    } else {
                        plugin.recentLogs.forEach { log ->
                            val ts = if (log.ts > 0L) fmt.format(Date(log.ts)) else "--:--:--"
                            val color = when (log.level) {
                                "error" -> G.danger
                                "warn" -> G.warning
                                else -> G.ink
                            }
                            Text("[$ts ${log.level.uppercase()}] ${log.message}", fontFamily = Mono, fontSize = 11.sp, color = color)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewingLogs = null }) { Text("关闭") } },
        )
    }

    editingGrants?.let { plugin ->
        HostPluginGrantsSheet(
            plugin = plugin,
            onDismiss = { editingGrants = null },
            onSave = { nextCaps ->
                editingGrants = null
                scope.launch {
                    onBusy("正在应用插件权限授权并热重载…")
                    try { app.extensions.updateHostPluginGrants(plugin.id, nextCaps) }
                    catch (e: Exception) { app.conversations.notify("权限更新失败：${e.message}") }
                    finally { onBusy(null) }
                }
            },
        )
    }

    viewingSnapshots?.let { plugin ->
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
        GaliaisSheet(onDismissRequest = { viewingSnapshots = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${plugin.name} · 源码快照", style = MaterialTheme.typography.titleLarge, color = G.ink)
                Text("每次由 Agent 升级或覆写前均会自动归档完整源码快照，可一键回滚至任意历史版本。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                if (loadingSnapshots) {
                    Text("正在读取快照记录…", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                } else if (snapshotList.isEmpty()) {
                    Text("暂无可回滚的历史快照", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        snapshotList.forEachIndexed { idx, snap ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("v${snap.version}", style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (snap.createdAt > 0L) fmt.format(Date(snap.createdAt)) else snap.id,
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = G.inkMuted,
                                        )
                                        TagPill("${snap.fileCount} 文件", fg = G.inkMuted, bg = G.surfaceSubtle)
                                    }
                                    if (snap.note.isNotBlank()) {
                                        Text(snap.note, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                    }
                                }
                                SecondaryPillButton("回滚", enabled = runtimeReady, onClick = {
                                    viewingSnapshots = null
                                    scope.launch {
                                        onBusy("正在回滚 ${plugin.name} 至 v${snap.version}…")
                                        try { app.extensions.rollbackHostPluginSnapshot(plugin.id, snap.id) }
                                        catch (e: Exception) { app.conversations.notify("回滚失败：${e.message}") }
                                        finally { onBusy(null) }
                                    }
                                })
                            }
                            if (idx < snapshotList.lastIndex) Hairline()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SecondaryPillButton("关闭", onClick = { viewingSnapshots = null })
                }
            }
        }
    }

    openingSurface?.let { (pid, sid) ->
        PluginSduiSheet(
            app = app,
            pluginId = pid,
            surfaceId = sid,
            cwd = defaultCwd,
            onOpenPanel = { panelPid, art ->
                openingSurface = null
                openingPanel = panelPid to art
            },
            onOpenSurface = { nextPid, nextSid ->
                openingSurface = nextPid to nextSid
            },
            onDismiss = { openingSurface = null },
        )
    }

    openingPanel?.let { (pid, artifact) ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(artifact),
            initialArtifactId = artifact.id,
            cwd = defaultCwd,
            pluginId = pid,
            onDismiss = { openingPanel = null },
        )
    }
}

@Composable
private fun HostPluginGrantsSheet(
    plugin: HostPluginInfo,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val allDeclared = remember(plugin.id) {
        (plugin.requiredCapabilities + plugin.optionalCapabilities).distinct()
    }
    var selected by remember(plugin.id, plugin.grantedCapabilities) {
        mutableStateOf(plugin.grantedCapabilities.toSet())
    }
    val capDescriptions = remember {
        mapOf(
            "commands" to "向聊天输入框注册 `/` 快捷命令",
            "settings.schema" to "声明原生 JSON Schema 配置页",
            "ui.panel" to "渲染隔离 WebView 交互看板",
            "ui.composerAction" to "向聊天输入栏 `+` 菜单注册一键快捷动作",
            "ui.sdui" to "渲染声明式原生 Jetpack Compose SDUI 界面（零 WebView）",
            "ui.slot" to "挂载首页横幅、聊天顶栏、消息底栏或工作台工具栏原生槽位",
            "hooks.prompt" to "在发送前与回复后挂载可隔离的 Prompt/响应钩子",
            "workspace.read" to "只读扫描工作区目录与文件内容",
            "workspace.write" to "在工作区目录内安全写入与更新文件（受限路径）",
            "storage.local" to "256KB 配额隔离本地持久化存储",
            "events.observe" to "订阅只读不可变会话观测事件",
            "rpc" to "暴露限定命名空间 plugin.<id>.* RPC 方法",
            "services.provide" to "向内核服务总线提供服务",
            "services.consume" to "消费核心内核总线暴露的服务",
            "agent.driver" to "注册可执行动态流式 Agent 驱动引擎",
            "provider.translator" to "注册热插拔自定义 LLM API 协议转换器",
            "deps.npm" to "受控安装插件专属第三方 npm 依赖（强制 --ignore-scripts）",
        )
    }
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${plugin.name} · 能力授权", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text(
                "遵循 Fail-Closed 协商：撤销必需能力（required）将阻止插件激活；撤销可选能力（optional）仅关闭对应特性。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            allDeclared.forEach { cap ->
                val isReq = cap in plugin.requiredCapabilities
                val checked = cap in selected
                SettingsToggleRow(
                    title = "$cap" + if (isReq) "（必需）" else "（可选）",
                    subtitle = capDescriptions[cap] ?: "插件声明能力",
                    checked = checked,
                    onCheckedChange = { want ->
                        selected = if (want) selected + cap else selected - cap
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并生效", onClick = { onSave(selected.toList()) })
            }
        }
    }
}

@Composable
private fun hostPluginStatusStyle(status: String) = when (status) {
    "active" -> Triple("运行中", G.success, G.successTint)
    "safe_mode_skipped" -> Triple("安全模式跳过", G.warning, G.warningTint)
    "failed" -> Triple("运行异常", G.danger, G.dangerTint)
    "incompatible" -> Triple("能力不兼容", G.danger, G.dangerTint)
    "invalid" -> Triple("清单无效", G.danger, G.dangerTint)
    else -> Triple("已停用", G.inkMuted, G.surfaceSubtle)
}

@Composable
private fun LocalHostPluginCreateSheet(
    onDismiss: () -> Unit,
    onCreate: (id: String, name: String, description: String) -> Unit,
) {
    var id by remember { mutableStateOf("local.my-tool") }
    var name by remember { mutableStateOf("我的本地插件") }
    var description by remember { mutableStateOf("包含 /命令、Schema 设置、本地存储与 WebView 面板的本地扩展") }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("新建本地 Host 插件", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text("将在 ~/.galiais/plugins/<id>/ 下生成 solbraid-plugin.json 与 index.mjs 并立即热激活。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            OutlinedTextField(
                value = id,
                onValueChange = { id = it.lowercase(); error = null },
                label = { Text("插件 ID（小写字母/数字/点/短横线）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("插件显示名称") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("功能简介") },
                minLines = 2,
                maxLines = 4,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("生成并激活", onClick = {
                    val cleanId = id.trim()
                    val cleanName = name.trim()
                    if (!Regex("^[a-z0-9][a-z0-9._-]{1,63}$").matches(cleanId)) {
                        error = "插件 ID 需 2~64 位小写字母、数字、点、短横线或下划线"
                        return@PrimaryPillButton
                    }
                    if (cleanName.isBlank()) {
                        error = "请填写插件名称"
                        return@PrimaryPillButton
                    }
                    onCreate(cleanId, cleanName, description.trim())
                })
            }
        }
    }
}
