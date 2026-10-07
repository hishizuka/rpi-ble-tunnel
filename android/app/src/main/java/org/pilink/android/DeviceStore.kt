package org.pilink.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class DeviceStore(private val preferences: SharedPreferences) {
    companion object {
        const val LIMIT = 3
        fun fromLegacy(id: String, hostKeyAlias: String, bluetoothName: String, port: Int): DeviceProfile {
            val hostname = DeviceProfile.normalizeHostname(hostKeyAlias)
            return DeviceProfile(id, hostname, port,
                legacyBluetoothName = bluetoothName.takeIf { DeviceProfile.normalizeHostname(it) != hostname }).validated()
        }
        fun uniquePorts(devices: List<DeviceProfile>): List<DeviceProfile> {
            val used = mutableSetOf<Int>()
            return devices.map { profile ->
                val port = if (profile.port !in used) profile.port else (2222..65535).first { it !in used }
                used.add(port)
                profile.copy(port = port)
            }
        }
    }

    var devices: List<DeviceProfile> = load()
        private set
    var selectedId: String? = preferences.getString("selected_device", null)?.takeIf { id -> devices.any { it.id == id } }
        ?: devices.firstOrNull()?.id
        private set
    val selected: DeviceProfile? get() = devices.firstOrNull { it.id == selectedId }

    private fun load(): List<DeviceProfile> {
        val saved = preferences.getString("registered_devices", null)
        if (saved != null) return runCatching {
            val array = JSONArray(saved)
            val loaded = List(array.length().coerceAtMost(LIMIT)) { index ->
                val row = array.getJSONObject(index)
                if (row.has("hostname")) DeviceProfile(row.getString("id"), row.getString("hostname"), row.getInt("port"),
                    row.optString("address").takeIf { it.isNotBlank() },
                    row.optString("legacyBluetoothName").takeIf { it.isNotBlank() }).validated()
                else fromLegacy(row.getString("id"), row.getString("hostKeyAlias"), row.getString("bluetoothName"), row.getInt("port"))
            }
            uniquePorts(loaded).also { persist(it, preferences.getString("selected_device", null)) }
        }.getOrDefault(emptyList())
        // A Bluetooth nickname alone cannot establish the Pi's actual hostname.
        return emptyList()
    }

    fun select(id: String) {
        require(devices.any { it.id == id })
        selectedId = id
        persist(devices, selectedId)
    }

    fun save(profile: DeviceProfile) {
        profile.validated()
        require(devices.none { it.id != profile.id && it.hostname == profile.hostname })
        val exists = devices.any { it.id == profile.id }
        require(exists || devices.size < LIMIT)
        devices = uniquePorts(if (exists) devices.map { if (it.id == profile.id) profile else it } else devices + profile)
        selectedId = profile.id
        persist(devices, selectedId)
    }

    fun remove(id: String) {
        devices = devices.filterNot { it.id == id }
        if (selectedId == id) selectedId = devices.firstOrNull()?.id
        persist(devices, selectedId)
    }

    private fun persist(devices: List<DeviceProfile>, selectedId: String?) {
        val array = JSONArray()
        devices.forEach { profile -> array.put(JSONObject().apply {
            put("id", profile.id); put("hostname", profile.hostname); put("port", profile.port)
            profile.address?.let { put("address", it) }
            profile.legacyBluetoothName?.let { put("legacyBluetoothName", it) }
        }) }
        preferences.edit().putString("registered_devices", array.toString()).putString("selected_device", selectedId).apply()
    }
}
