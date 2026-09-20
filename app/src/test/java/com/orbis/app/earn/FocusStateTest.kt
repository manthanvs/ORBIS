package com.orbis.app.earn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStateTest {

    private val start = 1_000_000L
    private val duration = EarnAction.FOCUS_DURATION_MILLIS
    private val running = FocusState(startedAtMillis = start)

    @Test
    fun `no session is neither active nor complete`() {
        val none = FocusState()

        assertFalse(none.active)
        assertFalse(none.completeAt(Long.MAX_VALUE))
        assertEquals(0L, none.remainingMillis(start))
    }

    @Test
    fun `remaining time is wall-clock arithmetic, not counted ticks`() {
        // The point of the rewrite: time the phone spent asleep still counts.
        assertEquals(duration, running.remainingMillis(start))
        assertEquals(duration - 60_000L, running.remainingMillis(start + 60_000L))
    }

    @Test
    fun `a session completes exactly at its end, not before`() {
        assertFalse(running.completeAt(start + duration - 1))
        assertTrue(running.completeAt(start + duration))
        assertTrue(running.completeAt(start + duration * 10))
    }

    @Test
    fun `a broken session never completes`() {
        val broken = running.copy(broken = true)

        assertFalse(broken.completeAt(start + duration * 10))
    }

    @Test
    fun `remaining time is clamped to the session`() {
        // A clock that jumps backwards must not show more than the session length,
        // nor a negative once it is over.
        assertEquals(duration, running.remainingMillis(start - 3_600_000L))
        assertEquals(0L, running.remainingMillis(start + duration * 2))
    }
}
