package com.galiais.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.AgentCatalog
import com.galiais.agent.engine.ConversationKey
import com.galiais.agent.engine.ConversationState
import com.galiais.agent.engine.ConversationSummary
import com.galiais.agent.engine.CustomProvider
import com.galiais.agent.engine.EngineKind
import com.galiais.agent.engine.FileDiff
import com.galiais.agent.engine.FuzzyMatch
import com.galiais.agent.engine.ImageAttachment
import com.galiais.agent.engine.McpServerSummary
import com.galiais.agent.engine.ModelOption
import com.galiais.agent.engine.SlashCommand
import com.galiais.agent.engine.TokenUsage
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.engine.reasoningEfforts
import com.galiais.agent.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

private fun readStreamBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16_384)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 将系统图库选中的图片在 IO 线程安全有界读入、按 EXIF 纠正方向并缩放到安全尺寸（最长边 ≤1600px JPEG）以便直传 Codex / Claude。 */
suspend fun loadScaledImageAttachment(context: Context, uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "image.jpg"
    val raw = resolver.openInputStream(uri)?.use { readStreamBounded(it, 24 * 1024 * 1024) } ?: return@withContext null
    if (raw.isEmpty()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return@withContext null
    val rotationDeg = runCatching {
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(raw))
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotationDeg != 0f) {
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDeg) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        decoded = rotated
    }
    val scaled = if (max(decoded.width, decoded.height) > 1600) {
        val ratio = 1600f / max(decoded.width, decoded.height)
        Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
            .also { if (it !== decoded) decoded.recycle() }
    } else decoded
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
    scaled.recycle()
    val bytes = out.toByteArray()
    if (bytes.size > 3 * 1024 * 1024) return@withContext null
    ImageAttachment(
        id = newId(),
        name = name,
        mimeType = "image/jpeg",
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        bytes = bytes.size,
    )
}

data class DocumentAttachment(
    val name: String,
    val mimeType: String,
    val data: String,
    val isPdf: Boolean,
)

/** 读取用户选取的 PDF（Base64，最大 5 MB）或纯文本/Markdown/源码（UTF-8，最大 512 KB）供 Claude Code 原生 document 块使用（先校验上限避免大文件 OOM）。 */
suspend fun loadDocumentAttachment(context: Context, uri: Uri): DocumentAttachment? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "document"
    val mime = resolver.getType(uri) ?: if (name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "text/plain"
    val isPdf = mime.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)
    val maxBytes = if (isPdf) 5 * 1024 * 1024 else 512 * 1024
    val raw = resolver.openInputStream(uri)?.use { readStreamBounded(it, maxBytes) } ?: return@withContext null
    if (raw.isEmpty()) return@withContext null
    if (isPdf) {
        DocumentAttachment(name, "application/pdf", Base64.encodeToString(raw, Base64.NO_WRAP), true)
    } else {
        DocumentAttachment(name, "text/plain", String(raw, Charsets.UTF_8), false)
    }
}

/** 会话详情、Token 用量与快捷操作抽屉。 */
@Composable
fun SessionInfoSheet(
    app: GaliaisApplication,
    key: ConversationKey,
    state: ConversationState?,
    provider: CustomProvider?,
    onSwitchProviderModel: () -> Unit = {},
    onRename: () -> Unit,
    onCompact: () -> Unit,
    onFork: (() -> Unit)?,
    onArchive: (() -> Unit)? = null,
    onReview: () -> Unit,
    onExportCopy: () -> Unit,
    onExportSave: () -> Unit,
    onOpenDirectory: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var claudeMcp by remember(key) { mutableStateOf<List<McpServerSummary>>(emptyList()) }
    LaunchedEffect(key) {
        if (key.engine == EngineKind.CLAUDE) {
            app.conversations.claude.refreshContextUsage(key)
            claudeMcp = try { app.conversations.claude.mcpServers(key) } catch (_: Exception) { emptyList() }
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineLabel(key.engine)
                Text(
                    state?.summary?.title ?: "会话详情",
                    style = MaterialTheme.typography.titleMedium,
                    color = G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            state?.tokenUsage?.let { usage -> TokenUsageCard(usage) }

            CrispCard(padding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaRow(
                        "服务商",
                        when {
                            provider != null && !provider.enabled -> "${provider.name}（已停用）"
                            provider != null -> "${provider.name}（${provider.displayTypeBadge()}）"
                            state?.providerId != null -> "已删除的服务商（请重选）"
                            else -> when (key.engine.id) {
                                "codex" -> "OpenAI 官方"
                                "claude" -> "Anthropic 官方"
                                "antigravity" -> "Google 官方"
                                else -> "Agent 自带配置"
                            }
                        },
                    )
                    state?.model?.let { MetaRow("模型", it) }
                    state?.effort?.let { MetaRow("推理强度", it) }
                    if (key.engine == EngineKind.CLAUDE) {
                        MetaRow("权限模式", claudePermissionModeLabel(state?.permissionMode))
                    } else if (key.engine == EngineKind.CODEX) {
                        MetaRow("审批策略", codexPermissionModeLabel(state?.approvalPolicy ?: app.settings.state.value.approvalPolicy, state?.approvalsReviewer ?: app.settings.state.value.approvalsReviewer))
                    } else if (key.engine.id == "antigravity") {
                        MetaRow("执行模式", antigravityPermissionModeLabel(state?.permissionMode))
                    }
                    if (key.engine == EngineKind.CODEX || key.engine == EngineKind.CLAUDE) {
                        val summary = state?.reasoningSummary ?: app.settings.state.value.defaultReasoningSummaryFor(key.engine)
                        MetaRow("推理摘要", if (key.engine == EngineKind.CLAUDE) { if (summary == "none") "隐藏" else "显示" } else reasoningSummaryLabel(summary))
                    }
                    state?.summary?.cwd?.let { MetaRow("工作目录", it) }
                    MetaRow("时间线条目", "${state?.items?.size ?: 0} 条")
                }
            }

            if (key.engine == EngineKind.CLAUDE && claudeMcp.isNotEmpty()) {
                CrispCard(padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("会话 MCP 服务器 (${claudeMcp.size})", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        claudeMcp.forEach { srv ->
                            val ok = srv.status.equals("connected", ignoreCase = true)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(srv.name, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TagPill(
                                    text = srv.status.ifBlank { "未知" },
                                    fg = if (ok) G.success else G.inkMuted,
                                    bg = if (ok) G.successTint else G.surfaceSubtle,
                                )
                            }
                        }
                    }
                }
            }

            SectionLabel("模型与上下文")
            CrispCard(padding = PaddingValues(0.dp)) {
                val running = state?.running == true
                SheetActionRow(GaliaisIcons.Plug, "切换服务商与模型", "更换本会话使用的服务商、模型或推理档位", enabled = !running, onClick = { onDismiss(); onSwitchProviderModel() })
                Hairline(Modifier.padding(start = 48.dp))
                SheetActionRow(GaliaisIcons.Compress, "压缩上下文", "总结早期消息，减少上下文占用", enabled = !running, onClick = { onDismiss(); onCompact() })
                if (onFork != null) {
                    Hairline(Modifier.padding(start = 48.dp))
                    SheetActionRow(GaliaisIcons.GitBranch, "创建会话分支", "沿用当前上下文创建新会话", enabled = !running, onClick = { onDismiss(); onFork() })
                }
                Hairline(Modifier.padding(start = 48.dp))
                SheetActionRow(GaliaisIcons.ShieldCheck, "代码审查", "审查当前工作区的未提交代码改动", enabled = !running, onClick = { onDismiss(); onReview() })
            }

            SectionLabel("导出与工作区")
            CrispCard(padding = PaddingValues(0.dp)) {
                SheetActionRow(GaliaisIcons.Copy, "复制会话 Markdown", "复制已加载的会话内容，较长的工具输出会截断", onClick = { onDismiss(); onExportCopy() })
                if (state?.summary?.cwd != null) {
                    Hairline(Modifier.padding(start = 48.dp))
                    SheetActionRow(GaliaisIcons.FilePlus, "导出 Markdown 到工作区", "保存为当前目录下的 .md 文件", onClick = { onDismiss(); onExportSave() })
                }
                if (onOpenDirectory != null) {
                    Hairline(Modifier.padding(start = 48.dp))
                    SheetActionRow(GaliaisIcons.Folder, "打开工作目录", "浏览、编辑或查看 Git 改动", onClick = { onDismiss(); onOpenDirectory() })
                }
            }

            SectionLabel("会话管理")
            CrispCard(padding = PaddingValues(0.dp)) {
                val running = state?.running == true
                SheetActionRow(GaliaisIcons.Edit, "重命名会话", "修改当前会话标题", onClick = { onDismiss(); onRename() })
                if (onArchive != null) {
                    Hairline(Modifier.padding(start = 48.dp))
                    SheetActionRow(GaliaisIcons.Archive, "归档会话", "移入归档列表，可从会话页恢复", enabled = !running, onClick = { onDismiss(); onArchive() })
                }
            }

            CrispCard(padding = PaddingValues(0.dp)) {
                SheetActionRow(GaliaisIcons.Trash, "删除此会话", "仅删除会话记录，不影响工作区文件", danger = true, onClick = { onDismiss(); onDelete() })
            }
        }
    }
}

fun claudePermissionModeLabel(mode: String?): String = when (mode) {
    "acceptEdits" -> "自动允许编辑"
    "plan" -> "计划模式"
    "auto" -> "AI 自动审校"
    "bypassPermissions" -> "完全访问"
    else -> "默认询问"
}

fun antigravityPermissionModeLabel(mode: String?): String = when (mode) {
    "accept-edits" -> "自动允许编辑"
    "auto" -> "智能自动"
    "plan" -> "计划模式"
    "bypass" -> "完全访问"
    else -> "默认询问"
}

fun codexPermissionModeLabel(policy: String?, reviewer: String?): String = when {
    policy == "never" -> "完全访问"
    reviewer == "auto_review" -> "AI 自动审校"
    policy == "untrusted" -> "严格审批"
    else -> "按需审批"
}

fun compactComposerPermLabel(label: String): String = when (label) {
    "默认询问" -> "默认"
    "自动允许编辑" -> "自动编辑"
    "AI 自动审校" -> "AI 审校"
    "计划模式" -> "计划"
    "按需审批" -> "按需"
    "严格审批" -> "严格"
    else -> label
}

fun reasoningSummaryLabel(summary: String?): String = when (summary) {
    "concise" -> "简明"
    "detailed" -> "详细"
    "none" -> "关闭"
    else -> "自动"
}

fun effortLabel(value: String?): String = when (value) {
    null -> "默认"
    "none" -> "关闭"
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    "ultra" -> "超高"
    else -> value
}

fun reasoningSummaryOptions(engine: EngineKind): List<Pair<String, String>> = when (engine) {
    EngineKind.CODEX -> listOf("auto" to "自动", "concise" to "简明", "detailed" to "详细", "none" to "关闭")
    EngineKind.CLAUDE -> listOf("auto" to "显示", "none" to "隐藏")
    else -> emptyList()
}

@Composable
fun AgentOptionChips(title: String, options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = G.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                SelectableChip(label, selected = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
fun ConversationModelOptions(app: GaliaisApplication, key: ConversationKey, state: ConversationState?, model: ModelOption?) {
    val settings = app.settings.state.value
    val repo = app.conversations
    val builtin = key.engine == EngineKind.CODEX || key.engine == EngineKind.CLAUDE
    val isAgy = key.engine.id == "antigravity"
    val acpConfigs = if (!builtin) repo.hub.configOptionsOf(key) else emptyList()
    val thoughtOpt = acpConfigs.firstOrNull { it.id == "thought_level" || it.category == "thought_level" }
    val efforts = when {
        builtin -> model?.reasoningEfforts().orEmpty()
        isAgy -> model?.efforts.orEmpty().ifEmpty { thoughtOpt?.options?.map { it.value }.orEmpty() }
        else -> emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (efforts.isNotEmpty()) {
            val effortOptions = if (isAgy) efforts.map { it to effortLabel(it) }
            else listOf(null to "默认") + efforts.map { it to effortLabel(it) }
            AgentOptionChips("推理强度", effortOptions, state?.effort ?: thoughtOpt?.currentValue) {
                if (isAgy && it != null) repo.setEffort(key, it)
                else if (!isAgy) repo.setEffort(key, it)
            }
        }
        if (builtin) {
            AgentOptionChips("推理摘要", reasoningSummaryOptions(key.engine),
                state?.reasoningSummary ?: settings.defaultReasoningSummaryFor(key.engine)) {
                if (it != null) repo.setReasoningSummary(key, it)
            }
        }
        if (key.engine == EngineKind.CODEX) {
            if (model?.serviceTiers?.isNotEmpty() == true) {
                AgentOptionChips("服务档位", listOf(null to "默认") + model.serviceTiers.map { it.id to it.name }, state?.serviceTier) {
                    repo.setServiceTier(key, it)
                }
            }
            AgentOptionChips("工作模式", listOf("default" to "执行", "plan" to "计划"), state?.collaborationMode ?: "default") {
                if (it != null) repo.setCollaborationMode(key, it)
            }
        } else if (key.engine == EngineKind.CLAUDE && model?.supportsFastMode == true) {
            AgentOptionChips("快速模式", listOf(null to "默认", "true" to "开启", "false" to "关闭"), state?.fastMode?.toString()) {
                repo.setFastMode(key, it?.toBooleanStrict())
            }
        } else if (!builtin) {
            if (!isAgy && thoughtOpt != null && thoughtOpt.options.isNotEmpty()) {
                AgentOptionChips(
                    title = "推理强度",
                    options = thoughtOpt.options.map { it.value to effortLabel(it.value) },
                    selected = state?.effort ?: thoughtOpt.currentValue,
                ) { picked ->
                    if (picked != null) repo.setAcpConfigOption(key, thoughtOpt.id, picked)
                }
            }
            acpConfigs.filter { it.id !in setOf("model", "mode", "thought_level") && it.category !in setOf("model", "mode", "thought_level") }.forEach { opt ->
                if (opt.options.isNotEmpty()) {
                    AgentOptionChips(
                        title = opt.name,
                        options = opt.options.map { it.value to it.name },
                        selected = opt.currentValue,
                    ) { picked ->
                        if (picked != null) repo.setAcpConfigOption(key, opt.id, picked)
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenUsageCard(usage: TokenUsage) {
    val win = usage.contextWindow?.takeIf { it > 0 }
    val ratio = usage.ratio()
    val cacheHit = usage.cacheHitPercent()
    CrispCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前上下文与 Token 用量", style = MaterialTheme.typography.titleSmall, color = G.ink, modifier = Modifier.weight(1f))
                TagPill(
                    usage.compactLabel(),
                    fg = if (ratio != null && ratio >= 0.85f) G.danger else if (ratio != null && ratio >= 0.65f) G.warning else G.accent,
                    bg = if (ratio != null && ratio >= 0.85f) G.dangerTint else if (ratio != null && ratio >= 0.65f) G.warningTint else G.accentContainer,
                )
            }
            if (win != null && ratio != null) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)),
                    color = if (ratio >= 0.85f) G.danger else if (ratio >= 0.65f) G.warning else G.accent,
                    trackColor = G.surfaceSubtle,
                )
                val remaining = (win - usage.totalTokens).coerceAtLeast(0L)
                Text(
                    "剩余窗口 ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(remaining)} tok" +
                        (cacheHit?.takeIf { usage.cachedInputTokens > 0L }?.let { " · 缓存命中 ${"%.1f".format(it)}%" } ?: ""),
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = G.inkMuted,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCell("总输入", usage.inputTokens)
                StatCell("未缓存", usage.uncachedInputTokens)
                StatCell("缓存读", usage.cachedInputTokens)
                StatCell("输出", usage.outputTokens)
                if (usage.reasoningTokens > 0) StatCell("思维链", usage.reasoningTokens)
            }
            if (usage.cacheWriteTokens > 0L) {
                Text("缓存写入：${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(usage.cacheWriteTokens)} tokens", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
            }
            usage.sessionTotalTokens?.takeIf { it > usage.totalTokens }?.let { cum ->
                Text("会话累计消耗：${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(cum)} tokens", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Long) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
        Text(java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = G.inkMuted, modifier = Modifier.weight(0.32f))
        Text(value, fontFamily = Mono, fontSize = 11.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.68f))
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> G.inkSubtle
                danger -> G.danger
                else -> G.inkMuted
            },
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    !enabled -> G.inkSubtle
                    danger -> G.danger
                    else -> G.ink
                },
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) G.inkMuted else G.inkSubtle)
        }
    }
}

/** `@` 文件模糊引用抽屉（底层调用官方 Codex fuzzyFileSearch，空查询回退当前目录）。 */
@Composable
fun FileMentionSheet(files: WorkspaceFiles, cwd: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<FuzzyMatch>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, cwd) {
        loading = true
        if (query.isBlank()) {
            matches = try {
                files.list(cwd).map { FuzzyMatch(it.name + if (it.directory) "/" else "", it.name) }
            } catch (_: Exception) { emptyList() }
        } else {
            delay(180)
            matches = try { files.fuzzySearch(query.trim(), cwd) } catch (_: Exception) { emptyList() }
        }
        loading = false
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("引用工作区文件", style = MaterialTheme.typography.titleLarge, color = G.ink)
            SearchBar(value = query, onValueChange = { query = it }, placeholder = "搜索 $cwd 下的文件…")
            HeaderProgressSlot(loading)
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!loading && matches.isEmpty()) {
                    Text("没有找到匹配文件", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(12.dp))
                }
                matches.take(50).forEach { m ->
                    val rel = m.path.removePrefix(cwd.trimEnd('/') + "/")
                    val isDir = rel.endsWith("/")
                    Surface(onClick = { onSelect(rel) }, shape = D.shape(10.dp), color = Color.Transparent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (isDir) GaliaisIcons.Folder else GaliaisIcons.FileLines,
                                contentDescription = null,
                                tint = if (isDir) G.accent else G.inkMuted,
                                modifier = Modifier.size(17.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(m.fileName.ifBlank { rel.substringAfterLast('/') }, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@$rel", fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun builtInSlashCommands(engine: EngineKind): List<SlashCommand> = buildList {
    val caps = AgentCatalog.capabilitiesOf(engine)
    add(SlashCommand("compact", "压缩上下文，总结早期记录释放空间", localAction = "compact"))
    if (caps.fork) add(SlashCommand("fork", "沿用当前上下文创建新会话", localAction = "fork"))
    if (engine == EngineKind.CLAUDE || engine.id == "antigravity") {
        add(SlashCommand("plan", "切换计划模式", localAction = "plan"))
    }
    if (caps.archive) {
        add(SlashCommand("archive", "将当前会话归档，可随时恢复", localAction = "archive"))
    }
    add(SlashCommand("review", "审查当前工作区的未提交改动", localAction = "review"))
    add(SlashCommand("diff", "打开本轮文件改动总览", localAction = "diff"))
    add(SlashCommand("preview", "打开本地 Web 服务实时预览", localAction = "preview"))
    add(SlashCommand("export", "复制当前会话的 Markdown", localAction = "export"))
}

fun slashCommandIcon(cmd: SlashCommand): ImageVector = when {
    cmd.localAction == "compact" -> GaliaisIcons.Compress
    cmd.localAction == "fork" -> GaliaisIcons.GitBranch
    cmd.localAction == "plan" -> GaliaisIcons.ListCheck
    cmd.localAction == "archive" -> GaliaisIcons.Archive
    cmd.localAction == "review" -> GaliaisIcons.ShieldCheck
    cmd.localAction == "diff" -> GaliaisIcons.FileDiff
    cmd.localAction == "preview" -> GaliaisIcons.Globe
    cmd.localAction == "export" -> GaliaisIcons.Copy
    cmd.localAction?.startsWith("plugin:") == true -> GaliaisIcons.Plug
    else -> GaliaisIcons.Slash
}

/** `/` 快捷指令面板：合并内置动作与 Claude Code 动态官方斜杠指令。 */
@Composable
fun SlashCommandSheet(
    engine: EngineKind,
    dynamicCommands: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val builtIn = remember(engine) { builtInSlashCommands(engine) }
    val extra = remember(dynamicCommands, builtIn) {
        val seen = builtIn.map { it.name }.toSet()
        dynamicCommands.filter { it.name !in seen }
    }
    var filter by remember { mutableStateOf("") }
    val all = remember(builtIn, extra, filter) {
        val q = filter.trim().lowercase().removePrefix("/")
        (builtIn + extra).filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("快捷指令", style = MaterialTheme.typography.titleLarge, color = G.ink)
            SearchBar(value = filter, onValueChange = { filter = it }, placeholder = "筛选指令，如 compact / review…")
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                all.forEach { cmd ->
                    Surface(onClick = { onPick(cmd) }, shape = D.shape(10.dp), color = Color.Transparent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(slashCommandIcon(cmd), contentDescription = null, tint = G.accent, modifier = Modifier.size(16.dp))
                            Box(Modifier.clip(D.shape(6.dp)).background(G.accentContainer).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text("/${cmd.name}", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = G.onAccentContainer)
                            }
                            Text(
                                cmd.description.ifBlank { "执行 /${cmd.name}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = G.inkMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiffOverviewSheet(
    turnDiff: String?,
    edits: List<FileDiff>,
    cwd: String? = null,
    onDismiss: () -> Unit,
) {
    val distinctPaths = remember(edits) { edits.map { it.path }.distinct() }
    var selectedPath by remember(edits) { mutableStateOf<String?>(null) }
    val totals = remember(turnDiff, edits) {
        if (edits.isNotEmpty()) {
            var a = 0; var r = 0
            for (e in edits) { val (ca, cr) = diffStats(e.diff); a += ca; r += cr }
            a to r
        } else if (!turnDiff.isNullOrBlank()) {
            diffStats(turnDiff)
        } else 0 to 0
    }
    val visibleEdits = remember(edits, selectedPath) {
        if (selectedPath == null) edits else edits.filter { it.path == selectedPath }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("文件改动", style = MaterialTheme.typography.titleLarge, color = G.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (distinctPaths.isNotEmpty()) {
                            Text("${distinctPaths.size} 个文件", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                        if (totals.first > 0 || totals.second > 0) {
                            Text("+${totals.first}", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.success)
                            Text("-${totals.second}", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.danger)
                        }
                    }
                }
                SecondaryPillButton("关闭", onClick = onDismiss)
            }

            if (distinctPaths.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SelectableChip(
                        text = "全部 (${distinctPaths.size})",
                        selected = selectedPath == null,
                        onClick = { selectedPath = null },
                    )
                    distinctPaths.forEach { path ->
                        val shortName = stripCwdPrefix(path, cwd).substringAfterLast('/')
                        SelectableChip(
                            text = shortName,
                            selected = selectedPath == path,
                            onClick = { selectedPath = if (selectedPath == path) null else path },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selectedPath == null && !turnDiff.isNullOrBlank()) {
                    DiffView(FileDiff("本轮汇总改动", "update", turnDiff))
                }
                visibleEdits.forEach { edit ->
                    val displayPath = stripCwdPrefix(edit.path, cwd)
                    DiffView(if (displayPath == edit.path) edit else edit.copy(path = displayPath))
                }
            }
        }
    }
}


/** 可搜索的模型、服务商、推理强度、推理摘要详细度与双引擎权限模式一体化配置抽屉。 */
@Composable
fun ModelPickerSheet(
    engine: EngineKind,
    providers: List<CustomProvider>,
    activeProvider: CustomProvider?,
    currentProviderId: String? = activeProvider?.id,
    switchingProvider: Boolean = false,
    models: List<ModelOption>,
    currentModel: String?,
    supportsAutoMode: Boolean,
    optionsContent: @Composable () -> Unit,
    permissionMode: String,
    codexApprovalPolicy: String = "on-request",
    codexApprovalsReviewer: String = "user",
    onSelectProvider: (String?, String?) -> Unit,
    onSelectModel: (String?) -> Unit,
    onManualModel: () -> Unit,
    onSelectPermissionMode: (String) -> Unit,
    onSelectCodexApproval: (policy: String, reviewer: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(models, filter) {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter {
            it.id.lowercase().contains(q) || it.label.lowercase().contains(q) || it.description.lowercase().contains(q)
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
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineLabel(engine)
                Text("模型与权限", style = MaterialTheme.typography.titleLarge, color = G.ink, modifier = Modifier.weight(1f))
                SecondaryPillButton("完成", onClick = onDismiss)
            }

            val missingProvider = currentProviderId != null && activeProvider == null
            val disabledProvider = activeProvider != null && !activeProvider.enabled
            if (missingProvider) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.dangerTint) {
                    Text(
                        "当前会话绑定的服务商已被删除，请在下方重新选择服务商或切回官方配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.danger,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            } else if (disabledProvider) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.warningTint) {
                    Text(
                        "当前服务商「${activeProvider.name}」已停用，请在服务商页重新启用或切换为其他服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.warning,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            val canSwitchProvider = engine == EngineKind.CODEX || engine == EngineKind.CLAUDE || AgentCatalog.find(engine.id)?.providerBridge != null
            if (canSwitchProvider && (providers.isNotEmpty() || currentProviderId != null)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("服务商")
                    if (switchingProvider) {
                        TagPill("切换中…", fg = G.accent, bg = G.accentContainer)
                    } else if (engine == EngineKind.CODEX) {
                        Text("保留对话上下文无缝迁移", style = MaterialTheme.typography.labelSmall, color = G.inkSubtle)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectableChip(
                        text = when (engine.id) {
                            "codex" -> "OpenAI 官方"
                            "claude" -> "Anthropic 官方"
                            "antigravity" -> "Google 官方"
                            else -> "Agent 自带配置"
                        },
                        selected = activeProvider == null && currentProviderId == null,
                        onClick = { if (!switchingProvider) onSelectProvider(null, null) },
                    )
                    providers.forEach { p ->
                        SelectableChip(
                            text = "${p.name} · ${p.displayTypeBadge()}",
                            selected = activeProvider?.id == p.id,
                            onClick = { if (!switchingProvider) onSelectProvider(p.id, p.models.firstOrNull()) },
                        )
                    }
                }
            } else if (canSwitchProvider) {
                Surface(Modifier.fillMaxWidth(), shape = D.shape(10.dp), color = G.surfaceSubtle, border = BorderStroke(0.75.dp, G.border)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "当前服务商：${when (engine.id) { "codex" -> "OpenAI 官方"; "claude" -> "Anthropic 官方"; "antigravity" -> "Google 官方"; else -> "Agent 自带配置" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = G.ink,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("在「设置 → 服务商」添加并启用自定义服务商后，可在此随时切换。", style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                    }
                }
            }

            optionsContent()

            if (engine == EngineKind.CODEX) {
                SectionLabel("操作审批")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val activeKey = when {
                        codexApprovalPolicy == "never" -> "never"
                        codexApprovalsReviewer == "auto_review" -> "auto_review"
                        codexApprovalPolicy == "untrusted" -> "untrusted"
                        else -> "on-request"
                    }
                    listOf(
                        Triple("on-request", "按需审批", "on-request" to "user"),
                        Triple("untrusted", "严格审批", "untrusted" to "user"),
                        Triple("auto_review", "AI 自动审校", "on-request" to "auto_review"),
                        Triple("never", "完全访问", "never" to "user"),
                    ).forEach { (id, label, pair) ->
                        SelectableChip(
                            text = label,
                            selected = activeKey == id,
                            onClick = { onSelectCodexApproval(pair.first, pair.second) },
                        )
                    }
                }
            } else if (engine == EngineKind.CLAUDE) {
                SectionLabel("权限模式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "default" to "默认询问",
                        "acceptEdits" to "自动允许编辑",
                        "plan" to "计划模式",
                        "auto" to "AI 自动审校",
                        "bypassPermissions" to "完全访问",
                    ).filter { (id, _) -> id != "auto" || supportsAutoMode }.forEach { (id, label) ->
                        SelectableChip(
                            text = label,
                            selected = permissionMode == id,
                            onClick = { onSelectPermissionMode(id) },
                        )
                    }
                }
            } else if (engine.id == "antigravity") {
                SectionLabel("执行权限模式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "default" to "默认询问",
                        "accept-edits" to "自动允许编辑",
                        "auto" to "智能自动",
                        "plan" to "计划模式",
                        "bypass" to "完全访问",
                    ).forEach { (id, label) ->
                        SelectableChip(
                            text = label,
                            selected = permissionMode == id,
                            onClick = { onSelectPermissionMode(id) },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("选择模型")
                Spacer(Modifier.weight(1f))
                if (activeProvider != null) {
                    TagPill("手动输入 ID", fg = G.accent, bg = G.accentContainer, onClick = { onDismiss(); onManualModel() })
                }
            }
            if (models.size > 5) {
                SearchBar(value = filter, onValueChange = { filter = it }, placeholder = "搜索模型 ID 或名称…")
            }

            CrispCard(padding = PaddingValues(0.dp)) {
                if (activeProvider == null) {
                    ModelOptionRow(
                        title = "引擎默认",
                        subtitle = "跟随引擎当前默认模型",
                        selected = currentModel == null,
                        badge = null,
                        onClick = { onSelectModel(null); onDismiss() },
                    )
                    if (filtered.isNotEmpty()) Hairline()
                }
                if (filtered.isEmpty() && activeProvider != null) {
                    Text(
                        if (filter.isBlank()) "暂无模型，请选择「手动输入 ID」" else "未找到匹配的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                filtered.forEachIndexed { idx, opt ->
                    ModelOptionRow(
                        title = opt.label,
                        subtitle = opt.description.ifBlank { if (opt.id != opt.label) opt.id else "" },
                        selected = currentModel == opt.id,
                        badge = if (opt.isDefault) "默认" else null,
                        onClick = { onSelectModel(opt.id); onDismiss() },
                    )
                    if (idx < filtered.lastIndex) Hairline()
                }
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) G.accentContainer.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) G.accent else G.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badge?.let { TagPill(it, fg = G.inkMuted, bg = G.surfaceSubtle) }
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(GaliaisIcons.Check, contentDescription = "已选", tint = G.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Codex 已归档会话列表抽屉：支持一键恢复。 */
@Composable
fun ArchivedThreadsSheet(
    app: GaliaisApplication,
    onRestored: (ConversationKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var archived by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var restoringId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        archived = try { app.conversations.codex.listArchived() } catch (_: Exception) { emptyList() }
        loading = false
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已归档会话", style = MaterialTheme.typography.titleLarge, color = G.ink, modifier = Modifier.weight(1f))
                SecondaryPillButton("关闭", onClick = onDismiss)
            }
            if (loading) {
                GaliaisProgressBar()
            } else if (archived.isEmpty()) {
                CrispCard(padding = PaddingValues(16.dp)) {
                    Text("当前没有已归档的 Codex 会话。", style = MaterialTheme.typography.bodyMedium, color = G.inkMuted)
                    Spacer(Modifier.height(4.dp))
                    Text("可在会话操作菜单中归档，或使用 /archive 指令。", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    archived.forEach { s ->
                        val busy = restoringId == s.key.id
                        CrispCard(padding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(s.title, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    s.cwd?.let {
                                        Text(it, fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                                }
                                PrimaryPillButton(
                                    text = if (busy) "恢复中…" else "恢复",
                                    enabled = restoringId == null,
                                    onClick = {
                                        restoringId = s.key.id
                                        scope.launch {
                                            try {
                                                app.conversations.unarchive(s.key)
                                                archived = archived.filterNot { it.key == s.key }
                                                onDismiss()
                                                onRestored(s.key)
                                            } catch (e: Exception) {
                                                app.conversations.notify("恢复失败：${e.message}")
                                            } finally {
                                                restoringId = null
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
