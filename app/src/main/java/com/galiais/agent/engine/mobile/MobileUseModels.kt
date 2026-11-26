package com.galiais.agent.engine.mobile

import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import org.json.JSONArray
import org.json.JSONObject

const val MOBILE_BACKEND_AUTO = "auto"
const val MOBILE_BACKEND_ROOT = "root"
const val MOBILE_BACKEND_SHIZUKU = "shizuku"
const val MOBILE_BACKEND_ACCESSIBILITY = "accessibility"
val MOBILE_BACKEND_OPTIONS = setOf(MOBILE_BACKEND_AUTO, MOBILE_BACKEND_ROOT, MOBILE_BACKEND_SHIZUKU, MOBILE_BACKEND_ACCESSIBILITY)

const val MOBILE_SAFETY_BALANCED = "balanced"
const val MOBILE_SAFETY_STRICT = "strict"
const val MOBILE_SAFETY_UNRESTRICTED = "unrestricted"
val MOBILE_SAFETY_OPTIONS = setOf(MOBILE_SAFETY_BALANCED, MOBILE_SAFETY_STRICT, MOBILE_SAFETY_UNRESTRICTED)

enum class MobileBackendKind(val id: String, val label: String) {
    ROOT("root", "Root 原生驱动"),
    SHIZUKU("shizuku", "Shizuku 进阶驱动（免 Root）"),
    ACCESSIBILITY("accessibility", "无障碍服务（免 Root）"),
    NONE("none", "未就绪"),
}

/** Mobile-Use 全局配置：三级后端路由、安全护栏、截屏压缩参数、通知与宿主 Shell 权限开关。 */
data class MobileUseConfig(
    val enabled: Boolean = true,
    val preferredBackend: String = MOBILE_BACKEND_AUTO,
    val safetyMode: String = MOBILE_SAFETY_BALANCED,
    val showOverlayPill: Boolean = true,
    val screenshotMaxWidth: Int = 720,
    val screenshotQuality: Int = 78,
    val customSensitivePackages: List<String> = emptyList(),
    val allowHostShell: Boolean = false,
    val allowNotifications: Boolean = true,
) {
    fun validated(): MobileUseConfig {
        require(preferredBackend in MOBILE_BACKEND_OPTIONS) { "请选择有效的手机操控驱动模式" }
        require(safetyMode in MOBILE_SAFETY_OPTIONS) { "请选择有效的手机操控安全策略" }
        val cleanPkgs = customSensitivePackages
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= 128 && !it.any(Char::isWhitespace) }
            .distinct()
            .take(64)
        return copy(
            screenshotMaxWidth = screenshotMaxWidth.coerceIn(360, 1440),
            screenshotQuality = screenshotQuality.coerceIn(40, 95),
            customSensitivePackages = cleanPkgs,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("preferredBackend", preferredBackend)
        .put("safetyMode", safetyMode)
        .put("showOverlayPill", showOverlayPill)
        .put("screenshotMaxWidth", screenshotMaxWidth)
        .put("screenshotQuality", screenshotQuality)
        .put("customSensitivePackages", JSONArray(customSensitivePackages))
        .put("allowHostShell", allowHostShell)
        .put("allowNotifications", allowNotifications)

    companion object {
        fun fromJson(raw: String?): MobileUseConfig {
            if (raw.isNullOrBlank()) return MobileUseConfig()
            return try {
                val obj = JSONObject(raw)
                val pkgs = buildList {
                    val arr = obj.optJSONArray("customSensitivePackages")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val s = arr.optString(i).trim()
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                }
                MobileUseConfig(
                    enabled = obj.optBoolean("enabled", true),
                    preferredBackend = obj.optString("preferredBackend", MOBILE_BACKEND_AUTO).takeIf { it in MOBILE_BACKEND_OPTIONS } ?: MOBILE_BACKEND_AUTO,
                    safetyMode = obj.optString("safetyMode", MOBILE_SAFETY_BALANCED).takeIf { it in MOBILE_SAFETY_OPTIONS } ?: MOBILE_SAFETY_BALANCED,
                    showOverlayPill = obj.optBoolean("showOverlayPill", true),
                    screenshotMaxWidth = obj.optInt("screenshotMaxWidth", 720).coerceIn(360, 1440),
                    screenshotQuality = obj.optInt("screenshotQuality", 78).coerceIn(40, 95),
                    customSensitivePackages = pkgs,
                    allowHostShell = obj.optBoolean("allowHostShell", false),
                    allowNotifications = obj.optBoolean("allowNotifications", true),
                )
            } catch (_: Exception) {
                MobileUseConfig()
            }
        }
    }
}

/** 界面树中的单个可交互/可读节点（Root uiautomator 与 AccessibilityService 统一输出格式）。 */
data class UiTreeNode(
    val index: Int,
    val text: String = "",
    val contentDesc: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        if (text.isNotEmpty()) put("text", text)
        if (contentDesc.isNotEmpty()) put("contentDesc", contentDesc)
        if (resourceId.isNotEmpty()) put("resourceId", resourceId)
        if (className.isNotEmpty()) put("className", className.substringAfterLast('.'))
        if (packageName.isNotEmpty()) put("packageName", packageName)
        put("bounds", JSONArray(listOf(left, top, right, bottom)))
        put("center", JSONArray(listOf(centerX, centerY)))
        if (clickable) put("clickable", true)
        if (editable) put("editable", true)
        if (scrollable) put("scrollable", true)
        if (checked) put("checked", true)
        if (focused) put("focused", true)
        if (!enabled) put("enabled", false)
    }
}

data class MobileUseStatus(
    val enabled: Boolean = true,
    val activeBackend: MobileBackendKind = MobileBackendKind.NONE,
    val preferredBackend: String = MOBILE_BACKEND_AUTO,
    val safetyMode: String = MOBILE_SAFETY_BALANCED,
    val runtimeMode: String = "root",
    val rootAvailable: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val shizukuUid: Int = -1,
    val accessibilityConnected: Boolean = false,
    val accessibilitySettingEnabled: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val allowHostShell: Boolean = false,
    val allowNotifications: Boolean = true,
    val emergencyStopped: Boolean = false,
    val activeActionLabel: String? = null,
    val lastActionAtMs: Long = 0L,
    val actionCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("ready", enabled && !emergencyStopped && activeBackend != MobileBackendKind.NONE)
        .put("activeBackend", activeBackend.id)
        .put("activeBackendLabel", activeBackend.label)
        .put("preferredBackend", preferredBackend)
        .put("safetyMode", safetyMode)
        .put("runtimeMode", runtimeMode)
        .put("rootAvailable", rootAvailable)
        .put("shizukuInstalled", shizukuInstalled)
        .put("shizukuAlive", shizukuAlive)
        .put("shizukuGranted", shizukuGranted)
        .put("shizukuUid", shizukuUid)
        .put("accessibilityConnected", accessibilityConnected)
        .put("accessibilitySettingEnabled", accessibilitySettingEnabled)
        .put("notificationListenerConnected", notificationListenerConnected)
        .put("notificationListenerEnabled", notificationListenerEnabled)
        .put("writeSettingsGranted", writeSettingsGranted)
        .put("overlayPermissionGranted", overlayPermissionGranted)
        .put("allowHostShell", allowHostShell)
        .put("allowNotifications", allowNotifications)
        .put("emergencyStopped", emergencyStopped)
        .put("activeActionLabel", activeActionLabel ?: JSONObject.NULL)
        .put("lastActionAtMs", lastActionAtMs)
        .put("actionCount", actionCount)
}

/** 纯 JVM 可测试的 Mobile-Use 核心逻辑：双后端路由、XML 节点解析、元素匹配、敏感包拦截与按键映射。 */
object MobileUseLogic {
    private val DEFAULT_SENSITIVE_EXACT = setOf(
        "com.eg.android.alipaygphone",
        "com.unionpay",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "me.bmax.apatch",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
    )

    private val DEFAULT_SENSITIVE_KEYWORDS = listOf(
        "bank", "wallet", "pay", "alipay", "icbc", "cmb", "ccb", "abchina", "boc",
        "packageinstaller", "permissioncontroller", "biometric", "credential", "keystore", "authenticator",
    )

    private val BOUNDS_RE = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private val NODE_TAG_RE = Regex("""<node\b([^>]+)/?>""")
    private val ATTR_RE = Regex("""([a-zA-Z0-9_:-]+)="([^"]*)"""")

    /**
     * 三级后端自动路由策略（Root > Shizuku > Accessibility）：
     * - `root` 偏好：仅在 Root 可用时使用 Root；
     * - `shizuku` 偏好：仅在 Shizuku 就绪时使用 Shizuku；
     * - `accessibility` 偏好：仅在无障碍就绪时使用无障碍；
     * - `auto`（默认）：
     *   - 免 Root PRoot 模式：优先 Shizuku（shell 级免 Root 能力）→ 无障碍服务 → Root（若设备恰巧已 Root）；
     *   - Root chroot 模式：优先常驻 Root 原生驱动 → Shizuku → 无障碍服务。
     */
    fun resolveBackend(
        preferred: String,
        runtimeMode: String,
        rootAvailable: Boolean,
        accessibilityReady: Boolean,
        shizukuReady: Boolean = false,
    ): MobileBackendKind = when (preferred) {
        MOBILE_BACKEND_ROOT -> if (rootAvailable) MobileBackendKind.ROOT else MobileBackendKind.NONE
        MOBILE_BACKEND_SHIZUKU -> if (shizukuReady) MobileBackendKind.SHIZUKU else MobileBackendKind.NONE
        MOBILE_BACKEND_ACCESSIBILITY -> if (accessibilityReady) MobileBackendKind.ACCESSIBILITY else MobileBackendKind.NONE
        else -> if (runtimeMode == RUNTIME_MODE_PROOT) {
            when {
                shizukuReady -> MobileBackendKind.SHIZUKU
                accessibilityReady -> MobileBackendKind.ACCESSIBILITY
                rootAvailable -> MobileBackendKind.ROOT
                else -> MobileBackendKind.NONE
            }
        } else {
            when {
                rootAvailable -> MobileBackendKind.ROOT
                shizukuReady -> MobileBackendKind.SHIZUKU
                accessibilityReady -> MobileBackendKind.ACCESSIBILITY
                else -> MobileBackendKind.NONE
            }
        }
    }

    fun isSensitivePackage(pkg: String?, customPackages: List<String> = emptyList()): Boolean {
        val clean = pkg?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty()) return false
        if (clean in DEFAULT_SENSITIVE_EXACT) return true
        if (customPackages.any { it.trim().equals(clean, ignoreCase = true) }) return true
        // 避免误伤 Solbraid 自身或普通系统启动器
        if (clean == "com.galiais.agent" || clean.contains("launcher")) return false
        return DEFAULT_SENSITIVE_KEYWORDS.any { kw -> clean.contains(kw) }
    }

    fun decodeXmlEntities(raw: String): String = raw
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /** 解析 `uiautomator dump` 输出的 XML 为精简结构化节点列表，过滤空容器节点以节省模型上下文 Token。 */
    fun parseUiAutomatorXml(xml: String, maxNodes: Int = 160): List<UiTreeNode> {
        if (xml.isBlank()) return emptyList()
        val result = ArrayList<UiTreeNode>(minOf(maxNodes, 128))
        var idx = 0
        for (match in NODE_TAG_RE.findAll(xml)) {
            if (result.size >= maxNodes) break
            val attrText = match.groupValues[1]
            val attrs = HashMap<String, String>(16)
            for (am in ATTR_RE.findAll(attrText)) {
                attrs[am.groupValues[1]] = decodeXmlEntities(am.groupValues[2])
            }
            val boundsRaw = attrs["bounds"].orEmpty()
            val bm = BOUNDS_RE.find(boundsRaw) ?: continue
            val l = bm.groupValues[1].toIntOrNull() ?: 0
            val t = bm.groupValues[2].toIntOrNull() ?: 0
            val r = bm.groupValues[3].toIntOrNull() ?: 0
            val b = bm.groupValues[4].toIntOrNull() ?: 0
            if (r <= l || b <= t) continue
            val text = attrs["text"].orEmpty().trim()
            val desc = attrs["content-desc"].orEmpty().trim()
            val resId = attrs["resource-id"].orEmpty().trim()
            val cls = attrs["class"].orEmpty().trim()
            val pkg = attrs["package"].orEmpty().trim()
            val clickable = attrs["clickable"] == "true"
            val scrollable = attrs["scrollable"] == "true"
            val editable = cls.contains("EditText", ignoreCase = true) || attrs["editable"] == "true"
            val checked = attrs["checked"] == "true"
            val focused = attrs["focused"] == "true"
            val enabled = attrs["enabled"] != "false"
            // 跳过既无文本/描述又不可交互的纯布局壳节点
            if (text.isEmpty() && desc.isEmpty() && !clickable && !editable && !scrollable && !focused) continue
            result += UiTreeNode(
                index = idx++,
                text = text.take(160),
                contentDesc = desc.take(160),
                resourceId = resId.substringAfterLast('/'),
                className = cls,
                packageName = pkg,
                left = l,
                top = t,
                right = r,
                bottom = b,
                clickable = clickable,
                editable = editable,
                scrollable = scrollable,
                checked = checked,
                focused = focused,
                enabled = enabled,
            )
        }
        return result
    }

    /** 按文本、描述、resourceId 或类名定位匹配节点（优先精确匹配，其次子串忽略大小写）。 */
    fun findMatchingNodes(
        nodes: List<UiTreeNode>,
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        className: String? = null,
    ): List<UiTreeNode> {
        val qText = text?.trim()?.takeIf { it.isNotEmpty() }
        val qDesc = contentDesc?.trim()?.takeIf { it.isNotEmpty() }
        val qRes = resourceId?.trim()?.takeIf { it.isNotEmpty() }
        val qCls = className?.trim()?.takeIf { it.isNotEmpty() }
        if (qText == null && qDesc == null && qRes == null && qCls == null) return emptyList()

        fun matches(node: UiTreeNode, exact: Boolean): Boolean {
            if (qText != null) {
                val ok = if (exact) {
                    node.text.equals(qText, ignoreCase = true) || node.contentDesc.equals(qText, ignoreCase = true)
                } else {
                    node.text.contains(qText, ignoreCase = true) || node.contentDesc.contains(qText, ignoreCase = true)
                }
                if (!ok) return false
            }
            if (qDesc != null) {
                val ok = if (exact) node.contentDesc.equals(qDesc, ignoreCase = true)
                else node.contentDesc.contains(qDesc, ignoreCase = true)
                if (!ok) return false
            }
            if (qRes != null) {
                val ok = if (exact) node.resourceId.equals(qRes, ignoreCase = true)
                else node.resourceId.contains(qRes, ignoreCase = true)
                if (!ok) return false
            }
            if (qCls != null) {
                val ok = node.className.contains(qCls, ignoreCase = true)
                if (!ok) return false
            }
            return true
        }

        val exactHits = nodes.filter { matches(it, exact = true) }
        return exactHits.ifEmpty { nodes.filter { matches(it, exact = false) } }
    }

    /** 根据屏幕分辨率与语义方向计算起止滑动坐标 `[x1, y1, x2, y2]`（手指滑动方向）。 */
    fun computeSwipeCoords(
        direction: String,
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float = 0.48f,
    ): IntArray {
        val w = screenWidth.coerceAtLeast(320)
        val h = screenHeight.coerceAtLeast(480)
        val ratio = distanceRatio.coerceIn(0.15f, 0.85f)
        val cx = w / 2
        val cy = h / 2
        val dy = (h * ratio / 2f).toInt()
        val dx = (w * ratio / 2f).toInt()
        return when (direction.trim().lowercase()) {
            "up", "scroll_down" -> intArrayOf(cx, (cy + dy).coerceAtMost(h - 40), cx, (cy - dy).coerceAtLeast(40))
            "down", "scroll_up" -> intArrayOf(cx, (cy - dy).coerceAtLeast(40), cx, (cy + dy).coerceAtMost(h - 40))
            "left", "scroll_right" -> intArrayOf((cx + dx).coerceAtMost(w - 30), cy, (cx - dx).coerceAtLeast(30), cy)
            "right", "scroll_left" -> intArrayOf((cx - dx).coerceAtLeast(30), cy, (cx + dx).coerceAtMost(w - 30), cy)
            else -> throw IllegalArgumentException("不支持的滑动方向：$direction（可选 up / down / left / right）")
        }
    }

    fun normalizeKeyName(raw: String): String {
        val clean = raw.trim().lowercase().removePrefix("keycode_")
        return when (clean) {
            "back", "go_back" -> "back"
            "home" -> "home"
            "recents", "recent", "app_switch", "overview" -> "recents"
            "notifications", "notification" -> "notifications"
            "quick_settings", "quicksettings" -> "quick_settings"
            "enter", "return", "dpad_center" -> "enter"
            "del", "delete", "backspace" -> "delete"
            "tab" -> "tab"
            "esc", "escape" -> "escape"
            "space" -> "space"
            "volume_up", "vol_up" -> "volume_up"
            "volume_down", "vol_down" -> "volume_down"
            "power", "lock", "lock_screen" -> "power"
            "paste" -> "paste"
            "screenshot", "sysrq" -> "screenshot"
            else -> clean
        }
    }

    fun toAndroidKeyCode(canonicalKey: String): Int? = when (normalizeKeyName(canonicalKey)) {
        "back" -> 4
        "home" -> 3
        "recents" -> 187
        "enter" -> 66
        "delete" -> 67
        "tab" -> 61
        "escape" -> 111
        "space" -> 62
        "volume_up" -> 24
        "volume_down" -> 25
        "volume_mute" -> 164
        "power" -> 26
        "wakeup" -> 224
        "sleep" -> 223
        "paste" -> 279
        "copy" -> 278
        "cut" -> 277
        "media_play_pause" -> 85
        "media_next" -> 87
        "media_previous" -> 88
        "screenshot" -> 120
        else -> canonicalKey.toIntOrNull()?.takeIf { it in 1..400 }
    }

    private val OTP_KEYWORD_RE = Regex(
        """(?:验证码|校验码|动态码|确认码|登录码|安全码|verification\s*code|otp|passcode|security\s*code|auth\s*code)[^0-9]{0,14}\b([0-9]{4,8})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_REVERSE_RE = Regex(
        """\b([0-9]{4,8})\b[^0-9]{0,16}(?:为您的验证码|是您的验证码|验证码|verification\s*code|otp)""",
        RegexOption.IGNORE_CASE,
    )

    /** 从通知标题/正文中提取 4–8 位短信或应用验证码。 */
    fun extractOtpCode(text: String): String? {
        if (text.isBlank()) return null
        OTP_KEYWORD_RE.find(text)?.let { return it.groupValues[1] }
        OTP_REVERSE_RE.find(text)?.let { return it.groupValues[1] }
        return null
    }

    /** 筛选适合在截屏上叠加 Set-of-Mark 编号框的代表性交互/文本节点（去重重叠框）。 */
    fun filterInteractiveNodesForSoM(nodes: List<UiTreeNode>, maxLabels: Int = 45): List<UiTreeNode> {
        val out = ArrayList<UiTreeNode>(minOf(nodes.size, maxLabels))
        for (n in nodes) {
            if (out.size >= maxLabels) break
            if (n.width < 12 || n.height < 12) continue
            val meaningful = n.clickable || n.editable || n.scrollable || n.text.isNotEmpty() || n.contentDesc.isNotEmpty()
            if (!meaningful) continue
            val duplicate = out.any { prev ->
                kotlin.math.abs(prev.centerX - n.centerX) <= 10 &&
                    kotlin.math.abs(prev.centerY - n.centerY) <= 10 &&
                    kotlin.math.abs(prev.width - n.width) <= 24 &&
                    kotlin.math.abs(prev.height - n.height) <= 24
            }
            if (!duplicate) out += n
        }
        return out
    }

    /** 计算双指缩放起止坐标：返回 2 条路径 `[[x1s,y1s,x1e,y1e], [x2s,y2s,x2e,y2e]]`。 */
    fun computePinchCoords(
        cx: Int,
        cy: Int,
        startDistancePx: Int,
        endDistancePx: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Array<IntArray> {
        val w = screenWidth.coerceAtLeast(320)
        val h = screenHeight.coerceAtLeast(480)
        val r0 = (startDistancePx / 2).coerceIn(20, minOf(w, h) / 2 - 20)
        val r1 = (endDistancePx / 2).coerceIn(20, minOf(w, h) / 2 - 20)
        val clampX = { v: Int -> v.coerceIn(16, w - 16) }
        val clampY = { v: Int -> v.coerceIn(16, h - 16) }
        return arrayOf(
            intArrayOf(clampX(cx - r0), clampY(cy - r0 / 2), clampX(cx - r1), clampY(cy - r1 / 2)),
            intArrayOf(clampX(cx + r0), clampY(cy + r0 / 2), clampX(cx + r1), clampY(cy + r1 / 2)),
        )
    }

    private val HOST_BLOCK_PATTERNS = listOf(
        Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+/(?:\s|$|\*)""") to "禁止根目录递归强删",
        Regex("""\bdd\b[^\n]*\bof=/dev/block/""") to "禁止直接写入底层块设备分区",
        Regex("""\b(?:mkfs|mke2fs|fastboot|flash_image)\b""") to "禁止格式化或刷写底层分区",
        Regex("""\breboot\s+(?:bootloader|recovery|edl)\b""") to "禁止自动重启至刷机/恢复模式",
    )

    /** 拦截可能导致变砖或不可逆清盘的极端破坏性宿主命令。 */
    fun checkDangerousHostCommand(cmd: String): String? {
        val s = cmd.trim()
        if (s.isEmpty()) return "宿主命令不能为空"
        for ((re, reason) in HOST_BLOCK_PATTERNS) {
            if (re.containsMatchIn(s)) return "安全护栏拦截：$reason"
        }
        return null
    }
}
