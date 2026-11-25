package com.galiais.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.R
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.ConversationSummary
import com.galiais.agent.engine.CreateOptions
import com.galiais.agent.engine.DEFAULT_WORKSPACE
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.ProviderAuthMode
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RuntimeSnapshot
import com.galiais.agent.engine.WorkspaceArchive
import com.galiais.agent.engine.WorkspaceFiles
import kotlinx.coroutines.launch

@Composable
fun ConversationListScreen(
    app: GaliaisApplication,
    modifier: Modifier,
    open: (ConversationKey) -> Unit,
    pendingCreate: PendingCreate? = null,
    onConsumePendingCreate: () -> Unit = {},
    pendingAddProject: Boolean = false,
    onConsumePendingAddProject: () -> Unit = {},
    onOpenDirectory: (String) -> Unit = {},
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val environment by app.runtime.environment.collectAsState()
    val summaries by app.conversations.summaries.collectAsState()
    val requests by app.conversations.requests.collectAsState()
    val tickets by app.superAgent.tickets.collectAsState()
    val loading by app.conversations.loading.collectAsState()
    val initialSyncDone by app.conversations.initialSyncDone.collectAsState()
    val settings by app.settings.state.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    var creating by remember { mutableStateOf<PendingCreate?>(null) }
    var addingProject by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showSuperAgentBoard by remember { mutableStateOf(false) }
    var openingPluginSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openingPluginPanel by remember { mutableStateOf<Pair<String, com.galiais.agent.engine.ChatArtifact>?>(null) }
    val homeBannerSlots = remember(hostPlugins) { app.extensions.activePluginUiSlots("home.banner") }
    val homeFooterSlots = remember(hostPlugins) { app.extensions.activePluginUiSlots("home.footer") }
    var homeBannerRefreshSeq by remember { mutableStateOf(0) }
    var visibleHomeBanners by remember { mutableStateOf<List<Triple<String, String, com.galiais.agent.engine.HostPluginSduiPayload>>>(emptyList()) }
    var visibleHomeFooters by remember { mutableStateOf<List<Triple<String, String, com.galiais.agent.engine.HostPluginSduiPayload>>>(emptyList()) }
    LaunchedEffect(runtime.ready, homeBannerSlots, hostPlugins.generationId, settings.defaultCwd, homeBannerRefreshSeq) {
        if (!runtime.ready || homeBannerSlots.isEmpty()) {
            visibleHomeBanners = emptyList()
            return@LaunchedEffect
        }
        visibleHomeBanners = homeBannerSlots.mapNotNull { slot ->
            val p = runCatching { app.extensions.renderHostPluginSlot(slot.pluginId, slot.id, settings.defaultCwd) }.getOrNull()
            if (p != null && !p.hidden && p.nodes.isNotEmpty()) Triple(slot.pluginId, slot.id, p) else null
        }
    }
    LaunchedEffect(runtime.ready, homeFooterSlots, hostPlugins.generationId, settings.defaultCwd, homeBannerRefreshSeq) {
        if (!runtime.ready || homeFooterSlots.isEmpty()) {
            visibleHomeFooters = emptyList()
            return@LaunchedEffect
        }
        visibleHomeFooters = homeFooterSlots.mapNotNull { slot ->
            val p = runCatching { app.extensions.renderHostPluginSlot(slot.pluginId, slot.id, settings.defaultCwd) }.getOrNull()
            if (p != null && !p.hidden && p.nodes.isNotEmpty()) Triple(slot.pluginId, slot.id, p) else null
        }
    }
    var exportingDir by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { WorkspaceFiles(app.runtime, app.envSsh) }
    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val src = exportingDir
        exportingDir = null
        if (uri == null || src == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val count = WorkspaceArchive.exportDirectoryToZipUri(context, uri, src, files)
                app.conversations.notify("已导出「${projectDisplayName(src)}」共 $count 个文件")
            } catch (e: Exception) {
                app.conversations.notify("导出失败：${e.message}")
            }
        }
    }
    val pendingKeys = remember(requests) { requests.mapNotNull { it.conversation }.toSet() }
    val filtered = remember(summaries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) summaries
        else summaries.filter { s ->
            s.title.lowercase().contains(q) ||
                s.preview.lowercase().contains(q) ||
                (s.cwd?.lowercase()?.contains(q) == true) ||
                s.key.engine.label.lowercase().contains(q)
        }
    }
    val projectGroups = remember(filtered, settings.projects, query) {
        fun norm(cwd: String?): String = cwd?.trim()?.trimEnd('/')?.ifEmpty { "/" } ?: UNASSIGNED_PROJECT
        val byDir = filtered.groupBy { norm(it.cwd) }
        val ordered = linkedSetOf<String>()
        settings.projects.forEach { ordered += norm(it) }
        byDir.keys.forEach { ordered += it }
        val q = query.trim()
        ordered.mapNotNull { dir ->
            val list = byDir[dir].orEmpty()
            if (q.isNotEmpty() && list.isEmpty()) null
            else if (dir == UNASSIGNED_PROJECT && list.isEmpty()) null
            else dir to list
        }
    }

    LaunchedEffect(pendingCreate) {
        if (pendingCreate != null) {
            creating = pendingCreate
            onConsumePendingCreate()
        }
    }
    LaunchedEffect(pendingAddProject) {
        if (pendingAddProject) {
            addingProject = true
            onConsumePendingAddProject()
        }
    }

    Box(modifier.fillMaxSize().galiaisCanvasBackground()) {
        Column(Modifier.fillMaxSize()) {
            // 极简页头：Wordmark + 编排 + 已归档 + 搜索 + 刷新
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_solbraid_brand),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(24.dp).clip(D.shape(6.dp)).border(0.75.dp, G.border, D.shape(6.dp)),
                    )
                    Text(
                        "Solbraid",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = G.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (runtime.ready) {
                    PluginSlotActionBar(
                        app = app,
                        slotName = "home.topbar",
                        cwd = settings.defaultCwd,
                        onOpenSurface = { pid, sid -> openingPluginSurface = pid to sid },
                        onOpenPanel = { pid, art -> openingPluginPanel = pid to art },
                    )
                    val activeTickets = tickets.count { it.active }
                    val needsAct = tickets.any { it.stage.needsAttention }
                    TagPill(
                        text = if (activeTickets > 0) "编排 $activeTickets" else "编排",
                        fg = if (needsAct) G.warning else G.ink,
                        bg = if (needsAct) G.warningTint else G.surfaceSubtle,
                        border = if (needsAct) G.warning.copy(alpha = 0.4f) else G.border,
                        onClick = { showSuperAgentBoard = true },
                    )
                }
                if (runtime.ready) {
                    TagPill("已归档", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = { showArchived = true })
                }
                if (summaries.isNotEmpty()) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            GaliaisIcons.Search,
                            contentDescription = "搜索会话",
                            tint = if (searching) G.accent else G.inkMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { app.conversations.refresh() },
                    enabled = runtime.ready && !loading,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        GaliaisIcons.Refresh,
                        contentDescription = "刷新会话",
                        tint = if (runtime.ready && !loading) G.inkMuted else G.inkSubtle,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (searching) {
                SearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索标题、内容或目录…",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            HeaderProgressSlot(loading || (runtime.ready && !initialSyncDone))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
            ) {
                val linkedRequests = requests.filter { it.conversation != null }
                val orphanRequests = requests.filter { it.conversation == null }
                val showAttentionStrip = runtime.ready && (linkedRequests.isNotEmpty() || tickets.any { it.active })
                if (showAttentionStrip) {
                    item(key = "attention-inbox") {
                        AttentionInboxStrip(
                            app = app,
                            requests = requests,
                            tickets = tickets,
                            onOpenConversation = open,
                            onOpenBoard = { showSuperAgentBoard = true },
                        )
                    }
                }
                items(visibleHomeBanners, key = { "home-slot-${it.first}-${it.second}" }) { (pid, sid, payload) ->
                    PluginSduiBannerCard(
                        app = app,
                        pluginId = pid,
                        slotId = sid,
                        payload = payload,
                        cwd = settings.defaultCwd,
                        onOpenSurface = { p, s -> openingPluginSurface = p to s },
                        onOpenPanel = { p, art -> openingPluginPanel = p to art },
                        onRefresh = { homeBannerRefreshSeq += 1 },
                    )
                }
                if (!runtime.ready) {
                    item {
                        RuntimeCard(
                            runtime = runtime.copy(mode = if (runtime.idle) settings.effectiveRuntimeMode else runtime.mode),
                            onStart = app.runtime::start,
                            onStop = app.runtime::stop,
                            installed = environment.isInstalledFor(settings.effectiveRuntimeMode),
                        )
                    }
                }

                if (!showAttentionStrip && linkedRequests.isNotEmpty()) {
                    item {
                        ApprovalCardContainer(badgeText = "${linkedRequests.size} 个请求等待确认") {
                            linkedRequests.take(3).forEach { request ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(D.shape(8.dp))
                                        .clickable { request.conversation?.let(open) }
                                        .padding(vertical = 5.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    EngineLabel(request.engine)
                                    Text(
                                        request.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = G.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("前往 →", style = MaterialTheme.typography.labelSmall, color = G.warning)
                                }
                            }
                        }
                    }
                }

                items(orphanRequests, key = { "orphan-${it.engine.id}-${it.requestId}" }) { request ->
                    RequestCard(request) { decision -> app.conversations.resolve(request, decision) }
                }

                if (query.isNotBlank() && projectGroups.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            Text("没有匹配「${query.trim()}」的项目或会话", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                        }
                    }
                } else if (
                    runtime.ready &&
                    initialSyncDone &&
                    !loading &&
                    summaries.isEmpty() &&
                    query.isBlank() &&
                    settings.projects.size <= 1 &&
                    settings.projects.all { it == DEFAULT_WORKSPACE }
                ) {
                    item {
                        val agyManifest = AgentCatalog.find("antigravity")
                        val hubStatuses by app.conversations.hub.statuses.collectAsState()
                        val hasAntigravity = agyManifest != null && (agyManifest.preinstalled || hubStatuses["antigravity"]?.installed == true)
                        EmptyWorkspaceWelcome(
                            showAntigravity = hasAntigravity,
                            onLaunch = { engine ->
                                creating = PendingCreate(settings.defaultCwd, engine = engine)
                            },
                        )
                    }
                }

                items(projectGroups, key = { "project-${it.first}" }) { (dir, sessions) ->
                    val expanded = query.isNotBlank() || dir !in settings.collapsedProjects
                    val canRemove = dir != UNASSIGNED_PROJECT && (settings.projects.size > 1 || dir != settings.defaultCwd) && sessions.isEmpty()
                    // 不叠加 animateItem() 弹簧位移动画且把 8dp 间距放进折叠体内部，彻底消除折叠/展开时下方分组回弹与 8dp 瞬间跳变。
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ProjectGroupHeader(
                            path = dir,
                            sessionCount = sessions.size,
                            expanded = expanded,
                            hasRunning = sessions.any { it.running },
                            hasPending = sessions.any { it.key in pendingKeys },
                            isDefault = dir == settings.defaultCwd,
                            canRemove = canRemove,
                            runtimeReady = runtime.ready,
                            onToggle = { app.settings.toggleProjectCollapsed(dir) },
                            onNewChat = { creating = PendingCreate(dir) },
                            onBrowseFiles = if (dir != UNASSIGNED_PROJECT) ({ onOpenDirectory(dir) }) else null,
                            onExportZip = if (dir != UNASSIGNED_PROJECT) ({
                                exportingDir = dir
                                exportZipLauncher.launch("${projectDisplayName(dir)}.zip")
                            }) else null,
                            onSetDefault = if (dir != UNASSIGNED_PROJECT) ({
                                app.settings.setDefaultCwd(dir)
                                app.conversations.notify("已将「${projectDisplayName(dir)}」设为默认项目")
                            }) else null,
                            onRemove = if (canRemove) ({
                                app.settings.removeProject(dir)
                                app.conversations.notify("已从项目列表移除「${projectDisplayName(dir)}」")
                            }) else null,
                        )
                        AnimatedExpandable(expanded = expanded) {
                            Spacer(Modifier.height(D.space(8.dp)))
                            val layoutMode = D.listLayout
                            val guideColor = G.borderStrong.copy(alpha = 0.7f)
                            val guideMod = if (layoutMode == "tree_guide" && sessions.isNotEmpty()) {
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp)
                                    .drawBehind {
                                        val x = 3.dp.toPx()
                                        drawLine(
                                            color = guideColor,
                                            start = Offset(x, 4.dp.toPx()),
                                            end = Offset(x, (size.height - 4.dp.toPx()).coerceAtLeast(4.dp.toPx())),
                                            strokeWidth = 1.5.dp.toPx(),
                                        )
                                    }
                                    .padding(start = 12.dp)
                            } else {
                                Modifier.fillMaxWidth().padding(start = if (layoutMode == "grouped") 4.dp else 10.dp)
                            }
                            Column(
                                modifier = guideMod,
                                verticalArrangement = Arrangement.spacedBy(if (layoutMode == "grouped") 3.dp else D.space(8.dp)),
                            ) {
                                if (sessions.isEmpty()) {
                                    val canTapStart = !runtime.ready && runtime.bound && !runtime.busy
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable(
                                            enabled = (runtime.ready && dir != UNASSIGNED_PROJECT) || canTapStart,
                                        ) {
                                            if (runtime.ready) creating = PendingCreate(dir)
                                            else app.runtime.start()
                                        },
                                        shape = D.shape(10.dp),
                                        color = G.surfaceSubtle,
                                        border = BorderStroke(0.75.dp, G.border),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                when {
                                                    !runtime.ready && runtime.busy -> "正在准备运行环境，就绪后可在此项目新建会话…"
                                                    !runtime.ready -> "启动运行环境后可在此项目新建会话"
                                                    !initialSyncDone || loading -> "正在加载会话列表…"
                                                    else -> "此项目暂无会话"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = G.inkMuted,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (canTapStart) {
                                                TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                                            }
                                        }
                                    }
                                } else {
                                    sessions.forEachIndexed { idx, summary ->
                                        val groupPos = if (layoutMode == "grouped" && sessions.size > 1) {
                                            when (idx) {
                                                0 -> CardGroupPos.FIRST
                                                sessions.lastIndex -> CardGroupPos.LAST
                                                else -> CardGroupPos.MIDDLE
                                            }
                                        } else CardGroupPos.SINGLE
                                        ConversationRow(
                                            app = app,
                                            summary = summary,
                                            hasPendingRequest = summary.key in pendingKeys,
                                            open = open,
                                            showCwd = false,
                                            groupPosition = groupPos,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (visibleHomeFooters.isNotEmpty()) {
                    items(visibleHomeFooters, key = { "home-footer:${it.first}:${it.second}" }) { (pid, sid, payload) ->
                        PluginSduiBannerCard(
                            app = app,
                            pluginId = pid,
                            slotId = sid,
                            payload = payload,
                            cwd = settings.defaultCwd,
                            onOpenSurface = { p, s -> openingPluginSurface = p to s },
                            onOpenPanel = { p, art -> openingPluginPanel = p to art },
                            onRefresh = { homeBannerRefreshSeq += 1 },
                        )
                    }
                }
            }
        }

        if (D.navBarStyle != "split_fab") {
            PrimaryFab(
                text = "创建项目",
                icon = GaliaisIcons.Add,
                onClick = { addingProject = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (addingProject) {
        AddProjectSheet(
            app = app,
            onAdded = { path ->
                addingProject = false
                app.conversations.notify("已添加项目：${projectDisplayName(path)}")
            },
            onDismiss = { addingProject = false },
        )
    }

    if (showArchived) {
        ArchivedThreadsSheet(
            app = app,
            onRestored = { restoredKey -> open(restoredKey) },
            onDismiss = { showArchived = false },
        )
    }

    creating?.let { request ->
        NewConversationSheet(
            app = app,
            initial = request,
            onDismiss = { creating = null },
            onCreated = { key ->
                request.referenceFile?.let { file -> app.conversations.putInitialDraft(key, "@$file ") }
                creating = null
                open(key)
            },
        )
    }
    if (showSuperAgentBoard) {
        SuperAgentBoardSheet(
            app = app,
            defaultCwd = settings.defaultCwd,
            onOpenConversation = open,
            onDismiss = { showSuperAgentBoard = false },
        )
    }
    openingPluginSurface?.let { (pid, sid) ->
        PluginSduiSheet(
            app = app,
            pluginId = pid,
            surfaceId = sid,
            cwd = settings.defaultCwd,
            onOpenPanel = { panelPid, art ->
                openingPluginSurface = null
                openingPluginPanel = panelPid to art
            },
            onOpenSurface = { nextPid, nextSid -> openingPluginSurface = nextPid to nextSid },
            onDismiss = { openingPluginSurface = null },
        )
    }
    openingPluginPanel?.let { (pid, art) ->
        ArtifactStudioSheet(
            app = app,
            artifacts = listOf(art),
            initialArtifactId = art.id,
            cwd = settings.defaultCwd,
            pluginId = pid,
            onDismiss = { openingPluginPanel = null },
        )
    }
}

@Composable
private fun EmptyWorkspaceWelcome(showAntigravity: Boolean = false, onLaunch: (EngineKind) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("开始编码任务", style = MaterialTheme.typography.headlineSmall, color = G.ink)
            Text(
                "选择 Agent，在项目中创建会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = G.inkMuted,
            )
        }
        Spacer(Modifier.height(4.dp))
        QuickEngineCard(
            engine = EngineKind.CODEX,
            title = "使用 Codex",
            subtitle = "支持任务执行、代码审查和运行中补充指令",
            onClick = { onLaunch(EngineKind.CODEX) },
        )
        QuickEngineCard(
            engine = EngineKind.CLAUDE,
            title = "使用 Claude Code",
            subtitle = "支持任务执行、计划模式和会话分支",
            onClick = { onLaunch(EngineKind.CLAUDE) },
        )
        if (showAntigravity) {
            QuickEngineCard(
                engine = EngineKind.of("antigravity"),
                title = "使用 Antigravity",
                subtitle = "支持多模型代理编排、工具链与代码编辑",
                onClick = { onLaunch(EngineKind.of("antigravity")) },
            )
        }
    }
}

@Composable
private fun QuickEngineCard(engine: EngineKind, title: String, subtitle: String, onClick: () -> Unit) {
    CrispCard(onClick = onClick, padding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.padding(D.space(16.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AgentAppIcon(engine = engine, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
            }
            Text("→", style = MaterialTheme.typography.titleMedium, color = G.inkSubtle)
        }
    }
}

@Composable
fun RuntimeBadge(runtime: RuntimeSnapshot) {
    val pct = runtime.progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
    val (label, dotColor, bgColor) = when {
        runtime.ready -> Triple("运行中", G.success, G.successTint)
        runtime.state == "error" -> Triple("运行异常", G.danger, G.dangerTint)
        runtime.maintenance -> Triple("维护中", G.warning, G.warningTint)
        runtime.state == "stopping" -> Triple("停止中", G.warning, G.warningTint)
        runtime.busy && runtime.firstTimeSetup && pct != null -> Triple("配置 $pct%", G.accent, G.accentContainer)
        runtime.busy && pct != null -> Triple("启动 $pct%", G.warning, G.warningTint)
        runtime.busy -> Triple("启动中", G.warning, G.warningTint)
        else -> Triple("已停止", G.inkSubtle, G.surfaceSubtle)
    }
    Row(
        modifier = Modifier
            .clip(D.shape(999.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (runtime.busy) PulsingDot(dotColor, size = 6.5.dp)
        else Box(Modifier.size(6.5.dp).background(dotColor, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (runtime.ready || runtime.busy || runtime.state == "error") dotColor else G.inkMuted, fontWeight = FontWeight.SemiBold)
    }
}

private val FIRST_SETUP_STEPS = listOf("安装系统", "准备组件", "连接引擎")

@Composable
fun RuntimeCard(
    runtime: RuntimeSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    installed: Boolean = false,
    onRestart: (() -> Unit)? = null,
) {
    val isProot = runtime.mode == RUNTIME_MODE_PROOT
    CrispCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地运行环境", style = MaterialTheme.typography.titleMedium, color = G.ink, modifier = Modifier.weight(1f))
            TagPill(if (isProot) "免 Root" else "Root", fg = G.inkMuted, bg = G.surfaceSubtle)
            RuntimeBadge(runtime)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            runtime.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (runtime.state == "error") G.danger else G.inkMuted,
        )
        if (runtime.busy) {
            Spacer(Modifier.height(10.dp))
            val p = runtime.progress
            if (p != null && runtime.stepTotal > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "步骤 ${runtime.stepIndex}/${runtime.stepTotal} · ${runtime.stepLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = G.accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(p.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = G.accent,
                    )
                }
            }
            GaliaisProgressBar(thin = false, progress = p)
            if (runtime.firstTimeSetup && runtime.stepTotal == FIRST_SETUP_STEPS.size) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FIRST_SETUP_STEPS.forEachIndexed { idx, name ->
                        val stepNo = idx + 1
                        val done = runtime.stepIndex > stepNo
                        val active = runtime.stepIndex == stepNo
                        val dotColor = when {
                            done -> G.success
                            active -> G.accent
                            else -> G.borderStrong
                        }
                        val textColor = when {
                            done -> G.success
                            active -> G.ink
                            else -> G.inkSubtle
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (active) PulsingDot(dotColor, size = 6.dp)
                            else Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor,
                                fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
        if (!runtime.ready && !runtime.busy && !installed) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (isProot)
                    "将从安装包准备免 Root 运行环境。环境安装无需下载，在线模型需要网络连接。"
                else
                    "将从安装包准备 Root 运行环境，需要 Root 权限。在线模型需要网络连接。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkSubtle,
            )
        }
        if (runtime.maintenance) return@CrispCard
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runtime.busy || runtime.ready) {
                if (runtime.ready && onRestart != null) {
                    SecondaryPillButton("重启环境", onClick = onRestart)
                }
                SecondaryPillButton("停止运行环境", onClick = onStop, enabled = runtime.state != "stopping")
            } else {
                PrimaryPillButton(
                    text = if (runtime.state == "error") "重新启动" else if (!installed) "安装运行环境" else "启动运行环境",
                    onClick = onStart,
                    enabled = runtime.bound,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    app: GaliaisApplication,
    summary: ConversationSummary,
    hasPendingRequest: Boolean,
    open: (ConversationKey) -> Unit,
    modifier: Modifier = Modifier,
    showCwd: Boolean = true,
    groupPosition: CardGroupPos = CardGroupPos.SINGLE,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier.padding(start = if (summary.parent != null) 18.dp else 0.dp)) {
        CrispCard(
            onClick = { open(summary.key) },
            onLongClick = { menu = true },
            padding = PaddingValues(0.dp),
            groupPosition = groupPosition,
        ) {
            Column(Modifier.padding(D.space(14.dp)), verticalArrangement = Arrangement.spacedBy(D.space(5.dp))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    EngineLabel(summary.key.engine)
                    if (summary.parent != null) {
                        TagPill("分支/委派", fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                    if (hasPendingRequest) {
                        TagPill("待确认", fg = G.warning, bg = G.warningTint)
                    }
                    Spacer(Modifier.weight(1f))
                    if (summary.running) PulsingDot(G.warning, size = 7.dp)
                    Text(relativeTime(summary.updatedAt), style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                }
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = summary.preview.trim().replace('\n', ' ')
                if (preview.isNotEmpty() && !preview.startsWith(summary.title.take(24))) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showCwd) {
                    summary.cwd?.let {
                        Text(
                            it,
                            fontFamily = Mono,
                            fontSize = 11.5.sp,
                            color = G.inkSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        GaliaisDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GaliaisMenuRow(title = "重命名", icon = GaliaisIcons.Code, onClick = { menu = false; renaming = true })
                if (AgentCatalog.capabilitiesOf(summary.key.engine).fork) {
                    GaliaisMenuRow(
                        title = "创建会话分支",
                        icon = GaliaisIcons.GitBranch,
                        onClick = {
                            menu = false
                            scope.launch {
                                try {
                                    val forked = app.conversations.fork(summary.key)
                                    if (forked != null) {
                                        app.conversations.notify("已创建会话分支")
                                        open(forked)
                                    }
                                } catch (e: Exception) {
                                    app.conversations.notify("创建分支失败：${e.message}")
                                }
                            }
                        },
                    )
                }
                if (AgentCatalog.capabilitiesOf(summary.key.engine).archive) {
                    GaliaisMenuRow(
                        title = "归档会话",
                        icon = GaliaisIcons.Archive,
                        onClick = { menu = false; app.conversations.archive(summary.key) },
                    )
                }
                Hairline(Modifier.padding(vertical = 2.dp))
                GaliaisMenuRow(title = "删除", icon = GaliaisIcons.Close, danger = true, onClick = { menu = false; deleting = true })
            }
        }
    }

    if (renaming) {
        TextInputDialog("重命名会话", summary.title, onDismiss = { renaming = false }) {
            app.conversations.rename(summary.key, it)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("删除会话？", color = G.ink) },
            text = { Text("将删除「${summary.title}」的 ${summary.key.engine.label} 会话记录，不会删除工作区文件。此操作不能撤销。", color = G.inkMuted) },
            confirmButton = {
                TextButton(onClick = { app.conversations.delete(summary.key); deleting = false }) {
                    Text("删除", color = G.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text("取消", color = G.inkMuted) }
            },
        )
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.surface,
        shape = D.dialogShape,
        title = { Text(title, color = G.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(200) },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text("确定", color = if (value.isNotBlank()) G.ink else G.inkSubtle, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationSheet(
    app: GaliaisApplication,
    initial: PendingCreate,
    onDismiss: () -> Unit,
    onCreated: (ConversationKey) -> Unit,
) {
    val settings by app.settings.state.collectAsState()
    val hubStatuses by app.conversations.hub.statuses.collectAsState()
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val installedAgents = remember(settings.customAgents, hubStatuses, hostPlugins, initial.engine) {
        val builtins = AgentCatalog.all().filter { it.preinstalled || hubStatuses[it.id]?.installed == true }.map { it.engineKind }
        val pluginDrivers = if (hostPlugins.safeMode) emptyList() else hostPlugins.drivers
            .filter { it.executable && builtins.none { b -> b.id == it.id } }
            .map { EngineKind(it.id, it.name) }
        val list = builtins + pluginDrivers
        if (initial.engine != null && initial.engine !in list) list + initial.engine else list
    }
    val startEngine = initial.engine ?: EngineKind.CODEX
    var engine by remember { mutableStateOf(startEngine) }
    LaunchedEffect(installedAgents) {
        if (engine !in installedAgents) {
            engine = installedAgents.firstOrNull() ?: EngineKind.CODEX
        }
    }
    var providerId by remember(startEngine, settings.codexProviderId, settings.claudeProviderId, settings.agentProviders) {
        mutableStateOf(settings.defaultProviderIdFor(startEngine))
    }
    var model by remember(startEngine, settings.codexModel, settings.claudeModel, settings.agentModels, settings.codexProviderId, settings.claudeProviderId, settings.agentProviders) {
        val initProvider = settings.defaultProviderIdFor(startEngine)
        val initModel = settings.defaultModelFor(startEngine)
        mutableStateOf(initModel ?: settings.provider(initProvider)?.models?.firstOrNull() ?: "")
    }
    var providerMenu by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(initial.cwd) }
    var trust by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var arenaMode by remember { mutableStateOf(false) }
    var arenaAgents by remember(installedAgents) { mutableStateOf(installedAgents.take(2).toSet()) }
    var arenaPrompt by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val selectedProvider = settings.provider(providerId)
    val officialLabel = when (engine.id) {
        "codex" -> "OpenAI 官方"
        "claude" -> "Anthropic 官方"
        else -> "Agent 自带配置"
    }
    val trusted = cwd.trim() in settings.trustedProjects
    val selectableProviders = settings.allSelectableProviders()
    val needsKey = engine == EngineKind.CLAUDE && selectedProvider == null && !settings.hasClaudeKey && !settings.hasClaudeOauthToken && settings.claudeBaseUrl == null
    val needsModel = selectedProvider != null && !selectedProvider.id.startsWith(com.galiais.agent.engine.CustomProvider.GROUP_PREFIX) && model.isBlank() && engine.id in setOf("codex", "claude")

    GaliaisSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("新建会话", style = MaterialTheme.typography.titleLarge, color = G.ink)

            if (installedAgents.size >= 2) {
                SegmentedPillBar(
                    options = listOf(false to "单个 Agent", true to "多个 Agent 对比"),
                    selected = arenaMode,
                    onSelect = { arenaMode = it },
                    enabled = !busy,
                )
            }

            if (arenaMode) {
                Text(
                    "选择至少 2 个 Agent 执行同一任务。它们共享工作目录，文件修改可能相互影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.inkMuted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    installedAgents.forEach { item ->
                        val picked = item in arenaAgents
                        AgentSelectableChip(
                            engine = item,
                            selected = picked,
                            onClick = {
                                arenaAgents = if (picked && arenaAgents.size > 1) arenaAgents - item else arenaAgents + item
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = arenaPrompt,
                    onValueChange = { arenaPrompt = it },
                    label = { Text("任务描述") },
                    minLines = 2,
                    maxLines = 4,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
            // Agent 选择（支持所有已安装的内置与第三方 Agent，统一带真实应用图标）
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedAgents.forEach { item ->
                    AgentSelectableChip(
                        engine = item,
                        selected = engine == item,
                        onClick = {
                            engine = item
                            val nextProvider = settings.defaultProviderIdFor(item)
                            providerId = nextProvider
                            model = settings.defaultModelFor(item) ?: settings.provider(nextProvider)?.models?.firstOrNull() ?: ""
                        },
                    )
                }
            }

            // 服务商选择行（含启用的自定义服务商与路由组）
            if (selectableProviders.isNotEmpty()) {
                Box {
                    Surface(
                        onClick = { providerMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = D.shape(12.dp),
                        color = G.surfaceSubtle,
                        border = BorderStroke(0.75.dp, G.border),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("服务商", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    selectedProvider?.let { "${it.name} · ${it.displayTypeBadge()}" } ?: officialLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = G.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(GaliaisIcons.ChevronDown, contentDescription = "选择服务商", tint = G.inkMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    GaliaisDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            GaliaisMenuRow(
                                title = officialLabel,
                                selected = providerId == null,
                                onClick = {
                                    providerId = null
                                    model = settings.defaultModelFor(engine).takeIf { settings.defaultProviderIdFor(engine) == null } ?: ""
                                    providerMenu = false
                                },
                            )
                            selectableProviders.forEach { p ->
                                GaliaisMenuRow(
                                    title = p.name,
                                    subtitle = if (p.isRoutingGroup) "多服务商路由组" else p.displayTypeBadge(),
                                    selected = providerId == p.id,
                                    onClick = {
                                        providerId = p.id
                                        model = p.models.firstOrNull() ?: if (p.isRoutingGroup) "auto" else ""
                                        providerMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 模型选择与快速标签
            if (selectedProvider != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.trim() },
                        label = { Text("模型 ID") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (selectedProvider.models.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedProvider.models.forEach { m ->
                                SelectableChip(text = m, selected = m == model, onClick = { model = m })
                            }
                        }
                    }
                }
            }
            }

            // 项目工作目录（支持快捷切换已添加项目）
            if (settings.projects.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    settings.projects.forEach { proj ->
                        SelectableChip(
                            text = projectDisplayName(proj),
                            selected = cwd.trim().trimEnd('/').ifEmpty { "/" } == proj,
                            onClick = { cwd = proj },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = { Text("工作目录（Linux 路径）") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )

            initial.referenceFile?.let { file ->
                TagPill("将在新会话中引用：@$file", fg = G.ink, bg = G.surfaceSubtle, border = G.border)
            }

            val includesClaude = if (arenaMode) EngineKind.CLAUDE in arenaAgents else engine == EngineKind.CLAUDE
            if (includesClaude && !trusted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(D.shape(10.dp))
                        .background(G.surfaceSubtle)
                        .toggleable(value = trust, role = Role.Checkbox, onValueChange = { trust = it })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    GaliaisCheckbox(trust)
                    Spacer(Modifier.width(4.dp))
                    Text("我信任此项目，允许 Claude Code 加载其中的配置与钩子", style = MaterialTheme.typography.bodySmall, color = G.ink)
                }
            }

            if (!arenaMode && needsKey) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.warningTint) {
                    Text(
                        "未检测到已保存的 Claude API Key / OAuth Token。若你已在终端完成 OAuth 登录可直接创建；否则请在设置中配置凭据或选择自定义服务商。",
                        color = G.warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            if (!arenaMode && selectedProvider != null && !selectedProvider.isRoutingGroup && selectedProvider.authMode != ProviderAuthMode.NONE && !selectedProvider.hasApiKey) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.warningTint) {
                    Text(
                        "服务商「${selectedProvider.name}」尚未填写 API Key，发送消息可能鉴权失败；可先前往「设置 → 服务商」补充密钥。",
                        color = G.warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            if (!arenaMode && needsModel) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Text("请为自定义服务商选择或填写模型 ID。", color = G.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
            if (busy) {
                GaliaisProgressBar(thin = false)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryPillButton("取消", onClick = onDismiss, enabled = !busy)
                val cwdValid = cwd.trim().startsWith("/")
                val canCreate = if (arenaMode) {
                    cwdValid && arenaAgents.size >= 2 && arenaPrompt.isNotBlank() && (!includesClaude || trusted || trust)
                } else {
                    cwdValid && !needsModel && (!includesClaude || trusted || trust)
                }
                PrimaryPillButton(
                    text = if (arenaMode) "并行启动 (${arenaAgents.size})" else "创建",
                    modifier = Modifier.weight(1f),
                    enabled = !busy && canCreate,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val path = cwd.trim().trimEnd('/').ifEmpty { "/" }
                                app.settings.addProject(path)
                                if (includesClaude && !trusted) app.settings.trust(path)
                                if (arenaMode) {
                                    val ordered = installedAgents.filter { it in arenaAgents }
                                    val keys = app.conversations.createArena(ordered, path, arenaPrompt)
                                    onCreated(keys.first())
                                } else {
                                    onCreated(
                                        app.conversations.create(
                                            engine,
                                            path,
                                            CreateOptions(
                                                providerId = providerId ?: "",
                                                model = model.trim().ifBlank { null },
                                            ),
                                        ),
                                    )
                                }
                            } catch (error: Exception) {
                                app.conversations.notify("新建会话失败：${error.message}")
                                busy = false
                            }
                        }
                    },
                )
            }
        }
    }
}

fun relativeTime(millis: Long): String {
    val delta = (System.currentTimeMillis() - millis) / 1000
    return when {
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60} 分钟前"
        delta < 86_400 -> "${delta / 3600} 小时前"
        delta < 86_400 * 30 -> "${delta / 86_400} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(millis))
    }
}
