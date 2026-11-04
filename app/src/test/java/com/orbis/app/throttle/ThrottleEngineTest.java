package com.orbis.app.throttle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orbis.app.surface.BrowserPackages;
import com.orbis.app.surface.Surface;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.usage.UsageProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ThrottleEngineTest {

    // 180 minutes: top of the ladder.
    private final UsageProfile heavyInstagram =
            UsageProfile.from(Collections.singletonMap("com.instagram.android", 180 * 60_000L));
    private final UsageProfile lightInstagram =
            UsageProfile.from(Collections.singletonMap("com.instagram.android", 60_000L));

    private static List<Surface> throttledSurfaces() {
        List<Surface> surfaces = new ArrayList<>();
        for (Surface surface : Surface.values()) {
            if (surface.getThrottled()) {
                surfaces.add(surface);
            }
        }
        return surfaces;
    }

    @Test
    public void normalSurfaceIsNeverThrottled() {
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.NORMAL, heavyInstagram);

        assertFalse(rule.getActive());
        assertEquals(0L, rule.getDelayMillis());
    }

    @Test
    public void passthroughForcesZeroDelayEvenOnAThrottledSurface() {
        // Phase 2b relies on this: the tunnel must carry traffic untouched while
        // it is being proven correct.
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram, 0L, true);

        assertFalse(rule.getActive());
        assertEquals(0L, rule.getDelayMillis());
    }

    @Test
    public void reelsOnAHeavyDayGetsTheStrongestDelay() {
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram);

        assertTrue(rule.getActive());
        assertEquals(ThrottleEngine.MAX_DELAY_MILLIS, rule.getDelayMillis());
    }

    @Test
    public void lightUseIsBarelySlowed() {
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.REELS, lightInstagram);

        assertTrue(rule.getDelayMillis() >= ThrottleEngine.BASE_DELAY_MILLIS);
        assertTrue(rule.getDelayMillis() < ThrottleEngine.MAX_DELAY_MILLIS);
    }

    @Test
    public void heavierUseIsNeverSlowedLessThanLighterUse() {
        long heavy = ThrottleEngine.ruleFor(Surface.REELS, heavyInstagram).getDelayMillis();
        long light = ThrottleEngine.ruleFor(Surface.REELS, lightInstagram).getDelayMillis();

        assertTrue("expected " + heavy + " >= " + light, heavy >= light);
    }

    @Test
    public void delayIsAlwaysWithinTheDocumentedBounds() {
        List<UsageProfile> profiles =
                Arrays.asList(
                        UsageProfile.EMPTY,
                        lightInstagram,
                        heavyInstagram,
                        // Absurd, on purpose.
                        UsageProfile.from(
                                Collections.singletonMap(
                                        "com.instagram.android", 24 * 60 * 60_000L)));

        for (Surface surface : throttledSurfaces()) {
            for (UsageProfile profile : profiles) {
                long delay = ThrottleEngine.ruleFor(surface, profile).getDelayMillis();
                assertTrue(
                        surface + " delay " + delay + " out of bounds",
                        delay >= ThrottleEngine.BASE_DELAY_MILLIS
                                && delay <= ThrottleEngine.MAX_DELAY_MILLIS);
            }
        }
    }

    @Test
    public void anUnusedAppStillGetsTheBaseDelayOnAThrottledSurface() {
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.SHORTS, UsageProfile.EMPTY);

        assertEquals(ThrottleEngine.BASE_DELAY_MILLIS, rule.getDelayMillis());
    }

    @Test
    public void shortVideoInABrowserScalesWithTheHeaviestShortFormApp() {
        // It has no owning app of its own, and returning zero for it pinned browser
        // Shorts at the base delay however heavily the user watched them. The habit
        // is the user's, not the app's.
        ThrottleRule rule = ThrottleEngine.ruleFor(Surface.BROWSER_SHORT_VIDEO, heavyInstagram);

        assertEquals(ThrottleEngine.MAX_DELAY_MILLIS, rule.getDelayMillis());
    }

    // ------------------------------------------------------------------ routing

    @Test
    public void eachInAppSurfaceRoutesOnlyItsOwnApp() {
        // The whole point of per-surface routing: a tunnel delays everything it
        // carries, so carrying more than the app on screen slows the wrong things.
        assertEquals(
                Collections.singletonList(TargetApp.INSTAGRAM.getPackageName()),
                ThrottleEngine.routeFor(Surface.REELS, TargetApp.INSTAGRAM.getPackageName()));
        assertEquals(
                Collections.singletonList(TargetApp.YOUTUBE.getPackageName()),
                ThrottleEngine.routeFor(Surface.SHORTS, TargetApp.YOUTUBE.getPackageName()));
        assertEquals(
                Collections.singletonList(TargetApp.SNAPCHAT.getPackageName()),
                ThrottleEngine.routeFor(Surface.SPOTLIGHT, TargetApp.SNAPCHAT.getPackageName()));
    }

    @Test
    public void watchingReelsNeverRoutesYoutubeSnapchatOrABrowser() {
        List<String> route =
                ThrottleEngine.routeFor(Surface.REELS, TargetApp.INSTAGRAM.getPackageName());

        assertFalse(route.contains(TargetApp.YOUTUBE.getPackageName()));
        assertFalse(route.contains(TargetApp.SNAPCHAT.getPackageName()));
        for (String routedPackage : route) {
            assertFalse(routedPackage, BrowserPackages.ALL.contains(routedPackage));
        }
    }

    @Test
    public void noSurfaceEverRoutesWhatsapp() {
        List<String> everyPackage = new ArrayList<>();
        for (TargetApp app : TargetApp.values()) {
            everyPackage.add(app.getPackageName());
        }
        everyPackage.addAll(BrowserPackages.ALL);

        for (Surface surface : Surface.values()) {
            for (String packageName : everyPackage) {
                List<String> route = ThrottleEngine.routeFor(surface, packageName);
                assertFalse(
                        surface + " from " + packageName + " routed WhatsApp",
                        route.contains(TargetApp.WHATSAPP.getPackageName()));
            }
        }
    }

    @Test
    public void aModdedBuildRoutesItsOwnPackageNotTheOfficialOne() {
        // Routing com.instagram.android while the user is in InstaPro would slow an
        // app that is not even open and leave the feed at full speed.
        assertEquals(
                Collections.singletonList("com.instapro2.android"),
                ThrottleEngine.routeFor(Surface.REELS, "com.instapro2.android"));
        assertEquals(
                Collections.singletonList("app.morphe.android.youtube"),
                ThrottleEngine.routeFor(Surface.SHORTS, "app.morphe.android.youtube"));
    }

    @Test
    public void aSurfaceNeverRoutesAnotherAppVariant() {
        // Morphe is YouTube, so Morphe on screen can never mean Reels.
        assertEquals(
                Collections.singletonList(TargetApp.INSTAGRAM.getPackageName()),
                ThrottleEngine.routeFor(Surface.REELS, "app.morphe.android.youtube"));
    }

    @Test
    public void morpheShortsScaleWithCombinedYoutubeTime() {
        UsageProfile heavyMorphe =
                UsageProfile.from(
                        Collections.singletonMap("app.morphe.android.youtube", 180 * 60_000L));

        assertEquals(
                ThrottleEngine.MAX_DELAY_MILLIS,
                ThrottleEngine.ruleFor(Surface.SHORTS, heavyMorphe).getDelayMillis());
    }

    @Test
    public void aNormalSurfaceRoutesNothingAtAll() {
        assertTrue(
                ThrottleEngine.routeFor(Surface.NORMAL, TargetApp.INSTAGRAM.getPackageName())
                        .isEmpty());
    }

    @Test
    public void browserShortVideoRoutesJustThatBrowser() {
        String chrome = "com.android.chrome";

        assertEquals(
                Collections.singletonList(chrome),
                ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, chrome));
    }

    @Test
    public void anUnrecognisedPackageRoutesNothingRatherThanGuessing() {
        // Fails closed, like detection does: something ORBIS cannot attribute to
        // one app is left alone instead of being routed on a hunch.
        assertTrue(
                ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, "com.example.unknown")
                        .isEmpty());
        assertTrue(ThrottleEngine.routeFor(Surface.BROWSER_SHORT_VIDEO, null).isEmpty());
    }

    @Test
    public void everyThrottledSurfaceRoutesExactlyOneApp() {
        Map<Surface, String> cases = new LinkedHashMap<>();
        cases.put(Surface.REELS, TargetApp.INSTAGRAM.getPackageName());
        cases.put(Surface.SHORTS, TargetApp.YOUTUBE.getPackageName());
        cases.put(Surface.SPOTLIGHT, TargetApp.SNAPCHAT.getPackageName());
        cases.put(Surface.BROWSER_SHORT_VIDEO, "com.android.chrome");

        for (Map.Entry<Surface, String> entry : cases.entrySet()) {
            assertEquals(
                    entry.getKey() + " should route exactly one app",
                    1,
                    ThrottleEngine.routeFor(entry.getKey(), entry.getValue()).size());
        }
    }
}
