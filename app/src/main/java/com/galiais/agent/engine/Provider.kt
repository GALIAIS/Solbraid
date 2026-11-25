package com.galiais.agent.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** 服务商上游协议。本地网关在需要时自动完成 Codex(Responses) / Claude(Anthropic) / Antigravity(Gemini) 与以下 4 种协议的双向转换。 */
enum class ProviderProtocol(
    val id: String,
    val label: String,
    val shortLabel: String,
    val defaultUrl: String,
    val defaultAuth: ProviderAuthMode,
    val hint: String,
) {
    OPENAI_RESPONSES(
        "openai_responses", "OpenAI Responses API", "Responses",
        "https://api.openai.com/v1", ProviderAuthMode.BEARER,
        "POST /responses（Codex 原生直通，Claude 自动转换）",
    ),
    OPENAI_CHAT(
        "openai_chat", "OpenAI Chat Completions", "Chat 兼容",
        "https://api.openai.com/v1", ProviderAuthMode.BEARER,
        "POST /chat/completions（兼容 DeepSeek / Kimi / Qwen / OpenRouter / Ollama 等）",
    ),
    ANTHROPIC_MESSAGES(
        "anthropic_messages", "Anthropic Messages API", "Anthropic",
        "https://api.anthropic.com", ProviderAuthMode.X_API_KEY,
        "POST /v1/messages（Claude Code 原生直通，Codex 自动转换）",
    ),
    GOOGLE_GEMINI(
        "google_gemini", "Google Gemini API", "Gemini",
        "https://generativelanguage.googleapis.com/v1beta", ProviderAuthMode.X_GOOG_API_KEY,
        "POST /models/{model}:streamGenerateContent（Antigravity 直通，Codex/Claude 自动转换）",
    );

    fun resolvedEndpointPreview(rawBaseUrl: String): String {
        val base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return when (this) {
            OPENAI_RESPONSES -> "POST $base/responses"
            OPENAI_CHAT -> "POST $base/chat/completions"
            ANTHROPIC_MESSAGES -> if (base.endsWith("/v1")) "POST $base/messages" else "POST $base/v1/messages"
            GOOGLE_GEMINI -> "POST $base/models/{model}:streamGenerateContent"
        }
    }

    companion object {
        fun of(id: String): ProviderProtocol = entries.firstOrNull { it.id == id } ?: OPENAI_CHAT
    }
}

enum class ProviderAuthMode(val id: String, val label: String) {
    BEARER("bearer", "Authorization: Bearer"),
    X_API_KEY("x-api-key", "x-api-key"),
    X_GOOG_API_KEY("x-goog-api-key", "x-goog-api-key"),
    API_KEY_HEADER("api-key", "api-key（Azure 等）"),
    NONE("none", "无需认证（本地 / 免费节点）");

    companion object {
        fun of(id: String): ProviderAuthMode = entries.firstOrNull { it.id == id } ?: BEARER
    }
}

/** 融合 Magpie 多协议端点、密钥池故障转移、计费与费率倍数的统一服务商配置。 */
data class CustomProvider(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val authMode: ProviderAuthMode,
    val models: List<String>,
    val modelMap: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val hasApiKey: Boolean = false,
    val presetId: String? = null,
    val regionId: String? = null,
    val chatUrl: String = "",
    val responsesUrl: String = "",
    val anthropicUrl: String = "",
    val geminiUrl: String = "",
    val extraBodyJson: String = "",
    val billingKind: String = "", // "" | "plan" | "payg" | "free"
    val priceRate: Double = 1.0,
    val keyPoolCount: Int = 0,
    val anthropicAuthFallback: Boolean = true,
    val enabled: Boolean = true,
) {
    /** 注册到 Codex config.toml [model_providers.<key>] 时的唯一标识。 */
    val codexProviderId: String get() = CODEX_PREFIX + id
    /** 是否为多服务商路由组映射出的虚拟服务商。 */
    val isRoutingGroup: Boolean get() = id.startsWith(GROUP_PREFIX)

    fun supportedProtocolsSummary(): String {
        if (isRoutingGroup) return "路由组"
        val list = buildList {
            if (responsesUrl.isNotBlank() || protocol == ProviderProtocol.OPENAI_RESPONSES) add("Responses")
            if (chatUrl.isNotBlank() || protocol == ProviderProtocol.OPENAI_CHAT) add("Chat")
            if (anthropicUrl.isNotBlank() || protocol == ProviderProtocol.ANTHROPIC_MESSAGES) add("Anthropic")
            if (geminiUrl.isNotBlank() || protocol == ProviderProtocol.GOOGLE_GEMINI) add("Gemini")
        }.distinct()
        return if (list.isEmpty()) protocol.shortLabel else list.joinToString(" · ")
    }

    fun displayTypeBadge(): String = if (isRoutingGroup) "路由组" else protocol.shortLabel

    fun billingLabel(): String = when (billingKind) {
        "plan" -> "订阅套餐"
        "payg" -> "按量计费"
        "free" -> "免费"
        else -> ""
    }

    fun toMetadataJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol.id)
        .put("baseUrl", baseUrl)
        .put("authMode", authMode.id)
        .put("models", JSONArray(models))
        .put("modelMap", JSONObject(modelMap))
        .put("headers", JSONObject(headers))
        .put("queryParams", JSONObject(queryParams))
        .put("presetId", presetId ?: JSONObject.NULL)
        .put("regionId", regionId ?: JSONObject.NULL)
        .put("chatUrl", chatUrl)
        .put("responsesUrl", responsesUrl)
        .put("anthropicUrl", anthropicUrl)
        .put("geminiUrl", geminiUrl)
        .put("extraBodyJson", extraBodyJson)
        .put("billingKind", billingKind)
        .put("priceRate", priceRate)
        .put("keyPoolCount", keyPoolCount)
        .put("anthropicAuthFallback", anthropicAuthFallback)
        .put("enabled", enabled)

    fun toGatewayJson(apiKey: String?, apiKeys: List<String> = emptyList()): JSONObject = toMetadataJson().apply {
        val mergedKeys = buildList {
            apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (it !in this) add(it) }
            apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it !in this) add(0, it) }
        }
        if (mergedKeys.isNotEmpty()) {
            put("apiKey", mergedKeys.first())
            put("apiKeys", JSONArray(mergedKeys))
        }
    }

    companion object {
        const val CODEX_PREFIX = "galiais_"
        const val GROUP_PREFIX = "group_"

        fun fromCodexProviderId(codexId: String?): String? =
            codexId?.takeIf { it.startsWith(CODEX_PREFIX) }?.removePrefix(CODEX_PREFIX)?.takeIf { it.isNotEmpty() }

        fun fromJson(obj: JSONObject, hasApiKey: Boolean, keyPoolCount: Int = 0): CustomProvider {
            val modelMap = linkedMapOf<String, String>()
            obj.optJSONObject("modelMap")?.let { mm -> mm.keys().forEach { k -> val v = mm.optString(k).trim(); if (k.isNotBlank() && v.isNotBlank()) modelMap[k.trim()] = v } }
            val headers = linkedMapOf<String, String>()
            obj.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            val queryParams = linkedMapOf<String, String>()
            obj.optJSONObject("queryParams")?.let { q -> q.keys().forEach { k -> queryParams[k] = q.optString(k) } }
            return CustomProvider(
                id = obj.getString("id"),
                name = obj.optString("name", obj.getString("id")),
                protocol = ProviderProtocol.of(obj.optString("protocol")),
                baseUrl = obj.optString("baseUrl"),
                authMode = ProviderAuthMode.of(obj.optString("authMode")),
                models = obj.arr("models").strings().filter { it.isNotBlank() }.distinct(),
                modelMap = modelMap,
                headers = headers,
                queryParams = queryParams,
                hasApiKey = hasApiKey,
                presetId = obj.str("presetId"),
                regionId = obj.str("regionId"),
                chatUrl = obj.optString("chatUrl", ""),
                responsesUrl = obj.optString("responsesUrl", ""),
                anthropicUrl = obj.optString("anthropicUrl", ""),
                geminiUrl = obj.optString("geminiUrl", ""),
                extraBodyJson = obj.optString("extraBodyJson", ""),
                billingKind = obj.optString("billingKind", ""),
                priceRate = obj.optDouble("priceRate", 1.0).takeIf { it.isFinite() && it >= 0.0 } ?: 1.0,
                keyPoolCount = keyPoolCount.coerceAtLeast(obj.optInt("keyPoolCount", if (hasApiKey) 1 else 0)),
                anthropicAuthFallback = obj.optBoolean("anthropicAuthFallback", true),
                enabled = obj.optBoolean("enabled", true),
            )
        }
    }
}

/** Magpie 风格跨服务商路由组：按故障转移 / 轮询 / 最低费率自动调度多个服务商与模型。 */
data class RoutingTarget(
    val providerId: String,
    val model: String,
    val weight: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().put("providerId", providerId).put("model", model).put("weight", weight)
    companion object {
        fun fromJson(obj: JSONObject) = RoutingTarget(
            providerId = obj.getString("providerId"),
            model = obj.optString("model"),
            weight = obj.optInt("weight", 1).coerceAtLeast(1),
        )
    }
}

data class RoutingGroup(
    val id: String,
    val name: String,
    val strategy: String = "failover", // "failover" | "round_robin" | "cheapest"
    val targets: List<RoutingTarget> = emptyList(),
) {
    /** 暴露给引擎的虚拟服务商 ID。 */
    val virtualProviderId: String get() = CustomProvider.GROUP_PREFIX + id
    val codexProviderId: String get() = CustomProvider.CODEX_PREFIX + virtualProviderId

    fun strategyLabel(): String = when (strategy) {
        "round_robin" -> "轮询均衡"
        "cheapest" -> "最低费率优先"
        else -> "主备故障转移"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("strategy", strategy)
        .put("targets", JSONArray().apply { targets.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(obj: JSONObject) = RoutingGroup(
            id = obj.getString("id"),
            name = obj.optString("name", obj.getString("id")),
            strategy = obj.optString("strategy", "failover").takeIf { it in setOf("failover", "round_robin", "cheapest") } ?: "failover",
            targets = obj.arr("targets").objects().mapNotNull { runCatching { RoutingTarget.fromJson(it) }.getOrNull() },
        )
    }
}

data class ProviderRegion(
    val id: String,
    val label: String,
    val chatUrl: String = "",
    val responsesUrl: String = "",
    val anthropicUrl: String = "",
    val geminiUrl: String = "",
    val billingKind: String = "",
    val sampleModels: List<String> = emptyList(),
)

/** Magpie 风格服务商预设：按官方/套餐/中转/本地分组，内置多协议端点与多区域/套餐切换。 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val name: String,
    val category: String = "vendor", // "vendor" | "plan" | "relay" | "local"
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val authMode: ProviderAuthMode,
    val chatUrl: String = "",
    val responsesUrl: String = "",
    val anthropicUrl: String = "",
    val geminiUrl: String = "",
    val billingKind: String = "payg",
    val note: String = "",
    val regions: List<ProviderRegion> = emptyList(),
    val sampleModels: List<String> = emptyList(),
)

val providerPresets: List<ProviderPreset> = listOf(
    // ── 官方模型商 ──
    ProviderPreset(
        id = "openai", label = "OpenAI", name = "OpenAI", category = "vendor",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://api.openai.com/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.openai.com/v1", responsesUrl = "https://api.openai.com/v1",
        note = "官方 Responses & Chat API", sampleModels = listOf("gpt-5.4", "gpt-5.3-codex", "o4-mini"),
    ),
    ProviderPreset(
        id = "anthropic", label = "Anthropic", name = "Anthropic", category = "vendor",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES, baseUrl = "https://api.anthropic.com", authMode = ProviderAuthMode.X_API_KEY,
        anthropicUrl = "https://api.anthropic.com",
        note = "官方 Claude Messages API", sampleModels = listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5"),
    ),
    ProviderPreset(
        id = "gemini", label = "Google Gemini", name = "Google Gemini", category = "vendor",
        protocol = ProviderProtocol.GOOGLE_GEMINI, baseUrl = "https://generativelanguage.googleapis.com/v1beta", authMode = ProviderAuthMode.X_GOOG_API_KEY,
        geminiUrl = "https://generativelanguage.googleapis.com/v1beta",
        chatUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        note = "Gemini 原生 + OpenAI 兼容", sampleModels = listOf("gemini-2.5-pro", "gemini-2.5-flash"),
    ),
    ProviderPreset(
        id = "deepseek", label = "DeepSeek", name = "DeepSeek", category = "vendor",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://api.deepseek.com/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.deepseek.com/v1", anthropicUrl = "https://api.deepseek.com/anthropic",
        note = "支持 Chat & Anthropic 原生端点", sampleModels = listOf("deepseek-chat", "deepseek-reasoner"),
    ),
    ProviderPreset(
        id = "xai", label = "xAI Grok", name = "xAI", category = "vendor",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://api.x.ai/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.x.ai/v1", responsesUrl = "https://api.x.ai/v1",
        note = "支持 Responses & Chat", sampleModels = listOf("grok-4", "grok-3-mini"),
    ),
    ProviderPreset(
        id = "kimi", label = "Kimi / Moonshot", name = "Kimi", category = "vendor",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://api.moonshot.cn/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.moonshot.cn/v1", anthropicUrl = "https://api.moonshot.cn/anthropic",
        note = "中国区 / 国际区 / Kimi Code 套餐",
        regions = listOf(
            ProviderRegion("cn", "中国区按量", chatUrl = "https://api.moonshot.cn/v1", anthropicUrl = "https://api.moonshot.cn/anthropic", billingKind = "payg"),
            ProviderRegion("intl", "国际区按量", chatUrl = "https://api.moonshot.ai/v1", anthropicUrl = "https://api.moonshot.ai/anthropic", billingKind = "payg"),
            ProviderRegion("coding-cn", "Kimi Code 中国套餐", chatUrl = "https://api.kimi.com/coding/v1", anthropicUrl = "https://api.kimi.com/coding", billingKind = "plan", sampleModels = listOf("kimi-for-coding")),
        ),
        sampleModels = listOf("kimi-k2.5", "kimi-k2-thinking"),
    ),
    ProviderPreset(
        id = "zhipu", label = "智谱 GLM / Z.ai", name = "智谱 GLM", category = "vendor",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://open.bigmodel.cn/api/paas/v4", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://open.bigmodel.cn/api/paas/v4", anthropicUrl = "https://open.bigmodel.cn/api/anthropic",
        note = "按量 / Coding Plan / Z.ai 国际",
        regions = listOf(
            ProviderRegion("cn-api", "国内按量", chatUrl = "https://open.bigmodel.cn/api/paas/v4", anthropicUrl = "https://open.bigmodel.cn/api/anthropic", billingKind = "payg"),
            ProviderRegion("cn-coding", "国内 Coding Plan", chatUrl = "https://open.bigmodel.cn/api/coding/paas/v4", anthropicUrl = "https://open.bigmodel.cn/api/anthropic", billingKind = "plan", sampleModels = listOf("glm-5", "glm-4.7")),
            ProviderRegion("zai-api", "Z.ai 国际按量", chatUrl = "https://api.z.ai/api/paas/v4", anthropicUrl = "https://api.z.ai/api/anthropic", billingKind = "payg"),
            ProviderRegion("zai-coding", "Z.ai 国际 Coding", chatUrl = "https://api.z.ai/api/coding/paas/v4", anthropicUrl = "https://api.z.ai/api/anthropic", billingKind = "plan"),
        ),
        sampleModels = listOf("glm-5", "glm-4.7", "glm-4.5-air"),
    ),
    ProviderPreset(
        id = "minimax", label = "MiniMax", name = "MiniMax", category = "vendor",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES, baseUrl = "https://api.minimaxi.com/anthropic", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.minimaxi.com/v1", anthropicUrl = "https://api.minimaxi.com/anthropic",
        note = "中国区 / 国际区 · 支持 Anthropic 原生",
        regions = listOf(
            ProviderRegion("cn", "中国区", chatUrl = "https://api.minimaxi.com/v1", anthropicUrl = "https://api.minimaxi.com/anthropic", billingKind = "payg"),
            ProviderRegion("intl", "国际区", chatUrl = "https://api.minimax.io/v1", anthropicUrl = "https://api.minimax.io/anthropic", billingKind = "payg"),
        ),
        sampleModels = listOf("MiniMax-M2.5", "MiniMax-M2.1"),
    ),
    // ── 国内云 & Token / Coding 订阅套餐 ──
    ProviderPreset(
        id = "volcengine", label = "火山方舟 Ark", name = "火山方舟", category = "plan",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", responsesUrl = "https://ark.cn-beijing.volces.com/api/coding/v3",
        anthropicUrl = "https://ark.cn-beijing.volces.com/api/coding", billingKind = "plan",
        note = "Coding / Agent 套餐 · 三协议直通",
        regions = listOf(
            ProviderRegion("coding", "Coding Plan", chatUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", responsesUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", anthropicUrl = "https://ark.cn-beijing.volces.com/api/coding", billingKind = "plan"),
            ProviderRegion("agent", "Agent Plan", chatUrl = "https://ark.cn-beijing.volces.com/api/plan/v3", responsesUrl = "https://ark.cn-beijing.volces.com/api/plan/v3", anthropicUrl = "https://ark.cn-beijing.volces.com/api/plan", billingKind = "plan"),
            ProviderRegion("api", "按量计费", chatUrl = "https://ark.cn-beijing.volces.com/api/v3", responsesUrl = "https://ark.cn-beijing.volces.com/api/v3", billingKind = "payg"),
        ),
        sampleModels = listOf("ark-code-latest", "doubao-seed-2.0-code", "deepseek-v3.2", "glm-5"),
    ),
    ProviderPreset(
        id = "bailian", label = "阿里云百炼 / 通义", name = "阿里云百炼", category = "plan",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", anthropicUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic",
        billingKind = "plan", note = "Token Plan 套餐 & DashScope 按量",
        regions = listOf(
            ProviderRegion("bailian-plan", "百炼 Token Plan (北京)", chatUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", anthropicUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic", billingKind = "plan"),
            ProviderRegion("qwen-plan", "通义 Token Plan", chatUrl = "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1", anthropicUrl = "https://token-plan.maas.qianwenaiapi.com/apps/anthropic", billingKind = "plan"),
            ProviderRegion("cn-api", "DashScope 中国按量", chatUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", anthropicUrl = "https://dashscope.aliyuncs.com/apps/anthropic", billingKind = "payg"),
            ProviderRegion("intl-api", "DashScope 国际按量", chatUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", anthropicUrl = "https://dashscope-intl.aliyuncs.com/apps/anthropic", billingKind = "payg"),
        ),
        sampleModels = listOf("qwen3-coder-plus", "qwen3-max", "deepseek-v3.2"),
    ),
    ProviderPreset(
        id = "tencent-cloud", label = "腾讯云 TokenHub", name = "腾讯云 TokenHub", category = "plan",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://tokenhub.tencentmaas.com/plan/v3", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://tokenhub.tencentmaas.com/plan/v3", responsesUrl = "https://tokenhub.tencentmaas.com/plan/v3",
        anthropicUrl = "https://tokenhub.tencentmaas.com/plan/anthropic", billingKind = "plan",
        note = "Token Plan 套餐 · 三协议直通",
        regions = listOf(
            ProviderRegion("plan", "Token Plan 中国", chatUrl = "https://tokenhub.tencentmaas.com/plan/v3", responsesUrl = "https://tokenhub.tencentmaas.com/plan/v3", anthropicUrl = "https://tokenhub.tencentmaas.com/plan/anthropic", billingKind = "plan"),
            ProviderRegion("cn", "按量计费 · 中国", chatUrl = "https://tokenhub.tencentmaas.com/v1", responsesUrl = "https://tokenhub.tencentmaas.com/v1", anthropicUrl = "https://tokenhub.tencentmaas.com", billingKind = "payg"),
            ProviderRegion("intl", "按量计费 · 国际", chatUrl = "https://tokenhub-intl.tencentmaas.com/v1", responsesUrl = "https://tokenhub-intl.tencentmaas.com/v1", anthropicUrl = "https://tokenhub-intl.tencentmaas.com", billingKind = "payg"),
        ),
        sampleModels = listOf("tc-code-latest", "auto", "glm-5", "kimi-k2.6"),
    ),
    ProviderPreset(
        id = "baidu-qianfan", label = "百度千帆", name = "百度千帆", category = "plan",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://qianfan.baidubce.com/v2/tokenplan/personal", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://qianfan.baidubce.com/v2/tokenplan/personal", responsesUrl = "https://qianfan.baidubce.com/v2/tokenplan/personal",
        anthropicUrl = "https://qianfan.baidubce.com/anthropic/tokenplan/personal", billingKind = "plan",
        note = "Token Plan 个人/企业 & 按量",
        regions = listOf(
            ProviderRegion("personal", "Token Plan 个人", chatUrl = "https://qianfan.baidubce.com/v2/tokenplan/personal", responsesUrl = "https://qianfan.baidubce.com/v2/tokenplan/personal", anthropicUrl = "https://qianfan.baidubce.com/anthropic/tokenplan/personal", billingKind = "plan"),
            ProviderRegion("team", "Token Plan 企业", chatUrl = "https://qianfan.baidubce.com/v2/tokenplan/team", responsesUrl = "https://qianfan.baidubce.com/v2/tokenplan/team", anthropicUrl = "https://qianfan.baidubce.com/anthropic/tokenplan/team", billingKind = "plan"),
            ProviderRegion("api", "按量计费", chatUrl = "https://qianfan.baidubce.com/v2", responsesUrl = "https://qianfan.baidubce.com/v2", anthropicUrl = "https://qianfan.baidubce.com/anthropic", billingKind = "payg"),
        ),
        sampleModels = listOf("qianfan-code-latest", "deepseek-v3.2", "glm-5"),
    ),
    ProviderPreset(
        id = "huaweicloud", label = "华为云 MaaS", name = "华为云 MaaS", category = "plan",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://api.modelarts-maas.com/plan/v2", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.modelarts-maas.com/plan/v2", anthropicUrl = "https://api.modelarts-maas.com/plan/anthropic",
        billingKind = "plan", note = "Token Plan · 西南-贵阳一",
        regions = listOf(
            ProviderRegion("plan", "Token Plan", chatUrl = "https://api.modelarts-maas.com/plan/v2", anthropicUrl = "https://api.modelarts-maas.com/plan/anthropic", billingKind = "plan"),
            ProviderRegion("api", "按量计费", chatUrl = "https://api.modelarts-maas.com/openai/v1", anthropicUrl = "https://api.modelarts-maas.com/anthropic", billingKind = "payg"),
        ),
    ),
    // ── 中转聚合 ──
    ProviderPreset(
        id = "openrouter", label = "OpenRouter", name = "OpenRouter", category = "relay",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://openrouter.ai/api/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://openrouter.ai/api/v1", anthropicUrl = "https://openrouter.ai/api",
        note = "全球全模型聚合 · Chat & Anthropic",
    ),
    ProviderPreset(
        id = "siliconflow", label = "硅基流动", name = "硅基流动", category = "relay",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://api.siliconflow.cn/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.siliconflow.cn/v1", note = "中国区 / 国际区",
        regions = listOf(
            ProviderRegion("cn", "中国区", chatUrl = "https://api.siliconflow.cn/v1", billingKind = "payg"),
            ProviderRegion("intl", "国际区", chatUrl = "https://api.siliconflow.com/v1", billingKind = "payg"),
        ),
    ),
    ProviderPreset(
        id = "aihubmix", label = "AiHubMix", name = "AiHubMix", category = "relay",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://aihubmix.com/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://aihubmix.com/v1", anthropicUrl = "https://aihubmix.com", note = "多厂商聚合中转",
    ),
    ProviderPreset(
        id = "302ai", label = "302.AI", name = "302.AI", category = "relay",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://api.302.ai/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.302.ai/v1", anthropicUrl = "https://api.302.ai", note = "按量中转站",
    ),
    ProviderPreset(
        id = "vercel", label = "Vercel AI Gateway", name = "Vercel AI Gateway", category = "relay",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://ai-gateway.vercel.sh/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://ai-gateway.vercel.sh/v1", responsesUrl = "https://ai-gateway.vercel.sh/v1", anthropicUrl = "https://ai-gateway.vercel.sh",
        note = "三协议原生直通网关",
    ),
    ProviderPreset(
        id = "opencode-zen", label = "OpenCode Zen", name = "OpenCode Zen", category = "relay",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://opencode.ai/zen/v1", authMode = ProviderAuthMode.NONE,
        chatUrl = "https://opencode.ai/zen/v1", responsesUrl = "https://opencode.ai/zen/v1", anthropicUrl = "https://opencode.ai/zen",
        billingKind = "free", note = "内置免费编程模型（可免 Key）",
    ),
    ProviderPreset(
        id = "groq", label = "Groq", name = "Groq", category = "vendor",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://api.groq.com/openai/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api.groq.com/openai/v1", responsesUrl = "https://api.groq.com/openai/v1", note = "超低延迟推理",
    ),
    ProviderPreset(
        id = "nvidia", label = "NVIDIA NIM", name = "NVIDIA NIM", category = "relay",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "https://integrate.api.nvidia.com/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://integrate.api.nvidia.com/v1", note = "NVIDIA 托管推理",
    ),
    ProviderPreset(
        id = "modelscope", label = "魔搭 ModelScope", name = "魔搭 ModelScope", category = "relay",
        protocol = ProviderProtocol.OPENAI_RESPONSES, baseUrl = "https://api-inference.modelscope.cn/v1", authMode = ProviderAuthMode.BEARER,
        chatUrl = "https://api-inference.modelscope.cn/v1", responsesUrl = "https://api-inference.modelscope.cn/v1", note = "免费额度开源模型",
    ),
    // ── 本地部署 ──
    ProviderPreset(
        id = "ollama", label = "本地 Ollama", name = "Ollama", category = "local",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "http://127.0.0.1:11434/v1", authMode = ProviderAuthMode.NONE,
        chatUrl = "http://127.0.0.1:11434/v1", anthropicUrl = "http://127.0.0.1:11434",
        billingKind = "free", note = "本机 / 局域网 Ollama",
    ),
    ProviderPreset(
        id = "lmstudio", label = "LM Studio", name = "LM Studio", category = "local",
        protocol = ProviderProtocol.OPENAI_CHAT, baseUrl = "http://127.0.0.1:1234/v1", authMode = ProviderAuthMode.NONE,
        chatUrl = "http://127.0.0.1:1234/v1", billingKind = "free", note = "本地服务 1234 端口",
    ),
)

/** 协议网关返回的单个服务商实时运行健康快照（密钥冷却与自适应鉴权学习状态）。 */
data class ProviderHealthSnapshot(
    val providerId: String,
    val enabled: Boolean = true,
    val keyCount: Int = 0,
    val coolingCount: Int = 0,
    val maxCooldownSec: Int = 0,
    val learnedAnthropicAuth: String? = null,
) {
    val hasCoolingKeys: Boolean get() = coolingCount > 0 && maxCooldownSec > 0
}

/** 密钥展示脱敏，避免界面明文暴露已保存的备用 API Key。 */
fun maskApiKey(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 8) return "••••" + trimmed.takeLast(minOf(4, trimmed.length))
    return trimmed.take(4) + "••••" + trimmed.takeLast(4)
}

/** 根据用户粘贴的 API 地址智能推断最可能的上游协议。 */
fun suggestProtocolForUrl(rawUrl: String): ProviderProtocol? {
    val lower = rawUrl.trim().lowercase()
    if (lower.length < 8 || (!lower.startsWith("http://") && !lower.startsWith("https://"))) return null
    return when {
        "generativelanguage.googleapis.com" in lower || "/v1beta" in lower && "gemini" in lower -> ProviderProtocol.GOOGLE_GEMINI
        "api.anthropic.com" in lower || lower.endsWith("/anthropic") || "/anthropic/" in lower -> ProviderProtocol.ANTHROPIC_MESSAGES
        "api.openai.com" in lower || "api.x.ai" in lower -> ProviderProtocol.OPENAI_RESPONSES
        ":11434" in lower || ":1234" in lower || "openrouter.ai" in lower || "api.deepseek.com" in lower ||
            "compatible-mode" in lower || "api.siliconflow" in lower -> ProviderProtocol.OPENAI_CHAT
        else -> null
    }
}

private fun isPrivateHost(host: String): Boolean {
    val lower = host.lowercase()
    if (lower in setOf("localhost", "::1", "[::1]") || lower.endsWith(".local")) return true
    if (!Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(lower)) return false
    val octets = lower.split('.').map { it.toInt() }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 127 || octets[0] == 10 ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 100 && octets[1] in 64..127)
}

/** 校验并规范化服务商地址：远端必须为 HTTPS，本机/局域网允许 HTTP。 */
fun validateProviderBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "请填写 API 地址" }
    val uri = URI(trimmed)
    val host = uri.host ?: error("API 地址缺少主机名")
    val allowedScheme = uri.scheme == "https" || (uri.scheme == "http" && isPrivateHost(host))
    require(allowedScheme) { "远端服务商地址必须使用 https://（仅本机或局域网允许 http://）" }
    require(uri.userInfo == null && uri.fragment == null) { "API 地址不能包含账号密码或片段" }
    require(uri.query == null) { "请在查询参数栏填写参数，API 地址仅保留路径" }
    return trimmed
}

fun validateOptionalEndpointUrl(raw: String): String {
    val t = raw.trim()
    return if (t.isEmpty()) "" else validateProviderBaseUrl(t)
}

fun newProviderId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

/** 导出服务商与路由组为便携 JSON（默认不含明文 API Key，确保安全分享与备份）。 */
fun exportProvidersBundleJson(providers: List<CustomProvider>, groups: List<RoutingGroup>): String =
    JSONObject()
        .put("schema", "solbraid.providers.v1")
        .put("exportedAt", System.currentTimeMillis())
        .put("providers", JSONArray().apply { providers.forEach { put(it.toMetadataJson()) } })
        .put("routingGroups", JSONArray().apply { groups.forEach { put(it.toJson()) } })
        .toString(2)

/** 从 JSON 文本导入服务商配置（兼容单条对象、数组或完整 bundle）。 */
fun parseProvidersBundleJson(raw: String): Pair<List<CustomProvider>, List<RoutingGroup>> {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "剪贴板内容为空" }
    val providers = mutableListOf<CustomProvider>()
    val groups = mutableListOf<RoutingGroup>()
    if (trimmed.startsWith("[")) {
        JSONArray(trimmed).objects().forEach { obj ->
            val p = CustomProvider.fromJson(obj.put("id", obj.optString("id").ifBlank { newProviderId() }), hasApiKey = false)
            providers += p.copy(baseUrl = validateProviderBaseUrl(p.baseUrl))
        }
    } else {
        val root = JSONObject(trimmed)
        if (root.has("providers")) {
            root.arr("providers").objects().forEach { obj ->
                val p = CustomProvider.fromJson(obj.put("id", obj.optString("id").ifBlank { newProviderId() }), hasApiKey = false)
                providers += p.copy(baseUrl = validateProviderBaseUrl(p.baseUrl))
            }
            root.arr("routingGroups").objects().forEach { obj ->
                runCatching { RoutingGroup.fromJson(obj) }.getOrNull()?.let { groups += it }
            }
        } else if (root.has("baseUrl")) {
            val p = CustomProvider.fromJson(root.put("id", root.optString("id").ifBlank { newProviderId() }), hasApiKey = false)
            providers += p.copy(baseUrl = validateProviderBaseUrl(p.baseUrl))
        }
    }
    require(providers.isNotEmpty() || groups.isNotEmpty()) { "未在 JSON 中找到有效的服务商配置" }
    return providers to groups
}
