package com.galiais.agent.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.galiais.agent.engine.newProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 统一扩展中心：单源管理 MCP 服务、Skills 技能库与原子化 Plugin 插件包，自动投影到全部 Agent。 */
@Composable
fun ExtensionsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val registry by app.extensions.registry.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val communityRegistry by app.extensions.communityRegistry.collectAsState()
    val workspaceSkills by app.extensions.workspaceSkills.collectAsState()
    val probes by app.extensions.probes.collectAsState()
    val syncing by app.extensions.syncing.collectAsState()
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("registry") } // "registry" | "plugins" | "instructions" | "mcp" | "skills" | "subagents" | "hooks"
    var editingMcp by remember { mutableStateOf<ManagedMcpServer?>(null) }
    var creatingMcp by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<ManagedSkill?>(null) }
    var creatingSkill by remember { mutableStateOf(false) }
    var editingInstruction by remember { mutableStateOf<ManagedInstruction?>(null) }
    var creatingInstruction by remember { mutableStateOf(false) }
    var editingSubagent by remember { mutableStateOf<ManagedSubagent?>(null) }
    var creatingSubagent by remember { mutableStateOf(false) }
    var editingHook by remember { mutableStateOf<ManagedHook?>(null) }
    var creatingHook by remember { mutableStateOf(false) }
    var importSkillDialog by remember { mutableStateOf(false) }
    var importPluginDialog by remember { mutableStateOf(false) }
    var secretPluginTarget by remember { mutableStateOf<ManagedPlugin?>(null) }
    var sharePrefill by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    val probingIds = remember { mutableStateMapOf<String, Boolean>() }
    fun shareToRegistry(kind: String, id: String) {
        sharePrefill = kind to id
        section = "registry"
    }

    LaunchedEffect(runtime.ready, settings.defaultCwd) {
        if (runtime.ready) {
            runCatching { app.extensions.refreshSkills(settings.defaultCwd) }
            runCatching { app.extensions.refreshHostPlugins() }
            runCatching { app.extensions.refreshCommunityRegistry() }
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
                                val res = app.extensions.syncNow()
                                app.extensions.refreshSkills(settings.defaultCwd)
                                runCatching { app.extensions.refreshHostPlugins() }
                                runCatching { app.extensions.refreshCommunityRegistry() }
                                val mcps = res.optInt("codexMcpCount")
                                val agyMcps = res.optInt("antigravityMcpCount")
                                val sks = res.optInt("codexSkills")
                                val instr = res.optInt("claudeInstructions")
                                app.conversations.notify("已同步到全部引擎（指令 $instr · MCP $mcps/$agyMcps · 技能 $sks）")
                            } catch (e: Exception) {
                                app.conversations.notify("同步失败：${e.message}")
                            }
                        }
                    },
                )
            }
        },
    ) {
        CrispCard(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("全 Agent 统一资源库", style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (runtime.ready)
                            "单源管理指令（CLAUDE.md / AGENTS.md / GEMINI.md）、MCP、技能、子代理、钩子与插件包，自动无侵入同步到三大内置 CLI。"
                        else
                            "运行环境未启动，启动后可安装共享仓库资源、加载插件与同步 MCP / 技能。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }
                if (!runtime.ready && !runtime.busy && runtime.bound) {
                    TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                }
            }
        }

        GaliaisScrollableTabBar(
            options = listOf(
                "registry" to "共享仓库 (${communityRegistry.items.size})",
                "plugins" to "插件 (${hostPlugins.extensionPlugins.size + registry.plugins.size})",
                "instructions" to "指令 (${registry.instructions.size})",
                "mcp" to "MCP (${registry.mcpServers.size})",
                "skills" to "技能 (${registry.skills.size})",
                "subagents" to "子代理 (${registry.subagents.size})",
                "hooks" to "钩子 (${registry.hooks.size})",
            ),
            selected = section,
            onSelect = { section = it },
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
            "registry" -> {
                CommunityRegistrySection(
                    app = app,
                    snapshot = communityRegistry,
                    localRegistry = registry,
                    hostPlugins = hostPlugins,
                    runtimeReady = runtime.ready,
                    prefillUpload = sharePrefill,
                    onClearPrefill = { sharePrefill = null },
                    onBusy = { busyMessage = it },
                )
            }

            "instructions" -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { SectionLabel("全局指令集（自动同步三端）") }
                    PrimaryPillButton("+ 新建指令", onClick = { creatingInstruction = true })
                }
                if (registry.instructions.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("配置一次长期规则与风格偏好，自动以托管标记写入三大 CLI 的全局指令文件，不覆盖你在终端手写的内容。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            SecondaryPillButton("前往共享仓库浏览", onClick = { section = "registry" })
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.instructions.forEachIndexed { idx, item ->
                            Column(
                                Modifier.fillMaxWidth().clickable { editingInstruction = item }.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                        Text(item.content.lines().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    GaliaisSwitch(checked = item.enabled, onCheckedChange = { app.extensions.toggleInstruction(item.id, it) })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TagPill(if ("all" in item.targets) "全部 Agent" else item.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                    Spacer(Modifier.weight(1f))
                                    TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { shareToRegistry("instruction", item.id) })
                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { app.extensions.deleteInstruction(item.id) })
                                }
                            }
                            if (idx < registry.instructions.lastIndex) Hairline()
                        }
                    }
                }
            }

            "subagents" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("共享子代理（.claude / .codex / .agents）")
                    Spacer(Modifier.weight(1f))
                    PrimaryPillButton("+ 新建子代理", onClick = { creatingSubagent = true })
                }
                if (registry.subagents.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("定义可复用的角色子代理（带 YAML Frontmatter 的 .md 文件），自动同步到三大 CLI 的子代理目录。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            SecondaryPillButton("前往共享仓库浏览", onClick = { section = "registry" })
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.subagents.forEachIndexed { idx, ag ->
                            Column(
                                Modifier.fillMaxWidth().clickable { editingSubagent = ag }.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(ag.name, fontFamily = Mono, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                            if (ag.model.isNotBlank()) TagPill(ag.model, fg = G.accent, bg = G.accentContainer)
                                        }
                                        if (ag.description.isNotBlank()) Text(ag.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    GaliaisSwitch(checked = ag.enabled, onCheckedChange = { app.extensions.toggleSubagent(ag.id, it) })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TagPill(if ("all" in ag.targets) "全部 Agent" else ag.targets.joinToString(" · "), fg = G.inkMuted, bg = G.surfaceSubtle)
                                    Spacer(Modifier.weight(1f))
                                    TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { shareToRegistry("subagent", ag.id) })
                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { app.extensions.deleteSubagent(ag.id) })
                                }
                            }
                            if (idx < registry.subagents.lastIndex) Hairline()
                        }
                    }
                }
            }

            "hooks" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("生命周期钩子（Hooks）")
                    Spacer(Modifier.weight(1f))
                    PrimaryPillButton("+ 新建钩子", onClick = { creatingHook = true })
                }
                if (registry.hooks.isEmpty()) {
                    CrispCard {
                        Text("配置工具调用前后（PreToolUse / PostToolUse / Stop / SessionStart）自动执行的 Shell 命令钩子，同步至 Codex 与 Antigravity hooks.json。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.hooks.forEachIndexed { idx, hook ->
                            Column(
                                Modifier.fillMaxWidth().clickable { editingHook = hook }.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(hook.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                            TagPill(hook.event, fg = G.accent, bg = G.accentContainer)
                                        }
                                        Text(hook.command, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    GaliaisSwitch(checked = hook.enabled, onCheckedChange = { app.extensions.toggleHook(hook.id, it) })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TagPill("匹配 ${hook.matcher}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                    Spacer(Modifier.weight(1f))
                                    TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { shareToRegistry("hook", hook.id) })
                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { app.extensions.deleteHook(hook.id) })
                                }
                            }
                            if (idx < registry.hooks.lastIndex) Hairline()
                        }
                    }
                }
            }

            "mcp" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("已托管 MCP 服务器")
                    Spacer(Modifier.weight(1f))
                    PrimaryPillButton("+ 添加 MCP", onClick = { creatingMcp = true })
                }
                if (registry.mcpServers.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("尚未添加统一托管的 MCP 服务器。可手动配置 Stdio / HTTP 服务，或从「共享仓库」下载其他用户分享并通过 AI 安全审查的 MCP。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            SecondaryPillButton("前往共享仓库浏览", onClick = { section = "registry" })
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.mcpServers.forEachIndexed { idx, srv ->
                            val probe = probes[srv.id]
                            val probing = probingIds[srv.id] == true
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editingMcp = srv }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(srv.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            TagPill(srv.transport.uppercase(), fg = G.inkMuted, bg = G.surfaceSubtle)
                                            srv.pluginId?.let { TagPill("插件", fg = G.accent, bg = G.accentContainer) }
                                        }
                                        val endpoint = if (srv.transport == "http") srv.url.orEmpty() else listOfNotNull(srv.command, srv.args.joinToString(" ").takeIf { it.isNotBlank() }).joinToString(" ")
                                        Text(endpoint, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    GaliaisSwitch(checked = srv.enabled, onCheckedChange = { app.extensions.toggleMcp(srv.id, it) })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val targetLabel = if ("all" in srv.targets) "全部 Agent" else srv.targets.joinToString(" · ")
                                    TagPill(targetLabel, fg = G.inkMuted, bg = G.surfaceSubtle)
                                    if (probe != null) {
                                        TagPill(
                                            text = if (probe.ok) "正常 ${probe.latencyMs}ms · ${probe.tools.size} 工具" else "异常",
                                            fg = if (probe.ok) G.success else G.danger,
                                            bg = if (probe.ok) G.successTint else G.dangerTint,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TagPill(
                                        text = if (probing) "探测中…" else "测速",
                                        fg = G.accent,
                                        bg = G.accentContainer,
                                        onClick = if (!probing && runtime.ready) ({
                                            probingIds[srv.id] = true
                                            scope.launch {
                                                try {
                                                    val r = app.extensions.probeMcp(srv)
                                                    app.conversations.notify("${srv.name}：${r.message}")
                                                } finally {
                                                    probingIds.remove(srv.id)
                                                }
                                            }
                                        }) else null,
                                    )
                                    if (srv.pluginId == null) {
                                        TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { shareToRegistry("mcp", srv.id) })
                                        TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { app.extensions.deleteMcp(srv.id) })
                                    }
                                }
                                if (probe != null && !probe.ok && probe.message.isNotBlank()) {
                                    Text(probe.message, style = MaterialTheme.typography.labelSmall, color = G.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (idx < registry.mcpServers.lastIndex) Hairline()
                        }
                    }
                }
            }

            "skills" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("托管技能库（SKILL.md）")
                    Spacer(Modifier.weight(1f))
                    SecondaryPillButton("GitHub 导入", enabled = runtime.ready, onClick = { importSkillDialog = true })
                    PrimaryPillButton("+ 新建", enabled = runtime.ready, onClick = { creatingSkill = true })
                }
                if (registry.skills.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有托管技能。你可以直接新建 SKILL.md、从 GitHub 导入，或前往「共享仓库」下载其他用户分享的技能。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            SecondaryPillButton("前往共享仓库浏览", onClick = { section = "registry" })
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.skills.forEachIndexed { idx, sk ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { if (sk.pluginId == null) editingSkill = sk }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(sk.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.Medium)
                                        TagPill(
                                            when (sk.source) {
                                                "github" -> "GitHub"
                                                "plugin" -> "插件"
                                                else -> "本地"
                                            },
                                            fg = G.inkMuted,
                                            bg = G.surfaceSubtle,
                                        )
                                    }
                                    if (sk.description.isNotBlank()) {
                                        Text(sk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                GaliaisSwitch(checked = sk.enabled, onCheckedChange = { app.extensions.toggleSkill(sk.id, it) })
                            }
                            if (idx < registry.skills.lastIndex) Hairline()
                        }
                    }
                }

                if (workspaceSkills.isNotEmpty()) {
                    SectionLabel("当前工作区发现的技能（${workspaceSkills.size}）")
                    CrispCard(padding = PaddingValues(0.dp)) {
                        workspaceSkills.forEachIndexed { idx, sk ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sk.name, style = MaterialTheme.typography.titleSmall, color = G.ink)
                                if (sk.description.isNotBlank()) Text(sk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(sk.dirPath, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (idx < workspaceSkills.lastIndex) Hairline()
                        }
                    }
                }
            }

            else -> {
                HostPluginsSection(
                    app = app,
                    hostPlugins = hostPlugins,
                    runtimeReady = runtime.ready,
                    defaultCwd = settings.defaultCwd,
                    onSharePlugin = { shareToRegistry("host_plugin", it) },
                    onBusy = { busyMessage = it },
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("已安装插件包（MCP / Skills 套件）")
                    Spacer(Modifier.weight(1f))
                    PrimaryPillButton("GitHub 安装", enabled = runtime.ready, onClick = { importPluginDialog = true })
                }
                if (registry.plugins.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("插件包将 MCP + Skills + 快捷 `/` 指令打包为原子单元，支持一键启停与干净卸载。可从 GitHub 导入或前往「共享仓库」下载。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            SecondaryPillButton("前往共享仓库浏览", onClick = { section = "registry" })
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        registry.plugins.forEachIndexed { idx, plugin ->
                            val pMcps = registry.mcpServers.count { it.pluginId == plugin.id }
                            val pSkills = registry.skills.count { it.pluginId == plugin.id }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(plugin.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                                            TagPill("v${plugin.version}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                        }
                                        if (plugin.description.isNotBlank()) {
                                            Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                                        }
                                    }
                                    GaliaisSwitch(checked = plugin.enabled, onCheckedChange = { app.extensions.togglePlugin(plugin.id, it) })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (pMcps > 0) TagPill("$pMcps MCP", fg = G.accent, bg = G.accentContainer)
                                    if (pSkills > 0) TagPill("$pSkills 技能", fg = G.inkMuted, bg = G.surfaceSubtle)
                                    if (plugin.commands.isNotEmpty()) TagPill("${plugin.commands.size} 指令", fg = G.inkMuted, bg = G.surfaceSubtle)
                                    Spacer(Modifier.weight(1f))
                                    if (plugin.requiredSecrets.isNotEmpty()) {
                                        TagPill("配置密钥", fg = G.accent, bg = G.accentContainer, onClick = { secretPluginTarget = plugin })
                                    }
                                    TagPill("分享", fg = G.accent, bg = G.accentContainer, onClick = { shareToRegistry("plugin_pack", plugin.id) })
                                    TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = {
                                        scope.launch { app.extensions.uninstallPlugin(plugin.id) }
                                    })
                                }
                            }
                            if (idx < registry.plugins.lastIndex) Hairline()
                        }
                    }
                }
            }
                    }
                }
            }
        }
    }

    if (creatingMcp || editingMcp != null) {
        McpEditorSheet(
            app = app,
            initial = editingMcp,
            onDismiss = { creatingMcp = false; editingMcp = null },
        )
    }
    if (creatingInstruction || editingInstruction != null) {
        InstructionEditorDialog(
            initial = editingInstruction,
            onDismiss = { creatingInstruction = false; editingInstruction = null },
            onSave = {
                app.extensions.upsertInstruction(it)
                creatingInstruction = false; editingInstruction = null
            },
        )
    }
    if (creatingSubagent || editingSubagent != null) {
        SubagentEditorDialog(
            initial = editingSubagent,
            onDismiss = { creatingSubagent = false; editingSubagent = null },
            onSave = {
                app.extensions.upsertSubagent(it)
                creatingSubagent = false; editingSubagent = null
            },
        )
    }
    if (creatingHook || editingHook != null) {
        HookEditorDialog(
            initial = editingHook,
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
            onDismiss = { creatingSkill = false; editingSkill = null },
        )
    }
    if (importSkillDialog) {
        TextInputDialog(
            title = "从 GitHub 导入技能 (SKILL.md)",
            initial = "https://github.com/",
            onDismiss = { importSkillDialog = false },
        ) { url ->
            importSkillDialog = false
            scope.launch {
                busyMessage = "正在从 GitHub 克隆并校验 SKILL.md…"
                try { app.extensions.importSkillFromGitHub(url) }
                catch (e: Exception) { app.conversations.notify("导入技能失败：${e.message}") }
                finally { busyMessage = null }
            }
        }
    }
    if (importPluginDialog) {
        TextInputDialog(
            title = "从 GitHub 安装插件包",
            initial = "https://github.com/",
            onDismiss = { importPluginDialog = false },
        ) { url ->
            importPluginDialog = false
            scope.launch {
                busyMessage = "正在拉取并校验插件包（MCP / Skills）…"
                try { app.extensions.importPluginFromGitHub(url) }
                catch (e: Exception) { app.conversations.notify("安装插件失败：${e.message}") }
                finally { busyMessage = null }
            }
        }
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
}

@Composable
private fun McpEditorSheet(
    app: GaliaisApplication,
    initial: ManagedMcpServer?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var idDraft by remember { mutableStateOf(initial?.id ?: "") }
    var transport by remember { mutableStateOf(initial?.transport ?: "stdio") }
    var command by remember { mutableStateOf(initial?.command ?: "npx") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var envText by remember {
        val values = if (initial?.transport == "http") initial.headers else initial?.env
        mutableStateOf(values?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    var secretKeyName by remember { mutableStateOf(initial?.secretEnvKeys?.firstOrNull() ?: initial?.secretHeaderKeys?.firstOrNull() ?: "") }
    var secretKeyValue by remember { mutableStateOf("") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude")).filterNot { it == "all" }.toSet()) }
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
            Text(if (initial == null) "添加 MCP 服务器" else "编辑 MCP · ${initial.name}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            SegmentedPillBar(
                options = listOf("stdio" to "Stdio 本地进程", "http" to "HTTP / SSE 远程"),
                selected = transport,
                onSelect = { transport = it },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; if (initial == null) idDraft = it.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-') },
                label = { Text("显示名称") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (transport == "stdio") {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.trim() },
                    label = { Text("启动命令（如 npx / uvx / node）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text("命令参数（空格分隔）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("服务端 URL（https://...）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = envText,
                onValueChange = { envText = it },
                label = { Text(if (transport == "http") "附加 Headers（每行 Key=Value）" else "普通环境变量（每行 KEY=VALUE）") },
                minLines = 2,
                maxLines = 4,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secretKeyName,
                onValueChange = { secretKeyName = it.trim() },
                label = { Text(if (transport == "http") "加密 Header 名称（可选，如 Authorization）" else "加密密钥环境变量名（可选，如 GITHUB_TOKEN）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            if (secretKeyName.isNotBlank()) {
                val alreadySaved = initial != null && app.settings.hasExtensionSecret(initial.id, secretKeyName)
                OutlinedTextField(
                    value = secretKeyValue,
                    onValueChange = { secretKeyValue = it },
                    label = { Text(if (alreadySaved) "密钥值（已加密保存，留空保持不变）" else "密钥值（Android Keystore 加密存储）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("生效 Agent", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectableChip("全部 Agent", selected = targetAll, onClick = { targetAll = true })
                listOf("codex" to "Codex", "claude" to "Claude Code", "antigravity" to "Antigravity").forEach { (id, label) ->
                    val picked = !targetAll && id in selectedTargets
                    SelectableChip(label, selected = picked, onClick = {
                        targetAll = false
                        selectedTargets = if (id in selectedTargets) selectedTargets - id else selectedTargets + id
                    })
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并同步", onClick = {
                    val cleanName = name.trim()
                    if (cleanName.isBlank()) { error = "请填写名称"; return@PrimaryPillButton }
                    val slug = (initial?.id ?: idDraft).trim()
                        .ifBlank { cleanName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-') }
                    val cleanId = slug.ifBlank { "mcp-${java.util.UUID.randomUUID().toString().take(8)}" }
                    if (transport == "stdio" && command.isBlank()) { error = "请填写启动命令"; return@PrimaryPillButton }
                    if (transport == "http" && url.isBlank()) { error = "请填写服务 URL"; return@PrimaryPillButton }
                    val kv = linkedMapOf<String, String>()
                    envText.lineSequence().map { it.trim() }.filter { it.contains('=') }.forEach { line ->
                        val i = line.indexOf('=')
                        val k = line.substring(0, i).trim()
                        if (k.isNotEmpty()) kv[k] = line.substring(i + 1).trim()
                    }
                    val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                    val mergedSecretEnvKeys = if (transport == "stdio") {
                        val existingExtra = initial?.secretEnvKeys.orEmpty().drop(1)
                        (listOfNotNull(secretKeyName.takeIf { it.isNotBlank() }) + existingExtra).distinct()
                    } else emptyList()
                    val mergedSecretHeaderKeys = if (transport == "http") {
                        val existingExtra = initial?.secretHeaderKeys.orEmpty().drop(1)
                        (listOfNotNull(secretKeyName.takeIf { it.isNotBlank() }) + existingExtra).distinct()
                    } else emptyList()
                    val srv = ManagedMcpServer(
                        id = cleanId,
                        name = cleanName,
                        transport = transport,
                        command = if (transport == "stdio") command else null,
                        args = if (transport == "stdio") argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() } else emptyList(),
                        url = if (transport == "http") url else null,
                        env = if (transport == "stdio") kv else emptyMap(),
                        secretEnvKeys = mergedSecretEnvKeys,
                        headers = if (transport == "http") kv else emptyMap(),
                        secretHeaderKeys = mergedSecretHeaderKeys,
                        enabled = initial?.enabled ?: true,
                        scope = initial?.scope ?: "global",
                        targets = targets,
                        pluginId = initial?.pluginId,
                    )
                    val secrets = if (secretKeyName.isNotBlank() && secretKeyValue.isNotBlank()) mapOf(secretKeyName to secretKeyValue.trim()) else emptyMap()
                    app.extensions.upsertMcp(srv, secrets)
                    onDismiss()
                })
            }
        }
    }
}

@Composable
private fun SkillEditorSheet(
    app: GaliaisApplication,
    initial: ManagedSkill?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var body by remember { mutableStateOf(initial?.body ?: "") }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
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
            Text(if (initial == null) "新建技能 (SKILL.md)" else "编辑技能 · ${initial.name}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("技能名称（建议英文短横线，如 compose-audit）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("触发描述（告诉 Agent 何时调用该技能）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("技能正文（Markdown 规范与步骤）") },
                minLines = 6,
                maxLines = 12,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial != null && initial.pluginId == null) {
                    TextButton(enabled = !saving && !deleting, onClick = {
                        deleting = true
                        error = null
                        scope.launch {
                            try {
                                app.extensions.deleteSkill(initial.id)
                                onDismiss()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                error = e.message ?: "技能删除失败，请重试"
                            } finally {
                                deleting = false
                            }
                        }
                    }) { Text(if (deleting) "删除中…" else "删除", color = G.danger) }
                }
                Spacer(Modifier.weight(1f))
                SecondaryPillButton("取消", onClick = onDismiss)
                PrimaryPillButton(if (saving) "保存中…" else "保存并同步", enabled = !saving && !deleting, onClick = {
                    if (name.isBlank() || body.isBlank()) { error = "请填写技能名称和正文"; return@PrimaryPillButton }
                    saving = true; error = null
                    scope.launch {
                        try {
                            app.extensions.saveLocalSkill(
                                id = initial?.id,
                                name = name.trim(),
                                description = desc.trim(),
                                body = body,
                                targets = initial?.targets ?: listOf("all"),
                            )
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message
                            saving = false
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun PluginSecretsDialog(
    title: String,
    specs: List<com.galiais.agent.engine.PluginSecretSpec>,
    bucket: String,
    app: GaliaisApplication,
    confirmText: String,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val values = remember(specs) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        shape = D.dialogShape,
        title = { Text(title, color = G.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                specs.forEach { spec ->
                    val saved = app.settings.hasExtensionSecret(bucket, spec.key)
                    OutlinedTextField(
                        value = values[spec.key] ?: "",
                        onValueChange = { values[spec.key] = it },
                        label = { Text(if (saved) "${spec.label}（已保存，留空不变）" else spec.label) },
                        supportingText = if (spec.hint.isNotBlank()) ({ Text(spec.hint, style = MaterialTheme.typography.labelSmall) }) else null,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() })
            }) { Text(confirmText, color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) } },
    )
}

@Composable
private fun TargetAgentsPicker(
    targetAll: Boolean,
    selectedTargets: Set<String>,
    onChange: (Boolean, Set<String>) -> Unit,
) {
    Text("生效 Agent", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectableChip("全部 Agent", selected = targetAll, onClick = { onChange(true, selectedTargets) })
        listOf("codex" to "Codex", "claude" to "Claude Code", "antigravity" to "Antigravity").forEach { (id, label) ->
            val picked = !targetAll && id in selectedTargets
            SelectableChip(label, selected = picked, onClick = {
                val next = if (id in selectedTargets) selectedTargets - id else selectedTargets + id
                onChange(false, next)
            })
        }
    }
}

@Composable
private fun InstructionEditorDialog(
    initial: ManagedInstruction?,
    onDismiss: () -> Unit,
    onSave: (ManagedInstruction) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "新建全局指令块" else "编辑指令 · ${initial.title}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text("自动以标记区块无损同步到 CLAUDE.md / AGENTS.md / GEMINI.md，不覆盖你手写的内容。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("指令标题（如：中文回复与代码规范）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                label = { Text("指令正文（Markdown）") }, minLines = 5, maxLines = 10,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并同步", onClick = {
                    val t = title.trim(); val c = content.trim()
                    if (t.isBlank() || c.isBlank()) { error = "请填写标题和指令正文"; return@PrimaryPillButton }
                    val id = initial?.id ?: ("inst-" + System.currentTimeMillis().toString(36))
                    val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                    onSave(ManagedInstruction(id = id, title = t, content = c, enabled = initial?.enabled ?: true, targets = targets))
                })
            }
        }
    }
}

@Composable
private fun SubagentEditorDialog(
    initial: ManagedSubagent?,
    onDismiss: () -> Unit,
    onSave: (ManagedSubagent) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "新建子代理 (Subagent)" else "编辑子代理 · ${initial.name}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text("自动同步至 .claude/agents、.codex/agents 与 .agents/agents 目录，三端原生复用。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("子代理名称（建议英文短横线，如 code-reviewer）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("职责描述（告诉主代理何时委派）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it.trim() },
                label = { Text("推荐模型（可选，留空继承主会话）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("系统提示词（System Prompt）") }, minLines = 5, maxLines = 10,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并同步", onClick = {
                    val n = name.trim(); val p = prompt.trim()
                    if (n.isBlank() || p.isBlank()) { error = "请填写子代理名称和系统提示词"; return@PrimaryPillButton }
                    val id = initial?.id ?: n.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "sub-${System.currentTimeMillis().toString(36)}" }
                    val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                    onSave(ManagedSubagent(id = id, name = n, description = description.trim(), model = model.trim(), prompt = p, enabled = initial?.enabled ?: true, targets = targets))
                })
            }
        }
    }
}

@Composable
private fun HookEditorDialog(
    initial: ManagedHook?,
    onDismiss: () -> Unit,
    onSave: (ManagedHook) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var event by remember { mutableStateOf(initial?.event ?: "PostToolUse") }
    var matcher by remember { mutableStateOf(initial?.matcher ?: "*") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "新建生命周期钩子 (Hook)" else "编辑钩子 · ${initial.name}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("钩子名称（如：提交后格式化）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Text("触发事件", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PreToolUse", "PostToolUse", "Stop", "SessionStart").forEach { ev ->
                    SelectableChip(ev, selected = event == ev, onClick = { event = ev })
                }
            }
            OutlinedTextField(
                value = matcher, onValueChange = { matcher = it.trim() },
                label = { Text("工具匹配器 Matcher（如 * / Edit|Write / Bash）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = command, onValueChange = { command = it },
                label = { Text("执行 Shell 命令") }, minLines = 2, maxLines = 5,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并同步", onClick = {
                    val n = name.trim(); val cmd = command.trim()
                    if (n.isBlank() || cmd.isBlank()) { error = "请填写名称和命令"; return@PrimaryPillButton }
                    val id = initial?.id ?: ("hook-" + System.currentTimeMillis().toString(36))
                    val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                    onSave(ManagedHook(id = id, name = n, event = event, matcher = matcher.ifBlank { "*" }, command = cmd, enabled = initial?.enabled ?: true, targets = targets))
                })
            }
        }
    }
}
