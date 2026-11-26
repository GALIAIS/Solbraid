package com.galiais.agent.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.galiais.agent.engine.ArtifactKind
import com.galiais.agent.engine.ChatArtifact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val INLINE_ARTIFACT_HOST = "artifact.solbraid.local"

/** 离线轻量行内 LaTeX 数学排版器：将 `$...$` / `\(...\)` 转换为高可读性 Unicode 数学排版，零 WebView 开销。 */
internal object InlineMathFormatter {
    private val symbols = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
        "emptyset" to "∅", "varnothing" to "∅", "in" to "∈", "notin" to "∉", "subset" to "⊂",
        "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "·", "circ" to "∘",
        "oplus" to "⊕", "otimes" to "⊗", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "ll" to "≪", "gg" to "≫", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "leftrightarrow" to "↔", "mapsto" to "↦", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "iff" to "⟺", "implies" to "⟹", "land" to "∧", "lor" to "∨",
        "neg" to "¬", "perp" to "⊥", "parallel" to "∥", "angle" to "∠", "hbar" to "ℏ", "ell" to "ℓ",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "sum" to "∑", "prod" to "∏", "int" to "∫",
        "iint" to "∬", "oint" to "∮", "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", "!" to "",
    )
    private val bb = mapOf(
        'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'E' to "𝔼", 'P' to "ℙ", 'F' to "𝔽", 'H' to "ℍ",
    )
    private val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'T' to 'ᵀ', 'k' to 'ᵏ', 'x' to 'ˣ',
    )
    private val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )
    // 匹配行内公式：$...$（避开货币金额如 $100）或 \(...\)
    private val inlineMathRe = Regex("""\\\(([^)\n][^\\\n]*?(?:\\[^)][^\\\n]*?)*?)\\\)|(?<![\\$])\$(?!\s)(?!\d+(?:[.,]\d+)?(?:\s|$|[.,;?!]))([^$\n]+?)(?<!\s)\$(?!\d)""")

    private fun readGroup(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return "" to i
        if (s[i] == '{') {
            var depth = 1
            var j = i + 1
            while (j < s.length && depth > 0) {
                if (s[j] == '\\' && j + 1 < s.length) { j += 2; continue }
                if (s[j] == '{') depth++ else if (s[j] == '}') depth--
                j++
            }
            return s.substring(i + 1, (j - 1).coerceAtLeast(i + 1)) to j
        }
        if (s[i] == '\\') {
            var j = i + 1
            if (j < s.length && s[j].isLetter()) while (j < s.length && s[j].isLetter()) j++
            else if (j < s.length) j++
            return s.substring(i, j) to j
        }
        return s[i].toString() to (i + 1)
    }

    fun toPrettyUnicode(tex: String): String {
        val sb = StringBuilder()
        var i = 0
        val s = tex.trim()
        while (i < s.length) {
            when (val ch = s[i]) {
                '{', '}' -> i++
                '^', '_' -> {
                    val (grp, next) = readGroup(s, i + 1)
                    val rendered = toPrettyUnicode(grp)
                    val table = if (ch == '^') supMap else subMap
                    if (rendered.isNotEmpty() && rendered.all { it in table }) {
                        rendered.forEach { sb.append(table[it]) }
                    } else if (rendered.isNotEmpty()) {
                        sb.append(ch).append(if (rendered.length > 1) "($rendered)" else rendered)
                    }
                    i = next
                }
                '\\' -> {
                    var j = i + 1
                    val cmd = if (j < s.length && s[j].isLetter()) {
                        while (j < s.length && s[j].isLetter()) j++
                        s.substring(i + 1, j)
                    } else if (j < s.length) {
                        j++
                        s.substring(i + 1, j)
                    } else ""
                    i = j
                    when (cmd) {
                        "frac", "dfrac", "tfrac", "cfrac" -> {
                            val (a, n1) = readGroup(s, i)
                            val (b, n2) = readGroup(s, n1)
                            val pa = toPrettyUnicode(a)
                            val pb = toPrettyUnicode(b)
                            sb.append(if (pa.length > 2) "($pa)" else pa).append('/').append(if (pb.length > 2) "($pb)" else pb)
                            i = n2
                        }
                        "sqrt" -> {
                            val (body, n1) = readGroup(s, i)
                            val pb = toPrettyUnicode(body)
                            sb.append("√").append(if (pb.length > 1) "($pb)" else pb)
                            i = n1
                        }
                        "mathbb" -> {
                            val (body, n1) = readGroup(s, i)
                            body.forEach { sb.append(bb[it] ?: it) }
                            i = n1
                        }
                        "text", "textrm", "mathrm", "mathbf", "boldsymbol", "mathcal", "operatorname", "boxed" -> {
                            val (body, n1) = readGroup(s, i)
                            sb.append(if (cmd == "text" || cmd == "textrm") body else toPrettyUnicode(body))
                            i = n1
                        }
                        "hat" -> { val (b, n1) = readGroup(s, i); sb.append(toPrettyUnicode(b)).append('\u0302'); i = n1 }
                        "bar", "overline" -> { val (b, n1) = readGroup(s, i); sb.append(toPrettyUnicode(b)).append('\u0304'); i = n1 }
                        "vec" -> { val (b, n1) = readGroup(s, i); sb.append(toPrettyUnicode(b)).append('\u20D7'); i = n1 }
                        "tilde" -> { val (b, n1) = readGroup(s, i); sb.append(toPrettyUnicode(b)).append('\u0303'); i = n1 }
                        "left", "right", "displaystyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg" -> Unit
                        else -> sb.append(symbols[cmd] ?: cmd)
                    }
                }
                else -> { sb.append(if (ch == '-') '−' else ch); i++ }
            }
        }
        return sb.toString()
    }

    fun appendWithMath(builder: AnnotatedString.Builder, rawText: String, mathFg: Color, mathBg: Color) {
        var cursor = 0
        for (m in inlineMathRe.findAll(rawText)) {
            if (m.range.first > cursor) builder.append(rawText.substring(cursor, m.range.first))
            val tex = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val pretty = toPrettyUnicode(tex)
            builder.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    color = mathFg,
                    background = mathBg,
                ),
            ) {
                append("\u00A0$pretty\u00A0")
            }
            cursor = m.range.last + 1
        }
        if (cursor < rawText.length) builder.append(rawText.substring(cursor))
    }
}

/** 聊天内嵌实时离线可视化组件卡片（数学公式 / Mermaid / SVG / 交互图表 / HTML / React 组件）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InlineVisualArtifactCard(
    artifact: ChatArtifact,
    onOpenStudio: ((ChatArtifact) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = G.dark
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showSource by remember(artifact.id) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1300); copied = false } }

    val minH = when (artifact.kind) {
        ArtifactKind.MATH -> 54
        ArtifactKind.LATEX -> 64
        ArtifactKind.CHART, ArtifactKind.MERMAID, ArtifactKind.SVG -> 150
        else -> 180
    }
    val maxH = when (artifact.kind) {
        ArtifactKind.MATH -> 220
        ArtifactKind.LATEX -> 280
        else -> 320
    }
    var contentHeightDp by remember(artifact.id) { mutableIntStateOf(minH) }
    var pageLoaded by remember(artifact.id) { mutableStateOf(false) }

    val (accent, badgeTint) = when (artifact.kind) {
        ArtifactKind.MATH, ArtifactKind.LATEX -> G.claude to G.claudeTint
        ArtifactKind.MERMAID, ArtifactKind.SVG -> G.codex to G.codexTint
        ArtifactKind.CHART -> G.success to G.successTint
        else -> G.accent to G.accentContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = G.surface,
        border = BorderStroke(0.75.dp, if (showSource) G.border else accent.copy(alpha = 0.28f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (G.dark) G.surfaceElevated else G.surfaceSubtle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    when (artifact.kind) {
                        ArtifactKind.MATH, ArtifactKind.LATEX -> GaliaisIcons.FileLines
                        ArtifactKind.CHART, ArtifactKind.CSV -> GaliaisIcons.Sparkles
                        ArtifactKind.MERMAID, ArtifactKind.SVG -> GaliaisIcons.Layers
                        else -> GaliaisIcons.Code
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    artifact.title.ifBlank { artifact.kind.label },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!artifact.streaming) {
                    TagPill(
                        text = if (showSource) "预览" else "源码",
                        fg = G.inkMuted,
                        bg = G.surface,
                        border = G.border,
                        onClick = { showSource = !showSource },
                    )
                }
                TagPill(
                    text = if (copied) "已复制" else "复制",
                    fg = if (copied) G.success else G.inkMuted,
                    bg = G.surface,
                    border = G.border,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(artifact.title, artifact.content).toClipEntry())
                            copied = true
                        }
                    },
                )
                if (onOpenStudio != null) {
                    TagPill(
                        text = "全屏",
                        fg = accent,
                        bg = badgeTint,
                        onClick = { onOpenStudio(artifact) },
                    )
                }
            }
            Hairline()

            if (artifact.streaming) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TypingWaveDots(accent, dotSize = 4.5.dp)
                    Text("正在生成${artifact.kind.label}，完成后自动离线渲染…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                }
            } else if (showSource) {
                CodeBlock(artifact.content, artifact.language.ifBlank { artifact.kind.extension }, modifier = Modifier.padding(8.dp))
            } else {
                val payloadJson = remember(artifact.kind, artifact.content, dark) {
                    JSONObject()
                        .put("kind", artifact.kind.wire)
                        .put("content", artifact.content)
                        .put("dark", dark)
                        .put("inline", true)
                        .toString()
                }
                // 防快速滑屏风暴：停留 180ms 后再实例化内联 WebView，避免高速滚动大会话时反复创建/销毁 Chromium 造成掉帧
                var mountWebView by remember(artifact.id, showSource) { mutableStateOf(false) }
                LaunchedEffect(artifact.id, showSource) {
                    delay(180L)
                    mountWebView = true
                }
                val webViewHolder = remember(artifact.id, showSource) { object { var view: WebView? = null } }
                val interactiveKind = artifact.kind in setOf(ArtifactKind.HTML, ArtifactKind.REACT, ArtifactKind.CHART)
                DisposableEffect(artifact.id, showSource) {
                    onDispose {
                        webViewHolder.view?.let { wv ->
                            wv.stopLoading()
                            wv.destroy()
                        }
                        webViewHolder.view = null
                        pageLoaded = false
                    }
                }
                Box(Modifier.fillMaxWidth().height(contentHeightDp.coerceIn(minH, maxH).dp), contentAlignment = Alignment.Center) {
                    if (!mountWebView) {
                        Text("正在准备${artifact.kind.label}预览…", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                    } else {
                        androidx.compose.runtime.key(artifact.id, showSource) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().height(contentHeightDp.coerceIn(minH, maxH).dp),
                                factory = { ctx ->
                                    pageLoaded = false
                                    object : WebView(ctx) {
                                        override fun onTouchEvent(event: MotionEvent?): Boolean {
                                            when (event?.actionMasked) {
                                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                                    if (interactiveKind || canScrollHorizontally(-1) || canScrollHorizontally(1)) {
                                                        parent?.requestDisallowInterceptTouchEvent(true)
                                                    }
                                                }
                                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                                    parent?.requestDisallowInterceptTouchEvent(false)
                                                }
                                            }
                                            return super.onTouchEvent(event)
                                        }
                                    }.apply {
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        isVerticalScrollBarEnabled = false
                                        isHorizontalScrollBarEnabled = true
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                        addJavascriptInterface(
                                            object {
                                                @JavascriptInterface
                                                fun onHeight(px: Int) {
                                                    post {
                                                        val target = px.coerceIn(minH, maxH)
                                                        if (kotlin.math.abs(target - contentHeightDp) > 8) contentHeightDp = target
                                                    }
                                                }
                                            },
                                            "SolbraidInlineBridge",
                                        )
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                                val uri = request?.url ?: return null
                                                if (uri.host != INLINE_ARTIFACT_HOST) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                                                val name = uri.lastPathSegment?.takeIf { it in setOf("artifact-viewer.html", "artifact-latex.js", "artifact-mermaid.js", "artifact-renderer.js") }
                                                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                                                val mime = if (name.endsWith(".html")) "text/html" else "application/javascript"
                                                return runCatching { WebResourceResponse(mime, "utf-8", ctx.assets.open("galiais/runtime/$name")) }
                                                    .getOrElse { WebResourceResponse("text/plain", "utf-8", 404, "Missing Asset", emptyMap(), "".byteInputStream()) }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                pageLoaded = true
                                                view?.tag = payloadJson
                                                view?.evaluateJavascript("window.SolbraidStudio && window.SolbraidStudio.render($payloadJson);", null)
                                            }
                                        }
                                        webViewHolder.view = this
                                        loadUrl("https://$INLINE_ARTIFACT_HOST/artifact-viewer.html?dark=${if (dark) 1 else 0}")
                                    }
                                },
                                update = { wv ->
                                    webViewHolder.view = wv
                                    // 去重：仅当内容/主题变化时重渲染 WebView，彻底杜绝父重组触发的重复渲染与高度抖动风暴
                                    if (pageLoaded && wv.tag != payloadJson) {
                                        wv.tag = payloadJson
                                        wv.evaluateJavascript("window.SolbraidStudio && window.SolbraidStudio.render($payloadJson);", null)
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

/** 原生 Compose 可排序 CSV/TSV 数据表格（零 WebView 开销，支持点击列头排序 + 内联迷你数值条 + 全屏图表）。 */
@Composable
fun InlineCsvTableCard(
    csvText: String,
    onOpenStudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(csvText) {
        val lines = csvText.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 2) null else {
            val sep = if (lines[0].contains('\t') && !lines[0].contains(',')) '\t' else ','
            fun splitLine(line: String): List<String> {
                val out = mutableListOf<String>()
                val cur = StringBuilder()
                var inQ = false
                var i = 0
                while (i < line.length) {
                    val ch = line[i]
                    if (inQ) {
                        if (ch == '"' && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i += 2; continue }
                        if (ch == '"') { inQ = false; i++; continue }
                        cur.append(ch)
                    } else if (ch == '"') {
                        inQ = true
                    } else if (ch == sep) {
                        out += cur.toString().trim(); cur.clear()
                    } else {
                        cur.append(ch)
                    }
                    i++
                }
                out += cur.toString().trim()
                return out
            }
            val headers = splitLine(lines[0])
            val rows = lines.drop(1).map(::splitLine)
            headers to rows
        }
    }
    if (parsed == null) {
        CodeBlock(csvText, "csv", modifier = modifier)
        return
    }
    val (headers, rawRows) = parsed
    var sortCol by remember(csvText) { mutableIntStateOf(-1) }
    var sortAsc by remember(csvText) { mutableStateOf(true) }
    var showRaw by remember(csvText) { mutableStateOf(false) }

    val sortedRows = remember(rawRows, sortCol, sortAsc) {
        if (sortCol < 0) rawRows else rawRows.sortedWith { a, b ->
            val va = a.getOrNull(sortCol).orEmpty()
            val vb = b.getOrNull(sortCol).orEmpty()
            val na = va.toDoubleOrNull()
            val nb = vb.toDoubleOrNull()
            val cmp = if (na != null && nb != null) na.compareTo(nb) else va.compareTo(vb, ignoreCase = true)
            if (sortAsc) cmp else -cmp
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = D.shape(12.dp),
        color = G.surface,
        border = BorderStroke(0.75.dp, G.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(G.surfaceSubtle).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(GaliaisIcons.FileLines, contentDescription = null, tint = G.accent, modifier = Modifier.size(13.dp))
                Text("数据表 · ${rawRows.size} 行 × ${headers.size} 列（点击表头可排序）", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TagPill(if (showRaw) "表格" else "源码", fg = G.inkMuted, bg = G.surface, border = G.border, onClick = { showRaw = !showRaw })
                if (onOpenStudio != null) {
                    TagPill("图表视图", fg = G.accent, bg = G.accentContainer, onClick = onOpenStudio)
                }
            }
            Hairline()
            if (showRaw) {
                CodeBlock(csvText, "csv", modifier = Modifier.padding(8.dp))
            } else {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(if (G.dark) G.surfaceElevated else G.surfaceSubtle)) {
                        headers.forEachIndexed { idx, h ->
                            val marker = if (sortCol == idx) (if (sortAsc) " ↑" else " ↓") else ""
                            Text(
                                "$h$marker",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (sortCol == idx) G.accent else G.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(124.dp)
                                    .clickable {
                                        if (sortCol == idx) sortAsc = !sortAsc else { sortCol = idx; sortAsc = true }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.75.dp, color = G.border)
                    sortedRows.take(30).forEachIndexed { rIdx, row ->
                        Row(Modifier.background(if (rIdx % 2 == 1) G.surfaceSubtle.copy(alpha = 0.45f) else Color.Transparent)) {
                            headers.indices.forEach { cIdx ->
                                Text(
                                    row.getOrNull(cIdx).orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = G.ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(124.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                                )
                            }
                        }
                        if (rIdx < minOf(29, sortedRows.lastIndex)) HorizontalDivider(thickness = 0.5.dp, color = G.border)
                    }
                    if (sortedRows.size > 30) {
                        Text("仅展示前 30 行（共 ${sortedRows.size} 行），点右上角「图表视图」查看全部。", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

private val inlineBitmapCache = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?): Boolean = size > 24
}

/** Markdown 内联图片卡片：后台线程异步解码 + LRU 缓存渲染 Base64 Data URI 图片，并对远程图片链接提供一键打开。 */
@Composable
fun InlineImageCard(
    destination: String,
    alt: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val decoded by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = destination) {
        if (!destination.startsWith("data:image/", ignoreCase = true)) {
            value = null
            return@produceState
        }
        val cacheKey = "${destination.length}:${destination.hashCode()}"
        val cached = synchronized(inlineBitmapCache) { inlineBitmapCache[cacheKey] }
        if (cached != null) {
            value = cached
            return@produceState
        }
        val img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val b64 = destination.substringAfter("base64,", "")
                if (b64.isBlank() || b64.length > 6_000_000) null
                else {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (img != null) {
            synchronized(inlineBitmapCache) { inlineBitmapCache[cacheKey] = img }
        }
        value = img
    }
    val img = decoded
    if (img != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = D.shape(12.dp),
            color = G.surface,
            border = BorderStroke(0.75.dp, G.border),
        ) {
            Column {
                Image(
                    bitmap = img,
                    contentDescription = alt.ifBlank { "图片" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp).background(G.surfaceSubtle),
                )
                if (alt.isNotBlank()) {
                    Text(alt, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    } else {
        val isHttp = destination.startsWith("https://") || destination.startsWith("http://")
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(D.shape(10.dp))
                .clickable(enabled = isHttp) { runCatching { uriHandler.openUri(destination) } },
            shape = D.shape(10.dp),
            color = G.surfaceSubtle,
            border = BorderStroke(0.75.dp, G.border),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(GaliaisIcons.Image, contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(alt.ifBlank { "图片资源" }, style = MaterialTheme.typography.labelMedium, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(destination, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isHttp) TagPill("查看", fg = G.accent, bg = G.accentContainer)
            }
        }
    }
}
