package com.orbis.app.dashboard;

import java.time.LocalDate;
import java.util.Objects;

/** One recorded day's short-form total. */
public final class DayTotal {

    private final LocalDate date;
    private final long millis;

    public DayTotal(LocalDate date, long millis) {
        this.date = date;
        this.millis = millis;
    }

    public LocalDate getDate() {
        return date;
    }

    public long getMillis() {
        return millis;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DayTotal)) {
            return false;
        }
        DayTotal that = (DayTotal) other;
        return millis == that.millis && Objects.equals(date, that.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, millis);
    }

    @Override
    public String toString() {
        return "DayTotal(" + date + ", " + millis + "ms)";
    }
}
