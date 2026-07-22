package com.vela.android.lab.data.market

import java.time.Instant

/**
 * Port of `BootstrapMarketUpdate` from `app/data/stream_manager.py`,
 * **without** any Qt or Alpaca dependency. This is the input shape the
 * Android lab's [OneMinuteBarAggregator] consumes. In Phase 1 the only
 * producer is the test suite. In Phase 2 the Alpaca paper market data
 * client will produce these from REST/WebSocket frames.
 *
 * Timestamps are `Instant` (always UTC), removing the Python tzinfo
 * normalization branch that Qt code used.
 */
data class BootstrapMarketUpdate(
    val symbol: String,
    val sequence: Int,
    val price: Double,
    val change: Double,
    val timestamp: Instant = Instant.now(),
    val source: String = "bootstrap-simulated",
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val volume: Double? = null,
)
