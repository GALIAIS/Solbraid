package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全站统一顶栏原语（对齐 56dp 编辑风 / 64dp Material 3，消除 4 个一级 Tab 切换上下跳动）。
 */
@Composable
fun GaliaisTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    countLabel: String? = null,
    onBack: (() -> Unit)? = null,
    loading: Boolean = false,
    applyStatusBarPadding: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().let { if (applyStatusBarPadding) it.statusBarsPadding() else it }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (D.material) 64.dp else 56.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = G.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!countLabel.isNullOrBlank()) {
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = G.inkMuted,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = G.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
        HeaderProgressSlot(loading)
    }
}

/**
 * 轻量交互动作按钮（与纯只读 TagPill 语义彻底分离：内置 >= 36dp 触控高度、明确禁用态降透与可选图标）。
 */
@Composable
fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val g = G
    val fg = when {
        !enabled -> g.inkSubtle
        danger -> g.danger
        primary -> g.onAccent
        else -> g.ink
    }
    val bg = when {
        !enabled -> g.surfaceSubtle.copy(alpha = 0.65f)
        danger -> g.dangerTint
        primary -> g.accent
        else -> g.surfaceSubtle
    }
    val border = when {
        !enabled -> g.border.copy(alpha = 0.5f)
        danger -> g.danger.copy(alpha = 0.35f)
        primary -> null
        else -> g.border
    }
    val shape = D.chipShape
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .alpha(if (enabled) 1f else 0.58f)
            .clip(shape)
            .background(bg)
            .let { if (border != null) it.border(0.75.dp, border, shape) else it }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分区标题行（左侧标题强制 weight(1f) 单行省略，杜绝挤压右侧操作按钮）。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(title, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 统一底部抽屉脚手架：固定 titleLarge 顶栏 + 单层可滚正文 + Hairline 吸底操作栏 + 单次 ime.union(navigationBars) 避让。 */
@Composable
fun GaliaisSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    scrollable: Boolean = true,
    headerAction: @Composable (() -> Unit)? = null,
    footer: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                headerAction?.invoke()
                IconButton(onClick = onDismiss) {
                    Icon(GaliaisIcons.Close, contentDescription = "关闭", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (footer == null) 20.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
                content = content,
            )
            if (footer != null) {
                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = footer,
                )
            }
        }
    }
}

/** 统一主题化对话框（自动应用 G.surface、D.dialogShape、内容区滚动保护及规范禁用态按钮色）。 */
@Composable
fun GaliaisAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
    dismissText: String? = "取消",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = G.surface,
        shape = D.dialogShape,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (danger) G.danger else G.ink) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                val fg = when {
                    !confirmEnabled -> G.inkSubtle
                    danger -> G.danger
                    else -> G.accent
                }
                Text(confirmText, color = fg, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = dismissText?.let { label ->
            {
                TextButton(onClick = onDismissRequest) {
                    Text(label, color = G.inkMuted)
                }
            }
        },
    )
}

/** 统一主题化对话框（兼容自定义 confirmButton / dismissButton / text 插槽，自动应用主题表面色、圆角与内容滚动保护）。 */
@Composable
fun GaliaisAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    text: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = G.surface,
        shape = D.dialogShape,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor ?: G.ink,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                text()
            }
        },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/** 统一二次确认弹窗（区分危险红色与中性强调色）。 */
@Composable
fun GaliaisConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissText: String? = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = confirmText,
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
        danger = danger,
        dismissText = dismissText,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
    }
}

/** 统一空态占位卡片（居中图标 + 标题 + 说明 + 可选主操作）。 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CrispCard(modifier = modifier, padding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(D.shape(12.dp)).background(G.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(22.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, textAlign = TextAlign.Center)
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryPillButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/** 全站统一下拉选择字段（替代 4 种风格分裂的选择框）。 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "点击选择",
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = D.fieldShape,
            color = if (enabled) G.surface else G.surfaceSubtle,
            border = BorderStroke(1.dp, G.borderStrong.copy(alpha = if (enabled) 0.85f else 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isNotBlank()) G.ink else G.inkSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(GaliaisIcons.ChevronDown, contentDescription = null, tint = G.inkMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 终端暗色语义令牌：避免浅色主题下默认深墨色 G.accent 在 #0B0E14 黑曜石终端底色上隐身（对比度仅 1.1:1）。 */
internal fun darkTerminalGaliaisColors(): GaliaisColors = galiaisColors(
    com.galiais.agent.engine.ThemeConfig(darkTone = "obsidian", accent = 0xFF7AA2F7L),
    dark = true,
)
