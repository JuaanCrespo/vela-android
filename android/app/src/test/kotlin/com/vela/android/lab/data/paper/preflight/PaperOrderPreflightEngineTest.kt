package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.data.paper.PaperPositionSnapshot
import com.vela.android.lab.state.AppState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PaperOrderPreflightEngineTest {

    private val engine = PaperOrderPreflightEngine()

    private fun account(
        equity: Double = 100_000.0,
        cash: Double = 50_000.0,
        buyingPower: Double = 200_000.0,
        portfolioValue: Double = 100_000.0,
        tradingBlocked: Boolean = false,
        accountBlocked: Boolean = false,
    ): PaperAccountSnapshot = PaperAccountSnapshot(
        cashUsd = cash,
        buyingPowerUsd = buyingPower,
        equityUsd = equity,
        portfolioValueUsd = portfolioValue,
        tradingBlocked = tradingBlocked,
        accountBlocked = accountBlocked,
        patternDayTrader = false,
        currency = "USD",
        status = "ACTIVE",
    )

    private fun clock(open: Boolean = true): PaperClockSnapshot = PaperClockSnapshot(
        isOpen = open,
        nextOpenIso = null,
        nextCloseIso = null,
        timestampIso = null,
    )

    private fun intent(
        symbol: String = "SPY",
        side: OrderSide = OrderSide.BUY,
        qty: Double = 1.0,
    ): PaperOrderIntent = PaperOrderIntent(
        symbol = symbol,
        side = side,
        quantity = qty,
        type = OrderType.MARKET,
        tif = TimeInForce.DAY,
        source = IntentSource.MANUAL_DRY_RUN,
        createdAtEpochMillis = 1_000L,
        clientDryRunId = "dry-run-1",
    )

    @Test
    fun `valid BUY dry-run produces ALLOWED_DRY_RUN with no blocks`() {
        val result = engine.preflight(
            intent = intent(qty = 2.0),
            account = account(),
            clockSnap = clock(open = true),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(mode = OperationMode.READ_ONLY, realModeLocked = true),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result.status)
        assertEquals(1000.0, result.estimatedNotionalUsd)
        assertEquals(2.0, result.positionImpactQty)
        assertEquals("BULLISH", result.relatedSignalState)
        assertEquals(true, result.marketOpen)
        assertTrue(result.blockReasons.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `accountBlocked produces BLOCKED with AccountBlocked reason`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(accountBlocked = true),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.AccountBlocked })
    }

    @Test
    fun `tradingBlocked produces BLOCKED with TradingBlocked reason`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(tradingBlocked = true),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.TradingBlocked })
    }

    @Test
    fun `insufficient buying power blocks BUY`() {
        val result = engine.preflight(
            intent = intent(qty = 100.0),
            account = account(buyingPower = 1_000.0),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.InsufficientBuyingPower })
    }

    @Test
    fun `market closed is a warning not a block`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(),
            clockSnap = clock(open = false),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.WARNING_ONLY, result.status)
        assertTrue(result.warnings.any { it is PreflightWarning.MarketClosed })
        assertTrue(result.blockReasons.isEmpty())
    }

    @Test
    fun `missing latest price blocks because notional cannot be estimated`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = null,  // <-- missing
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.MissingLatestPrice })
        assertNull(result.estimatedNotionalUsd)
    }

    @Test
    fun `symbol not in watchlist is a warning`() {
        val result = engine.preflight(
            intent = intent(symbol = "TSLA"),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 200.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY", "AAPL"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.WARNING_ONLY, result.status)
        assertTrue(result.warnings.any {
            it is PreflightWarning.SymbolNotInWatchlist && it.symbol == "TSLA"
        })
    }

    @Test
    fun `high allocation after fill produces a warning`() {
        // Buying 200 SPY at $500 = $100,000 of exposure; portfolio
        // value is $100,000 → 100% allocation → > 25% threshold.
        // Buying power $1M ensures the BUY is not also blocked.
        val result = engine.preflight(
            intent = intent(qty = 200.0),
            account = account(equity = 100_000.0, buyingPower = 1_000_000.0, portfolioValue = 100_000.0),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.WARNING_ONLY, result.status)
        assertTrue(result.warnings.any { it is PreflightWarning.HighAllocationAfter })
        assertNotNull(result.allocationPercentAfter)
        assertTrue(result.allocationPercentAfter!! >= 100.0)
    }

    @Test
    fun `SELL more than held quantity is a block`() {
        val held = PaperPositionSnapshot(
            symbol = "SPY", qty = 1.0,
            marketValueUsd = 500.0, unrealizedPlUsd = 0.0, side = "long",
        )
        val result = engine.preflight(
            intent = intent(side = OrderSide.SELL, qty = 5.0),
            account = account(),
            clockSnap = clock(),
            positions = listOf(held),
            latestLocalClose = 500.0,
            latestSignalState = "BEARISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any {
            it is PreflightBlockReason.SellExceedsPosition && it.requestedQty == 5.0
        })
    }

    @Test
    fun `no credentials blocks regardless of side`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = false,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.NoCredentials })
    }

    @Test
    fun `RealLocked false is a block (defense-in-depth)`() {
        val state = AppState(realModeLocked = false)
        val result = engine.preflight(
            intent = intent(),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = state,
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.RealLocked })
    }

    @Test
    fun `invalid symbol input blocks before notional is computed`() {
        val result = engine.preflight(
            intent = intent(symbol = "BTC/USD"),  // crypto-slash, rejected
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = emptySet(),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any {
            it is PreflightBlockReason.InvalidSymbol && it.raw == "BTC/USD"
        })
    }

    @Test
    fun `invalid quantity (zero or negative) is a block`() {
        val zero = engine.preflight(
            intent = intent(qty = 0.0),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        val negative = engine.preflight(
            intent = intent(qty = -5.0),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertTrue(zero.blockReasons.any { it is PreflightBlockReason.InvalidQuantity })
        assertTrue(negative.blockReasons.any { it is PreflightBlockReason.InvalidQuantity })
    }

    @Test
    fun `no local signal raises a warning`() {
        val result = engine.preflight(
            intent = intent(),
            account = account(),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = null,  // <-- no signal
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        assertEquals(PreflightStatus.WARNING_ONLY, result.status)
        assertTrue(result.warnings.any {
            it is PreflightWarning.NoLocalSignal && it.symbol == "SPY"
        })
    }

    @Test
    fun `buying power after estimate accounts for BUY notional`() {
        val result = engine.preflight(
            intent = intent(qty = 10.0),
            account = account(buyingPower = 10_000.0),
            clockSnap = clock(),
            positions = emptyList(),
            latestLocalClose = 500.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
        )
        // Notional = 10 * 500 = 5000 → BP after = 10000 - 5000 = 5000
        assertEquals(5000.0, result.estimatedBuyingPowerAfterUsd)
        assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result.status)
    }

    @TestFactory
    fun `engine declares no execution-shape method`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "executeorder", "cancelorder",
            "replaceorder", "openposition", "closeposition", "trading",
            "post", "put", "patch", "delete",
        )
        val methods = PaperOrderPreflightEngine::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { name ->
            DynamicTest.dynamicTest("engine method '$name' has no forbidden substring") {
                val lower = name.lowercase()
                for (bad in forbidden) {
                    assertFalse(
                        lower.contains(bad),
                        "engine method '$name' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }
}
