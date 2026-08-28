package com.orbis.app.surface

import android.accessibilityservice.AccessibilityService
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.orbis.app.data.UsageRepository
import com.orbis.app.earn.ClearTimeHolder
import com.orbis.app.earn.EarnRepository
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.throttle.ThrottleSettings
import com.orbis.app.usage.UsageProfileHolder
import com.orbis.app.vpn.OrbisVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the foreground screen, publishes the detected [Surface], and gates the
 * tunnel on it.
 *
 * Scope and cost are constrained deliberately:
 *
 *  - `res/xml/accessibility_service_config.xml` lists the packages this service
 *    may observe. Events from anything else - banking, messaging, WhatsApp -
 *    never reach this process.
 *  - Text is read **only** for browsers, where a URL is the signal, and only
 *    from the address bar. Instagram, YouTube and Snapchat are matched on view
 *    ids alone, so ORBIS never collects captions, messages or usernames.
 *  - Detection asks the framework for the handful of ids that matter rather than
 *    walking the tree. Every node accessor is a binder call into the observed
 *    app, and the old breadth-first walk made roughly ten thousand of them per
 *    second during playback - on the main thread, and on Instagram's UI thread
 *    as much as ours.
 */
class OrbisAccessibilityService : AccessibilityService() {

    private var lastEvaluationMillis = 0L
    private var lastLoggedSurface: Surface? = null
    private var lastLoggedPackage: String? = null

    /** When a throttled surface was last actually on screen. */
    private var lastThrottledMillis = 0L

    /** When clear-time spending was last written back to the ledger. */
    private var lastFlushMillis = 0L

    /**
     * Cached [VpnService.prepare] result. Consent effectively never changes, but
     * checking it is a round trip to the system server and the gate runs on
     * every event.
     */
    private var consentCheckedMillis = 0L
    private var hasConsent = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** Trailing evaluation, so the last event of a burst is not simply dropped. */
    private val trailingEvaluation = Runnable { evaluate() }

    /** See [scheduleWatchdog] - this is what guarantees the tunnel comes down. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!OrbisVpnService.isRunning.value) return
            if (SystemClock.uptimeMillis() - lastThrottledMillis >= STOP_GRACE_MILLIS) {
                Log.i(TAG, "watchdog: no throttled surface recently, stopping tunnel")
                OrbisVpnService.stop(this@OrbisAccessibilityService)
                return
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; observing=" + (serviceInfo?.packageNames?.joinToString() ?: "ALL"))
        ThrottleSettings.init(applicationContext)

        // The gate scales the delay by today's usage, but this service may be the
        // first thing to run in the process - the UI need never have opened. Seed
        // the profile so the first throttle is not stuck at the base delay, and
        // the clear-time balance so credit earned earlier today is honoured before
        // the user ever opens the app.
        scope.launch {
            runCatching { UsageRepository.shared(applicationContext).refreshToday() }
            runCatching { EarnRepository.shared(applicationContext).refresh() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // A window change is the transition that actually matters and is rare, so
        // it is handled at once. Content changes fire dozens of times a second
        // during playback and say almost nothing new, so they are coalesced.
        val immediate = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val now = SystemClock.uptimeMillis()

        if (!immediate && now - lastEvaluationMillis < MIN_INTERVAL_MILLIS) {
            // Re-arm rather than drop: otherwise the final event of a burst is
            // discarded and the surface goes stale until the next one arrives.
            handler.removeCallbacks(trailingEvaluation)
            handler.postDelayed(trailingEvaluation, MIN_INTERVAL_MILLIS)
            return
        }

        handler.removeCallbacks(trailingEvaluation)
        evaluate()
    }

    private fun evaluate() {
        val now = SystemClock.uptimeMillis()
        lastEvaluationMillis = now

        val root = rootInActiveWindow ?: return
        try {
            // Trust the window's own package: a notification or overlay can
            // deliver an event tagged with a different one.
            val activePackage = root.packageName?.toString() ?: return

            val signals = collectSignals(root, activePackage)
            val surface = SurfaceDetector.detect(signals)

            // Only on transitions: content-change events fire several times a
            // second while a video plays, and logging each one buries anything
            // useful.
            if (surface != lastLoggedSurface || activePackage != lastLoggedPackage) {
                lastLoggedSurface = surface
                lastLoggedPackage = activePackage
                Log.d(TAG, "$activePackage -> $surface")
            }

            SurfaceMonitor.publish(surface, activePackage, System.currentTimeMillis())
            applyThrottleGate(surface, activePackage, now)
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Brings the tunnel up only while a short-form feed is on screen, pointed only
     * at the app showing it, and takes it down again afterwards.
     *
     * Both halves matter. Without the gate the tunnel would be slowing - and, for
     * TCP and anything it cannot relay, breaking - traffic during DMs, Stories and
     * ordinary browsing. Without the routing it would be doing that to every
     * target app at once, so watching Reels degraded YouTube, Snapchat and each
     * routed browser too.
     */
    private fun applyThrottleGate(surface: Surface, activePackage: String, nowMillis: Long) {
        if (!ThrottleSettings.enabled.value) return

        if (!surface.throttled) {
            // Teardown is driven by the watchdog rather than by this event, because
            // the events stop arriving the moment the user leaves the observed apps -
            // which is exactly when the tunnel most needs to come down.
            if (OrbisVpnService.isRunning.value) scheduleWatchdog()
            return
        }

        // Clear time the user has earned buys this feed back to full speed. ORBIS
        // then gets out of the way completely - including taking the tunnel down,
        // exactly as it would for a surface that was never throttled. Charging
        // before the consent check is deliberate: credit is spent on watching, not
        // on ORBIS being in a position to interfere.
        val onCredit = ClearTimeHolder.charge(nowMillis)
        flushSpend(nowMillis)
        if (onCredit) {
            if (OrbisVpnService.isRunning.value) scheduleWatchdog()
            return
        }

        lastThrottledMillis = nowMillis
        if (!hasConsent(nowMillis)) return

        // Fails closed: a throttled surface ORBIS cannot attribute to one app is
        // left alone rather than routed as a guess.
        val route = ThrottleEngine.routeFor(surface, activePackage)
        if (route.isEmpty()) return

        // Real usage, not EMPTY: this is what makes the throttle adaptive.
        val delay = ThrottleEngine
            .ruleFor(surface, UsageProfileHolder.profile.value)
            .delayMillis

        // Compared against the service rather than a local copy, so a tunnel the
        // watchdog stopped behind this gate's back is noticed. Re-sending on every
        // evaluation would be a startService round trip several times a second.
        val current = OrbisVpnService.isRunning.value &&
            OrbisVpnService.routedApps.value == route &&
            OrbisVpnService.currentDelayMillis == delay

        if (!current) OrbisVpnService.start(this, delay, route)
        scheduleWatchdog()
    }

    /**
     * Keeps a teardown check pending for as long as the tunnel is up.
     *
     * Hysteresis alone is not enough: surfaces flicker as views recycle mid-scroll
     * so the grace period is real, but a grace period that only advances when
     * another event arrives never expires once the user switches to an app ORBIS
     * does not observe. The tunnel would then stay up indefinitely, in breach of
     * the "TUN interface must be released" invariant.
     */
    private fun scheduleWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLIS)
    }

    /**
     * Writes metered clear-time spending back to the ledger, occasionally.
     *
     * The gate runs several times a second while a feed is on screen; a row per
     * tick would be hundreds of writes a minute. Up to [SPEND_FLUSH_MILLIS] of
     * spending is therefore unbilled if the process dies - which under-charges the
     * user, the harmless direction to be wrong in.
     */
    private fun flushSpend(nowMillis: Long) {
        if (nowMillis - lastFlushMillis < SPEND_FLUSH_MILLIS) return
        lastFlushMillis = nowMillis

        scope.launch {
            runCatching {
                val repository = EarnRepository.shared(applicationContext)
                repository.flushSpend()
                repository.refresh()
            }
        }
    }

    private fun hasConsent(nowMillis: Long): Boolean {
        if (nowMillis - consentCheckedMillis < CONSENT_CACHE_MILLIS) return hasConsent
        consentCheckedMillis = nowMillis
        hasConsent = VpnService.prepare(this) == null
        return hasConsent
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(trailingEvaluation)
        // Detection is what gates the tunnel. With it gone nothing would ever take
        // the tunnel down again, so it goes down now.
        if (OrbisVpnService.isRunning.value) OrbisVpnService.stop(this)
        scope.cancel()
        SurfaceMonitor.reset()
    }

    /**
     * Asks the framework for the few ids that identify a throttled surface,
     * instead of enumerating everything on screen and filtering afterwards.
     *
     * Returns as soon as one is visible - the detector only needs to know whether
     * *any* matched, so there is nothing to gain from finding the rest.
     */
    private fun collectSignals(
        root: AccessibilityNodeInfo,
        packageName: String,
    ): SurfaceSignals {
        SurfaceDetector.candidateIdsFor(packageName)?.let { candidates ->
            for (id in candidates) {
                if (isVisible(root, id)) {
                    return SurfaceSignals(packageName, resourceIds = setOf(id))
                }
            }
            return SurfaceSignals(packageName)
        }

        if (packageName in BrowserPackages.ALL) {
            return SurfaceSignals(packageName, texts = browserUrls(root, packageName))
        }

        return SurfaceSignals(packageName)
    }

    /**
     * Visibility is the whole ballgame. Instagram keeps the Reels view pager alive
     * in the tree while Stories is on screen, so an id being *present* means
     * nothing - matching on presence reports REELS during Stories and throttles
     * the one surface that must stay normal. Only what the user can see counts.
     */
    private fun isVisible(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val matches = runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId)
        }.getOrNull() ?: return false

        var visible = false
        for (node in matches) {
            if (node == null) continue
            if (!visible && node.isVisibleToUser) visible = true
            node.recycleCompat()
        }
        return visible
    }

    /**
     * The address bar, by id where the browser is known, and otherwise a bounded
     * walk that stops at the first URL that matches.
     *
     * The fallback matters: the toolbar is often gone during fullscreen video,
     * which is precisely when the surface is worth detecting.
     */
    private fun browserUrls(root: AccessibilityNodeInfo, packageName: String): Set<String> {
        BrowserPackages.URL_BAR_IDS[packageName]?.let { barId ->
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId(barId)
            }.getOrNull()

            var url: String? = null
            matches?.forEach { node ->
                if (node == null) return@forEach
                if (url == null && node.isVisibleToUser) {
                    url = node.text?.toString()?.takeIf { it.isNotBlank() }
                }
                node.recycleCompat()
            }
            url?.let { return setOf(it) }
        }

        return walkForUrl(root)
    }

    /** Bounded breadth-first search that returns as soon as a URL matches. */
    private fun walkForUrl(root: AccessibilityNodeInfo): Set<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var found: String? = null

        while (queue.isNotEmpty() && visited < MAX_NODES && found == null) {
            val node = queue.removeFirst()
            visited++

            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank() && SurfaceDetector.isShortVideoUrl(text)) {
                    found = text
                }
            }

            if (found == null) {
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(queue::add)
                }
            }

            // Never recycle the caller's root; evaluate() owns that one.
            if (node !== root) node.recycleCompat()
        }

        // Whatever is still queued was obtained here and is now unreachable.
        queue.forEach { if (it !== root) it.recycleCompat() }

        return if (found == null) emptySet() else setOf(found)
    }

    /**
     * Recycling is mandatory below API 33: every node handed out holds a native
     * buffer from a fixed pool, and this walk used to leak all of them. It is a
     * no-op from 33 onwards, where the pool was removed.
     */
    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        runCatching { recycle() }
    }

    private companion object {
        const val TAG = "OrbisSurface"

        /** Content-change events can fire many times a second while video plays. */
        const val MIN_INTERVAL_MILLIS = 400L

        /** Cap on nodes visited by the browser fallback walk. */
        const val MAX_NODES = 400

        /** How long the surface must stay normal before the tunnel comes down. */
        const val STOP_GRACE_MILLIS = 3_000L

        /** How often the teardown check runs while the tunnel is up. */
        const val WATCHDOG_INTERVAL_MILLIS = 1_500L

        /** Consent is granted once and then effectively permanent. */
        const val CONSENT_CACHE_MILLIS = 60_000L

        /** How often metered clear-time spending is written back. */
        const val SPEND_FLUSH_MILLIS = 10_000L
    }
}
