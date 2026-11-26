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

/** 资源库托管全局/项目级指令块（自动无损同步至 CLAUDE.md / AGENTS.md / GEMINI.md，保留手写内容）。 */
data class ManagedInstruction(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
    val scope: String = "global", // "global" | "project:/abs/path"
    val priority: Int = 0,
) {
    /** 粗略估算指令占用的上下文 Token 数（英文 ~4 字符/tok，中文 ~1.5 字符/tok）。 */
    val estimatedTokens: Int
        get() {
            var cjk = 0
            var other = 0
            for (ch in content) {
                if (ch.code > 0x2E7F) cjk++ else other++
            }
            return ((cjk / 1.5) + (other / 4.0)).toInt().coerceAtLeast(if (content.isNotBlank()) 1 else 0)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("content", content)
        .put("enabled", enabled).put("targets", JSONArray(targets))
        .put("scope", scope).put("priority", priority)

    companion object {
        fun fromJson(obj: JSONObject) = ManagedInstruction(
            id = obj.getString("id"),
            title = obj.optString("title", obj.getString("id")),
            content = obj.optString("content"),
            enabled = obj.optBoolean("enabled", true),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
            scope = obj.optString("scope", "global").ifBlank { "global" },
            priority = obj.optInt("priority", 0),
        )
    }
}

/** 资源库托管子代理定义（自动投影到 .claude/agents / .codex/agents / .agents/agents，支持完整 Frontmatter）。 */
data class ManagedSubagent(
    val id: String,
    val name: String,
    val description: String = "",
    val model: String = "",
    val prompt: String = "",
    val tools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val permissionMode: String = "", // "" | "plan" | "acceptEdits" | "default"
    val maxTurns: Int = 0,
    val scope: String = "global",
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
) {
    fun toMarkdownPreview(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        if (description.isNotBlank()) appendLine("description: $description")
        if (model.isNotBlank()) appendLine("model: $model")
        if (tools.isNotEmpty()) appendLine("tools: ${tools.joinToString(", ")}")
        if (disallowedTools.isNotEmpty()) appendLine("disallowedTools: ${disallowedTools.joinToString(", ")}")
        if (permissionMode.isNotBlank()) appendLine("permissionMode: $permissionMode")
        if (maxTurns > 0) appendLine("maxTurns: $maxTurns")
        appendLine("---")
        appendLine()
        append(prompt.trim())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description).put("model", model)
        .put("prompt", prompt).put("tools", JSONArray(tools)).put("disallowedTools", JSONArray(disallowedTools))
        .put("permissionMode", permissionMode).put("maxTurns", maxTurns).put("scope", scope)
        .put("enabled", enabled).put("targets", JSONArray(targets))

    companion object {
        fun fromJson(obj: JSONObject) = ManagedSubagent(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            description = obj.optString("description"),
            model = obj.optString("model"),
            prompt = obj.optString("prompt"),
            tools = obj.arr("tools").strings(),
            disallowedTools = obj.arr("disallowedTools").strings(),
            permissionMode = obj.optString("permissionMode"),
            maxTurns = obj.optInt("maxTurns", 0),
            scope = obj.optString("scope", "global").ifBlank { "global" },
            enabled = obj.optBoolean("enabled", true),
            targets = obj.arr("targets").strings().ifEmpty { listOf("all") },
        )
    }
}

/** 资源库托管生命周期钩子（支持全部 8 类官方生命周期事件、超时控制、作用域与现场试运行）。 */
data class ManagedHook(
    val id: String,
    val name: String,
    val event: String = "PostToolUse",
    val matcher: String = "*",
    val command: String = "",
    val timeoutSec: Int = 30,
    val scope: String = "global",
    val description: String = "",
    val enabled: Boolean = true,
    val targets: List<String> = listOf("all"),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("event", event).put("matcher", matcher)
        .put("command", command).put("timeoutSec", timeoutSec).put("scope", scope)
        .put("description", description).put("enabled", enabled).put("targets", JSONArray(targets))

    companion object {
        fun fromJson(obj: JSONObject) = ManagedHook(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            event = obj.optString("event", "PostToolUse"),
            matcher = obj.optString("matcher", "*"),
            command = obj.optString("command"),
            timeoutSec = obj.optInt("timeoutSec", 30).coerceIn(1, 300),
            scope = obj.optString("scope", "global").ifBlank { "global" },
            description = obj.optString("description"),
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

data class McpToolInfo(
    val name: String,
    val description: String = "",
)

data class McpProbeResult(
    val ok: Boolean,
    val latencyMs: Int = 0,
    val tools: List<String> = emptyList(),
    val toolDetails: List<McpToolInfo> = emptyList(),
    val message: String = "",
)

data class HookTestResult(
    val ok: Boolean,
    val exitCode: Int = -1,
    val blocked: Boolean = false,
    val latencyMs: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
)

data class SkillCompanionFile(
    val relPath: String,
    val absPath: String,
    val size: Long = 0L,
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

data class HostPluginComposerAction(
    val pluginId: String,
    val id: String,
    val label: String,
    val description: String = "",
    val command: String? = null,
    val panelId: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject, fallbackPluginId: String = "") = HostPluginComposerAction(
            pluginId = obj.optString("pluginId", fallbackPluginId),
            id = obj.optString("id"),
            label = obj.optString("label", obj.optString("id")),
            description = obj.optString("description"),
            command = obj.str("command"),
            panelId = obj.str("panelId"),
        )
    }
}

data class HostPluginSourceSnapshot(
    val id: String,
    val pluginId: String,
    val version: String = "0.0.0",
    val createdAt: Long = 0L,
    val note: String = "",
    val fileCount: Int = 0,
) {
    companion object {
        fun fromJson(obj: JSONObject) = HostPluginSourceSnapshot(
            id = obj.optString("id"),
            pluginId = obj.optString("pluginId"),
            version = obj.optString("version", "0.0.0"),
            createdAt = obj.optLong("createdAt", 0L),
            note = obj.optString("note"),
            fileCount = obj.optInt("fileCount", 0),
        )
    }
}

data class HostPluginSurface(
    val pluginId: String,
    val id: String,
    val title: String,
    val description: String = "",
) {
    companion object {
        fun fromJson(obj: JSONObject, fallbackPluginId: String = "") = HostPluginSurface(
            pluginId = obj.optString("pluginId", fallbackPluginId),
            id = obj.optString("id"),
            title = obj.optString("title", obj.optString("id")),
            description = obj.optString("description"),
        )
    }
}

data class HostPluginUiSlot(
    val pluginId: String,
    val id: String,
    val slot: String, // 21 slots: nav.tab, global.overlay, *.replace, home/chat/files/ssh/terminal/settings/studio.*
    val label: String = "",
    val description: String = "",
    val badge: String = "",
    val surfaceId: String? = null,
    val panelId: String? = null,
    val command: String? = null,
    val hasRender: Boolean = false,
    val routes: List<String> = emptyList(),
    val alignment: String = "bottomEnd",
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val draggable: Boolean = true,
    val displayMode: String = "",
) {
    fun matchesRoute(currentRoute: String): Boolean {
        if (routes.isEmpty()) return true
        val norm = currentRoute.trim().lowercase()
        return routes.any { raw ->
            val r = raw.trim().lowercase()
            r == "*" || r == norm || (r.endsWith("*") && norm.startsWith(r.removeSuffix("*")))
        }
    }

    companion object {
        fun fromJson(obj: JSONObject, fallbackPluginId: String = "") = HostPluginUiSlot(
            pluginId = obj.optString("pluginId", fallbackPluginId),
            id = obj.optString("id"),
            slot = obj.optString("slot"),
            label = obj.optString("label"),
            description = obj.optString("description"),
            badge = obj.optString("badge"),
            surfaceId = obj.str("surfaceId"),
            panelId = obj.str("panelId"),
            command = obj.str("command"),
            hasRender = obj.optBoolean("hasRender", false),
            routes = obj.arr("routes").strings(),
            alignment = obj.optString("alignment", "bottomEnd"),
            offsetX = obj.optInt("offsetX", 0),
            offsetY = obj.optInt("offsetY", 0),
            draggable = obj.optBoolean("draggable", obj.optString("slot") == "global.overlay"),
            displayMode = obj.optString("displayMode", ""),
        )
    }
}

data class HostPluginTranslator(
    val pluginId: String,
    val id: String,
    val name: String,
    val description: String = "",
    val baseProtocol: String = "openai_chat",
) {
    companion object {
        fun fromJson(obj: JSONObject, fallbackPluginId: String = "") = HostPluginTranslator(
            pluginId = obj.optString("pluginId", fallbackPluginId),
            id = obj.optString("id"),
            name = obj.optString("name", obj.optString("id")),
            description = obj.optString("description"),
            baseProtocol = obj.optString("baseProtocol", "openai_chat"),
        )
    }
}

data class HostPluginSduiPayload(
    val pluginId: String,
    val surfaceId: String,
    val title: String,
    val subtitle: String = "",
    val nodes: List<JSONObject> = emptyList(),
    val hidden: Boolean = false,
) {
    companion object {
        fun fromJson(obj: JSONObject) = HostPluginSduiPayload(
            pluginId = obj.optString("pluginId"),
            surfaceId = obj.optString("surfaceId", obj.optString("slotId")),
            title = obj.optString("title"),
            subtitle = obj.optString("subtitle"),
            nodes = obj.arr("nodes").objects(),
            hidden = obj.optBoolean("hidden", false),
        )
    }
}

data class HostPluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "Solbraid",
    val kind: String = "local", // "core" | "builtin" | "community" | "local"
    val overriddenBuiltin: Boolean = false,
    val executionTier: String = "trusted-in-process",
    val status: String = "disabled", // "active" | "disabled" | "failed" | "incompatible" | "invalid" | "safe_mode_skipped"
    val enabled: Boolean = false,
    val error: String? = null,
    val dirPath: String = "",
    val snapshotCount: Int = 0,
    val requiredCapabilities: List<String> = emptyList(),
    val optionalCapabilities: List<String> = emptyList(),
    val grantedCapabilities: List<String> = emptyList(),
    val settingsFields: List<ElicitationField> = emptyList(),
    val settingsValues: JSONObject = JSONObject(),
    val commands: List<HostPluginCommand> = emptyList(),
    val panels: List<HostPluginPanel> = emptyList(),
    val surfaces: List<HostPluginSurface> = emptyList(),
    val uiSlots: List<HostPluginUiSlot> = emptyList(),
    val composerActions: List<HostPluginComposerAction> = emptyList(),
    val translators: List<HostPluginTranslator> = emptyList(),
    val services: List<String> = emptyList(),
    val recentLogs: List<HostPluginLog> = emptyList(),
) {
    val isCore: Boolean get() = kind == "core"
    val isWorkerIsolated: Boolean get() = executionTier == "worker-isolated"
    val isRemovable: Boolean get() = (kind == "local" || kind == "community") && !overriddenBuiltin

    companion object {
        fun fromJson(obj: JSONObject): HostPluginInfo {
            val id = obj.optString("id")
            val caps = obj.optJSONObject("capabilities")
            val schemaObj = obj.optJSONObject("settingsSchema")
            val fields = if (schemaObj != null) parseElicitationSchema(JSONObject().put("schema", schemaObj)) else emptyList()
            val settingsObj = obj.optJSONObject("settings") ?: JSONObject()
            return HostPluginInfo(
                id = id,
                name = obj.optString("name", id),
                version = obj.optString("version", "1.0.0"),
                description = obj.optString("description"),
                author = obj.optString("author", "Solbraid"),
                kind = obj.optString("kind", "local"),
                overriddenBuiltin = obj.optBoolean("overriddenBuiltin", false),
                executionTier = obj.optString("executionTier", "trusted-in-process"),
                status = obj.optString("status", "disabled"),
                enabled = obj.optBoolean("enabled", false),
                error = obj.str("error"),
                dirPath = obj.optString("dirPath"),
                snapshotCount = obj.optInt("snapshotCount", 0),
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
                surfaces = obj.arr("surfaces").objects().map { s ->
                    HostPluginSurface.fromJson(s, id)
                }.filter { it.id.isNotBlank() },
                uiSlots = obj.arr("uiSlots").objects().map { u ->
                    HostPluginUiSlot.fromJson(u, id)
                }.filter { it.id.isNotBlank() },
                composerActions = obj.arr("composerActions").objects().map { a ->
                    HostPluginComposerAction.fromJson(a, id)
                }.filter { it.id.isNotBlank() },
                translators = obj.arr("translators").objects().map { t ->
                    HostPluginTranslator.fromJson(t, id)
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
    val executable: Boolean = false,
    val description: String = "",
    val pluginId: String = "",
)

data class HostPluginSnapshot(
    val generationId: String = "",
    val generationSeq: Int = 0,
    val safeMode: Boolean = false,
    val hasLastKnownGood: Boolean = false,
    val agentControlAvailable: Boolean = false,
    val executionTier: String = "trusted-in-process",
    val pluginsRoot: String = "",
    val studioOverrides: List<String> = emptyList(),
    val drivers: List<HostAgentDriver> = emptyList(),
    val translators: List<HostPluginTranslator> = emptyList(),
    val surfaces: List<HostPluginSurface> = emptyList(),
    val uiSlots: List<HostPluginUiSlot> = emptyList(),
    val composerActions: List<HostPluginComposerAction> = emptyList(),
    val plugins: List<HostPluginInfo> = emptyList(),
) {
    val corePlugins: List<HostPluginInfo> = plugins.filter { it.isCore }
    val extensionPlugins: List<HostPluginInfo> = plugins.filterNot { it.isCore }
    val activeCount: Int = plugins.count { it.status == "active" }

    companion object {
        fun fromJson(obj: JSONObject): HostPluginSnapshot = HostPluginSnapshot(
            generationId = obj.optString("generationId"),
            generationSeq = obj.optInt("generationSeq", 0),
            safeMode = obj.optBoolean("safeMode", false),
            hasLastKnownGood = obj.optBoolean("hasLastKnownGood", false),
            agentControlAvailable = obj.optBoolean("agentControlAvailable", false),
            executionTier = obj.optJSONObject("hostDescriptor")?.optString("executionTier", "trusted-in-process") ?: "trusted-in-process",
            pluginsRoot = obj.optString("pluginsRoot"),
            studioOverrides = obj.arr("studioOverrides").strings(),
            drivers = obj.arr("drivers").objects().map { d ->
                HostAgentDriver(
                    id = d.optString("id"),
                    name = d.optString("name", d.optString("id")),
                    protocol = d.optString("protocol", "acp"),
                    builtin = d.optBoolean("builtin", false),
                    executable = d.optBoolean("executable", false),
                    description = d.optString("description"),
                    pluginId = d.optString("pluginId"),
                )
            }.filter { it.id.isNotBlank() },
            translators = obj.arr("translators").objects().map { t ->
                HostPluginTranslator.fromJson(t)
            }.filter { it.id.isNotBlank() },
            surfaces = obj.arr("surfaces").objects().map { s ->
                HostPluginSurface.fromJson(s)
            }.filter { it.id.isNotBlank() },
            uiSlots = obj.arr("uiSlots").objects().map { u ->
                HostPluginUiSlot.fromJson(u)
            }.filter { it.id.isNotBlank() },
            composerActions = obj.arr("composerActions").objects().map { a ->
                HostPluginComposerAction.fromJson(a)
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
