package com.galiais.agent.engine.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Set-of-Mark (SoM) 截屏标注与局部裁剪器：
 * 在截屏上为可交互 UI 节点绘制带索引编号的高对比边界框（如 `#0`, `#1`, `#2`），
 * 让多模态模型能够按精确序号（`nodeIndex`）点击控件，显著降低坐标偏差率。
 */
internal object MobileUseAnnotator {

    private val BOX_COLORS = intArrayOf(
        Color.parseColor("#E53935"),
        Color.parseColor("#1E88E5"),
        Color.parseColor("#43A047"),
        Color.parseColor("#FB8C00"),
        Color.parseColor("#8E24AA"),
        Color.parseColor("#00897B"),
    )

    fun cropBitmap(src: Bitmap, crop: IntArray): Bitmap {
        if (crop.size < 4) return src
        val x = crop[0].coerceIn(0, (src.width - 1).coerceAtLeast(0))
        val y = crop[1].coerceIn(0, (src.height - 1).coerceAtLeast(0))
        val w = crop[2].coerceIn(1, (src.width - x).coerceAtLeast(1))
        val h = crop[3].coerceIn(1, (src.height - y).coerceAtLeast(1))
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    fun drawSetOfMark(
        src: Bitmap,
        nodes: List<UiTreeNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val sx = out.width.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = out.height.toFloat() / screenHeight.coerceAtLeast(1)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (out.width / 320f).coerceIn(1.8f, 3.5f)
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (out.width / 34f).coerceIn(16f, 26f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val fm = textPaint.fontMetrics
        val tagH = (fm.descent - fm.ascent) + 6f

        for (node in nodes) {
            val l = (node.left * sx).coerceIn(1f, out.width - 2f)
            val t = (node.top * sy).coerceIn(1f, out.height - 2f)
            val r = (node.right * sx).coerceIn(l + 2f, out.width - 1f)
            val b = (node.bottom * sy).coerceIn(t + 2f, out.height - 1f)
            val color = BOX_COLORS[node.index % BOX_COLORS.size]
            strokePaint.color = color
            tagBgPaint.color = color
            canvas.drawRect(l, t, r, b, strokePaint)

            val label = "#${node.index}"
            val tw = textPaint.measureText(label) + 10f
            val tagTop = if (t >= tagH) t - tagH else t
            val tagLeft = l.coerceIn(0f, (out.width - tw).coerceAtLeast(0f))
            val tagRect = RectF(tagLeft, tagTop, tagLeft + tw, tagTop + tagH)
            canvas.drawRoundRect(tagRect, 4f, 4f, tagBgPaint)
            canvas.drawText(label, tagLeft + 5f, tagTop - fm.ascent + 2f, textPaint)
        }
        return out
    }
}
