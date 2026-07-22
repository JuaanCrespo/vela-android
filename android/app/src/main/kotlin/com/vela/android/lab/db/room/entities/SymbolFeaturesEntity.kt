package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of [com.vela.android.lab.data.market.SymbolFeatures].
 *
 * One row per (symbol, bucketStart). `direction` is stored as the
 * raw string ("up", "down", "flat") to match the Python journal.
 */
@Entity(
    tableName = "symbol_features",
    indices = [
        Index(
            value = ["symbol", "bucketStartEpochMillis"],
            unique = true,
            name = "uq_symbol_features_symbol_bucket",
        ),
        Index(
            value = ["bucketStartEpochMillis"],
            name = "ix_symbol_features_bucket",
        ),
    ],
)
data class SymbolFeaturesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val bucketStartEpochMillis: Long,
    val shortReturn: Double,
    val percentChange: Double,
    val barRange: Double,
    val direction: String,
    val recentBarCount: Int,
)
