package com.galiais.agent.engine.terminal

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 终端内联图片/视频帧放置元数据（支持 iTerm2 OSC 1337、Kitty Graphics f=24/32/100+zlib 与 DEC Sixel，100% 纯 Kotlin）。 */
data class TerminalInlineImage(
    val id: Int,
    var absRow: Int,
    val col: Int,
    val colsSpan: Int,
    val rowsSpan: Int,
    val data: ByteArray = ByteArray(0),
    val argbPixels: IntArray? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    val revision: Long = 1L,
    val name: String = "image",
) {
    /** 检测是否为动态 GIF 或动态 WebP（供画布启用 Android 原生帧动画渲染）。 */
    fun isAnimatedEncoded(): Boolean {
        if (data.size >= 6 &&
            data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()
        ) return true
        if (data.size >= 34 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() && data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
        ) {
            val headStr = String(data, 12, min(data.size - 12, 64), Charsets.ISO_8859_1)
            if ("ANIM" in headStr) return true
        }
        return false
    }
}

/**
 * 有界终端内联图片与实时视频帧存储（源自 Warp `iterm_image.rs` / `kitty.rs` 并扩展 DEC Sixel）：
 * - 支持 iTerm2 `OSC 1337`（含动态 GIF/WebP）、Kitty `APC G`（PNG `f=100` / 原始 RGB `f=24` / RGBA `f=32` / zlib `o=z`）
 *   以及 DEC Sixel (`DCS ... q`)；
 * - 同 ID 或同 `(absRow, col)` 坐标的连续帧执行原地覆盖（递增 `revision`），支持 `mpv --vo=sixel` / `mpv --vo=kitty` / `chafa` 60fps 流畅播放视频！
 */
class TerminalImageStore(
    private val maxImages: Int = 24,
    private val maxBytesPerImage: Int = 4 * 1024 * 1024,
) {
    private var nextId = 1
    private var frameRev = 1L
    private val images = ArrayDeque<TerminalInlineImage>(16)

    // iTerm2 分片缓冲
    private var pendingItermMeta: ItermMeta? = null
    private val pendingItermB64 = StringBuilder()

    // Kitty 分片缓冲
    private var pendingKittyParams: Map<String, String>? = null
    private val pendingKittyB64 = StringBuilder()

    private data class ItermMeta(
        val inline: Boolean,
        val widthCells: Int?,
        val heightCells: Int?,
        val name: String,
    )

    fun clear() {
        images.clear()
        pendingItermMeta = null
        pendingItermB64.clear()
        pendingKittyParams = null
        pendingKittyB64.clear()
    }

    fun shiftRowsUp(delta: Int) {
        if (delta <= 0 || images.isEmpty()) return
        val iter = images.iterator()
        while (iter.hasNext()) {
            val img = iter.next()
            img.absRow -= delta
            if (img.absRow + img.rowsSpan <= 0) iter.remove()
        }
    }

    fun snapshotVisible(firstAbsRow: Int, rows: Int): List<TerminalInlineImage> {
        if (images.isEmpty()) return emptyList()
        val lastAbs = firstAbsRow + rows - 1
        return images.filter { img ->
            val endRow = img.absRow + img.rowsSpan - 1
            endRow >= firstAbsRow && img.absRow <= lastAbs
        }
    }

    fun count(): Int = images.size

    /** 处理 iTerm2 `OSC 1337` 负载；若成功落图则返回需要向下推进的终端行数。 */
    fun handleOsc1337(payload: String, absRow: Int, col: Int, maxCols: Int, maxRows: Int): Int {
        return when {
            payload.startsWith("File=") -> {
                val colon = payload.indexOf(':')
                if (colon < 0) return 0
                val meta = parseItermMeta(payload.substring(5, colon))
                if (!meta.inline) return 0
                val raw = decodeBase64Strict(payload.substring(colon + 1)) ?: return 0
                placeImage(raw, meta.name, absRow, col, meta.widthCells, meta.heightCells, maxCols, maxRows)
            }
            payload.startsWith("MultipartFile=") -> {
                val meta = parseItermMeta(payload.substring(14))
                pendingItermMeta = meta.takeIf { it.inline }
                pendingItermB64.clear()
                0
            }
            payload.startsWith("FilePart=") -> {
                if (pendingItermMeta != null && pendingItermB64.length < maxBytesPerImage * 2) {
                    pendingItermB64.append(payload.substring(9))
                }
                0
            }
            payload.startsWith("FileEnd") -> {
                val meta = pendingItermMeta ?: return 0
                pendingItermMeta = null
                val raw = decodeBase64Strict(pendingItermB64.toString())
                pendingItermB64.clear()
                if (raw == null) 0 else placeImage(raw, meta.name, absRow, col, meta.widthCells, meta.heightCells, maxCols, maxRows)
            }
            else -> 0
        }
    }

    /**
     * 处理 Kitty Graphics `APC G <k=v,...>;<b64>`；返回 `(推进的行数, 需回发给 PTY 的应答串)`。
     */
    fun handleKittyApc(payload: String, absRow: Int, col: Int, maxCols: Int, maxRows: Int): Pair<Int, String?> {
        if (!payload.startsWith("G")) return 0 to null
        val body = payload.substring(1)
        val semi = body.indexOf(';')
        val ctrlStr = if (semi >= 0) body.substring(0, semi) else body
        val dataPart = if (semi >= 0) body.substring(semi + 1) else ""

        val params = HashMap<String, String>()
        if (ctrlStr.isNotEmpty()) {
            for (part in ctrlStr.split(',')) {
                val eq = part.indexOf('=')
                if (eq > 0) params[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        val action = params["a"] ?: pendingKittyParams?.get("a") ?: "T"
        if (action == "d") {
            val id = params["i"]?.toIntOrNull()
            if (id != null) images.removeAll { it.id == id } else images.clear()
            return 0 to null
        }
        val quiet = params["q"]?.toIntOrNull() ?: 0
        val reqId = params["i"]?.toIntOrNull()
        if (action == "q") {
            val resp = if (quiet == 2) null else "\u001b_Gi=${reqId ?: 1};OK\u001b\\"
            return 0 to resp
        }

        val more = params["m"] == "1"
        if (pendingKittyParams == null) {
            pendingKittyParams = params
            pendingKittyB64.clear()
        }
        if (pendingKittyB64.length < maxBytesPerImage * 2) {
            pendingKittyB64.append(dataPart)
        }
        if (more) return 0 to null

        val merged = pendingKittyParams ?: params
        val b64 = pendingKittyB64.toString()
        pendingKittyParams = null
        pendingKittyB64.clear()

        val mergedQuiet = merged["q"]?.toIntOrNull() ?: quiet
        val mergedId = merged["i"]?.toIntOrNull() ?: reqId
        val fmt = merged["f"]?.toIntOrNull() ?: 100
        val medium = merged["t"] ?: "d"
        if (medium != "d" || (fmt != 100 && fmt != 24 && fmt != 32)) {
            val resp = if (mergedQuiet >= 1) null else "\u001b_Gi=${mergedId ?: 1};ENOTSUP:unsupported format\u001b\\"
            return 0 to resp
        }
        val decodedB64 = decodeBase64Strict(b64) ?: return 0 to null
        val raw = if (merged["o"] == "z") inflateZlibBounded(decodedB64) ?: return 0 to null else decodedB64
        val colsSpan = merged["c"]?.toIntOrNull()
        val rowsSpan = merged["r"]?.toIntOrNull()
        val advance = if (fmt == 100) {
            placeImage(raw, "kitty-${mergedId ?: nextId}", absRow, col, colsSpan, rowsSpan, maxCols, maxRows, forcedId = mergedId)
        } else {
            val srcW = merged["s"]?.toIntOrNull() ?: 0
            val srcH = merged["v"]?.toIntOrNull() ?: 0
            val argb = convertRawRgbToArgb(raw, srcW, srcH, hasAlpha = fmt == 32) ?: return 0 to null
            placeRawPixels(argb, srcW, srcH, "kitty-${mergedId ?: nextId}", absRow, col, colsSpan, rowsSpan, maxCols, maxRows, forcedId = mergedId)
        }
        val resp = if (mergedQuiet >= 1) null else "\u001b_Gi=${mergedId ?: (nextId - 1)};OK\u001b\\"
        return advance to resp
    }

    /** 处理 DEC Sixel (`DCS ... q`) 图形与实时视频帧，原地更新相同锚点以支持 60fps 视频播放。 */
    fun handleSixel(dcsPayload: String, absRow: Int, col: Int, maxCols: Int, maxRows: Int): Int {
        val frame = TerminalSixelDecoder.decode(dcsPayload) ?: return 0
        return placeRawPixels(frame.argb, frame.width, frame.height, "sixel", absRow, col, null, null, maxCols, maxRows)
    }

    private fun placeRawPixels(
        argb: IntArray,
        imgW: Int,
        imgH: Int,
        name: String,
        absRow: Int,
        col: Int,
        reqCols: Int?,
        reqRows: Int?,
        maxCols: Int,
        maxRows: Int,
        forcedId: Int? = null,
    ): Int {
        if (imgW <= 0 || imgH <= 0 || argb.size < imgW * imgH) return 0
        val availCols = max(4, maxCols - col)
        val colsSpan = (reqCols ?: min(availCols, max(8, ceil(imgW / 18.0).toInt()))).coerceIn(2, availCols)
        val estRows = ceil((colsSpan * (imgH.toDouble() / imgW.toDouble())) * 0.5).toInt()
        val rowsSpan = (reqRows ?: estRows).coerceIn(2, min(maxRows.coerceAtLeast(4), 24))
        val existingIdx = images.indexOfFirst { (forcedId != null && it.id == forcedId) || (it.absRow == absRow && it.col == col) }
        val id = forcedId ?: if (existingIdx >= 0) images[existingIdx].id else nextId++
        val item = TerminalInlineImage(
            id = id,
            absRow = absRow,
            col = col,
            colsSpan = colsSpan,
            rowsSpan = rowsSpan,
            argbPixels = argb,
            pixelWidth = imgW,
            pixelHeight = imgH,
            revision = ++frameRev,
            name = name,
        )
        if (existingIdx >= 0) {
            images[existingIdx] = item
        } else {
            images.addLast(item)
            while (images.size > maxImages) images.removeFirst()
        }
        return rowsSpan
    }

    private fun placeImage(
        raw: ByteArray,
        name: String,
        absRow: Int,
        col: Int,
        reqCols: Int?,
        reqRows: Int?,
        maxCols: Int,
        maxRows: Int,
        forcedId: Int? = null,
    ): Int {
        if (raw.isEmpty() || raw.size > maxBytesPerImage) return 0
        val (imgW, imgH) = probeImageSize(raw)
        val availCols = max(4, maxCols - col)
        val colsSpan = (reqCols ?: min(availCols, max(8, ceil(imgW / 18.0).toInt()))).coerceIn(2, availCols)
        val estRows = if (imgW > 0 && imgH > 0) {
            ceil((colsSpan * (imgH.toDouble() / imgW.toDouble())) * 0.5).toInt()
        } else 8
        val rowsSpan = (reqRows ?: estRows).coerceIn(2, min(maxRows.coerceAtLeast(4), 24))
        val existingIdx = images.indexOfFirst { (forcedId != null && it.id == forcedId) || (it.absRow == absRow && it.col == col) }
        val id = forcedId ?: if (existingIdx >= 0) images[existingIdx].id else nextId++
        val item = TerminalInlineImage(
            id = id,
            absRow = absRow,
            col = col,
            colsSpan = colsSpan,
            rowsSpan = rowsSpan,
            data = raw,
            revision = ++frameRev,
            name = name,
        )
        if (existingIdx >= 0) {
            images[existingIdx] = item
        } else {
            images.addLast(item)
            while (images.size > maxImages) images.removeFirst()
        }
        return rowsSpan
    }

    private fun convertRawRgbToArgb(raw: ByteArray, w: Int, h: Int, hasAlpha: Boolean): IntArray? {
        if (w <= 0 || h <= 0 || w > 1600 || h > 1200) return null
        val bpp = if (hasAlpha) 4 else 3
        val total = w * h
        if (raw.size < total * bpp) return null
        val out = IntArray(total)
        var off = 0
        for (i in 0 until total) {
            val r = raw[off].toInt() and 0xFF
            val g = raw[off + 1].toInt() and 0xFF
            val b = raw[off + 2].toInt() and 0xFF
            val a = if (hasAlpha) (raw[off + 3].toInt() and 0xFF) else 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            off += bpp
        }
        return out
    }

    private fun inflateZlibBounded(compressed: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(min(compressed.size * 3, 65536))
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished() && out.size() < maxBytesPerImage) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        out.toByteArray()
    }.getOrNull()

    private fun parseItermMeta(metaStr: String): ItermMeta {
        var inline = false
        var widthCells: Int? = null
        var heightCells: Int? = null
        var name = "image"
        for (kv in metaStr.split(';')) {
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val k = kv.substring(0, eq).trim()
            val v = kv.substring(eq + 1).trim()
            when (k) {
                "inline" -> inline = v == "1" || v.equals("true", ignoreCase = true)
                "width" -> widthCells = parseCellDim(v, 40)
                "height" -> heightCells = parseCellDim(v, 14)
                "name" -> decodeBase64Strict(v)?.toString(Charsets.UTF_8)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
            }
        }
        return ItermMeta(inline, widthCells, heightCells, name)
    }

    private fun parseCellDim(v: String, refMax: Int): Int? = when {
        v.endsWith("px") -> v.dropLast(2).toIntOrNull()?.let { max(2, it / 16) }
        v.endsWith("%") -> v.dropLast(1).toIntOrNull()?.let { max(2, (refMax * it.coerceIn(5, 100)) / 100) }
        else -> v.toIntOrNull()?.takeIf { it > 0 }
    }

    /** 轻量解析 PNG/JPEG 头部宽高（零 Android 依赖，确保纯 JVM 单测稳定运行）。 */
    private fun probeImageSize(data: ByteArray): Pair<Int, Int> {
        if (data.size >= 24 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) {
            val w = ((data[16].toInt() and 0xFF) shl 24) or ((data[17].toInt() and 0xFF) shl 16) or ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
            val h = ((data[20].toInt() and 0xFF) shl 24) or ((data[21].toInt() and 0xFF) shl 16) or ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
            if (w in 1..16384 && h in 1..16384) return w to h
        }
        return 320 to 200
    }

    private fun decodeBase64Strict(b64: String): ByteArray? {
        val clean = buildString(b64.length) {
            for (ch in b64) if (!ch.isWhitespace()) append(ch)
        }
        if (clean.isEmpty()) return null
        return runCatching { java.util.Base64.getDecoder().decode(clean) }.getOrNull()
    }
}
