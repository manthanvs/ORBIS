package com.orbis.app.throttle

import com.orbis.app.surface.BrowserPackages
import com.orbis.app.surface.Surface
import com.orbis.app.usage.TargetApp
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

    @Test
    fun `short video in a browser scales with the heaviest short-form app`() {
        // It has no owning app of its own, and returning zero for it pinned browser
        // Shorts at the base delay however heavily the user watched them. The habit
        // is the user's, not the app's.
        val rule = ThrottleEngine.ruleFor(Surface.BROWSER_SHORT_VIDEO, heavyInstagram)

        assertEquals(ThrottleEngine.MAX_DELAY_MILLIS, rule.delayMillis)
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun `each in-app surface routes only its own app`() {
        // The whole point of per-surface routing: a tunnel delays everything it
        // carries, so carrying more than the app on screen slows the wrong things.
        assertEquals(
            listOf(TargetApp.INSTAGRAM.packageName),
            ThrottleEngine.routeFor(Surface.REELS, TargetApp.INSTAGRAM.packageName),
        )
        assertEquals(
            listOf(TargetApp.YOUTUBE.packageName),
            ThrottleEngine.routeFor(Surface.SHORTS, TargetApp.YOUTUBE.packageName),
        )
        assertEquals(
            listOf(TargetApp.SNAPCHAT.packageName),
            ThrottleEngine.routeFor(Surface.SPOTLIGHT, TargetApp.SNAPCHAT.packageName),
        )
    }

    @Test
    fun `watching reels never routes youtube snapchat or a browser`() {
        val route = ThrottleEngine.routeFor(Surface.REELS, TargetApp.INSTAGRAM.packageName)

        assertFalse(route.contains(TargetApp.YOUTUBE.packageName))
        assertFalse(route.contains(TargetApp.SNAPCHAT.packageName))
        assertTrue(route.none { it in BrowserPackages.ALL })
    }

    @Test
    fun `no surface ever routes whatsapp`() {
        val everyPackage = TargetApp.entries.map { it.packageName } + BrowserPackages.ALL

        for (surface in Surface.entries) {
            for (packageName in everyPackage) {
                val route = ThrottleEngine.routeFor(surface, packageName)
                assertFalse(
                    "$surface from $packageName routed WhatsApp",
                    route.contains(TargetApp.WHATSAPP.packageName),
                )
            }
        }
    }

    @Test
    fun `a normal surface routes nothing at all`() {
        assertTrue(
            ThrottleEngine.routeFor(Surface.NORMAL, TargetApp.INSTAGRAM.packageName).isEmpty()
        )
    }

    @Test
    fun `browser short video routes just that browser`() {
        val chrome = "com.android.chrome"

        assertEquals(
            listOf(chrome),
            ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, chrome),
        )
    }

    @Test
    fun `an unrecognised package routes nothing rather than guessing`() {
        // Fails closed, like detection does: something ORBIS cannot attribute to
        // one app is left alone instead of being routed on a hunch.
        assertTrue(
            ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, "com.example.unknown").isEmpty()
        )
        assertTrue(ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, null).isEmpty())
    }

    @Test
    fun `every throttled surface routes exactly one app`() {
        val cases = mapOf(
            Surface.REELS to TargetApp.INSTAGRAM.packageName,
            Surface.SHORTS to TargetApp.YOUTUBE.packageName,
            Surface.SPOTLIGHT to TargetApp.SNAPCHAT.packageName,
            Surface.BROWSER_SHORT_VIDEO to "com.android.chrome",
        )

        for ((surface, activePackage) in cases) {
            assertEquals(
                "$surface should route exactly one app",
                1,
                ThrottleEngine.routeFor(surface, activePackage).size,
            )
        }
    }
}
