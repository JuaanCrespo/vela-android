package com.vela.android.lab.data.market

import java.time.Instant
import java.util.Locale

/**
 * Port of `SymbolSignal` from `app/data/signal_engine.py`.
 */
data class SymbolSignal(
    val symbol: String,
    val bucketStart: Instant,
    val state: SignalState,
    val score: Int,
    val shortReturn: Double,
    val percentChange: Double,
    val barRange: Double,
    val direction: String,
) {
    val summary: String
        get() = buildString {
            append(symbol)
            append(' ')
            append(state.value)
            append(" score=")
            append(score)
            append(" ret=")
            append(String.format(Locale.ROOT, "%.4f", shortReturn))
            append(" pct=")
            append(String.format(Locale.ROOT, "%.4f", percentChange))
            append(" range=")
            append(String.format(Locale.ROOT, "%.2f", barRange))
        }
}
