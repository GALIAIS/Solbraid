package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.STREAM_ANIM_INSTANT
import com.galiais.agent.engine.STREAM_SPEED_BALANCED
import com.galiais.agent.engine.STREAM_SPEED_FAST
import com.galiais.agent.engine.STREAM_SPEED_GENTLE
import com.galiais.agent.engine.USER_RENDER_ALWAYS_PLAIN
import com.galiais.agent.engine.USER_RENDER_ALWAYS_RICH
import com.galiais.agent.engine.USER_RENDER_FOLLOW_EDITOR

/** 常规设置子页面：集中管理用户编辑器（纯文本 / 自动渲染）、消息渲染、工作区编辑与通用交互偏好。 */
@Composable
fun GeneralSettingsScreen(
    app: GaliaisApplication,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val settings by app.settings.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    SubPageScaffold(title = "常规", onBack = onBack, snackbar = snackbar) {
        // 1. 用户编辑器与输入渲染
        SectionLabel("用户编辑器")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsToggleRow(
                    title = "启用纯文本编辑器",
                    subtitle = "关闭语法高亮、内容预览、格式工具栏和符号补全。",
                    checked = settings.plainTextEditor,
                    onCheckedChange = app.settings::setPlainTextEditor,
                )

                Column(Modifier.fillMaxWidth()) {
                    AnimatedExpandable(expanded = !settings.plainTextEditor) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Hairline()
                            SettingsToggleRow(
                                title = "输入语法高亮",
                                subtitle = "在输入时突出显示 Markdown、代码、公式、文件引用和命令。",
                                checked = settings.editorSyntaxHighlight,
                                onCheckedChange = app.settings::setEditorSyntaxHighlight,
                            )
                            Hairline()
                            SettingsToggleRow(
                                title = "自动预览",
                                subtitle = "输入公式、图表或 Markdown 时显示预览。",
                                checked = settings.editorAutoPreview,
                                onCheckedChange = app.settings::setEditorAutoPreview,
                            )
                            Hairline()
                            SettingsToggleRow(
                                title = "显示快捷格式工具栏",
                                subtitle = "在聊天输入框上方显示粗体、代码、代码块、数学公式、引用与全屏编辑快捷按钮。",
                                checked = settings.editorFormatToolbar,
                                onCheckedChange = app.settings::setEditorFormatToolbar,
                            )
                            Hairline()
                            SettingsToggleRow(
                                title = "括号与引号自动配对",
                                subtitle = "输入 ( [ { \" ` $ 时自动补全右侧符号并将光标置于中间，纯文本模式下自动禁用。",
                                checked = settings.editorAutoPair,
                                onCheckedChange = app.settings::setEditorAutoPair,
                            )
                            Hairline()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("长文本切换为纯文本", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                Text(
                                    "输入内容超过所选长度时，暂时关闭格式渲染。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = G.inkMuted,
                                )
                                SegmentedPillBar(
                                    options = listOf(4000 to "4K 字", 8000 to "8K 字", 20000 to "20K 字", 0 to "不限制"),
                                    selected = settings.editorPlainFallbackThreshold,
                                    onSelect = app.settings::setEditorPlainFallbackThreshold,
                                )
                            }
                        }
                    }
                }

                Hairline()
                SettingsToggleRow(
                    title = "输入框使用等宽字体",
                    subtitle = "采用等宽字体编写提示词，方便对齐代码缩进、JSON 与 Markdown 表格。",
                    checked = settings.editorMonospace,
                    onCheckedChange = app.settings::setEditorMonospace,
                )
                Hairline()
                SettingsToggleRow(
                    title = "显示输入字数与 Token 估算",
                    subtitle = "在输入框右上角实时显示字符数、行数与估算 Token 数，并保留全屏编辑入口。",
                    checked = settings.showComposerWordCount,
                    onCheckedChange = app.settings::setShowComposerWordCount,
                )
                Hairline()
                SettingsToggleRow(
                    title = "Enter 直接发送",
                    subtitle = "使用键盘时按 Enter 发送消息，Shift+Enter 换行。",
                    checked = settings.enterToSend,
                    onCheckedChange = app.settings::setEnterToSend,
                )
                Hairline()
                SettingsToggleRow(
                    title = "自动保留未发送草稿",
                    subtitle = "退出会话或重启应用后，自动恢复尚未发送的输入草稿。",
                    checked = settings.keepUnsentDrafts,
                    onCheckedChange = app.settings::setKeepUnsentDrafts,
                )
            }
        }

        // 2. 消息与内容渲染
        SectionLabel("消息与内容渲染")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已发送的用户消息渲染", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    Text(
                        when (settings.userMessageRenderMode) {
                            USER_RENDER_ALWAYS_RICH -> "始终自动渲染用户气泡中的 Markdown、代码块与数学公式。"
                            USER_RENDER_ALWAYS_PLAIN -> "始终以原始纯文本展示用户消息气泡，不做任何解析。"
                            else -> if (settings.plainTextEditor) {
                                "跟随编辑器：当前已启用纯文本编辑器，用户气泡保持原始纯文本。"
                            } else {
                                "跟随编辑器：当前为自动渲染模式，用户气泡自动渲染 Markdown、代码与公式。"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                    SegmentedPillBar(
                        options = listOf(
                            USER_RENDER_FOLLOW_EDITOR to "跟随编辑器",
                            USER_RENDER_ALWAYS_RICH to "始终渲染",
                            USER_RENDER_ALWAYS_PLAIN to "始终纯文本",
                        ),
                        selected = settings.userMessageRenderMode,
                        onSelect = app.settings::setUserMessageRenderMode,
                    )
                }
                Hairline()
                Column(Modifier.fillMaxWidth()) {
                    SettingsToggleRow(
                        title = "助手回复富文本渲染",
                        subtitle = "开启后自动渲染助手消息的 Markdown、表格、代码块与高亮；关闭则以原始纯文本显示。",
                        checked = settings.assistantRichRender,
                        onCheckedChange = app.settings::setAssistantRichRender,
                    )
                    AnimatedExpandable(expanded = settings.assistantRichRender) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Hairline()
                            SettingsToggleRow(
                                title = "聊天流自动内联可视化组件",
                                subtitle = "开启后直接在对话中渲染 Mermaid、图表、SVG、HTML/React 与块级公式；关闭则仅显示轻量入口卡片，点击再进全屏工作台。",
                                checked = settings.inlineVisualArtifacts,
                                onCheckedChange = app.settings::setInlineVisualArtifacts,
                            )
                            Hairline()
                            SettingsToggleRow(
                                title = "自动渲染数学公式",
                                subtitle = "解析行内 $...$ / \\(...\\) 与块级 $$...$$ / \\[...\\] 数学公式；若经常讨论 Shell \$变量可关闭避免误识别。",
                                checked = settings.renderMathFormulas,
                                onCheckedChange = app.settings::setRenderMathFormulas,
                            )
                        }
                    }
                }
            }
        }

        // 3. 流式输出动效与流速
        SectionLabel("流式输出动效")
        CrispCard {
            val activeStreamOpt = remember(settings.streamAnimationStyle) {
                STREAM_ANIMATION_OPTIONS.firstOrNull { it.id == settings.streamAnimationStyle } ?: STREAM_ANIMATION_OPTIONS.first()
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("输出动画效果", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    Text(
                        activeStreamOpt.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }

                StreamingAnimationPreviewCard(
                    style = settings.streamAnimationStyle,
                    speed = settings.streamSpeed,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    STREAM_ANIMATION_OPTIONS.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { opt ->
                                val selected = opt.id == settings.streamAnimationStyle
                                val cardShape = D.shape(11.dp)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(cardShape)
                                        .clickable { app.settings.setStreamAnimationStyle(opt.id) },
                                    shape = cardShape,
                                    color = if (selected) {
                                        G.accentContainer.copy(alpha = if (G.dark) 0.42f else 0.72f)
                                    } else {
                                        G.surfaceSubtle.copy(alpha = if (G.dark) 0.45f else 0.62f)
                                    },
                                    border = BorderStroke(
                                        width = if (selected) 1.25.dp else 0.75.dp,
                                        color = if (selected) G.accent else G.border,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                opt.title,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (selected) G.accent else G.ink,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (selected) {
                                                Icon(
                                                    GaliaisIcons.Check,
                                                    contentDescription = "已选中",
                                                    tint = G.accent,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            } else {
                                                Text(
                                                    opt.badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = G.inkSubtle,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        Text(
                                            opt.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) G.ink.copy(alpha = 0.78f) else G.inkMuted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth()) {
                    AnimatedExpandable(expanded = settings.streamAnimationStyle != STREAM_ANIM_INSTANT) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Hairline()
                            Spacer(Modifier.height(2.dp))
                            Text("流式推进流速", style = MaterialTheme.typography.titleSmall, color = G.ink)
                            Text(
                                when (settings.streamSpeed) {
                                    STREAM_SPEED_GENTLE -> "舒缓节奏：更适合从容阅读长篇思考与逐段解析。"
                                    STREAM_SPEED_FAST -> "迅捷节奏：低延迟紧跟高吞吐推理模型，同时保持平滑不抖动。"
                                    else -> "均衡节奏：按屏幕刷新率平滑释放，高速模型下自动动态提速不掉队。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = G.inkMuted,
                            )
                            SegmentedPillBar(
                                options = listOf(
                                    STREAM_SPEED_GENTLE to "舒缓",
                                    STREAM_SPEED_BALANCED to "均衡",
                                    STREAM_SPEED_FAST to "迅捷",
                                ),
                                selected = settings.streamSpeed,
                                onSelect = app.settings::setStreamSpeed,
                            )
                        }
                    }
                }
            }
        }

        // 4. 文件编辑器与对话显示
        SectionLabel("文件编辑器与对话体验")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsToggleRow(
                    title = "工作区文件自动换行",
                    subtitle = "在文件预览与代码编辑时自动折行；关闭后支持横向滚动保持原始代码排版。",
                    checked = settings.fileEditorWordWrap,
                    onCheckedChange = app.settings::setFileEditorWordWrap,
                )
                Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("编辑器缩进宽度", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    Text(
                        "在文件编辑器与全屏提示词编辑器点击缩进按钮时插入的字符。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                    SegmentedPillBar(
                        options = listOf(2 to "2 空格", 4 to "4 空格", 0 to "Tab 制表符"),
                        selected = settings.editorTabSize,
                        onSelect = app.settings::setEditorTabSize,
                    )
                }
                Hairline()
                SettingsToggleRow(
                    title = "自动跟随新消息",
                    subtitle = "阅读位置接近底部时，自动滚动到最新输出。",
                    checked = settings.autoScroll,
                    onCheckedChange = app.settings::setAutoScroll,
                )
                Hairline()
                SettingsToggleRow(
                    title = "默认展开执行过程",
                    subtitle = "轮次完成后默认展开「用时」轨迹与思维链摘要。",
                    checked = settings.expandThinking,
                    onCheckedChange = app.settings::setExpandThinking,
                )
                Hairline()
                SettingsToggleRow(
                    title = "显示上下文 Token 用量环",
                    subtitle = "在聊天输入框底栏显示上下文占用进度环。",
                    checked = settings.showTokenUsage,
                    onCheckedChange = app.settings::setShowTokenUsage,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryPillButton("恢复常规默认设置", onClick = { confirmReset = true })
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmReset) {
        GaliaisConfirmDialog(
            title = "恢复常规默认设置？",
            message = "将重置编辑器模式、消息渲染偏好、流式输出动效、自动滚动与输入快捷键为默认值，不影响服务商与工作区数据。",
            confirmText = "确认恢复",
            danger = true,
            onConfirm = {
                confirmReset = false
                app.settings.resetGeneralSettings()
                app.conversations.notify("已恢复常规默认设置")
            },
            onDismiss = { confirmReset = false },
        )
    }
}
