package org.pilink.android

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
    private val ready: (PiLinkProfile, String) -> Unit,
    private val failure: (String) -> Unit,
    private val requiredCapability: Long = PiLinkProfile.SSH,
    private val legacyName: String? = null
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanning = false
    private var stopped = false
    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null
    private var profile: PiLinkProfile? = null
    private var readIndex = 0
    private var readOrder = PiLinkProfile.READ_ORDER
    private var advertisedName: String? = null
    private val excluded = mutableSetOf<String>()
    private val values = mutableMapOf<UUID, ByteArray>()
    private var stage = "BLE 接続"
    private val timeout = Runnable { fail("タイムアウト: $stage") }

    private fun onMain(action: () -> Unit) {
        handler.post {
            if (!stopped) {
                try { action() } catch (error: Exception) { fail(error.message ?: "Bluetooth エラー") }
            }
        }
    }

    fun start() {
        try {
            require(adapter?.isEnabled == true) { "Bluetooth を ON にしてください" }
            handler.postDelayed(timeout, 30000)
            if (targetAddress != null) {
                connect(adapter.getRemoteDevice(targetAddress))
            } else {
                startScan()
            }
        } catch (error: Exception) { fail(error.message ?: "Bluetooth を開始できません") }
    }

    private fun startScan() {
        stage = "PiLink の検索"
        report("PiLink を検索しています…")
        val scanner = adapter?.bluetoothLeScanner ?: error("BLE scanner がありません")
        scanning = true
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(PiLinkProfile.SERVICE)).build()),
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
        override fun onScanFailed(errorCode: Int) = onMain { fail("BLE scan 失敗: $errorCode") }
    }

    private fun connect(selected: BluetoothDevice) {
        stopScan()
        device = selected
        stage = "GATT 接続"
        report("Pi に接続しています… (${selected.address})")
        gatt = selected.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: error("GATT を開始できません")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (gatt === current) {
                if (status != BluetoothGatt.GATT_SUCCESS) fail("GATT 接続エラー: $status")
                else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stage = "Service discovery"
                    check(current.discoverServices()) { "Service discovery を開始できません" }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) fail("Pi の BLE 接続が切れました")
            }
        }

        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) = onMain {
            if (gatt === current) {
                check(status == BluetoothGatt.GATT_SUCCESS) { "Service discovery エラー: $status" }
                val service = current.getService(PiLinkProfile.SERVICE) ?: error("PiLink Service がありません")
                for (uuid in PiLinkProfile.READ_ORDER) {
                    check(service.getCharacteristic(uuid) != null) { "GATT characteristic がありません: $uuid" }
                }
                readOrder = PiLinkProfile.READ_ORDER + if (service.getCharacteristic(PiLinkProfile.HOSTNAME) != null)
                    listOf(PiLinkProfile.HOSTNAME) else emptyList()
                stage = "GATT 設定の読み取り"
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
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT 読み取りエラー: $status" }
            check(uuid == readOrder[readIndex]) { "GATT 応答の順序が不正です" }
            values[uuid] = bytes
            readIndex++
            readNext()
        }
    }

    private fun readNext() {
        if (readIndex == readOrder.size) {
            val hostname = values[PiLinkProfile.HOSTNAME]?.let { DeviceProfile.decodeHostname(it) }
            // A shortened advertisement is only a candidate, never the identity.
            if ((hostname != null && hostname != targetName) || (hostname == null && targetAddress == null &&
                advertisedName != targetName && advertisedName != legacyName)) {
                excluded.add(device!!.address)
                val previous = gatt
                gatt = null; device = null; values.clear(); readIndex = 0
                previous?.disconnect(); previous?.close()
                startScan()
                return
            }
            val decoded = PiLinkProfile.decode(values, requiredCapability)
            profile = decoded
            handler.removeCallbacks(timeout)
            report("GATT: version=${decoded.version}, PSM=${decoded.psm}, capabilities=${decoded.capabilities}")
            ready(decoded, device!!.address)
            return
        }
        val current = gatt ?: error("GATT 接続がありません")
        val characteristic = current.getService(PiLinkProfile.SERVICE).getCharacteristic(readOrder[readIndex])
        check(current.readCharacteristic(characteristic)) { "GATT read を開始できません" }
    }

    fun openChannel(connected: (BluetoothSocket) -> Unit) {
        if (stopped || socket != null) return
        try {
            stage = "L2CAP 接続"
            val selected = device ?: error("Pi が未接続です")
            val decoded = profile ?: error("GATT が未取得です")
            val channel = selected.createInsecureL2capChannel(decoded.psm)
            socket = channel
            handler.postDelayed(timeout, 30000)
            thread(name = "pilink-l2cap-connect", isDaemon = true) {
                try {
                    channel.connect()
                    onMain {
                        if (socket === channel) {
                            handler.removeCallbacks(timeout)
                            connected(channel)
                        }
                    }
                } catch (error: Exception) { onMain { fail("L2CAP 接続失敗: ${error.message}") } }
            }
        } catch (error: Exception) { fail(error.message ?: "L2CAP を開始できません") }
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
        handler.removeCallbacks(timeout)
        runCatching { stopScan() }
        runCatching { socket?.close() }
        socket = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
