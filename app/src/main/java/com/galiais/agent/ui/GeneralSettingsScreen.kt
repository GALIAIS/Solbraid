package com.galiais.agent.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
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
                    subtitle = "开启后输入框不渲染任何内容（关闭语法着色、实时预览、格式栏与自动配对），保持原始纯文本输入与最快 IME 响应；关闭则自动渲染。",
                    checked = settings.plainTextEditor,
                    onCheckedChange = app.settings::setPlainTextEditor,
                )

                // 实时生效行为说明条 + 仅在非纯文本（自动渲染）模式下展示精细渲染选项（合并于无 spacedBy 容器内避免折叠跳变）
                Column(Modifier.fillMaxWidth()) {
                    val modeTint = if (settings.plainTextEditor) G.surfaceSubtle else G.accentContainer.copy(alpha = 0.72f)
                    val modeFg = if (settings.plainTextEditor) G.inkMuted else G.onAccentContainer
                    val modeBorder = if (settings.plainTextEditor) G.border else G.accent.copy(alpha = 0.28f)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = D.shape(10.dp),
                        color = modeTint,
                        border = BorderStroke(0.75.dp, modeBorder),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (settings.plainTextEditor) GaliaisIcons.FileLines else GaliaisIcons.Sparkles,
                                contentDescription = null,
                                tint = if (settings.plainTextEditor) G.inkMuted else G.accent,
                                modifier = Modifier.padding(top = 1.dp).size(15.dp),
                            )
                            Text(
                                if (settings.plainTextEditor) {
                                    "当前为纯文本模式：输入框零样式加工、不弹出预览条、不改写字符，适合长代码/日志粘贴与极致输入法兼容性。"
                                } else {
                                    "当前为自动渲染模式：输入框实时着色 Markdown / 代码 / 数学公式 / @文件 / /指令（严格保持光标偏移），检测到富内容时自动提供即时预览。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = modeFg,
                            )
                        }
                    }

                    AnimatedExpandable(expanded = !settings.plainTextEditor) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Hairline()
                            SettingsToggleRow(
                                title = "输入实时语法着色",
                                subtitle = "在输入框内高亮标题、粗斜体、代码块、数学公式、@文件与 /指令，零字符位移不干扰中文拼音输入。",
                                checked = settings.editorSyntaxHighlight,
                                onCheckedChange = app.settings::setEditorSyntaxHighlight,
                            )
                            Hairline()
                            SettingsToggleRow(
                                title = "输入自动实时渲染预览",
                                subtitle = "检测到输入框包含数学公式、Mermaid、图表、SVG 或结构化 Markdown 时，自动在输入框上方展示可折叠渲染预览。",
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
                                Text("大文本自动降级保护", style = MaterialTheme.typography.titleSmall, color = G.ink)
                                Text(
                                    "当单次输入或粘贴文本超过阈值时，临时降级为纯文本零渲染以防止卡顿。",
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

        // 3. 文件编辑器与对话显示
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
            message = "将重置编辑器模式、消息渲染偏好、自动滚动与输入快捷键为默认值，不影响服务商与工作区数据。",
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
