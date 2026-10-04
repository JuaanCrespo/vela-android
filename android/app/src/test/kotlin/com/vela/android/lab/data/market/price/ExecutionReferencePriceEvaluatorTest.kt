package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketDataProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 3.a.1-C: trust is derived from provenance, two-sided validity, timestamps, and freshness. */
class ExecutionReferencePriceEvaluatorTest {

    private val now = 1_000_000L
    private val evaluator = ExecutionReferencePriceEvaluator()

    private fun observation(
        bid: Double = 500.0,
        ask: Double = 500.0,
        provenance: MarketDataProvenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
        eventAgeMillis: Long = 0L,
        receivedAgeMillis: Long = eventAgeMillis,
    ): LiveQuoteObservation = LiveQuoteObservation(
        bid = bid,
        ask = ask,
        provenance = provenance,
        eventTimeEpochMillis = now - eventAgeMillis,
        receivedAtEpochMillis = now - receivedAgeMillis,
    )

    private fun evaluate(observation: LiveQuoteObservation?): ExecutionReferencePrice =
        evaluator.evaluate("SPY", observation, now)

    private fun reasonOf(reference: ExecutionReferencePrice): ExecutionPriceRejection =
        (reference as ExecutionReferencePrice.Rejected).reason

    @Test
    fun `real IEX quote with both sides is LIVE_QUOTE_MID at the mid price`() {
        val trusted = evaluate(observation(bid = 499.0, ask = 501.0, eventAgeMillis = 250L))
                as ExecutionReferencePrice.Trusted
        assertEquals(500.0, trusted.price, 1e-9)
        assertEquals(MarketPriceSource.LIVE_QUOTE_MID, trusted.source)
        assertEquals(250L, trusted.ageMillis)
        assertEquals(now, trusted.evaluatedAtEpochMillis)
    }

    @Test
    fun `one-sided quote is INVALID_QUOTE, never a single-side reference`() {
        assertEquals(
            ExecutionPriceRejection.INVALID_QUOTE,
            reasonOf(evaluate(observation(bid = 0.0, ask = 501.0))),
        )
        assertEquals(
            ExecutionPriceRejection.INVALID_QUOTE,
            reasonOf(evaluate(observation(bid = 499.0, ask = 0.0))),
        )
    }

    @Test
    fun `only ALPACA_IEX_REAL_TIME can be trusted, every other provenance is rejected`() {
        for (provenance in MarketDataProvenance.entries) {
            val reference = evaluate(observation(provenance = provenance))
            if (provenance == MarketDataProvenance.ALPACA_IEX_REAL_TIME) {
                assertTrue(reference is ExecutionReferencePrice.Trusted)
            } else {
                assertEquals(ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME, reasonOf(reference))
            }
        }
    }

    @Test
    fun `missing observation is NO_LIVE_QUOTE`() {
        assertEquals(ExecutionPriceRejection.NO_LIVE_QUOTE, reasonOf(evaluate(null)))
    }

    @Test
    fun `missing event or receipt timestamp is TIMESTAMP_MISSING`() {
        val noEvent = LiveQuoteObservation(500.0, 500.0, MarketDataProvenance.ALPACA_IEX_REAL_TIME, 0L, now)
        val noReceipt = LiveQuoteObservation(500.0, 500.0, MarketDataProvenance.ALPACA_IEX_REAL_TIME, now, 0L)
        assertEquals(ExecutionPriceRejection.TIMESTAMP_MISSING, reasonOf(evaluate(noEvent)))
        assertEquals(ExecutionPriceRejection.TIMESTAMP_MISSING, reasonOf(evaluate(noReceipt)))
    }

    @Test
    fun `NaN, infinite, zero, negative and crossed quotes are INVALID_QUOTE and never a price`() {
        val invalid = listOf(
            observation(bid = Double.NaN, ask = Double.NaN),
            observation(bid = Double.POSITIVE_INFINITY, ask = 500.0),
            observation(bid = 500.0, ask = Double.NEGATIVE_INFINITY),
            observation(bid = Double.POSITIVE_INFINITY, ask = Double.POSITIVE_INFINITY),
            observation(bid = 0.0, ask = 0.0),
            observation(bid = -1.0, ask = 500.0),
            observation(bid = 501.0, ask = 500.0),
        )
        for (case in invalid) {
            assertEquals(
                ExecutionPriceRejection.INVALID_QUOTE,
                reasonOf(evaluate(case)),
                "bid=${case.bid} ask=${case.ask}",
            )
        }
    }

    @Test
    fun `age exactly at the 10 second limit is trusted, one millisecond beyond is STALE`() {
        assertTrue(evaluate(observation(eventAgeMillis = 10_000L)) is ExecutionReferencePrice.Trusted)
        assertEquals(
            ExecutionPriceRejection.STALE,
            reasonOf(evaluate(observation(eventAgeMillis = 10_001L))),
        )
    }

    @Test
    fun `the OLDER of event and receipt time decides freshness, within the skew window`() {
        // Event 10.5 s old, received 11 s old: the event is 0.5 s after the receipt (inside the 2 s window),
        // and the conservative age is the older timestamp's, 11 s, so the quote is STALE.
        val reference = evaluate(observation(eventAgeMillis = 10_500L, receivedAgeMillis = 11_000L))
        assertEquals(ExecutionPriceRejection.STALE, reasonOf(reference))
    }

    @Test
    fun `receipt fresh but event 11 s old is STALE, because the event time is the older timestamp`() {
        val reference = evaluate(observation(eventAgeMillis = 11_000L, receivedAgeMillis = 0L))
        assertEquals(ExecutionPriceRejection.STALE, reasonOf(reference))
    }

    @Test
    fun `conservative age equals now minus the older timestamp`() {
        val trusted = evaluate(observation(eventAgeMillis = 1_000L, receivedAgeMillis = 2_500L))
                as ExecutionReferencePrice.Trusted
        assertEquals(2_500L, trusted.ageMillis)
    }

    @Test
    fun `small future skew is tolerated and clamped to age zero`() {
        val trusted = evaluate(observation(eventAgeMillis = -1_500L)) as ExecutionReferencePrice.Trusted
        assertEquals(0L, trusted.ageMillis)
    }

    @Test
    fun `future skew beyond 2 seconds is FUTURE_TIMESTAMP, never trusted`() {
        assertEquals(
            ExecutionPriceRejection.FUTURE_TIMESTAMP,
            reasonOf(evaluate(observation(eventAgeMillis = -2_001L))),
        )
    }

    @Test
    fun `event time far in the future with a current receipt time fails closed`() {
        val reference = evaluate(observation(eventAgeMillis = -60_000L, receivedAgeMillis = 0L))
        assertEquals(ExecutionPriceRejection.FUTURE_TIMESTAMP, reasonOf(reference))
    }

    @Test
    fun `receipt time far in the future with a current event time fails closed`() {
        val reference = evaluate(observation(eventAgeMillis = 0L, receivedAgeMillis = -60_000L))
        assertEquals(ExecutionPriceRejection.FUTURE_TIMESTAMP, reasonOf(reference))
    }

    @Test
    fun `event time after receipt beyond the skew tolerance fails closed`() {
        // Event 1 s old, received 4 s old: the event is 3 s after the receipt, beyond 2 s.
        val reference = evaluate(observation(eventAgeMillis = 1_000L, receivedAgeMillis = 4_000L))
        assertEquals(ExecutionPriceRejection.EVENT_AFTER_RECEIPT, reasonOf(reference))
    }

    @Test
    fun `event time after receipt within the skew tolerance is accepted at its conservative age`() {
        // Event 1 s old, received 2.5 s old: 1.5 s after the receipt, within 2 s.
        val trusted = evaluate(observation(eventAgeMillis = 1_000L, receivedAgeMillis = 2_500L))
                as ExecutionReferencePrice.Trusted
        assertEquals(2_500L, trusted.ageMillis)
    }

    @Test
    fun `freshness limit cannot be raised above the live-quote limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionReferencePriceEvaluator(maxAgeMillis = 10_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionReferencePriceEvaluator(maxFutureSkewMillis = 2_001L)
        }
    }

    @Test
    fun `rejections carry a credential-free code and the provenance that caused them`() {
        val rejected = evaluate(observation(provenance = MarketDataProvenance.LOCAL_DEMO_SYNTHETIC))
                as ExecutionReferencePrice.Rejected
        assertEquals(ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME, rejected.reason)
        assertEquals(MarketDataProvenance.LOCAL_DEMO_SYNTHETIC, rejected.provenance)
        assertFalse(rejected.toString().contains("500.0"))
    }
}
