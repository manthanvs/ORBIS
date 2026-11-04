package com.orbis.app.usage;

/**
 * A framework-free view of a single usage event.
 *
 * <p>{@link UsageStatsSource} maps {@code android.app.usage.UsageEvents.Event}
 * onto this so the time arithmetic in {@link ForegroundTimeCalculator} stays
 * pure and unit-testable.
 */
public final class UsageEventRecord {

    private final String packageName;
    private final UsageEventType type;
    private final long timestampMillis;
    private final String activity;

    public UsageEventRecord(
            String packageName, UsageEventType type, long timestampMillis, String activity) {
        this.packageName = packageName;
        this.type = type;
        this.timestampMillis = timestampMillis;
        this.activity = activity;
    }

    /**
     * Without an activity, every event of the package counts as one activity -
     * fine for a stream with no overlapping activities, wrong for a real app.
     * See {@link ForegroundTimeCalculator}.
     */
    public UsageEventRecord(String packageName, UsageEventType type, long timestampMillis) {
        this(packageName, type, timestampMillis, null);
    }

    public String getPackageName() {
        return packageName;
    }

    public UsageEventType getType() {
        return type;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    /** The activity class, which is what pairs a pause with its resume. */
    public String getActivity() {
        return activity;
    }
}
