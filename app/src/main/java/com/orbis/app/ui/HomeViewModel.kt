package com.orbis.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.dashboard.ReclaimedTime
import com.orbis.app.data.UsageRepository
import com.orbis.app.earn.ClearTimeHolder
import com.orbis.app.earn.EarnRepository
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.throttle.ThrottleLevel
import com.orbis.app.surface.SurfaceMonitor
import com.orbis.app.throttle.ThrottleSettings
import com.orbis.app.usage.UsageProfile
import com.orbis.app.usage.UsageProfileHolder
import com.orbis.app.vpn.OrbisVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * How ORBIS is currently protecting the user's attention.
 *
 * Kept separate from the slow-moving dashboard numbers so that a change of
 * surface repaints one card rather than the whole screen.
 */
data class ProtectionUiState(
    val hasUsageAccess: Boolean = false,
    val hasDetection: Boolean = false,
    /**
     * Whether Android currently lets ORBIS run its VPN.
     *
     * Tracked separately from [autoThrottle] because the two drift apart: the
     * home screen's "Turn it on" used to flip the setting without ever asking,
     * and another VPN app taking over silently revokes it later. Either way the
     * screen said "You are all set" while nothing could be slowed.
     */
    val hasVpnConsent: Boolean = false,
    val autoThrottle: Boolean = false,
    val tunnelRunning: Boolean = false,
    val detected: DetectedSurface = DetectedSurface(),
    val delayMillis: Long = 0L,
    /** The pulse: squeezed this long in every [pulsePeriodMillis]. */
    val squeezeMillis: Long = 0L,
    val pulsePeriodMillis: Long = 0L,
) {
    /** A running tunnel is proof of consent, whatever the last check said. */
    val canSlow: Boolean get() = hasVpnConsent || tunnelRunning
}

private data class TunnelShape(
    val running: Boolean,
    val delayMillis: Long,
    val squeezeMillis: Long,
    val pulsePeriodMillis: Long,
)

data class HomeUiState(
    val loading: Boolean = true,
    val summary: ReclaimedSummary? = null,
    val profile: UsageProfile = UsageProfile.EMPTY,
    /** Consecutive days on which clear time was earned. */
    val streak: Int = 0,
    val earnedToday: Boolean = false,
    /** Clear time left today, live. */
    val clearTimeMillis: Long = 0L,
    /** How hard ORBIS is pushing, given today and the recent average. */
    val level: ThrottleLevel = ThrottleLevel.NUDGE,
    val error: String? = null,
)

/**
 * Backs the home screen.
 *
 * One ViewModel rather than three, because the home screen shows reclaimed time,
 * today's per-app split, the live surface and the deed streak side by side. With
 * a ViewModel each, the activity had to collect all of them at the top of the
 * composition, so any one of them changing repainted every screen.
 */
class HomeViewModel(
    private val usage: UsageRepository,
    private val earn: EarnRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _access = MutableStateFlow(ProtectionUiState())

    /**
     * The live half of the screen.
     *
     * Only the fields the home screen actually shows are folded in: the tunnel
     * publishes packet counters several times a second, and mapping them away
     * before `distinctUntilChanged` is what stops the home screen waking for
     * traffic it never displays.
     */
    val protection: StateFlow<ProtectionUiState> = combine(
        _access,
        SurfaceMonitor.state,
        // The pulse's shape, not its phase: `squeezing` flips every few seconds,
        // and the home screen does not need to wake for each flip.
        OrbisVpnService.tunnelStats
            .map { TunnelShape(it.running, it.delayMillis, it.squeezeMillis, it.pulsePeriodMillis) }
            .distinctUntilChanged(),
        ThrottleSettings.enabled,
    ) { access, detected, tunnel, autoThrottle ->
        access.copy(
            detected = detected,
            tunnelRunning = tunnel.running,
            delayMillis = tunnel.delayMillis,
            squeezeMillis = tunnel.squeezeMillis,
            pulsePeriodMillis = tunnel.pulsePeriodMillis,
            autoThrottle = autoThrottle,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = ProtectionUiState(),
    )

    init {
        // The streak is the earn loop's now - days on which clear time was earned,
        // which includes a good deed but is no longer only one.
        viewModelScope.launch {
            earn.observeToday().collect { movements ->
                val summary = earn.summarize(movements)
                _state.update {
                    it.copy(
                        streak = summary.streak,
                        earnedToday = summary.balance.earnedMillis > 0L,
                    )
                }
            }
        }
        viewModelScope.launch {
            ClearTimeHolder.remainingMillis.collect { remaining ->
                _state.update { it.copy(clearTimeMillis = remaining) }
            }
        }
        viewModelScope.launch { runCatching { earn.refresh() } }
    }

    /** Permissions are granted outside the app, so they are pushed in on resume. */
    fun onPermissionsChanged(
        hasUsageAccess: Boolean,
        hasDetection: Boolean,
        hasVpnConsent: Boolean,
    ) {
        _access.update {
            it.copy(
                hasUsageAccess = hasUsageAccess,
                hasDetection = hasDetection,
                hasVpnConsent = hasVpnConsent,
            )
        }
        if (hasUsageAccess) refresh()
    }

    fun refresh(force: Boolean = false) {
        if (!_access.value.hasUsageAccess) {
            _state.update { it.copy(loading = false) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                // Today first, so the screen reflects the current session rather
                // than whatever was last written.
                val profile = usage.refreshToday(force)
                val history = usage.dailyHistory(ReclaimedTime.BASELINE_WINDOW_DAYS + 1)
                profile to ReclaimedTime.summarize(history, LocalDate.now())
            }.onSuccess { (profile, summary) ->
                val level = ThrottleEngine.levelFor(profile, UsageProfileHolder.baselineMillis.value)
                _state.update {
                    it.copy(
                        loading = false,
                        profile = profile,
                        summary = summary,
                        level = level,
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = throwable.message ?: throwable::class.simpleName,
                    )
                }
            }
        }
    }

    /** Housekeeping, run once per launch rather than on every refresh. */
    fun prune() {
        viewModelScope.launch {
            runCatching { usage.pruneHistory(UsageRepository.RETENTION_DAYS) }
        }
    }

    companion object {
        /** Survives a rotation without tearing the combine down and rebuilding it. */
        private const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    HomeViewModel(
                        usage = UsageRepository.shared(appContext),
                        earn = EarnRepository.shared(appContext),
                    )
                }
            }
        }
    }
}
