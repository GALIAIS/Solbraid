package com.galiais.agent.engine

import android.util.Base64
import android.util.Log
import com.galiais.agent.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class PortTunnel(val vmPort: Int, val localPort: Int)

/**
 * 通过 Agent Hub 将 Android 回环端口转发到本地 Linux 开发服务。
 * 每个连接独立排队，保证数据顺序与 EOF 顺序一致。
 */
class PortForwardManager(private val hub: AgentHubController) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = ConcurrentHashMap<Int, Pair<ServerSocket, Job>>() // vmPort -> (ServerSocket, acceptJob)
    private val activeSockets = ConcurrentHashMap<String, Socket>()         // connId -> client Socket
    private val remoteData = ConcurrentHashMap<String, Channel<ByteArray>>()
    private val connectionPorts = ConcurrentHashMap<String, Int>()

    private val tunnelsFlow = MutableStateFlow<Map<Int, Int>>(emptyMap())   // vmPort -> localPort
    val tunnels: StateFlow<Map<Int, Int>> = tunnelsFlow.asStateFlow()

    /** 确保虚拟机内 [vmPort] 已映射到手机本地 `127.0.0.1:<localPort>`，返回 `localPort`。 */
    suspend fun ensureTunnel(vmPort: Int): Int = withContext(Dispatchers.IO) {
        require(vmPort in 1024..65535) { "端口号需在 1024..65535 之间" }
        synchronized(servers) {
            servers[vmPort]?.first?.takeIf { !it.isClosed }?.let { return@withContext it.localPort }
            check(servers.size < 16) { "预览端口已达 16 个，请先关闭不用的预览页面" }
            val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            val localPort = server.localPort
            val job = ioScope.launch {
                try {
                    while (isActive && !server.isClosed) {
                        val client = try { server.accept() } catch (_: Exception) { break }
                        launch { bridgeClient(vmPort, client, server) }
                    }
                } finally {
                    runCatching { server.close() }
                }
            }
            servers[vmPort] = server to job
            syncState()
            localPort
        }
    }

    fun closeTunnel(vmPort: Int) {
        synchronized(servers) {
            servers.remove(vmPort)?.let { (srv, job) ->
                job.cancel()
                runCatching { srv.close() }
            }
            connectionPorts.filterValues { it == vmPort }.keys.forEach { closeConn(it, notifyRemote = true) }
            syncState()
        }
    }

    fun closeAll() {
        synchronized(servers) {
            for ((_, pair) in servers) {
                pair.second.cancel()
                runCatching { pair.first.close() }
            }
            servers.clear()
            activeSockets.keys.toList().forEach { closeConn(it, notifyRemote = true) }
            tunnelsFlow.value = emptyMap()
        }
    }

    internal fun onRemoteData(connId: String, dataBase64: String) {
        val channel = remoteData[connId] ?: return
        val bytes = try { Base64.decode(dataBase64, Base64.DEFAULT) } catch (_: IllegalArgumentException) {
            closeConn(connId, notifyRemote = true)
            return
        }
        if (channel.trySend(bytes).isFailure) {
            Log.w("GALIAIS", "预览连接接收队列已满，关闭连接以免数据损坏")
            closeConn(connId, notifyRemote = true)
        }
    }

    internal fun onRemoteClosed(connId: String) {
        // EOF 排在此前数据之后，不能在写入协程排空前关闭浏览器连接。
        remoteData[connId]?.close()
    }

    private suspend fun bridgeClient(vmPort: Int, client: Socket, server: ServerSocket) {
        val connId = "tcp-" + newId()
        client.tcpNoDelay = true
        val incoming = Channel<ByteArray>(64)
        synchronized(servers) {
            if (servers[vmPort]?.first !== server || server.isClosed) { client.close(); return }
            activeSockets[connId] = client
            connectionPorts[connId] = vmPort
            remoteData[connId] = incoming
        }
        val receiver = ioScope.launch {
            try {
                val output = client.getOutputStream()
                for (bytes in incoming) { output.write(bytes); output.flush() }
            } catch (error: java.io.IOException) {
                Log.d("GALIAIS", "预览连接写入结束：${error.message}")
            } finally { closeConn(connId, notifyRemote = true) }
        }
        try {
            hub.repo.runtime.hubRequest("tcp/open", jsonOf("connId" to connId, "port" to vmPort), 10_000)
            val input = client.getInputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                val res = hub.repo.runtime.hubRequest("tcp/write", jsonOf("connId" to connId, "dataBase64" to b64), 15_000)
                if (!res.optBoolean("ok", false)) break
            }
            hub.repo.runtime.hubRequest("tcp/end", jsonOf("connId" to connId), 10_000)
            receiver.join()
        } catch (e: Exception) {
            Log.d("GALIAIS", "本地端口转发连接结束 ($vmPort): ${e.message}")
        } finally {
            closeConn(connId, notifyRemote = true)
            receiver.cancel()
        }
    }

    private fun closeConn(connId: String, notifyRemote: Boolean) {
        val sock = activeSockets.remove(connId) ?: return
        connectionPorts.remove(connId)
        remoteData.remove(connId)?.cancel()
        runCatching { sock.close() }
        if (notifyRemote && hub.repo.runtime.hubAvailable) {
            ioScope.launch {
                runCatching { hub.repo.runtime.hubRequest("tcp/close", jsonOf("connId" to connId), 5_000) }
            }
        }
    }

    private fun syncState() {
        tunnelsFlow.value = servers.mapValues { it.value.first.localPort }
    }
}
