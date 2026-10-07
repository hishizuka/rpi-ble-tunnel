package org.pilink.android

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MuxLimits {
    const val STREAMS = 8
    const val WINDOW = 16384
    const val DATA = 1024
    const val HEADER = 12
}

enum class MuxType(val wire: Int) {
    OPEN(1), OK(2), DATA(3), WINDOW(4), FIN(5), RESET(6), PING(7), PONG(8), OPEN_TCP(9)
}

data class MuxFrame(val type: MuxType, val id: Long, val payload: ByteArray = byteArrayOf()) {
    val value: Long get() {
        require(payload.size == 4) { "多重化の整数長が不正です" }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
    fun encode(allowInternet: Boolean = false): ByteArray {
        validate(type, id, payload.size, allowInternet)
        if (type == MuxType.OPEN_TCP) MuxDestination.decode(payload)
        return ByteBuffer.allocate(MuxLimits.HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(80).put(76).put(1).put(type.wire.toByte()).putInt(id.toInt()).putInt(payload.size)
            .put(payload).array()
    }
    companion object {
        fun control(type: MuxType, id: Long, value: Long): MuxFrame {
            require(value in 0..0xffffffffL)
            return MuxFrame(type, id, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
        }
        private fun validate(type: MuxType, id: Long, length: Int, allowInternet: Boolean) {
            val control = type == MuxType.PING || type == MuxType.PONG
            require(if (control) id == 0L else id in 1..0xffffffffL && (allowInternet || id and 1L == 1L)) { "多重化 stream ID が不正です" }
            require(type != MuxType.OPEN_TCP || allowInternet && id and 1L == 0L) { "Internet の stream ID が不正です" }
            require(type != MuxType.OPEN || id and 1L == 1L) { "SSH の stream ID が不正です" }
            require(when (type) {
                MuxType.DATA -> length in 1..MuxLimits.DATA
                MuxType.FIN -> length == 0
                MuxType.OPEN_TCP -> length in 9..MuxLimits.DATA
                else -> length == 4
            }) { "多重化フレームの長さが不正です" }
        }
        fun read(input: InputStream, allowInternet: Boolean = false): MuxFrame {
            fun exact(length: Int): ByteArray {
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) throw EOFException("多重化の通信路が切断されました")
                    if (count == 0) continue
                    offset += count
                }
                return bytes
            }
            val header = exact(MuxLimits.HEADER)
            require(header[0] == 80.toByte() && header[1] == 76.toByte() && header[2] == 1.toByte()) {
                "多重化の magic / version が不正です"
            }
            val type = MuxType.entries.firstOrNull { it.wire == header[3].toInt() }
                ?: error("多重化の type が不正です")
            val integers = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val id = integers.getInt(4).toLong() and 0xffffffffL
            val length = integers.getInt(8)
            // Validate before allocating payload storage, including unsigned overflow.
            validate(type, id, length, allowInternet)
            return MuxFrame(type, id, exact(length)).also { if (type == MuxType.OPEN_TCP) MuxDestination.decode(it.payload) }
        }
    }
}

class MuxBuffer(private val capacity: Int = MuxLimits.WINDOW) {
    private val bytes = ByteArray(capacity)
    private var offset = 0
    var size: Int = 0
        private set
    fun append(data: ByteArray) {
        require(data.size <= capacity - size) { "多重化バッファの上限を超えました" }
        for (i in data.indices) bytes[(offset + size + i) % capacity] = data[i]
        size += data.size
    }
    fun peek(limit: Int = MuxLimits.DATA): ByteArray = ByteArray(minOf(size, limit)) { bytes[(offset + it) % capacity] }
    fun consume(count: Int) {
        require(count in 0..size)
        offset = (offset + count) % capacity
        size -= count
    }
    fun clear() { offset = 0; size = 0 }
}

class MuxStream(val id: Long) {
    var destination: MuxDestination? = null
        internal set
    val received = MuxBuffer()
    internal val outgoing = MuxBuffer()
    internal var openSent = false
    internal var acknowledged = false
    internal var credit = 0
    internal var receiveCredit = MuxLimits.WINDOW
    internal var window = 0
    internal var finSent = false
    internal var reset: Long? = null
    var localEOF = false
        internal set
    var remoteEOF = false
        internal set
    val readAllowance: Int get() = if (acknowledged && !localEOF && reset == null) credit - outgoing.size else 0
}

// The bridge serializes all core operations with its lock; the core performs no I/O.
class MuxCore(private val allowInternet: Boolean = false) {
    private var nextID = 1L
    private var highestEven = 0L
    private var cursor = 0L
    private val table = linkedMapOf<Long, MuxStream>()
    private val retired = mutableListOf<Long>()
    private val rejected = ArrayDeque<Long>()
    private var pong: Long? = null
    val streams: List<MuxStream> get() = table.values.toList()
    val count: Int get() = table.size
    fun contains(stream: MuxStream) = table[stream.id] === stream
    fun open(): MuxStream {
        check(count < MuxLimits.STREAMS && nextID < 0xffffffffL) { "同時接続は 8 本までです" }
        val stream = MuxStream(nextID)
        nextID += 2
        table[stream.id] = stream
        return stream
    }
    fun queue(stream: MuxStream, bytes: ByteArray) {
        require(contains(stream) && bytes.isNotEmpty() && bytes.size <= stream.readAllowance) { "多重化の送信枠を超えました" }
        stream.outgoing.append(bytes)
    }
    fun consume(stream: MuxStream, count: Int) {
        stream.received.consume(count)
        stream.window += count
        reap(stream)
    }
    fun eof(stream: MuxStream) { stream.localEOF = true }
    fun connected(stream: MuxStream) {
        require(contains(stream) && stream.destination != null && !stream.acknowledged)
        stream.acknowledged = true
    }
    fun cancel(stream: MuxStream, reason: Long = 3) {
        if (!contains(stream)) return
        if (stream.destination == null && !stream.openSent) { retire(stream); return }
        stream.reset = reason
        stream.outgoing.clear()
        stream.received.clear()
    }
    private fun retire(stream: MuxStream) {
        table.remove(stream.id)
        retired.add(stream.id)
    }
    private fun reap(stream: MuxStream) {
        if (stream.finSent && stream.remoteEOF && stream.received.size == 0) retire(stream)
    }
    fun takeRetired(): List<Long> = retired.toList().also { retired.clear() }
    fun receive(frame: MuxFrame) {
        if (frame.type == MuxType.PING || frame.type == MuxType.PONG) {
            if (frame.type == MuxType.PING) { check(pong == null) { "PING の待機枠を超えました" }; pong = frame.value }
            return
        }
        require(frame.type != MuxType.OPEN) { "Pi からの OPEN は未対応です" }
        if (frame.type == MuxType.OPEN_TCP) {
            require(allowInternet && frame.id > highestEven && frame.id and 1L == 0L) { "OPEN_TCP の ID が不正です" }
            val destination = MuxDestination.decode(frame.payload)
            highestEven = frame.id
            if (count >= MuxLimits.STREAMS) {
                check(rejected.size < 16) { "OPEN_TCP の拒否待機枠を超えました" }
                rejected.addLast(frame.id)
                return
            }
            table[frame.id] = MuxStream(frame.id).also {
                it.destination = destination
                it.credit = MuxLimits.WINDOW
                it.receiveCredit = 0
            }
            return
        }
        require(frame.id and 1L == 1L || allowInternet) { "stream ID が不正です" }
        val stream = table[frame.id]
        if (stream == null) {
            require(if (frame.id and 1L == 1L) frame.id < nextID else frame.id <= highestEven) { "未発行の stream ID です" }
            return
        }
        if (frame.type == MuxType.RESET) { retire(stream); return }
        if (stream.reset != null) return
        when (frame.type) {
            MuxType.OK -> {
                require(stream.destination == null && stream.openSent && !stream.acknowledged && frame.value == MuxLimits.WINDOW.toLong()) { "OPEN_OK が不正です" }
                stream.acknowledged = true
                stream.credit = MuxLimits.WINDOW
            }
            MuxType.DATA -> {
                require(stream.acknowledged && !stream.remoteEOF && frame.payload.size <= stream.receiveCredit) { "受信枠超過または FIN 後の DATA です" }
                stream.received.append(frame.payload)
                stream.receiveCredit -= frame.payload.size
            }
            MuxType.WINDOW -> {
                require(stream.acknowledged && frame.value in 1..(MuxLimits.WINDOW - stream.credit).toLong()) { "WINDOW が不正です" }
                stream.credit += frame.value.toInt()
            }
            MuxType.FIN -> {
                require(stream.acknowledged && !stream.remoteEOF) { "FIN が不正です" }
                stream.remoteEOF = true
                reap(stream)
            }
            else -> error("予期しない多重化フレームです")
        }
    }
    fun nextFrame(): MuxFrame? {
        if (rejected.isNotEmpty()) return MuxFrame.control(MuxType.RESET, rejected.removeFirst(), 2)
        pong?.let { pong = null; return MuxFrame.control(MuxType.PONG, 0, it) }
        val all = streams
        // OPEN IDs must stay monotonic when scheduling interleaves both directions.
        all.filter { it.destination == null && !it.openSent && it.reset == null }.minByOrNull { it.id }?.let {
            it.openSent = true
            cursor = it.id
            return MuxFrame.control(MuxType.OPEN, it.id, MuxLimits.WINDOW.toLong())
        }
        for (stream in all.filter { it.id > cursor } + all.filter { it.id <= cursor }) {
            val frame = when {
                stream.reset != null -> MuxFrame.control(MuxType.RESET, stream.id, stream.reset!!).also { retire(stream) }
                stream.destination != null && stream.acknowledged && !stream.openSent -> {
                    stream.openSent = true
                    stream.receiveCredit = MuxLimits.WINDOW
                    MuxFrame.control(MuxType.OK, stream.id, MuxLimits.WINDOW.toLong())
                }
                stream.acknowledged && stream.window > 0 -> {
                    val credit = stream.window
                    stream.window = 0
                    stream.receiveCredit += credit
                    MuxFrame.control(MuxType.WINDOW, stream.id, credit.toLong())
                }
                stream.acknowledged && stream.outgoing.size > 0 -> {
                    val bytes = stream.outgoing.peek()
                    stream.outgoing.consume(bytes.size)
                    stream.credit -= bytes.size
                    MuxFrame(MuxType.DATA, stream.id, bytes)
                }
                stream.acknowledged && stream.localEOF && !stream.finSent -> {
                    stream.finSent = true
                    MuxFrame(MuxType.FIN, stream.id).also { reap(stream) }
                }
                else -> null
            }
            if (frame != null) { cursor = stream.id; return frame }
        }
        return null
    }
}
