package com.orbis.app.throttle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spots a routed app whose traffic has moved to TCP, which the relay cannot carry.
 *
 * The relay forwards UDP and drops TCP. That is survivable while an app streams
 * over QUIC, which is UDP - but apps fall back to TCP, and remember having done
 * so. Measured on CPH2585: Morphe sent its Shorts video over TCP, the relay
 * dropped every packet, and ORBIS turned "slower" into "frozen on the first
 * frame": 73 TCP packets dropped against 135 KB of UDP in 24 seconds.
 *
 * The signature is TCP being attempted steadily while almost nothing arrives:
 * an app streaming happily over QUIC downloads far more than [maxBytesIn] in the
 * window even while squeezed, and an idle app does not keep knocking on TCP.
 *
 * Pure, so the thresholds are testable against the measured patterns.
 */
class TcpStarvationDetector(
    private val windowMillis: Long = 6_000L,
    private val minTcpDrops: Long = 12L,
    private val maxBytesIn: Long = 96L * 1024L,
) {
    private data class Sample(val atMillis: Long, val tcpDropped: Long, val bytesIn: Long)

    private val samples = ArrayDeque<Sample>()

    /**
     * Records the relay's running totals at [nowMillis] and reports whether the
     * last window looks like an app trying TCP and getting nothing.
     */
    fun starving(nowMillis: Long, tcpDropped: Long, bytesIn: Long): Boolean {
        samples.addLast(Sample(nowMillis, tcpDropped, bytesIn))
        while (samples.size > 1 && nowMillis - samples.first().atMillis > windowMillis) {
            samples.removeFirst()
        }

        // Judge only a (nearly) full window: the first seconds of every tunnel
        // are handshakes and retries, and look like starvation for a moment.
        val oldest = samples.first()
        if (nowMillis - oldest.atMillis < windowMillis * 4 / 5) return false

        val drops = tcpDropped - oldest.tcpDropped
        val bytes = bytesIn - oldest.bytesIn
        return drops >= minTcpDrops && bytes <= maxBytesIn
    }

    fun reset() = samples.clear()
}

/**
 * Apps ORBIS has stood down for, and until when.
 *
 * Once an app is on TCP the relay can only starve it, so ORBIS leaves it at full
 * speed instead - a missed throttle is a much smaller failure than a broken app.
 * The stand-down doubles each time it trips for the same app, because an app
 * that has decided UDP is broken keeps deciding it for a while: YouTube's network
 * stack backs off from QUIC the same way.
 *
 * A process-wide object for the same reason as [com.orbis.app.earn.ClearTimeHolder]:
 * the VPN service decides, and the accessibility gate - which cannot be bound to -
 * has to respect it.
 */
object TcpFallback {

    const val FIRST_STAND_DOWN_MILLIS = 10 * 60_000L
    const val MAX_STAND_DOWN_MILLIS = 60 * 60_000L

    private val strikes = HashMap<String, Int>()

    private val _standingDown = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Package to wall-clock millis it is left alone until. */
    val standingDown: StateFlow<Map<String, Long>> = _standingDown.asStateFlow()

    /** Stands [packageName] down and returns for how long, in millis. */
    @Synchronized
    fun standDown(packageName: String, nowMillis: Long): Long {
        val strike = (strikes[packageName] ?: 0) + 1
        strikes[packageName] = strike
        val duration = (FIRST_STAND_DOWN_MILLIS shl (strike - 1).coerceAtMost(10))
            .coerceAtMost(MAX_STAND_DOWN_MILLIS)
        _standingDown.value = active(nowMillis) + (packageName to nowMillis + duration)
        return duration
    }

    fun isStandingDown(packageName: String, nowMillis: Long): Boolean =
        (_standingDown.value[packageName] ?: 0L) > nowMillis

    /** Forgets expired entries; strikes are kept so a repeat still doubles. */
    @Synchronized
    fun active(nowMillis: Long): Map<String, Long> {
        val live = _standingDown.value.filterValues { it > nowMillis }
        if (live.size != _standingDown.value.size) _standingDown.value = live
        return live
    }

    /** For tests. */
    @Synchronized
    fun clear() {
        strikes.clear()
        _standingDown.value = emptyMap()
    }
}
