package com.vela.android.lab.data.market.price

/**
 * Explicit freshness thresholds per [MarketPriceSource].
 *
 * The policy is **explicit and testable**. Phase 3.a.1-C uses the live-quote threshold as the
 * execution limit (see [ExecutionReferencePriceEvaluator]). The Room-bar threshold is
 * display-only. A Room bar is never an execution reference, and its bucket start is not a
 * freshness clock for execution.
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
