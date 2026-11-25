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
import androidx.compose.ui.graphics.toArgb
import com.galiais.agent.ui.G
import com.galiais.agent.ui.GaliaisApp
import com.galiais.agent.ui.GaliaisTheme

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
            }
        }
    }
}
