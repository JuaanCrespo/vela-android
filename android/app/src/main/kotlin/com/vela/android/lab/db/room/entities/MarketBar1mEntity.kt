package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of [com.vela.android.lab.data.market.OneMinuteBar].
 *
 * Schema notes:
 *  - `bucketStartEpochMillis` is the minute-truncated UTC instant for
 *    the bar's bucket. Stored as Long so SQLite indexes work directly.
 *  - `(symbol, bucketStartEpochMillis)` is unique — a single bar per
 *    symbol per minute bucket, matching the Windows schema's
 *    `uq_market_bars_symbol_timestamp`.
 *  - No FK to assets table yet; we'll add that once the assets table
 *    lands in a later phase.
 */
@Entity(
    tableName = "market_bars_1m",
    indices = [
        Index(
            value = ["symbol", "bucketStartEpochMillis"],
            unique = true,
            name = "uq_market_bars_1m_symbol_bucket",
        ),
        Index(
            value = ["bucketStartEpochMillis"],
            name = "ix_market_bars_1m_bucket",
        ),
    ],
)
data class MarketBar1mEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val bucketStartEpochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val updateCount: Int,
    val syntheticVolume: Double,
    val lastUpdateTimeEpochMillis: Long?,
)
