package com.orbis.app.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 7, 29)

private fun minutes(value: Long) = value * 60_000L

class ReclaimedTimeTest {

    private fun history(vararg daysAgoToMinutes: Pair<Int, Long>): Map<LocalDate, Long> =
        daysAgoToMinutes.associate { (back, mins) ->
            TODAY.minusDays(back.toLong()) to minutes(mins)
        }

    @Test
    fun `no baseline until there is enough history`() {
        // Two recorded days is not enough to claim an average.
        val summary = ReclaimedTime.summarize(history(1 to 60, 2 to 60), TODAY)

        assertNull(summary.baselineMillis)
        assertEquals(0L, summary.reclaimedTodayMillis)
    }

    @Test
    fun `baseline appears at the minimum day count`() {
        val summary = ReclaimedTime.summarize(history(1 to 60, 2 to 60, 3 to 60), TODAY)

        assertEquals(minutes(60), summary.baselineMillis)
    }

    @Test
    fun `reclaims the difference from the baseline`() {
        val summary = ReclaimedTime.summarize(
            history(0 to 20, 1 to 60, 2 to 60, 3 to 60),
            TODAY,
        )

        assertEquals(minutes(60), summary.baselineMillis)
        assertEquals(minutes(20), summary.todayMillis)
        assertEquals(minutes(40), summary.reclaimedTodayMillis)
    }

    @Test
    fun `a heavier day reads as zero reclaimed never negative`() {
        // Positive framing: a bad day is not shown as a deficit.
        val summary = ReclaimedTime.summarize(
            history(0 to 200, 1 to 60, 2 to 60, 3 to 60),
            TODAY,
        )

        assertEquals(0L, summary.reclaimedTodayMillis)
        assertTrue(summary.reclaimedWeekMillis >= 0L)
    }

    @Test
    fun `today is excluded from its own baseline`() {
        // A huge today must not drag the baseline up and mask itself.
        val summary = ReclaimedTime.summarize(
            history(0 to 600, 1 to 30, 2 to 30, 3 to 30),
            TODAY,
        )

        assertEquals(minutes(30), summary.baselineMillis)
    }

    @Test
    fun `missing days are ignored rather than counted as zero`() {
        // Days before ORBIS existed are absent, not idle. Counting them as zero
        // would drag the baseline down and manufacture reclaimed time.
        val withGaps = ReclaimedTime.summarize(history(1 to 90, 5 to 90, 9 to 90), TODAY)

        assertEquals(minutes(90), withGaps.baselineMillis)
    }

    @Test
    fun `week series contains only recorded days oldest first`() {
        val summary = ReclaimedTime.summarize(
            history(0 to 10, 2 to 20, 6 to 30),
            TODAY,
        )

        assertEquals(3, summary.week.size)
        assertEquals(TODAY.minusDays(6), summary.week.first().date)
        assertEquals(TODAY, summary.week.last().date)
    }

    @Test
    fun `week series never reaches back beyond seven days`() {
        val summary = ReclaimedTime.summarize(history(0 to 10, 7 to 99, 8 to 99), TODAY)

        assertEquals(1, summary.week.size)
        assertEquals(TODAY, summary.week.single().date)
    }

    @Test
    fun `baseline ignores days outside its window`() {
        val summary = ReclaimedTime.summarize(
            history(1 to 10, 2 to 10, 3 to 10, 20 to 600),
            TODAY,
        )

        assertEquals(minutes(10), summary.baselineMillis)
    }

    @Test
    fun `empty history is handled`() {
        val summary = ReclaimedTime.summarize(emptyMap(), TODAY)

        assertEquals(0L, summary.todayMillis)
        assertNull(summary.baselineMillis)
        assertEquals(0L, summary.reclaimedTodayMillis)
        assertEquals(0L, summary.reclaimedWeekMillis)
        assertTrue(summary.week.isEmpty())
    }

    @Test
    fun `weekly reclaimed sums only recorded days`() {
        // baseline 60; today 20 (+40), yesterday 50 (+10), 2 days ago 90 (0)
        val summary = ReclaimedTime.summarize(
            history(0 to 20, 1 to 50, 2 to 90, 3 to 60, 4 to 60, 5 to 60),
            TODAY,
        )

        assertEquals(minutes(64), summary.baselineMillis) // (50+90+60+60+60)/5
        assertTrue(summary.reclaimedWeekMillis > 0L)
    }
}
