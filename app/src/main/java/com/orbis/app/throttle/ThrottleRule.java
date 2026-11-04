package com.orbis.app.throttle;

/**
 * How hard to slow the current surface, and why. Pure Java - no Android types -
 * so the policy can be tested without a device.
 */
public final class ThrottleRule {

    /** The rule for anything that is not a throttled surface: no friction. */
    public static final ThrottleRule NONE =
            new ThrottleRule(Friction.NONE, "not a throttled surface");

    private final Friction friction;
    private final String reason;

    public ThrottleRule(Friction friction, String reason) {
        this.friction = friction;
        this.reason = reason;
    }

    public Friction getFriction() {
        return friction;
    }

    /** Human-readable, for the diagnostics screen. */
    public String getReason() {
        return reason;
    }

    public long getDelayMillis() {
        return friction.getDelayMillis();
    }

    public boolean getActive() {
        return friction.getActive();
    }
}
