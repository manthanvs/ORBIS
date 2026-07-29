package com.orbis.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One good-deed prompt and what became of it.
 *
 * [photoPath] points at app-private storage, never shared storage - that keeps the
 * feature clear of broad storage permissions entirely.
 */
@Entity(tableName = "good_deed")
data class GoodDeedEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    /** Absolute path inside the app's private files dir, or null if none captured. */
    val photoPath: String? = null,
    val note: String = "",
    val completed: Boolean = false,
)
