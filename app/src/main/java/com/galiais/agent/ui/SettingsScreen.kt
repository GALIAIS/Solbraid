package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galiais.agent.BuildConfig
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.providerPresets

/** 设置中心：按功能域分组的清晰分类导航页，点击各项进入独立子页面。 */
@Composable
fun SettingsScreen(app: GaliaisApplication, modifier: Modifier, onNavigate: (String) -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val hubStatuses by app.conversations.hub.statuses.collectAsState()
    val extRegistry by app.extensions.registry.collectAsState()
    val tickets by app.superAgent.tickets.collectAsState()
    val envConfig by app.envSsh.envConfig.collectAsState()
    val sshHosts by app.envSsh.sshHosts.collectAsState()
    val activeTunnels by app.envSsh.activeTunnels.collectAsState()
    val mobileStatus by app.mobileUse.status.collectAsState()
    androidx.compose.runtime.LaunchedEffect(runtime.ready, runtime.mode) { app.mobileUse.refreshStatus() }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, com.galiais.agent.engine.ChatArtifact>?>(null) }

    val installedAgents = AgentCatalog.all().filter { it.preinstalled || hubStatuses[it.id]?.installed == true }
    val agentsSummary = buildString {
        append("已安装 ${installedAgents.size} 个 · ")
        append(installedAgents.take(3).joinToString("、") { it.name })
    }

    val providersSummary = if (settings.providers.isEmpty() && settings.routingGroups.isEmpty()) {
        "内置 ${providerPresets.size} 个官方/套餐/中转预设 · 多 Key 池 · 路由组"
    } else {
        val enabledCnt = settings.providers.count { it.enabled }
        val names = settings.providers.filter { it.enabled }.take(3).joinToString("、") { it.name }
        val grp = if (settings.routingGroups.isNotEmpty()) " · ${settings.routingGroups.size} 路由组" else ""
        "已启用 $enabledCnt/${settings.providers.size}$grp" + if (names.isNotBlank()) " · $names" else ""
    }

    val workspaceSummary = buildString {
        append(if (settings.delegationEnabled) "跨 Agent 委派已开启" else "跨 Agent 委派已关闭")
        append(" · ")
        append(settings.defaultCwd)
    }

    val modeTag = if (settings.effectiveRuntimeMode == RUNTIME_MODE_PROOT) "免 Root" else "Root"
    val hubDegraded = runtime.ready && !app.runtime.hubAvailable
    val runtimeSummary = when {
        hubDegraded -> "$modeTag · 运行中 · Agent Hub 异常（点此重启恢复）"
        runtime.ready -> if (settings.keepRuntimeOnExit) "$modeTag · 运行中 · 后台运行已开启" else "$modeTag · 运行中 · 后台运行已关闭"
        runtime.busy -> "$modeTag · ${runtime.message}"
        runtime.state == "error" -> "$modeTag · 运行异常 · 查看详情"
        else -> if (settings.autoStartRuntime) "$modeTag · 模式、后台运行、存储与重置" else "$modeTag · 未启动 · 自动启动已关闭"
    }

    Column(modifier.fillMaxSize().galiaisCanvasBackground()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = G.ink)
        }
        HeaderProgressSlot(runtime.busy)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            // 顶部紧凑运行环境状态卡（快捷启停，点整张卡进入运行环境详情页，去除下方重复入口）
            CrispCard(
                onClick = { onNavigate("settings/runtime") },
                padding = PaddingValues(D.space(14.dp)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "本地运行环境",
                                style = MaterialTheme.typography.titleSmall,
                                color = G.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            RuntimeBadge(runtime)
                        }
                        Text(
                            runtimeSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (runtime.state == "error") G.danger else G.inkMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (runtime.busy || runtime.ready) {
                        if (hubDegraded) {
                            PrimaryPillButton("重启", onClick = app.runtime::restart)
                        } else {
                            SecondaryPillButton("停止", onClick = app.runtime::stop, enabled = runtime.state != "stopping" && !runtime.maintenance)
                        }
                    } else {
                        PrimaryPillButton(
                            text = if (runtime.state == "error") "重试" else "启动",
                            onClick = app.runtime::start,
                            enabled = runtime.bound,
                        )
                    }
                    Icon(GaliaisIcons.ChevronRight, contentDescription = "查看运行环境详情", tint = G.inkSubtle, modifier = Modifier.size(18.dp))
                }
                if (runtime.busy) {
                    Spacer(Modifier.height(10.dp))
                    GaliaisProgressBar(thin = false, progress = runtime.progress)
                }
            }

            SectionLabel("常规与个性化")
            CrispCard(padding = PaddingValues(0.dp)) {
                val generalSummary = buildString {
                    append(if (settings.plainTextEditor) "纯文本编辑器（不渲染输入）" else "自动渲染编辑器")
                    if (!settings.plainTextEditor && settings.editorAutoPreview) append(" · 实时预览")
                    if (!settings.assistantRichRender) append(" · 助手纯文本")
                    else if (!settings.inlineVisualArtifacts) append(" · 轻量卡片")
                }
                SettingsNavRow(
                    icon = GaliaisIcons.Gear,
                    title = "常规",
                    subtitle = generalSummary,
                    iconTint = G.accent,
                    iconBg = G.accentContainer,
                    onClick = { onNavigate("settings/general") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Palette,
                    title = "主题与外观",
                    subtitle = themeSummary(settings.theme),
                    iconTint = G.artifact,
                    iconBg = G.artifactTint,
                    onClick = { onNavigate("settings/theme") },
                )
            }

            SectionLabel("模型与 Agent")
            CrispCard(padding = PaddingValues(0.dp)) {
                SettingsNavRow(
                    icon = GaliaisIcons.Sparkles,
                    title = "Agent 管理",
                    subtitle = agentsSummary,
                    badge = "${installedAgents.size}",
                    iconTint = G.accent,
                    iconBg = G.accentContainer,
                    onClick = { onNavigate("settings/agents") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Server,
                    title = "服务商",
                    subtitle = providersSummary,
                    badge = if (settings.providers.isNotEmpty()) "${settings.providers.size}" else null,
                    iconTint = G.codex,
                    iconBg = G.codexTint,
                    onClick = { onNavigate("settings/providers") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Activity,
                    title = "用量中心",
                    subtitle = "概览 · 逐请求 · 会话分析 · 上下文监控 · 预估费用",
                    iconTint = G.success,
                    iconBg = G.successTint,
                    onClick = { onNavigate("settings/usage") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                val extCount = extRegistry.totalCount
                SettingsNavRow(
                    icon = GaliaisIcons.Code,
                    title = "资源库",
                    subtitle = "指令 ${extRegistry.instructions.size} · MCP ${extRegistry.mcpServers.size} · 技能 ${extRegistry.skills.size} · 子代理 ${extRegistry.subagents.size} · 插件 ${extRegistry.plugins.size}",
                    badge = if (extCount > 0) "$extCount" else null,
                    iconTint = G.artifact,
                    iconBg = G.artifactTint,
                    onClick = { onNavigate("settings/extensions") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                val activeTicketCount = tickets.count { it.active }
                SettingsNavRow(
                    icon = GaliaisIcons.GitBranch,
                    title = "Super-Agent 编排",
                    subtitle = "Worktree 并行 · 并发 ${settings.maxParallelWorkers} 槽 · 按角色分配引擎模型",
                    badge = if (activeTicketCount > 0) "$activeTicketCount" else null,
                    iconTint = G.inkMuted,
                    iconBg = G.surfaceSubtle,
                    onClick = { onNavigate("settings/superagent") },
                )
            }

            SectionLabel("协作与环境")
            CrispCard(padding = PaddingValues(0.dp)) {
                val enabledEnvCount = envConfig.vars.count { it.enabled }
                SettingsNavRow(
                    icon = GaliaisIcons.Wrench,
                    title = "环境中心",
                    subtitle = "变量 $enabledEnvCount · PATH +${envConfig.extraPaths.size} · 别名 ${envConfig.aliases.size} · Git · .env",
                    badge = if (enabledEnvCount > 0) "$enabledEnvCount" else null,
                    iconTint = G.accent,
                    iconBg = G.accentContainer,
                    onClick = { onNavigate("settings/env") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Terminal,
                    title = "SSH 工作台",
                    subtitle = "已保存 ${sshHosts.size} 台主机" + if (activeTunnels.isNotEmpty()) " · ${activeTunnels.size} 条隧道运行中" else " · 终端 · 隧道 · SFTP · 多密钥",
                    badge = if (sshHosts.isNotEmpty()) "${sshHosts.size}" else null,
                    iconTint = G.codex,
                    iconBg = G.codexTint,
                    onClick = { onNavigate("tab/ssh") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                SettingsNavRow(
                    icon = GaliaisIcons.Layers,
                    title = "工作区与会话",
                    subtitle = workspaceSummary,
                    iconTint = G.inkMuted,
                    iconBg = G.surfaceSubtle,
                    onClick = { onNavigate("settings/workspace") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                val mu = settings.mobileUse
                val mobileSummary = when {
                    mobileStatus.emergencyStopped -> "已紧急停止 · 点此解除熔断"
                    !mu.enabled -> "已关闭 · 支持 Root / Shizuku / 无障碍三级手机操控"
                    mobileStatus.activeBackend.id == "root" -> "已就绪 · Root 完全控机 · 14 项能力（GUI/硬件/通知/文件/宿主）"
                    mobileStatus.activeBackend.id == "shizuku" -> "已就绪 · Shizuku 免 Root 进阶 · GUI/硬件/通知/包管理"
                    mobileStatus.activeBackend.id == "accessibility" -> "已就绪 · 免 Root 无障碍 · 截屏 / UI 树 / 点击 / 通知 / Intent"
                    else -> "待授权 · 开启 Root、Shizuku 或无障碍服务即可启用"
                }
                SettingsNavRow(
                    icon = GaliaisIcons.Smartphone,
                    title = "手机操控（Mobile-Use）",
                    subtitle = mobileSummary,
                    badge = when {
                        mobileStatus.emergencyStopped -> "急停"
                        mu.enabled && mobileStatus.activeBackend.id != "none" -> when (mobileStatus.activeBackend.id) {
                            "root" -> "Root"
                            "shizuku" -> "Shizuku"
                            else -> "无障碍"
                        }
                        else -> null
                    },
                    iconTint = if (mobileStatus.emergencyStopped) G.danger else if (mu.enabled) G.accent else G.inkMuted,
                    iconBg = if (mobileStatus.emergencyStopped) G.dangerTint else if (mu.enabled) G.accentContainer else G.surfaceSubtle,
                    onClick = { onNavigate("settings/mobileuse") },
                )
                Hairline(Modifier.padding(start = 66.dp))
                val sb = settings.selfBuild
                val selfBuildSummary = if (!sb.enabled) {
                    "已关闭（默认） · 手机端纯本地 ARM64 编译与看门狗自更新"
                } else {
                    buildString {
                        append("已启用 · ${sb.targetAbis} · ${sb.variant}")
                        append(if (sb.allowAgentBuild) " · 允许 Agent 编译" else " · 仅手动触发")
                    }
                }
                SettingsNavRow(
                    icon = GaliaisIcons.Cpu,
                    title = "本机 APK 自迭代",
                    subtitle = selfBuildSummary,
                    badge = if (sb.enabled) "已开" else null,
                    iconTint = if (sb.enabled) G.accent else G.inkMuted,
                    iconBg = if (sb.enabled) G.accentContainer else G.surfaceSubtle,
                    onClick = { onNavigate("settings/selfbuild") },
                )
            }

            PluginSlotBannerColumn(
                app = app,
                slotName = "settings.section",
                cwd = settings.defaultCwd,
                onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
            )

            SectionLabel("其他")
            CrispCard(padding = PaddingValues(0.dp)) {
                SettingsNavRow(
                    icon = GaliaisIcons.Info,
                    title = "关于 Solbraid",
                    subtitle = "版本 ${BuildConfig.VERSION_NAME} · 用户协议与开放源代码许可",
                    iconTint = G.inkMuted,
                    iconBg = G.surfaceSubtle,
                    onClick = { onNavigate("settings/about") },
                )
            }

            Spacer(Modifier.height(20.dp + LocalFloatingNavBottomInset.current))
        }
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
