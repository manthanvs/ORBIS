package com.orbis.app.usage

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Exercises the real pipeline: framework events -> [UsageEventRecord] ->
 * [ForegroundTimeCalculator].
 *
 * The unit tests prove the arithmetic against synthetic events; this proves the
 * adapter actually reads the system and that the event types we map are the ones
 * the platform really emits.
 *
 * Grants usage access to itself via shell. Doing it here rather than with an adb
 * command beforehand matters: the instrumented-test task reinstalls the APK,
 * and reinstalling clears any app-op set earlier.
 */
@RunWith(AndroidJUnit4::class)
class UsageStatsSourceTest {

    @Before
    fun grantUsageAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "appops set $packageName GET_USAGE_STATS allow"
        )
        // The command runs asynchronously; draining the pipe waits for it to finish.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test
    fun readsForegroundTimeForTheAppUnderTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "usage access not granted - run: adb shell appops set ${context.packageName} GET_USAGE_STATS allow",
            UsageAccess.isGranted(context),
        )

        val now = System.currentTimeMillis()
        val start = now - TimeUnit.HOURS.toMillis(1)

        val events = UsageStatsSource.from(context).eventsBetween(start, now)
        assertTrue(
            "expected the system to report usage events in the last hour",
            events.isNotEmpty(),
        )

        val totals = ForegroundTimeCalculator.totalsByPackage(events, start, now)

        // Instrumentation runs inside the app under test, so it is in the
        // foreground right now and must show measurable time.
        val ownTime = totals[context.packageName] ?: 0L
        assertTrue(
            "expected non-zero foreground time for ${context.packageName}, got: $totals",
            ownTime > 0L,
        )
    }

    @Test
    fun reportsNothingForAnInvertedWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()

        val events = UsageStatsSource.from(context).eventsBetween(now, now - 60_000L)

        assertTrue("a backwards window cannot contain events", events.isEmpty())
    }
}
