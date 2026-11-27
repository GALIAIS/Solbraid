package com.galiais.agent.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 跨引擎委派：Codex 通过 dynamic tool、Claude 通过 SDK 进程内 MCP 工具发起，
 * 经用户确认后由 App 在另一个引擎中新开子会话执行，并把最终回答返回给发起方。
 */
class DelegationCoordinator(private val repo: ConversationRepository) {
    private class ActiveRun(
        val request: PendingRequest.Delegation,
        val itemId: String,
        var job: Job? = null,
        var child: ConversationKey? = null,
        var responded: Boolean = false,
    )

    private val active = mutableMapOf<ConversationKey, ActiveRun>()
    private val accepted = mutableSetOf<String>()

    fun requested(from: EngineKind, requestId: Any, parent: ConversationKey?, target: EngineKind, task: String, cwd: String?) {
        val token = "${from.id}:$requestId"
        if (token in accepted) return
        val reason = when {
            !repo.settings.state.value.delegationEnabled -> "用户已在设置中关闭跨 Agent 委派"
            parent == null -> "委派请求缺少来源会话"
            target == from -> "不能将任务委派给自身引擎"
            AgentCatalog.find(target.id) == null && !repo.hub.isInstalled(target.id) -> "目标 Agent 不存在：${target.id}"
            task.isBlank() || task.length > 20_000 -> "委派任务描述无效"
            parent in active -> "该会话已有一个委派正在执行"
            else -> null
        }
        if (reason != null) { respond(from, requestId, reason, false); return }
        repo.addRequest(PendingRequest.Delegation(from, requestId, parent, target, task, cwd ?: repo.state(parent!!)?.summary?.cwd))
    }

    fun resolve(request: PendingRequest.Delegation, decision: Decision) {
        if (decision !is Decision.Allow) { respond(request.engine, request.requestId, "用户拒绝了委派", false); return }
        val parent = request.conversation ?: return
        if (parent in active) {
            respond(request.engine, request.requestId, "该会话已有委派正在运行，请稍后重试", false)
            return
        }
        val token = "${request.engine.id}:${request.requestId}"
        accepted += token
        val entry = ActiveRun(request, "delegation-${request.requestId}")
        active[parent] = entry
        entry.job = repo.scope.launch { run(parent, entry) }
    }

    /** 取消该父会话正在进行的委派，同时中断子会话并通知发起引擎。 */
    suspend fun cancel(parent: ConversationKey) {
        val entry = active.remove(parent) ?: return
        entry.job?.cancel()
        val child = entry.child
        if (child != null) try { repo.adapter(child.engine).interrupt(child) } catch (_: Exception) { }
        repo.mutateItem<TimelineItem>(parent, entry.itemId, { null }) {
            if (it is TimelineItem.Delegation) it.copy(result = "已取消委派", status = ItemStatus.DECLINED) else it
        }
        finish(entry, "用户取消了委派", false)
    }

    fun reset() {
        for ((parent, entry) in active.entries.toList()) {
            entry.job?.cancel()
            repo.mutateItem<TimelineItem>(parent, entry.itemId, { null }) {
                if (it is TimelineItem.Delegation && it.status == ItemStatus.RUNNING) {
                    it.copy(result = "运行环境已重置，委派中断", status = ItemStatus.FAILED)
                } else it
            }
        }
        active.clear()
        accepted.clear()
    }

    private suspend fun run(parent: ConversationKey, entry: ActiveRun) {
        val request = entry.request
        repo.upsertItem(parent, TimelineItem.Delegation(entry.itemId, request.target, request.task, null, null, ItemStatus.RUNNING))
        try {
            val cwd = request.cwd ?: repo.settings.state.value.defaultCwd
            // 用户在确认弹层中已看到工作目录；委派给 Claude 需要信任该项目。
            if (request.target == EngineKind.CLAUDE) repo.settings.trust(cwd)
            val child = repo.create(request.target, cwd, CreateOptions(parent = parent, allowDelegation = false))
            entry.child = child
            repo.update(child) { it.copy(summary = it.summary.copy(title = "委派：" + request.task.lineSequence().first().take(40), parent = parent)) }
            repo.upsertItem(parent, TimelineItem.Delegation(entry.itemId, request.target, request.task, child, null, ItemStatus.RUNNING))
            val result = withTimeout(2 * 60 * 60 * 1000L) {
                when (request.target.id) {
                    "claude" -> repo.claude.runTask(child, request.task)
                    "codex" -> repo.codex.runTask(child, request.task)
                    else -> {
                        repo.adapter(request.target).send(child, request.task)
                        repo.state(child)?.items?.lastOrNull { it is TimelineItem.AgentMessage }?.let { (it as TimelineItem.AgentMessage).text } ?: ""
                    }
                }
            }
            val bounded = if (result.length > 60_000) result.take(60_000) + "\n…（结果过长，已截断；完整内容见子会话）" else result
            repo.upsertItem(parent, TimelineItem.Delegation(entry.itemId, request.target, request.task, child, bounded, ItemStatus.COMPLETED))
            finish(entry, bounded.ifBlank { "${request.target.label} 已完成，但没有返回文字结果" }, true)
        } catch (_: TimeoutCancellationException) {
            entry.child?.let { child ->
                try { repo.adapter(child.engine).interrupt(child) }
                catch (error: Exception) { repo.notify("委派已超时，子任务中断失败：${error.message}") }
            }
            repo.upsertItem(parent, TimelineItem.Delegation(entry.itemId, request.target, request.task, entry.child, "委派超时", ItemStatus.FAILED))
            finish(entry, "委派超时，未确认任务完成", false)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "委派失败"
            repo.mutateItem<TimelineItem>(parent, entry.itemId, { null }) {
                if (it is TimelineItem.Delegation) it.copy(result = message, status = ItemStatus.FAILED) else it
            }
            finish(entry, "委派失败：$message", false)
        } finally {
            active.remove(parent, entry)
            accepted -= "${request.engine.id}:${request.requestId}"
        }
    }

    private fun finish(entry: ActiveRun, text: String, success: Boolean) {
        if (entry.responded) return
        entry.responded = true
        respond(entry.request.engine, entry.request.requestId, text, success)
    }

    private fun respond(from: EngineKind, requestId: Any, text: String, success: Boolean) {
        try {
            when (from.id) {
                "codex" -> repo.codex.respondToolCall(requestId, text, success)
                "claude" -> repo.claude.respondDelegation(requestId, text, success)
                else -> repo.runtime.hubRespond(requestId, jsonOf("ok" to success, "text" to text))
            }
        } catch (error: Exception) { repo.notify("无法回传委派结果：${error.message}") }
    }
}
