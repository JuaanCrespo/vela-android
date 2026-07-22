package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity

/**
 * Phase 2.n read-only UI state for the "Dry-run audit — local only"
 * card. Pure data; no Android imports. Carries no credential value
 * and no Alpaca account id (the underlying entity stores neither).
 */
data class PaperOrderDryRunAuditUiState(
    val totalDryRuns: Int,
    val recentRows: List<PaperOrderDryRunAuditEntity>,
    val isRefreshing: Boolean,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
) {
    companion object {
        val Initial: PaperOrderDryRunAuditUiState = PaperOrderDryRunAuditUiState(
            totalDryRuns = 0,
            recentRows = emptyList(),
            isRefreshing = false,
            lastRefreshAtEpochMillis = null,
            lastError = null,
        )
    }
}
