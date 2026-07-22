package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of [com.vela.android.lab.data.market.SymbolSignal].
 *
 * `state` is persisted as the enum's string value (BULLISH / BEARISH /
 * NEUTRAL) to match the Windows journal payload format.
 */
@Entity(
    tableName = "symbol_signals",
    indices = [
        Index(
            value = ["symbol", "bucketStartEpochMillis"],
            unique = true,
            name = "uq_symbol_signals_symbol_bucket",
        ),
        Index(
            value = ["bucketStartEpochMillis"],
            name = "ix_symbol_signals_bucket",
        ),
        Index(
            value = ["state"],
            name = "ix_symbol_signals_state",
        ),
    ],
)
data class SymbolSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val bucketStartEpochMillis: Long,
    val state: String,
    val score: Int,
    val shortReturn: Double,
    val percentChange: Double,
    val barRange: Double,
    val direction: String,
)
