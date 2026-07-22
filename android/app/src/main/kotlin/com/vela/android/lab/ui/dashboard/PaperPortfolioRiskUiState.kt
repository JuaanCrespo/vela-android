package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.paper.PaperPortfolioSnapshot
import com.vela.android.lab.data.paper.PerSymbolPaperExposure
import com.vela.android.lab.data.paper.RiskFlag

/**
 * Phase 2.l read-only UI state for the "Paper portfolio risk —
 * read only" card. Pure data; no Android imports.
 *
 * Carries **no** credential value. Every field is derived from
 * already-fetched Paper snapshots + already-persisted Room data.
 */
data class PaperPortfolioRiskUiState(
    val credentialsConfigured: Boolean,
    val isRefreshing: Boolean,
    val portfolio: PaperPortfolioSnapshot,
    val exposures: List<PerSymbolPaperExposure>,
    val flags: List<RiskFlag>,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
) {
    companion object {
        val Initial: PaperPortfolioRiskUiState = PaperPortfolioRiskUiState(
            credentialsConfigured = false,
            isRefreshing = false,
            portfolio = PaperPortfolioSnapshot.Empty,
            exposures = emptyList(),
            flags = emptyList(),
            lastRefreshAtEpochMillis = null,
            lastError = null,
        )
    }
}
