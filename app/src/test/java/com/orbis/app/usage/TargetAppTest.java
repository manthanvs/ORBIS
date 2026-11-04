package com.orbis.app.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TargetAppTest {

    @Test
    public void whatsappIsNeverThrottleable() {
        // Hard invariant from CLAUDE.md: WhatsApp is a communication tool, not
        // passive-scroll content. If this test ever fails, the throttle engine is
        // about to slow down someone's messaging.
        assertFalse(TargetApp.WHATSAPP.getThrottled());
        assertFalse(TargetApp.isThrottleable("com.whatsapp"));
        assertFalse(TargetApp.getThrottleable().contains(TargetApp.WHATSAPP));
    }

    @Test
    public void onlyInstagramYoutubeAndSnapchatAreThrottleable() {
        assertEquals(
                EnumSet.of(TargetApp.INSTAGRAM, TargetApp.YOUTUBE, TargetApp.SNAPCHAT),
                EnumSet.copyOf(TargetApp.getThrottleable()));
    }

    @Test
    public void resolvesKnownPackages() {
        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage("com.instagram.android"));
        assertEquals(TargetApp.YOUTUBE, TargetApp.fromPackage("com.google.android.youtube"));
        assertEquals(TargetApp.SNAPCHAT, TargetApp.fromPackage("com.snapchat.android"));
        assertEquals(TargetApp.WHATSAPP, TargetApp.fromPackage("com.whatsapp"));
    }

    @Test
    public void untrackedPackagesAreUnknownAndNotThrottleable() {
        assertNull(TargetApp.fromPackage("com.android.chrome"));
        assertFalse(TargetApp.isThrottleable("com.android.chrome"));
    }

    @Test
    public void packageNamesAreUnique() {
        List<TargetApp> apps = Arrays.asList(TargetApp.values());
        Set<String> packages = new HashSet<>();
        for (TargetApp app : apps) {
            packages.add(app.getPackageName());
        }
        assertEquals(apps.size(), packages.size());
    }
}
