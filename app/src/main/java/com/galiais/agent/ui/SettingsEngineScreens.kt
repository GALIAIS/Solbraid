package com.galiais.agent.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentAuthState
import com.galiais.agent.engine.ClaudeAgentSummary
import com.galiais.agent.engine.CliAuthStatus
import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.ExperimentalFeatureItem
import com.galiais.agent.engine.McpServerSummary
import com.galiais.agent.engine.ModelOption
import com.galiais.agent.engine.ProviderAuthMode
import com.galiais.agent.engine.ProviderHealthSnapshot
import com.galiais.agent.engine.RateLimitSummary
import com.galiais.agent.engine.RoutingGroup
import com.galiais.agent.engine.RoutingTarget
import com.galiais.agent.engine.SkillSummary
import com.galiais.agent.engine.exportProvidersBundleJson
import com.galiais.agent.engine.newProviderId
import com.galiais.agent.engine.parseProvidersBundleJson
import com.galiais.agent.engine.providerPresets
import com.galiais.agent.engine.str
import com.galiais.agent.engine.reasoningEfforts
import com.galiais.agent.engine.selectedModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class ProviderTestResult(val ok: Boolean, val latencyMs: Int?, val message: String)

private val claudeAliases = listOf(
    ModelOption("sonnet", "Sonnet（别名）"),
    ModelOption("opus", "Opus（别名）"),
    ModelOption("haiku", "Haiku（别名）"),
)

/** 服务商中心（融合 Magpie 多协议端点、密钥池故障转移与跨服务商路由组）。 */
@Composable
fun ProvidersSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val activePluginProtocols = remember(hostPlugins) {
        if (hostPlugins.safeMode) emptySet()
        else hostPlugins.translators.map { "plugin:${it.id.lowercase()}" }.toSet()
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var activeTab by rememberSaveable { mutableStateOf("providers") } // "providers" | "groups"
    var editingProvider by remember { mutableStateOf<CustomProvider?>(null) }
    var addingProvider by remember { mutableStateOf(false) }
    var deletingProvider by remember { mutableStateOf<CustomProvider?>(null) }
    var editingGroup by remember { mutableStateOf<RoutingGroup?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<RoutingGroup?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var fetchedPicker by remember { mutableStateOf<Pair<CustomProvider, List<String>>?>(null) }
    val testStatus = remember { mutableStateMapOf<String, ProviderTestResult>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val healthMap = remember { mutableStateMapOf<String, ProviderHealthSnapshot>() }

    fun refreshHealth() {
        if (!runtime.ready) {
            healthMap.clear()
            return
        }
        scope.launch {
            val latest = runCatching { app.conversations.fetchProviderHealth() }.getOrDefault(emptyMap())
            healthMap.clear()
            healthMap.putAll(latest)
        }
    }

    LaunchedEffect(runtime.ready, settings.providers, settings.routingGroups) {
        if (!runtime.ready) {
            healthMap.clear()
        } else {
            val latest = runCatching { app.conversations.fetchProviderHealth() }.getOrDefault(emptyMap())
            healthMap.clear()
            healthMap.putAll(latest)
        }
    }

    SubPageScaffold(
        title = "服务商",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (settings.providers.isNotEmpty() || settings.routingGroups.isNotEmpty()) {
                TagPill("导出", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = {
                    val json = exportProvidersBundleJson(settings.providers, settings.routingGroups)
                    scope.launch {
                        clipboard.setClipEntry(ClipData.newPlainText("solbraid-providers.json", json).toClipEntry())
                        app.conversations.notify("已复制服务商配置 JSON（不含明文 API Key）")
                    }
                })
            }
            TagPill("导入", fg = G.accent, bg = G.accentContainer, onClick = {
                importError = null
                showImportDialog = true
            })
        },
        floatingActionButton = {
            PrimaryFab(
                text = if (activeTab == "groups") "新建路由组" else "添加服务商",
                icon = GaliaisIcons.Add,
                onClick = { if (activeTab == "groups") addingGroup = true else addingProvider = true },
            )
        },
    ) {
        if (!runtime.ready) {
            CrispCard(background = G.surfaceSubtle) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "运行环境未启动，启动后可拉取远端模型、实测 Ping 及查看密钥冷却状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TagPill(
                        text = if (runtime.busy) "启动中…" else "启动环境",
                        fg = if (!runtime.busy && runtime.bound) G.onAccent else G.inkSubtle,
                        bg = if (!runtime.busy && runtime.bound) G.accent else G.surface,
                        onClick = if (!runtime.busy && runtime.bound) app.runtime::start else null,
                    )
                }
            }
        }

        SegmentedPillBar(
            options = listOf(
                "providers" to "服务商 (${settings.providers.size})",
                "groups" to "路由组 (${settings.routingGroups.size})",
            ),
            selected = activeTab,
            onSelect = { activeTab = it },
        )

        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 260.dp)) {
            Crossfade(targetState = activeTab, animationSpec = tween(120), label = "providersTab") { currentTab ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
                    if (currentTab == "groups") {
            if (settings.routingGroups.isEmpty()) {
                EmptyStateView(
                    icon = GaliaisIcons.SwapHoriz,
                    title = "尚未创建路由组",
                    description = "将多个服务商与模型编排为主备故障转移、轮询均衡或最低费率策略，像单个服务商一样绑定到任意 Agent。",
                    actionLabel = "新建路由组",
                    onAction = { addingGroup = true },
                )
            } else {
                SectionLabel("已配置路由组（${settings.routingGroups.size}）")
                settings.routingGroups.forEach { group ->
                    val vid = group.virtualProviderId
                    val isCodex = settings.codexProviderId == vid
                    val isClaude = settings.claudeProviderId == vid
                    val isAgy = settings.agentProviders["antigravity"] == vid
                    var groupMenu by remember(group.id) { mutableStateOf(false) }
                    CrispCard(padding = PaddingValues(0.dp)) {
                        Column(
                            Modifier.fillMaxWidth().clickable { editingGroup = group }.padding(D.space(14.dp)),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(group.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TagPill(group.strategyLabel(), fg = G.accent, bg = G.accentContainer)
                                Box {
                                    IconButton(onClick = { groupMenu = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多操作", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                                    }
                                    GaliaisDropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            GaliaisMenuRow(title = "编辑路由组", onClick = { groupMenu = false; editingGroup = group })
                                            GaliaisMenuRow(title = "删除路由组", tintOverride = G.danger, onClick = { groupMenu = false; deletingGroup = group })
                                        }
                                    }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                group.targets.forEachIndexed { idx, t ->
                                    val p = settings.providers.firstOrNull { it.id == t.providerId }
                                    val pName = p?.name ?: t.providerId
                                    val missingKey = p != null && p.authMode != ProviderAuthMode.NONE && !p.hasApiKey
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            "${idx + 1}. $pName · ${t.model}",
                                            fontFamily = Mono,
                                            fontSize = 11.5.sp,
                                            color = if (p == null || !p.enabled) G.inkSubtle else G.inkMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (group.strategy == "round_robin") {
                                            TagPill("${t.weight}x", fg = G.inkMuted, bg = G.surfaceSubtle)
                                        }
                                        val missingPluginProto = p != null && p.customProtocolId.startsWith("plugin:") &&
                                            p.customProtocolId.lowercase() !in activePluginProtocols
                                        when {
                                            p == null -> TagPill("已删除", fg = G.danger, bg = G.dangerTint)
                                            !p.enabled -> TagPill("已停用", fg = G.warning, bg = G.warningTint)
                                            missingPluginProto -> TagPill("插件协议未加载", fg = G.warning, bg = G.warningTint)
                                            missingKey -> TagPill("缺密钥", fg = G.warning, bg = G.warningTint)
                                        }
                                    }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill(if (isCodex) "Codex 默认" else "设为 Codex", fg = if (isCodex) G.codex else G.inkMuted, bg = if (isCodex) G.codexTint else G.surfaceSubtle, border = if (isCodex) G.codex.copy(alpha = 0.4f) else G.border, onClick = {
                                    app.settings.setCodexProvider(if (isCodex) null else vid)
                                })
                                TagPill(if (isClaude) "Claude 默认" else "设为 Claude", fg = if (isClaude) G.claude else G.inkMuted, bg = if (isClaude) G.claudeTint else G.surfaceSubtle, border = if (isClaude) G.claude.copy(alpha = 0.4f) else G.border, onClick = {
                                    app.settings.setClaudeProvider(if (isClaude) null else vid)
                                })
                                TagPill(if (isAgy) "Antigravity 默认" else "设为 Antigravity", fg = if (isAgy) G.accent else G.inkMuted, bg = if (isAgy) G.accentContainer else G.surfaceSubtle, border = if (isAgy) G.accent.copy(alpha = 0.4f) else G.border, onClick = {
                                    app.settings.setAgentProvider("antigravity", if (isAgy) null else vid)
                                })
                            }
                        }
                    }
                }
            }
        } else if (settings.providers.isEmpty()) {
            EmptyStateView(
                icon = GaliaisIcons.Plug,
                title = "尚未添加服务商",
                description = "内置 ${providerPresets.size} 个主流官方/套餐/中转预设，支持多协议直通与多 Key 池故障转移。",
                actionLabel = "添加服务商",
                onAction = { addingProvider = true },
            )
        } else {
            SectionLabel("已配置服务商（${settings.providers.size}）")
            settings.providers.forEachIndexed { pIdx, provider ->
                val isCodexDefault = settings.codexProviderId == provider.id
                val isClaudeDefault = settings.claudeProviderId == provider.id
                val isAgyDefault = settings.agentProviders["antigravity"] == provider.id
                val busy = testing[provider.id] == true
                val status = testStatus[provider.id]
                val health = healthMap[provider.id]
                var menuOpen by remember(provider.id) { mutableStateOf(false) }

                fun runFetchModels() {
                    if (!runtime.ready || busy) return
                    testing[provider.id] = true
                    scope.launch {
                        try {
                            val res = app.conversations.testProvider(provider, null)
                            val ms = res.optInt("latencyMs")
                            val count = res.optInt("modelCount")
                            val remoteArr = res.optJSONArray("models")
                            val remoteList = buildList {
                                if (remoteArr != null) {
                                    for (i in 0 until remoteArr.length()) {
                                        val m = remoteArr.optString(i).trim()
                                        if (m.isNotEmpty()) add(m)
                                    }
                                }
                            }
                            testStatus[provider.id] = ProviderTestResult(true, ms, "已获取 $count 个模型 · $ms ms")
                            if (remoteList.isNotEmpty()) {
                                fetchedPicker = provider to remoteList
                            }
                        } catch (e: Exception) {
                            testStatus[provider.id] = ProviderTestResult(false, null, "连接失败：${e.message}")
                        } finally {
                            testing[provider.id] = false
                        }
                    }
                }

                fun runPingProvider() {
                    if (!runtime.ready || busy) return
                    testing[provider.id] = true
                    scope.launch {
                        try {
                            val res = app.conversations.pingProvider(provider, null)
                            val ttft = res.optInt("ttftMs")
                            val ms = res.optInt("latencyMs")
                            val m = res.optString("mappedModel").ifBlank { res.optString("model") }
                            val prev = res.optString("preview")
                            testStatus[provider.id] = ProviderTestResult(true, ms, "实测成功（$m）· TTFT ${ttft}ms / 总 ${ms}ms" + if (prev.isNotBlank()) " · 「$prev」" else "")
                        } catch (e: Exception) {
                            testStatus[provider.id] = ProviderTestResult(false, null, "推理实测失败：${e.message}")
                        } finally {
                            testing[provider.id] = false
                            refreshHealth()
                        }
                    }
                }

                CrispCard(padding = PaddingValues(0.dp)) {
                    Column(
                        Modifier.fillMaxWidth().clickable { editingProvider = provider }.padding(D.space(14.dp)),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (provider.enabled) G.ink else G.inkSubtle,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            GaliaisSwitch(checked = provider.enabled, onCheckedChange = { on ->
                                app.conversations.saveProvider(provider.copy(enabled = on), null)
                            })
                            Box {
                                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多操作", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                                }
                                GaliaisDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        GaliaisMenuRow(title = "编辑服务商", onClick = { menuOpen = false; editingProvider = provider })
                                        if (runtime.ready && !busy) {
                                            GaliaisMenuRow(title = "拉取远端模型", onClick = { menuOpen = false; runFetchModels() })
                                            GaliaisMenuRow(title = "实测推理 Ping", onClick = { menuOpen = false; runPingProvider() })
                                        }
                                        if (settings.providers.size > 1 && pIdx > 0) {
                                            GaliaisMenuRow(title = "上移", onClick = { menuOpen = false; app.conversations.moveProvider(provider.id, -1) })
                                        }
                                        if (settings.providers.size > 1 && pIdx < settings.providers.lastIndex) {
                                            GaliaisMenuRow(title = "下移", onClick = { menuOpen = false; app.conversations.moveProvider(provider.id, +1) })
                                        }
                                        GaliaisMenuRow(
                                            title = "复制副本",
                                            onClick = {
                                                menuOpen = false
                                                val copyName = ("${provider.name} 副本").take(80)
                                                val clone = provider.copy(id = newProviderId(), name = copyName)
                                                val keys = app.settings.providerApiKeys(provider.id)
                                                app.conversations.saveProvider(clone, apiKey = keys.firstOrNull(), apiKeysPool = keys)
                                                app.conversations.notify("已复制为「$copyName」")
                                            },
                                        )
                                        if (runtime.ready && (provider.keyPoolCount > 1 || health?.hasCoolingKeys == true)) {
                                            GaliaisMenuRow(
                                                title = "重置密钥冷却",
                                                onClick = {
                                                    menuOpen = false
                                                    scope.launch {
                                                        runCatching { app.conversations.resetProviderCooldowns(provider.id) }
                                                            .onSuccess {
                                                                app.conversations.notify("已重置「${provider.name}」密钥冷却状态")
                                                                refreshHealth()
                                                            }
                                                            .onFailure { app.conversations.notify("重置失败：${it.message}") }
                                                    }
                                                },
                                            )
                                        }
                                        GaliaisMenuRow(
                                            title = "删除服务商",
                                            tintOverride = G.danger,
                                            onClick = { menuOpen = false; deletingProvider = provider },
                                        )
                                    }
                                }
                            }
                        }
                        Text(provider.baseUrl, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TagPill(provider.supportedProtocolsSummary(), fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border)
                            if (!provider.enabled) {
                                TagPill("已停用", fg = G.inkSubtle, bg = G.surfaceSubtle)
                            }
                            val missingPluginProto = provider.customProtocolId.startsWith("plugin:") &&
                                provider.customProtocolId.lowercase() !in activePluginProtocols
                            if (missingPluginProto) {
                                TagPill("插件协议未加载", fg = G.warning, bg = G.warningTint)
                            }
                            provider.billingLabel().takeIf { it.isNotBlank() }?.let { b ->
                                TagPill(b, fg = if (provider.billingKind == "plan") G.accent else if (provider.billingKind == "free") G.success else G.inkMuted, bg = G.surfaceSubtle)
                            }
                            if (provider.billingKind !in setOf("plan", "free") && provider.priceRate != 1.0) {
                                TagPill("${provider.priceRate}x", fg = G.warning, bg = G.warningTint)
                            }
                            status?.latencyMs?.takeIf { status.ok }?.let { ms ->
                                val (fg, bg) = when {
                                    ms < 1500 -> G.success to G.successTint
                                    ms < 3500 -> G.warning to G.warningTint
                                    else -> G.danger to G.dangerTint
                                }
                                TagPill("${ms}ms", fg = fg, bg = bg)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val (keyLabel, keyColor) = when {
                                provider.authMode == ProviderAuthMode.NONE -> "无需密钥" to G.inkSubtle
                                provider.keyPoolCount > 1 -> "${provider.keyPoolCount} 密钥轮换" to G.success
                                provider.hasApiKey -> "已保存密钥" to G.success
                                else -> "未设置密钥" to G.warning
                            }
                            Text("${provider.models.size} 模型", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                            Text("·", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                            Text(keyLabel, style = MaterialTheme.typography.labelSmall, color = keyColor)
                            if (provider.modelMap.isNotEmpty()) {
                                Text("· 映射 ${provider.modelMap.size} 条", style = MaterialTheme.typography.labelSmall, color = G.claude)
                            }
                        }
                        if (health?.hasCoolingKeys == true) {
                            Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.warningTint) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${health.coolingCount}/${maxOf(1, health.keyCount)} 个密钥冷却中（剩余约 ${health.maxCooldownSec}s）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.warning,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TagPill(
                                        text = "立即恢复",
                                        fg = G.warning,
                                        bg = G.surface,
                                        onClick = {
                                            scope.launch {
                                                runCatching { app.conversations.resetProviderCooldowns(provider.id) }
                                                    .onSuccess {
                                                        app.conversations.notify("已恢复「${provider.name}」冷却密钥")
                                                        refreshHealth()
                                                    }
                                                    .onFailure { app.conversations.notify("恢复失败：${it.message}") }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagPill(
                                text = if (isCodexDefault) "Codex 默认" else "设为 Codex",
                                fg = if (isCodexDefault) G.codex else G.inkMuted,
                                bg = if (isCodexDefault) G.codexTint else G.surfaceSubtle,
                                border = if (isCodexDefault) G.codex.copy(alpha = 0.4f) else G.border,
                                onClick = {
                                    app.settings.setCodexProvider(if (isCodexDefault) null else provider.id)
                                    app.conversations.notify(if (isCodexDefault) "Codex 已切回 OpenAI 官方" else "Codex 默认服务商已设为「${provider.name}」")
                                },
                            )
                            TagPill(
                                text = if (isClaudeDefault) "Claude 默认" else "设为 Claude",
                                fg = if (isClaudeDefault) G.claude else G.inkMuted,
                                bg = if (isClaudeDefault) G.claudeTint else G.surfaceSubtle,
                                border = if (isClaudeDefault) G.claude.copy(alpha = 0.4f) else G.border,
                                onClick = {
                                    app.settings.setClaudeProvider(if (isClaudeDefault) null else provider.id)
                                    app.conversations.notify(if (isClaudeDefault) "Claude Code 已切回 Anthropic 官方" else "Claude Code 默认服务商已设为「${provider.name}」")
                                },
                            )
                            TagPill(
                                text = if (isAgyDefault) "Antigravity 默认" else "设为 Antigravity",
                                fg = if (isAgyDefault) G.accent else G.inkMuted,
                                bg = if (isAgyDefault) G.accentContainer else G.surfaceSubtle,
                                border = if (isAgyDefault) G.accent.copy(alpha = 0.4f) else G.border,
                                onClick = {
                                    app.settings.setAgentProvider("antigravity", if (isAgyDefault) null else provider.id)
                                    app.conversations.notify(if (isAgyDefault) "Antigravity 已切回官方配置" else "Antigravity 默认服务商已设为「${provider.name}」")
                                },
                            )
                        }
                        if (provider.models.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                provider.models.take(4).forEach { m ->
                                    TagPill(m, fg = G.inkMuted, bg = G.surfaceSubtle)
                                }
                                if (provider.models.size > 4) TagPill("+${provider.models.size - 4}", fg = G.inkSubtle, bg = G.surfaceSubtle)
                            }
                        }
                        status?.let { res ->
                            val (fg, bg) = when {
                                !res.ok -> G.danger to G.dangerTint
                                (res.latencyMs ?: 0) >= 3500 -> G.warning to G.warningTint
                                else -> G.success to G.successTint
                            }
                            Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = bg) {
                                Text(res.message, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        if (runtime.ready) {
                            Hairline(Modifier.padding(top = 2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactActionButton(
                                    text = if (busy) "检测中…" else "拉取模型",
                                    enabled = !busy,
                                    onClick = ::runFetchModels,
                                )
                                CompactActionButton(
                                    text = "实测 Ping",
                                    enabled = !busy,
                                    onClick = ::runPingProvider,
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
        Spacer(Modifier.height(72.dp))
    }

    if (addingProvider || editingProvider != null) {
        ProviderEditorDialog(app, editingProvider, onDismiss = { addingProvider = false; editingProvider = null }) { saved ->
            addingProvider = false; editingProvider = null
            app.conversations.notify("已保存服务商「${saved.name}」")
        }
    }

    if (addingGroup || editingGroup != null) {
        RoutingGroupEditorDialog(
            providers = settings.providers,
            initial = editingGroup,
            onDismiss = { addingGroup = false; editingGroup = null },
            onSave = { group ->
                app.conversations.saveRoutingGroup(group)
                addingGroup = false; editingGroup = null
                app.conversations.notify("已保存路由组「${group.name}」")
            },
        )
    }

    deletingGroup?.let { target ->
        GaliaisConfirmDialog(
            title = "删除路由组？",
            message = "将删除路由组「${target.name}」，不会删除其中的服务商。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                app.conversations.deleteRoutingGroup(target.id)
                deletingGroup = null
                app.conversations.notify("已删除路由组「${target.name}」")
            },
            onDismiss = { deletingGroup = null },
        )
    }

    if (showImportDialog) {
        GaliaisAlertDialog(
            title = "导入服务商 JSON",
            onDismissRequest = { showImportDialog = false },
            confirmText = "导入",
            confirmEnabled = importJsonText.isNotBlank(),
            onConfirm = {
                runCatching {
                    val (providers, groups) = parseProvidersBundleJson(importJsonText)
                    providers.forEach { p -> app.conversations.saveProvider(p, null) }
                    groups.forEach { g -> app.conversations.saveRoutingGroup(g) }
                    providers.size to groups.size
                }
                    .onSuccess { (pCount, gCount) ->
                        showImportDialog = false
                        importJsonText = ""
                        app.conversations.notify("已导入 $pCount 个服务商" + if (gCount > 0) "与 $gCount 个路由组" else "")
                    }
                    .onFailure { importError = "导入失败：${it.message}" }
            },
        ) {
            Text("粘贴由「导出」生成的 JSON（或单条服务商配置）。导入后请在对应服务商中补充 API Key。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            OutlinedTextField(
                value = importJsonText,
                onValueChange = { importJsonText = it; importError = null },
                label = { Text("服务商 JSON") },
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            importError?.let { err ->
                Text(err, style = MaterialTheme.typography.labelSmall, color = G.danger)
            }
        }
    }

    fetchedPicker?.let { (targetProvider, remoteModels) ->
        RemoteModelPickerDialog(
            remote = remoteModels,
            existing = targetProvider.models.toSet(),
            onDismiss = { fetchedPicker = null },
            onConfirm = { chosen ->
                val merged = (targetProvider.models + chosen).distinct()
                runCatching {
                    app.conversations.saveProvider(targetProvider.copy(models = merged), null)
                    app.conversations.notify("已将 ${chosen.size} 个模型保存到「${targetProvider.name}」")
                }.onFailure {
                    app.conversations.notify("保存模型失败：${it.message}")
                }
                fetchedPicker = null
            },
        )
    }

    deletingProvider?.let { target ->
        GaliaisConfirmDialog(
            title = "删除服务商？",
            message = "将删除「${target.name}」及其加密保存的全部 API Key。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                app.conversations.deleteProvider(target.id)
                deletingProvider = null
                app.conversations.notify("已删除服务商「${target.name}」")
            },
            onDismiss = { deletingProvider = null },
        )
    }
}

@Composable
private fun RoutingGroupEditorDialog(
    providers: List<CustomProvider>,
    initial: RoutingGroup?,
    onDismiss: () -> Unit,
    onSave: (RoutingGroup) -> Unit,
) {
    val id = remember(initial) { initial?.id ?: newProviderId() }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var strategy by remember(initial) { mutableStateOf(initial?.strategy ?: "failover") }
    var targets by remember(initial) { mutableStateOf(initial?.targets ?: emptyList()) }
    var pickProviderId by remember(providers) { mutableStateOf(providers.firstOrNull { it.enabled }?.id ?: providers.firstOrNull()?.id ?: "") }
    var pickModel by remember(pickProviderId, providers) {
        mutableStateOf(providers.firstOrNull { it.id == pickProviderId }?.models?.firstOrNull() ?: "")
    }
    val pickedProvider = providers.firstOrNull { it.id == pickProviderId }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建服务商路由组" else "编辑路由组",
        subtitle = "跨服务商编排主备故障转移、权重轮询或最低费率策略",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton(
                text = "保存路由组",
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && targets.isNotEmpty(),
                onClick = { onSave(RoutingGroup(id = id, name = name.trim(), strategy = strategy, targets = targets)) },
            )
        },
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("路由组名称（如：主力编程高可用）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
        AgentOptionChips(
            title = "调度策略",
            options = listOf("failover" to "主备故障转移", "round_robin" to "轮询均衡", "cheapest" to "最低费率"),
            selected = strategy,
            onSelect = { if (it != null) strategy = it },
        )
        Text(
            when (strategy) {
                "round_robin" -> "按节点权重轮询分发请求，点下方节点权重标签可循环切换 1x–5x。"
                "cheapest" -> "优先路由到免费、订阅套餐与低倍率节点，故障时自动回退下一节点。"
                else -> "严格按列表顺序请求主节点，遇到 429 / 5xx 自动无感切换至备选节点。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = G.inkMuted,
        )
        if (providers.isEmpty()) {
            Text("请先在「服务商」页添加至少 1 个服务商。", style = MaterialTheme.typography.bodySmall, color = G.warning)
        } else {
            Text("选择服务商与目标模型", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                providers.forEach { p ->
                    val chipLabel = if (!p.enabled) "${p.name}(已停用)" else p.name
                    SelectableChip(chipLabel, selected = pickProviderId == p.id, onClick = {
                        pickProviderId = p.id
                        pickModel = p.models.firstOrNull() ?: ""
                    })
                }
            }
            if (pickedProvider != null && (!pickedProvider.enabled || (pickedProvider.authMode != ProviderAuthMode.NONE && !pickedProvider.hasApiKey))) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.warningTint) {
                    Text(
                        if (!pickedProvider.enabled) "提示：「${pickedProvider.name}」当前已停用，请求会被路由组跳过。"
                        else "提示：「${pickedProvider.name}」尚未设置 API Key，请求可能会鉴权失败。",
                        style = MaterialTheme.typography.labelSmall,
                        color = G.warning,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            if (pickedProvider != null && pickedProvider.models.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    pickedProvider.models.take(12).forEach { m ->
                        SelectableChip(m, selected = pickModel == m, onClick = { pickModel = m })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = pickModel, onValueChange = { pickModel = it.trim() }, label = { Text("目标模型 ID") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.weight(1f))
                SecondaryPillButton("加入", enabled = pickProviderId.isNotBlank() && pickModel.isNotBlank(), onClick = {
                    targets = targets + RoutingTarget(pickProviderId, pickModel.trim(), weight = 1)
                })
            }
        }
        if (targets.isNotEmpty()) {
            Text("已编排节点（${targets.size}）", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                targets.forEachIndexed { idx, t ->
                    val p = providers.firstOrNull { it.id == t.providerId }
                    val pName = p?.name ?: t.providerId
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "${idx + 1}. $pName / ${t.model}",
                            fontFamily = Mono,
                            fontSize = 11.5.sp,
                            color = if (p == null || !p.enabled) G.inkSubtle else G.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (strategy == "round_robin") {
                            TagPill("${t.weight}x", fg = G.accent, bg = G.accentContainer, onClick = {
                                val nextWeight = if (t.weight >= 5) 1 else t.weight + 1
                                targets = targets.mapIndexed { i, item -> if (i == idx) item.copy(weight = nextWeight) else item }
                            })
                        }
                        if (idx > 0) {
                            TagPill("↑", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = {
                                val list = targets.toMutableList()
                                val cur = list.removeAt(idx)
                                list.add(idx - 1, cur)
                                targets = list
                            })
                        }
                        if (idx < targets.lastIndex) {
                            TagPill("↓", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = {
                                val list = targets.toMutableList()
                                val cur = list.removeAt(idx)
                                list.add(idx + 1, cur)
                                targets = list
                            })
                        }
                        TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = { targets = targets.filterIndexed { i, _ -> i != idx } })
                    }
                }
            }
        }
    }
}

/** Codex 设置专属设置子页面。 */
@Composable
fun CodexSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit, onOpenProviders: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val accountVersion by app.conversations.codex.accountVersion.collectAsState()
    val loginFlow by app.conversations.codex.loginFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    var account by remember { mutableStateOf<String?>(null) }
    var codexModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var mcpServers by remember { mutableStateOf<List<McpServerSummary>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillSummary>>(emptyList()) }
    var rateLimits by remember { mutableStateOf<List<RateLimitSummary>>(emptyList()) }
    var expFeatures by remember { mutableStateOf<List<ExperimentalFeatureItem>>(emptyList()) }
    var reloadingMcp by remember { mutableStateOf(false) }
    var codexKey by remember { mutableStateOf("") }

    fun report(action: String, block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { app.conversations.notify("$action：${e.message}") }
    }

    LaunchedEffect(runtime.ready, accountVersion) {
        if (!runtime.ready) {
            account = null; mcpServers = emptyList(); skills = emptyList(); rateLimits = emptyList(); expFeatures = emptyList()
            return@LaunchedEffect
        }
        account = try {
            val result = app.conversations.codex.account()
            val value = result.optJSONObject("account")
            when (value?.optString("type")) {
                null -> if (result.optBoolean("requiresOpenaiAuth")) "未登录" else "无需登录"
                "apiKey" -> "已使用 API Key 登录"
                "chatgpt" -> "ChatGPT 账号 ${value.str("email") ?: ""}"
                else -> value.optString("type")
            }
        } catch (e: Exception) { "读取失败：${e.message}" }
        mcpServers = try { app.conversations.codex.mcpServers() } catch (_: Exception) { emptyList() }
        skills = try { app.conversations.codex.skills(settings.defaultCwd) } catch (_: Exception) { emptyList() }
        rateLimits = try { app.conversations.codex.rateLimits() } catch (_: Exception) { emptyList() }
        expFeatures = try { app.conversations.codex.experimentalFeatures() } catch (_: Exception) { emptyList() }
    }

    val codexProvider = settings.provider(settings.codexProviderId)
    var codexLoading by remember { mutableStateOf(false) }
    LaunchedEffect(runtime.ready, settings.codexProviderId, settings.providers, accountVersion) {
        if (!runtime.ready) { codexModels = emptyList(); return@LaunchedEffect }
        codexLoading = true
        try { codexModels = app.conversations.codex.models(null) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { app.conversations.notify("读取 Codex 模型选项失败：${error.message}") }
        finally { codexLoading = false }
    }
    val codexModelOption = codexModels.selectedModel(settings.codexModel ?: codexProvider?.models?.firstOrNull())

    SubPageScaffold(title = "Codex 设置", onBack = onBack, snackbar = snackbar, loading = codexLoading) {
        CrispCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAppIcon(engine = EngineKind.CODEX, size = 42.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("OpenAI Codex CLI", style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                    Text("配置模型、审批和工具", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            }
        }
        CrispCard { CliUpdateControls(app, "codex") }
        SectionLabel("模型与接入方式")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderPickerRow("默认服务商", settings.codexProviderId, "OpenAI", settings.allSelectableProviders()) { app.settings.setCodexProvider(it) }
                Hairline()
                if (codexProvider == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("账号：${account ?: if (runtime.busy) "运行环境启动中…" else "运行环境未就绪"}", style = MaterialTheme.typography.bodyMedium, color = G.ink, modifier = Modifier.weight(1f))
                        if (!runtime.ready && !runtime.busy && runtime.bound) {
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        }
                        if (runtime.ready && account != null && account != "未登录" && !account!!.startsWith("读取失败")) {
                            TagPill("退出登录", fg = G.danger, bg = G.dangerTint, onClick = {
                                report("退出失败") { app.conversations.codex.logout(); app.conversations.notify("已退出 Codex 账号") }
                            })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPillButton(
                            text = "ChatGPT 设备码登录",
                            enabled = runtime.ready && loginFlow?.running != true,
                            onClick = {
                                report("启动设备码登录失败") {
                                    val flow = app.conversations.codex.startDeviceCodeLogin()
                                    if (!flow.userCode.isNullOrBlank()) {
                                        clipboard.setClipEntry(ClipData.newPlainText("deviceCode", flow.userCode).toClipEntry())
                                        app.conversations.notify("设备码 ${flow.userCode} 已复制，请打开验证页完成授权")
                                    }
                                }
                            },
                        )
                        SecondaryPillButton(
                            text = "浏览器 OAuth 登录",
                            enabled = runtime.ready && loginFlow?.running != true,
                            onClick = {
                                report("启动 OAuth 登录失败") {
                                    val flow = app.conversations.codex.startChatGptOAuthLogin()
                                    flow.authUrl?.let { url ->
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                }
                            },
                        )
                    }
                    loginFlow?.let { flow ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = D.shape(10.dp),
                            color = if (flow.error != null) G.dangerTint else G.codexTint,
                            border = BorderStroke(0.75.dp, if (flow.error != null) G.danger.copy(alpha = 0.35f) else G.codex.copy(alpha = 0.35f)),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when {
                                        flow.error != null -> "授权失败：${flow.error}"
                                        flow.type == "chatgptDeviceCode" -> "正在等待 ChatGPT 设备码授权…"
                                        else -> "请在浏览器中完成 ChatGPT 登录授权…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (flow.error != null) G.danger else G.codex,
                                    fontWeight = FontWeight.Medium,
                                )
                                flow.userCode?.let { code ->
                                    Surface(shape = D.shape(8.dp), color = G.surface) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            SelectionContainer(Modifier.weight(1f)) {
                                                Text(code, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = G.ink, letterSpacing = 1.5.sp)
                                            }
                                            TagPill("复制代码", fg = G.accent, bg = G.accentContainer, onClick = {
                                                scope.launch {
                                                    clipboard.setClipEntry(ClipData.newPlainText("deviceCode", code).toClipEntry())
                                                    app.conversations.notify("已复制设备码：$code")
                                                }
                                            })
                                        }
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    flow.authUrl?.let { url ->
                                        PrimaryPillButton(if (flow.userCode != null) "复制并打开验证页" else "打开授权页面", onClick = {
                                            scope.launch {
                                                flow.userCode?.let { clipboard.setClipEntry(ClipData.newPlainText("deviceCode", it).toClipEntry()) }
                                            }
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        })
                                        SecondaryPillButton("复制链接", onClick = {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipData.newPlainText("authUrl", url).toClipEntry())
                                                app.conversations.notify("已复制授权链接")
                                            }
                                        })
                                    }
                                    if (flow.running) {
                                        SecondaryPillButton("取消登录", danger = true, onClick = {
                                            report("取消登录失败") { app.conversations.codex.cancelLogin() }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    Hairline()
                    Text("或使用 OpenAI API Key 登录", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                    OutlinedTextField(
                        value = codexKey,
                        onValueChange = { codexKey = it.trim() },
                        label = { Text("OpenAI API Key") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryPillButton(
                        text = "用 Key 登录",
                        enabled = runtime.ready && codexKey.isNotBlank(),
                        onClick = {
                            val key = codexKey; codexKey = ""
                            report("Codex 登录失败") { app.conversations.codex.loginWithApiKey(key); app.conversations.notify("Codex 已登录") }
                        },
                    )
                    Text("ChatGPT Plus/Pro/Team 订阅账号推荐设备码登录；API Key 将由本地 Codex 保存。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                    ModelPickerRow("默认模型", settings.codexModel, codexModels) { app.settings.setCodexModel(it) }
                } else {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.codexTint) {
                        Text(
                            "使用「${codexProvider.name}」，无需登录 OpenAI。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.codex,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    val options = codexModels.ifEmpty { codexProvider.models.map { ModelOption(it, it) } }
                    ModelPickerRow("默认模型", settings.codexModel ?: codexProvider.models.firstOrNull(), options, allowDefault = false) { app.settings.setCodexModel(it) }
                    if (options.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("该服务商尚未配置模型。", style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.weight(1f))
                            TagPill("管理服务商", fg = G.accent, bg = G.accentContainer, onClick = onOpenProviders)
                        }
                    }
                }
                val efforts = codexModelOption?.reasoningEfforts().orEmpty()
                if (efforts.isNotEmpty()) {
                    Hairline()
                    AgentOptionChips("默认推理强度", listOf(null to "默认") + efforts.map { it to effortLabel(it) },
                        settings.codexEffort, app.settings::setCodexEffort)
                }
                if (codexModelOption?.serviceTiers?.isNotEmpty() == true) {
                    AgentOptionChips("默认服务档位", listOf(null to "默认") + codexModelOption.serviceTiers.map { it.id to it.name },
                        settings.codexServiceTier, app.settings::setCodexServiceTier)
                }
                AgentOptionChips("默认工作模式", listOf("default" to "执行", "plan" to "计划"), settings.codexCollaborationMode) {
                    if (it != null) app.settings.setCodexCollaborationMode(it)
                }
                AgentOptionChips("网页搜索", listOf(null to "默认", "disabled" to "关闭", "cached" to "缓存", "live" to "实时"),
                    settings.codexWebSearch, app.settings::setCodexWebSearch)
            }
        }

        SectionLabel("操作审批")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val activePosture = when {
                    settings.approvalPolicy == "never" -> "never"
                    settings.approvalsReviewer == "auto_review" -> "auto_review"
                    settings.approvalPolicy == "untrusted" -> "untrusted"
                    else -> "on-request"
                }
                RadioChoiceRow("按需审批（推荐）", activePosture == "on-request") {
                    app.settings.setApprovalPolicy("on-request")
                    app.settings.setApprovalsReviewer("user")
                }
                RadioChoiceRow("严格审批（除可信只读命令外均询问）", activePosture == "untrusted") {
                    app.settings.setApprovalPolicy("untrusted")
                    app.settings.setApprovalsReviewer("user")
                }
                RadioChoiceRow("AI 自动审校（Guardian 风险子代理）", activePosture == "auto_review") {
                    app.settings.setApprovalPolicy("on-request")
                    app.settings.setApprovalsReviewer("auto_review")
                }
                RadioChoiceRow("完全访问（不询问直接执行，谨慎开启）", activePosture == "never") {
                    app.settings.setApprovalPolicy("never")
                    app.settings.setApprovalsReviewer("user")
                }
                Text("下一轮消息起生效，也可在对话输入框左下角随时按会话切换。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
            }
        }

        if (runtime.ready && rateLimits.isNotEmpty()) {
            SectionLabel("账号额度")
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rateLimits.forEach { rl ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rl.label, style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                                Text(
                                    "${rl.usedPercent}%" + (rl.resetsAt?.let { " · 重置 $it" } ?: ""),
                                    fontFamily = Mono,
                                    fontSize = 11.5.sp,
                                    color = if (rl.usedPercent >= 85) G.danger else G.inkMuted,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (rl.usedPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                                color = if (rl.usedPercent >= 85) G.danger else if (rl.usedPercent >= 65) G.warning else G.codex,
                                trackColor = G.surfaceSubtle,
                            )
                        }
                    }
                }
            }
        }

        if (runtime.ready) {
            SectionLabel("MCP 服务与技能")
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MCP 服务器（${mcpServers.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                        TagPill(
                            text = if (reloadingMcp) "重新加载中…" else "重新加载 MCP",
                            fg = if (!reloadingMcp) G.accent else G.inkSubtle,
                            bg = if (!reloadingMcp) G.accentContainer else G.surfaceSubtle,
                            onClick = if (!reloadingMcp) {
                                {
                                    reloadingMcp = true
                                    scope.launch {
                                        try {
                                            app.conversations.codex.reloadMcpServers()
                                            mcpServers = app.conversations.codex.mcpServers()
                                            app.conversations.notify("Codex MCP 配置已重新加载")
                                        } catch (e: Exception) {
                                            app.conversations.notify("重新加载 MCP 失败：${e.message}")
                                        } finally {
                                            reloadingMcp = false
                                        }
                                    }
                                }
                            } else null,
                        )
                    }
                    if (mcpServers.isEmpty()) {
                        Text("暂无 MCP 服务。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    } else {
                        mcpServers.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(s.name, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, modifier = Modifier.weight(1f))
                                TagPill("${s.toolCount} 工具 · ${s.status}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                TagPill("OAuth", fg = G.accent, bg = G.accentContainer, onClick = {
                                    report("MCP OAuth 授权失败") {
                                        val authUrl = app.conversations.codex.startMcpOAuthLogin(s.name)
                                        if (!authUrl.isNullOrBlank()) {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))) }
                                            app.conversations.notify("已打开 ${s.name} OAuth 授权页")
                                        } else {
                                            app.conversations.notify("${s.name} 未返回 OAuth 链接（可能无需 OAuth）")
                                        }
                                    }
                                })
                            }
                        }
                    }
                    Hairline()
                    Text("工作区技能（${skills.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    if (skills.isEmpty()) {
                        Text("当前工作区未发现 Codex 技能。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    } else {
                        skills.take(15).forEach { sk ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(sk.name, fontFamily = Mono, fontSize = 12.5.sp, color = if (sk.enabled) G.ink else G.inkSubtle, fontWeight = FontWeight.Medium)
                                    if (sk.description.isNotBlank()) Text(sk.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                GaliaisSwitch(checked = sk.enabled, onCheckedChange = { checked ->
                                    report("切换技能失败") {
                                        app.conversations.codex.setSkillEnabled(sk, checked)
                                        skills = skills.map { if (it.name == sk.name && it.path == sk.path) it.copy(enabled = checked) else it }
                                        app.conversations.notify("${if (checked) "已启用" else "已停用"}技能 ${sk.name}")
                                    }
                                })
                            }
                        }
                    }
                }
            }

            if (expFeatures.isNotEmpty()) {
                SectionLabel("实验功能（${expFeatures.size}）")
                CrispCard {
                    Collapsible("查看实验功能", initiallyOpen = false) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            expFeatures.forEachIndexed { idx, f ->
                                if (idx > 0) Hairline()
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(f.displayName, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (f.stage.isNotBlank()) TagPill(f.stage, fg = G.inkMuted, bg = G.surfaceSubtle)
                                        }
                                        if (f.description.isNotBlank()) {
                                            Text(f.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    GaliaisSwitch(checked = f.enabled, onCheckedChange = { checked ->
                                        report("更新实验特性失败") {
                                            app.conversations.codex.setExperimentalFeature(f.name, checked)
                                            expFeatures = expFeatures.map { if (it.name == f.name) it.copy(enabled = checked) else it }
                                            app.conversations.notify("已${if (checked) "开启" else "关闭"} ${f.displayName}")
                                        }
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

/** Claude Code 设置专属设置子页面。 */
@Composable
fun ClaudeSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit, onOpenProviders: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val authMap by app.conversations.hub.authStates.collectAsState()
    val claudeAuthState = authMap["claude"]
    val scope = rememberCoroutineScope()
    var claudeModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var claudeMcpServers by remember { mutableStateOf<List<McpServerSummary>>(emptyList()) }
    var claudeAgents by remember { mutableStateOf<List<ClaudeAgentSummary>>(emptyList()) }
    var claudeAuthStatus by remember { mutableStateOf<CliAuthStatus?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var claudeKey by remember { mutableStateOf("") }
    var claudeOauthInput by remember { mutableStateOf("") }
    var claudeUrl by remember(settings.claudeBaseUrl) { mutableStateOf(settings.claudeBaseUrl ?: "") }
    var claudeModel by remember(settings.claudeModel) { mutableStateOf(settings.claudeModel ?: "") }
    var addingProject by remember { mutableStateOf(false) }

    fun report(action: String, block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { app.conversations.notify("$action：${e.message}") }
    }

    LaunchedEffect(runtime.ready, refreshTick, claudeAuthState?.done) {
        claudeAuthStatus = if (runtime.ready) {
            runCatching { app.conversations.hub.queryAuthStatus("claude") }.getOrNull()
        } else null
    }

    var claudeLoading by remember { mutableStateOf(false) }
    LaunchedEffect(runtime.ready, refreshTick, settings.claudeProviderId, settings.providers, claudeAuthState?.done, settings.hasClaudeKey, settings.hasClaudeOauthToken) {
        if (!runtime.ready) { claudeModels = emptyList(); claudeMcpServers = emptyList(); claudeAgents = emptyList(); return@LaunchedEffect }
        claudeLoading = true
        try {
            claudeModels = app.conversations.claude.models(null, forceRefresh = refreshTick > 0 || claudeAuthState?.done == true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { app.conversations.notify("读取 Claude Code 模型选项失败：${error.message}") }
        claudeMcpServers = try { app.conversations.claude.mcpServers(null) } catch (_: Exception) { claudeMcpServers }
        claudeAgents = try { app.conversations.claude.agents(null) } catch (_: Exception) { claudeAgents }
        claudeLoading = false
    }

    val claudeProvider = settings.provider(settings.claudeProviderId)
    val claudeModelOption = claudeModels.selectedModel(settings.claudeModel ?: claudeProvider?.models?.firstOrNull())
    var claudeAuthTab by rememberSaveable {
        mutableStateOf(
            when {
                settings.hasClaudeOauthToken -> "token"
                settings.hasClaudeKey -> "apikey"
                else -> "oauth"
            },
        )
    }

    SubPageScaffold(title = "Claude Code 设置", onBack = onBack, snackbar = snackbar, loading = claudeLoading) {
        CrispCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAppIcon(engine = EngineKind.CLAUDE, size = 42.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Anthropic Claude Code", style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                    Text("配置模型、权限和工具", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            }
        }
        CrispCard { CliUpdateControls(app, "claude") }
        SectionLabel("模型与接入方式")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderPickerRow("默认服务商", settings.claudeProviderId, "Anthropic", settings.allSelectableProviders()) { app.settings.setClaudeProvider(it) }
                Hairline()
                if (claudeProvider == null) {
                    val authLabel = when {
                        !runtime.ready -> "运行环境未就绪"
                        claudeAuthStatus?.loggedIn == true -> buildString {
                            append("Claude.ai 已登录")
                            claudeAuthStatus?.email?.let { append(" · $it") }
                            claudeAuthStatus?.subscriptionType?.let { append(" ($it)") }
                        }
                        settings.hasClaudeOauthToken -> "已保存长效 OAuth Token"
                        settings.hasClaudeKey -> "已保存 Anthropic API Key"
                        else -> "未登录（支持 Claude Pro/Max OAuth 或 API Key）"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            authLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (claudeAuthStatus?.loggedIn == true || settings.hasClaudeOauthToken || settings.hasClaudeKey) G.success else G.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (runtime.ready) {
                            TagPill("刷新", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { refreshTick++ })
                        } else if (!runtime.busy && runtime.bound) {
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        }
                    }
                    SegmentedPillBar(
                        options = listOf("oauth" to "OAuth 登录", "token" to "长效 Token", "apikey" to "API Key"),
                        selected = claudeAuthTab,
                        onSelect = { claudeAuthTab = it },
                    )
                    when (claudeAuthTab) {
                        "oauth" -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryPillButton(
                                    text = if (claudeAuthState?.running == true) "等待授权完成…" else "Claude.ai OAuth 登录",
                                    enabled = runtime.ready && claudeAuthState?.running != true,
                                    onClick = { report("启动 Claude OAuth 失败") { app.conversations.hub.startCliAuth("claude", "oauth") } },
                                )
                                SecondaryPillButton(
                                    text = "setup-token 订阅登录",
                                    enabled = runtime.ready && claudeAuthState?.running != true,
                                    onClick = { report("启动 setup-token 失败") { app.conversations.hub.startCliAuth("claude", "setup-token") } },
                                )
                                if (runtime.ready && claudeAuthStatus?.loggedIn == true) {
                                    SecondaryPillButton("退出登录", danger = true, onClick = {
                                        report("退出登录失败") {
                                            app.conversations.hub.logoutAuth("claude")
                                            refreshTick++
                                            app.conversations.notify("已退出 Claude 账号")
                                        }
                                    })
                                }
                            }
                            if (claudeAuthState != null) {
                                CliInteractiveAuthCard(
                                    app = app,
                                    agentId = "claude",
                                    authState = claudeAuthState,
                                    onCancel = { report("取消登录失败") { app.conversations.hub.cancelAuth("claude") } },
                                )
                            }
                        }
                        "token" -> {
                            Text(
                                if (settings.hasClaudeOauthToken) "长效 OAuth Token（CLAUDE_CODE_OAUTH_TOKEN）已加密保存" else "粘贴由 claude setup-token 生成的长效 OAuth Token",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (settings.hasClaudeOauthToken) G.success else G.inkMuted,
                            )
                            OutlinedTextField(
                                value = claudeOauthInput,
                                onValueChange = { claudeOauthInput = it.trim() },
                                label = { Text("CLAUDE_CODE_OAUTH_TOKEN") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryPillButton(
                                    text = "保存 Token",
                                    enabled = claudeOauthInput.isNotBlank(),
                                    onClick = {
                                        report("保存 Token 失败") {
                                            app.settings.setClaudeOauthToken(claudeOauthInput)
                                            claudeOauthInput = ""
                                            app.conversations.notify("已加密保存 OAuth Token，新会话生效")
                                        }
                                    },
                                )
                                if (settings.hasClaudeOauthToken) {
                                    SecondaryPillButton("删除 Token", danger = true, onClick = { app.settings.setClaudeOauthToken(null) })
                                }
                            }
                        }
                        else -> {
                            Text(
                                if (settings.hasClaudeKey) "Anthropic API Key 已加密保存" else "使用 Anthropic API Key 连接官方或自定义端点",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (settings.hasClaudeKey) G.success else G.inkMuted,
                            )
                            OutlinedTextField(
                                value = claudeKey,
                                onValueChange = { claudeKey = it.trim() },
                                label = { Text("Anthropic API Key") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryPillButton(
                                    text = "保存密钥",
                                    enabled = claudeKey.isNotBlank(),
                                    onClick = {
                                        report("保存失败") {
                                            app.settings.setClaudeApiKey(claudeKey)
                                            claudeKey = ""
                                            app.conversations.notify("已加密保存，新打开的 Claude 会话生效")
                                        }
                                    },
                                )
                                if (settings.hasClaudeKey) SecondaryPillButton("删除密钥", danger = true, onClick = { app.settings.setClaudeApiKey(null) })
                            }
                        }
                    }
                    Hairline()
                    ModelPickerRow("默认模型", settings.claudeModel, claudeModels.ifEmpty { claudeAliases }) {
                        app.settings.setClaudeModel(it)
                        claudeModel = it ?: ""
                    }
                    Collapsible(
                        title = if (claudeUrl.isNotBlank()) "自定义 API 地址与模型覆盖（已配置）" else "自定义 API 地址与模型覆盖（可选）",
                        initiallyOpen = claudeUrl.isNotBlank(),
                    ) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = claudeUrl,
                                onValueChange = { claudeUrl = it.trim() },
                                label = { Text("API 地址（可选，HTTPS）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = claudeModel,
                                onValueChange = { claudeModel = it.trim() },
                                label = { Text("自定义模型（可选，如 sonnet / opus）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecondaryPillButton("保存地址与模型", onClick = {
                                report("保存失败") {
                                    app.settings.setClaudeBaseUrl(claudeUrl)
                                    app.settings.setClaudeModel(claudeModel.ifBlank { null })
                                    app.conversations.notify("已保存")
                                }
                            })
                        }
                    }
                    Text("凭证仅加密保存在本机 Keystore，打开会话时传给 Claude Code。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                } else {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.claudeTint) {
                        Text(
                            "使用「${claudeProvider.name}」的模型和 API Key。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.claude,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    val options = claudeModels.ifEmpty { claudeProvider.models.map { ModelOption(it, it) } }
                    ModelPickerRow("默认模型", settings.claudeModel ?: claudeProvider.models.firstOrNull(), options, allowDefault = false) { app.settings.setClaudeModel(it) }
                    if (options.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("该服务商尚未配置模型。", style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.weight(1f))
                            TagPill("管理服务商", fg = G.accent, bg = G.accentContainer, onClick = onOpenProviders)
                        }
                    }
                }
                val efforts = claudeModelOption?.reasoningEfforts().orEmpty()
                if (efforts.isNotEmpty()) {
                    Hairline()
                    AgentOptionChips("默认推理强度", listOf(null to "默认") + efforts.map { it to effortLabel(it) },
                        settings.claudeEffort, app.settings::setClaudeEffort)
                }
                if (claudeModelOption?.supportsFastMode == true) {
                    AgentOptionChips("默认快速模式", listOf(null to "默认", "true" to "开启", "false" to "关闭"), settings.claudeFastMode?.toString()) {
                        app.settings.setClaudeFastMode(it?.toBooleanStrict())
                    }
                }
                Hairline()
                AgentOptionChips(
                    title = "Prompt 缓存窗口（ANTHROPIC_PROMPT_CACHE_TTL）",
                    options = listOf(null to "默认（自动）", "5m" to "5 分钟", "1h" to "1 小时（长对话省费）"),
                    selected = settings.claudePromptCacheTtl,
                    onSelect = {
                        app.settings.setClaudePromptCacheTtl(it)
                        app.conversations.notify("Prompt 缓存窗口已设为：${it ?: "默认"}")
                    },
                )
                SettingsToggleRow(
                    title = "Git 提交附带 Co-Authored-By 归属",
                    subtitle = "关闭后提交信息不再附加 Claude 署名。",
                    checked = settings.claudeCommitAttribution,
                    onCheckedChange = { app.settings.setClaudeCommitAttribution(it) },
                )
            }
        }

        SectionLabel("Artifacts 富交互产物")
        CrispCard {
            SettingsToggleRow(
                title = "启用 Claude Artifacts 模式",
                subtitle = "允许生成交互网页、SVG、Mermaid 图表、React 组件与 LaTeX 论文，并在离线产物工作台渲染与导出 PDF。",
                checked = settings.artifactsModeEnabled,
                onCheckedChange = {
                    app.settings.setArtifactsModeEnabled(it)
                    app.conversations.notify(if (it) "已开启 Artifacts 交互产物模式（新会话生效）" else "已关闭 Artifacts 交互产物模式")
                },
            )
        }

        SectionLabel("默认权限模式")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioChoiceRow("默认询问（高风险操作逐次确认）", settings.claudePermissionMode == "default") { app.settings.setClaudePermissionMode("default") }
                RadioChoiceRow("自动允许编辑（文件改动自动放行）", settings.claudePermissionMode == "acceptEdits") { app.settings.setClaudePermissionMode("acceptEdits") }
                RadioChoiceRow("计划模式（分析与方案规划）", settings.claudePermissionMode == "plan") { app.settings.setClaudePermissionMode("plan") }
                if (claudeModelOption?.supportsAutoMode == true) {
                    RadioChoiceRow("AI 自动审校（自动判定操作权限）", settings.claudePermissionMode == "auto") { app.settings.setClaudePermissionMode("auto") }
                }
                RadioChoiceRow("完全访问（跳过权限检查，谨慎开启）", settings.claudePermissionMode == "bypassPermissions") { app.settings.setClaudePermissionMode("bypassPermissions") }
                Text("新建会话使用此模式；已有会话可在对话输入框左下角即时切换。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
            }
        }

        if (runtime.ready) {
            SectionLabel("MCP 服务器与子代理")
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MCP 服务器（${claudeMcpServers.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                        TagPill("刷新", fg = G.accent, bg = G.accentContainer, onClick = { refreshTick++ })
                    }
                    if (claudeMcpServers.isEmpty()) {
                        Text("暂无活动 MCP 服务。打开 Claude Code 会话后可查看。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    } else {
                        claudeMcpServers.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(s.name, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, fontWeight = FontWeight.Medium)
                                    val detail = buildString {
                                        append(s.status)
                                        if (s.toolCount > 0) append(" · ${s.toolCount} 工具")
                                        s.scope?.let { append(" · $it") }
                                        s.error?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                    }
                                    Text(detail, style = MaterialTheme.typography.labelSmall, color = if (s.status == "failed") G.danger else G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (s.status != "connected" && s.enabled) {
                                    TagPill("重连", fg = G.accent, bg = G.accentContainer, onClick = {
                                        report("重连 MCP 失败") {
                                            app.conversations.claude.reconnectMcpServer(s.name)
                                            refreshTick++
                                            app.conversations.notify("已请求重连 ${s.name}")
                                        }
                                    })
                                }
                                GaliaisSwitch(checked = s.enabled, onCheckedChange = { checked ->
                                    report("切换 MCP 失败") {
                                        app.conversations.claude.toggleMcpServer(s.name, checked)
                                        refreshTick++
                                        app.conversations.notify("${if (checked) "已启用" else "已停用"} MCP ${s.name}")
                                    }
                                })
                            }
                        }
                    }
                    if (claudeAgents.isNotEmpty()) {
                        Hairline()
                        Text("可用子代理（${claudeAgents.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        claudeAgents.take(12).forEach { ag ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(ag.name, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, fontWeight = FontWeight.Medium)
                                    ag.model?.let { TagPill(it, fg = G.claude, bg = G.claudeTint) }
                                }
                                if (ag.description.isNotBlank()) Text(ag.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("已信任的项目目录")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("信任目录后，Claude Code 可加载其中的项目配置与钩子。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                if (settings.trustedProjects.isEmpty()) {
                    Text("暂无已信任的目录。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                } else {
                    settings.trustedProjects.sorted().forEach { project ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(project, fontFamily = Mono, fontSize = 12.sp, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TagPill("移除", fg = G.danger, bg = G.dangerTint, onClick = { app.settings.untrust(project) })
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                SecondaryPillButton("添加信任目录", onClick = { addingProject = true })
            }
        }
    }

    if (addingProject) {
        TextInputDialog("添加信任目录", settings.defaultCwd, onDismiss = { addingProject = false }) { raw ->
            val p = raw.trim().trimEnd('/').ifEmpty { "/" }
            if (p.startsWith("/")) { app.settings.trust(p); app.conversations.notify("已信任：$p") }
            else app.conversations.notify("目录必须以 / 开头")
            addingProject = false
        }
    }
}

@Composable
private fun RadioChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(D.shape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        GaliaisRadio(selected)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = G.ink, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ProviderPickerRow(label: String, currentId: String?, officialLabel: String, providers: List<CustomProvider>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val currentName = providers.firstOrNull { it.id == currentId }?.let { "${it.name}（${it.displayTypeBadge()}）" } ?: officialLabel
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clip(D.shape(10.dp)).clickable { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                Text(currentName, style = MaterialTheme.typography.bodyMedium, color = G.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(GaliaisIcons.ChevronDown, contentDescription = "选择", tint = G.inkMuted, modifier = Modifier.size(16.dp))
        }
        GaliaisDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GaliaisMenuRow(title = officialLabel, selected = currentId == null, onClick = { onSelect(null); open = false })
                providers.forEach { p ->
                    GaliaisMenuRow(
                        title = p.name,
                        subtitle = p.displayTypeBadge(),
                        selected = currentId == p.id,
                        onClick = { onSelect(p.id); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(label: String, current: String?, options: List<ModelOption>, allowDefault: Boolean = true, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val currentText = options.firstOrNull { it.id == current }?.label ?: current ?: "引擎默认"
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clip(D.shape(10.dp)).clickable(enabled = options.isNotEmpty()) { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                Text(
                    currentText,
                    fontFamily = if (current != null) Mono else MaterialTheme.typography.bodyMedium.fontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (options.isNotEmpty()) G.ink else G.inkSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(GaliaisIcons.ChevronDown, contentDescription = "选择", tint = if (options.isNotEmpty()) G.inkMuted else G.inkSubtle, modifier = Modifier.size(16.dp))
        }
        GaliaisDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (allowDefault) GaliaisMenuRow(title = "引擎默认", selected = current == null, onClick = { onSelect(null); open = false })
                options.forEach { option ->
                    GaliaisMenuRow(
                        title = option.label + if (option.isDefault) "（默认）" else "",
                        selected = current == option.id,
                        onClick = { onSelect(option.id); open = false },
                    )
                }
            }
        }
    }
}

/** 通用 CLI 交互式 OAuth 授权助手卡片：支持捕获授权链接、设备码复制与 PTY 授权码粘贴回传。 */
@Composable
fun CliInteractiveAuthCard(
    app: GaliaisApplication,
    agentId: String,
    authState: AgentAuthState,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var inputDraft by remember(agentId) { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = D.shape(10.dp),
        color = when {
            authState.done && authState.ok == true -> G.successTint
            authState.done && authState.ok == false -> G.dangerTint
            else -> G.surfaceSubtle
        },
        border = BorderStroke(
            0.75.dp,
            when {
                authState.needsInput -> G.accent
                authState.done && authState.ok == true -> G.success.copy(alpha = 0.4f)
                authState.done && authState.ok == false -> G.danger.copy(alpha = 0.4f)
                else -> G.border
            },
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        authState.running && authState.needsInput -> "需要粘贴授权码 / 回调内容"
                        authState.running -> "交互式 OAuth 授权进行中…"
                        authState.ok == true -> "授权登录已完成"
                        else -> "授权流程已结束"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        authState.needsInput -> G.accent
                        authState.done && authState.ok == true -> G.success
                        authState.done && authState.ok == false -> G.danger
                        else -> G.ink
                    },
                    modifier = Modifier.weight(1f),
                )
                if (authState.running) {
                    TagPill("取消", fg = G.danger, bg = G.dangerTint, onClick = onCancel)
                }
            }
            authState.deviceCode?.let { code ->
                Surface(shape = D.shape(8.dp), color = G.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(code, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = G.ink, letterSpacing = 1.2.sp)
                        }
                        TagPill("复制代码", fg = G.accent, bg = G.accentContainer, onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("deviceCode", code).toClipEntry())
                                app.conversations.notify("已复制设备码：$code")
                            }
                        })
                    }
                }
            }
            authState.url?.let { loginUrl ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryPillButton("打开授权链接", onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))) }
                            .onFailure { app.conversations.notify("无法打开浏览器：${it.message}") }
                    })
                    SecondaryPillButton("复制链接", onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText("authUrl", loginUrl).toClipEntry())
                            app.conversations.notify("已复制授权链接")
                        }
                    })
                }
            }
            if (authState.running) {
                OutlinedTextField(
                    value = inputDraft,
                    onValueChange = { inputDraft = it },
                    label = { Text(if (authState.needsInput) "粘贴浏览器返回的授权码或回调链接" else "交互输入（授权码 / 确认 y / 回调链接）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryPillButton(
                        text = "发送到终端",
                        enabled = inputDraft.isNotBlank(),
                        onClick = {
                            val text = inputDraft.trim()
                            inputDraft = ""
                            scope.launch {
                                runCatching { app.conversations.hub.sendAuthInput(agentId, text) }
                                    .onFailure { app.conversations.notify("发送失败：${it.message}") }
                            }
                        },
                    )
                    SecondaryPillButton("发送回车", onClick = {
                        scope.launch {
                            runCatching { app.conversations.hub.sendAuthInput(agentId, "") }
                                .onFailure { app.conversations.notify("发送失败：${it.message}") }
                        }
                    })
                }
            }
            if (authState.lines.isNotEmpty()) {
                Surface(shape = D.shape(8.dp), color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                    Text(
                        authState.lines.takeLast(14).joinToString("\n"),
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = G.codeInk,
                        modifier = Modifier.fillMaxWidth().padding(9.dp),
                    )
                }
            }
        }
    }
}
