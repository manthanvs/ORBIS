package com.orbis.app.throttle

import com.orbis.app.surface.Surface
import com.orbis.app.usage.UsageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionTest {

    private val pulse = Friction(
        delayMillis = 300L,
        periodMillis = 5_000L,
        squeezeMillis = 2_000L,
        squeezeBytesPerSecond = 16_000L,
        openBytesPerSecond = 0L,
    )

    // ------------------------------------------------------------------ schedule

    @Test
    fun `every cycle starts with its squeeze`() {
        // So the drag lands the moment a feed opens, not a few seconds in.
        assertTrue(pulse.squeezingAt(0L))
        assertTrue(pulse.squeezingAt(1_999L))
        assertFalse(pulse.squeezingAt(2_000L))
        assertFalse(pulse.squeezingAt(4_999L))
    }

    @Test
    fun `the pulse repeats every period`() {
        assertTrue(pulse.squeezingAt(5_000L))
        assertTrue(pulse.squeezingAt(5_000L * 100 + 500L))
        assertFalse(pulse.squeezingAt(5_000L * 100 + 3_000L))
    }

    @Test
    fun `the squeeze caps downloads and the rest of the cycle does not`() {
        assertEquals(16_000L, pulse.downloadCeilingAt(500L))
        assertEquals(0L, pulse.downloadCeilingAt(3_000L))
    }

    @Test
    fun `requests are only delayed while squeezed, so normal means normal`() {
        assertEquals(300L, pulse.delayAt(500L))
        assertEquals(0L, pulse.delayAt(3_000L))
    }

    @Test
    fun `no friction never squeezes or delays`() {
        assertFalse(Friction.NONE.squeezingAt(0L))
        assertEquals(0L, Friction.NONE.delayAt(0L))
        assertEquals(0L, Friction.NONE.downloadCeilingAt(0L))
        assertFalse(Friction.NONE.active)
    }

    @Test
    fun `friction survives the trip through an intent extra`() {
        assertEquals(pulse, Friction.fromArray(pulse.toArray()))
        assertEquals(Friction.NONE, Friction.fromArray(null))
        assertEquals(Friction.NONE, Friction.fromArray(longArrayOf(1L, 2L)))
    }

    // ----------------------------------------------------------------- policing

    @Test
    fun `an unlimited ceiling passes everything`() {
        val bucket = TokenBucket()
        repeat(1_000) { assertTrue(bucket.tryTake(1_400, 0L, it * 1_000L)) }
    }

    @Test
    fun `a squeeze starts empty rather than spending a saved-up burst`() {
        val bucket = TokenBucket()
        assertTrue(bucket.tryTake(1_400, 0L, 0L))

        // The first packet of the squeeze finds nothing banked from the open phase.
        assertFalse(bucket.tryTake(1_400, 16_000L, 1_000L))
    }

    @Test
    fun `over a second the squeeze passes roughly its rate and no more`() {
        val bucket = TokenBucket()
        val rate = 16_000L
        var passed = 0L
        // A packet every millisecond for one second: 1.4 MB offered.
        for (ms in 0L..1_000L) {
            if (bucket.tryTake(1_400, rate, ms * 1_000_000L)) passed += 1_400
        }
        assertTrue("passed $passed", passed <= rate + 1_500)
        assertTrue("passed $passed", passed >= rate / 2)
    }

    @Test
    fun `a trickle always lets a full packet through eventually`() {
        // A rate below one packet per burst window must still pass something, or
        // the squeeze would be a cut-off.
        val bucket = TokenBucket()
        bucket.tryTake(1_400, 2_000L, 0L)
        assertTrue(bucket.tryTake(1_400, 2_000L, 1_000_000_000L))
    }

    // ------------------------------------------------------------------ engine

    private fun minutes(vararg usage: Pair<String, Int>) =
        UsageProfile.from(usage.associate { (pkg, min) -> pkg to min * 60_000L })

    @Test
    fun `every throttled surface pulses every five seconds`() {
        for (surface in Surface.entries.filter { it.throttled }) {
            val friction = ThrottleEngine.ruleFor(surface, UsageProfile.EMPTY).friction
            assertEquals(ThrottleEngine.PULSE_PERIOD_MILLIS, friction.periodMillis)
            assertTrue(friction.squeezeMillis > 0L)
        }
    }

    @Test
    fun `squeezes grow with the day's short-form time`() {
        val light = ThrottleEngine.ruleFor(Surface.REELS, UsageProfile.EMPTY).friction
        val heavy = ThrottleEngine.ruleFor(
            Surface.REELS,
            minutes("com.instagram.android" to 90),
        ).friction

        assertEquals(ThrottleEngine.MIN_SQUEEZE_MILLIS, light.squeezeMillis)
        assertEquals(ThrottleEngine.MAX_SQUEEZE_MILLIS, heavy.squeezeMillis)
    }

    @Test
    fun `switching apps does not reset the friction`() {
        // Measured on device: two hours of Morphe, six minutes of Snapchat - and
        // Spotlight got the lightest friction there is. The habit is the user's.
        val profile = minutes(
            "app.morphe.android.youtube" to 120,
            "com.snapchat.android" to 6,
        )

        assertEquals(
            ThrottleEngine.MAX_SQUEEZE_MILLIS,
            ThrottleEngine.ruleFor(Surface.SPOTLIGHT, profile).friction.squeezeMillis,
        )
    }

    @Test
    fun `whatsapp time never adds friction`() {
        val friction = ThrottleEngine.ruleFor(
            Surface.REELS,
            minutes("com.whatsapp" to 300),
        ).friction

        assertEquals(ThrottleEngine.MIN_SQUEEZE_MILLIS, friction.squeezeMillis)
    }

    @Test
    fun `a squeeze always leaves part of every cycle at full speed`() {
        val heaviest = ThrottleEngine.ruleFor(
            Surface.SHORTS,
            minutes("com.google.android.youtube" to 24 * 60),
        ).friction

        assertTrue(heaviest.squeezeMillis < heaviest.periodMillis)
        assertTrue(heaviest.squeezingAt(0L))
        assertFalse(heaviest.squeezingAt(heaviest.periodMillis - 1L))
    }

    @Test
    fun `a squeeze is a trickle, never a cut-off`() {
        val friction = ThrottleEngine.ruleFor(Surface.REELS, UsageProfile.EMPTY).friction
        assertTrue(friction.squeezeBytesPerSecond > 0L)
    }

    @Test
    fun `the gaps between squeezes are capped, tighter on a heavier day`() {
        // Uncapped gaps let a pre-loading app refill a whole reel between squeezes.
        val light = ThrottleEngine.ruleFor(Surface.REELS, UsageProfile.EMPTY).friction
        val heavy = ThrottleEngine.ruleFor(
            Surface.REELS,
            minutes("com.instagram.android" to 90),
        ).friction

        assertEquals(ThrottleEngine.LIGHT_OPEN_BYTES_PER_SECOND, light.openBytesPerSecond)
        assertEquals(ThrottleEngine.HEAVY_OPEN_BYTES_PER_SECOND, heavy.openBytesPerSecond)
        assertTrue(heavy.openBytesPerSecond > heavy.squeezeBytesPerSecond)
    }
}
