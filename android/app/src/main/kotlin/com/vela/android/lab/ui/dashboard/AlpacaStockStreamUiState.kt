package com.vela.android.lab.ui.dashboard

/**
 * Debug-only UI state for the Phase 2.e "Alpaca real market data —
 * read only" card. Pure data; no Android imports.
 *
 * The card mirrors a read-only `AlpacaStockMarketDataClient` plus
 * the shared `AlpacaTestStreamPipelineBridge` (with a stock-specific
 * instance). No field on this state carries a credential value.
 */
data class AlpacaStockStreamUiState(
    val feedUrl: String,
    val symbol: String,
    val credentialsConfigured: Boolean,
    val connectionState: String,
    val subscribed: Boolean,
    val barsReceived: Int,
    val pipelinePersisted: Int,
    val lastBarSymbol: String?,
    val lastBarClose: Double?,
    val lastBarTimestamp: String?,
    val lastError: String?,
    // Phase 2.f read-only diagnostics
    val healthPhase: String,
    val lastConnectedAtEpochMillis: Long?,
    val lastDisconnectedAtEpochMillis: Long?,
    val lastMessageAtEpochMillis: Long?,
    val reconnectAttempts: Int,
    val lastErrorType: String?,
) {
    companion object {
        fun initial(feedUrl: String, symbol: String): AlpacaStockStreamUiState =
            AlpacaStockStreamUiState(
                feedUrl = feedUrl,
                symbol = symbol,
                credentialsConfigured = false,
                connectionState = "DISCONNECTED",
                subscribed = false,
                barsReceived = 0,
                pipelinePersisted = 0,
                lastBarSymbol = null,
                lastBarClose = null,
                lastBarTimestamp = null,
                lastError = null,
                healthPhase = "DISCONNECTED",
                lastConnectedAtEpochMillis = null,
                lastDisconnectedAtEpochMillis = null,
                lastMessageAtEpochMillis = null,
                reconnectAttempts = 0,
                lastErrorType = null,
            )
    }
}
