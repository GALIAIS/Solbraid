package com.galiais.agent.engine.fs

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 流式压缩/解压/包内透视与 APK 静态分析引擎（无 4MB/64MB 人工上限，严格防 Zip-Slip）。 */
object ArchiveEngine {
    private val DEFAULT_SKIP_DIRS = setOf(".git", "node_modules", ".gradle", "build", "target", "__pycache__", ".venv")

    fun normalizeEntryPath(raw: String): String? {
        val unified = raw.replace('\\', '/')
        if (unified.isBlank() || unified.length > 4096 || unified.startsWith('/') ||
            unified.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(unified)
        ) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.size > 96 || segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** 免解压列出 ZIP / APK / JAR 内部目录与文件条目。 */
    fun listZipEntries(file: File): List<ArchiveItem> {
        val items = mutableListOf<ArchiveItem>()
        ZipFile(file).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val rel = normalizeEntryPath(e.name) ?: continue
                if (rel.startsWith("__MACOSX/") || rel.substringAfterLast('/') == ".DS_Store") continue
                items += ArchiveItem(
                    path = rel,
                    isDirectory = e.isDirectory,
                    size = e.size.coerceAtLeast(0L),
                    compressedSize = e.compressedSize,
                    modifiedMs = e.time.coerceAtLeast(0L),
                )
            }
        }
        return items.sortedWith(compareBy<ArchiveItem> { !it.isDirectory }.thenBy { it.path.lowercase() })
    }

    /** 免解压读取 ZIP 内单个文件内容用于预览（默认上限 4 MB）。 */
    fun readZipEntryBytes(file: File, entryPath: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        val target = normalizeEntryPath(entryPath) ?: error("压缩包条目路径无效")
        ZipFile(file).use { zf ->
            val entry = zf.getEntry(target) ?: zf.entries().asSequence().firstOrNull { normalizeEntryPath(it.name) == target }
                ?: error("压缩包内未找到：$target")
            require(!entry.isDirectory) { "不能以文件形式预览目录条目" }
            zf.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32_768)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    require(out.size() + n <= maxBytes) { "包内文件超过预览上限（${maxBytes / (1024 * 1024)} MB），请先解压后查看" }
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }
    }

    /** 流式安全解压 ZIP 到目标目录（可选只解压选中条目，支持进度回调与取消）。 */
    fun extractZipStream(
        input: InputStream,
        destDir: File,
        selectedPrefixes: Set<String>? = null,
        overwrite: Boolean = true,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (itemName: String, doneFiles: Int, bytesCopied: Long) -> Unit = { _, _, _ -> },
    ): Int {
        if (!destDir.exists()) check(destDir.mkdirs()) { "无法创建解压目标目录：${destDir.absolutePath}" }
        val canonicalRoot = destDir.canonicalFile
        var doneFiles = 0
        var totalBytes = 0L
        val buf = ByteArray(65_536)
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            while (true) {
                if (cancelled.get()) throw InterruptedException("已取消解压")
                val entry = zis.nextEntry ?: break
                val rel = normalizeEntryPath(entry.name) ?: error("压缩包包含非法路径：${entry.name.take(120)}")
                if (rel.startsWith("__MACOSX/") || rel.substringAfterLast('/') == ".DS_Store") continue
                if (selectedPrefixes != null && selectedPrefixes.none { rel == it || rel.startsWith("$it/") }) continue
                val outFile = File(canonicalRoot, rel)
                val outCanonical = outFile.canonicalFile
                check(outCanonical.path == canonicalRoot.path || outCanonical.path.startsWith(canonicalRoot.path + File.separator)) {
                    "检测到路径穿越攻击，已拦截：$rel"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    if (outFile.exists() && !overwrite) continue
                    BufferedOutputStream(outFile.outputStream()).use { out ->
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("已取消解压")
                            val n = zis.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            totalBytes += n
                        }
                    }
                    doneFiles++
                    onProgress(rel, doneFiles, totalBytes)
                }
            }
        }
        return doneFiles
    }

    /** 流式打包任意文件/目录集合为 ZIP（支持大文件、进度回调与取消）。 */
    fun createZipStream(
        sources: List<Pair<String, File>>,
        output: OutputStream,
        excludeBuildDirs: Boolean = false,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (itemName: String, doneFiles: Int, bytesCopied: Long) -> Unit = { _, _, _ -> },
    ): Int {
        var doneFiles = 0
        var totalBytes = 0L
        val buf = ByteArray(65_536)
        ZipOutputStream(BufferedOutputStream(output)).use { zos ->
            fun addRec(baseName: String, file: File, depth: Int) {
                if (cancelled.get()) throw InterruptedException("已取消打包")
                if (depth > 96) return
                if (file.isDirectory) {
                    val children = file.listFiles().orEmpty().sortedBy { it.name.lowercase() }
                    if (children.isEmpty()) {
                        zos.putNextEntry(ZipEntry(baseName.trimEnd('/') + "/"))
                        zos.closeEntry()
                        return
                    }
                    for (child in children) {
                        if (excludeBuildDirs && child.isDirectory && child.name in DEFAULT_SKIP_DIRS) continue
                        val childRel = if (baseName.isBlank()) child.name else "$baseName/${child.name}"
                        addRec(childRel, child, depth + 1)
                    }
                } else if (file.isFile) {
                    zos.putNextEntry(ZipEntry(baseName))
                    file.inputStream().use { input ->
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("已取消打包")
                            val n = input.read(buf)
                            if (n <= 0) break
                            zos.write(buf, 0, n)
                            totalBytes += n
                        }
                    }
                    zos.closeEntry()
                    doneFiles++
                    onProgress(baseName, doneFiles, totalBytes)
                }
            }
            for ((rel, file) in sources) {
                addRec(rel, file, 0)
            }
        }
        return doneFiles
    }

    /** 解析 APK 包名、版本、ABI、权限与签名 SHA-256。 */
    @Suppress("DEPRECATION")
    fun inspectApk(context: Context, apkFile: File): ApkInspectInfo {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("无法解析 APK 清单信息，文件可能损坏")
        info.applicationInfo?.let {
            it.sourceDir = apkFile.absolutePath
            it.publicSourceDir = apkFile.absolutePath
        }
        val label = info.applicationInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val minSdk = info.applicationInfo?.minSdkVersion ?: 0
        val targetSdk = info.applicationInfo?.targetSdkVersion ?: 0
        val perms = info.requestedPermissions?.toList().orEmpty()
        val abis = runCatching {
            ZipFile(apkFile).use { zf ->
                zf.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.removePrefix("lib/").substringBefore('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        }
        val sigSha256 = sigBytes?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
        }
        return ApkInspectInfo(
            packageName = info.packageName.orEmpty(),
            appLabel = label.ifBlank { info.packageName.orEmpty() },
            versionName = info.versionName.orEmpty(),
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            abis = abis,
            permissions = perms,
            signingSha256 = sigSha256,
        )
    }
}
