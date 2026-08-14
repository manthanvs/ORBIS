package com.orbis.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A day's total across the short-form apps, summed in SQL. */
data class DailyTotal(
    val date: String,
    val totalMillis: Long,
)

@Dao
interface UsageLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: UsageLog)

    /**
     * One transaction for the whole refresh.
     *
     * Room wraps a multi-row insert in a single transaction; the per-row version
     * cost one journal write and fsync per app, four times per refresh.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<UsageLog>)

    @Query("SELECT * FROM usage_log WHERE date = :date ORDER BY durationMillis DESC")
    fun observeForDate(date: String): Flow<List<UsageLog>>

    @Query("SELECT * FROM usage_log WHERE date BETWEEN :startDate AND :endDate")
    suspend fun forDateRange(startDate: String, endDate: String): List<UsageLog>

    /**
     * Daily totals for [apps] only, aggregated by the database.
     *
     * Returns one row per day instead of one per app per day, and does the
     * filtering, grouping and summing in SQL - the Kotlin version built three
     * intermediate collections and parsed a `LocalDate` for every row.
     */
    @Query(
        """
        SELECT date AS date, SUM(durationMillis) AS totalMillis
        FROM usage_log
        WHERE date BETWEEN :startDate AND :endDate AND app IN (:apps)
        GROUP BY date
        ORDER BY date
        """
    )
    suspend fun dailyTotals(
        startDate: String,
        endDate: String,
        apps: Collection<String>,
    ): List<DailyTotal>

    /**
     * Drops history older than the dashboard's own window.
     *
     * Nothing pruned this table before, so it grew unbounded for data no screen
     * can ever show.
     */
    @Query("DELETE FROM usage_log WHERE date < :cutoffDate")
    suspend fun deleteBefore(cutoffDate: String)
}
