package com.orbis.app.settings

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
 * How much of itself the app shows.
 *
 * Simple mode is the default, and deliberately so: the numbers ORBIS collects are
 * only useful if the person reading them understands what they mean. The detailed
 * home screen assumes the reader knows what a baseline, a surface and a throttle
 * delay are; simple mode assumes nothing.
 *
 * Backed by its **own** DataStore file rather than the one
 * [com.orbis.app.throttle.ThrottleSettings] owns. DataStore permits exactly one
 * active instance per file per process and throws if a second is created, so two
 * objects sharing "orbis_settings" would crash on first read.
 */
object UiSettings {

    private val KEY_SIMPLE_MODE = booleanPreferencesKey("simple_mode")

    /** Default on: a first-time reader should land on the explainable screen. */
    private val _simpleMode = MutableStateFlow(true)
    val simpleMode: StateFlow<Boolean> = _simpleMode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: DataStore<Preferences>? = null

    @Synchronized
    fun init(context: Context) {
        if (store != null) return

        val created = PreferenceDataStoreFactory.create {
            context.applicationContext.preferencesDataStoreFile("orbis_ui")
        }
        store = created

        scope.launch {
            runCatching {
                created.data.collect { preferences ->
                    _simpleMode.value = preferences[KEY_SIMPLE_MODE] ?: true
                }
            }
        }
    }

    fun setSimpleMode(value: Boolean) {
        // In memory first so the toggle responds on the same frame; the collector
        // above confirms it once the write lands.
        _simpleMode.value = value
        val target = store ?: return
        scope.launch {
            runCatching { target.edit { it[KEY_SIMPLE_MODE] = value } }
        }
    }
}
