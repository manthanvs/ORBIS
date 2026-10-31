package com.orbis.app.usage;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import com.orbis.app.surface.BrowserPackages;
import com.orbis.app.surface.SurfaceDetector;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the builds of Instagram, YouTube and Snapchat actually installed - mods
 * included - instead of trusting a hardcoded list.
 *
 * <p>A list of known mods is always out of date: the test phone alone ran two of
 * them, and there are dozens more. So ORBIS asks the device two questions about
 * each candidate, and only a "yes" to both counts:
 *
 * <ol>
 *   <li><b>Does it handle the app's own links?</b> Everything that opens
 *       {@code instagram.com/reels/} is either Instagram, a build of it, or a
 *       browser.
 *   <li><b>Does it carry the app's screen ids?</b> A mod keeps the original
 *       resource names under its own package, so {@code clips_viewer_view_pager}
 *       resolves inside it. This is the question that matters: it is the same
 *       signal detection itself uses, so anything registered here is something
 *       ORBIS can actually recognise on screen - and anything else (a companion
 *       app, a link shortener, a browser) is dropped rather than watched.
 * </ol>
 *
 * <p>Browsers are excluded by name; an unknown one fails the id check anyway.
 *
 * <p>Needs the {@code <queries>} block in the manifest - without it Android 11+
 * answers question 1 with an empty list and hides every other app.
 *
 * <p><b>Blind spot:</b> an app the user has <i>hidden</i> (ColorOS's hidden
 * apps) looks uninstalled to {@code queryIntentActivities}, so it cannot be
 * discovered this way. One of the test phone's clients is hidden, which is why
 * the known builds stay seeded in {@link TargetApp} as well.
 */
public final class VariantDiscovery {

    private static final String TAG = "OrbisVariants";

    /** A link only this app's builds claim, per app. */
    private static final Map<TargetApp, String> SITES = sites();

    private VariantDiscovery() {
        // No instances: one pass over the installed apps.
    }

    private static Map<TargetApp, String> sites() {
        Map<TargetApp, String> map = new LinkedHashMap<>();
        map.put(TargetApp.INSTAGRAM, "https://www.instagram.com/reels/");
        map.put(TargetApp.YOUTUBE, "https://www.youtube.com/shorts/");
        map.put(TargetApp.SNAPCHAT, "https://www.snapchat.com/spotlight");
        return map;
    }

    /**
     * Registers every build found, and returns the full set of packages ORBIS
     * should now observe and may route.
     */
    public static Set<String> run(Context context) {
        PackageManager packageManager = context.getPackageManager();
        // Browsers open these links too, and are handled by URL, not by ids.
        // An unknown browser would fail the id check below anyway.
        Set<String> browsers = BrowserPackages.INSTANCE.getALL();

        for (Map.Entry<TargetApp, String> site : SITES.entrySet()) {
            TargetApp app = site.getKey();
            String marker = SurfaceDetector.markerIdFor(app);
            if (marker == null) {
                continue;
            }

            for (String candidate : handlersOf(packageManager, site.getValue())) {
                if (browsers.contains(candidate) || TargetApp.fromPackage(candidate) == app) {
                    continue;
                }
                if (!carriesId(packageManager, candidate, marker)) {
                    continue;
                }
                Log.i(TAG, candidate + " looks like " + app.getDisplayName());
                TargetApp.registerVariant(candidate, app);
            }
        }

        return TargetApp.getPackageNames();
    }

    private static Set<String> handlersOf(PackageManager packageManager, String url) {
        Set<String> packages = new HashSet<>();
        try {
            List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(url)), 0);
            for (ResolveInfo handler : handlers) {
                packages.add(handler.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
            // A device that refuses the query tells us nothing; discovery is
            // best-effort and the seeded builds still work.
        }
        return packages;
    }

    /** Whether this package defines the id as one of its own view ids. */
    private static boolean carriesId(
            PackageManager packageManager, String packageName, String id) {
        try {
            return packageManager
                            .getResourcesForApplication(packageName)
                            .getIdentifier(id, "id", packageName)
                    != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
