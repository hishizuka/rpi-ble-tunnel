package org.pilink.android

enum class ConnectionMode(val capability: Long) {
    INTERNET(PiLinkProfile.INTERNET), MULTIPLEX(PiLinkProfile.MUX), SSH(PiLinkProfile.SSH);

    companion object {
        fun fromCapability(capability: Long): ConnectionMode = entries.firstOrNull { it.capability == capability }
            ?: throw IllegalArgumentException("Unsupported negotiated capability: $capability")
    }
}
