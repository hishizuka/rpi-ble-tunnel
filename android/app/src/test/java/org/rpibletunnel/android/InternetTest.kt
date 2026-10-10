package org.rpibletunnel.android

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class InternetTest {
    companion object {
        fun target(id: Long, port: Int = 443, name: String = "example.com"): MuxFrame {
            val host = name.toByteArray(Charsets.US_ASCII)
            val payload = ByteBuffer.allocate(8 + host.size).order(ByteOrder.LITTLE_ENDIAN).putInt(16384)
                .put(3).put(host.size.toByte()).put(host).put((port shr 8).toByte()).put(port.toByte()).array()
            return MuxFrame(MuxType.OPEN_TCP, id, payload)
        }
    }

    @Test fun targetCodecHandlesBoundariesAndRejectsLegacyAndMalformedTargets() {
        val frame = target(2)
        assertEquals(MuxDestination("example.com", 443, 3), MuxDestination.decode(frame.payload))
        for (size in listOf(1, 3, 11, 12, 127)) {
            val input = object : FilterInputStream(ByteArrayInputStream(frame.encode(true))) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(size, length))
            }
            assertArrayEquals(frame.payload, MuxFrame.read(input, true).payload)
        }
        assertThrows(IllegalArgumentException::class.java) { frame.encode() }
        assertThrows(IllegalArgumentException::class.java) { MuxFrame.read(ByteArrayInputStream(frame.encode(true))) }
        assertThrows(IllegalArgumentException::class.java) { target(1).encode(true) }
        for (payload in listOf(target(2, 0).payload, target(2, name = "a\u0000b").payload,
            target(2, name = "").payload, frame.payload.copyOf(frame.payload.size - 1), frame.payload.copyOf().also { it[0] = 1 })) {
            assertThrows(RuntimeException::class.java) { MuxDestination.decode(payload) }
        }
        val ipv4 = byteArrayOf(0, 64, 0, 0, 1, 127, 0, 0, 1, 0, 22)
        assertEquals(MuxDestination("127.0.0.1", 22, 1), MuxDestination.decode(ipv4))
        val ipv6 = ByteArray(23).also { it[1] = 64; it[4] = 4; it[20] = 1; it[22] = 22 }
        assertEquals(4, MuxDestination.decode(ipv6).addressType)
        assertEquals(22, MuxDestination.decode(ipv6).port)
    }

    @Test fun reverseAcknowledgmentCreditsHalfCloseAndLateFrames() {
        val core = MuxCore(true)
        core.receive(target(2))
        val stream = core.streams.single()
        assertEquals(0, stream.readAllowance)
        assertNull(core.nextFrame())
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame(MuxType.DATA, 2, byteArrayOf(1))) }
        core.connected(stream)
        assertEquals(MuxType.OK, core.nextFrame()!!.type)
        val bytes = byteArrayOf(0, -1, 3)
        core.receive(MuxFrame(MuxType.DATA, 2, bytes))
        assertArrayEquals(bytes, stream.received.peek())
        assertNull(core.nextFrame())
        core.consume(stream, bytes.size)
        assertEquals(3L, core.nextFrame()!!.value)
        core.queue(stream, bytes); core.eof(stream)
        assertEquals(MuxType.DATA, core.nextFrame()!!.type)
        assertEquals(MuxType.FIN, core.nextFrame()!!.type)
        core.receive(MuxFrame(MuxType.FIN, 2))
        assertEquals(listOf(2L), core.takeRetired())
        core.receive(MuxFrame.control(MuxType.WINDOW, 2, 1))
        assertThrows(IllegalArgumentException::class.java) { core.receive(target(2)) }
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame(MuxType.FIN, 4)) }
    }

    @Test fun forwardReverseStreamsShareLimitAndRejectionsPreserveOtherStreams() {
        val core = MuxCore(true)
        repeat(4) {
            val stream = core.open(); assertEquals(stream.id, core.nextFrame()!!.id)
            core.receive(MuxFrame.control(MuxType.OK, stream.id, 16384))
        }
        for (id in listOf(2L, 4L, 6L, 8L)) { core.receive(target(id)); core.connected(core.streams.last()); core.nextFrame() }
        assertEquals(8, core.count)
        assertThrows(IllegalStateException::class.java) { core.open() }
        core.receive(target(10))
        val reset = core.nextFrame()!!
        assertEquals(MuxType.RESET, reset.type); assertEquals(10L, reset.id); assertEquals(2L, reset.value)
        core.receive(MuxFrame.control(MuxType.RESET, 2, 3))
        core.receive(target(12))
        assertEquals(8, core.count)
        assertTrue(core.streams.any { it.id == 12L })
    }

    @Test fun mixedDirectionsKeepOpenIdsMonotonicAndUnsentCancelIsLocal() {
        val core = MuxCore(true)
        core.receive(target(2)); core.connected(core.streams.single()); core.nextFrame()
        val first = core.open(); val second = core.open()
        assertEquals(first.id, core.nextFrame()!!.id)
        assertEquals(second.id, core.nextFrame()!!.id)
        val unsent = core.open(); core.cancel(unsent)
        assertFalse(core.contains(unsent)); assertNull(core.nextFrame())
        core.receive(target(4)); core.cancel(core.streams.last(), 4)
        val reset = core.nextFrame()!!
        assertEquals(4L, reset.id); assertEquals(4L, reset.value)
    }
}
