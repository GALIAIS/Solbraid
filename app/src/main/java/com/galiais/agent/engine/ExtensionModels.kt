package com.galiais.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/** 统一托管 MCP 服务器配置（单源定义，自动投影到 Codex / Claude / 外部 ACP Agent）。 */
data class ManagedMcpServer(
    val id: String,
    val name: String,
    val transport: String = "stdio", // "stdio" | "http"
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    val secretEnvKeys: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val secretHeaderKeys: List<String> = emptyList(),
    val enabled: Boolean = true,
    val scope: String = "global", // "global" | "project:/abs/path"
    val targets: List<String> = listOf("all"),
    val pluginId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("transport", transport)
        .put("command", command ?: JSONObject.NULL)
        .put("args", JSONArray(args))
        .put("url", url ?: JSONObject.NULL)
        .put("env", JSONObject(env))
        .put("secretEnvKeys", JSONArray(secretEnvKeys))
        .put("headers", JSONObject(headers))
        .put("secretHeaderKeys", JSONArray(secretHeaderKeys))
        .put("enabled", enabled)
        .put("scope", scope)
        .put("targets", JSONArray(targets))
        .put("pluginId", pluginId ?: JSONObject.NULL)

    companion object {
        fun fromJson(obj: JSONObject): ManagedMcpServer = ManagedMcpServer(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            transport = obj.optString("transport", "stdio"),
            command = obj.str("command"),
            args = obj.arr("args").strings(),
            url = obj.str("url"),
            env = obj.optJSONObject("env")?.toStringMap() ?: emptyMap(),
            secretEnvKeys = obj.arr("secretEnvKeys").strings(),
            headers = obj.optJSONObject("headers")?.toStringMap() ?: emptyMap(),
            secretHeaderKeys = obj.arr("secretHeaderKeys").strings(),
            enabled = obj.optBoolean("enabled", true),
            scope = obj.optString("scope", "global"),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
            pluginId = obj.str("pluginId"),
        )
    }
}

/** 统一托管技能条目（遵循开放 SKILL.md 标准，零拷贝软链到各 Agent 发现目录）。 */
data class ManagedSkill(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String = "",
    val dirPath: String = "",
    val source: String = "local", // "local" | "github" | "plugin" | "workspace"
    val sourceUrl: String? = null,
    val enabled: Boolean = true,
    val scope: String = "global",
    val targets: List<String> = listOf("all"),
    val pluginId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("description", description)
        .put("body", body)
        .put("dirPath", dirPath)
        .put("source", source)
        .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        .put("enabled", enabled)
        .put("scope", scope)
        .put("targets", JSONArray(targets))
        .put("pluginId", pluginId ?: JSONObject.NULL)

    companion object {
        fun fromJson(obj: JSONObject): ManagedSkill = ManagedSkill(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            description = obj.optString("description"),
            body = obj.optString("body"),
            dirPath = obj.optString("dirPath"),
            source = obj.optString("source", "local"),
            sourceUrl = obj.str("sourceUrl"),
            enabled = obj.optBoolean("enabled", true),
            scope = obj.optString("scope", "global"),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
            pluginId = obj.str("pluginId"),
        )
    }
}

data class PluginSecretSpec(val key: String, val label: String, val hint: String = "") {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("label", label).put("hint", hint)
    companion object {
        fun fromJson(obj: JSONObject) = PluginSecretSpec(
            key = obj.getString("key"),
            label = obj.optString("label", obj.getString("key")),
            hint = obj.optString("hint"),
        )
    }
}

data class PluginCommand(val name: String, val description: String, val promptTemplate: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("promptTemplate", promptTemplate)
    companion object {
        fun fromJson(obj: JSONObject) = PluginCommand(
            name = obj.getString("name").removePrefix("/"),
            description = obj.optString("description"),
            promptTemplate = obj.optString("promptTemplate"),
        )
    }
}

/** 原子化插件包：捆绑 MCP、Skills 与快捷斜杠指令，支持一键启停与干净卸载。 */
data class ManagedPlugin(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val source: String = "builtin", // "builtin" | "github"
    val sourceUrl: String? = null,
    val dirPath: String = "",
    val enabled: Boolean = true,
    val requiredSecrets: List<PluginSecretSpec> = emptyList(),
    val commands: List<PluginCommand> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("description", description)
        .put("source", source)
        .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        .put("dirPath", dirPath)
        .put("enabled", enabled)
        .put("requiredSecrets", JSONArray().apply { requiredSecrets.forEach { put(it.toJson()) } })
        .put("commands", JSONArray().apply { commands.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(obj: JSONObject) = ManagedPlugin(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            version = obj.optString("version", "1.0.0"),
            description = obj.optString("description"),
            source = obj.optString("source", "builtin"),
            sourceUrl = obj.str("sourceUrl"),
            dirPath = obj.optString("dirPath"),
            enabled = obj.optBoolean("enabled", true),
            requiredSecrets = obj.arr("requiredSecrets").objects().map(PluginSecretSpec::fromJson),
            commands = obj.arr("commands").objects().map(PluginCommand::fromJson),
        )
    }
}

/** 资源库托管全局指令块（自动同步至 CLAUDE.md / AGENTS.md / GEMINI.md，保留手写内容）。 */
data class ManagedInstruction(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("content", content)
        .put("enabled", enabled).put("targets", JSONArray(targets))
    companion object {
        fun fromJson(obj: JSONObject) = ManagedInstruction(
            id = obj.getString("id"),
            title = obj.optString("title", obj.getString("id")),
            content = obj.optString("content"),
            enabled = obj.optBoolean("enabled", true),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
        )
    }
}

/** 资源库托管子代理定义（自动投影到 .claude/agents / .codex/agents / .agents/agents）。 */
data class ManagedSubagent(
    val id: String,
    val name: String,
    val description: String = "",
    val model: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description).put("model", model)
        .put("prompt", prompt).put("enabled", enabled).put("targets", JSONArray(targets))
    companion object {
        fun fromJson(obj: JSONObject) = ManagedSubagent(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            description = obj.optString("description"),
            model = obj.optString("model"),
            prompt = obj.optString("prompt"),
            enabled = obj.optBoolean("enabled", true),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
        )
    }
}

/** 资源库托管生命周期钩子（PreToolUse / PostToolUse / Stop / SessionStart）。 */
data class ManagedHook(
    val id: String,
    val name: String,
    val event: String = "PostToolUse",
    val matcher: String = "*",
    val command: String = "",
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("event", event).put("matcher", matcher)
        .put("command", command).put("enabled", enabled).put("targets", JSONArray(targets))
    companion object {
        fun fromJson(obj: JSONObject) = ManagedHook(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            event = obj.optString("event", "PostToolUse"),
            matcher = obj.optString("matcher", "*"),
            command = obj.optString("command"),
            enabled = obj.optBoolean("enabled", true),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
        )
    }
}

data class ExtensionRegistry(
    val mcpServers: List<ManagedMcpServer> = emptyList(),
    val skills: List<ManagedSkill> = emptyList(),
    val plugins: List<ManagedPlugin> = emptyList(),
    val instructions: List<ManagedInstruction> = emptyList(),
    val subagents: List<ManagedSubagent> = emptyList(),
    val hooks: List<ManagedHook> = emptyList(),
) {
    val totalCount: Int get() = mcpServers.size + skills.size + plugins.size + instructions.size + subagents.size + hooks.size

    fun toJson(): JSONObject = JSONObject()
        .put("mcpServers", JSONArray().apply { mcpServers.forEach { put(it.toJson()) } })
        .put("skills", JSONArray().apply { skills.forEach { put(it.toJson()) } })
        .put("plugins", JSONArray().apply { plugins.forEach { put(it.toJson()) } })
        .put("instructions", JSONArray().apply { instructions.forEach { put(it.toJson()) } })
        .put("subagents", JSONArray().apply { subagents.forEach { put(it.toJson()) } })
        .put("hooks", JSONArray().apply { hooks.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(raw: String?): ExtensionRegistry {
            if (raw.isNullOrBlank()) return ExtensionRegistry()
            return try {
                val obj = JSONObject(raw)
                ExtensionRegistry(
                    mcpServers = obj.arr("mcpServers").objects().mapNotNull { runCatching { ManagedMcpServer.fromJson(it) }.getOrNull() },
                    skills = obj.arr("skills").objects().mapNotNull { runCatching { ManagedSkill.fromJson(it) }.getOrNull() },
                    plugins = obj.arr("plugins").objects().mapNotNull { runCatching { ManagedPlugin.fromJson(it) }.getOrNull() },
                    instructions = obj.arr("instructions").objects().mapNotNull { runCatching { ManagedInstruction.fromJson(it) }.getOrNull() },
                    subagents = obj.arr("subagents").objects().mapNotNull { runCatching { ManagedSubagent.fromJson(it) }.getOrNull() },
                    hooks = obj.arr("hooks").objects().mapNotNull { runCatching { ManagedHook.fromJson(it) }.getOrNull() },
                )
            } catch (_: Exception) {
                ExtensionRegistry()
            }
        }
    }
}

data class McpProbeResult(
    val ok: Boolean,
    val latencyMs: Int = 0,
    val tools: List<String> = emptyList(),
    val message: String = "",
)

private fun JSONObject.toStringMap(): Map<String, String> {
    val map = linkedMapOf<String, String>()
    keys().forEach { k -> map[k] = optString(k) }
    return map
}

// ── Solbraid Host 插件微内核运行态 DTO（对接 runtime/plugin-kernel.mjs） ─────

data class HostPluginCommand(
    val name: String,
    val description: String,
    val hasHandler: Boolean = true,
)

data class HostPluginPanel(
    val id: String,
    val title: String,
    val description: String = "",
)

data class HostPluginLog(
    val ts: Long,
    val level: String,
    val message: String,
)

data class HostPluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "Solbraid",
    val kind: String = "local", // "core" | "builtin" | "community" | "local"
    val executionTier: String = "trusted-in-process",
    val status: String = "disabled", // "active" | "disabled" | "failed" | "incompatible" | "invalid" | "safe_mode_skipped"
    val enabled: Boolean = false,
    val error: String? = null,
    val dirPath: String = "",
    val requiredCapabilities: List<String> = emptyList(),
    val optionalCapabilities: List<String> = emptyList(),
    val grantedCapabilities: List<String> = emptyList(),
    val settingsFields: List<ElicitationField> = emptyList(),
    val settingsValues: JSONObject = JSONObject(),
    val commands: List<HostPluginCommand> = emptyList(),
    val panels: List<HostPluginPanel> = emptyList(),
    val services: List<String> = emptyList(),
    val recentLogs: List<HostPluginLog> = emptyList(),
) {
    val isCore: Boolean get() = kind == "core"
    val isRemovable: Boolean get() = kind == "local" || kind == "community"

    companion object {
        fun fromJson(obj: JSONObject): HostPluginInfo {
            val caps = obj.optJSONObject("capabilities")
            val schemaObj = obj.optJSONObject("settingsSchema")
            val fields = if (schemaObj != null) parseElicitationSchema(JSONObject().put("schema", schemaObj)) else emptyList()
            val settingsObj = obj.optJSONObject("settings") ?: JSONObject()
            return HostPluginInfo(
                id = obj.optString("id"),
                name = obj.optString("name", obj.optString("id")),
                version = obj.optString("version", "1.0.0"),
                description = obj.optString("description"),
                author = obj.optString("author", "Solbraid"),
                kind = obj.optString("kind", "local"),
                executionTier = obj.optString("executionTier", "trusted-in-process"),
                status = obj.optString("status", "disabled"),
                enabled = obj.optBoolean("enabled", false),
                error = obj.str("error"),
                dirPath = obj.optString("dirPath"),
                requiredCapabilities = caps?.arr("required")?.strings().orEmpty(),
                optionalCapabilities = caps?.arr("optional")?.strings().orEmpty(),
                grantedCapabilities = obj.arr("grantedCapabilities").strings(),
                settingsFields = fields,
                settingsValues = settingsObj,
                commands = obj.arr("commands").objects().map { c ->
                    HostPluginCommand(
                        name = c.optString("name"),
                        description = c.optString("description"),
                        hasHandler = c.optBoolean("hasHandler", true),
                    )
                }.filter { it.name.isNotBlank() },
                panels = obj.arr("panels").objects().map { p ->
                    HostPluginPanel(
                        id = p.optString("id"),
                        title = p.optString("title", p.optString("id")),
                        description = p.optString("description"),
                    )
                }.filter { it.id.isNotBlank() },
                services = obj.arr("services").strings(),
                recentLogs = obj.arr("recentLogs").objects().map { l ->
                    HostPluginLog(
                        ts = l.optLong("ts"),
                        level = l.optString("level", "info"),
                        message = l.optString("message"),
                    )
                },
            )
        }
    }
}

data class HostAgentDriver(
    val id: String,
    val name: String,
    val protocol: String,
    val builtin: Boolean = false,
    val description: String = "",
    val pluginId: String = "",
)

data class HostPluginSnapshot(
    val generationId: String = "",
    val generationSeq: Int = 0,
    val safeMode: Boolean = false,
    val hasLastKnownGood: Boolean = false,
    val executionTier: String = "trusted-in-process",
    val pluginsRoot: String = "",
    val drivers: List<HostAgentDriver> = emptyList(),
    val plugins: List<HostPluginInfo> = emptyList(),
) {
    val corePlugins: List<HostPluginInfo> get() = plugins.filter { it.isCore }
    val extensionPlugins: List<HostPluginInfo> get() = plugins.filterNot { it.isCore }
    val activeCount: Int get() = plugins.count { it.status == "active" }

    companion object {
        fun fromJson(obj: JSONObject): HostPluginSnapshot = HostPluginSnapshot(
            generationId = obj.optString("generationId"),
            generationSeq = obj.optInt("generationSeq", 0),
            safeMode = obj.optBoolean("safeMode", false),
            hasLastKnownGood = obj.optBoolean("hasLastKnownGood", false),
            executionTier = obj.optJSONObject("hostDescriptor")?.optString("executionTier", "trusted-in-process") ?: "trusted-in-process",
            pluginsRoot = obj.optString("pluginsRoot"),
            drivers = obj.arr("drivers").objects().map { d ->
                HostAgentDriver(
                    id = d.optString("id"),
                    name = d.optString("name", d.optString("id")),
                    protocol = d.optString("protocol", "acp"),
                    builtin = d.optBoolean("builtin", false),
                    description = d.optString("description"),
                    pluginId = d.optString("pluginId"),
                )
            }.filter { it.id.isNotBlank() },
            plugins = obj.arr("plugins").objects().map(HostPluginInfo::fromJson),
        )
    }
}

data class HostPluginCommandResult(
    val kind: String, // "prompt" | "artifact" | "notice"
    val prompt: String = "",
    val message: String = "",
    val title: String = "",
    val artifactKind: String = "html",
    val content: String = "",
)

// ── 统一共享资源仓库与强制 AI 安全审查 DTO（对接 runtime/community-registry.mjs） ──

data class AuditFinding(
    val ruleId: String,
    val severity: String,
    val title: String,
    val location: String,
    val detail: String,
) {
    companion object {
        fun fromJson(obj: JSONObject) = AuditFinding(
            ruleId = obj.optString("ruleId"),
            severity = obj.optString("severity", "medium"),
            title = obj.optString("title"),
            location = obj.optString("location"),
            detail = obj.optString("detail"),
        )
    }
}

data class ResourceAuditReport(
    val verdict: String = "pending_review", // "approved" | "rejected" | "pending_review"
    val riskLevel: String = "medium", // "low" | "medium" | "high" | "critical"
    val score: Int = 0,
    val reviewerMode: String = "static-only",
    val reviewerModel: String = "",
    val auditedAt: Long = 0L,
    val sha256: String = "",
    val summary: String = "",
    val checks: List<String> = emptyList(),
    val findings: List<AuditFinding> = emptyList(),
) {
    val approved: Boolean get() = verdict == "approved"

    companion object {
        fun fromJson(obj: JSONObject?) = if (obj == null) ResourceAuditReport() else ResourceAuditReport(
            verdict = obj.optString("verdict", "pending_review"),
            riskLevel = obj.optString("riskLevel", "medium"),
            score = obj.optInt("score", 0),
            reviewerMode = obj.optString("reviewerMode", "static-only"),
            reviewerModel = obj.optString("reviewerModel"),
            auditedAt = obj.optLong("auditedAt", 0L),
            sha256 = obj.optString("sha256"),
            summary = obj.optString("summary"),
            checks = obj.arr("checks").strings(),
            findings = obj.arr("findings").objects().map(AuditFinding::fromJson),
        )
    }
}

data class CommunityRegistryConfig(
    val mode: String = "builtin-local", // "builtin-local" | "http" | "github"
    val serverUrl: String = "",
    val githubRepo: String = "GALIAIS/Solbraid",
    val githubBranch: String = "main",
    val hasGithubToken: Boolean = false,
    val authorName: String = "Solbraid 用户",
    val reviewerBaseUrl: String = "",
    val hasReviewerApiKey: Boolean = false,
    val reviewerModel: String = "",
    val reviewerWireApi: String = "openai",
    val requireLlmReview: Boolean = false,
    val staticRulesCount: Int = 14,
) {
    companion object {
        fun fromJson(obj: JSONObject?) = if (obj == null) CommunityRegistryConfig() else CommunityRegistryConfig(
            mode = obj.optString("mode", "builtin-local"),
            serverUrl = obj.optString("serverUrl"),
            githubRepo = obj.optString("githubRepo", "GALIAIS/Solbraid"),
            githubBranch = obj.optString("githubBranch", "main"),
            hasGithubToken = obj.optBoolean("hasGithubToken", false),
            authorName = obj.optString("authorName", "Solbraid 用户"),
            reviewerBaseUrl = obj.optString("reviewerBaseUrl"),
            hasReviewerApiKey = obj.optBoolean("hasReviewerApiKey", false),
            reviewerModel = obj.optString("reviewerModel"),
            reviewerWireApi = obj.optString("reviewerWireApi", "openai"),
            requireLlmReview = obj.optBoolean("requireLlmReview", false),
            staticRulesCount = obj.optInt("staticRulesCount", 14),
        )
    }
}

data class CommunityResourceItem(
    val id: String,
    val kind: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val sha256: String,
    val downloads: Int,
    val publishedAt: Long,
    val updatedAt: Long,
    val audit: ResourceAuditReport,
) {
    companion object {
        fun fromJson(obj: JSONObject) = CommunityResourceItem(
            id = obj.optString("id"),
            kind = obj.optString("kind"),
            name = obj.optString("name", obj.optString("id")),
            version = obj.optString("version", "1.0.0"),
            author = obj.optString("author", "匿名用户"),
            description = obj.optString("description"),
            tags = obj.arr("tags").strings(),
            sha256 = obj.optString("sha256"),
            downloads = obj.optInt("downloads", 0),
            publishedAt = obj.optLong("publishedAt", 0L),
            updatedAt = obj.optLong("updatedAt", 0L),
            audit = ResourceAuditReport.fromJson(obj.optJSONObject("audit")),
        )
    }
}

data class CommunitySubmissionRecord(
    val submissionId: String,
    val resourceId: String,
    val kind: String,
    val name: String,
    val version: String,
    val author: String,
    val status: String,
    val submittedAt: Long,
    val audit: ResourceAuditReport,
) {
    companion object {
        fun fromJson(obj: JSONObject) = CommunitySubmissionRecord(
            submissionId = obj.optString("submissionId"),
            resourceId = obj.optString("resourceId"),
            kind = obj.optString("kind"),
            name = obj.optString("name"),
            version = obj.optString("version", "1.0.0"),
            author = obj.optString("author"),
            status = obj.optString("status"),
            submittedAt = obj.optLong("submittedAt", 0L),
            audit = ResourceAuditReport.fromJson(obj.optJSONObject("audit")),
        )
    }
}

data class CommunityRegistrySnapshot(
    val config: CommunityRegistryConfig = CommunityRegistryConfig(),
    val sourceLabel: String = "",
    val items: List<CommunityResourceItem> = emptyList(),
    val submissions: List<CommunitySubmissionRecord> = emptyList(),
    val aiReviewerActive: Boolean = true,
) {
    companion object {
        fun fromJson(obj: JSONObject) = CommunityRegistrySnapshot(
            config = CommunityRegistryConfig.fromJson(obj.optJSONObject("config")),
            sourceLabel = obj.optString("sourceLabel"),
            items = obj.arr("items").objects().map(CommunityResourceItem::fromJson),
            submissions = obj.arr("submissions").objects().map(CommunitySubmissionRecord::fromJson),
            aiReviewerActive = obj.optBoolean("aiReviewerActive", true),
        )
    }
}

data class CommunitySubmitResult(
    val status: String, // "approved" | "rejected" | "pending_review" | "pr_bundle_ready" | "pr_submitted"
    val audit: ResourceAuditReport,
    val bundlePath: String = "",
    val prHint: String = "",
    val prUrl: String = "",
    val prNumber: Int = 0,
)
