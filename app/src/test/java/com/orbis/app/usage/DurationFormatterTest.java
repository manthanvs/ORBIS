package com.orbis.app.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DurationFormatterTest {

    @Test
    public void formatsSecondsOnly() {
        assertEquals("45s", DurationFormatter.format(45_000L));
    }

    @Test
    public void formatsThePhase1AcceptanceCase() {
        // "A minute of Instagram use is correctly logged."
        assertEquals("1m 0s", DurationFormatter.format(60_000L));
    }

    @Test
    public void formatsMinutesAndSeconds() {
        assertEquals("2m 30s", DurationFormatter.format(150_000L));
    }

    @Test
    public void formatsHoursAndMinutesAndDropsSeconds() {
        assertEquals("1h 30m", DurationFormatter.format(5_400_000L));
    }

    @Test
    public void zeroAndNegativeRenderAsZero() {
        assertEquals("0m", DurationFormatter.format(0L));
        assertEquals("0m", DurationFormatter.format(-5_000L));
    }
}
