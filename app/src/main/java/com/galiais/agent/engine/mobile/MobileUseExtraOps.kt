package com.galiais.agent.engine.mobile

import android.app.Application
import android.content.ComponentName
import android.media.MediaScannerConnection
import android.os.Environment
import com.galiais.agent.engine.fs.RootFsDaemon
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 通知中心交互、手机/容器双向文件桥、以及受控 Root/Shizuku 宿主底层 Shell & Logcat 执行器。 */
internal object MobileUseExtraOps {

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    suspend fun executeNotification(
        app: Application,
        cfg: MobileUseConfig,
        backend: MobileBackendKind,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        if (!cfg.allowNotifications) {
            throw IllegalStateException("通知读取与回复已在设置中关闭，请前往「设置 → 手机操控（Mobile-Use）」开启")
        }
        val op = params.optString("operation", "list").trim().lowercase()
        val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
        val listenerComp = ComponentName(app, SolbraidNotificationListenerService::class.java).flattenToString()

        if (op == "status") {
            return JSONObject()
                .put("ok", true)
                .put("connected", SolbraidNotificationListenerService.instance != null)
                .put("settingEnabled", SolbraidNotificationListenerService.isEnabledInSystemSettings(app))
        }
        if (op == "auto_grant") {
            if (!hasPriv) throw IllegalStateException("免 Root 无障碍模式无法自动授权通知监听，请通过设置页手动开启「Solbraid 通知读取与回复」")
            val res = privilegedExec("cmd notification allow_listener ${shQuote(listenerComp)}", 10)
            if (res == null || res.exitCode != 0) throw IllegalStateException("自动授权通知监听失败：${res?.stdout}")
            delay(800)
            return JSONObject()
                .put("ok", true)
                .put("granted", true)
                .put("connected", SolbraidNotificationListenerService.instance != null)
        }

        var svc = SolbraidNotificationListenerService.instance
        if (svc == null && hasPriv && !SolbraidNotificationListenerService.isEnabledInSystemSettings(app)) {
            // Root / Shizuku 下自动授予通知监听权，零弹窗立即可用
            privilegedExec("cmd notification allow_listener ${shQuote(listenerComp)}", 10)
            repeat(8) {
                delay(250)
                svc = SolbraidNotificationListenerService.instance
                if (svc != null) return@repeat
            }
        }
        val activeSvc = svc
            ?: throw IllegalStateException("通知监听服务尚未启用：请前往「设置 → 手机操控（Mobile-Use）」开启「Solbraid 通知读取与回复」")

        return when (op) {
            "list" -> {
                val arr = activeSvc.listActiveNotifications(
                    query = params.optString("query"),
                    packageFilter = params.optString("packageName"),
                    limit = params.optInt("limit", 40),
                )
                JSONObject().put("ok", true).put("count", arr.length()).put("notifications", arr)
            }
            "otp" -> {
                val maxAgeMs = params.optLong("maxAgeSec", 600L).coerceIn(30L, 3600L) * 1000L
                val hit = activeSvc.findRecentOtp(maxAgeMs)
                JSONObject().put("ok", true).put("found", hit != null).apply {
                    if (hit != null) put("otp", hit)
                }
            }
            "reply" -> {
                val key = params.optString("key").trim()
                val text = params.optString("text")
                require(key.isNotEmpty() && text.isNotEmpty()) { "reply 必须提供通知 key 与回复内容 text" }
                activeSvc.replyToNotification(key, text)
            }
            "action" -> {
                val key = params.optString("key").trim()
                val idx = params.optInt("actionIndex", 0)
                require(key.isNotEmpty()) { "action 必须提供通知 key" }
                activeSvc.triggerAction(key, idx)
            }
            "dismiss" -> activeSvc.dismissNotification(
                key = params.optString("key"),
                clearAll = params.optBoolean("clearAll", false),
            )
            else -> throw IllegalArgumentException("不支持的通知操作：$op（可选 status / list / otp / reply / action / dismiss / auto_grant）")
        }
    }

    suspend fun executeFiles(
        app: Application,
        backend: MobileBackendKind,
        activeLinuxRootDir: File,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        val op = params.optString("operation", "list").trim().lowercase()
        val isRoot = backend == MobileBackendKind.ROOT
        val sdcardRoot = Environment.getExternalStorageDirectory().canonicalPath

        fun resolveHostPath(raw: String): File {
            val clean = raw.trim().ifEmpty { sdcardRoot }
                .replace(Regex("^/sdcard(?=/|$)"), sdcardRoot)
            require(clean.startsWith("/") && !clean.contains('\u0000')) { "宿主路径必须是绝对路径：$raw" }
            val f = File(clean)
            if (!isRoot) {
                val canon = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                val allowed = canon == sdcardRoot || canon.startsWith("$sdcardRoot/")
                require(allowed) { "免 Root 模式下仅允许访问共享存储（/sdcard 或 $sdcardRoot），拒绝越界访问：$raw" }
            }
            return f
        }

        fun resolveGuestPath(raw: String): File {
            val clean = raw.trim()
            require(clean.startsWith("/") && !clean.contains("..") && !clean.contains('\u0000')) {
                "容器路径必须是合法绝对路径（如 /home/agent/workspace/a.png）：$raw"
            }
            return File(activeLinuxRootDir, clean.removePrefix("/"))
        }

        return when (op) {
            "list" -> {
                val dir = resolveHostPath(params.optString("hostPath", "/sdcard"))
                val limit = params.optInt("limit", 100).coerceIn(1, 300)
                if (dir.canRead() && dir.isDirectory) {
                    val entries = dir.listFiles().orEmpty()
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                        .take(limit)
                    val arr = JSONArray()
                    entries.forEach { f ->
                        arr.put(
                            JSONObject()
                                .put("name", f.name)
                                .put("path", f.absolutePath)
                                .put("isDir", f.isDirectory)
                                .put("size", if (f.isFile) f.length() else 0L)
                                .put("mtimeMs", f.lastModified()),
                        )
                    }
                    JSONObject().put("ok", true).put("path", dir.absolutePath).put("count", arr.length()).put("entries", arr)
                } else if (isRoot) {
                    val res = privilegedExec("ls -la ${shQuote(dir.absolutePath)} | head -n ${limit + 2}", 15)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("列举目录失败：${res?.stdout}")
                    JSONObject().put("ok", true).put("path", dir.absolutePath).put("raw", res.stdout)
                } else {
                    throw IllegalStateException("无法读取目录（请确认已授予全部文件访问权限）：${dir.absolutePath}")
                }
            }
            "pull" -> {
                // 手机宿主路径 -> Linux 容器工作区
                val src = resolveHostPath(params.optString("hostPath"))
                val dst = resolveGuestPath(params.optString("guestPath"))
                dst.parentFile?.mkdirs()
                if (src.isFile && src.canRead()) {
                    src.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false)
                } else if (isRoot) {
                    val res = privilegedExec("cp -f ${shQuote(src.absolutePath)} ${shQuote(dst.absolutePath)} && chmod 644 ${shQuote(dst.absolutePath)}", 30)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("Root 拉取宿主文件失败：${res?.stdout}")
                } else {
                    throw IllegalStateException("无法读取手机文件：${src.absolutePath}")
                }
                JSONObject()
                    .put("ok", true)
                    .put("operation", "pull")
                    .put("hostPath", src.absolutePath)
                    .put("guestPath", params.optString("guestPath"))
                    .put("bytes", dst.length())
            }
            "push" -> {
                // Linux 容器工作区 -> 手机宿主路径（并自动刷新媒体库）
                val src = resolveGuestPath(params.optString("guestPath"))
                require(src.isFile) { "容器源文件不存在：${params.optString("guestPath")}" }
                val dst = resolveHostPath(params.optString("hostPath"))
                val copied = runCatching {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    true
                }.getOrDefault(false)
                if (!copied) {
                    if (!isRoot) throw IllegalStateException("写入手机共享存储失败（请检查全部文件访问权限）：${dst.absolutePath}")
                    val res = privilegedExec("mkdir -p ${shQuote(dst.parentFile?.absolutePath ?: "/sdcard")} && cp -f ${shQuote(src.absolutePath)} ${shQuote(dst.absolutePath)}", 30)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("Root 写入宿主文件失败：${res?.stdout}")
                }
                if (dst.absolutePath.startsWith(sdcardRoot)) {
                    runCatching { MediaScannerConnection.scanFile(app, arrayOf(dst.absolutePath), null, null) }
                }
                JSONObject()
                    .put("ok", true)
                    .put("operation", "push")
                    .put("guestPath", params.optString("guestPath"))
                    .put("hostPath", dst.absolutePath)
                    .put("bytes", src.length())
            }
            "delete" -> {
                val target = resolveHostPath(params.optString("hostPath"))
                val deleted = runCatching { target.deleteRecursively() }.getOrDefault(false)
                if (!deleted && isRoot) {
                    val res = privilegedExec("rm -rf ${shQuote(target.absolutePath)}", 15)
                    if (res == null || res.exitCode != 0) throw IllegalStateException("删除失败：${res?.stdout}")
                } else if (!deleted) {
                    throw IllegalStateException("删除文件失败：${target.absolutePath}")
                }
                JSONObject().put("ok", true).put("operation", "delete").put("hostPath", target.absolutePath)
            }
            "media_scan" -> {
                val target = resolveHostPath(params.optString("hostPath"))
                MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null)
                JSONObject().put("ok", true).put("operation", "media_scan").put("hostPath", target.absolutePath)
            }
            else -> throw IllegalArgumentException("不支持的 files 操作：$op（可选 list / pull / push / delete / media_scan）")
        }
    }

    suspend fun executeHost(
        cfg: MobileUseConfig,
        backend: MobileBackendKind,
        params: JSONObject,
        privilegedExec: suspend (String, Int) -> RootFsDaemon.ExecOutput?,
    ): JSONObject {
        if (!cfg.allowHostShell) {
            throw IllegalStateException(
                "高危护栏拦截：Android 宿主底层 Shell / Logcat / Settings 写权限默认关闭。请前往「设置 → 手机操控（Mobile-Use）」显式开启「允许 AI 执行宿主底层命令」后再试。",
            )
        }
        val hasPriv = backend == MobileBackendKind.ROOT || backend == MobileBackendKind.SHIZUKU
        if (!hasPriv) {
            throw IllegalStateException("宿主底层命令需要 Root（chroot）或 Shizuku 免 Root 进阶驱动（当前仅连接无障碍服务）")
        }
        val op = params.optString("operation", "shell").trim().lowercase()
        val timeoutSec = params.optInt("timeoutSec", 20).coerceIn(1, 90)
        val maxOutputChars = 48_000

        val cmd = when (op) {
            "shell" -> {
                val raw = params.optString("command").trim()
                val blocked = MobileUseLogic.checkDangerousHostCommand(raw)
                if (blocked != null) throw IllegalStateException(blocked)
                raw
            }
            "logcat" -> {
                val lines = params.optInt("lines", 120).coerceIn(10, 600)
                val tag = params.optString("tag").trim()
                val level = params.optString("level", "D").trim().uppercase().take(1)
                val pkg = params.optString("packageName").trim()
                if (params.optBoolean("clear", false)) {
                    "logcat -c && echo CLEARED"
                } else {
                    val filter = if (tag.isNotEmpty()) "${shQuote("$tag:$level")} '*:S'" else "*:$level"
                    val base = "logcat -d -t $lines -v time $filter"
                    if (pkg.isNotEmpty()) "$base | grep -F ${shQuote(pkg)} | tail -n $lines" else base
                }
            }
            "settings_get" -> {
                val ns = params.optString("namespace", "global").trim().lowercase()
                require(ns in setOf("system", "secure", "global")) { "namespace 须为 system / secure / global" }
                val key = params.optString("key").trim()
                require(Regex("^[a-zA-Z0-9._-]+$").matches(key)) { "非法设置键名：$key" }
                "settings get $ns $key"
            }
            "settings_put" -> {
                val ns = params.optString("namespace", "system").trim().lowercase()
                require(ns in setOf("system", "secure", "global")) { "namespace 须为 system / secure / global" }
                val key = params.optString("key").trim()
                require(Regex("^[a-zA-Z0-9._-]+$").matches(key)) { "非法设置键名：$key" }
                val value = params.optString("value")
                "settings put $ns $key ${shQuote(value)}"
            }
            "getprop" -> {
                val key = params.optString("key").trim()
                if (key.isEmpty()) "getprop | head -n 120"
                else {
                    require(Regex("^[a-zA-Z0-9._-]+$").matches(key)) { "非法属性名：$key" }
                    "getprop $key"
                }
            }
            "setprop" -> {
                val key = params.optString("key").trim()
                require(Regex("^[a-zA-Z0-9._-]+$").matches(key)) { "非法属性名：$key" }
                "setprop $key ${shQuote(params.optString("value"))}"
            }
            "dumpsys" -> {
                val service = params.optString("service").trim()
                require(Regex("^[a-zA-Z0-9._-]+$").matches(service)) { "请提供合法 dumpsys 服务名（如 battery / window / meminfo / package）" }
                val extra = params.optString("args").trim()
                if (extra.isEmpty()) "dumpsys $service | head -c 36000"
                else "dumpsys $service $extra | head -c 36000"
            }
            "ps" -> {
                val q = params.optString("query").trim()
                if (q.isEmpty()) "ps -A | head -n 120" else "ps -A | grep -iF ${shQuote(q)} | head -n 80"
            }
            else -> throw IllegalArgumentException(
                "不支持的 host 操作：$op（可选 shell / logcat / settings_get / settings_put / getprop / setprop / dumpsys / ps）",
            )
        }

        val res = privilegedExec(cmd, timeoutSec)
            ?: throw IllegalStateException("特权宿主执行器未就绪")
        val truncated = res.stdout.length > maxOutputChars
        return JSONObject()
            .put("ok", res.exitCode == 0)
            .put("backend", backend.id)
            .put("operation", op)
            .put("exitCode", res.exitCode)
            .put("truncated", truncated)
            .put("output", if (truncated) res.stdout.take(maxOutputChars) else res.stdout)
    }
}
