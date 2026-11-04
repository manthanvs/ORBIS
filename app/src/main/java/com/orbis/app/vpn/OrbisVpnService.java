package com.orbis.app.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.orbis.app.R;
import com.orbis.app.surface.BrowserPackages;
import com.orbis.app.throttle.Friction;
import com.orbis.app.throttle.TcpFallback;
import com.orbis.app.throttle.TcpStarvationDetector;
import com.orbis.app.throttle.TokenBucket;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.util.StateHolder;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relays short-form video traffic so it can be slowed down.
 *
 * <p>Scope is deliberately tiny, because a VPN that misbehaves takes the user's
 * connectivity with it:
 *
 * <ul>
 *   <li>{@code addAllowedApplication} limits the tunnel to the <b>single app
 *       whose short-form feed is on screen</b>, named by the caller. A tunnel
 *       applies its delay to everything it carries and cannot tell one app's
 *       packets from another's, so routing every target app at once meant
 *       watching Instagram Reels also degraded YouTube, Snapchat and all seven
 *       routed browsers. <b>WhatsApp's packets never enter this service at
 *       all</b> - enforced by the OS, not by logic in here.
 *   <li>Both IPv4 and IPv6 UDP are relayed. Handling only IPv4 is not a partial
 *       implementation but a broken one: the tunnel captures IPv6 too, so
 *       anything unhandled is silently blackholed rather than merely
 *       un-throttled.
 *   <li>TCP is counted and dropped, and carried instead by {@link TcpProxy}
 *       where the app honours the proxy the tunnel advertises.
 * </ul>
 *
 * <p>The delay is applied by <i>deferring</i> each packet, never by sleeping in
 * the read loop - blocking there would serialise every flow and turn a 120 ms
 * delay into a near-total stall.
 *
 * <h2>Threading</h2>
 *
 * <p>Exactly three threads, regardless of how many conversations are in flight:
 *
 * <ul>
 *   <li><b>orbis-tun</b> reads the TUN, parses in place, and either sends
 *       immediately or hands the packet to the delay queue.
 *   <li><b>orbis-select</b> owns the {@link Selector}: it registers new flows,
 *       drains replies back into the TUN, evicts idle flows and publishes stats.
 *       It is the only thread that writes to the tunnel, so no lock is needed.
 *   <li><b>orbis-delay</b> drains the {@link DelayQueue} once each packet is due.
 * </ul>
 *
 * <p>An earlier version ran a thread and a blocking socket per flow with no
 * eviction, which meant hundreds of threads and file descriptors after a few
 * minutes of scrolling - browsers are routed too, so every DNS lookup and every
 * CDN connection opened another one.
 */
public class OrbisVpnService extends VpnService {

    private static final String TAG = "OrbisVpn";

    public static final String ACTION_START = "com.orbis.app.vpn.START";
    public static final String ACTION_STOP = "com.orbis.app.vpn.STOP";

    /** A {@link Friction} as {@link Friction#toArray()}. */
    public static final String EXTRA_FRICTION = "friction";

    /** Which packages this session may route. */
    public static final String EXTRA_ROUTE_PACKAGES = "routePackages";

    /**
     * Stop this session on a timer, regardless of what the screen is doing.
     *
     * <p>The automatic path does not use it - there, the accessibility gate's
     * watchdog owns teardown. It exists for the manual diagnostic on the Controls
     * screen, which nothing else would ever bring down: a tunnel raised by hand
     * used to stay up until the user remembered to stop it, dropping every routed
     * app's TCP the whole time.
     */
    public static final String EXTRA_AUTO_STOP_MILLIS = "autoStopMillis";

    /** How long the manual diagnostic on the Controls screen runs for. */
    public static final long MANUAL_TEST_MILLIS = 30_000L;

    private static final String CHANNEL_ID = "orbis_throttle";
    private static final int NOTIFICATION_ID = 1;

    private static final String SESSION = "ORBIS";
    private static final String TUNNEL_ADDRESS_V4 = "10.111.222.2";
    private static final String TUNNEL_ADDRESS_V6 = "fd00:1:2:3::2";
    private static final int MTU = 1500;

    private static final int IPV4_HEADER_BYTES = 20;
    private static final int UDP_HEADER_BYTES = 8;

    /** How long the selector waits before doing its housekeeping pass. */
    private static final long SELECT_TIMEOUT_MILLIS = 1_000L;

    /** A conversation this quiet is over; its channel is closed. */
    private static final long FLOW_IDLE_MILLIS = 30_000L;

    /** Ceiling on concurrent conversations. */
    private static final int MAX_FLOWS = 512;

    /**
     * Deferred packets held at once, i.e. the relay's memory ceiling. Beyond it
     * packets are dropped.
     */
    private static final int MAX_PENDING_PACKETS = 128;

    /** Replies drained per flow per select, so one busy flow cannot starve others. */
    private static final int MAX_REPLIES_PER_SELECT = 16;

    /**
     * Download bytes a flow may carry before the squeeze applies to it -
     * comfortably more than a QUIC handshake, far less than a video.
     */
    private static final long BULK_FLOW_BYTES = 16L * 1024L;

    private static final long THREAD_JOIN_MILLIS = 500L;

    private static final StateHolder<Boolean> RUNNING = new StateHolder<>(false);

    /**
     * The packages the live tunnel is routing, or empty when it is down.
     *
     * <p>The gate compares against this rather than remembering what it last
     * asked for: the service is the only thing that knows whether a request
     * actually took effect, and local copies went stale whenever the watchdog
     * stopped the tunnel behind the gate's back.
     */
    private static final StateHolder<List<String>> ROUTED =
            new StateHolder<>(Collections.<String>emptyList());

    private static final StateHolder<TunnelStats> STATS = new StateHolder<>(TunnelStats.IDLE);

    /** Immutable, swapped whole, so all three relay threads see a consistent set. */
    private static volatile Friction friction = Friction.NONE;

    /** When the current tunnel's pulse started; its squeezes are timed from here. */
    private static volatile long pulseEpochNanos = 0L;

    private static volatile String status = "idle";

    /**
     * Diagnostics are surfaced in the UI rather than logged, because ColorOS
     * silently drops this app's logcat output and a blackholing tunnel is
     * otherwise indistinguishable from a working one.
     *
     * <p>Atomic, not volatile longs: they are incremented from the reader, the
     * selector and the delay thread at once, and volatile gives visibility
     * without atomicity - the counts silently undercounted.
     */
    private static final AtomicLong udpPacketsForwarded = new AtomicLong(0);

    private static final AtomicLong tcpPacketsDropped = new AtomicLong(0);
    private static final AtomicLong packetsRead = new AtomicLong(0);
    private static final AtomicLong packetsDropped = new AtomicLong(0);

    /** Download bytes delivered to the app, and packets the squeeze held back. */
    private static final AtomicLong bytesIn = new AtomicLong(0);

    private static final AtomicLong packetsPoliced = new AtomicLong(0);

    /** One UDP conversation, pinned to a connected {@link DatagramChannel}. */
    private static final class UdpFlow {
        final long key;
        final DatagramChannel channel;
        final boolean ipv6;

        /** The app's own address; the destination of every reply. */
        final int localAddressV4;

        /** The remote peer; the source of every reply. */
        final int remoteAddressV4;

        final byte[] localAddressV6;
        final byte[] remoteAddressV6;
        final int localPort;
        final int remotePort;

        /** Payload offset of a reply, i.e. where its headers stop. */
        final int replyHeaderBytes;

        volatile long lastUsedMillis = 0L;
        volatile boolean closed = false;

        /** Download bytes this flow has carried. Selector thread only. */
        long received = 0L;

        UdpFlow(
                long key,
                DatagramChannel channel,
                boolean ipv6,
                int localAddressV4,
                int remoteAddressV4,
                byte[] localAddressV6,
                byte[] remoteAddressV6,
                int localPort,
                int remotePort) {
            this.key = key;
            this.channel = channel;
            this.ipv6 = ipv6;
            this.localAddressV4 = localAddressV4;
            this.remoteAddressV4 = remoteAddressV4;
            this.localAddressV6 = localAddressV6;
            this.remoteAddressV6 = remoteAddressV6;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.replyHeaderBytes =
                    ipv6
                            ? Ipv6.HEADER_BYTES + UDP_HEADER_BYTES
                            : IPV4_HEADER_BYTES + UDP_HEADER_BYTES;
        }

        /**
         * IPv6 keys fold a 128-bit address into 32 bits, so a hit has to be
         * confirmed against the full address before the flow is reused.
         */
        boolean matchesV6(byte[] packet, int offset) {
            if (remoteAddressV6 == null) {
                return false;
            }
            for (int index = 0; index < Ipv6.ADDRESS_BYTES; index++) {
                if (remoteAddressV6[index] != packet[offset + index]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A packet waiting out its throttle delay.
     *
     * <p>Instances are pooled and reused: at video bitrates a fresh buffer per
     * deferred packet was the app's main source of memory pressure.
     */
    private static final class Pending implements Delayed {
        final byte[] payload = new byte[MTU];
        final ByteBuffer buffer = ByteBuffer.wrap(payload);
        UdpFlow flow;
        int length;
        long dueAtNanos;

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(dueAtNanos, ((Pending) other).dueAtNanos);
        }
    }

    private ParcelFileDescriptor tunnel;
    private Thread reader;
    private Thread selectorThread;
    private Thread delayThread;
    private Selector selector;

    private final ConcurrentHashMap<Long, UdpFlow> flowsV4 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, UdpFlow> flowsV6 = new ConcurrentHashMap<>();

    /** Channels are registered on the selector thread; see the class comment. */
    private final ConcurrentLinkedQueue<UdpFlow> pendingRegistrations =
            new ConcurrentLinkedQueue<>();

    private final DelayQueue<Pending> delayed = new DelayQueue<>();
    private final ArrayBlockingQueue<Pending> pendingPool =
            new ArrayBlockingQueue<>(MAX_PENDING_PACKETS);

    /** Polices downloads to the pulse's ceiling. Selector thread only. */
    private final TokenBucket inboundBucket = new TokenBucket();

    /** Carries the routed app's TCP, which this relay can only drop. */
    private TcpProxy tcpProxy;

    private volatile boolean active = false;

    /**
     * What this tunnel is currently routing; empty when it is down. Volatile:
     * written by the lifecycle thread, read by the selector's TCP check.
     */
    private volatile List<String> routedNow = Collections.emptyList();

    /** Watches for an app that has moved to TCP. Selector thread only. */
    private final TcpStarvationDetector tcpStarvation = new TcpStarvationDetector();

    /** Set once this session has stood down, so it trips only once. */
    private volatile boolean stoodDown = false;

    /**
     * Every start and stop runs here, one at a time.
     *
     * <p>{@code establish()} is a binder round trip and {@link #shutdown} joins
     * three threads, so neither belongs on the main thread - and re-pointing the
     * tunnel at a different app is a shutdown immediately followed by a start,
     * which must not interleave with another request to do the same thing.
     */
    private HandlerThread lifecycleThread;

    private Handler lifecycleHandler;

    /** Bounded manual sessions stop themselves; see {@link #EXTRA_AUTO_STOP_MILLIS}. */
    private final Runnable autoStop =
            () -> {
                if (active) {
                    shutdown();
                    status = "test window ended";
                    publishStats();
                    stopSelf();
                }
            };

    public static StateHolder<Boolean> isRunning() {
        return RUNNING;
    }

    public static StateHolder<List<String>> routedApps() {
        return ROUTED;
    }

    /**
     * Live counters, published by the service rather than polled.
     *
     * <p>The UI used to sample the counters on a 1 Hz timer that ran whether or
     * not the tunnel was up, redrawing the whole screen every second.
     */
    public static StateHolder<TunnelStats> tunnelStats() {
        return STATS;
    }

    /** The friction the live tunnel is applying, for the gate's change check. */
    public static Friction currentFriction() {
        return friction;
    }

    public static String status() {
        return status;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("orbis-vpn-lifecycle");
        thread.start();
        lifecycleThread = thread;
        lifecycleHandler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Read the intent on this thread: it is recycled once onStartCommand
        // returns, so the lifecycle thread must never be handed the object itself.
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            post(
                    () -> {
                        shutdown();
                        stopSelf();
                    });
            return START_NOT_STICKY;
        }

        Friction requestedFriction =
                Friction.fromArray(intent == null ? null : intent.getLongArrayExtra(EXTRA_FRICTION));
        List<String> requestedRoute =
                intent == null
                        ? Collections.<String>emptyList()
                        : valueOrEmpty(intent.getStringArrayListExtra(EXTRA_ROUTE_PACKAGES));
        long autoStopMillis =
                intent == null ? 0L : intent.getLongExtra(EXTRA_AUTO_STOP_MILLIS, 0L);

        post(() -> handleStart(requestedFriction, requestedRoute, autoStopMillis));
        return START_STICKY;
    }

    private static List<String> valueOrEmpty(List<String> value) {
        return value == null ? Collections.<String>emptyList() : value;
    }

    private void post(Runnable block) {
        Handler handler = lifecycleHandler;
        if (handler == null) {
            block.run();
        } else {
            handler.post(block);
        }
    }

    /**
     * Brings the tunnel up, re-points it at a different app, or just re-scales it.
     *
     * <p>Android fixes the allow-list at {@code establish()} time, so a change of
     * routed app is a full rebuild. A change of friction alone is not - that is
     * adopted in place, keeping the pulse's rhythm, which is what lets the
     * throttle track usage across a long session rather than staying frozen at
     * whatever it was when the feed first appeared.
     */
    private void handleStart(Friction requested, List<String> route, long autoStopMillis) {
        if (route.isEmpty()) {
            status = "nothing to route";
            publishStats();
            // START_STICKY redelivers a null intent after the process is killed,
            // which lands here with no route. Staying alive with nothing to do
            // would leave an idle service the system keeps restarting.
            if (!active) {
                stopSelf();
            }
            return;
        }

        if (active && route.equals(routedNow)) {
            friction = requested;
            status = "tunnel up, " + describe(requested);
            armAutoStop(autoStopMillis);
            publishStats();
            return;
        }

        if (active) {
            shutdown();
        }

        friction = requested;
        start(route);
        armAutoStop(autoStopMillis);
    }

    private void armAutoStop(long millis) {
        if (lifecycleHandler == null) {
            return;
        }
        lifecycleHandler.removeCallbacks(autoStop);
        if (millis > 0L) {
            lifecycleHandler.postDelayed(autoStop, millis);
        }
    }

    private synchronized void start(List<String> route) {
        if (active) {
            return;
        }

        // Started before establish(): the proxy's port has to go into the
        // tunnel's own config, and Android fixes that at establish() time.
        TcpProxy proxy;
        try {
            proxy =
                    new TcpProxy(
                            socket -> protect(socket),
                            () ->
                                    friction.downloadCeilingAt(
                                            pulseElapsedMillis(System.nanoTime())));
            proxy.start();
        } catch (Throwable unavailable) {
            proxy = null;
        }
        tcpProxy = proxy;

        Builder builder =
                new Builder()
                        .setSession(SESSION)
                        .addAddress(TUNNEL_ADDRESS_V4, 32)
                        .addRoute("0.0.0.0", 0)
                        .setMtu(MTU)
                        // Without this the descriptor is non-blocking: read()
                        // returns 0 immediately and forever, so the relay spins at
                        // 100% CPU, forwards nothing, and silently blackholes every
                        // routed app.
                        .setBlocking(true);

        // The routed app's TCP goes to the proxy on loopback instead of into the
        // tunnel, where it would only be dropped. Apps that ignore the proxy still
        // have their TCP dropped, and the safety valve still covers them.
        if (proxy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxy.getPort()));
            } catch (Throwable refused) {
                status = "proxy setup failed: " + refused.getMessage();
            }
        }

        // Claiming IPv6 as well. Omitting it makes Android mark ::/0 unreachable
        // for the routed apps, which kills most of their traffic outright.
        try {
            builder.addAddress(TUNNEL_ADDRESS_V6, 128);
            builder.addRoute("::", 0);
        } catch (Throwable refused) {
            status = "IPv6 setup failed: " + refused.getMessage();
        }

        int allowed = 0;
        for (String packageName : route) {
            try {
                builder.addAllowedApplication(packageName);
                allowed++;
            } catch (PackageManager.NameNotFoundException absent) {
                // Not installed on this device; nothing to route.
            }
        }
        if (allowed == 0) {
            status = "no target apps installed";
            publishStats();
            stopSelf();
            return;
        }

        Selector openedSelector;
        try {
            openedSelector = Selector.open();
        } catch (Throwable failed) {
            status = "selector open failed: " + failed.getMessage();
            publishStats();
            stopSelf();
            return;
        }

        try {
            tunnel = builder.establish();
        } catch (Throwable failed) {
            status = "establish failed: " + failed.getMessage();
            tunnel = null;
        }

        ParcelFileDescriptor descriptor = tunnel;
        if (descriptor == null) {
            if ("idle".equals(status)) {
                status = "establish() returned null";
            }
            closeQuietly(openedSelector);
            RUNNING.set(false);
            publishStats();
            stopSelf();
            return;
        }

        goForeground();

        active = true;
        routedNow = route;
        ROUTED.set(route);
        RUNNING.set(true);
        packetsRead.set(0);
        udpPacketsForwarded.set(0);
        tcpPacketsDropped.set(0);
        packetsDropped.set(0);
        bytesIn.set(0);
        packetsPoliced.set(0);

        // Each tunnel starts its own rhythm, squeeze first, so the drag lands the
        // moment a feed opens. The bucket starts empty for the same reason.
        pulseEpochNanos = System.nanoTime();
        inboundBucket.reset();
        tcpStarvation.reset();
        stoodDown = false;

        selector = openedSelector;
        // Tops the pool back up rather than only filling it once, so a restart
        // cannot leave the relay permanently short of slots.
        while (pendingPool.remainingCapacity() > 0) {
            pendingPool.offer(new Pending());
        }

        status = "tunnel up, " + describe(friction);
        publishStats();
        Log.i(TAG, "tunnel up for " + join(route) + ", " + describe(friction));

        // Both streams wrap the *same* descriptor. Neither is ever closed: the
        // ParcelFileDescriptor owns that fd, and closing it three times risks
        // yanking a number that has already been recycled by another thread.
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());

        selectorThread = named("orbis-select", () -> selectLoop(openedSelector, output));
        delayThread = named("orbis-delay", this::delayLoop);
        reader = named("orbis-tun", () -> relay(input));
    }

    private static Thread named(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.start();
        return thread;
    }

    /**
     * Keeps the tunnel alive once ORBIS is backgrounded, which is exactly when the
     * user is scrolling. The notification is deliberately low importance: it is a
     * disclosure that traffic is being shaped, not something to interrupt with.
     */
    private void goForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.throttle_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.throttle_notification_title))
                        .setContentText(getString(R.string.throttle_notification_text))
                        .setSmallIcon(R.drawable.ic_orbis_notification)
                        .setOngoing(true)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---------------------------------------------------------------- outbound

    private void relay(FileInputStream input) {
        byte[] buffer = new byte[MTU];
        // One buffer over the read array, re-windowed per packet, so forwarding
        // costs no allocation and no copy at all.
        ByteBuffer sendBuffer = ByteBuffer.wrap(buffer);
        Ipv4.UdpView viewV4 = new Ipv4.UdpView();
        Ipv6.UdpView viewV6 = new Ipv6.UdpView();

        // Include the pulse: it is the only way to see that the throttle actually
        // scaled with usage rather than sitting at the base value.
        status = "relay running, " + describe(friction);

        try {
            while (active) {
                int read = input.read(buffer);
                // A negative read is EOF - the descriptor has been closed under
                // us, and the old code treated that as "nothing yet" and spun.
                if (read < 0) {
                    break;
                }
                // setBlocking(true) means this should not happen; the sleep is
                // only so that a driver which disagrees cannot peg a core.
                if (read == 0) {
                    Thread.sleep(1);
                    continue;
                }
                packetsRead.incrementAndGet();

                int version = (buffer[0] & 0xF0) >> 4;
                if (version == 4) {
                    handleIpv4(buffer, read, sendBuffer, viewV4);
                } else if (version == 6) {
                    handleIpv6(buffer, read, sendBuffer, viewV6);
                }
            }
        } catch (Throwable stopped) {
            if (active) {
                status =
                        "relay died: "
                                + stopped.getClass().getSimpleName()
                                + ": "
                                + stopped.getMessage();
                Log.e(TAG, "relay stopped: " + stopped.getMessage());
            }
        }
    }

    private void handleIpv4(byte[] buffer, int read, ByteBuffer sendBuffer, Ipv4.UdpView view) {
        Ipv4.Header header = Ipv4.parseHeader(buffer, read);
        if (header == null) {
            return;
        }
        if (header.getProtocol() == Ipv4.PROTOCOL_TCP) {
            tcpPacketsDropped.incrementAndGet();
            return;
        }
        if (header.getProtocol() != Ipv4.PROTOCOL_UDP) {
            return;
        }
        if (!Ipv4.parseUdpInto(buffer, read, view)) {
            return;
        }

        // srcPort | dstPort | dstAddr packs the 5-tuple exactly into 64 bits, so
        // the map lookup needs no key object and no string building.
        long key =
                ((long) view.sourcePort << 48)
                        | ((long) view.destinationPort << 32)
                        | (view.destinationAddress & 0xFFFFFFFFL);

        UdpFlow existing = flowsV4.get(key);
        UdpFlow flow;
        if (existing != null && !existing.closed) {
            flow = existing;
        } else {
            if (existing != null) {
                closeFlow(existing);
            }
            flow = openFlowV4(key, view);
            if (flow == null) {
                return;
            }
        }

        forward(flow, buffer, sendBuffer, view.payloadOffset, view.payloadLength);
    }

    private void handleIpv6(byte[] buffer, int read, ByteBuffer sendBuffer, Ipv6.UdpView view) {
        Integer next = Ipv6.nextHeader(buffer, read);
        if (next == null) {
            return;
        }
        if (next == Ipv6.NEXT_HEADER_TCP) {
            tcpPacketsDropped.incrementAndGet();
            return;
        }
        if (next != Ipv6.NEXT_HEADER_UDP) {
            return;
        }
        if (!Ipv6.parseUdpInto(buffer, read, view)) {
            return;
        }

        // A 128-bit address cannot share the 64-bit key, so it is folded to 32
        // bits and the hit confirmed against the full address below.
        int addressHash = addressHash(buffer, Ipv6.DESTINATION_OFFSET);
        long key =
                ((long) view.sourcePort << 48)
                        | ((long) view.destinationPort << 32)
                        | (addressHash & 0xFFFFFFFFL);

        UdpFlow existing = flowsV6.get(key);
        UdpFlow flow;
        if (existing != null
                && !existing.closed
                && existing.matchesV6(buffer, Ipv6.DESTINATION_OFFSET)) {
            flow = existing;
        } else {
            if (existing != null) {
                closeFlow(existing);
            }
            flow = openFlowV6(key, buffer, view);
            if (flow == null) {
                return;
            }
        }

        forward(flow, buffer, sendBuffer, view.payloadOffset, view.payloadLength);
    }

    /**
     * Sends now, or queues the packet until its delay expires.
     *
     * <p>Never sleeps: the read loop has to keep draining the TUN or every other
     * flow stalls behind this one.
     */
    private void forward(
            UdpFlow flow, byte[] buffer, ByteBuffer sendBuffer, int payloadOffset, int payloadLength) {

        flow.lastUsedMillis = SystemClock.elapsedRealtime();

        // Delayed only while squeezed: between squeezes the feed runs normally.
        long delay = friction.delayAt(pulseElapsedMillis(System.nanoTime()));
        if (delay <= 0L) {
            // Window the shared buffer onto this payload - no copy, no allocation.
            ((Buffer) sendBuffer).clear();
            ((Buffer) sendBuffer).position(payloadOffset);
            ((Buffer) sendBuffer).limit(payloadOffset + payloadLength);
            send(flow, sendBuffer);
            return;
        }

        // The pool is the back-pressure: when every slot is in flight the packet
        // is dropped rather than queued. Dropping is the correct failure mode for
        // a UDP throttle, and it is what bounds the relay's memory.
        Pending pending = pendingPool.poll();
        if (pending == null) {
            packetsDropped.incrementAndGet();
            return;
        }

        System.arraycopy(buffer, payloadOffset, pending.payload, 0, payloadLength);
        pending.flow = flow;
        pending.length = payloadLength;
        pending.dueAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        delayed.put(pending);
    }

    private void delayLoop() {
        while (active) {
            Pending pending;
            try {
                pending = delayed.take();
            } catch (InterruptedException stopped) {
                break;
            }

            UdpFlow flow = pending.flow;
            if (flow != null && !flow.closed) {
                ((Buffer) pending.buffer).clear();
                ((Buffer) pending.buffer).limit(pending.length);
                send(flow, pending.buffer);
            }

            pending.flow = null;
            pendingPool.offer(pending);
        }
    }

    private void send(UdpFlow flow, ByteBuffer buffer) {
        try {
            flow.channel.write(buffer);
            udpPacketsForwarded.incrementAndGet();
        } catch (Throwable broken) {
            closeFlow(flow);
        }
    }

    // ----------------------------------------------------------------- inbound

    /**
     * The one thread that touches the {@link Selector} and the one that writes to
     * the tunnel. Also does the housekeeping - registration, idle eviction, stats
     * - because it already wakes at least once a second.
     */
    private void selectLoop(Selector selector, FileOutputStream output) {
        byte[] out = new byte[MTU];
        ByteBuffer replyBuffer = ByteBuffer.wrap(out);

        try {
            while (active) {
                registerPending(selector);
                selector.select(SELECT_TIMEOUT_MILLIS);
                if (!active) {
                    break;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid() || !key.isReadable()) {
                        continue;
                    }
                    drainReplies(key, out, replyBuffer, output);
                }

                evictIdleFlows();
                publishStats();
                checkTcpFallback();
            }
        } catch (Throwable stopped) {
            if (active) {
                status =
                        "selector died: "
                                + stopped.getClass().getSimpleName()
                                + ": "
                                + stopped.getMessage();
                Log.e(TAG, "selector stopped: " + stopped.getMessage());
            }
        }
    }

    /**
     * The safety valve: an app whose traffic has moved to a road ORBIS cannot
     * carry is left alone.
     *
     * <p>Rather than starve it, ORBIS stands down for that app for a while;
     * {@link TcpFallback} decides how long, and the gate will not raise the
     * tunnel for it until then. Runs on the selector thread, once per pass.
     *
     * <p>Only automatic sessions are judged. The manual test routes every app at
     * once, and its counters describe no single one of them.
     */
    private void checkTcpFallback() {
        if (stoodDown) {
            return;
        }
        List<String> route = routedNow;
        if (route.size() != 1) {
            return;
        }

        // Counts what the proxy carried as well as what the relay forwarded. An
        // app whose video now comes over the TCP proxy has a UDP byte count of
        // nearly zero, and the phone's strict Private DNS keeps knocking on TCP
        // 853 - which ignores an HTTP proxy - so judging on UDP alone stood down
        // the very apps ORBIS was throttling successfully.
        TcpProxy proxy = tcpProxy;
        boolean starving =
                tcpStarvation.starving(
                        SystemClock.elapsedRealtime(),
                        tcpPacketsDropped.get(),
                        bytesIn.get() + (proxy == null ? 0L : proxy.bytesCarried()));
        if (!starving) {
            return;
        }

        stoodDown = true;
        String packageName = route.get(0);
        long minutes = TcpFallback.standDown(packageName, System.currentTimeMillis()) / 60_000L;
        status =
                packageName
                        + " is on TCP, which ORBIS cannot relay - left at full speed for "
                        + minutes
                        + "m";
        Log.i(TAG, status);
        post(
                () -> {
                    shutdown();
                    stopSelf();
                });
    }

    private void registerPending(Selector selector) {
        while (true) {
            UdpFlow flow = pendingRegistrations.poll();
            if (flow == null) {
                return;
            }
            if (flow.closed) {
                continue;
            }
            try {
                flow.channel.register(selector, SelectionKey.OP_READ, flow);
            } catch (Throwable refused) {
                closeFlow(flow);
            }
        }
    }

    /**
     * Reads every reply the kernel has buffered for one flow and writes each back
     * into the tunnel.
     *
     * <p>The payload is read straight into the position it will occupy in the
     * finished packet, so the headers are simply written in front of it and
     * nothing is ever copied.
     */
    private void drainReplies(
            SelectionKey key, byte[] out, ByteBuffer replyBuffer, FileOutputStream output)
            throws Exception {

        Object attachment = key.attachment();
        if (!(attachment instanceof UdpFlow)) {
            return;
        }
        UdpFlow flow = (UdpFlow) attachment;
        if (flow.closed) {
            return;
        }

        for (int round = 0; round < MAX_REPLIES_PER_SELECT; round++) {
            ((Buffer) replyBuffer).clear();
            ((Buffer) replyBuffer).position(flow.replyHeaderBytes);

            int read;
            try {
                read = flow.channel.read(replyBuffer);
            } catch (Throwable broken) {
                closeFlow(flow);
                return;
            }
            if (read <= 0) {
                return;
            }

            flow.lastUsedMillis = SystemClock.elapsedRealtime();

            // The squeeze. Downloads beyond this phase's ceiling are dropped, and
            // the sender's congestion control reads that as a slow network and
            // backs off - which is what the player then shows. This is the
            // direction the video arrives on; the old delay never touched it.
            //
            // Only a flow that is already streaming is policed. Measured on
            // CPH2585: policing every flow from one shared allowance let the video
            // connection starve new connections' handshakes, the client decided
            // its QUIC route was broken, fell back to TCP - which this relay drops
            // - and a Short froze on its first frame. A handshake is a few KB, so
            // a flow runs free until it has carried BULK_FLOW_BYTES.
            flow.received += read;
            if (flow.received > BULK_FLOW_BYTES) {
                long nowNanos = System.nanoTime();
                long ceiling = friction.downloadCeilingAt(pulseElapsedMillis(nowNanos));
                if (!inboundBucket.tryTake(read, ceiling, nowNanos)) {
                    packetsPoliced.incrementAndGet();
                    continue;
                }
            }

            int total;
            if (flow.ipv6) {
                if (flow.remoteAddressV6 == null || flow.localAddressV6 == null) {
                    return;
                }
                total =
                        Ipv6.buildUdpInto(
                                out,
                                flow.remoteAddressV6,
                                flow.localAddressV6,
                                flow.remotePort,
                                flow.localPort,
                                out,
                                flow.replyHeaderBytes,
                                read);
            } else {
                total =
                        Ipv4.buildUdpInto(
                                out,
                                flow.remoteAddressV4,
                                flow.localAddressV4,
                                flow.remotePort,
                                flow.localPort,
                                out,
                                flow.replyHeaderBytes,
                                read);
            }

            output.write(out, 0, total);
            bytesIn.addAndGet(read);
        }
    }

    // ------------------------------------------------------------------- flows

    private UdpFlow openFlowV4(long key, Ipv4.UdpView view) {
        if (!hasFlowCapacity()) {
            return null;
        }

        DatagramChannel channel =
                openChannel(Ipv4.toBytes(view.destinationAddress), view.destinationPort);
        if (channel == null) {
            return null;
        }

        UdpFlow flow =
                new UdpFlow(
                        key,
                        channel,
                        false,
                        view.sourceAddress,
                        view.destinationAddress,
                        null,
                        null,
                        view.sourcePort,
                        view.destinationPort);
        flowsV4.put(key, flow);
        enqueueRegistration(flow);
        return flow;
    }

    private UdpFlow openFlowV6(long key, byte[] packet, Ipv6.UdpView view) {
        if (!hasFlowCapacity()) {
            return null;
        }

        // Copied once per flow rather than twice per packet.
        byte[] remote =
                Arrays.copyOfRange(
                        packet,
                        Ipv6.DESTINATION_OFFSET,
                        Ipv6.DESTINATION_OFFSET + Ipv6.ADDRESS_BYTES);
        byte[] local =
                Arrays.copyOfRange(
                        packet, Ipv6.SOURCE_OFFSET, Ipv6.SOURCE_OFFSET + Ipv6.ADDRESS_BYTES);

        DatagramChannel channel = openChannel(remote, view.destinationPort);
        if (channel == null) {
            return null;
        }

        UdpFlow flow =
                new UdpFlow(
                        key,
                        channel,
                        true,
                        0,
                        0,
                        local,
                        remote,
                        view.sourcePort,
                        view.destinationPort);
        flowsV6.put(key, flow);
        enqueueRegistration(flow);
        return flow;
    }

    private DatagramChannel openChannel(byte[] address, int port) {
        try {
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(false);
            // Without protect() our own packets would loop back into the tunnel
            // and never reach the network.
            if (!protect(channel.socket())) {
                channel.close();
                return null;
            }
            // Connecting pins the peer, so replies arrive on a plain read() with
            // no SocketAddress to allocate or compare per packet.
            channel.connect(new InetSocketAddress(InetAddress.getByAddress(address), port));
            return channel;
        } catch (Throwable failed) {
            Log.d(TAG, "could not open flow: " + failed.getMessage());
            return null;
        }
    }

    private void enqueueRegistration(UdpFlow flow) {
        flow.lastUsedMillis = SystemClock.elapsedRealtime();
        pendingRegistrations.add(flow);
        Selector current = selector;
        if (current != null) {
            current.wakeup();
        }
    }

    /**
     * A hard ceiling on concurrent conversations.
     *
     * <p>Browsers are routed too, so a busy page can open flows faster than they
     * expire. Refusing beyond the cap costs one dropped packet; running without
     * one costs file descriptors until the process dies.
     */
    private boolean hasFlowCapacity() {
        if (flowsV4.size() + flowsV6.size() < MAX_FLOWS) {
            return true;
        }
        packetsDropped.incrementAndGet();
        return false;
    }

    private void evictIdleFlows() {
        long now = SystemClock.elapsedRealtime();
        evictIdleFrom(flowsV4, now);
        evictIdleFrom(flowsV6, now);
    }

    private void evictIdleFrom(Map<Long, UdpFlow> flows, long now) {
        if (flows.isEmpty()) {
            return;
        }
        Iterator<UdpFlow> iterator = flows.values().iterator();
        while (iterator.hasNext()) {
            UdpFlow flow = iterator.next();
            if (flow.closed || now - flow.lastUsedMillis > FLOW_IDLE_MILLIS) {
                iterator.remove();
                closeChannel(flow);
            }
        }
    }

    private void closeFlow(UdpFlow flow) {
        Map<Long, UdpFlow> map = flow.ipv6 ? flowsV6 : flowsV4;
        map.remove(flow.key, flow);
        closeChannel(flow);
    }

    private void closeChannel(UdpFlow flow) {
        if (flow.closed) {
            return;
        }
        flow.closed = true;
        try {
            Selector current = selector;
            if (current != null) {
                SelectionKey key = flow.channel.keyFor(current);
                if (key != null) {
                    key.cancel();
                }
            }
        } catch (Throwable ignored) {
            // The key is already gone; nothing to cancel.
        }
        try {
            flow.channel.close();
        } catch (Throwable ignored) {
            // Already closed.
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onRevoke() {
        Log.i(TAG, "consent revoked");
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        // Drop queued work first, so nothing re-establishes a tunnel on the way out.
        if (lifecycleHandler != null) {
            lifecycleHandler.removeCallbacksAndMessages(null);
        }
        shutdown();
        if (lifecycleThread != null) {
            lifecycleThread.quitSafely();
        }
        lifecycleThread = null;
        lifecycleHandler = null;
        super.onDestroy();
    }

    /**
     * Releases the TUN interface and every relay channel.
     *
     * <p>A leaked interface keeps routing the user's traffic after ORBIS is gone,
     * so this must run on every exit path - stop, revoke, destroy and failure.
     */
    private synchronized void shutdown() {
        if (!active && tunnel == null) {
            return;
        }
        active = false;
        routedNow = Collections.emptyList();
        ROUTED.set(Collections.<String>emptyList());
        RUNNING.set(false);

        // Wakes the selector out of select() and the delay thread out of take().
        Selector current = selector;
        if (current != null) {
            current.wakeup();
        }
        if (delayThread != null) {
            delayThread.interrupt();
        }

        // The reader is parked in a read() on the TUN fd, which interrupt() does
        // not unblock - closing the descriptor is what ends it.
        try {
            if (tunnel != null) {
                tunnel.close();
            }
        } catch (Throwable ignored) {
            // Already closed.
        }
        tunnel = null;

        // Join before dropping the references, so a fast stop/start cannot leave
        // the previous relay writing into the next tunnel.
        joinQuietly(reader);
        joinQuietly(selectorThread);
        joinQuietly(delayThread);
        reader = null;
        selectorThread = null;
        delayThread = null;

        for (UdpFlow flow : flowsV4.values()) {
            closeChannel(flow);
        }
        for (UdpFlow flow : flowsV6.values()) {
            closeChannel(flow);
        }
        flowsV4.clear();
        flowsV6.clear();
        pendingRegistrations.clear();

        // Recycle the deferred packets rather than dropping the pool on the floor.
        while (true) {
            Pending pending = delayed.poll();
            if (pending == null) {
                break;
            }
            pending.flow = null;
            pendingPool.offer(pending);
        }

        closeQuietly(selector);
        selector = null;

        TcpProxy proxy = tcpProxy;
        if (proxy != null) {
            try {
                proxy.stop();
            } catch (Throwable ignored) {
                // Already stopped.
            }
        }
        tcpProxy = null;

        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // Not in the foreground.
        }

        status =
                "stopped (read="
                        + packetsRead.get()
                        + ", udp="
                        + udpPacketsForwarded.get()
                        + ", tcp dropped="
                        + tcpPacketsDropped.get()
                        + ")";
        publishStats();
        Log.i(TAG, status);
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(THREAD_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Selector selector) {
        if (selector == null) {
            return;
        }
        try {
            selector.close();
        } catch (Throwable ignored) {
            // Already closed.
        }
    }

    /**
     * {@code adb shell dumpsys activity service com.orbis.app/.vpn.OrbisVpnService}
     *
     * <p>ColorOS drops this app's logcat, so this is how the pulse is watched from
     * a computer: one line, cheap enough to poll every second while a feed plays.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (writer == null) {
            return;
        }
        publishStats();
        TunnelStats s = STATS.get();
        writer.println(
                "ORBIS running="
                        + s.isRunning()
                        + " routed="
                        + join(s.getRouted())
                        + " squeezing="
                        + s.isSqueezing()
                        + " squeeze="
                        + s.getSqueezeMillis()
                        + "/"
                        + s.getPulsePeriodMillis()
                        + "ms delay="
                        + s.getDelayMillis()
                        + "ms bytesIn="
                        + s.getBytesIn()
                        + " policed="
                        + s.getPacketsPoliced()
                        + " udpFwd="
                        + s.getUdpForwarded()
                        + " tcpDropped="
                        + s.getTcpDropped()
                        + " shed="
                        + s.getPacketsDropped()
                        + " flows="
                        + s.getActiveFlows()
                        + " tcpCarried="
                        + s.getTcpCarried()
                        + " status="
                        + s.getStatus());
    }

    private void publishStats() {
        TcpProxy proxy = tcpProxy;
        long carried = proxy == null ? 0L : proxy.bytesCarried();
        long paced = proxy == null ? 0L : proxy.chunksPaced();

        STATS.set(
                new TunnelStats(
                        active,
                        packetsRead.get(),
                        udpPacketsForwarded.get(),
                        tcpPacketsDropped.get(),
                        packetsDropped.get(),
                        flowsV4.size() + flowsV6.size(),
                        friction.getDelayMillis(),
                        routedNow,
                        bytesIn.get() + carried,
                        packetsPoliced.get() + paced,
                        carried,
                        active
                                && friction.squeezingAt(
                                        pulseElapsedMillis(System.nanoTime())),
                        friction.getSqueezeMillis(),
                        friction.getPeriodMillis(),
                        status));
    }

    private int addressHash(byte[] packet, int offset) {
        int hash = 1;
        for (int index = 0; index < Ipv6.ADDRESS_BYTES; index++) {
            hash = 31 * hash + packet[offset + index];
        }
        return hash;
    }

    private static long pulseElapsedMillis(long nowNanos) {
        return (nowNanos - pulseEpochNanos) / 1_000_000L;
    }

    private static String describe(Friction friction) {
        if (friction.getPeriodMillis() > 0L) {
            return "squeezing "
                    + friction.getSqueezeMillis()
                    + "ms of every "
                    + friction.getPeriodMillis()
                    + "ms";
        }
        return "delay " + friction.getDelayMillis() + "ms";
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) {
                joined.append('+');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    /**
     * Every package ORBIS is <i>ever</i> willing to route - the upper bound, not
     * the routing for any one session.
     *
     * <p>Browsers are included because {@code youtube.com/shorts} opens in a
     * browser on many devices, and detection alone throttles nothing if the
     * traffic never enters the tunnel.
     *
     * <p>A live session routes one of these at a time, chosen by
     * {@link com.orbis.app.throttle.ThrottleEngine#routeFor} from the surface on
     * screen. Only the manual diagnostic uses the whole set.
     *
     * <p>Caveat worth keeping in mind: while a browser is routed, <i>all</i> of
     * its traffic goes through the tunnel, not just the Shorts tab - a VPN cannot
     * see tabs.
     *
     * <p>WhatsApp is absent, and must stay absent.
     */
    public static List<String> routedPackages() {
        List<String> packages = new ArrayList<>(TargetApp.getThrottleablePackages());
        packages.addAll(BrowserPackages.ALL);
        return packages;
    }

    /**
     * @param routePackages the apps this session may carry. The automatic gate
     *     always names the single app on screen.
     * @param autoStopMillis a hard stop, or 0 to leave teardown to the caller.
     */
    public static void start(
            Context context, Friction friction, List<String> routePackages, long autoStopMillis) {
        context.startService(
                new Intent(context, OrbisVpnService.class)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_FRICTION, friction.toArray())
                        .putStringArrayListExtra(
                                EXTRA_ROUTE_PACKAGES, new ArrayList<>(routePackages))
                        .putExtra(EXTRA_AUTO_STOP_MILLIS, autoStopMillis));
    }

    public static void start(Context context, Friction friction, List<String> routePackages) {
        start(context, friction, routePackages, 0L);
    }

    public static void stop(Context context) {
        context.startService(
                new Intent(context, OrbisVpnService.class).setAction(ACTION_STOP));
    }
}
