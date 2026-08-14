package com.orbis.app.usage

/** One tracked app's measured time for a period. */
data class AppUsage(
    val app: TargetApp,
    val durationMillis: Long,
)

/**
 * Tracked apps ranked by time spent, heaviest first.
 *
 * Drives the dashboard now and, from Phase 3, throttle intensity.
 */
data class UsageProfile(
    val entries: List<AppUsage>,
) {
    // Computed once. Both of these are read from the accessibility service's hot
    // path and from composables, where a getter would recompute per recomposition.
    val totalMillis: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.sumOf { it.durationMillis }
    }

    private val byApp: Map<TargetApp, Long> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.associate { it.app to it.durationMillis }
    }

    fun durationOf(app: TargetApp): Long = byApp[app] ?: 0L

    /** This app's share of today's tracked time, 0f..1f. */
    fun shareOf(app: TargetApp): Float {
        val total = totalMillis
        return if (total <= 0L) 0f else durationOf(app).toFloat() / total
    }

    /**
     * The heaviest app the throttle engine is actually allowed to act on.
     *
     * Skips protected apps, so a user whose top app is WhatsApp gets whatever is
     * beneath it - never WhatsApp itself.
     */
    val heaviestThrottleable: AppUsage?
        get() = entries.firstOrNull { it.app.throttled && it.durationMillis > 0L }

    companion object {
        val EMPTY = UsageProfile(emptyList())

        /** Unknown packages are dropped - ORBIS only reports on apps it targets. */
        fun from(totalsByPackage: Map<String, Long>): UsageProfile {
            val entries = totalsByPackage
                .mapNotNull { (packageName, millis) ->
                    TargetApp.fromPackage(packageName)?.let { AppUsage(it, millis) }
                }
                // Name is the tie-break purely so ordering is deterministic in tests.
                .sortedWith(
                    compareByDescending<AppUsage> { it.durationMillis }
                        .thenBy { it.app.displayName }
                )
            return UsageProfile(entries)
        }
    }
}
