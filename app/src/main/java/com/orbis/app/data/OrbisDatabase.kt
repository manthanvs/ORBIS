package com.orbis.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single Room database for ORBIS. All persistence lives here — there is no
 * server component and no cloud sync.
 *
 * `ThrottleRule` is still absent: throttle intensity is derived from usage at
 * runtime by `ThrottleEngine`, so there is nothing to persist yet. Add it here with
 * a further version bump if rules ever become user-editable.
 */
@Database(
    entities = [UsageLog::class, GoodDeedEntry::class],
    version = 3,
    exportSchema = false,
)
abstract class OrbisDatabase : RoomDatabase() {
    abstract fun usageLogDao(): UsageLogDao
    abstract fun goodDeedDao(): GoodDeedDao

    companion object {
        /**
         * Adds `good_deed` for Phase 5.
         *
         * A real migration rather than a destructive fallback: by this point the
         * database holds the user's accumulated usage history, which is exactly
         * what the dashboard's baseline is computed from. Wiping it would reset
         * "reclaimed time" to "still learning" and silently destroy real data.
         *
         * The DDL must match what Room generates for [GoodDeedEntry] exactly, or
         * Room's schema validation throws on the next open.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `good_deed` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestampMillis` INTEGER NOT NULL, " +
                        "`photoPath` TEXT, " +
                        "`note` TEXT NOT NULL, " +
                        "`completed` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Indices only - no data is touched.
         *
         * `usage_log`'s unique index is reordered to lead with `date`: SQLite can
         * only use an index whose leading column is constrained, so the old
         * `(app, date)` ordering left the dashboard's date-range query doing a
         * full scan of a table that grows forever. `(date, app)` enforces exactly
         * the same uniqueness and serves the range query too.
         *
         * The index names must be precisely what Room derives from the entities,
         * or schema validation throws on the next open.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_usage_log_app_date`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_log_date_app` " +
                        "ON `usage_log` (`date`, `app`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_good_deed_completed_timestampMillis` " +
                        "ON `good_deed` (`completed`, `timestampMillis`)"
                )
            }
        }
    }
}
