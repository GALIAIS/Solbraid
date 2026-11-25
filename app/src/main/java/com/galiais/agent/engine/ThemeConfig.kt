package com.galiais.agent.engine

import org.json.JSONObject

/**
 * 外观主题配置：支持 24 套大师预设与 7 大正交外观维度自由拼装，同时兼容 Material 3 动态取色。
 * 所有字段均有白名单与范围校验，旧版配置 JSON 可无损平滑迁移。
 */
data class ThemeConfig(
    val style: String = STYLE_GALIAIS,
    val mode: String = "system",
    val dynamicColor: Boolean = true,
    val seedColor: Long = 0xFF6750A4,
    val paletteStyle: String = "tonal",
    val accent: Long = 0xFF6E7BF2,
    val paper: String = "warm",
    val darkTone: String = "obsidian",
    val corner: Float = 1f,
    val cardStyle: String = "glass_hairline",
    val navBarStyle: String = "floating_glass",
    val canvasTexture: String = "mesh_glow",
    val buttonStyle: String = "pill",
    val approvalStyle: String = "top_glow",
    val listLayout: String = "cards",
    val typographyStyle: String = "editorial",
    val density: String = "standard",
    val fontScale: Float = 1f,
    val codeFontScale: Float = 1f,
    val codeTheme: String = "auto",
    val codeLineNumbers: Boolean = false,
    val bubbleStyle: String = "auto",
) {
    val isMaterial: Boolean get() = style == STYLE_MATERIAL

    fun toJson(): JSONObject = JSONObject()
        .put("style", style)
        .put("mode", mode)
        .put("dynamicColor", dynamicColor)
        .put("seedColor", seedColor)
        .put("paletteStyle", paletteStyle)
        .put("accent", accent)
        .put("paper", paper)
        .put("darkTone", darkTone)
        .put("corner", corner.toDouble())
        .put("cardStyle", cardStyle)
        .put("navBarStyle", navBarStyle)
        .put("canvasTexture", canvasTexture)
        .put("buttonStyle", buttonStyle)
        .put("approvalStyle", approvalStyle)
        .put("listLayout", listLayout)
        .put("typographyStyle", typographyStyle)
        .put("density", density)
        .put("fontScale", fontScale.toDouble())
        .put("codeFontScale", codeFontScale.toDouble())
        .put("codeTheme", codeTheme)
        .put("codeLineNumbers", codeLineNumbers)
        .put("bubbleStyle", bubbleStyle)

    /** 统一做白名单与范围校验，保证任何来源的数据都能安全渲染。 */
    fun sanitized(): ThemeConfig = copy(
        style = style.takeIf { it in STYLES } ?: STYLE_GALIAIS,
        mode = mode.takeIf { it in MODES } ?: "system",
        seedColor = seedColor or 0xFF000000,
        paletteStyle = paletteStyle.takeIf { it in PALETTE_STYLES } ?: "tonal",
        accent = if (accent == 0L) 0L else accent or 0xFF000000,
        paper = paper.takeIf { it in PAPERS } ?: "warm",
        darkTone = darkTone.takeIf { it in DARK_TONES } ?: "obsidian",
        corner = corner.coerceIn(0f, 1.6f).takeIf { !it.isNaN() } ?: 1f,
        cardStyle = cardStyle.takeIf { it in CARD_STYLES } ?: "glass_hairline",
        navBarStyle = navBarStyle.takeIf { it in NAV_BAR_STYLES } ?: "floating_glass",
        canvasTexture = canvasTexture.takeIf { it in CANVAS_TEXTURES } ?: "clean",
        buttonStyle = buttonStyle.takeIf { it in BUTTON_STYLES } ?: "pill",
        approvalStyle = approvalStyle.takeIf { it in APPROVAL_STYLES } ?: "top_glow",
        listLayout = listLayout.takeIf { it in LIST_LAYOUTS } ?: "cards",
        typographyStyle = typographyStyle.takeIf { it in TYPOGRAPHY_STYLES } ?: "editorial",
        density = density.takeIf { it in DENSITIES } ?: "standard",
        fontScale = fontScale.coerceIn(0.85f, 1.3f).takeIf { !it.isNaN() } ?: 1f,
        codeFontScale = codeFontScale.coerceIn(0.8f, 1.4f).takeIf { !it.isNaN() } ?: 1f,
        codeTheme = codeTheme.takeIf { it in CODE_THEMES } ?: "auto",
        bubbleStyle = bubbleStyle.takeIf { it in BUBBLE_STYLES } ?: "auto",
    )

    companion object {
        const val STYLE_GALIAIS = "galiais"
        const val STYLE_MATERIAL = "material"

        val STYLES = setOf(STYLE_GALIAIS, STYLE_MATERIAL)
        val MODES = setOf("system", "light", "dark")
        val PALETTE_STYLES = setOf("tonal", "vibrant", "expressive", "mono")
        val PAPERS = setOf("warm", "neutral", "cool", "sand", "lavender", "mint")
        val DARK_TONES = setOf("obsidian", "dim", "amoled", "midnight", "forest", "espresso")
        val CARD_STYLES = setOf(
            "auto", "glass_hairline", "hardware_tactile", "elevated",
            "grouped_expressive", "outlined", "filled",
        )
        val NAV_BAR_STYLES = setOf(
            "auto", "floating_glass", "split_fab", "hardware_slots",
            "editorial_bar", "m3_expressive",
        )
        val CANVAS_TEXTURES = setOf("clean", "dot_grid", "paper_grain", "mesh_glow")
        val BUTTON_STYLES = setOf("pill", "squircle", "chamfer_tactile", "tonal_soft")
        val APPROVAL_STYLES = setOf(
            "top_glow", "badge_ring", "accent_rail",
            "soft_halo", "hazard_header", "tonal_banner",
        )
        val LIST_LAYOUTS = setOf("cards", "tree_guide", "grouped")
        val TYPOGRAPHY_STYLES = setOf("editorial", "mono_tech", "rounded")
        val DENSITIES = setOf("compact", "standard", "relaxed")
        val CODE_THEMES = setOf("auto", "github-dark", "one-dark", "dracula", "nord", "github-light", "solarized-light")
        val BUBBLE_STYLES = setOf("auto", "neutral", "accent")

        fun fromJson(raw: String?, legacyMode: String? = null): ThemeConfig {
            if (raw.isNullOrBlank()) return ThemeConfig(mode = legacyMode ?: "system").sanitized()
            return try {
                val o = JSONObject(raw)
                val d = ThemeConfig()
                val legacyStyle = o.optString("style", d.style)
                val hasNewAxes = o.has("navBarStyle") || o.has("approvalStyle") || o.has("canvasTexture")
                ThemeConfig(
                    style = legacyStyle,
                    mode = o.optString("mode", d.mode),
                    dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
                    seedColor = o.optLong("seedColor", d.seedColor),
                    paletteStyle = o.optString("paletteStyle", d.paletteStyle),
                    accent = o.optLong("accent", if (hasNewAxes) d.accent else 0L),
                    paper = o.optString("paper", d.paper),
                    darkTone = o.optString("darkTone", d.darkTone),
                    corner = o.optDouble("corner", d.corner.toDouble()).toFloat(),
                    cardStyle = o.optString("cardStyle", if (hasNewAxes) d.cardStyle else "auto"),
                    navBarStyle = o.optString("navBarStyle", if (legacyStyle == STYLE_MATERIAL) "m3_expressive" else d.navBarStyle),
                    canvasTexture = o.optString("canvasTexture", if (hasNewAxes) d.canvasTexture else "clean"),
                    buttonStyle = o.optString("buttonStyle", d.buttonStyle),
                    approvalStyle = o.optString("approvalStyle", d.approvalStyle),
                    listLayout = o.optString("listLayout", d.listLayout),
                    typographyStyle = o.optString("typographyStyle", d.typographyStyle),
                    density = o.optString("density", d.density),
                    fontScale = o.optDouble("fontScale", d.fontScale.toDouble()).toFloat(),
                    codeFontScale = o.optDouble("codeFontScale", d.codeFontScale.toDouble()).toFloat(),
                    codeTheme = o.optString("codeTheme", d.codeTheme),
                    codeLineNumbers = o.optBoolean("codeLineNumbers", d.codeLineNumbers),
                    bubbleStyle = o.optString("bubbleStyle", d.bubbleStyle),
                ).sanitized()
            } catch (_: Exception) {
                ThemeConfig(mode = legacyMode ?: "system").sanitized()
            }
        }
    }
}

/** 用户保存的自定义混搭主题槽位。 */
data class CustomThemeSlot(
    val id: String,
    val name: String,
    val config: ThemeConfig,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("config", config.toJson())

    companion object {
        fun fromJson(obj: JSONObject): CustomThemeSlot? {
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            val cfgObj = obj.optJSONObject("config") ?: return null
            if (id.isBlank() || name.isBlank()) return null
            return CustomThemeSlot(id = id, name = name, config = ThemeConfig.fromJson(cfgObj.toString()))
        }
    }
}
