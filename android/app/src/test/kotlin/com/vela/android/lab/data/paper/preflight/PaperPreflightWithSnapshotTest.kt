package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.state.AppState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun intent(symbol: String = "SPY", side: OrderSide = OrderSide.BUY, qty: Double = 1.0): PaperOrderIntent =
        PaperOrderIntent(
            symbol = symbol, side = side, quantity = qty,
            type = OrderType.MARKET, tif = TimeInForce.DAY,
            source = IntentSource.MANUAL_DRY_RUN,
            createdAtEpochMillis = 1_000L,
            clientDryRunId = "drylet-$symbol-$qty",
        )

    @Test
    fun `fresh snapshot price feeds notional and result records FRESH source`() {
        val snap = MarketPriceSnapshot(
            symbol = "SPY",
            price = 500.0, bid = 499.95, ask = 500.05,
            marketTimestampMillis = 99_500L, deviceReceivedAtMillis = 99_500L,
            ageMillis = 500L,
            source = MarketPriceSource.LIVE_QUOTE_MID,
            freshness = PriceFreshness.FRESH,
            reason = null,
        )
        val result = engine.preflight(
            intent = intent(qty = 2.0),
            account = account, clockSnap = clock,
            positions = emptyList(),
            latestLocalClose = null,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
            priceSnapshot = snap,
        )
        assertEquals(PreflightStatus.ALLOWED_DRY_RUN, result.status)
        // notional = 500 * 2 = 1000
        assertEquals(1000.0, result.estimatedNotionalUsd)
        assertEquals("LIVE_QUOTE_MID", result.priceSource)
        assertEquals("FRESH", result.priceFreshness)
        assertEquals(500L, result.priceAgeMillis)
    }

    @Test
    fun `missing snapshot blocks with MissingLatestPrice and NONE source`() {
        val snap = MarketPriceSnapshot.missing("SPY", reason = "no data")
        val result = engine.preflight(
            intent = intent(),
            account = account, clockSnap = clock,
            positions = emptyList(),
            latestLocalClose = null,
            latestSignalState = null,
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
            priceSnapshot = snap,
        )
        assertEquals(PreflightStatus.BLOCKED, result.status)
        assertTrue(result.blockReasons.any { it is PreflightBlockReason.MissingLatestPrice })
        assertEquals("NONE", result.priceSource)
        assertEquals("MISSING", result.priceFreshness)
    }

    @Test
    fun `stale snapshot raises StalePrice warning but does NOT block`() {
        val snap = MarketPriceSnapshot(
            symbol = "SPY",
            price = 500.0, bid = null, ask = null,
            marketTimestampMillis = 50_000L, deviceReceivedAtMillis = 50_000L,
            ageMillis = 30_000L,
            source = MarketPriceSource.ROOM_BAR_CLOSE,
            freshness = PriceFreshness.STALE,
            reason = "Room bar stale",
        )
        val result = engine.preflight(
            intent = intent(),
            account = account, clockSnap = clock,
            positions = emptyList(),
            latestLocalClose = null,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
            priceSnapshot = snap,
        )
        // Stale price is a warning, not a block.
        assertEquals(PreflightStatus.WARNING_ONLY, result.status)
        assertTrue(result.warnings.any { it is PreflightWarning.StalePrice })
        assertEquals("ROOM_BAR_CLOSE", result.priceSource)
        assertEquals("STALE", result.priceFreshness)
    }

    @Test
    fun `LIMIT price overrides snapshot and stale-warning is not raised`() {
        val stale = MarketPriceSnapshot(
            symbol = "SPY",
            price = 600.0, bid = null, ask = null,
            marketTimestampMillis = 50_000L, deviceReceivedAtMillis = 50_000L,
            ageMillis = 30_000L,
            source = MarketPriceSource.ROOM_BAR_CLOSE,
            freshness = PriceFreshness.STALE,
            reason = null,
        )
        val limitIntent = intent().copy(
            type = OrderType.LIMIT,
            limitPriceUsd = 500.0,
        )
        val result = engine.preflight(
            intent = limitIntent,
            account = account, clockSnap = clock,
            positions = emptyList(),
            latestLocalClose = null,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
            priceSnapshot = stale,
        )
        // Used 500 (limit), not 600 (snapshot); no stale warning.
        assertEquals(500.0, result.estimatedNotionalUsd)
        assertFalse(result.warnings.any { it is PreflightWarning.StalePrice })
    }

    @Test
    fun `back-compat legacy latestLocalClose still works when snapshot is null`() {
        val result = engine.preflight(
            intent = intent(),
            account = account, clockSnap = clock,
            positions = emptyList(),
            latestLocalClose = 100.0,
            latestSignalState = "BULLISH",
            watchlist = setOf("SPY"),
            appState = AppState(),
            credentialsConfigured = true,
            priceSnapshot = null,
        )
        assertEquals(100.0, result.estimatedNotionalUsd)
        assertNotNull(result.priceSource)
        // Without a snapshot, the result reports ROOM_BAR_CLOSE as the
        // best-effort source label (since price was found, not MISSING).
        assertEquals("ROOM_BAR_CLOSE", result.priceSource)
    }
}
