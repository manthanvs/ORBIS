package com.orbis.app.earn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClearTimeTest {

    private static ClearTimeMovement earn(EarnAction action) {
        return earn(action, action.getRewardMillis());
    }

    private static ClearTimeMovement earn(EarnAction action, long millis) {
        return ClearTimeMovement.earned(action.name(), millis);
    }

    private static ClearTimeMovement spend(long millis) {
        return ClearTimeMovement.spent(millis);
    }

    private static List<ClearTimeMovement> repeat(int times, ClearTimeMovement movement) {
        List<ClearTimeMovement> movements = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            movements.add(movement);
        }
        return movements;
    }

    private static List<ClearTimeMovement> earnTimes(int times, EarnAction action) {
        List<ClearTimeMovement> movements = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            movements.add(earn(action));
        }
        return movements;
    }

    @Test
    public void anEmptyDayHasNoCredit() {
        ClearTimeBalance balance = ClearTime.balance(Collections.emptyList());

        assertEquals(0L, balance.getRemainingMillis());
        assertFalse(balance.getHasCredit());
    }

    @Test
    public void earningThenSpendingLeavesTheDifference() {
        ClearTimeBalance balance =
                ClearTime.balance(
                        Arrays.asList(earn(EarnAction.FOCUS_SESSION), spend(2 * 60_000L)));

        assertEquals(
                EarnAction.FOCUS_SESSION.getRewardMillis() - 2 * 60_000L,
                balance.getRemainingMillis());
        assertTrue(balance.getHasCredit());
    }

    @Test
    public void overspendingReadsAsZeroNeverAsADebt() {
        // The tunnel keeps charging while the feed is on screen and the last tick
        // can overshoot. A negative balance would then have to be paid off before
        // the next reward did anything, which is a punishment loop.
        ClearTimeBalance balance =
                ClearTime.balance(
                        Arrays.asList(earn(EarnAction.FOCUS_SESSION), spend(60 * 60_000L)));

        assertEquals(0L, balance.getRemainingMillis());
        assertFalse(balance.getHasCredit());
    }

    @Test
    public void earningsAreCappedAtTheDailyCeiling() {
        List<ClearTimeMovement> movements = earnTimes(20, EarnAction.FOCUS_SESSION);

        assertEquals(ClearTime.DAILY_CAP_MILLIS, ClearTime.balance(movements).getEarnedMillis());
    }

    @Test
    public void spendingDoesNotReOpenRoomToEarnPastTheCap() {
        // Earnings are capped, not the remainder - otherwise a user could spend to
        // the floor and grind back up to the ceiling all day.
        List<ClearTimeMovement> spentDown = earnTimes(20, EarnAction.FOCUS_SESSION);
        spentDown.add(spend(ClearTime.DAILY_CAP_MILLIS));

        assertEquals(0L, ClearTime.rewardFor(EarnAction.FOCUS_SESSION, spentDown));
        assertFalse(ClearTime.canEarn(EarnAction.FOCUS_SESSION, spentDown));
    }

    @Test
    public void remainingUsesCountsDownAndStopsAtZero() {
        EarnAction action = EarnAction.FOCUS_SESSION;

        assertEquals(action.getDailyLimit(), ClearTime.remainingUses(action, Collections.emptyList()));
        assertEquals(
                action.getDailyLimit() - 1,
                ClearTime.remainingUses(action, Collections.singletonList(earn(action))));
        assertEquals(
                0, ClearTime.remainingUses(action, earnTimes(action.getDailyLimit() + 5, action)));
    }

    @Test
    public void theLastRewardOfTheDayIsTrimmedToTheCapRatherThanExceedingIt() {
        // Five focus sessions is 25 minutes against a 30 minute cap, so the sixth
        // must pay 5 minutes, not its full 5... and never push the day to 35.
        List<ClearTimeMovement> nearCap = earnTimes(5, EarnAction.FOCUS_SESSION);
        long reward = ClearTime.rewardFor(EarnAction.FOCUS_SESSION, nearCap);

        assertTrue("reward " + reward + " should not overshoot the cap", reward <= 5 * 60_000L);

        List<ClearTimeMovement> atCap = new ArrayList<>(nearCap);
        atCap.add(earn(EarnAction.FOCUS_SESSION, reward));
        assertEquals(ClearTime.DAILY_CAP_MILLIS, ClearTime.balance(atCap).getEarnedMillis());
    }

    @Test
    public void aSpendMovementNeverCountsTowardsAnActionDailyLimit() {
        List<ClearTimeMovement> busy = repeat(10, spend(60_000L));

        assertEquals(
                EarnAction.FOCUS_SESSION.getDailyLimit(),
                ClearTime.remainingUses(EarnAction.FOCUS_SESSION, busy));
    }

    @Test
    public void orderOfMovementsDoesNotMatter() {
        List<ClearTimeMovement> forwards =
                Arrays.asList(earn(EarnAction.FOCUS_SESSION), spend(60_000L));
        List<ClearTimeMovement> backwards = new ArrayList<>(forwards);
        Collections.reverse(backwards);

        assertEquals(
                ClearTime.balance(forwards).getRemainingMillis(),
                ClearTime.balance(backwards).getRemainingMillis());
    }

    @Test
    public void everyActionIsWorthLessThanTheDailyCapOnItsOwn() {
        // An action that alone fills the day makes the cap the only rule that
        // matters and the rest of the economy decorative.
        for (EarnAction action : EarnAction.values()) {
            assertTrue(
                    action.name()
                            + " pays "
                            + action.getRewardMillis()
                            + " against a "
                            + ClearTime.DAILY_CAP_MILLIS
                            + " cap",
                    action.getRewardMillis() < ClearTime.DAILY_CAP_MILLIS);
            assertTrue(action.name() + " has no daily limit", action.getDailyLimit() > 0);
        }
    }
}
