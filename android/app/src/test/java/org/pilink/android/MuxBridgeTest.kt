package org.pilink.android

import java.io.File
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MuxBridgeTest {
    private val tail = byteArrayOf(0, 70, 73, 78, -1)
    private val loopback = InetAddress.getByName("127.0.0.1")

    private inner class Fixture : AutoCloseable {
        private val executable = File(System.getProperty("pilink.mux.server", "") ?: "").also {
            assumeTrue("Build the C peer with scripts/build-android.sh", it.isFile && it.canExecute())
        }
        val echo = ServerSocket(0, 32, loopback)
        val echoSockets = CopyOnWriteArrayList<Socket>()
        val echoStopped = AtomicBoolean()
        val failed = AtomicReference<String>()
        val failureCount = AtomicInteger()
        val failedEvent = CountDownLatch(1)
        val connections = AtomicInteger()
        val active = AtomicBoolean()
        val activity = CopyOnWriteArrayList<Boolean>()
        val closes = AtomicInteger()
        val log = File.createTempFile("pilink-c-mux-", ".log")
        val server: Process
        val wire: Socket
        val bridge: MuxBridge

        init {
            val wirePort = ServerSocket(0, 1, loopback).use { it.localPort }
            server = ProcessBuilder(executable.absolutePath, wirePort.toString(), echo.localPort.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
            thread(name = "test-mux-echo", isDaemon = true) {
                try {
                    while (!echoStopped.get()) {
                        val socket = echo.accept()
                        echoSockets.add(socket)
                        thread(name = "test-mux-echo-peer", isDaemon = true) {
                            socket.use {
                                try {
                                    val bytes = ByteArray(4096)
                                    while (true) {
                                        val count = it.getInputStream().read(bytes)
                                        if (count < 0) { it.getOutputStream().write(tail); break }
                                        it.getOutputStream().write(bytes, 0, count)
                                    }
                                } catch (_: java.io.IOException) { /* Abrupt resets are expected. */ }
                                finally { echoSockets.remove(socket) }
                            }
                        }
                    }
                } catch (_: java.io.IOException) { /* Closing the listener stops accept. */ }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!log.readText().contains("READY")) {
                check(server.isAlive && System.nanoTime() < deadline) { log.readText() }
                Thread.sleep(10)
            }
            wire = Socket(loopback, wirePort).apply { tcpNoDelay = true }
            val boundedOutput = object : OutputStream() {
                override fun write(value: Int) { wire.getOutputStream().write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    check(length <= 127) { "Wire write exceeded MTU" }
                    wire.getOutputStream().write(bytes, offset, length)
                }
            }
            bridge = MuxBridge(wire.getInputStream(), boundedOutput, { closes.incrementAndGet(); wire.close() },
                127, 0, {}, { failed.set(it); failureCount.incrementAndGet(); failedEvent.countDown() },
                { active.set(it); activity.add(it) }, { connections.set(it) })
            bridge.start()
        }
        fun socket() = Socket(loopback, bridge.localPort).apply { soTimeout = 10000; tcpNoDelay = true }
        fun idle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (connections.get() > 0 || active.get()) {
                assertTrue("Streams did not retire", System.nanoTime() < deadline)
                failed.get()?.let { throw AssertionError(it) }
                Thread.sleep(10)
            }
        }
        override fun close() {
            bridge.close()
            assertTrue("Mux workers leaked", bridge.awaitTermination(5000))
            assertEquals(1, closes.get())
            assertFalse(active.get())
            server.destroy()
            assertTrue(server.waitFor(5, TimeUnit.SECONDS))
            echoStopped.set(true)
            echo.close()
            echoSockets.forEach { it.close() }
            log.delete()
        }
    }

    private fun roundtrip(fixture: Fixture, bytes: ByteArray) {
        fixture.socket().use { socket ->
            val writer = Executors.newSingleThreadExecutor()
            try {
                val sent = writer.submit { socket.getOutputStream().write(bytes); socket.shutdownOutput() }
                assertArrayEquals(bytes + tail, socket.getInputStream().readAllBytes())
                sent.get(10, TimeUnit.SECONDS)
            } finally { writer.shutdownNow() }
        }
    }

    @Test fun eightBinaryStreamsInteroperateWithCPeer() {
        Fixture().use { fixture ->
            val pool = Executors.newFixedThreadPool(8)
            try {
                val jobs = (0..7).map { index -> pool.submit {
                    roundtrip(fixture, ByteArray(131073) { (it * 37 + index).toByte() })
                } }
                jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            fixture.idle()
            assertNull(fixture.failed.get())
            assertTrue(fixture.activity.contains(true))
            assertFalse(fixture.activity.last())
        }
    }

    @Test fun stalledReaderAndResetDoNotBlockAnotherStream() {
        Fixture().use { fixture ->
            val slow = fixture.socket().apply { receiveBufferSize = 1024; sendBufferSize = 4096 }
            val writer = Executors.newSingleThreadExecutor()
            try {
                val blocked = writer.submit {
                    try { slow.getOutputStream().write(ByteArray(8 * 1024 * 1024)) }
                    catch (_: java.io.IOException) { /* The test resets this peer. */ }
                }
                Thread.sleep(200)
                assertFalse("The slow peer did not reach backpressure", blocked.isDone)
                val started = System.nanoTime()
                roundtrip(fixture, byteArrayOf(0, 1, -1))
                assertTrue("Stalled reader blocked a different stream", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
                slow.setSoLinger(true, 0); slow.close()
                blocked.get(5, TimeUnit.SECONDS)
                roundtrip(fixture, byteArrayOf(3, 2, 1))
                fixture.idle()
                assertNull(fixture.failed.get())
            } finally { slow.close(); writer.shutdownNow() }
        }
    }

    @Test fun ninthTcpRejectedAndSlotsReusedAfterHalfClose() {
        Fixture().use { fixture ->
            val held = (0..7).map { fixture.socket() }
            try {
                held.forEachIndexed { index, socket ->
                    socket.getOutputStream().write(index)
                    assertEquals(index, socket.getInputStream().read())
                }
                fixture.socket().use { assertEquals(-1, it.getInputStream().read()) }
                assertEquals(8, fixture.connections.get())
                held.forEach { it.shutdownOutput(); assertArrayEquals(tail, it.getInputStream().readAllBytes()) }
            } finally { held.forEach { it.close() } }
            fixture.idle()
            repeat(16) { roundtrip(fixture, byteArrayOf(it.toByte(), 0, -1)); fixture.idle() }
            assertNull(fixture.failed.get())
        }
    }

    @Test fun wireLossClosesEveryTcpAndReleasesActivity() {
        Fixture().use { fixture ->
            val sockets = (0..2).map { fixture.socket() }
            try {
                sockets.forEach { it.getOutputStream().write(1); assertEquals(1, it.getInputStream().read()) }
                fixture.server.destroy()
                assertTrue(fixture.failedEvent.await(5, TimeUnit.SECONDS))
                sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
                assertTrue(fixture.bridge.awaitTermination(5000))
                assertFalse(fixture.active.get())
                assertEquals(0, fixture.connections.get())
                assertEquals(1, fixture.failureCount.get())
            } finally { sockets.forEach { it.close() } }
        }
    }

    @Test fun explicitStopUnblocksWireWorkersAndRemovesListener() {
        Fixture().use { fixture ->
            fixture.socket().use { socket ->
                socket.getOutputStream().write(1); assertEquals(1, socket.getInputStream().read())
                fixture.bridge.close(); fixture.bridge.close()
                assertTrue(fixture.bridge.awaitTermination(5000))
                assertEquals(-1, socket.getInputStream().read())
                assertNull(fixture.failed.get())
                assertThrows(java.io.IOException::class.java) { fixture.socket() }
            }
        }
    }

    @Test fun malformedPeerWindowFailsOnceAndClosesTcp() {
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { peer ->
                listener.accept().use { wire ->
                    peer.soTimeout = 5000
                    val failed = CountDownLatch(1)
                    val failures = AtomicInteger()
                    val active = AtomicBoolean()
                    val bridge = MuxBridge(wire.getInputStream(), wire.getOutputStream(), { wire.close() },
                        127, 0, {}, { failures.incrementAndGet(); failed.countDown() }, { active.set(it) })
                    try {
                        bridge.start()
                        Socket(loopback, bridge.localPort).use { tcp ->
                            tcp.soTimeout = 5000
                            val open = MuxFrame.read(peer.getInputStream())
                            assertEquals(MuxType.OPEN, open.type)
                            peer.getOutputStream().write(MuxFrame.control(MuxType.OK, open.id, 16384).encode())
                            peer.getOutputStream().write(MuxFrame.control(MuxType.WINDOW, open.id, 1).encode())
                            assertTrue(failed.await(5, TimeUnit.SECONDS))
                            assertEquals(-1, tcp.getInputStream().read())
                            assertTrue(bridge.awaitTermination(5000))
                            assertEquals(1, failures.get())
                            assertFalse(active.get())
                        }
                    } finally { bridge.close(); assertTrue(bridge.awaitTermination(5000)) }
                }
            }
        }
    }

    @Test fun stopBeforeStartClosesListenerAndPortConflictCleansUp() {
        val closes = AtomicInteger()
        val bridge = MuxBridge(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
            { closes.incrementAndGet() }, 127, 0, {}, {})
        bridge.close()
        assertTrue(bridge.awaitTermination(5000))
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) { bridge.start() }
        assertThrows(java.io.IOException::class.java) { Socket(loopback, bridge.localPort) }
        ServerSocket(0, 1, loopback).use { busy ->
            assertThrows(java.net.BindException::class.java) {
                MuxBridge(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), {}, 127, busy.localPort, {}, {})
            }
        }
    }

    @Test fun activityRemainsHeldUntilLastFinIsWritten() {
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { peer ->
                listener.accept().use { wire ->
                    peer.soTimeout = 5000
                    val finEntered = CountDownLatch(1)
                    val releaseFin = CountDownLatch(1)
                    val inactive = CountDownLatch(1)
                    val retired = CountDownLatch(1)
                    val active = AtomicBoolean()
                    val count = AtomicInteger()
                    val failed = AtomicReference<String>()
                    val delayedOutput = object : OutputStream() {
                        override fun write(value: Int) { wire.getOutputStream().write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (offset == 0 && bytes[3].toInt() == MuxType.FIN.wire) {
                                finEntered.countDown()
                                check(releaseFin.await(5, TimeUnit.SECONDS))
                            }
                            wire.getOutputStream().write(bytes, offset, length)
                        }
                    }
                    val bridge = MuxBridge(wire.getInputStream(), delayedOutput, { wire.close() },
                        127, 0, {}, { failed.set(it) }, { busy ->
                            active.set(busy)
                            if (!busy) inactive.countDown()
                        }, { value -> count.set(value); if (value == 0) retired.countDown() })
                    try {
                        bridge.start()
                        Socket(loopback, bridge.localPort).use { tcp ->
                            val open = MuxFrame.read(peer.getInputStream())
                            peer.getOutputStream().write(MuxFrame.control(MuxType.OK, open.id, 16384).encode())
                            peer.getOutputStream().write(MuxFrame(MuxType.FIN, open.id).encode())
                            tcp.shutdownOutput()
                            assertTrue(finEntered.await(5, TimeUnit.SECONDS))
                            // The remote FIN may be decoded after our FIN enters the wire writer.
                            assertTrue(retired.await(5, TimeUnit.SECONDS))
                            assertEquals(0, count.get())
                            assertTrue("Activity was released before the final wire write", active.get())
                            assertEquals(1L, inactive.count)
                            releaseFin.countDown()
                            assertEquals(MuxType.FIN, MuxFrame.read(peer.getInputStream()).type)
                            assertTrue(inactive.await(5, TimeUnit.SECONDS))
                            assertFalse(active.get())
                            assertNull(failed.get())
                        }
                    } finally {
                        releaseFin.countDown()
                        bridge.close()
                        assertTrue(bridge.awaitTermination(5000))
                    }
                }
            }
        }
    }
}
