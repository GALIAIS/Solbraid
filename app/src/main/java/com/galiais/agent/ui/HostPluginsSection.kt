package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.HostPluginInfo
import com.galiais.agent.engine.HostPluginSnapshot
import com.galiais.agent.engine.HostPluginSourceSnapshot
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
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
    searchQuery: String = "",
    onOpenSourceEditor: (FileRef, pluginId: String) -> Unit = { _, _ -> },
    onBusy: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showCorePlugins by remember { mutableStateOf(false) }
    var editingSettings by remember { mutableStateOf<HostPluginInfo?>(null) }
    var editingGrants by remember { mutableStateOf<HostPluginInfo?>(null) }
    var viewingLogs by remember { mutableStateOf<HostPluginInfo?>(null) }
    var viewingSnapshots by remember { mutableStateOf<HostPluginInfo?>(null) }
    var snapshotList by remember { mutableStateOf<List<HostPluginSourceSnapshot>>(emptyList()) }
    var loadingSnapshots by remember { mutableStateOf(false) }
    var creatingLocal by remember { mutableStateOf(false) }
    var importingBundle by remember { mutableStateOf(false) }
    var openingPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }
    var openingSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingConfirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val q = searchQuery.trim().lowercase()
    val filteredExt = remember(hostPlugins.extensionPlugins, q) {
        if (q.isEmpty()) hostPlugins.extensionPlugins
        else hostPlugins.extensionPlugins.filter { it.name.lowercase().contains(q) || it.id.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }
    val filteredCore = remember(hostPlugins.corePlugins, q) {
        if (q.isEmpty()) hostPlugins.corePlugins
        else hostPlugins.corePlugins.filter { it.name.lowercase().contains(q) || it.id.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }

    val driversByPlugin = remember(hostPlugins.drivers) { hostPlugins.drivers.groupBy { it.pluginId } }

    SectionHeader("本地插件") {
        CompactActionButton("导入 JSON", enabled = runtimeReady, onClick = { importingBundle = true })
        CompactActionButton("+ 本地插件", primary = true, enabled = runtimeReady, onClick = { creatingLocal = true })
    }

    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (hostPlugins.generationId.isBlank()) "未启动" else "插件已加载",
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
                    TagPill("插件管理 MCP", fg = G.accent, bg = G.accentContainer)
                }
                if (hostPlugins.studioOverrides.isNotEmpty()) {
                    TagPill("自定义工作台文件 ${hostPlugins.studioOverrides.size} 项", fg = G.warning, bg = G.warningTint)
                }
            }
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
                title = "安全模式",
                subtitle = "仅加载核心插件，暂停扩展插件",
                checked = hostPlugins.safeMode,
                enabled = runtimeReady,
                onCheckedChange = { enable ->
                    scope.launch {
                        onBusy(if (enable) "正在切换至插件安全模式…" else "正在退出安全模式…")
                        try { app.extensions.setHostPluginSafeMode(enable) }
                        catch (e: Exception) { app.conversations.notify("安全模式切换失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactActionButton("重新加载插件", enabled = runtimeReady, onClick = {
                    scope.launch {
                        onBusy("正在重新加载插件…")
                        try { app.extensions.reloadHostPlugins() }
                        catch (e: Exception) { app.conversations.notify("重载失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                })
                if (hostPlugins.hasLastKnownGood) {
                    CompactActionButton("回滚快照", enabled = runtimeReady, onClick = {
                        pendingConfirm = Triple("恢复插件快照？", "将恢复上次成功加载的配置并重新加载插件。") {
                            scope.launch {
                                onBusy("正在回滚至上次正常快照…")
                                try { app.extensions.rollbackHostPlugins() }
                                catch (e: Exception) { app.conversations.notify("回滚失败：${e.message}") }
                                finally { onBusy(null) }
                            }
                        }
                    })
                }
            }
        }
    }

    if (filteredExt.isEmpty()) {
        CrispCard {
            Text(
                when {
                    q.isNotEmpty() -> "未找到匹配「$searchQuery」的扩展插件。"
                    runtimeReady -> "暂无扩展插件。可创建本地插件或导入 JSON。"
                    else -> "运行环境就绪后将自动加载本地扩展插件。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
        }
    } else {
        CrispCard(padding = PaddingValues(0.dp)) {
            filteredExt.forEachIndexed { idx, plugin ->
                val (statusLabel, statusFg, statusBg) = hostPluginStatusStyle(plugin.status)
                val kindLabel = when (plugin.kind) {
                    "builtin" -> "内置"
                    "community" -> "社区"
                    else -> "本地"
                }
                var menuOpen by remember(plugin.id) { mutableStateOf(false) }
                val pluginDrivers = driversByPlugin[plugin.id].orEmpty()
                val metaParts = buildList {
                    add("v${plugin.version}")
                    add(kindLabel)
                    if (plugin.overriddenBuiltin) add("已覆写内置")
                    if (plugin.isWorkerIsolated) add("线程隔离")
                    if (plugin.commands.isNotEmpty()) add(plugin.commands.joinToString(" ") { "/${it.name}" })
                    if (plugin.composerActions.isNotEmpty()) add("输入栏+${plugin.composerActions.size}")
                    if (plugin.uiSlots.isNotEmpty()) add("槽位+${plugin.uiSlots.size}")
                    if (plugin.translators.isNotEmpty()) add("协议:${plugin.translators.joinToString(",") { it.name }}")
                    if (pluginDrivers.isNotEmpty()) add("驱动:${pluginDrivers.joinToString(",") { it.name }}")
                    if (plugin.requiredCapabilities.isNotEmpty() || plugin.optionalCapabilities.isNotEmpty()) {
                        add("权限 ${plugin.grantedCapabilities.size}")
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            plugin.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = G.ink,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TagPill(statusLabel, fg = statusFg, bg = statusBg)
                        Box(Modifier.weight(1f))
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
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多插件操作", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                            }
                            GaliaisDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (plugin.settingsFields.isNotEmpty()) {
                                        GaliaisMenuRow("插件配置", onClick = { menuOpen = false; editingSettings = plugin })
                                    }
                                    if (plugin.requiredCapabilities.isNotEmpty() || plugin.optionalCapabilities.isNotEmpty()) {
                                        GaliaisMenuRow("管理权限", onClick = { menuOpen = false; editingGrants = plugin })
                                    }
                                    if (runtimeReady && !hostPlugins.safeMode) {
                                        GaliaisMenuRow("热重载插件", onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                onBusy("正在热载 ${plugin.name}…")
                                                try { app.extensions.reloadSingleHostPlugin(plugin.id) }
                                                catch (e: Exception) { app.conversations.notify("热载失败：${e.message}") }
                                                finally { onBusy(null) }
                                            }
                                        })
                                    }
                                    if (plugin.snapshotCount > 0) {
                                        GaliaisMenuRow("源码快照", onClick = {
                                            menuOpen = false
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
                                    GaliaisMenuRow("诊断日志", onClick = { menuOpen = false; viewingLogs = plugin })
                                    if (plugin.overriddenBuiltin) {
                                        GaliaisMenuRow("恢复内置版本", onClick = {
                                            menuOpen = false
                                            pendingConfirm = Triple("恢复原版内置插件？", "将移除本地覆写并恢复内置原版「${plugin.name}」。") {
                                                scope.launch {
                                                    onBusy("正在恢复原版内置插件…")
                                                    try { app.extensions.restoreBuiltinHostPlugin(plugin.id) }
                                                    catch (e: Exception) { app.conversations.notify("恢复失败：${e.message}") }
                                                    finally { onBusy(null) }
                                                }
                                            }
                                        })
                                    }
                                    if (plugin.dirPath.startsWith("/")) {
                                        val dirScope = if (plugin.dirPath.startsWith("/home/agent")) FileScope.LINUX_WORKSPACE else FileScope.LINUX_ROOT
                                        GaliaisMenuRow("编辑入口文件", onClick = {
                                            menuOpen = false
                                            onOpenSourceEditor(FileRef(dirScope, "${plugin.dirPath.trimEnd('/')}/index.mjs"), plugin.id)
                                        })
                                        GaliaisMenuRow("编辑插件清单", onClick = {
                                            menuOpen = false
                                            onOpenSourceEditor(FileRef(dirScope, "${plugin.dirPath.trimEnd('/')}/solbraid-plugin.json"), plugin.id)
                                        })
                                    }
                                    if (plugin.isRemovable) {
                                        GaliaisMenuRow("导出插件 JSON 到剪贴板", onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                try {
                                                    val bundle = app.extensions.exportLocalHostPluginBundle(plugin.id)
                                                    clipboard.setClipEntry(ClipData.newPlainText("solbraid-plugin", bundle.toString(2)).toClipEntry())
                                                    app.conversations.notify("已导出插件 JSON 到剪贴板：${plugin.name}")
                                                } catch (e: Exception) {
                                                    app.conversations.notify("导出失败：${e.message}")
                                                }
                                            }
                                        })
                                        GaliaisMenuRow("卸载插件", tintOverride = G.danger, onClick = {
                                            menuOpen = false
                                            pendingConfirm = Triple("卸载本地插件？", "确定删除插件「${plugin.name}」及其目录文件吗？") {
                                                scope.launch {
                                                    try { app.extensions.removeLocalHostPlugin(plugin.id) }
                                                    catch (e: Exception) { app.conversations.notify("卸载失败：${e.message}") }
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (plugin.description.isNotBlank()) {
                        Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = G.inkSubtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!plugin.error.isNullOrBlank()) {
                        Text(plugin.error, style = MaterialTheme.typography.labelSmall, color = G.danger)
                    }
                    val hasPrimaryActions = (plugin.status == "active" && (plugin.surfaces.isNotEmpty() || plugin.panels.isNotEmpty() || plugin.commands.isNotEmpty())) || plugin.settingsFields.isNotEmpty()
                    if (hasPrimaryActions) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (plugin.status == "active") {
                                plugin.commands.take(3).forEach { cmd ->
                                    CompactActionButton(
                                        text = "运行 /${cmd.name}",
                                        onClick = {
                                            scope.launch {
                                                onBusy("正在运行 /${cmd.name}…")
                                                try {
                                                    val res = app.extensions.runHostPluginCommand(plugin.id, cmd.name, cwd = defaultCwd)
                                                    when (res.kind) {
                                                        "artifact" -> openingPanel = plugin.id to ChatArtifact(
                                                            id = "hostcmd:${plugin.id}:${cmd.name}",
                                                            identifier = "cmd-${plugin.id}-${cmd.name}",
                                                            title = res.title.ifBlank { "/${cmd.name}" },
                                                            kind = com.galiais.agent.engine.ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind),
                                                            language = res.artifactKind,
                                                            content = res.content,
                                                        )
                                                        else -> app.conversations.notify(res.message.ifBlank { res.prompt.ifBlank { "命令已执行" } })
                                                    }
                                                } catch (e: Exception) {
                                                    app.conversations.notify("执行失败：${e.message}")
                                                } finally {
                                                    onBusy(null)
                                                }
                                            }
                                        },
                                    )
                                }
                                plugin.surfaces.forEach { surface ->
                                    CompactActionButton(
                                        text = "打开 ${surface.title}",
                                        onClick = { openingSurface = plugin.id to surface.id },
                                    )
                                }
                                plugin.panels.forEach { panel ->
                                    CompactActionButton(
                                        text = "面板 ${panel.title}",
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
                            if (plugin.settingsFields.isNotEmpty()) {
                                CompactActionButton("配置", onClick = { editingSettings = plugin })
                            }
                        }
                    }
                }
                if (idx < filteredExt.lastIndex) Hairline()
            }
        }
    }

    if (filteredCore.isNotEmpty()) {
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
                        "核心内核插件",
                        style = MaterialTheme.typography.titleSmall,
                        color = G.ink,
                        fontWeight = FontWeight.Medium,
                    )
                    AnimatedChevron(expanded = showCorePlugins, tint = G.inkMuted)
                }
                AnimatedExpandable(expanded = showCorePlugins || q.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        filteredCore.forEachIndexed { idx, core ->
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
                                val coreDrivers = driversByPlugin[core.id].orEmpty()
                                if (coreDrivers.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        coreDrivers.forEach { d -> TagPill(d.name, fg = G.accent, bg = G.accentContainer) }
                                    }
                                }
                            }
                            if (idx < filteredCore.lastIndex) Hairline()
                        }
                    }
                }
            }
        }
    }

    editingSettings?.let { plugin ->
        GaliaisSheetScaffold(
            title = "${plugin.name} · 插件配置",
            subtitle = "修改后保存以应用配置。",
            onDismiss = { editingSettings = null },
        ) {
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

    if (creatingLocal) {
        LocalHostPluginCreateSheet(
            onDismiss = { creatingLocal = false },
            onCreate = { id, name, desc, tier, tpl ->
                creatingLocal = false
                scope.launch {
                    onBusy("正在创建本地插件…")
                    try { app.extensions.createLocalHostPlugin(id, name, desc, executionTier = tier, template = tpl) }
                    catch (e: Exception) { app.conversations.notify("创建失败：${e.message}") }
                    finally { onBusy(null) }
                }
            },
        )
    }

    if (importingBundle) {
        PasteImportSheet(
            app = app,
            title = "导入本地插件",
            subtitle = "粘贴导出的插件 JSON。导入前会检查清单和源码。",
            placeholder = "{ \"payload\": { \"manifest\": {...}, \"files\": {\"index.mjs\": \"...\"} } }",
            confirmLabel = "校验并导入",
            onDismiss = { importingBundle = false },
            onImport = { raw ->
                try {
                    org.json.JSONObject(raw.trim())
                    scope.launch {
                        onBusy("正在校验并激活导入的插件…")
                        try { app.extensions.importLocalHostPluginBundle(raw) }
                        catch (e: Exception) { app.conversations.notify("导入失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                    null
                } catch (e: Exception) {
                    "JSON 格式错误：${e.message}"
                }
            },
        )
    }

    viewingLogs?.let { plugin ->
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        val logDump = remember(plugin) {
            buildString {
                appendLine("ID: ${plugin.id} · 执行层: ${plugin.executionTier}")
                if (plugin.dirPath.isNotBlank()) appendLine("目录: ${plugin.dirPath}")
                if (plugin.grantedCapabilities.isNotEmpty()) appendLine("已授能力: ${plugin.grantedCapabilities.joinToString(", ")}")
                plugin.recentLogs.forEach { log ->
                    val ts = if (log.ts > 0L) fmt.format(Date(log.ts)) else "--:--:--"
                    appendLine("[$ts ${log.level.uppercase()}] ${log.message}")
                }
            }.trim()
        }
        GaliaisAlertDialog(
            title = "${plugin.name} · 诊断与日志",
            onDismissRequest = { viewingLogs = null },
            confirmText = "复制日志",
            onConfirm = {
                scope.launch {
                    clipboard.setClipEntry(ClipData.newPlainText("plugin-logs", logDump).toClipEntry())
                    app.conversations.notify("已复制插件诊断日志")
                }
            },
            dismissText = "关闭",
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
        }
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
        GaliaisSheetScaffold(
            title = "${plugin.name} · 源码快照",
            subtitle = "每次由 Agent 升级或覆写前自动归档，可随时回滚",
            onDismiss = { viewingSnapshots = null },
        ) {
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
                            CompactActionButton("回滚", enabled = runtimeReady, onClick = {
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
        }
    }

    pendingConfirm?.let { (title, message, action) ->
        GaliaisConfirmDialog(
            title = title,
            message = message,
            confirmText = "确认",
            danger = true,
            onConfirm = {
                pendingConfirm = null
                action()
            },
            onDismiss = { pendingConfirm = null },
        )
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
    GaliaisSheetScaffold(
        title = "${plugin.name} · 能力授权",
        subtitle = "撤销必需能力将阻止插件激活；撤销可选能力仅关闭对应特性",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存并生效", modifier = Modifier.weight(1f), onClick = { onSave(selected.toList()) })
        },
    ) {
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
    onCreate: (id: String, name: String, description: String, executionTier: String, template: String) -> Unit,
) {
    var id by remember { mutableStateOf("local.my-tool") }
    var name by remember { mutableStateOf("我的本地插件") }
    var description by remember { mutableStateOf("本地 Solbraid 扩展插件") }
    var executionTier by remember { mutableStateOf("worker-isolated") }
    var template by remember { mutableStateOf("basic") }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = "新建本地 Host 插件",
        subtitle = "在 ~/.galiais/plugins/<id>/ 下生成清单与入口并热激活",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("生成并激活", modifier = Modifier.weight(1f), onClick = {
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
                onCreate(cleanId, cleanName, description.trim(), executionTier, template)
            })
        },
    ) {
        Text("执行层隔离", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        SegmentedPillBar(
            options = listOf("worker-isolated" to "Worker 线程隔离（推荐）", "trusted-in-process" to "同进程运行"),
            selected = executionTier,
            onSelect = { executionTier = it },
        )
        Text("脚手架模板", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "basic" to "命令 + WebView 面板",
                "sdui" to "原生 Compose SDUI",
                "hook" to "Prompt Hook 拦截器",
                "translator" to "自定义协议转换器",
            ).forEach { (key, label) ->
                SelectableChip(label, selected = template == key, onClick = { template = key })
            }
        }
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
    }
}
