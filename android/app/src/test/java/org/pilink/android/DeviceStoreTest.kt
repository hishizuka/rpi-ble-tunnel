package org.pilink.android

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceStoreTest {
    private val firstAddress = "AA:BB:CC:DD:EE:01"
    private val secondAddress = "AA:BB:CC:DD:EE:02"
    private fun profile(address: String) = DeviceProfile(address, "test-pi", address = address, displayName = "表示名")

    private fun preferences(values: MutableMap<String, String?> = mutableMapOf()): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String?; proxy }
                "apply" -> null
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun sameHostnameAndDisplayNameKeepDistinctAdaptersAndSelectionAfterReload() {
        val prefs = preferences()
        val store = DeviceStore(prefs)
        store.save(profile(firstAddress))
        store.save(profile(secondAddress))
        val restored = DeviceStore(prefs)
        assertEquals(listOf(firstAddress, secondAddress), restored.devices.map { it.id })
        assertEquals(listOf("表示名", "表示名"), restored.devices.map { it.displayName })
        assertEquals(listOf(2222, 2223), restored.devices.map { it.port })
        assertEquals(secondAddress, restored.selected?.address)
        restored.select(firstAddress)
        assertEquals(firstAddress, DeviceStore(prefs).selected?.address)
    }

    @Test fun editingOrDeletingOneDuplicateNameDoesNotChangeTheOtherAdapter() {
        val store = DeviceStore(preferences())
        store.save(profile(firstAddress))
        store.save(profile(secondAddress))
        store.save(store.devices.first().copy(displayName = "外付け"))
        assertEquals("表示名", store.devices[1].displayName)
        store.remove(secondAddress)
        assertEquals(listOf(firstAddress), store.devices.map { it.id })
        assertEquals(firstAddress, store.selectedId)
    }

    @Test fun addressesAreCaseInsensitiveAndCannotReplaceAnotherRegisteredAdapter() {
        val store = DeviceStore(preferences())
        store.save(profile(firstAddress))
        store.save(profile(firstAddress.lowercase()).copy(displayName = "名前変更"))
        assertEquals(1, store.devices.size)
        assertEquals(firstAddress, store.selected?.address)
        store.save(profile(secondAddress))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(store.devices.first().copy(address = secondAddress))
        }
        assertEquals(listOf(firstAddress, secondAddress), store.devices.map { it.address })
    }

    @Test fun existingAddressEntriesMigrateSelectionAndKeepUnresolvedLegacyEntries() {
        val rows = JSONArray().put(JSONObject().put("id", "old-id").put("hostname", "test-pi")
            .put("port", 3000).put("address", firstAddress.lowercase()))
            .put(JSONObject().put("id", "hostname-only").put("hostname", "spare-pi").put("port", 3001))
        val values = mutableMapOf<String, String?>("registered_devices" to rows.toString(), "selected_device" to "old-id")
        val prefs = preferences(values)
        val store = DeviceStore(prefs)
        assertEquals(firstAddress, store.selectedId)
        assertEquals("test-pi", store.selected?.displayName)
        assertEquals(3000, store.selected?.port)
        assertEquals("hostname-only", store.devices[1].id)
        assertNull(store.devices[1].address)
        assertEquals(store.devices, DeviceStore(prefs).devices)
        store.save(store.devices[1].copy(address = secondAddress))
        assertEquals(2, store.devices.size)
        assertEquals(secondAddress, store.selectedId)
        assertEquals("spare-pi", store.selected?.hostname)
        assertEquals(3001, store.selected?.port)
    }

    @Test fun capacityCountsAdaptersAndEditingAtCapacityStillWorks() {
        val store = DeviceStore(preferences())
        store.save(profile(firstAddress))
        store.save(profile(secondAddress))
        store.save(profile("AA:BB:CC:DD:EE:03"))
        assertThrows(IllegalArgumentException::class.java) { store.save(profile("AA:BB:CC:DD:EE:04")) }
        store.save(store.devices.first().copy(displayName = "名前変更"))
        assertEquals(3, store.devices.size)
        assertEquals("名前変更", store.selected?.displayName)
    }

    @Test fun newRegistrationRequiresAnAddress() {
        val store = DeviceStore(preferences())
        assertThrows(IllegalArgumentException::class.java) { store.save(DeviceProfile("unresolved", "test-pi")) }
        assertTrue(store.devices.isEmpty())
    }
}
