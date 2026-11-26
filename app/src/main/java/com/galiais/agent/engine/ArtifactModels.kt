package com.galiais.agent.engine

import org.json.JSONObject

/** 产物类型：统一支持 Claude `<antArtifact>`、Codex 规划可视化、Antigravity 产物与离线 LaTeX 文档。 */
enum class ArtifactKind(val wire: String, val label: String, val extension: String) {
    HTML("html", "交互网页", "html"),
    SVG("svg", "SVG 图形", "svg"),
    MERMAID("mermaid", "Mermaid 图表", "mmd"),
    CHART("chart", "交互图表", "json"),
    MATH("math", "数学公式", "tex"),
    LATEX("latex", "LaTeX 排版", "tex"),
    REACT("react", "React 组件", "jsx"),
    MARKDOWN("markdown", "文档报告", "md"),
    CSV("csv", "数据图表", "csv"),
    CODE("code", "代码产物", "txt");

    companion object {
        fun fromMimeOrLang(type: String?, language: String?): ArtifactKind {
            val t = (type ?: "").lowercase().trim()
            val l = (language ?: "").lowercase().trim()
            return when {
                t.contains("mermaid") || l in setOf("mermaid", "mmd") -> MERMAID
                t.contains("chart") || t.contains("echarts") || t.contains("vega") || l in setOf("chart", "echarts", "chartjs", "plot", "vega", "vega-lite") -> CHART
                t.contains("svg") || l == "svg" -> SVG
                l in setOf("math", "katex") -> MATH
                t.contains("latex") || t.contains("tex") || l in setOf("latex", "tex") -> LATEX
                t.contains("react") || l in setOf("jsx", "tsx", "react") -> REACT
                t.contains("html") || l in setOf("html", "htm") -> HTML
                t.contains("csv") || l in setOf("csv", "tsv") -> CSV
                t.contains("markdown") || l in setOf("markdown", "md") -> MARKDOWN
                else -> CODE
            }
        }
    }
}

data class ChatArtifact(
    val id: String,
    val identifier: String,
    val title: String,
    val kind: ArtifactKind,
    val language: String,
    val content: String,
    val version: Int = 1,
    val sourceItemId: String = "",
    val streaming: Boolean = false,
)

/** 从单条助手 Markdown 文本拆分普通段落与 `<antArtifact>` 交互产物块（支持流式未闭合标签）。 */
sealed interface MessageSegment {
    data class Text(val markdown: String) : MessageSegment
    data class Artifact(val artifact: ChatArtifact) : MessageSegment
}

private val ANT_OPEN_RE = Regex("""<antArtifact\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val ATTR_RE = Regex("""([a-zA-Z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val VISUAL_FENCE_RE = Regex("""```([a-zA-Z0-9_+-]+)\s*\n([\s\S]*?)```""")
private val RAW_SVG_RE = Regex("""<svg\b[^>]*>[\s\S]*?</svg>""", RegexOption.IGNORE_CASE)

private fun parseAttrs(raw: String): Map<String, String> = buildMap {
    ATTR_RE.findAll(raw).forEach { m ->
        put(m.groupValues[1].lowercase(), m.groupValues[2].ifEmpty { m.groupValues[3] })
    }
}

/** 提取普通 Markdown 段落中未被代码围栏包裹的独立 `<svg>...</svg>` 片段。 */
private fun splitRawSvgSegments(sourceItemId: String, markdown: String, startIdx: Int): List<MessageSegment> {
    if (!markdown.contains("<svg", ignoreCase = true) || !markdown.contains("</svg>", ignoreCase = true)) {
        return listOf(MessageSegment.Text(markdown))
    }
    // 计算每个三反引号围栏区间，避免误拆 ```svg 或 ```html 代码块
    val fenceRanges = VISUAL_FENCE_RE.findAll(markdown).map { it.range }.toList()
    val matches = RAW_SVG_RE.findAll(markdown).filter { m ->
        m.value.length >= 40 && fenceRanges.none { f -> m.range.first >= f.first && m.range.last <= f.last }
    }.toList()
    if (matches.isEmpty()) return listOf(MessageSegment.Text(markdown))

    val out = mutableListOf<MessageSegment>()
    var cursor = 0
    matches.forEachIndexed { offset, m ->
        if (m.range.first > cursor) {
            val before = markdown.substring(cursor, m.range.first)
            if (before.isNotBlank()) out += MessageSegment.Text(before)
        }
        val svg = m.value.trim()
        val idx = startIdx + offset
        val title = HTML_TITLE_RE.find(svg)?.groupValues?.get(1)?.trim()
            ?: "SVG 图形 #${idx + 1}"
        out += MessageSegment.Artifact(
            ChatArtifact(
                id = "$sourceItemId:rawsvg:$idx",
                identifier = "svg-$sourceItemId-$idx",
                title = title,
                kind = ArtifactKind.SVG,
                language = "svg",
                content = svg,
                version = 1,
                sourceItemId = sourceItemId,
                streaming = false,
            ),
        )
        cursor = m.range.last + 1
    }
    if (cursor < markdown.length) {
        val tail = markdown.substring(cursor)
        if (tail.isNotBlank()) out += MessageSegment.Text(tail)
    }
    return out
}

fun splitMessageSegments(sourceItemId: String, text: String, streaming: Boolean = false): List<MessageSegment> {
    if (!text.contains("<antArtifact", ignoreCase = true)) {
        return splitRawSvgSegments(sourceItemId, text, 0)
    }
    val segments = mutableListOf<MessageSegment>()
    var cursor = 0
    var idx = 0
    while (cursor < text.length) {
        val open = ANT_OPEN_RE.find(text, cursor) ?: break
        if (open.range.first > cursor) {
            val before = text.substring(cursor, open.range.first)
            if (before.isNotBlank()) segments += splitRawSvgSegments(sourceItemId, before, idx)
        }
        val attrs = parseAttrs(open.groupValues[1])
        val bodyStart = open.range.last + 1
        val closeIdx = text.indexOf("</antArtifact>", bodyStart, ignoreCase = true)
        val closed = closeIdx >= 0
        val body = if (closed) text.substring(bodyStart, closeIdx) else text.substring(bodyStart)
        val kind = ArtifactKind.fromMimeOrLang(attrs["type"], attrs["language"])
        val ident = attrs["identifier"]?.takeIf { it.isNotBlank() } ?: "artifact-$sourceItemId-$idx"
        val title = attrs["title"]?.takeIf { it.isNotBlank() } ?: "${kind.label} #${idx + 1}"
        segments += MessageSegment.Artifact(
            ChatArtifact(
                id = "$sourceItemId:ant:$idx",
                identifier = ident,
                title = title,
                kind = kind,
                language = attrs["language"] ?: kind.extension,
                content = body.trim(),
                version = 1,
                sourceItemId = sourceItemId,
                streaming = !closed && streaming,
            ),
        )
        idx++
        cursor = if (closed) closeIdx + "</antArtifact>".length else text.length
    }
    if (cursor < text.length) {
        val tail = text.substring(cursor)
        if (tail.isNotBlank()) segments += splitRawSvgSegments(sourceItemId, tail, idx)
    }
    return segments
}

private val LATEX_TITLE_RE = Regex("""\\title\{([^{}]{1,60})\}""")
private val HTML_TITLE_RE = Regex("""<title>([^<]{1,60})</title>""", RegexOption.IGNORE_CASE)
private val VISUAL_ARTIFACT_TOOLS = setOf("show_widget", "visualize", "render_artifact", "create_artifact")
private val itemArtifactCache = object : LinkedHashMap<String, List<ChatArtifact>>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatArtifact>>?): Boolean = size > 512
}

private fun extractRawArtifactsForItem(item: TimelineItem): List<ChatArtifact> {
    val cacheKey = when (item) {
        is TimelineItem.AgentMessage -> if (item.streaming) null else "a:${item.id}:${item.text.length}:${item.text.hashCode()}"
        is TimelineItem.ToolCall -> if (item.name in VISUAL_ARTIFACT_TOOLS) "t:${item.id}:${item.input.length}:${item.input.hashCode()}" else return emptyList()
        is TimelineItem.Plan -> if (item.streaming) null else "p:${item.id}:${item.text.length}:${item.text.hashCode()}"
        else -> return emptyList()
    }
    if (cacheKey != null) {
        synchronized(itemArtifactCache) {
            itemArtifactCache[cacheKey]?.let { return it }
        }
    }
    val out = mutableListOf<ChatArtifact>()
    when (item) {
        is TimelineItem.AgentMessage -> {
            // 快路径：无围栏、无 SVG、无 antArtifact 时零正则直接返回
            val t = item.text
            if (t.contains("```") || t.contains("<svg", ignoreCase = true) || t.contains("<antArtifact", ignoreCase = true)) {
                val segments = splitMessageSegments(item.id, t, item.streaming)
                var fenceIdx = 0
                for (seg in segments) {
                    when (seg) {
                        is MessageSegment.Artifact -> if (seg.artifact.content.isNotBlank() || seg.artifact.streaming) out += seg.artifact
                        is MessageSegment.Text -> if (seg.markdown.contains("```")) {
                            VISUAL_FENCE_RE.findAll(seg.markdown).forEach { m ->
                                val lang = m.groupValues[1].lowercase()
                                val code = m.groupValues[2].trim()
                                val kind = when (lang) {
                                    "html", "htm" -> if (code.length >= 40) ArtifactKind.HTML else null
                                    "svg" -> ArtifactKind.SVG
                                    "mermaid", "mmd" -> ArtifactKind.MERMAID
                                    "chart", "echarts", "chartjs", "plot", "vega", "vega-lite" -> ArtifactKind.CHART
                                    "math", "katex" -> ArtifactKind.MATH
                                    "latex", "tex" -> ArtifactKind.LATEX
                                    "jsx", "tsx" -> if (code.length >= 60) ArtifactKind.REACT else null
                                    "csv", "tsv" -> if (code.lines().size >= 2) ArtifactKind.CSV else null
                                    else -> null
                                } ?: return@forEach
                                val title = when (kind) {
                                    ArtifactKind.LATEX -> LATEX_TITLE_RE.find(code)?.groupValues?.get(1) ?: "LaTeX 排版 #${fenceIdx + 1}"
                                    ArtifactKind.HTML -> HTML_TITLE_RE.find(code)?.groupValues?.get(1) ?: "交互网页 #${fenceIdx + 1}"
                                    else -> "${kind.label} #${fenceIdx + 1}"
                                }
                                if (code.isNotBlank() || item.streaming) {
                                    out += ChatArtifact(
                                        id = "${item.id}:fence:$fenceIdx",
                                        identifier = "fence-${kind.wire}-${item.id}-$fenceIdx",
                                        title = title,
                                        kind = kind,
                                        language = lang,
                                        content = code,
                                        sourceItemId = item.id,
                                        streaming = item.streaming,
                                    )
                                }
                                fenceIdx++
                            }
                        }
                    }
                }
            }
        }
        is TimelineItem.ToolCall -> {
            val obj = runCatching { JSONObject(item.input) }.getOrNull()
            val code = obj?.optString("widget_code")?.ifBlank { null }
                ?: obj?.optString("html")?.ifBlank { null }
                ?: obj?.optString("content")?.ifBlank { null }
                ?: obj?.optString("code")?.ifBlank { null }
            if (obj != null && !code.isNullOrBlank()) {
                val title = obj.optString("title").ifBlank { "交互组件 ${item.name}" }
                val kind = ArtifactKind.fromMimeOrLang(obj.optString("type"), if (code.trimStart().startsWith("<svg")) "svg" else "html")
                out += ChatArtifact(
                    id = "${item.id}:widget",
                    identifier = obj.optString("identifier").ifBlank { "widget-${item.id}" },
                    title = title,
                    kind = kind,
                    language = kind.extension,
                    content = code,
                    sourceItemId = item.id,
                )
            }
        }
        is TimelineItem.Plan -> {
            if (item.text.isNotBlank() || item.streaming) {
                val baseIdent = item.filePath?.takeIf { it.isNotBlank() }
                    ?.substringAfterLast('/')
                    ?.let { "plan-$it" }
                    ?: "plan-${item.id}"
                out += ChatArtifact(
                    id = "${item.id}:plan",
                    identifier = baseIdent,
                    title = item.title.ifBlank { "执行计划" },
                    kind = ArtifactKind.MARKDOWN,
                    language = "markdown",
                    content = item.text.trim(),
                    sourceItemId = item.id,
                    streaming = item.streaming,
                )
            }
        }
        is TimelineItem.UserMessage,
        is TimelineItem.Reasoning,
        is TimelineItem.CommandRun,
        is TimelineItem.FileEdit,
        is TimelineItem.Delegation,
        is TimelineItem.InteractionReceipt,
        is TimelineItem.Notice,
        -> Unit
    }
    if (cacheKey != null) {
        synchronized(itemArtifactCache) {
            itemArtifactCache[cacheKey] = out
        }
    }
    return out
}

/** 从整条会话时间线汇总全部可渲染产物（`<antArtifact>` + Claude `show_widget`/`visualize` + 可视化代码块），按条目 LRU 缓存并自动追踪版本号。 */
fun extractArtifactsFromItems(items: List<TimelineItem>): List<ChatArtifact> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<ChatArtifact>()
    val versionByIdent = HashMap<String, Int>()
    for (item in items) {
        val rawList = extractRawArtifactsForItem(item)
        if (rawList.isEmpty()) continue
        for (raw in rawList) {
            if (raw.content.isBlank() && !raw.streaming) continue
            val ver = (versionByIdent[raw.identifier] ?: 0) + 1
            versionByIdent[raw.identifier] = ver
            result += if (ver == raw.version) raw else raw.copy(version = ver)
        }
    }
    return result
}

data class VisualArtifactTemplate(
    val id: String,
    val category: String,
    val title: String,
    val subtitle: String,
    val kind: ArtifactKind,
    val prompt: String,
)

/** Codex Official-Plan 可视化 + Claude Artifacts + 离线 LaTeX 产物模板库。 */
val visualArtifactTemplates: List<VisualArtifactTemplate> = listOf(
    VisualArtifactTemplate(
        id = "arch-mermaid",
        category = "架构与流程",
        title = "系统架构 / 流程图",
        subtitle = "基于 Codex 规划视觉规范输出 Mermaid 架构与数据流图",
        kind = ArtifactKind.MERMAID,
        prompt = "请分析当前项目或以下需求，绘制清晰的系统架构与核心数据流 Mermaid 图（使用 ```mermaid 或 <antArtifact type=\"application/vnd.ant.mermaid\">，节点标签简明，带模块分组与数据方向）：\n",
    ),
    VisualArtifactTemplate(
        id = "seq-mermaid",
        category = "架构与流程",
        title = "调用时序图",
        subtitle = "梳理跨服务/客户端请求、鉴权与状态流转",
        kind = ArtifactKind.MERMAID,
        prompt = "请绘制以下交互流程的 Mermaid sequenceDiagram 时序图，标注关键请求参数、状态变更与异常分支：\n",
    ),
    VisualArtifactTemplate(
        id = "interactive-dashboard",
        category = "交互与界面",
        title = "交互式 HTML 仪表盘 / 原型",
        subtitle = "单文件零外链 HTML/CSS/JS，可在离线工作台实时点击交互",
        kind = ArtifactKind.HTML,
        prompt = "请生成一个完全自包含、零外部 CDN 依赖的单文件 HTML 交互式可视化页面（使用 <antArtifact type=\"text/html\" title=\"...\"> 或 ```html），适配 360px 移动端屏幕并带交互控件：\n",
    ),
    VisualArtifactTemplate(
        id = "react-widget",
        category = "交互与界面",
        title = "React 交互式组件（JSX + Hooks）",
        subtitle = "离线内置 JSX 编译器与 useState/useMemo 运行时，支持真实点击状态变化",
        kind = ArtifactKind.REACT,
        prompt = "请使用 React 函数组件（```jsx 或 <antArtifact type=\"application/vnd.ant.react\">，使用 useState/useMemo，不引入第三方 npm 包，导出默认函数组件 App）实现以下交互式可视化小工具：\n",
    ),
    VisualArtifactTemplate(
        id = "ui-wireframe",
        category = "交互与界面",
        title = "移动端 UI 交互原型",
        subtitle = "带可点击 Tab/弹窗/状态切换的精简界面设计稿",
        kind = ArtifactKind.HTML,
        prompt = "请设计一个现代简洁的移动端界面交互原型，以零外链单文件 HTML 输出，包含真实可切换状态与响应式布局，主题围绕：\n",
    ),
    VisualArtifactTemplate(
        id = "svg-diagram",
        category = "图形与数据",
        title = "高精 SVG 原理示意图",
        subtitle = "适合算法结构、内存布局、硬件拓扑与几何图解",
        kind = ArtifactKind.SVG,
        prompt = "请用规范的独立 SVG（带明确 viewBox 与清晰标注文字，使用 <antArtifact type=\"image/svg+xml\"> 或 ```svg）绘制以下概念的高精度原理示意图：\n",
    ),
    VisualArtifactTemplate(
        id = "interactive-chart",
        category = "图形与数据",
        title = "交互式统计图表（柱状/折线/饼图/雷达）",
        subtitle = "输出 ```chart JSON，聊天内直接渲染可点击图例与悬浮提示",
        kind = ArtifactKind.CHART,
        prompt = "请整理以下数据的对比与趋势，输出一个 ```chart JSON 代码块（支持 type: bar/line/area/pie/donut/radar/scatter，包含 title、labels、series:[{name, values}]），以便在聊天窗口直接渲染离线交互图表：\n",
    ),
    VisualArtifactTemplate(
        id = "data-csv-chart",
        category = "图形与数据",
        title = "数据对比表与自动柱状图",
        subtitle = "输出结构化 CSV，在工作台自动生成图表与统计表",
        kind = ArtifactKind.CSV,
        prompt = "请整理以下主题的核心量化对比数据，使用 ```csv 代码块（首行为表头，第 2 列为主要数值指标）输出以便离线自动生成柱状图与数据表：\n",
    ),
    VisualArtifactTemplate(
        id = "latex-paper",
        category = "LaTeX 排版",
        title = "LaTeX 学术论文 / 技术报告",
        subtitle = "支持离线编译预览与一键导出 PDF（定理、公式、三线表）",
        kind = ArtifactKind.LATEX,
        prompt = "请用规范完整的 LaTeX（包含 \\documentclass、\\title、\\begin{abstract}、\\section、数学公式与表格，使用 <antArtifact type=\"text/latex\"> 或 ```latex）撰写技术报告或推导文档，主题为：\n",
    ),
    VisualArtifactTemplate(
        id = "latex-formula",
        category = "LaTeX 排版",
        title = "数学公式推导与证明",
        subtitle = "带编号方程、矩阵、分段函数与定理证明环境",
        kind = ArtifactKind.LATEX,
        prompt = "请使用 LaTeX（equation / align / theorem / proof 环境，输出在 ```latex 代码块中）严谨推导并证明以下数学问题：\n",
    ),
)

const val DEFAULT_LATEX_SCRATCHPAD = """\documentclass[11pt]{article}
\title{Solbraid 离线 LaTeX 排版引擎示例}
\author{Solbraid Studio}
\date{2026}
\begin{document}
\maketitle

\begin{abstract}
本引擎支持零联网离线编译常见学术文档结构、数学公式、矩阵、定理证明与三线表，并可一键导出标准 PDF；当 Linux 环境安装 \texttt{tectonic} 或 \texttt{xelatex} 时也可调用原生 TeX 引擎。
\end{abstract}

\section{核心数学公式}
欧拉恒等式与高斯积分展示：
\begin{equation}
e^{i\pi} + 1 = 0, \qquad \int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}
\end{equation}

矩阵变换与分段函数：
\[
A = \begin{pmatrix} \cos\theta & -\sin\theta \\ \sin\theta & \cos\theta \end{pmatrix}, \quad
f(x) = \begin{cases} x^2 & x \ge 0 \\ -x & x < 0 \end{cases}
\]

\begin{theorem}[Cauchy-Schwarz 不等式]
对任意向量 $\vec{u}, \vec{v} \in \mathbb{R}^n$，均有 $|\langle \vec{u}, \vec{v} \rangle|^2 \leq \|\vec{u}\|^2 \|\vec{v}\|^2$。
\end{theorem}

\section{性能指标对比}
\begin{tabular}{lcc}
\toprule
编译模式 & 离线可用 & 典型耗时 \\
\midrule
Solbraid 内置引擎 & 是 & < 20 ms \\
Linux 原生 TeX & 需安装 & 1.5 s \\
\bottomrule
\end{tabular}
\end{document}"""
