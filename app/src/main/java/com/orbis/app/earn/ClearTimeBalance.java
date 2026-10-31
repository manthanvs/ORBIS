package com.orbis.app.earn;

import java.util.Objects;

/** What one day's ledger earned and spent. */
public final class ClearTimeBalance {

    public static final ClearTimeBalance EMPTY = new ClearTimeBalance(0L, 0L);

    private final long earnedMillis;
    private final long spentMillis;

    public ClearTimeBalance(long earnedMillis, long spentMillis) {
        this.earnedMillis = earnedMillis;
        this.spentMillis = spentMillis;
    }

    public long getEarnedMillis() {
        return earnedMillis;
    }

    public long getSpentMillis() {
        return spentMillis;
    }

    /** Never negative: overspending is capped, not carried forward as a debt. */
    public long getRemainingMillis() {
        return Math.max(earnedMillis - spentMillis, 0L);
    }

    public boolean getHasCredit() {
        return getRemainingMillis() > 0L;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClearTimeBalance)) {
            return false;
        }
        ClearTimeBalance that = (ClearTimeBalance) other;
        return earnedMillis == that.earnedMillis && spentMillis == that.spentMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(earnedMillis, spentMillis);
    }

    @Override
    public String toString() {
        return "ClearTimeBalance(earned=" + earnedMillis + ", spent=" + spentMillis + ")";
    }
}
