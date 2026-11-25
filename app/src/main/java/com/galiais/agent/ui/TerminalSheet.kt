package com.galiais.agent.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.TerminalSession
import com.galiais.agent.engine.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

private val TermCanvasBg = Color(0xFF0B0E14)
private val TermChromeBg = Color(0xFF111520)
private val TermBarBg = Color(0xFF151A26)
private val TermKeyBg = Color(0xFF1E2433)
private val TermKeyActiveBg = Color(0xFF2B416B)
private val TermBorder = Color(0xFF262F42)
private val TermInk = Color(0xFFE6EDF3)
private val TermMuted = Color(0xFF8B949E)
private val TermAccent = Color(0xFF7AA2F7)
private val TermGreen = Color(0xFF4ADE80)
private val TermAmber = Color(0xFFFBBF24)
private val TermRed = Color(0xFFF87171)

private class TerminalBridge(
    private val dispatch: (() -> Unit) -> Unit,
    private val onReady: (Int, Int) -> Unit,
    private val onRestored: (String) -> Unit,
    private val onJsError: (String) -> Unit,
    private val onInput: (String, String, Boolean) -> Unit,
    private val onResize: (String, Int, Int) -> Unit,
    private val onGrid: (Int, Int) -> Unit,
    private val onResync: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onModifiers: (Boolean, Boolean) -> Unit,
    private val onFont: (Int) -> Unit,
) {
    @JavascriptInterface fun ready(cols: Int, rows: Int) = dispatch { onReady(cols, rows) }
    @JavascriptInterface fun restored(id: String) = dispatch { onRestored(id) }
    @JavascriptInterface fun jsError(message: String) = dispatch { onJsError(message) }
    @JavascriptInterface fun input(id: String, data: String, binary: Boolean) = dispatch { onInput(id, data, binary) }
    @JavascriptInterface fun resize(id: String, cols: Int, rows: Int) = dispatch { onResize(id, cols, rows) }
    @JavascriptInterface fun grid(cols: Int, rows: Int) = dispatch { onGrid(cols, rows) }
    @JavascriptInterface fun resync(id: String) = dispatch { onResync(id) }
    @JavascriptInterface fun copy(text: String) = dispatch { onCopy(text) }
    @JavascriptInterface fun modifiers(ctrl: Boolean, alt: Boolean) = dispatch { onModifiers(ctrl, alt) }
    @JavascriptInterface fun fontChanged(size: Int) = dispatch { onFont(size) }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 沉浸式开发者交互终端（单窗口零闪烁 + 常驻多标签栏 + 一键新建/结束 + 硬件键帽辅助栏）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalSheet(app: GaliaisApplication, cwd: String, sshHost: SshHostConfig? = null, onDismiss: () -> Unit) {
    val terminals = app.conversations.hub.terminals
    val sessions by terminals.sessions.collectAsState()
    val sshHosts by app.envSsh.sshHosts.collectAsState()
    val appSettings by app.settings.state.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val appWasDark = G.dark

    // 进入暗色终端时将系统状态栏/导航栏图标切为浅色，离开时无感恢复原主题图标色。
    DisposableEffect(view, appWasDark) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !appWasDark
            controller?.isAppearanceLightNavigationBars = !appWasDark
        }
    }
    BackHandler { onDismiss() }

    var selectedId by rememberSaveable(cwd, sshHost?.id) { mutableStateOf<String?>(null) }
    var autoBootDone by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var attachmentGeneration by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var restoredSessionId by remember { mutableStateOf<String?>(null) }
    var rendererFailed by remember { mutableStateOf(false) }
    var confirmEndTarget by remember { mutableStateOf<TerminalSession?>(null) }
    var confirmEndAll by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val termPrefs = remember(app) { app.getSharedPreferences("galiais_terminal", android.content.Context.MODE_PRIVATE) }
    var fontSize by rememberSaveable { mutableIntStateOf(termPrefs.getInt("font_size", 14).coerceIn(10, 26)) }
    var gridCols by remember { mutableIntStateOf(80) }
    var gridRows by remember { mutableIntStateOf(24) }
    var showCommandBar by rememberSaveable { mutableStateOf(false) }
    var commandLine by remember { mutableStateOf("") }
    var commandHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var showNewMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    val usable = runtime.ready && webReady && !attaching && selected?.running == true
    val background = TermCanvasBg.toArgb()
    val foreground = TermInk.toArgb()
    fun hexColor(value: Int) = String.format("#%06x", value and 0xffffff)
    fun script(method: String, value: String = "") {
        web?.evaluateJavascript("window.TerminalApp && window.TerminalApp.$method($value)", null)
    }
    fun sendKey(data: String) {
        script("key", JSONObject.quote(data))
        web?.requestFocus()
    }
    fun showIme() {
        script("focus")
        web?.let { webView ->
            webView.requestFocus()
            webView.post { app.getSystemService(InputMethodManager::class.java)?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    fun triggerPaste() {
        val text = app.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        when {
            text.isEmpty() -> app.conversations.notify("剪贴板为空")
            text.length > 128 * 1024 -> error = "粘贴内容过长，请分段输入"
            text.contains('\n') || text.contains('\r') -> pasteDraft = text
            else -> script("paste", JSONObject.quote(text))
        }
    }
    suspend fun attach(id: String) {
        val generation = ++attachmentGeneration
        attaching = true
        script("prepare", JSONObject.quote(id))
        try {
            val snapshot = terminals.attach(id)
            if (generation == attachmentGeneration && selectedId == id && webReady) script("restore", snapshot.toString())
        } finally { if (generation == attachmentGeneration) attaching = false }
    }
    fun report(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "终端操作失败" }
        }
    }
    fun open(targetCwd: String = cwd, targetHost: SshHostConfig? = sshHost) {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                selectedId = terminals.open(targetCwd, targetHost, cols = gridCols, rows = gridRows)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "启动终端失败" }
            finally { connecting = false }
        }
    }
    fun closeSession(targetId: String) {
        report {
            val before = terminals.sessions.value
            val idx = before.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
            terminals.close(targetId)
            val remaining = terminals.sessions.value
            if (selectedId == targetId) {
                val next = remaining.getOrNull(idx.coerceAtMost(remaining.lastIndex))?.id
                selectedId = next
                if (next == null) {
                    restoredSessionId = null
                    script("clearScreen")
                }
            }
        }
    }
    fun requestCloseSession(session: TerminalSession) {
        if (session.running) confirmEndTarget = session
        else closeSession(session.id)
    }
    fun reconnectSession(dead: TerminalSession) {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                val hostForReconnect = dead.sshHostId?.let { hid ->
                    sshHosts.firstOrNull { it.id == hid }?.let { h ->
                        if (dead.remoteCwd.isNotBlank()) h.copy(defaultPath = dead.remoteCwd) else h
                    }
                } ?: sshHost?.takeIf { it.id == dead.sshHostId }
                val newId = terminals.open(dead.cwd, hostForReconnect, cols = gridCols, rows = gridRows)
                selectedId = newId
                runCatching { terminals.close(dead.id) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "重新连接终端失败" }
            finally { connecting = false }
        }
    }
    fun submitCommandLine() {
        val line = commandLine
        if (!usable || line.isEmpty()) return
        commandLine = ""
        historyIndex = -1
        val trimmed = line.trim()
        if (trimmed.isNotEmpty()) {
            commandHistory = (listOf(trimmed) + commandHistory.filterNot { it == trimmed }).take(30)
        }
        selectedId?.let { id -> report { terminals.write(id, line + "\n", false) } }
    }
    val bridge = remember {
        TerminalBridge(
            dispatch = { action -> scope.launch { action() } },
            onReady = { cols, rows ->
                if (cols in 20..500 && rows in 6..300) {
                    gridCols = cols
                    gridRows = rows
                }
                webReady = true
            },
            onRestored = { id ->
                if (selectedId == id) restoredSessionId = id
            },
            onJsError = { msg -> Log.e("GALIAIS_TERM", "JS error: $msg"); error = "终端前端异常：$msg" },
            onInput = { id, data, binary -> report { terminals.write(id, data, binary) } },
            onResize = { id, cols, rows -> report { terminals.resize(id, cols, rows) } },
            onGrid = { cols, rows -> gridCols = cols; gridRows = rows },
            onResync = { id -> report { attach(id) } },
            onCopy = { text ->
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("终端", text))
                app.conversations.notify(if (text.isEmpty()) "没有可复制的内容" else "已复制终端内容")
            },
            onModifiers = { nextCtrl, nextAlt -> ctrl = nextCtrl; alt = nextAlt },
            onFont = { value ->
                val clamped = value.coerceIn(10, 26)
                fontSize = clamped
                termPrefs.edit().putInt("font_size", clamped).apply()
            },
        )
    }

    // 等待 xterm 测出真实手机行列后再挂接或按真实尺寸启动首终端，彻底消除 80x24 -> 手机列宽二次重排闪烁。
    LaunchedEffect(runtime.ready, webReady) {
        if (!runtime.ready || !webReady || autoBootDone) return@LaunchedEffect
        autoBootDone = true
        try {
            terminals.refresh()
            val latest = terminals.sessions.value
            val expectedRemote = sshHost?.defaultPath.orEmpty().trim()
            if (latest.none { it.id == selectedId }) {
                selectedId = latest.lastOrNull {
                    it.cwd == cwd && it.sshHostId == sshHost?.id && it.remoteCwd == expectedRemote && it.running
                }?.id
                if (selectedId == null) open(cwd, sshHost)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "读取终端会话失败" }
    }
    LaunchedEffect(webReady) {
        if (webReady) terminals.outputs.collect { packet ->
            if (packet.id == selectedId) script("push", packet.toJson().toString())
        }
    }
    // 仅在切换会话或 WebView/环境重新就绪时挂接，不监听 selected?.status 防止进程退出瞬间整屏清空闪烁。
    LaunchedEffect(selectedId, webReady, runtime.ready) {
        val id = selectedId
        if (id != null && webReady && runtime.ready) {
            try { attach(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "恢复终端失败" }
        }
    }
    LaunchedEffect(webReady, fontSize) {
        if (webReady) {
            val theme = jsonOf(
                "background" to hexColor(background), "foreground" to hexColor(foreground),
                "cursor" to "#7aa2f7", "cursorAccent" to "#0b0e14", "selectionBackground" to "#2b416b",
                "black" to "#15161e", "red" to "#f7768e", "green" to "#9ece6a", "yellow" to "#e0af68",
                "blue" to "#7aa2f7", "magenta" to "#bb9af7", "cyan" to "#7dcfff", "white" to "#c0caf5",
                "brightBlack" to "#565f89", "brightRed" to "#ff7a93", "brightGreen" to "#b9f27c", "brightYellow" to "#ff9e64",
                "brightBlue" to "#7da6ff", "brightMagenta" to "#c0a3ff", "brightCyan" to "#0db9d7", "brightWhite" to "#f0f6fc",
            )
            script("configure", jsonOf("fontSize" to fontSize, "theme" to theme).toString())
        }
    }

    val canvasAlpha by animateFloatAsState(
        targetValue = if (selected != null && webReady && restoredSessionId == selected.id) 1f else 0f,
        animationSpec = tween(90),
        label = "termCanvasReveal",
    )

    Box(Modifier.fillMaxSize().background(TermCanvasBg)) {
        Column(Modifier.fillMaxSize().background(TermChromeBg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            // 1. 顶部曜石工具栏（48dp）：返回 + 状态点/会话标题 + 新建(+) + 一键结束(×) + 命令条 + 更多菜单
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).background(TermChromeBg).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = TermInk, modifier = Modifier.size(19.dp))
                }
                val dotColor = when {
                    connecting || attaching -> TermAmber
                    selected?.running == true -> TermGreen
                    selected != null -> TermRed
                    else -> TermMuted
                }
                val isSsh = selected?.sshHostId != null || (selected == null && sshHost != null)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isSsh) Color(0xFF1F2F4A) else Color(0xFF1B2B25))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (isSsh) "SSH" else "LOCAL",
                            fontFamily = Mono,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSsh) TermAccent else TermGreen,
                        )
                    }
                    Text(
                        selected?.title ?: if (sessions.isEmpty()) "终端" else (sshHost?.name ?: "本地终端"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TermInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sessions.isNotEmpty()) {
                        Text(
                            "${sessions.size}/8",
                            fontFamily = Mono,
                            fontSize = 10.sp,
                            color = TermMuted,
                        )
                    }
                }

                // 新建终端（支持新建当前目录终端、切换项目目录或快速连已保存 SSH 主机）
                val extraProjects = remember(appSettings.projects, cwd) { appSettings.projects.filter { it != cwd } }
                Box {
                    IconButton(
                        enabled = runtime.ready && !connecting && sessions.size < 8,
                        onClick = {
                            if (sshHosts.isEmpty() && extraProjects.isEmpty()) open(cwd, null)
                            else showNewMenu = true
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            GaliaisIcons.Add,
                            contentDescription = "新建终端",
                            tint = if (runtime.ready && !connecting && sessions.size < 8) TermInk else TermMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    GaliaisDropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow(
                                title = "新建本地终端",
                                subtitle = cwd.replace("/home/agent", "~"),
                                icon = GaliaisIcons.Terminal,
                                onClick = { showNewMenu = false; open(cwd, null) },
                            )
                            if (extraProjects.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                Text("在项目目录新建", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                                extraProjects.take(5).forEach { dir ->
                                    GaliaisMenuRow(
                                        title = projectDisplayName(dir),
                                        subtitle = dir.replace("/home/agent", "~"),
                                        icon = GaliaisIcons.Folder,
                                        onClick = { showNewMenu = false; open(dir, null) },
                                    )
                                }
                            }
                            if (sshHosts.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                Text("快速连接 SSH", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                                sshHosts.take(6).forEach { host ->
                                    GaliaisMenuRow(
                                        title = host.name,
                                        subtitle = "${host.user}@${host.host}",
                                        icon = GaliaisIcons.Server,
                                        onClick = { showNewMenu = false; open(cwd, host) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 直接结束当前终端按钮（无需进入三级菜单，已退出状态单点即关）
                if (selected != null && runtime.ready) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF28191F))
                            .clickable(role = Role.Button) { requestCloseSession(selected) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            GaliaisIcons.Close,
                            contentDescription = "结束当前终端",
                            tint = TermRed,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                // 命令直发栏开关（中文输入法友好）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showCommandBar) TermKeyActiveBg else Color.Transparent)
                        .clickable { showCommandBar = !showCommandBar },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GaliaisIcons.Edit, contentDescription = "命令直发栏", tint = if (showCommandBar) TermAccent else TermMuted, modifier = Modifier.size(16.dp))
                }

                // 更多操作菜单
                Box {
                    IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(34.dp)) {
                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "终端选项", tint = TermInk, modifier = Modifier.size(18.dp))
                    }
                    GaliaisDropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow(title = "复制选中 / 全部", icon = GaliaisIcons.Copy, enabled = webReady && selected != null, onClick = { showMoreMenu = false; script("copy") })
                            GaliaisMenuRow(title = "粘贴剪贴板", icon = GaliaisIcons.FileLines, enabled = usable, onClick = { showMoreMenu = false; triggerPaste() })
                            GaliaisMenuRow(title = "全选终端输出", icon = GaliaisIcons.Check, enabled = webReady && selected != null, onClick = { showMoreMenu = false; script("selectAll") })
                            GaliaisMenuRow(title = "滚到底部", icon = GaliaisIcons.ArrowDown, enabled = webReady && selected != null, onClick = { showMoreMenu = false; script("scrollBottom") })
                            GaliaisMenuRow(title = "清屏 (Ctrl+L)", icon = GaliaisIcons.Refresh, enabled = usable, onClick = { showMoreMenu = false; sendKey("\u000c") })
                            Hairline(Modifier.padding(vertical = 2.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("字号 ${fontSize}px", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, modifier = Modifier.weight(1f))
                                TagPill("A−", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { script("font", "-1") })
                                TagPill("A+", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { script("font", "1") })
                            }
                            if (selected != null && runtime.ready) {
                                GaliaisMenuRow(
                                    title = "重启当前终端",
                                    subtitle = "关闭当前会话并原位重建",
                                    icon = GaliaisIcons.Refresh,
                                    enabled = !connecting,
                                    onClick = { showMoreMenu = false; reconnectSession(selected) },
                                )
                            }
                            val exitedSessions = sessions.filter { !it.running }
                            if (exitedSessions.isNotEmpty() && runtime.ready) {
                                GaliaisMenuRow(
                                    title = "清理已退出终端 (${exitedSessions.size})",
                                    icon = GaliaisIcons.Close,
                                    onClick = {
                                        showMoreMenu = false
                                        report {
                                            exitedSessions.forEach { s -> runCatching { terminals.close(s.id) } }
                                            val remaining = terminals.sessions.value
                                            if (remaining.none { it.id == selectedId }) {
                                                selectedId = remaining.lastOrNull()?.id
                                                if (selectedId == null) {
                                                    restoredSessionId = null
                                                    script("clearScreen")
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            if (sessions.size > 1 && runtime.ready) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                GaliaisMenuRow(
                                    title = "结束全部终端 (${sessions.size})",
                                    icon = GaliaisIcons.Trash,
                                    danger = true,
                                    onClick = { showMoreMenu = false; confirmEndAll = true },
                                )
                            } else if (selected != null && runtime.ready) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                GaliaisMenuRow(title = "结束当前终端", icon = GaliaisIcons.Trash, danger = true, onClick = { showMoreMenu = false; requestCloseSession(selected) })
                            }
                        }
                    }
                }
            }

            // 2. 常驻多会话胶囊标签栏（1 个会话即显示，便于一键关闭当前标签或一键新建新标签）
            if (sessions.isNotEmpty()) {
                val tabListState = rememberLazyListState()
                val activeTabIndex = remember(sessions, selectedId) { sessions.indexOfFirst { it.id == selectedId } }
                LaunchedEffect(activeTabIndex, sessions.size) {
                    if (activeTabIndex >= 0) tabListState.animateScrollToItem((activeTabIndex - 1).coerceAtLeast(0))
                }
                LazyRow(
                    state = tabListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TermChromeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val active = selectedId == session.id
                        val tabDot = if (session.running) TermGreen else TermRed
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) TermKeyBg else Color(0xFF151A24))
                                .border(0.75.dp, if (active) TermAccent.copy(alpha = 0.55f) else TermBorder, RoundedCornerShape(8.dp))
                                .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.Tab) {
                                    selectedId = session.id
                                    error = null
                                }
                                .padding(start = 9.dp, end = if (runtime.ready) 6.dp else 9.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(tabDot))
                            if (session.sshHostId != null) {
                                Text("SSH", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TermAccent)
                            }
                            Text(
                                session.title + if (session.running) "" else " · 退出",
                                fontFamily = Mono,
                                fontSize = 11.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) TermInk else TermMuted,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 132.dp),
                            )
                            if (runtime.ready) {
                                Icon(
                                    GaliaisIcons.Close,
                                    contentDescription = "关闭终端",
                                    tint = if (active) TermInk.copy(alpha = 0.72f) else TermMuted.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .clickable { requestCloseSession(session) }
                                        .padding(1.dp),
                                )
                            }
                        }
                    }
                    if (runtime.ready && sessions.size < 8) {
                        item(key = "_add_tab") {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF151A24))
                                    .border(0.75.dp, TermBorder, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !connecting, role = Role.Button) { open(cwd, null) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(GaliaisIcons.Add, contentDescription = "新建本地终端", tint = TermAccent, modifier = Modifier.size(13.dp))
                                Text("新建", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TermAccent)
                            }
                        }
                    }
                }
            }

            // 3. 轻量状态微信息条（目录 + 网格行列 + 快捷复制/粘贴）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermBarBg)
                    .border(0.5.dp, TermBorder)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pathLabel = selected?.let {
                    if (!it.remoteCwd.isNullOrBlank()) it.remoteCwd else it.cwd
                } ?: cwd
                Text(
                    pathLabel.replace("/home/agent", "~"),
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = TermMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${gridCols}×${gridRows}", fontFamily = Mono, fontSize = 10.5.sp, color = TermMuted.copy(alpha = 0.78f))
                Text("${fontSize}px", fontFamily = Mono, fontSize = 10.5.sp, color = TermMuted.copy(alpha = 0.78f))
                Text(
                    "复制",
                    fontSize = 11.sp,
                    color = if (webReady) TermAccent else TermMuted,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = webReady) { script("copy") }.padding(horizontal = 4.dp, vertical = 1.dp),
                )
                Text(
                    "粘贴",
                    fontSize = 11.sp,
                    color = if (usable) TermAccent else TermMuted,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = usable) { triggerPaste() }.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(TermCanvasBg)) {
                if (attaching || connecting) GaliaisProgressBar(modifier = Modifier.fillMaxSize())
            }

            if (!runtime.ready) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF2B2111)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("本地运行环境未连接", Modifier.weight(1f), color = TermAmber, style = MaterialTheme.typography.bodySmall)
                    PrimaryPillButton("启动", enabled = runtime.bound && !runtime.busy, onClick = app.runtime::start)
                }
            }
            error?.let { message ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF33181D)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message, Modifier.weight(1f), color = TermRed, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        error = null
                        if (rendererFailed) rendererFailed = false
                        else selectedId?.let { id -> report { attach(id) } } ?: open()
                    }) { Text("重试", color = TermAccent) }
                }
            }
            if (selected != null && !selected.running && selected.status != "disconnected") {
                Row(
                    Modifier.fillMaxWidth().background(TermBarBg).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        selected.error ?: "终端进程已退出（退出码 ${selected.exitCode ?: "—"}）",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = TermMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        onClick = { closeSession(selected.id) },
                        enabled = runtime.ready,
                        shape = RoundedCornerShape(99.dp),
                        color = TermKeyBg,
                        border = BorderStroke(0.75.dp, TermBorder),
                    ) {
                        Text("关闭标签", fontSize = 11.5.sp, color = TermInk, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    PrimaryPillButton("重新连接", enabled = runtime.ready && !connecting, onClick = { reconnectSession(selected) })
                }
            }

            // 4. 全屏 xterm.js 终端主画布（WebView 仅在首屏快照恢复与尺寸拟合完成后平滑显露，彻底零闪烁）
            if (!rendererFailed) {
                Box(Modifier.weight(1f).fillMaxWidth().background(TermCanvasBg)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setBackgroundColor(background)
                                alpha = 0f
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                settings.setSupportMultipleWindows(false)
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                isFocusableInTouchMode = true
                                addJavascriptInterface(bridge, "TerminalBridge")
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                                        Log.w("GALIAIS_TERM", "[${cm.messageLevel()}] ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})")
                                        if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                            scope.launch { error = "终端渲染报错：${cm.message()}" }
                                        }
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                        val uri = request.url
                                        val name = uri.path?.removePrefix("/")
                                        val allowed = setOf("terminal.html", "terminal-client.js", "terminal-xterm.js", "terminal-xterm.css", "terminal-fit.js")
                                        if (uri.scheme != "https" || uri.host != "terminal.solbraid.local" || name !in allowed) {
                                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                                        }
                                        return try {
                                            val mime = when {
                                                name!!.endsWith(".html") -> "text/html"
                                                name.endsWith(".css") -> "text/css"
                                                else -> "application/javascript"
                                            }
                                            WebResourceResponse(mime, "UTF-8", context.assets.open("galiais/runtime/$name"))
                                        } catch (_: java.io.IOException) {
                                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                                        }
                                    }
                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        webReady = false
                                        restoredSessionId = null
                                        rendererFailed = true
                                        error = "终端显示已中断，会话仍在后台运行"
                                        return true
                                    }
                                }
                                web = this
                                val qBg = URLEncoder.encode(hexColor(background), "UTF-8")
                                val qFg = URLEncoder.encode(hexColor(foreground), "UTF-8")
                                loadUrl("https://terminal.solbraid.local/terminal.html?bg=$qBg&fg=$qFg&font=$fontSize")
                            }
                        },
                        update = { view ->
                            view.setBackgroundColor(background)
                            view.alpha = canvasAlpha
                        },
                        onRelease = { view ->
                            if (web === view) { web = null; webReady = false; restoredSessionId = null }
                            view.removeJavascriptInterface("TerminalBridge")
                            view.stopLoading()
                            view.destroy()
                        },
                    )
                    if (sessions.isEmpty() && !connecting && autoBootDone) {
                        // 所有终端均已结束时的暗色空态引导：随时一键新建或返回，绝不残余死终端黑屏
                        Box(
                            Modifier.fillMaxSize().background(TermCanvasBg).padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(TermKeyBg).border(0.75.dp, TermBorder, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(GaliaisIcons.Terminal, contentDescription = null, tint = TermAccent, modifier = Modifier.size(24.dp))
                                }
                                Text("暂无活动终端", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TermInk)
                                Text(
                                    "可新建本地 Shell 或快速连接 SSH 主机，离开页面后进程仍会在后台保持运行。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TermMuted,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PrimaryPillButton("新建本地终端", enabled = runtime.ready && !connecting, onClick = { open(cwd, null) })
                                    Surface(
                                        onClick = onDismiss,
                                        shape = RoundedCornerShape(99.dp),
                                        color = TermKeyBg,
                                        border = BorderStroke(0.75.dp, TermBorder),
                                    ) {
                                        Text("返回", fontSize = 13.sp, color = TermInk, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    }
                                }
                                val quickProjects = appSettings.projects.filter { it != cwd }.take(4)
                                if (quickProjects.isNotEmpty() || sshHosts.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        quickProjects.forEach { dir ->
                                            Surface(
                                                onClick = { open(dir, null) },
                                                enabled = runtime.ready && !connecting,
                                                shape = RoundedCornerShape(8.dp),
                                                color = TermBarBg,
                                                border = BorderStroke(0.75.dp, TermBorder),
                                            ) {
                                                Text(
                                                    "📁 ${projectDisplayName(dir)}",
                                                    fontFamily = Mono,
                                                    fontSize = 11.5.sp,
                                                    color = TermInk,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                )
                                            }
                                        }
                                        sshHosts.take(4).forEach { host ->
                                            Surface(
                                                onClick = { open(cwd, host) },
                                                enabled = runtime.ready && !connecting,
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF172235),
                                                border = BorderStroke(0.75.dp, TermAccent.copy(alpha = 0.4f)),
                                            ) {
                                                Text(
                                                    "SSH · ${host.name}",
                                                    fontFamily = Mono,
                                                    fontSize = 11.5.sp,
                                                    color = TermAccent,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))

            // 5. 可选中文输入法友好直发命令条（支持历史命令 ↑/↓ 回溯）
            if (showCommandBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TermBarBg)
                        .border(0.5.dp, TermBorder)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("$", fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TermGreen)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TermCanvasBg)
                            .border(0.75.dp, TermBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (commandLine.isEmpty()) {
                            Text("输入命令后按回车或点击发送…", fontFamily = Mono, fontSize = 12.5.sp, color = TermMuted.copy(alpha = 0.7f))
                        }
                        BasicTextField(
                            value = commandLine,
                            onValueChange = { commandLine = it; historyIndex = -1 },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = TermInk),
                            cursorBrush = SolidColor(TermAccent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitCommandLine() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (commandHistory.isNotEmpty()) {
                        TermKeycap("↑", enabled = usable) {
                            val nextIdx = (historyIndex + 1).coerceAtMost(commandHistory.lastIndex)
                            historyIndex = nextIdx
                            commandLine = commandHistory[nextIdx]
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (usable && commandLine.isNotEmpty()) TermAccent else TermKeyBg)
                            .clickable(enabled = usable && commandLine.isNotEmpty(), role = Role.Button) { submitCommandLine() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GaliaisIcons.Send, contentDescription = "发送命令", tint = if (usable && commandLine.isNotEmpty()) TermCanvasBg else TermMuted, modifier = Modifier.size(15.dp))
                    }
                }
            }

            // 6. 单行硬件键帽辅助栏（贴合软键盘上方，右侧固定键盘唤起按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermChromeBg)
                    .border(0.5.dp, TermBorder)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TermKeycap("ESC", enabled = usable) { sendKey("\u001b") }
                    TermKeycap("TAB", enabled = usable) { sendKey("\t") }
                    TermKeycap("CTRL", active = ctrl, enabled = usable) {
                        ctrl = !ctrl; script("modifiers", "$ctrl,$alt"); web?.requestFocus()
                    }
                    TermKeycap("ALT", active = alt, enabled = usable) {
                        alt = !alt; script("modifiers", "$ctrl,$alt"); web?.requestFocus()
                    }
                    listOf(
                        "←" to "\u001b[D", "↓" to "\u001b[B", "↑" to "\u001b[A", "→" to "\u001b[C",
                        "^C" to "\u0003", "^D" to "\u0004", "^L" to "\u000c", "^R" to "\u0012", "^Z" to "\u001a",
                        "/" to "/", "-" to "-", "|" to "|", "~" to "~",
                        "HOME" to "\u001b[H", "END" to "\u001b[F", "PGUP" to "\u001b[5~", "PGDN" to "\u001b[6~",
                    ).forEach { (label, seq) ->
                        TermKeycap(label, enabled = usable, accent = label == "^C") { sendKey(seq) }
                    }
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(TermKeyBg)
                        .border(0.75.dp, TermBorder, RoundedCornerShape(7.dp))
                        .clickable(enabled = usable, role = Role.Button) { showIme() }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("键盘", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (usable) TermAccent else TermMuted)
                }
            }
        }
    }

    confirmEndTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmEndTarget = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("结束终端「${target.title}」？", color = G.ink) },
            text = { Text("将关闭该 PTY 会话并终止其中正在运行的程序。", color = G.inkMuted) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sessions.size > 1) {
                        TextButton(onClick = {
                            confirmEndTarget = null
                            report {
                                terminals.closeAll()
                                selectedId = null
                                restoredSessionId = null
                                script("clearScreen")
                            }
                        }) { Text("结束全部 (${sessions.size})", color = G.warning, fontWeight = FontWeight.Medium) }
                    }
                    TextButton(onClick = {
                        confirmEndTarget = null
                        closeSession(target.id)
                    }) { Text("结束", color = G.danger, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmEndTarget = null }) { Text("取消", color = G.inkMuted) } },
        )
    }
    if (confirmEndAll) {
        AlertDialog(
            onDismissRequest = { confirmEndAll = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("结束全部 ${sessions.size} 个终端？", color = G.ink) },
            text = { Text("将关闭所有后台与当前终端会话并终止运行中的程序。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEndAll = false
                    report {
                        terminals.closeAll()
                        selectedId = null
                        restoredSessionId = null
                        script("clearScreen")
                    }
                }) { Text("全部结束", color = G.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmEndAll = false }) { Text("取消", color = G.inkMuted) } },
        )
    }
    pasteDraft?.let { text ->
        AlertDialog(
            onDismissRequest = { pasteDraft = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("粘贴多行内容？", color = G.ink) },
            text = { Text("剪贴板包含换行符（共 ${text.length} 字符），直接粘贴可能立即执行多条命令。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = { pasteDraft = null; script("paste", JSONObject.quote(text)) }) {
                    Text("确认粘贴", color = G.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { pasteDraft = null }) { Text("取消", color = G.inkMuted) } },
        )
    }
}

@Composable
private fun TermKeycap(
    label: String,
    active: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        active -> TermKeyActiveBg
        accent -> Color(0xFF2B1C23)
        else -> TermKeyBg
    }
    val border = when {
        active -> TermAccent
        accent -> TermRed.copy(alpha = 0.45f)
        else -> TermBorder
    }
    val fg = when {
        !enabled -> TermMuted.copy(alpha = 0.45f)
        active -> TermAccent
        accent -> TermRed
        else -> TermInk
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = BorderStroke(0.75.dp, border),
    ) {
        Box(
            modifier = Modifier.height(32.dp).widthIn(min = 34.dp).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = Mono,
                fontSize = 11.5.sp,
                fontWeight = if (active || accent) FontWeight.SemiBold else FontWeight.Medium,
                color = fg,
                maxLines = 1,
            )
        }
    }
}
