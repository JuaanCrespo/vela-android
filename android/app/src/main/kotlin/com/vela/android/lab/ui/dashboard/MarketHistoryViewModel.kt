package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.WatchlistRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.j read-only history ViewModel. Loads recent persisted
 * market-data + signal snapshots from the existing Phase 1.e Room
 * repositories. No network. No credentials. No order, trading, or
 * account method.
 *
 * The VM:
 *  - resolves the current watchlist on refresh and queries each
 *    repository for the latest snapshot per symbol;
 *  - emits a [MarketHistoryUiState] StateFlow the dashboard renders;
 *  - sets `isRefreshing = true` for the duration of the load so the
 *    UI can disable the Refresh button.
 *
 * Refresh is **manual** — the VM does not poll Room. The watchlist
 * is re-read on every refresh so newly-added / removed symbols are
 * reflected without a separate hook from the watchlist VM.
 */
class MarketHistoryViewModel(
    private val watchlistRepository: WatchlistRepository,
    private val marketDataRepository: MarketDataRepository,
    private val featureRepository: FeatureRepository,
    private val signalRepository: SignalRepository,
    private val journalRepository: JournalRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val recentBarLimit: Int = DEFAULT_RECENT_BAR_LIMIT,
) : ViewModel() {

    private val _uiState: MutableStateFlow<MarketHistoryUiState> =
        MutableStateFlow(MarketHistoryUiState.Initial)

    val uiState: StateFlow<MarketHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshInternal() }
    }

    /** User-facing refresh action. Safe to call repeatedly. */
    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        _uiState.update { it.copy(isRefreshing = true, lastError = null) }
        try {
            val symbols = watchlistRepository.load()
            val perSymbol = mutableMapOf<String, PerSymbolHistory>()
            for (symbol in symbols) {
                val recent = marketDataRepository.recentBars(symbol, recentBarLimit)
                val latestBar = recent.lastOrNull()
                val latestFeatures = featureRepository.latestFor(symbol)
                val latestSignal = signalRepository.latestFor(symbol)
                val journalForSymbol = journalRepository.forSymbol(symbol)
                perSymbol[symbol] = PerSymbolHistory(
                    symbol = symbol,
                    latestBarClose = latestBar?.close,
                    latestBarTimestampMillis = latestBar?.bucketStart?.toEpochMilli(),
                    recentBarCount = recent.size,
                    latestFeatureDirection = latestFeatures?.direction,
                    latestSignalState = latestSignal?.state?.value,
                    latestSignalScore = latestSignal?.score,
                    journalEventCount = journalForSymbol.size,
                )
            }
            val totalBars = marketDataRepository.countAll()
            val totalJournal = journalRepository.count()
            _uiState.update {
                it.copy(
                    symbols = symbols,
                    perSymbol = perSymbol,
                    totalPersistedBars = totalBars,
                    totalJournalEvents = totalJournal,
                    lastRefreshAtEpochMillis = clock().toEpochMilli(),
                    lastError = null,
                    isRefreshing = false,
                )
            }
        } catch (exc: Exception) {
            _uiState.update {
                it.copy(
                    lastError = exc.message ?: exc::class.simpleName ?: "Refresh failed",
                    isRefreshing = false,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_RECENT_BAR_LIMIT: Int = 20
    }
}
