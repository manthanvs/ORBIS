package com.orbis.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.orbis.app.LiveDataValue;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.usage.UsageAccess;
import com.orbis.app.usage.UsageStatsSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * End-to-end for the write path: a real {@link UsageStatsSource} against the live
 * system, through the repository, into Room.
 *
 * <p>Uses an in-memory database so it never touches the user's real
 * {@code orbis.db}.
 */
@RunWith(AndroidJUnit4.class)
public class UsageRepositoryTest {

    private OrbisDatabase database;
    private UsageRepository repository;

    @Before
    public void setUp() throws IOException {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();

        // The instrumented-test task reinstalls the APK, and reinstalling clears
        // any app-op set beforehand - so it is granted here.
        ParcelFileDescriptor descriptor =
                instrumentation
                        .getUiAutomation()
                        .executeShellCommand(
                                "appops set "
                                        + context.getPackageName()
                                        + " GET_USAGE_STATS allow");
        try (InputStream drain = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (drain.read() >= 0) {
                // The command runs asynchronously; draining waits for it.
            }
        }

        database = Room.inMemoryDatabaseBuilder(context, OrbisDatabase.class).build();
        repository = new UsageRepository(UsageStatsSource.from(context), database.usageLogDao());
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void refreshTodayWritesOneRowPerTrackedApp() throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("usage access not granted", UsageAccess.isGranted(context));

        repository.refreshToday();

        List<UsageLog> rows =
                LiveDataValue.of(
                        database.usageLogDao().observeForDate(LocalDate.now().toString()));

        Set<String> written = new HashSet<>();
        for (UsageLog row : rows) {
            written.add(row.app);
        }
        Set<String> expected = new HashSet<>();
        for (TargetApp app : TargetApp.values()) {
            expected.add(app.getPackageName());
        }

        // Every tracked app gets a row even at zero, so the dashboard can tell
        // "not used today" apart from "never measured".
        assertEquals(TargetApp.values().length, rows.size());
        assertEquals(expected, written);
    }

    @Test
    public void refreshTodayIsIdempotent() throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("usage access not granted", UsageAccess.isGranted(context));

        // force, or the repository's cache would short-circuit the second and
        // third calls and the write path would only actually run once - which is
        // not what this test claims to prove.
        repository.refreshToday(true);
        repository.refreshToday(true);
        repository.refreshToday(true);

        List<UsageLog> rows =
                LiveDataValue.of(
                        database.usageLogDao().observeForDate(LocalDate.now().toString()));

        // Three refreshes must not produce three sets of rows.
        assertEquals(TargetApp.values().length, rows.size());
        for (UsageLog row : rows) {
            assertTrue(row.durationMillis >= 0L);
        }
    }
}
