package com.galiais.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.system.Os
import com.galiais.agent.engine.RUNTIME_MODE_PROOT
import com.galiais.agent.engine.RUNTIME_MODE_ROOT
import com.galiais.agent.engine.WorkspaceArchive
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地运行环境前台服务（支持 Root 原生 chroot 优先 + 免 Root PRoot 双模式，含实时配置进度上报）。
 */
class VmRuntimeService : Service() {
    companion object {
        const val START = "com.galiais.agent.START_RUNTIME"
        const val STOP = "com.galiais.agent.STOP_RUNTIME"
        const val RESTART = "com.galiais.agent.RESTART_RUNTIME"
        const val MAINTAIN = "com.galiais.agent.MAINTAIN_RUNTIME"
        const val APPROVE_REQ = "com.galiais.agent.APPROVE_REQ"
        const val DENY_REQ = "com.galiais.agent.DENY_REQ"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_DELETE_CACHE = "deleteCache"
        const val EXTRA_RESTART = "restart"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESTART_UPDATED_RUNTIME = "restartUpdatedRuntime"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_REQ_ID = "reqId"
        const val OP_RESET_VM = "reset-vm"
        const val OP_DELETE_ALL = "delete-all"
        const val OP_RESTORE_LEGACY = "restore-legacy-backup"
        const val APPROVAL_CHANNEL = "agent-approvals"
        const val APPROVAL_NOTIFICATION = 3
        private const val CHANNEL = "linux-runtime"
        private const val NOTIFICATION = 2
        private const val RUNTIME_ASSETS = "galiais/runtime"
        private const val MAXIMUM_EVENTS = 4096
        private const val MAXIMUM_EVENT_BYTES = 8 * 1024 * 1024
        private const val GUEST_NODE = "/opt/galiais-codex/usr/lib/chatgpt/resources/node"
        private const val GUEST_MUX = "/opt/galiais-codex/android/guest-mux.mjs"
    }
    inner class LocalBinder : Binder() { val runtime: VmRuntimeService get() = this@VmRuntimeService }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<(String) -> Unit>()
    private val events = ArrayDeque<String>()
    private val serverRequests = linkedMapOf<String, JSONObject>()
    private var serverRequestBytes = 0
    private var eventBytes = 0
    private var drainScheduled = false
    @Volatile private var command: ManagedProcess? = null
    @Volatile private var engine: CliRpcConnection? = null
    @Volatile private var claude: CliRpcConnection? = null
    @Volatile private var gateway: CliRpcConnection? = null
    @Volatile private var agentHub: CliRpcConnection? = null
    @Volatile private var busy = false
    @Volatile private var maintaining = false
    @Volatile private var pendingMaintenance: Intent? = null
    @Volatile private var pendingRestartMode: String? = null
    @Volatile private var resetBackupReady = false
    @Volatile var activeMode = RUNTIME_MODE_ROOT
        private set
    @Volatile var reattached = false
        private set
    @Volatile var firstTimeSetup = false
        private set
    @Volatile var progress: Float? = null
        private set
    @Volatile var stepIndex = 0
        private set
    @Volatile var stepTotal = 0
        private set
    @Volatile var stepLabel = ""
        private set
    @Volatile var state = "stopped"
        private set
    @Volatile var message = "运行环境已停止"
        private set
    @Volatile var engineInfo: String? = null
        private set
    @Volatile var codexVersion: String? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var turnWakeLock: PowerManager.WakeLock? = null
    @Volatile private var activeTurnRunning = false
    private var installControl = RunControl()
    private val processLock = Any()
    private data class QueuedSend(val lockId: String, var sent: Boolean = false)
    private val queuedSends = linkedMapOf<String, QueuedSend>()

    private val settings get() = (application as GaliaisApplication).settings.state.value
    private fun linuxRoot(mode: String = activeMode) = File(filesDir, if (mode == RUNTIME_MODE_PROOT) "linux-proot" else "linux")
    private fun runScript(mode: String = activeMode) = if (mode == RUNTIME_MODE_PROOT) "run-proot.sh" else "run-linux.sh"
    private fun prepareScript(mode: String = activeMode) = if (mode == RUNTIME_MODE_PROOT) "prepare-proot-runtime.sh" else "prepare-vm-runtime.sh"

    private fun writeNativeLibMarker() {
        val partial = File(filesDir, "native-lib.path.part")
        partial.writeText(applicationInfo.nativeLibraryDir + "\n", Charsets.UTF_8)
        partial.renameTo(File(filesDir, "native-lib.path"))
    }

    private fun sendIdentity(params: JSONObject): Pair<String, String> {
        val conversation = params.getString("conversationId")
        val message = params.getString("messageId")
        val lock = params.getString("lockId")
        require(listOf(conversation, message, lock).all { it.isNotBlank() && it.length <= 200 && !it.contains('\u0000') }) { "队列发送标识无效" }
        return JSONObject().put("conversation", conversation).put("message", message).toString() to lock
    }
    fun acquireQueuedSend(params: JSONObject): JSONObject = synchronized(queuedSends) {
        val (key, lock) = sendIdentity(params)
        val previous = queuedSends[key]
        if (previous != null) return@synchronized JSONObject().put("acquired", !previous.sent && previous.lockId == lock)
        check(queuedSends.values.count { !it.sent } < 64) { "待确认的队列发送过多，请先核对运行中的任务" }
        if (queuedSends.size >= 1024) queuedSends.entries.firstOrNull { it.value.sent }?.let { queuedSends.remove(it.key) }
        queuedSends[key] = QueuedSend(lock)
        JSONObject().put("acquired", true)
    }
    fun releaseQueuedSend(params: JSONObject) = synchronized(queuedSends) {
        val (key, lock) = sendIdentity(params)
        val current = queuedSends[key] ?: error("队列发送锁已失效")
        check(current.lockId == lock && !current.sent) { "队列发送锁不属于此操作" }
        if (params.getBoolean("sent")) current.sent = true else queuedSends.remove(key)
        Unit
    }

    override fun onCreate() {
        super.onCreate()
        activeMode = settings.effectiveRuntimeMode
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "本地 Linux 运行环境", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { requestStop(); return START_NOT_STICKY }
            RESTART -> { requestRestart(intent.getStringExtra(EXTRA_MODE) ?: settings.effectiveRuntimeMode); return START_NOT_STICKY }
            MAINTAIN -> { requestMaintenance(intent); return START_NOT_STICKY }
            APPROVE_REQ, DENY_REQ -> {
                val eng = intent.getStringExtra(EXTRA_ENGINE)
                val reqId = intent.getStringExtra(EXTRA_REQ_ID)
                if (!eng.isNullOrBlank() && !reqId.isNullOrBlank()) {
                    (application as GaliaisApplication).conversations.resolveFromNotification(eng, reqId, intent.action == APPROVE_REQ)
                }
                if (!busy) stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent == null) { if (!busy) stopSelf(); return START_NOT_STICKY }
        if (busy) {
            startForeground(NOTIFICATION, notification(message))
            return START_NOT_STICKY
        }
        beginRun(intent.getStringExtra(EXTRA_MODE) ?: settings.effectiveRuntimeMode)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (busy && !maintaining) {
            if (!settings.keepRuntimeOnExit) stopRuntime()
            else if (state == "ready") update("ready", "后台运行中，可从通知栏返回", progress = 1f)
        } else if (!busy) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun beginRun(mode: String) {
        busy = true
        resetBackupReady = false
        activeMode = mode
        reattached = false
        val linux = LinuxRuntime(this, mode)
        firstTimeSetup = !linux.ready || !linux.vmProvisioned
        stepTotal = if (firstTimeSetup) 3 else 2
        stepIndex = 1
        stepLabel = if (firstTimeSetup) "安装 Linux 系统" else "检查运行环境"
        progress = 0.04f
        cancelled.set(false); stopping.set(false)
        installControl = RunControl()
        startForeground(NOTIFICATION, notification(if (firstTimeSetup) "正在安装运行环境" else "正在准备本地运行环境"))
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GALIAIS:runtime").apply { acquire(30 * 60 * 1000L) }
        }
        worker.execute {
            try { runtimeMain(mode) }
            catch (error: Exception) { if (!stopping.get()) fail(error.message ?: "无法启动本地运行环境") }
        }
    }

    private fun runtimeMain(mode: String) {
        writeNativeLibMarker()
        if (mode == RUNTIME_MODE_PROOT) {
            check(LinuxRuntime.prootBinariesPresent(this)) { "安装包缺少内置 PRoot 用户态引擎（libproot.so）" }
        }
        update("preparing", if (firstTimeSetup) "正在准备安装文件" else "正在检查本地运行环境",
            progress = if (firstTimeSetup) 0.05f else 0.18f, step = 1, label = if (firstTimeSetup) "安装 Linux 系统" else "检查运行环境")
        copyRuntimeAssets()
        val linux = LinuxRuntime(this, mode)
        if (linux.ready && linux.vmProvisioned && (daemonPresent(mode) || linux.hasAnyMuxArtifacts())) {
            syncHotRuntimeScripts(mode)
            if (daemonPresent(mode) && linux.daemonGenerationMatches(applicationInfo.nativeLibraryDir)) {
                reattached = true
                update("starting-engine", "正在重新连接后台引擎", progress = 0.68f, step = 2, label = "连接引擎")
                startEngine(mode)
                return
            }
            // APK 覆盖安装升级后 nativeLibraryDir 已变更，旧后台引擎已无法继续服务新版本，自动无缝重启并刷新组件。
            update("preparing", "检测到新版本，正在自动重启引擎加载新组件", progress = 0.25f, step = 1, label = "更新运行组件")
            runCatching { stopMuxDaemons(mode) }.onFailure {
                // 即使单通道异常也继续校验是否已停稳，若仍有旧代残留则抛出明确错误
                if (daemonPresent(mode)) throw it
            }
            check(!cancelled.get()) { "已取消运行环境更新" }
        }
        prepare(linux)
        startEngine(mode)
    }

    fun observe(callback: (String) -> Unit): AutoCloseable {
        observers.add(callback)
        callback(snapshot())
        synchronized(serverRequests) { serverRequests.values.map { it.toString() } }.forEach(callback)
        return AutoCloseable { observers.remove(callback) }
    }
    private fun connection(name: String): CliRpcConnection = when (name) {
        "codex" -> engine ?: error("Codex 连接已断开")
        "claude" -> claude ?: error("Claude Code 连接已断开")
        "gateway" -> gateway ?: error("本地协议网关已断开")
        "agent-hub" -> agentHub ?: error("Agent 服务已断开")
        else -> error("未知编码引擎：$name")
    }
    fun request(method: String, params: JSONObject? = null, engineName: String = "codex", timeoutMs: Long = 120_000): CompletableFuture<Any?> {
        check(state == "ready") { "引擎尚未连接，请先启动运行环境" }
        return connection(engineName).request(method, params, timeoutMs)
    }
    fun respond(id: Any, result: Any?, engineName: String = "codex") {
        synchronized(serverRequests) {
            check(serverRequests.containsKey("$engineName:$id")) { "审批请求已失效；未发送决定" }
            connection(engineName).respond(id, result)
            removeServerRequest(id, engineName)
        }
    }
    fun reject(id: Any, code: Int, reason: String, engineName: String = "codex") {
        synchronized(serverRequests) {
            check(serverRequests.containsKey("$engineName:$id")) { "审批请求已失效；未发送决定" }
            connection(engineName).reject(id, code, reason)
            removeServerRequest(id, engineName)
        }
    }
    private fun removeServerRequest(id: Any, engineName: String) {
        serverRequests.remove("$engineName:$id")?.let { serverRequestBytes -= it.toString().toByteArray(Charsets.UTF_8).size }
    }
    private fun engineEvent(engineName: String, event: JSONObject) {
        val packet = JSONObject().put("kind", "engine-event").put("engine", engineName).put("data", event)
        if (engineName in setOf("claude", "agent-hub") && event.optString("method") == "galiais/requestResolved") synchronized(serverRequests) {
            removeServerRequest(event.getJSONObject("params").get("id"), engineName)
        }
        if (engineName == "codex" && event.optString("method") == "serverRequest/resolved") synchronized(serverRequests) {
            removeServerRequest(event.getJSONObject("params").get("requestId"), engineName)
        }
        if (event.has("method") && event.has("id")) synchronized(serverRequests) {
            val size = packet.toString().toByteArray(Charsets.UTF_8).size
            check(serverRequests.size < 64 && serverRequestBytes + size <= 8 * 1024 * 1024) { "未处理的引擎审批请求超过限制；请重新连接并核对任务状态" }
            val key = "$engineName:${event.get("id")}"
            check(!serverRequests.containsKey(key)) { "引擎重复发送同一审批请求" }
            serverRequests[key] = packet
            serverRequestBytes += size
        }
        emit(packet.toString())
    }
    private fun snapshot() = JSONObject().put("kind", "runtime-state").put("state", state).put("message", message)
        .put("mode", activeMode)
        .put("reattached", reattached)
        .put("firstTimeSetup", firstTimeSetup)
        .put("stepIndex", stepIndex)
        .put("stepTotal", stepTotal)
        .put("stepLabel", stepLabel)
        .put("progress", progress?.toDouble() ?: JSONObject.NULL)
        .put("engineInfo", engineInfo?.let(::JSONObject) ?: JSONObject.NULL).toString()

    private fun update(next: String, text: String, progress: Float? = this.progress, step: Int = this.stepIndex, label: String = this.stepLabel) {
        state = next
        message = text
        this.progress = progress?.coerceIn(0f, 1f)
        this.stepIndex = step
        this.stepLabel = label
        val notifyText = if (progress != null && next in setOf("preparing", "downloading", "starting-vm", "starting-engine"))
            "${(progress.coerceIn(0f, 1f) * 100).toInt()}% · $text" else text
        main.post { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(notifyText)) }
        emit(snapshot())
    }
    private fun emit(packet: String) {
        if (observers.isEmpty()) return
        var overflow = false
        synchronized(events) {
            val size = packet.toByteArray(Charsets.UTF_8).size
            if (events.size >= MAXIMUM_EVENTS || eventBytes + size > MAXIMUM_EVENT_BYTES) overflow = true
            else {
                events.addLast(packet); eventBytes += size
                if (!drainScheduled) {
                    drainScheduled = true
                    val drain = object : Runnable {
                        override fun run() {
                            var processed = 0
                            while (processed < 64) {
                                val next = synchronized(events) {
                                    if (events.isEmpty()) { drainScheduled = false; null }
                                    else events.removeFirst().also { eventBytes -= it.toByteArray(Charsets.UTF_8).size }
                                } ?: return
                                observers.forEach { it(next) }
                                processed++
                            }
                            val hasMore = synchronized(events) {
                                if (events.isEmpty()) { drainScheduled = false; false } else true
                            }
                            if (hasMore) main.post(this)
                        }
                    }
                    main.post(drain)
                }
            }
        }
        if (overflow) fail("界面未及时接收引擎消息，连接已停止；任务结果以引擎记录为准")
    }

    private fun copyRuntimeAssets() {
        val directory = File(filesDir, "vm-runtime")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建平台程序目录" }
        val runtimeAssets = assets.list(RUNTIME_ASSETS)?.sorted().orEmpty()
        check("prepare-vm-runtime.sh" in runtimeAssets && "run-linux.sh" in runtimeAssets && "run-proot.sh" in runtimeAssets) { "安装包缺少运行环境平台程序" }
        for (name in runtimeAssets) {
            check(!cancelled.get()) { "已取消准备运行环境" }
            val target = File(directory, name)
            val assetBytes = assets.open("$RUNTIME_ASSETS/$name").use { it.readBytes() }
            if (target.isFile && target.length() == assetBytes.size.toLong() && runCatching { target.readBytes().contentEquals(assetBytes) }.getOrDefault(false)) {
                continue
            }
            val partial = File(directory, "$name.part")
            partial.outputStream().use { output -> output.write(assetBytes); output.fd.sync() }
            check(partial.renameTo(target)) { "无法更新平台程序：$name" }
        }
    }

    private fun prepare(linux: LinuxRuntime) {
        val mode = linux.mode
        val isFirst = firstTimeSetup
        if (!linux.ready) {
            linux.install(installControl) { frac, msg ->
                update("preparing", msg, progress = 0.05f + frac * 0.48f, step = 1, label = "安装 Linux 系统")
            }
        } else if (isFirst) {
            update("preparing", "Linux 系统已就绪", progress = 0.53f, step = 1, label = "安装 Linux 系统")
        }
        check(filesDir.usableSpace >= 1_000_000_000L || linux.vmProvisioned) { "本地 Linux 运行环境至少需要 1 GB 可用空间" }
        val directory = File(filesDir, "vm-runtime")
        val prepStep = if (isFirst) 2 else 1
        val prepLabel = if (isFirst) "准备运行组件" else "检查运行环境"
        update("preparing", "正在准备 Codex、Claude Code 和 Node.js",
            progress = if (isFirst) 0.55f else 0.35f, step = prepStep, label = prepLabel)
        if (mode == RUNTIME_MODE_PROOT) {
            linux.prepareProotPayloads(directory, installControl)
        }
        val setup = synchronized(processLock) {
            check(!cancelled.get()) { "已取消组件安装" }
            launchPlatformProcess(this, mode, File(directory, prepareScript(mode)), emptyList()).also { command = it }
        }
        try {
            val input = setup.process.inputStream.bufferedReader(Charsets.UTF_8)
            setup.acknowledge(input)
            val error = CompletableFuture<String>()
            kotlin.concurrent.thread(isDaemon = true, name = "prepare-stderr") {
                try { error.complete(setup.process.errorStream.bufferedReader().use(::diagnosticTail)) } catch (failure: Exception) { error.completeExceptionally(failure) }
            }
            while (true) {
                val line = readLine(input) ?: break
                check(!cancelled.get()) { "已取消组件安装" }
                if (line.startsWith("GALIAIS_PROGRESS ")) {
                    val rest = line.removePrefix("GALIAIS_PROGRESS ").trim()
                    val split = rest.split(" ", limit = 2)
                    val pct = split[0].toIntOrNull()?.coerceIn(0, 100)
                    val msg = split.getOrNull(1)?.trim().orEmpty()
                    if (pct != null && msg.isNotEmpty()) {
                        val overall = if (isFirst) 0.55f + (pct / 100f) * 0.22f else 0.35f + (pct / 100f) * 0.25f
                        update("preparing", msg, progress = overall, step = prepStep, label = prepLabel)
                    }
                }
            }
            check(setup.process.waitFor(30, TimeUnit.SECONDS)) { "组件安装进程未正常退出" }
            check(setup.process.exitValue() == 0) { "组件安装失败：${error.get(5, TimeUnit.SECONDS)}" }
        } finally { synchronized(processLock) { if (command === setup) command = null }; setup.close() }
    }

    private data class RootResult(val code: Int, val output: String, val diagnostic: String)

    private fun runPlatform(mode: String, script: String, arguments: List<String>, timeoutSeconds: Long): RootResult {
        val managed = launchPlatformProcess(this, mode, File(filesDir, "vm-runtime/$script"), arguments)
        try {
            val input = managed.process.inputStream.bufferedReader(Charsets.UTF_8)
            managed.acknowledge(input)
            val output = CompletableFuture<String>()
            val diagnostic = CompletableFuture<String>()
            kotlin.concurrent.thread(isDaemon = true, name = "platform-stdout") {
                try { output.complete(diagnosticTail(input)) } catch (failure: Exception) { output.completeExceptionally(failure) }
            }
            kotlin.concurrent.thread(isDaemon = true, name = "platform-stderr") {
                try { diagnostic.complete(managed.process.errorStream.bufferedReader().use(::diagnosticTail)) } catch (failure: Exception) { diagnostic.completeExceptionally(failure) }
            }
            check(managed.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "平台脚本执行超时：$script" }
            return RootResult(managed.process.exitValue(),
                runCatching { output.get(5, TimeUnit.SECONDS) }.getOrDefault(""),
                runCatching { diagnostic.get(5, TimeUnit.SECONDS) }.getOrDefault(""))
        } finally { managed.close() }
    }

    private fun daemonPresent(mode: String = activeMode): Boolean =
        listOf("gateway", "codex", "claude", "agent-hub").any { channel ->
            val sockFile = File(linuxRoot(mode), "home/agent/.galiais/mux/$channel.sock")
            val exists = runCatching { Os.lstat(sockFile.absolutePath); true }.getOrDefault(false)
            if (!exists) return@any false
            try {
                android.net.LocalSocket().use { ls ->
                    ls.connect(android.net.LocalSocketAddress(sockFile.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                    true
                }
            } catch (e: java.io.IOException) {
                val msg = e.message.orEmpty()
                // Root 模式下 0600 套接字由 agent UID 持有，App UID 探活会报 EACCES；仅当明确 ECONNREFUSED/ENOENT 才认定为假死残留
                if (msg.contains("ECONNREFUSED", true) || msg.contains("ENOENT", true) || msg.contains("Connection refused", true)) {
                    false
                } else {
                    File(linuxRoot(mode), "home/agent/.galiais/mux/$channel.pid").isFile
                }
            }
        }

    private fun syncHotRuntimeScripts(mode: String) {
        val script = if (mode == RUNTIME_MODE_PROOT) "sync-hot-runtime-proot.sh" else "sync-hot-runtime.sh"
        val result = runPlatform(mode, script, emptyList(), 20)
        check(result.code == 0) { "更新运行组件失败：${result.diagnostic.ifBlank { result.output }}" }
    }

    private fun stopMuxDaemons(mode: String) {
        writeNativeLibMarker()
        if (!File(filesDir, "vm-runtime/${runScript(mode)}").isFile) copyRuntimeAssets()
        val result = runPlatform(mode, runScript(mode), listOf(GUEST_NODE, GUEST_MUX, "--stop-all"), 30)
        check(result.code == 0) { "未能停止后台引擎：${result.diagnostic.ifBlank { result.output }}" }
    }

    fun setActiveTurnRunning(active: Boolean) {
        main.post {
            if (destroyed.get()) return@post
            activeTurnRunning = active
            if (active && state == "ready") {
                try {
                    val wl = turnWakeLock ?: getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GALIAIS:active-turn")
                        .also { it.setReferenceCounted(false); turnWakeLock = it }
                    // 单次上限 25 分钟，防止异常泄露；任务活跃期间保护后台网络不被 Android Doze/netd 阻断（EPERM）
                    wl.acquire(25 * 60 * 1000L)
                } catch (_: Exception) {}
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("Agent 任务执行中（后台网络保活）"))
            } else {
                try { turnWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                turnWakeLock = null
                if (state == "ready") {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
                }
            }
        }
    }

    fun applyKeepAlive(keep: Boolean) {
        if (state != "ready") return
        val connections = listOfNotNull(engine, claude, gateway, agentHub)
        worker.execute {
            try {
                connections.forEach { it.request("galiais/keepAlive", JSONObject().put("keep", keep), 5000).get(6, TimeUnit.SECONDS) }
                main.post { if (state == "ready") update("ready", if (keep) "后台运行已开启" else "后台运行已关闭，断开连接后将停止引擎", progress = 1f) }
            } catch (error: Exception) {
                main.post { if (state == "ready") update("ready", "后台设置应用失败，请重启运行环境：${error.message}", progress = 1f) }
            }
        }
    }

    private fun readLine(input: Reader): String? {
        val line = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.isEmpty()) null else error("运行环境状态消息被截断")
            if (value == 10) return line.toString()
            check(line.length < 65536) { "运行环境状态消息过长" }
            line.append(value.toChar())
        }
    }
    private fun diagnosticTail(input: Reader): String {
        val tail = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return tail.toString()
            tail.append(buffer, 0, count)
            if (tail.length > 16384) tail.delete(0, tail.length - 16384)
        }
    }
    private fun startEngine(mode: String, retriedFallback: Boolean = false) {
        if (stopping.get() || engine != null) return
        val engStep = if (firstTimeSetup) 3 else 2
        val proot = mode == RUNTIME_MODE_PROOT
        update("starting-engine", "正在启动服务商网关", progress = if (firstTimeSetup) 0.80f else 0.72f, step = engStep, label = "连接引擎")
        val gatewayConnection = synchronized(processLock) {
            if (stopping.get()) return
            CliRpcConnection(this, {}, { fail("协议网关：$it") },
                if (proot) "launch-gateway-proot.sh" else "launch-gateway.sh", mode).also { gateway = it }
        }
        var connecting = "协议网关"
        try {
            val gatewayInfo = gatewayConnection.start()
            connecting = "Codex"
            update("starting-engine", "正在连接 Codex", progress = if (firstTimeSetup) 0.87f else 0.82f, step = engStep, label = "连接引擎")
            val connection = synchronized(processLock) {
                if (stopping.get()) return
                CliRpcConnection(this, { engineEvent("codex", it) }, { fail("Codex：$it") },
                    if (proot) "launch-engine-proot.sh" else "launch-engine.sh", mode).also { engine = it }
            }
            val codexInfo = connection.start()
            codexVersion = codexInfo.optString("userAgent").substringAfter('/', "").substringBefore(' ').ifBlank { "0.171.0-alpha.13" }
            val restoredSnapshot = WorkspaceArchive.restoreAfterVmResetIfNeeded(this, connection, mode)
            connecting = "Claude Code"
            update("starting-engine", "正在连接 Claude Code", progress = if (firstTimeSetup) 0.94f else 0.92f, step = engStep, label = "连接引擎")
            val claudeConnection = synchronized(processLock) {
                if (stopping.get()) return
                CliRpcConnection(this, { engineEvent("claude", it) }, { fail("Claude Code：$it") },
                    if (proot) "launch-claude-proot.sh" else "launch-claude.sh", mode).also { claude = it }
            }
            val claudeInfo = claudeConnection.start()
            update("starting-engine", "正在启动 Agent 服务", progress = 0.98f, step = engStep, label = "连接 Agent 引擎")
            val hubInfo = try {
                val hubConnection = synchronized(processLock) {
                    if (stopping.get()) return
                    CliRpcConnection(this, { engineEvent("agent-hub", it) }, ::hubDisconnected,
                        if (proot) "launch-agent-hub-proot.sh" else "launch-agent-hub.sh", mode).also { agentHub = it }
                }
                hubConnection.start()
            } catch (_: Exception) {
                agentHub = null
                JSONObject().put("error", "unavailable")
            }
            engineInfo = JSONObject().put("codex", codexInfo).put("claude", claudeInfo).put("gateway", gatewayInfo).put("agentHub", hubInfo).toString()
            if (!stopping.get()) {
                // 启动与准备阶段完成，释放唤醒锁避免空闲后台 24/7 持续耗电
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                val modeTag = if (proot) "免 Root" else "Root"
                val readyText = when {
                    restoredSnapshot -> "$modeTag 环境已就绪，项目备份已恢复"
                    reattached -> "已连接 $modeTag 环境的后台引擎"
                    else -> "$modeTag 环境已就绪"
                }
                update("ready", readyText, progress = 1f, step = stepTotal, label = "已就绪")
            }
        } catch (error: Exception) {
            // 若因在线更新的 Codex/Claude 组件损坏导致启动失败，自动清理覆写标记回退内置稳定版本并立即自动重试，避免用户手动再点一次重试
            val failedId = when (connecting) { "Codex" -> "codex"; "Claude Code" -> "claude"; else -> null }
            val overrideActive = failedId?.let { File(linuxRoot(mode), "home/agent/.galiais/cli/$it/active.json") }
            if (!retriedFallback && !stopping.get() && overrideActive != null && overrideActive.isFile && overrideActive.delete()) {
                runCatching { agentHub?.close() }; agentHub = null
                runCatching { claude?.close() }; claude = null
                runCatching { engine?.close() }; engine = null
                runCatching { gateway?.close() }; gateway = null
                update("starting-engine", "检测到 $connecting 在线更新版异常，已自动回退内置稳定版并重试…", progress = 0.72f, step = engStep, label = "自动回退恢复")
                startEngine(mode, retriedFallback = true)
                return
            }
            if (!stopping.get()) fail("$connecting：${error.message ?: "引擎连接失败"}")
        }
    }
    private fun fail(reason: String) { stopRuntime(reason, killDaemons = false) }

    private fun hubDisconnected(reason: String) {
        main.post {
            if (agentHub == null || stopping.get()) return@post
            agentHub = null
            engineInfo = engineInfo?.let { JSONObject(it).put("agentHub", JSONObject().put("error", reason)).toString() }
            synchronized(serverRequests) {
                serverRequests.keys.filter { it.startsWith("agent-hub:") }.toList().forEach { key ->
                    serverRequests.remove(key)?.let { serverRequestBytes -= it.toString().toByteArray(Charsets.UTF_8).size }
                }
            }
            engineEvent("agent-hub", JSONObject().put("method", "hub/disconnected").put("params", JSONObject().put("message", reason)))
            update(state, "外部 Agent 服务已断开：$reason（可点击「重启环境」一键恢复）", progress = progress)
        }
    }

    private fun requestRestart(mode: String) {
        if (maintaining || state == "backing-up") return
        pendingMaintenance = null
        val targetTag = if (mode == RUNTIME_MODE_PROOT) "免 Root" else "Root"
        if (busy) {
            pendingRestartMode = mode
            val switchStatus = if (activeMode != mode) "正在停止旧环境并切换启动 $targetTag 模式" else "正在重启本地运行环境"
            stopRuntime(null, killDaemons = true, statusText = switchStatus)
            return
        }
        val otherMode = if (mode == RUNTIME_MODE_PROOT) RUNTIME_MODE_ROOT else RUNTIME_MODE_PROOT
        val modesToStop = buildList {
            if (daemonPresent(otherMode)) add(otherMode)
            if (daemonPresent(mode)) add(mode)
        }
        if (modesToStop.isNotEmpty()) {
            if (!stopping.compareAndSet(false, true)) return
            pendingRestartMode = mode
            busy = true
            val stopText = if (otherMode in modesToStop) "正在停止旧环境并切换启动 $targetTag 模式" else "正在重启本地运行环境"
            startForeground(NOTIFICATION, notification(stopText))
            update("stopping", stopText, progress = null)
            kotlin.concurrent.thread(name = "restart-detached-mux") {
                val errors = mutableListOf<String>()
                for (m in modesToStop) {
                    try { stopMuxDaemons(m) } catch (error: Exception) { errors += (error.message ?: "停止 $m 后台进程失败") }
                }
                val failure = errors.takeIf { it.isNotEmpty() }?.joinToString("；")
                main.post { finishStop(failure) }
            }
            return
        }
        pendingRestartMode = null
        beginRun(mode)
    }

    private fun requestStop() {
        if (maintaining || state == "backing-up") return
        pendingMaintenance = null
        pendingRestartMode = null
        if (busy) { stopRuntime(null, killDaemons = true); return }
        val mode = settings.effectiveRuntimeMode
        if (daemonPresent(mode)) {
            if (!stopping.compareAndSet(false, true)) return
            busy = true
            startForeground(NOTIFICATION, notification("正在停止后台引擎"))
            update("stopping", "正在停止后台引擎", progress = null)
            kotlin.concurrent.thread(name = "stop-detached-mux") {
                val failure = try { stopMuxDaemons(mode); null } catch (error: Exception) { error.message }
                main.post {
                    busy = false
                    stopping.set(false)
                    update(if (failure == null) "stopped" else "error", failure ?: "运行环境已停止", progress = null)
                    stopForeground(if (failure == null) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
                    if (failure == null) getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                    if (!busy) stopSelf()
                }
            }
        } else stopSelf()
    }

    private fun stopRuntime(error: String? = null, killDaemons: Boolean = error == null, statusText: String = "正在停止本地引擎") {
        if (!stopping.compareAndSet(false, true)) return
        synchronized(processLock) { cancelled.set(true) }
        installControl.cancel()
        val mode = activeMode
        update(if (error == null) "stopping" else "error", error ?: statusText, progress = null)
        Thread({
            var failure = error
            fun record(cleanup: Exception) { failure = (failure?.plus("；") ?: "") + cleanup.message }
            try { agentHub?.close() } catch (cleanup: Exception) { record(cleanup) }
            try { engine?.close() } catch (cleanup: Exception) { record(cleanup) }
            try { claude?.close() } catch (cleanup: Exception) { record(cleanup) }
            try { gateway?.close() } catch (cleanup: Exception) { record(cleanup) }
            agentHub = null
            gateway = null
            claude = null
            engine = null; engineInfo = null; codexVersion = null
            synchronized(serverRequests) { serverRequests.clear(); serverRequestBytes = 0 }
            try { command?.close() } catch (cleanup: Exception) { record(cleanup) }
            command = null
            if (killDaemons && daemonPresent(mode)) {
                try { stopMuxDaemons(mode) } catch (cleanup: Exception) { record(cleanup) }
            }
            if (destroyed.get()) { worker.shutdown() }
            main.post { finishStop(failure) }
        }, "stop-linux-runtime").start()
    }

    private fun finishStop(failure: String?) {
        busy = false
        stopping.set(false)
        reattached = false
        firstTimeSetup = false
        val queued = pendingMaintenance
        pendingMaintenance = null
        val restartMode = pendingRestartMode
        pendingRestartMode = null
        val incompleteReset = queued?.getStringExtra(EXTRA_OPERATION) == OP_RESET_VM && !resetBackupReady
        val finalFailure = failure ?: if (incompleteReset) "项目备份未完成，已取消重置" else null
        if (queued != null && finalFailure == null && !destroyed.get()) { runMaintenance(queued); return }
        if (restartMode != null && finalFailure == null && !destroyed.get()) {
            val targetTag = if (restartMode == RUNTIME_MODE_PROOT) "免 Root" else "Root"
            update("stopped", "旧环境已停止，正在自动准备 $targetTag 环境", progress = null, step = 0, label = "")
            beginRun(restartMode)
            return
        }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        turnWakeLock?.let { if (it.isHeld) it.release() }; turnWakeLock = null
        activeTurnRunning = false
        update(if (finalFailure == null) "stopped" else "error", finalFailure ?: "运行环境已停止", progress = null, step = 0, label = "")
        stopForeground(if (finalFailure == null) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun requestMaintenance(intent: Intent) {
        val operation = intent.getStringExtra(EXTRA_OPERATION)
        if (operation !in setOf(OP_RESET_VM, OP_DELETE_ALL, OP_RESTORE_LEGACY)) {
            if (!busy) stopSelf()
            return
        }
        if (maintaining || pendingMaintenance != null) return
        if (operation == OP_RESET_VM || operation == OP_RESTORE_LEGACY) {
            val requestedMode = intent.getStringExtra(EXTRA_MODE) ?: settings.effectiveRuntimeMode
            if (state != "ready" || engine == null || requestedMode != activeMode) {
                update(state, "请先启动所选模式的环境，完成项目备份后才能重置", progress = progress)
                if (!busy) stopSelf()
                return
            }
            val repo = (application as GaliaisApplication).conversations
            if (repo.conversations.value.values.any { it.running } || repo.requests.value.isNotEmpty()) {
                update("ready", "请先结束运行中的任务并处理审批，再重置工作区", progress = 1f)
                return
            }
        }
        startForeground(NOTIFICATION, notification("正在准备维护本地运行环境"))
        if (operation == OP_RESTORE_LEGACY) {
            val connection = engine ?: return
            val mode = activeMode
            maintaining = true
            update("backing-up", "正在恢复旧版项目备份，已有文件将保留", progress = null)
            worker.execute {
                val error = try { WorkspaceArchive.restoreLegacyBackup(this, connection, mode); null }
                    catch (failure: Exception) { failure.message ?: "恢复旧版备份失败" }
                main.post {
                    maintaining = false
                    if (destroyed.get()) stopRuntime("运行环境服务已结束", killDaemons = !settings.keepRuntimeOnExit)
                    else update("ready", error ?: "旧版项目备份已恢复到当前模式，已有文件保持不变", progress = 1f)
                }
            }
            return
        }
        if (busy) {
            pendingMaintenance = intent
            val activeCodex = if (operation == OP_RESET_VM && state == "ready") engine else null
            if (activeCodex != null) {
                resetBackupReady = false
                update("backing-up", "正在备份项目，完成后重置…", progress = null)
                Thread({
                    try {
                        WorkspaceArchive.backupBeforeVmReset(this, activeCodex, settings.projects, activeMode)
                        if (pendingMaintenance === intent) {
                            resetBackupReady = true
                            stopRuntime(null, killDaemons = true)
                        }
                    } catch (error: Exception) {
                        main.post {
                            if (pendingMaintenance === intent) {
                                pendingMaintenance = null
                                update("ready", "备份失败，已取消重置：${error.message}", progress = 1f)
                            }
                        }
                    }
                }, "backup-before-reset").start()
            } else {
                stopRuntime(null, killDaemons = true)
            }
        } else runMaintenance(intent)
    }

    private fun runMaintenance(intent: Intent) {
        val operation = intent.getStringExtra(EXTRA_OPERATION)
        val deleteCache = intent.getBooleanExtra(EXTRA_DELETE_CACHE, false)
        val restart = intent.getBooleanExtra(EXTRA_RESTART, false)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: settings.effectiveRuntimeMode
        activeMode = mode
        busy = true; maintaining = true; firstTimeSetup = false
        startForeground(NOTIFICATION, notification("正在维护本地运行环境"))
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GALIAIS:maintenance").apply { acquire(30 * 60 * 1000L) }
        }
        update("maintenance", if (operation == OP_RESET_VM) "正在重置工作区与会话记录" else "正在删除本地 Linux 环境", progress = null)
        worker.execute {
            var failure: String? = null
            try {
                val linux = LinuxRuntime(this, mode)
                if (linux.ready && daemonPresent(mode)) {
                    update("maintenance", "正在停止后台引擎", progress = null)
                    stopMuxDaemons(mode)
                }
                val control = RunControl()
                if (operation == OP_RESET_VM) {
                    check(resetBackupReady) { "项目备份未完成，已取消重置" }
                    resetBackupReady = false
                    check(linux.ready) { "尚未安装 Linux 环境，无需重置工作区" }
                    linux.resetVm(control) { update("maintenance", it, progress = null) }
                } else {
                    linux.deleteEnvironment(deleteCache, control) { update("maintenance", it, progress = null) }
                    File(filesDir, if (mode == RUNTIME_MODE_PROOT) "vm-proot-daemon.err" else "vm-daemon.err").delete()
                }
            } catch (error: Exception) { failure = error.message ?: "维护操作失败" }
            main.post {
                maintaining = false; busy = false
                if (failure == null && restart && !destroyed.get()) {
                    update("stopped", "维护完成，正在启动运行环境", progress = null)
                    beginRun(mode)
                    return@post
                }
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                val done = if (operation == OP_RESET_VM) "工作区与会话已重置，下次启动将恢复项目备份" else
                    "运行环境已删除，下次启动时将重新安装"
                update(if (failure == null) "stopped" else "error", failure ?: done, progress = null, step = 0, label = "")
                stopForeground(if (failure == null) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, VmRuntimeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notif_runtime)
            .setContentTitle(if (state == "ready") "Solbraid · 本地环境运行中" else "Solbraid 本地运行环境")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(busy).setShowWhen(false)
        val p = progress
        if (p != null && state in setOf("preparing", "downloading", "starting-vm", "starting-engine")) {
            builder.setProgress(100, (p.coerceIn(0f, 1f) * 100).toInt(), false)
        }
        if (!maintaining && busy) builder.addAction(Notification.Action.Builder(null, "停止运行环境", stop).build())
        return builder.build()
    }
    override fun onDestroy() {
        destroyed.set(true)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        turnWakeLock?.let { if (it.isHeld) it.release() }; turnWakeLock = null
        activeTurnRunning = false
        if (busy && !stopping.get() && !maintaining) stopRuntime("运行服务已结束，任务未自动重试", killDaemons = !settings.keepRuntimeOnExit)
        else if (!busy) { worker.shutdown() }
        observers.clear()
        super.onDestroy()
    }
}
