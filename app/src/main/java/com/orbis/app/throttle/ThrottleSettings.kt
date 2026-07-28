package com.orbis.app.throttle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether ORBIS may bring the tunnel up on its own when a short-form video surface
 * appears.
 *
 * Off by default: nothing touches the network until the user opts in. The choice is
 * persisted, because a setting that silently resets on every restart is worse than
 * no setting - the user believes throttling is on when it is not.
 *
 * [init] is idempotent and called from both entry points ([com.orbis.app.MainActivity]
 * and the accessibility service), since either may start the process first.
 */
object ThrottleSettings {

    private val KEY_ENABLED = booleanPreferencesKey("auto_throttle_enabled")

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: DataStore<Preferences>? = null

    @Synchronized
    fun init(context: Context) {
        if (store != null) return

        val created = PreferenceDataStoreFactory.create {
            context.applicationContext.preferencesDataStoreFile("orbis_settings")
        }
        store = created

        scope.launch {
            runCatching {
                created.data.collect { preferences ->
                    _enabled.value = preferences[KEY_ENABLED] ?: false
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        // Update in memory first so the UI responds immediately; the collector above
        // will confirm it once written.
        _enabled.value = value
        val target = store ?: return
        scope.launch {
            runCatching { target.edit { it[KEY_ENABLED] = value } }
        }
    }
}
