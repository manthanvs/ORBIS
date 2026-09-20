package com.orbis.app.surface

import com.orbis.app.usage.TargetApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `accessibility_service_config.xml` in step with the code.
 *
 * `packageNames` there is the privacy boundary, and it is the one list the
 * compiler cannot check: the Kotlin can know about a package perfectly well and
 * still never receive a single event from it. That is exactly how Morphe and
 * InstaPro - the test device's actual YouTube and Instagram - went undetected.
 */
class AccessibilityConfigTest {

    private val observed: Set<String> by lazy {
        // Unit tests run with the module as the working directory, but be
        // tolerant of being launched from the project root too.
        val relative = "src/main/res/xml/accessibility_service_config.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.exists() }

        Regex("""android:packageNames="([^"]*)"""")
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: error("packageNames not found in ${file.path}")
    }

    @Test
    fun `every throttleable package, variants included, is observed`() {
        TargetApp.throttleable.flatMap { it.allPackages }.forEach { packageName ->
            assertTrue("$packageName is missing from packageNames", packageName in observed)
        }
    }

    @Test
    fun `every browser is observed`() {
        BrowserPackages.ALL.forEach { browser ->
            assertTrue("$browser is missing from packageNames", browser in observed)
        }
    }

    @Test
    fun `whatsapp is never observed`() {
        assertFalse(TargetApp.WHATSAPP.packageName in observed)
    }

    @Test
    fun `nothing is observed that ORBIS does not act on`() {
        // The boundary is only honest if it is also tight.
        val expected = TargetApp.throttleable.flatMap { it.allPackages }.toSet() + BrowserPackages.ALL
        assertTrue("unexpected: ${observed - expected}", (observed - expected).isEmpty())
    }
}
