package org.rpibletunnel.android

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MultiplexTest {
    private val payload = ByteArray(MuxLimits.DATA) { (it * 37).toByte() }
    private fun acknowledged(core: MuxCore): MuxStream = core.open().also {
        assertEquals(it.id, core.nextFrame()!!.id)
        core.receive(MuxFrame.control(MuxType.OK, it.id, MuxLimits.WINDOW.toLong()))
    }

    @Test fun codecHandlesSplitAndCoalescedBinaryFrames() {
        val frames = listOf(MuxFrame.control(MuxType.OPEN, 1, 16384), MuxFrame(MuxType.DATA, 3, payload),
            MuxFrame(MuxType.FIN, 3), MuxFrame.control(MuxType.PING, 0, 0xdeadbeefL))
        val bytes = frames.flatMap { it.encode().asIterable() }.toByteArray()
        for (size in listOf(1, 3, 11, 12, 127, 4096)) {
            val stream = object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(size, len))
            }
            for (frame in frames) assertArrayEquals(frame.encode(), MuxFrame.read(stream).encode())
            assertThrows(EOFException::class.java) { MuxFrame.read(stream) }
        }
        assertArrayEquals(byteArrayOf(80, 76, 1, 1, 1, 0, 0, 0, 4, 0, 0, 0, 0, 64, 0, 0), frames[0].encode())
    }

    @Test fun badHeadersAndOversizedPayloadRejectedBeforeRead() {
        for (offset in 0..3) {
            val bytes = MuxFrame(MuxType.FIN, 1).encode(); bytes[offset] = -1
            assertThrows(RuntimeException::class.java) { MuxFrame.read(ByteArrayInputStream(bytes)) }
        }
        for (length in listOf(-1, Int.MAX_VALUE, 1025, 0)) {
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .put(80).put(76).put(1).put(3).putInt(1).putInt(length).array()
            assertThrows(IllegalArgumentException::class.java) { MuxFrame.read(ByteArrayInputStream(header)) }
        }
        val encoded = MuxFrame(MuxType.DATA, 1, payload).encode()
        for (length in listOf(0, 1, 11, 12, encoded.size - 1)) {
            assertThrows(EOFException::class.java) { MuxFrame.read(ByteArrayInputStream(encoded.copyOf(length))) }
        }
        for (bad in listOf(MuxFrame(MuxType.FIN, 0), MuxFrame(MuxType.FIN, 2), MuxFrame(MuxType.DATA, 1),
            MuxFrame(MuxType.FIN, 1, byteArrayOf(1)), MuxFrame.control(MuxType.PING, 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { bad.encode() }
        }
    }

    @Test fun boundedRingPreservesBytesAcrossWraparound() {
        val buffer = MuxBuffer(7)
        buffer.append(byteArrayOf(0, 1, 2, 3, -1)); buffer.consume(3)
        buffer.append(byteArrayOf(4, 5, 6, 7, 8))
        assertEquals(7, buffer.size)
        assertArrayEquals(byteArrayOf(3, -1, 4, 5, 6, 7, 8), buffer.peek(7))
        assertThrows(IllegalArgumentException::class.java) { buffer.append(byteArrayOf(9)) }
        assertThrows(IllegalArgumentException::class.java) { buffer.consume(8) }
        buffer.consume(7); buffer.append(byteArrayOf(0, -1))
        assertArrayEquals(byteArrayOf(0, -1), buffer.peek())
    }

    @Test fun fairnessAndIndependentCredits() {
        val core = MuxCore()
        val first = acknowledged(core); val second = acknowledged(core)
        core.queue(first, ByteArray(16384)); core.queue(second, payload)
        assertEquals(first.id, core.nextFrame()!!.id)
        assertEquals(second.id, core.nextFrame()!!.id)
        repeat(15) { assertEquals(first.id, core.nextFrame()!!.id) }
        assertEquals(0, first.readAllowance)
        assertNull(core.nextFrame())
        core.queue(second, payload)
        assertEquals(second.id, core.nextFrame()!!.id)
        assertThrows(IllegalArgumentException::class.java) { core.queue(first, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame.control(MuxType.WINDOW, first.id, 16385)) }
        core.receive(MuxFrame.control(MuxType.WINDOW, first.id, 1024))
        assertEquals(1024, first.readAllowance)
    }

    @Test fun windowReturnedOnlyAfterDeliveryAndFinFollowsData() {
        val core = MuxCore(); val stream = acknowledged(core)
        repeat(16) { core.receive(MuxFrame(MuxType.DATA, stream.id, payload)) }
        assertNull(core.nextFrame())
        assertEquals(16384, stream.received.size)
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame(MuxType.DATA, stream.id, byteArrayOf(1))) }
        core.consume(stream, 127)
        val credit = core.nextFrame()!!
        assertEquals(MuxType.WINDOW, credit.type); assertEquals(127L, credit.value)
        core.receive(MuxFrame(MuxType.DATA, stream.id, payload.copyOf(127)))
        assertEquals(16384, stream.received.size)
        core.queue(stream, payload); core.eof(stream)
        assertEquals(MuxType.DATA, core.nextFrame()!!.type)
        assertEquals(MuxType.FIN, core.nextFrame()!!.type)
        core.receive(MuxFrame(MuxType.FIN, stream.id))
        assertTrue(core.contains(stream))
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame(MuxType.DATA, stream.id, byteArrayOf(1))) }
        core.consume(stream, 16384)
        assertEquals(listOf(stream.id), core.takeRetired())
        core.receive(MuxFrame.control(MuxType.WINDOW, stream.id, 1))
        assertEquals(0, core.count)
    }

    @Test fun limitsResetFreshIdsAndUnknownStreams() {
        val core = MuxCore()
        val streams = (1..8).map { acknowledged(core) }
        assertThrows(IllegalStateException::class.java) { core.open() }
        core.cancel(streams[0])
        val reset = core.nextFrame()!!
        assertEquals(MuxType.RESET, reset.type); assertEquals(3L, reset.value)
        assertEquals(listOf(1L), core.takeRetired())
        assertEquals(17L, core.open().id)
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame(MuxType.FIN, 99)) }
        assertThrows(IllegalArgumentException::class.java) { core.receive(MuxFrame.control(MuxType.OK, 3, 16384)) }
        core.receive(MuxFrame.control(MuxType.RESET, 3, 123))
        assertEquals(listOf(3L), core.takeRetired())
        core.receive(MuxFrame.control(MuxType.PING, 0, 0xdeadbeefL))
        val pong = core.nextFrame()!!
        assertEquals(MuxType.PONG, pong.type); assertEquals(0xdeadbeefL, pong.value)
    }
}
