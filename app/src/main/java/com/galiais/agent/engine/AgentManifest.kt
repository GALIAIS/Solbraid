package com.galiais.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/** GALIAIS 通用 Agent 协议（GUAP v1）支持的三档驱动类型。 */
enum class AgentProtocol(val wireId: String, val badge: String, val description: String) {
    CODEX_NATIVE("codex-native", "Codex Native", "官方 Codex app-server v2 原生协议"),
    CLAUDE_NATIVE("claude-native", "Claude SDK", "官方 Claude Agent SDK 原生协议"),
    ACP("acp", "ACP", "开放标准 Agent Client Protocol（JSON-RPC 2.0 over stdio）"),
    NDJSON("ndjson", "NDJSON", "通用 stream-json / JSONL 流式命令行桥接"),
    EXEC_STREAM("exec-stream", "CLI Exec", "通用命令行标准输出流式桥接");

    val isNative: Boolean get() = this == CODEX_NATIVE || this == CLAUDE_NATIVE

    companion object {
        fun of(wireId: String?): AgentProtocol =
            entries.firstOrNull { it.wireId.equals(wireId, ignoreCase = true) } ?: EXEC_STREAM
    }
}

/** 能力矩阵：界面按能力动态渲染按钮，而非硬编码判断引擎名称。 */
data class AgentCapabilities(
    val streaming: Boolean = true,
    val approval: Boolean = false,
    val reasoning: Boolean = false,
    val plan: Boolean = false,
    val diff: Boolean = false,
    val images: Boolean = false,
    val modes: Boolean = false,
    val models: Boolean = false,
    val effort: Boolean = false,
    val steer: Boolean = false,
    val revert: Boolean = false,
    val fork: Boolean = false,
    val archive: Boolean = false,
    val slashCommands: Boolean = false,
    val mcp: Boolean = false,
    val skills: Boolean = false,
    val delegation: Boolean = false,
    val customProvider: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("streaming", streaming)
        .put("approval", approval)
        .put("reasoning", reasoning)
        .put("plan", plan)
        .put("diff", diff)
        .put("images", images)
        .put("modes", modes)
        .put("models", models)
        .put("effort", effort)
        .put("steer", steer)
        .put("revert", revert)
        .put("fork", fork)
        .put("archive", archive)
        .put("slashCommands", slashCommands)
        .put("mcp", mcp)
        .put("skills", skills)
        .put("delegation", delegation)
        .put("customProvider", customProvider)

    companion object {
        val CODEX = AgentCapabilities(
            streaming = true, approval = true, reasoning = true, plan = true, diff = true,
            images = true, modes = true, models = true, effort = true, steer = true,
            revert = true, fork = true, archive = true, slashCommands = true, mcp = true,
            skills = true, delegation = true, customProvider = true,
        )
        val CLAUDE = AgentCapabilities(
            streaming = true, approval = true, reasoning = true, plan = true, diff = true,
            images = true, modes = true, models = true, effort = true, steer = false,
            revert = true, fork = true, archive = true, slashCommands = true, mcp = true,
            skills = true, delegation = true, customProvider = true,
        )
        val ACP_DEFAULT = AgentCapabilities(
            streaming = true, approval = true, reasoning = true, plan = true, diff = true,
            images = true, modes = true, models = true, effort = false, revert = true, slashCommands = true,
            mcp = true, customProvider = true,
        )
        val BRIDGE_DEFAULT = AgentCapabilities(
            streaming = true, approval = false, reasoning = false, models = true, revert = true, customProvider = true,
        )

        fun fromJson(obj: JSONObject?, fallback: AgentCapabilities = BRIDGE_DEFAULT): AgentCapabilities {
            if (obj == null) return fallback
            return AgentCapabilities(
                streaming = obj.optBoolean("streaming", fallback.streaming),
                approval = obj.optBoolean("approval", fallback.approval),
                reasoning = obj.optBoolean("reasoning", fallback.reasoning),
                plan = obj.optBoolean("plan", fallback.plan),
                diff = obj.optBoolean("diff", fallback.diff),
                images = obj.optBoolean("images", fallback.images),
                modes = obj.optBoolean("modes", fallback.modes),
                models = obj.optBoolean("models", fallback.models),
                effort = obj.optBoolean("effort", fallback.effort),
                steer = obj.optBoolean("steer", fallback.steer),
                revert = obj.optBoolean("revert", fallback.revert),
                fork = obj.optBoolean("fork", fallback.fork),
                archive = obj.optBoolean("archive", fallback.archive),
                slashCommands = obj.optBoolean("slashCommands", fallback.slashCommands),
                mcp = obj.optBoolean("mcp", fallback.mcp),
                skills = obj.optBoolean("skills", fallback.skills),
                delegation = obj.optBoolean("delegation", fallback.delegation),
                customProvider = obj.optBoolean("customProvider", fallback.customProvider),
            )
        }
    }
}

data class AgentInstallSpec(
    /** builtin / npm / script / custom */
    val strategy: String = "custom",
    val packageName: String? = null,
    val command: String? = null,
    val binaryPath: String? = null,
    val versionArgs: List<String> = listOf("--version"),
    val uninstallPaths: List<String> = emptyList(),
    val requiredCommands: List<String> = emptyList(),
) {
    fun previewCommand(): String = when (strategy) {
        "builtin" -> "内置组件，随运行环境安装"
        "npm" -> "npm install --prefix ~/.galiais/npm-global -g ${packageName.orEmpty()}"
        else -> command.orEmpty()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("strategy", strategy)
        .apply {
            packageName?.let { put("package", it) }
            command?.let { put("command", it) }
            binaryPath?.let { put("binaryPath", it) }
            put("versionArgs", JSONArray(versionArgs))
            put("uninstallPaths", JSONArray(uninstallPaths))
            put("requiredCommands", JSONArray(requiredCommands))
        }

    companion object {
        fun fromJson(obj: JSONObject?): AgentInstallSpec {
            if (obj == null) return AgentInstallSpec()
            return AgentInstallSpec(
                strategy = obj.str("strategy") ?: "custom",
                packageName = obj.str("package") ?: obj.str("packageName"),
                command = obj.str("command"),
                binaryPath = obj.str("binaryPath"),
                versionArgs = obj.optJSONArray("versionArgs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }?.takeIf { it.isNotEmpty() } ?: listOf("--version"),
                uninstallPaths = obj.optJSONArray("uninstallPaths")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                requiredCommands = obj.optJSONArray("requiredCommands")?.strings()?.also { commands ->
                    require(commands.size <= 16 && commands.all { Regex("[a-zA-Z0-9._+-]{1,80}").matches(it) }) { "安装依赖命令无效" }
                } ?: emptyList(),
            )
        }
    }
}

data class AgentLaunchSpec(
    val command: String = "",
    val args: List<String> = emptyList(),
    val promptFlag: String? = null,
    val modelFlag: String? = null,
    val authLoginCommand: String? = null,
    val env: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("command", command)
        .put("args", JSONArray(args))
        .apply {
            promptFlag?.let { put("promptFlag", it) }
            modelFlag?.let { put("modelFlag", it) }
            authLoginCommand?.let { put("authLoginCommand", it) }
            if (env.isNotEmpty()) put("env", JSONObject().apply { env.forEach { (k, v) -> put(k, v) } })
        }

    companion object {
        fun fromJson(obj: JSONObject?): AgentLaunchSpec {
            if (obj == null) return AgentLaunchSpec()
            val envObj = obj.optJSONObject("env")
            val envMap = linkedMapOf<String, String>()
            if (envObj != null) envObj.keys().forEach { k -> envObj.str(k)?.let { envMap[k] = it } }
            return AgentLaunchSpec(
                command = obj.str("command") ?: "",
                args = obj.optJSONArray("args")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                promptFlag = obj.str("promptFlag"),
                modelFlag = obj.str("modelFlag"),
                authLoginCommand = obj.str("authLoginCommand"),
                env = envMap,
            )
        }
    }
}

data class AgentProviderBridge(
    /** openai-chat / anthropic / gemini */
    val gatewayRoute: String = "openai-chat",
    val baseUrlEnv: String? = null,
    val apiKeyEnv: String? = null,
    val modelEnv: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gatewayRoute", gatewayRoute)
        .put("envMapping", JSONObject().apply {
            baseUrlEnv?.let { put("baseUrl", it) }
            apiKeyEnv?.let { put("apiKey", it) }
            modelEnv?.let { put("model", it) }
        })

    companion object {
        fun fromJson(obj: JSONObject?): AgentProviderBridge? {
            if (obj == null) return null
            val map = obj.optJSONObject("envMapping") ?: obj
            val base = map.str("baseUrl") ?: map.str("baseUrlEnv")
            val key = map.str("apiKey") ?: map.str("apiKeyEnv")
            val model = map.str("model") ?: map.str("modelEnv")
            if (base == null && key == null && model == null) return null
            return AgentProviderBridge(
                gatewayRoute = obj.str("gatewayRoute") ?: "openai-chat",
                baseUrlEnv = base,
                apiKeyEnv = key,
                modelEnv = model,
            )
        }
    }
}

/** 统一 Agent 清单：描述内置官方引擎、内置扩展目录与用户导入的自定义 CLI Agent。 */
data class AgentManifest(
    val id: String,
    val name: String,
    val vendor: String,
    val description: String,
    val accentHex: Long,
    val protocol: AgentProtocol,
    val builtIn: Boolean = false,
    val preinstalled: Boolean = false,
    val homepage: String = "",
    val install: AgentInstallSpec = AgentInstallSpec(),
    val launch: AgentLaunchSpec = AgentLaunchSpec(),
    val providerBridge: AgentProviderBridge? = null,
    val capabilities: AgentCapabilities = AgentCapabilities.BRIDGE_DEFAULT,
) {
    val engineKind: EngineKind get() = EngineKind(id, name)

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("id", id)
        .put("name", name)
        .put("vendor", vendor)
        .put("description", description)
        .put("accentColor", String.format("#%06X", accentHex and 0xFFFFFF))
        .put("protocol", protocol.wireId)
        .put("homepage", homepage)
        .put("install", install.toJson())
        .put("launch", launch.toJson())
        .apply { providerBridge?.let { put("providerBridge", it.toJson()) } }
        .put("capabilities", capabilities.toJson())

    companion object {
        private val ID_RE = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")

        fun parseAccentHex(raw: String?, fallback: Long = 0xFF6366F1): Long {
            val cleaned = raw?.trim()?.removePrefix("#") ?: return fallback
            if (cleaned.length != 6) return fallback
            val rgb = cleaned.toLongOrNull(16) ?: return fallback
            return 0xFF000000L or rgb
        }

        fun fromJson(obj: JSONObject, builtIn: Boolean = false, preinstalled: Boolean = false): AgentManifest {
            val id = (obj.str("id") ?: "").trim().lowercase()
            require(ID_RE.matches(id)) { "Agent ID 仅允许小写字母、数字、连字符和下划线（1～48 位）" }
            val name = (obj.str("name") ?: "").trim()
            require(name.isNotBlank() && name.length <= 60) { "请填写 Agent 名称（不超过 60 字）" }
            val protocol = AgentProtocol.of(obj.str("protocol"))
            val capFallback = when (protocol) {
                AgentProtocol.CODEX_NATIVE -> AgentCapabilities.CODEX
                AgentProtocol.CLAUDE_NATIVE -> AgentCapabilities.CLAUDE
                AgentProtocol.ACP -> AgentCapabilities.ACP_DEFAULT
                else -> AgentCapabilities.BRIDGE_DEFAULT
            }
            return AgentManifest(
                id = id,
                name = name,
                vendor = obj.str("vendor") ?: "自定义",
                description = obj.str("description") ?: "通过 ${protocol.badge} 接入的 CLI Agent",
                accentHex = parseAccentHex(obj.str("accentColor")),
                protocol = protocol,
                builtIn = builtIn,
                preinstalled = preinstalled,
                homepage = obj.str("homepage") ?: "",
                install = AgentInstallSpec.fromJson(obj.optJSONObject("install")),
                launch = AgentLaunchSpec.fromJson(obj.optJSONObject("launch")),
                providerBridge = AgentProviderBridge.fromJson(obj.optJSONObject("providerBridge")),
                capabilities = AgentCapabilities.fromJson(obj.optJSONObject("capabilities"), capFallback),
            )
        }
    }
}

/** VM 侧 Agent 实时探测状态。 */
data class AgentRuntimeStatus(
    val id: String,
    val installed: Boolean = false,
    val version: String? = null,
    val binaryPath: String? = null,
    val running: Boolean = false,
    val installing: Boolean = false,
    val activeSessions: Int = 0,
    val error: String? = null,
    val installError: String? = null,
    val installation: AgentInstallProgress? = null,
)

enum class AgentInstallPhase(val wireId: String, val label: String, val active: Boolean) {
    PREPARING("preparing", "准备安装", true),
    INSTALLING("installing", "下载与安装", true),
    VERIFYING("verifying", "检查程序", true),
    CANCELLING("cancelling", "正在取消", true),
    COMPLETED("completed", "安装完成", false),
    CANCELLED("cancelled", "已取消安装", false),
    FAILED("failed", "安装失败", false),
}

data class AgentInstallProgress(val operationId: String, val phase: AgentInstallPhase, val step: Int) {
    companion object {
        const val STEP_COUNT = 3
        fun fromJson(data: JSONObject): AgentInstallProgress {
            val phase = AgentInstallPhase.entries.firstOrNull { it.wireId == data.getString("phase") }
                ?: error("安装阶段无效")
            val step = data.getInt("step")
            require(step in 1..STEP_COUNT) { "安装步骤无效" }
            return AgentInstallProgress(data.getString("operationId"), phase, step)
        }
    }
}

/** 交互登录助手捕获到的 OAuth URL、设备码与终端行。 */
data class AgentAuthState(
    val agentId: String,
    val running: Boolean = false,
    val url: String? = null,
    val deviceCode: String? = null,
    val needsInput: Boolean = false,
    val lines: List<String> = emptyList(),
    val done: Boolean = false,
    val ok: Boolean? = null,
)

/** CLI 账号当前认证状态（Claude.ai / Antigravity Google / Codex ChatGPT）。 */
data class CliAuthStatus(
    val agentId: String,
    val loggedIn: Boolean = false,
    val authMethod: String = "none",
    val email: String? = null,
    val subscriptionType: String? = null,
    val orgName: String? = null,
    val raw: String = "",
)

/** 应用内置的三大原生 Agent。 */
object BuiltInAgents {
    val CODEX = AgentManifest(
        id = "codex",
        name = "Codex",
        vendor = "OpenAI",
        description = "内置 Codex，支持任务执行、运行中补充指令、会话分支与代码审查。",
        accentHex = 0xFF10A37F,
        protocol = AgentProtocol.CODEX_NATIVE,
        builtIn = true,
        preinstalled = true,
        homepage = "https://github.com/openai/codex",
        install = AgentInstallSpec(strategy = "builtin"),
        capabilities = AgentCapabilities.CODEX,
    )

    val CLAUDE = AgentManifest(
        id = "claude",
        name = "Claude Code",
        vendor = "Anthropic",
        description = "内置 Claude Code，支持计划模式、权限切换、会话分支与子代理。",
        accentHex = 0xFFD97757,
        protocol = AgentProtocol.CLAUDE_NATIVE,
        builtIn = true,
        preinstalled = true,
        homepage = "https://code.claude.com",
        install = AgentInstallSpec(strategy = "builtin"),
        capabilities = AgentCapabilities.CLAUDE,
    )

    val ANTIGRAVITY = AgentManifest(
        id = "antigravity",
        name = "Antigravity",
        vendor = "Google",
        description = "内置 Google Antigravity，支持原生 ACP 与 Stream-JSON 双协议、Google OAuth / API Key / 自定义服务商、思考强度、五档权限与插件。",
        accentHex = 0xFF4285F4,
        protocol = AgentProtocol.ACP,
        builtIn = true,
        preinstalled = true,
        homepage = "https://antigravity.google",
        install = AgentInstallSpec(
            strategy = "builtin",
            binaryPath = "/opt/galiais-codex/usr/lib/chatgpt/resources/agy",
            versionArgs = listOf("--version"),
        ),
        launch = AgentLaunchSpec(
            command = "/opt/galiais-codex/usr/lib/chatgpt/resources/agy",
            args = listOf("acp"),
            authLoginCommand = "/opt/galiais-codex/usr/lib/chatgpt/resources/agy acp --login",
        ),
        providerBridge = AgentProviderBridge(
            gatewayRoute = "gemini",
            baseUrlEnv = "GOOGLE_GEMINI_BASE_URL",
            apiKeyEnv = "GEMINI_API_KEY",
            modelEnv = "AGY_LLM_GATEWAY_MODELS",
        ),
        capabilities = AgentCapabilities(
            streaming = true, approval = true, reasoning = true, plan = true, diff = true,
            images = true, modes = true, models = true, effort = true, steer = false,
            revert = true, fork = true, archive = true, slashCommands = true,
            mcp = true, skills = true, delegation = true, customProvider = true,
        ),
    )


    val ALL: List<AgentManifest> = listOf(CODEX, CLAUDE, ANTIGRAVITY)
    val EXTERNAL: List<AgentManifest> = ALL.filterNot { it.protocol.isNative }
}

/** Agent 目录与协议路由；旧配置不会重新注册已移除的 Agent。 */
object AgentCatalog {
    fun all(): List<AgentManifest> = BuiltInAgents.ALL

    fun external(): List<AgentManifest> = BuiltInAgents.EXTERNAL

    fun find(id: String): AgentManifest? = all().firstOrNull { it.id == id }

    fun engineKindOf(id: String): EngineKind = when (id) {
        "codex" -> EngineKind.CODEX
        "claude" -> EngineKind.CLAUDE
        "agent-hub" -> EngineKind.HUB
        else -> find(id)?.engineKind ?: EngineKind(id, id)
    }

    fun allEngineKinds(): List<EngineKind> = all().map { it.engineKind }

    fun capabilitiesOf(kind: EngineKind): AgentCapabilities =
        find(kind.id)?.capabilities ?: AgentCapabilities.BRIDGE_DEFAULT
}
