package com.galiais.agent.engine

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 已配对的远程电脑主机信息（脱敏：不含原始配对令牌明文）。 */
data class PcHostInfo(
    val id: String,
    val name: String,
    val os: String = "auto",
    val transport: String = "usb", // "usb" | "lan" | "ssh" | "public" | "relay"
    val address: String = "127.0.0.1",
    val port: Int = 29840,
    val url: String = "",
    val relayUrl: String = "",
    val relaySecret: String = "",
    val pairId: String = "",
    val sshAlias: String = "",
    val sshPort: Int = 22,
    val sshKeyPath: String = "",
    val tls: Boolean = false,
    val tlsFingerprint: String = "",
    val tlsInsecure: Boolean = false,
    val mac: String = "",
    val broadcast: String = "255.255.255.255",
    val permissionLevel: String = "operate", // "observe" | "operate" | "admin"
    val engine: String = "", // "native-go" | "node" | ""
    val hasToken: Boolean = false,
    val tokenPreview: String = "",
    val hasRelaySecret: Boolean = false,
    val isDefault: Boolean = false,
    val sshTunnelActive: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val transportLabel: String
        get() = when (transport) {
            "usb" -> "USB 直连"
            "lan" -> if (tls) "局域网 TLS" else "局域网"
            "ssh" -> "SSH 隧道"
            "public" -> "公网直连"
            "relay" -> "公网中继"
            else -> transport
        }

    val osLabel: String
        get() = when (os.lowercase()) {
            "win32", "windows" -> "Windows"
            "darwin", "macos" -> "macOS"
            "linux" -> "Linux"
            else -> "自动识别"
        }

    val permissionLabel: String
        get() = when (permissionLevel) {
            "observe" -> "只读观察"
            "admin" -> "完全管理"
            else -> "常规操控"
        }

    val endpointSummary: String
        get() = when (transport) {
            "usb" -> "127.0.0.1:$port (adb reverse)"
            "ssh" -> "${sshAlias.ifBlank { "未设 SSH 别名" }} → 127.0.0.1:$port"
            "relay" -> "${relayUrl.ifBlank { "未设中继" }} · $pairId"
            "public" -> url.ifBlank { "${if (tls) "https" else "http"}://$address:$port" }
            else -> "${if (tls) "https" else "http"}://$address:$port"
        }

    companion object {
        fun fromJson(o: JSONObject) = PcHostInfo(
            id = o.optString("id"),
            name = o.optString("name", o.optString("id")),
            os = o.optString("os", "auto"),
            transport = o.optString("transport", "usb"),
            address = o.optString("address", "127.0.0.1"),
            port = o.optInt("port", 29840),
            url = o.optString("url"),
            relayUrl = o.optString("relayUrl"),
            pairId = o.optString("pairId"),
            sshAlias = o.optString("sshAlias"),
            sshPort = o.optInt("sshPort", 22),
            sshKeyPath = o.optString("sshKeyPath"),
            tls = o.optBoolean("tls", false),
            tlsFingerprint = o.optString("tlsFingerprint"),
            tlsInsecure = o.optBoolean("tlsInsecure", false),
            mac = o.optString("mac"),
            broadcast = o.optString("broadcast", "255.255.255.255"),
            permissionLevel = o.optString("permissionLevel", "operate"),
            engine = o.optString("engine"),
            hasToken = o.optBoolean("hasToken", false),
            tokenPreview = o.optString("tokenPreview"),
            hasRelaySecret = o.optBoolean("hasRelaySecret", false),
            isDefault = o.optBoolean("isDefault", false),
            sshTunnelActive = o.optBoolean("sshTunnelActive", false),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** 新增或编辑远程电脑主机的表单模型，支持直接解析 solbraid-pc-agent.mjs 输出的配对 JSON。 */
data class PcHostDraft(
    val id: String = "",
    val name: String = "",
    val os: String = "auto",
    val transport: String = "usb",
    val address: String = "127.0.0.1",
    val port: Int = 29840,
    val url: String = "",
    val relayUrl: String = "",
    val relaySecret: String = "",
    val pairId: String = "",
    val sshAlias: String = "",
    val sshPort: Int = 22,
    val tls: Boolean = false,
    val tlsFingerprint: String = "",
    val mac: String = "",
    val broadcast: String = "255.255.255.255",
    val permissionLevel: String = "operate",
    val token: String = "",
    val setDefault: Boolean = true,
) {
    fun toRpcParams(action: String = "add"): JSONObject = JSONObject()
        .put("action", action)
        .put("id", id.trim())
        .put("name", name.trim().ifBlank { id.trim().ifBlank { "我的电脑" } })
        .put("os", os)
        .put("transport", transport)
        .put("address", address.trim())
        .put("port", port.coerceIn(1, 65535))
        .put("url", url.trim())
        .put("relayUrl", relayUrl.trim())
        .apply { if (relaySecret.isNotBlank()) put("relaySecret", relaySecret.trim()) }
        .put("pairId", pairId.trim())
        .put("sshAlias", sshAlias.trim())
        .put("sshPort", sshPort.coerceIn(1, 65535))
        .put("tls", tls)
        .put("tlsFingerprint", tlsFingerprint.trim())
        .put("mac", mac.trim())
        .put("broadcast", broadcast.trim().ifBlank { "255.255.255.255" })
        .put("permissionLevel", permissionLevel)
        .put("setDefault", setDefault)
        .apply { if (token.isNotBlank()) put("token", token.trim()) }

    companion object {
        fun fromHost(h: PcHostInfo) = PcHostDraft(
            id = h.id, name = h.name, os = h.os, transport = h.transport,
            address = h.address, port = h.port, url = h.url, relayUrl = h.relayUrl,
            pairId = h.pairId, sshAlias = h.sshAlias, sshPort = h.sshPort,
            tls = h.tls, tlsFingerprint = h.tlsFingerprint, mac = h.mac,
            broadcast = h.broadcast, permissionLevel = h.permissionLevel,
            token = "", setDefault = h.isDefault,
        )

        /** 解析电脑端 `solbraid-pc-agent.mjs` 启动横幅打印的 JSON 配对串。 */
        fun parsePairingJson(raw: String): PcHostDraft {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            require(start >= 0 && end > start) { "未在剪贴板文本中找到 JSON 配对对象" }
            val o = JSONObject(trimmed.substring(start, end + 1))
            val tok = o.optString("token").trim()
            require(tok.isNotBlank()) { "配对 JSON 缺少 token 字段" }
            val trans = o.optString("transport", "usb").lowercase().takeIf { it in setOf("usb", "lan", "ssh", "public", "relay") } ?: "usb"
            return PcHostDraft(
                id = o.optString("id"),
                name = o.optString("name", o.optString("id", "我的电脑")),
                os = o.optString("os", "auto"),
                transport = trans,
                address = o.optString("address", if (trans == "usb") "127.0.0.1" else ""),
                port = o.optInt("port", 29840),
                url = o.optString("url"),
                relayUrl = o.optString("relayUrl"),
                relaySecret = o.optString("relaySecret"),
                pairId = o.optString("pairId"),
                sshAlias = o.optString("sshAlias"),
                sshPort = o.optInt("sshPort", 22),
                tls = o.optBoolean("tls", false),
                // 配对 JSON 的 tlsFingerprint 属于电脑；中继的证书由中继服务器提供。
                tlsFingerprint = if (trans == "relay") o.optString("relayTlsFingerprint") else o.optString("tlsFingerprint"),
                mac = o.optString("mac"),
                broadcast = o.optString("broadcast", "255.255.255.255"),
                permissionLevel = o.optString("permissionLevel", "operate").takeIf { it in setOf("observe", "operate", "admin") } ?: "operate",
                token = tok,
                setDefault = true,
            )
        }
    }
}

data class PcPingResult(
    val ok: Boolean,
    val via: String = "",
    val latencyMs: Int = 0,
    val platform: String = "",
    val hostname: String = "",
    val user: String = "",
    val permissionLevel: String = "",
    val emergencyStopActive: Boolean = false,
    val error: String = "",
)

data class PcMonitorInfo(
    val index: Int,
    val primary: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcMonitorInfo(
            index = o.optInt("index"),
            primary = o.optBoolean("primary"),
            x = o.optInt("x"),
            y = o.optInt("y"),
            width = o.optInt("width"),
            height = o.optInt("height"),
        )
    }
}

data class PcScreenFrame(
    val hostId: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val via: String,
    val monitors: List<PcMonitorInfo> = emptyList(),
    val capturedAtMs: Long = System.currentTimeMillis(),
)

data class PcDiscoveredHost(
    val address: String,
    val port: Int,
    val name: String,
    val hostname: String,
    val os: String,
    val arch: String,
    val tls: Boolean,
    val tlsFingerprint: String,
    val pairId: String,
    val permissionLevel: String,
    val mac: String,
    val engine: String,
    val version: String,
) {
    fun toDraft(token: String = "") = PcHostDraft(
        name = name.ifBlank { hostname.ifBlank { address } },
        os = os.ifBlank { "auto" },
        transport = "lan",
        address = address,
        port = port,
        tls = tls,
        tlsFingerprint = tlsFingerprint,
        mac = mac,
        permissionLevel = permissionLevel.ifBlank { "operate" },
        token = token,
        setDefault = true,
    )

    companion object {
        fun fromJson(o: JSONObject) = PcDiscoveredHost(
            address = o.optString("address"),
            port = o.optInt("port", 29840),
            name = o.optString("name"),
            hostname = o.optString("hostname"),
            os = o.optString("os", "auto"),
            arch = o.optString("arch"),
            tls = o.optBoolean("tls"),
            tlsFingerprint = o.optString("tlsFingerprint"),
            pairId = o.optString("pairId"),
            permissionLevel = o.optString("permissionLevel", "operate"),
            mac = o.optString("mac"),
            engine = o.optString("engine", "native-go"),
            version = o.optString("version"),
        )
    }
}

data class PcBuildTarget(
    val os: String,
    val arch: String,
    val label: String,
    val filename: String,
    val path: String,
    val built: Boolean,
    val size: Long,
    val sha256: String,
    val builtAt: Long,
    val upToDate: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcBuildTarget(
            os = o.optString("os"),
            arch = o.optString("arch"),
            label = o.optString("label"),
            filename = o.optString("filename"),
            path = o.optString("path"),
            built = o.optBoolean("built"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256"),
            builtAt = o.optLong("builtAt"),
            upToDate = o.optBoolean("upToDate"),
        )
    }
}

data class PcBuildStatus(
    val toolchainInstalled: Boolean,
    val goVersion: String,
    val goBin: String,
    val sourceReady: Boolean,
    val sourceHash: String,
    val targets: List<PcBuildTarget>,
) {
    companion object {
        fun fromJson(o: JSONObject): PcBuildStatus {
            val tc = o.optJSONObject("toolchain") ?: JSONObject()
            return PcBuildStatus(
                toolchainInstalled = tc.optBoolean("installed"),
                goVersion = tc.optString("goVersion"),
                goBin = tc.optString("goBin"),
                sourceReady = o.optBoolean("sourceReady"),
                sourceHash = o.optString("sourceHash"),
                targets = o.arr("targets").objects().map(PcBuildTarget::fromJson),
            )
        }
    }
}

data class PcBuildProgress(
    val stage: String,
    val percent: Int,
    val message: String,
    val target: String = "",
)

data class PcRemoteFileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcRemoteFileEntry(
            name = o.optString("name"),
            path = o.optString("path"),
            isDir = o.optBoolean("isDir", o.optString("type") == "dir"),
            size = o.optLong("size"),
            mtime = o.optLong("mtime", o.optLong("mtimeMs")),
        )
    }
}

data class PcProcessItem(
    val pid: Int,
    val name: String,
    val cpu: Double,
    val memPct: Double,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcProcessItem(
            pid = o.optInt("pid"),
            name = o.optString("name"),
            cpu = o.optDouble("cpu", 0.0),
            memPct = o.optDouble("memPct", 0.0),
        )
    }
}

data class PcSystemInfo(
    val hostname: String,
    val platform: String,
    val arch: String,
    val cpus: Int,
    val user: String,
    val engine: String,
    val monitors: List<PcMonitorInfo>,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcSystemInfo(
            hostname = o.optString("hostname"),
            platform = o.optString("platform"),
            arch = o.optString("arch"),
            cpus = o.optInt("cpus"),
            user = o.optString("user"),
            engine = o.optString("engine"),
            monitors = o.arr("monitors").objects().map(PcMonitorInfo::fromJson),
        )
    }
}

data class PcWindowItem(
    val hwnd: String,
    val title: String,
    val process: String,
    val pid: Int,
    val active: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcWindowItem(
            hwnd = o.optString("hwnd"),
            title = o.optString("title"),
            process = o.optString("process"),
            pid = o.optInt("pid"),
            active = o.optBoolean("active"),
            x = o.optInt("x"),
            y = o.optInt("y"),
            width = o.optInt("width"),
            height = o.optInt("height"),
        )
    }
}

data class PcAuditEntry(
    val ts: Long,
    val action: String,
    val hostId: String,
    val via: String,
    val ok: Boolean,
    val blocked: String,
    val reason: String,
    val error: String,
    val ms: Int,
) {
    companion object {
        fun fromJson(o: JSONObject) = PcAuditEntry(
            ts = o.optLong("ts"),
            action = o.optString("action"),
            hostId = o.optString("hostId"),
            via = o.optString("via"),
            ok = o.optBoolean("ok", o.optString("blocked").isBlank() && o.optString("error").isBlank()),
            blocked = o.optString("blocked"),
            reason = o.optString("reason"),
            error = o.optString("error"),
            ms = o.optInt("ms"),
        )
    }
}

data class PcHighRiskApproval(
    val id: String,
    val hostId: String,
    val hostName: String,
    val action: String,
    val subAction: String,
    val reason: String,
    val summary: String,
)

/** Computer Use Android 原生状态仓库（chroot / PRoot 通用，经 Agent Hub RPC 驱动 computer-use-hub.mjs）。 */
class ComputerUseRepository(
    private val runtime: RuntimeClient,
    private val onNotify: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshMutex = Mutex()
    private val approvalMutex = Mutex()
    private val approvalWaiters = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val hostsFlow = MutableStateFlow<List<PcHostInfo>>(emptyList())
    val hosts: StateFlow<List<PcHostInfo>> = hostsFlow.asStateFlow()

    private val defaultHostIdFlow = MutableStateFlow("")
    val defaultHostId: StateFlow<String> = defaultHostIdFlow.asStateFlow()

    private val emergencyStopFlow = MutableStateFlow(false)
    val emergencyStop: StateFlow<Boolean> = emergencyStopFlow.asStateFlow()

    private val pingsFlow = MutableStateFlow<Map<String, PcPingResult>>(emptyMap())
    val pings: StateFlow<Map<String, PcPingResult>> = pingsFlow.asStateFlow()

    private val auditFlow = MutableStateFlow<List<PcAuditEntry>>(emptyList())
    val auditEntries: StateFlow<List<PcAuditEntry>> = auditFlow.asStateFlow()

    private val liveFrameFlow = MutableStateFlow<PcScreenFrame?>(null)
    val liveFrame: StateFlow<PcScreenFrame?> = liveFrameFlow.asStateFlow()

    private val pendingApprovalFlow = MutableStateFlow<PcHighRiskApproval?>(null)
    val pendingApproval: StateFlow<PcHighRiskApproval?> = pendingApprovalFlow.asStateFlow()

    private val discoveredFlow = MutableStateFlow<List<PcDiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<PcDiscoveredHost>> = discoveredFlow.asStateFlow()

    private val buildStatusFlow = MutableStateFlow<PcBuildStatus?>(null)
    val buildStatus: StateFlow<PcBuildStatus?> = buildStatusFlow.asStateFlow()

    private val buildProgressFlow = MutableStateFlow<PcBuildProgress?>(null)
    val buildProgress: StateFlow<PcBuildProgress?> = buildProgressFlow.asStateFlow()

    fun onBuildProgress(o: JSONObject) {
        buildProgressFlow.value = PcBuildProgress(
            stage = o.optString("stage"),
            percent = o.optInt("percent"),
            message = o.optString("message"),
            target = o.optString("target"),
        )
    }

    fun clearBuildProgress() {
        buildProgressFlow.value = null
    }

    suspend fun refreshHosts() = refreshMutex.withLock {
        if (!runtime.hubAvailable) return@withLock
        val res = runtime.hubRequest("computerUse/hosts", jsonOf("action" to "list"), timeoutMs = 12_000)
        hostsFlow.value = res.arr("hosts").objects().map(PcHostInfo::fromJson)
        defaultHostIdFlow.value = res.optString("defaultHostId")
        acceptEmergencyStop(res.optBoolean("emergencyStop", false))
    }

    fun refreshHostsAsync() {
        scope.launch { runCatching { refreshHosts() } }
    }

    suspend fun saveHost(draft: PcHostDraft, isUpdate: Boolean = false) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", draft.toRpcParams(if (isUpdate) "update" else "add"), timeoutMs = 12_000)
        refreshHosts()
        onNotify("已保存电脑主机：${draft.name.ifBlank { draft.id }}")
    }

    suspend fun removeHost(hostId: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to "remove", "hostId" to hostId), timeoutMs = 12_000)
        pingsFlow.value = pingsFlow.value - hostId
        if (liveFrameFlow.value?.hostId == hostId) liveFrameFlow.value = null
        refreshHosts()
        onNotify("已移除电脑主机")
    }

    suspend fun setDefaultHost(hostId: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to "set_default", "hostId" to hostId), timeoutMs = 10_000)
        refreshHosts()
    }

    suspend fun setPermission(hostId: String, level: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to "set_permission", "hostId" to hostId, "permissionLevel" to level), timeoutMs = 10_000)
        refreshHosts()
        val label = when (level) { "observe" -> "只读观察"; "admin" -> "完全管理"; else -> "常规操控" }
        onNotify("已切换权限档位：$label")
    }

    suspend fun setEmergencyStop(enabled: Boolean) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to "emergency_stop", "enabled" to enabled), timeoutMs = 10_000)
        acceptEmergencyStop(enabled)
        onNotify(if (enabled) "电脑操控已停止" else "电脑操控已恢复")
    }

    fun acceptEmergencyStop(enabled: Boolean) {
        emergencyStopFlow.value = enabled
        if (enabled) {
            approvalWaiters.values.toList().forEach { it.complete(false) }
            approvalWaiters.clear()
            pendingApprovalFlow.value = null
        }
    }

    suspend fun toggleSshTunnel(hostId: String, start: Boolean) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val act = if (start) "ssh_tunnel_start" else "ssh_tunnel_stop"
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to act, "hostId" to hostId), timeoutMs = 15_000)
        refreshHosts()
        onNotify(if (start) "SSH 端口转发隧道已启动" else "SSH 隧道已停止")
    }

    suspend fun pingHost(hostId: String): PcPingResult {
        if (!runtime.hubAvailable) {
            val res = PcPingResult(ok = false, error = "运行环境未就绪")
            pingsFlow.value = pingsFlow.value + (hostId to res)
            return res
        }
        return try {
            val raw = runtime.hubRequest("computerUse/hosts", jsonOf("action" to "ping", "hostId" to hostId), timeoutMs = 15_000)
            val remote = raw.optJSONObject("remote") ?: JSONObject()
            val res = PcPingResult(
                ok = raw.optBoolean("ok", true),
                via = raw.optString("via"),
                latencyMs = raw.optInt("latencyMs"),
                platform = remote.optString("platform"),
                hostname = remote.optString("hostname"),
                user = remote.optString("user"),
                permissionLevel = remote.optString("permissionLevel"),
                emergencyStopActive = remote.optBoolean("emergencyStopActive"),
            )
            pingsFlow.value = pingsFlow.value + (hostId to res)
            runCatching { refreshHosts() }
            res
        } catch (e: Exception) {
            val res = PcPingResult(ok = false, error = e.message ?: "连通探测失败")
            pingsFlow.value = pingsFlow.value + (hostId to res)
            res
        }
    }

    suspend fun wakeHost(hostId: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("computerUse/execute", jsonOf("action" to "wake", "params" to jsonOf("hostId" to hostId)), timeoutMs = 10_000)
        onNotify("已发送 WoL 唤醒包 → ${res.optString("mac")}")
    }

    suspend fun refreshAudit(limit: Int = 60) {
        if (!runtime.hubAvailable) return
        val res = runtime.hubRequest("computerUse/hosts", jsonOf("action" to "audit", "limit" to limit), timeoutMs = 10_000)
        auditFlow.value = res.arr("entries").objects().map(PcAuditEntry::fromJson)
    }

    suspend fun captureScreenshot(hostId: String, maxWidth: Int = 1080, quality: Int = 70, monitor: Int? = null): PcScreenFrame {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val p = jsonOf("hostId" to hostId, "maxWidth" to maxWidth, "quality" to quality)
        if (monitor != null) p.put("monitor", monitor)
        val raw = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "screenshot", "params" to p),
            timeoutMs = 30_000,
        )
        val bytes = withContext(Dispatchers.Default) {
            val data = raw.optString("_mcpImageBase64").ifEmpty { raw.optString("imageBase64") }
            check(data.isNotBlank()) { "电脑未返回画面数据" }
            Base64.decode(data, Base64.DEFAULT)
        }
        val frame = PcScreenFrame(
            hostId = hostId,
            bytes = bytes,
            width = raw.optInt("width", 1280),
            height = raw.optInt("height", 720),
            screenWidth = raw.optInt("screenWidth", raw.optInt("width", 1920)),
            screenHeight = raw.optInt("screenHeight", raw.optInt("height", 1080)),
            via = raw.optString("via"),
            monitors = raw.arr("monitors").objects().map(PcMonitorInfo::fromJson),
        )
        liveFrameFlow.value = frame
        return frame
    }

    suspend fun mouseDragNormalized(hostId: String, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf(
                "action" to "mouse",
                "params" to jsonOf(
                    "hostId" to hostId,
                    "action" to "drag",
                    "x" to (fromX.coerceIn(0f, 1f) * 1000f).toInt(),
                    "y" to (fromY.coerceIn(0f, 1f) * 1000f).toInt(),
                    "toX" to (toX.coerceIn(0f, 1f) * 1000f).toInt(),
                    "toY" to (toY.coerceIn(0f, 1f) * 1000f).toInt(),
                    "normalized" to true,
                ),
            ),
            timeoutMs = 15_000,
        )
    }

    suspend fun discoverLanHosts(timeoutMs: Int = 1800): List<PcDiscoveredHost> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest("computerUse/hosts", jsonOf("action" to "discover_lan", "timeoutMs" to timeoutMs), timeoutMs = 10_000)
        val list = res.arr("hosts").objects().map(PcDiscoveredHost::fromJson)
        discoveredFlow.value = list
        return list
    }

    suspend fun refreshBuildStatus(): PcBuildStatus? {
        if (!runtime.hubAvailable) return null
        val res = runtime.hubRequest("computerUse/hosts", jsonOf("action" to "build_status"), timeoutMs = 15_000)
        return PcBuildStatus.fromJson(res).also { buildStatusFlow.value = it }
    }

    suspend fun installGoToolchain() {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest("computerUse/hosts", jsonOf("action" to "install_toolchain"), timeoutMs = 300_000)
        refreshBuildStatus()
        onNotify("Go 交叉编译工具链已安装就绪")
    }

    suspend fun buildNativeTargets(os: String = "", arch: String = "", all: Boolean = false, force: Boolean = false) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val p = jsonOf("action" to "build_native", "all" to all, "force" to force)
        if (os.isNotBlank()) p.put("os", os)
        if (arch.isNotBlank()) p.put("arch", arch)
        runtime.hubRequest("computerUse/hosts", p, timeoutMs = 300_000)
        refreshBuildStatus()
        onNotify(if (all) "全部 6 平台原生守护程序编译完成" else "已编译原生程序：$os/$arch")
    }

    suspend fun deployOverSsh(
        sshAlias: String,
        name: String = "",
        port: Int = 29840,
        permissionLevel: String = "operate",
        transport: String = "ssh",
        autoTls: Boolean = false,
        installService: Boolean = true,
    ): JSONObject {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/hosts",
            jsonOf(
                "action" to "deploy_ssh",
                "sshAlias" to sshAlias,
                "name" to name,
                "port" to port,
                "permissionLevel" to permissionLevel,
                "transport" to transport,
                "autoTls" to autoTls,
                "installService" to installService,
            ),
            timeoutMs = 360_000,
        )
        refreshHosts()
        refreshBuildStatus()
        val osArch = "${res.optString("remoteOs")}/${res.optString("remoteArch")}"
        onNotify("已自动编译安装原生程序至 $sshAlias ($osArch) 并完成配对")
        return res
    }

    suspend fun listRemoteFiles(hostId: String, remotePath: String = "~"): Pair<String, List<PcRemoteFileEntry>> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "files", "params" to jsonOf("hostId" to hostId, "action" to "list", "path" to remotePath)),
            timeoutMs = 20_000,
        )
        val entries = res.optJSONArray("entries") ?: res.optJSONArray("items") ?: error("电脑代理返回的文件列表无效")
        return res.optString("path", remotePath) to entries.objects().map(PcRemoteFileEntry::fromJson)
    }

    suspend fun pullRemoteFile(hostId: String, remotePath: String, localPath: String): Long {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "files", "params" to jsonOf("hostId" to hostId, "action" to "pull", "path" to remotePath, "localPath" to localPath)),
            timeoutMs = 60_000,
        )
        return res.optLong("bytes")
    }

    suspend fun pushRemoteFile(hostId: String, localPath: String, remotePath: String): Long {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "files", "params" to jsonOf("hostId" to hostId, "action" to "push", "localPath" to localPath, "path" to remotePath)),
            timeoutMs = 60_000,
        )
        return res.optLong("bytes")
    }

    suspend fun deleteRemoteFile(hostId: String, remotePath: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "files", "params" to jsonOf("hostId" to hostId, "action" to "delete", "path" to remotePath, "confirmHighRisk" to true)),
            timeoutMs = 20_000,
        )
    }

    suspend fun listProcesses(hostId: String, query: String = ""): List<PcProcessItem> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "process", "params" to jsonOf("hostId" to hostId, "action" to "list", "query" to query, "limit" to 60)),
            timeoutMs = 15_000,
        )
        return res.arr("processes").objects().map(PcProcessItem::fromJson)
    }

    suspend fun killProcess(hostId: String, pid: Int) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "process", "params" to jsonOf("hostId" to hostId, "action" to "kill", "pid" to pid, "confirmHighRisk" to true)),
            timeoutMs = 15_000,
        )
    }

    suspend fun getSystemInfo(hostId: String): PcSystemInfo {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "system", "params" to jsonOf("hostId" to hostId, "action" to "info")),
            timeoutMs = 15_000,
        )
        return PcSystemInfo.fromJson(res)
    }

    suspend fun setSystemVolume(hostId: String, level: Int) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "system", "params" to jsonOf("hostId" to hostId, "action" to "volume", "level" to level.coerceIn(0, 100))),
            timeoutMs = 12_000,
        )
    }

    suspend fun toggleSystemMute(hostId: String, muted: Boolean = true) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "system", "params" to jsonOf("hostId" to hostId, "action" to "mute", "muted" to muted)),
            timeoutMs = 12_000,
        )
    }

    suspend fun systemPower(hostId: String, powerAction: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "system", "params" to jsonOf("hostId" to hostId, "action" to powerAction, "confirmHighRisk" to true)),
            timeoutMs = 15_000,
        )
    }

    suspend fun mouseNormalized(hostId: String, action: String, normX: Float, normY: Float) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf(
                "action" to "mouse",
                "params" to jsonOf(
                    "hostId" to hostId,
                    "action" to action,
                    "x" to (normX.coerceIn(0f, 1f) * 1000f).toInt(),
                    "y" to (normY.coerceIn(0f, 1f) * 1000f).toInt(),
                    "normalized" to true,
                ),
            ),
            timeoutMs = 15_000,
        )
    }

    suspend fun scrollDesktop(hostId: String, deltaY: Int) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "mouse", "params" to jsonOf("hostId" to hostId, "action" to "scroll", "deltaY" to deltaY)),
            timeoutMs = 12_000,
        )
    }

    suspend fun sendText(hostId: String, text: String, pressEnter: Boolean = false) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf(
                "action" to "keyboard",
                "params" to jsonOf("hostId" to hostId, "action" to "type", "text" to text, "pressEnter" to pressEnter),
            ),
            timeoutMs = 15_000,
        )
    }

    suspend fun sendHotkey(hostId: String, keys: List<String>) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val params = if (keys.size == 1) {
            jsonOf("hostId" to hostId, "action" to "key", "key" to keys.first())
        } else {
            jsonOf("hostId" to hostId, "action" to "hotkey", "keys" to JSONArray(keys))
        }
        runtime.hubRequest("computerUse/execute", jsonOf("action" to "keyboard", "params" to params), timeoutMs = 15_000)
    }

    suspend fun listWindows(hostId: String): List<PcWindowItem> {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "window", "params" to jsonOf("hostId" to hostId, "action" to "list")),
            timeoutMs = 15_000,
        )
        return res.arr("windows").objects().map(PcWindowItem::fromJson)
    }

    suspend fun controlWindow(hostId: String, action: String, hwnd: String, title: String = "") {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "window", "params" to jsonOf("hostId" to hostId, "action" to action, "hwnd" to hwnd, "title" to title)),
            timeoutMs = 15_000,
        )
    }

    suspend fun getRemoteClipboard(hostId: String): String {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        val res = runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "clipboard", "params" to jsonOf("hostId" to hostId, "action" to "get")),
            timeoutMs = 12_000,
        )
        return res.optString("text")
    }

    suspend fun setRemoteClipboard(hostId: String, text: String) {
        check(runtime.hubAvailable) { "请先启动本地运行环境" }
        runtime.hubRequest(
            "computerUse/execute",
            jsonOf("action" to "clipboard", "params" to jsonOf("hostId" to hostId, "action" to "set", "text" to text)),
            timeoutMs = 12_000,
        )
    }

    /** 接收 Agent Hub 的高危电脑操作反向审批请求，弹出全局原生审批弹窗并等待用户决策。 */
    suspend fun requestHighRiskApproval(params: JSONObject): JSONObject = approvalMutex.withLock {
        if (emergencyStopFlow.value) return@withLock JSONObject().put("approved", false)
        val req = PcHighRiskApproval(
            id = UUID.randomUUID().toString(),
            hostId = params.optString("hostId"),
            hostName = params.optString("hostName", params.optString("hostId", "远程电脑")),
            action = params.optString("action"),
            subAction = params.optString("subAction"),
            reason = params.optString("reason", "检测到高危电脑操控请求"),
            summary = params.optString("summary"),
        )
        val deferred = CompletableDeferred<Boolean>()
        approvalWaiters[req.id] = deferred
        pendingApprovalFlow.value = req
        try {
            val approved = withTimeoutOrNull(60_000L) { deferred.await() } ?: false
            JSONObject().put("approved", approved)
        } finally {
            approvalWaiters.remove(req.id)
            if (pendingApprovalFlow.value?.id == req.id) pendingApprovalFlow.value = null
        }
    }

    fun resolveApproval(id: String, approved: Boolean) {
        approvalWaiters.remove(id)?.complete(approved)
        if (pendingApprovalFlow.value?.id == id) pendingApprovalFlow.value = null
    }
}
