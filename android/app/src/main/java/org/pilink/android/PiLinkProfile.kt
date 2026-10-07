package org.pilink.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class PiLinkProfile(val version: Int, val psm: Int, val capabilities: Long) {
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
            else -> throw IllegalArgumentException("Pi が対応する接続方式を提供していません")
        }

        fun decode(values: Map<UUID, ByteArray>, requiredCapability: Long = SSH): PiLinkProfile {
            fun buffer(uuid: UUID, length: Int): ByteBuffer {
                val bytes = values[uuid] ?: error("GATT の値がありません: $uuid")
                require(bytes.size == length) { "GATT の長さが不正です: $uuid" }
                return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            }
            val version = buffer(VERSION, 2).short.toInt() and 0xffff
            val psm = buffer(PSM, 2).short.toInt() and 0xffff
            val capabilities = buffer(CAPABILITIES, 4).int.toLong() and 0xffffffffL
            require(version == 1) { "未対応の protocol version: $version" }
            require(psm in 0x80..0xff) { "不正な LE PSM: $psm" }
            if (requiredCapability == AUTO) selectCapability(capabilities)
            require(capabilities and requiredCapability == requiredCapability) {
                when (requiredCapability) {
                    INTERNET -> "Pi が Internet モードではありません"
                    MUX -> "Pi が多重化モードではありません"
                    else -> "Pi が SSH モードではありません"
                }
            }
            return PiLinkProfile(version, psm, capabilities)
        }
    }
}
