package com.orbis.app.earn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusStateTest {

    private static final long START = 1_000_000L;
    private static final long DURATION = EarnAction.FOCUS_DURATION_MILLIS;

    private final FocusState running = new FocusState(START, false);

    @Test
    public void noSessionIsNeitherActiveNorComplete() {
        FocusState none = new FocusState();

        assertFalse(none.getActive());
        assertFalse(none.completeAt(Long.MAX_VALUE));
        assertEquals(0L, none.remainingMillis(START));
    }

    @Test
    public void remainingTimeIsWallClockArithmeticNotCountedTicks() {
        // The point of the rewrite: time the phone spent asleep still counts.
        assertEquals(DURATION, running.remainingMillis(START));
        assertEquals(DURATION - 60_000L, running.remainingMillis(START + 60_000L));
    }

    @Test
    public void aSessionCompletesExactlyAtItsEndNotBefore() {
        assertFalse(running.completeAt(START + DURATION - 1));
        assertTrue(running.completeAt(START + DURATION));
        assertTrue(running.completeAt(START + DURATION * 10));
    }

    @Test
    public void aBrokenSessionNeverCompletes() {
        FocusState broken = running.asBroken();

        assertFalse(broken.completeAt(START + DURATION * 10));
    }

    @Test
    public void remainingTimeIsClampedToTheSession() {
        // A clock that jumps backwards must not show more than the session length,
        // nor a negative once it is over.
        assertEquals(DURATION, running.remainingMillis(START - 3_600_000L));
        assertEquals(0L, running.remainingMillis(START + DURATION * 2));
    }
}
