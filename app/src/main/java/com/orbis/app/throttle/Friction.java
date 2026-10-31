package com.orbis.app.throttle;

import java.util.Objects;

/**
 * What the tunnel does to the routed app's traffic.
 *
 * <p>A <b>pulse</b>: every period the feed is squeezed for the squeeze length -
 * its downloads held to a trickle and its requests delayed - and for the rest of
 * the cycle it runs normally, up to the open ceiling (0 = no ceiling at all).
 *
 * <p>Pulsing replaced a constant delay because a constant delay could not be
 * felt. Measured on CPH2585: two clients both sat at the 400 ms maximum, yet
 * only one ever felt slower. Added latency barely dents the throughput of a
 * video stream that is already flowing, and these apps pre-load the next few
 * reels, so the delay vanished into their buffers. The delay also only ever
 * touched the upload direction; the video itself arrives on the download. A
 * squeeze caps the download, which is the direction the video comes in on, and
 * the gap between squeezes is what keeps it friction rather than breakage.
 *
 * <p>Immutable, so the tunnel's three threads can share one without locks and
 * the schedule is testable without a device.
 */
public final class Friction {

    /** No friction at all: the tunnel carries traffic untouched. */
    public static final Friction NONE = new Friction(0L, 0L, 0L, 0L, 0L);

    private final long delayMillis;
    private final long periodMillis;
    private final long squeezeMillis;
    private final long squeezeBytesPerSecond;
    private final long openBytesPerSecond;

    /**
     * @param delayMillis extra latency on outgoing packets, during a squeeze only
     * @param periodMillis one full cycle: squeeze, then normal. 0 means never squeeze
     * @param squeezeMillis how much of each cycle is squeezed; always less than the period
     * @param squeezeBytesPerSecond download ceiling while squeezed. Never 0: a trickle, not a cut-off
     * @param openBytesPerSecond download ceiling the rest of the time, or 0 for none
     */
    public Friction(
            long delayMillis,
            long periodMillis,
            long squeezeMillis,
            long squeezeBytesPerSecond,
            long openBytesPerSecond) {
        this.delayMillis = delayMillis;
        this.periodMillis = periodMillis;
        this.squeezeMillis = squeezeMillis;
        this.squeezeBytesPerSecond = squeezeBytesPerSecond;
        this.openBytesPerSecond = openBytesPerSecond;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public long getPeriodMillis() {
        return periodMillis;
    }

    public long getSqueezeMillis() {
        return squeezeMillis;
    }

    public long getSqueezeBytesPerSecond() {
        return squeezeBytesPerSecond;
    }

    public long getOpenBytesPerSecond() {
        return openBytesPerSecond;
    }

    public boolean getActive() {
        return !equals(NONE);
    }

    /**
     * Whether this many millis since the tunnel came up falls in a squeeze.
     *
     * <p>Each cycle <i>starts</i> with its squeeze, so the drag lands the moment
     * a feed opens rather than a few seconds in.
     */
    public boolean squeezingAt(long elapsedMillis) {
        if (periodMillis <= 0L || squeezeMillis <= 0L) {
            return false;
        }
        return Math.floorMod(elapsedMillis, periodMillis) < squeezeMillis;
    }

    /** Download ceiling at this point in the cycle, bytes/second; 0 means unlimited. */
    public long downloadCeilingAt(long elapsedMillis) {
        return squeezingAt(elapsedMillis) ? squeezeBytesPerSecond : openBytesPerSecond;
    }

    /** Outgoing delay: only while squeezed, so normal means normal. */
    public long delayAt(long elapsedMillis) {
        return squeezingAt(elapsedMillis) ? delayMillis : 0L;
    }

    /** For an Intent extra - the service is started, not bound, so no Parcelable. */
    public long[] toArray() {
        return new long[] {
            delayMillis, periodMillis, squeezeMillis, squeezeBytesPerSecond, openBytesPerSecond
        };
    }

    public static Friction fromArray(long[] values) {
        if (values == null || values.length < 5) {
            return NONE;
        }
        return new Friction(values[0], values[1], values[2], values[3], values[4]);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Friction)) {
            return false;
        }
        Friction that = (Friction) other;
        return delayMillis == that.delayMillis
                && periodMillis == that.periodMillis
                && squeezeMillis == that.squeezeMillis
                && squeezeBytesPerSecond == that.squeezeBytesPerSecond
                && openBytesPerSecond == that.openBytesPerSecond;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                delayMillis, periodMillis, squeezeMillis, squeezeBytesPerSecond, openBytesPerSecond);
    }

    @Override
    public String toString() {
        return "Friction(delay=" + delayMillis
                + ", period=" + periodMillis
                + ", squeeze=" + squeezeMillis
                + ", squeezeBps=" + squeezeBytesPerSecond
                + ", openBps=" + openBytesPerSecond
                + ")";
    }
}
