package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.db.room.dao.PaperOrderSubmitAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity

/** Sanitized append-only persistence boundary used before and after one network attempt. */
class PaperOrderSubmitAuditRepository(
    private val dao: PaperOrderSubmitAuditDao,
) {
    suspend fun recordAttemptStarted(
        request: PaperOrderSubmitRequest,
        preview: PaperOrderPayloadPreview,
        marketOpen: Boolean,
        timestampEpochMillis: Long,
    ): Long = dao.insert(
        event(
            request = request,
            preview = preview,
            marketOpen = marketOpen,
            status = ATTEMPT_STARTED,
            alpacaOrderId = null,
            timestampEpochMillis = timestampEpochMillis,
            safeErrorMessage = null,
        ),
    )

    suspend fun recordResult(
        request: PaperOrderSubmitRequest,
        preview: PaperOrderPayloadPreview,
        marketOpen: Boolean,
        result: PaperOrderSubmitResult,
    ): Long = dao.insert(
        event(
            request = request,
            preview = preview,
            marketOpen = marketOpen,
            status = result.status.name,
            alpacaOrderId = result.alpacaOrderId,
            timestampEpochMillis = result.submittedAtEpochMillis,
            safeErrorMessage = result.safeErrorMessage,
        ),
    )

    suspend fun hasAttemptForPreview(previewId: String): Boolean =
        dao.countByPreviewId(previewId) > 0

    suspend fun hasClientOrderId(clientOrderId: String): Boolean =
        dao.countByClientOrderId(clientOrderId) > 0

    suspend fun countAll(): Int = dao.countAll()

    suspend fun recent(limit: Int): List<PaperOrderSubmitAuditEntity> =
        if (limit <= 0) emptyList() else dao.recent(limit)

    suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderSubmitAuditEntity> =
        if (symbol.isBlank() || limit <= 0) emptyList()
        else dao.recentBySymbol(symbol.trim().uppercase(), limit)

    suspend fun byAttemptId(attemptId: String): List<PaperOrderSubmitAuditEntity> =
        if (attemptId.isBlank()) emptyList() else dao.byAttemptId(attemptId)

    private fun event(
        request: PaperOrderSubmitRequest,
        preview: PaperOrderPayloadPreview,
        marketOpen: Boolean,
        status: String,
        alpacaOrderId: String?,
        timestampEpochMillis: Long,
        safeErrorMessage: String?,
    ): PaperOrderSubmitAuditEntity = PaperOrderSubmitAuditEntity(
        eventKey = "${request.submitAttemptId}:$status",
        submitAttemptId = request.submitAttemptId,
        previewId = request.previewId,
        linkedClientDryRunId = request.linkedClientDryRunId,
        clientOrderId = request.clientOrderId,
        symbol = request.symbol,
        side = request.side.name,
        quantity = request.quantity,
        orderType = request.type.name,
        timeInForce = request.timeInForce.name,
        limitPriceUsd = request.limitPrice,
        status = status,
        alpacaOrderId = alpacaOrderId,
        submittedAtEpochMillis = timestampEpochMillis,
        safeErrorMessage = safeErrorMessage,
        priceSource = preview.priceSource.orEmpty(),
        priceFreshness = preview.priceFreshness.orEmpty(),
        marketOpen = marketOpen,
        confirmationTokenId = request.confirmationTokenId,
    )

    companion object {
        const val ATTEMPT_STARTED: String = "ATTEMPT_STARTED"
    }
}
