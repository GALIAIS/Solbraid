package com.galiais.agent.engine

import android.util.Log
import com.galiais.agent.LinuxRuntime
import com.galiais.agent.engine.ssh.NativeSftpClient
import com.galiais.agent.engine.ssh.NativeSshEngine
import com.galiais.agent.engine.ssh.NativeTerminalManager
import com.galiais.agent.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val containerTunnelsFlow = MutableStateFlow<List<SshActiveTunnel>>(emptyList())
    private val transferJobs = ConcurrentHashMap<String, Job>()

    val nativeSsh = NativeSshEngine(runtime.app, settings)
    val nativeSftp = NativeSftpClient(nativeSsh)
    val nativeTerminals = NativeTerminalManager(
        app = runtime.app,
        runtime = runtime,
        sshEngine = nativeSsh,
        allHostsProvider = { sshHostsFlow.value },
        prepareLocalRuntime = { syncBeforeLaunch() },
        onNotify = onNotify,
    )

    private val hostTestsFlow = MutableStateFlow<Map<String, SshHostTestResult>>(emptyMap())
    val hostTests: StateFlow<Map<String, SshHostTestResult>> = hostTestsFlow.asStateFlow()

    private val syncingFlow = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncingFlow.asStateFlow()
    private val transfersFlow = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers = transfersFlow.asStateFlow()
    private val envSyncErrorFlow = MutableStateFlow<String?>(null)
    val envSyncError = envSyncErrorFlow.asStateFlow()

    private fun mergeActiveTunnels() {
        val nativeList = nativeSsh.activeNativeTunnels.value
        val nativeIds = nativeList.mapTo(HashSet()) { it.id }
        val containerOnly = containerTunnelsFlow.value.filterNot { it.id in nativeIds }
        activeTunnelsFlow.value = nativeList + containerOnly
    }

    init {
        scope.launch {
            nativeSsh.activeNativeTunnels.collect { mergeActiveTunnels() }
        }
        scope.launch {
            runCatching { refreshSshKeys() }
        }
        scope.launch {
            runtime.hubEvents.collect { packet ->
                if (packet.optString("method") == "ssh/transferChanged") {
                    val value = packet.getJSONObject("params").getJSONObject("transfer")
                    acceptTransfer(SftpTransfer.fromJson(value))
                }
                if (packet.optString("method") == "ssh/tunnelChanged") {
                    val params = packet.optJSONObject("params") ?: return@collect
                    containerTunnelsFlow.value = params.arr("tunnels").objects().mapNotNull {
                        runCatching { SshActiveTunnel.fromJson(it) }.getOrNull()
                    }
                    mergeActiveTunnels()
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
        containerTunnelsFlow.value = emptyList()
        mergeActiveTunnels()
        syncedEnvironmentPayload = null
        nativeTerminals.onLocalRuntimeDisconnected()
        transfersFlow.value = transfersFlow.value.map {
            if (it.active && !transferJobs.containsKey(it.id)) it.copy(phase = "failed", error = "运行环境已断开，请检查文件后重试") else it
        }
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
        // 安全加固：绝不再向 Linux 容器磁盘落盘明文密码或私钥口令；原生 SSH/SFTP 直接从 Android Keystore 内存读取。
        val payload = jsonOf(
            "hosts" to hostsArr,
            "passwords" to JSONObject(),
            "keyPassphrases" to JSONObject(),
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
        nativeSftp.closeHost(host.id)
        // 若主机别名发生修改，同步更新关联隧道预设的 alias，防止隧道失效
        val updatedTunnels = savedTunnelsFlow.value.map { t ->
            if (t.hostId == host.id && t.alias != host.alias) t.copy(alias = host.alias) else t
        }
        if (updatedTunnels != savedTunnelsFlow.value) {
            savedTunnelsFlow.value = updatedTunnels
            settings.setSshTunnelsJson(JSONArray().apply { updatedTunnels.forEach { put(it.toJson()) } }.toString())
        }
        persistHosts(sshHostsFlow.value.filterNot { it.id == host.id } + host)
        onNotify("已保存 SSH 主机：${host.name}（ssh ${host.alias}）")
    }

    fun deleteSshHost(id: String) {
        settings.setExtensionSecret(SSH_SECRET_BUCKET, id, null)
        nativeSftp.closeHost(id)
        hostTestsFlow.value = hostTestsFlow.value - id
        val orphanTunnels = savedTunnelsFlow.value.filter { it.hostId == id }
        val activeOrphans = activeTunnelsFlow.value.filter { it.hostId == id }
        if (orphanTunnels.isNotEmpty()) {
            val remaining = savedTunnelsFlow.value.filterNot { it.hostId == id }
            savedTunnelsFlow.value = remaining
            settings.setSshTunnelsJson(JSONArray().apply { remaining.forEach { put(it.toJson()) } }.toString())
        }
        if (activeOrphans.isNotEmpty()) {
            scope.launch {
                activeOrphans.forEach { t -> runCatching { stopTunnel(t.id) } }
            }
        }
        persistHosts(sshHostsFlow.value.filterNot { it.id == id })
        onNotify("已删除 SSH 主机")
    }

    fun hasSshPassword(hostId: String): Boolean = settings.hasExtensionSecret(SSH_SECRET_BUCKET, hostId)

    suspend fun testSshHost(host: SshHostConfig): SshHostTestResult {
        val parsed = nativeSsh.testHost(host, sshHostsFlow.value)
        hostTestsFlow.value = hostTestsFlow.value + (host.id to parsed)
        return parsed
    }

    suspend fun deployKeyToHost(host: SshHostConfig, keyName: String = "id_ed25519") {
        nativeSsh.deployPublicKey(host, sshHostsFlow.value, keyName)
        onNotify("已部署公钥 $keyName 到 ${host.alias}，现在可切换密钥免密登录")
    }

    suspend fun execSshCommand(host: SshHostConfig, command: String, remoteCwd: String = ""): SshExecResult =
        nativeSsh.execCommand(host, sshHostsFlow.value, command, remoteCwd, interactive = true, timeoutMs = 45_000)

    suspend fun refreshSshKeys(): List<SshKeyInfo> {
        val list = nativeSsh.listKeys()
        sshKeysFlow.value = list
        return list
    }

    suspend fun generateSshKey(name: String, type: String = "ed25519", comment: String = "solbraid-mobile", passphrase: String = ""): SshKeyInfo? {
        val key = nativeSsh.generateKey(name, type, comment, passphrase)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        refreshSshKeys()
        onNotify("已生成密钥：$name")
        return key
    }

    suspend fun importSshKey(name: String, privateKey: String, publicKey: String = "", passphrase: String = ""): SshKeyInfo? {
        val key = nativeSsh.importKey(name, privateKey, publicKey, passphrase)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        refreshSshKeys()
        onNotify("已导入密钥：$name")
        return key
    }

    suspend fun deleteSshKey(name: String) {
        nativeSsh.deleteKey(name)
        if (runtime.hubAvailable) {
            runCatching { runtime.hubRequest("ssh/deleteKey", jsonOf("name" to name), timeoutMs = 8_000) }
        }
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, null)
        refreshSshKeys()
        onNotify("已删除密钥：$name")
    }

    fun hasSshKeyPassphrase(name: String) = settings.hasExtensionSecret(SSH_KEY_SECRET_BUCKET, name)

    suspend fun saveSshKeyPassphrase(name: String, passphrase: String) {
        if (passphrase.isNotEmpty()) nativeSsh.verifyKeyPassphrase(name, passphrase)
        settings.setExtensionSecret(SSH_KEY_SECRET_BUCKET, name, passphrase)
        onNotify(if (passphrase.isEmpty()) "已清除密钥口令" else "已验证并加密保存密钥口令")
    }

    suspend fun knownHosts(): List<SshKnownHost> = nativeSsh.listKnownHosts()

    suspend fun removeKnownHost(id: String) {
        nativeSsh.removeKnownHost(id)
    }

    // ── SSH 隧道（-L / -R 原生 JSch 无需本地 Linux，-D SOCKS5 使用容器 OpenSSH） ─────

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
        if (runtime.hubAvailable) {
            val res = runtime.hubRequest("ssh/listTunnels", JSONObject(), timeoutMs = 10_000)
            containerTunnelsFlow.value = res.arr("tunnels").objects().mapNotNull { runCatching { SshActiveTunnel.fromJson(it) }.getOrNull() }
        }
        mergeActiveTunnels()
    }

    suspend fun startTunnel(preset: SshTunnelPreset) {
        val host = sshHostsFlow.value.firstOrNull { it.id == preset.hostId } ?: error("未找到关联的 SSH 主机配置")
        if (preset.mode == "local" || preset.mode == "remote") {
            nativeSsh.startNativeTunnel(preset, host, sshHostsFlow.value)
            mergeActiveTunnels()
            onNotify("原生隧道已启动：${preset.name}")
            return
        }
        check(runtime.hubAvailable) { "SOCKS5 动态代理 (-D) 需先启动本地运行环境；-L / -R 端口转发可直接原生运行" }
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
        nativeSsh.stopNativeTunnel(id)
        if (containerTunnelsFlow.value.any { it.id == id } && runtime.hubAvailable) {
            runtime.hubRequest("ssh/stopTunnel", jsonOf("id" to id), timeoutMs = 10_000)
            refreshActiveTunnels()
        } else {
            mergeActiveTunnels()
        }
    }

    // ── SFTP 远端文件浏览器（基于原生连接复用池，不依赖本地 Linux 运行时） ──────────

    private fun resolveLocalTransferFile(path: String): File {
        val clean = path.trim()
        if (clean.startsWith("/storage/") || clean.startsWith("/sdcard/") || clean.startsWith(runtime.app.filesDir.absolutePath)) {
            return File(clean)
        }
        val direct = File(clean)
        if (direct.exists()) return direct
        val mode = settings.state.value.effectiveRuntimeMode.ifBlank { RUNTIME_MODE_ROOT }
        return File(LinuxRuntime(runtime.app, mode).root, clean.trimStart('/'))
    }

    suspend fun sftpList(host: SshHostConfig, remotePath: String = "."): Pair<String, List<SshRemoteEntry>> =
        nativeSftp.list(host, sshHostsFlow.value, remotePath)

    private fun acceptTransfer(transfer: SftpTransfer) {
        transfersFlow.value = (transfersFlow.value.filterNot { it.id == transfer.id } + transfer).takeLast(20)
    }

    suspend fun refreshTransfers() {
        if (!runtime.hubAvailable) return
        val result = runtime.hubRequest("ssh/transfers", JSONObject(), timeoutMs = 10_000)
        val remoteTransfers = result.arr("transfers").objects().map(SftpTransfer::fromJson)
        val nativeOnly = transfersFlow.value.filter { it.id.startsWith("nat-") }
        transfersFlow.value = (remoteTransfers + nativeOnly).takeLast(20)
    }

    suspend fun startTransfer(host: SshHostConfig, direction: String, localPath: String, remotePath: String) {
        val transferId = "nat-${newId().take(8)}"
        val name = if (direction == "upload") localPath.trimEnd('/').substringAfterLast('/') else remotePath.trimEnd('/').substringAfterLast('/')
        val initial = SftpTransfer(
            id = transferId,
            hostId = host.id,
            direction = direction,
            localPath = localPath,
            remotePath = remotePath,
            phase = "running",
            progressPercent = 0,
            progressLabel = name,
            error = null,
            resultPath = null,
        )
        acceptTransfer(initial)
        val job = scope.launch(Dispatchers.IO) {
            try {
                if (direction == "upload") {
                    val srcFile = resolveLocalTransferFile(localPath)
                    require(srcFile.exists()) { "本地文件不存在：$localPath" }
                    if (srcFile.isDirectory) {
                        val children = srcFile.walkTopDown().filter { it.isFile }.toList()
                        val total = children.size.coerceAtLeast(1)
                        val baseRemote = "${remotePath.trimEnd('/')}/${srcFile.name}"
                        runCatching { nativeSftp.mkdir(host, sshHostsFlow.value, baseRemote) }
                        children.forEachIndexed { idx, file ->
                            val relDir = file.parentFile?.relativeTo(srcFile)?.path?.replace('\\', '/').orEmpty()
                            val targetDir = if (relDir.isEmpty()) baseRemote else "$baseRemote/$relDir"
                            if (relDir.isNotEmpty()) runCatching { nativeSftp.mkdir(host, sshHostsFlow.value, targetDir) }
                            nativeSftp.uploadFile(host, sshHostsFlow.value, file, targetDir) { pct ->
                                val overall = ((idx * 100 + pct) / total).coerceIn(0, 100)
                                acceptTransfer(initial.copy(progressPercent = overall, progressLabel = "${file.name} (${idx + 1}/$total)"))
                            }
                        }
                        acceptTransfer(initial.copy(phase = "completed", progressPercent = 100, resultPath = baseRemote))
                    } else {
                        val dest = nativeSftp.uploadFile(host, sshHostsFlow.value, srcFile, remotePath) { pct ->
                            acceptTransfer(initial.copy(progressPercent = pct, progressLabel = srcFile.name))
                        }
                        acceptTransfer(initial.copy(phase = "completed", progressPercent = 100, resultPath = dest))
                    }
                    onNotify("上传完成：$name")
                } else {
                    val destDir = resolveLocalTransferFile(localPath).apply { mkdirs() }
                    val destFile = File(destDir, name.ifBlank { "download.bin" })
                    nativeSftp.downloadFile(host, sshHostsFlow.value, remotePath, destFile) { pct ->
                        acceptTransfer(initial.copy(progressPercent = pct, progressLabel = name))
                    }
                    acceptTransfer(initial.copy(phase = "completed", progressPercent = 100, resultPath = destFile.absolutePath))
                    onNotify("已下载：$name")
                }
            } catch (_: CancellationException) {
                acceptTransfer(initial.copy(phase = "cancelled", error = "用户已取消"))
            } catch (e: Exception) {
                acceptTransfer(initial.copy(phase = "failed", error = e.message ?: "传输失败"))
            } finally {
                transferJobs.remove(transferId)
            }
        }
        transferJobs[transferId] = job
    }

    suspend fun cancelTransfer(id: String) {
        val job = transferJobs.remove(id)
        if (job != null) {
            job.cancel()
            return
        }
        if (runtime.hubAvailable) {
            runtime.hubRequest("ssh/cancelTransfer", jsonOf("id" to id), timeoutMs = 10_000)
        }
    }

    suspend fun sftpRename(host: SshHostConfig, path: String, newName: String) {
        nativeSftp.rename(host, sshHostsFlow.value, path, newName)
    }

    suspend fun sftpChmod(host: SshHostConfig, path: String, mode: String) {
        nativeSftp.chmod(host, sshHostsFlow.value, path, mode)
    }

    suspend fun sftpReadText(host: SshHostConfig, path: String): SftpTextDocument =
        nativeSftp.readText(host, sshHostsFlow.value, path)

    suspend fun sftpWriteText(host: SshHostConfig, path: String, content: String, revision: String): String =
        nativeSftp.writeText(host, sshHostsFlow.value, path, content, revision)

    suspend fun sftpMkdir(host: SshHostConfig, remotePath: String) {
        nativeSftp.mkdir(host, sshHostsFlow.value, remotePath)
        onNotify("已创建远端文件夹")
    }

    suspend fun sftpDelete(host: SshHostConfig, remotePath: String, directory: Boolean) {
        nativeSftp.deleteRecursive(host, sshHostsFlow.value, remotePath, directory)
        onNotify("已删除远端项目")
    }

    companion object {
        const val ENV_SECRET_BUCKET = "envhub"
        const val SSH_SECRET_BUCKET = "sshhost"
        const val SSH_KEY_SECRET_BUCKET = "sshkey"
    }
}
