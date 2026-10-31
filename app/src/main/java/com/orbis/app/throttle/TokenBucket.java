package com.orbis.app.throttle;

/**
 * A download-rate policer: packets beyond the rate are dropped, not queued.
 *
 * <p>Dropping is the right tool here. Queueing a video's downloads would need
 * seconds of buffer at video bitrates - the unbounded-memory trap the delay
 * queue's fixed pool exists to avoid - while a dropped packet is simply what the
 * sender's congestion control is built to react to: it backs off to the rate on
 * offer, and the player sees a slow network.
 *
 * <p>Not thread-safe: the tunnel only touches it from the selector thread.
 */
public final class TokenBucket {

    /** How much unused allowance may be saved up, in seconds of the rate. */
    private static final double BURST_SECONDS = 0.25;

    /** Always room for one full packet, or a low rate could pass nothing. */
    private static final double MIN_BURST_BYTES = 1_500.0;

    private double tokens = 0.0;
    private long lastNanos = 0L;

    /**
     * Whether the last reading means anything. A flag, not a sentinel value: a
     * clock reading can be 0, and treating it as "never started" stopped the
     * bucket refilling at all.
     */
    private boolean timing = false;

    /**
     * @param bytes the size of the packet asking to pass
     * @param rateBytesPerSecond the ceiling, or 0 for none
     * @param nowNanos the current clock reading
     * @return whether the packet may pass
     */
    public boolean tryTake(int bytes, long rateBytesPerSecond, long nowNanos) {
        if (rateBytesPerSecond <= 0L) {
            // Unlimited. Start the next limited stretch from empty, and stop the
            // clock, so a squeeze bites at once instead of refilling for all the
            // time spent at full speed.
            tokens = 0.0;
            timing = false;
            return true;
        }

        if (timing) {
            double elapsedSeconds = Math.max(nowNanos - lastNanos, 0L) / 1_000_000_000.0;
            double burst = Math.max(rateBytesPerSecond * BURST_SECONDS, MIN_BURST_BYTES);
            tokens = Math.min(burst, tokens + elapsedSeconds * rateBytesPerSecond);
        }
        lastNanos = nowNanos;
        timing = true;

        if (tokens < bytes) {
            return false;
        }
        tokens -= bytes;
        return true;
    }

    public void reset() {
        tokens = 0.0;
        timing = false;
    }
}
