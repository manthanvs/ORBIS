package com.orbis.app.data;

import android.content.Context;

import androidx.room.Room;

/**
 * Single {@link OrbisDatabase} instance for the process.
 *
 * <p>Deliberately hand-rolled: ORBIS has no DI framework, and one database with
 * a couple of DAOs does not justify adding one.
 */
public final class DatabaseProvider {

    private static final String DATABASE_NAME = "orbis.db";

    private static volatile OrbisDatabase instance;

    private DatabaseProvider() {
        // No instances: this holds the one database.
    }

    public static OrbisDatabase get(Context context) {
        OrbisDatabase existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (DatabaseProvider.class) {
            if (instance == null) {
                instance = Room.databaseBuilder(
                                context.getApplicationContext(),
                                OrbisDatabase.class,
                                DATABASE_NAME)
                        // Deliberately no fallbackToDestructiveMigration: the usage
                        // history in here is what the dashboard baseline is built from.
                        .addMigrations(
                                OrbisDatabase.MIGRATION_1_2,
                                OrbisDatabase.MIGRATION_2_3,
                                OrbisDatabase.MIGRATION_3_4,
                                OrbisDatabase.MIGRATION_4_5)
                        .build();
            }
            return instance;
        }
    }
}
