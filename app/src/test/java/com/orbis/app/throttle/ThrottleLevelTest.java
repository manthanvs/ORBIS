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
    public void theBufferIsHandedBackEveryMidnightHoweverHeavyTheWeekWas() {
        // Nothing scrolled yet today is level 1, even on top of two and a half
        // hours a day for a week. The old model floored the level at the week's
        // and so opened the morning at Immune.
        assertEquals(
                ThrottleLevel.NUDGE,
                ThrottleEngine.levelFor(minutes("com.instagram.android", 0), 150 * 60_000L));
    }

    @Test
    public void aHeavyWeekClimbsTheLadderFasterThanALightOneDoes() {
        // The same 30 minutes today, against two different weeks. This is the
        // whole point of the ramp: heavier pattern, sooner friction.
        UsageProfile today = minutes("com.instagram.android", 30);

        assertEquals(ThrottleLevel.DRAG, ThrottleEngine.levelFor(today, 0L));
        assertEquals(ThrottleLevel.IMMUNE, ThrottleEngine.levelFor(today, 150 * 60_000L));
    }

    @Test
    public void theRampIsTheWeeksOwnLevelSoItEasesOffWithThePatternToo() {
        assertEquals(1, ThrottleEngine.rampFor(0L));
        assertEquals(2, ThrottleEngine.rampFor(20 * 60_000L));
        assertEquals(3, ThrottleEngine.rampFor(45 * 60_000L));
        assertEquals(4, ThrottleEngine.rampFor(90 * 60_000L));
        assertEquals(5, ThrottleEngine.rampFor(150 * 60_000L));

        // Not a ratchet: a lighter week genuinely slows the climb back down.
        assertEquals(1, ThrottleEngine.rampFor(19 * 60_000L));
    }

    @Test
    public void theLadderIsWalkedStepByStepEvenAtTheHeaviestRamp() {
        // Gradual, not a jump to the top: the user sees themselves climbing.
        long heavyWeek = 150 * 60_000L;
        String app = "com.instagram.android";

        assertEquals(ThrottleLevel.NUDGE, ThrottleEngine.levelFor(minutes(app, 3), heavyWeek));
        assertEquals(ThrottleLevel.DRAG, ThrottleEngine.levelFor(minutes(app, 5), heavyWeek));
        assertEquals(ThrottleLevel.STUTTER, ThrottleEngine.levelFor(minutes(app, 10), heavyWeek));
        assertEquals(ThrottleLevel.GRIND, ThrottleEngine.levelFor(minutes(app, 20), heavyWeek));
        assertEquals(ThrottleLevel.IMMUNE, ThrottleEngine.levelFor(minutes(app, 30), heavyWeek));
    }

    @Test
    public void aQuietWeekDoesNotHoldAHeavyDayDown() {
        // Today always counts on its own - a light ramp is still a climb.
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
