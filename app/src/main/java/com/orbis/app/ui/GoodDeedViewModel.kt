package com.orbis.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.data.GoodDeedEntry
import com.orbis.app.deed.GoodDeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoodDeedUiState(
    val streak: Int = 0,
    val doneToday: Boolean = false,
    val entries: List<GoodDeedEntry> = emptyList(),
    val capturing: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

class GoodDeedViewModel(
    private val repository: GoodDeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GoodDeedUiState())
    val state: StateFlow<GoodDeedUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { entries ->
                _state.update { it.copy(entries = entries) }
                refreshStreak()
            }
        }
    }

    fun refreshStreak() {
        viewModelScope.launch {
            _state.update {
                it.copy(streak = repository.streak(), doneToday = repository.doneToday())
            }
        }
    }

    fun startCapture() = _state.update { it.copy(capturing = true, message = null) }

    fun cancelCapture(photoPath: String? = null) {
        viewModelScope.launch {
            repository.discardPhoto(photoPath)
            _state.update { it.copy(capturing = false) }
        }
    }

    fun save(photoPath: String?, note: String) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { repository.record(photoPath, note) }
                .onSuccess {
                    _state.update {
                        it.copy(saving = false, capturing = false, message = "Logged. Nice one.")
                    }
                    refreshStreak()
                }
                .onFailure { throwable ->
                    // The photo is already on disk; keep it rather than lose the
                    // moment, and say plainly that the entry did not save.
                    _state.update {
                        it.copy(
                            saving = false,
                            message = "Couldn't save that: ${throwable.message ?: "unknown error"}",
                        )
                    }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    GoodDeedViewModel(
                        GoodDeedRepository(DatabaseProvider.get(appContext).goodDeedDao())
                    )
                }
            }
        }
    }
}
