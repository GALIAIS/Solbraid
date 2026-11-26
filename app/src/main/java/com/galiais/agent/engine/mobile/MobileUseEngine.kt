package com.galiais.agent.engine.mobile

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.galiais.agent.engine.AppSettings
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RuntimeClient
import com.galiais.agent.engine.fs.UnifiedFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Solbraid 双模式 Mobile-Use 手机操控引擎（chroot/Root 与 PRoot/无障碍统一接口）：
 * - chroot / Root 模式：复用常驻 `RootFsDaemon`（零重复 fork su），直接驱动 screencap / uiautomator / input / am / dumpsys；
 * - PRoot / 免 Root 模式：复用内置 `SolbraidAccessibilityService`（Android 11+ 无障碍截屏 + 毫秒级 UI 树 + 手势注入 + 原生中文输入）；
 * - 两个模式通过同一套 Agent Hub 反向 RPC 暴露给全部 Agent（Codex / Claude Code / Antigravity / ACP）。
 */
class MobileUseEngine(
    private val app: Application,
    private val runtime: RuntimeClient,
    private val settings: AppSettings,
    private val fileManager: UnifiedFileRepository,
) {
    private val opMutex = Mutex()
    private val selfUpdateMutex = Mutex()
    private val emergencyStoppedFlag = AtomicBoolean(false)
    private val actionCounter = AtomicInteger(0)
    private val stopGeneration = AtomicInteger(0)
    @Volatile private var activeJob: Job? = null
    @Volatile private var operationGeneration = 0
    private var guardedForeground: String? = null
    data class Approval(val id: String, val action: String, val packageName: String, val details: String, val returnToApp: Boolean = true)
    private val approvalFlow = MutableStateFlow<Approval?>(null)
    val pendingApproval: StateFlow<Approval?> = approvalFlow.asStateFlow()
    @Volatile private var approvalDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var activeActionLabel: String? = null
    @Volatile private var lastActionAtMs: Long = 0L

    private val guardUi = MobileUseGuardUi(app) { triggerEmergencyStop() }
    internal val shizuku = ShizukuBridge(app)
    @Volatile private var lastUiNodes: List<UiTreeNode> = emptyList()
    @Volatile private var cachedDaemonRootOk: Boolean = false

    private val config: MobileUseConfig get() = settings.state.value.mobileUse
    private val runtimeMode: String get() = settings.state.value.effectiveRuntimeMode

    private val statusFlow = MutableStateFlow(computeStatus())
    val status: StateFlow<MobileUseStatus> = statusFlow.asStateFlow()

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission().also { refreshStatus() }

    fun refreshStatus(): MobileUseStatus {
        if (!config.enabled) activeJob?.cancel(CancellationException("手机操控已关闭"))
        val snap = computeStatus()
        statusFlow.value = snap
        return snap
    }

    private fun isRootEffectivelyAvailable(): Boolean {
        if (runtime.rootAvailable.value == true) return true
        if (cachedDaemonRootOk) return true
        val rtSnap = runtime.snapshot.value
        if (rtSnap.ready && rtSnap.mode != RUNTIME_MODE_PROOT) return true
        return false
    }

    private fun computeStatus(): MobileUseStatus {
        val cfg = config
        val mode = runtimeMode
        val rootOk = isRootEffectivelyAvailable()
        val shizukuSt = shizuku.refreshStatus()
        val a11yConn = SolbraidAccessibilityService.instance != null
        val a11yEnabled = SolbraidAccessibilityService.isEnabledInSystemSettings(app)
        val notifConn = SolbraidNotificationListenerService.instance != null
        val notifEnabled = SolbraidNotificationListenerService.isEnabledInSystemSettings(app)
        val writeSettingsOk = Settings.System.canWrite(app)
        val overlayOk = Settings.canDrawOverlays(app)
        val backend = MobileUseLogic.resolveBackend(
            preferred = cfg.preferredBackend,
            runtimeMode = mode,
            rootAvailable = rootOk,
            accessibilityReady = a11yConn,
            shizukuReady = shizukuSt.ready,
        )
        return MobileUseStatus(
            enabled = cfg.enabled,
            activeBackend = backend,
            preferredBackend = cfg.preferredBackend,
            safetyMode = cfg.safetyMode,
            runtimeMode = mode,
            rootAvailable = rootOk,
            shizukuInstalled = shizukuSt.installed,
            shizukuAlive = shizukuSt.binderAlive,
            shizukuGranted = shizukuSt.permissionGranted,
            shizukuUid = shizukuSt.uid,
            accessibilityConnected = a11yConn,
            accessibilitySettingEnabled = a11yEnabled,
            notificationListenerConnected = notifConn,
            notificationListenerEnabled = notifEnabled,
            writeSettingsGranted = writeSettingsOk,
            overlayPermissionGranted = overlayOk,
            allowHostShell = cfg.allowHostShell,
            allowNotifications = cfg.allowNotifications,
            emergencyStopped = emergencyStoppedFlag.get(),
            activeActionLabel = activeActionLabel,
            lastActionAtMs = lastActionAtMs,
            actionCount = actionCounter.get(),
        )
    }

    fun triggerEmergencyStop() {
        emergencyStoppedFlag.set(true)
        stopGeneration.incrementAndGet()
        activeJob?.cancel(CancellationException("用户已紧急停止手机操控"))
        approvalDecision?.complete(false)
        activeActionLabel = null
        guardUi.showStoppedNotice()
        refreshStatus()
    }

    fun resumeFromEmergencyStop() {
        emergencyStoppedFlag.set(false)
        guardUi.hideAll()
        refreshStatus()
    }

    fun resolveApproval(id: String, allow: Boolean) {
        if (approvalFlow.value?.id == id) approvalDecision?.complete(allow)
    }

    private suspend fun requestUserConfirmation(request: Approval) {
        val decision = CompletableDeferred<Boolean>()
        approvalDecision = decision
        approvalFlow.value = request
        guardUi.showActive("等待操作确认，请打开 Solbraid", showPill = config.showOverlayPill, autoHideDelayMs = 0)
        try {
            val allowed = withTimeoutOrNull(120_000) { decision.await() }
            check(allowed == true) { if (allowed == null) "操作确认已超时" else "用户拒绝了本次操作" }
        } finally {
            approvalFlow.value = null
            approvalDecision = null
            decision.cancel()
            if (!emergencyStoppedFlag.get()) guardUi.hideAll()
        }
    }

    private fun ensureOperationActive() {
        activeJob?.ensureActive()
        if (!config.enabled || emergencyStoppedFlag.get() || operationGeneration != stopGeneration.get()) {
            throw CancellationException("手机操控已停止")
        }
    }

    private suspend fun ensureMutationAllowed(backend: MobileBackendKind) {
        ensureOperationActive()
        val expected = guardedForeground
        if (expected != null && detectForegroundPackage(backend) != expected) {
            throw IllegalStateException("前台应用已变化，请重新读取界面后操作")
        }
        ensureOperationActive()
    }

    /** 统一执行入口：供 Agent Hub 反向 RPC 与设置页现场烟测复用。 */
    suspend fun execute(action: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val act = action.trim().lowercase()
        if (act == "app" || act == "clipboard") {
            params.put("operation", params.optString("operation", if (act == "app") "list" else "read").trim().lowercase())
        }
        if (act == "status") {
            if (!isRootEffectivelyAvailable() && runtime.rootAvailable.value == null) {
                runtime.probeRoot()
                cachedDaemonRootOk = runCatching {
                    val res = fileManager.rootDaemon.exec("id -u")
                    res.exitCode == 0 && res.stdout.trim() == "0"
                }.getOrDefault(false)
            }
            val st = refreshStatus()
            val (sw, sh) = currentScreenSize()
            return@withContext st.toJson().apply {
                put("ok", true)
                put("screenWidth", sw)
                put("screenHeight", sh)
            }
        }
        val generation = stopGeneration.get()
        val cfg = config
        if (!cfg.enabled) {
            throw IllegalStateException("手机操控（Mobile-Use）已在设置中关闭，请前往「设置 → 手机操控（Mobile-Use）」开启")
        }
        if (emergencyStoppedFlag.get()) {
            throw IllegalStateException("AI 手机操控已被用户紧急停止，请在「设置 → 手机操控（Mobile-Use）」中点击恢复后再试")
        }

        // 剪贴板、设备遥测/硬件、Intent、通知、文件与部分应用操作可由 Android 原生 API 零依赖执行
        val opLower = params.optString("operation", "").trim().lowercase()
        val isMutating = act in setOf("tap", "swipe", "type", "key", "intent") ||
            (act == "app" && opLower !in setOf("", "list", "foreground", "info", "extract_apk")) ||
            (act == "clipboard" && opLower == "write") ||
            (act == "device" && opLower !in setOf("", "telemetry", "info")) ||
            (act == "notification" && opLower in setOf("reply", "action", "dismiss")) ||
            (act == "files" && opLower in setOf("push", "delete")) ||
            (act == "host" && opLower in setOf("shell", "settings_put", "setprop"))

        val requireUiBackend = act in setOf("screenshot", "ui_tree", "tap", "swipe", "type", "key")

        return@withContext opMutex.withLock {
            if (emergencyStoppedFlag.get() || generation != stopGeneration.get()) {
                throw IllegalStateException("AI 手机操控已被用户紧急停止")
            }
            operationGeneration = generation
            activeJob = currentCoroutineContext()[Job]
            guardedForeground = null
            try {
                ensureOperationActive()
                val liveConfig = config
                val backend = resolveActiveBackend(liveConfig, requireUiBackend = requireUiBackend)
                if (isMutating && act != "host" && act != "files" && !(act == "device" && opLower in setOf("volume", "torch", "vibrate", "media"))) {
                    checkSensitiveGuard(liveConfig, backend, act, params)
                }
                ensureOperationActive()
                val label = actionDisplayLabel(act, params)
                activeActionLabel = label
                lastActionAtMs = System.currentTimeMillis()
                actionCounter.incrementAndGet()
                refreshStatus()
                if (isMutating || act == "screenshot") guardUi.showActive(label, showPill = liveConfig.showOverlayPill)
                when (act) {
                    "screenshot" -> doScreenshot(backend, liveConfig, params)
                    "ui_tree" -> doUiTree(backend, params)
                    "tap" -> doTap(backend, params)
                    "swipe" -> doSwipe(backend, params)
                    "type" -> doType(backend, params)
                    "key" -> doKey(backend, params)
                    "app" -> doApp(backend, params)
                    "clipboard" -> {
                        if (isMutating) ensureMutationAllowed(backend)
                        doClipboard(params)
                    }
                    "device" -> MobileUseDeviceOps.execute(app, backend, params) { cmd, timeoutSec ->
                        privilegedExec(backend, cmd, timeoutSec, mutating = isMutating)
                    }
                    "intent" -> MobileUseIntentOps.execute(app, backend, activeLinuxRootDir(), params) { cmd, timeoutSec ->
                        privilegedExec(backend, cmd, timeoutSec, mutating = true)
                    }
                    "notification" -> MobileUseExtraOps.executeNotification(app, liveConfig, backend, params) { cmd, timeoutSec ->
                        privilegedExec(backend, cmd, timeoutSec, mutating = opLower != "list" && opLower != "otp" && opLower != "status")
                    }
                    "files" -> MobileUseExtraOps.executeFiles(app, backend, activeLinuxRootDir(), params) { cmd, timeoutSec ->
                        privilegedExec(backend, cmd, timeoutSec, mutating = opLower in setOf("push", "delete"))
                    }
                    "host" -> MobileUseExtraOps.executeHost(liveConfig, backend, params) { cmd, timeoutSec ->
                        privilegedExec(backend, cmd, timeoutSec, mutating = false)
                    }
                    else -> throw IllegalArgumentException("不支持的 Mobile-Use 动作：$act")
                }
            } finally {
                activeJob = null
                guardedForeground = null
                activeActionLabel = null
                lastActionAtMs = System.currentTimeMillis()
                refreshStatus()
            }
        }
    }

    private fun resolveActiveBackend(cfg: MobileUseConfig, requireUiBackend: Boolean): MobileBackendKind {
        var rootOk = isRootEffectivelyAvailable()
        if (!rootOk && runtime.rootAvailable.value == null && cfg.preferredBackend !in setOf(MOBILE_BACKEND_ACCESSIBILITY, MOBILE_BACKEND_SHIZUKU)) {
            rootOk = runCatching {
                val res = fileManager.rootDaemon.exec("id -u")
                res.exitCode == 0 && res.stdout.trim() == "0"
            }.getOrDefault(false)
            if (rootOk) cachedDaemonRootOk = true
        }
        val shizukuOk = shizuku.refreshStatus().ready
        val a11yOk = SolbraidAccessibilityService.instance != null
        val backend = MobileUseLogic.resolveBackend(cfg.preferredBackend, runtimeMode, rootOk, a11yOk, shizukuOk)
        if (requireUiBackend && backend == MobileBackendKind.NONE) {
            val hint = when {
                cfg.preferredBackend == MOBILE_BACKEND_ROOT -> "当前强制使用 Root 驱动，但设备未获取 Root 权限"
                cfg.preferredBackend == MOBILE_BACKEND_SHIZUKU -> "当前强制使用 Shizuku 驱动，请先启动 Shizuku 并在「设置 → 手机操控」完成授权"
                cfg.preferredBackend == MOBILE_BACKEND_ACCESSIBILITY -> "当前强制使用无障碍服务，请前往系统无障碍设置开启「Solbraid AI 手机操控」"
                runtimeMode == RUNTIME_MODE_PROOT -> "免 Root（PRoot）模式需开启无障碍服务或 Shizuku：请前往「设置 → 手机操控（Mobile-Use）」一键启用"
                else -> "当前未检测到 Root、Shizuku 或无障碍服务，请在「设置 → 手机操控（Mobile-Use）」完成授权"
            }
            throw IllegalStateException(hint)
        }
        return backend
    }

    private fun actionDisplayLabel(act: String, params: JSONObject): String = when (act) {
        "screenshot" -> if (params.optInt("recordSec", 0) > 0) "录屏" else if (params.optBoolean("annotate", false)) "标注截屏" else "截屏"
        "ui_tree" -> if (params.optString("waitFor").isNotBlank()) "等待元素" else "读取界面"
        "tap" -> when {
            params.optBoolean("doubleTap", false) -> "双击屏幕"
            params.optBoolean("longPress", false) -> "长按屏幕"
            else -> "点击屏幕"
        }
        "swipe" -> when {
            params.has("pinch") || params.has("scale") -> "双指缩放"
            params.optBoolean("drag", false) -> "拖拽屏幕"
            params.optString("scrollUntilText").isNotBlank() -> "滚动查找"
            else -> "滑动屏幕"
        }
        "type" -> "输入文本"
        "key" -> "按键 ${params.optString("key")}"
        "app" -> "应用 ${params.optString("operation", "list")}"
        "clipboard" -> if (params.optString("operation") == "write") "写剪贴板" else "读剪贴板"
        "device" -> "设备 ${params.optString("operation", "telemetry")}"
        "intent" -> "直达 ${params.optString("operation", "open_url")}"
        "notification" -> "通知 ${params.optString("operation", "list")}"
        "files" -> "手机文件 ${params.optString("operation", "list")}"
        "host" -> "宿主 ${params.optString("operation", "shell")}"
        else -> act
    }

    private suspend fun checkSensitiveGuard(cfg: MobileUseConfig, backend: MobileBackendKind, act: String, params: JSONObject) {
        if (cfg.safetyMode == MOBILE_SAFETY_UNRESTRICTED) return
        val fgPkg = detectForegroundPackage(backend)
        check(fgPkg.isNotBlank()) { "无法确认前台应用，请检查手机操控权限后重试" }
        guardedForeground = fgPkg
        val targetPkg = if (act == "app" && params.optString("operation") == "launch") {
            resolveLaunchPackage(params).also { params.put("packageName", it) }
        } else if (act == "app") params.optString("packageName").trim() else ""
        val blockedPkg = listOf(fgPkg, targetPkg).firstOrNull {
            MobileUseLogic.isSensitivePackage(it, cfg.customSensitivePackages)
        }
        if (blockedPkg != null || cfg.safetyMode == MOBILE_SAFETY_STRICT) {
            val request = Approval(UUID.randomUUID().toString(), actionDisplayLabel(act, params), blockedPkg ?: fgPkg,
                JSONObject(params.toString()).apply { remove("confirmSensitive") }.toString(2))
            requestUserConfirmation(request)
            // 审批会切换到本应用；只在原应用重新成为前台后执行本次操作。
            val returned = withTimeoutOrNull(30_000) {
                while (detectForegroundPackage(backend) != fgPkg) {
                    ensureOperationActive()
                    delay(200)
                }
                true
            }
            check(returned == true) { "未返回目标应用，本次授权已失效" }
        }
        ensureMutationAllowed(backend)
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = app.getSystemService(WindowManager::class.java)
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        val dm: DisplayMetrics = app.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(360) to dm.heightPixels.coerceAtLeast(640)
    }

    private fun activeLinuxRootDir(): File =
        File(app.filesDir, if (runtimeMode == RUNTIME_MODE_PROOT) "linux-proot" else "linux")

    /** 将截屏同时落盘到当前活跃 Linux rootfs 的 `~/.galiais/mobile-shots/`（自动滚动保留最新 30 张），方便容器内工具链直接读取。 */
    private fun persistShotToActiveGuest(jpegBytes: ByteArray): Pair<String, File> {
        val shotName = "shot-${System.currentTimeMillis()}.jpg"
        val guestRel = "/home/agent/.galiais/mobile-shots/$shotName"
        val guestDir = File(activeLinuxRootDir(), "home/agent/.galiais/mobile-shots")
        val targetFile = try {
            guestDir.mkdirs()
            val out = File(guestDir, shotName)
            out.writeBytes(jpegBytes)
            out.setReadable(true, false)
            // 仅保留最近 30 张截屏，防止容器空间膨胀
            guestDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(30)
                ?.forEach { runCatching { it.delete() } }
            out
        } catch (_: Exception) {
            val fallbackDir = File(app.cacheDir, "mobile-shots").apply { mkdirs() }
            File(fallbackDir, shotName).apply { writeBytes(jpegBytes) }
        }
        return guestRel to targetFile
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * `RootFsDaemon` 使用 `env -i` 隔离环境，而 Android 的 `uiautomator`/`input`/`am`/`monkey`/`cmd`
     * 底层依赖 `app_process`，必须带上宿主进程的 `ANDROID_*` 与 `*CLASSPATH` 系统变量。
     */
    private suspend fun rootAndroidExec(command: String, mutating: Boolean = false): com.galiais.agent.engine.fs.RootFsDaemon.ExecOutput {
        currentCoroutineContext().ensureActive()
        if (mutating) ensureMutationAllowed(MobileBackendKind.ROOT)
        val operationJob = currentCoroutineContext()[Job]
        return fileManager.rootDaemon.exec(androidHostCommand(command), retryOnDisconnect = !mutating, beforeExecute = {
            operationJob?.ensureActive()
            if (mutating) ensureOperationActive()
        })
    }

    private fun androidHostCommand(command: String): String {
        val envKeys = listOf(
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE", "EXTERNAL_STORAGE",
            "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "STANDALONE_SYSTEMSERVER_JARS",
        )
        val exports = buildString {
            for (k in envKeys) {
                val v = System.getenv(k)
                if (!v.isNullOrEmpty()) append("export ").append(k).append('=').append(shQuote(v)).append("; ")
            }
        }
        return "( $exports$command )"
    }

    private suspend fun privilegedExec(
        backend: MobileBackendKind,
        command: String,
        timeoutSec: Int = 25,
        mutating: Boolean = false,
    ): com.galiais.agent.engine.fs.RootFsDaemon.ExecOutput? = when (backend) {
        MobileBackendKind.ROOT -> rootAndroidExec(command, mutating = mutating)
        MobileBackendKind.SHIZUKU -> {
            currentCoroutineContext().ensureActive()
            if (mutating) ensureMutationAllowed(backend)
            shizuku.exec(command, timeoutSec)
        }
        else -> {
            if (isRootEffectivelyAvailable()) rootAndroidExec(command, mutating = mutating)
            else if (shizuku.refreshStatus().ready) shizuku.exec(command, timeoutSec)
            else null
        }
    }

    private suspend fun detectForegroundPackage(backend: MobileBackendKind): String {
        if (backend == MobileBackendKind.ACCESSIBILITY) {
            SolbraidAccessibilityService.instance?.currentForegroundPackage()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU || isRootEffectivelyAvailable()) {
            val out = runCatching {
                privilegedExec(
                    backend,
                    "dumpsys window displays 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | tail -n 2",
                    timeoutSec = 8,
                )
            }.getOrNull()?.stdout.orEmpty()
            val match = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""").find(out)
            if (match != null) return match.groupValues[1]
        }
        return SolbraidAccessibilityService.instance?.currentForegroundPackage().orEmpty()
    }

    private suspend fun doScreenshot(backend: MobileBackendKind, cfg: MobileUseConfig, params: JSONObject): JSONObject {
        val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
        if (params.optBoolean("wakeScreen", false) && hasPriv) {
            privilegedExec(backend, "input keyevent 224", timeoutSec = 8)
            delay(350)
        }
        val recordSec = params.optInt("recordSec", 0)
        if (recordSec > 0) {
            if (!hasPriv) throw IllegalStateException("短录屏功能需要 Root 或 Shizuku 驱动")
            val sec = recordSec.coerceIn(1, 30)
            val tmpMp4 = "/data/local/tmp/solbraid-rec-${System.currentTimeMillis()}.mp4"
            val res = privilegedExec(backend, "screenrecord --time-limit $sec --size 720x1280 ${shQuote(tmpMp4)}", timeoutSec = sec + 15)
            if (res == null || res.exitCode != 0) throw IllegalStateException("录屏失败：${res?.stdout}")
            val recName = "rec-${System.currentTimeMillis()}.mp4"
            val guestRel = "/home/agent/.galiais/mobile-shots/$recName"
            val hostOut = File(activeLinuxRootDir(), "home/agent/.galiais/mobile-shots/$recName").apply { parentFile?.mkdirs() }
            val cpRes = privilegedExec(backend, "cp -f ${shQuote(tmpMp4)} ${shQuote(hostOut.absolutePath)} && chmod 644 ${shQuote(hostOut.absolutePath)} && rm -f ${shQuote(tmpMp4)}", timeoutSec = 15)
            if (cpRes == null || cpRes.exitCode != 0) throw IllegalStateException("导出录屏失败：${cpRes?.stdout}")
            return JSONObject()
                .put("ok", true)
                .put("backend", backend.id)
                .put("recordedSeconds", sec)
                .put("guestPath", guestRel)
                .put("bytes", hostOut.length())
                .put("mimeType", "video/mp4")
        }

        val maxWidth = params.optInt("maxWidth", cfg.screenshotMaxWidth).coerceIn(360, 1440)
        val quality = params.optInt("quality", cfg.screenshotQuality).coerceIn(40, 95)
        val includeImage = params.optBoolean("includeImage", true)
        val annotate = params.optBoolean("annotate", false)
        val cropArr = params.optJSONArray("crop")?.let { arr ->
            if (arr.length() >= 4) intArrayOf(arr.optInt(0), arr.optInt(1), arr.optInt(2), arr.optInt(3)) else null
        }

        var rawBitmap: Bitmap = when (backend) {
            MobileBackendKind.ACCESSIBILITY -> {
                val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
                svc.captureScreenshotBitmap()
            }
            MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> captureScreenshotBitmapViaPriv(backend)
            MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
        }
        val origW = rawBitmap.width
        val origH = rawBitmap.height

        var somNodes: List<UiTreeNode> = emptyList()
        if (annotate && cropArr == null) {
            val (_, nodes) = runCatching { dumpNodesRaw(backend, 140, allWindows = false) }.getOrDefault("" to emptyList())
            lastUiNodes = nodes
            somNodes = MobileUseLogic.filterInteractiveNodesForSoM(nodes, maxLabels = 45)
        }

        if (cropArr != null) {
            val cropped = MobileUseAnnotator.cropBitmap(rawBitmap, cropArr)
            if (cropped !== rawBitmap) rawBitmap.recycle()
            rawBitmap = cropped
        }

        val scaled = if (rawBitmap.width > maxWidth) {
            val targetH = ((rawBitmap.height.toFloat() * maxWidth) / rawBitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBitmap, maxWidth, targetH, true).also {
                if (it !== rawBitmap) rawBitmap.recycle()
            }
        } else {
            rawBitmap
        }
        val finalBitmap = if (annotate && somNodes.isNotEmpty()) {
            MobileUseAnnotator.drawSetOfMark(scaled, somNodes, origW, origH).also {
                if (it !== scaled) scaled.recycle()
            }
        } else {
            scaled
        }

        val outW = finalBitmap.width
        val outH = finalBitmap.height
        val baos = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        finalBitmap.recycle()
        val jpegBytes = baos.toByteArray()

        val (guestPath, hostFile) = persistShotToActiveGuest(jpegBytes)
        return JSONObject().apply {
            put("ok", true)
            put("backend", backend.id)
            put("guestPath", guestPath)
            put("hostPath", hostFile.absolutePath)
            put("screenWidth", origW)
            put("screenHeight", origH)
            put("width", outW)
            put("height", outH)
            put("bytes", jpegBytes.size)
            put("mimeType", "image/jpeg")
            if (annotate) {
                put("annotated", true)
                val marks = JSONArray()
                somNodes.forEach { n ->
                    marks.put(
                        JSONObject()
                            .put("index", n.index)
                            .put("label", n.text.ifEmpty { n.contentDesc.ifEmpty { n.resourceId.ifEmpty { n.className.substringAfterLast('.') } } })
                            .put("center", JSONArray(listOf(n.centerX, n.centerY))),
                    )
                }
                put("marks", marks)
            }
            if (includeImage) {
                put("_mcpImageBase64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                put("_mcpMimeType", "image/jpeg")
            }
        }
    }

    private suspend fun captureScreenshotBitmapViaPriv(backend: MobileBackendKind): Bitmap {
        val rawPath = if (backend == MobileBackendKind.SHIZUKU) {
            "/data/local/tmp/solbraid-shot-${System.currentTimeMillis()}.png"
        } else {
            File(File(app.cacheDir, "mobile-shots").apply { mkdirs() }, "raw-${System.currentTimeMillis()}.png").absolutePath
        }
        val localCopy = File(File(app.cacheDir, "mobile-shots").apply { mkdirs() }, "dec-${System.currentTimeMillis()}.png")
        try {
            val qPath = shQuote(rawPath)
            val res = privilegedExec(backend, "screencap -p $qPath && chmod 644 $qPath", timeoutSec = 20)
            if (res == null || res.exitCode != 0) {
                throw IllegalStateException("${backend.label} 截屏失败：${res?.stdout?.ifBlank { "screencap exit=${res.exitCode}" }}")
            }
            val readFile = if (backend == MobileBackendKind.SHIZUKU) {
                val b64Res = privilegedExec(backend, "base64 -w 0 $qPath 2>/dev/null || base64 $qPath | tr -d '\\n'", timeoutSec = 20)
                if (b64Res == null || b64Res.exitCode != 0 || b64Res.stdout.isBlank()) {
                    throw IllegalStateException("Shizuku 读取截屏流失败")
                }
                localCopy.writeBytes(Base64.decode(b64Res.stdout.trim(), Base64.DEFAULT))
                localCopy
            } else {
                File(rawPath)
            }
            return BitmapFactory.decodeFile(readFile.absolutePath)
                ?: throw IllegalStateException("无法解码 screencap 输出图像")
        } finally {
            if (backend == MobileBackendKind.SHIZUKU) {
                runCatching { privilegedExec(backend, "rm -f ${shQuote(rawPath)}", timeoutSec = 5) }
            } else {
                runCatching { File(rawPath).delete() }
            }
            runCatching { localCopy.delete() }
        }
    }

    private suspend fun dumpNodesRaw(
        backend: MobileBackendKind,
        maxNodes: Int,
        allWindows: Boolean = false,
    ): Pair<String, List<UiTreeNode>> {
        if (allWindows && SolbraidAccessibilityService.instance != null) {
            val dump = SolbraidAccessibilityService.instance!!.dumpUiTree(maxNodes, allWindows = true)
            return dump.packageName to dump.nodes
        }
        return when (backend) {
            MobileBackendKind.ACCESSIBILITY -> {
                val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
                val dump = svc.dumpUiTree(maxNodes, allWindows = allWindows)
                dump.packageName to dump.nodes
            }
            MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> {
                val tmpXml = "/data/local/tmp/solbraid-ui-${System.currentTimeMillis()}.xml"
                val qXml = shQuote(tmpXml)
                val res = privilegedExec(
                    backend,
                    "rm -f $qXml; uiautomator dump $qXml >/dev/null 2>&1 && cat $qXml; __rc=\$?; rm -f $qXml; (exit \$__rc)",
                    timeoutSec = 20,
                )
                if (res == null || res.exitCode != 0 || !res.stdout.contains("<hierarchy")) {
                    // 若系统上同时开了无障碍导致 uiautomator dump 冲突，自动回退无障碍毫秒级读树
                    val a11y = SolbraidAccessibilityService.instance
                    if (a11y != null) {
                        val dump = a11y.dumpUiTree(maxNodes, allWindows = allWindows)
                        return dump.packageName to dump.nodes
                    }
                    throw IllegalStateException("${backend.label} 读取 UI 树失败：${res?.stdout?.take(240)?.ifBlank { "uiautomator dump 失败" }}")
                }
                val nodes = MobileUseLogic.parseUiAutomatorXml(res.stdout, maxNodes)
                val pkg = nodes.firstOrNull { it.packageName.isNotEmpty() }?.packageName ?: detectForegroundPackage(backend)
                pkg to nodes
            }
            MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
        }
    }

    private suspend fun doUiTree(backend: MobileBackendKind, params: JSONObject): JSONObject {
        val maxNodes = params.optInt("maxNodes", 140).coerceIn(10, 240)
        val allWindows = params.optBoolean("allWindows", false)
        val query = params.optString("query").trim().takeIf { it.isNotEmpty() }
        val waitFor = params.optString("waitFor").trim().takeIf { it.isNotEmpty() }
        val waitGone = params.optString("waitGone").trim().takeIf { it.isNotEmpty() }
        val timeoutMs = params.optLong("timeoutMs", 10_000L).coerceIn(500L, 30_000L)

        var pkg = ""
        var allNodes: List<UiTreeNode> = emptyList()
        var waitMatched = true
        val startMs = System.currentTimeMillis()
        if (waitFor != null || waitGone != null) {
            waitMatched = false
            val deadline = startMs + timeoutMs
            while (System.currentTimeMillis() <= deadline) {
                ensureOperationActive()
                val pair = dumpNodesRaw(backend, maxNodes, allWindows)
                pkg = pair.first
                allNodes = pair.second
                val hitFor = waitFor == null || MobileUseLogic.findMatchingNodes(allNodes, text = waitFor).isNotEmpty() ||
                    MobileUseLogic.findMatchingNodes(allNodes, resourceId = waitFor).isNotEmpty()
                val hitGone = waitGone == null || (
                    MobileUseLogic.findMatchingNodes(allNodes, text = waitGone).isEmpty() &&
                        MobileUseLogic.findMatchingNodes(allNodes, resourceId = waitGone).isEmpty()
                    )
                if (hitFor && hitGone) {
                    waitMatched = true
                    break
                }
                delay(450)
            }
        } else {
            val pair = dumpNodesRaw(backend, maxNodes, allWindows)
            pkg = pair.first
            allNodes = pair.second
        }
        lastUiNodes = allNodes

        val nodes = if (query != null) {
            MobileUseLogic.findMatchingNodes(allNodes, text = query)
                .ifEmpty { MobileUseLogic.findMatchingNodes(allNodes, resourceId = query) }
        } else {
            allNodes
        }
        val (sw, sh) = currentScreenSize()
        val arr = JSONArray()
        nodes.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("ok", waitMatched)
            .put("backend", backend.id)
            .put("packageName", pkg)
            .put("allWindows", allWindows)
            .put("screenWidth", sw)
            .put("screenHeight", sh)
            .put("nodeCount", nodes.size)
            .put("elapsedMs", System.currentTimeMillis() - startMs)
            .apply {
                if (waitFor != null || waitGone != null) put("waitMatched", waitMatched)
            }
            .put("nodes", arr)
    }

    private suspend fun doTap(backend: MobileBackendKind, params: JSONObject): JSONObject {
        val longPress = params.optBoolean("longPress", false)
        val doubleTap = params.optBoolean("doubleTap", false)
        val useNodeAction = params.optBoolean("useNodeAction", false)
        val durationMs = params.optLong("durationMs", if (longPress) 650L else 80L).coerceIn(40L, 3000L)
        val hasCoords = params.has("x") && params.has("y") && !params.isNull("x") && !params.isNull("y")
        var matchedNode: UiTreeNode? = null
        val (tx, ty) = when {
            hasCoords -> params.getInt("x") to params.getInt("y")
            params.has("nodeIndex") && !params.isNull("nodeIndex") -> {
                val targetIdx = params.getInt("nodeIndex")
                val cached = lastUiNodes.firstOrNull { it.index == targetIdx }
                    ?: dumpNodesRaw(backend, 200).second.also { lastUiNodes = it }.firstOrNull { it.index == targetIdx }
                    ?: throw IllegalStateException("未找到索引 #$targetIdx 对应的 UI 节点，请先重新截屏(annotate=true)或读取 UI 树")
                matchedNode = cached
                cached.centerX to cached.centerY
            }
            else -> {
                val text = params.optString("text").trim().takeIf { it.isNotEmpty() }
                val desc = params.optString("contentDesc").trim().takeIf { it.isNotEmpty() }
                val resId = params.optString("resourceId").trim().takeIf { it.isNotEmpty() }
                val cls = params.optString("className").trim().takeIf { it.isNotEmpty() }
                val matchIndex = params.optInt("matchIndex", 0).coerceAtLeast(0)
                if (text == null && desc == null && resId == null && cls == null) {
                    throw IllegalArgumentException("点击操作需提供 (x, y) 坐标、nodeIndex 索引，或提供 text / contentDesc / resourceId")
                }
                val (_, nodes) = dumpNodesRaw(backend, 200)
                lastUiNodes = nodes
                val hits = MobileUseLogic.findMatchingNodes(nodes, text = text, contentDesc = desc, resourceId = resId, className = cls)
                val pick = hits.getOrNull(matchIndex)
                    ?: throw IllegalStateException("未在当前界面找到匹配元素（text=$text, contentDesc=$desc, resourceId=$resId）")
                matchedNode = pick
                pick.centerX to pick.centerY
            }
        }

        val ok = when (backend) {
            MobileBackendKind.ACCESSIBILITY -> {
                val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
                ensureMutationAllowed(backend)
                when {
                    useNodeAction && matchedNode != null -> svc.performNodeAction(
                        matchedNode,
                        if (longPress) "long_click" else "click",
                        ::ensureOperationActive,
                    )
                    doubleTap -> svc.performDoubleTap(tx, ty, ::ensureOperationActive)
                    else -> svc.performTap(tx, ty, durationMs, ::ensureOperationActive)
                }
            }
            MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> {
                val cmd = when {
                    doubleTap -> "input tap $tx $ty && input tap $tx $ty"
                    longPress -> "input swipe $tx $ty $tx $ty $durationMs"
                    else -> "input tap $tx $ty"
                }
                val res = privilegedExec(backend, cmd, timeoutSec = 15, mutating = true)
                if (res == null || res.exitCode != 0) throw IllegalStateException("${backend.label} 点击失败：${res?.stdout}")
                true
            }
            MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
        }
        return JSONObject().apply {
            put("ok", ok)
            put("backend", backend.id)
            put("x", tx)
            put("y", ty)
            put("longPress", longPress)
            put("doubleTap", doubleTap)
            if (matchedNode != null) put("matchedNode", matchedNode.toJson())
        }
    }

    private suspend fun doSwipe(backend: MobileBackendKind, params: JSONObject): JSONObject {
        // 1. 双指缩放模式（pinch / scale）
        if (params.has("scale") || params.optBoolean("pinch", false)) {
            val svc = SolbraidAccessibilityService.instance
                ?: throw IllegalStateException("双指缩放（pinch）需要开启「Solbraid 无障碍服务」以注入真多点触控手势")
            val (sw, sh) = currentScreenSize()
            val cx = params.optInt("cx", sw / 2)
            val cy = params.optInt("cy", sh / 2)
            val scale = params.optDouble("scale", 1.5).coerceIn(0.25, 4.0)
            val baseDist = (minOf(sw, sh) * 0.28).toInt()
            val startDist = if (scale >= 1.0) baseDist else (baseDist * 1.8).toInt()
            val endDist = if (scale >= 1.0) (baseDist * scale.coerceAtMost(2.5)).toInt() else (baseDist * scale).toInt()
            val paths = MobileUseLogic.computePinchCoords(cx, cy, startDist, endDist, sw, sh)
            val dur = params.optLong("durationMs", 380L)
            ensureMutationAllowed(backend)
            val ok = svc.performPinch(paths[0], paths[1], dur, ::ensureOperationActive)
            return JSONObject().put("ok", ok).put("backend", "accessibility").put("gesture", "pinch").put("scale", scale)
        }

        // 2. 自动滚动直至找到指定文本（scrollUntilText）
        val scrollTarget = params.optString("scrollUntilText").trim()
        if (scrollTarget.isNotEmpty()) {
            val dir = params.optString("direction", "up").trim()
            val maxScrolls = params.optInt("maxScrolls", 8).coerceIn(1, 20)
            val (sw, sh) = currentScreenSize()
            val coords = MobileUseLogic.computeSwipeCoords(dir, sw, sh, params.optDouble("distanceRatio", 0.55).toFloat())
            var foundNode: UiTreeNode? = null
            var scrolled = 0
            for (step in 0..maxScrolls) {
                ensureOperationActive()
                val (_, nodes) = dumpNodesRaw(backend, 180)
                lastUiNodes = nodes
                val hit = MobileUseLogic.findMatchingNodes(nodes, text = scrollTarget).firstOrNull()
                if (hit != null) {
                    foundNode = hit
                    break
                }
                if (step < maxScrolls) {
                    performRawSwipe(backend, coords[0], coords[1], coords[2], coords[3], params.optLong("durationMs", 360L), drag = false)
                    scrolled++
                    delay(450)
                }
            }
            return JSONObject()
                .put("ok", foundNode != null)
                .put("backend", backend.id)
                .put("found", foundNode != null)
                .put("scrolls", scrolled)
                .apply { if (foundNode != null) put("matchedNode", foundNode.toJson()) }
        }

        val durationMs = params.optLong("durationMs", 320L).coerceIn(80L, 3000L)
        val drag = params.optBoolean("drag", false)
        val hasExplicit = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
        val coords = if (hasExplicit) {
            intArrayOf(params.getInt("x1"), params.getInt("y1"), params.getInt("x2"), params.getInt("y2"))
        } else {
            val dir = params.optString("direction").trim()
            if (dir.isEmpty()) throw IllegalArgumentException("滑动操作需提供 direction (up/down/left/right)、scrollUntilText、scale 或完整坐标 (x1,y1,x2,y2)")
            val ratio = params.optDouble("distanceRatio", 0.48).toFloat()
            val (sw, sh) = currentScreenSize()
            MobileUseLogic.computeSwipeCoords(dir, sw, sh, ratio)
        }
        val (x1, y1, x2, y2) = coords
        val ok = performRawSwipe(backend, x1, y1, x2, y2, durationMs, drag)
        return JSONObject()
            .put("ok", ok)
            .put("backend", backend.id)
            .put("drag", drag)
            .put("from", JSONArray(listOf(x1, y1)))
            .put("to", JSONArray(listOf(x2, y2)))
            .put("durationMs", durationMs)
    }

    private suspend fun performRawSwipe(
        backend: MobileBackendKind,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        drag: Boolean,
    ): Boolean = when (backend) {
        MobileBackendKind.ACCESSIBILITY -> {
            val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
            ensureMutationAllowed(backend)
            if (drag) svc.performDrag(x1, y1, x2, y2, holdMs = 450L, moveMs = durationMs, beforeAction = ::ensureOperationActive)
            else svc.performSwipe(x1, y1, x2, y2, durationMs, ::ensureOperationActive)
        }
        MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> {
            val cmd = if (drag) "input draganddrop $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(500L)}" else "input swipe $x1 $y1 $x2 $y2 $durationMs"
            val res = privilegedExec(backend, cmd, timeoutSec = 15, mutating = true)
            if (res == null || res.exitCode != 0) throw IllegalStateException("${backend.label} 滑动失败：${res?.stdout}")
            true
        }
        MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
    }

    private suspend fun doType(backend: MobileBackendKind, params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw IllegalArgumentException("输入文本不能为空")
        val clearFirst = params.optBoolean("clearFirst", false)
        val submit = params.optBoolean("submit", false)
        val isPureSafeAscii = text.all { it.code in 0x20..0x7E && it != '\'' && it != '"' && it != '\\' && it != '$' && it != '`' }

        var methodUsed = ""
        ensureMutationAllowed(backend)
        val ok = when (backend) {
            MobileBackendKind.ACCESSIBILITY -> {
                val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
                methodUsed = "accessibility_set_text"
                svc.performTextInput(text, clearFirst, submit, ::ensureOperationActive)
            }
            MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> {
                val a11y = SolbraidAccessibilityService.instance
                if (a11y != null && !isPureSafeAscii && a11y.performTextInput(text, clearFirst, submit, ::ensureOperationActive)) {
                    methodUsed = "accessibility_unicode_assist"
                    true
                } else {
                    if (clearFirst) {
                        val select = privilegedExec(backend, "input keycombination 113 29", timeoutSec = 10, mutating = true)
                        check(select != null && select.exitCode == 0) { "无法选择已有文本，请手动清空输入框" }
                        privilegedExec(backend, "input keyevent 67", timeoutSec = 10, mutating = true)
                    }
                    if (isPureSafeAscii) {
                        val escaped = text.replace(" ", "%s")
                        val res = privilegedExec(backend, "input text ${shQuote(escaped)}", timeoutSec = 15, mutating = true)
                        if (res == null || res.exitCode != 0) throw IllegalStateException("${backend.label} 输入文本失败：${res?.stdout}")
                        methodUsed = "${backend.id}_input_text"
                    } else {
                        writeClipboardInternal(text)
                        delay(90)
                        val res = privilegedExec(backend, "input keyevent 279", timeoutSec = 10, mutating = true)
                        if (res == null || res.exitCode != 0) throw IllegalStateException("${backend.label} 粘贴中文文本失败：${res?.stdout}")
                        methodUsed = "${backend.id}_clipboard_paste"
                    }
                    if (submit) {
                        delay(60)
                        privilegedExec(backend, "input keyevent 66", timeoutSec = 10, mutating = true)
                    }
                    true
                }
            }
            MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
        }
        return JSONObject()
            .put("ok", ok)
            .put("backend", backend.id)
            .put("method", methodUsed)
            .put("length", text.length)
            .put("submitted", submit)
    }

    private suspend fun doKey(backend: MobileBackendKind, params: JSONObject): JSONObject {
        val rawKey = params.optString("key").trim()
        if (rawKey.isEmpty()) throw IllegalArgumentException("请指定要按下的系统按键（如 back / home / recents / enter）")
        val canonical = MobileUseLogic.normalizeKeyName(rawKey)
        val ok = when (backend) {
            MobileBackendKind.ACCESSIBILITY -> {
                val svc = SolbraidAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未连接")
                ensureMutationAllowed(backend)
                val handled = svc.performSystemKey(canonical, ::ensureOperationActive)
                if (!handled) {
                    throw IllegalStateException("无障碍模式不支持按键「$rawKey」（支持 back / home / recents / notifications / quick_settings / power / screenshot）")
                }
                true
            }
            MobileBackendKind.ROOT, MobileBackendKind.SHIZUKU -> {
                val cmd = when (canonical) {
                    "notifications" -> "cmd statusbar expand-notifications"
                    "quick_settings" -> "cmd statusbar expand-settings"
                    else -> {
                        val code = MobileUseLogic.toAndroidKeyCode(canonical)
                            ?: throw IllegalArgumentException("无法识别的按键名称：$rawKey")
                        "input keyevent $code"
                    }
                }
                val res = privilegedExec(backend, cmd, timeoutSec = 10, mutating = true)
                if (res == null || res.exitCode != 0) throw IllegalStateException("${backend.label} 按键执行失败：${res?.stdout}")
                true
            }
            MobileBackendKind.NONE -> throw IllegalStateException("手机操控驱动未就绪")
        }
        return JSONObject()
            .put("ok", ok)
            .put("backend", backend.id)
            .put("key", canonical)
    }

    private fun resolveLaunchPackage(params: JSONObject): String {
        val explicit = params.optString("packageName").trim()
        if (explicit.isNotEmpty()) return explicit
        val name = params.optString("name").trim()
        require(name.isNotEmpty()) { "请提供 packageName 或 name 以启动应用" }
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities.firstOrNull { it.loadLabel(pm).toString().equals(name, ignoreCase = true) }?.activityInfo?.packageName
            ?: activities.firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }?.activityInfo?.packageName
            ?: error("未找到名称匹配「$name」的已安装应用")
    }

    private suspend fun doApp(backend: MobileBackendKind, params: JSONObject): JSONObject {
        val op = params.optString("operation", "list").trim().lowercase()
        val pm = app.packageManager
        when (op) {
            "foreground" -> {
                val pkg = detectForegroundPackage(backend)
                val label = pkg.takeIf { it.isNotEmpty() }?.let {
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
                }.orEmpty()
                return JSONObject()
                    .put("ok", true)
                    .put("backend", backend.id)
                    .put("packageName", pkg)
                    .put("label", label)
            }
            "list" -> {
                val query = params.optString("query").trim()
                val limit = params.optInt("limit", 60).coerceIn(1, 200)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
                val seen = HashSet<String>()
                val items = resolved.mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (!seen.add(pkg)) return@mapNotNull null
                    val label = ri.loadLabel(pm).toString()
                    if (query.isNotEmpty() && !pkg.contains(query, ignoreCase = true) && !label.contains(query, ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    pkg to label
                }.sortedBy { it.second.lowercase() }.take(limit)
                val arr = JSONArray()
                items.forEach { (pkg, label) -> arr.put(JSONObject().put("packageName", pkg).put("label", label)) }
                return JSONObject().put("ok", true).put("count", items.size).put("apps", arr)
            }
            "launch" -> {
                val pkg = resolveLaunchPackage(params)
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    ensureMutationAllowed(backend)
                    withContext(Dispatchers.Main) { ensureOperationActive(); app.startActivity(launchIntent) }
                    return JSONObject().put("ok", true).put("method", "android_intent").put("packageName", pkg)
                }
                if (backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU) {
                    val res = privilegedExec(backend, "monkey -p ${shQuote(pkg)} -c android.intent.category.LAUNCHER 1", timeoutSec = 15, mutating = true)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("启动应用失败：$pkg")
                    return JSONObject().put("ok", true).put("method", "${backend.id}_monkey").put("packageName", pkg)
                }
                throw IllegalStateException("无法获取应用启动入口：$pkg")
            }
            "info" -> {
                val pkg = params.optString("packageName").trim()
                require(pkg.isNotEmpty()) { "info 操作必须提供 packageName" }
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                }
                val ai = pi.applicationInfo
                val perms = JSONArray()
                pi.requestedPermissions?.take(60)?.forEach { perms.put(it) }
                return JSONObject()
                    .put("ok", true)
                    .put("packageName", pkg)
                    .put("label", ai?.let { pm.getApplicationLabel(it).toString() }.orEmpty())
                    .put("versionName", pi.versionName.orEmpty())
                    .put("versionCode", pi.longVersionCode)
                    .put("enabled", ai?.enabled ?: false)
                    .put("targetSdk", ai?.targetSdkVersion ?: 0)
                    .put("sourceDir", ai?.sourceDir.orEmpty())
                    .put("requestedPermissions", perms)
            }
            "open_settings" -> {
                val pkg = params.optString("packageName").trim().ifEmpty { app.packageName }
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) { app.startActivity(intent) }
                return JSONObject().put("ok", true).put("operation", "open_settings").put("packageName", pkg)
            }
            "extract_apk" -> {
                val pkg = params.optString("packageName").trim()
                require(pkg.isNotEmpty()) { "extract_apk 必须提供 packageName" }
                val src = File(pm.getApplicationInfo(pkg, 0).sourceDir)
                require(src.isFile && src.canRead()) { "无法读取应用安装包：${src.absolutePath}" }
                val guestRel = params.optString("destPath").trim().ifEmpty { "/home/agent/workspace/extracted-$pkg.apk" }
                require(guestRel.startsWith("/") && !guestRel.contains("..")) { "目标路径无效：$guestRel" }
                val dst = File(activeLinuxRootDir(), guestRel.removePrefix("/")).apply { parentFile?.mkdirs() }
                src.copyTo(dst, overwrite = true)
                dst.setReadable(true, false)
                return JSONObject().put("ok", true).put("packageName", pkg).put("guestPath", guestRel).put("bytes", dst.length())
            }
            "install" -> {
                val apkPath = params.optString("apkPath").trim()
                require(apkPath.isNotEmpty() && apkPath.endsWith(".apk") && !apkPath.contains("..")) { "请提供有效的 .apk 路径" }
                val hostFile = if (apkPath.startsWith("/sdcard/") || apkPath.startsWith("/storage/")) File(apkPath)
                else File(activeLinuxRootDir(), apkPath.removePrefix("/"))
                val src = if (hostFile.isFile) hostFile else File(apkPath)
                require(src.isFile) { "未找到 APK 文件：$apkPath" }
                val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
                if (hasPriv && params.optBoolean("silent", true)) {
                    val tmpApk = "/data/local/tmp/solbraid-inst-${System.currentTimeMillis()}.apk"
                    val staged = File(File(app.cacheDir, "self-build-apks").apply { mkdirs() }, "inst-${System.currentTimeMillis()}.apk")
                    src.copyTo(staged, overwrite = true)
                    staged.setReadable(true, false)
                    try {
                        val cmd = "cp -f ${shQuote(staged.absolutePath)} ${shQuote(tmpApk)} && pm install -r -g ${shQuote(tmpApk)}; __rc=\$?; rm -f ${shQuote(tmpApk)}; (exit \$__rc)"
                        val res = privilegedExec(backend, cmd, timeoutSec = 90, mutating = true)
                        if (res == null || res.exitCode != 0 || !res.stdout.contains("Success", ignoreCase = true)) {
                            throw IllegalStateException("静默安装失败：${res?.stdout}")
                        }
                        return JSONObject().put("ok", true).put("backend", backend.id).put("method", "pm_install").put("output", res.stdout.trim())
                    } finally {
                        runCatching { staged.delete() }
                    }
                }
                val stageDir = File(app.cacheDir, "self-build-apks").apply { mkdirs() }
                val staged = File(stageDir, src.name)
                src.copyTo(staged, overwrite = true)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", staged)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) { app.startActivity(intent) }
                return JSONObject().put("ok", true).put("method", "android_system_installer").put("apkPath", apkPath)
            }
            "uninstall" -> {
                val pkg = params.optString("packageName").trim()
                require(pkg.isNotEmpty() && pkg != app.packageName) { "请提供有效的目标 packageName（禁止卸载 Solbraid 自身）" }
                val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
                if (hasPriv && params.optBoolean("silent", true)) {
                    val res = privilegedExec(backend, "pm uninstall ${shQuote(pkg)}", timeoutSec = 30, mutating = true)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("静默卸载失败：${res?.stdout}")
                    return JSONObject().put("ok", true).put("backend", backend.id).put("packageName", pkg).put("output", res.stdout.trim())
                }
                val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) { app.startActivity(intent) }
                return JSONObject().put("ok", true).put("method", "android_uninstall_prompt").put("packageName", pkg)
            }
            "stop", "clear_data", "freeze", "unfreeze", "grant", "revoke" -> {
                val pkg = params.optString("packageName").trim()
                require(pkg.isNotEmpty()) { "请提供目标 packageName" }
                if (pkg == app.packageName && op in setOf("stop", "clear_data", "freeze")) {
                    throw IllegalStateException("禁止对 Solbraid 自身执行 $op")
                }
                val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
                if (!hasPriv) {
                    throw IllegalStateException("免 Root 无障碍模式无法静默执行 $op（请启用 Root 或接入 Shizuku 进阶驱动）")
                }
                val cmd = when (op) {
                    "stop" -> "am force-stop ${shQuote(pkg)}"
                    "clear_data" -> "pm clear ${shQuote(pkg)}"
                    "freeze" -> "pm disable-user --user 0 ${shQuote(pkg)}"
                    "unfreeze" -> "pm enable ${shQuote(pkg)}"
                    "grant", "revoke" -> {
                        val perm = params.optString("permission").trim()
                        require(Regex("^[a-zA-Z0-9._]+$").matches(perm)) { "请提供合法 Android 权限名（如 android.permission.CAMERA）" }
                        "pm $op ${shQuote(pkg)} ${shQuote(perm)}"
                    }
                    else -> error("unreachable")
                }
                val res = privilegedExec(backend, cmd, timeoutSec = 20, mutating = true)
                if (res == null || res.exitCode != 0) throw IllegalStateException("执行 $op 失败：${res?.stdout}")
                return JSONObject().put("ok", true).put("backend", backend.id).put("operation", op).put("packageName", pkg).put("output", res.stdout.trim())
            }
            else -> throw IllegalArgumentException(
                "不支持的 app 操作：$op（可选 list / launch / foreground / info / open_settings / extract_apk / install / uninstall / stop / clear_data / freeze / unfreeze / grant / revoke）",
            )
        }
    }

    private suspend fun doClipboard(params: JSONObject): JSONObject {
        val op = params.optString("operation", "read").trim().lowercase()
        return when (op) {
            "write" -> {
                val text = params.optString("text")
                writeClipboardInternal(text)
                JSONObject().put("ok", true).put("operation", "write").put("length", text.length)
            }
            "read" -> {
                val text = withContext(Dispatchers.Main) {
                    val cm = app.getSystemService(ClipboardManager::class.java)
                    val clip = cm?.primaryClip
                    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(app).toString() else ""
                }
                JSONObject().put("ok", true).put("operation", "read").put("text", text)
            }
            else -> throw IllegalArgumentException("不支持的剪贴板操作：$op（可选 read / write）")
        }
    }

    private suspend fun writeClipboardInternal(text: String) = withContext(Dispatchers.Main) {
        ensureOperationActive()
        val cm = app.getSystemService(ClipboardManager::class.java)
            ?: throw IllegalStateException("系统剪贴板服务不可用")
        cm.setPrimaryClip(ClipData.newPlainText("Solbraid Mobile-Use", text))
    }

    private fun selfUpdateWatchdog(): File = File(app.filesDir, "vm-runtime/self-update-watchdog.sh").also {
        check(it.isFile) { "自更新组件缺失，请重新准备运行环境" }
    }

    suspend fun selfBuildHostStatus(): JSONObject = withContext(Dispatchers.IO) {
        if (runtimeMode == RUNTIME_MODE_PROOT) return@withContext JSONObject().put("hasBackup", false)
        val script = selfUpdateWatchdog()
        val result = fileManager.rootDaemon.exec(androidHostCommand(
            "SOLBRAID_APP_FILES=${shQuote(app.filesDir.absolutePath)} /system/bin/sh ${shQuote(script.absolutePath)} --status",
        ))
        check(result.exitCode == 0) { "无法读取自更新状态：${result.stdout.take(300)}" }
        val data = JSONObject(result.stdout.trim())
        JSONObject().put("hasBackup", data.optBoolean("hasBackup"))
            .put("heartbeat", data.optJSONObject("heartbeat") ?: JSONObject())
            .put("watchdogStatus", data.optJSONObject("status") ?: JSONObject())
    }

    suspend fun rollbackSelfBuildApk(requireConfirmation: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        selfUpdateMutex.withLock {
            check(settings.state.value.selfBuild.enabled) { "本机 APK 自构建已关闭" }
            check(runtimeMode != RUNTIME_MODE_PROOT) { "版本回滚需要 Root 权限" }
            val script = selfUpdateWatchdog()
            if (requireConfirmation) {
                opMutex.withLock {
                    requestUserConfirmation(Approval(UUID.randomUUID().toString(), "回滚应用", app.packageName,
                        "安装上一版本并重启应用，当前任务将中断。", returnToApp = false))
                }
            }
            val result = fileManager.rootDaemon.exec(androidHostCommand(
                "SOLBRAID_APP_FILES=${shQuote(app.filesDir.absolutePath)} /system/bin/sh ${shQuote(script.absolutePath)} --spawn-rollback",
            ), retryOnDisconnect = false)
            check(result.exitCode == 0) { "启动版本回滚失败：${result.stdout.take(300)}" }
            JSONObject(result.stdout.trim())
        }
    }

    /** 在宿主校验包名与签名后，按运行模式交给 Root 看门狗或系统安装器。 */
    suspend fun installSelfBuildApk(guestApkPath: String): JSONObject = withContext(Dispatchers.IO) {
        selfUpdateMutex.withLock {
            val cfg = settings.state.value.selfBuild
            check(cfg.enabled) { "本机 APK 自构建已关闭" }
            val clean = guestApkPath.trim()
            require(clean.startsWith("/") && clean.endsWith(".apk") && !clean.contains("..")) { "无效的 APK 路径：$clean" }
            val root = activeLinuxRootDir().canonicalFile
            val src = File(root, clean.removePrefix("/")).canonicalFile
            check(src.path.startsWith(root.path + File.separator) && src.isFile) { "未在本地运行环境中找到 APK 文件：$clean" }
            val stageDir = File(app.cacheDir, "self-build-apks").apply { mkdirs() }
            val staged = File(stageDir, "solbraid-${UUID.randomUUID()}.apk")
            var handedOff = false
            try {
                src.copyTo(staged)
                val pm = app.packageManager
                val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(staged.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(staged.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                } ?: error("无法解析候选 APK，请重新构建")
                check(candidate.packageName == app.packageName) { "候选 APK 包名不匹配" }
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
                val candidateSigners = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
                check(installedSigners.isNotEmpty() && installedSigners == candidateSigners) {
                    "APK 签名与已安装版本不匹配。请使用原签名密钥重新构建。"
                }
                if (runtimeMode != RUNTIME_MODE_PROOT) {
                    val script = selfUpdateWatchdog()
                    val command = "SOLBRAID_APP_FILES=${shQuote(app.filesDir.absolutePath)} " +
                        "SOLBRAID_WATCHDOG_ENABLED=${cfg.watchdogEnabled} SOLBRAID_WATCHDOG_TIMEOUT=${cfg.watchdogTimeoutSec} " +
                        "/system/bin/sh ${shQuote(script.absolutePath)} --spawn ${shQuote(staged.absolutePath)}"
                    handedOff = true
                    val result = fileManager.rootDaemon.exec(androidHostCommand(command), retryOnDisconnect = false)
                    check(result.exitCode == 0) { "启动自更新失败：${result.stdout.take(300)}" }
                    return@withLock JSONObject(result.stdout.trim())
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", staged)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    app.startActivity(intent)
                    handedOff = true
                }
                JSONObject().put("ok", true).put("mode", "android_system_installer")
                    .put("apkPath", clean).put("stagedPath", staged.absolutePath)
                    .put("message", "已调起系统安装器，请确认更新")
            } catch (error: Exception) {
                if (!handedOff) staged.delete()
                throw error
            }
        }
    }
}
