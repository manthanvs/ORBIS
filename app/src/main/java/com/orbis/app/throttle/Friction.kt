package com.orbis.app.throttle

/**
 * What the tunnel does to the routed app's traffic.
 *
 * A **pulse**: every [periodMillis] the feed is squeezed for [squeezeMillis] -
 * its downloads held to a trickle of [squeezeBytesPerSecond] and its requests
 * delayed by [delayMillis] - and for the rest of the cycle it runs normally, up to
 * [openBytesPerSecond] (0 = no ceiling at all).
 *
 * Pulsing replaced a constant delay because a constant delay could not be felt.
 * Measured on CPH2585: Instagram and Morphe both sat at the 400 ms maximum, yet
 * only Morphe ever felt slower. Added latency barely dents the throughput of a
 * video stream that is already flowing, and Instagram and Snapchat pre-load the
 * next few reels, so the delay vanished into their buffers. The delay also only
 * ever touched the upload direction; the video itself arrives on the download.
 * A squeeze caps the download, which is the direction the video comes in on, and
 * the gap between squeezes is what keeps it friction rather than breakage.
 *
 * Pure and immutable, so the tunnel's three threads can share one without locks
 * and the schedule is testable without a device.
 */
data class Friction(
    /** Extra latency on outgoing packets, applied during a squeeze only. */
    val delayMillis: Long = 0L,
    /** One full cycle: squeeze, then normal. 0 means never squeeze. */
    val periodMillis: Long = 0L,
    /** How much of each cycle is squeezed. Always less than [periodMillis]. */
    val squeezeMillis: Long = 0L,
    /** Download ceiling while squeezed. Never 0: a trickle, not a cut-off. */
    val squeezeBytesPerSecond: Long = 0L,
    /** Download ceiling the rest of the time, or 0 for none. */
    val openBytesPerSecond: Long = 0L,
) {
    val active: Boolean get() = this != NONE

    /**
     * Whether [elapsedMillis] since the tunnel came up falls in a squeeze.
     *
     * Each cycle *starts* with its squeeze, so the drag lands the moment a feed
     * opens rather than a few seconds in.
     */
    fun squeezingAt(elapsedMillis: Long): Boolean {
        if (periodMillis <= 0L || squeezeMillis <= 0L) return false
        return Math.floorMod(elapsedMillis, periodMillis) < squeezeMillis
    }

    /** Download ceiling at [elapsedMillis], bytes/second; 0 means unlimited. */
    fun downloadCeilingAt(elapsedMillis: Long): Long =
        if (squeezingAt(elapsedMillis)) squeezeBytesPerSecond else openBytesPerSecond

    /** Outgoing delay at [elapsedMillis]: only while squeezed, so normal means normal. */
    fun delayAt(elapsedMillis: Long): Long =
        if (squeezingAt(elapsedMillis)) delayMillis else 0L

    /** For an Intent extra - the service is started, not bound, so no Parcelable. */
    fun toArray(): LongArray = longArrayOf(
        delayMillis,
        periodMillis,
        squeezeMillis,
        squeezeBytesPerSecond,
        openBytesPerSecond,
    )

    companion object {
        val NONE = Friction()

        fun fromArray(values: LongArray?): Friction {
            if (values == null || values.size < 5) return NONE
            return Friction(
                delayMillis = values[0],
                periodMillis = values[1],
                squeezeMillis = values[2],
                squeezeBytesPerSecond = values[3],
                openBytesPerSecond = values[4],
            )
        }
    }
}

/**
 * A download-rate policer: packets beyond the rate are dropped, not queued.
 *
 * Dropping is the right tool here. Queueing a video's downloads would need
 * seconds of buffer at video bitrates - the unbounded-memory trap the delay
 * queue's fixed pool exists to avoid - while a dropped packet is simply what the
 * sender's congestion control is built to react to: it backs off to the rate on
 * offer, and the player sees a slow network.
 *
 * Not thread-safe: the tunnel only touches it from the selector thread.
 */
class TokenBucket {

    private var tokens = 0.0
    private var lastNanos = 0L

    /**
     * Whether [lastNanos] means anything. A flag, not a sentinel value: a clock
     * reading can be 0, and treating it as "never started" stopped the bucket
     * refilling at all.
     */
    private var timing = false

    /**
     * @param rateBytesPerSecond the ceiling, or 0 for none.
     * @return whether a packet of [bytes] may pass at [nowNanos].
     */
    fun tryTake(bytes: Int, rateBytesPerSecond: Long, nowNanos: Long): Boolean {
        if (rateBytesPerSecond <= 0L) {
            // Unlimited. Start the next limited stretch from empty, and stop the
            // clock, so a squeeze bites at once instead of refilling for all the
            // time spent at full speed.
            tokens = 0.0
            timing = false
            return true
        }

        if (timing) {
            val elapsedSeconds = (nowNanos - lastNanos).coerceAtLeast(0L) / 1_000_000_000.0
            val burst = maxOf(rateBytesPerSecond * BURST_SECONDS, MIN_BURST_BYTES)
            tokens = minOf(burst, tokens + elapsedSeconds * rateBytesPerSecond)
        }
        lastNanos = nowNanos
        timing = true

        if (tokens < bytes) return false
        tokens -= bytes
        return true
    }

    fun reset() {
        tokens = 0.0
        timing = false
    }

    private companion object {
        /** How much unused allowance may be saved up, in seconds of the rate. */
        const val BURST_SECONDS = 0.25

        /** Always room for one full packet, or a low rate could pass nothing. */
        const val MIN_BURST_BYTES = 1_500.0
    }
}
