package com.orbis.app.data

import com.orbis.app.usage.ForegroundTimeCalculator
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile
import com.orbis.app.usage.UsageProfileHolder
import com.orbis.app.usage.UsageStatsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    /**
     * Recomputes today's totals from the event stream and writes them.
     *
     * Every tracked app is written, including those with zero time, so the
     * dashboard can distinguish "not used today" from "never measured".
     */
    suspend fun refreshToday(): UsageProfile = withContext(Dispatchers.IO) {
        val today = LocalDate.now(clock)
        val startOfDay = today.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val now = clock.millis()

        val totals = ForegroundTimeCalculator.totalsByPackage(
            events = source.eventsBetween(startOfDay, now),
            windowStartMillis = startOfDay,
            windowEndMillis = now,
        )

        val date = today.toString()
        TargetApp.entries.forEach { app ->
            dao.upsert(
                UsageLog(
                    app = app.packageName,
                    date = date,
                    durationMillis = totals[app.packageName] ?: 0L,
                )
            )
        }

        UsageProfile.from(totals).also(UsageProfileHolder::publish)
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
        val shortForm = TargetApp.throttleable.map { it.packageName }.toSet()

        dao.forDateRange(start.toString(), today.toString())
            .filter { it.app in shortForm }
            .groupBy { LocalDate.parse(it.date) }
            .mapValues { (_, logs) -> logs.sumOf { it.durationMillis } }
    }

    /**
     * Today's persisted usage.
     *
     * The date is resolved when this is called, so a session left open across
     * midnight keeps observing the previous day until the flow is recollected.
     */
    fun observeToday(): Flow<UsageProfile> {
        val date = LocalDate.now(clock).toString()
        return dao.observeForDate(date).map { logs ->
            UsageProfile.from(logs.associate { it.app to it.durationMillis })
        }
    }
}
