package com.orbis.app.usage

import com.orbis.app.surface.Surface
import com.orbis.app.surface.SurfaceDetector
import com.orbis.app.surface.SurfaceSignals
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.vpn.OrbisVpnService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A build discovered at runtime has to behave exactly like a seeded one, or
 * coverage stops at whichever mods happened to be known when this was written.
 */
class VariantRegistryTest {

    private val gbInsta = "com.gbwhatsapp.instagram.clone"

    @After
    fun tearDown() = TargetApp.forgetDiscoveredVariants()

    @Test
    fun `a discovered build resolves to its app everywhere`() {
        assertEquals(null, TargetApp.fromPackage(gbInsta))

        TargetApp.registerVariant(gbInsta, TargetApp.INSTAGRAM)

        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage(gbInsta))
        assertTrue(gbInsta in TargetApp.packageNames)
        assertTrue(gbInsta in TargetApp.throttleablePackages)
        assertTrue(TargetApp.isThrottleable(gbInsta))
    }

    @Test
    fun `a discovered build is detected, routed and measured like the original`() {
        TargetApp.registerVariant(gbInsta, TargetApp.INSTAGRAM)

        // Detected: its own package's ids, not Instagram's.
        assertEquals(
            Surface.REELS,
            SurfaceDetector.detect(
                SurfaceSignals(gbInsta, resourceIds = setOf("$gbInsta:id/clips_viewer_view_pager")),
            ),
        )
        assertTrue(
            SurfaceDetector.candidateIdsFor(gbInsta).orEmpty().all { it.startsWith("$gbInsta:id/") },
        )

        // Routed: the build on screen, not the official package.
        assertEquals(listOf(gbInsta), ThrottleEngine.routeFor(Surface.REELS, gbInsta))
        assertTrue(gbInsta in OrbisVpnService.routedPackages())

        // Measured: its minutes count towards Instagram's, so the level climbs.
        assertEquals(
            30 * 60_000L,
            UsageProfile.from(mapOf(gbInsta to 30 * 60_000L)).durationOf(TargetApp.INSTAGRAM),
        )
    }

    @Test
    fun `stories stay normal in a discovered build too`() {
        TargetApp.registerVariant(gbInsta, TargetApp.INSTAGRAM)

        assertEquals(
            Surface.NORMAL,
            SurfaceDetector.detect(
                SurfaceSignals(gbInsta, resourceIds = setOf("$gbInsta:id/reel_viewer_root")),
            ),
        )
    }

    @Test
    fun `whatsapp can never be discovered as a throttleable app`() {
        // Discovery only ever asks about the three short-form apps, but the
        // invariant is worth a lock of its own.
        TargetApp.registerVariant("com.whatsapp.clone", TargetApp.WHATSAPP)

        assertFalse(TargetApp.isThrottleable("com.whatsapp.clone"))
        assertFalse("com.whatsapp.clone" in OrbisVpnService.routedPackages())
    }

    @Test
    fun `forgetting discovered builds leaves the seeded ones`() {
        TargetApp.registerVariant(gbInsta, TargetApp.INSTAGRAM)
        TargetApp.forgetDiscoveredVariants()

        assertEquals(null, TargetApp.fromPackage(gbInsta))
        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage("com.instapro2.android"))
        assertEquals(TargetApp.YOUTUBE, TargetApp.fromPackage("app.morphe.android.youtube"))
    }
}
