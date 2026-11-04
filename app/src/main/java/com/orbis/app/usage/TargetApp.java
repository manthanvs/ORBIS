package com.orbis.app.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The apps ORBIS knows about.
 *
 * <p>Usage is measured for every entry, but whether an app is throttled decides
 * which ones the throttle engine is ever allowed to touch. Display names are
 * hardcoded so the app needs no package-visibility permissions to render its UI.
 *
 * <p>Variants are repackaged builds of the same app - modded clients installed
 * under their own package name. They matter more than they look: on the device
 * ORBIS was tested on, the user's Instagram <i>was</i> a repackaged client and
 * their YouTube <i>was</i> another, and the official apps were barely opened.
 * Recognising only the official package meant ORBIS saw almost none of the
 * scrolling it exists for.
 *
 * <p>A variant is folded into its app everywhere - usage is summed, detection
 * uses the app's rules, and routing sends the variant's own package into the
 * tunnel. Only add one whose resources carry the same view ids under its own
 * package name; check with {@code aapt2 dump resources} before adding it. A
 * variant that does not match simply fails safe to NORMAL.
 */
public enum TargetApp {

    INSTAGRAM(
            "com.instagram.android",
            "Instagram",
            true,
            // Verified on CPH2585: the clips_* Reels ids, under its own package.
            "com.instapro2.android"),

    YOUTUBE(
            "com.google.android.youtube",
            "YouTube",
            true,
            // Both verified on CPH2585: the reel_* Shorts ids, under their own
            // packages. The second is the build the first was forked from and it
            // renames its resources the same way.
            "app.morphe.android.youtube",
            "app.revanced.android.youtube"),

    SNAPCHAT("com.snapchat.android", "Snapchat", true),

    // WhatsApp is a communication tool, not passive-scroll content, and is never
    // throttled. This is a hard invariant - see CLAUDE.md. It is still measured,
    // so the dashboard can show it and the tests can prove it stays untouched.
    WHATSAPP("com.whatsapp", "WhatsApp", false);

    private final String packageName;
    private final String displayName;
    private final boolean throttled;
    private final Set<String> variants;

    TargetApp(String packageName, String displayName, boolean throttled, String... variants) {
        this.packageName = packageName;
        this.displayName = displayName;
        this.throttled = throttled;
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, variants);
        this.variants = Collections.unmodifiableSet(set);
    }

    public String getPackageName() {
        return packageName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean getThrottled() {
        return throttled;
    }

    public Set<String> getVariants() {
        return variants;
    }

    /** The official package and every variant of it. */
    public Set<String> getAllPackages() {
        Set<String> all = new LinkedHashSet<>();
        all.add(packageName);
        all.addAll(variants);
        return Collections.unmodifiableSet(all);
    }

    private static final Map<String, TargetApp> SEEDED = seed();

    /**
     * Seeded with the known builds, extended at runtime by variant discovery
     * with whatever else is on the device.
     *
     * <p>Copy-on-write behind a volatile: this map is read on the accessibility
     * hot path several times a second and written perhaps once a session, so
     * readers must never take a lock.
     */
    private static volatile Map<String, TargetApp> byPackage = SEEDED;

    /** The only apps the throttle engine may act on. */
    private static final List<TargetApp> THROTTLEABLE = throttleableApps();

    private static Map<String, TargetApp> seed() {
        Map<String, TargetApp> map = new HashMap<>();
        for (TargetApp app : values()) {
            for (String name : app.getAllPackages()) {
                map.put(name, app);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<TargetApp> throttleableApps() {
        List<TargetApp> apps = new ArrayList<>();
        for (TargetApp app : values()) {
            if (app.throttled) {
                apps.add(app);
            }
        }
        return Collections.unmodifiableList(apps);
    }

    /** Resolves variants too: a repackaged Instagram is Instagram. */
    public static TargetApp fromPackage(String packageName) {
        return byPackage.get(packageName);
    }

    /** Every package ORBIS measures, variants included; filters the event stream. */
    public static Set<String> getPackageNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byPackage.keySet()));
    }

    public static List<TargetApp> getThrottleable() {
        return THROTTLEABLE;
    }

    /** Every build of those apps known right now, discovered ones included. */
    public static Set<String> getThrottleablePackages() {
        Set<String> packages = new HashSet<>();
        for (Map.Entry<String, TargetApp> entry : byPackage.entrySet()) {
            if (entry.getValue().throttled) {
                packages.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(packages);
    }

    public static boolean isThrottleable(String packageName) {
        TargetApp app = fromPackage(packageName);
        return app != null && app.throttled;
    }

    /** Records a build of this app found on this device. */
    public static synchronized void registerVariant(String packageName, TargetApp app) {
        if (byPackage.get(packageName) == app) {
            return;
        }
        Map<String, TargetApp> extended = new HashMap<>(byPackage);
        extended.put(packageName, app);
        byPackage = Collections.unmodifiableMap(extended);
    }

    /** For tests, and for a device whose apps changed under us. */
    public static synchronized void forgetDiscoveredVariants() {
        byPackage = SEEDED;
    }
}
