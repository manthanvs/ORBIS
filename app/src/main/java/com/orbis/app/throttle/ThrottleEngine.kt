package com.orbis.app.throttle

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

        val app = surface.owningApp()
        val minutes = app?.let { profile.durationOf(it) / 60_000L } ?: 0L

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

    /** Which app a surface belongs to; browser short-video has no single owner. */
    private fun Surface.owningApp(): TargetApp? = when (this) {
        Surface.REELS -> TargetApp.INSTAGRAM
        Surface.SHORTS -> TargetApp.YOUTUBE
        Surface.SPOTLIGHT -> TargetApp.SNAPCHAT
        Surface.BROWSER_SHORT_VIDEO, Surface.NORMAL -> null
    }
}
