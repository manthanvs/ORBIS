package com.orbis.app.surface;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import com.orbis.app.data.UsageRepository;
import com.orbis.app.earn.ClearTimeHolder;
import com.orbis.app.earn.EarnRepository;
import com.orbis.app.earn.FocusSession;
import com.orbis.app.throttle.Friction;
import com.orbis.app.throttle.TcpFallback;
import com.orbis.app.throttle.ThrottleEngine;
import com.orbis.app.throttle.ThrottleSettings;
import com.orbis.app.usage.UsageProfileHolder;
import com.orbis.app.usage.VariantDiscovery;
import com.orbis.app.util.Background;
import com.orbis.app.vpn.OrbisVpnService;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Watches the foreground screen, publishes the detected {@link Surface}, and
 * gates the tunnel on it.
 *
 * <p>Scope and cost are constrained deliberately:
 *
 * <ul>
 *   <li>{@code res/xml/accessibility_service_config.xml} lists the packages this
 *       service may observe. Events from anything else - banking, messaging,
 *       WhatsApp - never reach this process.
 *   <li>Text is read <b>only</b> for browsers, where a URL is the signal, and
 *       only from the address bar. Instagram, YouTube and Snapchat are matched on
 *       view ids alone, so ORBIS never collects captions, messages or usernames.
 *   <li>Detection asks the framework for the handful of ids that matter rather
 *       than walking the tree. Every node accessor is a binder call into the
 *       observed app, and the old breadth-first walk made roughly ten thousand of
 *       them per second during playback - on the main thread, and on the observed
 *       app's UI thread as much as ours.
 * </ul>
 */
public class OrbisAccessibilityService extends AccessibilityService {

    private static final String TAG = "OrbisSurface";

    /** Content-change events can fire many times a second while video plays. */
    private static final long MIN_INTERVAL_MILLIS = 400L;

    /** Cap on nodes visited by the browser fallback walk. */
    private static final int MAX_NODES = 400;

    /** How long the surface must stay normal before the tunnel comes down. */
    private static final long STOP_GRACE_MILLIS = 3_000L;

    /** How often the teardown check runs while the tunnel is up. */
    private static final long WATCHDOG_INTERVAL_MILLIS = 1_500L;

    /** Consent is granted once and then effectively permanent. */
    private static final long CONSENT_CACHE_MILLIS = 60_000L;

    /** How often metered clear-time spending is written back. */
    private static final long SPEND_FLUSH_MILLIS = 10_000L;

    /** How soon a missing VPN consent is checked again. */
    private static final long CONSENT_RETRY_MILLIS = 3_000L;

    /** How often usage is re-read while a feed is on screen. */
    private static final long USAGE_REFRESH_MILLIS = 60_000L;

    private long lastEvaluationMillis = 0L;
    private Surface lastLoggedSurface = null;
    private String lastLoggedPackage = null;

    /** When a throttled surface was last actually on screen. */
    private long lastThrottledMillis = 0L;

    /** When clear-time spending was last written back to the ledger. */
    private long lastFlushMillis = 0L;

    /** When today's usage was last re-read while a feed was on screen. */
    private long lastUsageRefreshMillis = 0L;

    /** When a throttled surface was last seen at all, credit or not. */
    private long lastFeedSeenMillis = 0L;

    /**
     * Cached {@link VpnService#prepare} result. Consent effectively never
     * changes, but checking it is a round trip to the system server and the gate
     * runs on every event.
     */
    private long consentCheckedMillis = 0L;

    private boolean hasConsent = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Trailing evaluation, so the last event of a burst is not simply dropped. */
    private final Runnable trailingEvaluation = this::evaluate;

    /**
     * Looks at the screen again, then decides whether the tunnel comes down.
     *
     * <p>It re-evaluates rather than trusting the last event because a video
     * playing steadily sends no events at all. Measured on CPH2585: a Short left
     * playing lost its throttle ~9 s in, because the last event had aged past the
     * grace period while the Short was still on screen - so the slowdown flickered
     * off mid-video and back on at the next tap. Looking costs a handful of binder
     * calls every interval; being wrong costs the feature.
     *
     * <p>It is also what meters clear time through a long video, for the same
     * reason.
     */
    private final Runnable watchdog =
            new Runnable() {
                @Override
                public void run() {
                    evaluate();

                    boolean running = OrbisVpnService.isRunning().get();
                    if (running
                            && SystemClock.uptimeMillis() - lastThrottledMillis
                                    >= STOP_GRACE_MILLIS) {
                        Log.i(TAG, "watchdog: no throttled surface recently, stopping tunnel");
                        OrbisVpnService.stop(OrbisAccessibilityService.this);
                    }

                    // Keep looking while there is either a tunnel to take down or
                    // a feed whose credit is being spent. Once both are gone,
                    // events take over. Keyed on a fresh sighting rather than the
                    // surface monitor: when the screen cannot be read at all - an
                    // app ORBIS does not observe - the monitor keeps its last
                    // value, and this loop would never end.
                    boolean onFeed =
                            ThrottleSettings.enabled().get()
                                    && SystemClock.uptimeMillis() - lastFeedSeenMillis
                                            < STOP_GRACE_MILLIS;
                    if (running || onFeed) {
                        scheduleWatchdog();
                    }
                }
            };

    /**
     * Stands everything down when the screen goes off.
     *
     * <p>Locking the phone with Reels open leaves Reels as the foreground surface,
     * so nothing else would ever end the session: the tunnel stayed up all night,
     * dropping the app's TCP - its background sync included - and the watchdog
     * kept re-reading a screen nobody could see. Nobody watches a feed with the
     * screen off. When it comes back on, the unlock's own window events raise the
     * tunnel again within a second if the feed is still there.
     */
    private final BroadcastReceiver screenOff =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        return;
                    }
                    handler.removeCallbacks(watchdog);
                    handler.removeCallbacks(trailingEvaluation);
                    lastFeedSeenMillis = 0L;
                    lastThrottledMillis = 0L;
                    lastFlushMillis = 0L;
                    // Credit spent up to the lock is written back now rather than
                    // waiting for a flush that, with the screen off, may not come
                    // for hours.
                    flushSpend(SystemClock.uptimeMillis());
                    if (OrbisVpnService.isRunning().get()) {
                        OrbisVpnService.stop(OrbisAccessibilityService.this);
                    }
                }
            };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // A system broadcast: delivered to a non-exported receiver, and it cannot
        // be declared in the manifest at all.
        ContextCompat.registerReceiver(
                this,
                screenOff,
                new IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        AccessibilityServiceInfo info = getServiceInfo();
        Log.i(
                TAG,
                "connected; observing="
                        + (info == null || info.packageNames == null
                                ? "ALL"
                                : String.valueOf(info.packageNames.length)));

        ThrottleSettings.init(getApplicationContext());
        FocusSession.init(getApplicationContext());
        observeInstalledBuilds();

        // The gate scales the friction by today's usage, but this service may be
        // the first thing to run in the process - the UI need never have opened.
        // Seed the profile so the first throttle is not stuck at the base level,
        // and the clear-time balance so credit earned earlier today is honoured
        // before the user ever opens the app.
        Background.run(
                () -> {
                    try {
                        UsageRepository.shared(getApplicationContext()).refreshToday();
                    } catch (Throwable ignored) {
                        // Usage access may not be granted yet.
                    }
                    try {
                        EarnRepository.shared(getApplicationContext()).refresh();
                    } catch (Throwable ignored) {
                        // An empty ledger is a valid state.
                    }
                });
    }

    /**
     * Widens what this service observes to the builds of the target apps actually
     * installed, mods included.
     *
     * <p>The config XML can only name packages known when it was written, and a
     * hardcoded list of mods ages badly - there are dozens, and the two this
     * project knew about were found by looking at one phone. Discovery only
     * registers a package that carries the app's own screen ids, so the boundary
     * widens to things ORBIS can genuinely recognise, never to anything that
     * merely opens the same links.
     */
    private void observeInstalledBuilds() {
        try {
            Set<String> observed = new HashSet<>(VariantDiscovery.run(getApplicationContext()));
            observed.addAll(BrowserPackages.ALL);

            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            // Compared by contents, not by count: discovering one build while
            // another drops out leaves the size unchanged and would skip the
            // update, so the service would keep watching a package that is gone
            // and miss the one that arrived.
            if (info.packageNames != null
                    && observed.equals(new HashSet<>(Arrays.asList(info.packageNames)))) {
                return;
            }
            info.packageNames = observed.toArray(new String[0]);
            setServiceInfo(info);
            Log.i(TAG, "observing " + observed.size() + " packages");
        } catch (Throwable ignored) {
            // Discovery is best-effort; the seeded builds still work.
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        // A window change is the transition that actually matters and is rare, so
        // it is handled at once. Content changes fire dozens of times a second
        // during playback and say almost nothing new, so they are coalesced.
        boolean immediate = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        long now = SystemClock.uptimeMillis();

        if (!immediate && now - lastEvaluationMillis < MIN_INTERVAL_MILLIS) {
            // Re-arm rather than drop: otherwise the final event of a burst is
            // discarded and the surface goes stale until the next one arrives.
            handler.removeCallbacks(trailingEvaluation);
            handler.postDelayed(trailingEvaluation, MIN_INTERVAL_MILLIS);
            return;
        }

        handler.removeCallbacks(trailingEvaluation);
        evaluate();
    }

    private void evaluate() {
        long now = SystemClock.uptimeMillis();
        lastEvaluationMillis = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // Trust the window's own package: a notification or overlay can
            // deliver an event tagged with a different one.
            CharSequence rawPackage = root.getPackageName();
            if (rawPackage == null) {
                return;
            }
            String activePackage = rawPackage.toString();

            // A system overlay covers the app without replacing it. Treat it as
            // no new information: hold the last verdict, and the tunnel with it.
            // Checked before collectSignals, which is a handful of binder calls
            // into a window whose answer we are going to discard anyway.
            if (ThrottleEngine.overlayHoldsVerdict(activePackage, now - lastFeedSeenMillis)) {
                // The watchdog is what will eventually take the tunnel down, so
                // it has to stay armed through the hold.
                scheduleWatchdog();
                return;
            }

            SurfaceSignals signals = collectSignals(root, activePackage);
            Surface surface = SurfaceDetector.detect(signals);

            // Only on transitions: content-change events fire several times a
            // second while a video plays, and logging each one buries anything
            // useful.
            if (surface != lastLoggedSurface || !activePackage.equals(lastLoggedPackage)) {
                lastLoggedSurface = surface;
                lastLoggedPackage = activePackage;
                Log.d(TAG, activePackage + " -> " + surface);
            }

            SurfaceMonitor.publish(surface, activePackage, System.currentTimeMillis());

            // Before the gate, which returns early when auto-slowing is off: a
            // focus session is broken by opening a feed whatever ORBIS would then
            // have done about it.
            if (surface.getThrottled()) {
                lastFeedSeenMillis = now;
                FocusSession.onFeedOpened(getApplicationContext());
            }

            applyThrottleGate(surface, activePackage, now);
        } finally {
            recycleCompat(root);
        }
    }

    /**
     * Brings the tunnel up only while a short-form feed is on screen, pointed only
     * at the app showing it, and takes it down again afterwards.
     *
     * <p>Both halves matter. Without the gate the tunnel would be slowing - and,
     * for anything it cannot relay, breaking - traffic during DMs, Stories and
     * ordinary browsing. Without the routing it would be doing that to every
     * target app at once, so watching Reels degraded YouTube, Snapchat and each
     * routed browser too.
     */
    private void applyThrottleGate(Surface surface, String activePackage, long nowMillis) {
        if (!ThrottleSettings.enabled().get()) {
            return;
        }

        if (!surface.getThrottled()) {
            // Teardown is driven by the watchdog rather than by this event,
            // because the events stop arriving the moment the user leaves the
            // observed apps - which is exactly when the tunnel most needs to come
            // down.
            if (OrbisVpnService.isRunning().get()) {
                scheduleWatchdog();
            }
            return;
        }

        // An app the relay would only starve - its traffic has moved to a road
        // ORBIS cannot carry - is left at full speed for a while, credit
        // untouched. Checked before anything that would count this as throttled,
        // so the watchdog brings the tunnel down.
        if (TcpFallback.isStandingDown(activePackage, System.currentTimeMillis())) {
            if (OrbisVpnService.isRunning().get()) {
                scheduleWatchdog();
            }
            return;
        }

        // Clear time the user has earned buys this feed back to full speed. ORBIS
        // then gets out of the way completely - including taking the tunnel down,
        // exactly as it would for a surface that was never throttled. Charging
        // before the consent check is deliberate: credit is spent on watching, not
        // on ORBIS being in a position to interfere.
        boolean onCredit = ClearTimeHolder.charge(nowMillis);
        flushSpend(nowMillis);
        if (onCredit) {
            // Armed even with no tunnel to take down: the watchdog's re-look is
            // what keeps charging while a single long video plays without events.
            scheduleWatchdog();
            return;
        }

        lastThrottledMillis = nowMillis;
        refreshUsageIfStale(nowMillis);
        if (!hasConsent(nowMillis)) {
            return;
        }

        // Fails closed: a throttled surface ORBIS cannot attribute to one app is
        // left alone rather than routed as a guess.
        List<String> route = ThrottleEngine.routeFor(surface, activePackage);
        if (route.isEmpty()) {
            return;
        }

        // Real usage, not an empty profile: this is what makes the throttle
        // adaptive.
        Friction friction =
                ThrottleEngine.ruleFor(
                                surface,
                                UsageProfileHolder.profile().get(),
                                UsageProfileHolder.baselineMillis().get())
                        .getFriction();

        // Compared against the service rather than a local copy, so a tunnel the
        // watchdog stopped behind this gate's back is noticed. Re-sending on every
        // evaluation would be a startService round trip several times a second.
        boolean current =
                OrbisVpnService.isRunning().get()
                        && route.equals(OrbisVpnService.routedApps().get())
                        && friction.equals(OrbisVpnService.currentFriction());

        if (!current) {
            OrbisVpnService.start(this, friction, route);
        }
        scheduleWatchdog();
    }

    /**
     * Keeps a teardown check pending for as long as the tunnel is up.
     *
     * <p>Hysteresis alone is not enough: surfaces flicker as views recycle
     * mid-scroll so the grace period is real, but a grace period that only
     * advances when another event arrives never expires once the user switches to
     * an app ORBIS does not observe. The tunnel would then stay up indefinitely,
     * in breach of the "TUN interface must be released" invariant.
     */
    private void scheduleWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLIS);
    }

    /**
     * Writes metered clear-time spending back to the ledger, occasionally.
     *
     * <p>The gate runs several times a second while a feed is on screen; a row per
     * tick would be hundreds of writes a minute. Up to {@link #SPEND_FLUSH_MILLIS}
     * of spending is therefore unbilled if the process dies - which under-charges
     * the user, the harmless direction to be wrong in.
     */
    private void flushSpend(long nowMillis) {
        if (nowMillis - lastFlushMillis < SPEND_FLUSH_MILLIS) {
            return;
        }
        lastFlushMillis = nowMillis;

        Background.run(
                () -> {
                    try {
                        EarnRepository repository =
                                EarnRepository.shared(getApplicationContext());
                        repository.flushSpend();
                        repository.refresh();
                    } catch (Throwable ignored) {
                        // Losing a flush under-charges the user, which is the
                        // harmless direction to be wrong in.
                    }
                });
    }

    /**
     * Re-reads today's usage every minute while a feed is on screen.
     *
     * <p>The level scales with today's minutes, but the profile was only ever read
     * when the service connected or the ORBIS UI opened. On an ordinary day
     * neither happens after breakfast, so the throttle stayed at the morning's
     * intensity however much the user scrolled. The gate re-reads the profile on
     * its next pass and the tunnel adopts the new friction in place.
     */
    private void refreshUsageIfStale(long nowMillis) {
        if (nowMillis - lastUsageRefreshMillis < USAGE_REFRESH_MILLIS) {
            return;
        }
        lastUsageRefreshMillis = nowMillis;
        Background.run(
                () -> {
                    try {
                        UsageRepository.shared(getApplicationContext()).refreshToday();
                    } catch (Throwable ignored) {
                        // Usage access may have been revoked; the level simply
                        // stops climbing.
                    }
                });
    }

    /**
     * A granted consent is cached for a minute; a missing one for a few seconds.
     * Caching "no" for the full minute meant the first Reels after the user tapped
     * Allow could go unslowed for up to sixty seconds.
     */
    private boolean hasConsent(long nowMillis) {
        long ttl = hasConsent ? CONSENT_CACHE_MILLIS : CONSENT_RETRY_MILLIS;
        if (nowMillis - consentCheckedMillis < ttl) {
            return hasConsent;
        }
        consentCheckedMillis = nowMillis;
        hasConsent = VpnService.prepare(this) == null;
        return hasConsent;
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: this service only observes.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenOff);
        } catch (Throwable ignored) {
            // Never registered, or already gone.
        }
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(trailingEvaluation);
        // Detection is what gates the tunnel. With it gone nothing would ever take
        // the tunnel down again, so it goes down now.
        if (OrbisVpnService.isRunning().get()) {
            OrbisVpnService.stop(this);
        }
        SurfaceMonitor.reset();
    }

    /**
     * Asks the framework for the few ids that identify a throttled surface,
     * instead of enumerating everything on screen and filtering afterwards.
     *
     * <p>Returns as soon as one is visible - the detector only needs to know
     * whether <i>any</i> matched, so there is nothing to gain from finding the
     * rest.
     */
    private SurfaceSignals collectSignals(AccessibilityNodeInfo root, String packageName) {
        List<String> candidates = SurfaceDetector.candidateIdsFor(packageName);
        if (candidates != null) {
            for (String id : candidates) {
                if (isVisible(root, id)) {
                    return new SurfaceSignals(packageName, Collections.singleton(id));
                }
            }
            return new SurfaceSignals(packageName, Collections.<String>emptySet());
        }

        if (BrowserPackages.ALL.contains(packageName)) {
            return new SurfaceSignals(
                    packageName,
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet(),
                    browserUrls(root, packageName));
        }

        return new SurfaceSignals(packageName, Collections.<String>emptySet());
    }

    /**
     * Visibility is the whole ballgame. Instagram keeps the Reels view pager alive
     * in the tree while Stories is on screen, so an id being <i>present</i> means
     * nothing - matching on presence reports REELS during Stories and throttles
     * the one surface that must stay normal. Only what the user can see counts.
     */
    private boolean isVisible(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> matches;
        try {
            matches = root.findAccessibilityNodeInfosByViewId(viewId);
        } catch (Throwable unavailable) {
            return false;
        }
        if (matches == null) {
            return false;
        }

        boolean visible = false;
        for (AccessibilityNodeInfo node : matches) {
            if (node == null) {
                continue;
            }
            if (!visible && node.isVisibleToUser()) {
                visible = true;
            }
            recycleCompat(node);
        }
        return visible;
    }

    /**
     * The address bar, by id where the browser is known, and otherwise a bounded
     * walk that stops at the first URL that matches.
     *
     * <p>The fallback matters: the toolbar is often gone during fullscreen video,
     * which is precisely when the surface is worth detecting.
     */
    private Set<String> browserUrls(AccessibilityNodeInfo root, String packageName) {
        String barId = BrowserPackages.URL_BAR_IDS.get(packageName);
        if (barId != null) {
            List<AccessibilityNodeInfo> matches;
            try {
                matches = root.findAccessibilityNodeInfosByViewId(barId);
            } catch (Throwable unavailable) {
                matches = null;
            }

            String url = null;
            if (matches != null) {
                for (AccessibilityNodeInfo node : matches) {
                    if (node == null) {
                        continue;
                    }
                    if (url == null && node.isVisibleToUser()) {
                        CharSequence text = node.getText();
                        if (text != null && text.toString().trim().length() > 0) {
                            url = text.toString();
                        }
                    }
                    recycleCompat(node);
                }
            }
            if (url != null) {
                return Collections.singleton(url);
            }
        }

        return walkForUrl(root);
    }

    /** Bounded breadth-first search that returns as soon as a URL matches. */
    private Set<String> walkForUrl(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        String found = null;

        while (!queue.isEmpty() && visited < MAX_NODES && found == null) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (node.isVisibleToUser()) {
                CharSequence raw = node.getText();
                String text = raw == null ? null : raw.toString();
                if (text != null
                        && text.trim().length() > 0
                        && SurfaceDetector.isShortVideoUrl(text)) {
                    found = text;
                }
            }

            if (found == null) {
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }

            // Never recycle the caller's root; evaluate() owns that one.
            if (node != root) {
                recycleCompat(node);
            }
        }

        // Whatever is still queued was obtained here and is now unreachable.
        for (AccessibilityNodeInfo pending : queue) {
            if (pending != root) {
                recycleCompat(pending);
            }
        }

        return found == null ? Collections.<String>emptySet() : Collections.singleton(found);
    }

    /**
     * Recycling is mandatory below API 33: every node handed out holds a native
     * buffer from a fixed pool, and this walk used to leak all of them. It is a
     * no-op from 33 onwards, where the pool was removed.
     */
    @SuppressWarnings("deprecation")
    private void recycleCompat(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
            // Already recycled; nothing to undo.
        }
    }
}
