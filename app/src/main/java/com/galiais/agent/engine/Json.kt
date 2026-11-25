package com.galiais.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/** 读取可空字符串：缺失或 JSON null 都返回 null。 */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { has(key) }

fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((key, value) in pairs) if (value != null) put(key, value)
}

/** 便于在界面上显示的 JSON 文本，限制长度避免一次渲染过大内容。 */
fun Any?.pretty(limit: Int = 4000): String {
    val text = when (this) {
        null, JSONObject.NULL -> ""
        is JSONObject -> toString(2)
        is JSONArray -> toString(2)
        else -> toString()
    }
    return if (text.length > limit) text.take(limit) + "\n…（已截断 ${text.length - limit} 字符）" else text
}
