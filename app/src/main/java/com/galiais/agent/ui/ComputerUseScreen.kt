package com.galiais.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.PcHostDraft
import com.galiais.agent.engine.PcHostInfo
import com.galiais.agent.engine.PcWindowItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Computer Use 原生跨平台电脑操控中心（chroot / PRoot 通用：多机管理、实时画面触控直点、快捷热键、剪贴板协同、急停与审计）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComputerUseScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val runtime by app.runtime.snapshot.collectAsState()
    val hosts by app.computerUse.hosts.collectAsState()
    val defaultHostId by app.computerUse.defaultHostId.collectAsState()
    val emergencyStop by app.computerUse.emergencyStop.collectAsState()
    val pings by app.computerUse.pings.collectAsState()
    val auditEntries by app.computerUse.auditEntries.collectAsState()
    val liveFrame by app.computerUse.liveFrame.collectAsState()
    val discoveredHosts by app.computerUse.discoveredHosts.collectAsState()
    val sshHosts by app.envSsh.sshHosts.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("hosts") } // "hosts" | "remote" | "build" | "audit"
    var selectedHostId by rememberSaveable { mutableStateOf("") }
    var creatingHost by remember { mutableStateOf(false) }
    var prefillDraft by remember { mutableStateOf<PcHostDraft?>(null) }
    var editingHost by remember { mutableStateOf<PcHostInfo?>(null) }
    var pastePairOpen by remember { mutableStateOf(false) }
    var lanScanOpen by remember { mutableStateOf(false) }
    var lanScanning by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PcHostInfo?>(null) }
    var filesSheetHost by remember { mutableStateOf<PcHostInfo?>(null) }
    var sysSheetHost by remember { mutableStateOf<PcHostInfo?>(null) }

    var tapMode by rememberSaveable { mutableStateOf("click") } // "click" | "double_click" | "right_click" | "drag"
    var refreshMode by rememberSaveable { mutableStateOf("manual") } // "manual" | "auto2s" | "stream"
    var selectedMonitor by rememberSaveable { mutableIntStateOf(0) } // 0..N or -1 (all)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var lastTapMarker by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var inputText by remember { mutableStateOf("") }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var windowsSheetOpen by remember { mutableStateOf(false) }
    var windowsLoading by remember { mutableStateOf(false) }
    var remoteWindows by remember { mutableStateOf<List<PcWindowItem>>(emptyList()) }
    val pingingIds = remember { mutableStateMapOf<String, Boolean>() }

    val activeHost = remember(hosts, selectedHostId, defaultHostId) {
        hosts.firstOrNull { it.id == selectedHostId }
            ?: hosts.firstOrNull { it.id == defaultHostId }
            ?: hosts.firstOrNull()
    }

    LaunchedEffect(runtime.ready) {
        if (runtime.ready) {
            runCatching { app.computerUse.refreshHosts() }
            runCatching { app.computerUse.refreshAudit() }
            runCatching { app.computerUse.refreshBuildStatus() }
        }
    }

    LaunchedEffect(section, refreshMode, selectedMonitor, activeHost?.id, runtime.ready, emergencyStop) {
        if (section == "remote" && refreshMode != "manual" && activeHost != null && runtime.ready && !emergencyStop) {
            val intervalMs = if (refreshMode == "stream") 400L else 2000L
            val mw = if (refreshMode == "stream") 960 else 1080
            val q = if (refreshMode == "stream") 58 else 68
            while (true) {
                if (!capturing) {
                    capturing = true
                    try {
                        app.computerUse.captureScreenshot(activeHost.id, maxWidth = mw, quality = q, monitor = selectedMonitor)
                        remoteError = null
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        remoteError = e.message
                    } finally {
                        capturing = false
                    }
                }
                delay(intervalMs)
            }
        }
    }

    fun refreshFrameOnce(hostId: String, mon: Int = selectedMonitor) {
        if (capturing || !runtime.ready) return
        scope.launch {
            capturing = true
            remoteError = null
            try {
                app.computerUse.captureScreenshot(hostId, maxWidth = 1080, quality = 72, monitor = mon)
            } catch (e: Exception) {
                remoteError = e.message ?: "截屏失败"
            } finally {
                capturing = false
            }
        }
    }

    fun triggerLanScan() {
        lanScanOpen = true
        lanScanning = true
        scope.launch {
            try { app.computerUse.discoverLanHosts() }
            catch (e: Exception) { app.conversations.notify("局域网扫描失败：${e.message}") }
            finally { lanScanning = false }
        }
    }

    SubPageScaffold(
        title = "电脑操控",
        onBack = onBack,
        snackbar = snackbar,
        loading = capturing,
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        actions = {
            if (runtime.ready) {
                TagPill(
                    text = if (emergencyStop) "已急停" else "急停",
                    fg = if (emergencyStop) G.onAccent else G.danger,
                    bg = if (emergencyStop) G.danger else G.dangerTint,
                    onClick = {
                        scope.launch {
                            runCatching { app.computerUse.setEmergencyStop(!emergencyStop) }
                                .onFailure { app.conversations.notify("切换急停失败：${it.message}") }
                        }
                    },
                )
            }
        },
    ) {
        SegmentedPillBar(
            options = listOf(
                "hosts" to "主机 (${hosts.size})",
                "remote" to "遥控",
                "build" to "构建部署",
                "audit" to "审计",
            ),
            selected = section,
            onSelect = {
                section = it
                if (it == "audit" && runtime.ready) scope.launch { runCatching { app.computerUse.refreshAudit() } }
                if (it == "build" && runtime.ready) scope.launch { runCatching { app.computerUse.refreshBuildStatus() } }
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(D.space(10.dp)),
        ) {
            if (emergencyStop) {
                CrispCard(background = G.dangerTint, borderColor = G.danger.copy(alpha = 0.55f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("所有电脑操控已暂停", style = MaterialTheme.typography.titleSmall, color = G.danger, fontWeight = FontWeight.SemiBold)
                        Text("所有 AI 引擎和遥控页发往电脑的操控请求均被拦截，确认安全后可随时解除。", style = MaterialTheme.typography.bodySmall, color = G.ink)
                        PrimaryPillButton("解除急停并恢复", onClick = {
                            scope.launch { runCatching { app.computerUse.setEmergencyStop(false) } }
                        })
                    }
                }
            }

            if (!runtime.ready) {
                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "运行环境未启动，启动后可在 Root chroot 与免 Root PRoot 双模式下统一操控远程电脑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (!runtime.busy && runtime.bound) {
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        }
                    }
                }
            }

        when (section) {
            "hosts" -> {
                SectionHeader("已配对电脑") {
                    CompactActionButton("扫局域网", enabled = runtime.ready, onClick = ::triggerLanScan)
                    CompactActionButton("指引", onClick = { showGuide = true })
                    CompactActionButton("粘贴", enabled = runtime.ready, onClick = { pastePairOpen = true })
                    CompactActionButton("+ 添加", primary = true, enabled = runtime.ready, onClick = { prefillDraft = null; creatingHost = true })
                }
                if (hosts.isEmpty()) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "在 Windows、macOS 或 Linux 电脑启动代理，然后添加主机或导入配对信息。",
                                style = MaterialTheme.typography.bodySmall,
                                color = G.inkMuted,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryPillButton("构建并部署到电脑", onClick = { section = "build" })
                                SecondaryPillButton("扫描局域网电脑", enabled = runtime.ready, onClick = ::triggerLanScan)
                                SecondaryPillButton("粘贴配对 JSON", enabled = runtime.ready, onClick = { pastePairOpen = true })
                            }
                        }
                    }
                } else {
                    CrispCard(padding = PaddingValues(0.dp)) {
                        hosts.forEachIndexed { idx, h ->
                            val ping = pings[h.id]
                            val pinging = pingingIds[h.id] == true
                            Column(
                                Modifier.fillMaxWidth().clickable { editingHost = h }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(h.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (h.isDefault) TagPill("默认", fg = G.onAccent, bg = G.accent)
                                    if (h.engine == "native-go") TagPill("原生引擎", fg = G.success, bg = G.successTint)
                                    TagPill(h.osLabel, fg = G.inkMuted, bg = G.surfaceSubtle)
                                    TagPill(h.transportLabel, fg = G.accent, bg = G.accentContainer)
                                }
                                Text(h.endpointSummary, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (ping != null) {
                                    TagPill(
                                        text = if (ping.ok) "在线 · ${ping.latencyMs}ms · ${ping.hostname.ifBlank { ping.platform }} (${ping.via})" else "离线 · ${ping.error.take(36)}",
                                        fg = if (ping.ok) G.success else G.danger,
                                        bg = if (ping.ok) G.successTint else G.dangerTint,
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val permColor = when (h.permissionLevel) {
                                        "admin" -> G.danger to G.dangerTint
                                        "observe" -> G.inkMuted to G.surfaceSubtle
                                        else -> G.accent to G.accentContainer
                                    }
                                    TagPill(
                                        text = "权限：${h.permissionLabel}",
                                        fg = permColor.first,
                                        bg = permColor.second,
                                        onClick = {
                                            val next = when (h.permissionLevel) { "observe" -> "operate"; "operate" -> "admin"; else -> "observe" }
                                            scope.launch { runCatching { app.computerUse.setPermission(h.id, next) }.onFailure { app.conversations.notify("切换权限失败：${it.message}") } }
                                        },
                                    )
                                    if (runtime.ready) {
                                        TagPill(if (pinging) "探测中…" else "测速", fg = G.accent, bg = G.accentContainer, onClick = {
                                            if (!pinging) {
                                                pingingIds[h.id] = true
                                                scope.launch {
                                                    try { app.computerUse.pingHost(h.id) }
                                                    finally { pingingIds.remove(h.id) }
                                                }
                                            }
                                        })
                                        TagPill("进入遥控", fg = G.onAccent, bg = G.accent, onClick = {
                                            selectedHostId = h.id
                                            section = "remote"
                                            refreshFrameOnce(h.id)
                                        })
                                        TagPill("远程文件", fg = G.ink, bg = G.surfaceSubtle, onClick = { filesSheetHost = h })
                                        TagPill("进程系统", fg = G.ink, bg = G.surfaceSubtle, onClick = { sysSheetHost = h })
                                        if (h.transport == "ssh") {
                                            TagPill(
                                                text = if (h.sshTunnelActive) "停隧道" else "启隧道",
                                                fg = if (h.sshTunnelActive) G.success else G.inkMuted,
                                                bg = if (h.sshTunnelActive) G.successTint else G.surfaceSubtle,
                                                onClick = {
                                                    scope.launch {
                                                        runCatching { app.computerUse.toggleSshTunnel(h.id, !h.sshTunnelActive) }
                                                            .onFailure { app.conversations.notify("隧道操作失败：${it.message}") }
                                                    }
                                                },
                                            )
                                        }
                                        if (h.mac.isNotBlank()) {
                                            TagPill("远程开机", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                                scope.launch { runCatching { app.computerUse.wakeHost(h.id) }.onFailure { app.conversations.notify("WoL 发送失败：${it.message}") } }
                                            })
                                        }
                                    }
                                    if (!h.isDefault && runtime.ready) {
                                        TagPill("设默认", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = {
                                            scope.launch { runCatching { app.computerUse.setDefaultHost(h.id) } }
                                        })
                                    }
                                    TagPill("删除", fg = G.danger, bg = G.dangerTint, onClick = { pendingDelete = h })
                                }
                            }
                            if (idx < hosts.lastIndex) Hairline()
                        }
                    }
                }
            }

            "remote" -> {
                if (activeHost == null) {
                    CrispCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("请先在「电脑主机」页配对至少一台电脑。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            PrimaryPillButton("前往添加电脑", onClick = { section = "hosts"; creatingHost = true })
                        }
                    }
                } else {
                    if (hosts.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            hosts.forEach { h ->
                                SelectableChip(h.name, selected = h.id == activeHost.id, onClick = {
                                    selectedHostId = h.id
                                    refreshFrameOnce(h.id)
                                })
                            }
                        }
                    }

                    // 实时画面视口 + 触控直点映射（JPEG 解码卸载到 Default 后台线程，消除高速流主线程掉帧）
                    val frameForHost = liveFrame?.takeIf { it.hostId == activeHost.id }
                    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = frameForHost) {
                        val decoded = frameForHost?.let { frame ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap() }.getOrNull()
                            }
                        }
                        if (decoded != null || frameForHost == null) value = decoded
                    }
                    CrispCard(padding = PaddingValues(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(activeHost.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (zoom > 1.05f) {
                                    TagPill("${String.format("%.1f", zoom)}× 重置", fg = G.accent, bg = G.accentContainer, onClick = { zoom = 1f; pan = Offset.Zero })
                                } else if (frameForHost != null) {
                                    TagPill("${frameForHost.screenWidth}×${frameForHost.screenHeight}", fg = G.inkMuted, bg = G.surfaceSubtle)
                                }
                                TagPill(
                                    text = when (refreshMode) { "stream" -> "快速刷新"; "auto2s" -> "自动 2s"; else -> "手动" },
                                    fg = if (refreshMode != "manual") G.success else G.inkMuted,
                                    bg = if (refreshMode != "manual") G.successTint else G.surfaceSubtle,
                                    onClick = {
                                        refreshMode = when (refreshMode) { "manual" -> "auto2s"; "auto2s" -> "stream"; else -> "manual" }
                                    },
                                )
                                TagPill(if (capturing) "截屏中…" else "刷新画面", fg = G.accent, bg = G.accentContainer, onClick = { refreshFrameOnce(activeHost.id) })
                            }
                            if ((frameForHost?.monitors?.size ?: 0) > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    frameForHost!!.monitors.forEach { m ->
                                        SelectableChip(
                                            if (m.primary) "主屏 ${m.index + 1}" else "显示器 ${m.index + 1}",
                                            selected = selectedMonitor == m.index,
                                            onClick = { selectedMonitor = m.index; refreshFrameOnce(activeHost.id, m.index) },
                                        )
                                    }
                                    SelectableChip("全部屏幕", selected = selectedMonitor == -1, onClick = { selectedMonitor = -1; refreshFrameOnce(activeHost.id, -1) })
                                }
                            }
                            if (remoteError != null) {
                                Text(remoteError!!, style = MaterialTheme.typography.bodySmall, color = G.danger)
                            }
                            if (tapMode == "drag") {
                                Text(
                                    if (dragOrigin == null) "拖拽模式：先轻点起点，再轻点终点完成鼠标拖放" else "已锁定起点，再轻点终点即可拖拽释放",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = G.accent,
                                )
                            }
                            val ratio = if (frameForHost != null && frameForHost.height > 0) (frameForHost.width.toFloat() / frameForHost.height.toFloat()).coerceIn(0.6f, 2.4f) else (16f / 9f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(ratio)
                                    .clip(D.shape(10.dp))
                                    .background(Color(0xFF121418))
                                    .pointerInput(activeHost.id) {
                                        detectTransformGestures { _, panChange, zoomChange, _ ->
                                            val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                            zoom = nextZoom
                                            if (nextZoom <= 1.01f) {
                                                pan = Offset.Zero
                                            } else {
                                                val maxX = size.width * (nextZoom - 1f) / 2f
                                                val maxY = size.height * (nextZoom - 1f) / 2f
                                                pan = Offset(
                                                    (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                                    (pan.y + panChange.y).coerceIn(-maxY, maxY),
                                                )
                                            }
                                        }
                                    }
                                    .pointerInput(activeHost.id, tapMode, runtime.ready, emergencyStop, zoom, pan, dragOrigin) {
                                        detectTapGestures { offset ->
                                            if (!runtime.ready || emergencyStop || size.width <= 0 || size.height <= 0) return@detectTapGestures
                                            val cx = size.width / 2f
                                            val cy = size.height / 2f
                                            val ux = cx + (offset.x - cx - pan.x) / zoom
                                            val uy = cy + (offset.y - cy - pan.y) / zoom
                                            val nx = (ux / size.width.toFloat()).coerceIn(0f, 1f)
                                            val ny = (uy / size.height.toFloat()).coerceIn(0f, 1f)
                                            if (tapMode == "drag") {
                                                val start = dragOrigin
                                                if (start == null) {
                                                    dragOrigin = nx to ny
                                                    lastTapMarker = nx to ny
                                                } else {
                                                    dragOrigin = null
                                                    lastTapMarker = nx to ny
                                                    scope.launch {
                                                        try {
                                                            app.computerUse.mouseDragNormalized(activeHost.id, start.first, start.second, nx, ny)
                                                            delay(350L)
                                                            refreshFrameOnce(activeHost.id)
                                                        } catch (e: Exception) {
                                                            remoteError = e.message
                                                        }
                                                    }
                                                }
                                                return@detectTapGestures
                                            }
                                            lastTapMarker = nx to ny
                                            scope.launch {
                                                try {
                                                    app.computerUse.mouseNormalized(activeHost.id, tapMode, nx, ny)
                                                    delay(350L)
                                                    refreshFrameOnce(activeHost.id)
                                                } catch (e: Exception) {
                                                    remoteError = e.message
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                val frameBitmap = bitmap
                                if (frameBitmap != null) {
                                    Box(
                                        Modifier.fillMaxSize().graphicsLayer(
                                            scaleX = zoom,
                                            scaleY = zoom,
                                            translationX = pan.x,
                                            translationY = pan.y,
                                        ),
                                    ) {
                                        Image(
                                            bitmap = frameBitmap,
                                            contentDescription = "远程电脑桌面画面",
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        lastTapMarker?.let { (nx, ny) ->
                                            Canvas(Modifier.fillMaxSize()) {
                                                val c = Offset(nx * size.width, ny * size.height)
                                                drawCircle(Color(0xFFE05A47), radius = 14f / zoom, center = c, style = Stroke(width = 3f / zoom))
                                                drawCircle(Color(0x66E05A47), radius = 5f / zoom, center = c)
                                                dragOrigin?.let { (dx, dy) ->
                                                    val s = Offset(dx * size.width, dy * size.height)
                                                    drawCircle(Color(0xFF3B82F6), radius = 14f / zoom, center = s, style = Stroke(width = 3f / zoom))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            "点击「刷新画面」捕获远程桌面，支持双指缩放平移与精准触控操作",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFB8C0CC),
                                            textAlign = TextAlign.Center,
                                        )
                                        TagPill("立即截屏", fg = G.onAccent, bg = G.accent, onClick = { refreshFrameOnce(activeHost.id) })
                                    }
                                }
                            }
                            SegmentedPillBar(
                                options = listOf("click" to "单击", "double_click" to "双击", "right_click" to "右键", "drag" to "拖拽"),
                                selected = tapMode,
                                onSelect = { tapMode = it; dragOrigin = null },
                            )
                        }
                    }

                    // 快捷热键、滚轮与窗口管理
                    CrispCard(padding = PaddingValues(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("快捷热键与桌面操作", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "Win" to listOf("Win"),
                                    "Alt+Tab" to listOf("Alt", "Tab"),
                                    "Ctrl+C" to listOf("Ctrl", "c"),
                                    "Ctrl+V" to listOf("Ctrl", "v"),
                                    "Ctrl+Z" to listOf("Ctrl", "z"),
                                    "任务管理器" to listOf("Ctrl", "Shift", "Esc"),
                                    "Esc" to listOf("Escape"),
                                    "Enter" to listOf("Enter"),
                                ).forEach { (label, keys) ->
                                    TagPill(label, fg = G.ink, bg = G.surfaceSubtle, onClick = {
                                        scope.launch {
                                            runCatching { app.computerUse.sendHotkey(activeHost.id, keys) }
                                                .onFailure { app.conversations.notify("按键失败：${it.message}") }
                                        }
                                    })
                                }
                                TagPill("向上滚动", fg = G.ink, bg = G.surfaceSubtle, onClick = {
                                    scope.launch { runCatching { app.computerUse.scrollDesktop(activeHost.id, 360) } }
                                })
                                TagPill("向下滚动", fg = G.ink, bg = G.surfaceSubtle, onClick = {
                                    scope.launch { runCatching { app.computerUse.scrollDesktop(activeHost.id, -360) } }
                                })
                                TagPill("窗口列表", fg = G.accent, bg = G.accentContainer, onClick = {
                                    windowsSheetOpen = true
                                    windowsLoading = true
                                    scope.launch {
                                        try { remoteWindows = app.computerUse.listWindows(activeHost.id) }
                                        catch (e: Exception) { app.conversations.notify("获取窗口失败：${e.message}") }
                                        finally { windowsLoading = false }
                                    }
                                })
                                TagPill("远程文件", fg = G.accent, bg = G.accentContainer, onClick = { filesSheetHost = activeHost })
                                TagPill("进程与系统", fg = G.accent, bg = G.accentContainer, onClick = { sysSheetHost = activeHost })
                            }
                            Hairline()
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                label = { Text("Unicode 文本直输（不受电脑中文输入法中英状态影响）") },
                                singleLine = true,
                                shape = D.fieldShape,
                                colors = galiaisTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryPillButton("发送文本", enabled = inputText.isNotBlank() && runtime.ready, onClick = {
                                    val txt = inputText
                                    scope.launch {
                                        runCatching { app.computerUse.sendText(activeHost.id, txt, pressEnter = false); inputText = "" }
                                            .onFailure { app.conversations.notify("输入失败：${it.message}") }
                                    }
                                })
                                PrimaryPillButton("发送并回车", enabled = inputText.isNotBlank() && runtime.ready, onClick = {
                                    val txt = inputText
                                    scope.launch {
                                        runCatching { app.computerUse.sendText(activeHost.id, txt, pressEnter = true); inputText = "" }
                                            .onFailure { app.conversations.notify("输入失败：${it.message}") }
                                    }
                                })
                            }
                            Hairline()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryPillButton("拉取电脑剪贴板", enabled = runtime.ready, onClick = {
                                    scope.launch {
                                        runCatching {
                                            val txt = app.computerUse.getRemoteClipboard(activeHost.id)
                                            app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pc-clipboard", txt))
                                            app.conversations.notify("已同步电脑剪贴板到手机（${txt.length} 字）")
                                        }.onFailure { app.conversations.notify("拉取剪贴板失败：${it.message}") }
                                    }
                                })
                                SecondaryPillButton("推送手机剪贴板", enabled = runtime.ready, onClick = {
                                    val clip = app.getSystemService(ClipboardManager::class.java)?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                                    if (clip.isBlank()) {
                                        app.conversations.notify("手机剪贴板为空")
                                    } else {
                                        scope.launch {
                                            runCatching {
                                                app.computerUse.setRemoteClipboard(activeHost.id, clip)
                                                app.conversations.notify("已推送手机剪贴板到电脑（${clip.length} 字）")
                                            }.onFailure { app.conversations.notify("推送剪贴板失败：${it.message}") }
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }

            "build" -> {
                ComputerUseBuildTab(
                    app = app,
                    runtimeReady = runtime.ready,
                    sshAliases = sshHosts.map { it.alias }.filter { it.isNotBlank() },
                    onDeployed = { section = "hosts" },
                )
            }

            "audit" -> {
                CrispCard(padding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("安全护栏机制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                        Text(
                            "• 每次 RPC 强制校验 256-bit 令牌 HMAC-SHA256 + ±120s 时间戳 + 一次性 Nonce 防重放。\n" +
                                "• 三档权限：只读观察（observe） / 常规操控（operate） / 完全管理（admin）。\n" +
                                "• 高危操作（关机/重启/锁屏/强杀进程/删除文件/破坏性命令）触发手机端全局强审批弹窗。\n" +
                                "• 手机右上角「急停」或电脑端创建 ~/.solbraid-pc-agent/STOP 文件立即切断控制权。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                        )
                    }
                }
                SectionHeader("最近操控审计 (${auditEntries.size})") {
                    CompactActionButton("刷新日志", enabled = runtime.ready, onClick = {
                        scope.launch { runCatching { app.computerUse.refreshAudit() } }
                    })
                }
                if (auditEntries.isEmpty()) {
                    CrispCard { Text("暂无电脑操控审计记录", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
                } else {
                    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                    CrispCard(padding = PaddingValues(0.dp)) {
                        auditEntries.take(40).forEachIndexed { idx, e ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(e.action.ifBlank { "rpc" }, fontFamily = Mono, style = MaterialTheme.typography.labelLarge, color = G.ink, fontWeight = FontWeight.SemiBold)
                                    if (e.hostId.isNotBlank()) TagPill(e.hostId, fg = G.inkMuted, bg = G.surfaceSubtle)
                                    TagPill(
                                        text = when {
                                            e.blocked.isNotBlank() -> "拦截"
                                            e.ok -> "通过${if (e.ms > 0) " ${e.ms}ms" else ""}"
                                            else -> "失败"
                                        },
                                        fg = if (e.ok && e.blocked.isBlank()) G.success else G.danger,
                                        bg = if (e.ok && e.blocked.isBlank()) G.successTint else G.dangerTint,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(if (e.ts > 0) fmt.format(Date(e.ts)) else "", fontFamily = Mono, fontSize = 10.sp, color = G.inkSubtle)
                                }
                                val detail = listOf(e.via, e.reason, e.error).filter { it.isNotBlank() }.joinToString(" · ")
                                if (detail.isNotBlank()) {
                                    Text(detail, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (idx < auditEntries.take(40).lastIndex) Hairline()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        }
    }

    if (creatingHost || editingHost != null) {
        PcHostEditorSheet(
            app = app,
            initial = editingHost,
            prefillDraft = prefillDraft,
            sshAliases = sshHosts.map { it.alias }.filter { it.isNotBlank() },
            onDismiss = { creatingHost = false; editingHost = null; prefillDraft = null },
            onSave = { draft, isUpdate ->
                creatingHost = false; editingHost = null; prefillDraft = null
                scope.launch {
                    runCatching { app.computerUse.saveHost(draft, isUpdate) }
                        .onFailure { app.conversations.notify("保存主机失败：${it.message}") }
                }
            },
        )
    }

    if (lanScanOpen) {
        PcLanDiscoverySheet(
            app = app,
            discovered = discoveredHosts,
            scanning = lanScanning,
            onRescan = ::triggerLanScan,
            onPickHost = { found ->
                lanScanOpen = false
                prefillDraft = found.toDraft()
                creatingHost = true
            },
            onDismiss = { lanScanOpen = false },
        )
    }

    filesSheetHost?.let { host ->
        PcRemoteFilesSheet(
            app = app,
            host = host,
            defaultPhoneDir = settings.defaultCwd.ifBlank { "/home/agent/workspace" },
            onDismiss = { filesSheetHost = null },
        )
    }

    sysSheetHost?.let { host ->
        PcSystemProcessesSheet(
            app = app,
            host = host,
            onDismiss = { sysSheetHost = null },
        )
    }

    if (pastePairOpen) {
        PcPastePairSheet(
            app = app,
            onDismiss = { pastePairOpen = false },
            onParsed = { draft ->
                scope.launch {
                    runCatching {
                        app.computerUse.saveHost(draft, isUpdate = false)
                        val targetId = draft.id.ifBlank { app.computerUse.defaultHostId.value }
                        if (targetId.isNotBlank()) app.computerUse.pingHost(targetId)
                    }.onFailure { app.conversations.notify("配对失败：${it.message}") }
                }
            },
        )
    }

    if (showGuide) {
        PcAgentSetupGuideDialog(app = app, onDismiss = { showGuide = false })
    }

    if (windowsSheetOpen && activeHost != null) {
        PcWindowsListSheet(
            hostName = activeHost.name,
            windows = remoteWindows,
            loading = windowsLoading,
            onAction = { act, win ->
                scope.launch {
                    runCatching {
                        app.computerUse.controlWindow(activeHost.id, act, win.hwnd, win.title)
                        app.conversations.notify("窗口「${win.title.take(18)}」已执行 $act")
                        if (act == "focus" || act == "maximize") {
                            windowsSheetOpen = false
                            delay(300L)
                            refreshFrameOnce(activeHost.id)
                        } else {
                            remoteWindows = app.computerUse.listWindows(activeHost.id)
                        }
                    }.onFailure { app.conversations.notify("窗口操控失败：${it.message}") }
                }
            },
            onRefresh = {
                windowsLoading = true
                scope.launch {
                    try { remoteWindows = app.computerUse.listWindows(activeHost.id) }
                    catch (e: Exception) { app.conversations.notify("刷新窗口失败：${e.message}") }
                    finally { windowsLoading = false }
                }
            },
            onDismiss = { windowsSheetOpen = false },
        )
    }

    pendingDelete?.let { target ->
        GaliaisConfirmDialog(
            title = "移除电脑主机？",
            message = "确定移除「${target.name}」及其配对令牌吗？",
            confirmText = "移除",
            danger = true,
            onConfirm = {
                pendingDelete = null
                scope.launch { runCatching { app.computerUse.removeHost(target.id) } }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
