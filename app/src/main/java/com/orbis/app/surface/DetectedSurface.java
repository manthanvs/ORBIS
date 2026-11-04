package com.orbis.app.surface;

import java.util.Objects;

/** What detection last decided, and when. */
public final class DetectedSurface {

    private final Surface surface;
    private final String packageName;
    private final long updatedAtMillis;

    public DetectedSurface(Surface surface, String packageName, long updatedAtMillis) {
        this.surface = surface;
        this.packageName = packageName;
        this.updatedAtMillis = updatedAtMillis;
    }

    /** Nothing detected yet. */
    public DetectedSurface() {
        this(Surface.NORMAL, null, 0L);
    }

    public Surface getSurface() {
        return surface;
    }

    public String getPackageName() {
        return packageName;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DetectedSurface)) {
            return false;
        }
        DetectedSurface that = (DetectedSurface) other;
        return updatedAtMillis == that.updatedAtMillis
                && surface == that.surface
                && Objects.equals(packageName, that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(surface, packageName, updatedAtMillis);
    }
}
