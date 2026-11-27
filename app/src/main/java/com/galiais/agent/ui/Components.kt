package com.galiais.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Velocity
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
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.EngineKind

/** 标记当前处于浮层容器（底部抽屉、对话框、悬浮气泡或父卡片内部），子卡片自动降级为平面细框以杜绝“阴影叠阴影”和滚动边界硬裁切。 */
val LocalFlatCardContext = compositionLocalOf { false }

/** 柔和的语义化阴影色（替代 Android 默认纯黑重影）。 */
internal fun softShadowAmbient(g: GaliaisColors): Color =
    if (g.dark) Color.Black.copy(alpha = 0.55f) else g.ink.copy(alpha = 0.22f)

internal fun softShadowSpot(g: GaliaisColors): Color =
    if (g.dark) Color.Black.copy(alpha = 0.70f) else g.ink.copy(alpha = 0.36f)

/**
 * 卡片：随「卡片材质」令牌切换 6 种风格（微光边 / 工业硬影 / 柔影浮卡 / M3 拼接 / 极细框 / 柔和填充）。
 * 显式传入 [background] 时视为语义着色卡片（警告、提示等）；在抽屉/弹窗/父卡片内自动平面化去投影。
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
    flat: Boolean = LocalFlatCardContext.current,
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
    } else if (flat) {
        bg = when (d.cardStyle) {
            "filled", "grouped_expressive" -> g.surfaceFilled
            else -> if (g.dark) lerp(g.surface, g.surfaceSubtle, 0.35f) else g.surface
        }
        border = borderColor.takeIf { it != Color.Unspecified }
            ?: if (d.cardStyle == "hardware_tactile") g.borderStrong else g.border
        elevation = 0.dp
    } else when (d.cardStyle) {
        "filled", "grouped_expressive" -> {
            bg = g.surfaceFilled
            border = borderColor.takeIf { it != Color.Unspecified }
            elevation = 0.dp
        }
        "elevated" -> {
            bg = g.surfaceElevated
            border = borderColor.takeIf { it != Color.Unspecified } ?: if (g.dark) g.border.copy(alpha = 0.65f) else g.border.copy(alpha = 0.48f)
            elevation = if (g.dark) 2.dp else 3.dp
        }
        "glass_hairline" -> {
            bg = g.surface
            border = if (borderColor != Color.Unspecified) borderColor else g.border
            elevation = if (g.dark) 1.dp else 1.5.dp
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

    val outerMod = when {
        hardwareShadow -> {
            val shadowColor = if (g.dark) Color.Black.copy(alpha = 0.85f) else g.ink.copy(alpha = 0.16f)
            val cornerPx = if (radius != Dp.Unspecified) d.radius(radius) else d.cardRadius
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
        }
        elevation > 0.dp -> {
            modifier
                .fillMaxWidth()
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = softShadowAmbient(g),
                    spotColor = softShadowSpot(g),
                )
        }
        else -> modifier.fillMaxWidth()
    }

    Surface(
        modifier = outerMod,
        shape = shape,
        color = bg,
        border = border?.let { BorderStroke(d.borderWidth, it) },
        shadowElevation = 0.dp,
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
        CompositionLocalProvider(LocalFlatCardContext provides true) {
            Column(
                clickModifier
                    .then(specularMod)
                    .fillMaxWidth()
                    .padding(padding ?: PaddingValues(d.space(16.dp))),
                content = content,
            )
        }
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

/** 统一语义状态枚举（方案 A：FA7 Pro+ / Pro 双层 Duotone 极简微章，形状 + 语义图标 + 柔和色阶协同表达）。 */
enum class StatusVisual {
    RUNNING,
    SUCCESS,
    WARNING,
    DANGER,
    STOPPED,
    SKIPPED,
    PENDING,
}

/** Duotone 轨道微弧旋转 / 真实进度环：progress 非空时按真实比例绘制，为空时 60/120fps 平滑旋转。 */
@Composable
fun StatusProgressArc(
    color: Color,
    size: Dp = 14.dp,
    progress: Float? = null,
    strokeWidth: Dp = 1.9.dp,
    modifier: Modifier = Modifier,
) {
    if (progress != null) {
        val animatedProgress by animateFloatAsState(
            targetValue = progress.coerceIn(0.06f, 1f),
            animationSpec = tween(260),
            label = "statusArcProgress",
        )
        Canvas(modifier = modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val pad = stroke.width / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = Offset(pad, pad)
            drawArc(color = color.copy(alpha = 0.24f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(color = color, startAngle = -90f, sweepAngle = animatedProgress * 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
    } else {
        val transition = rememberInfiniteTransition(label = "statusSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart),
            label = "statusSpinAngle",
        )
        Canvas(modifier = modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val pad = stroke.width / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = Offset(pad, pad)
            drawArc(color = color.copy(alpha = 0.24f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(color = color, startAngle = angle - 90f, sweepAngle = 115f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
    }
}

/** 统一 Duotone 语义状态微章（无边框轻盈嵌入行内、列表与胶囊）。 */
@Composable
fun StatusGlyph(
    visual: StatusVisual,
    color: Color,
    size: Dp = 15.dp,
    progress: Float? = null,
    modifier: Modifier = Modifier,
) {
    when (visual) {
        StatusVisual.RUNNING -> StatusProgressArc(
            color = color,
            size = (size - 1.dp).coerceAtLeast(10.dp),
            progress = progress,
            strokeWidth = if (size <= 13.dp) 1.7.dp else 1.9.dp,
            modifier = modifier,
        )
        StatusVisual.SUCCESS -> Icon(GaliaisIcons.CheckCircle, contentDescription = null, tint = color, modifier = modifier.size(size))
        StatusVisual.WARNING -> Icon(GaliaisIcons.AlertTriangle, contentDescription = null, tint = color, modifier = modifier.size(size))
        StatusVisual.DANGER -> Icon(GaliaisIcons.XCircle, contentDescription = null, tint = color, modifier = modifier.size(size))
        StatusVisual.STOPPED -> Icon(GaliaisIcons.PowerOff, contentDescription = null, tint = color, modifier = modifier.size(size))
        StatusVisual.SKIPPED -> Icon(GaliaisIcons.MinusCircle, contentDescription = null, tint = color, modifier = modifier.size(size))
        StatusVisual.PENDING -> Icon(GaliaisIcons.CirclePending, contentDescription = null, tint = color, modifier = modifier.size(size))
    }
}

/** 方案 A 语义状态胶囊：柔和浅底 + 0.75dp 语义微边 + FA7 Duotone 微章或真实进度环 + 文本。 */
@Composable
fun StatusBadge(
    label: String,
    visual: StatusVisual,
    color: Color,
    background: Color,
    progress: Float? = null,
    textColor: Color = color,
    borderAlpha: Float = 0.24f,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(D.shape(999.dp))
            .background(background)
            .border(0.75.dp, color.copy(alpha = borderAlpha), D.shape(999.dp))
            .padding(horizontal = 9.5.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.5.dp),
    ) {
        StatusGlyph(visual = visual, color = color, size = 14.dp, progress = progress)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 图表图例专用圆角色条（替代孤立小圆点，与分段进度条语言呼应）。 */
@Composable
fun ColorSwatchPill(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(10.dp).height(5.dp).clip(RoundedCornerShape(999.dp)).background(color))
}

/** 兼容旧调用的运行态微弧指示器（已升级为 Duotone 细轨道旋转环，不再是闪烁圆点）。 */
@Composable
fun PulsingDot(color: Color, size: Dp = 7.dp) {
    StatusProgressArc(
        color = color,
        size = (size + 5.dp).coerceIn(11.dp, 15.dp),
        strokeWidth = 1.75.dp,
    )
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
        modifier = modifier.defaultMinSize(minHeight = 42.dp),
        shape = d.buttonShape,
        border = border,
        elevation = if (tactile) ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp) else ButtonDefaults.buttonElevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = g.surfaceSubtle,
            disabledContentColor = g.inkSubtle,
        ),
        contentPadding = PaddingValues(horizontal = if (d.material) 18.dp else 16.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
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
        modifier = modifier.defaultMinSize(minHeight = 42.dp),
        shape = d.buttonShape,
        border = BorderStroke(if (tactile) 1.25.dp else 1.dp, if (danger) g.danger.copy(alpha = 0.5f) else g.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (tonal) (if (danger) g.dangerTint else g.surfaceSubtle) else Color.Transparent,
            contentColor = if (danger) g.danger else if (d.material) g.accent else g.ink,
        ),
        contentPadding = PaddingValues(horizontal = if (d.material) 18.dp else 16.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
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
    val outerRadius = if (material) D.radius(12.dp) else D.radius(12.dp)
    val outer = RoundedCornerShape(outerRadius)
    val inner = RoundedCornerShape((outerRadius - 3.dp).coerceAtLeast(2.dp))
    val trackBg = if (material) G.surfaceFilled else G.surfaceSubtle
    val trackBorder = if (material) G.borderStrong.copy(alpha = 0.65f) else G.border
    val activeBg = if (material) G.accentContainer else G.surface
    val activeFg = if (material) G.onAccentContainer else G.ink
    val activeBorder = if (material) G.accent.copy(alpha = 0.32f) else G.border
    val itemHeight = if (compact) 32.dp else 38.dp
    val gap = 3.dp
    val hPad = when {
        !fillWidth -> if (compact) 11.dp else 14.dp
        options.size >= 5 -> 3.dp
        options.size == 4 -> 4.dp
        else -> 8.dp
    }
    val textSize = when {
        compact -> 11.5.sp
        options.size >= 5 -> 11.5.sp
        options.size == 4 -> 12.sp
        else -> 12.5.sp
    }
    val labelFontFamily = MaterialTheme.typography.labelMedium.fontFamily

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
                            fontFamily = labelFontFamily,
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
            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp)) },
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
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 拦截内部滚动容器向上滑到底部后的负向余量，避免 Material3 1.5 ModalBottomSheet 的
 * AnchoredDraggable 收到上冲嵌套滚动增量引发抖动（b/486562294），同时完全保留向下拖拽关闭手势。
 */
private val BottomSheetUpwardOverscrollGuard = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

/** 底部抽屉：统一形状、底色与拖动手柄。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaliaisSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.statusBarsPadding(),
        sheetState = state,
        shape = D.sheetShape,
        containerColor = G.surface,
        contentColor = G.ink,
        scrimColor = Color.Black.copy(alpha = if (G.dark) 0.56f else 0.34f),
        // Material3 1.5 默认 modalWindowInsets 包含 Top 状态栏并通过 SheetWindowInsets(state) 动态读取 offset，
        // 当抽屉内容高度接近屏幕高度时会形成 offset ↔ 顶部内边距 ↔ 实测高度 ↔ Expanded 锚点 的高频上下振荡闭环；
        // 外层使用静态 statusBarsPadding() 避让状态栏，内容区关闭动态 insets（底部由各抽屉内部显式处理 navigationBarsPadding/imePadding）。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = { BottomSheetDefaults.DragHandle(color = if (D.material) G.inkMuted.copy(alpha = 0.4f) else G.borderStrong) },
    ) {
        CompositionLocalProvider(LocalFlatCardContext provides true) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(BottomSheetUpwardOverscrollGuard),
                content = content,
            )
        }
    }
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
    val totalLines = remember(text.text) { text.text.count { it == '\n' } + 1 }
    val expandedLongState = remember { androidx.compose.runtime.mutableStateOf(false) }
    val expandedLong = expandedLongState.value
    val collapseLimit = 80
    val shouldCollapse = totalLines > collapseLimit && !expandedLong
    val displayText = remember(text, shouldCollapse) {
        if (!shouldCollapse) text
        else {
            var cut = 0
            var seen = 0
            val raw = text.text
            while (cut < raw.length && seen < collapseLimit) {
                val nl = raw.indexOf('\n', cut)
                if (nl < 0) { cut = raw.length; break }
                cut = nl + 1
                seen++
            }
            text.subSequence(0, cut.coerceAtMost(text.length))
        }
    }
    val shownLines = if (shouldCollapse) collapseLimit else totalLines
    val gutterText = remember(shownLines) { (1..shownLines).joinToString("\n") }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            if (d.codeLineNumbers && !softWrap) {
                Text(
                    gutterText,
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
                    displayText,
                    fontFamily = Mono,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    softWrap = softWrap,
                    modifier = Modifier.padding(contentPadding),
                )
            }
        }
        if (totalLines > collapseLimit) {
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(G.codeHeader.copy(alpha = 0.55f))
                    .clickable { expandedLongState.value = !expandedLongState.value }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expandedLong) "收起长代码（仅显示前 $collapseLimit 行）" else "展开全部 $totalLines 行代码（剩余 ${totalLines - collapseLimit} 行）",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = G.accent,
                )
            }
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
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
            Surface(color = if (D.material) MaterialTheme.colorScheme.surface else G.canvas) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (D.material) 64.dp else 56.dp)
                            .padding(start = 4.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
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
        val bodyMod = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(contentPadding)
        Column(
            modifier = bodyMod,
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            content()
            if (scrollable) Spacer(Modifier.height(24.dp))
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
                .size(36.dp)
                .clip(D.shape(if (D.material) 12.dp else 10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
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
                maxLines = 2,
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
                modifier = Modifier.size(36.dp).clip(D.shape(if (D.material) 12.dp else 10.dp)).background(G.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) G.ink else G.inkSubtle)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
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
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(GaliaisIcons.Search, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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
                        .size(30.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "清除搜索" }
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape).background(G.surfaceSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GaliaisIcons.Close, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(12.dp))
                    }
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
        modifier = modifier
            .height(34.dp)
            .widthIn(min = 34.dp)
            .semantics { contentDescription = "回到底部" },
        shape = CircleShape,
        color = if (D.material) MaterialTheme.colorScheme.secondaryContainer else G.surface,
        contentColor = if (D.material) MaterialTheme.colorScheme.onSecondaryContainer else G.ink,
        border = if (D.material) null else BorderStroke(0.75.dp, G.borderStrong),
        shadowElevation = if (G.dark) 3.dp else 5.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (running) 11.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
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
        shadowElevation = if (isHardware) 2.dp else if (g.dark) 5.dp else 6.dp,
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
                .padding(horizontal = 4.dp, vertical = 4.dp)
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
            Modifier
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .widthIn(min = minWidth, max = maxWidth)
                .shadow(
                    elevation = if (g.dark) 6.dp else 8.dp,
                    shape = d.popupShape,
                    clip = false,
                    ambientColor = softShadowAmbient(g),
                    spotColor = softShadowSpot(g),
                )
        }

        Surface(
            modifier = hardwareOuter,
            shape = d.popupShape,
            color = popupBg,
            border = BorderStroke(d.borderWidth, if (isHardware) g.borderStrong else g.border),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            CompositionLocalProvider(LocalFlatCardContext provides true) {
                Column(
                    modifier = specularMod
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    content = content,
                )
            }
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
