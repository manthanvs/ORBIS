package com.orbis.app.surface

/**
 * A framework-free snapshot of the foreground screen.
 *
 * `OrbisAccessibilityService` fills this in from the node tree so all the
 * decision-making in [SurfaceDetector] stays pure and unit-testable.
 *
 * [resourceIds] may be fully qualified (`com.instagram.android:id/clips_tab`) or
 * bare (`clips_tab`); the detector normalises either form.
 */
data class SurfaceSignals(
    val packageName: String,
    val resourceIds: Set<String> = emptySet(),
    val contentDescriptions: Set<String> = emptySet(),
    /** Visible text, used only to spot short-video URLs in a browser address bar. */
    val texts: Set<String> = emptySet(),
)

/**
 * Browsers ORBIS inspects for short-video URLs.
 *
 * Deliberately an allow-list. The accessibility service is also scoped to these
 * packages in its config, so ORBIS never receives events from anything else.
 */
object BrowserPackages {
    val ALL: Set<String> = setOf(
        "com.android.chrome",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
    )
}
