package com.orbis.app.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.orbis.app.surface.Surface;
import com.orbis.app.surface.SurfaceDetector;
import com.orbis.app.surface.SurfaceSignals;
import com.orbis.app.throttle.ThrottleEngine;
import com.orbis.app.vpn.OrbisVpnService;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * A build discovered at runtime has to behave exactly like a seeded one, or
 * coverage stops at whichever mods happened to be known when this was written.
 */
public class VariantRegistryTest {

    private static final String GB_INSTA = "com.gbwhatsapp.instagram.clone";

    @After
    public void tearDown() {
        TargetApp.forgetDiscoveredVariants();
    }

    @Test
    public void aDiscoveredBuildResolvesToItsAppEverywhere() {
        assertNull(TargetApp.fromPackage(GB_INSTA));

        TargetApp.registerVariant(GB_INSTA, TargetApp.INSTAGRAM);

        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage(GB_INSTA));
        assertTrue(TargetApp.getPackageNames().contains(GB_INSTA));
        assertTrue(TargetApp.getThrottleablePackages().contains(GB_INSTA));
        assertTrue(TargetApp.isThrottleable(GB_INSTA));
    }

    @Test
    public void aDiscoveredBuildIsDetectedRoutedAndMeasuredLikeTheOriginal() {
        TargetApp.registerVariant(GB_INSTA, TargetApp.INSTAGRAM);

        // Detected: its own package's ids, not Instagram's.
        assertEquals(
                Surface.REELS,
                SurfaceDetector.detect(
                        new SurfaceSignals(
                                GB_INSTA,
                                Collections.singleton(
                                        GB_INSTA + ":id/clips_viewer_view_pager"))));
        List<String> candidates = SurfaceDetector.candidateIdsFor(GB_INSTA);
        assertFalse(candidates.isEmpty());
        for (String id : candidates) {
            assertTrue(id, id.startsWith(GB_INSTA + ":id/"));
        }

        // Routed: the build on screen, not the official package.
        assertEquals(
                Collections.singletonList(GB_INSTA),
                ThrottleEngine.routeFor(Surface.REELS, GB_INSTA));
        assertTrue(OrbisVpnService.routedPackages().contains(GB_INSTA));

        // Measured: its minutes count towards Instagram's, so the level climbs.
        assertEquals(
                30 * 60_000L,
                UsageProfile.from(Collections.singletonMap(GB_INSTA, 30 * 60_000L))
                        .durationOf(TargetApp.INSTAGRAM));
    }

    @Test
    public void storiesStayNormalInADiscoveredBuildToo() {
        TargetApp.registerVariant(GB_INSTA, TargetApp.INSTAGRAM);

        assertEquals(
                Surface.NORMAL,
                SurfaceDetector.detect(
                        new SurfaceSignals(
                                GB_INSTA,
                                Collections.singleton(GB_INSTA + ":id/reel_viewer_root"))));
    }

    @Test
    public void whatsappCanNeverBeDiscoveredAsAThrottleableApp() {
        // Discovery only ever asks about the three short-form apps, but the
        // invariant is worth a lock of its own.
        TargetApp.registerVariant("com.whatsapp.clone", TargetApp.WHATSAPP);

        assertFalse(TargetApp.isThrottleable("com.whatsapp.clone"));
        assertFalse(OrbisVpnService.routedPackages().contains("com.whatsapp.clone"));
    }

    @Test
    public void forgettingDiscoveredBuildsLeavesTheSeededOnes() {
        TargetApp.registerVariant(GB_INSTA, TargetApp.INSTAGRAM);
        TargetApp.forgetDiscoveredVariants();

        assertNull(TargetApp.fromPackage(GB_INSTA));
        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage("com.instapro2.android"));
        assertEquals(TargetApp.YOUTUBE, TargetApp.fromPackage("app.morphe.android.youtube"));
    }
}
