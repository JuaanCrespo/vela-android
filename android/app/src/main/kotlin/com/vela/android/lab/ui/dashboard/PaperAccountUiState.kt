package com.vela.android.lab.ui.dashboard

/**
 * Phase 2.k read-only UI state for the "Alpaca Paper account —
 * read only" card. Pure data; no Android imports.
 *
 * Carries **no** credential value. The boolean `credentialsConfigured`
 * is derived from the secure store at refresh time, never from the
 * credential value itself.
 */
data class PaperAccountUiState(
    val credentialsConfigured: Boolean,
    val isRefreshing: Boolean,
    val marketOpen: Boolean?,
    val nextOpenIso: String?,
    val nextCloseIso: String?,
    val equityUsd: Double?,
    val buyingPowerUsd: Double?,
    val cashUsd: Double?,
    val portfolioValueUsd: Double?,
    val tradingBlocked: Boolean?,
    val accountBlocked: Boolean?,
    val patternDayTrader: Boolean?,
    val accountStatus: String?,
    val positionsCount: Int?,
    val topPositions: List<PaperPositionRow>,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
) {
    companion object {
        val Initial: PaperAccountUiState = PaperAccountUiState(
            credentialsConfigured = false,
            isRefreshing = false,
            marketOpen = null,
            nextOpenIso = null,
            nextCloseIso = null,
            equityUsd = null,
            buyingPowerUsd = null,
            cashUsd = null,
            portfolioValueUsd = null,
            tradingBlocked = null,
            accountBlocked = null,
            patternDayTrader = null,
            accountStatus = null,
            positionsCount = null,
            topPositions = emptyList(),
            lastRefreshAtEpochMillis = null,
            lastError = null,
        )
    }
}

/** Compact UI row for the positions list. */
data class PaperPositionRow(
    val symbol: String,
    val qty: Double,
    val marketValueUsd: Double,
    val unrealizedPlUsd: Double,
)
