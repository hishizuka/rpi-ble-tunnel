package org.rpibletunnel.android

import java.util.Locale

data class DeviceProfile(val id: String, val hostname: String, val port: Int = 2222,
                         val address: String? = null, val legacyBluetoothName: String? = null,
                         val displayName: String = hostname) {
    companion object {
        private val hostnamePattern = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
        private val addressPattern = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        fun normalizeHostname(value: String): String = value.trim().lowercase(Locale.ROOT).removeSuffix(".local")
        fun validHostname(value: String): Boolean = value.length in 1..63 &&
            hostnamePattern.matches(value)
        fun normalizeAddress(value: String): String = value.trim().uppercase(Locale.ROOT).also {
            require(validAddress(it))
        }
        fun validAddress(value: String): Boolean = addressPattern.matches(value)
        fun decodeHostname(bytes: ByteArray): String {
            require(bytes.all { it.toInt() in 0x21..0x7e }) { "Invalid Pi hostname" }
            return normalizeHostname(bytes.toString(Charsets.US_ASCII)).also {
                require(validHostname(it)) { "Invalid Pi hostname" }
            }
        }
    }

    fun validated(): DeviceProfile {
        require(id.isNotBlank())
        require(validHostname(hostname))
        require(port in 1024..65535)
        require(address == null || validAddress(address))
        require(displayName.isNotBlank())
        require(legacyBluetoothName == null || legacyBluetoothName.length in 1..64)
        return this
    }

    fun normalized(): DeviceProfile {
        val canonicalAddress = address?.let(::normalizeAddress)
        // Keep unresolved legacy entries until the user selects their adapter.
        return copy(id = canonicalAddress ?: id, address = canonicalAddress,
            displayName = displayName.trim()).validated()
    }

    fun sshCommand(localPort: Int = port): String {
        validated()
        require(localPort in 1024..65535)
        // Preserve the .local identity already used by OpenSSH's known_hosts.
        return "ssh -p $localPort -o HostKeyAlias=$hostname.local pi@127.0.0.1"
    }
}
