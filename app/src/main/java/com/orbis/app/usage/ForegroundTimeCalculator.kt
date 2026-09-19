package com.orbis.app.usage

/**
 * Turns a raw event stream into per-package foreground time.
 *
 * The framework reports transitions, not durations, so sessions have to be paired
 * up by hand. The cases that are easy to get wrong are covered by tests:
 *
 *  - a session still open when the window closes (the app is in use *right now*)
 *  - a BACKGROUND with no matching FOREGROUND as the app's *first* event (it was
 *    already running when the window opened)
 *  - a repeated FOREGROUND with no BACKGROUND in between (the app never left)
 *  - several activities of one app resumed at once, paused in any order
 *
 * The last one is why sessions are paired **per activity**, not per package.
 * Measured on CPH2585: InstaPro opens through `LauncherActivity` and
 * `PinLockActivity` together, and Morphe bounces links through a trampoline, so
 * an app routinely has two activities resumed and pauses them out of order. One
 * open session per package closed on the first pause and met the second with no
 * session at all - which was then "credited from the window start", i.e. from
 * midnight. A single Morphe pause at 17:55 added nearly eighteen hours, and the
 * dashboard reported 19 h of YouTube by half past seven in the evening. The
 * throttle scales with these minutes, so it also pinned YouTube at maximum
 * friction all day.
 *
 * An app is foreground while *any* of its activities is resumed. A pause with no
 * matching resume is credited from the window start only when it is the
 * package's first event in the window; anywhere later it is noise and counts for
 * nothing. And no package is ever credited more than the window itself.
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
        // Per package: which of its activities are resumed, and since when the
        // package as a whole has been in front.
        val resumed = mutableMapOf<String, MutableSet<String>>()
        val since = mutableMapOf<String, Long>()
        val seen = HashSet<String>()

        // `sortedBy` would box a Long per element, and `filter` would copy the
        // list a second time. The framework already returns events in order, so
        // in production this sort finds nothing to do - it is here because the
        // tests feed in deliberately shuffled streams. The sort is stable, so
        // events sharing a millisecond keep the framework's order.
        events
            .filterTo(ArrayList(events.size)) {
                it.timestampMillis in windowStartMillis..windowEndMillis
            }
            .apply { sortWith { a, b -> a.timestampMillis.compareTo(b.timestampMillis) } }
            .forEach { event ->
                val packageName = event.packageName
                val firstEvent = seen.add(packageName)
                val live = resumed.getOrPut(packageName) { HashSet(2) }
                val activity = event.activity.orEmpty()

                when (event.type) {
                    UsageEventType.FOREGROUND -> {
                        if (live.isEmpty()) since[packageName] = event.timestampMillis
                        // A repeated resume of the same activity changes nothing:
                        // without an intervening pause the app never left.
                        live.add(activity)
                    }

                    UsageEventType.BACKGROUND -> when {
                        live.remove(activity) -> if (live.isEmpty()) {
                            val start = since.remove(packageName) ?: event.timestampMillis
                            totals.addDuration(packageName, event.timestampMillis - start)
                        }

                        // Already in front when the window opened.
                        firstEvent && live.isEmpty() ->
                            totals.addDuration(
                                packageName,
                                event.timestampMillis - windowStartMillis,
                            )

                        // Unmatched mid-window: a trampoline or an activity whose
                        // resume fell outside the stream. It carries no duration.
                        else -> Unit
                    }
                }
            }

        // Whatever is still open ran until the window closed.
        since.forEach { (packageName, start) ->
            if (resumed[packageName].orEmpty().isNotEmpty()) {
                totals.addDuration(packageName, windowEndMillis - start)
            }
        }

        // Nothing can have been in front for longer than the window lasted.
        val windowLength = windowEndMillis - windowStartMillis
        totals.replaceAll { _, millis -> millis.coerceAtMost(windowLength) }

        return totals
    }

    private fun MutableMap<String, Long>.addDuration(packageName: String, delta: Long) {
        if (delta <= 0L) return
        this[packageName] = (this[packageName] ?: 0L) + delta
    }
}
