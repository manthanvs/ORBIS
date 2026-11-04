package com.orbis.app.dashboard;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** What the dashboard shows: today, the user's own baseline, and the week. */
public final class ReclaimedSummary {

    private final long todayMillis;
    private final Long baselineMillis;
    private final long reclaimedTodayMillis;
    private final long reclaimedWeekMillis;
    private final List<DayTotal> week;

    /**
     * @param baselineMillis null until there is enough history to be honest
     *     about. The UI must say "still learning" rather than invent a number.
     * @param week oldest first, today last. Only days ORBIS actually recorded.
     */
    public ReclaimedSummary(
            long todayMillis,
            Long baselineMillis,
            long reclaimedTodayMillis,
            long reclaimedWeekMillis,
            List<DayTotal> week) {
        this.todayMillis = todayMillis;
        this.baselineMillis = baselineMillis;
        this.reclaimedTodayMillis = reclaimedTodayMillis;
        this.reclaimedWeekMillis = reclaimedWeekMillis;
        this.week = Collections.unmodifiableList(week);
    }

    public long getTodayMillis() {
        return todayMillis;
    }

    public Long getBaselineMillis() {
        return baselineMillis;
    }

    public long getReclaimedTodayMillis() {
        return reclaimedTodayMillis;
    }

    public long getReclaimedWeekMillis() {
        return reclaimedWeekMillis;
    }

    public List<DayTotal> getWeek() {
        return week;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReclaimedSummary)) {
            return false;
        }
        ReclaimedSummary that = (ReclaimedSummary) other;
        return todayMillis == that.todayMillis
                && reclaimedTodayMillis == that.reclaimedTodayMillis
                && reclaimedWeekMillis == that.reclaimedWeekMillis
                && Objects.equals(baselineMillis, that.baselineMillis)
                && Objects.equals(week, that.week);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                todayMillis, baselineMillis, reclaimedTodayMillis, reclaimedWeekMillis, week);
    }

    @Override
    public String toString() {
        return "ReclaimedSummary(today=" + todayMillis
                + ", baseline=" + baselineMillis
                + ", reclaimedToday=" + reclaimedTodayMillis
                + ", reclaimedWeek=" + reclaimedWeekMillis
                + ")";
    }
}
