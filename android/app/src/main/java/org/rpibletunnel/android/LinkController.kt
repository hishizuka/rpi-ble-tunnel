package org.rpibletunnel.android

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
    private val requiredCapability: Long = RpiBleTunnelProfile.AUTO,
    private val modeSelected: (Long) -> Unit = {},
    initialAddress: String? = null,
    private val legacyName: String? = null,
    private val addressSelected: (String) -> Unit = {},
    private var powerSaving: Boolean = false
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
    private var sessionBusy = false

    fun setPowerSaving(enabled: Boolean) {
        powerSaving = enabled
        mux?.setPowerSaving(enabled)
        updateBlePriority()
    }

    private fun updateBlePriority() {
        val busy = !ready || (multiplex && mux == null) || pending != null || sessionBusy
        ble?.setPowerSaving(powerSaving, busy)
    }

    private fun updateSessionActive(active: Boolean) {
        sessionBusy = active
        updateBlePriority()
        sessionActive(active)
    }

    fun start() {
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
        connectBLE()
    }

    private fun connectBLE() {
        if (stopped) return
        ready = false
        // A retained TCP listener must keep its wire format across SSH sessions.
        val expectedCapability = negotiatedCapability ?: requiredCapability
        ble = BleConnection(context, handler, targetName, address, report, { profile, selectedAddress ->
            if (!stopped) {
                val capability = if (expectedCapability == RpiBleTunnelProfile.AUTO)
                    RpiBleTunnelProfile.selectCapability(profile.capabilities) else expectedCapability
                negotiatedCapability = capability
                internet = capability == RpiBleTunnelProfile.INTERNET
                multiplex = internet || capability == RpiBleTunnelProfile.MUX
                modeSelected(capability)
                address = selectedAddress
                addressSelected(selectedAddress)
                ready = true
                try {
                    if (multiplex) openMultiplexChannel()
                    else {
                        if (listener == null) startListener()
                        updateBlePriority()
                        report("READY 127.0.0.1:$port → BLE → Pi 127.0.0.1:22")
                        if (pending != null) openChannel()
                    }
                } catch (error: Exception) { fail(error.message ?: "TCP listener failed") }
            }
        }, ::fail, expectedCapability, legacyName)
        updateBlePriority()
        ble!!.start()
    }

    private fun startListener() {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            listener = server
        } catch (error: Exception) { server.close(); throw error }
        thread(name = "rpi-ble-tunnel-tcp-listener", isDaemon = true) {
            try {
                while (!stopped) {
                    val socket = server.accept()
                    handler.post { accept(socket) }
                }
            } catch (error: Exception) {
                handler.post { if (!stopped) fail("TCP listener error: ${error.message}") }
            }
        }
    }

    private fun accept(socket: Socket) {
        if (stopped || pending != null || session != null) {
            runCatching { socket.close() }
            if (!stopped) report("Concurrent connection limit: 1; closed the additional TCP connection")
            return
        }
        try {
            socket.tcpNoDelay = true
            pending = socket
            updateBlePriority()
            if (ready) openChannel() else report("Waiting for BLE to reconnect for the next SSH session")
        } catch (error: Exception) {
            runCatching { socket.close() }
            fail("TCP connection error: ${error.message}")
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
                                report("SSH session closed: $reason, TCP→BLE=$sent, BLE→TCP=$received bytes")
                                session = null
                                ready = false
                                updateSessionActive(false)
                                ble?.close()
                                ble = null
                                // Re-read the profile after each session, preserving the local listener.
                                handler.postDelayed({ connectBLE() }, 500)
                            }
                        }
                    }
                    session = bridge
                    pending = null
                    report("SSH session connected (L2CAP MTU=${channel.maxTransmitPacketSize})")
                    updateSessionActive(true)
                    bridge.start()
                } catch (error: Exception) { fail("SSH bridge failed: ${error.message}") }
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
                    { active -> handler.post { if (!stopped) updateSessionActive(active) } },
                    { count -> handler.post { if (!stopped) report(if (internet) "Relay streams: $count" else "SSH streams: $count") } },
                    allowInternet = internet, powerSaving = powerSaving)
                mux = bridge
                bridge.start()
                updateBlePriority()
                report("READY 127.0.0.1:$port → BLE mux → Pi 127.0.0.1:22 (up to 8 connections)")
                if (internet) report("Internet relay: Pi SOCKS5 → BLE → Android TCP / DNS")
            } catch (error: Exception) {
                runCatching { channel.close() }
                fail("Multiplex startup failed: ${error.message}")
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
