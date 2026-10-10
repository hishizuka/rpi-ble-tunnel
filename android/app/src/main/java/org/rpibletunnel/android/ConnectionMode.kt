package org.rpibletunnel.android

enum class ConnectionMode(val capability: Long) {
    INTERNET(RpiBleTunnelProfile.INTERNET), MULTIPLEX(RpiBleTunnelProfile.MUX), SSH(RpiBleTunnelProfile.SSH);

    companion object {
        fun fromCapability(capability: Long): ConnectionMode = entries.firstOrNull { it.capability == capability }
            ?: throw IllegalArgumentException("Unsupported negotiated capability: $capability")
    }
}
