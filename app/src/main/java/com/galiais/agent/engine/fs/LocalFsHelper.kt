package com.galiais.agent.engine.fs

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.galiais.agent.LinuxRuntime
import java.io.File
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
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
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
}
