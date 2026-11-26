package com.galiais.agent.engine

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Proxy
import androidx.core.content.ContextCompat
import com.galiais.agent.newId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val MULTI_NL_RE = Regex("\n{3,}")
private val ACP_META_RUN_PREFIX_RE = Regex("^(?:run|call|tool)_+")
private val ACP_META_TOOL_NAMES = setOf("update_steps", "update_focus", "task_boundary", "update_plan", "todowrite", "todo_write", "set_plan")

internal fun normalizeAcpMetaToolName(title: String): String =
    title.trim().lowercase().replace("-", "_").replace(Regex("\\s+"), "_").replace(ACP_META_RUN_PREFIX_RE, "")

internal fun isAcpMetaToolTitle(title: String): Boolean =
    normalizeAcpMetaToolName(title) in ACP_META_TOOL_NAMES

/**
 * 手机版 Agent Harness 核心控制器：对接虚拟机内 `agent-hub.mjs`，统一调度所有外部
 * ACP（Antigravity CLI / Gemini CLI / OpenCode / Goose 等）与通用 CLI 桥接（NDJSON / Exec-Stream）Agent。
 */
class AgentHubController(val repo: ConversationRepository) {
    private val runtime get() = repo.runtime
    private val settings get() = repo.settings

    private val statusesFlow = MutableStateFlow<Map<String, AgentRuntimeStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, AgentRuntimeStatus>> = statusesFlow.asStateFlow()

    private val installLogsFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val installLogs: StateFlow<Map<String, List<String>>> = installLogsFlow.asStateFlow()

    private val authStatesFlow = MutableStateFlow<Map<String, AgentAuthState>>(emptyMap())
    val authStates: StateFlow<Map<String, AgentAuthState>> = authStatesFlow.asStateFlow()

    val terminals = TerminalRepository(runtime, repo.scope) { ssh ->
        repo.envSsh.syncBeforeLaunch()
        if (ssh) repo.envSsh.syncSshNow()
    }

    private val cloneLogsFlow = MutableStateFlow<List<String>>(emptyList())
    val cloneLogs: StateFlow<List<String>> = cloneLogsFlow.asStateFlow()

    private val listeningPortsFlow = MutableStateFlow<List<Int>>(emptyList())
    val listeningPorts: StateFlow<List<Int>> = listeningPortsFlow.asStateFlow()

    val portForwarder = PortForwardManager(this)

    private val dynamicCommands = mutableMapOf<ConversationKey, List<SlashCommand>>()
    private val availableModels = mutableMapOf<ConversationKey, List<ModelOption>>()
    private val globalModels = mutableMapOf<String, List<ModelOption>>()
    private val agyAgentsCache = mutableListOf<Pair<String, String>>()
    private val availableModes = mutableMapOf<ConversationKey, List<Pair<String, String>>>()
    private val configOptions = mutableMapOf<ConversationKey, List<AcpConfigOption>>()
    private val activeStreamMsgId = mutableMapOf<ConversationKey, String>()
    private val activeThoughtMsgId = mutableMapOf<ConversationKey, String>()
    private val interceptedMetaToolIds = mutableMapOf<ConversationKey, MutableSet<String>>()
    private val commandStartedAt = mutableMapOf<String, Long>()
    private val adapters = mutableMapOf<String, UniversalAgentAdapter>()
    private val networkMutex = Mutex()
    private val systemProxyFlow = MutableStateFlow(detectSystemProxy(runtime.app))
    val systemProxy: StateFlow<String?> = systemProxyFlow.asStateFlow()
    private var syncedNetwork: String? = null
    private var projectedNetwork: String? = null
    private data class InstallAttempt(
        val operationId: String,
        var cancelled: Boolean = false,
        var submitted: Boolean = false,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val installAttempts = mutableMapOf<String, InstallAttempt>()

    init {
        // 网络监听与应用仓库同寿命，网络切换后更新系统代理及 MCP 配置。
        runtime.app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshSystemNetwork()
            override fun onLost(network: Network) = refreshSystemNetwork()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refreshSystemNetwork()
        })
        ContextCompat.registerReceiver(runtime.app, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refreshSystemNetwork()
        }, IntentFilter(Proxy.PROXY_CHANGE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        repo.scope.launch {
            runtime.hubEvents.collect { packet ->
                try { handleHubPacket(packet) }
                catch (e: Exception) { Log.w("GALIAIS", "处理 Agent Hub 事件失败", e) }
            }
        }
    }

    private fun refreshSystemNetwork() {
        repo.scope.launch {
            systemProxyFlow.value = detectSystemProxy(runtime.app)
            if (!runtime.snapshot.value.ready || settings.state.value.downloadNetwork.proxyMode != "system") return@launch
            try { syncNetworkConfig(refreshExtensions = true) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { repo.notify("网络配置更新失败：${e.message ?: "请在下载设置中重试"}") }
        }
    }

    fun adapterFor(kind: EngineKind): UniversalAgentAdapter =
        adapters.getOrPut(kind.id) { UniversalAgentAdapter(this, kind) }

    suspend fun listAllSessions(): List<ConversationSummary> {
        if (!runtime.hubAvailable) return emptyList()
        val res = runtime.hubRequest("sessions/list", JSONObject())
        val activeDriverNames = repo.extensions.hostPlugins.value.drivers
            .filter { it.executable && it.id.isNotBlank() }
            .associate { it.id to it.name.ifBlank { it.id } }
        return res.arr("sessions").objects().mapNotNull { s ->
            val agentId = s.str("agentId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = s.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val known = AgentCatalog.find(agentId)
            val label = known?.name ?: s.str("driverName")?.takeIf { it.isNotBlank() } ?: activeDriverNames[agentId] ?: agentId
            ConversationSummary(
                key = ConversationKey(EngineKind(agentId, label), id),
                title = s.str("title") ?: "新会话",
                preview = s.optString("preview"),
                cwd = s.str("cwd") ?: DEFAULT_WORKSPACE,
                updatedAt = normalizeTime(s.optLong("updatedAt", System.currentTimeMillis())),
                running = s.optBoolean("running"),
            )
        }
    }

    fun isInstalled(agentId: String): Boolean {
        val m = AgentCatalog.find(agentId)
        if (m != null) {
            if (m.protocol.isNative) return true
            return statusesFlow.value[agentId]?.let { it.installed && it.error == null && !it.installing } == true
        }
        val host = repo.extensions.hostPlugins.value
        return !host.safeMode && host.drivers.any { it.id == agentId && it.executable }
    }

    private fun buildNetworkPayload(override: DownloadNetworkConfig? = null): JSONObject {
        val cfg = (override ?: settings.state.value.downloadNetwork).validated()
        return cfg.toHubJson(detectSystemProxy(runtime.app))
    }

    suspend fun configureAndProbe() {
        if (!runtime.hubAvailable) return
        val gw = runtime.gatewayEndpoint
        val manifestsArr = JSONArray().apply {
            AgentCatalog.external().forEach { put(it.toJson()) }
        }
        val params = JSONObject()
            .put("manifests", manifestsArr)
            .put("network", buildNetworkPayload())
        if (gw != null) params.put("gateway", jsonOf("port" to gw.first, "token" to gw.second))
        networkMutex.withLock { runtime.hubRequest("hub/configure", params) }
        refreshStatuses()
    }

    suspend fun syncNetworkConfig(override: DownloadNetworkConfig? = null, refreshExtensions: Boolean = false) = networkMutex.withLock {
        check(runtime.snapshot.value.ready && runtime.hubAvailable) { "运行环境未就绪" }
        val payload = buildNetworkPayload(override)
        val serialized = payload.toString()
        if (syncedNetwork != serialized) {
            runtime.hubRequest("network/configure", jsonOf("network" to payload), 15_000)
            syncedNetwork = serialized
        }
        if (refreshExtensions && (projectedNetwork != serialized || override != null)) {
            repo.extensions.syncNow()
            projectedNetwork = serialized
        }
    }

    suspend fun saveDownloadNetwork(config: DownloadNetworkConfig): Unit = repo.scope.async {
        settings.setDownloadNetwork(config)
        try {
            if (runtime.snapshot.value.ready) {
                syncNetworkConfig(config, refreshExtensions = true)
                repo.notify("已保存并应用下载网络配置")
            } else {
                repo.notify("已保存，运行环境启动后生效")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            val message = "配置已保存，应用失败：${e.message ?: "请重试"}"
            repo.notify(message)
            throw IllegalStateException(message, e)
        }
    }.await()

    private fun cancelledInstall(manifest: AgentManifest, progress: AgentInstallProgress): AgentRuntimeStatus {
        val current = statusesFlow.value[manifest.id] ?: AgentRuntimeStatus(manifest.id)
        val result = current.copy(installing = false, installError = null, installation = progress.copy(phase = AgentInstallPhase.CANCELLED))
        if (current.installation?.operationId == progress.operationId) statusesFlow.value += manifest.id to result
        repo.notify("已取消安装 ${manifest.name}")
        return result
    }

    suspend fun testDownloadNetwork(override: DownloadNetworkConfig? = null): JSONObject {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        return runtime.hubRequest("network/test", jsonOf("network" to buildNetworkPayload(override)), 25_000)
    }

    suspend fun cancelInstall(agentId: String) {
        val current = statusesFlow.value[agentId] ?: return
        val progress = current.installation ?: return
        if (!current.installing || progress.phase == AgentInstallPhase.CANCELLING) return
        val attempt = installAttempts[agentId]?.takeIf { it.operationId == progress.operationId }
        if (attempt != null) attempt.cancelled = true
        if (attempt?.submitted != false) {
            check(runtime.snapshot.value.ready && runtime.hubAvailable) { "连接已断开，尚未确认安装停止" }
            val response = runtime.hubRequest("agents/cancelInstall", jsonOf("agentId" to agentId, "operationId" to progress.operationId), 15_000)
            if (!response.optBoolean("cancelled")) {
                refreshStatuses(agentId)
                repo.notify("安装任务已结束，请查看最终结果")
                return
            }
        }
        statusesFlow.value[agentId]?.takeIf { it.installation?.operationId == progress.operationId && it.installing }?.let {
            statusesFlow.value += agentId to it.copy(installation = progress.copy(phase = AgentInstallPhase.CANCELLING))
        }
        val stopped = withTimeoutOrNull(30_000) {
            if (attempt != null) attempt.finished.await()
            else statuses.first { it[agentId]?.installation?.let { p -> p.operationId == progress.operationId && !p.phase.active } == true }
            true
        }
        check(stopped == true) { "已请求取消，尚未确认安装停止，请查看安装状态" }
    }

    suspend fun refreshStatuses(agentId: String? = null) {
        if (!runtime.hubAvailable) return
        val res = runtime.hubRequest("agents/status", agentId?.let { jsonOf("agentId" to it) } ?: JSONObject(), 30_000)
        val arr = res.arr("statuses").objects()
        val next = statusesFlow.value.toMutableMap()
        for (obj in arr) {
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val result = parseStatus(obj)
            val current = next[id]
            if (current?.installation?.phase?.active == true && current.installation.operationId != result.installation?.operationId) continue
            next[id] = result
        }
        statusesFlow.value = next
    }

    suspend fun install(manifest: AgentManifest): AgentRuntimeStatus {
        check(runtime.hubAvailable) { "Agent 服务未就绪，请先启动运行环境" }
        check(statusesFlow.value.values.none { it.installing }) { "已有安装或卸载任务，请完成后再试" }
        val progress = AgentInstallProgress(newId(), AgentInstallPhase.PREPARING, 1)
        val attempt = InstallAttempt(progress.operationId)
        installAttempts[manifest.id] = attempt
        installLogsFlow.value = installLogsFlow.value + (manifest.id to listOf("正在准备安装 ${manifest.name}…"))
        statusesFlow.value = statusesFlow.value + (manifest.id to (statusesFlow.value[manifest.id]?.copy(installing = true, installError = null, installation = progress)
            ?: AgentRuntimeStatus(manifest.id, installing = true, installation = progress)))
        return try {
            syncNetworkConfig()
            if (attempt.cancelled) return cancelledInstall(manifest, progress)
            attempt.submitted = true
            val res = runtime.hubRequest("agents/install", jsonOf("agentId" to manifest.id, "manifest" to manifest.toJson(), "operationId" to progress.operationId), 600_000)
            if (res.optBoolean("cancelled")) return cancelledInstall(manifest, AgentInstallProgress.fromJson(res.getJSONObject("installation")))
            val st = parseStatus(res)
            if (statusesFlow.value[manifest.id]?.installation?.operationId == progress.operationId)
                statusesFlow.value = statusesFlow.value + (manifest.id to st)
            repo.notify("${manifest.name} 安装完成（${st.version ?: "已就绪"}）")
            st
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            val prev = statusesFlow.value[manifest.id] ?: AgentRuntimeStatus(manifest.id)
            if (prev.installation?.operationId == progress.operationId) {
                statusesFlow.value = statusesFlow.value + (manifest.id to prev.copy(installing = false, installError = e.message,
                    installation = prev.installation.copy(phase = AgentInstallPhase.FAILED)))
            }
            throw e
        } finally {
            installAttempts.remove(manifest.id, attempt)
            attempt.finished.complete(Unit)
        }
    }

    suspend fun uninstall(manifest: AgentManifest): AgentRuntimeStatus {
        check(runtime.hubAvailable) { "Agent 服务未就绪" }
        val res = runtime.hubRequest("agents/uninstall", jsonOf("agentId" to manifest.id, "manifest" to manifest.toJson()), 120_000)
        val st = parseStatus(res)
        statusesFlow.value = statusesFlow.value + (manifest.id to st)
        repo.notify("已卸载 ${manifest.name}")
        return st
    }

    suspend fun stopAgent(agentId: String) {
        if (!runtime.hubAvailable) return
        runtime.hubRequest("agents/stop", jsonOf("agentId" to agentId))
        statusesFlow.value[agentId]?.let {
            statusesFlow.value = statusesFlow.value + (agentId to it.copy(running = false))
        }
    }

    suspend fun startAuth(manifest: AgentManifest, mode: String = "oauth") {
        check(runtime.hubAvailable) { "Agent 服务未就绪" }
        authStatesFlow.value = authStatesFlow.value + (manifest.id to AgentAuthState(manifest.id, running = true, lines = listOf("正在启动交互登录…")))
        runtime.hubRequest("agents/authStart", jsonOf("agentId" to manifest.id, "manifest" to manifest.toJson(), "mode" to mode))
    }

    suspend fun startCliAuth(agentId: String, mode: String = "oauth") {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val manifest = AgentCatalog.find(agentId)
        authStatesFlow.value = authStatesFlow.value + (agentId to AgentAuthState(agentId, running = true, lines = listOf("正在启动交互登录…")))
        val payload = jsonOf("agentId" to agentId, "mode" to mode).apply {
            manifest?.let { put("manifest", it.toJson()) }
        }
        runtime.hubRequest("agents/authStart", payload)
    }

    suspend fun sendAuthInput(agentId: String, text: String) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("agents/authInput", jsonOf("agentId" to agentId, "text" to text))
        val prev = authStatesFlow.value[agentId] ?: return
        authStatesFlow.value = authStatesFlow.value + (agentId to prev.copy(
            needsInput = false,
            lines = (prev.lines + "▸ 已发送输入").takeLast(80),
        ))
    }

    suspend fun queryAuthStatus(agentId: String): CliAuthStatus {
        if (!runtime.hubAvailable) return CliAuthStatus(agentId)
        val res = runtime.hubRequest("agents/authStatus", jsonOf("agentId" to agentId), 15_000)
        return CliAuthStatus(
            agentId = agentId,
            loggedIn = res.optBoolean("loggedIn", false),
            authMethod = res.optString("authMethod", "none"),
            email = res.str("email"),
            subscriptionType = res.str("subscriptionType"),
            orgName = res.str("orgName"),
            raw = res.optString("raw", ""),
        )
    }

    suspend fun logoutAuth(agentId: String) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("agents/authLogout", jsonOf("agentId" to agentId), 20_000)
        authStatesFlow.value = authStatesFlow.value - agentId
    }

    suspend fun cancelAuth(agentId: String) {
        if (!runtime.hubAvailable) return
        runtime.hubRequest("agents/authCancel", jsonOf("agentId" to agentId))
        authStatesFlow.value[agentId]?.let {
            authStatesFlow.value = authStatesFlow.value + (agentId to it.copy(running = false, done = true, needsInput = false))
        }
    }

    suspend fun openPty(
        cwd: String,
        sshHostId: String? = null,
        sshAlias: String? = null,
        remoteCwd: String = "",
    ): String {
        check(runtime.hubAvailable) { "运行环境尚未就绪" }
        val payload = jsonOf(
            "cwd" to cwd,
            "sshHostId" to sshHostId,
            "sshAlias" to sshAlias,
            "remoteCwd" to remoteCwd.takeIf { it.isNotBlank() },
        )
        val res = runtime.hubRequest("pty/open", payload, timeoutMs = 25_000)
        return res.getString("ptyId")
    }

    suspend fun writePty(ptyId: String, data: String) {
        if (!runtime.hubAvailable) return
        runtime.hubRequest("pty/write", jsonOf("ptyId" to ptyId, "data" to data))
    }

    suspend fun closePty(ptyId: String) {
        if (!runtime.hubAvailable) return
        runCatching { runtime.hubRequest("pty/close", jsonOf("ptyId" to ptyId)) }
    }

    suspend fun getGitSshKey(): String {
        check(runtime.hubAvailable) { "运行环境未就绪，请先启动运行环境" }
        val res = runtime.hubRequest("git/sshKey", JSONObject(), 20_000)
        return res.optString("publicKey")
    }

    suspend fun cloneGitRepo(url: String, targetDir: String, branch: String?, token: String?): String {
        check(runtime.hubAvailable) { "运行环境未就绪，请先启动运行环境" }
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("git@")) {
            val parsed = java.net.URI(cleanUrl)
            require(parsed.scheme in setOf("https", "ssh") && parsed.query == null && parsed.fragment == null &&
                (parsed.userInfo == null || parsed.scheme == "ssh" && !parsed.userInfo.contains(':'))) {
                "仓库地址不能包含密码或查询参数，请在令牌栏填写凭据"
            }
        }
        cloneLogsFlow.value = listOf("$ git clone $url")
        val res = runtime.hubRequest(
            "git/clone",
            jsonOf(
                "url" to url.trim(),
                "targetDir" to targetDir.trim(),
                "branch" to branch?.trim()?.takeIf { it.isNotEmpty() },
                "token" to token?.trim()?.takeIf { it.isNotEmpty() },
            ),
            timeoutMs = 600_000,
        )
        return res.optString("path")
    }

    suspend fun refreshListeningPorts(): List<Int> {
        if (!runtime.hubAvailable) return emptyList()
        return try {
            val res = runtime.hubRequest("ports/list", JSONObject(), 10_000)
            val ports = buildList {
                val arr = res.optJSONArray("ports") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.optInt(i, 0)
                    if (p in 1024..65535) add(p)
                }
            }
            listeningPortsFlow.value = ports
            ports
        } catch (_: Exception) {
            listeningPortsFlow.value
        }
    }

    fun reset() {
        terminals.disconnected()
        syncedNetwork = null
        projectedNetwork = null
        portForwarder.closeAll()
        statusesFlow.value = emptyMap()
        authStatesFlow.value = emptyMap()
        listeningPortsFlow.value = emptyList()
        dynamicCommands.clear()
        availableModels.clear()
        globalModels.clear()
        agyAgentsCache.clear()
        availableModes.clear()
        configOptions.clear()
        activeStreamMsgId.clear()
        activeThoughtMsgId.clear()
        commandStartedAt.clear()
    }

    private fun parseStatus(obj: JSONObject) = AgentRuntimeStatus(
        id = obj.optString("id"),
        installed = obj.optBoolean("installed", false),
        version = obj.str("version"),
        binaryPath = obj.str("binaryPath"),
        running = obj.optBoolean("running", false),
        installing = obj.optBoolean("installing", false),
        activeSessions = obj.optInt("activeSessions", 0),
        error = obj.str("error"),
        installError = obj.str("installError"),
        installation = obj.obj("installation")?.let(AgentInstallProgress::fromJson),
    )

    internal fun providerPayload(agentId: String, providerId: String?, model: String?): JSONObject? {
        val pid = providerId ?: return null
        val p = settings.state.value.provider(pid) ?: error("${EngineKind.of(agentId).label} 会话使用的服务商已删除，请重新选择")
        return jsonOf("id" to p.id, "model" to (model ?: p.models.firstOrNull()))
    }

    internal fun commandsOf(key: ConversationKey): List<SlashCommand> = dynamicCommands[key].orEmpty()
    internal fun modelsOf(key: ConversationKey?): List<ModelOption> = key?.let { availableModels[it] }.orEmpty()
    internal fun globalModelsOf(agentId: String): List<ModelOption> = globalModels[agentId].orEmpty()
    fun agyAgents(): List<Pair<String, String>> = agyAgentsCache.toList()
    fun modesOf(key: ConversationKey?): List<Pair<String, String>> = key?.let { availableModes[it] }.orEmpty()
    fun configOptionsOf(key: ConversationKey?): List<AcpConfigOption> = key?.let { configOptions[it] }.orEmpty()
    internal fun clearSessionModelCache(key: ConversationKey) {
        availableModels.remove(key)
        configOptions.remove(key)
    }

    private val agyEffortSuffixRe = Regex("-(low|medium|high|xhigh|max)$", RegexOption.IGNORE_CASE)
    private val agyLabelSuffixRe = Regex("\\s*\\((?:low|medium|high|xhigh|extra|max)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val agyEffortOrder = listOf("low", "medium", "high", "xhigh", "max")

    internal fun splitAgyModelEffort(rawId: String?): Pair<String?, String?> {
        val clean = rawId?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val m = agyEffortSuffixRe.find(clean) ?: return clean to null
        return clean.removeRange(m.range) to m.groupValues[1].lowercase()
    }

    internal fun canonicalizeAgyModels(raw: List<ModelOption>): List<ModelOption> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        val names = linkedMapOf<String, String>()
        val descs = linkedMapOf<String, String>()
        for (opt in raw) {
            val (base, suffixEff) = splitAgyModelEffort(opt.id)
            if (base.isNullOrBlank()) continue
            val list = grouped.getOrPut(base) { mutableListOf() }
            opt.efforts.forEach { e -> if (e !in list) list += e }
            if (suffixEff != null && suffixEff !in list) list += suffixEff
            if (base !in names) {
                names[base] = opt.label.replace(agyLabelSuffixRe, "").trim().ifBlank { base }
            }
            if (base !in descs && opt.description.isNotBlank()) descs[base] = opt.description
        }
        return grouped.entries.map { (base, effs) ->
            val sortedEffs = effs.distinct().sortedBy { e -> agyEffortOrder.indexOf(e).let { if (it < 0) 99 else it } }
            ModelOption(
                id = base,
                label = names[base] ?: base,
                description = descs[base].orEmpty(),
                efforts = sortedEffs,
                defaultEffort = sortedEffs.firstOrNull { it == "high" } ?: sortedEffs.firstOrNull(),
            )
        }
    }

    suspend fun fetchAgyCatalog(forceRefresh: Boolean = false): List<ModelOption> {
        if (!runtime.hubAvailable) return globalModels["antigravity"].orEmpty()
        if (!forceRefresh && globalModels["antigravity"]?.isNotEmpty() == true) {
            return globalModels["antigravity"].orEmpty()
        }
        val res = runtime.hubRequest("agy/catalog", jsonOf("apiKey" to settings.antigravityApiKey()), 25_000)
        val grouped = res.arr("groupedModels").objects().mapNotNull { g ->
            val id = g.str("id") ?: g.str("modelId") ?: return@mapNotNull null
            val efforts = g.arr("efforts").strings()
            ModelOption(
                id = id,
                label = (g.str("name") ?: id).replace(agyLabelSuffixRe, "").trim().ifBlank { id },
                efforts = efforts,
                defaultEffort = efforts.firstOrNull { it == "high" } ?: efforts.firstOrNull(),
            )
        }
        val effective = if (grouped.isNotEmpty()) {
            canonicalizeAgyModels(grouped)
        } else {
            val rawModels = res.arr("models").objects().mapNotNull { m ->
                val id = m.str("modelId") ?: m.str("id") ?: return@mapNotNull null
                ModelOption(id = id, label = m.str("name") ?: id, efforts = m.arr("efforts").strings())
            }
            canonicalizeAgyModels(rawModels)
        }
        if (effective.isNotEmpty()) globalModels["antigravity"] = effective
        val agents = res.arr("agents").objects().mapNotNull { a ->
            val id = a.str("id") ?: return@mapNotNull null
            id to a.optString("description")
        }
        agyAgentsCache.clear()
        agyAgentsCache.addAll(agents)
        return effective
    }

    suspend fun installAgyPlugin(target: String): String {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("agy/plugins", jsonOf("action" to "install", "name" to target.trim()), 125_000)
        return res.optString("output", "安装完成")
    }

    suspend fun uninstallAgyPlugin(name: String): String {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("agy/plugins", jsonOf("action" to "uninstall", "name" to name.trim()), 30_000)
        return res.optString("output", "已卸载")
    }

    suspend fun importAgyPlugins(source: String? = null): String {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("agy/plugins", jsonOf("action" to "import", "source" to source), 35_000)
        return res.optString("output", "导入完成")
    }

    internal fun updateSessionMetadata(key: ConversationKey, session: JSONObject) {
        val rawModels = session.optJSONArray("availableModels")?.objects().orEmpty().mapNotNull { m ->
            val id = m.str("modelId") ?: m.str("id") ?: return@mapNotNull null
            ModelOption(
                id = id,
                label = m.str("name") ?: id,
                description = m.optString("description"),
                efforts = m.arr("efforts").strings(),
            )
        }
        val models = if (key.engine.id == "antigravity") canonicalizeAgyModels(rawModels) else rawModels
        if (models.isNotEmpty()) availableModels[key] = models

        val modes = session.optJSONArray("availableModes")?.objects().orEmpty().mapNotNull { m ->
            val id = m.str("id") ?: m.str("modeId") ?: return@mapNotNull null
            id to (m.str("name") ?: id)
        }
        if (modes.isNotEmpty()) availableModes[key] = modes

        val configs = parseConfigOptions(session.optJSONArray("configOptions"))
        if (configs.isNotEmpty()) configOptions[key] = configs
    }

    internal fun updateConfigOptions(key: ConversationKey, arr: JSONArray?) {
        val parsed = parseConfigOptions(arr)
        if (parsed.isNotEmpty()) configOptions[key] = parsed
    }

    private fun parseConfigOptions(arr: JSONArray?): List<AcpConfigOption> =
        arr?.objects().orEmpty().mapNotNull { opt ->
            val id = opt.str("id") ?: return@mapNotNull null
            val choices = opt.optJSONArray("options")?.objects().orEmpty().mapNotNull { c ->
                val v = c.str("value") ?: c.str("id") ?: return@mapNotNull null
                AcpConfigChoice(v, c.str("name") ?: v)
            }
            AcpConfigOption(
                id = id,
                name = opt.str("name") ?: id,
                category = opt.optString("category"),
                currentValue = opt.str("currentValue"),
                options = choices,
            )
        }

    suspend fun listAgyPlugins(): List<AgyPluginSummary> {
        if (!runtime.hubAvailable) return emptyList()
        val res = runtime.hubRequest("agy/plugins", jsonOf("action" to "list"), 20_000)
        return res.arr("plugins").objects().mapNotNull { p ->
            val name = p.str("name") ?: return@mapNotNull null
            AgyPluginSummary(name = name, enabled = p.optBoolean("enabled", true), description = p.optString("description"), version = p.optString("version"))
        }
    }

    suspend fun toggleAgyPlugin(name: String, enabled: Boolean) {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        runtime.hubRequest("agy/plugins", jsonOf("action" to if (enabled) "enable" else "disable", "name" to name), 20_000)
    }

    suspend fun listAgyArtifacts(): List<AgyArtifactEntry> {
        if (!runtime.hubAvailable) return emptyList()
        val res = runtime.hubRequest("agy/artifacts", JSONObject(), 15_000)
        return res.arr("artifacts").objects().mapNotNull { a ->
            val path = a.str("path") ?: return@mapNotNull null
            AgyArtifactEntry(
                id = a.optString("id", path),
                name = a.optString("name", path.substringAfterLast('/')),
                path = path,
                size = a.optLong("size", 0L),
                updatedAt = a.optLong("updatedAt", 0L),
                sessionDir = a.optString("sessionDir"),
            )
        }
    }

    suspend fun queryUsageSummary(
        period: String = "7d",
        agent: String? = null,
        providerId: String? = null,
        model: String? = null,
        day: String? = null,
        sessionId: String? = null,
        source: String? = null,
        failedOnly: Boolean = false,
        search: String? = null,
    ): JSONObject {
        if (!runtime.hubAvailable) return JSONObject()
        return runtime.hubRequest(
            "usage/summary",
            jsonOf(
                "period" to period,
                "agent" to agent?.takeIf { it.isNotBlank() },
                "providerId" to providerId?.takeIf { it.isNotBlank() },
                "model" to model?.takeIf { it.isNotBlank() },
                "day" to day?.takeIf { it.isNotBlank() },
                "sessionId" to sessionId?.takeIf { it.isNotBlank() },
                "source" to source?.takeIf { it.isNotBlank() },
                "failedOnly" to failedOnly,
                "search" to search?.trim()?.takeIf { it.isNotEmpty() },
                "limit" to 200,
            ),
            15_000,
        )
    }

    suspend fun exportUsageCsv(
        period: String = "all",
        agent: String? = null,
        providerId: String? = null,
        model: String? = null,
        day: String? = null,
        sessionId: String? = null,
        source: String? = null,
        failedOnly: Boolean = false,
        search: String? = null,
    ): JSONObject {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        return runtime.hubRequest(
            "usage/exportCsv",
            jsonOf(
                "period" to period,
                "agent" to agent?.takeIf { it.isNotBlank() },
                "providerId" to providerId?.takeIf { it.isNotBlank() },
                "model" to model?.takeIf { it.isNotBlank() },
                "day" to day?.takeIf { it.isNotBlank() },
                "sessionId" to sessionId?.takeIf { it.isNotBlank() },
                "source" to source?.takeIf { it.isNotBlank() },
                "failedOnly" to failedOnly,
                "search" to search?.trim()?.takeIf { it.isNotEmpty() },
            ),
            20_000,
        )
    }

    fun recordDirectUsage(
        agent: String,
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long = 0L,
        cacheWriteTokens: Long = 0L,
        reasoningTokens: Long = 0L,
        durationMs: Long = 0L,
        ttftMs: Long = 0L,
        genMs: Long = 0L,
        streamed: Boolean = false,
        exact: Boolean = true,
        contextUsed: Long = 0L,
        contextWindow: Long? = null,
        billingKind: String = "plan",
        costUsd: Double? = null,
        status: Int = 200,
        error: String = "",
        providerName: String = "官方账号直连",
        sessionId: String? = null,
    ) {
        if (!runtime.hubAvailable) return
        if (inputTokens <= 0L && outputTokens <= 0L && durationMs <= 0L && error.isBlank()) return
        repo.scope.launch {
            runCatching {
                val payload = jsonOf(
                    "agent" to agent,
                    "providerId" to "official_$agent",
                    "providerName" to providerName,
                    "model" to model,
                    "in" to inputTokens,
                    "out" to outputTokens,
                    "cacheRead" to cacheReadTokens,
                    "cacheWrite" to cacheWriteTokens,
                    "reasoning" to reasoningTokens,
                    "ms" to durationMs,
                    "ttftMs" to ttftMs,
                    "genMs" to genMs,
                    "streamed" to streamed,
                    "exact" to exact,
                    "contextUsed" to contextUsed,
                    "contextWindow" to (contextWindow ?: 0L),
                    "status" to status,
                    "err" to error,
                    "billingKind" to billingKind,
                    "source" to "direct",
                    "sessionId" to sessionId,
                )
                if (costUsd != null) payload.put("cost", costUsd)
                runtime.hubRequest("usage/record", payload, 8_000)
            }.onFailure { err ->
                android.util.Log.w("SolbraidUsage", "recordDirectUsage($agent) failed: ${err.message}")
            }
        }
    }

    suspend fun clearUsageLedger(
        agent: String? = null,
        providerId: String? = null,
        model: String? = null,
        day: String? = null,
        sessionId: String? = null,
        source: String? = null,
        period: String? = null,
        failedOnly: Boolean = false,
        search: String? = null,
    ): JSONObject {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        return runtime.hubRequest(
            "usage/clear",
            jsonOf(
                "agent" to agent?.takeIf { it.isNotBlank() },
                "providerId" to providerId?.takeIf { it.isNotBlank() },
                "model" to model?.takeIf { it.isNotBlank() },
                "day" to day?.takeIf { it.isNotBlank() },
                "sessionId" to sessionId?.takeIf { it.isNotBlank() },
                "source" to source?.takeIf { it.isNotBlank() },
                "period" to period?.takeIf { it.isNotBlank() && it != "all" },
                "failedOnly" to failedOnly,
                "search" to search?.takeIf { it.isNotBlank() },
            ),
            10_000,
        )
    }

    suspend fun latexStatus(): LatexEngineStatus {
        if (!runtime.hubAvailable) return LatexEngineStatus()
        val res = runtime.hubRequest("latex/status", JSONObject(), 10_000)
        return LatexEngineStatus(
            offlineEngine = res.optString("offlineEngine", "solbraid-latex-v1"),
            nativeAvailable = res.optBoolean("nativeAvailable", false),
            nativeEngine = res.str("nativeEngine"),
            nativePath = res.str("nativePath"),
        )
    }

    suspend fun compileLatex(source: String, cwd: String?, filename: String = "document"): LatexCompileResult {
        check(runtime.hubAvailable) { "运行环境未就绪" }
        val res = runtime.hubRequest("latex/compile", jsonOf("source" to source, "cwd" to cwd, "filename" to filename), 65_000)
        return LatexCompileResult(
            ok = res.optBoolean("ok", false),
            nativeAvailable = res.optBoolean("nativeAvailable", false),
            engine = res.optString("engine", "solbraid-latex-v1"),
            pdfPath = res.str("pdfPath"),
            pdfSize = res.optLong("pdfSize", 0L),
            log = res.optString("log"),
        )
    }
    internal fun clearTurnStream(key: ConversationKey) {
        activeStreamMsgId.remove(key)
        activeThoughtMsgId.remove(key)
        interceptedMetaToolIds.remove(key)
    }

    private fun closeActiveThought(key: ConversationKey, endedAt: Long = System.currentTimeMillis()) {
        val thoughtId = activeThoughtMsgId.remove(key) ?: return
        repo.update(key) { state ->
            state.copy(items = state.items.map { item ->
                if (item is TimelineItem.Reasoning && item.id == thoughtId && item.streaming) {
                    item.copy(
                        text = item.text.trimEnd(),
                        streaming = false,
                        completedAtMs = item.completedAtMs ?: endedAt,
                    )
                } else item
            })
        }
    }

    /** 统一收尾外部 Agent 轮次：记录本轮耗时并在缺少细粒度 FileEdit 事件时从 git turnDiff 合成文件变更卡。 */
    internal fun finishTurn(key: ConversationKey, turnDiff: String? = null) {
        clearTurnStream(key)
        repo.update(key) { state ->
            val resolvedDur = state.turnStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
            val lastUserIdx = state.items.indexOfLast { it is TimelineItem.UserMessage }
            val lastUser = state.items.getOrNull(lastUserIdx) as? TimelineItem.UserMessage
            val nextDurs = if (resolvedDur != null && lastUser != null && lastUser.id !in state.turnDurations) {
                state.turnDurations + (lastUser.id to resolvedDur)
            } else state.turnDurations
            val effectiveDiff = turnDiff?.takeIf { it.isNotBlank() } ?: state.turnDiff
            val endedAt = System.currentTimeMillis()
            var nextItems = state.items.map { item ->
                when {
                    item is TimelineItem.AgentMessage -> item.copy(streaming = false)
                    item is TimelineItem.Reasoning -> item.copy(
                        text = item.text.trimEnd(),
                        streaming = false,
                        completedAtMs = item.completedAtMs ?: endedAt,
                    )
                    item is TimelineItem.UserMessage && lastUser != null && item.id == lastUser.id && item.durationMs == null && resolvedDur != null ->
                        item.copy(durationMs = resolvedDur)
                    else -> item
                }
            }
            if (!effectiveDiff.isNullOrBlank()) {
                val tail = if (lastUserIdx >= 0) nextItems.drop(lastUserIdx + 1) else nextItems
                if (tail.none { it is TimelineItem.FileEdit }) {
                    val parsedDiffs = splitMultiFileDiff(effectiveDiff)
                    if (parsedDiffs.isNotEmpty()) {
                        val diffItem = TimelineItem.FileEdit("turn-diff-${lastUser?.id ?: newId()}", parsedDiffs, ItemStatus.COMPLETED)
                        val insertAt = nextItems.indexOfLast { it is TimelineItem.AgentMessage }.takeIf { it > lastUserIdx } ?: nextItems.size
                        nextItems = nextItems.toMutableList().apply { add(insertAt, diffItem) }
                    }
                }
            }
            state.copy(
                running = false,
                turnStartedAt = null,
                focusText = null,
                turnDiff = effectiveDiff,
                turnDurations = nextDurs,
                items = nextItems,
            )
        }
    }

    private fun handleHubPacket(packet: JSONObject) {
        val method = packet.optString("method")
        val params = packet.optJSONObject("params") ?: JSONObject()
        if (packet.has("id") && method == "mobileUse/execute") {
            val reqId = packet.get("id")
            val action = params.optString("action")
            val actionParams = params.optJSONObject("params") ?: JSONObject()
            repo.scope.launch {
                try {
                    val mobile = (runtime.app as com.galiais.agent.GaliaisApplication).mobileUse
                    val result = mobile.execute(action, actionParams)
                    runCatching { runtime.hubRespond(reqId, result) }
                } catch (cancel: CancellationException) {
                    runCatching { runtime.hubReject(reqId, -32000, "手机操控请求已取消") }
                    throw cancel
                } catch (err: Exception) {
                    runCatching { runtime.hubReject(reqId, -32000, err.message ?: "手机操控执行失败") }
                }
            }
            return
        }
        if (packet.has("id") && method in setOf("selfBuild/installApk", "selfBuild/hostStatus", "selfBuild/hostRollback", "selfBuild/requestRollback")) {
            val reqId = packet.get("id")
            val apkPath = params.optString("apkPath")
            repo.scope.launch {
                try {
                    val mobile = (runtime.app as com.galiais.agent.GaliaisApplication).mobileUse
                    val result = when (method) {
                        "selfBuild/hostStatus" -> mobile.selfBuildHostStatus()
                        "selfBuild/hostRollback" -> mobile.rollbackSelfBuildApk()
                        "selfBuild/requestRollback" -> mobile.rollbackSelfBuildApk(requireConfirmation = true)
                        else -> mobile.installSelfBuildApk(apkPath)
                    }
                    runCatching { runtime.hubRespond(reqId, result) }
                } catch (cancel: CancellationException) {
                    runCatching { runtime.hubReject(reqId, -32000, "调起系统安装器已取消") }
                    throw cancel
                } catch (err: Exception) {
                    runCatching { runtime.hubReject(reqId, -32000, err.message ?: "调起系统安装器失败") }
                }
            }
            return
        }
        if (packet.has("id") && method == "agent/delegate") {
            val reqId = packet.get("id")
            val fromKind = EngineKind.of(params.optString("agentId"))
            val sessionKey = params.str("sessionId")?.let { ConversationKey(fromKind, it) }
            val targetKind = EngineKind.of(params.str("targetAgent")?.takeIf { it.isNotBlank() } ?: "codex")
            repo.delegation.requested(fromKind, reqId, sessionKey, targetKind, params.optString("task"), params.str("cwd"))
            return
        }
        if (packet.has("id") && method == "agent/requestPermission") {
            val reqId = packet.get("id")
            val agentId = params.optString("agentId")
            val sessionId = params.str("sessionId")
            val kind = EngineKind.of(agentId)
            val key = sessionId?.let { ConversationKey(kind, it) }
            val toolCall = params.optJSONObject("toolCall") ?: JSONObject()
            val title = toolCall.str("title") ?: params.str("title") ?: "Agent 工具调用"
            val toolKind = toolCall.str("kind")?.lowercase().orEmpty()
            val rawInput = toolCall.optJSONObject("rawInput") ?: params
            val options = params.arr("options").objects().mapNotNull { option ->
                val id = option.str("optionId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PermissionOption(id, option.str("name") ?: id, option.optString("kind"))
            }
            val planMd = rawInput.str("plan")
                ?: rawInput.str("planMarkdown")
                ?: rawInput.str("Content")?.takeIf { isPlanArtifactPath(rawInput.str("Path") ?: rawInput.str("path")) }
            val planPath = rawInput.str("Path") ?: rawInput.str("path") ?: rawInput.str("planPath")
            val isPlanReview = toolKind == "plan" || title.equals("ExitPlanMode", ignoreCase = true) ||
                title.contains("plan", ignoreCase = true) && !planMd.isNullOrBlank() ||
                rawInput.optBoolean("RequestFeedback", false)
            if (isPlanReview && key != null && !planMd.isNullOrBlank()) {
                repo.upsertItem(key, TimelineItem.Plan(
                    id = "acp-plan-$reqId",
                    text = planMd,
                    title = extractPlanTitle(planMd, planPath),
                    filePath = planPath,
                    status = ItemStatus.RUNNING,
                ))
            }
            repo.addRequest(PendingRequest.ToolApproval(
                engine = kind,
                requestId = reqId,
                conversation = key,
                toolName = title,
                input = rawInput,
                reason = toolCall.str("kind"),
                changes = emptyList(),
                permissionOptions = options,
                isExitPlanMode = isPlanReview,
                planMarkdown = planMd,
                planFilePath = planPath,
            ))
            return
        }
        if (packet.has("id") && method == "agent/requestUserInput") {
            val reqId = packet.get("id")
            val agentId = params.optString("agentId")
            val sessionId = params.str("sessionId")
            val kind = EngineKind.of(agentId)
            val key = sessionId?.let { ConversationKey(kind, it) }
            val rawQuestions = params.arr("questions").objects().ifEmpty {
                if (params.str("question") != null || params.str("prompt") != null) listOf(params) else emptyList()
            }
            val qs = rawQuestions.mapIndexed { idx, q ->
                val rawOpts = q.optJSONArray("options")
                val labels = mutableListOf<String>()
                val descs = mutableListOf<String>()
                val previews = mutableListOf<String>()
                if (rawOpts != null) {
                    for (i in 0 until rawOpts.length()) {
                        val el = rawOpts.opt(i)
                        when (el) {
                            is JSONObject -> {
                                labels += el.optString("label").ifBlank { el.optString("title").ifBlank { el.optString("value") } }
                                descs += el.optString("description")
                                previews += el.optString("markdown").ifBlank { el.optString("preview") }
                            }
                            is String -> {
                                labels += el
                                descs += ""
                                previews += ""
                            }
                        }
                    }
                }
                val isMulti = q.optBoolean(
                    "multiSelect",
                    q.optBoolean(
                        "is_multi_select",
                        q.optBoolean("isMultiSelect", q.optBoolean("multiple", q.optBoolean("allowMultiple", false))),
                    ),
                )
                Question(
                    id = q.optString("id").ifBlank { q.optString("question").ifBlank { "q_$idx" } },
                    header = q.optString("header"),
                    text = q.optString("question").ifBlank { q.optString("prompt").ifBlank { q.optString("title") } },
                    options = labels,
                    allowOther = q.optBoolean("allowOther", q.optBoolean("isOther", true)),
                    secret = q.optBoolean("secret", q.optBoolean("isSecret", false)),
                    optionDescriptions = descs,
                    multiSelect = isMulti,
                    optionPreviews = previews,
                )
            }
            if (qs.isNotEmpty()) {
                repo.addRequest(PendingRequest.UserInput(kind, reqId, key, qs, protocol = "acp_question"))
            } else {
                runtime.hubRespond(reqId, jsonOf("cancelled" to true, "answers" to JSONObject()))
            }
            return
        }
        if (packet.has("id") && method == "agent/requestElicitation") {
            val reqId = packet.get("id")
            val agentId = params.optString("agentId")
            val sessionId = params.str("sessionId")
            val kind = EngineKind.of(agentId)
            val key = sessionId?.let { ConversationKey(kind, it) }
            repo.addRequest(PendingRequest.Elicitation(
                engine = kind,
                requestId = reqId,
                conversation = key,
                message = listOfNotNull(params.str("title"), params.str("message") ?: params.str("description")).joinToString("\n"),
                raw = params,
                schemaFields = parseElicitationSchema(params),
                url = params.str("url"),
                serverName = params.str("serverName"),
                mode = params.str("mode") ?: if (params.str("url") != null) "url" else "form",
            ))
            return
        }
        when (method) {
            "hub/disconnected" -> {
                reset()
                val message = params.optString("message", "外部 Agent 服务已断开")
                repo.requests.value.filter { it.engine.id !in setOf("codex", "claude") }.forEach { repo.removeRequest(it.engine, it.requestId) }
                repo.conversations.value.keys.filter { it.engine.id !in setOf("codex", "claude") }.forEach { key ->
                    repo.update(key) { it.copy(running = false, loaded = false, turnStartedAt = null) }
                    repo.upsertItem(key, TimelineItem.Notice("hub-disconnected", message, error = true))
                }
                repo.notify("外部 Agent 服务已断开，请重启运行环境")
            }
            "galiais/requestResolved" -> params.opt("id")?.let { id ->
                repo.requests.value.filter { it.engine.id !in setOf("codex", "claude") && it.requestId.toString() == id.toString() }
                    .forEach { repo.removeRequest(it.engine, id) }
            }
            "sessions/started", "sessions/completed", "sessions/failed" -> {
                val kind = EngineKind.of(params.getString("agentId"))
                val key = ConversationKey(kind, params.getString("sessionId"))
                if (method == "sessions/started") {
                    repo.update(key) { state ->
                        state.copy(
                            running = true,
                            turnStartedAt = state.turnStartedAt ?: System.currentTimeMillis(),
                            turnDiff = null,
                        )
                    }
                } else {
                    val ctxObj = params.optJSONObject("contextUsage")
                    val ctxUsed = ctxObj?.optLong("used", -1L)?.takeIf { it >= 0L }
                    val ctxSize = ctxObj?.optLong("size", -1L)?.takeIf { it > 0L }
                    params.optJSONObject("usage")?.let { u ->
                        val rawIn = u.optLong("inputTokens", 0L)
                        val outTok = u.optLong("outputTokens", 0L)
                        val cacheTok = u.optLong("cachedReadTokens", 0L)
                        val cacheWriteTok = u.optLong("cachedWriteTokens", 0L)
                        val thoughtTok = u.optLong("thoughtTokens", 0L)
                        val inTok = if (rawIn > 0L && rawIn < cacheTok + cacheWriteTok) rawIn + cacheTok + cacheWriteTok else rawIn.coerceAtLeast(cacheTok + cacheWriteTok)
                        val totalTok = u.optLong("totalTokens", inTok + outTok).coerceAtLeast(inTok + outTok)
                        if (ctxUsed != null || totalTok > 0L || inTok > 0L || outTok > 0L) {
                            repo.update(key) { st ->
                                val prev = st.tokenUsage
                                val resolvedWindow = ctxSize ?: prev?.contextWindow ?: inferModelContextWindow(st.model)
                                // 优先保持 usage_update / contextUsage 上报的真实上下文窗口占用，绝不用本轮累计账单 totalTok 覆盖！
                                val currentContext = ctxUsed ?: prev?.totalTokens ?: totalTok
                                val nextSessionTotal = (prev?.sessionTotalTokens ?: 0L) + totalTok.coerceAtLeast(0L)
                                st.copy(
                                    tokenUsage = TokenUsage(
                                        totalTokens = currentContext,
                                        inputTokens = if (inTok > 0L) inTok else (prev?.inputTokens ?: currentContext),
                                        cachedInputTokens = if (cacheTok > 0L) cacheTok else (prev?.cachedInputTokens ?: 0L),
                                        cacheWriteTokens = if (cacheWriteTok > 0L) cacheWriteTok else (prev?.cacheWriteTokens ?: 0L),
                                        outputTokens = if (outTok > 0L) outTok else (prev?.outputTokens ?: 0L),
                                        reasoningTokens = if (thoughtTok > 0L) thoughtTok else (prev?.reasoningTokens ?: 0L),
                                        contextWindow = resolvedWindow,
                                        sessionTotalTokens = nextSessionTotal.takeIf { it > 0L },
                                    ),
                                )
                            }
                        }
                    } ?: run {
                        if (ctxUsed != null || ctxSize != null) {
                            repo.update(key) { st ->
                                val prev = st.tokenUsage
                                st.copy(
                                    tokenUsage = TokenUsage(
                                        totalTokens = ctxUsed ?: (prev?.totalTokens ?: 0L),
                                        inputTokens = ctxUsed ?: (prev?.inputTokens ?: 0L),
                                        cachedInputTokens = prev?.cachedInputTokens ?: 0L,
                                        outputTokens = prev?.outputTokens ?: 0L,
                                        reasoningTokens = prev?.reasoningTokens ?: 0L,
                                        contextWindow = ctxSize ?: prev?.contextWindow ?: inferModelContextWindow(st.model),
                                        sessionTotalTokens = prev?.sessionTotalTokens,
                                    ),
                                )
                            }
                        }
                    }
                    finishTurn(key, params.str("turnDiff"))
                }
                if (method == "sessions/failed") repo.upsertItem(key, TimelineItem.Notice("hub-failure-${key.id}", params.optString("message"), error = true))
            }
            "agents/installProgress" -> {
                val id = params.getString("agentId")
                val progress = AgentInstallProgress.fromJson(params.getJSONObject("installation"))
                val current = statusesFlow.value[id] ?: AgentRuntimeStatus(id)
                if (current.installation?.phase?.active == true && current.installation.operationId != progress.operationId) return
                statusesFlow.value = statusesFlow.value + (id to current.copy(installation = progress, installing = progress.phase.active,
                    installError = if (progress.phase == AgentInstallPhase.FAILED) params.str("error") else null))
            }
            "agents/installLog" -> {
                val id = params.optString("agentId")
                val line = params.optString("line")
                val prev = installLogsFlow.value[id].orEmpty()
                installLogsFlow.value = installLogsFlow.value + (id to (prev + line).takeLast(200))
                if (params.optBoolean("done")) {
                    val result = params.obj("status")?.let(::parseStatus)
                        ?: (statusesFlow.value[id] ?: AgentRuntimeStatus(id)).copy(installing = false,
                            installError = if (params.optBoolean("ok") || params.optBoolean("cancelled")) null else line)
                    val current = statusesFlow.value[id]
                    if (current?.installation?.phase?.active != true || current.installation.operationId == result.installation?.operationId)
                        statusesFlow.value = statusesFlow.value + (id to result)
                }
            }
            "agents/authEvent" -> {
                val id = params.optString("agentId")
                val line = params.optString("line")
                val url = params.str("url")
                val deviceCode = params.str("deviceCode")
                val needsInput = params.optBoolean("needsInput", false)
                val done = params.optBoolean("done", false)
                val ok = if (params.has("ok")) params.optBoolean("ok") else null
                val prev = authStatesFlow.value[id] ?: AgentAuthState(id, running = true)
                authStatesFlow.value = authStatesFlow.value + (id to prev.copy(
                    running = !done,
                    url = url ?: prev.url,
                    deviceCode = deviceCode ?: prev.deviceCode,
                    needsInput = if (done) false else (needsInput || prev.needsInput),
                    lines = (prev.lines + line).takeLast(80),
                    done = done,
                    ok = ok ?: prev.ok,
                ))
            }
            "agents/hibernated" -> {
                val id = params.optString("agentId")
                statusesFlow.value[id]?.let {
                    statusesFlow.value = statusesFlow.value + (id to it.copy(running = false))
                }
            }
            "pty/output", "pty/closed", "pty/sessionsChanged" -> terminals.onPacket(method, params)
            "git/cloneLog" -> {
                val line = params.optString("line")
                if (line.isNotBlank()) cloneLogsFlow.value = (cloneLogsFlow.value + line).takeLast(120)
            }
            "tcp/data" -> portForwarder.onRemoteData(params.optString("connId"), params.optString("dataBase64"))
            "tcp/closed" -> portForwarder.onRemoteClosed(params.optString("connId"))
            "plugins/changed" -> {
                repo.scope.launch {
                    runCatching { repo.extensions.refreshHostPlugins() }
                    runCatching { repo.refresh() }
                }
            }
            "agent/event" -> handleAgentEvent(params)
        }
    }

    private fun handleAgentEvent(params: JSONObject) {
        val agentId = params.optString("agentId")
        val sessionId = params.optString("sessionId")
        if (agentId.isBlank() || sessionId.isBlank()) return
        val key = ConversationKey(EngineKind.of(agentId), sessionId)
        when (params.optString("kind")) {
            "notice" -> repo.upsertItem(key, TimelineItem.Notice("notice-" + newId(), params.optString("text"), params.optBoolean("error")))
            "thought-reset" -> {
                closeActiveThought(key)
                params.str("focus")?.takeIf { it.isNotBlank() }?.let { focus ->
                    repo.update(key) { it.copy(focusText = focus) }
                }
            }
            "turn-retry" -> {
                // 先刷出已缓冲的流式分片，再清理上一轮失败尝试遗留的软错误文本或中断思考，避免重复拼接成巨型思考块。
                repo.findItem(key, "")
                val oldStreamId = activeStreamMsgId.remove(key)
                val oldThoughtId = activeThoughtMsgId.remove(key)
                val clearPartialThought = params.optBoolean("clearPartialThought", false)
                val discardSoftReply = params.optBoolean("discardSoftReply", true)
                val attempt = params.optInt("attempt", 1)
                val maxAttempts = params.optInt("maxAttempts", 3)
                val delaySec = ((params.optLong("delayMs", 2000L) + 500L) / 1000L).coerceAtLeast(1L)
                val reason = params.str("reason").orEmpty().ifBlank { "网络波动" }
                val statusText = params.str("focus") ?: "${reason}，${delaySec}秒后自动重试（第 ${attempt}/${maxAttempts} 次）…"
                val endedAt = System.currentTimeMillis()
                repo.update(key) { state ->
                    val lastUserIdx = state.items.indexOfLast { it is TimelineItem.UserMessage }
                    val nextItems = state.items.mapIndexedNotNull { idx, item ->
                        if (idx <= lastUserIdx) return@mapIndexedNotNull item
                        when {
                            discardSoftReply && item is TimelineItem.AgentMessage && (item.id == oldStreamId || item.text.contains("network issue", ignoreCase = true) || item.text.trim().startsWith("⚠")) -> null
                            clearPartialThought && item is TimelineItem.Reasoning && (item.id == oldThoughtId || item.streaming) -> null
                            item is TimelineItem.Reasoning && item.streaming -> item.copy(
                                text = item.text.trimEnd(),
                                streaming = false,
                                completedAtMs = item.completedAtMs ?: endedAt,
                            )
                            item is TimelineItem.AgentMessage && item.streaming -> item.copy(streaming = false)
                            else -> item
                        }
                    }
                    state.copy(items = nextItems, focusText = statusText)
                }
            }
            "acp-update" -> handleAcpUpdate(key, params.optJSONObject("update") ?: return)
        }
    }

    private fun handleAcpUpdate(key: ConversationKey, update: JSONObject) {
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text") ?: update.optString("text")
                if (text.isEmpty()) return
                closeActiveThought(key)
                val id = activeStreamMsgId.getOrPut(key) { "agent-" + newId() }
                repo.appendStreamingDelta(key, id, text, "agent") { TimelineItem.AgentMessage(id, "", streaming = true) }
            }
            "agent_thought_chunk" -> {
                val raw = update.optJSONObject("content")?.optString("text") ?: update.optString("text")
                if (raw.isEmpty()) return
                val id = activeThoughtMsgId.getOrPut(key) { "thought-" + newId() }
                val now = System.currentTimeMillis()
                val cleaned = if (raw.contains("\n\n\n")) raw.replace(MULTI_NL_RE, "\n\n") else raw
                repo.appendStreamingDelta(key, id, cleaned, "reasoning") { TimelineItem.Reasoning(id, "", streaming = true, startedAtMs = now) }
            }
            "tool_call", "tool_call_update" -> {
                val callId = update.str("toolCallId") ?: ("tool-" + newId())
                val title = update.str("title") ?: ""
                val kindStr = update.str("kind")?.lowercase().orEmpty()
                val rawInputObj = update.optJSONObject("rawInput")
                val metaIds = interceptedMetaToolIds.getOrPut(key) { mutableSetOf() }
                // 原生步骤清单与焦点状态工具拦截（兼容 Antigravity "Run update_steps" / "Run update_focus" / "Run task_boundary" 及其完成态更新）
                if (callId in metaIds || isAcpMetaToolTitle(title)) {
                    metaIds += callId
                    if (rawInputObj != null) tryApplyAcpStepChecklist(key, title, rawInputObj)
                    return
                }
                if (update.optString("sessionUpdate") == "tool_call") {
                    // 工具调用到来时切分流式消息段并完结前一段推理链，使思考→工具→再思考→回复层次清晰。
                    activeStreamMsgId.remove(key)
                    closeActiveThought(key)
                }
                val st = when (update.optString("status").lowercase()) {
                    "completed" -> ItemStatus.COMPLETED
                    "failed" -> ItemStatus.FAILED
                    else -> ItemStatus.RUNNING
                }
                val input = update.opt("rawInput")?.takeUnless { it == JSONObject.NULL }?.toString() ?: ""
                val rawOutputAny = update.opt("rawOutput")?.takeUnless { it == JSONObject.NULL }
                val contentBlocks = update.arr("content").objects()
                val diffBlocks = extractAcpFileDiffs(kindStr, rawInputObj, contentBlocks)
                val outputText = extractAcpOutputText(rawOutputAny, contentBlocks)

                // 原生计划 Artifact 文件识别（implementation_plan.md / plan.md / ArtifactMetadata 计划文档 / ACP locations）
                val locations = update.arr("locations").objects()
                val writtenPlan = extractAcpPlanArtifact(title, rawInputObj, diffBlocks, locations)
                if (writtenPlan != null) {
                    val reqFeedback = writtenPlan.requestFeedback
                    repo.upsertItem(key, TimelineItem.Plan(
                        id = writtenPlan.path?.let { "brain-${it.substringAfterLast('/')}" } ?: "plan-$callId",
                        text = writtenPlan.text,
                        title = extractPlanTitle(writtenPlan.text, writtenPlan.path),
                        streaming = st == ItemStatus.RUNNING,
                        filePath = writtenPlan.path,
                        status = if (st == ItemStatus.RUNNING || reqFeedback) ItemStatus.RUNNING else st,
                        requestFeedback = reqFeedback,
                        summary = writtenPlan.summary,
                        artifactType = writtenPlan.artifactType,
                    ))
                }

                val existing = repo.findItem(key, callId)
                val isFileEdit = existing is TimelineItem.FileEdit || diffBlocks.isNotEmpty() || kindStr in setOf("edit", "write", "delete")
                val isCommand = existing is TimelineItem.CommandRun || (!isFileEdit && (kindStr == "execute" || rawInputObj?.str("command") != null || rawInputObj?.str("cmd") != null))

                when {
                    isFileEdit -> {
                        repo.mutateItem(key, callId, { TimelineItem.FileEdit(callId, diffBlocks, st) }) {
                            if (it is TimelineItem.FileEdit) it.copy(
                                changes = if (diffBlocks.isNotEmpty()) diffBlocks else it.changes,
                                status = st,
                            ) else it
                        }
                    }
                    isCommand -> {
                        commandStartedAt.putIfAbsent(callId, System.currentTimeMillis())
                        val cmd = rawInputObj?.str("command") ?: rawInputObj?.str("cmd") ?: title.ifBlank { "执行命令" }
                        val cmdCwd = rawInputObj?.str("cwd")
                        val exitCode = (rawOutputAny as? JSONObject)?.let { o ->
                            if (o.has("exitCode")) o.optInt("exitCode") else if (o.has("exit_code")) o.optInt("exit_code") else null
                        } ?: if (st == ItemStatus.COMPLETED) 0 else null
                        val durMs = if (st == ItemStatus.COMPLETED || st == ItemStatus.FAILED) {
                            commandStartedAt.remove(callId)?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                        } else null
                        repo.mutateItem(key, callId, {
                            TimelineItem.CommandRun(callId, cmd, cmdCwd, outputText.orEmpty().takeLast(200_000), exitCode, st, durationMs = durMs)
                        }) {
                            if (it is TimelineItem.CommandRun) it.copy(
                                command = cmd.ifBlank { it.command },
                                cwd = cmdCwd ?: it.cwd,
                                output = outputText?.takeLast(200_000) ?: it.output,
                                exitCode = exitCode ?: it.exitCode,
                                status = st,
                                durationMs = durMs ?: it.durationMs,
                            ) else it
                        }
                    }
                    else -> {
                        val normalizedName = when (kindStr) {
                            "read" -> title.ifBlank { "Read" }
                            "search" -> title.ifBlank { "Grep" }
                            "fetch" -> title.ifBlank { "WebFetch" }
                            else -> title.ifBlank { "工具调用" }
                        }
                        val preservedKind = kindStr.takeIf { it.isNotBlank() }
                        repo.mutateItem(key, callId, { TimelineItem.ToolCall(callId, normalizedName, input, outputText, st, kind = preservedKind) }) {
                            if (it is TimelineItem.ToolCall) it.copy(
                                name = normalizedName.ifBlank { it.name },
                                input = input.ifBlank { it.input },
                                result = outputText ?: it.result,
                                status = st,
                                kind = preservedKind ?: it.kind,
                            ) else it
                        }
                    }
                }
            }
            "plan" -> {
                val steps = update.arr("entries").objects().mapNotNull {
                    val label = it.optString("content").ifBlank { it.optString("title").ifBlank { it.optString("step") } }
                    if (label.isBlank()) null
                    else PlanStep(
                        step = label,
                        status = normalizePlanStepStatus(it.optString("status", "pending")),
                        id = it.str("id"),
                        activeForm = it.str("activeForm"),
                        priority = it.str("priority"),
                    )
                }
                val explanation = update.str("explanation") ?: update.str("title")
                if (steps.isNotEmpty() || update.optBoolean("clear", false)) {
                    repo.update(key) { it.copy(plan = steps, planExplanation = explanation ?: if (steps.isEmpty()) null else it.planExplanation) }
                }
                val planMd = update.str("markdown") ?: update.str("text") ?: update.str("plan")
                if (!planMd.isNullOrBlank()) {
                    val planPath = update.str("path")
                    val planId = update.str("id") ?: planPath?.let { "brain-${it.substringAfterLast('/')}" } ?: "acp-plan-${key.id}"
                    val reqFeedback = update.optBoolean("requestFeedback", false)
                    val artType = update.str("artifactType") ?: inferPlanArtifactType(planPath)
                    repo.upsertItem(key, TimelineItem.Plan(
                        id = planId,
                        text = planMd,
                        title = update.str("title") ?: extractPlanTitle(planMd, planPath),
                        filePath = planPath,
                        status = if (reqFeedback) ItemStatus.RUNNING else ItemStatus.COMPLETED,
                        requestFeedback = reqFeedback,
                        summary = update.str("summary"),
                        artifactType = artType,
                    ))
                }
            }
            "focus_update" -> {
                val focus = update.str("focus") ?: update.str("text")
                if (!focus.isNullOrBlank()) {
                    repo.update(key) { it.copy(focusText = focus) }
                }
            }
            "available_commands_update" -> {
                dynamicCommands[key] = update.arr("availableCommands").objects().mapNotNull {
                    val name = it.optString("name").removePrefix("/")
                    if (name.isBlank()) null else SlashCommand(name, it.optString("description"))
                }
            }
            "current_mode_update" -> {
                update.str("currentModeId")?.let { m -> repo.update(key) { it.copy(permissionMode = m) } }
            }
            "config_option_update" -> {
                val arr = update.optJSONArray("configOptions")
                updateConfigOptions(key, arr)
                parseConfigOptions(arr).firstOrNull { it.id == "thought_level" }?.currentValue?.let { eff ->
                    repo.update(key) { it.copy(effort = eff) }
                }
            }
            "usage_update" -> {
                val used = update.optLong("used", -1L)
                val size = update.optLong("size", -1L).takeIf { it > 0L }
                if (used >= 0L || size != null) {
                    repo.update(key) { state ->
                        val prev = state.tokenUsage
                        state.copy(
                            tokenUsage = TokenUsage(
                                totalTokens = if (used >= 0L) used else (prev?.totalTokens ?: 0L),
                                inputTokens = if (used >= 0L) used else (prev?.inputTokens ?: 0L),
                                cachedInputTokens = prev?.cachedInputTokens ?: 0L,
                                outputTokens = prev?.outputTokens ?: 0L,
                                reasoningTokens = prev?.reasoningTokens ?: 0L,
                                contextWindow = size ?: prev?.contextWindow ?: inferModelContextWindow(state.model),
                                sessionTotalTokens = prev?.sessionTotalTokens,
                            ),
                        )
                    }
                }
            }
            "available_models_update" -> {
                val rawModels = update.arr("availableModels").objects().mapNotNull { m ->
                    val id = m.str("modelId") ?: m.str("id") ?: return@mapNotNull null
                    ModelOption(
                        id = id,
                        label = m.str("name") ?: id,
                        description = m.optString("description"),
                        efforts = m.arr("efforts").strings(),
                    )
                }
                val models = if (key.engine.id == "antigravity") canonicalizeAgyModels(rawModels) else rawModels
                if (models.isNotEmpty()) availableModels[key] = models
            }
        }
    }

    private data class ExtractedPlanArtifact(
        val path: String?,
        val text: String,
        val requestFeedback: Boolean,
        val summary: String?,
        val artifactType: String,
    )

    private fun inferPlanArtifactType(path: String?): String {
        val file = path?.lowercase()?.replace('\\', '/')?.substringAfterLast('/').orEmpty()
        return when {
            file == "implementation_plan.md" || file == "plan.md" -> "implementation_plan"
            file == "walkthrough.md" -> "walkthrough"
            file == "task.md" -> "task"
            else -> "plan"
        }
    }

    private fun isPlanArtifactPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase().replace('\\', '/')
        val file = lower.substringAfterLast('/')
        return file == "implementation_plan.md" || file == "plan.md" ||
            (lower.contains("/brain/") && file.endsWith(".md") && (file.contains("plan") || file.contains("walkthrough") || file == "task.md")) ||
            (lower.contains("/.claude/plans/") && file.endsWith(".md"))
    }

    private fun extractAcpPlanArtifact(
        title: String,
        rawInput: JSONObject?,
        diffs: List<FileDiff>,
        locations: List<JSONObject> = emptyList(),
    ): ExtractedPlanArtifact? {
        val locPath = locations.firstNotNullOfOrNull { it.str("path") ?: it.str("uri")?.removePrefix("file://") }
        val path = rawInput?.str("Path") ?: rawInput?.str("TargetFile") ?: rawInput?.str("path")
            ?: rawInput?.str("file_path") ?: rawInput?.str("filePath") ?: locPath
        val artifactMeta = rawInput?.optJSONObject("ArtifactMetadata")
        val hasArtifactMeta = artifactMeta != null
        val lowerTitle = title.lowercase()
        val isPlanTool = lowerTitle == "exitplanmode" || lowerTitle.contains("plan_artifact") || isPlanArtifactPath(path) ||
            (hasArtifactMeta && path?.endsWith(".md", ignoreCase = true) == true)
        if (!isPlanTool) return null
        val text = rawInput?.str("Content")
            ?: rawInput?.str("CodeContent")
            ?: rawInput?.str("content")
            ?: rawInput?.str("plan")
            ?: diffs.firstOrNull()?.let { d ->
                d.diff.lineSequence().filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.substring(1) }.joinToString("\n")
            }
        val cleanText = text?.takeIf { it.isNotBlank() } ?: return null
        val artType = inferPlanArtifactType(path)
        val reqFeedback = artifactMeta?.let { m ->
            if (m.has("RequestFeedback")) m.optBoolean("RequestFeedback", false)
            else if (m.has("requestFeedback")) m.optBoolean("requestFeedback", false)
            else artType == "implementation_plan"
        } ?: (artType == "implementation_plan")
        val summary = artifactMeta?.str("Summary") ?: artifactMeta?.str("summary")
        return ExtractedPlanArtifact(path, cleanText, reqFeedback, summary, artType)
    }

    private fun tryApplyAcpStepChecklist(key: ConversationKey, title: String, rawInput: JSONObject): Boolean {
        val norm = normalizeAcpMetaToolName(title)
        if (norm == "update_focus") {
            val focus = rawInput.str("focus") ?: rawInput.str("status")
            if (!focus.isNullOrBlank()) {
                repo.update(key) { it.copy(focusText = focus) }
            }
            return true
        }
        val stepArray = when {
            norm in setOf("update_steps", "update_plan", "todowrite", "todo_write", "set_plan") ->
                rawInput.optJSONArray("steps") ?: rawInput.optJSONArray("plan") ?: rawInput.optJSONArray("todos") ?: rawInput.optJSONArray("entries")
            else -> null
        }
        if (stepArray != null) {
            val steps = stepArray.objects().mapNotNull { obj ->
                val label = obj.optString("title").ifBlank { obj.optString("content").ifBlank { obj.optString("step") } }
                if (label.isBlank()) null
                else PlanStep(
                    step = label,
                    status = normalizePlanStepStatus(obj.optString("status", "pending")),
                    id = obj.str("id"),
                    activeForm = obj.str("activeForm"),
                    priority = obj.str("priority"),
                )
            }
            val explanation = rawInput.str("explanation") ?: rawInput.str("summary") ?: "${key.engine.label} 执行步骤"
            repo.update(key) { it.copy(plan = steps, planExplanation = if (steps.isEmpty()) null else explanation) }
            return true
        }
        if (norm == "task_boundary") {
            val taskName = rawInput.str("TaskName") ?: return false
            val taskStatus = rawInput.str("TaskStatus")
            val mode = rawInput.str("Mode")
            repo.update(key) { st ->
                val existing = st.plan.toMutableList()
                val idx = existing.indexOfFirst { it.step == taskName }
                val step = PlanStep(
                    step = taskName,
                    status = "in_progress",
                    activeForm = taskStatus,
                    priority = mode,
                )
                if (idx >= 0) existing[idx] = step else {
                    for (i in existing.indices) {
                        if (existing[i].status == "in_progress") existing[i] = existing[i].copy(status = "completed")
                    }
                    existing += step
                }
                st.copy(plan = existing, planExplanation = taskStatus ?: mode ?: "${key.engine.label} 当前任务")
            }
            return true
        }
        return false
    }

    private fun extractAcpFileDiffs(kind: String, rawInput: JSONObject?, contentBlocks: List<JSONObject>): List<FileDiff> {
        val diffs = mutableListOf<FileDiff>()
        for (block in contentBlocks) {
            if (block.optString("type").equals("diff", ignoreCase = true)) {
                val path = block.str("path") ?: continue
                val precomputed = block.str("diff")
                val oldText = block.optString("oldText", "")
                val newText = block.optString("newText", "")
                val editKind = block.str("kind")?.takeIf { it in setOf("add", "delete", "update") } ?: when {
                    oldText.isEmpty() && newText.isNotEmpty() -> "add"
                    newText.isEmpty() && oldText.isNotEmpty() -> "delete"
                    else -> "update"
                }
                val diffBody = precomputed ?: unifiedDiff(path, oldText, newText)
                diffs += FileDiff(path, editKind, diffBody)
            }
        }
        if (diffs.isNotEmpty() || rawInput == null) return diffs
        val path = rawInput.str("path") ?: rawInput.str("file_path") ?: rawInput.str("filePath") ?: return emptyList()
        return when {
            rawInput.has("old_string") || rawInput.has("new_string") ->
                listOf(FileDiff(path, "update", unifiedDiff(path, rawInput.optString("old_string"), rawInput.optString("new_string"))))
            rawInput.has("oldText") || rawInput.has("newText") ->
                listOf(FileDiff(path, "update", unifiedDiff(path, rawInput.optString("oldText"), rawInput.optString("newText"))))
            kind == "write" && rawInput.has("content") ->
                listOf(FileDiff(path, "add", unifiedDiff(path, "", rawInput.optString("content"))))
            kind == "delete" ->
                listOf(FileDiff(path, "delete", ""))
            else -> emptyList()
        }
    }

    private fun extractAcpOutputText(rawOutput: Any?, contentBlocks: List<JSONObject>): String? {
        val fromBlocks = contentBlocks.mapNotNull { b ->
            when (b.optString("type").lowercase()) {
                "content" -> b.optJSONObject("content")?.str("text") ?: b.str("text")
                "text" -> b.str("text")
                else -> null
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }
        if (fromBlocks != null) return fromBlocks
        return when (rawOutput) {
            is String -> rawOutput.takeIf { it.isNotBlank() }
            is JSONObject -> rawOutput.str("stdout") ?: rawOutput.str("output") ?: rawOutput.str("text") ?: rawOutput.toString()
            null -> null
            else -> rawOutput.toString()
        }
    }
}

/** 单个外部 Agent 的通用协议适配器：将 `EngineAdapter` 全部方法路由到 `agent-hub.mjs`。 */
class UniversalAgentAdapter(private val hub: AgentHubController, override val kind: EngineKind) : EngineAdapter {
    private val repo get() = hub.repo
    private val runtime get() = repo.runtime
    private val settings get() = repo.settings

    override suspend fun list(): List<ConversationSummary> {
        if (!runtime.hubAvailable) return emptyList()
        val res = runtime.hubRequest("sessions/list", jsonOf("agentId" to kind.id))
        return res.arr("sessions").objects().map { s ->
            val id = s.optString("id")
            ConversationSummary(
                key = ConversationKey(kind, id),
                title = s.str("title") ?: "新会话",
                preview = s.optString("preview"),
                cwd = s.str("cwd") ?: DEFAULT_WORKSPACE,
                updatedAt = normalizeTime(s.optLong("updatedAt", System.currentTimeMillis())),
                running = s.optBoolean("running"),
            )
        }
    }

    override suspend fun load(key: ConversationKey) {
        val res = runtime.hubRequest("sessions/load", jsonOf("agentId" to kind.id, "sessionId" to key.id))
        val session = res.optJSONObject("session") ?: return
        val items = buildList {
            session.arr("messages").objects().forEach { m ->
                val id = m.str("id") ?: newId()
                val text = m.optString("text")
                when (m.optString("role")) {
                    "user" -> add(TimelineItem.UserMessage(id, text, m.optInt("imageCount", 0)))
                    "thought" -> add(TimelineItem.Reasoning(
                        id = id,
                        text = text,
                        streaming = false,
                        startedAtMs = m.optLong("startedAtMs", 0L).takeIf { it > 0L },
                        completedAtMs = m.optLong("completedAtMs", 0L).takeIf { it > 0L },
                    ))
                    "tool" -> {
                        val toolName = m.optString("name", "工具调用")
                        if (isAcpMetaToolTitle(toolName)) return@forEach
                        val st = when (m.optString("status").lowercase()) {
                            "failed" -> ItemStatus.FAILED
                            "declined" -> ItemStatus.DECLINED
                            else -> ItemStatus.COMPLETED
                        }
                        val kindStr = m.optString("kind").lowercase()
                        val diffs = m.arr("diffs").objects().mapNotNull { d ->
                            val p = d.str("path") ?: return@mapNotNull null
                            val dk = d.optString("kind", "update")
                            val diffText = d.str("diff") ?: unifiedDiff(p, d.optString("oldText"), d.optString("newText"))
                            FileDiff(p, dk, diffText)
                        }
                        val cmd = m.str("command")
                        when {
                            diffs.isNotEmpty() -> add(TimelineItem.FileEdit(id, diffs, st))
                            !cmd.isNullOrBlank() || kindStr == "execute" -> add(TimelineItem.CommandRun(
                                id = id,
                                command = cmd ?: toolName.ifBlank { "执行命令" },
                                cwd = m.str("cwd"),
                                output = m.optString("result"),
                                exitCode = if (m.has("exitCode")) m.optInt("exitCode") else if (st == ItemStatus.COMPLETED) 0 else null,
                                status = st,
                                durationMs = m.optLong("durationMs", 0L).takeIf { it > 0L },
                            ))
                            else -> add(TimelineItem.ToolCall(
                                id = id,
                                name = toolName,
                                input = m.optString("input"),
                                result = m.str("result"),
                                status = st,
                                kind = kindStr.takeIf { it.isNotBlank() },
                            ))
                        }
                    }
                    "plan" -> {
                        if (text.isNotBlank()) {
                            val reqFb = m.optBoolean("requestFeedback", false)
                            val st = when (m.optString("status").lowercase()) {
                                "running" -> ItemStatus.RUNNING
                                "failed" -> ItemStatus.FAILED
                                "declined" -> ItemStatus.DECLINED
                                else -> if (reqFb) ItemStatus.RUNNING else ItemStatus.COMPLETED
                            }
                            add(TimelineItem.Plan(
                                id = id,
                                text = text,
                                title = m.str("title") ?: extractPlanTitle(text, m.str("filePath")),
                                streaming = false,
                                filePath = m.str("filePath"),
                                status = st,
                                requestFeedback = reqFb,
                                summary = m.str("summary"),
                                artifactType = m.str("artifactType") ?: "plan",
                            ))
                        }
                    }
                    "receipt" -> {
                        val entries = m.arr("entries").objects().mapNotNull { e ->
                            val lbl = e.optString("label")
                            val v = e.optString("value")
                            if (lbl.isBlank() && v.isBlank()) null else lbl to v
                        }
                        val st = if (m.optString("status").equals("declined", true)) ItemStatus.DECLINED else ItemStatus.COMPLETED
                        add(TimelineItem.InteractionReceipt(
                            id = id,
                            kind = m.optString("kind", "question"),
                            title = m.optString("title", "交互记录"),
                            entries = entries,
                            status = st,
                        ))
                    }
                    "agent" -> add(TimelineItem.AgentMessage(id, text, streaming = false))
                    "notice" -> add(TimelineItem.Notice(id, text, m.optBoolean("error")))
                }
            }
            // 为早期历史 Antigravity 会话回填磁盘上的 Brain 计划/总结产物，使重开后同样展现完整 Plan Artifact 卡
            val existingPlanPaths = filterIsInstance<TimelineItem.Plan>().mapNotNull { it.filePath }.toSet()
            session.arr("brainPlans").objects().forEach { bp ->
                val p = bp.str("path")
                val md = bp.optString("markdown")
                if (md.isBlank() || (p != null && p in existingPlanPaths)) return@forEach
                val reqFb = bp.optBoolean("requestFeedback", false)
                add(TimelineItem.Plan(
                    id = bp.str("id") ?: "brain-${p?.substringAfterLast('/') ?: newId()}",
                    text = md,
                    title = bp.str("title") ?: extractPlanTitle(md, p),
                    streaming = false,
                    filePath = p,
                    status = ItemStatus.COMPLETED,
                    requestFeedback = reqFb,
                    summary = bp.str("summary"),
                    artifactType = bp.str("artifactType") ?: "plan",
                ))
            }
        }
        val restoredSteps = session.arr("plan").objects().mapNotNull { s ->
            val label = s.optString("step").ifBlank { s.optString("title").ifBlank { s.optString("content") } }
            if (label.isBlank()) null
            else PlanStep(
                step = label,
                status = normalizePlanStepStatus(s.optString("status", "pending")),
                id = s.str("id"),
                activeForm = s.str("activeForm"),
                priority = s.str("priority"),
            )
        }
        val restoredPlanExpl = session.str("planExplanation")
        val rawModel = session.str("model") ?: settings.state.value.agentModels[kind.id]
        val (model, splitEff) = if (kind.id == "antigravity") hub.splitAgyModelEffort(rawModel) else (rawModel to null)
        val providerId = if (session.has("providerId")) session.str("providerId") else settings.state.value.agentProviders[kind.id]
        val mode = session.str("mode") ?: if (kind.id == "antigravity") settings.state.value.antigravityPermissionMode else "default"
        val effort = session.str("effort") ?: splitEff ?: hub.configOptionsOf(key).firstOrNull { it.id == "thought_level" }?.currentValue
        hub.updateSessionMetadata(key, session)
        val resolvedEffort = effort ?: hub.configOptionsOf(key).firstOrNull { it.id == "thought_level" }?.currentValue
        repo.update(key) { state ->
            state.copy(
                summary = state.summary.copy(
                    title = session.str("title") ?: state.summary.title,
                    cwd = session.str("cwd") ?: state.summary.cwd,
                ),
                items = if (items.isNotEmpty() || state.items.isEmpty()) items else state.items,
                plan = if (restoredSteps.isNotEmpty()) restoredSteps else state.plan,
                planExplanation = restoredPlanExpl ?: state.planExplanation,
                loaded = true,
                running = session.optBoolean("running"),
                providerId = providerId,
                model = model,
                effort = resolvedEffort ?: state.effort,
                permissionMode = mode,
            )
        }
    }

    override suspend fun create(cwd: String, options: CreateOptions): ConversationKey {
        check(runtime.hubAvailable) { "Agent 服务未就绪" }
        val normCwd = cwd.trim().trimEnd('/').ifEmpty { "/" }
        val st = settings.state.value
        val resolvedProviderId = when (options.providerId) {
            null -> st.agentProviders[kind.id]
            "" -> null
            else -> options.providerId
        }
        val resolvedModel = options.model
            ?: st.agentModels[kind.id].takeIf { resolvedProviderId == st.agentProviders[kind.id] }
            ?: st.provider(resolvedProviderId)?.models?.firstOrNull()
        if (resolvedProviderId != null) repo.syncProvidersNow()
        repo.envSsh.syncBeforeLaunch()
        val directApiKey = if (resolvedProviderId == null && kind.id == "antigravity") settings.antigravityApiKey() else null
        val defaultMode = if (kind.id == "antigravity") st.antigravityPermissionMode else null
        val defaultEffort = if (kind.id == "antigravity") st.antigravityEffort else null
        val res = runtime.hubRequest("sessions/create", jsonOf(
            "agentId" to kind.id,
            "cwd" to normCwd,
            "model" to resolvedModel,
            "mode" to defaultMode,
            "effort" to defaultEffort,
            "sandbox" to (kind.id == "antigravity" && st.antigravitySandbox),
            "bypassPermissions" to (kind.id == "antigravity" && defaultMode == "bypass"),
            "agentName" to if (kind.id == "antigravity") st.antigravityAgent else null,
            "transport" to if (kind.id == "antigravity") st.antigravityTransport else "acp",
            "allowDelegation" to options.allowDelegation,
            "provider" to hub.providerPayload(kind.id, resolvedProviderId, resolvedModel),
            "apiKey" to directApiKey,
        ))
        val session = res.getJSONObject("session")
        val id = session.getString("id")
        val key = ConversationKey(kind, id)
        hub.updateSessionMetadata(key, session)
        val initialEffort = session.str("effort") ?: hub.configOptionsOf(key).firstOrNull { it.id == "thought_level" }?.currentValue ?: defaultEffort
        repo.update(key) {
            ConversationState(
                summary = ConversationSummary(key, session.str("title") ?: "新会话", "", normCwd, System.currentTimeMillis(), parent = options.parent),
                loaded = true,
                providerId = resolvedProviderId,
                model = session.str("model") ?: resolvedModel,
                effort = initialEffort,
                permissionMode = session.str("mode") ?: defaultMode ?: "default",
            )
        }
        return key
    }

    override suspend fun send(key: ConversationKey, text: String, attachments: List<ImageAttachment>) {
        hub.clearTurnStream(key)
        val current = repo.state(key)
        val clientId = "user-" + newId()
        repo.upsertItem(key, TimelineItem.UserMessage(clientId, text, imageCount = attachments.size))
        repo.update(key) { st ->
            val preview = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: st.summary.title
            st.copy(
                running = true,
                turnStartedAt = System.currentTimeMillis(),
                focusText = null,
                turnDiff = null,
                summary = st.summary.copy(
                    title = if (st.summary.title in setOf("新会话", "未命名会话")) preview else st.summary.title,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        val imagesArr = JSONArray().apply {
            attachments.forEach { put(jsonOf("mimeType" to it.mimeType, "base64" to it.base64)) }
        }
        var returnedDiff: String? = null
        try {
            if (current?.providerId != null) repo.syncProvidersNow()
            repo.envSsh.syncBeforeLaunch()
            val directApiKey = if (current?.providerId == null && kind.id == "antigravity") settings.antigravityApiKey() else null
            val res = runtime.hubRequest("sessions/prompt", jsonOf(
                "agentId" to kind.id,
                "sessionId" to key.id,
                "clientMessageId" to clientId,
                "text" to text,
                "images" to imagesArr,
                "provider" to hub.providerPayload(kind.id, current?.providerId, current?.model),
                "apiKey" to directApiKey,
            ), timeoutMs = 1_500_000)
            val finalText = res.optString("text")
            if (finalText.isNotBlank()) {
                val hasAgentMsg = repo.state(key)?.items?.lastOrNull() is TimelineItem.AgentMessage
                if (!hasAgentMsg) {
                    repo.upsertItem(key, TimelineItem.AgentMessage("agent-" + newId(), finalText, streaming = false))
                }
            }
            returnedDiff = res.str("turnDiff")
            if (res.optString("stopReason") == "cancelled") {
                repo.upsertItem(key, TimelineItem.Notice("cancelled-" + newId(), "已中断"))
            }
        } catch (e: Exception) {
            val errMsg = e.message ?: "${kind.label} 执行失败"
            val lastNotice = repo.state(key)?.items?.lastOrNull() as? TimelineItem.Notice
            if (lastNotice == null || !lastNotice.error || lastNotice.text != errMsg) {
                repo.upsertItem(key, TimelineItem.Notice("error-" + newId(), errMsg, error = true))
            }
            throw e
        } finally {
            hub.finishTurn(key, returnedDiff)
        }
    }

    override suspend fun interrupt(key: ConversationKey) {
        runtime.hubRequest("sessions/cancel", jsonOf("agentId" to kind.id, "sessionId" to key.id))
    }

    /** 截断持久化消息记录至指定用户消息之前，并重置远端驱动会话以在下次发送时桥接剩余上下文。 */
    suspend fun revertBeforeTurn(key: ConversationKey, userMessageId: String, userTurnIndex: Int) {
        hub.clearTurnStream(key)
        runtime.hubRequest(
            "sessions/revertBefore",
            jsonOf(
                "agentId" to kind.id,
                "sessionId" to key.id,
                "messageId" to userMessageId,
                "userTurnIndex" to userTurnIndex,
            ),
        )
    }

    override suspend fun rename(key: ConversationKey, title: String) {
        runtime.hubRequest("sessions/rename", jsonOf("agentId" to kind.id, "sessionId" to key.id, "title" to title))
        repo.update(key) { it.copy(summary = it.summary.copy(title = title)) }
    }

    override suspend fun delete(key: ConversationKey) {
        try {
            runtime.hubRequest("sessions/delete", jsonOf("agentId" to kind.id, "sessionId" to key.id))
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // 兼容旧版守护进程或已移除插件残留会话：后端已不存在或未注册时允许本地彻底清理，不卡死删除动作
            val ignorable = "会话不存在" in msg || "未注册的 Agent" in msg || "已卸载" in msg || "ENOENT" in msg
            if (!ignorable) throw e
        }
        repo.remove(key)
    }

    override suspend fun compact(key: ConversationKey) {
        send(key, "/compact")
    }

    override suspend fun startReview(key: ConversationKey, instructions: String?) {
        val prompt = instructions?.takeIf { it.isNotBlank() } ?: "请审查当前工作区 git diff 的未提交改动并指出潜在问题。"
        send(key, prompt)
    }

    override suspend fun commands(key: ConversationKey): List<SlashCommand> = hub.commandsOf(key)

    override suspend fun models(key: ConversationKey?): List<ModelOption> {
        val pid = if (key != null) repo.state(key)?.providerId else settings.state.value.agentProviders[kind.id]
        val p = settings.state.value.provider(pid)
        if (p != null) {
            return p.models.map { ModelOption(it, it, p.name) }
        }
        val fromHub = hub.modelsOf(key)
        if (fromHub.isNotEmpty()) return fromHub
        if (kind.id == "antigravity") {
            val cached = hub.globalModelsOf("antigravity")
            if (cached.isNotEmpty()) return cached
            return runCatching { hub.fetchAgyCatalog() }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    override suspend fun setProvider(key: ConversationKey, providerId: String?, model: String?): ConversationKey {
        check(repo.state(key)?.running != true) { "请先结束当前任务再切换服务商" }
        val targetProvider = providerId?.let {
            val p = settings.state.value.provider(it) ?: error("所选自定义服务商不存在")
            require(p.enabled) { "服务商「${p.name}」已停用，请先启用" }
            p
        }
        if (targetProvider != null) repo.syncProvidersNow()
        val resolvedModel = model?.takeIf { it.isNotBlank() } ?: targetProvider?.models?.firstOrNull()
        runtime.hubRequest("sessions/setProvider", jsonOf("agentId" to kind.id, "sessionId" to key.id,
            "providerId" to (providerId ?: JSONObject.NULL), "model" to (resolvedModel ?: JSONObject.NULL)))
        hub.clearSessionModelCache(key)
        val nextWin = inferModelContextWindow(resolvedModel)
        repo.update(key) { st ->
            st.copy(
                providerId = providerId,
                model = resolvedModel,
                effort = if (providerId != null) null else st.effort,
                tokenUsage = st.tokenUsage?.let { u -> if (nextWin != null) u.copy(contextWindow = nextWin) else u },
            )
        }
        return key
    }

    override suspend fun setModel(key: ConversationKey, model: String?) {
        val result = runtime.hubRequest("sessions/setModel", jsonOf("agentId" to kind.id, "sessionId" to key.id, "model" to model))
        val effectiveModel = if (result.has("model")) result.str("model") else model
        val nextWin = inferModelContextWindow(effectiveModel)
        repo.update(key) { st ->
            st.copy(
                model = effectiveModel,
                tokenUsage = st.tokenUsage?.let { u -> if (nextWin != null) u.copy(contextWindow = nextWin) else u },
            )
        }
    }

    override suspend fun setEffort(key: ConversationKey, effort: String?) {
        val target = hub.configOptionsOf(key).firstOrNull { opt ->
            val probe = "${opt.category} ${opt.id} ${opt.name}".lowercase()
            "thought" in probe || "thinking" in probe || "effort" in probe || "reasoning" in probe
        }
        val configId = target?.id ?: if (kind.id == "antigravity") "thought_level" else error("当前会话未暴露思考/推理强度配置项")
        val value = effort ?: target?.options?.firstOrNull()?.value ?: "high"
        setConfigOption(key, configId, value)
        repo.update(key) { it.copy(effort = value) }
    }

    suspend fun setConfigOption(key: ConversationKey, configId: String, value: String) {
        val res = runtime.hubRequest("sessions/setConfigOption", jsonOf(
            "agentId" to kind.id,
            "sessionId" to key.id,
            "configId" to configId,
            "value" to value,
        ))
        hub.updateConfigOptions(key, res.optJSONArray("configOptions"))
        if (configId == "thought_level") repo.update(key) { it.copy(effort = value) }
        else if (configId == "model") repo.update(key) { it.copy(model = value) }
        else if (configId == "mode") repo.update(key) { it.copy(permissionMode = value) }
    }

    suspend fun setMode(key: ConversationKey, mode: String) {
        runtime.hubRequest("sessions/setMode", jsonOf("agentId" to kind.id, "sessionId" to key.id, "mode" to mode))
        repo.update(key) { it.copy(permissionMode = mode) }
    }

    override fun onEvent(event: EngineEvent) { /* 外部 Agent 事件由 AgentHubController 经 runtime.hubEvents 统一分发 */ }

    override fun resolve(request: PendingRequest, decision: Decision) {
        when (request) {
            is PendingRequest.UserInput -> {
                if (decision is Decision.Answers) {
                    val answersObj = JSONObject()
                    decision.values.forEach { (qId, vals) ->
                        answersObj.put(qId, if (vals.size == 1) vals.first() else JSONArray(vals))
                    }
                    runtime.hubRespond(request.requestId, jsonOf("cancelled" to false, "answers" to answersObj))
                } else {
                    runtime.hubRespond(request.requestId, jsonOf("cancelled" to true, "answers" to JSONObject()))
                }
                return
            }
            is PendingRequest.Elicitation -> {
                val res = when (decision) {
                    is Decision.FormSubmit -> jsonOf("action" to "accept", "content" to decision.content)
                    is Decision.Allow, is Decision.AllowForSession, is Decision.AllowWithRule -> jsonOf("action" to "accept", "content" to JSONObject())
                    is Decision.Deny -> jsonOf("action" to if (decision.interrupt) "cancel" else "decline")
                    else -> jsonOf("action" to "decline")
                }
                runtime.hubRespond(request.requestId, res)
                return
            }
            else -> Unit
        }
        val options = (request as? PendingRequest.ToolApproval)?.permissionOptions.orEmpty()
        val selected = when (decision) {
            Decision.Allow -> options.firstOrNull { it.kind == "allow_once" }
                ?: error("该 Agent 未提供单次授权选项，请在应用内选择")
            Decision.AllowForSession, is Decision.AllowWithRule ->
                options.firstOrNull { it.kind == "allow_always" } ?: options.firstOrNull { it.kind == "allow_once" }
                    ?: error("该 Agent 未提供持久授权选项")
            is Decision.Answers -> options.firstOrNull { it.id == decision.values["optionId"]?.singleOrNull() }
                ?: error("审批选项已失效，请重新打开请求")
            is Decision.Deny, is Decision.FormSubmit -> null
        }
        val outcome = jsonOf("outcome" to if (selected == null) jsonOf("outcome" to "cancelled")
            else jsonOf("outcome" to "selected", "optionId" to selected.id))
        runtime.hubRespond(request.requestId, outcome)
    }

    override fun reset() {}
}
