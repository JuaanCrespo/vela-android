package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.data.market.price.ExecutionPriceRejection
import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.ExecutionReferencePriceEvaluator
import com.vela.android.lab.data.market.price.LiveQuoteObservation
import com.vela.android.lab.data.market.tick.MarketDataProvenance
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.state.AppState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3.a.1-C: the preflight engine accepts only an execution reference. A trusted reference
 * feeds notional and buying power. Any other reference blocks, and there is no fallback value.
 */
class PaperPreflightWithSnapshotTest {

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

    private val nowMillis = 100_000L

    private fun newIntent(
        symbol: String = "SPY",
        side: OrderSide = OrderSide.BUY,
        qty: Double = 1.0,
    ): PaperOrderIntent = PaperOrderIntent(
        symbol = symbol, side = side, quantity = qty,
        type = OrderType.MARKET, tif = TimeInForce.DAY,
        source = IntentSource.MANUAL_DRY_RUN,
        createdAtEpochMillis = 1_000L,
        clientDryRunId = "drylet-$symbol-$qty",
    )

    /** A trusted IEX reference, built through the production evaluator. */
    private fun trusted(
        price: Double = 500.0,
        eventAgeMillis: Long = 500L,
        provenance: MarketDataProvenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
    ): ExecutionReferencePrice = ExecutionReferencePriceEvaluator().evaluate(
        symbol = "SPY",
        observation = LiveQuoteObservation(
            bid = price,
            ask = price,
            provenance = provenance,
            eventTimeEpochMillis = nowMillis - eventAgeMillis,
            receivedAtEpochMillis = nowMillis - eventAgeMillis,
        ),
        nowEpochMillis = nowMillis,
    )

    private fun preflight(
        intent: PaperOrderIntent = newIntent(),
        reference: ExecutionReferencePrice?,
        accountSnapshot: PaperAccountSnapshot? = account,
    ): PaperOrderPreflightResult = engine.preflight(
        intent = intent,
        account = accountSnapshot,
        clockSnap = clock,
        positions = emptyList(),
        latestSignalState = "BULLISH",
        watchlist = setOf("SPY"),
        appState = AppState(),
        credentialsConfigured = true,
        executionReference = reference,
    )

    @Test
    fun `trusted IEX quote feeds notional and result records LIVE_QUOTE_MID FRESH`() {
        val result = preflight(intent = newIntent(qty = 2.0), reference = trusted(price = 500.0))
        assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result.status)
        assertEquals(1000.0, result.estimatedNotionalUsd)
        assertEquals("LIVE_QUOTE_MID", result.priceSource)
        assertEquals("FRESH", result.priceFreshness)
        assertEquals(500L, result.priceAgeMillis)
    }

    @Test
    fun `rejected reference blocks with NoTrustedExecutionPrice and no notional`() {
        val rejected = ExecutionReferencePrice.Rejected("SPY", ExecutionPriceRejection.NO_LIVE_QUOTE)
        val result = preflight(reference = rejected)
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(
            result.blockReasons.contains(
                PreflightBlockReason.NoTrustedExecutionPrice(ExecutionPriceRejection.NO_LIVE_QUOTE),
            ),
        )
        assertNull(result.estimatedNotionalUsd)
        assertEquals("NONE", result.priceSource)
        assertEquals("MISSING", result.priceFreshness)
        assertNull(result.priceAgeMillis)
    }

    @Test
    fun `missing reference is a block, identical in effect to a rejected one`() {
        val result = preflight(reference = null)
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(
            result.blockReasons.contains(
                PreflightBlockReason.NoTrustedExecutionPrice(ExecutionPriceRejection.NOT_PROVIDED),
            ),
        )
        assertNull(result.estimatedNotionalUsd)
    }

    @Test
    fun `stale trusted reference blocks, it is no longer a warning-only price`() {
        val stale = ExecutionReferencePriceEvaluator().evaluate(
            symbol = "SPY",
            observation = LiveQuoteObservation(
                bid = 500.0,
                ask = 500.0,
                provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
                eventTimeEpochMillis = nowMillis - 30_000L,
                receivedAtEpochMillis = nowMillis - 30_000L,
            ),
            nowEpochMillis = nowMillis,
        )
        val result = preflight(reference = stale)
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(
            result.blockReasons.contains(
                PreflightBlockReason.NoTrustedExecutionPrice(ExecutionPriceRejection.STALE),
            ),
        )
        assertNull(result.estimatedNotionalUsd)
    }

    @Test
    fun `demo or test quote never feeds notional, even when fresh`() {
        val demo = trusted(price = 500.0, provenance = MarketDataProvenance.LOCAL_DEMO_SYNTHETIC)
        val test = trusted(price = 500.0, provenance = MarketDataProvenance.ALPACA_TEST_SYNTHETIC)
        for (reference in listOf(demo, test)) {
            assertTrue(reference is ExecutionReferencePrice.Rejected, "must be rejected: $reference")
            val result = preflight(reference = reference)
            assertEquals(PreflightStatus.BLOCKED, result.status)
            assertNull(result.estimatedNotionalUsd)
        }
    }

    @Test
    fun `buying power is checked against the trusted price only`() {
        val poorAccount = account.copy(buyingPowerUsd = 100.0)
        val result = preflight(
            intent = newIntent(qty = 1.0),
            reference = trusted(price = 500.0),
            accountSnapshot = poorAccount,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(
            result.blockReasons.any {
                it is PreflightBlockReason.InsufficientBuyingPower && it.needed == 500.0
            },
        )
    }

    @Test
    fun `quantity is unchanged by the execution price`() {
        val result = preflight(intent = newIntent(qty = 3.0), reference = trusted(price = 400.0))
        assertEquals(3.0, result.intent.quantity)
        assertEquals(3.0, result.positionImpactQty)
        assertEquals(1200.0, result.estimatedNotionalUsd)
    }

    @Test
    fun `a trusted reference for another symbol is SYMBOL_MISMATCH and never prices this intent`() {
        // A trusted, fresh QQQ quote must not authorize an SPY order.
        val qqqReference = ExecutionReferencePriceEvaluator().evaluate(
            symbol = "QQQ",
            observation = LiveQuoteObservation(
                bid = 400.0,
                ask = 400.0,
                provenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
                eventTimeEpochMillis = nowMillis - 500L,
                receivedAtEpochMillis = nowMillis - 500L,
            ),
            nowEpochMillis = nowMillis,
        )
        assertTrue(qqqReference is ExecutionReferencePrice.Trusted)
        val result = preflight(intent = newIntent(symbol = "SPY", qty = 2.0), reference = qqqReference)
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(
            result.blockReasons.contains(
                PreflightBlockReason.NoTrustedExecutionPrice(ExecutionPriceRejection.SYMBOL_MISMATCH),
            ),
        )
        assertNull(result.estimatedNotionalUsd)
        assertEquals("NONE", result.priceSource)
    }

    @Test
    fun `LIMIT order keeps its operator limit for notional but still requires a trusted reference`() {
        val limitIntent = newIntent().copy(type = OrderType.LIMIT, limitPriceUsd = 500.0)
        val withTrusted = preflight(intent = limitIntent, reference = trusted(price = 600.0))
        assertEquals(500.0, withTrusted.estimatedNotionalUsd)
        assertFalse(withTrusted.blockReasons.any { it is PreflightBlockReason.NoTrustedExecutionPrice })

        val withoutTrusted = preflight(
            intent = limitIntent,
            reference = ExecutionReferencePrice.Rejected("SPY", ExecutionPriceRejection.NO_LIVE_QUOTE),
        )
        assertEquals(PreflightStatus.BLOCKED, withoutTrusted.status)
        assertNull(withoutTrusted.estimatedNotionalUsd)
    }
}
