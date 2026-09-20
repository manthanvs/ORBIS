package com.orbis.app.earn

/**
 * The things a user can do to buy their feed back to full speed.
 *
 * The economics matter more than they look. Each reward has to be worth less
 * than the effort is worth to the user, or the action becomes a chore performed
 * to farm credit; and worth enough to be a real alternative to simply switching
 * ORBIS off, which is what every restriction-only app eventually gets.
 *
 * [dailyLimit] is what stops the loop degenerating. Without it the strongest
 * player is whoever grinds the cheapest action all morning, which is neither
 * healthy nor the point.
 */
enum class EarnAction(
    val displayName: String,
    val description: String,
    val rewardMillis: Long,
    val dailyLimit: Int,
) {
    /**
     * A timer the user cannot cheat by tabbing away: ORBIS already knows when a
     * short-form feed is on screen, so opening one cancels the session.
     */
    FOCUS_SESSION(
        displayName = "Focus session",
        description = "Fifteen minutes without opening a short-form feed.",
        rewardMillis = 5 * 60_000L,
        dailyLimit = 6,
    ),
    ;

    companion object {
        /**
         * How long a focus session runs.
         *
         * Long enough that finishing one means something, short enough to start
         * on an impulse - the moment it needs to compete with is the one where
         * the user has already reached for the feed.
         */
        const val FOCUS_DURATION_MILLIS = 15 * 60_000L

        fun fromName(name: String): EarnAction? = entries.firstOrNull { it.name == name }
    }
}
