package com.galiais.agent.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Solbraid 统一扩展中心仓库：单源管理 MCP、Skills 与原子化 Plugin 插件包，
 * 密钥经 Android Keystore AES-GCM 加密，变更时自动投影到 Codex / Claude / 外部 ACP Agent。
 */
class ExtensionRepository(
    private val runtime: RuntimeClient,
    private val settings: AppSettings,
    private val onNotify: (String) -> Unit,
    private val onReloadCodexMcp: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncMutex = Mutex()
    private val skillMutex = Mutex()
    private val hostPluginsMutex = Mutex()

    private val registryFlow = MutableStateFlow(ExtensionRegistry.fromJson(settings.extensionRegistryJson()))
    val registry: StateFlow<ExtensionRegistry> = registryFlow.asStateFlow()

    private val workspaceSkillsFlow = MutableStateFlow<List<ManagedSkill>>(emptyList())
    val workspaceSkills: StateFlow<List<ManagedSkill>> = workspaceSkillsFlow.asStateFlow()

    private val probesFlow = MutableStateFlow<Map<String, McpProbeResult>>(emptyMap())
    val probes: StateFlow<Map<String, McpProbeResult>> = probesFlow.asStateFlow()

    private val syncingFlow = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncingFlow.asStateFlow()

    private val hostPluginsFlow = MutableStateFlow(HostPluginSnapshot())
    val hostPlugins: StateFlow<HostPluginSnapshot> = hostPluginsFlow.asStateFlow()

    private val hookTestsFlow = MutableStateFlow<Map<String, HookTestResult>>(emptyMap())
    val hookTests: StateFlow<Map<String, HookTestResult>> = hookTestsFlow.asStateFlow()

    private fun commit(transform: (ExtensionRegistry) -> ExtensionRegistry) {
        val next = transform(registryFlow.value)
        registryFlow.value = next
        settings.setExtensionRegistryJson(next.toJson().toString())
        scheduleSync()
    }

    private fun scheduleSync() {
        if (!runtime.snapshot.value.ready) return
        scope.launch {
            try {
                syncNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w("GALIAIS", "扩展同步失败", e)
                onNotify("扩展已保存，同步失败：${e.message ?: "请稍后重试"}")
            }
        }
    }

    /** 将全部托管 MCP/Skills/Plugins 与 Keystore 解密密钥同步投影到 Linux 三大引擎。 */
    suspend fun syncNow(): JSONObject = syncMutex.withLock {
        check(runtime.snapshot.value.ready && runtime.hubAvailable) { "运行环境未就绪，请稍后同步" }
        syncingFlow.value = true
        try {
            val payload = jsonOf(
                "registry" to registryFlow.value.toJson(),
                "secrets" to settings.exportExtensionSecretsJson(),
            )
            val res = runtime.hubRequest("extensions/sync", payload, timeoutMs = 20_000)
            check(res.optBoolean("ok")) { res.str("message") ?: "扩展同步失败" }
            if (res.optBoolean("codexConfigChanged", true)) {
                onReloadCodexMcp()
            }
            res
        } finally {
            syncingFlow.value = false
        }
    }

    // ── MCP 管理 ─────────────────────────────────────────────────────────────

    fun upsertMcp(
        server: ManagedMcpServer,
        secretValues: Map<String, String> = emptyMap(),
        removedSecretKeys: Set<String> = emptySet(),
    ) {
        removedSecretKeys.forEach { k -> settings.setExtensionSecret(server.id, k, null) }
        secretValues.forEach { (k, v) -> settings.setExtensionSecret(server.id, k, v) }
        commit { reg ->
            val list = reg.mcpServers.filterNot { it.id == server.id } + server
            reg.copy(mcpServers = list)
        }
        onNotify("已保存 MCP：${server.name}")
    }

    fun duplicateMcp(id: String) {
        val src = registryFlow.value.mcpServers.firstOrNull { it.id == id } ?: return
        val newId = "${src.id}-copy-${System.currentTimeMillis().toString(36).takeLast(4)}"
        val copy = src.copy(id = newId, name = "${src.name} 副本", pluginId = null, enabled = false)
        commit { reg -> reg.copy(mcpServers = reg.mcpServers + copy) }
        onNotify("已创建副本：${copy.name}（默认关闭，可按需配置密钥）")
    }

    /** 解析标准 Claude / Cursor / Codex `mcpServers` JSON 或单条 MCP JSON 并批量导入。 */
    fun importMcpServersFromJson(rawJson: String): Int {
        val root = JSONObject(rawJson.trim())
        val mapObj = root.optJSONObject("mcpServers") ?: if (root.has("command") || root.has("url") || root.has("serverUrl")) {
            JSONObject().put(root.optString("name", root.optString("id", "imported-mcp")), root)
        } else {
            root
        }
        val added = mutableListOf<ManagedMcpServer>()
        val keys = mapObj.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next().trim()
            val item = mapObj.optJSONObject(rawKey) ?: continue
            val url = item.str("url") ?: item.str("serverUrl")
            val command = item.str("command")
            if (url.isNullOrBlank() && command.isNullOrBlank()) continue
            val transport = if (!url.isNullOrBlank() && command.isNullOrBlank()) "http" else item.optString("transport", if (!url.isNullOrBlank()) "http" else "stdio")
            val slug = rawKey.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "mcp-${System.currentTimeMillis().toString(36)}" }
            val args = item.arr("args").strings()
            val envObj = item.optJSONObject("env")
            val envMap = linkedMapOf<String, String>()
            envObj?.keys()?.forEach { k -> envMap[k] = envObj.optString(k) }
            val hdrObj = item.optJSONObject("headers") ?: item.optJSONObject("http_headers")
            val hdrMap = linkedMapOf<String, String>()
            hdrObj?.keys()?.forEach { k -> hdrMap[k] = hdrObj.optString(k) }
            added += ManagedMcpServer(
                id = slug,
                name = item.optString("name", rawKey),
                transport = if (transport == "http") "http" else "stdio",
                command = if (transport == "http") null else command,
                args = if (transport == "http") emptyList() else args,
                url = if (transport == "http") url else null,
                env = if (transport == "http") emptyMap() else envMap,
                headers = if (transport == "http") hdrMap else emptyMap(),
                enabled = true,
                scope = "global",
                targets = listOf("all"),
            )
        }
        require(added.isNotEmpty()) { "未在 JSON 中找到有效的 MCP 服务（需包含 command 或 url/serverUrl）" }
        commit { reg ->
            val incomingIds = added.map { it.id }.toSet()
            reg.copy(mcpServers = reg.mcpServers.filterNot { it.id in incomingIds } + added)
        }
        onNotify("已导入 ${added.size} 个 MCP 服务器")
        return added.size
    }

    /** 导出标准 `mcpServers` JSON（不含 Keystore 加密密钥明文）。 */
    fun exportMcpServersJson(): String {
        val servers = JSONObject()
        registryFlow.value.mcpServers.forEach { srv ->
            val obj = JSONObject()
            if (srv.transport == "http") {
                obj.put("url", srv.url.orEmpty())
                if (srv.headers.isNotEmpty()) obj.put("headers", JSONObject(srv.headers))
            } else {
                obj.put("command", srv.command.orEmpty())
                obj.put("args", JSONArray(srv.args))
                if (srv.env.isNotEmpty()) obj.put("env", JSONObject(srv.env))
            }
            servers.put(srv.id, obj)
        }
        return JSONObject().put("mcpServers", servers).toString(2)
    }

    fun toggleMcp(id: String, enabled: Boolean) {
        commit { reg ->
            reg.copy(mcpServers = reg.mcpServers.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    fun deleteMcp(id: String) {
        settings.removeExtensionBucketSecrets(id)
        probesFlow.value = probesFlow.value - id
        commit { reg -> reg.copy(mcpServers = reg.mcpServers.filterNot { it.id == id }) }
        onNotify("已移除 MCP 服务器")
    }

    suspend fun probeMcp(server: ManagedMcpServer): McpProbeResult {
        if (!runtime.hubAvailable) {
            val res = McpProbeResult(false, message = "运行环境未就绪")
            probesFlow.value = probesFlow.value + (server.id to res)
            return res
        }
        val secrets = JSONObject()
        val bucket = server.id
        val pluginBucket = server.pluginId?.let { "plugin:$it" }
        (server.secretEnvKeys + server.secretHeaderKeys).distinct().forEach { k ->
            val v = settings.extensionSecret(bucket, k) ?: pluginBucket?.let { settings.extensionSecret(it, k) }
            if (!v.isNullOrEmpty()) secrets.put(k, v)
        }
        return try {
            val raw = runtime.hubRequest("extensions/probeMcp", jsonOf("server" to server.toJson(), "secrets" to secrets), timeoutMs = 15_000)
            val tools = raw.arr("tools").strings()
            val details = raw.arr("toolDetails").objects().map { o ->
                McpToolInfo(name = o.optString("name"), description = o.optString("description"))
            }.filter { it.name.isNotBlank() }.ifEmpty { tools.map { McpToolInfo(it) } }
            val res = McpProbeResult(
                ok = raw.optBoolean("ok"),
                latencyMs = raw.optInt("latencyMs"),
                tools = tools,
                toolDetails = details,
                message = raw.str("message") ?: raw.str("error") ?: if (raw.optBoolean("ok")) "连通正常" else "连接失败",
            )
            probesFlow.value = probesFlow.value + (server.id to res)
            res
        } catch (e: Exception) {
            val res = McpProbeResult(false, message = e.message ?: "探测失败")
            probesFlow.value = probesFlow.value + (server.id to res)
            res
        }
    }

    // ── Skills 管理 ──────────────────────────────────────────────────────────

    suspend fun refreshSkills(cwd: String? = null) = skillMutex.withLock {
        if (!runtime.hubAvailable) return@withLock
        val res = runtime.hubRequest("extensions/listSkills", jsonOf("cwd" to (cwd ?: settings.state.value.defaultCwd)), timeoutMs = 15_000)
        val ws = res.arr("workspace").objects().map { ManagedSkill.fromJson(it).copy(source = "workspace") }
        workspaceSkillsFlow.value = ws
        val managedOnDisk = res.arr("managed").objects().map { ManagedSkill.fromJson(it) }
        if (managedOnDisk.isNotEmpty()) {
            val existingById = registryFlow.value.skills.associateBy { it.id }
            val merged = registryFlow.value.skills.toMutableList()
            for (diskSkill in managedOnDisk) {
                val cur = existingById[diskSkill.id]
                if (cur == null) merged += diskSkill
                else {
                    val idx = merged.indexOfFirst { it.id == diskSkill.id }
                    if (idx >= 0) merged[idx] = cur.copy(name = diskSkill.name, description = diskSkill.description, body = diskSkill.body, dirPath = diskSkill.dirPath)
                }
            }
            if (merged != registryFlow.value.skills) {
                registryFlow.value = registryFlow.value.copy(skills = merged)
                settings.setExtensionRegistryJson(registryFlow.value.toJson().toString())
            }
        }
    }

    suspend fun saveLocalSkill(
        id: String?,
        name: String,
        description: String,
        body: String,
        targets: List<String> = listOf("all"),
        scope: String = "global",
    ) = skillMutex.withLock {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("extensions/saveSkill", jsonOf("id" to id, "name" to name, "description" to description, "body" to body), timeoutMs = 15_000)
        val savedId = res.getString("id")
        val dirPath = res.getString("dirPath")
        val existing = registryFlow.value.skills.firstOrNull { it.id == savedId }
        val skill = ManagedSkill(
            id = savedId,
            name = res.optString("name", name),
            description = description,
            body = body,
            dirPath = dirPath,
            source = existing?.source ?: "local",
            sourceUrl = existing?.sourceUrl,
            enabled = existing?.enabled ?: true,
            scope = scope.ifBlank { existing?.scope ?: "global" },
            targets = targets.ifEmpty { existing?.targets ?: listOf("all") },
            pluginId = existing?.pluginId,
        )
        commit { reg -> reg.copy(skills = reg.skills.filterNot { it.id == savedId } + skill) }
        onNotify("已保存技能：${skill.name}")
    }

    /** 解析完整 SKILL.md（含 YAML Frontmatter）并保存为托管技能。 */
    suspend fun importSkillFromMarkdown(rawMarkdown: String, fallbackName: String = "custom-skill") {
        val text = rawMarkdown.replace("\r\n", "\n").trim()
        require(text.isNotBlank()) { "SKILL.md 内容为空" }
        val fmMatch = Regex("^---\\n([\\s\\S]*?)\\n---\\n?([\\s\\S]*)$").find(text)
        var name = fallbackName
        var desc = ""
        var body = text
        if (fmMatch != null) {
            body = fmMatch.groupValues[2].trim()
            fmMatch.groupValues[1].lineSequence().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    val k = line.substring(0, i).trim().lowercase()
                    val v = line.substring(i + 1).trim().trim('"', '\'')
                    if (k == "name" && v.isNotBlank()) name = v
                    if (k == "description" && v.isNotBlank()) desc = v
                }
            }
        }
        saveLocalSkill(id = null, name = name, description = desc, body = body.ifBlank { text })
    }

    suspend fun listSkillFiles(dirPath: String): List<SkillCompanionFile> {
        if (!runtime.hubAvailable || dirPath.isBlank()) return emptyList()
        val res = runtime.hubRequest("extensions/listSkillFiles", jsonOf("dirPath" to dirPath), timeoutMs = 10_000)
        return res.arr("files").objects().map { o ->
            SkillCompanionFile(
                relPath = o.optString("relPath"),
                absPath = o.optString("absPath"),
                size = o.optLong("size", 0L),
            )
        }.filter { it.relPath.isNotBlank() }
    }

    suspend fun importSkillFromGitHub(url: String) = skillMutex.withLock {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("extensions/importSkillGitHub", jsonOf("url" to url.trim()), timeoutMs = 100_000)
        val skill = ManagedSkill(
            id = res.getString("id"),
            name = res.optString("name", res.getString("id")),
            description = res.optString("description"),
            body = res.optString("body"),
            dirPath = res.getString("dirPath"),
            source = "github",
            sourceUrl = url.trim(),
            enabled = true,
        )
        commit { reg -> reg.copy(skills = reg.skills.filterNot { it.id == skill.id } + skill) }
        onNotify("已导入技能：${skill.name}")
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        commit { reg -> reg.copy(skills = reg.skills.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    suspend fun deleteSkill(id: String) = scope.async {
        try {
            skillMutex.withLock {
                check(runtime.snapshot.value.ready && runtime.hubAvailable) { "请先启动运行环境，再删除技能" }
                val res = runtime.hubRequest("extensions/deleteSkill", jsonOf("id" to id), timeoutMs = 10_000)
                check(res.optBoolean("ok")) { res.str("message") ?: "技能文件删除失败" }
                commit { reg -> reg.copy(skills = reg.skills.filterNot { it.id == id }) }
                onNotify("已删除技能")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            onNotify("技能删除未完成：${e.message ?: "请稍后重试"}")
            throw e
        }
    }.await()

    // ── Plugin 插件包管理 ────────────────────────────────────────────────────

    suspend fun importPluginFromGitHub(url: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("extensions/importPluginGitHub", jsonOf("url" to url.trim()), timeoutMs = 130_000)
        val pluginId = res.getString("id")
        val plugin = ManagedPlugin(
            id = pluginId,
            name = res.optString("name", pluginId),
            version = res.optString("version", "1.0.0"),
            description = res.optString("description"),
            source = "github",
            sourceUrl = url.trim(),
            dirPath = res.optString("dirPath"),
            enabled = true,
            requiredSecrets = res.arr("requiredSecrets").objects().map(PluginSecretSpec::fromJson),
            commands = res.arr("commands").objects().map(PluginCommand::fromJson),
        )
        val mcps = res.arr("mcpServers").objects().map { ManagedMcpServer.fromJson(it).copy(pluginId = pluginId) }
        val skills = res.arr("skills").objects().map { ManagedSkill.fromJson(it).copy(source = "plugin", pluginId = pluginId) }
        commit { reg ->
            reg.copy(
                plugins = reg.plugins.filterNot { it.id == pluginId } + plugin,
                mcpServers = reg.mcpServers.filterNot { it.pluginId == pluginId } + mcps,
                skills = reg.skills.filterNot { it.pluginId == pluginId } + skills,
            )
        }
        onNotify("已安装插件包：${plugin.name}（${mcps.size} MCP · ${skills.size} 技能）")
    }

    fun saveLocalPluginPack(plugin: ManagedPlugin) {
        commit { reg ->
            reg.copy(plugins = reg.plugins.filterNot { it.id == plugin.id } + plugin)
        }
        onNotify("已保存插件包：${plugin.name}")
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        commit { reg ->
            reg.copy(
                plugins = reg.plugins.map { if (it.id == pluginId) it.copy(enabled = enabled) else it },
                mcpServers = reg.mcpServers.map { if (it.pluginId == pluginId) it.copy(enabled = enabled) else it },
                skills = reg.skills.map { if (it.pluginId == pluginId) it.copy(enabled = enabled) else it },
            )
        }
    }

    fun updatePluginSecrets(pluginId: String, secrets: Map<String, String>) {
        secrets.forEach { (k, v) -> settings.setExtensionSecret("plugin:$pluginId", k, v) }
        onNotify("已保存插件密钥")
        scheduleSync()
    }

    suspend fun uninstallPlugin(pluginId: String) {
        if (runtime.hubAvailable) {
            runCatching { runtime.hubRequest("extensions/uninstallPlugin", jsonOf("pluginId" to pluginId), timeoutMs = 15_000) }
        }
        settings.removeExtensionBucketSecrets("plugin:$pluginId")
        commit { reg ->
            reg.copy(
                plugins = reg.plugins.filterNot { it.id == pluginId },
                mcpServers = reg.mcpServers.filterNot { it.pluginId == pluginId },
                skills = reg.skills.filterNot { it.pluginId == pluginId },
            )
        }
        onNotify("已卸载插件包")
    }

    /** 汇总所有已启用插件包与 Host 微内核插件提供的 `/` 快捷指令，自动注入聊天页指令补全。 */
    fun activePluginSlashCommands(): List<SlashCommand> {
        val fromPacks = registryFlow.value.plugins
            .filter { it.enabled }
            .flatMap { plugin ->
                plugin.commands.map { cmd ->
                    SlashCommand(
                        name = cmd.name,
                        description = "${cmd.description}（${plugin.name}）",
                        localAction = "plugin:${plugin.id}:${cmd.name}",
                    )
                }
            }
        val fromHost = hostPluginsFlow.value.plugins
            .filter { it.status == "active" }
            .flatMap { plugin ->
                plugin.commands.map { cmd ->
                    SlashCommand(
                        name = cmd.name,
                        description = "${cmd.description.ifBlank { "执行插件命令" }}（${plugin.name}）",
                        localAction = "hostplugin:${plugin.id}:${cmd.name}",
                    )
                }
            }
        return (fromHost + fromPacks).distinctBy { it.name }
    }

    /** 汇总所有已激活 Host 插件注册到聊天输入框 `+` 菜单的快捷动作（安全模式下隐藏）。 */
    fun activePluginComposerActions(): List<HostPluginComposerAction> {
        val snap = hostPluginsFlow.value
        if (snap.safeMode) return emptyList()
        return snap.plugins
            .filter { it.status == "active" }
            .flatMap { it.composerActions }
            .distinctBy { "${it.pluginId}:${it.id}" }
    }

    /** 按原生槽位名过滤已激活插件的 UI 挂载项（安全模式下隐藏）。 */
    fun activePluginUiSlots(slotName: String): List<HostPluginUiSlot> {
        val snap = hostPluginsFlow.value
        if (snap.safeMode) return emptyList()
        return snap.plugins
            .filter { it.status == "active" }
            .flatMap { it.uiSlots }
            .filter { it.slot == slotName }
            .distinctBy { "${it.pluginId}:${it.id}" }
    }

    // ── Solbraid Host 插件微内核管理（Generation / 安全模式 / Schema 设置 / WebView 面板） ──

    suspend fun refreshHostPlugins(): HostPluginSnapshot = hostPluginsMutex.withLock {
        if (!runtime.hubAvailable) return@withLock hostPluginsFlow.value
        val res = runtime.hubRequest("plugins/list", JSONObject(), timeoutMs = 12_000)
        val snap = HostPluginSnapshot.fromJson(res)
        val prev = hostPluginsFlow.value
        if (prev.generationId.isEmpty() || snap.generationSeq >= prev.generationSeq || snap.generationId != prev.generationId) {
            hostPluginsFlow.value = snap
        }
        hostPluginsFlow.value
    }

    suspend fun toggleHostPlugin(pluginId: String, enabled: Boolean): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/toggle", jsonOf("pluginId" to pluginId, "enabled" to enabled), timeoutMs = 15_000)
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        return snap
    }

    suspend fun updateHostPluginSettings(pluginId: String, settingsJson: JSONObject) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("plugins/updateSettings", jsonOf("pluginId" to pluginId, "settings" to settingsJson), timeoutMs = 12_000)
        refreshHostPlugins()
        onNotify("已保存插件配置")
    }

    suspend fun updateHostPluginGrants(pluginId: String, grantedCapabilities: List<String>): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/updateGrants",
            jsonOf("pluginId" to pluginId, "grantedCapabilities" to JSONArray(grantedCapabilities)),
            timeoutMs = 15_000,
        )
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify("已更新插件能力授权")
        return snap
    }

    suspend fun exportLocalHostPluginBundle(pluginId: String): JSONObject {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        return runtime.hubRequest("plugins/exportBundle", jsonOf("pluginId" to pluginId), timeoutMs = 15_000)
    }

    /** 导入本地/JSON 导出的 Host 插件包（经清单与静态安全门禁校验后热激活）。 */
    suspend fun importLocalHostPluginBundle(rawJson: String): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val root = JSONObject(rawJson.trim())
        val payload = root.optJSONObject("payload") ?: root
        require(payload.optJSONObject("manifest") != null) { "JSON 中缺少 manifest 字段" }
        val snapJson = runtime.hubRequest("plugins/installBundle", payload, timeoutMs = 20_000)
        val snap = HostPluginSnapshot.fromJson(snapJson)
        hostPluginsFlow.value = snap
        onNotify("已通过安全检查并导入 Host 插件")
        return snap
    }

    /** 导出全部托管资源库配置（指令/MCP/子代理/钩子/插件包元信息，不含加密密钥明文）。 */
    fun exportAllExtensionsJson(): String = JSONObject()
        .put("format", "solbraid-extensions-v1")
        .put("exportedAt", System.currentTimeMillis())
        .put("registry", registryFlow.value.toJson())
        .toString(2)

    /** 合并导入资源库备份 JSON（同名覆盖、异名保留，绝不覆盖加密密钥）。 */
    fun importAllExtensionsJson(rawJson: String): String {
        val root = JSONObject(rawJson.trim())
        val regObj = root.optJSONObject("registry") ?: root
        val incoming = ExtensionRegistry.fromJson(regObj.toString())
        val importedCnt = incoming.instructions.size + incoming.mcpServers.size + incoming.subagents.size + incoming.hooks.size + incoming.plugins.size
        require(importedCnt > 0) { "未在备份 JSON 中找到可导入的资源项" }
        commit { cur ->
            fun <T> mergeById(old: List<T>, add: List<T>, idOf: (T) -> String): List<T> {
                val addIds = add.map(idOf).toSet()
                return old.filterNot { idOf(it) in addIds } + add
            }
            cur.copy(
                instructions = mergeById(cur.instructions, incoming.instructions) { it.id },
                mcpServers = mergeById(cur.mcpServers, incoming.mcpServers) { it.id },
                subagents = mergeById(cur.subagents, incoming.subagents) { it.id },
                hooks = mergeById(cur.hooks, incoming.hooks) { it.id },
                plugins = mergeById(cur.plugins, incoming.plugins) { it.id },
            )
        }
        val summary = "指令 ${incoming.instructions.size} · MCP ${incoming.mcpServers.size} · 子代理 ${incoming.subagents.size} · 钩子 ${incoming.hooks.size}"
        onNotify("已导入资源库：$summary")
        return summary
    }

    suspend fun setHostPluginSafeMode(enabled: Boolean): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/setSafeMode", jsonOf("enabled" to enabled), timeoutMs = 15_000)
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify(if (enabled) "已切入插件安全模式（仅运行核心内核）" else "已退出插件安全模式并重载插件")
        return snap
    }

    suspend fun rollbackHostPlugins(): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/rollback", JSONObject(), timeoutMs = 15_000)
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify("已回滚至上次正常插件快照")
        return snap
    }

    suspend fun reloadHostPlugins(): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/reload", JSONObject(), timeoutMs = 15_000)
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify("已重载 Host 插件代际（${snap.generationId}）")
        return snap
    }

    suspend fun reloadSingleHostPlugin(pluginId: String): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/reloadPlugin", jsonOf("pluginId" to pluginId), timeoutMs = 15_000)
        val snapObj = res.optJSONObject("snapshot")
        val snap = if (snapObj != null) HostPluginSnapshot.fromJson(snapObj) else refreshHostPlugins()
        hostPluginsFlow.value = snap
        onNotify("已增量热载插件：$pluginId")
        return snap
    }

    suspend fun listHostPluginSnapshots(pluginId: String): List<HostPluginSourceSnapshot> {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/snapshots", jsonOf("pluginId" to pluginId), timeoutMs = 12_000)
        return res.arr("snapshots").objects().map(HostPluginSourceSnapshot::fromJson)
    }

    suspend fun rollbackHostPluginSnapshot(pluginId: String, snapshotId: String? = null): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val params = jsonOf("pluginId" to pluginId)
        if (!snapshotId.isNullOrBlank()) params.put("snapshotId", snapshotId)
        val res = runtime.hubRequest("plugins/rollbackSnapshot", params, timeoutMs = 15_000)
        val snapObj = res.optJSONObject("snapshot")
        val snap = if (snapObj != null) HostPluginSnapshot.fromJson(snapObj) else refreshHostPlugins()
        hostPluginsFlow.value = snap
        val restored = res.optString("restoredVersion")
        onNotify(if (restored.isNotBlank()) "已回滚插件源码至 v$restored" else "已回滚插件源码快照")
        return snap
    }

    suspend fun restoreBuiltinHostPlugin(pluginId: String): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/restoreBuiltin", jsonOf("pluginId" to pluginId), timeoutMs = 15_000)
        val snapObj = res.optJSONObject("snapshot")
        val snap = if (snapObj != null) HostPluginSnapshot.fromJson(snapObj) else refreshHostPlugins()
        hostPluginsFlow.value = snap
        onNotify("已恢复原版内置插件")
        return snap
    }

    suspend fun createLocalHostPlugin(
        id: String,
        name: String,
        description: String,
        executionTier: String = "worker-isolated",
        template: String = "basic",
    ): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/createLocal",
            jsonOf(
                "id" to id.trim(),
                "name" to name.trim(),
                "description" to description.trim(),
                "executionTier" to executionTier,
                "template" to template,
            ),
            timeoutMs = 15_000,
        )
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify("已创建并激活本地插件：$name")
        return snap
    }

    suspend fun removeLocalHostPlugin(pluginId: String): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("plugins/removeLocal", jsonOf("pluginId" to pluginId), timeoutMs = 15_000)
        val snap = HostPluginSnapshot.fromJson(res)
        hostPluginsFlow.value = snap
        onNotify("已卸载本地插件")
        return snap
    }

    suspend fun runHostPluginCommand(
        pluginId: String,
        command: String,
        args: String = "",
        cwd: String? = null,
        sessionId: String? = null,
    ): HostPluginCommandResult {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/runCommand",
            jsonOf("pluginId" to pluginId, "command" to command, "args" to args, "cwd" to cwd, "sessionId" to sessionId),
            timeoutMs = 20_000,
        )
        return HostPluginCommandResult(
            kind = res.optString("kind", "notice"),
            prompt = res.optString("prompt"),
            message = res.optString("message"),
            title = res.optString("title"),
            artifactKind = res.optString("artifactKind", "html"),
            content = res.optString("content"),
        )
    }

    suspend fun renderHostPluginSurface(
        pluginId: String,
        surfaceId: String,
        cwd: String? = null,
        params: JSONObject = JSONObject(),
    ): HostPluginSduiPayload {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/renderSurface",
            jsonOf(
                "pluginId" to pluginId,
                "surfaceId" to surfaceId,
                "cwd" to (cwd ?: settings.state.value.defaultCwd),
                "params" to params,
            ),
            timeoutMs = 15_000,
        )
        return HostPluginSduiPayload.fromJson(res)
    }

    suspend fun renderHostPluginSlot(
        pluginId: String,
        slotId: String,
        cwd: String? = null,
        context: JSONObject = JSONObject(),
    ): HostPluginSduiPayload {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/renderSlot",
            jsonOf(
                "pluginId" to pluginId,
                "slotId" to slotId,
                "cwd" to (cwd ?: settings.state.value.defaultCwd),
                "context" to context,
            ),
            timeoutMs = 12_000,
        )
        return HostPluginSduiPayload.fromJson(res)
    }

    suspend fun resetStudioOverride(fileName: String) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("plugins/studioOverride", jsonOf("action" to "reset", "fileName" to fileName), timeoutMs = 10_000)
        refreshHostPlugins()
        onNotify("已恢复原版工作台资源：$fileName")
    }

    suspend fun renderHostPluginPanel(pluginId: String, panelId: String, cwd: String? = null): ChatArtifact {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/renderPanel",
            jsonOf("pluginId" to pluginId, "panelId" to panelId, "cwd" to (cwd ?: settings.state.value.defaultCwd)),
            timeoutMs = 20_000,
        )
        val kind = ArtifactKind.fromMimeOrLang(res.optString("kind", "html"), res.optString("kind", "html"))
        return ChatArtifact(
            id = "hostpanel:$pluginId:$panelId",
            identifier = "panel-$pluginId-$panelId",
            title = res.optString("title", panelId),
            kind = kind,
            language = kind.extension,
            content = res.optString("content"),
        )
    }

    fun resolvePluginCommandPrompt(localAction: String): String? {
        if (!localAction.startsWith("plugin:")) return null
        val rest = localAction.removePrefix("plugin:")
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val pluginId = rest.substring(0, sep)
        val cmdName = rest.substring(sep + 1)
        return registryFlow.value.plugins.firstOrNull { it.id == pluginId && it.enabled }
            ?.commands?.firstOrNull { it.name == cmdName }?.promptTemplate
    }

    // ── 全局/项目级指令集（CLAUDE.md / AGENTS.md / GEMINI.md） ──────────────

    fun upsertInstruction(item: ManagedInstruction) {
        commit { reg ->
            val exists = reg.instructions.any { it.id == item.id }
            val nextItem = if (exists || item.priority != 0) item else item.copy(priority = reg.instructions.size)
            val updated = if (exists) reg.instructions.map { if (it.id == item.id) nextItem else it } else reg.instructions + nextItem
            reg.copy(instructions = updated.sortedBy { it.priority })
        }
        onNotify("已保存指令：${item.title}")
    }

    fun reorderInstruction(id: String, moveUp: Boolean) {
        commit { reg ->
            val list = reg.instructions.sortedBy { it.priority }.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@commit reg
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in list.indices) return@commit reg
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            reg.copy(instructions = list.mapIndexed { i, v -> v.copy(priority = i) })
        }
    }

    fun duplicateInstruction(id: String) {
        val src = registryFlow.value.instructions.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = "inst-${System.currentTimeMillis().toString(36)}",
            title = "${src.title} 副本",
            priority = registryFlow.value.instructions.size,
        )
        upsertInstruction(copy)
    }

    fun toggleInstruction(id: String, enabled: Boolean) {
        commit { reg -> reg.copy(instructions = reg.instructions.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    fun deleteInstruction(id: String) {
        commit { reg -> reg.copy(instructions = reg.instructions.filterNot { it.id == id }) }
        onNotify("已删除指令")
    }

    // ── 子代理（Subagents） ──────────────────────────────────────────────────

    fun upsertSubagent(item: ManagedSubagent) {
        commit { reg -> reg.copy(subagents = reg.subagents.filterNot { it.id == item.id } + item) }
        onNotify("已保存子代理：${item.name}")
    }

    fun duplicateSubagent(id: String) {
        val src = registryFlow.value.subagents.firstOrNull { it.id == id } ?: return
        val suffix = System.currentTimeMillis().toString(36).takeLast(4)
        upsertSubagent(src.copy(id = "${src.id}-$suffix", name = "${src.name}-copy"))
    }

    /** 从带 YAML Frontmatter 的 Markdown 文本导入子代理定义。 */
    fun importSubagentFromMarkdown(rawMd: String) {
        val text = rawMd.replace("\r\n", "\n").trim()
        require(text.isNotBlank()) { "Markdown 内容为空" }
        val fm = Regex("^---\\n([\\s\\S]*?)\\n---\\n?([\\s\\S]*)$").find(text)
        var name = "custom-agent"
        var desc = ""
        var model = ""
        var tools = emptyList<String>()
        var disallowed = emptyList<String>()
        var permMode = ""
        var maxTurns = 0
        var prompt = text
        if (fm != null) {
            prompt = fm.groupValues[2].trim()
            fm.groupValues[1].lineSequence().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    val k = line.substring(0, i).trim().lowercase()
                    val v = line.substring(i + 1).trim().trim('"', '\'')
                    when (k) {
                        "name" -> if (v.isNotBlank()) name = v
                        "description" -> desc = v
                        "model" -> model = v
                        "tools" -> tools = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        "disallowedtools", "disallowed_tools" -> disallowed = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        "permissionmode", "permission_mode" -> permMode = v
                        "maxturns", "max_turns" -> maxTurns = v.toIntOrNull() ?: 0
                    }
                }
            }
        }
        val slug = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "sub-${System.currentTimeMillis().toString(36)}" }
        upsertSubagent(
            ManagedSubagent(
                id = slug,
                name = name,
                description = desc,
                model = model,
                prompt = prompt.ifBlank { text },
                tools = tools,
                disallowedTools = disallowed,
                permissionMode = permMode,
                maxTurns = maxTurns,
            ),
        )
    }

    fun toggleSubagent(id: String, enabled: Boolean) {
        commit { reg -> reg.copy(subagents = reg.subagents.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    fun deleteSubagent(id: String) {
        commit { reg -> reg.copy(subagents = reg.subagents.filterNot { it.id == id }) }
        onNotify("已删除子代理")
    }

    // ── 生命周期钩子（Hooks） ────────────────────────────────────────────────

    fun upsertHook(item: ManagedHook) {
        commit { reg -> reg.copy(hooks = reg.hooks.filterNot { it.id == item.id } + item) }
        onNotify("已保存钩子：${item.name}")
    }

    fun duplicateHook(id: String) {
        val src = registryFlow.value.hooks.firstOrNull { it.id == id } ?: return
        upsertHook(src.copy(id = "hook-${System.currentTimeMillis().toString(36)}", name = "${src.name} 副本", enabled = false))
    }

    fun toggleHook(id: String, enabled: Boolean) {
        commit { reg -> reg.copy(hooks = reg.hooks.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    fun deleteHook(id: String) {
        hookTestsFlow.value = hookTestsFlow.value - id
        commit { reg -> reg.copy(hooks = reg.hooks.filterNot { it.id == id }) }
        onNotify("已删除钩子")
    }

    suspend fun testHook(hook: ManagedHook, cwd: String? = null): HookTestResult {
        if (!runtime.hubAvailable) {
            val res = HookTestResult(ok = false, stderr = "请先启动本地运行环境")
            hookTestsFlow.value = hookTestsFlow.value + (hook.id to res)
            return res
        }
        val effectiveCwd = if (hook.scope.startsWith("project:")) hook.scope.removePrefix("project:") else (cwd ?: settings.state.value.defaultCwd)
        return try {
            val raw = runtime.hubRequest(
                "extensions/testHook",
                jsonOf(
                    "event" to hook.event,
                    "matcher" to hook.matcher,
                    "command" to hook.command,
                    "cwd" to effectiveCwd,
                    "timeoutSec" to hook.timeoutSec.coerceAtMost(15),
                ),
                timeoutMs = 20_000,
            )
            val res = HookTestResult(
                ok = raw.optBoolean("ok"),
                exitCode = raw.optInt("exitCode", -1),
                blocked = raw.optBoolean("blocked"),
                latencyMs = raw.optInt("latencyMs"),
                stdout = raw.optString("stdout"),
                stderr = raw.optString("stderr"),
            )
            hookTestsFlow.value = hookTestsFlow.value + (hook.id to res)
            res
        } catch (e: Exception) {
            val res = HookTestResult(ok = false, stderr = e.message ?: "试运行失败")
            hookTestsFlow.value = hookTestsFlow.value + (hook.id to res)
            res
        }
    }
}
