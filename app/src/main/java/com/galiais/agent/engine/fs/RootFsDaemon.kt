package com.galiais.agent.engine.fs

import android.content.Context
import android.os.Process
import com.galiais.agent.RootShell
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * 常驻 Root 文件引擎守护进程（基于 `su --mount-master -s /system/bin/sh` 单长连接）：
 * - 避免每次 ls/stat/chmod 重复 fork `su` 的 200ms 延迟与 Root 日志刷屏；
 * - 支持毫秒级批量目录读取（含属主、八进制权限、SELinux 上下文与软链解析）；
 * - 支持大文件零 Base64 膨胀的分片/暂存流式读写，写回自动保留原 mode / uid:gid / SELinux 上下文并原子替换；
 * - 即使 Linux 运行环境未启动，也能独立访问手机宿主 `/`、`/data`、`/data/adb`、`/system`、`/sdcard` 及容器内 root 目录。
 */
internal class RootFsDaemon(private val context: Context) {
    private val lock = Any()
    private var process: java.lang.Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private val stageRoot: File get() = File(context.cacheDir, "root-fs-stage").apply { mkdirs() }
    private val appUid: Int get() = Process.myUid()

    data class ExecOutput(val exitCode: Int, val stdout: String)

    fun isProtectedSystemPath(path: String): Boolean {
        val p = normalizeVfsPath(path)
        return PROTECTED_PREFIXES.any { p == it || p.startsWith("$it/") }
    }

    fun isUnremovablePath(scope: FileScope, path: String): Boolean {
        val p = normalizeVfsPath(path)
        if (p == "/") return true
        return when (scope) {
            FileScope.HOST_ROOT -> p in setOf(
                "/data", "/data/data", "/data/user", "/data/user/0", "/data/system",
                "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev",
                "/storage", "/storage/emulated", "/storage/emulated/0",
            )
            FileScope.LINUX_WORKSPACE, FileScope.LINUX_ROOT -> p in setOf(
                "/home", "/home/agent", "/home/agent/workspace", "/etc", "/usr", "/bin", "/lib", "/opt", "/proc", "/dev",
            )
            FileScope.ANDROID_STORAGE -> p in setOf("/storage", "/storage/emulated", "/storage/emulated/0")
        }
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun ensureConnectedLocked() {
        val cur = process
        if (cur != null && cur.isAlive && writer != null && reader != null) return
        closeLocked()
        val key = UUID.randomUUID().toString()
        val proc = RootShell.process("exec /system/bin/env -i PATH=/system/bin:/system/xbin /system/bin/sh")
            .redirectErrorStream(true)
            .start()
        val w = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        val r = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        // 使用 RootShell 同款能力与挂载命名空间校验握手，确保确实拿到 uid=0
        w.write(RootShell.metadataCommand(key))
        w.write("\n")
        w.flush()
        val packet = try {
            RootShell.readHandshake(r, key)
        } catch (e: Exception) {
            runCatching { proc.destroyForcibly() }
            throw e
        }
        try {
            RootShell.validate(packet)
        } catch (e: Exception) {
            runCatching { proc.destroyForcibly() }
            throw e
        }
        process = proc
        writer = w
        reader = r
    }

    private fun closeLocked() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroyForcibly() }
        writer = null
        reader = null
        process = null
    }

    fun close() = synchronized(lock) { closeLocked() }

    /** 在常驻 su 会话中同步执行一条命令并返回完整 stdout 与退出码；断连自动重连一次。 */
    fun exec(script: String, retryOnDisconnect: Boolean = true, beforeExecute: () -> Unit = {}): ExecOutput = synchronized(lock) {
        val lastAttempt = if (retryOnDisconnect) 1 else 0
        for (attempt in 0..lastAttempt) {
            try {
                beforeExecute()
                ensureConnectedLocked()
                val w = writer!!
                val r = reader!!
                val sentinel = "__GALIAIS_RFS_${UUID.randomUUID().toString().replace("-", "")}__"
                beforeExecute()
                w.write("{\n")
                w.write(script)
                w.write("\n} 2>&1\n")
                w.write("__rfs_rc=\$?\nprintf '\\n%s:%s\\n' '$sentinel' \"\$__rfs_rc\"\n")
                w.flush()
                val sb = StringBuilder()
                var rc = -1
                while (true) {
                    val line = r.readLine() ?: throw IllegalStateException("Root 文件服务连接意外断开")
                    if (line.startsWith("$sentinel:")) {
                        rc = line.substringAfter(':').trim().toIntOrNull() ?: 1
                        break
                    }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
                return ExecOutput(rc, sb.toString())
            } catch (e: Exception) {
                closeLocked()
                if (attempt == lastAttempt) throw e
            }
        }
        error("Root 命令执行失败")
    }

    /** 毫秒级列出任意 Root 目录（使用 find -exec stat {} + 批量系统调用，百项目录 ~20ms，支持空格/中文/软链断链与 SELinux）。 */
    fun listDirectory(physicalDir: String, scope: FileScope, virtualDir: String): List<FileNode> {
        val qDir = shQuote(physicalDir)
        val sep = "\u001F"
        val script = """
            if [ ! -d $qDir ]; then echo "NOT_A_DIRECTORY"; exit 2; fi
            cd -- $qDir || exit 1
            find . -maxdepth 1 -mindepth 1 -exec stat -c '%A$sep%a$sep%U$sep%G$sep%s$sep%Y$sep%C$sep%n' {} + 2>/dev/null
            printf '__RFS_LINKS__\n'
            for __f in .* *; do
              case "${'$'}__f" in .|..) continue ;; esac
              [ -L "${'$'}__f" ] || continue
              __tgt=${'$'}(readlink -- "${'$'}__f" 2>/dev/null || true)
              __dir=0; __broken=0
              if [ -d "${'$'}__f" ]; then __dir=1; elif [ ! -e "${'$'}__f" ]; then __broken=1; fi
              printf '%s$sep%s$sep%s$sep%s\n' "${'$'}__dir" "${'$'}__broken" "${'$'}__tgt" "${'$'}__f"
            done
        """.trimIndent()
        val out = exec(script)
        if (out.exitCode != 0) {
            throw IllegalStateException(out.stdout.ifBlank { "无法读取目录：$virtualDir" })
        }
        val marker = "__RFS_LINKS__"
        val markerIdx = out.stdout.indexOf(marker)
        val statSection = if (markerIdx >= 0) out.stdout.substring(0, markerIdx) else out.stdout
        val linkSection = if (markerIdx >= 0) out.stdout.substring(markerIdx + marker.length) else ""

        // 解析软链元数据表：name -> Triple(isDir, isBroken, target)
        val linkMeta = HashMap<String, Triple<Boolean, Boolean, String?>>()
        for (line in linkSection.lineSequence()) {
            if (line.isBlank()) continue
            val p = line.split(sep)
            if (p.size < 4) continue
            val name = p.drop(3).joinToString(sep)
            if (name.isNotEmpty()) {
                linkMeta[name] = Triple(p[0] == "1", p[1] == "1", p[2].takeIf { it.isNotEmpty() })
            }
        }

        val base = normalizeVfsPath(virtualDir)
        val nodes = ArrayList<FileNode>()
        for (line in statSection.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split(sep)
            if (parts.size < 8) continue
            val perm = parts[0]
            val octal = parts[1]
            val owner = parts[2]
            val group = parts[3]
            val size = parts[4].toLongOrNull() ?: 0L
            val mtimeSec = parts[5].toLongOrNull() ?: 0L
            val selinux = parts[6].takeIf { it.isNotBlank() && it != "?" }
            val name = parts.drop(7).joinToString(sep).removePrefix("./")
            if (name.isEmpty() || name == "." || name == "..") continue
            val firstChar = perm.firstOrNull() ?: '-'
            val isLink = firstChar == 'l'
            val lm = if (isLink) linkMeta[name] else null
            val isDir = if (isLink) (lm?.first == true) else (firstChar == 'd')
            val broken = lm?.second == true
            val tgt = lm?.third
            val vPath = if (base == "/") "/$name" else "$base/$name"
            nodes += FileNode(
                scope = scope,
                path = vPath,
                name = name,
                isDirectory = isDir,
                isSymlink = isLink,
                symlinkTarget = tgt,
                isBrokenSymlink = broken,
                size = size,
                lastModifiedMs = mtimeSec * 1000L,
                permissions = perm,
                octalMode = octal,
                owner = owner,
                group = group,
                selinuxContext = selinux,
                mimeType = if (isDir) "inode/directory" else TextCodec.guessMimeType(name),
                isSpecialFile = firstChar in setOf('c', 'b', 'p', 's'),
            )
        }
        return nodes
    }

    fun statNode(physicalPath: String, scope: FileScope, virtualPath: String): FileNode {
        val qPath = shQuote(physicalPath)
        val sep = "\u001F"
        val script = """
            [ -e $qPath ] || [ -L $qPath ] || { echo "ENOENT"; exit 2; }
            __st=${'$'}(stat -c '%A$sep%a$sep%U$sep%G$sep%s$sep%Y$sep%C' -- $qPath 2>/dev/null) || exit 1
            __dir=0; __broken=0; __tgt=""
            if [ -L $qPath ]; then
              __tgt=${'$'}(readlink -- $qPath 2>/dev/null || true)
              if [ -d $qPath ]; then __dir=1; elif [ ! -e $qPath ]; then __broken=1; fi
            elif [ -d $qPath ]; then
              __dir=1
            fi
            printf '%s$sep%s$sep%s$sep%s\n' "${'$'}__st" "${'$'}__dir" "${'$'}__broken" "${'$'}__tgt"
        """.trimIndent()
        val out = exec(script)
        if (out.exitCode != 0) throw IllegalStateException(out.stdout.ifBlank { "文件不存在：$virtualPath" })
        val line = out.stdout.lineSequence().firstOrNull { it.contains(sep) }
            ?: throw IllegalStateException("读取文件属性失败：$virtualPath")
        val parts = line.split(sep)
        val perm = parts.getOrElse(0) { "-" }
        val firstChar = perm.firstOrNull() ?: '-'
        val norm = normalizeVfsPath(virtualPath)
        val name = norm.substringAfterLast('/').ifEmpty { "/" }
        return FileNode(
            scope = scope,
            path = norm,
            name = name,
            isDirectory = parts.getOrNull(7) == "1",
            isSymlink = firstChar == 'l',
            symlinkTarget = parts.getOrNull(9)?.takeIf { it.isNotEmpty() },
            isBrokenSymlink = parts.getOrNull(8) == "1",
            size = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
            lastModifiedMs = (parts.getOrNull(5)?.toLongOrNull() ?: 0L) * 1000L,
            permissions = perm,
            octalMode = parts.getOrElse(1) { "" },
            owner = parts.getOrElse(2) { "" },
            group = parts.getOrElse(3) { "" },
            selinuxContext = parts.getOrNull(6)?.takeIf { it.isNotBlank() && it != "?" },
            mimeType = if (parts.getOrNull(7) == "1") "inode/directory" else TextCodec.guessMimeType(name),
            isSpecialFile = firstChar in setOf('c', 'b', 'p', 's'),
        )
    }

    /** 将任意 Root 权限文件高速拷贝到应用私有缓存文件（自动赋权为应用可读，兼容 /proc /sys 伪文件并拦截特殊设备）。 */
    fun stageFileToReadable(physicalSrc: String, destFile: File, maxBytes: Long = -1L) {
        destFile.parentFile?.mkdirs()
        val qSrc = shQuote(physicalSrc)
        val qDst = shQuote(destFile.absolutePath)
        val isPseudoFs = physicalSrc.startsWith("/proc/") || physicalSrc.startsWith("/sys/")
        val effectiveLimit = when {
            maxBytes > 0L -> maxBytes
            isPseudoFs -> 1_048_576L // /proc 与 /sys 伪文件默认封顶 1 MB，避免无限流或 cp 读空
            else -> -1L
        }
        val specialGuard = if (isPseudoFs) "" else {
            "if [ -b $qSrc ] || [ -c $qSrc ] || [ -p $qSrc ] || [ -S $qSrc ]; then echo \"不支持直接读取特殊设备或管道文件\"; exit 21; fi"
        }
        val copyCmd = if (effectiveLimit > 0L) {
            "head -c $effectiveLimit -- $qSrc > $qDst 2>/dev/null || dd if=$qSrc of=$qDst bs=65536 count=${(effectiveLimit + 65_535L) / 65_536L} 2>/dev/null"
        } else {
            "cp -fL -- $qSrc $qDst || cat -- $qSrc > $qDst"
        }
        val script = """
            [ -e $qSrc ] || { echo "源文件不存在"; exit 2; }
            $specialGuard
            $copyCmd || exit 1
            chown $appUid:$appUid $qDst 2>/dev/null || true
            chmod 600 $qDst 2>/dev/null || true
        """.trimIndent()
        val out = exec(script)
        if (out.exitCode != 0) throw IllegalStateException(out.stdout.ifBlank { "Root 读取文件失败：$physicalSrc" })
    }

    /** 将任意 Root 权限目录递归暂存到应用可读缓存目录（用于打包导出 Root 目录）。 */
    fun stageDirectoryToReadable(physicalSrc: String, destDir: File) {
        destDir.mkdirs()
        val qSrc = shQuote(physicalSrc)
        val qDst = shQuote(destDir.absolutePath)
        val script = """
            [ -d $qSrc ] || { echo "源目录不存在"; exit 2; }
            cp -a -- $qSrc/. $qDst/ || exit 1
            chown -R $appUid:$appUid $qDst 2>/dev/null || true
            chmod -R u+rX $qDst 2>/dev/null || true
        """.trimIndent()
        val out = exec(script)
        if (out.exitCode != 0) throw IllegalStateException(out.stdout.ifBlank { "Root 读取目录失败：$physicalSrc" })
    }

    /**
     * 将本地暂存文件原子写入 Root 目标路径：
     * - 若目标是符号链接：先 `readlink -f` 解析真实目标（拒绝悬空软链），在真实目标目录执行原子替换，绝不摧毁原软链；
     * - 完整保留原文件的 mode / uid:gid / SELinux 上下文（新建时按 `createModeOctal` 赋权）；
     * - 当 `failIfExists = true` 时，目标已存在直接返回 EEXIST(17)，防止静默覆盖；
     * - 可选生成 `.bak` 自动备份。
     */
    fun commitStagedFileAtomic(
        stagedSrc: File,
        physicalDst: String,
        createBackup: Boolean = false,
        createModeOctal: String = "644",
        failIfExists: Boolean = false,
    ) {
        val safeMode = createModeOctal.trim().takeIf { Regex("^[0-7]{3,4}$").matches(it) } ?: "644"
        val qSrc = shQuote(stagedSrc.absolutePath)
        val qDst = shQuote(physicalDst)
        val suffix = UUID.randomUUID().toString().take(8)
        val failGuard = if (failIfExists) {
            "if [ -e $qDst ] || [ -L $qDst ]; then echo \"EEXIST\"; exit 17; fi"
        } else ""
        val backupPart = if (createBackup) {
            "if [ -f \"\$__target\" ]; then cp -p -- \"\$__target\" \"\$__target.bak\" 2>/dev/null || true; fi"
        } else ""
        val script = """
            $failGuard
            __target=$qDst
            if [ -L $qDst ]; then
              [ -e $qDst ] || { echo "DANGLING_SYMLINK"; exit 22; }
              __target=${'$'}(readlink -f -- $qDst 2>/dev/null) || { echo "无法解析符号链接目标"; exit 22; }
            fi
            if [ -d "${'$'}__target" ]; then echo "EISDIR"; exit 21; fi
            __parent=${'$'}(dirname -- "${'$'}__target")
            mkdir -p -- "${'$'}__parent" || exit 1
            __tmp="${'$'}__target.galiais.tmp.$suffix"
            __has=0; __mode=""; __ug=""; __ctx=""
            if [ -e "${'$'}__target" ]; then
              __has=1
              __mode=${'$'}(stat -c '%a' -- "${'$'}__target" 2>/dev/null || true)
              __ug=${'$'}(stat -c '%u:%g' -- "${'$'}__target" 2>/dev/null || true)
              __ctx=${'$'}(stat -c '%C' -- "${'$'}__target" 2>/dev/null || true)
            fi
            $backupPart
            (dd if=$qSrc of="${'$'}__tmp" bs=65536 conv=fsync 2>/dev/null || cp -f -- $qSrc "${'$'}__tmp") || { rm -f -- "${'$'}__tmp"; exit 1; }
            if [ "${'$'}__has" = 1 ]; then
              [ -n "${'$'}__mode" ] && chmod "${'$'}__mode" "${'$'}__tmp" 2>/dev/null || true
              [ -n "${'$'}__ug" ] && chown "${'$'}__ug" "${'$'}__tmp" 2>/dev/null || true
              if [ -n "${'$'}__ctx" ] && [ "${'$'}__ctx" != "?" ]; then chcon "${'$'}__ctx" "${'$'}__tmp" 2>/dev/null || true; fi
            else
              chmod $safeMode "${'$'}__tmp" 2>/dev/null || true
              __pug=${'$'}(stat -c '%u:%g' -- "${'$'}__parent" 2>/dev/null || true)
              [ -n "${'$'}__pug" ] && chown "${'$'}__pug" "${'$'}__tmp" 2>/dev/null || true
            fi
            mv -f -- "${'$'}__tmp" "${'$'}__target" || { rm -f -- "${'$'}__tmp"; exit 1; }
        """.trimIndent()
        val out = exec(script)
        if (out.exitCode != 0) {
            val msg = out.stdout.trim()
            if (out.exitCode == 17 || msg == "EEXIST") throw IllegalStateException("EEXIST")
            if (out.exitCode == 22 || msg == "DANGLING_SYMLINK") throw IllegalStateException("目标为悬空符号链接，已拒绝覆盖：$physicalDst")
            if (msg.contains("Read-only file system", ignoreCase = true)) {
                throw IllegalStateException("目标分区处于只读挂载状态（Read-only file system），无法写入：$physicalDst")
            }
            throw IllegalStateException(msg.ifBlank { "Root 原子写入失败：$physicalDst" })
        }
    }

    fun mkdir(physicalPath: String) = requireOk("mkdir -p -- ${shQuote(physicalPath)}", "创建目录失败")
    fun rename(oldPhysical: String, newPhysical: String) =
        requireOk("mv -- ${shQuote(oldPhysical)} ${shQuote(newPhysical)}", "重命名/移动失败")
    fun copyRecursive(srcPhysical: String, dstPhysical: String) =
        requireOk("cp -a -- ${shQuote(srcPhysical)} ${shQuote(dstPhysical)}", "复制失败")
    fun copyDirectoryContents(srcPhysical: String, dstPhysical: String) =
        requireOk("cp -a -- ${shQuote(srcPhysical.trimEnd('/') + "/.")} ${shQuote(dstPhysical.trimEnd('/') + "/")}", "复制目录失败")
    fun remove(physicalPath: String) = requireOk("rm -rf -- ${shQuote(physicalPath)}", "删除失败")
    fun chmod(physicalPath: String, octal: String, recursive: Boolean) {
        require(Regex("^[0-7]{3,4}$").matches(octal.trim())) { "权限格式无效（需 3-4 位八进制，例如 755 或 0644）" }
        val flag = if (recursive) "-R " else ""
        requireOk("chmod $flag${octal.trim()} -- ${shQuote(physicalPath)}", "修改权限失败")
    }
    fun chown(physicalPath: String, ownerSpec: String, recursive: Boolean) {
        require(Regex("^[a-zA-Z0-9_.-]+(:[a-zA-Z0-9_.-]+)?$").matches(ownerSpec.trim())) { "属主格式无效（例如 root:root 或 1000:1000）" }
        val flag = if (recursive) "-R " else ""
        requireOk("chown $flag${shQuote(ownerSpec.trim())} -- ${shQuote(physicalPath)}", "修改属主失败")
    }
    fun chcon(physicalPath: String, contextSpec: String, recursive: Boolean) {
        require(Regex("^[a-zA-Z0-9_.:-]+$").matches(contextSpec.trim())) { "SELinux 上下文格式无效" }
        val flag = if (recursive) "-R " else ""
        requireOk("chcon $flag${shQuote(contextSpec.trim())} -- ${shQuote(physicalPath)}", "修改 SELinux 上下文失败")
    }
    fun createSymlink(target: String, linkPhysical: String) =
        requireOk("ln -s -- ${shQuote(target)} ${shQuote(linkPhysical)}", "创建符号链接失败")

    fun queryMountInfo(physicalPath: String): MountInfo? {
        val q = shQuote(physicalPath)
        val out = exec("""
            __mp=${'$'}(stat -c '%m' -- $q 2>/dev/null || true)
            __fs=${'$'}(stat -f -c '%T' -- $q 2>/dev/null || true)
            __line=${'$'}(awk -v mp="${'$'}__mp" '${'$'}2 == mp { print ${'$'}1 "|" ${'$'}3 "|" ${'$'}4 }' /proc/mounts 2>/dev/null | tail -n 1)
            printf '%s|%s|%s\n' "${'$'}__mp" "${'$'}__fs" "${'$'}__line"
        """.trimIndent())
        if (out.exitCode != 0) return null
        val parts = out.stdout.trim().split('|')
        val mp = parts.getOrNull(0).orEmpty().ifBlank { "/" }
        val fs = parts.getOrNull(3)?.ifBlank { parts.getOrNull(1).orEmpty() } ?: parts.getOrNull(1).orEmpty()
        val opts = parts.getOrNull(4).orEmpty().split(',')
        return MountInfo(mountPoint = mp, fsType = fs.ifBlank { "unknown" }, readOnly = "ro" in opts, device = parts.getOrNull(2).orEmpty())
    }

    fun computeDirStats(physicalPath: String): DirStats {
        val q = shQuote(physicalPath)
        val out = exec("""
            __kb=${'$'}(du -sk -- $q 2>/dev/null | awk '{print ${'$'}1}')
            __fc=${'$'}(find $q -type f 2>/dev/null | wc -l)
            __dc=${'$'}(find $q -type d 2>/dev/null | wc -l)
            printf '%s|%s|%s\n' "${'$'}__kb" "${'$'}__fc" "${'$'}__dc"
        """.trimIndent())
        val parts = out.stdout.trim().split('|')
        return DirStats(
            totalBytes = (parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L) * 1024L,
            fileCount = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
            dirCount = ((parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0),
        )
    }

    fun analyzeDiskUsage(physicalDir: String, virtualDir: String): List<DiskUsageEntry> {
        val q = shQuote(physicalDir)
        val out = exec("""
            cd -- $q || exit 1
            for __f in .* *; do
              case "${'$'}__f" in .|..) continue ;; esac
              [ -e "${'$'}__f" ] || continue
              __kb=${'$'}(du -sk -- "${'$'}__f" 2>/dev/null | awk '{print ${'$'}1}')
              __d=0; [ -d "${'$'}__f" ] && __d=1
              printf '%s\t%s\t%s\n' "${'$'}__kb" "${'$'}__d" "${'$'}__f"
            done | sort -rn | head -n 60
        """.trimIndent())
        if (out.exitCode != 0) return emptyList()
        val base = normalizeVfsPath(virtualDir)
        return out.stdout.lineSequence().mapNotNull { line ->
            val tab1 = line.indexOf('\t')
            if (tab1 < 0) return@mapNotNull null
            val tab2 = line.indexOf('\t', tab1 + 1)
            if (tab2 < 0) return@mapNotNull null
            val kb = line.substring(0, tab1).trim().toLongOrNull() ?: 0L
            val isDir = line.substring(tab1 + 1, tab2).trim() == "1"
            val name = line.substring(tab2 + 1)
            if (name.isBlank()) return@mapNotNull null
            DiskUsageEntry(name = name, path = if (base == "/") "/$name" else "$base/$name", isDirectory = isDir, bytes = kb * 1024L)
        }.toList()
    }

    fun searchByName(physicalRoot: String, virtualRoot: String, scope: FileScope, query: String, maxResults: Int = 120): List<SearchHit> {
        val qRoot = shQuote(physicalRoot)
        val qPat = shQuote("*${query.trim()}*")
        val out = exec("find $qRoot -maxdepth 7 -iname $qPat 2>/dev/null | head -n $maxResults")
        if (out.exitCode != 0 && out.stdout.isBlank()) return emptyList()
        val physPrefix = physicalRoot.trimEnd('/')
        val vPrefix = normalizeVfsPath(virtualRoot).trimEnd('/')
        return out.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() && it != physicalRoot }.map { phys ->
            val rel = if (physPrefix.isEmpty()) phys else phys.removePrefix(physPrefix)
            val vPath = normalizeVfsPath(vPrefix + "/" + rel.trimStart('/'))
            SearchHit(scope = scope, path = vPath, name = vPath.substringAfterLast('/'))
        }.toList()
    }

    /** 在 Root 权限目录内执行递归全文 Grep（自动跳过二进制文件）。 */
    fun grepContent(
        physicalRoot: String,
        virtualRoot: String,
        scope: FileScope,
        query: String,
        useRegex: Boolean,
        caseSensitive: Boolean,
        maxResults: Int = 120,
    ): List<SearchHit> {
        val qRoot = shQuote(physicalRoot)
        val qPat = shQuote(query)
        val flags = buildString {
            append("-rnI")
            if (!caseSensitive) append('i')
            append(if (useRegex) 'E' else 'F')
        }
        val out = exec("grep $flags -- $qPat $qRoot 2>/dev/null | head -n $maxResults")
        if (out.stdout.isBlank()) return emptyList()
        val physPrefix = physicalRoot.trimEnd('/')
        val vPrefix = normalizeVfsPath(virtualRoot).trimEnd('/')
        return out.stdout.lineSequence().mapNotNull { line ->
            val firstColon = line.indexOf(':')
            if (firstColon <= 0) return@mapNotNull null
            val secondColon = line.indexOf(':', firstColon + 1)
            if (secondColon <= firstColon + 1) return@mapNotNull null
            val phys = line.substring(0, firstColon)
            val lineNum = line.substring(firstColon + 1, secondColon).toIntOrNull() ?: return@mapNotNull null
            val snippet = line.substring(secondColon + 1).trim().take(180)
            val rel = if (physPrefix.isEmpty()) phys else phys.removePrefix(physPrefix)
            val vPath = normalizeVfsPath(vPrefix + "/" + rel.trimStart('/'))
            SearchHit(scope = scope, path = vPath, name = vPath.substringAfterLast('/'), isDirectory = false, lineNumber = lineNum, previewLine = snippet)
        }.toList()
    }

    fun newStageFile(prefix: String = "rfs-"): File = File.createTempFile(prefix, ".tmp", stageRoot)

    private fun requireOk(cmd: String, fallbackError: String) {
        val out = exec(cmd)
        if (out.exitCode != 0) throw IllegalStateException(out.stdout.ifBlank { fallbackError })
    }

    companion object {
        private val PROTECTED_PREFIXES = setOf("/system", "/vendor", "/product", "/odm", "/system_ext", "/apex", "/proc", "/sys", "/dev")
    }
}
