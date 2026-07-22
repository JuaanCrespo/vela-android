package com.vela.android.lab.data.market.tick

/**
 * Phase 2.i read-only quote tick. Pure data; no Android imports.
 *
 *  - `marketTimestampMillis` is the server-stamped event time, in
 *    epoch millis.
 *  - `receivedAtMillis` is the device wall-clock at the moment the
 *    Alpaca client received and parsed the frame.
 *  - `latencyMillis` is `receivedAtMillis - marketTimestampMillis`.
 *    Reported raw (can be negative on clock skew) so the dashboard
 *    can surface it honestly.
 *  - `source` is the Alpaca feed label ("alpaca-iex-stream", etc.)
 *    so future second feeds remain distinguishable.
 *
 * No field on this class carries credential or trading-shape data.
 */
data class MarketTick(
    val symbol: String,
    val bidPrice: Double,
    val askPrice: Double,
    val marketTimestampMillis: Long,
    val receivedAtMillis: Long,
    val source: String,
) {
    val spread: Double get() = askPrice - bidPrice
    val latencyMillis: Long get() = receivedAtMillis - marketTimestampMillis
}
