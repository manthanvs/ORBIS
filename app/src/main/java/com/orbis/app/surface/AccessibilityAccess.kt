package com.orbis.app.surface

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Like usage access, an accessibility service can only be switched on by the user
 * in Settings - there is no runtime request, and adb cannot grant it on many OEM
 * builds. So: check the setting, and deep-link if it is off.
 */
object AccessibilityAccess {

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, OrbisAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        // The stored list uses the short form on most builds and the full form on
        // some, so accept either rather than guessing.
        val short = component.flattenToShortString()
        val full = component.flattenToString()
        return enabled.split(':').any { it.equals(short, true) || it.equals(full, true) }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
