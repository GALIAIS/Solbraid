package com.galiais.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.galiais.agent.engine.fs.EditorDraftStore
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileRevision
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.LineEnding
import com.galiais.agent.engine.fs.TextCodec
import com.galiais.agent.engine.fs.TextEncoding
import com.galiais.agent.engine.fs.splitValidatedRelativePath
import com.galiais.agent.ui.firstChangedNewLine
import com.galiais.agent.ui.files.applySmartEnterIndent
import com.galiais.agent.ui.files.applySymbolOrWrap
import com.galiais.agent.ui.files.computeGutterDiffMarkers
import com.galiais.agent.ui.files.formatXmlBasic
import com.galiais.agent.ui.files.trimTrailingWhitespace
import com.galiais.agent.ui.isLargeOverwriteDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

class FileTransactionAndEditorTest {

    @Test
    fun splitValidatedRelativePath_allowsNestedAndBlocksTraversal() {
        assertEquals(listOf("src", "utils", "cache.kt"), splitValidatedRelativePath("src/utils/cache.kt"))
        assertEquals(listOf("notes.md"), splitValidatedRelativePath(" /notes.md "))
        assertTrue(runCatching { splitValidatedRelativePath("../escape.txt") }.isFailure)
        assertTrue(runCatching { splitValidatedRelativePath("a/../b.txt") }.isFailure)
        assertTrue(runCatching { splitValidatedRelativePath("a//b.txt") }.isFailure)
        assertTrue(runCatching { splitValidatedRelativePath("bad\u0000name.txt") }.isFailure)
    }

    @Test
    fun fileRevision_detectsSameSecondEqualLengthChangesViaSha256() {
        val revA = FileRevision(1700000000000L, 4L, TextCodec.sha256Hex("true".toByteArray()))
        val revB = FileRevision(1700000000000L, 4L, TextCodec.sha256Hex("1234".toByteArray()))
        val decoded = FileRevision.decode(revA.encode())
        assertEquals(revA, decoded)
        assertTrue(revA.contentDiffersFrom(revB))
        assertFalse(revA.contentDiffersFrom(revA.copy()))
    }

    @Test
    fun detectEncoding_threePointSamplingCatchesGbkAfter64KbAsciiPrefix() {
        val asciiPrefix = "a".repeat(70_000).toByteArray(Charsets.UTF_8)
        val gbkTail = "中文GBK配置项\n".toByteArray(Charset.forName("GB18030"))
        val combined = asciiPrefix + gbkTail
        assertEquals(TextEncoding.GB18030, TextCodec.detectEncoding(combined))

        val validUtf8 = (asciiPrefix + "中文UTF8配置项\n".toByteArray(Charsets.UTF_8))
        assertEquals(TextEncoding.UTF_8, TextCodec.detectEncoding(validUtf8))
    }

    @Test
    fun decodeBytes_tracksTrailingNewlineAndMixedLineEndings() {
        val mixed = "a\r\nb\nc\n".toByteArray(Charsets.UTF_8)
        val docMixed = TextCodec.decodeBytes(mixed)
        assertTrue(docMixed.mixedLineEndings)
        assertTrue(docMixed.hasTrailingNewline)
        assertEquals("a\nb\nc\n", docMixed.text)

        val cleanNoTrail = "line1\nline2".toByteArray(Charsets.UTF_8)
        val docClean = TextCodec.decodeBytes(cleanNoTrail)
        assertFalse(docClean.mixedLineEndings)
        assertFalse(docClean.hasTrailingNewline)
    }

    @Test
    fun readTextPage_alignsUtf8MultibyteAndLinesWithExactLineNumbersAndSearch() {
        val tmp = Files.createTempFile("solbraid-large-utf8-", ".txt").toFile()
        try {
            val sb = StringBuilder()
            for (i in 1..4000) {
                sb.append("第").append(i).append("行：中文多字节边界对齐验证内容_TOKEN_").append(i).append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)

            val idx = TextCodec.buildSparseLineIndex(tmp, strideBytes = 16 * 1024)
            assertEquals(4001, idx.totalLines)

            val p0 = TextCodec.readTextPage(tmp, 0, pageBytes = 16 * 1024, encoding = TextEncoding.UTF_8, lineIndex = idx)
            val p1 = TextCodec.readTextPage(tmp, 1, pageBytes = 16 * 1024, encoding = TextEncoding.UTF_8, lineIndex = idx)
            val p2 = TextCodec.readTextPage(tmp, 2, pageBytes = 16 * 1024, encoding = TextEncoding.UTF_8, lineIndex = idx)

            // 严禁出现 UTF-8 截断乱码符号 U+FFFD
            assertFalse(p0.content.contains('\uFFFD'))
            assertFalse(p1.content.contains('\uFFFD'))
            assertFalse(p2.content.contains('\uFFFD'))
            // 每页起点与终点必须紧邻无缝拼接且对齐完整行
            assertEquals(1, p0.startLineNumber)
            assertEquals(p0.endOffset, p1.startOffset)
            assertEquals(p1.endOffset, p2.startOffset)
            assertTrue(p1.content.startsWith("第${p1.startLineNumber}行："))
            assertTrue(p2.content.startsWith("第${p2.startLineNumber}行："))

            // 跳行偏移精确定位
            val off2500 = TextCodec.byteOffsetForLine(tmp, 2500, idx)
            assertEquals(2500, TextCodec.lineNumberAtOffset(tmp, off2500, idx))

            // 流式搜索精准命中
            val hits = TextCodec.searchLargeFile(tmp, "TOKEN_3210", encoding = TextEncoding.UTF_8, pageBytes = 16 * 1024)
            assertEquals(1, hits.size)
            assertEquals(3210, hits.first().lineNumber)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun editorDraftStore_saveLoadClearAndPruneLru() {
        val dir = Files.createTempDirectory("solbraid-drafts-").toFile()
        try {
            val ref1 = FileRef(FileScope.LINUX_WORKSPACE, "/home/agent/workspace/a.kt")
            val ref2 = FileRef(FileScope.LINUX_WORKSPACE, "/home/agent/workspace/b.kt")
            assertTrue(EditorDraftStore.saveDraftToDir(dir, ref1, "rev1", "hello draft 1", TextEncoding.UTF_8, LineEnding.LF))
            val loaded = EditorDraftStore.loadDraftFromDir(dir, ref1)
            assertNotNull(loaded)
            assertEquals("hello draft 1", loaded!!.draftText)

            Thread.sleep(15)
            assertTrue(EditorDraftStore.saveDraftToDir(dir, ref2, "rev2", "hello draft 2", TextEncoding.UTF_8, LineEnding.LF))
            // 将总配额设为极小值验证 LRU 优先淘汰最旧的 ref1 而保留 ref2
            EditorDraftStore.pruneDir(dir, keepFile = EditorDraftStore.draftFile(dir, ref2), maxTotalBytes = 1L)
            assertNull(EditorDraftStore.loadDraftFromDir(dir, ref1))
            assertNotNull(EditorDraftStore.loadDraftFromDir(dir, ref2))

            EditorDraftStore.clearDraftFromDir(dir, ref2)
            assertNull(EditorDraftStore.loadDraftFromDir(dir, ref2))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editorHelpers_gutterDiffAutoIndentWrapAndCleanup() {
        val markers = computeGutterDiffMarkers(
            savedText = "line1\nline2\nline3\nline4",
            currentText = "line1\nline2_mod\nline3\nline3_added\nline4",
        )
        assertEquals('~', markers[2])
        assertEquals('+', markers[4])

        // 回车延续缩进 + '{' 自动多缩进一层
        val prev = TextFieldValue("    if (ok) {", TextRange(13))
        val typedEnter = TextFieldValue("    if (ok) {\n", TextRange(14))
        val indented = applySmartEnterIndent(prev, typedEnter, tabSize = 4)
        assertEquals("    if (ok) {\n        ", indented.text)
        assertEquals(TextRange(22), indented.selection)

        // 选中文本点击 '(' 自动包裹
        val wrapped = applySymbolOrWrap(TextFieldValue("hello world", TextRange(6, 11)), "(")
        assertEquals("hello (world)", wrapped.text)
        assertEquals(TextRange(7, 12), wrapped.selection)

        // 清理行尾空格保留末行换行
        assertEquals("a\nb\n", trimTrailingWhitespace("a   \nb\t \n"))
        assertEquals("<root>\n  <item>1</item>\n</root>", formatXmlBasic("<root><item>1</item></root>", 2))
    }

    @Test
    fun diffViewHelpers_extractFirstChangedLineAndFlagLargeOverwrite() {
        val diff = """
            --- a/app.kt
            +++ b/app.kt
            @@ -40,4 +42,5 @@
             context1
             context2
            +addedAt44
             context3
        """.trimIndent()
        assertEquals(44, firstChangedNewLine(diff))
        assertTrue(isLargeOverwriteDiff(added = 250, removed = 240, kind = "update"))
        assertFalse(isLargeOverwriteDiff(added = 500, removed = 0, kind = "add"))
        assertFalse(isLargeOverwriteDiff(added = 12, removed = 8, kind = "update"))
    }

    @Test
    fun describeToolCall_rendersSolbraidFileToolsAsNativeNotMcp() {
        val readMeta = com.galiais.agent.ui.describeToolCall("mcp__solbraid_plugin_ctl__solbraid_file_read", """{"path":"/home/agent/workspace/app.kt"}""")
        assertEquals(com.galiais.agent.ui.ToolCallKind.READ_FILE, readMeta.kind)
        assertEquals("读取文件", readMeta.badge)
        assertEquals("app.kt", readMeta.summary)

        val editMeta = com.galiais.agent.ui.describeToolCall("mcp__solbraid_plugin_ctl__solbraid_file_edit", """{"path":"src/Main.kt"}""")
        assertEquals(com.galiais.agent.ui.ToolCallKind.EDIT_FILE, editMeta.kind)
        assertEquals("编辑文件", editMeta.badge)

        val createMeta = com.galiais.agent.ui.describeToolCall("solbraid_file_create", """{"path":"scripts/run.sh"}""")
        assertEquals(com.galiais.agent.ui.ToolCallKind.EDIT_FILE, createMeta.kind)
        assertEquals("创建文件", createMeta.badge)

        val searchMeta = com.galiais.agent.ui.describeToolCall("mcp__solbraid_plugin_ctl__solbraid_file_search", """{"path":"huge.log","query":"ERROR"}""")
        assertEquals(com.galiais.agent.ui.ToolCallKind.SEARCH_CODE, searchMeta.kind)
        assertEquals("搜索文件", searchMeta.badge)

        val revertMeta = com.galiais.agent.ui.describeToolCall("mcp__solbraid_plugin_ctl__solbraid_file_revert", """{"path":"src/Main.kt"}""")
        assertEquals(com.galiais.agent.ui.ToolCallKind.EDIT_FILE, revertMeta.kind)
        assertEquals("撤回文件", revertMeta.badge)
    }
}
