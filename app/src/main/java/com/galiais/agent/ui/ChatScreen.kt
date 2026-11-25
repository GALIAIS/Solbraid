package com.galiais.agent.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.FileDiff
import com.galiais.agent.engine.FuzzyMatch
import com.galiais.agent.engine.ImageAttachment
import com.galiais.agent.engine.ModelOption
import com.galiais.agent.engine.RewindPreviewResult
import com.galiais.agent.engine.SlashCommand
import com.galiais.agent.engine.TimelineItem
import com.galiais.agent.engine.TokenUsage
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.extractArtifactsFromItems
import com.galiais.agent.engine.selectedModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

private val AT_MENTION_RE = Regex("(?:^|\\s)@([^\\s@]*)$")
private val AT_REPLACE_RE = Regex("@[^\\s@]*$")

private val claudeFallbackModels = listOf(
    ModelOption("sonnet", "Sonnet"),
    ModelOption("opus", "Opus"),
    ModelOption("haiku", "Haiku"),
)

private val starterPrompts = listOf(
    "梳理当前目录结构与主要模块",
    "检查 Git 状态与未提交改动",
    "排查当前项目的构建或运行问题",
)

@Composable
fun ChatScreen(
    app: GaliaisApplication,
    key: ConversationKey,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenConversation: (ConversationKey) -> Unit,
    onReplaceConversation: (ConversationKey) -> Unit = onOpenConversation,
    onOpenDirectory: (String) -> Unit = {},
) {
    val stateHolder = remember(key) { app.conversations.observeConversation(key) }.collectAsState(initial = app.conversations.state(key))
    val state = stateHolder.value
    val arenaGroups by app.conversations.arenaGroups.collectAsState()
    val allRequests by app.conversations.requests.collectAsState()
    val runtime by app.runtime.snapshot.collectAsState()
    val settings by app.settings.state.collectAsState()
    val extRegistry by app.extensions.registry.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val requests = remember(allRequests, key) { allRequests.filter { it.conversation == key } }
    var draftValue by rememberSaveable(key.route, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val draft = draftValue.text
    val composerFocus = remember(key) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val attachments = remember(key.route) { mutableStateListOf<ImageAttachment>() }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showDiffs by remember { mutableStateOf(false) }
    var inspectedDiffs by remember { mutableStateOf<List<FileDiff>?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMentions by remember { mutableStateOf(false) }
    var showSlash by remember { mutableStateOf(false) }
    var showModelPopover by remember { mutableStateOf(false) }
    var showUsagePopover by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var switchingProvider by remember(key) { mutableStateOf(false) }
    var customModelDialog by remember { mutableStateOf(false) }
    var revertTarget by remember(key) { mutableStateOf<TimelineItem.UserMessage?>(null) }
    var claudeRewindTarget by remember { mutableStateOf<Pair<String, RewindPreviewResult>?>(null) }
    var models by remember(key.engine, state?.providerId) { mutableStateOf<List<ModelOption>>(emptyList()) }
    var dynamicCommands by remember(key) { mutableStateOf<List<SlashCommand>>(emptyList()) }
    var inlineFileMatches by remember { mutableStateOf<List<FuzzyMatch>>(emptyList()) }
    var permissionMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var showArtifactsDrawer by remember { mutableStateOf(false) }
    var showVisualTemplates by remember { mutableStateOf(false) }
    var showLatexScratchpad by remember { mutableStateOf(false) }
    var showExpandedComposer by remember { mutableStateOf(false) }
    var studioArtifact by remember { mutableStateOf<ChatArtifact?>(null) }
    val initialSnapshot = remember(key) { app.conversations.state(key) }
    val initialScrollIndex = remember(key) {
        if (initialSnapshot?.loaded == true && initialSnapshot.items.isNotEmpty()) {
            (initialSnapshot.items.size * 3).coerceAtLeast(1)
        } else 0
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    var followLatest by remember(key) { mutableStateOf(true) }
    // 已加载会话首帧直接定位到底部并立刻可见（随转场平滑滑入）；未加载会话等数据就绪定位底端后再揭示，消除跳动。
    var initialPositioned by remember(key) {
        mutableStateOf(initialSnapshot?.loaded == true)
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }

    fun replaceDraft(text: String) {
        draftValue = TextFieldValue(text, selection = TextRange(text.length))
    }

    fun restoreDraft(text: String) {
        val existing = draftValue.text
        replaceDraft(when {
            existing.isBlank() -> text
            text.isBlank() || existing == text -> existing
            else -> "$text\n\n$existing"
        })
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (attachments.size >= 4) { app.conversations.notify("单条消息最多附带 4 张图片"); return@rememberLauncherForActivityResult }
        scope.launch {
            val loaded = try { loadScaledImageAttachment(context, uri) } catch (_: Exception) { null }
            if (loaded == null) app.conversations.notify("无法读取图片，或处理后的图片超过 3 MB")
            else attachments.add(loaded)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val doc = try { loadDocumentAttachment(context, uri) } catch (_: Exception) { null }
            if (doc == null) {
                app.conversations.notify("无法读取文档，或大小超过限制（PDF ≤5MB，文本 ≤512KB）")
            } else {
                val prompt = draft.trim()
                draftValue = TextFieldValue("")
                runCatching {
                    app.conversations.sendClaudeDocument(key, prompt, doc.name, doc.mimeType, doc.data, doc.isPdf)
                }.onFailure { err ->
                    if (prompt.isNotEmpty()) restoreDraft(prompt)
                    app.conversations.notify("发送文档失败：${err.message ?: "请重试"}")
                }
            }
        }
    }

    val activeProvider = settings.provider(state?.providerId)
    val fileEditChangeCount = state?.items?.sumOf { if (it is TimelineItem.FileEdit) it.changes.size else 0 } ?: 0
    val fileEdits = remember(state?.items?.size, fileEditChangeCount, state?.running) {
        state?.items?.filterIsInstance<TimelineItem.FileEdit>()?.flatMap { it.changes } ?: emptyList()
    }
    // 流式输出时不在每个 Token 扫全历史正则，仅在条目数/末项变更、流式结束或主动打开产物抽屉时提取。
    val conversationArtifacts = remember(state?.items?.size, state?.items?.lastOrNull()?.id, state?.running, showArtifactsDrawer) {
        extractArtifactsFromItems(state?.items.orEmpty())
    }
    val hasDiffs = !state?.turnDiff.isNullOrBlank() || fileEdits.isNotEmpty()
    val cwd = state?.summary?.cwd
    val needsTrust = key.engine == EngineKind.CLAUDE && cwd != null && cwd !in settings.trustedProjects

    LaunchedEffect(key) {
        val initial = app.conversations.consumeInitialDraft(key)
        if (!initial.isNullOrEmpty()) {
            if (draftValue.text.isEmpty()) replaceDraft(initial)
        } else if (draftValue.text.isEmpty() && settings.keepUnsentDrafts) {
            val saved = app.settings.conversationDraft(key.route)
            if (saved.isNotEmpty()) replaceDraft(saved)
        }
    }
    LaunchedEffect(key.route, draft, settings.keepUnsentDrafts) {
        if (!settings.keepUnsentDrafts) return@LaunchedEffect
        delay(280)
        app.settings.setConversationDraft(key.route, draft)
    }
    LaunchedEffect(key, state?.unsentMessage?.id) {
        val failed = state?.unsentMessage ?: return@LaunchedEffect
        restoreDraft(failed.text)
        val existingIds = attachments.map { it.id }.toSet()
        attachments.addAll(failed.attachments.filter { it.id !in existingIds })
        app.conversations.clearUnsentMessage(key, failed.id)
    }
    LaunchedEffect(key, state?.revertedMessage?.id, state?.loaded, runtime.ready) {
        val restored = state?.revertedMessage ?: return@LaunchedEffect
        if (!runtime.ready || !state.loaded) return@LaunchedEffect
        restoreDraft(restored.text)
        app.conversations.clearRevertedMessage(key, restored.id)
        composerFocus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(key, runtime.ready) { if (runtime.ready) app.conversations.open(key) }
    LaunchedEffect(key, runtime.ready, state?.providerId, state?.model, state?.loaded, settings.providers, showModelPopover, showModelSheet) {
        if (!runtime.ready) { models = emptyList(); return@LaunchedEffect }
        models = try { app.conversations.adapter(key.engine).models(key) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (showModelPopover || showModelSheet) app.conversations.notify("读取模型选项失败：${error.message}")
            emptyList()
        }
    }
    LaunchedEffect(key, runtime.ready, state?.loaded) {
        if (!runtime.ready || state?.loaded != true) return@LaunchedEffect
        dynamicCommands = try { app.conversations.adapter(key.engine).commands(key) } catch (_: Exception) { emptyList() }
        if (hostPlugins.generationId.isEmpty()) {
            runCatching { app.extensions.refreshHostPlugins() }
        }
    }
    LaunchedEffect(showUsagePopover, key) {
        if (showUsagePopover && key.engine == EngineKind.CLAUDE) {
            app.conversations.claude.refreshContextUsage(key)
        }
    }

    val effectiveModels = when {
        models.isNotEmpty() -> models
        activeProvider != null -> activeProvider.models.map { ModelOption(it, it) }
        key.engine == EngineKind.CLAUDE -> claudeFallbackModels
        else -> emptyList()
    }
    val currentModelOption = effectiveModels.selectedModel(state?.model)

    val turnBlocks = remember(state?.items, state?.turnDurations, state?.running) {
        groupIntoTurnBlocks(state?.items.orEmpty(), state?.turnDurations.orEmpty(), state?.running == true)
    }
    // 只有用户主动脱离底部且下方确实还有内容时才显示回底按钮，流式增长期间不闪烁。
    val showScrollDown by remember(list) { derivedStateOf { !followLatest && list.canScrollForward } }
    // 毫秒级识别用户上滑意图：手指一向上回看立刻解除自动跟随，彻底杜绝流式回跳。
    val userScrollDetector = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0.5f) {
                    followLatest = false
                }
                return Offset.Zero
            }
        }
    }

    // 当用户手动滑回底部时自动恢复跟随
    LaunchedEffect(key, list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (!scrolling && !canScrollForward) followLatest = true
            }
    }

    // 首屏无闪贴底：首次加载出历史消息时，在揭示列表前先精确滚到最后一项底部，杜绝先闪顶部再跳底。
    LaunchedEffect(key, runtime.ready, state?.loaded, state?.items?.isEmpty()) {
        if (initialPositioned) return@LaunchedEffect
        if (!runtime.ready) {
            initialPositioned = true
            return@LaunchedEffect
        }
        val cur = state ?: return@LaunchedEffect
        if (!cur.loaded) return@LaunchedEffect
        if (cur.items.isEmpty()) {
            initialPositioned = true
            return@LaunchedEffect
        }
        withTimeoutOrNull(450L) {
            val total = snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
            list.scrollToItem((total - 1).coerceAtLeast(0))
            yield()
            val info = list.layoutInfo
            val lastVis = info.visibleItemsInfo.lastOrNull()
            if (lastVis != null && lastVis.index == info.totalItemsCount - 1) {
                val visibleEnd = info.viewportEndOffset - info.afterContentPadding
                val overflow = (lastVis.offset + lastVis.size) - visibleEnd
                if (overflow > 1) list.scrollBy(overflow.toFloat())
            }
        }
        initialPositioned = true
    }

    // 像素级无回跳平滑贴底：仅在运行流式输出或新增消息项时贴底，用户静默展开/折叠历史块或弹起 / @ 浮层时绝不抢占滚动。
    val currentRunning by androidx.compose.runtime.rememberUpdatedState(state?.running == true)
    LaunchedEffect(key, list, settings.autoScroll) {
        var lastTotalCount = 0
        snapshotFlow {
            val info = list.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            Triple(
                info.totalItemsCount,
                lastVisible?.let { Triple(it.index, it.offset, it.size) },
                info.viewportEndOffset - info.afterContentPadding,
            )
        }.collect { (totalCount, lastVis, viewportEnd) ->
            val addedNewItem = totalCount > lastTotalCount
            lastTotalCount = totalCount
            if (!initialPositioned || !settings.autoScroll || !followLatest || list.isScrollInProgress || totalCount <= 0) return@collect
            if (!currentRunning && !addedNewItem) return@collect
            val lastIdx = totalCount - 1
            if (lastVis == null || lastVis.first < lastIdx) {
                list.scrollToItem(lastIdx)
            } else {
                val overflow = (lastVis.second + lastVis.third) - viewportEnd
                if (overflow > 1) {
                    list.scrollBy(overflow.toFloat())
                }
            }
        }
    }

    val pluginCommands = remember(extRegistry, hostPlugins) { app.extensions.activePluginSlashCommands() }
    val allSlashCommands = remember(key.engine, dynamicCommands, pluginCommands) {
        val builtIn = builtInSlashCommands(key.engine)
        val seen = builtIn.map { it.name }.toMutableSet()
        buildList {
            addAll(builtIn)
            pluginCommands.forEach { if (seen.add(it.name)) add(it) }
            dynamicCommands.forEach { if (seen.add(it.name)) add(it) }
        }
    }
    val inlineSlashMatches = remember(draft, allSlashCommands, state?.running) {
        if (state?.running == true || !draft.startsWith("/") || draft.contains(' ') || draft.contains('\n')) emptyList()
        else {
            val q = draft.removePrefix("/").lowercase()
            allSlashCommands.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }.take(8)
        }
    }
    val atToken = remember(draft, cwd, state?.running) {
        if (cwd == null || state.running) null
        else AT_MENTION_RE.find(draft)?.groupValues?.getOrNull(1)
    }
    // 预热与缓存目录列表及模糊查询结果，让 @ 悬浮窗实现 0ms 首帧即时响应并避免高频键入叠加 IPC 阻塞。
    var cachedRootFiles by remember(cwd) { mutableStateOf<List<FuzzyMatch>>(emptyList()) }
    var cachedRootAtMs by remember(cwd) { mutableLongStateOf(0L) }
    val fileQueryCache = remember(cwd) { LinkedHashMap<String, List<FuzzyMatch>>() }
    LaunchedEffect(cwd, runtime.ready) {
        if (cwd == null || !runtime.ready) return@LaunchedEffect
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                files.list(cwd).take(40).map { FuzzyMatch(it.name + if (it.directory) "/" else "", it.name) }
            }
        }.getOrNull()
        if (loaded != null) {
            cachedRootFiles = loaded
            cachedRootAtMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(atToken, cwd, runtime.ready) {
        if (atToken == null || cwd == null || !runtime.ready) {
            inlineFileMatches = emptyList()
            return@LaunchedEffect
        }
        val query = atToken.trim()
        if (query.isEmpty()) {
            if (cachedRootFiles.isNotEmpty()) {
                inlineFileMatches = cachedRootFiles.take(8)
            }
            if (cachedRootFiles.isEmpty() || System.currentTimeMillis() - cachedRootAtMs > 15_000L) {
                val fresh = runCatching {
                    withContext(Dispatchers.IO) {
                        files.list(cwd).take(40).map { FuzzyMatch(it.name + if (it.directory) "/" else "", it.name) }
                    }
                }.getOrNull()
                if (fresh != null) {
                    cachedRootFiles = fresh
                    cachedRootAtMs = System.currentTimeMillis()
                    inlineFileMatches = fresh.take(8)
                }
            }
            return@LaunchedEffect
        }
        fileQueryCache[query]?.let { hit ->
            inlineFileMatches = hit
            return@LaunchedEffect
        }
        // 本地瞬时过滤已有缓存作为零延迟预览，85ms 去抖后再补齐跨目录精确模糊搜索。
        val qLower = query.lowercase()
        val localHit = (cachedRootFiles + inlineFileMatches)
            .distinctBy { it.path }
            .filter { it.path.lowercase().contains(qLower) || it.fileName.lowercase().contains(qLower) }
            .take(8)
        if (localHit.isNotEmpty()) inlineFileMatches = localHit
        delay(85)
        val remote = runCatching {
            withContext(Dispatchers.IO) { files.fuzzySearch(query, cwd).take(8) }
        }.getOrDefault(localHit)
        if (fileQueryCache.size > 48) fileQueryCache.clear()
        fileQueryCache[query] = remote
        inlineFileMatches = remote
    }

    fun submitMessage() {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val pics = attachments.toList()
        followLatest = true
        replaceDraft("")
        app.settings.setConversationDraft(key.route, "")
        attachments.clear()
        app.conversations.send(key, text, pics)
    }

    fun submitSteer() {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val pics = attachments.toList()
        followLatest = true
        replaceDraft("")
        app.settings.setConversationDraft(key.route, "")
        attachments.clear()
        app.conversations.steer(key, text, pics)
    }

    fun copyMarkdownArchive() {
        val md = app.conversations.exportMarkdown(key)
        if (md.isBlank()) { app.conversations.notify("暂无可导出的会话内容"); return }
        scope.launch {
            clipboard.setClipEntry(ClipData.newPlainText("conversation.md", md).toClipEntry())
            app.conversations.notify("已复制会话 Markdown")
        }
    }

    fun forkConversation() {
        scope.launch {
            try {
                val forked = app.conversations.fork(key)
                if (forked != null) {
                    app.conversations.notify("已创建会话分支")
                    onOpenConversation(forked)
                } else {
                    app.conversations.notify("当前 Agent 不支持会话分支")
                }
            } catch (e: Exception) {
                app.conversations.notify("创建分支失败：${e.message}")
            }
        }
    }

    fun runSlashCommand(cmd: SlashCommand) {
        replaceDraft("")
        when (cmd.localAction) {
            "compact" -> app.conversations.compact(key)
            "fork" -> forkConversation()
            "plan" -> {
                val next = if (state?.permissionMode == "plan") "default" else "plan"
                app.conversations.setPermissionMode(key, next)
            }
            "archive" -> {
                app.conversations.archive(key)
                onBack()
            }
            "review" -> app.conversations.startReview(key)
            "diff" -> if (hasDiffs) showDiffs = true else app.conversations.notify("当前会话还没有文件改动")
            "preview" -> showPreview = true
            "export" -> copyMarkdownArchive()
            else -> {
                val action = cmd.localAction
                if (action != null && action.startsWith("hostplugin:")) {
                    val parts = action.removePrefix("hostplugin:").split(':', limit = 2)
                    if (parts.size == 2) {
                        scope.launch {
                            try {
                                val res = app.extensions.runHostPluginCommand(parts[0], parts[1], cwd = cwd, sessionId = key.id)
                                when (res.kind) {
                                    "prompt" -> if (res.prompt.isNotBlank()) replaceDraft("${res.prompt} ")
                                    "artifact" -> if (res.content.isNotBlank()) {
                                        val artKind = ArtifactKind.fromMimeOrLang(res.artifactKind, res.artifactKind)
                                        studioArtifact = ChatArtifact(
                                            id = "hostcmd:${parts[0]}:${parts[1]}",
                                            identifier = "hostcmd-${parts[0]}-${parts[1]}",
                                            title = res.title.ifBlank { "/${cmd.name}" },
                                            kind = artKind,
                                            language = artKind.extension,
                                            content = res.content,
                                        )
                                    }
                                    else -> app.conversations.notify(res.message.ifBlank { "命令 /${cmd.name} 已执行" })
                                }
                            } catch (e: Exception) {
                                app.conversations.notify("执行插件命令失败：${e.message}")
                            }
                        }
                    }
                } else {
                    val pluginPrompt = action?.let { app.extensions.resolvePluginCommandPrompt(it) }
                    replaceDraft(if (pluginPrompt != null) "$pluginPrompt " else "/${cmd.name} ")
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize().galiaisCanvasBackground(),
        snackbarHost = { GaliaisSnackbarHost(snackbar) },
        topBar = {
            Surface(color = if (D.material) MaterialTheme.colorScheme.surface else G.canvas.copy(alpha = 0.94f)) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(if (D.material) 62.dp else 58.dp).padding(start = 4.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(GaliaisIcons.ArrowBack, contentDescription = "返回", tint = G.ink, modifier = Modifier.size(20.dp))
                        }
                        Column(
                            Modifier.weight(1f).clip(D.shape(8.dp)).clickable { showInfo = true }.padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                state?.summary?.title ?: "会话",
                                style = MaterialTheme.typography.titleMedium,
                                color = G.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EngineLabel(key.engine)
                                cwd?.let {
                                    Text(
                                        projectDisplayName(it),
                                        fontFamily = Mono,
                                        fontSize = 11.sp,
                                        color = G.inkMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                when {
                                    state?.providerId != null && activeProvider == null -> Text(
                                        "服务商已删",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.danger,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    activeProvider != null && !activeProvider.enabled -> Text(
                                        "${activeProvider.name}(已停用)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.warning,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    activeProvider != null -> Text(
                                        activeProvider.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = G.inkSubtle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (state?.running == true) {
                                    val fallbackTurnStart = remember(key, state.running) { System.currentTimeMillis() }
                                    PulsingDot(agentAccent(key.engine), size = 5.5.dp)
                                    ElapsedTimerText(state.turnStartedAt ?: fallbackTurnStart)
                                }
                            }
                        }
                        if (conversationArtifacts.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(D.chipShape)
                                    .background(G.claudeTint)
                                    .clickable { showArtifactsDrawer = true }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(GaliaisIcons.Layers, contentDescription = null, tint = G.claude, modifier = Modifier.size(13.dp))
                                Text("产物 ${conversationArtifacts.size}", style = MaterialTheme.typography.labelSmall, color = G.claude, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        if (hasDiffs) {
                            val editedFileCount = remember(fileEdits) { fileEdits.map { it.path }.distinct().size }
                            val diffPillLabel = if (editedFileCount > 0) "改动 $editedFileCount" else "改动"
                            Row(
                                modifier = Modifier
                                    .clip(D.chipShape)
                                    .background(G.accentContainer)
                                    .clickable { showDiffs = true }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(GaliaisIcons.FileDiff, contentDescription = null, tint = G.accent, modifier = Modifier.size(13.dp))
                                Text(diffPillLabel, style = MaterialTheme.typography.labelSmall, color = G.accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多操作", tint = G.inkMuted, modifier = Modifier.size(21.dp))
                        }
                    }
                    HeaderProgressSlot(runtime.ready && (state?.loaded != true || state.reverting))
                    val arenaSiblings = state?.arenaGroupId?.let { arenaGroups[it] }.orEmpty()
                    if (arenaSiblings.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(G.surfaceSubtle)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("并行对比", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                            arenaSiblings.forEach { sib ->
                                val active = sib == key
                                val sibRunning = app.conversations.state(sib)?.running == true
                                Row(
                                    modifier = Modifier
                                        .clip(D.chipShape)
                                        .background(if (active) G.accent else G.surface)
                                        .border(0.75.dp, if (active) G.accent else G.border, D.chipShape)
                                        .clickable(enabled = !active) { onOpenConversation(sib) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    AgentAppIcon(engine = sib.engine, size = 15.dp)
                                    if (sibRunning) PulsingDot(if (active) G.onAccent else G.warning, size = 6.dp)
                                    Text(
                                        sib.engine.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (active) G.onAccent else G.ink,
                                    )
                                }
                            }
                        }
                        HeaderDivider()
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!runtime.ready) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), shape = D.shape(10.dp), color = G.dangerTint) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(GaliaisIcons.AlertTriangle, contentDescription = null, tint = G.danger, modifier = Modifier.size(16.dp))
                        Text("运行环境未就绪：${runtime.message}", style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.weight(1f))
                        if (!runtime.busy && runtime.bound) {
                            TagPill("启动", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        } else if (runtime.busy && runtime.progress != null) {
                            TagPill("${(runtime.progress!!.coerceIn(0f, 1f) * 100).toInt()}%", fg = G.danger, bg = G.surface)
                        }
                    }
                }
            }
            if (state?.providerId != null && (activeProvider == null || !activeProvider.enabled)) {
                val deleted = activeProvider == null
                Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), shape = D.shape(10.dp), color = G.warningTint) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(GaliaisIcons.AlertTriangle, contentDescription = null, tint = G.warning, modifier = Modifier.size(16.dp))
                        Text(
                            if (deleted) "此会话绑定的服务商已被删除，继续发送将失败。" else "此会话服务商「${activeProvider.name}」已停用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.ink,
                            modifier = Modifier.weight(1f),
                        )
                        TagPill("切换服务商", fg = G.accent, bg = G.accentContainer, onClick = { showModelSheet = true })
                    }
                }
            }
            if (needsTrust) {
                CrispCard(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    background = G.warningTint,
                    borderColor = G.warning.copy(alpha = 0.4f),
                    padding = PaddingValues(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(GaliaisIcons.ShieldCheck, contentDescription = null, tint = G.warning, modifier = Modifier.size(18.dp))
                        Text("恢复 Claude Code 会话需信任此项目，并允许加载项目配置与钩子。", style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f))
                        PrimaryPillButton("信任", onClick = { app.settings.trust(cwd) })
                    }
                }
            }
            var dismissedPlanSig by remember(key) { mutableStateOf<String?>(null) }
            val currentPlanSig = remember(state?.plan) {
                state?.plan?.joinToString("|") { "${it.step}:${it.status}" }
            }
            if (state != null && state.plan.isNotEmpty() && currentPlanSig != dismissedPlanSig) {
                PlanChecklistCard(
                    steps = state.plan,
                    explanation = state.planExplanation,
                    focusText = state.focusText,
                    onDismiss = { dismissedPlanSig = currentPlanSig },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            val openFileLink: (String, String?) -> Unit = remember(key, cwd) {
                { rawPath, lineSpec ->
                    scope.launch {
                        val targetPath = when {
                            rawPath.startsWith("/") -> rawPath
                            !cwd.isNullOrBlank() -> cwd.trimEnd('/') + "/" + rawPath.trimStart('/')
                            else -> rawPath
                        }
                        try {
                            when (val preview = files.read(targetPath, limit = 256 * 1024)) {
                                is com.galiais.agent.engine.FilePreview.Text -> {
                                    val fileName = targetPath.substringAfterLast('/')
                                    val ext = fileName.substringAfterLast('.', "").lowercase()
                                    val kind = com.galiais.agent.engine.ArtifactKind.fromMimeOrLang(null, ext)
                                    val titleWithLine = if (!lineSpec.isNullOrBlank()) "$fileName · $lineSpec" else fileName
                                    studioArtifact = ChatArtifact(
                                        id = "file-link-${targetPath.hashCode()}-${lineSpec.orEmpty()}",
                                        identifier = "file-$fileName",
                                        title = titleWithLine,
                                        kind = kind,
                                        language = ext.ifBlank { "txt" },
                                        content = preview.content,
                                    )
                                }
                                is com.galiais.agent.engine.FilePreview.Image -> {
                                    app.conversations.notify("图片文件（${preview.bytes.size / 1024} KB）：$targetPath")
                                }
                                is com.galiais.agent.engine.FilePreview.Binary -> {
                                    app.conversations.notify("暂不支持在线预览二进制文件（${preview.bytes / 1024} KB）")
                                }
                            }
                        } catch (e: Exception) {
                            app.conversations.notify("无法打开文件：${e.message ?: targetPath.substringAfterLast('/')}")
                        }
                    }
                }
            }

            val density = LocalDensity.current
            var dockHeightPx by remember { mutableIntStateOf(0) }
            val composerBottomPadDp = with(density) { dockHeightPx.toDp() }
            val listRevealAlpha by animateFloatAsState(
                targetValue = if (initialPositioned) 1f else 0f,
                animationSpec = tween(100),
                label = "chatListReveal",
            )
            // 单容器浮层架构：消息列表延伸至底部透明输入框后方，/ 与 @ 悬浮窗叠加于列表之上不挤压 LazyColumn 重排。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalFileLinkHandler provides openFileLink) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = listRevealAlpha }.nestedScroll(userScrollDetector),
                    state = list,
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = D.space(12.dp),
                        bottom = (composerBottomPadDp + D.space(8.dp)).coerceAtLeast(88.dp),
                    ),
                    verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
                ) {
                    if (state?.loaded == true && state.items.isEmpty() && requests.isEmpty()) {
                        item(key = "empty-welcome") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AgentAppIcon(engine = key.engine, size = 48.dp)
                                EngineLabel(key.engine)
                                Text("向 ${key.engine.label} 描述你的任务", style = MaterialTheme.typography.titleMedium, color = G.ink)
                                cwd?.let {
                                    Text(it, fontFamily = Mono, fontSize = 12.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    starterPrompts.forEach { hint ->
                                        TagPill(hint, fg = G.ink, bg = G.surface, border = G.border, onClick = { replaceDraft(hint) })
                                    }
                                }
                            }
                        }
                    }
                    val allowUndo = state?.running != true && state?.reverting != true
                    val allowFork = AgentCatalog.capabilitiesOf(key.engine).fork && state?.running != true
                    val inPlanMode = when (key.engine) {
                        EngineKind.CODEX -> (state?.collaborationMode ?: settings.codexCollaborationMode) == "plan"
                        else -> state?.permissionMode == "plan"
                    }
                    val canReviewPlan = state?.running != true && requests.isEmpty()
                    val approvePlanAction: ((TimelineItem.Plan) -> Unit)? = if (canReviewPlan) ({ plan ->
                        app.conversations.update(key) { st ->
                            st.copy(
                                items = st.items.map { item ->
                                    if (item is TimelineItem.Plan && item.id == plan.id) {
                                        item.copy(requestFeedback = false, status = com.galiais.agent.engine.ItemStatus.COMPLETED, streaming = false)
                                    } else item
                                },
                            )
                        }
                        if (inPlanMode) {
                            when (key.engine) {
                                EngineKind.CODEX -> app.conversations.setCollaborationMode(key, "default")
                                EngineKind.CLAUDE -> app.conversations.setPermissionMode(
                                    key,
                                    settings.claudePermissionMode.takeUnless { it == "plan" } ?: "acceptEdits",
                                )
                                else -> app.conversations.setPermissionMode(key, "default")
                            }
                        }
                        app.conversations.send(key, "批准以上执行计划《${plan.title.ifBlank { "执行计划" }}》，请开始按方案实施。")
                    }) else null
                    val revisePlanAction: ((TimelineItem.Plan, String) -> Unit)? = if (canReviewPlan) ({ plan, feedback ->
                        app.conversations.update(key) { st ->
                            st.copy(
                                items = st.items.map { item ->
                                    if (item is TimelineItem.Plan && item.id == plan.id) {
                                        item.copy(requestFeedback = false, status = com.galiais.agent.engine.ItemStatus.DECLINED, streaming = false)
                                    } else item
                                },
                            )
                        }
                        app.conversations.send(key, "针对执行计划《${plan.title.ifBlank { "执行计划" }}》的修改建议：\n$feedback")
                    }) else null
                    turnBlocks.forEachIndexed { blockIdx, block ->
                        val hasPendingPlan = block.planItems.any { it.requestFeedback || it.status == com.galiais.agent.engine.ItemStatus.RUNNING }
                        val enablePlanActions = (blockIdx == turnBlocks.lastIndex && (inPlanMode || hasPendingPlan)) || hasPendingPlan
                        turnBlockItems(
                            block = block,
                            activeTurnStartedAt = state?.turnStartedAt,
                            expandByDefault = settings.expandThinking,
                            cwd = cwd,
                            onOpenConversation = onOpenConversation,
                            onCancelDelegation = { app.conversations.cancelDelegation(key) },
                            onQuote = { quote -> replaceDraft(if (draft.isBlank()) quote else "$draft\n$quote") },
                            onUndoTurn = if (allowUndo) ({ user ->
                                when (key.engine) {
                                    EngineKind.CODEX -> user.turnId?.let { revertTarget = user }
                                        ?: app.conversations.notify("此轮暂无可回退标识")
                                    EngineKind.CLAUDE -> scope.launch {
                                        try {
                                            val preview = app.conversations.rewindClaudeFiles(key, user.id, dryRun = true)
                                            if (!preview.canRewind) {
                                                app.conversations.notify(preview.error ?: "此轮暂无可回滚的文件检查点")
                                            } else {
                                                claudeRewindTarget = user.id to preview
                                            }
                                        } catch (e: Exception) {
                                            app.conversations.notify("读取检查点失败：${e.message}")
                                        }
                                    }
                                    else -> app.conversations.notify("当前 Agent 暂不支持轮次撤销")
                                }
                            }) else null,
                            onFork = if (allowFork) ({ forkConversation() }) else null,
                            onInspectDiffs = { diffs -> inspectedDiffs = diffs },
                            onOpenArtifact = { art -> studioArtifact = art },
                            onApprovePlan = if (enablePlanActions) approvePlanAction else null,
                            onRevisePlan = if (enablePlanActions) revisePlanAction else null,
                        )
                    }
                    item(key = "conversation-end", contentType = "sentinel") { Spacer(Modifier.height(2.dp)) }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollDown,
                    enter = GaliaisMotion.fabEnter,
                    exit = GaliaisMotion.fabExit,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerBottomPadDp + 10.dp),
                ) {
                    ScrollToBottomFab(
                        running = state?.running == true,
                        onClick = {
                            followLatest = true
                            scope.launch {
                                val end = list.layoutInfo.totalItemsCount - 1
                                if (end >= 0) list.animateScrollToItem(end)
                            }
                        },
                    )
                }

                // 底部透明浮层区：/ 与 @ 悬浮窗、实时预览条悬浮于输入框正上方且不触发 LazyColumn 尺寸重测；输入框后方完全透明。
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    val showInlinePopup = inlineSlashMatches.isNotEmpty() || inlineFileMatches.isNotEmpty()
                    var renderedSlash by remember { mutableStateOf<List<SlashCommand>>(emptyList()) }
                    var renderedFiles by remember { mutableStateOf<List<FuzzyMatch>>(emptyList()) }
                    if (inlineSlashMatches.isNotEmpty()) {
                        renderedSlash = inlineSlashMatches
                        renderedFiles = emptyList()
                    } else if (inlineFileMatches.isNotEmpty()) {
                        renderedSlash = emptyList()
                        renderedFiles = inlineFileMatches
                    }
                    AnimatedVisibility(
                        visible = showInlinePopup,
                        enter = slideInVertically(tween(130, easing = GaliaisMotion.NavEasing)) { it / 6 } + fadeIn(tween(110)),
                        exit = fadeOut(tween(90)),
                    ) {
                        val popupBg = when (D.cardStyle) {
                            "elevated", "filled", "grouped_expressive" -> G.surfaceElevated
                            else -> G.surface
                        }
                        val isHardware = D.cardStyle == "hardware_tactile"
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                            shape = D.popupShape,
                            color = popupBg,
                            border = BorderStroke(if (isHardware) D.borderWidth else 1.dp, G.borderStrong),
                            shadowElevation = if (G.dark) 2.dp else 3.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (renderedSlash.isNotEmpty()) {
                                    renderedSlash.forEach { cmd ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(D.shape(10.dp))
                                                .clickable { runSlashCommand(cmd) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(slashCommandIcon(cmd), contentDescription = null, tint = G.accent, modifier = Modifier.size(15.dp))
                                            TagPill("/${cmd.name}", fg = G.onAccentContainer, bg = G.accentContainer)
                                            Text(
                                                cmd.description.ifBlank { "执行 /${cmd.name}" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = G.inkMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                } else if (cwd != null && renderedFiles.isNotEmpty()) {
                                    renderedFiles.forEach { m ->
                                        val rel = m.path.removePrefix(cwd.trimEnd('/') + "/")
                                        val isDir = rel.endsWith("/")
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(D.shape(10.dp))
                                                .clickable {
                                                    val suffix = if (isDir) "" else " "
                                                    replaceDraft(draft.replace(AT_REPLACE_RE, "@$rel$suffix"))
                                                    if (!isDir) inlineFileMatches = emptyList()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                if (isDir) GaliaisIcons.Folder else GaliaisIcons.FileLines,
                                                contentDescription = null,
                                                tint = if (isDir) G.accent else G.inkMuted,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Text(rel, fontFamily = Mono, fontSize = 12.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 非纯文本模式下：检测到公式/图表/代码块/Markdown 结构时自动展示实时渲染预览条
                    val fallbackLimit = settings.editorPlainFallbackThreshold
                    val overPlainLimit = fallbackLimit > 0 && draft.length > fallbackLimit
                    val showAutoPreviewStrip = !settings.plainTextEditor &&
                        settings.editorAutoPreview &&
                        !overPlainLimit &&
                        inlineSlashMatches.isEmpty() &&
                        inlineFileMatches.isEmpty() &&
                        remember(draft, settings.renderMathFormulas) { hasRichRenderableContent(draft, settings.renderMathFormulas) }
                    AnimatedVisibility(
                        visible = showAutoPreviewStrip,
                        enter = GaliaisMotion.popoverEnter,
                        exit = GaliaisMotion.popoverExit,
                    ) {
                        ComposerLivePreviewStrip(
                            draft = draft,
                            onExpandEditor = { showExpandedComposer = true },
                            onOpenArtifact = { art -> studioArtifact = art },
                        )
                    }

                    // 停靠审批面板 + 透明背景悬浮输入框（测量其总高度作为列表底部留白，使最新消息刚好停在输入框上方）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { dockHeightPx = it.height },
                    ) {
                        AnimatedVisibility(
                            visible = requests.isNotEmpty(),
                            enter = GaliaisMotion.popoverEnter,
                            exit = GaliaisMotion.popoverExit,
                        ) {
                            DockedApprovalPanel(
                                requests = requests,
                                onDecision = { req, dec -> app.conversations.resolve(req, dec) },
                                onOpenArtifact = { art -> studioArtifact = art },
                            )
                        }

                        // 浮层输入框（透明外框容器，四周圆角与底部导航安全区透出画布与滚动消息）
                        val composerColors = G
                        val composerVisualTransformation = remember(
                            settings.plainTextEditor,
                            settings.editorSyntaxHighlight,
                            settings.renderMathFormulas,
                            settings.editorPlainFallbackThreshold,
                            composerColors,
                        ) {
                            if (!settings.plainTextEditor && settings.editorSyntaxHighlight) {
                                ComposerRichVisualTransformation(
                                    colors = composerColors,
                                    renderMath = settings.renderMathFormulas,
                                    fallbackThreshold = settings.editorPlainFallbackThreshold,
                                )
                            } else VisualTransformation.None
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.48f to Color.Transparent,
                                        0.84f to G.canvas.copy(alpha = 0.82f),
                                        1.0f to G.canvas,
                                    ),
                                )
                                .navigationBarsPadding()
                                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
                        ) {
                val inputEnabled = runtime.ready && state?.loaded == true && !needsTrust
                val isRunning = state?.running == true
                val canSteerMode = inputEnabled && isRunning && key.engine == EngineKind.CODEX && state.activeTurnId != null
                val fieldEnabled = inputEnabled && (!isRunning || canSteerMode)
                val hasPayload = draft.isNotBlank() || attachments.isNotEmpty()
                val canSend = inputEnabled && !isRunning && state.reverting != true && hasPayload
                val canSteer = canSteerMode && hasPayload
                val composerBorderColor by animateColorAsState(
                    targetValue = when {
                        canSteer -> G.codex
                        hasPayload -> G.accent.copy(alpha = 0.65f)
                        else -> G.borderStrong
                    },
                    animationSpec = tween(180),
                    label = "composerBorder",
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = D.composerShape,
                    color = if (D.material) MaterialTheme.colorScheme.surfaceContainerHigh else G.surface,
                    border = if (D.material && !canSteer && !hasPayload) null else BorderStroke(1.dp, composerBorderColor),
                    shadowElevation = if (G.dark || D.material) 0.dp else 2.dp,
                ) {
                    Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 7.dp)) {
                        if (attachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                attachments.forEach { att ->
                                    Surface(
                                        shape = D.shape(10.dp),
                                        color = G.surfaceSubtle,
                                        border = BorderStroke(0.75.dp, G.border),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(GaliaisIcons.Image, contentDescription = null, tint = G.accent, modifier = Modifier.size(14.dp))
                                            Text("${att.name} (${att.bytes / 1024}K)", fontSize = 11.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Icon(
                                                GaliaisIcons.Close,
                                                contentDescription = "移除图片",
                                                tint = G.inkMuted,
                                                modifier = Modifier.size(14.dp).clickable { attachments.remove(att) },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!settings.plainTextEditor && settings.editorFormatToolbar && fieldEnabled) {
                            ComposerFormatToolbar(
                                value = draftValue,
                                indentStr = settings.tabIndentString(),
                                onValueChange = { draftValue = it },
                                onInsertMention = if (cwd != null && !isRunning) ({ showMentions = true }) else null,
                                onExpandEditor = { showExpandedComposer = true },
                            )
                        }

                        if ((settings.showComposerWordCount && draft.isNotEmpty()) || draft.length >= 120 || draft.count { it == '\n' } >= 3) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (settings.showComposerWordCount && draft.isNotEmpty()) {
                                    val lineCnt = draft.count { it == '\n' } + 1
                                    Text(
                                        "${draft.length} 字 · ${lineCnt} 行 · ≈${estimatePromptTokens(draft)} tok",
                                        fontFamily = Mono,
                                        fontSize = 10.5.sp,
                                        color = G.inkSubtle,
                                    )
                                }
                                if (!settings.plainTextEditor && overPlainLimit) {
                                    TagPill("超长纯文本保护", fg = G.warning, bg = G.warningTint)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "全屏编辑",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = G.accent,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(D.shape(6.dp))
                                        .clickable { showExpandedComposer = true }
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }

                        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 160.dp)) {
                            if (draft.isEmpty()) {
                                Text(
                                    when {
                                        canSteerMode -> "补充指令，调整当前任务…"
                                        isRunning -> "${key.engine.label} 正在工作…"
                                        else -> "和 ${key.engine.label} 一起工作"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = G.inkSubtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = draftValue,
                                onValueChange = { next ->
                                    draftValue = if (!settings.plainTextEditor && settings.editorAutoPair) {
                                        applyAutoPairEdit(draftValue, next)
                                    } else next
                                },
                                enabled = fieldEnabled,
                                visualTransformation = composerVisualTransformation,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = G.ink,
                                    fontFamily = if (settings.editorMonospace) Mono else MaterialTheme.typography.bodyLarge.fontFamily,
                                ),
                                cursorBrush = SolidColor(G.accent),
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(composerFocus)
                                    .onPreviewKeyEvent { ev ->
                                        if (settings.enterToSend && ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && !ev.isShiftPressed) {
                                            if (canSend) { submitMessage(); true }
                                            else if (canSteer) { submitSteer(); true }
                                            else false
                                        } else false
                                    },
                            )
                        }

                        Spacer(Modifier.height(6.dp))
                        // 三段防溢出底栏：左侧「+」+ 权限 + 上下文环 + 模型触发器共享 weight(1f)，右侧发送/中断按钮永远固定不被挤出
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                            // 左侧：+ 菜单 + 权限模式直切（首帧即渲染占位与控件，加载完成时不跳变）
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                run {
                                    val idle = !isRunning
                                    val canOpenAttach = inputEnabled && (idle || canSteerMode)
                                    Box {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(if (attachMenu) G.accentContainer else Color.Transparent)
                                                .semantics { contentDescription = "添加附件或快捷指令" }
                                                .clickable(enabled = canOpenAttach, role = Role.Button) { attachMenu = true },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                GaliaisIcons.Add,
                                                contentDescription = null,
                                                tint = if (!canOpenAttach) G.inkSubtle else if (attachMenu) G.accent else G.ink,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        GaliaisAnchoredPopover(
                                            expanded = attachMenu,
                                            onDismissRequest = { attachMenu = false },
                                            preferAbove = true,
                                            alignEnd = false,
                                            minWidth = 196.dp,
                                            maxWidth = 240.dp,
                                        ) {
                                            GaliaisMenuRow(
                                                title = "添加图片",
                                                subtitle = "从相册附加截图或参考图",
                                                icon = GaliaisIcons.Image,
                                                onClick = { attachMenu = false; imagePicker.launch("image/*") },
                                            )
                                            if (cwd != null && idle) {
                                                GaliaisMenuRow(
                                                    title = "引用工作区文件",
                                                    subtitle = "输入 @ 模糊搜索项目文件",
                                                    icon = GaliaisIcons.AtSign,
                                                    onClick = { attachMenu = false; showMentions = true },
                                                )
                                            }
                                            if (idle) {
                                                GaliaisMenuRow(
                                                    title = "快捷指令",
                                                    subtitle = "输入 / 执行会话与工具指令",
                                                    icon = GaliaisIcons.Slash,
                                                    onClick = { attachMenu = false; showSlash = true },
                                                )
                                                GaliaisMenuRow(
                                                    title = "可视化与产物模板",
                                                    subtitle = "架构图、原型、SVG 与 LaTeX 模板",
                                                    icon = GaliaisIcons.Sparkles,
                                                    onClick = { attachMenu = false; showVisualTemplates = true },
                                                )
                                                GaliaisMenuRow(
                                                    title = "离线 LaTeX 编译器",
                                                    subtitle = "编辑、实时渲染公式/论文并导出 PDF",
                                                    icon = GaliaisIcons.FileLines,
                                                    onClick = { attachMenu = false; showLatexScratchpad = true },
                                                )
                                                if (key.engine == EngineKind.CLAUDE) {
                                                    GaliaisMenuRow(
                                                        title = "附加 PDF / 文档",
                                                        subtitle = "发送给 Claude Code 原生文档块",
                                                        icon = GaliaisIcons.FilePlus,
                                                        onClick = {
                                                            attachMenu = false
                                                            documentPicker.launch(arrayOf("application/pdf", "text/*"))
                                                        },
                                                    )
                                                }
                                            }
                                            if (conversationArtifacts.isNotEmpty()) {
                                                GaliaisMenuRow(
                                                    title = "会话产物（${conversationArtifacts.size}）",
                                                    subtitle = "打开离线产物工作台查看全部结果",
                                                    icon = GaliaisIcons.Layers,
                                                    onClick = { attachMenu = false; showArtifactsDrawer = true },
                                                )
                                            }
                                            Hairline(Modifier.padding(vertical = 2.dp))
                                            GaliaisMenuRow(
                                                title = "全屏编辑器",
                                                subtitle = if (settings.plainTextEditor) "当前：纯文本无渲染模式" else "当前：自动渲染预览模式",
                                                icon = GaliaisIcons.FilePen,
                                                onClick = { attachMenu = false; showExpandedComposer = true },
                                            )
                                            GaliaisMenuRow(
                                                title = if (settings.plainTextEditor) "切回自动渲染编辑" else "切换纯文本编辑器",
                                                subtitle = if (settings.plainTextEditor) "恢复语法高亮与实时预览" else "关闭一切输入渲染",
                                                icon = GaliaisIcons.Gear,
                                                onClick = {
                                                    attachMenu = false
                                                    val nextPlain = !settings.plainTextEditor
                                                    app.settings.setPlainTextEditor(nextPlain)
                                                    app.conversations.notify(if (nextPlain) "已切换为纯文本编辑器" else "已切换为自动渲染编辑器")
                                                },
                                            )
                                        }
                                    }
                                }

                                if (runtime.ready) {
                                    if (key.engine == EngineKind.CODEX) {
                                        val effPolicy = state?.approvalPolicy ?: settings.approvalPolicy
                                        val effReviewer = state?.approvalsReviewer ?: settings.approvalsReviewer
                                        val isFullAccess = effPolicy == "never"
                                        val isAuto = effReviewer == "auto_review" && !isFullAccess
                                        val permText = codexPermissionModeLabel(effPolicy, effReviewer)
                                        val permIcon = when {
                                            isFullAccess -> GaliaisIcons.AlertTriangle
                                            isAuto -> GaliaisIcons.Sparkles
                                            else -> GaliaisIcons.ShieldCheck
                                        }
                                        Box {
                                            ComposerPermissionPill(
                                                label = compactComposerPermLabel(permText),
                                                icon = permIcon,
                                                dangerHighlight = isFullAccess,
                                                accentHighlight = isAuto,
                                                expanded = permissionMenu,
                                                onClick = { permissionMenu = true },
                                            )
                                            GaliaisAnchoredPopover(
                                                expanded = permissionMenu,
                                                onDismissRequest = { permissionMenu = false },
                                                preferAbove = true,
                                                alignEnd = false,
                                                minWidth = 232.dp,
                                                maxWidth = 272.dp,
                                            ) {
                                                Text(
                                                    "执行审批策略",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = G.inkMuted,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                )
                                                listOf(
                                                    Triple("on-request" to "user", "按需审批", "默认，高风险前询问"),
                                                    Triple("untrusted" to "user", "严格审批", "除只读命令外均询问"),
                                                    Triple("on-request" to "auto_review", "AI 自动审校", "Guardian 风险子代理判定"),
                                                    Triple("never" to "user", "完全访问", "跳过询问直接执行"),
                                                ).forEach { (pair, label, desc) ->
                                                    val active = effPolicy == pair.first && effReviewer == pair.second
                                                    val rowIcon = when {
                                                        pair.first == "never" -> GaliaisIcons.AlertTriangle
                                                        pair.second == "auto_review" -> GaliaisIcons.Sparkles
                                                        else -> GaliaisIcons.ShieldCheck
                                                    }
                                                    GaliaisMenuRow(
                                                        title = label,
                                                        subtitle = desc,
                                                        icon = rowIcon,
                                                        selected = active,
                                                        tintOverride = if (pair.first == "never") G.warning else Color.Unspecified,
                                                        onClick = {
                                                            app.conversations.setCodexApproval(key, pair.first, pair.second)
                                                            permissionMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else if (key.engine == EngineKind.CLAUDE) {
                                        val pm = state?.permissionMode ?: settings.claudePermissionMode
                                        val isFullAccess = pm == "bypassPermissions"
                                        val isAccent = pm == "plan" || pm == "auto"
                                        val permIcon = when (pm) {
                                            "bypassPermissions" -> GaliaisIcons.AlertTriangle
                                            "plan" -> GaliaisIcons.ListCheck
                                            "auto" -> GaliaisIcons.Sparkles
                                            "acceptEdits" -> GaliaisIcons.FilePen
                                            else -> GaliaisIcons.ShieldCheck
                                        }
                                        Box {
                                            ComposerPermissionPill(
                                                label = compactComposerPermLabel(claudePermissionModeLabel(pm)),
                                                icon = permIcon,
                                                dangerHighlight = isFullAccess,
                                                accentHighlight = isAccent,
                                                expanded = permissionMenu,
                                                onClick = { permissionMenu = true },
                                            )
                                            GaliaisAnchoredPopover(
                                                expanded = permissionMenu,
                                                onDismissRequest = { permissionMenu = false },
                                                preferAbove = true,
                                                alignEnd = false,
                                                minWidth = 232.dp,
                                                maxWidth = 272.dp,
                                            ) {
                                                Text(
                                                    "权限模式",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = G.inkMuted,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                )
                                                listOf(
                                                    Triple("default", "默认询问", "写入与命令逐次确认"),
                                                    Triple("acceptEdits", "自动允许编辑", "文件修改自动放行"),
                                                    Triple("plan", "计划模式", "分析与方案规划"),
                                                    Triple("auto", "AI 自动审校", "安全分类器自动判定"),
                                                    Triple("bypassPermissions", "完全访问", "跳过全部权限确认"),
                                                ).filter { (id, _, _) -> id != "auto" || currentModelOption?.supportsAutoMode == true }.forEach { (id, label, desc) ->
                                                    val active = pm == id
                                                    val rowIcon = when (id) {
                                                        "bypassPermissions" -> GaliaisIcons.AlertTriangle
                                                        "plan" -> GaliaisIcons.ListCheck
                                                        "auto" -> GaliaisIcons.Sparkles
                                                        "acceptEdits" -> GaliaisIcons.FilePen
                                                        else -> GaliaisIcons.ShieldCheck
                                                    }
                                                    GaliaisMenuRow(
                                                        title = label,
                                                        subtitle = desc,
                                                        icon = rowIcon,
                                                        selected = active,
                                                        tintOverride = if (id == "bypassPermissions") G.warning else Color.Unspecified,
                                                        onClick = {
                                                            app.conversations.setPermissionMode(key, id)
                                                            permissionMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else if (key.engine.id == "antigravity") {
                                        val pm = state?.permissionMode?.ifBlank { settings.antigravityPermissionMode } ?: settings.antigravityPermissionMode
                                        val isBypass = pm == "bypass"
                                        val isAccent = pm in setOf("plan", "auto", "accept-edits")
                                        val permIcon = when (pm) {
                                            "bypass" -> GaliaisIcons.AlertTriangle
                                            "plan" -> GaliaisIcons.ListCheck
                                            "auto" -> GaliaisIcons.Sparkles
                                            "accept-edits" -> GaliaisIcons.FilePen
                                            else -> GaliaisIcons.ShieldCheck
                                        }
                                        Box {
                                            ComposerPermissionPill(
                                                label = compactComposerPermLabel(antigravityPermissionModeLabel(pm)),
                                                icon = permIcon,
                                                dangerHighlight = isBypass,
                                                accentHighlight = isAccent,
                                                expanded = permissionMenu,
                                                onClick = { permissionMenu = true },
                                            )
                                            GaliaisAnchoredPopover(
                                                expanded = permissionMenu,
                                                onDismissRequest = { permissionMenu = false },
                                                preferAbove = true,
                                                alignEnd = false,
                                                minWidth = 236.dp,
                                                maxWidth = 276.dp,
                                            ) {
                                                Text(
                                                    "Antigravity 执行权限模式",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = G.inkMuted,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                )
                                                listOf(
                                                    Triple("default", "默认询问", "编辑文件与终端命令均先确认"),
                                                    Triple("accept-edits", "自动允许编辑", "自动放行工作区改动，命令询问"),
                                                    Triple("auto", "智能自动", "编辑与安全命令自动放行"),
                                                    Triple("plan", "计划模式", "先分析规划再执行修改"),
                                                    Triple("bypass", "完全访问", "跳过全部工具权限确认"),
                                                ).forEach { (id, label, desc) ->
                                                    val active = pm == id
                                                    val rowIcon = when (id) {
                                                        "bypass" -> GaliaisIcons.AlertTriangle
                                                        "plan" -> GaliaisIcons.ListCheck
                                                        "auto" -> GaliaisIcons.Sparkles
                                                        "accept-edits" -> GaliaisIcons.FilePen
                                                        else -> GaliaisIcons.ShieldCheck
                                                    }
                                                    GaliaisMenuRow(
                                                        title = label,
                                                        subtitle = desc,
                                                        icon = rowIcon,
                                                        selected = active,
                                                        tintOverride = if (id == "bypass") G.warning else Color.Unspecified,
                                                        onClick = {
                                                            app.conversations.setPermissionMode(key, id)
                                                            permissionMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else if (AgentCatalog.capabilitiesOf(key.engine).modes) {
                                        val acpModes = remember(key, state?.permissionMode, state?.loaded) {
                                            app.conversations.hub.modesOf(key).ifEmpty {
                                                listOf("default" to "默认模式", "plan" to "计划模式")
                                            }
                                        }
                                        val currentModeId = state?.permissionMode?.ifBlank { acpModes.first().first } ?: acpModes.first().first
                                        val currentModeName = acpModes.firstOrNull { it.first == currentModeId }?.second ?: currentModeId
                                        Box {
                                            ComposerPermissionPill(
                                                label = currentModeName,
                                                icon = if (currentModeId.contains("plan", ignoreCase = true)) GaliaisIcons.ListCheck else GaliaisIcons.Sparkles,
                                                dangerHighlight = false,
                                                accentHighlight = currentModeId != "default",
                                                expanded = permissionMenu,
                                                onClick = { permissionMenu = true },
                                            )
                                            GaliaisAnchoredPopover(
                                                expanded = permissionMenu,
                                                onDismissRequest = { permissionMenu = false },
                                                preferAbove = true,
                                                alignEnd = false,
                                                minWidth = 216.dp,
                                                maxWidth = 264.dp,
                                            ) {
                                                Text(
                                                    "${key.engine.label} 会话模式",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = G.inkMuted,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                )
                                                acpModes.forEach { (id, label) ->
                                                    GaliaisMenuRow(
                                                        title = label,
                                                        subtitle = id,
                                                        icon = if (id.contains("plan", ignoreCase = true)) GaliaisIcons.ListCheck else GaliaisIcons.Sparkles,
                                                        selected = currentModeId == id,
                                                        onClick = {
                                                            app.conversations.setPermissionMode(key, id)
                                                            permissionMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        val pmFallback = state?.permissionMode
                                        if (!pmFallback.isNullOrBlank() && pmFallback != "default") {
                                            TagPill(
                                                text = pmFallback,
                                                fg = G.accent,
                                                bg = G.accentContainer,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f, fill = true))

                            // 右侧元数据（首帧即渲染占位与模型名，避免 loaded 翻 true 时底栏控件突现闪烁）
                            if (settings.showTokenUsage) {
                                Box {
                                    ContextUsageRing(
                                        ratio = state?.tokenUsage?.ratio(),
                                        hasData = state?.tokenUsage != null,
                                        expanded = showUsagePopover,
                                        onClick = { showUsagePopover = true },
                                    )
                                    GaliaisAnchoredPopover(
                                        expanded = showUsagePopover,
                                        onDismissRequest = { showUsagePopover = false },
                                        preferAbove = true,
                                        alignEnd = true,
                                        minWidth = 244.dp,
                                        maxWidth = 288.dp,
                                        contentPadding = PaddingValues(12.dp),
                                    ) {
                                        ContextUsagePopoverContent(
                                            usage = state?.tokenUsage,
                                            running = isRunning,
                                            onCompact = {
                                                showUsagePopover = false
                                                app.conversations.compact(key)
                                            },
                                            onOpenInfo = {
                                                showUsagePopover = false
                                                showInfo = true
                                            },
                                        )
                                    }
                                }
                            }

                            if (runtime.ready) {
                                val modelShort = (currentModelOption?.label ?: state?.model ?: "默认模型")
                                val effortShort = state?.effort?.let { effortLabel(it) }
                                Box(Modifier.weight(1f, fill = false)) {
                                    ComposerModelTrigger(
                                        modelLabel = modelShort,
                                        effortLabel = effortShort,
                                        enabled = !isRunning && state?.loaded == true,
                                        expanded = showModelPopover,
                                        onClick = { showModelPopover = true },
                                    )
                                    GaliaisAnchoredPopover(
                                        expanded = showModelPopover,
                                        onDismissRequest = { showModelPopover = false },
                                        preferAbove = true,
                                        alignEnd = true,
                                        minWidth = 264.dp,
                                        maxWidth = 316.dp,
                                        contentPadding = PaddingValues(8.dp),
                                    ) {
                                        ModelSwitcherPopoverContent(
                                            engine = key.engine,
                                            activeProvider = activeProvider,
                                            models = effectiveModels,
                                            currentModel = state?.model,
                                            optionsContent = { ConversationModelOptions(app, key, state, currentModelOption) },
                                            onSelectModel = { m ->
                                                app.conversations.setModel(key, m)
                                                showModelPopover = false
                                            },
                                            onOpenFullSheet = {
                                                showModelPopover = false
                                                showModelSheet = true
                                            },
                                        )
                                    }
                                }
                            }
                            } // 结束 weight(1f) 自适应左中区

                            if (canSteer) {
                                TagPill(
                                    text = "插话",
                                    fg = G.onAccent,
                                    bg = G.codex,
                                    onClick = { submitSteer() },
                                )
                            }
                            AnimatedContent(
                                targetState = isRunning,
                                modifier = Modifier.size(if (D.material) 40.dp else 36.dp),
                                contentAlignment = Alignment.Center,
                                transitionSpec = { (fadeIn(tween(120)) togetherWith fadeOut(tween(90))).using(null) },
                                label = "sendStopButton",
                            ) { running ->
                                if (running) {
                                    Box(
                                        modifier = Modifier
                                            .size(if (D.material) 40.dp else 36.dp)
                                            .clip(CircleShape)
                                            .background(G.danger)
                                            .semantics { contentDescription = "中断" }
                                            .clickable(role = Role.Button) { app.conversations.interrupt(key) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(GaliaisIcons.Stop, contentDescription = null, tint = G.onDanger, modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(if (D.material) 40.dp else 36.dp)
                                            .clip(if (D.material) D.shape(12.dp) else CircleShape)
                                            .background(if (canSend) G.accent else G.surfaceSubtle)
                                            .semantics { contentDescription = "发送" }
                                            .clickable(enabled = canSend, role = Role.Button) { submitMessage() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            GaliaisIcons.Send,
                                            contentDescription = null,
                                            tint = if (canSend) G.onAccent else G.inkSubtle,
                                            modifier = Modifier.size(16.dp),
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
                }
            }
        }
    }

    if (showInfo) {
        SessionInfoSheet(
            app = app,
            key = key,
            state = state,
            provider = activeProvider,
            onSwitchProviderModel = { showModelSheet = true },
            onRename = { renaming = true },
            onCompact = { app.conversations.compact(key) },
            onFork = if (AgentCatalog.capabilitiesOf(key.engine).fork) ({ forkConversation() }) else null,
            onArchive = if (AgentCatalog.capabilitiesOf(key.engine).archive) ({ app.conversations.archive(key); onBack() }) else null,
            onReview = { app.conversations.startReview(key) },
            onExportCopy = { copyMarkdownArchive() },
            onExportSave = {
                val targetDir = cwd ?: return@SessionInfoSheet
                val md = app.conversations.exportMarkdown(key)
                if (md.isBlank()) { app.conversations.notify("暂无可导出内容"); return@SessionInfoSheet }
                val fileName = "solbraid-${key.engine.id}-${key.id.take(8)}.md"
                scope.launch {
                    try {
                        files.write(targetDir.trimEnd('/') + "/" + fileName, md)
                        app.conversations.notify("已导出到 $fileName")
                    } catch (e: Exception) { app.conversations.notify("导出失败：${e.message}") }
                }
            },
            onOpenDirectory = cwd?.let { dir -> { onOpenDirectory(dir) } },
            onDelete = { deleting = true },
            onDismiss = { showInfo = false },
        )
    }

    if (showModelSheet) {
        ModelPickerSheet(
            engine = key.engine,
            providers = settings.allSelectableProviders(),
            activeProvider = activeProvider,
            currentProviderId = state?.providerId,
            switchingProvider = switchingProvider,
            models = effectiveModels,
            currentModel = state?.model,
            supportsAutoMode = currentModelOption?.supportsAutoMode == true,
            optionsContent = { ConversationModelOptions(app, key, state, currentModelOption) },
            permissionMode = state?.permissionMode ?: "default",
            codexApprovalPolicy = state?.approvalPolicy ?: settings.approvalPolicy,
            codexApprovalsReviewer = state?.approvalsReviewer ?: settings.approvalsReviewer,
            onSelectProvider = { pid, m ->
                switchingProvider = true
                app.conversations.setProvider(
                    key = key,
                    providerId = pid,
                    model = m,
                    onMigrated = { nextKey ->
                        showModelSheet = false
                        onReplaceConversation(nextKey)
                    },
                    onFinished = { switchingProvider = false },
                )
            },
            onSelectModel = { m -> app.conversations.setModel(key, m) },
            onManualModel = { customModelDialog = true },
            onSelectPermissionMode = { m -> app.conversations.setPermissionMode(key, m) },
            onSelectCodexApproval = { pol, rev -> app.conversations.setCodexApproval(key, pol, rev) },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showMentions && cwd != null) {
        FileMentionSheet(
            files = files,
            cwd = cwd,
            onSelect = { rel ->
                replaceDraft(if (draft.isBlank() || draft.endsWith(" ") || draft.endsWith("\n")) "$draft@$rel " else "$draft @$rel ")
                showMentions = false
            },
            onDismiss = { showMentions = false },
        )
    }

    if (showSlash) {
        SlashCommandSheet(
            engine = key.engine,
            dynamicCommands = pluginCommands + dynamicCommands,
            onPick = { cmd ->
                showSlash = false
                runSlashCommand(cmd)
            },
            onDismiss = { showSlash = false },
        )
    }

    revertTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revertTarget = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("回退到此轮前？", color = G.ink) },
            text = {
                Text(
                    "将移除此轮及之后的会话记录，并将这条消息的文字放回输入框。工作区文件不会还原。" +
                        if (target.imageCount > 0) "原消息图片需重新添加。" else "",
                    color = G.inkMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    revertTarget = null
                    app.conversations.revertBeforeTurn(key, target.id)
                }) { Text("确认回退", color = G.warning, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { revertTarget = null }) { Text("取消", color = G.inkMuted) } },
        )
    }

    if (renaming) {
        TextInputDialog("重命名会话", state?.summary?.title ?: "", onDismiss = { renaming = false }) {
            app.conversations.rename(key, it)
            renaming = false
        }
    }
    if (customModelDialog) {
        TextInputDialog("指定模型 ID", state?.model ?: "", onDismiss = { customModelDialog = false }) {
            app.conversations.setModel(key, it)
            customModelDialog = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("删除会话？", color = G.ink) },
            text = { Text("将删除当前 ${key.engine.label} 会话记录，不会删除工作区文件。此操作不能撤销。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    app.conversations.delete(key)
                    onBack()
                }) { Text("删除", color = G.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消", color = G.inkMuted) } },
        )
    }
    claudeRewindTarget?.let { (userMsgId, preview) ->
        AlertDialog(
            onDismissRequest = { claudeRewindTarget = null },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("撤销本轮文件修改？", color = G.ink) },
            text = {
                val count = preview.filesChanged.size
                val listPreview = preview.filesChanged.take(4).joinToString("\n") { "• ${it.substringAfterLast('/')}" }
                val more = if (count > 4) "\n… 等共 $count 个文件" else ""
                Text(
                    "将基于 Claude 检查点恢复工作区磁盘文件（+${preview.insertions} -${preview.deletions}）：\n\n$listPreview$more",
                    color = G.inkMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    claudeRewindTarget = null
                    scope.launch {
                        try {
                            val res = app.conversations.rewindClaudeFiles(key, userMsgId, dryRun = false)
                            if (res.canRewind) app.conversations.notify("已撤销 ${res.filesChanged.size} 个文件的磁盘改动")
                            else app.conversations.notify(res.error ?: "撤销失败")
                        } catch (e: Exception) {
                            app.conversations.notify("撤销失败：${e.message}")
                        }
                    }
                }) { Text("确认撤销", color = G.warning, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { claudeRewindTarget = null }) { Text("取消", color = G.inkMuted) } },
        )
    }
    inspectedDiffs?.let { diffs ->
        DiffOverviewSheet(null, diffs, cwd = cwd, onDismiss = { inspectedDiffs = null })
    }
    if (showDiffs) {
        DiffOverviewSheet(state?.turnDiff, fileEdits, cwd = cwd, onDismiss = { showDiffs = false })
    }
    if (showPreview) {
        LivePreviewSheet(app, onDismiss = { showPreview = false })
    }
    if (showVisualTemplates) {
        VisualTemplatePickerSheet(
            app = app,
            onPickTemplate = { tpl ->
                replaceDraft(if (draft.isBlank()) tpl.prompt else "$draft\n\n${tpl.prompt}")
                composerFocus.requestFocus()
            },
            onOpenLatexScratchpad = { showLatexScratchpad = true },
            onDismiss = { showVisualTemplates = false },
        )
    }
    if (showArtifactsDrawer) {
        ConversationArtifactsSheet(
            artifacts = conversationArtifacts,
            onOpenArtifact = { art -> studioArtifact = art },
            onOpenLatexScratchpad = { showLatexScratchpad = true },
            onDismiss = { showArtifactsDrawer = false },
        )
    }
    if (showExpandedComposer) {
        val inputReady = runtime.ready && state?.loaded == true && !needsTrust
        val isRun = state?.running == true
        val steerMode = inputReady && isRun && key.engine == EngineKind.CODEX && state.activeTurnId != null
        val sendAllowed = inputReady && (!isRun || steerMode) && state.reverting != true && (draft.isNotBlank() || attachments.isNotEmpty())
        ExpandedComposerDialog(
            value = draftValue,
            settings = settings,
            canSend = sendAllowed,
            onValueChange = { draftValue = it },
            onSend = { if (steerMode) submitSteer() else submitMessage() },
            onOpenArtifact = { art -> studioArtifact = art },
            onDismiss = { showExpandedComposer = false },
        )
    }
    if (showLatexScratchpad) {
        ArtifactStudioSheet(
            app = app,
            artifacts = emptyList(),
            cwd = cwd,
            editableScratchpad = true,
            onDismiss = { showLatexScratchpad = false },
        )
    }
    studioArtifact?.let { active ->
        val merged = remember(conversationArtifacts, active) {
            if (conversationArtifacts.any { it.id == active.id }) conversationArtifacts else conversationArtifacts + active
        }
        ArtifactStudioSheet(
            app = app,
            artifacts = merged,
            initialArtifactId = active.id,
            cwd = cwd,
            onDismiss = { studioArtifact = null },
        )
    }
}

/** 图 3 风格左下角权限直切入口：常驻无框极简排版，「完全访问」橙色醒目提示，展开时给轻微高光反馈。 */
@Composable
private fun ComposerPermissionPill(
    label: String,
    icon: ImageVector,
    dangerHighlight: Boolean,
    accentHighlight: Boolean,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = D.chipShape
    val fg = when {
        dangerHighlight -> G.warning
        accentHighlight || expanded -> G.accent
        else -> G.inkMuted
    }
    val bg = when {
        expanded && dangerHighlight -> G.warningTint
        expanded -> G.accentContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .let {
                if (expanded && dangerHighlight) it.border(0.75.dp, G.warning.copy(alpha = 0.45f), shape)
                else if (expanded) it.border(0.75.dp, G.accent.copy(alpha = 0.4f), shape)
                else it
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (dangerHighlight || accentHighlight || expanded) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 76.dp),
        )
        Icon(GaliaisIcons.ChevronDown, contentDescription = null, tint = fg.copy(alpha = 0.78f), modifier = Modifier.size(13.dp))
    }
}

/** 图 3 风格右下角紧凑上下文占用环形进度图标（点击弹出上下文用量悬浮窗）。 */
@Composable
private fun ContextUsageRing(
    ratio: Float?,
    hasData: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val effective = ratio ?: if (hasData) 0.12f else 0f
    val track = G.borderStrong.copy(alpha = if (hasData) 0.45f else 0.35f)
    val arc = when {
        !hasData -> Color.Transparent
        effective >= 0.85f -> G.danger
        effective >= 0.65f -> G.warning
        expanded -> G.accent
        else -> G.inkMuted
    }
    val desc = if (ratio != null) "上下文用量 ${(ratio * 100).toInt()}%" else "上下文用量"
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (expanded) G.accentContainer else Color.Transparent)
            .semantics { contentDescription = desc }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
            if (hasData) {
                drawArc(
                    color = arc,
                    startAngle = -90f,
                    sweepAngle = (effective.coerceIn(0.05f, 1f) * 360f),
                    useCenter = false,
                    style = stroke,
                )
            }
        }
    }
}

/** 图 3 风格右下角模型 & 推理强度合体触发器（主色模型名 + 次色推理档位，点击弹出 Codex 风格模型浮窗）。 */
@Composable
private fun ComposerModelTrigger(
    modelLabel: String,
    effortLabel: String?,
    enabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val shape = D.chipShape
    val bg = when {
        expanded -> G.accentContainer
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .let {
                if (expanded) it.border(0.75.dp, G.accent.copy(alpha = 0.4f), shape)
                else it
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            modelLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> G.inkSubtle
                expanded -> G.accent
                else -> G.ink
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 112.dp),
        )
        if (!effortLabel.isNullOrBlank()) {
            Text(
                effortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (expanded) G.accent.copy(alpha = 0.85f) else G.inkMuted,
                maxLines = 1,
            )
        }
        Icon(
            GaliaisIcons.ChevronDown,
            contentDescription = null,
            tint = when {
                !enabled -> G.inkSubtle
                expanded -> G.accent
                else -> G.inkMuted
            },
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Codex 风格悬浮上下文用量卡：直接展示窗口进度、输入/缓存命中/输出/推理 Token 与快速压缩入口。 */
@Composable
private fun ContextUsagePopoverContent(
    usage: TokenUsage?,
    running: Boolean,
    onCompact: () -> Unit,
    onOpenInfo: () -> Unit,
) {
    val win = usage?.contextWindow?.takeIf { it > 0 }
    val ratio = usage?.ratio()
    val pctColor = when {
        ratio == null -> G.accent
        ratio >= 0.85f -> G.danger
        ratio >= 0.65f -> G.warning
        else -> G.accent
    }
    val pctBg = when {
        ratio == null -> G.accentContainer
        ratio >= 0.85f -> G.dangerTint
        ratio >= 0.65f -> G.warningTint
        else -> G.accentContainer
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "上下文用量",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = G.ink,
                modifier = Modifier.weight(1f),
            )
            when {
                ratio != null -> TagPill("${(ratio * 100).toInt()}%", fg = pctColor, bg = pctBg)
                usage != null -> TagPill(formatCompactTokens(usage.totalTokens), fg = G.accent, bg = G.accentContainer)
                else -> TagPill("待统计", fg = G.inkMuted, bg = G.surfaceSubtle)
            }
        }

        if (usage != null) {
            if (win != null && ratio != null) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                    color = pctColor,
                    trackColor = G.surfaceSubtle,
                )
                Text(
                    "当前上下文 ${formatCompactTokens(usage.totalTokens)} / ${formatCompactTokens(win)} tokens",
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = G.inkMuted,
                )
            } else {
                Text(
                    "当前上下文 ${formatCompactTokens(usage.totalTokens)} tokens",
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = G.inkMuted,
                )
            }

            val cachePct = usage.cacheHitPercent()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(D.shape(10.dp))
                    .background(G.surfaceSubtle)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                UsageStatRow("本轮输入（未缓存）", "${formatCompactTokens(usage.inputTokens)} (${formatCompactTokens(usage.uncachedInputTokens)})")
                UsageStatRow(
                    "缓存读取",
                    if (cachePct != null && usage.cachedInputTokens > 0) "${formatCompactTokens(usage.cachedInputTokens)} (${"%.1f".format(cachePct)}%)"
                    else formatCompactTokens(usage.cachedInputTokens),
                )
                if (usage.cacheWriteTokens > 0L) {
                    UsageStatRow("缓存写入", formatCompactTokens(usage.cacheWriteTokens))
                }
                UsageStatRow("本轮输出", formatCompactTokens(usage.outputTokens))
                if (usage.reasoningTokens > 0) {
                    UsageStatRow("推理", formatCompactTokens(usage.reasoningTokens))
                }
                usage.sessionTotalTokens?.takeIf { it > usage.totalTokens }?.let { cum ->
                    UsageStatRow("会话累计", formatCompactTokens(cum))
                }
            }
        } else {
            Text(
                "发送消息后将自动显示本轮上下文窗口占用、输入与缓存命中统计。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
        }

        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PopoverChoiceChip(
                label = "压缩上下文",
                icon = GaliaisIcons.Compress,
                selected = false,
                enabled = !running,
                modifier = Modifier.weight(1f),
                onClick = onCompact,
            )
            PopoverChoiceChip(
                label = "会话详情",
                icon = GaliaisIcons.Info,
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = onOpenInfo,
            )
        }
    }
}

@Composable
private fun UsageStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = G.ink)
    }
}

/** Codex 风格悬浮模型切换卡：原地快速切换模型、推理强度、推理摘要详细度，并保留完整服务商抽屉入口。 */
@Composable
private fun ModelSwitcherPopoverContent(
    engine: EngineKind,
    activeProvider: CustomProvider?,
    models: List<ModelOption>,
    currentModel: String?,
    optionsContent: @Composable () -> Unit,
    onSelectModel: (String?) -> Unit,
    onOpenFullSheet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "选择模型",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = G.inkMuted,
                modifier = Modifier.weight(1f),
            )
            val providerChipLabel = activeProvider?.name ?: when (engine.id) {
                "codex" -> "OpenAI 官方"
                "claude" -> "Anthropic 官方"
                "antigravity" -> "Google 官方"
                else -> "默认配置"
            }
            TagPill(
                text = providerChipLabel,
                fg = if (activeProvider != null) G.accent else G.inkMuted,
                bg = if (activeProvider != null) G.accentContainer else G.surfaceSubtle,
                onClick = onOpenFullSheet,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 196.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (activeProvider == null) {
                GaliaisMenuRow(
                    title = "引擎默认",
                    subtitle = "遵循 ${engine.label} 默认配置",
                    selected = currentModel == null,
                    onClick = { onSelectModel(null) },
                )
            }
            if (currentModel != null && models.none { it.id == currentModel }) {
                GaliaisMenuRow(
                    title = currentModel,
                    subtitle = "自定义模型 ID",
                    selected = true,
                    onClick = { onSelectModel(currentModel) },
                )
            }
            models.forEach { opt ->
                GaliaisMenuRow(
                    title = opt.label,
                    subtitle = opt.id.takeIf { it != opt.label },
                    selected = currentModel == opt.id,
                    onClick = { onSelectModel(opt.id) },
                )
            }
            if (models.isEmpty() && activeProvider != null) {
                Text(
                    "该服务商尚未预设模型，可点击下方指定。",
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        optionsContent()

        Hairline()
        GaliaisMenuRow(
            title = "更多模型与服务商…",
            icon = GaliaisIcons.Gear,
            tintOverride = G.accent,
            onClick = onOpenFullSheet,
        )
    }
}

@Composable
private fun PopoverChoiceChip(
    label: String,
    icon: ImageVector? = null,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = D.chipShape
    val fg = when {
        !enabled -> G.inkSubtle
        selected -> G.accent
        else -> G.ink
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> G.accentContainer
                    else -> G.surfaceSubtle
                },
            )
            .border(
                0.75.dp,
                when {
                    selected -> G.accent.copy(alpha = 0.55f)
                    else -> G.border
                },
                shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

private fun formatCompactTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
