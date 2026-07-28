package com.orbis.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageProfileTest {

    @Test
    fun `ranks apps by time spent descending`() {
        val profile = UsageProfile.from(
            mapOf(
                "com.instagram.android" to 30_000L,
                "com.google.android.youtube" to 90_000L,
                "com.snapchat.android" to 60_000L,
            )
        )

        assertEquals(
            listOf(TargetApp.YOUTUBE, TargetApp.SNAPCHAT, TargetApp.INSTAGRAM),
            profile.entries.map { it.app },
        )
    }

    @Test
    fun `drops packages ORBIS does not target`() {
        val profile = UsageProfile.from(
            mapOf(
                "com.instagram.android" to 10_000L,
                "com.android.chrome" to 999_000L,
                "com.spotify.music" to 500_000L,
            )
        )

        assertEquals(listOf(TargetApp.INSTAGRAM), profile.entries.map { it.app })
        assertEquals(10_000L, profile.totalMillis)
    }

    @Test
    fun `heaviestThrottleable skips whatsapp even when it is the top app`() {
        // The invariant that matters: heaviest overall is WhatsApp, but the
        // throttle engine must be handed Instagram instead.
        val profile = UsageProfile.from(
            mapOf(
                "com.whatsapp" to 600_000L,
                "com.instagram.android" to 120_000L,
                "com.snapchat.android" to 60_000L,
            )
        )

        assertEquals(TargetApp.WHATSAPP, profile.entries.first().app)
        assertEquals(TargetApp.INSTAGRAM, profile.heaviestThrottleable?.app)
    }

    @Test
    fun `heaviestThrottleable is null when only whatsapp was used`() {
        val profile = UsageProfile.from(mapOf("com.whatsapp" to 600_000L))

        assertNull(profile.heaviestThrottleable)
    }

    @Test
    fun `totalMillis sums every tracked app including protected ones`() {
        val profile = UsageProfile.from(
            mapOf(
                "com.whatsapp" to 1_000L,
                "com.instagram.android" to 2_000L,
            )
        )

        assertEquals(3_000L, profile.totalMillis)
    }

    @Test
    fun `durationOf reports zero for an unused app`() {
        val profile = UsageProfile.from(mapOf("com.instagram.android" to 2_000L))

        assertEquals(2_000L, profile.durationOf(TargetApp.INSTAGRAM))
        assertEquals(0L, profile.durationOf(TargetApp.YOUTUBE))
    }

    @Test
    fun `empty input yields an empty profile`() {
        val profile = UsageProfile.from(emptyMap())

        assertTrue(profile.entries.isEmpty())
        assertEquals(0L, profile.totalMillis)
        assertNull(profile.heaviestThrottleable)
    }
}
