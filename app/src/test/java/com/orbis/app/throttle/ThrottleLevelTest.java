package com.orbis.app.throttle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.orbis.app.surface.Surface;
import com.orbis.app.usage.UsageProfile;

import org.junit.Test;

import java.util.Collections;

public class ThrottleLevelTest {

    private static UsageProfile minutes(String packageName, int min) {
        return UsageProfile.from(Collections.singletonMap(packageName, min * 60_000L));
    }

    private static ThrottleLevel levelAt(int minutes) {
        return ThrottleEngine.levelFor(minutes("com.instagram.android", minutes));
    }

    @Test
    public void theLadderClimbsWithTheDayAndTopsOutAtImmune() {
        assertEquals(ThrottleLevel.NUDGE, levelAt(0));
        assertEquals(ThrottleLevel.NUDGE, levelAt(19));
        assertEquals(ThrottleLevel.DRAG, levelAt(20));
        assertEquals(ThrottleLevel.STUTTER, levelAt(45));
        assertEquals(ThrottleLevel.GRIND, levelAt(90));
        assertEquals(ThrottleLevel.IMMUNE, levelAt(150));
        assertEquals(ThrottleLevel.IMMUNE, levelAt(10_000));
    }

    @Test
    public void everyStepUpSqueezesLongerAndCapsTheGapTighter() {
        ThrottleLevel[] levels = ThrottleLevel.values();
        for (int i = 1; i < levels.length; i++) {
            ThrottleLevel lower = levels[i - 1];
            ThrottleLevel higher = levels[i];
            Friction a = lower.getFriction();
            Friction b = higher.getFriction();
            assertTrue(
                    lower + " -> " + higher + " squeeze",
                    b.getSqueezeMillis() > a.getSqueezeMillis());
            assertTrue(
                    lower + " -> " + higher + " gap",
                    b.getOpenBytesPerSecond() < a.getOpenBytesPerSecond());
            assertTrue(
                    lower + " -> " + higher + " delay",
                    b.getDelayMillis() >= a.getDelayMillis());
        }
    }

    @Test
    public void evenImmuneLeavesAGapAndATrickle() {
        // The top of the ladder is still friction, not a block - the invariant
        // the whole thesis rests on.
        Friction friction = ThrottleLevel.IMMUNE.getFriction();

        assertTrue(friction.getSqueezeMillis() < friction.getPeriodMillis());
        assertTrue(friction.getSqueezeBytesPerSecond() > 0L);
        assertTrue(friction.getOpenBytesPerSecond() > friction.getSqueezeBytesPerSecond());
    }

    @Test
    public void theRecentAverageFloorsTheLevelSoMidnightDoesNotResetIt() {
        // Two hours a day for a week, five minutes so far today.
        ThrottleLevel level =
                ThrottleEngine.levelFor(minutes("com.instagram.android", 5), 120 * 60_000L);

        // 120 minutes a day is Grind - and nowhere near the Nudge that five
        // minutes alone would have earned.
        assertEquals(ThrottleLevel.GRIND, level);
    }

    @Test
    public void aQuietWeekDoesNotHoldAHeavyDayDown() {
        // The floor only ever raises the level; today still counts on its own.
        ThrottleLevel level =
                ThrottleEngine.levelFor(minutes("com.instagram.android", 200), 0L);

        assertEquals(ThrottleLevel.IMMUNE, level);
    }

    @Test
    public void theRuleNamesTheLevelItApplied() {
        ThrottleRule rule =
                ThrottleEngine.ruleFor(Surface.REELS, minutes("com.instagram.android", 200));

        assertTrue(rule.getReason(), rule.getReason().contains("level 5"));
        assertTrue(rule.getReason(), rule.getReason().contains("Immune"));
        assertEquals(ThrottleLevel.IMMUNE.getFriction(), rule.getFriction());
    }
}
