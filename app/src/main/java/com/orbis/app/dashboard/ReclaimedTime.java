package com.orbis.app.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns daily short-form usage into "time reclaimed".
 *
 * <p>There is no external yardstick for what a person <i>should</i> use, so the
 * baseline is the user's own recent average. Reclaimed time is therefore "less
 * than you have been averaging", which is honest and self-referential.
 *
 * <p>Two rules keep it from flattering the user:
 *
 * <ul>
 *   <li><b>Days with no record are ignored</b>, never counted as zero. A day
 *       before ORBIS was installed, or one where it was switched off, is missing
 *       data - treating it as "no usage" would manufacture reclaimed time out of
 *       nothing.
 *   <li><b>Below the minimum history there is no baseline at all.</b> On day one
 *       the honest answer is "still learning", not a number.
 * </ul>
 *
 * <p>Never returns a negative: a heavier-than-usual day reads as zero reclaimed,
 * not as a deficit. That is the positive-framing invariant, not arithmetic
 * sloppiness.
 */
public final class ReclaimedTime {

    /** Fewer recorded days than this and no baseline is offered. */
    public static final int MIN_BASELINE_DAYS = 3;

    /** How far back the baseline looks, excluding today. */
    public static final int BASELINE_WINDOW_DAYS = 14;

    public static final int WEEK_DAYS = 7;

    private ReclaimedTime() {
        // No instances: one pure summary function.
    }

    public static ReclaimedSummary summarize(Map<LocalDate, Long> history, LocalDate today) {
        Long todayRecorded = history.get(today);
        long todayMillis = todayRecorded == null ? 0L : todayRecorded;

        List<Long> baselineDays = new ArrayList<>();
        for (int back = 1; back <= BASELINE_WINDOW_DAYS; back++) {
            Long millis = history.get(today.minusDays(back));
            if (millis != null) {
                baselineDays.add(millis);
            }
        }

        Long baseline = null;
        if (baselineDays.size() >= MIN_BASELINE_DAYS) {
            long sum = 0L;
            for (Long millis : baselineDays) {
                sum += millis;
            }
            baseline = sum / baselineDays.size();
        }

        List<DayTotal> week = new ArrayList<>();
        for (int back = WEEK_DAYS - 1; back >= 0; back--) {
            LocalDate date = today.minusDays(back);
            Long millis = history.get(date);
            if (millis != null) {
                week.add(new DayTotal(date, millis));
            }
        }

        long reclaimedToday = baseline == null ? 0L : reclaimed(baseline, todayMillis);

        long reclaimedWeek = 0L;
        if (baseline != null) {
            for (DayTotal day : week) {
                reclaimedWeek += reclaimed(baseline, day.getMillis());
            }
        }

        return new ReclaimedSummary(
                todayMillis, baseline, reclaimedToday, reclaimedWeek, week);
    }

    private static long reclaimed(long baselineMillis, long actualMillis) {
        return Math.max(baselineMillis - actualMillis, 0L);
    }
}
