package com.galiais.agent.engine.mobile

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import com.galiais.agent.engine.fs.RootFsDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 设备遥测与硬件/系统开关执行器（Root/Shizuku 全控 + PRoot 原生硬件/设置面板拉满）。 */
internal object MobileUseDeviceOps {

    suspend fun execute(
        app: Application,
        backend: MobileBackendKind,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val op = params.optString("operation", "telemetry").trim().lowercase()
        val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
        return when (op) {
            "telemetry", "info" -> collectTelemetry(app)
            "volume" -> handleVolume(app, params)
            "torch", "flashlight" -> handleTorch(app, params)
            "vibrate" -> handleVibrate(app, params)
            "media" -> handleMedia(app, params)
            "brightness" -> handleBrightness(app, hasPriv, params, privilegedExec)
            "auto_rotate" -> handleSystemIntSetting(
                app, hasPriv, Settings.System.ACCELEROMETER_ROTATION,
                if (params.optBoolean("enabled", true)) 1 else 0, "auto_rotate", privilegedExec,
            )
            "screen_timeout" -> {
                val sec = params.optInt("seconds", 60).coerceIn(15, 3600)
                handleSystemIntSetting(
                    app, hasPriv, Settings.System.SCREEN_OFF_TIMEOUT, sec * 1000, "screen_timeout", privilegedExec,
                )
            }
            "wifi", "data", "bluetooth", "airplane", "nfc", "dark_mode", "dnd", "wake", "sleep", "wm_size", "wm_density" ->
                handlePrivilegedToggle(app, op, backend, hasPriv, params, privilegedExec)
            else -> throw IllegalArgumentException(
                "不支持的 device 操作：$op（可选 telemetry / volume / torch / vibrate / media / brightness / auto_rotate / screen_timeout / wifi / data / bluetooth / airplane / nfc / dark_mode / dnd / wake / sleep / wm_size / wm_density）",
            )
        }
    }

    private fun collectTelemetry(app: Application): JSONObject {
        val batIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val pm = app.getSystemService(PowerManager::class.java)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)

        val audio = app.getSystemService(AudioManager::class.java)
        fun volJson(stream: Int) = JSONObject()
            .put("current", audio?.getStreamVolume(stream) ?: 0)
            .put("max", audio?.getStreamMaxVolume(stream) ?: 0)

        val brightness = runCatching {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val autoRotate = runCatching {
            Settings.System.getInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
        }.getOrDefault(false)
        val night = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        return JSONObject()
            .put("ok", true)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("screenOn", pm?.isInteractive ?: true)
            .put("powerSaveMode", pm?.isPowerSaveMode ?: false)
            .put("darkMode", night)
            .put("brightness", brightness)
            .put("autoRotate", autoRotate)
            .put("writeSettingsGranted", Settings.System.canWrite(app))
            .put("battery", JSONObject().put("percent", pct).put("charging", charging).put("temperatureC", tempC))
            .put("network", JSONObject().put("type", netType).put("vpn", vpnActive))
            .put("memoryMb", JSONObject().put("available", mi.availMem / (1024 * 1024)).put("total", mi.totalMem / (1024 * 1024)))
            .put("storageGb", JSONObject()
                .put("available", String.format("%.2f", statFs.availableBytes / 1_073_741_824.0).toDouble())
                .put("total", String.format("%.2f", statFs.totalBytes / 1_073_741_824.0).toDouble()))
            .put("volumes", JSONObject()
                .put("music", volJson(AudioManager.STREAM_MUSIC))
                .put("ring", volJson(AudioManager.STREAM_RING))
                .put("alarm", volJson(AudioManager.STREAM_ALARM))
                .put("notification", volJson(AudioManager.STREAM_NOTIFICATION)))
    }

    private fun handleVolume(app: Application, params: JSONObject): JSONObject {
        val audio = app.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("AudioManager 不可用")
        val streamName = params.optString("stream", "music").trim().lowercase()
        val stream = when (streamName) {
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notif" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> throw IllegalArgumentException("不支持的音量通道：$streamName（可选 music / ring / alarm / notification / call）")
        }
        val max = audio.getStreamMaxVolume(stream)
        val showUi = if (params.optBoolean("showUi", false)) AudioManager.FLAG_SHOW_UI else 0
        when {
            params.has("mute") -> {
                val dir = if (params.optBoolean("mute")) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audio.adjustStreamVolume(stream, dir, showUi)
            }
            params.has("percent") -> {
                val pct = params.optInt("percent").coerceIn(0, 100)
                val target = kotlin.math.round(max * pct / 100.0).toInt().coerceIn(0, max)
                audio.setStreamVolume(stream, target, showUi)
            }
            params.has("level") -> {
                val target = params.optInt("level").coerceIn(0, max)
                audio.setStreamVolume(stream, target, showUi)
            }
            params.has("delta") -> {
                val d = params.optInt("delta")
                val target = (audio.getStreamVolume(stream) + d).coerceIn(0, max)
                audio.setStreamVolume(stream, target, showUi)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("stream", streamName)
            .put("current", audio.getStreamVolume(stream))
            .put("max", max)
    }

    private fun handleTorch(app: Application, params: JSONObject): JSONObject {
        val enabled = params.optBoolean("enabled", true)
        val cm = app.getSystemService(CameraManager::class.java)
            ?: throw IllegalStateException("CameraManager 不可用")
        val camId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("设备未检测到可用闪光灯")
        cm.setTorchMode(camId, enabled)
        return JSONObject().put("ok", true).put("torch", enabled).put("cameraId", camId)
    }

    private fun handleVibrate(app: Application, params: JSONObject): JSONObject {
        val ms = params.optLong("durationMs", 150L).coerceIn(30L, 2000L)
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        } ?: throw IllegalStateException("震动马达服务不可用")
        vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return JSONObject().put("ok", true).put("durationMs", ms)
    }

    private fun handleMedia(app: Application, params: JSONObject): JSONObject {
        val audio = app.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("AudioManager 不可用")
        val cmd = params.optString("command", "play_pause").trim().lowercase()
        val code = when (cmd) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> throw IllegalArgumentException("不支持的媒体控制指令：$cmd")
        }
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 15, KeyEvent.ACTION_UP, code, 0))
        return JSONObject().put("ok", true).put("mediaCommand", cmd)
    }

    private suspend fun handleBrightness(
        app: Application,
        hasPriv: Boolean,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val auto = if (params.has("auto")) params.optBoolean("auto") else null
        val value = when {
            params.has("percent") -> kotlin.math.round(params.optInt("percent").coerceIn(0, 100) * 2.55).toInt().coerceIn(1, 255)
            params.has("value") -> params.optInt("value").coerceIn(1, 255)
            else -> null
        }
        if (hasPriv) {
            if (auto != null) {
                privilegedExec("settings put system screen_brightness_mode ${if (auto) 1 else 0}", 10)
            }
            if (value != null) {
                if (auto == null) privilegedExec("settings put system screen_brightness_mode 0", 10)
                privilegedExec("settings put system screen_brightness $value", 10)
            }
            return JSONObject().put("ok", true).put("method", "privileged").put("brightness", value).put("auto", auto)
        }
        if (!Settings.System.canWrite(app)) {
            throw IllegalStateException("免 Root 修改屏幕亮度需要授予「修改系统设置」权限，请先在「设置 → 手机操控」中一键授权")
        }
        if (auto != null) {
            Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto) 1 else 0)
        }
        if (value != null) {
            if (auto == null) Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
        return JSONObject().put("ok", true).put("method", "write_settings").put("brightness", value).put("auto", auto)
    }

    private suspend fun handleSystemIntSetting(
        app: Application,
        hasPriv: Boolean,
        key: String,
        value: Int,
        label: String,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        if (hasPriv) {
            val res = privilegedExec("settings put system $key $value", 10)
            if (res == null || res.exitCode != 0) throw IllegalStateException("写入系统设置失败：${res?.stdout}")
            return JSONObject().put("ok", true).put("method", "privileged").put(label, value)
        }
        if (!Settings.System.canWrite(app)) {
            throw IllegalStateException("免 Root 修改 $label 需要授予「修改系统设置」权限，请先在「设置 → 手机操控」授权")
        }
        Settings.System.putInt(app.contentResolver, key, value)
        return JSONObject().put("ok", true).put("method", "write_settings").put(label, value)
    }

    private suspend fun handlePrivilegedToggle(
        app: Application,
        op: String,
        backend: MobileBackendKind,
        hasPriv: Boolean,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val enabled = params.optBoolean("enabled", true)
        val onOff = if (enabled) "enable" else "disable"
        if (!hasPriv) {
            val panelAction = when (op) {
                "wifi", "data", "airplane" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
                "nfc" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "dark_mode" -> Settings.ACTION_DISPLAY_SETTINGS
                else -> null
            }
            if (panelAction != null && params.optBoolean("openPanelFallback", true)) {
                withContext(Dispatchers.Main) {
                    app.startActivity(Intent(panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return JSONObject()
                    .put("ok", true)
                    .put("backend", backend.id)
                    .put("fallback", "settings_panel")
                    .put("panelAction", panelAction)
                    .put("message", "免 Root 模式无法静默改写 $op，已弹出系统设置面板，可继续结合 UI 树与点击完成切换")
            }
            throw IllegalStateException("免 Root 无障碍模式无法静默执行 $op（请启用 Root 或接入 Shizuku 进阶驱动，或设置 openPanelFallback=true 弹出系统面板）")
        }

        val cmd = when (op) {
            "wifi" -> "svc wifi $onOff"
            "data" -> "svc data $onOff"
            "bluetooth" -> "svc bluetooth $onOff"
            "nfc" -> "svc nfc $onOff"
            "airplane" -> "cmd connectivity airplane-mode $onOff"
            "dark_mode" -> "cmd uimode night ${if (enabled) "yes" else "no"}"
            "dnd" -> "cmd notification set_dnd ${if (enabled) "on" else "off"}"
            "wake" -> "input keyevent 224"
            "sleep" -> "input keyevent 223"
            "wm_size" -> {
                val spec = params.optString("value").trim()
                require(spec == "reset" || Regex("^\\d{3,4}x\\d{3,4}$").matches(spec)) { "wm_size 须为 WxH（如 1080x2400）或 reset" }
                "wm size $spec"
            }
            "wm_density" -> {
                val spec = params.optString("value").trim()
                require(spec == "reset" || (spec.toIntOrNull() in 120..800)) { "wm_density 须为 120–800 整数或 reset" }
                "wm density $spec"
            }
            else -> throw IllegalArgumentException("未知操作：$op")
        }
        val res = privilegedExec(cmd, 15)
        if (res == null || res.exitCode != 0) {
            throw IllegalStateException("执行 $op 失败：${res?.stdout?.ifBlank { "exit=${res.exitCode}" }}")
        }
        return JSONObject()
            .put("ok", true)
            .put("backend", backend.id)
            .put("operation", op)
            .put("output", res.stdout.trim())
    }
}
