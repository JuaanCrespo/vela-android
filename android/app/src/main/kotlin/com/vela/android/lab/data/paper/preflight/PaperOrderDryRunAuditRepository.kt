package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity

/**
 * Phase 2.n local-only audit-trail repository for dry-run preflight
 * evaluations. **No network dependency.** The class only inserts
 * and reads from the local Room table.
 *
 * **No credential, API key, or Alpaca account id ever passes
 * through this class.** The mapping helper [toEntity] copies only
 * intent fields + engine-output fields + short summary strings.
 */
class PaperOrderDryRunAuditRepository(
    private val dao: PaperOrderDryRunAuditDao,
) {

    /**
     * Persist [result] as one audit row. Returns the inserted row's
     * primary key on success.
     *
     * Throws whatever the DAO throws (e.g. constraint failure on a
     * duplicate `clientDryRunId`). Callers are expected to wrap the
     * call in a `try/catch` and surface failure as a UI-state error.
     */
    suspend fun saveDryRun(result: PaperOrderPreflightResult): Long =
        dao.insert(result.toEntity())

    suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity> =
        if (limit <= 0) emptyList() else dao.recent(limit)

    suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderDryRunAuditEntity> =
        if (limit <= 0 || symbol.isBlank()) emptyList()
        else dao.recentBySymbol(symbol.uppercase(), limit)

    suspend fun countAll(): Int = dao.countAll()
}

/**
 * Pure mapping from a preflight result + its originating intent to
 * the audit entity. **Stores no credentials, no API keys, no
 * account id.** Block reasons and warnings are flattened to short
 * messages so the row is human-readable without re-running the
 * engine.
 */
internal fun PaperOrderPreflightResult.toEntity(): PaperOrderDryRunAuditEntity =
    PaperOrderDryRunAuditEntity(
        clientDryRunId = intent.clientDryRunId,
        createdAtEpochMillis = intent.createdAtEpochMillis,
        symbol = intent.symbol,
        side = intent.side.name,
        orderType = intent.type.name,
        timeInForce = intent.tif.name,
        quantity = intent.quantity,
        limitPriceUsd = intent.limitPriceUsd,
        status = status.name,
        estimatedNotionalUsd = estimatedNotionalUsd,
        buyingPowerAfterUsd = estimatedBuyingPowerAfterUsd,
        allocationPercentAfter = allocationPercentAfter,
        latestPriceUsedUsd = priceUsedFor(this),
        latestSignalState = relatedSignalState,
        marketOpen = marketOpen,
        blockReasonsSummary = blockReasons.joinToString("\n") { it.message },
        warningsSummary = warnings.joinToString("\n") { it.message },
        source = intent.source.name,
        priceSource = priceSource,
        priceFreshness = priceFreshness,
        priceAgeMillis = priceAgeMillis,
    )

/**
 * Derive the price the engine used: limit price (for LIMIT orders)
 * or the locally persisted close (inferred from `estimatedNotional /
 * quantity` when present). Returns `null` if neither is available.
 */
private fun priceUsedFor(result: PaperOrderPreflightResult): Double? {
    val notional = result.estimatedNotionalUsd
    val qty = result.intent.quantity
    if (notional != null && qty > 0.0) return notional / qty
    return result.intent.limitPriceUsd
}
