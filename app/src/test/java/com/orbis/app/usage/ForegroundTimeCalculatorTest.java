package com.orbis.app.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class ForegroundTimeCalculatorTest {

    private static final long WINDOW_START = 1_000L;
    private static final long WINDOW_END = 101_000L;

    private static final String INSTAGRAM = "com.instagram.android";
    private static final String WHATSAPP = "com.whatsapp";

    private static UsageEventRecord foreground(String pkg, long at) {
        return new UsageEventRecord(pkg, UsageEventType.FOREGROUND, at);
    }

    private static UsageEventRecord background(String pkg, long at) {
        return new UsageEventRecord(pkg, UsageEventType.BACKGROUND, at);
    }

    private static UsageEventRecord resumed(String pkg, String activity, long at) {
        return new UsageEventRecord(pkg, UsageEventType.FOREGROUND, at, activity);
    }

    private static UsageEventRecord paused(String pkg, String activity, long at) {
        return new UsageEventRecord(pkg, UsageEventType.BACKGROUND, at, activity);
    }

    private static Map<String, Long> totals(UsageEventRecord... events) {
        return ForegroundTimeCalculator.totalsByPackage(
                Arrays.asList(events), WINDOW_START, WINDOW_END);
    }

    private static long millis(Map<String, Long> totals, String pkg) {
        Long value = totals.get(pkg);
        return value == null ? 0L : value;
    }

    @Test
    public void pairsASingleSession() {
        Map<String, Long> result =
                totals(foreground(INSTAGRAM, 10_000L), background(INSTAGRAM, 70_000L));

        assertEquals(60_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void sumsRepeatedSessionsForTheSameApp() {
        Map<String, Long> result =
                totals(
                        foreground(INSTAGRAM, 10_000L),
                        background(INSTAGRAM, 20_000L),
                        foreground(INSTAGRAM, 30_000L),
                        background(INSTAGRAM, 45_000L));

        assertEquals(25_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void sessionStillOpenAtWindowEndRunsUntilTheWindowCloses() {
        // The app is in the foreground right now - this is the common case when
        // refreshing while the user is actively scrolling.
        Map<String, Long> result = totals(foreground(INSTAGRAM, 41_000L));

        assertEquals(WINDOW_END - 41_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void backgroundWithNoForegroundCountsFromTheWindowStart() {
        // The app was already open when the window began, so its RESUMED event
        // falls outside the query range.
        Map<String, Long> result = totals(background(INSTAGRAM, 31_000L));

        assertEquals(30_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void repeatedForegroundWithoutBackgroundKeepsTheEarliest() {
        Map<String, Long> result =
                totals(
                        foreground(INSTAGRAM, 11_000L),
                        foreground(INSTAGRAM, 51_000L),
                        background(INSTAGRAM, 71_000L));

        assertEquals(60_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void tracksInterleavedAppsIndependently() {
        Map<String, Long> result =
                totals(
                        foreground(INSTAGRAM, 10_000L),
                        background(INSTAGRAM, 25_000L),
                        foreground(WHATSAPP, 25_000L),
                        background(WHATSAPP, 30_000L));

        assertEquals(15_000L, millis(result, INSTAGRAM));
        assertEquals(5_000L, millis(result, WHATSAPP));
    }

    @Test
    public void ignoresEventsOutsideTheWindow() {
        Map<String, Long> result =
                totals(
                        foreground(INSTAGRAM, WINDOW_START - 5_000L),
                        background(INSTAGRAM, WINDOW_END + 5_000L));

        // Both events are out of range, so nothing is left to pair.
        assertTrue(result.isEmpty());
    }

    @Test
    public void handlesOutOfOrderInput() {
        Map<String, Long> result =
                totals(background(INSTAGRAM, 70_000L), foreground(INSTAGRAM, 10_000L));

        assertEquals(60_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void omitsAppsWithNoMeasurableTime() {
        Map<String, Long> result =
                totals(foreground(INSTAGRAM, 10_000L), background(INSTAGRAM, 10_000L));

        assertTrue(result.isEmpty());
    }

    // --- Several activities of one app at once. Measured on CPH2585, where the
    // --- per-package pairing turned 28 minutes of YouTube into 19 hours.

    @Test
    public void twoActivitiesResumedTogetherCountOnceUntilTheLastOnePauses() {
        // InstaPro opens through LauncherActivity and PinLockActivity together.
        Map<String, Long> result =
                totals(
                        resumed(INSTAGRAM, "Launcher", 10_000L),
                        resumed(INSTAGRAM, "PinLock", 10_100L),
                        paused(INSTAGRAM, "Launcher", 10_200L),
                        paused(INSTAGRAM, "PinLock", 40_000L));

        assertEquals(30_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void anUnmatchedPauseMidWindowIsNotCreditedFromTheWindowStart() {
        // The exact failure: a trampoline's pause met no open session and was
        // credited from midnight.
        Map<String, Long> result =
                totals(
                        resumed(INSTAGRAM, "Main", 10_000L),
                        paused(INSTAGRAM, "Main", 20_000L),
                        paused(INSTAGRAM, "UrlTrampoline", 90_000L));

        assertEquals(10_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void anUnmatchedPauseAsTheFirstEventStillCountsFromTheWindowStart() {
        // The one case the old rule was for: open since before midnight.
        Map<String, Long> result = totals(paused(INSTAGRAM, "Main", 25_000L));

        assertEquals(24_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void activitiesPausedOutOfOrderStillCloseTheSessionCorrectly() {
        Map<String, Long> result =
                totals(
                        resumed(INSTAGRAM, "A", 10_000L),
                        resumed(INSTAGRAM, "B", 20_000L),
                        paused(INSTAGRAM, "A", 30_000L),
                        resumed(INSTAGRAM, "A", 35_000L),
                        paused(INSTAGRAM, "B", 40_000L),
                        paused(INSTAGRAM, "A", 50_000L));

        assertEquals(40_000L, millis(result, INSTAGRAM));
    }

    @Test
    public void noAppIsEverCreditedMoreThanTheWindow() {
        Map<String, Long> result =
                totals(
                        paused(INSTAGRAM, "Main", WINDOW_END),
                        resumed(INSTAGRAM, "Main", WINDOW_START));

        assertTrue(millis(result, INSTAGRAM) <= WINDOW_END - WINDOW_START);
    }

    @Test
    public void returnsEmptyForAnInvertedWindow() {
        Map<String, Long> result =
                ForegroundTimeCalculator.totalsByPackage(
                        Collections.singletonList(foreground(INSTAGRAM, 10_000L)),
                        WINDOW_END,
                        WINDOW_START);

        assertTrue(result.isEmpty());
    }
}
