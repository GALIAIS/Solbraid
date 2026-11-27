package com.galiais.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.BuildConfig
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.R
import com.galiais.agent.engine.DEFAULT_WORKSPACE
import com.galiais.agent.engine.str

/** 工作区与会话设置子页面。 */
@Composable
fun WorkspaceSettingsScreen(app: GaliaisApplication, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    var cwdDraft by remember(settings.defaultCwd) { mutableStateOf(settings.defaultCwd) }

    SubPageScaffold(title = "工作区与会话", onBack = onBack, snackbar = snackbar) {
        SectionLabel("默认工作区")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = cwdDraft,
                    onValueChange = { cwdDraft = it },
                    label = { Text("默认工作目录（Linux 路径）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryPillButton(
                        text = "保存目录",
                        enabled = cwdDraft.trim().startsWith("/") && cwdDraft.trim() != settings.defaultCwd,
                        onClick = {
                            val normalized = cwdDraft.trim().trimEnd('/').ifEmpty { "/" }
                            app.settings.setDefaultCwd(normalized)
                            app.conversations.notify("已更新默认工作目录：$normalized")
                        },
                    )
                    if (settings.defaultCwd != DEFAULT_WORKSPACE) {
                        SecondaryPillButton("恢复默认", onClick = {
                            app.settings.setDefaultCwd(DEFAULT_WORKSPACE)
                            cwdDraft = DEFAULT_WORKSPACE
                        })
                    }
                }
            }
        }

        SectionLabel("Agent 协作")
        CrispCard {
            SettingsToggleRow(
                title = "允许跨 Agent 委派",
                subtitle = "允许 Agent 创建子会话处理任务，每次委派需确认。",
                checked = settings.delegationEnabled,
                onCheckedChange = app.settings::setDelegationEnabled,
            )
        }

        SectionLabel("引擎推理摘要显示")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codex 推理摘要", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    SegmentedPillBar(
                        options = listOf("auto" to "自动", "concise" to "简明", "detailed" to "详细", "none" to "关闭"),
                        selected = settings.reasoningSummary,
                        onSelect = app.settings::setReasoningSummary,
                    )
                }
                Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Claude Code 推理摘要", style = MaterialTheme.typography.titleSmall, color = G.ink)
                    SegmentedPillBar(
                        options = listOf("summarized" to "显示", "omitted" to "隐藏"),
                        selected = settings.claudeThinkingDisplay,
                        onSelect = app.settings::setClaudeThinkingDisplay,
                    )
                }
            }
        }
        Text(
            "编辑器模式、自动跟随、执行过程展开、Token 环与快捷键等通用显示偏好，请前往「设置 → 常规」统一配置。",
            style = MaterialTheme.typography.labelSmall,
            color = G.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 关于 Solbraid 子页面。 */
@Composable
fun AboutSettingsScreen(
    app: GaliaisApplication,
    snackbar: SnackbarHostState? = null,
    onNavigate: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val runtime by app.runtime.snapshot.collectAsState()
    val cliStates by app.conversations.cliUpdates.states.collectAsState()
    val hubStatuses by app.conversations.hub.statuses.collectAsState()

    SubPageScaffold(title = "关于 Solbraid", onBack = onBack, snackbar = snackbar) {
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        painter = painterResource(R.drawable.ic_solbraid_brand),
                        contentDescription = "Solbraid",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(38.dp).clip(D.shape(10.dp)).border(0.75.dp, G.border, D.shape(10.dp)),
                    )
                    Text("Solbraid", style = MaterialTheme.typography.headlineSmall, color = G.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TagPill("v${BuildConfig.VERSION_NAME}", fg = G.accent, bg = G.accentContainer)
                }
                Text(
                    "在 Android 上使用编程助手、本地 Linux 环境和 SSH 终端。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = G.inkMuted,
                )
            }
        }

        SectionLabel("协议与许可")
        CrispCard(padding = PaddingValues(0.dp)) {
            SettingsNavRow(
                icon = GaliaisIcons.FileLines,
                title = "用户协议",
                subtitle = "服务条款、本地执行边界、数据隐私与安全责任",
                iconTint = G.accent,
                iconBg = G.accentContainer,
                onClick = { onNavigate("settings/about/agreement") },
            )
            Hairline(Modifier.padding(start = 66.dp))
            SettingsNavRow(
                icon = GaliaisIcons.BookOpen,
                title = "开放源代码许可",
                subtitle = "GNU AGPL-3.0 与第三方开源组件许可声明",
                iconTint = G.codex,
                iconBg = G.codexTint,
                onClick = { onNavigate("settings/about/licenses") },
            )
            Hairline(Modifier.padding(start = 66.dp))
            SettingsNavRow(
                icon = GaliaisIcons.Globe,
                title = "项目源代码仓库",
                subtitle = "github.com/GALIAIS/Solbraid",
                iconTint = G.inkMuted,
                iconBg = G.surfaceSubtle,
                onClick = { openExternalBrowserUrl(context, SOLBRAID_REPO_URL) },
            )
        }

        SectionLabel("引擎与通道版本")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val info = runtime.engineInfo
                if (info == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("启动运行环境后可查看引擎与网关版本。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.weight(1f))
                        if (!runtime.busy && runtime.bound) {
                            TagPill("启动环境", fg = G.onAccent, bg = G.accent, onClick = app.runtime::start)
                        }
                    }
                } else {
                    info.optJSONObject("codex")?.str("userAgent")?.let { InfoRow("Codex CLI", it) }
                    info.optJSONObject("claude")?.let {
                        InfoRow("Claude Code", it.optString("cliVersion"))
                        InfoRow("Claude Agent SDK", it.optString("sdkVersion"))
                    }
                    val agyVer = cliStates["antigravity"]?.currentVersion?.takeIf { it.isNotBlank() }
                        ?: hubStatuses["antigravity"]?.version?.takeIf { it.isNotBlank() }
                    if (agyVer != null) {
                        InfoRow("Antigravity", agyVer)
                    }
                    app.runtime.gatewayEndpoint?.let { (port, _) ->
                        InfoRow("本地协议网关", "127.0.0.1:$port")
                    }
                    val hubErr = info.optJSONObject("agentHub")?.str("error")
                    InfoRow("Agent Hub", if (hubErr != null) "异常：$hubErr" else "已连接")
                }
            }
        }

        SectionLabel("运行与数据")
        CrispCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("应用密钥存储", "使用 Android Keystore 和 AES-GCM 加密", mono = false)
                InfoRow("服务商连接", "本地网关使用随机令牌认证", mono = false)
                InfoRow("后台运行", "支持重新连接仍在运行的引擎", mono = false)
                InfoRow("执行方式", "本地 Linux 环境，不启用官方沙箱", mono = false)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.weight(0.40f))
        if (mono) {
            Text(value, fontFamily = Mono, fontSize = 12.sp, color = G.ink, modifier = Modifier.weight(0.60f))
        } else {
            Text(value, style = MaterialTheme.typography.bodySmall, color = G.ink, modifier = Modifier.weight(0.60f))
        }
    }
}
