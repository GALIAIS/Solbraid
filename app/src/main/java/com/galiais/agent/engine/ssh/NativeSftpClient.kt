package com.galiais.agent.engine.ssh

import com.galiais.agent.engine.SftpTextDocument
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.SshRemoteEntry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生连接复用 SFTP 客户端（基于 JSch `ChannelSftp`）：
 * - 每台主机维护 1 条持久化复用连接（5 分钟空闲自动回收、断线自动无感重连），
 *   彻底解决旧版“每次点目录/保存文件跑 4～6 次完整 SSH 握手”的卡顿与 `fail2ban` 风险；
 * - 支持覆盖上传、递归删目录、软链接透视、文本毫秒级读写与实时进度回调。
 */
class NativeSftpClient(
    private val sshEngine: NativeSshEngine,
) {
    private class PooledChannel(
        val mutex: Mutex = Mutex(),
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
            val now = System.currentTimeMillis()
            val alive = entry.handle?.session?.isConnected == true && entry.sftp?.isConnected == true
            if (!alive || now - entry.lastUsedMs > 5 * 60_000L) {
                runCatching { entry.sftp?.disconnect() }
                runCatching { entry.handle?.close() }
                val handle = sshEngine.openSession(host, allHosts, interactive = true, timeoutMs = 15_000)
                val ch = handle.session.openChannel("sftp") as ChannelSftp
                ch.connect(12_000)
                entry.handle = handle
                entry.sftp = ch
            }
            entry.lastUsedMs = System.currentTimeMillis()
            try {
                block(entry.sftp!!)
            } catch (e: Exception) {
                // 若连接在中途断开，关闭旧通道后自动重连一次
                if (entry.handle?.session?.isConnected != true || entry.sftp?.isConnected != true) {
                    runCatching { entry.sftp?.disconnect() }
                    runCatching { entry.handle?.close() }
                    val handle = sshEngine.openSession(host, allHosts, interactive = true, timeoutMs = 15_000)
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
            runCatching { entry.sftp?.disconnect() }
            runCatching { entry.handle?.close() }
        }
    }

    fun closeAll() {
        pool.keys.toList().forEach(::closeHost)
    }

    suspend fun list(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String = "."): Pair<String, List<SshRemoteEntry>> =
        withChannel(host, allHosts) { sftp ->
            val target = remotePath.trim().ifEmpty { "." }
            val resolved = runCatching { sftp.realpath(target) }.getOrDefault(if (target == ".") sftp.pwd() else target)
            val raw = sftp.ls(resolved)
            val list = raw.mapNotNull { item ->
                val name = item.filename ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                val fullPath = if (resolved == "/") "/$name" else "${resolved.trimEnd('/')}/$name"
                val attrs = item.attrs
                val isLink = attrs.isLink
                val isDir = if (isLink) runCatching { sftp.stat(fullPath).isDir }.getOrDefault(false) else attrs.isDir
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
        if (expectedRevision.isNotBlank()) {
            val currentBaos = ByteArrayOutputStream()
            runCatching { sftp.get(remotePath, currentBaos) }
            val currentBytes = currentBaos.toByteArray()
            if (currentBytes.isNotEmpty() && sha256Hex(currentBytes) != expectedRevision) {
                error("远端文件已被其他进程修改，请先「重新读取」后再保存")
            }
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        sftp.put(ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE)
        sha256Hex(bytes)
    }

    suspend fun mkdir(host: SshHostConfig, allHosts: List<SshHostConfig>, remotePath: String) =
        withChannel(host, allHosts) { sftp -> sftp.mkdir(remotePath) }

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
            require(remotePath != "/" && remotePath.isNotBlank()) { "禁止删除根目录" }
            fun rmRec(path: String, isDir: Boolean) {
                if (!isDir) {
                    sftp.rm(path)
                    return
                }
                val entries = sftp.ls(path)
                for (e in entries) {
                    val name = e.filename ?: continue
                    if (name == "." || name == "..") continue
                    val childPath = "${path.trimEnd('/')}/$name"
                    rmRec(childPath, e.attrs.isDir && !e.attrs.isLink)
                }
                sftp.rmdir(path)
            }
            rmRec(remotePath, directory)
        }

    suspend fun uploadFile(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        localFile: File,
        remoteDir: String,
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
                transferred += count
                val pct = ((transferred * 100L) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                return true
            }
            override fun end() { onProgress(100) }
        }
        sftp.put(localFile.absolutePath, dest, monitor, ChannelSftp.OVERWRITE)
        dest
    }

    suspend fun downloadFile(
        host: SshHostConfig,
        allHosts: List<SshHostConfig>,
        remotePath: String,
        localFile: File,
        onProgress: (percent: Int) -> Unit = {},
    ): File = withChannel(host, allHosts) { sftp ->
        localFile.parentFile?.mkdirs()
        val total = runCatching { sftp.stat(remotePath).size }.getOrDefault(1L).coerceAtLeast(1L)
        var transferred = 0L
        var lastPct = -1
        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dst: String?, max: Long) { onProgress(0) }
            override fun count(count: Long): Boolean {
                transferred += count
                val pct = ((transferred * 100L) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                return true
            }
            override fun end() { onProgress(100) }
        }
        sftp.get(remotePath, localFile.absolutePath, monitor)
        localFile
    }
}
