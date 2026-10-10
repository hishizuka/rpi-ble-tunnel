package org.rpibletunnel.android

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MuxDestination(val host: String, val port: Int, val addressType: Int) {
    companion object {
        fun decode(payload: ByteArray): MuxDestination {
            require(payload.size >= 9 && ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int == MuxLimits.WINDOW) {
                "Invalid OPEN_TCP receive window or length"
            }
            val type = payload[4].toInt() and 255
            val end = when (type) {
                1 -> 9
                4 -> 21
                3 -> 6 + (payload[5].toInt() and 255)
                else -> error("Invalid OPEN_TCP address type")
            }
            require(payload.size == end + 2) { "Invalid OPEN_TCP destination length" }
            val host = if (type == 3) {
                require(end > 6 && payload.sliceArray(6 until end).all { (it.toInt() and 255) in 33..126 }) {
                    "Invalid destination hostname"
                }
                String(payload, 6, end - 6, Charsets.US_ASCII)
            } else InetAddress.getByAddress(payload.copyOfRange(5, end)).hostAddress!!
            val port = ((payload[end].toInt() and 255) shl 8) or (payload[end + 1].toInt() and 255)
            require(port > 0) { "Destination port is zero" }
            return MuxDestination(host, port, type)
        }
    }
}
