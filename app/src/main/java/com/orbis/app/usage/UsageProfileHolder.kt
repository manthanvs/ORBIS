package com.orbis.app.usage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the most recently computed [UsageProfile].
 *
 * Exists because the throttle decision happens in the accessibility service, which
 * has no repository of its own, while the profile is computed in
 * `UsageRepository`. Without somewhere for the two to meet, the gate was passing
 * [UsageProfile.EMPTY] and the throttle silently never scaled.
 *
 * Mirrors the `SurfaceMonitor` pattern: a plain object, because the accessibility
 * service is owned by the system and cannot be bound to conventionally.
 */
object UsageProfileHolder {

    private val _profile = MutableStateFlow(UsageProfile.EMPTY)
    val profile: StateFlow<UsageProfile> = _profile.asStateFlow()

    private val _baselineMillis = MutableStateFlow(0L)

    /**
     * The recent daily short-form average, which floors the throttle level.
     *
     * Without it every midnight would drop a settled habit back to level 1 and
     * spend the morning nudging someone who is well past nudging.
     */
    val baselineMillis: StateFlow<Long> = _baselineMillis.asStateFlow()

    fun publish(profile: UsageProfile, baselineMillis: Long = _baselineMillis.value) {
        _profile.value = profile
        _baselineMillis.value = baselineMillis
    }
}
