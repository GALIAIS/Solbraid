package com.galiais.agent.engine

import android.util.Log
import com.galiais.agent.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Solbraid 统一环境中心与完整 SSH 套件仓库：
 * - 管理全局/项目环境变量（含 Keystore 加密 Secret）、PATH、Shell 别名/启动片段与 Git 全局身份
 * - 管理 SSH 主机簿（自动无损同步 ~/.ssh/config）、多密钥生成/导入、后台隧道与 SFTP 传输
 */
class EnvSshRepository(
    private val runtime: RuntimeClient,
    private val settings: AppSettings,
    private val onNotify: (String) -> Unit,
    private val onReloadCodexMcp: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val envSyncMutex = Mutex()
    private val sshSyncMutex = Mutex()
    private var syncedEnvironmentPayload: String? = null

    private val envConfigFlow = MutableStateFlow(EnvironmentHubConfig.fromJson(settings.envHubConfigJson()))
    val envConfig: StateFlow<EnvironmentHubConfig> = envConfigFlow.asStateFlow()

    private val sshHostsFlow = MutableStateFlow(loadHosts())
    val sshHosts: StateFlow<List<SshHostConfig>> = sshHostsFlow.asStateFlow()

    private val savedTunnelsFlow = MutableStateFlow(loadSavedTunnels())
    val savedTunnels: StateFlow<List<SshTunnelPreset>> = savedTunnelsFlow.asStateFlow()

    private val sshKeysFlow = MutableStateFlow<List<SshKeyInfo>>(emptyList())
    val sshKeys: StateFlow<List<SshKeyInfo>> = sshKeysFlow.asStateFlow()

    private val activeTunnelsFlow = MutableStateFlow<List<SshActiveTunnel>>(emptyList())
    val activeTunnels: StateFlow<List<SshActiveTunnel>> = activeTunnelsFlow.asStateFlow()

    private val hostTestsFlow = MutableStateFlow<Map<String, SshHostTestResult>>(emptyMap())
    val hostTests: StateFlow<Map<String, SshHostTestResult>> = hostTestsFlow.asStateFlow()

    private val syncingFlow = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncingFlow.asStateFlow()
    private val transfersFlow = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers = transfersFlow.asStateFlow()
    private val envSyncErrorFlow = MutableStateFlow<String?>(null)
    val envSyncError = envSyncErrorFlow.asStateFlow()

    init {
        scope.launch {
            runtime.hubEvents.collect { packet ->
                if (packet.optString("method") == "ssh/transferChanged") {
                    val value = packet.getJSONObject("params").getJSONObject("transfer")
                    acceptTransfer(SftpTransfer.fromJson(value))
                }
                if (packet.optString("method") == "ssh/tunnelChanged") {
                    val params = packet.optJSONObject("params") ?: return@collect
                    activeTunnelsFlow.value = params.arr("tunnels").objects().mapNotNull {
                        runCatching { SshActiveTunnel.fromJson(it) }.getOrNull()
                    }
                    params.str("error")?.takeIf { it.isNotBlank() }?.let { err ->
                        onNotify("SSH 隧道已断开：${err.lineSequence().first().take(80)}")
                    }
                }
            }
        }
    }

    private fun loadHosts(): List<SshHostConfig> {
        val raw = settings.sshHostsJson() ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { runCatching { SshHostConfig.fromJson(it) }.getOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadSavedTunnels(): List<SshTunnelPreset> {
        val raw = settings.sshTunnelsJson() ?: return emptyList()
        return try {
            JSONArray(raw).objects().mapNotNull { runCatching { SshTunnelPreset.fromJson(it) }.getOrNull() }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun syncAllOnReady(): Boolean {
        if (!runtime.hubAvailable) return false
        return listOf(
            reportFailure("同步环境中心失败") { syncEnvironmentNow() },
            reportFailure("同步 SSH 配置失败") { syncSshNow() },
            reportFailure("读取 SSH 密钥失败") { refreshSshKeys() },
            reportFailure("读取 SSH 隧道失败") { refreshActiveTunnels() },
            reportFailure("读取传输状态失败") { refreshTransfers() },
        ).all { it }
    }

    private suspend fun reportFailure(label: String, action: suspend () -> Unit): Boolean {
        return try { action(); true }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            Log.w("GALIAIS", label, e)
            onNotify("$label：${e.message ?: "请重试"}")
            false
        }
    }

    fun resetRuntimeState() {
        activeTunnelsFlow.value = emptyList()
        syncedEnvironmentPayload = null
        transfersFlow.value = transfersFlow.value.map { if (it.active) it.copy(phase = "failed", error = "运行环境已断开，请检查文件后重试") else it }
    }

    // ── 完整环境中心 ─────────────────────────────────────────────────────────

    private fun commitEnv(transform: (EnvironmentHubConfig) -> EnvironmentHubConfig) {
        val next = transform(envConfigFlow.value)
        envConfigFlow.value = next
        settings.setEnvHubConfigJson(next.toJson().toString())
        if (runtime.snapshot.value.ready) {
            scope.launch { reportFailure("环境配置已保存，但同步失败") { syncEnvironmentNow() } }
        }
    }

    suspend fun syncEnvironmentNow(force: Boolean = false): JSONObject = envSyncMutex.withLock {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncingFlow.value = true
        try {
            val payload = jsonOf(
                "config" to envConfigFlow.value.toJson(),
                "secrets" to settings.exportBucketSecretsJson(ENV_SECRET_BUCKET),
            )
            val fingerprint = payload.toString()
            if (!force && fingerprint == syncedEnvironmentPayload) return@withLock jsonOf("ok" to true)
            val result = runtime.hubRequest("env/sync", payload, timeoutMs = 15_000)
            runCatching { onReloadCodexMcp() }.onFailure { Log.w("GALIAIS", "重载 Codex MCP 配置失败（已忽略，不影响环境同步）", it) }
            syncedEnvironmentPayload = fingerprint
            envSyncErrorFlow.value = null
            result
        } catch (cancel: CancellationException) { throw cancel
        } catch (error: Exception) {
            envSyncErrorFlow.value = error.message ?: "同步失败，请重试"
            throw error
        } finally {
            syncingFlow.value = false
        }
    }

    suspend fun syncBeforeLaunch() {
        val config = envConfigFlow.value
        if (!runtime.hubAvailable) {
            check(config.vars.none { it.enabled } && config.extraPaths.isEmpty()) { "环境中心未就绪，无法应用当前环境变量" }
            return
        }
        syncEnvironmentNow()
    }

    suspend fun effectiveEnvironment(cwd: String?): JSONObject {
        syncBeforeLaunch()
        if (!runtime.hubAvailable) return JSONObject()
        return runtime.hubRequest("env/effective", jsonOf("cwd" to cwd), timeoutMs = 10_000).getJSONObject("env")
    }

    fun upsertEnvVar(entry: EnvVarEntry, secretPlain: String? = null) {
        validateEnvEntry(entry)
        require(envConfigFlow.value.vars.none { it.id != entry.id && it.key == entry.key && it.scope == entry.scope }) { "此范围内已存在同名变量，请编辑原条目" }
        if (!entry.secret || secretPlain != null) settings.setExtensionSecret(ENV_SECRET_BUCKET, entry.id, secretPlain?.takeIf { entry.secret })
        commitEnv { cfg ->
            val cleaned = if (entry.secret) entry.copy(value = "") else entry
            cfg.copy(vars = cfg.vars.filterNot { it.id == entry.id } + cleaned)
        }
        onNotify("已保存环境变量：${entry.key}")
    }

    fun toggleEnvVar(id: String, enabled: Boolean) {
        commitEnv { cfg -> cfg.copy(vars = cfg.vars.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    fun deleteEnvVar(id: String) {
        settings.setExtensionSecret(ENV_SECRET_BUCKET, id, null)
        commitEnv { cfg -> cfg.copy(vars = cfg.vars.filterNot { it.id == id }) }
        onNotify("已删除环境变量")
    }

    fun hasEnvSecret(id: String): Boolean = settings.hasExtensionSecret(ENV_SECRET_BUCKET, id)

    fun importDotEnv(rawText: String, scopeTag: String = "global", treatSensitiveAsSecret: Boolean = true): Int {
        val pairs = parseDotEnvLines(rawText)
        if (pairs.isEmpty()) return 0
        pairs.forEach { (key, value) -> validateEnvEntry(EnvVarEntry("", key, value, scope = scopeTag)) }
        val sensitiveRe = Regex("(TOKEN|SECRET|KEY|PASSWORD|PASS|AUTH)", RegexOption.IGNORE_CASE)
        require(pairs.none { (key, value) -> treatSensitiveAsSecret && sensitiveRe.containsMatchIn(key) && value.length > 8192 }) { "敏感值不能超过 8192 字符" }
        commitEnv { cfg ->
            val list = cfg.vars.toMutableList()
            for ((k, v) in pairs) {
                val existingIdx = list.indexOfFirst { it.key == k && it.scope == scopeTag }
                val isSecret = treatSensitiveAsSecret && sensitiveRe.containsMatchIn(k)
                val id = if (existingIdx >= 0) list[existingIdx].id else newId().take(10)
                settings.setExtensionSecret(ENV_SECRET_BUCKET, id, v.takeIf { isSecret })
                val nextEntry = EnvVarEntry(id = id, key = k, value = if (isSecret) "" else v, secret = isSecret, enabled = true, scope = scopeTag)
                if (existingIdx >= 0) list[existingIdx] = nextEntry else list += nextEntry
            }
            cfg.copy(vars = list)
        }
        onNotify("已导入 ${pairs.size} 条环境变量")
        return pairs.size
    }

    fun exportNonSecretDotEnv(scopeFilter: String? = null): String = buildString {
        envConfigFlow.value.vars
            .filter { scopeFilter == null || it.scope == scopeFilter }
            .forEach { v ->
                if (v.secret) appendLine("# ${v.key}=<encrypted-in-keystore>")
                else appendLine((if (v.enabled) "" else "# ") + v.key + "=" + encodeDotEnvValue(v.value))
            }
    }

    suspend fun updateShellAndGit(extraPaths: List<String>, aliases: List<ShellAlias>, shellSnippet: String, git: GitIdentityConfig) {
        require(extraPaths.all { it.isNotBlank() && it.none { c -> c in ":\r\n\u0000" } }) { "每行应为一个目录，不能包含冒号或控制字符" }
        require(aliases.all { Regex("^[A-Za-z0-9_.-]{1,40}$").matches(it.name) && it.command.isNotBlank() && it.command.none { c -> c in "\r\n\u0000" } }) { "别名应为 name=command，每行一条" }
        require(aliases.map { it.name }.distinct().size == aliases.size) { "别名重复" }
        require(shellSnippet.toByteArray(Charsets.UTF_8).size <= 8192 && '\u0000' !in shellSnippet) { "Shell 启动片段无效或超过 8 KB" }
        require(git.autoCrlf in setOf("", "true", "false", "input")) { "core.autocrlf 只能为 true、false 或 input" }
        if (runtime.hubAvailable && shellSnippet.isNotBlank()) {
            runtime.hubRequest("env/validateShell", jsonOf("shellSnippet" to shellSnippet), timeoutMs = 10_000)
        }
        commitEnv { cfg ->
            cfg.copy(
                extraPaths = extraPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                aliases = aliases.filter { it.name.isNotBlank() && it.command.isNotBlank() },
                shellSnippet = shellSnippet,
                git = git,
            )
        }
        if (runtime.hubAvailable) {
            syncEnvironmentNow()
            onNotify("已保存并同步 Shell 与 Git 配置")
        } else {
            syncedEnvironmentPayload = null
            onNotify("已保存 Shell 与 Git 配置，启动运行环境后自动同步")
        }
    }

    suspend fun fetchRuntimeEnvSnapshot(cwd: String? = null): List<RuntimeEnvEntry> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("env/snapshot", jsonOf("cwd" to cwd), timeoutMs = 10_000)
        return res.arr("entries").objects().map {
            RuntimeEnvEntry(it.getString("key"), it.optString("value", ""), it.optBoolean("masked", false))
        }
    }

    suspend fun readProjectDotEnv(cwd: String): ProjectDotEnvSnapshot {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("env/readDotEnv", jsonOf("cwd" to cwd), timeoutMs = 10_000)
        return ProjectDotEnvSnapshot(res.optBoolean("exists"), res.getString("content"), res.getString("revision"))
    }

    suspend fun writeProjectDotEnv(cwd: String, content: String, expectedRevision: String): String = scope.async {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("env/writeDotEnv", jsonOf("cwd" to cwd, "content" to content, "expectedRevision" to expectedRevision), timeoutMs = 10_000)
        onNotify("已保存工作区 .env")
        res.getString("revision")
    }.await()

    // ── SSH 多密钥、主机簿、隧道与 SFTP ──────────────────────────────────────

    private fun persistHosts(list: List<SshHostConfig>) {
        sshHostsFlow.value = list
        settings.setSshHostsJson(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        if (runtime.snapshot.value.ready) {
            scope.launch { reportFailure("SSH 配置已保存，但同步失败") { syncSshNow() } }
        }
    }

    suspend fun syncSshNow(): JSONObject = sshSyncMutex.withLock {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val hostsArr = JSONArray().apply { sshHostsFlow.value.forEach { put(it.toJson()) } }
        val payload = jsonOf(
            "hosts" to hostsArr,
            "passwords" to settings.exportBucketSecretsJson(SSH_SECRET_BUCKET),
            "keyPassphrases" to settings.exportBucketSecretsJson(SSH_KEY_SECRET_BUCKET),
        )
        runtime.hubRequest("ssh/sync", payload, timeoutMs = 15_000)
    }

    fun upsertSshHost(host: SshHostConfig, passwordPlain: String? = null) {
        require(sshHostsFlow.value.none { it.id != host.id && it.alias.equals(host.alias, ignoreCase = true) }) { "SSH 别名已存在，请更换别名" }
        if (host.authType == "password" && !passwordPlain.isNullOrEmpty()) {
            settings.setExtensionSecret(SSH_SECRET_BUCKET, host.id, passwordPlain)
        } else if (host.authType != "password") {
            settings.setExtensionSecret(SSH_SECRET_BUCKET, host.id, null)
        }
        persistHosts(sshHostsFlow.value.filterNot { it.id == host.id } + host)
        onNotify("已保存 SSH 主机：${host.name}（ssh ${host.alias}）")
    }

    fun deleteSshHost(id: String) {
        settings.setExtensionSecret(SSH_SECRET_BUCKET, id, null)
        hostTestsFlow.value = hostTestsFlow.value - id
        val orphanTunnels = savedTunnelsFlow.value.filter { it.hostId == id }
        val activeOrphans = activeTunnelsFlow.value.filter { it.hostId == id }
        if (orphanTunnels.isNotEmpty()) {
            val remaining = savedTunnelsFlow.value.filterNot { it.hostId == id }
            savedTunnelsFlow.value = remaining
            settings.setSshTunnelsJson(JSONArray().apply { remaining.forEach { put(it.toJson()) } }.toString())
        }
        if (activeOrphans.isNotEmpty() && runtime.hubAvailable) {
            scope.launch {
                activeOrphans.forEach { t -> runCatching { stopTunnel(t.id) } }
            }
        }
        persistHosts(sshHostsFlow.value.filterNot { it.id == id })
        onNotify("已删除 SSH 主机")
    }

    fun hasSshPassword(hostId: String): Boolean = settings.hasExtensionSecret(SSH_SECRET_BUCKET, hostId)

    suspend fun testSshHost(host: SshHostConfig): SshHostTestResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        val res = runtime.hubRequest("ssh/test", jsonOf("hostId" to host.id, "alias" to host.alias), timeoutMs = 25_000)
        val parsed = SshHostTestResult(
            ok = res.optBoolean("ok"),
            latencyMs = res.optInt("latencyMs"),
            remoteInfo = res.optString("remoteInfo", ""),
            error = res.optString("error", ""),
        )
        hostTestsFlow.value = hostTestsFlow.value + (host.id to parsed)
        return parsed
    }

    suspend fun deployKeyToHost(host: SshHostConfig, keyName: String = "id_ed25519") {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        runtime.hubRequest("ssh/deployKey", jsonOf("hostId" to host.id, "alias" to host.alias, "keyName" to keyName), timeoutMs = 28_000)
        onNotify("已部署公钥 $keyName 到 ${host.alias}，现在可切换密钥免密登录")
    }

    suspend fun execSshCommand(host: SshHostConfig, command: String, remoteCwd: String = ""): SshExecResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        val res = runtime.hubRequest("ssh/exec", jsonOf("hostId" to host.id, "alias" to host.alias, "command" to command, "remoteCwd" to remoteCwd), timeoutMs = 70_000)
        return SshExecResult(
            exitCode = res.optInt("exitCode", 1),
            stdout = res.optString("stdout", ""),
            stderr = res.optString("stderr", ""),
            durationMs = res.optInt("durationMs", 0),
        )
    }

    suspend fun refreshSshKeys(): List<SshKeyInfo> {
        if (!runtime.hubAvailable) return emptyList()
        val res = runtime.hubRequest("ssh/listKeys", JSONObject(), timeoutMs = 15_000)
        val list = res.arr("keys").objects().mapNotNull { runCatching { SshKeyInfo.fromJson(it) }.getOrNull() }
        sshKeysFlow.value = list
        return list
    }

    suspend fun generateSshKey(name: String, type: String = "ed25519", comment: String = "solbraid-mobile", passphrase: String = ""): SshKeyInfo? {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("ssh/generateKey", jsonOf("name" to name, "type" to type, "comment" to comment, "passphrase" to passphrase), timeoutMs = 30_000)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        syncSshNow()
        refreshSshKeys()
        onNotify("已生成密钥：$name")
        return res.optJSONObject("key")?.let(SshKeyInfo::fromJson)
    }

    suspend fun importSshKey(name: String, privateKey: String, publicKey: String = "", passphrase: String = ""): SshKeyInfo? {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("ssh/importKey", jsonOf("name" to name, "privateKey" to privateKey, "publicKey" to publicKey, "passphrase" to passphrase), timeoutMs = 15_000)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        syncSshNow()
        refreshSshKeys()
        onNotify("已导入密钥：$name")
        return res.optJSONObject("key")?.let(SshKeyInfo::fromJson)
    }

    suspend fun deleteSshKey(name: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("ssh/deleteKey", jsonOf("name" to name), timeoutMs = 10_000)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, null)
        syncSshNow()
        refreshSshKeys()
        onNotify("已删除密钥：$name")
    }

    fun hasSshKeyPassphrase(name: String) = settings.hasExtensionSecret(SSH_KEY_SECRET_BUCKET, name)

    suspend fun saveSshKeyPassphrase(name: String, passphrase: String) {
        check(runtime.hubAvailable) { "请先启动运行环境" }
        if (passphrase.isNotEmpty()) runtime.hubRequest("ssh/verifyKeyPassphrase", jsonOf("name" to name, "passphrase" to passphrase), timeoutMs = 15_000)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        syncSshNow()
    }

    suspend fun knownHosts(): List<SshKnownHost> =
        runtime.hubRequest("ssh/knownHosts", JSONObject(), timeoutMs = 10_000).arr("entries").objects().map {
            SshKnownHost(it.getString("id"), it.getString("hosts"), it.getString("keyType"), it.getString("fingerprint"), it.optString("marker"))
        }

    suspend fun removeKnownHost(id: String) { runtime.hubRequest("ssh/removeKnownHost", jsonOf("id" to id), timeoutMs = 10_000) }

    // ── SSH 隧道 ─────────────────────────────────────────────────────────────

    fun saveTunnelPreset(preset: SshTunnelPreset) {
        val next = savedTunnelsFlow.value.filterNot { it.id == preset.id } + preset
        savedTunnelsFlow.value = next
        settings.setSshTunnelsJson(JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
        onNotify("已保存隧道配置：${preset.name}")
    }

    fun deleteTunnelPreset(id: String) {
        scope.launch {
            reportFailure("删除隧道失败") {
                stopTunnel(id)
                val next = savedTunnelsFlow.value.filterNot { it.id == id }
                settings.setSshTunnelsJson(JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
                savedTunnelsFlow.value = next
            }
        }
    }

    suspend fun refreshActiveTunnels() {
        if (!runtime.hubAvailable) return
        val res = runtime.hubRequest("ssh/listTunnels", JSONObject(), timeoutMs = 10_000)
        activeTunnelsFlow.value = res.arr("tunnels").objects().mapNotNull { runCatching { SshActiveTunnel.fromJson(it) }.getOrNull() }
    }

    suspend fun startTunnel(preset: SshTunnelPreset) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        runtime.hubRequest(
            "ssh/startTunnel",
            jsonOf(
                "id" to preset.id,
                "hostId" to preset.hostId,
                "alias" to preset.alias,
                "mode" to preset.mode,
                "localPort" to preset.localPort,
                "remoteHost" to preset.remoteHost,
                "remotePort" to preset.remotePort,
            ),
            timeoutMs = 20_000,
        )
        refreshActiveTunnels()
        onNotify("隧道已启动：${preset.name}")
    }

    suspend fun stopTunnel(id: String) {
        if (!runtime.hubAvailable) {
            check(activeTunnelsFlow.value.none { it.id == id }) { "运行环境未连接，无法确认隧道已停止" }
            return
        }
        runtime.hubRequest("ssh/stopTunnel", jsonOf("id" to id), timeoutMs = 10_000)
        refreshActiveTunnels()
    }

    // ── SFTP 远端文件浏览器 ──────────────────────────────────────────────────

    suspend fun sftpList(host: SshHostConfig, remotePath: String = "."): Pair<String, List<SshRemoteEntry>> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        val res = runtime.hubRequest("ssh/sftpList", jsonOf("hostId" to host.id, "alias" to host.alias, "remotePath" to remotePath), timeoutMs = 30_000)
        val resolved = res.optString("path", remotePath)
        val list = res.arr("entries").objects().mapNotNull { runCatching { SshRemoteEntry.fromJson(it) }.getOrNull() }
        return resolved to list
    }

    private fun acceptTransfer(transfer: SftpTransfer) {
        transfersFlow.value = (transfersFlow.value.filterNot { it.id == transfer.id } + transfer).takeLast(20)
    }

    suspend fun refreshTransfers() {
        val result = runtime.hubRequest("ssh/transfers", JSONObject(), timeoutMs = 10_000)
        transfersFlow.value = result.arr("transfers").objects().map(SftpTransfer::fromJson)
    }

    suspend fun startTransfer(host: SshHostConfig, direction: String, localPath: String, remotePath: String) {
        check(runtime.hubAvailable) { "请先启动运行环境" }
        syncSshNow()
        val result = runtime.hubRequest("ssh/startTransfer", jsonOf("hostId" to host.id, "direction" to direction,
            "localPath" to localPath, "remotePath" to remotePath), timeoutMs = 20_000)
        acceptTransfer(SftpTransfer.fromJson(result.getJSONObject("transfer")))
    }

    suspend fun cancelTransfer(id: String) { runtime.hubRequest("ssh/cancelTransfer", jsonOf("id" to id), timeoutMs = 10_000) }

    suspend fun sftpRename(host: SshHostConfig, path: String, newName: String) {
        runtime.hubRequest("ssh/sftpRename", jsonOf("hostId" to host.id, "remotePath" to path, "newName" to newName), timeoutMs = 60_000)
    }
    suspend fun sftpChmod(host: SshHostConfig, path: String, mode: String) {
        runtime.hubRequest("ssh/sftpChmod", jsonOf("hostId" to host.id, "remotePath" to path, "mode" to mode), timeoutMs = 40_000)
    }
    suspend fun sftpReadText(host: SshHostConfig, path: String): SftpTextDocument {
        val response = runtime.hubRequest("ssh/sftpReadText", jsonOf("hostId" to host.id, "remotePath" to path), timeoutMs = 65_000)
        return SftpTextDocument(response.getString("content"), response.getString("revision"))
    }
    suspend fun sftpWriteText(host: SshHostConfig, path: String, content: String, revision: String): String {
        return runtime.hubRequest("ssh/sftpWriteText", jsonOf("hostId" to host.id, "remotePath" to path,
            "content" to content, "expectedRevision" to revision), timeoutMs = 180_000).getString("revision")
    }

    suspend fun sftpMkdir(host: SshHostConfig, remotePath: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        runtime.hubRequest("ssh/sftpMkdir", jsonOf("hostId" to host.id, "alias" to host.alias, "remotePath" to remotePath), timeoutMs = 25_000)
        onNotify("已创建远端文件夹")
    }

    suspend fun sftpDelete(host: SshHostConfig, remotePath: String, directory: Boolean) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        syncSshNow()
        runtime.hubRequest("ssh/sftpDelete", jsonOf("hostId" to host.id, "alias" to host.alias, "remotePath" to remotePath, "directory" to directory), timeoutMs = 25_000)
        onNotify("已删除远端项目")
    }

    companion object {
        const val ENV_SECRET_BUCKET = "envhub"
        const val SSH_SECRET_BUCKET = "sshhost"
        const val SSH_KEY_SECRET_BUCKET = "sshkey"
    }
}
