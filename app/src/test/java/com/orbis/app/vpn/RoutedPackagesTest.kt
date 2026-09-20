package com.orbis.app.vpn

import com.orbis.app.surface.BrowserPackages
import com.orbis.app.usage.TargetApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tunnel's allow-list is the strongest form the WhatsApp invariant takes: a
 * package absent from here cannot be routed at all, regardless of what any
 * detection or throttle logic later decides.
 */
class RoutedPackagesTest {

    private val routed = OrbisVpnService.routedPackages()

    @Test
    fun `whatsapp is never routed through the tunnel`() {
        assertFalse(routed.contains(TargetApp.WHATSAPP.packageName))
    }

    @Test
    fun `the three throttleable apps are routed`() {
        assertTrue(routed.contains(TargetApp.INSTAGRAM.packageName))
        assertTrue(routed.contains(TargetApp.YOUTUBE.packageName))
        assertTrue(routed.contains(TargetApp.SNAPCHAT.packageName))
    }

    @Test
    fun `browsers are routed so browser shorts can actually be throttled`() {
        // Detection of BROWSER_SHORT_VIDEO is a no-op unless the browser's traffic
        // reaches the tunnel. youtube.com/shorts opens in a browser on many devices.
        BrowserPackages.ALL.forEach { browser ->
            assertTrue("expected $browser to be routed", routed.contains(browser))
        }
    }

    @Test
    fun `routed list has no duplicates`() {
        assertTrue(routed.size == routed.toSet().size)
    }

    @Test
    fun `every routed package is either throttleable or a browser`() {
        val expected = TargetApp.throttleable.flatMap { it.allPackages }.toSet() + BrowserPackages.ALL
        assertTrue(routed.toSet() == expected)
    }

    @Test
    fun `modded builds of the target apps can be routed`() {
        // InstaPro and Morphe were the user's actual Instagram and YouTube on the
        // test device. Absent from here, their feeds could never be slowed.
        assertTrue(routed.contains("com.instapro2.android"))
        assertTrue(routed.contains("app.morphe.android.youtube"))
    }
}
