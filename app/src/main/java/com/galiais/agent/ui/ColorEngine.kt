package com.galiais.agent.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 零依赖感知色彩引擎：基于 OKLCH 生成与 Material 3 色调（Tone，等同 CIELAB L*）一致的色调板，
 * 并按 M3 规范把色调映射到全部 Color Role。色调按真实相对亮度求解，保证文字/背景对比度与官方方案接近。
 */
object ColorEngine {
    data class Lch(val l: Double, val c: Double, val h: Double)

    private fun toLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun toGamma(c: Double): Double = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055

    fun toOklch(argb: Long): Lch {
        val r = toLinear(((argb shr 16) and 0xFFL) / 255.0)
        val g = toLinear(((argb shr 8) and 0xFFL) / 255.0)
        val b = toLinear((argb and 0xFFL) / 255.0)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = Math.toDegrees(atan2(okB, okA)).let { if (it < 0) it + 360 else it }
        return Lch(okL, sqrt(okA * okA + okB * okB), hue)
    }

    private fun oklchToLinear(l: Double, c: Double, h: Double): DoubleArray {
        val rad = Math.toRadians(h)
        val a = c * cos(rad)
        val b = c * sin(rad)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -1e-4 && it <= 1.0001 }

    /** 在给定亮度与色相下取不超过 [c] 的最大可显示彩度。 */
    private fun fit(l: Double, c: Double, h: Double): DoubleArray {
        val direct = oklchToLinear(l, c, h)
        if (inGamut(direct)) return direct
        var lo = 0.0
        var hi = c
        repeat(16) {
            val mid = (lo + hi) / 2
            if (inGamut(oklchToLinear(l, mid, h))) lo = mid else hi = mid
        }
        return oklchToLinear(l, lo, h)
    }

    private fun luminance(rgb: DoubleArray): Double =
        0.2126 * rgb[0].coerceIn(0.0, 1.0) + 0.7152 * rgb[1].coerceIn(0.0, 1.0) + 0.0722 * rgb[2].coerceIn(0.0, 1.0)

    private fun yFromTone(t: Double): Double = if (t > 8.0) ((t + 16.0) / 116.0).pow(3) else t / 903.2962963

    private fun toColor(rgb: DoubleArray): Color = Color(
        red = toGamma(rgb[0].coerceIn(0.0, 1.0)).toFloat(),
        green = toGamma(rgb[1].coerceIn(0.0, 1.0)).toFloat(),
        blue = toGamma(rgb[2].coerceIn(0.0, 1.0)).toFloat(),
    )

    /** 求解指定色相、目标彩度与 M3 色调（0~100）的颜色。 */
    fun tone(hue: Double, chroma: Double, tone: Double): Color {
        if (tone <= 0.0) return Color.Black
        if (tone >= 100.0) return Color.White
        val target = yFromTone(tone)
        var l = Math.cbrt(target)
        var rgb = fit(l, chroma, hue)
        repeat(6) {
            val current = luminance(rgb)
            if (current > 1e-9) {
                l = (l * Math.cbrt(target / current)).coerceIn(0.0, 1.0)
                rgb = fit(l, chroma, hue)
            }
        }
        return toColor(rgb)
    }

    fun relativeLuminance(color: Color): Double {
        fun ch(v: Float) = toLinear(v.toDouble())
        return 0.2126 * ch(color.red) + 0.7152 * ch(color.green) + 0.0722 * ch(color.blue)
    }

    class TonalPalette(private val hue: Double, private val chroma: Double) {
        private val cache = HashMap<Int, Color>()
        fun tone(t: Int): Color = cache.getOrPut(t) { tone(hue, chroma, t.toDouble()) }
    }

    /** 由种子色与配色风格生成完整 M3 ColorScheme。 */
    fun scheme(seed: Long, paletteStyle: String, dark: Boolean): ColorScheme {
        val src = toOklch(seed)
        val h = src.h
        val p: TonalPalette
        val s: TonalPalette
        val t: TonalPalette
        val n: TonalPalette
        val nv: TonalPalette
        when (paletteStyle) {
            "vibrant" -> {
                p = TonalPalette(h, maxOf(src.c, 0.16)); s = TonalPalette(h + 15, 0.075); t = TonalPalette(h + 45, 0.10)
                n = TonalPalette(h, 0.014); nv = TonalPalette(h, 0.026)
            }
            "expressive" -> {
                p = TonalPalette(h, 0.13); s = TonalPalette(h + 120, 0.07); t = TonalPalette(h + 200, 0.105)
                n = TonalPalette(h + 15, 0.022); nv = TonalPalette(h + 15, 0.034)
            }
            "mono" -> {
                p = TonalPalette(h, 0.0); s = TonalPalette(h, 0.0); t = TonalPalette(h, 0.0)
                n = TonalPalette(h, 0.0); nv = TonalPalette(h, 0.0)
            }
            else -> {
                p = TonalPalette(h, 0.115); s = TonalPalette(h, 0.04); t = TonalPalette(h + 60, 0.07)
                n = TonalPalette(h, 0.008); nv = TonalPalette(h, 0.018)
            }
        }
        val e = TonalPalette(25.0, 0.17)
        return if (!dark) lightColorScheme(
            primary = p.tone(40), onPrimary = p.tone(100), primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
            inversePrimary = p.tone(80),
            secondary = s.tone(40), onSecondary = s.tone(100), secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
            tertiary = t.tone(40), onTertiary = t.tone(100), tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
            background = n.tone(98), onBackground = n.tone(10),
            surface = n.tone(98), onSurface = n.tone(10),
            surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
            surfaceTint = p.tone(40),
            inverseSurface = n.tone(20), inverseOnSurface = n.tone(95),
            error = e.tone(40), onError = e.tone(100), errorContainer = e.tone(90), onErrorContainer = e.tone(10),
            outline = nv.tone(50), outlineVariant = nv.tone(80), scrim = Color.Black,
            surfaceBright = n.tone(98), surfaceDim = n.tone(87),
            surfaceContainerLowest = n.tone(100), surfaceContainerLow = n.tone(96), surfaceContainer = n.tone(94),
            surfaceContainerHigh = n.tone(92), surfaceContainerHighest = n.tone(90),
        ) else darkColorScheme(
            primary = p.tone(80), onPrimary = p.tone(20), primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
            inversePrimary = p.tone(40),
            secondary = s.tone(80), onSecondary = s.tone(20), secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
            tertiary = t.tone(80), onTertiary = t.tone(20), tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
            background = n.tone(6), onBackground = n.tone(90),
            surface = n.tone(6), onSurface = n.tone(90),
            surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
            surfaceTint = p.tone(80),
            inverseSurface = n.tone(90), inverseOnSurface = n.tone(20),
            error = e.tone(80), onError = e.tone(20), errorContainer = e.tone(30), onErrorContainer = e.tone(90),
            outline = nv.tone(60), outlineVariant = nv.tone(30), scrim = Color.Black,
            surfaceBright = n.tone(24), surfaceDim = n.tone(6),
            surfaceContainerLowest = n.tone(4), surfaceContainerLow = n.tone(10), surfaceContainer = n.tone(12),
            surfaceContainerHigh = n.tone(17), surfaceContainerHighest = n.tone(22),
        )
    }
}
