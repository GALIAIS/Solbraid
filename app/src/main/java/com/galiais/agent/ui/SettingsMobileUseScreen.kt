package com.galiais.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_ACCESSIBILITY
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_AUTO
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_ROOT
import com.galiais.agent.engine.mobile.MOBILE_BACKEND_SHIZUKU
import com.galiais.agent.engine.mobile.MOBILE_SAFETY_BALANCED
import com.galiais.agent.engine.mobile.MOBILE_SAFETY_STRICT
import com.galiais.agent.engine.mobile.MOBILE_SAFETY_UNRESTRICTED
import com.galiais.agent.engine.mobile.MobileBackendKind
import com.galiais.agent.engine.mobile.MobileUseConfig
import com.galiais.agent.engine.mobile.SolbraidAccessibilityService
import com.galiais.agent.engine.mobile.SolbraidNotificationListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Mobile-Use 双模式 AI 手机操控配置与现场自检中心（支持 Root/chroot 与 免 Root PRoot/无障碍双引擎）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileUseSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by app.settings.state.collectAsState()
    val status by app.mobileUse.status.collectAsState()
    val rootAvail by app.runtime.rootAvailable.collectAsState()
    val a11yConn by SolbraidAccessibilityService.connected.collectAsState()
    val notifConn by SolbraidNotificationListenerService.connected.collectAsState()
    val shizukuSt by app.mobileUse.shizuku.status.collectAsState()
    val scope = rememberCoroutineScope()

    val cfg = settings.mobileUse
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var testSummary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cfg, rootAvail, a11yConn, notifConn, shizukuSt, settings.effectiveRuntimeMode) {
        if (rootAvail == null) app.runtime.probeRoot()
        app.mobileUse.refreshStatus()
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                app.mobileUse.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun updateCfg(next: MobileUseConfig, toast: String? = null) {
        runCatching {
            app.settings.setMobileUseConfig(next.validated())
            app.mobileUse.refreshStatus()
            if (!toast.isNullOrBlank()) app.conversations.notify(toast)
        }.onFailure {
            app.conversations.notify("保存失败：${it.message}")
        }
    }

    SubPageScaffold(title = "手机操控（Mobile-Use）", onBack = onBack, snackbar = snackbar, loading = busyLabel != null) {
        // 1. 急停状态卡（熔断时优先置顶）
        if (status.emergencyStopped) {
            CrispCard(
                background = G.dangerTint,
                borderColor = G.danger.copy(alpha = 0.55f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 手机操控已紧急熔断", style = MaterialTheme.typography.titleSmall, color = G.danger, fontWeight = FontWeight.SemiBold)
                    Text(
                        "所有 Agent 的截屏、点击、滑动与文本输入请求当前都会被拦截，确认安全后可随时恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.ink,
                    )
                    PrimaryPillButton("解除急停并恢复操控", onClick = {
                        app.mobileUse.resumeFromEmergencyStop()
                        app.conversations.notify("已恢复 AI 手机操控")
                    })
                }
            }
        }

        // 2. 总开关与当前生效双后端卡片
        val ready = cfg.enabled && !status.emergencyStopped && status.activeBackend != MobileBackendKind.NONE
        val modeLabel = if (settings.effectiveRuntimeMode == RUNTIME_MODE_PROOT) "免 Root（PRoot）" else "Root（chroot）"
        CrispCard(
            background = if (ready) G.accentContainer.copy(alpha = 0.22f) else G.surface,
            borderColor = if (ready) G.accent.copy(alpha = 0.45f) else G.border,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(
                        text = if (ready) "已就绪 · ${status.activeBackend.label}" else if (!cfg.enabled) "已关闭" else "待授权驱动",
                        fg = if (ready) G.success else G.warning,
                        bg = if (ready) G.successTint else G.warningTint,
                    )
                    TagPill(text = "运行模式：$modeLabel", fg = G.accent, bg = G.accentContainer)
                }
                SettingsToggleRow(
                    title = "允许 AI 操控本机屏幕与应用",
                    subtitle = "开启后，Codex、Claude Code、Antigravity 等全部 Agent 均可在 Root chroot 与免 Root PRoot 双模式下统一调用截屏、UI 树、点击、滑动、中文输入、应用与剪贴板能力。",
                    checked = cfg.enabled,
                    onCheckedChange = { enabled ->
                        updateCfg(cfg.copy(enabled = enabled), if (enabled) "已开启 Mobile-Use 手机操控" else "已关闭 Mobile-Use 手机操控")
                    },
                    icon = GaliaisIcons.Smartphone,
                )
            }
        }

        // 3. 三级后端与扩展权限状态
        SectionLabel("三级驱动与扩展能力状态（Root / Shizuku / 无障碍）")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val items = listOf(
                    Triple(
                        "Root 原生驱动（chroot 完全控机）",
                        status.rootAvailable,
                        if (status.rootAvailable) "已就绪 · 常驻 RootFsDaemon，全 GUI/硬件开关/包管理/宿主 Shell 与 Logcat" else "未检测到 Root 权限（免 Root 环境将自动使用 Shizuku 或无障碍）",
                    ),
                    Triple(
                        "Shizuku 进阶驱动（免 Root 无线调试 uid=2000）",
                        status.shizukuAlive && status.shizukuGranted,
                        when {
                            status.shizukuAlive && status.shizukuGranted -> "已授权连接（uid=${status.shizukuUid}）· PRoot 免 Root 亦可解锁静默装卸、强杀、全按键与系统设置"
                            status.shizukuAlive -> "Shizuku 已运行但尚未授权 · 点下方「授权 Shizuku」立即解锁"
                            status.shizukuInstalled -> "已安装 Shizuku 但服务未启动 · 打开 Shizuku 通过无线调试激活即可"
                            else -> "可选 · 未安装 Shizuku（免 Root 手机接入后可获得近 Root 级控机能力）"
                        },
                    ),
                    Triple(
                        "Solbraid 无障碍服务（PRoot 免 Root 基础核心）",
                        status.accessibilityConnected,
                        when {
                            status.accessibilityConnected -> "已连接 · Android 11+ 免 Root 截屏、多窗口 UI 树、双指缩放/拖拽与原生中文输入"
                            status.accessibilitySettingEnabled -> "系统已启用但服务正在重连，请稍候或重新开关一次"
                            else -> "未开启 · 前往系统无障碍开启「Solbraid AI 手机操控」即可免 Root 完整使用"
                        },
                    ),
                    Triple(
                        "通知监听与内联回复（双模式通用）",
                        status.notificationListenerConnected,
                        when {
                            status.notificationListenerConnected -> "已连接 · 支持读取全部通知、提取验证码、点击按钮与免切屏回复"
                            status.rootAvailable || (status.shizukuAlive && status.shizukuGranted) -> "未开启 · 可点下方「授权通知监听」或前往系统通知使用权开启"
                            else -> "未开启 · 前往系统设置授予「Solbraid 通知读取与回复」即可启用"
                        },
                    ),
                    Triple(
                        "急停悬浮胶囊与系统设置写入",
                        status.accessibilityConnected || status.overlayPermissionGranted,
                        buildString {
                            append(
                                when {
                                    status.accessibilityConnected -> "胶囊就绪（无障碍专属浮层）"
                                    status.overlayPermissionGranted -> "胶囊就绪（悬浮窗权限）"
                                    else -> "通知栏急停常驻"
                                },
                            )
                            append(" · 修改系统设置：")
                            append(if (status.writeSettingsGranted || status.rootAvailable) "已就绪" else "未授权（可选，免 Root 调亮度/旋转）")
                        },
                    ),
                )
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.surfaceSubtle) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items.forEach { (title, ok, detail) ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.padding(top = 5.dp).size(7.dp).background(if (ok) G.success else G.warning, CircleShape))
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.labelMedium, color = G.ink, fontWeight = FontWeight.Medium)
                                    Text(detail, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryPillButton(
                        text = if (status.accessibilityConnected) "管理无障碍服务" else "开启免 Root 无障碍",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { app.conversations.notify("无法打开无障碍设置：${it.message}") }
                        },
                    )
                    if (status.shizukuAlive && !status.shizukuGranted) {
                        PrimaryPillButton(
                            text = "授权 Shizuku",
                            onClick = {
                                if (!app.mobileUse.requestShizukuPermission()) {
                                    app.conversations.notify("无法发起 Shizuku 授权，请确认 Shizuku 版本 >= v11")
                                }
                            },
                        )
                    }
                    if (!status.notificationListenerConnected) {
                        SecondaryPillButton(
                            text = if (status.rootAvailable || (status.shizukuAlive && status.shizukuGranted)) "授权通知监听" else "开启通知使用权",
                            onClick = {
                                if (status.rootAvailable || (status.shizukuAlive && status.shizukuGranted)) {
                                    scope.launch {
                                        runCatching {
                                            app.mobileUse.execute("notification", JSONObject().put("operation", "auto_grant"))
                                            app.conversations.notify("已自动开启通知监听服务")
                                        }.onFailure { app.conversations.notify("自动授权失败：${it.message}") }
                                    }
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { app.conversations.notify("无法打开通知使用权设置：${it.message}") }
                                }
                            },
                        )
                    }
                    if (!status.writeSettingsGranted && !status.rootAvailable) {
                        SecondaryPillButton(
                            text = "系统设置写入（可选）",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { app.conversations.notify("无法打开系统设置写入页：${it.message}") }
                            },
                        )
                    }
                    if (!status.overlayPermissionGranted && !status.accessibilityConnected) {
                        SecondaryPillButton(
                            text = "授予悬浮窗（可选）",
                            onClick = {
                                runCatching {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }.onFailure { app.conversations.notify("无法打开悬浮窗设置：${it.message}") }
                            },
                        )
                    }
                    SecondaryPillButton("刷新状态", onClick = {
                        app.runtime.probeRoot(force = true)
                        app.mobileUse.refreshStatus()
                    })
                }
            }
        }

        // 4. 驱动路由与安全护栏策略
        SectionLabel("驱动路由与安全护栏")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("执行驱动偏好", style = MaterialTheme.typography.labelMedium, color = G.ink)
                SegmentedPillBar(
                    options = listOf(
                        MOBILE_BACKEND_AUTO to "自动路由",
                        MOBILE_BACKEND_ROOT to "Root",
                        MOBILE_BACKEND_SHIZUKU to "Shizuku",
                        MOBILE_BACKEND_ACCESSIBILITY to "无障碍",
                    ),
                    selected = cfg.preferredBackend,
                    onSelect = { updateCfg(cfg.copy(preferredBackend = it)) },
                )
                Text(
                    "自动路由：chroot 模式按 Root → Shizuku → 无障碍优先；PRoot 免 Root 模式按 Shizuku → 无障碍 → Root 优先。",
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                )
                Hairline()
                Text("敏感界面安全护栏", style = MaterialTheme.typography.labelMedium, color = G.ink)
                SegmentedPillBar(
                    options = listOf(
                        MOBILE_SAFETY_BALANCED to "平衡护栏",
                        MOBILE_SAFETY_STRICT to "严格确认",
                        MOBILE_SAFETY_UNRESTRICTED to "自由执行",
                    ),
                    selected = cfg.safetyMode,
                    onSelect = { updateCfg(cfg.copy(safetyMode = it)) },
                )
                Text(
                    when (cfg.safetyMode) {
                        MOBILE_SAFETY_STRICT -> "每次点击、滑动、输入、按键、应用操作和剪贴板写入均需在手机上确认。"
                        MOBILE_SAFETY_UNRESTRICTED -> "自由执行：放行所有界面操作（含系统安装器与权限框），仍随时支持悬浮胶囊一键急停。"
                        else -> "平衡护栏（推荐）：普通应用流畅执行；遇到支付、银行、系统权限授权、应用安装器与 Root 管理器界面时自动拦截保护。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                )
                Hairline()
                SettingsToggleRow(
                    title = "允许 AI 读取与回复手机通知",
                    subtitle = "开启后 Agent 可查询通知栏消息、自动提取验证码及免切屏直接回复（需开启通知监听服务）。",
                    checked = cfg.allowNotifications,
                    onCheckedChange = { updateCfg(cfg.copy(allowNotifications = it)) },
                )
                Hairline()
                SettingsToggleRow(
                    title = "允许 AI 执行 Android 宿主底层命令（高危）",
                    subtitle = "默认关闭。开启后在 Root（chroot）或 Shizuku 驱动下解锁 solbraid_mobile_host（宿主 Shell、logcat、settings 读写、dumpsys、getprop/setprop）；破坏性清盘命令仍会被硬性拦截。",
                    checked = cfg.allowHostShell,
                    onCheckedChange = {
                        updateCfg(
                            cfg.copy(allowHostShell = it),
                            if (it) "已开启宿主底层命令权限（请留意安全）" else "已关闭宿主底层命令权限",
                        )
                    },
                )
                Hairline()
                SettingsToggleRow(
                    title = "操控期间显示悬浮急停胶囊",
                    subtitle = "AI 执行截屏或交互动作时在屏幕右上角显示「点此停止」胶囊（空闲 7 秒自动隐去），同时通知栏常驻急停按钮。",
                    checked = cfg.showOverlayPill,
                    onCheckedChange = { updateCfg(cfg.copy(showOverlayPill = it)) },
                )
                Hairline()
                Text("截屏传给 AI 的最大宽度（控制 Token 成本）", style = MaterialTheme.typography.labelMedium, color = G.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(540 to "540px · 省流", 720 to "720px · 均衡", 1080 to "1080px · 高清").forEach { (w, lbl) ->
                        SelectableChip(
                            text = lbl,
                            selected = cfg.screenshotMaxWidth == w,
                            onClick = { updateCfg(cfg.copy(screenshotMaxWidth = w)) },
                        )
                    }
                }
            }
        }

        // 5. 现场烟测与一键急停演练
        SectionLabel("现场验证与紧急熔断")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryPillButton(
                        text = "现场测试（截屏 + UI 树）",
                        enabled = ready && busyLabel == null,
                        onClick = {
                            scope.launch {
                                busyLabel = "正在通过 ${status.activeBackend.label} 执行截屏与 UI 树自检…"
                                try {
                                    val shot = app.mobileUse.execute("screenshot", JSONObject().put("includeImage", false))
                                    val tree = app.mobileUse.execute("ui_tree", JSONObject().put("maxNodes", 80))
                                    testSummary = buildString {
                                        appendLine("驱动后端：${shot.optString("backend")}（运行模式：${settings.effectiveRuntimeMode}）")
                                        appendLine("屏幕尺寸：${shot.optInt("screenWidth")}×${shot.optInt("screenHeight")} → 压缩 ${shot.optInt("width")}×${shot.optInt("height")}（${shot.optInt("bytes") / 1024} KB）")
                                        appendLine("容器截屏路径：${shot.optString("guestPath")}")
                                        append("前台应用：${tree.optString("packageName").ifBlank { "未知" }} · 识别到 ${tree.optInt("nodeCount")} 个可交互/文本节点")
                                    }
                                    app.conversations.notify("Mobile-Use 现场自检通过")
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    testSummary = "自检失败：${e.message}"
                                    app.conversations.notify("自检失败：${e.message}")
                                } finally {
                                    busyLabel = null
                                }
                            }
                        },
                    )
                    if (!status.emergencyStopped) {
                        SecondaryPillButton(
                            text = "立即熔断急停",
                            danger = true,
                            onClick = {
                                app.mobileUse.triggerEmergencyStop()
                                app.conversations.notify("已触发 AI 手机操控紧急停止")
                            },
                        )
                    }
                }
                if (!testSummary.isNullOrBlank()) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                        Text(
                            testSummary!!,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = Mono,
                            color = G.inkMuted,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}
