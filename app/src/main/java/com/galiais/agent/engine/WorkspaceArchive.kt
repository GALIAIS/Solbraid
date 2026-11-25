package com.galiais.agent.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.galiais.agent.CliRpcConnection
import com.galiais.agent.LinuxRuntime
import com.galiais.agent.newId
import com.galiais.agent.deleteLocalTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 宿主 Android 与虚拟机工作区之间的安全文件桥与防丢快照器：
 * 1. 支持通过系统文件选择器（SAF）向任意项目目录导入单文件或解压导入 `.zip`（严格防 Zip-Slip 穿越）；
 * 2. 支持将虚拟机内任意项目目录打包导出为 `.zip` 到手机本地存储；
 * 3. 在执行「重置虚拟机」前自动备份 `/home/agent` 下的全部项目代码到宿主持久化目录，重建后自动恢复。
 */
object WorkspaceArchive {
    private val SKIP_DIRS = setOf("node_modules", ".gradle", "build", "target", "__pycache__", ".venv", ".next", "dist")
    private const val MAX_ZIP_FILES = 400
    private const val MAX_SINGLE_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    suspend fun importUriToDirectory(
        context: Context,
        uri: Uri,
        targetDir: String,
        files: WorkspaceFiles,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: "imported-file"
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
        val cleanTarget = targetDir.trimEnd('/').ifEmpty { "/" }

        if (safeName.lowercase().endsWith(".zip")) {
            val extracted = mutableListOf<Pair<String, ByteArray>>()
            val names = mutableSetOf<String>()
            var totalBytes = 0L
            resolver.openInputStream(uri)?.use { rawIn ->
                ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val rel = normalizeZipEntryPath(entry.name) ?: error("ZIP 包含无效路径：${entry.name.take(120)}")
                        if (rel.startsWith("__MACOSX/") || rel.substringAfterLast('/') == ".DS_Store") continue
                        if (entry.isDirectory) continue
                        check(names.add(rel)) { "ZIP 包含重复文件：$rel" }
                        check(extracted.size < MAX_ZIP_FILES) { "ZIP 内文件数超过 $MAX_ZIP_FILES 上限" }
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            check(out.size() + n <= MAX_SINGLE_FILE_BYTES) { "ZIP 内单文件超过 4 MB 上限：$rel" }
                            totalBytes += n
                            check(totalBytes <= MAX_TOTAL_BYTES) { "ZIP 解压总大小超过 64 MB 上限" }
                            out.write(buf, 0, n)
                        }
                        extracted += rel to out.toByteArray()
                    }
                }
            } ?: error("无法读取所选 ZIP 文件")

            check(extracted.isNotEmpty()) { "ZIP 中没有可导入文件" }
            for (name in names) {
                val parts = name.split('/')
                check((1 until parts.size).none { parts.take(it).joinToString("/") in names }) { "ZIP 中的文件与目录同名：$name" }
            }
            val directories = mutableMapOf(cleanTarget to files.list(cleanTarget).associateBy { it.name })
            for ((rel, _) in extracted) {
                var parent = cleanTarget
                for (segment in rel.split('/').dropLast(1)) {
                    val next = parent.trimEnd('/') + "/" + segment
                    val entry = directories.getValue(parent)[segment]
                    if (entry != null) check(entry.directory && !files.isSymbolicLink(next)) { "导入路径与现有文件或符号链接冲突：$rel" }
                    if (next !in directories) {
                        if (entry == null) files.createDirectory(next)
                        directories[next] = files.list(next).associateBy { it.name }
                    }
                    parent = next
                }
                check(rel.substringAfterLast('/') !in directories.getValue(parent)) { "目标已有同名文件，请先重命名或移走：$rel" }
            }
            var written = 0
            try {
                for ((rel, data) in extracted) {
                    files.writeBytes(cleanTarget.trimEnd('/') + "/" + rel, data, MAX_SINGLE_FILE_BYTES)
                    written++
                }
            } catch (error: Exception) { throw IllegalStateException("导入中断，已写入 $written 个文件：${error.message}", error) }
            "已解压导入 ${extracted.size} 个文件到 ${cleanTarget.substringAfterLast('/').ifEmpty { "/" }}"
        } else {
            check(files.list(cleanTarget).none { it.name == safeName }) { "目标已有同名文件，请先重命名或移走：$safeName" }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    check(out.size() + n <= MAX_SINGLE_FILE_BYTES) { "文件超过 4 MB 上限" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: error("无法读取所选文件")
            files.writeBytes(cleanTarget.trimEnd('/') + "/" + safeName, bytes, MAX_SINGLE_FILE_BYTES)
            "已导入文件：$safeName"
        }
    }

    suspend fun exportDirectoryToZipUri(
        context: Context,
        uri: Uri,
        sourceDir: String,
        files: WorkspaceFiles,
    ): Int = withContext(Dispatchers.IO) {
        val cleanRoot = sourceDir.trimEnd('/').ifEmpty { "/" }
        val filePaths = mutableListOf<String>()
        suspend fun walk(dir: String, depth: Int = 0) {
            check(depth <= 64) { "目录层级超过导出上限，请选择子目录" }
            val children = files.list(dir)
            for (entry in children) {
                if (files.isSymbolicLink(entry.path)) continue
                if (entry.directory) {
                    if (entry.name in SKIP_DIRS || entry.name == ".git") continue
                    walk(entry.path, depth + 1)
                } else {
                    check(filePaths.size < MAX_ZIP_FILES) { "文件数超过 $MAX_ZIP_FILES，请选择子目录分批导出" }
                    filePaths += entry.path
                }
            }
        }
        walk(cleanRoot)
        check(filePaths.isNotEmpty()) { "该目录内没有可导出的源码文件" }

        var writtenCount = 0
        var totalBytes = 0L
        val staged = File.createTempFile("galiais-export-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(BufferedOutputStream(staged.outputStream())).use { zos ->
                for (fullPath in filePaths) {
                    val data = files.readBytes(fullPath, MAX_SINGLE_FILE_BYTES)
                    check(totalBytes + data.size <= MAX_TOTAL_BYTES) { "源码超过 64 MB，请选择子目录分批导出" }
                    val rel = fullPath.removePrefix(cleanRoot.trimEnd('/') + "/")
                    check(rel.isNotEmpty()) { "导出文件路径无效" }
                    zos.putNextEntry(ZipEntry(rel))
                    zos.write(data)
                    zos.closeEntry()
                    totalBytes += data.size
                    writtenCount++
                }
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                staged.inputStream().use { it.copyTo(output) }
            } ?: error("无法写入目标导出文件")
        } finally { staged.delete() }
        writtenCount
    }

    private fun normalizeZipEntryPath(raw: String): String? {
        val unified = raw.replace('\\', '/')
        if (unified.isBlank() || unified.length > 4096 || unified.startsWith('/') || unified.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(unified)) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.size > 64 || segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** 备份成功落盘后才允许重置；原生文件通道避免 RPC 帧大小截断大型项目。 */
    fun backupBeforeVmReset(context: Context, codexConn: CliRpcConnection, projectPaths: List<String>, mode: String) {
        val pending = pendingBackup(context, mode)
        check(!pending.exists()) { "已有尚未恢复的项目备份，请先重新启动环境完成恢复" }
        val dirs = (projectPaths + DEFAULT_WORKSPACE)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("/home/agent/") && it.split('/').none { part -> part == ".." } }
            .map { it.removePrefix("/home/agent/").substringBefore('/') }
            .filter { it.isNotBlank() && !it.startsWith(".") }
            .distinct()
        val root = LinuxRuntime(context, mode).root
        val existingDirs = dirs.filter { File(root, "home/agent/$it").exists() }
        check("workspace" in existingDirs) { "工作区不可访问，已取消重置" }
        val tmpTar = "/tmp/galiais-reset-${newId()}.tgz"
        val source = File(root, tmpTar.removePrefix("/"))
        val cmd = buildList {
            add("tar"); add("-czf"); add(tmpTar)
            add("-C"); add("/home/agent")
            add("--"); addAll(existingDirs)
        }
        try {
            val result = codexConn.request("command/exec", jsonOf(
                "command" to JSONArray(cmd), "cwd" to "/home/agent", "timeoutMs" to 120_000,
                "sandboxPolicy" to jsonOf("type" to "dangerFullAccess"),
            ), 125_000).get() as? org.json.JSONObject
            check(result?.optInt("exitCode", -1) == 0) { "项目打包失败，已取消重置：${result?.optString("stderr").orEmpty().takeLast(600)}" }
            check(source.isFile && source.length() > 0) { "项目备份未生成，已取消重置" }
            check(pending.parentFile!!.isDirectory || pending.parentFile!!.mkdirs()) { "无法创建备份目录" }
            val atomic = AtomicFile(pending)
            val output = atomic.startWrite()
            try {
                val copied = source.inputStream().use { it.copyTo(output) }
                check(copied == source.length()) { "项目备份未完整写入，已取消重置" }
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
        } finally { source.delete() }
    }

    private fun pendingBackup(context: Context, mode: String): File {
        require(mode in setOf(RUNTIME_MODE_ROOT, RUNTIME_MODE_PROOT)) { "备份运行模式无效" }
        return File(context.filesDir, "workspace-backups/$mode/pending-reset-restore.tgz")
    }

    /** 旧备份未记录模式，必须由用户明确选择恢复目标后迁移。 */
    fun restoreLegacyBackup(context: Context, codexConn: CliRpcConnection, mode: String) {
        val legacy = File(context.filesDir, "workspace-backups/pending-reset-restore.tgz")
        check(legacy.isFile) { "旧版备份已不存在" }
        val pending = pendingBackup(context, mode)
        check(!pending.exists()) { "当前模式已有待恢复备份，请先重新启动环境" }
        check(pending.parentFile!!.isDirectory || pending.parentFile!!.mkdirs()) { "无法创建备份目录" }
        val atomic = AtomicFile(pending)
        val output = atomic.startWrite()
        try {
            legacy.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        check(legacy.renameTo(File(legacy.parentFile, "legacy-source-$mode-${newId()}.tgz"))) { "无法归档旧备份；原文件仍保留" }
        restoreAfterVmResetIfNeeded(context, codexConn, mode)
    }

    /** 只恢复同一运行模式的备份，已有文件保留，失败时保留待恢复快照并阻止继续启动。 */
    fun restoreAfterVmResetIfNeeded(context: Context, codexConn: CliRpcConnection, mode: String): Boolean {
        val pending = pendingBackup(context, mode)
        if (!pending.isFile) return false
        val tmpTar = "/tmp/galiais-restore-${newId()}.tgz"
        val staging = File(LinuxRuntime(context, mode).root, tmpTar.removePrefix("/"))
        val guestDirectory = "/tmp/galiais-restore-${newId()}"
        val extracted = File(LinuxRuntime(context, mode).root, guestDirectory.removePrefix("/"))
        try {
            check(extracted.mkdir()) { "无法创建恢复暂存目录" }
            pending.inputStream().use { input -> staging.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            val result = codexConn.request("command/exec", jsonOf(
                "command" to JSONArray(listOf("tar", "--no-same-owner", "-xzf", tmpTar, "-C", guestDirectory)),
                "cwd" to "/home/agent", "timeoutMs" to 120_000,
                "sandboxPolicy" to jsonOf("type" to "dangerFullAccess"),
            ), 125_000).get() as? org.json.JSONObject
            check(result?.optInt("exitCode", -1) == 0) { "项目恢复失败；备份已保留，请重新启动后重试：${result?.optString("stderr").orEmpty().takeLast(600)}" }
            mergeRestoredDirectory(extracted, File(LinuxRuntime(context, mode).root, "home/agent"))
            check(pending.renameTo(File(pending.parentFile, "last-restored.tgz"))) { "项目已恢复，但无法归档备份，请检查存储空间" }
            return true
        } finally { staging.delete(); deleteLocalTree(extracted) }
    }

    private fun mergeRestoredDirectory(source: File, target: File) {
        val entries = source.listFiles() ?: error("无法读取备份暂存目录")
        for (entry in entries) {
            val destination = File(target, entry.name)
            if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(entry.renameTo(destination)) { "无法恢复 ${entry.name}；备份已保留" }
            } else if (Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                mergeRestoredDirectory(entry, destination)
            }
        }
    }
}
