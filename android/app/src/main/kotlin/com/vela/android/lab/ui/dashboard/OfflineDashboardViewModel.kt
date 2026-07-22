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
 * No network. No order submission. No Alpaca. No REAL unlock.
 */
class OfflineDashboardViewModel(
    private val coordinator: OfflineMarketPipelineCoordinator,
    private val marketDataRepository: MarketDataRepository,
    private val featureRepository: FeatureRepository,
    private val signalRepository: SignalRepository,
    private val journalRepository: JournalRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val _uiState: MutableStateFlow<OfflineDashboardUiState> =
        MutableStateFlow(OfflineDashboardUiState.Initial)

    val uiState: StateFlow<OfflineDashboardUiState> = _uiState.asStateFlow()

    private var sequenceCounter: Int = 0
    private var btcPrice: Double = INITIAL_BTC_PRICE
    private var spyPrice: Double = INITIAL_SPY_PRICE

    fun generateBtcUpdate() {
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

    fun clearDemoState() {
        viewModelScope.launch {
            try {
                marketDataRepository.clearAll()
                featureRepository.clear()
                signalRepository.clear()
                journalRepository.clear()
                sequenceCounter = 0
                btcPrice = INITIAL_BTC_PRICE
                spyPrice = INITIAL_SPY_PRICE
                _uiState.value = OfflineDashboardUiState.Initial
            } catch (exc: Exception) {
                _uiState.update { current ->
                    current.copy(lastError = "Clear failed: ${exc.message ?: exc::class.simpleName}")
                }
            }
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
    }
}
