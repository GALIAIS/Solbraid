package com.galiais.agent.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SELF_BUILD_HEAP_OPTIONS
import com.galiais.agent.engine.SelfBuildConfig
import com.galiais.agent.engine.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 本机纯本地 ARM64 APK 自迭代配置与运维中心：默认关闭，全部权限护栏、目录、构建与看门狗参数完全自由配置。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfBuildSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()

    val saved = settings.selfBuild
    var repoDir by remember(saved.repoDir) { mutableStateOf(saved.repoDir) }
    var androidHome by remember(saved.androidHome) { mutableStateOf(saved.androidHome) }
    var javaHome by remember(saved.javaHome) { mutableStateOf(saved.javaHome) }
    var aapt2Path by remember(saved.aapt2Path) { mutableStateOf(saved.aapt2Path) }
    var signingPropertiesPath by remember(saved.signingPropertiesPath) { mutableStateOf(saved.signingPropertiesPath) }
    var timeoutText by remember(saved.watchdogTimeoutSec) { mutableStateOf(saved.watchdogTimeoutSec.toString()) }
    var customGradleArgs by remember(saved.customGradleArgs) { mutableStateOf(saved.customGradleArgs) }

    var statusJson by remember { mutableStateOf<JSONObject?>(null) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var lastApkPath by remember { mutableStateOf<String?>(null) }
    var lastOutputTail by remember { mutableStateOf<String?>(null) }
    var confirmDialog by remember { mutableStateOf<String?>(null) }

    fun persistAndSync(next: SelfBuildConfig, toast: String? = null) {
        try {
            val validated = next.validated()
            app.settings.setSelfBuildConfig(validated)
            scope.launch {
                runCatching { app.conversations.syncSelfBuildConfigNow() }
                    .onFailure { if (runtime.ready && app.runtime.hubAvailable) app.conversations.notify("已保存本地设置，但同步运行环境失败：${it.message}") }
            }
            if (!toast.isNullOrBlank()) app.conversations.notify(toast)
        } catch (e: Exception) {
            app.conversations.notify("配置校验失败：${e.message}")
        }
    }

    fun refreshStatus(silent: Boolean = false) {
        if (!runtime.ready || !app.runtime.hubAvailable) return
        scope.launch {
            if (!silent) busyLabel = "正在检测本机编译工具链…"
            try {
                statusJson = app.runtime.hubRequest("selfBuild/status", jsonOf("repoDir" to saved.repoDir), timeoutMs = 25_000)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (!silent) app.conversations.notify("工具链体检失败：${e.message}")
            } finally {
                if (!silent) busyLabel = null
            }
        }
    }

    LaunchedEffect(runtime.ready, app.runtime.hubAvailable, saved.repoDir, saved.androidHome, saved.javaHome, saved.aapt2Path, saved.signingPropertiesPath) {
        if (runtime.ready && app.runtime.hubAvailable) {
            runCatching { app.conversations.syncSelfBuildConfigNow() }
            refreshStatus(silent = true)
        }
    }

    val hasTextChanges = repoDir != saved.repoDir ||
        androidHome != saved.androidHome ||
        javaHome != saved.javaHome ||
        aapt2Path != saved.aapt2Path ||
        signingPropertiesPath != saved.signingPropertiesPath ||
        timeoutText != saved.watchdogTimeoutSec.toString() ||
        customGradleArgs != saved.customGradleArgs

    SubPageScaffold(title = "本机 APK 自迭代", onBack = onBack, snackbar = snackbar, loading = busyLabel != null) {
        // 1. 总开关（默认关闭）
        CrispCard(
            background = if (saved.enabled) G.accentContainer.copy(alpha = 0.22f) else G.surface,
            borderColor = if (saved.enabled) G.accent.copy(alpha = 0.45f) else G.border,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagPill(
                        text = if (saved.enabled) "已启用" else "已关闭（默认）",
                        fg = if (saved.enabled) G.accent else G.inkMuted,
                        bg = if (saved.enabled) G.accentContainer else G.surfaceSubtle,
                    )
                    TagPill(
                        text = if (saved.watchdogEnabled) "看门狗 ${saved.watchdogTimeoutSec}s" else "无看门狗",
                        fg = if (saved.watchdogEnabled) G.success else G.warning,
                        bg = if (saved.watchdogEnabled) G.successTint else G.warningTint,
                    )
                }
                SettingsToggleRow(
                    title = "启用本机 ARM64 APK 自迭代闭环",
                    subtitle = "默认关闭。开启后可在手机端直接用本地 JDK + Android SDK 编译 Solbraid APK，并通过独立 Root 看门狗校验首帧心跳（崩溃自动回滚）。",
                    checked = saved.enabled,
                    onCheckedChange = { enabled ->
                        persistAndSync(
                            saved.copy(enabled = enabled),
                            toast = if (enabled) "已启用本机 APK 自迭代功能" else "已关闭本机 APK 自迭代功能",
                        )
                    },
                    icon = GaliaisIcons.Cpu,
                )
            }
        }

        AnimatedExpandable(expanded = saved.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(D.space(12.dp))) {
                // 2. Agent 权限与安全护栏
                SectionLabel("权限与安全护栏")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsToggleRow(
                            title = "允许 Agent 通过 MCP 触发本地编译",
                            subtitle = "关闭时，Agent 调用 solbraid_self_build(build) 会被直接拦截，仅允许查看状态或由你在此页面手动触发编译。",
                            checked = saved.allowAgentBuild,
                            onCheckedChange = { persistAndSync(saved.copy(allowAgentBuild = it)) },
                        )
                        Hairline()
                        SettingsToggleRow(
                            title = "允许 Agent 触发看门狗安装 APK",
                            subtitle = "高风险开关（默认关闭）。即使开启，Agent 仍必须显式传入 confirmInstall=true 才能触发安装并重启应用。",
                            checked = saved.allowAgentApply,
                            onCheckedChange = { persistAndSync(saved.copy(allowAgentApply = it)) },
                        )
                        Hairline()
                        SettingsToggleRow(
                            title = "编译前运行单元测试（testDebugUnitTest）",
                            subtitle = "强烈建议开启：任意单测失败将立即中止打包，防止错误代码进入安装阶段。",
                            checked = saved.runUnitTests,
                            onCheckedChange = { persistAndSync(saved.copy(runUnitTests = it)) },
                        )
                        Hairline()
                        SettingsToggleRow(
                            title = "安装后启用首帧心跳看门狗（崩溃自动回滚）",
                            subtitle = "独立于 App 进程树监控新版本首帧渲染心跳；超时或启动崩溃将自动恢复 last-known-good.apk。",
                            checked = saved.watchdogEnabled,
                            onCheckedChange = { persistAndSync(saved.copy(watchdogEnabled = it)) },
                        )
                    }
                }

                // 3. 构建与看门狗参数
                SectionLabel("构建与看门狗参数")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("目标 ABI 架构", style = MaterialTheme.typography.labelMedium, color = G.ink)
                        SegmentedPillBar(
                            options = listOf("arm64-v8a" to "仅 arm64-v8a", "arm64-v8a,x86_64" to "arm64 + x86_64"),
                            selected = saved.targetAbis,
                            onSelect = { persistAndSync(saved.copy(targetAbis = it)) },
                        )
                        Hairline()
                        Text("构建变体（Variant）", style = MaterialTheme.typography.labelMedium, color = G.ink)
                        SegmentedPillBar(
                            options = listOf("debug" to "Debug 调试版", "release" to "Release 发布版"),
                            selected = saved.variant,
                            onSelect = { persistAndSync(saved.copy(variant = it)) },
                        )
                        Hairline()
                        Text("Gradle JVM 堆内存上限", style = MaterialTheme.typography.labelMedium, color = G.ink)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SELF_BUILD_HEAP_OPTIONS.sorted().forEach { mb ->
                                SelectableChip(
                                    text = "${mb} MB",
                                    selected = saved.gradleHeapMb == mb,
                                    onClick = { persistAndSync(saved.copy(gradleHeapMb = mb)) },
                                )
                            }
                        }
                        Hairline()
                        Text("看门狗心跳超时（10–120 秒）", style = MaterialTheme.typography.labelMedium, color = G.ink)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 20, 30, 60).forEach { sec ->
                                SelectableChip(
                                    text = "${sec}s",
                                    selected = saved.watchdogTimeoutSec == sec,
                                    onClick = {
                                        timeoutText = sec.toString()
                                        persistAndSync(saved.copy(watchdogTimeoutSec = sec))
                                    },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = timeoutText,
                            onValueChange = { s -> timeoutText = s.filter { it.isDigit() }.take(3) },
                            label = { Text("自定义看门狗超时（秒）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = customGradleArgs,
                            onValueChange = { customGradleArgs = it },
                            label = { Text("附加 Gradle 参数（可选，如 --offline --stacktrace）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // 4. 工具链与目录配置
                SectionLabel("工具链与目录路径")
                CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = signingPropertiesPath,
                            onValueChange = { signingPropertiesPath = it },
                            label = { Text("签名配置文件路径") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("使用已安装版本的原签名密钥。配置文件包含 storeFile、storePassword、keyAlias、keyPassword；请存放在项目外的私有目录。安装前会校验证书。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        OutlinedTextField(
                            value = repoDir,
                            onValueChange = { repoDir = it },
                            label = { Text("Solbraid 源码仓库目录") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = androidHome,
                            onValueChange = { androidHome = it },
                            label = { Text("Android SDK 目录（ANDROID_HOME）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = javaHome,
                            onValueChange = { javaHome = it },
                            label = { Text("JDK 17 目录（留空自动检测）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = aapt2Path,
                            onValueChange = { aapt2Path = it },
                            label = { Text("原生 ARM64 aapt2 路径（留空自动检测）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryPillButton(
                                text = "保存全部配置",
                                enabled = hasTextChanges,
                                onClick = {
                                    val sec = timeoutText.toIntOrNull() ?: saved.watchdogTimeoutSec
                                    persistAndSync(
                                        saved.copy(
                                            repoDir = repoDir,
                                            androidHome = androidHome,
                                            javaHome = javaHome,
                                            aapt2Path = aapt2Path,
                                            signingPropertiesPath = signingPropertiesPath,
                                            watchdogTimeoutSec = sec,
                                            customGradleArgs = customGradleArgs,
                                        ),
                                        toast = "已保存并同步本机 APK 自迭代配置",
                                    )
                                },
                            )
                            SecondaryPillButton(
                                text = "恢复默认",
                                onClick = { confirmDialog = "reset" },
                            )
                        }
                    }
                }
            }
        }

        // 5. 本机工具链体检与手动运维
        SectionLabel("工具链体检与手动操作")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val st = statusJson
                if (!runtime.ready || !app.runtime.hubAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            when {
                                runtime.busy -> "本地运行环境正在准备中，就绪后自动完成工具链体检…"
                                !runtime.ready -> "本地运行环境未就绪，启动后即可自动检测工具链状态并执行本地编译。"
                                else -> "Agent 服务暂未连接，重启运行环境即可恢复工具链体检与编译能力。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (!runtime.ready && !runtime.busy && runtime.bound) {
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        } else if (runtime.ready && !app.runtime.hubAvailable) {
                            TagPill("重启恢复", fg = G.danger, bg = G.dangerTint, onClick = app.runtime::restart)
                        }
                    }
                } else if (st != null) {
                    val checks = listOf(
                        Triple("CPU 架构", st.optString("arch").isNotBlank(), st.optString("arch", "unknown")),
                        Triple("JDK 17", st.optBoolean("javaOk"), st.optString("javaHome").ifBlank { "未检测到" }),
                        Triple("Android SDK 36", st.optBoolean("platform36Ok"), st.optString("androidHome").ifBlank { "未配置" }),
                        Triple("原生 ARM64 aapt2", st.optBoolean("aapt2Ok"), st.optString("aapt2Bin").ifBlank { "未检测到" }),
                        Triple("源码仓库", st.optBoolean("repoOk"), st.optString("repoDir").ifBlank { "未检测到" }),
                        Triple("签名配置", st.optBoolean("keystoreOk"), if (st.optBoolean("keystoreOk")) "已配置，安装前校验证书" else "请配置原签名密钥"),
                        Triple("离线 .so 零下载复用", st.optBoolean("nativeOfflineHydratable"), if (st.optBoolean("nativeOfflineHydratable")) "/opt/galiais-native 可用" else "不可用"),
                    )
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.surfaceSubtle) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            checks.forEach { (label, ok, detail) ->
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatusGlyph(
                                        visual = if (ok) StatusVisual.SUCCESS else StatusVisual.WARNING,
                                        color = if (ok) G.success else G.warning,
                                        size = 14.5.dp,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.labelSmall, color = G.ink, fontWeight = FontWeight.Medium)
                                        Text(detail, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                    }
                                }
                            }
                            val wdObj = st.optJSONObject("watchdogStatus")
                            val wdDetail = wdObj?.optString("detail").orEmpty()
                            if (wdDetail.isNotBlank()) {
                                Hairline()
                                Text(
                                    "最近看门狗记录：${wdObj?.optString("phase")} · $wdDetail",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (wdObj?.optBoolean("ok") == true) G.success else G.inkMuted,
                                )
                            }
                        }
                    }
                }
                if (busyLabel != null) {
                    Text(busyLabel!!, style = MaterialTheme.typography.bodySmall, color = G.accent)
                }
                val canOperate = saved.enabled && runtime.ready && app.runtime.hubAvailable && busyLabel == null
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPillButton(
                        text = "刷新体检",
                        enabled = runtime.ready && app.runtime.hubAvailable && busyLabel == null,
                        onClick = { refreshStatus(silent = false) },
                    )
                    SecondaryPillButton(
                        text = "初始化工具链",
                        enabled = canOperate,
                        onClick = {
                            scope.launch {
                                busyLabel = "正在初始化 ARM64 工具链与离线资产…"
                                try {
                                    val res = app.runtime.hubRequest("selfBuild/bootstrap", jsonOf("repoDir" to saved.repoDir), timeoutMs = 480_000)
                                    statusJson = res
                                    lastOutputTail = res.optString("outputTail").takeIf { it.isNotBlank() }
                                    app.conversations.notify("工具链初始化完成")
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    app.conversations.notify("初始化失败：${e.message}")
                                } finally {
                                    busyLabel = null
                                }
                            }
                        },
                    )
                    PrimaryPillButton(
                        text = "开始本地编译 APK",
                        enabled = canOperate,
                        onClick = {
                            scope.launch {
                                busyLabel = "正在手机端本地执行 Gradle 编译（通常需 2–5 分钟）…"
                                try {
                                    val res = app.runtime.hubRequest(
                                        "selfBuild/build",
                                        jsonOf("repoDir" to saved.repoDir, "variant" to saved.variant, "abis" to saved.targetAbis),
                                        timeoutMs = 920_000,
                                    )
                                    lastApkPath = res.optString("apkPath").takeIf { it.isNotBlank() }
                                    lastOutputTail = res.optString("outputTail").takeIf { it.isNotBlank() }
                                    val sec = (res.optLong("durationMs") / 1000L).coerceAtLeast(1)
                                    app.conversations.notify("本地编译成功（耗时 ${sec}s）：${lastApkPath ?: "已输出"}")
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    app.conversations.notify("本地编译失败：${e.message}")
                                } finally {
                                    busyLabel = null
                                }
                            }
                        },
                    )
                    if (!lastApkPath.isNullOrBlank()) {
                        PrimaryPillButton(
                            text = "安装刚编译的 APK",
                            enabled = canOperate,
                            onClick = { confirmDialog = "apply" },
                        )
                    }
                    if (statusJson?.optBoolean("hasBackup") == true) {
                        SecondaryPillButton(
                            text = "回滚上一稳定版",
                            enabled = canOperate,
                            danger = true,
                            onClick = { confirmDialog = "rollback" },
                        )
                    }
                }
                if (!saved.enabled) {
                    Text("总开关当前已关闭，所有初始化、编译、安装与回滚操作已锁定。", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                }
                if (!lastOutputTail.isNullOrBlank()) {
                    Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                        Text(
                            lastOutputTail!!.takeLast(900),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = Mono,
                            color = G.inkMuted,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDialog == "apply" && !lastApkPath.isNullOrBlank()) {
        val isProot = settings.effectiveRuntimeMode == com.galiais.agent.engine.RUNTIME_MODE_PROOT
        GaliaisConfirmDialog(
            title = "确认安装自编译 APK？",
            message = if (isProot) {
                "当前处于免 Root（PRoot）模式，即将通过 Android 原生系统安装器安装：$lastApkPath\n\n点击确认后将弹出系统安装界面，请按系统提示完成覆盖安装。"
            } else {
                "即将通过独立 Root 进程安装：$lastApkPath\n\n安装过程会重启 Solbraid 并中断当前前台页面。" +
                    if (saved.watchdogEnabled) "已启用首帧心跳看门狗（${saved.watchdogTimeoutSec}s），若启动异常将自动回滚上一版本。"
                    else "注意：当前看门狗已关闭，异常时需手动回滚。"
            },
            confirmText = if (isProot) "打开系统安装器" else "确认安装并重启",
            onConfirm = {
                val apk = lastApkPath!!
                confirmDialog = null
                scope.launch {
                    busyLabel = if (isProot) "正在调起 Android 系统安装器…" else "正在移交独立看门狗安装 APK…"
                    try {
                        val res = app.runtime.hubRequest("selfBuild/apply", jsonOf("apkPath" to apk, "confirmInstall" to true), timeoutMs = 30_000)
                        val mode = res.optString("mode")
                        app.conversations.notify(
                            if (mode == "android_system_installer") "已调起 Android 系统安装器，请在弹窗中确认安装"
                            else "已移交看门狗安装，应用即将自动重启…",
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        app.conversations.notify("触发安装失败：${e.message}")
                    } finally {
                        busyLabel = null
                    }
                }
            },
            onDismiss = { confirmDialog = null },
        )
    }

    if (confirmDialog == "rollback") {
        GaliaisConfirmDialog(
            title = "确认回滚到上一稳定版本？",
            message = "将使用 last-known-good.apk 覆盖安装当前版本并重启 Solbraid。",
            confirmText = "确认回滚",
            danger = true,
            onConfirm = {
                confirmDialog = null
                scope.launch {
                    busyLabel = "正在触发回滚安装…"
                    try {
                        app.runtime.hubRequest("selfBuild/rollback", jsonOf("confirmRollback" to true), timeoutMs = 30_000)
                        app.conversations.notify("已触发回滚，应用即将自动重启…")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        app.conversations.notify("回滚失败：${e.message}")
                    } finally {
                        busyLabel = null
                    }
                }
            },
            onDismiss = { confirmDialog = null },
        )
    }

    if (confirmDialog == "reset") {
        GaliaisConfirmDialog(
            title = "恢复自迭代默认配置？",
            message = "将清空自定义路径与 Gradle 参数，并关闭自迭代总开关。",
            confirmText = "恢复默认",
            danger = true,
            onConfirm = {
                confirmDialog = null
                val def = SelfBuildConfig()
                repoDir = def.repoDir
                androidHome = def.androidHome
                javaHome = def.javaHome
                aapt2Path = def.aapt2Path
                signingPropertiesPath = def.signingPropertiesPath
                timeoutText = def.watchdogTimeoutSec.toString()
                customGradleArgs = def.customGradleArgs
                persistAndSync(def, toast = "已恢复默认配置（默认关闭）")
            },
            onDismiss = { confirmDialog = null },
        )
    }
}
