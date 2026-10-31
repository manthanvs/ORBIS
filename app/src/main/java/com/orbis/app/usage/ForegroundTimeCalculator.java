package com.orbis.app.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw event stream into per-package foreground time.
 *
 * <p>The framework reports transitions, not durations, so sessions have to be
 * paired up by hand. The cases that are easy to get wrong are covered by tests:
 *
 * <ul>
 *   <li>a session still open when the window closes (the app is in use <i>right
 *       now</i>)
 *   <li>a BACKGROUND with no matching FOREGROUND as the app's <i>first</i> event
 *       (it was already running when the window opened)
 *   <li>a repeated FOREGROUND with no BACKGROUND in between (the app never left)
 *   <li>several activities of one app resumed at once, paused in any order
 * </ul>
 *
 * <p>The last one is why sessions are paired <b>per activity</b>, not per
 * package. Measured on CPH2585: one Instagram client opens through two
 * activities together, and a YouTube client bounces links through a trampoline,
 * so an app routinely has two activities resumed and pauses them out of order.
 * One open session per package closed on the first pause and met the second with
 * no session at all - which was then "credited from the window start", i.e. from
 * midnight. A single pause at 17:55 added nearly eighteen hours, and the
 * dashboard reported 19 h of YouTube by half past seven in the evening. The
 * throttle scales with these minutes, so it also pinned that app at maximum
 * friction all day.
 *
 * <p>An app is foreground while <i>any</i> of its activities is resumed. A pause
 * with no matching resume is credited from the window start only when it is the
 * package's first event in the window; anywhere later it is noise and counts for
 * nothing. And no package is ever credited more than the window itself.
 *
 * <p>Deliberately free of Android types so it runs in {@code src/test} on the JVM.
 */
public final class ForegroundTimeCalculator {

    private ForegroundTimeCalculator() {
        // No instances: one pure function over an event stream.
    }

    /**
     * @return package name to total foreground milliseconds within the window.
     *     Packages with no measurable time are omitted.
     */
    public static Map<String, Long> totalsByPackage(
            List<UsageEventRecord> events, long windowStartMillis, long windowEndMillis) {

        if (windowEndMillis <= windowStartMillis) {
            return Collections.emptyMap();
        }

        Map<String, Long> totals = new HashMap<>();
        // Per package: which of its activities are resumed, and since when the
        // package as a whole has been in front.
        Map<String, Set<String>> resumed = new HashMap<>();
        Map<String, Long> since = new HashMap<>();
        Set<String> seen = new HashSet<>();

        // The framework already returns events in order, so in production this
        // sort finds nothing to do - it is here because the tests feed in
        // deliberately shuffled streams. Collections.sort is stable, so events
        // sharing a millisecond keep the framework's order.
        List<UsageEventRecord> ordered = new ArrayList<>(events.size());
        for (UsageEventRecord event : events) {
            long at = event.getTimestampMillis();
            if (at >= windowStartMillis && at <= windowEndMillis) {
                ordered.add(event);
            }
        }
        Collections.sort(ordered, (a, b) -> Long.compare(a.getTimestampMillis(), b.getTimestampMillis()));

        for (UsageEventRecord event : ordered) {
            String packageName = event.getPackageName();
            boolean firstEvent = seen.add(packageName);
            Set<String> live = resumed.computeIfAbsent(packageName, name -> new HashSet<>(2));
            String activity = event.getActivity() == null ? "" : event.getActivity();
            long at = event.getTimestampMillis();

            if (event.getType() == UsageEventType.FOREGROUND) {
                if (live.isEmpty()) {
                    since.put(packageName, at);
                }
                // A repeated resume of the same activity changes nothing: without
                // an intervening pause the app never left.
                live.add(activity);
            } else if (event.getType() == UsageEventType.BACKGROUND) {
                if (live.remove(activity)) {
                    if (live.isEmpty()) {
                        Long start = since.remove(packageName);
                        long from = start == null ? at : start;
                        addDuration(totals, packageName, at - from);
                    }
                } else if (firstEvent && live.isEmpty()) {
                    // Already in front when the window opened.
                    addDuration(totals, packageName, at - windowStartMillis);
                }
                // Unmatched mid-window: a trampoline or an activity whose resume
                // fell outside the stream. It carries no duration.
            }
        }

        // Whatever is still open ran until the window closed.
        for (Map.Entry<String, Long> entry : since.entrySet()) {
            Set<String> live = resumed.get(entry.getKey());
            if (live != null && !live.isEmpty()) {
                addDuration(totals, entry.getKey(), windowEndMillis - entry.getValue());
            }
        }

        // Nothing can have been in front for longer than the window lasted.
        long windowLength = windowEndMillis - windowStartMillis;
        totals.replaceAll((name, millis) -> Math.min(millis, windowLength));

        return totals;
    }

    private static void addDuration(Map<String, Long> totals, String packageName, long delta) {
        if (delta <= 0L) {
            return;
        }
        Long current = totals.get(packageName);
        totals.put(packageName, (current == null ? 0L : current) + delta);
    }
}
