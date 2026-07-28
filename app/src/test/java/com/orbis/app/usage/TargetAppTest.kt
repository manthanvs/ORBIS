package com.orbis.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppTest {

    @Test
    fun `whatsapp is never throttleable`() {
        // Hard invariant from CLAUDE.md: WhatsApp is a communication tool, not
        // passive-scroll content. If this test ever fails, the throttle engine is
        // about to slow down someone's messaging.
        assertFalse(TargetApp.WHATSAPP.throttled)
        assertFalse(TargetApp.isThrottleable("com.whatsapp"))
        assertFalse(TargetApp.throttleable.contains(TargetApp.WHATSAPP))
    }

    @Test
    fun `only instagram youtube and snapchat are throttleable`() {
        assertEquals(
            setOf(TargetApp.INSTAGRAM, TargetApp.YOUTUBE, TargetApp.SNAPCHAT),
            TargetApp.throttleable.toSet(),
        )
    }

    @Test
    fun `resolves known packages`() {
        assertEquals(TargetApp.INSTAGRAM, TargetApp.fromPackage("com.instagram.android"))
        assertEquals(TargetApp.YOUTUBE, TargetApp.fromPackage("com.google.android.youtube"))
        assertEquals(TargetApp.SNAPCHAT, TargetApp.fromPackage("com.snapchat.android"))
        assertEquals(TargetApp.WHATSAPP, TargetApp.fromPackage("com.whatsapp"))
    }

    @Test
    fun `untracked packages are unknown and not throttleable`() {
        assertNull(TargetApp.fromPackage("com.android.chrome"))
        assertFalse(TargetApp.isThrottleable("com.android.chrome"))
    }

    @Test
    fun `package names are unique`() {
        val packages = TargetApp.entries.map { it.packageName }
        assertTrue(packages.size == packages.toSet().size)
    }
}
