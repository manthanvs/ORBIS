package com.orbis.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.dashboard.ReclaimedTime
import com.orbis.app.data.DatabaseProvider
import com.orbis.app.data.UsageRepository
import com.orbis.app.usage.UsageStatsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardUiState(
    val loading: Boolean = true,
    val summary: ReclaimedSummary? = null,
)

class DashboardViewModel(
    private val repository: UsageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                // Recompute today first so the dashboard reflects the current
                // session rather than whatever was last written.
                repository.refreshToday()
                repository.dailyHistory(ReclaimedTime.BASELINE_WINDOW_DAYS + 1)
            }.onSuccess { history ->
                _state.value = DashboardUiState(
                    loading = false,
                    summary = ReclaimedTime.summarize(history, LocalDate.now()),
                )
            }.onFailure {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    DashboardViewModel(
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
