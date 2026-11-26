package com.galiais.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ManagedHook
import com.galiais.agent.engine.ManagedInstruction
import com.galiais.agent.engine.ManagedMcpServer
import com.galiais.agent.engine.ManagedPlugin
import com.galiais.agent.engine.ManagedSkill
import com.galiais.agent.engine.ManagedSubagent
import com.galiais.agent.engine.McpProbeResult
import com.galiais.agent.engine.SkillCompanionFile
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.ui.files.CodeEditorDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 统一资源库：单源管理 Host 插件、插件包、指令集、MCP、技能、子代理与生命周期钩子，自动无损投影到全部 Agent。 */
@Composable
fun ExtensionsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val registry by app.extensions.registry.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val workspaceSkills by app.extensions.workspaceSkills.collectAsState()
    val probes by app.extensions.probes.collectAsState()
    val hookTests by app.extensions.hookTests.collectAsState()
    val syncing by app.extensions.syncing.collectAsState()
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("plugins") } // "plugins" | "instructions" | "mcp" | "skills" | "subagents" | "hooks"
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var topMenuOpen by remember { mutableStateOf(false) }
    var showSyncTargets by remember { mutableStateOf(false) }
    var showInstructionsPreview by remember { mutableStateOf(false) }

    var editingMcp by remember { mutableStateOf<ManagedMcpServer?>(null) }
    var creatingMcp by remember { mutableStateOf(false) }
    var viewingMcpTools by remember { mutableStateOf<Pair<String, McpProbeResult>?>(null) }

    var editingSkill by remember { mutableStateOf<ManagedSkill?>(null) }
    var creatingSkill by remember { mutableStateOf(false) }
    var viewingSkillFiles by remember { mutableStateOf<Triple<String, String, List<SkillCompanionFile>>?>(null) }
    var loadingSkillFiles by remember { mutableStateOf(false) }

    var editingInstruction by remember { mutableStateOf<ManagedInstruction?>(null) }
    var creatingInstruction by remember { mutableStateOf(false) }
    var editingSubagent by remember { mutableStateOf<ManagedSubagent?>(null) }
    var creatingSubagent by remember { mutableStateOf(false) }
    var editingHook by remember { mutableStateOf<ManagedHook?>(null) }
    var creatingHook by remember { mutableStateOf(false) }
    var editingPluginPack by remember { mutableStateOf<ManagedPlugin?>(null) }
    var creatingPluginPack by remember { mutableStateOf(false) }

    var importSkillGitHub by remember { mutableStateOf(false) }
    var importPluginGitHub by remember { mutableStateOf(false) }
    var pasteImportMode by remember { mutableStateOf<String?>(null) } // "mcp" | "skill" | "subagent" | "backup"
    var secretPluginTarget by remember { mutableStateOf<ManagedPlugin?>(null) }
    var openingEditor by remember { mutableStateOf<Pair<FileRef, String?>?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    val probingIds = remember { mutableStateMapOf<String, Boolean>() }
    val testingHookIds = remember { mutableStateMapOf<String, Boolean>() }
    val expandedPluginIds = remember { mutableStateMapOf<String, Boolean>() }

    val modelSuggestions = remember(settings.providers, settings.codexModel, settings.claudeModel) {
        buildList {
            settings.claudeModel?.takeIf { it.isNotBlank() }?.let(::add)
            settings.codexModel?.takeIf { it.isNotBlank() }?.let(::add)
            settings.providers.forEach { p -> p.models.forEach { m -> if (m.isNotBlank()) add(m) } }
        }.distinct()
    }
    val q = searchQuery.trim().lowercase()

    fun copyText(label: String, text: String, toast: String) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        app.conversations.notify(toast)
    }

    LaunchedEffect(runtime.ready, settings.defaultCwd) {
        if (runtime.ready) {
            runCatching { app.extensions.refreshSkills(settings.defaultCwd) }
            runCatching { app.extensions.refreshHostPlugins() }
        }
    }

    SubPageScaffold(
        title = "资源库",
        onBack = onBack,
        snackbar = snackbar,
        loading = syncing || busyMessage != null,
        actions = {
            if (runtime.ready) {
                SecondaryPillButton(
                    text = if (syncing) "同步中…" else "同步全部",
                    enabled = !syncing,
                    onClick = {
                        scope.launch {
                            try {
                                app.extensions.syncNow()
                                app.extensions.refreshSkills(settings.defaultCwd)
                                runCatching { app.extensions.refreshHostPlugins() }
                                app.conversations.notify("资源已同步")
                            } catch (e: Exception) {
                                app.conversations.notify("同步失败：${e.message}")
                            }
                        }
                    },
                )
            }
            Box {
                IconButton(onClick = { topMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.MoreHoriz, contentDescription = "资源库更多操作", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("查看同步路径") },
                        onClick = { topMenuOpen = false; showSyncTargets = true },
                    )
                    DropdownMenuItem(
                        text = { Text("导出资源库备份 JSON") },
                        onClick = {
                            topMenuOpen = false
                            copyText("solbraid-extensions", app.extensions.exportAllExtensionsJson(), "已复制备份，密钥未导出")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导入资源库备份 JSON") },
                        onClick = { topMenuOpen = false; pasteImportMode = "backup" },
                    )
                }
            }
        },
    ) {
        if (!runtime.ready) {
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "请启动运行环境以加载插件、检查 MCP 服务或同步资源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    if (!runtime.busy && runtime.bound) {
                        TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                    }
                }
            }
        }

        GaliaisScrollableTabBar(
            options = listOf(
                "plugins" to "插件",
                "instructions" to "指令",
                "mcp" to "MCP",
                "skills" to "技能",
                "subagents" to "子代理",
                "hooks" to "钩子",
            ),
            selected = section,
            onSelect = { section = it },
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索当前分类", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle) },
            leadingIcon = { Icon(GaliaisIcons.Search, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(16.dp)) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                        Icon(GaliaisIcons.Close, contentDescription = "清除筛选", tint = G.inkSubtle, modifier = Modifier.size(15.dp))
                    }
                }
            } else null,
            singleLine = true,
            shape = D.fieldShape,
            colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        if (busyMessage != null) {
            CrispCard(padding = PaddingValues(12.dp)) {
                Text(busyMessage!!, style = MaterialTheme.typography.bodySmall, color = G.accent)
                Spacer(Modifier.height(6.dp))
                GaliaisProgressBar(thin = false)
            }
        }

        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 280.dp)) {
            Crossfade(targetState = section, animationSpec = tween(120), label = "extSection") { activeSection ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
                    when (activeSection) {
                        "plugins" -> {
                            HostPluginsSection(
                                app = app,
                                hostPlugins = hostPlugins,
                                runtimeReady = runtime.ready,
                                defaultCwd = settings.defaultCwd,
                                searchQuery = searchQuery,
                                onOpenSourceEditor = { ref, pluginId -> openingEditor = ref to pluginId },
                                onBusy = { busyMessage = it },
                            )

                            val filteredPacks = remember(registry.plugins, q) {
                                if (q.isEmpty()) registry.plugins
                                else registry.plugins.filter { p ->
                                    p.name.lowercase().contains(q) || p.id.lowercase().contains(q) ||
                                        p.description.lowercase().contains(q) || p.commands.any { it.name.lowercase().contains(q) }
                                }
                            }
                            SectionHeader("插件包") {
                                CompactActionButton("GitHub", enabled = runtime.ready, onClick = { importPluginGitHub = true })
                                CompactActionButton("+ 本地包", primary = true, onClick = { creatingPluginPack = true })
                            }
                            if (filteredPacks.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (registry.plugins.isEmpty()) "创建插件包以管理命令、MCP 服务和技能，也可从 GitHub 导入。"
                                        else "没有匹配「$searchQuery」的插件包。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filteredPacks.forEachIndexed { idx, p ->
                                        val linkedMcps = registry.mcpServers.filter { it.pluginId == p.id }
                                        val linkedSkills = registry.skills.filter { it.pluginId == p.id }
                                        val expanded = expandedPluginIds[p.id] == true
                                        Column(
                                            Modifier.fillMaxWidth().clickable { expandedPluginIds[p.id] = !expanded }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                        TagPill("v${p.version}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                        TagPill(if (p.source == "github") "GitHub" else "本地", fg = G.accent, bg = G.accentContainer)
                                                    }
                                                    if (p.description.isNotBlank()) {
                                                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                GaliaisSwitch(checked = p.enabled, onCheckedChange = { app.extensions.togglePlugin(p.id, it) })
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill("${linkedMcps.size} MCP · ${linkedSkills.size} 技能 · ${p.commands.size} 指令", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                TagPill(if (expanded) "收起详情" else "查看内容", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { expandedPluginIds[p.id] = !expanded })
                                                TagPill("编辑", fg = G.accent, bg = G.accentContainer, onClick = { editingPluginPack = p })
                                                if (p.requiredSecrets.isNotEmpty()) {
                                                    val configured = p.requiredSecrets.count { app.settings.hasExtensionSecret("plugin:${p.id}", it.key) }
                                                    TagPill("密钥 ($configured/${p.requiredSecrets.size})", fg = G.accent, bg = G.accentContainer, onClick = { secretPluginTarget = p })
                                                }
                                                TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = {
                                                    pendingConfirm = Triple("卸载插件包？", "确定卸载「${p.name}」及其关联的 MCP、技能与加密密钥吗？") {
                                                        scope.launch { app.extensions.uninstallPlugin(p.id) }
                                                    }
                                                })
                                            }
                                            if (expanded) {
                                                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        if (p.commands.isNotEmpty()) {
                                                            Text("快捷指令：", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                                                            p.commands.forEach { c ->
                                                                Text("/${c.name}  ${c.description.ifBlank { c.promptTemplate.take(40) }}", fontFamily = Mono, fontSize = 11.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            }
                                                        }
                                                        if (linkedMcps.isNotEmpty()) {
                                                            Text("关联 MCP：${linkedMcps.joinToString("、") { it.name }}", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                                        }
                                                        if (linkedSkills.isNotEmpty()) {
                                                            Text("关联技能：${linkedSkills.joinToString("、") { it.name }}", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                                        }
                                                        if (p.commands.isEmpty() && linkedMcps.isEmpty() && linkedSkills.isEmpty()) {
                                                            Text("暂无资源，可编辑插件包添加命令。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (idx < filteredPacks.lastIndex) Hairline()
                                    }
                                }
                            }
                        }

                        "instructions" -> {
                            val sortedAll = remember(registry.instructions) { registry.instructions.sortedBy { it.priority } }
                            val filtered = remember(sortedAll, q) {
                                if (q.isEmpty()) sortedAll
                                else sortedAll.filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) || it.scope.lowercase().contains(q) }
                            }
                            val enabledCount = sortedAll.count { it.enabled }
                            val totalTok = sortedAll.filter { it.enabled }.sumOf { it.estimatedTokens }

                            SectionHeader("指令集") {
                                if (sortedAll.isNotEmpty()) {
                                    CompactActionButton("合并预览", onClick = { showInstructionsPreview = true })
                                }
                                CompactActionButton("+ 新建指令", primary = true, onClick = { creatingInstruction = true })
                            }
                            if (sortedAll.isNotEmpty()) {
                                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
                                    Text(
                                        "已启用 $enabledCount/${sortedAll.size} 条 · 约 $totalTok tokens",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            }
                            if (filtered.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (sortedAll.isEmpty()) "配置全局或项目级长期规范，自动通过托管标记块同步至 CLAUDE.md / AGENTS.md / GEMINI.md，支持优先级排序与合并预览。"
                                        else "没有匹配「$searchQuery」的指令块。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filtered.forEachIndexed { idx, item ->
                                        Column(
                                            Modifier.fillMaxWidth().clickable { editingInstruction = item }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(item.content.lines().firstOrNull { it.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                GaliaisSwitch(checked = item.enabled, onCheckedChange = { app.extensions.toggleInstruction(item.id, it) })
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill(scopeDisplayLabel(item.scope), fg = if (item.scope.startsWith("project:")) G.accent else G.inkMuted, bg = if (item.scope.startsWith("project:")) G.accentContainer else G.surfaceSubtle)
                                                TagPill("~${item.estimatedTokens} tok", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                TagPill(if ("all" in item.targets) "全部 Agent" else item.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (q.isEmpty() && idx > 0) {
                                                    TagPill("上移", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.reorderInstruction(item.id, moveUp = true) })
                                                }
                                                if (q.isEmpty() && idx < filtered.lastIndex) {
                                                    TagPill("下移", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.reorderInstruction(item.id, moveUp = false) })
                                                }
                                                TagPill("复制", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.duplicateInstruction(item.id) })
                                                TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                    pendingConfirm = Triple("删除指令块？", "确定删除「${item.title}」并从三端同步移除吗？") {
                                                        app.extensions.deleteInstruction(item.id)
                                                    }
                                                })
                                            }
                                        }
                                        if (idx < filtered.lastIndex) Hairline()
                                    }
                                }
                            }
                        }

                        "mcp" -> {
                            val filtered = remember(registry.mcpServers, q) {
                                if (q.isEmpty()) registry.mcpServers
                                else registry.mcpServers.filter { s ->
                                    s.name.lowercase().contains(q) || s.id.lowercase().contains(q) ||
                                        (s.command ?: "").lowercase().contains(q) || (s.url ?: "").lowercase().contains(q)
                                }
                            }
                            SectionHeader("MCP 工具服务") {
                                CompactActionButton("导入 JSON", onClick = { pasteImportMode = "mcp" })
                                if (registry.mcpServers.isNotEmpty()) {
                                    CompactActionButton("导出", onClick = {
                                        copyText("mcpServers", app.extensions.exportMcpServersJson(), "已复制标准 mcpServers JSON 到剪贴板")
                                    })
                                    if (runtime.ready) {
                                        CompactActionButton("全部测速", onClick = {
                                            registry.mcpServers.filter { it.enabled }.forEach { srv ->
                                                if (probingIds[srv.id] != true) {
                                                    probingIds[srv.id] = true
                                                    scope.launch {
                                                        try { app.extensions.probeMcp(srv) }
                                                        finally { probingIds.remove(srv.id) }
                                                    }
                                                }
                                            }
                                        })
                                    }
                                }
                                CompactActionButton("+ 添加", primary = true, onClick = { creatingMcp = true })
                            }
                            if (filtered.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (registry.mcpServers.isEmpty()) "可手动添加 MCP 服务，或粘贴 mcpServers JSON 批量导入。"
                                        else "没有匹配「$searchQuery」的 MCP 服务。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filtered.forEachIndexed { idx, srv ->
                                        val probe = probes[srv.id]
                                        val probing = probingIds[srv.id] == true
                                        val secretCount = if (srv.transport == "http") srv.secretHeaderKeys.size else srv.secretEnvKeys.size
                                        Column(
                                            Modifier.fillMaxWidth().clickable { editingMcp = srv }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(srv.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                        TagPill(srv.transport.uppercase(), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                        if (srv.pluginId != null) TagPill("插件包", fg = G.accent, bg = G.accentContainer)
                                                    }
                                                    val endpoint = if (srv.transport == "http") srv.url.orEmpty() else listOfNotNull(srv.command, srv.args.joinToString(" ").takeIf { it.isNotBlank() }).joinToString(" ")
                                                    Text(endpoint, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                GaliaisSwitch(checked = srv.enabled, onCheckedChange = { app.extensions.toggleMcp(srv.id, it) })
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill(scopeDisplayLabel(srv.scope), fg = if (srv.scope.startsWith("project:")) G.accent else G.inkMuted, bg = if (srv.scope.startsWith("project:")) G.accentContainer else G.surfaceSubtle)
                                                TagPill(if ("all" in srv.targets) "全部 Agent" else srv.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (secretCount > 0) TagPill("$secretCount 密钥", fg = G.accent, bg = G.accentContainer)
                                                if (probe != null) {
                                                    TagPill(
                                                        text = if (probe.ok) "${probe.toolDetails.size} 工具 · ${probe.latencyMs}ms" else probe.message.take(22),
                                                        fg = if (probe.ok) G.success else G.danger,
                                                        bg = if (probe.ok) G.successTint else G.dangerTint,
                                                        onClick = { viewingMcpTools = srv.name to probe },
                                                    )
                                                }
                                                if (runtime.ready) {
                                                    TagPill(if (probing) "测速中…" else "测速", fg = G.accent, bg = G.accentContainer, onClick = {
                                                        if (!probing) {
                                                            probingIds[srv.id] = true
                                                            scope.launch {
                                                                try { app.extensions.probeMcp(srv) }
                                                                finally { probingIds.remove(srv.id) }
                                                            }
                                                        }
                                                    })
                                                }
                                                TagPill("复制", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.duplicateMcp(srv.id) })
                                                if (srv.pluginId == null) {
                                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                        pendingConfirm = Triple("删除 MCP 服务？", "确定删除「${srv.name}」及其加密密钥吗？") {
                                                            app.extensions.deleteMcp(srv.id)
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                        if (idx < filtered.lastIndex) Hairline()
                                    }
                                }
                            }
                        }

                        "skills" -> {
                            val filteredManaged = remember(registry.skills, q) {
                                if (q.isEmpty()) registry.skills
                                else registry.skills.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || it.id.lowercase().contains(q) }
                            }
                            val filteredWs = remember(workspaceSkills, q) {
                                if (q.isEmpty()) workspaceSkills
                                else workspaceSkills.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || it.dirPath.lowercase().contains(q) }
                            }
                            SectionHeader("托管技能") {
                                CompactActionButton("粘贴", enabled = runtime.ready, onClick = { pasteImportMode = "skill" })
                                CompactActionButton("GitHub", enabled = runtime.ready, onClick = { importSkillGitHub = true })
                                CompactActionButton("+ 新建", primary = true, enabled = runtime.ready, onClick = { creatingSkill = true })
                            }
                            if (filteredManaged.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (registry.skills.isEmpty()) "支持在线编写、粘贴 SKILL.md 或从 GitHub 仓库导入，自动软链投影到 Codex / Claude Code / Antigravity 技能发现目录，并可直接浏览编辑伴随脚本与参考文档。"
                                        else "没有匹配「$searchQuery」的托管技能。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filteredManaged.forEachIndexed { idx, sk ->
                                        Column(
                                            Modifier.fillMaxWidth().clickable { editingSkill = sk }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(sk.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                        TagPill(when (sk.source) { "github" -> "GitHub"; "plugin" -> "插件包"; else -> "本地" }, fg = G.inkMuted, bg = G.surfaceSubtle)
                                                    }
                                                    if (sk.description.isNotBlank()) {
                                                        Text(sk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                GaliaisSwitch(checked = sk.enabled, onCheckedChange = { app.extensions.toggleSkill(sk.id, it) })
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill(scopeDisplayLabel(sk.scope), fg = if (sk.scope.startsWith("project:")) G.accent else G.inkMuted, bg = if (sk.scope.startsWith("project:")) G.accentContainer else G.surfaceSubtle)
                                                TagPill(if ("all" in sk.targets) "全部 Agent" else sk.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (runtime.ready && sk.dirPath.startsWith("/")) {
                                                    TagPill("目录文件", fg = G.accent, bg = G.accentContainer, onClick = {
                                                        loadingSkillFiles = true
                                                        viewingSkillFiles = Triple(sk.name, sk.dirPath, emptyList())
                                                        scope.launch {
                                                            try {
                                                                val files = app.extensions.listSkillFiles(sk.dirPath)
                                                                viewingSkillFiles = Triple(sk.name, sk.dirPath, files)
                                                            } finally { loadingSkillFiles = false }
                                                        }
                                                    })
                                                }
                                                if (runtime.ready && sk.source == "github" && !sk.sourceUrl.isNullOrBlank()) {
                                                    TagPill("拉取更新", fg = G.accent, bg = G.accentContainer, onClick = {
                                                        scope.launch {
                                                            busyMessage = "正在更新技能 ${sk.name}…"
                                                            try { app.extensions.importSkillFromGitHub(sk.sourceUrl) }
                                                            catch (e: Exception) { app.conversations.notify("更新失败：${e.message}") }
                                                            finally { busyMessage = null }
                                                        }
                                                    })
                                                }
                                                if (sk.pluginId == null) {
                                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                        pendingConfirm = Triple("删除技能？", "确定删除「${sk.name}」并同步移除磁盘上的 SKILL.md 吗？") {
                                                            scope.launch {
                                                                try { app.extensions.deleteSkill(sk.id) }
                                                                catch (c: CancellationException) { throw c }
                                                                catch (_: Exception) {}
                                                            }
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                        if (idx < filteredManaged.lastIndex) Hairline()
                                    }
                                }
                            }
                            if (filteredWs.isNotEmpty()) {
                                SectionHeader("工作区技能")
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filteredWs.forEachIndexed { idx, sk ->
                                        Column(
                                            Modifier.fillMaxWidth().clickable {
                                                if (sk.dirPath.startsWith("/")) {
                                                    val path = "${sk.dirPath.trimEnd('/')}/SKILL.md"
                                                    val fs = if (path.startsWith("/home/agent")) FileScope.LINUX_WORKSPACE else FileScope.LINUX_ROOT
                                                    openingEditor = FileRef(fs, path) to null
                                                }
                                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(sk.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.Medium)
                                                    if (sk.description.isNotBlank()) Text(sk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(sk.dirPath, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (runtime.ready) {
                                                    TagPill("纳入托管", fg = G.accent, bg = G.accentContainer, onClick = {
                                                        scope.launch {
                                                            try {
                                                                app.extensions.saveLocalSkill(
                                                                    id = null,
                                                                    name = sk.name,
                                                                    description = sk.description,
                                                                    body = sk.body.ifBlank { "# ${sk.name}\n" },
                                                                )
                                                            } catch (e: Exception) {
                                                                app.conversations.notify("纳入托管失败：${e.message}")
                                                            }
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                        if (idx < filteredWs.lastIndex) Hairline()
                                    }
                                }
                            }
                        }

                        "subagents" -> {
                            val filtered = remember(registry.subagents, q) {
                                if (q.isEmpty()) registry.subagents
                                else registry.subagents.filter { ag ->
                                    ag.name.lowercase().contains(q) || ag.description.lowercase().contains(q) ||
                                        ag.model.lowercase().contains(q) || ag.prompt.lowercase().contains(q)
                                }
                            }
                            SectionHeader("共享子代理") {
                                CompactActionButton("粘贴 .md", onClick = { pasteImportMode = "subagent" })
                                CompactActionButton("+ 新建子代理", primary = true, onClick = { creatingSubagent = true })
                            }
                            if (filtered.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (registry.subagents.isEmpty()) "创建子代理，指定职责、模型和可用工具。"
                                        else "没有匹配「$searchQuery」的子代理。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filtered.forEachIndexed { idx, ag ->
                                        val permSummary = buildString {
                                            if (ag.tools.isNotEmpty()) append("允许 ${ag.tools.size} 工具")
                                            if (ag.disallowedTools.isNotEmpty()) {
                                                if (isNotEmpty()) append(" · ")
                                                append("禁 ${ag.disallowedTools.size}")
                                            }
                                        }
                                        Column(
                                            Modifier.fillMaxWidth().clickable { editingSubagent = ag }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(ag.name, fontFamily = Mono, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                        if (ag.model.isNotBlank()) TagPill(ag.model, fg = G.accent, bg = G.accentContainer)
                                                    }
                                                    if (ag.description.isNotBlank()) Text(ag.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                GaliaisSwitch(checked = ag.enabled, onCheckedChange = { app.extensions.toggleSubagent(ag.id, it) })
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill(scopeDisplayLabel(ag.scope), fg = if (ag.scope.startsWith("project:")) G.accent else G.inkMuted, bg = if (ag.scope.startsWith("project:")) G.accentContainer else G.surfaceSubtle)
                                                if (permSummary.isNotBlank()) TagPill(permSummary, fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (ag.permissionMode.isNotBlank()) TagPill(ag.permissionMode, fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (ag.maxTurns > 0) TagPill("≤${ag.maxTurns}轮", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                TagPill(if ("all" in ag.targets) "全部 Agent" else ag.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                TagPill("复制", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.duplicateSubagent(ag.id) })
                                                TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                    pendingConfirm = Triple("删除子代理？", "确定删除子代理「${ag.name}」并同步移除其 .md 文件吗？") {
                                                        app.extensions.deleteSubagent(ag.id)
                                                    }
                                                })
                                            }
                                        }
                                        if (idx < filtered.lastIndex) Hairline()
                                    }
                                }
                            }
                        }

                        "hooks" -> {
                            val filtered = remember(registry.hooks, q) {
                                if (q.isEmpty()) registry.hooks
                                else registry.hooks.filter { hk ->
                                    hk.name.lowercase().contains(q) || hk.event.lowercase().contains(q) ||
                                        hk.matcher.lowercase().contains(q) || hk.command.lowercase().contains(q) ||
                                        hk.description.lowercase().contains(q)
                                }
                            }
                            SectionHeader("生命周期钩子") {
                                CompactActionButton("+ 新建钩子", primary = true, onClick = { creatingHook = true })
                            }
                            if (filtered.isEmpty()) {
                                CrispCard {
                                    Text(
                                        if (registry.hooks.isEmpty()) "添加钩子，在指定事件发生时执行命令。"
                                        else "没有匹配「$searchQuery」的钩子。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.inkMuted,
                                    )
                                }
                            } else {
                                CrispCard(padding = PaddingValues(0.dp)) {
                                    filtered.forEachIndexed { idx, hk ->
                                        val testing = testingHookIds[hk.id] == true
                                        val lastTest = hookTests[hk.id]
                                        Column(
                                            Modifier.fillMaxWidth().clickable { editingHook = hk }.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(hk.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                        TagPill(hk.event, fg = G.accent, bg = G.accentContainer)
                                                    }
                                                    if (hk.description.isNotBlank()) {
                                                        Text(hk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                    Text("${hk.matcher} → ${hk.command}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                GaliaisSwitch(checked = hk.enabled, onCheckedChange = { app.extensions.toggleHook(hk.id, it) })
                                            }
                                            if (lastTest != null) {
                                                val (label, fg, bg) = when {
                                                    lastTest.blocked -> Triple("阻断 (exit 2) · ${lastTest.latencyMs}ms", G.warning, G.warningTint)
                                                    lastTest.ok -> Triple("放行 (exit 0) · ${lastTest.latencyMs}ms", G.success, G.successTint)
                                                    else -> Triple("失败 (exit ${lastTest.exitCode}) · ${lastTest.latencyMs}ms", G.danger, G.dangerTint)
                                                }
                                                val snippet = (lastTest.stderr.ifBlank { lastTest.stdout }).lines().firstOrNull { it.isNotBlank() }.orEmpty()
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    TagPill(label, fg = fg, bg = bg)
                                                    if (snippet.isNotBlank()) {
                                                        Text(snippet, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TagPill(scopeDisplayLabel(hk.scope), fg = if (hk.scope.startsWith("project:")) G.accent else G.inkMuted, bg = if (hk.scope.startsWith("project:")) G.accentContainer else G.surfaceSubtle)
                                                TagPill("${hk.timeoutSec}s", fg = G.inkMuted, bg = G.surfaceSubtle)
                                                TagPill(if ("all" in hk.targets) "全部 Agent" else hk.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                                if (runtime.ready) {
                                                    TagPill(if (testing) "试运行中…" else "试运行", fg = G.accent, bg = G.accentContainer, onClick = {
                                                        if (!testing) {
                                                            testingHookIds[hk.id] = true
                                                            scope.launch {
                                                                try { app.extensions.testHook(hk, settings.defaultCwd) }
                                                                finally { testingHookIds.remove(hk.id) }
                                                            }
                                                        }
                                                    })
                                                }
                                                TagPill("复制", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { app.extensions.duplicateHook(hk.id) })
                                                TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = {
                                                    pendingConfirm = Triple("删除钩子？", "确定删除「${hk.name}」并同步从各 CLI 移除吗？") {
                                                        app.extensions.deleteHook(hk.id)
                                                    }
                                                })
                                            }
                                        }
                                        if (idx < filtered.lastIndex) Hairline()
                                    }
                                }
                            }
                        }
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
            onConfirm = { pendingConfirm = null; action() },
            onDismiss = { pendingConfirm = null },
        )
    }

    if (showSyncTargets) {
        ExtensionSyncTargetsDialog(onDismiss = { showSyncTargets = false })
    }
    if (showInstructionsPreview) {
        InstructionsMergedPreviewDialog(app = app, instructions = registry.instructions, onDismiss = { showInstructionsPreview = false })
    }
    if (creatingMcp || editingMcp != null) {
        McpEditorSheet(
            app = app,
            initial = editingMcp,
            projects = settings.projects,
            defaultCwd = settings.defaultCwd,
            onDismiss = { creatingMcp = false; editingMcp = null },
        )
    }
    viewingMcpTools?.let { (srvName, probe) ->
        McpToolsDialog(serverName = srvName, probe = probe, onDismiss = { viewingMcpTools = null })
    }
    if (creatingInstruction || editingInstruction != null) {
        InstructionEditorSheet(
            initial = editingInstruction,
            projects = settings.projects,
            defaultCwd = settings.defaultCwd,
            onDismiss = { creatingInstruction = false; editingInstruction = null },
            onSave = {
                app.extensions.upsertInstruction(it)
                creatingInstruction = false; editingInstruction = null
            },
        )
    }
    if (creatingSubagent || editingSubagent != null) {
        SubagentEditorSheet(
            initial = editingSubagent,
            projects = settings.projects,
            defaultCwd = settings.defaultCwd,
            modelSuggestions = modelSuggestions,
            onDismiss = { creatingSubagent = false; editingSubagent = null },
            onSave = {
                app.extensions.upsertSubagent(it)
                creatingSubagent = false; editingSubagent = null
            },
        )
    }
    if (creatingHook || editingHook != null) {
        HookEditorSheet(
            app = app,
            initial = editingHook,
            projects = settings.projects,
            defaultCwd = settings.defaultCwd,
            runtimeReady = runtime.ready,
            onDismiss = { creatingHook = false; editingHook = null },
            onSave = {
                app.extensions.upsertHook(it)
                creatingHook = false; editingHook = null
            },
        )
    }
    if (creatingSkill || editingSkill != null) {
        SkillEditorSheet(
            app = app,
            initial = editingSkill,
            projects = settings.projects,
            defaultCwd = settings.defaultCwd,
            onOpenInCodeEditor = { ref -> openingEditor = ref to null },
            onDismiss = { creatingSkill = false; editingSkill = null },
        )
    }
    viewingSkillFiles?.let { (skName, dirPath, files) ->
        SkillFilesDialog(
            skillName = skName,
            dirPath = dirPath,
            files = files,
            loading = loadingSkillFiles,
            onOpenFile = { ref -> openingEditor = ref to null },
            onDismiss = { viewingSkillFiles = null },
        )
    }
    if (creatingPluginPack || editingPluginPack != null) {
        PluginPackEditorSheet(
            initial = editingPluginPack,
            onDismiss = { creatingPluginPack = false; editingPluginPack = null },
            onSave = {
                app.extensions.saveLocalPluginPack(it)
                creatingPluginPack = false; editingPluginPack = null
            },
        )
    }
    if (importSkillGitHub) {
        TextInputDialog(
            title = "从 GitHub 导入技能",
            initial = "https://github.com/",
            onDismiss = { importSkillGitHub = false },
        ) { url ->
            importSkillGitHub = false
            scope.launch {
                busyMessage = "正在从 GitHub 克隆并校验 SKILL.md…"
                try { app.extensions.importSkillFromGitHub(url) }
                catch (e: Exception) { app.conversations.notify("导入技能失败：${e.message}") }
                finally { busyMessage = null }
            }
        }
    }
    if (importPluginGitHub) {
        TextInputDialog(
            title = "从 GitHub 安装插件包",
            initial = "https://github.com/",
            onDismiss = { importPluginGitHub = false },
        ) { url ->
            importPluginGitHub = false
            scope.launch {
                busyMessage = "正在下载并校验插件包…"
                try { app.extensions.importPluginFromGitHub(url) }
                catch (e: Exception) { app.conversations.notify("安装插件失败：${e.message}") }
                finally { busyMessage = null }
            }
        }
    }
    pasteImportMode?.let { mode ->
        val (title, subtitle, placeholder) = when (mode) {
            "mcp" -> Triple(
                "粘贴导入 MCP JSON",
                "支持 Claude / Cursor / Codex 标准 mcpServers JSON 或单条配置对象",
                "{\"mcpServers\": {\"github\": {\"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-github\"]}}}",
            )
            "skill" -> Triple(
                "粘贴导入 SKILL.md",
                "支持标准 YAML Frontmatter（name / description）+ Markdown 正文",
                "---\nname: my-skill\ndescription: 何时触发\n---\n\n# 步骤\n...",
            )
            "subagent" -> Triple(
                "粘贴导入子代理 .md",
                "支持 YAML Frontmatter（name / description / model / tools / disallowedTools / permissionMode / maxTurns）",
                "---\nname: code-reviewer\ndescription: 只读审查\ntools: Read, Grep, Glob\n---\n\n你是一名代码审查专家...",
            )
            else -> Triple(
                "导入资源库备份 JSON",
                "合并导入指令、MCP、子代理、钩子与插件包元信息（不覆盖加密密钥）",
                "{\"format\": \"solbraid-extensions-v1\", \"registry\": {...}}",
            )
        }
        PasteImportSheet(
            app = app,
            title = title,
            subtitle = subtitle,
            placeholder = placeholder,
            onDismiss = { pasteImportMode = null },
            onImport = { raw ->
                try {
                    when (mode) {
                        "mcp" -> { app.extensions.importMcpServersFromJson(raw); null }
                        "subagent" -> { app.extensions.importSubagentFromMarkdown(raw); null }
                        "backup" -> { app.extensions.importAllExtensionsJson(raw); null }
                        "skill" -> {
                            scope.launch {
                                busyMessage = "正在解析并保存 SKILL.md…"
                                try { app.extensions.importSkillFromMarkdown(raw) }
                                catch (e: Exception) { app.conversations.notify("导入技能失败：${e.message}") }
                                finally { busyMessage = null }
                            }
                            null
                        }
                        else -> null
                    }
                } catch (e: Exception) {
                    e.message ?: "解析失败，请检查格式"
                }
            },
        )
    }
    secretPluginTarget?.let { plugin ->
        PluginSecretsDialog(
            title = "${plugin.name} · 密钥配置",
            specs = plugin.requiredSecrets,
            bucket = "plugin:${plugin.id}",
            app = app,
            confirmText = "保存并同步",
            onDismiss = { secretPluginTarget = null },
            onSave = { secrets ->
                secretPluginTarget = null
                app.extensions.updatePluginSecrets(plugin.id, secrets)
            },
        )
    }
    openingEditor?.let { (ref, reloadPluginId) ->
        CodeEditorDialog(
            app = app,
            fileRef = ref,
            onDismiss = { openingEditor = null },
            onSaved = {
                if (runtime.ready) {
                    scope.launch {
                        if (reloadPluginId != null) {
                            runCatching { app.extensions.reloadSingleHostPlugin(reloadPluginId) }
                        } else {
                            runCatching { app.extensions.refreshSkills(settings.defaultCwd) }
                        }
                    }
                }
            },
        )
    }
}
