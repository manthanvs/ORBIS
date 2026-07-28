package com.orbis.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single Room database for ORBIS. All persistence lives here — there is no
 * server component and no cloud sync.
 *
 * `ThrottleRule` and `GoodDeedEntry` join this in Phases 3 and 5; bump [version]
 * and add a migration when they do.
 */
@Database(
    entities = [UsageLog::class],
    version = 1,
    exportSchema = false,
)
abstract class OrbisDatabase : RoomDatabase() {
    abstract fun usageLogDao(): UsageLogDao
}
