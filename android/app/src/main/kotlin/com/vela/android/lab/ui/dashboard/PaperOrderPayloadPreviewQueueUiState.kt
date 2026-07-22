package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity

/** Read-only UI snapshot of the Phase 2.q local review queue. */
data class PaperOrderPayloadPreviewQueueUiState(
    val totalPreviews: Int,
    val recentRows: List<PaperOrderPayloadPreviewEntity>,
    val isRefreshing: Boolean,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
) {
    companion object {
        val Initial: PaperOrderPayloadPreviewQueueUiState =
            PaperOrderPayloadPreviewQueueUiState(
                totalPreviews = 0,
                recentRows = emptyList(),
                isRefreshing = false,
                lastRefreshAtEpochMillis = null,
                lastError = null,
            )
    }
}
