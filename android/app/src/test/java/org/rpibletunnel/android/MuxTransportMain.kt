package org.rpibletunnel.android

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

// The same bridge can be tested against a Pi C peer without installing an APK.
object MuxTransportMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3 && (args.size == 2 || args[2] == "internet")) { "Usage: MuxTransportMain WIRE_PORT LOCAL_PORT [internet]" }
        val wire = Socket(InetAddress.getByName("127.0.0.1"), args[0].toInt()).apply { tcpNoDelay = true }
        val ended = CountDownLatch(1)
        val failure = AtomicReference<String>()
        val bridge = MuxBridge(wire.getInputStream(), wire.getOutputStream(), { wire.close() }, 127,
            args[1].toInt(), { println(it) }, { failure.set(it); ended.countDown() }, connections = { println("MUX connections=$it") },
            allowInternet = args.size == 3)
        Runtime.getRuntime().addShutdownHook(Thread { bridge.close(); bridge.awaitTermination(5000) })
        bridge.start()
        println("READY Android JVM mux 127.0.0.1:${bridge.localPort}")
        ended.await()
        check(bridge.awaitTermination(5000))
        failure.get()?.let { error(it) }
    }
}
