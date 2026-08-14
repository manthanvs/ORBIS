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

    /**
     * The address-bar view id per browser, so the URL can be read with a single
     * targeted lookup.
     *
     * Reading text from the whole tree instead means stringifying every visible
     * text node on the page - hundreds of them, several times a second. These ids
     * are best-effort: a browser that has renamed its toolbar, or has it hidden
     * during fullscreen video, simply falls back to the bounded tree walk.
     */
    val URL_BAR_IDS: Map<String, String> = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "com.sec.android.app.sbrowser" to
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.duckduckgo.mobile.android" to
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
    )

    val ALL: Set<String> = URL_BAR_IDS.keys
}
