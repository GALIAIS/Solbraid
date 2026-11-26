package com.galiais.agent.engine

import org.json.JSONObject

data class ConversationKey(val engine: EngineKind, val id: String) {
    val route: String
        get() = "${engine.id}/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}"

    companion object {
        fun decodeRouteId(raw: String): String =
            runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
}

data class ConversationSummary(
    val key: ConversationKey,
    val title: String,
    val preview: String,
    val cwd: String?,
    val updatedAt: Long,
    val running: Boolean = false,
    val parent: ConversationKey? = null,
    val archived: Boolean = false,
)

enum class ItemStatus { RUNNING, COMPLETED, FAILED, DECLINED }

data class FileDiff(val path: String, val kind: String, val diff: String)

data class ImageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val base64: String,
    val bytes: Int,
)

data class UnsentMessage(val id: String, val text: String, val attachments: List<ImageAttachment>)

data class TokenUsage(
    val totalTokens: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val contextWindow: Long? = null,
    val sessionTotalTokens: Long? = null,
) {
    val uncachedInputTokens: Long get() = (inputTokens - cachedInputTokens - cacheWriteTokens).coerceAtLeast(0L)

    fun cacheHitPercent(): Double? {
        if (inputTokens <= 0L) return null
        return ((cachedInputTokens.toDouble() / inputTokens.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    fun ratio(): Float? {
        val win = contextWindow?.takeIf { it > 0 } ?: return null
        return (totalTokens.toFloat() / win.toFloat()).coerceIn(0f, 1f)
    }

    fun compactLabel(): String {
        fun fmt(n: Long): String = when {
            n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fk".format(n / 1_000.0)
            else -> n.toString()
        }
        val win = contextWindow?.takeIf { it > 0 }
        return if (win != null) {
            val pct = kotlin.math.round((totalTokens.toDouble() / win) * 100.0).toInt().coerceIn(0, 100)
            "${fmt(totalTokens)} / ${fmt(win)} · $pct%"
        } else {
            "${fmt(totalTokens)} tok"
        }
    }
}

/** 根据模型 ID 推断默认上下文窗口大小（仅在 CLI 未显式返回权威窗口大小或自定义服务商场景下作为兜底）。 */
fun inferModelContextWindow(model: String?): Long? {
    val m = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        m.contains("[1m]") || m.contains("1m-context") -> 1_000_000L
        m.startsWith("gemini") || m.contains("gemini-") -> 1_048_576L
        m.startsWith("gpt-4.1") -> 1_047_576L
        m.startsWith("gpt-6") || m.startsWith("gpt-5") || m.contains("codex") -> 272_000L
        m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") -> 200_000L
        m.startsWith("claude") || m.contains("claude-") || m in setOf("sonnet", "opus", "haiku") -> 200_000L
        m.contains("qwen3") || m.contains("qwen-3") -> 262_144L
        m.contains("kimi") || m.contains("moonshot") -> 256_000L
        m.contains("grok-4") || m.contains("grok-3") -> 256_000L
        m.contains("doubao") -> 256_000L
        m.contains("glm-5") || m.contains("minimax") -> 200_000L
        m.contains("deepseek") || m.contains("glm-4") || m.contains("gpt-4") || m.contains("gpt-oss") || m.contains("qwen") -> 128_000L
        else -> null
    }
}

data class PlanStep(
    val step: String,
    val status: String,
    val id: String? = null,
    val activeForm: String? = null,
    val priority: String? = null,
)

/** 步骤清单统计：将 completed 与 skipped 统一计为终态 settled，防止含跳过步骤时进度卡死。 */
data class PlanStepStats(
    val total: Int,
    val completed: Int,
    val inProgress: Int,
    val skipped: Int,
    val failed: Int,
    val pending: Int,
) {
    val settled: Int get() = completed + skipped
    val allSettled: Boolean get() = total > 0 && settled == total && failed == 0 && inProgress == 0
    val progress: Float get() = if (total > 0) (settled.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

fun List<PlanStep>.computeStats(): PlanStepStats {
    var completed = 0
    var inProgress = 0
    var skipped = 0
    var failed = 0
    var pending = 0
    for (item in this) {
        when (normalizePlanStepStatus(item.status)) {
            "completed" -> completed++
            "in_progress" -> inProgress++
            "skipped" -> skipped++
            "failed" -> failed++
            else -> pending++
        }
    }
    return PlanStepStats(size, completed, inProgress, skipped, failed, pending)
}

/** 解析选项中的 `(Recommended)` / `（推荐）` 标记：返回展示用干净文案与是否推荐。 */
private val RECOMMENDED_TAG_RE = Regex("""\s*(?:\(Recommended\)|\(推荐\)|（推荐）)\s*""", RegexOption.IGNORE_CASE)
fun parseRecommendedLabel(raw: String): Pair<String, Boolean> {
    val isRec = RECOMMENDED_TAG_RE.containsMatchIn(raw) || raw.contains("推荐")
    val clean = raw.replace(RECOMMENDED_TAG_RE, " ").trim().ifBlank { raw.trim() }
    return clean to isRec
}

/** 统一归一化三引擎（Codex / Claude / Antigravity ACP）的步骤状态值。 */
fun normalizePlanStepStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "done", "completed", "complete", "finished", "success" -> "completed"
    "in_progress", "inprogress", "running", "active", "working", "started" -> "in_progress"
    "failed", "error", "blocked" -> "failed"
    "skipped", "cancelled", "canceled", "deleted" -> "skipped"
    else -> "pending"
}

/** 从计划 Markdown 或文件名提取简洁标题。 */
fun extractPlanTitle(markdown: String, fallbackPath: String? = null): String {
    val heading = markdown.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("#") && it.trimStart('#').isNotBlank() }
        ?.trimStart('#')?.trim()?.take(48)
    if (!heading.isNullOrBlank()) return heading
    val baseName = fallbackPath?.substringAfterLast('/')?.removeSuffix(".md")?.trim()
    return when (baseName?.lowercase()) {
        "implementation_plan" -> "实施计划"
        "task" -> "任务清单"
        "walkthrough" -> "实施总结"
        null, "" -> "执行计划"
        else -> baseName.take(40)
    }
}

data class SlashCommand(val name: String, val description: String, val localAction: String? = null)

data class McpServerSummary(
    val name: String,
    val status: String,
    val toolCount: Int,
    val origin: String? = null,
    val scope: String? = null,
    val error: String? = null,
    val enabled: Boolean = status != "disabled",
)

data class SkillSummary(
    val name: String,
    val description: String,
    val path: String? = null,
    val enabled: Boolean = true,
)

data class ClaudeAgentSummary(val name: String, val description: String, val model: String? = null)

data class ExperimentalFeatureItem(
    val name: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val stage: String = "",
)

data class RateLimitSummary(
    val label: String,
    val usedPercent: Int,
    val resetsAt: String? = null,
)

data class FuzzyMatch(val path: String, val fileName: String)

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Claude Code 文件检查点回滚结果（`session/rewindFiles`）。 */
data class RewindPreviewResult(
    val canRewind: Boolean,
    val error: String? = null,
    val filesChanged: List<String> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0,
)

/** ACP 动态配置项（如 Antigravity 思考预算 / 推理强度 / 模式等）。 */
data class AcpConfigChoice(val value: String, val name: String)
data class AcpConfigOption(
    val id: String,
    val name: String,
    val category: String = "",
    val currentValue: String? = null,
    val options: List<AcpConfigChoice> = emptyList(),
)

/** Antigravity 原生插件与 Brain 产物记录。 */
data class AgyPluginSummary(
    val name: String,
    val enabled: Boolean = true,
    val description: String = "",
    val version: String = "",
)
data class AgyArtifactEntry(
    val id: String,
    val name: String,
    val path: String,
    val size: Long = 0L,
    val updatedAt: Long = 0L,
    val sessionDir: String = "",
)

/** 离线 / Linux 原生 LaTeX 编译状态与结果。 */
data class LatexEngineStatus(
    val offlineEngine: String = "solbraid-latex-v1",
    val nativeAvailable: Boolean = false,
    val nativeEngine: String? = null,
    val nativePath: String? = null,
)
data class LatexCompileResult(
    val ok: Boolean,
    val nativeAvailable: Boolean = false,
    val engine: String = "solbraid-latex-v1",
    val pdfPath: String? = null,
    val pdfSize: Long = 0L,
    val log: String = "",
)

/** 统一的会话时间线条目；Codex / Claude / 外部 Agent 的原始事件都会映射到这里。 */
@androidx.compose.runtime.Immutable
sealed interface TimelineItem {
    val id: String

    @androidx.compose.runtime.Immutable
    data class UserMessage(
        override val id: String,
        val text: String,
        val imageCount: Int = 0,
        val turnId: String? = null,
        val durationMs: Long? = null,
    ) : TimelineItem

    /** [phase]: Codex v2 `commentary`（过程旁白）/ `partial_answer` / `final_answer`。 */
    @androidx.compose.runtime.Immutable
    data class AgentMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val phase: String? = null,
    ) : TimelineItem

    @androidx.compose.runtime.Immutable
    data class Reasoning(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val summaryParts: Map<Int, String>? = null,
        val startedAtMs: Long? = null,
        val completedAtMs: Long? = null,
    ) : TimelineItem {
        val summaryText: String by lazy(LazyThreadSafetyMode.NONE) {
            summaryParts?.toSortedMap()?.values?.joinToString("\n\n") ?: text
        }
        val durationMs: Long? get() = startedAtMs?.let { start -> completedAtMs?.let { end -> (end - start).coerceAtLeast(0L) } }
    }
    @androidx.compose.runtime.Immutable
    data class Plan(
        override val id: String,
        val text: String,
        val title: String = "执行计划",
        val streaming: Boolean = false,
        val filePath: String? = null,
        val status: ItemStatus = ItemStatus.COMPLETED,
        val requestFeedback: Boolean = false,
        val summary: String? = null,
        val artifactType: String = "plan",
    ) : TimelineItem
    @androidx.compose.runtime.Immutable
    data class CommandRun(
        override val id: String,
        val command: String,
        val cwd: String?,
        val output: String,
        val exitCode: Int?,
        val status: ItemStatus,
        val actionSummary: String? = null,
        val durationMs: Long? = null,
    ) : TimelineItem
    @androidx.compose.runtime.Immutable
    data class FileEdit(override val id: String, val changes: List<FileDiff>, val status: ItemStatus) : TimelineItem
    @androidx.compose.runtime.Immutable
    data class ToolCall(
        override val id: String,
        val name: String,
        val input: String,
        val result: String?,
        val status: ItemStatus,
        val kind: String? = null,
    ) : TimelineItem
    @androidx.compose.runtime.Immutable
    data class Delegation(override val id: String, val target: EngineKind, val task: String, val child: ConversationKey?,
        val result: String?, val status: ItemStatus) : TimelineItem
    /** 用户提交结构化提问、Elicitation 表单或方案审批后的时间线回执卡。 */
    @androidx.compose.runtime.Immutable
    data class InteractionReceipt(
        override val id: String,
        val kind: String,
        val title: String,
        val entries: List<Pair<String, String>> = emptyList(),
        val status: ItemStatus = ItemStatus.COMPLETED,
    ) : TimelineItem
    @androidx.compose.runtime.Immutable
    data class Notice(override val id: String, val text: String, val error: Boolean = false) : TimelineItem
}

data class Question(
    val id: String,
    val header: String,
    val text: String,
    val options: List<String>,
    val allowOther: Boolean,
    val secret: Boolean,
    val optionDescriptions: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val optionPreviews: List<String> = emptyList(),
)

/** MCP / Claude / Codex / ACP 原生 Elicitation JSON Schema 表单字段。 */
data class ElicitationField(
    val name: String,
    val title: String,
    val description: String = "",
    val type: String = "string",
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val enumNames: List<String> = emptyList(),
    val enumDescriptions: List<String> = emptyList(),
    val defaultValue: String? = null,
    val defaultMultiValues: List<String> = emptyList(),
    val format: String? = null,
)

private fun resolveSchemaPropType(prop: JSONObject): Pair<String, JSONObject> {
    val rawTypeAny = prop.opt("type")
    if (rawTypeAny is org.json.JSONArray) {
        for (i in 0 until rawTypeAny.length()) {
            val t = rawTypeAny.optString(i).lowercase()
            if (t.isNotBlank() && t != "null") return t to prop
        }
    } else if (rawTypeAny is String && rawTypeAny.isNotBlank()) {
        return rawTypeAny.lowercase() to prop
    }
    // 支持 JSON Schema draft 2020-12 可空包装：anyOf: [{type: "array", items: ...}, {type: "null"}]
    val union = prop.optJSONArray("anyOf") ?: prop.optJSONArray("oneOf")
    if (union != null) {
        val nonNullObjs = (0 until union.length()).mapNotNull { union.optJSONObject(it) }
            .filterNot { it.optString("type").equals("null", ignoreCase = true) }
        if (nonNullObjs.size == 1 && !nonNullObjs[0].has("const")) {
            val inner = nonNullObjs[0]
            val innerType = inner.optString("type", "string").lowercase()
            return innerType to inner
        }
    }
    return "string" to prop
}

/** 从 MCP / Codex / Claude / ACP elicitation 请求解析 `requestedSchema` 表单字段。 */
fun parseElicitationSchema(raw: JSONObject): List<ElicitationField> {
    val schema = raw.optJSONObject("requestedSchema")
        ?: raw.optJSONObject("requested_schema")
        ?: raw.optJSONObject("schema")
        ?: return emptyList()
    val props = schema.optJSONObject("properties") ?: return emptyList()
    val requiredSet = buildSet {
        val arr = schema.optJSONArray("required")
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
    val fields = mutableListOf<ElicitationField>()
    val keys = props.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val prop = props.optJSONObject(key) ?: continue
        val (rawType, resolvedProp) = resolveSchemaPropType(prop)
        val itemsObj = if (rawType == "array") resolvedProp.optJSONObject("items") else null
        val enumSource = itemsObj ?: resolvedProp
        val enumVals = mutableListOf<String>()
        val enumNames = mutableListOf<String>()
        val enumDescs = mutableListOf<String>()

        val directEnum = enumSource.optJSONArray("enum")
        if (directEnum != null) {
            val namesArr = enumSource.optJSONArray("enumNames")
            for (i in 0 until directEnum.length()) {
                val v = directEnum.opt(i)?.toString().orEmpty()
                if (v.isEmpty()) continue
                enumVals += v
                enumNames += namesArr?.optString(i)?.takeIf { it.isNotBlank() } ?: v
                enumDescs += ""
            }
        }
        val union = enumSource.optJSONArray("oneOf") ?: enumSource.optJSONArray("anyOf")
        if (enumVals.isEmpty() && union != null) {
            for (i in 0 until union.length()) {
                val o = union.optJSONObject(i) ?: continue
                if (o.optString("type").equals("null", ignoreCase = true)) continue
                val v = o.opt("const")?.takeUnless { it == JSONObject.NULL }?.toString()
                    ?: o.optJSONArray("enum")?.opt(0)?.toString()
                    ?: continue
                if (v.isEmpty()) continue
                enumVals += v
                enumNames += o.optString("title").ifBlank { v }
                enumDescs += o.optString("description")
            }
        }
        val effectiveType = when {
            rawType == "array" && enumVals.isNotEmpty() -> "multi_enum"
            rawType == "array" -> "string_array"
            enumVals.isNotEmpty() -> "enum"
            else -> rawType
        }
        val defRaw = prop.opt("default")?.takeUnless { it == JSONObject.NULL }
        val defVal = if (defRaw is org.json.JSONArray) null else defRaw?.toString()
        val defMulti = if (defRaw is org.json.JSONArray) {
            (0 until defRaw.length()).mapNotNull { defRaw.opt(it)?.toString()?.takeIf(String::isNotBlank) }
        } else emptyList()
        fields += ElicitationField(
            name = key,
            title = prop.optString("title").ifBlank { key },
            description = prop.optString("description"),
            type = effectiveType,
            required = key in requiredSet,
            enumValues = enumVals,
            enumNames = enumNames,
            enumDescriptions = enumDescs,
            defaultValue = defVal,
            defaultMultiValues = defMulti,
            format = (prop.optString("format").ifBlank { resolvedProp.optString("format") }).takeIf { it.isNotBlank() },
        )
    }
    return fields
}

data class PermissionOption(val id: String, val name: String, val kind: String)

/** 等待用户处理的引擎请求。决定通过 [ConversationRepository.resolve] 回传给发起的引擎。 */
sealed interface PendingRequest {
    val engine: EngineKind
    val requestId: Any
    val conversation: ConversationKey?
    val title: String

    data class CommandApproval(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val command: String,
        val cwd: String?,
        val reason: String?,
        val actionSummary: String? = null,
        val proposedExecPolicy: List<String> = emptyList(),
        val networkHost: String? = null,
        val networkProtocol: String? = null,
        val allowNetworkAmendment: Boolean = false,
    ) : PendingRequest {
        override val title: String get() = actionSummary?.takeIf { it.isNotBlank() } ?: "执行命令"
    }

    data class FileApproval(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val reason: String?,
        val changes: List<FileDiff>,
        val grantRoot: String? = null,
    ) : PendingRequest {
        override val title: String get() = if (changes.size > 1) "修改 ${changes.size} 个文件" else "修改文件"
    }

    data class ToolApproval(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val toolName: String,
        val input: JSONObject,
        val reason: String?,
        val changes: List<FileDiff>,
        val permissionOptions: List<PermissionOption> = emptyList(),
        val displayTitle: String? = null,
        val displayName: String? = null,
        val description: String? = null,
        val blockedPath: String? = null,
        val claudeSuggestionsJson: String? = null,
        val defaultToNo: Boolean = false,
        val suppressAlwaysAllowRule: Boolean = false,
        val isExitPlanMode: Boolean = false,
        val planMarkdown: String? = null,
        val planFilePath: String? = null,
        val allowedPrompts: List<String> = emptyList(),
    ) : PendingRequest {
        override val title: String
            get() = when {
                isExitPlanMode -> "方案已就绪，确认退出计划模式"
                !displayTitle.isNullOrBlank() -> displayTitle
                !displayName.isNullOrBlank() -> displayName
                else -> "使用工具 $toolName"
            }
    }

    data class PermissionGrant(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val reason: String?,
        val permissions: JSONObject,
    ) : PendingRequest { override val title = "授予额外权限" }

    data class UserInput(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val questions: List<Question>,
        val isClaudeAskUserQuestion: Boolean = false,
        val rawClaudeInput: JSONObject? = null,
        val protocol: String = "",
    ) : PendingRequest { override val title = "需要你的选择" }

    data class Elicitation(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val message: String,
        val raw: JSONObject,
        val schemaFields: List<ElicitationField> = emptyList(),
        val url: String? = null,
        val serverName: String? = null,
        val mode: String = "form",
    ) : PendingRequest {
        override val title: String get() = serverName?.takeIf { it.isNotBlank() }?.let { "$it 请求输入" } ?: "需要补充信息"
    }

    data class Delegation(
        override val engine: EngineKind,
        override val requestId: Any,
        override val conversation: ConversationKey?,
        val target: EngineKind,
        val task: String,
        val cwd: String?,
    ) : PendingRequest { override val title = "委派给 ${target.label}" }
}

/** Codex v2 与 Claude SDK 的审批决策映射。 */
sealed interface Decision {
    /** 仅允许本次调用（Codex: accept / Claude: allow user_temporary）。 */
    data object Allow : Decision
    /** 本会话同类操作不再询问（Codex: acceptForSession / scope=session）。 */
    data object AllowForSession : Decision
    /** 允许并应用引擎建议的规则（Codex: execpolicy/network / Claude: updatedPermissions）。 */
    data class AllowWithRule(
        val execPolicy: List<String>? = null,
        val networkHost: String? = null,
        val networkAction: String = "allow",
        val claudePermissionsJson: String? = null,
    ) : Decision
    /** 拒绝操作：[interrupt]=false 拒绝并让模型继续尝试；[interrupt]=true 拒绝并立即中断本轮。 */
    data class Deny(val reason: String = "用户拒绝", val interrupt: Boolean = false) : Decision
    data class Answers(val values: Map<String, List<String>>) : Decision
    /** 结构化 Elicitation Schema 表单提交（支持字符串、数值、布尔与枚举类型）。 */
    data class FormSubmit(val content: JSONObject) : Decision
}

data class ConversationState(
    val summary: ConversationSummary,
    val items: List<TimelineItem> = emptyList(),
    val running: Boolean = false,
    val activeTurnId: String? = null,
    val loaded: Boolean = false,
    /** null 表示使用官方内置服务商；非 null 指向 [CustomProvider.id]。 */
    val providerId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    /** 会话级 Codex 审批策略覆盖（untrusted / on-request / never）；null 表示跟随全局设置。 */
    val approvalPolicy: String? = null,
    /** 会话级 Codex 审校者（user / auto_review）；null 表示跟随全局设置。 */
    val approvalsReviewer: String? = null,
    /** 会话级推理摘要详细度（auto / concise / detailed / none）；null 表示跟随全局设置。 */
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    val collaborationMode: String? = null,
    val fastMode: Boolean? = null,
    val webSearch: String? = null,
    /** Claude Code 权限模式（default / acceptEdits / plan / auto / bypassPermissions）。 */
    val permissionMode: String = "default",
    val turnStartedAt: Long? = null,
    /** 按用户消息 id 或 turnId 记录每轮真实耗时（毫秒），驱动 `用时 X分Y秒 >`。 */
    val turnDurations: Map<String, Long> = emptyMap(),
    val turnDiff: String? = null,
    val tokenUsage: TokenUsage? = null,
    val plan: List<PlanStep> = emptyList(),
    val planExplanation: String? = null,
    val focusText: String? = null,
    val arenaGroupId: String? = null,
    val unsentMessage: UnsentMessage? = null,
    val reverting: Boolean = false,
    val revertedMessage: TimelineItem.UserMessage? = null,
)

data class ModelOption(
    val id: String,
    val label: String,
    val description: String = "",
    val isDefault: Boolean = false,
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
    val serviceTiers: List<ServiceTierOption> = emptyList(),
    val defaultServiceTier: String? = null,
    val resolvedModel: String? = null,
    val supportsFastMode: Boolean = false,
    val supportsAutoMode: Boolean = false,
)

data class ServiceTierOption(val id: String, val name: String, val description: String = "")

fun List<ModelOption>.selectedModel(id: String?): ModelOption? =
    if (id == null) firstOrNull { it.isDefault } else firstOrNull { it.id == id || it.resolvedModel == id }

fun ModelOption.reasoningEfforts(): List<String> = efforts.filterNot {
    it == "none" && listOfNotNull(id, resolvedModel).any { name ->
        name.contains("gpt-6", ignoreCase = true) && name.contains("astra", ignoreCase = true)
    }
}

internal data class ConversationOptions(
    val model: String?, val effort: String?, val approvalPolicy: String?, val approvalsReviewer: String?,
    val reasoningSummary: String?, val permissionMode: String, val serviceTier: String?,
    val collaborationMode: String?, val fastMode: Boolean?, val webSearch: String?,
) {
    fun applyTo(state: ConversationState) = state.copy(model = model, effort = effort, approvalPolicy = approvalPolicy,
        approvalsReviewer = approvalsReviewer, reasoningSummary = reasoningSummary, permissionMode = permissionMode,
        serviceTier = serviceTier, collaborationMode = collaborationMode, fastMode = fastMode, webSearch = webSearch)

    fun toJson() = jsonOf("version" to 1, "model" to model, "effort" to effort, "approvalPolicy" to approvalPolicy,
        "approvalsReviewer" to approvalsReviewer, "reasoningSummary" to reasoningSummary, "permissionMode" to permissionMode,
        "serviceTier" to serviceTier, "collaborationMode" to collaborationMode, "fastMode" to fastMode,
        "webSearch" to webSearch)

    companion object {
        fun from(state: ConversationState) = ConversationOptions(state.model, state.effort, state.approvalPolicy,
            state.approvalsReviewer, state.reasoningSummary, state.permissionMode, state.serviceTier,
            state.collaborationMode, state.fastMode, state.webSearch)

        fun fromJson(data: JSONObject): ConversationOptions {
            require(data.optInt("version") == 1) { "会话设置版本不受支持" }
            fun text(name: String, limit: Int = 256): String? {
                val value = data.opt(name)
                if (value == null || value == JSONObject.NULL) return null
                require(value is String && value.length <= limit) { "会话设置格式无效：$name" }
                return value
            }
            val policy = text("approvalPolicy")?.also { require(it in CODEX_APPROVAL_POLICIES) }
            val reviewer = text("approvalsReviewer")?.also { require(it in CODEX_APPROVAL_REVIEWERS) }
            val summary = text("reasoningSummary")?.also { require(it in REASONING_SUMMARIES) }
            val mode = text("collaborationMode")?.also { require(it in CODEX_COLLABORATION_MODES) }
            val search = text("webSearch")?.also { require(it in CODEX_WEB_SEARCH_MODES) }
            val fast = data.opt("fastMode").takeUnless { it == JSONObject.NULL }
            require(fast == null || fast is Boolean) { "会话快速模式设置无效" }
            val permission = text("permissionMode", 64) ?: "default"
            return ConversationOptions(text("model"), text("effort", 40), policy, reviewer, summary,
                permission, text("serviceTier"), mode, fast,
                search)
        }
    }
}

/** 秒或毫秒时间戳统一为毫秒。 */
fun normalizeTime(value: Long): Long = if (value in 1 until 100_000_000_000L) value * 1000 else value
