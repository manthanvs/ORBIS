package com.orbis.app.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.orbis.app.surface.BrowserPackages
import com.orbis.app.surface.SurfaceDetector

/**
 * Finds the builds of Instagram, YouTube and Snapchat actually installed - mods
 * included - instead of trusting a hardcoded list.
 *
 * A list of known mods is always out of date: the test phone alone ran InstaPro
 * and Morphe, and there are dozens more. So ORBIS asks the device two questions
 * about each candidate, and only a "yes" to both counts:
 *
 * 1. **Does it handle the app's own links?** Everything that opens
 *    `instagram.com/reels/` is either Instagram, a build of it, or a browser.
 * 2. **Does it carry the app's screen ids?** A mod keeps the original resource
 *    names under its own package, so `clips_viewer_view_pager` resolves inside
 *    it. This is the question that matters: it is the same signal detection
 *    itself uses, so anything registered here is something ORBIS can actually
 *    recognise on screen - and anything else (Threads, a link shortener, a
 *    browser) is dropped rather than watched.
 *
 * Browsers are excluded by name; an unknown one fails the id check anyway.
 *
 * Needs the `<queries>` block in the manifest - without it Android 11+ answers
 * question 1 with an empty list and hides every other app.
 *
 * **Blind spot:** an app the user has *hidden* (ColorOS's hidden apps) looks
 * uninstalled to `queryIntentActivities`, so it cannot be discovered this way.
 * InstaPro on the test phone is hidden, which is why the known builds stay
 * seeded in [TargetApp.variants] as well.
 */
object VariantDiscovery {

    private const val TAG = "OrbisVariants"

    /** A link only this app's builds claim, per app. */
    private val SITES: Map<TargetApp, String> = mapOf(
        TargetApp.INSTAGRAM to "https://www.instagram.com/reels/",
        TargetApp.YOUTUBE to "https://www.youtube.com/shorts/",
        TargetApp.SNAPCHAT to "https://www.snapchat.com/spotlight",
    )

    /**
     * Registers every build found, and returns the full set of packages ORBIS
     * should now observe and may route.
     */
    fun run(context: Context): Set<String> {
        val packageManager = context.packageManager
        // Browsers open these links too, and are handled by URL, not by ids.
        // An unknown browser would fail the id check below anyway.
        val browsers = BrowserPackages.ALL

        SITES.forEach { (app, url) ->
            val marker = SurfaceDetector.markerIdFor(app) ?: return@forEach
            handlersOf(packageManager, url)
                .filter { it !in browsers && TargetApp.fromPackage(it) != app }
                .filter { carriesId(packageManager, it, marker) }
                .forEach { packageName ->
                    Log.i(TAG, "$packageName looks like ${app.displayName}")
                    TargetApp.registerVariant(packageName, app)
                }
        }

        return TargetApp.packageNames
    }

    private fun handlersOf(packageManager: PackageManager, url: String): Set<String> =
        runCatching {
            packageManager
                .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url)), 0)
                .map { it.activityInfo.packageName }
                .toSet()
        }.getOrDefault(emptySet())

    /** Whether [packageName] defines [id] as one of its own view ids. */
    private fun carriesId(
        packageManager: PackageManager,
        packageName: String,
        id: String,
    ): Boolean = runCatching {
        packageManager
            .getResourcesForApplication(packageName)
            .getIdentifier(id, "id", packageName) != 0
    }.getOrDefault(false)
}
