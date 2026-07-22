package com.vela.android.lab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.ui.navigation.VelaDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Activity-scoped adapter for visual preferences only. */
class VelaPreferencesViewModel(
    private val store: VelaPreferencesStore,
) : ViewModel() {

    val uiState: StateFlow<VelaPreferencesState> = store.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = VelaPreferencesState(),
    )

    fun onDensitySelected(value: VelaUiDensity) = update { store.setDensity(value) }

    fun onCandleCountSelected(value: VelaCandleCount) =
        update { store.setCandleCount(value) }

    fun onDefaultSymbolSelected(symbol: String, availableSymbols: Collection<String>) =
        update { store.setDefaultSymbol(symbol, availableSymbols) }

    fun onAdvancedDiagnosticsChanged(value: Boolean) =
        update { store.setAdvancedDiagnostics(value) }

    fun onRememberLastSectionChanged(value: Boolean) =
        update { store.setRememberLastSection(value) }

    fun onTimeFormatSelected(value: VelaTimeFormat) =
        update { store.setTimeFormat(value) }

    fun onDestinationSelected(destination: VelaDestination) {
        if (!uiState.value.preferences.rememberLastSection) return
        update { store.setLastDestination(destination) }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
