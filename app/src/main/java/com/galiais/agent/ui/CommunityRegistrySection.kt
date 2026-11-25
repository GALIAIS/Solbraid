package com.galiais.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.CommunityRegistrySnapshot
import com.galiais.agent.engine.CommunityResourceItem
import com.galiais.agent.engine.ExtensionRegistry
import com.galiais.agent.engine.HostPluginSnapshot
import com.galiais.agent.engine.ResourceAuditReport
import kotlinx.coroutines.launch

internal fun resourceKindLabel(kind: String): String = when (kind) {
    "instruction" -> "指令"
    "mcp" -> "MCP"
    "skill" -> "技能"
    "subagent" -> "子代理"
    "hook" -> "钩子"
    "host_plugin" -> "Host 插件"
    "plugin_pack" -> "插件包"
    else -> kind
}

/** 统一社区共享资源仓库视图：浏览下载其他用户分享的各类资源，上传分享并强制通过仓库 AI 安全审查。 */
@Composable
fun CommunityRegistrySection(
    app: GaliaisApplication,
    snapshot: CommunityRegistrySnapshot,
    localRegistry: ExtensionRegistry,
    hostPlugins: HostPluginSnapshot,
    runtimeReady: Boolean,
    prefillUpload: Pair<String, String>? = null,
    onClearPrefill: () -> Unit = {},
    onBusy: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var filterKind by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var showUploadSheet by remember { mutableStateOf(false) }
    var activePrefill by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showAuditLog by remember { mutableStateOf(false) }
    var inspectingAudit by remember { mutableStateOf<Pair<String, ResourceAuditReport>?>(null) }
    var removingItem by remember { mutableStateOf<CommunityResourceItem?>(null) }

    LaunchedEffect(prefillUpload) {
        if (prefillUpload != null) {
            activePrefill = prefillUpload
            showUploadSheet = true
            onClearPrefill()
        }
    }

    val installedKeys = remember(localRegistry, hostPlugins.plugins) {
        buildSet {
            localRegistry.instructions.forEach { add("instruction:${it.id}") }
            localRegistry.mcpServers.forEach { add("mcp:${it.id}") }
            localRegistry.skills.forEach { add("skill:${it.id}") }
            localRegistry.subagents.forEach { add("subagent:${it.id}") }
            localRegistry.hooks.forEach { add("hook:${it.id}") }
            localRegistry.plugins.forEach { add("plugin_pack:${it.id}") }
            hostPlugins.plugins.forEach { add("host_plugin:${it.id}") }
        }
    }

    val visibleItems = remember(snapshot.items, filterKind, searchQuery) {
        val q = searchQuery.trim().lowercase()
        snapshot.items.filter { item ->
            (filterKind == "all" || item.kind == filterKind) && (
                q.isEmpty() ||
                    item.name.lowercase().contains(q) ||
                    item.id.lowercase().contains(q) ||
                    item.description.lowercase().contains(q) ||
                    item.author.lowercase().contains(q) ||
                    item.tags.any { it.lowercase().contains(q) }
                )
        }
    }

    val canRemove = snapshot.config.mode != "github"

    SectionHeader("统一共享资源仓库") {
        CompactActionButton("+ 上传分享", primary = true, enabled = runtimeReady, onClick = {
            activePrefill = null
            showUploadSheet = true
        })
    }

    CrispCard(padding = PaddingValues(13.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (snapshot.config.mode) {
                        "http" -> "远程 HTTP 共享仓库"
                        "github" -> "GitHub 社区仓库"
                        else -> "自托管 / 本地共享仓库"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = G.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TagPill(
                    text = if (snapshot.config.reviewerBaseUrl.isNotBlank()) "AI 语义门禁" else "规则门禁 (${snapshot.config.staticRulesCount})",
                    fg = G.success,
                    bg = G.successTint,
                )
            }
            val sourceText = snapshot.sourceLabel.ifBlank { "运行环境就绪后自动连接共享仓库" }
            Text(sourceText, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactActionButton("配置仓库与审查", enabled = runtimeReady, onClick = { showConfigSheet = true })
                CompactActionButton("刷新目录", enabled = runtimeReady, onClick = {
                    scope.launch {
                        onBusy("正在拉取共享仓库目录…")
                        try { app.extensions.refreshCommunityRegistry() }
                        catch (e: Exception) { app.conversations.notify("刷新仓库失败：${e.message}") }
                        finally { onBusy(null) }
                    }
                })
            }
        }
    }

    SearchBar(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = "搜索共享资源名称、作者或标签…",
    )

    val kindFilters = remember(snapshot.items.size) {
        listOf(
            "all" to "全部 (${snapshot.items.size})",
            "host_plugin" to "Host 插件",
            "skill" to "技能",
            "mcp" to "MCP",
            "instruction" to "指令",
            "subagent" to "子代理",
            "hook" to "钩子",
            "plugin_pack" to "插件包",
        )
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(kindFilters, key = { it.first }) { (key, label) ->
            SelectableChip(text = label, selected = filterKind == key, onClick = { filterKind = key })
        }
    }

    if (visibleItems.isEmpty()) {
        EmptyStateView(
            icon = GaliaisIcons.Sparkles,
            title = if (snapshot.items.isEmpty()) "当前仓库暂无已发布的社区资源" else "没有匹配筛选条件的共享资源",
            description = "点击「+ 上传分享」可将指令、技能、MCP、子代理、钩子或插件提交 AI 审查后分享；可在仓库配置中切换 GitHub 或团队 HTTP 服务。",
            actionLabel = if (runtimeReady) "上传分享" else null,
            onAction = if (runtimeReady) ({ activePrefill = null; showUploadSheet = true }) else null,
        )
    } else {
        CrispCard(padding = PaddingValues(0.dp)) {
            visibleItems.forEachIndexed { idx, item ->
                val isInstalled = "${item.kind}:${item.id}" in installedKeys
                CommunityResourceCardRow(
                    item = item,
                    installed = isInstalled,
                    canRemove = canRemove,
                    runtimeReady = runtimeReady,
                    onInspectAudit = { inspectingAudit = item.name to item.audit },
                    onDownload = {
                        scope.launch {
                            onBusy("正在验真并安装 ${item.name}…")
                            try { app.extensions.downloadAndInstallCommunityResource(item) }
                            catch (e: Exception) { app.conversations.notify("安装失败：${e.message}") }
                            finally { onBusy(null) }
                        }
                    },
                    onRemove = { removingItem = item },
                )
                if (idx < visibleItems.lastIndex) Hairline()
            }
        }
    }

    if (snapshot.submissions.isNotEmpty()) {
        CrispCard(padding = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAuditLog = !showAuditLog }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "仓库 AI 安全审查记录（最近 ${snapshot.submissions.size} 条）",
                        style = MaterialTheme.typography.titleSmall,
                        color = G.ink,
                        fontWeight = FontWeight.Medium,
                    )
                    AnimatedChevron(expanded = showAuditLog, tint = G.inkMuted)
                }
                AnimatedExpandable(expanded = showAuditLog) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        snapshot.submissions.take(15).forEachIndexed { idx, rec ->
                            val (stText, stFg, stBg) = when (rec.status) {
                                "approved" -> Triple("审查通过", G.success, G.successTint)
                                "pr_submitted" -> Triple("已提 PR 待审", G.accent, G.accentContainer)
                                "rejected" -> Triple("恶意/高危拦截", G.danger, G.dangerTint)
                                else -> Triple("待人工复审", G.warning, G.warningTint)
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { inspectingAudit = rec.name to rec.audit }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rec.name.ifBlank { rec.resourceId }, style = MaterialTheme.typography.titleSmall, color = G.ink)
                                    TagPill(resourceKindLabel(rec.kind), fg = G.inkMuted, bg = G.surfaceSubtle)
                                    TagPill(stText, fg = stFg, bg = stBg)
                                }
                                Text(rec.audit.summary, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (idx < snapshot.submissions.take(15).lastIndex) Hairline()
                        }
                    }
                }
            }
        }
    }

    if (showUploadSheet) {
        CommunityUploadSheet(
            app = app,
            config = snapshot.config,
            localRegistry = localRegistry,
            hostPlugins = hostPlugins,
            prefill = activePrefill,
            onDismiss = { showUploadSheet = false; activePrefill = null },
        )
    }

    if (showConfigSheet) {
        CommunityRegistryConfigSheet(
            app = app,
            initial = snapshot.config,
            onDismiss = { showConfigSheet = false },
            onSave = { patch ->
                showConfigSheet = false
                scope.launch {
                    onBusy("正在保存共享仓库配置…")
                    try { app.extensions.saveCommunityRegistryConfig(patch) }
                    catch (e: Exception) { app.conversations.notify("保存失败：${e.message}") }
                    finally { onBusy(null) }
                }
            },
        )
    }

    inspectingAudit?.let { (title, report) ->
        AuditReportDialog(title = title, report = report, onDismiss = { inspectingAudit = null })
    }

    removingItem?.let { item ->
        GaliaisConfirmDialog(
            title = "从共享仓库下架？",
            message = "确定下架资源「${item.name}」（${resourceKindLabel(item.kind)}）吗？此操作不会卸载本机已安装副本。",
            confirmText = "确认下架",
            danger = true,
            onConfirm = {
                removingItem = null
                scope.launch {
                    try { app.extensions.removeCommunityResource(item.id, item.kind) }
                    catch (e: Exception) { app.conversations.notify("下架失败：${e.message}") }
                }
            },
            onDismiss = { removingItem = null },
        )
    }
}

@Composable
private fun CommunityResourceCardRow(
    item: CommunityResourceItem,
    installed: Boolean,
    canRemove: Boolean,
    runtimeReady: Boolean,
    onInspectAudit: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold)
                    TagPill("v${item.version}", fg = G.inkMuted, bg = G.surfaceSubtle)
                    TagPill(resourceKindLabel(item.kind), fg = G.accent, bg = G.accentContainer)
                    if (installed) TagPill("已安装", fg = G.success, bg = G.successTint)
                }
                Text("作者：${item.author} · 下载 ${item.downloads} 次", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
            }
            CompactActionButton(
                text = if (installed) "更新" else "安装",
                primary = !installed,
                enabled = runtimeReady,
                onClick = onDownload,
            )
        }
        if (item.description.isNotBlank()) {
            Text(item.description, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TagPill("AI 审查 ${item.audit.score}分 · 报告", fg = G.success, bg = G.successTint, onClick = onInspectAudit)
            item.tags.take(3).forEach { t -> TagPill("#$t", fg = G.inkMuted, bg = G.surfaceSubtle) }
            if (canRemove) {
                TagPill("下架", fg = G.danger, bg = G.dangerTint, onClick = onRemove)
            }
        }
    }
}
