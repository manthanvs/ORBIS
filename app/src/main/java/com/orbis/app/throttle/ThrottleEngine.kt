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
 * replaced. Every [PULSE_PERIOD_MILLIS] the feed is squeezed to a trickle, then
 * released to run normally, so it keeps stuttering on purpose without ever
 * breaking. How long each squeeze lasts scales with today's short-form time.
 */
object ThrottleEngine {

    const val BASE_DELAY_MILLIS = 120L
    const val MAX_DELAY_MILLIS = 400L

    /** One squeeze-then-normal cycle. Five seconds: short enough to recur in every reel. */
    const val PULSE_PERIOD_MILLIS = 5_000L

    /** Squeeze length on a light day and on a heavy one. Never the whole cycle. */
    const val MIN_SQUEEZE_MILLIS = 1_500L
    const val MAX_SQUEEZE_MILLIS = 3_000L

    /**
     * The download trickle during a squeeze, ~128 kbit/s. Far below any video
     * bitrate, so the player stalls or drops quality - but above zero, so the
     * app's connections and control traffic survive and it recovers the moment
     * the squeeze lifts. A cut-off would read as broken; this reads as slow.
     */
    const val SQUEEZE_BYTES_PER_SECOND = 16_000L

    /**
     * The ceiling *between* squeezes: ~4.8 Mbit/s on a light day, which a phone
     * video never notices, falling to ~1.3 Mbit/s on a heavy one.
     *
     * Unlimited gaps were tried first and measured on CPH2585: Instagram pulled
     * up to 5.7 MB/s in each two-second gap - more than a whole reel - so it
     * played straight through three-second squeezes without a single stall.
     * An app that pre-loads refills in the gap unless the gap itself is capped.
     */
    const val LIGHT_OPEN_BYTES_PER_SECOND = 600_000L
    const val HEAVY_OPEN_BYTES_PER_SECOND = 160_000L

    /** Daily short-form minutes past which the user is considered a heavy user. */
    private const val HEAVY_USE_MINUTES = 45L

    /** The friction the manual 30-second relay test runs at: the heaviest there is. */
    val TEST_FRICTION: Friction = frictionFor(HEAVY_USE_MINUTES)

    fun ruleFor(
        surface: Surface,
        profile: UsageProfile,
        passthrough: Boolean = false,
    ): ThrottleRule {
        if (passthrough) return ThrottleRule(Friction.NONE, "passthrough (Phase 2b)")
        if (!surface.throttled) return ThrottleRule.NONE

        val minutes = shortFormMillis(profile) / 60_000L
        return ThrottleRule(
            friction = frictionFor(minutes),
            reason = "$surface, ${minutes}m of short-form today",
        )
    }

    /**
     * Ramps from a light squeeze to a heavy one as the day's short-form minutes
     * approach the heavy-use threshold, so a light user is only nudged.
     */
    private fun frictionFor(minutes: Long): Friction {
        val used = minutes.coerceIn(0L, HEAVY_USE_MINUTES)
        fun ramp(from: Long, to: Long) = from + ((to - from) * used) / HEAVY_USE_MINUTES

        return Friction(
            delayMillis = ramp(BASE_DELAY_MILLIS, MAX_DELAY_MILLIS),
            periodMillis = PULSE_PERIOD_MILLIS,
            squeezeMillis = ramp(MIN_SQUEEZE_MILLIS, MAX_SQUEEZE_MILLIS),
            squeezeBytesPerSecond = SQUEEZE_BYTES_PER_SECOND,
            openBytesPerSecond = ramp(LIGHT_OPEN_BYTES_PER_SECOND, HEAVY_OPEN_BYTES_PER_SECOND),
        )
    }

    /**
     * Today's short-form minutes across every throttleable app, together.
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
