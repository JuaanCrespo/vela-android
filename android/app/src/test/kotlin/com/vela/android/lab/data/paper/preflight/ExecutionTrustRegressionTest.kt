package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.data.market.price.ExecutionPriceRejection
import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.tick.MarketDataProvenance
import com.vela.android.lab.data.market.tick.MarketTick
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.state.AppState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3.a.1-C publication-audit regressions. Each test runs the real provider and the real preflight
 * engine on the same in-memory tick buffer. Nothing here touches the network, a broker, or a device.
 */
class ExecutionTrustRegressionTest {

    private val nowMillis = 100_000L

    private val engine = PaperOrderPreflightEngine()

    private val account = PaperAccountSnapshot(
        cashUsd = 50_000.0, buyingPowerUsd = 200_000.0,
        equityUsd = 100_000.0, portfolioValueUsd = 100_000.0,
        tradingBlocked = false, accountBlocked = false,
        patternDayTrader = false, currency = "USD", status = "ACTIVE",
    )

    private val clock = PaperClockSnapshot(
        isOpen = true, nextOpenIso = null, nextCloseIso = null, timestampIso = null,
    )

    private fun provider(buffer: MarketTickBuffer): MarketPriceSnapshotProvider =
        MarketPriceSnapshotProvider(tickBuffer = buffer, clock = { Instant.ofEpochMilli(nowMillis) })

    private fun tick(
        symbol: String = "SPY",
        bid: Double = 500.0,
        ask: Double = 500.0,
        eventAtMillis: Long,
        receivedAtMillis: Long,
        provenance: MarketDataProvenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
    ): MarketTick = MarketTick(
        symbol = symbol,
        bidPrice = bid,
        askPrice = ask,
        marketTimestampMillis = eventAtMillis,
        receivedAtMillis = receivedAtMillis,
        source = "alpaca-iex-stream",
        provenance = provenance,
    )

    private fun intent(symbol: String = "SPY"): PaperOrderIntent = PaperOrderIntent(
        symbol = symbol,
        side = OrderSide.BUY,
        quantity = 1.0,
        type = OrderType.MARKET,
        tif = TimeInForce.DAY,
        source = IntentSource.MANUAL_DRY_RUN,
        createdAtEpochMillis = 1_000L,
        clientDryRunId = "regression-1",
    )

    private fun preflight(symbol: String, reference: ExecutionReferencePrice?): PaperOrderPreflightResult =
        engine.preflight(
            intent = intent(symbol),
            account = account,
            clockSnap = clock,
            positions = emptyList(),
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY", "QQQ"),
            appState = AppState(),
            credentialsConfigured = true,
            executionReference = reference,
        )

    private fun reviewFor(buffer: MarketTickBuffer, symbol: String): ExecutionReferencePrice =
        runBlocking { provider(buffer).executionReferenceFor(symbol) }

    private fun rejectionOf(reference: ExecutionReferencePrice): ExecutionPriceRejection =
        (reference as ExecutionReferencePrice.Rejected).reason

    private fun assertBlockedWith(result: PaperOrderPreflightResult, rejection: ExecutionPriceRejection) {
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.contains(PreflightBlockReason.NoTrustedExecutionPrice(rejection)))
        assertNull(result.estimatedNotionalUsd)
        assertEquals("NONE", result.priceSource)
    }

    @Test
    fun `future event time ahead of now with a current receipt fails closed`() {
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(eventAtMillis = nowMillis + 60_000L, receivedAtMillis = nowMillis))
        }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.FUTURE_TIMESTAMP, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.FUTURE_TIMESTAMP)
    }

    @Test
    fun `future receipt time ahead of now with a current event fails closed`() {
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(eventAtMillis = nowMillis, receivedAtMillis = nowMillis + 60_000L))
        }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.FUTURE_TIMESTAMP, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.FUTURE_TIMESTAMP)
    }

    @Test
    fun `event time after receipt beyond the skew tolerance fails closed`() {
        // Event 1 s old, received 4 s old: the event is 3 s after the receipt, beyond the 2 s tolerance.
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(eventAtMillis = nowMillis - 1_000L, receivedAtMillis = nowMillis - 4_000L))
        }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.EVENT_AFTER_RECEIPT, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.EVENT_AFTER_RECEIPT)
    }

    @Test
    fun `event time after receipt within the skew tolerance is accepted at its conservative age`() {
        // Event 1 s old, received 2.5 s old: 1.5 s after the receipt, within the 2 s tolerance.
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(eventAtMillis = nowMillis - 1_000L, receivedAtMillis = nowMillis - 2_500L))
        }
        val reference = reviewFor(buffer, "SPY")
        val trusted = reference as ExecutionReferencePrice.Trusted
        assertEquals(2_500L, trusted.ageMillis)
        val result = preflight("SPY", reference)
        assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result.status)
        assertEquals(500.0, result.estimatedNotionalUsd)
    }

    @Test
    fun `unknown provenance fails closed in the provider and in preflight`() {
        // A tick built without a declared provenance defaults to UNKNOWN, which is never execution-eligible.
        val undeclared = MarketTick(
            symbol = "SPY",
            bidPrice = 500.0,
            askPrice = 500.0,
            marketTimestampMillis = nowMillis,
            receivedAtMillis = nowMillis,
            source = "alpaca-iex-stream",
        )
        val buffer = MarketTickBuffer().also { it.pushQuote(undeclared) }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME)
    }

    @Test
    fun `a trusted QQQ quote never authorizes an SPY intent`() {
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(symbol = "QQQ", bid = 400.0, ask = 400.0, eventAtMillis = nowMillis, receivedAtMillis = nowMillis))
        }
        // The SPY lookup finds no SPY quote, so it has no reference at all.
        val spyReview = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.NO_LIVE_QUOTE, rejectionOf(spyReview))
        assertBlockedWith(preflight("SPY", spyReview), ExecutionPriceRejection.NO_LIVE_QUOTE)

        // Even when the QQQ reference itself is trusted, it is refused for an SPY intent.
        val qqqReview = reviewFor(buffer, "QQQ")
        assertTrue(qqqReview is ExecutionReferencePrice.Trusted)
        assertBlockedWith(preflight("SPY", qqqReview), ExecutionPriceRejection.SYMBOL_MISMATCH)
    }

    @Test
    fun `no trusted quote fails closed deterministically with no zero, NaN or placeholder price`() {
        val reference = reviewFor(MarketTickBuffer(), "SPY")
        assertEquals(ExecutionPriceRejection.NO_LIVE_QUOTE, rejectionOf(reference))
        val result = preflight("SPY", reference)
        assertBlockedWith(result, ExecutionPriceRejection.NO_LIVE_QUOTE)
        assertNull(result.priceAgeMillis)
        assertEquals("MISSING", result.priceFreshness)
    }

    @Test
    fun `one-sided quote fails closed as INVALID_QUOTE and is never used as a reference`() {
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(bid = 0.0, ask = 500.0, eventAtMillis = nowMillis, receivedAtMillis = nowMillis))
        }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.INVALID_QUOTE, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.INVALID_QUOTE)
    }

    @Test
    fun `stale trusted quote blocks in preflight and is not a warning-only price`() {
        val buffer = MarketTickBuffer().also {
            it.pushQuote(tick(eventAtMillis = nowMillis - 60_000L, receivedAtMillis = nowMillis - 60_000L))
        }
        val reference = reviewFor(buffer, "SPY")
        assertEquals(ExecutionPriceRejection.STALE, rejectionOf(reference))
        assertBlockedWith(preflight("SPY", reference), ExecutionPriceRejection.STALE)
    }
}
