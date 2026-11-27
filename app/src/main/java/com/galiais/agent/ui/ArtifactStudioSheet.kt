package com.galiais.agent.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.LinuxRuntime
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import com.galiais.agent.engine.DEFAULT_LATEX_SCRATCHPAD
import com.galiais.agent.engine.LatexEngineStatus
import com.galiais.agent.engine.VisualArtifactTemplate
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.visualArtifactTemplates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private const val ARTIFACT_HOST = "artifact.solbraid.local"

/** 聊天时间线中的内联产物卡片（用于 `<antArtifact>`、Widget 与可预览代码块）。 */
@Composable
fun InlineArtifactCard(
    artifact: ChatArtifact,
    onOpen: (ChatArtifact) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (artifact.kind) {
        ArtifactKind.LATEX, ArtifactKind.MATH -> G.artifact
        ArtifactKind.MERMAID, ArtifactKind.SVG -> G.codex
        ArtifactKind.CHART -> G.success
        ArtifactKind.HTML, ArtifactKind.REACT -> G.accent
        else -> G.inkMuted
    }
    val tint = when (artifact.kind) {
        ArtifactKind.LATEX, ArtifactKind.MATH -> G.artifactTint
        ArtifactKind.MERMAID, ArtifactKind.SVG -> G.codexTint
        ArtifactKind.CHART -> G.successTint
        ArtifactKind.HTML, ArtifactKind.REACT -> G.accentContainer
        else -> G.surfaceSubtle
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(D.shape(12.dp))
            .clickable { onOpen(artifact) },
        shape = D.shape(12.dp),
        color = G.surface,
        border = BorderStroke(0.75.dp, accent.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = D.shape(9.dp), color = tint, modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when (artifact.kind) {
                        ArtifactKind.LATEX, ArtifactKind.MATH -> GaliaisIcons.FileLines
                        ArtifactKind.HTML, ArtifactKind.REACT -> GaliaisIcons.Globe
                        ArtifactKind.SVG, ArtifactKind.MERMAID -> GaliaisIcons.Layers
                        ArtifactKind.CHART -> GaliaisIcons.Sparkles
                        else -> GaliaisIcons.Code
                    }
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    artifact.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = G.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(artifact.kind.label, fg = accent, bg = tint)
                    if (artifact.version > 1) TagPill("v${artifact.version}", fg = G.inkMuted, bg = G.surfaceSubtle)
                    if (artifact.streaming) {
                        Text("生成中…", style = MaterialTheme.typography.labelSmall, color = accent)
                    } else {
                        Text("${artifact.content.lines().size} 行", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                    }
                }
            }
            TagPill(
                text = if (artifact.kind == ArtifactKind.LATEX) "编译预览" else "打开预览",
                fg = accent,
                bg = tint,
                onClick = { onOpen(artifact) },
            )
        }
    }
}

/** 离线产物工作台（支持 HTML / SVG / Mermaid / LaTeX / React / Markdown / CSV 预览、视口切换、实时控制台、编辑编译、PDF 导出与写入工作区）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactStudioSheet(
    app: GaliaisApplication,
    artifacts: List<ChatArtifact>,
    initialArtifactId: String? = null,
    cwd: String? = null,
    savePath: String? = null,
    editableScratchpad: Boolean = false,
    pluginId: String? = null,
    externalLoading: Boolean = false,
    externalError: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val dark = G.dark

    val list = remember(artifacts, editableScratchpad) {
        if (artifacts.isNotEmpty()) artifacts
        else listOf(
            ChatArtifact(
                id = "latex-scratchpad",
                identifier = "latex-scratchpad",
                title = "离线 LaTeX 编译器",
                kind = ArtifactKind.LATEX,
                language = "latex",
                content = DEFAULT_LATEX_SCRATCHPAD,
            ),
        )
    }
    var selectedId by remember(initialArtifactId, list) {
        mutableStateOf(initialArtifactId?.takeIf { id -> list.any { it.id == id } } ?: list.last().id)
    }
    val current = list.firstOrNull { it.id == selectedId } ?: list.last()
    val editedByArtifact = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var tab by remember { mutableIntStateOf(0) } // 0=预览 1=源码/编辑
    val editableContent = editedByArtifact[current.id] ?: current.content
    fun setEditableContent(next: String) {
        if (next == current.content) editedByArtifact.remove(current.id) else editedByArtifact[current.id] = next
    }
    val hostPlugins by app.extensions.hostPlugins.collectAsState()
    val studioSlots = remember(hostPlugins) { app.extensions.activePluginUiSlots("studio.toolbar") }
    var openingSlotSurface by remember { mutableStateOf<Pair<String, String>?>(null) }
    var viewport by remember { mutableStateOf("auto") } // auto / mobile / desktop
    var consoleLogs by remember(current.id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showConsole by remember(current.id) { mutableStateOf(false) }
    var renderNonce by remember { mutableIntStateOf(0) }
    val webViewHolder = remember { object { var view: WebView? = null; var seq = 0; var lastViewport = "auto" } }
    var pageLoaded by remember { mutableStateOf(false) }
    var contentRendered by remember { mutableStateOf(false) }
    var latexStatus by remember { mutableStateOf<LatexEngineStatus?>(null) }
    var compilingNative by remember { mutableStateOf(false) }
    var compileLog by remember { mutableStateOf<String?>(null) }

    val supportsConsole = current.kind in setOf(ArtifactKind.HTML, ArtifactKind.REACT)
    val errorLogCount = consoleLogs.count { it.first == "error" }

    fun Color.toCssHex(): String = String.format("#%06X", 0xFFFFFF and this.toArgb())
    val cCanvas = G.canvas
    val cSurface = G.surface
    val cSurfaceSubtle = G.surfaceSubtle
    val cInk = G.ink
    val cInkMuted = G.inkMuted
    val cBorder = G.border
    val cAccent = G.accent
    val themePaletteJson = remember(cCanvas, cSurface, cSurfaceSubtle, cInk, cInkMuted, cBorder, cAccent) {
        JSONObject()
            .put("bg", cCanvas.toCssHex())
            .put("paper", cSurface.toCssHex())
            .put("surface", cSurfaceSubtle.toCssHex())
            .put("ink", cInk.toCssHex())
            .put("muted", cInkMuted.toCssHex())
            .put("border", cBorder.toCssHex())
            .put("accent", cAccent.toCssHex())
    }

    LaunchedEffect(current.kind) {
        if (current.kind == ArtifactKind.LATEX && app.runtime.snapshot.value.ready) {
            latexStatus = runCatching { app.conversations.hub.latexStatus() }.getOrNull()
        }
    }

    val latestPluginId by rememberUpdatedState(pluginId)
    val latestCurrent by rememberUpdatedState(current)
    val latestEditableContent by rememberUpdatedState(editableContent)
    val latestDark by rememberUpdatedState(dark)
    val latestViewport by rememberUpdatedState(viewport)
    val latestPalette by rememberUpdatedState(themePaletteJson)
    val latestExternalLoading by rememberUpdatedState(externalLoading)
    val canvasArgb = G.canvas.toArgb()

    fun pushToWebView(
        wv: WebView?,
        art: ChatArtifact = latestCurrent,
        content: String = latestEditableContent,
        isDark: Boolean = latestDark,
        vp: String = latestViewport,
        force: Boolean = false,
    ) {
        if (wv == null || !pageLoaded || latestExternalLoading) return
        val basePayload = JSONObject()
            .put("id", art.id)
            .put("kind", art.kind.wire)
            .put("title", art.title)
            .put("content", content)
            .put("dark", isDark)
            .put("viewport", vp)
            .put("palette", latestPalette)
            .apply { if (!latestPluginId.isNullOrBlank()) put("pluginId", latestPluginId) }
        val tagKey = "$renderNonce:${basePayload}"
        if (!force && wv.tag == tagKey) return
        wv.tag = tagKey
        val nextSeq = ++webViewHolder.seq
        webViewHolder.lastViewport = vp
        basePayload.put("renderSeq", nextSeq)
        wv.evaluateJavascript("window.SolbraidStudio && window.SolbraidStudio.render($basePayload);", null)
    }

    LaunchedEffect(pageLoaded, externalLoading, current.id, current.kind, current.title, editableContent, dark, renderNonce) {
        if (pageLoaded && !externalLoading) pushToWebView(webViewHolder.view, current, editableContent, dark, viewport, force = false)
    }

    // 兜底：若极少数设备未触发 JS onRendered，确保 1.4s 后可见而非永久隐藏
    LaunchedEffect(pageLoaded, externalLoading) {
        if (pageLoaded && !externalLoading && !contentRendered) {
            delay(1400)
            contentRendered = true
        }
    }

    // 视口切换直接走原地 CSS 切换，避免重载整个页面/iframe 引起闪烁
    LaunchedEffect(pageLoaded, viewport) {
        val wv = webViewHolder.view ?: return@LaunchedEffect
        if (!pageLoaded || webViewHolder.lastViewport == viewport) return@LaunchedEffect
        webViewHolder.lastViewport = viewport
        wv.evaluateJavascript("window.SolbraidStudio && window.SolbraidStudio.setViewport(${JSONObject.quote(viewport)});", null)
    }

    // 单实例 WebView 仅在工作台关闭时销毁：切换产物 Tab 或「预览 ↔ 源码」彻底零重建、零闪屏
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder.view?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
            webViewHolder.view = null
            pageLoaded = false
            contentRendered = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        SyncFullscreenDialogWindow(cCanvas)
        var moreActionsMenu by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = G.canvas,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                // 顶栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(GaliaisIcons.Close, contentDescription = "关闭工作台", tint = G.ink, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(current.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(current.kind.label)
                                if (current.version > 1) append(" · v${current.version}")
                                if (current.kind == ArtifactKind.LATEX) {
                                    append(if (latexStatus?.nativeAvailable == true) " · 原生 ${latexStatus?.nativeEngine} + 离线渲染" else " · 内置离线 LaTeX 引擎")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = G.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SegmentedPillBar(
                        options = listOf(0 to "预览", 1 to if (current.kind == ArtifactKind.LATEX || editableScratchpad) "编辑" else "源码"),
                        selected = tab,
                        onSelect = { tab = it },
                        fillWidth = false,
                        compact = true,
                    )
                }
                Hairline()

                // 多产物 / 版本切换条
                if (list.size > 1) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
                        GaliaisScrollableTabBar(
                            options = list.map { art ->
                                art.id to (if (art.version > 1) "${art.title} v${art.version}" else art.title).take(22)
                            },
                            selected = current.id,
                            onSelect = { id ->
                                selectedId = id
                                compileLog = null
                            },
                        )
                    }
                    Hairline()
                }

                // 主画布区：单实例 WebView 持久保活且尺寸恒定，源码层平滑覆盖不销毁不隐藏 WebView，彻底消除闪烁
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize()) {
                        val supportsViewport = current.kind in setOf(ArtifactKind.HTML, ArtifactKind.REACT)
                        if (supportsViewport || editableContent != current.content) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (supportsViewport) {
                                    Text("视口", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                    SegmentedPillBar(
                                        options = listOf("auto" to "自适应", "mobile" to "手机", "desktop" to "桌面"),
                                        selected = viewport,
                                        onSelect = { viewport = it },
                                        fillWidth = false,
                                        compact = true,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (editableContent != current.content) {
                                    TagPill("已编辑", fg = G.warning, bg = G.warningTint)
                                }
                            }
                            Hairline()
                        }
                        val webAlpha by animateFloatAsState(
                            targetValue = if (contentRendered && !externalLoading && externalError == null) 1f else 0f,
                            animationSpec = tween(90),
                            label = "studioWebAlpha",
                        )
                        Box(Modifier.weight(1f).fillMaxWidth().background(cCanvas)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize().alpha(webAlpha),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setBackgroundColor(canvasArgb)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        addJavascriptInterface(
                                            object {
                                                @JavascriptInterface
                                                fun onRendered(seq: Int) {
                                                    post {
                                                        if (seq <= 0 || seq >= webViewHolder.seq) {
                                                            contentRendered = true
                                                        }
                                                    }
                                                }

                                                @JavascriptInterface
                                                fun onConsole(level: String, message: String) {
                                                    post {
                                                        consoleLogs = (consoleLogs + (level to message)).takeLast(80)
                                                        if (level == "error") showConsole = true
                                                    }
                                                }

                                                @JavascriptInterface
                                                fun onPluginRpc(reqId: String, method: String, paramsJson: String) {
                                                    val pid = latestPluginId?.takeIf { it.isNotBlank() }
                                                    val cleanMethod = method.trim()
                                                    val reply: (Boolean, String) -> Unit = { ok, payloadLiteral ->
                                                        post {
                                                            evaluateJavascript(
                                                                "window.SolbraidStudio && window.SolbraidStudio.resolvePluginRpc(${JSONObject.quote(reqId)}, $ok, $payloadLiteral);",
                                                                null,
                                                            )
                                                        }
                                                    }
                                                    if (pid == null || !Regex("^[a-zA-Z0-9._-]{1,64}$").matches(cleanMethod)) {
                                                        reply(false, JSONObject.quote("越权或非法的插件 RPC 调用"))
                                                        return
                                                    }
                                                    scope.launch {
                                                        try {
                                                            val params = runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())
                                                            val res = app.runtime.hubRequest("plugin.$pid.$cleanMethod", params, timeoutMs = 15_000)
                                                            reply(true, res.toString())
                                                        } catch (e: Exception) {
                                                            reply(false, JSONObject.quote(e.message ?: "插件 RPC 调用失败"))
                                                        }
                                                    }
                                                }
                                            },
                                            "SolbraidStudioBridge",
                                        )
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                                val uri = request?.url ?: return null
                                                if (uri.host != ARTIFACT_HOST) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                                                val name = uri.lastPathSegment?.takeIf { it in setOf("artifact-viewer.html", "artifact-latex.js", "artifact-mermaid.js", "artifact-renderer.js") }
                                                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                                                val mime = if (name.endsWith(".html")) "text/html" else "application/javascript"
                                                if (!app.extensions.hostPlugins.value.safeMode) {
                                                    val overrideStream = runCatching {
                                                        val rootDir = LinuxRuntime(ctx, app.settings.state.value.runtimeMode ?: com.galiais.agent.engine.RUNTIME_MODE_ROOT).root
                                                        val overrideDir = java.io.File(rootDir, "home/agent/.galiais/studio-overrides").canonicalFile
                                                        val candidate = java.io.File(overrideDir, name).canonicalFile
                                                        if (candidate.path.startsWith(overrideDir.path + java.io.File.separator) &&
                                                            candidate.isFile &&
                                                            candidate.length() in 1L..(2L * 1024 * 1024)
                                                        ) {
                                                            candidate.inputStream()
                                                        } else null
                                                    }.getOrNull()
                                                    if (overrideStream != null) {
                                                        return WebResourceResponse(mime, "utf-8", overrideStream)
                                                    }
                                                }
                                                return runCatching {
                                                    WebResourceResponse(mime, "utf-8", ctx.assets.open("galiais/runtime/$name"))
                                                }.getOrElse {
                                                    WebResourceResponse("text/plain", "utf-8", 404, "Missing Asset", emptyMap(), "".byteInputStream())
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                pageLoaded = true
                                                pushToWebView(view, force = true)
                                            }
                                        }
                                        webViewHolder.view = this
                                        val initBg = URLEncoder.encode(cCanvas.toCssHex(), "UTF-8")
                                        loadUrl("https://$ARTIFACT_HOST/artifact-viewer.html?dark=${if (dark) 1 else 0}&bg=$initBg")
                                    }
                                },
                                update = { wv ->
                                    webViewHolder.view = wv
                                    wv.setBackgroundColor(canvasArgb)
                                    if (wv.visibility != View.VISIBLE) wv.visibility = View.VISIBLE
                                    wv.isEnabled = (tab == 0)
                                },
                            )
                            if (externalError != null) {
                                Box(Modifier.fillMaxSize().background(cCanvas).padding(20.dp), contentAlignment = Alignment.Center) {
                                    CrispCard { Text(externalError, color = G.danger, style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                    if (tab == 1) {
                        Surface(
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
                            color = G.canvas,
                        ) {
                            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editableContent,
                                    onValueChange = { setEditableContent(it) },
                                    label = { Text("${current.kind.label} 源码（可实时修改并切回预览）") },
                                    shape = D.fieldShape,
                                    colors = galiaisTextFieldColors(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, lineHeight = 18.sp),
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                        }
                    }
                }

                if (showConsole) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = D.shape(10.dp),
                        color = G.codeBg,
                        border = BorderStroke(0.75.dp, if (errorLogCount > 0) G.danger.copy(alpha = 0.55f) else G.codeBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("运行控制台（${consoleLogs.size}）", style = MaterialTheme.typography.labelSmall, color = G.codeMuted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TagPill("清空", fg = G.codeMuted, bg = G.codeHeader, onClick = { consoleLogs = emptyList() })
                                TagPill("收起", fg = G.codeMuted, bg = G.codeHeader, onClick = { showConsole = false })
                            }
                            if (consoleLogs.isEmpty()) {
                                Text("暂无 console.log 或运行报错。", fontFamily = Mono, fontSize = 11.sp, color = G.codeMuted)
                            } else {
                                Column(Modifier.fillMaxWidth().heightIn(max = 128.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    consoleLogs.forEach { (lv, msg) ->
                                        val tint = when (lv) {
                                            "error" -> G.danger
                                            "warn" -> G.warning
                                            else -> G.codeInk
                                        }
                                        Text("[${lv.uppercase()}] $msg", fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, color = tint)
                                    }
                                }
                            }
                        }
                    }
                }

                compileLog?.let { log ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = D.shape(8.dp), color = G.codeBg, border = BorderStroke(0.75.dp, G.codeBorder)) {
                        Text(log.takeLast(600), fontFamily = Mono, fontSize = 11.sp, color = G.codeInk, modifier = Modifier.padding(8.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }

                Hairline()
                // 底部精简动作条：保留 3 个高频主操作，其余归入右侧「⋯」溢出菜单，避免 10 个药丸横向盲滑
                val wsFiles = remember { WorkspaceFiles(app.runtime, app.envSsh) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app.runtime.snapshot.value.ready) {
                        if (savePath != null) {
                            CompactActionButton(
                                text = "保存",
                                icon = GaliaisIcons.Check,
                                primary = true,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            wsFiles.write(savePath, editableContent)
                                            app.conversations.notify("已写回 $savePath")
                                        }.onFailure { app.conversations.notify("保存失败：${it.message}") }
                                    }
                                },
                            )
                        } else {
                            CompactActionButton(
                                text = "保存工作区",
                                icon = GaliaisIcons.Download,
                                primary = true,
                                onClick = {
                                    scope.launch {
                                        val targetDir = (cwd ?: app.settings.state.value.defaultCwd).trimEnd('/')
                                        val safeName = current.identifier.replace(Regex("[^a-zA-Z0-9._-]+"), "_").ifBlank { "artifact" }
                                        val outDir = "$targetDir/artifacts"
                                        val path = "$outDir/$safeName.${current.kind.extension}"
                                        runCatching {
                                            wsFiles.createDirectory(outDir)
                                            wsFiles.write(path, editableContent)
                                            app.conversations.notify("已保存到 $path")
                                        }.onFailure { app.conversations.notify("保存失败：${it.message}") }
                                    }
                                },
                            )
                        }
                    }
                    if (tab == 0) {
                        CompactActionButton(
                            text = "导出 PDF",
                            icon = GaliaisIcons.FileLines,
                            onClick = {
                                val wv = webViewHolder.view
                                if (wv == null) {
                                    app.conversations.notify("预览视图尚未加载完成")
                                } else {
                                    runCatching {
                                        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                                        val safeJob = current.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]+"), "_").ifBlank { "Solbraid_Artifact" }
                                        val adapter = wv.createPrintDocumentAdapter(safeJob)
                                        pm.print(safeJob, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                                    }.onFailure { app.conversations.notify("导出 PDF 失败：${it.message}") }
                                }
                            },
                        )
                    } else {
                        CompactActionButton(
                            text = "复制源码",
                            icon = GaliaisIcons.Copy,
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipData.newPlainText(current.title, editableContent).toClipEntry())
                                    app.conversations.notify("已复制 ${current.title} 源码")
                                }
                            },
                        )
                    }
                    if (supportsConsole || consoleLogs.isNotEmpty()) {
                        TagPill(
                            text = if (consoleLogs.isEmpty()) "控制台" else "控制台 (${consoleLogs.size})",
                            fg = if (errorLogCount > 0) G.danger else if (showConsole) G.accent else G.ink,
                            bg = if (errorLogCount > 0) G.dangerTint else if (showConsole) G.accentContainer else G.surfaceSubtle,
                            border = if (errorLogCount > 0 || showConsole) null else G.border,
                            onClick = { showConsole = !showConsole },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { moreActionsMenu = true }) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多操作", tint = G.inkMuted, modifier = Modifier.size(20.dp))
                        }
                        GaliaisDropdownMenu(expanded = moreActionsMenu, onDismissRequest = { moreActionsMenu = false }) {
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                GaliaisMenuRow(
                                    title = "复制源码",
                                    icon = GaliaisIcons.Copy,
                                    onClick = {
                                        moreActionsMenu = false
                                        scope.launch {
                                            clipboard.setClipEntry(ClipData.newPlainText(current.title, editableContent).toClipEntry())
                                            app.conversations.notify("已复制 ${current.title} 源码")
                                        }
                                    },
                                )
                                if (tab == 0) {
                                    GaliaisMenuRow(
                                        title = "重新渲染预览",
                                        icon = GaliaisIcons.Refresh,
                                        onClick = {
                                            moreActionsMenu = false
                                            consoleLogs = emptyList()
                                            renderNonce++
                                        },
                                    )
                                }
                                if (editableContent != current.content) {
                                    GaliaisMenuRow(
                                        title = "重置源码更改",
                                        icon = GaliaisIcons.Undo,
                                        tintOverride = G.warning,
                                        onClick = {
                                            moreActionsMenu = false
                                            setEditableContent(current.content)
                                            consoleLogs = emptyList()
                                        },
                                    )
                                }
                                GaliaisMenuRow(
                                    title = "分享源码",
                                    icon = GaliaisIcons.Share,
                                    onClick = {
                                        moreActionsMenu = false
                                        runCatching {
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "${current.title}.${current.kind.extension}")
                                                putExtra(Intent.EXTRA_TEXT, editableContent)
                                            }
                                            context.startActivity(Intent.createChooser(send, "分享 ${current.title}"))
                                        }.onFailure { app.conversations.notify("分享失败：${it.message}") }
                                    },
                                )
                                if (current.kind == ArtifactKind.LATEX && app.runtime.snapshot.value.ready) {
                                    GaliaisMenuRow(
                                        title = if (compilingNative) "TeX 编译中…" else "Linux 原生 TeX 编译",
                                        icon = GaliaisIcons.Terminal,
                                        tintOverride = G.artifact,
                                        onClick = {
                                            if (compilingNative) return@GaliaisMenuRow
                                            moreActionsMenu = false
                                            compilingNative = true
                                            compileLog = null
                                            scope.launch {
                                                try {
                                                    val safeName = current.identifier.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                                                    val res = app.conversations.hub.compileLatex(editableContent, cwd ?: app.settings.state.value.defaultCwd, safeName)
                                                    compileLog = res.log.ifBlank { null }
                                                    if (res.ok && res.pdfPath != null) {
                                                        app.conversations.notify("已用 ${res.engine} 编译生成 ${res.pdfPath}")
                                                    } else {
                                                        app.conversations.notify(if (!res.nativeAvailable) "未安装原生 TeX，请直接点「导出 PDF」离线出稿" else "TeX 编译失败，请查看日志")
                                                    }
                                                } catch (e: Exception) {
                                                    compileLog = e.message
                                                    app.conversations.notify("编译失败：${e.message}")
                                                } finally {
                                                    compilingNative = false
                                                }
                                            }
                                        },
                                    )
                                }
                                if (savePath != null && app.runtime.snapshot.value.ready) {
                                    GaliaisMenuRow(
                                        title = "另存到 artifacts/ 目录",
                                        icon = GaliaisIcons.Folder,
                                        onClick = {
                                            moreActionsMenu = false
                                            scope.launch {
                                                val targetDir = (cwd ?: app.settings.state.value.defaultCwd).trimEnd('/')
                                                val safeName = current.identifier.replace(Regex("[^a-zA-Z0-9._-]+"), "_").ifBlank { "artifact" }
                                                val outDir = "$targetDir/artifacts"
                                                val path = "$outDir/$safeName.${current.kind.extension}"
                                                runCatching {
                                                    wsFiles.createDirectory(outDir)
                                                    wsFiles.write(path, editableContent)
                                                    app.conversations.notify("已保存到 $path")
                                                }.onFailure { app.conversations.notify("保存失败：${it.message}") }
                                            }
                                        },
                                    )
                                }
                                if (studioSlots.isNotEmpty() && app.runtime.snapshot.value.ready) {
                                    Hairline(Modifier.padding(vertical = 2.dp))
                                    studioSlots.take(4).forEach { slot ->
                                        GaliaisMenuRow(
                                            title = slot.badge.ifBlank { slot.label.ifBlank { slot.id } },
                                            icon = GaliaisIcons.Sparkles,
                                            onClick = {
                                                moreActionsMenu = false
                                                scope.launch {
                                                    try {
                                                        when {
                                                            !slot.surfaceId.isNullOrBlank() -> openingSlotSurface = slot.pluginId to slot.surfaceId
                                                            !slot.command.isNullOrBlank() -> {
                                                                val res = app.extensions.runHostPluginCommand(slot.pluginId, slot.command, args = editableContent.take(4096), cwd = cwd)
                                                                app.conversations.notify(res.message.ifBlank { "${slot.label.ifBlank { slot.id }} 已执行" })
                                                            }
                                                            else -> app.conversations.notify("工具栏槽位未绑定命令或原生页面")
                                                        }
                                                    } catch (e: Exception) {
                                                        app.conversations.notify("插件工具栏动作失败：${e.message}")
                                                    }
                                                }
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
    }
    openingSlotSurface?.let { (pid, sid) ->
        PluginSduiSheet(
            app = app,
            pluginId = pid,
            surfaceId = sid,
            cwd = cwd,
            onOpenSurface = { nextPid, nextSid -> openingSlotSurface = nextPid to nextSid },
            onDismiss = { openingSlotSurface = null },
        )
    }
}

/** 可视化与产物模板选择器（涵盖 Codex Official-Plan 视觉规范、Claude Artifacts 与离线 LaTeX 编译器入口）。 */
@Composable
fun VisualTemplatePickerSheet(
    app: GaliaisApplication,
    onPickTemplate: (VisualArtifactTemplate) -> Unit,
    onOpenLatexScratchpad: () -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = remember { listOf("全部") + visualArtifactTemplates.map { it.category }.distinct() }
    var selectedCategory by remember { mutableStateOf("全部") }
    var latexStatus by remember { mutableStateOf<LatexEngineStatus?>(null) }

    LaunchedEffect(Unit) {
        if (app.runtime.snapshot.value.ready) {
            latexStatus = runCatching { app.conversations.hub.latexStatus() }.getOrNull()
        }
    }

    val filtered = remember(selectedCategory) {
        if (selectedCategory == "全部") visualArtifactTemplates
        else visualArtifactTemplates.filter { it.category == selectedCategory }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(GaliaisIcons.Sparkles, contentDescription = null, tint = G.accent, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("可视化与产物模板", style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
                    Text("选择提示词模板，或打开离线 LaTeX 编译器。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            }

            // 离线 LaTeX 独立工作台快捷入口
            CrispCard(
                onClick = { onDismiss(); onOpenLatexScratchpad() },
                padding = PaddingValues(12.dp),
                background = G.artifactTint,
                borderColor = G.artifact.copy(alpha = 0.35f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(GaliaisIcons.FileLines, contentDescription = null, tint = G.artifact, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("打开离线 LaTeX 编译器", style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (latexStatus?.nativeAvailable == true) "已检测到原生 ${latexStatus?.nativeEngine} + 内置离线引擎 · 支持实时编辑与导出 PDF"
                            else "内置零联网 LaTeX 数学/论文渲染引擎 · 支持实时编辑与导出 PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                        )
                    }
                    TagPill("立即使用", fg = G.artifact, bg = G.surface)
                }
            }

            GaliaisScrollableTabBar(
                options = categories.map { it to it },
                selected = selectedCategory,
                onSelect = { selectedCategory = it },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.forEach { tpl ->
                    CrispCard(
                        onClick = { onDismiss(); onPickTemplate(tpl) },
                        padding = PaddingValues(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        tpl.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = G.ink,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    TagPill(tpl.kind.label, fg = G.accent, bg = G.accentContainer)
                                }
                                Text(tpl.subtitle, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                            }
                            Icon(GaliaisIcons.ChevronRight, contentDescription = null, tint = G.inkSubtle, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 当前会话全部产物列表抽屉。 */
@Composable
fun ConversationArtifactsSheet(
    artifacts: List<ChatArtifact>,
    onOpenArtifact: (ChatArtifact) -> Unit,
    onOpenLatexScratchpad: () -> Unit,
    onDismiss: () -> Unit,
) {
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会话产物（${artifacts.size}）", style = MaterialTheme.typography.titleMedium, color = G.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TagPill("LaTeX 编译器", fg = G.artifact, bg = G.artifactTint, onClick = { onDismiss(); onOpenLatexScratchpad() })
            }
            if (artifacts.isEmpty()) {
                CrispCard {
                    Text(
                        "当前会话尚未生成可渲染产物。让 Codex、Claude Code 或 Antigravity 输出 HTML / SVG / Mermaid / LaTeX / CSV，或通过输入框「+ → 可视化与产物模板」快速创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }
            } else {
                artifacts.reversed().forEach { art ->
                    InlineArtifactCard(artifact = art, onOpen = { onDismiss(); onOpenArtifact(it) })
                }
            }
        }
    }
}
