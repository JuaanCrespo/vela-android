package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperFinalPriceStabilityPolicyTest {
    private val policy = PaperFinalPriceStabilityPolicy()

    @Test
    fun `exact same fresh price passes`() {
        val result = evaluate(submitTestPrice())
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0.0, result.driftPercent)
    }

    @Test
    fun `fresher source and price within tolerance passes`() {
        val result = evaluate(
            submitTestPrice(
                price = 500.50,
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = 100L,
            ),
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertTrue(result.sourceCompatible)
        assertEquals(0.1, result.driftPercent!!, 0.000_001)
    }

    @Test
    fun `same quote source class within tolerance passes`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                price = 499.50,
                source = MarketPriceSource.LIVE_QUOTE_BID_ASK,
                ageMillis = 100L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
    }

    @Test
    fun `price exactly at drift threshold passes`() {
        val result = evaluate(submitTestPrice(price = 501.25))
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0.25, result.driftPercent!!, 0.000_001)
    }

    @Test
    fun `drift above threshold blocks with explicit reason`() {
        val result = evaluate(submitTestPrice(price = 501.26))
        assertEquals(PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED, result.result)
        assertTrue(result.driftPercent!! > result.allowedDriftPercent)
    }

    @Test
    fun `stale final price blocks`() {
        val result = evaluate(submitTestPrice(freshness = PriceFreshness.STALE))
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `missing final price blocks`() {
        val result = policy.evaluate(
            submitTestPreview(),
            MarketPriceSnapshot.missing("SPY", "missing"),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertFalse(result.allowed)
    }

    @Test
    fun `different symbol blocks`() {
        val result = evaluate(submitTestPrice(symbol = "QQQ"))
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `price beyond short final age window blocks even if marked fresh`() {
        val result = evaluate(submitTestPrice(ageMillis = 10_001L))
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertEquals(10_001L, result.finalPriceAgeMillis)
        assertEquals(10_001L, result.rawFinalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    @Test
    fun `lower quality source class cannot replace a quote preview`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(source = MarketPriceSource.ROOM_BAR_CLOSE),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertFalse(result.sourceCompatible)
    }

    // --- Phase 2.v.3 future-timestamp skew tolerance ---

    @Test
    fun `phase 2v3 small negative raw age passes with effective age clamped to zero`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -87L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-87L, result.rawFinalPriceAgeMillis)
        assertTrue(result.futureSkewToleranceApplied)
        assertEquals(2_000L, result.allowedFutureSkewMillis)
    }

    @Test
    fun `phase 2v3 raw age exactly at negative tolerance boundary passes`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -2_000L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-2_000L, result.rawFinalPriceAgeMillis)
        assertTrue(result.futureSkewToleranceApplied)
    }

    @Test
    fun `phase 2v3 raw age just beyond negative tolerance blocks`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -2_001L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertEquals(-2_001L, result.rawFinalPriceAgeMillis)
        assertEquals(-2_001L, result.finalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    @Test
    fun `phase 2v3 large future timestamp still blocks`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -60_000L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertEquals(-60_000L, result.rawFinalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    @Test
    fun `phase 2v3 positive age within max age does not mark tolerance applied`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = 42L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(42L, result.finalPriceAgeMillis)
        assertEquals(42L, result.rawFinalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    @Test
    fun `phase 2v3 negative raw age within tolerance combined with excessive drift still blocks drift`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                price = 550.0,
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -500L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED, result.result)
        assertTrue(result.futureSkewToleranceApplied)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-500L, result.rawFinalPriceAgeMillis)
    }

    @Test
    fun `phase 2v3 non positive final price still blocks even with negative raw age`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                price = 0.0,
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -500L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `phase 2v3 symbol mismatch still blocks even with negative raw age within tolerance`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            submitTestPrice(
                symbol = "QQQ",
                source = MarketPriceSource.LIVE_QUOTE_MID,
                ageMillis = -500L,
            ),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `phase 2v3 tolerance defaults are documented on evaluation`() {
        val result = evaluate(submitTestPrice())
        assertNotNull(result.rawFinalPriceAgeMillis)
        assertEquals(2_000L, result.allowedFutureSkewMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    private fun evaluate(finalPrice: MarketPriceSnapshot): PaperFinalPriceEvaluation =
        policy.evaluate(submitTestPreview(), finalPrice, SUBMIT_TEST_NOW)
}
