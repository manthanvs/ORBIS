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
import kotlin.concurrent.thread

/**
 * Relays short-form video traffic so it can be slowed down.
 *
 * Scope is deliberately tiny, because a VPN that misbehaves takes the user's
 * connectivity with it:
 *
 *  - `addAllowedApplication` limits the tunnel to Instagram, YouTube and
 *    Snapchat. **WhatsApp's packets never enter this service at all** - that is
 *    enforced by the OS, not by logic in here, which is the strongest form the
 *    invariant can take.
 *  - The tunnel is only established while a throttled surface is on screen, and
 *    torn down the moment it is not, so normal use is never routed through it.
 *  - UDP (QUIC) is relayed, which is what carries the video. TCP is counted and
 *    dropped; that only ever happens during an active throttle, where impeding
 *    the fallback path is the intended friction rather than a fault.
 *
 * Phase 2b runs with [delayMillis] at zero: prove the tunnel carries traffic
 * before letting it interfere with any.
 */
class OrbisVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val flows = ConcurrentHashMap<String, DatagramSocket>()

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
            .addAddress(TUNNEL_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)

        var allowed = 0
        TargetApp.throttleable.forEach { app ->
            try {
                builder.addAllowedApplication(app.packageName)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
                // App simply is not installed on this device; nothing to route.
            }
        }
        if (allowed == 0) {
            Log.w(TAG, "no target apps installed - not establishing a tunnel")
            stopSelf()
            return
        }

        tunnel = builder.establish()
        val descriptor = tunnel
        if (descriptor == null) {
            // Consent revoked, or another VPN took over.
            Log.w(TAG, "establish() returned null; VPN not started")
            running.value = false
            stopSelf()
            return
        }

        active = true
        running.value = true
        Log.i(TAG, "tunnel up for $allowed app(s), delay=${delayMillis}ms")
        worker = thread(name = "orbis-vpn") { relay(descriptor) }
    }

    private fun relay(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
            while (active) {
                val read = input.read(buffer)
                if (read <= 0) continue

                val header = Ipv4.parseHeader(buffer, read) ?: continue
                when (header.protocol) {
                    Ipv4.PROTOCOL_UDP -> {
                        val datagram = Ipv4.parseUdp(buffer, read) ?: continue
                        forward(datagram, output)
                    }

                    Ipv4.PROTOCOL_TCP -> tcpPacketsDropped++

                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            if (active) Log.e(TAG, "relay stopped: ${t.message}")
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun forward(datagram: Ipv4.UdpDatagram, output: FileOutputStream) {
        val key = "${datagram.sourcePort}:${datagram.destinationAddress}:${datagram.destinationPort}"

        val socket = flows.getOrPut(key) {
            DatagramSocket().also { created ->
                // Without protect() our own packets would loop back into the
                // tunnel and never reach the network.
                protect(created)
                created.soTimeout = SOCKET_TIMEOUT_MILLIS
                thread(name = "orbis-udp") { readReplies(key, created, datagram, output) }
            }
        }

        // The throttle: hold the packet briefly before it leaves. Zero in 2b.
        val delay = delayMillis
        if (delay > 0L) Thread.sleep(delay)

        runCatching {
            socket.send(
                DatagramPacket(
                    datagram.payload,
                    datagram.payload.size,
                    toInetAddress(datagram.destinationAddress),
                    datagram.destinationPort,
                )
            )
            udpPacketsForwarded++
        }.onFailure { close(key) }
    }

    private fun readReplies(
        key: String,
        socket: DatagramSocket,
        outbound: Ipv4.UdpDatagram,
        output: FileOutputStream,
    ) {
        val buffer = ByteArray(MTU)
        try {
            while (active && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    // Idle flow; keep waiting while the tunnel is up.
                    continue
                }

                val reply = Ipv4.buildUdp(
                    // Swap direction: the reply comes back from the destination.
                    sourceAddress = outbound.destinationAddress,
                    destinationAddress = outbound.sourceAddress,
                    sourcePort = outbound.destinationPort,
                    destinationPort = outbound.sourcePort,
                    payload = packet.data.copyOfRange(0, packet.length),
                )
                synchronized(output) { output.write(reply) }
            }
        } catch (t: Throwable) {
            if (active) Log.d(TAG, "flow $key ended: ${t.message}")
        } finally {
            close(key)
        }
    }

    private fun close(key: String) {
        flows.remove(key)?.let { runCatching { it.close() } }
    }

    override fun onRevoke() {
        // The user switched to another VPN or revoked consent.
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
     * A leaked interface keeps routing the user's traffic after ORBIS is gone,
     * so this must run on every exit path - stop, revoke, destroy and failure.
     */
    private fun shutdown() {
        if (!active && tunnel == null) return
        active = false
        running.value = false

        worker?.interrupt()
        worker = null

        flows.keys.toList().forEach(::close)
        flows.clear()

        runCatching { tunnel?.close() }
        tunnel = null

        Log.i(TAG, "tunnel down (udp forwarded=$udpPacketsForwarded, tcp dropped=$tcpPacketsDropped)")
    }

    companion object {
        private const val TAG = "OrbisVpn"

        const val ACTION_START = "com.orbis.app.vpn.START"
        const val ACTION_STOP = "com.orbis.app.vpn.STOP"
        const val EXTRA_DELAY_MILLIS = "delayMillis"

        private const val SESSION = "ORBIS"
        private const val TUNNEL_ADDRESS = "10.111.222.2"
        private const val MTU = 1500
        private const val SOCKET_TIMEOUT_MILLIS = 10_000

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        @Volatile
        private var delayMillis = 0L

        /** Packet counters, surfaced in the UI so 2b can be judged on evidence. */
        @Volatile
        var udpPacketsForwarded = 0L
            private set

        @Volatile
        var tcpPacketsDropped = 0L
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

        fun toInetAddress(address: Int): InetAddress = InetAddress.getByAddress(
            byteArrayOf(
                ((address shr 24) and 0xFF).toByte(),
                ((address shr 16) and 0xFF).toByte(),
                ((address shr 8) and 0xFF).toByte(),
                (address and 0xFF).toByte(),
            )
        )
    }
}
