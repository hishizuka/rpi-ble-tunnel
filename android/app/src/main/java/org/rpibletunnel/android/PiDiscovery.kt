package org.rpibletunnel.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque

// Registration reads identity only; it never opens a tunnel or starts SSH.
@SuppressLint("MissingPermission")
class PiDiscovery(private val context: Context, private val found: (String, String) -> Unit,
                  private val finished: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val seen = mutableSetOf<String>()
    private val queue = ArrayDeque<BluetoothDevice>()
    private var current: BluetoothGatt? = null
    private var stopped = false
    private var scanning = false
    private var oldDaemonFound = false
    private val timeout = Runnable {
        stop()
        finished(context.getString(if (oldDaemonFound) R.string.pi_update_required else R.string.pi_not_found))
    }
    private val readTimeout = Runnable { releaseCurrent() }

    fun start() {
        try {
            check(adapter?.isEnabled == true) { context.getString(R.string.bluetooth_off) }
            val scanner = adapter.bluetoothLeScanner ?: error(context.getString(R.string.pi_scan_failed))
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RpiBleTunnelProfile.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            scanning = true
            handler.postDelayed(timeout, 15000)
        } catch (error: Exception) { stop(); finished(error.message ?: context.getString(R.string.pi_scan_failed)) }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!stopped && seen.add(result.device.address)) {
                    queue.add(result.device)
                    readNextDevice()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { if (!stopped) { stop(); finished(context.getString(R.string.pi_scan_failed)) } }
        }
    }

    private fun readNextDevice() {
        if (stopped || current != null || queue.isEmpty()) return
        val device = queue.removeFirst()
        try {
            current = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (current == null) readNextDevice() else handler.postDelayed(readTimeout, 4000)
        } catch (_: Exception) { releaseCurrent() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (stopped || current !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) releaseCurrent()
                else if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices()) releaseCurrent()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (stopped || current !== gatt) return@post
                val hostname = gatt.getService(RpiBleTunnelProfile.SERVICE)?.getCharacteristic(RpiBleTunnelProfile.HOSTNAME)
                if (status != BluetoothGatt.GATT_SUCCESS) releaseCurrent()
                else if (hostname == null) { oldDaemonFound = true; releaseCurrent() }
                else if (!gatt.readCharacteristic(hostname)) releaseCurrent()
            }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) receive(gatt, characteristic, characteristic.value?.clone() ?: byteArrayOf(), status)
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            receive(gatt, characteristic, value.clone(), status)
        }
    }

    private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        handler.post {
            if (stopped || current !== gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == RpiBleTunnelProfile.HOSTNAME) {
                val name = runCatching { DeviceProfile.decodeHostname(value) }.getOrNull()
                if (name != null) found(name, gatt.device.address)
            }
            releaseCurrent()
        }
    }

    private fun releaseCurrent() {
        handler.removeCallbacks(readTimeout)
        val previous = current
        current = null
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
        readNextDevice()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(readTimeout)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        queue.clear()
        releaseCurrent()
    }
}
