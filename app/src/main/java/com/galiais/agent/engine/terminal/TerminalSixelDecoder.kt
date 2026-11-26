package com.galiais.agent.engine.terminal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯 Kotlin DEC Sixel 图形解码器（`DCS P1 ; P2 ; P3 q <sixel_data> ST`）：
 * - 支持 VT340 默认 16 色板、`#Pc;2;r;g;b` RGB (0..100) 与 `#Pc;1;h;l;s` HLS 调色板定义；
 * - 支持 `!count<char>` RLE 游程压缩、`"Pan;Pad;Ph;Pv` 光栅声明、`$` 行首回车与 `-` 6px 换带；
 * - 直接输出 ARGB `IntArray` 供画布零编码 60fps 渲染（兼容 `img2sixel`、`chafa -f sixel`、`lsix`、`mpv --vo=sixel`）。
 */
object TerminalSixelDecoder {
    data class DecodedFrame(val width: Int, val height: Int, val argb: IntArray)

    private const val MAX_W = 1600
    private const val MAX_H = 1200

    private val DEFAULT_PALETTE = intArrayOf(
        0xFF000000.toInt(), 0xFF3333CC.toInt(), 0xFFCC2121.toInt(), 0xFF33CC33.toInt(),
        0xFFCC33CC.toInt(), 0xFF33CCCC.toInt(), 0xFFCCCC33.toInt(), 0xFF878787.toInt(),
        0xFF424242.toInt(), 0xFF545499.toInt(), 0xFF994242.toInt(), 0xFF549954.toInt(),
        0xFF995499.toInt(), 0xFF549999.toInt(), 0xFF999954.toInt(), 0xFFCCCCCC.toInt(),
    )

    fun decode(dcsPayload: String): DecodedFrame? {
        val qIdx = dcsPayload.indexOf('q')
        if (qIdx < 0 || qIdx + 1 >= dcsPayload.length) return null
        val header = dcsPayload.substring(0, qIdx)
        val headerParts = header.split(';')
        val p2 = headerParts.getOrNull(1)?.toIntOrNull() ?: 0
        val transparentBg = p2 == 1

        val palette = IntArray(256) { i ->
            if (i < DEFAULT_PALETTE.size) DEFAULT_PALETTE[i] else 0xFFFFFFFF.toInt()
        }

        // 先进行轻量单遍解析，动态扩容行缓冲，再裁剪到真实边界
        var rasterW = 0
        var rasterH = 0
        var curW = 128
        var curH = 64
        var pixels = IntArray(curW * curH) { if (transparentBg) 0 else palette[0] }
        var maxX = 0
        var maxY = 0
        var x = 0
        var y = 0
        var curColor = palette[7]

        fun ensureCapacity(needW: Int, needH: Int) {
            val nw = min(MAX_W, max(curW, max(needW, curW * 2)))
            val nh = min(MAX_H, max(curH, max(needH, curH * 2)))
            if (nw == curW && nh == curH) return
            val bg = if (transparentBg) 0 else palette[0]
            val next = IntArray(nw * nh) { bg }
            val copyW = min(curW, nw)
            val copyH = min(curH, nh)
            for (row in 0 until copyH) {
                System.arraycopy(pixels, row * curW, next, row * nw, copyW)
            }
            pixels = next
            curW = nw
            curH = nh
        }

        fun paintSixel(bits: Int, repeatCount: Int) {
            val count = repeatCount.coerceIn(1, MAX_W)
            if (y >= MAX_H || x >= MAX_W) {
                x += count
                return
            }
            val endX = min(MAX_W, x + count)
            val endY = min(MAX_H, y + 6)
            if (endX > curW || endY > curH) ensureCapacity(endX, endY)
            if (bits != 0) {
                for (bit in 0 until 6) {
                    if ((bits and (1 shl bit)) != 0) {
                        val py = y + bit
                        if (py < curH) {
                            val rowOff = py * curW
                            for (px in x until min(endX, curW)) {
                                pixels[rowOff + px] = curColor
                            }
                            if (py + 1 > maxY) maxY = py + 1
                        }
                    }
                }
            }
            if (endX > maxX) maxX = endX
            x += count
        }

        val len = dcsPayload.length
        var i = qIdx + 1
        while (i < len) {
            val ch = dcsPayload[i]
            when {
                ch == '$' -> {
                    x = 0
                    i++
                }
                ch == '-' -> {
                    x = 0
                    y += 6
                    i++
                }
                ch == '!' -> {
                    i++
                    var rep = 0
                    while (i < len && dcsPayload[i] in '0'..'9') {
                        rep = (rep * 10 + (dcsPayload[i] - '0')).coerceAtMost(MAX_W)
                        i++
                    }
                    if (i < len) {
                        val c = dcsPayload[i].code
                        if (c in 63..126) paintSixel(c - 63, rep.coerceAtLeast(1))
                        i++
                    }
                }
                ch == '#' -> {
                    i++
                    val nums = ArrayList<Int>(5)
                    var acc = 0
                    var hasDigit = false
                    while (i < len) {
                        val c = dcsPayload[i]
                        if (c in '0'..'9') {
                            acc = acc * 10 + (c - '0')
                            hasDigit = true
                            i++
                        } else if (c == ';') {
                            nums += if (hasDigit) acc else 0
                            acc = 0
                            hasDigit = false
                            i++
                        } else {
                            break
                        }
                    }
                    if (hasDigit) nums += acc
                    val reg = (nums.getOrNull(0) ?: 0).coerceIn(0, 255)
                    if (nums.size >= 5) {
                        val space = nums[1]
                        val argb = when (space) {
                            2 -> {
                                val r = (nums[2].coerceIn(0, 100) * 255 + 50) / 100
                                val g = (nums[3].coerceIn(0, 100) * 255 + 50) / 100
                                val b = (nums[4].coerceIn(0, 100) * 255 + 50) / 100
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            1 -> hlsToArgb(nums[2], nums[3], nums[4])
                            else -> palette[reg]
                        }
                        palette[reg] = argb
                    }
                    curColor = palette[reg]
                }
                ch == '"' -> {
                    i++
                    val nums = ArrayList<Int>(4)
                    var acc = 0
                    var hasDigit = false
                    while (i < len) {
                        val c = dcsPayload[i]
                        if (c in '0'..'9') {
                            acc = acc * 10 + (c - '0')
                            hasDigit = true
                            i++
                        } else if (c == ';') {
                            nums += if (hasDigit) acc else 0
                            acc = 0
                            hasDigit = false
                            i++
                        } else {
                            break
                        }
                    }
                    if (hasDigit) nums += acc
                    if (nums.size >= 4) {
                        rasterW = nums[2].coerceIn(0, MAX_W)
                        rasterH = nums[3].coerceIn(0, MAX_H)
                        if (rasterW > 0 && rasterH > 0) ensureCapacity(rasterW, rasterH)
                    }
                }
                ch.code in 63..126 -> {
                    paintSixel(ch.code - 63, 1)
                    i++
                }
                else -> i++
            }
        }

        val finalW = max(maxX, rasterW).coerceIn(0, curW)
        val finalH = max(maxY, rasterH).coerceIn(0, curH)
        if (finalW <= 0 || finalH <= 0) return null

        val out = IntArray(finalW * finalH)
        for (row in 0 until finalH) {
            System.arraycopy(pixels, row * curW, out, row * finalW, finalW)
        }
        return DecodedFrame(finalW, finalH, out)
    }

    private fun hlsToArgb(hDeg: Int, lPct: Int, sPct: Int): Int {
        val h = ((hDeg % 360) + 360) % 360
        val l = lPct.coerceIn(0, 100) / 100f
        val s = sPct.coerceIn(0, 100) / 100f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = l - c * 0.5f
        val (rf, gf, bf) = when (h) {
            in 0 until 60 -> Triple(c, x, 0f)
            in 60 until 120 -> Triple(x, c, 0f)
            in 120 until 180 -> Triple(0f, c, x)
            in 180 until 240 -> Triple(0f, x, c)
            in 240 until 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rf + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
