package com.vela.android.lab.data.pipeline

/**
 * Read-only snapshot of [AlpacaTestStreamPipelineBridge] progress.
 *
 * - [receivedUpdates] counts every [com.vela.android.lab.data.market.BootstrapMarketUpdate]
 *   the bridge saw on the upstream [com.vela.android.lab.data.market.source.MarketDataClient.updates]
 *   flow, including any that the coordinator rejected.
 * - [persistedUpdates] counts only the ones the coordinator
 *   accepted (`PipelineStepResult.accepted == true`).
 * - The "last*" fields reflect the most recent successfully-forwarded
 *   update — useful for surfacing "what just landed in the journal"
 *   without polling Room.
 * - **No credential value** is stored in any field of this class.
 */
data class AlpacaTestStreamBridgeState(
    val receivedUpdates: Int,
    val persistedUpdates: Int,
    val lastSymbol: String?,
    val lastPrice: Double?,
    val lastBarClose: Double?,
    val lastSignalState: String?,
    val lastJournalEventsForUpdate: Int,
    val lastError: String?,
    /**
     * Phase 2.g per-symbol routing metrics. Keyed by the normalized
     * symbol. Empty by default. Updated atomically with the
     * aggregate counters on every [AlpacaTestStreamPipelineBridge.handleUpdate]
     * call so that the dashboard can render a watchlist row without
     * polling Room.
     */
    val perSymbol: Map<String, SymbolBridgeStats> = emptyMap(),
) {
    companion object {
        val Initial: AlpacaTestStreamBridgeState = AlpacaTestStreamBridgeState(
            receivedUpdates = 0,
            persistedUpdates = 0,
            lastSymbol = null,
            lastPrice = null,
            lastBarClose = null,
            lastSignalState = null,
            lastJournalEventsForUpdate = 0,
            lastError = null,
            perSymbol = emptyMap(),
        )
    }
}

/**
 * Per-symbol Phase 2.g routing metrics held inside the bridge state.
 * Pure-data; no Android imports; no credential value.
 */
data class SymbolBridgeStats(
    val received: Int,
    val persisted: Int,
    val lastClose: Double?,
    val lastSignalState: String?,
    val lastBarBucketStartEpochMillis: Long?,
) {
    companion object {
        val Initial: SymbolBridgeStats = SymbolBridgeStats(
            received = 0,
            persisted = 0,
            lastClose = null,
            lastSignalState = null,
            lastBarBucketStartEpochMillis = null,
        )
    }
}
