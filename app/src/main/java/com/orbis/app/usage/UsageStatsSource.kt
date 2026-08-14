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

    /**
     * @param packages when non-null, only these packages are materialised.
     *   `queryEvents` returns transitions for **every** app on the device, which
     *   on a busy phone is tens of thousands of events for a single day - and
     *   ORBIS reports on four of them. Filtering here rather than downstream is
     *   the difference between allocating four figures of records per refresh and
     *   allocating a handful.
     */
    fun eventsBetween(
        startMillis: Long,
        endMillis: Long,
        packages: Set<String>? = null,
    ): List<UsageEventRecord> {
        val records = mutableListOf<UsageEventRecord>()
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.BACKGROUND
                else -> null
            } ?: continue

            val packageName = event.packageName ?: continue
            if (packages != null && packageName !in packages) continue

            records += UsageEventRecord(
                packageName = packageName,
                type = type,
                timestampMillis = event.timeStamp,
            )
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
