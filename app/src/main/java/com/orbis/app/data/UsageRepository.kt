package com.orbis.app.data

import android.content.Context
import android.os.SystemClock
import com.orbis.app.usage.ForegroundTimeCalculator
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile
import com.orbis.app.usage.UsageProfileHolder
import com.orbis.app.usage.UsageStatsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/**
 * Reads foreground time from the system and persists it as one [UsageLog] row per
 * app per day.
 *
 * [clock] is injectable so day-boundary behaviour can be exercised in tests
 * without waiting for midnight.
 */
class UsageRepository(
    private val source: UsageStatsSource,
    private val dao: UsageLogDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /** Serialises refreshes so two callers cannot race on the same day's rows. */
    private val refreshLock = Mutex()

    private var cachedProfile: UsageProfile? = null
    private var cachedAtMillis = 0L
    private var cachedDate: LocalDate? = null

    /**
     * Recomputes today's totals from the event stream and writes them.
     *
     * Every tracked app is written, including those with zero time, so the
     * dashboard can distinguish "not used today" from "never measured".
     *
     * Results are cached for [CACHE_TTL_MILLIS]. Both ViewModels and the
     * accessibility service refresh on their own schedule, and a resume used to
     * fire two full-day `queryEvents` scans concurrently, each followed by four
     * writes to the same rows.
     *
     * @param force skips the cache, for an explicit pull-to-refresh.
     */
    suspend fun refreshToday(force: Boolean = false): UsageProfile = refreshLock.withLock {
        val today = LocalDate.now(clock)
        val now = SystemClock.elapsedRealtime()

        if (!force) {
            val cached = cachedProfile
            if (
                cached != null &&
                cachedDate == today &&
                now - cachedAtMillis < CACHE_TTL_MILLIS
            ) {
                return@withLock cached
            }
        }

        val profile = withContext(Dispatchers.IO) {
            val startOfDay = today.atStartOfDay(clock.zone).toInstant().toEpochMilli()
            val endOfWindow = clock.millis()

            val totals = ForegroundTimeCalculator.totalsByPackage(
                // Only the apps ORBIS reports on; the raw stream carries every
                // app on the device.
                events = source.eventsBetween(
                    startOfDay,
                    endOfWindow,
                    packages = TargetApp.packageNames,
                ),
                windowStartMillis = startOfDay,
                windowEndMillis = endOfWindow,
            )

            val date = today.toString()
            dao.upsertAll(
                TargetApp.entries.map { app ->
                    UsageLog(
                        app = app.packageName,
                        date = date,
                        durationMillis = totals[app.packageName] ?: 0L,
                    )
                }
            )

            UsageProfile.from(totals)
        }

        cachedProfile = profile
        cachedAtMillis = now
        cachedDate = today
        UsageProfileHolder.publish(profile)
        profile
    }

    /**
     * Daily totals for the short-form apps over the last [days], for the dashboard.
     *
     * Sums only [TargetApp.throttleable] — WhatsApp is measured but is not
     * short-form content, so counting it would make messaging look like something
     * to reclaim.
     *
     * Days with no rows are simply absent from the map. That distinction matters:
     * `ReclaimedTime` treats a missing day as unknown rather than as zero usage.
     */
    suspend fun dailyHistory(days: Int): Map<LocalDate, Long> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(clock)
        val start = today.minusDays((days - 1).toLong())
        val shortForm = TargetApp.throttleable.map { it.packageName }

        dao.dailyTotals(start.toString(), today.toString(), shortForm)
            .associate { LocalDate.parse(it.date) to it.totalMillis }
    }

    /**
     * Discards history the dashboard can no longer show.
     *
     * Cheap and rare, but nothing pruned this table before and it grows for as
     * long as the app is installed.
     */
    suspend fun pruneHistory(keepDays: Int) = withContext(Dispatchers.IO) {
        val cutoff = LocalDate.now(clock).minusDays(keepDays.toLong())
        dao.deleteBefore(cutoff.toString())
    }

    companion object {
        /**
         * Long enough to collapse the burst of refreshes a resume triggers, short
         * enough that the number on screen still tracks a live session.
         */
        const val CACHE_TTL_MILLIS = 20_000L

        /** History older than this cannot appear on any screen. */
        const val RETENTION_DAYS = 60

        @Volatile
        private var instance: UsageRepository? = null

        /**
         * The one repository for the process.
         *
         * Each ViewModel used to build its own, so nothing was ever shared or
         * cached and every resume re-scanned the whole day twice over.
         */
        fun shared(context: Context): UsageRepository {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: UsageRepository(
                    source = UsageStatsSource.from(appContext),
                    dao = DatabaseProvider.get(appContext).usageLogDao(),
                ).also { instance = it }
            }
        }
    }
}
