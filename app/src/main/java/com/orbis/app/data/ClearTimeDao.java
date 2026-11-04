package com.orbis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ClearTimeDao {

    @Insert
    void insert(ClearTimeEntry entry);

    /**
     * One day's ledger. Clear time expires overnight, so this is the only read
     * the balance ever needs - the index on {@code date} serves it without a scan.
     */
    @Query("SELECT * FROM clear_time WHERE date = :date ORDER BY timestampMillis")
    List<ClearTimeEntry> forDate(String date);

    /**
     * Days on which anything was earned, for the streak.
     *
     * <p>Filtered to credits: a day where the user only <i>spent</i> clear time
     * is not a day they did anything, and counting it would let a streak run on
     * entirely unearned.
     */
    @Query("SELECT DISTINCT date FROM clear_time WHERE earnedMillis > 0 ORDER BY date")
    List<String> earnedDates();

    @Query("DELETE FROM clear_time WHERE date < :cutoffDate")
    void deleteBefore(String cutoffDate);
}
