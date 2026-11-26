package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.terminal.PsReadLinePredictor
import com.galiais.agent.engine.terminal.SecretRedactionMode
import com.galiais.agent.engine.terminal.TerminalColorScheme
import com.galiais.agent.engine.terminal.TerminalCursorStyle
import com.galiais.agent.engine.terminal.TerminalSearchOptions
import com.galiais.agent.engine.terminal.TerminalThemes
import kotlinx.coroutines.delay

/** 终端顶部全文回滚搜索条（支持 Aa 大小写 / .* 正则 / W 全词模式 + 统一 32dp 对齐）。 */
@Composable
internal fun TerminalSearchBar(
    scheme: TerminalColorScheme,
    query: String,
    options: TerminalSearchOptions,
    matchCount: Int,
    activeIndex: Int,
    onQueryChange: (String) -> Unit,
    onOptionsChange: (TerminalSearchOptions) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40L)
        runCatching { focusRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.barColor)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.bgColor)
                    .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
                    .clickable { runCatching { focusRequester.requestFocus() } }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        if (options.useRegex) "输入正则表达式检索回滚历史…" else "搜索终端输出与回滚历史…",
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = scheme.fgColor, fontFamily = Mono, fontSize = 12.sp),
                    cursorBrush = SolidColor(scheme.accentColor),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            SearchFlagChip("Aa", options.caseSensitive, scheme) {
                onOptionsChange(options.copy(caseSensitive = !options.caseSensitive))
            }
            SearchFlagChip(".*", options.useRegex, scheme) {
                onOptionsChange(options.copy(useRegex = !options.useRegex))
            }
            SearchFlagChip("W", options.wholeWord, scheme) {
                onOptionsChange(options.copy(wholeWord = !options.wholeWord))
            }
            Text(
                if (query.isBlank()) "" else if (matchCount == 0) "0/0" else "${activeIndex + 1}/$matchCount",
                fontFamily = Mono,
                fontSize = 10.sp,
                color = if (matchCount > 0) scheme.accentColor else Color(0xFF8B949E),
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.keyColor)
                    .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
                    .clickable(enabled = matchCount > 0, onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.ArrowUp, contentDescription = "上一匹配", tint = if (matchCount > 0) scheme.fgColor else Color(0xFF6E7681), modifier = Modifier.size(14.dp))
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.keyColor)
                    .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
                    .clickable(enabled = matchCount > 0, onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.ArrowDown, contentDescription = "下一匹配", tint = if (matchCount > 0) scheme.fgColor else Color(0xFF6E7681), modifier = Modifier.size(14.dp))
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.keyColor)
                    .border(0.75.dp, scheme.borderColor, RoundedCornerShape(7.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GaliaisIcons.Close, contentDescription = "关闭搜索", tint = Color(0xFF8B949E), modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun SearchFlagChip(
    label: String,
    active: Boolean,
    scheme: TerminalColorScheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) scheme.accentColor.copy(alpha = 0.22f) else scheme.keyColor)
            .border(0.75.dp, if (active) scheme.accentColor else scheme.borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Mono,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) scheme.accentColor else Color(0xFF8B949E),
        )
    }
}

/**
 * 零高度抖动的类 PowerShell PSReadLine 悬浮预测建议胶囊：
 * 叠加于终端视口底部，出现/消失绝不改变终端行列高度，彻底根治打字时触发 SIGWINCH 重排。
 */
@Composable
internal fun PsReadLineOverlayPill(
    scheme: TerminalColorScheme,
    liveDraft: String,
    prediction: String,
    onAccept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trimmed = liveDraft.trimStart()
    if (prediction.length <= trimmed.length) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.chromeColor.copy(alpha = 0.94f))
            .border(0.85.dp, scheme.accentColor.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .clickable { onAccept(prediction) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("PS>", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = scheme.accentColor)
        Text(
            text = buildAnnotatedString {
                val prefixLen = trimmed.length.coerceAtMost(prediction.length)
                pushStyle(SpanStyle(color = Color(scheme.ansi16[11]), fontWeight = FontWeight.SemiBold))
                append(prediction.substring(0, prefixLen))
                pop()
                pushStyle(SpanStyle(color = TermMuted))
                append(prediction.substring(prefixLen))
                pop()
            },
            fontFamily = Mono,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(scheme.keyColor)
                .border(0.75.dp, scheme.accentColor.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text("→ 补全", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = scheme.accentColor)
        }
    }
}

/** 分屏窗格顶部 24dp 紧凑状态栏（聚焦指示、动态标题、尺寸、方向切换、放大/还原与关闭）。 */
@Composable
internal fun TerminalSplitPaneHeader(
    title: String,
    dims: Pair<Int, Int>,
    scheme: TerminalColorScheme,
    focused: Boolean,
    isHorizontalSplit: Boolean,
    isZoomed: Boolean,
    broadcastActive: Boolean,
    onFocus: () -> Unit,
    onToggleAxis: () -> Unit,
    onToggleZoom: () -> Unit,
    onClosePane: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(if (focused) scheme.keyColor else scheme.barColor)
            .clickable(onClick = onFocus)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (focused) scheme.accentColor else TermMuted.copy(alpha = 0.5f)),
        )
        Text(
            title,
            fontFamily = Mono,
            fontSize = 10.5.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) scheme.fgColor else TermMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (broadcastActive) {
            Text("广播", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TermAmber)
        }
        Text("${dims.first}×${dims.second}", fontFamily = Mono, fontSize = 9.5.sp, color = TermMuted)
        Box(
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleAxis),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isHorizontalSplit) GaliaisIcons.SplitVertical else GaliaisIcons.SplitHorizontal,
                contentDescription = if (isHorizontalSplit) "切为上下分屏" else "切为左右分屏",
                tint = TermMuted,
                modifier = Modifier.size(13.dp),
            )
        }
        Box(
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleZoom),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isZoomed) GaliaisIcons.Minimize else GaliaisIcons.Maximize,
                contentDescription = if (isZoomed) "还原分屏" else "放大窗格",
                tint = if (isZoomed) scheme.accentColor else TermMuted,
                modifier = Modifier.size(13.dp),
            )
        }
        Box(
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClosePane),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GaliaisIcons.Close, contentDescription = "关闭此窗格", tint = TermMuted, modifier = Modifier.size(11.dp))
        }
    }
}

/** Windows Terminal / PowerShell / Warp 外观、渲染与安全隐私设置面板（12 套深浅主题 + 密钥脱敏 + 安全防护）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalAppearanceSheet(
    currentTheme: TerminalColorScheme,
    currentCursor: TerminalCursorStyle,
    boxDrawingEnabled: Boolean,
    minContrastEnabled: Boolean,
    secretRedactionMode: SecretRedactionMode,
    safeGuardEnabled: Boolean,
    bellHapticEnabled: Boolean,
    visualBellEnabled: Boolean,
    fontSize: Int,
    onSelectTheme: (TerminalColorScheme) -> Unit,
    onSelectCursor: (TerminalCursorStyle) -> Unit,
    onToggleBoxDrawing: (Boolean) -> Unit,
    onToggleMinContrast: (Boolean) -> Unit,
    onSelectSecretMode: (SecretRedactionMode) -> Unit,
    onToggleSafeGuard: (Boolean) -> Unit,
    onToggleBellHaptic: (Boolean) -> Unit,
    onToggleVisualBell: (Boolean) -> Unit,
    onFontDelta: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("终端外观与渲染设置", style = MaterialTheme.typography.titleMedium, color = G.ink)

            Text("配色方案（PowerShell / Warp 深浅 12 套 · CascadiaMonoNF）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalThemes.ALL.forEach { preset ->
                    val selected = preset.id == currentTheme.id
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) G.accentContainer else G.surfaceSubtle)
                            .border(if (selected) 1.25.dp else 0.75.dp, if (selected) G.accent else G.border, RoundedCornerShape(10.dp))
                            .clickable { onSelectTheme(preset) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(preset.bgColor)
                                .border(1.dp, preset.accentColor, CircleShape),
                        )
                        Text(
                            preset.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) G.accent else G.ink,
                        )
                    }
                }
            }

            Text("光标形态", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            SegmentedPillBar(
                options = TerminalCursorStyle.entries.map { it.id to it.label },
                selected = currentCursor.id,
                onSelect = { id -> onSelectCursor(TerminalCursorStyle.fromId(id)) },
            )

            Text("敏感凭据自动脱敏（Warp Secret Redaction · 点按单条可临时显隐）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            SegmentedPillBar(
                options = SecretRedactionMode.entries.map { it.id to it.label },
                selected = secretRedactionMode.id,
                onSelect = { id -> onSelectSecretMode(SecretRedactionMode.fromId(id)) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("字体大小 ${fontSize}sp", style = MaterialTheme.typography.bodyMedium, color = G.ink, modifier = Modifier.weight(1f))
                TagPill("A−", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { onFontDelta(-1) })
                TagPill("A+", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { onFontDelta(1) })
            }

            SettingsToggleRow(
                title = "像素级矢量盒绘（Warp 算法）",
                subtitle = "零重叠几何绘制制表符、圆角、块元素、Powerline 胶囊与斜三角，消除表格断缝",
                checked = boxDrawingEnabled,
                onCheckedChange = onToggleBoxDrawing,
            )
            SettingsToggleRow(
                title = "最小对比度护眼增强",
                subtitle = "保持色相自动提亮低对比 ANSI 文本（如黑底暗蓝），提升夜间可读性",
                checked = minContrastEnabled,
                onCheckedChange = onToggleMinContrast,
            )
            SettingsToggleRow(
                title = "高危命令二次确认拦截",
                subtitle = "提交 rm -rf /、dd、mkfs、chmod -R 777 / 等破坏指令前弹窗确认",
                checked = safeGuardEnabled,
                onCheckedChange = onToggleSafeGuard,
            )
            SettingsToggleRow(
                title = "终端响铃触觉反馈 (BEL)",
                subtitle = "收到远端响铃字符 (\\a) 时提供轻触震动提示",
                checked = bellHapticEnabled,
                onCheckedChange = onToggleBellHaptic,
            )
            SettingsToggleRow(
                title = "视觉响铃闪光提示",
                subtitle = "收到远端响铃时在终端画布柔光闪烁一次，静音环境不易遗漏",
                checked = visualBellEnabled,
                onCheckedChange = onToggleVisualBell,
            )
        }
    }
}

/** Warp 风格模糊历史检索面板（支持智能大小写、通配符 `*`/`?` 与命中字符高亮，一键填充或直接执行）。 */
@Composable
internal fun TerminalHistorySearchSheet(
    scheme: TerminalColorScheme,
    history: List<String>,
    onFill: (String) -> Unit,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, history) { PsReadLinePredictor.searchHistoryFuzzy(query, history, limit = 60) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50L)
        runCatching { focusRequester.requestFocus() }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("命令历史模糊检索 (Ctrl+Shift+R)", style = MaterialTheme.typography.titleMedium, color = G.ink)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(G.surfaceSubtle)
                    .border(0.75.dp, G.border, RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("输入关键字或通配符（如 doc*log / gitst）…", fontFamily = Mono, fontSize = 12.sp, color = G.inkMuted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = G.ink, fontFamily = Mono, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(scheme.accentColor),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (results.isEmpty()) {
                Text("未匹配到历史命令", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(results, key = { it.first }) { (cmd, hitIndices) ->
                        val hitSet = remember(hitIndices) { hitIndices.toHashSet() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(G.surfaceSubtle)
                                .border(0.75.dp, G.border, RoundedCornerShape(8.dp))
                                .clickable { onFill(cmd) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    for (i in cmd.indices) {
                                        if (i in hitSet) {
                                            pushStyle(SpanStyle(color = scheme.accentColor, fontWeight = FontWeight.Bold))
                                            append(cmd[i])
                                            pop()
                                        } else {
                                            append(cmd[i])
                                        }
                                    }
                                },
                                fontFamily = Mono,
                                fontSize = 11.5.sp,
                                color = G.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TagPill("执行", fg = scheme.bgColor, bg = scheme.accentColor, border = scheme.accentColor, onClick = { onRun(cmd) })
                        }
                    }
                }
            }
        }
    }
}
