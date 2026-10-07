package org.pilink.android

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// Blocking writes provide independent backpressure with one bounded buffer per direction.
class StreamBridge(
    private val tcpInput: InputStream,
    private val tcpOutput: OutputStream,
    private val bleInput: InputStream,
    private val bleOutput: OutputStream,
    private val closeEndpoints: () -> Unit,
    private val transmitSize: Int,
    private val onFinished: (Long, Long, String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val remaining = AtomicInteger(2)
    private val toBLE = AtomicLong()
    private val toTCP = AtomicLong()
    @Volatile private var reason = "接続終了"

    fun start() {
        check(started.compareAndSet(false, true))
        require(transmitSize > 0)
        thread(name = "pilink-tcp-to-ble", isDaemon = true) {
            pump(tcpInput, bleOutput, transmitSize.coerceAtMost(16384), toBLE)
        }
        thread(name = "pilink-ble-to-tcp", isDaemon = true) {
            pump(bleInput, tcpOutput, 16384, toTCP)
        }
    }

    private fun pump(input: InputStream, output: OutputStream, chunk: Int, count: AtomicLong) {
        try {
            val buffer = ByteArray(chunk)
            while (!closed.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, 0, n)
                count.addAndGet(n.toLong())
            }
        } catch (error: Exception) {
            if (!closed.get()) reason = error.message ?: error.javaClass.simpleName
        } finally {
            // Closing sockets interrupts the other pump, including a blocked read or write.
            close()
            if (remaining.decrementAndGet() == 0) onFinished(toBLE.get(), toTCP.get(), reason)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { closeEndpoints() }
    }
}
