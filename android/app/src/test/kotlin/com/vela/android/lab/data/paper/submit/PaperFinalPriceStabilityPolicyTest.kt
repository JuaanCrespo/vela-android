package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.ExecutionPriceRejection
import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.ExecutionReferencePriceEvaluator
import com.vela.android.lab.data.market.price.LiveQuoteObservation
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.market.tick.MarketDataProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperFinalPriceStabilityPolicyTest {
    private val policy = PaperFinalPriceStabilityPolicy()

    @Test
    fun `exact same fresh trusted price passes`() {
        val result = evaluate(submitTestPrice())
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0.0, result.driftPercent)
    }

    @Test
    fun `fresher trusted quote within tolerance passes`() {
        val result = evaluate(submitTestPrice(price = 500.50, ageMillis = 100L))
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertTrue(result.sourceCompatible)
        assertEquals(0.1, result.driftPercent!!, 0.000_001)
    }

    @Test
    fun `two-sided quote mid within tolerance passes`() {
        val preview = submitTestPreview(priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(
            preview,
            quote(bid = 499.45, ask = 499.55, eventAgeMillis = 100L),
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(MarketPriceSource.LIVE_QUOTE_MID.name, result.finalPriceSource)
        assertEquals(0.1, result.driftPercent!!, 0.000_001)
    }

    @Test
    fun `a numeric preview relabelled as a trusted source cannot pass against the current trusted quote`() {
        // Labelled LIVE_QUOTE_MID, but its numeric price (727) is a Room-like value. The current trusted
        // quote is 520, so the drift check refuses it. The label alone never authorizes.
        val relabelled = submitTestPreview(previewPriceUsd = 727.0, priceSource = MarketPriceSource.LIVE_QUOTE_MID)
        val result = policy.evaluate(relabelled, submitTestPrice(price = 520.0), SUBMIT_TEST_NOW)
        assertEquals(PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED, result.result)
        assertFalse(result.allowed)
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
    fun `stale live quote is rejected upstream and yields no trusted price`() {
        // The quote is stamped at SUBMIT_TEST_NOW and evaluated 60 s later, so the evaluator
        // refuses it. The policy never receives a trusted value. (Epoch timestamps <= 0 are
        // TIMESTAMP_MISSING, so the clock here must be past the 60 s window.)
        val rejected = ExecutionReferencePriceEvaluator().evaluate(
            symbol = "SPY",
            observation = LiveQuoteObservation(
                bid = 500.0,
                ask = 500.0,
                provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
                eventTimeEpochMillis = SUBMIT_TEST_NOW,
                receivedAtEpochMillis = SUBMIT_TEST_NOW,
            ),
            nowEpochMillis = SUBMIT_TEST_NOW + 60_000L,
        )
        assertEquals(ExecutionPriceRejection.STALE, (rejected as ExecutionReferencePrice.Rejected).reason)
        val result = evaluate(rejected)
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, result.result)
        assertFalse(result.allowed)
    }

    @Test
    fun `missing final reference blocks as no trusted execution price`() {
        val result = policy.evaluate(
            submitTestPreview(),
            null,
            SUBMIT_TEST_NOW,
        )
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, result.result)
        assertFalse(result.allowed)
        assertEquals(PriceFreshness.MISSING.name, result.finalPriceFreshness)
    }

    @Test
    fun `trusted price that becomes stale before submit blocks with not-fresh`() {
        // Trusted when reviewed (age 1 s). Re-evaluated 9.001 s later the age is 10.001 s.
        val reviewed = submitTestPrice(ageMillis = 1_000L)
        val result = policy.evaluate(
            submitTestPreview(),
            reviewed,
            SUBMIT_TEST_NOW + 9_001L,
        )
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertEquals(10_001L, result.finalPriceAgeMillis)
        assertEquals(10_001L, result.rawFinalPriceAgeMillis)
        assertEquals(PriceFreshness.STALE.name, result.finalPriceFreshness)
    }

    @Test
    fun `different symbol blocks`() {
        val result = evaluate(submitTestPrice(symbol = "QQQ"))
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `price beyond short final age window blocks even if it was fresh at review`() {
        val reviewed = submitTestPrice(ageMillis = 1_000L)
        val result = policy.evaluate(submitTestPreview(), reviewed, SUBMIT_TEST_NOW + 9_001L)
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertEquals(10_001L, result.finalPriceAgeMillis)
        assertEquals(10_001L, result.rawFinalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    // --- 3.a.1-C: a legacy Room preview can never be compared with, or authorize, anything ---

    @Test
    fun `legacy Room preview cannot pass even against a trusted final quote`() {
        val legacyPreview = submitTestPreview(priceSource = MarketPriceSource.ROOM_BAR_CLOSE)
        val result = policy.evaluate(legacyPreview, submitTestPrice(), SUBMIT_TEST_NOW)
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
        assertFalse(result.sourceCompatible)
        assertFalse(result.allowed)
    }

    @Test
    fun `self comparison of a legacy Room reference cannot show zero drift and authorize`() {
        // The pathological case: preview and final both from the same legacy Room close.
        // A Room close cannot become a trusted final, so the final side has no price at all.
        val legacyPreview = submitTestPreview(
            previewPriceUsd = 500.0,
            priceSource = MarketPriceSource.ROOM_BAR_CLOSE,
        )
        val result = policy.evaluate(legacyPreview, null, SUBMIT_TEST_NOW)
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, result.result)
        assertFalse(result.allowed)
    }

    // --- Phase 2.v.3 future-timestamp skew tolerance, re-expressed for trusted quotes ---

    @Test
    fun `small negative raw age passes with effective age clamped to zero`() {
        val result = evaluate(submitTestPrice(ageMillis = -87L))
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-87L, result.rawFinalPriceAgeMillis)
        assertTrue(result.futureSkewToleranceApplied)
        assertEquals(2_000L, result.allowedFutureSkewMillis)
    }

    @Test
    fun `raw age exactly at negative tolerance boundary passes`() {
        val result = evaluate(submitTestPrice(ageMillis = -2_000L))
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-2_000L, result.rawFinalPriceAgeMillis)
        assertTrue(result.futureSkewToleranceApplied)
    }

    @Test
    fun `raw age just beyond negative tolerance is rejected upstream as a future timestamp`() {
        val rejected = ExecutionReferencePriceEvaluator().evaluate(
            symbol = "SPY",
            observation = LiveQuoteObservation(
                bid = 500.0,
                ask = 500.0,
                provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
                eventTimeEpochMillis = SUBMIT_TEST_NOW + 2_001L,
                receivedAtEpochMillis = SUBMIT_TEST_NOW + 2_001L,
            ),
            nowEpochMillis = SUBMIT_TEST_NOW,
        )
        assertEquals(
            ExecutionPriceRejection.FUTURE_TIMESTAMP,
            (rejected as ExecutionReferencePrice.Rejected).reason,
        )
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, evaluate(rejected).result)
    }

    @Test
    fun `large future timestamp still blocks`() {
        val result = evaluate(
            ExecutionReferencePriceEvaluator().evaluate(
                symbol = "SPY",
                observation = LiveQuoteObservation(
                    bid = 500.0,
                    ask = 500.0,
                    provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
                    eventTimeEpochMillis = SUBMIT_TEST_NOW + 60_000L,
                    receivedAtEpochMillis = SUBMIT_TEST_NOW + 60_000L,
                ),
                nowEpochMillis = SUBMIT_TEST_NOW,
            ),
        )
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, result.result)
    }

    @Test
    fun `positive age within max age does not mark tolerance applied`() {
        val result = evaluate(submitTestPrice(ageMillis = 42L))
        assertEquals(PaperFinalPriceGateResult.ALLOWED, result.result)
        assertEquals(42L, result.finalPriceAgeMillis)
        assertEquals(42L, result.rawFinalPriceAgeMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    @Test
    fun `negative raw age within tolerance combined with excessive drift still blocks drift`() {
        val result = evaluate(submitTestPrice(price = 550.0, ageMillis = -500L))
        assertEquals(PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED, result.result)
        assertTrue(result.futureSkewToleranceApplied)
        assertEquals(0L, result.finalPriceAgeMillis)
        assertEquals(-500L, result.rawFinalPriceAgeMillis)
    }

    @Test
    fun `non positive quote blocks as invalid, never as a zero price`() {
        val result = evaluate(submitTestPrice(price = 0.0, ageMillis = -500L))
        assertEquals(PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE, result.result)
        assertEquals(null, result.finalPriceUsd)
    }

    @Test
    fun `symbol mismatch still blocks even with negative raw age within tolerance`() {
        val result = evaluate(submitTestPrice(symbol = "QQQ", ageMillis = -500L))
        assertEquals(PaperFinalPriceGateResult.PRICE_NOT_FRESH, result.result)
    }

    @Test
    fun `tolerance defaults are documented on evaluation`() {
        val result = evaluate(submitTestPrice())
        assertNotNull(result.rawFinalPriceAgeMillis)
        assertEquals(2_000L, result.allowedFutureSkewMillis)
        assertFalse(result.futureSkewToleranceApplied)
    }

    private fun quote(
        bid: Double,
        ask: Double,
        eventAgeMillis: Long = 0L,
    ): ExecutionReferencePrice = ExecutionReferencePriceEvaluator().evaluate(
        symbol = "SPY",
        observation = LiveQuoteObservation(
            bid = bid,
            ask = ask,
            provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
            eventTimeEpochMillis = SUBMIT_TEST_NOW - eventAgeMillis,
            receivedAtEpochMillis = SUBMIT_TEST_NOW - eventAgeMillis,
        ),
        nowEpochMillis = SUBMIT_TEST_NOW,
    )

    private fun evaluate(finalPrice: ExecutionReferencePrice?): PaperFinalPriceEvaluation =
        policy.evaluate(submitTestPreview(), finalPrice, SUBMIT_TEST_NOW)
}
