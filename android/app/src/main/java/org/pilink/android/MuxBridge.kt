package org.pilink.android

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Shared across reconnects so an uninterruptible DNS call cannot create more workers.
private object MuxResolver {
    val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(6),
        { action -> Thread(action, "pilink-mux-dns").apply { isDaemon = true } })
}

// TCP is nonblocking; DNS runs separately from the TCP and Bluetooth workers.
class MuxBridge(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeWire: () -> Unit,
    private val transmitSize: Int,
    port: Int,
    private val report: (String) -> Unit,
    private val failure: (String) -> Unit,
    private val active: (Boolean) -> Unit = {},
    private val connections: (Int) -> Unit = {},
    private val allowInternet: Boolean = false,
    private val resolve: (MuxDestination) -> List<InetAddress> = { InetAddress.getAllByName(it.host).toList() },
    private val connectTimeoutMillis: Long = 10000,
    private var powerSaving: Boolean = false
) : Closeable {
    private class Endpoint(val stream: MuxStream) : Closeable {
        var channel: SocketChannel? = null
        var key: SelectionKey? = null
        var shutdown = false
        var connecting = false
        var resolved = false
        var addresses = ArrayDeque<InetAddress>()
        var job: Future<*>? = null
        var deadline = 0L
        var lastReason = 1L
        val pendingConnect: Boolean get() = stream.destination != null && !stream.acknowledged && stream.reset == null

        fun attach(channel: SocketChannel, selector: Selector, connecting: Boolean = false) {
            val key = channel.register(selector, if (connecting) SelectionKey.OP_CONNECT else 0)
            key.attach(this)
            this.channel = channel
            this.key = key
            this.connecting = connecting
        }

        fun closeChannel() {
            key?.cancel()
            runCatching { channel?.close() }
            key = null
            channel = null
            connecting = false
        }

        override fun close() {
            job?.cancel(true)
            job = null
            closeChannel()
        }
    }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val core = MuxCore(allowInternet)
    private val endpoints = mutableMapOf<Long, Endpoint>()
    private val workers = mutableSetOf<Thread>()
    private val selector = Selector.open()
    private val listener = ServerSocketChannel.open()
    private var writing = false
    private var lastActive = false
    private var lastCount = 0
    val localPort: Int

    fun setPowerSaving(enabled: Boolean) {
        lock.withLock {
            powerSaving = enabled
            selector.wakeup()
        }
    }

    private fun selectTimeoutMillis(): Long = lock.withLock {
        if (!powerSaving) return@withLock 100L
        val deadline = endpoints.values.asSequence().filter { it.pendingConnect }
            .minOfOrNull { it.deadline } ?: return@withLock 0L
        // Zero means an event-only wait; pending DNS/TCP connects retain their deadlines.
        (TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 1).coerceAtLeast(1)
    }

    init {
        try {
            require(transmitSize > 0 && port in 0..65535 && connectTimeoutMillis > 0)
            listener.configureBlocking(false)
            listener.socket().reuseAddress = true
            listener.bind(InetSocketAddress("127.0.0.1", port), MuxLimits.STREAMS)
            listener.register(selector, SelectionKey.OP_ACCEPT)
            localPort = listener.socket().localPort
        } catch (error: Exception) { listener.close(); selector.close(); throw error }
    }

    fun start() {
        check(started.compareAndSet(false, true) && !closed.get())
        worker("pilink-mux-wire-rx") {
            while (!closed.get()) {
                val frame = MuxFrame.read(input, allowInternet)
                lock.withLock {
                    if (!closed.get()) {
                        core.receive(frame)
                        if (frame.type == MuxType.OPEN_TCP) core.streams.firstOrNull { it.id == frame.id }?.let { openInternet(it) }
                        retire(); signal()
                    }
                }
            }
        }
        worker("pilink-mux-wire-tx") {
            while (!closed.get()) {
                val frame = lock.withLock {
                    var pending: MuxFrame? = null
                    while (!closed.get() && pending == null) {
                        pending = core.nextFrame()
                        if (pending == null) changed.await()
                    }
                    if (pending != null) { writing = true; retire(); notifyActivity(); selector.wakeup() }
                    pending
                } ?: break
                val bytes = frame.encode(allowInternet)
                var offset = 0
                while (offset < bytes.size && !closed.get()) {
                    val length = minOf(transmitSize, bytes.size - offset)
                    output.write(bytes, offset, length)
                    offset += length
                }
                lock.withLock { writing = false; notifyActivity() }
            }
        }
        worker("pilink-mux-tcp") {
            while (!closed.get()) {
                selector.select(selectTimeoutMillis())
                lock.withLock {
                    if (!closed.get()) {
                        retire()
                        val ready = selector.selectedKeys().toList()
                        selector.selectedKeys().clear()
                        // Finish existing sockets before enforcing the limit on new ones.
                        for (key in ready) {
                            if (key.isValid && key.channel() !== listener) pump(key.attachment() as Endpoint)
                        }
                        retire()
                        if (ready.any { it.isValid && it.channel() === listener && it.isAcceptable }) accept()
                        for (endpoint in endpoints.values.toList()) {
                            if (endpoint.pendingConnect) {
                                if (System.nanoTime() > endpoint.deadline) {
                                    endpoint.job?.cancel(true)
                                    core.cancel(endpoint.stream, 6)
                                    changed.signalAll()
                                } else if (endpoint.resolved && endpoint.channel == null) connectNext(endpoint)
                            }
                            interests(endpoint)
                        }
                        notifyActivity()
                    }
                }
            }
        }
    }

    private fun worker(name: String, action: () -> Unit) {
        val thread = Thread({
            try { action() } catch (error: Exception) {
                if (closeOnce()) failure(error.message ?: "Multiplex transport error")
            } finally { lock.withLock {
                workers.remove(Thread.currentThread())
                if (closed.get() && workers.isEmpty()) runCatching { selector.close() }
                changed.signalAll()
            } }
        }, name).apply { isDaemon = true }
        lock.withLock { workers.add(thread) }
        thread.start()
    }

    private fun accept() {
        repeat(16) {
            val channel = listener.accept() ?: return
            if (core.count >= MuxLimits.STREAMS) {
                channel.close()
                report("Concurrent connection limit: 8; closed the additional TCP connection")
                return@repeat
            }
            try {
                channel.configureBlocking(false)
                channel.socket().tcpNoDelay = true
                val stream = core.open()
                val endpoint = Endpoint(stream)
                endpoint.attach(channel, selector)
                endpoints[stream.id] = endpoint
                signal()
            } catch (error: Exception) { channel.close(); throw error }
        }
    }

    private fun openInternet(stream: MuxStream) {
        val destination = stream.destination ?: return
        val endpoint = Endpoint(stream)
        endpoint.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMillis)
        endpoints[stream.id] = endpoint
        report("Internet connection: stream=${stream.id} ${destination.host}:${destination.port}")
        try {
            MuxResolver.executor.purge()
            endpoint.job = MuxResolver.executor.submit {
                val addresses = runCatching { resolve(destination).take(8) }.getOrDefault(emptyList())
                lock.withLock {
                    if (!closed.get() && endpoints[stream.id] === endpoint && core.contains(stream) && stream.reset == null) {
                        endpoint.job = null
                        endpoint.resolved = true
                        endpoint.addresses.addAll(addresses)
                        if (addresses.isEmpty()) core.cancel(stream, 4)
                        signal()
                    }
                }
            }
        } catch (_: RejectedExecutionException) { core.cancel(stream, 2) }
    }

    private fun connectionReason(error: Exception): Long = when (error) {
        is ConnectException -> 5
        is NoRouteToHostException -> 7
        is java.net.SocketTimeoutException -> 6
        else -> 1
    }

    private fun connectNext(endpoint: Endpoint) {
        endpoint.closeChannel()
        while (endpoint.addresses.isNotEmpty()) {
            val address = endpoint.addresses.removeFirst()
            var channel: SocketChannel? = null
            try {
                channel = SocketChannel.open()
                channel.configureBlocking(false)
                channel.socket().tcpNoDelay = true
                val connected = channel.connect(InetSocketAddress(address, endpoint.stream.destination!!.port))
                endpoint.attach(channel, selector, connecting = !connected)
                if (connected) core.connected(endpoint.stream)
                signal()
                return
            } catch (error: Exception) {
                endpoint.lastReason = connectionReason(error)
                runCatching { channel?.close() }
            }
        }
        core.cancel(endpoint.stream, endpoint.lastReason)
        signal()
    }

    private fun pump(endpoint: Endpoint) {
        val stream = endpoint.stream
        if (!core.contains(stream) || stream.reset != null) return
        val channel = endpoint.channel ?: return
        if (endpoint.connecting) {
            try {
                if (channel.finishConnect()) {
                    endpoint.connecting = false
                    core.connected(stream)
                    signal()
                }
            } catch (error: Exception) {
                endpoint.lastReason = connectionReason(error)
                connectNext(endpoint)
            }
            return
        }
        try {
            if (stream.received.size > 0) {
                val count = channel.write(ByteBuffer.wrap(stream.received.peek()))
                if (count > 0) { core.consume(stream, count); changed.signalAll() }
            }
            if (stream.remoteEOF && stream.received.size == 0 && !endpoint.shutdown) {
                channel.socket().shutdownOutput()
                endpoint.shutdown = true
            }
            if (stream.readAllowance > 0) {
                val bytes = ByteBuffer.allocate(minOf(MuxLimits.DATA, stream.readAllowance))
                val count = channel.read(bytes)
                if (count > 0) core.queue(stream, bytes.array().copyOf(count))
                else if (count < 0) core.eof(stream)
                changed.signalAll()
            }
        } catch (_: java.io.IOException) { core.cancel(stream); changed.signalAll() }
    }

    private fun interests(endpoint: Endpoint) {
        val key = endpoint.key ?: return
        if (!key.isValid) return
        if (endpoint.connecting) { key.interestOps(SelectionKey.OP_CONNECT); return }
        var events = 0
        if (endpoint.stream.readAllowance > 0) events = events or SelectionKey.OP_READ
        if (endpoint.stream.received.size > 0 || endpoint.stream.remoteEOF && !endpoint.shutdown) events = events or SelectionKey.OP_WRITE
        key.interestOps(events)
    }

    private fun retire() {
        for (id in core.takeRetired()) endpoints.remove(id)?.close()
        MuxResolver.executor.purge()
        notifyActivity()
    }

    private fun notifyActivity() {
        val busy = !closed.get() && (core.count > 0 || writing)
        if (busy != lastActive) { lastActive = busy; active(busy) }
        val count = if (closed.get()) 0 else core.count
        if (count != lastCount) { lastCount = count; connections(count) }
    }

    private fun signal() { changed.signalAll(); selector.wakeup(); notifyActivity() }

    private fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        lock.withLock {
            endpoints.values.forEach { it.close() }
            MuxResolver.executor.purge()
            endpoints.clear()
            runCatching { listener.close() }
            changed.signalAll()
            notifyActivity()
            selector.wakeup()
            if (workers.isEmpty()) runCatching { selector.close() }
        }
        runCatching { closeWire() }
        return true
    }
    override fun close() { closeOnce() }
    fun awaitTermination(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        lock.withLock {
            while (workers.isNotEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                changed.awaitNanos(remaining)
            }
        }
        selector.close()
        return true
    }
}
