package com.orbis.app.throttle

import com.orbis.app.surface.Surface
import com.orbis.app.usage.UsageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleLevelTest {

    private fun minutes(vararg usage: Pair<String, Int>) =
        UsageProfile.from(usage.associate { (pkg, min) -> pkg to min * 60_000L })

    private fun levelAt(minutes: Int) =
        ThrottleEngine.levelFor(minutes("com.instagram.android" to minutes))

    @Test
    fun `the ladder climbs with the day and tops out at immune`() {
        assertEquals(ThrottleLevel.NUDGE, levelAt(0))
        assertEquals(ThrottleLevel.NUDGE, levelAt(19))
        assertEquals(ThrottleLevel.DRAG, levelAt(20))
        assertEquals(ThrottleLevel.STUTTER, levelAt(45))
        assertEquals(ThrottleLevel.GRIND, levelAt(90))
        assertEquals(ThrottleLevel.IMMUNE, levelAt(150))
        assertEquals(ThrottleLevel.IMMUNE, levelAt(10_000))
    }

    @Test
    fun `every step up squeezes longer and caps the gap tighter`() {
        ThrottleLevel.entries.zipWithNext { lower, higher ->
            val a = lower.friction
            val b = higher.friction
            assertTrue("$lower -> $higher squeeze", b.squeezeMillis > a.squeezeMillis)
            assertTrue("$lower -> $higher gap", b.openBytesPerSecond < a.openBytesPerSecond)
            assertTrue("$lower -> $higher delay", b.delayMillis >= a.delayMillis)
        }
    }

    @Test
    fun `even immune leaves a gap and a trickle`() {
        // The top of the ladder is still friction, not a block - the invariant
        // the whole thesis rests on.
        val friction = ThrottleLevel.IMMUNE.friction

        assertTrue(friction.squeezeMillis < friction.periodMillis)
        assertTrue(friction.squeezeBytesPerSecond > 0L)
        assertTrue(friction.openBytesPerSecond > friction.squeezeBytesPerSecond)
    }

    @Test
    fun `the recent average floors the level, so midnight does not reset it`() {
        // Two hours a day for a week, five minutes so far today.
        val level = ThrottleEngine.levelFor(
            profile = minutes("com.instagram.android" to 5),
            baselineMillis = 120 * 60_000L,
        )

        // 120 minutes a day is Grind - and nowhere near the Nudge that five
        // minutes alone would have earned.
        assertEquals(ThrottleLevel.GRIND, level)
    }

    @Test
    fun `a quiet week does not hold a heavy day down`() {
        // The floor only ever raises the level; today still counts on its own.
        val level = ThrottleEngine.levelFor(
            profile = minutes("com.instagram.android" to 200),
            baselineMillis = 0L,
        )

        assertEquals(ThrottleLevel.IMMUNE, level)
    }

    @Test
    fun `the rule names the level it applied`() {
        val rule = ThrottleEngine.ruleFor(
            Surface.REELS,
            minutes("com.instagram.android" to 200),
        )

        assertTrue(rule.reason, rule.reason.contains("level 5"))
        assertTrue(rule.reason, rule.reason.contains("Immune"))
        assertEquals(ThrottleLevel.IMMUNE.friction, rule.friction)
    }
}
