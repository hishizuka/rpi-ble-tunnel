package org.rpibletunnel.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class RpiBleTunnelProfile(val version: Int, val psm: Int, val capabilities: Long) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6f6d0001-8e6d-4c8a-a8bf-5b8a2a786a21")
        val VERSION: UUID = UUID.fromString("6f6d0002-8e6d-4c8a-a8bf-5b8a2a786a21")
        val PSM: UUID = UUID.fromString("6f6d0003-8e6d-4c8a-a8bf-5b8a2a786a21")
        val CAPABILITIES: UUID = UUID.fromString("6f6d0004-8e6d-4c8a-a8bf-5b8a2a786a21")
        val HOSTNAME: UUID = UUID.fromString("6f6d0005-8e6d-4c8a-a8bf-5b8a2a786a21")
        val READ_ORDER = listOf(VERSION, PSM, CAPABILITIES)
        const val AUTO = 0L
        const val SSH = 2L
        const val MUX = 4L
        const val INTERNET = 8L

        fun selectCapability(capabilities: Long): Long = when {
            capabilities and INTERNET != 0L -> INTERNET
            capabilities and MUX != 0L -> MUX
            capabilities and SSH != 0L -> SSH
            else -> throw IllegalArgumentException("Pi has no supported connection mode")
        }

        fun decode(values: Map<UUID, ByteArray>, requiredCapability: Long = SSH): RpiBleTunnelProfile {
            fun buffer(uuid: UUID, length: Int): ByteBuffer {
                val bytes = values[uuid] ?: error("GATT value missing: $uuid")
                require(bytes.size == length) { "Invalid GATT value length: $uuid" }
                return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            }
            val version = buffer(VERSION, 2).short.toInt() and 0xffff
            val psm = buffer(PSM, 2).short.toInt() and 0xffff
            val capabilities = buffer(CAPABILITIES, 4).int.toLong() and 0xffffffffL
            require(version == 1) { "Unsupported protocol version: $version" }
            require(psm in 0x80..0xff) { "Invalid LE PSM: $psm" }
            if (requiredCapability == AUTO) selectCapability(capabilities)
            require(capabilities and requiredCapability == requiredCapability) {
                when (requiredCapability) {
                    INTERNET -> "Pi is not in Internet mode"
                    MUX -> "Pi is not in multiplex mode"
                    else -> "Pi is not in SSH mode"
                }
            }
            return RpiBleTunnelProfile(version, psm, capabilities)
        }
    }
}
