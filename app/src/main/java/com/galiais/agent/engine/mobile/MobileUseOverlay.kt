package com.galiais.agent.engine.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.galiais.agent.MainActivity
import com.galiais.agent.R

/**
 * Mobile-Use 实时视觉指示与一键紧急停止守护器：
 * - 优先复用 `SolbraidAccessibilityService` 的 `TYPE_ACCESSIBILITY_OVERLAY`（免悬浮窗权限）；
 * - Root 单独模式下，若用户授予了 `SYSTEM_ALERT_WINDOW`，按 Android 11+ `createWindowContext` 规范挂载 `TYPE_APPLICATION_OVERLAY` 急停胶囊；
 * - 无论何种模式，始终同步挂出带「紧急停止」按钮的通知；待审批场景自动切换高优先级审批通道（Heads-up）。
 */
internal class MobileUseGuardUi(
    private val app: Application,
    private val onEmergencyStop: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appOverlayView: LinearLayout? = null
    private var appOverlayLabel: TextView? = null
    private var appOverlayWm: WindowManager? = null
    private var channelReady = false
    private val hideRunnable = Runnable { hideAll() }

    private fun ensureChannel() {
        if (channelReady) return
        channelReady = true
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 手机操控保护", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI 正在操作手机界面时提供实时提示与紧急停止入口"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_APPROVAL_ID, "AI 手机高危操作审批", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "敏感或高危手机操控需要您确认时立即弹窗提醒"
                },
            )
        }
    }

    fun showActive(actionLabel: String, showPill: Boolean, autoHideDelayMs: Long = 0L, isApproval: Boolean = false) {
        mainHandler.removeCallbacks(hideRunnable)
        val pillText = "AI $actionLabel · 点此停止"
        val a11y = SolbraidAccessibilityService.instance
        if (showPill) {
            if (a11y != null) {
                removeAppOverlayNow()
                a11y.updateFloatingStopPill(true, pillText, onEmergencyStop)
            } else if (Settings.canDrawOverlays(app)) {
                showAppOverlay(pillText)
            }
        } else {
            a11y?.updateFloatingStopPill(false, "", onEmergencyStop)
            removeAppOverlayNow()
        }
        postGuardNotification(actionLabel, stopped = false, isApproval = isApproval)
        if (autoHideDelayMs > 0L) {
            mainHandler.postDelayed(hideRunnable, autoHideDelayMs)
        }
    }

    fun showStoppedNotice() {
        mainHandler.removeCallbacks(hideRunnable)
        SolbraidAccessibilityService.instance?.updateFloatingStopPill(false, "", onEmergencyStop)
        removeAppOverlayNow()
        postGuardNotification("已紧急停止 AI 手机操控", stopped = true, isApproval = false)
    }

    fun hideAll() {
        mainHandler.removeCallbacks(hideRunnable)
        SolbraidAccessibilityService.instance?.updateFloatingStopPill(false, "", onEmergencyStop)
        removeAppOverlayNow()
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private fun postGuardNotification(label: String, stopped: Boolean, isApproval: Boolean) {
        ensureChannel()
        runCatching {
            val openIntent = PendingIntent.getActivity(
                app,
                401,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val channel = if (isApproval && !stopped) CHANNEL_APPROVAL_ID else CHANNEL_ID
            val priority = if (isApproval && !stopped) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
            val builder = NotificationCompat.Builder(app, channel)
                .setSmallIcon(R.drawable.ic_notif_runtime)
                .setContentTitle(
                    when {
                        stopped -> "AI 手机操控已熔断"
                        isApproval -> "Solbraid 请求高危手机操作确认"
                        else -> "Solbraid 正在操控手机"
                    },
                )
                .setContentText(label)
                .setContentIntent(openIntent)
                .setOngoing(!stopped)
                .setAutoCancel(stopped)
                .setPriority(priority)
            if (!stopped) {
                val stopPi = PendingIntent.getBroadcast(
                    app,
                    402,
                    Intent(app, MobileUseStopReceiver::class.java).setAction(MobileUseStopReceiver.ACTION_EMERGENCY_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "紧急停止", stopPi)
            }
            app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun overlayWindowContext(): android.content.Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = app.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(app)
            }
        }
        return app
    }

    private fun showAppOverlay(label: String) {
        mainHandler.post {
            val existing = appOverlayView
            if (existing != null && appOverlayLabel != null) {
                appOverlayLabel?.text = label
                existing.setOnClickListener { onEmergencyStop() }
                return@post
            }
            val winCtx = overlayWindowContext()
            val wm = winCtx.getSystemService(WindowManager::class.java) ?: app.getSystemService(WindowManager::class.java) ?: return@post
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, winCtx.resources.displayMetrics).toInt() }
            val container = LinearLayout(winCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E61C1917"))
                    cornerRadius = dp(24f).toFloat()
                    setStroke(dp(1f), Color.parseColor("#FFEF4444"))
                }
                elevation = dp(8f).toFloat()
                setOnClickListener { onEmergencyStop() }
            }
            val dot = TextView(winCtx).apply {
                text = "● "
                setTextColor(Color.parseColor("#FFEF4444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val tv = TextView(winCtx).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            container.addView(dot)
            container.addView(tv)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12f)
                y = dp(48f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            runCatching {
                wm.addView(container, lp)
                appOverlayWm = wm
                appOverlayView = container
                appOverlayLabel = tv
            }
        }
    }

    private fun removeAppOverlayNow() {
        mainHandler.post {
            val view = appOverlayView ?: return@post
            val wm = appOverlayWm ?: app.getSystemService(WindowManager::class.java)
            appOverlayView = null
            appOverlayLabel = null
            appOverlayWm = null
            runCatching { wm?.removeViewImmediate(view) }
        }
    }

    companion object {
        private const val CHANNEL_ID = "mobile-use-guard"
        private const val CHANNEL_APPROVAL_ID = "mobile-use-approval"
        private const val NOTIFICATION_ID = 41
    }
}
