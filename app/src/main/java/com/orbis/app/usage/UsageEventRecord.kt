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
)

enum class UsageEventType {
    /** The app became visible to the user (ACTIVITY_RESUMED). */
    FOREGROUND,

    /** The app stopped being visible (ACTIVITY_PAUSED). */
    BACKGROUND,
}
