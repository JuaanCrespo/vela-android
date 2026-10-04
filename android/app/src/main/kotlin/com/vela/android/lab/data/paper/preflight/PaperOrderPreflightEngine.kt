package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.data.market.price.ExecutionPriceRejection
import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.paper.HIGH_ALLOCATION_PERCENT_THRESHOLD
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.data.paper.PaperPositionSnapshot
import com.vela.android.lab.data.watchlist.WatchlistConfig
import com.vela.android.lab.state.AppState
import kotlin.math.abs

/**
 * Phase 2.m local-only preflight engine. **Pure function.**
 *
 * - Never opens a network connection.
 * - Never mutates the database.
 * - Never returns a "submission" — only a hypothetical evaluation.
 * - Never references `AlpacaHttpClient`; needs no `HttpClient`,
 *   `OkHttp`, or `Request` in its imports.
 *
 * Phase 2.m policy choices (encoded here, asserted by tests):
 *
 *  - `REAL` mode is **always** a hard block, regardless of intent.
 *  - Missing credentials is a **block** (the engine has no way to
 *    estimate buying power without a recent account snapshot, and
 *    the user must explicitly fix this before dry-running again).
 *  - `account_blocked` / `trading_blocked` from the live Paper
 *    account snapshot are **blocks**.
 *  - Market closed is a **warning**, not a block — `DAY` orders
 *    queued outside market hours still preflight cleanly; the
 *    operator just needs to know.
 *  - Selling more than the held quantity is a **block** (the lab
 *    has no short-selling design yet).
 *  - High allocation post-fill is a **warning** (> 25%).
 *  - Symbol not on the watchlist is a **warning**.
 *  - No local signal for the symbol is a **warning**.
 *
 * Phase 3.a.1-C price policy:
 *
 *  - The only price authority is an [ExecutionReferencePrice.Trusted] live quote. A missing,
 *    rejected, stale, or non-IEX reference is a **block** (`NoTrustedExecutionPrice`), for every
 *    order type. Notional and buying power are never estimated from an untrusted price.
 *  - Persisted Room closes are not an input. The former latest-close fallback was removed.
 *  - A LIMIT order keeps its operator-entered limit price for notional. It still requires a
 *    trusted reference, because the final submit gate always requires one.
 */
class PaperOrderPreflightEngine(
    private val highAllocationPercent: Double = HIGH_ALLOCATION_PERCENT_THRESHOLD,
) {

    fun preflight(
        intent: PaperOrderIntent,
        account: PaperAccountSnapshot?,
        clockSnap: PaperClockSnapshot?,
        positions: List<PaperPositionSnapshot>,
        latestSignalState: String?,
        watchlist: Set<String>,
        appState: AppState,
        credentialsConfigured: Boolean,
        executionReference: ExecutionReferencePrice? = null,
    ): PaperOrderPreflightResult {
        val blocks = mutableListOf<PreflightBlockReason>()
        val warnings = mutableListOf<PreflightWarning>()

        // --- Hard blocks ----------------------------------------------------
        if (!appState.realModeLocked) {
            // The lab promises REAL stays locked. If the lab ever
            // tried to flip it, surface that as a hard block.
            blocks += PreflightBlockReason.RealLocked
        }
        // Sanity: REAL operation mode is itself a block.
        if (appState.mode.name == "REAL") {
            blocks += PreflightBlockReason.RealLocked
        }
        if (!credentialsConfigured) {
            blocks += PreflightBlockReason.NoCredentials
        }

        val normalizedSymbol = WatchlistConfig.normalize(intent.symbol)
        if (normalizedSymbol == null) {
            blocks += PreflightBlockReason.InvalidSymbol(intent.symbol)
        }
        if (intent.quantity <= 0.0 || !intent.quantity.isFinite()) {
            blocks += PreflightBlockReason.InvalidQuantity(intent.quantity)
        }

        if (account != null) {
            if (account.accountBlocked) blocks += PreflightBlockReason.AccountBlocked
            if (account.tradingBlocked) blocks += PreflightBlockReason.TradingBlocked
        }

        // --- Execution reference + notional --------------------------------
        // 3.a.1-C: a missing reference is the same as a rejected one. No fallback exists.
        // A trusted reference authorizes only the symbol it was derived for (SYMBOL_MISMATCH otherwise).
        val intentSymbol = intent.symbol.trim().uppercase()
        val reference: ExecutionReferencePrice = executionReference
            ?: ExecutionReferencePrice.Rejected(intentSymbol, ExecutionPriceRejection.NOT_PROVIDED)
        val trusted = (reference as? ExecutionReferencePrice.Trusted)?.takeIf { it.symbol == intentSymbol }
        val untrustedReason: ExecutionPriceRejection? = when {
            trusted != null -> null
            reference is ExecutionReferencePrice.Rejected -> reference.reason
            else -> ExecutionPriceRejection.SYMBOL_MISMATCH
        }
        if (untrustedReason != null) {
            blocks += PreflightBlockReason.NoTrustedExecutionPrice(untrustedReason)
        }
        val priceUsed: Double? = when {
            trusted == null -> null
            intent.type == OrderType.LIMIT && intent.limitPriceUsd != null -> intent.limitPriceUsd
            else -> trusted.price
        }
        val notional = priceUsed?.let { it * intent.quantity }

        // --- Side-specific blocks ------------------------------------------
        val heldQty = normalizedSymbol?.let { sym ->
            positions.firstOrNull { it.symbol == sym }?.qty ?: 0.0
        } ?: 0.0
        when (intent.side) {
            OrderSide.BUY -> {
                if (notional != null && account != null) {
                    if (notional > account.buyingPowerUsd) {
                        blocks += PreflightBlockReason.InsufficientBuyingPower(
                            needed = notional,
                            available = account.buyingPowerUsd,
                        )
                    }
                }
            }
            OrderSide.SELL -> {
                if (intent.quantity > heldQty) {
                    blocks += PreflightBlockReason.SellExceedsPosition(
                        heldQty = heldQty,
                        requestedQty = intent.quantity,
                    )
                }
            }
        }

        // --- Warnings ------------------------------------------------------
        if (clockSnap?.isOpen == false) {
            warnings += PreflightWarning.MarketClosed
        }
        if (normalizedSymbol != null && normalizedSymbol !in watchlist) {
            warnings += PreflightWarning.SymbolNotInWatchlist(normalizedSymbol)
        }
        if (normalizedSymbol != null && latestSignalState == null) {
            warnings += PreflightWarning.NoLocalSignal(normalizedSymbol)
        }

        // --- Hypothetical impact ------------------------------------------
        val positionImpactQty = when (intent.side) {
            OrderSide.BUY -> intent.quantity
            OrderSide.SELL -> -intent.quantity
        }
        val hypotheticalMarketValueDelta = when {
            priceUsed != null -> positionImpactQty * priceUsed
            else -> 0.0
        }
        val portfolioValue = account?.portfolioValueUsd ?: 0.0
        val newAbsExposureForSymbol = abs(
            (positions.firstOrNull { it.symbol == normalizedSymbol }?.marketValueUsd ?: 0.0) +
                hypotheticalMarketValueDelta,
        )
        val allocationAfter = if (portfolioValue > 0.0) {
            newAbsExposureForSymbol / portfolioValue * 100.0
        } else {
            null
        }
        if (allocationAfter != null && allocationAfter > highAllocationPercent) {
            warnings += PreflightWarning.HighAllocationAfter(allocationAfter)
        }
        val buyingPowerAfter: Double? = when {
            notional == null || account == null -> null
            intent.side == OrderSide.BUY -> account.buyingPowerUsd - notional
            else -> account.buyingPowerUsd + notional  // SELL frees buying power
        }
        if (priceUsed == null) {
            // The notional/buying-power numbers are unknown but the
            // block already captured this; surface a paired warning.
            warnings += PreflightWarning.NoLatestPriceWarning
        }

        val status = when {
            blocks.isNotEmpty() -> PreflightStatus.BLOCKED
            warnings.isNotEmpty() -> PreflightStatus.WARNING_ONLY
            else -> PreflightStatus.ALLOWED_DRY_RUN
        }

        return PaperOrderPreflightResult(
            intent = intent,
            status = status,
            estimatedNotionalUsd = notional,
            estimatedBuyingPowerAfterUsd = buyingPowerAfter,
            allocationPercentAfter = allocationAfter,
            positionImpactQty = positionImpactQty,
            relatedSignalState = latestSignalState,
            marketOpen = clockSnap?.isOpen,
            blockReasons = blocks,
            warnings = warnings,
            priceSource = trusted?.source?.name ?: MarketPriceSource.NONE.name,
            priceFreshness = if (trusted != null) {
                PriceFreshness.FRESH.name
            } else {
                PriceFreshness.MISSING.name
            },
            priceAgeMillis = trusted?.ageMillis,
        )
    }
}
