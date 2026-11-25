package com.galiais.agent

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class RuntimeArtifact(
    val filename: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val fallbackUrls: List<String> = emptyList(),
)

class RuntimeArtifacts(private val context: Context, private val cancelled: AtomicBoolean,
    private val progress: (Long, Long, String) -> Unit) : AutoCloseable {
    @Volatile private var active: HttpURLConnection? = null

    fun obtain(artifact: RuntimeArtifact, label: String): File {
        val directory = File(context.filesDir, "runtime-downloads")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建运行环境下载目录" }
        val complete = File(directory, artifact.filename)
        if (complete.isFile) {
            progress(artifact.size, artifact.size, "$label：校验本地缓存")
            if (complete.length() != artifact.size || hash(complete) != artifact.sha256) {
                check(complete.delete()) { "$label 缓存校验失败且无法清理" }
                error("$label 缓存校验失败；再次安装时会重新下载")
            }
            return complete
        }
        val partial = File(directory, artifact.filename + ".part")
        var offset = partial.length()
        if (offset > artifact.size) {
            check(partial.delete()) { "$label 的未完成下载大小无效且无法清理" }
            offset = 0
        }
        if (offset == artifact.size) {
            progress(artifact.size, artifact.size, "$label：校验下载完整性")
            if (hash(partial) != artifact.sha256) {
                check(partial.delete()) { "$label 下载校验失败且无法清理" }
                error("$label 下载校验失败；再次安装时会重新下载")
            }
            check(partial.renameTo(complete)) { "无法保存已校验的运行环境文件" }
            return complete
        }
        progress(offset, artifact.size, "$label：${offset * 100 / artifact.size}%")
        val allUrls = listOf(artifact.url) + artifact.fallbackUrls
        var lastError: Exception? = null
        var downloadedOk = false
        for (sourceUrl in allUrls) {
            check(!cancelled.get()) { "已取消下载；未完成文件保留供重试" }
            offset = partial.length().takeIf { it in 1 until artifact.size } ?: 0L
            var location = URI(sourceUrl)
            var redirects = 0
            try {
                while (true) {
                    check(!cancelled.get()) { "已取消下载；未完成文件保留供重试" }
                    require(location.scheme == "https" && location.userInfo == null) { "运行环境下载地址不安全" }
                    val connection = location.toURL().openConnection() as HttpURLConnection
                    active = connection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", "Solbraid/${BuildConfig.VERSION_NAME}")
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                    try {
                        val status = connection.responseCode
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            check(++redirects <= 5) { "$label 下载重定向过多" }
                            val target = connection.getHeaderField("Location") ?: error("下载重定向缺少地址")
                            location = location.resolve(target)
                            continue
                        }
                        if (status == 206) {
                            val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range") ?: "")
                                ?: error("下载续传范围无效")
                            check(range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == artifact.size &&
                                range.groupValues[2].toLong() == artifact.size - 1) { "下载续传范围与官方文件不符" }
                        } else {
                            check(status == 200) { "$label 下载失败（HTTP $status）" }
                            offset = 0
                        }
                        var written = offset
                        var reported = offset
                        connection.inputStream.use { input -> java.io.FileOutputStream(partial, offset > 0).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                check(!cancelled.get()) { "已取消下载；未完成文件保留供重试" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                check(written <= artifact.size) { "$label 下载超过官方文件大小" }
                                output.write(buffer, 0, count)
                                if (written - reported >= 2 * 1024 * 1024 || written == artifact.size) {
                                    val mbDone = written / (1024 * 1024)
                                    val mbTotal = artifact.size / (1024 * 1024)
                                    progress(written, artifact.size, "$label：${written * 100 / artifact.size}%（$mbDone / $mbTotal MB）")
                                    reported = written
                                }
                            }
                            output.fd.sync()
                        } }
                        check(written == artifact.size) { "$label 下载未完成，重试时会继续下载" }
                        downloadedOk = true
                        break
                    } finally {
                        active = null
                        connection.disconnect()
                    }
                }
                if (downloadedOk) break
            } catch (e: Exception) {
                if (cancelled.get()) throw e
                lastError = e
            }
        }
        if (!downloadedOk) throw (lastError ?: IllegalStateException("$label 下载失败"))
        if (hash(partial) != artifact.sha256) {
            check(partial.delete()) { "$label SHA-256 校验失败且无法清理" }
            error("$label SHA-256 校验失败，未安装该文件；再次安装时会重新下载")
        }
        check(partial.renameTo(complete)) { "无法保存已校验的运行环境文件" }
        return complete
    }

    private fun hash(file: File): String =
        FileInputStream(file).use { input -> input.sha256Hex { check(!cancelled.get()) { "已取消校验" } } }
    override fun close() { active?.disconnect() }

    companion object {
        val isArm64: Boolean get() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

        private val codexAmd64 = RuntimeArtifact(
            "codex-26.1117.41037-amd64.deb",
            "https://persistent.oaistatic.com/codex-app-prod/linux/deb/pool/main/c/chatgpt/chatgpt_26.1117.41037_amd64.deb",
            "830e251b6ca4ad45fe6904127a790e8f00f7c26ed4c8039473f7323c7c4e2692",
            534451850,
        )
        private val codexArm64 = RuntimeArtifact(
            "codex-0.171.0-alpha.13-linux-arm64.tgz",
            "https://registry.npmmirror.com/@openai/codex/-/codex-0.171.0-alpha.13-linux-arm64.tgz",
            "df5223a9d613ae90f3dc51aab80b55360bd3abfb2d231b02db809cf451adc493",
            176529966,
            fallbackUrls = listOf("https://registry.npmjs.org/@openai/codex/-/codex-0.171.0-alpha.13-linux-arm64.tgz"),
        )
        val nodeArm64 = RuntimeArtifact(
            "node-v22.14.0-linux-arm64.tar.gz",
            "https://npmmirror.com/mirrors/node/v22.14.0/node-v22.14.0-linux-arm64.tar.gz",
            "8cf30ff7250f9463b53c18f89c6c606dfda70378215b2c905d0a9a8b08bd45e0",
            53545640,
            fallbackUrls = listOf("https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-arm64.tar.gz"),
        )
        private val claudeAmd64 = RuntimeArtifact(
            "claude-code-2.1.328-linux-x64",
            "https://downloads.claude.ai/claude-code-releases/2.1.328/linux-x64/claude",
            "a982d38030b97e750848add2cbb2df6d9a70d5544a22ca9b216a349ba25865e8",
            373019152,
        )
        private val claudeArm64 = RuntimeArtifact(
            "claude-code-2.1.328-linux-arm64",
            "https://downloads.claude.ai/claude-code-releases/2.1.328/linux-arm64/claude",
            "307f8bada27b37db7553fac75e86004e3dbff5a931ffc611b54fe710ce1eb521",
            371844536,
        )
        private val imageAmd64 = RuntimeArtifact(
            "ubuntu-24.04-minimal-cloudimg-amd64-20261105.img",
            "https://mirrors.ustc.edu.cn/ubuntu-cloud-images/minimal/releases/noble/release-20261105/ubuntu-24.04-minimal-cloudimg-amd64.img",
            "406630c08b5e545fcc7209854b129d47e3af822db128c9f19336593b5a2d3fe0",
            259981312,
            fallbackUrls = listOf("https://cloud-images.ubuntu.com/minimal/releases/noble/release-20261105/ubuntu-24.04-minimal-cloudimg-amd64.img"),
        )
        private val imageArm64 = RuntimeArtifact(
            "ubuntu-24.04-minimal-cloudimg-arm64-20261105.img",
            "https://mirrors.ustc.edu.cn/ubuntu-cloud-images/minimal/releases/noble/release-20261105/ubuntu-24.04-minimal-cloudimg-arm64.img",
            "3dc8370d923978d4b610fa7964d1b36ba040eefb43198809ca5bc8ac51777b18",
            223870976,
            fallbackUrls = listOf("https://cloud-images.ubuntu.com/minimal/releases/noble/release-20261105/ubuntu-24.04-minimal-cloudimg-arm64.img"),
        )

        val codex: RuntimeArtifact get() = if (isArm64) codexArm64 else codexAmd64
        val claude: RuntimeArtifact get() = if (isArm64) claudeArm64 else claudeAmd64
        val image: RuntimeArtifact get() = if (isArm64) imageArm64 else imageAmd64
    }
}
