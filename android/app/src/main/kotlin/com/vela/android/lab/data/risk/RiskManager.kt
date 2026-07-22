package com.vela.android.lab.data.risk

import java.util.Locale

/**
 * Port of `app/data/risk_manager.py::RiskManager`.
 *
 * Pure logic, no I/O. Mirrors the Python implementation rule-by-rule so
 * existing test fixtures (`tests/test_risk_manager.py`) translate
 * one-to-one. Number formatting uses `Locale.ROOT` to keep decimal
 * separators stable across devices.
 */
class RiskManager(val limits: RiskLimits = RiskLimits()) {

    fun evaluateEntry(
        symbol: String,
        requestedSize: Double,
        snapshot: RiskStateSnapshot? = null,
    ): RiskDecision {
        val normalizedSymbol = normalizeSymbol(symbol)
        val state = snapshot ?: RiskStateSnapshot()

        val inputError = inputError(
            action = RiskAction.ENTRY,
            symbol = normalizedSymbol,
            requestedSize = requestedSize,
        )
        if (inputError != null) return inputError

        if (normalizedSymbol in limits.blockedSymbols) {
            return RiskDecision(
                allowed = false,
                action = RiskAction.ENTRY,
                symbol = normalizedSymbol,
                requestedSize = requestedSize,
                reason = "Symbol '$normalizedSymbol' is blocked by risk policy.",
                rule = "symbol_block",
            )
        }

        if (requestedSize > limits.maxPositionSize) {
            return RiskDecision(
                allowed = false,
                action = RiskAction.ENTRY,
                symbol = normalizedSymbol,
                requestedSize = requestedSize,
                reason = "Requested position size ${formatTwo(requestedSize)} exceeds " +
                    "max position size ${formatTwo(limits.maxPositionSize)}.",
                rule = "max_position_size",
            )
        }

        if (state.realizedPnlTotal <= -limits.maxDailyLoss) {
            return RiskDecision(
                allowed = false,
                action = RiskAction.ENTRY,
                symbol = normalizedSymbol,
                requestedSize = requestedSize,
                reason = "Daily loss limit reached: realized PnL " +
                    "${formatTwo(state.realizedPnlTotal)} <= -${formatTwo(limits.maxDailyLoss)}.",
                rule = "max_daily_loss",
            )
        }

        if (
            !state.hasOpenSymbol(normalizedSymbol) &&
            state.openPositionCount >= limits.maxOpenPositions
        ) {
            return RiskDecision(
                allowed = false,
                action = RiskAction.ENTRY,
                symbol = normalizedSymbol,
                requestedSize = requestedSize,
                reason = "Max open positions limit reached " +
                    "(${limits.maxOpenPositions}).",
                rule = "max_open_positions",
            )
        }

        return RiskDecision(
            allowed = true,
            action = RiskAction.ENTRY,
            symbol = normalizedSymbol,
            requestedSize = requestedSize,
            reason = "Entry allowed by risk policy.",
        )
    }

    fun evaluateExit(
        symbol: String,
        requestedSize: Double,
        snapshot: RiskStateSnapshot? = null,
    ): RiskDecision {
        val normalizedSymbol = normalizeSymbol(symbol)
        val inputError = inputError(
            action = RiskAction.EXIT,
            symbol = normalizedSymbol,
            requestedSize = requestedSize,
        )
        if (inputError != null) return inputError

        return RiskDecision(
            allowed = true,
            action = RiskAction.EXIT,
            symbol = normalizedSymbol,
            requestedSize = requestedSize,
            reason = "Exit allowed by risk policy.",
        )
    }

    private fun normalizeSymbol(symbol: String): String = symbol.trim().uppercase()

    private fun inputError(
        action: RiskAction,
        symbol: String,
        requestedSize: Double,
    ): RiskDecision? {
        if (symbol.isEmpty()) {
            return RiskDecision(
                allowed = false,
                action = action,
                symbol = "",
                requestedSize = requestedSize,
                reason = "Risk validation requires a symbol.",
                rule = "invalid_symbol",
            )
        }
        if (requestedSize <= 0.0) {
            return RiskDecision(
                allowed = false,
                action = action,
                symbol = symbol,
                requestedSize = requestedSize,
                reason = "Risk validation requires a positive position size.",
                rule = "invalid_size",
            )
        }
        return null
    }

    private fun formatTwo(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value)
}
