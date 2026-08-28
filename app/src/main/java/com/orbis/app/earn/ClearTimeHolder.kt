package com.orbis.app.earn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide clear-time balance, and the meter that spends it.
 *
 * A plain object for the same reason [com.orbis.app.surface.SurfaceMonitor] is:
 * the accessibility service is owned by the system and cannot be bound to, but it
 * is the only thing that knows a short-form feed is on screen - so it has to be
 * able to read the balance and charge against it without a repository.
 *
 * Spending is metered in memory and written back in batches. The gate re-evaluates
 * several times a second while a feed is on screen, and a database row per tick
 * would be hundreds of writes a minute for a number only ORBIS reads.
 */
object ClearTimeHolder {

    /**
     * A tick longer than this is treated as a gap rather than watching - the user
     * put the phone down, took a call, or the service was not scheduled. Charging
     * the real elapsed time would bill them for time they were not scrolling.
     */
    private const val MAX_TICK_MILLIS = 2_000L

    private val _remainingMillis = MutableStateFlow(0L)

    /** What is left to spend today, already net of anything not yet written back. */
    val remainingMillis: StateFlow<Long> = _remainingMillis.asStateFlow()

    private val pendingSpend = AtomicLong(0L)

    @Volatile
    private var lastChargeMillis = 0L

    /** Seeded from the ledger by [EarnRepository] whenever it changes. */
    fun publish(remaining: Long) {
        _remainingMillis.value = remaining.coerceAtLeast(0L)
    }

    /**
     * Bills for another tick of watching a throttled surface.
     *
     * @return true while credit is covering the feed, meaning ORBIS should stay
     *   out of the way entirely. False the moment it runs out, from which point
     *   the caller throttles as normal.
     */
    @Synchronized
    fun charge(nowMillis: Long): Boolean {
        if (_remainingMillis.value <= 0L) {
            // Nothing to spend. Reset the clock so the next credit is not
            // immediately billed for however long the user scrolled without it.
            lastChargeMillis = 0L
            return false
        }

        val previous = lastChargeMillis
        lastChargeMillis = nowMillis

        // The first tick of a session has nothing to measure from, and a tick
        // after a gap must not bill the gap.
        val elapsed = if (previous == 0L) 0L else (nowMillis - previous)
        val billable = elapsed.coerceIn(0L, MAX_TICK_MILLIS)
        if (billable == 0L) return true

        pendingSpend.addAndGet(billable)
        _remainingMillis.value = (_remainingMillis.value - billable).coerceAtLeast(0L)
        return _remainingMillis.value > 0L
    }

    /** Takes the spend accumulated since the last call, for writing to the ledger. */
    fun drainPendingSpend(): Long = pendingSpend.getAndSet(0L)

    /** Called when the day rolls over or the service stops, so nothing lingers. */
    fun reset() {
        lastChargeMillis = 0L
        pendingSpend.set(0L)
        _remainingMillis.value = 0L
    }
}
