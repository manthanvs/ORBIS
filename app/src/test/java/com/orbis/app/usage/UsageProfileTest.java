package com.orbis.app.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UsageProfileTest {

    /** Package/millis pairs, in order. */
    private static Map<String, Long> totals(Object... pairs) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static List<TargetApp> apps(UsageProfile profile) {
        List<TargetApp> ranked = new ArrayList<>();
        for (AppUsage entry : profile.getEntries()) {
            ranked.add(entry.getApp());
        }
        return ranked;
    }

    @Test
    public void ranksAppsByTimeSpentDescending() {
        UsageProfile profile =
                UsageProfile.from(
                        totals(
                                "com.instagram.android", 30_000L,
                                "com.google.android.youtube", 90_000L,
                                "com.snapchat.android", 60_000L));

        assertEquals(
                Arrays.asList(TargetApp.YOUTUBE, TargetApp.SNAPCHAT, TargetApp.INSTAGRAM),
                apps(profile));
    }

    @Test
    public void dropsPackagesOrbisDoesNotTarget() {
        UsageProfile profile =
                UsageProfile.from(
                        totals(
                                "com.instagram.android", 10_000L,
                                "com.android.chrome", 999_000L,
                                "com.spotify.music", 500_000L));

        assertEquals(Collections.singletonList(TargetApp.INSTAGRAM), apps(profile));
        assertEquals(10_000L, profile.getTotalMillis());
    }

    @Test
    public void heaviestThrottleableSkipsWhatsappEvenWhenItIsTheTopApp() {
        // The invariant that matters: heaviest overall is WhatsApp, but the
        // throttle engine must be handed Instagram instead.
        UsageProfile profile =
                UsageProfile.from(
                        totals(
                                "com.whatsapp", 600_000L,
                                "com.instagram.android", 120_000L,
                                "com.snapchat.android", 60_000L));

        assertEquals(TargetApp.WHATSAPP, profile.getEntries().get(0).getApp());
        assertEquals(TargetApp.INSTAGRAM, profile.getHeaviestThrottleable().getApp());
    }

    @Test
    public void heaviestThrottleableIsNullWhenOnlyWhatsappWasUsed() {
        UsageProfile profile = UsageProfile.from(totals("com.whatsapp", 600_000L));

        assertNull(profile.getHeaviestThrottleable());
    }

    @Test
    public void totalMillisSumsEveryTrackedAppIncludingProtectedOnes() {
        UsageProfile profile =
                UsageProfile.from(totals("com.whatsapp", 1_000L, "com.instagram.android", 2_000L));

        assertEquals(3_000L, profile.getTotalMillis());
    }

    @Test
    public void durationOfReportsZeroForAnUnusedApp() {
        UsageProfile profile = UsageProfile.from(totals("com.instagram.android", 2_000L));

        assertEquals(2_000L, profile.durationOf(TargetApp.INSTAGRAM));
        assertEquals(0L, profile.durationOf(TargetApp.YOUTUBE));
    }

    @Test
    public void aModdedBuildTimeIsSummedIntoItsApp() {
        // An hour split between YouTube and Morphe is an hour of YouTube - that is
        // what the throttle should scale by, and what the user spent.
        UsageProfile profile =
                UsageProfile.from(
                        totals(
                                "com.google.android.youtube", 10 * 60_000L,
                                "app.morphe.android.youtube", 50 * 60_000L,
                                "com.instapro2.android", 30 * 60_000L));

        assertEquals(60 * 60_000L, profile.durationOf(TargetApp.YOUTUBE));
        assertEquals(30 * 60_000L, profile.durationOf(TargetApp.INSTAGRAM));
        assertEquals(2, profile.getEntries().size());
        assertEquals(TargetApp.YOUTUBE, profile.getHeaviestThrottleable().getApp());
    }

    @Test
    public void variantsResolveToTheirApp() {
        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage("com.instapro2.android"));
        assertEquals(TargetApp.YOUTUBE, TargetApp.fromPackage("app.morphe.android.youtube"));
        assertTrue(TargetApp.getPackageNames().contains("com.instapro2.android"));
    }

    @Test
    public void emptyInputYieldsAnEmptyProfile() {
        UsageProfile profile = UsageProfile.from(Collections.emptyMap());

        assertTrue(profile.getEntries().isEmpty());
        assertEquals(0L, profile.getTotalMillis());
        assertNull(profile.getHeaviestThrottleable());
    }
}
