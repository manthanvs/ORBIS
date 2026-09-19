package com.orbis.app.throttle

import com.orbis.app.surface.BrowserPackages
import com.orbis.app.surface.Surface
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile

/**
 * How hard to slow the current surface. Pure Kotlin - no Android types - so the
 * policy can be tested without a device.
 */
data class ThrottleRule(
    val delayMillis: Long,
    val reason: String,
) {
    val active: Boolean get() = delayMillis > 0L

    companion object {
        val NONE = ThrottleRule(0L, "not a throttled surface")
    }
}

/**
 * Decides the delay applied to short-form video traffic.
 *
 * Phase 2b runs this with [passthrough] = true, so every rule resolves to zero
 * delay: the tunnel is proved to carry traffic correctly before it is allowed to
 * interfere with any.
 *
 * Intensity scales with how heavily the app is used, per the adaptive-throttle
 * goal, but stays modest by design - a delay large enough to feel broken defeats
 * the point.
 */
object ThrottleEngine {

    /** Kept small on purpose. See the "modest delay" note in CLAUDE.md. */
    const val BASE_DELAY_MILLIS = 120L
    const val MAX_DELAY_MILLIS = 400L

    /** Daily minutes past which an app is considered heavily used. */
    private const val HEAVY_USE_MINUTES = 45L

    fun ruleFor(
        surface: Surface,
        profile: UsageProfile,
        passthrough: Boolean = false,
    ): ThrottleRule {
        if (passthrough) return ThrottleRule(0L, "passthrough (Phase 2b)")
        if (!surface.throttled) return ThrottleRule.NONE

        val minutes = weightMillisFor(surface, profile) / 60_000L

        // Ramp from the base delay up to the cap as daily use approaches the
        // heavy-use threshold, so a light user is barely touched.
        val scaled = BASE_DELAY_MILLIS +
            ((MAX_DELAY_MILLIS - BASE_DELAY_MILLIS) * minutes.coerceAtMost(HEAVY_USE_MINUTES)) /
            HEAVY_USE_MINUTES

        return ThrottleRule(
            delayMillis = scaled.coerceIn(BASE_DELAY_MILLIS, MAX_DELAY_MILLIS),
            reason = "$surface, ${minutes}m today",
        )
    }

    /**
     * The usage a surface's intensity is scaled by.
     *
     * In-app surfaces are scaled by their own app. A short-form feed in a browser
     * has no owning app, and returning zero for it pinned browser Shorts at the
     * base delay however heavily the user watched them - so it is scaled by the
     * heaviest short-form app instead. The habit is the user's, not the app's.
     */
    private fun weightMillisFor(surface: Surface, profile: UsageProfile): Long =
        when (surface) {
            Surface.BROWSER_SHORT_VIDEO ->
                profile.heaviestThrottleable?.durationMillis ?: 0L

            else -> surface.owningApp()?.let { profile.durationOf(it) } ?: 0L
        }

    /**
     * The packages the tunnel should route while [surface] is on screen - the one
     * app the user is actually watching, and nothing else.
     *
     * This is the difference between "slow the reel" and "slow the phone". Routing
     * every target app at once meant watching Instagram Reels also degraded
     * YouTube, Snapchat and every routed browser, because a tunnel applies its
     * delay to all the traffic it carries and cannot tell which app it came from.
     *
     * [activePackage] names the build actually on screen. The surface alone says
     * "Instagram", but the user may be in InstaPro, and routing the official
     * package would slow an app that is not even open while the feed plays at
     * full speed.
     */
    fun routeFor(surface: Surface, activePackage: String?): List<String> = when (surface) {
        Surface.REELS -> routeTo(TargetApp.INSTAGRAM, activePackage)
        Surface.SHORTS -> routeTo(TargetApp.YOUTUBE, activePackage)
        Surface.SPOTLIGHT -> routeTo(TargetApp.SNAPCHAT, activePackage)

        // Never WhatsApp, and never a package that is not a known browser: the
        // detector only reports this surface for BrowserPackages.ALL, and this
        // re-checks rather than trusting it.
        Surface.BROWSER_SHORT_VIDEO ->
            activePackage?.takeIf { it in BrowserPackages.ALL }?.let(::listOf).orEmpty()

        Surface.NORMAL -> emptyList()
    }

    /** The on-screen build of [app] if that is what [activePackage] is, else the official one. */
    private fun routeTo(app: TargetApp, activePackage: String?): List<String> =
        listOf(activePackage?.takeIf { TargetApp.fromPackage(it) == app } ?: app.packageName)

    /** Which app a surface belongs to; browser short-video has no single owner. */
    private fun Surface.owningApp(): TargetApp? = when (this) {
        Surface.REELS -> TargetApp.INSTAGRAM
        Surface.SHORTS -> TargetApp.YOUTUBE
        Surface.SPOTLIGHT -> TargetApp.SNAPCHAT
        Surface.BROWSER_SHORT_VIDEO, Surface.NORMAL -> null
    }
}
