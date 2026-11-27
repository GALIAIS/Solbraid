package com.galiais.agent.engine.ssh

import android.app.Application
import android.util.Base64
import com.galiais.agent.LinuxRuntime
import com.galiais.agent.engine.AppSettings
import com.galiais.agent.engine.EnvSshRepository
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import com.galiais.agent.engine.SshActiveTunnel
import com.galiais.agent.engine.SshExecResult
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshHostTestResult
import com.galiais.agent.engine.SshKeyInfo
import com.galiais.agent.engine.SshKnownHost
import com.galiais.agent.engine.SshTunnelPreset
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 固定容量尾部保留字节流：防止远端命令海量输出导致 JVM OOM。 */
private class BoundedTailOutputStream(private val maxBytes: Int = 256 * 1024) : OutputStream() {
    private val buf = ByteArray(maxBytes)
    private var head = 0
    private var size = 0

    @Synchronized
    override fun write(b: Int) {
        if (size < maxBytes) {
            buf[(head + size) % maxBytes] = b.toByte()
            size++
        } else {
            buf[head] = b.toByte()
            head = (head + 1) % maxBytes
        }
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (len >= maxBytes) {
            System.arraycopy(b, off + len - maxBytes, buf, 0, maxBytes)
            head = 0
            size = maxBytes
            return
        }
        for (i in 0 until len) write(b[off + i].toInt())
    }

    @Synchronized
    fun toUtf8String(): String {
        if (size == 0) return ""
        val out = ByteArray(size)
        val firstPart = minOf(size, maxBytes - head)
        System.arraycopy(buf, head, out, 0, firstPart)
        if (firstPart < size) {
            System.arraycopy(buf, 0, out, firstPart, size - firstPart)
        }
        return String(out, Charsets.UTF_8)
    }
}

/** 交互式 SSH 握手提示（首次连接 TOFU 指纹确认 / 主机密钥变更告警 / 2FA OTP 或缺失密码输入）。 */
sealed interface PendingSshPrompt {
    val id: String

    data class HostKeyVerify(
        override val id: String,
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprintSha256: String,
        val isChangedMismatch: Boolean,
        val existingFingerprint: String?,
        internal val decision: CompletableDeferred<Boolean>,
    ) : PendingSshPrompt

    data class AuthChallenge(
        override val id: String,
        val title: String,
        val prompt: String,
        val secret: Boolean,
        internal val answer: CompletableDeferred<String?>,
    ) : PendingSshPrompt
}

/** 打开一条可能经过多跳 ProxyJump 跳板链的原生 JSch 会话；关闭时自动按逆序释放所有跳板连接。 */
class NativeSshSessionHandle(
    val session: Session,
    private val jumpSessions: List<Session>,
) : AutoCloseable {
    override fun close() {
        runCatching { if (session.isConnected) session.disconnect() }
        jumpSessions.asReversed().forEach { s -> runCatching { if (s.isConnected) s.disconnect() } }
    }
}

/**
 * 原生 Android SSH 引擎（基于 `com.github.mwiede:jsch`）：
 * - 彻底解耦本地 Linux 运行时：未启动 chroot/PRoot 时亦可直接连 SSH、浏览 SFTP、开关 `-L/-R` 隧道与管理密钥；
 * - 所有密码与私钥口令仅在内存中从 Android Keystore（`AppSettings`）读取，绝不向容器磁盘落盘明文；
 * - 内置交互式 TOFU 主机指纹确认 + 变更告警、2FA/OTP/PAM `keyboard-interactive` 认证与多跳 `ProxyJump`。
 */
class NativeSshEngine(
    private val app: Application,
    private val settings: AppSettings,
) {
    private val keysDir = File(app.filesDir, "ssh-keys").apply { mkdirs() }
    private val knownHostsFile = File(app.filesDir, "ssh-known-hosts").apply {
        if (!exists()) runCatching { createNewFile() }
    }
    private val promptMutex = Mutex()
    private val promptFlow = MutableStateFlow<PendingSshPrompt?>(null)
    val pendingPrompt: StateFlow<PendingSshPrompt?> = promptFlow.asStateFlow()

    private val nativeTunnels = ConcurrentHashMap<String, Pair<SshActiveTunnel, NativeSshSessionHandle>>()
    private val nativeTunnelsFlow = MutableStateFlow<List<SshActiveTunnel>>(emptyList())
    val activeNativeTunnels: StateFlow<List<SshActiveTunnel>> = nativeTunnelsFlow.asStateFlow()

    private val ephemeralPasswords = ConcurrentHashMap<String, String>()
    private val tunnelMonitor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Solbraid-SshTunnelMonitor").apply { isDaemon = true }
    }.also { exec ->
        exec.scheduleWithFixedDelay({
            if (nativeTunnels.isNotEmpty()) runCatching { publishNativeTunnels() }
        }, 15L, 15L, TimeUnit.SECONDS)
    }

    fun putEphemeralPassword(hostId: String, password: String) {
        if (hostId.isNotBlank() && password.isNotEmpty()) ephemeralPasswords[hostId] = password
    }

    fun respondHostKey(id: String, trust: Boolean) {
        val cur = promptFlow.value as? PendingSshPrompt.HostKeyVerify ?: return
        if (cur.id != id) return
        promptFlow.value = null
        cur.decision.complete(trust)
    }

    fun respondAuth(id: String, value: String?) {
        val cur = promptFlow.value as? PendingSshPrompt.AuthChallenge ?: return
        if (cur.id != id) return
        promptFlow.value = null
        cur.answer.complete(value)
    }

    private fun linuxSshDir(): File {
        val mode = settings.state.value.effectiveRuntimeMode.ifBlank { RUNTIME_MODE_ROOT }
        return File(LinuxRuntime(app, mode).root, "home/agent/.ssh")
    }

    private fun findPrivateKeyFile(keyName: String): File? {
        val safe = keyName.trim().ifEmpty { "id_ed25519" }
        if (!SAFE_KEY_NAME_RE.matches(safe)) return null
        val local = File(keysDir, safe)
        if (local.isFile && local.canRead()) return local
        val guest = File(linuxSshDir(), safe)
        if (guest.isFile && guest.canRead()) return guest
        return null
    }

    private fun sha256Fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun createConfiguredJsch(): JSch = JSch().apply {
        if (knownHostsFile.isFile) runCatching { setKnownHosts(knownHostsFile.absolutePath) }
    }

    private fun attachIdentity(jsch: JSch, keyName: String) {
        val priv = findPrivateKeyFile(keyName) ?: return
        val pub = File(priv.parentFile, "${priv.name}.pub").takeIf { it.isFile && it.canRead() }
        val pass = settings.extensionSecret(EnvSshRepository.SSH_KEY_SECRET_BUCKET, keyName)
            ?.takeIf { it.isNotEmpty() }
            ?.toByteArray(Charsets.UTF_8)
        runCatching {
            val privBytes = priv.readBytes()
            val pubBytes = pub?.readBytes()
            jsch.addIdentity(priv.name, privBytes, pubBytes, pass)
        }
    }

    private suspend fun askHostKeyVerification(
        host: String,
        port: Int,
        keyType: String,
        fp: String,
        changed: Boolean,
        oldFp: String?,
    ): Boolean = promptMutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        val prompt = PendingSshPrompt.HostKeyVerify(
            id = UUID.randomUUID().toString(),
            host = host,
            port = port,
            keyType = keyType,
            fingerprintSha256 = fp,
            isChangedMismatch = changed,
            existingFingerprint = oldFp,
            decision = deferred,
        )
        promptFlow.value = prompt
        val ok = withTimeoutOrNull(90_000L) { deferred.await() } ?: false
        if (promptFlow.value?.id == prompt.id) promptFlow.value = null
        ok
    }

    private suspend fun askAuthChallenge(title: String, promptText: String, secret: Boolean): String? = promptMutex.withLock {
        val deferred = CompletableDeferred<String?>()
        val prompt = PendingSshPrompt.AuthChallenge(
            id = UUID.randomUUID().toString(),
            title = title,
            prompt = promptText,
            secret = secret,
            answer = deferred,
        )
        promptFlow.value = prompt
        val ans = withTimeoutOrNull(90_000L) { deferred.await() }
        if (promptFlow.value?.id == prompt.id) promptFlow.value = null
        ans
    }

    private fun resolveJumpChain(target: SshHostConfig, allHosts: List<SshHostConfig>): List<SshHostConfig> {
        val byAlias = allHosts.associateBy { it.alias.lowercase() }
        val hops = mutableListOf<SshHostConfig>()
        val visited = mutableSetOf(target.id)
        fun walk(h: SshHostConfig) {
            if (h.proxyJump.isBlank()) return
            for (raw in h.proxyJump.split(',')) {
                val clean = raw.trim()
                if (clean.isEmpty()) continue
                val aliasKey = clean.substringAfter('@').substringBefore(':').lowercase()
                val matched = byAlias[aliasKey]
                    ?: throw IllegalArgumentException("未找到 ProxyJump 跳板机配置：$clean，请先在主机簿添加对应别名")
                require(visited.add(matched.id)) { "检测到 ProxyJump 跳板循环引用：$clean" }
                walk(matched)
                hops += matched
            }
        }
        walk(target)
        return hops
    }

    private fun connectSingleHop(
        jsch: JSch,
        host: SshHostConfig,
        connectHost: String,
        connectPort: Int,
        interactive: Boolean,
        timeoutMs: Int,
    ): Session {
        if (host.authType == "key" || host.keyName.isNotBlank()) {
            attachIdentity(jsch, host.keyName.ifBlank { "id_ed25519" })
        }
        val savedPassword = if (host.authType == "password") {
            ephemeralPasswords.remove(host.id) ?: settings.extensionSecret(EnvSshRepository.SSH_SECRET_BUCKET, host.id)
        } else null

        val session = jsch.getSession(host.user.ifBlank { "root" }, connectHost, connectPort)
        if (!savedPassword.isNullOrEmpty()) session.setPassword(savedPassword.toByteArray(Charsets.UTF_8))
        val cfg = Properties().apply {
            put("StrictHostKeyChecking", "ask")
            put("PreferredAuthentications", if (host.authType == "password") "password,keyboard-interactive,publickey" else "publickey,keyboard-interactive,password")
            put("ServerAliveCountMax", "3")
            put("MaxAuthTries", "4")
        }
        session.setConfig(cfg)
        if (host.keepAliveSec > 0) {
            session.serverAliveInterval = host.keepAliveSec * 1000
        }
        session.setHostKeyAlias(if (host.port == 22) host.host else "[${host.host}]:${host.port}")

        val khRepo = jsch.hostKeyRepository
        val hostLookup = if (host.port == 22) host.host else "[${host.host}]:${host.port}"

        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            private var promptPassword: String? = savedPassword
            private var promptPassphrase: String? = null

            override fun getPassphrase(): String? = promptPassphrase
            override fun getPassword(): String? = promptPassword

            override fun promptPassword(message: String?): Boolean {
                if (!promptPassword.isNullOrEmpty()) return true
                if (!interactive) return false
                val ans = runBlocking { askAuthChallenge("SSH 密码认证 · ${host.name}", message ?: "${host.user}@${host.host} 密码", secret = true) }
                promptPassword = ans
                return !ans.isNullOrEmpty()
            }

            override fun promptPassphrase(message: String?): Boolean {
                if (!interactive) return false
                val ans = runBlocking { askAuthChallenge("私钥口令 · ${host.keyName}", message ?: "请输入私钥口令", secret = true) }
                promptPassphrase = ans
                return !ans.isNullOrEmpty()
            }

            override fun promptYesNo(message: String?): Boolean {
                val hostKey = session.hostKey
                val fp = hostKey?.key?.let { runCatching { sha256Fingerprint(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
                    ?: hostKey?.getFingerPrint(jsch)
                    ?: (message ?: "")
                val keyType = hostKey?.type ?: "SSH"
                val existing = khRepo?.getHostKey(hostLookup, keyType)?.firstOrNull()
                val oldFp = existing?.key?.let { runCatching { sha256Fingerprint(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
                val checkState = if (hostKey != null && khRepo != null) {
                    khRepo.check(hostLookup, Base64.decode(hostKey.key, Base64.DEFAULT))
                } else HostKeyRepository.NOT_INCLUDED
                val changed = checkState == HostKeyRepository.CHANGED
                if (!interactive && !changed) {
                    // 非交互快速测速且非密钥变更场景：自动走 TOFU 信任并写入，密钥变更仍严格拦截！
                    if (hostKey != null && khRepo != null) {
                        runCatching { khRepo.add(HostKey(hostLookup, Base64.decode(hostKey.key, Base64.DEFAULT)), null) }
                    }
                    return true
                }
                val trusted = runBlocking { askHostKeyVerification(host.host, host.port, keyType, fp, changed, oldFp) }
                if (trusted && changed && hostKey != null && khRepo != null) {
                    runCatching {
                        khRepo.remove(hostLookup, keyType)
                        khRepo.add(HostKey(hostLookup, Base64.decode(hostKey.key, Base64.DEFAULT)), null)
                    }
                }
                return trusted
            }

            override fun showMessage(message: String?) {}

            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<out String>?,
                echo: BooleanArray?,
            ): Array<String>? {
                val prompts = prompt.orEmpty()
                if (prompts.isEmpty()) return emptyArray()
                // 若服务器单次只问 Password 且已存密码，自动填充；涉及 2FA/OTP/Verification Code 弹交互输入框
                if (prompts.size == 1 && !savedPassword.isNullOrEmpty() &&
                    prompts[0].contains("password", ignoreCase = true) &&
                    !prompts[0].contains("code", ignoreCase = true) &&
                    !prompts[0].contains("otp", ignoreCase = true)
                ) {
                    return arrayOf(savedPassword)
                }
                if (!interactive) return null
                val out = Array(prompts.size) { "" }
                for (i in prompts.indices) {
                    val isSecret = !(echo?.getOrNull(i) ?: false)
                    val title = name?.takeIf { it.isNotBlank() } ?: "SSH 交互认证（2FA / PAM）"
                    val detail = listOfNotNull(instruction?.takeIf { it.isNotBlank() }, prompts[i]).joinToString("\n")
                    val ans = runBlocking { askAuthChallenge(title, detail, secret = isSecret) } ?: return null
                    out[i] = ans
                }
                return out
            }
        }
        session.connect(timeoutMs)
        return session
    }

    /** 建立原生 SSH 会话（自动处理 ProxyJump 多跳转发、TOFU 指纹确认与 2FA 交互）。 */
    suspend fun openSession(
        target: SshHostConfig,
        allHosts: List<SshHostConfig>,
        interactive: Boolean = true,
        timeoutMs: Int = 15_000,
    ): NativeSshSessionHandle = withContext(Dispatchers.IO) {
        val jsch = createConfiguredJsch()
        val hops = resolveJumpChain(target, allHosts)
        val openedJumps = mutableListOf<Session>()
        try {
            var nextHost = target.host
            var nextPort = target.port
            if (hops.isNotEmpty()) {
                var prevSession: Session? = null
                for (hop in hops) {
                    val s = if (prevSession == null) {
                        connectSingleHop(jsch, hop, hop.host, hop.port, interactive, timeoutMs)
                    } else {
                        val lp = prevSession.setPortForwardingL(0, hop.host, hop.port)
                        connectSingleHop(jsch, hop, "127.0.0.1", lp, interactive, timeoutMs)
                    }
                    openedJumps += s
                    prevSession = s
                }
                val finalPort = prevSession!!.setPortForwardingL(0, target.host, target.port)
                nextHost = "127.0.0.1"
                nextPort = finalPort
            }
            val mainSession = connectSingleHop(jsch, target, nextHost, nextPort, interactive, timeoutMs)
            NativeSshSessionHandle(mainSession, openedJumps)
        } catch (e: Exception) {
            openedJumps.asReversed().forEach { s -> runCatching { if (s.isConnected) s.disconnect() } }
            throw e
        }
    }

    suspend fun testHost(host: SshHostConfig, allHosts: List<SshHostConfig>): SshHostTestResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val res = execCommand(host, allHosts, "uname -srm && hostname && pwd", interactive = true, timeoutMs = 15_000)
            val lines = res.stdout.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            SshHostTestResult(
                ok = res.exitCode == 0,
                latencyMs = (System.currentTimeMillis() - started).toInt(),
                remoteInfo = lines.joinToString(" · ").take(200),
                error = if (res.exitCode == 0) "" else res.stderr.ifBlank { "退出码 ${res.exitCode}" }.take(240),
            )
        } catch (e: Exception) {
            SshHostTestResult(
                ok = false,
                latencyMs = (System.currentTimeMillis() - started).toInt(),
                error = (e.message ?: "SSH 连接失败").take(260),
            )
        }
    }

    suspend fun execCommand(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        command: String,
        remoteCwd: String = "",
        interactive: Boolean = true,
        timeoutMs: Int = 30_000,
    ): SshExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val trimCwd = remoteCwd.trim()
        val fullCmd = when {
            trimCwd.isEmpty() -> command
            trimCwd == "~" -> "cd ~ && $command"
            trimCwd.startsWith("~/") -> "cd ~/'${trimCwd.removePrefix("~/").replace("'", "'\\''")}' && $command"
            else -> "cd '${trimCwd.replace("'", "'\\''")}' && $command"
        }
        openSession(host, allHosts, interactive = interactive, timeoutMs = minOf(timeoutMs, 15_000)).use { handle ->
            val ch = handle.session.openChannel("exec") as ChannelExec
            val outBuf = BoundedTailOutputStream(256 * 1024)
            val errBuf = BoundedTailOutputStream(128 * 1024)
            ch.setCommand(fullCmd)
            ch.outputStream = outBuf
            ch.setErrStream(errBuf)
            ch.connect(minOf(timeoutMs, 15_000))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(35)
            }
            val code = if (ch.isClosed) ch.exitStatus else -1
            runCatching { ch.disconnect() }
            SshExecResult(
                exitCode = code,
                stdout = outBuf.toUtf8String().takeLast(40_000),
                stderr = errBuf.toUtf8String().takeLast(20_000),
                durationMs = (System.currentTimeMillis() - started).toInt(),
            )
        }
    }

    suspend fun deployPublicKey(host: SshHostConfig, allHosts: List<SshHostConfig>, keyName: String) {
        val safe = keyName.trim().ifEmpty { "id_ed25519" }
        val pubFile = File(keysDir, "$safe.pub").takeIf { it.isFile }
            ?: File(linuxSshDir(), "$safe.pub").takeIf { it.isFile }
            ?: error("未找到公钥 $safe.pub，请先在「密钥」页生成")
        val pubKey = pubFile.readText(Charsets.UTF_8).trim()
        require(pubKey.isNotBlank()) { "公钥内容为空" }
        val escaped = pubKey.replace("'", "'\\''")
        val script = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "(grep -qxF '$escaped' ~/.ssh/authorized_keys || printf '%s\\n' '$escaped' >> ~/.ssh/authorized_keys) && echo SOLBRAID_KEY_OK"
        val res = execCommand(host, allHosts, script, interactive = true, timeoutMs = 20_000)
        check(res.exitCode == 0 && res.stdout.contains("SOLBRAID_KEY_OK")) { "部署公钥失败：${res.stderr.ifBlank { res.stdout }}" }
    }

    // ── 原生 Android SSH 密钥与 known_hosts 管理（不依赖本地 Linux） ──────────────

    private fun writePrivateKeyAtomic0600(privFile: File, bytes: ByteArray) {
        privFile.parentFile?.mkdirs()
        try {
            val fd = android.system.Os.open(
                privFile.absolutePath,
                android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL,
                0x180, // 0600
            )
            try {
                FileOutputStream(fd).use { out ->
                    out.write(bytes)
                    out.flush()
                    fd.sync()
                }
            } catch (e: Exception) {
                runCatching { privFile.delete() }
                throw e
            }
            runCatching { android.system.Os.chmod(privFile.absolutePath, 0x180) }
        } catch (e: android.system.ErrnoException) {
            if (e.errno == android.system.OsConstants.EEXIST) {
                throw IllegalArgumentException("密钥 ${privFile.name} 已存在，请先删除或更换名称")
            }
            throw IllegalStateException("写入私钥失败：${e.message}", e)
        } catch (_: UnsatisfiedLinkError) {
            check(privFile.createNewFile()) { "密钥 ${privFile.name} 已存在，请先删除或更换名称" }
            privFile.setReadable(false, false); privFile.setReadable(true, true)
            privFile.setWritable(false, false); privFile.setWritable(true, true)
            privFile.writeBytes(bytes)
        }
    }

    suspend fun listKeys(): List<SshKeyInfo> = withContext(Dispatchers.IO) {
        val seen = linkedMapOf<String, SshKeyInfo>()
        fun scan(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".pub") }?.forEach { pub ->
                val name = pub.name.removeSuffix(".pub")
                if (name in seen || !File(dir, name).isFile) return@forEach
                val raw = runCatching { pub.readText(Charsets.UTF_8).trim() }.getOrDefault("")
                val parts = raw.split(Regex("\\s+"))
                val keyType = when {
                    parts.firstOrNull()?.contains("ed25519") == true -> "ED25519"
                    parts.firstOrNull()?.contains("rsa") == true -> "RSA"
                    parts.firstOrNull()?.contains("ecdsa") == true -> "ECDSA"
                    else -> "SSH"
                }
                val fp = parts.getOrNull(1)?.let { b64 ->
                    runCatching { sha256Fingerprint(Base64.decode(b64, Base64.DEFAULT)) }.getOrDefault("")
                }.orEmpty()
                seen[name] = SshKeyInfo(
                    name = name,
                    path = File(dir, name).absolutePath,
                    publicKey = raw,
                    fingerprint = fp,
                    keyType = keyType,
                    bits = if (keyType == "ED25519") 256 else 4096,
                    isDefault = name == "id_ed25519",
                )
            }
        }
        scan(keysDir)
        scan(linuxSshDir())
        seen.values.sortedByDescending { it.isDefault }
    }

    suspend fun generateKey(name: String, type: String = "ed25519", comment: String = "solbraid-mobile", passphrase: String = ""): SshKeyInfo = withContext(Dispatchers.IO) {
        val safe = name.trim()
        require(SAFE_KEY_NAME_RE.matches(safe)) { "密钥名称无效（仅支持字母数字开头，1–48 位字母/数字/._-）" }
        val privFile = File(keysDir, safe)
        require(!privFile.exists()) { "密钥 $safe 已存在，请更换名称" }
        val jsch = JSch()
        val kpType = if (type.lowercase() == "rsa") KeyPair.RSA else KeyPair.ED25519
        val bits = if (kpType == KeyPair.RSA) 4096 else 256
        val kp = KeyPair.genKeyPair(jsch, kpType, bits)
        try {
            val privBytes = ByteArrayOutputStream().also { out ->
                val passBytes = passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                kp.writeOpenSSHv1PrivateKey(out, passBytes)
            }.toByteArray()
            writePrivateKeyAtomic0600(privFile, privBytes)
            kp.writePublicKey(File(keysDir, "$safe.pub").absolutePath, comment)
        } finally {
            kp.dispose()
        }
        listKeys().first { it.name == safe }
    }

    suspend fun importKey(name: String, privateKey: String, publicKey: String = "", passphrase: String = ""): SshKeyInfo = withContext(Dispatchers.IO) {
        val safe = name.trim()
        require(SAFE_KEY_NAME_RE.matches(safe)) { "密钥名称无效（仅支持字母数字开头，1–48 位字母/数字/._-）" }
        val privFile = File(keysDir, safe)
        require(!privFile.exists()) { "密钥 $safe 已存在，请先删除原密钥或更换名称" }
        val privText = privateKey.trim() + "\n"
        require(privText.contains("PRIVATE KEY")) { "私钥格式无效（需为 PEM / OpenSSH 私钥）" }
        val jsch = JSch()
        val kp = KeyPair.load(jsch, privText.toByteArray(Charsets.UTF_8), publicKey.trim().takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
        try {
            if (kp.isEncrypted) {
                check(passphrase.isNotEmpty() && kp.decrypt(passphrase.toByteArray(Charsets.UTF_8))) { "私钥已加密，但口令为空或不正确" }
            }
            writePrivateKeyAtomic0600(privFile, privText.toByteArray(Charsets.UTF_8))
            val pubFile = File(keysDir, "$safe.pub")
            if (publicKey.isNotBlank()) pubFile.writeText(publicKey.trim() + "\n", Charsets.UTF_8)
            else kp.writePublicKey(pubFile.absolutePath, "solbraid-imported")
        } finally {
            kp.dispose()
        }
        listKeys().first { it.name == safe }
    }

    suspend fun deleteKey(name: String) = withContext(Dispatchers.IO) {
        val safe = name.trim()
        require(SAFE_KEY_NAME_RE.matches(safe)) { "密钥名称无效" }
        File(keysDir, safe).delete()
        File(keysDir, "$safe.pub").delete()
    }

    suspend fun verifyKeyPassphrase(name: String, passphrase: String) = withContext(Dispatchers.IO) {
        val priv = findPrivateKeyFile(name) ?: error("未找到私钥 $name")
        val kp = KeyPair.load(JSch(), priv.absolutePath)
        try {
            if (kp.isEncrypted) check(kp.decrypt(passphrase.toByteArray(Charsets.UTF_8))) { "私钥口令不正确" }
        } finally {
            kp.dispose()
        }
    }

    suspend fun listKnownHosts(): List<SshKnownHost> = withContext(Dispatchers.IO) {
        val jsch = createConfiguredJsch()
        val arr = jsch.hostKeyRepository?.getHostKey() ?: return@withContext emptyList()
        arr.mapIndexed { idx, hk ->
            val fp = runCatching { sha256Fingerprint(Base64.decode(hk.key, Base64.DEFAULT)) }.getOrDefault(hk.getFingerPrint(jsch))
            SshKnownHost(id = "${hk.host}|${hk.type}|$idx", hosts = hk.host, keyType = hk.type, fingerprint = fp, marker = hk.marker.orEmpty())
        }
    }

    suspend fun removeKnownHost(id: String) = withContext(Dispatchers.IO) {
        val parts = id.split("|")
        if (parts.size >= 2) {
            val jsch = createConfiguredJsch()
            jsch.hostKeyRepository?.remove(parts[0], parts[1])
        }
    }

    // ── 原生端口转发隧道（-L / -R，无需本地 Linux 运行时） ───────────────────────

    suspend fun startNativeTunnel(preset: SshTunnelPreset, host: SshHostConfig, allHosts: List<SshHostConfig>) = withContext(Dispatchers.IO) {
        stopNativeTunnel(preset.id)
        val handle = openSession(host, allHosts, interactive = true, timeoutMs = 15_000)
        try {
            when (preset.mode) {
                "remote" -> handle.session.setPortForwardingR("127.0.0.1", preset.remotePort, preset.remoteHost.ifBlank { "127.0.0.1" }, preset.localPort)
                else -> handle.session.setPortForwardingL("127.0.0.1", preset.localPort, preset.remoteHost.ifBlank { "127.0.0.1" }, preset.remotePort)
            }
            val active = SshActiveTunnel(
                id = preset.id,
                hostId = preset.hostId,
                alias = preset.alias,
                mode = preset.mode,
                localPort = preset.localPort,
                remoteHost = preset.remoteHost,
                remotePort = preset.remotePort,
                status = "running",
                startedAt = System.currentTimeMillis(),
            )
            nativeTunnels[preset.id] = active to handle
            publishNativeTunnels()
        } catch (e: Exception) {
            handle.close()
            throw e
        }
    }

    fun stopNativeTunnel(id: String) {
        nativeTunnels.remove(id)?.second?.close()
        publishNativeTunnels()
    }

    fun stopAllNativeTunnels() {
        nativeTunnels.values.forEach { it.second.close() }
        nativeTunnels.clear()
        publishNativeTunnels()
    }

    private fun publishNativeTunnels() {
        val dead = nativeTunnels.entries.filter { !it.value.second.session.isConnected }.map { it.key }
        dead.forEach { nativeTunnels.remove(it)?.second?.close() }
        nativeTunnelsFlow.value = nativeTunnels.values.map { it.first }
    }

    companion object {
        private val SAFE_KEY_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$")
    }
}
