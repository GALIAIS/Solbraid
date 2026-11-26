package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.ssh.ModifierKeyState
import com.galiais.agent.engine.ssh.StickyModifierManager
import com.galiais.agent.engine.terminal.PsReadLinePredictor
import com.galiais.agent.engine.terminal.TerminalColorScheme
import com.galiais.agent.engine.terminal.TerminalKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val TermMuted = Color(0xFF8B949E)
internal val TermGreen = Color(0xFF4ADE80)
internal val TermAmber = Color(0xFFFBBF24)
internal val TermRed = Color(0xFFF87171)

/**
 * 自研 PowerShell / Termux 风格双排终端扩展键栏（固定高度零抖动）：
 * - PSReadLine 历史预测条已升级为画布内悬浮胶囊，键入过程中绝不改变扩展栏高度；
 * - 全部方向键与键盘按钮采用精确居中的矢量图标，键帽文字禁用字体上下额外留白实现像素级居中。
 */
@Composable
internal fun TerminalExtraKeysDock(
    scheme: TerminalColorScheme,
    usable: Boolean,
    imeOpen: Boolean,
    showSecondRow: Boolean,
    showFnRow: Boolean,
    showCommandBar: Boolean,
    commandLine: String,
    onCommandLineChange: (String) -> Unit,
    onSubmitCommandLine: () -> Unit,
    onHistoryPrev: () -> Unit,
    onHistoryNext: () -> Unit,
    modifiers: StickyModifierManager,
    onDispatchKey: (TerminalKey) -> Unit,
    onSendRaw: (String) -> Unit,
    onToggleIme: () -> Unit,
    onOpenSnippets: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val psHighlightTransform = remember(scheme) {
        VisualTransformation { input ->
            val raw = input.text
            val tokens = PsReadLinePredictor.tokenize(raw)
            val annotated = buildAnnotatedString {
                append(raw)
                for (t in tokens) {
                    val color = when (t.kind) {
                        PsReadLinePredictor.TokenKind.COMMAND -> Color(scheme.ansi16[11])
                        PsReadLinePredictor.TokenKind.FLAG -> Color(scheme.ansi16[7])
                        PsReadLinePredictor.TokenKind.STRING -> Color(scheme.ansi16[10])
                        PsReadLinePredictor.TokenKind.OPERATOR -> Color(scheme.ansi16[13])
                        PsReadLinePredictor.TokenKind.PATH -> Color(scheme.ansi16[14])
                        PsReadLinePredictor.TokenKind.VARIABLE -> Color(scheme.ansi16[12])
                        PsReadLinePredictor.TokenKind.NORMAL -> scheme.fgColor
                    }
                    addStyle(SpanStyle(color = color), t.start, t.end.coerceAtMost(raw.length))
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.chromeColor),
    ) {
        // 可选中文输入法友好 + PowerShell 语法着色命令行直发栏（全元素严格对齐 32dp）
        if (showCommandBar) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(scheme.borderColor))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.barColor)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                IconKeyCell(GaliaisIcons.ArrowUp, "上一条命令", scheme = scheme, enabled = usable, modifier = Modifier.size(32.dp), onClick = onHistoryPrev)
                IconKeyCell(GaliaisIcons.ArrowDown, "下一条命令", scheme = scheme, enabled = usable, modifier = Modifier.size(32.dp), onClick = onHistoryNext)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(scheme.bgColor)
                        .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (commandLine.isEmpty()) {
                        Text("PowerShell 着色命令行（支持中文直发）", fontFamily = Mono, fontSize = 11.sp, color = TermMuted, maxLines = 1)
                    }
                    BasicTextField(
                        value = commandLine,
                        onValueChange = onCommandLineChange,
                        enabled = usable,
                        singleLine = true,
                        textStyle = TextStyle(color = scheme.fgColor, fontFamily = Mono, fontSize = 12.5.sp),
                        cursorBrush = SolidColor(scheme.accentColor),
                        visualTransformation = psHighlightTransform,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSubmitCommandLine() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (usable && commandLine.isNotEmpty()) scheme.accentColor else scheme.keyColor)
                        .clickable(enabled = usable && commandLine.isNotEmpty(), onClick = onSubmitCommandLine)
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "执行",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (usable && commandLine.isNotEmpty()) scheme.bgColor else TermMuted,
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(scheme.borderColor))

        if (showFnRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.barColor)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifierKeyCell("SHIFT", scheme = scheme, state = modifiers.shiftState, enabled = usable, modifier = Modifier.weight(1.35f), onClick = modifiers::tapShift)
                val fnKeys = listOf(
                    "F1" to TerminalKey.F1, "F2" to TerminalKey.F2, "F3" to TerminalKey.F3, "F4" to TerminalKey.F4,
                    "F5" to TerminalKey.F5, "F6" to TerminalKey.F6, "F8" to TerminalKey.F8, "F10" to TerminalKey.F10, "F12" to TerminalKey.F12,
                )
                fnKeys.forEach { (label, key) ->
                    ExtraKeyCell(label, scheme = scheme, enabled = usable, fontSize = 9.5f, modifier = Modifier.weight(0.95f), onClick = { onDispatchKey(key) })
                }
                ExtraKeyCell("历史", scheme = scheme, enabled = true, accent = true, fontSize = 9.5f, modifier = Modifier.weight(1.1f), onClick = onOpenHistory)
            }
        }

        if (showSecondRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.barColor)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepeatableIconKeyCell(GaliaisIcons.ArrowLeft, "左方向键", scheme = scheme, enabled = usable, modifier = Modifier.weight(1f), onFire = { onDispatchKey(TerminalKey.LEFT) })
                RepeatableIconKeyCell(GaliaisIcons.ArrowDown, "下方向键", scheme = scheme, enabled = usable, modifier = Modifier.weight(1f), onFire = { onDispatchKey(TerminalKey.DOWN) })
                RepeatableIconKeyCell(GaliaisIcons.ArrowUp, "上方向键", scheme = scheme, enabled = usable, modifier = Modifier.weight(1f), onFire = { onDispatchKey(TerminalKey.UP) })
                RepeatableIconKeyCell(GaliaisIcons.ArrowForward, "右方向键", scheme = scheme, enabled = usable, modifier = Modifier.weight(1f), onFire = { onDispatchKey(TerminalKey.RIGHT) })
                ExtraKeyCell("HOME", scheme = scheme, enabled = usable, fontSize = 9.5f, modifier = Modifier.weight(1.1f), onClick = { onDispatchKey(TerminalKey.HOME) })
                ExtraKeyCell("END", scheme = scheme, enabled = usable, fontSize = 9.5f, modifier = Modifier.weight(1f), onClick = { onDispatchKey(TerminalKey.END) })
                ExtraKeyCell("PGUP", scheme = scheme, enabled = usable, fontSize = 9.5f, modifier = Modifier.weight(1.1f), onClick = { onDispatchKey(TerminalKey.PAGE_UP) })
                ExtraKeyCell("PGDN", scheme = scheme, enabled = usable, fontSize = 9.5f, modifier = Modifier.weight(1.1f), onClick = { onDispatchKey(TerminalKey.PAGE_DOWN) })
                ExtraKeyCell("^D", scheme = scheme, enabled = usable, fontSize = 10f, modifier = Modifier.weight(0.95f), onClick = { onSendRaw("\u0004") })
                ExtraKeyCell("片段", scheme = scheme, enabled = true, accent = true, fontSize = 10f, modifier = Modifier.weight(1.1f), onClick = onOpenSnippets)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.chromeColor)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtraKeyCell("ESC", scheme = scheme, enabled = usable, fontSize = 10f, modifier = Modifier.weight(1.05f), onClick = { onDispatchKey(TerminalKey.ESCAPE) })
            ExtraKeyCell("TAB", scheme = scheme, enabled = usable, fontSize = 10f, modifier = Modifier.weight(1.05f), onClick = { onDispatchKey(TerminalKey.TAB) })
            ModifierKeyCell("CTRL", scheme = scheme, state = modifiers.ctrlState, enabled = usable, modifier = Modifier.weight(1.2f), onClick = modifiers::tapCtrl)
            ModifierKeyCell("ALT", scheme = scheme, state = modifiers.altState, enabled = usable, modifier = Modifier.weight(1.05f), onClick = modifiers::tapAlt)
            ExtraKeyCell("^C", scheme = scheme, enabled = usable, danger = true, fontSize = 10.5f, modifier = Modifier.weight(0.95f), onClick = { onSendRaw("\u0003") })
            ExtraKeyCell("/", scheme = scheme, enabled = usable, modifier = Modifier.weight(0.8f), onClick = { onSendRaw("/") })
            ExtraKeyCell("-", scheme = scheme, enabled = usable, modifier = Modifier.weight(0.8f), onClick = { onSendRaw("-") })
            ExtraKeyCell("|", scheme = scheme, enabled = usable, modifier = Modifier.weight(0.8f), onClick = { onSendRaw("|") })
            ExtraKeyCell("~", scheme = scheme, enabled = usable, modifier = Modifier.weight(0.8f), onClick = { onSendRaw("~") })
            Box(
                modifier = Modifier
                    .weight(0.95f)
                    .height(31.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (imeOpen) scheme.accentColor.copy(alpha = 0.2f) else scheme.keyColor)
                    .border(0.75.dp, if (imeOpen) scheme.accentColor else scheme.borderColor, RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleIme),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GaliaisIcons.Keyboard,
                    contentDescription = if (imeOpen) "收起键盘" else "展开键盘",
                    tint = if (imeOpen) scheme.accentColor else scheme.fgColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun IconKeyCell(
    icon: ImageVector,
    description: String,
    scheme: TerminalColorScheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(scheme.keyColor)
            .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) scheme.fgColor else TermMuted.copy(alpha = 0.42f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ModifierKeyCell(
    label: String,
    scheme: TerminalColorScheme,
    state: ModifierKeyState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when (state) {
        ModifierKeyState.LOCKED -> scheme.accentColor
        ModifierKeyState.TRANSIENT -> scheme.accentColor.copy(alpha = 0.25f)
        ModifierKeyState.OFF -> scheme.keyColor
    }
    val borderColor = when (state) {
        ModifierKeyState.LOCKED -> scheme.accentColor
        ModifierKeyState.TRANSIENT -> scheme.accentColor.copy(alpha = 0.8f)
        ModifierKeyState.OFF -> scheme.borderColor
    }
    val textColor = when {
        !enabled -> TermMuted.copy(alpha = 0.45f)
        state == ModifierKeyState.LOCKED -> scheme.bgColor
        state == ModifierKeyState.TRANSIENT -> scheme.accentColor
        else -> scheme.fgColor
    }
    Box(
        modifier = modifier
            .height(31.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (state == ModifierKeyState.OFF) 0.75.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state == ModifierKeyState.LOCKED) "$label*" else label,
            style = TextStyle(
                fontFamily = Mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun ExtraKeyCell(
    label: String,
    scheme: TerminalColorScheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    danger: Boolean = false,
    fontSize: Float = 11.5f,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> TermMuted.copy(alpha = 0.42f)
        danger -> TermRed
        accent -> scheme.accentColor
        else -> scheme.fgColor
    }
    Box(
        modifier = modifier
            .height(31.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.keyColor)
            .border(0.75.dp, scheme.borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = Mono,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RepeatableIconKeyCell(
    icon: ImageVector,
    description: String,
    scheme: TerminalColorScheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFire: () -> Unit,
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestFire by rememberUpdatedState(onFire)
    val repeatScope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .height(31.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.keyColor)
            .border(0.75.dp, scheme.borderColor, RoundedCornerShape(6.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    latestFire()
                    val repeatJob = repeatScope.launch {
                        delay(340L)
                        while (latestEnabled) {
                            latestFire()
                            delay(65L)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) scheme.fgColor else TermMuted.copy(alpha = 0.42f),
            modifier = Modifier.size(14.dp),
        )
    }
}
