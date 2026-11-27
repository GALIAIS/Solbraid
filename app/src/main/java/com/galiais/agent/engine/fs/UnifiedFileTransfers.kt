package com.galiais.agent.engine.fs

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
    ancestors: Set<String> = emptySet(),
    onBytes: (Long) -> Unit,
) {
    if (cancelled.get()) throw InterruptedException("已取消")
    assertWriteAllowed(dst.scope, dst.normalizedPath)
    val srcNode = stat(src)
    val sourceId = transferIdentity(src)
    check(sourceId != transferIdentity(dst)) { "不能覆盖源文件本身" }
    check(sourceId !in ancestors) { "目录包含循环链接：${srcNode.name}" }
    if (srcNode.isDirectory) {
        assertTransferDestination(src, dst)
        val children = list(src.scope, src.normalizedPath, includeHidden = true)
        if (!isSafPath(dst.scope, dst.normalizedPath)) mkdir(dst.scope, dst.normalizedPath)
        if (!isSafPath(src.scope, src.path) && !isSafPath(dst.scope, dst.path) &&
            (usesRootBackend(src.scope) || usesRootBackend(dst.scope))) {
            rootDaemon.copyDirectoryContents(physicalPathFor(src.scope, src.path), physicalPathFor(dst.scope, dst.path))
            return
        }
        var total = 0L
        for (child in children) {
            if (cancelled.get()) throw InterruptedException("已取消")
            val childDst = prepareTransferTarget(dst.scope, dst.normalizedPath, child.name, child.isDirectory, ConflictPolicy.OVERWRITE, child.octalMode)!!
            val base = total
            copySingleRef(FileRef(child.scope, child.path), childDst, cancelled, ancestors + sourceId) { bytes ->
                total = base + bytes
                onBytes(total)
            }
        }
    } else {
        check(!srcNode.isSpecialFile) { "不能复制特殊设备文件：${srcNode.name}" }
        val staged = stageReadableFile(src)
        val tmp = rootDaemon.newStageFile("copy-")
        try {
            staged.inputStream().use { input ->
                tmp.outputStream().use { output -> ExternalStorageBridge.copyStream(input, output, cancelled, onBytes) }
            }
            if (cancelled.get()) throw InterruptedException("已取消")
            writeLocalFileTo(dst, tmp, createModeOctal = srcNode.octalMode)
        } finally {
            tmp.delete()
        }
    }
}

private fun UnifiedFileRepository.transferIdentity(ref: FileRef): String {
    if (isSafPath(ref.scope, ref.normalizedPath)) {
        val (_, uri, docId) = parseSafRef(ref.normalizedPath)
        if (uri.authority == "com.android.externalstorage.documents" && docId.startsWith("primary:")) {
            return File(Environment.getExternalStorageDirectory(), docId.substringAfter(':')).canonicalPath
        }
        return "document:${uri.authority}:$docId"
    }
    val physical = File(physicalPathFor(ref.scope, ref.normalizedPath))
    if (usesRootBackend(ref.scope) || (!physical.exists() && hasRoot)) {
        val quoted = "'" + physical.absolutePath.replace("'", "'\"'\"'") + "'"
        val resolved = rootDaemon.exec("readlink -f -- $quoted")
        check(resolved.exitCode == 0 && resolved.stdout.trim().startsWith('/')) { "无法确认目标实际路径：${ref.path}" }
        return resolved.stdout.trim()
    }
    return physical.canonicalPath
}

private suspend fun UnifiedFileRepository.assertTransferDestination(src: FileRef, destination: FileRef) {
    val sourceId = transferIdentity(src)
    val targetId = transferIdentity(destination)
    check(targetId != sourceId) { "不能将目录复制或移动到自身" }
    if (!sourceId.startsWith("document:") && !targetId.startsWith("document:")) {
        check(!targetId.startsWith(sourceId.trimEnd('/') + "/")) { "不能将目录复制或移动到其子目录" }
    } else if (isSafPath(src.scope, src.normalizedPath) && isSafPath(destination.scope, destination.normalizedPath)) {
        val (_, sourceTree, sourceDoc) = parseSafRef(src.normalizedPath)
        val (_, targetTree, targetDoc) = parseSafRef(destination.normalizedPath)
        if (sourceTree.authority == targetTree.authority) {
            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(sourceTree, sourceDoc)
            val targetUri = DocumentsContract.buildDocumentUriUsingTree(targetTree, targetDoc)
            val isChild = try {
                DocumentsContract.isChildDocument(app.contentResolver, sourceUri, targetUri)
            } catch (_: UnsupportedOperationException) {
                val pending = ArrayDeque<FileRef>().apply { add(src) }
                val visited = mutableSetOf<String>()
                var found = false
                while (pending.isNotEmpty() && !found) {
                    val next = pending.removeLast()
                    if (!visited.add(transferIdentity(next))) continue
                    for (node in list(next.scope, next.path, includeHidden = true)) {
                        val child = FileRef(node.scope, node.path)
                        if (transferIdentity(child) == targetId) { found = true; break }
                        if (node.isDirectory) pending.add(child)
                    }
                }
                found
            }
            check(!isChild) { "不能将目录复制或移动到其子目录" }
        }
    }
}

internal suspend fun UnifiedFileRepository.prepareTransferTarget(
    scope: FileScope, dir: String, name: String, directory: Boolean, policy: ConflictPolicy,
    modeOctal: String = "644",
): FileRef? {
    var candidate = name
    var existing = findExistingInDirectory(scope, dir, candidate)
    if (existing != null) {
        when (policy) {
            ConflictPolicy.SKIP -> return null
            ConflictPolicy.OVERWRITE -> {
                check(stat(existing).isDirectory == directory) { "同名文件与文件夹冲突：$name" }
                return existing
            }
            ConflictPolicy.RENAME, ConflictPolicy.ASK -> {
                val stem = name.substringBeforeLast('.', name)
                val ext = name.removePrefix(stem)
                var i = 1
                do {
                    candidate = "$stem (${i++})$ext"
                    existing = findExistingInDirectory(scope, dir, candidate)
                } while (existing != null)
            }
        }
    }
    return if (directory) createDirectoryIn(scope, dir, candidate)
    else createFileInDirectory(scope, dir, candidate, octalMode = modeOctal)
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
            val node = stat(srcRef)
            if (clip.mode == ClipboardMode.CUT) {
                assertWriteAllowed(srcRef.scope, srcRef.normalizedPath)
                check(!rootDaemon.isUnremovablePath(srcRef.scope, srcRef.normalizedPath)) { "不能移动系统或工作区根目录" }
            }
            if (node.isDirectory) assertTransferDestination(srcRef, FileRef(targetScope, targetDir))
            val existing = findExistingInDirectory(targetScope, targetDir, node.name)
            val sameTarget = existing != null && transferIdentity(srcRef) == transferIdentity(existing)
            if (sameTarget && clip.mode == ClipboardMode.CUT) continue
            val dstRef = prepareTransferTarget(targetScope, targetDir, node.name, node.isDirectory,
                if (sameTarget) ConflictPolicy.RENAME else conflictPolicy, node.octalMode) ?: continue
            report(node.name, idx, totalBytes)
            if (clip.mode == ClipboardMode.CUT && !isSafPath(srcRef.scope, srcRef.path) && !isSafPath(dstRef.scope, dstRef.path) &&
                !usesRootBackend(srcRef.scope) && !usesRootBackend(dstRef.scope)) {
                val source = File(physicalPathFor(srcRef.scope, srcRef.path))
                val target = File(physicalPathFor(dstRef.scope, dstRef.path))
                if (source.renameTo(target)) {
                    processed++
                    report(node.name, idx + 1, totalBytes)
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
    val rootTarget = prepareTransferTarget(targetScope, targetDir, rootFolderName, true, conflictPolicy)
        ?: return@withContext "已跳过同名文件夹「$rootFolderName」"
    var written = 0
    runTrackedTransfer(title = "上传文件夹「$rootFolderName」", totalItems = items.size) { cancelled, report ->
        var bytesDone = 0L
        for ((idx, pair) in items.withIndex()) {
            if (cancelled.get()) throw InterruptedException("已取消上传文件夹")
            val (relPath, docUri) = pair
            val segments = splitValidatedRelativePath(relPath)
            val parent = if (segments.size == 1) rootTarget else
                createDirectoryIn(targetScope, rootTarget.path, segments.dropLast(1).joinToString("/"))
            val target = prepareTransferTarget(targetScope, parent.path, segments.last(), false, conflictPolicy) ?: continue
            val tmp = rootDaemon.newStageFile("dirup-")
            try {
                app.contentResolver.openInputStream(docUri)?.use { input ->
                    tmp.outputStream().use { out ->
                        ExternalStorageBridge.copyStream(input, out, cancelled) { b -> report(relPath, idx, bytesDone + b) }
                    }
                } ?: error("无法读取外部文件：$relPath")
                bytesDone += tmp.length()
                writeLocalFileTo(target, tmp)
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
    val visitedInodes = HashSet<Pair<Long, Long>>()
    val visitedPaths = HashSet<String>()
    fun walk(dir: File, relPrefix: String, depth: Int): List<Pair<String, File>> {
        if (depth > 96) return emptyList()
        val (dirIsLink, dirInode) = LocalFsHelper.lstatIdentity(dir)
        if (dirIsLink) return emptyList()
        if (dirInode != null && !visitedInodes.add(dirInode)) return emptyList()
        if (dirInode == null) {
            val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visitedPaths.add(canon)) return emptyList()
        }
        val out = mutableListOf<Pair<String, File>>()
        for (c in dir.listFiles().orEmpty()) {
            val (cIsLink, _) = LocalFsHelper.lstatIdentity(c)
            if (cIsLink) continue
            val rel = if (relPrefix.isEmpty()) c.name else "$relPrefix/${c.name}"
            if (c.isDirectory) out += walk(c, rel, depth + 1) else if (c.isFile) out += rel to c
        }
        return out
    }
    val safTarget = isSafPath(targetScope, targetDir)
    for ((rel, file) in walk(base, "", 0)) {
        if (cancelled.get()) throw InterruptedException("已取消")
        if (safTarget) {
            val segments = splitValidatedRelativePath(rel)
            val parentRef = if (segments.size == 1) FileRef(targetScope, targetDir)
            else createDirectoryIn(targetScope, targetDir, segments.dropLast(1).joinToString("/"))
            val dstRef = prepareTransferTarget(targetScope, parentRef.path, segments.last(), directory = false, policy = conflictPolicy)
                ?: continue
            writeLocalFileTo(dstRef, file)
            onFile(rel, file.length())
            continue
        }
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

/** 解压压缩包到指定目录（支持 `.zip` 原生流式解压及 `.tar.*` 安全预检解压）。 */
suspend fun UnifiedFileRepository.extractArchive(ref: FileRef, targetScope: FileScope, targetDir: String): Int =
    withContext(Dispatchers.IO) {
        val ext = ref.name.lowercase()
        val staged = stageReadableFile(ref)
        val tmpOut = File(app.cacheDir, "extract-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            runTrackedTransfer(title = "解压 ${ref.name}") { cancelled, report ->
                val count = if (ext.endsWith(".zip") || ext.endsWith(".jar") || ext.endsWith(".apk") || ext.endsWith(".aar")) {
                    staged.inputStream().use { input ->
                        ArchiveEngine.extractZipStream(input, tmpOut, cancelled = cancelled) { name, done, bytes ->
                            report(name, done, bytes)
                        }
                    }
                } else {
                    // 安全预检 tar 列表：拦截路径穿越（../、绝对路径）、符号链接/硬链接/设备文件及超量条目
                    val listProc = ProcessBuilder("tar", "-tf", staged.absolutePath).redirectErrorStream(true).start()
                    var entryCount = 0
                    listProc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (rawLine in lines) {
                            if (cancelled.get()) {
                                listProc.destroyForcibly()
                                throw InterruptedException("已取消解压")
                            }
                            val line = rawLine.trimEnd('\r')
                            if (line.isBlank()) continue
                            entryCount++
                            check(entryCount <= 50_000) { "归档条目数超过安全上限（50,000），已中止解压" }
                            val cleaned = line.removePrefix("./")
                            if (cleaned.isEmpty() || cleaned == ".") continue
                            check(ArchiveEngine.normalizeEntryPath(cleaned) != null) {
                                "检测到归档路径穿越条目，已拦截：${line.take(120)}"
                            }
                        }
                    }
                    check(listProc.waitFor() == 0) { "无法校验归档内容，文件可能已损坏或格式不支持" }

                    val verboseProc = ProcessBuilder("tar", "-tvf", staged.absolutePath).redirectErrorStream(true).start()
                    verboseProc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (rawLine in lines) {
                            if (cancelled.get()) {
                                verboseProc.destroyForcibly()
                                throw InterruptedException("已取消解压")
                            }
                            val typeChar = rawLine.firstOrNull() ?: continue
                            check(typeChar !in setOf('l', 'h', 'b', 'c', 'p')) {
                                "检测到归档包含符号链接/硬链接或设备节点，已安全拦截：${rawLine.take(120)}"
                            }
                        }
                    }
                    check(verboseProc.waitFor() == 0) { "归档条目安全预检失败" }

                    val pb = ProcessBuilder("tar", "-xf", staged.absolutePath, "-C", tmpOut.absolutePath).redirectErrorStream(true).start()
                    val output = pb.inputStream.bufferedReader().readText().take(400)
                    check(pb.waitFor() == 0) { "解压失败：$output" }
                    tmpOut.walkTopDown().count { !LocalFsHelper.lstatIdentity(it).first && it.isFile }
                }
                var committedBytes = 0L
                commitLocalTreeToScope(tmpOut, targetScope, targetDir, ConflictPolicy.RENAME, cancelled) { name, b ->
                    committedBytes += b
                    report(name, count, committedBytes)
                }
                count
            }
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
    val visitedInodes = HashSet<Pair<Long, Long>>()
    val visitedPaths = HashSet<String>()

    fun walk(dir: File, depth: Int) {
        if (depth > 16 || hits.size >= maxResults) return
        val (dirLink, dirInode) = LocalFsHelper.lstatIdentity(dir)
        if (depth > 0 && dirLink) return
        if (dirInode != null && !visitedInodes.add(dirInode)) return
        if (dirInode == null) {
            val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visitedPaths.add(canon)) return
        }
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (hits.size >= maxResults) return
            val (fIsLink, _) = LocalFsHelper.lstatIdentity(f)
            if (fIsLink) continue
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

/** 分析当前目录各子项的磁盘空间占用排行（自动跳过符号链接以防循环遍历）。 */
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
        val (childLink, _) = LocalFsHelper.lstatIdentity(child)
        if (!childLink && child.isDirectory) {
            val visitedInodes = HashSet<Pair<Long, Long>>()
            val visitedPaths = HashSet<String>()
            fun walkSize(d: File, depth: Int) {
                if (depth > 32) return
                val (dLink, dInode) = LocalFsHelper.lstatIdentity(d)
                if (dLink) return
                if (dInode != null && !visitedInodes.add(dInode)) return
                if (dInode == null) {
                    val canon = runCatching { d.canonicalPath }.getOrDefault(d.absolutePath)
                    if (!visitedPaths.add(canon)) return
                }
                for (f in d.listFiles().orEmpty()) {
                    val (fLink, _) = LocalFsHelper.lstatIdentity(f)
                    if (fLink) continue
                    if (f.isDirectory) walkSize(f, depth + 1)
                    else if (f.isFile) { bytes += f.length(); count++ }
                }
            }
            walkSize(child, 0)
        } else if (!childLink && child.isFile) {
            bytes = child.length(); count = 1
        }
        val vPath = if (norm == "/") "/${child.name}" else "$norm/${child.name}"
        DiskUsageEntry(child.name, vPath, !childLink && child.isDirectory, bytes, count)
    }.sortedByDescending { it.bytes }
}
