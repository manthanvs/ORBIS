package com.orbis.app.deed

import java.time.LocalDate

/**
 * Counts consecutive days containing at least one completed good deed.
 *
 * Pure, so the awkward parts — today not yet done, gaps, several deeds in one day —
 * are testable without a device or a clock.
 */
object GoodDeedStreak {

    /**
     * @param completedDates the dates of completed deeds, in any order, duplicates allowed.
     * @return length of the run ending today or yesterday, else 0.
     *
     * Yesterday still counts: a streak should not be declared broken at 00:01 just
     * because the day rolled over and the user has not acted yet. It breaks only
     * once a full day has been missed.
     */
    fun current(completedDates: Collection<LocalDate>, today: LocalDate): Int {
        if (completedDates.isEmpty()) return 0

        val days = completedDates.toSet()

        // Anchor on today if done, otherwise yesterday; anything older is a break.
        val anchor = when {
            days.contains(today) -> today
            days.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }

        var streak = 0
        var cursor = anchor
        while (days.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** True when a deed has already been completed today, so no need to prompt again. */
    fun doneToday(completedDates: Collection<LocalDate>, today: LocalDate): Boolean =
        completedDates.contains(today)
}
