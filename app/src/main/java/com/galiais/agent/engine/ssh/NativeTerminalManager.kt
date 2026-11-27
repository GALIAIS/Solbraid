package com.galiais.agent.engine.ssh

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.galiais.agent.engine.RuntimeClient
import com.galiais.agent.engine.SshHostConfig
import com.galiais.agent.engine.jsonOf
import com.galiais.agent.engine.terminal.SecretRedactionMode
import com.galiais.agent.engine.terminal.SolbraidTerminalEngine
import com.galiais.agent.engine.terminal.TerminalAnsiUtils
import com.galiais.agent.engine.terminal.TerminalColorScheme
import com.galiais.agent.engine.terminal.TerminalCursorStyle
import com.galiais.agent.engine.terminal.TerminalMediaRequest
import com.galiais.agent.engine.terminal.TerminalProgressState
import com.galiais.agent.engine.terminal.TerminalThemes
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TermSessionStatus { CONNECTING, RUNNING, EXITED, ERROR }

/** 内嵌 Cascadia Mono NF 真等宽编程字体包（支持 Powerline / Oh My Posh / Starship / Nerd Font 图标）。 */
data class TerminalFontPack(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
) {
    companion object {
        fun load(context: Context): TerminalFontPack {
            val regular = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/CascadiaMonoNF-Regular.ttf")
            }.getOrNull() ?: loadSystemTrueMono()
            val bold = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/CascadiaMonoNF-Bold.ttf")
            }.getOrNull() ?: Typeface.create(regular, Typeface.BOLD)
            val italic = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/CascadiaMonoNF-Italic.ttf")
            }.getOrNull() ?: Typeface.create(regular, Typeface.ITALIC)
            val boldItalic = Typeface.create(bold, Typeface.BOLD_ITALIC)
            return TerminalFontPack(regular, bold, italic, boldItalic)
        }

        private fun loadSystemTrueMono(): Typeface {
            val candidates = listOf(
                "/system/fonts/DroidSansMono.ttf",
                "/system/fonts/RobotoMono-Regular.ttf",
                "/system/fonts/NotoSansMono-Regular.ttf",
            )
            return candidates.firstNotNullOfOrNull { p ->
                val f = File(p)
                if (f.isFile && f.canRead()) runCatching { Typeface.createFromFile(f) }.getOrNull() else null
            } ?: Typeface.MONOSPACE
        }
    }
}

/**
 * 应用级常驻原生终端会话（基于 100% 纯 Kotlin `SolbraidTerminalEngine`）：
 * - 不随 Compose 页面退出销毁，离开后切回 0ms 瞬时恢复；
 * - 独立维护 SSH `ChannelShell` 或本地 PTY 二进制 TCP 流，零 WebView、零 JNI、零 RPC 阻塞；
 * - 支持输入追踪，自动联动 PSReadLine 命令历史与语义命令块标记。
 */
class LiveTerminalSession(
    val id: String,
    val title: String,
    val cwd: String,
    val sshHost: SshHostConfig?,
    val remoteCwd: String,
    initialCols: Int,
    initialRows: Int,
    private val ioScope: CoroutineScope,
    private val schemeProvider: () -> TerminalColorScheme,
    private val onClipboardCopy: (String) -> Unit,
    private val onCommandRecorded: (String) -> Unit,
    private val onNotification: (title: String?, body: String) -> Unit,
    private val onStatusChanged: () -> Unit,
) {
    val isSsh: Boolean get() = sshHost != null
    val status = MutableStateFlow(TermSessionStatus.CONNECTING)
    val exitCode = MutableStateFlow<Int?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val dynamicTitle = MutableStateFlow(title)
    val bellCount = MutableStateFlow(0)
    val dimensions = MutableStateFlow(initialCols to initialRows)
    /** PowerShell 7 / Windows Terminal OSC 9;4 原生进度条状态。 */
    val progressState = MutableStateFlow(TerminalProgressState())
    /** 通过 OSC 7 / OSC 9;9 实时同步的当前工作目录。 */
    val liveCwd = MutableStateFlow(if (sshHost != null) remoteCwd.ifBlank { "~" } else cwd)
    /** 正在录制的 PowerShell Transcript 日志路径（非空即表示录制中）。 */
    val transcriptPath = MutableStateFlow<String?>(null)
    /** 终端通过 OSC 1338 主动触发的视频/富媒体渲染请求（带序号防重复消费）。 */
    val pendingMediaRequest = MutableStateFlow<Pair<Long, TerminalMediaRequest>?>(null)
    /** 当前行正在键入的命令草稿（用于 PSReadLine 历史幽灵预测条，退出回显敏感场景自动清空）。 */
    val liveInputDraft = MutableStateFlow("")

    private val transcriptLock = Any()
    private var transcriptWriter: BufferedWriter? = null
    private var transcriptFile: File? = null

    private sealed interface OutboundOp {
        class Data(val bytes: ByteArray) : OutboundOp
        data class Resize(val cols: Int, val rows: Int) : OutboundOp
    }

    private val writeLock = Any()
    private val draftLock = Any()
    private val typedLine = StringBuilder()
    private val outbound = Channel<OutboundOp>(256)
    @Volatile var disposed: Boolean = false
        private set

    @Volatile private var sshHandle: NativeSshSessionHandle? = null
    @Volatile private var sshChannel: ChannelShell? = null
    @Volatile private var sshOut: OutputStream? = null

    @Volatile private var localSocket: Socket? = null
    @Volatile private var localOut: DataOutputStream? = null
    @Volatile var localBackendPtyId: String? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var writerJob: Job? = null

    val engine: SolbraidTerminalEngine = SolbraidTerminalEngine(
        initialCols = initialCols.coerceIn(10, 500),
        initialRows = initialRows.coerceIn(2, 300),
        onSendInput = { bytes ->
            trackUserInputBytes(bytes)
            sendBytesAsync(bytes)
        },
        onResize = { cols, rows ->
            val c = cols.coerceIn(10, 500)
            val r = rows.coerceIn(2, 300)
            dimensions.value = c to r
            sendResizeAsync(c, r)
        },
        onBell = { bellCount.value += 1 },
        onClipboardCopy = onClipboardCopy,
        onTitleChanged = { newTitle ->
            if (newTitle.isNotBlank()) dynamicTitle.value = newTitle.take(48)
        },
        onProgressChanged = { next -> progressState.value = next },
        onNotification = onNotification,
        onCwdChanged = { nextCwd ->
            if (nextCwd.isNotBlank()) liveCwd.value = nextCwd
        },
        onMediaRequest = { req ->
            pendingMediaRequest.value = System.nanoTime() to req
        },
        schemeProvider = schemeProvider,
    )

    val isActive: Boolean
        get() = status.value == TermSessionStatus.CONNECTING || status.value == TermSessionStatus.RUNNING

    private fun looksLikeSecretPrompt(): Boolean {
        val line = engine.lastVisibleLineText.lowercase()
        if (line.isEmpty()) return false
        return line.contains("password") ||
            line.contains("passphrase") ||
            line.contains("enter pin") ||
            line.contains("[sudo]") ||
            line.contains("verification code") ||
            line.contains("otp:") ||
            line.contains("密码")
    }

    private fun trackUserInputBytes(bytes: ByteArray) {
        synchronized(draftLock) {
            if (engine.isAlternateScreen || bytes.isEmpty() || looksLikeSecretPrompt()) {
                if (typedLine.isNotEmpty()) {
                    typedLine.clear()
                    liveInputDraft.value = ""
                }
                return
            }
            // 忽略转义应答序列（如 DSR / 鼠标 / 方向键 / 括号粘贴）
            if (bytes[0] == 0x1B.toByte()) {
                if (bytes.size >= 3 && bytes[1] == '['.code.toByte() && (bytes[2] == 'A'.code.toByte() || bytes[2] == 'B'.code.toByte())) {
                    typedLine.clear()
                    liveInputDraft.value = ""
                }
                return
            }
            val str = bytes.toString(Charsets.UTF_8)
            for (ch in str) {
                when (ch) {
                    '\r', '\n' -> {
                        val cmd = typedLine.toString().trim()
                        typedLine.clear()
                        liveInputDraft.value = ""
                        if (cmd.length >= 2 && !cmd.startsWith(" ")) {
                            engine.notifyCommandSubmitted(cmd)
                            onCommandRecorded(cmd)
                        }
                    }
                    '\u0003', '\u0004', '\u0015', '\u0017' -> { // Ctrl+C / Ctrl+D / Ctrl+U / Ctrl+W
                        typedLine.clear()
                        liveInputDraft.value = ""
                    }
                    '\u007f', '\b' -> {
                        if (typedLine.isNotEmpty()) {
                            typedLine.deleteCharAt(typedLine.lastIndex)
                            liveInputDraft.value = typedLine.toString()
                        }
                    }
                    else -> if (ch >= ' ' && typedLine.length < 256) {
                        typedLine.append(ch)
                        liveInputDraft.value = typedLine.toString()
                    }
                }
            }
        }
    }

    fun clearInputDraft() {
        synchronized(draftLock) {
            typedLine.clear()
            liveInputDraft.value = ""
        }
    }

    /** 接受 PSReadLine 历史预测：以纯文本流直发剩余后缀（绝不触发括号粘贴包裹），并精确同步草稿。 */
    fun acceptPrediction(fullSuggestion: String) {
        val trimmed = liveInputDraft.value.trimStart()
        if (fullSuggestion.length <= trimmed.length) return
        val suffix = fullSuggestion.substring(trimmed.length)
        engine.sendText(suffix)
    }

    /** 发送用户交互原始串（如扩展键 ^C/^D/符号、片段、直发栏），确保同步刷新草稿与预测条。 */
    fun sendUserRaw(raw: String) {
        if (raw.isEmpty()) return
        val bytes = raw.toByteArray(Charsets.UTF_8)
        trackUserInputBytes(bytes)
        sendBytesAsync(bytes)
    }

    /** 启动 PowerShell 风格流式会话录制（自动剥离 ANSI 转义序列落盘，不受 10,000 行回滚限制）。 */
    fun startTranscript(outputDir: File): String {
        synchronized(transcriptLock) {
            transcriptFile?.let { return it.absolutePath }
            outputDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.replace(Regex("""[^A-Za-z0-9._\-]"""), "_").take(24).ifBlank { "term" }
            val file = File(outputDir, "transcript-$safeTitle-$stamp.log")
            val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8))
            val hostInfo = sshHost?.let { "${it.user}@${it.host}:${it.port}" } ?: "Local Linux PTY"
            writer.write(TerminalAnsiUtils.formatTranscriptPrologue(title, hostInfo, liveCwd.value))
            writer.flush()
            transcriptWriter = writer
            transcriptFile = file
            transcriptPath.value = file.absolutePath
            return file.absolutePath
        }
    }

    /** 结束会话录制并写入 PowerShell 标准结束时间戳尾注。 */
    fun stopTranscript(): String? {
        synchronized(transcriptLock) {
            val w = transcriptWriter ?: return null
            val path = transcriptFile?.absolutePath
            runCatching {
                w.write(TerminalAnsiUtils.formatTranscriptEpilogue())
                w.flush()
                w.close()
            }
            transcriptWriter = null
            transcriptFile = null
            transcriptPath.value = null
            return path
        }
    }

    private fun appendToTranscript(rawText: String) {
        if (transcriptWriter == null || rawText.isEmpty()) return
        synchronized(transcriptLock) {
            val w = transcriptWriter ?: return
            val clean = TerminalAnsiUtils.stripAnsi(rawText).replace("\r\n", "\n").replace('\r', '\n')
            if (clean.isNotEmpty()) {
                runCatching {
                    w.write(clean)
                    w.flush()
                }
            }
        }
    }

    fun writeToScreen(text: String) {
        appendToTranscript(text)
        engine.writeText(text)
    }

    fun writeBytesToScreen(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (transcriptWriter != null && length > 0) {
            appendToTranscript(String(bytes, offset, length.coerceAtMost(bytes.size - offset), Charsets.UTF_8))
        }
        engine.writeOutput(bytes, offset, length)
    }

    private fun startWriterLoop() {
        writerJob?.cancel()
        writerJob = ioScope.launch {
            for (firstOp in outbound) {
                if (disposed || status.value != TermSessionStatus.RUNNING) break
                var op: OutboundOp = firstOp
                // 连续 Resize 自动合并为最新尺寸，杜绝旧尺寸乱序覆盖
                if (op is OutboundOp.Resize) {
                    var next = outbound.tryReceive().getOrNull()
                    while (next is OutboundOp.Resize) {
                        op = next
                        next = outbound.tryReceive().getOrNull()
                    }
                    executeOutboundOp(op)
                    if (next != null) executeOutboundOp(next)
                } else {
                    executeOutboundOp(op)
                }
            }
        }
    }

    private fun executeOutboundOp(op: OutboundOp) {
        synchronized(writeLock) {
            runCatching {
                when (op) {
                    is OutboundOp.Data -> {
                        sshOut?.let { out ->
                            out.write(op.bytes)
                            out.flush()
                        } ?: localOut?.let { out ->
                            PtyStreamCodec.writeDataFrame(out, op.bytes)
                        }
                    }
                    is OutboundOp.Resize -> {
                        val c = op.cols.coerceIn(10, 500)
                        val r = op.rows.coerceIn(2, 300)
                        sshChannel?.takeIf { it.isConnected }?.setPtySize(c, r, 0, 0)
                        localOut?.let { out -> PtyStreamCodec.writeResizeFrame(out, c, r) }
                    }
                }
            }.onFailure { err ->
                Log.w("GALIAIS_TERM", "Terminal write/resize failed ($id): ${err.message}")
            }
        }
    }

    fun sendBytesAsync(bytes: ByteArray) {
        if (bytes.isEmpty() || disposed || status.value != TermSessionStatus.RUNNING) return
        outbound.trySend(OutboundOp.Data(bytes))
    }

    fun sendResizeAsync(cols: Int, rows: Int) {
        if (disposed) return
        val c = cols.coerceIn(10, 500)
        val r = rows.coerceIn(2, 300)
        if (status.value == TermSessionStatus.RUNNING) {
            outbound.trySend(OutboundOp.Resize(c, r))
        }
    }

    internal fun bindConnectingJob(job: Job) {
        readerJob = job
    }

    internal fun attachSsh(handle: NativeSshSessionHandle, channel: ChannelShell, out: OutputStream, job: Job) {
        sshHandle = handle
        sshChannel = channel
        sshOut = out
        readerJob = job
        status.value = TermSessionStatus.RUNNING
        errorMessage.value = null
        startWriterLoop()
        // 连接就绪后立即同步手机最新实测行列数，彻底消除 80×24 初始错位
        val (curC, curR) = dimensions.value
        sendResizeAsync(curC, curR)
        onStatusChanged()
    }

    internal fun attachLocal(ptyId: String, socket: Socket, out: DataOutputStream, job: Job) {
        localBackendPtyId = ptyId
        localSocket = socket
        localOut = out
        readerJob = job
        status.value = TermSessionStatus.RUNNING
        errorMessage.value = null
        startWriterLoop()
        val (curC, curR) = dimensions.value
        sendResizeAsync(curC, curR)
        onStatusChanged()
    }

    internal fun markExited(code: Int?, err: String? = null) {
        if (status.value == TermSessionStatus.EXITED || status.value == TermSessionStatus.ERROR) return
        progressState.value = TerminalProgressState()
        exitCode.value = code
        if (!err.isNullOrBlank()) {
            errorMessage.value = err
            status.value = TermSessionStatus.ERROR
            writeToScreen("\r\n\u001b[38;5;203m● 连接中断：$err\u001b[0m\r\n")
        } else {
            status.value = TermSessionStatus.EXITED
            val codeText = code?.toString() ?: "0"
            writeToScreen("\r\n\u001b[38;5;244m● 会话已结束（退出码 $codeText）\u001b[0m\r\n")
        }
        stopTranscript()
        onStatusChanged()
    }

    fun closeTransport() {
        writerJob?.cancel()
        writerJob = null
        readerJob?.cancel()
        readerJob = null
        synchronized(writeLock) {
            runCatching { sshChannel?.disconnect() }
            runCatching { sshHandle?.close() }
            runCatching { localSocket?.close() }
            sshChannel = null
            sshHandle = null
            sshOut = null
            localSocket = null
            localOut = null
        }
    }

    fun dispose() {
        disposed = true
        stopTranscript()
        outbound.close()
        closeTransport()
    }
}

/**
 * Solbraid 原生多会话终端管理器（零 JNI / 零第三方终端库）：
 * - 统一管理最多 8 个并发原生 SSH / 本地 Linux 终端会话；
 * - 持久化 PowerShell 配色方案、光标样式、命令块开关、CascadiaMonoNF 字体包、片段库与 PSReadLine 命令历史。
 */
class NativeTerminalManager(
    private val app: Application,
    private val runtime: RuntimeClient,
    private val sshEngine: NativeSshEngine,
    private val allHostsProvider: () -> List<SshHostConfig>,
    private val prepareLocalRuntime: suspend () -> Unit,
    private val onNotify: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("galiais_terminal_snippets", Context.MODE_PRIVATE)
    val fonts: TerminalFontPack by lazy { TerminalFontPack.load(app) }

    private val sessionsFlow = MutableStateFlow<List<LiveTerminalSession>>(emptyList())
    val sessions: StateFlow<List<LiveTerminalSession>> = sessionsFlow.asStateFlow()

    private val hasActiveFlow = MutableStateFlow(false)
    val hasActiveSession: StateFlow<Boolean> = hasActiveFlow.asStateFlow()

    private val snippetsFlow = MutableStateFlow(TerminalSnippet.parseList(prefs.getString("snippets.v1", null)))
    val snippets: StateFlow<List<TerminalSnippet>> = snippetsFlow.asStateFlow()

    private val themeFlow = MutableStateFlow(TerminalThemes.byId(prefs.getString("terminal.theme", null)))
    val theme: StateFlow<TerminalColorScheme> = themeFlow.asStateFlow()

    private val cursorStyleFlow = MutableStateFlow(TerminalCursorStyle.fromId(prefs.getString("terminal.cursor", null)))
    val cursorStyle: StateFlow<TerminalCursorStyle> = cursorStyleFlow.asStateFlow()

    private val boxDrawingFlow = MutableStateFlow(prefs.getBoolean("terminal.box_drawing", true))
    val boxDrawingEnabled: StateFlow<Boolean> = boxDrawingFlow.asStateFlow()

    private val minContrastFlow = MutableStateFlow(prefs.getBoolean("terminal.min_contrast", true))
    val minContrastEnabled: StateFlow<Boolean> = minContrastFlow.asStateFlow()

    private val secretModeFlow = MutableStateFlow(SecretRedactionMode.fromId(prefs.getString("terminal.secret_mode", null)))
    val secretRedactionMode: StateFlow<SecretRedactionMode> = secretModeFlow.asStateFlow()

    private val safeGuardFlow = MutableStateFlow(prefs.getBoolean("terminal.safe_guard", true))
    val safeGuardEnabled: StateFlow<Boolean> = safeGuardFlow.asStateFlow()

    private val visualBellFlow = MutableStateFlow(prefs.getBoolean("terminal.visual_bell", false))
    val visualBellEnabled: StateFlow<Boolean> = visualBellFlow.asStateFlow()

    private val historyFlow = MutableStateFlow(loadHistory())
    val commandHistory: StateFlow<List<String>> = historyFlow.asStateFlow()

    private fun loadHistory(): List<String> {
        val raw = prefs.getString("terminal.history.v1", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    fun recordCommandHistory(cmd: String) {
        val clean = cmd.trim()
        if (clean.length < 2 || clean.length > 300) return
        val next = (listOf(clean) + historyFlow.value.filterNot { it == clean }).take(300)
        historyFlow.value = next
        prefs.edit().putString("terminal.history.v1", JSONArray(next).toString()).apply()
    }

    fun setTheme(scheme: TerminalColorScheme) {
        themeFlow.value = scheme
        prefs.edit().putString("terminal.theme", scheme.id).apply()
    }

    fun setCursorStyle(style: TerminalCursorStyle) {
        cursorStyleFlow.value = style
        prefs.edit().putString("terminal.cursor", style.id).apply()
    }

    fun setBoxDrawingEnabled(enabled: Boolean) {
        boxDrawingFlow.value = enabled
        prefs.edit().putBoolean("terminal.box_drawing", enabled).apply()
    }

    fun setMinContrastEnabled(enabled: Boolean) {
        minContrastFlow.value = enabled
        prefs.edit().putBoolean("terminal.min_contrast", enabled).apply()
    }

    fun setSecretRedactionMode(mode: SecretRedactionMode) {
        secretModeFlow.value = mode
        prefs.edit().putString("terminal.secret_mode", mode.id).apply()
    }

    fun setSafeGuardEnabled(enabled: Boolean) {
        safeGuardFlow.value = enabled
        prefs.edit().putBoolean("terminal.safe_guard", enabled).apply()
    }

    fun setVisualBellEnabled(enabled: Boolean) {
        visualBellFlow.value = enabled
        prefs.edit().putBoolean("terminal.visual_bell", enabled).apply()
    }

    fun transcriptOutputDir(): File = File(app.filesDir, "terminal-transcripts").apply { mkdirs() }

    private fun deliverOscNotification(title: String?, body: String) {
        val msg = if (title.isNullOrBlank()) body else "$title：$body"
        if (msg.isBlank()) return
        mainHandler.post { onNotify(msg.take(120)) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bellHapticFlow = MutableStateFlow(prefs.getBoolean("terminal.bell_haptic", true))
    val bellHapticEnabled: StateFlow<Boolean> = bellHapticFlow.asStateFlow()

    fun setBellHapticEnabled(enabled: Boolean) {
        bellHapticFlow.value = enabled
        prefs.edit().putBoolean("terminal.bell_haptic", enabled).apply()
    }

    private fun refreshActiveFlag() {
        hasActiveFlow.value = sessionsFlow.value.any { it.isActive }
    }

    private fun copyOsc52(text: String) {
        if (text.isEmpty()) return
        mainHandler.post {
            runCatching {
                app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Solbraid Terminal", text))
                onNotify("终端已复制内容到剪贴板")
            }
        }
    }

    fun findReusableSession(cwd: String, sshHost: SshHostConfig?): LiveTerminalSession? {
        val list = sessionsFlow.value
        return if (sshHost != null) {
            val wantRemote = sshHost.defaultPath.trim()
            list.lastOrNull { it.sshHost?.id == sshHost.id && it.remoteCwd == wantRemote && it.isActive }
        } else {
            list.lastOrNull { !it.isSsh && it.cwd == cwd && it.isActive }
        }
    }

    fun openSsh(
        host: SshHostConfig,
        cwd: String = "/home/agent/workspace",
        cols: Int = 80,
        rows: Int = 24,
    ): LiveTerminalSession {
        check(sessionsFlow.value.size < MAX_SESSIONS) { "最多同时保持 $MAX_SESSIONS 个终端会话，请先关闭不用的标签" }
        val id = "ssh-${UUID.randomUUID().toString().take(8)}"
        val session = LiveTerminalSession(
            id = id,
            title = host.name.ifBlank { host.alias.ifBlank { host.host } },
            cwd = cwd,
            sshHost = host,
            remoteCwd = host.defaultPath.trim(),
            initialCols = cols,
            initialRows = rows,
            ioScope = scope,
            schemeProvider = { themeFlow.value },
            onClipboardCopy = ::copyOsc52,
            onCommandRecorded = ::recordCommandHistory,
            onNotification = ::deliverOscNotification,
            onStatusChanged = ::refreshActiveFlag,
        )
        sessionsFlow.value = sessionsFlow.value + session
        refreshActiveFlag()
        connectSshInto(session, host)
        return session
    }

    private fun connectSshInto(session: LiveTerminalSession, host: SshHostConfig) {
        session.writeToScreen("\u001b[38;5;111m● 正在连接 ${host.user}@${host.host}:${host.port}...\u001b[0m\r\n")
        val job = scope.launch {
            var handle: NativeSshSessionHandle? = null
            var channel: ChannelShell? = null
            try {
                handle = sshEngine.openSession(host, allHostsProvider(), interactive = true, timeoutMs = 15_000)
                channel = handle.session.openChannel("shell") as ChannelShell
                val (c, r) = session.dimensions.value
                channel.setPty(true)
                channel.setPtyType("xterm-256color")
                channel.setPtySize(c.coerceIn(10, 500), r.coerceIn(2, 300), 0, 0)
                channel.setAgentForwarding(host.forwardAgent)
                val input = channel.inputStream
                val output = BufferedOutputStream(channel.outputStream, 8192)
                channel.connect(12_000)

                if (session.disposed) return@launch
                session.attachSsh(handle, channel, output, coroutineContext[Job]!!)
                session.writeToScreen("\u001b[38;5;114m● 已连接 ${host.name}\u001b[0m\r\n")

                val initParts = buildList {
                    val remoteDir = host.defaultPath.trim()
                    if (remoteDir.isNotEmpty() && !remoteDir.contains('\n') && !remoteDir.contains('\r')) {
                        val cdExpr = when {
                            remoteDir == "~" -> "cd ~"
                            remoteDir.startsWith("~/") -> "cd ~/'${remoteDir.removePrefix("~/").replace("'", "'\\''")}'"
                            else -> "cd '${remoteDir.replace("'", "'\\''")}'"
                        }
                        add("$cdExpr 2>/dev/null || true")
                    }
                    val startup = host.startupCommand.trim()
                    if (startup.isNotEmpty() && !startup.contains('\r')) {
                        add(startup)
                    }
                }
                if (initParts.isNotEmpty()) {
                    session.sendBytesAsync((initParts.joinToString(" && ") + "\n").toByteArray(Charsets.UTF_8))
                }

                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) session.writeBytesToScreen(buf, 0, n)
                }
                // 等待 JSch 处理完 CHANNEL_CLOSE 拿到真实远端退出码
                var waitTries = 0
                while (!channel.isClosed && waitTries < 12) {
                    delay(25L)
                    waitTries++
                }
                val code = if (channel.isClosed) channel.exitStatus else 0
                session.markExited(code)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                session.markExited(null, e.message ?: "SSH 连接失败")
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { handle?.close() }
            }
        }
        session.bindConnectingJob(job)
        job.invokeOnCompletion { refreshActiveFlag() }
    }

    fun openLocal(cwd: String, cols: Int = 80, rows: Int = 24): LiveTerminalSession {
        check(runtime.hubAvailable) { "请先启动本地 Linux 运行环境（SSH 主机无需启动本地环境即可直连）" }
        check(sessionsFlow.value.size < MAX_SESSIONS) { "最多同时保持 $MAX_SESSIONS 个终端会话，请先关闭不用的标签" }
        val id = "loc-${UUID.randomUUID().toString().take(8)}"
        val shortTitle = cwd.trimEnd('/').substringAfterLast('/').ifBlank { "本地终端" }
        val session = LiveTerminalSession(
            id = id,
            title = shortTitle,
            cwd = cwd,
            sshHost = null,
            remoteCwd = "",
            initialCols = cols,
            initialRows = rows,
            ioScope = scope,
            schemeProvider = { themeFlow.value },
            onClipboardCopy = ::copyOsc52,
            onCommandRecorded = ::recordCommandHistory,
            onNotification = ::deliverOscNotification,
            onStatusChanged = ::refreshActiveFlag,
        )
        sessionsFlow.value = sessionsFlow.value + session
        refreshActiveFlag()
        connectLocalInto(session, cwd)
        return session
    }

    private fun connectLocalInto(session: LiveTerminalSession, cwd: String) {
        val job = scope.launch {
            var socket: Socket? = null
            try {
                runCatching { prepareLocalRuntime() }
                val (c, r) = session.dimensions.value
                val res = runtime.hubRequest(
                    "pty/open",
                    jsonOf("cwd" to cwd, "cols" to c.coerceIn(10, 500), "rows" to r.coerceIn(2, 300), "streamOnly" to true),
                    timeoutMs = 20_000,
                )
                val sessJson = res.getJSONObject("session")
                val ptyId = sessJson.getString("ptyId")
                session.localBackendPtyId = ptyId
                if (session.disposed) {
                    runCatching { runtime.hubRequest("pty/close", jsonOf("ptyId" to ptyId), timeoutMs = 4_000) }
                    return@launch
                }
                val streamPort = res.optInt("streamPort", 0)
                val streamToken = res.optString("streamToken", "")
                check(streamPort in 1..65535 && streamToken.isNotBlank()) { "本地终端二进制流端口不可用，请重启运行环境" }

                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress("127.0.0.1", streamPort), 6_000)
                socket = sock

                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 8192))
                val input = DataInputStream(sock.getInputStream().buffered(16 * 1024))
                val authLine = jsonOf("token" to streamToken, "ptyId" to ptyId, "replay" to true).toString() + "\n"
                out.write(authLine.toByteArray(Charsets.UTF_8))
                out.flush()
                val ack = PtyStreamCodec.readHandshakeLine(input)
                check(ack == "OK") { "终端流鉴权失败：$ack" }

                if (session.disposed) {
                    runCatching { runtime.hubRequest("pty/close", jsonOf("ptyId" to ptyId), timeoutMs = 4_000) }
                    return@launch
                }
                session.attachLocal(ptyId, sock, out, coroutineContext[Job]!!)

                while (true) {
                    val frame = PtyStreamCodec.readFrame(input) ?: break
                    when (frame.first) {
                        PtyStreamCodec.FRAME_DATA -> session.writeBytesToScreen(frame.second)
                        PtyStreamCodec.FRAME_EXIT -> {
                            val meta = runCatching { JSONObject(frame.second.toString(Charsets.UTF_8)) }.getOrNull()
                            val code = meta?.optInt("exitCode")?.takeUnless { meta.isNull("exitCode") }
                            val err = meta?.optString("error")?.takeIf { it.isNotBlank() }
                            session.markExited(code, err)
                            break
                        }
                    }
                }
                if (session.isActive) session.markExited(0)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                session.markExited(null, e.message ?: "本地终端连接失败")
            } finally {
                runCatching { socket?.close() }
                refreshActiveFlag()
            }
        }
        session.bindConnectingJob(job)
    }

    fun closeSession(id: String) {
        val target = sessionsFlow.value.firstOrNull { it.id == id } ?: return
        sessionsFlow.value = sessionsFlow.value.filterNot { it.id == id }
        val backendPty = target.localBackendPtyId
        target.dispose()
        refreshActiveFlag()
        if (!backendPty.isNullOrBlank() && runtime.hubAvailable) {
            scope.launch { runCatching { runtime.hubRequest("pty/close", jsonOf("ptyId" to backendPty), timeoutMs = 6_000) } }
        }
    }

    fun closeExitedSessions() {
        sessionsFlow.value.filter { !it.isActive }.map { it.id }.forEach(::closeSession)
    }

    fun closeAllSessions() {
        sessionsFlow.value.map { it.id }.forEach(::closeSession)
    }

    fun onLocalRuntimeDisconnected() {
        sessionsFlow.value.filter { !it.isSsh && it.isActive }.forEach { s ->
            s.closeTransport()
            s.markExited(null, "本地 Linux 运行环境已断开")
        }
        refreshActiveFlag()
    }

    fun reconnect(id: String): LiveTerminalSession? {
        val old = sessionsFlow.value.firstOrNull { it.id == id } ?: return null
        val (cols, rows) = old.dimensions.value
        val host = old.sshHost?.let { h ->
            allHostsProvider().firstOrNull { it.id == h.id }?.let { latest ->
                if (old.remoteCwd.isNotBlank()) latest.copy(defaultPath = old.remoteCwd) else latest
            } ?: h
        }
        val cwd = old.cwd
        closeSession(id)
        return if (host != null) openSsh(host, cwd, cols, rows) else openLocal(cwd, cols, rows)
    }

    fun saveSnippet(snippet: TerminalSnippet) {
        val next = snippetsFlow.value.filterNot { it.id == snippet.id } + snippet
        snippetsFlow.value = next
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        prefs.edit().putString("snippets.v1", arr.toString()).apply()
    }

    fun deleteSnippet(id: String) {
        val next = snippetsFlow.value.filterNot { it.id == id }
        snippetsFlow.value = next
        val arr = JSONArray().apply { next.forEach { put(it.toJson()) } }
        prefs.edit().putString("snippets.v1", arr.toString()).apply()
    }

    companion object {
        const val MAX_SESSIONS = 8
    }
}
