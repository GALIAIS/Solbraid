package com.galiais.agent.engine.terminal

import androidx.compose.ui.graphics.Color

/** 终端光标形态（支持 PowerShell / Windows Terminal 默认竖线、经典方块与下划线）。 */
enum class TerminalCursorStyle(val id: String, val label: String) {
    BAR("bar", "竖线 |"),
    BLOCK("block", "方块 █"),
    UNDERLINE("underline", "下划线 _");

    companion object {
        fun fromId(id: String?): TerminalCursorStyle = entries.firstOrNull { it.id == id } ?: BAR
    }
}

/** 终端配色方案（内置 PowerShell 7 / Windows Terminal 与 Warp 深浅双系共 12 套权威配色）。 */
data class TerminalColorScheme(
    val id: String,
    val label: String,
    val background: Int,
    val foreground: Int,
    val chromeBg: Int,
    val barBg: Int,
    val keyBg: Int,
    val border: Int,
    val accent: Int,
    val cursor: Int,
    val selectionBg: Int,
    val ansi16: IntArray,
    val isLight: Boolean = false,
) {
    val bgColor: Color get() = Color(background)
    val fgColor: Color get() = Color(foreground)
    val chromeColor: Color get() = Color(chromeBg)
    val barColor: Color get() = Color(barBg)
    val keyColor: Color get() = Color(keyBg)
    val borderColor: Color get() = Color(border)
    val accentColor: Color get() = Color(accent)
    val cursorColor: Color get() = Color(cursor)
    val selectionColor: Color get() = Color(selectionBg)

    override fun equals(other: Any?): Boolean = other is TerminalColorScheme && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

object TerminalThemes {
    /** 经典 Windows PowerShell 蓝（#012456 深蓝背景 + Campbell/PS 亮黄/青高对比语法色）。 */
    val POWERSHELL_BLUE = TerminalColorScheme(
        id = "powershell_blue",
        label = "PowerShell 蓝",
        background = 0xFF012456.toInt(),
        foreground = 0xFFEEEDF0.toInt(),
        chromeBg = 0xFF011B42.toInt(),
        barBg = 0xFF02204D.toInt(),
        keyBg = 0xFF0C3168.toInt(),
        border = 0xFF1A4684.toInt(),
        accent = 0xFF61D6D6.toInt(),
        cursor = 0xFFF9F1A5.toInt(),
        selectionBg = 0x882E69B8.toInt(),
        ansi16 = intArrayOf(
            0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
            0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
            0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
            0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt(),
        ),
    )

    /** Windows Terminal 官方默认配色 Campbell（PowerShell 7 标准现代暗黑）。 */
    val CAMPBELL = TerminalColorScheme(
        id = "campbell",
        label = "Windows Terminal",
        background = 0xFF0C0C0C.toInt(),
        foreground = 0xFFCCCCCC.toInt(),
        chromeBg = 0xFF141414.toInt(),
        barBg = 0xFF1B1B1B.toInt(),
        keyBg = 0xFF262626.toInt(),
        border = 0xFF343434.toInt(),
        accent = 0xFF3B78FF.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
        selectionBg = 0x883B78FF.toInt(),
        ansi16 = intArrayOf(
            0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
            0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
            0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
            0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt(),
        ),
    )

    /** PowerShell 7 / Cascadia 现代深蓝曜石主题（推荐默认：兼顾 PowerShell 韵味与长时间护眼）。 */
    val CASCADIA_DARK = TerminalColorScheme(
        id = "cascadia_dark",
        label = "PowerShell 7 曜石",
        background = 0xFF0C1220.toInt(),
        foreground = 0xFFE6EDF3.toInt(),
        chromeBg = 0xFF101829.toInt(),
        barBg = 0xFF151F33.toInt(),
        keyBg = 0xFF1E2B44.toInt(),
        border = 0xFF2A3B5C.toInt(),
        accent = 0xFF58A6FF.toInt(),
        cursor = 0xFF58A6FF.toInt(),
        selectionBg = 0x882E5A9C.toInt(),
        ansi16 = intArrayOf(
            0xFF161B22.toInt(), 0xFFF85149.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
            0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
            0xFF6E7681.toInt(), 0xFFFF7B72.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
            0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFF0F6FC.toInt(),
        ),
    )

    /** Warp 官方 Dark 主题（直取自 Warp `default_themes.rs`：纯黑高对比 + 天青强调色）。 */
    val WARP_DARK = TerminalColorScheme(
        id = "warp_dark",
        label = "Warp Dark",
        background = 0xFF050505.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
        chromeBg = 0xFF0D0E11.toInt(),
        barBg = 0xFF14161A.toInt(),
        keyBg = 0xFF1E2128.toInt(),
        border = 0xFF2B2F38.toInt(),
        accent = 0xFF19AAD8.toInt(),
        cursor = 0xFF19AAD8.toInt(),
        selectionBg = 0x8819AAD8.toInt(),
        ansi16 = intArrayOf(
            0xFF616161.toInt(), 0xFFFF8272.toInt(), 0xFFB4FA72.toInt(), 0xFFFEFDC2.toInt(),
            0xFFA5D5FE.toInt(), 0xFFFF8FFD.toInt(), 0xFFD0D1FE.toInt(), 0xFFF1F1F1.toInt(),
            0xFF8E8E8E.toInt(), 0xFFFFC4BD.toInt(), 0xFFD6FCB9.toInt(), 0xFFFEFDD5.toInt(),
            0xFFC1E3FE.toInt(), 0xFFFFB1FE.toInt(), 0xFFE5E6FE.toInt(), 0xFFFEFFFF.toInt(),
        ),
    )

    /** Dracula 官方色板（直取自 Warp `default_themes.rs`）。 */
    val DRACULA = TerminalColorScheme(
        id = "dracula",
        label = "Dracula",
        background = 0xFF282A36.toInt(),
        foreground = 0xFFF8F8F2.toInt(),
        chromeBg = 0xFF21222C.toInt(),
        barBg = 0xFF262834.toInt(),
        keyBg = 0xFF343746.toInt(),
        border = 0xFF44475A.toInt(),
        accent = 0xFFFF79C6.toInt(),
        cursor = 0xFFFF79C6.toInt(),
        selectionBg = 0x8844475A.toInt(),
        ansi16 = intArrayOf(
            0xFF000000.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBBBBBB.toInt(),
            0xFF555555.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFCAA9FA.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    /** Gruvbox Dark 官方色板（直取自 Warp `default_themes.rs`）。 */
    val GRUVBOX_DARK = TerminalColorScheme(
        id = "gruvbox_dark",
        label = "Gruvbox Dark",
        background = 0xFF282828.toInt(),
        foreground = 0xFFEBDBB2.toInt(),
        chromeBg = 0xFF1D2021.toInt(),
        barBg = 0xFF242424.toInt(),
        keyBg = 0xFF3C3836.toInt(),
        border = 0xFF504945.toInt(),
        accent = 0xFFFC802D.toInt(),
        cursor = 0xFFFC802D.toInt(),
        selectionBg = 0x88504945.toInt(),
        ansi16 = intArrayOf(
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
        ),
    )

    /** Solarized Dark 官方色板（直取自 Warp `default_themes.rs`）。 */
    val SOLARIZED_DARK = TerminalColorScheme(
        id = "solarized_dark",
        label = "Solarized Dark",
        background = 0xFF002B36.toInt(),
        foreground = 0xFFF8F8F2.toInt(),
        chromeBg = 0xFF00212B.toInt(),
        barBg = 0xFF032A35.toInt(),
        keyBg = 0xFF073642.toInt(),
        border = 0xFF144B59.toInt(),
        accent = 0xFFCB4B16.toInt(),
        cursor = 0xFFCB4B16.toInt(),
        selectionBg = 0x88073642.toInt(),
        ansi16 = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
        ),
    )

    val ONE_HALF_DARK = TerminalColorScheme(
        id = "one_half_dark",
        label = "One Half Dark",
        background = 0xFF282C34.toInt(),
        foreground = 0xFFDCDFE4.toInt(),
        chromeBg = 0xFF21252B.toInt(),
        barBg = 0xFF252931.toInt(),
        keyBg = 0xFF313640.toInt(),
        border = 0xFF3E4452.toInt(),
        accent = 0xFF61AFEF.toInt(),
        cursor = 0xFFA3B3CC.toInt(),
        selectionBg = 0x88474E5D.toInt(),
        ansi16 = intArrayOf(
            0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFDCDFE4.toInt(),
            0xFF5A6374.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
        ),
    )

    val TOKYO_NIGHT = TerminalColorScheme(
        id = "tokyo_night",
        label = "Tokyo Night",
        background = 0xFF0B0E14.toInt(),
        foreground = 0xFFE6EDF3.toInt(),
        chromeBg = 0xFF111520.toInt(),
        barBg = 0xFF151A26.toInt(),
        keyBg = 0xFF1E2433.toInt(),
        border = 0xFF262F42.toInt(),
        accent = 0xFF7AA2F7.toInt(),
        cursor = 0xFF7AA2F7.toInt(),
        selectionBg = 0x882B416B.toInt(),
        ansi16 = intArrayOf(
            0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
            0xFF565F89.toInt(), 0xFFFF7A93.toInt(), 0xFFB9F27C.toInt(), 0xFFFF9E64.toInt(),
            0xFF7DA6FF.toInt(), 0xFFC0A3FF.toInt(), 0xFF0DB9D7.toInt(), 0xFFF0F6FC.toInt(),
        ),
    )

    /** Warp 官方 Light 浅色主题（直取自 Warp `default_themes.rs`，户外强光高对比）。 */
    val WARP_LIGHT = TerminalColorScheme(
        id = "warp_light",
        label = "Warp Light",
        background = 0xFFFFFFFF.toInt(),
        foreground = 0xFF111111.toInt(),
        chromeBg = 0xFFF2F4F7.toInt(),
        barBg = 0xFFE9EDF2.toInt(),
        keyBg = 0xFFDFE5EC.toInt(),
        border = 0xFFCBD4DF.toInt(),
        accent = 0xFF008EC4.toInt(),
        cursor = 0xFF008EC4.toInt(),
        selectionBg = 0x6600C2FF,
        ansi16 = intArrayOf(
            0xFF212121.toInt(), 0xFFC30771.toInt(), 0xFF10A778.toInt(), 0xFFA89C14.toInt(),
            0xFF008EC4.toInt(), 0xFF523C79.toInt(), 0xFF20A5BA.toInt(), 0xFF6B7280.toInt(),
            0xFF4B5563.toInt(), 0xFFFB007A.toInt(), 0xFF0F9D6E.toInt(), 0xFFB58900.toInt(),
            0xFF0284C7.toInt(), 0xFF6855DE.toInt(), 0xFF0891B2.toInt(), 0xFF111111.toInt(),
        ),
        isLight = true,
    )

    /** Solarized Light 官方浅色主题（直取自 Warp `default_themes.rs`）。 */
    val SOLARIZED_LIGHT = TerminalColorScheme(
        id = "solarized_light",
        label = "Solarized Light",
        background = 0xFFFDF6E3.toInt(),
        foreground = 0xFF586E75.toInt(),
        chromeBg = 0xFFEEE8D5.toInt(),
        barBg = 0xFFE6DFCB.toInt(),
        keyBg = 0xFFDDD6C1.toInt(),
        border = 0xFFC8C0AB.toInt(),
        accent = 0xFF2AA198.toInt(),
        cursor = 0xFF2AA198.toInt(),
        selectionBg = 0x552AA198,
        ansi16 = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF657B83.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFF073642.toInt(),
        ),
        isLight = true,
    )

    /** Gruvbox Light 官方浅色主题（直取自 Warp `default_themes.rs`）。 */
    val GRUVBOX_LIGHT = TerminalColorScheme(
        id = "gruvbox_light",
        label = "Gruvbox Light",
        background = 0xFFFBF1C7.toInt(),
        foreground = 0xFF3C3836.toInt(),
        chromeBg = 0xFFF2E5BC.toInt(),
        barBg = 0xFFEBDBB2.toInt(),
        keyBg = 0xFFE2D0A4.toInt(),
        border = 0xFFD5C4A1.toInt(),
        accent = 0xFFAD3B14.toInt(),
        cursor = 0xFFAD3B14.toInt(),
        selectionBg = 0x55AD3B14,
        ansi16 = intArrayOf(
            0xFF3C3836.toInt(), 0xFFCC241D.toInt(), 0xFF79740E.toInt(), 0xFFB57614.toInt(),
            0xFF076678.toInt(), 0xFF8F3F71.toInt(), 0xFF427B58.toInt(), 0xFF7C6F64.toInt(),
            0xFF928374.toInt(), 0xFF9D0006.toInt(), 0xFF79740E.toInt(), 0xFFB57614.toInt(),
            0xFF076678.toInt(), 0xFF8F3F71.toInt(), 0xFF427B58.toInt(), 0xFF282828.toInt(),
        ),
        isLight = true,
    )

    val ALL: List<TerminalColorScheme> = listOf(
        CASCADIA_DARK,
        WARP_DARK,
        POWERSHELL_BLUE,
        CAMPBELL,
        DRACULA,
        GRUVBOX_DARK,
        SOLARIZED_DARK,
        ONE_HALF_DARK,
        TOKYO_NIGHT,
        WARP_LIGHT,
        SOLARIZED_LIGHT,
        GRUVBOX_LIGHT,
    )

    fun byId(id: String?): TerminalColorScheme = ALL.firstOrNull { it.id == id } ?: CASCADIA_DARK
}

/**
 * 紧凑无装箱单元格样式编码（全基本类型 Int 零对象分配）：
 * - 颜色编码：`0` = 默认前/背景色；`1..256` = xterm 256 调色板索引（值-1）；高位 `0xFF000000` 置位 = 24-bit TrueColor ARGB。
 */
object TerminalStyle {
    const val COLOR_DEFAULT = 0
    const val FLAG_BOLD = 1 shl 0
    const val FLAG_DIM = 1 shl 1
    const val FLAG_ITALIC = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_DOUBLE_UNDERLINE = 1 shl 4
    const val FLAG_CURLY_UNDERLINE = 1 shl 5
    const val FLAG_BLINK = 1 shl 6
    const val FLAG_INVERSE = 1 shl 7
    const val FLAG_HIDDEN = 1 shl 8
    const val FLAG_STRIKETHROUGH = 1 shl 9
    /** 双宽字符（CJK/Emoji）的右半占位单元。 */
    const val FLAG_WIDE_CONTINUATION = 1 shl 10
    /** 双宽字符的左半主单元。 */
    const val FLAG_WIDE_LEAD = 1 shl 11
    /** 点状下划线（SGR 4:4）。 */
    const val FLAG_DOTTED_UNDERLINE = 1 shl 12
    /** 虚线下划线（SGR 4:5）。 */
    const val FLAG_DASHED_UNDERLINE = 1 shl 13
    const val ALL_UNDERLINE_FLAGS = FLAG_UNDERLINE or FLAG_DOUBLE_UNDERLINE or FLAG_CURLY_UNDERLINE or FLAG_DOTTED_UNDERLINE or FLAG_DASHED_UNDERLINE

    fun indexed(index: Int): Int = (index.coerceIn(0, 255)) + 1

    fun trueColor(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private val xterm256Tail: IntArray = IntArray(240) { i ->
        val idx = i + 16
        if (idx < 232) {
            val n = idx - 16
            val b = n % 6
            val g = (n / 6) % 6
            val r = n / 36
            val map = intArrayOf(0, 95, 135, 175, 215, 255)
            trueColor(map[r], map[g], map[b])
        } else {
            val gray = 8 + (idx - 232) * 10
            trueColor(gray, gray, gray)
        }
    }

    fun resolveColor(
        encoded: Int,
        isFg: Boolean,
        bold: Boolean,
        scheme: TerminalColorScheme,
        paletteOverrides: Map<Int, Int>? = null,
        fgOverride: Int? = null,
        bgOverride: Int? = null,
    ): Int {
        if (encoded == COLOR_DEFAULT) {
            return if (isFg) (fgOverride ?: scheme.foreground) else (bgOverride ?: scheme.background)
        }
        if ((encoded and 0xFF000000.toInt()) != 0) return encoded
        var idx = encoded - 1
        if (isFg && bold && idx in 0..7) idx += 8
        paletteOverrides?.get(idx)?.let { return it }
        return if (idx in 0..15) scheme.ansi16[idx] else xterm256Tail[(idx - 16).coerceIn(0, 239)]
    }

    /**
     * 最小对比度护眼增强（零对象分配）：当低亮度前景色（如黑底暗蓝 `34m`）与背景对比度不足时，
     * 保持色相平滑提亮/压暗至满足 `minRatio`（默认 3.2:1），确保深色终端所有输出清晰可读。
     */
    fun ensureReadableContrast(fg: Int, bg: Int, minRatio: Float = 3.2f): Int {
        if (fg == bg) return fg
        val bgLum = relativeLuminance(bg)
        val fgLum = relativeLuminance(fg)
        if (contrastRatio(fgLum, bgLum) >= minRatio) return fg
        val target = if (bgLum < 0.28f) 255 else 0
        val fr = (fg ushr 16) and 0xFF
        val fgG = (fg ushr 8) and 0xFF
        val fb = fg and 0xFF
        var lo = 0f
        var hi = 1f
        var best = fg
        repeat(6) {
            val mid = (lo + hi) * 0.5f
            val nr = (fr + ((target - fr) * mid)).toInt().coerceIn(0, 255)
            val ng = (fgG + ((target - fgG) * mid)).toInt().coerceIn(0, 255)
            val nb = (fb + ((target - fb) * mid)).toInt().coerceIn(0, 255)
            val cand = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            if (contrastRatio(relativeLuminance(cand), bgLum) >= minRatio) {
                best = cand
                hi = mid
            } else {
                lo = mid
            }
        }
        return best
    }

    private fun contrastRatio(l1: Float, l2: Float): Float {
        val hi = if (l1 >= l2) l1 else l2
        val lo = if (l1 >= l2) l2 else l1
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun relativeLuminance(argb: Int): Float {
        val r = linearize(((argb ushr 16) and 0xFF) / 255f)
        val g = linearize(((argb ushr 8) and 0xFF) / 255f)
        val b = linearize((argb and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
