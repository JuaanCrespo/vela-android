package com.vela.android.lab.ui.dashboard

/**
 * UI state for the offline dashboard. Held as a `StateFlow` inside
 * [OfflineDashboardViewModel] and read by the Compose screen via
 * `collectAsStateWithLifecycle`.
 *
 * Pure-data; no Android imports — JVM tests assert on this directly.
 */
data class OfflineDashboardUiState(
    val modeLabel: String,
    val realLocked: Boolean,
    val pipelineLabel: String,
    val lastSymbol: String?,
    val lastPrice: Double?,
    val lastBarClose: Double?,
    val lastFeatureDirection: String?,
    val lastSignalState: String?,
    val lastSignalScore: Int?,
    val persistedBarCount: Int,
    val journalEventCount: Int,
    val lastError: String?,
) {
    companion object {
        val Initial: OfflineDashboardUiState = OfflineDashboardUiState(
            modeLabel = "READ_ONLY",
            realLocked = true,
            pipelineLabel = "Offline demo",
            lastSymbol = null,
            lastPrice = null,
            lastBarClose = null,
            lastFeatureDirection = null,
            lastSignalState = null,
            lastSignalScore = null,
            persistedBarCount = 0,
            journalEventCount = 0,
            lastError = null,
        )
    }
}
