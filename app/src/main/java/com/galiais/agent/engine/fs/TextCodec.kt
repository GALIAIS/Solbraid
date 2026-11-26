package com.galiais.agent.engine.fs

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** 支持的文本编码选项。 */
enum class TextEncoding(val id: String, val label: String, val charsetName: String, val withBom: Boolean = false) {
    UTF_8("utf-8", "UTF-8", "UTF-8", false),
    UTF_8_BOM("utf-8-bom", "UTF-8 BOM", "UTF-8", true),
    GB18030("gb18030", "GBK / GB18030", "GB18030", false),
    UTF_16_LE("utf-16le", "UTF-16 LE", "UTF-16LE", true),
    UTF_16_BE("utf-16be", "UTF-16 BE", "UTF-16BE", true),
    SHIFT_JIS("shift_jis", "Shift-JIS", "Shift_JIS", false),
    BIG5("big5", "Big5 繁体", "Big5", false),
    ISO_8859_1("iso-8859-1", "ISO-8859-1", "ISO-8859-1", false),
    ;

    fun charset(): Charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
}

/** 换行符格式。 */
enum class LineEnding(val label: String, val seq: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
    ;
}

/** 查看器分类（由扩展名 + 文件头魔数共同判定）。 */
enum class ViewerCategory {
    TEXT_CODE,
    IMAGE,
    ANIMATED_IMAGE,
    SVG,
    PDF,
    VIDEO,
    AUDIO,
    MARKDOWN,
    HTML,
    JSON_STRUCTURED,
    CSV_TABLE,
    SQLITE,
    ARCHIVE,
    APK,
    FONT,
    BINARY_HEX,
}

data class DecodedTextDocument(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val totalBytes: Long,
    val truncated: Boolean,
    val hasTrailingNewline: Boolean = false,
    val mixedLineEndings: Boolean = false,
    val revision: FileRevision? = null,
)

data class TextPageChunk(
    val content: String,
    val startOffset: Long,
    val endOffset: Long,
    val totalBytes: Long,
    val pageIndex: Int,
    val totalPages: Int,
    val startLineNumber: Int = 1,
    val lineCount: Int = 0,
)

/** 超大文件稀疏行号索引（每 strideBytes 建立 1 个锚点，100MB 仅占用 < 4KB 内存）。 */
data class SparseLineIndex(
    val fileSize: Long,
    val strideBytes: Int,
    val lineAtAnchor: IntArray,
    val totalLines: Int,
    val encoding: TextEncoding = TextEncoding.UTF_8,
)

/** 编码探测、换行符规范化、魔数识别、大文件分页读取与哈希校验工具。 */
object TextCodec {
    private val CODE_EXTS = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rs", "go", "c", "h",
        "cpp", "cc", "cxx", "hpp", "cs", "swift", "dart", "php", "rb", "lua", "pl", "r", "scala",
        "sh", "bash", "zsh", "fish", "bat", "ps1", "yml", "yaml", "toml", "ini", "conf", "cfg",
        "properties", "env", "xml", "css", "scss", "less", "sql", "graphql", "proto", "gradle",
        "groovy", "vue", "svelte", "tex", "bib", "diff", "patch", "log", "txt", "gitignore",
        "dockerfile", "makefile", "cmake", "lock", "rc", "service", "rules", "list",
    )
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "heic", "heif", "avif", "ico", "tif", "tiff")
    private val ANIM_IMAGE_EXTS = setOf("gif", "webp")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "ts", "avi", "flv")
    private val AUDIO_EXTS = setOf("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "amr", "wma", "mid")
    private val ARCHIVE_EXTS = setOf("zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "jar", "aar", "war")
    private val FONT_EXTS = setOf("ttf", "otf", "woff", "woff2")
    private val SQLITE_EXTS = setOf("db", "sqlite", "sqlite3", "db3")

    fun matchesMediaFilter(node: FileNode, filter: MediaFilter): Boolean {
        if (filter == MediaFilter.ALL || node.isDirectory) return true
        val ext = node.extension
        return when (filter) {
            MediaFilter.ALL -> true
            MediaFilter.CODE -> ext in CODE_EXTS || ext in setOf("json", "md", "markdown", "html", "htm", "csv", "tsv", "svg")
            MediaFilter.IMAGE -> ext in IMAGE_EXTS || ext in ANIM_IMAGE_EXTS || ext == "svg"
            MediaFilter.VIDEO -> ext in VIDEO_EXTS
            MediaFilter.AUDIO -> ext in AUDIO_EXTS
            MediaFilter.DOC -> ext in setOf("pdf", "md", "markdown", "txt", "html", "htm", "csv", "tsv", "tex", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
            MediaFilter.ARCHIVE -> ext in ARCHIVE_EXTS || ext == "apk"
        }
    }

    fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "aac", "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "apk" -> "application/vnd.android.package-archive"
            "zip", "jar", "aar" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            "xz", "txz" -> "application/x-xz"
            "7z" -> "application/x-7z-compressed"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "tsv" -> "text/tab-separated-values"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "db", "sqlite", "sqlite3" -> "application/vnd.sqlite3"
            else -> if (ext in CODE_EXTS) "text/plain" else "application/octet-stream"
        }
    }

    /** 结合扩展名与文件头部魔数精确识别文件查看器类型。 */
    fun classifyFile(name: String, headerBytes: ByteArray): ViewerCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        val lowerName = name.lowercase()
        if (ext == "apk") return ViewerCategory.APK
        if (ext == "pdf" || startsWithAscii(headerBytes, "%PDF-")) return ViewerCategory.PDF
        if (ext == "svg") return ViewerCategory.SVG
        if (ext in ANIM_IMAGE_EXTS) return ViewerCategory.ANIMATED_IMAGE
        if (ext in IMAGE_EXTS) return ViewerCategory.IMAGE
        if (ext in VIDEO_EXTS) return ViewerCategory.VIDEO
        if (ext in AUDIO_EXTS) return ViewerCategory.AUDIO
        if (ext in FONT_EXTS) return ViewerCategory.FONT
        if (ext in SQLITE_EXTS || startsWithAscii(headerBytes, "SQLite format 3\u0000")) return ViewerCategory.SQLITE
        if (ext in ARCHIVE_EXTS) return ViewerCategory.ARCHIVE
        if (ext == "json") return ViewerCategory.JSON_STRUCTURED
        if (ext == "csv" || ext == "tsv") return ViewerCategory.CSV_TABLE
        if (ext == "md" || ext == "markdown") return ViewerCategory.MARKDOWN
        if (ext == "html" || ext == "htm") return ViewerCategory.HTML

        // 魔数兜底识别（无后缀文件也能正确识别）
        if (headerBytes.size >= 4) {
            if (headerBytes[0] == 0x89.toByte() && headerBytes[1] == 0x50.toByte() && headerBytes[2] == 0x4E.toByte() && headerBytes[3] == 0x47.toByte()) return ViewerCategory.IMAGE
            if (headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xD8.toByte() && headerBytes[2] == 0xFF.toByte()) return ViewerCategory.IMAGE
            if (startsWithAscii(headerBytes, "GIF87a") || startsWithAscii(headerBytes, "GIF89a")) return ViewerCategory.ANIMATED_IMAGE
            if (headerBytes.size >= 12 && startsWithAscii(headerBytes, "RIFF") && startsWithAscii(headerBytes.copyOfRange(8, 12), "WEBP")) return ViewerCategory.ANIMATED_IMAGE
            if (headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() && headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte()) return ViewerCategory.ARCHIVE
            if (headerBytes[0] == 0x7F.toByte() && headerBytes[1] == 0x45.toByte() && headerBytes[2] == 0x4C.toByte() && headerBytes[3] == 0x46.toByte()) return ViewerCategory.BINARY_HEX
        }
        if (lowerName in setOf("dockerfile", "makefile", "cmakelists.txt", "gemfile", "podfile", "procfile", "hosts", "fstab", "passwd", "group", "resolv.conf", "build.prop")) {
            return ViewerCategory.TEXT_CODE
        }
        if (looksBinary(headerBytes)) return ViewerCategory.BINARY_HEX
        return ViewerCategory.TEXT_CODE
    }

    fun detectMagicDescription(header: ByteArray): String {
        if (header.isEmpty()) return "空文件 (0 bytes)"
        return when {
            startsWithAscii(header, "%PDF-") -> "PDF 文档"
            startsWithAscii(header, "SQLite format 3") -> "SQLite 3 数据库"
            header.size >= 4 && header[0] == 0x7F.toByte() && header[1] == 0x45.toByte() && header[2] == 0x4C.toByte() && header[3] == 0x46.toByte() ->
                if (header.size > 4 && header[4] == 2.toByte()) "ELF 64-bit 可执行/动态库" else "ELF 32-bit 可执行/动态库"
            header.size >= 4 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "PNG 图像"
            header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "JPEG 图像"
            startsWithAscii(header, "GIF8") -> "GIF 动图"
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> "ZIP / APK / JAR 归档"
            header.size >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> "GZIP 压缩流"
            header.size >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() && header[2] == 0x7A.toByte() -> "XZ 压缩流"
            startsWithAscii(header, "7z\u00BC\u00AF") -> "7-Zip 归档"
            startsWithAscii(header, "dex\n") -> "Android Dalvik DEX 字节码"
            startsWithAscii(header, "ID3") -> "MP3 音频 (ID3)"
            startsWithAscii(header, "fLaC") -> "FLAC 无损音频"
            startsWithAscii(header, "OggS") -> "OGG / Opus 容器"
            header.size >= 8 && startsWithAscii(header.copyOfRange(4, 8), "ftyp") -> "MP4 / MOV / HEIF 媒体容器"
            startsWithAscii(header, "#!") -> "可执行脚本（Shebang）"
            looksBinary(header) -> "未知二进制数据"
            else -> "纯文本 / 源码"
        }
    }

    fun looksBinary(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        // UTF-16 BOM 属于文本而非二进制
        if (sample.size >= 2 && ((sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) || (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()))) {
            return false
        }
        val checkLen = minOf(sample.size, 8192)
        var nonText = 0
        for (i in 0 until checkLen) {
            val b = sample[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F && b != 0x1B)) nonText++
        }
        return nonText > checkLen * 0.15
    }

    private fun isValidUtf8Slice(bytes: ByteArray, rawStart: Int, rawLen: Int): Boolean {
        if (rawLen <= 0 || rawStart >= bytes.size) return true
        var start = rawStart.coerceIn(0, bytes.size)
        val end = (rawStart + rawLen).coerceAtMost(bytes.size)
        // 若切片起点落在文件中间，跳过最多 3 个 UTF-8 续字节（10xxxxxx），避免因切片切在多字节字符中间导致误判
        if (start > 0) {
            var skipped = 0
            while (start < end && skipped < 3 && (bytes[start].toInt() and 0xC0) == 0x80) {
                start++
                skipped++
            }
        }
        // 若切片终点未到文件末尾，裁剪末尾可能被截断的 UTF-8 引导字节序列
        var trimEnd = end
        if (trimEnd < bytes.size && trimEnd > start) {
            var back = 0
            while (trimEnd > start && back < 4 && (bytes[trimEnd - 1].toInt() and 0x80) != 0) {
                val lead = bytes[trimEnd - 1].toInt() and 0xFF
                trimEnd--
                back++
                if ((lead and 0xC0) != 0x80) break
            }
        }
        if (trimEnd <= start) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes, start, trimEnd - start)); true }.getOrDefault(false)
    }

    /** 三段抽样（头 64KB + 中 32KB + 尾 32KB）严格校验 UTF-8，彻底消除前英文后 GBK 乱码误判。 */
    fun detectEncoding(bytes: ByteArray): TextEncoding {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return TextEncoding.UTF_8_BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return TextEncoding.UTF_16_LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return TextEncoding.UTF_16_BE
        val headOk = isValidUtf8Slice(bytes, 0, minOf(bytes.size, 65_536))
        if (!headOk) return TextEncoding.GB18030
        if (bytes.size > 65_536) {
            val midStart = (bytes.size / 2 - 16_384).coerceAtLeast(65_536)
            if (!isValidUtf8Slice(bytes, midStart, 32_768)) return TextEncoding.GB18030
            val tailStart = (bytes.size - 32_768).coerceAtLeast(0)
            if (!isValidUtf8Slice(bytes, tailStart, 32_768)) return TextEncoding.GB18030
        }
        return TextEncoding.UTF_8
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65_536)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun decodeBytes(
        bytes: ByteArray,
        encodingOverride: TextEncoding? = null,
        totalBytes: Long = bytes.size.toLong(),
        truncated: Boolean = false,
        revision: FileRevision? = null,
    ): DecodedTextDocument {
        val enc = encodingOverride ?: detectEncoding(bytes)
        val payload = when {
            enc == TextEncoding.UTF_8_BOM && bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                bytes.copyOfRange(3, bytes.size)
            (enc == TextEncoding.UTF_16_LE || enc == TextEncoding.UTF_16_BE) && bytes.size >= 2 &&
                ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) ->
                bytes.copyOfRange(2, bytes.size)
            else -> bytes
        }
        val rawText = String(payload, enc.charset())
        var crlfCount = 0
        var lfOnlyCount = 0
        var crOnlyCount = 0
        var i = 0
        while (i < rawText.length) {
            val ch = rawText[i]
            if (ch == '\r') {
                if (i + 1 < rawText.length && rawText[i + 1] == '\n') {
                    crlfCount++
                    i += 2
                    continue
                } else {
                    crOnlyCount++
                }
            } else if (ch == '\n') {
                lfOnlyCount++
            }
            i++
        }
        val lineEnding = if (crlfCount > lfOnlyCount && crlfCount > 0) LineEnding.CRLF else LineEnding.LF
        val mixed = (crlfCount > 0 && lfOnlyCount > 0) || crOnlyCount > 0
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val hasTrailing = normalized.endsWith("\n")
        return DecodedTextDocument(
            text = normalized,
            encoding = enc,
            lineEnding = lineEnding,
            totalBytes = totalBytes,
            truncated = truncated,
            hasTrailingNewline = hasTrailing,
            mixedLineEndings = mixed,
            revision = revision,
        )
    }

    fun encodeText(
        text: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
        preserveTrailingNewline: Boolean? = null,
        includeBom: Boolean = true,
    ): ByteArray {
        var normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (preserveTrailingNewline == true && normalized.isNotEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n"
        }
        val finalText = if (lineEnding == LineEnding.CRLF) normalized.replace("\n", "\r\n") else normalized
        val body = finalText.toByteArray(encoding.charset())
        if (!includeBom) return body
        val bom = when (encoding) {
            TextEncoding.UTF_8_BOM -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            TextEncoding.UTF_16_LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            TextEncoding.UTF_16_BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> return body
        }
        return bom + body
    }

    /** 流式构建超大文件稀疏行号索引（每 256KB 一个锚点，支持毫秒级真实行号查询与跳行）。 */
    private fun isUtf16(encoding: TextEncoding) = encoding == TextEncoding.UTF_16_LE || encoding == TextEncoding.UTF_16_BE

    private fun endsLineFeed(byte: Byte, previous: Byte, offset: Long, encoding: TextEncoding): Boolean = when (encoding) {
        TextEncoding.UTF_16_LE -> offset % 2L == 1L && previous == 10.toByte() && byte == 0.toByte()
        TextEncoding.UTF_16_BE -> offset % 2L == 1L && previous == 0.toByte() && byte == 10.toByte()
        else -> byte == 10.toByte()
    }

    fun buildSparseLineIndex(file: File, strideBytes: Int = 256 * 1024, encoding: TextEncoding = TextEncoding.UTF_8): SparseLineIndex {
        require(strideBytes > 0 && (!isUtf16(encoding) || strideBytes % 2 == 0)) { "行索引步长必须与编码对齐" }
        val total = file.length().coerceAtLeast(0L)
        val anchorCount = ((total / strideBytes).toInt() + 1).coerceAtLeast(1)
        val anchors = IntArray(anchorCount)
        anchors[0] = 1
        var line = 1
        var offset = 0L
        var nextAnchorIdx = 1
        val buf = ByteArray(65_536)
        var previous: Byte = 0
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val pos = offset + i
                    while (nextAnchorIdx < anchorCount && pos >= nextAnchorIdx.toLong() * strideBytes) {
                        anchors[nextAnchorIdx] = line
                        nextAnchorIdx++
                    }
                    if (endsLineFeed(buf[i], previous, pos, encoding)) line++
                    previous = buf[i]
                }
                offset += n
            }
        }
        while (nextAnchorIdx < anchorCount) {
            anchors[nextAnchorIdx] = line
            nextAnchorIdx++
        }
        return SparseLineIndex(total, strideBytes, anchors, line, encoding)
    }

    /** 根据稀疏行索引计算精确的 1-based 行号。 */
    fun lineNumberAtOffset(file: File, byteOffset: Long, index: SparseLineIndex?, encoding: TextEncoding = index?.encoding ?: TextEncoding.UTF_8): Int {
        if (byteOffset <= 0L) return 1
        val clamped = byteOffset.coerceAtMost(file.length().coerceAtLeast(0L))
        var startPos = 0L
        var line = 1
        if (index != null && index.lineAtAnchor.isNotEmpty() && index.strideBytes > 0) {
            val anchorIdx = (clamped / index.strideBytes).toInt().coerceIn(0, index.lineAtAnchor.lastIndex)
            startPos = anchorIdx.toLong() * index.strideBytes
            line = index.lineAtAnchor[anchorIdx]
        }
        val toRead = clamped - startPos
        if (toRead <= 0L) return line
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startPos)
            val buf = ByteArray(minOf(65_536L, toRead).toInt())
            var remain = toRead
            var previous: Byte = 0
            while (remain > 0L) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                if (n <= 0) break
                for (i in 0 until n) {
                    if (endsLineFeed(buf[i], previous, clamped - remain + i, encoding)) line++
                    previous = buf[i]
                }
                remain -= n
            }
        }
        return line
    }

    /** 根据 1-based 行号快速定位字节偏移与页号（用于超大文件跳行）。 */
    fun byteOffsetForLine(file: File, targetLine: Int, index: SparseLineIndex?): Long {
        if (targetLine <= 1) return 0L
        var startPos = 0L
        var curLine = 1
        val encoding = index?.encoding ?: file.inputStream().use { input ->
            val head = ByteArray(4)
            val n = input.read(head)
            detectEncoding(head.copyOf(n.coerceAtLeast(0)))
        }
        if (index != null && index.lineAtAnchor.isNotEmpty()) {
            var lo = 0
            var hi = index.lineAtAnchor.lastIndex
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (index.lineAtAnchor[mid] <= targetLine) lo = mid else hi = mid - 1
            }
            startPos = lo.toLong() * index.strideBytes
            curLine = index.lineAtAnchor[lo]
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startPos)
            val buf = ByteArray(65_536)
            var pos = startPos
            var previous: Byte = 0
            while (curLine < targetLine) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (endsLineFeed(buf[i], previous, pos + i, encoding)) {
                        curLine++
                        if (curLine == targetLine) return pos + i + 1
                    }
                    previous = buf[i]
                }
                pos += n
            }
            return pos
        }
    }

    /**
     * 按页读取文本；相邻页共享同一边界算法，UTF-16 按码元和代理对对齐。
     */
    fun readTextPage(
        file: File,
        pageIndex: Int,
        pageBytes: Int = 64 * 1024,
        encoding: TextEncoding = TextEncoding.UTF_8,
        lineIndex: SparseLineIndex? = null,
    ): TextPageChunk {
        val total = file.length().coerceAtLeast(0L)
        val totalPages = ((total + pageBytes - 1) / pageBytes).toInt().coerceAtLeast(1)
        val safePage = pageIndex.coerceIn(0, totalPages - 1)
        if (total == 0L) {
            return TextPageChunk("", 0L, 0L, 0L, 0, 1, startLineNumber = 1, lineCount = 1)
        }
        val rawStart = safePage.toLong() * pageBytes
        val rawEnd = minOf(total, rawStart + pageBytes)
        val maxLookAhead = 4096

        var alignedStart = rawStart
        var alignedEnd = rawEnd
        RandomAccessFile(file, "r").use { raf ->
            fun boundary(raw: Long): Long {
                if (raw <= 0L || raw >= total) return raw.coerceIn(0L, total)
                val start = if (isUtf16(encoding)) raw - raw % 2L else raw
                raf.seek(start)
                val probe = ByteArray(minOf(maxLookAhead.toLong(), total - start).toInt())
                val got = readFully(raf, probe, probe.size)
                var previous: Byte = 0
                for (i in 0 until got) {
                    if (endsLineFeed(probe[i], previous, start + i, encoding)) return start + i + 1
                    previous = probe[i]
                }
                var end = start + got
                if (end == total) return end
                if (isUtf16(encoding)) {
                    end -= end % 2L
                    if (end >= 2L) {
                        raf.seek(end - 2)
                        val a = raf.readUnsignedByte()
                        val b = raf.readUnsignedByte()
                        val unit = if (encoding == TextEncoding.UTF_16_LE) a or (b shl 8) else (a shl 8) or b
                        if (unit in 0xD800..0xDBFF) end -= 2
                    }
                } else if (encoding == TextEncoding.UTF_8 || encoding == TextEncoding.UTF_8_BOM) {
                    raf.seek(end)
                    var next = raf.read()
                    while (end > start && (next and 0xC0) == 0x80) {
                        end--
                        raf.seek(end)
                        next = raf.read()
                    }
                }
                return end
            }
            alignedStart = boundary(rawStart)
            alignedEnd = boundary(rawEnd)
            if (alignedStart > alignedEnd) alignedStart = alignedEnd
            val readLen = (alignedEnd - alignedStart).toInt().coerceAtLeast(0)
            val buf = ByteArray(readLen)
            if (readLen > 0) {
                raf.seek(alignedStart)
                readFully(raf, buf, readLen)
            }
            val bomBytes = if (alignedStart == 0L && encoding == TextEncoding.UTF_8_BOM &&
                buf.size >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()
            ) 3 else if (alignedStart == 0L && isUtf16(encoding) && buf.size >= 2 &&
                ((buf[0] == 0xFF.toByte() && buf[1] == 0xFE.toByte()) || (buf[0] == 0xFE.toByte() && buf[1] == 0xFF.toByte()))
            ) 2 else 0
            val payload = if (bomBytes > 0) buf.copyOfRange(bomBytes, buf.size) else buf
            val text = encoding.charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString().replace("\r\n", "\n")
            val startLine = lineNumberAtOffset(file, alignedStart, lineIndex, encoding)
            val linesInChunk = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1
            return TextPageChunk(
                content = text,
                startOffset = alignedStart,
                endOffset = alignedEnd,
                totalBytes = total,
                pageIndex = safePage,
                totalPages = totalPages,
                startLineNumber = startLine,
                lineCount = linesInChunk.coerceAtLeast(1),
            )
        }
    }

    /** 流式搜索超大文本文件（零 OOM，返回行号、字节偏移与对应分页索引）。 */
    fun searchLargeFile(
        file: File,
        query: String,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
        encoding: TextEncoding = TextEncoding.UTF_8,
        pageBytes: Int = 64 * 1024,
        maxHits: Int = 120,
    ): List<LargeSearchHit> {
        if (query.isEmpty() || !file.isFile) return emptyList()
        val regex = if (useRegex) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(query, opts) }.getOrNull() ?: return emptyList()
        } else null
        val hits = mutableListOf<LargeSearchHit>()
        val charset = encoding.charset()
        var lineNo = 1
        var byteOffset = 0L
        val lineBuf = java.io.ByteArrayOutputStream(256)
        val chunk = ByteArray(65_536)
        file.inputStream().use { input ->
            while (hits.size < maxHits) {
                val n = input.read(chunk)
                if (n <= 0) break
                var segStart = 0
                for (i in 0 until n) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        lineBuf.write(chunk, segStart, i - segStart)
                        val lineBytesLen = lineBuf.size() + 1L
                        val text = String(lineBuf.toByteArray(), charset).trimEnd('\r')
                        val matched = if (regex != null) regex.containsMatchIn(text) else text.contains(query, ignoreCase = !caseSensitive)
                        if (matched) {
                            hits += LargeSearchHit(
                                lineNumber = lineNo,
                                byteOffset = byteOffset,
                                pageIndex = (byteOffset / pageBytes).toInt(),
                                previewLine = text.trim().take(160),
                            )
                            if (hits.size >= maxHits) break
                        }
                        byteOffset += lineBytesLen
                        lineNo++
                        lineBuf.reset()
                        segStart = i + 1
                    }
                }
                if (segStart < n && hits.size < maxHits) {
                    if (lineBuf.size() < 256 * 1024) {
                        lineBuf.write(chunk, segStart, n - segStart)
                    } else {
                        byteOffset += (n - segStart)
                    }
                }
            }
        }
        if (hits.size < maxHits && lineBuf.size() > 0) {
            val text = String(lineBuf.toByteArray(), charset).trimEnd('\r')
            val matched = if (regex != null) regex.containsMatchIn(text) else text.contains(query, ignoreCase = !caseSensitive)
            if (matched) {
                hits += LargeSearchHit(
                    lineNumber = lineNo,
                    byteOffset = byteOffset,
                    pageIndex = (byteOffset / pageBytes).toInt(),
                    previewLine = text.trim().take(160),
                )
            }
        }
        return hits
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = raf.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    /** 读取指定偏移的十六进制页（默认 4KB/页 = 256 行）。 */
    fun readHexPage(file: File, offset: Long, pageBytes: Int = 4096): Pair<ByteArray, Long> {
        val total = file.length().coerceAtLeast(0L)
        val safeOff = offset.coerceIn(0L, (total - 1L).coerceAtLeast(0L))
        val len = minOf(pageBytes.toLong(), (total - safeOff).coerceAtLeast(0L)).toInt()
        val buf = ByteArray(len)
        if (len > 0) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(safeOff)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n <= 0) break
                    read += n
                }
            }
        }
        return buf to total
    }

    /** 一次流式扫描同时计算 MD5 / SHA-1 / SHA-256 / SHA-512。 */
    fun computeHashes(file: File): FileHashes {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha512 = MessageDigest.getInstance("SHA-512")
        val buf = ByteArray(65_536)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                sha512.update(buf, 0, n)
            }
        }
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
        return FileHashes(
            md5 = md5.digest().hex(),
            sha1 = sha1.digest().hex(),
            sha256 = sha256.digest().hex(),
            sha512 = sha512.digest().hex(),
        )
    }

    private fun startsWithAscii(bytes: ByteArray, prefix: String): Boolean {
        if (bytes.size < prefix.length) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i].code.toByte()) return false
        }
        return true
    }
}
