package com.orbis.app.usage;

import java.util.Objects;

/** One tracked app's measured time for a period. */
public final class AppUsage {

    private final TargetApp app;
    private final long durationMillis;

    public AppUsage(TargetApp app, long durationMillis) {
        this.app = app;
        this.durationMillis = durationMillis;
    }

    public TargetApp getApp() {
        return app;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppUsage)) {
            return false;
        }
        AppUsage that = (AppUsage) other;
        return durationMillis == that.durationMillis && app == that.app;
    }

    @Override
    public int hashCode() {
        return Objects.hash(app, durationMillis);
    }

    @Override
    public String toString() {
        return "AppUsage(" + app + ", " + durationMillis + "ms)";
    }
}
