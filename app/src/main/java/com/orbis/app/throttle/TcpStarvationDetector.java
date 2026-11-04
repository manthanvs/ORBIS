package com.orbis.app.throttle;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Spots a routed app whose traffic has moved to TCP that cannot be carried.
 *
 * <p>The packet relay forwards UDP and drops TCP. That is survivable while an
 * app streams over QUIC, which is UDP - but apps fall back to TCP, and remember
 * having done so. Measured on CPH2585: one YouTube client sent its Shorts video
 * over TCP, the relay dropped every packet, and ORBIS turned "slower" into
 * "frozen on the first frame": 73 TCP packets dropped against 135 KB of UDP in
 * 24 seconds.
 *
 * <p>The signature is TCP being attempted steadily while almost nothing arrives:
 * an app streaming happily downloads far more than the byte ceiling in the window
 * even while squeezed, and an idle app does not keep knocking on TCP.
 *
 * <p>Pure, so the thresholds are testable against the measured patterns.
 */
public final class TcpStarvationDetector {

    private static final class Sample {
        final long atMillis;
        final long tcpDropped;
        final long bytesIn;

        Sample(long atMillis, long tcpDropped, long bytesIn) {
            this.atMillis = atMillis;
            this.tcpDropped = tcpDropped;
            this.bytesIn = bytesIn;
        }
    }

    private final long windowMillis;
    private final long minTcpDrops;
    private final long maxBytesIn;

    private final Deque<Sample> samples = new ArrayDeque<>();

    public TcpStarvationDetector() {
        this(6_000L, 12L, 96L * 1024L);
    }

    public TcpStarvationDetector(long windowMillis, long minTcpDrops, long maxBytesIn) {
        this.windowMillis = windowMillis;
        this.minTcpDrops = minTcpDrops;
        this.maxBytesIn = maxBytesIn;
    }

    /**
     * Records the relay's running totals and reports whether the last window
     * looks like an app trying TCP and getting nothing.
     */
    public boolean starving(long nowMillis, long tcpDropped, long bytesIn) {
        samples.addLast(new Sample(nowMillis, tcpDropped, bytesIn));
        while (samples.size() > 1 && nowMillis - samples.getFirst().atMillis > windowMillis) {
            samples.removeFirst();
        }

        // Judge only a (nearly) full window: the first seconds of every tunnel
        // are handshakes and retries, and look like starvation for a moment.
        Sample oldest = samples.getFirst();
        if (nowMillis - oldest.atMillis < windowMillis * 4 / 5) {
            return false;
        }

        long drops = tcpDropped - oldest.tcpDropped;
        long bytes = bytesIn - oldest.bytesIn;
        return drops >= minTcpDrops && bytes <= maxBytesIn;
    }

    public void reset() {
        samples.clear();
    }
}
