package com.galiais.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.R
import com.galiais.agent.engine.EngineKind

/**
 * 真实官方 Agent 应用图标组件：
 * 为内置的 8 款主流 Agent（Codex、Claude Code、Antigravity、Gemini CLI、OpenCode、Qwen Code、Goose、Aider）
 * 渲染各自官方原色高清图标与自适应明暗模式的圆角应用底座，自定义 Agent 则渲染渐变品牌徽标。
 */
@Composable
fun AgentAppIcon(
    engine: EngineKind,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val dark = G.dark
    val shape = RoundedCornerShape((size.value * 0.24f).coerceAtLeast(3.5f).dp)
    val borderStroke = G.border.copy(alpha = if (dark) 0.85f else 0.7f)

    data class Spec(val resId: Int, val bg: Color, val insetFraction: Float)

    val spec: Spec? = when (engine.id) {
        "codex" -> Spec(R.drawable.agent_codex, Color.White, 0f)
        "claude" -> Spec(R.drawable.agent_claude, if (dark) Color(0xFF261B17) else Color(0xFFFFF6F1), 0.17f)
        "antigravity" -> Spec(R.drawable.agent_antigravity, if (dark) Color(0xFF171C26) else Color(0xFFF7FAFF), 0.16f)
        "gemini" -> Spec(R.drawable.agent_gemini, Color(0xFF1E1E2E), 0f)
        "opencode" -> Spec(R.drawable.ic_agent_opencode, Color(0xFF131010), 0f)
        "qwen" -> Spec(R.drawable.agent_qwen, if (dark) Color(0xFF151B36) else Color(0xFFF2F5FF), 0.17f)
        "goose" -> Spec(R.drawable.ic_agent_goose, Color(0xFF111827), 0.17f)
        "aider" -> Spec(R.drawable.agent_aider, Color(0xFF052E1E), 0f)
        else -> null
    }

    if (spec != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(spec.bg)
                .border(0.5.dp, borderStroke, shape)
                .let { if (spec.insetFraction > 0f) it.padding(size * spec.insetFraction) else it },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(spec.resId),
                contentDescription = engine.label,
                contentScale = if (spec.insetFraction > 0f) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val accent = agentAccent(engine)
        val initial = engine.label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f))))
                .border(0.5.dp, borderStroke, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.46f).sp,
                    lineHeight = (size.value * 0.46f).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        }
    }
}

/** 带真实官方 Agent 图标的可选胶囊（用于新建会话单选 / 多 Agent 并行对比多选）。 */
@Composable
fun AgentSelectableChip(
    engine: EngineKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = G
    val fg = when {
        D.material && selected -> g.onAccentContainer
        D.material -> g.ink
        selected -> g.onAccent
        else -> g.ink
    }
    val bg = when {
        D.material && selected -> g.accentContainer
        D.material -> g.surfaceSubtle
        selected -> g.accent
        else -> g.surfaceSubtle
    }
    val shape = D.chipShape
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .let { if (selected) it else it.border(if (D.material) 1.dp else 0.75.dp, if (D.material) g.borderStrong else g.border, shape) }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentAppIcon(engine = engine, size = 18.dp)
        Text(
            text = engine.label,
            style = if (D.material) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}
