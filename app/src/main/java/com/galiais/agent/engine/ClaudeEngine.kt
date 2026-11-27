package com.galiais.agent.engine

import com.galiais.agent.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val DELEGATE_TO_CODEX = "delegate_to_codex"

/** 官方 Claude Agent SDK（经 Linux 环境内 claude-host.mjs）适配器。 */
class ClaudeEngine(private val repo: ConversationRepository) : EngineAdapter {
    override val kind = EngineKind.CLAUDE
    private val runtime get() = repo.runtime
    private val settings get() = repo.settings
    /** 已在 claude-host 中打开（有活动 SDK 查询）的会话。 */
    private val connected = mutableSetOf<String>()
    private val modelCatalogs = mutableMapOf<String?, List<ModelOption>>()
    /** 每个会话当前流式输出的 API 消息 id，用于把 stream_event 与最终 assistant 消息对齐。 */
    private val streaming = mutableMapOf<String, String>()
    private val turnWaiters = mutableMapOf<String, CompletableDeferred<String>>()
    private val dialogRequests = mutableSetOf<String>()
    private val commandStartedAt = mutableMapOf<String, Long>()
    private val sessionCumulativeTokens = mutableMapOf<String, Long>()
    private val msgStartedAt = mutableMapOf<String, Long>()
    private val msgFirstTokenAt = mutableMapOf<String, Long>()
    private val msgLastTokenAt = mutableMapOf<String, Long>()
    /** 按单条 API message.id 暂存其精确流式耗时（durMs, ttftMs, genMs），供 assistant 逐请求入账使用。 */
    private val msgTimingById = mutableMapOf<String, Triple<Long, Long, Long>>()
    /** 记录本轮已逐条入账的 assistant message.id，避免同一消息多次下发或 result 汇总重复记账。 */
    private val turnBilledMsgIds = mutableMapOf<String, MutableSet<String>>()
    private val turnTtftSum = mutableMapOf<String, Long>()
    private val turnTtftCount = mutableMapOf<String, Int>()
    private val turnGenMsSum = mutableMapOf<String, Long>()
    private val claudeTasks = mutableMapOf<String, MutableList<PlanStep>>()
    private val pendingTaskCreateTool = mutableMapOf<String, Pair<String, Int>>()
    private val latestPlanFile = mutableMapOf<String, Pair<String, String>>()

    private fun key(sessionId: String) = ConversationKey(EngineKind.CLAUDE, sessionId)
    private suspend fun call(method: String, params: JSONObject? = null) = runtime.requestObject(kind, method, params)

    override suspend fun list(): List<ConversationSummary> = listSessions(includeArchived = false)

    suspend fun listArchived(): List<ConversationSummary> = listSessions(includeArchived = true).filter { it.archived }

    private suspend fun listSessions(includeArchived: Boolean): List<ConversationSummary> {
        val summaries = linkedMapOf<ConversationKey, ConversationSummary>()
        var offset = 0
        while (true) {
            val resp = call("session/list", jsonOf("limit" to 100, "offset" to offset))
            if (offset == 0 && resp.has("activeSessions")) {
                val activeList = resp.arr("activeSessions").objects()
                val activeIds = mutableSetOf<String>()
                val busyIds = mutableSetOf<String>()
                for (a in activeList) {
                    val sid = a.str("sessionId") ?: continue
                    activeIds += sid
                    if (a.optBoolean("busy", false)) busyIds += sid
                }
                connected.clear()
                connected += activeIds
                for (sid in busyIds) {
                    val k = key(sid)
                    if (repo.state(k) != null) repo.update(k) { it.copy(running = true) }
                }
            }
            val page = resp.arr("sessions").objects()
            for (info in page) {
                val archived = info.optBoolean("archived", false)
                if (!includeArchived && archived) continue
                val item = ConversationSummary(
                    key = key(info.getString("sessionId")),
                    title = info.str("customTitle") ?: info.optString("summary").ifBlank { "未命名会话" },
                    preview = info.optString("firstPrompt"),
                    cwd = info.str("cwd"),
                    updatedAt = normalizeTime(info.optLong("lastModified")),
                    archived = archived,
                    tag = info.str("tag"),
                    gitBranch = info.str("gitBranch"),
                )
                summaries[item.key] = item
            }
            if (page.size < 100) return summaries.values.toList()
            offset += page.size
        }
    }

    private data class ClaudeHistory(
        val page: ConversationHistoryPage,
        val plan: List<PlanStep>,
        val explanation: String?,
        val planFile: Pair<String, String>?,
        val model: String?,
        val permissionMode: String?,
        val usage: TokenUsage?,
    )

    override suspend fun load(key: ConversationKey) {
        val before = repo.state(key)
        val history = readHistoryPage(key, null)
        val savedProvider = settings.claudeSessionProvider(key.id)
        val selectedOptions = settings.conversationOptions(key)
        val current = repo.state(key)
        val livePlan = current?.plan !== before?.plan
        if (!livePlan) {
            claudeTasks[key.id] = history.plan.toMutableList()
            if (history.planFile != null) latestPlanFile[key.id] = history.planFile else latestPlanFile.remove(key.id)
        }
        repo.update(key) { base ->
            val updated = base.copy(
                items = base.mergeHistoryItems(before, history.page.items),
                historyCursor = history.page.nextCursor,
                plan = if (livePlan) base.plan else history.plan,
                planExplanation = if (livePlan) base.planExplanation else history.explanation,
                loaded = true, running = base.running && key.id in connected,
                providerId = base.providerId ?: savedProvider,
                model = if (base.model != before?.model) base.model else history.model ?: base.model,
                permissionMode = if (base.permissionMode != before?.permissionMode) base.permissionMode else history.permissionMode ?: base.permissionMode,
                tokenUsage = if (base.tokenUsage !== before?.tokenUsage) base.tokenUsage else history.usage ?: base.tokenUsage,
            )
            selectedOptions?.applyTo(updated) ?: updated
        }
    }

    override suspend fun loadEarlier(key: ConversationKey, cursor: String) = readHistoryPage(key, cursor).page

    private suspend fun readHistoryPage(key: ConversationKey, cursor: String?): ClaudeHistory {
        val current = repo.state(key)
        val response = runtime.historyRequest(key, current?.summary?.cwd, cursor)
        val defaultPermMode = settings.state.value.claudePermissionMode
        val initModel = current?.model
        val initPermMode = current?.permissionMode
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val prior = response.arr("priorState").objects()
            val messages = response.getJSONArray("messages").objects()
            val items = ArrayList<TimelineItem>(messages.size)
            val idToIdx = HashMap<String, Int>(messages.size * 2)
            var showItem = false
            fun upsertLocal(item: TimelineItem) {
                if (!showItem) return
                val prevIdx = idToIdx[item.id]
                if (prevIdx != null) items[prevIdx] = item
                else {
                    idToIdx[item.id] = items.size
                    items.add(item)
                }
            }
            val tasks = mutableListOf<PlanStep>()
            var planExplanation: String? = null
            val localPendingTaskCreate = HashMap<String, Int>()
            var localLatestPlan: Pair<String, String>? = null
            var curModel = initModel
            var curPermMode = initPermMode
            var curTokenUsage: TokenUsage? = null
            val taskIdRe = Regex("""(?:Task\s*#|id["']?\s*[:=]\s*["']?)([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

            for ((index, message) in (prior + messages).withIndex()) {
                showItem = index >= prior.size
                val isSubagentMsg = !message.isNull("parent_tool_use_id") && message.has("parent_tool_use_id")
                if (isSubagentMsg) continue
                when (message.optString("type")) {
                    "system" -> when (message.optString("subtype")) {
                        "init" -> {
                            message.str("model")?.let { curModel = it }
                            message.str("permissionMode")?.takeIf { it in CLAUDE_PERMISSION_MODES }?.let { curPermMode = it }
                        }
                        "status" -> {
                            message.str("permissionMode")?.takeIf { it in CLAUDE_PERMISSION_MODES }?.let { curPermMode = it }
                        }
                        "compact_boundary" -> {
                            val meta = message.optJSONObject("compact_metadata")
                            val triggerLabel = if (meta?.optString("trigger") == "auto") "上下文已自动压缩" else "上下文已压缩"
                            val pre = meta?.optLong("pre_tokens", 0L) ?: 0L
                            val post = meta?.optLong("post_tokens", 0L) ?: 0L
                            fun fmtK(n: Long) = if (n >= 1000L) "%.1fk".format(n / 1000.0) else n.toString()
                            val label = when {
                                pre > 0L && post > 0L -> "$triggerLabel（${fmtK(pre)} → ${fmtK(post)} tokens）"
                                pre > 0L -> "$triggerLabel（压缩前 ${fmtK(pre)} tokens）"
                                else -> triggerLabel
                            }
                            upsertLocal(TimelineItem.Notice(message.optString("uuid", newId()), label))
                            if (post > 0L) {
                                curTokenUsage = TokenUsage(
                                    totalTokens = post,
                                    inputTokens = post,
                                    cachedInputTokens = 0L,
                                    outputTokens = 0L,
                                    contextWindow = curTokenUsage?.contextWindow ?: inferModelContextWindow(curModel),
                                    sessionTotalTokens = curTokenUsage?.sessionTotalTokens,
                                )
                            }
                        }
                    }
                    "assistant" -> {
                        val body = message.optJSONObject("message") ?: continue
                        body.optJSONObject("usage")?.let { u ->
                            val inputTok = u.optLong("input_tokens")
                            val cacheRead = u.optLong("cache_read_input_tokens")
                            val cacheWrite = u.optLong("cache_creation_input_tokens")
                            val outTok = u.optLong("output_tokens")
                            val totalIn = inputTok + cacheRead + cacheWrite
                            if (totalIn + outTok > 0L) {
                                curTokenUsage = TokenUsage(
                                    totalTokens = totalIn + outTok,
                                    inputTokens = totalIn,
                                    cachedInputTokens = cacheRead,
                                    cacheWriteTokens = cacheWrite,
                                    outputTokens = outTok,
                                    contextWindow = curTokenUsage?.contextWindow ?: inferModelContextWindow(curModel),
                                    sessionTotalTokens = curTokenUsage?.sessionTotalTokens,
                                )
                            }
                        }
                        val messageId = body.optString("id", message.optString("uuid"))
                        body.arr("content").objects().forEachIndexed { index, block ->
                            val id = "$messageId:$index"
                            when (block.optString("type")) {
                                "text" -> upsertLocal(TimelineItem.AgentMessage(id, block.optString("text")))
                                "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let { thinkingText ->
                                    upsertLocal(TimelineItem.Reasoning(id = id, text = thinkingText, streaming = false))
                                }
                                "tool_use" -> {
                                    val tName = block.optString("name")
                                    val tInput = block.optJSONObject("input")
                                    val toolUseId = block.optString("id")
                                    when {
                                        tName == "TodoWrite" && tInput != null -> {
                                            val todos = tInput.arr("todos").objects().mapNotNull { t ->
                                                val label = t.str("content") ?: t.str("activeForm") ?: return@mapNotNull null
                                                PlanStep(label, normalizePlanStepStatus(t.optString("status", "pending")), t.str("id"), t.str("activeForm"), t.str("priority"))
                                            }
                                            tasks.clear(); tasks.addAll(todos)
                                            if (todos.isNotEmpty()) planExplanation = "Claude Code 任务清单"
                                        }
                                        tName == "TaskCreate" && tInput != null -> {
                                            val subject = tInput.str("subject") ?: tInput.str("description") ?: tInput.str("content")
                                            if (!subject.isNullOrBlank()) {
                                                val nextId = tInput.str("id") ?: (tasks.size + 1).toString()
                                                val idx = tasks.size
                                                tasks += PlanStep(subject, normalizePlanStepStatus(tInput.optString("status", "pending")), nextId, tInput.str("activeForm"), tInput.str("priority"))
                                                if (toolUseId.isNotBlank()) localPendingTaskCreate[toolUseId] = idx
                                                planExplanation = "Claude Code 任务清单"
                                            }
                                        }
                                        tName == "TaskUpdate" && tInput != null -> {
                                            val taskId = tInput.str("taskId") ?: tInput.str("id")
                                            val rawStatus = tInput.str("status")
                                            val idx = when {
                                                taskId == null -> -1
                                                else -> tasks.indexOfFirst { it.id == taskId }.takeIf { it >= 0 }
                                                    ?: taskId.toIntOrNull()?.minus(1)?.takeIf { it in tasks.indices }
                                                    ?: -1
                                            }
                                            if (idx >= 0) {
                                                if (rawStatus.equals("deleted", ignoreCase = true)) tasks.removeAt(idx)
                                                else {
                                                    val old = tasks[idx]
                                                    tasks[idx] = old.copy(
                                                        step = tInput.str("subject") ?: tInput.str("content") ?: old.step,
                                                        status = rawStatus?.let(::normalizePlanStepStatus) ?: old.status,
                                                        activeForm = tInput.str("activeForm") ?: old.activeForm,
                                                        priority = tInput.str("priority") ?: old.priority,
                                                    )
                                                }
                                                planExplanation = if (tasks.isEmpty()) null else "Claude Code 任务清单"
                                            }
                                        }
                                        tName == "EnterPlanMode" -> curPermMode = "plan"
                                        tName == "ExitPlanMode" -> {
                                            val planMd = tInput?.str("plan") ?: localLatestPlan?.second
                                            val planPath = tInput?.str("planFilePath") ?: localLatestPlan?.first
                                            if (!planMd.isNullOrBlank()) {
                                                upsertLocal(TimelineItem.Plan(
                                                    id = "claude-plan-${toolUseId.ifBlank { id }}",
                                                    text = planMd,
                                                    title = extractPlanTitle(planMd, planPath),
                                                    filePath = planPath,
                                                    status = ItemStatus.COMPLETED,
                                                ))
                                            }
                                            curPermMode = defaultPermMode
                                        }
                                        tName in setOf("Write", "Edit") && tInput != null -> {
                                            val path = tInput.str("file_path") ?: tInput.str("path")
                                            if (!path.isNullOrBlank() && isClaudePlanFile(path)) {
                                                val updatedContent = tInput.str("content") ?: run {
                                                    val oldStr = tInput.str("old_string")
                                                    val newStr = tInput.str("new_string")
                                                    val prev = localLatestPlan?.takeIf { it.first == path }?.second
                                                    when {
                                                        prev != null && oldStr != null && newStr != null ->
                                                            if (tInput.optBoolean("replace_all", false)) prev.replace(oldStr, newStr) else prev.replaceFirst(oldStr, newStr)
                                                        !newStr.isNullOrBlank() && prev == null -> newStr
                                                        else -> null
                                                    }
                                                }
                                                if (!updatedContent.isNullOrBlank()) {
                                                    localLatestPlan = path to updatedContent
                                                    upsertLocal(TimelineItem.Plan(
                                                        id = "claude-plan-file-${path.hashCode()}",
                                                        text = updatedContent,
                                                        title = extractPlanTitle(updatedContent, path),
                                                        filePath = path,
                                                        status = ItemStatus.COMPLETED,
                                                        requestFeedback = false,
                                                        artifactType = "implementation_plan",
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                    if (tName == "mcp__galiais__$DELEGATE_TO_CODEX" || tName == "mcp__galiais__delegate_to_agent") {
                                        val targetId = tInput?.str("agent_id")?.takeIf { it.isNotBlank() } ?: "codex"
                                        upsertLocal(TimelineItem.Delegation(toolUseId, EngineKind.of(targetId), tInput?.optString("task") ?: "", null, null, ItemStatus.COMPLETED))
                                    } else if (tName !in setOf("TodoWrite", "TaskCreate", "TaskUpdate")) {
                                        upsertLocal(toolItem(block, live = false))
                                    }
                                }
                            }
                        }
                    }
                    "user" -> {
                        val body = message.optJSONObject("message") ?: continue
                        val content = body.opt("content")
                        if (content is String) {
                            if (content.isNotBlank() && !message.optBoolean("isSynthetic") && !message.optBoolean("is_meta")) {
                                upsertLocal(TimelineItem.UserMessage(message.optString("uuid", newId()), content))
                            }
                            continue
                        }
                        val blocks = (content as? JSONArray)?.objects() ?: continue
                        val text = blocks.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
                        val images = blocks.count { it.optString("type") == "image" }
                        val documents = blocks.filter { it.optString("type") == "document" }
                        if (!message.optBoolean("isSynthetic") && !message.optBoolean("is_meta") &&
                            (text.isNotBlank() || images > 0 || documents.isNotEmpty())) {
                            val label = text.ifBlank { documents.joinToString("\n") { it.str("title") ?: "[文档]" } }
                            upsertLocal(TimelineItem.UserMessage(message.optString("uuid", newId()), label, imageCount = images))
                        }
                        for (result in blocks.filter { it.optString("type") == "tool_result" }) {
                            val output = blockText(result.opt("content"))
                            val status = if (result.optBoolean("is_error")) ItemStatus.FAILED else ItemStatus.COMPLETED
                            val toolUseId = result.optString("tool_use_id")
                            localPendingTaskCreate.remove(toolUseId)?.let { idx ->
                                val assignedId = taskIdRe.find(output)?.groupValues?.getOrNull(1)
                                if (assignedId != null && idx in tasks.indices) {
                                    tasks[idx] = tasks[idx].copy(id = assignedId)
                                }
                            }
                            val existingIdx = idToIdx[toolUseId] ?: continue
                            val existing = items[existingIdx]
                            items[existingIdx] = when (existing) {
                                is TimelineItem.CommandRun -> existing.copy(output = output.takeLast(200_000), status = status, exitCode = if (status == ItemStatus.FAILED) existing.exitCode else 0)
                                is TimelineItem.FileEdit -> existing.copy(status = status)
                                is TimelineItem.ToolCall -> existing.copy(result = output.take(20_000), status = status)
                                is TimelineItem.Delegation -> existing.copy(result = output.take(20_000), status = status)
                                else -> existing
                            }
                        }
                    }
                    "result" -> {
                        val resUsage = message.optJSONObject("usage")
                        val modelUsageObj = message.optJSONObject("modelUsage")
                        var reportedWindow: Long? = null
                        if (modelUsageObj != null) {
                            val keys = modelUsageObj.keys()
                            while (keys.hasNext()) {
                                val entry = modelUsageObj.optJSONObject(keys.next()) ?: continue
                                val cw = entry.optLong("contextWindow", 0L)
                                if (cw > 0L) reportedWindow = if (reportedWindow == null) cw else maxOf(reportedWindow, cw)
                            }
                        }
                        val turnIn = resUsage?.let { it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens") } ?: 0L
                        val turnOut = resUsage?.optLong("output_tokens") ?: 0L
                        val turnSum = turnIn + turnOut
                        if (reportedWindow != null || turnSum > 0L) {
                            val win = reportedWindow ?: curTokenUsage?.contextWindow ?: inferModelContextWindow(curModel)
                            curTokenUsage = curTokenUsage?.copy(contextWindow = win) ?: if (turnSum > 0L) {
                                TokenUsage(
                                    totalTokens = turnSum,
                                    inputTokens = turnIn,
                                    cachedInputTokens = resUsage?.optLong("cache_read_input_tokens") ?: 0L,
                                    cacheWriteTokens = resUsage?.optLong("cache_creation_input_tokens") ?: 0L,
                                    outputTokens = turnOut,
                                    contextWindow = win,
                                )
                            } else null
                        }
                    }
                }
            }
            ClaudeHistory(ConversationHistoryPage(items, response.str("nextCursor")), tasks,
                planExplanation, localLatestPlan, curModel, curPermMode, curTokenUsage)
        }
    }
    private fun normalizeDir(path: String): String = path.trim().trimEnd('/').ifEmpty { "/" }

    private fun sessionOptions(
        cwd: String,
        providerId: String?,
        model: String?,
        effort: String? = null,
        permissionMode: String? = null,
        reasoningSummary: String? = null,
        fastMode: Boolean? = null,
        additionalDirectories: List<String> = emptyList(),
        ultracode: Boolean = false,
    ): JSONObject {
        val normCwd = normalizeDir(cwd)
        require(settings.state.value.trustedProjects.any { normalizeDir(it) == normCwd }) { "尚未信任此项目，不能加载它的 Claude Code 配置" }
        val state = settings.state.value
        val resolvedMode = permissionMode ?: state.claudePermissionMode
        val thinkingDisplay = if ((reasoningSummary ?: state.defaultReasoningSummaryFor(kind)) == "none") "omitted" else "summarized"
        val extraDirsArr = JSONArray(additionalDirectories)
        if (providerId != null) {
            val provider = state.provider(providerId) ?: error("所选自定义服务商不存在")
            val (port, token) = runtime.gatewayEndpoint ?: error("本地协议网关未就绪")
            val resolvedModel = model?.takeIf { it.isNotBlank() }
                ?: provider.models.firstOrNull()
                ?: error("请先为服务商「${provider.name}」添加或填写模型")
            return jsonOf(
                "cwd" to normCwd, "trustProject" to true,
                "apiKey" to token,
                "baseUrl" to "http://127.0.0.1:$port/anthropic/${provider.id}",
                "customProvider" to true,
                "model" to resolvedModel,
                "effort" to effort,
                "permissionMode" to resolvedMode,
                "thinkingDisplay" to thinkingDisplay,
                "fastMode" to fastMode,
                "ultracode" to ultracode,
                "promptSuggestions" to true,
                "additionalDirectories" to extraDirsArr,
                "promptCacheTtl" to state.claudePromptCacheTtl,
                "commitAttribution" to state.claudeCommitAttribution,
                "artifactsEnabled" to state.artifactsModeEnabled,
            )
        }
        val officialModel = model?.takeIf { it.isNotBlank() }
        return jsonOf("cwd" to normCwd, "trustProject" to true, "apiKey" to settings.claudeApiKey(),
            "oauthToken" to settings.claudeOauthToken(),
            "baseUrl" to state.claudeBaseUrl, "model" to officialModel, "effort" to effort,
            "permissionMode" to resolvedMode, "thinkingDisplay" to thinkingDisplay, "fastMode" to fastMode,
            "ultracode" to ultracode, "promptSuggestions" to true, "additionalDirectories" to extraDirsArr,
            "promptCacheTtl" to state.claudePromptCacheTtl, "commitAttribution" to state.claudeCommitAttribution,
            "artifactsEnabled" to state.artifactsModeEnabled)
    }

    private fun applyInitMetadata(key: ConversationKey, init: JSONObject?) {
        if (init == null) return
        val style = init.str("output_style")
        val styles = init.arr("available_output_styles").strings()
        val acct = init.optJSONObject("account")
        val accountSummary = acct?.let { a ->
            listOfNotNull(
                a.str("email"),
                a.str("subscriptionType")?.uppercase(),
                a.str("organization"),
                a.str("apiProvider")?.takeIf { a.str("email").isNullOrBlank() },
            ).joinToString(" / ").takeIf { it.isNotBlank() }
        }
        val fastState = init.str("fast_mode_state")
        val fastReason = init.str("fast_mode_disabled_reason")
        val cliVer = init.str("claude_code_version")
        if (style != null || styles.isNotEmpty() || accountSummary != null || fastState != null || fastReason != null || cliVer != null) {
            repo.update(key) { st ->
                st.copy(
                    outputStyle = style ?: st.outputStyle,
                    availableOutputStyles = if (styles.isNotEmpty()) styles else st.availableOutputStyles,
                    claudeAccountSummary = accountSummary ?: st.claudeAccountSummary,
                    fastModeState = fastState ?: st.fastModeState,
                    fastModeDisabledReason = fastReason ?: st.fastModeDisabledReason,
                    claudeCodeVersion = cliVer ?: st.claudeCodeVersion,
                )
            }
        }
    }

    private suspend fun ensureOpen(key: ConversationKey) {
        if (key.id in connected) return
        val state = repo.state(key)
        val cwd = state?.summary?.cwd ?: error("会话缺少工作目录，无法恢复")
        val providerId = settings.claudeSessionProvider(key.id) ?: state.providerId
        val allowDelegation = settings.state.value.delegationEnabled && state.summary.parent == null
        if (providerId != null) repo.syncProvidersNow()
        repo.envSsh.syncBeforeLaunch()
        val opened = call(
            "session/open",
            sessionOptions(
                cwd, providerId, state.model, state.effort, state.permissionMode,
                state.reasoningSummary, state.fastMode, state.additionalDirectories, state.ultracode,
            ).put("resume", key.id).put("allowDelegation", allowDelegation),
        )
        connected += key.id
        settings.setClaudeSessionProvider(key.id, providerId)
        repo.update(key) { it.copy(providerId = providerId) }
        applyInitMetadata(key, opened.optJSONObject("initialization"))
    }

    override suspend fun create(cwd: String, options: CreateOptions): ConversationKey {
        return createSession(cwd, options)
    }

    private suspend fun createSession(cwd: String, options: CreateOptions, restoredOptions: ConversationOptions? = null): ConversationKey {
        val state = settings.state.value
        val providerId = when (options.providerId) {
            null -> state.claudeProviderId
            "" -> null
            else -> options.providerId
        }
        val model = options.model ?: state.claudeModel.takeIf { providerId == state.claudeProviderId }
            ?: providerId?.let { state.provider(it)?.models?.firstOrNull() }
        val useModelDefaults = model != null && providerId == state.claudeProviderId && (options.model == null || options.model == state.claudeModel)
        val effort = if (restoredOptions != null) restoredOptions.effort else state.claudeEffort.takeIf { useModelDefaults }
        val fastMode = if (restoredOptions != null) restoredOptions.fastMode else state.claudeFastMode.takeIf { useModelDefaults }
        val permissionMode = restoredOptions?.permissionMode
            ?: if (state.claudePermissionMode == "auto" && !useModelDefaults) "default" else state.claudePermissionMode
        val reasoningSummary = restoredOptions?.reasoningSummary ?: state.defaultReasoningSummaryFor(kind)
        val opts = sessionOptions(cwd, providerId, model, effort, permissionMode,
            reasoningSummary, fastMode).put("allowDelegation", options.allowDelegation)
        if (providerId != null) repo.syncProvidersNow()
        repo.envSsh.syncBeforeLaunch()
        val opened = call("session/open", opts)
        val key = key(opened.getString("sessionId"))
        connected += key.id
        settings.setClaudeSessionProvider(key.id, providerId)
        repo.update(key) {
            it.copy(
                summary = ConversationSummary(key, "新会话", "", opened.str("cwd") ?: cwd, System.currentTimeMillis(), parent = options.parent),
                loaded = true,
                providerId = providerId,
                model = opts.str("model") ?: it.model,
                effort = effort,
                permissionMode = permissionMode,
                reasoningSummary = reasoningSummary,
                fastMode = fastMode,
            )
        }
        applyInitMetadata(key, opened.optJSONObject("initialization"))
        return key
    }

    override suspend fun send(key: ConversationKey, text: String, attachments: List<ImageAttachment>) = repo.withEngineOperation(kind.id) {
        ensureOpen(key)
        val content: Any = if (attachments.isEmpty()) text else JSONArray().apply {
            for (att in attachments) {
                put(jsonOf(
                    "type" to "image",
                    "source" to jsonOf("type" to "base64", "media_type" to att.mimeType, "data" to att.base64),
                ))
            }
            if (text.isNotBlank()) put(jsonOf("type" to "text", "text" to text))
        }
        val started = call("turn/start", jsonOf("sessionId" to key.id, "content" to content))
        val messageId = started.str("messageId") ?: ("local-" + newId())
        repo.upsertItem(key, TimelineItem.UserMessage(messageId, text, imageCount = attachments.size))
        repo.update(key) { state ->
            val preview = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: if (attachments.isNotEmpty()) "[图片]" else state.summary.title
            val title = if (state.summary.title == "新会话") preview else state.summary.title
            state.copy(running = true, turnStartedAt = System.currentTimeMillis(), summary = state.summary.copy(title = title, preview = state.summary.preview.ifBlank { preview }))
        }
    }

    /** 发送 PDF 或文本文档块给 Claude Code 原生 `document` 内容接口。 */
    suspend fun sendWithDocument(key: ConversationKey, text: String, docName: String, mimeType: String, base64OrPlain: String, isPdf: Boolean) = repo.withEngineOperation(kind.id) {
        ensureOpen(key)
        val source = if (isPdf) {
            jsonOf("type" to "base64", "media_type" to "application/pdf", "data" to base64OrPlain)
        } else {
            jsonOf("type" to "text", "media_type" to mimeType.ifBlank { "text/plain" }, "data" to base64OrPlain)
        }
        val content = JSONArray().apply {
            put(jsonOf("type" to "document", "title" to docName, "source" to source))
            put(jsonOf("type" to "text", "text" to text.ifBlank { "请阅读并分析附件文档《$docName》。" }))
        }
        val started = call("turn/start", jsonOf("sessionId" to key.id, "content" to content))
        val messageId = started.str("messageId") ?: ("local-" + newId())
        val display = buildString {
            append("[文档：$docName]")
            if (text.isNotBlank()) append("\n").append(text)
        }
        repo.upsertItem(key, TimelineItem.UserMessage(messageId, display))
        repo.update(key) { state ->
            val preview = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "[文档] $docName"
            val title = if (state.summary.title == "新会话") preview else state.summary.title
            state.copy(running = true, turnStartedAt = System.currentTimeMillis(), summary = state.summary.copy(title = title, preview = state.summary.preview.ifBlank { preview }))
        }
    }

    suspend fun runTask(key: ConversationKey, text: String): String {
        val waiter = CompletableDeferred<String>()
        turnWaiters[key.id] = waiter
        try { send(key, text); return waiter.await() } finally { turnWaiters.remove(key.id, waiter) }
    }

    override suspend fun interrupt(key: ConversationKey) {
        if (key.id in connected || repo.state(key)?.running == true) {
            runCatching { call("turn/interrupt", jsonOf("sessionId" to key.id)) }
        }
    }

    override suspend fun rename(key: ConversationKey, title: String) {
        call("session/rename", jsonOf("sessionId" to key.id, "title" to title, "cwd" to repo.state(key)?.summary?.cwd))
    }

    override suspend fun delete(key: ConversationKey) {
        runCatching { call("session/close", jsonOf("sessionId" to key.id)) }
        connected -= key.id
        call("session/delete", jsonOf("sessionId" to key.id, "cwd" to repo.state(key)?.summary?.cwd))
        settings.setClaudeSessionProvider(key.id, null)
        sessionCumulativeTokens.remove(key.id)
    }

    override suspend fun compact(key: ConversationKey) {
        send(key, "/compact")
    }

    /** 官方 Claude Agent SDK 原生会话分叉（sdk.forkSession），保留当前服务商/模型/目录。 */
    override suspend fun fork(key: ConversationKey): ConversationKey {
        val current = repo.state(key)
        val cwd = current?.summary?.cwd
        val res = call("session/fork", jsonOf("sessionId" to key.id, "cwd" to cwd))
        val newKey = key(res.getString("sessionId"))
        settings.setClaudeSessionProvider(newKey.id, current?.providerId)
        repo.update(newKey) { state ->
            state.copy(
                summary = ConversationSummary(
                    key = newKey,
                    title = (current?.summary?.title?.takeIf { it.isNotBlank() }?.let { "$it（分支）" }) ?: "分支会话",
                    preview = current?.summary?.preview ?: "",
                    cwd = cwd,
                    updatedAt = System.currentTimeMillis(),
                    parent = key,
                ),
                loaded = false,
                providerId = current?.providerId,
                model = current?.model,
                effort = current?.effort,
                permissionMode = current?.permissionMode ?: settings.state.value.claudePermissionMode,
                reasoningSummary = current?.reasoningSummary,
                fastMode = current?.fastMode,
            )
        }
        return newKey
    }

    /** 实时切换 Claude Code 权限模式（5 档：default / acceptEdits / plan / auto / bypassPermissions）。 */
    suspend fun setPermissionMode(key: ConversationKey, mode: String) {
        require(mode in CLAUDE_PERMISSION_MODES) { "不支持的权限模式：$mode" }
        require(mode != "auto" || models(key).selectedModel(repo.state(key)?.model)?.supportsAutoMode == true) { "当前模型不支持自动审校" }
        if (key.id in connected) call("session/setPermissionMode", jsonOf("sessionId" to key.id, "mode" to mode))
        repo.update(key) { it.copy(permissionMode = mode) }
    }

    /** 基于官方文件检查点预览或真实回滚某条用户消息之后的工作区文件变更。 */
    suspend fun rewindFiles(key: ConversationKey, userMessageId: String, dryRun: Boolean): RewindPreviewResult {
        ensureOpen(key)
        val res = call("session/rewindFiles", jsonOf("sessionId" to key.id, "userMessageId" to userMessageId, "dryRun" to dryRun))
        return RewindPreviewResult(
            canRewind = res.optBoolean("canRewind", false),
            error = res.str("error"),
            filesChanged = res.optJSONArray("filesChanged")?.strings().orEmpty(),
            insertions = res.optInt("insertions", 0),
            deletions = res.optInt("deletions", 0),
        )
    }

    /**
     * 将 Claude Code 会话历史回退到指定用户消息之前（用于编辑重发与轮次撤销）：
     * - 若前面仍有历史轮次：使用官方 `sdk.forkSession(..., { upToMessageId })` 生成截断后的干净分支并回收旧会话；
     * - 若回退的是第 1 轮：销毁旧会话并按原配置重建全新会话；
     * - 若目标消息尚未落盘（例如上次发送失败）：原地清理本地时间线即可。
     */
    suspend fun revertBeforeTurn(
        key: ConversationKey,
        userMessageId: String,
        userTurnIndex: Int,
        retainedItems: List<TimelineItem>,
        retainedDurations: Map<String, Long>,
        rewindFiles: Boolean = false,
    ): ConversationKey {
        val current = repo.state(key) ?: error("会话不存在")
        val cwd = current.summary.cwd ?: settings.state.value.defaultCwd
        if (rewindFiles && key.id !in connected) {
            ensureOpen(key)
        }
        connected -= key.id
        val res = call(
            "session/revertBefore",
            jsonOf(
                "sessionId" to key.id,
                "beforeMessageId" to userMessageId,
                "userTurnIndex" to userTurnIndex,
                "cwd" to cwd,
                "title" to current.summary.title.takeUnless { it in setOf("新会话", "未命名会话") },
                "rewindFiles" to rewindFiles,
            ),
        )
        return when (res.optString("mode")) {
            "forked" -> {
                val newId = res.getString("sessionId")
                val newKey = key(newId)
                val remappedUserUuids = res.optJSONArray("userMessageUuids")?.strings().orEmpty()
                val oldToNewUserId = mutableMapOf<String, String>()
                var userIdx = 0
                val remappedItems = retainedItems.map { item ->
                    if (item is TimelineItem.UserMessage) {
                        val mappedUuid = remappedUserUuids.getOrNull(userIdx++)
                        if (!mappedUuid.isNullOrBlank()) {
                            oldToNewUserId[item.id] = mappedUuid
                            item.copy(id = mappedUuid)
                        } else item
                    } else item
                }
                val remappedDurations = buildMap {
                    retainedDurations.forEach { (k, v) -> put(oldToNewUserId[k] ?: k, v) }
                }
                connected -= key.id
                sessionCumulativeTokens.remove(key.id)
                claudeTasks.remove(key.id)
                latestPlanFile.remove(key.id)
                settings.setClaudeSessionProvider(newKey.id, current.providerId)
                settings.setClaudeSessionProvider(key.id, null)
                settings.removeConversationOptions(key)
                repo.update(newKey) {
                    ConversationState(
                        summary = current.summary.copy(
                            key = newKey,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        items = remappedItems,
                        loaded = remappedUserUuids.size == retainedItems.count { it is TimelineItem.UserMessage },
                        running = false,
                        activeTurnId = null,
                        providerId = current.providerId,
                        model = current.model,
                        effort = current.effort,
                        permissionMode = current.permissionMode,
                        reasoningSummary = current.reasoningSummary,
                        fastMode = current.fastMode,
                        turnDurations = remappedDurations,
                        arenaGroupId = current.arenaGroupId,
                    )
                }
                repo.persistOptions(newKey)
                repo.remove(key)
                newKey
            }
            "reset" -> {
                connected -= key.id
                sessionCumulativeTokens.remove(key.id)
                claudeTasks.remove(key.id)
                latestPlanFile.remove(key.id)
                settings.setClaudeSessionProvider(key.id, null)
                settings.removeConversationOptions(key)
                val newKey = createSession(
                    cwd = cwd,
                    options = CreateOptions(
                        providerId = current.providerId ?: "",
                        model = current.model,
                        parent = current.summary.parent,
                        allowDelegation = settings.state.value.delegationEnabled && current.summary.parent == null,
                    ),
                    restoredOptions = ConversationOptions.from(current),
                )
                repo.update(newKey) { st ->
                    st.copy(
                        effort = current.effort,
                        permissionMode = current.permissionMode,
                        reasoningSummary = current.reasoningSummary,
                        fastMode = current.fastMode,
                        arenaGroupId = current.arenaGroupId,
                    )
                }
                repo.persistOptions(newKey)
                repo.remove(key)
                newKey
            }
            else -> {
                repo.update(key) { st ->
                    st.copy(
                        items = retainedItems,
                        running = false,
                        activeTurnId = null,
                        turnStartedAt = null,
                        turnDurations = retainedDurations,
                        turnDiff = null,
                        plan = emptyList(),
                        planExplanation = null,
                    )
                }
                key
            }
        }
    }

    /** 读取官方 Claude Code 上下文用量与最大窗口，同步到会话 TokenUsage。 */
    suspend fun refreshContextUsage(key: ConversationKey): JSONObject? {
        if (key.id !in connected) return null
        val res = call("session/contextUsage", jsonOf("sessionId" to key.id, "detail" to "summary"))
        val total = res.optLong("totalTokens", res.optLong("total_tokens", -1L))
        val max = res.optLong("maxTokens", res.optLong("rawMaxTokens", res.optLong("raw_max_tokens", -1L)))
        if (total >= 0L && max > 0L) {
            repo.update(key) { state ->
                val prev = state.tokenUsage
                state.copy(
                    tokenUsage = TokenUsage(
                        totalTokens = total,
                        inputTokens = prev?.inputTokens ?: total,
                        cachedInputTokens = prev?.cachedInputTokens ?: 0L,
                        cacheWriteTokens = prev?.cacheWriteTokens ?: 0L,
                        outputTokens = prev?.outputTokens ?: 0L,
                        reasoningTokens = prev?.reasoningTokens ?: 0L,
                        contextWindow = max,
                        sessionTotalTokens = prev?.sessionTotalTokens ?: sessionCumulativeTokens[key.id],
                    ),
                )
            }
        }
        return res
    }

    /** 官方 `/usage` 实时限额窗口（5h/7d/Opus/Sonnet/OAuth）与会话费用报告。 */
    suspend fun usageReport(key: ConversationKey? = null): EngineUsageReport {
        val targetId = key?.id?.takeIf { it in connected } ?: connected.firstOrNull()
        if (targetId == null && key != null) runCatching { ensureOpen(key) }
        val activeId = key?.id?.takeIf { it in connected } ?: connected.firstOrNull()
        val res = runCatching {
            call("session/usage", jsonOf("sessionId" to activeId, "skipBehaviors" to true))
        }.getOrElse { err ->
            val st = key?.let { repo.state(it) }
            return EngineUsageReport(
                engine = EngineKind.CLAUDE,
                sessionTokens = st?.tokenUsage?.sessionTotalTokens ?: st?.tokenUsage?.totalTokens,
                note = err.message ?: "请先发送一条消息以连接 Claude Code 会话后再查询实时配额",
            )
        }
        val usage = res.optJSONObject("usage") ?: JSONObject()
        val acct = res.optJSONObject("account")
        val session = usage.optJSONObject("session")
        val rl = usage.optJSONObject("rate_limits")
        val windows = mutableListOf<RateLimitSummary>()
        fun addWindow(field: String, label: String) {
            val w = rl?.optJSONObject(field) ?: return
            if (w.isNull("utilization")) return
            val pct = kotlin.math.round(w.optDouble("utilization", -1.0)).toInt()
            if (pct >= 0) {
                val resetRaw = w.str("resets_at")?.let { iso ->
                    iso.replace('T', ' ').substringBefore('.').removeSuffix("Z").take(16)
                }
                windows += RateLimitSummary(label, pct.coerceIn(0, 100), resetRaw)
            }
        }
        addWindow("five_hour", "5 小时滚动配额")
        addWindow("seven_day", "7 天总配额")
        addWindow("seven_day_sonnet", "7 天 Sonnet 配额")
        addWindow("seven_day_opus", "7 天 Opus 配额")
        addWindow("seven_day_oauth_apps", "7 天 OAuth 应用配额")
        val modelRows = mutableListOf<Pair<String, String>>()
        session?.optJSONObject("model_usage")?.let { mu ->
            val keys = mu.keys()
            while (keys.hasNext()) {
                val m = keys.next()
                val entry = mu.optJSONObject(m) ?: continue
                val inT = entry.optLong("inputTokens", entry.optLong("input_tokens"))
                val outT = entry.optLong("outputTokens", entry.optLong("output_tokens"))
                val cost = entry.optDouble("costUSD", entry.optDouble("cost_usd", 0.0))
                modelRows += m to String.format(java.util.Locale.ROOT, "入 %d / 出 %d · $%.4f", inT, outT, cost)
            }
        }
        val plan = usage.str("subscription_type") ?: acct?.str("subscriptionType") ?: acct?.str("apiProvider")
        val account = listOfNotNull(acct?.str("email"), acct?.str("organization")).joinToString(" · ").takeIf { it.isNotBlank() }
        return EngineUsageReport(
            engine = EngineKind.CLAUDE,
            planLabel = plan,
            accountLabel = account,
            rateLimitsAvailable = usage.optBoolean("rate_limits_available", windows.isNotEmpty()),
            windows = windows,
            sessionCostUsd = session?.optDouble("total_cost_usd", Double.NaN)?.takeUnless { it.isNaN() },
            sessionTokens = key?.let { repo.state(it)?.tokenUsage?.sessionTotalTokens ?: repo.state(it)?.tokenUsage?.totalTokens },
            linesAdded = session?.optInt("total_lines_added", 0) ?: 0,
            linesRemoved = session?.optInt("total_lines_removed", 0) ?: 0,
            apiDurationMs = session?.optLong("total_api_duration_ms", 0L) ?: 0L,
            totalDurationMs = session?.optLong("total_duration_ms", 0L) ?: 0L,
            modelBreakdown = modelRows,
            note = if (!usage.optBoolean("rate_limits_available", false)) "当前为 API Key / 第三方服务商会话，无 claude.ai 订阅限额窗口。" else null,
        )
    }

    /** 官方 `/context` 上下文分类拆解（`getContextUsage({detail: 'full'})`）。 */
    suspend fun contextBreakdown(key: ConversationKey, detail: String = "full"): ContextUsageBreakdown {
        ensureOpen(key)
        val res = call("session/contextUsage", jsonOf("sessionId" to key.id, "detail" to detail))
        val total = res.optLong("totalTokens", 0L)
        val max = res.optLong("maxTokens", res.optLong("rawMaxTokens", 200_000L))
        val pct = res.optDouble("percentage", if (max > 0) total * 100.0 / max else 0.0)
        val labelMap = mapOf(
            "system_prompt" to "系统提示词",
            "system_tools" to "内置工具定义",
            "mcp_tools" to "MCP 工具定义",
            "mcp_instructions" to "MCP 指令",
            "mcp_tools_deferred" to "延迟加载 MCP 工具",
            "system_tools_deferred" to "延迟加载系统工具",
            "custom_agents" to "自定义子代理",
            "memory_files" to "记忆文件 (CLAUDE.md)",
            "skills" to "技能定义 (Skills)",
            "messages" to "对话消息与工具结果",
            "autocompact_buffer" to "自动压缩预留区",
            "compact_buffer" to "压缩缓冲区",
            "free_space" to "剩余可用窗口",
        )
        val cats = res.arr("categories").objects().map { c ->
            val cid = c.str("id") ?: c.optString("name")
            ContextCategoryRow(
                id = cid,
                name = labelMap[cid] ?: c.optString("name", cid),
                tokens = c.optLong("tokens", 0L),
                kind = c.optString("kind", "used"),
                colorHex = c.str("color"),
            )
        }
        val gridRows = buildList<List<ContextGridCell>> {
            val rawRows = res.optJSONArray("gridRows") ?: return@buildList
            for (r in 0 until rawRows.length()) {
                val rowArr = rawRows.optJSONArray(r) ?: continue
                val cells = buildList {
                    for (c in 0 until rowArr.length()) {
                        val cellObj = rowArr.optJSONObject(c)
                        if (cellObj != null) {
                            val cid = cellObj.str("categoryId") ?: cellObj.str("id") ?: cellObj.str("category") ?: "used"
                            val kind = when {
                                cid.contains("free", ignoreCase = true) -> "free"
                                cid.contains("buffer", ignoreCase = true) -> "buffer"
                                cid.contains("deferred", ignoreCase = true) -> "deferred"
                                else -> "used"
                            }
                            add(ContextGridCell(cid, cellObj.str("color"), kind))
                        } else {
                            val rawStr = rowArr.optString(c).trim()
                            if (rawStr.isNotEmpty()) {
                                val kind = when {
                                    rawStr.contains("free", ignoreCase = true) || rawStr == "." -> "free"
                                    rawStr.contains("buffer", ignoreCase = true) -> "buffer"
                                    else -> "used"
                                }
                                add(ContextGridCell(rawStr, null, kind))
                            }
                        }
                    }
                }
                if (cells.isNotEmpty()) add(cells)
            }
        }
        val memFiles = res.arr("memoryFiles").objects().map { m ->
            Triple(m.optString("path"), m.optString("type"), m.optLong("tokens", 0L))
        }
        val mcpTools = res.arr("mcpTools").objects().map { t ->
            Triple(t.optString("serverName"), t.optString("name"), t.optLong("tokens", 0L))
        }
        return ContextUsageBreakdown(
            engine = EngineKind.CLAUDE,
            model = res.str("model") ?: repo.state(key)?.model,
            totalTokens = total,
            maxTokens = max,
            percentage = pct,
            categories = cats,
            gridRows = gridRows,
            memoryFiles = memFiles,
            mcpTools = mcpTools,
        )
    }

    /** 官方 `/doctor` 健康体检（`claude doctor`）。 */
    suspend fun diagnosticsReport(cwd: String? = null): EngineDiagnosticsReport {
        val res = call("system/doctor", jsonOf("cwd" to cwd))
        val ok = res.optBoolean("ok", false)
        val cliVer = res.str("version")
        val sdkVer = res.str("sdkVersion")
        val out = listOf(res.optString("stdout"), res.optString("stderr")).filter { it.isNotBlank() }.joinToString("\n")
        return EngineDiagnosticsReport(
            engine = EngineKind.CLAUDE,
            ok = ok,
            version = listOfNotNull(cliVer?.let { "CLI $it" }, sdkVer?.let { "SDK $it" }).joinToString(" · "),
            summary = if (ok) "Claude Code 体检通过" else "Claude Code 体检发现待关注项",
            metrics = listOfNotNull(
                cliVer?.let { "CLI 版本" to it },
                sdkVer?.let { "Agent SDK 版本" to it },
                "活跃会话数" to connected.size.toString(),
            ),
            rawOutput = out.ifBlank { "无额外输出" },
        )
    }

    /** 支持指定任意历史消息节点分叉（`sdk.forkSession(..., {upToMessageId})`）。 */
    suspend fun forkUpTo(key: ConversationKey, upToMessageId: String? = null, title: String? = null): ConversationKey {
        val current = repo.state(key)
        val cwd = current?.summary?.cwd
        val params = jsonOf("sessionId" to key.id, "cwd" to cwd)
        if (!upToMessageId.isNullOrBlank()) params.put("upToMessageId", upToMessageId)
        if (!title.isNullOrBlank()) params.put("title", title)
        val res = call("session/fork", params)
        val newKey = key(res.getString("sessionId"))
        settings.setClaudeSessionProvider(newKey.id, current?.providerId)
        repo.update(newKey) { state ->
            state.copy(
                summary = ConversationSummary(
                    key = newKey,
                    title = title ?: (current?.summary?.title?.takeIf { it.isNotBlank() }?.let { "$it（分支）" }) ?: "分支会话",
                    preview = current?.summary?.preview ?: "",
                    cwd = cwd,
                    updatedAt = System.currentTimeMillis(),
                    parent = key,
                ),
                loaded = false,
                providerId = current?.providerId,
                model = current?.model,
                effort = current?.effort,
                permissionMode = current?.permissionMode ?: settings.state.value.claudePermissionMode,
                reasoningSummary = current?.reasoningSummary,
                fastMode = current?.fastMode,
                additionalDirectories = current?.additionalDirectories.orEmpty(),
                ultracode = current?.ultracode == true,
            )
        }
        return newKey
    }

    /** 将阻塞中的前景任务（Bash / 子代理）转入后台（等价于原生 Ctrl+B）。 */
    suspend fun backgroundTasks(key: ConversationKey, toolUseId: String? = null): Boolean {
        if (key.id !in connected) return false
        val params = jsonOf("sessionId" to key.id)
        if (!toolUseId.isNullOrBlank()) params.put("toolUseId", toolUseId)
        return call("session/backgroundTasks", params).optBoolean("backgrounded", false)
    }

    /** 精准停止指定后台任务（`query.stopTask(taskId)`）。 */
    suspend fun stopTask(key: ConversationKey, taskId: String) {
        ensureOpen(key)
        call("session/stopTask", jsonOf("sessionId" to key.id, "taskId" to taskId))
        repo.update(key) { st ->
            st.copy(backgroundTasks = st.backgroundTasks.map { t ->
                if (t.id == taskId) t.copy(status = "stopped") else t
            })
        }
    }

    /** 列举当前会话内的全部子代理 ID（`sdk.listSubagents`）。 */
    suspend fun listSubagents(key: ConversationKey): List<String> =
        call("session/listSubagents", jsonOf("sessionId" to key.id, "cwd" to repo.state(key)?.summary?.cwd))
            .arr("subagents").strings()

    /** 读取指定子代理的完整消息与工具调用链（`sdk.getSubagentMessages`）。 */
    suspend fun subagentMessages(key: ConversationKey, agentId: String): List<SubagentMessageEntry> {
        val raw = call("session/subagentMessages", jsonOf("sessionId" to key.id, "agentId" to agentId, "cwd" to repo.state(key)?.summary?.cwd))
            .arr("messages").objects()
        return raw.mapNotNull { m ->
            val type = m.optString("type")
            val content = m.optJSONObject("message")?.opt("content")
            val body = when (content) {
                is String -> content
                is JSONArray -> content.objects().joinToString("\n\n") { b ->
                    when (b.optString("type")) {
                        "text" -> b.optString("text")
                        "thinking" -> "【思考】\n${b.optString("thinking")}"
                        "tool_use" -> "【工具 ${b.optString("name")}】\n${b.optJSONObject("input")?.toString(2)?.take(600) ?: ""}"
                        "tool_result" -> "【结果】\n${blockText(b.opt("content")).take(600)}"
                        else -> ""
                    }
                }.trim()
                else -> ""
            }
            if (body.isBlank()) null else SubagentMessageEntry(
                role = type,
                title = when (type) { "user" -> "指令 / 工具结果"; "assistant" -> "子代理回复"; else -> type },
                body = body,
            )
        }
    }

    /** 官方会话归档、恢复与标签（`archiveSession` / `unarchiveSession` / `tagSession`）。 */
    suspend fun archive(key: ConversationKey) {
        call("session/archive", jsonOf("sessionId" to key.id, "cwd" to repo.state(key)?.summary?.cwd))
        connected -= key.id
    }

    suspend fun unarchive(key: ConversationKey) {
        call("session/unarchive", jsonOf("sessionId" to key.id, "cwd" to repo.state(key)?.summary?.cwd))
    }

    suspend fun tag(key: ConversationKey, tag: String?) {
        call("session/tag", jsonOf("sessionId" to key.id, "tag" to (tag ?: JSONObject.NULL), "cwd" to repo.state(key)?.summary?.cwd))
        repo.update(key) { it.copy(summary = it.summary.copy(tag = tag?.takeIf { s -> s.isNotBlank() })) }
    }

    /** 会话内热重载插件（含 Prompt Cache 影响检测）、技能与输出风格。 */
    suspend fun reloadPlugins(key: ConversationKey? = null, holdOnCacheImpact: Boolean = true): PluginReloadResult {
        val params = jsonOf("holdOnCacheImpact" to holdOnCacheImpact, "refuseIfBusy" to true)
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        val res = call("session/reloadPlugins", params)
        val impact = res.optJSONObject("cache_impact")
        return PluginReloadResult(
            held = res.optBoolean("held", false),
            errorCount = res.optInt("error_count", 0),
            pluginCount = res.arr("plugins").length(),
            commandCount = res.arr("commands").length(),
            addedMcps = impact?.arr("mcp_servers_added")?.strings().orEmpty(),
            removedMcps = impact?.arr("mcp_servers_removed")?.strings().orEmpty(),
            lspChange = impact?.str("lsp_tool_change"),
        )
    }

    suspend fun reloadSkills(key: ConversationKey? = null) {
        val params = jsonOf()
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        call("session/reloadSkills", params)
    }

    suspend fun reloadOutputStyles(key: ConversationKey? = null): List<String> {
        val params = jsonOf()
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        val res = call("session/reloadOutputStyles", params)
        val styles = res.arr("output_styles").strings().ifEmpty { res.arr("available_output_styles").strings() }
        if (key != null && styles.isNotEmpty()) repo.update(key) { it.copy(availableOutputStyles = styles) }
        return styles
    }

    suspend fun setMcpPermissionOverride(serverName: String, mode: String?, key: ConversationKey? = null): String? {
        val params = jsonOf("serverName" to serverName, "mode" to (mode ?: JSONObject.NULL))
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        return call("mcp/setPermissionOverride", params).str("warning")
    }

    suspend fun setOutputStyle(key: ConversationKey, style: String) {
        ensureOpen(key)
        call("session/updateSettings", jsonOf("sessionId" to key.id, "source" to "localSettings", "settings" to jsonOf("outputStyle" to style)))
        repo.update(key) { it.copy(outputStyle = style) }
    }

    suspend fun setUltracode(key: ConversationKey, enabled: Boolean) {
        if (key.id in connected) call("session/setUltracode", jsonOf("sessionId" to key.id, "enabled" to enabled))
        repo.update(key) { it.copy(ultracode = enabled) }
    }

    suspend fun addAdditionalDirectory(key: ConversationKey, dir: String) {
        val st = repo.state(key) ?: error("会话不存在")
        check(!st.running) { "请先等待本轮结束后再附加工作区目录" }
        val next = (st.additionalDirectories + dir).distinct()
        runCatching { call("session/close", jsonOf("sessionId" to key.id)) }
        connected -= key.id
        repo.update(key) { it.copy(additionalDirectories = next) }
        repo.notify("已附加工作区目录 $dir（下次发送自动生效）")
    }

    suspend fun mcpServers(key: ConversationKey? = null): List<McpServerSummary> {
        val params = if (key != null && key.id in connected) jsonOf("sessionId" to key.id) else jsonOf()
        return call("mcp/list", params).arr("servers").objects().map { s ->
            val tools = s.optJSONArray("tools")?.length() ?: 0
            val status = s.optString("status", "unknown")
            McpServerSummary(
                name = s.optString("name"),
                status = status,
                toolCount = tools,
                scope = s.str("scope"),
                error = s.str("error"),
                enabled = status != "disabled",
            )
        }
    }

    suspend fun toggleMcpServer(serverName: String, enabled: Boolean, key: ConversationKey? = null) {
        val params = jsonOf("serverName" to serverName, "enabled" to enabled)
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        call("mcp/toggle", params)
    }

    suspend fun reconnectMcpServer(serverName: String, key: ConversationKey? = null) {
        val params = jsonOf("serverName" to serverName)
        if (key != null && key.id in connected) params.put("sessionId", key.id)
        call("mcp/reconnect", params)
    }

    suspend fun agents(key: ConversationKey? = null): List<ClaudeAgentSummary> {
        val params = if (key != null && key.id in connected) jsonOf("sessionId" to key.id) else jsonOf()
        return call("agent/list", params).arr("agents").objects().map { a ->
            ClaudeAgentSummary(a.optString("name"), a.optString("description"), a.str("model"))
        }
    }

    override suspend fun startReview(key: ConversationKey, instructions: String?) {
        val prompt = if (instructions.isNullOrBlank()) {
            "请检查当前工作区的未提交代码改动（git status 与 git diff），重点审查潜在 Bug、边界条件、安全风险和可维护性，给出具体改进建议。"
        } else {
            "请按以下要求进行代码审查：\n$instructions"
        }
        send(key, prompt)
    }

    override suspend fun commands(key: ConversationKey): List<SlashCommand> {
        if (key.id !in connected) return emptyList()
        return call("command/list", jsonOf("sessionId" to key.id)).arr("commands").objects().mapNotNull { cmd ->
            val name = cmd.str("name")?.removePrefix("/") ?: return@mapNotNull null
            SlashCommand(name = name, description = cmd.str("description") ?: "")
        }
    }

    override suspend fun models(key: ConversationKey?): List<ModelOption> = models(key, forceRefresh = false)

    suspend fun models(key: ConversationKey?, forceRefresh: Boolean): List<ModelOption> {
        val customId = if (key == null) settings.state.value.claudeProviderId else repo.state(key)?.providerId
        val provider = customId?.let { settings.state.value.provider(it) ?: error("所选服务商不存在") }
        if (provider != null) {
            val cached = modelCatalogs[customId].orEmpty()
            return provider.models.map { id ->
                cached.selectedModel(id)?.copy(id = id) ?: ModelOption(id, id, provider.name, efforts = listOf("low", "medium", "high", "xhigh", "max"))
            }
        }
        val session = key?.id?.takeIf { it in connected }
            ?: connected.firstOrNull { repo.state(key(it))?.providerId == null }
        val cached = modelCatalogs[null].orEmpty()
        val available = if (session != null) {
            parseClaudeModels(call("model/list", jsonOf("sessionId" to session))).also { modelCatalogs[null] = it }
        } else if (cached.isNotEmpty() && !forceRefresh) {
            cached
        } else {
            runCatching {
                val probeParams = jsonOf(
                    "cwd" to settings.state.value.defaultCwd,
                    "apiKey" to settings.claudeApiKey(),
                    "oauthToken" to settings.claudeOauthToken(),
                    "baseUrl" to settings.state.value.claudeBaseUrl,
                    "refresh" to forceRefresh,
                )
                parseClaudeModels(call("model/list", probeParams))
            }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) modelCatalogs[null] = it }
        }
        return available
    }

    private fun parseClaudeModels(response: JSONObject): List<ModelOption> =
        response.arr("models").objects().map {
            ModelOption(
                it.getString("value"),
                it.optString("displayName", it.optString("value")),
                it.optString("description"),
                efforts = it.arr("supportedEffortLevels").strings(),
                resolvedModel = it.str("resolvedModel"),
                supportsFastMode = it.optBoolean("supportsFastMode"),
                supportsAutoMode = it.optBoolean("supportsAutoMode"),
            )
        }

    override suspend fun setProvider(key: ConversationKey, providerId: String?, model: String?): ConversationKey {
        check(repo.state(key)?.running != true) { "请先结束当前任务再切换服务商" }
        val targetProvider = providerId?.let {
            val p = settings.state.value.provider(it) ?: error("所选自定义服务商不存在")
            require(p.enabled) { "服务商「${p.name}」已停用，请先启用" }
            p
        }
        if (targetProvider != null) repo.syncProvidersNow()
        // 切换服务商需要更换 ANTHROPIC_BASE_URL，关闭当前 SDK 进程后下次发送自动 resume 生效（即使本地 connected 未同步也幂等关闭）。
        runCatching { call("session/close", jsonOf("sessionId" to key.id)) }
        connected -= key.id
        settings.setClaudeSessionProvider(key.id, providerId)
        val nextModel = model?.takeIf { it.isNotBlank() } ?: targetProvider?.models?.firstOrNull()
        val nextWin = inferModelContextWindow(nextModel)
        repo.update(key) {
            it.copy(
                providerId = providerId,
                model = nextModel,
                effort = null,
                fastMode = null,
                permissionMode = if (it.permissionMode == "auto") "default" else it.permissionMode,
                tokenUsage = it.tokenUsage?.let { u -> if (nextWin != null) u.copy(contextWindow = nextWin) else u },
            )
        }
        return key
    }

    override suspend fun setModel(key: ConversationKey, model: String?) {
        check(repo.state(key)?.running != true) { "请先结束当前任务再切换模型" }
        // 重连统一应用模型及其选项，避免多次实时更新只成功一部分（即使本地 connected 未同步也幂等关闭）。
        runCatching { call("session/close", jsonOf("sessionId" to key.id)) }
        connected -= key.id
        val nextWin = inferModelContextWindow(model)
        repo.update(key) {
            it.copy(
                model = model,
                effort = null,
                fastMode = null,
                permissionMode = if (it.permissionMode == "auto") "default" else it.permissionMode,
                tokenUsage = it.tokenUsage?.let { u -> if (nextWin != null) u.copy(contextWindow = nextWin) else u },
            )
        }
    }

    override suspend fun setEffort(key: ConversationKey, effort: String?) {
        require(effort == null || effort in models(key).selectedModel(repo.state(key)?.model)?.reasoningEfforts().orEmpty()) { "当前模型不支持所选推理强度" }
        if (key.id in connected) call("session/setEffort", JSONObject().put("sessionId", key.id).put("effort", effort ?: JSONObject.NULL))
        repo.update(key) { it.copy(effort = effort) }
    }

    suspend fun setReasoningSummary(key: ConversationKey, summary: String) {
        require(summary in setOf("auto", "none")) { "Claude Code 仅支持显示或隐藏推理摘要" }
        if (key.id in connected) call("session/setThinkingDisplay", jsonOf("sessionId" to key.id,
            "display" to if (summary == "none") "omitted" else "summarized"))
        repo.update(key) { it.copy(reasoningSummary = summary) }
    }

    suspend fun setFastMode(key: ConversationKey, enabled: Boolean?) {
        require(enabled != true || models(key).selectedModel(repo.state(key)?.model)?.supportsFastMode == true) { "当前模型不支持快速模式" }
        if (key.id in connected) call("session/setFastMode", jsonOf("sessionId" to key.id).put("enabled", enabled ?: JSONObject.NULL))
        repo.update(key) { it.copy(fastMode = enabled) }
    }

    private fun blockText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> content.objects().joinToString("\n") { block ->
            when (block.optString("type")) { "text" -> block.optString("text"); "image" -> "[图片]"; else -> "" }
        }
        else -> ""
    }

    private fun toolItem(block: JSONObject, live: Boolean): TimelineItem {
        val id = block.optString("id")
        val input = block.optJSONObject("input") ?: JSONObject()
        return when (block.optString("name")) {
            "Bash" -> {
                if (live) commandStartedAt.putIfAbsent(id, System.currentTimeMillis())
                TimelineItem.CommandRun(id, input.optString("command"), null, "", null, ItemStatus.RUNNING)
            }
            "Edit", "MultiEdit", "Write" -> TimelineItem.FileEdit(id, fileDiffs(block.optString("name"), input), ItemStatus.RUNNING)
            "show_widget", "visualize", "render_artifact", "create_artifact" ->
                TimelineItem.ToolCall(id, block.optString("name"), input.toString(), null, ItemStatus.RUNNING)
            else -> TimelineItem.ToolCall(id, block.optString("name"), input.pretty(), null, ItemStatus.RUNNING)
        }
    }

    /** 从 Claude 编辑类工具的输入生成 diff 预览。 */
    private fun fileDiffs(tool: String, input: JSONObject): List<FileDiff> {
        val path = input.optString("file_path")
        return when (tool) {
            "Write" -> listOf(FileDiff(path, "add", unifiedDiff(path, "", input.optString("content"))))
            "Edit" -> listOf(FileDiff(path, "update", unifiedDiff(path, input.optString("old_string"), input.optString("new_string"))))
            "MultiEdit" -> input.arr("edits").objects().map { FileDiff(path, "update", unifiedDiff(path, it.optString("old_string"), it.optString("new_string"))) }
            else -> emptyList()
        }
    }

    private fun handleMessage(key: ConversationKey, message: JSONObject, live: Boolean) {
        // 子代理内部的消息不进入主时间线，但直连模式仍需将其模型请求的真实 Token 逐条记入用量账本。
        val isSubagentMsg = !message.isNull("parent_tool_use_id") && message.has("parent_tool_use_id")
        if (isSubagentMsg) {
            if (live && message.optString("type") == "assistant") {
                val body = message.optJSONObject("message")
                val usage = body?.optJSONObject("usage")
                if (body != null && usage != null) {
                    recordClaudeAssistantUsage(key, body, usage, isSubagent = true)
                }
            }
            return
        }
        when (message.optString("type")) {
            "system" -> when (message.optString("subtype")) {
                "init" -> {
                    message.str("model")?.let { model -> repo.update(key) { it.copy(model = model) } }
                    message.str("permissionMode")?.takeIf { it in CLAUDE_PERMISSION_MODES }?.let { pm ->
                        repo.update(key) { it.copy(permissionMode = pm) }
                    }
                    message.arr("mcp_servers").objects().firstOrNull { it.optString("name") == "galiais" }?.let { server ->
                        android.util.Log.i("GALIAIS", "Claude 委派 MCP 状态：" + server.optString("status"))
                        if (live && server.optString("status") != "connected")
                            repo.upsertItem(key, TimelineItem.Notice("mcp-galiais-${key.id}", "跨 Agent 委派工具未能启动（${server.optString("status")}）", error = true))
                    }
                }
                "status" -> {
                    message.str("permissionMode")?.takeIf { it in CLAUDE_PERMISSION_MODES }?.let { pm ->
                        repo.update(key) { it.copy(permissionMode = pm) }
                    }
                    if (live && message.str("status") == "compacting") {
                        repo.upsertItem(key, TimelineItem.Notice("compacting-${key.id}", "正在自动压缩上下文…"))
                    }
                }
                "permission_denied" -> if (live) {
                    val tool = message.optString("tool_name", "工具")
                    val reasonType = when (message.str("decision_reason_type")) {
                        "classifier" -> "AI 自动审校拦截"
                        "rule" -> "权限规则拦截"
                        "mode" -> "当前权限模式拦截"
                        else -> "权限拦截"
                    }
                    val detail = message.str("decision_reason")?.take(140)?.let { "：$it" } ?: ""
                    repo.upsertItem(key, TimelineItem.Notice("denied-${message.optString("tool_use_id", newId())}", "$reasonType（$tool）$detail", error = true))
                }
                "compact_boundary" -> {
                    val meta = message.optJSONObject("compact_metadata")
                    val triggerLabel = if (meta?.optString("trigger") == "auto") "上下文已自动压缩" else "上下文已压缩"
                    val pre = meta?.optLong("pre_tokens", 0L) ?: 0L
                    val post = meta?.optLong("post_tokens", 0L) ?: 0L
                    fun fmtK(n: Long) = if (n >= 1000L) "%.1fk".format(n / 1000.0) else n.toString()
                    val label = when {
                        pre > 0L && post > 0L -> "$triggerLabel（${fmtK(pre)} → ${fmtK(post)} tokens）"
                        pre > 0L -> "$triggerLabel（压缩前 ${fmtK(pre)} tokens）"
                        else -> triggerLabel
                    }
                    repo.upsertItem(key, TimelineItem.Notice(message.optString("uuid", newId()), label))
                    if (post > 0L) {
                        repo.update(key) { st ->
                            val prev = st.tokenUsage
                            st.copy(tokenUsage = TokenUsage(
                                totalTokens = post,
                                inputTokens = post,
                                cachedInputTokens = 0L,
                                outputTokens = 0L,
                                contextWindow = prev?.contextWindow ?: inferModelContextWindow(st.model),
                                sessionTotalTokens = prev?.sessionTotalTokens ?: sessionCumulativeTokens[key.id],
                            ))
                        }
                    }
                    if (live && key.id in connected) {
                        repo.scope.launch { runCatching { refreshContextUsage(key) } }
                    }
                }
                "task_started", "task_progress", "task_notification" -> {
                    val taskId = message.str("task_id") ?: message.str("taskId") ?: message.str("id") ?: return
                    val desc = message.str("description") ?: message.str("summary") ?: message.str("title") ?: message.str("task_type") ?: "后台任务"
                    val cmd = message.str("command") ?: message.str("last_tool_name") ?: message.str("tool_name") ?: ""
                    val nextStatus = when (message.optString("subtype")) {
                        "task_started" -> "running"
                        "task_progress" -> message.str("status") ?: "running"
                        else -> message.str("status") ?: "completed"
                    }
                    repo.update(key) { st ->
                        val prevList = st.backgroundTasks
                        val entry = BackgroundTaskEntry(
                            id = taskId,
                            label = desc,
                            command = cmd,
                            status = nextStatus,
                            cwd = message.str("output_file") ?: st.summary.cwd,
                        )
                        val updated = if (prevList.any { it.id == taskId }) {
                            prevList.map { if (it.id == taskId) entry else it }
                        } else prevList + entry
                        st.copy(backgroundTasks = updated.takeLast(20))
                    }
                    if (live && message.optString("subtype") == "task_notification") {
                        val summaryLine = message.str("summary")?.take(140) ?: desc
                        repo.upsertItem(key, TimelineItem.Notice("task-$taskId", "后台任务已${if (nextStatus == "failed") "失败" else "完成"}：$summaryLine", error = nextStatus == "failed"))
                    }
                }
            }
            "stream_event" -> if (live) onStream(key, message.optJSONObject("event") ?: return)
            "assistant" -> {
                val body = message.optJSONObject("message") ?: return
                body.optJSONObject("usage")?.let { u ->
                    updateClaudeUsage(key, u)
                    if (live) recordClaudeAssistantUsage(key, body, u, isSubagent = false)
                }
                val messageId = body.optString("id", message.optString("uuid"))
                body.arr("content").objects().forEachIndexed { index, block ->
                    val id = "$messageId:$index"
                    when (block.optString("type")) {
                        "text" -> repo.upsertItem(key, TimelineItem.AgentMessage(id, block.optString("text")))
                        "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let { thinkingText ->
                            val prev = repo.findItem(key, id) as? TimelineItem.Reasoning
                            val now = System.currentTimeMillis()
                            repo.upsertItem(key, TimelineItem.Reasoning(
                                id = id,
                                text = thinkingText,
                                streaming = false,
                                startedAtMs = prev?.startedAtMs,
                                completedAtMs = prev?.completedAtMs ?: if (live) now else null,
                            ))
                        }
                        "tool_use" -> {
                            val tName = block.optString("name")
                            val tInput = block.optJSONObject("input")
                            val toolUseId = block.optString("id")
                            when {
                                tName == "TodoWrite" && tInput != null -> {
                                    val todos = tInput.arr("todos").objects().mapNotNull { t ->
                                        val label = t.str("content") ?: t.str("activeForm") ?: return@mapNotNull null
                                        PlanStep(
                                            step = label,
                                            status = normalizePlanStepStatus(t.optString("status", "pending")),
                                            id = t.str("id"),
                                            activeForm = t.str("activeForm"),
                                            priority = t.str("priority"),
                                        )
                                    }
                                    claudeTasks[key.id] = todos.toMutableList()
                                    if (todos.isNotEmpty()) repo.update(key) { it.copy(plan = todos, planExplanation = "Claude Code 任务清单") }
                                }
                                tName == "TaskCreate" && tInput != null -> {
                                    val subject = tInput.str("subject") ?: tInput.str("description") ?: tInput.str("content")
                                    if (!subject.isNullOrBlank()) {
                                        val list = claudeTasks.getOrPut(key.id) { mutableListOf() }
                                        val nextId = tInput.str("id") ?: (list.size + 1).toString()
                                        val step = PlanStep(
                                            step = subject,
                                            status = normalizePlanStepStatus(tInput.optString("status", "pending")),
                                            id = nextId,
                                            activeForm = tInput.str("activeForm"),
                                            priority = tInput.str("priority"),
                                        )
                                        val idx = list.size
                                        list += step
                                        if (toolUseId.isNotBlank()) pendingTaskCreateTool[toolUseId] = key.id to idx
                                        repo.update(key) { it.copy(plan = list.toList(), planExplanation = "Claude Code 任务清单") }
                                    }
                                }
                                tName == "TaskUpdate" && tInput != null -> {
                                    val taskId = tInput.str("taskId") ?: tInput.str("id")
                                    val rawStatus = tInput.str("status")
                                    val list = claudeTasks.getOrPut(key.id) { repo.state(key)?.plan?.toMutableList() ?: mutableListOf() }
                                    val idx = when {
                                        taskId == null -> -1
                                        else -> list.indexOfFirst { it.id == taskId }.takeIf { it >= 0 }
                                            ?: taskId.toIntOrNull()?.minus(1)?.takeIf { it in list.indices }
                                            ?: -1
                                    }
                                    if (idx >= 0) {
                                        if (rawStatus.equals("deleted", ignoreCase = true)) {
                                            list.removeAt(idx)
                                        } else {
                                            val old = list[idx]
                                            list[idx] = old.copy(
                                                step = tInput.str("subject") ?: tInput.str("content") ?: old.step,
                                                status = rawStatus?.let(::normalizePlanStepStatus) ?: old.status,
                                                activeForm = tInput.str("activeForm") ?: old.activeForm,
                                                priority = tInput.str("priority") ?: old.priority,
                                            )
                                        }
                                        repo.update(key) { it.copy(plan = list.toList(), planExplanation = if (list.isEmpty()) null else "Claude Code 任务清单") }
                                    }
                                }
                                tName == "EnterPlanMode" -> {
                                    repo.update(key) { it.copy(permissionMode = "plan") }
                                }
                                tName == "ExitPlanMode" -> {
                                    val planMd = tInput?.str("plan") ?: latestPlanFile[key.id]?.second
                                    val planPath = tInput?.str("planFilePath") ?: latestPlanFile[key.id]?.first
                                    if (!planMd.isNullOrBlank()) {
                                        repo.upsertItem(key, TimelineItem.Plan(
                                            id = "claude-plan-${toolUseId.ifBlank { id }}",
                                            text = planMd,
                                            title = extractPlanTitle(planMd, planPath),
                                            filePath = planPath,
                                            status = ItemStatus.COMPLETED,
                                        ))
                                    }
                                    repo.update(key) { it.copy(permissionMode = settings.state.value.claudePermissionMode) }
                                }
                                tName in setOf("Write", "Edit") && tInput != null -> {
                                    val path = tInput.str("file_path") ?: tInput.str("path")
                                    if (!path.isNullOrBlank() && isClaudePlanFile(path)) {
                                        val updatedContent = tInput.str("content") ?: run {
                                            val oldStr = tInput.str("old_string")
                                            val newStr = tInput.str("new_string")
                                            val prev = latestPlanFile[key.id]?.takeIf { it.first == path }?.second
                                            when {
                                                prev != null && oldStr != null && newStr != null -> {
                                                    if (tInput.optBoolean("replace_all", false)) prev.replace(oldStr, newStr)
                                                    else prev.replaceFirst(oldStr, newStr)
                                                }
                                                !newStr.isNullOrBlank() && prev == null -> newStr
                                                else -> null
                                            }
                                        }
                                        if (!updatedContent.isNullOrBlank()) {
                                            latestPlanFile[key.id] = path to updatedContent
                                            repo.upsertItem(key, TimelineItem.Plan(
                                                id = "claude-plan-file-${path.hashCode()}",
                                                text = updatedContent,
                                                title = extractPlanTitle(updatedContent, path),
                                                filePath = path,
                                                status = if (live) ItemStatus.RUNNING else ItemStatus.COMPLETED,
                                                requestFeedback = live,
                                                artifactType = "implementation_plan",
                                            ))
                                        }
                                    }
                                }
                            }
                            if (tName == "mcp__galiais__$DELEGATE_TO_CODEX" || tName == "mcp__galiais__delegate_to_agent") {
                                val targetId = tInput?.str("agent_id")?.takeIf { it.isNotBlank() } ?: "codex"
                                if (!live) repo.upsertItem(key, TimelineItem.Delegation(toolUseId, EngineKind.of(targetId),
                                    tInput?.optString("task") ?: "", null, null, ItemStatus.COMPLETED))
                            } else if (tName !in setOf("TodoWrite", "TaskCreate", "TaskUpdate")) {
                                repo.upsertItem(key, toolItem(block, live))
                            }
                        }
                    }
                }
            }
            "user" -> {
                val body = message.optJSONObject("message") ?: return
                val content = body.opt("content")
                if (content is String) { repo.upsertItem(key, TimelineItem.UserMessage(message.optString("uuid", newId()), content)); return }
                val blocks = (content as? JSONArray)?.objects() ?: return
                val text = blocks.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
                if (text.isNotBlank() && message.optBoolean("isSynthetic").not()) repo.upsertItem(key, TimelineItem.UserMessage(message.optString("uuid", newId()), text))
                for (result in blocks.filter { it.optString("type") == "tool_result" }) {
                    val output = blockText(result.opt("content"))
                    val status = if (result.optBoolean("is_error")) ItemStatus.FAILED else ItemStatus.COMPLETED
                    val toolUseId = result.optString("tool_use_id")
                    pendingTaskCreateTool.remove(toolUseId)?.let { (sid, idx) ->
                        val assignedId = Regex("""(?:Task\s*#|id["']?\s*[:=]\s*["']?)([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                            .find(output)?.groupValues?.getOrNull(1)
                        val list = claudeTasks[sid]
                        if (assignedId != null && list != null && idx in list.indices) {
                            list[idx] = list[idx].copy(id = assignedId)
                            repo.update(key) { it.copy(plan = list.toList()) }
                        }
                    }
                    val existing = repo.findItem(key, toolUseId) ?: continue
                    repo.upsertItem(key, when (existing) {
                        is TimelineItem.CommandRun -> {
                            val dur = commandStartedAt.remove(existing.id)?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                            existing.copy(
                                output = output.takeLast(200_000),
                                status = status,
                                exitCode = if (status == ItemStatus.FAILED) existing.exitCode else 0,
                                durationMs = dur ?: existing.durationMs,
                            )
                        }
                        is TimelineItem.FileEdit -> existing.copy(status = status)
                        is TimelineItem.ToolCall -> existing.copy(result = output.take(20_000), status = status)
                        is TimelineItem.Delegation -> existing.copy(result = output.take(20_000), status = status)
                        else -> existing
                    })
                }
            }
            "prompt_suggestion" -> {
                val pred = message.str("suggestion") ?: message.str("text")
                if (!pred.isNullOrBlank()) repo.update(key) { it.copy(nextPromptPrediction = pred) }
            }
            "result" -> {
                val resUsage = message.optJSONObject("usage")
                // 注意：Claude SDK 的 `result.usage` 是本轮所有工具调用子回合的累加总和，绝不能覆写单次上下文窗口占用！
                // 这里只提取 modelUsage 中的权威 contextWindow 与累计会话消耗，并在轮次结束时刷新官方 getContextUsage。
                val modelUsageObj = message.optJSONObject("modelUsage")
                var reportedWindow: Long? = null
                if (modelUsageObj != null) {
                    val keys = modelUsageObj.keys()
                    while (keys.hasNext()) {
                        val entry = modelUsageObj.optJSONObject(keys.next()) ?: continue
                        val cw = entry.optLong("contextWindow", 0L)
                        if (cw > 0L) reportedWindow = if (reportedWindow == null) cw else maxOf(reportedWindow, cw)
                    }
                }
                val turnIn = resUsage?.let {
                    it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens")
                } ?: 0L
                val turnOut = resUsage?.optLong("output_tokens") ?: 0L
                val turnSum = turnIn + turnOut
                if (live && turnSum > 0L) {
                    sessionCumulativeTokens[key.id] = (sessionCumulativeTokens[key.id] ?: 0L) + turnSum
                }
                if (reportedWindow != null || turnSum > 0L) {
                    repo.update(key) { st ->
                        val prev = st.tokenUsage
                        val win = reportedWindow ?: prev?.contextWindow ?: inferModelContextWindow(st.model)
                        val cum = sessionCumulativeTokens[key.id] ?: prev?.sessionTotalTokens
                        val nextUsage = if (prev != null) {
                            prev.copy(contextWindow = win, sessionTotalTokens = cum)
                        } else if (turnSum > 0L) {
                            TokenUsage(
                                totalTokens = turnSum,
                                inputTokens = turnIn,
                                cachedInputTokens = resUsage?.optLong("cache_read_input_tokens") ?: 0L,
                                cacheWriteTokens = resUsage?.optLong("cache_creation_input_tokens") ?: 0L,
                                outputTokens = turnOut,
                                contextWindow = win,
                                sessionTotalTokens = cum,
                            )
                        } else null
                        if (nextUsage != null) st.copy(tokenUsage = nextUsage) else st
                    }
                }
                val failed = message.optBoolean("is_error") || message.optString("subtype") != "success"
                val billedThisTurn = turnBilledMsgIds.remove(key.id)?.size ?: 0
                if (live && repo.state(key)?.providerId == null) {
                    val st = repo.state(key)
                    val prevUsage = st?.tokenUsage
                    val apiDurMs = message.optLong("duration_api_ms", 0L).takeIf { it > 0L }
                    val wallDurMs = message.optLong("duration_ms", 0L).takeIf { it > 0L }
                        ?: st?.turnStartedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                        ?: 0L
                    val claudeDur = apiDurMs ?: wallDurMs
                    val ttftCnt = turnTtftCount.remove(key.id) ?: 0
                    val avgTurnTtft = if (ttftCnt > 0) ((turnTtftSum.remove(key.id) ?: 0L) / ttftCnt) else { turnTtftSum.remove(key.id); 0L }
                    val genMs = (turnGenMsSum.remove(key.id) ?: 0L).coerceAtMost(claudeDur)
                    msgStartedAt.remove(key.id); msgFirstTokenAt.remove(key.id); msgLastTokenAt.remove(key.id)
                    val isApiKeyPayg = settings.state.value.hasClaudeKey && !settings.state.value.hasClaudeOauthToken
                    val isInterrupted = message.optString("subtype").contains("interrupt", ignoreCase = true) ||
                        message.optString("subtype").contains("cancel", ignoreCase = true)
                    // 本轮已按 assistant 逐条记账时，成功回合不再重复写入整轮总和；仅当本轮未记过任何请求或失败/中断时兜底入账
                    if (billedThisTurn == 0) {
                        val inTok = if (resUsage != null) {
                            resUsage.optLong("input_tokens") + resUsage.optLong("cache_read_input_tokens") + resUsage.optLong("cache_creation_input_tokens")
                        } else (prevUsage?.inputTokens ?: 0L)
                        val outTok = resUsage?.optLong("output_tokens") ?: (prevUsage?.outputTokens ?: 0L)
                        val reportedCost = message.optDouble("total_cost_usd", -1.0).takeIf { it >= 0.0 && isApiKeyPayg }
                        repo.hub.recordDirectUsage(
                            agent = "claude",
                            model = st?.model ?: "claude",
                            inputTokens = inTok,
                            outputTokens = outTok,
                            cacheReadTokens = resUsage?.optLong("cache_read_input_tokens") ?: (prevUsage?.cachedInputTokens ?: 0L),
                            cacheWriteTokens = resUsage?.optLong("cache_creation_input_tokens") ?: (prevUsage?.cacheWriteTokens ?: 0L),
                            durationMs = claudeDur,
                            ttftMs = avgTurnTtft,
                            genMs = genMs,
                            streamed = ttftCnt > 0 || genMs > 0L,
                            exact = true,
                            contextUsed = prevUsage?.totalTokens ?: 0L,
                            contextWindow = reportedWindow ?: prevUsage?.contextWindow,
                            billingKind = if (isApiKeyPayg) "payg" else "plan",
                            costUsd = reportedCost,
                            status = when {
                                isInterrupted -> 499
                                failed -> 500
                                else -> 200
                            },
                            error = if (failed || isInterrupted) message.optString("subtype") else "",
                            providerName = "Anthropic 官方",
                            sessionId = key.id,
                        )
                    } else if (failed || isInterrupted) {
                        repo.hub.recordDirectUsage(
                            agent = "claude",
                            model = st?.model ?: "claude",
                            inputTokens = 0L,
                            outputTokens = 0L,
                            durationMs = 1L,
                            billingKind = if (isApiKeyPayg) "payg" else "plan",
                            status = if (isInterrupted) 499 else 500,
                            error = message.optString("subtype").ifBlank { if (isInterrupted) "用户中断" else "error" },
                            providerName = "Anthropic 官方",
                            sessionId = key.id,
                        )
                    }
                } else {
                    turnTtftSum.remove(key.id); turnTtftCount.remove(key.id); turnGenMsSum.remove(key.id)
                    msgStartedAt.remove(key.id); msgFirstTokenAt.remove(key.id); msgLastTokenAt.remove(key.id)
                }
                if (failed && live) {
                    val detail = message.optJSONArray("errors")?.strings()?.joinToString("\n")?.ifBlank { null } ?: message.str("result") ?: message.optString("subtype")
                    // SDK 常把错误同时作为一条助手文字发出；已显示过就不重复。
                    val shown = repo.state(key)?.items?.lastOrNull { it is TimelineItem.AgentMessage }?.let { (it as TimelineItem.AgentMessage).text.trim() == detail.trim() } == true
                    if (!shown) repo.upsertItem(key, TimelineItem.Notice("result-" + message.optString("uuid", newId()), "Claude Code 本轮失败：$detail", error = true))
                }
                if (live) {
                    streaming.remove(key.id)
                    val durMs = message.optLong("duration_ms", -1L).takeIf { it > 0L }
                    val endedAt = System.currentTimeMillis()
                    repo.update(key) { state ->
                        val resolvedDur = durMs ?: state.turnStartedAt?.let { (endedAt - it).coerceAtLeast(0L) }
                        val lastUser = state.items.lastOrNull { it is TimelineItem.UserMessage } as? TimelineItem.UserMessage
                        val nextDurs = if (resolvedDur != null && lastUser != null) state.turnDurations + (lastUser.id to resolvedDur) else state.turnDurations
                        state.copy(
                            running = false,
                            turnStartedAt = null,
                            turnDurations = nextDurs,
                            items = state.items.map {
                                when {
                                    it is TimelineItem.AgentMessage -> it.copy(streaming = false)
                                    it is TimelineItem.Reasoning -> it.copy(streaming = false, completedAtMs = it.completedAtMs ?: endedAt)
                                    it is TimelineItem.UserMessage && lastUser != null && it.id == lastUser.id && resolvedDur != null -> it.copy(durationMs = resolvedDur)
                                    else -> it
                                }
                            },
                        )
                    }
                    turnWaiters.remove(key.id)?.let { waiter ->
                        if (failed) waiter.completeExceptionally(IllegalStateException("Claude Code 任务失败")) else waiter.complete(message.optString("result"))
                    }
                    if (key.id in connected) {
                        repo.scope.launch { runCatching { refreshContextUsage(key) } }
                    }
                }
            }
        }
    }

    private fun updateClaudeUsage(key: ConversationKey, usage: JSONObject) {
        val input = usage.optLong("input_tokens")
        val cacheRead = usage.optLong("cache_read_input_tokens")
        val cacheWrite = usage.optLong("cache_creation_input_tokens")
        val output = usage.optLong("output_tokens")
        val totalIn = input + cacheRead + cacheWrite
        val total = totalIn + output
        if (total <= 0L) return
        repo.update(key) {
            it.copy(
                tokenUsage = TokenUsage(
                    totalTokens = total,
                    inputTokens = totalIn,
                    cachedInputTokens = cacheRead,
                    cacheWriteTokens = cacheWrite,
                    outputTokens = output,
                    contextWindow = it.tokenUsage?.contextWindow ?: inferModelContextWindow(it.model),
                    sessionTotalTokens = it.tokenUsage?.sessionTotalTokens ?: sessionCumulativeTokens[key.id],
                ),
            )
        }
    }

    private fun recordClaudeAssistantUsage(key: ConversationKey, body: JSONObject, usage: JSONObject, isSubagent: Boolean) {
        val st = repo.state(key)
        if (st?.providerId != null) return
        val msgId = body.str("id")?.takeIf { it.isNotBlank() } ?: return
        val billed = turnBilledMsgIds.getOrPut(key.id) { mutableSetOf() }
        if (!billed.add(msgId)) return
        val input = usage.optLong("input_tokens")
        val cacheRead = usage.optLong("cache_read_input_tokens")
        val cacheWrite = usage.optLong("cache_creation_input_tokens")
        val output = usage.optLong("output_tokens")
        val totalIn = input + cacheRead + cacheWrite
        if (totalIn <= 0L && output <= 0L) return
        val timing = if (isSubagent) null else msgTimingById.remove(msgId)
        val now = System.currentTimeMillis()
        val fallbackDur = if (isSubagent) 1L else (msgStartedAt[key.id]?.let { (now - it).coerceAtLeast(1L) } ?: 1L)
        val durMs = timing?.first ?: fallbackDur
        val ttftMs = timing?.second ?: 0L
        val genMs = timing?.third ?: 0L
        val isApiKeyPayg = settings.state.value.hasClaudeKey && !settings.state.value.hasClaudeOauthToken
        val modelName = body.str("model")?.takeIf { it.isNotBlank() } ?: st?.model ?: "claude"
        repo.hub.recordDirectUsage(
            agent = "claude",
            model = modelName,
            inputTokens = totalIn,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            durationMs = durMs,
            ttftMs = ttftMs,
            genMs = genMs,
            streamed = ttftMs > 0L || genMs > 0L,
            exact = true,
            contextUsed = totalIn + output,
            contextWindow = st?.tokenUsage?.contextWindow ?: inferModelContextWindow(modelName),
            billingKind = if (isApiKeyPayg) "payg" else "plan",
            status = 200,
            providerName = "Anthropic 官方",
            sessionId = key.id,
        )
    }

    private fun onStream(key: ConversationKey, event: JSONObject) {
        when (event.optString("type")) {
            "message_start" -> {
                event.optJSONObject("message")?.optString("id")?.let { streaming[key.id] = it }
                msgStartedAt[key.id] = System.currentTimeMillis()
                msgFirstTokenAt.remove(key.id)
                msgLastTokenAt.remove(key.id)
            }
            "message_stop" -> {
                val now = System.currentTimeMillis()
                val msgId = streaming[key.id]
                val started = msgStartedAt.remove(key.id)
                val first = msgFirstTokenAt.remove(key.id)
                val last = msgLastTokenAt.remove(key.id)
                val durMs = if (started != null) (now - started).coerceAtLeast(1L) else 1L
                val ttftMs = if (started != null && first != null && first >= started) (first - started).coerceAtMost(durMs) else 0L
                val genMs = if (first != null && last != null && last > first) (last - first).coerceAtMost(durMs) else 0L
                if (!msgId.isNullOrBlank()) {
                    if (msgTimingById.size > 64) msgTimingById.clear()
                    msgTimingById[msgId] = Triple(durMs, ttftMs, genMs)
                }
                if (ttftMs > 0L) {
                    turnTtftSum[key.id] = (turnTtftSum[key.id] ?: 0L) + ttftMs
                    turnTtftCount[key.id] = (turnTtftCount[key.id] ?: 0) + 1
                }
                if (genMs > 0L) {
                    turnGenMsSum[key.id] = (turnGenMsSum[key.id] ?: 0L) + genMs
                }
            }
            "content_block_delta" -> {
                val messageId = streaming[key.id] ?: return
                val id = "$messageId:${event.optInt("index")}"
                val delta = event.optJSONObject("delta") ?: return
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val chunk = delta.optString("text")
                        if (chunk.isEmpty()) return
                        val now = System.currentTimeMillis()
                        msgFirstTokenAt.putIfAbsent(key.id, now)
                        msgLastTokenAt[key.id] = now
                        // 仅当存在流式推理块时才执行一次完结更新，避免每个 Token 都额外触发一次 StateFlow 重组
                        if (repo.state(key)?.items?.any { it is TimelineItem.Reasoning && it.streaming } == true) {
                            repo.update(key) { st ->
                                st.copy(items = st.items.map { if (it is TimelineItem.Reasoning && it.streaming) it.copy(streaming = false, completedAtMs = it.completedAtMs ?: now) else it })
                            }
                        }
                        repo.appendStreamingDelta(key, id, chunk, "agent") { TimelineItem.AgentMessage(id, "", true) }
                    }
                    "thinking_delta" -> {
                        val chunk = delta.optString("thinking")
                        if (chunk.isEmpty()) return
                        val now = System.currentTimeMillis()
                        msgFirstTokenAt.putIfAbsent(key.id, now)
                        msgLastTokenAt[key.id] = now
                        repo.appendStreamingDelta(key, id, chunk, "reasoning") { TimelineItem.Reasoning(id, "", true, startedAtMs = now) }
                    }
                }
            }
        }
    }

    override fun onEvent(event: EngineEvent) {
        val params = event.params
        val sessionKey = params.str("sessionId")?.let(::key)
        if (event.isRequest) {
            val id = event.data.get("id")
            if (event.method != "galiais/userRequest") { runtime.reject(kind, id, -32601, "Solbraid 不支持此请求"); return }
            val request: PendingRequest? = when (params.optString("kind")) {
                "permission" -> {
                    val tool = params.optString("toolName")
                    val input = params.optJSONObject("input") ?: JSONObject()
                    if (tool == "AskUserQuestion" && input.optJSONArray("questions") != null) {
                        val qs = input.arr("questions").objects().mapIndexed { idx, q ->
                            val opts = q.optJSONArray("options")?.objects().orEmpty()
                            Question(
                                id = q.optString("question").ifBlank { "q_$idx" },
                                header = q.optString("header"),
                                text = q.optString("question"),
                                options = opts.map { it.optString("label") },
                                allowOther = true,
                                secret = false,
                                optionDescriptions = opts.map { it.optString("description") },
                                multiSelect = q.optBoolean("multiSelect", false),
                                optionPreviews = opts.map { it.optString("markdown").ifBlank { it.optString("preview") } },
                            )
                        }
                        PendingRequest.UserInput(
                            engine = kind,
                            requestId = id,
                            conversation = sessionKey,
                            questions = qs,
                            isClaudeAskUserQuestion = true,
                            rawClaudeInput = input,
                            protocol = "claude_ask",
                        )
                    } else {
                        val suggestions = params.optJSONArray("suggestions")?.takeIf { it.length() > 0 }?.toString()
                        val isExitPlan = tool == "ExitPlanMode"
                        val planMd = if (isExitPlan) input.str("plan") ?: sessionKey?.let { latestPlanFile[it.id]?.second } else null
                        val planPath = if (isExitPlan) input.str("planFilePath") ?: sessionKey?.let { latestPlanFile[it.id]?.first } else null
                        val allowedPrompts = if (isExitPlan) {
                            input.optJSONArray("allowedPrompts")?.objects().orEmpty().mapNotNull { ap ->
                                ap.str("prompt") ?: ap.str("tool")
                            }
                        } else emptyList()
                        if (isExitPlan && sessionKey != null && !planMd.isNullOrBlank()) {
                            repo.upsertItem(sessionKey, TimelineItem.Plan(
                                id = "claude-plan-$id",
                                text = planMd,
                                title = extractPlanTitle(planMd, planPath),
                                filePath = planPath,
                                status = ItemStatus.RUNNING,
                                requestFeedback = true,
                                artifactType = "implementation_plan",
                            ))
                        }
                        PendingRequest.ToolApproval(
                            engine = kind,
                            requestId = id,
                            conversation = sessionKey,
                            toolName = tool,
                            input = input,
                            reason = params.str("decisionReason"),
                            changes = fileDiffs(tool, input),
                            displayTitle = params.str("title"),
                            displayName = params.str("displayName"),
                            description = params.str("description"),
                            blockedPath = params.str("blockedPath"),
                            claudeSuggestionsJson = suggestions,
                            defaultToNo = params.optBoolean("defaultToNo", false),
                            suppressAlwaysAllowRule = params.optBoolean("suppressAlwaysAllowRule", false),
                            isExitPlanMode = isExitPlan,
                            planMarkdown = planMd,
                            planFilePath = planPath,
                            allowedPrompts = allowedPrompts,
                        )
                    }
                }
                "elicitation" -> {
                    val body = params.optJSONObject("request") ?: JSONObject()
                    PendingRequest.Elicitation(
                        engine = kind,
                        requestId = id,
                        conversation = sessionKey,
                        message = listOfNotNull(body.str("title"), body.str("message") ?: body.str("description")).joinToString("\n"),
                        raw = body,
                        schemaFields = parseElicitationSchema(body),
                        url = body.str("url"),
                        serverName = body.str("serverName"),
                        mode = body.str("mode") ?: if (body.str("url") != null) "url" else "form",
                    )
                }
                "dialog" -> {
                    dialogRequests += id.toString()
                    val body = params.optJSONObject("request") ?: JSONObject()
                    PendingRequest.Elicitation(
                        engine = kind,
                        requestId = id,
                        conversation = sessionKey,
                        message = body.str("title") ?: body.str("message") ?: body.pretty(1000),
                        raw = body,
                        schemaFields = parseElicitationSchema(body),
                        url = body.str("url"),
                        serverName = body.str("serverName") ?: "Claude Code",
                        mode = "form",
                    )
                }
                "delegate" -> {
                    val targetId = params.str("targetAgent")?.takeIf { it.isNotBlank() } ?: "codex"
                    if (sessionKey == null) {
                        runtime.reject(kind, id, -32602, "委派请求缺少来源会话")
                    } else {
                        repo.delegation.requested(kind, id, sessionKey, EngineKind.of(targetId), params.optString("task"), repo.state(sessionKey)?.summary?.cwd)
                    }
                    null
                }
                else -> { runtime.reject(kind, id, -32602, "未知的 Claude Code 请求类型"); null }
            }
            request?.let(repo::addRequest)
            return
        }
        when (event.method) {
            "galiais/requestResolved" -> params.opt("id")?.let { repo.removeRequest(kind, it) }
            "claude/message" -> sessionKey?.let { handleMessage(it, params.optJSONObject("message") ?: return, live = true) }
            "claude/sessionClosed", "claude/sessionError" -> sessionKey?.let { key ->
                connected -= key.id
                streaming.remove(key.id)
                repo.upsertItem(key, TimelineItem.Notice("closed-" + newId(), params.str("reason") ?: params.str("message") ?: "Claude Code 会话已结束", error = event.method == "claude/sessionError"))
                repo.update(key) { it.copy(running = false) }
                turnWaiters.remove(key.id)?.completeExceptionally(IllegalStateException("Claude Code 会话已结束"))
            }
        }
    }

    override fun resolve(request: PendingRequest, decision: Decision) {
        val allowAny = decision is Decision.Allow || decision is Decision.AllowForSession || decision is Decision.AllowWithRule
        val deny = decision as? Decision.Deny
        val reason = deny?.reason ?: "用户拒绝"
        val result = when (request) {
            is PendingRequest.UserInput -> if (request.isClaudeAskUserQuestion && decision is Decision.Answers) {
                val updated = JSONObject(request.rawClaudeInput?.toString() ?: "{}")
                val answersObj = JSONObject()
                decision.values.forEach { (qKey, vals) ->
                    if (vals.isNotEmpty()) answersObj.put(qKey, vals.joinToString(", "))
                }
                updated.put("answers", answersObj)
                jsonOf("behavior" to "allow", "updatedInput" to updated, "decisionClassification" to "user_temporary")
            } else {
                jsonOf("behavior" to "deny", "message" to reason, "interrupt" to (deny?.interrupt == true))
            }
            is PendingRequest.ToolApproval -> {
                if (request.isExitPlanMode && allowAny) {
                    request.conversation?.let { key ->
                        repo.update(key) { st ->
                            st.copy(items = st.items.map { item ->
                                if (item is TimelineItem.Plan && item.status == ItemStatus.RUNNING) {
                                    item.copy(status = ItemStatus.COMPLETED, streaming = false)
                                } else item
                            })
                        }
                    }
                }
                when (decision) {
                    is Decision.Allow -> jsonOf("behavior" to "allow", "decisionClassification" to "user_temporary")
                    is Decision.AllowForSession, is Decision.AllowWithRule -> {
                        val rawPerm = (decision as? Decision.AllowWithRule)?.claudePermissionsJson ?: request.claudeSuggestionsJson
                        val res = jsonOf("behavior" to "allow", "decisionClassification" to "user_permanent")
                        if (!rawPerm.isNullOrBlank()) {
                            runCatching { JSONArray(rawPerm) }.getOrNull()?.let { res.put("updatedPermissions", it) }
                        }
                        res
                    }
                    is Decision.Deny -> jsonOf("behavior" to "deny", "message" to decision.reason, "interrupt" to decision.interrupt)
                    else -> jsonOf("behavior" to "deny", "message" to reason, "interrupt" to false)
                }
            }
            is PendingRequest.Elicitation -> if (dialogRequests.remove(request.requestId.toString())) {
                when (decision) {
                    is Decision.FormSubmit -> jsonOf("behavior" to "completed", "result" to decision.content)
                    else -> if (allowAny) jsonOf("behavior" to "completed", "result" to JSONObject()) else jsonOf("behavior" to "cancelled")
                }
            } else {
                when (decision) {
                    is Decision.FormSubmit -> jsonOf("action" to "accept", "content" to decision.content)
                    else -> jsonOf("action" to if (allowAny) "accept" else if (deny?.interrupt == true) "cancel" else "decline")
                }
            }
            else -> error("Claude Code 不处理此类请求")
        }
        runtime.respond(kind, request.requestId, result)
    }

    private fun isClaudePlanFile(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        val file = lower.substringAfterLast('/')
        return lower.contains("/.claude/plans/") || file == "plan.md" || file == "implementation_plan.md"
    }

    fun respondDelegation(id: Any, text: String, success: Boolean) =
        runtime.respond(kind, id, jsonOf("behavior" to if (success) "completed" else "failed", "text" to text))

    override fun reset() {
        connected.clear(); modelCatalogs.clear(); streaming.clear(); dialogRequests.clear(); commandStartedAt.clear(); sessionCumulativeTokens.clear()
        msgStartedAt.clear(); msgFirstTokenAt.clear(); msgLastTokenAt.clear(); msgTimingById.clear(); turnBilledMsgIds.clear()
        turnTtftSum.clear(); turnTtftCount.clear(); turnGenMsSum.clear()
        claudeTasks.clear(); pendingTaskCreateTool.clear(); latestPlanFile.clear()
        turnWaiters.values.forEach { it.completeExceptionally(IllegalStateException("运行环境已停止")) }
        turnWaiters.clear()
    }
}
