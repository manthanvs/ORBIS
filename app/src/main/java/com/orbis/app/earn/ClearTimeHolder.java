package com.orbis.app.earn;

import com.orbis.app.util.StateHolder;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide clear-time balance, and the meter that spends it.
 *
 * <p>Static state for the same reason the surface monitor is: the accessibility
 * service is owned by the system and cannot be bound to, but it is the only
 * thing that knows a short-form feed is on screen - so it has to be able to read
 * the balance and charge against it without a repository.
 *
 * <p>Spending is metered in memory and written back in batches. The gate
 * re-evaluates several times a second while a feed is on screen, and a database
 * row per tick would be hundreds of writes a minute for a number only ORBIS
 * reads.
 */
public final class ClearTimeHolder {

    /**
     * A tick longer than this is treated as a gap rather than watching - the user
     * put the phone down, took a call, or the service was not scheduled. Charging
     * the real elapsed time would bill them for time they were not scrolling.
     */
    private static final long MAX_TICK_MILLIS = 2_000L;

    /** What is left to spend today, already net of anything not yet written back. */
    private static final StateHolder<Long> REMAINING_MILLIS = new StateHolder<>(0L);

    private static final AtomicLong PENDING_SPEND = new AtomicLong(0L);

    private static volatile long lastChargeMillis = 0L;

    private ClearTimeHolder() {
        // No instances: one process-wide balance.
    }

    public static StateHolder<Long> remainingMillis() {
        return REMAINING_MILLIS;
    }

    /** Seeded from the ledger by the earn repository whenever it changes. */
    public static void publish(long remaining) {
        REMAINING_MILLIS.set(Math.max(remaining, 0L));
    }

    /**
     * Bills for another tick of watching a throttled surface.
     *
     * @return true while credit is covering the feed, meaning ORBIS should stay
     *     out of the way entirely. False the moment it runs out, from which point
     *     the caller throttles as normal.
     */
    public static synchronized boolean charge(long nowMillis) {
        if (REMAINING_MILLIS.get() <= 0L) {
            // Nothing to spend. Reset the clock so the next credit is not
            // immediately billed for however long the user scrolled without it.
            lastChargeMillis = 0L;
            return false;
        }

        long previous = lastChargeMillis;
        lastChargeMillis = nowMillis;

        // The first tick of a session has nothing to measure from, and a tick
        // after a gap must not bill the gap.
        long elapsed = previous == 0L ? 0L : nowMillis - previous;
        long billable = Math.min(Math.max(elapsed, 0L), MAX_TICK_MILLIS);
        if (billable == 0L) {
            return true;
        }

        PENDING_SPEND.addAndGet(billable);
        REMAINING_MILLIS.set(Math.max(REMAINING_MILLIS.get() - billable, 0L));
        return REMAINING_MILLIS.get() > 0L;
    }

    /** Takes the spend accumulated since the last call, for writing to the ledger. */
    public static long drainPendingSpend() {
        return PENDING_SPEND.getAndSet(0L);
    }

    /** Called when the day rolls over or the service stops, so nothing lingers. */
    public static void reset() {
        lastChargeMillis = 0L;
        PENDING_SPEND.set(0L);
        REMAINING_MILLIS.set(0L);
    }
}
