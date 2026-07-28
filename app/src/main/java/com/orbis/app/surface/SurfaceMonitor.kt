package com.orbis.app.surface

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetectedSurface(
    val surface: Surface = Surface.NORMAL,
    val packageName: String? = null,
    val updatedAtMillis: Long = 0L,
)

/**
 * Process-wide holder for the currently detected surface.
 *
 * The accessibility service publishes here; the UI (and, from Phase 2c, the
 * throttle engine) observes. A plain object rather than a bound service because
 * an [android.accessibilityservice.AccessibilityService] is owned by the system
 * and cannot be bound to conventionally.
 */
object SurfaceMonitor {

    private val _state = MutableStateFlow(DetectedSurface())
    val state: StateFlow<DetectedSurface> = _state.asStateFlow()

    fun publish(surface: Surface, packageName: String?, nowMillis: Long) {
        val current = _state.value
        if (current.surface == surface && current.packageName == packageName) return
        _state.value = DetectedSurface(surface, packageName, nowMillis)
    }

    /** Called when the service disconnects, so a stale surface cannot linger. */
    fun reset() {
        _state.value = DetectedSurface()
    }
}
