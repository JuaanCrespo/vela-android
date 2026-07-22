package com.vela.android.lab.data.paper

/**
 * Phase 2.k read-only snapshots returned by [AlpacaPaperReadOnlyClient].
 *
 * - [PaperAccountSnapshot] omits the raw account id so it cannot
 *   leak into a log or UI. The boolean flags `tradingBlocked` /
 *   `accountBlocked` are surfaced because they're operationally
 *   useful — they indicate the *server* believes trading should not
 *   happen; the Android lab takes that as a hard read-only signal
 *   no matter what.
 * - [PaperClockSnapshot] is the regular market clock response.
 * - [PaperPositionSnapshot] is one row of the positions list.
 *
 * No field on this hierarchy carries a credential value. No method
 * on this hierarchy submits an order or mutates account state.
 */
data class PaperAccountSnapshot(
    val cashUsd: Double,
    val buyingPowerUsd: Double,
    val equityUsd: Double,
    val portfolioValueUsd: Double,
    val tradingBlocked: Boolean,
    val accountBlocked: Boolean,
    val patternDayTrader: Boolean,
    val currency: String,
    val status: String,
)

data class PaperClockSnapshot(
    val isOpen: Boolean,
    val nextOpenIso: String?,
    val nextCloseIso: String?,
    val timestampIso: String?,
)

data class PaperPositionSnapshot(
    val symbol: String,
    val qty: Double,
    val marketValueUsd: Double,
    val unrealizedPlUsd: Double,
    val side: String,
)
