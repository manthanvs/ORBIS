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

    fun publish(profile: UsageProfile) {
        _profile.value = profile
    }
}
