package com.orbis.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: UsageLog)

    @Query("SELECT * FROM usage_log WHERE date = :date ORDER BY durationMillis DESC")
    fun observeForDate(date: String): Flow<List<UsageLog>>

    @Query("SELECT * FROM usage_log WHERE date BETWEEN :startDate AND :endDate")
    suspend fun forDateRange(startDate: String, endDate: String): List<UsageLog>
}
