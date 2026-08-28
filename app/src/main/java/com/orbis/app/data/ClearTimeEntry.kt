package com.orbis.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One movement in the clear-time ledger - time earned by doing something, or
 * time spent watching a feed at full speed.
 *
 * [date] is the local ISO day, stored rather than derived, because clear time
 * expires at the end of the day and expiry is then a `WHERE date =` instead of a
 * scan-and-convert over a table that grows forever. It matches `usage_log`'s
 * convention deliberately.
 *
 * [action] holds [com.orbis.app.earn.EarnAction.name] for a credit and
 * [com.orbis.app.earn.ClearTimeMovement.SPEND] for a debit - a string, not the
 * enum, so a build that renames or drops an action can still read back an older
 * history instead of crashing on it.
 */
@Entity(
    tableName = "clear_time",
    indices = [Index(value = ["date"])],
)
data class ClearTimeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val date: String,
    val action: String,
    val earnedMillis: Long = 0L,
    val spentMillis: Long = 0L,
)
