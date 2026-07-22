package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.source.alpaca.AlpacaStockMarketDataClient
import com.vela.android.lab.data.market.source.alpaca.AlpacaStreamEndpoint
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Phase 2.e "Alpaca real market data — read only"
 * card.
 *
 *  - Reads `Credentials configured` from the existing
 *    [SecureAlpacaCredentialsStore]; **never** prompts for, edits,
 *    or stores credentials itself. Credential entry remains the
 *    job of the Paper-credentials card.
 *  - Mirrors [client.connectionStatus] and [client.updates] into
 *    UI state.
 *  - Drives the existing [AlpacaTestStreamPipelineBridge] (a new
 *    instance bound to the stock client + coordinator) so SPY bars
 *    flow through the offline pipeline coordinator just like
 *    FAKEPACA bars.
 *  - Subscribes to a single symbol (default
 *    [AlpacaStreamEndpoint.STOCK_PRIMARY_SYMBOL] = `SPY`) before
 *    opening the socket.
 *
 * No order / account / trading methods.
 */
class AlpacaStockStreamViewModel(
    private val client: AlpacaStockMarketDataClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val pipelineBridge: AlpacaTestStreamPipelineBridge,
    private val tickBuffer: MarketTickBuffer = MarketTickBuffer(),
    private val symbol: String = AlpacaStreamEndpoint.STOCK_PRIMARY_SYMBOL,
) : ViewModel() {

    /** Phase 2.i: read-only access to the tick buffer for dashboard binding. */
    val tickBufferRef: MarketTickBuffer get() = tickBuffer

    private val _uiState: MutableStateFlow<AlpacaStockStreamUiState> =
        MutableStateFlow(AlpacaStockStreamUiState.initial(client.feedUrl, symbol))

    val uiState: StateFlow<AlpacaStockStreamUiState> = _uiState.asStateFlow()

    private val statusJob: Job
    private val updatesJob: Job
    private val bridgeJob: Job
    private val healthJob: Job
    private val quotesJob: Job

    init {
        viewModelScope.launch { refreshCredentialsConfigured() }

        statusJob = viewModelScope.launch {
            client.connectionStatus.collect { status ->
                _uiState.update {
                    it.copy(
                        connectionState = status.state.name,
                        lastError = status.lastError?.message,
                    )
                }
            }
        }
        updatesJob = viewModelScope.launch {
            client.updates.collect { update ->
                tickBuffer.recordBar(update.symbol)
                _uiState.update {
                    it.copy(
                        lastBarSymbol = update.symbol,
                        lastBarClose = update.close,
                        lastBarTimestamp = update.timestamp.toString(),
                        barsReceived = it.barsReceived + 1,
                    )
                }
            }
        }
        quotesJob = viewModelScope.launch {
            client.quotes.collect { tick ->
                tickBuffer.pushQuote(tick)
            }
        }
        bridgeJob = viewModelScope.launch {
            pipelineBridge.state.collect { bridgeState ->
                _uiState.update {
                    it.copy(
                        pipelinePersisted = bridgeState.persistedUpdates,
                    )
                }
            }
        }
        healthJob = viewModelScope.launch {
            client.health.collect { h ->
                _uiState.update {
                    it.copy(
                        healthPhase = h.phase.name,
                        lastConnectedAtEpochMillis = h.lastConnectedAtEpochMillis,
                        lastDisconnectedAtEpochMillis = h.lastDisconnectedAtEpochMillis,
                        lastMessageAtEpochMillis = h.lastMessageAtEpochMillis,
                        reconnectAttempts = h.reconnectAttempts,
                        lastErrorType = h.lastErrorType,
                    )
                }
            }
        }
    }

    /** Refresh `credentialsConfigured` from the secure store. */
    fun refresh() {
        viewModelScope.launch { refreshCredentialsConfigured() }
    }

    /** Start the read-only stock stream + attach the bridge. */
    fun startStream() {
        startStream(setOf(symbol))
    }

    /**
     * Phase 2.g: start the read-only stock stream subscribed to a
     * caller-supplied set of normalized stock symbols. Empty sets
     * fall back to the constructor default symbol so the existing
     * single-symbol flow keeps working.
     */
    fun startStream(symbols: Set<String>) {
        val effective = symbols.ifEmpty { setOf(symbol) }
        viewModelScope.launch {
            pipelineBridge.start(viewModelScope)
            client.subscribe(effective)
            _uiState.update { it.copy(subscribed = true) }
            client.connect()
        }
    }

    /** Stop the stream and detach the bridge. */
    fun stopStream() {
        viewModelScope.launch {
            client.disconnect()
            pipelineBridge.stop()
            _uiState.update { it.copy(subscribed = false) }
        }
    }

    private suspend fun refreshCredentialsConfigured() {
        val configured = credentialsStore.hasCredentials()
        _uiState.update { it.copy(credentialsConfigured = configured) }
    }

    override fun onCleared() {
        statusJob.cancel()
        updatesJob.cancel()
        bridgeJob.cancel()
        healthJob.cancel()
        quotesJob.cancel()
        pipelineBridge.stop()
    }
}
