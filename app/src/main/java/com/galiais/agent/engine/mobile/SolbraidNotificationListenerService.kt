package com.galiais.agent.engine.mobile

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Solbraid 通知监听与直接回复服务（chroot 与 PRoot 免 Root 均完整可用）：
 * - 读取当前通知栏全部通知（包名、标题、正文、大段文本、动作按钮与可回复标记）；
 * - 支持验证码自动提取、点击通知按钮、RemoteInput 免切屏内联回复消息与清除通知。
 */
class SolbraidNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connectedFlow.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        connectedFlow.value = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        connectedFlow.value = false
        super.onDestroy()
    }

    fun listActiveNotifications(query: String? = null, packageFilter: String? = null, limit: Int = 40): JSONArray {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val pf = packageFilter?.trim()?.takeIf { it.isNotEmpty() }
        val pm = packageManager
        val list = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedByDescending { it.postTime }
        val arr = JSONArray()
        for (sbn in list) {
            if (arr.length() >= limit.coerceIn(1, 100)) break
            val pkg = sbn.packageName.orEmpty()
            if (pf != null && !pkg.equals(pf, ignoreCase = true) && !pkg.contains(pf, ignoreCase = true)) continue
            val item = toJson(sbn, pm)
            if (q != null) {
                val hay = "${item.optString("appLabel")} ${item.optString("title")} ${item.optString("text")} ${item.optString("bigText")} $pkg"
                if (!hay.contains(q, ignoreCase = true)) continue
            }
            arr.put(item)
        }
        return arr
    }

    fun findRecentOtp(maxAgeMs: Long = 10 * 60_000L): JSONObject? {
        val now = System.currentTimeMillis()
        val list = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedByDescending { it.postTime }
        for (sbn in list) {
            if (now - sbn.postTime > maxAgeMs) continue
            val extras = sbn.notification?.extras ?: continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val combined = "$title\n$big\n$text"
            val code = MobileUseLogic.extractOtpCode(combined) ?: continue
            return JSONObject()
                .put("code", code)
                .put("packageName", sbn.packageName)
                .put("key", sbn.key)
                .put("title", title)
                .put("text", big.ifBlank { text }.take(240))
                .put("postTime", sbn.postTime)
        }
        return null
    }

    fun replyToNotification(key: String, replyText: String): JSONObject {
        val sbn = activeNotifications?.firstOrNull { it.key == key }
            ?: throw IllegalStateException("未找到对应通知（可能已被划除）：$key")
        val actions = sbn.notification?.actions.orEmpty()
        for ((idx, action) in actions.withIndex()) {
            val inputs = action.remoteInputs
            if (inputs != null && inputs.isNotEmpty() && action.actionIntent != null) {
                val intent = Intent()
                val bundle = Bundle()
                for (ri in inputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(inputs, intent, bundle)
                action.actionIntent.send(this, 0, intent)
                return JSONObject()
                    .put("ok", true)
                    .put("key", key)
                    .put("actionIndex", idx)
                    .put("actionTitle", action.title?.toString().orEmpty())
                    .put("repliedLength", replyText.length)
            }
        }
        throw IllegalStateException("该通知不支持直接内联回复（未声明 RemoteInput）")
    }

    fun triggerAction(key: String, actionIndex: Int): JSONObject {
        val sbn = activeNotifications?.firstOrNull { it.key == key }
            ?: throw IllegalStateException("未找到对应通知：$key")
        val actions = sbn.notification?.actions.orEmpty()
        val action = actions.getOrNull(actionIndex)
            ?: throw IllegalArgumentException("通知动作索引越界：$actionIndex（共 ${actions.size} 项）")
        val pi = action.actionIntent ?: throw IllegalStateException("该通知动作无可执行 Intent")
        pi.send()
        return JSONObject()
            .put("ok", true)
            .put("key", key)
            .put("actionIndex", actionIndex)
            .put("actionTitle", action.title?.toString().orEmpty())
    }

    fun dismissNotification(key: String?, clearAll: Boolean): JSONObject {
        if (clearAll) {
            cancelAllNotifications()
            return JSONObject().put("ok", true).put("clearedAll", true)
        }
        val cleanKey = key?.trim().orEmpty()
        require(cleanKey.isNotEmpty()) { "请提供要清除的通知 key，或传 clearAll=true" }
        cancelNotification(cleanKey)
        return JSONObject().put("ok", true).put("dismissedKey", cleanKey)
    }

    private fun toJson(sbn: StatusBarNotification, pm: android.content.pm.PackageManager): JSONObject {
        val n = sbn.notification
        val extras = n?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val appLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val actionsArr = JSONArray()
        n?.actions?.forEachIndexed { idx, act ->
            val hasReply = !act.remoteInputs.isNullOrEmpty()
            actionsArr.put(
                JSONObject()
                    .put("index", idx)
                    .put("title", act.title?.toString().orEmpty())
                    .put("hasReply", hasReply),
            )
        }
        return JSONObject().apply {
            put("key", sbn.key)
            put("packageName", sbn.packageName)
            put("appLabel", appLabel)
            put("postTime", sbn.postTime)
            put("ongoing", sbn.isOngoing)
            put("clearable", sbn.isClearable)
            if (title.isNotEmpty()) put("title", title.take(180))
            if (text.isNotEmpty()) put("text", text.take(400))
            if (bigText.isNotEmpty() && bigText != text) put("bigText", bigText.take(800))
            if (subText.isNotEmpty()) put("subText", subText.take(120))
            if (actionsArr.length() > 0) put("actions", actionsArr)
        }
    }

    companion object {
        @Volatile var instance: SolbraidNotificationListenerService? = null
            private set

        private val connectedFlow = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

        fun isEnabledInSystemSettings(context: Context): Boolean {
            val expected = ComponentName(context, SolbraidNotificationListenerService::class.java).flattenToString()
            val expectedShort = ComponentName(context, SolbraidNotificationListenerService::class.java).flattenToShortString()
            val raw = runCatching {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            }.getOrNull().orEmpty()
            return raw.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true) }
        }
    }
}
