package com.galiais.agent.engine.ssh

import com.galiais.agent.engine.SftpTextDocument
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshRemoteEntry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生连接复用 SFTP 客户端（基于 JSch `ChannelSftp`）：
 * - 每台主机维护 1 条持久化复用连接（5 分钟空闲自动回收、断线自动无感重连、关闭后严防僵尸重连）；
 * - 支持原子写入+乐观锁冲突检测、递归建目录、软链接透视、可取消流式传输与实时进度回调。
 */
class NativeSftpClient(
    private val sshEngine: NativeSshEngine,
) {
    private class PooledChannel(
        val mutex: Mutex = Mutex(),
        val closed: AtomicBoolean = AtomicBoolean(false),
        var handle: NativeSshSessionHandle? = null,
        var sftp: ChannelSftp? = null,
        var lastUsedMs: Long = 0L,
    )

    private val pool = ConcurrentHashMap<String, PooledChannel>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private suspend fun <T> withChannel(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        block: (ChannelSftp) -> T,
    ): T = withContext(Dispatchers.IO) {
        val entry = pool.computeIfAbsent(host.id) { PooledChannel() }
        entry.mutex.withLock {
            if (entry.closed.get()) throw CancellationException("SFTP 连接已关闭")
            val now = System.currentTimeMillis()
            val alive = entry.handle?.session?.isConnected == true && entry.sftp?.isConnected == true
            if (!alive || now - entry.lastUsedMs > 5 * 60_000L) {
                runCatching { entry.sftp?.disconnect() }
                runCatching { entry.handle?.close() }
                val handle = sshEngine.openSession(host, allHosts, interactive = true, timeoutMs = 15_000)
                if (entry.closed.get()) {
                    handle.close()
                    throw CancellationException("SFTP 连接已关闭")
                }
                val ch = handle.session.openChannel("sftp") as ChannelSftp
                ch.connect(12_000)
                entry.handle = handle
                entry.sftp = ch
            }
            entry.lastUsedMs = System.currentTimeMillis()
            try {
                block(entry.sftp!!)
            } catch (e: Exception) {
                if (entry.closed.get() || e is CancellationException) throw e
                // 若连接在中途断开，关闭旧通道后自动重连一次
                if (entry.handle?.session?.isConnected != true || entry.sftp?.isConnected != true) {
                    runCatching { entry.sftp?.disconnect() }
                    runCatching { entry.handle?.close() }
                    if (entry.closed.get()) throw CancellationException("SFTP 连接已关闭")
                    val handle = sshEngine.openSession(host, allHosts, interactive = true, timeoutMs = 15_000)
                    if (entry.closed.get()) {
                        handle.close()
                        throw CancellationException("SFTP 连接已关闭")
                    }
                    val ch = handle.session.openChannel("sftp") as ChannelSftp
                    ch.connect(12_000)
                    entry.handle = handle
                    entry.sftp = ch
                    entry.lastUsedMs = System.currentTimeMillis()
                    block(ch)
                } else throw e
            }
        }
    }

    fun closeHost(hostId: String) {
        pool.remove(hostId)?.let { entry ->
            entry.closed.set(true)
            runCatching { entry.sftp?.disconnect() }
            runCatching { entry.handle?.close() }
        }
    }

    fun closeAll() {
        pool.keys.toList().forEach(::closeHost)
    }

    // JSch 会展开 ls、stat 和删除操作中的通配符；条目名称必须按字面值传入。
    private fun literalPath(path: String): String = buildString(path.length) {
        for (ch in path) {
            if (ch == '\\' || ch == '*' || ch == '?') append('\\')
            append(ch)
        }
    }

    suspend fun list(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String = "."): Pair<String, List<SshRemoteEntry>> =
        withChannel(host, allHosts) { sftp ->
            val target = remotePath.trim().ifEmpty { "." }
            val resolved = runCatching { sftp.realpath(target) }.getOrDefault(if (target == ".") sftp.pwd() else target)
            val raw = sftp.ls(literalPath(resolved))
            val list = raw.mapNotNull { item ->
                val name = item.filename ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                val fullPath = if (resolved == "/") "/$name" else "${resolved.trimEnd('/')}/$name"
                val attrs = item.attrs
                val isLink = attrs.isLink
                val isDir = if (isLink) runCatching { sftp.stat(literalPath(fullPath)).isDir }.getOrDefault(false) else attrs.isDir
                val permStr = attrs.permissionsString.orEmpty()
                val mod = synchronized(dateFmt) { dateFmt.format(Date(attrs.mTime.toLong() * 1000L)) }
                SshRemoteEntry(
                    name = name,
                    path = fullPath,
                    directory = isDir,
                    size = attrs.size,
                    permissions = permStr,
                    modified = mod,
                )
            }.sortedWith(compareByDescending<SshRemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
            resolved to list
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun readText(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String): SftpTextDocument =
        withChannel(host, allHosts) { sftp ->
            val stat = sftp.stat(remotePath)
            require(stat.size <= 4L * 1024 * 1024) { "远端文本文件超过 4 MB，请下载后在本地编辑器打开" }
            val baos = ByteArrayOutputStream(stat.size.toInt().coerceAtLeast(256))
            sftp.get(remotePath, baos)
            val bytes = baos.toByteArray()
            SftpTextDocument(bytes.toString(Charsets.UTF_8), sha256Hex(bytes))
        }

    suspend fun writeText(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        remotePath: String,
        content: String,
        expectedRevision: String,
    ): String = withChannel(host, allHosts) { sftp ->
        var origMode: Int? = null
        if (expectedRevision.isNotBlank()) {
            val currentBaos = ByteArrayOutputStream()
            var exists = true
            try {
                origMode = runCatching { sftp.stat(remotePath).permissions and 0xFFF }.getOrNull()
                sftp.get(remotePath, currentBaos)
            } catch (e: com.jcraft.jsch.SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) exists = false
                else throw e
            }
            if (exists) {
                val currentBytes = currentBaos.toByteArray()
                if (sha256Hex(currentBytes) != expectedRevision) {
                    error("远端文件已被其他进程修改，请先「重新读取」后再保存")
                }
            }
        } else {
            origMode = runCatching { sftp.stat(remotePath).permissions and 0xFFF }.getOrNull()
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val parent = remotePath.trimEnd('/').substringBeforeLast('/', "")
        val leaf = remotePath.trimEnd('/').substringAfterLast('/')
        val tmpPath = "${if (parent.isEmpty()) "" else parent}/.${leaf}.solbraid.${UUID.randomUUID().toString().take(8)}.tmp"
        var stagedOk = false
        try {
            sftp.put(ByteArrayInputStream(bytes), tmpPath, ChannelSftp.OVERWRITE)
            origMode?.let { mode -> runCatching { sftp.chmod(mode, tmpPath) } }
            // JSch ChannelSftp.rename 会在服务端支持 posix-rename@openssh.com 时自动使用原子重命名；否则回退直接覆盖写
            val renamed = runCatching {
                sftp.rename(tmpPath, remotePath)
                true
            }.getOrDefault(false)
            if (renamed) {
                stagedOk = true
            } else {
                sftp.put(ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE)
                origMode?.let { mode -> runCatching { sftp.chmod(mode, remotePath) } }
                runCatching { sftp.rm(tmpPath) }
                stagedOk = true
            }
        } finally {
            if (!stagedOk) runCatching { sftp.rm(tmpPath) }
        }
        sha256Hex(bytes)
    }

    suspend fun mkdir(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String) =
        withChannel(host, allHosts) { sftp -> sftp.mkdir(remotePath) }

    suspend fun mkdirs(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String) =
        withChannel(host, allHosts) { sftp ->
            val clean = remotePath.trimEnd('/')
            if (clean.isEmpty() || clean == "/") return@withChannel
            val absolute = clean.startsWith('/')
            val parts = clean.split('/').filter { it.isNotEmpty() }
            var cur = if (absolute) "" else "."
            for (part in parts) {
                cur = if (cur.isEmpty()) "/$part" else "$cur/$part"
                val exists = runCatching { sftp.stat(cur).isDir }.getOrDefault(false)
                if (!exists) {
                    runCatching { sftp.mkdir(cur) }
                }
            }
        }

    suspend fun rename(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String, newName: String) =
        withChannel(host, allHosts) { sftp ->
            val clean = newName.trim()
            require(clean.isNotEmpty() && !clean.contains('/') && clean != "." && clean != "..") { "新名称无效" }
            val parent = remotePath.trimEnd('/').substringBeforeLast('/', "")
            val dest = if (parent.isEmpty()) "/$clean" else "$parent/$clean"
            sftp.rename(remotePath, dest)
        }

    suspend fun chmod(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String, octalMode: String) =
        withChannel(host, allHosts) { sftp ->
            val mode = octalMode.trim().toIntOrNull(8) ?: error("八进制权限格式无效（例如 644 / 755）")
            sftp.chmod(mode, remotePath)
        }

    suspend fun deleteRecursive(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String, directory: Boolean) =
        withChannel(host, allHosts) { sftp ->
            val target = remotePath.trimEnd('/')
            require(target.isNotBlank() && target.split('/').none { it == ".." } && target.substringAfterLast('/') != ".") { "请选择要删除的文件或目录" }
            fun rmRec(path: String, isDir: Boolean) {
                val literal = literalPath(path)
                if (!isDir || sftp.lstat(literal).isLink) {
                    sftp.rm(literal)
                    return
                }
                val entries = sftp.ls(literal)
                for (e in entries) {
                    val name = e.filename ?: continue
                    if (name == "." || name == "..") continue
                    require('/' !in name) { "远端返回了无效的文件名" }
                    val childPath = "${path.trimEnd('/')}/$name"
                    rmRec(childPath, e.attrs.isDir && !e.attrs.isLink)
                }
                sftp.rmdir(literal)
            }
            rmRec(target, directory)
        }

    suspend fun uploadFile(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        localFile: File,
        remoteDir: String,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (percent: Int) -> Unit = {},
    ): String = withChannel(host, allHosts) { sftp ->
        require(localFile.isFile && localFile.canRead()) { "本地文件不可读：${localFile.path}" }
        val total = localFile.length().coerceAtLeast(1L)
        val dest = "${remoteDir.trimEnd('/')}/${localFile.name}"
        var transferred = 0L
        var lastPct = -1
        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dst: String?, max: Long) { onProgress(0) }
            override fun count(count: Long): Boolean {
                if (cancelled.get()) return false
                transferred += count
                val pct = ((transferred * 100L) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                return !cancelled.get()
            }
            override fun end() { if (!cancelled.get()) onProgress(100) }
        }
        sftp.put(localFile.absolutePath, dest, monitor, ChannelSftp.OVERWRITE)
        if (cancelled.get()) throw CancellationException("用户已取消上传")
        dest
    }

    suspend fun downloadFile(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        remotePath: String,
        localFile: File,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (percent: Int) -> Unit = {},
    ): File = withChannel(host, allHosts) { sftp ->
        localFile.parentFile?.mkdirs()
        val total = runCatching { sftp.stat(remotePath).size }.getOrDefault(1L).coerceAtLeast(1L)
        var transferred = 0L
        var lastPct = -1
        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dst: String?, max: Long) { onProgress(0) }
            override fun count(count: Long): Boolean {
                if (cancelled.get()) return false
                transferred += count
                val pct = ((transferred * 100L) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                return !cancelled.get()
            }
            override fun end() { if (!cancelled.get()) onProgress(100) }
        }
        try {
            sftp.get(remotePath, localFile.absolutePath, monitor)
        } catch (e: Exception) {
            if (cancelled.get()) {
                runCatching { localFile.delete() }
                throw CancellationException("用户已取消下载")
            }
            throw e
        }
        if (cancelled.get()) {
            runCatching { localFile.delete() }
            throw CancellationException("用户已取消下载")
        }
        localFile
    }
}
