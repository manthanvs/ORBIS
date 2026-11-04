package com.orbis.app.earn;

import java.util.Collection;

/**
 * The economy behind the side quest.
 *
 * <p>ORBIS slows short-form feeds; clear time buys them back to full speed. That
 * inversion is the whole design. Restriction on its own reliably gets
 * uninstalled - it reads as something being taken away, and people push back
 * against that harder than they push back against the habit. An earn-back loop
 * never blocks anything: the user always holds the lever, and the friction
 * becomes a price rather than a punishment.
 *
 * <p>Two rules keep it honest, and both are load-bearing:
 *
 * <ul>
 *   <li><b>Credit expires at the end of the day.</b> Callers pass in one day's
 *       movements and nothing else. Hoarding turns a daily trade into a savings
 *       account, and a user with four hours banked is a user ORBIS has stopped
 *       doing anything for.
 *   <li><b>The daily cap bounds the day.</b> Without a ceiling the loop rewards
 *       whoever grinds hardest, and the app quietly becomes the thing it was
 *       meant to interrupt.
 * </ul>
 *
 * <p>Pure, so the awkward parts - a partial spend, an overspend, a day at the
 * cap - are testable without a device or a clock.
 */
public final class ClearTime {

    /** The most clear time a single day can hold, however much is earned. */
    public static final long DAILY_CAP_MILLIS = 30 * 60_000L;

    private ClearTime() {
        // No instances: the economy is stateless.
    }

    /**
     * @param movements one day's ledger, in any order
     * @return what that day earned and spent, with earnings capped
     */
    public static ClearTimeBalance balance(Collection<ClearTimeMovement> movements) {
        long earned = 0L;
        long spent = 0L;
        for (ClearTimeMovement movement : movements) {
            earned += movement.getEarnedMillis();
            spent += movement.getSpentMillis();
        }
        // The cap applies to what was earned, not to what is left, so spending
        // does not quietly re-open room to earn past the ceiling.
        long cappedEarned = Math.min(Math.max(earned, 0L), DAILY_CAP_MILLIS);
        return new ClearTimeBalance(cappedEarned, Math.max(spent, 0L));
    }

    /**
     * How many more times this action may be performed today.
     *
     * <p>Counts the action's own movements, so one action running out does not
     * block the others.
     */
    public static int remainingUses(EarnAction action, Collection<ClearTimeMovement> movements) {
        int used = 0;
        for (ClearTimeMovement movement : movements) {
            if (action.name().equals(movement.getActionName())) {
                used++;
            }
        }
        return Math.max(action.getDailyLimit() - used, 0);
    }

    public static boolean canEarn(EarnAction action, Collection<ClearTimeMovement> movements) {
        return remainingUses(action, movements) > 0
                && balance(movements).getEarnedMillis() < DAILY_CAP_MILLIS;
    }

    /**
     * What this action would actually pay right now, once the daily cap applies.
     *
     * <p>Returns zero rather than a partial reward when the action is out of
     * uses, so the caller never records a movement that buys nothing.
     */
    public static long rewardFor(EarnAction action, Collection<ClearTimeMovement> movements) {
        if (remainingUses(action, movements) <= 0) {
            return 0L;
        }
        long headroom = DAILY_CAP_MILLIS - balance(movements).getEarnedMillis();
        return Math.max(Math.min(action.getRewardMillis(), headroom), 0L);
    }
}
