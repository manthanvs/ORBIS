package com.orbis.app.surface

import com.orbis.app.usage.TargetApp

/**
 * Decides which [Surface] the user is on, from signals captured off the
 * accessibility tree. Pure, so every rule below is unit-testable.
 *
 * Two rules make this safe, and both are load-bearing:
 *
 * 1. **Scope by package first, always.** `reel_*` means *Stories* in Instagram
 *    but *Shorts* in YouTube. A cross-app substring match on "reel" would
 *    throttle Instagram Stories - exactly the surface that must stay normal.
 *
 * 2. **Match ids exactly, never by substring.** Snapchat's navigation bar carries
 *    `ngs_spotlight_icon_container` on every screen; a `contains("spotlight")`
 *    test would report Spotlight for the whole app.
 *
 * Unknown input falls through to [Surface.NORMAL]: never throttle what you have
 * not positively identified.
 */
object SurfaceDetector {

    /** Instagram calls Reels "clips" internally. `reel_*` is Stories - leave it alone. */
    private val INSTAGRAM_REELS_IDS = setOf(
        "clips_viewer_view_pager",
        "clips_viewer_container",
        "clips_viewer_video_layout",
        "clips_video_container",
        "clips_media_component",
        "root_clips_layout",
    )

    /** YouTube calls Shorts "reel" internally - the opposite of Instagram. */
    private val YOUTUBE_SHORTS_IDS = setOf(
        "reel_watch_fragment_root",
        "reel_watch_player",
        "reel_player_page_container",
        "reel_player_overlay_root",
        "reel_recycler",
    )

    /** The content container only, not `ngs_spotlight_icon_container` (the tab icon). */
    private val SNAPCHAT_SPOTLIGHT_IDS = setOf(
        "spotlight_container",
    )

    private val SHORT_VIDEO_URL = Regex(
        "(?:youtube\\.com|youtu\\.be)/shorts" +
            "|instagram\\.com/reels?/" +
            "|snapchat\\.com/spotlight",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The fully-qualified view ids worth looking for in [packageName], or null
     * when the package is not identified by ids at all (browsers are identified
     * by the URL text instead).
     *
     * Lets the accessibility service ask the framework for these ids directly
     * instead of walking the whole node tree and reporting back everything it
     * finds. The rules themselves stay here, so there is still one place that
     * decides what counts as a throttled surface.
     */
    fun candidateIdsFor(packageName: String): List<String>? = CANDIDATE_IDS[packageName]

    private fun Set<String>.qualifiedFor(packageName: String): List<String> =
        map { "$packageName:id/$it" }

    private val CANDIDATE_IDS: Map<String, List<String>> = mapOf(
        TargetApp.INSTAGRAM.packageName to
            INSTAGRAM_REELS_IDS.qualifiedFor(TargetApp.INSTAGRAM.packageName),
        TargetApp.YOUTUBE.packageName to
            YOUTUBE_SHORTS_IDS.qualifiedFor(TargetApp.YOUTUBE.packageName),
        TargetApp.SNAPCHAT.packageName to
            SNAPCHAT_SPOTLIGHT_IDS.qualifiedFor(TargetApp.SNAPCHAT.packageName),
    )

    /** True when [text] contains a short-video URL. Used for browser address bars. */
    fun isShortVideoUrl(text: String): Boolean = SHORT_VIDEO_URL.containsMatchIn(text)

    fun detect(signals: SurfaceSignals): Surface = when (signals.packageName) {
        TargetApp.INSTAGRAM.packageName ->
            signals.matches(INSTAGRAM_REELS_IDS).toSurface(Surface.REELS)

        TargetApp.YOUTUBE.packageName ->
            signals.matches(YOUTUBE_SHORTS_IDS).toSurface(Surface.SHORTS)

        TargetApp.SNAPCHAT.packageName ->
            signals.matches(SNAPCHAT_SPOTLIGHT_IDS).toSurface(Surface.SPOTLIGHT)

        in BrowserPackages.ALL ->
            signals.texts.any { SHORT_VIDEO_URL.containsMatchIn(it) }
                .toSurface(Surface.BROWSER_SHORT_VIDEO)

        // Includes WhatsApp and every untracked app.
        else -> Surface.NORMAL
    }

    private fun SurfaceSignals.matches(ids: Set<String>): Boolean =
        resourceIds.any { it.simpleId() in ids }

    /** `com.instagram.android:id/clips_tab` -> `clips_tab`; bare names pass through. */
    private fun String.simpleId(): String = substringAfterLast(":id/")

    private fun Boolean.toSurface(whenTrue: Surface): Surface =
        if (this) whenTrue else Surface.NORMAL
}
