package org.rpibletunnel.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import java.util.UUID
import kotlin.concurrent.thread

// The activity grants runtime permissions before this connection is created.
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val handler: Handler,
    private val targetName: String,
    private val targetAddress: String?,
    private val report: (String) -> Unit,
    private val ready: (RpiBleTunnelProfile, String) -> Unit,
    private val failure: (String) -> Unit,
    private val requiredCapability: Long = RpiBleTunnelProfile.SSH,
    private val legacyName: String? = null
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanning = false
    private var stopped = false
    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null
    private var profile: RpiBleTunnelProfile? = null
    private var readIndex = 0
    private var readOrder = RpiBleTunnelProfile.READ_ORDER
    private var advertisedName: String? = null
    private val excluded = mutableSetOf<String>()
    private val values = mutableMapOf<UUID, ByteArray>()
    private var stage = "BLE connection"
    private val timeout = Runnable { fail("Timeout: $stage") }
    private val powerPolicy = BlePowerPolicy { lowPower ->
        val priority = if (lowPower) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        val label = if (lowPower) "LOW_POWER" else "BALANCED"
        val accepted = runCatching { gatt?.requestConnectionPriority(priority) == true }
        report("BLE priority: $label ${if (accepted.getOrDefault(false)) "requested" else "not accepted"}")
        accepted.getOrDefault(false)
    }

    fun setPowerSaving(enabled: Boolean, busy: Boolean) { powerPolicy.update(enabled, busy) }

    private fun onMain(action: () -> Unit) {
        handler.post {
            if (!stopped) {
                try { action() } catch (error: Exception) { fail(error.message ?: "Bluetooth error") }
            }
        }
    }

    fun start() {
        try {
            require(adapter?.isEnabled == true) { "Turn on Bluetooth" }
            handler.postDelayed(timeout, 30000)
            if (targetAddress != null) {
                connect(adapter.getRemoteDevice(targetAddress))
            } else {
                startScan()
            }
        } catch (error: Exception) { fail(error.message ?: "Could not start Bluetooth") }
    }

    private fun startScan() {
        stage = "rpi-ble-tunnel discovery"
        report("Searching for rpi-ble-tunnel…")
        val scanner = adapter?.bluetoothLeScanner ?: error("BLE scanner unavailable")
        scanning = true
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RpiBleTunnelProfile.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onMain {
            val advertised = result.scanRecord?.deviceName ?: result.device.name
            if (device == null && result.device.address !in excluded && (advertised == targetName || (legacyName != null && advertised == legacyName) ||
                (!advertised.isNullOrEmpty() && targetName.startsWith(advertised)))) {
                advertisedName = advertised
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) = onMain { fail("BLE scan failed: $errorCode") }
    }

    private fun connect(selected: BluetoothDevice) {
        stopScan()
        device = selected
        stage = "GATT connection"
        report("Pi connecting: ${selected.address}")
        gatt = selected.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: error("Could not start GATT")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (gatt === current) {
                if (status != BluetoothGatt.GATT_SUCCESS) fail("GATT connection error: $status")
                else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stage = "Service discovery"
                    check(current.discoverServices()) { "Could not start service discovery" }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) fail("Pi BLE connection disconnected")
            }
        }

        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) = onMain {
            if (gatt === current) {
                check(status == BluetoothGatt.GATT_SUCCESS) { "Service discovery error: $status" }
                val service = current.getService(RpiBleTunnelProfile.SERVICE) ?: error("rpi-ble-tunnel service missing")
                for (uuid in RpiBleTunnelProfile.READ_ORDER) {
                    check(service.getCharacteristic(uuid) != null) { "GATT characteristic missing: $uuid" }
                }
                readOrder = RpiBleTunnelProfile.READ_ORDER + if (service.getCharacteristic(RpiBleTunnelProfile.HOSTNAME) != null)
                    listOf(RpiBleTunnelProfile.HOSTNAME) else emptyList()
                stage = "Reading GATT settings"
                readNext()
            }
        }

        @Deprecated("Used on Android 10–12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) receive(current, characteristic.uuid, characteristic.value?.clone() ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            receive(current, characteristic.uuid, value.clone(), status)
        }
    }

    private fun receive(current: BluetoothGatt, uuid: UUID, bytes: ByteArray, status: Int) = onMain {
        if (gatt === current && readIndex < readOrder.size) {
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT read error: $status" }
            check(uuid == readOrder[readIndex]) { "Unexpected GATT response order" }
            values[uuid] = bytes
            readIndex++
            readNext()
        }
    }

    private fun readNext() {
        if (readIndex == readOrder.size) {
            val hostname = values[RpiBleTunnelProfile.HOSTNAME]?.let { DeviceProfile.decodeHostname(it) }
            // A shortened advertisement is only a candidate, never the identity.
            if ((hostname != null && hostname != targetName) || (hostname == null && targetAddress == null &&
                advertisedName != targetName && advertisedName != legacyName)) {
                excluded.add(device!!.address)
                val previous = gatt
                gatt = null; device = null; values.clear(); readIndex = 0
                powerPolicy.disconnected()
                previous?.disconnect(); previous?.close()
                startScan()
                return
            }
            val decoded = RpiBleTunnelProfile.decode(values, requiredCapability)
            profile = decoded
            handler.removeCallbacks(timeout)
            powerPolicy.connected()
            report("GATT: version=${decoded.version}, PSM=${decoded.psm}, capabilities=${decoded.capabilities}")
            ready(decoded, device!!.address)
            return
        }
        val current = gatt ?: error("No GATT connection")
        val characteristic = current.getService(RpiBleTunnelProfile.SERVICE).getCharacteristic(readOrder[readIndex])
        check(current.readCharacteristic(characteristic)) { "Could not start GATT read" }
    }

    fun openChannel(connected: (BluetoothSocket) -> Unit) {
        if (stopped || socket != null) return
        try {
            stage = "L2CAP connection"
            val selected = device ?: error("Pi is not connected")
            val decoded = profile ?: error("GATT settings unavailable")
            val channel = selected.createInsecureL2capChannel(decoded.psm)
            socket = channel
            handler.postDelayed(timeout, 30000)
            thread(name = "rpi-ble-tunnel-l2cap-connect", isDaemon = true) {
                try {
                    channel.connect()
                    onMain {
                        if (socket === channel) {
                            handler.removeCallbacks(timeout)
                            connected(channel)
                        }
                    }
                } catch (error: Exception) { onMain { fail("L2CAP connection failed: ${error.message}") } }
            }
        } catch (error: Exception) { fail(error.message ?: "Could not start L2CAP") }
    }

    private fun stopScan() {
        if (scanning) {
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun fail(message: String) {
        if (stopped) return
        close()
        failure(message)
    }

    fun close() {
        if (stopped) return
        stopped = true
        powerPolicy.disconnected()
        handler.removeCallbacks(timeout)
        runCatching { stopScan() }
        runCatching { socket?.close() }
        socket = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
