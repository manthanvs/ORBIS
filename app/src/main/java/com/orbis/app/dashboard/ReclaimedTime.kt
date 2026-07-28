package com.orbis.app.dashboard

import java.time.LocalDate

data class DayTotal(
    val date: LocalDate,
    val millis: Long,
)

/**
 * @property baselineMillis null until there is enough history to be honest about.
 *   The UI must say "still learning" rather than invent a number.
 */
data class ReclaimedSummary(
    val todayMillis: Long,
    val baselineMillis: Long?,
    val reclaimedTodayMillis: Long,
    val reclaimedWeekMillis: Long,
    /** Oldest first, today last. Only days ORBIS actually recorded. */
    val week: List<DayTotal>,
)

/**
 * Turns daily short-form usage into "time reclaimed".
 *
 * There is no external yardstick for what a person *should* use, so the baseline is
 * the user's own recent average. Reclaimed time is therefore "less than you have
 * been averaging", which is honest and self-referential.
 *
 * Two rules keep it from flattering the user:
 *
 *  - **Days with no record are ignored**, never counted as zero. A day before ORBIS
 *    was installed, or one where it was switched off, is missing data - treating it
 *    as "no usage" would manufacture reclaimed time out of nothing.
 *  - **Below [MIN_BASELINE_DAYS] of history there is no baseline at all.** On day
 *    one the honest answer is "still learning", not a number.
 *
 * Never returns a negative: a heavier-than-usual day reads as zero reclaimed, not as
 * a deficit. That is the positive-framing invariant, not arithmetic sloppiness.
 */
object ReclaimedTime {

    /** Fewer recorded days than this and no baseline is offered. */
    const val MIN_BASELINE_DAYS = 3

    /** How far back the baseline looks, excluding today. */
    const val BASELINE_WINDOW_DAYS = 14

    const val WEEK_DAYS = 7

    fun summarize(history: Map<LocalDate, Long>, today: LocalDate): ReclaimedSummary {
        val todayMillis = history[today] ?: 0L

        val baselineDays = (1..BASELINE_WINDOW_DAYS)
            .mapNotNull { back -> history[today.minusDays(back.toLong())] }

        val baseline = if (baselineDays.size >= MIN_BASELINE_DAYS) {
            baselineDays.sum() / baselineDays.size
        } else {
            null
        }

        val week = (WEEK_DAYS - 1 downTo 0)
            .map { back -> today.minusDays(back.toLong()) }
            .mapNotNull { date -> history[date]?.let { DayTotal(date, it) } }

        val reclaimedToday = baseline?.let { reclaimed(it, todayMillis) } ?: 0L
        val reclaimedWeek = baseline
            ?.let { base -> week.sumOf { reclaimed(base, it.millis) } }
            ?: 0L

        return ReclaimedSummary(
            todayMillis = todayMillis,
            baselineMillis = baseline,
            reclaimedTodayMillis = reclaimedToday,
            reclaimedWeekMillis = reclaimedWeek,
            week = week,
        )
    }

    private fun reclaimed(baselineMillis: Long, actualMillis: Long): Long =
        (baselineMillis - actualMillis).coerceAtLeast(0L)
}
