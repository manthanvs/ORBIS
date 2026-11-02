package com.orbis.app.throttle;

/**
 * How hard ORBIS is pushing, as a ladder the user can see themselves climbing.
 *
 * <p>The old ramp was continuous and topped out at 45 minutes a day - which a
 * heavy user passes before lunch, so every hour after that felt identical, and
 * the whole thing read as "barely there". Levels fix both: the steps are
 * visible, and the top of the ladder is far heavier than the old maximum.
 *
 * <p>{@link #IMMUNE} is the end state, and it is deliberately close to
 * unwatchable: 2.8 seconds of every 3 held to a trickle, and the 200ms gap
 * between them capped below any video bitrate. Someone living at that level is not being
 * slowed down any more - short-form has simply stopped being worth opening,
 * which is the point. It is still never a block: the trickle keeps the app alive
 * and the user can always buy the level off with clear time.
 */
public enum ThrottleLevel {

    NUDGE(1, "Nudge", 0L, 900L, 600_000L, 120L),
    DRAG(2, "Drag", 20L, 1_200L, 400_000L, 200L),
    STUTTER(3, "Stutter", 45L, 1_500L, 250_000L, 280L),
    GRIND(4, "Grind", 90L, 1_800L, 150_000L, 340L),
    IMMUNE(5, "Immune", 150L, 2_800L, 24_000L, 400L);

    /**
     * One squeeze-then-gap cycle.
     *
     * <p>Three seconds rather than five: at five the player had a long enough
     * gap to refill its buffer and ride through the next squeeze, so the stall
     * arrived late and only once. A shorter cycle interrupts more often for the
     * same average throughput, and the interruption is what the user notices.
     */
    public static final long PULSE_PERIOD_MILLIS = 3_000L;

    /**
     * The download trickle during a squeeze, ~64 kbit/s, at every level.
     * Above zero on purpose: the app's connections survive and recover the
     * moment the squeeze lifts. A cut-off reads as broken; this reads as slow.
     */
    public static final long SQUEEZE_BYTES_PER_SECOND = 8_000L;

    private final int number;
    private final String label;
    private final long fromMinutes;
    private final long squeezeMillis;
    private final long openBytesPerSecond;
    private final long delayMillis;

    ThrottleLevel(
            int number,
            String label,
            long fromMinutes,
            long squeezeMillis,
            long openBytesPerSecond,
            long delayMillis) {
        this.number = number;
        this.label = label;
        this.fromMinutes = fromMinutes;
        this.squeezeMillis = squeezeMillis;
        this.openBytesPerSecond = openBytesPerSecond;
        this.delayMillis = delayMillis;
    }

    public int getNumber() {
        return number;
    }

    public String getLabel() {
        return label;
    }

    /** Daily short-form minutes at which this level starts. */
    public long getFromMinutes() {
        return fromMinutes;
    }

    public Friction getFriction() {
        return new Friction(
                delayMillis,
                PULSE_PERIOD_MILLIS,
                squeezeMillis,
                SQUEEZE_BYTES_PER_SECOND,
                openBytesPerSecond);
    }

    /** The highest level whose threshold {@code minutes} has reached. */
    public static ThrottleLevel forMinutes(long minutes) {
        ThrottleLevel[] levels = values();
        ThrottleLevel match = levels[0];
        for (ThrottleLevel level : levels) {
            if (minutes >= level.fromMinutes) {
                match = level;
            }
        }
        return match;
    }
}
