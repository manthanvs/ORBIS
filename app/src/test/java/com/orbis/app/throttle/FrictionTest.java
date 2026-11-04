package com.orbis.app.throttle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orbis.app.surface.Surface;
import com.orbis.app.usage.UsageProfile;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class FrictionTest {

    private final Friction pulse =
            new Friction(
                    300L, // delayMillis
                    5_000L, // periodMillis
                    2_000L, // squeezeMillis
                    16_000L, // squeezeBytesPerSecond
                    0L); // openBytesPerSecond

    // ------------------------------------------------------------------ schedule

    @Test
    public void everyCycleStartsWithItsSqueeze() {
        // So the drag lands the moment a feed opens, not a few seconds in.
        assertTrue(pulse.squeezingAt(0L));
        assertTrue(pulse.squeezingAt(1_999L));
        assertFalse(pulse.squeezingAt(2_000L));
        assertFalse(pulse.squeezingAt(4_999L));
    }

    @Test
    public void thePulseRepeatsEveryPeriod() {
        assertTrue(pulse.squeezingAt(5_000L));
        assertTrue(pulse.squeezingAt(5_000L * 100 + 500L));
        assertFalse(pulse.squeezingAt(5_000L * 100 + 3_000L));
    }

    @Test
    public void theSqueezeCapsDownloadsAndTheRestOfTheCycleDoesNot() {
        assertEquals(16_000L, pulse.downloadCeilingAt(500L));
        assertEquals(0L, pulse.downloadCeilingAt(3_000L));
    }

    @Test
    public void requestsAreOnlyDelayedWhileSqueezedSoNormalMeansNormal() {
        assertEquals(300L, pulse.delayAt(500L));
        assertEquals(0L, pulse.delayAt(3_000L));
    }

    @Test
    public void noFrictionNeverSqueezesOrDelays() {
        assertFalse(Friction.NONE.squeezingAt(0L));
        assertEquals(0L, Friction.NONE.delayAt(0L));
        assertEquals(0L, Friction.NONE.downloadCeilingAt(0L));
        assertFalse(Friction.NONE.getActive());
    }

    @Test
    public void frictionSurvivesTheTripThroughAnIntentExtra() {
        assertEquals(pulse, Friction.fromArray(pulse.toArray()));
        assertEquals(Friction.NONE, Friction.fromArray(null));
        assertEquals(Friction.NONE, Friction.fromArray(new long[] {1L, 2L}));
    }

    // ----------------------------------------------------------------- policing

    @Test
    public void anUnlimitedCeilingPassesEverything() {
        TokenBucket bucket = new TokenBucket();
        for (int i = 0; i < 1_000; i++) {
            assertTrue(bucket.tryTake(1_400, 0L, i * 1_000L));
        }
    }

    @Test
    public void aSqueezeStartsEmptyRatherThanSpendingASavedUpBurst() {
        TokenBucket bucket = new TokenBucket();
        assertTrue(bucket.tryTake(1_400, 0L, 0L));

        // The first packet of the squeeze finds nothing banked from the open phase.
        assertFalse(bucket.tryTake(1_400, 16_000L, 1_000L));
    }

    @Test
    public void overASecondTheSqueezePassesRoughlyItsRateAndNoMore() {
        TokenBucket bucket = new TokenBucket();
        long rate = 16_000L;
        long passed = 0L;
        // A packet every millisecond for one second: 1.4 MB offered.
        for (long ms = 0L; ms <= 1_000L; ms++) {
            if (bucket.tryTake(1_400, rate, ms * 1_000_000L)) {
                passed += 1_400;
            }
        }
        assertTrue("passed " + passed, passed <= rate + 1_500);
        assertTrue("passed " + passed, passed >= rate / 2);
    }

    @Test
    public void aTrickleAlwaysLetsAFullPacketThroughEventually() {
        // A rate below one packet per burst window must still pass something, or
        // the squeeze would be a cut-off.
        TokenBucket bucket = new TokenBucket();
        bucket.tryTake(1_400, 2_000L, 0L);
        assertTrue(bucket.tryTake(1_400, 2_000L, 1_000_000_000L));
    }

    // ------------------------------------------------------------------ engine

    private static UsageProfile minutes(Object... pairs) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            totals.put((String) pairs[i], ((Integer) pairs[i + 1]) * 60_000L);
        }
        return UsageProfile.from(totals);
    }

    private static Friction frictionFor(Surface surface, UsageProfile profile) {
        return ThrottleEngine.ruleFor(surface, profile).getFriction();
    }

    @Test
    public void everyThrottledSurfacePulsesOnTheSamePeriod() {
        for (Surface surface : Surface.values()) {
            if (!surface.getThrottled()) {
                continue;
            }
            Friction friction = frictionFor(surface, UsageProfile.EMPTY);
            assertEquals(ThrottleEngine.PULSE_PERIOD_MILLIS, friction.getPeriodMillis());
            assertTrue(friction.getSqueezeMillis() > 0L);
        }
    }

    @Test
    public void squeezesGrowWithTheDayShortFormTime() {
        Friction light = frictionFor(Surface.REELS, UsageProfile.EMPTY);
        Friction heavy = frictionFor(Surface.REELS, minutes("com.instagram.android", 180));

        assertEquals(ThrottleEngine.MIN_SQUEEZE_MILLIS, light.getSqueezeMillis());
        assertEquals(ThrottleEngine.MAX_SQUEEZE_MILLIS, heavy.getSqueezeMillis());
    }

    @Test
    public void switchingAppsDoesNotResetTheFriction() {
        // Measured on device: two hours of Morphe, six minutes of Snapchat - and
        // Spotlight got the lightest friction there is. The habit is the user's.
        UsageProfile profile =
                minutes("app.morphe.android.youtube", 180, "com.snapchat.android", 6);

        assertEquals(
                ThrottleEngine.MAX_SQUEEZE_MILLIS,
                frictionFor(Surface.SPOTLIGHT, profile).getSqueezeMillis());
    }

    @Test
    public void whatsappTimeNeverAddsFriction() {
        Friction friction = frictionFor(Surface.REELS, minutes("com.whatsapp", 300));

        assertEquals(ThrottleEngine.MIN_SQUEEZE_MILLIS, friction.getSqueezeMillis());
    }

    @Test
    public void aSqueezeAlwaysLeavesPartOfEveryCycleAtFullSpeed() {
        Friction heaviest =
                frictionFor(Surface.SHORTS, minutes("com.google.android.youtube", 24 * 60));

        assertTrue(heaviest.getSqueezeMillis() < heaviest.getPeriodMillis());
        assertTrue(heaviest.squeezingAt(0L));
        assertFalse(heaviest.squeezingAt(heaviest.getPeriodMillis() - 1L));
    }

    @Test
    public void aSqueezeIsATrickleNeverACutOff() {
        Friction friction = frictionFor(Surface.REELS, UsageProfile.EMPTY);
        assertTrue(friction.getSqueezeBytesPerSecond() > 0L);
    }

    @Test
    public void theGapsBetweenSqueezesAreCappedTighterOnAHeavierDay() {
        // Uncapped gaps let a pre-loading app refill a whole reel between squeezes.
        Friction light = frictionFor(Surface.REELS, UsageProfile.EMPTY);
        Friction heavy = frictionFor(Surface.REELS, minutes("com.instagram.android", 180));

        assertEquals(
                ThrottleEngine.LIGHT_OPEN_BYTES_PER_SECOND, light.getOpenBytesPerSecond());
        assertEquals(
                ThrottleEngine.HEAVY_OPEN_BYTES_PER_SECOND, heavy.getOpenBytesPerSecond());
        assertTrue(heavy.getOpenBytesPerSecond() > heavy.getSqueezeBytesPerSecond());
    }
}
