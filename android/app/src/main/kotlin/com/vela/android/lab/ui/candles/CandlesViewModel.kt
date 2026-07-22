package com.vela.android.lab.ui.candles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.watchlist.WatchlistRepository
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Local, read-only candles state. Its only data dependencies are the watchlist and the existing
 * Room-backed market repository. It owns no HTTP client and never starts or restarts a stream.
 */
class CandlesViewModel(
    private val watchlistRepository: WatchlistRepository,
    private val marketDataRepository: MarketDataRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val staleAfter: Duration = DEFAULT_STALE_AFTER,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CandlesUiState())
    val uiState: StateFlow<CandlesUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** Manual/local refresh: re-reads watchlist and one-minute bars already persisted in Room. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshInternal() }
    }

    fun onSymbolSelected(symbol: String) {
        val normalized = symbol.trim().uppercase()
        if (normalized !in _uiState.value.symbols || normalized == _uiState.value.selectedSymbol) {
            return
        }
        _uiState.update { it.copy(selectedSymbol = normalized, selectedCandle = null) }
        refresh()
    }

    fun onCandleCountSelected(count: Int) {
        if (count !in CandlesUiState.CANDLE_COUNT_OPTIONS || count == _uiState.value.candleCount) {
            return
        }
        _uiState.update { it.copy(candleCount = count, selectedCandle = null) }
        refresh()
    }

    fun onCandleSelected(candle: CandleUiModel) {
        if (_uiState.value.candles.any { it.stableId == candle.stableId }) {
            _uiState.update { it.copy(selectedCandle = candle) }
        }
    }

    private suspend fun refreshInternal() {
        _uiState.update {
            it.copy(dataState = CandleDataState.LOADING, errorMessage = null)
        }
        try {
            val symbols = watchlistRepository.load()
            val current = _uiState.value
            val selectedSymbol = current.selectedSymbol
                ?.takeIf { it in symbols }
                ?: symbols.firstOrNull()
            val now = clock()

            if (selectedSymbol == null) {
                _uiState.update {
                    it.copy(
                        symbols = symbols,
                        selectedSymbol = null,
                        candles = emptyList(),
                        selectedCandle = null,
                        dataState = CandleDataState.EMPTY,
                        freshness = CandleFreshness.UNKNOWN,
                        lastBarAgeMillis = null,
                        lastRefreshAt = now,
                        rejectedBarCount = 0,
                    )
                }
                return
            }

            val bars = marketDataRepository.recentBars(selectedSymbol, current.candleCount)
            val mapped = CandleMapper.map(bars, current.candleCount)
            val latest = mapped.candles.lastOrNull()
            val (freshness, ageMillis) = CandleMapper.freshness(
                latestTimestamp = latest?.timestamp,
                now = now,
                staleAfter = staleAfter,
            )
            val previousSelection = current.selectedCandle
            val selectedCandle = previousSelection
                ?.let { selected -> mapped.candles.firstOrNull { it.stableId == selected.stableId } }
                ?: latest
            val dataState = when {
                bars.isEmpty() -> CandleDataState.EMPTY
                mapped.candles.isEmpty() -> CandleDataState.INSUFFICIENT
                else -> CandleDataState.READY
            }

            _uiState.update {
                it.copy(
                    symbols = symbols,
                    selectedSymbol = selectedSymbol,
                    candles = mapped.candles,
                    selectedCandle = selectedCandle,
                    dataState = dataState,
                    freshness = freshness,
                    lastBarAgeMillis = ageMillis,
                    lastRefreshAt = now,
                    rejectedBarCount = mapped.rejectedCount,
                    errorMessage = null,
                )
            }
        } catch (exc: Exception) {
            _uiState.update {
                it.copy(
                    dataState = CandleDataState.ERROR,
                    errorMessage = exc.message ?: exc::class.simpleName ?: "Error de lectura local",
                    lastRefreshAt = clock(),
                )
            }
        }
    }

    companion object {
        /** Two missed one-minute buckets marks the local snapshot stale. */
        val DEFAULT_STALE_AFTER: Duration = Duration.ofMinutes(2)
    }
}
