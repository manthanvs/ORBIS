package com.orbis.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.Collection;
import java.util.List;

/**
 * Every blocking method here runs on the repository's executor, never on the
 * main thread. The observing method returns LiveData, which Room updates off the
 * main thread and delivers on it.
 */
@Dao
public interface UsageLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UsageLog log);

    /**
     * One transaction for the whole refresh.
     *
     * <p>Room wraps a multi-row insert in a single transaction; the per-row
     * version cost one journal write and fsync per app, four times per refresh.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<UsageLog> logs);

    @Query("SELECT * FROM usage_log WHERE date = :date ORDER BY durationMillis DESC")
    LiveData<List<UsageLog>> observeForDate(String date);

    @Query("SELECT * FROM usage_log WHERE date BETWEEN :startDate AND :endDate")
    List<UsageLog> forDateRange(String startDate, String endDate);

    /**
     * Daily totals for the given apps only, aggregated by the database.
     *
     * <p>Returns one row per day instead of one per app per day, and does the
     * filtering, grouping and summing in SQL - doing it in application code built
     * three intermediate collections and parsed a date for every row.
     */
    @Query(
            "SELECT date AS date, SUM(durationMillis) AS totalMillis "
                    + "FROM usage_log "
                    + "WHERE date BETWEEN :startDate AND :endDate AND app IN (:apps) "
                    + "GROUP BY date "
                    + "ORDER BY date")
    List<DailyTotal> dailyTotals(String startDate, String endDate, Collection<String> apps);

    /**
     * Drops history older than the dashboard's own window.
     *
     * <p>Nothing pruned this table before, so it grew unbounded for data no
     * screen can ever show.
     */
    @Query("DELETE FROM usage_log WHERE date < :cutoffDate")
    void deleteBefore(String cutoffDate);
}
