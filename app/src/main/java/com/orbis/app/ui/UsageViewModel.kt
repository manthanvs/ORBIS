package com.orbis.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.data.UsageRepository
import com.orbis.app.usage.UsageProfile
import com.orbis.app.usage.UsageStatsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsageUiState(
    val hasUsageAccess: Boolean = false,
    val loading: Boolean = false,
    val profile: UsageProfile = UsageProfile.EMPTY,
    val error: String? = null,
)

class UsageViewModel(
    private val repository: UsageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    /**
     * Called on every resume. Usage access is granted outside the app, so the
     * answer can change while we are backgrounded and must not be cached.
     */
    fun onUsageAccessChanged(granted: Boolean) {
        _state.update { it.copy(hasUsageAccess = granted) }
        if (granted) refresh()
    }

    fun refresh() {
        if (!_state.value.hasUsageAccess) return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.refreshToday() }
                .onSuccess { profile ->
                    _state.update { it.copy(loading = false, profile = profile) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: throwable::class.simpleName,
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    UsageViewModel(
                        UsageRepository(
                            source = UsageStatsSource.from(appContext),
                            dao = DatabaseProvider.get(appContext).usageLogDao(),
                        )
                    )
                }
            }
        }
    }
}
