package com.orbis.app.usage

/**
 * The apps ORBIS knows about.
 *
 * Usage is measured for every entry, but [throttled] decides which ones the
 * throttle engine is ever allowed to touch. Display names are hardcoded so the
 * app needs no package-visibility permissions to render its UI.
 *
 * [variants] are repackaged builds of the same app - modded clients installed
 * under their own package name. They matter more than they look: on the device
 * ORBIS was tested on, the user's Instagram *was* InstaPro and their YouTube
 * *was* Morphe, and the official apps were barely opened. Recognising only the
 * official package meant ORBIS saw almost none of the scrolling it exists for.
 *
 * A variant is folded into its app everywhere - usage is summed, detection uses
 * the app's rules, and routing sends the variant's own package into the tunnel.
 * Only add one whose resources carry the same view ids under its own package
 * name; check with `aapt2 dump resources` before adding it. A variant that does
 * not match simply fails safe to `NORMAL`.
 */
enum class TargetApp(
    val packageName: String,
    val displayName: String,
    val throttled: Boolean,
    val variants: Set<String> = emptySet(),
) {
    INSTAGRAM(
        "com.instagram.android",
        "Instagram",
        throttled = true,
        // Verified on CPH2585: the clips_* Reels ids, under com.instapro2.android.
        variants = setOf("com.instapro2.android"),
    ),
    YOUTUBE(
        "com.google.android.youtube",
        "YouTube",
        throttled = true,
        // Morphe verified on CPH2585: the reel_* Shorts ids, under its own
        // package. ReVanced is the build Morphe forked from and renames its
        // resources the same way.
        variants = setOf("app.morphe.android.youtube", "app.revanced.android.youtube"),
    ),
    SNAPCHAT("com.snapchat.android", "Snapchat", throttled = true),

    // WhatsApp is a communication tool, not passive-scroll content, and is never
    // throttled. This is a hard invariant - see CLAUDE.md. It is still measured,
    // so the dashboard can show it and the tests can prove it stays untouched.
    WHATSAPP("com.whatsapp", "WhatsApp", throttled = false),
    ;

    /** The official package and every variant of it. */
    val allPackages: Set<String> get() = setOf(packageName) + variants

    companion object {
        private val seeded: Map<String, TargetApp> =
            entries.flatMap { app -> app.allPackages.map { it to app } }.toMap()

        /**
         * Seeded with the known builds, extended at runtime by
         * [VariantDiscovery] with whatever else is on the device.
         *
         * Copy-on-write behind a @Volatile: this map is read on the
         * accessibility hot path several times a second and written perhaps
         * once a session, so readers must never take a lock.
         */
        @Volatile
        private var byPackage: Map<String, TargetApp> = seeded

        /** Resolves variants too: InstaPro is Instagram. */
        fun fromPackage(packageName: String): TargetApp? = byPackage[packageName]

        /** Every package ORBIS measures, variants included; filters the event stream. */
        val packageNames: Set<String> get() = byPackage.keys

        /** Records a build of [app] found on this device. */
        @Synchronized
        fun registerVariant(packageName: String, app: TargetApp) {
            if (byPackage[packageName] == app) return
            byPackage = byPackage + (packageName to app)
        }

        /** For tests, and for a device whose apps changed under us. */
        @Synchronized
        fun forgetDiscoveredVariants() {
            byPackage = seeded
        }

        /** The only apps the throttle engine may act on. */
        val throttleable: List<TargetApp> = entries.filter { it.throttled }

        /** Every build of those apps known right now, discovered ones included. */
        val throttleablePackages: Set<String>
            get() = byPackage.filterValues { it.throttled }.keys

        fun isThrottleable(packageName: String): Boolean =
            fromPackage(packageName)?.throttled == true
    }
}
