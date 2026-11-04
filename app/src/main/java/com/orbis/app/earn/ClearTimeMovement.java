package com.orbis.app.earn;

import java.util.Objects;

/**
 * One movement in the clear-time ledger: either something earned or something
 * spent, never both.
 *
 * <p>The action name is {@link EarnAction#name()} for a credit and {@link #SPEND}
 * for a debit. It is a string rather than the enum so that a build which drops or
 * renames an action can still read back a user's history instead of crashing on
 * it.
 */
public final class ClearTimeMovement {

    public static final String SPEND = "SPEND";

    private final String actionName;
    private final long earnedMillis;
    private final long spentMillis;

    public ClearTimeMovement(String actionName, long earnedMillis, long spentMillis) {
        this.actionName = actionName;
        this.earnedMillis = earnedMillis;
        this.spentMillis = spentMillis;
    }

    /** A credit: this action paid out this much. */
    public static ClearTimeMovement earned(String actionName, long earnedMillis) {
        return new ClearTimeMovement(actionName, earnedMillis, 0L);
    }

    /** A debit: this much credit was spent watching. */
    public static ClearTimeMovement spent(long spentMillis) {
        return new ClearTimeMovement(SPEND, 0L, spentMillis);
    }

    public String getActionName() {
        return actionName;
    }

    public long getEarnedMillis() {
        return earnedMillis;
    }

    public long getSpentMillis() {
        return spentMillis;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClearTimeMovement)) {
            return false;
        }
        ClearTimeMovement that = (ClearTimeMovement) other;
        return earnedMillis == that.earnedMillis
                && spentMillis == that.spentMillis
                && Objects.equals(actionName, that.actionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionName, earnedMillis, spentMillis);
    }

    @Override
    public String toString() {
        return "ClearTimeMovement(" + actionName
                + ", earned=" + earnedMillis
                + ", spent=" + spentMillis
                + ")";
    }
}
