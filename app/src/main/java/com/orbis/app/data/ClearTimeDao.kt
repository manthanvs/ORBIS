package com.orbis.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClearTimeDao {

    @Insert
    suspend fun insert(entry: ClearTimeEntry)

    /**
     * One day's ledger. Clear time expires overnight, so this is the only read the
     * balance ever needs - the index on `date` serves it without a scan.
     */
    @Query("SELECT * FROM clear_time WHERE date = :date ORDER BY timestampMillis")
    fun observeForDate(date: String): Flow<List<ClearTimeEntry>>

    @Query("SELECT * FROM clear_time WHERE date = :date ORDER BY timestampMillis")
    suspend fun forDate(date: String): List<ClearTimeEntry>

    /**
     * Days on which anything was earned, for the streak.
     *
     * Filtered to credits: a day where the user only *spent* clear time is not a
     * day they did anything, and counting it would let a streak run on entirely
     * unearned.
     */
    @Query("SELECT DISTINCT date FROM clear_time WHERE earnedMillis > 0 ORDER BY date")
    suspend fun earnedDates(): List<String>

    @Query("DELETE FROM clear_time WHERE date < :cutoffDate")
    suspend fun deleteBefore(cutoffDate: String)
}
