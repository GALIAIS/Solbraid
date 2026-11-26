package com.galiais.agent.engine.terminal

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Warp 风格的像素对齐几何制表、块元素与 Powerline 矢量计算引擎（100% 纯 Kotlin，可直接 JVM 单测）：
 * - 核心线宽与无重叠矩形拆分算法直接取材自 Warp `grid_renderer/box_drawing.rs`；
 * - 覆盖 Unicode Light/Heavy/Mixed/Double 制表符（U+2500..U+257F）、圆角（╭╮╰╯）、
 *   块元素与阴影（U+2580..U+259F）以及 Powerline 尖角（U+E0B0 / U+E0B2），
 *   消除等宽字体行距导致的竖线断缝与 TUI 表格错位。
 */
object TerminalBoxDrawing {
    const val WEIGHT_NONE = 0
    const val WEIGHT_LIGHT = 1
    const val WEIGHT_HEAVY = 2
    const val WEIGHT_DOUBLE = 3

    data class StrokeMetrics(val light: Float, val heavy: Float) {
        fun thickness(weight: Int): Float = if (weight == WEIGHT_HEAVY) heavy else light

        companion object {
            fun fromCell(cellWidth: Float, cellHeight: Float): StrokeMetrics {
                val light = max(1f, round(min(cellWidth, cellHeight) / 8f))
                return StrokeMetrics(light = light, heavy = max(light * 2f, light + 1f))
            }
        }
    }

    /** 几何渲染单元（单元格局部坐标 `[0..cellW] × [0..cellH]`）。 */
    sealed interface Primitive {
        data class FillRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val alphaScale: Float = 1f) : Primitive
        /** 圆角拐弯（╭╮╰╯）：startDeg 与 sweepDeg 与 Android Canvas.drawArc 一致。 */
        data class CornerArc(
            val cx: Float,
            val cy: Float,
            val rx: Float,
            val ry: Float,
            val startDeg: Float,
            val sweepDeg: Float,
            val strokeWidth: Float,
        ) : Primitive
        /** Powerline 实心尖角（rightPointing=true 为 U+E0B0 ，false 为 U+E0B2 ）。 */
        data class PowerlineTriangle(val rightPointing: Boolean) : Primitive
        /** Powerline 细尖角（rightPointing=true 为 U+E0B1 ，false 为 U+E0B3 ）。 */
        data class PowerlineChevron(val rightPointing: Boolean, val strokeWidth: Float) : Primitive
        /** Nerd Font 半圆胶囊（U+E0B4/E0B6 实心，U+E0B5/E0B7 空心细弧，源自 Warp `grid_renderer.rs`）。 */
        data class HalfCircle(val leftOpening: Boolean, val filled: Boolean, val strokeWidth: Float) : Primitive
        /** Nerd Font 斜切三角（U+E0B8  / U+E0BA  / U+E0BC  / U+E0BE ，源自 Warp `grid_renderer.rs`）。 */
        data class SlantTriangle(val corner: Int) : Primitive
    }

    const val SLANT_BOTTOM_LEFT = 0   // U+E0B8 
    const val SLANT_BOTTOM_RIGHT = 1  // U+E0BA 
    const val SLANT_TOP_LEFT = 2      // U+E0BC 
    const val SLANT_TOP_RIGHT = 3     // U+E0BE 

    fun isSupported(cp: Int): Boolean = when (cp) {
        in 0x2500..0x2503,
        in 0x250C..0x254B,
        in 0x2550..0x2570,
        in 0x2574..0x257F,
        in 0x2580..0x259F,
        in 0x2800..0x28FF,
        in 0xE0B0..0xE0B8,
        0xE0BA, 0xE0BC, 0xE0BE,
        in 0x1FB00..0x1FB3B,
        -> true
        else -> false
    }

    fun computePrimitives(cp: Int, cellWidth: Float, cellHeight: Float): List<Primitive> {
        if (cellWidth <= 0f || cellHeight <= 0f) return emptyList()
        val metrics = StrokeMetrics.fromCell(cellWidth, cellHeight)
        when (cp) {
            0xE0B0 -> return listOf(Primitive.PowerlineTriangle(rightPointing = true))
            0xE0B1 -> return listOf(Primitive.PowerlineChevron(rightPointing = true, strokeWidth = metrics.light))
            0xE0B2 -> return listOf(Primitive.PowerlineTriangle(rightPointing = false))
            0xE0B3 -> return listOf(Primitive.PowerlineChevron(rightPointing = false, strokeWidth = metrics.light))
            0xE0B4 -> return listOf(Primitive.HalfCircle(leftOpening = true, filled = true, strokeWidth = metrics.light))
            0xE0B5 -> return listOf(Primitive.HalfCircle(leftOpening = true, filled = false, strokeWidth = metrics.light))
            0xE0B6 -> return listOf(Primitive.HalfCircle(leftOpening = false, filled = true, strokeWidth = metrics.light))
            0xE0B7 -> return listOf(Primitive.HalfCircle(leftOpening = false, filled = false, strokeWidth = metrics.light))
            0xE0B8 -> return listOf(Primitive.SlantTriangle(SLANT_BOTTOM_LEFT))
            0xE0BA -> return listOf(Primitive.SlantTriangle(SLANT_BOTTOM_RIGHT))
            0xE0BC -> return listOf(Primitive.SlantTriangle(SLANT_TOP_LEFT))
            0xE0BE -> return listOf(Primitive.SlantTriangle(SLANT_TOP_RIGHT))
        }
        if (cp in 0x2800..0x28FF) return computeBraille(cp - 0x2800, cellWidth, cellHeight)
        if (cp in 0x1FB00..0x1FB3B) return computeSextant(cp - 0x1FB00, cellWidth, cellHeight)
        if (cp in 0x2580..0x259F) return computeBlockElement(cp, cellWidth, cellHeight)
        if (cp in 0x256D..0x2570) return computeRoundedCorner(cp, cellWidth, cellHeight, metrics)

        val edges = edgesFor(cp) ?: return emptyList()
        val up = (edges ushr 12) and 0xF
        val down = (edges ushr 8) and 0xF
        val left = (edges ushr 4) and 0xF
        val right = edges and 0xF

        val out = ArrayList<Primitive>(6)
        if (up == WEIGHT_DOUBLE || down == WEIGHT_DOUBLE || left == WEIGHT_DOUBLE || right == WEIGHT_DOUBLE) {
            pushDoubleLineRects(out, up, down, left, right, cellWidth, cellHeight, metrics)
        } else {
            pushSolidLineRects(out, up, down, left, right, cellWidth, cellHeight, metrics)
        }
        return out
    }

    private fun band(center: Float, thickness: Float): Pair<Float, Float> {
        val low = round(center - thickness * 0.5f)
        return low to (low + thickness)
    }

    private fun pushRect(out: MutableList<Primitive>, x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float = 1f) {
        if (x1 > x0 && y1 > y0) out += Primitive.FillRect(x0, y0, x1, y1, alpha)
    }

    /** 直接复刻 Warp `box_drawing.rs` 的零重叠实心/轻重混合制表算法。 */
    private fun pushSolidLineRects(
        out: MutableList<Primitive>,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
        w: Float,
        h: Float,
        m: StrokeMetrics,
    ) {
        val hasV = up != WEIGHT_NONE || down != WEIGHT_NONE
        val hasH = left != WEIGHT_NONE || right != WEIGHT_NONE
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 轻重直线过渡（╽ ╿ ╼ ╾）
        if (!hasH && up != WEIGHT_NONE && down != WEIGHT_NONE && up != down) {
            val splitY = round(cy)
            val (u0, u1) = band(cx, m.thickness(up))
            val (d0, d1) = band(cx, m.thickness(down))
            pushRect(out, max(0f, u0), 0f, min(w, u1), splitY)
            pushRect(out, max(0f, d0), splitY, min(w, d1), h)
            return
        }
        if (!hasV && left != WEIGHT_NONE && right != WEIGHT_NONE && left != right) {
            val splitX = round(cx)
            val (l0, l1) = band(cy, m.thickness(left))
            val (r0, r1) = band(cy, m.thickness(right))
            pushRect(out, 0f, max(0f, l0), splitX, min(h, l1))
            pushRect(out, splitX, max(0f, r0), w, min(h, r1))
            return
        }

        if (hasV) {
            val vWeight = if (up == WEIGHT_HEAVY || down == WEIGHT_HEAVY) WEIGHT_HEAVY else WEIGHT_LIGHT
            val hWeight = if (left == WEIGHT_HEAVY || right == WEIGHT_HEAVY) WEIGHT_HEAVY else WEIGHT_LIGHT
            val (vLow, vHigh) = band(cx, m.thickness(vWeight))
            val (hLow, hHigh) = band(cy, m.thickness(hWeight))

            if (up != WEIGHT_NONE && down != WEIGHT_NONE && up != down) {
                val (u0, u1) = band(cx, m.thickness(up))
                val (d0, d1) = band(cx, m.thickness(down))
                pushRect(out, max(0f, u0), 0f, min(w, u1), hLow)
                pushRect(out, max(0f, vLow), hLow, min(w, vHigh), hHigh)
                pushRect(out, max(0f, d0), hHigh, min(w, d1), h)
            } else {
                val top = if (up != WEIGHT_NONE) 0f else hLow
                val bottom = if (down != WEIGHT_NONE) h else hHigh
                pushRect(out, max(0f, vLow), top, min(w, vHigh), bottom)
            }

            // 水平臂严格停在垂直带外缘，避免交叉处半透明叠加变亮
            if (left != WEIGHT_NONE) {
                val (ll, lh) = band(cy, m.thickness(left))
                pushRect(out, 0f, max(0f, ll), max(0f, vLow), min(h, lh))
            }
            if (right != WEIGHT_NONE) {
                val (rl, rh) = band(cy, m.thickness(right))
                pushRect(out, min(w, vHigh), max(0f, rl), w, min(h, rh))
            }
        } else if (hasH) {
            val t = m.thickness(if (left == WEIGHT_HEAVY || right == WEIGHT_HEAVY) WEIGHT_HEAVY else WEIGHT_LIGHT)
            val (hLow, hHigh) = band(cy, t)
            val (cLow, cHigh) = band(cx, t)
            val x0 = if (left != WEIGHT_NONE) 0f else cLow
            val x1 = if (right != WEIGHT_NONE) w else cHigh
            pushRect(out, max(0f, x0), max(0f, hLow), min(w, x1), min(h, hHigh))
        }
    }

    private fun pushDoubleLineRects(
        out: MutableList<Primitive>,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
        w: Float,
        h: Float,
        m: StrokeMetrics,
    ) {
        val t = m.light
        val gap = max(1f, round(t * 1.1f))
        val cx = w * 0.5f
        val cy = h * 0.5f
        val vxL = round(cx - gap * 0.5f - t)
        val vxR = round(cx + gap * 0.5f)
        val hyT = round(cy - gap * 0.5f - t)
        val hyB = round(cy + gap * 0.5f)
        val (vc0, vc1) = band(cx, t)
        val (hc0, hc1) = band(cy, t)

        val vDouble = up == WEIGHT_DOUBLE || down == WEIGHT_DOUBLE
        val hDouble = left == WEIGHT_DOUBLE || right == WEIGHT_DOUBLE
        val vOuterL = if (vDouble) vxL else vc0
        val vOuterR = if (vDouble) vxR + t else vc1
        val hOuterT = if (hDouble) hyT else hc0
        val hOuterB = if (hDouble) hyB + t else hc1

        // 垂直臂
        if (up == WEIGHT_DOUBLE) {
            val yEndL = if (left == WEIGHT_DOUBLE) hyT + t else if (down == WEIGHT_DOUBLE) cy else hOuterB
            val yEndR = if (right == WEIGHT_DOUBLE) hyT + t else if (down == WEIGHT_DOUBLE) cy else hOuterB
            pushRect(out, max(0f, vxL), 0f, min(w, vxL + t), min(h, yEndL))
            pushRect(out, max(0f, vxR), 0f, min(w, vxR + t), min(h, yEndR))
        } else if (up == WEIGHT_LIGHT) {
            pushRect(out, max(0f, vc0), 0f, min(w, vc1), min(h, if (down == WEIGHT_LIGHT) cy else hOuterB))
        }
        if (down == WEIGHT_DOUBLE) {
            val yStartL = if (left == WEIGHT_DOUBLE) hyB else if (up == WEIGHT_DOUBLE) cy else hOuterT
            val yStartR = if (right == WEIGHT_DOUBLE) hyB else if (up == WEIGHT_DOUBLE) cy else hOuterT
            pushRect(out, max(0f, vxL), max(0f, yStartL), min(w, vxL + t), h)
            pushRect(out, max(0f, vxR), max(0f, yStartR), min(w, vxR + t), h)
        } else if (down == WEIGHT_LIGHT) {
            pushRect(out, max(0f, vc0), max(0f, if (up == WEIGHT_LIGHT) cy else hOuterT), min(w, vc1), h)
        }

        // 水平臂
        if (left == WEIGHT_DOUBLE) {
            val xEndT = if (up == WEIGHT_DOUBLE) vxL + t else if (right == WEIGHT_DOUBLE) cx else vOuterR
            val xEndB = if (down == WEIGHT_DOUBLE) vxL + t else if (right == WEIGHT_DOUBLE) cx else vOuterR
            pushRect(out, 0f, max(0f, hyT), min(w, xEndT), min(h, hyT + t))
            pushRect(out, 0f, max(0f, hyB), min(w, xEndB), min(h, hyB + t))
        } else if (left == WEIGHT_LIGHT) {
            pushRect(out, 0f, max(0f, hc0), min(w, if (right == WEIGHT_LIGHT) cx else vOuterR), min(h, hc1))
        }
        if (right == WEIGHT_DOUBLE) {
            val xStartT = if (up == WEIGHT_DOUBLE) vxR else if (left == WEIGHT_DOUBLE) cx else vOuterL
            val xStartB = if (down == WEIGHT_DOUBLE) vxR else if (left == WEIGHT_DOUBLE) cx else vOuterL
            pushRect(out, max(0f, xStartT), max(0f, hyT), w, min(h, hyT + t))
            pushRect(out, max(0f, xStartB), max(0f, hyB), w, min(h, hyB + t))
        } else if (right == WEIGHT_LIGHT) {
            pushRect(out, max(0f, if (left == WEIGHT_LIGHT) cx else vOuterL), max(0f, hc0), w, min(h, hc1))
        }
    }

    private fun computeRoundedCorner(cp: Int, w: Float, h: Float, m: StrokeMetrics): List<Primitive> {
        val (vx0, vx1) = band(w * 0.5f, m.light)
        val (hy0, hy1) = band(h * 0.5f, m.light)
        val midX = (vx0 + vx1) * 0.5f
        val midY = (hy0 + hy1) * 0.5f
        val rx = min(midX, w - midX)
        val ry = min(midY, h - midY)
        return when (cp) {
            0x256D -> listOf(Primitive.CornerArc(w, h, w - midX, h - midY, 180f, 90f, m.light)) // ╭
            0x256E -> listOf(Primitive.CornerArc(0f, h, midX, h - midY, 270f, 90f, m.light))     // ╮
            0x256F -> listOf(Primitive.CornerArc(0f, 0f, midX, midY, 0f, 90f, m.light))          // ╯
            0x2570 -> listOf(Primitive.CornerArc(w, 0f, w - midX, midY, 90f, 90f, m.light))      // ╰
            else -> listOf(Primitive.CornerArc(midX, midY, rx, ry, 0f, 90f, m.light))
        }
    }

    private fun computeBlockElement(cp: Int, w: Float, h: Float): List<Primitive> {
        val out = ArrayList<Primitive>(3)
        val mx = round(w * 0.5f)
        val my = round(h * 0.5f)
        when (cp) {
            0x2580 -> pushRect(out, 0f, 0f, w, my)                                  // ▀
            in 0x2581..0x2588 -> {                                                   // ▁..█
                val frac = (cp - 0x2580) / 8f
                pushRect(out, 0f, round(h * (1f - frac)), w, h)
            }
            in 0x2589..0x258F -> {                                                   // ▉..▏
                val frac = (0x2590 - cp) / 8f
                pushRect(out, 0f, 0f, round(w * frac), h)
            }
            0x2590 -> pushRect(out, mx, 0f, w, h)                                   // ▐
            0x2591 -> pushRect(out, 0f, 0f, w, h, alpha = 0.25f)                    // ░
            0x2592 -> pushRect(out, 0f, 0f, w, h, alpha = 0.50f)                    // ▒
            0x2593 -> pushRect(out, 0f, 0f, w, h, alpha = 0.75f)                    // ▓
            0x2594 -> pushRect(out, 0f, 0f, w, max(1f, round(h / 8f)))              // ▔
            0x2595 -> pushRect(out, w - max(1f, round(w / 8f)), 0f, w, h)           // ▕
            else -> {
                // 象限块 U+2596..U+259F（按 UL=1, UR=2, LL=4, LR=8 掩码组合）
                val mask = when (cp) {
                    0x2596 -> 4
                    0x2597 -> 8
                    0x2598 -> 1
                    0x2599 -> 1 or 4 or 8
                    0x259A -> 1 or 8
                    0x259B -> 1 or 2 or 4
                    0x259C -> 1 or 2 or 8
                    0x259D -> 2
                    0x259E -> 2 or 4
                    0x259F -> 2 or 4 or 8
                    else -> 0
                }
                if ((mask and 1) != 0) pushRect(out, 0f, 0f, mx, my)
                if ((mask and 2) != 0) pushRect(out, mx, 0f, w, my)
                if ((mask and 4) != 0) pushRect(out, 0f, my, mx, h)
                if ((mask and 8) != 0) pushRect(out, mx, my, w, h)
            }
        }
        return out
    }

    /** 2×4 Braille 点阵几何绘制（btop / vtop / nvtop / gnuplot 矢量曲线零锯齿）。 */
    private fun computeBraille(bits: Int, w: Float, h: Float): List<Primitive> {
        if (bits == 0) return emptyList()
        val out = ArrayList<Primitive>(8)
        val dotW = max(1.5f, round(w * 0.28f))
        val dotH = max(1.5f, round(h * 0.14f))
        val colX = floatArrayOf(round(w * 0.28f - dotW * 0.5f), round(w * 0.72f - dotW * 0.5f))
        val rowY = floatArrayOf(
            round(h * 0.14f - dotH * 0.5f),
            round(h * 0.38f - dotH * 0.5f),
            round(h * 0.62f - dotH * 0.5f),
            round(h * 0.86f - dotH * 0.5f),
        )
        // Unicode Braille 8-dot bit mapping: bit0=(0,0) bit1=(0,1) bit2=(0,2) bit3=(1,0) bit4=(1,1) bit5=(1,2) bit6=(0,3) bit7=(1,3)
        val colIdx = intArrayOf(0, 0, 0, 1, 1, 1, 0, 1)
        val rowIdx = intArrayOf(0, 1, 2, 0, 1, 2, 3, 3)
        for (b in 0 until 8) {
            if ((bits and (1 shl b)) != 0) {
                val x0 = max(0f, colX[colIdx[b]])
                val y0 = max(0f, rowY[rowIdx[b]])
                pushRect(out, x0, y0, min(w, x0 + dotW), min(h, y0 + dotH))
            }
        }
        return out
    }

    /** Unicode 2×3 Sextant 块拼图（U+1FB00..U+1FB3B，供 chafa / timg / 高密彩块视频渲染零缝隙拼接）。 */
    private fun computeSextant(offset: Int, w: Float, h: Float): List<Primitive> {
        // 标准 Sextant 码点跳过全空(0)、左半(21=0b010101)、右半(42=0b101010)、全满(63)
        var pattern = offset + 1
        if (pattern >= 21) pattern++
        if (pattern >= 42) pattern++
        val out = ArrayList<Primitive>(6)
        val mx = round(w * 0.5f)
        val y1 = round(h / 3f)
        val y2 = round(h * 2f / 3f)
        val xs = floatArrayOf(0f, mx, w)
        val ys = floatArrayOf(0f, y1, y2, h)
        for (bit in 0 until 6) {
            if ((pattern and (1 shl bit)) != 0) {
                val c = bit % 2
                val r = bit / 2
                pushRect(out, xs[c], ys[r], xs[c + 1], ys[r + 1])
            }
        }
        return out
    }

    private fun pack(up: Int, down: Int, left: Int, right: Int): Int =
        (up shl 12) or (down shl 8) or (left shl 4) or right

    private fun edgesFor(cp: Int): Int? {
        val n = WEIGHT_NONE; val l = WEIGHT_LIGHT; val h = WEIGHT_HEAVY; val d = WEIGHT_DOUBLE
        return when (cp) {
            0x2500 -> pack(n, n, l, l); 0x2501 -> pack(n, n, h, h)
            0x2502 -> pack(l, l, n, n); 0x2503 -> pack(h, h, n, n)
            0x250C -> pack(n, l, n, l); 0x250D -> pack(n, l, n, h); 0x250E -> pack(n, h, n, l); 0x250F -> pack(n, h, n, h)
            0x2510 -> pack(n, l, l, n); 0x2511 -> pack(n, l, h, n); 0x2512 -> pack(n, h, l, n); 0x2513 -> pack(n, h, h, n)
            0x2514 -> pack(l, n, n, l); 0x2515 -> pack(l, n, n, h); 0x2516 -> pack(h, n, n, l); 0x2517 -> pack(h, n, n, h)
            0x2518 -> pack(l, n, l, n); 0x2519 -> pack(l, n, h, n); 0x251A -> pack(h, n, l, n); 0x251B -> pack(h, n, h, n)
            0x251C -> pack(l, l, n, l); 0x251D -> pack(l, l, n, h); 0x251E -> pack(h, l, n, l); 0x251F -> pack(l, h, n, l)
            0x2520 -> pack(h, h, n, l); 0x2521 -> pack(h, l, n, h); 0x2522 -> pack(l, h, n, h); 0x2523 -> pack(h, h, n, h)
            0x2524 -> pack(l, l, l, n); 0x2525 -> pack(l, l, h, n); 0x2526 -> pack(h, l, l, n); 0x2527 -> pack(l, h, l, n)
            0x2528 -> pack(h, h, l, n); 0x2529 -> pack(h, l, h, n); 0x252A -> pack(l, h, h, n); 0x252B -> pack(h, h, h, n)
            0x252C -> pack(n, l, l, l); 0x252D -> pack(n, l, h, l); 0x252E -> pack(n, l, l, h); 0x252F -> pack(n, l, h, h)
            0x2530 -> pack(n, h, l, l); 0x2531 -> pack(n, h, h, l); 0x2532 -> pack(n, h, l, h); 0x2533 -> pack(n, h, h, h)
            0x2534 -> pack(l, n, l, l); 0x2535 -> pack(l, n, h, l); 0x2536 -> pack(l, n, l, h); 0x2537 -> pack(l, n, h, h)
            0x2538 -> pack(h, n, l, l); 0x2539 -> pack(h, n, h, l); 0x253A -> pack(h, n, l, h); 0x253B -> pack(h, n, h, h)
            0x253C -> pack(l, l, l, l); 0x253D -> pack(l, l, h, l); 0x253E -> pack(l, l, l, h); 0x253F -> pack(l, l, h, h)
            0x2540 -> pack(h, l, l, l); 0x2541 -> pack(l, h, l, l); 0x2542 -> pack(h, h, l, l); 0x2543 -> pack(h, l, h, l)
            0x2544 -> pack(h, l, l, h); 0x2545 -> pack(l, h, h, l); 0x2546 -> pack(l, h, l, h); 0x2547 -> pack(h, l, h, h)
            0x2548 -> pack(l, h, h, h); 0x2549 -> pack(h, h, h, l); 0x254A -> pack(h, h, l, h); 0x254B -> pack(h, h, h, h)
            // 双线及单双混合制表符
            0x2550 -> pack(n, n, d, d); 0x2551 -> pack(d, d, n, n)
            0x2552 -> pack(n, l, n, d); 0x2553 -> pack(n, d, n, l); 0x2554 -> pack(n, d, n, d)
            0x2555 -> pack(n, l, d, n); 0x2556 -> pack(n, d, l, n); 0x2557 -> pack(n, d, d, n)
            0x2558 -> pack(l, n, n, d); 0x2559 -> pack(d, n, n, l); 0x255A -> pack(d, n, n, d)
            0x255B -> pack(l, n, d, n); 0x255C -> pack(d, n, l, n); 0x255D -> pack(d, n, d, n)
            0x255E -> pack(l, l, n, d); 0x255F -> pack(d, d, n, l); 0x2560 -> pack(d, d, n, d)
            0x2561 -> pack(l, l, d, n); 0x2562 -> pack(d, d, l, n); 0x2563 -> pack(d, d, d, n)
            0x2564 -> pack(n, l, d, d); 0x2565 -> pack(n, d, l, l); 0x2566 -> pack(n, d, d, d)
            0x2567 -> pack(l, n, d, d); 0x2568 -> pack(d, n, l, l); 0x2569 -> pack(d, n, d, d)
            0x256A -> pack(l, l, d, d); 0x256B -> pack(d, d, l, l); 0x256C -> pack(d, d, d, d)
            // 短臂与轻重过渡
            0x2574 -> pack(n, n, l, n); 0x2575 -> pack(l, n, n, n); 0x2576 -> pack(n, n, n, l); 0x2577 -> pack(n, l, n, n)
            0x2578 -> pack(n, n, h, n); 0x2579 -> pack(h, n, n, n); 0x257A -> pack(n, n, n, h); 0x257B -> pack(n, h, n, n)
            0x257C -> pack(n, n, l, h); 0x257D -> pack(l, h, n, n); 0x257E -> pack(n, n, h, l); 0x257F -> pack(h, l, n, n)
            else -> null
        }
    }
}
