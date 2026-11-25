package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.EnvironmentStatus
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.VmRuntimeService
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.DownloadNetworkConfig
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.ProviderHealthSnapshot
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import com.galiais.agent.engine.arr
import com.galiais.agent.engine.jsonOf
import com.galiais.agent.engine.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** 本地运行环境控制中心：运行模式切换（Root 优先 / 免 Root PRoot）、配置进度、启动/后台策略、存储与重置。 */
@Composable
fun RuntimeSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val env by app.runtime.environment.collectAsState()
    val rootAvailable by app.runtime.rootAvailable.collectAsState()
    val rootAccess by app.runtime.rootAccess.collectAsState()
    val requestingRoot by app.runtime.requestingRoot.collectAsState()
    val conversations by app.conversations.conversations.collectAsState()
    val cliStates by app.conversations.cliUpdates.states.collectAsState()
    val cliRestarting by app.conversations.cliUpdates.restarting.collectAsState()
    val hubStatuses by app.conversations.hub.statuses.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingMode by remember { mutableStateOf<String?>(null) }
    var healthMap by remember { mutableStateOf<Map<String, ProviderHealthSnapshot>>(emptyMap()) }
    var diagnosing by remember { mutableStateOf(false) }
    var diagnosticItems by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    var syncingAll by remember { mutableStateOf(false) }
    var showDownloadSheet by remember { mutableStateOf(false) }

    val activeMode = settings.effectiveRuntimeMode
    val isProot = activeMode == RUNTIME_MODE_PROOT
    val hasRunningTasks = conversations.values.any { it.running }

    LaunchedEffect(activeMode) {
        app.runtime.refreshEnvironment(activeMode)
        app.runtime.probeRoot()
    }
    LaunchedEffect(runtime.ready, settings.providers) {
        if (runtime.ready) {
            healthMap = runCatching { app.conversations.fetchProviderHealth() }.getOrDefault(emptyMap())
            runCatching { app.conversations.cliUpdates.refresh() }
        } else {
            healthMap = emptyMap()
        }
    }

    SubPageScaffold(title = "运行环境", onBack = onBack, snackbar = snackbar) {
        RuntimeCard(
            runtime = runtime.copy(mode = if (runtime.idle) activeMode else runtime.mode),
            onStart = app.runtime::start,
            onStop = app.runtime::stop,
            installed = env.isInstalledFor(activeMode),
            onRestart = {
                if (hasRunningTasks) dialog = "restart" else app.runtime.restart()
            },
        )
        if (runtime.ready && (runtime.reattached || settings.keepRuntimeOnExit)) {
            CrispCard(background = G.successTint, borderColor = G.success.copy(alpha = 0.35f)) {
                Text(
                    if (runtime.reattached) "已重新连接后台引擎。"
                    else if (isProot) "后台运行已开启。可从通知栏返回或停止；系统可能限制后台进程。"
                    else "后台运行已开启。再次打开应用时会尝试重新连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.ink,
                )
            }
        }

        SectionLabel("运行模式")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (rootText, rootColor, rootBg) = when (rootAvailable) {
                        null -> Triple("正在检测 Root…", G.warning, G.warningTint)
                        true -> Triple("Root 环境权限可用", G.success, G.successTint)
                        false -> Triple(rootAccess?.status?.label ?: "Root 启动未确认", G.inkMuted, G.surfaceSubtle)
                    }
                    Row(
                        modifier = Modifier.weight(1f).clip(D.chipShape).background(rootBg).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (rootAvailable == null) PulsingDot(rootColor, size = 6.dp)
                        else Box(Modifier.size(6.dp).background(rootColor, CircleShape))
                        Text(rootText, style = MaterialTheme.typography.labelSmall, color = G.ink, fontWeight = FontWeight.Medium)
                    }
                    TagPill(
                        text = if (requestingRoot) "检查中…" else if (rootAvailable == true) "已授权" else "重新检查",
                        fg = if (rootAvailable == true) G.success else G.accent,
                        bg = if (rootAvailable == true) G.successTint else G.accentContainer,
                        onClick = if (requestingRoot || rootAvailable == true) null else ({
                            scope.launch {
                                val ok = app.runtime.requestRoot()
                                if (ok && activeMode == RUNTIME_MODE_ROOT && runtime.idle) {
                                    app.runtime.start()
                                    app.conversations.notify("Root 权限已就绪，正在自动启动 Root 运行环境…")
                                } else {
                                    app.conversations.notify(if (ok) "Root 环境权限可用" else app.runtime.rootAccess.value?.message ?: "Root 检查未完成")
                                }
                            }
                        }),
                    )
                }
                SegmentedPillBar(
                    options = listOf(
                        RUNTIME_MODE_ROOT to "Root 模式",
                        RUNTIME_MODE_PROOT to "免 Root 模式",
                    ),
                    selected = activeMode,
                    enabled = !runtime.maintenance,
                    onSelect = { next ->
                        if (next == activeMode) return@SegmentedPillBar
                        val targetName = if (next == RUNTIME_MODE_PROOT) "免 Root 模式" else "Root 模式"
                        val targetInstalled = env.isInstalledFor(next)
                        if (hasRunningTasks) {
                            pendingMode = next
                            dialog = "switch-mode"
                        } else {
                            app.runtime.switchMode(next, autoStart = true)
                            app.conversations.notify(
                                if (targetInstalled) "正在切换并自动启动 $targetName…"
                                else "正在切换并自动安装启动 $targetName…",
                            )
                        }
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(
                        text = if (env.rootEnvInstalled) "Root 环境已安装" else "Root 未安装",
                        fg = if (env.rootEnvInstalled) G.success else G.inkSubtle,
                        bg = if (env.rootEnvInstalled) G.successTint else G.surfaceSubtle,
                    )
                    TagPill(
                        text = if (env.prootEnvInstalled) "免 Root 环境已安装" else "免 Root 未安装",
                        fg = if (env.prootEnvInstalled) G.success else G.inkSubtle,
                        bg = if (env.prootEnvInstalled) G.successTint else G.surfaceSubtle,
                    )
                }
                Text(
                    if (isProot)
                        "免 Root 模式使用 PRoot 用户态容器，无需 Root 权限，数据保存在独立工作区（与 Root 环境隔离）。"
                    else
                        "Root 模式使用原生 Linux chroot，I/O 与编译性能接近原生设备，适合大型代码仓库与密集工具调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                )
            }
        }

        SectionLabel("下载与网络")
        CrispCard(padding = PaddingValues(0.dp)) {
            val net = settings.downloadNetwork
            val netSummary = buildString {
                append(if (net.mirrorPreset == "cn_mirror") "国内镜像" else "默认官方源")
                append(" · ")
                append(
                    when (net.proxyMode) {
                        "manual" -> "手动代理"
                        "none" -> "直连无代理"
                        else -> "跟随环境代理"
                    },
                )
                if (net.preferIpv4) append(" · IPv4")
            }
            SettingsNavRow(
                icon = GaliaisIcons.Globe,
                title = "下载镜像与代理",
                subtitle = netSummary,
                iconTint = G.accent,
                iconBg = G.accentContainer,
                onClick = { showDownloadSheet = true },
            )
        }

        SectionLabel("启动与后台运行")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsToggleRow(
                    title = "打开应用时自动启动",
                    subtitle = "仅在运行环境已安装时自动启动。",
                    checked = settings.autoStartRuntime,
                    onCheckedChange = app.settings::setAutoStartRuntime,
                    icon = GaliaisIcons.Cpu,
                )
                Hairline()
                SettingsToggleRow(
                    title = "关闭应用后保持运行",
                    subtitle = if (settings.keepRuntimeOnExit) {
                        if (isProot) "移除最近任务后继续运行；强行停止应用会中断任务。"
                        else "移除最近任务后继续运行，实际行为受系统后台限制。"
                    } else "移除最近任务后停止引擎，运行中的任务将中断。",
                    checked = settings.keepRuntimeOnExit,
                    onCheckedChange = { value ->
                        app.settings.setKeepRuntimeOnExit(value)
                        app.runtime.applyKeepAlive(value)
                    },
                    icon = GaliaisIcons.Layers,
                )
            }
        }

        SectionLabel("运行组件")
        ArchitectureCard(env)

        SectionLabel("存储占用")
        StorageCard(
            env = env,
            canClean = env.cacheBytes > 0 && runtime.state in setOf("stopped", "error", "ready"),
            onRefresh = { app.runtime.refreshEnvironment(activeMode) },
            onClean = { dialog = "cache" },
        )

        SectionLabel("核心通道与 CLI 状态")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                val gw = app.runtime.gatewayEndpoint
                val info = runtime.engineInfo
                val coolingTotal = healthMap.values.sumOf { it.coolingCount }
                val enabledProviders = settings.providers.count { it.enabled }
                val codexVer = info?.optJSONObject("codex")?.str("userAgent")
                val claudeObj = info?.optJSONObject("claude")
                val claudeVer = claudeObj?.let { "${it.optString("cliVersion")} · SDK ${it.optString("sdkVersion")}" }
                val hubErr = info?.optJSONObject("agentHub")?.str("error")
                val installedExtCount = AgentCatalog.external().count { it.preinstalled || hubStatuses[it.id]?.installed == true }

                ChannelStatusRow(
                    title = "协议网关 (Gateway)",
                    ok = runtime.ready && gw != null,
                    warning = coolingTotal > 0,
                    detail = when {
                        !runtime.ready || gw == null -> "未启动"
                        coolingTotal > 0 -> "127.0.0.1:${gw.first} · $enabledProviders/${settings.providers.size} 服务商 · $coolingTotal 密钥冷却中"
                        else -> "127.0.0.1:${gw.first} · $enabledProviders/${settings.providers.size} 服务商 · ${settings.routingGroups.size} 路由组"
                    },
                )
                ChannelStatusRow(
                    title = "Codex CLI",
                    ok = runtime.ready && codexVer != null,
                    detail = codexVer ?: if (runtime.ready) "已连接" else "未连接",
                )
                ChannelStatusRow(
                    title = "Claude Code",
                    ok = runtime.ready && claudeVer != null,
                    detail = claudeVer ?: if (runtime.ready) "已连接" else "未连接",
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ChannelStatusRow(
                            title = "Agent Hub（外部 CLI / 终端 / Git）",
                            ok = runtime.ready && app.runtime.hubAvailable,
                            warning = runtime.ready && hubErr != null,
                            detail = when {
                                !runtime.ready -> "未启动"
                                hubErr != null -> "异常：$hubErr"
                                else -> "已连接 · $installedExtCount 个扩展 Agent 就绪"
                            },
                        )
                    }
                    if (runtime.ready && hubErr != null) {
                        TagPill("重启恢复", fg = G.danger, bg = G.dangerTint, onClick = app.runtime::restart)
                    }
                }

                Hairline()
                val coreClis = listOf("codex" to "Codex", "claude" to "Claude", "antigravity" to "Antigravity")
                val anyChecking = cliStates.values.any { it.working }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    coreClis.forEach { (id, label) ->
                        val st = cliStates[id]
                        val text = when {
                            st == null -> "$label —"
                            st.pendingRestart -> "$label ${st.installedVersion}（待重启）"
                            st.updateAvailable && !st.latestVersion.isNullOrBlank() -> "$label ${st.currentVersion} → ${st.latestVersion}"
                            st.currentVersion.isNotBlank() -> "$label ${st.currentVersion}"
                            else -> label
                        }
                        val (fg, bg) = when {
                            st?.pendingRestart == true -> G.warning to G.warningTint
                            st?.updateAvailable == true -> G.accent to G.accentContainer
                            st?.currentVersion?.isNotBlank() == true -> G.success to G.successTint
                            else -> G.inkMuted to G.surfaceSubtle
                        }
                        TagPill(text, fg = fg, bg = bg)
                    }
                }
                val anyPendingRestart = cliStates.values.any { it.pendingRestart }
                val anyInstalling = cliStates.values.any { it.installing }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPillButton(
                        text = if (anyChecking) "检查更新中…" else "检查 3 大 Agent 更新",
                        enabled = runtime.ready && app.runtime.hubAvailable && !anyChecking,
                        onClick = {
                            app.conversations.cliUpdates.check(null)
                            app.conversations.notify("正在检查 Codex / Claude Code / Antigravity 更新…")
                        },
                    )
                    if (anyPendingRestart) {
                        PrimaryPillButton(
                            text = if (cliRestarting) "重启中…" else "重启生效",
                            enabled = runtime.ready && !anyInstalling && !cliRestarting,
                            onClick = { dialog = "cli-restart" },
                        )
                    }
                }
            }
        }

        SectionLabel("诊断与快捷维护")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("支持一键诊断网关、通道与存储健康状态，或全量同步服务商、扩展与环境变量。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryPillButton(
                        text = if (diagnosing) "自检中…" else "运行环境自检",
                        enabled = !diagnosing && !runtime.maintenance,
                        onClick = {
                            diagnosing = true
                            scope.launch {
                                val checks = mutableListOf<Triple<String, Boolean, String>>()
                                app.runtime.refreshEnvironment(activeMode)
                                val curEnv = app.runtime.environment.value
                                val modeOk = if (isProot) curEnv.prootBinariesReady else (rootAvailable == true)
                                checks += Triple(
                                    if (isProot) "PRoot 运行引擎" else "Root 授权能力",
                                    modeOk,
                                    if (modeOk) "正常可用" else if (isProot) "缺少内置 PRoot 组件" else (rootAccess?.message ?: "未确认 Root 权限"),
                                )
                                checks += Triple(
                                    "环境组件完整度",
                                    curEnv.fullyInstalled,
                                    if (curEnv.fullyInstalled) "Ubuntu · Node · 3 大内置 CLI 完整" else "尚有组件未安装，启动环境将自动补齐",
                                )
                                val spaceOk = curEnv.freeSpaceBytes >= 1_000_000_000L
                                checks += Triple(
                                    "可用存储空间",
                                    spaceOk,
                                    "剩余 ${formatBytes(curEnv.freeSpaceBytes)}" + if (spaceOk) "（充裕）" else "（建议清理缓存或释放至 1 GB 以上）",
                                )
                                if (runtime.ready) {
                                    val gwHealth = runCatching { app.conversations.fetchProviderHealth() }
                                    gwHealth.onSuccess { map ->
                                        healthMap = map
                                        val cool = map.values.sumOf { it.coolingCount }
                                        checks += Triple(
                                            "本地协议网关",
                                            cool == 0,
                                            if (cool == 0) "响应正常 · ${map.size} 个服务商已挂载" else "响应正常，但有 $cool 个密钥冷却中",
                                        )
                                    }.onFailure { err ->
                                        checks += Triple("本地协议网关", false, "通信异常：${err.message}")
                                    }
                                    val hubOk = app.runtime.hubAvailable
                                    checks += Triple(
                                        "Agent Hub 与终端服务",
                                        hubOk,
                                        if (hubOk) "在线" else "通道异常，建议点击上方「重启环境」恢复",
                                    )
                                    val claudeProbe = runCatching {
                                        app.runtime.requestObject(EngineKind.CLAUDE, "session/list", jsonOf("limit" to 1, "offset" to 0))
                                    }
                                    claudeProbe.onSuccess { res ->
                                        val activeCnt = res.arr("activeSessions").length()
                                        checks += Triple(
                                            "Claude Code 会话桥接",
                                            true,
                                            if (activeCnt > 0) "在线 · $activeCnt 个活跃 SDK 会话" else "在线 · 就绪",
                                        )
                                    }.onFailure { err ->
                                        checks += Triple("Claude Code 会话桥接", false, "异常：${err.message}")
                                    }
                                    val codexProbe = runCatching {
                                        app.runtime.requestObject(EngineKind.CODEX, "thread/list", jsonOf("limit" to 1))
                                    }
                                    codexProbe.onSuccess {
                                        checks += Triple("Codex 会话通道", true, "在线 · 就绪")
                                    }.onFailure { err ->
                                        checks += Triple("Codex 会话通道", false, "异常：${err.message}")
                                    }
                                    if (hubOk) {
                                        val agyProbe = runCatching {
                                            app.runtime.hubRequest("agents/status", jsonOf("agentId" to "antigravity"), timeoutMs = 15_000)
                                        }
                                        agyProbe.onSuccess { res ->
                                            val st = res.arr("statuses").optJSONObject(0)
                                            val installed = st?.optBoolean("installed") == true
                                            val errText = st?.str("error")
                                            val ver = st?.str("version")
                                            checks += Triple(
                                                "Antigravity (ACP)",
                                                installed && errText.isNullOrBlank(),
                                                when {
                                                    !errText.isNullOrBlank() -> "异常：$errText"
                                                    installed -> "在线 · ${ver ?: "已就绪"}"
                                                    else -> "未检测到内置 agy 可执行文件"
                                                },
                                            )
                                        }.onFailure { err ->
                                            checks += Triple("Antigravity (ACP)", false, "异常：${err.message}")
                                        }
                                    }
                                } else {
                                    checks += Triple("后台引擎通道", false, "环境未启动，启动后可检测 RPC 与网关连通性")
                                }
                                diagnosticItems = checks
                                diagnosing = false
                            }
                        },
                    )
                    SecondaryPillButton(
                        text = if (syncingAll) "同步中…" else "全量同步配置",
                        enabled = runtime.ready && !syncingAll,
                        onClick = {
                            syncingAll = true
                            scope.launch {
                                try {
                                    app.conversations.syncProviders()
                                    app.conversations.syncAgentsToHub()
                                    runCatching { app.envSsh.syncAllOnReady() }
                                    runCatching { app.extensions.syncNow() }
                                    app.conversations.refresh()
                                    healthMap = runCatching { app.conversations.fetchProviderHealth() }.getOrDefault(healthMap)
                                    app.conversations.notify("已完成服务商、扩展、环境与会话全量同步")
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    app.conversations.notify("同步失败：${e.message}")
                                } finally {
                                    syncingAll = false
                                }
                            }
                        },
                    )
                    SecondaryPillButton(
                        text = "刷新会话列表",
                        enabled = runtime.ready,
                        onClick = {
                            app.conversations.refresh()
                            app.conversations.notify("正在刷新会话列表…")
                        },
                    )
                }
                if (diagnosticItems.isNotEmpty()) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.surfaceSubtle) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val passCount = diagnosticItems.count { it.second }
                                Text(
                                    "自检结果（$passCount/${diagnosticItems.size} 项正常）",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (passCount == diagnosticItems.size) G.success else G.warning,
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill("收起", fg = G.inkMuted, bg = G.surface, onClick = { diagnosticItems = emptyList() })
                            }
                            diagnosticItems.forEach { (title, ok, msg) ->
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.padding(top = 5.dp).size(6.dp).background(if (ok) G.success else G.warning, CircleShape))
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.labelSmall, color = G.ink, fontWeight = FontWeight.Medium)
                                        Text(msg, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("重置与删除")
        CrispCard(borderColor = G.danger.copy(alpha = 0.35f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (env.legacyBackupPresent) {
                    MaintenanceRow(
                        title = "发现旧版项目备份",
                        body = "旧备份未记录运行模式。可恢复到当前模式，已有文件不会被覆盖。",
                        action = "恢复旧备份",
                        enabled = runtime.ready,
                        onClick = { dialog = "restore-legacy" },
                    )
                    Hairline()
                }
                MaintenanceRow(
                    title = "重置工作区与会话",
                    body = "备份当前模式的项目后，清空工作区、会话和登录状态。下次启动恢复项目；备份失败则取消。需先启动环境并结束任务。",
                    action = "重置工作区",
                    enabled = env.chrootInstalled && runtime.ready,
                    onClick = { dialog = "reset" },
                )
                Hairline()
                MaintenanceRow(
                    title = if (isProot) "删除免 Root 环境" else "删除 Root 环境",
                    body = if (isProot)
                        "删除免 Root 环境及其中的项目、会话和登录状态。保留 Root 环境与应用设置。"
                    else
                        "删除 Root 环境及其中的项目、会话和登录状态。保留免 Root 环境与应用设置。",
                    action = "删除当前环境",
                    enabled = (env.chrootInstalled || env.cacheBytes > 0) && !runtime.maintenance,
                    onClick = { dialog = "delete" },
                )
                if (runtime.busy && !runtime.maintenance) {
                    Text("维护操作会先停止引擎。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                }
            }
        }
    }

    when (dialog) {
        "restart" -> ConfirmDialog(
            title = "重启运行环境？",
            body = "当前有会话正在执行任务，重启将中断正在进行的任务并重新拉起全部引擎与本地网关。",
            confirm = "立即重启",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                app.runtime.restart()
            },
        )
        "cli-restart" -> ConfirmDialog(
            title = "重启运行环境以应用 CLI 更新？",
            body = "将重启本地运行环境使新版本生效，正在运行的会话、终端和 SSH 隧道将被中断。",
            confirm = "确认重启",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                app.conversations.cliUpdates.restart()
            },
        )
        "switch-mode" -> pendingMode?.let { target ->
            val targetName = if (target == RUNTIME_MODE_PROOT) "免 Root 模式" else "Root 模式"
            val targetInstalled = env.isInstalledFor(target)
            ConfirmDialog(
                title = "切换为 $targetName？",
                body = "当前有会话正在执行任务，切换将中断任务并自动${if (targetInstalled) "启动" else "安装并启动"} $targetName。两种模式的工作区和会话分别保存。",
                confirm = if (targetInstalled) "切换并启动" else "切换并自动安装",
                onDismiss = { dialog = null; pendingMode = null },
                onConfirm = {
                    dialog = null; pendingMode = null
                    app.runtime.switchMode(target, autoStart = true)
                    app.conversations.notify(
                        if (targetInstalled) "正在切换并自动启动 $targetName…"
                        else "正在切换并自动安装启动 $targetName…",
                    )
                },
            )
        }
        "cache" -> ConfirmDialog(
            title = "清理历史下载缓存？",
            body = "将删除 ${formatBytes(env.cacheBytes)} 下载缓存，保留已安装环境和项目文件。",
            confirm = "清理",
            danger = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch {
                    try {
                        val freed = app.runtime.clearDownloadCache()
                        app.conversations.notify("已清理缓存，释放 ${formatBytes(freed)}")
                    } catch (error: Exception) {
                        app.conversations.notify(error.message ?: "清理缓存失败")
                    }
                }
            },
        )
        "restore-legacy" -> ConfirmDialog(
            title = "恢复到当前 ${if (isProot) "免 Root" else "Root"} 环境？",
            body = "将旧版项目备份恢复到当前模式的工作区，只补充缺失文件，保留已有文件与原始备份。请先结束正在运行的任务。",
            confirm = "恢复备份",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                app.runtime.maintain(VmRuntimeService.OP_RESTORE_LEGACY, deleteCache = false, restart = false)
            },
        )
        "reset" -> MaintenanceDialog(
            title = "重置工作区与会话？",
            body = "确认备份成功后，清空当前模式的工作区、会话记录与登录状态。下次启动恢复项目文件；Ubuntu 和内置 CLI 保留。备份失败会取消重置。",
            keyword = "重置",
            options = listOf("完成后启动环境" to true),
            confirm = "确认重置",
            onDismiss = { dialog = null },
            onConfirm = { values ->
                dialog = null
                app.runtime.maintain(VmRuntimeService.OP_RESET_VM, deleteCache = false, restart = values[0])
                app.conversations.notify("正在重置工作区与会话…")
            },
        )
        "delete" -> MaintenanceDialog(
            title = "删除当前环境？",
            body = if (isProot)
                "将永久删除免 Root 环境中的项目、会话和登录状态。保留 Root 环境及应用设置。重新安装无法恢复已删除的数据。"
            else
                "将永久删除 Root 环境中的项目、会话和登录状态。保留免 Root 环境及应用设置。重新安装无法恢复已删除的数据。",
            keyword = "删除",
            options = listOf("同时清理历史缓存（${formatBytes(env.cacheBytes)}）" to false, "删除后重新安装并启动" to false),
            confirm = "永久删除",
            onDismiss = { dialog = null },
            onConfirm = { values ->
                dialog = null
                app.runtime.maintain(VmRuntimeService.OP_DELETE_ALL, deleteCache = values[0], restart = values[1])
                app.conversations.notify("正在删除本地运行环境…")
            },
        )
    }

    if (showDownloadSheet) {
        DownloadAccelerationSheet(app = app, onDismiss = { showDownloadSheet = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchitectureCard(env: EnvironmentStatus) {
    val isProot = env.mode == RUNTIME_MODE_PROOT
    val archName = if (com.galiais.agent.RuntimeArtifacts.isArm64) "ARM64" else "x86_64"
    CrispCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (isProot)
                    "Ubuntu 24.04 · PRoot · $archName"
                else
                    "Ubuntu 24.04 · chroot · $archName",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isProot) ComponentPill("PRoot", env.prootBinariesReady, env.loading)
                ComponentPill(if (isProot) "Ubuntu PRoot" else "Ubuntu chroot", env.chrootInstalled, env.loading)
                ComponentPill("Node 与运行组件", env.vmProvisioned, env.loading)
                ComponentPill("CA 证书与 Git", env.sshTrusted, env.loading)
                ComponentPill(if (env.daemonSocketPresent) "后台连接入口就绪" else "未检测到后台入口", env.daemonSocketPresent, env.loading)
            }
        }
    }
}

@Composable
private fun ComponentPill(label: String, ok: Boolean, loading: Boolean) {
    val fg = when { ok -> G.success; loading -> G.inkSubtle; else -> G.inkMuted }
    val bg = when { ok -> G.successTint; else -> G.surfaceSubtle }
    Row(
        modifier = Modifier.clip(D.chipShape).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.5.dp).background(fg, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (ok) G.ink else G.inkMuted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StorageCard(env: EnvironmentStatus, canClean: Boolean, onRefresh: () -> Unit, onClean: () -> Unit) {
    val system = (env.chrootBytes - env.vmBytes).coerceAtLeast(0)
    val segments = listOf(
        Triple("系统与会话", system, G.accent),
        Triple("工作区与项目文件", env.vmBytes, G.codex),
        Triple("历史下载缓存", env.cacheBytes, G.warning),
    )
    val total = segments.sumOf { it.second }
    CrispCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("当前环境占用", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    Text(if (env.loading && total == 0L) "统计中…" else formatBytes(total), style = MaterialTheme.typography.headlineSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                }
                Text("设备可用 ${formatBytes(env.freeSpaceBytes)}", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            }
            Row(Modifier.fillMaxWidth().height(10.dp).clip(D.shape(5.dp)).background(G.surfaceSubtle)) {
                if (total > 0) segments.forEach { (_, bytes, color) ->
                    if (bytes > 0) Box(Modifier.weight(bytes.toFloat() / total).height(10.dp).background(color))
                }
            }
            segments.forEach { (label, bytes, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                    Text(formatBytes(bytes), fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryPillButton(if (env.loading) "统计中…" else "刷新统计", onClick = onRefresh, enabled = !env.loading)
                if (env.cacheBytes > 0) SecondaryPillButton("清理下载缓存", onClick = onClean, enabled = canClean)
            }
        }
    }
}

@Composable
private fun MaintenanceRow(title: String, body: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink)
        Text(body, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
        SecondaryPillButton(action, onClick = onClick, enabled = enabled, danger = true)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    GaliaisConfirmDialog(
        title = title,
        message = body,
        confirmText = confirm,
        danger = danger,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MaintenanceDialog(
    title: String,
    body: String,
    keyword: String,
    options: List<Pair<String, Boolean>>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Boolean>) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var values by remember { mutableStateOf(options.map { it.second }) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        shape = D.dialogShape,
        title = { Text(title, color = G.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                options.forEachIndexed { index, (label, _) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(D.shape(8.dp))
                            .toggleable(value = values[index], role = Role.Checkbox) { checked ->
                                values = values.toMutableList().also { it[index] = checked }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GaliaisCheckbox(values[index])
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = G.ink)
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(20) },
                    singleLine = true,
                    label = { Text("输入「$keyword」以确认") },
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(values) }, enabled = typed.trim() == keyword) {
                Text(confirm, color = if (typed.trim() == keyword) G.danger else G.inkSubtle, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) } },
    )
}

@Composable
private fun ChannelStatusRow(title: String, ok: Boolean, detail: String, warning: Boolean = false) {
    val dot = when {
        warning -> G.warning
        ok -> G.success
        else -> G.inkSubtle
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = G.ink, fontWeight = FontWeight.Medium)
            Text(detail, fontFamily = Mono, fontSize = 11.5.sp, color = if (warning) G.warning else G.inkMuted)
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return if (index == 0) "$bytes B" else String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

/** Agent 管理页与运行环境页共用的下载网络设置抽屉（无二次套壳卡片）。 */
@Composable
fun DownloadAccelerationSheet(app: GaliaisApplication, onDismiss: () -> Unit) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("下载与网络", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    Text("用于 Agent 安装、扩展依赖下载与仓库导入", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                }
                SecondaryPillButton("关闭", onClick = onDismiss)
            }
            DownloadAccelerationCard(app = app, wrapInCard = false)
        }
    }
}

/** 镜像源、本机/系统代理、IPv4 优先与连通性测速卡片（运行环境页与 Agent 页共用）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadAccelerationCard(app: GaliaisApplication, wrapInCard: Boolean = true) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val saved = settings.downloadNetwork
    val scope = rememberCoroutineScope()
    val systemProxy by app.conversations.hub.systemProxy.collectAsState()

    var mirrorPreset by remember { mutableStateOf(saved.mirrorPreset) }
    var proxyMode by remember { mutableStateOf(saved.proxyMode) }
    var manualProxy by remember { mutableStateOf(saved.manualProxyUrl) }
    var preferIpv4 by remember { mutableStateOf(saved.preferIpv4) }
    var customNpm by remember { mutableStateOf(saved.customNpmRegistry) }
    var ghMirror by remember { mutableStateOf(saved.githubMirrorPrefix) }
    var applying by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Triple<Boolean, String, String>?>(null) }

    LaunchedEffect(saved) {
        mirrorPreset = saved.mirrorPreset
        proxyMode = saved.proxyMode
        manualProxy = saved.manualProxyUrl
        preferIpv4 = saved.preferIpv4
        customNpm = saved.customNpmRegistry
        ghMirror = saved.githubMirrorPrefix
    }

    fun currentDraft() = DownloadNetworkConfig(
        mirrorPreset = mirrorPreset,
        customNpmRegistry = customNpm.trim(),
        githubMirrorPrefix = ghMirror.trim(),
        proxyMode = proxyMode,
        manualProxyUrl = manualProxy.trim(),
        preferIpv4 = preferIpv4,
    )

    fun saveAndApply(draft: DownloadNetworkConfig) {
        val validated = try { draft.validated() } catch (e: IllegalArgumentException) {
            configError = e.message
            return
        }
        applying = true
        configError = null
        scope.launch {
            try {
                app.conversations.hub.saveDownloadNetwork(validated)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                configError = e.message ?: "配置应用失败，请重试"
            } finally {
                applying = false
            }
        }
    }

    val hasChanges = currentDraft() != saved
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("下载来源", style = MaterialTheme.typography.titleSmall, color = G.ink)
                SegmentedPillBar(
                    options = listOf("official" to "默认来源", "cn_mirror" to "国内镜像"),
                    selected = mirrorPreset,
                    onSelect = {
                        mirrorPreset = it
                    },
                )
                Text(
                    if (mirrorPreset == "cn_mirror")
                        "npm 使用阿里云镜像，Python 使用清华镜像。"
                    else
                        "沿用工具原有来源，通过当前网络下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                )
            }

            Hairline()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("下载代理", style = MaterialTheme.typography.titleSmall, color = G.ink)
                SegmentedPillBar(
                    options = listOf("system" to "跟随环境", "manual" to "手动代理", "none" to "不使用代理"),
                    selected = proxyMode,
                    onSelect = {
                        proxyMode = it
                    },
                )
                when (proxyMode) {
                    "system" -> Text(
                        if (systemProxy != null) "使用系统代理：$systemProxy"
                        else "沿用当前网络及运行环境已有的代理设置。VPN 是否生效取决于其应用规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (systemProxy != null) G.success else G.inkMuted,
                    )
                    "manual" -> {
                        OutlinedTextField(
                            value = manualProxy,
                            onValueChange = { manualProxy = it },
                            label = { Text("代理地址（HTTP / SOCKS5）") },
                            placeholder = { Text("http://127.0.0.1:7890") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Clash 7890" to "http://127.0.0.1:7890",
                                "v2ray 10809" to "http://127.0.0.1:10809",
                                "SOCKS5 10808" to "socks5h://127.0.0.1:10808",
                            ).forEach { (label, url) ->
                                SelectableChip(
                                    text = label,
                                    selected = manualProxy.trim() == url,
                                    onClick = { manualProxy = url },
                                )
                            }
                        }
                    }
                    else -> Text(
                        "禁用下载进程的代理设置，仍受系统 VPN 路由影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }
            }

            Hairline()

            SettingsToggleRow(
                title = "IPv4 兼容模式",
                subtitle = "默认关闭。连接异常时可启用，部分下载工具将仅使用 IPv4。",
                checked = preferIpv4,
                onCheckedChange = {
                    preferIpv4 = it
                },
                icon = GaliaisIcons.Globe,
            )

            Collapsible("高级镜像配置（可选）", initiallyOpen = customNpm.isNotBlank() || ghMirror.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customNpm,
                        onValueChange = { customNpm = it },
                        label = { Text("自定义 npm Registry（留空按上方预设）") },
                        placeholder = { Text("https://registry.npmjs.org") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ghMirror,
                        onValueChange = { ghMirror = it },
                        label = { Text("GitHub 仓库镜像前缀（可选）") },
                        placeholder = { Text("例如 https://gh-proxy.com/") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("仅用于技能与插件仓库导入。安装脚本通过上方网络与代理下载。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            }

            testResult?.let { (ok, npmSummary, ghSummary) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(D.shape(10.dp))
                        .background(if (ok) G.successTint else G.dangerTint)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("npm 源：$npmSummary", fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                    Text("GitHub：$ghSummary", fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                }
            }

            configError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryPillButton(
                    text = if (applying) "应用中…" else "保存并应用",
                    enabled = hasChanges && !applying && !testing,
                    onClick = {
                        val draft = currentDraft()
                        saveAndApply(draft)
                    },
                )
                SecondaryPillButton(
                    text = if (testing) "检查中…" else "测试连通性",
                    enabled = runtime.ready && !testing && !applying,
                    onClick = {
                        val draft = try { currentDraft().validated() } catch (e: IllegalArgumentException) {
                            configError = e.message
                            return@SecondaryPillButton
                        }
                        configError = null
                        testing = true
                        scope.launch {
                            try {
                                val res = app.conversations.hub.testDownloadNetwork(draft)
                                val npmObj = res.optJSONObject("npm")
                                val ghObj = res.optJSONObject("github")
                                val npmMsg = if (npmObj?.optBoolean("ok") == true) "连通 (${npmObj.optInt("ms")}ms)"
                                    else "失败：${npmObj?.optString("error").orEmpty().ifBlank { "超时" }}"
                                val ghMsg = if (ghObj?.optBoolean("ok") == true) "连通 (${ghObj.optInt("ms")}ms)"
                                    else "失败：${ghObj?.optString("error").orEmpty().ifBlank { "超时" }}"
                                testResult = Triple(res.optBoolean("ok"), npmMsg, ghMsg)
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                testResult = Triple(false, e.message ?: "连接检查失败", "-")
                            } finally {
                                testing = false
                            }
                        }
                    },
                )
                SecondaryPillButton("恢复默认", enabled = !applying && !testing, onClick = {
                    val defaults = DownloadNetworkConfig()
                    mirrorPreset = defaults.mirrorPreset
                    proxyMode = defaults.proxyMode
                    manualProxy = defaults.manualProxyUrl
                    preferIpv4 = defaults.preferIpv4
                    customNpm = defaults.customNpmRegistry
                    ghMirror = defaults.githubMirrorPrefix
                    testResult = null
                    saveAndApply(defaults)
                })
            }
        }
    }
    if (wrapInCard) CrispCard { body() } else body()
}
