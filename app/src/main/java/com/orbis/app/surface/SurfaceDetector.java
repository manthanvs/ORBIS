package com.orbis.app.surface;

import com.orbis.app.usage.TargetApp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides which {@link Surface} the user is on, from signals captured off the
 * accessibility tree. Pure, so every rule below is unit-testable.
 *
 * <p>Two rules make this safe, and both are load-bearing:
 *
 * <ol>
 *   <li><b>Scope by package first, always.</b> {@code reel_*} means <i>Stories</i>
 *       in Instagram but <i>Shorts</i> in YouTube. A cross-app substring match on
 *       "reel" would throttle Instagram Stories - exactly the surface that must
 *       stay normal.
 *   <li><b>Match ids exactly, never by substring.</b> Snapchat's navigation bar
 *       carries {@code ngs_spotlight_icon_container} on every screen; a
 *       "contains spotlight" test would report Spotlight for the whole app.
 * </ol>
 *
 * <p>Unknown input falls through to {@link Surface#NORMAL}: never throttle what
 * you have not positively identified.
 */
public final class SurfaceDetector {

    /** Instagram calls Reels "clips" internally. {@code reel_*} is Stories - leave it alone. */
    private static final Set<String> INSTAGRAM_REELS_IDS = setOf(
            "clips_viewer_view_pager",
            "clips_viewer_container",
            "clips_viewer_video_layout",
            "clips_video_container",
            "clips_media_component",
            "root_clips_layout");

    /** YouTube calls Shorts "reel" internally - the opposite of Instagram. */
    private static final Set<String> YOUTUBE_SHORTS_IDS = setOf(
            "reel_watch_fragment_root",
            "reel_watch_player",
            "reel_player_page_container",
            "reel_player_overlay_root",
            "reel_recycler");

    /** The content container only, not {@code ngs_spotlight_icon_container} (the tab icon). */
    private static final Set<String> SNAPCHAT_SPOTLIGHT_IDS = setOf("spotlight_container");

    private static final Pattern SHORT_VIDEO_URL = Pattern.compile(
            "(?:youtube\\.com|youtu\\.be)/shorts"
                    + "|instagram\\.com/reels?/"
                    + "|snapchat\\.com/spotlight",
            Pattern.CASE_INSENSITIVE);

    /**
     * Qualified with each package's <i>own</i> name. A repackaged client carries
     * the same ids under its own package - one YouTube client's Shorts player is
     * {@code app.morphe.android.youtube:id/reel_watch_player} - so asking the
     * framework for the official name would never find it.
     *
     * <p>Cached rather than precomputed, because builds are now discovered at
     * runtime and the service asks on every evaluation.
     */
    private static final Map<String, List<String>> QUALIFIED = new ConcurrentHashMap<>();

    private SurfaceDetector() {
        // No instances: the rules are stateless.
    }

    /**
     * The fully-qualified view ids worth looking for in this package, or null
     * when the package is not identified by ids at all (browsers are identified
     * by the URL text instead).
     *
     * <p>Lets the accessibility service ask the framework for these ids directly
     * instead of walking the whole node tree and reporting back everything it
     * finds. The rules themselves stay here, so there is still one place that
     * decides what counts as a throttled surface.
     */
    public static List<String> candidateIdsFor(String packageName) {
        Set<String> ids = idsFor(TargetApp.Companion.fromPackage(packageName));
        if (ids == null) {
            return null;
        }
        return QUALIFIED.computeIfAbsent(packageName, name -> qualifiedFor(ids, name));
    }

    /**
     * One id per app, enough to recognise a build of it by its resources alone.
     * Variant discovery asks this of every candidate.
     */
    public static String markerIdFor(TargetApp app) {
        Set<String> ids = idsFor(app);
        if (ids == null) {
            return null;
        }
        return ids.iterator().next();
    }

    /** True when the text contains a short-video URL. Used for browser address bars. */
    public static boolean isShortVideoUrl(String text) {
        return SHORT_VIDEO_URL.matcher(text).find();
    }

    /**
     * Scoped by <i>app</i>, which the package resolves to - so a repackaged
     * Instagram gets Instagram's rules, and {@code reel_*} still means Stories
     * there, not Shorts.
     */
    public static Surface detect(SurfaceSignals signals) {
        if (BrowserPackages.INSTANCE.getALL().contains(signals.getPackageName())) {
            for (String text : signals.getTexts()) {
                if (isShortVideoUrl(text)) {
                    return Surface.BROWSER_SHORT_VIDEO;
                }
            }
            return Surface.NORMAL;
        }

        TargetApp app = TargetApp.Companion.fromPackage(signals.getPackageName());
        if (app == null) {
            // WhatsApp and every untracked app.
            return Surface.NORMAL;
        }

        switch (app) {
            case INSTAGRAM:
                return toSurface(matches(signals, INSTAGRAM_REELS_IDS), Surface.REELS);
            case YOUTUBE:
                return toSurface(matches(signals, YOUTUBE_SHORTS_IDS), Surface.SHORTS);
            case SNAPCHAT:
                return toSurface(matches(signals, SNAPCHAT_SPOTLIGHT_IDS), Surface.SPOTLIGHT);
            case WHATSAPP:
            default:
                return Surface.NORMAL;
        }
    }

    /** The rules for an app, or null for one that is never throttled. */
    private static Set<String> idsFor(TargetApp app) {
        if (app == null) {
            return null;
        }
        switch (app) {
            case INSTAGRAM:
                return INSTAGRAM_REELS_IDS;
            case YOUTUBE:
                return YOUTUBE_SHORTS_IDS;
            case SNAPCHAT:
                return SNAPCHAT_SPOTLIGHT_IDS;
            case WHATSAPP:
            default:
                return null;
        }
    }

    private static List<String> qualifiedFor(Set<String> ids, String packageName) {
        List<String> qualified = new ArrayList<>(ids.size());
        for (String id : ids) {
            qualified.add(packageName + ":id/" + id);
        }
        return Collections.unmodifiableList(qualified);
    }

    private static boolean matches(SurfaceSignals signals, Set<String> ids) {
        for (String resourceId : signals.getResourceIds()) {
            if (ids.contains(simpleId(resourceId))) {
                return true;
            }
        }
        return false;
    }

    /** {@code com.instagram.android:id/clips_tab} -> {@code clips_tab}; bare names pass through. */
    private static String simpleId(String resourceId) {
        int marker = resourceId.lastIndexOf(":id/");
        return marker < 0 ? resourceId : resourceId.substring(marker + ":id/".length());
    }

    private static Surface toSurface(boolean matched, Surface whenTrue) {
        return matched ? whenTrue : Surface.NORMAL;
    }

    /** Insertion-ordered, so {@link #markerIdFor} returns a stable first id. */
    private static Set<String> setOf(String... ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(ids)));
    }
}
