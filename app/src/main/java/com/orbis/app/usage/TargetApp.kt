package com.orbis.app.usage

/**
 * The apps ORBIS knows about.
 *
 * Usage is measured for every entry, but [throttled] decides which ones the
 * throttle engine is ever allowed to touch. Display names are hardcoded so the
 * app needs no package-visibility permissions to render its UI.
 */
enum class TargetApp(
    val packageName: String,
    val displayName: String,
    val throttled: Boolean,
) {
    INSTAGRAM("com.instagram.android", "Instagram", throttled = true),
    YOUTUBE("com.google.android.youtube", "YouTube", throttled = true),
    SNAPCHAT("com.snapchat.android", "Snapchat", throttled = true),

    // WhatsApp is a communication tool, not passive-scroll content, and is never
    // throttled. This is a hard invariant - see CLAUDE.md. It is still measured,
    // so the dashboard can show it and the tests can prove it stays untouched.
    WHATSAPP("com.whatsapp", "WhatsApp", throttled = false),
    ;

    companion object {
        private val byPackage: Map<String, TargetApp> = entries.associateBy { it.packageName }

        fun fromPackage(packageName: String): TargetApp? = byPackage[packageName]

        /** Every package ORBIS measures; used to filter the raw event stream. */
        val packageNames: Set<String> = byPackage.keys

        /** The only apps the throttle engine may act on. */
        val throttleable: List<TargetApp> = entries.filter { it.throttled }

        fun isThrottleable(packageName: String): Boolean =
            fromPackage(packageName)?.throttled == true
    }
}
