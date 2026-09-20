package com.orbis.app.earn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 7, 29)

class EarnStreakTest {

    private fun daysAgo(vararg backs: Int) = backs.map { TODAY.minusDays(it.toLong()) }

    @Test
    fun `no deeds is no streak`() {
        assertEquals(0, EarnStreak.current(emptyList(), TODAY))
    }

    @Test
    fun `today alone is a streak of one`() {
        assertEquals(1, EarnStreak.current(daysAgo(0), TODAY))
    }

    @Test
    fun `counts consecutive days ending today`() {
        assertEquals(4, EarnStreak.current(daysAgo(0, 1, 2, 3), TODAY))
    }

    @Test
    fun `yesterday still counts so the streak survives until a full day is missed`() {
        // Otherwise a streak would appear broken at 00:01 before the user has had
        // any chance to act.
        assertEquals(3, EarnStreak.current(daysAgo(1, 2, 3), TODAY))
    }

    @Test
    fun `a missed day breaks the streak`() {
        // Two days ago and older, with nothing since.
        assertEquals(0, EarnStreak.current(daysAgo(2, 3, 4), TODAY))
    }

    @Test
    fun `only the current run counts not the longest one`() {
        // A 5-day run last week does not extend today's 2-day run.
        assertEquals(2, EarnStreak.current(daysAgo(0, 1, 5, 6, 7, 8, 9), TODAY))
    }

    @Test
    fun `several deeds on one day count once`() {
        val sameDayTwice = listOf(TODAY, TODAY, TODAY.minusDays(1))

        assertEquals(2, EarnStreak.current(sameDayTwice, TODAY))
    }

    @Test
    fun `unordered input is handled`() {
        assertEquals(3, EarnStreak.current(daysAgo(2, 0, 1), TODAY))
    }

    @Test
    fun `doneToday reflects only today`() {
        assertTrue(EarnStreak.doneToday(daysAgo(0, 1), TODAY))
        assertFalse(EarnStreak.doneToday(daysAgo(1, 2), TODAY))
        assertFalse(EarnStreak.doneToday(emptyList(), TODAY))
    }
}
