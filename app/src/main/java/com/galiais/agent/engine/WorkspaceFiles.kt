package com.galiais.agent.engine

import android.util.Base64
import org.json.JSONArray

data class FileEntry(val name: String, val path: String, val directory: Boolean)

sealed interface FilePreview {
    data class Text(val content: String, val truncated: Boolean) : FilePreview
    data class Image(val bytes: ByteArray, val mime: String) : FilePreview
    data class Binary(val bytes: Int) : FilePreview
}

data class GitCommit(val hash: String, val subject: String, val author: String, val relTime: String)

data class GitOverview(val isRepo: Boolean, val statusText: String, val diffs: List<FileDiff>)

/** 通过 Codex app-server 操作本地 Linux 工作区；文件操作由用户在界面中直接发起。 */
class WorkspaceFiles(private val runtime: RuntimeClient, private val environment: EnvSshRepository) {
    private fun check(path: String) = require(path.startsWith("/") && !path.contains('\u0000') && path.split('/').none { it == ".." }) { "路径无效" }

    suspend fun list(path: String): List<FileEntry> {
        check(path)
        return runtime.requestObject(EngineKind.CODEX, "fs/readDirectory", jsonOf("path" to path)).arr("entries").objects()
            .map { FileEntry(it.optString("fileName"), path.trimEnd('/') + "/" + it.optString("fileName"), it.optBoolean("isDirectory")) }
            .sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun isSymbolicLink(path: String): Boolean {
        check(path)
        return runtime.requestObject(EngineKind.CODEX, "fs/getMetadata", jsonOf("path" to path)).getBoolean("isSymlink")
    }

    suspend fun read(path: String, limit: Int = 256 * 1024): FilePreview {
        check(path)
        val bytes = Base64.decode(runtime.requestObject(EngineKind.CODEX, "fs/readFile", jsonOf("path" to path)).optString("dataBase64"), Base64.DEFAULT)
        val ext = path.substringAfterLast('.', "").lowercase()
        val imgMime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> null
        }
        if (imgMime != null && bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) {
            return FilePreview.Image(bytes, imgMime)
        }
        val head = bytes.copyOf(minOf(bytes.size, limit))
        if (head.take(8000).any { it == 0.toByte() }) return FilePreview.Binary(bytes.size)
        return FilePreview.Text(String(head, Charsets.UTF_8), bytes.size > limit)
    }

    suspend fun readBytes(path: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
        check(path)
        val raw = Base64.decode(runtime.requestObject(EngineKind.CODEX, "fs/readFile", jsonOf("path" to path)).optString("dataBase64"), Base64.DEFAULT)
        require(raw.size <= maxBytes) { "文件超过大小上限（${maxBytes / (1024 * 1024)} MB）" }
        return raw
    }

    suspend fun writeBytes(path: String, bytes: ByteArray, maxBytes: Int = 8 * 1024 * 1024) {
        check(path)
        require(bytes.size <= maxBytes) { "文件超过 ${maxBytes / (1024 * 1024)} MB，无法直接传入工作区" }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        runtime.requestObject(EngineKind.CODEX, "fs/writeFile", jsonOf("path" to path, "dataBase64" to b64))
    }

    suspend fun write(path: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2 * 1024 * 1024) { "文件超过 2 MB，不支持在线保存" }
        writeBytes(path, bytes)
    }

    suspend fun createDirectory(path: String) {
        check(path)
        runtime.requestObject(EngineKind.CODEX, "fs/createDirectory", jsonOf("path" to path, "recursive" to true))
    }

    suspend fun remove(path: String, recursive: Boolean = true) {
        check(path)
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        require(normalized !in setOf("/", "/home", "/home/agent", DEFAULT_WORKSPACE)) { "不允许删除系统或默认工作区根目录" }
        runtime.requestObject(EngineKind.CODEX, "fs/remove", jsonOf("path" to normalized, "recursive" to recursive, "force" to false))
    }

    suspend fun rename(oldPath: String, newPath: String) {
        check(oldPath)
        check(newPath)
        val src = oldPath.trimEnd('/').ifEmpty { "/" }
        val dst = newPath.trimEnd('/').ifEmpty { "/" }
        require(src !in setOf("/", "/home", "/home/agent", DEFAULT_WORKSPACE)) { "不允许重命名系统或默认工作区根目录" }
        if (src == dst) return
        val parent = src.substringBeforeLast('/', "").ifEmpty { "/" }
        val checkExists = exec(listOf("test", "-e", dst), parent, timeoutMs = 8_000)
        require(checkExists.exitCode != 0) { "目标名称已存在：${dst.substringAfterLast('/')}" }
        val res = exec(listOf("mv", "--", src, dst), parent, timeoutMs = 15_000)
        require(res.exitCode == 0) { res.stderr.ifBlank { res.stdout }.trim().ifBlank { "重命名失败" } }
    }

    /** 利用官方 Codex fuzzyFileSearch 在工作区内跨目录模糊搜索文件。 */
    suspend fun fuzzySearch(query: String, root: String): List<FuzzyMatch> {
        check(root)
        val result = runtime.requestObject(
            EngineKind.CODEX,
            "fuzzyFileSearch",
            jsonOf("query" to query, "roots" to JSONArray().put(root), "cancellationToken" to null),
        )
        return result.arr("files").objects().map {
            FuzzyMatch(path = it.optString("path"), fileName = it.optString("file_name"))
        }.filter { it.path.isNotBlank() }
    }

    /** 在本地 Linux 环境中执行用户发起的单次命令。 */
    suspend fun exec(command: List<String>, cwd: String, timeoutMs: Int = 30_000): CommandResult {
        check(cwd)
        require(command.isNotEmpty()) { "命令不能为空" }
        val result = runtime.requestObject(
            EngineKind.CODEX,
            "command/exec",
            jsonOf(
                "command" to JSONArray(command),
                "cwd" to cwd,
                "env" to environment.effectiveEnvironment(cwd),
                "timeoutMs" to timeoutMs,
                "outputBytesCap" to 256 * 1024,
                "sandboxPolicy" to jsonOf("type" to "dangerFullAccess"),
            ),
        )
        return CommandResult(
            exitCode = result.optInt("exitCode", -1),
            stdout = result.optString("stdout"),
            stderr = result.optString("stderr"),
        )
    }

    /** 在当前目录查询 Git 状态与未提交 Diff（包含未跟踪的新增文本文件预览，正确支持中文/非 ASCII 文件名）。 */
    suspend fun gitOverview(cwd: String): GitOverview {
        val status = exec(listOf("git", "-c", "core.quotepath=false", "status", "--short", "-b"), cwd, timeoutMs = 15_000)
        if (status.exitCode != 0) {
            return GitOverview(false, status.stderr.ifBlank { "当前目录不是 Git 仓库" }.trim(), emptyList())
        }
        val diff = exec(listOf("git", "-c", "core.quotepath=false", "diff", "HEAD"), cwd, timeoutMs = 20_000)
        val raw = if (diff.exitCode == 0) diff.stdout else exec(listOf("git", "-c", "core.quotepath=false", "diff"), cwd, timeoutMs = 20_000).stdout
        val diffs = splitMultiFileDiff(raw).toMutableList()
        val trackedPaths = diffs.map { it.path }.toSet()
        val untrackedRes = exec(listOf("git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"), cwd, timeoutMs = 10_000)
        if (untrackedRes.exitCode == 0 && untrackedRes.stdout.isNotBlank()) {
            val base = cwd.trimEnd('/')
            for (rel in untrackedRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() && it !in trackedPaths }.take(10)) {
                val preview = runCatching { read("$base/$rel", limit = 64 * 1024) }.getOrNull()
                if (preview is FilePreview.Text) {
                    val lines = preview.content.lines()
                    val body = buildString {
                        append("@@ -0,0 +1,${lines.size} @@\n")
                        lines.take(200).forEach { append('+').append(it).append('\n') }
                        if (lines.size > 200) append("... (${lines.size - 200} lines omitted)\n")
                    }
                    diffs += FileDiff(path = rel, kind = "add", diff = body)
                }
            }
        }
        return GitOverview(true, status.stdout.trim(), diffs)
    }

    suspend fun gitLog(cwd: String, limit: Int = 25): List<GitCommit> {
        val res = exec(listOf("git", "log", "-n", limit.toString(), "--format=%h%x1f%s%x1f%an%x1f%ar"), cwd, timeoutMs = 12_000)
        if (res.exitCode != 0 || res.stdout.isBlank()) return emptyList()
        return res.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split('\u001F')
            if (parts.size < 4) null
            else GitCommit(hash = parts[0].trim(), subject = parts[1].trim(), author = parts[2].trim(), relTime = parts[3].trim())
        }.toList()
    }

    suspend fun gitInit(cwd: String): CommandResult =
        exec(listOf("git", "init", "-b", "main"), cwd, timeoutMs = 15_000)

    suspend fun gitBranches(cwd: String): Pair<String, List<String>> {
        val res = exec(listOf("git", "branch", "--format=%(HEAD)|%(refname:short)"), cwd, timeoutMs = 10_000)
        if (res.exitCode != 0) return "" to emptyList()
        var current = ""
        val all = mutableListOf<String>()
        for (line in res.stdout.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.contains('|')) continue
            val isHead = trimmed.startsWith("*|")
            val name = trimmed.substringAfter('|').trim()
            if (name.isNotBlank()) {
                all += name
                if (isHead) current = name
            }
        }
        if (current.isBlank()) {
            val head = exec(listOf("git", "symbolic-ref", "--short", "HEAD"), cwd, timeoutMs = 8_000)
            if (head.exitCode == 0) current = head.stdout.trim()
        }
        return current to all
    }

    suspend fun gitCheckout(cwd: String, branch: String, createNew: Boolean = false): CommandResult {
        val clean = branch.trim()
        require(clean.isNotBlank() && !clean.startsWith("-")) { "分支名无效" }
        val cmd = if (createNew) listOf("git", "checkout", "-b", clean) else listOf("git", "checkout", clean)
        return exec(cmd, cwd, timeoutMs = 20_000)
    }

    suspend fun gitStageAndCommit(cwd: String, message: String): CommandResult {
        val msg = message.trim()
        require(msg.isNotBlank()) { "提交说明不能为空" }
        val checkEmail = exec(listOf("git", "config", "user.email"), cwd, timeoutMs = 8_000)
        if (checkEmail.exitCode != 0 || checkEmail.stdout.isBlank()) {
            exec(listOf("git", "config", "user.name", "Solbraid Developer"), cwd, timeoutMs = 8_000)
            exec(listOf("git", "config", "user.email", "agent@solbraid.local"), cwd, timeoutMs = 8_000)
        }
        val addRes = exec(listOf("git", "add", "-A"), cwd, timeoutMs = 20_000)
        if (addRes.exitCode != 0) return addRes
        return exec(listOf("git", "commit", "-m", msg), cwd, timeoutMs = 25_000)
    }

    suspend fun gitPull(cwd: String): CommandResult =
        exec(listOf("git", "pull", "--ff-only"), cwd, timeoutMs = 60_000)

    suspend fun gitPush(cwd: String): CommandResult =
        exec(listOf("git", "push", "-u", "origin", "HEAD"), cwd, timeoutMs = 60_000)
}

internal fun splitMultiFileDiff(raw: String): List<FileDiff> {
    if (raw.isBlank()) return emptyList()
    val result = mutableListOf<FileDiff>()
    val current = StringBuilder()
    var path = ""
    var kind = "update"
    fun flush() {
        if (path.isNotBlank() && current.isNotEmpty()) {
            result += FileDiff(path, kind, current.toString())
        }
        current.clear()
        path = ""
        kind = "update"
    }
    for (line in raw.lineSequence()) {
        if (line.startsWith("diff --git ")) {
            flush()
            path = when {
                line.contains(" \"b/") -> line.substringAfterLast(" \"b/").trim().removeSuffix("\"")
                line.contains(" b/") -> line.substringAfterLast(" b/").trim().removeSurrounding("\"")
                else -> line.removePrefix("diff --git ").substringBefore(' ').trim().removeSurrounding("\"").removePrefix("a/").removePrefix("b/")
            }
            current.append(line).append('\n')
        } else {
            if (line.startsWith("new file mode")) kind = "add"
            else if (line.startsWith("deleted file mode")) kind = "delete"
            current.append(line).append('\n')
        }
    }
    flush()
    return result
}
