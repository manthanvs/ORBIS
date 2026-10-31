package com.orbis.app.earn;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Counts consecutive days containing at least one completed earned day.
 *
 * <p>Pure, so the awkward parts - today not yet done, gaps, several earns in one
 * day - are testable without a device or a clock.
 */
public final class EarnStreak {

    private EarnStreak() {
        // No instances: a holder for two pure functions.
    }

    /**
     * Length of the run of earned days ending today or yesterday, else 0.
     *
     * <p>Yesterday still counts: a streak should not be declared broken at 00:01
     * just because the day rolled over and the user has not acted yet. It breaks
     * only once a full day has been missed.
     *
     * @param completedDates dates of completed earns, any order, duplicates allowed
     * @param today the date to count back from
     */
    public static int current(Collection<LocalDate> completedDates, LocalDate today) {
        if (completedDates.isEmpty()) {
            return 0;
        }

        Set<LocalDate> days = new HashSet<>(completedDates);

        // Anchor on today if done, otherwise yesterday; anything older is a break.
        LocalDate anchor;
        if (days.contains(today)) {
            anchor = today;
        } else if (days.contains(today.minusDays(1))) {
            anchor = today.minusDays(1);
        } else {
            return 0;
        }

        int streak = 0;
        LocalDate cursor = anchor;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** True when clear time has already been earned today. */
    public static boolean doneToday(Collection<LocalDate> completedDates, LocalDate today) {
        return completedDates.contains(today);
    }
}
