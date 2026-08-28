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
import com.orbis.app.surface.SurfaceMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val earn: EarnRepository,
    private val deeds: GoodDeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EarnUiState())
    val state: StateFlow<EarnUiState> = _state.asStateFlow()

    private var focusJob: Job? = null

    init {
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
     */
    fun startFocus() {
        if (focusJob?.isActive == true) return
        if (_state.value.remainingUses[EarnAction.FOCUS_SESSION] == 0) {
            _state.update { it.copy(message = "No focus sessions left today.") }
            return
        }

        focusJob = viewModelScope.launch {
            var remaining = EarnAction.FOCUS_DURATION_MILLIS
            _state.update { it.copy(focusRemainingMillis = remaining, message = null) }

            while (remaining > 0L) {
                delay(TICK_MILLIS)

                if (SurfaceMonitor.state.value.surface.throttled) {
                    _state.update {
                        it.copy(
                            focusRemainingMillis = null,
                            message = "Session ended - a short-form feed opened. " +
                                "No hard feelings, start another whenever.",
                        )
                    }
                    return@launch
                }

                remaining -= TICK_MILLIS
                _state.update { it.copy(focusRemainingMillis = remaining.coerceAtLeast(0L)) }
            }

            val awarded = earn.award(EarnAction.FOCUS_SESSION)
            _state.update {
                it.copy(
                    focusRemainingMillis = null,
                    message = if (awarded > 0L) {
                        "Nice. ${awarded / 60_000L} clear minutes added."
                    } else {
                        "Session done - you are already at today's ceiling."
                    },
                )
            }
        }
    }

    fun cancelFocus() {
        focusJob?.cancel()
        focusJob = null
        _state.update { it.copy(focusRemainingMillis = null) }
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

    override fun onCleared() {
        focusJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TICK_MILLIS = 1_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    EarnViewModel(
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
