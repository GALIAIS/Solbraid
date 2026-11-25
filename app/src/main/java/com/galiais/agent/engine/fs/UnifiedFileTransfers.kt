package com.galiais.agent.engine.fs

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [UnifiedFileRepository] 的扩展能力模块：
 * 跨存储域流式复制/移动、批量重命名、外部多文件/整文件夹上传、下载/相册/另存为导出、
 * 流式压缩解压、双模搜索（文件名 + 全文 Grep）、磁盘空间分析与 Git 状态角标。
 */

internal suspend fun UnifiedFileRepository.copySingleRef(
    src: FileRef,
    dst: FileRef,
    cancelled: AtomicBoolean,
    onBytes: (Long) -> Unit,
) {
    assertWriteAllowed(dst.scope, dst.normalizedPath)
    val srcNode = stat(src)
    val srcPhys = physicalPathFor(src.scope, src.normalizedPath)
    val dstPhys = physicalPathFor(dst.scope, dst.normalizedPath)
    if (srcNode.isDirectory) {
        if (usesRootBackend(src.scope) || usesRootBackend(dst.scope) || !File(srcPhys).canRead()) {
            rootDaemon.copyRecursive(srcPhys, dstPhys)
            return
        }
        val dstDir = File(dstPhys).apply { mkdirs() }
        val children = File(srcPhys).listFiles().orEmpty()
        for (c in children) {
            if (cancelled.get()) throw InterruptedException("已取消")
            copySingleRef(
                FileRef(src.scope, joinVfsPath(src.normalizedPath, c.name)),
                FileRef(dst.scope, joinVfsPath(dst.normalizedPath, c.name)),
                cancelled,
                onBytes,
            )
        }
        if (dstDir.exists()) return
    } else {
        if (!usesRootBackend(src.scope) && !usesRootBackend(dst.scope)) {
            val sf = File(srcPhys)
            val df = File(dstPhys)
            if (sf.isFile && sf.canRead() && (df.parentFile?.canWrite() == true || df.parentFile?.mkdirs() == true)) {
                sf.inputStream().use { input ->
                    df.outputStream().use { output -> ExternalStorageBridge.copyStream(input, output, cancelled, onBytes) }
                }
                return
            }
        }
        val staged = stageReadableFile(src)
        writeLocalFileTo(dst, staged)
        onBytes(staged.length())
    }
}

/** 执行剪贴板粘贴（无缝支持任意两个存储域间的跨区复制/移动）。 */
suspend fun UnifiedFileRepository.pasteClipboard(
    targetScope: FileScope,
    targetDir: String,
    conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
): Int = withContext(Dispatchers.IO) {
    val clip = clipboard.value ?: return@withContext 0
    val verb = if (clip.mode == ClipboardMode.CUT) "移动" else "复制"
    var processed = 0
    runTrackedTransfer(title = "$verb ${clip.items.size} 项到 ${targetDir.substringAfterLast('/').ifEmpty { "/" }}", totalItems = clip.items.size) { cancelled, report ->
        var totalBytes = 0L
        for ((idx, srcRef) in clip.items.withIndex()) {
            if (cancelled.get()) throw InterruptedException("已取消$verb")
            var dstPath = joinVfsPath(targetDir, srcRef.name)
            if (srcRef.scope == targetScope && srcRef.normalizedPath == dstPath) {
                if (clip.mode == ClipboardMode.CUT) continue
                dstPath = resolveUniquePath(targetScope, targetDir, srcRef.name)
            } else {
                val exists = runCatching { stat(FileRef(targetScope, dstPath)); true }.getOrDefault(false)
                if (exists) {
                    when (conflictPolicy) {
                        ConflictPolicy.SKIP -> continue
                        ConflictPolicy.RENAME, ConflictPolicy.ASK -> dstPath = resolveUniquePath(targetScope, targetDir, srcRef.name)
                        ConflictPolicy.OVERWRITE -> Unit
                    }
                }
            }
            val dstRef = FileRef(targetScope, dstPath)
            report(srcRef.name, idx, totalBytes)
            // 同物理分区内移动直接 rename 秒完
            if (clip.mode == ClipboardMode.CUT && srcRef.scope == targetScope && !usesRootBackend(srcRef.scope)) {
                val sf = File(physicalPathFor(srcRef.scope, srcRef.normalizedPath))
                val df = File(physicalPathFor(targetScope, dstPath))
                if (sf.renameTo(df)) {
                    processed++
                    report(srcRef.name, idx + 1, totalBytes)
                    continue
                }
            }
            val baseBytes = totalBytes
            var itemCopied = 0L
            copySingleRef(srcRef, dstRef, cancelled) { copied ->
                itemCopied = copied
                report(srcRef.name, idx, baseBytes + copied)
            }
            totalBytes = baseBytes + itemCopied
            if (clip.mode == ClipboardMode.CUT) {
                delete(srcRef, moveToTrash = false)
            }
            processed++
            report(srcRef.name, idx + 1, totalBytes)
        }
    }
    if (clip.mode == ClipboardMode.CUT) clearClipboard()
    processed
}

private suspend fun UnifiedFileRepository.resolveUniquePath(scope: FileScope, dir: String, name: String): String {
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it == name || it.isEmpty()) "" else ".$it" }
    var idx = 1
    var candidate = joinVfsPath(dir, name)
    while (runCatching { stat(FileRef(scope, candidate)); true }.getOrDefault(false)) {
        candidate = joinVfsPath(dir, "$stem ($idx)$ext")
        idx++
    }
    return candidate
}

/** 批量上传外部多选文件到当前目录（无 4MB 上限；遇到 ZIP 可选直接保存原包或解压导入）。 */
suspend fun UnifiedFileRepository.importExternalUris(
    uris: List<Uri>,
    targetScope: FileScope,
    targetDir: String,
    extractZip: Boolean = false,
    conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
): String = withContext(Dispatchers.IO) {
    require(uris.isNotEmpty()) { "未选择文件" }
    var totalFiles = 0
    runTrackedTransfer(title = "上传 ${uris.size} 个外部文件", totalItems = uris.size) { cancelled, report ->
        var bytesDone = 0L
        for ((idx, uri) in uris.withIndex()) {
            if (cancelled.get()) throw InterruptedException("已取消上传")
            val rawName = ExternalStorageBridge.sanitizeFilename(ExternalStorageBridge.queryDisplayName(app, uri))
            report(rawName, idx, bytesDone)
            if (extractZip && rawName.lowercase().endsWith(".zip")) {
                val extractDir = File(app.cacheDir, "zip-in-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
                try {
                    val count = app.contentResolver.openInputStream(uri)?.use { input ->
                        ArchiveEngine.extractZipStream(input, extractDir, cancelled = cancelled) { name, _, b ->
                            report(name, idx, bytesDone + b)
                        }
                    } ?: error("无法读取 ZIP 文件：$rawName")
                    commitLocalTreeToScope(extractDir, targetScope, targetDir, conflictPolicy, cancelled) { name, b ->
                        bytesDone += b
                        report(name, idx, bytesDone)
                    }
                    totalFiles += count
                } finally {
                    extractDir.deleteRecursively()
                }
            } else {
                var dstPath = joinVfsPath(targetDir, rawName)
                val exists = runCatching { stat(FileRef(targetScope, dstPath)); true }.getOrDefault(false)
                if (exists) {
                    when (conflictPolicy) {
                        ConflictPolicy.SKIP -> continue
                        ConflictPolicy.RENAME, ConflictPolicy.ASK -> dstPath = resolveUniquePath(targetScope, targetDir, rawName)
                        ConflictPolicy.OVERWRITE -> Unit
                    }
                }
                val tmp = rootDaemon.newStageFile("up-")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out ->
                            ExternalStorageBridge.copyStream(input, out, cancelled) { b -> report(rawName, idx, bytesDone + b) }
                        }
                    } ?: error("无法读取外部文件：$rawName")
                    bytesDone += tmp.length()
                    writeLocalFileTo(FileRef(targetScope, dstPath), tmp)
                    totalFiles++
                } finally {
                    tmp.delete()
                }
            }
            report(rawName, idx + 1, bytesDone)
        }
    }
    "已上传 $totalFiles 个文件到 ${targetDir.substringAfterLast('/').ifEmpty { "/" }}"
}

/** 整文件夹递归上传（通过 Android SAF DocumentTree）。 */
suspend fun UnifiedFileRepository.importExternalFolderTree(
    treeUri: Uri,
    targetScope: FileScope,
    targetDir: String,
    conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
): String = withContext(Dispatchers.IO) {
    val items = ExternalStorageBridge.walkDocumentTree(app, treeUri)
    check(items.isNotEmpty()) { "所选文件夹为空" }
    val rootFolderName = ExternalStorageBridge.sanitizeFilename(ExternalStorageBridge.queryDisplayName(app, treeUri))
    val baseTarget = joinVfsPath(targetDir, rootFolderName)
    mkdir(targetScope, baseTarget)
    var written = 0
    runTrackedTransfer(title = "上传文件夹「$rootFolderName」", totalItems = items.size) { cancelled, report ->
        var bytesDone = 0L
        for ((idx, pair) in items.withIndex()) {
            if (cancelled.get()) throw InterruptedException("已取消上传文件夹")
            val (relPath, docUri) = pair
            val fullTarget = normalizeVfsPath("$baseTarget/$relPath")
            val parentDir = fullTarget.substringBeforeLast('/').ifEmpty { "/" }
            mkdir(targetScope, parentDir)
            val tmp = rootDaemon.newStageFile("dirup-")
            try {
                app.contentResolver.openInputStream(docUri)?.use { input ->
                    tmp.outputStream().use { out ->
                        ExternalStorageBridge.copyStream(input, out, cancelled) { b -> report(relPath, idx, bytesDone + b) }
                    }
                } ?: continue
                bytesDone += tmp.length()
                writeLocalFileTo(FileRef(targetScope, fullTarget), tmp)
                written++
            } finally {
                tmp.delete()
            }
            report(relPath, idx + 1, bytesDone)
        }
    }
    "已上传文件夹「$rootFolderName」（共 $written 个文件）"
}

private suspend fun UnifiedFileRepository.commitLocalTreeToScope(
    localDir: File,
    targetScope: FileScope,
    targetDir: String,
    conflictPolicy: ConflictPolicy,
    cancelled: AtomicBoolean,
    onFile: (String, Long) -> Unit,
) {
    val base = localDir.canonicalFile
    fun walk(dir: File, relPrefix: String): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        for (c in dir.listFiles().orEmpty()) {
            val rel = if (relPrefix.isEmpty()) c.name else "$relPrefix/${c.name}"
            if (c.isDirectory) out += walk(c, rel) else if (c.isFile) out += rel to c
        }
        return out
    }
    for ((rel, file) in walk(base, "")) {
        if (cancelled.get()) throw InterruptedException("已取消")
        val fullTarget = normalizeVfsPath("$targetDir/$rel")
        val parent = fullTarget.substringBeforeLast('/').ifEmpty { "/" }
        mkdir(targetScope, parent)
        val finalTarget = if (conflictPolicy != ConflictPolicy.OVERWRITE && runCatching { stat(FileRef(targetScope, fullTarget)); true }.getOrDefault(false)) {
            if (conflictPolicy == ConflictPolicy.SKIP) continue
            resolveUniquePath(targetScope, parent, file.name)
        } else fullTarget
        writeLocalFileTo(FileRef(targetScope, finalTarget), file)
        onFile(rel, file.length())
    }
}

/** 导出单文件到手机「Download/Solbraid」目录。 */
suspend fun UnifiedFileRepository.saveFileToDownloads(ref: FileRef): String = withContext(Dispatchers.IO) {
    runTrackedTransfer(title = "保存到下载：${ref.name}") { cancelled, report ->
        val staged = stageReadableFile(ref)
        ExternalStorageBridge.saveToDownloads(app, staged, ref.name, cancelled = cancelled) { b -> report(ref.name, 0, b) }
    }
}

/** 导出单文件到 SAF 选定的任意外部位置。 */
suspend fun UnifiedFileRepository.exportSingleFileToUri(ref: FileRef, destUri: Uri) = withContext(Dispatchers.IO) {
    runTrackedTransfer(title = "另存为：${ref.name}") { cancelled, report ->
        val staged = stageReadableFile(ref)
        ExternalStorageBridge.exportToUri(app, staged, destUri, cancelled = cancelled) { b -> report(ref.name, 0, b) }
    }
}

/** 流式打包选定文件/目录为 ZIP 并写入目标 URI（或原地保存）。 */
suspend fun UnifiedFileRepository.compressToZipUri(refs: List<FileRef>, destUri: Uri, excludeBuildDirs: Boolean = false): Int =
    withContext(Dispatchers.IO) {
        val stagedDir = File(app.cacheDir, "zip-out-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            val sources = refs.map { ref ->
                val phys = File(physicalPathFor(ref.scope, ref.normalizedPath))
                if (!usesRootBackend(ref.scope) && phys.exists() && phys.canRead()) {
                    ref.name to phys
                } else if (runCatching { stat(ref).isDirectory }.getOrDefault(false)) {
                    val sub = File(stagedDir, ExternalStorageBridge.sanitizeFilename(ref.name)).apply { mkdirs() }
                    rootDaemon.stageDirectoryToReadable(phys.absolutePath, sub)
                    ref.name to sub
                } else {
                    ref.name to stageReadableFile(ref)
                }
            }
            runTrackedTransfer(title = "打包导出 ZIP", totalItems = refs.size) { cancelled, report ->
                app.contentResolver.openOutputStream(destUri, "wt")?.use { out ->
                    ArchiveEngine.createZipStream(sources, out, excludeBuildDirs, cancelled) { item, done, bytes ->
                        report(item, done, bytes)
                    }
                } ?: error("无法打开 ZIP 导出目标")
            }
        } finally {
            stagedDir.deleteRecursively()
        }
    }

/** 在当前目录原地创建 ZIP 压缩包。 */
suspend fun UnifiedFileRepository.compressInPlace(refs: List<FileRef>, targetScope: FileScope, targetDir: String, archiveName: String): FileRef =
    withContext(Dispatchers.IO) {
        val cleanName = if (archiveName.lowercase().endsWith(".zip")) archiveName else "$archiveName.zip"
        val tmpZip = rootDaemon.newStageFile("pack-")
        val stagedDir = File(app.cacheDir, "zip-in-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            val sources = refs.map { ref ->
                val phys = File(physicalPathFor(ref.scope, ref.normalizedPath))
                if (!usesRootBackend(ref.scope) && phys.exists() && phys.canRead()) {
                    ref.name to phys
                } else if (runCatching { stat(ref).isDirectory }.getOrDefault(false)) {
                    val sub = File(stagedDir, ExternalStorageBridge.sanitizeFilename(ref.name)).apply { mkdirs() }
                    rootDaemon.stageDirectoryToReadable(phys.absolutePath, sub)
                    ref.name to sub
                } else {
                    ref.name to stageReadableFile(ref)
                }
            }
            runTrackedTransfer(title = "压缩为 $cleanName", totalItems = refs.size) { cancelled, report ->
                tmpZip.outputStream().use { out ->
                    ArchiveEngine.createZipStream(sources, out, excludeBuildDirs = false, cancelled = cancelled) { item, done, bytes ->
                        report(item, done, bytes)
                    }
                }
            }
            val dstRef = FileRef(targetScope, joinVfsPath(targetDir, cleanName))
            writeLocalFileTo(dstRef, tmpZip)
            dstRef
        } finally {
            tmpZip.delete()
            stagedDir.deleteRecursively()
        }
    }

/** 解压压缩包到指定目录（支持 `.zip` 原生流式解压及 `.tar.*`/`.7z` 系统命令解压）。 */
suspend fun UnifiedFileRepository.extractArchive(ref: FileRef, targetScope: FileScope, targetDir: String): Int =
    withContext(Dispatchers.IO) {
        val ext = ref.name.lowercase()
        val staged = stageReadableFile(ref)
        val tmpOut = File(app.cacheDir, "extract-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            val count = runTrackedTransfer(title = "解压 ${ref.name}") { cancelled, report ->
                if (ext.endsWith(".zip") || ext.endsWith(".jar") || ext.endsWith(".apk") || ext.endsWith(".aar")) {
                    staged.inputStream().use { input ->
                        ArchiveEngine.extractZipStream(input, tmpOut, cancelled = cancelled) { name, done, bytes ->
                            report(name, done, bytes)
                        }
                    }
                } else {
                    // 使用 tar 解压 .tar / .tar.gz / .tgz / .tar.xz / .tar.bz2
                    val pb = ProcessBuilder("tar", "-xf", staged.absolutePath, "-C", tmpOut.absolutePath).redirectErrorStream(true).start()
                    val output = pb.inputStream.bufferedReader().readText()
                    check(pb.waitFor() == 0) { "解压失败：${output.take(400)}" }
                    tmpOut.walkTopDown().count { it.isFile }
                }
            }
            commitLocalTreeToScope(tmpOut, targetScope, targetDir, ConflictPolicy.OVERWRITE, AtomicBoolean(false)) { _, _ -> }
            count
        } finally {
            tmpOut.deleteRecursively()
        }
    }

/** 批量重命名（支持前后缀、查找替换/正则、序号递增）。 */
suspend fun UnifiedFileRepository.batchRename(
    refs: List<FileRef>,
    findText: String,
    replaceText: String,
    prefix: String,
    suffix: String,
    useRegex: Boolean,
    addNumbering: Boolean,
): Int = withContext(Dispatchers.IO) {
    val regex = if (useRegex && findText.isNotEmpty()) Regex(findText) else null
    var renamed = 0
    refs.forEachIndexed { idx, ref ->
        val old = ref.name
        val stem = old.substringBeforeLast('.', old)
        val ext = old.substringAfterLast('.', "").let { if (it == old || it.isEmpty()) "" else ".$it" }
        var nextStem = when {
            regex != null -> stem.replace(regex, replaceText)
            findText.isNotEmpty() -> stem.replace(findText, replaceText)
            else -> stem
        }
        nextStem = "$prefix$nextStem$suffix" + if (addNumbering) "_%03d".format(idx + 1) else ""
        val nextName = (nextStem.trim() + ext).ifBlank { old }
        if (nextName != old) {
            rename(ref, nextName)
            renamed++
        }
    }
    renamed
}

/** 双模搜索：文件名模糊/通配搜索 + 正文全文搜索（含正则与区分大小写）。 */
suspend fun UnifiedFileRepository.search(
    scope: FileScope,
    rootPath: String,
    query: String,
    contentSearch: Boolean,
    useRegex: Boolean = false,
    caseSensitive: Boolean = false,
    maxResults: Int = 120,
): List<SearchHit> = withContext(Dispatchers.IO) {
    val q = query.trim()
    if (q.isEmpty()) return@withContext emptyList()
    val physRoot = physicalPathFor(scope, rootPath)
    val normRoot = normalizeVfsPath(rootPath)
    if (usesRootBackend(scope) || !File(physRoot).canRead()) {
        return@withContext if (contentSearch) {
            rootDaemon.grepContent(physRoot, normRoot, scope, q, useRegex, caseSensitive, maxResults)
        } else {
            rootDaemon.searchByName(physRoot, normRoot, scope, q, maxResults)
        }
    }
    val rootFile = File(physRoot)
    if (!rootFile.exists() || !rootFile.canRead()) return@withContext emptyList()
    val pattern = if (useRegex) {
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        runCatching { Regex(q, opts) }.getOrNull() ?: return@withContext emptyList()
    } else null
    val hits = ArrayList<SearchHit>()
    val skipDirs = setOf(".git", "node_modules", ".gradle", "build", "__pycache__", ".venv")
    val baseCanon = rootFile.canonicalPath.trimEnd('/')

    fun walk(dir: File, depth: Int) {
        if (depth > 16 || hits.size >= maxResults) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (hits.size >= maxResults) return
            val rel = f.canonicalPath.removePrefix(baseCanon).trimStart('/')
            val vPath = if (normRoot == "/") "/$rel" else "$normRoot/$rel"
            if (f.isDirectory) {
                if (f.name in skipDirs) continue
                if (!contentSearch) {
                    val matched = pattern?.containsMatchIn(f.name) ?: f.name.contains(q, ignoreCase = !caseSensitive)
                    if (matched) hits += SearchHit(scope, vPath, f.name, isDirectory = true)
                }
                walk(f, depth + 1)
            } else if (f.isFile) {
                if (!contentSearch) {
                    val matched = pattern?.containsMatchIn(f.name) ?: f.name.contains(q, ignoreCase = !caseSensitive)
                    if (matched) hits += SearchHit(scope, vPath, f.name, isDirectory = false, size = f.length())
                } else {
                    if (f.length() > 2L * 1024 * 1024) continue
                    val head = runCatching { f.inputStream().use { inp -> ByteArray(512).let { b -> b.copyOf(inp.read(b).coerceAtLeast(0)) } } }.getOrNull() ?: continue
                    if (TextCodec.looksBinary(head)) continue
                    runCatching {
                        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            var lineNo = 0
                            for (line in lines) {
                                lineNo++
                                if (hits.size >= maxResults) break
                                val matched = pattern?.containsMatchIn(line) ?: line.contains(q, ignoreCase = !caseSensitive)
                                if (matched) {
                                    hits += SearchHit(scope, vPath, f.name, false, lineNo, line.trim().take(160), f.length())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    walk(rootFile, 0)
    hits
}

/** 分析当前目录各子项的磁盘空间占用排行。 */
suspend fun UnifiedFileRepository.analyzeDiskUsage(scope: FileScope, path: String): List<DiskUsageEntry> = withContext(Dispatchers.IO) {
    val phys = physicalPathFor(scope, path)
    if (usesRootBackend(scope) || !File(phys).canRead()) {
        return@withContext rootDaemon.analyzeDiskUsage(phys, path)
    }
    val dir = File(phys)
    val norm = normalizeVfsPath(path)
    dir.listFiles().orEmpty().map { child ->
        var bytes = 0L
        var count = 0
        if (child.isDirectory) {
            child.walkTopDown().maxDepth(32).forEach { f ->
                if (f.isFile) { bytes += f.length(); count++ }
            }
        } else if (child.isFile) {
            bytes = child.length(); count = 1
        }
        val vPath = if (norm == "/") "/${child.name}" else "$norm/${child.name}"
        DiskUsageEntry(child.name, vPath, child.isDirectory, bytes, count)
    }.sortedByDescending { it.bytes }
}
