package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.HostPluginSduiPayload
import com.galiais.agent.engine.HostPluginUiSlot
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private fun parseOverlayAlignment(name: String): Alignment = when (name.trim()) {
    "topStart" -> Alignment.TopStart
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    else -> Alignment.BottomEnd
}

/**
 * 方案 A：`global.overlay` 全局自由定位 / 可拖拽悬浮层。
 * 无插件挂载时直接 return（零节点、零触摸拦截）；挂载后可在任意页面任意位置渲染可拖动胶囊、FAB 或迷你 SDUI 浮窗。
 */
@Composable
fun PluginGlobalOverlayLayer(
    app: GaliaisApplication,
    currentRoute: String,
    cwd: String?,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val overlaySlots = remember(hostPlugins, currentRoute) {
        app.extensions.activePluginUiSlots("global.overlay")
            .filter { it.matchesRoute(currentRoute) }
            .take(4)
    }
    var openingSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }

    if (runtime.ready && overlaySlots.isNotEmpty()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val pluginNav = LocalPluginNavigator.current
        val bottomChrome = LocalBottomChromeInset.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomChrome.coerceAtLeast(10.dp)),
        ) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()
            overlaySlots.forEach { slot ->
                val slotKey = "${slot.pluginId}:${slot.id}"
                var dragX by remember(slotKey) { mutableFloatStateOf(0f) }
                var dragY by remember(slotKey) { mutableFloatStateOf(0f) }
                var itemSize by remember(slotKey) { mutableStateOf(IntSize.Zero) }
                var collapsed by remember(slotKey) { mutableStateOf(false) }
                var cardRefreshSeq by remember(slotKey) { mutableIntStateOf(0) }
                var cardPayload by remember(slotKey) { mutableStateOf<HostPluginSduiPayload?>(null) }
                val isCardMode = slot.displayMode == "card" || (slot.displayMode.isBlank() && slot.hasRender)

                LaunchedEffect(runtime.ready, slotKey, isCardMode, hostPlugins.generationId, cwd, cardRefreshSeq) {
                    if (!isCardMode || !slot.hasRender) {
                        cardPayload = null
                        return@LaunchedEffect
                    }
                    cardPayload = runCatching { app.extensions.renderHostPluginSlot(slot.pluginId, slot.id, cwd) }
                        .getOrNull()
                        ?.takeIf { !it.hidden && it.nodes.isNotEmpty() }
                }

                fun triggerOverlay() {
                    scope.launch {
                        try {
                            when {
                                !slot.surfaceId.isNullOrBlank() -> {
                                    if (slot.displayMode == "fab" && pluginNav != null) pluginNav(slot.pluginId, slot.surfaceId)
                                    else openingSurface = slot.pluginId to slot.surfaceId
                                }
                                !slot.panelId.isNullOrBlank() -> openingPanel = slot.pluginId to app.extensions.renderHostPluginPanel(slot.pluginId, slot.panelId, cwd)
                                !slot.command.isNullOrBlank() -> {
                                    val res = app.extensions.runHostPluginCommand(slot.pluginId, slot.command, cwd = cwd)
                                    if (res.kind == "artifact" && res.content.isNotBlank()) {
                                        val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                        openingPanel = slot.pluginId to ChatArtifact("hostcmd:${slot.pluginId}:${slot.command}", "hostcmd-${slot.pluginId}-${slot.command}", res.title.ifBlank { slot.label }, k, k.extension, res.content)
                                    } else {
                                        app.conversations.notify(res.message.ifBlank { "${slot.label} 已执行" })
                                    }
                                }
                                else -> collapsed = !collapsed
                            }
                        } catch (e: Exception) {
                            app.conversations.notify("执行悬浮插件失败：${e.message}")
                        }
                    }
                }

                val align = parseOverlayAlignment(slot.alignment)
                val baseOffsetX = with(density) { slot.offsetX.dp.toPx() }
                val baseOffsetY = with(density) { slot.offsetY.dp.toPx() }
                val itemW = itemSize.width.toFloat().coerceAtLeast(1f)
                val itemH = itemSize.height.toFloat().coerceAtLeast(1f)
                val availW = (containerW - itemW).coerceAtLeast(0f)
                val availH = (containerH - itemH).coerceAtLeast(0f)
                val minX = when (align) {
                    Alignment.TopStart, Alignment.CenterStart, Alignment.BottomStart -> 0f
                    Alignment.TopCenter, Alignment.Center, Alignment.BottomCenter -> -availW / 2f
                    else -> -availW
                }
                val maxX = minX + availW
                val minY = when (align) {
                    Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd -> 0f
                    Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd -> -availH / 2f
                    else -> -availH
                }
                val maxY = minY + availH
                val clampedX = (baseOffsetX + dragX).coerceIn(minX, maxX)
                val clampedY = (baseOffsetY + dragY).coerceIn(minY, maxY)

                val dragMod = if (slot.draggable) {
                    Modifier.pointerInput(slotKey, minX, maxX, minY, maxY, isCardMode, collapsed) {
                        detectDragGestures(
                            onDragEnd = {
                                if (!isCardMode || collapsed) {
                                    val cur = (baseOffsetX + dragX).coerceIn(minX, maxX)
                                    val snap = if (cur - minX < maxX - cur) minX else maxX
                                    dragX = snap - baseOffsetX
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragX = (baseOffsetX + dragX + dragAmount.x).coerceIn(minX, maxX) - baseOffsetX
                            dragY = (baseOffsetY + dragY + dragAmount.y).coerceIn(minY, maxY) - baseOffsetY
                        }
                    }
                } else Modifier

                Box(
                    modifier = Modifier
                        .align(align)
                        .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                        .onSizeChanged { itemSize = it }
                        .then(dragMod),
                ) {
                    val liveCard = cardPayload
                    when {
                        isCardMode && liveCard != null && !collapsed -> {
                            Surface(
                                modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
                                shape = D.shape(16.dp),
                                color = G.surface.copy(alpha = 0.96f),
                                border = BorderStroke(1.dp, G.borderStrong),
                                shadowElevation = 8.dp,
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(G.accent))
                                        Text(
                                            liveCard.title.ifBlank { slot.label },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = G.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TagPill("收起", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { collapsed = true })
                                    }
                                    PluginSduiBannerCard(
                                        app = app,
                                        pluginId = slot.pluginId,
                                        slotId = slot.id,
                                        payload = liveCard.copy(title = ""),
                                        cwd = cwd,
                                        onOpenSurface = { pid, sid -> openingSurface = pid to sid },
                                        onOpenPanel = { pid, art -> openingPanel = pid to art },
                                        onRefresh = { cardRefreshSeq += 1 },
                                        embedded = true,
                                    )
                                }
                            }
                        }
                        slot.displayMode == "fab" -> {
                            Surface(
                                onClick = { triggerOverlay() },
                                modifier = Modifier.size(48.dp),
                                shape = D.fabShape,
                                color = G.accent,
                                contentColor = G.onAccent,
                                shadowElevation = 8.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val txt = slot.badge.ifBlank { slot.label.take(2) }
                                    if (txt.isNotBlank()) {
                                        Text(txt, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    } else {
                                        Icon(GaliaisIcons.Sparkles, contentDescription = slot.label, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        else -> {
                            Surface(
                                onClick = { if (isCardMode && collapsed) collapsed = false else triggerOverlay() },
                                shape = CircleShape,
                                color = G.surface.copy(alpha = 0.96f),
                                border = BorderStroke(1.dp, G.accent.copy(alpha = 0.45f)),
                                shadowElevation = 6.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(G.accent))
                                    Text(
                                        slot.badge.ifBlank { slot.label.ifBlank { "插件" } },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = G.ink,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    PluginSlotOverlayHost(
        app = app,
        cwd = cwd,
        openingSurface = openingSurface,
        openingPanel = openingPanel,
        onOpenSurface = { openingSurface = it },
        onOpenPanel = { openingPanel = it },
    )
}

/** 方案 A 配套：无限层级全屏原生插件路由页（`plugin://<pluginId>/<surfaceId>`）。 */
@Composable
fun PluginFullPageRouteScreen(
    app: GaliaisApplication,
    pluginId: String,
    surfaceId: String,
    cwd: String?,
    onBack: () -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val activePlugin = remember(hostPlugins, pluginId) {
        if (hostPlugins.safeMode) null
        else hostPlugins.plugins.firstOrNull { it.id == pluginId && it.status == "active" }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val pluginNav = LocalPluginNavigator.current
    var payload by remember(pluginId, surfaceId) { mutableStateOf<HostPluginSduiPayload?>(null) }
    var loading by remember(pluginId, surfaceId) { mutableStateOf(true) }
    var error by remember(pluginId, surfaceId) { mutableStateOf<String?>(null) }
    var reloadSeq by remember(pluginId, surfaceId) { mutableIntStateOf(0) }
    val formState = remember(pluginId, surfaceId) { mutableStateMapOf<String, Any>() }
    var openingSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }

    LaunchedEffect(runtime.ready, pluginId, surfaceId, hostPlugins.generationId, cwd, reloadSeq) {
        if (!runtime.ready) {
            loading = false
            return@LaunchedEffect
        }
        if (hostPlugins.generationId.isNotEmpty() && activePlugin == null) {
            loading = false
            payload = null
            error = "所属插件「$pluginId」已卸载、停用或处于安全模式。"
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            payload = app.extensions.renderHostPluginSurface(pluginId, surfaceId, cwd)
        } catch (e: Exception) {
            error = e.message ?: "渲染插件全屏页面失败"
        } finally {
            loading = false
        }
    }

    fun dispatchPageAction(action: JSONObject) {
        scope.launch {
            try {
                when (action.optString("type")) {
                    "refresh" -> reloadSeq += 1
                    "rpc" -> {
                        val method = action.optString("method").trim()
                        if (SAFE_RPC_METHOD_RE.matches(method)) {
                            val params = JSONObject(action.optJSONObject("params")?.toString() ?: "{}")
                            formState.forEach { (k, v) -> if (!params.has(k)) params.put(k, v) }
                            val res = app.runtime.hubRequest("plugin.$pluginId.$method", params, timeoutMs = 15_000)
                            val msg = res.optString("notice").ifBlank { res.optString("message") }
                            if (msg.isNotBlank()) app.conversations.notify(msg)
                            reloadSeq += 1
                        }
                    }
                    "command" -> {
                        val cmd = action.optString("command").trim()
                        if (cmd.isNotBlank()) {
                            val res = app.extensions.runHostPluginCommand(pluginId, cmd, action.optString("args"), cwd)
                            if (res.kind == "artifact" && res.content.isNotBlank()) {
                                val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                openingPanel = pluginId to ChatArtifact("hostcmd:$pluginId:$cmd", "hostcmd-$pluginId-$cmd", res.title.ifBlank { "/$cmd" }, k, k.extension, res.content)
                            } else {
                                app.conversations.notify(res.message.ifBlank { "/$cmd 已执行" })
                            }
                            reloadSeq += 1
                        }
                    }
                    "panel" -> action.optString("panelId").takeIf { it.isNotBlank() }?.let { pid ->
                        openingPanel = pluginId to app.extensions.renderHostPluginPanel(pluginId, pid, cwd)
                    }
                    "surface" -> action.optString("surfaceId").takeIf { it.isNotBlank() }?.let { sid ->
                        if (action.optBoolean("fullScreen", false) && pluginNav != null) pluginNav(pluginId, sid)
                        else openingSurface = pluginId to sid
                    }
                    "navigate" -> parsePluginNavigateTarget(action, pluginId)?.let { (pid, sid) ->
                        if (pluginNav != null) pluginNav(pid, sid) else openingSurface = pid to sid
                    }
                    "copy" -> action.optString("text").takeIf { it.isNotBlank() }?.let { txt ->
                        clipboard.setClipEntry(ClipData.newPlainText("sdui", txt).toClipEntry())
                        app.conversations.notify("已复制到剪贴板")
                    }
                }
            } catch (e: Exception) {
                app.conversations.notify("执行插件页面交互失败：${e.message}")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .galiaisCanvasBackground()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        payload?.title?.ifBlank { surfaceId } ?: surfaceId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = G.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TagPill("原生页面", fg = G.accent, bg = G.accentContainer)
                }
                payload?.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { reloadSeq += 1 }, enabled = !loading) {
                Icon(GaliaisIcons.Refresh, contentDescription = "刷新", tint = if (loading) G.inkSubtle else G.inkMuted, modifier = Modifier.size(19.dp))
            }
        }
        HeaderProgressSlot(loading)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                error != null -> Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = G.danger)
                        SecondaryPillButton("返回上一页", onClick = onBack)
                    }
                }
                payload != null -> payload!!.nodes.forEach { node ->
                    PluginSduiNode(node = node, formState = formState, onAction = ::dispatchPageAction)
                }
                loading -> Text("正在加载插件页面…", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    PluginSlotOverlayHost(
        app = app,
        cwd = cwd,
        openingSurface = openingSurface,
        openingPanel = openingPanel,
        onOpenSurface = { openingSurface = it },
        onOpenPanel = { openingPanel = it },
    )
}

/**
 * 方案 B：整页可接管容器（`home.replace` / `ssh.replace` / `files.replace` / `settings.replace` / `chat.replace`）。
 * 无插件接管时直接透传 `originalContent()` 零开销；有插件接管时自带顶部安全条，随时 1 键切换原版/插件版，永不锁死界面。
 */
@Composable
fun PluginPageTakeoverHost(
    app: GaliaisApplication,
    replaceSlotName: String,
    cwd: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    originalContent: @Composable () -> Unit,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val activeSlot = remember(hostPlugins, replaceSlotName) {
        app.extensions.activePluginUiSlots(replaceSlotName).firstOrNull()
    }
    if (!runtime.ready || activeSlot == null) {
        originalContent()
        return
    }
    var bypassTakeover by remember(activeSlot.pluginId, activeSlot.id) { mutableStateOf(false) }
    val bottomChrome = LocalBottomChromeInset.current

    if (bypassTakeover) {
        Box(Modifier.fillMaxSize()) {
            originalContent()
            Surface(
                onClick = { bypassTakeover = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = bottomChrome + 10.dp),
                shape = CircleShape,
                color = G.accentContainer,
                border = BorderStroke(0.75.dp, G.accent.copy(alpha = 0.5f)),
                shadowElevation = 4.dp,
            ) {
                Text(
                    "切回插件页",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = G.accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .galiaisCanvasBackground()
            .let { if (onBack != null) it.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)) else it },
    ) {
        GaliaisTopBar(
            title = activeSlot.label.ifBlank { activeSlot.pluginId },
            subtitle = "由插件接管本页",
            onBack = onBack,
            applyStatusBarPadding = onBack != null,
            actions = {
                TagPill("切回原版", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { bypassTakeover = true })
            },
        )
        PluginFullTabScreen(
            app = app,
            slot = activeSlot,
            cwd = cwd,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            showTopBar = false,
        )
    }
}
