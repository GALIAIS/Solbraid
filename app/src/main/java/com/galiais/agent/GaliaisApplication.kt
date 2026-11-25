package com.galiais.agent

import android.app.Application
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import com.galiais.agent.engine.AppSettings
import com.galiais.agent.engine.ConversationRepository
import com.galiais.agent.engine.EnvSshRepository
import com.galiais.agent.engine.ExtensionRepository
import com.galiais.agent.engine.RuntimeClient
import com.galiais.agent.engine.SuperAgentCoordinator
import com.galiais.agent.engine.fs.UnifiedFileRepository
import java.io.File

class GaliaisApplication : Application() {
    val settings: AppSettings by lazy { AppSettings(this) }
    val runtime: RuntimeClient by lazy { RuntimeClient(this) }
    val conversations: ConversationRepository by lazy { ConversationRepository(runtime, settings) }
    val fileManager: UnifiedFileRepository by lazy { UnifiedFileRepository(this, runtime, settings) }
    val extensions: ExtensionRepository by lazy { conversations.extensions }
    val envSsh: EnvSshRepository by lazy { conversations.envSsh }
    val superAgent: SuperAgentCoordinator by lazy { conversations.superAgent }
    var network: VmNetworkBroker? = null
        private set
    var networkError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        cleanupStaleLeases()
        // 原生 Root / PRoot 模式已脱离旧 QEMU 方案，不再冷启动常驻 VmNetworkBroker 线程与抽象 Socket。
        runtime.bind()
        conversations.toString() // 立即开始订阅运行服务事件
    }

    private fun readPersistedNetworkName(file: File): String? {
        if (!file.isFile) return null
        val text = try { file.readText(Charsets.UTF_8).trim() } catch (_: Exception) { return null }
        return text.takeIf { Regex("^galiais\\.network\\.[a-zA-Z0-9.-]{1,64}$").matches(it) }
    }

    private fun cleanupStaleLeases() {
        val cutoff = System.currentTimeMillis() - 60_000L
        filesDir.listFiles { file ->
            file.isFile && file.name.startsWith("root-session-") && file.name.endsWith(".lease") && file.lastModified() < cutoff
        }?.forEach { runCatching { it.delete() } }
    }
}
