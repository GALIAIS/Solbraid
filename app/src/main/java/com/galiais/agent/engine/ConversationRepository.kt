package com.galiais.agent.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.galiais.agent.MainActivity
import com.galiais.agent.VmRuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

data class CreateOptions(
    /** null 表示使用该引擎在设置中配置的默认服务商；传 "" 表示强制使用官方内置服务商；其他值指向 CustomProvider.id。 */
    val providerId: String? = null,
    val model: String? = null,
    val parent: ConversationKey? = null,
    /** 子会话不再注册委派工具，避免两个引擎互相递归委派。 */
    val allowDelegation: Boolean = true,
)

/** 单个官方引擎的协议适配器。所有方法都在主线程调用，状态只通过仓库修改。 */
interface EngineAdapter {
    val kind: EngineKind
    suspend fun list(): List<ConversationSummary>
    suspend fun load(key: ConversationKey)
    suspend fun loadEarlier(key: ConversationKey, cursor: String): ConversationHistoryPage
    suspend fun create(cwd: String, options: CreateOptions): ConversationKey
    suspend fun send(key: ConversationKey, text: String, attachments: List<ImageAttachment> = emptyList())
    suspend fun interrupt(key: ConversationKey)
    suspend fun rename(key: ConversationKey, title: String)
    suspend fun delete(key: ConversationKey)
    suspend fun compact(key: ConversationKey)
    suspend fun fork(key: ConversationKey): ConversationKey? = null
    suspend fun startReview(key: ConversationKey, instructions: String? = null)
    suspend fun commands(key: ConversationKey): List<SlashCommand> = emptyList()
    suspend fun models(key: ConversationKey?): List<ModelOption>
    suspend fun setProvider(key: ConversationKey, providerId: String?, model: String?): ConversationKey
    suspend fun setModel(key: ConversationKey, model: String?)
    suspend fun setEffort(key: ConversationKey, effort: String?)
    fun onEvent(event: EngineEvent)
    fun resolve(request: PendingRequest, decision: Decision)
    fun reset()
}

/**
 * 两个引擎共用的会话仓库：统一列表、时间线、待处理请求都在这里，界面只订阅 StateFlow。
 * 只在主线程修改，避免锁。
 */
class ConversationRepository(val runtime: RuntimeClient, val settings: AppSettings) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val conversationsFlow = MutableStateFlow<Map<ConversationKey, ConversationState>>(emptyMap())
    val conversations: StateFlow<Map<ConversationKey, ConversationState>> = conversationsFlow.asStateFlow()
    private val summariesFlow = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val summaries: StateFlow<List<ConversationSummary>> = summariesFlow.asStateFlow()
    private val loadingJobs = mutableMapOf<ConversationKey, Job>()
    private val loadedOrder = LinkedHashSet<ConversationKey>()
    @Volatile private var activeConversationKey: ConversationKey? = null

    private fun publishConversations(next: Map<ConversationKey, ConversationState>, syncSummaryList: Boolean = true) {
        conversationsFlow.value = next
        if (syncSummaryList) {
            val sorted = next.values.map { it.summary.copy(running = it.running) }.sortedByDescending { it.updatedAt }
            if (sorted != summariesFlow.value) summariesFlow.value = sorted
        }
    }

    fun setActiveConversation(key: ConversationKey?) {
        activeConversationKey = key
        if (key != null) {
            loadedOrder.remove(key)
            loadedOrder.add(key)
        }
    }

    private fun recordLoadedAndEvictIfNeeded(key: ConversationKey) {
        loadedOrder.remove(key)
        loadedOrder.add(key)
        val maxInRam = 6
        if (loadedOrder.size <= maxInRam) return
        val current = conversationsFlow.value
        val toEvict = loadedOrder.filter { k ->
            val state = current[k]
            k != key && k != activeConversationKey && state?.loaded == true && !state.running && !state.reverting
        }.take((loadedOrder.size - maxInRam).coerceAtLeast(0))
        if (toEvict.isEmpty()) return
        val next = current.toMutableMap()
        var evictedAny = false
        for (k in toEvict) {
            val st = next[k] ?: continue
            loadingJobs.remove(k)?.cancel()
            next[k] = st.copy(items = emptyList(), turnDurations = emptyMap(), loaded = false,
                historyCursor = null, loadingEarlier = false, historyError = null)
            loadedOrder.remove(k)
            evictedAny = true
        }
        if (evictedAny) publishConversations(next, syncSummaryList = false)
    }
    private val requestsFlow = MutableStateFlow<List<PendingRequest>>(emptyList())
    val requests: StateFlow<List<PendingRequest>> = requestsFlow.asStateFlow()
    private val messageFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** 一次性提示（错误、操作结果），界面用 Snackbar 展示。 */
    val messages = messageFlow.asSharedFlow()
    private val loadingFlow = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = loadingFlow.asStateFlow()
    private val initialSyncDoneFlow = MutableStateFlow(false)
    /** 运行环境就绪后是否已完成至少一次会话列表拉取（用于避免空态欢迎页一闪而过）。 */
    val initialSyncDone: StateFlow<Boolean> = initialSyncDoneFlow.asStateFlow()
    private val arenaGroupsFlow = MutableStateFlow<Map<String, List<ConversationKey>>>(emptyMap())
    val arenaGroups: StateFlow<Map<String, List<ConversationKey>>> = arenaGroupsFlow.asStateFlow()

    val codex = CodexEngine(this)
    val claude = ClaudeEngine(this)
    val hub = AgentHubController(this)
    val delegation = DelegationCoordinator(this)
    val extensions = ExtensionRepository(runtime, settings, ::notify) { codex.reloadMcpServers() }
    val envSsh = EnvSshRepository(runtime, settings, ::notify) { codex.reloadMcpServers() }
    val cliUpdates = CliUpdateRepository(this)
    val superAgent = SuperAgentCoordinator(this, runtime, settings)
    private var wasReady = false
    private var lastReadyMode: String? = null
    private var startupSyncJob: Job? = null
    private var refreshJob: Job? = null
    private val providerSyncMutex = kotlinx.coroutines.sync.Mutex()
    private var syncedProvidersFingerprint: String? = null

    fun adapter(kind: EngineKind): EngineAdapter = when (kind.id) {
        "codex" -> codex
        "claude" -> claude
        else -> hub.adapterFor(kind)
    }

    init {
        scope.launch {
            runtime.events.collect { event ->
                try { adapter(event.engine).onEvent(event) }
                catch (error: Exception) { Log.e("GALIAIS", "处理 ${event.engine.label} 事件失败：${event.method}", error); notify("处理引擎事件失败：${error.message}") }
            }
        }
        scope.launch {
            runtime.snapshot.collect { snapshot ->
                if (snapshot.ready && !wasReady) {
                    wasReady = true
                    if (lastReadyMode != null && lastReadyMode != snapshot.mode) {
                        loadingJobs.values.forEach { it.cancel() }; loadingJobs.clear(); loadedOrder.clear()
                        publishConversations(emptyMap())
                        arenaGroupsFlow.value = emptyMap()
                        requestsFlow.value = emptyList()
                    }
                    lastReadyMode = snapshot.mode
                    loadingFlow.value = true
                    initialSyncDoneFlow.value = false
                    startupSyncJob?.cancel()
                    startupSyncJob = scope.launch {
                        try {
                            if (!runtime.isServiceReady) return@launch
                            try { syncProvidersNow() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) {
                                Log.w("GALIAIS", "同步自定义服务商失败", error)
                                if (runtime.isServiceReady) notify("同步自定义服务商失败：${error.message}")
                            }
                            if (!runtime.isServiceReady) return@launch
                            try { hub.configureAndProbe() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) {
                                Log.w("GALIAIS", "同步 Agent Hub 失败", error)
                                if (runtime.isServiceReady) notify("外部 Agent 配置失败：${error.message}")
                            }
                            if (!runtime.isServiceReady) return@launch
                            try { envSsh.syncAllOnReady() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { Log.w("GALIAIS", "同步环境与 SSH 失败", error) }
                            if (!runtime.isServiceReady) return@launch
                            try { extensions.syncNow() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) {
                                Log.w("GALIAIS", "同步扩展中心失败", error)
                                if (runtime.isServiceReady) notify("扩展同步失败：${error.message}")
                            }
                            if (!runtime.isServiceReady) return@launch
                            try { extensions.refreshHostPlugins() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { Log.w("GALIAIS", "拉取 Host 插件快照失败", error) }
                            try { syncSelfBuildConfigNow() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { Log.w("GALIAIS", "同步本机自迭代配置失败", error) }
                            try { superAgent.loadPersisted() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { Log.w("GALIAIS", "恢复工单失败", error) }
                            if (runtime.isServiceReady) refresh().join()
                        } finally {
                            initialSyncDoneFlow.value = true
                            loadingFlow.value = false
                        }
                    }
                }
                else if (!snapshot.ready && wasReady && snapshot.state !in setOf("connecting", "backing-up")) {
                    wasReady = false
                    startupSyncJob?.cancel(); startupSyncJob = null
                    refreshJob?.cancel(); refreshJob = null
                    loadingFlow.value = false
                    initialSyncDoneFlow.value = false
                    deltaFlushJob?.cancel(); pendingDeltas.clear()
                    syncedProvidersFingerprint = null
                    delegation.reset(); superAgent.resetActiveJobs(); envSsh.resetRuntimeState(); cliUpdates.disconnected(); codex.reset(); claude.reset(); hub.reset()
                    requestsFlow.value = emptyList()
                    val modeChanged = lastReadyMode != null && settings.state.value.effectiveRuntimeMode != lastReadyMode
                    if (modeChanged) {
                        loadingJobs.values.forEach { it.cancel() }; loadingJobs.clear(); loadedOrder.clear()
                        publishConversations(emptyMap())
                        arenaGroupsFlow.value = emptyMap()
                    } else {
                        loadingJobs.values.forEach { it.cancel() }; loadingJobs.clear(); loadedOrder.clear()
                        publishConversations(conversationsFlow.value.mapValues {
                            it.value.copy(running = false, activeTurnId = null, loaded = false,
                                historyCursor = null, loadingEarlier = false, historyError = null)
                        })
                    }
                }
            }
        }
        scope.launch {
            requestsFlow.collect { list -> syncApprovalNotification(list) }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(
                conversationsFlow.map { all -> all.values.any { it.running } }.distinctUntilChanged(),
                envSsh.nativeTerminals.hasActiveSession,
                envSsh.activeTunnels.map { list -> list.any { it.status == "running" } }.distinctUntilChanged(),
            ) { anyTurn, anyTerm, anyTunnel -> anyTurn || anyTerm || anyTunnel }
                .distinctUntilChanged()
                .collect { keepAlive -> runtime.setActiveTurnRunning(keepAlive) }
        }
    }

    fun resolveFromNotification(engineId: String, reqIdStr: String, allow: Boolean) {
        val target = requestsFlow.value.firstOrNull { it.engine.id == engineId && it.requestId.toString() == reqIdStr } ?: return
        resolve(target, if (allow) Decision.Allow else Decision.Deny("通知栏拒绝"))
    }

    private fun syncApprovalNotification(list: List<PendingRequest>) {
        val ctx = runtime.app
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val first = list.firstOrNull()
        if (first == null) {
            nm.cancel(VmRuntimeService.APPROVAL_NOTIFICATION)
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(VmRuntimeService.APPROVAL_CHANNEL, "Agent 操作审批", NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = PendingIntent.getActivity(
            ctx, 30,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = when (first) {
            is PendingRequest.CommandApproval -> first.command
            is PendingRequest.FileApproval -> first.changes.joinToString("，") { it.path.substringAfterLast('/') }
            is PendingRequest.ToolApproval -> first.toolName
            is PendingRequest.Delegation -> first.task.take(120)
            is PendingRequest.UserInput -> first.questions.firstOrNull()?.text ?: "点击进入应用回答"
            is PendingRequest.Elicitation -> first.message.take(120)
            is PendingRequest.PermissionGrant -> first.reason ?: "请求额外权限"
        }
        val builder = Notification.Builder(ctx, VmRuntimeService.APPROVAL_CHANNEL)
            .setSmallIcon(com.galiais.agent.R.drawable.ic_notif_approval)
            .setContentTitle("${first.engine.label} · ${first.title}" + if (list.size > 1) "（共 ${list.size} 项）" else "")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openApp)
            .setAutoCancel(false)

        if (first !is PendingRequest.UserInput && first !is PendingRequest.Elicitation) {
            val reqKey = first.requestId.toString()
            val approvePi = PendingIntent.getBroadcast(
                ctx, 31,
                Intent(ctx, com.galiais.agent.VmRuntimeActionReceiver::class.java).setAction(VmRuntimeService.APPROVE_REQ)
                    .putExtra(VmRuntimeService.EXTRA_ENGINE, first.engine.id)
                    .putExtra(VmRuntimeService.EXTRA_REQ_ID, reqKey),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val denyPi = PendingIntent.getBroadcast(
                ctx, 32,
                Intent(ctx, com.galiais.agent.VmRuntimeActionReceiver::class.java).setAction(VmRuntimeService.DENY_REQ)
                    .putExtra(VmRuntimeService.EXTRA_ENGINE, first.engine.id)
                    .putExtra(VmRuntimeService.EXTRA_REQ_ID, reqKey),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (first !is PendingRequest.ToolApproval || first.engine.id in setOf("codex", "claude") || first.permissionOptions.any { it.kind == "allow_once" }) {
                builder.addAction(Notification.Action.Builder(null, "允许本次", approvePi).build())
            }
            builder.addAction(Notification.Action.Builder(null, "拒绝", denyPi).build())
        }
        nm.notify(VmRuntimeService.APPROVAL_NOTIFICATION, builder.build())
    }

    fun notify(text: String) { messageFlow.tryEmit(text) }

    fun syncAgentsToHub() = launchOperation("同步 Agent 目录失败") {
        hub.configureAndProbe()
    }

    /** 重新拉取内置与已安装外部 Agent 的会话列表；已打开会话的时间线保留。 */
    fun refresh(): Job {
        refreshJob?.cancel()
        val job = scope.launch {
            if (!runtime.snapshot.value.ready || !runtime.isServiceReady) return@launch
            loadingFlow.value = true
            try {
                for (engine in listOf<EngineAdapter>(codex, claude)) {
                    if (!runtime.snapshot.value.ready || !runtime.isServiceReady) return@launch
                    val listed = try {
                        engine.list()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        // 运行环境正在停止、重启或切换模式时，静默放弃在途刷新，避免弹出过期的“引擎尚未连接”提示
                        if (!runtime.snapshot.value.ready || !runtime.isServiceReady) return@launch
                        notify("${engine.kind.label} 会话列表读取失败：${error.message}")
                        continue
                    }
                    val current = conversationsFlow.value
                    val merged = current.filterKeys { it.engine != engine.kind || listed.any { s -> s.key == it } }.toMutableMap()
                    for (summary in listed) {
                        val existing = merged[summary.key]
                        merged[summary.key] = existing?.copy(summary = summary.copy(parent = summary.parent ?: existing.summary.parent))
                            ?: initialState(summary).copy(running = summary.running)
                    }
                    publishConversations(merged)
                }
                if (runtime.snapshot.value.ready && runtime.isServiceReady && runtime.hubAvailable) {
                    val hubListed = try { hub.listAllSessions() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    if (hubListed != null) {
                        val keepKeys = hubListed.map { it.key }.toSet()
                        val current = conversationsFlow.value
                        val merged = current.filterKeys { k ->
                            k.engine == EngineKind.CODEX || k.engine == EngineKind.CLAUDE || k in keepKeys
                        }.toMutableMap()
                        for (summary in hubListed) {
                            val existing = merged[summary.key]
                            merged[summary.key] = existing?.copy(summary = summary.copy(parent = summary.parent ?: existing.summary.parent))
                                ?: initialState(summary).copy(running = summary.running)
                        }
                        publishConversations(merged)
                    }
                }
            } finally {
                loadingFlow.value = false
                if (runtime.snapshot.value.ready) initialSyncDoneFlow.value = true
            }
        }
        refreshJob = job
        return job
    }

    fun state(key: ConversationKey): ConversationState? = conversationsFlow.value[key]

    /** 单会话精准观察流：避免其他后台或并行对比会话的流式刷新触发当前聊天页无效重组。 */
    fun observeConversation(key: ConversationKey): Flow<ConversationState?> =
        conversationsFlow.map { it[key] }.distinctUntilChanged()

    private enum class StreamDeltaKind { AGENT_TEXT, REASONING_TEXT, REASONING_PART, PLAN_TEXT, COMMAND_OUTPUT }
    private data class StreamDeltaBucket(
        val itemId: String,
        val kind: StreamDeltaKind,
        val summaryIndex: Int = 0,
        val buffer: StringBuilder = StringBuilder(),
        val create: () -> TimelineItem?,
    )
    private val pendingDeltas = linkedMapOf<ConversationKey, LinkedHashMap<String, StreamDeltaBucket>>()
    private var deltaFlushJob: Job? = null
    private val lastFlushAtMs = hashMapOf<ConversationKey, Long>()

    /**
     * 流式增量合帧缓冲（首包 0ms 直通 + 稳态 24ms / ~42Hz 合帧）：
     * - 新消息首包或静默超过 64ms 后的首个增量立即提交，消除首字等待感；
     * - 稳态每 24ms 合并高频 SSE 碎片，减少 Conversation Map / Timeline List 复制，并配合展示层 VSync 流速缓动实现 120Hz 顺滑输出；
     * - 任意结构化事件（tool_call / completed / update / upsert）到来前会自动同步 flush，保证 100% 顺序一致。
     */
    fun appendStreamingDelta(
        key: ConversationKey,
        itemId: String,
        delta: String,
        kind: String,
        summaryIndex: Int = 0,
        create: () -> TimelineItem?,
    ) {
        if (delta.isEmpty()) return
        val mappedKind = when (kind) {
            "agent" -> StreamDeltaKind.AGENT_TEXT
            "reasoning" -> StreamDeltaKind.REASONING_TEXT
            "reasoning_part" -> StreamDeltaKind.REASONING_PART
            "plan" -> StreamDeltaKind.PLAN_TEXT
            "command_output" -> StreamDeltaKind.COMMAND_OUTPUT
            else -> return
        }
        val byItem = pendingDeltas.getOrPut(key) { linkedMapOf() }
        val bucketKey = if (mappedKind == StreamDeltaKind.REASONING_PART) "$itemId#$summaryIndex" else "$itemId:${mappedKind.ordinal}"
        val isNewBucket = !byItem.containsKey(bucketKey)
        val bucket = byItem.getOrPut(bucketKey) { StreamDeltaBucket(itemId, mappedKind, summaryIndex, StringBuilder(), create) }
        bucket.buffer.append(delta)
        val now = System.currentTimeMillis()
        val lastFlush = lastFlushAtMs[key] ?: 0L
        // 首字直通（静默超过 64ms 且刚开启新缓冲）或单次缓冲超过 2KB 时立即提交，兼顾极速首字与内存上限
        if (bucket.buffer.length >= 2048 || (isNewBucket && (now - lastFlush) >= 64L)) {
            flushStreamingDeltas(key)
            return
        }
        if (deltaFlushJob?.isActive != true) {
            deltaFlushJob = scope.launch {
                delay(24L)
                flushAllStreamingDeltas()
            }
        }
    }

    fun flushAllStreamingDeltas() {
        if (pendingDeltas.isEmpty()) return
        val keys = pendingDeltas.keys.toList()
        for (k in keys) flushStreamingDeltas(k)
    }

    fun flushStreamingDeltas(key: ConversationKey) {
        val buckets = pendingDeltas.remove(key) ?: return
        if (buckets.isEmpty()) return
        lastFlushAtMs[key] = System.currentTimeMillis()
        val current = conversationsFlow.value
        val existing = current[key] ?: initialState(ConversationSummary(key, "新会话", "", null, System.currentTimeMillis()))
        val items = existing.items.toMutableList()
        var changed = false
        for (b in buckets.values) {
            val chunk = b.buffer.toString()
            if (chunk.isEmpty()) continue
            // 活跃流式条目几乎总是位于列表尾部，逆序查找将长会话 O(N) 扫描降为 O(1)
            val idx = items.indexOfLast { it.id == b.itemId }
            val base = if (idx >= 0) items[idx] else (b.create() ?: continue)
            val next = when (b.kind) {
                StreamDeltaKind.AGENT_TEXT -> if (base is TimelineItem.AgentMessage) base.copy(text = base.text + chunk, streaming = true) else base
                StreamDeltaKind.REASONING_TEXT -> if (base is TimelineItem.Reasoning) {
                    val prev = base.text
                    val cleanChunk = if (prev.endsWith("\n\n")) chunk.trimStart('\n') else chunk
                    base.copy(text = prev + cleanChunk, streaming = true)
                } else base
                StreamDeltaKind.REASONING_PART -> if (base is TimelineItem.Reasoning) {
                    val parts = base.summaryParts.orEmpty()
                    base.copy(summaryParts = parts + (b.summaryIndex to (parts[b.summaryIndex].orEmpty() + chunk)), streaming = true)
                } else base
                StreamDeltaKind.PLAN_TEXT -> if (base is TimelineItem.Plan) {
                    val nextText = base.text + chunk
                    base.copy(text = nextText, title = extractPlanTitle(nextText, base.filePath), streaming = true, status = ItemStatus.RUNNING)
                } else base
                StreamDeltaKind.COMMAND_OUTPUT -> if (base is TimelineItem.CommandRun) base.copy(output = (base.output + chunk).takeLast(200_000)) else base
            }
            if (idx >= 0) items[idx] = next else items.add(next)
            changed = true
        }
        if (changed) {
            // 流式增量绝不触碰 summariesFlow，杜绝后台会话列表重排与重组
            publishConversations(current + (key to existing.copy(items = items)), syncSummaryList = false)
        }
    }

    private fun initialState(summary: ConversationSummary): ConversationState {
        val state = ConversationState(summary)
        return settings.conversationOptions(summary.key)?.applyTo(state) ?: state
    }

    fun persistOptions(key: ConversationKey) {
        state(key)?.let { settings.saveConversationOptions(key, ConversationOptions.from(it)) }
    }

    fun update(key: ConversationKey, block: (ConversationState) -> ConversationState) {
        flushStreamingDeltas(key)
        val current = conversationsFlow.value
        val existing = current[key] ?: initialState(ConversationSummary(key, "新会话", "", null, System.currentTimeMillis()))
        val next = block(existing)
        if (next === existing) return
        val summaryChanged = existing.summary != next.summary || existing.running != next.running || !current.containsKey(key)
        publishConversations(current + (key to next), syncSummaryList = summaryChanged)
        if (next.loaded && !existing.loaded) recordLoadedAndEvictIfNeeded(key)
    }

    fun remove(key: ConversationKey) {
        pendingDeltas.remove(key)
        lastFlushAtMs.remove(key)
        loadingJobs.remove(key)?.cancel()
        loadedOrder.remove(key)
        publishConversations(conversationsFlow.value - key)
        requestsFlow.value = requestsFlow.value.filter { it.conversation != key }
        arenaGroupsFlow.value = arenaGroupsFlow.value.mapValues { it.value - key }.filterValues { it.size > 1 }
    }

    /** 新增或替换时间线条目（按 id，优先从尾部匹配降低长会话查找开销）。 */
    fun upsertItem(key: ConversationKey, item: TimelineItem) = update(key) { state ->
        val index = state.items.indexOfLast { it.id == item.id }
        val items = if (index >= 0) state.items.toMutableList().also { it[index] = item } else state.items + item
        state.copy(items = items, summary = state.summary.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 修改已有条目；条目不存在时用 [create] 生成。 */
    fun <T : TimelineItem> mutateItem(key: ConversationKey, id: String, create: () -> T?, transform: (TimelineItem) -> TimelineItem) {
        flushStreamingDeltas(key)
        val item = state(key)?.items?.lastOrNull { it.id == id }
        val next = if (item != null) transform(item) else create()?.let(transform) ?: return
        upsertItem(key, next)
    }

    fun findItem(key: ConversationKey, id: String): TimelineItem? {
        flushStreamingDeltas(key)
        return state(key)?.items?.lastOrNull { it.id == id }
    }

    fun addRequest(request: PendingRequest) {
        requestsFlow.value = requestsFlow.value.filterNot { it.engine == request.engine && it.requestId.toString() == request.requestId.toString() } + request
    }

    fun removeRequest(engine: EngineKind, requestId: Any) {
        requestsFlow.value = requestsFlow.value.filterNot { it.engine == engine && it.requestId.toString() == requestId.toString() }
    }

    fun resolve(request: PendingRequest, decision: Decision) {
        try {
            if (request is PendingRequest.Delegation) delegation.resolve(request, decision)
            else adapter(request.engine).resolve(request, decision)
            removeRequest(request.engine, request.requestId)
            recordInteractionReceipt(request, decision)
        } catch (error: Exception) { notify("提交审批结果失败：${error.message}") }
    }

    /** 将用户对结构化提问、Elicitation 表单及退出计划模式的决策写入时间线回执，避免审批卡消失后无迹可寻。 */
    private fun recordInteractionReceipt(request: PendingRequest, decision: Decision) {
        val key = request.conversation ?: return
        val receipt: TimelineItem.InteractionReceipt = when (request) {
            is PendingRequest.UserInput -> {
                if (decision is Decision.Answers && decision.values.isNotEmpty()) {
                    val entries = request.questions.mapNotNull { q ->
                        val ans = decision.values[q.id]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val label = q.header.ifBlank { q.text.take(36) }
                        val formatted = ans.joinToString("、") { parseRecommendedLabel(it).first }
                        label to formatted
                    }.ifEmpty {
                        decision.values.map { (k, v) -> k to v.joinToString("、") }
                    }
                    TimelineItem.InteractionReceipt(
                        id = "receipt-q-${request.requestId}",
                        kind = "question",
                        title = "已回答 ${entries.size} 项问题",
                        entries = entries,
                        status = ItemStatus.COMPLETED,
                    )
                } else {
                    TimelineItem.InteractionReceipt(
                        id = "receipt-q-${request.requestId}",
                        kind = "question",
                        title = "已跳过提问",
                        entries = emptyList(),
                        status = ItemStatus.DECLINED,
                    )
                }
            }
            is PendingRequest.Elicitation -> when (decision) {
                is Decision.FormSubmit -> {
                    val fieldTitles = request.schemaFields.associate { it.name to it.title }
                    val entries = buildList {
                        val keys = decision.content.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = decision.content.opt(k)
                            val displayVal = when (v) {
                                is JSONArray -> (0 until v.length()).joinToString("、") { idx ->
                                    parseRecommendedLabel(v.optString(idx)).first
                                }
                                is Boolean -> if (v) "是" else "否"
                                else -> parseRecommendedLabel(v?.toString().orEmpty()).first
                            }
                            if (displayVal.isNotBlank()) add((fieldTitles[k] ?: k) to displayVal)
                        }
                    }
                    TimelineItem.InteractionReceipt(
                        id = "receipt-e-${request.requestId}",
                        kind = "elicitation",
                        title = request.serverName?.takeIf { it.isNotBlank() }?.let { "已提交 $it 表单" } ?: "已提交补充信息",
                        entries = entries,
                        status = ItemStatus.COMPLETED,
                    )
                }
                is Decision.Allow, is Decision.AllowForSession, is Decision.AllowWithRule -> TimelineItem.InteractionReceipt(
                    id = "receipt-e-${request.requestId}",
                    kind = "elicitation",
                    title = if (!request.url.isNullOrBlank()) "已完成外部链接授权" else "已确认补充信息",
                    entries = listOfNotNull(request.url?.takeIf { it.isNotBlank() }?.let { "链接" to it }),
                    status = ItemStatus.COMPLETED,
                )
                is Decision.Deny -> TimelineItem.InteractionReceipt(
                    id = "receipt-e-${request.requestId}",
                    kind = "elicitation",
                    title = "已拒绝补充信息",
                    entries = listOfNotNull(decision.reason.takeIf { it.isNotBlank() && it != "用户拒绝提供输入" }?.let { "原因" to it }),
                    status = ItemStatus.DECLINED,
                )
                else -> return
            }
            is PendingRequest.ToolApproval -> {
                if (!request.isExitPlanMode) return
                val denied = decision is Decision.Deny
                // 同步将时间线中待确认的计划卡状态落地
                update(key) { st ->
                    val updated = st.items.map { item ->
                        if (item is TimelineItem.Plan && (item.requestFeedback || item.status == ItemStatus.RUNNING)) {
                            item.copy(
                                requestFeedback = false,
                                status = if (denied) ItemStatus.DECLINED else ItemStatus.COMPLETED,
                                streaming = false,
                            )
                        } else item
                    }
                    st.copy(items = updated)
                }
                val title = when (decision) {
                    is Decision.AllowWithRule -> "已批准方案并开启自动编辑"
                    is Decision.Allow, is Decision.AllowForSession -> "已批准方案，开始执行"
                    is Decision.Deny -> "已退回方案并要求修改"
                    else -> "方案确认已处理"
                }
                val entries = buildList {
                    request.planFilePath?.takeIf { it.isNotBlank() }?.let { add("方案文件" to it.substringAfterLast('/')) }
                    if (decision is Decision.Deny && decision.reason.isNotBlank()) add("修改意见" to decision.reason)
                }
                TimelineItem.InteractionReceipt(
                    id = "receipt-plan-${request.requestId}",
                    kind = "plan_decision",
                    title = title,
                    entries = entries,
                    status = if (denied) ItemStatus.DECLINED else ItemStatus.COMPLETED,
                )
            }
            else -> return
        }
        upsertItem(key, receipt)
    }

    /** 在仓库作用域中执行操作，失败时提示而不是崩溃。 */
    fun launchOperation(label: String, block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Log.w("GALIAIS", label, error); notify("$label：${error.message ?: error.javaClass.simpleName}") }
    }

    suspend fun create(engine: EngineKind, cwd: String, options: CreateOptions = CreateOptions()): ConversationKey =
        adapter(engine).create(cwd, options.copy(allowDelegation = options.allowDelegation && settings.state.value.delegationEnabled))
            .also(::persistOptions)

    /** 多 Agent 并行对比（Arena）：在同一工作目录为多个已安装 Agent 同时创建兄弟会话并并发下发同一任务。 */
    suspend fun createArena(engines: List<EngineKind>, cwd: String, prompt: String): List<ConversationKey> {
        require(engines.size >= 2) { "请至少选择 2 个 Agent 进行并行对比" }
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "请输入要并行执行的任务描述" }
        val groupId = "arena-" + com.galiais.agent.newId()
        val created = mutableListOf<ConversationKey>()
        for (eng in engines.distinct()) {
            val k = create(eng, cwd, CreateOptions(allowDelegation = false))
            update(k) { it.copy(arenaGroupId = groupId) }
            created += k
        }
        arenaGroupsFlow.value = arenaGroupsFlow.value + (groupId to created)
        created.forEach { k -> send(k, cleanPrompt) }
        return created
    }

    fun open(key: ConversationKey, reload: Boolean = false) {
        val current = state(key)
        if (current?.reverting == true) return
        if (current?.loaded == true && !reload) {
            recordLoadedAndEvictIfNeeded(key)
            return
        }
        if (reload) loadingJobs.remove(key)?.cancel()
        else if (loadingJobs[key]?.isActive == true) return
        val job = launchOperation("打开会话失败") {
            update(key) { it.copy(loadingEarlier = false, historyError = null) }
            try {
                withEngineOperation(key.engine.id) { adapter(key.engine).load(key) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (state(key) != null) update(key) { it.copy(historyError = error.localizedMessage ?: "读取会话失败") }
                throw error
            }
        }
        trackLoad(key, job)
    }

    private fun trackLoad(key: ConversationKey, job: Job) {
        loadingJobs[key] = job
        job.invokeOnCompletion { loadingJobs.remove(key, job) }
    }

    fun loadEarlier(key: ConversationKey): Boolean {
        val current = state(key) ?: return false
        val cursor = current.historyCursor ?: return false
        if (!current.loaded || current.reverting || loadingJobs[key]?.isActive == true) return false
        val job = launchOperation("读取历史失败") {
            update(key) { it.copy(loadingEarlier = true, historyError = null) }
            try {
                val page = adapter(key.engine).loadEarlier(key, cursor)
                check(page.nextCursor != cursor) { "历史分页标识重复" }
                if (state(key)?.historyCursor == cursor) update(key) { latest ->
                    val ids = latest.items.mapTo(hashSetOf()) { it.id }
                    latest.copy(items = page.items.filter { ids.add(it.id) } + latest.items,
                        turnDurations = page.durations + latest.turnDurations,
                        historyCursor = page.nextCursor, loadingEarlier = false)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (state(key)?.historyCursor == cursor) update(key) {
                    it.copy(loadingEarlier = false, historyError = error.localizedMessage ?: "读取历史失败")
                }
                throw error
            }
        }
        trackLoad(key, job)
        return true
    }

    internal suspend fun completeHistory(snapshot: ConversationState): ConversationState {
        check(snapshot.loaded) { "会话历史尚未加载" }
        var cursor = snapshot.historyCursor ?: return snapshot
        val key = snapshot.summary.key
        val seen = mutableSetOf<String>()
        val pages = mutableListOf<ConversationHistoryPage>()
        while (true) {
            check(seen.add(cursor)) { "历史分页标识重复" }
            val page = adapter(key.engine).loadEarlier(key, cursor)
            pages += page
            cursor = page.nextCursor ?: break
        }
        return withContext(Dispatchers.Default) {
            val ids = snapshot.items.mapTo(hashSetOf()) { it.id }
            val earlier = pages.map { page -> page.items.filter { ids.add(it.id) } }
            snapshot.copy(
                items = earlier.asReversed().flatten() + snapshot.items,
                turnDurations = buildMap {
                    pages.asReversed().forEach { putAll(it.durations) }
                    putAll(snapshot.turnDurations)
                },
                historyCursor = null, loadingEarlier = false,
            )
        }
    }
    private val initialDrafts = mutableMapOf<ConversationKey, String>()
    fun putInitialDraft(key: ConversationKey, text: String) { initialDrafts[key] = text }
    fun consumeInitialDraft(key: ConversationKey): String? = initialDrafts.remove(key)

    private val sending = mutableMapOf<ConversationKey, CompletableDeferred<Unit>>()
    private val engineOperationLocks = listOf(EngineKind.CODEX, EngineKind.CLAUDE)
        .associate { it.id to kotlinx.coroutines.sync.Mutex() }

    internal suspend fun <T> withEngineOperation(id: String, block: suspend () -> T): T {
        val lock = engineOperationLocks[id] ?: return block()
        return lock.withLock { block() }
    }

    private suspend fun withSend(key: ConversationKey, waitForPrevious: Boolean, block: suspend () -> Unit) {
        while (true) {
            val previous = sending[key] ?: break
            check(waitForPrevious) { "上一条消息仍在发送，请稍候" }
            previous.await()
        }
        val done = CompletableDeferred<Unit>()
        sending[key] = done
        try {
            check(state(key)?.reverting != true) { "会话正在回退，请稍候" }
            block()
        } finally {
            sending.remove(key)
            done.complete(Unit)
        }
    }

    private suspend fun sendRecoverably(
        key: ConversationKey, text: String, attachments: List<ImageAttachment>, followUp: Boolean = false,
        submit: suspend () -> Unit = { adapter(key.engine).send(key, text, attachments) },
    ) {
        try {
            withSend(key, waitForPrevious = followUp) {
                require(attachments.size <= 4 && attachments.sumOf { it.base64.length.toLong() } + text.toByteArray(Charsets.UTF_8).size < 7 * 1024 * 1024) { "消息附件过大，请减少图片或降低图片大小" }
                submit()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            update(key) { state ->
                val pending = state.unsentMessage
                val recovered = listOfNotNull(pending?.text, text).filter { it.isNotEmpty() }.joinToString("\n\n")
                state.copy(unsentMessage = UnsentMessage(com.galiais.agent.newId(), recovered, pending?.attachments.orEmpty() + attachments))
            }
            throw error
        }
    }

    fun clearUnsentMessage(key: ConversationKey, id: String) = update(key) {
        if (it.unsentMessage?.id == id) it.copy(unsentMessage = null) else it
    }

    fun clearRevertedMessage(key: ConversationKey, id: String) = update(key) {
        if (it.revertedMessage?.id == id) it.copy(revertedMessage = null) else it
    }

    fun send(key: ConversationKey, text: String, attachments: List<ImageAttachment> = emptyList()) =
        launchOperation("发送失败") { sendRecoverably(key, text, attachments) }
    suspend fun sendClaudeDocumentNow(
        key: ConversationKey,
        text: String,
        docName: String,
        mimeType: String,
        base64OrPlain: String,
        isPdf: Boolean,
    ) {
        withSend(key, waitForPrevious = false) {
            require(key.engine == EngineKind.CLAUDE) { "仅 Claude Code 支持原生文档块输入" }
            claude.sendWithDocument(key, text, docName, mimeType, base64OrPlain, isPdf)
        }
    }
    fun sendClaudeDocument(key: ConversationKey, text: String, docName: String, mimeType: String, base64OrPlain: String, isPdf: Boolean) =
        launchOperation("发送文档失败") {
            try {
                sendClaudeDocumentNow(key, text, docName, mimeType, base64OrPlain, isPdf)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (text.isNotBlank()) {
                    update(key) { it.copy(unsentMessage = UnsentMessage(com.galiais.agent.newId(), text, emptyList())) }
                }
                throw error
            }
        }
    fun setAcpConfigOption(key: ConversationKey, configId: String, value: String) =
        launchOperation("更新配置项失败") {
            hub.adapterFor(key.engine).setConfigOption(key, configId, value)
            notify("已更新设置：$value")
        }
    fun steer(key: ConversationKey, expectedTurnId: String, text: String, attachments: List<ImageAttachment>) = launchOperation("插话失败") {
        sendRecoverably(key, text, attachments, followUp = true) { codex.steer(key, expectedTurnId, text, attachments) }
    }

    fun enqueue(key: ConversationKey, text: String) = launchOperation("加入队列失败") {
        sendRecoverably(key, text, emptyList(), followUp = true) { codex.enqueueMessage(key, text) }
        notify("已加入队列")
    }
    fun isEditableUserMessage(item: TimelineItem.UserMessage): Boolean =
        !item.id.startsWith("steer-")

    private suspend fun revertBeforeTurnInternal(
        key: ConversationKey,
        userMessageId: String,
        rewindClaudeFiles: Boolean,
        restoreToComposer: Boolean,
    ): Pair<ConversationKey, TimelineItem.UserMessage> {
        val snapshot = state(key) ?: error("会话不存在")
        check(snapshot.loaded && !snapshot.running && !snapshot.reverting && key !in sending) { "请等待当前操作完成后再继续" }
        loadingJobs.remove(key)?.cancel()
        update(key) { it.copy(reverting = true, loadingEarlier = false) }
        var resolvedKey = key
        try {
            val current = completeHistory(snapshot)
            check(state(key)?.reverting == true && state(key)?.running == false) { "会话状态已变更，请重新选择" }
            val turnStart = current.items.indexOfFirst { it.id == userMessageId }
            val message = current.items.getOrNull(turnStart) as? TimelineItem.UserMessage
                ?: error("此轮消息已变更，请重新选择")
            require(isEditableUserMessage(message)) { "运行中插话不支持单独编辑重发，请编辑该轮主消息" }
            val userTurnIndex = current.items.take(turnStart).count { it is TimelineItem.UserMessage && isEditableUserMessage(it) }
            val retained = current.items.take(turnStart)
            val retainedIds = retained.flatMap { item ->
                listOfNotNull(item.id, (item as? TimelineItem.UserMessage)?.turnId)
            }.toSet()
            val retainedDurations = current.turnDurations.filterKeys { id -> id in retainedIds }
            when (key.engine) {
                EngineKind.CODEX -> {
                    val targetTurnId = message.turnId ?: current.items.drop(turnStart + 1)
                        .firstNotNullOfOrNull { (it as? TimelineItem.UserMessage)?.turnId }
                    val updatedSummary = if (targetTurnId != null) {
                        codex.revertBeforeTurn(key, targetTurnId)
                    } else {
                        current.summary.copy(
                            title = if (retained.isEmpty()) "新会话" else current.summary.title,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    if (state(key) != null) {
                        update(key) {
                            it.copy(
                                summary = if (retained.isEmpty()) updatedSummary.copy(title = "新会话") else updatedSummary,
                                items = retained,
                                historyCursor = null,
                                running = false,
                                activeTurnId = null,
                                turnStartedAt = null,
                                turnDurations = retainedDurations,
                                turnDiff = null,
                                tokenUsage = null,
                                plan = emptyList(),
                                planExplanation = null,
                                focusText = null,
                                reverting = false,
                                revertedMessage = if (restoreToComposer) message else null,
                            )
                        }
                    }
                }
                EngineKind.CLAUDE -> {
                    resolvedKey = claude.revertBeforeTurn(
                        key = key,
                        userMessageId = message.id,
                        userTurnIndex = userTurnIndex,
                        retainedItems = retained,
                        retainedDurations = retainedDurations,
                        rewindFiles = rewindClaudeFiles,
                    )
                    if (restoreToComposer && state(resolvedKey) != null) {
                        update(resolvedKey) { it.copy(revertedMessage = message) }
                    }
                }
                else -> {
                    hub.adapterFor(key.engine).revertBeforeTurn(key, message.id, userTurnIndex)
                    if (state(key) != null) {
                        update(key) {
                            it.copy(
                                summary = if (retained.isEmpty()) {
                                    it.summary.copy(title = "新会话", preview = "", updatedAt = System.currentTimeMillis())
                                } else {
                                    it.summary.copy(updatedAt = System.currentTimeMillis())
                                },
                                items = retained,
                                historyCursor = null,
                                remoteSessionId = null,
                                running = false,
                                activeTurnId = null,
                                turnStartedAt = null,
                                turnDurations = retainedDurations,
                                turnDiff = null,
                                tokenUsage = null,
                                plan = emptyList(),
                                planExplanation = null,
                                focusText = null,
                                reverting = false,
                                revertedMessage = if (restoreToComposer) message else null,
                            )
                        }
                    }
                }
            }
            return resolvedKey to message
        } finally {
            if (state(key) != null) update(key) { it.copy(reverting = false) }
            if (resolvedKey != key && state(resolvedKey) != null) update(resolvedKey) { it.copy(reverting = false) }
        }
    }

    fun revertBeforeTurn(
        key: ConversationKey,
        userMessageId: String,
        onMigrated: (ConversationKey) -> Unit = {},
    ) = launchOperation("回退轮次失败") {
        val (nextKey, message) = revertBeforeTurnInternal(
            key = key,
            userMessageId = userMessageId,
            rewindClaudeFiles = false,
            restoreToComposer = true,
        )
        if (nextKey != key) onMigrated(nextKey)
        notify(if (message.imageCount > 0) "会话已回退，请重新添加原消息图片" else "会话已回退到此轮前，本地文件未改动")
    }

    /**
     * 全引擎统一的「编辑并重发 / 重新生成」：
     * 先回退至目标用户消息之前（Codex 原生 thread/revert、Claude 官方 forkSession/reset、Antigravity/外部 Agent 历史截断并桥接上下文），
     * 若会话标识迁移（如 Claude 分支替换）则立刻平滑替换路由，随后以新内容与附件立即发送。
     */
    fun editAndResend(
        key: ConversationKey,
        userMessageId: String,
        newText: String,
        attachments: List<ImageAttachment> = emptyList(),
        rewindClaudeFiles: Boolean = false,
        onMigrated: (ConversationKey) -> Unit = {},
        onFailedBeforeSend: (String) -> Unit = {},
        onFinished: () -> Unit = {},
    ) = scope.launch {
        var reverted = false
        try {
            val cleanText = newText.trim()
            require(cleanText.isNotEmpty() || attachments.isNotEmpty()) { "消息内容不能为空" }
            val (targetKey, _) = revertBeforeTurnInternal(
                key = key,
                userMessageId = userMessageId,
                rewindClaudeFiles = rewindClaudeFiles,
                restoreToComposer = false,
            )
            reverted = true
            if (targetKey != key) onMigrated(targetKey)
            sendRecoverably(targetKey, cleanText, attachments)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val reason = error.message ?: error.javaClass.simpleName
            Log.w("GALIAIS", "编辑重发失败", error)
            notify("编辑重发失败：$reason")
            if (!reverted) onFailedBeforeSend(reason)
        } finally {
            onFinished()
        }
    }
    fun archive(key: ConversationKey) = launchOperation("归档失败") {
        when (key.engine) {
            EngineKind.CODEX -> {
                codex.archive(key)
                remove(key)
                notify("会话已归档")
            }
            EngineKind.CLAUDE -> {
                claude.archive(key)
                remove(key)
                notify("Claude 会话已归档")
            }
            else -> {
                runtime.hubRequest("sessions/archive", jsonOf("agentId" to key.engine.id, "sessionId" to key.id, "archived" to true))
                remove(key)
                notify("会话已归档")
            }
        }
    }
    fun unarchive(key: ConversationKey) = launchOperation("取消归档失败") {
        when (key.engine) {
            EngineKind.CODEX -> {
                codex.unarchive(key)
                refresh()
                notify("已从归档恢复会话")
            }
            EngineKind.CLAUDE -> {
                claude.unarchive(key)
                refresh()
                notify("已从归档恢复 Claude 会话")
            }
            else -> {
                runtime.hubRequest("sessions/archive", jsonOf("agentId" to key.engine.id, "sessionId" to key.id, "archived" to false))
                refresh()
                notify("已从归档恢复会话")
            }
        }
    }

    /** 三端统一 `/usage` 实时配额与会话用量报告。 */
    suspend fun usageReport(key: ConversationKey): EngineUsageReport = when (key.engine) {
        EngineKind.CODEX -> codex.usageReport(key)
        EngineKind.CLAUDE -> claude.usageReport(key)
        else -> hub.usageReport(key)
    }

    /** 三端统一 `/context` 上下文窗口精细拆解。 */
    suspend fun contextBreakdown(key: ConversationKey): ContextUsageBreakdown = when (key.engine) {
        EngineKind.CLAUDE -> claude.contextBreakdown(key, detail = "full")
        EngineKind.CODEX -> {
            val st = state(key)
            val u = st?.tokenUsage
            val used = u?.totalTokens ?: 0L
            val max = u?.contextWindow ?: inferModelContextWindow(st?.model) ?: 272_000L
            val pct = if (max > 0) (used * 100.0 / max).coerceIn(0.0, 100.0) else 0.0
            val rows = buildList {
                if (u != null) {
                    if (u.cachedInputTokens > 0) add(ContextCategoryRow("cached_input", "缓存命中输入", u.cachedInputTokens, "used"))
                    if (u.uncachedInputTokens > 0) add(ContextCategoryRow("uncached_input", "新增输入", u.uncachedInputTokens, "used"))
                    if (u.outputTokens > 0) add(ContextCategoryRow("output", "模型输出", u.outputTokens, "used"))
                    if (u.reasoningTokens > 0) add(ContextCategoryRow("reasoning", "推理思考", u.reasoningTokens, "used"))
                }
                add(ContextCategoryRow("free_space", "剩余可用窗口", (max - used).coerceAtLeast(0L), "free"))
            }
            ContextUsageBreakdown(EngineKind.CODEX, st?.model, used, max, pct, rows)
        }
        else -> {
            val st = state(key)
            val u = st?.tokenUsage
            val used = u?.totalTokens ?: 0L
            val max = u?.contextWindow ?: inferModelContextWindow(st?.model) ?: 1_048_576L
            val pct = if (max > 0) (used * 100.0 / max).coerceIn(0.0, 100.0) else 0.0
            val rows = buildList {
                if (used > 0) add(ContextCategoryRow("messages", "当前上下文占用", used, "used"))
                add(ContextCategoryRow("free_space", "剩余可用窗口", (max - used).coerceAtLeast(0L), "free"))
            }
            ContextUsageBreakdown(
                engine = key.engine,
                model = st?.model,
                totalTokens = used,
                maxTokens = max,
                percentage = pct,
                categories = rows,
                note = if (key.engine.id == "antigravity") "ACP 实时上报总上下文；如需分项视图可一键切入原生 TUI 执行 /context。" else null,
            )
        }
    }

    /** 三端统一 `/doctor` 健康体检。 */
    suspend fun diagnosticsReport(key: ConversationKey): EngineDiagnosticsReport = when (key.engine) {
        EngineKind.CODEX -> codex.diagnosticsReport()
        EngineKind.CLAUDE -> claude.diagnosticsReport(state(key)?.summary?.cwd)
        else -> hub.diagnosticsReport(key)
    }

    /** 指定消息节点分叉会话（Claude 原生支持 upToMessageId；Codex/Antigravity 支持整会话分叉）。 */
    suspend fun forkAtMessage(key: ConversationKey, upToMessageId: String? = null): ConversationKey? = when (key.engine) {
        EngineKind.CLAUDE -> claude.forkUpTo(key, upToMessageId)
        EngineKind.CODEX -> codex.fork(key)
        else -> hub.forkSession(key, upToMessageId)
    }

    /** 为会话动态附加工作区目录（`/add-dir`）。 */
    suspend fun addAdditionalDirectory(key: ConversationKey, dir: String) {
        val clean = dir.trim().trimEnd('/').ifEmpty { "/" }
        require(clean.startsWith("/")) { "附加目录必须是绝对路径" }
        when (key.engine) {
            EngineKind.CLAUDE -> claude.addAdditionalDirectory(key, clean)
            EngineKind.CODEX -> {
                update(key) { st -> st.copy(additionalDirectories = (st.additionalDirectories + clean).distinct()) }
                notify("已记录附加目录 $clean")
            }
            else -> hub.addAdditionalDirectory(key, clean)
        }
    }

    /** 构建无缝切入原生 TUI 终端的命令（复用当前会话 ID）。 */
    fun buildNativeTuiCommand(key: ConversationKey): String? {
        val st = state(key)
        val cwd = st?.summary?.cwd ?: settings.state.value.defaultCwd
        fun shQ(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val cdPrefix = "cd ${shQ(cwd)} && exec "
        return when (key.engine.id) {
            "codex" -> "${cdPrefix}codex resume ${shQ(key.id)}"
            "claude" -> "${cdPrefix}claude --resume ${shQ(key.id)}"
            "antigravity" -> {
                val remoteId = st?.remoteSessionId?.takeIf { it.isNotBlank() }
                remoteId?.let { "${cdPrefix}agy --conversation ${shQ(it)}" }
            }
            else -> "${cdPrefix}${shQ(key.engine.id)}"
        }
    }
    fun setCodexApproval(key: ConversationKey, policy: String, reviewer: String = "user") {
        require(policy in CODEX_APPROVAL_POLICIES && reviewer in CODEX_APPROVAL_REVIEWERS)
        update(key) { it.copy(approvalPolicy = policy, approvalsReviewer = reviewer) }
        persistOptions(key)
        val label = when {
            policy == "never" -> "完全访问"
            reviewer == "auto_review" -> "AI 自动审校"
            policy == "untrusted" -> "严格审批"
            else -> "按需审批"
        }
        notify("Codex 本会话已切换为「$label」（下一轮生效）")
    }

    fun setReasoningSummary(key: ConversationKey, summary: String) = launchOperation("切换推理摘要失败") {
        require(summary in REASONING_SUMMARIES)
        if (key.engine == EngineKind.CLAUDE) claude.setReasoningSummary(key, summary)
        else update(key) { it.copy(reasoningSummary = summary) }
        persistOptions(key)
        val label = when (summary) { "concise" -> "简明"; "detailed" -> "详细"; "none" -> "关闭"; else -> "自动" }
        notify("推理摘要已设为「$label」")
    }

    fun setServiceTier(key: ConversationKey, tier: String?) {
        require(key.engine == EngineKind.CODEX && (tier == null || tier.length in 1..256))
        update(key) { it.copy(serviceTier = tier) }
        persistOptions(key)
    }

    fun setCollaborationMode(key: ConversationKey, mode: String) {
        val clean = mode.trim()
        require(key.engine == EngineKind.CODEX && clean.isNotBlank() && clean.length <= 64)
        update(key) { it.copy(collaborationMode = clean) }
        persistOptions(key)
    }

    /** 列举全部三大引擎的已归档会话（Codex / Claude / Antigravity）。 */
    suspend fun listAllArchived(): List<ConversationSummary> {
        val out = mutableListOf<ConversationSummary>()
        runCatching { out += codex.listArchived() }
        runCatching { out += claude.listArchived() }
        runCatching {
            val res = runtime.hubRequest("sessions/list", jsonOf("includeArchived" to true))
            val arr = res.optJSONArray("sessions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (!s.optBoolean("archived", false)) continue
                    val agentId = s.str("agentId")?.takeIf { it.isNotBlank() } ?: continue
                    val id = s.str("id")?.takeIf { it.isNotBlank() } ?: continue
                    val known = AgentCatalog.find(agentId)
                    val label = known?.name ?: s.str("driverName") ?: agentId
                    out += ConversationSummary(
                        key = ConversationKey(EngineKind(agentId, label), id),
                        title = s.str("title") ?: "新会话",
                        preview = s.optString("preview"),
                        cwd = s.str("cwd"),
                        updatedAt = normalizeTime(s.optLong("updatedAt", System.currentTimeMillis())),
                        archived = true,
                    )
                }
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun setFastMode(key: ConversationKey, enabled: Boolean?) = launchOperation("切换快速模式失败") {
        require(key.engine == EngineKind.CLAUDE)
        claude.setFastMode(key, enabled)
        persistOptions(key)
    }

    suspend fun rewindClaudeFiles(key: ConversationKey, userMessageId: String, dryRun: Boolean): RewindPreviewResult =
        claude.rewindFiles(key, userMessageId, dryRun)

    fun setPermissionMode(key: ConversationKey, mode: String) = launchOperation("切换权限模式失败") {
        if (key.engine == EngineKind.CLAUDE) {
            claude.setPermissionMode(key, mode)
            persistOptions(key)
            val label = when (mode) {
                "acceptEdits" -> "自动允许编辑"
                "plan" -> "计划模式"
                "auto" -> "AI 自动审校"
                "bypassPermissions" -> "完全访问"
                else -> "默认询问"
            }
            notify("Claude Code 已切换为「$label」")
        } else if (AgentCatalog.capabilitiesOf(key.engine).modes) {
            hub.adapterFor(key.engine).setMode(key, mode)
            val modeLabel = if (key.engine.id == "antigravity") when (mode) {
                "accept-edits" -> "自动允许编辑"
                "auto" -> "智能自动"
                "plan" -> "计划模式"
                "bypass" -> "完全访问"
                else -> "默认询问"
            } else mode
            notify("${key.engine.label} 已切换为「$modeLabel」")
        }
    }
    fun compact(key: ConversationKey) = launchOperation("压缩上下文失败") {
        adapter(key.engine).compact(key)
        notify("已开始压缩上下文")
    }
    suspend fun fork(key: ConversationKey): ConversationKey? = adapter(key.engine).fork(key)
        ?.also(::persistOptions)
    fun startReview(key: ConversationKey, instructions: String? = null) = launchOperation("发起代码审查失败") {
        adapter(key.engine).startReview(key, instructions)
    }
    fun interrupt(key: ConversationKey) = launchOperation("中断失败") {
        delegation.cancel(key)
        adapter(key.engine).interrupt(key)
    }
    fun cancelDelegation(parent: ConversationKey) = launchOperation("取消委派失败") { delegation.cancel(parent) }
    /** 将所有自定义服务商与路由组同步到虚拟机内本地网关（仅驻留内存）与 Codex 配置。 */
    suspend fun syncProvidersNow(force: Boolean = false) = providerSyncMutex.withLock {
        if (!runtime.snapshot.value.ready) return@withLock
        val st = settings.state.value
        val list = st.providers
        val payload = JSONArray().apply {
            list.forEach { p -> put(p.toGatewayJson(settings.providerApiKey(p.id), settings.providerApiKeys(p.id))) }
        }
        val groupsPayload = JSONArray().apply { st.routingGroups.forEach { put(it.toJson()) } }
        val endpointTag = runtime.gatewayEndpoint?.let { "${it.first}:${it.second}" }.orEmpty()
        val fp = "$endpointTag|$payload|$groupsPayload"
        if (!force && fp == syncedProvidersFingerprint) return@withLock
        runtime.gatewayRequest("providers/sync", jsonOf("providers" to payload, "routingGroups" to groupsPayload))
        val codexProviders = list + st.routingGroups.filter { it.targets.isNotEmpty() }.mapNotNull { st.provider(it.virtualProviderId) }
        codex.syncProviders(codexProviders)
        syncedProvidersFingerprint = fp
    }

    fun syncProviders(force: Boolean = false) = launchOperation("同步自定义服务商失败") { syncProvidersNow(force) }

    suspend fun fetchRemoteModels(provider: CustomProvider, apiKeyOverride: String?): List<String> {
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: settings.providerApiKey(provider.id)
        val pool = if (apiKeyOverride?.isNotBlank() == true) listOf(apiKeyOverride.trim()) else settings.providerApiKeys(provider.id)
        val result = runtime.gatewayRequest("providers/listModels", jsonOf("provider" to provider.toGatewayJson(key, pool)))
        return result.arr("models").strings()
    }

    suspend fun testProvider(provider: CustomProvider, apiKeyOverride: String?, apiKeysPoolOverride: List<String>? = null): JSONObject {
        val pool = apiKeysPoolOverride ?: if (apiKeyOverride?.isNotBlank() == true) listOf(apiKeyOverride.trim()) else settings.providerApiKeys(provider.id)
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: pool.firstOrNull() ?: settings.providerApiKey(provider.id)
        return runtime.gatewayRequest("providers/test", jsonOf("provider" to provider.toGatewayJson(key, pool)))
    }

    suspend fun pingProvider(provider: CustomProvider, apiKeyOverride: String?, modelOverride: String? = null, apiKeysPoolOverride: List<String>? = null): JSONObject {
        val pool = apiKeysPoolOverride ?: if (apiKeyOverride?.isNotBlank() == true) listOf(apiKeyOverride.trim()) else settings.providerApiKeys(provider.id)
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: pool.firstOrNull() ?: settings.providerApiKey(provider.id)
        return runtime.gatewayRequest(
            "providers/ping",
            jsonOf(
                "provider" to provider.toGatewayJson(key, pool),
                "model" to (modelOverride?.takeIf { it.isNotBlank() } ?: provider.models.firstOrNull()),
            ),
        )
    }

    suspend fun fetchProviderHealth(): Map<String, ProviderHealthSnapshot> {
        if (!runtime.snapshot.value.ready) return emptyMap()
        val res = runtime.gatewayRequest("providers/health")
        val mapObj = res.optJSONObject("providers") ?: return emptyMap()
        return buildMap {
            mapObj.keys().forEach { pid ->
                val item = mapObj.optJSONObject(pid) ?: return@forEach
                put(
                    pid,
                    ProviderHealthSnapshot(
                        providerId = pid,
                        enabled = item.optBoolean("enabled", true),
                        keyCount = item.optInt("keyCount", 0),
                        coolingCount = item.optInt("coolingCount", 0),
                        maxCooldownSec = item.optInt("maxCooldownSec", 0),
                        learnedAnthropicAuth = item.str("learnedAnthropicAuth"),
                    ),
                )
            }
        }
    }

    suspend fun resetProviderCooldowns(providerId: String? = null): JSONObject {
        return runtime.gatewayRequest("providers/resetCooldowns", jsonOf("providerId" to providerId))
    }

    fun moveProvider(id: String, delta: Int) {
        settings.moveProvider(id, delta)
        if (runtime.snapshot.value.ready) syncProviders()
    }

    fun saveProvider(provider: CustomProvider, apiKey: String?, clearKey: Boolean = false, apiKeysPool: List<String>? = null) {
        settings.saveProvider(provider, apiKey, clearKey, apiKeysPool)
        if (runtime.snapshot.value.ready) syncProviders()
    }

    fun deleteProvider(id: String) {
        settings.deleteProvider(id)
        if (runtime.snapshot.value.ready) syncProviders()
    }

    fun saveRoutingGroup(group: RoutingGroup) {
        settings.saveRoutingGroup(group)
        if (runtime.snapshot.value.ready) syncProviders()
    }

    fun deleteRoutingGroup(id: String) {
        settings.deleteRoutingGroup(id)
        if (runtime.snapshot.value.ready) syncProviders()
    }

    fun setProvider(
        key: ConversationKey,
        providerId: String?,
        model: String?,
        onMigrated: (ConversationKey) -> Unit = {},
        onFinished: () -> Unit = {},
    ) = scope.launch {
        try {
            val nextKey = adapter(key.engine).setProvider(key, providerId, model)
            persistOptions(nextKey)
            val targetName = providerId?.let { settings.state.value.provider(it)?.name ?: it } ?: when (key.engine.id) {
                "codex" -> "OpenAI 官方"
                "claude" -> "Anthropic 官方"
                "antigravity" -> "Google 官方"
                else -> "官方配置"
            }
            if (nextKey != key) {
                onMigrated(nextKey)
                notify("已切换服务商为「$targetName」（已保留会话上下文）")
            } else {
                notify("已切换服务商为「$targetName」")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("GALIAIS", "切换服务商失败", error)
            notify("切换服务商失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            onFinished()
        }
    }
    fun setModel(key: ConversationKey, model: String?) = launchOperation("切换模型失败") {
        adapter(key.engine).setModel(key, model)
        persistOptions(key)
    }
    fun setEffort(key: ConversationKey, effort: String?) = launchOperation("切换推理强度失败") {
        adapter(key.engine).setEffort(key, effort)
        persistOptions(key)
    }
    fun rename(key: ConversationKey, title: String) = launchOperation("重命名失败") {
        adapter(key.engine).rename(key, title)
        update(key) { it.copy(summary = it.summary.copy(title = title)) }
    }
    fun delete(key: ConversationKey) = launchOperation("删除失败") {
        adapter(key.engine).delete(key)
        settings.removeConversationOptions(key)
        arenaGroupsFlow.value = arenaGroupsFlow.value
            .mapValues { (_, list) -> list.filterNot { it == key } }
            .filterValues { it.size >= 2 }
        remove(key)
    }

    /** 将 Android 端本机 APK 自迭代配置（默认关闭）同步到 Agent Hub 运行时持久层。 */
    suspend fun syncSelfBuildConfigNow() {
        if (!runtime.hubAvailable) return
        runtime.hubRequest("selfBuild/saveConfig", settings.state.value.selfBuild.toJson(), timeoutMs = 15_000)
    }

    /** 将整个会话时间线导出为整洁的 Markdown 文档，方便复制或保存归档。 */
    suspend fun exportMarkdown(key: ConversationKey): String {
        val snapshot = state(key) ?: return ""
        val state = completeHistory(snapshot)
        return withContext(Dispatchers.Default) { formatMarkdown(state) }
    }

    private fun formatMarkdown(state: ConversationState): String {
        val key = state.summary.key
        val sb = StringBuilder()
        sb.appendLine("# ${state.summary.title}")
        sb.appendLine()
        sb.appendLine("- **引擎**：${key.engine.label}")
        state.model?.let { sb.appendLine("- **模型**：`$it`") }
        state.summary.cwd?.let { sb.appendLine("- **工作目录**：`$it`") }
        state.tokenUsage?.let { sb.appendLine("- **Token 用量**：${it.compactLabel()}") }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        for (item in state.items) {
            when (item) {
                is TimelineItem.UserMessage -> {
                    sb.appendLine("## 用户")
                    if (item.imageCount > 0) sb.appendLine("_（附带 ${item.imageCount} 张图片）_")
                    sb.appendLine(item.text)
                    sb.appendLine()
                }
                is TimelineItem.AgentMessage -> {
                    sb.appendLine("## ${key.engine.label}")
                    sb.appendLine(item.text)
                    sb.appendLine()
                }
                is TimelineItem.Reasoning -> {
                    if (item.summaryText.isNotBlank()) {
                        sb.appendLine("<details><summary>推理摘要</summary>")
                        sb.appendLine()
                        sb.appendLine(item.summaryText)
                        sb.appendLine()
                        sb.appendLine("</details>")
                        sb.appendLine()
                    }
                }
                is TimelineItem.Plan -> {
                    sb.appendLine("### 执行计划")
                    sb.appendLine(item.text)
                    sb.appendLine()
                }
                is TimelineItem.CommandRun -> {
                    sb.appendLine("### 终端命令 `${item.command}`" + (item.exitCode?.let { " (exit $it)" } ?: ""))
                    if (item.output.isNotBlank()) {
                        sb.appendLine("```")
                        sb.appendLine(item.output.takeLast(8000))
                        sb.appendLine("```")
                    }
                    sb.appendLine()
                }
                is TimelineItem.FileEdit -> {
                    sb.appendLine("### 文件修改")
                    for (c in item.changes) {
                        sb.appendLine("#### `${c.path}` (${c.kind})")
                        sb.appendLine("```diff")
                        sb.appendLine(c.diff)
                        sb.appendLine("```")
                    }
                    sb.appendLine()
                }
                is TimelineItem.ToolCall -> {
                    sb.appendLine("### 工具调用 `${item.name}`")
                    if (item.input.isNotBlank()) {
                        sb.appendLine("```json")
                        sb.appendLine(item.input.take(4000))
                        sb.appendLine("```")
                    }
                    sb.appendLine()
                }
                is TimelineItem.Delegation -> {
                    sb.appendLine("### 跨 Agent 委派 → ${item.target.label}")
                    sb.appendLine(item.task)
                    item.result?.let { sb.appendLine().appendLine(it) }
                    sb.appendLine()
                }
                is TimelineItem.InteractionReceipt -> {
                    sb.appendLine("### ${item.title}")
                    for ((k, v) in item.entries) {
                        sb.appendLine("- **$k**：$v")
                    }
                    sb.appendLine()
                }
                is TimelineItem.Notice -> {
                    sb.appendLine("> ${item.text}")
                    sb.appendLine()
                }
            }
        }
        return sb.toString().trim()
    }
}
