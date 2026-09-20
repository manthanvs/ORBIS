package com.orbis.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WINDOW_START = 1_000L
private const val WINDOW_END = 101_000L

private const val INSTAGRAM = "com.instagram.android"
private const val WHATSAPP = "com.whatsapp"

class ForegroundTimeCalculatorTest {

    private fun foreground(pkg: String, at: Long) =
        UsageEventRecord(pkg, UsageEventType.FOREGROUND, at)

    private fun background(pkg: String, at: Long) =
        UsageEventRecord(pkg, UsageEventType.BACKGROUND, at)

    private fun resumed(pkg: String, activity: String, at: Long) =
        UsageEventRecord(pkg, UsageEventType.FOREGROUND, at, activity)

    private fun paused(pkg: String, activity: String, at: Long) =
        UsageEventRecord(pkg, UsageEventType.BACKGROUND, at, activity)

    private fun totals(vararg events: UsageEventRecord) =
        ForegroundTimeCalculator.totalsByPackage(events.toList(), WINDOW_START, WINDOW_END)

    @Test
    fun `pairs a single session`() {
        val result = totals(
            foreground(INSTAGRAM, 10_000L),
            background(INSTAGRAM, 70_000L),
        )

        assertEquals(60_000L, result[INSTAGRAM])
    }

    @Test
    fun `sums repeated sessions for the same app`() {
        val result = totals(
            foreground(INSTAGRAM, 10_000L),
            background(INSTAGRAM, 20_000L),
            foreground(INSTAGRAM, 30_000L),
            background(INSTAGRAM, 45_000L),
        )

        assertEquals(25_000L, result[INSTAGRAM])
    }

    @Test
    fun `session still open at window end runs until the window closes`() {
        // The app is in the foreground right now - this is the common case when
        // refreshing while the user is actively scrolling.
        val result = totals(foreground(INSTAGRAM, 41_000L))

        assertEquals(WINDOW_END - 41_000L, result[INSTAGRAM])
    }

    @Test
    fun `background with no foreground counts from the window start`() {
        // The app was already open when the window began, so its RESUMED event
        // falls outside the query range.
        val result = totals(background(INSTAGRAM, 31_000L))

        assertEquals(30_000L, result[INSTAGRAM])
    }

    @Test
    fun `repeated foreground without background keeps the earliest`() {
        val result = totals(
            foreground(INSTAGRAM, 11_000L),
            foreground(INSTAGRAM, 51_000L),
            background(INSTAGRAM, 71_000L),
        )

        assertEquals(60_000L, result[INSTAGRAM])
    }

    @Test
    fun `tracks interleaved apps independently`() {
        val result = totals(
            foreground(INSTAGRAM, 10_000L),
            background(INSTAGRAM, 25_000L),
            foreground(WHATSAPP, 25_000L),
            background(WHATSAPP, 30_000L),
        )

        assertEquals(15_000L, result[INSTAGRAM])
        assertEquals(5_000L, result[WHATSAPP])
    }

    @Test
    fun `ignores events outside the window`() {
        val result = totals(
            foreground(INSTAGRAM, WINDOW_START - 5_000L),
            background(INSTAGRAM, WINDOW_END + 5_000L),
        )

        // Both events are out of range, so nothing is left to pair.
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles out-of-order input`() {
        val result = totals(
            background(INSTAGRAM, 70_000L),
            foreground(INSTAGRAM, 10_000L),
        )

        assertEquals(60_000L, result[INSTAGRAM])
    }

    @Test
    fun `omits apps with no measurable time`() {
        val result = totals(
            foreground(INSTAGRAM, 10_000L),
            background(INSTAGRAM, 10_000L),
        )

        assertTrue(result.isEmpty())
    }

    // --- Several activities of one app at once. Measured on CPH2585, where the
    // --- per-package pairing turned 28 minutes of YouTube into 19 hours.

    @Test
    fun `two activities resumed together count once, until the last one pauses`() {
        // InstaPro opens through LauncherActivity and PinLockActivity together.
        val result = totals(
            resumed(INSTAGRAM, "Launcher", 10_000L),
            resumed(INSTAGRAM, "PinLock", 10_100L),
            paused(INSTAGRAM, "Launcher", 10_200L),
            paused(INSTAGRAM, "PinLock", 40_000L),
        )

        assertEquals(30_000L, result[INSTAGRAM])
    }

    @Test
    fun `an unmatched pause mid-window is not credited from the window start`() {
        // The exact failure: a trampoline's pause met no open session and was
        // credited from midnight.
        val result = totals(
            resumed(INSTAGRAM, "Main", 10_000L),
            paused(INSTAGRAM, "Main", 20_000L),
            paused(INSTAGRAM, "UrlTrampoline", 90_000L),
        )

        assertEquals(10_000L, result[INSTAGRAM])
    }

    @Test
    fun `an unmatched pause as the first event still counts from the window start`() {
        // The one case the old rule was for: open since before midnight.
        val result = totals(
            paused(INSTAGRAM, "Main", 25_000L),
        )

        assertEquals(24_000L, result[INSTAGRAM])
    }

    @Test
    fun `activities paused out of order still close the session correctly`() {
        val result = totals(
            resumed(INSTAGRAM, "A", 10_000L),
            resumed(INSTAGRAM, "B", 20_000L),
            paused(INSTAGRAM, "A", 30_000L),
            resumed(INSTAGRAM, "A", 35_000L),
            paused(INSTAGRAM, "B", 40_000L),
            paused(INSTAGRAM, "A", 50_000L),
        )

        assertEquals(40_000L, result[INSTAGRAM])
    }

    @Test
    fun `no app is ever credited more than the window`() {
        val result = totals(
            paused(INSTAGRAM, "Main", WINDOW_END),
            resumed(INSTAGRAM, "Main", WINDOW_START),
        )

        assertTrue((result[INSTAGRAM] ?: 0L) <= WINDOW_END - WINDOW_START)
    }

    @Test
    fun `returns empty for an inverted window`() {
        val result = ForegroundTimeCalculator.totalsByPackage(
            events = listOf(foreground(INSTAGRAM, 10_000L)),
            windowStartMillis = WINDOW_END,
            windowEndMillis = WINDOW_START,
        )

        assertTrue(result.isEmpty())
    }
}
