package com.vela.android.lab.data.paper

/**
 * Phase 2.l read-only portfolio + risk models. Pure data; no
 * Android imports.
 *
 * **No method on this hierarchy submits orders, mutates account
 * state, or performs any trading action.** Every value is derived
 * from already-fetched [PaperAccountSnapshot] / [PaperClockSnapshot]
 * / [PaperPositionSnapshot] data plus locally persisted bars and
 * signals.
 */
data class PaperPortfolioSnapshot(
    val equityUsd: Double,
    val cashUsd: Double,
    val buyingPowerUsd: Double,
    val portfolioValueUsd: Double,
    /** Sum of absolute market values across all open positions. */
    val grossMarketValueUsd: Double,
    val positionsCount: Int,
    val marketOpen: Boolean?,
    val tradingBlocked: Boolean,
    val accountBlocked: Boolean,
    val patternDayTrader: Boolean,
    val accountStatus: String,
) {
    companion object {
        val Empty: PaperPortfolioSnapshot = PaperPortfolioSnapshot(
            equityUsd = 0.0,
            cashUsd = 0.0,
            buyingPowerUsd = 0.0,
            portfolioValueUsd = 0.0,
            grossMarketValueUsd = 0.0,
            positionsCount = 0,
            marketOpen = null,
            tradingBlocked = false,
            accountBlocked = false,
            patternDayTrader = false,
            accountStatus = "",
        )
    }
}

/**
 * One row of the per-symbol exposure table. Joins:
 *  - the Paper position (qty, market value, unrealized P&L)
 *  - the current watchlist (`inWatchlist`)
 *  - the latest locally persisted signal state (`latestSignalState`)
 *  - the latest locally persisted bar close (`latestLocalClose`)
 *
 * `allocationPercent = (|marketValue| / portfolioValue) * 100`, or
 * `0.0` if portfolio value is non-positive.
 */
data class PerSymbolPaperExposure(
    val symbol: String,
    val qty: Double,
    val marketValueUsd: Double,
    val unrealizedPlUsd: Double,
    val side: String,
    val allocationPercent: Double,
    val inWatchlist: Boolean,
    val latestSignalState: String?,
    val latestLocalClose: Double?,
)

/**
 * Snapshot of risk indicators. Risk flags are **informational only**;
 * the lab does not trade on them. They surface conditions a human
 * operator should be aware of: blocked flags, missing market data,
 * positions outside the watchlist, oversized allocations.
 */
data class PaperRiskSnapshot(
    val flags: List<RiskFlag>,
)

data class RiskFlag(
    val code: Code,
    val severity: Severity,
    val message: String,
    val symbol: String? = null,
) {
    enum class Severity { INFO, WARN }

    enum class Code {
        NO_CREDENTIALS,
        ACCOUNT_BLOCKED,
        TRADING_BLOCKED,
        MARKET_CLOSED,
        PATTERN_DAY_TRADER,
        POSITION_NOT_IN_WATCHLIST,
        NO_LOCAL_MARKET_DATA,
        HIGH_ALLOCATION,
    }
}

/** Threshold used by the "high allocation" risk flag. */
const val HIGH_ALLOCATION_PERCENT_THRESHOLD: Double = 25.0
