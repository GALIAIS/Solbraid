package com.galiais.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.DataInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VmNetworkBroker(context: Context, existingName: String? = null) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val name = existingName ?: "galiais.network.${UUID.randomUUID()}"
    private val server = LocalServerSocket(name)
    private val stopped = AtomicBoolean(false)
    private val routeSequence = AtomicLong()
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()

    fun start() {
        acceptor.execute {
            while (!stopped.get()) {
                val client = try { server.accept() } catch (error: IOException) {
                    if (!stopped.get()) Log.e("GALIAIS", "运行环境网络监听已停止", error)
                    break
                }
                try {
                    val uid = client.peerCredentials.uid
                    if (uid != 60000 && uid != Process.myUid()) { client.close(); continue }
                    client.soTimeout = 3000
                    clients.add(client)
                    workers.execute { handle(client) }
                } catch (error: Exception) {
                    clients.remove(client)
                    try { client.close() } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
                    if (!stopped.get() && error !is java.util.concurrent.RejectedExecutionException)
                        Log.w("GALIAIS", "无法接收运行环境网络请求", error)
                }
            }
        }
    }

    private fun handle(client: LocalSocket) {
        var received: Array<FileDescriptor> = emptyArray()
        var created: FileDescriptor? = null
        try {
            val input = DataInputStream(client.inputStream)
            val header = ByteArray(8)
            input.readFully(header)
            received = client.ancillaryFileDescriptors ?: emptyArray()
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val operation = fields.int
            val size = fields.int
            require(size in 0..128) { "网络请求大小无效" }
            val payload = ByteArray(size)
            input.readFully(payload)
            when (operation) {
                1 -> {
                    require(size == 12 && received.isEmpty()) { "网络句柄请求无效" }
                    val args = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val domain = args.int
                    val type = args.int
                    val protocol = args.int
                    require(domain == OsConstants.AF_INET || domain == OsConstants.AF_INET6) { "不支持的网络地址类型" }
                    val socketType = type and (OsConstants.SOCK_CLOEXEC or OsConstants.SOCK_NONBLOCK).inv()
                    require(socketType == OsConstants.SOCK_STREAM || socketType == OsConstants.SOCK_DGRAM) { "不支持的网络句柄类型" }
                    require(protocol == 0 || (socketType == OsConstants.SOCK_STREAM && protocol == OsConstants.IPPROTO_TCP) ||
                        (socketType == OsConstants.SOCK_DGRAM && protocol == OsConstants.IPPROTO_UDP)) { "不支持的网络协议" }
                    val descriptor = Os.socket(domain, type, protocol)
                    created = descriptor
                    client.setFileDescriptorsForSend(arrayOf(descriptor))
                    respond(client, 0)
                }
                2 -> {
                    require(size == 52 && received.size == 1) { "网络路由请求无效" }
                    val command = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
                    require(command in setOf(1, 9, 10, 11)) { "不支持的网络路由操作" }
                    // 解析目标地址（routing_message 偏移 24 为 sockaddr）：本机回环流量无需绑定物理网卡
                    val family = ByteBuffer.wrap(payload, 24, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val destAddr = when (family) {
                        OsConstants.AF_INET -> InetAddress.getByAddress(payload.copyOfRange(28, 32))
                        OsConstants.AF_INET6 -> InetAddress.getByAddress(payload.copyOfRange(32, 48))
                        else -> null
                    }
                    if (destAddr != null && (destAddr.isLoopbackAddress || destAddr.isAnyLocalAddress)) {
                        respond(client, 0)
                    } else if (Build.VERSION.SDK_INT < 35) {
                        val activeNet = connectivity?.activeNetwork
                            ?: throw ErrnoException("activeNetwork", OsConstants.ENETUNREACH)
                        activeNet.bindSocket(received[0])
                        respond(client, 0)
                    } else {
                        val activeNet = connectivity?.activeNetwork
                        if (activeNet != null) {
                            activeNet.bindSocket(received[0])
                            respond(client, 0)
                        } else {
                            payload.fill(0, 4, 16)
                            LocalSocket().use { netd ->
                                netd.bind(LocalSocketAddress("$name.route.${routeSequence.incrementAndGet()}", LocalSocketAddress.Namespace.ABSTRACT))
                                netd.soTimeout = 3000
                                netd.connect(LocalSocketAddress("/dev/socket/fwmarkd", LocalSocketAddress.Namespace.FILESYSTEM))
                                check(netd.peerCredentials.uid == 0) { "Android 网络服务身份无效" }
                                netd.setFileDescriptorsForSend(received)
                                netd.outputStream.write(payload)
                                val response = ByteArray(4)
                                DataInputStream(netd.inputStream).readFully(response)
                                val code = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).int
                                require(code in -4095..0) { "Android 网络服务响应无效" }
                                respond(client, code)
                            }
                        }
                    }
                }
                3 -> {
                    require(received.size == 1 && size in setOf(16, 28)) { "本地网络绑定请求无效" }
                    val family = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val port = (payload[2].toInt() and 255) * 256 + (payload[3].toInt() and 255)
                    val address = when {
                        family == OsConstants.AF_INET && size == 16 -> InetAddress.getByAddress(payload.copyOfRange(4, 8))
                        family == OsConstants.AF_INET6 && size == 28 -> {
                            require(payload.copyOfRange(4, 8).all { it == 0.toByte() } && payload.copyOfRange(24, 28).all { it == 0.toByte() }) { "不支持带作用域或流标识的本地绑定" }
                            InetAddress.getByAddress(payload.copyOfRange(8, 24))
                        }
                        else -> throw IllegalArgumentException("网络绑定地址无效")
                    }
                    require((address.isLoopbackAddress && (port == 0 || port >= 1024)) || (address.isAnyLocalAddress && port == 0)) { "仅允许本机或出站临时网络绑定" }
                    Os.bind(received[0], address, port)
                    respond(client, 0)
                }
                4 -> {
                    require(received.size == 1 && size == 4) { "本机网络监听请求无效" }
                    val backlog = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
                    require(backlog in 0..128) { "网络监听队列无效" }
                    val address = Os.getsockname(received[0])
                    require(address is java.net.InetSocketAddress && address.address.isLoopbackAddress && address.port >= 1024) { "仅允许本机网络监听" }
                    Os.listen(received[0], backlog)
                    respond(client, 0)
                }
                else -> throw IllegalArgumentException("未知网络操作")
            }
        } catch (error: Exception) {
            val code = when (error) {
                is ErrnoException -> error.errno
                is java.net.SocketTimeoutException -> OsConstants.ETIMEDOUT
                is IOException -> OsConstants.EIO
                else -> OsConstants.EPROTO
            }
            try { respond(client, -code) } catch (reply: IOException) { error.addSuppressed(reply) }
            if (!stopped.get() && error !is java.io.EOFException && error !is java.net.SocketTimeoutException)
                Log.w("GALIAIS", "运行环境网络请求失败", error)
        } finally {
            created?.let(::closeDescriptor)
            received.forEach(::closeDescriptor)
            clients.remove(client)
            try { client.close() } catch (error: IOException) { if (!stopped.get()) Log.w("GALIAIS", "关闭网络转接连接失败", error) }
        }
    }

    private fun respond(client: LocalSocket, code: Int) {
        client.outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array())
    }
    private fun closeDescriptor(fd: FileDescriptor) {
        if (fd.valid()) try { Os.close(fd) } catch (error: ErrnoException) { Log.w("GALIAIS", "关闭网络句柄失败", error) }
    }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        server.close()
        for (client in clients) try { client.close() } catch (error: IOException) { Log.w("GALIAIS", "关闭网络转接连接失败", error) }
        acceptor.shutdownNow()
        workers.shutdownNow()
    }
}
