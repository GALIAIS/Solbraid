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
)

data class TextPageChunk(
    val content: String,
    val startOffset: Long,
    val endOffset: Long,
    val totalBytes: Long,
    val pageIndex: Int,
    val totalPages: Int,
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

    fun detectEncoding(bytes: ByteArray): TextEncoding {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return TextEncoding.UTF_8_BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return TextEncoding.UTF_16_LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return TextEncoding.UTF_16_BE
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val okUtf8 = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, minOf(bytes.size, 65_536))); true }.getOrDefault(false)
        return if (okUtf8) TextEncoding.UTF_8 else TextEncoding.GB18030
    }

    fun decodeBytes(bytes: ByteArray, encodingOverride: TextEncoding? = null, totalBytes: Long = bytes.size.toLong(), truncated: Boolean = false): DecodedTextDocument {
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
        val crlfCount = rawText.windowed(2).count { it == "\r\n" }
        val lfCount = rawText.count { it == '\n' } - crlfCount
        val lineEnding = if (crlfCount > lfCount && crlfCount > 0) LineEnding.CRLF else LineEnding.LF
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        return DecodedTextDocument(normalized, enc, lineEnding, totalBytes, truncated)
    }

    fun encodeText(text: String, encoding: TextEncoding, lineEnding: LineEnding): ByteArray {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val finalText = if (lineEnding == LineEnding.CRLF) normalized.replace("\n", "\r\n") else normalized
        val body = finalText.toByteArray(encoding.charset())
        val bom = when (encoding) {
            TextEncoding.UTF_8_BOM -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            TextEncoding.UTF_16_LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            TextEncoding.UTF_16_BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> return body
        }
        return bom + body
    }

    /** 按页读取大文本文件（64KB/页，自动对齐行边界，零 OOM）。 */
    fun readTextPage(file: File, pageIndex: Int, pageBytes: Int = 64 * 1024, encoding: TextEncoding = TextEncoding.UTF_8): TextPageChunk {
        val total = file.length().coerceAtLeast(0L)
        val totalPages = ((total + pageBytes - 1) / pageBytes).toInt().coerceAtLeast(1)
        val safePage = pageIndex.coerceIn(0, totalPages - 1)
        val start = safePage.toLong() * pageBytes
        val len = minOf(pageBytes.toLong(), (total - start).coerceAtLeast(0L)).toInt()
        val buf = ByteArray(len)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            var off = 0
            while (off < len) {
                val n = raf.read(buf, off, len - off)
                if (n <= 0) break
                off += n
            }
        }
        val text = String(buf, encoding.charset()).replace("\r\n", "\n")
        return TextPageChunk(text, start, start + len, total, safePage, totalPages)
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
