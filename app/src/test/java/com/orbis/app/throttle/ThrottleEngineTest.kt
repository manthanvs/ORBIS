package com.orbis.app.throttle

import com.orbis.app.surface.Surface
import com.orbis.app.usage.UsageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleEngineTest {

    private val heavyInstagram = UsageProfile.from(
        mapOf("com.instagram.android" to 60 * 60_000L) // 60 minutes
    )
    private val lightInstagram = UsageProfile.from(
        mapOf("com.instagram.android" to 60_000L) // 1 minute
    )

    @Test
    fun `normal surface is never throttled`() {
        val rule = ThrottleEngine.ruleFor(Surface.NORMAL, heavyInstagram)

        assertFalse(rule.active)
        assertEquals(0L, rule.delayMillis)
    }

    @Test
    fun `passthrough forces zero delay even on a throttled surface`() {
        // Phase 2b relies on this: the tunnel must carry traffic untouched while
        // it is being proven correct.
        val rule = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram, passthrough = true)

        assertFalse(rule.active)
        assertEquals(0L, rule.delayMillis)
    }

    @Test
    fun `reels on a heavy day gets the strongest delay`() {
        val rule = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram)

        assertTrue(rule.active)
        assertEquals(ThrottleEngine.MAX_DELAY_MILLIS, rule.delayMillis)
    }

    @Test
    fun `light use is barely slowed`() {
        val rule = ThrottleEngine.ruleFor(Surface.REELS, lightInstagram)

        assertTrue(rule.delayMillis >= ThrottleEngine.BASE_DELAY_MILLIS)
        assertTrue(rule.delayMillis < ThrottleEngine.MAX_DELAY_MILLIS)
    }

    @Test
    fun `heavier use is never slowed less than lighter use`() {
        val heavy = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram).delayMillis
        val light = ThrottleEngine.ruleFor(Surface.REELS, lightInstagram).delayMillis

        assertTrue("expected $heavy >= $light", heavy >= light)
    }

    @Test
    fun `delay is always within the documented bounds`() {
        val surfaces = Surface.entries.filter { it.throttled }
        val profiles = listOf(
            UsageProfile.EMPTY,
            lightInstagram,
            heavyInstagram,
            UsageProfile.from(mapOf("com.instagram.android" to 24 * 60 * 60_000L)), // absurd
        )

        for (surface in surfaces) {
            for (profile in profiles) {
                val delay = ThrottleEngine.ruleFor(surface, profile).delayMillis
                assertTrue(
                    "$surface delay $delay out of bounds",
                    delay in ThrottleEngine.BASE_DELAY_MILLIS..ThrottleEngine.MAX_DELAY_MILLIS,
                )
            }
        }
    }

    @Test
    fun `an unused app still gets the base delay on a throttled surface`() {
        val rule = ThrottleEngine.ruleFor(Surface.SHORTS, UsageProfile.EMPTY)

        assertEquals(ThrottleEngine.BASE_DELAY_MILLIS, rule.delayMillis)
    }
}
