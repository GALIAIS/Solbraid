package com.galiais.agent.engine.terminal

import kotlin.math.max
import kotlin.math.min

/** 单元格搜索命中坐标（`absRow` 跨越回滚缓冲与当前屏幕）。 */
data class TerminalSearchMatch(
    val absRow: Int,
    val startCol: Int,
    val endCol: Int,
)

/** 终端全文搜索选项（参照 Warp `find.rs`：支持大小写、正则与全词匹配）。 */
data class TerminalSearchOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
)

/** 命令块语义元数据（支持 OSC 133 Shell Integration 与自动提示符启发式双通道识别）。 */
data class TerminalCommandBlock(
    val id: Int,
    var promptAbsRow: Int,
    var outputStartAbsRow: Int,
    var endAbsRow: Int,
    var commandText: String = "",
    var exitCode: Int? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    var finishedAtMs: Long? = null,
)

/** PowerShell 7 / Windows Terminal `OSC 9;4;<mode>;<percent>` 原生进度条状态。 */
data class TerminalProgressState(
    /** 0=隐藏，1=正常进度，2=错误，3=不确定脉冲，4=警告/暂停。 */
    val mode: Int = 0,
    val percent: Int = 0,
) {
    val active: Boolean get() = mode in 1..4
}

enum class TerminalLinkKind { URL, OSC8, FILE_PATH }

/** 单个可点击终端超链接（支持 HTTP URL、OSC 8 显式链接以及带行列号的文件路径）。 */
data class TerminalLinkSpan(
    val absRow: Int,
    val startCol: Int,
    val endCol: Int,
    val url: String,
    val kind: TerminalLinkKind = TerminalLinkKind.URL,
    val fileLine: Int? = null,
    val fileCol: Int? = null,
)

/**
 * 有界 OSC 8 超链接驻留池（直接参照 Warp `hyperlink_registry.rs`）：
 * 每个单元格只存 4 字节 Int ID，防止 `ls --hyperlink=auto` / `rg` 海量链接导致内存膨胀。
 */
class HyperlinkRegistry(
    private val maxDistinctEntries: Int = 4096,
    private val maxUriBytes: Int = 2048,
) {
    private val byUri = HashMap<String, Int>(64)
    private val byId = ArrayList<String>(64)

    fun intern(uri: String): Int {
        if (uri.isEmpty() || uri.toByteArray(Charsets.UTF_8).size > maxUriBytes) return 0
        byUri[uri]?.let { return it }
        if (byId.size >= maxDistinctEntries) return 0
        val id = byId.size + 1
        byId.add(uri)
        byUri[uri] = id
        return id
    }

    fun get(id: Int): String? = if (id in 1..byId.size) byId[id - 1] else null

    fun clear() {
        byUri.clear()
        byId.clear()
    }

    val size: Int get() = byId.size
}

/** 紧凑无装箱字符行（纯原生 IntArray + 稀疏组合字符/OSC 8 ID/彩色下划线表，120fps 渲染零 GC 抖动）。 */
class TerminalRow(cols: Int) {
    var cols: Int = cols
        private set
    var chars: IntArray = IntArray(cols) { ' '.code }
    var fg: IntArray = IntArray(cols)
    var bg: IntArray = IntArray(cols)
    var flags: IntArray = IntArray(cols)
    /** 稀疏组合字符/变体选择符/ZWJ 扩展串（仅含零宽附加码点时按列分配）。 */
    var clusters: MutableMap<Int, String>? = null
    /** 稀疏 OSC 8 显式超链接驻留 ID 表（列号 -> HyperlinkRegistry ID）。 */
    var hyperlinkIds: MutableMap<Int, Int>? = null
    /** 稀疏彩色下划线颜色表（SGR 58，列号 -> 编码色值）。 */
    var underlineColors: MutableMap<Int, Int>? = null
    /** 当前行是否因行宽满自动软折行延续至下一行（复制与 Reflow 时保留连续性）。 */
    var softWrapped: Boolean = false
    /** 所属命令块 ID（0 表示无块）。 */
    var blockId: Int = 0
    /** 该行是否为命令起始提示符行（用于静默跳转上一/下一命令，不绘制分割线）。 */
    var isCommandHeader: Boolean = false

    fun clear(fillFg: Int = 0, fillBg: Int = 0, fillFlags: Int = 0) {
        chars.fill(' '.code)
        fg.fill(fillFg)
        bg.fill(fillBg)
        flags.fill(fillFlags)
        clusters?.clear()
        hyperlinkIds?.clear()
        underlineColors?.clear()
        softWrapped = false
        blockId = 0
        isCommandHeader = false
    }

    fun clearRange(fromCol: Int, toColExclusive: Int, fillFg: Int = 0, fillBg: Int = 0) {
        var s = fromCol.coerceIn(0, cols)
        var e = toColExclusive.coerceIn(s, cols)
        // 若擦除边界切开双宽字符，一并清理相邻残余半格防止花屏
        if (s > 0 && s < cols && (flags[s] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) s--
        if (e in 1 until cols && (flags[e] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) e++
        for (i in s until e) {
            chars[i] = ' '.code
            fg[i] = fillFg
            bg[i] = fillBg
            flags[i] = 0
            clusters?.remove(i)
            hyperlinkIds?.remove(i)
            underlineColors?.remove(i)
        }
    }

    fun resize(newCols: Int) {
        if (newCols == cols) return
        val copyLen = min(cols, newCols)
        chars = IntArray(newCols) { i -> if (i < copyLen) chars[i] else ' '.code }
        fg = IntArray(newCols) { i -> if (i < copyLen) fg[i] else 0 }
        bg = IntArray(newCols) { i -> if (i < copyLen) bg[i] else 0 }
        flags = IntArray(newCols) { i -> if (i < copyLen) flags[i] else 0 }
        // 若截断后最后一格变成孤立宽字符头，清空它避免渲染越界
        if (newCols > 0 && (flags[newCols - 1] and TerminalStyle.FLAG_WIDE_LEAD) != 0) {
            chars[newCols - 1] = ' '.code
            flags[newCols - 1] = 0
            clusters?.remove(newCols - 1)
        }
        clusters?.entries?.removeIf { it.key >= newCols }
        hyperlinkIds?.entries?.removeIf { it.key >= newCols }
        underlineColors?.entries?.removeIf { it.key >= newCols }
        cols = newCols
    }

    fun copyFrom(src: TerminalRow) {
        if (cols != src.cols) resize(src.cols)
        src.chars.copyInto(chars)
        src.fg.copyInto(fg)
        src.bg.copyInto(bg)
        src.flags.copyInto(flags)
        val sc = src.clusters
        if (sc.isNullOrEmpty()) {
            clusters?.clear()
        } else {
            val dst = clusters ?: HashMap<Int, String>(sc.size).also { clusters = it }
            dst.clear()
            dst.putAll(sc)
        }
        val sh = src.hyperlinkIds
        if (sh.isNullOrEmpty()) {
            hyperlinkIds?.clear()
        } else {
            val dst = hyperlinkIds ?: HashMap<Int, Int>(sh.size).also { hyperlinkIds = it }
            dst.clear()
            dst.putAll(sh)
        }
        val su = src.underlineColors
        if (su.isNullOrEmpty()) {
            underlineColors?.clear()
        } else {
            val dst = underlineColors ?: HashMap<Int, Int>(su.size).also { underlineColors = it }
            dst.clear()
            dst.putAll(su)
        }
        softWrapped = src.softWrapped
        blockId = src.blockId
        isCommandHeader = src.isCommandHeader
    }

    fun glyphAt(col: Int): String {
        val cp = chars[col]
        val base = safeCodePointString(cp)
        val extra = clusters?.get(col)
        return if (extra.isNullOrEmpty()) base else base + extra
    }

    fun lastNonBlankCol(): Int {
        var last = cols - 1
        while (last >= 0 && (chars[last] == ' '.code || chars[last] == 0) &&
            (flags[last] and TerminalStyle.FLAG_WIDE_CONTINUATION) == 0 &&
            clusters?.containsKey(last) != true
        ) {
            last--
        }
        return last
    }

    /** 返回去尾空行文本以及每个 UTF-16 字符对应的终端起/止列映射（彻底解决 CJK/Emoji 列偏移）。 */
    fun toStringWithColMap(): Triple<String, IntArray, IntArray> {
        val last = lastNonBlankCol()
        if (last < 0) return Triple("", IntArray(0), IntArray(0))
        val sb = StringBuilder(last + 8)
        val startCols = ArrayList<Int>(last + 8)
        val endCols = ArrayList<Int>(last + 8)
        var i = 0
        while (i <= last) {
            if ((flags[i] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) { i++; continue }
            val isWide = (flags[i] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && i + 1 < cols
            val colEnd = if (isWide) i + 1 else i
            val glyph = glyphAt(i)
            sb.append(glyph)
            repeat(glyph.length) {
                startCols.add(i)
                endCols.add(colEnd)
            }
            i += if (isWide) 2 else 1
        }
        val sArr = IntArray(startCols.size) { startCols[it] }
        val eArr = IntArray(endCols.size) { endCols[it] }
        return Triple(sb.toString(), sArr, eArr)
    }

    fun toTrimmedString(): String {
        val last = lastNonBlankCol()
        if (last < 0) return ""
        val sb = StringBuilder(last + 4)
        for (i in 0..last) {
            if ((flags[i] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) continue
            val cp = chars[i]
            if (cp > 0) sb.append(glyphAt(i))
        }
        return sb.toString()
    }

    fun substringCols(fromCol: Int, toColInclusive: Int): String {
        var s = fromCol.coerceIn(0, (cols - 1).coerceAtLeast(0))
        val e = toColInclusive.coerceIn(s, (cols - 1).coerceAtLeast(0))
        if (s > 0 && (flags[s] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) s--
        val sb = StringBuilder(e - s + 4)
        for (i in s..e) {
            if ((flags[i] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) continue
            val cp = chars[i]
            if (cp <= 0) sb.append(' ') else sb.append(glyphAt(i))
        }
        return sb.toString().trimEnd()
    }

    companion object {
        fun safeCodePointString(cp: Int): String {
            if (cp <= 0) return " "
            if (!Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF) return "\uFFFD"
            return if (cp < 0x10000) cp.toChar().toString() else String(Character.toChars(cp))
        }
    }
}

/** 精确 Unicode 终端列宽计算（区分 0 宽组合音标、2 宽 CJK/全角/BMP+SMP Emoji 与 1 宽 ASCII/Powerline/Nerd Font）。 */
object UnicodeCellWidth {
    fun widthOf(cp: Int): Int {
        if (cp < 0x20 || cp == 0x7F) return 0
        if (cp < 0x7F) return 1
        // Nerd Font / Powerline 私有区图标全部按单宽渲染，避免提示符错位
        if (cp in 0xE000..0xF8FF) return 1
        // 零宽控制、ZWJ 与变体选择符
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return 0
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) return 0
        // 常见 East Asian Wide / Fullwidth 与默认宽展示 Emoji 区块（窄符号如 ✓ ✗ ⚙ ⚠ ☁ 保持单宽，遇 VS16 \uFE0F 动态升宽）
        return if (
            cp in 0x1100..0x115F ||
            cp in 0x231A..0x231B ||
            cp in 0x2329..0x232A ||
            cp in 0x23E9..0x23F3 ||
            cp in 0x23F8..0x23FA ||
            cp in 0x25FD..0x25FE ||
            cp == 0x2614 || cp == 0x2615 ||
            cp in 0x2648..0x2653 ||
            cp == 0x267F || cp == 0x2693 || cp == 0x26A1 ||
            cp in 0x26AA..0x26AB ||
            cp in 0x26BD..0x26BE ||
            cp in 0x26C4..0x26C5 ||
            cp == 0x26CE || cp == 0x26D4 || cp == 0x26EA ||
            cp in 0x26F2..0x26F3 || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD ||
            cp == 0x2705 || cp in 0x270A..0x270B || cp == 0x2728 ||
            cp == 0x274C || cp == 0x274E || cp in 0x2753..0x2755 || cp == 0x2757 ||
            cp in 0x2795..0x2797 || cp == 0x27B0 || cp == 0x27BF ||
            cp == 0x2B50 || cp == 0x2B55 ||
            cp in 0x2E80..0x303E ||
            cp in 0x3040..0xA4CF ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE19 ||
            cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1FAFF ||
            cp in 0x20000..0x3FFFD
        ) 2 else 1
    }

    /** 对应 Warp `ansi_handler.rs`：单宽符号遇到 Emoji 变体选择符 U+FE0F 时是否动态升宽为 2 列。 */
    fun canUpgradeWithVs16(cp: Int): Boolean =
        cp in 0x2300..0x27BF || cp in 0x2900..0x2BFF || cp == 0x00A9 || cp == 0x00AE ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
}

/**
 * 完整终端双屏缓冲（主屏 + 备用屏 + 10,000 行环形回滚 + Reflow + 命令块追踪 + 全文搜索）：
 * - 全部状态变更由 `SolbraidTerminalEngine` 的单一锁串行保护；
 * - 渲染器读取轻量快照（`ViewportSnapshot`）实现无锁 120fps 绘制。
 */
class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val maxScrollback: Int = 10_000,
) {
    var cols: Int = initialCols.coerceIn(4, 500)
        private set
    var rows: Int = initialRows.coerceIn(2, 300)
        private set

    // 主屏 + 历史回滚缓冲
    private val scrollback = ArrayDeque<TerminalRow>(256)
    private var primaryScreen = Array(rows) { TerminalRow(cols) }
    private var altScreen = Array(rows) { TerminalRow(cols) }
    var useAltScreen: Boolean = false
        private set

    private val activeScreen: Array<TerminalRow>
        get() = if (useAltScreen) altScreen else primaryScreen

    // 光标与卷动区域
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var wrapPending: Boolean = false
    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1
    var cursorVisible: Boolean = true
    var cursorStyleOverride: TerminalCursorStyle? = null
    var cursorBlink: Boolean = true

    // 当前笔刷样式
    var curFg: Int = TerminalStyle.COLOR_DEFAULT
    var curBg: Int = TerminalStyle.COLOR_DEFAULT
    var curUnderlineColor: Int = TerminalStyle.COLOR_DEFAULT
    var curFlags: Int = 0

    // 终端私有模式状态（供输入编码、DECRQM 与鼠标协议实时读取）
    var appCursorKeys: Boolean = false
    var appKeypad: Boolean = false
    var autoWrapMode: Boolean = true
    var originMode: Boolean = false
    var insertMode: Boolean = false
    var lineFeedNewLineMode: Boolean = false
    var alternateScrollMode: Boolean = true
    var bracketedPasteMode: Boolean = false
    var focusReportingMode: Boolean = false
    var mouseTrackingMode: Int = 0 // 0=off, 1000=normal, 1002=button, 1003=any
    var sgrMouseMode: Boolean = false
    var synchronizedOutput: Boolean = false
    var g0GraphicsCharset: Boolean = false
    var g1GraphicsCharset: Boolean = false
    var useG1Charset: Boolean = false
    var activeHyperlinkId: Int = 0
    val hyperlinkRegistry = HyperlinkRegistry()
    val imageStore = TerminalImageStore()
    var windowTitle: String = ""
    private val titleStack = ArrayDeque<String>(8)
    var reportedCwd: String = ""
    var progressState: TerminalProgressState = TerminalProgressState()

    // 会话级动态调色板与前/背景/光标覆写（OSC 4/104, OSC 10/11/12/110/111/112）
    val paletteOverrides = HashMap<Int, Int>()
    var dynamicFgOverride: Int? = null
    var dynamicBgOverride: Int? = null
    var dynamicCursorOverride: Int? = null

    // 供 CSI 14t / 16t 像素尺寸查询上报的单元格像素宽高
    @Volatile var cellPixelWidth: Int = 18
    @Volatile var cellPixelHeight: Int = 36

    fun pushTitle() {
        if (titleStack.size >= 16) titleStack.removeFirst()
        titleStack.addLast(windowTitle)
    }

    fun popTitle(): String? {
        val prev = titleStack.removeLastOrNull() ?: return null
        windowTitle = prev
        return prev
    }

    fun setActiveHyperlink(uri: String?) {
        activeHyperlinkId = if (uri.isNullOrEmpty()) 0 else hyperlinkRegistry.intern(uri)
    }

    // Kitty 键盘增强协议模式栈（主/备屏独立维护，深度上限 16，与 Warp `grid_handler.rs` 一致）
    private val primaryKittyStack = ArrayDeque<Int>(8)
    private val altKittyStack = ArrayDeque<Int>(8)
    private var primaryKittyFlags: Int = 0
    private var altKittyFlags: Int = 0
    val kittyKeyboardFlags: Int get() = if (useAltScreen) altKittyFlags else primaryKittyFlags

    fun setKittyKeyboardMode(flags: Int, applyMode: Int) {
        val masked = flags and 0x1F
        val cur = kittyKeyboardFlags
        val next = when (applyMode) {
            1 -> masked
            2 -> cur or masked
            3 -> cur and masked.inv()
            else -> return
        }
        if (useAltScreen) altKittyFlags = next else primaryKittyFlags = next
    }

    fun pushKittyKeyboardMode(flags: Int) {
        val stack = if (useAltScreen) altKittyStack else primaryKittyStack
        val masked = flags and 0x1F
        if (stack.size >= 16) stack.removeFirst()
        stack.addLast(masked)
        if (useAltScreen) altKittyFlags = masked else primaryKittyFlags = masked
    }

    fun popKittyKeyboardModes(count: Int) {
        val stack = if (useAltScreen) altKittyStack else primaryKittyStack
        repeat(count.coerceAtLeast(1).coerceAtMost(stack.size)) { stack.removeLast() }
        val top = stack.lastOrNull() ?: 0
        if (useAltScreen) altKittyFlags = top else primaryKittyFlags = top
    }

    // DECSC/DECRC 光标保存槽（主/备屏各一套）
    private data class SavedCursor(
        var row: Int = 0,
        var col: Int = 0,
        var fg: Int = 0,
        var bg: Int = 0,
        var underlineColor: Int = 0,
        var flags: Int = 0,
        var origin: Boolean = false,
    )
    private val primarySaved = SavedCursor()
    private val altSaved = SavedCursor()

    // Tab stops
    private var tabStops = BooleanArray(cols) { it % 8 == 0 }

    // 语义命令块追踪（OSC 133 + 自动提示符启发式）
    private var nextBlockId = 1
    private val blocks = ArrayDeque<TerminalCommandBlock>(64)
    private var activeBlock: TerminalCommandBlock? = null
    private var hasSeenOsc133: Boolean = false
    /** 累计写入回滚区的历史总行数（单调递增，用于画布向上翻阅时精准锚定阅读位置）。 */
    var totalScrollbackAppended: Long = 0L
        private set

    val scrollbackSize: Int get() = if (useAltScreen) 0 else scrollback.size
    val totalLines: Int get() = scrollbackSize + rows

    fun rowAtAbsolute(absRow: Int): TerminalRow? {
        val sb = scrollbackSize
        return if (absRow < sb) scrollback.getOrNull(absRow)
        else activeScreen.getOrNull(absRow - sb)
    }

    fun screenRow(r: Int): TerminalRow = activeScreen[r.coerceIn(0, rows - 1)]

    fun absoluteRowOfScreen(screenRow: Int): Int = scrollbackSize + screenRow.coerceIn(0, rows - 1)

    fun getBlocksSnapshot(): List<TerminalCommandBlock> = blocks.toList()

    fun saveCursor() {
        val slot = if (useAltScreen) altSaved else primarySaved
        slot.row = cursorRow; slot.col = cursorCol
        slot.fg = curFg; slot.bg = curBg; slot.underlineColor = curUnderlineColor
        slot.flags = curFlags; slot.origin = originMode
    }

    fun restoreCursor() {
        val slot = if (useAltScreen) altSaved else primarySaved
        cursorRow = slot.row.coerceIn(0, rows - 1)
        cursorCol = slot.col.coerceIn(0, cols - 1)
        curFg = slot.fg; curBg = slot.bg; curUnderlineColor = slot.underlineColor
        curFlags = slot.flags; originMode = slot.origin
        wrapPending = false
    }

    fun resetAttributes() {
        curFg = TerminalStyle.COLOR_DEFAULT
        curBg = TerminalStyle.COLOR_DEFAULT
        curUnderlineColor = TerminalStyle.COLOR_DEFAULT
        curFlags = 0
    }

    fun fullReset() {
        if (useAltScreen) switchToPrimaryScreen(restore = false)
        scrollback.clear()
        primaryScreen.forEach { it.clear() }
        altScreen.forEach { it.clear() }
        cursorRow = 0; cursorCol = 0; wrapPending = false
        scrollTop = 0; scrollBottom = rows - 1
        cursorVisible = true; cursorBlink = true; cursorStyleOverride = null
        appCursorKeys = false; appKeypad = false; autoWrapMode = true
        originMode = false; insertMode = false; lineFeedNewLineMode = false; alternateScrollMode = true
        bracketedPasteMode = false
        focusReportingMode = false; mouseTrackingMode = 0; sgrMouseMode = false
        synchronizedOutput = false; g0GraphicsCharset = false; g1GraphicsCharset = false; useG1Charset = false
        activeHyperlinkId = 0; hyperlinkRegistry.clear(); imageStore.clear()
        titleStack.clear(); hasSeenOsc133 = false
        progressState = TerminalProgressState()
        paletteOverrides.clear()
        dynamicFgOverride = null; dynamicBgOverride = null; dynamicCursorOverride = null
        primaryKittyStack.clear(); altKittyStack.clear()
        primaryKittyFlags = 0; altKittyFlags = 0
        resetAttributes()
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        blocks.clear(); activeBlock = null
    }

    fun switchToAlternateScreen(save: Boolean, clearAlt: Boolean) {
        if (save) saveCursor()
        if (!useAltScreen) {
            useAltScreen = true
            if (clearAlt) altScreen.forEach { it.clear() }
            scrollTop = 0; scrollBottom = rows - 1
            cursorRow = 0; cursorCol = 0; wrapPending = false
        }
    }

    fun switchToPrimaryScreen(restore: Boolean) {
        if (useAltScreen) {
            useAltScreen = false
            scrollTop = 0; scrollBottom = rows - 1
            if (restore) restoreCursor()
        }
    }

    fun putCodePoint(cp: Int) {
        if (!Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF) return
        val graphics = if (useG1Charset) g1GraphicsCharset else g0GraphicsCharset
        val mapped = if (graphics) mapDecGraphics(cp) else cp
        val w = UnicodeCellWidth.widthOf(mapped)
        if (w == 0) {
            // 将零宽组合音标、变体选择符(\uFE0F)、ZWJ 附加到前一个有效格；遇 VS16 参照 Warp 动态将单宽符号升级为双宽 Emoji
            if (mapped >= 0x20 && mapped != 0x7F && mapped != 0xFEFF) {
                val row = activeScreen[cursorRow]
                var prev = if (wrapPending) cursorCol else cursorCol - 1
                if (prev >= 0 && (row.flags[prev] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) prev--
                if (prev in 0 until cols && row.chars[prev] > 0) {
                    val map = row.clusters ?: HashMap<Int, String>(4).also { row.clusters = it }
                    val cur = map[prev].orEmpty()
                    if (cur.length < 16) {
                        map[prev] = cur + TerminalRow.safeCodePointString(mapped)
                    }
                    if (mapped == 0xFE0F &&
                        (row.flags[prev] and TerminalStyle.FLAG_WIDE_LEAD) == 0 &&
                        UnicodeCellWidth.canUpgradeWithVs16(row.chars[prev]) &&
                        prev + 1 < cols
                    ) {
                        val next = prev + 1
                        if ((row.flags[next] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && next + 1 < cols) {
                            row.chars[next + 1] = ' '.code; row.flags[next + 1] = 0; row.clusters?.remove(next + 1)
                        }
                        row.flags[prev] = row.flags[prev] or TerminalStyle.FLAG_WIDE_LEAD
                        row.chars[next] = 0
                        row.fg[next] = row.fg[prev]
                        row.bg[next] = row.bg[prev]
                        row.flags[next] = (row.flags[prev] and TerminalStyle.FLAG_WIDE_LEAD.inv()) or TerminalStyle.FLAG_WIDE_CONTINUATION
                        row.clusters?.remove(next)
                        if (cursorCol == next) {
                            if (next + 1 >= cols) {
                                cursorCol = cols - 1
                                wrapPending = true
                            } else {
                                cursorCol = next + 1
                            }
                        }
                    }
                }
            }
            return
        }

        if (wrapPending) {
            if (autoWrapMode) {
                activeScreen[cursorRow].softWrapped = true
                cursorCol = 0
                lineFeed()
            }
            wrapPending = false
        }

        if (w == 2 && cursorCol == cols - 1) {
            // 宽字符落在最后一列：自动换行
            if (autoWrapMode) {
                activeScreen[cursorRow].clearRange(cursorCol, cols, curFg, curBg)
                activeScreen[cursorRow].softWrapped = true
                cursorCol = 0
                lineFeed()
            } else {
                return
            }
        }

        val row = activeScreen[cursorRow]
        if (insertMode) insertBlankChars(w)

        // 若覆写到已有宽字符的另一半，清掉残余半格防止花屏
        if ((row.flags[cursorCol] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0 && cursorCol > 0) {
            row.chars[cursorCol - 1] = ' '.code; row.flags[cursorCol - 1] = 0; row.clusters?.remove(cursorCol - 1)
        }
        if ((row.flags[cursorCol] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && cursorCol + 1 < cols) {
            row.chars[cursorCol + 1] = ' '.code; row.flags[cursorCol + 1] = 0; row.clusters?.remove(cursorCol + 1)
        }

        row.chars[cursorCol] = mapped
        row.fg[cursorCol] = curFg
        row.bg[cursorCol] = curBg
        row.flags[cursorCol] = curFlags or (if (w == 2) TerminalStyle.FLAG_WIDE_LEAD else 0)
        row.clusters?.remove(cursorCol)
        val linkId = activeHyperlinkId
        if (linkId != 0) {
            (row.hyperlinkIds ?: HashMap<Int, Int>(4).also { row.hyperlinkIds = it })[cursorCol] = linkId
        } else {
            row.hyperlinkIds?.remove(cursorCol)
        }
        val uColor = curUnderlineColor
        if (uColor != TerminalStyle.COLOR_DEFAULT) {
            (row.underlineColors ?: HashMap<Int, Int>(4).also { row.underlineColors = it })[cursorCol] = uColor
        } else {
            row.underlineColors?.remove(cursorCol)
        }
        if (activeBlock != null && !useAltScreen) row.blockId = activeBlock!!.id

        if (w == 2 && cursorCol + 1 < cols) {
            val next = cursorCol + 1
            if ((row.flags[next] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && next + 1 < cols) {
                row.chars[next + 1] = ' '.code; row.flags[next + 1] = 0; row.clusters?.remove(next + 1)
            }
            row.chars[next] = 0
            row.fg[next] = curFg
            row.bg[next] = curBg
            row.flags[next] = curFlags or TerminalStyle.FLAG_WIDE_CONTINUATION
            row.clusters?.remove(next)
            if (linkId != 0) {
                (row.hyperlinkIds ?: HashMap<Int, Int>(4).also { row.hyperlinkIds = it })[next] = linkId
            } else {
                row.hyperlinkIds?.remove(next)
            }
            if (uColor != TerminalStyle.COLOR_DEFAULT) {
                (row.underlineColors ?: HashMap<Int, Int>(4).also { row.underlineColors = it })[next] = uColor
            } else {
                row.underlineColors?.remove(next)
            }
        }

        val nextCol = cursorCol + w
        if (nextCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        } else {
            cursorCol = nextCol
        }
    }

    fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    fun backspace() {
        wrapPending = false
        if (cursorCol > 0) cursorCol--
    }

    fun tab(count: Int = 1) {
        wrapPending = false
        repeat(count.coerceAtLeast(1)) {
            var next = cursorCol + 1
            while (next < cols - 1 && !tabStops[next]) next++
            cursorCol = next.coerceAtMost(cols - 1)
        }
    }

    fun backTab(count: Int = 1) {
        wrapPending = false
        repeat(count.coerceAtLeast(1)) {
            var prev = cursorCol - 1
            while (prev > 0 && !tabStops[prev]) prev--
            cursorCol = prev.coerceAtLeast(0)
        }
    }

    fun setTabStop() {
        if (cursorCol in tabStops.indices) tabStops[cursorCol] = true
    }

    fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> if (cursorCol in tabStops.indices) tabStops[cursorCol] = false
            3 -> tabStops.fill(false)
        }
    }

    fun lineFeed(applyLnm: Boolean = false) {
        wrapPending = false
        if (applyLnm && lineFeedNewLineMode) cursorCol = 0
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        activeBlock?.let { blk ->
            if (!useAltScreen) blk.endAbsRow = max(blk.endAbsRow, absoluteRowOfScreen(cursorRow))
        }
    }

    fun reverseIndex() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun pushRowToScrollback(src: TerminalRow) {
        val evicted = TerminalRow(cols).apply { copyFrom(src) }
        scrollback.addLast(evicted)
        totalScrollbackAppended++
        if (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
            imageStore.shiftRowsUp(1)
            for (b in blocks) {
                b.promptAbsRow = (b.promptAbsRow - 1).coerceAtLeast(0)
                b.outputStartAbsRow = (b.outputStartAbsRow - 1).coerceAtLeast(0)
                b.endAbsRow = (b.endAbsRow - 1).coerceAtLeast(0)
            }
            while (blocks.isNotEmpty() && blocks.first().endAbsRow <= 0) {
                val dropped = blocks.removeFirst()
                if (activeBlock?.id == dropped.id) activeBlock = null
            }
        }
    }

    fun scrollUp(lines: Int, pushToHistoryOverride: Boolean? = null) {
        val n = lines.coerceIn(1, scrollBottom - scrollTop + 1)
        val scr = activeScreen
        val pushToHistory = pushToHistoryOverride ?: (!useAltScreen && scrollTop == 0 && scrollBottom == rows - 1)
        for (i in 0 until n) {
            if (pushToHistory) {
                pushRowToScrollback(scr[i])
            }
        }
        for (r in scrollTop..(scrollBottom - n)) {
            scr[r].copyFrom(scr[r + n])
        }
        for (r in (scrollBottom - n + 1)..scrollBottom) {
            scr[r].clear(curFg, curBg)
        }
    }

    fun scrollDown(lines: Int) {
        val n = lines.coerceIn(1, scrollBottom - scrollTop + 1)
        val scr = activeScreen
        for (r in scrollBottom downTo (scrollTop + n)) {
            scr[r].copyFrom(scr[r - n])
        }
        for (r in scrollTop until (scrollTop + n)) {
            scr[r].clear(curFg, curBg)
        }
    }

    fun setScrollRegion(top1Based: Int, bottom1Based: Int) {
        val t = (top1Based - 1).coerceIn(0, rows - 1)
        val b = (bottom1Based - 1).coerceIn(0, rows - 1)
        if (t < b) {
            scrollTop = t
            scrollBottom = b
            cursorRow = if (originMode) scrollTop else 0
            cursorCol = 0
            wrapPending = false
        }
    }

    fun moveCursorTo(row1Based: Int, col1Based: Int) {
        wrapPending = false
        val baseRow = if (originMode) scrollTop else 0
        val maxRow = if (originMode) scrollBottom else rows - 1
        cursorRow = (baseRow + row1Based - 1).coerceIn(baseRow, maxRow)
        cursorCol = (col1Based - 1).coerceIn(0, cols - 1)
    }

    fun moveCursorRelative(dRow: Int, dCol: Int) {
        wrapPending = false
        val minRow = if (originMode) scrollTop else 0
        val maxRow = if (originMode) scrollBottom else rows - 1
        cursorRow = (cursorRow + dRow).coerceIn(minRow, maxRow)
        cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
    }

    fun eraseDisplay(mode: Int) {
        val scr = activeScreen
        when (mode) {
            0 -> {
                scr[cursorRow].clearRange(cursorCol, cols, curFg, curBg)
                for (r in (cursorRow + 1) until rows) scr[r].clear(curFg, curBg)
            }
            1 -> {
                for (r in 0 until cursorRow) scr[r].clear(curFg, curBg)
                scr[cursorRow].clearRange(0, cursorCol + 1, curFg, curBg)
            }
            2 -> {
                if (!useAltScreen) {
                    // 清屏前把非空行推入回滚区（模拟 Windows Terminal / xterm 行为，不丢输出）
                    var lastNonEmpty = rows - 1
                    while (lastNonEmpty >= 0 && scr[lastNonEmpty].lastNonBlankCol() < 0) lastNonEmpty--
                    for (r in 0..lastNonEmpty) {
                        pushRowToScrollback(scr[r])
                    }
                }
                for (r in 0 until rows) scr[r].clear(curFg, curBg)
            }
            3 -> {
                if (!useAltScreen) { scrollback.clear(); blocks.clear(); activeBlock = null; imageStore.clear() }
                for (r in 0 until rows) scr[r].clear(curFg, curBg)
            }
        }
    }

    fun eraseLine(mode: Int) {
        val row = activeScreen[cursorRow]
        when (mode) {
            0 -> row.clearRange(cursorCol, cols, curFg, curBg)
            1 -> row.clearRange(0, cursorCol + 1, curFg, curBg)
            2 -> row.clear(curFg, curBg)
        }
    }

    fun insertBlankChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val row = activeScreen[cursorRow]
        if (cursorCol > 0 && (row.flags[cursorCol] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) {
            row.clearRange(cursorCol - 1, cursorCol + 1, curFg, curBg)
        }
        for (c in (cols - 1) downTo (cursorCol + count)) {
            row.chars[c] = row.chars[c - count]
            row.fg[c] = row.fg[c - count]
            row.bg[c] = row.bg[c - count]
            row.flags[c] = row.flags[c - count]
            val cl = row.clusters?.remove(c - count)
            if (cl != null) (row.clusters ?: HashMap<Int, String>().also { row.clusters = it })[c] = cl else row.clusters?.remove(c)
            val hl = row.hyperlinkIds?.remove(c - count)
            if (hl != null) (row.hyperlinkIds ?: HashMap<Int, Int>().also { row.hyperlinkIds = it })[c] = hl else row.hyperlinkIds?.remove(c)
            val uc = row.underlineColors?.remove(c - count)
            if (uc != null) (row.underlineColors ?: HashMap<Int, Int>().also { row.underlineColors = it })[c] = uc else row.underlineColors?.remove(c)
        }
        if ((row.flags[cols - 1] and TerminalStyle.FLAG_WIDE_LEAD) != 0) {
            row.clearRange(cols - 1, cols, curFg, curBg)
        }
        row.clearRange(cursorCol, cursorCol + count, curFg, curBg)
    }

    fun deleteChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val row = activeScreen[cursorRow]
        if (cursorCol > 0 && (row.flags[cursorCol] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) {
            row.clearRange(cursorCol - 1, cursorCol + 1, curFg, curBg)
        }
        for (c in cursorCol until (cols - count)) {
            row.chars[c] = row.chars[c + count]
            row.fg[c] = row.fg[c + count]
            row.bg[c] = row.bg[c + count]
            row.flags[c] = row.flags[c + count]
            val cl = row.clusters?.remove(c + count)
            if (cl != null) (row.clusters ?: HashMap<Int, String>().also { row.clusters = it })[c] = cl else row.clusters?.remove(c)
            val hl = row.hyperlinkIds?.remove(c + count)
            if (hl != null) (row.hyperlinkIds ?: HashMap<Int, Int>().also { row.hyperlinkIds = it })[c] = hl else row.hyperlinkIds?.remove(c)
            val uc = row.underlineColors?.remove(c + count)
            if (uc != null) (row.underlineColors ?: HashMap<Int, Int>().also { row.underlineColors = it })[c] = uc else row.underlineColors?.remove(c)
        }
        if (cursorCol < cols - count && (row.flags[cursorCol] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) {
            row.clearRange(cursorCol, cursorCol + 1, curFg, curBg)
        }
        row.clearRange(cols - count, cols, curFg, curBg)
    }

    fun eraseChars(n: Int) {
        activeScreen[cursorRow].clearRange(cursorCol, (cursorCol + n).coerceAtMost(cols), curFg, curBg)
    }

    fun insertLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        cursorCol = 0
        wrapPending = false
        val savedTop = scrollTop
        scrollTop = cursorRow
        scrollDown(n)
        scrollTop = savedTop
    }

    fun deleteLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        cursorCol = 0
        wrapPending = false
        val savedTop = scrollTop
        scrollTop = cursorRow
        // CSI M (DL) 绝不能把被删行推入历史回滚区
        scrollUp(n, pushToHistoryOverride = false)
        scrollTop = savedTop
    }

    /** OSC 133 / 自动回车语义命令块标记（自动回溯软折行首行；若远端原生支持 OSC 133 则不重复建块）。 */
    fun onCommandSubmitted(commandLine: String) {
        if (useAltScreen || hasSeenOsc133) return
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return
        val absRow = absoluteRowOfScreen(cursorRow)
        var startAbs = absRow
        while (startAbs > 0 && rowAtAbsolute(startAbs - 1)?.softWrapped == true) {
            startAbs--
        }
        val blk = TerminalCommandBlock(
            id = nextBlockId++,
            promptAbsRow = startAbs,
            outputStartAbsRow = absRow + 1,
            endAbsRow = absRow,
            commandText = trimmed,
        )
        for (r in startAbs..absRow) {
            rowAtAbsolute(r)?.apply {
                blockId = blk.id
                if (r == startAbs) isCommandHeader = true
            }
        }
        activeBlock = blk
        blocks.addLast(blk)
        if (blocks.size > 120) blocks.removeFirst()
    }

    fun onOsc133(code: String, param: String) {
        if (useAltScreen) return
        hasSeenOsc133 = true
        val absRow = absoluteRowOfScreen(cursorRow)
        when (code) {
            "A" -> {
                // 新提示符开始，结束上一块
                activeBlock?.let { if (it.finishedAtMs == null) it.finishedAtMs = System.currentTimeMillis() }
                activeBlock = null
            }
            "C" -> {
                var startAbs = absRow
                while (startAbs > 0 && rowAtAbsolute(startAbs - 1)?.softWrapped == true) {
                    startAbs--
                }
                val cmdText = buildString {
                    for (r in startAbs..absRow) {
                        append(rowAtAbsolute(r)?.toTrimmedString().orEmpty())
                    }
                }.trim()
                val blk = TerminalCommandBlock(
                    id = nextBlockId++,
                    promptAbsRow = startAbs,
                    outputStartAbsRow = absRow + 1,
                    endAbsRow = absRow,
                    commandText = cmdText,
                )
                for (r in startAbs..absRow) {
                    rowAtAbsolute(r)?.apply {
                        blockId = blk.id
                        if (r == startAbs) isCommandHeader = true
                    }
                }
                activeBlock = blk
                blocks.addLast(blk)
                if (blocks.size > 120) blocks.removeFirst()
            }
            "D" -> {
                activeBlock?.let { blk ->
                    blk.exitCode = param.toIntOrNull()
                    blk.finishedAtMs = System.currentTimeMillis()
                    blk.endAbsRow = max(blk.endAbsRow, absRow)
                }
            }
        }
    }

    /** 窗口尺寸变更：主屏执行真实软折行重排（Reflow）并同步映射光标/命令块，备用屏无条件安全钳制防越界崩溃。 */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(4, 500)
        val r = newRows.coerceIn(2, 300)
        if (c == cols && r == rows) return

        // 1. 调整备用屏
        val nextAlt = Array(r) { idx ->
            TerminalRow(c).also { row ->
                if (idx < altScreen.size) {
                    row.copyFrom(altScreen[idx])
                    row.resize(c)
                }
            }
        }
        altScreen = nextAlt

        // 2. 主屏调整（列宽变化时对 softWrapped 逻辑行执行真实 Reflow，完整保留折行内容与精确光标坐标）
        val allPrimary = ArrayList<TerminalRow>(scrollback.size + primaryScreen.size)
        allPrimary.addAll(scrollback)
        allPrimary.addAll(primaryScreen)
        val oldPrimaryAbsCursor = scrollback.size + (if (useAltScreen) primarySaved.row else cursorRow).coerceIn(0, primaryScreen.lastIndex)
        val oldPrimaryCursorCol = (if (useAltScreen) primarySaved.col else cursorCol).coerceIn(0, cols - 1)

        var lastUsed = allPrimary.lastIndex
        while (lastUsed > oldPrimaryAbsCursor && allPrimary[lastUsed].lastNonBlankCol() < 0) {
            lastUsed--
        }

        val reflowed = ArrayList<TerminalRow>(lastUsed + 16)
        var newPrimaryAbsCursor = oldPrimaryAbsCursor
        var newPrimaryCursorCol = oldPrimaryCursorCol

        if (c == cols) {
            for (i in 0..lastUsed) reflowed.add(allPrimary[i])
        } else {
            var i = 0
            while (i <= lastUsed) {
                // 收集一条由 softWrapped 串联起来的完整逻辑行
                val groupStart = i
                while (i < lastUsed && allPrimary[i].softWrapped) i++
                val groupEnd = i
                i++

                val cursorInGroup = oldPrimaryAbsCursor in groupStart..groupEnd
                val logicalBlockId = allPrimary[groupStart].blockId
                val logicalIsHeader = allPrimary[groupStart].isCommandHeader

                // 计算该逻辑行尾部有效单元长度（若光标在此逻辑行，至少保留到光标列）
                var totalCells = 0
                val srcRows = ArrayList<Pair<TerminalRow, Int>>(groupEnd - groupStart + 1)
                var cursorLinear = -1
                for (g in groupStart..groupEnd) {
                    val sr = allPrimary[g]
                    val len = if (g < groupEnd) {
                        sr.cols
                    } else {
                        val lastNonBlank = sr.lastNonBlankCol() + 1
                        if (cursorInGroup && g == oldPrimaryAbsCursor) max(lastNonBlank, oldPrimaryCursorCol) else lastNonBlank
                    }
                    if (cursorInGroup && g == oldPrimaryAbsCursor) {
                        cursorLinear = totalCells + oldPrimaryCursorCol
                    }
                    srcRows.add(sr to len)
                    totalCells += len
                }

                if (totalCells == 0) {
                    val empty = TerminalRow(c).apply {
                        blockId = logicalBlockId
                        isCommandHeader = logicalIsHeader
                    }
                    if (cursorInGroup) {
                        newPrimaryAbsCursor = reflowed.size
                        newPrimaryCursorCol = 0
                    }
                    reflowed.add(empty)
                    continue
                }

                var dstRow = TerminalRow(c).apply {
                    blockId = logicalBlockId
                    isCommandHeader = logicalIsHeader
                }
                var dstCol = 0
                var linearIdx = 0
                var cursorPlaced = false

                for ((sr, len) in srcRows) {
                    var sc = 0
                    while (sc < len) {
                        if ((sr.flags[sc] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) {
                            sc++
                            linearIdx++
                            continue
                        }
                        val isWide = (sr.flags[sc] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && sc + 1 < sr.cols
                        val span = if (isWide) 2 else 1

                        if (cursorInGroup && !cursorPlaced && cursorLinear in linearIdx until (linearIdx + span)) {
                            if (span == 2 && dstCol == c - 1) {
                                dstRow.softWrapped = true
                                reflowed.add(dstRow)
                                dstRow = TerminalRow(c).apply { blockId = logicalBlockId }
                                dstCol = 0
                            }
                            newPrimaryAbsCursor = reflowed.size
                            newPrimaryCursorCol = dstCol.coerceIn(0, c - 1)
                            cursorPlaced = true
                        }

                        if (dstCol + span > c) {
                            dstRow.softWrapped = true
                            reflowed.add(dstRow)
                            dstRow = TerminalRow(c).apply { blockId = logicalBlockId }
                            dstCol = 0
                        }

                        dstRow.chars[dstCol] = sr.chars[sc]
                        dstRow.fg[dstCol] = sr.fg[sc]
                        dstRow.bg[dstCol] = sr.bg[sc]
                        dstRow.flags[dstCol] = sr.flags[sc]
                        sr.clusters?.get(sc)?.let { cl ->
                            (dstRow.clusters ?: HashMap<Int, String>(4).also { dstRow.clusters = it })[dstCol] = cl
                        }
                        sr.hyperlinkIds?.get(sc)?.let { hl ->
                            (dstRow.hyperlinkIds ?: HashMap<Int, Int>(4).also { dstRow.hyperlinkIds = it })[dstCol] = hl
                        }
                        sr.underlineColors?.get(sc)?.let { uc ->
                            (dstRow.underlineColors ?: HashMap<Int, Int>(4).also { dstRow.underlineColors = it })[dstCol] = uc
                        }
                        if (isWide && dstCol + 1 < c) {
                            dstRow.chars[dstCol + 1] = 0
                            dstRow.fg[dstCol + 1] = sr.fg[sc]
                            dstRow.bg[dstCol + 1] = sr.bg[sc]
                            dstRow.flags[dstCol + 1] = sr.flags[sc + 1]
                            sr.hyperlinkIds?.get(sc + 1)?.let { hl ->
                                (dstRow.hyperlinkIds ?: HashMap<Int, Int>(4).also { dstRow.hyperlinkIds = it })[dstCol + 1] = hl
                            }
                            sr.underlineColors?.get(sc + 1)?.let { uc ->
                                (dstRow.underlineColors ?: HashMap<Int, Int>(4).also { dstRow.underlineColors = it })[dstCol + 1] = uc
                            }
                        }
                        dstCol += span
                        sc += span
                        linearIdx += span
                    }
                }
                if (cursorInGroup && !cursorPlaced) {
                    if (dstCol >= c) {
                        dstRow.softWrapped = true
                        reflowed.add(dstRow)
                        dstRow = TerminalRow(c).apply { blockId = logicalBlockId }
                        dstCol = 0
                    }
                    newPrimaryAbsCursor = reflowed.size
                    newPrimaryCursorCol = dstCol.coerceIn(0, c - 1)
                }
                reflowed.add(dstRow)
            }
        }

        scrollback.clear()
        val nextPrimary = Array(r) { TerminalRow(c) }
        val droppedTop: Int
        if (reflowed.size <= r) {
            droppedTop = 0
            for (k in reflowed.indices) nextPrimary[k] = reflowed[k]
            if (useAltScreen) {
                primarySaved.row = newPrimaryAbsCursor.coerceIn(0, r - 1)
                primarySaved.col = newPrimaryCursorCol.coerceIn(0, c - 1)
            } else {
                cursorRow = newPrimaryAbsCursor.coerceIn(0, r - 1)
                cursorCol = newPrimaryCursorCol.coerceIn(0, c - 1)
            }
        } else {
            val sbCount = reflowed.size - r
            val sbStart = max(0, sbCount - maxScrollback)
            droppedTop = sbStart
            for (k in sbStart until sbCount) scrollback.addLast(reflowed[k])
            for (k in 0 until r) nextPrimary[k] = reflowed[sbCount + k]
            val shiftedCursor = (newPrimaryAbsCursor - sbCount).coerceIn(0, r - 1)
            if (useAltScreen) {
                primarySaved.row = shiftedCursor
                primarySaved.col = newPrimaryCursorCol.coerceIn(0, c - 1)
            } else {
                cursorRow = shiftedCursor
                cursorCol = newPrimaryCursorCol.coerceIn(0, c - 1)
            }
        }

        primaryScreen = nextPrimary
        cols = c
        rows = r
        // 无论主屏还是备用屏，均无条件钳制光标至新尺寸内（彻底根治 vim/htop 开键盘缩窗越界崩溃）
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        altSaved.row = altSaved.row.coerceIn(0, rows - 1)
        altSaved.col = altSaved.col.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }

        // 同步校正 blocks 的起止行号（基于 Reflow 后各行 blockId 实际落点）
        if (blocks.isNotEmpty()) {
            val minRowByBlock = HashMap<Int, Int>()
            val maxRowByBlock = HashMap<Int, Int>()
            val totalNow = scrollback.size + primaryScreen.size
            for (ar in 0 until totalNow) {
                val bid = (if (ar < scrollback.size) scrollback[ar] else primaryScreen[ar - scrollback.size]).blockId
                if (bid > 0) {
                    if (bid !in minRowByBlock) minRowByBlock[bid] = ar
                    maxRowByBlock[bid] = ar
                }
            }
            val iter = blocks.iterator()
            while (iter.hasNext()) {
                val b = iter.next()
                val mn = minRowByBlock[b.id]
                val mx = maxRowByBlock[b.id]
                if (mn == null || mx == null) {
                    if (droppedTop > 0) iter.remove()
                } else {
                    b.promptAbsRow = mn
                    b.outputStartAbsRow = (mn + 1).coerceAtMost(mx)
                    b.endAbsRow = mx
                }
            }
        }
    }

    /** 全文搜索回滚缓冲 + 当前屏幕（支持大小写、正则与全词匹配，精确映射 CJK/Emoji 终端列区间）。 */
    fun search(
        query: String,
        options: TerminalSearchOptions = TerminalSearchOptions(),
        maxMatches: Int = 500,
    ): List<TerminalSearchMatch> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val regexOpts = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val compiledRegex: Regex? = when {
            options.useRegex -> runCatching {
                val pattern = if (options.wholeWord) "\\b(?:$needle)\\b" else needle
                Regex(pattern, regexOpts)
            }.getOrNull() ?: return emptyList()
            options.wholeWord -> runCatching { Regex("\\b${Regex.escape(needle)}\\b", regexOpts) }.getOrNull()
            else -> null
        }
        val plainNeedle = if (options.caseSensitive) needle else needle.lowercase()
        val results = ArrayList<TerminalSearchMatch>()
        val total = totalLines
        for (absRow in 0 until total) {
            val row = rowAtAbsolute(absRow) ?: continue
            val (text, startCols, endCols) = row.toStringWithColMap()
            if (text.isEmpty()) continue
            if (compiledRegex != null) {
                for (m in compiledRegex.findAll(text)) {
                    if (m.range.isEmpty()) continue
                    val sc = startCols[m.range.first.coerceIn(0, startCols.lastIndex)]
                    val ec = endCols[m.range.last.coerceIn(0, endCols.lastIndex)]
                    results += TerminalSearchMatch(absRow, sc, ec.coerceAtMost(cols - 1))
                    if (results.size >= maxMatches) return results
                }
            } else {
                val hay = if (options.caseSensitive) text else text.lowercase()
                var pos = hay.indexOf(plainNeedle)
                while (pos >= 0) {
                    val endCharIdx = (pos + plainNeedle.length - 1).coerceAtMost(endCols.lastIndex)
                    val sc = startCols[pos.coerceIn(0, startCols.lastIndex)]
                    val ec = endCols[endCharIdx]
                    results += TerminalSearchMatch(absRow, sc, ec.coerceAtMost(cols - 1))
                    if (results.size >= maxMatches) return results
                    pos = hay.indexOf(plainNeedle, pos + plainNeedle.length)
                }
            }
        }
        return results
    }

    /** 语义词界选词（参照 Warp `selection.rs`：非空白连词优先，遇括号/引号/分号等代码边界自动收束）。 */
    fun wordBoundsAtCol(absRow: Int, col: Int): IntRange? {
        val row = rowAtAbsolute(absRow) ?: return null
        if (cols <= 0) return null
        var c = col.coerceIn(0, cols - 1)
        if (c > 0 && (row.flags[c] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) c--
        val cp0 = row.chars[c]
        if (cp0 <= 32) return null
        fun charAtCell(idx: Int): Int {
            val p = if (idx > 0 && (row.flags[idx] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) idx - 1 else idx
            return row.chars[p]
        }
        fun isSemanticDelimiter(cp: Int): Boolean =
            cp <= 32 || cp == '('.code || cp == ')'.code || cp == '['.code || cp == ']'.code ||
                cp == '{'.code || cp == '}'.code || cp == '<'.code || cp == '>'.code ||
                cp == '\''.code || cp == '"'.code || cp == '`'.code || cp == ','.code || cp == ';'.code

        val stopOnDelim = !isSemanticDelimiter(cp0)
        fun isBoundary(idx: Int): Boolean {
            val ch = charAtCell(idx)
            return if (stopOnDelim) isSemanticDelimiter(ch) else ch != cp0
        }
        var s = c
        while (s > 0 && !isBoundary(s - 1)) {
            s--
            if (s > 0 && (row.flags[s] and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) s--
        }
        var e = if ((row.flags[c] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && c + 1 < cols) c + 1 else c
        while (e + 1 < cols && !isBoundary(e + 1)) {
            e++
            if ((row.flags[e] and TerminalStyle.FLAG_WIDE_LEAD) != 0 && e + 1 < cols) e++
        }
        return s..e
    }

    /** 三击整行选区：返回包含所有软折行续行的完整逻辑行绝对区间。 */
    fun logicalLineRange(absRow: Int): IntRange {
        val total = totalLines
        if (total == 0) return 0..0
        var start = absRow.coerceIn(0, total - 1)
        var end = start
        while (start > 0 && rowAtAbsolute(start - 1)?.softWrapped == true) start--
        while (end + 1 < total && rowAtAbsolute(end)?.softWrapped == true) end++
        return start..end
    }

    /** 基于静默 OSC 133 / 命令记录向上跳转上一条命令首行。 */
    fun findPreviousPromptRow(beforeAbsRow: Int): Int? {
        for (i in blocks.lastIndex downTo 0) {
            val r = blocks[i].promptAbsRow
            if (r < beforeAbsRow) return r
        }
        return null
    }

    /** 基于静默 OSC 133 / 命令记录向下跳转下一条命令首行。 */
    fun findNextPromptRow(afterAbsRow: Int): Int? {
        for (b in blocks) {
            if (b.promptAbsRow > afterAbsRow) return b.promptAbsRow
        }
        return null
    }

    /** 导出全部历史与当前屏幕纯文本（自动还原软折行，不引入多余换行符）。 */
    fun exportFullTranscript(): String {
        val total = totalLines
        if (total == 0) return ""
        return extractText(0, 0, total - 1, cols - 1)
    }

    fun hyperlinkAt(absRow: Int, col: Int): String? {
        val row = rowAtAbsolute(absRow) ?: return null
        val c = col.coerceIn(0, (cols - 1).coerceAtLeast(0))
        val id = row.hyperlinkIds?.get(c) ?: return null
        return hyperlinkRegistry.get(id)
    }

    /** 选区文本提取（自动尊重软折行，避免长命令行复制后多出换行符）。 */
    fun extractText(startAbsRow: Int, startCol: Int, endAbsRow: Int, endCol: Int): String {
        val total = totalLines
        if (total == 0) return ""
        val (r0, c0, r1, c1) = if (startAbsRow < endAbsRow || (startAbsRow == endAbsRow && startCol <= endCol)) {
            intArrayOf(startAbsRow, startCol, endAbsRow, endCol)
        } else {
            intArrayOf(endAbsRow, endCol, startAbsRow, startCol)
        }
        val minR = r0.coerceIn(0, total - 1)
        val maxR = r1.coerceIn(minR, total - 1)
        return buildString {
            for (r in minR..maxR) {
                val row = rowAtAbsolute(r) ?: continue
                val sc = if (r == minR) c0 else 0
                val ec = if (r == maxR) c1 else cols - 1
                append(row.substringCols(sc, ec))
                if (r < maxR && !row.softWrapped) append('\n')
            }
        }.trimEnd()
    }

    fun extractBlockText(blockId: Int): String {
        val total = totalLines
        return buildString {
            for (r in 0 until total) {
                val row = rowAtAbsolute(r) ?: continue
                if (row.blockId == blockId) {
                    append(row.toTrimmedString())
                    if (!row.softWrapped) append('\n')
                }
            }
        }.trim()
    }

    fun detectUrls(fromAbsRow: Int, toAbsRowInclusive: Int): List<TerminalLinkSpan> {
        val out = ArrayList<TerminalLinkSpan>()
        val total = totalLines
        if (total == 0) return out
        val s = fromAbsRow.coerceIn(0, total - 1)
        val e = toAbsRowInclusive.coerceIn(s, total - 1)
        for (r in s..e) {
            val row = rowAtAbsolute(r) ?: continue
            // 1. 先采集显式 OSC 8 超链接区间（通过有界驻留池还原 URI）
            val hl = row.hyperlinkIds
            if (!hl.isNullOrEmpty()) {
                var c = 0
                while (c < row.cols) {
                    val id = hl[c]
                    if (id != null && id > 0) {
                        val uri = hyperlinkRegistry.get(id)
                        val startC = c
                        while (c + 1 < row.cols && hl[c + 1] == id) c++
                        if (uri != null) out += TerminalLinkSpan(r, startC, c, uri, TerminalLinkKind.OSC8)
                    }
                    c++
                }
            }
            // 2. 再做 HTTP/HTTPS URL 与智能文件路径（/path/file.kt:42:5 / ./src/a.ts:10）检测
            val (text, startCols, endCols) = row.toStringWithColMap()
            if (text.isEmpty()) continue
            if (text.contains("://")) {
                for (m in URL_REGEX.findAll(text)) {
                    val sc = startCols[m.range.first.coerceIn(0, startCols.lastIndex)]
                    val ec = endCols[m.range.last.coerceIn(0, endCols.lastIndex)]
                    if (out.none { it.absRow == r && it.startCol == sc && it.url == m.value }) {
                        out += TerminalLinkSpan(r, sc, ec, m.value, TerminalLinkKind.URL)
                    }
                }
            }
            if (text.contains('/') && text.contains('.')) {
                for (m in FILE_LINK_REGEX.findAll(text)) {
                    val pathPart = m.groupValues[1]
                    if (pathPart.startsWith("//") || pathPart.contains("://")) continue
                    val lineNum = m.groupValues[2].toIntOrNull()
                    val colNum = m.groupValues[3].toIntOrNull()
                    val sc = startCols[m.range.first.coerceIn(0, startCols.lastIndex)]
                    val ec = endCols[m.range.last.coerceIn(0, endCols.lastIndex)]
                    if (out.none { it.absRow == r && sc in it.startCol..it.endCol }) {
                        out += TerminalLinkSpan(r, sc, ec, pathPart, TerminalLinkKind.FILE_PATH, lineNum, colNum)
                    }
                }
            }
        }
        return out
    }

    private fun mapDecGraphics(cp: Int): Int = when (cp) {
        0x6A -> 0x2518 // ┘
        0x6B -> 0x2510 // ┐
        0x6C -> 0x250C // ┌
        0x6D -> 0x2514 // └
        0x6E -> 0x253C // ┼
        0x71 -> 0x2500 // ─
        0x74 -> 0x251C // ├
        0x75 -> 0x2524 // ┤
        0x76 -> 0x2534 // ┴
        0x77 -> 0x252C // ┬
        0x78 -> 0x2502 // │
        0x60 -> 0x25C6 // ◆
        0x61 -> 0x2592 // ▒
        else -> cp
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s"'<>()\[\]{}]+""")
        private val FILE_LINK_REGEX = Regex("""(?<![A-Za-z0-9_:])((?:\.\.?/|~/|/)[A-Za-z0-9._/\-]+\.[A-Za-z0-9]{1,12})(?::(\d+))?(?::(\d+))?""")
    }
}
