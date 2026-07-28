package com.orbis.app.surface

import android.accessibilityservice.AccessibilityService
import android.net.VpnService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.data.UsageRepository
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.throttle.ThrottleSettings
import com.orbis.app.usage.UsageProfileHolder
import com.orbis.app.usage.UsageStatsSource
import com.orbis.app.vpn.OrbisVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the foreground screen and publishes the detected [Surface].
 *
 * Step 2a: detection only. Nothing here throttles anything - it exists so the
 * detection rules can be proven correct on a real device before any VPN code is
 * written against them.
 *
 * Scope and cost are constrained deliberately:
 *
 *  - `res/xml/accessibility_service_config.xml` lists the packages this service
 *    may observe. Events from anything else - banking, messaging, WhatsApp -
 *    never reach this process.
 *  - Text is read **only** for browsers, where a URL is the signal. Instagram,
 *    YouTube and Snapchat are matched on view ids alone, so ORBIS never collects
 *    captions, messages or usernames from them.
 *  - Traversal is bounded and rate-limited; this runs on the main thread on every
 *    content change, so it must stay cheap.
 */
class OrbisAccessibilityService : AccessibilityService() {

    private var lastEvaluationMillis = 0L
    private var lastLoggedSurface: Surface? = null
    private var lastLoggedPackage: String? = null
    private var normalSince = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; observing=" + (serviceInfo?.packageNames?.joinToString() ?: "ALL"))
        ThrottleSettings.init(applicationContext)

        // The gate scales the delay by today's usage, but this service may be the
        // first thing to run in the process - the UI need never have opened. Seed
        // the profile so the first throttle is not stuck at the base delay.
        scope.launch {
            runCatching {
                UsageRepository(
                    source = UsageStatsSource.from(applicationContext),
                    dao = DatabaseProvider.get(applicationContext).usageLogDao(),
                ).refreshToday()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        val now = System.currentTimeMillis()
        if (now - lastEvaluationMillis < MIN_INTERVAL_MILLIS) return
        lastEvaluationMillis = now

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "event from $packageName but rootInActiveWindow was null")
            return
        }
        // Trust the window's own package over the event's: a notification or
        // overlay can deliver an event tagged with a different package.
        val activePackage = root.packageName?.toString() ?: packageName

        val signals = collectSignals(root, activePackage)
        val surface = SurfaceDetector.detect(signals)

        // Only on transitions: content-change events fire several times a second
        // while a video plays, and logging each one buries anything useful.
        if (surface != lastLoggedSurface || activePackage != lastLoggedPackage) {
            lastLoggedSurface = surface
            lastLoggedPackage = activePackage
            Log.d(TAG, "$activePackage -> $surface (${signals.resourceIds.size} visible ids)")
        }

        SurfaceMonitor.publish(surface, activePackage, now)
        applyThrottleGate(surface, now)
    }

    /**
     * Brings the tunnel up only while a short-form feed is on screen, and takes it
     * down again afterwards. Without this the tunnel would be slowing - and, for
     * TCP and anything it cannot relay, breaking - traffic during DMs, Stories and
     * ordinary browsing.
     */
    private fun applyThrottleGate(surface: Surface, nowMillis: Long) {
        if (!ThrottleSettings.enabled.value) return
        // Consent has never been granted, so starting would silently no-op.
        if (VpnService.prepare(this) != null) return

        val running = OrbisVpnService.isRunning.value

        if (surface.throttled) {
            normalSince = 0L
            if (!running) {
                // Real usage, not EMPTY: this is what makes the throttle adaptive.
                val delay = ThrottleEngine
                    .ruleFor(surface, UsageProfileHolder.profile.value)
                    .delayMillis
                OrbisVpnService.start(this, delay)
            }
            return
        }

        if (!running) return

        // Brief hysteresis: surfaces flicker as views recycle mid-scroll, and
        // tearing the tunnel down and back up on every flicker is worse than
        // leaving it up for another moment.
        if (normalSince == 0L) {
            normalSince = nowMillis
        } else if (nowMillis - normalSince >= STOP_GRACE_MILLIS) {
            normalSince = 0L
            OrbisVpnService.stop(this)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        SurfaceMonitor.reset()
    }

    private fun collectSignals(root: AccessibilityNodeInfo, packageName: String): SurfaceSignals {
        val readText = packageName in BrowserPackages.ALL

        val resourceIds = mutableSetOf<String>()
        val texts = mutableSetOf<String>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            // Visibility is the whole ballgame. Instagram keeps the Reels view
            // pager alive in the tree while Stories is on screen, so an unfiltered
            // walk finds clips_* ids during Stories and reports REELS - throttling
            // the one surface that must stay normal. Only what the user can
            // actually see counts.
            if (node.isVisibleToUser) {
                node.viewIdResourceName?.let { resourceIds += it }
                if (readText) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }

        return SurfaceSignals(
            packageName = packageName,
            resourceIds = resourceIds,
            texts = texts,
        )
    }

    private companion object {
        const val TAG = "OrbisSurface"

        /** Content-change events can fire many times a second while video plays. */
        const val MIN_INTERVAL_MILLIS = 250L

        /** Cap on nodes visited per pass, so a deep tree cannot stall the UI. */
        const val MAX_NODES = 600

        /** How long the surface must stay normal before the tunnel comes down. */
        const val STOP_GRACE_MILLIS = 3_000L
    }
}
