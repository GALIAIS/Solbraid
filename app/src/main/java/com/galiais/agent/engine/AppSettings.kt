package com.galiais.agent.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.galiais.agent.engine.mobile.MobileUseConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SettingsState(
    val codexProviderId: String? = null,
    val codexModel: String? = null,
    val codexEffort: String? = null,
    val codexServiceTier: String? = null,
    val codexCollaborationMode: String = "default",
    val codexWebSearch: String? = null,
    val claudeProviderId: String? = null,
    val claudeModel: String? = null,
    val claudeEffort: String? = null,
    val claudeFastMode: Boolean? = null,
    val claudeThinkingDisplay: String = "summarized",
    val claudePromptCacheTtl: String? = null,
    val claudeCommitAttribution: Boolean = true,
    val claudeBaseUrl: String? = null,
    val hasClaudeKey: Boolean = false,
    val hasClaudeOauthToken: Boolean = false,
    val hasAntigravityKey: Boolean = false,
    val antigravityPermissionMode: String = "default",
    val antigravityEffort: String? = null,
    val antigravitySandbox: Boolean = false,
    val antigravityAgent: String? = null,
    val antigravityTransport: String = "acp",
    val artifactsModeEnabled: Boolean = true,
    val providers: List<CustomProvider> = emptyList(),
    val routingGroups: List<RoutingGroup> = emptyList(),
    /** Codex 审批策略：on-request（按需询问）、untrusted（严格询问）或 never（完全访问）。 */
    val approvalPolicy: String = "on-request",
    /** Codex 审批审校者：user（人工审批）或 auto_review（Guardian AI 自动审校）。 */
    val approvalsReviewer: String = "user",
    /** Claude Code 默认权限模式（5 档）：default / acceptEdits / plan / auto / bypassPermissions。 */
    val claudePermissionMode: String = "default",
    /** Codex 推理摘要详细度：auto / concise / detailed / none。 */
    val reasoningSummary: String = "auto",
    val defaultCwd: String = DEFAULT_WORKSPACE,
    /** 已添加的项目文件夹列表（按用户顺序，用于会话页折叠分组）。 */
    val projects: List<String> = listOf(DEFAULT_WORKSPACE),
    /** 在会话页中处于折叠状态的项目文件夹路径。 */
    val collapsedProjects: Set<String> = emptySet(),
    val trustedProjects: Set<String> = emptySet(),
    val delegationEnabled: Boolean = true,
    /** 对话交互体验与常规编辑器/渲染设置。 */
    val autoScroll: Boolean = true,
    val expandThinking: Boolean = false,
    val showTokenUsage: Boolean = true,
    val enterToSend: Boolean = false,
    /** 用户编辑器是否启用纯文本模式：true=绝不渲染任何输入；false=自动渲染（实时语法着色+自动预览）。 */
    val plainTextEditor: Boolean = false,
    val editorSyntaxHighlight: Boolean = true,
    val editorAutoPreview: Boolean = true,
    val editorFormatToolbar: Boolean = false,
    val editorAutoPair: Boolean = false,
    val editorMonospace: Boolean = false,
    /** 大文本保护阈值（字符数，0 表示不限制）：超过后自动临时降级为纯文本零渲染。 */
    val editorPlainFallbackThreshold: Int = 8000,
    val showComposerWordCount: Boolean = false,
    /** 已发送用户消息渲染模式：follow_editor（跟随编辑器）/ always_rich / always_plain。 */
    val userMessageRenderMode: String = USER_RENDER_FOLLOW_EDITOR,
    val assistantRichRender: Boolean = true,
    val inlineVisualArtifacts: Boolean = true,
    val renderMathFormulas: Boolean = true,
    val keepUnsentDrafts: Boolean = true,
    val fileEditorWordWrap: Boolean = true,
    /** 缩进宽度：2 / 4 空格，0 表示真实 Tab 字符。 */
    val editorTabSize: Int = 2,
    /** 运行环境模式（null 表示首次进入尚未选择）与启动/后台常驻策略。 */
    val runtimeMode: String? = null,
    val autoStartRuntime: Boolean = true,
    val keepRuntimeOnExit: Boolean = true,
    /** 虚拟机内存档位（MiB）：支持 2048 / 3072 / 4096 / 6144。 */
    val vmMemoryMiB: Int = 4096,
    /** 完整外观主题配置（设计语言、配色、7 维外观组合、字号、代码配色等）。 */
    val theme: ThemeConfig = ThemeConfig(),
    /** 用户保存的自定义混搭主题槽位。 */
    val customThemes: List<CustomThemeSlot> = emptyList(),
    /** 用户自定义/导入的外部 CLI Agent 清单（GUAP v1）。 */
    val customAgents: List<AgentManifest> = emptyList(),
    /** 第三方 Agent 默认绑定的自定义服务商与模型（agentId -> providerId / model）。 */
    val agentProviders: Map<String, String> = emptyMap(),
    val agentModels: Map<String, String> = emptyMap(),
    /** Mobile Super-Agent 编排配置：最大并发 Worker 槽位（1..3）及按角色路由的引擎/模型。 */
    val maxParallelWorkers: Int = 2,
    val roleRoutes: Map<String, RoleRouteConfig> = RoleRouteConfig.defaults(),
    /** 默认沿用用户网络，仅在明确配置时覆盖下载来源与代理。 */
    val downloadNetwork: DownloadNetworkConfig = DownloadNetworkConfig(),
    /** 本机纯本地 ARM64 APK 自迭代闭环配置（默认关闭，可在设置完全自由配置）。 */
    val selfBuild: SelfBuildConfig = SelfBuildConfig(),
    /** 双模式 AI 手机操控（Mobile-Use）配置：chroot/Root 与 PRoot/无障碍统一护栏与后端路由。 */
    val mobileUse: MobileUseConfig = MobileUseConfig(),
) {
    /** 兼容旧调用：明暗模式。 */
    val themeMode: String get() = theme.mode
    val effectiveRuntimeMode: String get() = runtimeMode ?: RUNTIME_MODE_ROOT
    fun provider(id: String?): CustomProvider? = id?.let { target ->
        providers.firstOrNull { it.id == target }
            ?: routingGroups.firstOrNull { it.virtualProviderId == target }?.let { g ->
                CustomProvider(
                    id = g.virtualProviderId,
                    name = g.name,
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    baseUrl = "http://127.0.0.1",
                    authMode = ProviderAuthMode.NONE,
                    models = listOf("auto") + g.targets.map { it.model }.filter { it.isNotBlank() }.distinct(),
                    billingKind = "plan",
                )
            }
    }
    fun allSelectableProviders(): List<CustomProvider> = providers.filter { it.enabled } + routingGroups.filter { it.targets.isNotEmpty() }.mapNotNull { provider(it.virtualProviderId) }
    fun defaultProviderIdFor(agent: EngineKind): String? = when (agent.id) {
        "codex" -> codexProviderId
        "claude" -> claudeProviderId
        else -> agentProviders[agent.id]
    }
    fun defaultModelFor(agent: EngineKind): String? = when (agent.id) {
        "codex" -> codexModel
        "claude" -> claudeModel
        else -> agentModels[agent.id]
    }
    fun defaultReasoningSummaryFor(agent: EngineKind): String =
        if (agent == EngineKind.CLAUDE) { if (claudeThinkingDisplay == "omitted") "none" else "auto" } else reasoningSummary

    val shouldRenderUserMessages: Boolean
        get() = when (userMessageRenderMode) {
            USER_RENDER_ALWAYS_RICH -> true
            USER_RENDER_ALWAYS_PLAIN -> false
            else -> !plainTextEditor
        }

    fun tabIndentString(): String = when (editorTabSize) {
        0 -> "\t"
        4 -> "    "
        else -> "  "
    }
}

const val USER_RENDER_FOLLOW_EDITOR = "follow_editor"
const val USER_RENDER_ALWAYS_RICH = "always_rich"
const val USER_RENDER_ALWAYS_PLAIN = "always_plain"
val USER_RENDER_MODES = setOf(USER_RENDER_FOLLOW_EDITOR, USER_RENDER_ALWAYS_RICH, USER_RENDER_ALWAYS_PLAIN)
val EDITOR_FALLBACK_THRESHOLDS = setOf(0, 4000, 8000, 20000)
val EDITOR_TAB_SIZES = setOf(0, 2, 4)

const val DEFAULT_WORKSPACE = "/home/agent/workspace"
const val RUNTIME_MODE_ROOT = "root"
const val RUNTIME_MODE_PROOT = "proot"
const val CN_NPM_MIRROR = "https://registry.npmmirror.com"
const val CN_PYPI_MIRROR = "https://pypi.tuna.tsinghua.edu.cn/simple"

/** Agent 与扩展共用的下载网络配置。 */
data class DownloadNetworkConfig(
    /** official 沿用工具原有来源配置，cn_mirror 显式使用国内镜像。 */
    val mirrorPreset: String = "official",
    /** 自定义 npm registry（留空则按预设决定）。 */
    val customNpmRegistry: String = "",
    /** 仅用于 GitHub 仓库导入，不改写安装脚本。 */
    val githubMirrorPrefix: String = "",
    /** 代理模式：system（读取 Android 系统代理）、manual（手动代理）或 none（直连）。 */
    val proxyMode: String = "system",
    /** 手动代理地址（如 http://127.0.0.1:7890 或 socks5h://127.0.0.1:10808）。 */
    val manualProxyUrl: String = "",
    /** 按需启用 IPv4 兼容选项，默认保留网络的地址族选择。 */
    val preferIpv4: Boolean = false,
) {
    fun validated(): DownloadNetworkConfig {
        require(mirrorPreset in setOf("official", "cn_mirror")) { "请选择有效的下载来源" }
        require(proxyMode in setOf("system", "manual", "none")) { "请选择有效的代理方式" }
        fun address(value: String, label: String, schemes: Set<String>, proxy: Boolean = false): String {
            val text = value.trim()
            if (text.isEmpty()) return ""
            val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("${label}格式无效") }
            require(uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "${label}的协议、主机或端口无效" }
            require(uri.rawQuery == null && uri.rawFragment == null && !text.any { it.isWhitespace() || it.code < 32 }) { "${label}不能包含查询参数、片段或空白字符" }
            if (proxy) require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "代理地址不能包含路径" }
            return text.trimEnd('/')
        }
        val npm = address(customNpmRegistry, "npm 镜像地址", setOf("http", "https"))
        val github = address(githubMirrorPrefix, "GitHub 仓库镜像地址", setOf("http", "https"))
        val proxy = address(manualProxyUrl, "代理地址", setOf("http", "https", "socks5", "socks5h"), proxy = true)
        require(proxyMode != "manual" || proxy.isNotEmpty()) { "请填写手动代理地址" }
        return copy(customNpmRegistry = npm, githubMirrorPrefix = github, manualProxyUrl = proxy)
    }

    fun resolvedNpmRegistry(): String =
        customNpmRegistry.trim().ifBlank { if (mirrorPreset == "cn_mirror") CN_NPM_MIRROR else "" }

    fun resolvedPypiIndex(): String =
        if (mirrorPreset == "cn_mirror") CN_PYPI_MIRROR else ""

    fun resolvedProxyUrl(systemProxy: String?): String = when (proxyMode) {
        "manual" -> manualProxyUrl.trim()
        "system" -> systemProxy?.trim().orEmpty()
        else -> ""
    }

    fun toHubJson(systemProxy: String?): JSONObject = JSONObject()
        .put("proxyMode", proxyMode)
        .put("npmRegistry", resolvedNpmRegistry())
        .put("pypiIndexUrl", resolvedPypiIndex())
        .put("githubMirrorPrefix", githubMirrorPrefix.trim())
        .put("proxyUrl", resolvedProxyUrl(systemProxy))
        .put("preferIpv4", preferIpv4)

    fun toJson(): JSONObject = JSONObject()
        .put("mirrorPreset", mirrorPreset)
        .put("customNpmRegistry", customNpmRegistry)
        .put("githubMirrorPrefix", githubMirrorPrefix)
        .put("proxyMode", proxyMode)
        .put("manualProxyUrl", manualProxyUrl)
        .put("preferIpv4", preferIpv4)

    companion object {
        fun fromJson(raw: String?): DownloadNetworkConfig {
            if (raw.isNullOrBlank()) return DownloadNetworkConfig()
            return try {
                val obj = JSONObject(raw)
                DownloadNetworkConfig(
                    mirrorPreset = obj.optString("mirrorPreset", "official").takeIf { it in setOf("cn_mirror", "official") } ?: "official",
                    customNpmRegistry = obj.optString("customNpmRegistry", ""),
                    githubMirrorPrefix = obj.optString("githubMirrorPrefix", ""),
                    proxyMode = obj.optString("proxyMode", "system").takeIf { it in setOf("system", "manual", "none") } ?: "system",
                    manualProxyUrl = obj.optString("manualProxyUrl", ""),
                    preferIpv4 = obj.optBoolean("preferIpv4", false),
                )
            } catch (_: Exception) {
                DownloadNetworkConfig()
            }
        }
    }
}

/** 本机纯本地 ARM64 APK 自迭代闭环配置：默认关闭，所有权限护栏、工具链目录、构建与看门狗参数均可在设置完全自由配置。 */
data class SelfBuildConfig(
    val enabled: Boolean = false,
    val allowAgentBuild: Boolean = false,
    val allowAgentApply: Boolean = false,
    val runUnitTests: Boolean = true,
    val watchdogEnabled: Boolean = true,
    val watchdogTimeoutSec: Int = 20,
    val repoDir: String = "/home/agent/workspace/Solbraid",
    val androidHome: String = "/home/agent/android-sdk",
    val javaHome: String = "",
    val aapt2Path: String = "",
    val signingPropertiesPath: String = "/home/agent/.galiais/self-build-signing.properties",
    val targetAbis: String = "arm64-v8a",
    val variant: String = "debug",
    val gradleHeapMb: Int = 4096,
    val customGradleArgs: String = "",
) {
    fun validated(): SelfBuildConfig {
        fun cleanPath(value: String, label: String, allowEmpty: Boolean): String {
            val s = value.trim()
            if (s.isEmpty()) {
                require(allowEmpty) { "${label}不能为空" }
                return ""
            }
            require(s.startsWith("/") && '\u0000' !in s && '\n' !in s && '\r' !in s) { "${label}必须是有效的 Linux 绝对路径" }
            return s.trimEnd('/').ifEmpty { "/" }
        }
        val cleanRepo = cleanPath(repoDir, "源码仓库目录", allowEmpty = false)
        val cleanSdk = cleanPath(androidHome, "Android SDK 目录", allowEmpty = false)
        val cleanJava = cleanPath(javaHome, "JDK 目录", allowEmpty = true)
        val cleanAapt2 = cleanPath(aapt2Path, "原生 aapt2 路径", allowEmpty = true)
        require(targetAbis in SELF_BUILD_ABIS) { "请选择有效的目标 ABI" }
        require(variant in SELF_BUILD_VARIANTS) { "请选择有效的构建变体" }
        require(gradleHeapMb in SELF_BUILD_HEAP_OPTIONS) { "请选择有效的 Gradle 内存档位" }
        require(watchdogTimeoutSec in 10..120) { "看门狗心跳超时需在 10 到 120 秒之间" }
        val cleanExtra = customGradleArgs.trim()
        require(cleanExtra.length <= 512) { "附加 Gradle 参数不能超过 512 字符" }
        require(!Regex("""[;&|`$()<>\r\n\u0000]""").containsMatchIn(cleanExtra)) { "附加 Gradle 参数包含不允许的特殊字符" }
        if (cleanExtra.isNotEmpty()) {
            for (tok in cleanExtra.split(Regex("\\s+"))) {
                require(Regex("^-[A-Za-z0-9._:=/-]+$").matches(tok)) { "附加 Gradle 参数格式无效：$tok" }
            }
        }
        return copy(
            repoDir = cleanRepo,
            androidHome = cleanSdk,
            javaHome = cleanJava,
            aapt2Path = cleanAapt2,
            signingPropertiesPath = cleanPath(signingPropertiesPath, "签名配置文件", allowEmpty = false),
            customGradleArgs = cleanExtra,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("allowAgentBuild", allowAgentBuild)
        .put("allowAgentApply", allowAgentApply)
        .put("runUnitTests", runUnitTests)
        .put("watchdogEnabled", watchdogEnabled)
        .put("watchdogTimeoutSec", watchdogTimeoutSec)
        .put("repoDir", repoDir)
        .put("androidHome", androidHome)
        .put("javaHome", javaHome)
        .put("aapt2Path", aapt2Path)
        .put("signingPropertiesPath", signingPropertiesPath)
        .put("targetAbis", targetAbis)
        .put("variant", variant)
        .put("gradleHeapMb", gradleHeapMb)
        .put("customGradleArgs", customGradleArgs)

    companion object {
        fun fromJson(raw: String?): SelfBuildConfig {
            if (raw.isNullOrBlank()) return SelfBuildConfig()
            return try {
                val obj = JSONObject(raw)
                SelfBuildConfig(
                    enabled = obj.optBoolean("enabled", false),
                    allowAgentBuild = obj.optBoolean("allowAgentBuild", false),
                    allowAgentApply = obj.optBoolean("allowAgentApply", false),
                    runUnitTests = obj.optBoolean("runUnitTests", true),
                    watchdogEnabled = obj.optBoolean("watchdogEnabled", true),
                    watchdogTimeoutSec = obj.optInt("watchdogTimeoutSec", 20).coerceIn(10, 120),
                    repoDir = obj.optString("repoDir", "/home/agent/workspace/Solbraid").ifBlank { "/home/agent/workspace/Solbraid" },
                    androidHome = obj.optString("androidHome", "/home/agent/android-sdk").ifBlank { "/home/agent/android-sdk" },
                    javaHome = obj.optString("javaHome", ""),
                    aapt2Path = obj.optString("aapt2Path", ""),
                    signingPropertiesPath = obj.optString("signingPropertiesPath", "/home/agent/.galiais/self-build-signing.properties"),
                    targetAbis = obj.optString("targetAbis", "arm64-v8a").takeIf { it in SELF_BUILD_ABIS } ?: "arm64-v8a",
                    variant = obj.optString("variant", "debug").takeIf { it in SELF_BUILD_VARIANTS } ?: "debug",
                    gradleHeapMb = obj.optInt("gradleHeapMb", 4096).takeIf { it in SELF_BUILD_HEAP_OPTIONS } ?: 4096,
                    customGradleArgs = obj.optString("customGradleArgs", ""),
                )
            } catch (_: Exception) {
                SelfBuildConfig()
            }
        }
    }
}

val SELF_BUILD_ABIS = setOf("arm64-v8a", "arm64-v8a,x86_64")
val SELF_BUILD_VARIANTS = setOf("debug", "release")
val SELF_BUILD_HEAP_OPTIONS = setOf(2048, 3072, 4096, 6144)

/** 读取 Android 系统当前网络 HTTP 代理（无需额外权限）。 */
fun detectSystemProxy(context: Context): String? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val lp = cm?.defaultProxy
    val host = lp?.host?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getProperty("http.proxyHost")?.trim()?.takeIf { it.isNotEmpty() }
    val port = lp?.port?.takeIf { it in 1..65535 }
        ?: System.getProperty("http.proxyPort")?.toIntOrNull()?.takeIf { it in 1..65535 }
    val authority = host?.let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    return if (authority != null && port != null) "http://$authority:$port" else null
}

/** Super-Agent 按角色路由的引擎、服务商、模型与推理强度（不污染单聊默认设置）。 */
data class RoleRouteConfig(
    val engineId: String,
    val providerId: String? = null,
    val model: String? = null,
    val effort: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("engineId", engineId)
        .put("providerId", providerId ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL)
        .put("effort", effort ?: JSONObject.NULL)

    companion object {
        const val ROLE_ORCHESTRATOR = "orchestrator"
        const val ROLE_WORKER = "worker"
        const val ROLE_REVIEWER = "reviewer"

        fun defaults(): Map<String, RoleRouteConfig> = linkedMapOf(
            ROLE_ORCHESTRATOR to RoleRouteConfig(engineId = "claude"),
            ROLE_WORKER to RoleRouteConfig(engineId = "codex"),
            ROLE_REVIEWER to RoleRouteConfig(engineId = "claude"),
        )

        fun fromJson(obj: JSONObject, fallbackEngine: String): RoleRouteConfig = RoleRouteConfig(
            engineId = obj.str("engineId") ?: fallbackEngine,
            providerId = obj.str("providerId"),
            model = obj.str("model"),
            effort = obj.str("effort"),
        )
    }
}
val CODEX_APPROVAL_POLICIES = setOf("untrusted", "on-request", "never")
val CODEX_APPROVAL_REVIEWERS = setOf("user", "auto_review")
val CLAUDE_PERMISSION_MODES = setOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")
val REASONING_SUMMARIES = setOf("auto", "concise", "detailed", "none")
val CODEX_COLLABORATION_MODES = setOf("default", "plan")
val CODEX_WEB_SEARCH_MODES = setOf("disabled", "cached", "live")
val CLAUDE_THINKING_DISPLAYS = setOf("summarized", "omitted")
val CLAUDE_CACHE_TTLS = setOf("5m", "1h")
val ANTIGRAVITY_PERMISSION_MODES = setOf("default", "accept-edits", "auto", "plan", "bypass")
val ANTIGRAVITY_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")

/** 应用设置：普通项存 SharedPreferences，密钥（含所有自定义服务商 API Key）均用 Android Keystore AES-GCM 加密后保存。 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("galiais-settings", Context.MODE_PRIVATE)
    private val stateFlow = MutableStateFlow(run {
        if (prefs.contains("codex.sandbox")) {
            // 旧“只读”选项实际使用严格审批，迁移时保留该行为。
            prefs.edit().apply {
                if (prefs.getString("codex.sandbox", null) == "read-only") putString("codex.approvalPolicy", "untrusted")
                remove("codex.sandbox")
            }.apply()
        }
        if (!prefs.contains("claude.thinkingDisplay")) {
            val display = if (prefs.getString("chat.reasoningSummary", "auto") == "none") "omitted" else "summarized"
            prefs.edit().putString("claude.thinkingDisplay", display).apply()
        }
        load()
    })
    val state: StateFlow<SettingsState> = stateFlow.asStateFlow()

    private val providerKeyCountCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun cachedProviderKeyCount(id: String): Int =
        providerKeyCountCache.getOrPut(id) { providerApiKeys(id).size }

    private fun loadProviders(): List<CustomProvider> {
        val raw = prefs.getString("providers.list", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { obj ->
                val id = obj.optString("id")
                if (id.isBlank()) null else {
                    val hasSecret = prefs.contains(PROVIDER_SECRET_PREFIX + id) || prefs.contains(PROVIDER_KEYPOOL_PREFIX + id)
                    val storedCount = obj.optInt("keyPoolCount", -1)
                    val poolSize = when {
                        !hasSecret -> 0
                        storedCount > 0 -> storedCount
                        else -> cachedProviderKeyCount(id)
                    }
                    val hasAny = poolSize > 0 || hasSecret
                    CustomProvider.fromJson(obj, hasAny, poolSize.coerceAtLeast(if (hasAny) 1 else 0))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadRoutingGroups(): List<RoutingGroup> {
        val raw = prefs.getString("providers.routingGroups", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { runCatching { RoutingGroup.fromJson(it) }.getOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadCustomAgents(): List<AgentManifest> {
        val raw = prefs.getString("agents.custom", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { runCatching { AgentManifest.fromJson(it) }.getOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadCustomThemes(): List<CustomThemeSlot> {
        val raw = prefs.getString("ui.customThemes", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { runCatching { CustomThemeSlot.fromJson(it) }.getOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadStringMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { k -> obj.str(k)?.let { put(k, it) } } }
        } catch (_: Exception) { emptyMap() }
    }

    private fun loadProjects(defaultCwd: String): List<String> {
        val raw = prefs.getString("projects.list", null)
        val parsed = if (raw.isNullOrBlank()) emptyList() else try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).trim().trimEnd('/').ifEmpty { "/" }.takeIf { it.startsWith("/") }
            }
        } catch (_: Exception) { emptyList() }
        val normalizedDefault = defaultCwd.trim().trimEnd('/').ifEmpty { "/" }
        return (parsed + normalizedDefault).distinct()
    }

    private fun load(): SettingsState {
        val providers = loadProviders()
        val routingGroups = loadRoutingGroups()
        val ids = providers.map { it.id }.toSet() + routingGroups.map { it.virtualProviderId }.toSet()
        val customAgents = loadCustomAgents()
        val defaultCwd = prefs.getString("defaultCwd", DEFAULT_WORKSPACE)!!
        return SettingsState(
            codexProviderId = prefs.getString("codex.providerId", null)?.takeIf { it in ids },
            codexModel = prefs.getString("codex.model", null),
            codexEffort = prefs.getString("codex.effort", null),
            codexServiceTier = prefs.getString("codex.serviceTier", null),
            codexCollaborationMode = prefs.getString("codex.collaborationMode", "default")?.takeIf { it in CODEX_COLLABORATION_MODES } ?: "default",
            codexWebSearch = prefs.getString("codex.webSearch", null)?.takeIf { it in CODEX_WEB_SEARCH_MODES },
            claudeProviderId = prefs.getString("claude.providerId", null)?.takeIf { it in ids },
            claudeModel = prefs.getString("claude.model", null),
            claudeEffort = prefs.getString("claude.effort", null),
            claudeFastMode = if (prefs.contains("claude.fastMode")) prefs.getBoolean("claude.fastMode", false) else null,
            claudeThinkingDisplay = prefs.getString("claude.thinkingDisplay", "summarized")?.takeIf { it in CLAUDE_THINKING_DISPLAYS } ?: "summarized",
            claudePromptCacheTtl = prefs.getString("claude.promptCacheTtl", null)?.takeIf { it in CLAUDE_CACHE_TTLS },
            claudeCommitAttribution = prefs.getBoolean("claude.commitAttribution", true),
            claudeBaseUrl = prefs.getString("claude.baseUrl", null),
            hasClaudeKey = prefs.contains(SECRET_PREFIX + "claude.apiKey"),
            hasClaudeOauthToken = prefs.contains(SECRET_PREFIX + "claude.oauthToken"),
            hasAntigravityKey = prefs.contains(SECRET_PREFIX + "antigravity.apiKey"),
            antigravityPermissionMode = prefs.getString("antigravity.permissionMode", "default")?.takeIf { it in ANTIGRAVITY_PERMISSION_MODES } ?: "default",
            antigravityEffort = prefs.getString("antigravity.effort", null)?.takeIf { it in ANTIGRAVITY_EFFORTS },
            antigravitySandbox = prefs.getBoolean("antigravity.sandbox", false),
            antigravityAgent = prefs.getString("antigravity.agent", null)?.takeIf { it.isNotBlank() },
            antigravityTransport = prefs.getString("antigravity.transport", "acp")?.takeIf { it in setOf("acp", "stream-json") } ?: "acp",
            artifactsModeEnabled = prefs.getBoolean("chat.artifactsMode", true),
            providers = providers,
            routingGroups = routingGroups,
            approvalPolicy = prefs.getString("codex.approvalPolicy", "on-request")?.takeIf { it in CODEX_APPROVAL_POLICIES } ?: "on-request",
            approvalsReviewer = prefs.getString("codex.approvalsReviewer", "user")?.takeIf { it in CODEX_APPROVAL_REVIEWERS } ?: "user",
            claudePermissionMode = prefs.getString("claude.permissionMode", "default")!!.takeIf { it in CLAUDE_PERMISSION_MODES } ?: "default",
            reasoningSummary = prefs.getString("chat.reasoningSummary", "auto")?.takeIf { it in REASONING_SUMMARIES } ?: "auto",
            defaultCwd = defaultCwd,
            projects = loadProjects(defaultCwd),
            collapsedProjects = prefs.getStringSet("projects.collapsed", emptySet())!!.toSet(),
            trustedProjects = if (prefs.contains("claude.trusted")) {
                prefs.getStringSet("claude.trusted", emptySet())!!.toSet()
            } else {
                // 内置空默认工作区出厂即默认信任，避免用户首次新建 Claude Code 会话就被勾选框卡住；外部目录仍需显式确认，并可在设置中随时移除。
                setOf(DEFAULT_WORKSPACE)
            },
            delegationEnabled = prefs.getBoolean("delegation.enabled", true),
            autoScroll = prefs.getBoolean("chat.autoScroll", true),
            expandThinking = prefs.getBoolean("chat.expandThinking", false),
            showTokenUsage = prefs.getBoolean("chat.showTokenUsage", true),
            enterToSend = prefs.getBoolean("chat.enterToSend", false),
            plainTextEditor = prefs.getBoolean("general.plainTextEditor", false),
            editorSyntaxHighlight = prefs.getBoolean("general.editorSyntaxHighlight", true),
            editorAutoPreview = prefs.getBoolean("general.editorAutoPreview", true),
            editorFormatToolbar = prefs.getBoolean("general.editorFormatToolbar", false),
            editorAutoPair = prefs.getBoolean("general.editorAutoPair", false),
            editorMonospace = prefs.getBoolean("general.editorMonospace", false),
            editorPlainFallbackThreshold = prefs.getInt("general.editorPlainFallbackThreshold", 8000).takeIf { it in EDITOR_FALLBACK_THRESHOLDS } ?: 8000,
            showComposerWordCount = prefs.getBoolean("general.showComposerWordCount", false),
            userMessageRenderMode = prefs.getString("general.userMessageRenderMode", USER_RENDER_FOLLOW_EDITOR)?.takeIf { it in USER_RENDER_MODES } ?: USER_RENDER_FOLLOW_EDITOR,
            assistantRichRender = prefs.getBoolean("general.assistantRichRender", true),
            inlineVisualArtifacts = prefs.getBoolean("general.inlineVisualArtifacts", true),
            renderMathFormulas = prefs.getBoolean("general.renderMathFormulas", true),
            keepUnsentDrafts = prefs.getBoolean("general.keepUnsentDrafts", true),
            fileEditorWordWrap = prefs.getBoolean("general.fileEditorWordWrap", true),
            editorTabSize = prefs.getInt("general.editorTabSize", 2).takeIf { it in EDITOR_TAB_SIZES } ?: 2,
            runtimeMode = prefs.getString("runtime.mode", null)?.takeIf { it == RUNTIME_MODE_ROOT || it == RUNTIME_MODE_PROOT },
            autoStartRuntime = prefs.getBoolean("runtime.autoStart", true),
            keepRuntimeOnExit = prefs.getBoolean("runtime.keepOnExit", true),
            vmMemoryMiB = prefs.getInt("vm.memoryMiB", 4096).takeIf { it in setOf(2048, 3072, 4096, 6144) } ?: 4096,
            theme = ThemeConfig.fromJson(prefs.getString("ui.theme", null), prefs.getString("ui.themeMode", null)),
            customThemes = loadCustomThemes(),
            customAgents = customAgents,
            agentProviders = loadStringMap("agents.providers").filterValues { it in ids },
            agentModels = loadStringMap("agents.models"),
            maxParallelWorkers = prefs.getInt("superagent.maxWorkers", 2).coerceIn(1, 3),
            roleRoutes = loadRoleRoutes(ids),
            downloadNetwork = DownloadNetworkConfig.fromJson(prefs.getString("runtime.downloadNetwork", null)),
            selfBuild = SelfBuildConfig.fromJson(prefs.getString(SELF_BUILD_CONFIG_KEY, null)),
            mobileUse = MobileUseConfig.fromJson(prefs.getString(MOBILE_USE_CONFIG_KEY, null)),
        )
    }

    fun setDownloadNetwork(config: DownloadNetworkConfig) {
        val validated = config.validated()
        edit { putString("runtime.downloadNetwork", validated.toJson().toString()) }
    }

    fun setSelfBuildConfig(config: SelfBuildConfig) {
        val validated = config.validated()
        edit { putString(SELF_BUILD_CONFIG_KEY, validated.toJson().toString()) }
    }

    fun setMobileUseConfig(config: MobileUseConfig) {
        val validated = config.validated()
        edit { putString(MOBILE_USE_CONFIG_KEY, validated.toJson().toString()) }
    }

    private fun loadRoleRoutes(validProviderIds: Set<String>): Map<String, RoleRouteConfig> {
        val defaults = RoleRouteConfig.defaults().toMutableMap()
        val raw = prefs.getString("superagent.roleRoutes", null) ?: return defaults
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { role ->
                val item = obj.optJSONObject(role) ?: return@forEach
                val parsed = RoleRouteConfig.fromJson(item, defaults[role]?.engineId ?: "codex")
                if (AgentCatalog.find(parsed.engineId) == null) return@forEach
                defaults[role] = parsed.copy(providerId = parsed.providerId?.takeIf { it in validProviderIds })
            }
        } catch (_: Exception) {}
        return defaults
    }

    fun setMaxParallelWorkers(count: Int) = edit { putInt("superagent.maxWorkers", count.coerceIn(1, 3)) }

    fun setRoleRoute(role: String, config: RoleRouteConfig) {
        val next = stateFlow.value.roleRoutes.toMutableMap()
        next[role] = config
        val obj = JSONObject()
        next.forEach { (k, v) -> obj.put(k, v.toJson()) }
        edit { putString("superagent.roleRoutes", obj.toString()) }
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        stateFlow.value = load()
    }

    fun setCodexProvider(id: String?) {
        val existing = stateFlow.value.codexProviderId
        edit {
            putOrRemove("codex.providerId", id)
            if (existing != id) { remove("codex.model"); remove("codex.effort"); remove("codex.serviceTier") }
        }
    }
    fun setCodexModel(value: String?) = edit {
        putOrRemove("codex.model", value)
        if (stateFlow.value.codexModel != value) { remove("codex.effort"); remove("codex.serviceTier") }
    }
    fun setCodexEffort(value: String?) = edit { putOrRemove("codex.effort", value?.takeIf { it.isNotBlank() }) }
    fun setCodexServiceTier(value: String?) = edit { putOrRemove("codex.serviceTier", value?.takeIf { it.isNotBlank() }) }
    fun setCodexCollaborationMode(value: String) { require(value in CODEX_COLLABORATION_MODES); edit { putString("codex.collaborationMode", value) } }
    fun setCodexWebSearch(value: String?) { require(value == null || value in CODEX_WEB_SEARCH_MODES); edit { putOrRemove("codex.webSearch", value) } }
    fun setClaudeProvider(id: String?) {
        val existing = stateFlow.value.claudeProviderId
        edit {
            putOrRemove("claude.providerId", id)
            if (existing != id) {
                remove("claude.model"); remove("claude.effort"); remove("claude.fastMode")
                if (stateFlow.value.claudePermissionMode == "auto") remove("claude.permissionMode")
            }
        }
    }
    fun setClaudeModel(value: String?) = edit {
        putOrRemove("claude.model", value)
        if (stateFlow.value.claudeModel != value) {
            remove("claude.effort"); remove("claude.fastMode")
            if (stateFlow.value.claudePermissionMode == "auto") remove("claude.permissionMode")
        }
    }
    fun setClaudeEffort(value: String?) = edit { putOrRemove("claude.effort", value?.takeIf { it.isNotBlank() }) }
    fun setClaudeFastMode(value: Boolean?) = edit { if (value == null) remove("claude.fastMode") else putBoolean("claude.fastMode", value) }
    fun setClaudeThinkingDisplay(value: String) { require(value in CLAUDE_THINKING_DISPLAYS); edit { putString("claude.thinkingDisplay", value) } }
    fun setClaudePromptCacheTtl(value: String?) { require(value == null || value in CLAUDE_CACHE_TTLS); edit { putOrRemove("claude.promptCacheTtl", value) } }
    fun setClaudeCommitAttribution(value: Boolean) = edit { putBoolean("claude.commitAttribution", value) }
    fun setAntigravityPermissionMode(value: String) { require(value in ANTIGRAVITY_PERMISSION_MODES); edit { putString("antigravity.permissionMode", value) } }
    fun setAntigravityEffort(value: String?) { require(value == null || value in ANTIGRAVITY_EFFORTS); edit { putOrRemove("antigravity.effort", value) } }
    fun setAntigravitySandbox(value: Boolean) = edit { putBoolean("antigravity.sandbox", value) }
    fun setAntigravityAgent(value: String?) = edit { putOrRemove("antigravity.agent", value?.trim()?.takeIf { it.isNotEmpty() }) }
    fun setAntigravityTransport(value: String) { require(value in setOf("acp", "stream-json")); edit { putString("antigravity.transport", value) } }
    fun setApprovalPolicy(value: String) { require(value in CODEX_APPROVAL_POLICIES); edit { putString("codex.approvalPolicy", value) } }
    fun setApprovalsReviewer(value: String) { require(value in CODEX_APPROVAL_REVIEWERS); edit { putString("codex.approvalsReviewer", value) } }
    fun setClaudePermissionMode(value: String) { require(value in CLAUDE_PERMISSION_MODES); edit { putString("claude.permissionMode", value) } }
    fun setReasoningSummary(value: String) { require(value in REASONING_SUMMARIES); edit { putString("chat.reasoningSummary", value) } }

    private fun conversationOptionsKey(key: ConversationKey) = "conversation.options.${stateFlow.value.effectiveRuntimeMode}.${key.engine.id}.${key.id}"

    internal fun conversationOptions(key: ConversationKey): ConversationOptions? {
        val raw = prefs.getString(conversationOptionsKey(key), null) ?: return null
        return try { ConversationOptions.fromJson(JSONObject(raw)) }
        catch (error: Exception) { throw IllegalStateException("无法读取此会话的模型设置", error) }
    }

    internal fun saveConversationOptions(key: ConversationKey, options: ConversationOptions) {
        prefs.edit().putString(conversationOptionsKey(key), options.toJson().toString()).apply()
    }

    internal fun removeConversationOptions(key: ConversationKey) {
        prefs.edit().remove(conversationOptionsKey(key)).apply()
    }
    fun setDefaultCwd(value: String) {
        val normalized = value.trim().trimEnd('/').ifEmpty { "/" }
        require(normalized.startsWith("/")) { "工作目录必须是绝对路径" }
        val nextProjects = (stateFlow.value.projects + normalized).distinct()
        edit {
            putString("defaultCwd", normalized)
            putString("projects.list", JSONArray(nextProjects).toString())
        }
    }

    fun addProject(path: String) {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        require(normalized.startsWith("/")) { "项目目录必须是绝对路径" }
        val next = (stateFlow.value.projects + normalized).distinct()
        val uncollapsed = stateFlow.value.collapsedProjects - normalized
        edit {
            putString("projects.list", JSONArray(next).toString())
            putStringSet("projects.collapsed", uncollapsed)
        }
    }

    fun removeProject(path: String) {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        val remaining = stateFlow.value.projects.filterNot { it == normalized }
        val nextDefault = if (stateFlow.value.defaultCwd == normalized) remaining.firstOrNull() ?: DEFAULT_WORKSPACE else stateFlow.value.defaultCwd
        val nextList = (remaining + nextDefault).distinct()
        edit {
            putString("defaultCwd", nextDefault)
            putString("projects.list", JSONArray(nextList).toString())
            putStringSet("projects.collapsed", stateFlow.value.collapsedProjects - normalized)
        }
    }

    fun toggleProjectCollapsed(path: String) {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        val current = stateFlow.value.collapsedProjects
        val next = if (normalized in current) current - normalized else current + normalized
        // 局部增量更新避免触发全量 load() 解析 JSON，消除点击项目折叠首帧卡顿。
        prefs.edit().putStringSet("projects.collapsed", HashSet(next)).apply()
        stateFlow.value = stateFlow.value.copy(collapsedProjects = next)
    }
    fun setDelegationEnabled(value: Boolean) = edit { putBoolean("delegation.enabled", value) }
    fun setAutoScroll(value: Boolean) = edit { putBoolean("chat.autoScroll", value) }
    fun setExpandThinking(value: Boolean) = edit { putBoolean("chat.expandThinking", value) }
    fun setShowTokenUsage(value: Boolean) = edit { putBoolean("chat.showTokenUsage", value) }
    fun setEnterToSend(value: Boolean) = edit { putBoolean("chat.enterToSend", value) }
    fun setPlainTextEditor(value: Boolean) = edit { putBoolean("general.plainTextEditor", value) }
    fun setEditorSyntaxHighlight(value: Boolean) = edit { putBoolean("general.editorSyntaxHighlight", value) }
    fun setEditorAutoPreview(value: Boolean) = edit { putBoolean("general.editorAutoPreview", value) }
    fun setEditorFormatToolbar(value: Boolean) = edit { putBoolean("general.editorFormatToolbar", value) }
    fun setEditorAutoPair(value: Boolean) = edit { putBoolean("general.editorAutoPair", value) }
    fun setEditorMonospace(value: Boolean) = edit { putBoolean("general.editorMonospace", value) }
    fun setEditorPlainFallbackThreshold(value: Int) {
        require(value in EDITOR_FALLBACK_THRESHOLDS) { "不支持的保护阈值：$value" }
        edit { putInt("general.editorPlainFallbackThreshold", value) }
    }
    fun setShowComposerWordCount(value: Boolean) = edit { putBoolean("general.showComposerWordCount", value) }
    fun setUserMessageRenderMode(mode: String) {
        require(mode in USER_RENDER_MODES) { "不支持的用户消息渲染模式：$mode" }
        edit { putString("general.userMessageRenderMode", mode) }
    }
    fun setAssistantRichRender(value: Boolean) = edit { putBoolean("general.assistantRichRender", value) }
    fun setInlineVisualArtifacts(value: Boolean) = edit { putBoolean("general.inlineVisualArtifacts", value) }
    fun setRenderMathFormulas(value: Boolean) = edit { putBoolean("general.renderMathFormulas", value) }
    fun setKeepUnsentDrafts(value: Boolean) = edit {
        putBoolean("general.keepUnsentDrafts", value)
        if (!value) {
            prefs.all.keys.filter { it.startsWith(CHAT_DRAFT_PREFIX) }.forEach(::remove)
        }
    }
    fun setFileEditorWordWrap(value: Boolean) = edit { putBoolean("general.fileEditorWordWrap", value) }
    fun setEditorTabSize(size: Int) {
        require(size in EDITOR_TAB_SIZES) { "不支持的缩进宽度：$size" }
        edit { putInt("general.editorTabSize", size) }
    }

    fun resetGeneralSettings() = edit {
        listOf(
            "general.plainTextEditor",
            "general.editorSyntaxHighlight",
            "general.editorAutoPreview",
            "general.editorFormatToolbar",
            "general.editorAutoPair",
            "general.editorMonospace",
            "general.editorPlainFallbackThreshold",
            "general.showComposerWordCount",
            "general.userMessageRenderMode",
            "general.assistantRichRender",
            "general.inlineVisualArtifacts",
            "general.renderMathFormulas",
            "general.keepUnsentDrafts",
            "general.fileEditorWordWrap",
            "general.editorTabSize",
            "chat.autoScroll",
            "chat.expandThinking",
            "chat.showTokenUsage",
            "chat.enterToSend",
        ).forEach(::remove)
    }

    fun conversationDraft(route: String): String {
        if (!stateFlow.value.keepUnsentDrafts || route.isBlank()) return ""
        return prefs.getString(CHAT_DRAFT_PREFIX + route, "") ?: ""
    }

    fun setConversationDraft(route: String, text: String) {
        if (route.isBlank()) return
        val editor = prefs.edit()
        if (!stateFlow.value.keepUnsentDrafts || text.isBlank()) {
            editor.remove(CHAT_DRAFT_PREFIX + route)
        } else {
            editor.putString(CHAT_DRAFT_PREFIX + route, text.take(65_536))
        }
        editor.apply()
    }

    fun setArtifactsModeEnabled(value: Boolean) = edit { putBoolean("chat.artifactsMode", value) }
    fun setRuntimeMode(mode: String) {
        require(mode == RUNTIME_MODE_ROOT || mode == RUNTIME_MODE_PROOT) { "未知运行模式：$mode" }
        edit { putString("runtime.mode", mode) }
    }
    fun setAutoStartRuntime(value: Boolean) = edit { putBoolean("runtime.autoStart", value) }
    fun setKeepRuntimeOnExit(value: Boolean) = edit { putBoolean("runtime.keepOnExit", value) }
    fun setVmMemoryMiB(mib: Int) {
        require(mib in setOf(2048, 3072, 4096, 6144)) { "不支持的内存档位：$mib" }
        edit { putInt("vm.memoryMiB", mib) }
    }
    fun setThemeMode(value: String) { require(value in ThemeConfig.MODES); updateTheme { it.copy(mode = value) } }

    /** 原子更新主题配置（校验后整体持久化为 JSON）。 */
    fun updateTheme(transform: (ThemeConfig) -> ThemeConfig) {
        val next = transform(stateFlow.value.theme).sanitized()
        if (next == stateFlow.value.theme) return
        edit {
            putString("ui.theme", next.toJson().toString())
            remove("ui.themeMode")
        }
    }

    fun resetTheme(keepStyle: Boolean) {
        val current = stateFlow.value.theme
        updateTheme { ThemeConfig(style = if (keepStyle) current.style else ThemeConfig.STYLE_GALIAIS, mode = current.mode) }
    }

    /** 保存当前混搭方案为自定义主题槽位（最多保留 20 套）。 */
    fun saveCustomTheme(name: String, config: ThemeConfig): CustomThemeSlot {
        val trimmed = name.trim().ifBlank { "我的混搭 #${stateFlow.value.customThemes.size + 1}" }.take(24)
        val slot = CustomThemeSlot(
            id = "custom_${System.currentTimeMillis()}",
            name = trimmed,
            config = config.sanitized(),
        )
        val next = (listOf(slot) + stateFlow.value.customThemes).take(20)
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        edit { putString("ui.customThemes", arr.toString()) }
        return slot
    }

    fun deleteCustomTheme(id: String) {
        val next = stateFlow.value.customThemes.filterNot { it.id == id }
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        edit { putString("ui.customThemes", arr.toString()) }
    }
    fun trust(project: String) = edit { putStringSet("claude.trusted", stateFlow.value.trustedProjects + project) }
    fun untrust(project: String) = edit { putStringSet("claude.trusted", stateFlow.value.trustedProjects - project) }

    fun saveProvider(provider: CustomProvider, apiKey: String? = null, clearKey: Boolean = false, apiKeysPool: List<String>? = null) {
        require(provider.name.isNotBlank() && provider.name.length <= 80) { "请填写服务商名称（不超过 80 字）" }
        require(Regex("^[a-z0-9_-]{1,64}$", RegexOption.IGNORE_CASE).matches(provider.id)) { "服务商标识无效" }
        val normalizedUrl = validateProviderBaseUrl(provider.baseUrl)
        val trimmedKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmedKey == null || (trimmedKey.length <= 4096 && !trimmedKey.contains('\n') && !trimmedKey.contains('\r'))) { "API Key 格式无效" }
        val cleanedPool = apiKeysPool?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.also { list ->
            require(list.size <= 32 && list.all { it.length <= 4096 && !it.contains('\n') && !it.contains('\r') }) { "密钥池格式无效" }
        }
        val mergedSinglePool = if (!clearKey && cleanedPool == null && trimmedKey != null) {
            (listOf(trimmedKey) + providerApiKeys(provider.id).drop(1)).distinct()
        } else null
        val effectiveCount = when {
            clearKey -> 0
            cleanedPool != null -> cleanedPool.size
            mergedSinglePool != null -> mergedSinglePool.size
            else -> providerApiKeys(provider.id).size
        }
        val cleaned = provider.copy(
            name = provider.name.trim(),
            baseUrl = normalizedUrl,
            chatUrl = validateOptionalEndpointUrl(provider.chatUrl),
            responsesUrl = validateOptionalEndpointUrl(provider.responsesUrl),
            anthropicUrl = validateOptionalEndpointUrl(provider.anthropicUrl),
            geminiUrl = validateOptionalEndpointUrl(provider.geminiUrl),
            models = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            keyPoolCount = effectiveCount,
        )
        val existingList = stateFlow.value.providers
        val existingIdx = existingList.indexOfFirst { it.id == cleaned.id }
        val next = if (existingIdx >= 0) existingList.toMutableList().apply { this[existingIdx] = cleaned } else existingList + cleaned
        val array = JSONArray().apply { next.forEach { put(it.toMetadataJson()) } }
        providerKeyCountCache[cleaned.id] = effectiveCount
        edit {
            putString("providers.list", array.toString())
            if (clearKey) {
                remove(PROVIDER_SECRET_PREFIX + cleaned.id)
                remove(PROVIDER_KEYPOOL_PREFIX + cleaned.id)
            } else if (cleanedPool != null) {
                if (cleanedPool.isEmpty()) {
                    remove(PROVIDER_SECRET_PREFIX + cleaned.id)
                    remove(PROVIDER_KEYPOOL_PREFIX + cleaned.id)
                } else {
                    putString(PROVIDER_SECRET_PREFIX + cleaned.id, encrypt(cleanedPool.first()))
                    putString(PROVIDER_KEYPOOL_PREFIX + cleaned.id, encrypt(JSONArray(cleanedPool).toString()))
                }
            } else if (mergedSinglePool != null) {
                putString(PROVIDER_SECRET_PREFIX + cleaned.id, encrypt(mergedSinglePool.first()))
                putString(PROVIDER_KEYPOOL_PREFIX + cleaned.id, encrypt(JSONArray(mergedSinglePool).toString()))
            }
        }
    }

    fun moveProvider(id: String, delta: Int) {
        val list = stateFlow.value.providers.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val target = idx + delta
        if (idx < 0 || target !in list.indices) return
        val item = list.removeAt(idx)
        list.add(target, item)
        val array = JSONArray().apply { list.forEach { put(it.toMetadataJson()) } }
        edit { putString("providers.list", array.toString()) }
    }

    fun deleteProvider(id: String) {
        providerKeyCountCache.remove(id)
        val next = stateFlow.value.providers.filterNot { it.id == id }
        val array = JSONArray().apply { next.forEach { put(it.toMetadataJson()) } }
        val prunedGroups = stateFlow.value.routingGroups.map { g -> g.copy(targets = g.targets.filterNot { it.providerId == id }) }
        val removedGroupVirtualIds = prunedGroups.filter { it.targets.isEmpty() }.map { it.virtualProviderId }.toSet()
        val nextGroups = prunedGroups.filter { it.targets.isNotEmpty() }
        val removedRefs = removedGroupVirtualIds + id
        val nextAgentProviders = stateFlow.value.agentProviders.filterValues { it !in removedRefs }
        edit {
            putString("providers.list", array.toString())
            putString("providers.routingGroups", JSONArray().apply { nextGroups.forEach { put(it.toJson()) } }.toString())
            remove(PROVIDER_SECRET_PREFIX + id)
            remove(PROVIDER_KEYPOOL_PREFIX + id)
            if (stateFlow.value.codexProviderId in removedRefs) { remove("codex.providerId"); remove("codex.model") }
            if (stateFlow.value.claudeProviderId in removedRefs) { remove("claude.providerId"); remove("claude.model") }
            putString("agents.providers", JSONObject().apply { nextAgentProviders.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun saveRoutingGroup(group: RoutingGroup) {
        require(group.name.isNotBlank() && group.name.length <= 60) { "请填写路由组名称" }
        require(group.targets.isNotEmpty()) { "请至少添加 1 个路由目标" }
        val normalized = group.copy(name = group.name.trim())
        val existingList = stateFlow.value.routingGroups
        val idx = existingList.indexOfFirst { it.id == normalized.id }
        val next = if (idx >= 0) existingList.toMutableList().apply { this[idx] = normalized } else existingList + normalized
        edit { putString("providers.routingGroups", JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()) }
    }

    fun deleteRoutingGroup(id: String) {
        val virtualId = CustomProvider.GROUP_PREFIX + id
        val next = stateFlow.value.routingGroups.filterNot { it.id == id }
        val nextAgentProviders = stateFlow.value.agentProviders.filterValues { it != virtualId }
        edit {
            putString("providers.routingGroups", JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
            if (stateFlow.value.codexProviderId == virtualId) { remove("codex.providerId"); remove("codex.model") }
            if (stateFlow.value.claudeProviderId == virtualId) { remove("claude.providerId"); remove("claude.model") }
            putString("agents.providers", JSONObject().apply { nextAgentProviders.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun providerApiKeys(id: String): List<String> {
        val rawPool = prefs.getString(PROVIDER_KEYPOOL_PREFIX + id, null)?.let(::decrypt)
        if (!rawPool.isNullOrBlank()) {
            val parsed = runCatching { JSONArray(rawPool).strings().map { it.trim() }.filter { it.isNotEmpty() }.distinct() }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return listOfNotNull(providerApiKey(id)?.takeIf { it.isNotBlank() })
    }

    fun saveCustomAgent(manifest: AgentManifest) {
        require(manifest.id !in setOf("codex", "claude", "agent-hub")) { "不能覆盖系统内置 Agent 标识" }
        val cleaned = manifest.copy(builtIn = false, preinstalled = false)
        val next = stateFlow.value.customAgents.filterNot { it.id == cleaned.id } + cleaned
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        edit { putString("agents.custom", arr.toString()) }
    }

    fun deleteCustomAgent(id: String) {
        val next = stateFlow.value.customAgents.filterNot { it.id == id }
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        val nextProv = stateFlow.value.agentProviders - id
        val nextMod = stateFlow.value.agentModels - id
        edit {
            putString("agents.custom", arr.toString())
            putString("agents.providers", JSONObject().apply { nextProv.forEach { (k, v) -> put(k, v) } }.toString())
            putString("agents.models", JSONObject().apply { nextMod.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun setAgentProvider(agentId: String, providerId: String?) {
        if (agentId == "codex") { setCodexProvider(providerId); return }
        if (agentId == "claude") { setClaudeProvider(providerId); return }
        val existing = stateFlow.value.agentProviders[agentId]
        val nextProv = stateFlow.value.agentProviders.toMutableMap().apply {
            if (providerId == null) remove(agentId) else put(agentId, providerId)
        }
        val nextMod = if (existing != providerId) stateFlow.value.agentModels - agentId else stateFlow.value.agentModels
        edit {
            putString("agents.providers", JSONObject().apply { nextProv.forEach { (k, v) -> put(k, v) } }.toString())
            putString("agents.models", JSONObject().apply { nextMod.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun setAgentModel(agentId: String, model: String?) {
        if (agentId == "codex") { setCodexModel(model); return }
        if (agentId == "claude") { setClaudeModel(model); return }
        val nextMod = stateFlow.value.agentModels.toMutableMap().apply {
            if (model.isNullOrBlank()) remove(agentId) else put(agentId, model.trim())
        }
        edit {
            putString("agents.models", JSONObject().apply { nextMod.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    fun providerApiKey(id: String): String? = prefs.getString(PROVIDER_SECRET_PREFIX + id, null)?.let(::decrypt)

    /** 记住某个 Claude 会话使用的服务商，重启恢复时沿用同一服务商。 */
    fun claudeSessionProvider(sessionId: String): String? =
        prefs.getString(SESSION_PROVIDER_PREFIX + sessionId, null)?.takeIf { it.isNotEmpty() }

    fun setClaudeSessionProvider(sessionId: String, providerId: String?) {
        prefs.edit().apply { putOrRemove(SESSION_PROVIDER_PREFIX + sessionId, providerId) }.apply()
    }

    fun setClaudeBaseUrl(value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
        if (normalized != null) {
            val uri = java.net.URI(normalized)
            require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null) { "地址必须是 https，且不能包含账号、查询参数或片段" }
        }
        edit { putOrRemove("claude.baseUrl", normalized) }
    }

    fun claudeApiKey(): String? = prefs.getString(SECRET_PREFIX + "claude.apiKey", null)?.let(::decrypt)
    fun setClaudeApiKey(value: String?) {
        val key = value?.trim()?.takeIf { it.isNotEmpty() }
        require(key == null || (key.length <= 4096 && !key.contains('\n') && !key.contains('\r'))) { "API Key 格式无效" }
        edit { putOrRemove(SECRET_PREFIX + "claude.apiKey", key?.let(::encrypt)) }
    }

    fun claudeOauthToken(): String? = prefs.getString(SECRET_PREFIX + "claude.oauthToken", null)?.let(::decrypt)
    fun setClaudeOauthToken(value: String?) {
        val token = value?.trim()?.takeIf { it.isNotEmpty() }
        require(token == null || (token.length <= 4096 && !token.contains('\n') && !token.contains('\r'))) { "OAuth Token 格式无效" }
        edit { putOrRemove(SECRET_PREFIX + "claude.oauthToken", token?.let(::encrypt)) }
    }

    fun antigravityApiKey(): String? = prefs.getString(SECRET_PREFIX + "antigravity.apiKey", null)?.let(::decrypt)
    fun setAntigravityApiKey(value: String?) {
        val key = value?.trim()?.takeIf { it.isNotEmpty() }
        require(key == null || (key.length <= 4096 && !key.contains('\n') && !key.contains('\r'))) { "API Key 格式无效" }
        edit { putOrRemove(SECRET_PREFIX + "antigravity.apiKey", key?.let(::encrypt)) }
    }

    /** 扩展中心注册表元数据与 Keystore 加密密钥存取。 */
    fun extensionRegistryJson(): String? = prefs.getString(EXT_REGISTRY_KEY, null)
    fun setExtensionRegistryJson(raw: String) {
        prefs.edit().putString(EXT_REGISTRY_KEY, raw).apply()
    }

    /** 完整环境中心与 SSH 主机簿/隧道持久化。 */
    fun envHubConfigJson(): String? = prefs.getString(ENV_HUB_KEY, null)
    fun setEnvHubConfigJson(raw: String) { prefs.edit().putString(ENV_HUB_KEY, raw).apply() }

    fun sshHostsJson(): String? = prefs.getString(SSH_HOSTS_KEY, null)
    fun setSshHostsJson(raw: String) { prefs.edit().putString(SSH_HOSTS_KEY, raw).apply() }

    fun sshTunnelsJson(): String? = prefs.getString(SSH_TUNNELS_KEY, null)
    fun setSshTunnelsJson(raw: String) { prefs.edit().putString(SSH_TUNNELS_KEY, raw).apply() }

    fun exportBucketSecretsJson(bucket: String): JSONObject {
        val out = JSONObject()
        val prefix = "$EXT_SECRET_PREFIX$bucket."
        for ((prefKey, rawVal) in prefs.all) {
            if (!prefKey.startsWith(prefix) || rawVal !is String) continue
            val key = prefKey.removePrefix(prefix)
            val plain = decrypt(rawVal) ?: continue
            out.put(key, plain)
        }
        return out
    }

    fun extensionSecret(bucket: String, secretKey: String): String? =
        prefs.getString("$EXT_SECRET_PREFIX$bucket.$secretKey", null)?.let(::decrypt)

    fun hasExtensionSecret(bucket: String, secretKey: String): Boolean =
        prefs.contains("$EXT_SECRET_PREFIX$bucket.$secretKey")

    fun setExtensionSecret(bucket: String, secretKey: String, value: String?) {
        val preserveWhitespace = bucket in setOf("envhub", "sshhost", "sshkey")
        val clean = (if (preserveWhitespace) value else value?.trim())?.takeIf { it.isNotEmpty() }
        require(clean == null || (clean.length <= 8192 && '\u0000' !in clean &&
            (bucket == "envhub" || (!clean.contains('\n') && !clean.contains('\r'))))) { "敏感值格式无效或超过 8192 字符" }
        prefs.edit().apply { putOrRemove("$EXT_SECRET_PREFIX$bucket.$secretKey", clean?.let(::encrypt)) }.apply()
    }

    fun removeExtensionBucketSecrets(bucket: String) {
        val prefix = "$EXT_SECRET_PREFIX$bucket."
        val toRemove = prefs.all.keys.filter { it.startsWith(prefix) }
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach(::remove) }.apply()
    }

    fun exportExtensionSecretsJson(): JSONObject {
        val root = JSONObject()
        val reservedNonExtensionBuckets = setOf("envhub", "sshhost", "sshkey")
        for ((prefKey, rawVal) in prefs.all) {
            if (!prefKey.startsWith(EXT_SECRET_PREFIX) || rawVal !is String) continue
            val rest = prefKey.removePrefix(EXT_SECRET_PREFIX)
            val dot = rest.indexOf('.')
            if (dot <= 0) continue
            val bucket = rest.substring(0, dot)
            if (bucket in reservedNonExtensionBuckets) continue
            val key = rest.substring(dot + 1)
            val plain = decrypt(rawVal) ?: continue
            val obj = root.optJSONObject(bucket) ?: JSONObject().also { root.put(bucket, it) }
            obj.put(key, plain)
        }
        return root
    }

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
        String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private companion object {
        const val KEY_ALIAS = "galiais-settings-secrets"
        const val SECRET_PREFIX = "secret."
        const val PROVIDER_SECRET_PREFIX = "secret.provider."
        const val PROVIDER_KEYPOOL_PREFIX = "secret.providerPool."
        const val SESSION_PROVIDER_PREFIX = "claude.sessionProvider."
        const val EXT_REGISTRY_KEY = "extensions.registry.v1"
        const val EXT_SECRET_PREFIX = "secret.ext."
        const val ENV_HUB_KEY = "env.hub.v1"
        const val SSH_HOSTS_KEY = "ssh.hosts.v1"
        const val SSH_TUNNELS_KEY = "ssh.tunnels.v1"
        const val CHAT_DRAFT_PREFIX = "chat.draft."
        const val SELF_BUILD_CONFIG_KEY = "selfbuild.config.v1"
        const val MOBILE_USE_CONFIG_KEY = "mobileuse.config.v1"
    }
}
