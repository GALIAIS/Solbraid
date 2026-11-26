package com.galiais.agent.engine.mobile

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.galiais.agent.BuildConfig
import com.galiais.agent.engine.fs.RootFsDaemon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class ShizukuStatus(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val uid: Int = -1,
    val version: Int = -1,
) {
    val ready: Boolean get() = binderAlive && permissionGranted
}

/**
 * Shizuku 免 Root 进阶提权桥（第三级后端：`Root > Shizuku > Accessibility`）：
 * - 在未 Root 但开启了无线调试 Shizuku 的手机上，自动通过官方 UserService AIDL 解锁 `uid=2000` (shell) 级能力；
 * - 未安装或未启动 Shizuku 时零崩溃静默降级为无障碍服务。
 */
internal class ShizukuBridge(private val app: Application) {
    private val statusFlow = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = statusFlow.asStateFlow()

    private val bindMutex = Mutex()
    @Volatile private var service: ISolbraidShizukuService? = null
    @Volatile private var pendingBind: CompletableDeferred<ISolbraidShizukuService>? = null
    private var listenersRegistered = false

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, SolbraidShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = if (binder != null && binder.pingBinder()) ISolbraidShizukuService.Stub.asInterface(binder) else null
            service = svc
            if (svc != null) {
                pendingBind?.complete(svc)
            } else {
                pendingBind?.completeExceptionally(IllegalStateException("Shizuku UserService Binder 无效"))
            }
            pendingBind = null
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshStatus()
        }
    }

    private val receivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        service = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku 服务已断开"))
        pendingBind = null
        refreshStatus()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQ_PERMISSION) refreshStatus()
    }

    init {
        ensureListeners()
        refreshStatus()
    }

    private fun ensureListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(receivedListener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
    }

    fun isShizukuAppInstalled(): Boolean = runCatching {
        app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    fun refreshStatus(): ShizukuStatus {
        val installed = isShizukuAppInstalled()
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (alive) {
            runCatching {
                !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else false
        val ver = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1
        val activeSvc = service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }
        if (activeSvc == null) service = null
        val bound = activeSvc != null
        val uid = if (activeSvc != null) runCatching { activeSvc.uid }.getOrDefault(-1)
            else if (alive) runCatching { Shizuku.getUid() }.getOrDefault(-1)
            else -1
        val next = ShizukuStatus(
            installed = installed,
            binderAlive = alive,
            permissionGranted = granted,
            serviceBound = bound,
            uid = uid,
            version = ver,
        )
        statusFlow.value = next
        return next
    }

    fun requestPermission(): Boolean {
        val st = refreshStatus()
        if (!st.binderAlive) return false
        if (st.permissionGranted) return true
        return runCatching {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQ_PERMISSION)
                true
            } else false
        }.getOrDefault(false)
    }

    private suspend fun ensureService(): ISolbraidShizukuService = bindMutex.withLock {
        val existing = service
        if (existing != null && runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) {
            return existing
        }
        val st = refreshStatus()
        check(st.binderAlive) { "Shizuku 服务未运行，请先打开 Shizuku 并通过无线调试启动" }
        check(st.permissionGranted) { "尚未向 Solbraid 授予 Shizuku 权限，请在「设置 → 手机操控」中点击授权 Shizuku" }
        val deferred = CompletableDeferred<ISolbraidShizukuService>()
        pendingBind = deferred
        withContext(Dispatchers.Main) {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }
        try {
            withTimeout(8_000L) { deferred.await() }
        } catch (e: Exception) {
            pendingBind = null
            throw IllegalStateException("连接 Shizuku UserService 超时：${e.message}", e)
        }
    }

    suspend fun exec(command: String, timeoutSec: Int = 25): RootFsDaemon.ExecOutput = withContext(Dispatchers.IO) {
        val svc = ensureService()
        val raw = try {
            svc.exec(command, timeoutSec)
        } catch (e: Exception) {
            service = null
            refreshStatus()
            throw IllegalStateException("Shizuku 执行失败：${e.message}", e)
        }
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext RootFsDaemon.ExecOutput(1, raw)
        RootFsDaemon.ExecOutput(obj.optInt("exitCode", 1), obj.optString("stdout"))
    }

    companion object {
        private const val REQ_PERMISSION = 4910
    }
}
