package com.galiais.agent.engine.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Solbraid 内置无障碍服务（PRoot / 免 Root 手机操控核心引擎）：
 * - Android 11+ (API 30+) 原生 `takeScreenshot(DEFAULT_DISPLAY)` 零弹窗截屏；
 * - 毫秒级 `rootInActiveWindow` UI 节点树读取；
 * - `dispatchGesture` 精准点击 / 长按 / 滑动；
 * - `ACTION_SET_TEXT` 原生中文与 Emoji 文本输入；
 * - `TYPE_ACCESSIBILITY_OVERLAY` 悬浮「AI 操控中 · 紧急停止」胶囊（免额外悬浮窗权限）。
 */
class SolbraidAccessibilityService : AccessibilityService() {

    data class ScreenshotBytes(
        val jpeg: ByteArray,
        val screenWidth: Int,
        val screenHeight: Int,
        val width: Int,
        val height: Int,
    )

    data class UiTreeDump(
        val packageName: String,
        val nodes: List<UiTreeNode>,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { cmd -> mainHandler.post(cmd) }
    private var overlayView: LinearLayout? = null
    private var overlayLabelView: TextView? = null
    @Volatile private var lastWindowPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedFlow.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim()
        if (!pkg.isNullOrEmpty()) lastWindowPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeFloatingPillNow()
        if (instance === this) instance = null
        connectedFlow.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeFloatingPillNow()
        if (instance === this) instance = null
        connectedFlow.value = false
        super.onDestroy()
    }

    fun currentForegroundPackage(): String {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString()?.trim() }.getOrNull()
        return rootPkg?.takeIf { it.isNotEmpty() } ?: lastWindowPackage
    }

    /** Android 11+ 原生无障碍截屏并返回原始软位图（供裁剪与 SoM 标注）。 */
    suspend fun captureScreenshotBitmap(): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalStateException("免 Root 无障碍截屏需要 Android 11 及以上系统")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                            ?: throw IllegalStateException("无法解码系统截屏帧缓冲")
                        val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()
                        buffer.close()
                        if (cont.isActive) cont.resume(softBitmap) else softBitmap.recycle()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val reason = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏过于频繁（系统限制约 1 秒 1 次），请稍候重试"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍截屏权限未生效，请重新开关无障碍服务"
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前窗口受 FLAG_SECURE 隐私保护，系统禁止截屏"
                        else -> "无障碍截屏失败（错误码 $errorCode）"
                    }
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(reason))
                }
            })
        }
    }

    /** Android 11+ 原生无障碍截屏（无需 Root、无需每次录屏授权弹窗）。 */
    suspend fun captureScreenshot(maxWidth: Int, quality: Int): ScreenshotBytes {
        val softBitmap = captureScreenshotBitmap()
        val origW = softBitmap.width
        val origH = softBitmap.height
        val scaled = if (origW > maxWidth) {
            val targetH = ((origH.toFloat() * maxWidth) / origW).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(softBitmap, maxWidth, targetH, true).also {
                if (it !== softBitmap) softBitmap.recycle()
            }
        } else {
            softBitmap
        }
        val outW = scaled.width
        val outH = scaled.height
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), baos)
        scaled.recycle()
        return ScreenshotBytes(baos.toByteArray(), origW, origH, outW, outH)
    }

    /** 毫秒级遍历当前活动窗口（可选遍历全部交互窗口，含输入法、弹窗、画中画与分屏）返回统一精简节点列表。 */
    suspend fun dumpUiTree(maxNodes: Int = 160, allWindows: Boolean = false): UiTreeDump = withContext(Dispatchers.Main) {
        val activeRoot = rootInActiveWindow
        val roots = ArrayList<AccessibilityNodeInfo>()
        if (allWindows) {
            val winList = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
                .sortedByDescending { it.layer }
            for (win in winList) {
                if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val r = runCatching { win.root }.getOrNull() ?: continue
                if (roots.none { it == r }) roots += r
            }
        }
        if (roots.isEmpty()) {
            val fallback = activeRoot
                ?: throw IllegalStateException("无法获取当前活动窗口，请确保屏幕已亮起且无障碍服务处于开启状态")
            roots += fallback
        }
        val nodes = ArrayList<UiTreeNode>(minOf(maxNodes, 160))
        val pkg = activeRoot?.packageName?.toString()?.trim().orEmpty()
            .ifEmpty { roots.firstOrNull()?.packageName?.toString()?.trim().orEmpty() }
            .ifEmpty { lastWindowPackage }
        val rect = Rect()
        var idx = 0

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 48 || nodes.size >= maxNodes) return
            if (!node.isVisibleToUser) return
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val resId = node.viewIdResourceName?.trim().orEmpty().substringAfterLast('/')
            val cls = node.className?.toString()?.trim().orEmpty()
            val nodePkg = node.packageName?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable || cls.contains("EditText", ignoreCase = true)
            val scrollable = node.isScrollable
            @Suppress("DEPRECATION")
            val checked = node.isChecked
            val focused = node.isFocused
            val enabled = node.isEnabled
            val validRect = rect.right > rect.left && rect.bottom > rect.top
            if (validRect && (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable || focused)) {
                nodes += UiTreeNode(
                    index = idx++,
                    text = text.take(160),
                    contentDesc = desc.take(160),
                    resourceId = resId,
                    className = cls,
                    packageName = nodePkg,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    checked = checked,
                    focused = focused,
                    enabled = enabled,
                )
            }
            val count = node.childCount
            for (i in 0 until count) {
                if (nodes.size >= maxNodes) break
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }

        for (r in roots) {
            if (nodes.size >= maxNodes) break
            visit(r, 0)
        }
        UiTreeDump(packageName = pkg, nodes = nodes)
    }

    suspend fun performTap(x: Int, y: Int, durationMs: Long = 80L, beforeAction: () -> Unit = {}): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 2500L)))
            .build()
        return dispatchGestureAwait(gesture, beforeAction)
    }

    suspend fun performDoubleTap(x: Int, y: Int, beforeAction: () -> Unit = {}): Boolean {
        val p1 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val p2 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 55L))
            .addStroke(GestureDescription.StrokeDescription(p2, 120L, 55L))
            .build()
        return dispatchGestureAwait(gesture, beforeAction)
    }

    suspend fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320L, beforeAction: () -> Unit = {}): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 2500L)))
            .build()
        return dispatchGestureAwait(gesture, beforeAction)
    }

    /** 长按后平滑拖拽（如拖动滑块、排序卡片或图标）。 */
    suspend fun performDrag(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        holdMs: Long = 450L,
        moveMs: Long = 420L,
        beforeAction: () -> Unit = {},
    ): Boolean {
        val hold = holdMs.coerceIn(150L, 2000L)
        val move = moveMs.coerceIn(120L, 2500L)
        val pHold = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
        val pMove = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val s1 = GestureDescription.StrokeDescription(pHold, 0L, hold, true)
        val s2 = s1.continueStroke(pMove, hold, move, false)
        if (!dispatchGestureAwait(GestureDescription.Builder().addStroke(s1).build(), beforeAction)) return false
        return dispatchGestureAwait(GestureDescription.Builder().addStroke(s2).build(), beforeAction)
    }

    /** 真双指缩放手势（地图、相册、网页放大缩小）。 */
    suspend fun performPinch(
        finger1: IntArray,
        finger2: IntArray,
        durationMs: Long = 380L,
        beforeAction: () -> Unit = {},
    ): Boolean {
        val dur = durationMs.coerceIn(120L, 2000L)
        val p1 = Path().apply {
            moveTo(finger1[0].toFloat(), finger1[1].toFloat())
            lineTo(finger1[2].toFloat(), finger1[3].toFloat())
        }
        val p2 = Path().apply {
            moveTo(finger2[0].toFloat(), finger2[1].toFloat())
            lineTo(finger2[2].toFloat(), finger2[3].toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, dur))
            .addStroke(GestureDescription.StrokeDescription(p2, 0L, dur))
            .build()
        return dispatchGestureAwait(gesture, beforeAction)
    }

    /** 通过无障碍语义动作直接操作指定 UI 节点（即使控件部分被遮挡也可命中）。 */
    suspend fun performNodeAction(
        target: UiTreeNode,
        actionName: String = "click",
        beforeAction: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main) {
        beforeAction()
        val root = rootInActiveWindow ?: return@withContext false
        val rect = Rect()
        fun findMatch(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 48) return null
            node.getBoundsInScreen(rect)
            val boundsClose = kotlin.math.abs(rect.left - target.left) <= 6 &&
                kotlin.math.abs(rect.top - target.top) <= 6 &&
                kotlin.math.abs(rect.right - target.right) <= 6 &&
                kotlin.math.abs(rect.bottom - target.bottom) <= 6
            val textSame = target.text.isEmpty() || node.text?.toString()?.trim() == target.text
            if (boundsClose && textSame) return node
            for (i in 0 until node.childCount) {
                val hit = findMatch(node.getChild(i), depth + 1)
                if (hit != null) return hit
            }
            return null
        }
        var cur = findMatch(root, 0) ?: return@withContext false
        val actionId = when (actionName.lowercase()) {
            "long_click", "longpress" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
            else -> AccessibilityNodeInfo.ACTION_CLICK
        }
        var hops = 0
        while (actionId == AccessibilityNodeInfo.ACTION_CLICK && !cur.isClickable && cur.parent != null && hops < 5) {
            cur = cur.parent
            hops++
        }
        cur.performAction(actionId)
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription, beforeAction: () -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            val dispatch = Runnable {
                if (!cont.isActive) return@Runnable
                try { beforeAction() } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return@Runnable
                }
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, mainHandler)
                if (!accepted && cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { mainHandler.removeCallbacks(dispatch) }
            mainHandler.post(dispatch)
        }

    /** 原生中文/Unicode 文本输入：自动寻找当前聚焦或可见可编辑输入框并执行 ACTION_SET_TEXT。 */
    suspend fun performTextInput(text: String, clearFirst: Boolean, submit: Boolean, beforeAction: () -> Unit = {}): Boolean =
        withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
                ?: findFirstEditable(root, 0)
                ?: return@withContext false

            if (!target.isFocused) {
                beforeAction()
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                beforeAction()
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val finalText = if (clearFirst) text else {
                val cur = target.text?.toString().orEmpty()
                if (cur.isEmpty()) text else cur + text
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            beforeAction()
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                beforeAction()
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            ok
        }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 36) return null
        if (node.isVisibleToUser && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val hit = findFirstEditable(node.getChild(i), depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    suspend fun performSystemKey(canonicalKey: String, beforeAction: () -> Unit = {}): Boolean = withContext(Dispatchers.Main) {
        val action = when (MobileUseLogic.normalizeKeyName(canonicalKey)) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return@withContext false
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return@withContext false
            else -> return@withContext false
        }
        beforeAction()
        performGlobalAction(action)
    }

    /** 在任意 App 顶层显示/隐藏无障碍悬浮「AI 操控中 · 停止」急停胶囊（无需 SYSTEM_ALERT_WINDOW 权限）。 */
    fun updateFloatingStopPill(visible: Boolean, label: String, onStopClick: () -> Unit) {
        mainHandler.post {
            if (!visible) {
                removeFloatingPillNow()
                return@post
            }
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val existing = overlayView
            if (existing != null && overlayLabelView != null) {
                overlayLabelView?.text = label
                existing.setOnClickListener { onStopClick() }
                return@post
            }
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E61C1917"))
                    cornerRadius = dp(24f).toFloat()
                    setStroke(dp(1f), Color.parseColor("#FFEF4444"))
                }
                elevation = dp(8f).toFloat()
                setOnClickListener { onStopClick() }
            }
            val dot = TextView(this).apply {
                text = "● "
                setTextColor(Color.parseColor("#FFEF4444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            container.addView(dot)
            container.addView(tv)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12f)
                y = dp(48f)
            }
            runCatching {
                wm.addView(container, lp)
                overlayView = container
                overlayLabelView = tv
            }
        }
    }

    private fun removeFloatingPillNow() {
        val view = overlayView ?: return
        overlayView = null
        overlayLabelView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    companion object {
        @Volatile var instance: SolbraidAccessibilityService? = null
            private set

        private val connectedFlow = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

        fun isEnabledInSystemSettings(context: Context): Boolean {
            val expected = ComponentName(context, SolbraidAccessibilityService::class.java).flattenToString()
            val expectedShort = ComponentName(context, SolbraidAccessibilityService::class.java).flattenToShortString()
            val enabled = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull().orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true) }
        }
    }
}
