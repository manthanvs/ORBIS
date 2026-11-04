package com.orbis.app.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tracked apps ranked by time spent, heaviest first.
 *
 * <p>Drives the dashboard and the throttle intensity.
 */
public final class UsageProfile {

    public static final UsageProfile EMPTY = new UsageProfile(Collections.emptyList());

    private final List<AppUsage> entries;

    // Computed once in the constructor. Both are read from the accessibility
    // service's hot path and from composables, where recomputing per read would
    // cost on every recomposition.
    private final long totalMillis;
    private final Map<TargetApp, Long> byApp;

    public UsageProfile(List<AppUsage> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));

        long total = 0L;
        Map<TargetApp, Long> index = new HashMap<>();
        for (AppUsage entry : this.entries) {
            total += entry.getDurationMillis();
            index.put(entry.getApp(), entry.getDurationMillis());
        }
        this.totalMillis = total;
        this.byApp = Collections.unmodifiableMap(index);
    }

    public List<AppUsage> getEntries() {
        return entries;
    }

    public long getTotalMillis() {
        return totalMillis;
    }

    public long durationOf(TargetApp app) {
        Long millis = byApp.get(app);
        return millis == null ? 0L : millis;
    }

    /** This app's share of today's tracked time, 0f..1f. */
    public float shareOf(TargetApp app) {
        if (totalMillis <= 0L) {
            return 0f;
        }
        return (float) durationOf(app) / totalMillis;
    }

    /**
     * The heaviest app the throttle engine is actually allowed to act on.
     *
     * <p>Skips protected apps, so a user whose top app is WhatsApp gets whatever
     * is beneath it - never WhatsApp itself.
     */
    public AppUsage getHeaviestThrottleable() {
        for (AppUsage entry : entries) {
            if (entry.getApp().getThrottled() && entry.getDurationMillis() > 0L) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Unknown packages are dropped - ORBIS only reports on apps it targets.
     *
     * <p>Variants are summed into their app, so an hour split between YouTube and
     * a repackaged client is one hour of YouTube - which is what the throttle
     * should scale by, and what the user would say they spent.
     */
    public static UsageProfile from(Map<String, Long> totalsByPackage) {
        Map<TargetApp, Long> summed = new LinkedHashMap<>();
        for (Map.Entry<String, Long> total : totalsByPackage.entrySet()) {
            TargetApp app = TargetApp.fromPackage(total.getKey());
            if (app == null) {
                continue;
            }
            Long running = summed.get(app);
            summed.put(app, (running == null ? 0L : running) + total.getValue());
        }

        List<AppUsage> entries = new ArrayList<>(summed.size());
        for (Map.Entry<TargetApp, Long> entry : summed.entrySet()) {
            entries.add(new AppUsage(entry.getKey(), entry.getValue()));
        }
        // Name is the tie-break purely so ordering is deterministic in tests.
        Collections.sort(entries, (a, b) -> {
            int byDuration = Long.compare(b.getDurationMillis(), a.getDurationMillis());
            if (byDuration != 0) {
                return byDuration;
            }
            return a.getApp().getDisplayName().compareTo(b.getApp().getDisplayName());
        });

        return new UsageProfile(entries);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UsageProfile)) {
            return false;
        }
        return entries.equals(((UsageProfile) other).entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries);
    }

    @Override
    public String toString() {
        return "UsageProfile(" + entries + ")";
    }
}
