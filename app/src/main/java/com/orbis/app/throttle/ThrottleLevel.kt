package com.orbis.app.throttle

/**
 * How hard ORBIS is pushing, as a ladder the user can see themselves climbing.
 *
 * The old ramp was continuous and topped out at 45 minutes a day - which a heavy
 * user passes before lunch, so every hour after that felt identical, and the
 * whole thing read as "barely there". Levels fix both: the steps are visible, and
 * the top of the ladder is far heavier than the old maximum.
 *
 * [IMMUNE] is the end state, and it is deliberately close to unwatchable: four
 * seconds of every five held to a trickle, and the gap between them capped below
 * any video bitrate. Someone living at that level is not being slowed down any
 * more - short-form has simply stopped being worth opening, which is the point.
 * It is still never a block: the trickle keeps the app alive and the user can
 * always buy the level off with clear time.
 */
enum class ThrottleLevel(
    val number: Int,
    val label: String,
    /** Daily short-form minutes at which this level starts. */
    val fromMinutes: Long,
    private val squeezeMillis: Long,
    private val openBytesPerSecond: Long,
    private val delayMillis: Long,
) {
    NUDGE(1, "Nudge", 0L, 1_500L, 600_000L, 120L),
    DRAG(2, "Drag", 20L, 2_000L, 400_000L, 200L),
    STUTTER(3, "Stutter", 45L, 2_500L, 250_000L, 280L),
    GRIND(4, "Grind", 90L, 3_000L, 150_000L, 340L),
    IMMUNE(5, "Immune", 150L, 4_000L, 60_000L, 400L),
    ;

    val friction: Friction
        get() = Friction(
            delayMillis = delayMillis,
            periodMillis = PULSE_PERIOD_MILLIS,
            squeezeMillis = squeezeMillis,
            squeezeBytesPerSecond = SQUEEZE_BYTES_PER_SECOND,
            openBytesPerSecond = openBytesPerSecond,
        )

    companion object {
        /** One squeeze-then-gap cycle. Short enough to recur inside every reel. */
        const val PULSE_PERIOD_MILLIS = 5_000L

        /**
         * The download trickle during a squeeze, ~128 kbit/s, at every level.
         * Above zero on purpose: the app's connections survive and recover the
         * moment the squeeze lifts. A cut-off reads as broken; this reads as slow.
         */
        const val SQUEEZE_BYTES_PER_SECOND = 16_000L

        fun forMinutes(minutes: Long): ThrottleLevel = entries.last { minutes >= it.fromMinutes }
    }
}
