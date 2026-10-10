package org.rpibletunnel.android

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class StreamBridgeTest {
    private fun pair(): Pair<Socket, Socket> = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
        val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val bridge = listener.accept()
        for (socket in listOf(peer, bridge)) {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
        }
        bridge to peer
    }

    @Test fun binaryDuplexTransferWithStalledReaderAndMtuBound() {
        val (tcp, tcpPeer) = pair()
        val (ble, blePeer) = pair()
        tcp.sendBufferSize = 1024
        tcpPeer.receiveBufferSize = 1024
        val ended = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val closes = AtomicInteger()
        val counts = AtomicReference<Pair<Long, Long>>()
        val mtuOutput = object : OutputStream() {
            override fun write(value: Int) { ble.getOutputStream().write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertTrue("write exceeds MTU", length <= 127)
                ble.getOutputStream().write(bytes, offset, length)
            }
        }
        val bridge = StreamBridge(tcp.getInputStream(), tcp.getOutputStream(), ble.getInputStream(), mtuOutput,
            { closes.incrementAndGet(); tcp.close(); ble.close() }, 127) { sent, received, _ ->
            counts.set(sent to received); ended.countDown()
        }
        try {
            val outbound = ByteArray(65536).also { Random(7).nextBytes(it) }
            val inbound = ByteArray(65536).also { Random(8).nextBytes(it) }
            bridge.start()
            val writers = listOf(thread {
                try { for (chunk in outbound.asList().chunked(511)) tcpPeer.getOutputStream().write(chunk.toByteArray()) }
                catch (failure: Throwable) { error.compareAndSet(null, failure) }
            }, thread {
                try { for (chunk in inbound.asList().chunked(2041)) blePeer.getOutputStream().write(chunk.toByteArray()) }
                catch (failure: Throwable) { error.compareAndSet(null, failure) }
            })
            // The TCP peer pauses while the opposite direction remains readable.
            assertArrayEquals(outbound, blePeer.getInputStream().readNBytes(outbound.size))
            Thread.sleep(100)
            assertArrayEquals(inbound, tcpPeer.getInputStream().readNBytes(inbound.size))
            writers.forEach { it.join(5000); assertFalse(it.isAlive) }
            error.get()?.let { throw AssertionError(it) }
            tcpPeer.close()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(65536L to 65536L, counts.get())
            assertEquals(1, closes.get())
        } finally {
            bridge.close(); tcpPeer.close(); blePeer.close()
        }
    }

    @Test fun eofForwardsLastBytesBeforeClosing() {
        val (tcp, tcpPeer) = pair()
        val (ble, blePeer) = pair()
        val ended = CountDownLatch(1)
        val bridge = StreamBridge(tcp.getInputStream(), tcp.getOutputStream(), ble.getInputStream(), ble.getOutputStream(),
            { tcp.close(); ble.close() }, 127) { _, _, _ -> ended.countDown() }
        try {
            bridge.start()
            val tail = ByteArray(4097).also { Random(9).nextBytes(it) }
            tcpPeer.getOutputStream().write(tail)
            tcpPeer.shutdownOutput()
            assertArrayEquals(tail, blePeer.getInputStream().readAllBytes())
            assertTrue(ended.await(5, TimeUnit.SECONDS))
        } finally { bridge.close(); tcpPeer.close(); blePeer.close() }
    }

    @Test fun cancellationUnblocksBothPumpsAndFinishesOnce() {
        val (tcp, tcpPeer) = pair()
        val (ble, blePeer) = pair()
        val ended = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val bridge = StreamBridge(tcp.getInputStream(), tcp.getOutputStream(), ble.getInputStream(), ble.getOutputStream(),
            { tcp.close(); ble.close() }, 512) { _, _, _ -> callbacks.incrementAndGet(); ended.countDown() }
        try {
            bridge.start(); bridge.close(); bridge.close()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        } finally { tcpPeer.close(); blePeer.close() }
    }
}
