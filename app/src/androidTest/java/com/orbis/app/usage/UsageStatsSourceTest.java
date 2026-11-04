package com.orbis.app.usage;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the real pipeline: framework events -> {@link UsageEventRecord} ->
 * {@link ForegroundTimeCalculator}.
 *
 * <p>The unit tests prove the arithmetic against synthetic events; this proves the
 * adapter actually reads the system and that the event types ORBIS maps are the
 * ones the platform really emits.
 *
 * <p>Grants usage access to itself via shell. Doing it here rather than with an
 * adb command beforehand matters: the instrumented-test task reinstalls the APK,
 * and reinstalling clears any app-op set earlier.
 */
@RunWith(AndroidJUnit4.class)
public class UsageStatsSourceTest {

    @Before
    public void grantUsageAccess() throws IOException {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String packageName = instrumentation.getTargetContext().getPackageName();
        ParcelFileDescriptor descriptor =
                instrumentation
                        .getUiAutomation()
                        .executeShellCommand(
                                "appops set " + packageName + " GET_USAGE_STATS allow");
        // The command runs asynchronously; draining the pipe waits for it to finish.
        try (InputStream drain = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (drain.read() >= 0) {
                // Discarded; only the wait matters.
            }
        }
    }

    @Test
    public void readsForegroundTimeForTheAppUnderTest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(
                "usage access not granted - run: adb shell appops set "
                        + context.getPackageName()
                        + " GET_USAGE_STATS allow",
                UsageAccess.isGranted(context));

        long now = System.currentTimeMillis();
        long start = now - TimeUnit.HOURS.toMillis(1);

        List<UsageEventRecord> events = UsageStatsSource.from(context).eventsBetween(start, now);
        assertTrue(
                "expected the system to report usage events in the last hour",
                !events.isEmpty());

        Map<String, Long> totals =
                ForegroundTimeCalculator.totalsByPackage(events, start, now);

        // Instrumentation runs inside the app under test, so it is in the
        // foreground right now and must show measurable time.
        Long ownTime = totals.get(context.getPackageName());
        assertTrue(
                "expected non-zero foreground time for "
                        + context.getPackageName()
                        + ", got: "
                        + totals,
                ownTime != null && ownTime > 0L);
    }

    @Test
    public void reportsNothingForAnInvertedWindow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long now = System.currentTimeMillis();

        List<UsageEventRecord> events =
                UsageStatsSource.from(context).eventsBetween(now, now - 60_000L);

        assertTrue("a backwards window cannot contain events", events.isEmpty());
    }
}
