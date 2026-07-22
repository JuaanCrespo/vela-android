package com.vela.android.lab.data.market.price

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarketPriceFreshnessPolicyTest {

    private val policy = MarketPriceFreshnessPolicy()

    @Test
    fun `live quote fresh under 10 seconds`() {
        assertEquals(
            PriceFreshness.FRESH,
            policy.classify(MarketPriceSource.LIVE_QUOTE_MID, 5_000L),
        )
    }

    @Test
    fun `live quote stale at 10001 ms`() {
        assertEquals(
            PriceFreshness.STALE,
            policy.classify(MarketPriceSource.LIVE_QUOTE_MID, 10_001L),
        )
    }

    @Test
    fun `live bar fresh at 90 seconds boundary`() {
        assertEquals(
            PriceFreshness.FRESH,
            policy.classify(MarketPriceSource.LIVE_BAR_CLOSE, 90_000L),
        )
    }

    @Test
    fun `live bar stale at 91 seconds`() {
        assertEquals(
            PriceFreshness.STALE,
            policy.classify(MarketPriceSource.LIVE_BAR_CLOSE, 91_000L),
        )
    }

    @Test
    fun `room bar fresh under 5 minutes`() {
        assertEquals(
            PriceFreshness.FRESH,
            policy.classify(MarketPriceSource.ROOM_BAR_CLOSE, 60_000L),
        )
    }

    @Test
    fun `room bar stale over 5 minutes`() {
        assertEquals(
            PriceFreshness.STALE,
            policy.classify(MarketPriceSource.ROOM_BAR_CLOSE, 6L * 60_000L),
        )
    }

    @Test
    fun `negative age is clamped to zero (clock skew tolerated)`() {
        assertEquals(
            PriceFreshness.FRESH,
            policy.classify(MarketPriceSource.LIVE_QUOTE_MID, -50L),
        )
    }

    @Test
    fun `NONE source classifies as MISSING`() {
        assertEquals(
            PriceFreshness.MISSING,
            policy.classify(MarketPriceSource.NONE, null),
        )
    }

    @Test
    fun `null age on non-NONE source classifies as STALE`() {
        assertEquals(
            PriceFreshness.STALE,
            policy.classify(MarketPriceSource.LIVE_QUOTE_MID, null),
        )
    }

    @Test
    fun `custom thresholds are honored`() {
        val tight = MarketPriceFreshnessPolicy(
            liveQuoteFreshMillis = 1_000L,
            liveBarFreshMillis = 5_000L,
            roomBarFreshMillis = 60_000L,
        )
        assertEquals(
            PriceFreshness.STALE,
            tight.classify(MarketPriceSource.LIVE_QUOTE_MID, 2_000L),
        )
        assertEquals(1_000L, tight.thresholdFor(MarketPriceSource.LIVE_QUOTE_MID))
    }
}
