package com.orbis.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.deed.GoodDeedRepository
import com.orbis.app.earn.ClearTimeBalance
import com.orbis.app.earn.ClearTimeHolder
import com.orbis.app.earn.EarnAction
import com.orbis.app.earn.EarnRepository
import com.orbis.app.earn.FocusSession
import com.orbis.app.earn.FocusSessionWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EarnUiState(
    val balance: ClearTimeBalance = ClearTimeBalance.EMPTY,
    /** Live remaining credit, which ticks down as a feed is watched. */
    val remainingMillis: Long = 0L,
    val streak: Int = 0,
    val remainingUses: Map<EarnAction, Int> = emptyMap(),
    /** Non-null while a focus session is running. */
    val focusRemainingMillis: Long? = null,
    val capturing: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

/**
 * The earn-back side quest.
 *
 * Owns the clear-time ledger, the focus session, and the good deed - which
 * survives as the highest-paying action rather than being the whole feature.
 */
class EarnViewModel(
    private val appContext: Context,
    private val earn: EarnRepository,
    private val deeds: GoodDeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EarnUiState())
    val state: StateFlow<EarnUiState> = _state.asStateFlow()

    /**
     * Whether the Earn screen is actually on screen.
     *
     * A ViewModel outlives its screen: after the user presses Home it keeps
     * running in the background, and it used to claim a finished session there -
     * silently, with an in-app message nobody was looking at, beating the
     * WorkManager job that would have posted a notification. So it only claims
     * while the user can see it happen.
     */
    private val visible = MutableStateFlow(false)

    fun onVisibilityChanged(isVisible: Boolean) {
        visible.value = isVisible
    }

    init {
        FocusSession.init(appContext)
        viewModelScope.launch { mirrorFocusSession() }

        viewModelScope.launch {
            earn.observeToday().collect { movements ->
                val summary = earn.summarize(movements)
                _state.update {
                    it.copy(
                        balance = summary.balance,
                        streak = summary.streak,
                        remainingUses = summary.remainingUses,
                    )
                }
            }
        }

        // The balance the gate is spending against, not the one the ledger last
        // wrote: credit is metered in memory between flushes, so reading the
        // database here would show a number several seconds out of date while the
        // user is actually watching something.
        viewModelScope.launch {
            ClearTimeHolder.remainingMillis.collect { remaining ->
                _state.update { it.copy(remainingMillis = remaining) }
            }
        }

        viewModelScope.launch { runCatching { earn.refresh() } }
    }

    // -------------------------------------------------------------- focus session

    /**
     * Starts a focus session, which pays out only if it is seen through.
     *
     * The verification is free: ORBIS already knows when a short-form feed is on
     * screen, so opening one ends the session. That is the whole mechanic - the
     * session is not a promise, it is a measurement.
     *
     * The session itself lives in [FocusSession], not here, so that locking the
     * phone or swiping ORBIS away - the natural thing to do when focusing - does
     * not quietly cancel it. This screen only displays it.
     */
    fun startFocus() {
        if (FocusSession.state.value.active) return
        if (_state.value.remainingUses[EarnAction.FOCUS_SESSION] == 0) {
            _state.update { it.copy(message = "No focus sessions left today.") }
            return
        }

        FocusSession.start(appContext, System.currentTimeMillis())
        FocusSessionWorker.schedule(appContext, EarnAction.FOCUS_DURATION_MILLIS)
        _state.update { it.copy(message = null) }
    }

    fun cancelFocus() {
        FocusSession.clear(appContext)
        FocusSessionWorker.cancel(appContext)
    }

    /**
     * Shows the running session, once a second, and claims it the moment it is
     * done if the user is here to see that happen.
     */
    private suspend fun mirrorFocusSession() {
        FocusSession.state.collectLatest { session ->
            if (!session.active) {
                _state.update { it.copy(focusRemainingMillis = null) }
                return@collectLatest
            }

            if (session.broken) {
                FocusSession.clear(appContext)
                FocusSessionWorker.cancel(appContext)
                _state.update {
                    it.copy(
                        focusRemainingMillis = null,
                        message = "Session ended - a short-form feed opened. " +
                            "No hard feelings, start another whenever.",
                    )
                }
                return@collectLatest
            }

            while (true) {
                val now = System.currentTimeMillis()
                if (session.completeAt(now) && visible.value) {
                    // Launched separately: claiming clears the session, which emits,
                    // which cancels this block - and with it any payout still in flight.
                    viewModelScope.launch { claimFocus(now) }
                    return@collectLatest
                }
                // Done but unseen: leave it to FocusSessionWorker, which notifies.
                // Showing zero until then is honest - the time is up.
                _state.update { it.copy(focusRemainingMillis = session.remainingMillis(now)) }
                delay(TICK_MILLIS)
            }
        }
    }

    private suspend fun claimFocus(nowMillis: Long) {
        if (!FocusSession.claimIfComplete(appContext, nowMillis)) return
        FocusSessionWorker.cancel(appContext)

        // The session is already cleared, so a cancelled award here would lose the
        // reward for good. It must finish whatever happens to this screen.
        val awarded = withContext(NonCancellable) {
            runCatching { earn.award(EarnAction.FOCUS_SESSION) }.getOrDefault(0L)
        }
        _state.update {
            it.copy(
                message = if (awarded > 0L) {
                    "Nice. ${awarded / 60_000L} clear minutes added."
                } else {
                    "Session done - you are already at today's ceiling."
                },
            )
        }
    }

    // ------------------------------------------------------------------ good deed

    fun startCapture() = _state.update { it.copy(capturing = true, message = null) }

    /** CameraX writes the file before the row exists, so a cancel must clean up. */
    fun cancelCapture(photoPath: String?) {
        viewModelScope.launch {
            photoPath?.let { runCatching { deeds.discardPhoto(it) } }
            _state.update { it.copy(capturing = false) }
        }
    }

    fun saveDeed(photoPath: String?, note: String) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }

            val recorded = runCatching { deeds.record(photoPath, note) }
            if (recorded.isFailure) {
                // The photo stays on disk: it is the user's, and losing it to
                // tidy up after our own failure is the worse outcome.
                _state.update {
                    it.copy(
                        saving = false,
                        capturing = false,
                        message = "Could not save that. Your photo is safe.",
                    )
                }
                return@launch
            }

            val awarded = runCatching { earn.award(EarnAction.GOOD_DEED) }.getOrDefault(0L)
            _state.update {
                it.copy(
                    saving = false,
                    capturing = false,
                    message = if (awarded > 0L) {
                        "Logged. ${awarded / 60_000L} clear minutes added."
                    } else {
                        "Logged - that is your deed for today."
                    },
                )
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val TICK_MILLIS = 1_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    EarnViewModel(
                        appContext = applicationContext,
                        earn = EarnRepository.shared(applicationContext),
                        deeds = GoodDeedRepository(
                            DatabaseProvider.get(applicationContext).goodDeedDao()
                        ),
                    )
                }
            }
        }
    }
}
