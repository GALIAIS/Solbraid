package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.EngineKind

/** 实时预览：完整映射 7 维外观（画布纹理、卡片、列表布局、按钮、审批卡与底栏），所见即所得。 */
@Composable
fun ThemePreview() {
    var previewTab by remember { mutableStateOf(0) }
    val frameShape = D.shape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(frameShape)
            .galiaisCanvasBackground()
            .border(1.dp, G.borderStrong, frameShape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(7.dp)),
        ) {
            CrispCard(
                padding = PaddingValues(D.space(10.dp)),
                groupPosition = if (D.listLayout == "grouped") CardGroupPos.FIRST else CardGroupPos.SINGLE,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineLabel(EngineKind.CODEX)
                    Text("模块化主题预览", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TagPill("运行中", fg = G.success, bg = G.successTint)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(
                        shape = D.shape(16.dp),
                        color = G.userBubble,
                        border = if (G.userBubbleBorder == Color.Transparent) null else BorderStroke(0.75.dp, G.userBubbleBorder),
                    ) {
                        Text(
                            "7 个外观维度实时自由混搭",
                            style = MaterialTheme.typography.bodyMedium,
                            color = G.onUserBubble,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                CodeBlock("fun mix(axes: Int = 7) = \"17,000+ styles\"", "kotlin")
            }

            ApprovalCardContainer(
                badgeText = "审批示例",
                headerTrailing = { EngineLabel(EngineKind.CLAUDE) },
            ) {
                Text("./gradlew :app:assembleArm64Debug", fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PrimaryPillButton("批准执行", onClick = {})
                        SecondaryPillButton("拒绝", onClick = {}, danger = true)
                    }
                    GaliaisSwitch(checked = true, onCheckedChange = {})
                }
            }
        }
        GaliaisBottomBar(
            selectedIndex = previewTab,
            onSelect = { previewTab = it },
            previewOnly = true,
        )
    }
}

/** 设计语言选择卡：带固定示意插图，不受当前主题影响以便直观对比。 */
@Composable
fun StyleOptionCard(
    title: String,
    subtitle: String,
    material: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = G.dark
    val shape = D.shape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(G.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) G.accent else G.border, shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StyleIllustration(material = material, dark = dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected) {
                Box(Modifier.size(18.dp).background(G.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(GaliaisIcons.Check, contentDescription = null, tint = G.onAccent, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, minLines = 2)
    }
}

@Composable
private fun StyleIllustration(material: Boolean, dark: Boolean) {
    val bg: Color; val card: Color; val line: Color; val cta: Color; val ctaInk: Color; val cardBorder: Color?
    if (material) {
        bg = if (dark) Color(0xFF141218) else Color(0xFFFEF7FF)
        card = if (dark) Color(0xFF211F26) else Color(0xFFF3EDF7)
        line = if (dark) Color(0xFF49454F) else Color(0xFFCAC4D0)
        cta = if (dark) Color(0xFF4F378B) else Color(0xFFEADDFF)
        ctaInk = if (dark) Color(0xFFEADDFF) else Color(0xFF21005D)
        cardBorder = null
    } else {
        bg = if (dark) Color(0xFF0D0D10) else Color(0xFFF9F8F6)
        card = if (dark) Color(0xFF16161A) else Color.White
        line = if (dark) Color(0xFF2E2E36) else Color(0xFFE5E2DC)
        cta = if (dark) Color(0xFFF3F3F5) else Color(0xFF151514)
        ctaInk = if (dark) Color(0xFF111114) else Color.White
        cardBorder = if (dark) Color(0xFF2A2A32) else Color(0xFFE5E2DC)
    }
    val cardShape = RoundedCornerShape(if (material) 14.dp else 8.dp)
    Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(9.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(cardShape).background(card)
                .let { if (cardBorder != null) it.border(1.dp, cardBorder, cardShape) else it }
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.width(54.dp).height(6.dp).clip(CircleShape).background(line))
            Box(Modifier.fillMaxWidth(0.85f).height(5.dp).clip(CircleShape).background(line.copy(alpha = 0.7f)))
            Box(Modifier.fillMaxWidth(0.6f).height(5.dp).clip(CircleShape).background(line.copy(alpha = 0.7f)))
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(if (material) 10.dp else 99.dp))
                .background(cta)
                .padding(horizontal = if (material) 10.dp else 12.dp, vertical = if (material) 7.dp else 5.dp),
        ) {
            Text(if (material) "＋ 新建" else "新建", fontSize = 10.sp, color = ctaInk, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 圆形色板；[color] 为 null 时显示「自定义」加号。 */
@Composable
fun ColorSwatch(color: Color?, selected: Boolean, label: String, onClick: () -> Unit, size: Dp = 40.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.widthIn(min = 52.dp)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color ?: G.surfaceSubtle)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) G.ink else G.border, CircleShape)
                .semantics { contentDescription = label; this.selected = selected }
                .clickable(role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                color == null -> Icon(GaliaisIcons.Add, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(17.dp))
                selected -> Box(Modifier.size(18.dp).background(Color.White.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(GaliaisIcons.Check, contentDescription = null, tint = Color(0xFF111114), modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) G.ink else G.inkMuted, maxLines = 1)
    }
}

/** 色块选项卡（纸张色调 / 深色基调）。 */
@Composable
fun ToneOptionCard(label: String, canvas: Color, surface: Color, border: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = D.shape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) G.accent else G.border, shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp).clip(D.shape(8.dp)).background(canvas).padding(8.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(20.dp).clip(RoundedCornerShape(5.dp)).background(surface).border(1.dp, border, RoundedCornerShape(5.dp)))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) G.ink else G.inkMuted)
    }
}

/** 代码配色卡：以该配色真实渲染一小段示意代码。 */
@Composable
fun CodeThemeCard(palette: CodePalette, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = D.shape(12.dp)
    Column(
        modifier = Modifier
            .width(118.dp)
            .clip(shape)
            .background(palette.bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) G.accent else palette.border, shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(22.dp).height(5.dp).clip(CircleShape).background(palette.keyword))
                Box(Modifier.width(34.dp).height(5.dp).clip(CircleShape).background(palette.function))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(12.dp).height(5.dp))
                Box(Modifier.width(46.dp).height(5.dp).clip(CircleShape).background(palette.string))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(18.dp).height(5.dp).clip(CircleShape).background(palette.number))
                Box(Modifier.width(30.dp).height(5.dp).clip(CircleShape).background(palette.comment))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(palette.header).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 11.sp, color = palette.ink, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(GaliaisIcons.Check, contentDescription = null, tint = palette.ink, modifier = Modifier.size(11.dp))
        }
    }
}

/** 一键主题预设小卡片：展示底色、卡片色、强调色、明暗角标与名称（自定义槽位支持右上角删除）。 */
@Composable
fun ThemePresetCard(
    title: String,
    subtitle: String,
    bg: Color,
    card: Color,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    modeTag: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    val shape = D.shape(14.dp)
    Box(modifier = modifier.width(134.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) G.accentContainer.copy(alpha = if (G.dark) 0.32f else 0.45f) else G.surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) G.accent else G.border, shape)
                .semantics { this.selected = selected }
                .clickable(role = Role.RadioButton, onClick = onClick)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(D.shape(8.dp))
                    .background(bg)
                    .border(0.5.dp, G.border, D.shape(8.dp))
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.65f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(card)
                        .border(0.5.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(4.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                if (modeTag != null) {
                    Text(
                        modeTag,
                        fontSize = 9.sp,
                        color = if (ColorEngine.relativeLuminance(bg) > 0.36) Color(0xFF18181B) else Color(0xFFF3F4F8),
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(G.surface.copy(alpha = 0.94f))
                    .border(0.75.dp, G.borderStrong, CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.Close, contentDescription = "删除收藏主题", tint = G.inkMuted, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Material 3 色彩角色色带：展示当前方案的真实角色色。 */
@Composable
fun SchemeRolesStrip() {
    val s = MaterialTheme.colorScheme
    val roles = listOf(
        Triple("主色", s.primary, s.onPrimary),
        Triple("辅色", s.secondary, s.onSecondary),
        Triple("三色", s.tertiary, s.onTertiary),
        Triple("容器", s.primaryContainer, s.onPrimaryContainer),
        Triple("表面", s.surfaceContainerHighest, s.onSurface),
        Triple("错误", s.error, s.onError),
    )
    Row(Modifier.fillMaxWidth().clip(D.shape(12.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        roles.forEach { (name, bg, fg) ->
            Box(Modifier.weight(1f).height(52.dp).background(bg).padding(6.dp), contentAlignment = Alignment.BottomStart) {
                Text(name, fontSize = 10.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun Long.toHex(): String = "#%06X".format(this and 0xFFFFFF)

fun parseHexColor(text: String): Long? {
    val t = text.trim().removePrefix("#")
    if (!Regex("^[0-9a-fA-F]{6}$").matches(t)) return null
    return 0xFF000000 or t.toLong(16)
}

@Composable
fun HexColorDialog(title: String, initial: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var value by remember { mutableStateOf(initial.toHex()) }
    val parsed = parseHexColor(value)
    GaliaisAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = "应用",
        confirmEnabled = parsed != null,
        onConfirm = { parsed?.let(onConfirm) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(D.shape(12.dp))
                    .background(parsed?.let { Color(it.toInt()) } ?: G.surfaceSubtle)
                    .border(1.dp, G.border, D.shape(12.dp)),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(7) },
                label = { Text("HEX，如 #0F8A68") },
                singleLine = true,
                isError = parsed == null,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
