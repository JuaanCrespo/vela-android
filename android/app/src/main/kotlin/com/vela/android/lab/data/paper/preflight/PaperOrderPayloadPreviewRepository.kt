package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.db.room.dao.PaperOrderPayloadPreviewDao
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity

/**
 * Phase 2.q local Room review-queue repository. No network,
 * credential, account, or execution dependency.
 */
class PaperOrderPayloadPreviewRepository(
    private val dao: PaperOrderPayloadPreviewDao,
) {
    suspend fun savePreview(preview: PaperOrderPayloadPreview): Long =
        dao.insert(preview.toEntity())

    suspend fun countAll(): Int = dao.countAll()

    suspend fun recent(limit: Int): List<PaperOrderPayloadPreviewEntity> =
        if (limit <= 0) emptyList() else dao.recent(limit)

    suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderPayloadPreviewEntity> =
        if (symbol.isBlank() || limit <= 0) emptyList()
        else dao.recentBySymbol(symbol.trim().uppercase(), limit)

    suspend fun byPreviewId(previewId: String): PaperOrderPayloadPreviewEntity? =
        if (previewId.isBlank()) null else dao.byPreviewId(previewId)
}

internal fun PaperOrderPayloadPreview.toEntity(): PaperOrderPayloadPreviewEntity =
    PaperOrderPayloadPreviewEntity(
        previewId = previewId,
        linkedClientDryRunId = linkedClientDryRunId,
        createdAtEpochMillis = generatedAtEpochMillis,
        symbol = symbol,
        side = side.name,
        orderType = type.name,
        timeInForce = timeInForce.name,
        quantity = quantity,
        limitPriceUsd = limitPriceUsd,
        status = status.name,
        estimatedNotionalUsd = estimatedNotionalUsd,
        priceSource = priceSource,
        priceFreshness = priceFreshness,
        executionEnabled = executionEnabled,
        endpointPreview = endpointPreview,
        httpMethodPreview = httpMethodPreview,
        warningsSummary = warningMessages.joinToString("\n"),
    )
