package com.galiais.agent.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ToolchainItem(
    val id: String,
    val name: String,
    val category: String,
    val rootless: Boolean,
    val multiVersion: Boolean,
    val requiresJdk: Boolean = false,
    val summary: String,
    val approxSizeMB: Int,
    val installed: Boolean,
    val version: String,
    val activeVersion: String,
    val installedVersions: List<String>,
    val binPath: String,
    val managed: Boolean,
    val diskBytes: Long,
    val canUninstall: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject): ToolchainItem {
            val vArr = o.optJSONArray("installedVersions")
            val vers = buildList { if (vArr != null) for (i in 0 until vArr.length()) add(vArr.optString(i)) }
            return ToolchainItem(
                id = o.optString("id"),
                name = o.optString("name"),
                category = o.optString("category", "compiler"),
                rootless = o.optBoolean("rootless", true),
                multiVersion = o.optBoolean("multiVersion", true),
                requiresJdk = o.optBoolean("requiresJdk", false),
                summary = o.optString("summary"),
                approxSizeMB = o.optInt("approxSizeMB"),
                installed = o.optBoolean("installed"),
                version = o.optString("version"),
                activeVersion = o.optString("activeVersion"),
                installedVersions = vers,
                binPath = o.optString("binPath"),
                managed = o.optBoolean("managed"),
                diskBytes = o.optLong("diskBytes"),
                canUninstall = o.optBoolean("canUninstall"),
            )
        }
    }
}

data class ToolchainVersionOption(
    val version: String,
    val channel: String,
    val filename: String = "",
    val sha256: String = "",
    val sha512: String = "",
    val checksumUrl: String = "",
    val buildToolsVersion: String = "",
    val sizeBytes: Long = 0L,
    val url: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = ToolchainVersionOption(
            version = o.optString("version"),
            channel = o.optString("channel"),
            filename = o.optString("filename"),
            sha256 = o.optString("sha256"),
            sha512 = o.optString("sha512"),
            checksumUrl = o.optString("checksumUrl"),
            buildToolsVersion = o.optString("buildToolsVersion"),
            sizeBytes = o.optLong("sizeBytes"),
            url = o.optString("url"),
        )
    }
}

data class ToolchainSnapshot(
    val platform: String = "linux",
    val arch: String = "arm64",
    val isRoot: Boolean = false,
    val aptWritable: Boolean = false,
    val aptViaHostRoot: Boolean = false,
    val mirrorPreset: String = "official",
    val npmRegistry: String = "",
    val pypiIndexUrl: String = "",
    val goProxy: String = "",
    val toolchains: List<ToolchainItem> = emptyList(),
) {
    companion object {
        fun fromJson(o: JSONObject): ToolchainSnapshot {
            val m = o.optJSONObject("mirrors") ?: JSONObject()
            return ToolchainSnapshot(
                platform = o.optString("platform", "linux"),
                arch = o.optString("arch", "arm64"),
                isRoot = o.optBoolean("isRoot"),
                aptWritable = o.optBoolean("aptWritable"),
                aptViaHostRoot = o.optBoolean("aptViaHostRoot"),
                mirrorPreset = o.optString("mirrorPreset", "official"),
                npmRegistry = m.optString("npmRegistry"),
                pypiIndexUrl = m.optString("pypiIndexUrl"),
                goProxy = m.optString("goProxy"),
                toolchains = o.arr("toolchains").objects().map(ToolchainItem::fromJson),
            )
        }
    }
}

data class ToolchainProgress(val id: String, val stage: String, val percent: Int, val message: String)

data class ProjectDepEntry(val name: String, val version: String, val dev: Boolean) {
    companion object {
        fun fromJson(o: JSONObject) = ProjectDepEntry(o.optString("name"), o.optString("version"), o.optBoolean("dev"))
    }
}

data class ProjectVersionHint(val toolchainId: String, val required: String, val source: String) {
    companion object {
        fun fromJson(o: JSONObject) = ProjectVersionHint(o.optString("toolchainId"), o.optString("required"), o.optString("source"))
    }
}

data class ProjectDepEcosystem(
    val id: String,
    val ecosystem: String,
    val label: String,
    val manager: String,
    val cwd: String,
    val manifest: String,
    val lockfile: String,
    val envReady: Boolean,
    val requiredToolchain: String,
    val depCount: Int,
    val deps: List<ProjectDepEntry>,
) {
    companion object {
        fun fromJson(o: JSONObject) = ProjectDepEcosystem(
            id = o.optString("id"),
            ecosystem = o.optString("ecosystem"),
            label = o.optString("label"),
            manager = o.optString("manager"),
            cwd = o.optString("cwd"),
            manifest = o.optString("manifest"),
            lockfile = o.optString("lockfile"),
            envReady = o.optBoolean("envReady", true),
            requiredToolchain = o.optString("requiredToolchain"),
            depCount = o.optInt("depCount"),
            deps = o.arr("deps").objects().map(ProjectDepEntry::fromJson),
        )
    }
}

data class CompileTarget(
    val id: String,
    val label: String,
    val ecosystem: String,
    val cwd: String,
    val command: String,
    val args: List<String>,
    val requiredToolchain: String,
    val crossCompile: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject): CompileTarget {
            val arr = o.optJSONArray("args")
            val argsList = buildList { if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i)) }
            return CompileTarget(
                id = o.optString("id"),
                label = o.optString("label"),
                ecosystem = o.optString("ecosystem"),
                cwd = o.optString("cwd"),
                command = o.optString("command"),
                args = argsList,
                requiredToolchain = o.optString("requiredToolchain"),
                crossCompile = o.optBoolean("crossCompile"),
            )
        }
    }
}

data class ProjectScanResult(
    val cwd: String,
    val ecosystems: List<ProjectDepEcosystem>,
    val compileTargets: List<CompileTarget>,
    val versionHints: List<ProjectVersionHint> = emptyList(),
) {
    companion object {
        fun fromJson(o: JSONObject) = ProjectScanResult(
            cwd = o.optString("cwd"),
            ecosystems = o.arr("ecosystems").objects().map(ProjectDepEcosystem::fromJson),
            compileTargets = o.arr("compileTargets").objects().map(CompileTarget::fromJson),
            versionHints = o.arr("versionHints").objects().map(ProjectVersionHint::fromJson),
        )
    }
}

data class AptPackageItem(val name: String, val version: String, val sizeKB: Int, val summary: String, val installed: Boolean) {
    companion object {
        fun fromJson(o: JSONObject) = AptPackageItem(o.optString("name"), o.optString("version"), o.optInt("sizeKB"), o.optString("summary"), o.optBoolean("installed"))
    }
}

data class CacheDiskEntry(val id: String, val label: String, val path: String, val bytes: Long) {
    companion object {
        fun fromJson(o: JSONObject) = CacheDiskEntry(o.optString("id"), o.optString("label"), o.optString("path"), o.optLong("bytes"))
    }
}

data class CacheStats(val totalBytes: Long = 0L, val entries: List<CacheDiskEntry> = emptyList()) {
    companion object {
        fun fromJson(o: JSONObject) = CacheStats(o.optLong("totalBytes"), o.arr("entries").objects().map(CacheDiskEntry::fromJson))
    }
}

data class ToolchainCmdResult(
    val ok: Boolean,
    val exitCode: Int,
    val ms: Long,
    val command: String,
    val stdout: String,
    val stderr: String,
    val artifactPath: String = "",
    val artifactSize: Long = 0L,
) {
    companion object {
        fun fromJson(o: JSONObject) = ToolchainCmdResult(
            ok = o.optBoolean("ok"),
            exitCode = o.optInt("exitCode", -1),
            ms = o.optLong("ms"),
            command = o.optString("command"),
            stdout = o.optString("stdout"),
            stderr = o.optString("stderr"),
            artifactPath = o.optString("artifactPath"),
            artifactSize = o.optLong("artifactSize"),
        )
    }
}

/** 编译器多版本管理、项目依赖、Ubuntu 系统包与编译工坊状态仓库。 */
class ToolchainRepository(
    private val runtime: RuntimeClient,
    private val onNotify: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val snapshotFlow = MutableStateFlow(ToolchainSnapshot())
    val snapshot: StateFlow<ToolchainSnapshot> = snapshotFlow.asStateFlow()

    private val progressFlow = MutableStateFlow<ToolchainProgress?>(null)
    val progress: StateFlow<ToolchainProgress?> = progressFlow.asStateFlow()

    private val logLinesFlow = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = logLinesFlow.asStateFlow()

    private val projectScanFlow = MutableStateFlow<ProjectScanResult?>(null)
    val projectScan: StateFlow<ProjectScanResult?> = projectScanFlow.asStateFlow()

    private val aptPackagesFlow = MutableStateFlow<List<AptPackageItem>>(emptyList())
    val aptPackages: StateFlow<List<AptPackageItem>> = aptPackagesFlow.asStateFlow()

    private val cacheStatsFlow = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = cacheStatsFlow.asStateFlow()

    fun onProgressEvent(o: JSONObject) {
        progressFlow.value = ToolchainProgress(o.optString("id"), o.optString("stage"), o.optInt("percent"), o.optString("message"))
        val line = o.optString("logLine").trim()
        if (line.isNotBlank()) logLinesFlow.value = (logLinesFlow.value + line).takeLast(60)
    }

    fun clearProgress() {
        progressFlow.value = null
        logLinesFlow.value = emptyList()
    }

    suspend fun refreshToolchains(): ToolchainSnapshot {
        if (!runtime.hubAvailable) return snapshotFlow.value
        val res = runtime.hubRequest("toolchains/list", JSONObject(), timeoutMs = 20_000)
        return ToolchainSnapshot.fromJson(res).also { snapshotFlow.value = it }
    }

    fun refreshToolchainsAsync() {
        scope.launch { runCatching { refreshToolchains() } }
    }

    suspend fun fetchVersions(id: String, refresh: Boolean = false): List<ToolchainVersionOption> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("toolchains/versions", jsonOf("id" to id, "refresh" to refresh), timeoutMs = 25_000)
        return res.arr("versions").objects().map(ToolchainVersionOption::fromJson)
    }

    suspend fun installToolchain(
        id: String,
        version: String = "",
        setDefault: Boolean = true,
        extraTargets: List<String> = emptyList(),
        aptComponents: List<String> = emptyList(),
    ) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        logLinesFlow.value = emptyList()
        val p = jsonOf(
            "id" to id,
            "version" to version.trim(),
            "setDefault" to setDefault,
            "extraTargets" to JSONArray(extraTargets),
            "aptComponents" to JSONArray(aptComponents),
        )
        val res = runtime.hubRequest("toolchains/install", p, timeoutMs = 600_000)
        res.optJSONObject("status")?.let { snapshotFlow.value = ToolchainSnapshot.fromJson(it) } ?: refreshToolchains()
        val verLabel = res.optString("version").ifBlank { version }
        onNotify("已完成安装：$id${if (verLabel.isNotBlank()) " $verLabel" else ""}")
    }

    suspend fun useVersion(id: String, version: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("toolchains/use", jsonOf("id" to id, "version" to version), timeoutMs = 20_000)
        res.optJSONObject("status")?.let { snapshotFlow.value = ToolchainSnapshot.fromJson(it) } ?: refreshToolchains()
        onNotify("已切换 $id 默认版本为 $version")
    }

    suspend fun uninstallToolchain(id: String, version: String = "") {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("toolchains/uninstall", jsonOf("id" to id, "version" to version), timeoutMs = 40_000)
        res.optJSONObject("status")?.let { snapshotFlow.value = ToolchainSnapshot.fromJson(it) } ?: refreshToolchains()
        onNotify(if (version.isNotBlank()) "已移除 $id $version" else "已卸载 $id")
    }

    suspend fun cancelActive() {
        if (!runtime.hubAvailable) return
        runCatching { runtime.hubRequest("toolchains/cancel", JSONObject(), timeoutMs = 5_000) }
        onNotify("已发送终止指令")
    }

    suspend fun applyMirrorPreset(preset: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("toolchains/mirrorPreset", jsonOf("preset" to preset), timeoutMs = 15_000)
        refreshToolchains()
        onNotify("已切换软件源：${res.optString("label", preset)}")
    }

    suspend fun refreshCaches(): CacheStats {
        if (!runtime.hubAvailable) return cacheStatsFlow.value
        val res = runtime.hubRequest("toolchains/caches", JSONObject(), timeoutMs = 20_000)
        return CacheStats.fromJson(res).also { cacheStatsFlow.value = it }
    }

    suspend fun cleanCaches(ids: List<String> = emptyList()): Long {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("toolchains/cleanCaches", jsonOf("ids" to JSONArray(ids)), timeoutMs = 60_000)
        res.optJSONObject("after")?.let { cacheStatsFlow.value = CacheStats.fromJson(it) } ?: refreshCaches()
        val freed = res.optLong("freedBytes")
        onNotify("缓存已清理，释放 ${String.format("%.1f MB", freed / 1048576.0)}")
        return freed
    }

    suspend fun scanProject(cwd: String): ProjectScanResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("deps/scan", jsonOf("cwd" to cwd), timeoutMs = 20_000)
        return ProjectScanResult.fromJson(res).also { projectScanFlow.value = it }
    }

    suspend fun runProjectDepAction(
        cwd: String,
        ecosystem: String,
        action: String,
        packages: List<String> = emptyList(),
        dev: Boolean = false,
    ): ToolchainCmdResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        logLinesFlow.value = emptyList()
        val res = runtime.hubRequest(
            "deps/run",
            jsonOf("cwd" to cwd, "ecosystem" to ecosystem, "action" to action, "packages" to JSONArray(packages), "dev" to dev),
            timeoutMs = 300_000,
        )
        runCatching { scanProject(cwd) }
        return ToolchainCmdResult.fromJson(res)
    }

    suspend fun queryApt(action: String = "list", query: String = ""): List<AptPackageItem> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("deps/apt", jsonOf("action" to action, "query" to query), timeoutMs = 30_000)
        return res.arr("packages").objects().map(AptPackageItem::fromJson).also { aptPackagesFlow.value = it }
    }

    suspend fun mutateApt(action: String, packages: List<String> = emptyList()): ToolchainCmdResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        logLinesFlow.value = emptyList()
        val res = runtime.hubRequest("deps/apt", jsonOf("action" to action, "packages" to JSONArray(packages)), timeoutMs = 300_000)
        return ToolchainCmdResult.fromJson(res)
    }

    suspend fun runCompile(
        cwd: String,
        ecosystem: String = "auto",
        command: String = "",
        args: List<String> = emptyList(),
        targetOs: String = "",
        targetArch: String = "",
        file: String = "",
    ): ToolchainCmdResult {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        logLinesFlow.value = emptyList()
        val p = jsonOf("cwd" to cwd, "ecosystem" to ecosystem)
        if (command.isNotBlank()) p.put("command", command)
        if (args.isNotEmpty()) p.put("args", JSONArray(args))
        if (targetOs.isNotBlank()) p.put("targetOs", targetOs)
        if (targetArch.isNotBlank()) p.put("targetArch", targetArch)
        if (file.isNotBlank()) p.put("file", file)
        val res = runtime.hubRequest("compile/run", p, timeoutMs = 300_000)
        return ToolchainCmdResult.fromJson(res)
    }
}
