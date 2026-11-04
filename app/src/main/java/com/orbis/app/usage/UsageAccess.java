package com.orbis.app.usage;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

/**
 * {@code PACKAGE_USAGE_STATS} is a special permission: it cannot be requested at
 * runtime, only toggled by the user in Settings. So the flow is "check the
 * app-op, and if it is off, deep-link the user to the right Settings page".
 *
 * <p>Because the user leaves the app to grant it, callers must re-check on
 * resume rather than caching the result.
 */
public final class UsageAccess {

    private UsageAccess() {
        // No instances: two helpers over a special permission.
    }

    // Both app-op check variants are deprecated, and there is no non-deprecated
    // replacement for querying a special permission's state - reading the app-op
    // remains the only way to tell whether the user has granted usage access.
    @SuppressWarnings("deprecation")
    public static boolean isGranted(Context context) {
        AppOpsManager appOps =
                (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
        } else {
            mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
        }

        // MODE_DEFAULT does not mean "denied" - it means the app-op defers to the
        // permission. Treating it as denied leaves the app permanently stuck on
        // the grant prompt on devices that never write an explicit op entry.
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkPermission(
                            Manifest.permission.PACKAGE_USAGE_STATS,
                            Process.myPid(),
                            Process.myUid())
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Opens the system usage-access list. There is no reliable way to deep-link
     * straight to our own row, so the user still has to pick ORBIS from the list
     * - the UI should say so rather than implying one tap is enough.
     */
    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }
}
