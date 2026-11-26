package com.galiais.agent.engine.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** 统一键盘特殊按键枚举（脱离任何第三方终端库）。 */
enum class TerminalKey {
    ENTER, TAB, BACKSPACE, ESCAPE,
    UP, DOWN, LEFT, RIGHT,
    INSERT, DELETE, HOME, END, PAGE_UP, PAGE_DOWN,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

/** 渲染层只读视口快照（在 engine 锁内 O(rows) 拷贝，Canvas 绘制全程无锁 120fps）。 */
class TerminalRenderSnapshot(
    val revision: Long,
    val cols: Int,
    val rows: Int,
    val totalLines: Int,
    val scrollbackSize: Int,
    val scrollbackSequence: Long,
    val firstAbsRow: Int,
    val useAltScreen: Boolean,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val cursorBlink: Boolean,
    val cursorStyleOverride: TerminalCursorStyle?,
    val progress: TerminalProgressState,
    val paletteOverrides: Map<Int, Int>,
    val dynamicFgOverride: Int?,
    val dynamicBgOverride: Int?,
    val dynamicCursorOverride: Int?,
    val inlineImages: List<TerminalInlineImage>,
    val visibleRows: Array<TerminalRow>,
    /** 亚像素丝滑滚动预取的上方 1 行（位于 `firstAbsRow - 1`），无回滚时为 null。 */
    val preTopRow: TerminalRow? = null,
    val blocks: Map<Int, TerminalCommandBlock>,
)

/**
 * Solbraid 100% 纯 Kotlin 自研终端引擎：
 * - 零 JNI、零 WebView、零第三方终端库；
 * - 封装 VT500 状态机、双屏环形缓冲、标准 xterm/VT 输入编码、SGR 1006 鼠标协议与括号粘贴；
 * - 向外暴露低开销 `frameVersion` StateFlow 驱动 Compose Canvas 按需重绘。
 */
class SolbraidTerminalEngine(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val onSendInput: (ByteArray) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    private val onBell: () -> Unit = {},
    private val onClipboardCopy: (String) -> Unit = {},
    private val onTitleChanged: (String) -> Unit = {},
    private val onProgressChanged: (TerminalProgressState) -> Unit = {},
    private val onNotification: (title: String?, body: String) -> Unit = { _, _ -> },
    private val onCwdChanged: (String) -> Unit = {},
    private val onMediaRequest: (TerminalMediaRequest) -> Unit = {},
    private val schemeProvider: () -> TerminalColorScheme = { TerminalThemes.CASCADIA_DARK },
) {
    private val lock = Any()
    private val buffer = TerminalBuffer(initialCols, initialRows)
    private val parser = Vt500Parser(
        buffer = buffer,
        onSendResponse = { resp -> onSendInput(resp) },
        onBell = onBell,
        onClipboardCopy = onClipboardCopy,
        onTitleChanged = onTitleChanged,
        onProgressChanged = onProgressChanged,
        onNotification = onNotification,
        onCwdChanged = onCwdChanged,
        onMediaRequest = onMediaRequest,
        schemeProvider = schemeProvider,
    )
    private val revCounter = AtomicLong(1L)
    private val frameVersionFlow = MutableStateFlow(1L)
    val frameVersion: StateFlow<Long> = frameVersionFlow.asStateFlow()
    @Volatile private var syncStartedAtMs: Long = 0L

    val cols: Int get() = synchronized(lock) { buffer.cols }
    val rows: Int get() = synchronized(lock) { buffer.rows }
    val isMouseTrackingEnabled: Boolean get() = synchronized(lock) { buffer.mouseTrackingMode != 0 }
    val isMouseDragTrackingEnabled: Boolean get() = synchronized(lock) { buffer.mouseTrackingMode == 1002 || buffer.mouseTrackingMode == 1003 }
    val isAlternateScreen: Boolean get() = synchronized(lock) { buffer.useAltScreen }
    val isAlternateScrollEnabled: Boolean get() = synchronized(lock) { buffer.alternateScrollMode }
    val reportedCwd: String get() = synchronized(lock) { buffer.reportedCwd }
    val totalLines: Int get() = synchronized(lock) { buffer.totalLines }
    val scrollbackSize: Int get() = synchronized(lock) { buffer.scrollbackSize }
    val lastVisibleLineText: String get() = synchronized(lock) { buffer.screenRow(buffer.cursorRow).toTrimmedString() }

    fun updateCellPixelMetrics(cellWidthPx: Int, cellHeightPx: Int) {
        buffer.cellPixelWidth = cellWidthPx.coerceAtLeast(4)
        buffer.cellPixelHeight = cellHeightPx.coerceAtLeast(8)
    }

    private fun markDirty() {
        frameVersionFlow.value = revCounter.incrementAndGet()
    }

    fun writeOutput(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length <= 0) return
        val shouldRender = synchronized(lock) {
            val wasSync = buffer.synchronizedOutput
            parser.feed(bytes, offset, length)
            val nowSync = buffer.synchronizedOutput
            if (!wasSync && nowSync) syncStartedAtMs = System.currentTimeMillis()
            if (!nowSync) {
                syncStartedAtMs = 0L
                true
            } else {
                // DECSET 2026 同步输出保护：最多缓帧 60ms 防止异常远端程序锁死画面
                val now = System.currentTimeMillis()
                if (now - syncStartedAtMs > 60L) {
                    syncStartedAtMs = now
                    true
                } else {
                    false
                }
            }
        }
        if (shouldRender) markDirty()
    }

    fun writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeOutput(bytes, 0, bytes.size)
    }

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(4, 500)
        val r = newRows.coerceIn(2, 300)
        val changed = synchronized(lock) {
            if (c == buffer.cols && r == buffer.rows) false
            else {
                buffer.resize(c, r)
                true
            }
        }
        if (changed) {
            markDirty()
            onResize(c, r)
        }
    }

    fun clearScreen() {
        synchronized(lock) {
            buffer.eraseDisplay(2)
            buffer.moveCursorTo(1, 1)
        }
        markDirty()
    }

    fun fullReset() {
        synchronized(lock) {
            parser.reset()
            buffer.fullReset()
        }
        markDirty()
    }

    /** 通知引擎用户刚提交了一条命令（用于无 OSC 133 服务器时的自动命令块划分）。 */
    fun notifyCommandSubmitted(commandLine: String) {
        synchronized(lock) { buffer.onCommandSubmitted(commandLine) }
        markDirty()
    }

    /** 读取当前视口（支持向上翻看 `scrollOffsetLines` 行历史）的无锁渲染快照。 */
    fun captureViewport(scrollOffsetLines: Int): TerminalRenderSnapshot = synchronized(lock) {
        val sb = buffer.scrollbackSize
        val clampedOffset = scrollOffsetLines.coerceIn(0, sb)
        val firstAbs = (sb - clampedOffset).coerceAtLeast(0)
        val rCount = buffer.rows
        val cCount = buffer.cols
        val vis = Array(rCount) { i ->
            val src = buffer.rowAtAbsolute(firstAbs + i)
            TerminalRow(cCount).also { dst -> if (src != null) dst.copyFrom(src) }
        }
        val preTop = if (firstAbs > 0) {
            buffer.rowAtAbsolute(firstAbs - 1)?.let { src -> TerminalRow(cCount).also { it.copyFrom(src) } }
        } else null
        val blockMap = HashMap<Int, TerminalCommandBlock>()
        for (b in buffer.getBlocksSnapshot()) blockMap[b.id] = b
        TerminalRenderSnapshot(
            revision = revCounter.get(),
            cols = cCount,
            rows = rCount,
            totalLines = buffer.totalLines,
            scrollbackSize = sb,
            scrollbackSequence = buffer.totalScrollbackAppended,
            firstAbsRow = firstAbs,
            useAltScreen = buffer.useAltScreen,
            cursorRow = buffer.cursorRow,
            cursorCol = buffer.cursorCol,
            cursorVisible = buffer.cursorVisible,
            cursorBlink = buffer.cursorBlink,
            cursorStyleOverride = buffer.cursorStyleOverride,
            progress = buffer.progressState,
            paletteOverrides = if (buffer.paletteOverrides.isEmpty()) emptyMap() else HashMap(buffer.paletteOverrides),
            dynamicFgOverride = buffer.dynamicFgOverride,
            dynamicBgOverride = buffer.dynamicBgOverride,
            dynamicCursorOverride = buffer.dynamicCursorOverride,
            inlineImages = buffer.imageStore.snapshotVisible((firstAbs - 1).coerceAtLeast(0), rCount + 1),
            visibleRows = vis,
            preTopRow = preTop,
            blocks = blockMap,
        )
    }

    fun search(query: String, options: TerminalSearchOptions = TerminalSearchOptions()): List<TerminalSearchMatch> =
        synchronized(lock) { buffer.search(query, options) }

    fun logicalLineRange(absRow: Int): IntRange = synchronized(lock) { buffer.logicalLineRange(absRow) }

    fun findPreviousPromptRow(beforeAbsRow: Int): Int? = synchronized(lock) { buffer.findPreviousPromptRow(beforeAbsRow) }

    fun findNextPromptRow(afterAbsRow: Int): Int? = synchronized(lock) { buffer.findNextPromptRow(afterAbsRow) }

    fun extractText(startAbsRow: Int, startCol: Int, endAbsRow: Int, endCol: Int): String =
        synchronized(lock) { buffer.extractText(startAbsRow, startCol, endAbsRow, endCol) }

    fun extractAllVisible(scrollOffsetLines: Int): String = synchronized(lock) {
        val sb = buffer.scrollbackSize
        val start = (sb - scrollOffsetLines.coerceIn(0, sb)).coerceAtLeast(0)
        buffer.extractText(start, 0, start + buffer.rows - 1, buffer.cols - 1)
    }

    fun extractBlockText(blockId: Int): String = synchronized(lock) { buffer.extractBlockText(blockId) }

    fun detectVisibleUrls(scrollOffsetLines: Int): List<TerminalLinkSpan> = synchronized(lock) {
        val sb = buffer.scrollbackSize
        val start = (sb - scrollOffsetLines.coerceIn(0, sb)).coerceAtLeast(0)
        buffer.detectUrls(start, start + buffer.rows - 1)
    }

    fun wordBoundsAtCol(absRow: Int, col: Int): IntRange? = synchronized(lock) {
        buffer.wordBoundsAtCol(absRow, col)
    }

    fun hyperlinkAt(absRow: Int, col: Int): String? = synchronized(lock) {
        buffer.hyperlinkAt(absRow, col)
    }

    fun exportFullTranscript(): String = synchronized(lock) {
        buffer.exportFullTranscript()
    }

    fun sendKey(key: TerminalKey, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        val seq = encodeKey(key, ctrl, alt, shift)
        if (seq.isNotEmpty()) onSendInput(seq)
    }

    fun sendChar(codePoint: Int, ctrl: Boolean = false, alt: Boolean = false) {
        val bytes = encodeChar(codePoint, ctrl, alt)
        if (bytes.isNotEmpty()) onSendInput(bytes)
    }

    /** 直接以 UTF-8 原始文本发送（用于输入法提交与 PSReadLine 预测补全，不包裹括号粘贴转义）。 */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        onSendInput(normalized.toByteArray(Charsets.UTF_8))
    }

    fun sendPaste(text: String) {
        if (text.isEmpty()) return
        // 安全过滤括号粘贴闭合注入序列，并统一 CRLF/LF 为 PTY 标准的 CR
        val safe = text
            .replace("\u001b[200~", "")
            .replace("\u001b[201~", "")
            .replace("\r\n", "\r")
            .replace('\n', '\r')
        val bracketed = synchronized(lock) { buffer.bracketedPasteMode }
        val payload = if (bracketed) "\u001b[200~$safe\u001b[201~" else safe
        onSendInput(payload.toByteArray(Charsets.UTF_8))
    }

    /** 发送 DECSET 1004 窗格焦点事件（仅在远端程序请求焦点报告时发送）。 */
    fun sendFocus(focused: Boolean) {
        val report = synchronized(lock) { buffer.focusReportingMode }
        if (report) {
            onSendInput((if (focused) "\u001b[I" else "\u001b[O").toByteArray(Charsets.US_ASCII))
        }
    }

    /** 发送 SGR 1006 鼠标事件（button: 0=左键, 1=中键, 2=右键, 64=滚轮上, 65=滚轮下）。 */
    fun sendMouse(button: Int, col1Based: Int, row1Based: Int, pressed: Boolean) {
        val (tracking, sgr) = synchronized(lock) { buffer.mouseTrackingMode to buffer.sgrMouseMode }
        if (tracking == 0) return
        val c = col1Based.coerceAtLeast(1)
        val r = row1Based.coerceAtLeast(1)
        if (sgr) {
            val suffix = if (pressed) 'M' else 'm'
            onSendInput("\u001b[<$button;$c;$r$suffix".toByteArray(Charsets.US_ASCII))
        } else if (c <= 223 && r <= 223) {
            val cb = ((if (pressed) button else 3) + 32).toByte()
            onSendInput(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), cb, (c + 32).toByte(), (r + 32).toByte()))
        }
    }

    fun encodeKey(key: TerminalKey, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): ByteArray {
        val (appCursor, kittyFlags) = synchronized(lock) { buffer.appCursorKeys to buffer.kittyKeyboardFlags }
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

        // Warp `kitty_keyboard_protocol.rs`：启用 Kitty 键盘协议时，对歧义键（如 Shift+Enter / Escape / Ctrl+Enter）输出 CSI u
        val disambiguate = (kittyFlags and 1) != 0
        val reportAll = (kittyFlags and 8) != 0
        if (disambiguate || reportAll) {
            val kittyCode = when (key) {
                TerminalKey.ENTER -> 13
                TerminalKey.TAB -> 9
                TerminalKey.BACKSPACE -> 127
                TerminalKey.ESCAPE -> 27
                else -> -1
            }
            if (kittyCode > 0) {
                val ambiguous = key == TerminalKey.ESCAPE || ctrl || alt || shift
                if (reportAll || (disambiguate && ambiguous)) {
                    val seq = if (mod > 1) "\u001b[$kittyCode;${mod}u" else "\u001b[${kittyCode}u"
                    return seq.toByteArray(Charsets.US_ASCII)
                }
            }
        }

        val str = when (key) {
            TerminalKey.ENTER -> if (alt) "\u001b\r" else "\r"
            TerminalKey.TAB -> if (shift) "\u001b[Z" else "\t"
            TerminalKey.BACKSPACE -> when {
                alt -> "\u001b\u007f"
                ctrl -> "\u0008"
                else -> "\u007f"
            }
            TerminalKey.ESCAPE -> "\u001b"
            TerminalKey.UP -> csiCursor('A', appCursor, mod)
            TerminalKey.DOWN -> csiCursor('B', appCursor, mod)
            TerminalKey.RIGHT -> csiCursor('C', appCursor, mod)
            TerminalKey.LEFT -> csiCursor('D', appCursor, mod)
            TerminalKey.HOME -> csiCursor('H', appCursor, mod)
            TerminalKey.END -> csiCursor('F', appCursor, mod)
            TerminalKey.INSERT -> csiTilde(2, mod)
            TerminalKey.DELETE -> csiTilde(3, mod)
            TerminalKey.PAGE_UP -> csiTilde(5, mod)
            TerminalKey.PAGE_DOWN -> csiTilde(6, mod)
            TerminalKey.F1 -> if (mod == 1) "\u001bOP" else "\u001b[1;${mod}P"
            TerminalKey.F2 -> if (mod == 1) "\u001bOQ" else "\u001b[1;${mod}Q"
            TerminalKey.F3 -> if (mod == 1) "\u001bOR" else "\u001b[1;${mod}R"
            TerminalKey.F4 -> if (mod == 1) "\u001bOS" else "\u001b[1;${mod}S"
            TerminalKey.F5 -> csiTilde(15, mod)
            TerminalKey.F6 -> csiTilde(17, mod)
            TerminalKey.F7 -> csiTilde(18, mod)
            TerminalKey.F8 -> csiTilde(19, mod)
            TerminalKey.F9 -> csiTilde(20, mod)
            TerminalKey.F10 -> csiTilde(21, mod)
            TerminalKey.F11 -> csiTilde(23, mod)
            TerminalKey.F12 -> csiTilde(24, mod)
        }
        return str.toByteArray(Charsets.US_ASCII)
    }

    private fun csiCursor(letter: Char, appCursor: Boolean, mod: Int): String = when {
        mod > 1 -> "\u001b[1;${mod}$letter"
        appCursor -> "\u001bO$letter"
        else -> "\u001b[$letter"
    }

    private fun csiTilde(code: Int, mod: Int): String =
        if (mod > 1) "\u001b[$code;${mod}~" else "\u001b[$code~"

    fun encodeChar(codePoint: Int, ctrl: Boolean = false, alt: Boolean = false): ByteArray {
        val kittyFlags = synchronized(lock) { buffer.kittyKeyboardFlags }
        val disambiguate = (kittyFlags and 1) != 0
        val reportAlternate = (kittyFlags and 4) != 0
        val reportAll = (kittyFlags and 8) != 0
        val reportText = (kittyFlags and 16) != 0
        if ((reportAll || (disambiguate && (ctrl || alt))) && codePoint >= 0x20) {
            val isUpperAscii = codePoint in 'A'.code..'Z'.code
            val baseCp = if (isUpperAscii) codePoint + 32 else codePoint
            val mod = 1 + (if (isUpperAscii) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            val keyPart = if (reportAlternate && isUpperAscii) "$baseCp:$codePoint" else "$baseCp"
            val textPart = if (reportText && !ctrl && !alt) ";$codePoint" else ""
            val seq = when {
                textPart.isNotEmpty() -> "\u001b[$keyPart;$mod${textPart}u"
                mod > 1 -> "\u001b[$keyPart;${mod}u"
                else -> "\u001b[${keyPart}u"
            }
            return seq.toByteArray(Charsets.US_ASCII)
        }
        if (ctrl) {
            val upper = if (codePoint in 'a'.code..'z'.code) codePoint - 32 else codePoint
            val ctrlByte = when (upper) {
                in '@'.code..'_'.code -> (upper - '@'.code)
                ' '.code, '2'.code -> 0
                '3'.code -> 0x1B
                '4'.code -> 0x1C
                '5'.code -> 0x1D
                '6'.code -> 0x1E
                '7'.code, '/'.code -> 0x1F
                '8'.code, '?'.code -> 0x7F
                else -> -1
            }
            if (ctrlByte >= 0) {
                return if (alt) byteArrayOf(0x1B, ctrlByte.toByte()) else byteArrayOf(ctrlByte.toByte())
            }
        }
        val text = String(Character.toChars(codePoint))
        val raw = text.toByteArray(Charsets.UTF_8)
        return if (alt) byteArrayOf(0x1B) + raw else raw
    }
}

/**
 * 类 PowerShell `PSReadLine` 历史预测与命令语法着色辅助器：
 * - 优先从用户近期真实执行的命令中做前缀匹配，其次回退内置运维高频命令语料库；
 * - 提供轻量语法 Token 分色（命令/Cmdlet 黄、参数灰青、路径青、字符串绿、管道符洋红）。
 */
object PsReadLinePredictor {
    enum class TokenKind { COMMAND, FLAG, STRING, OPERATOR, PATH, VARIABLE, NORMAL }
    data class HighlightToken(val start: Int, val end: Int, val kind: TokenKind)

    private val BUILTIN_CORPUS = listOf(
        "git status -sb", "git log --oneline -n 20", "git diff --stat", "git pull --rebase",
        "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'", "docker logs -f --tail 100 ",
        "docker compose up -d", "docker compose logs -f", "systemctl status ", "systemctl restart ",
        "journalctl -u ", "journalctl -n 50 --no-pager", "ss -tulpn", "df -h", "free -h", "htop",
        "tmux attach || tmux", "tmux ls", "ls -la", "find . -maxdepth 3 -name ", "rg -n ",
        "ps aux --sort=-%mem | head -n 20", "curl -sS -I ", "tar -czvf ", "tar -xzvf ",
        "kubectl get pods -A", "nvidia-smi", "uname -a && uptime", "chmod +x ", "tail -f ",
    )

    fun predict(input: String, history: List<String>): String? {
        val q = input.trimStart()
        if (q.length < 2) return null
        val lower = q.lowercase()
        history.firstOrNull { it.length > q.length && it.lowercase().startsWith(lower) }?.let { return it }
        BUILTIN_CORPUS.firstOrNull { it.length > q.length && it.lowercase().startsWith(lower) }?.let { return it }
        // 前缀无命中时回退 Warp 模糊匹配，返回最高分的真实历史命令
        return history
            .mapNotNull { cmd -> TerminalFuzzyMatcher.match(cmd, q)?.let { cmd to it.score } }
            .maxByOrNull { it.second }
            ?.first
    }

    /** 供 `Ctrl+R` 悬浮历史面板使用：按 Warp 模糊评分 + 最近度排序并返回高亮下标。 */
    fun searchHistoryFuzzy(query: String, history: List<String>, limit: Int = 50): List<Pair<String, IntArray>> {
        val pool = (history + BUILTIN_CORPUS).distinct()
        val q = query.trim()
        if (q.isEmpty()) return pool.take(limit).map { it to IntArray(0) }
        return pool.mapIndexedNotNull { idx, cmd ->
            val hit = TerminalFuzzyMatcher.match(cmd, q) ?: return@mapIndexedNotNull null
            val recencyBonus = ((pool.size - idx) * 2).coerceAtMost(80)
            Triple(cmd, hit.matchedIndices, hit.score + recencyBonus)
        }.sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }

    fun tokenize(line: String): List<HighlightToken> {
        if (line.isEmpty()) return emptyList()
        val out = ArrayList<HighlightToken>()
        var i = 0
        var expectCommand = true
        while (i < line.length) {
            if (line[i].isWhitespace()) { i++; continue }
            val start = i
            val ch = line[i]
            when {
                ch == '\'' || ch == '"' -> {
                    i++
                    while (i < line.length && line[i] != ch) {
                        if (line[i] == '\\' && ch == '"' && i + 1 < line.length) i += 2 else i++
                    }
                    if (i < line.length) i++
                    out += HighlightToken(start, i, TokenKind.STRING)
                    expectCommand = false
                }
                ch == '|' || ch == '&' || ch == ';' || ch == '>' || ch == '<' -> {
                    i++
                    if (i < line.length && (line[i] == ch || line[i] == '&')) i++
                    out += HighlightToken(start, i, TokenKind.OPERATOR)
                    expectCommand = ch == '|' || ch == '&' || ch == ';'
                }
                else -> {
                    while (i < line.length && !line[i].isWhitespace() && line[i] !in "|&;<>") i++
                    val tok = line.substring(start, i)
                    val kind = when {
                        expectCommand -> { expectCommand = false; TokenKind.COMMAND }
                        tok.startsWith("-") -> TokenKind.FLAG
                        tok.startsWith("$") -> TokenKind.VARIABLE
                        tok.startsWith("/") || tok.startsWith("./") || tok.startsWith("~/") -> TokenKind.PATH
                        else -> TokenKind.NORMAL
                    }
                    out += HighlightToken(start, i, kind)
                }
            }
        }
        return out
    }
}
