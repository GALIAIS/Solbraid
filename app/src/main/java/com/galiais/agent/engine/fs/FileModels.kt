package com.galiais.agent.engine.fs

import com.galiais.agent.engine.DEFAULT_WORKSPACE
import org.json.JSONObject
import java.util.UUID

/** 统一虚拟文件系统存储域。 */
enum class FileScope(
    val id: String,
    val label: String,
    val shortLabel: String,
    val defaultRoot: String,
    val isRootElevated: Boolean,
) {
    LINUX_WORKSPACE("linux_workspace", "Linux 工作区", "工作区", DEFAULT_WORKSPACE, false),
    LINUX_ROOT("linux_root", "Linux Root", "容器 Root", "/", true),
    HOST_ROOT("host_root", "手机 Root", "手机 Root", "/", true),
    ANDROID_STORAGE("android_storage", "手机存储", "手机存储", "/storage/emulated/0", false),
    ;

    companion object {
        fun of(id: String?): FileScope = entries.firstOrNull { it.id == id } ?: LINUX_WORKSPACE
    }
}

/** 唯一跨存储域文件引用。 */
data class FileRef(
    val scope: FileScope,
    val path: String,
) {
    val normalizedPath: String
        get() = normalizeVfsPath(path)

    val name: String
        get() = normalizedPath.substringAfterLast('/').ifEmpty { "/" }

    val parentPath: String
        get() {
            val norm = normalizedPath
            if (norm == "/") return "/"
            return norm.substringBeforeLast('/').ifEmpty { "/" }
        }
}

/** 规范化 POSIX 虚拟路径（允许 SAF URI 透传）。 */
fun normalizeVfsPath(raw: String): String {
    if (raw.startsWith("saf://")) return raw.trimEnd('/').ifEmpty { raw }
    val clean = raw.replace('\\', '/').trim()
    if (clean.isEmpty() || clean == "/") return "/"
    val parts = ArrayList<String>()
    for (seg in clean.split('/')) {
        if (seg.isEmpty() || seg == ".") continue
        if (seg == "..") {
            if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        } else {
            parts.add(seg)
        }
    }
    return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
}

fun joinVfsPath(dir: String, name: String): String {
    val cleanName = name.trim().trim('/')
    require(cleanName.isNotEmpty() && !cleanName.contains('/') && cleanName != "." && cleanName != "..") {
        "文件名无效：$name"
    }
    val base = normalizeVfsPath(dir)
    return if (base == "/") "/$cleanName" else "$base/$cleanName"
}

/** 校验并拆分相对路径（支持 `a/b/c.kt` 级联创建，严禁路径穿越 `..`、空段与 NUL 字符）。 */
fun splitValidatedRelativePath(rawRelativePath: String): List<String> {
    val normalized = rawRelativePath.replace('\\', '/').trim().trim('/')
    require(normalized.isNotEmpty()) { "路径不能为空" }
    require(!normalized.contains('\u0000')) { "路径包含非法字符" }
    val segments = normalized.split('/')
    require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "路径不能包含空段、. 或 ..：$rawRelativePath"
    }
    return segments
}

/** 新建文件冲突策略。 */
enum class CreateConflictPolicy(val label: String) {
    FAIL_IF_EXISTS("重名时阻止"),
    AUTO_RENAME("自动编号重命名"),
    OVERWRITE_WITH_BACKUP("备份后覆盖"),
}

/** 独占创建遇到重名目标时抛出的结构化异常，便于 UI 弹出三选一决策。 */
class FileAlreadyExistsVfsException(
    val existingRef: FileRef,
    message: String = "目标文件已存在：${existingRef.name}",
) : IllegalStateException(message)

/** 基于修改时间、文件大小与内容 SHA-256 的精确修订锚点（解决同秒等长替换误判）。 */
data class FileRevision(
    val mtimeMs: Long,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun encode(): String = "$mtimeMs:$sizeBytes:$sha256"

    fun contentDiffersFrom(other: FileRevision): Boolean = when {
        sha256.isNotEmpty() && other.sha256.isNotEmpty() -> sha256 != other.sha256
        else -> mtimeMs != other.mtimeMs || sizeBytes != other.sizeBytes
    }

    companion object {
        fun decode(raw: String?): FileRevision? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size < 2) return null
            val mtime = parts[0].toLongOrNull() ?: return null
            val size = parts[1].toLongOrNull() ?: return null
            val sha = parts.getOrNull(2).orEmpty()
            return FileRevision(mtime, size, sha)
        }
    }
}

/** 超大文件流式搜索命中项。 */
data class LargeSearchHit(
    val lineNumber: Int,
    val byteOffset: Long,
    val pageIndex: Int,
    val previewLine: String,
)

/** 文件或目录的完整元数据节点。 */
data class FileNode(
    val scope: FileScope,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    val isBrokenSymlink: Boolean = false,
    val size: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val permissions: String = "",
    val octalMode: String = "",
    val owner: String = "",
    val group: String = "",
    val selinuxContext: String? = null,
    val mimeType: String = "",
    val isSpecialFile: Boolean = false,
    val gitStatus: String? = null,
) {
    val ref: FileRef get() = FileRef(scope, path)
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

/** 挂载点信息。 */
data class MountInfo(
    val mountPoint: String,
    val fsType: String,
    val readOnly: Boolean,
    val device: String = "",
)

/** 目录递归统计结果。 */
data class DirStats(
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
)

/** 文件哈希摘要结果。 */
data class FileHashes(
    val md5: String,
    val sha1: String,
    val sha256: String,
    val sha512: String,
)

/** 浏览器多标签页状态。 */
data class FileTab(
    val id: String = UUID.randomUUID().toString().take(8),
    val scope: FileScope,
    val path: String,
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
) {
    val title: String
        get() = when {
            path == "/" -> scope.shortLabel
            path == DEFAULT_WORKSPACE && scope == FileScope.LINUX_WORKSPACE -> "workspace"
            path == "/storage/emulated/0" -> "内部存储"
            else -> path.substringAfterLast('/').ifBlank { scope.shortLabel }
        }
}

enum class ClipboardMode { COPY, CUT }

data class FileClipboard(
    val mode: ClipboardMode,
    val items: List<FileRef>,
)

enum class ConflictPolicy(val label: String) {
    ASK("逐个询问"),
    OVERWRITE("覆盖已有"),
    SKIP("跳过同名"),
    RENAME("自动重命名"),
}

enum class SortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    MODIFIED("修改时间"),
    EXTENSION("类型"),
}

enum class ViewMode(val label: String) {
    DETAILED("详细列表"),
    COMPACT("紧凑列表"),
    GRID("缩略图网格"),
}

enum class MediaFilter(val label: String) {
    ALL("全部"),
    CODE("代码"),
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    DOC("文档"),
    ARCHIVE("压缩包"),
}

enum class TransferPhase { RUNNING, COMPLETED, FAILED, CANCELLED }

/** 后台流式传输任务状态。 */
data class TransferJob(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val phase: TransferPhase = TransferPhase.RUNNING,
    val currentItem: String = "",
    val doneItems: Int = 0,
    val totalItems: Int = 1,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null,
) {
    val progress: Float?
        get() = when {
            totalBytes > 0L -> (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalItems > 0 -> (doneItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
            else -> null
        }
    val active: Boolean get() = phase == TransferPhase.RUNNING
}

/** 书签条目。 */
data class FileBookmark(
    val id: String,
    val title: String,
    val scope: FileScope,
    val path: String,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("scope", scope.id)
        .put("path", path)

    companion object {
        fun fromJson(obj: JSONObject): FileBookmark? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val path = obj.optString("path").takeIf { it.isNotBlank() } ?: return null
            return FileBookmark(
                id = id,
                title = obj.optString("title").ifBlank { path.substringAfterLast('/').ifEmpty { "/" } },
                scope = FileScope.of(obj.optString("scope")),
                path = path,
                builtIn = false,
            )
        }
    }
}

/** SAF 持久化挂载的外部目录。 */
data class MountedSafTree(
    val id: String,
    val uriString: String,
    val displayName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", uriString)
        .put("name", displayName)

    companion object {
        fun fromJson(obj: JSONObject): MountedSafTree? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val uri = obj.optString("uri").takeIf { it.isNotBlank() } ?: return null
            return MountedSafTree(id, uri, obj.optString("name", "外部目录"))
        }
    }
}

/** 搜索结果（兼容文件名搜索与全文内容 Grep 搜索）。 */
data class SearchHit(
    val scope: FileScope,
    val path: String,
    val name: String,
    val isDirectory: Boolean = false,
    val lineNumber: Int? = null,
    val previewLine: String? = null,
    val size: Long = 0L,
)

/** 磁盘占用分析行。 */
data class DiskUsageEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val fileCount: Int = 0,
)

/** 回收站记录。 */
data class TrashEntry(
    val id: String,
    val originalScope: FileScope,
    val originalPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAtMs: Long,
    val trashFilePath: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("scope", originalScope.id)
        .put("originalPath", originalPath)
        .put("name", name)
        .put("isDirectory", isDirectory)
        .put("size", size)
        .put("deletedAtMs", deletedAtMs)
        .put("trashFilePath", trashFilePath)

    companion object {
        fun fromJson(obj: JSONObject): TrashEntry? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val trashPath = obj.optString("trashFilePath").takeIf { it.isNotBlank() } ?: return null
            return TrashEntry(
                id = id,
                originalScope = FileScope.of(obj.optString("scope")),
                originalPath = obj.optString("originalPath"),
                name = obj.optString("name"),
                isDirectory = obj.optBoolean("isDirectory"),
                size = obj.optLong("size"),
                deletedAtMs = obj.optLong("deletedAtMs"),
                trashFilePath = trashPath,
            )
        }
    }
}

/** 压缩包内部条目。 */
data class ArchiveItem(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long = -1L,
    val modifiedMs: Long = 0L,
)

/** APK 静态信息摘要。 */
data class ApkInspectInfo(
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val permissions: List<String>,
    val signingSha256: String?,
)

/** 文件大小人类可读格式。 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
