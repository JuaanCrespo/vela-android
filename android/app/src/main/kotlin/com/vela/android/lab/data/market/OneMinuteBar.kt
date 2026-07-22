package com.vela.android.lab.data.market

import java.time.Instant

/**
 * Port of `OneMinuteBar` from `app/data/bar_aggregator.py`.
 *
 * `bucketStart` is the minute-truncated UTC instant that this bar
 * represents. All other fields mirror the Python dataclass.
 */
data class OneMinuteBar(
    val symbol: String,
    val bucketStart: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val updateCount: Int,
    val syntheticVolume: Double,
    val lastUpdateTime: Instant? = null,
)
