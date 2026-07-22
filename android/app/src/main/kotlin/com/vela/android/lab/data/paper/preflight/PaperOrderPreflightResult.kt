package com.vela.android.lab.data.paper.preflight

/**
 * Outcome of [PaperOrderPreflightEngine.preflight]. Pure data; no
 * Android imports. **The engine never sends a request.** Every
 * field on this class is computed locally from already-fetched
 * snapshots plus the [PaperOrderIntent].
 *
 * `status` semantics:
 *  - [PreflightStatus.ALLOWED_DRY_RUN] — the hypothetical trade
 *    would have passed every guard. Nothing was submitted.
 *  - [PreflightStatus.WARNING_ONLY] — same as ALLOWED_DRY_RUN but
 *    one or more informational [PreflightWarning]s were raised.
 *  - [PreflightStatus.BLOCKED] — one or more [PreflightBlockReason]s
 *    were raised. Even if the engine could submit (which it cannot),
 *    the trade would not have been allowed.
 *
 * **Phase 2.m contract**: this result type does not contain — and
 * the engine does not return — any field that names a real order
 * id, exchange route, fill price, or any other server-side artefact
 * of a submitted trade. Everything is local-only and hypothetical.
 */
data class PaperOrderPreflightResult(
    val intent: PaperOrderIntent,
    val status: PreflightStatus,
    /** `quantity * (limit price OR latest local close)`, or null if unknown. */
    val estimatedNotionalUsd: Double?,
    /** Hypothetical buying power remaining after the intent. */
    val estimatedBuyingPowerAfterUsd: Double?,
    /** Hypothetical `|market value after fill| / portfolio value * 100`. */
    val allocationPercentAfter: Double?,
    /** Signed delta this intent would apply to the symbol's position. */
    val positionImpactQty: Double,
    val relatedSignalState: String?,
    val marketOpen: Boolean?,
    val blockReasons: List<PreflightBlockReason>,
    val warnings: List<PreflightWarning>,
    /**
     * Phase 2.o: provenance and freshness of the price the engine
     * used (or `null` for older callers that don't pass a snapshot).
     */
    val priceSource: String? = null,
    val priceFreshness: String? = null,
    val priceAgeMillis: Long? = null,
)

enum class PreflightStatus { ALLOWED_DRY_RUN, WARNING_ONLY, BLOCKED }

/**
 * A block-reason represents a condition under which the hypothetical
 * trade would not have been allowed. These are encoded as a sealed
 * hierarchy so the UI can render them faithfully.
 */
sealed interface PreflightBlockReason {
    val message: String
    object RealLocked : PreflightBlockReason {
        override val message: String = "REAL mode is locked; only dry-run is permitted."
    }
    object NoCredentials : PreflightBlockReason {
        override val message: String = "No Alpaca credentials configured."
    }
    object AccountBlocked : PreflightBlockReason {
        override val message: String = "Alpaca account is BLOCKED."
    }
    object TradingBlocked : PreflightBlockReason {
        override val message: String = "Alpaca trading is BLOCKED."
    }
    object MissingLatestPrice : PreflightBlockReason {
        override val message: String =
            "No latest local market data for the symbol; cannot estimate notional."
    }
    data class InvalidQuantity(val quantity: Double) : PreflightBlockReason {
        override val message: String = "Quantity must be > 0 (got $quantity)."
    }
    data class InvalidSymbol(val raw: String) : PreflightBlockReason {
        override val message: String = "'$raw' is not a valid US stock symbol."
    }
    data class InsufficientBuyingPower(
        val needed: Double,
        val available: Double,
    ) : PreflightBlockReason {
        override val message: String =
            "Notional $needed exceeds available buying power $available."
    }
    data class SellExceedsPosition(
        val heldQty: Double,
        val requestedQty: Double,
    ) : PreflightBlockReason {
        override val message: String =
            "Cannot SELL $requestedQty when only $heldQty held (no short positions in dry-run)."
    }
}

sealed interface PreflightWarning {
    val message: String
    object MarketClosed : PreflightWarning {
        override val message: String = "US market is closed at preflight time."
    }
    data class SymbolNotInWatchlist(val symbol: String) : PreflightWarning {
        override val message: String = "$symbol is not on the current watchlist."
    }
    data class HighAllocationAfter(val percent: Double) : PreflightWarning {
        override val message: String =
            "Hypothetical allocation after fill would be %.1f%% (above 25%% threshold)."
                .format(percent)
    }
    data class NoLocalSignal(val symbol: String) : PreflightWarning {
        override val message: String = "No local signal for $symbol."
    }
    object NoLatestPriceWarning : PreflightWarning {
        override val message: String =
            "Notional could not be estimated because the latest price is unknown."
    }

    /**
     * Phase 2.o — the freshest local price available was older than
     * the freshness threshold. The dry-run still runs, but the
     * operator should know the notional is based on a stale point.
     */
    data class StalePrice(
        val source: String,
        val ageMillis: Long,
    ) : PreflightWarning {
        override val message: String =
            "Stale price from $source (age ${ageMillis}ms exceeds the freshness threshold)."
    }
}
