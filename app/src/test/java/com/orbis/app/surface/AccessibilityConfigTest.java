package com.orbis.app.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orbis.app.usage.TargetApp;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps {@code accessibility_service_config.xml} in step with the code.
 *
 * <p>{@code packageNames} there is the privacy boundary, and it is the one list
 * the compiler cannot check: the code can know about a package perfectly well and
 * still never receive a single event from it. That is exactly how Morphe and
 * InstaPro - the test device's actual YouTube and Instagram - went undetected.
 */
public class AccessibilityConfigTest {

    private static final Pattern PACKAGE_NAMES =
            Pattern.compile("android:packageNames=\"([^\"]*)\"");

    private final Set<String> observed = readObserved();

    private static Set<String> readObserved() {
        // Unit tests run with the module as the working directory, but be
        // tolerant of being launched from the project root too.
        String relative = "src/main/res/xml/accessibility_service_config.xml";
        File file = new File(relative);
        if (!file.exists()) {
            file = new File("app/" + relative);
        }

        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + file.getPath(), failure);
        }

        Matcher matcher = PACKAGE_NAMES.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("packageNames not found in " + file.getPath());
        }

        Set<String> names = new LinkedHashSet<>();
        for (String name : matcher.group(1).split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    @Test
    public void everyThrottleablePackageVariantsIncludedIsObserved() {
        for (TargetApp app : TargetApp.getThrottleable()) {
            for (String packageName : app.getAllPackages()) {
                assertTrue(
                        packageName + " is missing from packageNames",
                        observed.contains(packageName));
            }
        }
    }

    @Test
    public void everyBrowserIsObserved() {
        for (String browser : BrowserPackages.ALL) {
            assertTrue(browser + " is missing from packageNames", observed.contains(browser));
        }
    }

    @Test
    public void theRuntimeObservedSetIsExactlyWhatTheConfigDeclares() {
        // OrbisAccessibilityService.observeInstalledBuilds() overwrites
        // packageNames with VariantDiscovery's result plus the browsers, so the
        // XML is only the privacy boundary if that runtime set agrees with it.
        // It did not: discovery returned every TargetApp package, WhatsApp
        // included, and the service widened itself to observe it.
        Set<String> runtime = new HashSet<>(BrowserPackages.ALL);
        runtime.addAll(TargetApp.getThrottleablePackages());

        assertEquals(observed, runtime);
        assertFalse(runtime.contains(TargetApp.WHATSAPP.getPackageName()));
    }

    @Test
    public void whatsappIsNeverObserved() {
        assertFalse(observed.contains(TargetApp.WHATSAPP.getPackageName()));
    }

    @Test
    public void nothingIsObservedThatOrbisDoesNotActOn() {
        // The boundary is only honest if it is also tight.
        Set<String> expected = new HashSet<>(BrowserPackages.ALL);
        for (TargetApp app : TargetApp.getThrottleable()) {
            expected.addAll(app.getAllPackages());
        }
        Set<String> unexpected = new HashSet<>(observed);
        unexpected.removeAll(expected);
        assertTrue("unexpected: " + unexpected, unexpected.isEmpty());
    }
}
