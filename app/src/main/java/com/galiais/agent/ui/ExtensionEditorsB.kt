package com.galiais.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.HookTestResult
import com.galiais.agent.engine.ManagedHook
import com.galiais.agent.engine.ManagedInstruction
import com.galiais.agent.engine.ManagedPlugin
import com.galiais.agent.engine.ManagedSubagent
import com.galiais.agent.engine.PluginCommand
import kotlinx.coroutines.launch

@Composable
internal fun InstructionEditorSheet(
    initial: ManagedInstruction?,
    projects: List<String>,
    defaultCwd: String,
    onDismiss: () -> Unit,
    onSave: (ManagedInstruction) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var scopeVal by remember { mutableStateOf(initial?.scope ?: "global") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    val estTokens = remember(content) { ManagedInstruction(id = "_", title = "", content = content).estimatedTokens }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建指令块" else "编辑指令 · ${initial.title}",
        subtitle = "将指令写入所选 Agent 的配置文件。",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存并同步", modifier = Modifier.weight(1f), onClick = {
                val t = title.trim(); val c = content.trim()
                if (t.isBlank() || c.isBlank()) { error = "请填写标题和指令正文"; return@PrimaryPillButton }
                val id = initial?.id ?: ("inst-" + System.currentTimeMillis().toString(36))
                val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                onSave(
                    ManagedInstruction(
                        id = id,
                        title = t,
                        content = c,
                        enabled = initial?.enabled ?: true,
                        targets = targets,
                        scope = scopeVal,
                        priority = initial?.priority ?: 0,
                    ),
                )
            })
        },
    ) {
        if (initial == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("中文工程规范", fg = G.accent, bg = G.accentContainer, onClick = {
                    if (title.isBlank()) title = "中文沟通与最小侵入规范"
                    content = "- 全程使用简洁清晰的中文汇报，引用文件附上路径。\n- 修改前先读上下文，保持改动聚焦、最小侵入，不顺手重构无关模块。\n- 每次修改后运行对应单测或编译校验，如实汇报结果。"
                })
                TagPill("严谨代码审查", fg = G.accent, bg = G.accentContainer, onClick = {
                    if (title.isBlank()) title = "严谨审查与防回归约束"
                    content = "- 优先检查空值边界、并发竞争、资源释放与安全注入风险。\n- 禁止编造命令输出或跳过失败用例。"
                })
                TagPill("Kotlin / Compose", fg = G.accent, bg = G.accentContainer, onClick = {
                    if (title.isBlank()) title = "Android Kotlin / Compose 规范"
                    content = "- 编译目标保持零警告（0 warnings），优先复用项目既有 UI 原语。\n- 避免在 Composable 重组路径内执行阻塞 I/O 或高频分配。"
                })
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("指令标题（如：中文回复与代码规范）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = content, onValueChange = { content = it },
            label = { Text("指令正文（Markdown · ${content.length} 字 · 约 $estTokens tokens）") },
            minLines = 6, maxLines = 12,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        ScopePicker(scopeVal, projects, defaultCwd) { scopeVal = it }
        TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

@Composable
internal fun InstructionsMergedPreviewDialog(
    app: GaliaisApplication,
    instructions: List<ManagedInstruction>,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf("claude") }
    val active = remember(instructions, target) {
        instructions
            .filter { it.enabled && it.content.isNotBlank() && ("all" in it.targets || target in it.targets) }
            .sortedBy { it.priority }
    }
    val merged = remember(active) {
        if (active.isEmpty()) "(当前引擎无已启用的托管指令)"
        else active.joinToString("\n\n") { "## ${it.title}\n\n${it.content.trim()}" }
    }
    val totalTokens = remember(active) { active.sumOf { it.estimatedTokens } }
    val targetFile = when (target) {
        "codex" -> "~/.codex/AGENTS.md"
        "antigravity" -> "~/.gemini/config/GEMINI.md"
        else -> "~/.claude/CLAUDE.md"
    }
    GaliaisSheetScaffold(
        title = "合并指令预览",
        subtitle = "$targetFile · ${active.size} 条启用 · 约 $totalTokens tokens",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("复制文本", onClick = {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("instructions", merged))
                app.conversations.notify("已复制合并指令文本")
            })
            Spacer(Modifier.weight(1f))
            PrimaryPillButton("关闭", onClick = onDismiss)
        },
    ) {
        SegmentedPillBar(
            options = listOf("claude" to "CLAUDE.md", "codex" to "AGENTS.md", "antigravity" to "GEMINI.md"),
            selected = target,
            onSelect = { target = it },
        )
        CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
            SelectionContainer {
                Text(merged, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
internal fun SubagentEditorSheet(
    initial: ManagedSubagent?,
    projects: List<String>,
    defaultCwd: String,
    modelSuggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (ManagedSubagent) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var toolsText by remember { mutableStateOf(initial?.tools?.joinToString(", ") ?: "") }
    var disallowedText by remember { mutableStateOf(initial?.disallowedTools?.joinToString(", ") ?: "") }
    var permissionMode by remember { mutableStateOf(initial?.permissionMode ?: "") }
    var maxTurnsText by remember { mutableStateOf(if ((initial?.maxTurns ?: 0) > 0) initial!!.maxTurns.toString() else "") }
    var scopeVal by remember { mutableStateOf(initial?.scope ?: "global") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var previewMd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildCandidate(): ManagedSubagent {
        val n = name.trim()
        val id = initial?.id ?: n.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "sub-${System.currentTimeMillis().toString(36)}" }
        val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
        return ManagedSubagent(
            id = id,
            name = n,
            description = description.trim(),
            model = model.trim(),
            prompt = prompt.trim(),
            tools = toolsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            disallowedTools = disallowedText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            permissionMode = permissionMode,
            maxTurns = maxTurnsText.trim().toIntOrNull()?.coerceIn(0, 200) ?: 0,
            scope = scopeVal,
            enabled = initial?.enabled ?: true,
            targets = targets,
        )
    }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建子代理" else "编辑子代理 · ${initial.name}",
        subtitle = "保存后同步到所选 Agent。",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存并同步", modifier = Modifier.weight(1f), onClick = {
                if (name.isBlank() || prompt.isBlank()) { error = "请填写子代理名称和系统提示词"; return@PrimaryPillButton }
                onSave(buildCandidate())
            })
        },
    ) {
        if (initial == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("只读代码审查", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "code-reviewer"; description = "只读审阅代码变更，按严重级别输出缺陷与改进建议"
                    toolsText = "Read, Grep, Glob"; disallowedText = "Write, Edit, Bash"; permissionMode = "plan"; maxTurnsText = "12"
                    prompt = "你是一名严谨的代码审查专家。只读分析代码与 Diff，重点检查安全漏洞、并发竞态、性能退化与边界错误，按 P0/P1/P2 输出精准定位与修改建议。"
                })
                TagPill("根因调试专家", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "root-debugger"; description = "定位崩溃、回归与性能瓶颈，制定最小修复方案"
                    toolsText = "Read, Grep, Glob, Bash"; disallowedText = ""; permissionMode = "default"; maxTurnsText = "20"
                    prompt = "你是一名系统级排障专家。通过日志、复现脚本与调用链追踪找到唯一根因，不猜测、不掩盖报错。"
                })
                TagPill("单测编写专家", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "test-engineer"; description = "补充边界单元测试与集成回归用例"
                    toolsText = "Read, Write, Edit, Grep, Glob, Bash"; disallowedText = ""; permissionMode = "acceptEdits"; maxTurnsText = "25"
                    prompt = "你是一名测试工程师。针对目标模块编写高覆盖、无随机抖动的单元测试，覆盖异常路径与边界输入并执行验证。"
                })
            }
        }
        SegmentedPillBar(
            options = listOf("edit" to "配置表单", "preview" to "预览 .md"),
            selected = if (previewMd) "preview" else "edit",
            onSelect = { previewMd = it == "preview" },
        )
        if (!previewMd) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("子代理名称（建议英文短横线，如 code-reviewer）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("职责描述（告诉主代理何时委派）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it.trim() },
                label = { Text("推荐模型（可选，留空继承主会话）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            if (modelSuggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    modelSuggestions.take(6).forEach { m ->
                        SelectableChip(m, selected = model == m, onClick = { model = if (model == m) "" else m })
                    }
                }
            }
            Text("工具权限与模式", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("只读工具集", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { toolsText = "Read, Grep, Glob"; disallowedText = "Write, Edit, Bash" })
                TagPill("读写+终端", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { toolsText = "Read, Write, Edit, Grep, Glob, Bash"; disallowedText = "" })
                TagPill("不限工具", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { toolsText = ""; disallowedText = "" })
            }
            OutlinedTextField(
                value = toolsText, onValueChange = { toolsText = it },
                label = { Text("允许工具白名单（逗号分隔，留空不限）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = disallowedText, onValueChange = { disallowedText = it },
                label = { Text("禁用工具黑名单（可选，如 Write, Edit）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            SegmentedPillBar(
                options = listOf("" to "继承权限", "plan" to "只读规划", "acceptEdits" to "自动编辑"),
                selected = permissionMode,
                onSelect = { permissionMode = it },
            )
            OutlinedTextField(
                value = maxTurnsText, onValueChange = { maxTurnsText = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("最大执行轮次（可选，留空不限，如 15）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("系统提示词（System Prompt）") }, minLines = 5, maxLines = 10,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            ScopePicker(scopeVal, projects, defaultCwd) { scopeVal = it }
            TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
        } else {
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                SelectionContainer {
                    Text(buildCandidate().toMarkdownPreview(), fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, lineHeight = 17.sp)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

internal val HOOK_EVENT_SPECS = listOf(
    "PreToolUse" to "工具调用前（退出码 2 可拦截本次调用）",
    "PostToolUse" to "工具执行成功后触发（自动格式化/校验）",
    "PostToolUseFailure" to "工具执行失败后触发（告警/记录）",
    "UserPromptSubmit" to "用户提交消息前触发（上下文注入/校验）",
    "Stop" to "主代理本轮回答结束时触发",
    "SubagentStop" to "子代理完成任务返回时触发",
    "SessionStart" to "新会话启动时触发（环境初始化）",
    "Notification" to "产生待确认或通知事件时触发",
)

@Composable
internal fun HookEditorSheet(
    app: GaliaisApplication,
    initial: ManagedHook?,
    projects: List<String>,
    defaultCwd: String,
    runtimeReady: Boolean,
    onDismiss: () -> Unit,
    onSave: (ManagedHook) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var event by remember { mutableStateOf(initial?.event ?: "PostToolUse") }
    var matcher by remember { mutableStateOf(initial?.matcher ?: "*") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var timeoutText by remember { mutableStateOf((initial?.timeoutSec ?: 30).toString()) }
    var scopeVal by remember { mutableStateOf(initial?.scope ?: "global") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<HookTestResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildCandidate(): ManagedHook {
        val id = initial?.id ?: ("hook-" + System.currentTimeMillis().toString(36))
        val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
        return ManagedHook(
            id = id,
            name = name.trim(),
            event = event,
            matcher = matcher.trim().ifBlank { "*" },
            command = command.trim(),
            timeoutSec = timeoutText.trim().toIntOrNull()?.coerceIn(1, 300) ?: 30,
            scope = scopeVal,
            description = description.trim(),
            enabled = initial?.enabled ?: true,
            targets = targets,
        )
    }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建生命周期钩子" else "编辑钩子 · ${initial.name}",
        subtitle = "同步至 Claude Code / Codex / Antigravity；Stdin 传入事件 JSON，退出码 2 表示阻断",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton(
                text = if (testing) "试运行中…" else "试运行",
                enabled = runtimeReady && !testing && command.isNotBlank(),
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        try { testResult = app.extensions.testHook(buildCandidate(), defaultCwd) }
                        finally { testing = false }
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            PrimaryPillButton("保存并同步", onClick = {
                if (name.isBlank() || command.isBlank()) { error = "请填写名称和 Shell 命令"; return@PrimaryPillButton }
                onSave(buildCandidate())
            })
        },
    ) {
        testResult?.let { res ->
            val (badge, fg, bg) = when {
                res.blocked -> Triple("已阻断 (exit 2) · ${res.latencyMs}ms", G.warning, G.warningTint)
                res.ok -> Triple("放行 (exit 0) · ${res.latencyMs}ms", G.success, G.successTint)
                else -> Triple("失败 (exit ${res.exitCode}) · ${res.latencyMs}ms", G.danger, G.dangerTint)
            }
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("试运行结果", style = MaterialTheme.typography.labelMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                        TagPill(badge, fg = fg, bg = bg)
                    }
                    if (res.stdout.isNotBlank()) {
                        Text("stdout:\n${res.stdout.trim()}", fontFamily = Mono, fontSize = 11.sp, color = G.ink)
                    }
                    if (res.stderr.isNotBlank()) {
                        Text("stderr:\n${res.stderr.trim()}", fontFamily = Mono, fontSize = 11.sp, color = G.danger)
                    }
                }
            }
        }
        if (initial == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("阻断危险 rm -rf", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "拦截高危删除命令"; event = "PreToolUse"; matcher = "Bash"; description = "检测 rm -rf / 等高危命令并以退出码 2 阻断"
                    command = "input=\$(cat); echo \"\$input\" | grep -qE 'rm\\s+-rf\\s+(/|~)' && { echo 'Blocked dangerous rm' >&2; exit 2; } || exit 0"
                })
                TagPill("编辑后记录变更", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "记录文件改动日志"; event = "PostToolUse"; matcher = "Edit|Write"; description = "在工作区追加修改时间戳"
                    command = "echo \"[hook \$(date +%H:%M:%S)] file edited\" >> /tmp/solbraid-hook.log"
                })
                TagPill("完成时检查 Git 状态", fg = G.accent, bg = G.accentContainer, onClick = {
                    name = "会话结束汇总 Git"; event = "Stop"; matcher = "*"; description = "本轮结束后输出简洁 git status"
                    command = "git status -s 2>/dev/null || true"
                })
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("钩子名称（如：编辑后自动格式化）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("用途备注（可选）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text("触发事件", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HOOK_EVENT_SPECS.forEach { (ev, _) ->
                SelectableChip(ev, selected = event == ev, onClick = { event = ev })
            }
        }
        HOOK_EVENT_SPECS.firstOrNull { it.first == event }?.second?.let { hint ->
            Text(hint, style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
        }
        Text("工具匹配器（Matcher）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("*", "Bash", "Edit|Write", "Read", "mcp__*").forEach { preset ->
                SelectableChip(preset, selected = matcher == preset, onClick = { matcher = preset })
            }
        }
        OutlinedTextField(
            value = matcher, onValueChange = { matcher = it.trim() },
            label = { Text("自定义 Matcher（支持正则管道，如 Edit|Write）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = command, onValueChange = { command = it },
            label = { Text("执行 Shell 命令（/bin/sh -lc，Stdin 接收 JSON）") }, minLines = 3, maxLines = 6,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = timeoutText, onValueChange = { timeoutText = it.filter { ch -> ch.isDigit() }.take(3) },
            label = { Text("超时秒数（1~300，默认 30）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        ScopePicker(scopeVal, projects, defaultCwd) { scopeVal = it }
        TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

@Composable
internal fun PluginPackEditorSheet(
    initial: ManagedPlugin?,
    onDismiss: () -> Unit,
    onSave: (ManagedPlugin) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var version by remember { mutableStateOf(initial?.version ?: "1.0.0") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    val cmds = remember(initial) {
        mutableStateListOf<PluginCommand>().apply {
            if (initial != null) addAll(initial.commands)
            else add(PluginCommand(name = "review", description = "按规范执行代码审查", promptTemplate = "请审查当前目录的最近改动，重点关注边界缺陷与性能。"))
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建本地插件包" else "编辑插件包 · ${initial.name}",
        subtitle = "定义可在聊天中使用的 / 命令。",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存", modifier = Modifier.weight(1f), onClick = {
                val n = name.trim()
                if (n.isBlank()) { error = "请填写插件包名称"; return@PrimaryPillButton }
                val cleanCmds = cmds.map { it.copy(name = it.name.trim().removePrefix("/"), description = it.description.trim(), promptTemplate = it.promptTemplate.trim()) }
                    .filter { it.name.isNotBlank() && it.promptTemplate.isNotBlank() }
                val id = initial?.id ?: ("pack-" + n.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { System.currentTimeMillis().toString(36) })
                onSave(
                    ManagedPlugin(
                        id = id,
                        name = n,
                        version = version.trim().ifBlank { "1.0.0" },
                        description = description.trim(),
                        source = initial?.source ?: "local",
                        sourceUrl = initial?.sourceUrl,
                        dirPath = initial?.dirPath ?: "",
                        enabled = initial?.enabled ?: true,
                        requiredSecrets = initial?.requiredSecrets ?: emptyList(),
                        commands = cleanCmds,
                    ),
                )
            })
        },
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("插件包名称（如：团队工程快捷指令包）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = version, onValueChange = { version = it.trim() },
            label = { Text("版本号（如 1.0.0）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("简介") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快捷 `/` 指令 (${cmds.size})", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, modifier = Modifier.weight(1f))
            CompactActionButton("+ 添加指令", onClick = { cmds.add(PluginCommand("", "", "")) })
        }
        cmds.forEachIndexed { idx, cmd ->
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = cmd.name,
                            onValueChange = { cmds[idx] = cmd.copy(name = it.trim().removePrefix("/")) },
                            label = { Text("指令名（无需 /）") }, singleLine = true,
                            shape = D.fieldShape, colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { cmds.removeAt(idx) }, modifier = Modifier.size(32.dp)) {
                            Icon(GaliaisIcons.Close, contentDescription = "删除指令", tint = G.danger, modifier = Modifier.size(16.dp))
                        }
                    }
                    OutlinedTextField(
                        value = cmd.description,
                        onValueChange = { cmds[idx] = cmd.copy(description = it) },
                        label = { Text("指令说明") }, singleLine = true,
                        shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cmd.promptTemplate,
                        onValueChange = { cmds[idx] = cmd.copy(promptTemplate = it) },
                        label = { Text("触发后展开的提示词模板") }, minLines = 2, maxLines = 5,
                        shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

@Composable
internal fun ExtensionSyncTargetsDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        Triple("Codex", "MCP / 指令 / 技能 / 子代理 / 钩子", "~/.codex/config.toml\n~/.codex/AGENTS.md\n~/.agents/skills/solbraid-*\n~/.codex/agents/solbraid-*.md\n~/.codex/hooks.json"),
        Triple("Claude Code", "MCP / 指令 / 技能 / 子代理 / 钩子", "SDK 运行时动态注入 mcpServers\n~/.claude/CLAUDE.md\n~/.claude/skills/solbraid-*\n~/.claude/agents/solbraid-*.md\n~/.claude/settings.json"),
        Triple("Antigravity", "MCP / 指令 / 技能 / 子代理 / 钩子", "~/.gemini/config/mcp_config.json\n~/.gemini/config/GEMINI.md\n~/.gemini/antigravity/skills/solbraid-*\n~/.agents/agents/solbraid-*.md\n~/.agents/hooks.json"),
        Triple("本地插件", "本地源码与快照", "~/.galiais/plugins/<plugin-id>/\n~/.galiais/plugin-snapshots/"),
    )
    GaliaisSheetScaffold(
        title = "资源同步路径",
        subtitle = "查看各类资源在运行环境中的存储位置。",
        onDismiss = onDismiss,
        footer = { SecondaryPillButton("关闭", modifier = Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        rows.forEach { (engine, cap, paths) ->
            CrispCard(padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(engine, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                        TagPill(cap, fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                    Text(paths, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, lineHeight = 16.sp)
                }
            }
        }
    }
}
