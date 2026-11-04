package com.orbis.app.throttle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TcpFallbackTest {

    private static final String MORPHE = "app.morphe.android.youtube";

    /**
     * {@link TcpFallback} publishes its stand-downs through a
     * {@link com.orbis.app.util.StateHolder}, which posts to LiveData - and
     * that needs a main looper this JVM does not have. The rule runs those
     * posts inline instead.
     */
    @Rule public final InstantTaskExecutorRule liveData = new InstantTaskExecutorRule();

    @After
    public void tearDown() {
        TcpFallback.clear();
    }

    /** Feeds one sample a second at the given per-second rates; returns the last verdict. */
    private static boolean run(
            TcpStarvationDetector detector,
            int seconds,
            long tcpDropsPerSecond,
            long bytesPerSecond) {
        boolean starving = false;
        for (int s = 0; s <= seconds; s++) {
            starving =
                    detector.starving(
                            s * 1_000L, s * tcpDropsPerSecond, s * bytesPerSecond);
        }
        return starving;
    }

    // ---------------------------------------------------------------- detector

    @Test
    public void morpheOnTcpIsRecognised() {
        // Measured: 73 TCP drops against 135 KB of UDP in 24 s - about 3 drops and
        // 5.6 KB a second, with the Short frozen on its first frame.
        assertTrue(run(new TcpStarvationDetector(), 10, 3L, 5_600L));
    }

    @Test
    public void instagramStreamingOverQuicIsLeftAlone() {
        // Even squeezed to a 160 KB/s ceiling it pulls far more than the starving
        // threshold, whatever stray TCP it also tries.
        assertFalse(run(new TcpStarvationDetector(), 30, 3L, 60_000L));
    }

    @Test
    public void anIdleAppIsNotStarving() {
        // A reel fully buffered downloads nothing - but nothing knocks on TCP either.
        assertFalse(run(new TcpStarvationDetector(), 30, 0L, 0L));
    }

    @Test
    public void theFirstSecondsOfATunnelAreNeverJudged() {
        // Handshakes and retries look like starvation for a moment.
        assertFalse(run(new TcpStarvationDetector(), 3, 20L, 0L));
    }

    @Test
    public void aBurstOfTcpThatRecoversIsForgiven() {
        TcpStarvationDetector detector = new TcpStarvationDetector();
        long tcp = 0L;
        long bytes = 0L;
        boolean starving = false;
        for (int s = 0; s <= 20; s++) {
            // TCP knocks for three seconds at the start, then QUIC takes over.
            if (s < 3) {
                tcp += 10;
            } else {
                bytes += 80_000;
            }
            starving = detector.starving(s * 1_000L, tcp, bytes);
        }
        assertFalse(starving);
    }

    // ------------------------------------------------------------- stand-down

    @Test
    public void aStoodDownAppIsLeftAloneUntilItsTimeIsUp() {
        long duration = TcpFallback.standDown(MORPHE, 1_000L);

        assertEquals(TcpFallback.FIRST_STAND_DOWN_MILLIS, duration);
        assertTrue(TcpFallback.isStandingDown(MORPHE, 1_000L + duration - 1));
        assertFalse(TcpFallback.isStandingDown(MORPHE, 1_000L + duration));
    }

    @Test
    public void onlyTheAppThatTrippedIsStoodDown() {
        TcpFallback.standDown(MORPHE, 0L);
        assertFalse(TcpFallback.isStandingDown("com.instapro2.android", 1L));
    }

    @Test
    public void repeatTripsDoubleUpToAnHour() {
        List<Long> durations = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            durations.add(TcpFallback.standDown(MORPHE, 0L));
        }

        assertEquals(
                Arrays.asList(
                        10 * 60_000L,
                        20 * 60_000L,
                        40 * 60_000L,
                        60 * 60_000L,
                        60 * 60_000L,
                        60 * 60_000L),
                durations);
    }

    @Test
    public void expiredEntriesAreForgotten() {
        TcpFallback.standDown(MORPHE, 0L);
        assertTrue(TcpFallback.active(TcpFallback.MAX_STAND_DOWN_MILLIS).isEmpty());
    }
}
