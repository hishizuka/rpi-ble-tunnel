package org.pilink.android

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// The debug activity exposes a temporary loopback peer for real-device half-close tests.
// The adb reverse test path did not preserve the FIN tail in this device setup.
internal object DebugEchoServer {
    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(8) { action -> Thread(action, "pilink-test-echo").apply { isDaemon = true } }
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    @Synchronized fun start(port: Int) {
        check(BuildConfig.DEBUG)
        require(port in 1024..65535)
        stop()
        val listener = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newFixedThreadPool(8) { action -> Thread(action, "pilink-test-echo").apply { isDaemon = true } }
        server = listener
        pool = workers
        thread(name = "pilink-test-echo-listener", isDaemon = true) {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    if (clients.size >= 8) { socket.close(); continue }
                    clients.add(socket)
                    try {
                        workers.execute {
                            socket.use {
                                try {
                                    val bytes = ByteArray(4096)
                                    while (true) {
                                        val count = it.getInputStream().read(bytes)
                                        if (count < 0) break
                                        it.getOutputStream().write(bytes, 0, count)
                                    }
                                    it.getOutputStream().write(byteArrayOf(0) + "FIN-tail".toByteArray(Charsets.US_ASCII) + byteArrayOf(-1))
                                } catch (_: java.io.IOException) { /* Stop and resets are expected. */ }
                                finally { clients.remove(socket) }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(socket); socket.close() }
                }
            } catch (_: java.io.IOException) { /* Closing the server stops accept. */ }
        }
        Log.i("PiLink", "TEST_ECHO_READY 127.0.0.1:$port")
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } }; clients.clear() }
        pool.shutdownNow()
    }
}
