package com.galiais.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.EngineKind

/**
 * 卡片：随「卡片材质」令牌切换 6 种风格（微光边 / 工业硬影 / 柔影浮卡 / M3 拼接 / 极细框 / 柔和填充）。
 * 显式传入 [background] 时视为语义着色卡片（警告、提示等）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CrispCard(
    modifier: Modifier = Modifier,
    radius: Dp = Dp.Unspecified,
    background: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    padding: PaddingValues? = null,
    groupPosition: CardGroupPos = CardGroupPos.SINGLE,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = D
    val g = G
    val shape = when {
        radius != Dp.Unspecified -> d.shape(radius)
        groupPosition != CardGroupPos.SINGLE -> d.cardShapeFor(groupPosition)
        else -> d.cardShape
    }
    val tinted = background != Color.Unspecified
    val bg: Color
    val border: Color?
    val elevation: Dp
    var topSpecular = false
    var hardwareShadow = false
    if (tinted) {
        bg = background
        border = borderColor.takeIf { it != Color.Unspecified }
        elevation = 0.dp
    } else when (d.cardStyle) {
        "filled", "grouped_expressive" -> {
            bg = g.surfaceFilled
            border = borderColor.takeIf { it != Color.Unspecified }
            elevation = 0.dp
        }
        "elevated" -> {
            bg = g.surfaceElevated
            border = borderColor.takeIf { it != Color.Unspecified } ?: if (g.dark) g.border.copy(alpha = 0.65f) else g.border.copy(alpha = 0.35f)
            elevation = if (g.dark) 2.dp else 5.dp
        }
        "glass_hairline" -> {
            bg = g.surface
            border = if (borderColor != Color.Unspecified) borderColor else g.border
            elevation = if (g.dark) 2.dp else 2.dp
            topSpecular = true
        }
        "hardware_tactile" -> {
            bg = g.surface
            border = if (borderColor != Color.Unspecified) borderColor else g.borderStrong
            elevation = 0.dp
            hardwareShadow = true
        }
        else -> {
            bg = if (d.material) g.canvas else g.surface
            border = if (borderColor != Color.Unspecified) borderColor else g.border
            elevation = 0.dp
        }
    }

    val outerMod = if (hardwareShadow) {
        val shadowColor = if (g.dark) Color.Black.copy(alpha = 0.85f) else g.ink.copy(alpha = 0.16f)
        val cornerPx = d.cardRadius
        modifier
            .fillMaxWidth()
            .padding(end = 2.dp, bottom = 2.dp)
            .drawBehind {
                val off = 2.5.dp.toPx()
                val cr = CornerRadius(cornerPx.toPx(), cornerPx.toPx())
                drawRoundRect(
                    color = shadowColor,
                    topLeft = Offset(off, off),
                    size = size,
                    cornerRadius = cr,
                )
            }
    } else {
        modifier.fillMaxWidth()
    }

    Surface(
        modifier = outerMod,
        shape = shape,
        color = bg,
        border = border?.let { BorderStroke(d.borderWidth, it) },
        shadowElevation = elevation,
        tonalElevation = 0.dp,
    ) {
        val clickModifier = if (onClick != null || onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
        } else Modifier
        val specularMod = if (topSpecular) {
            val hi = if (g.dark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.90f)
            Modifier.drawWithContent {
                drawContent()
                val inset = size.width * 0.08f
                drawLine(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = inset, endX = size.width - inset),
                    start = Offset(inset, 0.75.dp.toPx()),
                    end = Offset(size.width - inset, 0.75.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        } else Modifier
        Column(
            clickModifier
                .then(specularMod)
                .fillMaxWidth()
                .padding(padding ?: PaddingValues(d.space(16.dp))),
            content = content,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = D.hairline, color = G.border)
}

/** 页头下的分隔线：编辑风用细线，Material 3 用无分隔的平面层次。 */
@Composable
fun HeaderDivider() {
    if (!D.material) Hairline()
}

/** 固定 2dp 高度的顶栏底部分隔/加载槽位：对极短加载防抖 160ms，彻底杜绝进页瞬间闪出进度条与页面抖动。 */
@Composable
fun HeaderProgressSlot(loading: Boolean, modifier: Modifier = Modifier) {
    val showBar = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            kotlinx.coroutines.delay(160)
            showBar.value = true
        } else {
            showBar.value = false
        }
    }
    Box(modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomCenter) {
        HeaderDivider()
        if (showBar.value) {
            GaliaisProgressBar(modifier = Modifier.fillMaxWidth(), thin = true)
        }
    }
}

@Composable
fun PulsingDot(color: Color, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(Modifier.size(size).graphicsLayer { this.alpha = alpha.value }.background(color, CircleShape))
}

@Composable
fun agentAccent(engine: EngineKind): Color = when (engine.id) {
    "codex" -> G.codex
    "claude" -> G.claude
    else -> AgentCatalog.find(engine.id)?.let { Color(it.accentHex) } ?: G.accent
}

@Composable
fun agentTint(engine: EngineKind): Color = when (engine.id) {
    "codex" -> G.codexTint
    "claude" -> G.claudeTint
    else -> agentAccent(engine).copy(alpha = if (G.dark) 0.22f else 0.12f)
}

@Composable
fun EngineLabel(engine: EngineKind, modifier: Modifier = Modifier) {
    val fg = agentAccent(engine)
    val bg = agentTint(engine)
    Row(
        modifier = modifier.clip(D.shape(7.dp)).background(bg).padding(start = 4.dp, end = 7.5.dp, top = 2.5.dp, bottom = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.5.dp),
    ) {
        AgentAppIcon(engine = engine, size = 15.dp)
        Text(
            engine.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    fg: Color = G.inkMuted,
    bg: Color = G.surfaceSubtle,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = D.chipShape
    val material = D.material
    val base = modifier.clip(shape).background(bg)
        .let { if (border != null) it.border(if (material) 1.dp else 0.75.dp, border, shape) else it }
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = if (material) 12.dp else 10.dp, vertical = if (material) 6.dp else 5.dp)
    Box(base, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = if (material) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 可选中的筛选胶囊：固定宽度不跳动，切换干脆无双项残影或误触涟漪。 */
@Composable
fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = G
    val fg = when {
        D.material && selected -> g.onAccentContainer
        D.material -> g.inkMuted
        selected -> g.onAccent
        else -> g.ink
    }
    val bg = when {
        D.material && selected -> g.accentContainer
        D.material -> Color.Transparent
        selected -> g.accent
        else -> g.surfaceSubtle
    }
    val borderColor = when {
        selected && D.material -> g.accent.copy(alpha = 0.45f)
        selected -> g.accent
        D.material -> g.borderStrong
        else -> g.border
    }
    val shape = D.chipShape
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(if (D.material) 1.dp else 0.75.dp, borderColor, shape)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (D.material) 12.dp else 10.dp, vertical = if (D.material) 6.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (D.material) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PrimaryPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val d = D
    val g = G
    val tonal = d.buttonStyle == "tonal_soft"
    val tactile = d.buttonStyle == "chamfer_tactile"
    val container = if (tonal) g.accentContainer else g.accent
    val content = if (tonal) g.onAccentContainer else g.onAccent
    val border = when {
        !enabled -> null
        tonal -> BorderStroke(1.dp, g.accent.copy(alpha = 0.35f))
        tactile -> BorderStroke(1.25.dp, if (g.dark) Color.White.copy(alpha = 0.25f) else g.ink.copy(alpha = 0.32f))
        else -> null
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = d.buttonShape,
        border = border,
        elevation = if (tactile) ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp) else ButtonDefaults.buttonElevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = g.surfaceSubtle,
            disabledContentColor = g.inkSubtle,
        ),
        contentPadding = PaddingValues(horizontal = if (d.material) 24.dp else 18.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val d = D
    val g = G
    val tonal = d.buttonStyle == "tonal_soft"
    val tactile = d.buttonStyle == "chamfer_tactile"
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = d.buttonShape,
        border = BorderStroke(if (tactile) 1.25.dp else 1.dp, if (danger) g.danger.copy(alpha = 0.5f) else g.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (tonal) (if (danger) g.dangerTint else g.surfaceSubtle) else Color.Transparent,
            contentColor = if (danger) g.danger else if (d.material) g.accent else g.ink,
        ),
        contentPadding = PaddingValues(horizontal = if (d.material) 24.dp else 16.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    if (D.material) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = G.accent,
            modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = G.inkMuted,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
        )
    }
}

@Composable
fun galiaisTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = G.accent,
    unfocusedBorderColor = G.borderStrong,
    focusedLabelColor = G.accent,
    unfocusedLabelColor = G.inkMuted,
    focusedTextColor = G.ink,
    unfocusedTextColor = G.ink,
    cursorColor = G.accent,
    focusedContainerColor = if (D.material) Color.Transparent else G.surface,
    unfocusedContainerColor = if (D.material) Color.Transparent else G.surface,
)

/**
 * 统一分段标签栏：采用单一滑动高亮滑块（fillWidth=true）+ 无涟漪误闪，彻底消除切换时新旧两项同时闪烁/动画的残影，
 * 同时兼容 Material 3 色彩系统与编辑风视觉，并支持 compact / wrap-content 模式嵌入顶栏。
 */
@Composable
fun <T> SegmentedPillBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    compact: Boolean = false,
) {
    if (options.isEmpty()) return
    val material = D.material
    val outer = if (material) D.buttonShape else D.shape(12.dp)
    val inner = if (material) D.buttonShape else D.shape(9.dp)
    val trackBg = if (material) G.surfaceFilled else G.surfaceSubtle
    val trackBorder = if (material) G.borderStrong.copy(alpha = 0.65f) else G.border
    val activeBg = if (material) G.accentContainer else G.surface
    val activeFg = if (material) G.onAccentContainer else G.ink
    val activeBorder = if (material) G.accent.copy(alpha = 0.32f) else G.border
    val itemHeight = if (compact) 31.dp else 36.dp
    val gap = 3.dp
    val hPad = when {
        !fillWidth -> if (compact) 11.dp else 14.dp
        options.size >= 5 -> 3.dp
        options.size == 4 -> 5.dp
        else -> 8.dp
    }
    val textSize = when {
        compact -> 11.5.sp
        options.size >= 5 -> 11.5.sp
        options.size == 4 -> 12.sp
        else -> 12.5.sp
    }

    if (fillWidth) {
        val selectedIndex = options.indexOfFirst { it.first == selected }
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clip(outer)
                .background(trackBg)
                .border(if (material) 0.85.dp else 0.75.dp, trackBorder, outer)
                .padding(3.dp),
        ) {
            val count = options.size
            val slotWidth = ((maxWidth - gap * (count - 1)) / count).coerceAtLeast(0.dp)
            if (selectedIndex >= 0) {
                val indicatorOffset by animateDpAsState(
                    targetValue = (slotWidth + gap) * selectedIndex,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                    label = "segSlider",
                )
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(slotWidth)
                        .height(itemHeight)
                        .clip(inner)
                        .background(activeBg)
                        .border(0.75.dp, activeBorder, inner),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { (value, label) ->
                    val active = value == selected
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(itemHeight)
                            .clip(inner)
                            .selectable(selected = active, enabled = enabled, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(value) }
                            .padding(horizontal = hPad),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = textSize,
                            lineHeight = (textSize.value + 3f).sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) activeFg else G.inkMuted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        return
    }

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(outer)
            .background(trackBg)
            .border(if (material) 0.85.dp else 0.75.dp, trackBorder, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .clip(inner)
                    .background(if (active) activeBg else Color.Transparent)
                    .border(0.75.dp, if (active) activeBorder else Color.Transparent, inner)
                    .selectable(selected = active, enabled = enabled, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = hPad),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = textSize,
                    lineHeight = (textSize.value + 3f).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) activeFg else G.inkMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 统一横向可滚动标签栏：适用于 5 个及以上分类/子页（如资源库、主题分类、多产物切换），自动滚动居中选中项，切换干脆无双项闪烁。 */
@Composable
fun <T> GaliaisScrollableTabBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    val listState = rememberLazyListState()
    val selectedIndex = remember(options, selected) { options.indexOfFirst { it.first == selected } }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
        }
    }
    val material = D.material
    val outer = if (material) D.buttonShape else D.shape(12.dp)
    val inner = if (material) D.buttonShape else D.shape(9.dp)
    val trackBg = if (material) G.surfaceFilled else G.surfaceSubtle
    val trackBorder = if (material) G.borderStrong.copy(alpha = 0.65f) else G.border
    val activeBg = if (material) G.accentContainer else G.surface
    val activeFg = if (material) G.onAccentContainer else G.ink
    val activeBorder = if (material) G.accent.copy(alpha = 0.32f) else G.border

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(outer)
            .background(trackBg)
            .border(if (material) 0.85.dp else 0.75.dp, trackBorder, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(options, key = { idx, item -> "${item.first}-$idx" }) { _, (value, label) ->
            val active = value == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(inner)
                    .background(if (active) activeBg else Color.Transparent)
                    .border(0.75.dp, if (active) activeBorder else Color.Transparent, inner)
                    .selectable(selected = active, enabled = enabled, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) activeFg else G.inkMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun GaliaisSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    if (D.material) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = G.onAccent,
                checkedTrackColor = G.accent,
                checkedBorderColor = G.accent,
                uncheckedThumbColor = G.inkMuted,
                uncheckedTrackColor = G.surfaceSubtle,
                uncheckedBorderColor = G.borderStrong,
            ),
        )
    }
}

@Composable
fun GaliaisCheckbox(checked: Boolean) {
    Checkbox(
        checked = checked,
        onCheckedChange = null,
        colors = CheckboxDefaults.colors(checkedColor = G.accent, checkmarkColor = G.onAccent, uncheckedColor = G.borderStrong),
    )
}

@Composable
fun GaliaisRadio(selected: Boolean) {
    RadioButton(
        selected = selected,
        onClick = null,
        colors = RadioButtonDefaults.colors(selectedColor = G.accent, unselectedColor = G.borderStrong),
    )
}

@Composable
fun GaliaisProgressBar(modifier: Modifier = Modifier, thin: Boolean = true, progress: Float? = null) {
    val barModifier = modifier.fillMaxWidth().height(if (thin) 2.dp else 6.dp).let { if (thin) it else it.clip(RoundedCornerShape(99.dp)) }
    val track = if (D.material) G.accentContainer else G.surfaceSubtle
    if (progress != null) {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 320),
            label = "setupProgress",
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = barModifier,
            color = G.accent,
            trackColor = track,
        )
    } else {
        LinearProgressIndicator(
            modifier = barModifier,
            color = G.accent,
            trackColor = track,
        )
    }
}

/** 主操作悬浮按钮：编辑风为墨色胶囊；Material 3 为官方 ExtendedFAB（主色容器）。 */
@Composable
fun PrimaryFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (D.material) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier.semantics { contentDescription = text },
            shape = D.fabShape,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = { Icon(icon, contentDescription = null) },
            text = { Text(text, style = MaterialTheme.typography.labelLarge) },
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = D.fabShape,
            color = G.accent,
            contentColor = G.onAccent,
            shadowElevation = if (G.dark) 2.dp else 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 底部抽屉：统一形状、底色与拖动手柄。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaliaisSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = state,
        shape = D.sheetShape,
        containerColor = G.surface,
        contentColor = G.ink,
        scrimColor = Color.Black.copy(alpha = if (G.dark) 0.56f else 0.34f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = if (D.material) G.inkMuted.copy(alpha = 0.4f) else G.borderStrong) },
        content = content,
    )
}

@Composable
fun GaliaisSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = G.inverseSurface,
            contentColor = G.inverseOnSurface,
            shape = D.shape(if (D.material) 4.dp else 12.dp),
        )
    }
}

/** 代码正文：可选行号栏（不随横向滚动），字号随代码字号令牌缩放。 */
@Composable
fun CodeContent(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    baseSize: Float = 12.5f,
    contentPadding: Dp = 12.dp,
    softWrap: Boolean = false,
) {
    val d = D
    val fontSize = d.codeSize(baseSize)
    val lineHeight = d.codeSize(baseSize * 1.52f)
    Row(modifier.fillMaxWidth()) {
        if (d.codeLineNumbers && !softWrap) {
            val count = text.text.count { it == '\n' } + 1
            Text(
                (1..count).joinToString("\n"),
                fontFamily = Mono,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = G.codeMuted.copy(alpha = 0.7f),
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = contentPadding, top = contentPadding, bottom = contentPadding, end = 4.dp),
            )
        }
        Box(Modifier.weight(1f).let { m -> if (softWrap) m else m.horizontalScroll(rememberScrollState()) }) {
            Text(
                text,
                fontFamily = Mono,
                fontSize = fontSize,
                lineHeight = lineHeight,
                softWrap = softWrap,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

/** 统一的二级子页面脚手架：同时适配 Material 3 顶栏层次与 GALIAIS 极细分割线顶栏。 */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    loading: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize().galiaisCanvasBackground(),
        snackbarHost = { snackbar?.let { GaliaisSnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
        topBar = {
            Surface(color = if (D.material) MaterialTheme.colorScheme.surface else G.canvas.copy(alpha = 0.94f)) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (D.material) 64.dp else 58.dp)
                            .padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = G.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        actions()
                    }
                    HeaderProgressSlot(loading)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 设置页分类导航行：带着色图标角标、标题、实时状态摘要、可选右侧角标与右箭头。 */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: Color = G.accent,
    iconBg: Color = G.accentContainer,
    badge: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = D.space(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(D.shape(if (D.material) 12.dp else 10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = G.ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            TagPill(badge, fg = iconTint, bg = iconBg)
        }
        Icon(GaliaisIcons.ChevronRight, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(18.dp))
    }
}

/** 整行可点的设置开关行，支持可选左侧图标。 */
@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(D.shape(10.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(34.dp).clip(D.shape(9.dp)).background(G.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(17.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) G.ink else G.inkSubtle)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
        }
        GaliaisSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 主题自适应搜索栏（编辑风极细描边胶囊；Material 3 为 tonal 填充搜索条）。 */
@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val shape = D.shape(if (D.material) 28.dp else 12.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (D.material) MaterialTheme.colorScheme.surfaceContainerHigh else G.surface,
        border = if (D.material) null else BorderStroke(1.dp, G.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(GaliaisIcons.Search, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = G.ink),
                    cursorBrush = SolidColor(G.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(G.surfaceSubtle)
                        .semantics { contentDescription = "清除搜索" }
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GaliaisIcons.Close, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/** 回到底部悬浮按钮（长对话向上翻阅时居中悬浮于输入框上方；运行中带轻量脉冲提示）。 */
@Composable
fun ScrollToBottomFab(onClick: () -> Unit, modifier: Modifier = Modifier, running: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp).semantics { contentDescription = "回到底部" },
        shape = CircleShape,
        color = if (D.material) MaterialTheme.colorScheme.secondaryContainer else G.surface,
        contentColor = if (D.material) MaterialTheme.colorScheme.onSecondaryContainer else G.ink,
        border = if (D.material) null else BorderStroke(0.75.dp, G.borderStrong),
        shadowElevation = if (G.dark) 3.dp else 5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (running) 11.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (running) PulsingDot(G.accent, size = 6.dp)
            Icon(GaliaisIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(17.dp))
        }
    }
}


/**
 * 统一主题适配的下拉/长按悬浮菜单：完整匹配 7 维主题的圆角（`D.popupShape`）、边框材质、
 * 硬件硬边与顶部微光边，替换全局裸 M3 `DropdownMenu`。
 */
@Composable
fun GaliaisDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = G
    val d = D
    val isHardware = d.cardStyle == "hardware_tactile"
    val popupBg = when (d.cardStyle) {
        "elevated", "filled", "grouped_expressive" -> g.surfaceElevated
        else -> g.surface
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 168.dp),
        offset = offset,
        shape = d.popupShape,
        containerColor = popupBg,
        tonalElevation = 0.dp,
        shadowElevation = if (isHardware) 2.dp else if (g.dark) 8.dp else 10.dp,
        border = BorderStroke(d.borderWidth, if (isHardware) g.borderStrong else g.border),
        content = content,
    )
}

/**
 * 紧贴锚点的主题悬浮卡（用于聊天底部 `+`、权限、模型切换与上下文用量浮窗）：
 * 消除 M3 `DropdownMenu` 默认 48dp 顶部/底部留白，精准贴合锚点 6dp，自动防越界。
 */
@Composable
fun GaliaisAnchoredPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    preferAbove: Boolean = true,
    alignEnd: Boolean = false,
    minWidth: Dp = 200.dp,
    maxWidth: Dp = 316.dp,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val g = G
    val d = D
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val provider = remember(preferAbove, alignEnd, gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val desiredX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                val x = desiredX.coerceIn(marginPx, maxX)
                val aboveY = anchorBounds.top - popupContentSize.height - gapPx
                val belowY = anchorBounds.bottom + gapPx
                val y = when {
                    preferAbove && aboveY >= marginPx -> aboveY
                    !preferAbove && belowY + popupContentSize.height <= windowSize.height - marginPx -> belowY
                    aboveY >= marginPx -> aboveY
                    belowY + popupContentSize.height <= windowSize.height - marginPx -> belowY
                    preferAbove -> marginPx
                    else -> (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                }
                return IntOffset(x, y)
            }
        }
    }
    val isHardware = d.cardStyle == "hardware_tactile"
    val topSpecular = d.cardStyle == "glass_hairline"
    val popupBg = when (d.cardStyle) {
        "elevated", "filled", "grouped_expressive" -> g.surfaceElevated
        else -> g.surface
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val specularMod = if (topSpecular) {
            val hi = if (g.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
            Modifier.drawWithContent {
                drawContent()
                val inset = size.width * 0.10f
                drawLine(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.Transparent), startX = inset, endX = size.width - inset),
                    start = Offset(inset, 0.75.dp.toPx()),
                    end = Offset(size.width - inset, 0.75.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        } else Modifier

        val hardwareOuter = if (isHardware) {
            val shadowColor = if (g.dark) Color.Black.copy(alpha = 0.85f) else g.ink.copy(alpha = 0.16f)
            Modifier
                .widthIn(min = minWidth, max = maxWidth)
                .padding(end = 2.dp, bottom = 2.dp)
                .drawBehind {
                    val off = 2.5.dp.toPx()
                    val cr = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    drawRoundRect(
                        color = shadowColor,
                        topLeft = Offset(off, off),
                        size = size,
                        cornerRadius = cr,
                    )
                }
        } else {
            Modifier.widthIn(min = minWidth, max = maxWidth)
        }

        Surface(
            modifier = hardwareOuter,
            shape = d.popupShape,
            color = popupBg,
            border = BorderStroke(d.borderWidth, if (isHardware) g.borderStrong else g.border),
            shadowElevation = if (isHardware) 0.dp else if (g.dark) 10.dp else 12.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = specularMod.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/** 统一风格的悬浮菜单项：圆角内缩高亮态、可选图标/副标题/选中勾或危险态。 */
@Composable
fun GaliaisMenuRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    tintOverride: Color = Color.Unspecified,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val g = G
    val d = D
    val rowShape = d.shape(10.dp)
    val fg = when {
        !enabled -> g.inkSubtle
        tintOverride != Color.Unspecified -> tintOverride
        danger -> g.danger
        selected -> g.accent
        else -> g.ink
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (selected) g.accentContainer.copy(alpha = if (g.dark) 0.42f else 0.55f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (tintOverride != Color.Unspecified || danger || selected) fg else g.inkMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = g.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (selected) {
            Icon(GaliaisIcons.Check, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        }
    }
}
