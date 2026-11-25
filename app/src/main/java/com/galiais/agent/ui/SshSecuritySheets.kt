package com.galiais.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.galiais.agent.GaliaisApplication
import com.galiais.agent.engine.SshKnownHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SshKeyPassphraseDialog(app: GaliaisApplication, name: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(phrase: String) {
        busy = true
        scope.launch {
            try { app.envSsh.saveSshKeyPassphrase(name, phrase); onDismiss() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = e.message ?: "保存失败" }
            finally { busy = false }
        }
    }
    GaliaisAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "密钥口令 · $name",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("口令加密保存在本机，用于 SSH、SFTP 和隧道认证。", style = MaterialTheme.typography.bodySmall, color = G.inkMuted)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = !busy,
                    label = { Text("口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (app.envSsh.hasSshKeyPassphrase(name)) SecondaryPillButton("清除已保存口令", enabled = !busy, onClick = { save("") })
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            }
        },
        confirmButton = { TextButton(onClick = { save(value) }, enabled = !busy && value.isNotEmpty()) { Text(if (busy) "正在校验" else "保存", color = G.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = G.inkMuted) } },
    )
}

@Composable
fun KnownHostsSheet(app: GaliaisApplication, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SshKnownHost>>(emptyList()) }
    var nonce by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SshKnownHost?>(null) }
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(nonce) {
        loading = true
        error = null
        try { entries = app.envSsh.knownHosts() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "读取指纹失败" }
        finally { loading = false }
    }
    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("已知主机指纹", style = MaterialTheme.typography.titleLarge)
            SecondaryPillButton("刷新", enabled = !loading && !removing, onClick = { nonce++ })
            error?.let { Text(it, color = G.danger) }
            HeaderProgressSlot(loading)
            if (!loading && entries.isEmpty() && error == null) Text("尚无已记录的主机", color = G.inkMuted)
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    CrispCard(padding = PaddingValues(12.dp)) {
                        Text(if (entry.hosts.startsWith("|1|")) "已加密的主机名" else entry.hosts, style = MaterialTheme.typography.titleSmall)
                        Text(entry.keyType + if (entry.marker.isBlank()) "" else " · " + entry.marker, color = G.inkMuted)
                        Text(entry.fingerprint, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                        SecondaryPillButton("移除此指纹", danger = true, enabled = !removing, onClick = { deleting = entry })
                    }
                }
            }
        }
    }
    deleting?.let { entry ->
        GaliaisConfirmDialog(
            title = "移除此主机指纹？",
            message = "下次连接将重新记录主机密钥。请先核对服务器身份，避免信任被替换的主机。",
            confirmText = "移除",
            danger = true,
            confirmEnabled = !removing,
            onConfirm = {
                removing = true
                scope.launch {
                    try { app.envSsh.removeKnownHost(entry.id); deleting = null; nonce++ }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { error = e.message ?: "移除失败"; deleting = null }
                    finally { removing = false }
                }
            },
            onDismiss = { if (!removing) deleting = null },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SshQuickExecSheet(
    app: GaliaisApplication,
    host: com.galiais.agent.engine.SshHostConfig,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<com.galiais.agent.engine.SshExecResult?>(null) }
    var lastCommand by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun execute(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty() || running) return
        command = clean
        lastCommand = clean
        running = true
        error = null
        scope.launch {
            try {
                lastResult = app.envSsh.execSshCommand(host, clean, host.defaultPath)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "执行失败"
            } finally {
                running = false
            }
        }
    }

    GaliaisSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("远端快捷命令 · ${host.name}", style = MaterialTheme.typography.titleMedium, color = G.ink)
            Text("${host.user}@${host.host}:${host.port}" + if (host.defaultPath.isNotBlank()) " · ${host.defaultPath}" else "", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.inkMuted)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "系统负载" to "uname -a && uptime && free -h",
                    "磁盘空间" to "df -h",
                    "监听端口" to "ss -tulpn 2>/dev/null || netstat -tulpn 2>/dev/null",
                    "Docker" to "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'",
                    "GPU" to "nvidia-smi --query-gpu=name,memory.used,memory.total,utilization.gpu --format=csv",
                    "进程 Top" to "ps aux --sort=-%mem | head -n 15",
                ).forEach { (label, cmd) ->
                    TagPill(label, fg = G.accent, bg = G.accentContainer, onClick = { execute(cmd) })
                }
            }
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("远端 Shell 命令") },
                    singleLine = true,
                    shape = D.fieldShape,
                    colors = galiaisTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(if (running) "执行中…" else "执行", enabled = !running && command.isNotBlank(), onClick = { execute(command) })
            }
            HeaderProgressSlot(running)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = G.danger) }
            lastResult?.let { res ->
                val output = buildString {
                    if (res.stdout.isNotBlank()) append(res.stdout)
                    if (res.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(res.stderr)
                    }
                }.ifBlank { "（无输出）" }
                CrispCard(background = G.codeBg, borderColor = G.codeBorder, padding = PaddingValues(10.dp)) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("$ $lastCommand", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = G.codeInk, modifier = Modifier.weight(1f), maxLines = 1)
                        TagPill("exit ${res.exitCode} · ${res.durationMs}ms", fg = if (res.exitCode == 0) G.success else G.danger, bg = if (res.exitCode == 0) G.successTint else G.dangerTint)
                        TagPill("复制", fg = G.accent, bg = G.accentContainer, onClick = {
                            scope.launch {
                                clipboard.setClipEntry(android.content.ClipData.newPlainText("ssh-exec", output).toClipEntry())
                                app.conversations.notify("已复制远端输出")
                            }
                        })
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            output.takeLast(16_000),
                            fontFamily = Mono,
                            style = MaterialTheme.typography.bodySmall,
                            color = G.codeInk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}
