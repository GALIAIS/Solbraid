package com.galiais.agent.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ManagedMcpServer
import com.galiais.agent.engine.ManagedSkill
import com.galiais.agent.engine.McpProbeResult
import com.galiais.agent.engine.PluginSecretSpec
import com.galiais.agent.engine.SkillCompanionFile
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun scopeDisplayLabel(scope: String): String = when {
    scope.startsWith("project:") -> "项目:${scope.removePrefix("project:").substringAfterLast('/').ifBlank { "/" }}"
    else -> "全局"
}

@Composable
internal fun TargetAgentsPicker(
    targetAll: Boolean,
    selectedTargets: Set<String>,
    onChange: (Boolean, Set<String>) -> Unit,
) {
    Text("生效 Agent", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectableChip("全部 Agent", selected = targetAll, onClick = { onChange(true, selectedTargets) })
        listOf("codex" to "Codex", "claude" to "Claude Code", "antigravity" to "Antigravity").forEach { (id, label) ->
            val picked = !targetAll && id in selectedTargets
            SelectableChip(label, selected = picked, onClick = {
                val next = if (id in selectedTargets) selectedTargets - id else selectedTargets + id
                onChange(false, next)
            })
        }
    }
}

@Composable
internal fun ScopePicker(
    scope: String,
    projects: List<String>,
    defaultCwd: String,
    onChange: (String) -> Unit,
) {
    val isProject = scope.startsWith("project:")
    val projPath = if (isProject) scope.removePrefix("project:") else defaultCwd
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("生效作用域", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectableChip("全局生效", selected = !isProject, onClick = { onChange("global") })
            SelectableChip("仅指定项目", selected = isProject, onClick = { onChange("project:$projPath") })
        }
        if (isProject) {
            if (projects.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    projects.distinct().take(6).forEach { dir ->
                        val short = dir.substringAfterLast('/').ifBlank { dir }
                        SelectableChip(short, selected = projPath == dir, onClick = { onChange("project:$dir") })
                    }
                }
            }
            OutlinedTextField(
                value = projPath,
                onValueChange = { onChange("project:${it.trim()}") },
                label = { Text("项目绝对路径（Linux 路径）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun PasteImportSheet(
    app: GaliaisApplication,
    title: String,
    subtitle: String,
    placeholder: String,
    confirmLabel: String = "解析并导入",
    onDismiss: () -> Unit,
    onImport: (String) -> String?,
) {
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("从剪贴板粘贴", onClick = {
                val clip = app.getSystemService(ClipboardManager::class.java)?.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                if (clip.isBlank()) error = "剪贴板为空" else { raw = clip; error = null }
            })
            Spacer(Modifier.weight(1f))
            PrimaryPillButton(confirmLabel, enabled = raw.isNotBlank(), onClick = {
                val err = onImport(raw)
                if (err != null) error = err else onDismiss()
            })
        },
    ) {
        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it; error = null },
            label = { Text(placeholder) },
            minLines = 8,
            maxLines = 16,
            shape = D.fieldShape,
            colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

private data class SecretDraftRow(
    val key: String = "",
    val value: String = "",
    val alreadySaved: Boolean = false,
)

@Composable
internal fun McpEditorSheet(
    app: GaliaisApplication,
    initial: ManagedMcpServer?,
    projects: List<String>,
    defaultCwd: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var idDraft by remember { mutableStateOf(initial?.id ?: "") }
    var transport by remember { mutableStateOf(initial?.transport ?: "stdio") }
    var command by remember { mutableStateOf(initial?.command ?: "npx") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var envText by remember {
        val values = if (initial?.transport == "http") initial.headers else initial?.env
        mutableStateOf(values?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    val initialSecretKeys = remember(initial) {
        if (initial?.transport == "http") initial.secretHeaderKeys else initial?.secretEnvKeys.orEmpty()
    }
    val secretRows = remember(initial) {
        mutableStateListOf<SecretDraftRow>().apply {
            initialSecretKeys.forEach { k ->
                add(SecretDraftRow(key = k, value = "", alreadySaved = initial != null && app.settings.hasExtensionSecret(initial.id, k)))
            }
        }
    }
    var scopeVal by remember { mutableStateOf(initial?.scope ?: "global") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = if (initial == null) "添加 MCP 服务器" else "编辑 MCP · ${initial.name}",
        subtitle = "单源托管并同步至 Codex / Claude Code / Antigravity，密钥由 Keystore 加密存储",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存并同步", modifier = Modifier.weight(1f), onClick = {
                val cleanName = name.trim()
                if (cleanName.isBlank()) { error = "请填写名称"; return@PrimaryPillButton }
                val slug = (initial?.id ?: idDraft).trim()
                    .ifBlank { cleanName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-') }
                val cleanId = slug.ifBlank { "mcp-${java.util.UUID.randomUUID().toString().take(8)}" }
                if (transport == "stdio" && command.isBlank()) { error = "请填写启动命令"; return@PrimaryPillButton }
                if (transport == "http" && url.isBlank()) { error = "请填写服务 URL"; return@PrimaryPillButton }
                val kv = linkedMapOf<String, String>()
                envText.lineSequence().map { it.trim() }.filter { it.contains('=') }.forEach { line ->
                    val i = line.indexOf('=')
                    val k = line.substring(0, i).trim()
                    if (k.isNotEmpty()) kv[k] = line.substring(i + 1).trim()
                }
                val activeSecretKeys = secretRows.map { it.key.trim() }.filter { it.isNotEmpty() }.distinct()
                val secretUpdates = linkedMapOf<String, String>()
                secretRows.forEach { r ->
                    val k = r.key.trim()
                    if (k.isNotEmpty() && r.value.isNotBlank()) secretUpdates[k] = r.value.trim()
                }
                val removedKeys = initialSecretKeys.filterNot { it in activeSecretKeys }.toSet()
                val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                val srv = ManagedMcpServer(
                    id = cleanId,
                    name = cleanName,
                    transport = transport,
                    command = if (transport == "stdio") command.trim() else null,
                    args = if (transport == "stdio") argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() } else emptyList(),
                    url = if (transport == "http") url.trim() else null,
                    env = if (transport == "stdio") kv else emptyMap(),
                    secretEnvKeys = if (transport == "stdio") activeSecretKeys else emptyList(),
                    headers = if (transport == "http") kv else emptyMap(),
                    secretHeaderKeys = if (transport == "http") activeSecretKeys else emptyList(),
                    enabled = initial?.enabled ?: true,
                    scope = scopeVal,
                    targets = targets,
                    pluginId = initial?.pluginId,
                )
                app.extensions.upsertMcp(srv, secretUpdates, removedKeys)
                onDismiss()
            })
        },
    ) {
        if (initial == null) {
            Text("快捷模板", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("npx Stdio", fg = G.accent, bg = G.accentContainer, onClick = {
                    transport = "stdio"; command = "npx"; argsText = "-y @modelcontextprotocol/server-filesystem /home/agent/workspace"
                    if (name.isBlank()) name = "Filesystem MCP"
                })
                TagPill("uvx Python", fg = G.accent, bg = G.accentContainer, onClick = {
                    transport = "stdio"; command = "uvx"; argsText = "mcp-server-fetch"
                    if (name.isBlank()) name = "Fetch MCP"
                })
                TagPill("HTTP Bearer", fg = G.accent, bg = G.accentContainer, onClick = {
                    transport = "http"; url = "https://mcp.example.com/mcp"
                    if (secretRows.none { it.key == "Authorization" }) secretRows.add(SecretDraftRow(key = "Authorization"))
                    if (name.isBlank()) name = "Remote HTTP MCP"
                })
            }
        }
        SegmentedPillBar(
            options = listOf("stdio" to "Stdio 本地进程", "http" to "Streamable HTTP"),
            selected = transport,
            onSelect = { transport = it },
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("名称（如 Context7 / GitHub）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (initial == null) {
            OutlinedTextField(
                value = idDraft, onValueChange = { idDraft = it.trim() },
                label = { Text("标识 ID（可选，留空自动生成）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (transport == "stdio") {
            OutlinedTextField(
                value = command, onValueChange = { command = it },
                label = { Text("启动命令（如 npx / uvx / node）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = argsText, onValueChange = { argsText = it },
                label = { Text("参数列表（空格分隔）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = url, onValueChange = { url = it.trim() },
                label = { Text("MCP 服务 URL (https://...)") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = envText, onValueChange = { envText = it },
            label = { Text(if (transport == "http") "普通请求头（每行 KEY=VALUE）" else "普通环境变量（每行 KEY=VALUE）") },
            minLines = 2, maxLines = 4,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (transport == "http") "加密 Header 密钥 (${secretRows.size})" else "加密环境变量密钥 (${secretRows.size})",
                style = MaterialTheme.typography.labelMedium,
                color = G.inkMuted,
                modifier = Modifier.weight(1f),
            )
            CompactActionButton("+ 添加密钥", onClick = { secretRows.add(SecretDraftRow()) })
        }
        secretRows.forEachIndexed { idx, row ->
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = row.key,
                            onValueChange = { secretRows[idx] = row.copy(key = it.trim()) },
                            label = { Text(if (transport == "http") "Header 名（如 Authorization）" else "变量名（如 GITHUB_TOKEN）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { secretRows.removeAt(idx) }, modifier = Modifier.size(32.dp)) {
                            Icon(GaliaisIcons.Close, contentDescription = "移除密钥", tint = G.danger, modifier = Modifier.size(16.dp))
                        }
                    }
                    OutlinedTextField(
                        value = row.value,
                        onValueChange = { secretRows[idx] = row.copy(value = it) },
                        label = { Text(if (row.alreadySaved) "密钥值（Keystore 已保存，留空不变）" else "密钥值（Keystore AES-GCM 加密）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        ScopePicker(scopeVal, projects, defaultCwd) { scopeVal = it }
        TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

@Composable
internal fun McpToolsDialog(
    serverName: String,
    probe: McpProbeResult,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(probe.toolDetails, filter) {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) probe.toolDetails
        else probe.toolDetails.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }
    GaliaisSheetScaffold(
        title = "$serverName · 工具清单 (${probe.toolDetails.size})",
        subtitle = if (probe.ok) "探测延迟 ${probe.latencyMs}ms · ${probe.message}" else probe.message,
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("关闭", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        },
    ) {
        if (probe.toolDetails.size > 4) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("筛选工具名称或说明") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            CrispCard { Text("未匹配到 MCP 工具", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        } else {
            CrispCard(padding = PaddingValues(0.dp)) {
                filtered.forEachIndexed { idx, tool ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(tool.name, fontFamily = Mono, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                        if (tool.description.isNotBlank()) {
                            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        }
                    }
                    if (idx < filtered.lastIndex) Hairline()
                }
            }
        }
    }
}

@Composable
internal fun SkillEditorSheet(
    app: GaliaisApplication,
    initial: ManagedSkill?,
    projects: List<String>,
    defaultCwd: String,
    onOpenInCodeEditor: (FileRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var body by remember { mutableStateOf(initial?.body ?: "") }
    var scopeVal by remember { mutableStateOf(initial?.scope ?: "global") }
    var targetAll by remember { mutableStateOf(initial == null || "all" in initial.targets) }
    var selectedTargets by remember { mutableStateOf((initial?.targets ?: listOf("codex", "claude", "antigravity")).filterNot { it == "all" }.toSet()) }
    var previewMode by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = if (initial == null) "新建技能 (SKILL.md)" else "编辑技能 · ${initial.name}",
        subtitle = "遵循开放 SKILL.md 规范，自动软链投影到所选 Agent 发现目录",
        onDismiss = onDismiss,
        footer = {
            if (initial != null && initial.pluginId == null) {
                TextButton(enabled = !saving && !deleting, onClick = { confirmDelete = true }) {
                    Text(if (deleting) "删除中…" else "删除", color = G.danger)
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton(if (saving) "保存中…" else "保存并同步", enabled = !saving && !deleting, onClick = {
                if (name.isBlank() || body.isBlank()) { error = "请填写技能名称和正文"; return@PrimaryPillButton }
                saving = true; error = null
                val targets = if (targetAll || selectedTargets.isEmpty()) listOf("all") else selectedTargets.toList()
                scope.launch {
                    try {
                        app.extensions.saveLocalSkill(
                            id = initial?.id,
                            name = name.trim(),
                            description = desc.trim(),
                            body = body,
                            targets = targets,
                            scope = scopeVal,
                        )
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                        saving = false
                    }
                }
            })
        },
    ) {
        if (initial == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("代码审查模板", fg = G.accent, bg = G.accentContainer, onClick = {
                    if (name.isBlank()) name = "strict-code-review"
                    if (desc.isBlank()) desc = "按安全、并发、边界条件与性能四维度审查代码变更"
                    body = "# 执行流程\n\n1. 先读取完整变更 diff 与关键调用方。\n2. 逐项检查空指针、并发竞态、资源泄漏与权限边界。\n3. 输出按 P0 / P1 / P2 分级的修复清单与最小侵入补丁。"
                })
                TagPill("根因排障模板", fg = G.accent, bg = G.accentContainer, onClick = {
                    if (name.isBlank()) name = "root-cause-debug"
                    if (desc.isBlank()) desc = "复现故障、定位根因并验证回归测试"
                    body = "# 排查步骤\n\n1. 收集精确错误栈与最小复现命令。\n2. 提出 2 个以上假设并通过日志或断点验证。\n3. 仅修复根因，补充回归单测并确认全部测试通过。"
                })
            }
        }
        if (initial != null && initial.dirPath.startsWith("/")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactActionButton("在代码编辑器打开 SKILL.md", onClick = {
                    val filePath = "${initial.dirPath.trimEnd('/')}/SKILL.md"
                    val fileScope = if (filePath.startsWith("/home/agent")) FileScope.LINUX_WORKSPACE else FileScope.LINUX_ROOT
                    onDismiss()
                    onOpenInCodeEditor(FileRef(fileScope, filePath))
                })
            }
        }
        SegmentedPillBar(
            options = listOf("edit" to "编辑内容", "preview" to "Markdown 预览"),
            selected = if (previewMode) "preview" else "edit",
            onSelect = { previewMode = it == "preview" },
        )
        if (!previewMode) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("技能名称（建议英文短横线，如 compose-audit）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it },
                label = { Text("触发描述（告诉 Agent 何时调用该技能）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text("技能正文（Markdown 规范与步骤）") }, minLines = 6, maxLines = 12,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
            ScopePicker(scopeVal, projects, defaultCwd) { scopeVal = it }
            TargetAgentsPicker(targetAll, selectedTargets) { all, set -> targetAll = all; selectedTargets = set }
        } else {
            CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name.ifBlank { "(未命名技能)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.ink)
                    if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                    Hairline()
                    Markdown(text = body.ifBlank { "*暂无技能正文*" }, compact = true)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }

    if (confirmDelete && initial != null) {
        GaliaisConfirmDialog(
            title = "删除技能？",
            message = "确定删除本地技能「${initial.name}」并同步移除其 SKILL.md 文件吗？",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false; deleting = true; error = null
                scope.launch {
                    try { app.extensions.deleteSkill(initial.id); onDismiss() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { error = e.message ?: "技能删除失败，请重试" }
                    finally { deleting = false }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun SkillFilesDialog(
    skillName: String,
    dirPath: String,
    files: List<SkillCompanionFile>,
    loading: Boolean,
    onOpenFile: (FileRef) -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheetScaffold(
        title = "$skillName · 目录文件",
        subtitle = dirPath,
        onDismiss = onDismiss,
        footer = { SecondaryPillButton("关闭", modifier = Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        if (loading) {
            Text("正在扫描技能目录文件…", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
        } else if (files.isEmpty()) {
            CrispCard { Text("目录下未发现可查看文件", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        } else {
            CrispCard(padding = PaddingValues(0.dp)) {
                files.forEachIndexed { idx, f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val sc = if (f.absPath.startsWith("/home/agent")) FileScope.LINUX_WORKSPACE else FileScope.LINUX_ROOT
                                onDismiss()
                                onOpenFile(FileRef(sc, f.absPath))
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(GaliaisIcons.FileLines, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                        Text(f.relPath, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (f.size >= 1024) "${f.size / 1024} KB" else "${f.size} B", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle)
                    }
                    if (idx < files.lastIndex) Hairline()
                }
            }
        }
    }
}

@Composable
internal fun PluginSecretsDialog(
    title: String,
    specs: List<PluginSecretSpec>,
    bucket: String,
    app: GaliaisApplication,
    confirmText: String,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val values = remember(specs) { mutableStateMapOf<String, String>() }
    GaliaisAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = confirmText,
        onConfirm = { onSave(values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }) },
    ) {
        specs.forEach { spec ->
            val saved = app.settings.hasExtensionSecret(bucket, spec.key)
            OutlinedTextField(
                value = values[spec.key] ?: "",
                onValueChange = { values[spec.key] = it },
                label = { Text(if (saved) "${spec.label}（已保存，留空不变）" else spec.label) },
                supportingText = if (spec.hint.isNotBlank()) ({ Text(spec.hint, style = MaterialTheme.typography.labelSmall) }) else null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
