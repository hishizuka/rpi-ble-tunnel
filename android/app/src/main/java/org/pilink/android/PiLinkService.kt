package org.pilink.android

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

data class LinkState(val running: Boolean = false, val status: String = "停止中", val port: Int = 2222,
                     val log: String = "", val multiplex: Boolean = false, val internet: Boolean = false,
                     val mode: ConnectionMode? = null, val targetName: String = "",
                     val targetAlias: String = "", val ready: Boolean = false,
                     val retrying: Boolean = false)

class PiLinkService : Service() {
    companion object {
        const val START = "org.pilink.android.START"
        const val STOP = "org.pilink.android.STOP"
        private const val CHANNEL = "pilink-connection"
        private const val NOTIFICATION = 1
        private const val RETRY = "org.pilink.android.RETRY"
        fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    inner class LocalBinder : Binder() { val service: PiLinkService get() = this@PiLinkService }
    private val binder = LocalBinder()
    private val observers = mutableSetOf<(LinkState) -> Unit>()
    private val lines = ArrayDeque<String>()
    private var controller: LinkController? = null
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PiLink 接続", NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiLink:SSH")
        connectWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiLink:Connect")
        ContextCompat.registerReceiver(this, retryReceiver, IntentFilter(RETRY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val previous = getSharedPreferences("connection-result", MODE_PRIVATE)
        state = LinkState(status = previous.getString("status", "停止中") ?: "停止中",
            port = previous.getInt("port", 2222), log = previous.getString("log", "") ?: "",
            multiplex = previous.getBoolean("multiplex", false), internet = previous.getBoolean("internet", false))
    }

    override fun onBind(intent: Intent): IBinder = binder
    fun observe(observer: (LinkState) -> Unit) { observers.add(observer); observer(state) }
    fun removeObserver(observer: (LinkState) -> Unit) { observers.remove(observer) }
    private fun publish() { observers.toList().forEach { it(state) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopConnection("停止中")
            START -> if (!reconnect.running) {
                if (bluetoothPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    stopConnection("Bluetooth の利用許可が必要です")
                    return START_NOT_STICKY
                }
                val port = intent.getIntExtra("port", 2222)
                val name = intent.getStringExtra("name") ?: ""
                if (port !in 1024..65535 || !DeviceProfile.validHostname(name)) {
                    stopConnection("接続設定が不正です")
                    return START_NOT_STICKY
                }
                lines.clear()
                getSharedPreferences("connection-result", MODE_PRIVATE).edit().clear().apply()
                val capability = intent.getLongExtra("capability", PiLinkProfile.AUTO)
                if (capability !in listOf(PiLinkProfile.AUTO, PiLinkProfile.SSH, PiLinkProfile.MUX, PiLinkProfile.INTERNET)) {
                    stopConnection("接続設定が不正です")
                    return START_NOT_STICKY
                }
                state = LinkState(true, "接続を開始しています…", port, targetName = name,
                    targetAlias = intent.getStringExtra("host_key_alias") ?: "")
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
        state = state.copy(ready = false, retrying = false, mode = null,
            internet = false, multiplex = false,
            status = if (attemptCount == 1) "接続を開始しています…" else "再接続しています…")
        publish()
        connectWakeLock?.acquire(65_000)
        controller = LinkController(this, state.targetName, state.port,
            { message -> if (reconnect.isCurrent(token)) {
                if (message.startsWith("READY ")) releaseConnectWakeLock()
                report(message)
            } },
            { message -> retryAfterFailure(token, message) },
            { active -> if (reconnect.isCurrent(token)) setSessionActive(active) },
            config.getLongExtra("capability", PiLinkProfile.AUTO),
            { selected -> if (reconnect.isCurrent(token)) {
                state = state.copy(internet = selected == PiLinkProfile.INTERNET,
                    multiplex = selected == PiLinkProfile.INTERNET || selected == PiLinkProfile.MUX,
                    mode = ConnectionMode.fromCapability(selected))
                publish()
            } }, initialAddress = config.getStringExtra("address"),
            legacyName = config.getStringExtra("legacy_name"),
            addressSelected = { address -> if (reconnect.isCurrent(token)) request?.putExtra("address", address) })
        controller!!.start()
    }

    private fun retryAfterFailure(token: Long, message: String) {
        if (!reconnect.failed(token)) return
        controller?.stop()
        controller = null
        releaseConnectWakeLock()
        setSessionActive(false)
        state = state.copy(ready = false, retrying = true, mode = null, internet = false, multiplex = false)
        report("接続エラー: $message")
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
        Log.i("PiLink", message)
        lines.addLast("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $message")
        while (lines.size > 30) lines.removeFirst()
        val status = when {
            message.startsWith("READY ") || message.startsWith("Internet 中継中:") -> waitingStatus()
            message.startsWith("多重化 SSH 接続数:") || message.startsWith("中継接続数:") -> {
                val count = message.substringAfter(":").trim().toIntOrNull() ?: 0
                if (count == 0) waitingStatus() else if (state.internet) "SSH / Internet 中継中（$count / 8 本）" else "SSH 接続中（$count / 8 本）"
            }
            message.startsWith("GATT:") -> "Pi の接続設定を確認しています…"
            message.startsWith("Pi に接続") -> "Pi に接続しています…"
            message.startsWith("SSH セッション接続中") -> "SSH 接続中"
            message.startsWith("SSH セッション終了") -> "次の SSH 接続を準備しています…"
            message.startsWith("同時接続") -> if (state.multiplex) "SSH 接続中（同時に 8 本まで）" else "SSH 接続中（同時に 1 本まで）"
            else -> message
        }
        state = state.copy(status = status,
            ready = if (message.startsWith("READY ")) true else if (message.startsWith("SSH セッション終了")) false else state.ready,
            mode = if (message.startsWith("SSH セッション終了")) null else state.mode,
            log = lines.joinToString("\n"))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state.status))
        publish()
    }

    private fun waitingStatus() = when {
        state.internet -> "接続済み · SSH / Internet 待受中"
        state.multiplex -> "接続済み · SSH 待受中（最大 8 本）"
        else -> "接続済み · SSH 待受中"
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
        val stop = PendingIntent.getService(this, 1, Intent(this, PiLinkService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link).setContentTitle("PiLink")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    private fun stopConnection(status: String) {
        reconnect.stop()
        request = null
        controller?.stop()
        controller = null
        setSessionActive(false)
        releaseConnectWakeLock()
        state = state.copy(running = false, status = status, mode = null, ready = false, retrying = false)
        // Retain the failure message when an unbound background service is destroyed.
        getSharedPreferences("connection-result", MODE_PRIVATE).edit().putString("status", status)
            .putInt("port", state.port).putString("log", state.log).putBoolean("multiplex", state.multiplex)
            .putBoolean("internet", state.internet).apply()
        Log.i("PiLink", status)
        publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
