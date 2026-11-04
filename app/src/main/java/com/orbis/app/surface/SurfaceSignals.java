package com.orbis.app.surface;

import java.util.Collections;
import java.util.Set;

/**
 * A framework-free snapshot of the foreground screen.
 *
 * <p>The accessibility service fills this in from the node tree so all the
 * decision-making in {@link SurfaceDetector} stays pure and unit-testable.
 *
 * <p>Resource ids may be fully qualified
 * ({@code com.instagram.android:id/clips_tab}) or bare ({@code clips_tab}); the
 * detector normalises either form.
 */
public final class SurfaceSignals {

    private final String packageName;
    private final Set<String> resourceIds;
    private final Set<String> contentDescriptions;
    private final Set<String> texts;

    public SurfaceSignals(
            String packageName,
            Set<String> resourceIds,
            Set<String> contentDescriptions,
            Set<String> texts) {
        this.packageName = packageName;
        this.resourceIds = resourceIds == null ? Collections.emptySet() : resourceIds;
        this.contentDescriptions =
                contentDescriptions == null ? Collections.emptySet() : contentDescriptions;
        this.texts = texts == null ? Collections.emptySet() : texts;
    }

    public SurfaceSignals(String packageName, Set<String> resourceIds) {
        this(packageName, resourceIds, Collections.emptySet(), Collections.emptySet());
    }

    public String getPackageName() {
        return packageName;
    }

    public Set<String> getResourceIds() {
        return resourceIds;
    }

    public Set<String> getContentDescriptions() {
        return contentDescriptions;
    }

    /** Visible text, used only to spot short-video URLs in a browser address bar. */
    public Set<String> getTexts() {
        return texts;
    }
}
