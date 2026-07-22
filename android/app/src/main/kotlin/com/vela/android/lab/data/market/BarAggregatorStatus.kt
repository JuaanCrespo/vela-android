package com.vela.android.lab.data.market

/**
 * Port of `BarAggregatorStatus` from `app/data/bar_aggregator.py`.
 *
 * `countsBySymbol` is a list of `(symbol, count)` entries in sorted
 * symbol order, mirroring the Python tuple-of-tuples shape.
 */
data class BarAggregatorStatus(
    val totalBars: Int,
    val symbolCount: Int,
    val maxBarsPerSymbol: Int,
    val countsBySymbol: List<Pair<String, Int>>,
    val latestBar: OneMinuteBar? = null,
)
