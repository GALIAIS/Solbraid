package com.galiais.agent

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 本地 Linux 根环境（Root chroot 或免 Root PRoot）+ QEMU 虚拟机的安装状态与真实磁盘占用快照。 */
data class EnvironmentStatus(
    val mode: String = RUNTIME_MODE_ROOT,
    val chrootInstalled: Boolean = false,
    val vmProvisioned: Boolean = false,
    val sshTrusted: Boolean = false,
    val codexInstalled: Boolean = false,
    val claudeInstalled: Boolean = false,
    val antigravityInstalled: Boolean = false,
    val daemonSocketPresent: Boolean = false,
    val rootEnvInstalled: Boolean = false,
    val prootEnvInstalled: Boolean = false,
    val prootBinariesReady: Boolean = false,
    val chrootBytes: Long = 0L,
    val vmBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val appCacheBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val loading: Boolean = true,
    val legacyBackupPresent: Boolean = false,
) {
    val fullyInstalled: Boolean get() = chrootInstalled && vmProvisioned && codexInstalled && claudeInstalled && antigravityInstalled
    val totalUsedBytes: Long get() = chrootBytes + cacheBytes + appCacheBytes

    /** 返回指定模式是否已安装，避免切换模式后巡检尚未返回时界面误闪「安装运行环境」。 */
    fun isInstalledFor(targetMode: String): Boolean = when {
        mode == targetMode && !loading -> fullyInstalled
        targetMode == RUNTIME_MODE_PROOT -> prootEnvInstalled
        else -> rootEnvInstalled
    }
}

/** 在应用私有目录中安装、巡检、重置与卸载宿主 Linux（支持 Root chroot 与免 Root PRoot 双模式）。 */
class LinuxRuntime(private val context: Context, val mode: String = RUNTIME_MODE_ROOT) {
    val isProot: Boolean get() = mode == RUNTIME_MODE_PROOT
    val root: File = File(context.filesDir, if (isProot) "linux-proot" else "linux")
    private val workspace = File(root, "home/agent/workspace")
    private val vmDir = File(root, "home/agent/.galiais/vm")
    private val muxDir = File(root, "home/agent/.galiais/mux")
    private val cacheDir = File(context.filesDir, "runtime-downloads")
    val ready: Boolean get() = File(root, ".galiais-ready").isFile
    val vmProvisioned: Boolean
        get() = runCatching { File(vmDir, ".provisioned").readText(Charsets.UTF_8).trim() == "native-direct-v1" }.getOrDefault(false)
    val daemonSocketFile: File get() = File(muxDir, "gateway.sock")
    private val activeCommand = AtomicReference<ManagedProcess?>(null)

    companion object {
        /** 按实际权限与运行能力判断，兼容共享 su 协议的 Root 管理器和分支。 */
        fun checkRootAccess(timeoutSeconds: Long = 8): RootAccessResult = RootShell.check(timeoutSeconds)
        fun probeRootAvailable(timeoutSeconds: Long = 8): Boolean = checkRootAccess(timeoutSeconds).available
        fun requestRootAccess(timeoutSeconds: Long = 25): Boolean = checkRootAccess(timeoutSeconds).available

        fun prootBinariesPresent(context: Context): Boolean {
            val lib = File(context.applicationInfo.nativeLibraryDir)
            return File(lib, "libproot.so").canExecute() && File(lib, "libproot-loader.so").canExecute()
        }

        /**
         * 修剪 `cache/file-viewer/` 目录（按修改时间 LRU 淘汰超出容量配额或过期文件），
         * 可传入 keepFile 保护刚刚暂存准备打开的当前文件，杜绝单次查看大 APK/视频永久滞留数百 MB。
         */
        fun pruneViewerCacheDir(
            context: Context,
            keepFile: File? = null,
            maxBytes: Long = 80L * 1024 * 1024,
            maxAgeMs: Long = 24L * 3600_000L,
        ): Long {
            val viewerDir = File(context.cacheDir, "file-viewer")
            if (!viewerDir.isDirectory) return 0L
            val keepPath = runCatching { keepFile?.canonicalPath }.getOrNull()
            val now = System.currentTimeMillis()
            val files = viewerDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            var keptBytes = 0L
            var freed = 0L
            for (f in files) {
                val canon = runCatching { f.canonicalPath }.getOrNull()
                if (keepPath != null && canon == keepPath) {
                    keptBytes += f.length()
                    continue
                }
                val len = f.length()
                val expired = maxAgeMs > 0L && (now - f.lastModified()) > maxAgeMs
                if (expired || keptBytes + len > maxBytes) {
                    if (runCatching { f.delete() }.getOrDefault(false)) freed += len
                } else {
                    keptBytes += len
                }
            }
            return freed
        }

        /** 应用启动时后台异步自动清理过期临时缓存（不触碰用户项目、会话或登录凭据）。 */
        fun pruneTransientAppCaches(context: Context): Long {
            var freed = 0L
            freed += pruneViewerCacheDir(context)
            val cache = context.cacheDir
            val now = System.currentTimeMillis()
            val tempCutoff = now - 2L * 3600_000L
            val exportCutoff = now - 24L * 3600_000L
            cache.listFiles()?.forEach { child ->
                val name = child.name
                val shouldDelete = when {
                    name.startsWith("zip-in-") || name.startsWith("zip-out-") || name.startsWith("extract-") ||
                        (name.startsWith("galiais-export-") && name.endsWith(".zip")) -> child.lastModified() < tempCutoff
                    name == "root-fs-stage" && child.isDirectory -> {
                        child.listFiles()?.forEach { sub ->
                            if (sub.lastModified() < tempCutoff) {
                                freed += safeSize(sub)
                                runCatching { sub.deleteRecursively() }
                            }
                        }
                        false
                    }
                    (name == "exports" || name == "self-build-apks") && child.isDirectory -> {
                        child.listFiles()?.forEach { sub ->
                            if (sub.lastModified() < exportCutoff) {
                                freed += safeSize(sub)
                                runCatching { sub.deleteRecursively() }
                            }
                        }
                        false
                    }
                    else -> false
                }
                if (shouldDelete) {
                    freed += safeSize(child)
                    runCatching { child.deleteRecursively() }
                }
            }
            return freed
        }

        private fun safeSize(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(file)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                cur.listFiles()?.forEach { c ->
                    if (c.isDirectory) stack.addLast(c) else if (c.isFile) total += c.length()
                }
            }
            return total
        }
    }

    private fun symlinkOrFileExists(file: File): Boolean =
        runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)

    fun hasAnyMuxArtifacts(): Boolean =
        listOf("gateway", "codex", "claude", "agent-hub").any { channel ->
            listOf("sock", "pid", "generation").any { ext -> symlinkOrFileExists(File(muxDir, "$channel.$ext")) }
        }

    fun daemonGenerationMatches(generation: String): Boolean =
        listOf("gateway", "codex", "claude", "agent-hub").all { channel ->
            val hasChannel = symlinkOrFileExists(File(muxDir, "$channel.sock")) ||
                symlinkOrFileExists(File(muxDir, "$channel.generation"))
            !hasChannel || runCatching {
                val marker = File(muxDir, "$channel.generation")
                marker.length() <= 4096 && marker.readText(Charsets.UTF_8) == generation
            }.getOrDefault(false)
        }

    /** 无需提权即可安全扫描环境组件状态与真实已分配磁盘块大小（跳过符号链接与不可读目录）。 */
    fun inspect(): EnvironmentStatus {
        val nlib = File(context.applicationInfo.nativeLibraryDir)
        val chrootOk = File(root, ".galiais-ready").isFile
        val vmOk = vmProvisioned
        val sshOk = chrootOk && File(root, "etc/ssl/certs/ca-certificates.crt").isFile
        val rootProvisioned = !isProot && chrootOk && vmOk
        val codexOk = File(nlib, "libbin_codex.so").isFile &&
            File(nlib, "libbin_codex_code_mode_host.so").isFile &&
            (rootProvisioned || (symlinkOrFileExists(File(root, "opt/galiais-codex/usr/lib/chatgpt/resources/codex")) &&
                symlinkOrFileExists(File(root, "opt/galiais-codex/usr/lib/chatgpt/resources/codex-code-mode-host"))))
        val claudeOk = File(nlib, "libbin_claude.so").isFile &&
            (rootProvisioned || symlinkOrFileExists(File(root, "opt/galiais-codex/claude/current/claude")))
        val antigravityOk = File(nlib, "libbin_antigravity.so").isFile &&
            (rootProvisioned || symlinkOrFileExists(File(root, "opt/galiais-codex/usr/lib/chatgpt/resources/agy")))
        val daemonSock = symlinkOrFileExists(daemonSocketFile)
        val chrootBytes = if (root.exists()) allocatedBytes(root) else 0L
        val vmBytes = if (workspace.exists()) allocatedBytes(workspace) else 0L
        val cacheBytes = if (cacheDir.exists()) allocatedBytes(cacheDir) else 0L
        val appCacheBytes = computeAppCacheBytes()
        val freeBytes = context.filesDir.usableSpace
        val rootComplete = File(context.filesDir, "linux/.galiais-ready").isFile &&
            runCatching { File(context.filesDir, "linux/home/agent/.galiais/vm/.provisioned").readText(Charsets.UTF_8).trim() == "native-direct-v1" }.getOrDefault(false)
        val prootComplete = File(context.filesDir, "linux-proot/.galiais-ready").isFile &&
            runCatching { File(context.filesDir, "linux-proot/home/agent/.galiais/vm/.provisioned").readText(Charsets.UTF_8).trim() == "native-direct-v1" }.getOrDefault(false)
        return EnvironmentStatus(
            mode = mode,
            chrootInstalled = chrootOk,
            vmProvisioned = vmOk,
            sshTrusted = sshOk,
            codexInstalled = codexOk,
            claudeInstalled = claudeOk,
            antigravityInstalled = antigravityOk,
            daemonSocketPresent = daemonSock,
            rootEnvInstalled = rootComplete,
            prootEnvInstalled = prootComplete,
            prootBinariesReady = prootBinariesPresent(context),
            chrootBytes = chrootBytes,
            vmBytes = vmBytes,
            cacheBytes = cacheBytes,
            appCacheBytes = appCacheBytes,
            freeSpaceBytes = freeBytes,
            loading = false,
            legacyBackupPresent = File(context.filesDir, "workspace-backups/pending-reset-restore.tgz").isFile,
        )
    }

    /** 统计可安全清理的应用暂存缓存与历史运行日志（含 cache/file-viewer、临时解压、npm-cache 与旧 agy 日志）。 */
    private fun computeAppCacheBytes(): Long {
        var total = 0L
        val cache = context.cacheDir
        if (cache.isDirectory) {
            cache.listFiles()?.forEach { child ->
                val n = child.name
                if (n in setOf("file-viewer", "exports", "root-fs-stage") ||
                    n.startsWith("zip-in-") || n.startsWith("zip-out-") || n.startsWith("extract-") ||
                    (n.startsWith("galiais-export-") && n.endsWith(".zip"))) {
                    total += allocatedBytes(child)
                }
            }
        }
        val agyLogDir = File(root, "home/agent/.gemini/antigravity-cli/log")
        if (agyLogDir.isDirectory) total += allocatedBytes(agyLogDir)
        val npmCache = File(root, "home/agent/.galiais/npm-cache")
        if (npmCache.isDirectory) total += allocatedBytes(npmCache)
        return total
    }

    /** 一键清理应用预览暂存、导出/压缩临时目录、npm 缓存与过期运行日志（保留最近 5 份日志与全部项目/会话/登录态）。 */
    fun clearAppCache(): Long {
        val before = computeAppCacheBytes()
        val cache = context.cacheDir
        if (cache.isDirectory) {
            cache.listFiles()?.forEach { child ->
                val n = child.name
                if (n in setOf("file-viewer", "exports", "root-fs-stage") ||
                    n.startsWith("zip-in-") || n.startsWith("zip-out-") || n.startsWith("extract-") ||
                    (n.startsWith("galiais-export-") && n.endsWith(".zip"))) {
                    deleteLocalTree(child)
                }
            }
        }
        val agyLogDir = File(root, "home/agent/.gemini/antigravity-cli/log")
        if (agyLogDir.isDirectory) {
            val activeCutoff = System.currentTimeMillis() - 10L * 60_000L
            val logs = agyLogDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            logs.drop(5).filter { it.lastModified() < activeCutoff }.forEach { runCatching { it.delete() } }
        }
        val npmCache = File(root, "home/agent/.galiais/npm-cache")
        if (npmCache.isDirectory) {
            npmCache.listFiles()?.forEach { deleteLocalTree(it) }
        }
        val after = computeAppCacheBytes()
        return (before - after).coerceAtLeast(0L)
    }

    /** 清理已下载的安装包缓存（files/runtime-downloads），释放约 1.15 GB 空间；不影响已运行的环境。 */
    fun clearDownloadCache(): Long {
        if (!cacheDir.exists()) return 0L
        val before = allocatedBytes(cacheDir)
        check(cacheDir.canonicalFile.parentFile == context.filesDir.canonicalFile && cacheDir.name == "runtime-downloads") {
            "缓存目录校验失败，已取消清理"
        }
        cacheDir.listFiles()?.forEach { child ->
            deleteLocalTree(child)
        }
        return before
    }

    /** 安全重置工作区与会话记录，保留已解压的 Ubuntu 根环境。 */
    fun resetVm(control: RunControl, log: (String) -> Unit) {
        if (isProot) {
            log("正在重置免 Root 工作区与会话记录")
            val home = File(root, "home/agent")
            listOf("workspace", ".codex", ".claude", ".gemini", ".galiais/agent-sessions", ".galiais/mux", ".galiais/vm")
                .map { File(home, it) }.forEach { target ->
                    check(target.canonicalPath.startsWith(home.canonicalPath + File.separator)) { "重置路径指向环境之外：${target.name}" }
                    if (target.exists()) {
                        runCatching { runCommand("chmod -R u+rwX -- ${quote(target.absolutePath)} 2>/dev/null || true", control, 30) }
                        deleteLocalTree(target)
                    }
                }
            check(workspace.isDirectory || workspace.mkdirs()) { "无法重建工作区目录；项目备份已保留" }
            log("工作区与会话已重置")
            return
        }
        requireRoot(control)
        val uid = Process.myUid()
        log("正在校验进程并重置工作区与会话记录")
        val script = """
            set -eu
            base=${'$'}(readlink -f ${quote(context.filesDir.absolutePath)})
            case "${'$'}base" in */com.galiais.agent/files) ;; *) echo "拒绝操作非应用目录：${'$'}base"; exit 64 ;; esac
            target="${'$'}base/linux"
            if test -d "${'$'}target"; then
                ${chrootProcessGuardScript()}
                terminate_chroot_procs
                unmount_chroot_paths
            fi
            rm -rf -- "${'$'}target/home/agent/workspace" "${'$'}target/home/agent/.codex" "${'$'}target/home/agent/.claude" "${'$'}target/home/agent/.gemini" "${'$'}target/home/agent/.galiais/agent-sessions" "${'$'}target/home/agent/.galiais/mux" "${'$'}target/home/agent/.galiais/vm"
            mkdir -p "${'$'}target/home/agent/workspace"
            chown -R $uid:$uid "${'$'}target/home/agent"
        """.trimIndent()
        val result = runCommand(script, control, 60)
        check(result.code == 0) { "重置工作区失败：${result.text.trim()}" }
        log("工作区与会话已重置")
    }

    /** 彻底删除当前模式的 Ubuntu 根环境（Root: files/linux；免 Root: files/linux-proot）。 */
    fun deleteEnvironment(deleteCache: Boolean, control: RunControl, log: (String) -> Unit) {
        if (isProot) {
            log("正在删除免 Root Ubuntu PRoot 环境")
            val expectedBase = context.filesDir.canonicalFile
            if (root.exists()) {
                check(root.canonicalFile.parentFile == expectedBase && root.name == "linux-proot") { "PRoot 目录校验失败" }
                // PRoot 根文件系统里可能有只读文件（如 base.qcow2 0440），用 chmod 解除写保护后递归删除。
                val script = """
                    set -eu
                    target=${quote(root.absolutePath)}
                    chmod -R u+rwX -- "${'$'}target" 2>/dev/null || true
                    rm -rf -- "${'$'}target"
                """.trimIndent()
                val res = runCommand(script, control, 120)
                check(res.code == 0) { "删除 PRoot 环境失败：${res.text.trim()}" }
            }
            context.filesDir.listFiles { f -> f.name.startsWith("linux-proot-install-") }?.forEach(::deleteLocalTree)
            deleteLocalTree(File(context.cacheDir, "proot-tmp"))
            if (deleteCache) clearDownloadCache()
            check(!root.exists()) { "删除命令已退出，但 linux-proot 目录仍存在" }
            log(if (deleteCache) "免 Root 环境与下载缓存已删除" else "免 Root 环境已删除，下载缓存保留")
            return
        }
        requireRoot(control)
        log("正在校验 chroot 进程与挂载点")
        val script = """
            set -eu
            base=${'$'}(readlink -f ${quote(context.filesDir.absolutePath)})
            case "${'$'}base" in */com.galiais.agent/files) ;; *) echo "拒绝操作非应用目录：${'$'}base"; exit 64 ;; esac
            target="${'$'}base/linux"
            if test -e "${'$'}target"; then
                ${chrootProcessGuardScript()}
                terminate_chroot_procs
                unmount_chroot_paths
                rm -rf -- "${'$'}target"
            fi
            for stage in "${'$'}base"/linux-install-*; do
                test -e "${'$'}stage" || continue
                umount "${'$'}stage/dev" 2>/dev/null || umount -l "${'$'}stage/dev" 2>/dev/null || true
                rm -rf -- "${'$'}stage"
            done
            if test "${if (deleteCache) "1" else "0"}" = 1 && test -e "${'$'}base/runtime-downloads"; then
                rm -rf -- "${'$'}base/runtime-downloads"
            fi
        """.trimIndent()
        log("正在删除本地 Ubuntu chroot 环境")
        val result = runCommand(script, control, 120)
        check(result.code == 0) { "删除 chroot 环境失败：${result.text.trim()}" }
        check(!root.exists()) { "删除命令已退出，但 linux 目录仍存在" }
        log(if (deleteCache) "Root 环境与下载缓存已删除" else "Root 环境已删除，下载缓存保留")
    }

    /**
     * Root 清理守卫：按 device:inode 精确识别「根目录就是本 chroot」的进程（不同挂载命名空间下 /proc/PID/root
     * 可能显示为 /data/data/... 或 /data/user/0/...，路径比较不可靠），先 TERM 后 KILL；
     * 再检查两种路径形式的残留挂载点，任何无法解除的挂载都会拒绝删除，保证 rm 永远不会穿透到 Android 宿主文件。
     */
    private fun chrootProcessGuardScript(): String = """
        target_id=${'$'}(stat -c '%d:%i' "${'$'}target")
        alt_target="/data/data/com.galiais.agent/files/linux"
        list_chroot_procs() {
            for p in /proc/[0-9]*; do
                test -d "${'$'}p" || continue
                id=${'$'}(stat -L -c '%d:%i' "${'$'}p/root" 2>/dev/null || true)
                if test "${'$'}id" = "${'$'}target_id"; then
                    printf '%s\n' "${'$'}{p#/proc/}"
                fi
            done
        }
        terminate_chroot_procs() {
            for sig in TERM TERM TERM KILL KILL; do
                pids=${'$'}(list_chroot_procs)
                test -n "${'$'}pids" || return 0
                for pid in ${'$'}pids; do
                    test "${'$'}pid" = "${'$'}${'$'}" && continue
                    kill "-${'$'}sig" "${'$'}pid" 2>/dev/null || true
                done
                sleep 1
            done
            pids=${'$'}(list_chroot_procs)
            if test -n "${'$'}pids"; then
                echo "仍有进程占用 chroot，已拒绝删除：${'$'}pids"
                exit 1
            fi
        }
        list_chroot_mounts() {
            while IFS=' ' read -r _ mp _rest; do
                case "${'$'}mp" in
                    "${'$'}target"|"${'$'}target"/*|"${'$'}alt_target"|"${'$'}alt_target"/*) printf '%s\n' "${'$'}mp" ;;
                esac
            done < /proc/mounts
        }
        unmount_chroot_paths() {
            mounts=${'$'}(list_chroot_mounts | sort -r)
            if test -n "${'$'}mounts"; then
                printf '%s\n' "${'$'}mounts" | while IFS= read -r mp; do
                    test -n "${'$'}mp" || continue
                    umount "${'$'}mp" 2>/dev/null || umount -l "${'$'}mp" 2>/dev/null || true
                done
            fi
            still=${'$'}(list_chroot_mounts)
            if test -n "${'$'}still"; then
                echo "检测到未解除的挂载点，已拒绝删除：${'$'}still"
                exit 1
            fi
        }
    """.trimIndent()

    private fun allocatedBytes(rootFile: File): Long {
        val rootStat = try { Os.lstat(rootFile.absolutePath) } catch (_: Exception) { return 0L }
        val rootDev = rootStat.st_dev
        val skippedMounts = setOf("galiais-native", "proc", "dev", "sys")
        val codexMount = File(root, "opt/galiais-codex/usr/lib/chatgpt/resources/codex")
        val seenInodes = HashSet<Long>()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(rootFile)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == codexMount) continue
            val stat = try { Os.lstat(current.absolutePath) } catch (_: Exception) { continue }
            if (OsConstants.S_ISLNK(stat.st_mode)) continue
            if (current != rootFile && (stat.st_dev != rootDev || current.name in skippedMounts)) continue
            // 多硬链接文件共享同一 inode 磁盘块，仅统计首次出现以对齐 du 精确口径
            if (!OsConstants.S_ISDIR(stat.st_mode) && stat.st_nlink > 1L && !seenInodes.add(stat.st_ino)) continue
            total += stat.st_blocks * 512L
            if (OsConstants.S_ISDIR(stat.st_mode)) {
                current.listFiles()?.forEach { child -> stack.addLast(child) }
            }
        }
        return total
    }

    private fun requireRoot(control: RunControl) {
        val result = runCommand("id; command -v chroot; command -v setsid; command -v timeout", control, 10)
        check(result.code == 0 && result.text.contains("uid=0")) { "Root 未授权或缺少 chroot/setsid/timeout：${result.text}" }
    }

    /** 全离线解压内置 Ubuntu 24.04 根文件系统（零网络下载），实时回调 0.0..1.0 进度。 */
    fun install(control: RunControl, onProgress: (Float, String) -> Unit) {
        check(!ready) { "Linux 已安装，无需重复安装" }
        onProgress(0.08f, if (isProot) "正在检查 PRoot 组件" else "正在检查 Root 与 chroot 权限")
        if (isProot) {
            check(prootBinariesPresent(context)) { "安装包缺少内置 PRoot 组件（libproot.so / libproot-loader.so）" }
        } else {
            requireRoot(control)
        }
        val rootfsTar = File(context.applicationInfo.nativeLibraryDir, "libpayload_rootfs.so")
        check(rootfsTar.isFile) { "安装包缺少 Linux 系统文件（libpayload_rootfs.so）" }
        check(context.filesDir.usableSpace > 1_000_000_000L) { "至少需要 1 GB 可用空间" }
        val stagePrefix = if (isProot) "linux-proot-install-" else "linux-install-"
        val stage = File(context.filesDir, "$stagePrefix${newId()}")
        try {
            check(stage.mkdir()) { "无法创建安装暂存目录" }
            if (isProot) installProotStage(rootfsTar, stage, control, onProgress)
            else installRootStage(rootfsTar, stage, control, onProgress)
            check(ready && workspace.isDirectory) { "安装后无法访问运行环境或工作目录，未确认安装成功" }
            onProgress(1.0f, if (isProot) "免 Root 环境安装完成" else "Root 环境安装完成")
        } finally {
            if (stage.exists()) {
                if (isProot) {
                    runCatching {
                        runCommand("chmod -R u+rwX -- ${quote(stage.absolutePath)} 2>/dev/null || true; rm -rf -- ${quote(stage.absolutePath)}", RunControl(), 30)
                    }
                } else {
                    runCatching {
                        runCommand("test ${quote(stage.parentFile!!.canonicalPath)} = ${quote(context.filesDir.canonicalPath)} && rm -r -- ${quote(stage.absolutePath)}", RunControl(), 30)
                    }
                }
            }
        }
    }

    /** 沿用当前网络的 DNS，仅在 Android 没有提供地址时使用备用 DNS。 */
    internal fun buildResolvConfContent(): String {
        val dnsList = LinkedHashSet<String>()
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach { addr ->
                val host = addr.hostAddress?.substringBefore('%')?.trim().orEmpty()
                if (host.isNotEmpty() && Regex("^[0-9a-fA-F.:]+$").matches(host)) {
                    dnsList.add(host)
                }
            }
        }
        if (dnsList.isEmpty()) dnsList.addAll(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8"))
        return dnsList.joinToString("\n") { "nameserver $it" } + "\n"
    }

    /** 刷新已安装 Linux 环境的 `/etc/resolv.conf`，避免切换 Wi-Fi / 5G / 内网 VPN 后域名解析失效。 */
    fun syncResolvConf(control: RunControl) {
        if (!ready) return
        val etc = File(context.filesDir.canonicalFile, "${root.name}/etc")
        val target = File(etc, "resolv.conf")
        val content = buildResolvConfContent()
        if (target.canonicalFile == target && target.isFile && target.length() == content.length.toLong() &&
            runCatching { target.readText(Charsets.US_ASCII) }.getOrNull() == content
        ) return
        // Root 环境的 /etc 归 root 所有；在已核对的目录内替换文件，不跟随原有的 DNS 软链接。
        val script = """
            set -eu
            cd ${quote(etc.absolutePath)}
            test "${'$'}(pwd -P)" = ${quote(etc.absolutePath)}
            test ! -d resolv.conf
            tmp=${'$'}(mktemp .resolv.XXXXXXXX)
            trap 'rm -f -- "${'$'}tmp"' EXIT
            printf '%s' ${quote(content)} > "${'$'}tmp"
            chmod 644 "${'$'}tmp"
            mv -f -- "${'$'}tmp" resolv.conf
        """.trimIndent()
        val result = runCommand(script, control, 15)
        check(result.code == 0) { "无法更新 Linux 环境的 DNS：${result.text}" }
    }

    private fun installRootStage(archive: File, stage: File, control: RunControl, onProgress: (Float, String) -> Unit) {
        val uid = Process.myUid()
        val resolvContent = buildResolvConfContent()
        onProgress(0.35f, "正在解压 Linux 系统文件")
        val setup = """
            set -eu
            stage=${quote(stage.absolutePath)}
            target=${quote(root.absolutePath)}
            test ! -e "${'$'}target"
            tar -xzf ${quote(archive.absolutePath)} -C "${'$'}stage"
            chmod 755 "${'$'}stage"
            test -x "${'$'}stage/bin/sh" && test -x "${'$'}stage/usr/bin/git" && test -x "${'$'}stage/usr/bin/setpriv"
            mkdir -p "${'$'}stage/home/agent/workspace" "${'$'}stage/home/agent/.galiais" "${'$'}stage/dev" "${'$'}stage/proc" "${'$'}stage/tmp" "${'$'}stage/opt/galiais-native"
            printf 'appdata:x:$uid:\n' >> "${'$'}stage/etc/group"
            printf 'inet:x:3003:agent\n' >> "${'$'}stage/etc/group"
            printf 'agent:x:$uid:$uid:Agent:/home/agent:/bin/bash\n' >> "${'$'}stage/etc/passwd"
            printf 'agent:!:19000:0:99999:7:::\n' >> "${'$'}stage/etc/shadow"
            rm -f "${'$'}stage/etc/resolv.conf"
            printf '%s' ${quote(resolvContent)} > "${'$'}stage/etc/resolv.conf"
            printf '127.0.0.1 localhost\n::1 localhost\n' > "${'$'}stage/etc/hosts"
            chmod 1777 "${'$'}stage/tmp"
            chown -R $uid:$uid "${'$'}stage/home/agent"
            chmod -R ug+rwX "${'$'}stage/home/agent"
            printf '%s\n' 'set -eu' 'id' 'test "${'$'}(id -u)" = $uid' 'test -w /dev/null' 'test -r /dev/urandom' 'cd /home/agent/workspace' 'touch .runtime-check' 'rm .runtime-check' > "${'$'}stage/tmp/galiais-verify.sh"
            chmod 644 "${'$'}stage/tmp/galiais-verify.sh"
            trap 'umount "${'$'}stage/dev" 2>/dev/null || umount -l "${'$'}stage/dev" 2>/dev/null || true' EXIT
            mount -t tmpfs -o mode=755,size=1m,nosuid,noexec tmpfs "${'$'}stage/dev"
            for device in null:3 zero:5 random:8 urandom:9; do
                mknod "${'$'}stage/dev/${'$'}{device%:*}" c 1 "${'$'}{device#*:}"
                chmod 666 "${'$'}stage/dev/${'$'}{device%:*}"
            done
            chroot "${'$'}stage" /usr/bin/setpriv --reuid=$uid --regid=$uid --groups=3003,$uid /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin HOME=/home/agent /bin/sh /tmp/galiais-verify.sh
            umount "${'$'}stage/dev" 2>/dev/null || umount -l "${'$'}stage/dev" 2>/dev/null || true
            trap - EXIT
            rm -f "${'$'}stage/tmp/galiais-verify.sh"
            touch "${'$'}stage/.galiais-ready"
            mv "${'$'}stage" "${'$'}target"
        """.trimIndent()
        val result = runCommand(setup, control, 180)
        check(result.code == 0) { "Linux 环境安装失败：${result.text}" }
    }

    private fun installProotStage(archive: File, stage: File, control: RunControl, onProgress: (Float, String) -> Unit) {
        onProgress(0.30f, "正在解压免 Root 环境的系统文件")
        extractTarGzNonRoot(archive, stage, control)
        onProgress(0.82f, "正在配置免 Root 环境")
        val nlib = context.applicationInfo.nativeLibraryDir
        val prootTmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val resolvContent = buildResolvConfContent()
        val setup = """
            set -eu
            nlib=${quote(nlib)}
            stage=${quote(stage.absolutePath)}
            target=${quote(root.absolutePath)}
            ptmp=${quote(prootTmp.absolutePath)}
            test ! -e "${'$'}target"
            chmod 755 "${'$'}stage"
            test -x "${'$'}stage/bin/sh" || test -x "${'$'}stage/usr/bin/sh"
            test -x "${'$'}stage/usr/bin/git"
            mkdir -p "${'$'}stage/home/agent/workspace" "${'$'}stage/home/agent/.galiais" "${'$'}stage/dev" "${'$'}stage/proc" "${'$'}stage/sys" "${'$'}stage/tmp" "${'$'}stage/opt/galiais-native"
            printf 'agent:x:60000:\n' >> "${'$'}stage/etc/group"
            printf 'agent:x:60000:60000:Agent:/home/agent:/bin/bash\n' >> "${'$'}stage/etc/passwd"
            printf 'agent:!:19000:0:99999:7:::\n' >> "${'$'}stage/etc/shadow"
            rm -f "${'$'}stage/etc/resolv.conf"
            printf '%s' ${quote(resolvContent)} > "${'$'}stage/etc/resolv.conf"
            printf '127.0.0.1 localhost\n::1 localhost\n' > "${'$'}stage/etc/hosts"
            chmod 1777 "${'$'}stage/tmp"
            export LD_LIBRARY_PATH="${'$'}nlib"
            export PROOT_LOADER="${'$'}nlib/libproot-loader.so"
            export PROOT_LOADER_32="${'$'}nlib/libproot-loader32.so"
            export PROOT_TMP_DIR="${'$'}ptmp"
            export TMPDIR="${'$'}ptmp"
            "${'$'}nlib/libproot.so" --link2symlink -i 60000:60000 -r "${'$'}stage" -b /dev -b /proc -b /sys -w /home/agent/workspace /usr/bin/env -i HOME=/home/agent PATH=/usr/local/bin:/usr/bin:/bin /bin/sh -eu -c 'id; test "${'$'}(id -u)" = 60000; touch .runtime-check; rm .runtime-check'
            touch "${'$'}stage/.galiais-ready"
            mv "${'$'}stage" "${'$'}target"
        """.trimIndent()
        val result = runCommand(setup, control, 240)
        check(result.code == 0) { "免 Root PRoot 环境安装失败：${result.text}" }
    }

    /**
     * 免 Root PRoot 模式下在执行 prepare-proot-runtime.sh 之前，使用纯 Kotlin 无硬链接解包器预解压所有离线 tar.gz 组件，
     * 避免 Android 10+ SELinux 禁止 untrusted_app 调用 link(2) 导致宿主 /system/bin/tar 解压 libpayload_tools.so 报 Permission denied。
     */
    internal fun prepareProotPayloads(runtimeAssetsDir: File, control: RunControl) {
        if (!isProot || !ready) return
        val nlib = File(context.applicationInfo.nativeLibraryDir)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: "arm64-v8a"

        // 1. 安装器离线工具包（libpayload_tools.so，包含 bzcat/bzip2 -> bunzip2 等 POSIX 硬链接）
        val toolsChecksumFile = File(runtimeAssetsDir, "installer-tools-$abi.sha256")
        val toolsArchive = File(nlib, "libpayload_tools.so")
        if (toolsChecksumFile.isFile && toolsArchive.isFile) {
            val digest = toolsChecksumFile.readText(Charsets.UTF_8).trim().substringBefore(' ').trim()
            if (digest.matches(Regex("^[a-f0-9]{64}$"))) {
                val toolsTarget = File(root, "opt/galiais-install-tools/$digest")
                val alreadyValid = File(toolsTarget, "files.sha256").isFile &&
                    File(toolsTarget, "usr/bin/bunzip2").isFile &&
                    File(toolsTarget, "usr/bin/bzip2").exists() &&
                    File(toolsTarget, "usr/bin/bzcat").exists() &&
                    File(toolsTarget, "usr/bin/curl").isFile
                if (!alreadyValid) {
                    if (toolsTarget.exists()) deleteLocalTree(toolsTarget)
                    toolsTarget.mkdirs()
                    extractTarGzNonRoot(toolsArchive, toolsTarget, control)
                }
            }
        }

        // 2. 内置 npm 离线包（libpayload_npm.so）
        val npmArchive = File(nlib, "libpayload_npm.so")
        val resourcesDir = File(root, "opt/galiais-codex/usr/lib/chatgpt/resources")
        if (npmArchive.isFile && !File(resourcesDir, "lib/node_modules/npm/bin/npm-cli.js").isFile) {
            resourcesDir.mkdirs()
            extractTarGzNonRoot(npmArchive, resourcesDir, control)
        }

        // 3. Claude Agent SDK 与 Official Plan 压缩包
        val versionsFile = File(runtimeAssetsDir, "engine-versions.sh")
        if (versionsFile.isFile) {
            val vars = mutableMapOf<String, String>()
            val lineRe = Regex("""^([A-Z0-9_]+)='([^']*)'$""")
            versionsFile.readLines(Charsets.UTF_8).forEach { line ->
                lineRe.matchEntire(line.trim())?.let { m -> vars[m.groupValues[1]] = m.groupValues[2] }
            }
            val sdkVer = vars["CLAUDE_SDK_VERSION"].orEmpty()
            val sdkArchiveName = vars["CLAUDE_SDK_ARCHIVE"].orEmpty()
            if (sdkVer.isNotEmpty() && sdkArchiveName.isNotEmpty()) {
                val sdkDir = File(root, "opt/galiais-codex/claude-sdk-$sdkVer")
                val sdkTar = File(runtimeAssetsDir, sdkArchiveName)
                if (sdkTar.isFile && (!File(sdkDir, ".galiais-sdk-ready").isFile || !File(sdkDir, "sdk.mjs").isFile)) {
                    if (sdkDir.exists()) deleteLocalTree(sdkDir)
                    sdkDir.mkdirs()
                    extractTarGzNonRoot(sdkTar, sdkDir, control, stripComponents = 1)
                }
            }
            val planVer = vars["PLAN_VERSION"].orEmpty()
            val planArchiveName = vars["PLAN_ARCHIVE"].orEmpty()
            if (planVer.isNotEmpty() && planArchiveName.isNotEmpty()) {
                val planDir = File(root, "opt/galiais-codex/official-plan-$planVer")
                val planTar = File(runtimeAssetsDir, planArchiveName)
                if (planTar.isFile && !File(planDir, "skills/plan").isDirectory) {
                    planDir.mkdirs()
                    extractTarGzNonRoot(planTar, planDir, control)
                }
            }
        }
    }

    /**
     * 免 Root 解包 tar.gz：将 POSIX 硬链接（type '1'）转为相对符号链接（避免 Android SELinux 禁止 link() 及
     * PRoot 暂存路径绝对 .l2s 符号链接在 mv 之后断裂）。
     */
    internal fun extractTarGzNonRoot(archive: File, destination: File, control: RunControl, stripComponents: Int = 0) {
        fun readExact(input: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) return if (off == 0) false else error("tar 归档被截断")
                off += n
            }
            return true
        }
        fun skipExact(input: java.io.InputStream, count: Long) {
            var rem = count
            val scratch = ByteArray(8192)
            while (rem > 0) {
                val n = input.read(scratch, 0, minOf(rem, scratch.size.toLong()).toInt())
                if (n < 0) error("tar 归档内容被截断")
                rem -= n
            }
        }
        fun cstr(buf: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && buf[end] != 0.toByte()) end++
            return String(buf, off, end - off, Charsets.UTF_8)
        }
        fun octal(buf: ByteArray, off: Int, len: Int): Long {
            val s = cstr(buf, off, len).trim()
            return if (s.isEmpty()) 0L else s.toLong(8)
        }
        fun safeRel(raw: String): String {
            val parts = raw.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
            check(parts.none { it == ".." }) { "tar 归档包含非法上级路径：$raw" }
            return (if (stripComponents > 0) parts.drop(stripComponents) else parts).joinToString("/")
        }
        fun relativeLink(entryRel: String, targetRel: String): String {
            val from = entryRel.split('/').dropLast(1)
            val to = targetRel.split('/')
            var common = 0
            while (common < from.size && common < to.size && from[common] == to[common]) common++
            val up = List(from.size - common) { ".." }
            return (up + to.drop(common)).joinToString("/")
        }
        fun removeIfExists(file: File) {
            val exists = runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)
            if (exists) file.delete()
        }

        val header = ByteArray(512)
        val ioBuf = ByteArray(65_536)
        var paxPath: String? = null
        var paxLink: String? = null
        var gnuName: String? = null
        var gnuLink: String? = null
        val pendingHardLinks = mutableListOf<Pair<String, String>>()

        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { tar ->
            while (readExact(tar, header, 512)) {
                control.check()
                if (header.all { it == 0.toByte() }) break
                val type = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8).toInt() and 0x1FF
                val prefix = cstr(header, 345, 155)
                val rawName = cstr(header, 0, 100).let { if (prefix.isNotEmpty()) "$prefix/$it" else it }
                val rawLink = cstr(header, 157, 100)
                val pad = ((512 - (size % 512)) % 512)

                if (type == 'x' || type == 'g') {
                    check(size in 0L..4_194_304L) { "tar PAX 扩展头大小异常：$size" }
                    val data = ByteArray(size.toInt())
                    check(readExact(tar, data, data.size)) { "tar PAX 扩展头被截断" }
                    if (pad > 0) skipExact(tar, pad)
                    if (type == 'x') {
                        // PAX 记录长度按 UTF-8 字节偏移计算，不能用 String UTF-16 下标切片
                        var byteIdx = 0
                        while (byteIdx < data.size) {
                            var sp = byteIdx
                            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
                            if (sp <= byteIdx || sp >= data.size) break
                            val recLen = String(data, byteIdx, sp - byteIdx, Charsets.US_ASCII).toIntOrNull() ?: break
                            if (recLen <= (sp - byteIdx + 1)) break
                            val recEnd = (byteIdx + recLen).coerceAtMost(data.size)
                            val kv = String(data, sp + 1, recEnd - sp - 1, Charsets.UTF_8).trimEnd('\n')
                            val eq = kv.indexOf('=')
                            if (eq > 0) {
                                when (kv.substring(0, eq)) {
                                    "path" -> paxPath = kv.substring(eq + 1)
                                    "linkpath" -> paxLink = kv.substring(eq + 1)
                                }
                            }
                            byteIdx = recEnd
                        }
                    }
                    continue
                }
                if (type == 'L' || type == 'K') {
                    check(size in 0L..4_194_304L) { "tar GNU 长文件名头大小异常：$size" }
                    val data = ByteArray(size.toInt())
                    check(readExact(tar, data, data.size)) { "tar GNU 长文件名头被截断" }
                    if (pad > 0) skipExact(tar, pad)
                    val s = String(data, Charsets.UTF_8).trimEnd('\u0000')
                    if (type == 'L') gnuName = s else gnuLink = s
                    continue
                }

                val rel = safeRel(paxPath ?: gnuName ?: rawName)
                val linkRaw = paxLink ?: gnuLink ?: rawLink
                paxPath = null; paxLink = null; gnuName = null; gnuLink = null

                if (rel.isEmpty()) {
                    if (size > 0) skipExact(tar, size)
                    if (pad > 0) skipExact(tar, pad)
                    continue
                }
                val dst = File(destination, rel)
                when (type) {
                    '5' -> {
                        dst.mkdirs()
                        runCatching { Os.chmod(dst.absolutePath, mode or 0x1C0) }
                        if (size > 0) skipExact(tar, size)
                    }
                    '0', '\u0000' -> {
                        dst.parentFile?.mkdirs()
                        removeIfExists(dst)
                        dst.outputStream().use { out ->
                            var rem = size
                            while (rem > 0) {
                                val chunk = minOf(rem, ioBuf.size.toLong()).toInt()
                                check(readExact(tar, ioBuf, chunk)) { "tar 文件内容被截断：$rel" }
                                out.write(ioBuf, 0, chunk)
                                rem -= chunk
                            }
                        }
                        runCatching { Os.chmod(dst.absolutePath, mode or 0x180) }
                    }
                    '2' -> {
                        dst.parentFile?.mkdirs()
                        removeIfExists(dst)
                        Os.symlink(linkRaw, dst.absolutePath)
                        if (size > 0) skipExact(tar, size)
                    }
                    '1' -> {
                        val targetRel = safeRel(linkRaw)
                        pendingHardLinks.add(rel to targetRel)
                        if (size > 0) skipExact(tar, size)
                    }
                    else -> {
                        if (size > 0) skipExact(tar, size)
                    }
                }
                if (pad > 0) skipExact(tar, pad)
            }
        }
        for ((entryRel, targetRel) in pendingHardLinks) {
            val dst = File(destination, entryRel)
            dst.parentFile?.mkdirs()
            removeIfExists(dst)
            Os.symlink(relativeLink(entryRel, targetRel), dst.absolutePath)
        }
    }

    private data class CommandResult(val code: Int, val text: String)

    private fun runCommand(script: String, control: RunControl, timeoutSeconds: Long): CommandResult {
        control.check()
        val file = File(context.filesDir, "command-${newId()}.sh")
        file.writeText("#!/system/bin/sh\nexec 2>&1\n$script\n", Charsets.UTF_8)
        val managed = try { launchPlatformProcess(context, mode, file) }
            catch (error: java.io.IOException) {
                file.delete()
                throw IllegalStateException(
                    if (isProot) "无法启动免 Root PRoot 脚本进程"
                    else "未找到可调用的 su。请在 KernelSU 等管理器中为 Solbraid 授权 Root 后重试，或切换为免 Root 模式。",
                    error,
                )
            }
        if (!activeCommand.compareAndSet(null, managed)) {
            managed.close(); file.delete(); error("已有环境操作在运行")
        }
        val process = managed.process
        control.track(process)
        val output = StringBuilder()
        val readerError = AtomicReference<Throwable?>(null)
        val reader = thread(name = "linux-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { source ->
                    managed.acknowledge(source)
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        synchronized(output) { if (output.length < 128_000) output.append(buffer, 0, minOf(count, 128_000 - output.length)) }
                    }
                }
            } catch (error: java.io.IOException) { readerError.set(error) }
        }
        try {
            val started = SystemClock.elapsedRealtime()
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                control.check()
                readerError.get()?.let { throw it }
                check(SystemClock.elapsedRealtime() - started < (timeoutSeconds + 5) * 1000) { "命令超时，已停止；请检查可能产生的文件变更" }
            }
            reader.join(2000)
            control.check()
            readerError.get()?.let { throw it }
            val result = synchronized(output) { output.toString() }
            return CommandResult(process.exitValue(), result + if (result.length >= 128_000) "\n[输出已截断为 128,000 字符]" else "")
        } finally {
            try { managed.close() } finally {
                reader.join(1000)
                control.release(process)
                activeCommand.compareAndSet(managed, null)
                file.delete()
            }
        }
    }

}
