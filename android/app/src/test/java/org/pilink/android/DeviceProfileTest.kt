package org.pilink.android

import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTest {
    private val profile = DeviceProfile("test-device", "raspberrypi")

    @Test fun sshUsesTheHostnameAndActiveLocalPort() {
        assertEquals("ssh -p 2222 -o HostKeyAlias=raspberrypi.local pi@127.0.0.1", profile.sshCommand())
        assertEquals("ssh -p 2223 -o HostKeyAlias=raspberrypi.local pi@127.0.0.1", profile.sshCommand(2223))
        assertEquals("ssh -p 2222 -o HostKeyAlias=spare-pi.local pi@127.0.0.1", profile.copy(hostname = "spare-pi").sshCommand())
    }
    @Test fun unsafeHostnamesCannotBecomeShellCommands() {
        for (hostname in listOf("", "-oProxyCommand=cmd", "name space", "pi;command", "pi\ncommand", "$(cmd)", "`cmd`", "a_b", "pi-")) {
            assertThrows(IllegalArgumentException::class.java) { profile.copy(hostname = hostname).sshCommand() }
        }
    }
    @Test fun namesAddressesAndPortsAreValidated() {
        for (invalid in listOf(profile.copy(hostname = " "), profile.copy(hostname = "x".repeat(64)),
            profile.copy(hostname = "Pi"), profile.copy(port = 1023), profile.copy(port = 65536), profile.copy(id = ""),
            profile.copy(address = "invalid"), profile.copy(displayName = " "))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
        }
        profile.copy(hostname = "x".repeat(63), port = 1024, address = "AA:BB:CC:DD:EE:FF").validated()
        profile.copy(port = 65535).validated()
    }
    @Test fun displayNamesAreIndependentOfConnectionAndSshIdentity() {
        val named = profile.copy(displayName = "通勤号 / 内蔵", address = "aa:bb:cc:dd:ee:ff").normalized()
        assertEquals("AA:BB:CC:DD:EE:FF", named.id)
        assertEquals("AA:BB:CC:DD:EE:FF", named.address)
        assertEquals("通勤号 / 内蔵", named.displayName)
        assertEquals(profile.sshCommand(), named.sshCommand())
    }
    @Test fun localDomainAndCaseDoNotCreateDuplicateIdentities() {
        assertEquals("raspberrypi", DeviceProfile.normalizeHostname("  RASPBERRYPI.local  "))
        assertEquals("raspberrypi", DeviceProfile.normalizeHostname("raspberrypi"))
    }
    @Test fun legacyNicknameDoesNotBecomeTheDisplayedHostname() {
        val migrated = DeviceStore.fromLegacy("legacy", "raspberrypi.local", "LegacyPi", 2223)
        assertEquals("raspberrypi", migrated.hostname)
        assertEquals(2223, migrated.port)
        assertEquals("LegacyPi", migrated.legacyBluetoothName)
        assertEquals("ssh -p 2223 -o HostKeyAlias=raspberrypi.local pi@127.0.0.1", migrated.sshCommand())
        assertNull(DeviceStore.fromLegacy("legacy", "raspberrypi.local", "raspberrypi", 2222).legacyBluetoothName)
    }
    @Test fun gattReadsTheFullHostnameRatherThanATruncatedAdvertisement() {
        val hostname = "a-raspberry-pi-with-a-long-hostname"
        assertEquals(hostname, DeviceProfile.decodeHostname(hostname.toByteArray()))
        for (invalid in listOf(byteArrayOf(), "pi\u0000".toByteArray(), "pi name".toByteArray(), byteArrayOf(-1))) {
            assertThrows(IllegalArgumentException::class.java) { DeviceProfile.decodeHostname(invalid) }
        }
    }
    @Test fun termiusHostKeysHaveDistinctAutomaticallyAllocatedLocalPorts() {
        val devices = DeviceStore.uniquePorts(listOf(profile, DeviceProfile("second", "spare-pi"), DeviceProfile("third", "workstation")))
        assertEquals(listOf(2222, 2223, 2224), devices.map { it.port })
        assertEquals(devices, DeviceStore.uniquePorts(devices))
        assertEquals(listOf(3000, 2222), DeviceStore.uniquePorts(listOf(profile.copy(port = 3000),
            DeviceProfile("second", "spare-pi", 3000))).map { it.port })
    }
}
