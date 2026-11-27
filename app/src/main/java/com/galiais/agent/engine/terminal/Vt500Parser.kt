package com.galiais.agent.engine.terminal

import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 完整纯 Kotlin VT500 / xterm-256color / TrueColor 流式字节解析器：
 * - 内置跨 TCP 粘包/拆包安全的流式 UTF-8 解码器；
 * - 完整覆盖 CSI（含冒号/分号 TrueColor SGR、DECSET/DECRST、DSR/DA 应答、DECSCUSR 光标形态）、
 *   OSC（0/2 标题、7 目录、8 链接、10/11 前背景色查询、52 剪贴板、133 Shell Integration 命令块）、
 *   ESC（DECSC/DECRC、DEC 特殊制表字符集、索引与重置）与 DCS 吞吐。
 */
/** 终端主动媒体播放 / 富文本渲染请求（OSC 1338 协议）。 */
data class TerminalMediaRequest(
    val action: String, // "play" | "render"
    val kind: String,   // "video" | "audio" | "image" | "markdown" | "html" | "svg" | "pdf" | "auto"
    val source: String, // 路径、URL 或内联 Base64 数据
    val title: String = "",
)

class Vt500Parser(
    private val buffer: TerminalBuffer,
    private val onSendResponse: (ByteArray) -> Unit,
    private val onBell: () -> Unit,
    private val onClipboardCopy: (String) -> Unit,
    private val onTitleChanged: (String) -> Unit,
    private val onProgressChanged: (TerminalProgressState) -> Unit = {},
    private val onNotification: (title: String?, body: String) -> Unit = { _, _ -> },
    private val onCwdChanged: (String) -> Unit = {},
    private val onMediaRequest: (TerminalMediaRequest) -> Unit = {},
    private val schemeProvider: () -> TerminalColorScheme,
) {
    private enum class State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE, CSI_ENTRY, CSI_PARAM, OSC_STRING, OSC_ESC, APC_STRING, APC_ESC, DCS_PASSTHROUGH, DCS_ESC
    }

    private var state = State.GROUND
    private var escIntermediate: Int = 0
    private var csiPrivate: Char = '\u0000'
    private var csiIntermediate: Char = '\u0000'
    private val csiRaw = StringBuilder(32)
    private var oscRaw = ByteArrayOutputStream(256)
    private var apcRaw = ByteArrayOutputStream(256)
    private var dcsRaw = ByteArrayOutputStream(256)
    private var lastPrintedCp: Int = ' '.code

    // 流式 UTF-8 解码状态（容错跨包拆分的多字节中文/Emoji，严格拦截非法码点/代理区/过长编码）
    private var utf8CodePoint = 0
    private var utf8Remaining = 0
    private var utf8MinCp = 0

    private fun resetOrShrink(buf: ByteArrayOutputStream): ByteArrayOutputStream =
        if (buf.size() > 65_536) ByteArrayOutputStream(256) else buf.apply { reset() }

    fun reset() {
        state = State.GROUND
        escIntermediate = 0
        csiPrivate = '\u0000'
        csiIntermediate = '\u0000'
        csiRaw.clear()
        oscRaw = resetOrShrink(oscRaw)
        apcRaw = resetOrShrink(apcRaw)
        dcsRaw = resetOrShrink(dcsRaw)
        utf8CodePoint = 0
        utf8Remaining = 0
        utf8MinCp = 0
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            processByte(data[i].toInt() and 0xFF)
        }
    }

    private fun processByte(b: Int) {
        // 先处理正在进行的 UTF-8 多字节序列（仅在 GROUND 态）
        if (state == State.GROUND && utf8Remaining > 0) {
            if ((b and 0xC0) == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) {
                    val cp = utf8CodePoint
                    val valid = cp >= utf8MinCp && cp <= 0x10FFFF && cp !in 0xD800..0xDFFF
                    val safe = if (valid) cp else 0xFFFD
                    lastPrintedCp = safe
                    buffer.putCodePoint(safe)
                }
                return
            } else {
                // 非法续字节：放弃残留序列，继续按正常字节处理当前 b
                utf8Remaining = 0
            }
        }

        // CAN (0x18) / SUB (0x1A) 在任何控制态立即中止回 GROUND
        if (b == 0x18 || b == 0x1A) {
            state = State.GROUND
            return
        }

        when (state) {
            State.GROUND -> when {
                b == 0x1B -> { state = State.ESCAPE; escIntermediate = 0 }
                b < 0x20 -> executeC0(b)
                b == 0x7F -> Unit // DEL 忽略
                b < 0x80 -> { lastPrintedCp = b; buffer.putCodePoint(b) }
                b in 0xC2..0xDF -> { utf8CodePoint = b and 0x1F; utf8Remaining = 1; utf8MinCp = 0x80 }
                b in 0xE0..0xEF -> { utf8CodePoint = b and 0x0F; utf8Remaining = 2; utf8MinCp = 0x800 }
                b in 0xF0..0xF4 -> { utf8CodePoint = b and 0x07; utf8Remaining = 3; utf8MinCp = 0x10000 }
                else -> Unit
            }

            State.ESCAPE -> when (b) {
                '['.code -> {
                    state = State.CSI_ENTRY
                    csiPrivate = '\u0000'
                    csiIntermediate = '\u0000'
                    csiRaw.clear()
                }
                ']'.code -> { state = State.OSC_STRING; oscRaw.reset() }
                '_'.code -> { state = State.APC_STRING; apcRaw.reset() }
                'P'.code, '^'.code -> { state = State.DCS_PASSTHROUGH; dcsRaw.reset() }
                '('.code, ')'.code, '*'.code, '+'.code, '#'.code, ' '.code -> {
                    escIntermediate = b
                    state = State.ESCAPE_INTERMEDIATE
                }
                else -> {
                    dispatchEsc(b)
                    state = State.GROUND
                }
            }

            State.ESCAPE_INTERMEDIATE -> {
                if (b in 0x20..0x2F) {
                    escIntermediate = b
                } else {
                    when (escIntermediate) {
                        '('.code -> buffer.g0GraphicsCharset = (b == '0'.code)
                        ')'.code -> buffer.g1GraphicsCharset = (b == '0'.code)
                    }
                    state = State.GROUND
                }
            }

            State.CSI_ENTRY, State.CSI_PARAM -> when {
                b == 0x1B -> { state = State.ESCAPE; escIntermediate = 0 }
                b < 0x20 -> executeC0(b)
                b in 0x3C..0x3F && state == State.CSI_ENTRY && csiRaw.isEmpty() -> {
                    csiPrivate = b.toChar()
                    state = State.CSI_PARAM
                }
                b in 0x30..0x3B -> {
                    if (csiRaw.length < 256) csiRaw.append(b.toChar())
                    state = State.CSI_PARAM
                }
                b in 0x20..0x2F -> {
                    csiIntermediate = b.toChar()
                    state = State.CSI_PARAM
                }
                b in 0x40..0x7E -> {
                    dispatchCsi(b.toChar())
                    state = State.GROUND
                }
                else -> state = State.GROUND
            }

            State.OSC_STRING -> when (b) {
                0x07 -> { dispatchOsc(); state = State.GROUND }
                0x1B -> state = State.OSC_ESC
                else -> if (oscRaw.size() < 6 * 1024 * 1024) oscRaw.write(b)
            }

            State.OSC_ESC -> {
                if (b == '\\'.code) {
                    dispatchOsc()
                    state = State.GROUND
                } else {
                    dispatchOsc()
                    state = State.ESCAPE
                    processByte(b)
                }
            }

            State.APC_STRING -> when (b) {
                0x1B -> state = State.APC_ESC
                else -> if (apcRaw.size() < 6 * 1024 * 1024) apcRaw.write(b)
            }

            State.APC_ESC -> {
                if (b == '\\'.code) {
                    dispatchApc()
                    state = State.GROUND
                } else {
                    apcRaw.reset()
                    state = State.ESCAPE
                    processByte(b)
                }
            }

            State.DCS_PASSTHROUGH -> {
                if (b == 0x1B) state = State.DCS_ESC
                else if (dcsRaw.size() < 4 * 1024 * 1024) dcsRaw.write(b)
            }

            State.DCS_ESC -> {
                state = when (b) {
                    '\\'.code -> {
                        dispatchDcs()
                        State.GROUND
                    }
                    0x1B -> State.DCS_ESC
                    else -> {
                        if (dcsRaw.size() < 4 * 1024 * 1024) dcsRaw.write(b)
                        State.DCS_PASSTHROUGH
                    }
                }
            }
        }
    }

    private fun executeC0(b: Int) {
        when (b) {
            0x07 -> onBell()
            0x08 -> buffer.backspace()
            0x09 -> buffer.tab()
            0x0A, 0x0B, 0x0C -> buffer.lineFeed(applyLnm = true)
            0x0D -> buffer.carriageReturn()
            0x0E -> buffer.useG1Charset = true
            0x0F -> buffer.useG1Charset = false
        }
    }

    private fun dispatchEsc(finalByte: Int) {
        when (finalByte.toChar()) {
            'D' -> buffer.lineFeed()
            'E' -> { buffer.carriageReturn(); buffer.lineFeed() }
            'H' -> buffer.setTabStop()
            'M' -> buffer.reverseIndex()
            '7' -> buffer.saveCursor()
            '8' -> buffer.restoreCursor()
            '=' -> buffer.appKeypad = true
            '>' -> buffer.appKeypad = false
            'c' -> buffer.fullReset()
        }
    }

    private fun parseIntParams(): IntArray {
        if (csiRaw.isEmpty()) return IntArray(0)
        val parts = csiRaw.toString().split(';')
        return IntArray(parts.size) { i ->
            val p = parts[i]
            val colon = p.indexOf(':')
            val head = if (colon >= 0) p.substring(0, colon) else p
            head.toIntOrNull() ?: 0
        }
    }

    private fun param(params: IntArray, idx: Int, defaultVal: Int): Int {
        val v = params.getOrNull(idx) ?: 0
        return if (v <= 0) defaultVal else v
    }

    private fun dispatchCsi(cmd: Char) {
        if (cmd == 'm' && csiPrivate == '\u0000' && csiIntermediate == '\u0000') {
            applySgr(csiRaw.toString())
            return
        }
        val params = parseIntParams()
        if (csiPrivate == '?') {
            if (csiIntermediate == '$' && cmd == 'p') {
                val mode = params.getOrNull(0) ?: 0
                val status = queryDecPrivateMode(mode)
                onSendResponse("\u001b[?$mode;$status\$y".toByteArray(Charsets.US_ASCII))
                return
            }
            when (cmd) {
                'h' -> params.forEach { setPrivateMode(it, true) }
                'l' -> params.forEach { setPrivateMode(it, false) }
                'u' -> onSendResponse("\u001b[?${buffer.kittyKeyboardFlags}u".toByteArray(Charsets.US_ASCII))
            }
            return
        }
        if (csiPrivate == '\u0000' && csiIntermediate == '$' && cmd == 'p') {
            val mode = params.getOrNull(0) ?: 0
            val status = when (mode) {
                4 -> if (buffer.insertMode) 1 else 2
                20 -> if (buffer.lineFeedNewLineMode) 1 else 2
                else -> 0
            }
            onSendResponse("\u001b[$mode;$status\$y".toByteArray(Charsets.US_ASCII))
            return
        }
        if (csiPrivate == '>') {
            when (cmd) {
                'c' -> onSendResponse("\u001b[>0;279;0c".toByteArray(Charsets.US_ASCII))
                'q' -> onSendResponse("\u001bP>|SolbraidTerminal 2.0\u001b\\".toByteArray(Charsets.US_ASCII))
                'u' -> buffer.pushKittyKeyboardMode(params.getOrNull(0) ?: 0)
            }
            return
        }
        if (csiPrivate == '<') {
            if (cmd == 'u') buffer.popKittyKeyboardModes(param(params, 0, 1))
            return
        }
        if (csiPrivate == '=') {
            if (cmd == 'u') buffer.setKittyKeyboardMode(params.getOrNull(0) ?: 0, param(params, 1, 1))
            return
        }
        if (csiIntermediate == ' ' && cmd == 'q') {
            val shape = params.getOrNull(0) ?: 0
            buffer.cursorStyleOverride = when (shape) {
                0 -> null
                1, 2 -> TerminalCursorStyle.BLOCK
                3, 4 -> TerminalCursorStyle.UNDERLINE
                5, 6 -> TerminalCursorStyle.BAR
                else -> null
            }
            buffer.cursorBlink = shape == 0 || shape % 2 == 1
            return
        }
        when (cmd) {
            'A' -> buffer.moveCursorRelative(-param(params, 0, 1), 0)
            'B', 'e' -> buffer.moveCursorRelative(param(params, 0, 1), 0)
            'C', 'a' -> buffer.moveCursorRelative(0, param(params, 0, 1))
            'D' -> buffer.moveCursorRelative(0, -param(params, 0, 1))
            'E' -> { buffer.moveCursorRelative(param(params, 0, 1), 0); buffer.carriageReturn() }
            'F' -> { buffer.moveCursorRelative(-param(params, 0, 1), 0); buffer.carriageReturn() }
            'G', '`' -> buffer.moveCursorTo((if (buffer.originMode) buffer.cursorRow - buffer.scrollTop else buffer.cursorRow) + 1, param(params, 0, 1))
            'H', 'f' -> buffer.moveCursorTo(param(params, 0, 1), param(params, 1, 1))
            'I' -> buffer.tab(param(params, 0, 1))
            'Z' -> buffer.backTab(param(params, 0, 1))
            'd' -> buffer.moveCursorTo(param(params, 0, 1), buffer.cursorCol + 1)
            'J' -> buffer.eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> buffer.eraseLine(params.getOrNull(0) ?: 0)
            'L' -> buffer.insertLines(param(params, 0, 1))
            'M' -> buffer.deleteLines(param(params, 0, 1))
            'P' -> buffer.deleteChars(param(params, 0, 1))
            '@' -> buffer.insertBlankChars(param(params, 0, 1))
            'X' -> buffer.eraseChars(param(params, 0, 1))
            'S' -> buffer.scrollUp(param(params, 0, 1))
            'T' -> buffer.scrollDown(param(params, 0, 1))
            'b' -> if (lastPrintedCp >= 0x20) repeat(param(params, 0, 1).coerceAtMost(2000)) { buffer.putCodePoint(lastPrintedCp) }
            'g' -> buffer.clearTabStop(params.getOrNull(0) ?: 0)
            'h' -> params.forEach {
                when (it) {
                    4 -> buffer.insertMode = true
                    20 -> buffer.lineFeedNewLineMode = true
                }
            }
            'l' -> params.forEach {
                when (it) {
                    4 -> buffer.insertMode = false
                    20 -> buffer.lineFeedNewLineMode = false
                }
            }
            'r' -> buffer.setScrollRegion(param(params, 0, 1), param(params, 1, buffer.rows))
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'c' -> if ((params.getOrNull(0) ?: 0) == 0) onSendResponse("\u001b[?62;22c".toByteArray(Charsets.US_ASCII))
            'n' -> when (params.getOrNull(0) ?: 0) {
                5 -> onSendResponse("\u001b[0n".toByteArray(Charsets.US_ASCII))
                6 -> {
                    val r = (if (buffer.originMode) buffer.cursorRow - buffer.scrollTop else buffer.cursorRow) + 1
                    val c = buffer.cursorCol + 1
                    onSendResponse("\u001b[$r;${c}R".toByteArray(Charsets.US_ASCII))
                }
            }
            't' -> when (params.getOrNull(0) ?: 0) {
                14 -> {
                    val hPx = buffer.rows * buffer.cellPixelHeight
                    val wPx = buffer.cols * buffer.cellPixelWidth
                    onSendResponse("\u001b[4;$hPx;${wPx}t".toByteArray(Charsets.US_ASCII))
                }
                16 -> onSendResponse("\u001b[6;${buffer.cellPixelHeight};${buffer.cellPixelWidth}t".toByteArray(Charsets.US_ASCII))
                18 -> onSendResponse("\u001b[8;${buffer.rows};${buffer.cols}t".toByteArray(Charsets.US_ASCII))
                22 -> buffer.pushTitle()
                23 -> buffer.popTitle()?.let(onTitleChanged)
            }
        }
    }

    /** DECRQM (CSI ? Ps $ p) 模式状态查询应答（1=已开启, 2=未开启, 0=未知）。 */
    private fun queryDecPrivateMode(mode: Int): Int = when (mode) {
        1 -> if (buffer.appCursorKeys) 1 else 2
        6 -> if (buffer.originMode) 1 else 2
        7 -> if (buffer.autoWrapMode) 1 else 2
        12 -> if (buffer.cursorBlink) 1 else 2
        25 -> if (buffer.cursorVisible) 1 else 2
        47, 1047, 1049 -> if (buffer.useAltScreen) 1 else 2
        1000, 1002, 1003 -> if (buffer.mouseTrackingMode == mode) 1 else 2
        1004 -> if (buffer.focusReportingMode) 1 else 2
        1006 -> if (buffer.sgrMouseMode) 1 else 2
        1007 -> if (buffer.alternateScrollMode) 1 else 2
        2004 -> if (buffer.bracketedPasteMode) 1 else 2
        2026 -> if (buffer.synchronizedOutput) 1 else 2
        else -> 0
    }

    private fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> buffer.appCursorKeys = enabled
            6 -> {
                buffer.originMode = enabled
                buffer.moveCursorTo(1, 1)
            }
            7 -> buffer.autoWrapMode = enabled
            12 -> buffer.cursorBlink = enabled
            25 -> buffer.cursorVisible = enabled
            47, 1047 -> if (enabled) buffer.switchToAlternateScreen(save = false, clearAlt = true) else buffer.switchToPrimaryScreen(restore = false)
            1048 -> if (enabled) buffer.saveCursor() else buffer.restoreCursor()
            1049 -> if (enabled) buffer.switchToAlternateScreen(save = true, clearAlt = true) else buffer.switchToPrimaryScreen(restore = true)
            1000, 1002, 1003 -> {
                if (enabled) buffer.mouseTrackingMode = mode
                else if (buffer.mouseTrackingMode == mode) buffer.mouseTrackingMode = 0
            }
            1004 -> buffer.focusReportingMode = enabled
            1006 -> buffer.sgrMouseMode = enabled
            1007 -> buffer.alternateScrollMode = enabled
            2004 -> buffer.bracketedPasteMode = enabled
            2026 -> buffer.synchronizedOutput = enabled
        }
    }

    /** 完整 SGR 解析（同时兼容传统分号 `38;2;r;g;b` 与 Neovim/tmux 冒号子参数 `38:2::r:g:b` / `4:3` 波浪下划线）。 */
    private fun applySgr(raw: String) {
        if (raw.isEmpty()) {
            buffer.resetAttributes()
            return
        }
        val tokens = raw.split(';')
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (':' in tok) {
                val sub = tok.split(':').map { it.toIntOrNull() ?: 0 }
                when (sub[0]) {
                    4 -> {
                        val uStyle = sub.getOrNull(1) ?: 1
                        buffer.curFlags = buffer.curFlags and TerminalStyle.ALL_UNDERLINE_FLAGS.inv()
                        when (uStyle) {
                            1 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_UNDERLINE
                            2 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_DOUBLE_UNDERLINE
                            3 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_CURLY_UNDERLINE
                            4 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_DOTTED_UNDERLINE
                            5 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_DASHED_UNDERLINE
                        }
                    }
                    38, 48, 58 -> {
                        val mode = sub.getOrNull(1) ?: 0
                        val color = when {
                            mode == 5 && sub.size >= 3 -> TerminalStyle.indexed(sub[2])
                            mode == 2 && sub.size >= 6 -> TerminalStyle.trueColor(sub[3], sub[4], sub[5])
                            mode == 2 && sub.size >= 5 -> TerminalStyle.trueColor(sub[2], sub[3], sub[4])
                            else -> null
                        }
                        if (color != null) {
                            when (sub[0]) {
                                38 -> buffer.curFg = color
                                48 -> buffer.curBg = color
                                58 -> buffer.curUnderlineColor = color
                            }
                        }
                    }
                }
                i++
                continue
            }
            val v = tok.toIntOrNull() ?: 0
            when (v) {
                0 -> buffer.resetAttributes()
                1 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_BOLD
                2 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_DIM
                3 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_ITALIC
                4 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_UNDERLINE
                5, 6 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_BLINK
                7 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_INVERSE
                8 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_HIDDEN
                9 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_STRIKETHROUGH
                21 -> buffer.curFlags = buffer.curFlags or TerminalStyle.FLAG_DOUBLE_UNDERLINE
                22 -> buffer.curFlags = buffer.curFlags and (TerminalStyle.FLAG_BOLD or TerminalStyle.FLAG_DIM).inv()
                23 -> buffer.curFlags = buffer.curFlags and TerminalStyle.FLAG_ITALIC.inv()
                24 -> buffer.curFlags = buffer.curFlags and TerminalStyle.ALL_UNDERLINE_FLAGS.inv()
                25 -> buffer.curFlags = buffer.curFlags and TerminalStyle.FLAG_BLINK.inv()
                27 -> buffer.curFlags = buffer.curFlags and TerminalStyle.FLAG_INVERSE.inv()
                28 -> buffer.curFlags = buffer.curFlags and TerminalStyle.FLAG_HIDDEN.inv()
                29 -> buffer.curFlags = buffer.curFlags and TerminalStyle.FLAG_STRIKETHROUGH.inv()
                in 30..37 -> buffer.curFg = TerminalStyle.indexed(v - 30)
                38 -> {
                    val mode = tokens.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5 && i + 2 < tokens.size) {
                        buffer.curFg = TerminalStyle.indexed(tokens[i + 2].toIntOrNull() ?: 0)
                        i += 2
                    } else if (mode == 2 && i + 4 < tokens.size) {
                        buffer.curFg = TerminalStyle.trueColor(
                            tokens[i + 2].toIntOrNull() ?: 0,
                            tokens[i + 3].toIntOrNull() ?: 0,
                            tokens[i + 4].toIntOrNull() ?: 0,
                        )
                        i += 4
                    }
                }
                39 -> buffer.curFg = TerminalStyle.COLOR_DEFAULT
                in 40..47 -> buffer.curBg = TerminalStyle.indexed(v - 40)
                48 -> {
                    val mode = tokens.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5 && i + 2 < tokens.size) {
                        buffer.curBg = TerminalStyle.indexed(tokens[i + 2].toIntOrNull() ?: 0)
                        i += 2
                    } else if (mode == 2 && i + 4 < tokens.size) {
                        buffer.curBg = TerminalStyle.trueColor(
                            tokens[i + 2].toIntOrNull() ?: 0,
                            tokens[i + 3].toIntOrNull() ?: 0,
                            tokens[i + 4].toIntOrNull() ?: 0,
                        )
                        i += 4
                    }
                }
                49 -> buffer.curBg = TerminalStyle.COLOR_DEFAULT
                58 -> {
                    val mode = tokens.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5 && i + 2 < tokens.size) {
                        buffer.curUnderlineColor = TerminalStyle.indexed(tokens[i + 2].toIntOrNull() ?: 0)
                        i += 2
                    } else if (mode == 2 && i + 4 < tokens.size) {
                        buffer.curUnderlineColor = TerminalStyle.trueColor(
                            tokens[i + 2].toIntOrNull() ?: 0,
                            tokens[i + 3].toIntOrNull() ?: 0,
                            tokens[i + 4].toIntOrNull() ?: 0,
                        )
                        i += 4
                    }
                }
                59 -> buffer.curUnderlineColor = TerminalStyle.COLOR_DEFAULT
                in 90..97 -> buffer.curFg = TerminalStyle.indexed(v - 90 + 8)
                in 100..107 -> buffer.curBg = TerminalStyle.indexed(v - 100 + 8)
            }
            i++
        }
    }

    private fun dispatchDcs() {
        if (dcsRaw.size() == 0) return
        val text = dcsRaw.toString("ISO-8859-1")
        dcsRaw = resetOrShrink(dcsRaw)
        // DEC Sixel 图形/实时视频流：DCS [P1;P2;P3] q <sixel> ST
        val qIdx = text.indexOf('q')
        if (qIdx in 0..24) {
            val prefix = text.substring(0, qIdx)
            if (prefix.all { it.isDigit() || it == ';' }) {
                val absRow = buffer.absoluteRowOfScreen(buffer.cursorRow)
                val advance = buffer.imageStore.handleSixel(text, absRow, buffer.cursorCol, buffer.cols, buffer.rows)
                if (advance > 0 && !buffer.useAltScreen) {
                    repeat(advance) { buffer.lineFeed() }
                    buffer.carriageReturn()
                }
            }
        }
    }

    private fun dispatchApc() {
        val text = apcRaw.toString("UTF-8")
        apcRaw = resetOrShrink(apcRaw)
        if (!text.startsWith("G")) return
        val absRow = buffer.absoluteRowOfScreen(buffer.cursorRow)
        val (advanceRows, resp) = buffer.imageStore.handleKittyApc(text, absRow, buffer.cursorCol, buffer.cols, buffer.rows)
        if (advanceRows > 0) {
            repeat(advanceRows) { buffer.lineFeed() }
            buffer.carriageReturn()
        }
        if (!resp.isNullOrEmpty()) onSendResponse(resp.toByteArray(Charsets.US_ASCII))
    }

    private fun dispatchOsc() {
        val text = oscRaw.toString("UTF-8")
        oscRaw = resetOrShrink(oscRaw)
        val sep = text.indexOf(';')
        val code: Int
        val payload: String
        if (sep <= 0) {
            code = text.toIntOrNull() ?: return
            payload = ""
        } else {
            code = text.substring(0, sep).toIntOrNull() ?: return
            payload = text.substring(sep + 1)
        }
        when (code) {
            0, 1, 2 -> {
                buffer.windowTitle = payload
                onTitleChanged(payload)
            }
            4 -> {
                // OSC 4;index;spec[;index;spec...] 动态覆写/查询 256 调色板
                val parts = payload.split(';')
                var idx = 0
                while (idx + 1 < parts.size) {
                    val colorIdx = parts[idx].toIntOrNull()
                    val spec = parts[idx + 1]
                    if (colorIdx != null && colorIdx in 0..255) {
                        if (spec == "?") {
                            val argb = TerminalStyle.resolveColor(
                                TerminalStyle.indexed(colorIdx),
                                isFg = true,
                                bold = false,
                                scheme = schemeProvider(),
                                paletteOverrides = buffer.paletteOverrides,
                            )
                            sendRgbOscResponse("4;$colorIdx", argb)
                        } else {
                            parseXColor(spec)?.let { buffer.paletteOverrides[colorIdx] = it }
                        }
                    }
                    idx += 2
                }
            }
            7 -> {
                val decoded = decodeOsc7Path(payload)
                if (decoded.isNotEmpty()) {
                    buffer.reportedCwd = decoded
                    onCwdChanged(decoded)
                }
            }
            8 -> {
                val semi = payload.indexOf(';')
                if (semi >= 0) {
                    val uri = payload.substring(semi + 1).trim()
                    val safe = uri.takeIf { it.isNotEmpty() && (it.startsWith("http://") || it.startsWith("https://") || it.startsWith("file://")) }
                    buffer.setActiveHyperlink(safe)
                } else {
                    buffer.setActiveHyperlink(null)
                }
            }
            9 -> {
                val parts = payload.split(';')
                val first = parts.firstOrNull().orEmpty()
                when {
                    first == "4" -> {
                        // PowerShell 7 / Windows Terminal ConEmu OSC 9;4;<state>;<percent>
                        val mode = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val pct = (parts.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, 100)
                        val next = if (mode in 1..4) TerminalProgressState(mode, pct) else TerminalProgressState()
                        buffer.progressState = next
                        onProgressChanged(next)
                    }
                    first == "9" -> {
                        // PowerShell / Windows Terminal OSC 9;9;<cwd> 当前工作目录上报
                        val cwdPath = parts.drop(1).joinToString(";").trim()
                        if (cwdPath.isNotEmpty()) {
                            buffer.reportedCwd = cwdPath
                            onCwdChanged(cwdPath)
                        }
                    }
                    first.isNotEmpty() && !first.all { it.isDigit() } -> {
                        // iTerm2 / Warp OSC 9;<message> 终端主动通知
                        onNotification(null, payload.trim())
                    }
                }
            }
            10, 11, 12 -> {
                val parts = payload.split(';')
                var curCode = code
                for (p in parts) {
                    if (curCode > 12) break
                    if (p == "?") {
                        val scheme = schemeProvider()
                        val argb = when (curCode) {
                            10 -> buffer.dynamicFgOverride ?: scheme.foreground
                            11 -> buffer.dynamicBgOverride ?: scheme.background
                            else -> buffer.dynamicCursorOverride ?: scheme.cursor
                        }
                        sendRgbOscResponse(curCode.toString(), argb)
                    } else {
                        parseXColor(p)?.let { argb ->
                            when (curCode) {
                                10 -> buffer.dynamicFgOverride = argb
                                11 -> buffer.dynamicBgOverride = argb
                                12 -> buffer.dynamicCursorOverride = argb
                            }
                        }
                    }
                    curCode++
                }
            }
            50 -> {
                // iTerm2 / Warp OSC 50;CursorShape=0(Block)/1(Bar)/2(Underline)
                if (payload.startsWith("CursorShape=") && payload.length >= 13) {
                    buffer.cursorStyleOverride = when (payload[12]) {
                        '0' -> TerminalCursorStyle.BLOCK
                        '1' -> TerminalCursorStyle.BAR
                        '2' -> TerminalCursorStyle.UNDERLINE
                        else -> buffer.cursorStyleOverride
                    }
                }
            }
            52 -> {
                val semi = payload.indexOf(';')
                if (semi >= 0) {
                    val b64 = payload.substring(semi + 1).trim()
                    if (b64.isNotEmpty() && b64 != "?") {
                        runCatching {
                            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                            onClipboardCopy(decoded)
                        }
                    }
                }
            }
            104 -> {
                if (payload.isEmpty()) {
                    buffer.paletteOverrides.clear()
                } else {
                    payload.split(';').forEach { p -> p.toIntOrNull()?.let { buffer.paletteOverrides.remove(it) } }
                }
            }
            110 -> buffer.dynamicFgOverride = null
            111 -> buffer.dynamicBgOverride = null
            112 -> buffer.dynamicCursorOverride = null
            133 -> {
                val parts = payload.split(';')
                val kind = parts.firstOrNull().orEmpty()
                val arg = parts.getOrNull(1).orEmpty()
                buffer.onOsc133(kind, arg)
            }
            777 -> {
                // urxvt / foot / Warp OSC 777;notify;<title>;<body>
                val parts = payload.split(';')
                if (parts.size >= 3 && parts[0] == "notify") {
                    val title = parts[1].trim().takeIf { it.isNotEmpty() }
                    val body = parts.drop(2).joinToString(";").trim()
                    if (body.isNotEmpty()) onNotification(title, body)
                }
            }
            1337 -> {
                // iTerm2 / Warp 内联图片协议 OSC 1337;File=... / MultipartFile / FilePart / FileEnd
                val absRow = buffer.absoluteRowOfScreen(buffer.cursorRow)
                val advanceRows = buffer.imageStore.handleOsc1337(payload, absRow, buffer.cursorCol, buffer.cols, buffer.rows)
                if (advanceRows > 0) {
                    repeat(advanceRows) { buffer.lineFeed() }
                    buffer.carriageReturn()
                }
            }
            1338 -> {
                // Solbraid 终端富媒体与视频直播协议：OSC 1338;play=<path_or_url> / OSC 1338;render=<kind>;<source>
                when {
                    payload.startsWith("play=") -> {
                        val src = payload.substring(5).trim()
                        if (src.isNotEmpty()) onMediaRequest(TerminalMediaRequest("play", "video", src, src.substringAfterLast('/')))
                    }
                    payload.startsWith("render=") -> {
                        val rest = payload.substring(7)
                        val semi = rest.indexOf(';')
                        if (semi > 0) {
                            val kind = rest.substring(0, semi).trim().lowercase()
                            val src = rest.substring(semi + 1).trim()
                            if (src.isNotEmpty()) onMediaRequest(TerminalMediaRequest("render", kind, src, src.substringAfterLast('/')))
                        }
                    }
                }
            }
        }
    }

    private fun sendRgbOscResponse(prefix: String, argb: Int) {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val resp = String.format("\u001b]%s;rgb:%02x%02x/%02x%02x/%02x%02x\u001b\\", prefix, r, r, g, g, b, b)
        onSendResponse(resp.toByteArray(Charsets.US_ASCII))
    }

    private fun parseXColor(spec: String): Int? {
        val s = spec.trim()
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                6 -> {
                    val v = hex.toIntOrNull(16) ?: return null
                    (0xFF shl 24) or v
                }
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    TerminalStyle.trueColor(r * 17, g * 17, b * 17)
                }
                else -> null
            }
        }
        if (s.startsWith("rgb:", ignoreCase = true)) {
            val comps = s.substring(4).split('/')
            if (comps.size != 3) return null
            fun parseChannel(c: String): Int? {
                if (c.isEmpty() || c.length > 4) return null
                val raw = c.toIntOrNull(16) ?: return null
                return when (c.length) {
                    1 -> raw * 17
                    2 -> raw
                    3 -> raw ushr 4
                    else -> raw ushr 8
                }
            }
            val r = parseChannel(comps[0]) ?: return null
            val g = parseChannel(comps[1]) ?: return null
            val b = parseChannel(comps[2]) ?: return null
            return TerminalStyle.trueColor(r, g, b)
        }
        return null
    }

    private fun decodeOsc7Path(raw: String): String {
        val trimmed = raw.trim()
        val encoded = if (trimmed.startsWith("file://")) {
            val rest = trimmed.substring(7)
            val slash = rest.indexOf('/')
            if (slash < 0) return "" else rest.substring(slash)
        } else {
            trimmed
        }
        if ('%' !in encoded) return encoded
        val bytes = ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            if (encoded[i] == '%' && i + 2 < encoded.length) {
                val hi = encoded[i + 1].digitToIntOrNull(16)
                val lo = encoded[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val ch = encoded[i]
            if (ch.code < 0x80) bytes.write(ch.code) else bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }
}
