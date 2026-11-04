package com.orbis.app.surface;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Browsers ORBIS inspects for short-video URLs.
 *
 * <p>Deliberately an allow-list. The accessibility service is also scoped to
 * these packages in its config, so ORBIS never receives events from anything
 * else.
 */
public final class BrowserPackages {

    /**
     * The address-bar view id per browser, so the URL can be read with a single
     * targeted lookup.
     *
     * <p>Reading text from the whole tree instead means stringifying every
     * visible text node on the page - hundreds of them, several times a second.
     * These ids are best-effort: a browser that has renamed its toolbar, or has
     * it hidden during fullscreen video, simply falls back to the bounded tree
     * walk.
     */
    public static final Map<String, String> URL_BAR_IDS = urlBarIds();

    public static final Set<String> ALL = Collections.unmodifiableSet(URL_BAR_IDS.keySet());

    private BrowserPackages() {
        // No instances: a static allow-list.
    }

    private static Map<String, String> urlBarIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("com.android.chrome", "com.android.chrome:id/url_bar");
        ids.put("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar");
        ids.put("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view");
        ids.put("com.brave.browser", "com.brave.browser:id/url_bar");
        ids.put("com.opera.browser", "com.opera.browser:id/url_field");
        ids.put(
                "com.sec.android.app.sbrowser",
                "com.sec.android.app.sbrowser:id/location_bar_edit_text");
        ids.put(
                "com.duckduckgo.mobile.android",
                "com.duckduckgo.mobile.android:id/omnibarTextInput");
        return Collections.unmodifiableMap(ids);
    }
}
