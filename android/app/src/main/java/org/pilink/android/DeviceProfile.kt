package org.pilink.android

import java.util.Locale

data class DeviceProfile(val id: String, val hostname: String, val port: Int = 2222,
                         val address: String? = null, val legacyBluetoothName: String? = null) {
    companion object {
        fun normalizeHostname(value: String): String = value.trim().lowercase(Locale.ROOT).removeSuffix(".local")
        fun validHostname(value: String): Boolean = value.length in 1..63 &&
            Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(value)
        fun decodeHostname(bytes: ByteArray): String {
            require(bytes.all { it.toInt() in 0x21..0x7e }) { "Piのホスト名が不正です" }
            return normalizeHostname(bytes.toString(Charsets.US_ASCII)).also {
                require(validHostname(it)) { "Piのホスト名が不正です" }
            }
        }
    }

    fun validated(): DeviceProfile {
        require(id.isNotBlank())
        require(validHostname(hostname) && normalizeHostname(hostname) == hostname)
        require(port in 1024..65535)
        require(address == null || Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(address))
        require(legacyBluetoothName == null || legacyBluetoothName.length in 1..64)
        return this
    }

    fun sshCommand(localPort: Int = port): String {
        validated()
        require(localPort in 1024..65535)
        // Preserve the .local identity already used by OpenSSH's known_hosts.
        return "ssh -p $localPort -o HostKeyAlias=$hostname.local pi@127.0.0.1"
    }
}
