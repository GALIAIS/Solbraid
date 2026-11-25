package com.galiais.agent.engine

import android.util.Log
import com.galiais.agent.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 工单阶段模型（借鉴 dsh-superagent 003-execution.md：严格区分「需人介入」与「静默后台执行」）。 */
enum class TicketStage(val id: String, val label: String, val needsAttention: Boolean, val priority: Int) {
    AWAITING_ANSWER("awaiting_answer", "待审批 / 待回答", true, 0),
    AWAITING_REVIEW("awaiting_review", "待验收", true, 1),
    BLOCKED_FAILED("blocked_failed", "阻塞 / 失败", true, 2),
    WORKING("working", "后台执行中", false, 3),
    QUEUED("queued", "排队等待槽位", false, 4),
    MERGED("merged", "已合并", false, 5),
    DISCARDED("discarded", "已丢弃", false, 6);

    companion object {
        fun of(id: String?): TicketStage = entries.firstOrNull { it.id == id } ?: QUEUED
    }
}

/** 单个并行 Worker 工单（支持独立 Git Worktree 隔离、按角色路由模型、Diff 统计与一键合并/丢弃）。 */
data class WorkTicket(
    val id: String,
    val title: String,
    val task: String,
    val role: String = RoleRouteConfig.ROLE_WORKER,
    val engineId: String = "codex",
    val providerId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val repoCwd: String,
    val repoRoot: String = repoCwd,
    val worktreePath: String = repoCwd,
    val targetCwd: String = repoCwd,
    val branch: String? = null,
    val baseBranch: String? = null,
    val baseCommit: String? = null,
    val isolated: Boolean = false,
    val wantWorktree: Boolean = true,
    val cleanupPending: Boolean = false,
    val childConversation: ConversationKey? = null,
    val parentConversation: ConversationKey? = null,
    val stage: TicketStage = TicketStage.QUEUED,
    val summary: String = "",
    val error: String? = null,
    val diffText: String = "",
    val diffWarning: String? = null,
    val filesChanged: List<String> = emptyList(),
    val addedLines: Int = 0,
    val removedLines: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = stage !in setOf(TicketStage.MERGED, TicketStage.DISCARDED)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("task", task)
        .put("role", role)
        .put("engineId", engineId)
        .put("providerId", providerId ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL)
        .put("effort", effort ?: JSONObject.NULL)
        .put("repoCwd", repoCwd)
        .put("repoRoot", repoRoot)
        .put("worktreePath", worktreePath)
        .put("targetCwd", targetCwd)
        .put("branch", branch ?: JSONObject.NULL)
        .put("baseBranch", baseBranch ?: JSONObject.NULL)
        .put("baseCommit", baseCommit ?: JSONObject.NULL)
        .put("isolated", isolated)
        .put("wantWorktree", wantWorktree)
        .put("cleanupPending", cleanupPending)
        .put("childEngine", childConversation?.engine?.id ?: JSONObject.NULL)
        .put("childId", childConversation?.id ?: JSONObject.NULL)
        .put("parentEngine", parentConversation?.engine?.id ?: JSONObject.NULL)
        .put("parentId", parentConversation?.id ?: JSONObject.NULL)
        .put("stage", stage.id)
        .put("summary", summary)
        .put("error", error ?: JSONObject.NULL)
        .put("diffWarning", diffWarning ?: JSONObject.NULL)
        .put("filesChanged", JSONArray(filesChanged))
        .put("addedLines", addedLines)
        .put("removedLines", removedLines)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(obj: JSONObject): WorkTicket {
            val childEng = obj.str("childEngine")
            val childId = obj.str("childId")
            val parentEng = obj.str("parentEngine")
            val parentId = obj.str("parentId")
            val rawStage = TicketStage.of(obj.str("stage"))
            // 重启恢复时曾处于执行中的工单标记为阻塞以便人工确认续跑
            val restoredStage = if (rawStage == TicketStage.WORKING || rawStage == TicketStage.QUEUED) TicketStage.BLOCKED_FAILED else rawStage
            return WorkTicket(
                id = obj.getString("id"),
                title = obj.optString("title", "未命名工单"),
                task = obj.optString("task"),
                role = obj.optString("role", RoleRouteConfig.ROLE_WORKER),
                engineId = obj.optString("engineId", "codex"),
                providerId = obj.str("providerId"),
                model = obj.str("model"),
                effort = obj.str("effort"),
                repoCwd = obj.optString("repoCwd", DEFAULT_WORKSPACE),
                repoRoot = obj.optString("repoRoot", obj.optString("repoCwd", DEFAULT_WORKSPACE)),
                worktreePath = obj.optString("worktreePath", obj.optString("repoCwd", DEFAULT_WORKSPACE)),
                targetCwd = obj.optString("targetCwd", obj.optString("worktreePath", DEFAULT_WORKSPACE)),
                branch = obj.str("branch"),
                baseBranch = obj.str("baseBranch"),
                baseCommit = obj.str("baseCommit"),
                isolated = obj.optBoolean("isolated"),
                wantWorktree = obj.optBoolean("wantWorktree", true),
                cleanupPending = obj.optBoolean("cleanupPending"),
                childConversation = if (childEng != null && childId != null) ConversationKey(EngineKind.of(childEng), childId) else null,
                parentConversation = if (parentEng != null && parentId != null) ConversationKey(EngineKind.of(parentEng), parentId) else null,
                stage = restoredStage,
                summary = obj.optString("summary"),
                error = obj.str("error") ?: if (restoredStage != rawStage) "运行环境已重启，可点击继续执行" else null,
                diffWarning = obj.str("diffWarning"),
                filesChanged = obj.arr("filesChanged").strings(),
                addedLines = obj.optInt("addedLines"),
                removedLines = obj.optInt("removedLines"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

fun roleDisplayName(role: String): String = when (role) {
    RoleRouteConfig.ROLE_ORCHESTRATOR -> "总控规划"
    RoleRouteConfig.ROLE_REVIEWER -> "代码审查"
    else -> "实现工匠"
}

/**
 * Mobile Super-Agent 多工单并行编排器：
 * - 按角色路由引擎/模型（总控规划 / 实现工匠 / 代码审查）
 * - 支持多工单在独立 Git Worktree 中并行执行（并发上限由设置控制，默认 2 槽）
 * - 实时感知子会话审批/提问状态，自动置顶到首页「需要你处理」注意力收件箱
 * - 验收闭环：Diff 预览 → 一键压缩合并到主分支并清理 Worktree，或直接丢弃
 */
class SuperAgentCoordinator(
    private val repo: ConversationRepository,
    private val runtime: RuntimeClient,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticketsFlow = MutableStateFlow<List<WorkTicket>>(emptyList())
    val tickets: StateFlow<List<WorkTicket>> = ticketsFlow.asStateFlow()

    private val runningJobs = mutableMapOf<String, Job>()
    private val pendingPrompts = mutableMapOf<String, String>()
    private val stopRequested = mutableSetOf<String>()
    private val deletedTicketIds = mutableSetOf<String>()
    private val ticketPersistence = Mutex()
    private var schedulingPaused = true
    private val busyTicketsFlow = MutableStateFlow<Set<String>>(emptySet())
    val busyTickets: StateFlow<Set<String>> = busyTicketsFlow.asStateFlow()

    init {
        // 实时监听待处理审批/提问：若某个工单的子会话正在等用户审批，立即将其阶段切换为 AWAITING_ANSWER
        scope.launch {
            repo.requests.collect { reqs ->
                val waitingKeys = reqs.mapNotNull { it.conversation }.toSet()
                val current = ticketsFlow.value
                var changed = false
                val updated = current.map { t ->
                    val child = t.childConversation
                    when {
                        child != null && child in waitingKeys && t.stage == TicketStage.WORKING -> {
                            changed = true; t.copy(stage = TicketStage.AWAITING_ANSWER, updatedAt = System.currentTimeMillis())
                        }
                        child != null && child !in waitingKeys && t.stage == TicketStage.AWAITING_ANSWER -> {
                            changed = true; t.copy(stage = TicketStage.WORKING, updatedAt = System.currentTimeMillis())
                        }
                        else -> t
                    }
                }
                if (changed) ticketsFlow.value = updated
            }
        }
        scope.launch {
            runtime.snapshot.collect { if (it.ready) drainQueue() }
        }
    }

    suspend fun loadPersisted() {
        try {
            if (!runtime.hubAvailable) return
            val res = runtime.hubRequest("tickets/list", JSONObject(), timeoutMs = 10_000)
            val list = res.arr("tickets").objects().mapNotNull { runCatching { WorkTicket.fromJson(it) }.getOrNull() }
            val knownIds = ticketsFlow.value.map { it.id }.toSet()
            ticketsFlow.value = list.filterNot { it.id in knownIds } + ticketsFlow.value
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w("GALIAIS", "读取持久化工单失败", e)
        } finally {
            schedulingPaused = false
            drainQueue()
        }
    }

    fun resetActiveJobs() {
        schedulingPaused = true
        pendingPrompts.clear()
        runningJobs.values.toList().forEach { it.cancel() }
        ticketsFlow.value.filter { it.stage == TicketStage.QUEUED }.forEach {
            upsert(it.copy(stage = TicketStage.BLOCKED_FAILED, error = "运行环境已停止，请就绪后重试", updatedAt = System.currentTimeMillis()))
        }
    }

    /** 派发单个工单（自动按角色路由模型、进入队列并在有空槽时启动独立 Worktree 执行）。 */
    fun dispatchTicket(
        title: String,
        task: String,
        repoCwd: String,
        role: String = RoleRouteConfig.ROLE_WORKER,
        engineOverride: EngineKind? = null,
        isolatedWorktree: Boolean = true,
        parentConversation: ConversationKey? = null,
    ): WorkTicket {
        val st = settings.state.value
        val route = st.roleRoutes[role] ?: RoleRouteConfig(engineId = "codex")
        val engineId = engineOverride?.id ?: route.engineId
        val shortId = newId().take(8)
        val cleanTitle = title.trim().ifBlank { task.lineSequence().firstOrNull { it.isNotBlank() }?.take(36) ?: "工单 #$shortId" }
        val ticket = WorkTicket(
            id = shortId,
            title = cleanTitle,
            task = task.trim(),
            role = role,
            engineId = engineId,
            providerId = if (engineOverride != null && engineOverride.id != route.engineId) null else route.providerId,
            model = if (engineOverride != null && engineOverride.id != route.engineId) null else route.model,
            effort = if (engineOverride != null && engineOverride.id != route.engineId) null else route.effort,
            repoCwd = repoCwd,
            wantWorktree = isolatedWorktree,
            parentConversation = parentConversation,
            stage = TicketStage.QUEUED,
        )
        upsert(ticket)
        repo.notify("已创建工单：${ticket.title}")
        drainQueue()
        return ticket
    }

    /** 一次批量派发多个并行工单（各自在独立 Git Worktree 中运行，受最大并发槽位约束）。 */
    fun dispatchBatch(repoCwd: String, specs: List<Triple<String, String, String>>, isolatedWorktree: Boolean = true): List<WorkTicket> {
        val created = specs.filter { it.second.isNotBlank() }.map { (title, task, role) ->
            dispatchTicket(title = title, task = task, repoCwd = repoCwd, role = role, isolatedWorktree = isolatedWorktree)
        }
        return created
    }

    private fun drainQueue() {
        if (schedulingPaused || !runtime.snapshot.value.ready) return
        val maxSlots = settings.state.value.maxParallelWorkers.coerceIn(1, 3)
        val activeCount = runningJobs.size
        val freeSlots = (maxSlots - activeCount).coerceAtLeast(0)
        if (freeSlots == 0) return
        val queued = ticketsFlow.value.filter {
            it.stage == TicketStage.QUEUED && it.id !in runningJobs && it.id !in busyTicketsFlow.value
        }.sortedBy { it.createdAt }.take(freeSlots)
        for (ticket in queued) {
            launchWorker(ticket, pendingPrompts.remove(ticket.id))
        }
    }

    private fun launchWorker(initial: WorkTicket, followUp: String? = null) {
        if (initial.id in runningJobs || ticketsFlow.value.firstOrNull { it.id == initial.id }?.stage != TicketStage.QUEUED) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var current = initial.copy(stage = TicketStage.WORKING, error = null, updatedAt = System.currentTimeMillis())
                upsert(current)

                val engine = EngineKind.of(current.engineId)
                if (engine == EngineKind.CLAUDE) {
                    check(current.repoCwd in settings.state.value.trustedProjects) { "请先确认信任此项目，再执行 Claude 工单" }
                }

                if (current.wantWorktree && !current.isolated) {
                    check(current.childConversation == null) { "该旧工单未隔离，请检查原目录后重新派发" }
                    check(runtime.hubAvailable) { "Agent 服务未就绪，无法创建隔离工单" }
                    val wt = runtime.hubRequest("worktree/prepare", jsonOf("repoCwd" to current.repoCwd, "ticketId" to current.id), timeoutMs = 100_000)
                    check(wt.optBoolean("isolated")) { wt.str("warning") ?: "未能创建隔离目录，工单已停止" }
                    fun requiredPath(name: String): String = checkNotNull(wt.str(name)?.takeIf { it.isNotBlank() }) { "隔离结果缺少 $name，工单已停止" }
                    current = current.copy(
                        isolated = true,
                        repoRoot = requiredPath("repoRoot"),
                        worktreePath = requiredPath("worktreePath"),
                        targetCwd = requiredPath("targetCwd"),
                        branch = requiredPath("branch"),
                        baseBranch = requiredPath("baseBranch"),
                        baseCommit = requiredPath("baseCommit"),
                        updatedAt = System.currentTimeMillis(),
                    )
                    upsert(current)
                }
                checkWorkerMayRun(current.id)

                // 2. 创建或复用 Worker 独立会话
                if (engine == EngineKind.CLAUDE) {
                    check(current.repoCwd in settings.state.value.trustedProjects) { "此项目的信任已撤销，工单已停止" }
                    if (current.isolated) settings.trust(current.targetCwd)
                }
                val childKey = current.childConversation ?: run {
                    val created = repo.create(
                        engine = engine,
                        cwd = current.targetCwd,
                        options = CreateOptions(
                            providerId = current.providerId,
                            model = current.model,
                            parent = current.parentConversation,
                            allowDelegation = false,
                        ),
                    )
                    current.effort?.let { eff -> runCatching { repo.adapter(engine).setEffort(created, eff) } }
                    repo.update(created) { it.copy(summary = it.summary.copy(title = "[${roleDisplayName(current.role)}] ${current.title}")) }
                    current = current.copy(childConversation = created, updatedAt = System.currentTimeMillis())
                    upsert(current)
                    created
                }
                checkWorkerMayRun(current.id)

                // 3. 发送工单指令并等待本轮执行完成
                val prompt = followUp ?: buildWorkerPrompt(current)
                val reply = when (engine.id) {
                    "codex" -> repo.codex.runTask(childKey, prompt)
                    "claude" -> repo.claude.runTask(childKey, prompt)
                    else -> {
                        repo.adapter(engine).send(childKey, prompt)
                        repo.state(childKey)?.items?.lastOrNull { it is TimelineItem.AgentMessage }
                            ?.let { (it as TimelineItem.AgentMessage).text } ?: ""
                    }
                }
                checkWorkerMayRun(current.id)

                // 4. 采集 Worktree 增量 Diff 与增删统计，转入「待验收」
                val diffInfo = captureDiff(current)
                val summary = reply.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
                    ?: if (diffInfo.files.isNotEmpty()) "已完成，修改了 ${diffInfo.files.size} 个文件" else "已完成，请查看工单详情"
                current = current.copy(
                    stage = TicketStage.AWAITING_REVIEW,
                    summary = summary,
                    error = null,
                    diffText = diffInfo.diff,
                    diffWarning = diffInfo.warning,
                    filesChanged = diffInfo.files,
                    addedLines = diffInfo.added,
                    removedLines = diffInfo.removed,
                    updatedAt = System.currentTimeMillis(),
                )
                upsert(current)
                repo.notify("工单待验收：${current.title}")
            } catch (cancel: CancellationException) {
                val cur = ticketsFlow.value.firstOrNull { it.id == initial.id }
                if (cur != null && cur.stage in setOf(TicketStage.WORKING, TicketStage.AWAITING_ANSWER)) {
                    upsert(cur.copy(stage = TicketStage.BLOCKED_FAILED, error = "已取消执行", updatedAt = System.currentTimeMillis()))
                }
                throw cancel
            } catch (e: Exception) {
                val cur = ticketsFlow.value.firstOrNull { it.id == initial.id } ?: initial
                upsert(cur.copy(stage = TicketStage.BLOCKED_FAILED, error = e.message ?: "工单执行失败", updatedAt = System.currentTimeMillis()))
                repo.notify("工单阻塞：${cur.title}")
            } finally {
                if (runningJobs[initial.id] === coroutineContext[Job]) {
                    runningJobs.remove(initial.id)
                    stopRequested.remove(initial.id)
                }
                drainQueue()
            }
        }
        runningJobs[initial.id] = job
        job.start()
    }

    private fun checkWorkerMayRun(ticketId: String) {
        if (ticketId in stopRequested || schedulingPaused || !runtime.snapshot.value.ready) throw CancellationException("工单已停止")
    }

    private fun buildWorkerPrompt(ticket: WorkTicket): String = buildString {
        appendLine("你正在作为 Solbraid「${roleDisplayName(ticket.role)}」执行独立工单：${ticket.title}")
        if (ticket.isolated && ticket.branch != null) {
            appendLine("你运行在隔离的 Git Worktree 分支 `${ticket.branch}` 中，可放心完成修改与验证，但不要自行切换主分支或推送远程。")
        }
        appendLine("请直接聚焦任务完成改动与必要验证，完成后给出简洁的结果摘要（改了什么、验证了什么、是否存在未解决阻塞）。")
        appendLine()
        append(ticket.task)
    }

    private data class DiffSnapshot(val diff: String, val files: List<String>, val added: Int, val removed: Int, val warning: String?)

    private suspend fun captureDiff(ticket: WorkTicket): DiffSnapshot {
        return try {
            check(runtime.hubAvailable) { "运行环境未连接" }
            val res = runtime.hubRequest("worktree/diff", jsonOf("worktreePath" to ticket.worktreePath, "baseCommit" to ticket.baseCommit), timeoutMs = 25_000)
            DiffSnapshot(
                diff = res.optString("diff"),
                files = res.arr("files").strings(),
                added = res.optInt("added"),
                removed = res.optInt("removed"),
                warning = res.str("warning"),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            DiffSnapshot(ticket.diffText, ticket.filesChanged, ticket.addedLines, ticket.removedLines,
                "变更读取失败：${error.message ?: "请重试"}。请在工作区核对后再合并。")
        }
    }

    suspend fun refreshTicketDiff(ticketId: String): WorkTicket? {
        val cur = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: return null
        val diffInfo = captureDiff(cur)
        val latest = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: return null
        if (ticketId in busyTicketsFlow.value || latest.stage != cur.stage) return latest
        val next = latest.copy(
            diffText = diffInfo.diff,
            diffWarning = diffInfo.warning,
            filesChanged = diffInfo.files,
            addedLines = diffInfo.added,
            removedLines = diffInfo.removed,
            updatedAt = System.currentTimeMillis(),
        )
        upsert(next)
        return next
    }

    private suspend fun ticketOperation(
        ticketId: String,
        label: String,
        action: suspend (WorkTicket) -> Pair<Boolean, String>,
    ): Pair<Boolean, String> = scope.async {
        val cur = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: return@async false to "工单不存在"
        if (ticketId in busyTicketsFlow.value) return@async false to "工单正在处理，请稍后重试"
        busyTicketsFlow.value += ticketId
        try {
            action(cur)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            val message = "${label}失败：${e.message ?: "未知错误"}"
            ticketsFlow.value.firstOrNull { it.id == ticketId }?.let {
                upsert(it.copy(error = message, updatedAt = System.currentTimeMillis()))
            }
            repo.notify(message)
            false to message
        } finally {
            busyTicketsFlow.value -= ticketId
            drainQueue()
        }
    }.await()

    suspend fun mergeTicket(ticketId: String, squash: Boolean = true): Pair<Boolean, String> =
        ticketOperation(ticketId, "合并") { cur ->
            check(cur.stage == TicketStage.AWAITING_REVIEW) { "工单尚未完成，不能合并" }
            check(cur.id !in runningJobs && cur.childConversation?.let { repo.state(it)?.running } != true) { "工单会话仍在执行，请先停止" }
            if (!cur.isolated) {
                check(!cur.wantWorktree) { "该工单未完成隔离，不能标记为合并成功" }
                upsert(cur.copy(stage = TicketStage.MERGED, error = null, updatedAt = System.currentTimeMillis()))
                repo.notify("已标记工单完成：${cur.title}")
                return@ticketOperation true to "该工单直接在当前目录完成，已归档"
            }
            check(runtime.snapshot.value.ready && runtime.hubAvailable) { "运行环境未就绪" }
            val res = try {
                runtime.hubRequest(
                    "worktree/merge",
                    jsonOf(
                        "repoRoot" to cur.repoRoot,
                        "worktreePath" to cur.worktreePath,
                        "branch" to cur.branch,
                        "baseBranch" to cur.baseBranch,
                        "baseCommit" to cur.baseCommit,
                        "squash" to squash,
                        "commitMessage" to "Solbraid(${cur.id}): ${cur.title}",
                    ),
                    timeoutMs = 240_000,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                throw IllegalStateException("请检查主工作区与工单分支后重试，操作结果尚未确认。${e.message ?: ""}", e)
            }
            val ok = res.optBoolean("ok")
            val msg = res.optString("message", if (ok) "合并成功" else "合并失败")
            if (ok) {
                if (cur.isolated && cur.targetCwd != cur.repoCwd) settings.untrust(cur.targetCwd)
                val cleanupPending = res.optBoolean("cleanupPending")
                upsert(cur.copy(stage = TicketStage.MERGED, cleanupPending = cleanupPending, error = if (cleanupPending) msg else null, updatedAt = System.currentTimeMillis()))
                repo.notify(if (cleanupPending) msg else "已合并工单：${cur.title}")
            } else {
                val conflicts = res.arr("conflicts").strings()
                val detail = if (conflicts.isNotEmpty()) "$msg（冲突文件：${conflicts.joinToString("、")}）" else msg
                upsert(cur.copy(error = detail, updatedAt = System.currentTimeMillis()))
                repo.notify(detail)
            }
            ok to msg
        }

    private suspend fun discardWorktree(ticket: WorkTicket) {
        if (!ticket.isolated) return
        check(runtime.snapshot.value.ready && runtime.hubAvailable) { "请先启动运行环境，再清理隔离目录" }
        val res = runtime.hubRequest(
            "worktree/discard",
            jsonOf("repoRoot" to ticket.repoRoot, "worktreePath" to ticket.worktreePath, "branch" to ticket.branch),
            timeoutMs = 120_000,
        )
        check(res.optBoolean("ok")) { res.str("message") ?: "隔离目录清理失败，已保留工单记录" }
    }

    suspend fun discardTicket(ticketId: String): Pair<Boolean, String> = ticketOperation(ticketId, "丢弃") {
        stopWorker(ticketId)
        val cur = ticketsFlow.value.first { it.id == ticketId }
        discardWorktree(cur)
        if (cur.isolated && cur.targetCwd != cur.repoCwd) settings.untrust(cur.targetCwd)
        upsert(cur.copy(stage = TicketStage.DISCARDED, cleanupPending = false, error = null, updatedAt = System.currentTimeMillis()))
        repo.notify("已丢弃工单：${cur.title}")
        true to "已丢弃工单"
    }

    fun retryTicket(ticketId: String, followUp: String? = null) {
        val cur = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: return
        if (cur.id in runningJobs || cur.id in busyTicketsFlow.value || cur.stage != TicketStage.BLOCKED_FAILED) return
        if (cur.childConversation?.let { repo.state(it)?.running } == true) {
            repo.notify("工单会话仍在执行，请先停止后重试")
            return
        }
        if (!runtime.snapshot.value.ready) {
            repo.notify("请先启动运行环境，再重试工单")
            return
        }
        if (followUp != null) pendingPrompts[ticketId] = followUp else pendingPrompts.remove(ticketId)
        upsert(cur.copy(stage = TicketStage.QUEUED, error = null, updatedAt = System.currentTimeMillis()))
        drainQueue()
    }

    private suspend fun stopWorker(ticketId: String) {
        pendingPrompts.remove(ticketId)
        stopRequested += ticketId
        try {
            val stopped = withTimeoutOrNull(110_000) {
                val job = runningJobs[ticketId]
                // 先等准备阶段落盘；仅在收到会话停止事件后取消等待，防止删掉仍在写入的目录。
                while (job?.isActive == true) {
                    val child = ticketsFlow.value.firstOrNull { it.id == ticketId }?.childConversation
                    if (child != null && repo.state(child)?.running == true) {
                        interruptAndWait(child)
                        job.cancelAndJoin()
                    } else {
                        delay(100)
                    }
                }
                val cur = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: return@withTimeoutOrNull true
                cur.childConversation?.let { child ->
                    val state = repo.state(child)
                    // 重启后未打开或已删除的子会话不会处于运行态，允许直接继续丢弃/清除，避免变成不可删除的僵尸工单
                    if (state != null && state.loaded && state.running) {
                        interruptAndWait(child)
                    } else if (state?.running == true) {
                        runCatching { repo.adapter(child.engine).interrupt(child) }
                    }
                }
                if (cur.stage in setOf(TicketStage.WORKING, TicketStage.QUEUED, TicketStage.AWAITING_ANSWER)) {
                    upsert(cur.copy(stage = TicketStage.BLOCKED_FAILED, error = "已由用户中断", updatedAt = System.currentTimeMillis()))
                }
                true
            }
            check(stopped == true) { "尚未确认工单停止，已保留目录，请检查会话后重试" }
        } finally {
            if (ticketId !in runningJobs) stopRequested.remove(ticketId)
        }
    }

    private suspend fun interruptAndWait(child: ConversationKey) {
        check(runtime.snapshot.value.ready) { "运行环境已断开，无法确认工单停止" }
        repo.adapter(child.engine).interrupt(child)
        val stopped = withTimeoutOrNull(15_000) {
            repo.conversations.first { states -> states[child]?.let { it.loaded && !it.running } == true }
        }
        check(stopped != null && runtime.snapshot.value.ready) { "尚未收到工单停止结果，已保留目录" }
    }

    suspend fun cancelTicket(ticketId: String): Pair<Boolean, String> = ticketOperation(ticketId, "中断") {
        stopWorker(ticketId)
        true to "已停止工单"
    }

    suspend fun removeTicket(ticketId: String): Pair<Boolean, String> = ticketOperation(ticketId, "清除记录") { cur ->
        check(runtime.snapshot.value.ready && runtime.hubAvailable) { "请先启动运行环境，再清除工单记录" }
        if (cur.active || cur.cleanupPending) {
            stopWorker(ticketId)
            val latest = ticketsFlow.value.firstOrNull { it.id == ticketId } ?: cur
            discardWorktree(latest)
            if (latest.isolated && latest.targetCwd != latest.repoCwd) settings.untrust(latest.targetCwd)
        }
        deletedTicketIds += ticketId
        ticketPersistence.withLock {
            runtime.hubRequest("tickets/delete", jsonOf("ticketId" to ticketId), timeoutMs = 10_000)
        }
        ticketsFlow.value = ticketsFlow.value.filterNot { it.id == ticketId }
        true to "已清除工单记录"
    }

    private fun upsert(ticket: WorkTicket) {
        if (ticket.id in deletedTicketIds) return
        val next = (ticketsFlow.value.filterNot { it.id == ticket.id } + ticket)
            .sortedWith(compareBy<WorkTicket> { it.stage.priority }.thenByDescending { it.updatedAt })
        ticketsFlow.value = next
        if (runtime.hubAvailable) {
            scope.launch {
                try {
                    ticketPersistence.withLock {
                        if (ticket.id in deletedTicketIds) return@withLock
                        runtime.hubRequest("tickets/save", jsonOf("ticket" to ticket.toJson()), timeoutMs = 10_000)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w("GALIAIS", "保存工单状态失败", e)
                    repo.notify("工单状态保存失败：${e.message ?: "请检查运行环境"}")
                }
            }
        }
    }
}
