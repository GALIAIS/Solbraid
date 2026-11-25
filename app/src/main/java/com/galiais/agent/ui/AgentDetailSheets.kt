package com.galiais.agent.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.AgentInstallSpec
import com.galiais.agent.engine.AgentLaunchSpec
import com.galiais.agent.engine.AgentManifest
import com.galiais.agent.engine.AgentProtocol
import com.galiais.agent.engine.AgentProviderBridge
import com.galiais.agent.engine.AgyArtifactEntry
import com.galiais.agent.engine.AgyPluginSummary
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.CliAuthStatus
import com.galiais.agent.engine.FilePreview
import com.galiais.agent.engine.ModelOption
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun UniversalAgentDetailScreen(
    app: GaliaisApplication,
    agentId: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val statuses by app.conversations.hub.statuses.collectAsState()
    val logsMap by app.conversations.hub.installLogs.collectAsState()
    val authMap by app.conversations.hub.authStates.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    val manifest = remember(agentId) { AgentCatalog.find(agentId) }
    if (manifest == null) {
        SubPageScaffold(title = "Agent 详情", onBack = onBack, snackbar = snackbar) {
            Text("未找到 Agent：$agentId", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted, modifier = Modifier.padding(16.dp))
        }
        return
    }

    val status = statuses[agentId]
    var brainStudioTarget by remember { mutableStateOf<Pair<ChatArtifact, String>?>(null) }
    var brainImageTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val brainFiles = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val installed = status?.installed == true
    val installing = status?.installing == true
    val logs = logsMap[agentId].orEmpty()
    val authState = authMap[agentId]
    val accent = agentAccent(manifest.engineKind)
    val tint = agentTint(manifest.engineKind)
    val selectedProviderId = settings.agentProviders[agentId]
    val selectedProvider = settings.provider(selectedProviderId)
    val selectedModel = settings.agentModels[agentId] ?: ""

    var showAcceleration by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelDraft by remember(selectedModel) { mutableStateOf(selectedModel) }
    var authStatus by remember { mutableStateOf<CliAuthStatus?>(null) }
    var agyKeyDraft by remember { mutableStateOf("") }
    var agyModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var agyAgents by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var agyPlugins by remember { mutableStateOf<List<AgyPluginSummary>>(emptyList()) }
    var agyArtifacts by remember { mutableStateOf<List<AgyArtifactEntry>>(emptyList()) }
    var installPluginDialog by remember { mutableStateOf(false) }
    var pluginBusy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var loadingDetail by remember { mutableStateOf(false) }

    LaunchedEffect(runtime.ready, agentId, refreshTick, authState?.done, settings.hasAntigravityKey, selectedProviderId) {
        if (runtime.ready) {
            loadingDetail = true
            try {
                try { app.conversations.hub.refreshStatuses(agentId) } catch (_: Exception) {}
                authStatus = runCatching { app.conversations.hub.queryAuthStatus(agentId) }.getOrNull() ?: authStatus
                if (agentId == "antigravity") {
                    val fetchedModels = runCatching { app.conversations.hub.fetchAgyCatalog(forceRefresh = refreshTick > 0 || authState?.done == true) }.getOrElse { emptyList() }
                    if (fetchedModels.isNotEmpty()) agyModels = fetchedModels
                    val fetchedAgents = app.conversations.hub.agyAgents()
                    if (fetchedAgents.isNotEmpty()) agyAgents = fetchedAgents
                    agyPlugins = runCatching { app.conversations.hub.listAgyPlugins() }.getOrElse { agyPlugins }
                    agyArtifacts = runCatching { app.conversations.hub.listAgyArtifacts() }.getOrElse { agyArtifacts }
                }
            } finally {
                loadingDetail = false
            }
        } else {
            authStatus = null
            agyModels = emptyList()
            agyAgents = emptyList()
            agyPlugins = emptyList()
            agyArtifacts = emptyList()
            loadingDetail = false
        }
    }

    SubPageScaffold(
        title = manifest.name,
        onBack = onBack,
        snackbar = snackbar,
        loading = loadingDetail,
    ) {
            // 状态与主操作卡
            CrispCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentAppIcon(engine = manifest.engineKind, size = 42.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(manifest.name, style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagPill(manifest.vendor, fg = G.inkMuted, bg = G.surfaceSubtle)
                            TagPill(if (agentId == "antigravity") "Antigravity 原生" else manifest.protocol.badge, fg = accent, bg = tint)
                        }
                    }
                    val builtInReady = manifest.preinstalled && runtime.ready && status?.error == null
                    TagPill(
                        text = when {
                            installing -> "安装中…"
                            status?.error != null -> "启动失败"
                            (installed || builtInReady) && status?.running == true -> "运行中"
                            installed || builtInReady -> "已连接"
                            !runtime.ready && manifest.preinstalled -> "待启动"
                            else -> "未安装"
                        },
                        fg = if (status?.error != null) G.danger else if (installed || builtInReady) G.success else if (installing) G.warning else G.inkMuted,
                        bg = if (status?.error != null) G.dangerTint else if (installed || builtInReady) G.successTint else if (installing) G.warningTint else G.surfaceSubtle,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(manifest.description, style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                status?.version?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("版本：$it", fontFamily = Mono, fontSize = 11.5.sp, color = G.ink)
                }
                status?.binaryPath?.let {
                    Text("路径：$it", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status?.showInstallationProgress == true) {
                    Spacer(Modifier.height(12.dp))
                    AgentInstallProgressView(status)
                }
                (status?.installError ?: status?.error)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (installing) {
                        val cancelling = status.installation?.phase == com.galiais.agent.engine.AgentInstallPhase.CANCELLING
                        SecondaryPillButton(if (cancelling) "取消中…" else "取消安装", enabled = !cancelling && runtime.ready, danger = true, onClick = {
                            app.conversations.launchOperation("取消安装失败") { app.conversations.hub.cancelInstall(agentId) }
                        })
                    } else {
                        SecondaryPillButton("下载与网络", onClick = { showAcceleration = true })
                    }
                    if (installed && status.running) {
                        SecondaryPillButton("停止 Agent", onClick = {
                            scope.launch { app.conversations.hub.stopAgent(agentId); app.conversations.notify("已停止 ${manifest.name}") }
                        })
                    }
                }
            }

            if (logs.isNotEmpty() && (status?.showInstallationProgress == true || !status?.installError.isNullOrBlank())) {
                CrispCard(background = G.codeBg, borderColor = G.codeBorder, padding = PaddingValues(12.dp)) {
                    androidx.compose.runtime.key(installing, status.installError != null) {
                        Collapsible("安装日志 (${logs.size} 行)", initiallyOpen = installing || status.installError != null) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                Text(logs.joinToString("\n"), fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, color = G.codeInk)
                            }
                        }
                    }
                }
            }

            CrispCard { CliUpdateControls(app, agentId) }

            // OAuth / 交互式账号登录与 API Key 凭据
            if (!manifest.launch.authLoginCommand.isNullOrBlank()) {
                SectionLabel("账号登录与认证")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val statusSummary = when {
                            !runtime.ready -> "运行环境未就绪"
                            authStatus?.loggedIn == true -> when (authStatus?.authMethod) {
                                "llm-gateway" -> "已通过本地网关（LLM Gateway）连接"
                                "oauth-personal" -> "Google OAuth 已登录" + (authStatus?.email?.let { " · $it" } ?: "")
                                "gemini-api-key" -> "已使用 Gemini API Key 认证"
                                else -> "已认证（${authStatus?.authMethod}）"
                            }
                            agentId == "antigravity" && settings.hasAntigravityKey -> "已加密保存 Gemini API Key"
                            else -> "未登录（支持 Google OAuth 或 Gemini API Key / 自定义服务商）"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                statusSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (authStatus?.loggedIn == true || (agentId == "antigravity" && settings.hasAntigravityKey)) G.success else G.inkMuted,
                                modifier = Modifier.weight(1f),
                            )
                            if (runtime.ready) {
                                TagPill("刷新", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { refreshTick++ })
                            }
                        }
                        Text(
                            "支持 PTY 交互式 OAuth：点击「开始 OAuth 登录」后在浏览器完成授权，若页面返回授权码，直接粘贴到下方输入框回传给终端。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryPillButton(
                                text = if (authState?.running == true) "等待授权完成…" else "开始 OAuth 登录",
                                enabled = runtime.ready && installed && authState?.running != true,
                                onClick = {
                                    scope.launch {
                                        try { app.conversations.hub.startAuth(manifest, "oauth") }
                                        catch (e: Exception) { app.conversations.notify("启动登录失败：${e.message}") }
                                    }
                                },
                            )
                            if (runtime.ready && authStatus?.loggedIn == true) {
                                SecondaryPillButton("退出登录", danger = true, onClick = {
                                    scope.launch {
                                        runCatching { app.conversations.hub.logoutAuth(agentId) }
                                            .onSuccess { refreshTick++; app.conversations.notify("已退出 ${manifest.name} 登录凭据") }
                                            .onFailure { app.conversations.notify("退出失败：${it.message}") }
                                    }
                                })
                            }
                        }
                        if (authState != null) {
                            CliInteractiveAuthCard(
                                app = app,
                                agentId = agentId,
                                authState = authState,
                                onCancel = { scope.launch { app.conversations.hub.cancelAuth(agentId) } },
                            )
                        }
                        if (agentId == "antigravity" && selectedProvider == null) {
                            Hairline()
                            Text(
                                if (settings.hasAntigravityKey) "Gemini API Key 已加密保存（直连模式生效）" else "或使用 Gemini API Key 直连（免 OAuth）",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (settings.hasAntigravityKey) G.success else G.inkMuted,
                            )
                            OutlinedTextField(
                                value = agyKeyDraft,
                                onValueChange = { agyKeyDraft = it.trim() },
                                label = { Text("GEMINI_API_KEY") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryPillButton(
                                    text = "保存 API Key",
                                    enabled = agyKeyDraft.isNotBlank(),
                                    onClick = {
                                        runCatching {
                                            app.settings.setAntigravityApiKey(agyKeyDraft)
                                            agyKeyDraft = ""
                                            app.conversations.notify("已加密保存 Gemini API Key，新会话生效")
                                        }.onFailure { app.conversations.notify("保存失败：${it.message}") }
                                    },
                                )
                                if (settings.hasAntigravityKey) {
                                    SecondaryPillButton("删除密钥", danger = true, onClick = {
                                        app.settings.setAntigravityApiKey(null)
                                        app.conversations.notify("已删除 Gemini API Key")
                                    })
                                }
                            }
                        }
                    }
                }
            }

            if (agentId == "antigravity") {
                SectionLabel("执行权限与推理强度")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AgentOptionChips(
                            title = "默认推理强度（--effort / thought_level）",
                            options = listOf(null to "默认", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "极高", "max" to "最高"),
                            selected = settings.antigravityEffort,
                            onSelect = {
                                app.settings.setAntigravityEffort(it)
                                app.conversations.notify("Antigravity 默认推理强度已设为：${effortLabel(it)}")
                            },
                        )
                        Hairline()
                        Text("默认权限模式（4 档原生模式 + 完全访问）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        listOf(
                            Triple("default", "默认询问（Default）", "编辑文件与执行命令前逐次确认"),
                            Triple("accept-edits", "自动允许编辑（Accept Edits）", "自动放行工作区文件修改，终端命令仍询问"),
                            Triple("auto", "智能自动（Auto）", "自动放行编辑与安全命令，不可逆操作前确认"),
                            Triple("plan", "计划模式（Plan）", "先制定清晰计划，审阅后再动手实施"),
                            Triple("bypass", "完全访问（Skip Permissions）", "以 --dangerously-skip-permissions 启动，跳过全部确认"),
                        ).forEach { (modeId, title, desc) ->
                            val active = settings.antigravityPermissionMode == modeId
                            Surface(
                                onClick = {
                                    app.settings.setAntigravityPermissionMode(modeId)
                                    app.conversations.notify("Antigravity 默认模式已设为：$title")
                                },
                                shape = D.shape(10.dp),
                                color = if (active) (if (modeId == "bypass") G.warningTint else tint) else G.surfaceSubtle,
                                border = BorderStroke(0.75.dp, if (active) (if (modeId == "bypass") G.warning else accent).copy(alpha = 0.45f) else G.border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    GaliaisRadio(active)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (modeId == "bypass" && active) G.warning else G.ink, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(desc, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                    }
                                }
                            }
                        }
                        Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("终端沙箱隔离（--sandbox）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                Text("限制终端命令的文件与网络访问边界，新建会话生效。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                            GaliaisSwitch(checked = settings.antigravitySandbox, onCheckedChange = {
                                app.settings.setAntigravitySandbox(it)
                                app.conversations.notify(if (it) "已开启 Antigravity 沙箱隔离" else "已关闭 Antigravity 沙箱隔离")
                            })
                        }
                        Hairline()
                        AgentOptionChips(
                            title = "通信协议（ACP / Stream-JSON）",
                            options = listOf(
                                "acp" to "ACP 原生交互（默认，支持实时审批）",
                                "stream-json" to "Stream-JSON 流式直连（精准 Token / --settings）",
                            ),
                            selected = settings.antigravityTransport,
                            onSelect = { mode ->
                                if (mode != null) {
                                    app.settings.setAntigravityTransport(mode)
                                    app.conversations.notify("Antigravity 通信协议已切换为：${if (mode == "acp") "ACP 原生交互" else "Stream-JSON 流式"}（新会话生效）")
                                }
                            },
                        )
                        if (agyAgents.isNotEmpty()) {
                            Hairline()
                            AgentOptionChips(
                                title = "默认子代理角色（--agent）",
                                options = listOf(null to "默认") + agyAgents.map { it.first to it.first },
                                selected = settings.antigravityAgent,
                                onSelect = {
                                    app.settings.setAntigravityAgent(it)
                                    app.conversations.notify("默认子代理已设为：${it ?: "默认"}")
                                },
                            )
                        }
                    }
                }
            }

            if (agentId == "antigravity" && runtime.ready) {
                SectionLabel("Antigravity 插件与 Brain 产物")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("插件（${agyPlugins.size}）", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                            TagPill("安装", fg = G.accent, bg = G.accentContainer, onClick = if (!pluginBusy) ({ installPluginDialog = true }) else null)
                            TagPill("导入", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = if (!pluginBusy) ({
                                pluginBusy = true
                                scope.launch {
                                    runCatching { app.conversations.hub.importAgyPlugins("claude") }
                                        .onSuccess { refreshTick++; app.conversations.notify(it.ifBlank { "已尝试导入 Claude 插件" }) }
                                        .onFailure { app.conversations.notify("导入失败：${it.message}") }
                                    pluginBusy = false
                                }
                            }) else null)
                            TagPill("刷新", fg = G.accent, bg = G.accentContainer, onClick = { refreshTick++ })
                        }
                        if (agyPlugins.isEmpty()) {
                            Text("暂无已安装的 agy 原生插件；资源库的 MCP（~/.gemini/config/mcp_config.json）、Skills 与 GEMINI.md 会自动同步。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        } else {
                            agyPlugins.forEach { plugin ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(plugin.name, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, fontWeight = FontWeight.Medium)
                                        if (plugin.description.isNotBlank()) {
                                            Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    TagPill("卸载", fg = G.danger, bg = G.dangerTint, onClick = if (!pluginBusy) ({
                                        pluginBusy = true
                                        scope.launch {
                                            runCatching { app.conversations.hub.uninstallAgyPlugin(plugin.name) }
                                                .onSuccess { refreshTick++; app.conversations.notify("已卸载插件 ${plugin.name}") }
                                                .onFailure { app.conversations.notify("卸载失败：${it.message}") }
                                            pluginBusy = false
                                        }
                                    }) else null)
                                    GaliaisSwitch(checked = plugin.enabled, onCheckedChange = { enabled ->
                                        scope.launch {
                                            runCatching {
                                                app.conversations.hub.toggleAgyPlugin(plugin.name, enabled)
                                                refreshTick++
                                                app.conversations.notify("${if (enabled) "已启用" else "已停用"}插件 ${plugin.name}")
                                            }.onFailure { app.conversations.notify("切换插件失败：${it.message}") }
                                        }
                                    })
                                }
                            }
                        }
                        if (agyArtifacts.isNotEmpty()) {
                            Hairline()
                            Text("Brain 产物记录（${agyArtifacts.size}，点击预览）", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            agyArtifacts.take(12).forEach { art ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(D.shape(8.dp))
                                        .clickable {
                                            scope.launch {
                                                runCatching { brainFiles.read(art.path) }
                                                    .onSuccess { res ->
                                                        when (res) {
                                                            is FilePreview.Image -> brainImageTarget = art.name to res.bytes
                                                            is FilePreview.Text -> {
                                                                val kind = visualArtifactKindForFile(art.path) ?: ArtifactKind.MARKDOWN
                                                                brainStudioTarget = ChatArtifact(
                                                                    id = "brain:${art.id}",
                                                                    identifier = art.name.substringBeforeLast('.').ifBlank { "brain" },
                                                                    title = art.name,
                                                                    kind = kind,
                                                                    language = art.name.substringAfterLast('.', kind.extension),
                                                                    content = res.content,
                                                                ) to art.path
                                                            }
                                                            is FilePreview.Binary -> app.conversations.notify("二进制产物（${res.bytes} 字节），暂不支持文本预览")
                                                        }
                                                    }
                                                    .onFailure { app.conversations.notify("读取产物失败：${it.message}") }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(art.name, fontFamily = Mono, fontSize = 12.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TagPill(formatBytes(art.size), fg = G.inkMuted, bg = G.surfaceSubtle)
                                    TagPill("预览", fg = G.accent, bg = G.accentContainer)
                                }
                            }
                        }
                    }
                }
            }

            // 服务商透传与默认模型
            if (manifest.providerBridge != null) {
                SectionLabel("服务商与默认模型")
                CrispCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("默认服务商", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            Text(
                                selectedProvider?.let { "${it.name}（${it.displayTypeBadge()}）" } ?: if (agentId == "antigravity") "Google 官方账号 / API Key" else "使用 Agent 自带配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = G.inkMuted,
                            )
                        }
                        Box {
                            SecondaryPillButton("选择", onClick = { providerMenu = true })
                            GaliaisDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                                Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    GaliaisMenuRow(
                                        title = if (agentId == "antigravity") "Google 官方账号 / API Key" else "使用 Agent 自带配置",
                                        selected = settings.agentProviders[agentId] == null,
                                        onClick = { app.settings.setAgentProvider(agentId, null); providerMenu = false },
                                    )
                                    settings.allSelectableProviders().forEach { p ->
                                        GaliaisMenuRow(
                                            title = p.name,
                                            subtitle = p.displayTypeBadge(),
                                            selected = settings.agentProviders[agentId] == p.id,
                                            onClick = { app.settings.setAgentProvider(agentId, p.id); providerMenu = false },
                                        )
                                    }
                                    Hairline(Modifier.padding(vertical = 2.dp))
                                    GaliaisMenuRow(
                                        title = "管理自定义服务商…",
                                        tintOverride = G.accent,
                                        onClick = { providerMenu = false; onOpenProviders() },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (selectedProvider == null && agentId == "antigravity" && agyModels.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("账号已授权模型（${agyModels.size} 个）", style = MaterialTheme.typography.labelMedium, color = G.success, modifier = Modifier.weight(1f))
                            TagPill("刷新列表", fg = G.accent, bg = G.accentContainer, onClick = { refreshTick++ })
                        }
                        Spacer(Modifier.height(6.dp))
                        Box {
                            Surface(
                                onClick = { modelMenu = true },
                                shape = D.shape(10.dp),
                                color = G.surfaceSubtle,
                                border = BorderStroke(0.75.dp, G.border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("快速选择已授权模型", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                        Text(
                                            agyModels.firstOrNull { it.id == modelDraft }?.label ?: modelDraft.ifBlank { "引擎默认（由 Antigravity 自动选择）" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = G.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TagPill("选择", fg = G.accent, bg = G.accentContainer)
                                }
                            }
                            GaliaisDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    GaliaisMenuRow(title = "引擎默认", selected = modelDraft.isBlank(), onClick = {
                                        modelDraft = ""; app.settings.setAgentModel(agentId, null); modelMenu = false
                                    })
                                    agyModels.forEach { opt ->
                                        val effSummary = if (opt.efforts.isNotEmpty()) {
                                            " · 强度: ${opt.efforts.joinToString("/") { effortLabel(it) }}"
                                        } else ""
                                        GaliaisMenuRow(
                                            title = opt.label,
                                            subtitle = "${opt.id}$effSummary",
                                            selected = modelDraft == opt.id,
                                            onClick = {
                                                modelDraft = opt.id
                                                app.settings.setAgentModel(agentId, opt.id)
                                                modelMenu = false
                                                app.conversations.notify("已设为默认模型：${opt.label}")
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it.trim() },
                        label = { Text("默认模型 ID（留空则使用 Agent 默认）") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (selectedProvider != null && selectedProvider.models.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedProvider.models.forEach { m ->
                                SelectableChip(text = m, selected = modelDraft == m, onClick = {
                                    modelDraft = m
                                    app.settings.setAgentModel(agentId, m)
                                    app.conversations.notify("已保存默认模型：$m")
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryPillButton("保存模型", onClick = {
                            app.settings.setAgentModel(agentId, modelDraft.ifBlank { null })
                            app.conversations.notify("已保存 ${manifest.name} 默认模型")
                        })
                    }
                }
            }

            // 能力矩阵与清单查看
            SectionLabel("协议与功能")
            CrispCard {
                Text(manifest.protocol.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val c = manifest.capabilities
                    if (c.streaming) TagPill("流式输出", fg = G.accent, bg = G.accentContainer)
                    if (c.approval) TagPill("操作审批", fg = G.success, bg = G.successTint)
                    if (c.reasoning) TagPill("推理摘要", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.plan) TagPill("任务计划", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.diff) TagPill("文件差异", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.images) TagPill("图片输入", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.modes) TagPill("模式切换", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.slashCommands) TagPill("斜杠指令", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.mcp) TagPill("MCP", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (c.customProvider) TagPill("自定义服务商", fg = G.inkMuted, bg = G.surfaceSubtle)
                }
                Spacer(Modifier.height(10.dp))
                Collapsible("查看 JSON 清单", initiallyOpen = false) {
                    val pretty = remember(manifest) { manifest.toJson().toString(2) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(shape = D.shape(8.dp), color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                            Text(
                                pretty,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = G.codeInk,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
                            )
                        }
                        SecondaryPillButton("复制 JSON 清单", onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("agent.manifest.json", pretty).toClipEntry())
                                app.conversations.notify("已复制 JSON 清单")
                            }
                        })
                    }
                }
            }

    }

    if (installPluginDialog) {
        TextInputDialog(
            title = "安装 Antigravity 插件",
            initial = "",
            onDismiss = { installPluginDialog = false },
        ) { target ->
            val clean = target.trim()
            installPluginDialog = false
            if (clean.isNotEmpty()) {
                pluginBusy = true
                scope.launch {
                    runCatching { app.conversations.hub.installAgyPlugin(clean) }
                        .onSuccess { out ->
                            refreshTick++
                            app.conversations.notify(out.lineSequence().firstOrNull { it.isNotBlank() }?.take(100) ?: "已安装插件 $clean")
                        }
                        .onFailure { app.conversations.notify("安装插件失败：${it.message}") }
                    pluginBusy = false
                }
            }
        }
    }
    if (showAcceleration) {
        DownloadAccelerationSheet(app = app, onDismiss = { showAcceleration = false })
    }
    brainStudioTarget?.let { (art, filePath) ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(art),
            initialArtifactId = art.id,
            cwd = filePath.substringBeforeLast('/', "/home/agent"),
            savePath = filePath,
            onDismiss = { brainStudioTarget = null },
        )
    }
    brainImageTarget?.let { (name, bytes) ->
        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        AlertDialog(
            onDismissRequest = { brainImageTarget = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text(name, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    )
                } else {
                    Text("图片解码失败", color = G.inkMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { brainImageTarget = null }) { Text("关闭", color = G.accent) }
            },
        )
    }
}

