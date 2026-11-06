package com.orbis.app.data;

import android.content.Context;
import android.os.SystemClock;

import com.orbis.app.usage.ForegroundTimeCalculator;
import com.orbis.app.usage.TargetApp;
import com.orbis.app.usage.UsageProfile;
import com.orbis.app.usage.UsageProfileHolder;
import com.orbis.app.usage.UsageStatsSource;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads foreground time from the system and persists it as one {@link UsageLog}
 * row per app per day.
 *
 * <p>The clock is injectable so day-boundary behaviour can be exercised without
 * waiting for midnight.
 *
 * <p>Every method here blocks. Callers hand them to
 * {@link com.orbis.app.util.Background}; nothing in this class may run on the
 * main thread.
 */
public final class UsageRepository {

    /**
     * Long enough to collapse the burst of refreshes a resume triggers, short
     * enough that the number on screen still tracks a live session.
     */
    public static final long CACHE_TTL_MILLIS = 20_000L;

    /** History older than this cannot appear on any screen. */
    public static final int RETENTION_DAYS = 60;

    /** Days the throttle level's floor averages over. */
    public static final long BASELINE_DAYS = 7L;

    private static volatile UsageRepository instance;

    private final UsageStatsSource source;
    private final UsageLogDao dao;
    private final Clock clock;

    /** Serialises refreshes so two callers cannot race on the same day's rows. */
    private final Object refreshLock = new Object();

    private UsageProfile cachedProfile;
    private long cachedAtMillis;
    private LocalDate cachedDate;

    public UsageRepository(UsageStatsSource source, UsageLogDao dao, Clock clock) {
        this.source = source;
        this.dao = dao;
        this.clock = clock;
    }

    public UsageRepository(UsageStatsSource source, UsageLogDao dao) {
        this(source, dao, Clock.systemDefaultZone());
    }

    /**
     * The one repository for the process.
     *
     * <p>Each screen used to build its own, so nothing was ever shared or cached
     * and every resume re-scanned the whole day twice over.
     */
    public static UsageRepository shared(Context context) {
        UsageRepository existing = instance;
        if (existing != null) {
            return existing;
        }
        Context appContext = context.getApplicationContext();
        synchronized (UsageRepository.class) {
            if (instance == null) {
                instance =
                        new UsageRepository(
                                UsageStatsSource.from(appContext),
                                DatabaseProvider.get(appContext).usageLogDao());
            }
            return instance;
        }
    }

    public UsageProfile refreshToday() {
        return refreshToday(false);
    }

    /**
     * Recomputes today's totals from the event stream and writes them.
     *
     * <p>Every tracked app is written, including those with zero time, so the
     * dashboard can distinguish "not used today" from "never measured".
     *
     * <p>Results are cached for {@link #CACHE_TTL_MILLIS}. Both the screens and
     * the accessibility service refresh on their own schedule, and a resume used
     * to fire two full-day event scans concurrently, each followed by four writes
     * to the same rows.
     *
     * @param force skips the cache, for an explicit pull-to-refresh.
     */
    public UsageProfile refreshToday(boolean force) {
        synchronized (refreshLock) {
            LocalDate today = LocalDate.now(clock);
            long now = SystemClock.elapsedRealtime();

            if (!force
                    && cachedProfile != null
                    && today.equals(cachedDate)
                    && now - cachedAtMillis < CACHE_TTL_MILLIS) {
                return cachedProfile;
            }

            long startOfDay = today.atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            long endOfWindow = clock.millis();

            Map<String, Long> totals =
                    ForegroundTimeCalculator.totalsByPackage(
                            // Only the apps ORBIS reports on; the raw stream
                            // carries every app on the device.
                            source.eventsBetween(
                                    startOfDay, endOfWindow, TargetApp.getPackageNames()),
                            startOfDay,
                            endOfWindow);

            // Built first so the rows are written per app, variants folded in: a
            // repackaged client's time is logged under Instagram, not dropped.
            UsageProfile profile = UsageProfile.from(totals);

            String date = today.toString();
            List<UsageLog> rows = new ArrayList<>();
            for (TargetApp app : TargetApp.values()) {
                rows.add(new UsageLog(0L, app.getPackageName(), date, profile.durationOf(app)));
            }
            dao.upsertAll(rows);

            cachedProfile = profile;
            cachedAtMillis = now;
            cachedDate = today;
            UsageProfileHolder.publish(profile, recentAverageMillis(today));
            return profile;
        }
    }

    /**
     * What the last {@link #BASELINE_DAYS} of short-form actually looked like,
     * per day.
     *
     * <p>This sets the throttle level's <i>ramp</i> - how fast today climbs the
     * ladder - so a settled habit climbs out of its daily fresh start sooner
     * than a light one does. Today is excluded: it is only part-way through, and
     * a day that raised its own ramp would compound.
     */
    private long recentAverageMillis(LocalDate today) {
        List<DailyTotal> days =
                dao.dailyTotals(
                        today.minusDays(BASELINE_DAYS).toString(),
                        today.minusDays(1).toString(),
                        throttleablePackages());
        if (days.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (DailyTotal day : days) {
            sum += day.totalMillis;
        }
        return sum / days.size();
    }

    /**
     * Daily totals for the short-form apps over the last days, for the dashboard.
     *
     * <p>Sums only the throttleable apps - WhatsApp is measured but is not
     * short-form content, so counting it would make messaging look like something
     * to reclaim.
     *
     * <p>Days with no rows are simply absent from the map. That distinction
     * matters: the reclaimed-time maths treats a missing day as unknown rather
     * than as zero usage.
     */
    public Map<LocalDate, Long> dailyHistory(int days) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(days - 1L);

        Map<LocalDate, Long> history = new HashMap<>();
        for (DailyTotal total :
                dao.dailyTotals(start.toString(), today.toString(), throttleablePackages())) {
            history.put(LocalDate.parse(total.date), total.totalMillis);
        }
        return history;
    }

    /**
     * Discards history the dashboard can no longer show.
     *
     * <p>Cheap and rare, but nothing pruned this table before and it grows for as
     * long as the app is installed.
     */
    public void pruneHistory(int keepDays) {
        dao.deleteBefore(LocalDate.now(clock).minusDays(keepDays).toString());
    }

    private List<String> throttleablePackages() {
        List<String> packages = new ArrayList<>();
        for (TargetApp app : TargetApp.getThrottleable()) {
            packages.add(app.getPackageName());
        }
        return packages;
    }
}
