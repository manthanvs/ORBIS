package com.orbis.app.ui;

import android.content.Context;
import android.net.VpnService;

import com.orbis.app.surface.AccessibilityAccess;
import com.orbis.app.usage.UsageAccess;
import com.orbis.app.util.StateHolder;

/**
 * Which of the three permissions ORBIS actually has, right now.
 *
 * <p>Process-wide for the same reason {@link com.orbis.app.surface.SurfaceMonitor}
 * is: every one of these is granted <em>outside</em> the app - in Settings, or in
 * a system dialog - so nothing inside the app is told when one changes. The
 * activity re-reads all three on resume and publishes them here; the screens
 * observe. Nothing is ever cached beyond that.
 */
public final class Access {

    private static final StateHolder<Access> STATE = new StateHolder<>(new Access());

    public final boolean hasUsageAccess;
    public final boolean hasDetection;

    /**
     * Whether Android currently lets ORBIS run its VPN.
     *
     * <p>Tracked apart from the auto-throttle setting because the two drift: the
     * home screen's "Turn it on" once flipped the setting without ever asking,
     * and another VPN app taking over revokes consent silently later. Either way
     * the screen said "You are all set" while nothing could be slowed.
     */
    public final boolean hasVpnConsent;

    private Access(boolean hasUsageAccess, boolean hasDetection, boolean hasVpnConsent) {
        this.hasUsageAccess = hasUsageAccess;
        this.hasDetection = hasDetection;
        this.hasVpnConsent = hasVpnConsent;
    }

    /** Nothing granted - what the screens show before the first check. */
    private Access() {
        this(false, false, false);
    }

    public static StateHolder<Access> state() {
        return STATE;
    }

    /**
     * Re-reads all three from the system and publishes them.
     *
     * <p>Called from {@code onResume}, never cached: returning to the foreground
     * is the only reliable moment to notice a grant made in Settings.
     */
    public static void refresh(Context context) {
        STATE.set(
                new Access(
                        UsageAccess.isGranted(context),
                        AccessibilityAccess.isEnabled(context),
                        VpnService.prepare(context) == null));
    }
}
