package com.orbis.app.data

import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageAccess
import com.orbis.app.usage.UsageStatsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * End-to-end for the write path: real [UsageStatsSource] against the live system,
 * through the repository, into Room.
 *
 * Uses an in-memory database so it never touches the user's real `orbis.db`.
 */
@RunWith(AndroidJUnit4::class)
class UsageRepositoryTest {

    private lateinit var database: OrbisDatabase
    private lateinit var repository: UsageRepository

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} GET_USAGE_STATS allow"
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }

        database = Room.inMemoryDatabaseBuilder(context, OrbisDatabase::class.java).build()
        repository = UsageRepository(
            source = UsageStatsSource.from(context),
            dao = database.usageLogDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun refreshTodayWritesOneRowPerTrackedApp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("usage access not granted", UsageAccess.isGranted(context))

        repository.refreshToday()

        val rows = database.usageLogDao()
            .observeForDate(LocalDate.now().toString())
            .first()

        // Every tracked app gets a row even at zero, so the dashboard can tell
        // "not used today" apart from "never measured".
        assertEquals(TargetApp.entries.size, rows.size)
        assertEquals(
            TargetApp.entries.map { it.packageName }.toSet(),
            rows.map { it.app }.toSet(),
        )
    }

    @Test
    fun refreshTodayIsIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("usage access not granted", UsageAccess.isGranted(context))

        // force, or the repository's cache would short-circuit the second and
        // third calls and the write path would only actually run once - which is
        // not what this test claims to prove.
        repository.refreshToday(force = true)
        repository.refreshToday(force = true)
        repository.refreshToday(force = true)

        val rows = database.usageLogDao()
            .observeForDate(LocalDate.now().toString())
            .first()

        // Three refreshes must not produce three sets of rows.
        assertEquals(TargetApp.entries.size, rows.size)
        assertTrue(rows.all { it.durationMillis >= 0L })
    }
}
