package com.vela.android.lab.data.pipeline

/**
 * Journal event type identifiers emitted by
 * [OfflineMarketPipelineCoordinator]. Kept as plain string constants
 * (instead of an enum) so the journal table can ingest events from
 * other producers later without a type-coupling boundary.
 */
object PipelineEventTypes {
    const val MARKET_UPDATE_RECEIVED: String = "market_update_received"
    const val BAR_PERSISTED: String = "bar_persisted"
    const val FEATURES_PERSISTED: String = "features_persisted"
    const val SIGNAL_PERSISTED: String = "signal_persisted"
    const val INVALID_MARKET_UPDATE: String = "invalid_market_update"
}
