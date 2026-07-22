package com.vela.android.lab.data.market.price

/**
 * Phase 2.o explicit freshness thresholds for a [MarketPriceSnapshot].
 *
 * The policy is **explicit and testable**. It is **not** a hard
 * block by itself — it is an indicator the preflight engine
 * consumes to decide between a `WARNING_ONLY` and a `BLOCKED` status.
 *
 * Thresholds were chosen conservatively for a single-second-tape
 * IEX feed: a live quote older than 10 s is stale; a 1-minute bar
 * older than 90 s is stale; a Room bar older than 5 minutes is
 * stale and surfaced as such even though the preflight only uses
 * it as a fallback.
 *
 * **No method here submits orders or touches the network.**
 */
class MarketPriceFreshnessPolicy(
    private val liveQuoteFreshMillis: Long = DEFAULT_LIVE_QUOTE_FRESH_MILLIS,
    private val liveBarFreshMillis: Long = DEFAULT_LIVE_BAR_FRESH_MILLIS,
    private val roomBarFreshMillis: Long = DEFAULT_ROOM_BAR_FRESH_MILLIS,
) {

    /**
     * Classify [ageMillis] for the given [source] into a [PriceFreshness]
     * band. Negative ages are treated as 0 (clock skew).
     */
    fun classify(source: MarketPriceSource, ageMillis: Long?): PriceFreshness {
        if (ageMillis == null) return when (source) {
            MarketPriceSource.NONE -> PriceFreshness.MISSING
            else -> PriceFreshness.STALE
        }
        val age = ageMillis.coerceAtLeast(0L)
        val threshold = thresholdFor(source) ?: return PriceFreshness.MISSING
        return if (age <= threshold) PriceFreshness.FRESH else PriceFreshness.STALE
    }

    /** Threshold for the given source. Null = source has no threshold. */
    fun thresholdFor(source: MarketPriceSource): Long? = when (source) {
        MarketPriceSource.LIVE_QUOTE_MID, MarketPriceSource.LIVE_QUOTE_BID_ASK ->
            liveQuoteFreshMillis
        MarketPriceSource.LIVE_BAR_CLOSE -> liveBarFreshMillis
        MarketPriceSource.ROOM_BAR_CLOSE -> roomBarFreshMillis
        MarketPriceSource.NONE -> null
    }

    companion object {
        const val DEFAULT_LIVE_QUOTE_FRESH_MILLIS: Long = 10_000L
        const val DEFAULT_LIVE_BAR_FRESH_MILLIS: Long = 90_000L
        const val DEFAULT_ROOM_BAR_FRESH_MILLIS: Long = 5L * 60L * 1_000L
    }
}
