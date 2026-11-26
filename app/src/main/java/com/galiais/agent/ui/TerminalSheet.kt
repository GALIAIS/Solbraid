package com.galiais.agent.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.fs.FileRef
import com.galiais.agent.engine.fs.FileScope
import com.galiais.agent.engine.fs.normalizeVfsPath
import com.galiais.agent.engine.ssh.LiveTerminalSession
import com.galiais.agent.engine.ssh.StickyModifierManager
import com.galiais.agent.engine.ssh.TermSessionStatus
import com.galiais.agent.engine.terminal.PsReadLinePredictor
import com.galiais.agent.engine.terminal.TerminalDangerWarning
import com.galiais.agent.engine.terminal.TerminalLinkSpan
import com.galiais.agent.engine.terminal.TerminalMediaRequest
import com.galiais.agent.engine.terminal.TerminalProgressState
import com.galiais.agent.engine.terminal.TerminalSafeGuard
import com.galiais.agent.engine.terminal.TerminalSearchMatch
import com.galiais.agent.engine.terminal.TerminalSearchOptions
import com.galiais.agent.ui.files.CodeEditorDialog
import com.galiais.agent.ui.files.UniversalFileViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Solbraid 100% 纯自研类 PowerShell / Windows Terminal 原生终端（零 WebView、零 JNI、零第三方终端库）：
 * - 自研 VT500/xterm-256color/TrueColor 状态机 + 微软开源 CascadiaMonoNF 字体 + 自绘 120fps Compose Canvas；
 * - 支持 5 套 PowerShell/Windows Terminal 主题、`|`/`█`/`_` 光标、语义命令块徽章与一键复制、
 *   10,000 行全文搜索高亮、右侧拖拽滚动槽、PSReadLine 历史幽灵预测、SGR 1006 鼠标与双窗格分屏。
 */
@Composable
fun TerminalSheet(
    app: GaliaisApplication,
    cwd: String,
    sshHost: SshHostConfig? = null,
    initialSessionId: String? = null,
    onDismiss: () -> Unit,
) {
    val manager = app.envSsh.nativeTerminals
    val sessions by manager.sessions.collectAsState()
    val snippets by manager.snippets.collectAsState()
    val scheme by manager.theme.collectAsState()
    val cursorStyle by manager.cursorStyle.collectAsState()
    val boxDrawingEnabled by manager.boxDrawingEnabled.collectAsState()
    val minContrastEnabled by manager.minContrastEnabled.collectAsState()
    val secretRedactionMode by manager.secretRedactionMode.collectAsState()
    val safeGuardEnabled by manager.safeGuardEnabled.collectAsState()
    val bellHapticEnabled by manager.bellHapticEnabled.collectAsState()
    val visualBellEnabled by manager.visualBellEnabled.collectAsState()
    val history by manager.commandHistory.collectAsState()
    val sshHosts by app.envSsh.sshHosts.collectAsState()
    val sshKeys by app.envSsh.sshKeys.collectAsState()
    val appSettings by app.settings.state.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isImeOpen = WindowInsets.ime.getBottom(density) > 0
    val appWasDark = G.dark
    val termPrefs = remember(app) { app.getSharedPreferences("galiais_terminal", Context.MODE_PRIVATE) }

    var selectedId by rememberSaveable(cwd, sshHost?.id, initialSessionId) { mutableStateOf(initialSessionId) }
    var splitSecondId by rememberSaveable { mutableStateOf<String?>(null) }
    var splitAxis by rememberSaveable {
        mutableStateOf(if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "horizontal" else "vertical")
    }
    var splitRatio by rememberSaveable { mutableFloatStateOf(0.5f) }
    var focusedPaneIndex by rememberSaveable { mutableIntStateOf(0) }
    var zoomedPaneIndex by rememberSaveable { mutableIntStateOf(-1) }
    var broadcastInput by rememberSaveable { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableIntStateOf(termPrefs.getInt("font_size_v2", 11).coerceIn(8, 28)) }
    var showSecondRow by rememberSaveable { mutableStateOf(termPrefs.getBoolean("show_second_row", true)) }
    var showFnRow by rememberSaveable { mutableStateOf(termPrefs.getBoolean("show_fn_row", false)) }
    var keepScreenOn by rememberSaveable { mutableStateOf(termPrefs.getBoolean("keep_screen_on", true)) }
    var showCommandBar by rememberSaveable { mutableStateOf(false) }
    var showSearchBar by rememberSaveable { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOptions by remember { mutableStateOf(TerminalSearchOptions()) }
    var searchMatches by remember { mutableStateOf<List<TerminalSearchMatch>>(emptyList()) }
    var searchActiveIdx by remember { mutableIntStateOf(0) }
    var commandLine by remember { mutableStateOf("") }
    var composingText by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var showNewMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showQuickConnect by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var sftpHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var detectedUrls by remember { mutableStateOf<List<String>?>(null) }
    var confirmEndTarget by remember { mutableStateOf<LiveTerminalSession?>(null) }
    var confirmEndAll by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf<String?>(null) }
    var pendingDangerAction by remember { mutableStateOf<Pair<TerminalDangerWarning, () -> Unit>?>(null) }
    var openingEditorFile by remember { mutableStateOf<Pair<FileRef, Int?>?>(null) }
    var activeVideo by remember { mutableStateOf<TerminalVideoSource?>(null) }
    var openingRichViewerFile by remember { mutableStateOf<FileRef?>(null) }
    var showMediaUrlDialog by remember { mutableStateOf(false) }
    var visualBellPulse by remember { mutableIntStateOf(0) }
    var errorBanner by remember { mutableStateOf<String?>(null) }
    var autoBootDone by remember(cwd, sshHost?.id, initialSessionId) { mutableStateOf(false) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, com.galiais.agent.engine.ChatArtifact>?>(null) }
    val stickyModifiers = remember { StickyModifierManager() }
    val scrollOffsets = remember { mutableStateMapOf<String, Int>() }
    var unifiedImeView by remember { mutableStateOf<TerminalImeInputView?>(null) }

    val primarySession = sessions.firstOrNull { it.id == selectedId }
    val secondarySession = splitSecondId?.let { id -> sessions.firstOrNull { it.id == id && it.id != selectedId } }
    val isSplitActive = primarySession != null && secondarySession != null
    val focusedSession = if (focusedPaneIndex == 1 && secondarySession != null) secondarySession else primarySession

    val focusedStatus by (focusedSession?.status ?: remember { MutableStateFlow(TermSessionStatus.EXITED) }).collectAsState()
    val focusedExitCode by (focusedSession?.exitCode ?: remember { MutableStateFlow<Int?>(null) }).collectAsState()
    val focusedError by (focusedSession?.errorMessage ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val focusedDims by (focusedSession?.dimensions ?: remember { MutableStateFlow(80 to 24) }).collectAsState()
    val focusedLiveDraft by (focusedSession?.liveInputDraft ?: remember { MutableStateFlow("") }).collectAsState()
    val focusedBellCount by (focusedSession?.bellCount ?: remember { MutableStateFlow(0) }).collectAsState()
    val focusedProgress by (focusedSession?.progressState ?: remember { MutableStateFlow(TerminalProgressState()) }).collectAsState()
    val focusedLiveCwd by (focusedSession?.liveCwd ?: remember { MutableStateFlow(cwd) }).collectAsState()
    val focusedTranscriptPath by (focusedSession?.transcriptPath ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val focusedMediaReq by (focusedSession?.pendingMediaRequest ?: remember { MutableStateFlow<Pair<Long, TerminalMediaRequest>?>(null) }).collectAsState()
    val focusedFrameVer by (focusedSession?.engine?.frameVersion ?: remember { MutableStateFlow(0L) }).collectAsState()
    val usable = focusedSession != null && focusedStatus == TermSessionStatus.RUNNING

    val latestPrimary by rememberUpdatedState(primarySession)
    val latestSecondary by rememberUpdatedState(secondarySession)
    val latestFocused by rememberUpdatedState(focusedSession)
    val latestBroadcast by rememberUpdatedState(broadcastInput)

    fun inputTargets(): List<LiveTerminalSession> {
        val p = latestPrimary
        val s = latestSecondary
        if (latestBroadcast && p != null && s != null) {
            return listOf(p, s).filter { it.status.value == TermSessionStatus.RUNNING }
        }
        return listOfNotNull(latestFocused?.takeIf { it.status.value == TermSessionStatus.RUNNING })
    }

    val prediction = remember(focusedLiveDraft, history, showCommandBar, commandLine) {
        val src = if (showCommandBar && commandLine.isNotBlank()) commandLine else focusedLiveDraft
        PsReadLinePredictor.predict(src, history)
    }

    // 异步防抖全文搜索（支持大小写/正则/全词，在 Dispatchers.Default 执行，绝不卡顿 UI 线程）
    var lastJumpedKey by remember { mutableStateOf("") }
    LaunchedEffect(showSearchBar, searchQuery, searchOptions, focusedSession?.id, focusedFrameVer) {
        val target = focusedSession
        if (!showSearchBar || searchQuery.isBlank() || target == null) {
            searchMatches = emptyList()
            searchActiveIdx = 0
            lastJumpedKey = ""
            return@LaunchedEffect
        }
        delay(90L)
        val opts = searchOptions
        val found = withContext(Dispatchers.Default) { target.engine.search(searchQuery, opts) }
        searchMatches = found
        val curKey = "$searchQuery|${opts.caseSensitive}|${opts.useRegex}|${opts.wholeWord}"
        if (found.isEmpty()) {
            searchActiveIdx = 0
        } else if (curKey != lastJumpedKey || searchActiveIdx !in found.indices) {
            lastJumpedKey = curKey
            val idx = found.lastIndex
            searchActiveIdx = idx
            val match = found[idx]
            val sb = target.engine.scrollbackSize
            scrollOffsets[target.id] = (sb - match.absRow + target.engine.rows / 2).coerceIn(0, sb)
        }
    }

    fun jumpToSearchMatch(idx: Int) {
        val target = focusedSession ?: return
        val match = searchMatches.getOrNull(idx) ?: return
        searchActiveIdx = idx
        val sb = target.engine.scrollbackSize
        val targetOffset = (sb - match.absRow + target.engine.rows / 2).coerceIn(0, sb)
        scrollOffsets[target.id] = targetOffset
    }

    // 切换聚焦会话时清理未上屏拼音与粘滞修饰键，防止串台
    LaunchedEffect(focusedSession?.id) {
        unifiedImeView?.clearComposingBuffer(restartIme = true)
        composingText = ""
        stickyModifiers.resetAll()
    }

    // 打开搜索栏时让出原生 IME 焦点，关闭时平滑归还焦点
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            unifiedImeView?.clearFocus()
        } else if (focusedSession != null) {
            delay(40L)
            unifiedImeView?.requestFocus()
        }
    }

    // 远端 BEL 触觉与视觉响铃反馈
    var lastObservedBell by remember(focusedSession?.id) { mutableIntStateOf(focusedBellCount) }
    LaunchedEffect(focusedBellCount, bellHapticEnabled, visualBellEnabled) {
        if (focusedBellCount > lastObservedBell) {
            if (bellHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (visualBellEnabled) visualBellPulse++
        }
        lastObservedBell = focusedBellCount
    }

    // 退出分屏时自动关闭广播模式与放大态
    LaunchedEffect(isSplitActive) {
        if (!isSplitActive) {
            zoomedPaneIndex = -1
            broadcastInput = false
        }
    }

    DisposableEffect(view, appWasDark, keepScreenOn, scheme.isLight) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = scheme.isLight
        controller?.isAppearanceLightNavigationBars = scheme.isLight
        val prevKeep = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = prevKeep
            controller?.isAppearanceLightStatusBars = !appWasDark
            controller?.isAppearanceLightNavigationBars = !appWasDark
        }
    }

    BackHandler(enabled = !showSnippets && !showQuickConnect && !showAppearance && !showHistorySheet && !showMediaUrlDialog && sftpHost == null && detectedUrls == null && openingEditorFile == null && openingRichViewerFile == null && activeVideo == null) {
        if (showSearchBar) { showSearchBar = false; searchQuery = "" }
        else onDismiss()
    }

    fun openNewLocal(targetCwd: String, attachToSplit: Boolean = false) {
        errorBanner = null
        try {
            val s = manager.openLocal(targetCwd)
            if (attachToSplit && primarySession != null) {
                splitSecondId = s.id
                focusedPaneIndex = 1
            } else {
                selectedId = s.id
                focusedPaneIndex = 0
            }
        } catch (e: Exception) {
            errorBanner = e.message ?: "启动本地终端失败"
        }
    }

    fun openNewSsh(host: SshHostConfig, attachToSplit: Boolean = false) {
        errorBanner = null
        try {
            val s = manager.openSsh(host, cwd)
            if (attachToSplit && primarySession != null) {
                splitSecondId = s.id
                focusedPaneIndex = 1
            } else {
                selectedId = s.id
                focusedPaneIndex = 0
            }
        } catch (e: Exception) {
            errorBanner = e.message ?: "连接 SSH 失败"
        }
    }

    LaunchedEffect(autoBootDone, runtime.ready, initialSessionId, sshHost?.id) {
        if (autoBootDone) return@LaunchedEffect
        val list = manager.sessions.value
        if (initialSessionId != null && list.any { it.id == initialSessionId }) {
            selectedId = initialSessionId
            autoBootDone = true
            return@LaunchedEffect
        }
        if (sshHost != null) {
            val existing = manager.findReusableSession(cwd, sshHost)
            selectedId = existing?.id ?: runCatching { manager.openSsh(sshHost, cwd).id }
                .onFailure { errorBanner = it.message }.getOrNull()
            autoBootDone = true
            return@LaunchedEffect
        }
        val existingLocal = manager.findReusableSession(cwd, null)
        if (existingLocal != null) {
            selectedId = existingLocal.id
            autoBootDone = true
            return@LaunchedEffect
        }
        if (runtime.ready) {
            selectedId = runCatching { manager.openLocal(cwd).id }
                .onFailure { errorBanner = it.message }.getOrNull()
            autoBootDone = true
        } else if (list.isNotEmpty()) {
            selectedId = list.last().id
            autoBootDone = true
        }
    }

    fun closeSession(targetId: String) {
        val before = manager.sessions.value
        val idx = before.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        scrollOffsets.remove(targetId)
        manager.closeSession(targetId)
        val remaining = manager.sessions.value
        if (splitSecondId == targetId) {
            splitSecondId = null
            focusedPaneIndex = 0
            zoomedPaneIndex = -1
        }
        if (selectedId == targetId) {
            val nextPrimary = remaining.getOrNull(idx.coerceAtMost(remaining.lastIndex))?.id
            selectedId = nextPrimary
            if (nextPrimary != null && nextPrimary == splitSecondId) {
                splitSecondId = null
                focusedPaneIndex = 0
                zoomedPaneIndex = -1
            }
        }
    }

    fun requestCloseSession(session: LiveTerminalSession) {
        if (session.isActive) confirmEndTarget = session else closeSession(session.id)
    }

    fun copyToClipboard(text: String, notice: String = "已复制到剪贴板") {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Solbraid Terminal", text))
        app.conversations.notify(notice)
    }

    fun runWithSafeGuard(cmd: String, isRemote: Boolean, action: () -> Unit) {
        if (safeGuardEnabled) {
            val warn = TerminalSafeGuard.inspect(cmd, isRemote)
            if (warn != null) {
                pendingDangerAction = warn to action
                return
            }
        }
        action()
    }

    fun triggerPaste() {
        val target = focusedSession ?: return
        val text = app.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        when {
            text.isEmpty() -> app.conversations.notify("剪贴板为空")
            text.length > 128 * 1024 -> errorBanner = "粘贴内容过长，请分段输入"
            text.contains('\n') || text.contains('\r') -> pasteDraft = text
            else -> runWithSafeGuard(text, target.isSsh) { target.engine.sendPaste(text) }
        }
    }

    fun resolveLocalTerminalRef(rawPath: String, baseDir: String): FileRef {
        val trimmed = rawPath.trim()
        val resolved = when {
            trimmed.startsWith("~/") -> normalizeVfsPath("/home/agent/${trimmed.removePrefix("~/")}")
            trimmed.startsWith("/") -> normalizeVfsPath(trimmed)
            else -> normalizeVfsPath("${baseDir.trimEnd('/')}/$trimmed")
        }
        val scope = when {
            resolved.startsWith("/storage/emulated/") || resolved.startsWith("/sdcard/") -> FileScope.ANDROID_STORAGE
            resolved.startsWith("/home/agent") -> FileScope.LINUX_WORKSPACE
            else -> FileScope.LINUX_ROOT
        }
        return FileRef(scope, resolved)
    }

    fun openExternalUrl(url: String) {
        val trimmed = url.trim()
        if (isTerminalVideoSource(trimmed)) {
            val title = trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "在线视频" }
            activeVideo = TerminalVideoSource(title = title, remoteUrl = trimmed)
            return
        }
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { app.conversations.notify("无法打开链接：$trimmed") }
    }

    fun openTerminalMediaInput(raw: String, action: String = "auto", kind: String = "auto", titleOverride: String = "") {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val title = titleOverride.ifBlank { trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "在线媒体" } }
            if (action == "play" || kind == "video" || isTerminalVideoSource(trimmed)) {
                activeVideo = TerminalVideoSource(title = title, remoteUrl = trimmed)
            } else {
                openExternalUrl(trimmed)
            }
            return
        }
        val ref = resolveLocalTerminalRef(trimmed, focusedLiveCwd)
        if (action == "play" || kind == "video" || isTerminalVideoSource(ref.path)) {
            activeVideo = TerminalVideoSource(title = titleOverride.ifBlank { ref.name }, localFileRef = ref)
        } else {
            openingRichViewerFile = ref
        }
    }

    LaunchedEffect(focusedMediaReq) {
        val (_, req) = focusedMediaReq ?: return@LaunchedEffect
        focusedSession?.pendingMediaRequest?.value = null
        openTerminalMediaInput(
            raw = req.source,
            action = req.action,
            kind = req.kind,
            titleOverride = req.title,
        )
    }

    fun openSmartFileLink(span: TerminalLinkSpan) {
        val sess = focusedSession
        val raw = span.url.trim()
        val baseDir = (sess?.liveCwd?.value ?: cwd).trimEnd('/')
        val ref = resolveLocalTerminalRef(raw, baseDir)
        if (sess != null && sess.isSsh) {
            val copyStr = if (span.fileLine != null) "${ref.path}:${span.fileLine}" else ref.path
            copyToClipboard(copyStr, "已复制远端路径：$copyStr")
            return
        }
        when {
            isTerminalVideoSource(ref.path) -> activeVideo = TerminalVideoSource(title = ref.name, localFileRef = ref)
            span.fileLine == null && isTerminalRichMediaPath(ref.path) -> openingRichViewerFile = ref
            else -> openingEditorFile = ref to span.fileLine
        }
    }

    fun jumpPrompt(previous: Boolean) {
        val target = focusedSession ?: return
        val sb = target.engine.scrollbackSize
        val curTopAbs = (sb - (scrollOffsets[target.id] ?: 0)).coerceAtLeast(0)
        val destAbs = if (previous) target.engine.findPreviousPromptRow(curTopAbs) else target.engine.findNextPromptRow(curTopAbs)
        if (destAbs == null) {
            app.conversations.notify(if (previous) "已到达最早一条命令" else "已到达最新命令")
            return
        }
        scrollOffsets[target.id] = (sb - destAbs).coerceIn(0, sb)
    }

    fun submitCommandLine() {
        val targets = inputTargets()
        val line = commandLine
        if (targets.isEmpty() || line.isEmpty()) return
        runWithSafeGuard(line, targets.any { it.isSsh }) {
            commandLine = ""
            historyCursor = -1
            manager.recordCommandHistory(line)
            targets.forEach { t ->
                scrollOffsets[t.id] = 0
                t.engine.notifyCommandSubmitted(line)
                t.sendUserRaw(line + "\r")
            }
        }
    }

    fun updateFontSize(nextVal: Int) {
        val clamped = nextVal.coerceIn(8, 28)
        fontSize = clamped
        termPrefs.edit().putInt("font_size_v2", clamped).apply()
    }

    CompositionLocalProvider(LocalGaliaisColors provides darkTerminalGaliaisColors()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.chromeColor)
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            // 1. 44dp 单排 Windows Terminal 风格标签顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(scheme.chromeColor)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = scheme.fgColor, modifier = Modifier.size(18.dp))
                }

                val tabListState = rememberLazyListState()
                val activeIdx = remember(sessions, selectedId) { sessions.indexOfFirst { it.id == selectedId } }
                LaunchedEffect(activeIdx, sessions.size) {
                    if (activeIdx >= 0) tabListState.animateScrollToItem((activeIdx - 1).coerceAtLeast(0))
                }
                if (sessions.isEmpty()) {
                    Text(
                        "PowerShell 终端",
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.fgColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                } else {
                    LazyRow(
                        state = tabListState,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            val sStatus by session.status.collectAsState()
                            val dynTitle by session.dynamicTitle.collectAsState()
                            val active = session.id == focusedSession?.id
                            val inSplit = session.id == splitSecondId
                            val dotColor = when (sStatus) {
                                TermSessionStatus.CONNECTING -> TermAmber
                                TermSessionStatus.RUNNING -> TermGreen
                                else -> TermRed
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (active) scheme.keyColor else scheme.barColor)
                                    .border(
                                        if (active) 1.dp else 0.75.dp,
                                        if (active) scheme.accentColor.copy(alpha = 0.75f) else scheme.borderColor,
                                        RoundedCornerShape(7.dp),
                                    )
                                    .selectable(selected = active, role = Role.Tab) {
                                        if (inSplit) {
                                            focusedPaneIndex = 1
                                        } else {
                                            selectedId = session.id
                                            focusedPaneIndex = 0
                                        }
                                        if (zoomedPaneIndex >= 0) zoomedPaneIndex = focusedPaneIndex
                                        errorBanner = null
                                        stickyModifiers.resetAll()
                                    }
                                    .padding(start = 7.dp, end = 3.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                                if (session.isSsh) {
                                    Text("SSH", fontFamily = Mono, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = scheme.accentColor)
                                }
                                Text(
                                    dynTitle.ifBlank { session.title },
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) scheme.fgColor else TermMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 104.dp),
                                )
                                Box(
                                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { requestCloseSession(session) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(GaliaisIcons.Close, contentDescription = "关闭会话", tint = TermMuted, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }

                PluginSlotActionBar(
                    app = app,
                    slotName = "terminal.toolbar",
                    cwd = focusedSession?.cwd ?: cwd,
                    darkStyle = true,
                    onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                    onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                )

                // PowerShell Transcript 录制中红色 REC 胶囊（点击立即停止）
                if (focusedTranscriptPath != null && focusedSession != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33F87171))
                            .border(0.75.dp, TermRed, RoundedCornerShape(6.dp))
                            .clickable {
                                val saved = focusedSession.stopTranscript()
                                if (saved != null) app.conversations.notify("会话日志已保存：${saved.substringAfterLast('/')}")
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("REC", fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TermRed)
                    }
                }

                // PowerShell OSC 9;4 实时进度百分比徽标
                if (focusedProgress.mode in listOf(1, 2, 4)) {
                    val pctColor = when (focusedProgress.mode) {
                        2 -> TermRed
                        4 -> TermAmber
                        else -> scheme.accentColor
                    }
                    Text(
                        "${focusedProgress.percent}%",
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pctColor,
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }

                // 全文搜索快捷按钮（全顶栏按钮统一 34dp / 18dp 矢量图标对齐）
                IconButton(
                    enabled = focusedSession != null,
                    onClick = { showSearchBar = !showSearchBar; if (!showSearchBar) searchQuery = "" },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(GaliaisIcons.Search, contentDescription = "搜索输出", tint = if (showSearchBar) scheme.accentColor else scheme.fgColor, modifier = Modifier.size(18.dp))
                }

                // 当前为 SSH 会话时提供一键同主机 SFTP 按钮（联动 OSC 7/9;9 实时工作目录）
                focusedSession?.sshHost?.let { activeSshHost ->
                    IconButton(
                        onClick = {
                            val dir = focusedLiveCwd.takeIf { it.startsWith("/") } ?: activeSshHost.defaultPath
                            sftpHost = activeSshHost.copy(defaultPath = dir)
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(GaliaisIcons.Folder, contentDescription = "打开 SFTP", tint = scheme.accentColor, modifier = Modifier.size(18.dp))
                    }
                }

                // 新建终端菜单（本地终端自动继承 OSC 7 / 9;9 实时工作目录）
                val activeLocalDir = focusedLiveCwd.takeIf { focusedSession != null && !focusedSession.isSsh && it.startsWith("/") } ?: cwd
                val extraProjects = remember(appSettings.projects, cwd) { appSettings.projects.filter { it != cwd } }
                Box {
                    IconButton(enabled = sessions.size < 8, onClick = { showNewMenu = true }, modifier = Modifier.size(34.dp)) {
                        Icon(GaliaisIcons.Add, contentDescription = "新建终端", tint = if (sessions.size < 8) scheme.fgColor else TermMuted, modifier = Modifier.size(18.dp))
                    }
                    GaliaisDropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow(
                                title = "新建本地终端",
                                subtitle = if (runtime.ready) activeLocalDir.replace("/home/agent", "~") else "需先启动本地运行环境",
                                icon = GaliaisIcons.Terminal,
                                enabled = runtime.ready,
                                onClick = { showNewMenu = false; openNewLocal(activeLocalDir) },
                            )
                            GaliaisMenuRow(
                                title = "快捷连接 SSH…",
                                subtitle = "user@host:port 原生直连",
                                icon = GaliaisIcons.Server,
                                onClick = { showNewMenu = false; showQuickConnect = true },
                            )
                            if (primarySession != null && runtime.ready && sessions.size < 8) {
                                GaliaisMenuRow(
                                    title = "分屏新建本地终端",
                                    subtitle = "双窗格独立滚动与并行监控",
                                    icon = if (splitAxis == "horizontal") GaliaisIcons.SplitHorizontal else GaliaisIcons.SplitVertical,
                                    onClick = { showNewMenu = false; openNewLocal(activeLocalDir, attachToSplit = true) },
                                )
                            }
                            if (runtime.ready && extraProjects.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                Text("项目目录", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                                extraProjects.take(4).forEach { dir ->
                                    GaliaisMenuRow(
                                        title = projectDisplayName(dir),
                                        subtitle = dir.replace("/home/agent", "~"),
                                        icon = GaliaisIcons.Folder,
                                        onClick = { showNewMenu = false; openNewLocal(dir) },
                                    )
                                }
                            }
                            if (sshHosts.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                Text("SSH 主机簿", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                                sshHosts.take(6).forEach { host ->
                                    GaliaisMenuRow(
                                        title = host.name,
                                        subtitle = "${host.user}@${host.host}:${host.port}",
                                        icon = GaliaisIcons.Server,
                                        onClick = { showNewMenu = false; openNewSsh(host) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 更多选项菜单（主题/光标、分屏控制、广播输入、导出日志、复制粘贴、URL、清屏与重置）
                Box {
                    IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(34.dp)) {
                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "终端选项", tint = scheme.fgColor, modifier = Modifier.size(18.dp))
                    }
                    GaliaisDropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow("PowerShell 主题与光标", subtitle = "${scheme.label} · ${cursorStyle.label}", icon = GaliaisIcons.Palette, onClick = { showMoreMenu = false; showAppearance = true })
                            GaliaisMenuRow("播放视频 / 渲染媒体…", subtitle = "支持本地或网络 MP4/HLS、PDF、MD、图片与 Sixel/动图", icon = GaliaisIcons.Play, onClick = { showMoreMenu = false; showMediaUrlDialog = true })
                            GaliaisMenuRow("命令历史模糊检索", subtitle = "支持通配符 * / ? 与智能大小写", icon = GaliaisIcons.History, enabled = usable, onClick = { showMoreMenu = false; showHistorySheet = true })
                            if (focusedSession != null) {
                                val recording = focusedTranscriptPath != null
                                GaliaisMenuRow(
                                    title = if (recording) "停止录制会话日志 (REC)" else "录制会话日志 (Transcript)",
                                    subtitle = if (recording) "正在实时剥离 ANSI 写入 .log" else "PowerShell 标准格式落盘，不限回滚行数",
                                    icon = if (recording) GaliaisIcons.Stop else GaliaisIcons.Play,
                                    selected = recording,
                                    onClick = {
                                        showMoreMenu = false
                                        if (recording) {
                                            val path = focusedSession.stopTranscript()
                                            if (path != null) app.conversations.notify("已保存会话日志：${path.substringAfterLast('/')}")
                                        } else {
                                            val path = focusedSession.startTranscript(manager.transcriptOutputDir())
                                            app.conversations.notify("开始录制日志：${path.substringAfterLast('/')}")
                                        }
                                    },
                                )
                                GaliaisMenuRow("跳至上一条命令", icon = GaliaisIcons.ArrowUp, onClick = { showMoreMenu = false; jumpPrompt(previous = true) })
                                GaliaisMenuRow("跳至下一条命令", icon = GaliaisIcons.ArrowDown, onClick = { showMoreMenu = false; jumpPrompt(previous = false) })
                            }
                            if (isSplitActive) {
                                GaliaisMenuRow(
                                    title = if (splitAxis == "horizontal") "切为上下分屏" else "切为左右分屏",
                                    icon = if (splitAxis == "horizontal") GaliaisIcons.SplitVertical else GaliaisIcons.SplitHorizontal,
                                    onClick = { showMoreMenu = false; splitAxis = if (splitAxis == "horizontal") "vertical" else "horizontal" },
                                )
                                GaliaisMenuRow(
                                    title = if (broadcastInput) "关闭双窗格广播输入" else "开启双窗格广播输入",
                                    subtitle = "键入命令同时发送至两个分屏会话",
                                    icon = GaliaisIcons.SwapHoriz,
                                    selected = broadcastInput,
                                    onClick = { showMoreMenu = false; broadcastInput = !broadcastInput },
                                )
                                GaliaisMenuRow("关闭分屏", icon = GaliaisIcons.Close, onClick = { showMoreMenu = false; splitSecondId = null; focusedPaneIndex = 0; zoomedPaneIndex = -1; broadcastInput = false })
                            } else if (sessions.size >= 2) {
                                val other = sessions.firstOrNull { it.id != selectedId }
                                if (other != null) {
                                    GaliaisMenuRow("与「${other.title}」分屏", icon = GaliaisIcons.SplitVertical, onClick = { showMoreMenu = false; splitSecondId = other.id })
                                }
                            }
                            Hairline(Modifier.padding(vertical = 2.dp))
                            GaliaisMenuRow("复制当前屏输出", icon = GaliaisIcons.Copy, enabled = focusedSession != null, onClick = {
                                showMoreMenu = false
                                focusedSession?.let { s ->
                                    val txt = s.engine.extractAllVisible(scrollOffsets[s.id] ?: 0)
                                    if (txt.isNotEmpty()) copyToClipboard(txt, "已复制当前终端屏幕内容")
                                    else app.conversations.notify("当前屏幕没有可复制的内容")
                                }
                            })
                            GaliaisMenuRow("复制全部回滚日志", icon = GaliaisIcons.Download, enabled = focusedSession != null, onClick = {
                                showMoreMenu = false
                                focusedSession?.let { s ->
                                    val full = s.engine.exportFullTranscript()
                                    if (full.isNotEmpty()) copyToClipboard(full, "已复制全部回滚输出（${full.length} 字符）")
                                    else app.conversations.notify("当前会话尚无输出记录")
                                }
                            })
                            GaliaisMenuRow("粘贴剪贴板", icon = GaliaisIcons.ClipboardPaste, enabled = usable, onClick = { showMoreMenu = false; triggerPaste() })
                            GaliaisMenuRow("提取终端链接 (URL)", icon = GaliaisIcons.Link, enabled = focusedSession != null, onClick = {
                                showMoreMenu = false
                                val urls = focusedSession?.let { s -> s.engine.detectVisibleUrls(scrollOffsets[s.id] ?: 0).map { it.url }.distinct() }.orEmpty()
                                if (urls.isEmpty()) app.conversations.notify("当前输出中未检测到 URL") else detectedUrls = urls
                            })
                            GaliaisMenuRow("清屏 (Ctrl+L)", icon = GaliaisIcons.Eraser, enabled = usable, onClick = {
                                showMoreMenu = false
                                focusedSession?.let { s -> scrollOffsets[s.id] = 0; s.sendUserRaw("\u000c") }
                            })
                            GaliaisMenuRow("重置终端状态 (RIS)", icon = GaliaisIcons.Refresh, enabled = focusedSession != null, onClick = {
                                showMoreMenu = false
                                focusedSession?.let { s ->
                                    scrollOffsets[s.id] = 0
                                    s.clearInputDraft()
                                    s.progressState.value = TerminalProgressState()
                                    s.engine.fullReset()
                                    app.conversations.notify("已重置终端画布与解析状态")
                                }
                            })
                            Hairline(Modifier.padding(vertical = 2.dp))
                            GaliaisMenuRow(if (showCommandBar) "隐藏 PowerShell 着色直发栏" else "显示 PowerShell 着色直发栏", icon = GaliaisIcons.Edit, onClick = { showMoreMenu = false; showCommandBar = !showCommandBar })
                            GaliaisMenuRow(if (showSecondRow) "收起方向/翻页键排" else "展开方向/翻页键排", icon = GaliaisIcons.Keyboard, onClick = {
                                showMoreMenu = false
                                showSecondRow = !showSecondRow
                                termPrefs.edit().putBoolean("show_second_row", showSecondRow).apply()
                            })
                            GaliaisMenuRow(if (showFnRow) "收起 Shift / F1–F12 功能排" else "展开 Shift / F1–F12 功能排", icon = GaliaisIcons.Code, onClick = {
                                showMoreMenu = false
                                showFnRow = !showFnRow
                                termPrefs.edit().putBoolean("show_fn_row", showFnRow).apply()
                            })
                            GaliaisMenuRow(if (keepScreenOn) "关闭屏幕常亮" else "开启屏幕常亮", icon = GaliaisIcons.Sun, onClick = {
                                showMoreMenu = false
                                keepScreenOn = !keepScreenOn
                                termPrefs.edit().putBoolean("keep_screen_on", keepScreenOn).apply()
                            })
                            if (focusedSession != null) {
                                GaliaisMenuRow("原位重连 (${focusedDims.first}×${focusedDims.second})", icon = GaliaisIcons.Refresh, onClick = {
                                    showMoreMenu = false
                                    runCatching {
                                        manager.reconnect(focusedSession.id)?.let { next ->
                                            if (focusedPaneIndex == 1 && splitSecondId == focusedSession.id) splitSecondId = next.id
                                            else selectedId = next.id
                                        }
                                    }.onFailure { errorBanner = it.message }
                                })
                            }
                            if (sessions.isNotEmpty()) {
                                Hairline(Modifier.padding(vertical = 2.dp))
                                GaliaisMenuRow("结束全部终端 (${sessions.size})", icon = GaliaisIcons.Trash, danger = true, onClick = { showMoreMenu = false; confirmEndAll = true })
                            }
                        }
                    }
                }
            }

            if (showSearchBar) {
                TerminalSearchBar(
                    scheme = scheme,
                    query = searchQuery,
                    options = searchOptions,
                    matchCount = searchMatches.size,
                    activeIndex = searchActiveIdx,
                    onQueryChange = { searchQuery = it },
                    onOptionsChange = { searchOptions = it },
                    onPrev = { if (searchMatches.isNotEmpty()) jumpToSearchMatch((searchActiveIdx - 1 + searchMatches.size) % searchMatches.size) },
                    onNext = { if (searchMatches.isNotEmpty()) jumpToSearchMatch((searchActiveIdx + 1) % searchMatches.size) },
                    onClose = { showSearchBar = false; searchQuery = "" },
                )
            }

            // PowerShell 7 / Windows Terminal OSC 9;4 原生进度指示条（兼连接态不确定进度）
            Box(Modifier.fillMaxWidth().height(2.5.dp).background(scheme.bgColor)) {
                when {
                    focusedStatus == TermSessionStatus.CONNECTING || focusedProgress.mode == 3 -> {
                        GaliaisProgressBar(modifier = Modifier.fillMaxSize())
                    }
                    focusedProgress.mode in 1..4 -> {
                        val barColor = when (focusedProgress.mode) {
                            2 -> TermRed
                            4 -> TermAmber
                            else -> scheme.accentColor
                        }
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((focusedProgress.percent.coerceIn(0, 100) / 100f).coerceAtLeast(0.02f))
                                .background(barColor),
                        )
                    }
                }
            }

            errorBanner?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF33181D)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(msg, Modifier.weight(1f), color = TermRed, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { errorBanner = null }) { Text("关闭", color = scheme.accentColor) }
                }
            }

            if (focusedSession != null && (focusedStatus == TermSessionStatus.EXITED || focusedStatus == TermSessionStatus.ERROR)) {
                Row(
                    Modifier.fillMaxWidth().background(scheme.barColor).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        focusedError ?: "会话已退出（退出码 ${focusedExitCode ?: 0}）",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focusedStatus == TermSessionStatus.ERROR) TermRed else TermMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        onClick = {
                            runCatching {
                                manager.reconnect(focusedSession.id)?.let { next ->
                                    if (focusedPaneIndex == 1 && splitSecondId == focusedSession.id) splitSecondId = next.id
                                    else selectedId = next.id
                                }
                            }.onFailure { errorBanner = it.message }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = scheme.accentColor,
                    ) {
                        Text("重连", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.bgColor, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }

            // 2. 纯 Compose PowerShell 终端画布区（显式稳定分屏轴向 + 可拖拽分割线 + 单一全局 IME 视图 + 悬浮预测条）
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(scheme.bgColor),
            ) {
                val containerPxW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val containerPxH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val isHorizontalSplit = splitAxis == "horizontal"

                // 全终端唯一共享 IME 桥接视图（位于分屏分支外部，切换单/分屏、纵/横分屏与窗格永不断连、永不抢焦点）
                AndroidView(
                    factory = { ctx ->
                        TerminalImeInputView(ctx).also { v -> unifiedImeView = v }
                    },
                    update = { v ->
                        unifiedImeView = v
                        v.modifiers = stickyModifiers
                        v.inputEnabled = usable && !showSearchBar
                        v.onComposingChanged = { composingText = it }
                        v.onDispatchKey = { key, ctrl, alt, shift ->
                            inputTargets().forEach { t ->
                                scrollOffsets[t.id] = 0
                                t.engine.sendKey(key, ctrl = ctrl, alt = alt, shift = shift)
                            }
                        }
                        v.onDispatchChar = { cp, ctrl, alt ->
                            inputTargets().forEach { t ->
                                scrollOffsets[t.id] = 0
                                t.engine.sendChar(cp, ctrl = ctrl, alt = alt)
                            }
                        }
                        v.onDispatchText = { txt ->
                            inputTargets().forEach { t ->
                                scrollOffsets[t.id] = 0
                                t.engine.sendText(txt)
                            }
                        }
                        v.onInputSent = {
                            latestFocused?.let { s -> if ((scrollOffsets[s.id] ?: 0) != 0) scrollOffsets[s.id] = 0 }
                        }
                        v.onShortcut = { action ->
                            when (action) {
                                "copy" -> latestFocused?.let { s ->
                                    val txt = s.engine.extractAllVisible(scrollOffsets[s.id] ?: 0)
                                    if (txt.isNotEmpty()) copyToClipboard(txt, "已复制当前屏幕内容")
                                }
                                "paste" -> triggerPaste()
                                "search" -> { showSearchBar = !showSearchBar; if (!showSearchBar) searchQuery = "" }
                                "history" -> showHistorySheet = true
                                "zoom_in" -> updateFontSize(fontSize + 1)
                                "zoom_out" -> updateFontSize(fontSize - 1)
                                "zoom_reset" -> updateFontSize(11)
                            }
                        }
                    },
                    modifier = Modifier.size(1.dp).alpha(0f),
                )

                val renderSplitPane: @Composable (LiveTerminalSession, Int, Boolean, Modifier) -> Unit = { sess, idx, showMiniHeader, mod ->
                    val isFocused = focusedPaneIndex == idx || !isSplitActive
                    val st by sess.status.collectAsState()
                    val pTitle by sess.dynamicTitle.collectAsState()
                    val pDims by sess.dimensions.collectAsState()
                    key(sess.id) {
                        Column(
                            modifier = mod.let { m ->
                                if (showMiniHeader) {
                                    m.border(if (isFocused) 1.25.dp else 0.5.dp, if (isFocused) scheme.accentColor else scheme.borderColor)
                                } else m
                            },
                        ) {
                            if (showMiniHeader) {
                                TerminalSplitPaneHeader(
                                    title = pTitle.ifBlank { sess.title },
                                    dims = pDims,
                                    scheme = scheme,
                                    focused = isFocused,
                                    isHorizontalSplit = isHorizontalSplit,
                                    isZoomed = zoomedPaneIndex == idx,
                                    broadcastActive = broadcastInput,
                                    onFocus = {
                                        focusedPaneIndex = idx
                                        unifiedImeView?.focusAndShowIme()
                                    },
                                    onToggleAxis = { splitAxis = if (isHorizontalSplit) "vertical" else "horizontal" },
                                    onToggleZoom = {
                                        zoomedPaneIndex = if (zoomedPaneIndex == idx) -1 else idx
                                        focusedPaneIndex = idx
                                    },
                                    onClosePane = { requestCloseSession(sess) },
                                )
                            }
                            PowerShellTerminalCanvas(
                                engine = sess.engine,
                                fonts = manager.fonts,
                                scheme = scheme,
                                cursorStyle = cursorStyle,
                                fontSizeSp = fontSize,
                                boxDrawingEnabled = boxDrawingEnabled,
                                minContrastEnabled = minContrastEnabled,
                                secretRedactionMode = secretRedactionMode,
                                visualBellPulse = if (isFocused) visualBellPulse else 0,
                                inputEnabled = st == TermSessionStatus.RUNNING,
                                focusedPane = isFocused,
                                searchMatches = if (isFocused) searchMatches else emptyList(),
                                activeSearchIndex = if (isFocused) searchActiveIdx else 0,
                                scrollOffsetLines = scrollOffsets[sess.id] ?: 0,
                                composingText = if (isFocused) composingText else "",
                                onScrollOffsetChange = { scrollOffsets[sess.id] = it },
                                onFontSizePinch = ::updateFontSize,
                                onCopyText = { copyToClipboard(it) },
                                onOpenUrl = ::openExternalUrl,
                                onOpenFileLink = ::openSmartFileLink,
                                onFocusPane = { focusedPaneIndex = idx },
                                onRequestImeFocus = {
                                    focusedPaneIndex = idx
                                    unifiedImeView?.focusAndShowIme()
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }

                when {
                    primarySession != null && secondarySession != null && zoomedPaneIndex == 0 -> {
                        renderSplitPane(primarySession, 0, true, Modifier.fillMaxSize())
                    }
                    primarySession != null && secondarySession != null && zoomedPaneIndex == 1 -> {
                        renderSplitPane(secondarySession, 1, true, Modifier.fillMaxSize())
                    }
                    primarySession != null && secondarySession != null -> {
                        val w1 = splitRatio.coerceIn(0.25f, 0.75f)
                        val w2 = (1f - w1).coerceIn(0.25f, 0.75f)
                        if (isHorizontalSplit) {
                            Row(Modifier.fillMaxSize()) {
                                renderSplitPane(primarySession, 0, true, Modifier.weight(w1).fillMaxHeight())
                                Box(
                                    modifier = Modifier
                                        .width(10.dp)
                                        .fillMaxHeight()
                                        .background(scheme.chromeColor)
                                        .pointerInput(containerPxW) {
                                            detectTapGestures(onDoubleTap = { splitRatio = 0.5f })
                                        }
                                        .pointerInput(containerPxW) {
                                            detectDragGestures { change, drag ->
                                                change.consume()
                                                splitRatio = (splitRatio + drag.x / containerPxW).coerceIn(0.25f, 0.75f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.width(3.dp).height(28.dp).clip(CircleShape).background(scheme.borderColor))
                                }
                                renderSplitPane(secondarySession, 1, true, Modifier.weight(w2).fillMaxHeight())
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                renderSplitPane(primarySession, 0, true, Modifier.weight(w1).fillMaxWidth())
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .background(scheme.chromeColor)
                                        .pointerInput(containerPxH) {
                                            detectTapGestures(onDoubleTap = { splitRatio = 0.5f })
                                        }
                                        .pointerInput(containerPxH) {
                                            detectDragGestures { change, drag ->
                                                change.consume()
                                                splitRatio = (splitRatio + drag.y / containerPxH).coerceIn(0.25f, 0.75f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(scheme.borderColor))
                                }
                                renderSplitPane(secondarySession, 1, true, Modifier.weight(w2).fillMaxWidth())
                            }
                        }
                    }
                    primarySession != null -> {
                        renderSplitPane(primarySession, 0, false, Modifier.fillMaxSize())
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("当前无活跃终端会话", style = MaterialTheme.typography.titleMedium, color = scheme.fgColor)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (runtime.ready) "可新建本地 Linux 终端，或原生直连任意 SSH 主机。"
                                else "本地 Linux 运行环境未启动，你仍可直接原生连接 SSH 主机，或先启动本地环境。",
                                style = MaterialTheme.typography.bodySmall,
                                color = TermMuted,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (runtime.ready) PrimaryPillButton("新建本地终端", onClick = { openNewLocal(cwd) })
                                else SecondaryPillButton("启动本地环境", enabled = runtime.bound && !runtime.busy, onClick = app.runtime::start)
                                PrimaryPillButton("快捷连 SSH", onClick = { showQuickConnect = true })
                            }
                        }
                    }
                }

                // 零高度抖动 PSReadLine 悬浮预测建议胶囊（位于视口内部底部，绝不改变终端行列尺寸）
                val activeDraft = if (showCommandBar && commandLine.isNotBlank()) commandLine else focusedLiveDraft
                if (usable && prediction != null && prediction.length > activeDraft.trimStart().length) {
                    PsReadLineOverlayPill(
                        scheme = scheme,
                        liveDraft = activeDraft,
                        prediction = prediction,
                        onAccept = { full ->
                            if (showCommandBar && commandLine.isNotBlank()) commandLine = full
                            else inputTargets().forEach { it.acceptPrediction(full) }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // 终端内置硬件加速悬浮画中画 / 全屏视频播放器（支持边看边输入，兼容 OSC 1338 / 点击链接 / 手动输入）
                activeVideo?.let { src ->
                    TerminalVideoPlayerOverlay(
                        app = app,
                        source = src,
                        scheme = scheme,
                        onDismiss = { activeVideo = null },
                    )
                }
            }

            // 3. 固定高度双排粘滞/连发扩展键栏（采用精确矢量图标与真实 IME 状态）
            TerminalExtraKeysDock(
                scheme = scheme,
                usable = usable,
                imeOpen = isImeOpen,
                showSecondRow = showSecondRow,
                showFnRow = showFnRow,
                showCommandBar = showCommandBar,
                commandLine = commandLine,
                onCommandLineChange = { commandLine = it },
                onSubmitCommandLine = ::submitCommandLine,
                onHistoryPrev = {
                    if (history.isNotEmpty()) {
                        historyCursor = (historyCursor + 1).coerceAtMost(history.lastIndex)
                        commandLine = history[historyCursor]
                    }
                },
                onHistoryNext = {
                    if (historyCursor > 0) {
                        historyCursor -= 1
                        commandLine = history[historyCursor]
                    } else {
                        historyCursor = -1
                        commandLine = ""
                    }
                },
                modifiers = stickyModifiers,
                onDispatchKey = { key ->
                    val targets = inputTargets()
                    if (targets.isEmpty()) return@TerminalExtraKeysDock
                    val ctrl = stickyModifiers.isCtrlActive()
                    val alt = stickyModifiers.isAltActive()
                    val shift = stickyModifiers.isShiftActive()
                    targets.forEach { t ->
                        scrollOffsets[t.id] = 0
                        t.engine.sendKey(key = key, ctrl = ctrl, alt = alt, shift = shift)
                    }
                    stickyModifiers.clearTransients()
                },
                onSendRaw = { raw ->
                    val targets = inputTargets()
                    if (targets.isEmpty()) return@TerminalExtraKeysDock
                    val ctrl = stickyModifiers.isCtrlActive()
                    val alt = stickyModifiers.isAltActive()
                    targets.forEach { t ->
                        scrollOffsets[t.id] = 0
                        if ((ctrl || alt) && raw.length == 1 && raw[0].code >= 0x20) {
                            t.engine.sendChar(raw[0].code, ctrl = ctrl, alt = alt)
                        } else {
                            t.sendUserRaw(raw)
                        }
                    }
                    stickyModifiers.clearTransients()
                },
                onToggleIme = {
                    if (isImeOpen) {
                        unifiedImeView?.hideIme()
                    } else {
                        unifiedImeView?.focusAndShowIme()
                    }
                },
                onOpenSnippets = { showSnippets = true },
                onOpenHistory = { showHistorySheet = true },
            )
        }

        // 浮层与确认弹窗
        if (showAppearance) {
            TerminalAppearanceSheet(
                currentTheme = scheme,
                currentCursor = cursorStyle,
                boxDrawingEnabled = boxDrawingEnabled,
                minContrastEnabled = minContrastEnabled,
                secretRedactionMode = secretRedactionMode,
                safeGuardEnabled = safeGuardEnabled,
                bellHapticEnabled = bellHapticEnabled,
                visualBellEnabled = visualBellEnabled,
                fontSize = fontSize,
                onSelectTheme = manager::setTheme,
                onSelectCursor = manager::setCursorStyle,
                onToggleBoxDrawing = manager::setBoxDrawingEnabled,
                onToggleMinContrast = manager::setMinContrastEnabled,
                onSelectSecretMode = manager::setSecretRedactionMode,
                onToggleSafeGuard = manager::setSafeGuardEnabled,
                onToggleBellHaptic = manager::setBellHapticEnabled,
                onToggleVisualBell = manager::setVisualBellEnabled,
                onFontDelta = { d -> updateFontSize(fontSize + d) },
                onDismiss = { showAppearance = false },
            )
        }
        if (showHistorySheet) {
            TerminalHistorySearchSheet(
                scheme = scheme,
                history = history,
                onFill = { cmd ->
                    showHistorySheet = false
                    inputTargets().forEach { t ->
                        scrollOffsets[t.id] = 0
                        t.engine.sendText(cmd)
                    }
                },
                onRun = { cmd ->
                    showHistorySheet = false
                    val targets = inputTargets()
                    if (targets.isNotEmpty()) {
                        runWithSafeGuard(cmd, targets.any { it.isSsh }) {
                            manager.recordCommandHistory(cmd)
                            targets.forEach { t ->
                                scrollOffsets[t.id] = 0
                                t.engine.notifyCommandSubmitted(cmd)
                                t.sendUserRaw(cmd + "\r")
                            }
                        }
                    }
                },
                onDismiss = { showHistorySheet = false },
            )
        }
        pendingDangerAction?.let { (warn, proceed) ->
            GaliaisConfirmDialog(
                title = "高危命令拦截：${warn.title}",
                message = "${warn.reason}\n\n确认仍要在当前终端执行吗？",
                confirmText = "确认执行",
                danger = true,
                onConfirm = { pendingDangerAction = null; proceed() },
                onDismiss = { pendingDangerAction = null },
            )
        }
        openingEditorFile?.let { (ref, line) ->
            CodeEditorDialog(
                app = app,
                fileRef = ref,
                initialLine = line,
                onDismiss = { openingEditorFile = null },
            )
        }
        openingRichViewerFile?.let { ref ->
            UniversalFileViewerDialog(
                app = app,
                fileRef = ref,
                siblingImages = listOf(ref),
                onOpenInEditor = { r -> openingRichViewerFile = null; openingEditorFile = r to null },
                onSaveExternal = { app.conversations.notify("请在文件页导出：${it.name}") },
                onExtracted = { app.conversations.notify("解压完成") },
                onDismiss = { openingRichViewerFile = null },
            )
        }
        if (showMediaUrlDialog) {
            var mediaInput by remember { mutableStateOf("") }
            GaliaisAlertDialog(
                onDismissRequest = { showMediaUrlDialog = false },
                title = "播放视频或渲染媒体",
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "输入本地/Linux 文件路径（如 /sdcard/demo.mp4、README.md、report.pdf）或在线视频流 URL（MP4/WebM/HLS .m3u8）。命令行也可直接输出 OSC 1338;play=<路径> 自动唤起。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                        )
                        OutlinedTextField(
                            value = mediaInput,
                            onValueChange = { mediaInput = it },
                            label = { Text("文件路径或视频 URL") },
                            placeholder = { Text("https://... 或 /home/agent/demo.mp4") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = mediaInput.isNotBlank(),
                        onClick = {
                            val src = mediaInput.trim()
                            showMediaUrlDialog = false
                            openTerminalMediaInput(src)
                        },
                    ) { Text("立即播放/渲染", color = scheme.accentColor, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = { TextButton(onClick = { showMediaUrlDialog = false }) { Text("取消", color = G.inkMuted) } },
            )
        }
        pasteDraft?.let { draft ->
            GaliaisConfirmDialog(
                title = "粘贴多行内容？",
                message = "包含换行符（共 ${draft.length} 字），将在终端中逐行执行：\n\n${draft.take(260)}",
                confirmText = "粘贴",
                onConfirm = {
                    val targets = inputTargets()
                    pasteDraft = null
                    runWithSafeGuard(draft, targets.any { it.isSsh }) {
                        targets.forEach { it.engine.sendPaste(draft) }
                    }
                },
                onDismiss = { pasteDraft = null },
            )
        }
        confirmEndTarget?.let { target ->
            GaliaisConfirmDialog(
                title = "结束终端「${target.title}」？",
                message = "该会话正在运行，关闭后将断开当前连接。",
                confirmText = "结束",
                danger = true,
                onConfirm = { confirmEndTarget = null; closeSession(target.id) },
                onDismiss = { confirmEndTarget = null },
            )
        }
        if (confirmEndAll) {
            GaliaisConfirmDialog(
                title = "结束全部 ${sessions.size} 个终端？",
                message = "将断开所有本地及 SSH 终端会话。",
                confirmText = "全部结束",
                danger = true,
                onConfirm = {
                    confirmEndAll = false
                    manager.closeAllSessions()
                    scrollOffsets.clear()
                    selectedId = null
                    splitSecondId = null
                    focusedPaneIndex = 0
                    zoomedPaneIndex = -1
                    broadcastInput = false
                },
                onDismiss = { confirmEndAll = false },
            )
        }
        if (showQuickConnect) {
            QuickConnectSshDialog(
                availableKeys = sshKeys,
                onDismiss = { showQuickConnect = false },
                onConnect = { host, save, pwd ->
                    showQuickConnect = false
                    if (save) runCatching { app.envSsh.upsertSshHost(host, pwd) }
                    openNewSsh(host)
                },
            )
        }
        if (showSnippets) {
            TerminalSnippetsSheet(
                snippets = snippets,
                onSend = { sn ->
                    showSnippets = false
                    val payload = if (sn.autoEnter) sn.command + "\r" else sn.command
                    val targets = inputTargets()
                    if (targets.isNotEmpty()) {
                        runWithSafeGuard(sn.command, targets.any { it.isSsh }) {
                            if (sn.autoEnter) manager.recordCommandHistory(sn.command)
                            targets.forEach { t ->
                                scrollOffsets[t.id] = 0
                                if (sn.autoEnter) t.engine.notifyCommandSubmitted(sn.command)
                                t.sendUserRaw(payload)
                            }
                        }
                    }
                },
                onSave = manager::saveSnippet,
                onDelete = manager::deleteSnippet,
                onDismiss = { showSnippets = false },
            )
        }
        sftpHost?.let { host ->
            SftpBrowserSheet(
                app = app,
                host = host,
                files = files,
                localDefaultDir = appSettings.defaultCwd,
                onOpenTerminalAt = { remotePath ->
                    sftpHost = null
                    openNewSsh(host.copy(defaultPath = remotePath))
                },
                onDismiss = { sftpHost = null },
            )
        }
        detectedUrls?.let { urls ->
            GaliaisAlertDialog(
                onDismissRequest = { detectedUrls = null },
                title = "终端中的链接 (${urls.size})",
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        urls.take(10).forEach { u ->
                            Text(
                                u,
                                fontFamily = Mono,
                                fontSize = 12.sp,
                                color = scheme.accentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable { detectedUrls = null; openExternalUrl(u) }.padding(vertical = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { detectedUrls = null }) { Text("关闭", color = G.inkMuted) } },
            )
        }
        PluginSlotOverlayHost(
            app = app,
            cwd = focusedSession?.cwd ?: cwd,
            openingSurface = openingPluginSurface,
            openingPanel = openingPluginPanel,
            onOpenSurface = { openingPluginSurface = it },
            onOpenPanel = { openingPluginPanel = it },
        )
    }
}
