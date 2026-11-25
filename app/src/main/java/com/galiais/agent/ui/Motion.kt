package com.galiais.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** GALIAIS 统一动效规范：兼顾克制编辑风与 Material 3 强调缓动曲线。 */
object GaliaisMotion {
    const val NAV_ENTER_DURATION = 260
    const val NAV_EXIT_DURATION = 240
    const val NAV_DURATION = 260
    const val FADE_DURATION = 140
    const val POPOVER_DURATION = 180

    /** Material 3 标准流畅减速曲线：起步轻快、落位丝滑，消除生硬卡顿感。 */
    internal val NavEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /**
     * 原生级不透明推入/推出转场：
     * 进入子页从右侧完整滑入，底层页面微幅视差左移 -25%；
     * 返回上页时顶层页向右侧顺滑退场，底层页面从 -25% 复位，全程不透明零闪烁。
     */
    val navEnter: EnterTransition =
        slideInHorizontally(tween(NAV_ENTER_DURATION, easing = NavEasing)) { fullWidth -> fullWidth }

    val navExit: ExitTransition =
        slideOutHorizontally(tween(NAV_ENTER_DURATION, easing = NavEasing)) { fullWidth -> -(fullWidth * 0.25f).toInt() }

    val navPopEnter: EnterTransition =
        slideInHorizontally(tween(NAV_EXIT_DURATION, easing = NavEasing)) { fullWidth -> -(fullWidth * 0.25f).toInt() }

    val navPopExit: ExitTransition =
        slideOutHorizontally(tween(NAV_EXIT_DURATION, easing = NavEasing)) { fullWidth -> fullWidth }

    val popoverEnter: EnterTransition =
        slideInVertically(tween(POPOVER_DURATION, easing = FastOutSlowInEasing)) { it / 3 } +
            fadeIn(tween(160))

    val popoverExit: ExitTransition =
        slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it / 3 } +
            fadeOut(tween(140))

    val fabEnter: EnterTransition = scaleIn(tween(140), initialScale = 0.92f) + fadeIn(tween(140))
    val fabExit: ExitTransition = scaleOut(tween(100), targetScale = 0.92f) + fadeOut(tween(100))
}

/** 折叠区域只在显隐切换时改变高度；使用统一减速曲线与同步淡入淡出，父容器不应叠加 spacedBy 或 animateItem 避免跳变。 */
@Composable
fun AnimatedExpandable(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(220, easing = GaliaisMotion.NavEasing),
            expandFrom = Alignment.Top,
            clip = true,
        ) + fadeIn(tween(180, easing = GaliaisMotion.NavEasing)),
        exit = shrinkVertically(
            animationSpec = tween(190, easing = GaliaisMotion.NavEasing),
            shrinkTowards = Alignment.Top,
            clip = true,
        ) + fadeOut(tween(150, easing = GaliaisMotion.NavEasing)),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/** 箭头与折叠区域使用同一减速曲线与时长同步旋转。 */
@Composable
fun AnimatedChevron(
    expanded: Boolean,
    tint: Color = G.inkSubtle,
    size: Dp = 15.dp,
    modifier: Modifier = Modifier,
) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(210, easing = GaliaisMotion.NavEasing),
        label = "chevronAngle",
    )
    Icon(
        imageVector = GaliaisIcons.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size).graphicsLayer { rotationZ = angle },
    )
}

/** 三点波浪流式输入/思考指示器（纯 GPU graphicsLayer 绘制，零布局重排开销）。 */
@Composable
fun TypingWaveDots(color: Color = G.accent, dotSize: Dp = 5.5.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typingWave")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until 3) {
            val delayMs = i * 140
            val offsetY = transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at delayMs
                        -3.5f at (delayMs + 180)
                        0f at (delayMs + 380)
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dotOffset$i",
            )
            val alpha = transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0.35f at delayMs
                        1f at (delayMs + 180)
                        0.35f at (delayMs + 400)
                        0.35f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dotAlpha$i",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = offsetY.value * density
                        this.alpha = alpha.value
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

/** 实时跳动耗时计（如 4.2s / 1m 08s），用于活动中的 Agent 轮次顶条或思考状态。 */
@Composable
fun ElapsedTimerText(startedAt: Long, color: Color = G.inkMuted, modifier: Modifier = Modifier) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsedMs = (now - startedAt).coerceAtLeast(0L)
    val label = if (elapsedMs < 60_000L) {
        String.format(java.util.Locale.US, "%.1fs", elapsedMs / 1000.0)
    } else {
        val sec = elapsedMs / 1000L
        "${sec / 60}m ${String.format(java.util.Locale.US, "%02d", sec % 60)}s"
    }
    Text(
        text = label,
        style = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, fontFeatureSettings = "tnum"),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}
