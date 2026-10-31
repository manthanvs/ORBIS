package com.orbis.app.usage;

/** Formats a duration for display. Pure, so it is unit-testable. */
public final class DurationFormatter {

    private DurationFormatter() {
        // No instances: this is a holder for one pure function.
    }

    public static String format(long millis) {
        if (millis <= 0L) {
            return "0m";
        }

        long totalSeconds = millis / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
