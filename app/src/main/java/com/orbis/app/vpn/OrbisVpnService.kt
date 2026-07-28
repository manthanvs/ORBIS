package com.orbis.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.orbis.app.usage.TargetApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Relays short-form video traffic so it can be slowed down.
 *
 * Scope is deliberately tiny, because a VPN that misbehaves takes the user's
 * connectivity with it:
 *
 *  - `addAllowedApplication` limits the tunnel to Instagram, YouTube and
 *    Snapchat. **WhatsApp's packets never enter this service at all** - enforced
 *    by the OS, not by logic in here.
 *  - Both IPv4 and IPv6 UDP are relayed. Handling only IPv4 is not a partial
 *    implementation but a broken one: the tunnel captures IPv6 too, so anything
 *    unhandled is silently blackholed rather than merely un-throttled.
 *  - TCP is counted and dropped. That is survivable only because the tunnel is
 *    meant to be up solely while a throttled surface is on screen.
 *
 * The delay is applied by *scheduling* each packet, never by sleeping in the read
 * loop - blocking there would serialise every flow and turn a 120 ms delay into a
 * near-total stall.
 */
class OrbisVpnService : VpnService() {

    private class Flow(
        val socket: DatagramSocket,
        val buildReply: (ByteArray) -> ByteArray,
    )

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var scheduler: ScheduledExecutorService? = null
    private val flows = ConcurrentHashMap<String, Flow>()

    @Volatile
    private var active = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                delayMillis = intent?.getLongExtra(EXTRA_DELAY_MILLIS, 0L) ?: 0L
                start()
                START_STICKY
            }
        }
    }

    private fun start() {
        if (active) return

        val builder = Builder()
            .setSession(SESSION)
            .addAddress(TUNNEL_ADDRESS_V4, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)
            // Without this the descriptor is non-blocking: read() returns 0
            // immediately and forever, so the relay spins at 100% CPU, forwards
            // nothing, and silently blackholes every routed app.
            .setBlocking(true)

        // Claiming IPv6 as well. Omitting it makes Android mark ::/0 unreachable
        // for the routed apps, which kills most of their traffic outright.
        runCatching {
            builder.addAddress(TUNNEL_ADDRESS_V6, 128)
            builder.addRoute("::", 0)
        }.onFailure { status = "IPv6 setup failed: ${it.message}" }

        var allowed = 0
        TargetApp.throttleable.forEach { app ->
            try {
                builder.addAllowedApplication(app.packageName)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed on this device; nothing to route.
            }
        }
        if (allowed == 0) {
            status = "no target apps installed"
            stopSelf()
            return
        }

        tunnel = try {
            builder.establish()
        } catch (t: Throwable) {
            status = "establish failed: ${t.message}"
            null
        }

        val descriptor = tunnel
        if (descriptor == null) {
            if (status == "idle") status = "establish() returned null"
            running.value = false
            stopSelf()
            return
        }

        active = true
        running.value = true
        packetsRead = 0
        udpPacketsForwarded = 0
        tcpPacketsDropped = 0
        scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS)
        status = "tunnel up, delay ${delayMillis}ms"
        Log.i(TAG, "tunnel up for $allowed app(s), delay=${delayMillis}ms")
        worker = thread(name = "orbis-vpn") { relay(descriptor) }
    }

    private fun relay(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)
        status = "relay running"

        try {
            while (active) {
                val read = input.read(buffer)
                if (read <= 0) {
                    Thread.sleep(10)
                    continue
                }
                packetsRead++

                when ((buffer[0].toInt() and 0xF0) shr 4) {
                    4 -> handleIpv4(buffer, read, output)
                    6 -> handleIpv6(buffer, read, output)
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            if (active) {
                status = "relay died: ${t::class.simpleName}: ${t.message}"
                Log.e(TAG, "relay stopped: ${t.message}")
            }
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun handleIpv4(buffer: ByteArray, read: Int, output: FileOutputStream) {
        val header = Ipv4.parseHeader(buffer, read) ?: return
        when (header.protocol) {
            Ipv4.PROTOCOL_UDP -> {
                val datagram = Ipv4.parseUdp(buffer, read) ?: return
                forward(
                    key = "4:${datagram.sourcePort}:${datagram.destinationAddress}:${datagram.destinationPort}",
                    destination = Ipv4.toBytes(datagram.destinationAddress),
                    destinationPort = datagram.destinationPort,
                    payload = datagram.payload,
                    output = output,
                ) { reply ->
                    Ipv4.buildUdp(
                        sourceAddress = datagram.destinationAddress,
                        destinationAddress = datagram.sourceAddress,
                        sourcePort = datagram.destinationPort,
                        destinationPort = datagram.sourcePort,
                        payload = reply,
                    )
                }
            }

            Ipv4.PROTOCOL_TCP -> tcpPacketsDropped++
        }
    }

    private fun handleIpv6(buffer: ByteArray, read: Int, output: FileOutputStream) {
        when (Ipv6.nextHeader(buffer, read)) {
            Ipv6.NEXT_HEADER_UDP -> {
                val datagram = Ipv6.parseUdp(buffer, read) ?: return
                forward(
                    key = "6:${datagram.sourcePort}:${datagram.destinationAddress.contentHashCode()}:${datagram.destinationPort}",
                    destination = datagram.destinationAddress,
                    destinationPort = datagram.destinationPort,
                    payload = datagram.payload,
                    output = output,
                ) { reply ->
                    Ipv6.buildUdp(
                        sourceAddress = datagram.destinationAddress,
                        destinationAddress = datagram.sourceAddress,
                        sourcePort = datagram.destinationPort,
                        destinationPort = datagram.sourcePort,
                        payload = reply,
                    )
                }
            }

            Ipv6.NEXT_HEADER_TCP -> tcpPacketsDropped++
        }
    }

    private fun forward(
        key: String,
        destination: ByteArray,
        destinationPort: Int,
        payload: ByteArray,
        output: FileOutputStream,
        buildReply: (ByteArray) -> ByteArray,
    ) {
        val flow = flows.getOrPut(key) {
            val socket = DatagramSocket()
            // Without protect() our own packets would loop back into the tunnel
            // and never reach the network.
            protect(socket)
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            Flow(socket, buildReply).also {
                thread(name = "orbis-udp") { readReplies(key, it, output) }
            }
        }

        val address = InetAddress.getByAddress(destination)
        val packet = DatagramPacket(payload, payload.size, address, destinationPort)

        val send = Runnable {
            runCatching {
                flow.socket.send(packet)
                udpPacketsForwarded++
            }.onFailure { close(key) }
        }

        // Scheduled, not slept: the read loop must keep draining the tunnel or
        // every other flow stalls behind this one.
        val delay = delayMillis
        if (delay > 0L) {
            scheduler?.schedule(send, delay, TimeUnit.MILLISECONDS) ?: send.run()
        } else {
            send.run()
        }
    }

    private fun readReplies(key: String, flow: Flow, output: FileOutputStream) {
        val buffer = ByteArray(MTU)
        try {
            while (active && !flow.socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    flow.socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }

                val reply = flow.buildReply(packet.data.copyOfRange(0, packet.length))
                synchronized(output) { output.write(reply) }
            }
        } catch (t: Throwable) {
            if (active) Log.d(TAG, "flow $key ended: ${t.message}")
        } finally {
            close(key)
        }
    }

    private fun close(key: String) {
        flows.remove(key)?.let { runCatching { it.socket.close() } }
    }

    override fun onRevoke() {
        Log.i(TAG, "consent revoked")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /**
     * Releases the TUN interface and every relay socket.
     *
     * A leaked interface keeps routing the user's traffic after ORBIS is gone, so
     * this must run on every exit path - stop, revoke, destroy and failure.
     */
    private fun shutdown() {
        if (!active && tunnel == null) return
        active = false
        running.value = false

        scheduler?.shutdownNow()
        scheduler = null

        worker?.interrupt()
        worker = null

        flows.keys.toList().forEach(::close)
        flows.clear()

        runCatching { tunnel?.close() }
        tunnel = null

        status = "stopped (read=$packetsRead, udp=$udpPacketsForwarded, tcp dropped=$tcpPacketsDropped)"
        Log.i(TAG, status)
    }

    companion object {
        private const val TAG = "OrbisVpn"

        const val ACTION_START = "com.orbis.app.vpn.START"
        const val ACTION_STOP = "com.orbis.app.vpn.STOP"
        const val EXTRA_DELAY_MILLIS = "delayMillis"

        private const val SESSION = "ORBIS"
        private const val TUNNEL_ADDRESS_V4 = "10.111.222.2"
        private const val TUNNEL_ADDRESS_V6 = "fd00:1:2:3::2"
        private const val MTU = 1500
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        private const val SCHEDULER_THREADS = 4

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        @Volatile
        private var delayMillis = 0L

        /**
         * Diagnostics are surfaced in the UI rather than logged, because ColorOS
         * silently drops this app's logcat output and a blackholing tunnel is
         * otherwise indistinguishable from a working one.
         */
        @Volatile
        var udpPacketsForwarded = 0L
            private set

        @Volatile
        var tcpPacketsDropped = 0L
            private set

        /** Raw reads off the TUN. Zero here means nothing reaches the relay. */
        @Volatile
        var packetsRead = 0L
            private set

        @Volatile
        var status: String = "idle"
            private set

        fun start(context: Context, delayMillis: Long = 0L) {
            context.startService(
                Intent(context, OrbisVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_DELAY_MILLIS, delayMillis)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OrbisVpnService::class.java).setAction(ACTION_STOP)
            )
        }

        fun toInetAddress(address: Int): InetAddress =
            InetAddress.getByAddress(Ipv4.toBytes(address))
    }
}
