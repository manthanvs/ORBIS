package com.orbis.app.throttle;

import com.orbis.app.surface.BrowserPackages;
import com.orbis.app.surface.Surface;
import com.orbis.app.usage.AppUsage;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.usage.UsageProfile;

import java.util.Collections;
import java.util.List;

/**
 * Decides how a short-form feed is slowed.
 *
 * <p>Phase 2b runs this with {@code passthrough} true, so every rule resolves to
 * no friction at all: the tunnel is proved to carry traffic correctly before it
 * is allowed to interfere with any.
 *
 * <p>The friction is a <b>pulse</b> - see {@link Friction} for why a constant
 * delay was replaced - and its strength is a {@link ThrottleLevel}. Every level
 * squeezes the feed to a trickle for part of every five seconds and caps the gap
 * between squeezes; the higher the level, the longer the squeeze and the tighter
 * the gap.
 */
public final class ThrottleEngine {

    // The ends of the ladder, named for the UI and the tests.
    public static final long BASE_DELAY_MILLIS = ThrottleLevel.NUDGE.getFriction().getDelayMillis();
    public static final long MAX_DELAY_MILLIS = ThrottleLevel.IMMUNE.getFriction().getDelayMillis();
    public static final long MIN_SQUEEZE_MILLIS =
            ThrottleLevel.NUDGE.getFriction().getSqueezeMillis();
    public static final long MAX_SQUEEZE_MILLIS =
            ThrottleLevel.IMMUNE.getFriction().getSqueezeMillis();
    public static final long LIGHT_OPEN_BYTES_PER_SECOND =
            ThrottleLevel.NUDGE.getFriction().getOpenBytesPerSecond();
    public static final long HEAVY_OPEN_BYTES_PER_SECOND =
            ThrottleLevel.IMMUNE.getFriction().getOpenBytesPerSecond();
    public static final long PULSE_PERIOD_MILLIS = ThrottleLevel.PULSE_PERIOD_MILLIS;

    /** The friction the manual 30-second relay test runs at: the heaviest there is. */
    public static final Friction TEST_FRICTION = ThrottleLevel.IMMUNE.getFriction();

    private ThrottleEngine() {
        // No instances: the policy is stateless.
    }

    public static ThrottleRule ruleFor(Surface surface, UsageProfile profile) {
        return ruleFor(surface, profile, 0L, false);
    }

    public static ThrottleRule ruleFor(
            Surface surface, UsageProfile profile, long baselineMillis) {
        return ruleFor(surface, profile, baselineMillis, false);
    }

    public static ThrottleRule ruleFor(
            Surface surface, UsageProfile profile, long baselineMillis, boolean passthrough) {
        if (passthrough) {
            return new ThrottleRule(Friction.NONE, "passthrough (Phase 2b)");
        }
        if (!surface.getThrottled()) {
            return ThrottleRule.NONE;
        }

        long minutes = scoreMillis(profile, baselineMillis) / 60_000L;
        ThrottleLevel level = ThrottleLevel.forMinutes(minutes);
        String reason =
                surface + ", level " + level.getNumber() + " " + level.getLabel() + ", " + minutes + "m";
        return new ThrottleRule(level.getFriction(), reason);
    }

    /** The level this profile currently earns, for the UI to name. */
    public static ThrottleLevel levelFor(UsageProfile profile) {
        return levelFor(profile, 0L);
    }

    public static ThrottleLevel levelFor(UsageProfile profile, long baselineMillis) {
        return ThrottleLevel.forMinutes(scoreMillis(profile, baselineMillis) / 60_000L);
    }

    /**
     * Today's short-form minutes, floored by the recent daily average.
     *
     * <p>Today alone would drop everyone back to level 1 every midnight, so a
     * settled habit would spend each morning being nudged. The floor makes the
     * ladder a property of the account rather than of the hour: it only comes
     * down as the recent average does.
     */
    private static long scoreMillis(UsageProfile profile, long baselineMillis) {
        return Math.max(shortFormMillis(profile), baselineMillis);
    }

    /**
     * Short-form millis across every throttleable app in the profile, together.
     *
     * <p>It used to be the on-screen app's own minutes, which let the habit simply
     * move: two hours of one client earned the heaviest friction there, and then
     * Snapchat Spotlight - six minutes that day - got the lightest. The habit is
     * the user's, not the app's. WhatsApp is excluded because it is never
     * throttleable.
     */
    private static long shortFormMillis(UsageProfile profile) {
        long total = 0L;
        for (AppUsage entry : profile.getEntries()) {
            if (entry.getApp().getThrottled()) {
                total += entry.getDurationMillis();
            }
        }
        return total;
    }

    /**
     * The packages the tunnel should route while this surface is on screen - the
     * one app the user is actually watching, and nothing else.
     *
     * <p>This is the difference between "slow the reel" and "slow the phone".
     * Routing every target app at once meant watching Instagram Reels also
     * degraded YouTube, Snapchat and every routed browser, because a tunnel
     * applies its delay to all the traffic it carries and cannot tell which app
     * it came from.
     *
     * <p>{@code activePackage} names the build actually on screen. The surface
     * alone says "Instagram", but the user may be in a repackaged client, and
     * routing the official package would slow an app that is not even open while
     * the feed plays at full speed.
     */
    public static List<String> routeFor(Surface surface, String activePackage) {
        switch (surface) {
            case REELS:
                return routeTo(TargetApp.INSTAGRAM, activePackage);
            case SHORTS:
                return routeTo(TargetApp.YOUTUBE, activePackage);
            case SPOTLIGHT:
                return routeTo(TargetApp.SNAPCHAT, activePackage);
            case BROWSER_SHORT_VIDEO:
                // Never WhatsApp, and never a package that is not a known browser:
                // the detector only reports this surface for BrowserPackages.ALL,
                // and this re-checks rather than trusting it.
                if (activePackage != null
                        && BrowserPackages.INSTANCE.getALL().contains(activePackage)) {
                    return Collections.singletonList(activePackage);
                }
                return Collections.emptyList();
            case NORMAL:
            default:
                return Collections.emptyList();
        }
    }

    /** The on-screen build of this app if that is what is running, else the official one. */
    private static List<String> routeTo(TargetApp app, String activePackage) {
        if (activePackage != null && TargetApp.fromPackage(activePackage) == app) {
            return Collections.singletonList(activePackage);
        }
        return Collections.singletonList(app.getPackageName());
    }
}
