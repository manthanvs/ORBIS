package com.orbis.app.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SurfaceDetectorTest {

    private static final String INSTAGRAM = "com.instagram.android";
    private static final String YOUTUBE = "com.google.android.youtube";
    private static final String SNAPCHAT = "com.snapchat.android";
    private static final String WHATSAPP = "com.whatsapp";
    private static final String EDGE = "com.microsoft.emmx";
    private static final String INSTAPRO = "com.instapro2.android";
    private static final String MORPHE = "app.morphe.android.youtube";

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Surface byIds(String packageName, String... ids) {
        return SurfaceDetector.detect(new SurfaceSignals(packageName, set(ids)));
    }

    private static Surface byTexts(String packageName, String... texts) {
        return SurfaceDetector.detect(
                new SurfaceSignals(
                        packageName,
                        Collections.emptySet(),
                        Collections.emptySet(),
                        set(texts)));
    }

    // --- The reel_* inversion. These two tests are the whole reason the survey
    // --- was worth running: the same prefix means opposite things per app.

    @Test
    public void instagramReelIdsAreStoriesAndMustStayNormal() {
        Surface surface =
                byIds(
                        INSTAGRAM,
                        "com.instagram.android:id/reel_viewer_root",
                        "com.instagram.android:id/reel_viewer_media_container",
                        "com.instagram.android:id/reel_viewer_progress_bar");

        assertEquals(Surface.NORMAL, surface);
        assertFalse(surface.getThrottled());
    }

    @Test
    public void youtubeReelIdsAreShortsAndMustBeThrottled() {
        assertEquals(
                Surface.SHORTS,
                byIds(
                        YOUTUBE,
                        "com.google.android.youtube:id/reel_watch_player",
                        "com.google.android.youtube:id/reel_recycler"));
    }

    // --- Positive detection

    @Test
    public void instagramClipsIdsAreReels() {
        assertEquals(
                Surface.REELS,
                byIds(INSTAGRAM, "com.instagram.android:id/clips_viewer_view_pager"));
    }

    @Test
    public void snapchatSpotlightContainerIsSpotlight() {
        assertEquals(
                Surface.SPOTLIGHT, byIds(SNAPCHAT, "com.snapchat.android:id/spotlight_container"));
    }

    @Test
    public void snapchatNavBarIconAloneIsNotSpotlight() {
        // ngs_spotlight_icon_container sits in the navigation bar on every screen.
        // Substring matching on "spotlight" would report Spotlight app-wide.
        assertEquals(
                Surface.NORMAL,
                byIds(SNAPCHAT, "com.snapchat.android:id/ngs_spotlight_icon_container"));
    }

    // --- Browser URLs

    @Test
    public void browserShowingAShortsUrlIsThrottleable() {
        assertEquals(
                Surface.BROWSER_SHORT_VIDEO,
                byTexts(EDGE, "m.youtube.com/shorts/mB4suqRauAY"));
    }

    @Test
    public void browserShowingInstagramReelsUrlIsThrottleable() {
        assertEquals(
                Surface.BROWSER_SHORT_VIDEO,
                byTexts(EDGE, "https://www.instagram.com/reels/abc123/"));
    }

    @Test
    public void browserOnAnOrdinaryYoutubeVideoIsNormal() {
        assertEquals(Surface.NORMAL, byTexts(EDGE, "m.youtube.com/watch?v=mB4suqRauAY"));
    }

    @Test
    public void browserOnAnUnrelatedSiteIsNormal() {
        assertEquals(Surface.NORMAL, byTexts(EDGE, "en.wikipedia.org/wiki/Android"));
    }

    // --- Invariants

    @Test
    public void whatsappIsNeverThrottledWhateverItShows() {
        // Hard invariant. WhatsApp is not in packageNames either, so in practice
        // no events arrive - but the detector must refuse it regardless.
        assertEquals(
                Surface.NORMAL,
                SurfaceDetector.detect(
                        new SurfaceSignals(
                                WHATSAPP,
                                set(
                                        "com.whatsapp:id/clips_viewer_view_pager",
                                        "com.whatsapp:id/reel_recycler"),
                                Collections.emptySet(),
                                set("m.youtube.com/shorts/abc"))));
    }

    @Test
    public void unknownPackageIsNormal() {
        assertEquals(Surface.NORMAL, byIds("com.some.bank.app", "clips_video_container"));
    }

    @Test
    public void emptySignalsAreNormal() {
        assertEquals(Surface.NORMAL, byIds(INSTAGRAM));
    }

    @Test
    public void bareResourceIdsMatchAsWellAsQualifiedOnes() {
        assertEquals(Surface.REELS, byIds(INSTAGRAM, "clips_viewer_view_pager"));
    }

    @Test
    public void instagramFeedIdsAreNormal() {
        assertEquals(Surface.NORMAL, byIds(INSTAGRAM, "com.instagram.android:id/action_bar_root"));
    }

    // --- Modded builds. Measured on the test device: the user's Instagram was
    // --- InstaPro and their YouTube was Morphe, each carrying the official ids
    // --- under its own package name.

    @Test
    public void instaproReelsAreDetectedAsReels() {
        assertEquals(Surface.REELS, byIds(INSTAPRO, INSTAPRO + ":id/clips_viewer_view_pager"));
    }

    @Test
    public void instaproStoriesStayNormalExactlyAsInInstagram() {
        // A variant inherits the app's rules, the reel_ inversion included.
        assertEquals(Surface.NORMAL, byIds(INSTAPRO, INSTAPRO + ":id/reel_viewer_root"));
    }

    @Test
    public void morpheShortsAreDetectedAsShorts() {
        assertEquals(Surface.SHORTS, byIds(MORPHE, MORPHE + ":id/reel_watch_player"));
    }

    @Test
    public void variantIdsAreAskedForUnderTheVariantOwnPackage() {
        // Asking the framework for com.google.android.youtube:id/... inside Morphe
        // finds nothing - the ids live under app.morphe.android.youtube.
        List<String> candidates = SurfaceDetector.candidateIdsFor(MORPHE);

        assertFalse(candidates.isEmpty());
        for (String id : candidates) {
            assertTrue(id, id.startsWith(MORPHE + ":id/"));
        }
    }

    @Test
    public void whatsappHasNoCandidateIdsAtAll() {
        assertNull(SurfaceDetector.candidateIdsFor(WHATSAPP));
    }

    @Test
    public void onlyShortFormSurfacesAreMarkedThrottled() {
        Set<Surface> throttled = EnumSet.noneOf(Surface.class);
        for (Surface surface : Surface.values()) {
            if (surface.getThrottled()) {
                throttled.add(surface);
            }
        }

        assertEquals(
                EnumSet.of(
                        Surface.REELS,
                        Surface.SHORTS,
                        Surface.SPOTLIGHT,
                        Surface.BROWSER_SHORT_VIDEO),
                throttled);
    }
}
