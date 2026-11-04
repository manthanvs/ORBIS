package com.orbis.app.usage;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Thin adapter over {@link UsageStatsManager}.
 *
 * <p>Its only job is to flatten the framework's cursor-style event stream into
 * plain {@link UsageEventRecord}s; all arithmetic lives in
 * {@link ForegroundTimeCalculator}.
 *
 * <p>Returns an empty list when usage access has not been granted - the
 * framework reports no events rather than throwing, so callers should check
 * {@link UsageAccess#isGranted} to tell "not permitted" from "genuinely no
 * usage".
 */
public final class UsageStatsSource {

    private final UsageStatsManager usageStatsManager;

    public UsageStatsSource(UsageStatsManager usageStatsManager) {
        this.usageStatsManager = usageStatsManager;
    }

    public static UsageStatsSource from(Context context) {
        return new UsageStatsSource(
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE));
    }

    public List<UsageEventRecord> eventsBetween(long startMillis, long endMillis) {
        return eventsBetween(startMillis, endMillis, null);
    }

    /**
     * @param packages when non-null, only these packages are materialised.
     *     {@code queryEvents} returns transitions for <b>every</b> app on the
     *     device, which on a busy phone is tens of thousands of events for a
     *     single day - and ORBIS reports on four of them. Filtering here rather
     *     than downstream is the difference between allocating four figures of
     *     records per refresh and allocating a handful.
     */
    public List<UsageEventRecord> eventsBetween(
            long startMillis, long endMillis, Set<String> packages) {

        List<UsageEventRecord> records = new ArrayList<>();
        UsageEvents events = usageStatsManager.queryEvents(startMillis, endMillis);
        UsageEvents.Event event = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);

            UsageEventType type;
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                type = UsageEventType.FOREGROUND;
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                type = UsageEventType.BACKGROUND;
            } else {
                continue;
            }

            String packageName = event.getPackageName();
            if (packageName == null) {
                continue;
            }
            if (packages != null && !packages.contains(packageName)) {
                continue;
            }

            records.add(new UsageEventRecord(
                    packageName, type, event.getTimeStamp(), event.getClassName()));
        }
        return records;
    }
}
