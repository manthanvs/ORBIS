package com.orbis.app.usage

/** Formats a duration for display. Pure, so it is unit-testable. */
object DurationFormatter {

    fun format(millis: Long): String {
        if (millis <= 0L) return "0m"

        val totalSeconds = millis / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
