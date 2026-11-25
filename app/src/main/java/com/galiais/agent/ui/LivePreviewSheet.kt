package com.galiais.agent.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.galiais.agent.GaliaisApplication
import kotlinx.coroutines.launch

/**
 * 手机端本地实时 Web 预览抽屉：
 * 自动扫描虚拟机内监听的 TCP 端口（如 Vite 5173 / Next 3000 / Python 8000），
 * 一键建立 `127.0.0.1` 回环多路复用隧道并在应用内安全沙箱 WebView 中实时渲染。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePreviewSheet(
    app: GaliaisApplication,
    initialPort: Int? = null,
    onDismiss: () -> Unit,
) {
    val hub = app.conversations.hub
    val listeningPorts by hub.listeningPorts.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var portInput by remember { mutableStateOf((initialPort ?: listeningPorts.firstOrNull() ?: 3000).toString()) }
    var pathInput by remember { mutableStateOf("/") }
    var activeLocalUrl by remember { mutableStateOf<String?>(null) }
    var activeVmPort by remember { mutableStateOf<Int?>(null) }
    var desktopMode by remember { mutableStateOf(false) }
    var expandedView by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var consoleLogs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showConsole by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var keepTunnelForExternalBrowser by remember { mutableStateOf(false) }

    fun connectPort(targetPort: Int) {
        if (targetPort !in 1024..65535) {
            error = "端口号须为 1024–65535"
            return
        }
        loading = true; error = null
        scope.launch {
            try {
                val localPort = hub.portForwarder.ensureTunnel(targetPort)
                val cleanPath = if (pathInput.startsWith("/")) pathInput else "/$pathInput"
                val url = "http://127.0.0.1:$localPort$cleanPath"
                activeVmPort?.takeIf { it != targetPort }?.let { hub.portForwarder.closeTunnel(it) }
                activeVmPort = targetPort
                keepTunnelForExternalBrowser = false
                if (activeLocalUrl == url) {
                    pageError = null
                    webViewRef?.reload()
                } else {
                    activeLocalUrl = url
                }
            } catch (e: Exception) {
                error = "连接预览服务失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val ports = hub.refreshListeningPorts()
        val auto = initialPort ?: ports.firstOrNull()
        if (auto != null) {
            portInput = auto.toString()
            connectPort(auto)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
            if (!keepTunnelForExternalBrowser) {
                activeVmPort?.let { hub.portForwarder.closeTunnel(it) }
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
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("网页预览", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    Text(
                        activeVmPort?.let { "本地 :$it → ${activeLocalUrl ?: ""}" } ?: "选择或输入本地 Web 服务的端口。",
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = G.inkMuted,
                    )
                }
                SecondaryPillButton("扫描端口", onClick = { scope.launch { hub.refreshListeningPorts() } })
            }

            if (listeningPorts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("监听中：", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                    listeningPorts.forEach { p ->
                        val selected = activeVmPort == p
                        TagPill(
                            text = ":$p",
                            fg = if (selected) G.onAccent else G.accent,
                            bg = if (selected) G.accent else G.accentContainer,
                            onClick = {
                                portInput = p.toString()
                                connectPort(p)
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit).take(5); error = null },
                    label = { Text("服务端口") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.weight(0.9f),
                )
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("路径") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.weight(1.3f),
                )
                PrimaryPillButton(
                    "连接",
                    enabled = !loading && portInput.isNotBlank(),
                    onClick = { portInput.toIntOrNull()?.let { connectPort(it) } ?: run { error = "端口无效" } },
                )
            }

            if (activeLocalUrl != null) {
                val errLogs = consoleLogs.count { it.first == "error" }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canGoBack) {
                        TagPill("← 后退", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = { webViewRef?.goBack() })
                    }
                    if (canGoForward) {
                        TagPill("前进 →", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = { webViewRef?.goForward() })
                    }
                    TagPill("刷新页面", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = {
                        pageError = null
                        webViewRef?.reload()
                    })
                    TagPill(
                        text = if (desktopMode) "桌面模式" else "移动模式",
                        fg = if (desktopMode) G.accent else G.ink,
                        bg = if (desktopMode) G.accentContainer else G.surface,
                        border = G.borderStrong,
                        onClick = {
                            desktopMode = !desktopMode
                            webViewRef?.settings?.apply {
                                useWideViewPort = desktopMode
                                loadWithOverviewMode = desktopMode
                                userAgentString = if (desktopMode) {
                                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                } else null
                            }
                            webViewRef?.reload()
                        },
                    )
                    TagPill(
                        text = if (consoleLogs.isEmpty()) "控制台" else "控制台 (${consoleLogs.size})",
                        fg = if (errLogs > 0) G.danger else if (showConsole) G.accent else G.ink,
                        bg = if (errLogs > 0) G.dangerTint else if (showConsole) G.accentContainer else G.surface,
                        border = if (errLogs > 0 || showConsole) null else G.borderStrong,
                        onClick = { showConsole = !showConsole },
                    )
                    TagPill(
                        text = if (expandedView) "默认高度" else "放大画布",
                        fg = G.ink,
                        bg = G.surface,
                        border = G.borderStrong,
                        onClick = { expandedView = !expandedView },
                    )
                    TagPill("浏览器打开", fg = G.ink, bg = G.surface, border = G.borderStrong, onClick = {
                        activeLocalUrl?.let { u ->
                            keepTunnelForExternalBrowser = true
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                app.conversations.notify("已在外部浏览器打开，本地端口隧道保持运行")
                            }.onFailure {
                                keepTunnelForExternalBrowser = false
                                error = "无法调起系统浏览器：${it.message}"
                            }
                        }
                    })
                }
            }

            HeaderProgressSlot(loading)
            (error ?: pageError)?.let { msg ->
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = G.danger, modifier = Modifier.weight(1f))
                        if (activeLocalUrl != null) {
                            TagPill("重试", fg = G.danger, bg = G.surface, onClick = {
                                pageError = null; error = null
                                webViewRef?.reload()
                            })
                        }
                    }
                }
            }

            if (showConsole) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = D.shape(10.dp),
                    color = G.codeBg,
                    border = BorderStroke(0.75.dp, G.codeBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("页面控制台（${consoleLogs.size}）", style = MaterialTheme.typography.labelSmall, color = G.codeMuted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TagPill("清空", fg = G.codeMuted, bg = G.codeHeader, onClick = { consoleLogs = emptyList() })
                            TagPill("收起", fg = G.codeMuted, bg = G.codeHeader, onClick = { showConsole = false })
                        }
                        if (consoleLogs.isEmpty()) {
                            Text("暂无控制台日志。", fontFamily = Mono, fontSize = 11.sp, color = G.codeMuted)
                        } else {
                            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                consoleLogs.forEach { (lv, line) ->
                                    val clr = when (lv) { "error" -> G.danger; "warn" -> G.warning; else -> G.codeInk }
                                    Text("[${lv.uppercase()}] $line", fontFamily = Mono, fontSize = 11.sp, color = clr)
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().height(if (expandedView) 560.dp else 400.dp).clip(D.shape(12.dp)),
                shape = D.shape(12.dp),
                color = G.surfaceSubtle,
                border = BorderStroke(1.dp, G.border),
            ) {
                val targetUrl = activeLocalUrl
                val webBgArgb = G.canvas.toArgb()
                if (targetUrl == null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "先在终端启动 Web 服务（如 npm run dev），再选择对应端口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = G.inkMuted,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(webBgArgb)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                        if (cm != null) {
                                            val lv = when (cm.messageLevel()) {
                                                ConsoleMessage.MessageLevel.ERROR -> "error"
                                                ConsoleMessage.MessageLevel.WARNING -> "warn"
                                                else -> "log"
                                            }
                                            val loc = cm.lineNumber().takeIf { it > 0 }?.let { " (L$it)" }.orEmpty()
                                            post { consoleLogs = (consoleLogs + (lv to "${cm.message()}$loc")).takeLast(100) }
                                        }
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val host = request.url?.host?.lowercase()
                                        val scheme = request.url?.scheme?.lowercase()
                                        return !(scheme == "http" && (host == "127.0.0.1" || host == "localhost"))
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        canGoBack = view?.canGoBack() == true
                                        canGoForward = view?.canGoForward() == true
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                                        if (request?.isForMainFrame == true) {
                                            loading = false
                                            pageError = "页面加载失败：${err?.description ?: "无法连接本地开发服务器"}"
                                        }
                                    }
                                }
                                webViewRef = this
                                tag = targetUrl
                                pageError = null
                                loadUrl(targetUrl)
                            }
                        },
                        update = { view ->
                            view.setBackgroundColor(webBgArgb)
                            if (view.tag != targetUrl) {
                                view.tag = targetUrl
                                pageError = null
                                view.loadUrl(targetUrl)
                            }
                        },
                    )
                }
            }
        }
    }
}
