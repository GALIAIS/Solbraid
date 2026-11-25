package com.galiais.agent.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.HostPluginSduiPayload
import com.galiais.agent.engine.HostPluginUiSlot
import com.galiais.agent.engine.arr
import com.galiais.agent.engine.objects
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val SAFE_RPC_METHOD_RE = Regex("^[a-zA-Z0-9._-]{1,64}$")
internal val SAFE_PLUGIN_SEG_RE = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

/** 全局原生插件全屏路由导航器（支持任意页面内按钮一键跳转 `plugin://<pluginId>/<surfaceId>`）。 */
val LocalPluginNavigator = compositionLocalOf<((pluginId: String, surfaceId: String) -> Unit)?> { null }

internal fun parsePluginNavigateTarget(action: JSONObject, fallbackPluginId: String): Pair<String, String>? {
    val rawRoute = action.optString("route").trim()
    if (rawRoute.startsWith("plugin://", ignoreCase = true)) {
        val parts = rawRoute.removePrefix("plugin://").removePrefix("PLUGIN://").split("/", limit = 2)
        if (parts.size == 2 && SAFE_PLUGIN_SEG_RE.matches(parts[0]) && SAFE_PLUGIN_SEG_RE.matches(parts[1])) {
            return parts[0] to parts[1]
        }
    }
    val pid = action.optString("pluginId").trim().ifBlank { fallbackPluginId }
    val sid = action.optString("surfaceId").trim()
    return if (SAFE_PLUGIN_SEG_RE.matches(pid) && SAFE_PLUGIN_SEG_RE.matches(sid)) pid to sid else null
}

/**
 * Solbraid 声明式原生 Jetpack Compose SDUI 渲染器：
 * 直接将插件通过 `ctx.ui.registerSurface / registerSlot` 返回的安全 JSON AST 渲染为原生 Compose 界面，
 * 零 WebView 开销、完全跟随宿主配色与圆角规范，并支持 RPC/命令/面板/页面/剪贴板双向交互。
 */
@Composable
fun PluginSduiSheet(
    app: GaliaisApplication,
    pluginId: String,
    surfaceId: String,
    cwd: String?,
    onOpenPanel: (pluginId: String, artifact: ChatArtifact) -> Unit = { _, _ -> },
    onOpenSurface: (pluginId: String, surfaceId: String) -> Unit = { _, _ -> },
    onInjectPrompt: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val pluginNav = LocalPluginNavigator.current
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val activePlugin = remember(hostPlugins, pluginId) {
        if (hostPlugins.safeMode) null
        else hostPlugins.plugins.firstOrNull { it.id == pluginId && it.status == "active" }
    }
    var payload by remember(pluginId, surfaceId) { mutableStateOf<HostPluginSduiPayload?>(null) }
    var loading by remember(pluginId, surfaceId) { mutableStateOf(true) }
    var error by remember(pluginId, surfaceId) { mutableStateOf<String?>(null) }
    var reloadSeq by remember(pluginId, surfaceId) { mutableIntStateOf(0) }
    val formState = remember(pluginId, surfaceId) { mutableStateMapOf<String, Any>() }

    LaunchedEffect(pluginId, surfaceId, cwd, reloadSeq, hostPlugins.generationId) {
        if (hostPlugins.generationId.isNotEmpty() && activePlugin == null) {
            loading = false
            payload = null
            error = "所属插件「$pluginId」已卸载、停用或处于安全模式，当前页面不可用。"
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            payload = app.extensions.renderHostPluginSurface(pluginId, surfaceId, cwd)
        } catch (e: Exception) {
            error = e.message ?: "渲染原生插件页面失败"
        } finally {
            loading = false
        }
    }

    fun dispatchAction(action: JSONObject) {
        val type = action.optString("type")
        scope.launch {
            try {
                when (type) {
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
                        } else {
                            app.conversations.notify("非法插件 RPC 方法名")
                        }
                    }
                    "command" -> {
                        val cmd = action.optString("command").trim()
                        if (cmd.isNotBlank()) {
                            val res = app.extensions.runHostPluginCommand(pluginId, cmd, action.optString("args"), cwd)
                            when (res.kind) {
                                "prompt" -> if (res.prompt.isNotBlank()) { onInjectPrompt(res.prompt); onDismiss() }
                                "artifact" -> if (res.content.isNotBlank()) {
                                    val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                    onOpenPanel(pluginId, ChatArtifact("hostcmd:$pluginId:$cmd", "hostcmd-$pluginId-$cmd", res.title.ifBlank { "/$cmd" }, k, k.extension, res.content))
                                }
                                else -> app.conversations.notify(res.message.ifBlank { "/$cmd 已执行" })
                            }
                            reloadSeq += 1
                        }
                    }
                    "panel" -> {
                        val pid = action.optString("panelId").trim()
                        if (pid.isNotBlank()) {
                            val art = app.extensions.renderHostPluginPanel(pluginId, pid, cwd)
                            onOpenPanel(pluginId, art)
                        }
                    }
                    "surface" -> {
                        val sid = action.optString("surfaceId").trim()
                        if (sid.isNotBlank()) {
                            if (action.optBoolean("fullScreen", false) && pluginNav != null) {
                                onDismiss()
                                pluginNav(pluginId, sid)
                            } else {
                                onOpenSurface(pluginId, sid)
                            }
                        }
                    }
                    "navigate" -> {
                        val target = parsePluginNavigateTarget(action, pluginId)
                        if (target != null && pluginNav != null) {
                            onDismiss()
                            pluginNav(target.first, target.second)
                        } else if (target != null) {
                            onOpenSurface(target.first, target.second)
                        }
                    }
                    "prompt" -> {
                        val text = action.optString("text")
                        if (text.isNotBlank()) { onInjectPrompt(text); onDismiss() }
                    }
                    "copy" -> {
                        val text = action.optString("text")
                        if (text.isNotBlank()) {
                            clipboard.setClipEntry(ClipData.newPlainText("sdui", text).toClipEntry())
                            app.conversations.notify("已复制到剪贴板")
                        }
                    }
                }
            } catch (e: Exception) {
                app.conversations.notify("执行插件交互失败：${e.message}")
            }
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            payload?.title?.ifBlank { surfaceId } ?: surfaceId,
                            style = MaterialTheme.typography.titleLarge,
                            color = G.ink,
                        )
                        TagPill("原生 SDUI", fg = G.accent, bg = G.accentContainer)
                    }
                    payload?.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { reloadSeq += 1 }, enabled = !loading, modifier = Modifier.size(34.dp)) {
                    Icon(GaliaisIcons.Refresh, contentDescription = "刷新", tint = if (loading) G.inkSubtle else G.inkMuted, modifier = Modifier.size(18.dp))
                }
            }
            when {
                loading && payload == null -> Text("正在渲染原生插件页面…", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                error != null -> Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = G.danger)
                else -> payload?.nodes?.forEach { node ->
                    PluginSduiNode(node = node, formState = formState, onAction = ::dispatchAction)
                }
            }
        }
    }
}

/** 直接渲染已预加载好的首页插件 SDUI 卡片（不占位、支持全量交互动作）。 */
@Composable
fun PluginSduiBannerCard(
    app: GaliaisApplication,
    pluginId: String,
    slotId: String,
    payload: HostPluginSduiPayload,
    cwd: String?,
    onOpenSurface: (pluginId: String, surfaceId: String) -> Unit,
    onOpenPanel: (pluginId: String, artifact: ChatArtifact) -> Unit,
    onRefresh: () -> Unit,
    onInjectPrompt: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val pluginNav = LocalPluginNavigator.current
    val formState = remember(pluginId, slotId) { mutableStateMapOf<String, Any>() }
    if (payload.hidden || payload.nodes.isEmpty()) return

    CrispCard(padding = PaddingValues(13.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (payload.title.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(payload.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                    TagPill("插件", fg = G.accent, bg = G.accentContainer)
                }
            }
            payload.nodes.forEach { node ->
                PluginSduiNode(
                    node = node,
                    formState = formState,
                    onAction = { act ->
                        scope.launch {
                            try {
                                when (act.optString("type")) {
                                    "refresh" -> onRefresh()
                                    "surface" -> act.optString("surfaceId").takeIf { it.isNotBlank() }?.let { sid ->
                                        if (act.optBoolean("fullScreen", false) && pluginNav != null) pluginNav(pluginId, sid)
                                        else onOpenSurface(pluginId, sid)
                                    }
                                    "navigate" -> parsePluginNavigateTarget(act, pluginId)?.let { (pid, sid) ->
                                        if (pluginNav != null) pluginNav(pid, sid) else onOpenSurface(pid, sid)
                                    }
                                    "panel" -> act.optString("panelId").takeIf { it.isNotBlank() }?.let { pid ->
                                        onOpenPanel(pluginId, app.extensions.renderHostPluginPanel(pluginId, pid, cwd))
                                    }
                                    "rpc" -> {
                                        val m = act.optString("method").trim()
                                        if (SAFE_RPC_METHOD_RE.matches(m)) {
                                            val params = JSONObject(act.optJSONObject("params")?.toString() ?: "{}")
                                            formState.forEach { (k, v) -> if (!params.has(k)) params.put(k, v) }
                                            val res = app.runtime.hubRequest("plugin.$pluginId.$m", params, timeoutMs = 12_000)
                                            val msg = res.optString("notice").ifBlank { res.optString("message") }
                                            if (msg.isNotBlank()) app.conversations.notify(msg)
                                            onRefresh()
                                        }
                                    }
                                    "command" -> {
                                        val cmd = act.optString("command").trim()
                                        if (cmd.isNotBlank()) {
                                            val res = app.extensions.runHostPluginCommand(pluginId, cmd, act.optString("args"), cwd)
                                            when {
                                                res.kind == "prompt" && res.prompt.isNotBlank() && onInjectPrompt != null -> onInjectPrompt(res.prompt)
                                                res.kind == "artifact" && res.content.isNotBlank() -> {
                                                    val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                                    onOpenPanel(pluginId, ChatArtifact("hostcmd:$pluginId:$cmd", "hostcmd-$pluginId-$cmd", res.title.ifBlank { "/$cmd" }, k, k.extension, res.content))
                                                }
                                                else -> app.conversations.notify(res.message.ifBlank { "/$cmd 已执行" })
                                            }
                                            onRefresh()
                                        }
                                    }
                                    "prompt" -> {
                                        val t = act.optString("text")
                                        if (t.isNotBlank() && onInjectPrompt != null) onInjectPrompt(t)
                                    }
                                    "copy" -> {
                                        val t = act.optString("text")
                                        if (t.isNotBlank()) {
                                            clipboard.setClipEntry(ClipData.newPlainText("sdui", t).toClipEntry())
                                            app.conversations.notify("已复制到剪贴板")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                app.conversations.notify("执行插件卡片动作失败：${e.message}")
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 通用工具栏/顶栏/快捷条插件槽位：无插件注册时零渲染、零占位。 */
@Composable
fun PluginSlotActionBar(
    app: GaliaisApplication,
    slotName: String,
    cwd: String?,
    sessionId: String? = null,
    scrollable: Boolean = false,
    darkStyle: Boolean = false,
    modifier: Modifier = Modifier,
    onOpenSurface: (pluginId: String, surfaceId: String) -> Unit,
    onOpenPanel: (pluginId: String, artifact: ChatArtifact) -> Unit,
    onInjectPrompt: ((String) -> Unit)? = null,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val slots = remember(hostPlugins, slotName) { app.extensions.activePluginUiSlots(slotName) }
    if (!runtime.ready || slots.isEmpty()) return
    val scope = rememberCoroutineScope()

    fun trigger(slot: HostPluginUiSlot) {
        scope.launch {
            try {
                when {
                    !slot.surfaceId.isNullOrBlank() -> onOpenSurface(slot.pluginId, slot.surfaceId)
                    !slot.panelId.isNullOrBlank() -> onOpenPanel(slot.pluginId, app.extensions.renderHostPluginPanel(slot.pluginId, slot.panelId, cwd))
                    !slot.command.isNullOrBlank() -> {
                        val res = app.extensions.runHostPluginCommand(slot.pluginId, slot.command, cwd = cwd, sessionId = sessionId)
                        when {
                            res.kind == "prompt" && res.prompt.isNotBlank() && onInjectPrompt != null -> onInjectPrompt(res.prompt)
                            res.kind == "artifact" && res.content.isNotBlank() -> {
                                val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                onOpenPanel(slot.pluginId, ChatArtifact("hostcmd:${slot.pluginId}:${slot.command}", "hostcmd-${slot.pluginId}-${slot.command}", res.title.ifBlank { slot.label }, k, k.extension, res.content))
                            }
                            else -> app.conversations.notify(res.message.ifBlank { "${slot.label} 已执行" })
                        }
                    }
                    else -> app.conversations.notify("插件槽位「${slot.label}」未绑定动作")
                }
            } catch (e: Exception) {
                app.conversations.notify("执行插件槽位失败：${e.message}")
            }
        }
    }

    val rowMod = if (scrollable) modifier.horizontalScroll(rememberScrollState()) else modifier
    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slots.take(6).forEach { slot ->
            val pillText = slot.badge.takeIf { it.isNotBlank() } ?: slot.label
            TagPill(
                text = pillText,
                fg = if (darkStyle) Color(0xFF7AA2F7) else G.accent,
                bg = if (darkStyle) Color(0xFF1E2433) else G.accentContainer,
                border = if (darkStyle) Color(0xFF262F42) else null,
                onClick = { trigger(slot) },
            )
        }
    }
}

/** 通用卡片/横幅/区块插件槽位：预加载后仅对非隐藏且非空卡片渲染，无插件时完全不占位。 */
@Composable
fun PluginSlotBannerColumn(
    app: GaliaisApplication,
    slotName: String,
    cwd: String?,
    modifier: Modifier = Modifier,
    onOpenSurface: (pluginId: String, surfaceId: String) -> Unit,
    onOpenPanel: (pluginId: String, artifact: ChatArtifact) -> Unit,
    onInjectPrompt: ((String) -> Unit)? = null,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val slots = remember(hostPlugins, slotName) { app.extensions.activePluginUiSlots(slotName) }
    var refreshSeq by remember(slotName) { mutableIntStateOf(0) }
    var visibleCards by remember(slotName) { mutableStateOf<List<Triple<String, String, HostPluginSduiPayload>>>(emptyList()) }

    LaunchedEffect(runtime.ready, slots, hostPlugins.generationId, cwd, refreshSeq) {
        if (!runtime.ready || slots.isEmpty()) {
            visibleCards = emptyList()
            return@LaunchedEffect
        }
        visibleCards = slots.mapNotNull { slot ->
            val p = runCatching { app.extensions.renderHostPluginSlot(slot.pluginId, slot.id, cwd) }.getOrNull()
            if (p != null && !p.hidden && p.nodes.isNotEmpty()) Triple(slot.pluginId, slot.id, p) else null
        }
    }

    if (visibleCards.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(D.space(10.dp))) {
        visibleCards.forEach { (pid, sid, payload) ->
            PluginSduiBannerCard(
                app = app,
                pluginId = pid,
                slotId = sid,
                payload = payload,
                cwd = cwd,
                onOpenSurface = onOpenSurface,
                onOpenPanel = onOpenPanel,
                onRefresh = { refreshSeq += 1 },
                onInjectPrompt = onInjectPrompt,
            )
        }
    }
}

/** 通用浮层宿主：统一承接任意页面触发的插件原生 SDUI 弹层与产物面板，减少样板代码。 */
@Composable
fun PluginSlotOverlayHost(
    app: GaliaisApplication,
    cwd: String?,
    openingSurface: Pair<String, String>?,
    openingPanel: Pair<String, ChatArtifact>?,
    onOpenSurface: (Pair<String, String>?) -> Unit,
    onOpenPanel: (Pair<String, ChatArtifact>?) -> Unit,
    onInjectPrompt: (String) -> Unit = {},
) {
    openingSurface?.let { (pid, sid) ->
        PluginSduiSheet(
            app = app,
            pluginId = pid,
            surfaceId = sid,
            cwd = cwd,
            onOpenPanel = { panelPid, art ->
                onOpenSurface(null)
                onOpenPanel(panelPid to art)
            },
            onOpenSurface = { nextPid, nextSid -> onOpenSurface(nextPid to nextSid) },
            onInjectPrompt = onInjectPrompt,
            onDismiss = { onOpenSurface(null) },
        )
    }
    openingPanel?.let { (pid, art) ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(art),
            initialArtifactId = art.id,
            cwd = cwd,
            pluginId = pid,
            onDismiss = { onOpenPanel(null) },
        )
    }
}

/** `nav.tab` 动态底部导航独立整页：在应用主导航栏中整页渲染插件原生 SDUI 或快捷面板入口。 */
@Composable
fun PluginFullTabScreen(
    app: GaliaisApplication,
    slot: HostPluginUiSlot,
    cwd: String?,
    modifier: Modifier = Modifier,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val pluginNav = LocalPluginNavigator.current
    var payload by remember(slot.pluginId, slot.id, slot.surfaceId) { mutableStateOf<HostPluginSduiPayload?>(null) }
    var loading by remember(slot.pluginId, slot.id) { mutableStateOf(true) }
    var error by remember(slot.pluginId, slot.id) { mutableStateOf<String?>(null) }
    var reloadSeq by remember(slot.pluginId, slot.id) { mutableIntStateOf(0) }
    val formState = remember(slot.pluginId, slot.id) { mutableStateMapOf<String, Any>() }
    var openingSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPanel by remember { mutableStateOf<Pair<String, ChatArtifact>?>(null) }

    LaunchedEffect(runtime.ready, slot, hostPlugins.generationId, cwd, reloadSeq) {
        if (!runtime.ready) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            payload = when {
                !slot.surfaceId.isNullOrBlank() -> app.extensions.renderHostPluginSurface(slot.pluginId, slot.surfaceId, cwd)
                slot.hasRender -> app.extensions.renderHostPluginSlot(slot.pluginId, slot.id, cwd)
                else -> null
            }
        } catch (e: Exception) {
            error = e.message ?: "渲染插件页面失败"
        } finally {
            loading = false
        }
    }

    fun dispatchTabAction(action: JSONObject) {
        scope.launch {
            try {
                when (action.optString("type")) {
                    "refresh" -> reloadSeq += 1
                    "rpc" -> {
                        val method = action.optString("method").trim()
                        if (SAFE_RPC_METHOD_RE.matches(method)) {
                            val params = JSONObject(action.optJSONObject("params")?.toString() ?: "{}")
                            formState.forEach { (k, v) -> if (!params.has(k)) params.put(k, v) }
                            val res = app.runtime.hubRequest("plugin.${slot.pluginId}.$method", params, timeoutMs = 15_000)
                            val msg = res.optString("notice").ifBlank { res.optString("message") }
                            if (msg.isNotBlank()) app.conversations.notify(msg)
                            reloadSeq += 1
                        }
                    }
                    "command" -> {
                        val cmd = action.optString("command").trim()
                        if (cmd.isNotBlank()) {
                            val res = app.extensions.runHostPluginCommand(slot.pluginId, cmd, action.optString("args"), cwd)
                            if (res.kind == "artifact" && res.content.isNotBlank()) {
                                val k = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                openingPanel = slot.pluginId to ChatArtifact("hostcmd:${slot.pluginId}:$cmd", "hostcmd-${slot.pluginId}-$cmd", res.title.ifBlank { "/$cmd" }, k, k.extension, res.content)
                            } else {
                                app.conversations.notify(res.message.ifBlank { "/$cmd 已执行" })
                            }
                            reloadSeq += 1
                        }
                    }
                    "panel" -> action.optString("panelId").takeIf { it.isNotBlank() }?.let { pid ->
                        openingPanel = slot.pluginId to app.extensions.renderHostPluginPanel(slot.pluginId, pid, cwd)
                    }
                    "surface" -> action.optString("surfaceId").takeIf { it.isNotBlank() }?.let { sid ->
                        if (action.optBoolean("fullScreen", false) && pluginNav != null) pluginNav(slot.pluginId, sid)
                        else openingSurface = slot.pluginId to sid
                    }
                    "navigate" -> parsePluginNavigateTarget(action, slot.pluginId)?.let { (pid, sid) ->
                        if (pluginNav != null) pluginNav(pid, sid) else openingSurface = pid to sid
                    }
                    "copy" -> action.optString("text").takeIf { it.isNotBlank() }?.let { txt ->
                        clipboard.setClipEntry(ClipData.newPlainText("sdui", txt).toClipEntry())
                        app.conversations.notify("已复制到剪贴板")
                    }
                }
            } catch (e: Exception) {
                app.conversations.notify("执行插件交互失败：${e.message}")
            }
        }
    }

    Column(modifier.fillMaxSize().galiaisCanvasBackground()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (D.material) 64.dp else 56.dp).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(payload?.title?.ifBlank { slot.label } ?: slot.label, style = MaterialTheme.typography.titleLarge, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TagPill("插件", fg = G.accent, bg = G.accentContainer)
                }
                val sub = payload?.subtitle?.ifBlank { slot.description } ?: slot.description
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { reloadSeq += 1 }, enabled = !loading, modifier = Modifier.size(36.dp)) {
                Icon(GaliaisIcons.Refresh, contentDescription = "刷新插件页", tint = if (loading) G.inkSubtle else G.inkMuted, modifier = Modifier.size(19.dp))
            }
        }
        HeaderProgressSlot(loading)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !runtime.ready -> Text("启动本地运行环境后将加载插件页面。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                error != null -> Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.padding(12.dp))
                }
                payload != null && payload!!.nodes.isNotEmpty() -> payload!!.nodes.forEach { node ->
                    PluginSduiNode(node = node, formState = formState, onAction = ::dispatchTabAction)
                }
                !slot.panelId.isNullOrBlank() -> CrispCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(slot.label, style = MaterialTheme.typography.titleSmall, color = G.ink)
                        if (slot.description.isNotBlank()) Text(slot.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        PrimaryPillButton("打开完整面板", onClick = {
                            scope.launch {
                                runCatching { app.extensions.renderHostPluginPanel(slot.pluginId, slot.panelId, cwd) }
                                    .onSuccess { openingPanel = slot.pluginId to it }
                                    .onFailure { app.conversations.notify("打开面板失败：${it.message}") }
                            }
                        })
                    }
                }
                !loading -> Text("该插件暂未输出可展示节点。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
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

@Composable
private fun sduiToneColors(tone: String): Pair<Color, Color> = when (tone.lowercase()) {
    "accent" -> G.accent to G.accentContainer
    "success" -> G.success to G.successTint
    "warning" -> G.warning to G.warningTint
    "danger" -> G.danger to G.dangerTint
    "muted" -> G.inkMuted to G.surfaceSubtle
    else -> G.ink to G.surfaceSubtle
}

@Composable
internal fun PluginSduiNode(
    node: JSONObject,
    formState: MutableMap<String, Any>,
    onAction: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.optString("type")) {
        "column", "section" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.optString("title").takeIf { it.isNotBlank() }?.let { SectionLabel(it) }
            node.arr("children").objects().forEach { child -> PluginSduiNode(child, formState, onAction) }
        }
        "row" -> {
            val kids = node.arr("children").objects()
            val equalWeight = kids.isNotEmpty() && kids.all { it.optString("type") == "metric" }
            val hasButtonsOrBadges = kids.any { it.optString("type") in setOf("button", "badge") }
            if (hasButtonsOrBadges && !equalWeight) {
                FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kids.forEach { child -> PluginSduiNode(child, formState, onAction) }
                }
            } else {
                Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    kids.forEach { child ->
                        PluginSduiNode(child, formState, onAction, modifier = if (equalWeight) Modifier.weight(1f) else Modifier)
                    }
                }
            }
        }
        "flow" -> FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.arr("children").objects().forEach { child -> PluginSduiNode(child, formState, onAction) }
        }
        "card" -> CrispCard(modifier = modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.optString("title").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                }
                node.arr("children").objects().forEach { child -> PluginSduiNode(child, formState, onAction) }
            }
        }
        "divider" -> Hairline()
        "spacer" -> Spacer(Modifier.height(node.optInt("height", 8).coerceIn(2, 32).dp))
        "text" -> {
            val (fg, _) = sduiToneColors(node.optString("tone"))
            val style = when (node.optString("style")) {
                "title" -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                "subtitle" -> MaterialTheme.typography.titleSmall
                "caption" -> MaterialTheme.typography.labelSmall
                "mono" -> MaterialTheme.typography.bodySmall.copy(fontFamily = Mono)
                else -> MaterialTheme.typography.bodyMedium
            }
            Text(node.optString("text"), style = style, color = fg, modifier = modifier)
        }
        "badge" -> {
            val (fg, bg) = sduiToneColors(node.optString("tone", "muted"))
            val act = node.optJSONObject("action")
            TagPill(node.optString("text"), fg = fg, bg = bg, onClick = act?.let { { onAction(it) } })
        }
        "metric" -> {
            val (fg, _) = sduiToneColors(node.optString("tone", "accent"))
            Surface(modifier = modifier, shape = D.shape(12.dp), color = G.surfaceSubtle, border = BorderStroke(0.75.dp, G.border)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.optString("value", "0"), style = MaterialTheme.typography.titleLarge, color = fg, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(node.optString("label"), style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    node.optString("delta").takeIf { it.isNotBlank() }?.let {
                        Text(it, fontFamily = Mono, fontSize = 10.sp, color = G.inkSubtle, maxLines = 1)
                    }
                }
            }
        }
        "progress" -> {
            val (fg, _) = sduiToneColors(node.optString("tone", "accent"))
            val frac = node.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f)
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(node.optString("label"), style = MaterialTheme.typography.labelSmall, color = G.ink, modifier = Modifier.weight(1f))
                    Text("${(frac * 100).toInt()}%", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                }
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(D.chipShape),
                    color = fg,
                    trackColor = G.surfaceSubtle,
                )
            }
        }
        "kv" -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.optString("key"), style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.weight(1f))
            Text(node.optString("value"), fontFamily = Mono, fontSize = 12.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        "code" -> Box(
            modifier = modifier.fillMaxWidth().clip(D.shape(10.dp)).background(G.surfaceSubtle).padding(10.dp),
        ) {
            Text(node.optString("text"), fontFamily = Mono, fontSize = 11.sp, color = G.ink)
        }
        "list" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.arr("items").objects().forEach { item ->
                val act = item.optJSONObject("action")
                Row(
                    modifier = Modifier.fillMaxWidth().clip(D.shape(8.dp))
                        .then(if (act != null) Modifier.clickable { onAction(act) } else Modifier)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(item.optString("title"), style = MaterialTheme.typography.bodyMedium, color = G.ink)
                        item.optString("subtitle").takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                    }
                    item.optString("badge").takeIf { it.isNotBlank() }?.let {
                        TagPill(it, fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                }
            }
        }
        "button" -> {
            val label = node.optString("label", "执行")
            val act = node.optJSONObject("action") ?: JSONObject()
            when (node.optString("style")) {
                "primary" -> PrimaryPillButton(label, onClick = { onAction(act) })
                "danger" -> TagPill(label, fg = G.danger, bg = G.dangerTint, onClick = { onAction(act) })
                else -> SecondaryPillButton(label, onClick = { onAction(act) })
            }
        }
        "toggle" -> {
            val key = node.optString("key", node.optString("label"))
            val checked = (formState[key] as? Boolean) ?: node.optBoolean("value", false)
            SettingsToggleRow(
                title = node.optString("label", key),
                subtitle = node.optString("subtitle"),
                checked = checked,
                onCheckedChange = { want ->
                    formState[key] = want
                    node.optJSONObject("action")?.let(onAction)
                },
            )
        }
        "input" -> {
            val key = node.optString("key", "input")
            val textVal = (formState[key] as? String) ?: node.optString("value")
            OutlinedTextField(
                value = textVal,
                onValueChange = { formState[key] = it },
                label = { Text(node.optString("label", key)) },
                singleLine = !node.optBoolean("multiline", false),
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}
