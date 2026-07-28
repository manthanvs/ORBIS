package com.orbis.app.usage

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * `PACKAGE_USAGE_STATS` is a special permission: it cannot be requested at
 * runtime, only toggled by the user in Settings. So the flow is "check the app-op,
 * and if it is off, deep-link the user to the right Settings page".
 *
 * Because the user leaves the app to grant it, callers must re-check on resume
 * rather than caching the result.
 */
object UsageAccess {

    // Both app-op check variants are deprecated, and there is no non-deprecated
    // replacement for querying a special permission's state - reading the app-op
    // remains the only way to tell whether the user has granted usage access.
    @Suppress("DEPRECATION")
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        // MODE_DEFAULT does not mean "denied" - it means the app-op defers to the
        // permission. Treating it as denied leaves the app permanently stuck on
        // the grant prompt on devices that never write an explicit op entry.
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkPermission(
                Manifest.permission.PACKAGE_USAGE_STATS,
                Process.myPid(),
                Process.myUid(),
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * Opens the system usage-access list. There is no reliable way to deep-link
     * straight to our own row, so the user still has to pick ORBIS from the list -
     * the UI should say so rather than implying one tap is enough.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
