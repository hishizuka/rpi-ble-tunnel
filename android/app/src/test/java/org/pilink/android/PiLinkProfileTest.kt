package org.pilink.android

import org.junit.Assert.*
import org.junit.Test

class PiLinkProfileTest {
    private fun values() = mutableMapOf(
        PiLinkProfile.VERSION to byteArrayOf(1, 0),
        PiLinkProfile.PSM to byteArrayOf(0x80.toByte(), 0),
        PiLinkProfile.CAPABILITIES to byteArrayOf(2, 0, 0, 0)
    )

    @Test fun validLittleEndianProfile() {
        assertEquals(PiLinkProfile(1, 128, 2), PiLinkProfile.decode(values()))
    }
    @Test fun unknownCapabilityBitsAreIgnored() {
        val input = values()
        input[PiLinkProfile.CAPABILITIES] = byteArrayOf(2, 0, 0, 0x80.toByte())
        assertEquals(0x80000002L, PiLinkProfile.decode(input).capabilities)
    }
    @Test fun malformedAndMissingValuesAreRejected() {
        for (uuid in PiLinkProfile.READ_ORDER) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(8))) {
                val input = values(); input[uuid] = bytes
                assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input) }
            }
            val input = values(); input.remove(uuid)
            assertThrows(IllegalStateException::class.java) { PiLinkProfile.decode(input) }
        }
    }
    @Test fun invalidVersionAndPsmAreRejected() {
        val version = values(); version[PiLinkProfile.VERSION] = byteArrayOf(2, 0)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(version) }
        for (psm in listOf(byteArrayOf(0x7f, 0), byteArrayOf(0, 1), byteArrayOf(0, 0))) {
            val input = values(); input[PiLinkProfile.PSM] = psm
            assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input) }
        }
    }
    @Test fun echoDaemonCannotBeUsedAsSsh() {
        val input = values(); input[PiLinkProfile.CAPABILITIES] = byteArrayOf(1, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input) }
    }
    @Test fun multiplexModeIsExplicitAndAllRequiredBitsMustBePresent() {
        val input = values(); input[PiLinkProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0)
        assertEquals(4L, PiLinkProfile.decode(input, PiLinkProfile.MUX).capabilities)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input) }
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(values(), PiLinkProfile.MUX) }
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.MUX or PiLinkProfile.SSH) }
    }
    @Test fun muxProfileAcceptsFutureCapabilityBits() {
        val input = values(); input[PiLinkProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0x80.toByte())
        assertEquals(0x80000004L, PiLinkProfile.decode(input, PiLinkProfile.MUX).capabilities)
    }
    @Test fun internetCapabilityIsDistinctFromLegacyModes() {
        val input = values(); input[PiLinkProfile.CAPABILITIES] = byteArrayOf(8, 0, 0, 0)
        assertEquals(8L, PiLinkProfile.decode(input, PiLinkProfile.INTERNET).capabilities)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.MUX) }
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input) }
        input[PiLinkProfile.CAPABILITIES] = byteArrayOf(4, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.INTERNET) }
    }

    @Test fun automaticConnectionSupportsInternetAndBothLegacyTransports() {
        for (capability in listOf(PiLinkProfile.INTERNET, PiLinkProfile.MUX, PiLinkProfile.SSH)) {
            val input = values()
            input[PiLinkProfile.CAPABILITIES] = ByteArray(4) { index -> (capability shr (index * 8)).toByte() }
            val profile = PiLinkProfile.decode(input, PiLinkProfile.AUTO)
            assertEquals(capability, PiLinkProfile.selectCapability(profile.capabilities))
        }
    }

    @Test fun automaticConnectionPrefersInternetThenMultiplex() {
        assertEquals(PiLinkProfile.INTERNET, PiLinkProfile.selectCapability(14))
        assertEquals(PiLinkProfile.MUX, PiLinkProfile.selectCapability(6))
        assertEquals(PiLinkProfile.INTERNET, PiLinkProfile.selectCapability(0x80000008))
        assertEquals(PiLinkProfile.SSH, PiLinkProfile.selectCapability(0x80000002))
    }

    @Test fun automaticConnectionRejectsEchoAndUnknownTransports() {
        for (capability in listOf(0L, 1L, 0x80000000L, 0x80000001L)) {
            val input = values()
            input[PiLinkProfile.CAPABILITIES] = ByteArray(4) { index -> (capability shr (index * 8)).toByte() }
            assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.AUTO) }
        }
    }

    @Test fun automaticConnectionStillValidatesVersionAndPsm() {
        val input = values()
        input[PiLinkProfile.VERSION] = byteArrayOf(2, 0)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.AUTO) }
        input[PiLinkProfile.VERSION] = byteArrayOf(1, 0)
        input[PiLinkProfile.PSM] = byteArrayOf(0, 1)
        assertThrows(IllegalArgumentException::class.java) { PiLinkProfile.decode(input, PiLinkProfile.AUTO) }
    }
}
