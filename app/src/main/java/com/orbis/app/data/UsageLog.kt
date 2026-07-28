package com.orbis.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per app per day. Written by the usage-tracking layer (Phase 1) and
 * read by both the dashboard and the throttle-intensity ranking.
 */
@Entity(tableName = "usage_log")
data class UsageLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val app: String,
    /** ISO-8601 local date, `yyyy-MM-dd`. */
    val date: String,
    val durationMillis: Long,
)
