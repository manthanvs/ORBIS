package com.orbis.app.throttle;

import android.content.Context;
import android.content.SharedPreferences;

import com.orbis.app.util.StateHolder;

/**
 * Whether ORBIS may bring the tunnel up on its own when a short-form video
 * surface appears.
 *
 * <p>Off by default: nothing touches the network until the user opts in. The
 * choice is persisted, because a setting that silently resets on every restart
 * is worse than no setting - the user believes throttling is on when it is not.
 *
 * <p>{@link #init} is idempotent and called from both entry points (the activity
 * and the accessibility service), since either may start the process first.
 *
 * <p>SharedPreferences rather than DataStore: DataStore's API is Flow- and
 * coroutine-shaped and has no practical Java form, and one boolean does not
 * justify the RxDataStore wrapper.
 */
public final class ThrottleSettings {

    private static final String FILE = "orbis_settings";
    private static final String KEY_ENABLED = "auto_throttle_enabled";

    private static final StateHolder<Boolean> ENABLED = new StateHolder<>(false);

    private static volatile SharedPreferences store;

    private ThrottleSettings() {
        // No instances: one process-wide setting.
    }

    public static StateHolder<Boolean> enabled() {
        return ENABLED;
    }

    public static synchronized void init(Context context) {
        if (store != null) {
            return;
        }
        SharedPreferences created =
                context.getApplicationContext()
                        .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        store = created;
        ENABLED.set(created.getBoolean(KEY_ENABLED, false));
    }

    public static void setEnabled(boolean value) {
        // In memory first so the UI responds on the same frame; the write lands
        // asynchronously and only has to survive a restart.
        ENABLED.set(value);
        SharedPreferences target = store;
        if (target != null) {
            target.edit().putBoolean(KEY_ENABLED, value).apply();
        }
    }
}
