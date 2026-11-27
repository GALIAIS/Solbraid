package com.galiais.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.SshHostConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)
private val tabs: List<Tab>
    get() = listOf(
        Tab("会话", GaliaisIcons.Comments),
        Tab("SSH", GaliaisIcons.Terminal),
        Tab("文件", GaliaisIcons.Folders),
        Tab("设置", GaliaisIcons.Gear),
    )

/** 从文件页或欢迎卡片跳转到新建会话时携带的工作目录、可选引用文件与默认引擎。 */
data class PendingCreate(val cwd: String, val referenceFile: String? = null, val engine: EngineKind? = null)

/** 单窗口终端路由启动参数（避免 Android Dialog 独立窗口导致进终端闪白）。 */
private data class TerminalLaunchRequest(
    val cwd: String,
    val sshHost: SshHostConfig? = null,
    val initialSessionId: String? = null,
    val onClosed: (() -> Unit)? = null,
)

@Composable
fun GaliaisApp(app: GaliaisApplication) {
    val settings by app.settings.state.collectAsState()
    if (settings.runtimeMode == null) {
        ModeSelectionScreen(app)
        return
    }
    val navigation = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val tabStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(Unit) {
        app.conversations.messages.collectLatest { text ->
            snackbar.currentSnackbarData?.dismiss()
            val showJob = launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Indefinite) }
            val holdMs = if (text.contains("失败") || text.contains("异常") || text.contains("错误") || text.length > 28) 3800L else 2400L
            try {
                delay(holdMs)
            } finally {
                snackbar.currentSnackbarData?.dismiss()
                showJob.cancel()
            }
        }
    }
    fun safeNavigate(route: String) {
        navigation.navigate(route) { launchSingleTop = true }
    }
    fun safePopBack() {
        if (navigation.previousBackStackEntry != null) navigation.popBackStack()
    }
    val openConversation: (ConversationKey) -> Unit = { key ->
        // 触发跳转的同时立即开始拉取历史消息，与转场并行完成，最大限度降低进入聊天页时的空白等待与闪烁。
        if (app.runtime.snapshot.value.ready) app.conversations.open(key)
        safeNavigate("chat/${key.route}")
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pendingSharedUris by app.fileManager.pendingSharedUris.collectAsState()
    LaunchedEffect(pendingSharedUris) {
        if (pendingSharedUris.isNotEmpty()) {
            tab = 2
            if (navigation.currentBackStackEntry?.destination?.route != "home") {
                navigation.popBackStack("home", inclusive = false)
            }
        }
    }
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val navTabSlots = remember(hostPlugins) { app.extensions.activePluginUiSlots("nav.tab").take(2) }
    val extraNavLabels = remember(navTabSlots) { navTabSlots.map { it.label.take(4) } }
    LaunchedEffect(navTabSlots.size) {
        if (tab >= 4 + navTabSlots.size) tab = 0
    }
    var pendingCreate by remember { mutableStateOf<PendingCreate?>(null) }
    var pendingAddProject by remember { mutableStateOf(false) }
    var pendingBrowseDir by remember { mutableStateOf<String?>(null) }
    var terminalRequest by remember { mutableStateOf<TerminalLaunchRequest?>(null) }
    val openTerminal: (cwd: String, sshHost: SshHostConfig?, initialSessionId: String?, onClosed: (() -> Unit)?) -> Unit = { cwd, sshHost, sessionId, onClosed ->
        terminalRequest = TerminalLaunchRequest(cwd, sshHost, sessionId, onClosed)
        safeNavigate("terminal")
    }

    val navBackStackEntry by navigation.currentBackStackEntryAsState()
    val currentLogicalRoute = remember(navBackStackEntry?.destination?.route, tab) {
        val raw = navBackStackEntry?.destination?.route ?: "home"
        when {
            raw == "home" -> when (tab) {
                0 -> "home"
                1 -> "ssh"
                2 -> "files"
                3 -> "settings"
                else -> "nav.tab"
            }
            raw.startsWith("chat/") -> "chat"
            raw == "terminal" -> "terminal"
            raw.startsWith("plugin/") -> "plugin"
            raw.startsWith("settings/") -> "settings"
            else -> raw
        }
    }
    val openPluginFullPage: (String, String) -> Unit = { pid, sid ->
        val ep = java.net.URLEncoder.encode(pid, "UTF-8").replace("+", "%20")
        val es = java.net.URLEncoder.encode(sid, "UTF-8").replace("+", "%20")
        safeNavigate("plugin/$ep/$es")
    }

    val bottomChromeInset = when (currentLogicalRoute) {
        "home", "ssh", "files", "settings", "nav.tab" -> 78.dp
        "chat" -> 96.dp
        else -> 0.dp
    }

    CompositionLocalProvider(
        LocalRenderPrefs provides settings,
        LocalPluginNavigator provides openPluginFullPage,
        LocalBottomChromeInset provides bottomChromeInset,
    ) {
    Box(Modifier.fillMaxSize().background(G.canvas)) {
        NavHost(
            navController = navigation,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().background(G.canvas),
            enterTransition = { GaliaisMotion.navEnter },
            exitTransition = { GaliaisMotion.navExit },
            popEnterTransition = { GaliaisMotion.navPopEnter },
            popExitTransition = { GaliaisMotion.navPopExit },
        ) {
            composable("home") {
                BackHandler(enabled = tab != 0) { tab = 0 }
                val isFloatingNav = D.navBarStyle !in setOf("m3_expressive", "editorial_bar")
                val density = LocalDensity.current
                var floatingBarHeightDp by remember { mutableStateOf(82.dp) }
                if (isFloatingNav) {
                    // 悬浮式底栏：去除外围不透明裁切，让主内容区铺满整屏并穿透滚动至半透明悬浮底栏下方
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(G.canvas)
                            .galiaisCanvasBackground(),
                    ) {
                        CompositionLocalProvider(LocalFloatingNavBottomInset provides floatingBarHeightDp) {
                            val modifier = Modifier.fillMaxSize().statusBarsPadding()
                            Crossfade(targetState = tab, animationSpec = tween(140), label = "homeTabs") { currentTab ->
                                tabStateHolder.SaveableStateProvider(currentTab) {
                                    when (currentTab) {
                                        0 -> PluginPageTakeoverHost(app = app, replaceSlotName = "home.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                            ConversationListScreen(
                                                app = app,
                                                modifier = modifier,
                                                open = openConversation,
                                                pendingCreate = pendingCreate,
                                                onConsumePendingCreate = { pendingCreate = null },
                                                pendingAddProject = pendingAddProject,
                                                onConsumePendingAddProject = { pendingAddProject = false },
                                                onOpenDirectory = { dir -> pendingBrowseDir = dir; tab = 2 },
                                            )
                                        }
                                        1 -> PluginPageTakeoverHost(app = app, replaceSlotName = "ssh.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                            SshScreen(
                                                app = app,
                                                modifier = modifier,
                                                onOpenEnvironmentHub = { safeNavigate("settings/env") },
                                                onOpenTerminal = { dir, host -> openTerminal(dir, host, null, null) },
                                                onOpenTerminalSession = { sid -> openTerminal(settings.defaultCwd, null, sid, null) },
                                            )
                                        }
                                        2 -> PluginPageTakeoverHost(app = app, replaceSlotName = "files.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                            FilesScreen(
                                                app = app,
                                                modifier = modifier,
                                                requestedPath = pendingBrowseDir,
                                                onConsumeRequestedPath = { pendingBrowseDir = null },
                                                onOpenSsh = { tab = 1 },
                                                onOpenTerminal = { dir, onClosed -> openTerminal(dir, null, null, onClosed) },
                                                onUseDirectory = { cwd ->
                                                    app.settings.addProject(cwd)
                                                    app.settings.setDefaultCwd(cwd)
                                                    app.conversations.notify("已添加到项目并设为默认目录：$cwd")
                                                    tab = 0
                                                },
                                                onReferenceFile = { cwd, file ->
                                                    app.settings.addProject(cwd)
                                                    app.settings.setDefaultCwd(cwd)
                                                    pendingCreate = PendingCreate(cwd, file)
                                                    tab = 0
                                                },
                                            )
                                        }
                                        3 -> PluginPageTakeoverHost(app = app, replaceSlotName = "settings.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                            SettingsScreen(
                                                app = app,
                                                modifier = modifier,
                                                onNavigate = { route ->
                                                    if (route == "tab/ssh") tab = 1
                                                    else safeNavigate(route)
                                                },
                                            )
                                        }
                                        else -> {
                                            val dynSlot = navTabSlots.getOrNull(currentTab - 4)
                                            if (dynSlot != null) {
                                                PluginFullTabScreen(
                                                    app = app,
                                                    slot = dynSlot,
                                                    cwd = settings.defaultCwd,
                                                    modifier = modifier,
                                                )
                                            } else {
                                                SettingsScreen(
                                                    app = app,
                                                    modifier = modifier,
                                                    onNavigate = { route ->
                                                        if (route == "tab/ssh") tab = 1
                                                        else safeNavigate(route)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = floatingBarHeightDp + 6.dp),
                        ) {
                            GaliaisSnackbarHost(snackbar)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { size ->
                                    val measured = with(density) { size.height.toDp() }
                                    if (measured > 0.dp && measured != floatingBarHeightDp) floatingBarHeightDp = measured
                                },
                        ) {
                            GaliaisBottomBar(
                                selectedIndex = tab,
                                onSelect = { tab = it },
                                onQuickCreate = {
                                    pendingAddProject = true
                                    tab = 0
                                },
                                extraTabLabels = extraNavLabels,
                            )
                        }
                    }
                } else {
                    Scaffold(
                        containerColor = Color.Transparent,
                        modifier = Modifier.fillMaxSize().background(G.canvas).galiaisCanvasBackground(),
                        snackbarHost = { GaliaisSnackbarHost(snackbar) },
                        bottomBar = {
                            GaliaisBottomBar(
                                selectedIndex = tab,
                                onSelect = { tab = it },
                                onQuickCreate = {
                                    pendingAddProject = true
                                    tab = 0
                                },
                                extraTabLabels = extraNavLabels,
                            )
                        },
                    ) { padding ->
                        val modifier = Modifier.padding(padding)
                        Crossfade(targetState = tab, animationSpec = tween(140), label = "homeTabs") { currentTab ->
                            tabStateHolder.SaveableStateProvider(currentTab) {
                                when (currentTab) {
                                    0 -> PluginPageTakeoverHost(app = app, replaceSlotName = "home.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                        ConversationListScreen(
                                            app = app,
                                            modifier = modifier,
                                            open = openConversation,
                                            pendingCreate = pendingCreate,
                                            onConsumePendingCreate = { pendingCreate = null },
                                            pendingAddProject = pendingAddProject,
                                            onConsumePendingAddProject = { pendingAddProject = false },
                                            onOpenDirectory = { dir -> pendingBrowseDir = dir; tab = 2 },
                                        )
                                    }
                                    1 -> PluginPageTakeoverHost(app = app, replaceSlotName = "ssh.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                        SshScreen(
                                            app = app,
                                            modifier = modifier,
                                            onOpenEnvironmentHub = { safeNavigate("settings/env") },
                                            onOpenTerminal = { dir, host -> openTerminal(dir, host, null, null) },
                                            onOpenTerminalSession = { sid -> openTerminal(settings.defaultCwd, null, sid, null) },
                                        )
                                    }
                                    2 -> PluginPageTakeoverHost(app = app, replaceSlotName = "files.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                        FilesScreen(
                                            app = app,
                                            modifier = modifier,
                                            requestedPath = pendingBrowseDir,
                                            onConsumeRequestedPath = { pendingBrowseDir = null },
                                            onOpenSsh = { tab = 1 },
                                            onOpenTerminal = { dir, onClosed -> openTerminal(dir, null, null, onClosed) },
                                            onUseDirectory = { cwd ->
                                                app.settings.addProject(cwd)
                                                app.settings.setDefaultCwd(cwd)
                                                app.conversations.notify("已添加到项目并设为默认目录：$cwd")
                                                tab = 0
                                            },
                                            onReferenceFile = { cwd, file ->
                                                app.settings.addProject(cwd)
                                                app.settings.setDefaultCwd(cwd)
                                                pendingCreate = PendingCreate(cwd, file)
                                                tab = 0
                                            },
                                        )
                                    }
                                    3 -> PluginPageTakeoverHost(app = app, replaceSlotName = "settings.replace", cwd = settings.defaultCwd, modifier = modifier) {
                                        SettingsScreen(
                                            app = app,
                                            modifier = modifier,
                                            onNavigate = { route ->
                                                if (route == "tab/ssh") tab = 1
                                                else safeNavigate(route)
                                            },
                                        )
                                    }
                                    else -> {
                                        val dynSlot = navTabSlots.getOrNull(currentTab - 4)
                                        if (dynSlot != null) {
                                            PluginFullTabScreen(
                                                app = app,
                                                slot = dynSlot,
                                                cwd = settings.defaultCwd,
                                                modifier = modifier,
                                            )
                                        } else {
                                            SettingsScreen(
                                                app = app,
                                                modifier = modifier,
                                                onNavigate = { route ->
                                                    if (route == "tab/ssh") tab = 1
                                                    else safeNavigate(route)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composable("agent/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = ConversationKey.decodeRouteId(entry.arguments!!.getString("id")!!)
                when (id) {
                    "codex" -> CodexSettingsScreen(app, snackbar, onBack = ::safePopBack, onOpenProviders = { safeNavigate("settings/providers") })
                    "claude" -> ClaudeSettingsScreen(app, snackbar, onBack = ::safePopBack, onOpenProviders = { safeNavigate("settings/providers") })
                    else -> UniversalAgentDetailScreen(
                        app = app,
                        agentId = id,
                        snackbar = snackbar,
                        onBack = ::safePopBack,
                        onOpenProviders = { safeNavigate("settings/providers") },
                    )
                }
            }
            composable("settings/agents") {
                AgentsScreen(
                    app = app,
                    modifier = Modifier.fillMaxSize(),
                    snackbar = snackbar,
                    onOpenAgent = { id -> safeNavigate("agent/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}") },
                    onBack = ::safePopBack,
                )
            }
            composable("settings/general") { GeneralSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/env") { EnvironmentHubScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/theme") { ThemeSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/providers") { ProvidersSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/usage") {
                UsageScreen(
                    app = app,
                    snackbar = snackbar,
                    onBack = ::safePopBack,
                    onOpenConversation = openConversation,
                )
            }
            composable("settings/extensions") { ExtensionsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/superagent") { SuperAgentSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/codex") { CodexSettingsScreen(app, snackbar, onBack = ::safePopBack, onOpenProviders = { safeNavigate("settings/providers") }) }
            composable("settings/claude") { ClaudeSettingsScreen(app, snackbar, onBack = ::safePopBack, onOpenProviders = { safeNavigate("settings/providers") }) }
            composable("settings/workspace") { WorkspaceSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/mobileuse") { MobileUseSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/runtime") { RuntimeSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/selfbuild") { SelfBuildSettingsScreen(app, snackbar, onBack = ::safePopBack) }
            composable("settings/about") {
                AboutSettingsScreen(
                    app = app,
                    snackbar = snackbar,
                    onNavigate = ::safeNavigate,
                    onBack = ::safePopBack,
                )
            }
            composable("settings/about/agreement") { UserAgreementScreen(snackbar = snackbar, onBack = ::safePopBack) }
            composable("settings/about/licenses") { OpenSourceLicensesScreen(snackbar = snackbar, onBack = ::safePopBack) }

            composable("terminal") {
                val req = terminalRequest
                Box(Modifier.fillMaxSize()) {
                    TerminalSheet(
                        app = app,
                        cwd = req?.cwd ?: settings.defaultCwd,
                        sshHost = req?.sshHost,
                        initialSessionId = req?.initialSessionId,
                        onDismiss = {
                            req?.onClosed?.invoke()
                            safePopBack()
                        },
                    )
                    Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)) {
                        GaliaisSnackbarHost(snackbar)
                    }
                }
            }

            composable(
                route = "plugin/{pluginId}/{surfaceId}",
                arguments = listOf(
                    navArgument("pluginId") { type = NavType.StringType },
                    navArgument("surfaceId") { type = NavType.StringType },
                ),
            ) { entry ->
                val pid = ConversationKey.decodeRouteId(entry.arguments!!.getString("pluginId")!!)
                val sid = ConversationKey.decodeRouteId(entry.arguments!!.getString("surfaceId")!!)
                PluginFullPageRouteScreen(
                    app = app,
                    pluginId = pid,
                    surfaceId = sid,
                    cwd = settings.defaultCwd,
                    onBack = ::safePopBack,
                )
            }

            composable(
                route = "chat/{engine}/{id}",
                arguments = listOf(navArgument("engine") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                enterTransition = {
                    if (initialState.destination.route?.startsWith("chat/") == true) EnterTransition.None
                    else GaliaisMotion.navEnter
                },
                exitTransition = {
                    if (targetState.destination.route?.startsWith("chat/") == true) ExitTransition.None
                    else GaliaisMotion.navExit
                },
                popEnterTransition = {
                    if (initialState.destination.route?.startsWith("chat/") == true) EnterTransition.None
                    else GaliaisMotion.navPopEnter
                },
                popExitTransition = {
                    if (targetState.destination.route?.startsWith("chat/") == true) ExitTransition.None
                    else GaliaisMotion.navPopExit
                },
            ) { entry ->
                val key = ConversationKey(
                    EngineKind.of(entry.arguments!!.getString("engine")!!),
                    ConversationKey.decodeRouteId(entry.arguments!!.getString("id")!!),
                )
                PluginPageTakeoverHost(
                    app = app,
                    replaceSlotName = "chat.replace",
                    cwd = app.conversations.state(key)?.summary?.cwd ?: settings.defaultCwd,
                    onBack = ::safePopBack,
                ) {
                    ChatScreen(
                        app = app,
                        key = key,
                        snackbar = snackbar,
                        onBack = ::safePopBack,
                        onOpenConversation = openConversation,
                        onReplaceConversation = { nextKey ->
                            navigation.navigate("chat/${nextKey.route}") {
                                popUpTo("chat/${key.route}") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onOpenDirectory = { dir ->
                            pendingBrowseDir = dir
                            tab = 2
                            navigation.popBackStack("home", inclusive = false)
                        },
                    )
                }
            }
        }
        // 方案 A：全局自由定位 / 可拖拽悬浮层（无插件注册时零节点、零触摸拦截）
        PluginGlobalOverlayLayer(
            app = app,
            currentRoute = currentLogicalRoute,
            cwd = settings.defaultCwd,
        )
        val mobileApproval by app.mobileUse.pendingApproval.collectAsState()
        mobileApproval?.let { request ->
            GaliaisAlertDialog(
                title = "确认手机操作",
                confirmText = "允许本次",
                dismissText = "拒绝",
                onConfirm = { app.mobileUse.resolveApproval(request.id, true) },
                onDismissRequest = { app.mobileUse.resolveApproval(request.id, false) },
            ) {
                Text("${request.action} · ${request.packageName}", color = G.ink)
                Text(request.details, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                Text(if (request.returnToApp) "确认后请在 30 秒内返回目标应用。授权仅限本次操作。" else "授权仅限本次操作。", color = G.inkMuted)
            }
        }
        // 全局 SSH 交互式主机指纹确认（TOFU/MITM）与 2FA/OTP/密码质询弹窗
        SshPromptDialogHost(app.envSsh.nativeSsh)
    }
    }
}

/** 5 种模块化底部导航栏：全部采用单一滑动指示器 + 无涟漪误闪，避免切换时新旧 Tab 同时闪烁。 */
@Composable
fun GaliaisBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onQuickCreate: (() -> Unit)? = null,
    previewOnly: Boolean = false,
    extraTabLabels: List<String> = emptyList(),
) {
    val g = G
    val d = D
    val allTabs = remember(extraTabLabels) {
        if (extraTabLabels.isEmpty()) tabs
        else tabs + extraTabLabels.map { Tab(it, GaliaisIcons.Sparkles) }
    }
    val navPadMod = if (previewOnly) Modifier else Modifier.navigationBarsPadding()
    val safeIndex = selectedIndex.coerceIn(0, allTabs.lastIndex)
    val slideSpec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.9f, stiffness = 700f)

    when (d.navBarStyle) {
        "m3_expressive" -> {
            val container = if (d.material) MaterialTheme.colorScheme.surfaceContainer else g.surfaceFilled
            val topShape = RoundedCornerShape(topStart = d.radius(20.dp), topEnd = d.radius(20.dp))
            Surface(color = container, shape = topShape) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(navPadMod)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    val slotW = maxWidth / allTabs.size
                    val pillW = 60.dp.coerceAtMost((slotW - 8.dp).coerceAtLeast(36.dp))
                    val pillX by animateDpAsState(slotW * safeIndex + (slotW - pillW) / 2, slideSpec, label = "m3Pill")
                    Box(
                        modifier = Modifier
                            .offset(x = pillX, y = 2.dp)
                            .width(pillW)
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(g.accentContainer),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        allTabs.forEachIndexed { index, item ->
                            val active = safeIndex == index
                            val interaction = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { selected = active }
                                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(index) }
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                                    Icon(item.icon, contentDescription = null, tint = if (active) g.onAccentContainer else g.inkMuted, modifier = Modifier.size(19.dp))
                                }
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (active) g.ink else g.inkMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        "hardware_slots" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(navPadMod)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = g.surface,
                    border = BorderStroke(1.25.dp, g.borderStrong),
                    shadowElevation = if (g.dark) 2.dp else 3.dp,
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp)) {
                        val dividerTotal = 1.dp * (allTabs.size - 1).coerceAtLeast(0)
                        val slotW = ((maxWidth - dividerTotal) / allTabs.size).coerceAtLeast(0.dp)
                        val slotX by animateDpAsState((slotW + 1.dp) * safeIndex, slideSpec, label = "hwSlot")
                        Box(
                            modifier = Modifier
                                .offset(x = slotX)
                                .width(slotW)
                                .fillMaxHeight()
                                .background(g.surfaceSubtle),
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 6.dp)
                                    .width(20.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(g.accent),
                            )
                        }
                        Row(Modifier.fillMaxSize()) {
                            allTabs.forEachIndexed { index, item ->
                                val active = safeIndex == index
                                val interaction = remember { MutableInteractionSource() }
                                if (index > 0) {
                                    Box(Modifier.width(1.dp).fillMaxHeight().background(g.border))
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .semantics { selected = active }
                                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(index) }
                                        .padding(top = 9.dp, bottom = 4.dp, start = 2.dp, end = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                ) {
                                    Icon(item.icon, contentDescription = null, tint = if (active) g.ink else g.inkMuted, modifier = Modifier.size(18.dp))
                                    Text(
                                        item.label,
                                        fontFamily = Mono,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) g.ink else g.inkMuted,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        "split_fab" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(navPadMod)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatingDockTrack(
                    selectedIndex = safeIndex,
                    onSelect = onSelect,
                    tabItems = allTabs,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { onQuickCreate?.invoke() ?: onSelect(0) },
                    modifier = Modifier.size(58.dp).semantics { contentDescription = "创建项目" },
                    shape = CircleShape,
                    color = g.accent,
                    contentColor = g.onAccent,
                    shadowElevation = if (g.dark) 4.dp else 8.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(GaliaisIcons.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        "editorial_bar" -> {
            Surface(color = g.surface) {
                Column(Modifier.fillMaxWidth().then(navPadMod)) {
                    Hairline()
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().height(if (allTabs.size >= 5) 60.dp else 56.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        val slotW = maxWidth / allTabs.size
                        val slotX by animateDpAsState(slotW * safeIndex, slideSpec, label = "edPill")
                        Box(
                            modifier = Modifier
                                .offset(x = slotX + 3.dp)
                                .width((slotW - 6.dp).coerceAtLeast(0.dp))
                                .fillMaxHeight()
                                .clip(d.shape(12.dp))
                                .background(g.surfaceSubtle),
                        )
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            allTabs.forEachIndexed { index, item ->
                                val active = safeIndex == index
                                val interaction = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .semantics { selected = active }
                                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(index) }
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (allTabs.size >= 5) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                                            Icon(item.icon, contentDescription = null, tint = if (active) g.accent else g.inkSubtle, modifier = Modifier.size(17.dp))
                                            Text(
                                                item.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (active) g.accent else g.inkSubtle,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Icon(item.icon, contentDescription = null, tint = if (active) g.accent else g.inkSubtle, modifier = Modifier.size(18.dp))
                                            Text(
                                                item.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (active) g.accent else g.inkSubtle,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // floating_glass（默认）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(navPadMod)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FloatingDockTrack(
                    selectedIndex = safeIndex,
                    onSelect = onSelect,
                    tabItems = allTabs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FloatingDockTrack(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    tabItems: List<Tab> = tabs,
    modifier: Modifier = Modifier,
) {
    val g = G
    Surface(
        modifier = modifier.shadow(
            elevation = if (g.dark) 4.dp else 6.dp,
            shape = CircleShape,
            clip = false,
            ambientColor = softShadowAmbient(g),
            spotColor = softShadowSpot(g),
        ),
        shape = CircleShape,
        color = g.surface.copy(alpha = if (g.dark) 0.78f else 0.84f),
        border = BorderStroke(1.dp, g.borderStrong.copy(alpha = if (g.dark) 0.72f else 0.85f)),
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(4.dp).height(50.dp)) {
            val slotW = maxWidth / tabItems.size
            val pillX by animateDpAsState(
                targetValue = slotW * selectedIndex,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                label = "dockSlider",
            )
            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(slotW)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(g.accentContainer),
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabItems.forEachIndexed { index, item ->
                    val active = selectedIndex == index
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .semantics { selected = active }
                            .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(index) }
                            .padding(horizontal = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (active) g.onAccentContainer else g.inkMuted,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) g.onAccentContainer else g.inkMuted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
