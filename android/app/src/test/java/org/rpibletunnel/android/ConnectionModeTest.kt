package org.rpibletunnel.android

import org.junit.Assert.*
import org.junit.Test

class ConnectionModeTest {
    @Test fun modeReflectsTheNegotiatedCapability() {
        assertEquals(ConnectionMode.INTERNET, ConnectionMode.fromCapability(8))
        assertEquals(ConnectionMode.MULTIPLEX, ConnectionMode.fromCapability(4))
        assertEquals(ConnectionMode.SSH, ConnectionMode.fromCapability(2))
    }
    @Test fun unknownAndUnnegotiatedModesCannotBeDisplayedAsInternet() {
        for (capability in listOf(0L, 1L, 6L, 14L, 16L))
            assertThrows(IllegalArgumentException::class.java) { ConnectionMode.fromCapability(capability) }
    }
}
