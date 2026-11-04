package com.orbis.app.earn;

import java.util.Collections;
import java.util.Map;

/** What today's ledger adds up to, ready for the UI. */
public final class EarnSummary {

    public static final EarnSummary EMPTY =
            new EarnSummary(ClearTimeBalance.EMPTY, 0, Collections.<EarnAction, Integer>emptyMap());

    private final ClearTimeBalance balance;
    private final int streak;
    private final Map<EarnAction, Integer> remainingUses;

    public EarnSummary(
            ClearTimeBalance balance, int streak, Map<EarnAction, Integer> remainingUses) {
        this.balance = balance;
        this.streak = streak;
        this.remainingUses = Collections.unmodifiableMap(remainingUses);
    }

    public ClearTimeBalance getBalance() {
        return balance;
    }

    public int getStreak() {
        return streak;
    }

    /** Uses left today, per action, so buttons can be disabled honestly. */
    public Map<EarnAction, Integer> getRemainingUses() {
        return remainingUses;
    }

    public int remainingUsesOf(EarnAction action) {
        Integer left = remainingUses.get(action);
        return left == null ? 0 : left;
    }
}
