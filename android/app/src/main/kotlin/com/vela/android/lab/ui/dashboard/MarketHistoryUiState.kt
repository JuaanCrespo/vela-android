package com.vela.android.lab.ui.dashboard

/**
 * Phase 2.j read-only UI state for the "Recent market data — read
 * only" card. Pure data; no Android imports.
 *
 * Snapshots Room-persisted state for the *current watchlist*:
 *  - per symbol: latest bar close + timestamp, recent bar count,
 *    latest feature direction, latest signal state + score,
 *    journal-event count for the symbol
 *  - aggregate: total persisted bars, total journal events, the
 *    `Instant.toEpochMilli()` of the last refresh, last error.
 *
 * **No field carries a credential value.** No network surface; the
 * VM only reads from the existing Phase 1.e repositories.
 */
data class MarketHistoryUiState(
    val symbols: List<String>,
    val perSymbol: Map<String, PerSymbolHistory>,
    val totalPersistedBars: Int,
    val totalJournalEvents: Int,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
    val isRefreshing: Boolean,
) {
    companion object {
        val Initial: MarketHistoryUiState = MarketHistoryUiState(
            symbols = emptyList(),
            perSymbol = emptyMap(),
            totalPersistedBars = 0,
            totalJournalEvents = 0,
            lastRefreshAtEpochMillis = null,
            lastError = null,
            isRefreshing = false,
        )
    }
}

/**
 * Per-symbol read-only snapshot rendered as one row of the history
 * card. All fields are nullable so a watchlist symbol with no
 * persisted bars yet still renders cleanly as a row of dashes.
 */
data class PerSymbolHistory(
    val symbol: String,
    val latestBarClose: Double?,
    val latestBarTimestampMillis: Long?,
    val recentBarCount: Int,
    val latestFeatureDirection: String?,
    val latestSignalState: String?,
    val latestSignalScore: Int?,
    val journalEventCount: Int,
) {
    companion object {
        fun empty(symbol: String): PerSymbolHistory = PerSymbolHistory(
            symbol = symbol,
            latestBarClose = null,
            latestBarTimestampMillis = null,
            recentBarCount = 0,
            latestFeatureDirection = null,
            latestSignalState = null,
            latestSignalScore = null,
            journalEventCount = 0,
        )
    }
}
