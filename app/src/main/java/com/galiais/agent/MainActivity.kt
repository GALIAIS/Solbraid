package com.galiais.agent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.toArgb
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisApp
import com.galiais.agent.ui.GaliaisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 首帧渲染完成后原子写入启动健康心跳，供本机 APK 自迭代启动看门狗确认新版本正常存活。 */
    private suspend fun writeSelfUpdateHealthHeartbeat() = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("pid", android.os.Process.myPid())
                .put("ts", System.currentTimeMillis())
                .toString() + "\n"
            val target = File(filesDir, "self-update-healthy.json")
            val tmp = File(filesDir, "self-update-healthy.json.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                target.setReadable(true, false)
                tmp.delete()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as GaliaisApplication
        // 已安装环境：每次打开应用按设置自动启动；若后台仍有常驻引擎则自动重新挂接（旋转等配置变更不重复触发）。
        if (savedInstanceState == null) app.runtime.requestAutoStart()
        setContent {
            val settings by app.settings.state.collectAsState()
            val theme = settings.theme
            val dark = when (theme.mode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            GaliaisTheme(config = theme, dark = dark) {
                // 实时同步原生 Window 底色为当前主题画布色，彻底杜绝转场与切换主题时闪白。
                val canvasArgb = G.canvas.toArgb()
                LaunchedEffect(canvasArgb) {
                    window.setBackgroundDrawable(ColorDrawable(canvasArgb))
                    window.decorView.setBackgroundColor(canvasArgb)
                }
                GaliaisApp(app)
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    writeSelfUpdateHealthHeartbeat()
                }
            }
        }
    }
}
