package com.vela.android.lab.data.market

import java.time.Instant
import java.util.Locale

/**
 * Port of `SymbolFeatures` from `app/data/feature_engine.py`.
 *
 * `direction` is one of "up", "down", "flat" (the Python strings,
 * preserved verbatim so log messages and assertions match).
 */
data class SymbolFeatures(
    val symbol: String,
    val bucketStart: Instant,
    val shortReturn: Double,
    val percentChange: Double,
    val barRange: Double,
    val direction: String,
    val recentBarCount: Int,
) {
    val summary: String
        get() = buildString {
            append(symbol)
            append(' ')
            append(direction)
            append(" ret=")
            append(String.format(Locale.ROOT, "%.4f", shortReturn))
            append(" pct=")
            append(String.format(Locale.ROOT, "%.4f", percentChange))
            append(" range=")
            append(String.format(Locale.ROOT, "%.2f", barRange))
            append(" bars=")
            append(recentBarCount)
        }
}
