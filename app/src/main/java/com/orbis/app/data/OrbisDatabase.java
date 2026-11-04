package com.orbis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * The single Room database for ORBIS. All persistence lives here - there is no
 * server component and no cloud sync.
 *
 * <p>A throttle-rule table is still absent: throttle intensity is derived from
 * usage at runtime by the throttle engine, so there is nothing to persist yet.
 * Add it here with a further version bump if rules ever become user-editable.
 */
@Database(
        entities = {UsageLog.class, ClearTimeEntry.class},
        version = 5,
        exportSchema = false)
public abstract class OrbisDatabase extends RoomDatabase {

    public abstract UsageLogDao usageLogDao();

    public abstract ClearTimeDao clearTimeDao();

    /**
     * Adds {@code good_deed}.
     *
     * <p>A real migration rather than a destructive fallback: by this point the
     * database holds the user's accumulated usage history, which is exactly what
     * the dashboard's baseline is computed from. Wiping it would reset "reclaimed
     * time" to "still learning" and silently destroy real data.
     *
     * <p>The DDL is kept verbatim even though the table is dropped again in
     * {@link #MIGRATION_4_5}: a database still at version 1 has to walk the same
     * path everyone else walked, and a migration that has shipped is history.
     */
    public static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `good_deed` ("
                                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                                    + "`timestampMillis` INTEGER NOT NULL, "
                                    + "`photoPath` TEXT, "
                                    + "`note` TEXT NOT NULL, "
                                    + "`completed` INTEGER NOT NULL)");
                }
            };

    /**
     * Indices only - no data is touched.
     *
     * <p>{@code usage_log}'s unique index is reordered to lead with {@code date}:
     * SQLite can only use an index whose leading column is constrained, so the
     * old {@code (app, date)} ordering left the dashboard's date-range query
     * doing a full scan of a table that grows forever. {@code (date, app)}
     * enforces exactly the same uniqueness and serves the range query too.
     *
     * <p>The index names must be precisely what Room derives from the entities,
     * or schema validation throws on the next open.
     */
    public static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL("DROP INDEX IF EXISTS `index_usage_log_app_date`");
                    db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_log_date_app` "
                                    + "ON `usage_log` (`date`, `app`)");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS "
                                    + "`index_good_deed_completed_timestampMillis` "
                                    + "ON `good_deed` (`completed`, `timestampMillis`)");
                }
            };

    /**
     * Adds {@code clear_time} for the earn-back loop.
     *
     * <p>Additive; {@code good_deed} was still in place at this version. The DDL
     * must match what Room generates for the entity exactly, down to the index
     * name, or schema validation throws on the next open.
     */
    public static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `clear_time` ("
                                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                                    + "`timestampMillis` INTEGER NOT NULL, "
                                    + "`date` TEXT NOT NULL, "
                                    + "`action` TEXT NOT NULL, "
                                    + "`earnedMillis` INTEGER NOT NULL, "
                                    + "`spentMillis` INTEGER NOT NULL)");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_clear_time_date` "
                                    + "ON `clear_time` (`date`)");
                }
            };

    /**
     * Drops {@code good_deed}: the good-deed challenge is gone.
     *
     * <p>The one deliberate exception to "never destroy the user's history". The
     * rule exists because {@code usage_log} is what the dashboard's baseline is
     * computed from, and {@code clear_time} is today's ledger - losing either
     * changes what ORBIS tells the user. A table for a feature that no longer
     * exists tells them nothing.
     */
    public static final Migration MIGRATION_4_5 =
            new Migration(4, 5) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL("DROP TABLE IF EXISTS `good_deed`");
                }
            };
}
