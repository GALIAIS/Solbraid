package com.galiais.agent.engine.fs

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 外部存储双向互传桥：
 * - 支持一键保存到 `Download/Solbraid`（Android 10+ 走 MediaStore 免存储权限）与系统相册（图片/视频）；
 * - 支持 SAF 单文件另存为、多文件上传与整文件夹树递归上传；
 * - 支持 FileProvider 安全调起系统分享、`ACTION_VIEW`（用其他应用打开）以及 APK 安装器。
 */
object ExternalStorageBridge {
    fun openAllFilesPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null }
            ?: "file"
    }

    fun queryFileSize(context: Context, uri: Uri): Long {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }

    /** 一键将任意本地可读文件保存到手机 `Download/Solbraid/`（Android 10+ 零额外权限）。 */
    fun saveToDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String = TextCodec.guessMimeType(displayName),
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onBytes: (Long) -> Unit = {},
    ): String {
        val safeName = sanitizeFilename(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ext = safeName.substringAfterLast('.', "").lowercase()
            // Android MediaStore 对 text/plain 会强制给 .kt/.py/.sh 等代码文件追加 .txt 后缀；仅对标准后缀传递具体 MIME，其余走 octet-stream 以完整保留原文件名
            val storeMime = if (mimeType == "text/plain" && ext != "txt") "application/octet-stream" else mimeType
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, storeMime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Solbraid")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在手机下载目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> copyStream(input, out, cancelled, onBytes) }
                } ?: error("无法打开下载目录输出流")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Download/Solbraid/$safeName"
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Solbraid").apply { mkdirs() }
            val dst = uniqueFile(dir, safeName)
            sourceFile.inputStream().use { input ->
                dst.outputStream().use { out -> copyStream(input, out, cancelled, onBytes) }
            }
            return dst.absolutePath
        }
    }

    /** 一键保存图片或视频到系统相册（Pictures/Solbraid 或 Movies/Solbraid）。 */
    fun saveToGallery(
        context: Context,
        sourceFile: File,
        displayName: String,
        isVideo: Boolean,
        mimeType: String = TextCodec.guessMimeType(displayName),
    ): String {
        val safeName = sanitizeFilename(displayName)
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relDir = if (isVideo) "${Environment.DIRECTORY_MOVIES}/Solbraid" else "${Environment.DIRECTORY_PICTURES}/Solbraid"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("无法写入系统相册")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            } ?: error("无法打开相册写入流")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return "$relDir/$safeName"
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 将本地文件流式导出到用户通过 SAF `CreateDocument` 选定的任意位置。 */
    fun exportToUri(
        context: Context,
        sourceFile: File,
        destUri: Uri,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onBytes: (Long) -> Unit = {},
    ) {
        context.contentResolver.openOutputStream(destUri, "wt")?.use { out ->
            sourceFile.inputStream().use { input -> copyStream(input, out, cancelled, onBytes) }
        } ?: error("无法写入目标导出文件")
    }

    /** 使用系统分享面板分享文件。 */
    fun shareFile(context: Context, stagedFile: File, displayName: String, mimeType: String = TextCodec.guessMimeType(displayName)) {
        val shareFile = ensureInViewerCache(context, stagedFile, displayName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 $displayName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 调用系统「用其他应用打开」（对 `.apk` 自动调用系统安装器）。 */
    fun openWithExternalApp(context: Context, stagedFile: File, displayName: String, mimeType: String = TextCodec.guessMimeType(displayName)) {
        val viewFile = ensureInViewerCache(context, stagedFile, displayName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", viewFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "打开方式 · $displayName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 递归列出 SAF 选定文件夹树中的所有相对路径与文档 URI，用于整文件夹上传。 */
    fun walkDocumentTree(context: Context, treeUri: Uri): List<Pair<String, Uri>> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<Pair<String, Uri>>()
        fun walk(parentDocId: String, relPrefix: String, depth: Int) {
            if (depth > 48) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val docId = if (idxId >= 0) c.getString(idxId).orEmpty() else continue
                    val rawName = if (idxName >= 0) c.getString(idxName).orEmpty() else continue
                    val safe = sanitizeFilename(rawName)
                    val mime = if (idxMime >= 0) c.getString(idxMime).orEmpty() else ""
                    val rel = if (relPrefix.isEmpty()) safe else "$relPrefix/$safe"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId, rel, depth + 1)
                    } else {
                        results += rel to DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        walk(rootDocId, "", 0)
        return results
    }

    private fun ensureInViewerCache(context: Context, src: File, displayName: String): File {
        val viewerDir = File(context.cacheDir, "file-viewer").apply { mkdirs() }
        if (src.parentFile?.canonicalFile == viewerDir.canonicalFile) return src
        val safeName = sanitizeFilename(displayName)
        val dst = File(viewerDir, safeName)
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        return dst
    }

    fun sanitizeFilename(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').replace("..", "_").replace('\u0000', '_').trim().ifEmpty { "file" }

    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it == name || it.isEmpty()) "" else ".$it" }
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($idx)$ext")
            idx++
        }
        return candidate
    }

    fun copyStream(input: InputStream, output: java.io.OutputStream, cancelled: AtomicBoolean, onBytes: (Long) -> Unit) {
        val buf = ByteArray(131_072)
        var total = 0L
        while (true) {
            if (cancelled.get()) throw InterruptedException("传输已取消")
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        output.flush()
    }
}
