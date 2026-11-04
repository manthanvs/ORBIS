package com.orbis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One movement in the clear-time ledger - time earned by doing something, or
 * time spent watching a feed at full speed.
 *
 * <p>The date is the local ISO day, stored rather than derived, because clear
 * time expires at the end of the day and expiry is then a {@code WHERE date =}
 * instead of a scan-and-convert over a table that grows forever. It matches
 * {@code usage_log}'s convention deliberately.
 *
 * <p>The action holds {@code EarnAction.name()} for a credit and
 * {@code ClearTimeMovement.SPEND} for a debit - a string, not the enum, so a
 * build that renames or drops an action can still read back an older history
 * instead of crashing on it.
 */
@Entity(
        tableName = "clear_time",
        indices = {@Index(value = {"date"})})
public class ClearTimeEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestampMillis;

    @NonNull
    public String date;

    @NonNull
    public String action;

    public long earnedMillis;

    public long spentMillis;

    public ClearTimeEntry(
            long id,
            long timestampMillis,
            @NonNull String date,
            @NonNull String action,
            long earnedMillis,
            long spentMillis) {
        this.id = id;
        this.timestampMillis = timestampMillis;
        this.date = date;
        this.action = action;
        this.earnedMillis = earnedMillis;
        this.spentMillis = spentMillis;
    }
}
