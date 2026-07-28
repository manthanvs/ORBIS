package com.orbis.app.surface

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; observing=" + (serviceInfo?.packageNames?.joinToString() ?: "ALL"))
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
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
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
    }
}
