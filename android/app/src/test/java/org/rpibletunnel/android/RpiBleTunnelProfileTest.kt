package org.rpibletunnel.android

import org.junit.Assert.*
import org.junit.Test

class RpiBleTunnelProfileTest {
    private fun values() = mutableMapOf(
        RpiBleTunnelProfile.VERSION to byteArrayOf(1, 0),
        RpiBleTunnelProfile.PSM to byteArrayOf(0x80.toByte(), 0),
        RpiBleTunnelProfile.CAPABILITIES to byteArrayOf(2, 0, 0, 0)
    )

    @Test fun validLittleEndianProfile() {
        assertEquals(RpiBleTunnelProfile(1, 128, 2), RpiBleTunnelProfile.decode(values()))
    }
    @Test fun unknownCapabilityBitsAreIgnored() {
        val input = values()
        input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(2, 0, 0, 0x80.toByte())
        assertEquals(0x80000002L, RpiBleTunnelProfile.decode(input).capabilities)
    }
    @Test fun malformedAndMissingValuesAreRejected() {
        for (uuid in RpiBleTunnelProfile.READ_ORDER) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(8))) {
                val input = values(); input[uuid] = bytes
                assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input) }
            }
            val input = values(); input.remove(uuid)
            assertThrows(IllegalStateException::class.java) { RpiBleTunnelProfile.decode(input) }
        }
    }
    @Test fun invalidVersionAndPsmAreRejected() {
        val version = values(); version[RpiBleTunnelProfile.VERSION] = byteArrayOf(2, 0)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(version) }
        for (psm in listOf(byteArrayOf(0x7f, 0), byteArrayOf(0, 1), byteArrayOf(0, 0))) {
            val input = values(); input[RpiBleTunnelProfile.PSM] = psm
            assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input) }
        }
    }
    @Test fun echoDaemonCannotBeUsedAsSsh() {
        val input = values(); input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(1, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input) }
    }
    @Test fun multiplexModeIsExplicitAndAllRequiredBitsMustBePresent() {
        val input = values(); input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0)
        assertEquals(4L, RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.MUX).capabilities)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input) }
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(values(), RpiBleTunnelProfile.MUX) }
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.MUX or RpiBleTunnelProfile.SSH) }
    }
    @Test fun muxProfileAcceptsFutureCapabilityBits() {
        val input = values(); input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0x80.toByte())
        assertEquals(0x80000004L, RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.MUX).capabilities)
    }
    @Test fun internetCapabilityIsDistinctFromLegacyModes() {
        val input = values(); input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(8, 0, 0, 0)
        assertEquals(8L, RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.INTERNET).capabilities)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.MUX) }
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input) }
        input[RpiBleTunnelProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.INTERNET) }
    }

    @Test fun automaticConnectionSupportsInternetAndBothLegacyTransports() {
        for (capability in listOf(RpiBleTunnelProfile.INTERNET, RpiBleTunnelProfile.MUX, RpiBleTunnelProfile.SSH)) {
            val input = values()
            input[RpiBleTunnelProfile.CAPABILITIES] = ByteArray(4) { index -> (capability shr (index * 8)).toByte() }
            val profile = RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.AUTO)
            assertEquals(capability, RpiBleTunnelProfile.selectCapability(profile.capabilities))
        }
    }

    @Test fun automaticConnectionPrefersInternetThenMultiplex() {
        assertEquals(RpiBleTunnelProfile.INTERNET, RpiBleTunnelProfile.selectCapability(14))
        assertEquals(RpiBleTunnelProfile.MUX, RpiBleTunnelProfile.selectCapability(6))
        assertEquals(RpiBleTunnelProfile.INTERNET, RpiBleTunnelProfile.selectCapability(0x80000008))
        assertEquals(RpiBleTunnelProfile.SSH, RpiBleTunnelProfile.selectCapability(0x80000002))
    }

    @Test fun automaticConnectionRejectsEchoAndUnknownTransports() {
        for (capability in listOf(0L, 1L, 0x80000000L, 0x80000001L)) {
            val input = values()
            input[RpiBleTunnelProfile.CAPABILITIES] = ByteArray(4) { index -> (capability shr (index * 8)).toByte() }
            assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.AUTO) }
        }
    }

    @Test fun automaticConnectionStillValidatesVersionAndPsm() {
        val input = values()
        input[RpiBleTunnelProfile.VERSION] = byteArrayOf(2, 0)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.AUTO) }
        input[RpiBleTunnelProfile.VERSION] = byteArrayOf(1, 0)
        input[RpiBleTunnelProfile.PSM] = byteArrayOf(0, 1)
        assertThrows(IllegalArgumentException::class.java) { RpiBleTunnelProfile.decode(input, RpiBleTunnelProfile.AUTO) }
    }
}
