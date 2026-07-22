package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaStreamEndpoint
import com.vela.android.lab.data.market.source.alpaca.AlpacaTestStreamMarketDataClient
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the "Alpaca Paper Credentials" + Phase 2.d
 * stream-to-pipeline status card.
 *
 * Phase 2.c.1 responsibilities (credentials):
 *  - own the Key ID and Secret text-field contents (binding-only;
 *    cleared on save and clear)
 *  - persist credentials via [SecureAlpacaCredentialsStore.save]
 *    and surface a `Credentials configured: true/false` boolean
 *    derived **only** from the secure store
 *  - never store or surface the credential value after save
 *
 * Phase 2.d responsibilities (pipeline wiring):
 *  - start [AlpacaTestStreamPipelineBridge] when the user taps
 *    **Test Alpaca Market Data**, so incoming FAKEPACA bars are
 *    forwarded to the offline pipeline coordinator and persisted
 *    into Room
 *  - stop the bridge when the user taps **Stop Alpaca test stream**
 *  - mirror the bridge's read-only counters / last result into
 *    the dashboard UI state
 *
 * The ViewModel never reaches the trading or account APIs (compile
 * -time enforced via the [MarketDataClient] contract and the
 * reflection contract tests).
 */
class AlpacaTestStreamViewModel(
    private val client: AlpacaTestStreamMarketDataClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val pipelineBridge: AlpacaTestStreamPipelineBridge,
) : ViewModel() {

    private val _uiState: MutableStateFlow<AlpacaTestStreamUiState> =
        MutableStateFlow(AlpacaTestStreamUiState.Initial)

    val uiState: StateFlow<AlpacaTestStreamUiState> = _uiState.asStateFlow()

    private val statusJob: Job
    private val updatesJob: Job
    private val bridgeJob: Job
    private val healthJob: Job

    init {
        viewModelScope.launch { refreshCredentialsConfigured() }

        statusJob = viewModelScope.launch {
            client.connectionStatus.collect { status ->
                _uiState.update {
                    it.copy(
                        connectionState = status.state.name,
                        errorMessage = status.lastError?.message,
                    )
                }
            }
        }
        updatesJob = viewModelScope.launch {
            client.updates.collect { update ->
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
        bridgeJob = viewModelScope.launch {
            pipelineBridge.state.collect { bridgeState ->
                _uiState.update {
                    it.copy(
                        pipelineReceived = bridgeState.receivedUpdates,
                        pipelinePersisted = bridgeState.persistedUpdates,
                        lastPipelineSignalState = bridgeState.lastSignalState,
                        lastPipelineError = bridgeState.lastError,
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

    fun onKeyIdInputChange(value: String) {
        _uiState.update { it.copy(keyIdInput = value, lastSaveStatus = null, lastClearStatus = null) }
    }

    fun onSecretInputChange(value: String) {
        _uiState.update { it.copy(secretInput = value, lastSaveStatus = null, lastClearStatus = null) }
    }

    fun saveCredentials() {
        val current = _uiState.value
        val keyId = current.keyIdInput.trim()
        val secret = current.secretInput.trim()
        if (keyId.isEmpty() || secret.isEmpty()) {
            _uiState.update {
                it.copy(
                    lastSaveStatus = "Both fields are required.",
                    lastClearStatus = null,
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                credentialsStore.save(AlpacaCredentials(keyId = keyId, secret = secret))
                _uiState.update {
                    it.copy(
                        keyIdInput = "",
                        secretInput = "",
                        credentialsConfigured = true,
                        lastSaveStatus = "Saved.",
                        lastClearStatus = null,
                    )
                }
            } catch (exc: Exception) {
                _uiState.update {
                    it.copy(
                        keyIdInput = "",
                        secretInput = "",
                        lastSaveStatus = "Save failed: ${exc.message ?: exc::class.simpleName}",
                        lastClearStatus = null,
                    )
                }
            }
        }
    }

    fun clearCredentials() {
        viewModelScope.launch {
            try {
                credentialsStore.clear()
                _uiState.update {
                    it.copy(
                        keyIdInput = "",
                        secretInput = "",
                        credentialsConfigured = false,
                        lastSaveStatus = null,
                        lastClearStatus = "Cleared.",
                    )
                }
            } catch (exc: Exception) {
                _uiState.update {
                    it.copy(
                        lastClearStatus = "Clear failed: ${exc.message ?: exc::class.simpleName}",
                        lastSaveStatus = null,
                    )
                }
            }
        }
    }

    fun startSmokeTest() {
        viewModelScope.launch {
            pipelineBridge.start(viewModelScope)
            client.subscribe(setOf(AlpacaStreamEndpoint.TEST_SYMBOL))
            client.connect()
        }
    }

    fun stopSmokeTest() {
        viewModelScope.launch {
            client.disconnect()
            pipelineBridge.stop()
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
        pipelineBridge.stop()
    }
}
