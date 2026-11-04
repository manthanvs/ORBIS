package com.orbis.app.surface;

import com.orbis.app.util.StateHolder;

/**
 * Process-wide holder for the currently detected surface.
 *
 * <p>The accessibility service publishes here; the UI and the throttle gate
 * observe. Static state rather than a bound service because an
 * {@link android.accessibilityservice.AccessibilityService} is owned by the
 * system and cannot be bound to conventionally.
 */
public final class SurfaceMonitor {

    private static final StateHolder<DetectedSurface> STATE =
            new StateHolder<>(new DetectedSurface());

    private SurfaceMonitor() {
        // No instances: one process-wide value.
    }

    public static StateHolder<DetectedSurface> state() {
        return STATE;
    }

    public static void publish(Surface surface, String packageName, long nowMillis) {
        DetectedSurface current = STATE.get();
        boolean samePackage =
                packageName == null
                        ? current.getPackageName() == null
                        : packageName.equals(current.getPackageName());
        if (current.getSurface() == surface && samePackage) {
            return;
        }
        STATE.set(new DetectedSurface(surface, packageName, nowMillis));
    }

    /** Called when the service disconnects, so a stale surface cannot linger. */
    public static void reset() {
        STATE.set(new DetectedSurface());
    }
}
