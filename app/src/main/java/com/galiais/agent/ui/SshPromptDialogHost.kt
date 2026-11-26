package com.galiais.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshKeyInfo
import com.galiais.agent.engine.ssh.NativeSshEngine
import com.galiais.agent.engine.ssh.PendingSshPrompt
import com.galiais.agent.engine.ssh.TerminalSnippet
import com.galiais.agent.engine.ssh.parseQuickConnectSpec
import com.galiais.agent.newId

/** 全局 SSH 握手交互宿主：负责 TOFU 主机指纹确认、MITM 密钥变更告警及 2FA/OTP/密码质询。 */
@Composable
fun SshPromptDialogHost(sshEngine: NativeSshEngine) {
    val prompt by sshEngine.pendingPrompt.collectAsState()
    when (val p = prompt) {
        is PendingSshPrompt.HostKeyVerify -> {
            GaliaisAlertDialog(
                onDismissRequest = { sshEngine.respondHostKey(p.id, false) },
                title = if (p.isChangedMismatch) "警告：SSH 主机密钥已变更" else "确认 SSH 主机指纹",
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (p.isChangedMismatch) {
                                "远端主机 ${p.host}:${p.port} 返回了与历史记录不一致的公钥！这可能意味着服务器重装，也可能存在中间人攻击（MITM）。请务必核对指纹后再决定。"
                            } else {
                                "首次连接 ${p.host}:${p.port}。请核对以下服务器公钥指纹，确认无误后将写入本机 known_hosts："
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.isChangedMismatch) G.danger else G.inkMuted,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(G.surfaceSubtle)
                                .border(0.75.dp, if (p.isChangedMismatch) G.danger else G.border, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("算法：${p.keyType}", fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted)
                                Text(p.fingerprintSha256, fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = G.ink)
                                if (!p.existingFingerprint.isNullOrBlank()) {
                                    Text("原记录：${p.existingFingerprint}", fontFamily = Mono, fontSize = 10.5.sp, color = G.danger)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sshEngine.respondHostKey(p.id, true) }) {
                        Text(
                            if (p.isChangedMismatch) "覆盖并信任" else "信任并连接",
                            color = if (p.isChangedMismatch) G.danger else G.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sshEngine.respondHostKey(p.id, false) }) {
                        Text("拒绝连接", color = G.inkMuted)
                    }
                },
            )
        }

        is PendingSshPrompt.AuthChallenge -> {
            var answer by remember(p.id) { mutableStateOf("") }
            GaliaisAlertDialog(
                onDismissRequest = { sshEngine.respondAuth(p.id, null) },
                title = p.title,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.prompt, style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            label = { Text(if (p.secret) "输入密码 / 验证码" else "输入响应") },
                            singleLine = true,
                            visualTransformation = if (p.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sshEngine.respondAuth(p.id, answer) }) {
                        Text("提交", color = G.accent, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sshEngine.respondAuth(p.id, null) }) {
                        Text("取消", color = G.inkMuted)
                    }
                },
            )
        }

        null -> Unit
    }
}

/** 快捷连接对话框：一行 `user@host:port` / `ssh -p 2222 user@host` 立即发起原生 SSH 终端。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickConnectSshDialog(
    availableKeys: List<SshKeyInfo>,
    onDismiss: () -> Unit,
    onConnect: (host: SshHostConfig, saveToBook: Boolean, passwordPlain: String?) -> Unit,
) {
    var spec by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("key") }
    var keyName by remember { mutableStateOf(availableKeys.firstOrNull()?.name ?: "id_ed25519") }
    var password by remember { mutableStateOf("") }
    var saveToBook by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = "快捷连接 SSH",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = spec,
                    onValueChange = { spec = it; error = null },
                    label = { Text("连接地址") },
                    placeholder = { Text("root@192.168.1.10:22 或 ssh -p 2222 user@host") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                SegmentedPillBar(
                    options = listOf("key" to "密钥", "password" to "密码"),
                    selected = authType,
                    onSelect = { authType = it },
                )
                if (authType == "key" && availableKeys.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableKeys.forEach { k ->
                            SelectableChip(text = k.name, selected = keyName == k.name, onClick = { keyName = k.name })
                        }
                    }
                } else if (authType == "password") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码（可留空，连接时交互输入）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { saveToBook = !saveToBook },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saveToBook, onCheckedChange = { saveToBook = it })
                    Text("同时保存到 SSH 主机簿", style = MaterialTheme.typography.bodySmall, color = G.ink)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val base = parseQuickConnectSpec(spec, fallbackKeyName = keyName)
                    val host = base.copy(
                        id = if (saveToBook) newId().take(10) else base.id,
                        authType = authType,
                        keyName = if (authType == "key") keyName else "",
                    )
                    onConnect(host, saveToBook, password.takeIf { authType == "password" && it.isNotEmpty() })
                } catch (e: Exception) {
                    error = e.message ?: "连接格式无效"
                }
            }) { Text("连接", color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) }
        },
    )
}

/** 终端命令片段抽屉：点击即发，支持新增与删除自定义运维片段。 */
@Composable
fun TerminalSnippetsSheet(
    snippets: List<TerminalSnippet>,
    onSend: (TerminalSnippet) -> Unit,
    onSave: (TerminalSnippet) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var autoEnter by remember { mutableStateOf(true) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("命令片段", style = MaterialTheme.typography.titleMedium, color = G.ink, modifier = Modifier.weight(1f))
                TagPill(if (adding) "返回列表" else "+ 新建片段", fg = G.accent, bg = G.accentContainer, onClick = { adding = !adding })
            }
            if (adding) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称（如 查看日志）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = { Text("Shell 命令") },
                    minLines = 2,
                    maxLines = 4,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoEnter, onCheckedChange = { autoEnter = it })
                    Text("发送后自动回车执行", style = MaterialTheme.typography.bodySmall, color = G.ink)
                    Spacer(Modifier.weight(1f))
                    PrimaryPillButton("保存", enabled = title.isNotBlank() && cmd.isNotBlank(), onClick = {
                        onSave(TerminalSnippet("usr-${newId().take(8)}", title.trim(), cmd.trim(), autoEnter))
                        title = ""; cmd = ""; adding = false
                    })
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    snippets.forEach { item ->
                        CrispCard(padding = PaddingValues(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), onClick = { onSend(item) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.command, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(GaliaisIcons.Trash, contentDescription = "删除片段", tint = G.inkMuted, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
