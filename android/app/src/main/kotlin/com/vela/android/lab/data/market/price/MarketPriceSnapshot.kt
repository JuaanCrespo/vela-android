package com.vela.android.lab.data.market.price

/**
 * Phase 3.a.1-C: this file keeps only the price-source and freshness vocabulary.
 *
 * The former `MarketPriceSnapshot` data class was removed from the execution path. Execution
 * authority is now [ExecutionReferencePrice], which has no numeric value unless it is
 * [ExecutionReferencePrice.Trusted].
 */

/** Provenance label of a price value. Only the two live-quote sources can be execution references. */
enum class MarketPriceSource {
    /** Mid of latest live quote: (bid + ask) / 2. Highest priority. */
    LIVE_QUOTE_MID,

    /** Live quote present but bid/ask unbalanced — uses ask or bid alone. */
    LIVE_QUOTE_BID_ASK,

    /** Latest in-memory bar close from the live IEX stream. Never an execution reference. */
    LIVE_BAR_CLOSE,

    /**
     * Locally persisted Room bar close (`market_bars_1m`). Legacy, unknown provenance. Never an
     * execution reference (3.a.1-C). Retained only so historical audit rows remain readable.
     */
    ROOM_BAR_CLOSE,

    /** No price available from any source. */
    NONE,
}

/** Freshness band of a price, per [MarketPriceFreshnessPolicy]. */
enum class PriceFreshness {
    FRESH,
    STALE,
    MISSING,
}
