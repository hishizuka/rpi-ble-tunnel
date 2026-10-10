package org.rpibletunnel.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat

data class LinkState(val running: Boolean = false, val status: String = "", val port: Int = 2222,
                     val log: String = "", val multiplex: Boolean = false, val internet: Boolean = false,
                     val mode: ConnectionMode? = null, val targetName: String = "",
                     val ready: Boolean = false,
                     val retrying: Boolean = false, val targetAddress: String? = null,
                     val targetLabel: String = "", val failed: Boolean = false)

class RpiBleTunnelService : Service() {
    companion object {
        const val START = "org.rpibletunnel.android.START"
        const val STOP = "org.rpibletunnel.android.STOP"
        private const val CHANNEL = "rpi-ble-tunnel-connection"
        private const val NOTIFICATION = 1
        private const val RETRY = "org.rpibletunnel.android.RETRY"
        fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    inner class LocalBinder : Binder() { val service: RpiBleTunnelService get() = this@RpiBleTunnelService }
    private val binder = LocalBinder()
    private val observers = mutableSetOf<(LinkState) -> Unit>()
    private val lines = ArrayDeque<String>()
    private var controller: LinkController? = null
    private lateinit var settings: AppSettings
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.POWER_SAVING) controller?.setPowerSaving(settings.powerSaving)
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCallback: Runnable? = null
    private var retryAlarm: PendingIntent? = null
    private var request: Intent? = null
    private var attemptCount = 0
    private val reconnect = ReconnectLoop(::scheduleRetry, ::cancelRetry, ::startAttempt)
    private val retryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reconnect.retry(intent.getLongExtra("generation", -1))
        }
    }
    var state = LinkState()
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        settings.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rpi-ble-tunnel:SSH")
        connectWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rpi-ble-tunnel:Connect")
        ContextCompat.registerReceiver(this, retryReceiver, IntentFilter(RETRY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val previous = getSharedPreferences("connection-result", MODE_PRIVATE)
        val failed = previous.getBoolean("failed", false)
        state = LinkState(status = if (failed) previous.getString("status", null) ?: getString(R.string.connection_error)
            else getString(R.string.stopped), failed = failed,
            port = previous.getInt("port", 2222), log = previous.getString("log", "") ?: "",
            multiplex = previous.getBoolean("multiplex", false), internet = previous.getBoolean("internet", false))
    }

    override fun onBind(intent: Intent): IBinder = binder
    fun observe(observer: (LinkState) -> Unit) { observers.add(observer); observer(state) }
    fun removeObserver(observer: (LinkState) -> Unit) { observers.remove(observer) }
    private fun publish() { observers.toList().forEach { it(state) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopConnection(getString(R.string.stopped))
            START -> if (!reconnect.running) {
                if (bluetoothPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    stopConnection(getString(R.string.bluetooth_permission), failed = true)
                    return START_NOT_STICKY
                }
                val port = intent.getIntExtra("port", 2222)
                val name = intent.getStringExtra("name") ?: ""
                val address = intent.getStringExtra("address")
                if (port !in 1024..65535 || !DeviceProfile.validHostname(name) ||
                    (address == null && !BuildConfig.DEBUG) || (address != null && !DeviceProfile.validAddress(address))) {
                    stopConnection(getString(R.string.invalid_connection_settings), failed = true)
                    return START_NOT_STICKY
                }
                lines.clear()
                getSharedPreferences("connection-result", MODE_PRIVATE).edit().clear().apply()
                val capability = intent.getLongExtra("capability", RpiBleTunnelProfile.AUTO)
                if (capability !in listOf(RpiBleTunnelProfile.AUTO, RpiBleTunnelProfile.SSH, RpiBleTunnelProfile.MUX, RpiBleTunnelProfile.INTERNET)) {
                    stopConnection(getString(R.string.invalid_connection_settings), failed = true)
                    return START_NOT_STICKY
                }
                state = LinkState(true, getString(R.string.starting_connection), port, targetName = name,
                    targetAddress = address?.let(DeviceProfile::normalizeAddress),
                    targetLabel = intent.getStringExtra("display_name") ?: name)
                startForeground(NOTIFICATION, notification(state.status), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                request = Intent(intent)
                attemptCount = 0
                reconnect.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAttempt(token: Long) {
        val config = request ?: return
        attemptCount++
        state = state.copy(ready = false, retrying = false, failed = false, mode = null,
            internet = false, multiplex = false,
            status = getString(if (attemptCount == 1) R.string.starting_connection else R.string.reconnecting))
        publish()
        connectWakeLock?.acquire(65_000)
        controller = LinkController(this, state.targetName, state.port,
            { message -> if (reconnect.isCurrent(token)) {
                if (message.startsWith("READY ")) releaseConnectWakeLock()
                report(message)
            } },
            { message -> retryAfterFailure(token, message) },
            { active -> if (reconnect.isCurrent(token)) setSessionActive(active) },
            config.getLongExtra("capability", RpiBleTunnelProfile.AUTO),
            { selected -> if (reconnect.isCurrent(token)) {
                state = state.copy(internet = selected == RpiBleTunnelProfile.INTERNET,
                    multiplex = selected == RpiBleTunnelProfile.INTERNET || selected == RpiBleTunnelProfile.MUX,
                    mode = ConnectionMode.fromCapability(selected))
                publish()
            } }, initialAddress = config.getStringExtra("address"),
            legacyName = config.getStringExtra("legacy_name"),
            addressSelected = { address -> if (reconnect.isCurrent(token)) {
                request?.putExtra("address", address)
                state = state.copy(targetAddress = address)
                publish()
            } }, powerSaving = settings.powerSaving)
        controller!!.start()
    }

    private fun retryAfterFailure(token: Long, message: String) {
        if (!reconnect.failed(token)) return
        controller?.stop()
        controller = null
        releaseConnectWakeLock()
        setSessionActive(false)
        state = state.copy(ready = false, retrying = true, failed = true, mode = null, internet = false, multiplex = false)
        report("Connection error: $message")
        report(getString(R.string.reconnect_wait))
    }

    private fun scheduleRetry(token: Long, delay: Long) {
        cancelRetry()
        // A main-thread timer is prompt while awake; the alarm can also wake a sleeping device.
        retryCallback = Runnable { reconnect.retry(token) }.also { handler.postDelayed(it, delay) }
        retryAlarm = PendingIntent.getBroadcast(this, 0,
            Intent(RETRY).setPackage(packageName).putExtra("generation", token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, retryAlarm!!)
    }

    private fun cancelRetry() {
        retryCallback?.let { handler.removeCallbacks(it) }
        retryCallback = null
        retryAlarm?.let { getSystemService(AlarmManager::class.java).cancel(it) }
        retryAlarm = null
    }

    private fun releaseConnectWakeLock() {
        connectWakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun report(message: String) {
        Log.i("rpi-ble-tunnel", message)
        lines.addLast("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $message")
        while (lines.size > 30) lines.removeFirst()
        val status = when {
            message.startsWith("BLE priority:") -> state.status
            message.startsWith("READY ") || message.startsWith("Internet relay:") -> waitingStatus()
            message.startsWith("SSH streams:") || message.startsWith("Relay streams:") -> {
                val count = message.substringAfter(":").trim().toIntOrNull() ?: 0
                if (count == 0) waitingStatus() else getString(if (state.internet) R.string.internet_streams else R.string.ssh_streams, count)
            }
            message.startsWith("GATT:") -> getString(R.string.checking_pi_settings)
            message.startsWith("Pi connecting:") -> getString(R.string.connecting_pi)
            message.startsWith("Searching for rpi-ble-tunnel") -> getString(R.string.discovering_pi)
            message.startsWith("SSH session connected") -> getString(R.string.ssh_connected)
            message.startsWith("SSH session closed:") -> getString(R.string.preparing_ssh)
            message.startsWith("Waiting for BLE") -> getString(R.string.waiting_ble)
            message.startsWith("Concurrent connection limit") -> getString(R.string.ssh_connection_limit, if (state.multiplex) 8 else 1)
            message.startsWith("Connection error:") -> getString(R.string.connection_failure, message.substringAfter(":").trim())
            else -> message
        }
        state = state.copy(status = status,
            ready = if (message.startsWith("READY ")) true else if (message.startsWith("SSH session closed:")) false else state.ready,
            mode = if (message.startsWith("SSH session closed:")) null else state.mode,
            log = lines.joinToString("\n"))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state.status))
        publish()
    }

    private fun waitingStatus() = when {
        state.internet -> getString(R.string.waiting_internet)
        state.multiplex -> getString(R.string.waiting_mux)
        else -> getString(R.string.waiting_ssh)
    }

    // A wake lock covers active SSH/Internet streams and the final wire write.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun setSessionActive(active: Boolean) {
        val lock = wakeLock ?: return
        if (active && !lock.isHeld) lock.acquire()
        else if (!active && lock.isHeld) lock.release()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RpiBleTunnelService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build()
    }

    private fun stopConnection(status: String, failed: Boolean = false) {
        reconnect.stop()
        request = null
        controller?.stop()
        controller = null
        setSessionActive(false)
        releaseConnectWakeLock()
        state = state.copy(running = false, status = status, mode = null, ready = false, retrying = false, failed = failed)
        // Retain the failure message when an unbound background service is destroyed.
        getSharedPreferences("connection-result", MODE_PRIVATE).edit().putString("status", status)
            .putInt("port", state.port).putString("log", state.log).putBoolean("multiplex", state.multiplex)
            .putBoolean("internet", state.internet).putBoolean("failed", failed).apply()
        Log.i("rpi-ble-tunnel", status)
        publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        settings.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        reconnect.stop()
        request = null
        controller?.stop()
        setSessionActive(false)
        releaseConnectWakeLock()
        unregisterReceiver(retryReceiver)
        observers.clear()
        super.onDestroy()
    }
}
