package com.vela.android.lab.data.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of `tests/test_risk_manager.py`.
 */
class RiskManagerTest {

    @Test
    fun `entry is rejected for blocked symbol`() {
        val manager = RiskManager(
            RiskLimits(
                maxPositionSize = 10.0,
                maxOpenPositions = 5,
                maxDailyLoss = 100.0,
                blockedSymbols = setOf("spy"),
            )
        )

        val decision = manager.evaluateEntry(
            symbol = "SPY",
            requestedSize = 1.0,
            snapshot = RiskStateSnapshot(),
        )

        assertFalse(decision.allowed)
        assertEquals("symbol_block", decision.rule)
        assertTrue(decision.reason.contains("blocked by risk policy"))
    }

    @Test
    fun `entry is rejected when position size exceeds limit`() {
        val manager = RiskManager(
            RiskLimits(
                maxPositionSize = 1.0,
                maxOpenPositions = 5,
                maxDailyLoss = 100.0,
            )
        )

        val decision = manager.evaluateEntry(
            symbol = "QQQ",
            requestedSize = 2.0,
            snapshot = RiskStateSnapshot(),
        )

        assertFalse(decision.allowed)
        assertEquals("max_position_size", decision.rule)
        assertTrue(decision.reason.contains("exceeds max position size"))
    }

    @Test
    fun `entry is rejected when max open positions limit is hit`() {
        val manager = RiskManager(
            RiskLimits(
                maxPositionSize = 10.0,
                maxOpenPositions = 1,
                maxDailyLoss = 100.0,
            )
        )

        val decision = manager.evaluateEntry(
            symbol = "QQQ",
            requestedSize = 1.0,
            snapshot = RiskStateSnapshot(openSymbols = listOf("SPY")),
        )

        assertFalse(decision.allowed)
        assertEquals("max_open_positions", decision.rule)
        assertTrue(decision.reason.contains("Max open positions limit reached"))
    }

    @Test
    fun `exit is allowed even after daily loss limit is hit`() {
        val manager = RiskManager(
            RiskLimits(
                maxPositionSize = 10.0,
                maxOpenPositions = 5,
                maxDailyLoss = 1.0,
            )
        )

        val decision = manager.evaluateExit(
            symbol = "SPY",
            requestedSize = 1.0,
            snapshot = RiskStateSnapshot(
                openSymbols = listOf("SPY"),
                realizedPnlTotal = -5.0,
            ),
        )

        assertTrue(decision.allowed)
        assertNull(decision.rule)
        assertEquals("Exit allowed by risk policy.", decision.reason)
    }

    @Test
    fun `entry is rejected when input symbol is blank`() {
        val manager = RiskManager()

        val decision = manager.evaluateEntry(
            symbol = "   ",
            requestedSize = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("invalid_symbol", decision.rule)
    }

    @Test
    fun `entry is rejected when requested size is not positive`() {
        val manager = RiskManager()

        val decision = manager.evaluateEntry(
            symbol = "SPY",
            requestedSize = 0.0,
        )

        assertFalse(decision.allowed)
        assertEquals("invalid_size", decision.rule)
    }

    @Test
    fun `entry is rejected when realized PnL crosses daily loss threshold`() {
        val manager = RiskManager(
            RiskLimits(
                maxPositionSize = 10.0,
                maxOpenPositions = 5,
                maxDailyLoss = 100.0,
            )
        )

        val decision = manager.evaluateEntry(
            symbol = "SPY",
            requestedSize = 1.0,
            snapshot = RiskStateSnapshot(realizedPnlTotal = -100.0),
        )

        assertFalse(decision.allowed)
        assertEquals("max_daily_loss", decision.rule)
    }
}
