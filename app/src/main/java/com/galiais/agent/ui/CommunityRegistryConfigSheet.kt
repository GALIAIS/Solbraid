package com.galiais.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.CommunityRegistryConfig
import com.galiais.agent.engine.ResourceAuditReport
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 共享资源仓库配置与审查 AI 模型连通测试抽屉。 */
@Composable
internal fun CommunityRegistryConfigSheet(
    app: GaliaisApplication,
    initial: CommunityRegistryConfig,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initial.mode) }
    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var githubRepo by remember { mutableStateOf(initial.githubRepo.ifBlank { "GALIAIS/Solbraid" }) }
    var githubBranch by remember { mutableStateOf(initial.githubBranch.ifBlank { "main" }) }
    var githubToken by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf(initial.authorName) }
    var reviewerBaseUrl by remember { mutableStateOf(initial.reviewerBaseUrl) }
    var reviewerModel by remember { mutableStateOf(initial.reviewerModel) }
    var reviewerApiKey by remember { mutableStateOf("") }
    var reviewerWireApi by remember { mutableStateOf(initial.reviewerWireApi) }
    var requireLlm by remember { mutableStateOf(initial.requireLlmReview) }
    var testingReviewer by remember { mutableStateOf(false) }
    var testNotice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun buildPatch(): JSONObject = JSONObject()
        .put("mode", mode)
        .put("serverUrl", serverUrl)
        .put("githubRepo", githubRepo)
        .put("githubBranch", githubBranch.ifBlank { "main" })
        .put("authorName", authorName.trim().ifBlank { "Solbraid 用户" })
        .put("reviewerBaseUrl", reviewerBaseUrl)
        .put("reviewerModel", reviewerModel)
        .put("reviewerWireApi", reviewerWireApi)
        .put("requireLlmReview", requireLlm)
        .apply {
            if (githubToken.isNotBlank()) put("githubToken", githubToken.trim())
            if (reviewerApiKey.isNotBlank()) put("reviewerApiKey", reviewerApiKey.trim())
        }

    GaliaisSheetScaffold(
        title = "共享资源仓库配置",
        subtitle = "选择仓库来源并配置 AI 语义安全审查模型",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton(
                text = if (testingReviewer) "探测中…" else "测试 AI",
                enabled = !testingReviewer && reviewerBaseUrl.isNotBlank(),
                onClick = {
                    testingReviewer = true; testNotice = null
                    scope.launch {
                        try {
                            app.extensions.saveCommunityRegistryConfig(buildPatch())
                            val r = app.extensions.testCommunityReviewer()
                            val ok = r.optBoolean("ok")
                            val ms = r.optInt("latencyMs")
                            testNotice = ok to (if (ok) "连通正常（${ms}ms · ${r.optString("model")}）：${r.optString("summary")}" else "未通过：${r.optString("summary")}")
                        } catch (e: Exception) {
                            testNotice = false to "连通测试失败：${e.message}"
                        } finally {
                            testingReviewer = false
                        }
                    }
                },
            )
            PrimaryPillButton("保存配置", modifier = Modifier.weight(1f), onClick = { onSave(buildPatch()) })
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectableChip("内置/本地仓库", selected = mode == "builtin-local", onClick = { mode = "builtin-local" })
            SelectableChip("远程 HTTP 仓库", selected = mode == "http", onClick = { mode = "http" })
            SelectableChip("GitHub 社区仓库", selected = mode == "github", onClick = { mode = "github" })
        }

        if (mode == "http") {
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it.trim() },
                label = { Text("仓库服务地址（运行 registry/registry-server.mjs）") },
                placeholder = { Text("https://registry.example.com") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        } else if (mode == "github") {
            CompactActionButton("填入官方仓库 GALIAIS/Solbraid", onClick = { githubRepo = "GALIAIS/Solbraid"; githubBranch = "main" })
            OutlinedTextField(
                value = githubRepo, onValueChange = { githubRepo = it.trim() },
                label = { Text("GitHub 仓库（owner/repo）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = githubBranch, onValueChange = { githubBranch = it.trim() },
                label = { Text("分支（默认 main）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = githubToken, onValueChange = { githubToken = it },
                label = { Text(if (initial.hasGithubToken) "GitHub PAT（留空保持已存，用于一键发 PR）" else "GitHub PAT（选填，用于一键自动 Fork/发 PR 上传）") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = authorName, onValueChange = { authorName = it },
            label = { Text("默认分享作者昵称") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )

        Hairline()
        Text("仓库 AI 语义安全审查模型（可选）", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectableChip("OpenAI 兼容", selected = reviewerWireApi == "openai", onClick = { reviewerWireApi = "openai" })
            SelectableChip("Anthropic", selected = reviewerWireApi == "anthropic", onClick = { reviewerWireApi = "anthropic" })
        }
        OutlinedTextField(
            value = reviewerBaseUrl, onValueChange = { reviewerBaseUrl = it.trim() },
            label = { Text("审查 AI Base URL（如 https://api.openai.com/v1）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = reviewerModel, onValueChange = { reviewerModel = it.trim() },
            label = { Text("审查模型名称（如 gpt-4o-mini / claude-haiku-4-5）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = reviewerApiKey, onValueChange = { reviewerApiKey = it },
            label = { Text(if (initial.hasReviewerApiKey) "审查 API Key（留空保持已存密钥）" else "审查 API Key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        SettingsToggleRow(
            title = "严格强制 LLM 语义审查（Fail-Closed）",
            subtitle = "开启后若未配置审查模型或接口异常，将直接阻止自动入库",
            checked = requireLlm,
            onCheckedChange = { requireLlm = it },
        )

        testNotice?.let { (ok, msg) ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) G.success else G.danger)
        }
    }
}

@Composable
internal fun AuditReportDialog(
    title: String,
    report: ResourceAuditReport,
    onDismiss: () -> Unit,
) {
    GaliaisAlertDialog(
        title = "$title · AI 安全审查报告",
        onDismissRequest = onDismiss,
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TagPill(
                text = if (report.approved) "审查通过" else "已拦截 / 待审",
                fg = if (report.approved) G.success else G.danger,
                bg = if (report.approved) G.successTint else G.dangerTint,
            )
            TagPill("评分 ${report.score}/100", fg = G.inkMuted, bg = G.surfaceSubtle)
            TagPill("风险 ${report.riskLevel}", fg = G.inkMuted, bg = G.surfaceSubtle)
        }
        Text(report.summary, style = MaterialTheme.typography.bodySmall, color = G.ink)
        SelectionContainer {
            Text("模式：${report.reviewerMode} · 引擎：${report.reviewerModel.ifBlank { "solbraid-static-guard" }}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
        }
        if (report.sha256.isNotBlank()) {
            SelectionContainer {
                Text("SHA-256：${report.sha256}", fontFamily = Mono, fontSize = 10.5.sp, color = G.inkSubtle)
            }
        }
        if (report.checks.isNotEmpty()) {
            Hairline()
            Text("执行的安全检查项", style = MaterialTheme.typography.labelMedium, color = G.ink, fontWeight = FontWeight.SemiBold)
            report.checks.forEach { chk -> Text("✓ $chk", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        }
        if (report.findings.isNotEmpty()) {
            Hairline()
            Text("风险检出明细（${report.findings.size}）", style = MaterialTheme.typography.labelMedium, color = G.danger, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.findings.forEach { f ->
                        Text("[${f.severity.uppercase()}] ${f.title} (${f.location})\n${f.detail}", fontFamily = Mono, fontSize = 11.sp, color = G.danger)
                    }
                }
            }
        }
    }
}
