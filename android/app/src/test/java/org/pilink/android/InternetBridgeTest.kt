package org.pilink.android

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class InternetBridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private inner class Fixture(resolve: (MuxDestination) -> List<InetAddress>, timeout: Long) : AutoCloseable {
        private val server = ServerSocket(0, 1, loopback)
        private val wire = Socket(loopback, server.localPort)
        val peer = server.accept().apply { soTimeout = 5000; tcpNoDelay = true }
        val failed = AtomicReference<String>()
        val active = AtomicBoolean()
        val bridge = MuxBridge(wire.getInputStream(), wire.getOutputStream(), { wire.close() }, 127, 0,
            {}, { failed.set(it) }, active = { active.set(it) }, allowInternet = true, resolve = resolve, connectTimeoutMillis = timeout)
        init { bridge.start() }
        fun send(frame: MuxFrame) { peer.getOutputStream().write(frame.encode(true)) }
        fun read() = MuxFrame.read(peer.getInputStream(), true)
        override fun close() {
            bridge.close()
            assertTrue("Bridge workers leaked", bridge.awaitTermination(5000))
            assertFalse(active.get())
            peer.close(); server.close()
        }
    }

    @Test fun slowDnsTimesOutWithoutBlockingAnExistingForwardStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            Fixture({ entered.countDown(); release.await(); listOf(loopback) }, 500).use { fixture ->
                Socket(loopback, fixture.bridge.localPort).use { ssh ->
                    ssh.soTimeout = 5000
                    val open = fixture.read()
                    fixture.send(MuxFrame.control(MuxType.OK, open.id, 16384))
                    fixture.send(InternetTest.target(2))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    fixture.send(MuxFrame(MuxType.DATA, open.id, byteArrayOf(0, -1, 3)))
                    assertArrayEquals(byteArrayOf(0, -1, 3), ssh.getInputStream().readNBytes(3))
                    var timeout: MuxFrame
                    do { timeout = fixture.read() } while (timeout.type != MuxType.RESET)
                    assertEquals(2L, timeout.id); assertEquals(6L, timeout.value)
                    fixture.send(MuxFrame(MuxType.DATA, open.id, byteArrayOf(4)))
                    assertEquals(4, ssh.getInputStream().read())
                    assertNull(fixture.failed.get())
                }
            }
        } finally { release.countDown() }
    }

    // Model a DNS implementation that ignores interruption until the OS lookup finishes.
    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count != 0L) try { latch.await() } catch (_: InterruptedException) { }
    }

    @Test fun lateDnsCompletionAfterStopCannotCreateAnOutboundSocket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        ServerSocket(0, 1, loopback).use { target ->
            target.soTimeout = 300
            try {
                Fixture({ entered.countDown(); awaitIgnoringInterrupts(release); finished.countDown(); listOf(loopback) }, 10000).use { fixture ->
                    fixture.send(InternetTest.target(2, target.localPort))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(fixture.active.get())
                    fixture.bridge.close()
                    assertTrue(fixture.bridge.awaitTermination(5000))
                    assertFalse(fixture.active.get())
                    release.countDown()
                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    assertThrows(SocketTimeoutException::class.java) { target.accept() }
                    assertNull(fixture.failed.get())
                }
            } finally { release.countDown() }
        }
    }

    @Test fun resolverWorkersAndWaitingJobsRemainBoundedAcrossCancelledStreams() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            Fixture({
                val count = running.incrementAndGet()
                maximum.accumulateAndGet(count, ::maxOf)
                entered.countDown()
                try { awaitIgnoringInterrupts(release); listOf(loopback) } finally { running.decrementAndGet() }
            }, 500).use { fixture ->
                for (id in 2L..16L step 2) fixture.send(InternetTest.target(id))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(8) { val frame = fixture.read(); assertEquals(MuxType.RESET, frame.type); assertEquals(6L, frame.value) }
                // The two old running lookups still occupy the global workers.
                for (id in 18L..30L step 2) fixture.send(InternetTest.target(id))
                val rejected = fixture.read()
                assertEquals(MuxType.RESET, rejected.type); assertEquals(30L, rejected.id); assertEquals(2L, rejected.value)
                assertEquals(2, maximum.get())
                assertNull(fixture.failed.get())
            }
        } finally { release.countDown() }
    }
}
