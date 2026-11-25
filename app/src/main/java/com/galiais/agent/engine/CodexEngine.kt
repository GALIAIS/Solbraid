package com.galiais.agent.engine

import com.galiais.agent.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

const val DELEGATE_TO_CLAUDE = "delegate_to_claude"
const val DELEGATE_TO_AGENT = "delegate_to_agent"

/** Codex OAuth / 设备码登录进行中状态。 */
data class CodexLoginFlowState(
    val loginId: String,
    val type: String, // "chatgpt" | "chatgptDeviceCode"
    val authUrl: String? = null,
    val userCode: String? = null,
    val running: Boolean = true,
    val error: String? = null,
)

/** 官方 Codex app-server（v2 协议，codex-cli 0.171.0-alpha.13）适配器。 */
class CodexEngine(private val repo: ConversationRepository) : EngineAdapter {
    override val kind = EngineKind.CODEX
    private val runtime get() = repo.runtime
    private val settings get() = repo.settings.state.value
    /** 当前 app-server 进程中已 resume/start 的线程；运行环境重启后清空。 */
    private val attached = mutableSetOf<String>()
    /** v1 兼容审批（execCommandApproval/applyPatchApproval）需要不同的响应格式。 */
    private val legacyRequests = mutableSetOf<String>()
    private val turnWaiters = mutableMapOf<String, CompletableDeferred<String>>()
    private val submittedOptions = mutableMapOf<String, ConversationOptions>()
    private val billedTotalUsage = mutableMapOf<String, TokenUsage>()
    private val stepStartedAt = mutableMapOf<String, Long>()
    private val stepFirstTokenAt = mutableMapOf<String, Long>()
    private val stepLastTokenAt = mutableMapOf<String, Long>()
    private val accountVersionFlow = MutableStateFlow(0)
    /** 账户事件计数；设置页据此重新读取账户。 */
    val accountVersion: StateFlow<Int> = accountVersionFlow.asStateFlow()
    private val loginFlowMutable = MutableStateFlow<CodexLoginFlowState?>(null)
    val loginFlow: StateFlow<CodexLoginFlowState?> = loginFlowMutable.asStateFlow()

    private fun key(threadId: String) = ConversationKey(EngineKind.CODEX, threadId)
    private suspend fun call(method: String, params: JSONObject? = null) = runtime.requestObject(kind, method, params)

    private fun summary(thread: JSONObject, parent: ConversationKey? = null): ConversationSummary {
        val preview = thread.optString("preview")
        val title = thread.str("name")?.takeIf { it.isNotBlank() }
            ?: preview.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "未命名会话"
        return ConversationSummary(key(thread.getString("id")), title, preview, thread.str("cwd"),
            normalizeTime(thread.optLong("updatedAt", thread.optLong("createdAt"))),
            running = thread.obj("status")?.optString("type") == "active",
            parent = thread.str("parentThreadId")?.let(::key) ?: parent)
    }

    /** 将用户配置的自定义服务商注册到 Codex config.toml（指向本机回环网关，不写入真实 API Key）。 */
    suspend fun syncProviders(providers: List<CustomProvider>) {
        if (providers.isEmpty()) return
        val (port, token) = runtime.gatewayEndpoint ?: error("本地协议网关未就绪")
        val edits = JSONArray()
        for (provider in providers) {
            edits.put(jsonOf(
                "keyPath" to "model_providers.${provider.codexProviderId}",
                "mergeStrategy" to "replace",
                "value" to jsonOf(
                    "name" to provider.name,
                    "base_url" to "http://127.0.0.1:$port/openai-responses/${provider.id}",
                    "wire_api" to "responses",
                    "supports_websockets" to false,
                    "requires_openai_auth" to false,
                    "http_headers" to jsonOf("X-Galiais-Token" to token),
                ),
            ))
        }
        call("config/batchWrite", jsonOf("edits" to edits, "reloadUserConfig" to true))
    }

    // modelProviders 传空数组 = 全部服务商；省略时 Codex 只返回默认服务商的会话，会隐藏自定义服务商（galiais_*）创建的会话。
    override suspend fun list(): List<ConversationSummary> = listThreads(archived = false)

    private suspend fun listThreads(archived: Boolean): List<ConversationSummary> {
        val result = linkedMapOf<ConversationKey, ConversationSummary>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = call("thread/list", jsonOf("limit" to 100, "sortKey" to "updated_at", "archived" to archived,
                "modelProviders" to JSONArray(), "cursor" to cursor))
            page.arr("data").objects().forEach { thread ->
                val item = summary(thread).copy(archived = archived)
                result[item.key] = item
            }
            cursor = page.str("nextCursor")?.takeIf { it.isNotBlank() }
            check(cursor == null || cursors.add(cursor)) { "会话列表返回重复分页标识，已停止加载" }
        } while (cursor != null)
        return result.values.toList()
    }

    private fun effectivePolicy(key: ConversationKey): String =
        repo.state(key)?.approvalPolicy ?: settings.approvalPolicy

    private fun effectiveReviewer(key: ConversationKey): String =
        repo.state(key)?.approvalsReviewer ?: settings.approvalsReviewer

    private fun effectiveSummary(key: ConversationKey): String =
        repo.state(key)?.reasoningSummary ?: settings.reasoningSummary

    private fun normalizeDir(path: String): String = path.trim().trimEnd('/').ifEmpty { "/" }

    private suspend fun attach(key: ConversationKey) {
        if (key.id in attached) return
        val saved = repo.settings.conversationOptions(key)
        val current = repo.state(key)
        if (current?.providerId != null) repo.syncProvidersNow()
        val cwd = current?.summary?.cwd ?: call("thread/read", jsonOf("threadId" to key.id, "includeTurns" to false))
            .getJSONObject("thread").str("cwd")
        val environment = repo.envSsh.effectiveEnvironment(cwd)
        val params = jsonOf(
            "threadId" to key.id,
            "excludeTurns" to true,
            "sandbox" to "danger-full-access",
            "approvalPolicy" to effectivePolicy(key),
            "approvalsReviewer" to effectiveReviewer(key),
            "model" to current?.model,
            "config" to jsonOf("model_reasoning_effort" to current?.effort,
                "model_reasoning_summary" to effectiveSummary(key), "web_search" to current?.webSearch)
                .apply {
                    if (environment.length() > 0) put("shell_environment_policy.set", environment)
                    if (current?.providerId != null) {
                        inferModelContextWindow(current.model)?.let { win ->
                            put("model_context_window", win)
                            put("model_auto_compact_token_limit", (win * 85L) / 100L)
                        }
                    }
                },
        )
        if (settings.delegationEnabled) params.put("dynamicTools", JSONArray().put(delegateTool()).put(delegateToAgentTool()))
        if (saved != null) params.put("serviceTier", current?.serviceTier ?: JSONObject.NULL)
        val resumed = call("thread/resume", params)
        attached += key.id
        val providerId = CustomProvider.fromCodexProviderId(resumed.str("modelProvider"))
        repo.update(key) { state ->
            state.copy(providerId = providerId,
                model = if (state.model == current?.model) resumed.str("model") ?: state.model else state.model,
                effort = if (saved == null && state.effort == current?.effort) resumed.str("reasoningEffort") else state.effort,
                serviceTier = if (saved == null) resumed.str("serviceTier") else state.serviceTier,
                collaborationMode = state.collaborationMode ?: resumed.obj("collaborationMode")?.str("mode"))
        }
        repo.persistOptions(key)
    }

    override suspend fun load(key: ConversationKey) {
        attach(key)
        val thread = call("thread/read", jsonOf("threadId" to key.id, "includeTurns" to true)).getJSONObject("thread")
        val items = mutableListOf<TimelineItem>()
        val durations = mutableMapOf<String, Long>()
        var activeTurn: String? = null
        for (turn in thread.arr("turns").objects()) {
            val tid = turn.str("id")
            val durMs = if (turn.has("durationMs") && !turn.isNull("durationMs")) turn.optLong("durationMs", -1L).takeIf { it >= 0L } else null
            if (tid != null && durMs != null) durations[tid] = durMs
            var attachedDur = false
            val turnItems = turn.arr("items").objects()
            turnItems.forEachIndexed { index, raw ->
                val liveReasoning = turn.optString("status") == "inProgress" && index == turnItems.lastIndex &&
                    raw.optString("type") == "reasoning" && raw.isNull("completedAtMs")
                val mapped = mapItem(raw, live = liveReasoning) ?: return@forEachIndexed
                if (mapped is TimelineItem.UserMessage && tid != null) {
                    val withDur = if (!attachedDur && durMs != null) {
                        attachedDur = true
                        durations[mapped.id] = durMs
                        mapped.copy(turnId = tid, durationMs = durMs)
                    } else mapped.copy(turnId = tid)
                    items += withDur
                } else items += mapped
            }
            turn.obj("error")?.let { items += TimelineItem.Notice("error-${turn.optString("id")}", it.optString("message"), error = true) }
            if (turn.optString("status") == "inProgress") activeTurn = tid
        }
        val providerId = CustomProvider.fromCodexProviderId(thread.str("modelProvider"))
        repo.update(key) { state ->
            state.copy(summary = summary(thread, state.summary.parent), items = items, loaded = true,
                running = activeTurn != null, activeTurnId = activeTurn,
                turnDurations = state.turnDurations + durations,
                providerId = providerId)
        }
    }

    override suspend fun create(cwd: String, options: CreateOptions): ConversationKey {
        val normCwd = normalizeDir(cwd)
        val selectedProviderId = when (options.providerId) {
            null -> settings.codexProviderId
            "" -> null
            else -> options.providerId
        }
        val provider = selectedProviderId?.let { settings.provider(it) ?: error("所选自定义服务商不存在") }
        if (provider != null) repo.syncProvidersNow()
        val resolvedModel = if (provider != null) {
            options.model?.takeIf { it.isNotBlank() }
                ?: (if (settings.codexProviderId == provider.id) settings.codexModel else null)
                ?: provider.models.firstOrNull()
                ?: error("请先为服务商「${provider.name}」添加或填写模型")
        } else {
            options.model?.takeIf { it.isNotBlank() } ?: (if (settings.codexProviderId == null) settings.codexModel else null)
        }
        val capability = modelsForProvider(provider?.id).selectedModel(resolvedModel)
        val effort = settings.codexEffort?.takeIf { it in capability?.reasoningEfforts().orEmpty() }
        val tier = settings.codexServiceTier?.takeIf { value -> capability?.serviceTiers?.any { it.id == value } == true }
        val environment = repo.envSsh.effectiveEnvironment(normCwd)
        val params = jsonOf(
            "cwd" to normCwd,
            "approvalPolicy" to settings.approvalPolicy,
            "approvalsReviewer" to settings.approvalsReviewer,
            "sandbox" to "danger-full-access",
            "modelProvider" to (provider?.codexProviderId ?: "openai"),
            "model" to resolvedModel,
            "config" to jsonOf("model_reasoning_effort" to effort,
                "model_reasoning_summary" to settings.reasoningSummary, "web_search" to settings.codexWebSearch)
                .apply {
                    if (environment.length() > 0) put("shell_environment_policy.set", environment)
                    if (provider != null) {
                        inferModelContextWindow(resolvedModel)?.let { win ->
                            put("model_context_window", win)
                            put("model_auto_compact_token_limit", (win * 85L) / 100L)
                        }
                    }
                },
        ).put("serviceTier", tier ?: JSONObject.NULL)
        if (options.allowDelegation) params.put("dynamicTools", JSONArray().put(delegateTool()).put(delegateToAgentTool()))
        val started = call("thread/start", params)
        val thread = started.getJSONObject("thread")
        val key = key(thread.getString("id"))
        attached += key.id
        repo.update(key) { state ->
            state.copy(summary = summary(thread, options.parent ?: state.summary.parent).copy(title = state.summary.title.takeUnless { it == "新会话" } ?: "新会话"),
                loaded = true, providerId = provider?.id, model = started.str("model") ?: resolvedModel,
                effort = effort,
                approvalPolicy = settings.approvalPolicy,
                approvalsReviewer = settings.approvalsReviewer,
                reasoningSummary = settings.reasoningSummary,
                serviceTier = tier,
                collaborationMode = settings.codexCollaborationMode,
                webSearch = settings.codexWebSearch)
        }
        return key
    }

    private fun delegateTool() = jsonOf(
        "type" to "function", "name" to DELEGATE_TO_CLAUDE,
        "description" to "把一个独立、边界清晰的子任务交给 Claude Code 在同一工作区完成，并返回它的最终回答。需要用户确认后才会执行；不要用于简单问题。",
        "inputSchema" to jsonOf("type" to "object", "additionalProperties" to false, "required" to JSONArray().put("task"),
            "properties" to jsonOf("task" to jsonOf("type" to "string", "description" to "完整、可独立执行的任务描述，包括目标、约束和验收方式"))))

    private fun delegateToAgentTool() = jsonOf(
        "type" to "function", "name" to DELEGATE_TO_AGENT,
        "description" to "把一个独立、边界清晰的子任务交给指定的本地 Agent（如 claude、antigravity）在同一工作区完成并返回结果。需要用户确认后执行。",
        "inputSchema" to jsonOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to JSONArray().put("agent_id").put("task"),
            "properties" to jsonOf(
                "agent_id" to jsonOf("type" to "string", "description" to "目标 Agent 标识（如 claude、antigravity）"),
                "task" to jsonOf("type" to "string", "description" to "完整、可独立执行的任务描述，包括目标、约束和验收方式"),
            ),
        ),
    )

    override suspend fun send(key: ConversationKey, text: String, attachments: List<ImageAttachment>) {
        attach(key)
        val current = repo.state(key)
        val capability = models(key).selectedModel(current?.model)
        val model = current?.model ?: capability?.id ?: error("无法确定模型，请先选择模型")
        val supportedEfforts = capability?.reasoningEfforts().orEmpty()
        val effort = current?.effort ?: capability?.defaultEffort
        require(effort == null || supportedEfforts.isEmpty() || effort in supportedEfforts) { "当前模型不支持所选推理强度，请重新选择模型" }
        require(current?.serviceTier == null || capability == null || capability.serviceTiers.any { it.id == current.serviceTier }) { "当前模型不支持所选服务档位，请恢复默认档位" }
        val clientId = "local-" + newId()
        repo.upsertItem(key, TimelineItem.UserMessage(clientId, text, imageCount = attachments.size))
        repo.update(key) { state ->
            val preview = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: if (attachments.isNotEmpty()) "[图片]" else state.summary.title
            if (state.summary.title in setOf("新会话", "未命名会话")) state.copy(summary = state.summary.copy(title = preview)) else state
        }
        val inputs = JSONArray()
        if (text.isNotBlank()) inputs.put(jsonOf("type" to "text", "text" to text, "text_elements" to JSONArray()))
        for (att in attachments) {
            inputs.put(jsonOf("type" to "image", "url" to "data:${att.mimeType};base64,${att.base64}"))
        }
        try {
            val params = jsonOf(
                "threadId" to key.id,
                "clientUserMessageId" to clientId,
                "model" to model,
                "effort" to effort,
                "summary" to effectiveSummary(key),
                "approvalPolicy" to effectivePolicy(key),
                "approvalsReviewer" to effectiveReviewer(key),
                "sandboxPolicy" to jsonOf("type" to "dangerFullAccess"),
                "input" to inputs,
            ).put("serviceTier", current?.serviceTier ?: JSONObject.NULL)
            current?.collaborationMode?.let { mode ->
                params.put("collaborationMode", jsonOf("mode" to mode,
                    "settings" to jsonOf("model" to model).put("reasoning_effort", effort ?: JSONObject.NULL)
                        .put("developer_instructions", JSONObject.NULL)))
            }
            if (current != null) submittedOptions[key.id] = ConversationOptions.from(current)
            val started = call("turn/start", params)
            val turn = started.optJSONObject("turn")
            val turnId = turn?.str("id")
            if (turnId != null) {
                repo.mutateItem(key, clientId, { null }) { if (it is TimelineItem.UserMessage) it.copy(turnId = turnId) else it }
            }
            repo.update(key) { it.copy(running = true, activeTurnId = turnId ?: it.activeTurnId, turnStartedAt = System.currentTimeMillis()) }
        } catch (error: Exception) {
            repo.upsertItem(key, TimelineItem.Notice("send-error-$clientId", "消息未发送：${error.message}", error = true))
            throw error
        }
    }

    /** 运行中实时插话（官方 Codex turn/steer），不中断当前轮次即可注入新指导。 */
    suspend fun steer(key: ConversationKey, text: String, attachments: List<ImageAttachment> = emptyList()) {
        attach(key)
        val expectedTurnId = repo.state(key)?.activeTurnId ?: error("当前没有正在进行的轮次")
        val clientId = "steer-" + newId()
        val inputs = JSONArray()
        if (text.isNotBlank()) inputs.put(jsonOf("type" to "text", "text" to text, "text_elements" to JSONArray()))
        for (att in attachments) inputs.put(jsonOf("type" to "image", "url" to "data:${att.mimeType};base64,${att.base64}"))
        repo.upsertItem(key, TimelineItem.UserMessage(clientId, "↳ 插话：$text", imageCount = attachments.size, turnId = expectedTurnId))
        call("turn/steer", jsonOf(
            "threadId" to key.id,
            "expectedTurnId" to expectedTurnId,
            "clientUserMessageId" to clientId,
            "input" to inputs,
        ))
    }

    /** 将持久化对话历史回退到指定轮次之前（不撤销本地磁盘改动）。 */
    suspend fun revertBeforeTurn(key: ConversationKey, beforeTurnId: String): ConversationSummary {
        attach(key)
        val result = call("thread/revert", jsonOf("threadId" to key.id, "beforeTurnId" to beforeTurnId))
        return summary(result.getJSONObject("thread"), repo.state(key)?.summary?.parent)
    }

    suspend fun archive(key: ConversationKey) {
        call("thread/archive", jsonOf("threadId" to key.id))
        attached -= key.id
    }

    suspend fun unarchive(key: ConversationKey) {
        call("thread/unarchive", jsonOf("threadId" to key.id))
    }

    suspend fun listArchived(): List<ConversationSummary> = listThreads(archived = true)

    /** 委派用：发送任务并等待这一轮结束，返回最后一条助手回答。 */
    suspend fun runTask(key: ConversationKey, text: String): String {
        val waiter = CompletableDeferred<String>()
        turnWaiters[key.id] = waiter
        try { send(key, text); return waiter.await() } finally { turnWaiters.remove(key.id, waiter) }
    }

    override suspend fun interrupt(key: ConversationKey) {
        var turnId = repo.state(key)?.activeTurnId
        if (turnId == null) {
            // UI 重启/后台重连后可能尚未加载时间线，主动探测进行中的 turnId 再发起中断
            turnId = runCatching {
                val turns = call("thread/read", jsonOf("threadId" to key.id, "includeTurns" to true))
                    .optJSONObject("thread")?.arr("turns")?.objects().orEmpty()
                turns.lastOrNull { it.optString("status") == "inProgress" }?.str("id")
            }.getOrNull()
        }
        if (turnId == null) return
        call("turn/interrupt", jsonOf("threadId" to key.id, "turnId" to turnId))
    }

    override suspend fun rename(key: ConversationKey, title: String) { call("thread/name/set", jsonOf("threadId" to key.id, "name" to title)) }

    override suspend fun delete(key: ConversationKey) {
        try {
            call("thread/delete", jsonOf("threadId" to key.id))
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // 新建未发消息或已被远端清理的空线程容错放行，不阻塞本地列表删除
            val ignorable = msg.contains("not found", ignoreCase = true) ||
                msg.contains("no rollout", ignoreCase = true) ||
                msg.contains("ENOENT", ignoreCase = true)
            if (!ignorable) throw e
        }
        attached -= key.id
    }

    override suspend fun compact(key: ConversationKey) {
        attach(key)
        repo.update(key) { it.copy(running = true, turnStartedAt = System.currentTimeMillis()) }
        try {
            call("thread/compact/start", jsonOf("threadId" to key.id))
        } catch (error: Exception) {
            repo.update(key) { it.copy(running = false, turnStartedAt = null) }
            throw error
        }
    }

    override suspend fun fork(key: ConversationKey): ConversationKey {
        attach(key)
        val current = repo.state(key)
        val provider = current?.providerId?.let { settings.provider(it) }
        val environment = repo.envSsh.effectiveEnvironment(current?.summary?.cwd)
        val params = jsonOf(
            "threadId" to key.id,
            "modelProvider" to (provider?.codexProviderId ?: "openai"),
            "model" to current?.model,
            "sandbox" to "danger-full-access",
            "approvalPolicy" to effectivePolicy(key), "approvalsReviewer" to effectiveReviewer(key),
            "config" to jsonOf("model_reasoning_effort" to current?.effort,
                "model_reasoning_summary" to effectiveSummary(key), "web_search" to current?.webSearch)
                .apply {
                    if (environment.length() > 0) put("shell_environment_policy.set", environment)
                    if (current?.providerId != null) {
                        inferModelContextWindow(current.model)?.let { win ->
                            put("model_context_window", win)
                            put("model_auto_compact_token_limit", (win * 85L) / 100L)
                        }
                    }
                },
        ).put("serviceTier", current?.serviceTier ?: JSONObject.NULL)
        if (settings.delegationEnabled) params.put("dynamicTools", JSONArray().put(delegateTool()).put(delegateToAgentTool()))
        val forked = call("thread/fork", params)
        val thread = forked.getJSONObject("thread")
        val newKey = key(thread.getString("id"))
        attached += newKey.id
        val providerId = CustomProvider.fromCodexProviderId(forked.str("modelProvider")) ?: current?.providerId
        repo.update(newKey) { state ->
            state.copy(
                summary = summary(thread, key),
                loaded = false,
                providerId = providerId,
                model = forked.str("model") ?: current?.model,
                effort = current?.effort,
                approvalPolicy = current?.approvalPolicy,
                approvalsReviewer = current?.approvalsReviewer,
                reasoningSummary = current?.reasoningSummary,
                serviceTier = current?.serviceTier,
                collaborationMode = current?.collaborationMode,
                webSearch = current?.webSearch,
            )
        }
        return newKey
    }

    override suspend fun startReview(key: ConversationKey, instructions: String?) {
        attach(key)
        val target = if (instructions.isNullOrBlank()) jsonOf("type" to "uncommittedChanges")
        else jsonOf("type" to "custom", "instructions" to instructions.trim())
        call("review/start", jsonOf("threadId" to key.id, "target" to target, "delivery" to "inline"))
        repo.update(key) { it.copy(running = true) }
    }

    suspend fun mcpServers(): List<McpServerSummary> =
        call("mcpServerStatus/list", jsonOf("limit" to 50)).arr("data").objects().map { server ->
            val tools = server.optJSONObject("tools")?.length() ?: 0
            val runtimeStatus = server.opt("runtimeStatus")?.let {
                when (it) {
                    is JSONObject -> it.optString("type", it.toString())
                    is String -> it
                    else -> null
                }
            } ?: server.str("authStatus") ?: "已配置"
            McpServerSummary(server.optString("name"), runtimeStatus, tools, server.str("httpOrigin"))
        }

    suspend fun reloadMcpServers() {
        call("config/mcpServer/reload", null)
    }

    suspend fun skills(cwd: String? = null): List<SkillSummary> {
        val params = if (cwd != null) jsonOf("cwds" to JSONArray().put(cwd)) else jsonOf()
        return call("skills/list", params).arr("data").objects().flatMap { entry ->
            entry.arr("skills").objects().map { s ->
                SkillSummary(
                    name = s.optString("name"),
                    description = s.str("shortDescription") ?: s.optString("description"),
                    path = s.str("path"),
                    enabled = s.optBoolean("enabled", true),
                )
            }
        }
    }

    suspend fun setSkillEnabled(skill: SkillSummary, enabled: Boolean) {
        val params = jsonOf("enabled" to enabled)
        if (!skill.path.isNullOrBlank()) params.put("path", skill.path) else params.put("name", skill.name)
        call("skills/config/write", params)
    }

    private val portableFeatures = setOf("code_mode", "multi_agent", "js_repl", "memories", "undo", "shell_snapshot", "unified_exec")

    suspend fun experimentalFeatures(): List<ExperimentalFeatureItem> {
        val result = mutableListOf<ExperimentalFeatureItem>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = call("experimentalFeature/list", jsonOf("limit" to 100, "cursor" to cursor))
            page.arr("data").objects().filter { it.str("stage") == "beta" && it.str("name") in portableFeatures && !it.str("displayName").isNullOrBlank() }
                .forEach { f -> result += ExperimentalFeatureItem(f.getString("name"), f.getString("displayName"),
                    f.str("description") ?: "", f.optBoolean("enabled"), f.getString("stage")) }
            cursor = page.str("nextCursor")?.takeIf { it.isNotBlank() }
            check(cursor == null || cursors.add(cursor)) { "功能列表返回重复分页标识，已停止加载" }
        } while (cursor != null)
        return result
    }

    suspend fun setExperimentalFeature(name: String, enabled: Boolean) {
        require(name in portableFeatures) { "该功能没有本地执行组件" }
        call("experimentalFeature/enablement/set", jsonOf("enablement" to jsonOf(name to enabled)))
    }

    suspend fun rateLimits(): List<RateLimitSummary> {
        val res = call("account/rateLimits/read", null)
        val rl = res.optJSONObject("rateLimits") ?: return emptyList()
        val list = mutableListOf<RateLimitSummary>()
        fun parseWindow(obj: JSONObject?, label: String) {
            if (obj == null) return
            val pct = obj.optInt("usedPercent", -1)
            if (pct >= 0) {
                val resets = obj.optLong("resetsAt", 0L).takeIf { it > 0L }?.let { ts ->
                    val ms = normalizeTime(ts)
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
                }
                list += RateLimitSummary(label, pct.coerceIn(0, 100), resets)
            }
        }
        parseWindow(rl.optJSONObject("primary"), "短期配额")
        parseWindow(rl.optJSONObject("secondary"), "周配额")
        return list
    }

    override suspend fun models(key: ConversationKey?): List<ModelOption> {
        return modelsForProvider(if (key == null) settings.codexProviderId else repo.state(key)?.providerId)
    }

    suspend fun modelsForProvider(providerId: String?): List<ModelOption> {
        val provider = providerId?.let { settings.provider(it) ?: error("所选服务商不存在") }
        val result = linkedMapOf<String, ModelOption>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = call("model/list", jsonOf("modelProvider" to (provider?.codexProviderId ?: "openai"),
                "limit" to 100, "cursor" to cursor, "includeHidden" to false))
            page.arr("data").objects().filterNot { it.optBoolean("hidden") }.forEach {
                val id = it.str("model") ?: it.getString("id")
                result[id] = ModelOption(id, it.str("displayName") ?: id, it.optString("description"),
                    it.optBoolean("isDefault"), it.arr("supportedReasoningEfforts").objects().mapNotNull { option -> option.str("reasoningEffort") },
                    it.str("defaultReasoningEffort"),
                    serviceTiers = it.arr("serviceTiers").objects().map { tier ->
                        ServiceTierOption(tier.getString("id"), tier.getString("name"), tier.str("description") ?: "")
                    }, defaultServiceTier = it.str("defaultServiceTier"))
            }
            cursor = page.str("nextCursor")?.takeIf { it.isNotBlank() }
            check(cursor == null || cursors.add(cursor)) { "模型列表返回重复分页标识，已停止加载" }
        } while (cursor != null)
        provider?.models?.forEach { id -> result.putIfAbsent(id, ModelOption(id, id, provider.name)) }
        return result.values.toList()
    }

    override suspend fun setProvider(key: ConversationKey, providerId: String?, model: String?): ConversationKey {
        val current = repo.state(key) ?: error("会话不存在")
        check(!current.running && !current.reverting) { "请先结束当前任务再切换服务商" }
        val targetProvider = providerId?.let {
            val p = settings.provider(it) ?: error("所选自定义服务商不存在")
            require(p.enabled) { "服务商「${p.name}」已停用，请先启用" }
            p
        }
        if (current.providerId == providerId && (model == null || current.model == model)) return key
        if (targetProvider != null) repo.syncProvidersNow()
        val resolvedModel = model?.takeIf { it.isNotBlank() }
            ?: targetProvider?.models?.firstOrNull()
            ?: runCatching { modelsForProvider(null).firstOrNull { it.isDefault }?.id }.getOrNull()
        val cwd = current.summary.cwd ?: settings.defaultCwd
        val environment = repo.envSsh.effectiveEnvironment(cwd)
        val configObj = jsonOf(
            "model_reasoning_effort" to null,
            "model_reasoning_summary" to effectiveSummary(key),
            "web_search" to current.webSearch,
        ).apply {
            if (environment.length() > 0) put("shell_environment_policy.set", environment)
            if (targetProvider != null) {
                inferModelContextWindow(resolvedModel)?.let { win ->
                    put("model_context_window", win)
                    put("model_auto_compact_token_limit", (win * 85L) / 100L)
                }
            }
        }
        val targetCodexProvider = targetProvider?.codexProviderId ?: "openai"
        val origTitle = current.summary.title.takeUnless { it in setOf("新会话", "未命名会话") }
        val nextWin = inferModelContextWindow(resolvedModel)

        // 有历史记录的 Codex 线程通过原生 thread/fork(modelProvider) 完整保留上下文并切换服务商；空白线程直接重建。
        val forkedResult = if (current.items.isNotEmpty()) {
            try {
                attach(key)
                val forkParams = jsonOf(
                    "threadId" to key.id,
                    "modelProvider" to targetCodexProvider,
                    "model" to resolvedModel,
                    "sandbox" to "danger-full-access",
                    "approvalPolicy" to effectivePolicy(key),
                    "approvalsReviewer" to effectiveReviewer(key),
                    "config" to configObj,
                ).put("serviceTier", JSONObject.NULL)
                if (settings.delegationEnabled) forkParams.put("dynamicTools", JSONArray().put(delegateTool()).put(delegateToAgentTool()))
                call("thread/fork", forkParams)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("no rollout", ignoreCase = true) || msg.contains("not found", ignoreCase = true)) null
                else throw e
            }
        } else null

        if (forkedResult != null) {
            val thread = forkedResult.getJSONObject("thread")
            val newKey = key(thread.getString("id"))
            attached += newKey.id
            val actualModel = forkedResult.str("model") ?: resolvedModel
            val actualProvId = CustomProvider.fromCodexProviderId(forkedResult.str("modelProvider")) ?: providerId
            if (!origTitle.isNullOrBlank()) {
                runCatching { call("thread/name/set", jsonOf("threadId" to newKey.id, "name" to origTitle)) }
            }
            repo.update(newKey) { state ->
                state.copy(
                    summary = summary(thread, current.summary.parent).copy(
                        title = origTitle ?: current.summary.title,
                        preview = current.summary.preview,
                        cwd = cwd,
                        updatedAt = System.currentTimeMillis(),
                    ),
                    items = current.items,
                    loaded = true,
                    running = false,
                    providerId = actualProvId,
                    model = actualModel,
                    effort = null,
                    approvalPolicy = current.approvalPolicy,
                    approvalsReviewer = current.approvalsReviewer,
                    reasoningSummary = current.reasoningSummary,
                    serviceTier = null,
                    collaborationMode = current.collaborationMode,
                    webSearch = current.webSearch,
                    turnDurations = current.turnDurations,
                    turnDiff = current.turnDiff,
                    tokenUsage = current.tokenUsage?.let { u ->
                        val win = nextWin ?: u.contextWindow
                        if (win != null) u.copy(contextWindow = win) else u
                    },
                    plan = current.plan,
                    planExplanation = current.planExplanation,
                    arenaGroupId = current.arenaGroupId,
                )
            }
            // 将切换前的旧线程归档（不丢历史且列表不重复）
            runCatching { archive(key) }
            attached -= key.id
            submittedOptions.remove(key.id)
            billedTotalUsage.remove(key.id)
            repo.settings.removeConversationOptions(key)
            repo.remove(key)
            return newKey
        }

        // 尚未落盘 rollout 的空白会话：以新服务商直接重建并清理原空白占位线程
        val startParams = jsonOf(
            "cwd" to cwd,
            "approvalPolicy" to effectivePolicy(key),
            "approvalsReviewer" to effectiveReviewer(key),
            "sandbox" to "danger-full-access",
            "modelProvider" to targetCodexProvider,
            "model" to resolvedModel,
            "config" to configObj,
        ).put("serviceTier", JSONObject.NULL)
        if (settings.delegationEnabled) startParams.put("dynamicTools", JSONArray().put(delegateTool()).put(delegateToAgentTool()))
        val started = call("thread/start", startParams)
        val thread = started.getJSONObject("thread")
        val newKey = key(thread.getString("id"))
        attached += newKey.id
        if (!origTitle.isNullOrBlank()) {
            runCatching { call("thread/name/set", jsonOf("threadId" to newKey.id, "name" to origTitle)) }
        }
        repo.update(newKey) { state ->
            state.copy(
                summary = summary(thread, current.summary.parent).copy(
                    title = origTitle ?: current.summary.title,
                    cwd = cwd,
                ),
                items = current.items,
                loaded = true,
                providerId = providerId,
                model = started.str("model") ?: resolvedModel,
                effort = null,
                approvalPolicy = current.approvalPolicy,
                approvalsReviewer = current.approvalsReviewer,
                reasoningSummary = current.reasoningSummary,
                serviceTier = null,
                collaborationMode = current.collaborationMode,
                webSearch = current.webSearch,
                arenaGroupId = current.arenaGroupId,
            )
        }
        runCatching { call("thread/unsubscribe", jsonOf("threadId" to key.id)) }
        runCatching { call("thread/delete", jsonOf("threadId" to key.id)) }
        attached -= key.id
        submittedOptions.remove(key.id)
        billedTotalUsage.remove(key.id)
        repo.settings.removeConversationOptions(key)
        repo.remove(key)
        return newKey
    }

    override suspend fun setModel(key: ConversationKey, model: String?) {
        val selected = model ?: models(key).firstOrNull { it.isDefault }?.id ?: error("未取得默认模型，请选择具体模型")
        repo.update(key) { st ->
            val nextWin = inferModelContextWindow(selected)
            st.copy(
                model = selected,
                effort = null,
                serviceTier = null,
                tokenUsage = st.tokenUsage?.let { u -> if (nextWin != null) u.copy(contextWindow = nextWin) else u },
            )
        }
    }

    override suspend fun setEffort(key: ConversationKey, effort: String?) {
        require(effort == null || effort in models(key).selectedModel(repo.state(key)?.model)?.reasoningEfforts().orEmpty()) { "当前模型不支持所选推理强度" }
        repo.update(key) { it.copy(effort = effort) }
    }

    suspend fun account(): JSONObject = call("account/read", jsonOf("refreshToken" to false))
    suspend fun loginWithApiKey(apiKey: String) {
        require(apiKey.isNotBlank() && !apiKey.contains('\n')) { "API Key 格式无效" }
        call("account/login/start", jsonOf("type" to "apiKey", "apiKey" to apiKey.trim()))
        loginFlowMutable.value = null
        accountVersionFlow.value++
    }
    /** 启动官方 ChatGPT 浏览器 OAuth 登录（返回 authUrl + loginId）。 */
    suspend fun startChatGptOAuthLogin(): CodexLoginFlowState {
        val res = call("account/login/start", jsonOf("type" to "chatgpt"))
        val state = CodexLoginFlowState(
            loginId = res.str("loginId") ?: "",
            type = "chatgpt",
            authUrl = res.str("authUrl") ?: res.str("url"),
            running = true,
        )
        loginFlowMutable.value = state
        return state
    }
    /** 启动官方 ChatGPT 设备码登录（推荐手机端，不依赖 localhost 回调）。 */
    suspend fun startDeviceCodeLogin(): CodexLoginFlowState {
        val res = call("account/login/start", jsonOf("type" to "chatgptDeviceCode"))
        val state = CodexLoginFlowState(
            loginId = res.str("loginId") ?: "",
            type = "chatgptDeviceCode",
            authUrl = res.str("verificationUrl") ?: res.str("verificationUri") ?: res.str("authUrl") ?: "https://auth.openai.com/codex/device",
            userCode = res.str("userCode") ?: res.str("user_code") ?: res.str("deviceCode"),
            running = true,
        )
        loginFlowMutable.value = state
        return state
    }
    suspend fun cancelLogin() {
        val current = loginFlowMutable.value
        if (current != null && current.loginId.isNotBlank()) {
            runCatching { call("account/login/cancel", jsonOf("loginId" to current.loginId)) }
        }
        loginFlowMutable.value = null
    }
    suspend fun startMcpOAuthLogin(serverName: String): String? {
        val res = call("mcpServer/oauth/login", jsonOf("name" to serverName))
        return res.str("authorizationUrl") ?: res.str("authUrl") ?: res.str("url")
    }
    suspend fun logout() {
        call("account/logout")
        loginFlowMutable.value = null
        accountVersionFlow.value++
    }

    private fun status(value: String?) = when (value) {
        "completed" -> ItemStatus.COMPLETED; "failed" -> ItemStatus.FAILED; "declined" -> ItemStatus.DECLINED
        else -> ItemStatus.RUNNING
    }

    private fun summarizeCommandActions(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val labels = arr.objects().mapNotNull { a ->
            when (a.optString("type")) {
                "read" -> "读取 " + (a.str("name") ?: a.str("path")?.substringAfterLast('/') ?: "文件")
                "listFiles" -> "列出目录" + (a.str("path")?.let { " ${it.substringAfterLast('/')}" } ?: "")
                "search" -> "搜索" + (a.str("query")?.let { " \"$it\"" } ?: "")
                else -> null
            }
        }.distinct()
        return labels.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun mapItem(item: JSONObject, live: Boolean): TimelineItem? {
        val id = item.optString("id")
        return when (item.optString("type")) {
            "userMessage" -> {
                val text = item.arr("content").objects().joinToString("\n") { input ->
                    when (input.optString("type")) {
                        "text" -> input.optString("text")
                        "mention", "skill" -> "@" + input.optString("name")
                        "image", "localImage" -> "[图片]"
                        else -> "[${input.optString("type")}]"
                    }
                }
                TimelineItem.UserMessage(item.str("clientId") ?: id, text)
            }
            "agentMessage" -> TimelineItem.AgentMessage(
                id = id,
                text = item.optString("text"),
                streaming = live && item.optString("text").isEmpty(),
                phase = item.str("phase"),
            )
            "reasoning" -> {
                val parts = item.arr("summary").strings().withIndex().associate { it.index to it.value }
                TimelineItem.Reasoning(id, "", streaming = live, summaryParts = parts,
                    startedAtMs = item.optLong("startedAtMs", 0L).takeIf { it > 0L },
                    completedAtMs = item.optLong("completedAtMs", 0L).takeIf { it > 0L })
            }
            "plan" -> {
                val txt = item.optString("text")
                TimelineItem.Plan(
                    id = id,
                    text = txt,
                    title = extractPlanTitle(txt),
                    streaming = live,
                    status = if (live) ItemStatus.RUNNING else ItemStatus.COMPLETED,
                )
            }
            "commandExecution" -> TimelineItem.CommandRun(
                id = id,
                command = item.optString("command"),
                cwd = item.str("cwd"),
                output = item.str("aggregatedOutput") ?: "",
                exitCode = if (item.isNull("exitCode") || !item.has("exitCode")) null else item.optInt("exitCode"),
                status = status(item.str("status")),
                actionSummary = summarizeCommandActions(item.optJSONArray("commandActions")),
                durationMs = if (item.has("durationMs") && !item.isNull("durationMs")) item.optLong("durationMs", -1L).takeIf { it >= 0L } else null,
            )
            "fileChange" -> TimelineItem.FileEdit(id, item.arr("changes").objects().map {
                FileDiff(it.optString("path"), it.obj("kind")?.optString("type") ?: "update", it.optString("diff"))
            }, status(item.str("status")))
            "mcpToolCall" -> TimelineItem.ToolCall(id, item.optString("server") + "." + item.optString("tool"), item.opt("arguments").pretty(),
                item.opt("result")?.takeUnless { it == JSONObject.NULL }?.pretty() ?: item.opt("error")?.takeUnless { it == JSONObject.NULL }?.pretty(),
                status(item.str("status")))
            "dynamicToolCall" -> if (item.optString("tool") in setOf(DELEGATE_TO_CLAUDE, DELEGATE_TO_AGENT)) {
                val targetId = item.obj("arguments")?.str("agent_id")?.takeIf { it.isNotBlank() } ?: "claude"
                if (live) null else TimelineItem.Delegation(id, EngineKind.of(targetId),
                    item.obj("arguments")?.str("task") ?: item.opt("arguments").pretty(1000), null,
                    item.optJSONArray("contentItems")?.objects()?.joinToString("\n") { it.optString("text") },
                    if (item.has("success") && !item.isNull("success")) if (item.optBoolean("success")) ItemStatus.COMPLETED else ItemStatus.FAILED else status(item.str("status")))
            } else TimelineItem.ToolCall(id, item.optString("tool"),
                item.opt("arguments").pretty(), item.optJSONArray("contentItems")?.objects()?.joinToString("\n") { it.optString("text") }, status(item.str("status")))
            "webSearch" -> TimelineItem.ToolCall(id, "网页搜索", item.optString("query"), null, ItemStatus.COMPLETED)
            "collabAgentToolCall" -> TimelineItem.ToolCall(id, "子代理 " + item.optString("tool"), item.optString("prompt"), null, status(item.str("status")))
            "imageView" -> TimelineItem.Notice(id, "查看图片 " + item.optString("path"))
            "imageGeneration" -> {
                val prompt = item.str("revisedPrompt") ?: item.str("prompt") ?: "图像生成"
                val outPath = item.str("path") ?: item.str("outputPath") ?: item.str("result")
                TimelineItem.ToolCall(id, "图像生成", prompt, outPath, status(item.str("status")))
            }
            "contextCompaction" -> TimelineItem.Notice(id, "上下文已压缩")
            "enteredReviewMode" -> TimelineItem.Notice(id, "进入代码审查：" + item.optString("review"))
            "exitedReviewMode" -> TimelineItem.Notice(id, "代码审查结束")
            else -> null
        }
    }

    private fun appendText(key: ConversationKey, id: String, delta: String, deltaKind: String, create: () -> TimelineItem) {
        repo.appendStreamingDelta(key, id, delta, deltaKind, create = create)
    }

    private fun finishReasoning(key: ConversationKey, exceptId: String? = null, endedAt: Long = System.currentTimeMillis()) {
        repo.flushStreamingDeltas(key)
        if (repo.state(key)?.items?.any { it is TimelineItem.Reasoning && it.summaryParts != null && it.streaming && it.id != exceptId } != true) return
        repo.update(key) { state -> state.copy(items = state.items.map { item ->
            if (item is TimelineItem.Reasoning && item.summaryParts != null && item.streaming && item.id != exceptId)
                item.copy(streaming = false, completedAtMs = item.completedAtMs ?: endedAt)
            else item
        }) }
    }

    private fun appendReasoningSummary(key: ConversationKey, params: JSONObject) {
        val id = params.getString("itemId")
        val rawIndex = params.get("summaryIndex")
        require(rawIndex is Number && rawIndex.toDouble().isFinite() && rawIndex.toDouble() == rawIndex.toLong().toDouble() && rawIndex.toLong() in 0..Int.MAX_VALUE.toLong()) { "推理摘要分段编号无效" }
        val index = rawIndex.toInt()
        val delta = if (params.has("delta")) params.getString("delta") else ""
        if (delta.isEmpty()) {
            repo.mutateItem(key, id, { TimelineItem.Reasoning(id, "", true, emptyMap(), System.currentTimeMillis()) }) { item ->
                if (item !is TimelineItem.Reasoning) item
                else {
                    val parts = item.summaryParts.orEmpty()
                    item.copy(summaryParts = parts + (index to parts[index].orEmpty()))
                }
            }
        } else {
            repo.appendStreamingDelta(key, id, delta, "reasoning_part", summaryIndex = index) {
                TimelineItem.Reasoning(id, "", true, emptyMap(), System.currentTimeMillis())
            }
        }
    }

    override fun onEvent(event: EngineEvent) {
        if (event.isRequest) { onRequest(event); return }
        val params = event.params
        val threadKey = params.str("threadId")?.let(::key)
        when (event.method) {
            "thread/started" -> params.obj("thread")?.let { thread ->
                val key = key(thread.getString("id"))
                repo.update(key) { it.copy(summary = summary(thread, it.summary.parent).let { s -> if (it.summary.title != "新会话" && thread.str("name") == null) s.copy(title = it.summary.title) else s }) }
            }
            "thread/status/changed" -> threadKey?.let { key ->
                val active = params.obj("status")?.optString("type") == "active"
                repo.update(key) { it.copy(running = active, activeTurnId = if (active) it.activeTurnId else null) }
            }
            "thread/name/updated" -> threadKey?.let { key -> params.str("threadName")?.let { name -> repo.update(key) { it.copy(summary = it.summary.copy(title = name)) } } }
            "thread/settings/updated" -> threadKey?.let { key ->
                val updated = params.obj("threadSettings") ?: return@let
                val sent = submittedOptions[key.id] ?: return@let
                // 服务端可能在下一轮选项已修改后回报旧值，只同步仍与发送时一致的工作模式。
                val mode = updated.obj("collaborationMode")?.str("mode")?.takeIf { it in CODEX_COLLABORATION_MODES }
                if (mode != null && repo.state(key)?.collaborationMode == sent.collaborationMode) {
                    repo.update(key) { it.copy(collaborationMode = mode) }
                    submittedOptions[key.id] = sent.copy(collaborationMode = mode)
                    repo.persistOptions(key)
                }
            }
            "thread/deleted" -> threadKey?.let { key ->
                repo.settings.removeConversationOptions(key)
                submittedOptions.remove(key.id)
                billedTotalUsage.remove(key.id)
                stepStartedAt.remove(key.id); stepFirstTokenAt.remove(key.id); stepLastTokenAt.remove(key.id)
                repo.remove(key)
            }
            "thread/archived" -> threadKey?.let(repo::remove)
            "thread/unarchived" -> repo.refresh()
            "thread/reverted" -> threadKey?.let { key ->
                if (repo.state(key)?.reverting != true) {
                    repo.launchOperation("读取回退后的会话失败") { load(key) }
                }
            }
            "turn/started" -> threadKey?.let { key ->
                val now = System.currentTimeMillis()
                stepStartedAt[key.id] = now
                stepFirstTokenAt.remove(key.id)
                stepLastTokenAt.remove(key.id)
                repo.update(key) { it.copy(running = true, activeTurnId = params.obj("turn")?.optString("id"), turnStartedAt = now, turnDiff = null) }
            }
            "turn/diff/updated" -> threadKey?.let { key -> repo.update(key) { it.copy(turnDiff = params.str("diff")?.takeIf { d -> d.isNotBlank() }) } }
            "turn/plan/updated" -> threadKey?.let { key ->
                val steps = params.arr("plan").objects().mapNotNull {
                    val label = it.optString("step").ifBlank { it.optString("content").ifBlank { it.optString("title") } }
                    if (label.isBlank()) null
                    else PlanStep(
                        step = label,
                        status = normalizePlanStepStatus(it.optString("status", "pending")),
                        id = it.str("id"),
                        activeForm = it.str("activeForm"),
                        priority = it.str("priority"),
                    )
                }
                repo.update(key) { it.copy(plan = steps, planExplanation = params.str("explanation") ?: if (steps.isEmpty()) null else it.planExplanation) }
            }
            "thread/tokenUsage/updated" -> threadKey?.let { key ->
                val usage = params.obj("tokenUsage") ?: return
                // Codex 下发 `last`（最近一次模型请求的真实上下文窗口占用）与 `total`（整条会话累计账单总和）。
                // 上下文环必须使用 `last`，否则多轮工具调用累计很快超过窗口却不会触发自动压缩；计费增量继续使用单调递增的 `total`。
                val last = usage.obj("last")
                val total = usage.obj("total")
                val ctx = last ?: total ?: return
                val billing = total ?: ctx
                val reportedWindow = if (usage.isNull("modelContextWindow") || !usage.has("modelContextWindow")) null
                    else usage.optLong("modelContextWindow").takeIf { it > 0L }
                val window = reportedWindow ?: repo.state(key)?.tokenUsage?.contextWindow ?: inferModelContextWindow(repo.state(key)?.model)
                val ctxIn = ctx.optLong("inputTokens")
                val ctxOut = ctx.optLong("outputTokens")
                val ctxCache = ctx.optLong("cachedInputTokens")
                val ctxReason = ctx.optLong("reasoningOutputTokens")
                val ctxTotal = ctx.optLong("totalTokens").takeIf { it > 0L } ?: (ctxIn + ctxOut)
                val sessionTotal = billing.optLong("totalTokens").takeIf { it > 0L }
                    ?: (billing.optLong("inputTokens") + billing.optLong("outputTokens")).takeIf { it > 0L }
                val snapshot = TokenUsage(
                    totalTokens = ctxTotal,
                    inputTokens = ctxIn,
                    cachedInputTokens = ctxCache,
                    outputTokens = ctxOut,
                    reasoningTokens = ctxReason,
                    contextWindow = window,
                    sessionTotalTokens = sessionTotal,
                )
                val billSnapshot = TokenUsage(
                    totalTokens = sessionTotal ?: 0L,
                    inputTokens = billing.optLong("inputTokens"),
                    cachedInputTokens = billing.optLong("cachedInputTokens"),
                    outputTokens = billing.optLong("outputTokens"),
                    reasoningTokens = billing.optLong("reasoningOutputTokens"),
                    contextWindow = window,
                )
                val prevBilled = billedTotalUsage[key.id]
                val wasRunning = repo.state(key)?.running == true
                repo.update(key) { it.copy(tokenUsage = snapshot) }
                // 仅在会话活跃运行且累计 total 严格递增时按增量入账，杜绝重连重放与压缩后回落干扰计费
                if (repo.state(key)?.providerId == null && wasRunning) {
                    val deltaIn: Long
                    val deltaOut: Long
                    val deltaCache: Long
                    val deltaReason: Long
                    if (prevBilled == null && last != null) {
                        // 应用冷启动后恢复历史线程若未提前收到基线事件，直接采用本步 last 避免把整条历史会话累计值重复入账
                        deltaIn = ctxIn.coerceAtLeast(0L)
                        deltaOut = ctxOut.coerceAtLeast(0L)
                        deltaCache = ctxCache.coerceAtLeast(0L)
                        deltaReason = ctxReason.coerceAtLeast(0L)
                    } else {
                        val base = prevBilled ?: TokenUsage(0, 0, 0, 0, 0)
                        deltaIn = (billSnapshot.inputTokens - base.inputTokens).coerceAtLeast(0L)
                        deltaOut = (billSnapshot.outputTokens - base.outputTokens).coerceAtLeast(0L)
                        deltaCache = (billSnapshot.cachedInputTokens - base.cachedInputTokens).coerceAtLeast(0L)
                        deltaReason = (billSnapshot.reasoningTokens - base.reasoningTokens).coerceAtLeast(0L)
                    }
                    billedTotalUsage[key.id] = billSnapshot
                    if (deltaIn > 0L || deltaOut > 0L) {
                        val now = System.currentTimeMillis()
                        val stepStart = stepStartedAt[key.id] ?: repo.state(key)?.turnStartedAt ?: now
                        val stepDurMs = (now - stepStart).coerceAtLeast(1L)
                        val firstAt = stepFirstTokenAt.remove(key.id)
                        val lastAt = stepLastTokenAt.remove(key.id)
                        val ttftMs = if (firstAt != null && firstAt >= stepStart) (firstAt - stepStart).coerceAtMost(stepDurMs) else 0L
                        val genMs = if (firstAt != null && lastAt != null && lastAt > firstAt) (lastAt - firstAt).coerceAtMost(stepDurMs) else 0L
                        stepStartedAt[key.id] = now
                        repo.hub.recordDirectUsage(
                            agent = "codex",
                            model = repo.state(key)?.model ?: "codex",
                            inputTokens = deltaIn,
                            outputTokens = deltaOut,
                            cacheReadTokens = deltaCache,
                            reasoningTokens = deltaReason,
                            durationMs = stepDurMs,
                            ttftMs = ttftMs,
                            genMs = genMs,
                            streamed = firstAt != null,
                            exact = true,
                            contextUsed = snapshot.totalTokens,
                            contextWindow = snapshot.contextWindow,
                            providerName = "OpenAI 官方",
                            sessionId = key.id,
                        )
                    }
                } else {
                    billedTotalUsage[key.id] = billSnapshot
                }
            }
            "thread/compacted" -> threadKey?.let { key ->
                repo.upsertItem(key, TimelineItem.Notice("compacted-" + newId(), "上下文已完成压缩"))
                repo.update(key) { if (it.activeTurnId == null) it.copy(running = false, turnStartedAt = null) else it }
            }
            "item/fileChange/patchUpdated" -> threadKey?.let { key ->
                val id = params.optString("itemId")
                val changes = params.arr("changes").objects().map { FileDiff(it.optString("path"), it.obj("kind")?.optString("type") ?: "update", it.optString("diff")) }
                repo.mutateItem(key, id, { TimelineItem.FileEdit(id, changes, ItemStatus.RUNNING) }) {
                    if (it is TimelineItem.FileEdit) it.copy(changes = changes) else it
                }
            }
            "turn/completed" -> threadKey?.let { key ->
                finishReasoning(key)
                val turn = params.obj("turn") ?: JSONObject()
                val turnId = turn.str("id")
                val error = turn.obj("error")
                if (error != null) {
                    repo.upsertItem(key, TimelineItem.Notice("error-${turn.optString("id")}", error.optString("message"), error = true))
                    if (repo.state(key)?.providerId == null) {
                        val elapsed = repo.state(key)?.turnStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
                        repo.hub.recordDirectUsage(
                            agent = "codex",
                            model = repo.state(key)?.model ?: "codex",
                            inputTokens = 0L,
                            outputTokens = 0L,
                            durationMs = elapsed,
                            status = 500,
                            error = error.optString("message").take(240),
                            providerName = "OpenAI 官方",
                            sessionId = key.id,
                        )
                    }
                } else if (turn.optString("status") == "interrupted") repo.upsertItem(key, TimelineItem.Notice("interrupted-${turn.optString("id")}", "已中断"))
                repo.update(key) { state ->
                    val durMs = (if (turn.has("durationMs") && !turn.isNull("durationMs")) turn.optLong("durationMs", -1L).takeIf { it >= 0L } else null)
                        ?: state.turnStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                    val lastUser = state.items.lastOrNull { it is TimelineItem.UserMessage } as? TimelineItem.UserMessage
                    val nextDurations = if (durMs != null) buildMap {
                        putAll(state.turnDurations)
                        if (turnId != null) put(turnId, durMs)
                        if (lastUser != null) put(lastUser.id, durMs)
                    } else state.turnDurations
                    state.copy(
                        running = false,
                        activeTurnId = null,
                        turnStartedAt = null,
                        turnDurations = nextDurations,
                        items = state.items.map {
                            when {
                                it is TimelineItem.AgentMessage -> it.copy(streaming = false)
                                it is TimelineItem.Reasoning -> it.copy(streaming = false)
                                it is TimelineItem.Plan -> it.copy(
                                    streaming = false,
                                    title = extractPlanTitle(it.text, it.filePath),
                                    status = if (it.requestFeedback) ItemStatus.RUNNING else ItemStatus.COMPLETED,
                                )
                                it is TimelineItem.UserMessage && lastUser != null && it.id == lastUser.id && durMs != null -> it.copy(durationMs = durMs)
                                else -> it
                            }
                        },
                    )
                }
                turnWaiters.remove(key.id)?.let { waiter ->
                    if (error != null || turn.optString("status") != "completed") waiter.completeExceptionally(IllegalStateException(error?.optString("message") ?: "Codex 任务未完成"))
                    else waiter.complete(repo.state(key)?.items?.lastOrNull { it is TimelineItem.AgentMessage }?.let { (it as TimelineItem.AgentMessage).text } ?: "")
                }
            }
            "item/autoApprovalReview/completed" -> threadKey?.let { key ->
                val review = params.obj("review") ?: return
                val st = review.optString("status")
                if (st in setOf("denied", "timedOut", "aborted")) {
                    val risk = review.str("riskLevel")?.let { "（风险：$it）" } ?: ""
                    val rationale = review.str("rationale")?.take(160)?.let { "：$it" } ?: ""
                    repo.upsertItem(key, TimelineItem.Notice("guardian-${params.str("reviewId") ?: newId()}", "Guardian 自动审校拦截$risk$rationale", error = true))
                }
            }
            "item/started", "item/completed" -> threadKey?.let { key ->
                val item = params.obj("item") ?: return
                if (event.method == "item/started") finishReasoning(key, item.getString("id"),
                    item.optLong("startedAtMs", 0L).takeIf { it > 0L } ?: System.currentTimeMillis())
                val mapped = mapItem(item, live = event.method == "item/started") ?: return
                // 完成事件可能不含流式期间累积的命令输出；UserMessage 则需保留所属 turnId 以支持 thread/revert。
                val previous = repo.findItem(key, mapped.id)
                val merged = when {
                    mapped is TimelineItem.Reasoning -> {
                        val prior = previous as? TimelineItem.Reasoning
                        mapped.copy(
                            summaryParts = mapped.summaryParts?.takeIf { parts -> parts.values.any { it.isNotEmpty() } }
                                ?: prior?.summaryParts ?: emptyMap(),
                            startedAtMs = mapped.startedAtMs ?: prior?.startedAtMs
                                ?: System.currentTimeMillis().takeIf { event.method == "item/started" },
                            completedAtMs = mapped.completedAtMs ?: prior?.completedAtMs
                                ?: System.currentTimeMillis().takeIf { event.method == "item/completed" },
                            streaming = event.method == "item/started" && prior?.completedAtMs == null,
                        )
                    }
                    mapped is TimelineItem.Plan -> {
                        val prior = previous as? TimelineItem.Plan
                        val text = if (mapped.text.length < (prior?.text?.length ?: 0)) prior!!.text else mapped.text
                        val isLive = event.method == "item/started"
                        val inPlanCollab = (repo.state(key)?.collaborationMode ?: settings.codexCollaborationMode) == "plan"
                        mapped.copy(
                            text = text,
                            title = extractPlanTitle(text, mapped.filePath ?: prior?.filePath),
                            streaming = isLive,
                            status = if (isLive || inPlanCollab) ItemStatus.RUNNING else ItemStatus.COMPLETED,
                            requestFeedback = !isLive && inPlanCollab,
                            artifactType = "implementation_plan",
                        )
                    }
                    mapped is TimelineItem.CommandRun && previous is TimelineItem.CommandRun && mapped.output.length < previous.output.length ->
                        mapped.copy(output = previous.output)
                    mapped is TimelineItem.UserMessage -> {
                        val tid = params.str("turnId") ?: (previous as? TimelineItem.UserMessage)?.turnId ?: repo.state(key)?.activeTurnId
                        if (tid != null) mapped.copy(turnId = tid) else mapped
                    }
                    else -> mapped
                }
                repo.upsertItem(key, merged)
                // 工具执行/文件修改结束后重置下一步 LLM 计时起点，排除本地命令执行耗时对 LLM 延迟与吞吐的干扰
                if (event.method == "item/completed" && item.optString("type") in setOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "webSearch")) {
                    stepStartedAt[key.id] = System.currentTimeMillis()
                    stepFirstTokenAt.remove(key.id)
                    stepLastTokenAt.remove(key.id)
                }
            }
            "item/agentMessage/delta" -> threadKey?.let { key ->
                val chunk = params.optString("delta")
                if (chunk.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    stepFirstTokenAt.putIfAbsent(key.id, now)
                    stepLastTokenAt[key.id] = now
                }
                val id = params.optString("itemId")
                appendText(key, id, chunk, "agent") { TimelineItem.AgentMessage(id, "", true) }
            }
            "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded" -> threadKey?.let { key ->
                if (event.method == "item/reasoning/summaryTextDelta" && params.optString("delta").isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    stepFirstTokenAt.putIfAbsent(key.id, now)
                    stepLastTokenAt[key.id] = now
                }
                appendReasoningSummary(key, params)
            }
            "item/plan/delta" -> threadKey?.let { key ->
                val id = params.optString("itemId")
                appendText(key, id, params.optString("delta"), "plan") {
                    TimelineItem.Plan(id, "", streaming = true, status = ItemStatus.RUNNING)
                }
            }
            "item/commandExecution/outputDelta" -> threadKey?.let { key ->
                val id = params.optString("itemId")
                appendText(key, id, params.optString("delta"), "command_output") { TimelineItem.CommandRun(id, "", null, "", null, ItemStatus.RUNNING) }
            }
            "error" -> if (!params.optBoolean("willRetry")) {
                val message = params.obj("error")?.optString("message") ?: "Codex 出错"
                // 与 turn/completed 的错误使用同一条目 id，避免重复显示。
                threadKey?.let { repo.upsertItem(it, TimelineItem.Notice("error-" + (params.str("turnId") ?: newId()), message, error = true)) } ?: repo.notify(message)
            }
            "serverRequest/resolved" -> params.opt("requestId")?.let { repo.removeRequest(kind, it) }
            "warning", "configWarning", "guardianWarning" -> {
                val message = params.str("message") ?: params.str("summary") ?: return
                threadKey?.let { repo.upsertItem(it, TimelineItem.Notice("warning-" + newId(), message)) } ?: repo.notify("Codex：$message")
            }
            "model/rerouted" -> threadKey?.let { key ->
                val toModel = params.str("toModel") ?: params.str("model") ?: return
                repo.update(key) { it.copy(model = toModel) }
                repo.upsertItem(key, TimelineItem.Notice("rerouted-" + newId(), "模型已切换至 $toModel"))
            }
            "hook/started" -> threadKey?.let { key ->
                val name = params.str("hookName") ?: params.str("event") ?: "Hook"
                repo.upsertItem(key, TimelineItem.Notice("hook-${params.str("hookId") ?: newId()}", "执行钩子：$name"))
            }
            "account/updated" -> accountVersionFlow.value++
            "account/login/completed" -> {
                val ok = params.optBoolean("success", params.isNull("error") || !params.has("error"))
                val err = params.str("error") ?: params.obj("error")?.str("message")
                if (ok && err.isNullOrBlank()) {
                    loginFlowMutable.value = null
                    repo.notify("Codex 账号登录成功")
                } else {
                    loginFlowMutable.value = loginFlowMutable.value?.copy(running = false, error = err ?: "登录未完成")
                    repo.notify("Codex 登录失败：${err ?: "请重试"}")
                }
                accountVersionFlow.value++
            }
            "mcpServer/oauthLogin/completed" -> {
                val srv = params.str("name") ?: "MCP"
                val err = params.str("error")
                repo.notify(if (err.isNullOrBlank()) "MCP $srv OAuth 授权成功" else "MCP $srv OAuth 失败：$err")
            }
        }
    }

    private fun onRequest(event: EngineEvent) {
        val id = event.data.get("id")
        val params = event.params
        val conversation = (params.str("threadId") ?: params.str("conversationId"))?.let(::key)
        val request: PendingRequest? = when (event.method) {
            "item/commandExecution/requestApproval" -> {
                val item = conversation?.let { repo.findItem(it, params.optString("itemId")) } as? TimelineItem.CommandRun
                val actions = summarizeCommandActions(params.optJSONArray("commandActions")) ?: item?.actionSummary
                val execPolicy = params.optJSONArray("proposedExecpolicyAmendment")?.strings().orEmpty()
                val netCtx = params.obj("networkApprovalContext")
                val netAmend = params.optJSONArray("proposedNetworkPolicyAmendments")?.objects().orEmpty()
                val netHost = netCtx?.str("host") ?: netAmend.firstOrNull()?.str("host")
                val netProto = netCtx?.str("protocol")
                PendingRequest.CommandApproval(
                    engine = kind,
                    requestId = id,
                    conversation = conversation,
                    command = params.str("command") ?: item?.command ?: "（未提供命令）",
                    cwd = params.str("cwd") ?: item?.cwd,
                    reason = params.str("reason"),
                    actionSummary = actions,
                    proposedExecPolicy = execPolicy,
                    networkHost = netHost,
                    networkProtocol = netProto,
                    allowNetworkAmendment = netAmend.isNotEmpty() && !netHost.isNullOrBlank(),
                )
            }
            "item/fileChange/requestApproval" -> {
                val item = conversation?.let { repo.findItem(it, params.optString("itemId")) } as? TimelineItem.FileEdit
                PendingRequest.FileApproval(
                    engine = kind,
                    requestId = id,
                    conversation = conversation,
                    reason = params.str("reason"),
                    changes = item?.changes ?: emptyList(),
                    grantRoot = params.str("grantRoot"),
                )
            }
            "item/permissions/requestApproval" -> PendingRequest.PermissionGrant(kind, id, conversation, params.str("reason"), params.obj("permissions") ?: JSONObject())
            "item/tool/requestUserInput" -> PendingRequest.UserInput(
                engine = kind,
                requestId = id,
                conversation = conversation,
                questions = params.arr("questions").objects().mapIndexed { idx, q ->
                    val opts = q.optJSONArray("options")?.objects().orEmpty()
                    Question(
                        id = q.optString("id").ifBlank { "q_$idx" },
                        header = q.optString("header"),
                        text = q.optString("question"),
                        options = opts.map { it.optString("label") },
                        allowOther = q.optBoolean("isOther", true),
                        secret = q.optBoolean("isSecret"),
                        optionDescriptions = opts.map { it.optString("description") },
                        multiSelect = q.optBoolean("multiSelect", q.optBoolean("isMultiSelect", false)),
                        optionPreviews = opts.map { it.optString("markdown").ifBlank { it.optString("preview") } },
                    )
                },
                protocol = "codex",
            )
            "mcpServer/elicitation/request" -> PendingRequest.Elicitation(
                engine = kind,
                requestId = id,
                conversation = conversation,
                message = listOfNotNull(params.str("title"), params.str("message") ?: params.str("description")).joinToString("\n"),
                raw = params,
                schemaFields = parseElicitationSchema(params),
                url = params.str("url"),
                serverName = params.str("serverName"),
                mode = params.str("mode") ?: if (params.str("url") != null) "url" else "form",
            )
            "item/tool/call" -> {
                val arguments = params.obj("arguments")
                val toolName = params.optString("tool")
                if (toolName == DELEGATE_TO_CLAUDE && arguments?.str("task") != null) {
                    repo.delegation.requested(kind, id, conversation, EngineKind.CLAUDE, arguments.getString("task"), null)
                } else if (toolName == DELEGATE_TO_AGENT && arguments?.str("task") != null && arguments.str("agent_id") != null) {
                    val target = EngineKind.of(arguments.getString("agent_id").trim().lowercase())
                    repo.delegation.requested(kind, id, conversation, target, arguments.getString("task"), null)
                } else respondToolCall(id, "Solbraid 没有提供工具 $toolName", false)
                null
            }
            "execCommandApproval" -> {
                legacyRequests += id.toString()
                PendingRequest.CommandApproval(kind, id, conversation, params.arr("command").strings().joinToString(" "), params.str("cwd"), params.str("reason"))
            }
            "applyPatchApproval" -> {
                legacyRequests += id.toString()
                val files = params.obj("fileChanges") ?: JSONObject()
                PendingRequest.FileApproval(kind, id, conversation, params.str("reason"), files.keys().asSequence().map { path ->
                    val change = files.getJSONObject(path)
                    val type = change.optString("type")
                    FileDiff(path, type, change.str("unified_diff") ?: unifiedDiff(path, if (type == "delete") change.optString("content") else "", if (type == "add") change.optString("content") else ""))
                }.toList(), grantRoot = params.str("grantRoot"))
            }
            else -> { runtime.reject(kind, id, -32601, "Solbraid 不支持此请求：${event.method}"); null }
        }
        request?.let(repo::addRequest)
    }

    override fun resolve(request: PendingRequest, decision: Decision) {
        val id = request.requestId
        val legacy = legacyRequests.remove(id.toString())
        val allowAny = decision is Decision.Allow || decision is Decision.AllowForSession || decision is Decision.AllowWithRule
        val result: Any = when (request) {
            is PendingRequest.CommandApproval -> if (legacy) {
                val legacyDec: Any = when (decision) {
                    is Decision.AllowForSession -> "approved_for_session"
                    is Decision.Allow, is Decision.AllowWithRule -> "approved"
                    is Decision.Deny -> if (decision.interrupt) "abort" else jsonOf("denied" to jsonOf("rejection" to decision.reason))
                    else -> "abort"
                }
                jsonOf("decision" to legacyDec)
            } else {
                val v2Dec: Any = when (decision) {
                    is Decision.Allow -> "accept"
                    is Decision.AllowForSession -> "acceptForSession"
                    is Decision.AllowWithRule -> when {
                        !decision.execPolicy.isNullOrEmpty() -> jsonOf(
                            "acceptWithExecpolicyAmendment" to jsonOf("execpolicy_amendment" to JSONArray(decision.execPolicy)),
                        )
                        !decision.networkHost.isNullOrBlank() -> jsonOf(
                            "applyNetworkPolicyAmendment" to jsonOf(
                                "network_policy_amendment" to jsonOf("host" to decision.networkHost, "action" to decision.networkAction),
                            ),
                        )
                        else -> "acceptForSession"
                    }
                    is Decision.Deny -> if (decision.interrupt) "cancel" else "decline"
                    else -> "decline"
                }
                jsonOf("decision" to v2Dec)
            }
            is PendingRequest.FileApproval -> if (legacy) {
                val legacyDec: Any = when (decision) {
                    is Decision.AllowForSession -> "approved_for_session"
                    is Decision.Allow, is Decision.AllowWithRule -> "approved"
                    is Decision.Deny -> if (decision.interrupt) "abort" else jsonOf("denied" to jsonOf("rejection" to decision.reason))
                    else -> "abort"
                }
                jsonOf("decision" to legacyDec)
            } else {
                val v2Dec = when (decision) {
                    is Decision.Allow -> "accept"
                    is Decision.AllowForSession, is Decision.AllowWithRule -> "acceptForSession"
                    is Decision.Deny -> if (decision.interrupt) "cancel" else "decline"
                    else -> "decline"
                }
                jsonOf("decision" to v2Dec)
            }
            is PendingRequest.PermissionGrant -> {
                val granted = JSONObject()
                if (allowAny) for (name in listOf("network", "fileSystem")) request.permissions.optJSONObject(name)?.let { granted.put(name, it) }
                val scope = if (decision is Decision.AllowForSession || decision is Decision.AllowWithRule) "session" else "turn"
                jsonOf("permissions" to granted, "scope" to scope)
            }
            is PendingRequest.UserInput -> {
                val answers = JSONObject()
                (decision as? Decision.Answers)?.values?.forEach { (question, values) -> answers.put(question, jsonOf("answers" to JSONArray(values))) }
                jsonOf("answers" to answers)
            }
            is PendingRequest.Elicitation -> when (decision) {
                is Decision.FormSubmit -> jsonOf("action" to "accept", "content" to decision.content, "_meta" to JSONObject.NULL)
                is Decision.Allow, is Decision.AllowForSession, is Decision.AllowWithRule ->
                    jsonOf("action" to "accept", "content" to if (request.schemaFields.isNotEmpty()) JSONObject() else JSONObject.NULL, "_meta" to JSONObject.NULL)
                is Decision.Deny -> jsonOf("action" to if (decision.interrupt) "cancel" else "decline", "content" to JSONObject.NULL, "_meta" to JSONObject.NULL)
                else -> jsonOf("action" to "decline", "content" to JSONObject.NULL, "_meta" to JSONObject.NULL)
            }
            else -> error("Codex 不处理此类请求")
        }
        runtime.respond(kind, id, result)
    }

    fun respondToolCall(id: Any, text: String, success: Boolean) = runtime.respond(kind, id, jsonOf(
        "contentItems" to JSONArray().put(jsonOf("type" to "inputText", "text" to text)), "success" to success))

    override fun reset() {
        repo.conversations.value.keys.filter { it.engine == kind }.forEach { finishReasoning(it) }
        attached.clear(); legacyRequests.clear(); submittedOptions.clear(); billedTotalUsage.clear()
        stepStartedAt.clear(); stepFirstTokenAt.clear(); stepLastTokenAt.clear()
        loginFlowMutable.value = null
        turnWaiters.values.forEach { it.completeExceptionally(IllegalStateException("运行环境已停止")) }
        turnWaiters.clear()
    }
}
