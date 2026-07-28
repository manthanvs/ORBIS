package com.orbis.app.throttle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether ORBIS may bring the tunnel up on its own when a short-form video
 * surface appears.
 *
 * Off by default: nothing touches the network until the user opts in.
 */
object ThrottleSettings {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}
