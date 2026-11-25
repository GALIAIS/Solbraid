package com.galiais.agent.engine.fs

import com.galiais.agent.ui.files.computeLineDiffLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileManagerUnitTest {

    @Test
    fun vfsPathNormalizationAndJoin() {
        assertEquals("/", normalizeVfsPath(""))
        assertEquals("/", normalizeVfsPath("///"))
        assertEquals("/home/agent", normalizeVfsPath("/home/agent/"))
        assertEquals("/home/work", normalizeVfsPath("/home/agent/../work/./"))
        assertEquals("/", normalizeVfsPath("/../../.."))
        assertEquals("saf://abc123/doc", normalizeVfsPath("saf://abc123/doc"))
        assertEquals("/home/agent/a.txt", joinVfsPath("/home/agent", "a.txt"))
        assertEquals("/a.txt", joinVfsPath("/", "/a.txt"))
    }

    @Test
    fun textCodecRoundtripEncodingsAndLineEndings() {
        val sample = "第一行 Hello\n第二行 World\n"

        // UTF-8 BOM + CRLF
        val bomCrlf = TextCodec.encodeText(sample, TextEncoding.UTF_8_BOM, LineEnding.CRLF)
        assertEquals(0xEF.toByte(), bomCrlf[0])
        assertEquals(0xBB.toByte(), bomCrlf[1])
        assertEquals(0xBF.toByte(), bomCrlf[2])
        val decodedBom = TextCodec.decodeBytes(bomCrlf)
        assertEquals(TextEncoding.UTF_8_BOM, decodedBom.encoding)
        assertEquals(LineEnding.CRLF, decodedBom.lineEnding)
        assertEquals(sample, decodedBom.text)

        // UTF-16 LE BOM
        val u16 = TextCodec.encodeText("你好 Solbraid\nLine2", TextEncoding.UTF_16_LE, LineEnding.LF)
        val decodedU16 = TextCodec.decodeBytes(u16)
        assertEquals(TextEncoding.UTF_16_LE, decodedU16.encoding)
        assertEquals("你好 Solbraid\nLine2", decodedU16.text)

        // GB18030 / GBK auto-detect (non-UTF-8 CJK bytes)
        val gbBytes = "中文配置文件测试=成功\r\n第二行".toByteArray(Charset.forName("GB18030"))
        val decodedGb = TextCodec.decodeBytes(gbBytes)
        assertEquals(TextEncoding.GB18030, decodedGb.encoding)
        assertEquals(LineEnding.CRLF, decodedGb.lineEnding)
        assertEquals("中文配置文件测试=成功\n第二行", decodedGb.text)
    }

    @Test
    fun textCodecMagicClassificationAndHexDump() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pdfHeader = "%PDF-1.7\n".toByteArray()
        val sqliteHeader = "SQLite format 3\u0000".toByteArray()
        val elfHeader = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)

        assertEquals(ViewerCategory.IMAGE, TextCodec.classifyFile("unknown.bin", pngHeader))
        assertEquals(ViewerCategory.PDF, TextCodec.classifyFile("doc.dat", pdfHeader))
        assertEquals(ViewerCategory.SQLITE, TextCodec.classifyFile("data.bin", sqliteHeader))
        assertEquals(ViewerCategory.BINARY_HEX, TextCodec.classifyFile("tool", elfHeader))
        assertEquals(ViewerCategory.TEXT_CODE, TextCodec.classifyFile("Main.kt", "fun main() {}".toByteArray()))
        assertTrue(TextCodec.detectMagicDescription(elfHeader).contains("ELF"))

        val tmp = File.createTempFile("solbraid-codec-", ".bin")
        try {
            tmp.writeBytes("Hello Solbraid!".toByteArray())
            val hashes = TextCodec.computeHashes(tmp)
            assertEquals(32, hashes.md5.length)
            assertEquals(40, hashes.sha1.length)
            assertEquals(64, hashes.sha256.length)
            assertEquals(128, hashes.sha512.length)

            val (hexBytes, total) = TextCodec.readHexPage(tmp, offset = 0L, pageBytes = 32)
            assertEquals(15L, total)
            assertEquals("Hello Solbraid!", String(hexBytes, Charsets.UTF_8))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun archiveEngineRoundtripAndZipSlipGuard() {
        val srcDir = java.nio.file.Files.createTempDirectory("solbraid-zip-src-").toFile()
        val outDir = java.nio.file.Files.createTempDirectory("solbraid-zip-out-").toFile()
        val zipFile = File.createTempFile("solbraid-archive-", ".zip")
        try {
            File(srcDir, "hello.txt").writeText("Solbraid File Manager")
            File(srcDir, "sub").mkdirs()
            File(srcDir, "sub/data.json").writeText("""{"ok":true}""")

            val packed = zipFile.outputStream().use { out ->
                ArchiveEngine.createZipStream(
                    sources = listOf("hello.txt" to File(srcDir, "hello.txt"), "sub" to File(srcDir, "sub")),
                    output = out,
                    excludeBuildDirs = false,
                )
            }
            assertEquals(2, packed)

            val listed = ArchiveEngine.listZipEntries(zipFile)
            assertTrue(listed.any { it.path == "hello.txt" })
            assertTrue(listed.any { it.path == "sub/data.json" })

            val entryBytes = ArchiveEngine.readZipEntryBytes(zipFile, "sub/data.json")
            assertEquals("""{"ok":true}""", entryBytes.toString(Charsets.UTF_8))

            val extracted = zipFile.inputStream().use { ArchiveEngine.extractZipStream(it, outDir) }
            assertEquals(2, extracted)
            assertEquals("Solbraid File Manager", File(outDir, "hello.txt").readText())
            assertEquals("""{"ok":true}""", File(outDir, "sub/data.json").readText())

            // Zip-Slip path traversal must be rejected strictly
            val evilZip = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zos ->
                    zos.putNextEntry(ZipEntry("../../evil.txt"))
                    zos.write("pwned".toByteArray())
                    zos.closeEntry()
                }
            }.toByteArray()
            val evilOut = java.nio.file.Files.createTempDirectory("solbraid-evil-out-").toFile()
            try {
                val blocked = runCatching { ArchiveEngine.extractZipStream(ByteArrayInputStream(evilZip), evilOut) }.isFailure
                assertTrue(blocked)
                assertFalse(File(evilOut.parentFile, "evil.txt").exists())
            } finally {
                evilOut.deleteRecursively()
            }
        } finally {
            srcDir.deleteRecursively()
            outDir.deleteRecursively()
            zipFile.delete()
        }
    }

    @Test
    fun lineDiffHighlightsChanges() {
        val diff = computeLineDiffLines("a\nb\nc", "a\nb2\nc\nd")
        assertTrue(diff.any { it.first == '-' && it.second == "b" })
        assertTrue(diff.any { it.first == '+' && it.second == "b2" })
        assertTrue(diff.any { it.first == '+' && it.second == "d" })
    }
}
