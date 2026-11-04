package com.orbis.app.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orbis.app.surface.BrowserPackages;
import com.orbis.app.usage.TargetApp;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tunnel's allow-list is the strongest form the WhatsApp invariant takes: a
 * package absent from here cannot be routed at all, regardless of what any
 * detection or throttle logic later decides.
 */
public class RoutedPackagesTest {

    private final List<String> routed = OrbisVpnService.routedPackages();

    @Test
    public void whatsappIsNeverRoutedThroughTheTunnel() {
        assertFalse(routed.contains(TargetApp.WHATSAPP.getPackageName()));
    }

    @Test
    public void theThreeThrottleableAppsAreRouted() {
        assertTrue(routed.contains(TargetApp.INSTAGRAM.getPackageName()));
        assertTrue(routed.contains(TargetApp.YOUTUBE.getPackageName()));
        assertTrue(routed.contains(TargetApp.SNAPCHAT.getPackageName()));
    }

    @Test
    public void browsersAreRoutedSoBrowserShortsCanActuallyBeThrottled() {
        // Detection of BROWSER_SHORT_VIDEO is a no-op unless the browser's traffic
        // reaches the tunnel. youtube.com/shorts opens in a browser on many devices.
        for (String browser : BrowserPackages.ALL) {
            assertTrue("expected " + browser + " to be routed", routed.contains(browser));
        }
    }

    @Test
    public void routedListHasNoDuplicates() {
        assertEquals(routed.size(), new HashSet<>(routed).size());
    }

    @Test
    public void everyRoutedPackageIsEitherThrottleableOrABrowser() {
        Set<String> expected = new HashSet<>(BrowserPackages.ALL);
        for (TargetApp app : TargetApp.getThrottleable()) {
            expected.addAll(app.getAllPackages());
        }
        assertEquals(expected, new HashSet<>(routed));
    }

    @Test
    public void moddedBuildsOfTheTargetAppsCanBeRouted() {
        // InstaPro and Morphe were the user's actual Instagram and YouTube on the
        // test device. Absent from here, their feeds could never be slowed.
        assertTrue(routed.contains("com.instapro2.android"));
        assertTrue(routed.contains("app.morphe.android.youtube"));
    }
}
