package com.orbis.app.data;

import androidx.annotation.NonNull;

/** A day's total across the short-form apps, summed in SQL. */
public class DailyTotal {

    @NonNull
    public String date;

    public long totalMillis;

    public DailyTotal(@NonNull String date, long totalMillis) {
        this.date = date;
        this.totalMillis = totalMillis;
    }
}
