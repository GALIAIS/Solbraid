package com.galiais.agent

import com.galiais.agent.engine.terminal.PsReadLinePredictor
import com.galiais.agent.engine.terminal.SolbraidTerminalEngine
import com.galiais.agent.engine.terminal.TerminalAnsiUtils
import com.galiais.agent.engine.terminal.TerminalBoxDrawing
import com.galiais.agent.engine.terminal.TerminalCursorStyle
import com.galiais.agent.engine.terminal.TerminalFuzzyMatcher
import com.galiais.agent.engine.terminal.TerminalKey
import com.galiais.agent.engine.terminal.TerminalLinkKind
import com.galiais.agent.engine.terminal.TerminalMediaRequest
import com.galiais.agent.engine.terminal.TerminalProgressState
import com.galiais.agent.engine.terminal.TerminalSafeGuard
import com.galiais.agent.engine.terminal.TerminalSearchOptions
import com.galiais.agent.engine.terminal.TerminalSecretRedactor
import com.galiais.agent.engine.terminal.TerminalStyle
import com.galiais.agent.engine.terminal.TerminalThemes
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolbraidTerminalEngineTest {

    @Test
    fun parsesTrueColorColonSgrAndCurlyUnderline() {
        val engine = SolbraidTerminalEngine(40, 10, onSendInput = {})
        engine.writeText("\u001b[1;3;4:3;38;2;255;128;64;48:2::10:20:30mHi\u001b[0m!")
        val snap = engine.captureViewport(0)
        val row = snap.visibleRows[0]
        assertEquals('H'.code, row.chars[0])
        assertEquals(TerminalStyle.trueColor(255, 128, 64), row.fg[0])
        assertEquals(TerminalStyle.trueColor(10, 20, 30), row.bg[0])
        assertTrue((row.flags[0] and TerminalStyle.FLAG_BOLD) != 0)
        assertTrue((row.flags[0] and TerminalStyle.FLAG_ITALIC) != 0)
        assertTrue((row.flags[0] and TerminalStyle.FLAG_CURLY_UNDERLINE) != 0)
        assertEquals('!'.code, row.chars[2])
        assertEquals(TerminalStyle.COLOR_DEFAULT, row.fg[2])
        assertEquals(0, row.flags[2])
    }

    @Test
    fun handlesSplitUtf8WideCjkAndSoftWrapExtraction() {
        val engine = SolbraidTerminalEngine(10, 5, onSendInput = {})
        val bytes = "你好世界1234".toByteArray(Charsets.UTF_8)
        // 故意按单字节拆包喂入，验证跨 TCP 包流式 UTF-8 解码与宽字符对齐
        for (b in bytes) engine.writeOutput(byteArrayOf(b))
        val snap = engine.captureViewport(0)
        assertEquals('你'.code, snap.visibleRows[0].chars[0])
        assertTrue((snap.visibleRows[0].flags[0] and TerminalStyle.FLAG_WIDE_LEAD) != 0)
        assertTrue((snap.visibleRows[0].flags[1] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0)
        assertTrue(snap.visibleRows[0].softWrapped)
        val extracted = engine.extractText(0, 0, 1, 9)
        assertEquals("你好世界1234", extracted)
    }

    @Test
    fun alternateScreenPreservesPrimaryBufferAndCursor() {
        val engine = SolbraidTerminalEngine(40, 10, onSendInput = {})
        engine.writeText("primary-line\r\n")
        assertFalse(engine.isAlternateScreen)
        engine.writeText("\u001b[?1049h\u001b[2J\u001b[1;1Hvim-buffer")
        assertTrue(engine.isAlternateScreen)
        assertEquals("vim-buffer", engine.captureViewport(0).visibleRows[0].toTrimmedString())

        engine.writeText("\u001b[?1049l")
        assertFalse(engine.isAlternateScreen)
        assertEquals("primary-line", engine.captureViewport(0).visibleRows[0].toTrimmedString())
    }

    @Test
    fun respondsToDsrCursorQueryAndSupportsOsc133BlocksAndSearch() {
        val responses = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(
            initialCols = 40,
            initialRows = 6,
            onSendInput = { responses += it.toString(Charsets.UTF_8) },
            schemeProvider = { TerminalThemes.POWERSHELL_BLUE },
        )
        engine.writeText("\u001b[3;5H\u001b[6n")
        assertEquals("\u001b[3;5R", responses.last())

        // OSC 133 命令块 + 回滚全文搜索
        engine.writeText("\u001b[2J\u001b[1;1H\u001b]133;C\u0007PS> echo ok\r\nhello-needle\r\n\u001b]133;D;0\u0007")
        for (i in 1..15) engine.writeText("filler-line-$i\r\n")
        assertTrue(engine.scrollbackSize > 5)
        val matches = engine.search("NEEDLE")
        assertEquals(1, matches.size)
        assertEquals(6, matches[0].startCol)

        val snap = engine.captureViewport(engine.scrollbackSize)
        val blk = snap.blocks.values.firstOrNull()
        assertNotNull(blk)
        assertEquals(0, blk!!.exitCode)
        assertTrue(engine.extractBlockText(blk.id).contains("hello-needle"))
    }

    @Test
    fun encodesSgrMouseBracketedPasteAppCursorAndDecscusr() {
        val sent = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(80, 24, onSendInput = { sent += it.toString(Charsets.UTF_8) })
        engine.writeText("\u001b[?1h\u001b[?1000h\u001b[?1006h\u001b[?2004h\u001b[6 q")

        assertEquals(TerminalCursorStyle.BAR, engine.captureViewport(0).cursorStyleOverride)
        engine.sendKey(TerminalKey.UP)
        assertEquals("\u001bOA", sent.last())

        engine.sendPaste("ls -la")
        assertEquals("\u001b[200~ls -la\u001b[201~", sent.last())

        engine.sendMouse(0, 12, 5, pressed = true)
        assertEquals("\u001b[<0;12;5M", sent.last())
        engine.sendMouse(0, 12, 5, pressed = false)
        assertEquals("\u001b[<0;12;5m", sent.last())
    }

    @Test
    fun psReadLinePredictsHistoryAndTokenizesShellSyntax() {
        val history = listOf("docker compose logs -f api", "git status -sb")
        assertEquals("docker compose logs -f api", PsReadLinePredictor.predict("doc", history))
        assertEquals("journalctl -n 50 --no-pager", PsReadLinePredictor.predict("journalctl -n", emptyList()))

        val tokens = PsReadLinePredictor.tokenize("""grep -rn "hello" /var/log | head -n 5""")
        assertEquals(PsReadLinePredictor.TokenKind.COMMAND, tokens[0].kind)
        assertEquals(PsReadLinePredictor.TokenKind.FLAG, tokens[1].kind)
        assertEquals(PsReadLinePredictor.TokenKind.STRING, tokens[2].kind)
        assertEquals(PsReadLinePredictor.TokenKind.PATH, tokens[3].kind)
        assertEquals(PsReadLinePredictor.TokenKind.OPERATOR, tokens[4].kind)
        assertEquals(PsReadLinePredictor.TokenKind.COMMAND, tokens[5].kind)
    }

    @Test
    fun altScreenShrinkResizeNeverCrashesOutOfBounds() {
        val engine = SolbraidTerminalEngine(40, 20, onSendInput = {})
        engine.writeText("primary-top\r\n")
        engine.writeText("\u001b[?1049h\u001b[20;35Hbottom-edge")
        assertTrue(engine.isAlternateScreen)
        // 模拟上下分屏弹出键盘导致高度骤降至 4 行（原实现在 altScreen 下漏钳制 cursorRow 直接崩溃）
        engine.resize(40, 4)
        engine.writeText("SAFE_AFTER_SHRINK")
        val snap = engine.captureViewport(0)
        assertEquals(4, snap.rows)
        assertTrue(snap.cursorRow in 0 until snap.rows)
        assertTrue(snap.visibleRows[snap.cursorRow].toTrimmedString().contains("SAFE_AFTER_SHRINK"))
        // 退回主屏且不越界
        engine.writeText("\u001b[?1049l")
        assertFalse(engine.isAlternateScreen)
        assertTrue(engine.captureViewport(0).cursorRow in 0 until 4)
    }

    @Test
    fun softWrapReflowPreservesLongLinesAndCursorAcrossResize() {
        val engine = SolbraidTerminalEngine(20, 6, onSendInput = {})
        engine.writeText("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123")
        assertTrue(engine.captureViewport(0).visibleRows[0].softWrapped)
        // 缩窄至 10 列再扩回 35 列，验证软折行完整无损重排
        engine.resize(10, 6)
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123", engine.exportFullTranscript())
        engine.resize(35, 6)
        val snap = engine.captureViewport(0)
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123", snap.visibleRows[0].toTrimmedString())
        assertFalse(snap.visibleRows[0].softWrapped)
        assertEquals(30, snap.cursorCol)
    }

    @Test
    fun invalidUtf8AndSurrogatesNeverCrashExtractionOrRender() {
        val engine = SolbraidTerminalEngine(40, 6, onSendInput = {})
        // F7 BF BF BF (> 0x10FFFF) + ED A0 80 (UTF-8 surrogate) + 合法 ASCII
        val bad = byteArrayOf(
            0xF7.toByte(), 0xBF.toByte(), 0xBF.toByte(), 0xBF.toByte(),
            0xED.toByte(), 0xA0.toByte(), 0x80.toByte(),
            'O'.code.toByte(), 'K'.code.toByte(),
        )
        engine.writeOutput(bad)
        val text = engine.captureViewport(0).visibleRows[0].toTrimmedString()
        assertTrue(text.endsWith("OK"))
    }

    @Test
    fun cjkSearchUrlAndWordBoundsMapExactTerminalColumns() {
        val engine = SolbraidTerminalEngine(60, 6, onSendInput = {})
        engine.writeText("中文前缀 https://example.com/ok 尾词")
        val matches = engine.search("example")
        assertEquals(1, matches.size)
        // "中文前缀" 占 8 列 + 空格 1 列 = 9 列；"https://" 占 8 列 -> "example" 从第 17 列开始
        assertEquals(17, matches[0].startCol)
        assertEquals(23, matches[0].endCol)

        val urls = engine.detectVisibleUrls(0)
        assertEquals(1, urls.size)
        assertEquals(9, urls[0].startCol)
        assertEquals("https://example.com/ok", urls[0].url)

        val word = engine.wordBoundsAtCol(0, 12)
        assertNotNull(word)
        assertEquals(9..30, word)
    }

    @Test
    fun supportsDecset2026SyncOsc8HyperlinksFocusAndSafePaste() {
        val sent = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(60, 8, onSendInput = { sent += it.toString(Charsets.UTF_8) })
        val v0 = engine.frameVersion.value
        engine.writeText("\u001b[?2026hSYNC_FRAME")
        // 同步输出开启期间暂缓推送帧版本，关闭后一次性刷新
        assertEquals(v0, engine.frameVersion.value)
        engine.writeText("\u001b[?2026l")
        assertTrue(engine.frameVersion.value > v0)

        // OSC 8 超链接
        engine.writeText("\r\n\u001b]8;;https://solbraid.dev\u001b\\Docs\u001b]8;;\u001b\\")
        assertEquals("https://solbraid.dev", engine.hyperlinkAt(1, 2))

        // CSI ? 1004 焦点事件
        engine.writeText("\u001b[?1004h")
        engine.sendFocus(true)
        assertEquals("\u001b[I", sent.last())
        engine.sendFocus(false)
        assertEquals("\u001b[O", sent.last())

        // 括号粘贴自动剥离闭合注入序列并归一化 CRLF
        engine.writeText("\u001b[?2004h")
        engine.sendPaste("a\u001b[201~b\r\nc")
        assertEquals("\u001b[200~ab\rc\u001b[201~", sent.last())
    }

    @Test
    fun deleteLinesDoesNotPolluteScrollbackAndClearResetsBlockId() {
        val engine = SolbraidTerminalEngine(40, 4, onSendInput = {})
        engine.notifyCommandSubmitted("echo test")
        engine.writeText("row0\r\nrow1\r\nrow2\r\nrow3")
        val sbBefore = engine.scrollbackSize
        engine.writeText("\u001b[1;1H\u001b[2M") // CSI 2 M 在第 0 行删除两行
        assertEquals(sbBefore, engine.scrollbackSize)
        val snap = engine.captureViewport(0)
        // 底部新清出来的空白行 blockId 必须清零
        assertEquals(0, snap.visibleRows[3].blockId)
    }

    @Test
    fun parsesPowerShellOsc94ProgressStatesAndResetsCleanly() {
        val observed = mutableListOf<TerminalProgressState>()
        val engine = SolbraidTerminalEngine(
            initialCols = 40,
            initialRows = 6,
            onSendInput = {},
            onProgressChanged = { observed += it },
        )
        engine.writeText("\u001b]9;4;1;65\u001b\\")
        assertEquals(TerminalProgressState(1, 65), engine.captureViewport(0).progress)

        engine.writeText("\u001b]9;4;2;40\u0007")
        assertEquals(TerminalProgressState(2, 40), engine.captureViewport(0).progress)

        engine.writeText("\u001b]9;4;3\u001b\\")
        assertEquals(TerminalProgressState(3, 0), engine.captureViewport(0).progress)

        engine.writeText("\u001b]9;4;4;88\u001b\\")
        assertEquals(TerminalProgressState(4, 88), engine.captureViewport(0).progress)

        engine.writeText("\u001b]9;4;0\u001b\\")
        assertEquals(TerminalProgressState(0, 0), engine.captureViewport(0).progress)
        assertEquals(5, observed.size)

        engine.writeText("\u001b]9;4;1;50\u001b\\")
        engine.fullReset()
        assertFalse(engine.captureViewport(0).progress.active)
    }

    @Test
    fun supportsWarpKittyKeyboardProtocolStackAndShiftEnter() {
        val sent = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(40, 10, onSendInput = { sent += it.toString(Charsets.UTF_8) })

        // 默认查询应答 flags=0
        engine.writeText("\u001b[?u")
        assertEquals("\u001b[?0u", sent.last())

        // 压栈 DISAMBIGUATE (1) 后，Shift+Enter / Escape / Ctrl+C 按 Kitty CSI u 编码
        engine.writeText("\u001b[>1u\u001b[?u")
        assertEquals("\u001b[?1u", sent.last())

        engine.sendKey(TerminalKey.ENTER, shift = true)
        assertEquals("\u001b[13;2u", sent.last())

        engine.sendKey(TerminalKey.ESCAPE)
        assertEquals("\u001b[27u", sent.last())

        engine.sendChar('c'.code, ctrl = true)
        assertEquals("\u001b[99;5u", sent.last())

        // 普通无修饰回车仍保持传统 \r
        engine.sendKey(TerminalKey.ENTER)
        assertEquals("\r", sent.last())

        // 出栈后恢复传统编码
        engine.writeText("\u001b[<1u\u001b[?u")
        assertEquals("\u001b[?0u", sent.last())
        engine.sendKey(TerminalKey.ENTER, shift = true)
        assertEquals("\r", sent.last())
    }

    @Test
    fun upgradesVs16EmojiToDoubleWidthAndKeepsNarrowSymbolsSingleWidth() {
        val engine = SolbraidTerminalEngine(30, 6, onSendInput = {})
        // ✓ 与不带 VS16 的 ⚙ 保持单宽，避免提示符与表格错位
        engine.writeText("✓⚙")
        val snap1 = engine.captureViewport(0)
        assertEquals(2, snap1.cursorCol)
        assertEquals(0, snap1.visibleRows[0].flags[0] and TerminalStyle.FLAG_WIDE_LEAD)
        assertEquals(0, snap1.visibleRows[0].flags[1] and TerminalStyle.FLAG_WIDE_LEAD)

        // 遇到 U+FE0F (VS16) 参照 Warp 动态升宽为双宽单元格
        engine.writeText("\r\n⚙\uFE0F!")
        val snap2 = engine.captureViewport(0)
        val row1 = snap2.visibleRows[1]
        assertEquals(0x2699, row1.chars[0])
        assertTrue((row1.flags[0] and TerminalStyle.FLAG_WIDE_LEAD) != 0)
        assertTrue((row1.flags[1] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0)
        assertEquals('!'.code, row1.chars[2])
        assertEquals(3, snap2.cursorCol)
    }

    @Test
    fun warpBoxDrawingRectsAreDisjointAndMinimumContrastLiftsDarkBlue() {
        val sampleChars = (0x2500..0x2503).toList() + (0x250C..0x254B).toList() + (0x2574..0x257F).toList()
        for (cp in sampleChars) {
            assertTrue("Should support U+${cp.toString(16)}", TerminalBoxDrawing.isSupported(cp))
            val rects = TerminalBoxDrawing.computePrimitives(cp, 18f, 36f)
                .filterIsInstance<TerminalBoxDrawing.Primitive.FillRect>()
            assertTrue("Expected non-empty rects for U+${cp.toString(16)}", rects.isNotEmpty())
            // 严格验证 Warp 不重叠不变式（无正面积交集）
            for (i in rects.indices) {
                for (j in (i + 1) until rects.size) {
                    val a = rects[i]
                    val b = rects[j]
                    val ox = maxOf(a.x0, b.x0) < minOf(a.x1, b.x1)
                    val oy = maxOf(a.y0, b.y0) < minOf(a.y1, b.y1)
                    assertFalse("Overlap detected for U+${cp.toString(16)}", ox && oy)
                }
            }
        }
        // 验证双线、圆角、块元素与 Powerline 尖角均受支持
        assertTrue(TerminalBoxDrawing.computePrimitives(0x2554, 18f, 36f).isNotEmpty())
        assertTrue(TerminalBoxDrawing.computePrimitives(0x256D, 18f, 36f).single() is TerminalBoxDrawing.Primitive.CornerArc)
        assertTrue(TerminalBoxDrawing.computePrimitives(0x2588, 18f, 36f).isNotEmpty())
        assertTrue(TerminalBoxDrawing.computePrimitives(0xE0B0, 18f, 36f).single() is TerminalBoxDrawing.Primitive.PowerlineTriangle)

        // 验证 Warp 配色方案与最小对比度护眼提亮（Campbell 暗蓝 #0037DA 在 #0C0C0C 上自动提亮）
        assertEquals(0xFF050505.toInt(), TerminalThemes.WARP_DARK.background)
        val darkBlue = 0xFF0037DA.toInt()
        val lifted = TerminalStyle.ensureReadableContrast(darkBlue, 0xFF0C0C0C.toInt())
        assertTrue((lifted and 0xFF) >= (darkBlue and 0xFF))
        assertTrue(((lifted ushr 8) and 0xFF) > ((darkBlue ushr 8) and 0xFF))
    }

    @Test
    fun supportsDecrqmDynamicColorsTitleStackPixelQueriesAndOscNotifications() {
        val sent = mutableListOf<String>()
        val titles = mutableListOf<String>()
        val cwds = mutableListOf<String>()
        val notes = mutableListOf<Pair<String?, String>>()
        val engine = SolbraidTerminalEngine(
            initialCols = 80,
            initialRows = 24,
            onSendInput = { sent += it.toString(Charsets.UTF_8) },
            onTitleChanged = { titles += it },
            onNotification = { t, b -> notes += t to b },
            onCwdChanged = { cwds += it },
        )
        engine.updateCellPixelMetrics(20, 40)

        // DECRQM 模式查询：2026 开启前=2，开启后=1；1007 默认=1；未知模式=0
        engine.writeText("\u001b[?2026\$p")
        assertEquals("\u001b[?2026;2\$y", sent.last())
        engine.writeText("\u001b[?2026h\u001b[?2026\$p\u001b[?2026l")
        assertEquals("\u001b[?2026;1\$y", sent.last())
        engine.writeText("\u001b[?1007\$p")
        assertEquals("\u001b[?1007;1\$y", sent.last())
        engine.writeText("\u001b[?9999\$p")
        assertEquals("\u001b[?9999;0\$y", sent.last())

        // CSI 14t / 16t 像素尺寸查询 + CSI 22t / 23t 标题栈
        engine.writeText("\u001b[14t")
        assertEquals("\u001b[4;960;1600t", sent.last())
        engine.writeText("\u001b[16t")
        assertEquals("\u001b[6;40;20t", sent.last())
        engine.writeText("\u001b]2;ShellBase\u0007\u001b[22t\u001b]2;VimEdit\u0007\u001b[23t")
        assertEquals(listOf("ShellBase", "VimEdit", "ShellBase"), titles)

        // OSC 4 / 10 / 11 / 12 动态色板与重置 + OSC 50 光标形状
        engine.writeText("\u001b]4;1;#FF1122\u001b\\\u001b]10;#112233;#445566;#778899\u001b\\\u001b]50;CursorShape=2\u0007")
        val snap1 = engine.captureViewport(0)
        assertEquals(0xFFFF1122.toInt(), snap1.paletteOverrides[1])
        assertEquals(0xFF112233.toInt(), snap1.dynamicFgOverride)
        assertEquals(0xFF445566.toInt(), snap1.dynamicBgOverride)
        assertEquals(0xFF778899.toInt(), snap1.dynamicCursorOverride)
        assertEquals(TerminalCursorStyle.UNDERLINE, snap1.cursorStyleOverride)
        engine.writeText("\u001b]104;1\u001b\\\u001b]110\u001b\\\u001b]111\u001b\\\u001b]112\u001b\\")
        val snap2 = engine.captureViewport(0)
        assertTrue(snap2.paletteOverrides.isEmpty())
        assertEquals(null, snap2.dynamicFgOverride)
        assertEquals(null, snap2.dynamicBgOverride)
        assertEquals(null, snap2.dynamicCursorOverride)

        // OSC 7 percent-decode UTF-8 路径 + OSC 9;9 CWD + OSC 9 / 777 通知
        engine.writeText("\u001b]7;file://host/home/agent/my%20repo\u001b\\\u001b]9;9;/var/log\u001b\\")
        assertEquals(listOf("/home/agent/my repo", "/var/log"), cwds)
        engine.writeText("\u001b]9;Build Done\u0007\u001b]777;notify;Agent;Task Ready\u001b\\")
        assertEquals(listOf(null to "Build Done", "Agent" to "Task Ready"), notes)
    }

    @Test
    fun supportsColoredDottedDashedUnderlinesAndBoundedHyperlinkRegistry() {
        val engine = SolbraidTerminalEngine(40, 6, onSendInput = {})
        engine.writeText("\u001b[4:4;58:2::255:0:128mDot\u001b[4:5;58;5;196mDash\u001b[24;59mPlain")
        val row = engine.captureViewport(0).visibleRows[0]
        assertTrue((row.flags[0] and TerminalStyle.FLAG_DOTTED_UNDERLINE) != 0)
        assertEquals(TerminalStyle.trueColor(255, 0, 128), row.underlineColors?.get(0))
        assertTrue((row.flags[3] and TerminalStyle.FLAG_DASHED_UNDERLINE) != 0)
        assertEquals(TerminalStyle.indexed(196), row.underlineColors?.get(3))
        assertEquals(0, row.flags[7] and TerminalStyle.ALL_UNDERLINE_FLAGS)
        assertEquals(null, row.underlineColors?.get(7))

        // 驻留池同 URI 复用同一个 Int ID
        engine.writeText("\r\n\u001b]8;;https://a.dev\u001b\\A\u001b]8;;\u001b\\ \u001b]8;;https://a.dev\u001b\\B\u001b]8;;\u001b\\")
        val row1 = engine.captureViewport(0).visibleRows[1]
        assertEquals(row1.hyperlinkIds?.get(0), row1.hyperlinkIds?.get(2))
        assertEquals("https://a.dev", engine.hyperlinkAt(1, 0))
    }

    @Test
    fun decodesIterm2Osc1337AndKittyGraphicsInlineImages() {
        val sent = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(60, 20, onSendInput = { sent += it.toString(Charsets.UTF_8) })
        // 最小合法 PNG 头部（64x32）
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 64, 0, 0, 0, 32, 8, 2, 0, 0, 0,
        )
        val b64 = Base64.getEncoder().encodeToString(pngHeader)

        // iTerm2 OSC 1337 内联图片
        engine.writeText("\u001b]1337;File=inline=1;width=12;height=4:$b64\u0007")
        val snap1 = engine.captureViewport(0)
        assertEquals(1, snap1.inlineImages.size)
        assertEquals(12, snap1.inlineImages[0].colsSpan)
        assertEquals(4, snap1.inlineImages[0].rowsSpan)

        // Kitty Graphics Protocol APC G 分片上传 + OK 应答 + a=d 删除
        val mid = b64.length / 2
        engine.writeText("\u001b_Ga=T,f=100,i=42,c=10,r=3,m=1;${b64.substring(0, mid)}\u001b\\")
        engine.writeText("\u001b_Gm=0;${b64.substring(mid)}\u001b\\")
        assertEquals("\u001b_Gi=42;OK\u001b\\", sent.last())
        assertEquals(2, engine.captureViewport(0).inlineImages.size)

        engine.writeText("\u001b_Ga=d,i=42\u001b\\")
        assertEquals(1, engine.captureViewport(0).inlineImages.size)
    }

    @Test
    fun supportsRegexCaseWholeWordSearchSemanticWordTripleClickAndFileLinks() {
        val engine = SolbraidTerminalEngine(60, 8, onSendInput = {})
        engine.writeText("ERR_404 in /home/agent/app/Main.kt:42:7 and err_200\r\n")
        engine.writeText("word world foo(bar_baz)\r\n")

        // 区分大小写 + 正则 + 全词
        assertEquals(1, engine.search("ERR_\\d+", TerminalSearchOptions(caseSensitive = true, useRegex = true)).size)
        assertEquals(2, engine.search("err_\\d+", TerminalSearchOptions(caseSensitive = false, useRegex = true)).size)
        assertEquals(1, engine.search("word", TerminalSearchOptions(wholeWord = true)).size)

        // 语义词界选词（括号隔断，不把 foo(bar_baz) 整串吞掉）
        val barBounds = engine.wordBoundsAtCol(1, 16)
        assertEquals(15..21, barBounds)

        // 智能文件路径识别（含行号与列号）
        val links = engine.detectVisibleUrls(0)
        val fileLink = links.firstOrNull { it.kind == TerminalLinkKind.FILE_PATH }
        assertNotNull(fileLink)
        assertEquals("/home/agent/app/Main.kt", fileLink!!.url)
        assertEquals(42, fileLink.fileLine)
        assertEquals(7, fileLink.fileCol)

        // 三击逻辑行（自动合并软折行）
        val narrow = SolbraidTerminalEngine(10, 6, onSendInput = {})
        narrow.writeText("0123456789ABCDEF")
        assertEquals(0..1, narrow.logicalLineRange(1))
    }

    @Test
    fun redactsAllWarpSecretPatternsAndSupportsFuzzyWildcardMatchingAndSafeGuard() {
        val sample = "export OPENAI=sk-abcdefghijklmnopqrstuvwxyz1234567890abcdefghijkl AWS=AKIAIOSFODNN7EXAMPLE IP=192.168.1.42"
        val redacted = TerminalSecretRedactor.redactPlainText(sample)
        assertFalse(redacted.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(redacted.contains("192.168.1.42"))
        assertTrue(redacted.contains("****"))

        // Warp 智能大小写模糊匹配 + 通配符
        assertNotNull(TerminalFuzzyMatcher.match("docker compose logs -f api", "dclapi"))
        assertNotNull(TerminalFuzzyMatcher.match("docker compose logs -f api", "doc*log*api"))
        assertEquals(null, TerminalFuzzyMatcher.match("docker compose logs", "DCL")) // 含大写走精确大小写

        // 高危命令拦截
        assertNotNull(TerminalSafeGuard.inspect("sudo rm -rf /", isRemoteSsh = false))
        assertNotNull(TerminalSafeGuard.inspect("reboot", isRemoteSsh = true))
        assertEquals(null, TerminalSafeGuard.inspect("reboot", isRemoteSsh = false))
        assertEquals(null, TerminalSafeGuard.inspect("rm -rf ./build/tmp", isRemoteSsh = false))
    }

    @Test
    fun stripsPowerShellAnsiSequencesAndSupportsPowerlineExtraAndLightThemes() {
        val raw = "\u001b[1;36mPS>\u001b[0m \u001b]8;;https://pwsh.dev\u001b\\Hello\u001b]8;;\u001b\\"
        assertEquals("PS> Hello", TerminalAnsiUtils.stripAnsi(raw))

        // Nerd Font Powerline Extra 胶囊与斜三角全部受矢量支持
        for (cp in listOf(0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3, 0xE0B4, 0xE0B5, 0xE0B6, 0xE0B7, 0xE0B8, 0xE0BA, 0xE0BC, 0xE0BE)) {
            assertTrue("NF vector missing for U+${cp.toString(16)}", TerminalBoxDrawing.isSupported(cp))
            assertTrue(TerminalBoxDrawing.computePrimitives(cp, 18f, 36f).isNotEmpty())
        }

        // 12 套深浅主题（含 Warp Light / Solarized Light / Gruvbox Light）
        assertEquals(12, TerminalThemes.ALL.size)
        assertTrue(TerminalThemes.WARP_LIGHT.isLight)
        assertTrue(TerminalThemes.SOLARIZED_LIGHT.isLight)
        assertTrue(TerminalThemes.GRUVBOX_LIGHT.isLight)
    }

    @Test
    fun decodesSixelGraphicsAndKittyRawRgbZlibWithInPlaceVideoFrameUpdate() {
        val sent = mutableListOf<String>()
        val engine = SolbraidTerminalEngine(60, 20, onSendInput = { sent += it.toString(Charsets.UTF_8) })

        // 1. DEC Sixel (DCS q ... ST)：定义颜色 #1 亮红，RLE 绘制 12x6 像素色块
        engine.writeText("\u001bPq#1;2;100;0;0#1!12~\u001b\\")
        val snap1 = engine.captureViewport(0)
        assertEquals(1, snap1.inlineImages.size)
        val sixelImg = snap1.inlineImages[0]
        assertEquals(12, sixelImg.pixelWidth)
        assertEquals(6, sixelImg.pixelHeight)
        assertNotNull(sixelImg.argbPixels)
        assertEquals(0xFFFF0000.toInt(), sixelImg.argbPixels!![0])

        // 2. Kitty Graphics 原始 RGB (f=24) + zlib 压缩 (o=z) 上传 2x2 像素帧
        val rawRgb = byteArrayOf(
            0, 255.toByte(), 0,  0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 0,  255.toByte(), 0, 255.toByte(),
        )
        val deflater = java.util.zip.Deflater()
        deflater.setInput(rawRgb)
        deflater.finish()
        val outBuf = ByteArray(128)
        val compLen = deflater.deflate(outBuf)
        deflater.end()
        val b64Zlib = Base64.getEncoder().encodeToString(outBuf.copyOf(compLen))
        engine.writeText("\u001b_Ga=T,f=24,o=z,s=2,v=2,i=77,c=6,r=2;$b64Zlib\u001b\\")
        val snap2 = engine.captureViewport(0)
        val kittyFrame1 = snap2.inlineImages.first { it.id == 77 }
        assertEquals(2, kittyFrame1.pixelWidth)
        assertEquals(2, kittyFrame1.pixelHeight)
        assertEquals(0xFF00FF00.toInt(), kittyFrame1.argbPixels!![0])
        assertEquals(0xFF0000FF.toInt(), kittyFrame1.argbPixels[1])
        val rev1 = kittyFrame1.revision

        // 3. 同 ID 原地覆盖更新下一视频帧（revision 递增且不产生重复条目）
        val rawRgba = byteArrayOf(
            10, 20, 30, 255.toByte(),  40, 50, 60, 255.toByte(),
            70, 80, 90, 255.toByte(),  100, 110, 120, 255.toByte(),
        )
        val b64Rgba = Base64.getEncoder().encodeToString(rawRgba)
        engine.writeText("\u001b_Ga=T,f=32,s=2,v=2,i=77,c=6,r=2;$b64Rgba\u001b\\")
        val snap3 = engine.captureViewport(0)
        assertEquals(2, snap3.inlineImages.size)
        val kittyFrame2 = snap3.inlineImages.first { it.id == 77 }
        assertTrue(kittyFrame2.revision > rev1)
        assertEquals((0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30, kittyFrame2.argbPixels!![0])
    }

    @Test
    fun supportsBrailleSextantsOsc1338MediaAndPreTopScrollRow() {
        val mediaReqs = mutableListOf<TerminalMediaRequest>()
        val engine = SolbraidTerminalEngine(40, 5, onSendInput = {}, onMediaRequest = { mediaReqs += it })

        // 1. Braille (2x4 盲文点阵) 与 Sextant (2x3 六分马赛克) 程序化矢量渲染
        assertTrue(TerminalBoxDrawing.isSupported(0x28FF))
        assertEquals(8, TerminalBoxDrawing.computePrimitives(0x28FF, 16f, 32f).size)
        assertTrue(TerminalBoxDrawing.isSupported(0x1FB00))
        assertEquals(1, TerminalBoxDrawing.computePrimitives(0x1FB00, 16f, 30f).size)
        assertTrue(TerminalBoxDrawing.isSupported(0x1FB3B))
        assertEquals(5, TerminalBoxDrawing.computePrimitives(0x1FB3B, 16f, 30f).size)

        // 2. OSC 1338 终端原生媒体协议（视频播放 & 富文档渲染）
        engine.writeText("\u001b]1338;play=/home/agent/demo.mp4\u0007")
        engine.writeText("\u001b]1338;render=markdown;/home/agent/README.md\u001b\\")
        assertEquals(2, mediaReqs.size)
        assertEquals("play", mediaReqs[0].action)
        assertEquals("/home/agent/demo.mp4", mediaReqs[0].source)
        assertEquals("render", mediaReqs[1].action)
        assertEquals("markdown", mediaReqs[1].kind)
        assertEquals("/home/agent/README.md", mediaReqs[1].source)

        // 3. 亚像素平滑滚动预取行 preTopRow
        for (i in 1..10) engine.writeText("LINE_$i\r\n")
        val snap = engine.captureViewport(scrollOffsetLines = 0)
        assertNotNull(snap.preTopRow)
        assertTrue(snap.preTopRow!!.toTrimmedString().startsWith("LINE_"))
    }
}
