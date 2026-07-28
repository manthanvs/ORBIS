package com.orbis.app.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {

    @Test
    fun `formats seconds only`() {
        assertEquals("45s", DurationFormatter.format(45_000L))
    }

    @Test
    fun `formats the phase 1 acceptance case`() {
        // "A minute of Instagram use is correctly logged."
        assertEquals("1m 0s", DurationFormatter.format(60_000L))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("2m 30s", DurationFormatter.format(150_000L))
    }

    @Test
    fun `formats hours and minutes and drops seconds`() {
        assertEquals("1h 30m", DurationFormatter.format(5_400_000L))
    }

    @Test
    fun `zero and negative render as zero`() {
        assertEquals("0m", DurationFormatter.format(0L))
        assertEquals("0m", DurationFormatter.format(-5_000L))
    }
}
