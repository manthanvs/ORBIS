package com.orbis.app.earn

/**
 * One movement in the clear-time ledger: either something earned or something
 * spent, never both.
 *
 * [actionName] is [EarnAction.name] for a credit and [SPEND] for a debit. It is a
 * string rather than the enum so that a build which drops or renames an action
 * can still read back a user's history instead of crashing on it.
 */
data class ClearTimeMovement(
    val actionName: String,
    val earnedMillis: Long = 0L,
    val spentMillis: Long = 0L,
) {
    companion object {
        const val SPEND = "SPEND"
    }
}

data class ClearTimeBalance(
    val earnedMillis: Long = 0L,
    val spentMillis: Long = 0L,
) {
    /** Never negative: overspending is capped, not carried forward as a debt. */
    val remainingMillis: Long get() = (earnedMillis - spentMillis).coerceAtLeast(0L)

    val hasCredit: Boolean get() = remainingMillis > 0L

    companion object {
        val EMPTY = ClearTimeBalance()
    }
}

/**
 * The economy behind the side quest.
 *
 * ORBIS slows short-form feeds; clear time buys them back to full speed. That
 * inversion is the whole design. Restriction on its own reliably gets uninstalled
 * - it reads as something being taken away, and people push back against that
 * harder than they push back against the habit. An earn-back loop never blocks
 * anything: the user always holds the lever, and the friction becomes a price
 * rather than a punishment.
 *
 * Two rules keep it honest, and both are load-bearing:
 *
 *  - **Credit expires at the end of the day.** Callers pass in one day's
 *    movements and nothing else. Hoarding turns a daily trade into a savings
 *    account, and a user with four hours banked is a user ORBIS has stopped
 *    doing anything for.
 *  - **[DAILY_CAP_MILLIS] bounds the day.** Without a ceiling the loop rewards
 *    whoever grinds hardest, and the app quietly becomes the thing it was meant
 *    to interrupt.
 *
 * Pure, so the awkward parts - a partial spend, an overspend, a day at the cap -
 * are testable without a device or a clock.
 */
object ClearTime {

    /** The most clear time a single day can hold, however much is earned. */
    const val DAILY_CAP_MILLIS = 30 * 60_000L

    /**
     * @param movements one day's ledger, in any order.
     * @return what that day earned and spent, with earnings capped.
     */
    fun balance(movements: Collection<ClearTimeMovement>): ClearTimeBalance {
        var earned = 0L
        var spent = 0L
        for (movement in movements) {
            earned += movement.earnedMillis
            spent += movement.spentMillis
        }
        // The cap applies to what was earned, not to what is left, so spending
        // does not quietly re-open room to earn past the ceiling.
        return ClearTimeBalance(
            earnedMillis = earned.coerceIn(0L, DAILY_CAP_MILLIS),
            spentMillis = spent.coerceAtLeast(0L),
        )
    }

    /**
     * How many more times [action] may be performed today.
     *
     * Counts the action's own movements, so one action running out does not
     * block the others.
     */
    fun remainingUses(action: EarnAction, movements: Collection<ClearTimeMovement>): Int {
        val used = movements.count { it.actionName == action.name }
        return (action.dailyLimit - used).coerceAtLeast(0)
    }

    fun canEarn(action: EarnAction, movements: Collection<ClearTimeMovement>): Boolean =
        remainingUses(action, movements) > 0 &&
            balance(movements).earnedMillis < DAILY_CAP_MILLIS

    /**
     * What [action] would actually pay right now, once the daily cap is applied.
     *
     * Returns zero rather than a partial reward when the action is out of uses,
     * so the caller never records a movement that buys nothing.
     */
    fun rewardFor(action: EarnAction, movements: Collection<ClearTimeMovement>): Long {
        if (remainingUses(action, movements) <= 0) return 0L
        val headroom = DAILY_CAP_MILLIS - balance(movements).earnedMillis
        return action.rewardMillis.coerceAtMost(headroom).coerceAtLeast(0L)
    }
}
