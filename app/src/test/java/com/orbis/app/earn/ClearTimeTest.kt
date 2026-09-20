package com.orbis.app.earn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearTimeTest {

    private fun earn(action: EarnAction, millis: Long = action.rewardMillis) =
        ClearTimeMovement(actionName = action.name, earnedMillis = millis)

    private fun spend(millis: Long) =
        ClearTimeMovement(actionName = ClearTimeMovement.SPEND, spentMillis = millis)

    @Test
    fun `an empty day has no credit`() {
        val balance = ClearTime.balance(emptyList())

        assertEquals(0L, balance.remainingMillis)
        assertFalse(balance.hasCredit)
    }

    @Test
    fun `earning then spending leaves the difference`() {
        val balance = ClearTime.balance(
            listOf(earn(EarnAction.FOCUS_SESSION), spend(2 * 60_000L))
        )

        assertEquals(EarnAction.FOCUS_SESSION.rewardMillis - 2 * 60_000L, balance.remainingMillis)
        assertTrue(balance.hasCredit)
    }

    @Test
    fun `overspending reads as zero, never as a debt`() {
        // The tunnel keeps charging while the feed is on screen and the last tick
        // can overshoot. A negative balance would then have to be paid off before
        // the next reward did anything, which is a punishment loop.
        val balance = ClearTime.balance(
            listOf(earn(EarnAction.FOCUS_SESSION), spend(60 * 60_000L))
        )

        assertEquals(0L, balance.remainingMillis)
        assertFalse(balance.hasCredit)
    }

    @Test
    fun `earnings are capped at the daily ceiling`() {
        val movements = List(20) { earn(EarnAction.FOCUS_SESSION) }

        assertEquals(ClearTime.DAILY_CAP_MILLIS, ClearTime.balance(movements).earnedMillis)
    }

    @Test
    fun `spending does not re-open room to earn past the cap`() {
        // Earnings are capped, not the remainder - otherwise a user could spend to
        // the floor and grind back up to the ceiling all day.
        val atCap = List(20) { earn(EarnAction.FOCUS_SESSION) }
        val spentDown = atCap + spend(ClearTime.DAILY_CAP_MILLIS)

        assertEquals(0L, ClearTime.rewardFor(EarnAction.FOCUS_SESSION, spentDown))
        assertFalse(ClearTime.canEarn(EarnAction.FOCUS_SESSION, spentDown))
    }

    @Test
    fun `remaining uses counts down and stops at zero`() {
        val action = EarnAction.FOCUS_SESSION

        assertEquals(action.dailyLimit, ClearTime.remainingUses(action, emptyList()))
        assertEquals(
            action.dailyLimit - 1,
            ClearTime.remainingUses(action, listOf(earn(action))),
        )
        assertEquals(
            0,
            ClearTime.remainingUses(action, List(action.dailyLimit + 5) { earn(action) }),
        )
    }

    @Test
    fun `the last reward of the day is trimmed to the cap rather than exceeding it`() {
        // Five focus sessions is 25 minutes against a 30 minute cap, so the sixth
        // must pay 5 minutes, not its full 5... and never push the day to 35.
        val nearCap = List(5) { earn(EarnAction.FOCUS_SESSION) }
        val reward = ClearTime.rewardFor(EarnAction.FOCUS_SESSION, nearCap)

        assertTrue("reward $reward should not overshoot the cap", reward <= 5 * 60_000L)
        assertEquals(
            ClearTime.DAILY_CAP_MILLIS,
            ClearTime.balance(nearCap + earn(EarnAction.FOCUS_SESSION, reward)).earnedMillis,
        )
    }

    @Test
    fun `a spend movement never counts towards an action's daily limit`() {
        val busy = List(10) { spend(60_000L) }

        assertEquals(
            EarnAction.FOCUS_SESSION.dailyLimit,
            ClearTime.remainingUses(EarnAction.FOCUS_SESSION, busy),
        )
    }

    @Test
    fun `order of movements does not matter`() {
        val forwards = listOf(earn(EarnAction.FOCUS_SESSION), spend(60_000L))
        val backwards = forwards.reversed()

        assertEquals(
            ClearTime.balance(forwards).remainingMillis,
            ClearTime.balance(backwards).remainingMillis,
        )
    }

    @Test
    fun `every action is worth less than the daily cap on its own`() {
        // An action that alone fills the day makes the cap the only rule that
        // matters and the rest of the economy decorative.
        for (action in EarnAction.entries) {
            assertTrue(
                "${action.name} pays ${action.rewardMillis} against a ${ClearTime.DAILY_CAP_MILLIS} cap",
                action.rewardMillis < ClearTime.DAILY_CAP_MILLIS,
            )
            assertTrue("${action.name} has no daily limit", action.dailyLimit > 0)
        }
    }
}
