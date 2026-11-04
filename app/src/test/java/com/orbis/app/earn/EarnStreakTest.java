package com.orbis.app.earn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EarnStreakTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);

    private static List<LocalDate> daysAgo(int... backs) {
        List<LocalDate> dates = new ArrayList<>();
        for (int back : backs) {
            dates.add(TODAY.minusDays(back));
        }
        return dates;
    }

    @Test
    public void noDeedsIsNoStreak() {
        assertEquals(0, EarnStreak.current(Collections.emptyList(), TODAY));
    }

    @Test
    public void todayAloneIsAStreakOfOne() {
        assertEquals(1, EarnStreak.current(daysAgo(0), TODAY));
    }

    @Test
    public void countsConsecutiveDaysEndingToday() {
        assertEquals(4, EarnStreak.current(daysAgo(0, 1, 2, 3), TODAY));
    }

    @Test
    public void yesterdayStillCountsSoTheStreakSurvivesUntilAFullDayIsMissed() {
        // Otherwise a streak would appear broken at 00:01 before the user has had
        // any chance to act.
        assertEquals(3, EarnStreak.current(daysAgo(1, 2, 3), TODAY));
    }

    @Test
    public void aMissedDayBreaksTheStreak() {
        // Two days ago and older, with nothing since.
        assertEquals(0, EarnStreak.current(daysAgo(2, 3, 4), TODAY));
    }

    @Test
    public void onlyTheCurrentRunCountsNotTheLongestOne() {
        // A 5-day run last week does not extend today's 2-day run.
        assertEquals(2, EarnStreak.current(daysAgo(0, 1, 5, 6, 7, 8, 9), TODAY));
    }

    @Test
    public void severalDeedsOnOneDayCountOnce() {
        List<LocalDate> sameDayTwice = Arrays.asList(TODAY, TODAY, TODAY.minusDays(1));

        assertEquals(2, EarnStreak.current(sameDayTwice, TODAY));
    }

    @Test
    public void unorderedInputIsHandled() {
        assertEquals(3, EarnStreak.current(daysAgo(2, 0, 1), TODAY));
    }

    @Test
    public void doneTodayReflectsOnlyToday() {
        assertTrue(EarnStreak.doneToday(daysAgo(0, 1), TODAY));
        assertFalse(EarnStreak.doneToday(daysAgo(1, 2), TODAY));
        assertFalse(EarnStreak.doneToday(Collections.emptyList(), TODAY));
    }
}
