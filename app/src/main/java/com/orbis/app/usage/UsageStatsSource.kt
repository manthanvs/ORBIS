package com.orbis.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Thin adapter over [UsageStatsManager].
 *
 * Its only job is to flatten the framework's cursor-style event stream into plain
 * [UsageEventRecord]s; all arithmetic lives in [ForegroundTimeCalculator].
 *
 * Returns an empty list when usage access has not been granted - the framework
 * reports no events rather than throwing, so callers should check
 * [UsageAccess.isGranted] to tell "not permitted" from "genuinely no usage".
 */
class UsageStatsSource(
    private val usageStatsManager: UsageStatsManager,
) {

    fun eventsBetween(startMillis: Long, endMillis: Long): List<UsageEventRecord> {
        val records = mutableListOf<UsageEventRecord>()
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.BACKGROUND
                else -> null
            }
            if (type != null) {
                records += UsageEventRecord(
                    packageName = event.packageName,
                    type = type,
                    timestampMillis = event.timeStamp,
                )
            }
        }
        return records
    }

    companion object {
        fun from(context: Context): UsageStatsSource =
            UsageStatsSource(
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            )
    }
}
