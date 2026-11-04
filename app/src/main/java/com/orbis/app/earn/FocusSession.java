package com.orbis.app.earn;

import android.content.Context;
import android.content.SharedPreferences;

import com.orbis.app.util.StateHolder;

/**
 * The running focus session, kept outside any screen.
 *
 * <p>It used to live in a screen-scoped coroutine that counted one-second ticks,
 * which failed exactly where a focus session is supposed to happen:
 *
 * <ul>
 *   <li><b>The phone goes down.</b> Ticks stop in deep sleep, so a session begun
 *       with the phone face-down did not progress while it lay there.
 *   <li><b>The app goes away.</b> Swiping ORBIS from recents killed the counter,
 *       and the session with it, without a word.
 * </ul>
 *
 * <p>So the session is a wall-clock start time persisted to disk. Elapsed time is
 * arithmetic, not counting; the accessibility service - which runs whether or not
 * ORBIS is open - marks it broken the moment a feed appears; and a WorkManager job
 * pays out at the end if nobody is looking.
 *
 * <p>{@link #claimIfComplete} is the one door to the reward. The screen and the
 * job both knock on it, and whichever gets there first is the only one to get in.
 */
public final class FocusSession {

    private static final String PREFS = "orbis_focus";
    private static final String KEY_STARTED = "started_at";
    private static final String KEY_BROKEN = "broken";

    private static final StateHolder<FocusState> STATE = new StateHolder<>(new FocusState());

    private static volatile SharedPreferences prefs;

    private FocusSession() {
        // No instances: one session per device.
    }

    public static StateHolder<FocusState> state() {
        return STATE;
    }

    /** Idempotent; called by every entry point that may start the process first. */
    public static synchronized void init(Context context) {
        if (prefs != null) {
            return;
        }
        SharedPreferences loaded =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs = loaded;
        STATE.set(
                new FocusState(
                        loaded.getLong(KEY_STARTED, 0L), loaded.getBoolean(KEY_BROKEN, false)));
    }

    public static synchronized void start(Context context, long nowMillis) {
        init(context);
        write(new FocusState(nowMillis, false));
    }

    /**
     * Called by the accessibility service whenever a throttled surface is on
     * screen. Cheap when there is nothing to break, which is almost always.
     */
    public static void onFeedOpened(Context context) {
        FocusState current = STATE.get();
        if (!current.getActive() || current.getBroken()) {
            return;
        }
        synchronized (FocusSession.class) {
            init(context);
            FocusState latest = STATE.get();
            if (latest.getActive() && !latest.getBroken()) {
                write(latest.asBroken());
            }
        }
    }

    /**
     * Ends a completed session and reports that it was completed - exactly once.
     *
     * @return true if the caller should pay out. False when there is no session,
     *     it is still running, it was broken, or someone else already claimed it.
     */
    public static synchronized boolean claimIfComplete(Context context, long nowMillis) {
        init(context);
        if (!STATE.get().completeAt(nowMillis)) {
            return false;
        }
        write(new FocusState());
        return true;
    }

    public static synchronized void clear(Context context) {
        init(context);
        write(new FocusState());
    }

    private static void write(FocusState state) {
        STATE.set(state);
        SharedPreferences target = prefs;
        if (target != null) {
            target.edit()
                    .putLong(KEY_STARTED, state.getStartedAtMillis())
                    .putBoolean(KEY_BROKEN, state.getBroken())
                    // commit, not apply: the payout job may run in a fresh process
                    // moments later, and must read what was just written rather
                    // than the old value.
                    .commit();
        }
    }
}
