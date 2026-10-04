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
    /**
     * Set by the producer from its connected endpoint (3.a.1-C). Defaults to
     * [MarketDataProvenance.UNKNOWN], which is execution-ineligible, so a producer that
     * forgets to declare provenance cannot create execution authority.
     */
    val provenance: MarketDataProvenance = MarketDataProvenance.UNKNOWN,
) {
    val spread: Double get() = askPrice - bidPrice
    val latencyMillis: Long get() = receivedAtMillis - marketTimestampMillis
}
