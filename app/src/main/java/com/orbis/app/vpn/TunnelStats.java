package com.orbis.app.vpn;

import java.util.Collections;
import java.util.List;

/** A snapshot of what the relay is doing, for the UI. */
public final class TunnelStats {

    public static final TunnelStats IDLE = new TunnelStats();

    private final boolean running;
    private final long packetsRead;
    private final long udpForwarded;
    private final long tcpDropped;
    private final long packetsDropped;
    private final int activeFlows;
    private final long delayMillis;
    private final List<String> routed;
    private final long bytesIn;
    private final long packetsPoliced;
    private final long tcpCarried;
    private final boolean squeezing;
    private final long squeezeMillis;
    private final long pulsePeriodMillis;
    private final String status;

    public TunnelStats() {
        this(
                false, 0L, 0L, 0L, 0L, 0, 0L, Collections.<String>emptyList(), 0L, 0L, 0L, false,
                0L, 0L, "idle");
    }

    public TunnelStats(
            boolean running,
            long packetsRead,
            long udpForwarded,
            long tcpDropped,
            long packetsDropped,
            int activeFlows,
            long delayMillis,
            List<String> routed,
            long bytesIn,
            long packetsPoliced,
            long tcpCarried,
            boolean squeezing,
            long squeezeMillis,
            long pulsePeriodMillis,
            String status) {
        this.running = running;
        this.packetsRead = packetsRead;
        this.udpForwarded = udpForwarded;
        this.tcpDropped = tcpDropped;
        this.packetsDropped = packetsDropped;
        this.activeFlows = activeFlows;
        this.delayMillis = delayMillis;
        this.routed = Collections.unmodifiableList(routed);
        this.bytesIn = bytesIn;
        this.packetsPoliced = packetsPoliced;
        this.tcpCarried = tcpCarried;
        this.squeezing = squeezing;
        this.squeezeMillis = squeezeMillis;
        this.pulsePeriodMillis = pulsePeriodMillis;
        this.status = status;
    }

    public boolean isRunning() {
        return running;
    }

    public long getPacketsRead() {
        return packetsRead;
    }

    public long getUdpForwarded() {
        return udpForwarded;
    }

    public long getTcpDropped() {
        return tcpDropped;
    }

    /** Packets shed because the delay queue or the flow table was full. */
    public long getPacketsDropped() {
        return packetsDropped;
    }

    public int getActiveFlows() {
        return activeFlows;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    /** The packages this session is routing - what is actually being slowed. */
    public List<String> getRouted() {
        return routed;
    }

    /** Download bytes delivered to the routed app. */
    public long getBytesIn() {
        return bytesIn;
    }

    /** Download packets the squeeze dropped, or chunks it paced. */
    public long getPacketsPoliced() {
        return packetsPoliced;
    }

    /** Download bytes carried over the TCP proxy rather than dropped. */
    public long getTcpCarried() {
        return tcpCarried;
    }

    /** Whether the pulse is squeezing at this moment. */
    public boolean isSqueezing() {
        return squeezing;
    }

    public long getSqueezeMillis() {
        return squeezeMillis;
    }

    public long getPulsePeriodMillis() {
        return pulsePeriodMillis;
    }

    public String getStatus() {
        return status;
    }
}
