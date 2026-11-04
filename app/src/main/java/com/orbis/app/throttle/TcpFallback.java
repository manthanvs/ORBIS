package com.orbis.app.throttle;

import com.orbis.app.util.StateHolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Apps ORBIS has stood down for, and until when.
 *
 * <p>Once an app's traffic is on a road the relay cannot carry, ORBIS can only
 * starve it, so it leaves that app at full speed instead - a missed throttle is a
 * much smaller failure than a broken app. The stand-down doubles each time it
 * trips for the same app, because an app that has decided UDP is broken keeps
 * deciding it for a while: a client's network stack backs off from QUIC the same
 * way.
 *
 * <p>Static state for the same reason {@link com.orbis.app.earn.ClearTimeHolder}
 * is: the VPN service decides, and the accessibility gate - which cannot be bound
 * to - has to respect it.
 */
public final class TcpFallback {

    public static final long FIRST_STAND_DOWN_MILLIS = 10 * 60_000L;
    public static final long MAX_STAND_DOWN_MILLIS = 60 * 60_000L;

    private static final Map<String, Integer> STRIKES = new HashMap<>();

    /** Package to wall-clock millis it is left alone until. */
    private static final StateHolder<Map<String, Long>> STANDING_DOWN =
            new StateHolder<>(Collections.<String, Long>emptyMap());

    private TcpFallback() {
        // No instances: one process-wide stand-down list.
    }

    public static StateHolder<Map<String, Long>> standingDown() {
        return STANDING_DOWN;
    }

    /** Stands the package down and returns for how long, in millis. */
    public static synchronized long standDown(String packageName, long nowMillis) {
        Integer previous = STRIKES.get(packageName);
        int strike = (previous == null ? 0 : previous) + 1;
        STRIKES.put(packageName, strike);

        long duration = FIRST_STAND_DOWN_MILLIS << Math.min(strike - 1, 10);
        duration = Math.min(duration, MAX_STAND_DOWN_MILLIS);

        Map<String, Long> live = new HashMap<>(active(nowMillis));
        live.put(packageName, nowMillis + duration);
        STANDING_DOWN.set(Collections.unmodifiableMap(live));
        return duration;
    }

    public static boolean isStandingDown(String packageName, long nowMillis) {
        Long until = STANDING_DOWN.get().get(packageName);
        return until != null && until > nowMillis;
    }

    /** Forgets expired entries; strikes are kept so a repeat still doubles. */
    public static synchronized Map<String, Long> active(long nowMillis) {
        Map<String, Long> current = STANDING_DOWN.get();
        Map<String, Long> live = new HashMap<>();
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            if (entry.getValue() > nowMillis) {
                live.put(entry.getKey(), entry.getValue());
            }
        }
        if (live.size() != current.size()) {
            STANDING_DOWN.set(Collections.unmodifiableMap(live));
            return STANDING_DOWN.get();
        }
        return current;
    }

    /** For tests. */
    public static synchronized void clear() {
        STRIKES.clear();
        STANDING_DOWN.set(Collections.<String, Long>emptyMap());
    }
}
