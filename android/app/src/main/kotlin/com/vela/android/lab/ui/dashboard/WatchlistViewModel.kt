package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
import com.vela.android.lab.data.watchlist.WatchlistConfig
import com.vela.android.lab.data.watchlist.WatchlistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.g read-only watchlist ViewModel. Owns the user's small
 * stock symbol set and projects the per-symbol counters from the
 * stock bridge into the dashboard.
 *
 * No method here connects to a network. The stream lifecycle stays
 * with [AlpacaStockStreamViewModel]; this VM only:
 *
 *  - loads / seeds the watchlist from [WatchlistRepository]
 *  - exposes a [StateFlow] of the watchlist + per-symbol routing
 *    metrics
 *  - validates and routes add/remove user input through the
 *    repository
 *  - exposes the current `Set<String>` to callers that need to
 *    subscribe (the dashboard wires Start → `subscribeSet()`).
 */
class WatchlistViewModel(
    private val repository: WatchlistRepository,
    private val pipelineBridge: AlpacaTestStreamPipelineBridge,
) : ViewModel() {

    private val _uiState: MutableStateFlow<WatchlistUiState> =
        MutableStateFlow(WatchlistUiState.initial(WatchlistConfig.MAX_SYMBOLS))

    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    private val bridgeJob: Job

    init {
        viewModelScope.launch { reload() }
        bridgeJob = viewModelScope.launch {
            pipelineBridge.state.collect { bridgeState ->
                val mapped: Map<String, WatchlistSymbolStats> = bridgeState.perSymbol
                    .mapValues { (_, stats) ->
                        WatchlistSymbolStats(
                            received = stats.received,
                            persisted = stats.persisted,
                            lastClose = stats.lastClose,
                            lastSignalState = stats.lastSignalState,
                        )
                    }
                _uiState.update { it.copy(perSymbol = mapped) }
            }
        }
    }

    fun onAddInputChange(value: String) {
        _uiState.update { it.copy(addInput = value, lastStatus = null) }
    }

    fun add() {
        val current = _uiState.value
        val input = current.addInput.trim()
        if (input.isEmpty()) {
            _uiState.update { it.copy(lastStatus = "Enter a symbol first.") }
            return
        }
        viewModelScope.launch {
            val result = repository.add(input)
            val refreshed = repository.load()
            _uiState.update {
                it.copy(
                    symbols = refreshed,
                    addInput = "",
                    lastStatus = result.message,
                )
            }
        }
    }

    fun remove(symbol: String) {
        viewModelScope.launch {
            val result = repository.remove(symbol)
            val refreshed = repository.load()
            _uiState.update {
                it.copy(
                    symbols = refreshed,
                    lastStatus = result.message,
                )
            }
        }
    }

    /** Read the watchlist as a Set for the stock client's subscribe call. */
    fun subscribeSet(): Set<String> = _uiState.value.symbols.toSet()

    private suspend fun reload() {
        val symbols = repository.load()
        _uiState.update { it.copy(symbols = symbols) }
    }

    override fun onCleared() {
        bridgeJob.cancel()
    }
}
