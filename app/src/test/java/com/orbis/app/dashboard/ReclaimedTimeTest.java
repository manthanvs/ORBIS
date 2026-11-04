package com.orbis.app.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReclaimedTimeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);

    private static long minutes(long value) {
        return value * 60_000L;
    }

    /** Pairs of "days ago" and minutes, in order. */
    private static Map<LocalDate, Long> history(long... daysAgoThenMinutes) {
        Map<LocalDate, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < daysAgoThenMinutes.length; i += 2) {
            map.put(
                    TODAY.minusDays(daysAgoThenMinutes[i]),
                    minutes(daysAgoThenMinutes[i + 1]));
        }
        return map;
    }

    @Test
    public void noBaselineUntilThereIsEnoughHistory() {
        // Two recorded days is not enough to claim an average.
        ReclaimedSummary summary = ReclaimedTime.summarize(history(1, 60, 2, 60), TODAY);

        assertNull(summary.getBaselineMillis());
        assertEquals(0L, summary.getReclaimedTodayMillis());
    }

    @Test
    public void baselineAppearsAtTheMinimumDayCount() {
        ReclaimedSummary summary = ReclaimedTime.summarize(history(1, 60, 2, 60, 3, 60), TODAY);

        assertEquals(Long.valueOf(minutes(60)), summary.getBaselineMillis());
    }

    @Test
    public void reclaimsTheDifferenceFromTheBaseline() {
        ReclaimedSummary summary =
                ReclaimedTime.summarize(history(0, 20, 1, 60, 2, 60, 3, 60), TODAY);

        assertEquals(Long.valueOf(minutes(60)), summary.getBaselineMillis());
        assertEquals(minutes(20), summary.getTodayMillis());
        assertEquals(minutes(40), summary.getReclaimedTodayMillis());
    }

    @Test
    public void aHeavierDayReadsAsZeroReclaimedNeverNegative() {
        // Positive framing: a bad day is not shown as a deficit.
        ReclaimedSummary summary =
                ReclaimedTime.summarize(history(0, 200, 1, 60, 2, 60, 3, 60), TODAY);

        assertEquals(0L, summary.getReclaimedTodayMillis());
        assertTrue(summary.getReclaimedWeekMillis() >= 0L);
    }

    @Test
    public void todayIsExcludedFromItsOwnBaseline() {
        // A huge today must not drag the baseline up and mask itself.
        ReclaimedSummary summary =
                ReclaimedTime.summarize(history(0, 600, 1, 30, 2, 30, 3, 30), TODAY);

        assertEquals(Long.valueOf(minutes(30)), summary.getBaselineMillis());
    }

    @Test
    public void missingDaysAreIgnoredRatherThanCountedAsZero() {
        // Days before ORBIS existed are absent, not idle. Counting them as zero
        // would drag the baseline down and manufacture reclaimed time.
        ReclaimedSummary withGaps = ReclaimedTime.summarize(history(1, 90, 5, 90, 9, 90), TODAY);

        assertEquals(Long.valueOf(minutes(90)), withGaps.getBaselineMillis());
    }

    @Test
    public void weekSeriesContainsOnlyRecordedDaysOldestFirst() {
        ReclaimedSummary summary = ReclaimedTime.summarize(history(0, 10, 2, 20, 6, 30), TODAY);

        assertEquals(3, summary.getWeek().size());
        assertEquals(TODAY.minusDays(6), summary.getWeek().get(0).getDate());
        assertEquals(TODAY, summary.getWeek().get(summary.getWeek().size() - 1).getDate());
    }

    @Test
    public void weekSeriesNeverReachesBackBeyondSevenDays() {
        ReclaimedSummary summary = ReclaimedTime.summarize(history(0, 10, 7, 99, 8, 99), TODAY);

        assertEquals(1, summary.getWeek().size());
        assertEquals(TODAY, summary.getWeek().get(0).getDate());
    }

    @Test
    public void baselineIgnoresDaysOutsideItsWindow() {
        ReclaimedSummary summary =
                ReclaimedTime.summarize(history(1, 10, 2, 10, 3, 10, 20, 600), TODAY);

        assertEquals(Long.valueOf(minutes(10)), summary.getBaselineMillis());
    }

    @Test
    public void emptyHistoryIsHandled() {
        ReclaimedSummary summary = ReclaimedTime.summarize(Collections.emptyMap(), TODAY);

        assertEquals(0L, summary.getTodayMillis());
        assertNull(summary.getBaselineMillis());
        assertEquals(0L, summary.getReclaimedTodayMillis());
        assertEquals(0L, summary.getReclaimedWeekMillis());
        assertTrue(summary.getWeek().isEmpty());
    }

    @Test
    public void weeklyReclaimedSumsOnlyRecordedDays() {
        // baseline 60; today 20 (+40), yesterday 50 (+10), 2 days ago 90 (0)
        ReclaimedSummary summary =
                ReclaimedTime.summarize(
                        history(0, 20, 1, 50, 2, 90, 3, 60, 4, 60, 5, 60), TODAY);

        // (50+90+60+60+60)/5
        assertEquals(Long.valueOf(minutes(64)), summary.getBaselineMillis());
        assertTrue(summary.getReclaimedWeekMillis() > 0L);
    }
}
