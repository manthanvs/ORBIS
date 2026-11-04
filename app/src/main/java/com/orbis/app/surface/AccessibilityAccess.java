package com.orbis.app.surface;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Like usage access, an accessibility service can only be switched on by the
 * user in Settings - there is no runtime request, and adb cannot grant it on
 * many OEM builds. So: check the setting, and deep-link if it is off.
 */
public final class AccessibilityAccess {

    private AccessibilityAccess() {
        // No instances: two helpers over a system setting.
    }

    public static boolean isEnabled(Context context) {
        ComponentName component = new ComponentName(context, OrbisAccessibilityService.class);
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }

        // The stored list uses the short form on most builds and the full form on
        // some, so accept either rather than guessing.
        String shortForm = component.flattenToShortString();
        String fullForm = component.flattenToString();
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(shortForm) || entry.equalsIgnoreCase(fullForm)) {
                return true;
            }
        }
        return false;
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
}
