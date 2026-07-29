package com.orbis.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GoodDeedDao {

    @Insert
    suspend fun insert(entry: GoodDeedEntry): Long

    @Query("SELECT * FROM good_deed ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<GoodDeedEntry>>

    @Query("SELECT * FROM good_deed WHERE completed = 1 ORDER BY timestampMillis DESC")
    suspend fun completed(): List<GoodDeedEntry>

    @Query("SELECT COUNT(*) FROM good_deed WHERE completed = 1")
    suspend fun completedCount(): Int
}
