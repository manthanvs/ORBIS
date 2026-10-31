package com.orbis.app.earn;

/**
 * The things a user can do to buy their feed back to full speed.
 *
 * <p>The economics matter more than they look. Each reward has to be worth less
 * than the effort is worth to the user, or the action becomes a chore performed
 * to farm credit; and worth enough to be a real alternative to simply switching
 * ORBIS off, which is what every restriction-only app eventually gets.
 *
 * <p>The daily limit is what stops the loop degenerating. Without it the
 * strongest player is whoever grinds the cheapest action all morning, which is
 * neither healthy nor the point.
 */
public enum EarnAction {

    /**
     * A timer the user cannot cheat by tabbing away: ORBIS already knows when a
     * short-form feed is on screen, so opening one cancels the session.
     */
    FOCUS_SESSION(
            "Focus session",
            "Fifteen minutes without opening a short-form feed.",
            5 * 60_000L,
            6);

    /**
     * How long a focus session runs.
     *
     * <p>Long enough that finishing one means something, short enough to start on
     * an impulse - the moment it needs to compete with is the one where the user
     * has already reached for the feed.
     */
    public static final long FOCUS_DURATION_MILLIS = 15 * 60_000L;

    private final String displayName;
    private final String description;
    private final long rewardMillis;
    private final int dailyLimit;

    EarnAction(String displayName, String description, long rewardMillis, int dailyLimit) {
        this.displayName = displayName;
        this.description = description;
        this.rewardMillis = rewardMillis;
        this.dailyLimit = dailyLimit;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public long getRewardMillis() {
        return rewardMillis;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    /** The action with this name, or null for one this build no longer has. */
    public static EarnAction fromName(String name) {
        for (EarnAction action : values()) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        return null;
    }
}
