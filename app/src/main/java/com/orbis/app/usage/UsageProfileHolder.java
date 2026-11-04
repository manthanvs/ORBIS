package com.orbis.app.usage;

import com.orbis.app.util.StateHolder;

/**
 * Process-wide holder for the most recently computed {@link UsageProfile}.
 *
 * <p>Exists because the throttle decision happens in the accessibility service,
 * which has no repository of its own, while the profile is computed in the usage
 * repository. Without somewhere for the two to meet, the gate was passing an
 * empty profile and the throttle silently never scaled.
 *
 * <p>Mirrors the surface monitor's pattern: static state, because the
 * accessibility service is owned by the system and cannot be bound to
 * conventionally.
 */
public final class UsageProfileHolder {

    private static final StateHolder<UsageProfile> PROFILE =
            new StateHolder<>(UsageProfile.EMPTY);

    /**
     * The recent daily short-form average, which floors the throttle level.
     *
     * <p>Without it every midnight would drop a settled habit back to level 1 and
     * spend the morning nudging someone who is well past nudging.
     */
    private static final StateHolder<Long> BASELINE_MILLIS = new StateHolder<>(0L);

    private UsageProfileHolder() {
        // No instances: one process-wide value.
    }

    public static StateHolder<UsageProfile> profile() {
        return PROFILE;
    }

    public static StateHolder<Long> baselineMillis() {
        return BASELINE_MILLIS;
    }

    public static void publish(UsageProfile profile, long baselineMillis) {
        PROFILE.set(profile);
        BASELINE_MILLIS.set(baselineMillis);
    }

    /** Publishes a profile, leaving the baseline as it stands. */
    public static void publish(UsageProfile profile) {
        publish(profile, BASELINE_MILLIS.get());
    }
}
