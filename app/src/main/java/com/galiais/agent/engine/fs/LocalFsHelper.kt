package com.galiais.agent.engine.fs

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.galiais.agent.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 直通本地文件系统工具：
 * 1. 通过 Java File + Android Os.lstat/readlink 零拷贝高速访问应用自有目录（Linux 容器普通目录）及 Android 外部存储；
 * 2. 支持 Android SAF 持久化授权目录（`saf://<treeId>/...`）遍历与读写。
 */
internal object LocalFsHelper {
    fun linuxRootDir(context: Context, runtimeMode: String): File = LinuxRuntime(context, runtimeMode).root

    /** 将虚拟路径映射为宿主物理 File（仅适用于本地/容器存储域）。 */
    fun resolvePhysicalFile(context: Context, scope: FileScope, virtualPath: String, runtimeMode: String): File {
        val norm = normalizeVfsPath(virtualPath)
        return when (scope) {
            FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT -> {
                val root = linuxRootDir(context, runtimeMode)
                if (norm == "/") root else File(root, norm.removePrefix("/"))
            }
            FileScope.HOST_ROOT -> File(norm)
            FileScope.ANDROID_STORAGE -> File(norm)
        }
    }

    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun formatModeString(stMode: Int): Pair<String, String> {
        val typeChar = when (stMode and OsConstants.S_IFMT) {
            OsConstants.S_IFDIR -> 'd'
            OsConstants.S_IFLNK -> 'l'
            OsConstants.S_IFCHR -> 'c'
            OsConstants.S_IFBLK -> 'b'
            OsConstants.S_IFIFO -> 'p'
            OsConstants.S_IFSOCK -> 's'
            else -> '-'
        }
        val bits = stMode and 0xFFF
        val perm = buildString(10) {
            append(typeChar)
            append(if (bits and 0x100 != 0) 'r' else '-')
            append(if (bits and 0x080 != 0) 'w' else '-')
            append(when {
                bits and 0x800 != 0 && bits and 0x040 != 0 -> 's'
                bits and 0x800 != 0 -> 'S'
                bits and 0x040 != 0 -> 'x'
                else -> '-'
            })
            append(if (bits and 0x020 != 0) 'r' else '-')
            append(if (bits and 0x010 != 0) 'w' else '-')
            append(when {
                bits and 0x400 != 0 && bits and 0x008 != 0 -> 's'
                bits and 0x400 != 0 -> 'S'
                bits and 0x008 != 0 -> 'x'
                else -> '-'
            })
            append(if (bits and 0x004 != 0) 'r' else '-')
            append(if (bits and 0x002 != 0) 'w' else '-')
            append(when {
                bits and 0x200 != 0 && bits and 0x001 != 0 -> 't'
                bits and 0x200 != 0 -> 'T'
                bits and 0x001 != 0 -> 'x'
                else -> '-'
            })
        }
        val octal = "%04o".format(bits).let { if (it.startsWith("0")) it.substring(1) else it }
        return perm to octal
    }

    fun statLocalFile(file: File, scope: FileScope, virtualPath: String): FileNode {
        val st = Os.lstat(file.absolutePath)
        val fmt = st.st_mode and OsConstants.S_IFMT
        val isLink = fmt == OsConstants.S_IFLNK
        val target = if (isLink) runCatching { Os.readlink(file.absolutePath) }.getOrNull() else null
        var isDir = fmt == OsConstants.S_IFDIR
        var broken = false
        var effectiveSize = st.st_size
        if (isLink) {
            val resolved = runCatching { Os.stat(file.absolutePath) }.getOrNull()
            if (resolved == null) {
                broken = true
            } else {
                isDir = (resolved.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR
                effectiveSize = resolved.st_size
            }
        }
        val special = fmt in setOf(OsConstants.S_IFCHR, OsConstants.S_IFBLK, OsConstants.S_IFIFO, OsConstants.S_IFSOCK)
        val (permStr, octal) = formatModeString(st.st_mode)
        val name = virtualPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        return FileNode(
            scope = scope,
            path = normalizeVfsPath(virtualPath),
            name = name,
            isDirectory = isDir,
            isSymlink = isLink,
            symlinkTarget = target,
            isBrokenSymlink = broken,
            size = effectiveSize,
            lastModifiedMs = st.st_mtime * 1000L,
            permissions = permStr,
            octalMode = octal,
            owner = st.st_uid.toString(),
            group = st.st_gid.toString(),
            mimeType = if (isDir) "inode/directory" else TextCodec.guessMimeType(name),
            isSpecialFile = special,
        )
    }

    /** 列出本地目录（若因权限不足返回 null，交由调用方自动无缝切换至 RootFsDaemon）。 */
    fun listLocalDirectoryOrNull(dir: File, scope: FileScope, virtualDir: String): List<FileNode>? {
        if (!dir.exists() || !dir.isDirectory) return null
        val names = dir.list() ?: return null
        val base = normalizeVfsPath(virtualDir)
        val nodes = ArrayList<FileNode>(names.size)
        for (name in names) {
            val childFile = File(dir, name)
            val childVPath = if (base == "/") "/$name" else "$base/$name"
            val node = runCatching { statLocalFile(childFile, scope, childVPath) }.getOrNull()
                ?: FileNode(
                    scope = scope,
                    path = childVPath,
                    name = name,
                    isDirectory = childFile.isDirectory,
                    size = if (childFile.isFile) childFile.length() else 0L,
                    lastModifiedMs = childFile.lastModified(),
                    mimeType = if (childFile.isDirectory) "inode/directory" else TextCodec.guessMimeType(name),
                )
            nodes.add(node)
        }
        return nodes
    }

    /** 列出已挂载 SAF 目录下的子项（使用 DocumentsContract 零依赖高效查询）。 */
    fun listSafChildren(context: Context, treeUri: Uri, parentDocId: String, virtualPrefix: String): List<FileNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<FileNode>()
        (context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: error("无法读取授权目录，请检查存储设备或重新授权")).use { c ->
            val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val idxMod = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val docId = if (idxId >= 0) c.getString(idxId).orEmpty() else continue
                val name = if (idxName >= 0) c.getString(idxName).orEmpty().ifBlank { docId } else docId
                val mime = if (idxMime >= 0) c.getString(idxMime).orEmpty() else ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else 0L
                val mod = if (idxMod >= 0 && !c.isNull(idxMod)) c.getLong(idxMod) else 0L
                val childPath = "${virtualPrefix.trimEnd('/')}/${Uri.encode(docId)}"
                out += FileNode(
                    scope = FileScope.ANDROID_STORAGE,
                    path = childPath,
                    name = name,
                    isDirectory = isDir,
                    size = size,
                    lastModifiedMs = mod,
                    permissions = if (isDir) "drwxrwx---" else "-rw-rw----",
                    octalMode = if (isDir) "770" else "660",
                    owner = "SAF",
                    group = "SAF",
                    mimeType = if (isDir) "inode/directory" else mime.ifBlank { TextCodec.guessMimeType(name) },
                )
            }
        }
        return out
    }

    fun openSafInput(context: Context, treeUri: Uri, docId: String): InputStream {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return context.contentResolver.openInputStream(docUri) ?: error("无法读取外部 SAF 文件")
    }

    fun openSafOutput(context: Context, treeUri: Uri, docId: String): OutputStream {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return context.contentResolver.openOutputStream(docUri, "wt") ?: error("无法写入外部 SAF 文件")
    }

    /** 查询单个 SAF 文档精确元数据（含可写标志位检测）。 */
    fun statSafDocument(context: Context, treeUri: Uri, docId: String, virtualPath: String): FileNode {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        context.contentResolver.query(docUri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxMod = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val idxFlags = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                val name = if (idxName >= 0) c.getString(idxName).orEmpty().ifBlank { docId } else docId
                val mime = if (idxMime >= 0) c.getString(idxMime).orEmpty() else ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else 0L
                val mod = if (idxMod >= 0 && !c.isNull(idxMod)) c.getLong(idxMod) else 0L
                val flags = if (idxFlags >= 0 && !c.isNull(idxFlags)) c.getInt(idxFlags) else 0
                val writable = if (isDir) {
                    (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
                } else {
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                }
                return FileNode(
                    scope = FileScope.ANDROID_STORAGE,
                    path = normalizeVfsPath(virtualPath),
                    name = name,
                    isDirectory = isDir,
                    size = size,
                    lastModifiedMs = mod,
                    permissions = when {
                        isDir && writable -> "drwxrwx---"
                        isDir -> "dr-xr-x---"
                        writable -> "-rw-rw----"
                        else -> "-r--r-----"
                    },
                    octalMode = when {
                        isDir && writable -> "770"
                        isDir -> "550"
                        writable -> "660"
                        else -> "440"
                    },
                    owner = "SAF",
                    group = "SAF",
                    mimeType = if (isDir) "inode/directory" else mime.ifBlank { TextCodec.guessMimeType(name) },
                )
            }
        }
        throw IllegalStateException("外部 SAF 文件不存在或授权已失效：$virtualPath")
    }

    /** 在 SAF 父目录下按显示名精确查找直接子项，返回 Pair(docId, isDirectory)。 */
    fun findSafChildByName(context: Context, treeUri: Uri, parentDocId: String, displayName: String): Pair<String, Boolean>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        (context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: error("无法读取授权目录，请检查存储设备或重新授权")).use { c ->
            val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = if (idxName >= 0) c.getString(idxName).orEmpty() else continue
                if (name == displayName) {
                    val id = if (idxId >= 0) c.getString(idxId).orEmpty() else continue
                    val mime = if (idxMime >= 0) c.getString(idxMime).orEmpty() else ""
                    return id to (mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return null
    }

    /** 在 SAF 目录内创建新文件或子文件夹，返回新建文档的 documentId。 */
    fun createSafDocument(context: Context, treeUri: Uri, parentDocId: String, mimeType: String, displayName: String): String {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, displayName)
            ?: error("无法在外部 SAF 目录中创建：$displayName（请确认目录具备写入权限）")
        return DocumentsContract.getDocumentId(created)
    }

    fun renameSafDocument(context: Context, treeUri: Uri, docId: String, newName: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val renamed = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
            ?: error("重命名外部 SAF 文档失败：$newName")
        return DocumentsContract.getDocumentId(renamed)
    }

    fun deleteSafDocument(context: Context, treeUri: Uri, docId: String): Boolean {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }

    /**
     * 解析本地写入目标并透传软链接：
     * - 若目标是符号链接：拒绝悬空软链，返回真实物理目标 File 与原目标权限位（避免 rename 覆盖摧毁软链）；
     * - 若目标已存在：返回原 File 与当前权限位（07777 掩码）；
     * - 若目标尚不存在：返回原 File 与 null。
     */
    fun resolveWritableTargetPreservingSymlink(file: File): Pair<File, Int?> {
        val st = try {
            Os.lstat(file.absolutePath)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return file to null
            return file to null
        } catch (_: Throwable) {
            return file to null
        }
        val fmt = st.st_mode and OsConstants.S_IFMT
        if (fmt == OsConstants.S_IFLNK) {
            val resolvedSt = try {
                Os.stat(file.absolutePath)
            } catch (e: ErrnoException) {
                throw IllegalStateException("目标为悬空符号链接，已拒绝覆盖：${file.name}")
            }
            if ((resolvedSt.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR) {
                throw IllegalStateException("目标符号链接指向目录，无法按文件写入：${file.name}")
            }
            val realTarget = file.canonicalFile
            return realTarget to (resolvedSt.st_mode and 0xFFF)
        }
        if (fmt == OsConstants.S_IFDIR) {
            throw IllegalStateException("目标路径为目录，无法按文件写入：${file.name}")
        }
        return file to (st.st_mode and 0xFFF)
    }

    /** 原子独占创建本地文件（O_CREAT | O_EXCL），已存在时抛出 FileAlreadyExistsException，绝不静默截断原文件。 */
    fun createFileExclusiveLocal(file: File, initialBytes: ByteArray, octalMode: Int = 0x1A4) {
        file.parentFile?.mkdirs()
        try {
            val fd = Os.open(
                file.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL,
                octalMode,
            )
            try {
                FileOutputStream(fd).use { out ->
                    if (initialBytes.isNotEmpty()) out.write(initialBytes)
                    out.flush()
                    fd.sync()
                }
            } catch (e: Exception) {
                runCatching { file.delete() }
                throw e
            }
            // 显式 chmod 以不受进程 umask 掩码影响（例如新建 .sh 精确保留 0755）
            runCatching { Os.chmod(file.absolutePath, octalMode) }
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EEXIST) {
                throw FileAlreadyExistsException(file, null, "目标文件已存在：${file.name}")
            }
            throw IllegalStateException("创建文件失败：${e.message}", e)
        } catch (e: UnsatisfiedLinkError) {
            // 纯 JVM 单元测试回退路径
            if (!file.createNewFile()) throw FileAlreadyExistsException(file, null, "目标文件已存在：${file.name}")
            if (initialBytes.isNotEmpty()) file.writeBytes(initialBytes)
        }
    }
}
