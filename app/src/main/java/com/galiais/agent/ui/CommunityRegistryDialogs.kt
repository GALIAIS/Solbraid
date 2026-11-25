package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.CommunityRegistryConfig
import com.galiais.agent.engine.CommunitySubmitResult
import com.galiais.agent.engine.ExtensionRegistry
import com.galiais.agent.engine.HostPluginSnapshot
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 全 7 类资源上传分享抽屉：支持本地一键填入、上传前「安全预检」、GitHub 自动发 PR 跳转与完整拦截诊断。 */
@Composable
internal fun CommunityUploadSheet(
    app: GaliaisApplication,
    config: CommunityRegistryConfig,
    localRegistry: ExtensionRegistry,
    hostPlugins: HostPluginSnapshot,
    prefill: Pair<String, String>? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var kind by remember { mutableStateOf(prefill?.first ?: "skill") }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var author by remember { mutableStateOf(config.authorName) }
    var description by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var primaryContent by remember { mutableStateOf("") }
    var extraField by remember { mutableStateOf("") } // subagent model / hook matcher / mcp secret keys
    var hookEvent by remember { mutableStateOf("PostToolUse") }
    var mcpTransport by remember { mutableStateOf("stdio") }
    var selectedLocalPluginId by remember { mutableStateOf<String?>(null) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) } // "preview" | "submit"
    var error by remember { mutableStateOf<String?>(null) }
    var auditResult by remember { mutableStateOf<Pair<Boolean, CommunitySubmitResult>?>(null) } // (isPreview, result)

    val localExportablePlugins = remember(hostPlugins.plugins) { hostPlugins.plugins.filter { it.isRemovable } }

    fun applyPrefill(targetKind: String, targetId: String) {
        kind = targetKind
        selectedLocalPluginId = null
        selectedPackId = null
        when (targetKind) {
            "instruction" -> localRegistry.instructions.firstOrNull { it.id == targetId }?.let { ins ->
                id = ins.id; name = ins.title; description = ins.content.lines().firstOrNull().orEmpty().take(80); primaryContent = ins.content
            }
            "skill" -> localRegistry.skills.firstOrNull { it.id == targetId }?.let { sk ->
                id = sk.id; name = sk.name; description = sk.description; primaryContent = sk.body
            }
            "mcp" -> localRegistry.mcpServers.firstOrNull { it.id == targetId }?.let { m ->
                id = m.id; name = m.name; mcpTransport = m.transport; description = "${m.transport.uppercase()} MCP 服务"
                primaryContent = if (m.transport == "http") m.url.orEmpty() else listOfNotNull(m.command, m.args.joinToString(" ").takeIf { it.isNotBlank() }).joinToString(" ")
                extraField = (m.secretEnvKeys + m.secretHeaderKeys).distinct().joinToString(", ")
            }
            "subagent" -> localRegistry.subagents.firstOrNull { it.id == targetId }?.let { s ->
                id = s.id; name = s.name; description = s.description; primaryContent = s.prompt; extraField = s.model
            }
            "hook" -> localRegistry.hooks.firstOrNull { it.id == targetId }?.let { h ->
                id = h.id; name = h.name; description = "${h.event} · ${h.matcher}"; hookEvent = h.event; extraField = h.matcher; primaryContent = h.command
            }
            "host_plugin" -> localExportablePlugins.firstOrNull { it.id == targetId }?.let { p ->
                selectedLocalPluginId = p.id; id = p.id; name = p.name; version = p.version; description = p.description
            }
            "plugin_pack" -> localRegistry.plugins.firstOrNull { it.id == targetId }?.let { pack ->
                selectedPackId = pack.id; id = pack.id; name = pack.name; version = pack.version; description = pack.description
            }
        }
    }

    LaunchedEffect(prefill) {
        if (prefill != null) applyPrefill(prefill.first, prefill.second)
    }

    suspend fun buildBundleOrNull(): JSONObject? {
        val cleanName = name.trim()
        val cleanId = id.trim().ifBlank { cleanName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-') }
        if (cleanName.isBlank() || cleanId.length < 2) {
            error = "请填写资源名称与有效 ID（至少 2 位英文/数字）"; return null
        }
        if (kind == "host_plugin" && selectedLocalPluginId != null) {
            return app.extensions.exportLocalHostPluginBundle(selectedLocalPluginId!!)
                .put("id", cleanId).put("name", cleanName).put("version", version.ifBlank { "1.0.0" })
                .put("author", author.trim().ifBlank { config.authorName }).put("description", description.trim()).put("tags", tags.trim())
        }
        if (kind == "plugin_pack") {
            val pack = localRegistry.plugins.firstOrNull { it.id == selectedPackId }
            if (pack == null) { error = "请从上方选择一个已安装的插件包进行打包分享"; return null }
            val pMcps = localRegistry.mcpServers.filter { it.pluginId == pack.id }
            val pSkills = localRegistry.skills.filter { it.pluginId == pack.id }
            val payload = JSONObject()
                .put("mcpServers", JSONArray().apply { pMcps.forEach { put(it.toJson()) } })
                .put("skills", JSONArray().apply { pSkills.forEach { put(it.toJson()) } })
                .put("commands", JSONArray().apply { pack.commands.forEach { put(it.toJson()) } })
                .put("requiredSecrets", JSONArray().apply { pack.requiredSecrets.forEach { put(it.toJson()) } })
            return JSONObject().put("id", cleanId).put("kind", "plugin_pack").put("name", cleanName)
                .put("version", version.ifBlank { pack.version }).put("author", author.trim().ifBlank { config.authorName })
                .put("description", description.trim()).put("tags", tags.trim()).put("payload", payload)
        }
        if (primaryContent.isBlank()) { error = "请填写资源核心内容"; return null }
        val payload = when (kind) {
            "instruction" -> JSONObject().put("title", cleanName).put("content", primaryContent.trim())
            "skill" -> JSONObject().put("name", cleanName).put("description", description.trim()).put("body", primaryContent.trim())
            "subagent" -> JSONObject().put("name", cleanName).put("description", description.trim()).put("model", extraField.trim()).put("prompt", primaryContent.trim())
            "hook" -> JSONObject().put("name", cleanName).put("event", hookEvent).put("matcher", extraField.trim().ifBlank { "*" }).put("command", primaryContent.trim())
            "mcp" -> {
                val secretKeys = extraField.split(Regex("[,，\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
                if (mcpTransport == "http") {
                    JSONObject().put("name", cleanName).put("transport", "http").put("url", primaryContent.trim())
                        .put("secretHeaderKeys", JSONArray(secretKeys))
                } else {
                    val parts = primaryContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    JSONObject().put("name", cleanName).put("transport", "stdio")
                        .put("command", parts.firstOrNull().orEmpty()).put("args", JSONArray(parts.drop(1)))
                        .put("secretEnvKeys", JSONArray(secretKeys))
                }
            }
            "host_plugin" -> {
                val manifest = JSONObject()
                    .put("manifestVersion", "1.0.0").put("apiVersion", ">=1.0.0 <2.0.0")
                    .put("id", cleanId).put("name", cleanName).put("version", version.ifBlank { "1.0.0" })
                    .put("description", description.trim()).put("kind", "community").put("entrypoint", "index.mjs")
                    .put("capabilities", JSONObject().put("required", JSONArray(listOf("commands"))).put("optional", JSONArray()))
                JSONObject().put("manifest", manifest).put("files", JSONObject().put("index.mjs", primaryContent))
            }
            else -> JSONObject()
        }
        return JSONObject().put("id", cleanId).put("kind", kind).put("name", cleanName)
            .put("version", version.ifBlank { "1.0.0" }).put("author", author.trim().ifBlank { config.authorName })
            .put("description", description.trim()).put("tags", tags.trim()).put("payload", payload)
    }

    val targetHint = when (config.mode) {
        "github" -> if (config.hasGithubToken) "目标：GitHub ${config.githubRepo}（一键建分支发 PR 触发仓库 AI 审查）"
        else "目标：GitHub ${config.githubRepo}（未填 PAT 将先预检并导出 PR 包）"
        "http" -> "目标：远程仓库 ${config.serverUrl.ifBlank { "未配置" }}（通过服务端 AI 审查后公开）"
        else -> "目标：内置共享仓库（静态扫描 + AI 语义审查后即时发布）"
    }

    GaliaisSheetScaffold(
        title = "上传资源到共享仓库",
        subtitle = targetHint,
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton(if (busyAction == "preview") "预检中…" else "安全预检", enabled = busyAction == null, onClick = {
                busyAction = "preview"; error = null; auditResult = null
                scope.launch {
                    try { buildBundleOrNull()?.let { auditResult = true to app.extensions.previewCommunityAudit(it) } }
                    catch (e: Exception) { error = e.message ?: "安全预检失败" }
                    finally { busyAction = null }
                }
            })
            PrimaryPillButton(
                text = if (busyAction == "submit") "审查上传中…" else "提交审查并上传",
                modifier = Modifier.weight(1f),
                enabled = busyAction == null,
                onClick = {
                    busyAction = "submit"; error = null; auditResult = null
                    scope.launch {
                        try { buildBundleOrNull()?.let { auditResult = false to app.extensions.submitToCommunityRegistry(it) } }
                        catch (e: Exception) { error = e.message ?: "提交失败" }
                        finally { busyAction = null }
                    }
                },
            )
        },
    ) {
        LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("instruction", "skill", "mcp", "subagent", "hook", "host_plugin", "plugin_pack")) { k ->
                SelectableChip(resourceKindLabel(k), selected = kind == k, onClick = {
                    kind = k; selectedLocalPluginId = null; selectedPackId = null; auditResult = null; error = null
                })
            }
        }

        val pickers: List<Pair<String, () -> Unit>> = when (kind) {
            "instruction" -> localRegistry.instructions.map { it.title to { applyPrefill("instruction", it.id) } }
            "skill" -> localRegistry.skills.map { it.name to { applyPrefill("skill", it.id) } }
            "mcp" -> localRegistry.mcpServers.map { it.name to { applyPrefill("mcp", it.id) } }
            "subagent" -> localRegistry.subagents.map { it.name to { applyPrefill("subagent", it.id) } }
            "hook" -> localRegistry.hooks.map { it.name to { applyPrefill("hook", it.id) } }
            "host_plugin" -> localExportablePlugins.map { it.name to { applyPrefill("host_plugin", it.id) } }
            "plugin_pack" -> localRegistry.plugins.map { it.name to { applyPrefill("plugin_pack", it.id) } }
            else -> emptyList()
        }
        if (pickers.isNotEmpty()) {
            Text("从本地已有资源一键填入（自动剥离敏感密钥值）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pickers.take(12)) { (label, fill) ->
                    SelectableChip(label, selected = name == label, onClick = { fill(); auditResult = null; error = null })
                }
            }
        }

        OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("资源名称") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = id, onValueChange = { id = it.lowercase(); error = null }, label = { Text("唯一 ID") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.weight(1.3f))
            OutlinedTextField(value = version, onValueChange = { version = it.trim() }, label = { Text("版本") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.weight(0.7f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("分享作者") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("标签（逗号分隔）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("简介说明") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth())

        when (kind) {
            "mcp" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableChip("Stdio 命令", selected = mcpTransport == "stdio", onClick = { mcpTransport = "stdio" })
                    SelectableChip("HTTPS 端点", selected = mcpTransport == "http", onClick = { mcpTransport = "http" })
                }
                OutlinedTextField(value = primaryContent, onValueChange = { primaryContent = it }, label = { Text(if (mcpTransport == "http") "MCP HTTPS URL" else "启动命令与参数（如 npx -y @pkg/mcp）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = extraField, onValueChange = { extraField = it }, label = { Text("所需密钥变量名（选填，仅填键名如 GITHUB_TOKEN，切勿填真实密钥）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
            }
            "hook" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PreToolUse", "PostToolUse", "Stop", "SessionStart").forEach { ev -> SelectableChip(ev, selected = hookEvent == ev, onClick = { hookEvent = ev }) }
                }
                OutlinedTextField(value = extraField, onValueChange = { extraField = it }, label = { Text("匹配器 Matcher（默认 *）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = primaryContent, onValueChange = { primaryContent = it }, label = { Text("Shell 命令（将接受仓库恶意命令审查）") }, minLines = 2, maxLines = 6, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
            }
            "subagent" -> {
                OutlinedTextField(value = extraField, onValueChange = { extraField = it.trim() }, label = { Text("推荐模型（选填）") }, singleLine = true, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = primaryContent, onValueChange = { primaryContent = it }, label = { Text("子代理 System Prompt") }, minLines = 4, maxLines = 8, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
            }
            "host_plugin" -> if (selectedLocalPluginId != null) {
                Text("已绑定本地插件目录 `$selectedLocalPluginId`，提交时自动打包 solbraid-plugin.json 与全部源码。", style = MaterialTheme.typography.bodySmall, color = G.accent)
            } else {
                OutlinedTextField(value = primaryContent, onValueChange = { primaryContent = it }, label = { Text("插件入口 index.mjs 源码（或上方选择本地插件）") }, minLines = 5, maxLines = 10, shape = D.fieldShape, colors = galiaisTextFieldColors(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth())
            }
            "plugin_pack" -> Text(
                if (selectedPackId != null) "已选定插件包 `$selectedPackId`，将打包其关联的 MCP、技能与斜杠命令（不上传本地密钥值）。"
                else "请从上方选择已安装的插件包进行分享（如尚未安装，可先通过 GitHub 导入或组合 MCP/技能）。",
                style = MaterialTheme.typography.bodySmall, color = if (selectedPackId != null) G.accent else G.inkMuted,
            )
            else -> OutlinedTextField(
                value = primaryContent, onValueChange = { primaryContent = it; error = null },
                label = { Text(if (kind == "instruction") "指令正文（Markdown）" else "技能正文（SKILL.md）") },
                minLines = 4, maxLines = 9, shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }

        auditResult?.let { (isPreview, res) ->
            val ok = res.status in setOf("approved", "pr_bundle_ready", "pr_submitted")
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TagPill(
                            text = when {
                                isPreview && ok -> "安全预检通过（可安全上传）"
                                isPreview -> "安全预检拦截（请先整改）"
                                res.status == "approved" -> "AI 审查通过并已发布"
                                res.status == "pr_submitted" -> "已自动发起 GitHub PR #${res.prNumber}"
                                res.status == "pr_bundle_ready" -> "已通过预检（已导出 PR 包）"
                                res.status == "rejected" -> "仓库 AI 安全审查已拦截"
                                else -> "待人工复审（未公开）"
                            },
                            fg = if (ok) G.success else G.danger,
                            bg = if (ok) G.successTint else G.dangerTint,
                        )
                        TagPill("评分 ${res.audit.score}", fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                    Text(res.audit.summary, style = MaterialTheme.typography.bodySmall, color = G.ink)
                    if (res.prHint.isNotBlank()) Text(res.prHint, style = MaterialTheme.typography.bodySmall, color = G.accent)
                    if (res.prUrl.isNotBlank()) {
                        SecondaryPillButton("在浏览器查看 PR #${res.prNumber} 审查进度", onClick = { runCatching { uriHandler.openUri(res.prUrl) } })
                    }
                    res.audit.findings.forEach { f ->
                        Text("• [${f.severity.uppercase()}] ${f.title}：${f.detail}", fontFamily = Mono, fontSize = 11.sp, color = G.danger)
                    }
                }
            }
        }
    }
}
