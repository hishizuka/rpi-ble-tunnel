package org.pilink.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LinkController(
    private val context: Context,
    private val targetName: String,
    private val port: Int,
    private val report: (String) -> Unit,
    private val failure: (String) -> Unit,
    private val sessionActive: (Boolean) -> Unit = {},
    private val requiredCapability: Long = PiLinkProfile.AUTO,
    private val modeSelected: (Long) -> Unit = {},
    initialAddress: String? = null,
    private val legacyName: String? = null,
    private val addressSelected: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ble: BleConnection? = null
    private var ready = false
    private var address: String? = initialAddress
    private var listener: ServerSocket? = null
    private var pending: Socket? = null
    private var session: StreamBridge? = null
    private var mux: MuxBridge? = null
    @Volatile private var stopped = false
    private var multiplex = false
    private var internet = false
    private var negotiatedCapability: Long? = null

    fun start() {
        require(port in 1024..65535) { "ポートは 1024〜65535 で指定してください" }
        connectBLE()
    }

    private fun connectBLE() {
        if (stopped) return
        ready = false
        // A retained TCP listener must keep its wire format across SSH sessions.
        val expectedCapability = negotiatedCapability ?: requiredCapability
        ble = BleConnection(context, handler, targetName, address, report, { profile, selectedAddress ->
            if (!stopped) {
                val capability = if (expectedCapability == PiLinkProfile.AUTO)
                    PiLinkProfile.selectCapability(profile.capabilities) else expectedCapability
                negotiatedCapability = capability
                internet = capability == PiLinkProfile.INTERNET
                multiplex = internet || capability == PiLinkProfile.MUX
                modeSelected(capability)
                address = selectedAddress
                addressSelected(selectedAddress)
                ready = true
                try {
                    if (multiplex || internet) openMultiplexChannel()
                    else {
                        if (listener == null) startListener()
                        report("READY 127.0.0.1:$port → BLE → Pi 127.0.0.1:22")
                        if (pending != null) openChannel()
                    }
                } catch (error: Exception) { fail(error.message ?: "TCP 待受失敗") }
            }
        }, ::fail, expectedCapability, legacyName)
        ble!!.start()
    }

    private fun startListener() {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            listener = server
        } catch (error: Exception) { server.close(); throw error }
        thread(name = "pilink-tcp-listener", isDaemon = true) {
            try {
                while (!stopped) {
                    val socket = server.accept()
                    handler.post { accept(socket) }
                }
            } catch (error: Exception) {
                handler.post { if (!stopped) fail("TCP 待受エラー: ${error.message}") }
            }
        }
    }

    private fun accept(socket: Socket) {
        if (stopped || pending != null || session != null) {
            runCatching { socket.close() }
            if (!stopped) report("同時接続は 1 本までです。追加の TCP 接続を閉じました。")
            return
        }
        try {
            socket.tcpNoDelay = true
            pending = socket
            if (ready) openChannel() else report("次の SSH セッション: BLE の再接続を待っています")
        } catch (error: Exception) {
            runCatching { socket.close() }
            fail("TCP 接続エラー: ${error.message}")
        }
    }

    private fun openChannel() {
        ble?.openChannel { channel ->
            val tcp = pending
            if (stopped || tcp == null) {
                channel.close()
            } else {
                try {
                    lateinit var bridge: StreamBridge
                    bridge = StreamBridge(tcp.getInputStream(), tcp.getOutputStream(),
                        channel.inputStream, channel.outputStream, {
                            runCatching { tcp.close() }
                            runCatching { channel.close() }
                        }, channel.maxTransmitPacketSize.coerceAtLeast(1)) { sent, received, reason ->
                        handler.post {
                            if (!stopped && session === bridge) {
                                report("SSH セッション終了: $reason、TCP→BLE=$sent、BLE→TCP=$received バイト")
                                session = null
                                sessionActive(false)
                                ready = false
                                ble?.close()
                                ble = null
                                // Re-read the profile after each session, preserving the local listener.
                                handler.postDelayed({ connectBLE() }, 500)
                            }
                        }
                    }
                    session = bridge
                    pending = null
                    report("SSH セッション接続中 (L2CAP MTU=${channel.maxTransmitPacketSize})")
                    sessionActive(true)
                    bridge.start()
                } catch (error: Exception) { fail("SSH ブリッジ開始失敗: ${error.message}") }
            }
        }
    }

    private fun openMultiplexChannel() {
        ble?.openChannel { channel ->
            if (stopped) { channel.close(); return@openChannel }
            try {
                val bridge = MuxBridge(channel.inputStream, channel.outputStream,
                    { runCatching { channel.close() } }, channel.maxTransmitPacketSize.coerceAtLeast(1), port,
                    { message -> handler.post { if (!stopped) report(message) } },
                    { message -> handler.post { fail(message) } },
                    { active -> handler.post { if (!stopped) sessionActive(active) } },
                    { count -> handler.post { if (!stopped) report(if (internet) "中継接続数: $count" else "多重化 SSH 接続数: $count") } },
                    allowInternet = internet)
                mux = bridge
                bridge.start()
                report("READY 127.0.0.1:$port → BLE mux → Pi 127.0.0.1:22（最大 8 接続）")
                if (internet) report("Internet 中継中: Pi の SOCKS5 → BLE → Android TCP / DNS")
            } catch (error: Exception) {
                runCatching { channel.close() }
                fail("多重化開始失敗: ${error.message}")
            }
        }
    }

    private fun fail(message: String) {
        if (stopped) return
        stop()
        failure(message)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { listener?.close() }
        listener = null
        runCatching { pending?.close() }
        pending = null
        session?.close()
        session = null
        mux?.close()
        mux = null
        sessionActive(false)
        ble?.close()
        ble = null
    }
}
