package com.orbis.app.usage

/**
 * A framework-free view of a single usage event.
 *
 * [UsageStatsSource] maps `android.app.usage.UsageEvents.Event` onto this so the
 * time arithmetic in [ForegroundTimeCalculator] stays pure and unit-testable.
 */
data class UsageEventRecord(
    val packageName: String,
    val type: UsageEventType,
    val timestampMillis: Long,
    /**
     * The activity class, which is what pairs a pause with its resume. Null
     * treats every event of the package as one activity - fine for a stream
     * with no overlapping activities, wrong for a real app. See
     * [ForegroundTimeCalculator].
     */
    val activity: String? = null,
)

enum class UsageEventType {
    /** The app became visible to the user (ACTIVITY_RESUMED). */
    FOREGROUND,

    /** The app stopped being visible (ACTIVITY_PAUSED). */
    BACKGROUND,
}
