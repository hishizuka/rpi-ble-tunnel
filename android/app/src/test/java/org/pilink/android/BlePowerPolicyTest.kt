package org.pilink.android

import org.junit.Assert.*
import org.junit.Test

class BlePowerPolicyTest {
    @Test fun idleStreamsSettingsAndReconnectChooseThePriority() {
        val requests = mutableListOf<Boolean>()
        val policy = BlePowerPolicy { requests.add(it); true }
        policy.update(enabled = true, busy = true)
        assertTrue(requests.isEmpty())
        policy.connected()
        assertEquals(listOf(false), requests)
        policy.update(enabled = true, busy = false)
        policy.update(enabled = true, busy = false)
        assertEquals(listOf(false, true), requests)
        policy.update(enabled = true, busy = true)
        assertEquals(listOf(false, true, false), requests)
        // An open SSH session stays balanced even while its application is idle.
        policy.update(enabled = true, busy = true)
        assertEquals(3, requests.size)
        policy.update(enabled = true, busy = false)
        policy.update(enabled = false, busy = false)
        assertEquals(listOf(false, true, false, true, false), requests)
        policy.disconnected()
        policy.update(enabled = true, busy = true)
        assertEquals(5, requests.size)
        policy.connected()
        assertEquals(listOf(false, true, false, true, false, false), requests)
    }

    @Test fun disabledPowerSavingRestoresBalancedOnEveryConnection() {
        val requests = mutableListOf<Boolean>()
        val policy = BlePowerPolicy { requests.add(it); true }
        policy.update(enabled = false, busy = false)
        policy.connected()
        policy.update(enabled = false, busy = true)
        policy.update(enabled = false, busy = false)
        policy.disconnected()
        policy.connected()
        assertEquals(listOf(false, false), requests)
    }

    @Test fun rejectedRequestCanBeRetriedAndDisabled() {
        var accept = false
        val requests = mutableListOf<Boolean>()
        val policy = BlePowerPolicy { requests.add(it); accept }
        policy.update(enabled = true, busy = false)
        policy.connected()
        accept = true
        policy.update(enabled = true, busy = false)
        policy.update(enabled = true, busy = false)
        policy.update(enabled = false, busy = false)
        assertEquals(listOf(true, true, false), requests)
    }
}
