package com.orbis.app.earn

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One focus session, as the user would describe it: when it started, and
 * whether a feed has been opened since.
 *
 * Pure, so the arithmetic - still running, done, broken - is testable without a
 * clock or a device.
 */
data class FocusState(
    /** Wall-clock start, or 0 when no session is running. */
    val startedAtMillis: Long = 0L,
    val broken: Boolean = false,
    val durationMillis: Long = EarnAction.FOCUS_DURATION_MILLIS,
) {
    val active: Boolean get() = startedAtMillis > 0L

    val endsAtMillis: Long get() = startedAtMillis + durationMillis

    fun remainingMillis(nowMillis: Long): Long =
        if (!active) 0L else (endsAtMillis - nowMillis).coerceIn(0L, durationMillis)

    /** Seen through: time is up and nothing broke it. */
    fun completeAt(nowMillis: Long): Boolean = active && !broken && nowMillis >= endsAtMillis
}

/**
 * The running focus session, kept outside any screen.
 *
 * It used to live in a ViewModel coroutine that counted one-second ticks, which
 * failed exactly where a focus session is supposed to happen:
 *
 *  - **The phone goes down.** Ticks stop in deep sleep, so a session begun with
 *    the phone face-down did not progress while it lay there.
 *  - **The app goes away.** Swiping ORBIS from recents killed the coroutine, and
 *    the session with it, without a word.
 *
 * So the session is now a wall-clock start time persisted to disk. Elapsed time
 * is arithmetic, not counting; the accessibility service - which runs whether or
 * not ORBIS is open - marks it broken the moment a feed appears; and a WorkManager
 * job pays out at the end if nobody is looking.
 *
 * [claimIfComplete] is the one door to the reward. The screen and the job both
 * knock on it, and whichever gets there first is the only one to get in.
 */
object FocusSession {

    private const val PREFS = "orbis_focus"
    private const val KEY_STARTED = "started_at"
    private const val KEY_BROKEN = "broken"

    private val _state = MutableStateFlow(FocusState())
    val state: StateFlow<FocusState> = _state.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Idempotent; called by every entry point that may start the process first. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = loaded
        _state.value = FocusState(
            startedAtMillis = loaded.getLong(KEY_STARTED, 0L),
            broken = loaded.getBoolean(KEY_BROKEN, false),
        )
    }

    @Synchronized
    fun start(context: Context, nowMillis: Long) {
        init(context)
        write(FocusState(startedAtMillis = nowMillis))
    }

    /**
     * Called by the accessibility service whenever a throttled surface is on
     * screen. Cheap when there is nothing to break, which is almost always.
     */
    fun onFeedOpened(context: Context) {
        val current = _state.value
        if (!current.active || current.broken) return
        synchronized(this) {
            init(context)
            val latest = _state.value
            if (latest.active && !latest.broken) write(latest.copy(broken = true))
        }
    }

    /**
     * Ends a completed session and reports that it was completed - exactly once.
     *
     * @return true if the caller should pay out. False when there is no session,
     *   it is still running, it was broken, or someone else already claimed it.
     */
    @Synchronized
    fun claimIfComplete(context: Context, nowMillis: Long): Boolean {
        init(context)
        val current = _state.value
        if (!current.completeAt(nowMillis)) return false
        write(FocusState())
        return true
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        write(FocusState())
    }

    private fun write(state: FocusState) {
        _state.value = state
        prefs?.edit()
            ?.putLong(KEY_STARTED, state.startedAtMillis)
            ?.putBoolean(KEY_BROKEN, state.broken)
            // commit, not apply: the payout job may run in a fresh process moments
            // later, and must read what was just written rather than the old value.
            ?.commit()
    }
}
