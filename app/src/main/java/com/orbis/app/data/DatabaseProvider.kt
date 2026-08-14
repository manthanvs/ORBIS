package com.orbis.app.data

import android.content.Context
import androidx.room.Room

/**
 * Single [OrbisDatabase] instance for the process.
 *
 * Deliberately hand-rolled: ORBIS has no DI framework, and one database with a
 * couple of DAOs does not justify adding one.
 */
object DatabaseProvider {

    private const val DATABASE_NAME = "orbis.db"

    @Volatile
    private var instance: OrbisDatabase? = null

    fun get(context: Context): OrbisDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OrbisDatabase::class.java,
                DATABASE_NAME,
            )
                // Deliberately no fallbackToDestructiveMigration: the usage history
                // in here is what the dashboard baseline is built from.
                .addMigrations(OrbisDatabase.MIGRATION_1_2, OrbisDatabase.MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
}
