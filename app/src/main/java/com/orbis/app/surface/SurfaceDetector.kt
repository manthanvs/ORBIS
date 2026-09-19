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

    /** The rules for an app, or null for one that is never throttled. */
    private fun idsFor(app: TargetApp?): Set<String>? = when (app) {
        TargetApp.INSTAGRAM -> INSTAGRAM_REELS_IDS
        TargetApp.YOUTUBE -> YOUTUBE_SHORTS_IDS
        TargetApp.SNAPCHAT -> SNAPCHAT_SPOTLIGHT_IDS
        TargetApp.WHATSAPP, null -> null
    }

    /**
     * Qualified with each package's *own* name, variants included. A repackaged
     * client carries the same ids under its own package - Morphe's Shorts player
     * is `app.morphe.android.youtube:id/reel_watch_player` - so asking the
     * framework for the official name would never find it.
     *
     * Precomputed because the service asks on every evaluation.
     */
    private val CANDIDATE_IDS: Map<String, List<String>> =
        TargetApp.throttleable
            .flatMap { app ->
                val ids = idsFor(app) ?: return@flatMap emptyList()
                app.allPackages.map { packageName -> packageName to ids.qualifiedFor(packageName) }
            }
            .toMap()

    /** True when [text] contains a short-video URL. Used for browser address bars. */
    fun isShortVideoUrl(text: String): Boolean = SHORT_VIDEO_URL.containsMatchIn(text)

    /**
     * Scoped by *app*, which the package resolves to - so InstaPro gets
     * Instagram's rules, and `reel_*` still means Stories there, not Shorts.
     */
    fun detect(signals: SurfaceSignals): Surface {
        if (signals.packageName in BrowserPackages.ALL) {
            return signals.texts.any { SHORT_VIDEO_URL.containsMatchIn(it) }
                .toSurface(Surface.BROWSER_SHORT_VIDEO)
        }

        return when (TargetApp.fromPackage(signals.packageName)) {
            TargetApp.INSTAGRAM ->
                signals.matches(INSTAGRAM_REELS_IDS).toSurface(Surface.REELS)

            TargetApp.YOUTUBE ->
                signals.matches(YOUTUBE_SHORTS_IDS).toSurface(Surface.SHORTS)

            TargetApp.SNAPCHAT ->
                signals.matches(SNAPCHAT_SPOTLIGHT_IDS).toSurface(Surface.SPOTLIGHT)

            // WhatsApp and every untracked app.
            TargetApp.WHATSAPP, null -> Surface.NORMAL
        }
    }

    private fun SurfaceSignals.matches(ids: Set<String>): Boolean =
        resourceIds.any { it.simpleId() in ids }

    /** `com.instagram.android:id/clips_tab` -> `clips_tab`; bare names pass through. */
    private fun String.simpleId(): String = substringAfterLast(":id/")

    private fun Boolean.toSurface(whenTrue: Surface): Surface =
        if (this) whenTrue else Surface.NORMAL
}
