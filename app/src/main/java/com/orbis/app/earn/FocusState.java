package com.orbis.app.earn;

/**
 * One focus session, as the user would describe it: when it started, and whether
 * a feed has been opened since.
 *
 * <p>Pure, so the arithmetic - still running, done, broken - is testable without
 * a clock or a device.
 */
public final class FocusState {

    private final long startedAtMillis;
    private final boolean broken;
    private final long durationMillis;

    public FocusState(long startedAtMillis, boolean broken, long durationMillis) {
        this.startedAtMillis = startedAtMillis;
        this.broken = broken;
        this.durationMillis = durationMillis;
    }

    public FocusState(long startedAtMillis, boolean broken) {
        this(startedAtMillis, broken, EarnAction.FOCUS_DURATION_MILLIS);
    }

    /** No session running. */
    public FocusState() {
        this(0L, false, EarnAction.FOCUS_DURATION_MILLIS);
    }

    /** Wall-clock start, or 0 when no session is running. */
    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public boolean getBroken() {
        return broken;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public boolean getActive() {
        return startedAtMillis > 0L;
    }

    public long getEndsAtMillis() {
        return startedAtMillis + durationMillis;
    }

    public long remainingMillis(long nowMillis) {
        if (!getActive()) {
            return 0L;
        }
        long remaining = getEndsAtMillis() - nowMillis;
        return Math.min(Math.max(remaining, 0L), durationMillis);
    }

    /** Seen through: time is up and nothing broke it. */
    public boolean completeAt(long nowMillis) {
        return getActive() && !broken && nowMillis >= getEndsAtMillis();
    }

    /** This session, marked broken. */
    public FocusState asBroken() {
        return new FocusState(startedAtMillis, true, durationMillis);
    }
}
