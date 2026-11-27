package com.galiais.agent.engine.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.galiais.agent.engine.fs.RootFsDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 跨应用直达（URL/Deep Link/设置子页/闹钟/分享/拨号与短信草稿 + Root/Shizuku 任意组件与广播）。 */
internal object MobileUseIntentOps {

    suspend fun execute(
        app: Application,
        backend: MobileBackendKind,
        activeLinuxRootDir: File,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val op = params.optString("operation", "open_url").trim().lowercase()
        val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
        return when (op) {
            "open_url", "deep_link" -> openUrl(app, params)
            "open_settings" -> openSettings(app, params)
            "dial" -> openDial(app, params)
            "sms_draft" -> openSmsDraft(app, params)
            "email_draft" -> openEmailDraft(app, params)
            "set_alarm" -> setAlarm(app, params)
            "set_timer" -> setTimer(app, params)
            "share_file" -> shareFile(app, activeLinuxRootDir, params)
            "start_activity" -> startActivity(app, backend, hasPriv, params, privilegedExec)
            "broadcast" -> sendBroadcastOp(backend, hasPriv, params, privilegedExec)
            else -> throw IllegalArgumentException(
                "不支持的 intent 操作：$op（可选 open_url / open_settings / dial / sms_draft / email_draft / set_alarm / set_timer / share_file / start_activity / broadcast）",
            )
        }
    }

    private suspend fun launchIntent(app: Application, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { app.startActivity(intent) }
    }

    private suspend fun openUrl(app: Application, params: JSONObject): JSONObject {
        val url = params.optString("url").trim()
        require(url.isNotEmpty() && "://" in url || url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("geo:")) {
            "请提供合法的 URL 或 URI Scheme（如 https://...、weixin://、geo:31.2,121.5）"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pkg = params.optString("packageName").trim()
        if (pkg.isNotEmpty()) intent.setPackage(pkg)
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "open_url").put("url", url).put("packageName", pkg)
    }

    private suspend fun openSettings(app: Application, params: JSONObject): JSONObject {
        val page = params.optString("page", "main").trim().lowercase()
        val pkg = params.optString("packageName").trim().ifEmpty { app.packageName }
        val intent = when (page) {
            "main", "settings" -> Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "panel_internet" -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
            "panel_volume" -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            "panel_nfc" -> Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS)
            else -> throw IllegalArgumentException("未知系统设置页：$page")
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "open_settings").put("page", page)
    }

    private suspend fun openDial(app: Application, params: JSONObject): JSONObject {
        val phone = params.optString("phone").trim()
        require(phone.isNotEmpty()) { "请提供电话号码 phone" }
        launchIntent(app, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
        return JSONObject().put("ok", true).put("operation", "dial").put("phone", phone)
    }

    private suspend fun openSmsDraft(app: Application, params: JSONObject): JSONObject {
        val phone = params.optString("phone").trim()
        val body = params.optString("body")
        require(phone.isNotEmpty()) { "请提供收件人号码 phone" }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
            putExtra("sms_body", body)
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "sms_draft").put("phone", phone)
    }

    private suspend fun openEmailDraft(app: Application, params: JSONObject): JSONObject {
        val to = params.optString("to").trim()
        val subject = params.optString("subject")
        val body = params.optString("body")
        require(to.isNotEmpty()) { "请提供收件人邮箱 to" }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "email_draft").put("to", to)
    }

    private suspend fun setAlarm(app: Application, params: JSONObject): JSONObject {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", 0)
        require(hour in 0..23 && minute in 0..59) { "请提供合法的 hour (0-23) 与 minute (0-59)" }
        val msg = params.optString("message", "Solbraid 闹钟")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_SKIP_UI, params.optBoolean("skipUi", true))
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "set_alarm").put("hour", hour).put("minute", minute).put("message", msg)
    }

    private suspend fun setTimer(app: Application, params: JSONObject): JSONObject {
        val seconds = params.optInt("seconds", 0).coerceIn(1, 86400)
        val msg = params.optString("message", "Solbraid 倒计时")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_SKIP_UI, params.optBoolean("skipUi", true))
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("operation", "set_timer").put("seconds", seconds).put("message", msg)
    }

    private suspend fun shareFile(app: Application, activeLinuxRootDir: File, params: JSONObject): JSONObject {
        val rawPath = params.optString("path").trim()
        require(rawPath.isNotEmpty() && !rawPath.contains("..") && !rawPath.contains('\u0000')) { "请提供合法文件路径" }
        val sdcardCanon = android.os.Environment.getExternalStorageDirectory().canonicalFile
        val linuxCanon = activeLinuxRootDir.canonicalFile
        val src = if (rawPath.startsWith("/sdcard/") || rawPath.startsWith("/storage/")) {
            val normalized = rawPath.replace(Regex("^/sdcard(?=/|$)"), sdcardCanon.path)
            val candidate = File(normalized).canonicalFile
            require(candidate.path == sdcardCanon.path || candidate.path.startsWith(sdcardCanon.path + File.separator)) {
                "拒绝访问共享存储目录之外的文件：$rawPath"
            }
            candidate
        } else {
            val candidate = File(linuxCanon, rawPath.removePrefix("/")).canonicalFile
            require(candidate.path == linuxCanon.path || candidate.path.startsWith(linuxCanon.path + File.separator)) {
                "拒绝访问容器目录之外的文件：$rawPath"
            }
            candidate
        }
        require(src.isFile && src.canRead()) { "未找到要分享/打开的文件：$rawPath" }
        val stageDir = File(app.cacheDir, "exports").apply { mkdirs() }
        val staged = File(stageDir, src.name)
        src.copyTo(staged, overwrite = true)
        val ext = src.extension.lowercase()
        val mime = params.optString("mimeType").trim().ifEmpty {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", staged)
        val openMode = params.optBoolean("open", false)
        val intent = if (openMode) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val pkg = params.optString("packageName").trim()
        if (pkg.isNotEmpty()) intent.setPackage(pkg)
        val finalIntent = if (pkg.isEmpty() && !openMode) Intent.createChooser(intent, params.optString("title", "分享文件")) else intent
        launchIntent(app, finalIntent)
        return JSONObject().put("ok", true).put("operation", "share_file").put("file", src.name).put("mimeType", mime)
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private suspend fun startActivity(
        app: Application,
        backend: MobileBackendKind,
        hasPriv: Boolean,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val action = params.optString("action").trim()
        val component = params.optString("component").trim()
        val data = params.optString("data").trim()
        val pkg = params.optString("packageName").trim()
        val extras = params.optJSONObject("extras")

        if (hasPriv && (component.isNotEmpty() || params.optBoolean("useAm", false))) {
            val sb = StringBuilder("am start")
            if (action.isNotEmpty()) sb.append(" -a ").append(shQuote(action))
            if (component.isNotEmpty()) sb.append(" -n ").append(shQuote(component))
            if (data.isNotEmpty()) sb.append(" -d ").append(shQuote(data))
            if (pkg.isNotEmpty() && component.isEmpty()) sb.append(" -p ").append(shQuote(pkg))
            if (extras != null) {
                extras.keys().forEach { k ->
                    when (val v = extras.opt(k)) {
                        is Boolean -> sb.append(" --ez ").append(shQuote(k)).append(' ').append(v)
                        is Int -> sb.append(" --ei ").append(shQuote(k)).append(' ').append(v)
                        is Long -> sb.append(" --el ").append(shQuote(k)).append(' ').append(v)
                        else -> sb.append(" --es ").append(shQuote(k)).append(' ').append(shQuote(v?.toString().orEmpty()))
                    }
                }
            }
            val res = privilegedExec(sb.toString(), 15)
            if (res == null || res.exitCode != 0) throw IllegalStateException("am start 失败：${res?.stdout}")
            return JSONObject().put("ok", true).put("backend", backend.id).put("output", res.stdout.trim())
        }

        val intent = Intent().apply {
            if (action.isNotEmpty()) this.action = action
            if (data.isNotEmpty()) this.data = Uri.parse(data)
            if (component.isNotEmpty()) {
                val cn = ComponentName.unflattenFromString(component)
                    ?: throw IllegalArgumentException("组件名格式无效（应为 pkg/cls）：$component")
                this.component = cn
            } else if (pkg.isNotEmpty()) {
                setPackage(pkg)
            }
            if (extras != null) {
                extras.keys().forEach { k ->
                    when (val v = extras.opt(k)) {
                        is Boolean -> putExtra(k, v)
                        is Int -> putExtra(k, v)
                        is Long -> putExtra(k, v)
                        is Double -> putExtra(k, v)
                        else -> putExtra(k, v?.toString().orEmpty())
                    }
                }
            }
        }
        launchIntent(app, intent)
        return JSONObject().put("ok", true).put("backend", backend.id).put("method", "android_intent")
    }

    private suspend fun sendBroadcastOp(
        backend: MobileBackendKind,
        hasPriv: Boolean,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        if (!hasPriv) {
            throw IllegalStateException("发送系统级任意广播需要 Root 或 Shizuku 驱动")
        }
        val action = params.optString("action").trim()
        require(action.isNotEmpty()) { "broadcast 操作必须提供 action" }
        val sb = StringBuilder("am broadcast -a ").append(shQuote(action))
        val pkg = params.optString("packageName").trim()
        if (pkg.isNotEmpty()) sb.append(" -p ").append(shQuote(pkg))
        val extras = params.optJSONObject("extras")
        if (extras != null) {
            extras.keys().forEach { k ->
                val v = extras.opt(k)
                sb.append(" --es ").append(shQuote(k)).append(' ').append(shQuote(v?.toString().orEmpty()))
            }
        }
        val res = privilegedExec(sb.toString(), 15)
        if (res == null || res.exitCode != 0) throw IllegalStateException("am broadcast 失败：${res?.stdout}")
        return JSONObject().put("ok", true).put("backend", backend.id).put("output", res.stdout.trim())
    }
}
