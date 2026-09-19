package com.orbis.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.orbis.app.R
import com.orbis.app.surface.BrowserPackages
import com.orbis.app.throttle.Friction
import com.orbis.app.throttle.TcpFallback
import com.orbis.app.throttle.TcpStarvationDetector
import com.orbis.app.throttle.TokenBucket
import com.orbis.app.usage.TargetApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Relays short-form video traffic so it can be slowed down.
 *
 * Scope is deliberately tiny, because a VPN that misbehaves takes the user's
 * connectivity with it:
 *
 *  - `addAllowedApplication` limits the tunnel to the **single app whose
 *    short-form feed is on screen**, named by the caller. A tunnel applies its
 *    delay to everything it carries and cannot tell one app's packets from
 *    another's, so routing every target app at once meant watching Instagram
 *    Reels also degraded YouTube, Snapchat and all seven routed browsers.
 *    **WhatsApp's packets never enter this service at all** - enforced by the
 *    OS, not by logic in here.
 *  - Both IPv4 and IPv6 UDP are relayed. Handling only IPv4 is not a partial
 *    implementation but a broken one: the tunnel captures IPv6 too, so anything
 *    unhandled is silently blackholed rather than merely un-throttled.
 *  - TCP is counted and dropped. That is survivable only because the tunnel is
 *    meant to be up solely while a throttled surface is on screen.
 *
 * The delay is applied by *deferring* each packet, never by sleeping in the read
 * loop - blocking there would serialise every flow and turn a 120 ms delay into a
 * near-total stall.
 *
 * ### Threading
 *
 * Exactly three threads, regardless of how many conversations are in flight:
 *
 *  - **orbis-tun** reads the TUN, parses in place, and either sends immediately
 *    or hands the packet to the delay queue.
 *  - **orbis-select** owns the [Selector]: it registers new flows, drains
 *    replies back into the TUN, evicts idle flows and publishes [stats]. It is
 *    the only thread that writes to the tunnel, so no lock is needed.
 *  - **orbis-delay** drains the [DelayQueue] once each packet is due.
 *
 * An earlier version ran a thread and a blocking [java.net.DatagramSocket] per
 * flow with no eviction, which meant hundreds of threads and file descriptors
 * after a few minutes of scrolling - browsers are routed too, so every DNS
 * lookup and every CDN connection opened another one.
 */
class OrbisVpnService : VpnService() {

    /**
     * One UDP conversation, pinned to a connected [DatagramChannel].
     *
     * The tunnel-side addresses are captured once, at open, so building a reply
     * needs nothing from the inbound packet but its payload.
     */
    private class UdpFlow(
        val key: Long,
        val channel: DatagramChannel,
        val ipv6: Boolean,
        /** The app's own address; the destination of every reply. */
        val localAddressV4: Int,
        /** The remote peer; the source of every reply. */
        val remoteAddressV4: Int,
        val localAddressV6: ByteArray?,
        val remoteAddressV6: ByteArray?,
        val localPort: Int,
        val remotePort: Int,
    ) {
        @Volatile
        var lastUsedMillis: Long = 0L

        @Volatile
        var closed: Boolean = false

        /** Download bytes this flow has carried. Selector thread only. */
        var received: Long = 0L

        /** Payload offset of a reply, i.e. where its headers stop. */
        val replyHeaderBytes: Int =
            if (ipv6) Ipv6.HEADER_BYTES + UDP_HEADER_BYTES else IPV4_HEADER_BYTES + UDP_HEADER_BYTES

        /**
         * IPv6 keys fold a 128-bit address into 32 bits, so a hit has to be
         * confirmed against the full address before the flow is reused.
         */
        fun matchesV6(packet: ByteArray, offset: Int): Boolean {
            val expected = remoteAddressV6 ?: return false
            for (index in 0 until Ipv6.ADDRESS_BYTES) {
                if (expected[index] != packet[offset + index]) return false
            }
            return true
        }
    }

    /**
     * A packet waiting out its throttle delay.
     *
     * Instances are pooled and reused: at video bitrates a fresh buffer per
     * deferred packet was the app's main source of memory pressure.
     */
    private class Pending : Delayed {
        val payload = ByteArray(MTU)
        val buffer: ByteBuffer = ByteBuffer.wrap(payload)
        var flow: UdpFlow? = null
        var length: Int = 0
        var dueAtNanos: Long = 0L

        override fun getDelay(unit: TimeUnit): Long =
            unit.convert(dueAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int =
            dueAtNanos.compareTo((other as Pending).dueAtNanos)
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var selectorThread: Thread? = null
    private var delayThread: Thread? = null
    private var selector: Selector? = null

    private val flowsV4 = ConcurrentHashMap<Long, UdpFlow>()
    private val flowsV6 = ConcurrentHashMap<Long, UdpFlow>()

    /** Channels are registered on the selector thread; see the class comment. */
    private val pendingRegistrations = ConcurrentLinkedQueue<UdpFlow>()

    private val delayed = DelayQueue<Pending>()
    private val pendingPool = ArrayBlockingQueue<Pending>(MAX_PENDING_PACKETS)

    /** Polices downloads to the pulse's ceiling. Selector thread only. */
    private val inboundBucket = TokenBucket()

    @Volatile
    private var active = false

    /**
     * What this tunnel is currently routing; empty when it is down. Volatile:
     * written by the lifecycle thread, read by the selector's TCP check.
     */
    @Volatile
    private var routedNow: List<String> = emptyList()

    /** Watches for an app that has moved to TCP. Selector thread only. */
    private val tcpStarvation = TcpStarvationDetector()

    /** Set once this session has stood down, so it trips only once. */
    @Volatile
    private var stoodDown = false

    /**
     * Every start and stop runs here, one at a time.
     *
     * `establish()` is a binder round trip and [shutdown] joins three threads, so
     * neither belongs on the main thread - and re-pointing the tunnel at a
     * different app is a shutdown immediately followed by a start, which must not
     * interleave with another request to do the same thing.
     */
    private var lifecycleThread: HandlerThread? = null
    private var lifecycleHandler: Handler? = null

    /** Bounded manual sessions stop themselves; see [EXTRA_AUTO_STOP_MILLIS]. */
    private val autoStop = Runnable {
        if (active) {
            shutdown()
            status = "test window ended"
            publishStats()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("orbis-vpn-lifecycle").also { it.start() }
        lifecycleThread = thread
        lifecycleHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read the intent on this thread: it is recycled once onStartCommand
        // returns, so the lifecycle thread must never be handed the object itself.
        if (intent?.action == ACTION_STOP) {
            post {
                shutdown()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val requestedFriction = Friction.fromArray(intent?.getLongArrayExtra(EXTRA_FRICTION))
        val requestedRoute = intent?.getStringArrayListExtra(EXTRA_ROUTE_PACKAGES)?.toList()
            ?: emptyList()
        val autoStopMillis = intent?.getLongExtra(EXTRA_AUTO_STOP_MILLIS, 0L) ?: 0L

        post { handleStart(requestedFriction, requestedRoute, autoStopMillis) }
        return START_STICKY
    }

    private fun post(block: () -> Unit) {
        val handler = lifecycleHandler
        if (handler == null) block() else handler.post(block)
    }

    /**
     * Brings the tunnel up, re-points it at a different app, or just re-scales it.
     *
     * Android fixes the allow-list at `establish()` time, so a change of routed
     * app is a full rebuild. A change of friction alone is not - that is adopted
     * in place, keeping the pulse's rhythm, which is what lets the throttle track
     * usage across a long session rather than staying frozen at whatever it was
     * when the feed first appeared.
     */
    private fun handleStart(requested: Friction, route: List<String>, autoStopMillis: Long) {
        if (route.isEmpty()) {
            status = "nothing to route"
            publishStats()
            // START_STICKY redelivers a null intent after the process is killed,
            // which lands here with no route. Staying alive with nothing to do
            // would leave an idle service the system keeps restarting.
            if (!active) stopSelf()
            return
        }

        if (active && route == routedNow) {
            friction = requested
            status = "tunnel up, ${describe(requested)}"
            armAutoStop(autoStopMillis)
            publishStats()
            return
        }

        if (active) shutdown()

        friction = requested
        start(route)
        armAutoStop(autoStopMillis)
    }

    private fun armAutoStop(millis: Long) {
        lifecycleHandler?.removeCallbacks(autoStop)
        if (millis > 0L) lifecycleHandler?.postDelayed(autoStop, millis)
    }

    @Synchronized
    private fun start(route: List<String>) {
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
        route.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed on this device; nothing to route.
            }
        }
        if (allowed == 0) {
            status = "no target apps installed"
            publishStats()
            stopSelf()
            return
        }

        val openedSelector = try {
            Selector.open()
        } catch (t: Throwable) {
            status = "selector open failed: ${t.message}"
            publishStats()
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
            runCatching { openedSelector.close() }
            running.value = false
            publishStats()
            stopSelf()
            return
        }

        goForeground()

        active = true
        routedNow = route
        routed.value = route
        running.value = true
        packetsRead.set(0)
        udpPacketsForwarded.set(0)
        tcpPacketsDropped.set(0)
        packetsDropped.set(0)
        bytesIn.set(0)
        packetsPoliced.set(0)

        // Each tunnel starts its own rhythm, squeeze first, so the drag lands the
        // moment a feed opens. The bucket starts empty for the same reason.
        pulseEpochNanos = System.nanoTime()
        inboundBucket.reset()
        tcpStarvation.reset()
        stoodDown = false

        selector = openedSelector
        // Tops the pool back up rather than only filling it once, so a restart
        // cannot leave the relay permanently short of slots.
        while (pendingPool.remainingCapacity() > 0) {
            pendingPool.offer(Pending())
        }

        status = "tunnel up, ${describe(friction)}"
        publishStats()
        Log.i(TAG, "tunnel up for ${route.joinToString()}, ${describe(friction)}")

        // Both streams wrap the *same* descriptor. Neither is ever closed: the
        // ParcelFileDescriptor owns that fd, and closing it three times risks
        // yanking a number that has already been recycled by another thread.
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)

        selectorThread = thread(name = "orbis-select") { selectLoop(openedSelector, output) }
        delayThread = thread(name = "orbis-delay") { delayLoop() }
        reader = thread(name = "orbis-tun") { relay(input) }
    }

    /**
     * Keeps the tunnel alive once ORBIS is backgrounded, which is exactly when the
     * user is scrolling. The notification is deliberately low importance: it is a
     * disclosure that traffic is being shaped, not something to interrupt with.
     */
    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.throttle_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.throttle_notification_title))
            .setContentText(getString(R.string.throttle_notification_text))
            .setSmallIcon(R.drawable.ic_orbis_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- outbound

    private fun relay(input: FileInputStream) {
        val buffer = ByteArray(MTU)
        // One buffer over the read array, re-windowed per packet, so forwarding
        // costs no allocation and no copy at all.
        val sendBuffer = ByteBuffer.wrap(buffer)
        val viewV4 = Ipv4.UdpView()
        val viewV6 = Ipv6.UdpView()

        // Include the delay: it is the only way to see that the throttle actually
        // scaled with usage rather than sitting at the base value.
        status = "relay running, ${describe(friction)}"

        try {
            while (active) {
                val read = input.read(buffer)
                // A negative read is EOF - the descriptor has been closed under
                // us, and the old code treated that as "nothing yet" and spun.
                if (read < 0) break
                // setBlocking(true) means this should not happen; the sleep is
                // only so that a driver which disagrees cannot peg a core.
                if (read == 0) {
                    Thread.sleep(1)
                    continue
                }
                packetsRead.incrementAndGet()

                when ((buffer[0].toInt() and 0xF0) shr 4) {
                    4 -> handleIpv4(buffer, read, sendBuffer, viewV4)
                    6 -> handleIpv6(buffer, read, sendBuffer, viewV6)
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            if (active) {
                status = "relay died: ${t::class.simpleName}: ${t.message}"
                Log.e(TAG, "relay stopped: ${t.message}")
            }
        }
    }

    private fun handleIpv4(
        buffer: ByteArray,
        read: Int,
        sendBuffer: ByteBuffer,
        view: Ipv4.UdpView,
    ) {
        val header = Ipv4.parseHeader(buffer, read) ?: return
        if (header.protocol == Ipv4.PROTOCOL_TCP) {
            tcpPacketsDropped.incrementAndGet()
            return
        }
        if (header.protocol != Ipv4.PROTOCOL_UDP) return
        if (!Ipv4.parseUdpInto(buffer, read, view)) return

        // srcPort | dstPort | dstAddr packs the 5-tuple exactly into 64 bits, so
        // the map lookup needs no key object and no string building.
        val key = (view.sourcePort.toLong() shl 48) or
            (view.destinationPort.toLong() shl 32) or
            (view.destinationAddress.toLong() and 0xFFFFFFFFL)

        val existing = flowsV4[key]
        val flow = if (existing != null && !existing.closed) {
            existing
        } else {
            existing?.let { closeFlow(it) }
            openFlowV4(key, view) ?: return
        }

        forward(flow, buffer, sendBuffer, view.payloadOffset, view.payloadLength)
    }

    private fun handleIpv6(
        buffer: ByteArray,
        read: Int,
        sendBuffer: ByteBuffer,
        view: Ipv6.UdpView,
    ) {
        when (Ipv6.nextHeader(buffer, read)) {
            Ipv6.NEXT_HEADER_TCP -> {
                tcpPacketsDropped.incrementAndGet()
                return
            }

            Ipv6.NEXT_HEADER_UDP -> Unit
            else -> return
        }
        if (!Ipv6.parseUdpInto(buffer, read, view)) return

        // A 128-bit address cannot share the 64-bit key, so it is folded to 32
        // bits and the hit confirmed against the full address below.
        val addressHash = addressHash(buffer, Ipv6.DESTINATION_OFFSET)
        val key = (view.sourcePort.toLong() shl 48) or
            (view.destinationPort.toLong() shl 32) or
            (addressHash.toLong() and 0xFFFFFFFFL)

        val existing = flowsV6[key]
        val flow = if (
            existing != null &&
            !existing.closed &&
            existing.matchesV6(buffer, Ipv6.DESTINATION_OFFSET)
        ) {
            existing
        } else {
            existing?.let { closeFlow(it) }
            openFlowV6(key, buffer, view) ?: return
        }

        forward(flow, buffer, sendBuffer, view.payloadOffset, view.payloadLength)
    }

    /**
     * Sends now, or queues the packet until its delay expires.
     *
     * Never sleeps: the read loop has to keep draining the TUN or every other
     * flow stalls behind this one.
     */
    private fun forward(
        flow: UdpFlow,
        buffer: ByteArray,
        sendBuffer: ByteBuffer,
        payloadOffset: Int,
        payloadLength: Int,
    ) {
        flow.lastUsedMillis = SystemClock.elapsedRealtime()

        // Delayed only while squeezed: between squeezes the feed runs normally.
        val delay = friction.delayAt(pulseElapsedMillis(System.nanoTime()))
        if (delay <= 0L) {
            // Window the shared buffer onto this payload - no copy, no allocation.
            (sendBuffer as Buffer).clear()
            sendBuffer.position(payloadOffset)
            sendBuffer.limit(payloadOffset + payloadLength)
            send(flow, sendBuffer)
            return
        }

        // The pool is the back-pressure: when every slot is in flight the packet
        // is dropped rather than queued. Dropping is the correct failure mode for
        // a UDP throttle, and it is what bounds the relay's memory.
        val pending = pendingPool.poll()
        if (pending == null) {
            packetsDropped.incrementAndGet()
            return
        }

        buffer.copyInto(pending.payload, 0, payloadOffset, payloadOffset + payloadLength)
        pending.flow = flow
        pending.length = payloadLength
        pending.dueAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay)
        delayed.put(pending)
    }

    private fun delayLoop() {
        while (active) {
            val pending = try {
                delayed.take()
            } catch (_: InterruptedException) {
                break
            }

            val flow = pending.flow
            if (flow != null && !flow.closed) {
                (pending.buffer as Buffer).clear()
                pending.buffer.limit(pending.length)
                send(flow, pending.buffer)
            }

            pending.flow = null
            pendingPool.offer(pending)
        }
    }

    private fun send(flow: UdpFlow, buffer: ByteBuffer) {
        try {
            flow.channel.write(buffer)
            udpPacketsForwarded.incrementAndGet()
        } catch (_: Throwable) {
            closeFlow(flow)
        }
    }

    // ----------------------------------------------------------------- inbound

    /**
     * The one thread that touches the [Selector] and the one that writes to the
     * tunnel. Also does the housekeeping - registration, idle eviction, stats -
     * because it already wakes at least once a second.
     */
    private fun selectLoop(selector: Selector, output: FileOutputStream) {
        val out = ByteArray(MTU)
        val replyBuffer = ByteBuffer.wrap(out)

        try {
            while (active) {
                registerPending(selector)
                selector.select(SELECT_TIMEOUT_MILLIS)
                if (!active) break

                val keys = selector.selectedKeys()
                val iterator = keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    if (!key.isValid || !key.isReadable) continue
                    drainReplies(key, out, replyBuffer, output)
                }

                evictIdleFlows()
                publishStats()
                checkTcpFallback()
            }
        } catch (t: Throwable) {
            if (active) {
                status = "selector died: ${t::class.simpleName}: ${t.message}"
                Log.e(TAG, "selector stopped: ${t.message}")
            }
        }
    }

    /**
     * The safety valve: an app whose traffic has moved to TCP is left alone.
     *
     * This relay drops TCP, so for such an app the throttle is not friction but
     * breakage - a Short frozen on its first frame. Rather than starve it, ORBIS
     * stands down for that app for a while; [TcpFallback] decides how long, and
     * the gate will not raise the tunnel for it until then. Runs on the selector
     * thread, once per pass.
     *
     * Only automatic sessions are judged. The manual test routes every app at
     * once, and its counters describe no single one of them.
     */
    private fun checkTcpFallback() {
        if (stoodDown) return
        val route = routedNow
        if (route.size != 1) return

        val starving = tcpStarvation.starving(
            nowMillis = SystemClock.elapsedRealtime(),
            tcpDropped = tcpPacketsDropped.get(),
            bytesIn = bytesIn.get(),
        )
        if (!starving) return

        stoodDown = true
        val packageName = route.single()
        val minutes = TcpFallback.standDown(packageName, System.currentTimeMillis()) / 60_000L
        status = "$packageName is on TCP, which ORBIS cannot relay - left at full " +
            "speed for ${minutes}m"
        Log.i(TAG, status)
        post {
            shutdown()
            stopSelf()
        }
    }

    private fun registerPending(selector: Selector) {
        while (true) {
            val flow = pendingRegistrations.poll() ?: return
            if (flow.closed) continue
            runCatching {
                flow.channel.register(selector, SelectionKey.OP_READ, flow)
            }.onFailure { closeFlow(flow) }
        }
    }

    /**
     * Reads every reply the kernel has buffered for one flow and writes each back
     * into the tunnel.
     *
     * The payload is read straight into the position it will occupy in the
     * finished packet, so the headers are simply written in front of it and
     * nothing is ever copied.
     */
    private fun drainReplies(
        key: SelectionKey,
        out: ByteArray,
        replyBuffer: ByteBuffer,
        output: FileOutputStream,
    ) {
        val flow = key.attachment() as? UdpFlow ?: return
        if (flow.closed) return

        repeat(MAX_REPLIES_PER_SELECT) {
            (replyBuffer as Buffer).clear()
            replyBuffer.position(flow.replyHeaderBytes)

            val read = try {
                flow.channel.read(replyBuffer)
            } catch (_: Throwable) {
                closeFlow(flow)
                return
            }
            if (read <= 0) return

            flow.lastUsedMillis = SystemClock.elapsedRealtime()

            // The squeeze. Downloads beyond this phase's ceiling are dropped, and
            // the sender's congestion control reads that as a slow network and
            // backs off - which is what the player then shows. This is the
            // direction the video arrives on; the old delay never touched it.
            //
            // Only a flow that is already streaming is policed. Measured on
            // CPH2585: policing every flow from one shared allowance let the video
            // connection starve new connections' handshakes, YouTube decided its
            // QUIC route was broken, fell back to TCP - which this relay drops -
            // and a Short froze on its first frame. A handshake is a few KB, so a
            // flow runs free until it has carried [BULK_FLOW_BYTES].
            flow.received += read
            if (flow.received > BULK_FLOW_BYTES) {
                val nowNanos = System.nanoTime()
                val ceiling = friction.downloadCeilingAt(pulseElapsedMillis(nowNanos))
                if (!inboundBucket.tryTake(read, ceiling, nowNanos)) {
                    packetsPoliced.incrementAndGet()
                    return@repeat
                }
            }

            val total = if (flow.ipv6) {
                Ipv6.buildUdpInto(
                    out = out,
                    sourceAddress = flow.remoteAddressV6 ?: return,
                    destinationAddress = flow.localAddressV6 ?: return,
                    sourcePort = flow.remotePort,
                    destinationPort = flow.localPort,
                    payload = out,
                    payloadOffset = flow.replyHeaderBytes,
                    payloadLength = read,
                )
            } else {
                Ipv4.buildUdpInto(
                    out = out,
                    sourceAddress = flow.remoteAddressV4,
                    destinationAddress = flow.localAddressV4,
                    sourcePort = flow.remotePort,
                    destinationPort = flow.localPort,
                    payload = out,
                    payloadOffset = flow.replyHeaderBytes,
                    payloadLength = read,
                )
            }

            output.write(out, 0, total)
            bytesIn.addAndGet(read.toLong())
        }
    }

    // ------------------------------------------------------------------- flows

    private fun openFlowV4(key: Long, view: Ipv4.UdpView): UdpFlow? {
        if (!hasFlowCapacity()) return null

        val channel = openChannel(
            Ipv4.toBytes(view.destinationAddress),
            view.destinationPort,
        ) ?: return null

        val flow = UdpFlow(
            key = key,
            channel = channel,
            ipv6 = false,
            localAddressV4 = view.sourceAddress,
            remoteAddressV4 = view.destinationAddress,
            localAddressV6 = null,
            remoteAddressV6 = null,
            localPort = view.sourcePort,
            remotePort = view.destinationPort,
        )
        flowsV4[key] = flow
        enqueueRegistration(flow)
        return flow
    }

    private fun openFlowV6(key: Long, packet: ByteArray, view: Ipv6.UdpView): UdpFlow? {
        if (!hasFlowCapacity()) return null

        // Copied once per flow rather than twice per packet.
        val remote = packet.copyOfRange(
            Ipv6.DESTINATION_OFFSET,
            Ipv6.DESTINATION_OFFSET + Ipv6.ADDRESS_BYTES,
        )
        val local = packet.copyOfRange(
            Ipv6.SOURCE_OFFSET,
            Ipv6.SOURCE_OFFSET + Ipv6.ADDRESS_BYTES,
        )

        val channel = openChannel(remote, view.destinationPort) ?: return null

        val flow = UdpFlow(
            key = key,
            channel = channel,
            ipv6 = true,
            localAddressV4 = 0,
            remoteAddressV4 = 0,
            localAddressV6 = local,
            remoteAddressV6 = remote,
            localPort = view.sourcePort,
            remotePort = view.destinationPort,
        )
        flowsV6[key] = flow
        enqueueRegistration(flow)
        return flow
    }

    private fun openChannel(address: ByteArray, port: Int): DatagramChannel? = try {
        val channel = DatagramChannel.open()
        channel.configureBlocking(false)
        // Without protect() our own packets would loop back into the tunnel and
        // never reach the network.
        if (!protect(channel.socket())) {
            channel.close()
            null
        } else {
            // Connecting pins the peer, so replies arrive on a plain read() with
            // no SocketAddress to allocate or compare per packet.
            channel.connect(InetSocketAddress(InetAddress.getByAddress(address), port))
            channel
        }
    } catch (t: Throwable) {
        Log.d(TAG, "could not open flow: ${t.message}")
        null
    }

    private fun enqueueRegistration(flow: UdpFlow) {
        flow.lastUsedMillis = SystemClock.elapsedRealtime()
        pendingRegistrations.add(flow)
        selector?.wakeup()
    }

    /**
     * A hard ceiling on concurrent conversations.
     *
     * Browsers are routed too, so a busy page can open flows faster than they
     * expire. Refusing beyond the cap costs one dropped packet; running without
     * one costs file descriptors until the process dies.
     */
    private fun hasFlowCapacity(): Boolean {
        if (flowsV4.size + flowsV6.size < MAX_FLOWS) return true
        packetsDropped.incrementAndGet()
        return false
    }

    private fun evictIdleFlows() {
        val now = SystemClock.elapsedRealtime()
        evictIdleFrom(flowsV4, now)
        evictIdleFrom(flowsV6, now)
    }

    private fun evictIdleFrom(flows: ConcurrentHashMap<Long, UdpFlow>, now: Long) {
        if (flows.isEmpty()) return
        val iterator = flows.values.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next()
            if (flow.closed || now - flow.lastUsedMillis > FLOW_IDLE_MILLIS) {
                iterator.remove()
                closeChannel(flow)
            }
        }
    }

    private fun closeFlow(flow: UdpFlow) {
        val map = if (flow.ipv6) flowsV6 else flowsV4
        map.remove(flow.key, flow)
        closeChannel(flow)
    }

    private fun closeChannel(flow: UdpFlow) {
        if (flow.closed) return
        flow.closed = true
        runCatching { flow.channel.keyFor(selector)?.cancel() }
        runCatching { flow.channel.close() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onRevoke() {
        Log.i(TAG, "consent revoked")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Drop queued work first, so nothing re-establishes a tunnel on the way out.
        lifecycleHandler?.removeCallbacksAndMessages(null)
        shutdown()
        lifecycleThread?.quitSafely()
        lifecycleThread = null
        lifecycleHandler = null
        super.onDestroy()
    }

    /**
     * Releases the TUN interface and every relay channel.
     *
     * A leaked interface keeps routing the user's traffic after ORBIS is gone, so
     * this must run on every exit path - stop, revoke, destroy and failure.
     */
    @Synchronized
    private fun shutdown() {
        if (!active && tunnel == null) return
        active = false
        routedNow = emptyList()
        routed.value = emptyList()
        running.value = false

        // Wakes the selector out of select() and the delay thread out of take().
        runCatching { selector?.wakeup() }
        delayThread?.interrupt()

        // The reader is parked in a read() on the TUN fd, which interrupt() does
        // not unblock - closing the descriptor is what ends it.
        runCatching { tunnel?.close() }
        tunnel = null

        // Join before dropping the references, so a fast stop/start cannot leave
        // the previous relay writing into the next tunnel.
        runCatching { reader?.join(THREAD_JOIN_MILLIS) }
        runCatching { selectorThread?.join(THREAD_JOIN_MILLIS) }
        runCatching { delayThread?.join(THREAD_JOIN_MILLIS) }
        reader = null
        selectorThread = null
        delayThread = null

        flowsV4.values.forEach(::closeChannel)
        flowsV6.values.forEach(::closeChannel)
        flowsV4.clear()
        flowsV6.clear()
        pendingRegistrations.clear()

        // Recycle the deferred packets rather than dropping the pool on the floor.
        while (true) {
            val pending = delayed.poll() ?: break
            pending.flow = null
            pendingPool.offer(pending)
        }

        runCatching { selector?.close() }
        selector = null

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }

        status = "stopped (read=${packetsRead.get()}, udp=${udpPacketsForwarded.get()}, " +
            "tcp dropped=${tcpPacketsDropped.get()})"
        publishStats()
        Log.i(TAG, status)
    }

    /**
     * `adb shell dumpsys activity service com.orbis.app/.vpn.OrbisVpnService`
     *
     * ColorOS drops this app's logcat, so this is how the pulse is watched from a
     * computer: one line, cheap enough to poll every second while a feed plays.
     */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        writer ?: return
        publishStats()
        val s = tunnelStats.value
        writer.println(
            "ORBIS running=${s.running} routed=${s.routed.joinToString("+")} " +
                "squeezing=${s.squeezing} squeeze=${s.squeezeMillis}/${s.pulsePeriodMillis}ms " +
                "delay=${s.delayMillis}ms bytesIn=${s.bytesIn} policed=${s.packetsPoliced} " +
                "udpFwd=${s.udpForwarded} tcpDropped=${s.tcpDropped} shed=${s.packetsDropped} " +
                "flows=${s.activeFlows} status=${s.status}"
        )
    }

    private fun publishStats() {
        stats.value = TunnelStats(
            running = active,
            packetsRead = packetsRead.get(),
            udpForwarded = udpPacketsForwarded.get(),
            tcpDropped = tcpPacketsDropped.get(),
            packetsDropped = packetsDropped.get(),
            activeFlows = flowsV4.size + flowsV6.size,
            delayMillis = friction.delayMillis,
            routed = routedNow,
            bytesIn = bytesIn.get(),
            packetsPoliced = packetsPoliced.get(),
            squeezing = active && friction.squeezingAt(pulseElapsedMillis(System.nanoTime())),
            squeezeMillis = friction.squeezeMillis,
            pulsePeriodMillis = friction.periodMillis,
            status = status,
        )
    }

    private fun addressHash(packet: ByteArray, offset: Int): Int {
        var hash = 1
        for (index in 0 until Ipv6.ADDRESS_BYTES) {
            hash = 31 * hash + packet[offset + index]
        }
        return hash
    }

    companion object {
        private const val TAG = "OrbisVpn"

        const val ACTION_START = "com.orbis.app.vpn.START"
        const val ACTION_STOP = "com.orbis.app.vpn.STOP"
        /** A [Friction] as [Friction.toArray]. */
        const val EXTRA_FRICTION = "friction"

        /** Which packages this session may route. See [handleStart]. */
        const val EXTRA_ROUTE_PACKAGES = "routePackages"

        /**
         * Stop this session on a timer, regardless of what the screen is doing.
         *
         * The automatic path does not use it - there, the accessibility gate's
         * watchdog owns teardown. It exists for the manual diagnostic on the
         * Controls screen, which nothing else would ever bring down: a tunnel
         * raised by hand used to stay up until the user remembered to stop it,
         * dropping every routed app's TCP the whole time.
         */
        const val EXTRA_AUTO_STOP_MILLIS = "autoStopMillis"

        /** How long the manual diagnostic on the Controls screen runs for. */
        const val MANUAL_TEST_MILLIS = 30_000L

        private const val CHANNEL_ID = "orbis_throttle"
        private const val NOTIFICATION_ID = 1

        private const val SESSION = "ORBIS"
        private const val TUNNEL_ADDRESS_V4 = "10.111.222.2"
        private const val TUNNEL_ADDRESS_V6 = "fd00:1:2:3::2"
        private const val MTU = 1500

        private const val IPV4_HEADER_BYTES = 20
        private const val UDP_HEADER_BYTES = 8

        /** How long the selector waits before doing its housekeeping pass. */
        private const val SELECT_TIMEOUT_MILLIS = 1_000L

        /** A conversation this quiet is over; its channel is closed. */
        private const val FLOW_IDLE_MILLIS = 30_000L

        /** Ceiling on concurrent conversations. See [hasFlowCapacity]. */
        private const val MAX_FLOWS = 512

        /**
         * Deferred packets held at once, i.e. the relay's memory ceiling
         * (~[MAX_PENDING_PACKETS] x [MTU]). Beyond it packets are dropped.
         */
        private const val MAX_PENDING_PACKETS = 128

        /** Replies drained per flow per select, so one busy flow cannot starve others. */
        private const val MAX_REPLIES_PER_SELECT = 16

        /**
         * Download bytes a flow may carry before the squeeze applies to it -
         * comfortably more than a QUIC handshake, far less than a video.
         */
        private const val BULK_FLOW_BYTES = 64L * 1024L

        private const val THREAD_JOIN_MILLIS = 500L

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val routed = MutableStateFlow<List<String>>(emptyList())

        /**
         * The packages the live tunnel is routing, or empty when it is down.
         *
         * The gate compares against this rather than remembering what it last
         * asked for: the service is the only thing that knows whether a request
         * actually took effect, and local copies went stale whenever the watchdog
         * stopped the tunnel behind the gate's back.
         */
        val routedApps: StateFlow<List<String>> = routed.asStateFlow()

        /** Immutable, swapped whole, so all three relay threads see a consistent set. */
        @Volatile
        private var friction = Friction.NONE

        /** When the current tunnel's pulse started; its squeezes are timed from here. */
        @Volatile
        private var pulseEpochNanos = 0L

        /** The friction the live tunnel is applying, for the gate's change check. */
        val currentFriction: Friction get() = friction

        private fun pulseElapsedMillis(nowNanos: Long): Long =
            (nowNanos - pulseEpochNanos) / 1_000_000L

        private fun describe(friction: Friction): String =
            if (friction.periodMillis > 0L) {
                "squeezing ${friction.squeezeMillis}ms of every ${friction.periodMillis}ms"
            } else {
                "delay ${friction.delayMillis}ms"
            }

        /**
         * Diagnostics are surfaced in the UI rather than logged, because ColorOS
         * silently drops this app's logcat output and a blackholing tunnel is
         * otherwise indistinguishable from a working one.
         *
         * Atomic, not `@Volatile var`: they are incremented from the reader, the
         * selector and the delay thread at once, and `volatile` gives visibility
         * without atomicity - the counts silently undercounted.
         */
        private val udpPacketsForwarded = AtomicLong(0)
        private val tcpPacketsDropped = AtomicLong(0)
        private val packetsRead = AtomicLong(0)
        private val packetsDropped = AtomicLong(0)

        /** Download bytes delivered to the app, and packets the squeeze held back. */
        private val bytesIn = AtomicLong(0)
        private val packetsPoliced = AtomicLong(0)

        @Volatile
        var status: String = "idle"
            private set

        private val stats = MutableStateFlow(TunnelStats())

        /**
         * Live counters, published by the service rather than polled.
         *
         * The UI used to sample the counters on a 1 Hz timer that ran whether or
         * not the tunnel was up, recomposing the whole tree every second.
         */
        val tunnelStats: StateFlow<TunnelStats> = stats.asStateFlow()

        /**
         * Every package ORBIS is *ever* willing to route - the upper bound, not
         * the routing for any one session.
         *
         * Browsers are included because `youtube.com/shorts` opens in a browser on
         * many devices, and detection alone throttles nothing if the traffic never
         * enters the tunnel.
         *
         * A live session routes one of these at a time, chosen by
         * [com.orbis.app.throttle.ThrottleEngine.routeFor] from the surface on
         * screen. Only the manual diagnostic uses the whole set.
         *
         * Caveat worth keeping in mind: while a browser is routed, *all* of its
         * traffic goes through the tunnel, not just the Shorts tab - a VPN cannot
         * see tabs.
         *
         * WhatsApp is absent, and must stay absent.
         */
        fun routedPackages(): List<String> =
            TargetApp.throttleable.flatMap { it.allPackages } + BrowserPackages.ALL

        /**
         * @param routePackages the apps this session may carry. Defaults to every
         *   throttleable app, which is only right for the manual diagnostic; the
         *   automatic gate always names the single app on screen.
         * @param autoStopMillis a hard stop, or 0 to leave teardown to the caller.
         */
        fun start(
            context: Context,
            friction: Friction = Friction.NONE,
            routePackages: List<String> = routedPackages(),
            autoStopMillis: Long = 0L,
        ) {
            context.startService(
                Intent(context, OrbisVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_FRICTION, friction.toArray())
                    .putStringArrayListExtra(EXTRA_ROUTE_PACKAGES, ArrayList(routePackages))
                    .putExtra(EXTRA_AUTO_STOP_MILLIS, autoStopMillis)
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

/** A snapshot of what the relay is doing, for the UI. */
data class TunnelStats(
    val running: Boolean = false,
    val packetsRead: Long = 0L,
    val udpForwarded: Long = 0L,
    val tcpDropped: Long = 0L,
    /** Packets shed because the delay queue or the flow table was full. */
    val packetsDropped: Long = 0L,
    val activeFlows: Int = 0,
    val delayMillis: Long = 0L,
    /** The packages this session is routing - what is actually being slowed. */
    val routed: List<String> = emptyList(),
    /** Download bytes delivered to the routed app. */
    val bytesIn: Long = 0L,
    /** Download packets the squeeze dropped. */
    val packetsPoliced: Long = 0L,
    /** Whether the pulse is squeezing at this moment. */
    val squeezing: Boolean = false,
    val squeezeMillis: Long = 0L,
    val pulsePeriodMillis: Long = 0L,
    val status: String = "idle",
)
