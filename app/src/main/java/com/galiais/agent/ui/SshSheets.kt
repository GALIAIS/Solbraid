package com.galiais.agent.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.galiais.agent.engine.FileEntry
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshKeyInfo
import com.galiais.agent.engine.SshRemoteEntry
import com.galiais.agent.engine.SshTunnelPreset
import com.galiais.agent.engine.WorkspaceFiles
import com.galiais.agent.newId
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshHostEditorSheet(
    app: GaliaisApplication,
    initial: SshHostConfig?,
    availableKeys: List<SshKeyInfo>,
    availableHosts: List<SshHostConfig>,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "root") }
    var authType by remember { mutableStateOf(initial?.authType ?: "key") }
    var keyName by remember { mutableStateOf(initial?.keyName ?: availableKeys.firstOrNull()?.name ?: "id_ed25519") }
    var password by remember { mutableStateOf("") }
    var proxyJump by remember { mutableStateOf(initial?.proxyJump ?: "") }
    var forwardAgent by remember { mutableStateOf(initial?.forwardAgent ?: false) }
    var defaultPath by remember { mutableStateOf(initial?.defaultPath ?: "") }
    var startupCommand by remember { mutableStateOf(initial?.startupCommand ?: "") }
    var group by remember { mutableStateOf(initial?.group ?: "") }
    var keepAliveSec by remember { mutableStateOf((initial?.keepAliveSec ?: 30).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "添加 SSH 主机" else "编辑 SSH 主机 · ${initial.name}", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (initial == null && alias.isBlank()) {
                            alias = it.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
                        }
                    },
                    label = { Text("显示名称") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.trim() },
                    label = { Text("ssh 别名") },
                    placeholder = { Text("prod-web") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.weight(0.9f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("主机 / IP") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.weight(0.6f),
                )
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.trim() },
                label = { Text("用户名") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedPillBar(
                options = listOf("key" to "SSH 私钥（推荐）", "password" to "密码认证（加密）"),
                selected = authType,
                onSelect = { authType = it },
            )
            if (authType == "key") {
                if (availableKeys.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableKeys.forEach { k ->
                            SelectableChip(text = k.name, selected = keyName == k.name, onClick = { keyName = k.name })
                        }
                    }
                }
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it.trim() },
                    label = { Text("~/.ssh/ 下的私钥文件名") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val hasSaved = initial != null && app.envSsh.hasSshPassword(initial.id)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (hasSaved) "登录密码（已加密保存，留空不变）" else "登录密码（Android Keystore 加密）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Collapsible("高级选项（跳板机 / 默认目录 / 启动命令）", initiallyOpen = proxyJump.isNotBlank() || defaultPath.isNotBlank() || startupCommand.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = proxyJump,
                        onValueChange = { proxyJump = it.trim() },
                        label = { Text("跳板机 ProxyJump（已有别名或 user@host:port）") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val jumpChoices = availableHosts.filter { it.id != initial?.id }
                    if (jumpChoices.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            jumpChoices.forEach { h ->
                                SelectableChip(text = "经 ${h.alias}", selected = proxyJump == h.alias, onClick = {
                                    proxyJump = if (proxyJump == h.alias) "" else h.alias
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = defaultPath,
                        onValueChange = { defaultPath = it.trim() },
                        label = { Text("连上后自动切换目录（可选，如 /srv/app）") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = startupCommand,
                        onValueChange = { startupCommand = it },
                        label = { Text("连接后执行命令（可选，如 tmux attach || tmux）") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = group,
                            onValueChange = { group = it },
                            label = { Text("主机分组（如 生产 / GPU）") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            modifier = Modifier.weight(1.2f),
                        )
                        OutlinedTextField(
                            value = keepAliveSec,
                            onValueChange = { keepAliveSec = it.filter(Char::isDigit).take(3) },
                            label = { Text("心跳秒数") },
                            singleLine = true,
                            shape = D.fieldShape,
                            colors = galiaisTextFieldColors(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                    SettingsToggleRow(
                        title = "启用 SSH Agent 转发 (ForwardAgent)",
                        subtitle = "仅对高度信任的跳板机或个人开发机开启",
                        checked = forwardAgent,
                        onCheckedChange = { forwardAgent = it },
                    )
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存并同步", onClick = {
                    val cleanAlias = alias.trim().ifBlank { name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-') }
                    val p = port.toIntOrNull() ?: 22
                    if (cleanAlias.isBlank() || !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(cleanAlias)) {
                        error = "ssh 别名仅允许字母、数字、点、短横线和下划线"; return@PrimaryPillButton
                    }
                    if (host.isBlank() || user.isBlank()) { error = "请填写主机地址和用户名"; return@PrimaryPillButton }
                    if (p !in 1..65535) { error = "端口号须在 1..65535"; return@PrimaryPillButton }
                    val cfg = SshHostConfig(
                        id = initial?.id ?: newId().take(10),
                        alias = cleanAlias,
                        name = name.trim().ifBlank { cleanAlias },
                        host = host,
                        port = p,
                        user = user,
                        authType = authType,
                        keyName = if (authType == "key") keyName else "",
                        proxyJump = proxyJump,
                        forwardAgent = forwardAgent,
                        keepAliveSec = (keepAliveSec.toIntOrNull() ?: 30).coerceIn(0, 300),
                        defaultPath = defaultPath,
                        startupCommand = startupCommand.trim(),
                        group = group.trim(),
                    )
                    try {
                        app.envSsh.upsertSshHost(cfg, password.takeIf { it.isNotEmpty() })
                        onDismiss()
                    } catch (e: IllegalArgumentException) {
                        error = e.message
                    }
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshTunnelEditorSheet(
    hosts: List<SshHostConfig>,
    onSave: (SshTunnelPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedHostId by remember { mutableStateOf(hosts.firstOrNull()?.id ?: "") }
    var mode by remember { mutableStateOf("local") }
    var name by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf("8080") }
    var remoteHost by remember { mutableStateOf("127.0.0.1") }
    var remotePort by remember { mutableStateOf("8080") }
    var error by remember { mutableStateOf<String?>(null) }

    val selectedHost = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("新建 SSH 端口转发隧道", style = MaterialTheme.typography.titleLarge, color = G.ink)
            Text("选择目标 SSH 主机", style = MaterialTheme.typography.labelMedium, color = G.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hosts.forEach { h ->
                    SelectableChip(
                        text = "${h.name} (${h.alias})",
                        selected = h.id == selectedHost?.id,
                        onClick = { selectedHostId = h.id },
                    )
                }
            }
            SegmentedPillBar(
                options = listOf(
                    "local" to "-L 本地转发",
                    "remote" to "-R 远端转发",
                    "dynamic" to "-D SOCKS5",
                ),
                selected = mode,
                onSelect = {
                    mode = it
                    if (it == "dynamic" && localPort == "8080") localPort = "1080"
                },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("隧道名称") },
                placeholder = { Text(if (mode == "dynamic") "远端 SOCKS5 代理" else "远端 Web 服务预览") },
                singleLine = true,
                shape = D.fieldShape,
                colors = galiaisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (mode == "remote") "手机本地端口" else "本地监听端口") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.weight(1f),
                )
                if (mode != "dynamic") {
                    OutlinedTextField(
                        value = remotePort,
                        onValueChange = { remotePort = it.filter(Char::isDigit).take(5) },
                        label = { Text("远端端口") },
                        singleLine = true,
                        shape = D.fieldShape,
                        colors = galiaisTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (mode != "dynamic") {
                OutlinedTextField(
                    value = remoteHost,
                    onValueChange = { remoteHost = it.trim() },
                    label = { Text(if (mode == "local") "远端目标主机" else "本地转发目标") },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                when (mode) {
                    "local" -> "将远端 ${remoteHost.ifBlank { "127.0.0.1" }}:$remotePort 映射到本地 127.0.0.1:$localPort，启动后可在内置实时预览打开。"
                    "remote" -> "将手机本地 127.0.0.1:$localPort 暴露到远端服务器端口 $remotePort。"
                    else -> "在手机本地开启 127.0.0.1:$localPort SOCKS5 动态代理，借由远端主机访问内网资源。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = G.inkMuted,
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryPillButton("取消", onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                PrimaryPillButton("保存隧道", enabled = selectedHost != null, onClick = {
                    val hostCfg = selectedHost ?: return@PrimaryPillButton
                    val lp = localPort.toIntOrNull() ?: 0
                    val rp = if (mode == "dynamic") 0 else (remotePort.toIntOrNull() ?: 0)
                    val minLocal = if (mode == "remote") 1 else 1024
                    if (lp !in minLocal..65535) { error = "本地端口须在 $minLocal..65535"; return@PrimaryPillButton }
                    if (mode != "dynamic" && rp !in 1..65535) { error = "远端端口须在 1..65535"; return@PrimaryPillButton }
                    val title = name.trim().ifBlank {
                        when (mode) {
                            "dynamic" -> "${hostCfg.name} SOCKS5:$lp"
                            "remote" -> "${hostCfg.name} -R $rp"
                            else -> "${hostCfg.name} :$lp→:$rp"
                        }
                    }
                    onSave(
                        SshTunnelPreset(
                            id = newId().take(10),
                            name = title,
                            hostId = hostCfg.id,
                            alias = hostCfg.alias,
                            mode = mode,
                            localPort = lp,
                            remoteHost = remoteHost.trim().ifBlank { "127.0.0.1" },
                            remotePort = rp,
                        ),
                    )
                })
            }
        }
    }
}

@Composable
fun GenerateSshKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (name: String, type: String, comment: String, passphrase: String) -> Unit,
) {
    var keyName by remember { mutableStateOf("id_ed25519") }
    var keyType by remember { mutableStateOf("ed25519") }
    var comment by remember { mutableStateOf("solbraid-mobile") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = "生成 SSH 密钥对",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedPillBar(
                    options = listOf("ed25519" to "Ed25519（推荐）", "rsa" to "RSA 4096"),
                    selected = keyType,
                    onSelect = {
                        keyType = it
                        if (keyName == "id_ed25519" && it == "rsa") keyName = "id_rsa"
                        if (keyName == "id_rsa" && it == "ed25519") keyName = "id_ed25519"
                    },
                )
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it.trim() },
                    label = { Text("文件名（~/.ssh/ 下）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注（Comment）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("密钥口令（可选）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val clean = keyName.trim()
                if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$").matches(clean)) {
                    error = "文件名仅允许字母、数字、点、短横线和下划线"
                } else {
                    busy = true
                    scope.launch {
                        try { onConfirm(clean, keyType, comment.trim().ifBlank { "solbraid-mobile" }, passphrase) }
                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (e: Exception) { error = e.message ?: "生成失败" }
                        finally { busy = false }
                    }
                }
            }) { Text("生成", color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) }
        },
    )
}

@Composable
fun ImportSshKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (name: String, privateKey: String, passphrase: String) -> Unit,
) {
    var keyName by remember { mutableStateOf("imported_key") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = "导入 SSH 私钥",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it.trim() },
                    label = { Text("保存文件名（~/.ssh/ 下）") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text("OpenSSH / PEM 私钥全文") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
                    minLines = 5,
                    maxLines = 9,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("将自动写入 0600 权限并派生 .pub 公钥。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("密钥口令（加密私钥必填）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val clean = keyName.trim()
                when {
                    !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$").matches(clean) -> error = "文件名无效"
                    !privateKey.contains("PRIVATE KEY") -> error = "请粘贴完整私钥内容（含 BEGIN ... PRIVATE KEY）"
                    else -> {
                        busy = true
                        scope.launch {
                            try { onConfirm(clean, privateKey, passphrase) }
                            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                            catch (e: Exception) { error = e.message ?: "导入失败" }
                            finally { busy = false }
                        }
                    }
                }
            }) { Text("导入", color = G.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = G.inkMuted) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SftpBrowserSheet(
    app: GaliaisApplication,
    host: SshHostConfig,
    files: WorkspaceFiles,
    localDefaultDir: String,
    onOpenTerminalAt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var requestPath by remember(host.id) { mutableStateOf(host.defaultPath.ifBlank { "." }) }
    var displayPath by remember(host.id) { mutableStateOf(host.defaultPath.ifBlank { "." }) }
    var entries by remember { mutableStateOf<List<SshRemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var showMkdir by remember { mutableStateOf(false) }
    var showUploadPicker by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<SshRemoteEntry?>(null) }
    var entryAction by remember { mutableStateOf("") }
    var editingFile by remember { mutableStateOf<SshRemoteEntry?>(null) }
    var jumpPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(host.id, requestPath, reloadNonce) {
        loading = true
        error = null
        try {
            val (resolved, list) = app.envSsh.sftpList(host, requestPath)
            if (resolved.isNotBlank()) {
                displayPath = resolved
            }
            entries = list
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            error = e.message ?: "读取远端目录失败"
        } finally {
            loading = false
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(GaliaisIcons.Server, contentDescription = null, tint = G.accent, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("SFTP · ${host.name}", style = MaterialTheme.typography.titleMedium, color = G.ink)
                    Text(displayPath, fontFamily = Mono, fontSize = 11.5.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TagPill("刷新", fg = G.inkMuted, bg = G.surfaceSubtle, onClick = { reloadNonce++ })
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (displayPath != "/" && displayPath != ".") {
                    TagPill("↑ 上级", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = {
                        val trimmed = displayPath.trimEnd('/')
                        val idx = trimmed.lastIndexOf('/')
                        requestPath = if (idx <= 0) "/" else trimmed.substring(0, idx)
                    })
                }
                TagPill("+ 目录", fg = G.ink, bg = G.surfaceSubtle, border = G.border, onClick = { showMkdir = true })
                TagPill("上传", fg = G.accent, bg = G.accentContainer, onClick = { showUploadPicker = true })
                TagPill("转到路径", fg = G.ink, bg = G.surfaceSubtle, onClick = { jumpPath = displayPath })
                TagPill("在此开终端", fg = G.accent, bg = G.accentContainer, onClick = { onOpenTerminalAt(displayPath) })
            }

            HeaderProgressSlot(loading)
            busyLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = G.accent)
            }
            SftpTransferPanel(app, host.id, onCompleted = { reloadNonce++ })
            error?.let {
                CrispCard(padding = PaddingValues(11.dp), background = G.dangerTint, borderColor = G.danger) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger)
                }
            }

            if (loading) {
                Text("正在读取远端目录…", style = MaterialTheme.typography.bodySmall, color = G.inkMuted, modifier = Modifier.padding(8.dp))
            } else if (entries.isEmpty() && error == null) {
                CrispCard { Text("此远端目录为空。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted) }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 440.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        var rowMenu by remember(entry.path) { mutableStateOf(false) }
                        CrispCard(
                            padding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            onClick = {
                                if (entry.directory) requestPath = entry.path
                                else editingFile = entry
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    if (entry.directory) GaliaisIcons.Folder else GaliaisIcons.File,
                                    contentDescription = null,
                                    tint = if (entry.directory) G.accent else G.inkMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(entry.name, fontFamily = Mono, fontSize = 12.5.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            if (entry.permissions.isNotBlank()) append(entry.permissions).append(" · ")
                                            if (entry.directory) append("目录") else append(formatRemoteBytes(entry.size))
                                            if (entry.modified.isNotBlank()) append(" · ").append(entry.modified)
                                        },
                                        fontFamily = Mono,
                                        fontSize = 10.sp,
                                        color = G.inkMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(34.dp)) {
                                        Icon(GaliaisIcons.MoreHoriz, contentDescription = "文件操作", tint = G.inkMuted, modifier = Modifier.size(17.dp))
                                    }
                                    GaliaisDropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (!entry.directory) {
                                                GaliaisMenuRow("在线编辑", icon = GaliaisIcons.Edit, onClick = { rowMenu = false; editingFile = entry })
                                            }
                                            GaliaisMenuRow("下载到本地", icon = GaliaisIcons.Download, onClick = {
                                                rowMenu = false
                                                if (busyLabel != null) return@GaliaisMenuRow
                                                busyLabel = "正在下载 ${entry.name} → $localDefaultDir …"
                                                scope.launch {
                                                    try { app.envSsh.startTransfer(host, "download", localDefaultDir, entry.path) }
                                                    catch (e: Exception) { error = "下载失败：${e.message}" }
                                                    finally { busyLabel = null }
                                                }
                                            })
                                            GaliaisMenuRow("重命名", icon = GaliaisIcons.FilePen, onClick = { rowMenu = false; entryAction = "rename"; actionEntry = entry })
                                            GaliaisMenuRow("修改权限", icon = GaliaisIcons.ShieldCheck, onClick = { rowMenu = false; entryAction = "chmod"; actionEntry = entry })
                                            Hairline(Modifier.padding(vertical = 2.dp))
                                            GaliaisMenuRow("删除", icon = GaliaisIcons.Trash, danger = true, onClick = { rowMenu = false; entryAction = "delete"; actionEntry = entry })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        SftpEntryActionDialog(app, host, entry, entryAction, onDismiss = { actionEntry = null },
            onComplete = { actionEntry = null; reloadNonce++ })
    }
    editingFile?.let { entry ->
        SftpTextEditor(app, host, entry.path, onDismiss = { editingFile = null; reloadNonce++ })
    }
    jumpPath?.let { initial ->
        var value by remember { mutableStateOf(initial) }
        GaliaisAlertDialog(
            onDismissRequest = { jumpPath = null },
            title = "转到远端目录",
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("目录路径") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { requestPath = value; jumpPath = null }) { Text("打开", color = G.accent, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { jumpPath = null }) { Text("取消", color = G.inkMuted) } },
        )
    }
    if (showMkdir) {
        var dirName by remember { mutableStateOf("") }
        GaliaisAlertDialog(
            onDismissRequest = { showMkdir = false },
            title = "新建远端目录",
            text = {
                OutlinedTextField(
                    value = dirName,
                    onValueChange = { dirName = it },
                    label = { Text("目录名称") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = dirName.trim().trim('/')
                    if (clean.isNotEmpty() && !clean.contains('/')) {
                        showMkdir = false
                        val target = displayPath.trimEnd('/') + "/" + clean
                        busyLabel = "正在创建目录 $clean…"
                        scope.launch {
                            try {
                                app.envSsh.sftpMkdir(host, target)
                                reloadNonce++
                            } catch (e: Exception) {
                                error = "创建目录失败：${e.message}"
                            } finally {
                                busyLabel = null
                            }
                        }
                    }
                }) { Text("创建", color = G.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showMkdir = false }) { Text("取消", color = G.inkMuted) }
            },
        )
    }

    if (showUploadPicker) {
        LocalUploadPickerDialog(
            files = files,
            startDir = localDefaultDir,
            onDismiss = { showUploadPicker = false },
            onPickFile = { localPath ->
                showUploadPicker = false
                busyLabel = "正在上传 ${localPath.substringAfterLast('/')} → $displayPath …"
                scope.launch {
                    try {
                        app.envSsh.startTransfer(host, "upload", localPath, displayPath)
                        reloadNonce++
                    } catch (e: Exception) {
                        error = "上传失败：${e.message}"
                    } finally {
                        busyLabel = null
                    }
                }
            },
        )
    }
}

@Composable
private fun LocalUploadPickerDialog(
    files: WorkspaceFiles,
    startDir: String,
    onDismiss: () -> Unit,
    onPickFile: (String) -> Unit,
) {
    var localDir by remember { mutableStateOf(startDir) }
    var items by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loadErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(localDir) {
        loadErr = null
        try {
            items = files.list(localDir)
        } catch (e: Exception) {
            loadErr = e.message ?: "读取本地目录失败"
        }
    }

    GaliaisAlertDialog(
        onDismissRequest = onDismiss,
        title = "选择文件或目录",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localDir, fontFamily = Mono, fontSize = 11.sp, color = G.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (localDir != "/" && localDir != "/home/agent") {
                        TagPill("上级", fg = G.ink, bg = G.surfaceSubtle, onClick = {
                            val t = localDir.trimEnd('/')
                            val idx = t.lastIndexOf('/')
                            localDir = if (idx <= 0) "/" else t.substring(0, idx)
                        })
                    }
                }
                loadErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
                SecondaryPillButton("上传当前目录", enabled = loadErr == null, onClick = { onPickFile(localDir) })
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (f.directory) localDir = f.path else onPickFile(f.path)
                                }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (f.directory) GaliaisIcons.Folder else GaliaisIcons.File,
                                contentDescription = null,
                                tint = if (f.directory) G.accent else G.inkMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(f.name, fontFamily = Mono, fontSize = 12.sp, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = G.inkMuted) }
        },
    )
}

private fun formatRemoteBytes(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> String.format("%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
