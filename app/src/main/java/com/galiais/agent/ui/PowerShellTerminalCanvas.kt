package com.galiais.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ssh.TerminalFontPack
import com.galiais.agent.engine.terminal.SecretRedactionMode
import com.galiais.agent.engine.terminal.SolbraidTerminalEngine
import com.galiais.agent.engine.terminal.TerminalBoxDrawing
import com.galiais.agent.engine.terminal.TerminalColorScheme
import com.galiais.agent.engine.terminal.TerminalCursorStyle
import com.galiais.agent.engine.terminal.TerminalKey
import com.galiais.agent.engine.terminal.TerminalLinkKind
import com.galiais.agent.engine.terminal.TerminalLinkSpan
import com.galiais.agent.engine.terminal.TerminalRow
import com.galiais.agent.engine.terminal.TerminalSearchMatch
import com.galiais.agent.engine.terminal.TerminalSecretRedactor
import com.galiais.agent.engine.terminal.TerminalSecretSpan
import com.galiais.agent.engine.terminal.TerminalStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 选区起止绝对行列坐标（横跨回滚与主屏）。 */
internal data class TerminalSelection(
    val startAbsRow: Int,
    val startCol: Int,
    val endAbsRow: Int,
    val endCol: Int,
) {
    fun contains(absRow: Int, col: Int): Boolean {
        val (r0, c0, r1, c1) = normalized()
        if (absRow < r0 || absRow > r1) return false
        if (r0 == r1) return col in c0..c1
        return when (absRow) {
            r0 -> col >= c0
            r1 -> col <= c1
            else -> true
        }
    }

    fun normalized(): IntArray = if (startAbsRow < endAbsRow || (startAbsRow == endAbsRow && startCol <= endCol)) {
        intArrayOf(startAbsRow, startCol, endAbsRow, endCol)
    } else {
        intArrayOf(endAbsRow, endCol, startAbsRow, startCol)
    }
}

/**
 * Solbraid 纯 Jetpack Compose 自绘 PowerShell / Windows Terminal 渲染画布：
 * - 零内置 AndroidView（全分屏共享顶层唯一 IME 视图，切换分屏/横竖屏永不断连或抢焦点）；
 * - 90ms Resize 防抖（首帧即时生效，键盘弹出动画不再引发 SIGWINCH 风暴）；
 * - 统一无冲突手势管线（零延迟单击、双击精确选词、长按即拖选区、单指回滚/SGR 1006 鼠标、双指平滑缩放）；
 * - O(1) 行级搜索索引、组合字符/Emoji 簇渲染、OSC 8 超链接点击、回滚阅读位置自动锚定与备用屏自动复位。
 */
@Composable
internal fun PowerShellTerminalCanvas(
    engine: SolbraidTerminalEngine,
    fonts: TerminalFontPack,
    scheme: TerminalColorScheme,
    cursorStyle: TerminalCursorStyle,
    fontSizeSp: Int,
    boxDrawingEnabled: Boolean,
    minContrastEnabled: Boolean,
    secretRedactionMode: SecretRedactionMode,
    visualBellPulse: Int,
    inputEnabled: Boolean,
    focusedPane: Boolean,
    searchMatches: List<TerminalSearchMatch>,
    activeSearchIndex: Int,
    scrollOffsetLines: Int,
    composingText: String = "",
    onScrollOffsetChange: (Int) -> Unit,
    onFontSizePinch: (Int) -> Unit,
    onCopyText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenFileLink: (TerminalLinkSpan) -> Unit,
    onFocusPane: () -> Unit,
    onRequestImeFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameVersion by engine.frameVersion.collectAsState()
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSizeSp.sp.toPx() }
    val padLeftPx = with(density) { 6.dp.toPx() }
    val padTopPx = with(density) { 3.dp.toPx() }
    val padRightGutterPx = with(density) { 14.dp.toPx() }
    val padBottomPx = with(density) { 3.dp.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }
    val doubleTapDistPx = with(density) { 28.dp.toPx() }
    val handleHitRadiusPx = with(density) { 26.dp.toPx() }

    val textPaint = remember(fonts, textSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = fonts.regular
            textSize = textSizePx
        }
    }
    val cellWidth = remember(fonts, textSizePx) {
        max(textPaint.measureText("M"), 6f)
    }
    val fontMetrics = remember(fonts, textSizePx) { textPaint.fontMetrics }
    val lineHeight = remember(fontMetrics) {
        max((fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading) * 1.12f, 12f)
    }
    val baselineOffset = remember(fontMetrics, lineHeight) {
        val textH = fontMetrics.descent - fontMetrics.ascent
        (lineHeight - textH) * 0.5f - fontMetrics.ascent
    }
    val bgPaint = remember { Paint().apply { style = Paint.Style.FILL } }
    val decoPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val imgPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val dottedEffect = remember(density) {
        val d = with(density) { 1.8.dp.toPx() }
        DashPathEffect(floatArrayOf(d, d * 1.4f), 0f)
    }
    val dashedEffect = remember(density) {
        val d = with(density) { 4.dp.toPx() }
        DashPathEffect(floatArrayOf(d, d * 0.7f), 0f)
    }
    val wavePath = remember { Path() }
    val triPath = remember { Path() }
    val bitmapCache = remember {
        object : LruCache<Long, Bitmap>(24) {}
    }
    val animatedCache = remember {
        object : LruCache<Long, Drawable>(8) {}
    }
    val secretRowCache = remember {
        object : LruCache<Long, List<TerminalSecretSpan>>(128) {}
    }
    val imgSrcRect = remember { Rect() }
    val imgDstRect = remember { RectF() }
    val scrollScope = rememberCoroutineScope()
    val scrollAnim = remember(engine) { Animatable(scrollOffsetLines.toFloat()) }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var revealedSecretKeys by remember(engine) { mutableStateOf<Set<String>>(emptySet()) }
    var cursorBlinkOn by remember { mutableStateOf(true) }
    var visualBellFlash by remember { mutableStateOf(false) }
    var animFrameTick by remember { mutableIntStateOf(0) }
    var hasAppliedInitialSize by remember(engine) { mutableStateOf(false) }

    val snapshot = remember(frameVersion, scrollOffsetLines, engine) {
        engine.captureViewport(scrollOffsetLines)
    }
    val hasAnimatedImages = remember(snapshot) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && snapshot.inlineImages.any { it.isAnimatedEncoded() }
    }
    val matchesByRow = remember(searchMatches) {
        if (searchMatches.isEmpty()) emptyMap() else searchMatches.groupBy { it.absRow }
    }
    val secretsByRow = remember(snapshot, secretRedactionMode) {
        if (secretRedactionMode == SecretRedactionMode.OFF) {
            emptyMap<Int, List<TerminalSecretSpan>>()
        } else {
            val map = HashMap<Int, List<TerminalSecretSpan>>()
            fun scanRow(absRow: Int, row: TerminalRow) {
                var h = row.cols.toLong() * 31L
                val ch = row.chars
                val limit = row.cols.coerceAtMost(ch.size)
                var hasNonSpace = false
                for (i in 0 until limit) {
                    val cp = ch[i]
                    if (cp > 32) hasNonSpace = true
                    h = h * 31L + cp
                }
                if (!hasNonSpace) return
                val cached = secretRowCache.get(h)
                val spans = if (cached != null) {
                    val prefix = "$absRow:"
                    if (cached.isEmpty() || cached[0].key.startsWith(prefix)) {
                        cached
                    } else {
                        cached.map { it.copy(key = "$absRow:${it.startCol}:${it.endCol}") }
                    }
                } else {
                    TerminalSecretRedactor.findInRow(absRow, row).also { secretRowCache.put(h, it) }
                }
                if (spans.isNotEmpty()) map[absRow] = spans
            }
            snapshot.preTopRow?.let { scanRow(snapshot.firstAbsRow - 1, it) }
            for (r in 0 until snapshot.rows) {
                scanRow(snapshot.firstAbsRow + r, snapshot.visibleRows[r])
            }
            map
        }
    }

    // 全部手势与异步回调实时读取最新状态，彻底消除首帧冻结快照问题
    val latestEngine by rememberUpdatedState(engine)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestCellWidth by rememberUpdatedState(cellWidth)
    val latestLineHeight by rememberUpdatedState(lineHeight)
    val latestScrollOffset by rememberUpdatedState(scrollOffsetLines)
    val latestScrollAnim by rememberUpdatedState(scrollAnim)
    val latestFontSize by rememberUpdatedState(fontSizeSp)
    val latestScheme by rememberUpdatedState(scheme)
    val latestBoxDrawing by rememberUpdatedState(boxDrawingEnabled)
    val latestMinContrast by rememberUpdatedState(minContrastEnabled)
    val latestSecretMode by rememberUpdatedState(secretRedactionMode)
    val latestSecretsByRow by rememberUpdatedState(secretsByRow)
    val latestOnScrollOffset by rememberUpdatedState(onScrollOffsetChange)
    val latestOnFontPinch by rememberUpdatedState(onFontSizePinch)
    val latestOnOpenUrl by rememberUpdatedState(onOpenUrl)
    val latestOnOpenFileLink by rememberUpdatedState(onOpenFileLink)
    val latestOnFocusPane by rememberUpdatedState(onFocusPane)
    val latestOnRequestIme by rememberUpdatedState(onRequestImeFocus)

    // 外部代码跳转回滚位置时（如搜索/提示符跳转/回到底部按钮），无缝同步浮点平滑偏移
    LaunchedEffect(scrollOffsetLines, engine) {
        if (!scrollAnim.isRunning && floor(scrollAnim.value).toInt() != scrollOffsetLines) {
            scrollAnim.snapTo(scrollOffsetLines.toFloat())
        }
    }

    // 内联动态 GIF / WebP 帧动画刷新驱动（仅当视口可见动图时运行，平时零能耗）
    LaunchedEffect(hasAnimatedImages) {
        if (!hasAnimatedImages) return@LaunchedEffect
        while (true) {
            delay(42L)
            animFrameTick++
        }
    }

    // 同步单格像素尺寸至引擎，确保 CSI 14t / 16t 上报精确硬件像素
    LaunchedEffect(cellWidth, lineHeight, engine) {
        engine.updateCellPixelMetrics(cellWidth.roundToInt(), lineHeight.roundToInt())
    }

    // 视觉响铃柔光脉冲
    LaunchedEffect(visualBellPulse) {
        if (visualBellPulse > 0) {
            visualBellFlash = true
            delay(110L)
            visualBellFlash = false
        }
    }

    // 1. 向上翻阅历史时自动锚定当前阅读行；进入备用屏或清空回滚时立即复位偏移，绝不遮挡光标
    var lastSeq by remember(engine) { mutableLongStateOf(snapshot.scrollbackSequence) }
    LaunchedEffect(snapshot.useAltScreen, snapshot.scrollbackSize, snapshot.scrollbackSequence) {
        val prevSeq = lastSeq
        lastSeq = snapshot.scrollbackSequence
        if (snapshot.useAltScreen || snapshot.scrollbackSize == 0) {
            if (latestScrollOffset != 0) latestOnScrollOffset(0)
        } else if (latestScrollOffset > 0 && snapshot.scrollbackSequence > prevSeq) {
            val added = (snapshot.scrollbackSequence - prevSeq).toInt().coerceAtLeast(0)
            latestOnScrollOffset((latestScrollOffset + added).coerceIn(0, snapshot.scrollbackSize))
        } else if (latestScrollOffset > snapshot.scrollbackSize) {
            latestOnScrollOffset(snapshot.scrollbackSize)
        }
    }

    // 2. DECSET 1004 窗格焦点报告
    LaunchedEffect(focusedPane, engine) {
        engine.sendFocus(focusedPane)
    }

    // 3. 光标平滑闪烁（严格尊重 DECSCUSR / CSI ?12l 静止光标指令）
    LaunchedEffect(focusedPane, inputEnabled, scrollOffsetLines, snapshot.cursorBlink) {
        if (!focusedPane || !inputEnabled || scrollOffsetLines != 0 || !snapshot.cursorBlink) {
            cursorBlinkOn = true
            return@LaunchedEffect
        }
        cursorBlinkOn = true
        while (true) {
            delay(540L)
            cursorBlinkOn = !cursorBlinkOn
        }
    }

    // 4. 视口尺寸防抖重算（首帧立即下发，键盘弹出/分屏拖拽期间 90ms 合并防抖，杜绝 SIGWINCH 风暴）
    LaunchedEffect(viewportSize, cellWidth, lineHeight, engine) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val usableW = (viewportSize.width - padLeftPx - padRightGutterPx).coerceAtLeast(cellWidth * 10f)
        val usableH = (viewportSize.height - padTopPx - padBottomPx).coerceAtLeast(lineHeight * 2f)
        val cols = floor((usableW / cellWidth).toDouble()).toInt().coerceIn(10, 500)
        val rows = floor((usableH / lineHeight).toDouble()).toInt().coerceIn(2, 300)
        if (hasAppliedInitialSize) {
            delay(90L)
        } else {
            hasAppliedInitialSize = true
        }
        engine.resize(cols, rows)
    }

    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    var tapStreak by remember { mutableStateOf(0) }
    var zoomAccum by remember { mutableFloatStateOf(1f) }
    val activeBgColor = Color(snapshot.dynamicBgOverride ?: scheme.background)

    Box(
        modifier = modifier
            .background(activeBgColor)
            .onSizeChanged { viewportSize = it },
    ) {
        // 统一无冲突手势与 120fps 纯 Compose Canvas 渲染画布
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        if (latestScrollAnim.isRunning) {
                            scrollScope.launch { latestScrollAnim.stop() }
                        }
                        val downTime = System.currentTimeMillis()
                        val downPos = firstDown.position
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(firstDown.uptimeMillis, downPos)
                        var moved = false
                        var multiTouch = false
                        var longPressed = false
                        var didSmoothScroll = false
                        var scrollAccumPx = 0f
                        var mousePressedSent = false
                        var dragHandleMode = 0 // 0=none, 1=dragStartHandle, 2=dragEndHandle
                        zoomAccum = 1f

                        fun posToCol(x: Float): Int {
                            val snap = latestSnapshot
                            return ((x - padLeftPx) / latestCellWidth).toInt().coerceIn(0, (snap.cols - 1).coerceAtLeast(0))
                        }

                        fun posToRow(y: Float): Int {
                            val snap = latestSnapshot
                            val fracOffset = if (snap.useAltScreen) 0f else {
                                val v = latestScrollAnim.value
                                ((v - floor(v)) * latestLineHeight).coerceIn(0f, latestLineHeight)
                            }
                            return ((y - padTopPx - fracOffset) / latestLineHeight).toInt().coerceIn(0, (snap.rows - 1).coerceAtLeast(0))
                        }

                        // 检测是否按在已有选区的起始/结束圆点手柄上
                        selection?.let { sel ->
                            val norm = sel.normalized()
                            val snap = latestSnapshot
                            val sr = norm[0] - snap.firstAbsRow
                            val er = norm[2] - snap.firstAbsRow
                            if (sr in 0 until snap.rows) {
                                val hx = padLeftPx + norm[1] * latestCellWidth
                                val hy = padTopPx + sr * latestLineHeight
                                if (hypot((downPos.x - hx).toDouble(), (downPos.y - hy).toDouble()) <= handleHitRadiusPx) {
                                    dragHandleMode = 1
                                }
                            }
                            if (dragHandleMode == 0 && er in 0 until snap.rows) {
                                val hx = padLeftPx + (norm[3] + 1) * latestCellWidth
                                val hy = padTopPx + (er + 1) * latestLineHeight
                                if (hypot((downPos.x - hx).toDouble(), (downPos.y - hy).toDouble()) <= handleHitRadiusPx) {
                                    dragHandleMode = 2
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChanges = event.changes.filter { it.pressed }
                            if (activeChanges.isEmpty()) break

                            // 1. 双指捏合缩放字号（累积比例，永不被单指滚动拦截）
                            if (activeChanges.size >= 2) {
                                multiTouch = true
                                val z = event.calculateZoom()
                                if (z != 1f) {
                                    zoomAccum *= z
                                    if (zoomAccum >= 1.10f) {
                                        latestOnFontPinch((latestFontSize + 1).coerceAtMost(28))
                                        zoomAccum = 1f
                                    } else if (zoomAccum <= 0.90f) {
                                        latestOnFontPinch((latestFontSize - 1).coerceAtLeast(8))
                                        zoomAccum = 1f
                                    }
                                }
                                activeChanges.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                            if (multiTouch) continue

                            val change = activeChanges.first()
                            val dist = hypot((change.position.x - downPos.x).toDouble(), (change.position.y - downPos.y).toDouble()).toFloat()

                            // 2. 长按原地 380ms 自动进入自由框选模式
                            if (dragHandleMode == 0 && !moved && !longPressed && System.currentTimeMillis() - downTime >= 380L && dist < touchSlopPx) {
                                longPressed = true
                                latestOnFocusPane()
                                val snap = latestSnapshot
                                val c = posToCol(downPos.x)
                                val r = posToRow(downPos.y)
                                selection = TerminalSelection(snap.firstAbsRow + r, c, snap.firstAbsRow + r, c)
                            }

                            if (!moved && dist >= touchSlopPx) {
                                moved = true
                                latestOnFocusPane()
                                if (dragHandleMode == 0 && !longPressed && selection == null && latestEngine.isMouseDragTrackingEnabled) {
                                    latestEngine.sendMouse(0, posToCol(downPos.x) + 1, posToRow(downPos.y) + 1, pressed = true)
                                    mousePressedSent = true
                                }
                            }

                            if (moved || longPressed || dragHandleMode != 0) {
                                val deltaY = change.position.y - change.previousPosition.y
                                if (change.positionChanged()) change.consume()
                                val curSel = selection
                                if (curSel != null && (longPressed || dragHandleMode != 0)) {
                                    val snap = latestSnapshot
                                    val tr = snap.firstAbsRow + posToRow(change.position.y)
                                    val tc = posToCol(change.position.x)
                                    if (dragHandleMode == 1) {
                                        val norm = curSel.normalized()
                                        selection = TerminalSelection(tr, tc, norm[2], norm[3])
                                    } else {
                                        val norm = if (dragHandleMode == 2) curSel.normalized() else null
                                        selection = if (norm != null) {
                                            TerminalSelection(norm[0], norm[1], tr, tc)
                                        } else {
                                            curSel.copy(endAbsRow = tr, endCol = tc)
                                        }
                                    }
                                } else if (mousePressedSent && latestEngine.isMouseDragTrackingEnabled) {
                                    // SGR 1002/1003 左键拖拽事件（button = 32 表示运动+左键）
                                    latestEngine.sendMouse(32, posToCol(change.position.x) + 1, posToRow(change.position.y) + 1, pressed = true)
                                } else {
                                    val eng = latestEngine
                                    val lh = latestLineHeight
                                    if (!latestSnapshot.useAltScreen) {
                                        // 120fps 亚像素丝滑回滚 + 惯性速度追踪
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        val maxSb = eng.scrollbackSize.toFloat()
                                        if (maxSb > 0f && deltaY != 0f) {
                                            didSmoothScroll = true
                                            val nextFloat = (latestScrollAnim.value + deltaY / lh).coerceIn(0f, maxSb)
                                            scrollScope.launch { latestScrollAnim.snapTo(nextFloat) }
                                            val nextInt = floor(nextFloat).toInt().coerceIn(0, eng.scrollbackSize)
                                            if (nextInt != latestScrollOffset) latestOnScrollOffset(nextInt)
                                        }
                                    } else {
                                        scrollAccumPx += deltaY
                                        val lineDelta = (scrollAccumPx / lh).toInt()
                                        if (lineDelta != 0) {
                                            scrollAccumPx -= lineDelta * lh
                                            if (eng.isMouseTrackingEnabled) {
                                                val btn = if (lineDelta > 0) 64 else 65
                                                val c1 = posToCol(change.position.x) + 1
                                                val r1 = posToRow(change.position.y) + 1
                                                repeat(abs(lineDelta).coerceAtMost(5)) {
                                                    eng.sendMouse(btn, c1, r1, pressed = true)
                                                }
                                            } else if (eng.isAlternateScrollEnabled) {
                                                // Warp ALTERNATE_SCROLL (CSI ? 1007): 备用屏（less/man/git log/vim）滑动自动映射上下方向键
                                                val key = if (lineDelta > 0) TerminalKey.UP else TerminalKey.DOWN
                                                repeat(abs(lineDelta).coerceAtMost(5)) { eng.sendKey(key) }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (mousePressedSent) {
                            latestEngine.sendMouse(0, posToCol(downPos.x) + 1, posToRow(downPos.y) + 1, pressed = false)
                            return@awaitEachGesture
                        }

                        // 松手后触发原生指数衰减物理惯性回滚（120fps 丝滑滑行，落地微对齐整行）
                        if (didSmoothScroll && !latestSnapshot.useAltScreen && !multiTouch && !longPressed && dragHandleMode == 0) {
                            val velLinesPerSec = velocityTracker.calculateVelocity().y / latestLineHeight
                            val anim = latestScrollAnim
                            scrollScope.launch {
                                val maxSb = latestEngine.scrollbackSize.toFloat()
                                if (maxSb <= 0f) {
                                    anim.snapTo(0f)
                                    latestOnScrollOffset(0)
                                    return@launch
                                }
                                if (abs(velLinesPerSec) > 1.8f) {
                                    anim.updateBounds(lowerBound = 0f, upperBound = maxSb)
                                    anim.animateDecay(
                                        initialVelocity = velLinesPerSec,
                                        animationSpec = exponentialDecay(frictionMultiplier = 1.45f),
                                    ) {
                                        val intLine = floor(value).toInt().coerceIn(0, latestEngine.scrollbackSize)
                                        if (intLine != latestScrollOffset) latestOnScrollOffset(intLine)
                                    }
                                }
                                val settled = anim.value.roundToInt().coerceIn(0, latestEngine.scrollbackSize)
                                anim.snapTo(settled.toFloat())
                                latestOnScrollOffset(settled)
                            }
                            return@awaitEachGesture
                        }

                        // 3. 零延迟单击 / 280ms 双击语义选词 / 三击整逻辑行选区
                        if (!moved && !multiTouch && !longPressed && dragHandleMode == 0) {
                            latestOnFocusPane()
                            val now = System.currentTimeMillis()
                            val withinWindow = (now - lastTapMs) <= 290L &&
                                hypot((downPos.x - lastTapPos.x).toDouble(), (downPos.y - lastTapPos.y).toDouble()) <= doubleTapDistPx
                            tapStreak = if (withinWindow) ((tapStreak % 3) + 1) else 1
                            lastTapMs = now
                            lastTapPos = downPos

                            val snap = latestSnapshot
                            val eng = latestEngine
                            val col = posToCol(downPos.x)
                            val row = posToRow(downPos.y)
                            val absRow = snap.firstAbsRow + row

                            if (tapStreak == 2) {
                                val bounds = eng.wordBoundsAtCol(absRow, col)
                                if (bounds != null) {
                                    selection = TerminalSelection(absRow, bounds.first, absRow, bounds.last)
                                }
                                return@awaitEachGesture
                            }
                            if (tapStreak == 3) {
                                val lr = eng.logicalLineRange(absRow)
                                selection = TerminalSelection(lr.first, 0, lr.last, (snap.cols - 1).coerceAtLeast(0))
                                tapStreak = 0
                                return@awaitEachGesture
                            }

                            if (selection != null) {
                                selection = null
                                return@awaitEachGesture
                            }

                            // 3a. 若启用密钥脱敏且点中敏感区间，临时切换该条密钥的显隐状态
                            if (latestSecretMode != SecretRedactionMode.OFF) {
                                val hitSecret = latestSecretsByRow[absRow]?.firstOrNull { col in it.startCol..it.endCol }
                                if (hitSecret != null) {
                                    revealedSecretKeys = if (hitSecret.key in revealedSecretKeys) {
                                        revealedSecretKeys - hitSecret.key
                                    } else {
                                        revealedSecretKeys + hitSecret.key
                                    }
                                    return@awaitEachGesture
                                }
                            }

                            // 3b. 优先检测显式 OSC 8 超链接，再检测 URL 与智能文件路径 (file:line:col)
                            val osc8 = eng.hyperlinkAt(absRow, col)
                            if (osc8 != null) {
                                latestOnOpenUrl(osc8)
                                return@awaitEachGesture
                            }
                            val hitLink = eng.detectVisibleUrls(latestScrollOffset)
                                .firstOrNull { it.absRow == absRow && col in it.startCol..it.endCol }
                            if (hitLink != null) {
                                if (hitLink.kind == TerminalLinkKind.FILE_PATH) latestOnOpenFileLink(hitLink)
                                else latestOnOpenUrl(hitLink.url)
                                return@awaitEachGesture
                            }

                            // 3c. 若远端 TUI 开启鼠标追踪（vim/tmux/htop/claude），派发 SGR 1006 左键点击
                            if (eng.isMouseTrackingEnabled) {
                                eng.sendMouse(0, col + 1, row + 1, pressed = true)
                                eng.sendMouse(0, col + 1, row + 1, pressed = false)
                            }
                            latestOnRequestIme()
                        }
                    }
                },
        ) {
            if (animFrameTick < 0) return@Canvas
            val native = drawContext.canvas.nativeCanvas
            val activeScheme = latestScheme
            val useBoxDrawing = latestBoxDrawing
            val useMinContrast = latestMinContrast
            val activeSecretMode = latestSecretMode
            val activeBgInt = snapshot.dynamicBgOverride ?: activeScheme.background
            val activeFgInt = snapshot.dynamicFgOverride ?: activeScheme.foreground
            val activeCursorInt = snapshot.dynamicCursorOverride ?: activeScheme.cursor
            val palOverrides = snapshot.paletteOverrides.takeIf { it.isNotEmpty() }
            val activeMatch = searchMatches.getOrNull(activeSearchIndex)
            val currentFloatScroll = if (snapshot.useAltScreen) 0f else scrollAnim.value
            val fracPx = if (snapshot.useAltScreen) 0f else ((currentFloatScroll - floor(currentFloatScroll)) * lineHeight).coerceIn(0f, lineHeight)

            native.save()
            native.clipRect(0f, 0f, size.width, size.height)

            // 1. 内联图形与动态视频帧（iTerm2 OSC 1337 含动态 GIF/WebP、Kitty Graphics PNG/RGB/RGBA+zlib、DEC Sixel）
            if (snapshot.inlineImages.isNotEmpty()) {
                for (img in snapshot.inlineImages) {
                    val cacheKey = (img.id.toLong() shl 32) xor (img.revision and 0xFFFFFFFFL)
                    val relRow = img.absRow - snapshot.firstAbsRow
                    val x0 = padLeftPx + img.col * cellWidth
                    val y0 = padTopPx + relRow * lineHeight + fracPx
                    val maxW = img.colsSpan * cellWidth
                    val maxH = img.rowsSpan * lineHeight

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && img.isAnimatedEncoded()) {
                        val drawable = animatedCache.get(cacheKey) ?: runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(img.data)))
                        }.getOrNull()?.also { d ->
                            (d as? AnimatedImageDrawable)?.start()
                            animatedCache.put(cacheKey, d)
                        }
                        if (drawable != null) {
                            val iw = drawable.intrinsicWidth.coerceAtLeast(1)
                            val ih = drawable.intrinsicHeight.coerceAtLeast(1)
                            val scale = min(maxW / iw, maxH / ih)
                            val drawW = (iw * scale).roundToInt().coerceAtLeast(1)
                            val drawH = (ih * scale).roundToInt().coerceAtLeast(1)
                            drawable.setBounds(x0.roundToInt(), y0.roundToInt(), x0.roundToInt() + drawW, y0.roundToInt() + drawH)
                            drawable.draw(native)
                            continue
                        }
                    }

                    val bmp = bitmapCache.get(cacheKey) ?: runCatching {
                        val pixels = img.argbPixels
                        if (pixels != null && img.pixelWidth > 0 && img.pixelHeight > 0) {
                            Bitmap.createBitmap(pixels, img.pixelWidth, img.pixelHeight, Bitmap.Config.ARGB_8888)
                        } else {
                            BitmapFactory.decodeByteArray(img.data, 0, img.data.size)
                        }
                    }.getOrNull()?.also { bitmapCache.put(cacheKey, it) } ?: continue
                    val scale = min(maxW / bmp.width.coerceAtLeast(1), maxH / bmp.height.coerceAtLeast(1))
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    imgSrcRect.set(0, 0, bmp.width, bmp.height)
                    imgDstRect.set(x0, y0, x0 + drawW, y0 + drawH)
                    native.drawBitmap(bmp, imgSrcRect, imgDstRect, imgPaint)
                }
            }

            // 2. 逐行逐格绘制（含亚像素顶部预取行 preTopRow + Braille/Sextant/Box/Powerline 矢量 + 密钥脱敏 + 彩色下划线）
            fun drawTerminalRow(row: TerminalRow, absRow: Int, topY: Float) {
                val bottomY = topY + lineHeight
                if (bottomY < 0f || topY > size.height) return
                val baseY = topY + baselineOffset
                val rowMatches = matchesByRow[absRow]
                val rowSecrets = if (activeSecretMode != SecretRedactionMode.OFF) latestSecretsByRow[absRow] else null

                var c = 0
                while (c < row.cols) {
                    val fl = row.flags[c]
                    if ((fl and TerminalStyle.FLAG_WIDE_CONTINUATION) != 0) { c++; continue }
                    val isWide = (fl and TerminalStyle.FLAG_WIDE_LEAD) != 0
                    val cellSpan = if (isWide && c + 1 < row.cols) 2 else 1
                    val cellW = cellSpan * cellWidth
                    val leftX = padLeftPx + c * cellWidth
                    val rightX = leftX + cellW

                    val inverse = (fl and TerminalStyle.FLAG_INVERSE) != 0
                    val bold = (fl and TerminalStyle.FLAG_BOLD) != 0
                    val italic = (fl and TerminalStyle.FLAG_ITALIC) != 0
                    val rawFg = TerminalStyle.resolveColor(row.fg[c], isFg = true, bold = bold, scheme = activeScheme, paletteOverrides = palOverrides, fgOverride = activeFgInt, bgOverride = activeBgInt)
                    val rawBg = TerminalStyle.resolveColor(row.bg[c], isFg = false, bold = false, scheme = activeScheme, paletteOverrides = palOverrides, fgOverride = activeFgInt, bgOverride = activeBgInt)
                    val fgColor = if (inverse) rawBg else rawFg
                    val bgColor = if (inverse) rawFg else rawBg

                    if (bgColor != activeBgInt) {
                        bgPaint.color = bgColor
                        bgPaint.alpha = 255
                        native.drawRect(leftX, topY, rightX, bottomY, bgPaint)
                    }

                    // O(1) 行级搜索高亮
                    if (rowMatches != null) {
                        var hit: TerminalSearchMatch? = null
                        for (m in rowMatches) {
                            if (c in m.startCol..m.endCol) { hit = m; break }
                        }
                        if (hit != null) {
                            bgPaint.color = if (hit == activeMatch) 0xCCFF9E64.toInt() else 0x77E0AF68
                            native.drawRect(leftX, topY, rightX, bottomY, bgPaint)
                        }
                    }

                    // 自由框选高亮
                    if (selection?.contains(absRow, c) == true) {
                        bgPaint.color = activeScheme.selectionBg
                        native.drawRect(leftX, topY, rightX, bottomY, bgPaint)
                    }

                    val secretSpan = rowSecrets?.firstOrNull { c in it.startCol..it.endCol && it.key !in revealedSecretKeys }
                    val cp = row.chars[c]
                    val visibleGlyph = cp > 32 && (fl and TerminalStyle.FLAG_HIDDEN) == 0
                    val effectiveFg = if (visibleGlyph && useMinContrast) {
                        TerminalStyle.ensureReadableContrast(fgColor, bgColor)
                    } else {
                        fgColor
                    }
                    val baseAlpha = if ((fl and TerminalStyle.FLAG_DIM) != 0) 145 else 255

                    if (visibleGlyph) {
                        if (secretSpan != null && activeSecretMode == SecretRedactionMode.ASTERISKS) {
                            textPaint.typeface = fonts.bold
                            textPaint.color = activeScheme.accent
                            textPaint.alpha = 200
                            native.drawText("*", leftX, baseY, textPaint)
                        } else if (secretSpan == null && useBoxDrawing && TerminalBoxDrawing.isSupported(cp) && row.clusters?.containsKey(c) != true) {
                            val prims = TerminalBoxDrawing.computePrimitives(cp, cellW, lineHeight)
                            for (p in prims) {
                                when (p) {
                                    is TerminalBoxDrawing.Primitive.FillRect -> {
                                        bgPaint.color = effectiveFg
                                        bgPaint.alpha = (baseAlpha * p.alphaScale).roundToInt().coerceIn(0, 255)
                                        native.drawRect(leftX + p.x0, topY + p.y0, leftX + p.x1, topY + p.y1, bgPaint)
                                    }
                                    is TerminalBoxDrawing.Primitive.CornerArc -> {
                                        decoPaint.color = effectiveFg
                                        decoPaint.alpha = baseAlpha
                                        decoPaint.strokeWidth = p.strokeWidth
                                        decoPaint.strokeCap = Paint.Cap.BUTT
                                        native.drawArc(
                                            leftX + p.cx - p.rx,
                                            topY + p.cy - p.ry,
                                            leftX + p.cx + p.rx,
                                            topY + p.cy + p.ry,
                                            p.startDeg,
                                            p.sweepDeg,
                                            false,
                                            decoPaint,
                                        )
                                    }
                                    is TerminalBoxDrawing.Primitive.PowerlineTriangle -> {
                                        triPath.reset()
                                        if (p.rightPointing) {
                                            triPath.moveTo(leftX, topY)
                                            triPath.lineTo(rightX, topY + lineHeight * 0.5f)
                                            triPath.lineTo(leftX, bottomY)
                                        } else {
                                            triPath.moveTo(rightX, topY)
                                            triPath.lineTo(leftX, topY + lineHeight * 0.5f)
                                            triPath.lineTo(rightX, bottomY)
                                        }
                                        triPath.close()
                                        bgPaint.color = effectiveFg
                                        bgPaint.alpha = baseAlpha
                                        native.drawPath(triPath, bgPaint)
                                    }
                                    is TerminalBoxDrawing.Primitive.PowerlineChevron -> {
                                        triPath.reset()
                                        if (p.rightPointing) {
                                            triPath.moveTo(leftX + p.strokeWidth * 0.5f, topY)
                                            triPath.lineTo(rightX - p.strokeWidth * 0.5f, topY + lineHeight * 0.5f)
                                            triPath.lineTo(leftX + p.strokeWidth * 0.5f, bottomY)
                                        } else {
                                            triPath.moveTo(rightX - p.strokeWidth * 0.5f, topY)
                                            triPath.lineTo(leftX + p.strokeWidth * 0.5f, topY + lineHeight * 0.5f)
                                            triPath.lineTo(rightX - p.strokeWidth * 0.5f, bottomY)
                                        }
                                        decoPaint.color = effectiveFg
                                        decoPaint.alpha = baseAlpha
                                        decoPaint.strokeWidth = p.strokeWidth
                                        native.drawPath(triPath, decoPaint)
                                    }
                                    is TerminalBoxDrawing.Primitive.HalfCircle -> {
                                        val startDeg = if (p.leftOpening) -90f else 90f
                                        val ovalL = if (p.leftOpening) leftX - cellW else leftX
                                        val ovalR = if (p.leftOpening) rightX else rightX + cellW
                                        if (p.filled) {
                                            bgPaint.color = effectiveFg
                                            bgPaint.alpha = baseAlpha
                                            native.drawArc(ovalL, topY, ovalR, bottomY, startDeg, 180f, true, bgPaint)
                                        } else {
                                            decoPaint.color = effectiveFg
                                            decoPaint.alpha = baseAlpha
                                            decoPaint.strokeWidth = p.strokeWidth
                                            native.drawArc(ovalL, topY, ovalR, bottomY, startDeg, 180f, false, decoPaint)
                                        }
                                    }
                                    is TerminalBoxDrawing.Primitive.SlantTriangle -> {
                                        triPath.reset()
                                        when (p.corner) {
                                            TerminalBoxDrawing.SLANT_BOTTOM_LEFT -> {
                                                triPath.moveTo(leftX, topY); triPath.lineTo(rightX, bottomY); triPath.lineTo(leftX, bottomY)
                                            }
                                            TerminalBoxDrawing.SLANT_BOTTOM_RIGHT -> {
                                                triPath.moveTo(rightX, topY); triPath.lineTo(rightX, bottomY); triPath.lineTo(leftX, bottomY)
                                            }
                                            TerminalBoxDrawing.SLANT_TOP_LEFT -> {
                                                triPath.moveTo(leftX, topY); triPath.lineTo(rightX, topY); triPath.lineTo(leftX, bottomY)
                                            }
                                            else -> {
                                                triPath.moveTo(leftX, topY); triPath.lineTo(rightX, topY); triPath.lineTo(rightX, bottomY)
                                            }
                                        }
                                        triPath.close()
                                        bgPaint.color = effectiveFg
                                        bgPaint.alpha = baseAlpha
                                        native.drawPath(triPath, bgPaint)
                                    }
                                }
                            }
                            bgPaint.alpha = 255
                            decoPaint.alpha = 255
                        } else {
                            textPaint.typeface = when {
                                bold && italic -> fonts.boldItalic
                                bold -> fonts.bold
                                italic -> fonts.italic
                                else -> fonts.regular
                            }
                            textPaint.color = if (secretSpan != null) 0xFF8B949E.toInt() else effectiveFg
                            textPaint.alpha = if (secretSpan != null) 180 else baseAlpha
                            native.drawText(row.glyphAt(c), leftX, baseY, textPaint)
                        }
                    }

                    // 彩色下划线 (SGR 58) / 实线 / 双线 / 波浪 / 点状 (4:4) / 虚线 (4:5) / 删除线 / 密钥删除线 / OSC 8 轻底线
                    val hasOsc8 = row.hyperlinkIds?.containsKey(c) == true
                    val secretStrike = secretSpan != null && activeSecretMode == SecretRedactionMode.STRIKETHROUGH
                    val hasDeco = hasOsc8 || secretStrike || (fl and (TerminalStyle.ALL_UNDERLINE_FLAGS or TerminalStyle.FLAG_STRIKETHROUGH)) != 0
                    if (hasDeco) {
                        val customUl = row.underlineColors?.get(c)?.let {
                            TerminalStyle.resolveColor(it, isFg = true, bold = false, scheme = activeScheme, paletteOverrides = palOverrides)
                        }
                        decoPaint.color = customUl ?: if (hasOsc8 && (fl and TerminalStyle.ALL_UNDERLINE_FLAGS) == 0) activeScheme.accent else effectiveFg
                        decoPaint.alpha = baseAlpha
                        decoPaint.strokeWidth = max(1.2f, textSizePx * 0.07f)
                        val uy = baseY + max(2f, fontMetrics.descent * 0.45f)
                        when {
                            (fl and TerminalStyle.FLAG_DOUBLE_UNDERLINE) != 0 -> {
                                native.drawLine(leftX, uy - 1.5f, rightX, uy - 1.5f, decoPaint)
                                native.drawLine(leftX, uy + 1.5f, rightX, uy + 1.5f, decoPaint)
                            }
                            (fl and TerminalStyle.FLAG_CURLY_UNDERLINE) != 0 -> {
                                wavePath.reset()
                                val mid = (leftX + rightX) * 0.5f
                                wavePath.moveTo(leftX, uy)
                                wavePath.quadTo((leftX + mid) * 0.5f, uy - 2.5f, mid, uy)
                                wavePath.quadTo((mid + rightX) * 0.5f, uy + 2.5f, rightX, uy)
                                native.drawPath(wavePath, decoPaint)
                            }
                            (fl and TerminalStyle.FLAG_DOTTED_UNDERLINE) != 0 -> {
                                decoPaint.pathEffect = dottedEffect
                                native.drawLine(leftX, uy, rightX, uy, decoPaint)
                                decoPaint.pathEffect = null
                            }
                            (fl and TerminalStyle.FLAG_DASHED_UNDERLINE) != 0 -> {
                                decoPaint.pathEffect = dashedEffect
                                native.drawLine(leftX, uy, rightX, uy, decoPaint)
                                decoPaint.pathEffect = null
                            }
                            (fl and TerminalStyle.FLAG_UNDERLINE) != 0 || hasOsc8 -> {
                                native.drawLine(leftX, uy, rightX, uy, decoPaint)
                            }
                        }
                        if ((fl and TerminalStyle.FLAG_STRIKETHROUGH) != 0 || secretStrike) {
                            decoPaint.color = if (secretStrike) 0xFF8B949E.toInt() else effectiveFg
                            val sy = topY + lineHeight * 0.5f
                            native.drawLine(leftX, sy, rightX, sy, decoPaint)
                        }
                        decoPaint.alpha = 255
                    }

                    c += cellSpan
                }
            }

            if (fracPx > 0.5f && snapshot.preTopRow != null) {
                drawTerminalRow(snapshot.preTopRow, snapshot.firstAbsRow - 1, padTopPx - lineHeight + fracPx)
            }
            for (r in 0 until snapshot.rows) {
                drawTerminalRow(snapshot.visibleRows[r], snapshot.firstAbsRow + r, padTopPx + r * lineHeight + fracPx)
            }

            // 3. 绘制选区两端可拖拽圆形手柄（Warp 风格）
            selection?.let { sel ->
                val norm = sel.normalized()
                val sr = norm[0] - snapshot.firstAbsRow
                val er = norm[2] - snapshot.firstAbsRow
                val rPx = max(5.5f, cellWidth * 0.34f)
                bgPaint.color = activeScheme.accent
                bgPaint.alpha = 255
                if (sr in 0 until snapshot.rows) {
                    val sx = padLeftPx + norm[1] * cellWidth
                    val sy = padTopPx + sr * lineHeight + fracPx
                    native.drawRect(sx - 1.2f, sy, sx + 1.2f, sy + lineHeight, bgPaint)
                    native.drawCircle(sx, sy, rPx, bgPaint)
                }
                if (er in 0 until snapshot.rows) {
                    val ex = padLeftPx + (norm[3] + 1) * cellWidth
                    val ey = padTopPx + (er + 1) * lineHeight + fracPx
                    native.drawRect(ex - 1.2f, ey - lineHeight, ex + 1.2f, ey, bgPaint)
                    native.drawCircle(ex, ey, rPx, bgPaint)
                }
            }

            // 4. 绘制 PowerShell / Windows Terminal 光标（`|` 竖线 / `█` 方块反色重绘文字 / `_` 下划线）
            val effectiveScroll = if (snapshot.useAltScreen) 0 else scrollOffsetLines
            if (effectiveScroll == 0 && snapshot.cursorVisible && cursorBlinkOn &&
                snapshot.cursorRow in 0 until snapshot.rows && snapshot.cursorCol in 0 until snapshot.cols
            ) {
                val cx = padLeftPx + snapshot.cursorCol * cellWidth
                val cy = padTopPx + snapshot.cursorRow * lineHeight + fracPx
                val curRowObj = snapshot.visibleRows[snapshot.cursorRow]
                val curWide = (curRowObj.flags[snapshot.cursorCol] and TerminalStyle.FLAG_WIDE_LEAD) != 0
                val curW = if (curWide) cellWidth * 2f else cellWidth
                val effectiveCursor = snapshot.cursorStyleOverride ?: cursorStyle
                bgPaint.color = activeCursorInt
                bgPaint.alpha = when (effectiveCursor) {
                    TerminalCursorStyle.BLOCK -> if (focusedPane) 230 else 85
                    else -> if (focusedPane) 255 else 95
                }
                when (effectiveCursor) {
                    TerminalCursorStyle.BAR -> {
                        val barW = max(2.4f, cellWidth * 0.16f)
                        native.drawRoundRect(cx, cy + 1f, cx + barW, cy + lineHeight - 1f, 1.5f, 1.5f, bgPaint)
                    }
                    TerminalCursorStyle.UNDERLINE -> {
                        val uH = max(2.5f, lineHeight * 0.12f)
                        native.drawRect(cx, cy + lineHeight - uH, cx + curW, cy + lineHeight, bgPaint)
                    }
                    TerminalCursorStyle.BLOCK -> {
                        native.drawRect(cx, cy, cx + curW, cy + lineHeight, bgPaint)
                        // 方块光标聚焦态：以高对比背景色重绘光标下文字，确保永远清晰可读
                        val curCp = curRowObj.chars[snapshot.cursorCol]
                        if (focusedPane && curCp > 32 && (curRowObj.flags[snapshot.cursorCol] and TerminalStyle.FLAG_HIDDEN) == 0) {
                            val cfl = curRowObj.flags[snapshot.cursorCol]
                            val cBold = (cfl and TerminalStyle.FLAG_BOLD) != 0
                            val cItalic = (cfl and TerminalStyle.FLAG_ITALIC) != 0
                            textPaint.typeface = when {
                                cBold && cItalic -> fonts.boldItalic
                                cBold -> fonts.bold
                                cItalic -> fonts.italic
                                else -> fonts.regular
                            }
                            textPaint.color = activeBgInt
                            textPaint.alpha = 255
                            native.drawText(curRowObj.glyphAt(snapshot.cursorCol), cx, cy + baselineOffset, textPaint)
                        }
                    }
                }
                bgPaint.alpha = 255
            }

            // 5. 视觉响铃瞬时边缘闪光（可选）
            if (visualBellFlash) {
                bgPaint.color = activeScheme.accent
                bgPaint.alpha = 46
                native.drawRect(0f, 0f, size.width, size.height, bgPaint)
                bgPaint.alpha = 255
            }
            native.restore()
        }

        // 4. Windows Terminal 风格右侧可拖拽回滚滚动槽（同步 120fps 浮点滑行位置，流式输出不断触）
        if (snapshot.scrollbackSize > 0 && !snapshot.useAltScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(16.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val sb = latestSnapshot.scrollbackSize
                            if (sb <= 0) return@detectDragGestures
                            val h = size.height.coerceAtLeast(1).toFloat()
                            val fracFromBottom = 1f - (change.position.y / h).coerceIn(0f, 1f)
                            val targetF = fracFromBottom * sb
                            scrollScope.launch { latestScrollAnim.snapTo(targetF) }
                            latestOnScrollOffset(targetF.roundToInt().coerceIn(0, sb))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val trackH = size.height
                    val ratio = (snapshot.rows.toFloat() / snapshot.totalLines.coerceAtLeast(1)).coerceIn(0.06f, 0.85f)
                    val thumbH = max(28f, trackH * ratio)
                    val travel = max(1f, trackH - thumbH)
                    val fromTopFrac = 1f - (scrollAnim.value / snapshot.scrollbackSize.coerceAtLeast(1))
                    val thumbY = fromTopFrac.coerceIn(0f, 1f) * travel
                    drawRoundRect(
                        color = scheme.accentColor.copy(alpha = if (scrollAnim.value > 0.2f) 0.72f else 0.34f),
                        topLeft = Offset(size.width - 7.dp.toPx(), thumbY),
                        size = Size(3.5.dp.toPx(), thumbH),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                }
            }
        }

        // 5. 中文输入法拼音组合实时浮标（仅聚焦窗格且输入法正在拼字时轻柔提示）
        if (focusedPane && composingText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.chromeColor.copy(alpha = 0.94f))
                    .border(0.75.dp, scheme.accentColor.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(composingText, fontFamily = Mono, fontSize = 11.sp, color = scheme.accentColor, maxLines = 1)
            }
        }

        // 6. 选区浮动操作栏（复制 / 取消）
        selection?.let { sel ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.chromeColor.copy(alpha = 0.96f))
                    .border(0.75.dp, scheme.accentColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "复制选中",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.accentColor,
                    modifier = Modifier.clickable {
                        val norm = sel.normalized()
                        val text = engine.extractText(norm[0], norm[1], norm[2], norm[3])
                        selection = null
                        if (text.isNotEmpty()) onCopyText(text)
                    },
                )
                Text(
                    "取消",
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.clickable { selection = null },
                )
            }
        }

        // 7. 向上回滚时的回到底部悬浮胶囊（备用屏自动隐藏）
        if (scrollOffsetLines > 0 && !snapshot.useAltScreen && snapshot.scrollbackSize > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(scheme.chromeColor.copy(alpha = 0.92f))
                    .border(0.75.dp, scheme.accentColor.copy(alpha = 0.75f), RoundedCornerShape(999.dp))
                    .clickable { onScrollOffsetChange(0) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("↓ 回到底部 (+${scrollOffsetLines}行)", fontFamily = Mono, fontSize = 10.5.sp, color = scheme.accentColor)
            }
        }
    }
}
