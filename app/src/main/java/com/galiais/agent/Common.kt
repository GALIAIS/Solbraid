package com.galiais.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

fun newId(): String = UUID.randomUUID().toString()

/** 删除应用自己的目录树，不跟随目录内的符号链接。调用方负责限定根路径。 */
fun deleteLocalTree(file: File) {
    val root = file.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(path)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(path)
            return FileVisitResult.CONTINUE
        }
    })
}

/** 生成可安全嵌入 POSIX shell 的单引号字面量。 */
fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

fun strictUtf8Decoder(): CharsetDecoder = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

fun strictUtf8(bytes: ByteArray): String = strictUtf8Decoder().decode(ByteBuffer.wrap(bytes)).toString()

fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "内容超过 $limit 字节限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** 流式计算 SHA-256；每个缓冲区前调用 [checkpoint]，以便长时间校验可被取消。 */
fun InputStream.sha256Hex(checkpoint: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65_536)
    while (true) {
        checkpoint()
        val count = read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 可取消的运行环境操作：取消时断开登记的网络连接并强制结束登记的进程。 */
class RunControl {
    private val cancelled = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("操作已取消")
    }
    fun track(connection: HttpURLConnection) { check(); connections.add(connection); if (cancelled.get()) connection.disconnect() }
    fun release(connection: HttpURLConnection) { connections.remove(connection) }
    fun track(process: Process) { processes.add(process); if (cancelled.get()) process.destroyForcibly() }
    fun release(process: Process) { processes.remove(process) }
    fun cancel() {
        cancelled.set(true)
        connections.forEach { it.disconnect() }
        processes.forEach { it.destroyForcibly() }
    }
}
