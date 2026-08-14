package com.orbis.app.usage

/**
 * Turns a raw event stream into per-package foreground time.
 *
 * The framework reports transitions, not durations, so sessions have to be paired
 * up by hand. Three cases are easy to get wrong and are covered by tests:
 *
 *  - a session still open when the window closes (the app is in use *right now*)
 *  - a BACKGROUND with no matching FOREGROUND (the app was already running when
 *    the window opened)
 *  - a repeated FOREGROUND with no BACKGROUND in between (the app never left)
 *
 * Deliberately free of Android types so it runs in `src/test` on the JVM.
 */
object ForegroundTimeCalculator {

    /**
     * @return package name to total foreground milliseconds within the window.
     *         Packages with no measurable time are omitted.
     */
    fun totalsByPackage(
        events: List<UsageEventRecord>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): Map<String, Long> {
        if (windowEndMillis <= windowStartMillis) return emptyMap()

        val totals = mutableMapOf<String, Long>()
        val openedAt = mutableMapOf<String, Long>()

        // `sortedBy` would box a Long per element, and `filter` would copy the
        // list a second time. The framework already returns events in order, so
        // in production this sort finds nothing to do - it is here because the
        // tests feed in deliberately shuffled streams.
        events
            .filterTo(ArrayList(events.size)) {
                it.timestampMillis in windowStartMillis..windowEndMillis
            }
            .apply { sortWith { a, b -> a.timestampMillis.compareTo(b.timestampMillis) } }
            .forEach { event ->
                when (event.type) {
                    UsageEventType.FOREGROUND ->
                        // Keep the earliest of a repeated FOREGROUND: without an
                        // intervening BACKGROUND the app never actually left.
                        if (!openedAt.containsKey(event.packageName)) {
                            openedAt[event.packageName] = event.timestampMillis
                        }

                    UsageEventType.BACKGROUND -> {
                        // No open session means it was already foreground when the
                        // window began, so credit it from the window start.
                        val start = openedAt.remove(event.packageName) ?: windowStartMillis
                        totals.addDuration(event.packageName, event.timestampMillis - start)
                    }
                }
            }

        // Whatever is still open ran until the window closed.
        openedAt.forEach { (packageName, start) ->
            totals.addDuration(packageName, windowEndMillis - start)
        }

        return totals
    }

    private fun MutableMap<String, Long>.addDuration(packageName: String, delta: Long) {
        if (delta <= 0L) return
        this[packageName] = (this[packageName] ?: 0L) + delta
    }
}
