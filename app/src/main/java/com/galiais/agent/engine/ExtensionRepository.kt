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

    private val communityRegistryFlow = MutableStateFlow(CommunityRegistrySnapshot())
    val communityRegistry: StateFlow<CommunityRegistrySnapshot> = communityRegistryFlow.asStateFlow()

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
            onReloadCodexMcp()
            res
        } finally {
            syncingFlow.value = false
        }
    }

    // ── MCP 管理 ─────────────────────────────────────────────────────────────

    fun upsertMcp(server: ManagedMcpServer, secretValues: Map<String, String> = emptyMap()) {
        secretValues.forEach { (k, v) -> settings.setExtensionSecret(server.id, k, v) }
        commit { reg ->
            val list = reg.mcpServers.filterNot { it.id == server.id } + server
            reg.copy(mcpServers = list)
        }
        onNotify("已保存 MCP：${server.name}")
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
            val res = McpProbeResult(
                ok = raw.optBoolean("ok"),
                latencyMs = raw.optInt("latencyMs"),
                tools = tools,
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

    suspend fun saveLocalSkill(id: String?, name: String, description: String, body: String, targets: List<String> = listOf("all")) = skillMutex.withLock {
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
            scope = existing?.scope ?: "global",
            targets = targets.ifEmpty { existing?.targets ?: listOf("all") },
            pluginId = existing?.pluginId,
        )
        commit { reg -> reg.copy(skills = reg.skills.filterNot { it.id == savedId } + skill) }
        onNotify("已保存技能：${skill.name}")
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

    // ── 统一共享资源仓库（上传 AI 安全审查 + 验真下载安装） ──────────────────

    suspend fun refreshCommunityRegistry(kind: String = "all", query: String = ""): CommunityRegistrySnapshot {
        if (!runtime.hubAvailable) return communityRegistryFlow.value
        val res = runtime.hubRequest("registry/list", jsonOf("kind" to kind, "query" to query), timeoutMs = 20_000)
        val snap = CommunityRegistrySnapshot.fromJson(res)
        communityRegistryFlow.value = snap
        return snap
    }

    suspend fun saveCommunityRegistryConfig(patch: JSONObject): CommunityRegistrySnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("registry/saveConfig", patch, timeoutMs = 12_000)
        val snap = refreshCommunityRegistry()
        onNotify("已保存共享仓库与 AI 审查配置")
        return snap
    }

    suspend fun previewCommunityAudit(bundle: JSONObject): CommunitySubmitResult {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("registry/previewAudit", jsonOf("bundle" to bundle), timeoutMs = 45_000)
        return CommunitySubmitResult(
            status = res.optString("status", "rejected"),
            audit = ResourceAuditReport.fromJson(res.optJSONObject("audit")),
        )
    }

    suspend fun testCommunityReviewer(): JSONObject {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        return runtime.hubRequest("registry/testReviewer", JSONObject(), timeoutMs = 40_000)
    }

    suspend fun submitToCommunityRegistry(bundle: JSONObject): CommunitySubmitResult {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("registry/submit", jsonOf("bundle" to bundle), timeoutMs = 60_000)
        val result = CommunitySubmitResult(
            status = res.optString("status", "rejected"),
            audit = ResourceAuditReport.fromJson(res.optJSONObject("audit")),
            bundlePath = res.optString("bundlePath"),
            prHint = res.optString("prHint"),
            prUrl = res.optString("prUrl"),
            prNumber = res.optInt("prNumber", 0),
        )
        runCatching { refreshCommunityRegistry() }
        return result
    }

    suspend fun removeCommunityResource(id: String, kind: String) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("registry/remove", jsonOf("id" to id, "kind" to kind), timeoutMs = 15_000)
        runCatching { refreshCommunityRegistry() }
        onNotify("已从共享仓库下架")
    }

    suspend fun downloadAndInstallCommunityResource(item: CommunityResourceItem) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("registry/download", jsonOf("id" to item.id, "kind" to item.kind), timeoutMs = 30_000)
        val bundle = res.optJSONObject("bundle") ?: error("下载的资源包为空")
        val payload = bundle.optJSONObject("payload") ?: JSONObject()
        val kind = bundle.optString("kind", item.kind)
        val resId = bundle.optString("id", item.id)
        val resName = bundle.optString("name", item.name)
        when (kind) {
            "instruction" -> upsertInstruction(
                ManagedInstruction(
                    id = resId,
                    title = payload.optString("title", resName),
                    content = payload.optString("content"),
                    enabled = true,
                    targets = payload.arr("targets").strings().ifEmpty { listOf("all") },
                ),
            )
            "mcp" -> upsertMcp(
                ManagedMcpServer(
                    id = resId,
                    name = payload.optString("name", resName),
                    transport = payload.optString("transport", "stdio"),
                    command = payload.str("command"),
                    args = payload.arr("args").strings(),
                    url = payload.str("url"),
                    env = payload.optJSONObject("env")?.let { e -> buildMap { e.keys().forEach { k -> put(k, e.optString(k)) } } } ?: emptyMap(),
                    secretEnvKeys = payload.arr("secretEnvKeys").strings(),
                    headers = payload.optJSONObject("headers")?.let { h -> buildMap { h.keys().forEach { k -> put(k, h.optString(k)) } } } ?: emptyMap(),
                    secretHeaderKeys = payload.arr("secretHeaderKeys").strings(),
                    enabled = true,
                    targets = payload.arr("targets").strings().ifEmpty { listOf("all") },
                ),
            )
            "skill" -> saveLocalSkill(
                id = resId,
                name = payload.optString("name", resName),
                description = payload.optString("description", item.description),
                body = payload.optString("body"),
                targets = payload.arr("targets").strings().ifEmpty { listOf("all") },
            )
            "subagent" -> upsertSubagent(
                ManagedSubagent(
                    id = resId,
                    name = payload.optString("name", resName),
                    description = payload.optString("description", item.description),
                    model = payload.optString("model"),
                    prompt = payload.optString("prompt"),
                    enabled = true,
                    targets = payload.arr("targets").strings().ifEmpty { listOf("all") },
                ),
            )
            "hook" -> upsertHook(
                ManagedHook(
                    id = resId,
                    name = payload.optString("name", resName),
                    event = payload.optString("event", "PostToolUse"),
                    matcher = payload.optString("matcher", "*"),
                    command = payload.optString("command"),
                    enabled = true,
                    targets = payload.arr("targets").strings().ifEmpty { listOf("all") },
                ),
            )
            "host_plugin" -> {
                val snapJson = runtime.hubRequest("plugins/installBundle", payload, timeoutMs = 20_000)
                hostPluginsFlow.value = HostPluginSnapshot.fromJson(snapJson)
                onNotify("已验真并激活 Host 插件：$resName")
            }
            "plugin_pack" -> {
                val rawSkills = payload.arr("skills").objects().map { ManagedSkill.fromJson(it).copy(source = "plugin", pluginId = resId) }
                val rawMcps = payload.arr("mcpServers").objects().map { ManagedMcpServer.fromJson(it).copy(pluginId = resId) }
                val rawCmds = payload.arr("commands").objects().map(PluginCommand::fromJson)
                val rawSecrets = payload.arr("requiredSecrets").objects().map(PluginSecretSpec::fromJson)
                val installedSkills = if (rawSkills.isNotEmpty()) {
                    val skillsJson = JSONArray().apply { rawSkills.forEach { put(it.toJson()) } }
                    val installRes = runtime.hubRequest("extensions/installBuiltinPlugin", jsonOf("pluginId" to resId, "skills" to skillsJson), timeoutMs = 20_000)
                    installRes.arr("skills").objects().map { obj ->
                        val s = ManagedSkill.fromJson(obj)
                        val orig = rawSkills.firstOrNull { it.name == s.name }
                        s.copy(body = orig?.body ?: s.body, source = "plugin", pluginId = resId)
                    }
                } else emptyList()
                val plugin = ManagedPlugin(
                    id = resId,
                    name = resName,
                    version = item.version,
                    description = item.description,
                    source = "community",
                    enabled = true,
                    requiredSecrets = rawSecrets,
                    commands = rawCmds,
                )
                commit { reg ->
                    reg.copy(
                        plugins = reg.plugins.filterNot { it.id == resId } + plugin,
                        mcpServers = reg.mcpServers.filterNot { it.pluginId == resId } + rawMcps,
                        skills = reg.skills.filterNot { it.pluginId == resId } + installedSkills,
                    )
                }
                onNotify("已验真并安装插件包：$resName")
            }
            else -> error("未知资源类型：$kind")
        }
        runCatching { refreshCommunityRegistry() }
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

    suspend fun createLocalHostPlugin(id: String, name: String, description: String): HostPluginSnapshot {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest(
            "plugins/createLocal",
            jsonOf("id" to id.trim(), "name" to name.trim(), "description" to description.trim()),
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

    // ── 全局指令集（CLAUDE.md / AGENTS.md / GEMINI.md） ─────────────────────

    fun upsertInstruction(item: ManagedInstruction) {
        commit { reg -> reg.copy(instructions = reg.instructions.filterNot { it.id == item.id } + item) }
        onNotify("已保存指令：${item.title}")
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

    fun toggleHook(id: String, enabled: Boolean) {
        commit { reg -> reg.copy(hooks = reg.hooks.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    fun deleteHook(id: String) {
        commit { reg -> reg.copy(hooks = reg.hooks.filterNot { it.id == id }) }
        onNotify("已删除钩子")
    }
}
