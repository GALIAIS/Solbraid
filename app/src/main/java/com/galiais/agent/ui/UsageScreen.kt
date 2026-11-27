package com.galiais.agent.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.ConversationSummary
import com.galiais.agent.engine.TokenUsage
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.RateLimitSummary
import com.galiais.agent.engine.arr
import com.galiais.agent.engine.objects
import com.galiais.agent.engine.strings
import com.galiais.agent.engine.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class UsageTotals(
    val requests: Int = 0,
    val succeeded: Int = 0,
    val errors: Int = 0,
    val cancelled: Int = 0,
    val successRate: Double = 100.0,
    val inputTokens: Long = 0L,
    val uncachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val cacheHitRate: Double = 0.0,
    val costUsd: Double = 0.0,
    val directCount: Int = 0,
    val gatewayCount: Int = 0,
    val estimatedCount: Int = 0,
    val avgLatencyMs: Int = 0,
    val p50LatencyMs: Int = 0,
    val p95LatencyMs: Int = 0,
    val avgTtftMs: Int = 0,
    val p50TtftMs: Int = 0,
    val p95TtftMs: Int = 0,
    val ttftSampleCount: Int = 0,
    val avgTps: Double = 0.0,
    val medianTps: Double = 0.0,
    val peakTps: Double = 0.0,
    val tpsSampleCount: Int = 0,
    val e2eAvgTps: Double = 0.0,
    val peakContextUsed: Long = 0L,
    val peakContextWindow: Long = 0L,
    val uniqueSessions: Int = 0,
)

private data class UsageGroupRow(
    val id: String,
    val name: String,
    val requests: Int,
    val errors: Int,
    val inputTokens: Long,
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val cacheHitRate: Double,
    val costUsd: Double,
    val avgLatencyMs: Int,
    val p95LatencyMs: Int,
    val avgTtftMs: Int,
    val avgTps: Double,
    val e2eAvgTps: Double,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

private data class UsageSessionRow(
    val id: String,
    val key: String,
    val agent: String,
    val providerId: String,
    val providerName: String,
    val models: List<String>,
    val requests: Int,
    val errors: Int,
    val inputTokens: Long,
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val cacheHitRate: Double,
    val costUsd: Double,
    val avgLatencyMs: Int,
    val avgTtftMs: Int,
    val avgTps: Double,
    val e2eAvgTps: Double,
    val maxContextUsed: Long,
    val contextWindow: Long,
    val firstAt: Long,
    val lastAt: Long,
)

private data class UsageHeatmapCell(
    val day: String,
    val requests: Int,
    val tokens: Long,
    val costUsd: Double,
    val errors: Int,
    val level: Int,
)

private data class UsageRequestRow(
    val ts: Long,
    val agent: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val requestedModel: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val reasoningTokens: Long,
    val ms: Int,
    val ttftMs: Int,
    val genMs: Int,
    val streamed: Boolean,
    val exact: Boolean,
    val tps: Double,
    val e2eTps: Double,
    val status: Int,
    val err: String,
    val billingKind: String,
    val priceRate: Double,
    val priceMatched: Boolean,
    val costUsd: Double,
    val sessionId: String,
    val contextUsed: Long,
    val contextWindow: Long,
    val source: String,
    val rawJson: JSONObject,
) {
    val uncachedInputTokens: Long get() = (inputTokens - cacheReadTokens - cacheWriteTokens).coerceAtLeast(0L)
    val totalTokens: Long get() = inputTokens + outputTokens
    val cacheHitRate: Double get() = if (inputTokens > 0L) ((cacheReadTokens * 100.0) / inputTokens).coerceIn(0.0, 100.0) else 0.0
}

private fun fmtCompactTokens(n: Long): String = when {
    n >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", n / 1_000_000_000.0)
    n >= 1_000_000L -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000L -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

private fun fmtExactTokens(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

private fun fmtUsd(cost: Double): String = when {
    cost <= 0.0 -> "\$0.00"
    cost < 0.01 -> String.format(Locale.US, "\$%.4f", cost)
    else -> String.format(Locale.US, "\$%.2f", cost)
}

private fun fmtDurationMs(ms: Int): String = when {
    ms <= 0 -> "—"
    ms >= 60_000 -> String.format(Locale.US, "%dm%.1fs", ms / 60_000, (ms % 60_000) / 1000.0)
    ms >= 10_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    ms >= 1_000 -> String.format(Locale.US, "%.2fs", ms / 1000.0)
    else -> "${ms}ms"
}

private fun fmtRatePct(pct: Double): String = String.format(Locale.US, "%.1f%%", pct.coerceIn(0.0, 100.0))

private fun displayAgentName(agentId: String): String = when (agentId.lowercase()) {
    "codex" -> "Codex"
    "claude" -> "Claude Code"
    "antigravity" -> "Antigravity"
    "gateway" -> "本地网关"
    else -> agentId.ifBlank { "未知引擎" }
}

private fun parseUsageTotals(o: JSONObject?): UsageTotals {
    if (o == null) return UsageTotals()
    val inTok = o.optLong("inputTokens")
    val outTok = o.optLong("outputTokens")
    val cacheR = o.optLong("cacheReadTokens")
    val cacheW = o.optLong("cacheWriteTokens")
    return UsageTotals(
        requests = o.optInt("requests"),
        succeeded = o.optInt("succeeded", (o.optInt("requests") - o.optInt("errors") - o.optInt("cancelled")).coerceAtLeast(0)),
        errors = o.optInt("errors"),
        cancelled = o.optInt("cancelled"),
        successRate = o.optDouble("successRate", 100.0),
        inputTokens = inTok,
        uncachedInputTokens = o.optLong("uncachedInputTokens", (inTok - cacheR - cacheW).coerceAtLeast(0L)),
        outputTokens = outTok,
        cacheReadTokens = cacheR,
        cacheWriteTokens = cacheW,
        reasoningTokens = o.optLong("reasoningTokens"),
        totalTokens = o.optLong("totalTokens", inTok + outTok),
        cacheHitRate = o.optDouble("cacheHitRate", if (inTok > 0L) (cacheR * 100.0) / inTok else 0.0),
        costUsd = o.optDouble("costUsd", 0.0),
        directCount = o.optInt("directCount"),
        gatewayCount = o.optInt("gatewayCount"),
        estimatedCount = o.optInt("estimatedCount"),
        avgLatencyMs = o.optInt("avgLatencyMs"),
        p50LatencyMs = o.optInt("p50LatencyMs"),
        p95LatencyMs = o.optInt("p95LatencyMs"),
        avgTtftMs = o.optInt("avgTtftMs"),
        p50TtftMs = o.optInt("p50TtftMs"),
        p95TtftMs = o.optInt("p95TtftMs"),
        ttftSampleCount = o.optInt("ttftSampleCount"),
        avgTps = o.optDouble("avgTps", 0.0),
        medianTps = o.optDouble("medianTps", 0.0),
        peakTps = o.optDouble("peakTps", 0.0),
        tpsSampleCount = o.optInt("tpsSampleCount"),
        e2eAvgTps = o.optDouble("e2eAvgTps", 0.0),
        peakContextUsed = o.optLong("peakContextUsed"),
        peakContextWindow = o.optLong("peakContextWindow"),
        uniqueSessions = o.optInt("uniqueSessions"),
    )
}

private fun parseUsageGroups(root: JSONObject, key: String, mapAgentName: Boolean = false): List<UsageGroupRow> =
    root.arr(key).objects().map { o ->
        val rawId = o.optString("id")
        val rawName = o.optString("name", rawId)
        val inTok = o.optLong("inputTokens")
        val cacheR = o.optLong("cacheReadTokens")
        val cacheW = o.optLong("cacheWriteTokens")
        UsageGroupRow(
            id = rawId,
            name = if (mapAgentName) displayAgentName(rawId) else rawName,
            requests = o.optInt("requests"),
            errors = o.optInt("errors"),
            inputTokens = inTok,
            uncachedInputTokens = o.optLong("uncachedInputTokens", (inTok - cacheR - cacheW).coerceAtLeast(0L)),
            outputTokens = o.optLong("outputTokens"),
            cacheReadTokens = cacheR,
            cacheWriteTokens = cacheW,
            reasoningTokens = o.optLong("reasoningTokens"),
            cacheHitRate = o.optDouble("cacheHitRate", if (inTok > 0L) (cacheR * 100.0) / inTok else 0.0),
            costUsd = o.optDouble("costUsd", 0.0),
            avgLatencyMs = o.optInt("avgLatencyMs"),
            p95LatencyMs = o.optInt("p95LatencyMs"),
            avgTtftMs = o.optInt("avgTtftMs"),
            avgTps = o.optDouble("avgTps", 0.0),
            e2eAvgTps = o.optDouble("e2eAvgTps", 0.0),
        )
    }

private fun parseUsageSessions(root: JSONObject): List<UsageSessionRow> =
    root.arr("bySession").objects().map { o ->
        val inTok = o.optLong("inputTokens")
        val outTok = o.optLong("outputTokens")
        val cacheR = o.optLong("cacheReadTokens")
        val cacheW = o.optLong("cacheWriteTokens")
        UsageSessionRow(
            id = o.optString("id"),
            key = o.optString("key"),
            agent = o.optString("agent"),
            providerId = o.optString("providerId"),
            providerName = o.optString("providerName", o.optString("providerId")),
            models = o.arr("models").strings(),
            requests = o.optInt("requests"),
            errors = o.optInt("errors"),
            inputTokens = inTok,
            uncachedInputTokens = o.optLong("uncachedInputTokens", (inTok - cacheR - cacheW).coerceAtLeast(0L)),
            outputTokens = outTok,
            cacheReadTokens = cacheR,
            cacheWriteTokens = cacheW,
            reasoningTokens = o.optLong("reasoningTokens"),
            totalTokens = o.optLong("totalTokens", inTok + outTok),
            cacheHitRate = o.optDouble("cacheHitRate", if (inTok > 0L) (cacheR * 100.0) / inTok else 0.0),
            costUsd = o.optDouble("costUsd", 0.0),
            avgLatencyMs = o.optInt("avgLatencyMs"),
            avgTtftMs = o.optInt("avgTtftMs"),
            avgTps = o.optDouble("avgTps", 0.0),
            e2eAvgTps = o.optDouble("e2eAvgTps", 0.0),
            maxContextUsed = o.optLong("maxContextUsed"),
            contextWindow = o.optLong("contextWindow"),
            firstAt = o.optLong("firstAt"),
            lastAt = o.optLong("lastAt"),
        )
    }

private data class LiveUsageSession(
    val summary: ConversationSummary,
    val model: String?,
    val running: Boolean,
    val tokenUsage: TokenUsage?,
)

private class UsageViewData(summary: JSONObject) {
    val totals = parseUsageTotals(summary.optJSONObject("totals"))
    val totalMatched = summary.optInt("totalMatched", totals.requests)
    val byProvider = parseUsageGroups(summary, "byProvider")
    val byAgent = parseUsageGroups(summary, "byAgent", mapAgentName = true)
    val byModel = parseUsageGroups(summary, "byModel")
    val bySession = parseUsageSessions(summary)
    val daily = parseUsageGroups(summary, "daily")
    val heatmap = summary.arr("heatmap").objects().map { o ->
        UsageHeatmapCell(
            day = o.optString("day"),
            requests = o.optInt("requests"),
            tokens = o.optLong("tokens"),
            costUsd = o.optDouble("costUsd", 0.0),
            errors = o.optInt("errors"),
            level = o.optInt("level", 0),
        )
    }
    private val facets = summary.optJSONObject("facets")
    val facetAgents = facets?.arr("agents")?.strings().orEmpty().distinct()
    val facetProviders = facets?.arr("providers")?.objects().orEmpty().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        id to o.optString("name", id)
    }.distinctBy { it.first }
    val facetModels = facets?.arr("models")?.strings().orEmpty().distinct()
    val recent = summary.arr("recent").objects().map { o ->
        UsageRequestRow(
            ts = o.optLong("t"),
            agent = o.optString("agent"),
            providerId = o.optString("providerId"),
            providerName = o.optString("providerName", o.optString("providerId")),
            model = o.optString("model"),
            requestedModel = o.optString("requestedModel"),
            inputTokens = o.optLong("in"),
            outputTokens = o.optLong("out"),
            cacheReadTokens = o.optLong("cacheRead"),
            cacheWriteTokens = o.optLong("cacheWrite"),
            reasoningTokens = o.optLong("reasoning"),
            ms = o.optInt("ms"),
            ttftMs = o.optInt("ttftMs"),
            genMs = o.optInt("genMs"),
            streamed = o.optBoolean("streamed", false),
            exact = o.optBoolean("exact", true),
            tps = o.optDouble("tps", 0.0),
            e2eTps = o.optDouble("e2eTps", 0.0),
            status = o.optInt("status", 200),
            err = o.optString("err"),
            billingKind = o.optString("billingKind"),
            priceRate = o.optDouble("priceRate", 1.0),
            priceMatched = o.optBoolean("priceMatched", true),
            costUsd = o.optDouble("cost", 0.0),
            sessionId = o.optString("sessionId"),
            contextUsed = o.optLong("contextUsed"),
            contextWindow = o.optLong("contextWindow"),
            source = o.optString("source", "gateway"),
            rawJson = o,
        )
    }
}

private val emptyUsage = UsageViewData(JSONObject())

@Composable
fun UsageScreen(
    app: GaliaisApplication,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenConversation: ((ConversationKey) -> Unit)? = null,
) {
    val runtime by app.runtime.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    var period by rememberSaveable { mutableStateOf("7d") } // "today" | "24h" | "7d" | "30d" | "all"
    var mainTab by rememberSaveable { mutableStateOf("overview") } // "overview" | "requests" | "sessions" | "context"
    var breakdownTab by rememberSaveable { mutableStateOf("provider") } // "provider" | "agent" | "model"
    var chartMetric by rememberSaveable { mutableStateOf("tokens") } // "tokens" | "cost" | "requests"
    var filterAgent by remember { mutableStateOf<String?>(null) }
    var filterProviderId by remember { mutableStateOf<String?>(null) }
    var filterProviderLabel by remember { mutableStateOf<String?>(null) }
    var filterModel by remember { mutableStateOf<String?>(null) }
    var filterDay by remember { mutableStateOf<String?>(null) }
    var filterSessionId by remember { mutableStateOf<String?>(null) }
    var filterSessionLabel by remember { mutableStateOf<String?>(null) }
    var filterSource by remember { mutableStateOf<String?>(null) } // null | "gateway" | "direct"
    var failedOnly by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }

    var tick by remember { mutableIntStateOf(0) }
    var manualRefresh by remember { mutableIntStateOf(0) }
    val query = listOf(mainTab, period, filterAgent, filterProviderId, filterModel, filterDay,
        filterSessionId, filterSource, failedOnly, debouncedSearch, runtime.ready)
    var result by remember(query) { mutableStateOf<UsageViewData?>(null) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var loading by remember(query) { mutableStateOf(runtime.ready) }
    var codexLimits by remember { mutableStateOf<List<RateLimitSummary>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    var inspectRow by remember { mutableStateOf<UsageRequestRow?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(app, runtime.ready) {
        if (!runtime.ready) return@LaunchedEffect
        var previous: Pair<Int, Long>? = null
        app.conversations.conversations.map { states ->
            states.values.count { it.running } to states.values.sumOf {
                (it.tokenUsage?.totalTokens ?: 0L) + (it.tokenUsage?.sessionTotalTokens ?: 0L)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default).collectLatest { pulse ->
            val last = previous
            previous = pulse
            if (last != null && (pulse.first < last.first || pulse.second != last.second)) {
                delay(450)
                tick++
            }
        }
    }
    LaunchedEffect(searchInput) {
        delay(220)
        debouncedSearch = searchInput.trim()
    }

    val hasAnyFilter = filterAgent != null || filterProviderId != null || filterModel != null ||
        filterDay != null || filterSessionId != null || filterSource != null || failedOnly || debouncedSearch.isNotEmpty()

    LaunchedEffect(query, tick, manualRefresh) {
        if (!runtime.ready) return@LaunchedEffect
        loading = true
        error = null
        try {
            val response = app.conversations.hub.queryUsageSummary(
                view = mainTab, period = period, agent = filterAgent, providerId = filterProviderId,
                model = filterModel, day = filterDay, sessionId = filterSessionId,
                source = filterSource, failedOnly = failedOnly, search = debouncedSearch.ifBlank { null },
            )
            result = withContext(Dispatchers.Default) { UsageViewData(response) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.localizedMessage ?: "读取用量失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(runtime.ready, mainTab, manualRefresh) {
        if (!runtime.ready || mainTab != "overview") return@LaunchedEffect
        try { codexLimits = app.conversations.codex.rateLimits() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { codexLimits = emptyList() }
    }
    val data = result ?: emptyUsage
    val liveSessions = if (mainTab == "sessions" || mainTab == "context") {
        remember(app) {
            app.conversations.conversations.map { states ->
                states.mapValues { (_, state) ->
                    LiveUsageSession(state.summary, state.model, state.running, state.tokenUsage)
                }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyMap()).value
    } else emptyMap()

    fun clearFilters() {
        filterAgent = null; filterProviderId = null; filterProviderLabel = null
        filterModel = null; filterDay = null; filterSessionId = null; filterSessionLabel = null
        filterSource = null; failedOnly = false; searchInput = ""
    }

    SubPageScaffold(
        title = "用量中心",
        onBack = onBack,
        snackbar = snackbar,
        loading = loading,
        scrollable = false,
        actions = {
            if (runtime.ready) {
                IconButton(enabled = !loading, onClick = { manualRefresh++ }) {
                    Icon(GaliaisIcons.Refresh, contentDescription = "刷新用量", tint = if (!loading) G.inkMuted else G.inkSubtle, modifier = Modifier.size(20.dp))
                }
                if (data.totals.requests > 0) {
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(GaliaisIcons.MoreHoriz, contentDescription = "更多用量操作", tint = G.inkMuted, modifier = Modifier.size(20.dp))
                        }
                        GaliaisDropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                GaliaisMenuRow(title = "导出 CSV 报表", icon = GaliaisIcons.Download, onClick = {
                                    topMenuOpen = false
                                    scope.launch {
                                        runCatching {
                                            app.conversations.hub.exportUsageCsv(
                                                period = period,
                                                agent = filterAgent,
                                                providerId = filterProviderId,
                                                model = filterModel,
                                                day = filterDay,
                                                sessionId = filterSessionId,
                                                source = filterSource,
                                                failedOnly = failedOnly,
                                                search = debouncedSearch.ifBlank { null },
                                            )
                                        }.onSuccess { res ->
                                            val csv = res.optString("csv")
                                            val count = res.optInt("count")
                                            val fname = res.optString("filename", "solbraid-usage.csv").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                            if (csv.length <= 128_000) {
                                                clipboard.setClipEntry(ClipData.newPlainText(fname, csv).toClipEntry())
                                            }
                                            runCatching {
                                                val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                                val csvFile = java.io.File(exportDir, fname)
                                                csvFile.writeText(csv, Charsets.UTF_8)
                                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
                                                val share = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/csv"
                                                    putExtra(Intent.EXTRA_SUBJECT, fname)
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(Intent.createChooser(share, "导出用量 CSV（$count 条记录）"))
                                            }.onFailure { err ->
                                                app.conversations.notify("文件分享失败：${err.message}")
                                            }
                                            app.conversations.notify("已生成 CSV 文件（$count 条记录）")
                                        }.onFailure { app.conversations.notify("导出失败：${it.message}") }
                                    }
                                })
                                GaliaisMenuRow(title = "清空用量账本", tintOverride = G.danger, onClick = {
                                    topMenuOpen = false
                                    confirmClear = true
                                })
                            }
                        }
                    }
                }
            }
        },
    ) {
        SegmentedPillBar(
            options = listOf("overview" to "概览", "requests" to "请求", "sessions" to "会话", "context" to "上下文"),
            selected = mainTab,
            onSelect = { mainTab = it },
        )

        val spacing = D.space(10.dp)
        androidx.compose.runtime.key(mainTab) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (mainTab != "context") {
                    item(key = "period") { LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(listOf("today" to "今天", "24h" to "24h", "7d" to "7天", "30d" to "30天", "all" to "全部"), key = { it.first }) { (key, label) ->
                            SelectableChip(text = label, selected = period == key, onClick = { period = key; filterDay = null })
                        }
                    } }
                }

                if (hasAnyFilter && mainTab != "context") {
                    item(key = "filters") { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filterDay?.let { d -> TagPill("日期 $d ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterDay = null }) }
                        filterAgent?.let { a -> TagPill("Agent: ${displayAgentName(a)} ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterAgent = null }) }
                        filterProviderId?.let { TagPill("服务商: ${filterProviderLabel ?: it} ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterProviderId = null; filterProviderLabel = null }) }
                        filterModel?.let { m -> TagPill("模型: $m ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterModel = null }) }
                        filterSessionId?.let { TagPill("会话: ${filterSessionLabel ?: it.take(8)} ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterSessionId = null; filterSessionLabel = null }) }
                        filterSource?.let { s -> TagPill("来源: ${if (s == "direct") "官方直连" else "本地网关"} ✕", fg = G.accent, bg = G.accentContainer, onClick = { filterSource = null }) }
                        if (failedOnly) TagPill("仅看异常 ✕", fg = G.danger, bg = G.dangerTint, onClick = { failedOnly = false })
                        TagPill("清除全部筛选", fg = G.inkMuted, bg = G.surfaceSubtle, border = G.border, onClick = ::clearFilters)
                    } }
                }

                if (!runtime.ready && mainTab != "context") {
                    item { CrispCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "启动本地运行环境后即可查看实时账本、精确吞吐率、会话分布与上下文历史。",
                                style = MaterialTheme.typography.bodySmall,
                                color = G.inkMuted,
                                modifier = Modifier.weight(1f),
                            )
                            if (!runtime.busy && runtime.bound) {
                                TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                            }
                        }
                    } }
                }
                error?.let { reason ->
                    item(key = "usage-error") {
                        CrispCard {
                            Text("用量读取失败 · $reason", style = MaterialTheme.typography.bodySmall, color = G.danger)
                            CompactActionButton("重试", onClick = { tick++ })
                        }
                    }
                }
                if ((runtime.ready || mainTab == "context") &&
                    (result != null || mainTab == "requests" || mainTab == "context")) {
                    when (mainTab) {
                        "overview" -> usageOverview(
                            totals = data.totals,
                            codexLimits = codexLimits,
                            heatmap = data.heatmap,
                            daily = data.daily,
                            filterDay = filterDay,
                            chartMetric = chartMetric,
                            onChartMetricChange = { chartMetric = it },
                            onSelectDay = { day ->
                                filterDay = if (filterDay == day) null else day
                                if (filterDay != null) { period = "all"; mainTab = "requests" }
                            },
                            breakdownTab = breakdownTab,
                            onBreakdownTabChange = { breakdownTab = it },
                            byProvider = data.byProvider,
                            byAgent = data.byAgent,
                            byModel = data.byModel,
                            onDrillProvider = { row ->
                                filterProviderId = row.id; filterProviderLabel = row.name; mainTab = "requests"
                            },
                            onDrillAgent = { row ->
                                filterAgent = row.id; mainTab = "requests"
                            },
                            onDrillModel = { row ->
                                filterModel = row.id; mainTab = "requests"
                            },
                        )
                        "requests" -> usageRequests(
                            rows = data.recent,
                            totalMatched = data.totalMatched,
                            search = searchInput,
                            onSearchChange = { searchInput = it },
                            failedOnly = failedOnly,
                            onToggleFailed = { failedOnly = !failedOnly },
                            selectedSource = filterSource,
                            onSelectSource = { src -> filterSource = if (filterSource == src) null else src },
                            facetAgents = data.facetAgents,
                            selectedAgent = filterAgent,
                            onSelectAgent = { filterAgent = if (filterAgent == it) null else it },
                            facetProviders = data.facetProviders,
                            selectedProviderId = filterProviderId,
                            onSelectProvider = { id, label ->
                                if (filterProviderId == id) { filterProviderId = null; filterProviderLabel = null }
                                else { filterProviderId = id; filterProviderLabel = label }
                            },
                            facetModels = data.facetModels,
                            selectedModel = filterModel,
                            onSelectModel = { filterModel = if (filterModel == it) null else it },
                            onInspect = { inspectRow = it },
                            showEmpty = result != null,
                        )
                        "sessions" -> usageSessions(
                            sessions = data.bySession,
                            liveById = liveSessions.values.associateBy { it.summary.key.id },
                            onDrillSession = { sid, label ->
                                filterSessionId = sid; filterSessionLabel = label; mainTab = "requests"
                            },
                            onOpenConversation = onOpenConversation,
                        )
                        else -> usageContext(
                            conversations = liveSessions,
                            ledgerSessions = data.bySession,
                            totals = data.totals,
                            runtimeReady = runtime.ready,
                            onCompact = { key -> app.conversations.compact(key) },
                            onOpenConversation = onOpenConversation,
                        )
                    }
                }
            }
        }
    }
    inspectRow?.let { row ->
        UsageRequestDetailSheet(row = row, onDismiss = { inspectRow = null }, onCopyJson = {
            scope.launch {
                val json = withContext(Dispatchers.Default) { row.rawJson.toString(2) }
                clipboard.setClipEntry(ClipData.newPlainText("usage-record", json).toClipEntry())
                app.conversations.notify("已复制单条请求 JSON 详情")
            }
        })
    }

    if (confirmClear) {
        val canClearFiltered = hasAnyFilter || period != "all"
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = G.surface,
            shape = D.dialogShape,
            title = { Text("清空本地用量账本？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = G.danger) },
            text = {
                Text(
                    if (canClearFiltered) "可选择仅清除当前筛选/周期条件命中的记录，或清空全部历史账本，此操作不可撤销。"
                    else "将清除本地保存的全部历史 Token、耗时与费用记录，此操作不可撤销。",
                    color = G.inkMuted,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canClearFiltered) {
                        TextButton(onClick = {
                            confirmClear = false
                            scope.launch {
                                runCatching {
                                    app.conversations.hub.clearUsageLedger(
                                        agent = filterAgent,
                                        providerId = filterProviderId,
                                        model = filterModel,
                                        day = filterDay,
                                        sessionId = filterSessionId,
                                        source = filterSource,
                                        period = period,
                                        failedOnly = failedOnly,
                                        search = debouncedSearch.ifBlank { null },
                                    )
                                }.onSuccess { res -> clearFilters(); tick++; app.conversations.notify("已清除 ${res.optInt("removed")} 条筛选记录") }
                                    .onFailure { app.conversations.notify("清除失败：${it.message}") }
                            }
                        }) { Text("仅清当前筛选", color = G.warning, fontWeight = FontWeight.SemiBold) }
                    }
                    TextButton(onClick = {
                        confirmClear = false
                        scope.launch {
                            runCatching { app.conversations.hub.clearUsageLedger() }
                                .onSuccess { clearFilters(); tick++; app.conversations.notify("已清空全部本地用量账本") }
                                .onFailure { app.conversations.notify("清空失败：${it.message}") }
                        }
                    }) { Text("清空全部", color = G.danger, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = G.inkMuted) } },
        )
    }
}

private fun LazyListScope.usageOverview(
    totals: UsageTotals,
    codexLimits: List<RateLimitSummary>,
    heatmap: List<UsageHeatmapCell>,
    daily: List<UsageGroupRow>,
    filterDay: String?,
    chartMetric: String,
    onChartMetricChange: (String) -> Unit,
    onSelectDay: (String) -> Unit,
    breakdownTab: String,
    onBreakdownTabChange: (String) -> Unit,
    byProvider: List<UsageGroupRow>,
    byAgent: List<UsageGroupRow>,
    byModel: List<UsageGroupRow>,
    onDrillProvider: (UsageGroupRow) -> Unit,
    onDrillAgent: (UsageGroupRow) -> Unit,
    onDrillModel: (UsageGroupRow) -> Unit,
) {
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageKpiCard(
                title = "总 Token",
                value = fmtCompactTokens(totals.totalTokens),
                subtitle = "入 ${fmtCompactTokens(totals.inputTokens)} · 出 ${fmtCompactTokens(totals.outputTokens)}",
                modifier = Modifier.weight(1f),
            )
            UsageKpiCard(
                title = "预估费用",
                value = fmtUsd(totals.costUsd),
                subtitle = "网关 ${totals.gatewayCount} · 直连 ${totals.directCount}" +
                    if (totals.estimatedCount > 0) " · 估 ${totals.estimatedCount}" else "",
                modifier = Modifier.weight(1f),
            )
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageKpiCard(
                title = "缓存命中率",
                value = if (totals.inputTokens > 0L) fmtRatePct(totals.cacheHitRate) else "—",
                subtitle = "读 ${fmtCompactTokens(totals.cacheReadTokens)} · 写 ${fmtCompactTokens(totals.cacheWriteTokens)}",
                modifier = Modifier.weight(1f),
            )
            UsageKpiCard(
                title = "请求 / 成功率",
                value = "${totals.requests} · ${fmtRatePct(totals.successRate)}",
                subtitle = buildString {
                    append("成功 ${totals.succeeded}")
                    if (totals.errors > 0) append(" · 错 ${totals.errors}")
                    if (totals.cancelled > 0) append(" · 停 ${totals.cancelled}")
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    item {
        CrispCard(padding = PaddingValues(12.dp)) {
            val perfSummary = buildString {
                append("吞吐与延迟详情")
                if (totals.avgTps > 0.0) append(" · ${String.format(Locale.US, "%.1f", totals.avgTps)} tok/s")
                if (totals.avgTtftMs > 0) append(" · TTFT ${fmtDurationMs(totals.avgTtftMs)}")
            }
            Collapsible(title = perfSummary, initiallyOpen = false) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UsageKpiCard(
                            title = "纯解码吞吐",
                            value = if (totals.avgTps > 0.0) "${String.format(Locale.US, "%.1f", totals.avgTps)} tok/s" else "—",
                            subtitle = if (totals.tpsSampleCount > 0)
                                "中位 ${String.format(Locale.US, "%.1f", totals.medianTps)} · 峰 ${String.format(Locale.US, "%.1f", totals.peakTps)}"
                            else "需流式生成 ≥100ms",
                            modifier = Modifier.weight(1f),
                        )
                        UsageKpiCard(
                            title = "端到端速率",
                            value = if (totals.e2eAvgTps > 0.0) "${String.format(Locale.US, "%.1f", totals.e2eAvgTps)} tok/s" else "—",
                            subtitle = "总输出 / API 耗时（含首字）",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UsageKpiCard(
                            title = "首字延迟 TTFT",
                            value = fmtDurationMs(totals.avgTtftMs),
                            subtitle = if (totals.ttftSampleCount > 0)
                                "P50 ${fmtDurationMs(totals.p50TtftMs)} · P95 ${fmtDurationMs(totals.p95TtftMs)}"
                            else "暂无流式首字样本",
                            modifier = Modifier.weight(1f),
                        )
                        UsageKpiCard(
                            title = "API 响应延迟",
                            value = fmtDurationMs(totals.avgLatencyMs),
                            subtitle = if (totals.p50LatencyMs > 0)
                                "P50 ${fmtDurationMs(totals.p50LatencyMs)} · P95 ${fmtDurationMs(totals.p95LatencyMs)}"
                            else "暂无延迟样本",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (totals.totalTokens > 0L) {
        item {
            CrispCard(padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Token 精确构成", style = MaterialTheme.typography.labelMedium, color = G.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${fmtExactTokens(totals.totalTokens)} tokens", fontFamily = Mono, fontSize = 11.5.sp, color = G.accent, fontWeight = FontWeight.SemiBold)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagPill("未缓存入 ${fmtExactTokens(totals.uncachedInputTokens)}", fg = G.ink, bg = G.surfaceSubtle)
                        TagPill("缓存读 ${fmtExactTokens(totals.cacheReadTokens)}", fg = G.success, bg = G.successTint)
                        if (totals.cacheWriteTokens > 0L) TagPill("缓存写 ${fmtExactTokens(totals.cacheWriteTokens)}", fg = G.warning, bg = G.warningTint)
                        TagPill("总输出 ${fmtExactTokens(totals.outputTokens)}", fg = G.accent, bg = G.accentContainer)
                        if (totals.reasoningTokens > 0L) TagPill("含思维链 ${fmtExactTokens(totals.reasoningTokens)}", fg = G.claude, bg = G.claudeTint)
                        if (totals.uniqueSessions > 0) TagPill("覆盖 ${totals.uniqueSessions} 会话", fg = G.inkMuted, bg = G.surfaceSubtle)
                    }
                }
            }
        }
    }
    if (codexLimits.isNotEmpty()) {
        item {
            SectionLabel("Codex 官方订阅配额")
            CrispCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    codexLimits.forEach { rl ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rl.label, style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                                Text("${rl.usedPercent}%" + (rl.resetsAt?.let { " · 重置 $it" } ?: ""), fontFamily = Mono, fontSize = 11.5.sp, color = if (rl.usedPercent >= 85) G.danger else G.inkMuted)
                            }
                            LinearProgressIndicator(
                                progress = { (rl.usedPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                                color = if (rl.usedPercent >= 85) G.danger else if (rl.usedPercent >= 65) G.warning else G.codex,
                                trackColor = G.surfaceSubtle,
                            )
                        }
                    }
                }
            }
        }
    }
    if (heatmap.isNotEmpty()) {
        item {
            UsageHeatmapCard(cells = heatmap, selectedDay = filterDay, onSelectDay = onSelectDay)
        }
    }
    if (daily.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("每日趋势（近 ${daily.size} 天）")
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SelectableChip("Token", selected = chartMetric == "tokens", onClick = { onChartMetricChange("tokens") })
                    SelectableChip("费用", selected = chartMetric == "cost", onClick = { onChartMetricChange("cost") })
                    SelectableChip("请求", selected = chartMetric == "requests", onClick = { onChartMetricChange("requests") })
                }
            }
            CrispCard {
                val maxVal = when (chartMetric) {
                    "cost" -> daily.maxOf { it.costUsd }.coerceAtLeast(0.0001)
                    "requests" -> daily.maxOf { it.requests.toDouble() }.coerceAtLeast(1.0)
                    else -> daily.maxOf { it.totalTokens.toDouble() }.coerceAtLeast(1.0)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    daily.takeLast(12).reversed().forEach { d ->
                        val curVal = when (chartMetric) {
                            "cost" -> d.costUsd
                            "requests" -> d.requests.toDouble()
                            else -> d.totalTokens.toDouble()
                        }
                        val frac = (curVal / maxVal).toFloat().coerceIn(0.03f, 1f)
                        val activeDay = filterDay == d.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(D.shape(6.dp))
                                .background(if (activeDay) G.accentContainer.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onSelectDay(d.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                d.id.takeLast(5),
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = if (activeDay) G.accent else G.inkMuted,
                                fontWeight = if (activeDay) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.width(40.dp),
                            )
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(99.dp)).background(G.surfaceSubtle)) {
                                val barColor = when (chartMetric) { "cost" -> G.claude; "requests" -> G.codex; else -> G.accent }
                                Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(99.dp)).background(barColor))
                            }
                            val rightText = when (chartMetric) {
                                "requests" -> "${d.requests} 次" + if (d.errors > 0) " (${d.errors}错)" else ""
                                else -> "${fmtCompactTokens(d.totalTokens)} · ${fmtUsd(d.costUsd)}"
                            }
                            Text(
                                rightText,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = G.ink,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(min = 84.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    item {
        SegmentedPillBar(
            options = listOf("provider" to "按服务商", "agent" to "按 Agent", "model" to "按模型"),
            selected = breakdownTab,
            onSelect = onBreakdownTabChange,
        )
    }
    item {
        when (breakdownTab) {
            "agent" -> UsageBreakdownCard("按 Agent 统计（点击下钻请求）", byAgent, onDrillAgent)
            "model" -> UsageBreakdownCard("按模型统计（点击下钻请求）", byModel, onDrillModel)
            else -> UsageBreakdownCard("按服务商统计（点击下钻请求）", byProvider, onDrillProvider)
        }
    }
}
@Composable
private fun UsageKpiCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    CrispCard(modifier = modifier, padding = PaddingValues(11.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontFamily = Mono, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UsageHeatmapCard(
    cells: List<UsageHeatmapCell>,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
) {
    val activeDays = remember(cells) { cells.count { it.requests > 0 } }
    val weeks = remember(cells) { cells.chunked(7) }
    var inspectedCell by remember(cells, selectedDay) {
        mutableStateOf(cells.firstOrNull { it.day == selectedDay })
    }
    val rowWeekdays = remember(cells) {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val labels = arrayOf("日", "一", "二", "三", "四", "五", "六")
        (0 until 7).map { idx ->
            val dayStr = cells.getOrNull(idx)?.day
            if (dayStr.isNullOrBlank()) ""
            else runCatching {
                cal.time = df.parse(dayStr)!!
                labels[(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
            }.getOrDefault("")
        }
    }
    SectionLabel("近 12 周活跃热力图（$activeDays 天有请求 · 点击预览单日）")
    CrispCard(padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val colCount = weeks.size.coerceAtLeast(1)
                val axisW = 16.dp
                val axisGap = 6.dp
                val cellGap = 4.dp
                val gridAvailW = (maxWidth - axisW - axisGap).coerceAtLeast(120.dp)
                val rawCellSize = (gridAvailW - cellGap * (colCount - 1)) / colCount
                val useFixedSpread = rawCellSize > 26.dp
                val cellSize = rawCellSize.coerceIn(10.dp, 26.dp)
                val cellRadius = if (cellSize >= 20.dp) 5.dp else 4.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(axisGap),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.width(axisW),
                        verticalArrangement = Arrangement.spacedBy(cellGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        for (r in 0 until 7) {
                            Box(modifier = Modifier.height(cellSize), contentAlignment = Alignment.Center) {
                                if (r == 0 || r == 2 || r == 4 || r == 6) {
                                    Text(
                                        text = rowWeekdays.getOrElse(r) { "" },
                                        fontSize = 9.5.sp,
                                        color = G.inkSubtle,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = if (useFixedSpread) Arrangement.SpaceBetween else Arrangement.spacedBy(cellGap),
                    ) {
                        weeks.forEach { week ->
                            val colMod = if (useFixedSpread) Modifier.width(cellSize) else Modifier.weight(1f)
                            Column(modifier = colMod, verticalArrangement = Arrangement.spacedBy(cellGap)) {
                                for (r in 0 until 7) {
                                    val cell = week.getOrNull(r)
                                    if (cell == null) {
                                        Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                                    } else {
                                        val isSelected = selectedDay == cell.day || inspectedCell?.day == cell.day
                                        val bg = when (cell.level) {
                                            0 -> G.surfaceSubtle
                                            1 -> G.accent.copy(alpha = 0.22f)
                                            2 -> G.accent.copy(alpha = 0.45f)
                                            3 -> G.accent.copy(alpha = 0.72f)
                                            else -> G.accent
                                        }
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(cellRadius))
                                                .clickable {
                                                    inspectedCell = if (inspectedCell?.day == cell.day) null else cell
                                                },
                                            shape = RoundedCornerShape(cellRadius),
                                            color = bg,
                                            border = if (isSelected) BorderStroke(1.5.dp, G.ink) else if (cell.errors > 0 && cell.requests > 0) BorderStroke(0.75.dp, G.danger.copy(alpha = 0.6f)) else null,
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            inspectedCell?.let { cell ->
                Surface(Modifier.fillMaxWidth(), shape = D.shape(9.dp), color = G.surfaceSubtle) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${cell.day} · ${cell.requests} 次 · ${fmtCompactTokens(cell.tokens)} · ${fmtUsd(cell.costUsd)}",
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            color = G.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (cell.requests > 0) {
                            TagPill("查看请求 ▸", fg = G.accent, bg = G.accentContainer, onClick = { onSelectDay(cell.day) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(cells.firstOrNull()?.day?.takeLast(5).orEmpty(), fontFamily = Mono, fontSize = 10.sp, color = G.inkSubtle)
                Spacer(Modifier.weight(1f))
                Text("少", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                listOf(0, 1, 2, 3, 4).forEach { lv ->
                    val c = when (lv) {
                        0 -> G.surfaceSubtle
                        1 -> G.accent.copy(alpha = 0.22f)
                        2 -> G.accent.copy(alpha = 0.45f)
                        3 -> G.accent.copy(alpha = 0.72f)
                        else -> G.accent
                    }
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                Spacer(Modifier.weight(1f))
                Text(cells.lastOrNull()?.day?.takeLast(5).orEmpty(), fontFamily = Mono, fontSize = 10.sp, color = G.inkSubtle)
            }
        }
    }
}

@Composable
private fun UsageBreakdownCard(title: String, rows: List<UsageGroupRow>, onPick: (UsageGroupRow) -> Unit) {
    SectionLabel("$title（${rows.size}）")
    if (rows.isEmpty()) {
        CrispCard { Text("当前周期暂无统计数据。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        return
    }
    val maxTok = rows.maxOf { it.totalTokens }.coerceAtLeast(1L)
    CrispCard(padding = PaddingValues(0.dp)) {
        rows.forEachIndexed { idx, row ->
            Column(
                Modifier.fillMaxWidth().clickable { onPick(row) }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(fmtUsd(row.costUsd), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.accent)
                }
                LinearProgressIndicator(
                    progress = { (row.totalTokens.toFloat() / maxTok.toFloat()).coerceIn(0.02f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                    color = G.accent,
                    trackColor = G.surfaceSubtle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        buildString {
                            append("${row.requests} 次")
                            if (row.errors > 0) append(" · ${row.errors} 错")
                            if (row.avgTps > 0.0) append(" · ${String.format(Locale.US, "%.1f", row.avgTps)}t/s")
                            if (row.avgLatencyMs > 0) append(" · ${fmtDurationMs(row.avgLatencyMs)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.errors > 0) G.danger else G.inkMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "入 ${fmtCompactTokens(row.inputTokens)} · 出 ${fmtCompactTokens(row.outputTokens)}" +
                            if (row.cacheHitRate > 0.0) " · 缓 ${fmtRatePct(row.cacheHitRate)}" else "",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        color = G.inkMuted,
                    )
                }
            }
            if (idx < rows.lastIndex) Hairline()
        }
    }
}

private fun LazyListScope.usageRequests(
    rows: List<UsageRequestRow>,
    totalMatched: Int,
    search: String,
    onSearchChange: (String) -> Unit,
    failedOnly: Boolean,
    onToggleFailed: () -> Unit,
    selectedSource: String?,
    onSelectSource: (String) -> Unit,
    facetAgents: List<String>,
    selectedAgent: String?,
    onSelectAgent: (String) -> Unit,
    facetProviders: List<Pair<String, String>>,
    selectedProviderId: String?,
    onSelectProvider: (String, String) -> Unit,
    facetModels: List<String>,
    selectedModel: String?,
    onSelectModel: (String) -> Unit,
    showEmpty: Boolean,
    onInspect: (UsageRequestRow) -> Unit,
) {
    item(key = "request-filters") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchBar(
                value = search,
                onValueChange = onSearchChange,
                placeholder = "搜索模型、服务商、会话 ID 或错误信息…",
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { SelectableChip("仅看异常", selected = failedOnly, onClick = onToggleFailed) }
                item { SelectableChip("本地网关", selected = selectedSource == "gateway", onClick = { onSelectSource("gateway") }) }
                item { SelectableChip("官方直连", selected = selectedSource == "direct", onClick = { onSelectSource("direct") }) }
                items(facetAgents, key = { "ag:$it" }) { a ->
                    SelectableChip(displayAgentName(a), selected = selectedAgent == a, onClick = { onSelectAgent(a) })
                }
                items(facetProviders, key = { "pr:${it.first}" }) { (pid, pname) ->
                    SelectableChip(pname, selected = selectedProviderId == pid, onClick = { onSelectProvider(pid, pname) })
                }
                items(facetModels.take(10), key = { "md:$it" }) { m ->
                    SelectableChip(m, selected = selectedModel == m, onClick = { onSelectModel(m) })
                }
            }
            SectionLabel("请求明细 · 显示 ${rows.size} / $totalMatched 条")
        }
    }
    if (rows.isEmpty() && showEmpty) {
        item { CrispCard { Text("没有匹配的请求", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) } }
    }
    items(rows, contentType = { "request" }) { row ->
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
        CrispCard(padding = PaddingValues(0.dp)) {
            val failed = (row.status >= 400 || row.err.isNotBlank()) && row.status != 499
            val cancelled = row.status == 499
            val modelTitle = if (row.requestedModel.isNotBlank() && row.requestedModel != row.model) {
                "${row.requestedModel} → ${row.model}"
            } else row.model.ifBlank { "default" }
            Column(
                Modifier.fillMaxWidth().clickable { onInspect(row) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(modelTitle, fontFamily = Mono, fontSize = 12.sp, color = G.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!row.exact) TagPill("估算", fg = G.warning, bg = G.warningTint)
                    TagPill(displayAgentName(row.agent), fg = G.accent, bg = G.accentContainer)
                    TagPill(
                        text = if (cancelled) "中断" else "${row.status}",
                        fg = if (failed) G.danger else if (cancelled) G.warning else G.success,
                        bg = if (failed) G.dangerTint else if (cancelled) G.warningTint else G.successTint,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val srcLabel = if (row.source == "direct") "直连" else "网关"
                    val billLabel = when (row.billingKind) { "free" -> " · 免费"; "plan" -> " · 订阅"; else -> "" }
                    Text("${row.providerName} · $srcLabel$billLabel", style = MaterialTheme.typography.labelSmall, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        buildString {
                            append("入 ${fmtCompactTokens(row.inputTokens)} · 出 ${fmtCompactTokens(row.outputTokens)}")
                            if (row.cacheReadTokens > 0L) append(" · 缓 ${fmtRatePct(row.cacheHitRate)}")
                            if (row.reasoningTokens > 0L) append(" · 思 ${fmtCompactTokens(row.reasoningTokens)}")
                        },
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        color = G.inkSubtle,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.format(Date(row.ts)), fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
                    if (row.ttftMs > 0) Text("TTFT ${fmtDurationMs(row.ttftMs)}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
                    if (row.ms > 0) Text(fmtDurationMs(row.ms), fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
                    if (row.tps > 0.0) {
                        Text("${String.format(Locale.US, "%.1f", row.tps)}t/s", fontFamily = Mono, fontSize = 10.5.sp, color = G.codex)
                    } else if (row.e2eTps > 0.0) {
                        Text("总 ${String.format(Locale.US, "%.1f", row.e2eTps)}t/s", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(fmtUsd(row.costUsd), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
                }
                if (row.err.isNotBlank()) {
                    Text(row.err, style = MaterialTheme.typography.labelSmall, color = G.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
private fun LazyListScope.usageSessions(
    sessions: List<UsageSessionRow>,
    liveById: Map<String, LiveUsageSession>,
    onDrillSession: (String, String) -> Unit,
    onOpenConversation: ((ConversationKey) -> Unit)?,
) {
    item { SectionLabel("会话用量 · ${sessions.size} 个会话") }
    if (sessions.isEmpty()) {
        item { CrispCard { Text("所选时间范围内暂无会话用量", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) } }
    }
    items(sessions, key = { it.key }, contentType = { "session" }) { s ->
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        val live = s.id.takeIf { it.isNotBlank() }?.let { liveById[it] }
        val title = live?.summary?.title?.takeIf { it.isNotBlank() }
            ?: if (s.id.isNotBlank()) "会话 ${s.id.take(10)}" else "未绑定会话请求"
        CrispCard(padding = PaddingValues(13.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TagPill(displayAgentName(s.agent), fg = G.accent, bg = G.accentContainer)
                    Text(fmtUsd(s.costUsd), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.accent)
                }
                Text(
                    buildString {
                        append(s.providerName)
                        if (s.models.isNotEmpty()) append(" · ${s.models.take(2).joinToString(" / ")}")
                        live?.summary?.cwd?.let { append(" · ${projectDisplayName(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = G.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metricLine1 = buildString {
                    append("${s.requests} 次")
                    if (s.errors > 0) append(" (${s.errors}错)")
                    append(" · 总 ${fmtCompactTokens(s.totalTokens)}")
                    append("（入 ${fmtCompactTokens(s.inputTokens)} / 出 ${fmtCompactTokens(s.outputTokens)}）")
                }
                val metricLine2 = buildList {
                    if (s.cacheReadTokens > 0L) add("缓存 ${fmtRatePct(s.cacheHitRate)}")
                    if (s.reasoningTokens > 0L) add("思维链 ${fmtCompactTokens(s.reasoningTokens)}")
                    if (s.avgTps > 0.0) add("${String.format(Locale.US, "%.1f", s.avgTps)} tok/s")
                    if (s.maxContextUsed > 0L) {
                        val peak = if (s.contextWindow > 0L) {
                            val pct = ((s.maxContextUsed * 100.0) / s.contextWindow).coerceIn(0.0, 100.0)
                            "峰值 ${fmtCompactTokens(s.maxContextUsed)} (${fmtRatePct(pct)})"
                        } else "峰值 ${fmtCompactTokens(s.maxContextUsed)}"
                        add(peak)
                    }
                }.joinToString(" · ")
                Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(metricLine1, fontFamily = Mono, fontSize = 11.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (metricLine2.isNotBlank()) {
                            Text(metricLine2, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "最近 ${fmt.format(Date(s.lastAt))}" + if (s.avgLatencyMs > 0) " · 均耗 ${fmtDurationMs(s.avgLatencyMs)}" else "",
                        fontFamily = Mono,
                        fontSize = 10.5.sp,
                        color = G.inkSubtle,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (s.id.isNotBlank()) {
                        CompactActionButton("筛选请求", onClick = { onDrillSession(s.id, title) })
                    }
                    if (live != null && onOpenConversation != null) {
                        CompactActionButton("打开会话", primary = true, onClick = { onOpenConversation(live.summary.key) })
                    }
                }
            }
        }
    }
}
private fun LazyListScope.usageContext(
    conversations: Map<ConversationKey, LiveUsageSession>,
    ledgerSessions: List<UsageSessionRow>,
    totals: UsageTotals,
    runtimeReady: Boolean,
    onCompact: (ConversationKey) -> Unit,
    onOpenConversation: ((ConversationKey) -> Unit)?,
) {
    val activeWithUsage = conversations.values
            .filter { it.tokenUsage != null }
            .sortedByDescending { it.tokenUsage?.ratio() ?: (it.tokenUsage?.totalTokens?.toFloat()?.div(1_000_000f) ?: 0f) }
    val highPressureCount = activeWithUsage.count { (it.tokenUsage?.ratio() ?: 0f) >= 0.65f }
    val criticalCount = activeWithUsage.count { (it.tokenUsage?.ratio() ?: 0f) >= 0.85f }
    val avgUtilPct = run {
        val ratios = activeWithUsage.mapNotNull { it.tokenUsage?.ratio() }
        if (ratios.isEmpty()) 0.0 else (ratios.average() * 100.0)
    }

    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageKpiCard(
                title = "活跃监控会话",
                value = "${activeWithUsage.size} 个",
                subtitle = if (activeWithUsage.isNotEmpty()) "平均占用 ${fmtRatePct(avgUtilPct)}" else "打开会话后实时采集",
                modifier = Modifier.weight(1f),
            )
            UsageKpiCard(
                title = "高水位预警",
                value = "$highPressureCount 个",
                subtitle = if (criticalCount > 0) "达压缩线 (≥85%) $criticalCount 个" else "≥65% 建议适时压缩",
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (totals.peakContextUsed > 0L) {
        item { CrispCard(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("账本单次峰值上下文", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                    Text(
                        fmtExactTokens(totals.peakContextUsed) + " tokens" +
                            if (totals.peakContextWindow > 0L) " / ${fmtCompactTokens(totals.peakContextWindow)}" else "",
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = G.ink,
                    )
                }
                if (totals.peakContextWindow > 0L) {
                    val peakPct = ((totals.peakContextUsed * 100.0) / totals.peakContextWindow).coerceIn(0.0, 100.0)
                    TagPill(
                        fmtRatePct(peakPct),
                        fg = if (peakPct >= 85.0) G.danger else if (peakPct >= 65.0) G.warning else G.accent,
                        bg = if (peakPct >= 85.0) G.dangerTint else if (peakPct >= 65.0) G.warningTint else G.accentContainer,
                    )
                }
            }
        } }
    }
    item { SectionLabel("实时会话上下文 · ${activeWithUsage.size}") }
    if (activeWithUsage.isEmpty()) {
        item { CrispCard {
            Text(
                "暂无上下文用量。打开会话或发送消息后可查看。",
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
        } }
    }
    items(activeWithUsage, key = { "live-${it.summary.key.route}" }, contentType = { "live-context" }) { st ->
        val usage = st.tokenUsage ?: return@items
        val win = usage.contextWindow?.takeIf { it > 0L }
        val ratio = usage.ratio()
        val pct = ratio?.let { it * 100.0 }
        val barColor = when {
            ratio == null -> G.accent
            ratio >= 0.85f -> G.danger
            ratio >= 0.65f -> G.warning
            else -> G.success
        }
        val barTint = when {
            ratio == null -> G.accentContainer
            ratio >= 0.85f -> G.dangerTint
            ratio >= 0.65f -> G.warningTint
            else -> G.successTint
        }
        val remaining = win?.let { (it - usage.totalTokens).coerceAtLeast(0L) }
        val compactThreshold = win?.let { (it * 85L) / 100L }
        val cacheHit = usage.cacheHitPercent()

        CrispCard(padding = PaddingValues(13.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(st.summary.title, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TagPill(st.summary.key.engine.label, fg = agentAccent(st.summary.key.engine), bg = agentTint(st.summary.key.engine))
                    TagPill(
                        text = pct?.let(::fmtRatePct) ?: fmtCompactTokens(usage.totalTokens),
                        fg = barColor,
                        bg = barTint,
                    )
                }
                if (win != null && ratio != null) {
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                        color = barColor,
                        trackColor = G.surfaceSubtle,
                    )
                }
                Text(
                    buildString {
                        append("当前窗口 ${fmtExactTokens(usage.totalTokens)}")
                        if (win != null) append(" / ${fmtExactTokens(win)}")
                        if (remaining != null) append(" · 剩余 ${fmtCompactTokens(remaining)}")
                        st.model?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    },
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = G.inkMuted,
                )
                val ctxLine1 = buildList {
                    add("未缓存入 ${fmtCompactTokens(usage.uncachedInputTokens)}")
                    if (usage.cachedInputTokens > 0L) {
                        add("缓存读 ${fmtCompactTokens(usage.cachedInputTokens)}" + (cacheHit?.let { " (${fmtRatePct(it)})" } ?: ""))
                    }
                    if (usage.cacheWriteTokens > 0L) add("缓写 ${fmtCompactTokens(usage.cacheWriteTokens)}")
                    add("输出 ${fmtCompactTokens(usage.outputTokens)}")
                }.joinToString(" · ")
                val ctxLine2 = buildList {
                    if (usage.reasoningTokens > 0L) add("思维链 ${fmtCompactTokens(usage.reasoningTokens)}")
                    if (compactThreshold != null) add("压缩线 ${fmtCompactTokens(compactThreshold)}")
                    usage.sessionTotalTokens?.takeIf { it > usage.totalTokens }?.let { cum ->
                        add("累计 ${fmtCompactTokens(cum)}")
                    }
                }.joinToString(" · ")
                Surface(Modifier.fillMaxWidth(), shape = D.shape(8.dp), color = G.surfaceSubtle) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ctxLine1, fontFamily = Mono, fontSize = 10.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ctxLine2.isNotBlank()) {
                            Text(ctxLine2, fontFamily = Mono, fontSize = 10.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    if (runtimeReady && !st.running) {
                        CompactActionButton("压缩上下文", onClick = { onCompact(st.summary.key) })
                    }
                    if (onOpenConversation != null) {
                        CompactActionButton("打开会话", primary = true, onClick = { onOpenConversation(st.summary.key) })
                    }
                }
            }
        }
    }
    val activeIds = activeWithUsage.map { it.summary.key.id }.toSet()
    val historicalCtx = ledgerSessions.filter { it.maxContextUsed > 0L && it.id.isNotBlank() && it.id !in activeIds }.sortedByDescending { it.maxContextUsed }.take(15)
    if (historicalCtx.isNotEmpty()) {
        item { SectionLabel("历史会话峰值上下文（账本记录 · ${historicalCtx.size}）") }
        item { CrispCard(padding = PaddingValues(0.dp)) {
            historicalCtx.forEachIndexed { idx, s ->
                val pct = if (s.contextWindow > 0L) ((s.maxContextUsed * 100.0) / s.contextWindow).coerceIn(0.0, 100.0) else null
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${displayAgentName(s.agent)} · ${s.id.take(10)}",
                            fontFamily = Mono,
                            fontSize = 11.5.sp,
                            color = G.ink,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${s.models.firstOrNull() ?: s.providerName} · ${s.requests} 次请求 · 累计 ${fmtCompactTokens(s.totalTokens)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = G.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtCompactTokens(s.maxContextUsed), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
                        if (pct != null) {
                            Text("${fmtRatePct(pct)} / ${fmtCompactTokens(s.contextWindow)}", fontFamily = Mono, fontSize = 10.sp, color = if (pct >= 85.0) G.danger else G.inkSubtle)
                        }
                    }
                }
                if (idx < historicalCtx.lastIndex) Hairline()
            }
        } }
    }
}
@Composable
private fun UsageRequestDetailSheet(row: UsageRequestRow, onDismiss: () -> Unit, onCopyJson: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val failed = (row.status >= 400 || row.err.isNotBlank()) && row.status != 499
    val cancelled = row.status == 499

    GaliaisSheetScaffold(
        title = "请求账本详情",
        subtitle = "${displayAgentName(row.agent)} · ${if (cancelled) "已中断 499" else "HTTP ${row.status}"}",
        onDismiss = onDismiss,
        headerAction = {
            TagPill("复制 JSON", fg = G.accent, bg = G.accentContainer, onClick = onCopyJson)
        },
    ) {
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UsageDetailKv("时间", fmt.format(Date(row.ts)))
                UsageDetailKv("引擎 / 来源", "${displayAgentName(row.agent)} · ${if (row.source == "direct") "官方直连" else "本地网关"}")
                UsageDetailKv("数据精度", if (!row.exact) "估算（上游未返回精确 Token）" else if (row.streamed) "流式精确实测" else "API 精确返回（非流式）")
                UsageDetailKv("服务商", "${row.providerName} (${row.providerId})")
                if (row.requestedModel.isNotBlank() && row.requestedModel != row.model) {
                    UsageDetailKv("模型映射", "${row.requestedModel} → ${row.model}")
                } else {
                    UsageDetailKv("模型", row.model.ifBlank { "default" })
                }
                if (row.sessionId.isNotBlank()) UsageDetailKv("会话 ID", row.sessionId)
                if (row.contextUsed > 0L) {
                    val ctxStr = fmtExactTokens(row.contextUsed) + if (row.contextWindow > 0L) {
                        " / ${fmtExactTokens(row.contextWindow)} (${fmtRatePct((row.contextUsed * 100.0) / row.contextWindow)})"
                    } else ""
                    UsageDetailKv("当时上下文", ctxStr)
                }
            }
        }
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Token 精确拆解", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, fontWeight = FontWeight.SemiBold)
                UsageDetailKv("总输入（未缓存 + 缓读 + 缓写）", fmtExactTokens(row.inputTokens))
                UsageDetailKv("未命中缓存输入", fmtExactTokens(row.uncachedInputTokens))
                UsageDetailKv("缓存读取 / 命中率", "${fmtExactTokens(row.cacheReadTokens)} (${fmtRatePct(row.cacheHitRate)})")
                if (row.cacheWriteTokens > 0L) UsageDetailKv("缓存写入", fmtExactTokens(row.cacheWriteTokens))
                UsageDetailKv("总输出", fmtExactTokens(row.outputTokens))
                if (row.reasoningTokens > 0L) UsageDetailKv("其中思维链推理", fmtExactTokens(row.reasoningTokens))
                UsageDetailKv("合计 Token", fmtExactTokens(row.totalTokens))
            }
        }
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("耗时、吞吐与计费", style = MaterialTheme.typography.labelMedium, color = G.inkMuted, fontWeight = FontWeight.SemiBold)
                UsageDetailKv("首字延迟 (TTFT)", if (row.streamed && row.ttftMs > 0) "${row.ttftMs} ms" else if (!row.streamed) "非流式（不适用）" else "—")
                UsageDetailKv("纯生成解码耗时", if (row.genMs > 0) "${row.genMs} ms" else "—")
                UsageDetailKv("API 总耗时", if (row.ms > 0) "${row.ms} ms" else "—")
                UsageDetailKv("纯解码吞吐", if (row.tps > 0.0) "${String.format(Locale.US, "%.1f", row.tps)} tok/s" else "样本不足（需流式生成 ≥100ms）")
                UsageDetailKv("端到端速率", if (row.e2eTps > 0.0) "${String.format(Locale.US, "%.1f", row.e2eTps)} tok/s" else "—")
                UsageDetailKv(
                    "计费类型 / 倍率",
                    "${when (row.billingKind) { "plan" -> "订阅套餐"; "free" -> "免费"; "payg" -> "按量计费"; else -> "默认按量" }} · ${row.priceRate}x" +
                        if (!row.priceMatched && row.billingKind !in setOf("plan", "free")) "（默认单价）" else "",
                )
                UsageDetailKv("预估费用", fmtUsd(row.costUsd))
            }
        }
        if (row.err.isNotBlank()) {
            Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint, border = BorderStroke(0.75.dp, G.danger.copy(alpha = 0.35f))) {
                SelectionContainer {
                    Text(row.err, fontFamily = Mono, fontSize = 11.5.sp, color = G.danger, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun UsageDetailKv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
        Spacer(Modifier.weight(1f))
        SelectionContainer {
            Text(value, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
