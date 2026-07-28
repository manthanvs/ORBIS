package com.orbis.app.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private const val INSTAGRAM = "com.instagram.android"
private const val YOUTUBE = "com.google.android.youtube"
private const val SNAPCHAT = "com.snapchat.android"
private const val WHATSAPP = "com.whatsapp"
private const val EDGE = "com.microsoft.emmx"

class SurfaceDetectorTest {

    private fun detect(
        packageName: String,
        ids: Set<String> = emptySet(),
        texts: Set<String> = emptySet(),
    ) = SurfaceDetector.detect(
        SurfaceSignals(packageName = packageName, resourceIds = ids, texts = texts)
    )

    // --- The reel_* inversion. These two tests are the whole reason the survey
    // --- was worth running: the same prefix means opposite things per app.

    @Test
    fun `instagram reel_ ids are Stories and must stay normal`() {
        val surface = detect(
            INSTAGRAM,
            ids = setOf(
                "com.instagram.android:id/reel_viewer_root",
                "com.instagram.android:id/reel_viewer_media_container",
                "com.instagram.android:id/reel_viewer_progress_bar",
            ),
        )

        assertEquals(Surface.NORMAL, surface)
        assertFalse(surface.throttled)
    }

    @Test
    fun `youtube reel_ ids are Shorts and must be throttled`() {
        val surface = detect(
            YOUTUBE,
            ids = setOf(
                "com.google.android.youtube:id/reel_watch_player",
                "com.google.android.youtube:id/reel_recycler",
            ),
        )

        assertEquals(Surface.SHORTS, surface)
    }

    // --- Positive detection

    @Test
    fun `instagram clips ids are Reels`() {
        assertEquals(
            Surface.REELS,
            detect(INSTAGRAM, ids = setOf("com.instagram.android:id/clips_viewer_view_pager")),
        )
    }

    @Test
    fun `snapchat spotlight container is Spotlight`() {
        assertEquals(
            Surface.SPOTLIGHT,
            detect(SNAPCHAT, ids = setOf("com.snapchat.android:id/spotlight_container")),
        )
    }

    @Test
    fun `snapchat nav bar icon alone is not Spotlight`() {
        // ngs_spotlight_icon_container sits in the navigation bar on every screen.
        // Substring matching on "spotlight" would report Spotlight app-wide.
        assertEquals(
            Surface.NORMAL,
            detect(SNAPCHAT, ids = setOf("com.snapchat.android:id/ngs_spotlight_icon_container")),
        )
    }

    // --- Browser URLs

    @Test
    fun `browser showing a shorts url is throttleable`() {
        assertEquals(
            Surface.BROWSER_SHORT_VIDEO,
            detect(EDGE, texts = setOf("m.youtube.com/shorts/mB4suqRauAY")),
        )
    }

    @Test
    fun `browser showing instagram reels url is throttleable`() {
        assertEquals(
            Surface.BROWSER_SHORT_VIDEO,
            detect(EDGE, texts = setOf("https://www.instagram.com/reels/abc123/")),
        )
    }

    @Test
    fun `browser on an ordinary youtube video is normal`() {
        assertEquals(
            Surface.NORMAL,
            detect(EDGE, texts = setOf("m.youtube.com/watch?v=mB4suqRauAY")),
        )
    }

    @Test
    fun `browser on an unrelated site is normal`() {
        assertEquals(Surface.NORMAL, detect(EDGE, texts = setOf("en.wikipedia.org/wiki/Android")))
    }

    // --- Invariants

    @Test
    fun `whatsapp is never throttled whatever it shows`() {
        // Hard invariant. WhatsApp is not in packageNames either, so in practice
        // no events arrive - but the detector must refuse it regardless.
        assertEquals(
            Surface.NORMAL,
            detect(
                WHATSAPP,
                ids = setOf("com.whatsapp:id/clips_viewer_view_pager", "com.whatsapp:id/reel_recycler"),
                texts = setOf("m.youtube.com/shorts/abc"),
            ),
        )
    }

    @Test
    fun `unknown package is normal`() {
        assertEquals(Surface.NORMAL, detect("com.some.bank.app", ids = setOf("clips_video_container")))
    }

    @Test
    fun `empty signals are normal`() {
        assertEquals(Surface.NORMAL, detect(INSTAGRAM))
    }

    @Test
    fun `bare resource ids match as well as qualified ones`() {
        assertEquals(Surface.REELS, detect(INSTAGRAM, ids = setOf("clips_viewer_view_pager")))
    }

    @Test
    fun `instagram feed ids are normal`() {
        assertEquals(
            Surface.NORMAL,
            detect(INSTAGRAM, ids = setOf("com.instagram.android:id/action_bar_root")),
        )
    }

    @Test
    fun `only short-form surfaces are marked throttled`() {
        assertEquals(
            setOf(Surface.REELS, Surface.SHORTS, Surface.SPOTLIGHT, Surface.BROWSER_SHORT_VIDEO),
            Surface.entries.filter { it.throttled }.toSet(),
        )
    }
}
