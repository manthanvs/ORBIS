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
    val friction: Friction,
    val reason: String,
) {
    val delayMillis: Long get() = friction.delayMillis
    val active: Boolean get() = friction.active

    companion object {
        val NONE = ThrottleRule(Friction.NONE, "not a throttled surface")
    }
}

/**
 * Decides how a short-form feed is slowed.
 *
 * Phase 2b runs this with [passthrough] = true, so every rule resolves to no
 * friction at all: the tunnel is proved to carry traffic correctly before it is
 * allowed to interfere with any.
 *
 * The friction is a **pulse** - see [Friction] for why a constant delay was
 * replaced - and its strength is a [ThrottleLevel]. Every level squeezes the
 * feed to a trickle for part of every five seconds and caps the gap between
 * squeezes; the higher the level, the longer the squeeze and the tighter the gap.
 */
object ThrottleEngine {

    // The ends of the ladder, named for the UI and the tests.
    val BASE_DELAY_MILLIS = ThrottleLevel.NUDGE.friction.delayMillis
    val MAX_DELAY_MILLIS = ThrottleLevel.IMMUNE.friction.delayMillis
    val MIN_SQUEEZE_MILLIS = ThrottleLevel.NUDGE.friction.squeezeMillis
    val MAX_SQUEEZE_MILLIS = ThrottleLevel.IMMUNE.friction.squeezeMillis
    val LIGHT_OPEN_BYTES_PER_SECOND = ThrottleLevel.NUDGE.friction.openBytesPerSecond
    val HEAVY_OPEN_BYTES_PER_SECOND = ThrottleLevel.IMMUNE.friction.openBytesPerSecond
    const val PULSE_PERIOD_MILLIS = ThrottleLevel.PULSE_PERIOD_MILLIS

    /** The friction the manual 30-second relay test runs at: the heaviest there is. */
    val TEST_FRICTION: Friction = ThrottleLevel.IMMUNE.friction

    fun ruleFor(
        surface: Surface,
        profile: UsageProfile,
        baselineMillis: Long = 0L,
        passthrough: Boolean = false,
    ): ThrottleRule {
        if (passthrough) return ThrottleRule(Friction.NONE, "passthrough (Phase 2b)")
        if (!surface.throttled) return ThrottleRule.NONE

        val minutes = scoreMillis(profile, baselineMillis) / 60_000L
        val level = ThrottleLevel.forMinutes(minutes)
        return ThrottleRule(
            friction = level.friction,
            reason = "$surface, level ${level.number} ${level.label}, ${minutes}m",
        )
    }

    /** The level [profile] and [baselineMillis] currently earn, for the UI to name. */
    fun levelFor(profile: UsageProfile, baselineMillis: Long = 0L): ThrottleLevel =
        ThrottleLevel.forMinutes(scoreMillis(profile, baselineMillis) / 60_000L)

    /**
     * Today's short-form minutes, floored by the recent daily average.
     *
     * Today alone would drop everyone back to level 1 every midnight, so a
     * settled habit would spend each morning being nudged. The floor makes the
     * ladder a property of the account rather than of the hour: it only comes
     * down as the recent average does.
     */
    private fun scoreMillis(profile: UsageProfile, baselineMillis: Long): Long =
        maxOf(shortFormMillis(profile), baselineMillis)

    /**
     * Short-form millis across every throttleable app in [profile], together.
     *
     * It used to be the on-screen app's own minutes, which let the habit simply
     * move: two hours of Morphe earned the heaviest friction on YouTube, and then
     * Snapchat Spotlight - six minutes that day - got the lightest. The habit is
     * the user's, not the app's. WhatsApp is excluded because it is never
     * throttleable.
     */
    private fun shortFormMillis(profile: UsageProfile): Long =
        profile.entries.filter { it.app.throttled }.sumOf { it.durationMillis }

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

}
