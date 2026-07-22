package com.vela.android.lab.data.market.price

/**
 * Phase 2.o read-only market-price snapshot. Pure data; no Android
 * imports. **No method on this hierarchy submits orders, calls the
 * network, or mutates account state.**
 *
 * The snapshot answers: *"What is the freshest local price we have
 * for this symbol right now, and how confident are we?"*
 *
 * Field semantics:
 *  - [price] is the canonical scalar the preflight engine should
 *    use as `price × quantity`. Derived from the source.
 *  - [bid] / [ask] are present when [source] is a quote; both null
 *    when the source is a bar close.
 *  - [marketTimestampMillis] is the server-stamped event time.
 *  - [deviceReceivedAtMillis] is the device clock at the moment our
 *    code received the event (null for [MarketPriceSource.ROOM_BAR_CLOSE]
 *    because Room rows are historical).
 *  - [ageMillis] is `now - marketTimestamp` (or `now - deviceReceivedAt`
 *    if more recent), clamped to non-negative; null when missing.
 *  - [reason] is a short human-readable string when [freshness] is
 *    STALE or MISSING.
 */
data class MarketPriceSnapshot(
    val symbol: String,
    val price: Double?,
    val bid: Double?,
    val ask: Double?,
    val marketTimestampMillis: Long?,
    val deviceReceivedAtMillis: Long?,
    val ageMillis: Long?,
    val source: MarketPriceSource,
    val freshness: PriceFreshness,
    val reason: String?,
) {
    /** Convenience: `ask - bid` when both present; null otherwise. */
    val spread: Double? get() = if (bid != null && ask != null) ask - bid else null

    val hasPrice: Boolean get() = price != null && freshness != PriceFreshness.MISSING

    companion object {
        fun missing(symbol: String, reason: String): MarketPriceSnapshot =
            MarketPriceSnapshot(
                symbol = symbol,
                price = null,
                bid = null,
                ask = null,
                marketTimestampMillis = null,
                deviceReceivedAtMillis = null,
                ageMillis = null,
                source = MarketPriceSource.NONE,
                freshness = PriceFreshness.MISSING,
                reason = reason,
            )
    }
}

/** Provenance of the price the snapshot returned. */
enum class MarketPriceSource {
    /** Mid of latest live quote: (bid + ask) / 2. Highest priority. */
    LIVE_QUOTE_MID,

    /** Live quote present but bid/ask unbalanced — uses ask or bid alone. */
    LIVE_QUOTE_BID_ASK,

    /** Latest in-memory bar close from the live IEX stream. */
    LIVE_BAR_CLOSE,

    /** Locally persisted Room bar close (Phase 1.e pipeline). */
    ROOM_BAR_CLOSE,

    /** No price available from any source. */
    NONE,
}

/** Freshness band the snapshot falls into per [MarketPriceFreshnessPolicy]. */
enum class PriceFreshness {
    FRESH,
    STALE,
    MISSING,
}
