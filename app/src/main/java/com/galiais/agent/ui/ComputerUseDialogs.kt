package com.galiais.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.PcHostDraft
import com.galiais.agent.engine.PcHostInfo
import com.galiais.agent.engine.PcWindowItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PcHostEditorSheet(
    app: GaliaisApplication,
    initial: PcHostInfo?,
    prefillDraft: PcHostDraft? = null,
    sshAliases: List<String>,
    onDismiss: () -> Unit,
    onSave: (PcHostDraft, Boolean) -> Unit,
) {
    val initDraft = remember(initial, prefillDraft) {
        when {
            initial != null -> PcHostDraft.fromHost(initial)
            prefillDraft != null -> prefillDraft
            else -> PcHostDraft()
        }
    }
    var name by remember { mutableStateOf(initDraft.name) }
    var idDraft by remember { mutableStateOf(initDraft.id) }
    var osVal by remember { mutableStateOf(initDraft.os) }
    var transport by remember { mutableStateOf(initDraft.transport) }
    var address by remember { mutableStateOf(initDraft.address) }
    var portText by remember { mutableStateOf(initDraft.port.toString()) }
    var url by remember { mutableStateOf(initDraft.url) }
    var relayUrl by remember { mutableStateOf(initDraft.relayUrl) }
    var relaySecret by remember { mutableStateOf(initDraft.relaySecret) }
    var pairId by remember { mutableStateOf(initDraft.pairId) }
    var sshAlias by remember { mutableStateOf(initDraft.sshAlias) }
    var tls by remember { mutableStateOf(initDraft.tls) }
    var tlsFingerprint by remember { mutableStateOf(initDraft.tlsFingerprint) }
    var mac by remember { mutableStateOf(initDraft.mac) }
    var permissionLevel by remember { mutableStateOf(initDraft.permissionLevel) }
    var token by remember { mutableStateOf(initDraft.token) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheetScaffold(
        title = if (initial == null) "添加远程电脑" else "编辑电脑 · ${initial.name}",
        subtitle = "使用电脑代理提供的配对令牌连接。",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("取消", onClick = onDismiss)
            PrimaryPillButton("保存", modifier = Modifier.weight(1f), onClick = {
                val n = name.trim()
                if (n.isBlank()) { error = "请填写主机名称"; return@PrimaryPillButton }
                if (initial == null && token.isBlank()) { error = "请输入电脑端生成的配对令牌"; return@PrimaryPillButton }
                if (transport == "ssh" && sshAlias.isBlank()) { error = "SSH 隧道模式需填写 SSH 主机别名"; return@PrimaryPillButton }
                if (transport == "relay" && relayUrl.isBlank()) { error = "公网中继模式需填写中继服务器地址"; return@PrimaryPillButton }
                if (transport == "relay" && relaySecret.isBlank() && initial?.hasRelaySecret != true) {
                    error = "请输入与中继服务器一致的密钥"
                    return@PrimaryPillButton
                }
                if (transport == "lan" && address.isBlank()) { error = "局域网模式需填写电脑 IP"; return@PrimaryPillButton }
                val draft = PcHostDraft(
                    id = (initial?.id ?: idDraft).trim(),
                    name = n,
                    os = osVal,
                    transport = transport,
                    address = if (transport == "usb") "127.0.0.1" else address.trim(),
                    port = portText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 29840,
                    url = url.trim(),
                    relayUrl = relayUrl.trim(),
                    relaySecret = relaySecret.trim(),
                    pairId = pairId.trim(),
                    sshAlias = sshAlias.trim(),
                    tls = tls,
                    tlsFingerprint = tlsFingerprint.trim(),
                    mac = mac.trim(),
                    permissionLevel = permissionLevel,
                    token = token.trim(),
                    setDefault = initial?.isDefault ?: true,
                )
                onSave(draft, initial != null)
            })
        },
    ) {
        Text("连接模式", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "usb" to "USB 直连",
                "lan" to "局域网",
                "ssh" to "SSH 隧道",
                "public" to "公网直连",
                "relay" to "公网中继",
            ).forEach { (id, label) ->
                SelectableChip(label, selected = transport == id, onClick = {
                    transport = id
                    if (id == "usb") address = "127.0.0.1"
                })
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("电脑名称") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (initial == null) {
            OutlinedTextField(
                value = idDraft, onValueChange = { idDraft = it.trim() },
                label = { Text("主机 ID，留空自动生成") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        }
        when (transport) {
            "usb" -> {
                CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                    Text(
                        "在电脑上运行 adb reverse tcp:${portText.ifBlank { "29840" }} tcp:${portText.ifBlank { "29840" }}，将手机端口转发到电脑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = G.inkMuted,
                    )
                }
            }
            "ssh" -> {
                if (sshAliases.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sshAliases.take(6).forEach { alias ->
                            SelectableChip(alias, selected = sshAlias == alias, onClick = { sshAlias = alias })
                        }
                    }
                }
                OutlinedTextField(
                    value = sshAlias, onValueChange = { sshAlias = it.trim() },
                    label = { Text("SSH 别名或 user@host") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
            }
            "relay" -> {
                OutlinedTextField(
                    value = relayUrl, onValueChange = { relayUrl = it.trim() },
                    label = { Text("公网中继地址（如 https://relay.example.com）") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pairId, onValueChange = { pairId = it.trim() },
                    label = { Text("中继配对 ID（需与电脑端 --pair-id 一致）") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relaySecret, onValueChange = { relaySecret = it.trim() },
                    label = { Text(if (initial?.hasRelaySecret == true) "中继密钥，留空保留原值" else "中继密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
            "public" -> {
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim() },
                    label = { Text("完整公网代理地址（https://pc.example.com）") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                OutlinedTextField(
                    value = address, onValueChange = { address = it.trim() },
                    label = { Text("局域网 IP 或主机名（如 192.168.1.100）") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (transport != "relay" && transport != "public") {
            OutlinedTextField(
                value = portText, onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("电脑代理端口（默认 29840）") }, singleLine = true,
                shape = D.fieldShape, colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() },
            label = { Text(if (initial != null) "配对令牌（已保存，留空不变）" else "配对令牌 token（电脑启动时打印）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            shape = D.fieldShape, colors = galiaisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text("安全权限档位", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        SegmentedPillBar(
            options = listOf("observe" to "只读观察", "operate" to "常规操控", "admin" to "完全管理"),
            selected = permissionLevel,
            onSelect = { permissionLevel = it },
        )
        Text("目标操作系统", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "自动", "windows" to "Windows", "macos" to "macOS", "linux" to "Linux").forEach { (id, label) ->
                SelectableChip(label, selected = osVal == id, onClick = { osVal = id })
            }
        }
        OutlinedTextField(
            value = mac, onValueChange = { mac = it.trim() },
            label = { Text("远程唤醒用 MAC 地址（可选）") }, singleLine = true,
            shape = D.fieldShape, colors = galiaisTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
        )
        if (transport == "lan" || transport == "public") {
            SettingsToggleRow(
                title = "启用 HTTPS / TLS 加密",
                subtitle = "公网或不受信任局域网建议开启并固定证书 SHA-256 指纹",
                checked = tls,
                onCheckedChange = { tls = it },
            )
            if (tls) {
                OutlinedTextField(
                    value = tlsFingerprint, onValueChange = { tlsFingerprint = it.trim() },
                    label = { Text("自签证书 SHA-256 指纹（可选）") }, singleLine = true,
                    shape = D.fieldShape, colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
    }
}

@Composable
internal fun PcPastePairSheet(
    app: GaliaisApplication,
    onDismiss: () -> Unit,
    onParsed: (PcHostDraft) -> Unit,
) {
    PasteImportSheet(
        app = app,
        title = "粘贴电脑配对 JSON",
        subtitle = "在电脑运行 pc-agent 后，直接复制控制台底部打印的配对 JSON 并导入",
        placeholder = "{\"id\":\"my-pc\",\"transport\":\"usb\",\"address\":\"127.0.0.1\",\"port\":29840,\"token\":\"...\"}",
        confirmLabel = "导入并配对",
        onDismiss = onDismiss,
        onImport = { raw ->
            try {
                val draft = PcHostDraft.parsePairingJson(raw)
                onParsed(draft)
                null
            } catch (e: Exception) {
                e.message ?: "无法解析配对 JSON"
            }
        },
    )
}

@Composable
internal fun PcAgentSetupGuideDialog(app: GaliaisApplication, onDismiss: () -> Unit) {
    fun copyCmd(label: String, cmd: String) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, cmd))
        app.conversations.notify("已复制：$label")
    }
    val blocks = listOf(
        Triple("原生电脑代理", "可在「构建部署」栏离线编译或通过 SSH 部署，支持自签 TLS 与局域网发现", "solbraid-desktop --host 0.0.0.0 --port 29840 --auto-tls --permission operate"),
        Triple("Windows USB 连接", "适合手机 USB 连接 Windows 电脑，自动配置 adb reverse", "powershell -ExecutionPolicy Bypass -File pc-agent/start-windows.ps1"),
        Triple("手动建立 USB adb reverse", "macOS / Linux USB 连手机时执行", "adb reverse tcp:29840 tcp:29840"),
        Triple("公网中继", "在公网服务器启动中继，在电脑启动代理。两端与手机使用同一中继密钥。", "node pc-agent/solbraid-relay.mjs --port 29845 --secret YOUR_RELAY_SECRET\nsolbraid-desktop --relay https://your-relay --pair-id my-pc --relay-secret YOUR_RELAY_SECRET"),
    )
    GaliaisSheetScaffold(
        title = "电脑代理启动指南",
        subtitle = "选择与电脑系统和连接方式对应的命令。",
        onDismiss = onDismiss,
        footer = { SecondaryPillButton("关闭", modifier = Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        blocks.forEach { (title, desc, cmd) ->
            CrispCard(padding = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.ink)
                            Text(desc, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
                        }
                        TagPill("复制", fg = G.accent, bg = G.accentContainer, onClick = { copyCmd(title, cmd) })
                    }
                    CrispCard(background = G.surfaceSubtle, padding = PaddingValues(10.dp)) {
                        SelectionContainer {
                            Text(cmd, fontFamily = Mono, fontSize = 11.sp, color = G.ink, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PcWindowsListSheet(
    hostName: String,
    windows: List<PcWindowItem>,
    loading: Boolean,
    onAction: (action: String, win: PcWindowItem) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    GaliaisSheetScaffold(
        title = "$hostName · 桌面窗口 (${windows.size})",
        subtitle = "点击按钮直接置顶聚焦、最大化、最小化或关闭远程电脑窗口",
        onDismiss = onDismiss,
        footer = {
            SecondaryPillButton("刷新列表", onClick = onRefresh)
            Spacer(Modifier.weight(1f))
            PrimaryPillButton("关闭", onClick = onDismiss)
        },
    ) {
        if (loading) {
            Text("正在读取电脑可见窗口列表…", style = MaterialTheme.typography.bodySmall, color = G.inkSubtle)
        } else if (windows.isEmpty()) {
            CrispCard { Text("未检测到可见桌面窗口（请确认电脑已解锁且处于交互桌面会话）", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
        } else {
            CrispCard(padding = PaddingValues(0.dp)) {
                windows.forEachIndexed { idx, w ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(w.title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.titleSmall, color = G.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (w.active) TagPill("当前焦点", fg = G.success, bg = G.successTint)
                        }
                        Text("${w.process} (PID ${w.pid}) · ${w.width}×${w.height}", fontFamily = Mono, fontSize = 11.sp, color = G.inkSubtle)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagPill("聚焦", fg = G.accent, bg = G.accentContainer, onClick = { scope.launch { onAction("focus", w) } })
                            TagPill("最大化", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { scope.launch { onAction("maximize", w) } })
                            TagPill("最小化", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { scope.launch { onAction("minimize", w) } })
                            TagPill("关闭", fg = G.danger, bg = G.dangerTint, onClick = { scope.launch { onAction("close", w) } })
                        }
                    }
                    if (idx < windows.lastIndex) Hairline()
                }
            }
        }
    }
}
