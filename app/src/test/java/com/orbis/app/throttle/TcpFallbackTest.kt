package com.orbis.app.throttle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpFallbackTest {

    @After
    fun tearDown() = TcpFallback.clear()

    /** Feeds one sample a second at the given per-second rates; returns the last verdict. */
    private fun run(
        detector: TcpStarvationDetector,
        seconds: Int,
        tcpDropsPerSecond: Long,
        bytesPerSecond: Long,
    ): Boolean {
        var starving = false
        for (s in 0..seconds) {
            starving = detector.starving(
                nowMillis = s * 1_000L,
                tcpDropped = s * tcpDropsPerSecond,
                bytesIn = s * bytesPerSecond,
            )
        }
        return starving
    }

    // ---------------------------------------------------------------- detector

    @Test
    fun `morphe on tcp is recognised`() {
        // Measured: 73 TCP drops against 135 KB of UDP in 24 s - about 3 drops and
        // 5.6 KB a second, with the Short frozen on its first frame.
        assertTrue(run(TcpStarvationDetector(), 10, tcpDropsPerSecond = 3, bytesPerSecond = 5_600))
    }

    @Test
    fun `instagram streaming over quic is left alone`() {
        // Even squeezed to a 160 KB/s ceiling it pulls far more than the starving
        // threshold, whatever stray TCP it also tries.
        assertFalse(run(TcpStarvationDetector(), 30, tcpDropsPerSecond = 3, bytesPerSecond = 60_000))
    }

    @Test
    fun `an idle app is not starving`() {
        // A reel fully buffered downloads nothing - but nothing knocks on TCP either.
        assertFalse(run(TcpStarvationDetector(), 30, tcpDropsPerSecond = 0, bytesPerSecond = 0))
    }

    @Test
    fun `the first seconds of a tunnel are never judged`() {
        // Handshakes and retries look like starvation for a moment.
        assertFalse(run(TcpStarvationDetector(), 3, tcpDropsPerSecond = 20, bytesPerSecond = 0))
    }

    @Test
    fun `a burst of tcp that recovers is forgiven`() {
        val detector = TcpStarvationDetector()
        var tcp = 0L
        var bytes = 0L
        var starving = false
        for (s in 0..20) {
            // TCP knocks for three seconds at the start, then QUIC takes over.
            if (s < 3) tcp += 10 else bytes += 80_000
            starving = detector.starving(s * 1_000L, tcp, bytes)
        }
        assertFalse(starving)
    }

    // ------------------------------------------------------------- stand-down

    @Test
    fun `a stood-down app is left alone until its time is up`() {
        val duration = TcpFallback.standDown("app.morphe.android.youtube", 1_000L)

        assertEquals(TcpFallback.FIRST_STAND_DOWN_MILLIS, duration)
        assertTrue(TcpFallback.isStandingDown("app.morphe.android.youtube", 1_000L + duration - 1))
        assertFalse(TcpFallback.isStandingDown("app.morphe.android.youtube", 1_000L + duration))
    }

    @Test
    fun `only the app that tripped is stood down`() {
        TcpFallback.standDown("app.morphe.android.youtube", 0L)
        assertFalse(TcpFallback.isStandingDown("com.instapro2.android", 1L))
    }

    @Test
    fun `repeat trips double, up to an hour`() {
        val pkg = "app.morphe.android.youtube"
        val durations = (1..6).map { TcpFallback.standDown(pkg, 0L) }

        assertEquals(
            listOf(10L, 20L, 40L, 60L, 60L, 60L).map { it * 60_000L },
            durations,
        )
    }

    @Test
    fun `expired entries are forgotten`() {
        TcpFallback.standDown("app.morphe.android.youtube", 0L)
        assertTrue(TcpFallback.active(TcpFallback.MAX_STAND_DOWN_MILLIS).isEmpty())
    }
}
