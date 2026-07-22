package com.vela.android.lab.ui.dashboard

/**
 * Phase 2.g read-only UI state for the watchlist card. Pure data;
 * no Android imports.
 *
 * The view-model populates `symbols` from
 * [com.vela.android.lab.data.watchlist.WatchlistRepository.load] and
 * keeps `perSymbol` in sync with the active stream's
 * [com.vela.android.lab.data.pipeline.AlpacaTestStreamBridgeState.perSymbol].
 *
 * Carries no credential value.
 */
data class WatchlistUiState(
    val symbols: List<String>,
    val maxSymbols: Int,
    val addInput: String,
    val lastStatus: String?,
    val perSymbol: Map<String, WatchlistSymbolStats>,
) {

    val canAddMore: Boolean get() = symbols.size < maxSymbols

    companion object {
        fun initial(maxSymbols: Int): WatchlistUiState = WatchlistUiState(
            symbols = emptyList(),
            maxSymbols = maxSymbols,
            addInput = "",
            lastStatus = null,
            perSymbol = emptyMap(),
        )
    }
}

/**
 * UI projection of [com.vela.android.lab.data.pipeline.SymbolBridgeStats]
 * — kept as a separate data class so the UI layer does not depend
 * on the data-layer type and so the field set can drift without
 * cross-layer churn.
 */
data class WatchlistSymbolStats(
    val received: Int,
    val persisted: Int,
    val lastClose: Double?,
    val lastSignalState: String?,
) {
    companion object {
        val Initial: WatchlistSymbolStats = WatchlistSymbolStats(
            received = 0,
            persisted = 0,
            lastClose = null,
            lastSignalState = null,
        )
    }
}
