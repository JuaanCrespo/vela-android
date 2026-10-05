package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.pipeline.OfflineMarketPipelineCoordinator
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the offline dashboard.
 *
 * Demo buttons construct deterministic [BootstrapMarketUpdate]s
 * (incrementing sequence + small price bump) and push them through
 * [OfflineMarketPipelineCoordinator]. The pipeline result drives the
 * visible UI state, and persisted counts come from the repositories.
 *
 * Phase 3.a.1-D.1: the generators run only when [demoGeneratorsEnabled] is true. The composition root sets it from
 * the Debug build flag (`demoGeneratorsEnabledForThisBuild`). A closed gate rejects generation before any repository
 * or coordinator call. The reset action stays available, because it writes no market data.
 *
 * No network. No order submission. No Alpaca. No REAL unlock.
 */
class OfflineDashboardViewModel(
    private val coordinator: OfflineMarketPipelineCoordinator,
    private val marketDataRepository: MarketDataRepository,
    private val featureRepository: FeatureRepository,
    private val signalRepository: SignalRepository,
    private val journalRepository: JournalRepository,
    private val demoGeneratorsEnabled: Boolean,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val _uiState: MutableStateFlow<OfflineDashboardUiState> =
        MutableStateFlow(OfflineDashboardUiState.Initial.copy(demoGeneratorsAvailable = demoGeneratorsEnabled))

    val uiState: StateFlow<OfflineDashboardUiState> = _uiState.asStateFlow()

    private var sequenceCounter: Int = 0
    private var btcPrice: Double = INITIAL_BTC_PRICE
    private var spyPrice: Double = INITIAL_SPY_PRICE

    fun generateBtcUpdate() {
        if (!demoGeneratorsEnabled) {
            rejectDemoGeneration()
            return
        }
        viewModelScope.launch {
            sequenceCounter += 1
            btcPrice += BTC_TICK
            dispatchUpdate(
                BootstrapMarketUpdate(
                    symbol = "BTC/USD",
                    sequence = sequenceCounter,
                    price = btcPrice,
                    change = BTC_TICK,
                    timestamp = clock(),
                ),
            )
        }
    }

    fun generateSpyUpdate() {
        if (!demoGeneratorsEnabled) {
            rejectDemoGeneration()
            return
        }
        viewModelScope.launch {
            sequenceCounter += 1
            spyPrice += SPY_TICK
            dispatchUpdate(
                BootstrapMarketUpdate(
                    symbol = "SPY",
                    sequence = sequenceCounter,
                    price = spyPrice,
                    change = SPY_TICK,
                    timestamp = clock(),
                ),
            )
        }
    }

    /**
     * Phase 3.a.1-D.1 rejection. A closed gate writes no market data and reports why. It does not throw, and it does
     * not advance the price walk or the sequence counter.
     */
    private fun rejectDemoGeneration() {
        _uiState.update { current ->
            current.copy(demoStatus = DEMO_STATUS_DISABLED)
        }
    }

    /**
     * Resets only the in-memory demo price walk. Persisted market bars, features, signals, and journal
     * rows are NOT deleted (Phase 3.a.1-D). Their provenance is mixed or unknown, so no demo-only subset
     * can be identified safely. The sequence counter stays monotonic. The visible counters stay accurate
     * because they are read from storage, not cleared.
     */
    fun resetDemoStatus() {
        btcPrice = INITIAL_BTC_PRICE
        spyPrice = INITIAL_SPY_PRICE
        _uiState.update { current ->
            current.copy(demoStatus = DEMO_STATUS_RESET, lastError = null)
        }
    }

    private suspend fun dispatchUpdate(update: BootstrapMarketUpdate) {
        try {
            val result = coordinator.addUpdate(update)
            val persistedBarCount = marketDataRepository.countAll()
            val journalCount = journalRepository.count()
            _uiState.update { current ->
                current.copy(
                    lastSymbol = if (result.symbol.isNotEmpty()) result.symbol else current.lastSymbol,
                    lastPrice = update.price,
                    lastBarClose = result.bar?.close ?: current.lastBarClose,
                    lastFeatureDirection = result.features?.direction ?: current.lastFeatureDirection,
                    lastSignalState = result.signal?.state?.value ?: current.lastSignalState,
                    lastSignalScore = result.signal?.score ?: current.lastSignalScore,
                    persistedBarCount = persistedBarCount,
                    journalEventCount = journalCount,
                    lastError = null,
                )
            }
        } catch (exc: Exception) {
            _uiState.update { current ->
                current.copy(lastError = exc.message ?: exc::class.simpleName)
            }
        }
    }

    companion object {
        private const val INITIAL_BTC_PRICE: Double = 50_000.0
        private const val INITIAL_SPY_PRICE: Double = 400.0
        private const val BTC_TICK: Double = 5.0
        private const val SPY_TICK: Double = 0.25

        /** Shown after a demo reset. It states what was kept. Nothing is reported as deleted. */
        private const val DEMO_STATUS_RESET: String =
            "Demo generator prices reset. Stored market bars, features, signals and journal were kept."

        /** Shown when a closed build gate rejects a generator. Nothing is reported as written. */
        private const val DEMO_STATUS_DISABLED: String =
            "Demo generators are available only in Debug builds. No market data was written."
    }
}
