package com.galiais.agent.engine.fs

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 编辑器防崩溃/防杀后台未保存草稿条目。 */
data class EditorDraft(
    val scopeId: String,
    val path: String,
    val baseRevision: String,
    val draftText: String,
    val encodingId: String,
    val lineEndingName: String,
    val updatedAtMs: Long,
)

/**
 * 编辑器本地草稿持久库（`files/editor-drafts/<sha1>.json`）：
 * - 单文件草稿上限 2 MB，总目录配额 20 MB LRU 自动清理；
 * - 正常保存或主动放弃修改时立即清除，仅在意外退出/杀后台后提供一键恢复。
 */
object EditorDraftStore {
    private const val DIR_NAME = "editor-drafts"
    private const val MAX_SINGLE_DRAFT_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_DRAFT_BYTES = 20L * 1024 * 1024

    fun draftDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun draftKey(ref: FileRef): String {
        val raw = "${ref.scope.id}|${ref.normalizedPath}"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun draftFile(dir: File, ref: FileRef): File = File(dir, "${draftKey(ref)}.json")

    fun saveDraft(
        context: Context,
        ref: FileRef,
        baseRevision: String,
        draftText: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
    ): Boolean = saveDraftToDir(draftDir(context), ref, baseRevision, draftText, encoding, lineEnding)

    internal fun saveDraftToDir(
        dir: File,
        ref: FileRef,
        baseRevision: String,
        draftText: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
    ): Boolean {
        if (draftText.toByteArray(Charsets.UTF_8).size > MAX_SINGLE_DRAFT_BYTES) return false
        dir.mkdirs()
        val obj = JSONObject()
            .put("scopeId", ref.scope.id)
            .put("path", ref.normalizedPath)
            .put("baseRevision", baseRevision)
            .put("draftText", draftText)
            .put("encodingId", encoding.id)
            .put("lineEndingName", lineEnding.name)
            .put("updatedAtMs", System.currentTimeMillis())
        val target = draftFile(dir, ref)
        val tmp = File(dir, ".${target.name}.tmp")
        return runCatching {
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(obj.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            pruneDir(dir, keepFile = target)
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    fun loadDraft(context: Context, ref: FileRef): EditorDraft? = loadDraftFromDir(draftDir(context), ref)

    internal fun loadDraftFromDir(dir: File, ref: FileRef): EditorDraft? {
        val f = draftFile(dir, ref)
        if (!f.isFile) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val path = obj.optString("path")
            if (path.isBlank()) return@runCatching null
            EditorDraft(
                scopeId = obj.optString("scopeId", ref.scope.id),
                path = path,
                baseRevision = obj.optString("baseRevision", ""),
                draftText = obj.optString("draftText", ""),
                encodingId = obj.optString("encodingId", TextEncoding.UTF_8.id),
                lineEndingName = obj.optString("lineEndingName", LineEnding.LF.name),
                updatedAtMs = obj.optLong("updatedAtMs", f.lastModified()),
            )
        }.getOrNull()
    }

    fun clearDraft(context: Context, ref: FileRef) = clearDraftFromDir(draftDir(context), ref)

    internal fun clearDraftFromDir(dir: File, ref: FileRef) {
        runCatching { draftFile(dir, ref).delete() }
    }

    internal fun pruneDir(dir: File, keepFile: File? = null, maxTotalBytes: Long = MAX_TOTAL_DRAFT_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            if (keepFile != null && f.absolutePath == keepFile.absolutePath) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
