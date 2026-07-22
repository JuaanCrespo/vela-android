package com.vela.android.lab.data.paper.preflight

import java.time.Instant
import java.util.UUID

/**
 * Pure local Phase 2.q preview builder. It creates no HTTP request,
 * has no network dependency, performs no persistence, and cannot
 * submit/cancel/replace/execute an order.
 */
class PaperOrderPayloadPreviewBuilder(
    private val previewIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = { Instant.now() },
) {

    fun build(draft: PaperOrderRequestDraft): PaperOrderPayloadPreviewValidation {
        if (draft.executionEnabled) {
            return rejected(
                PaperOrderPayloadPreviewRejection.EXECUTION_NOT_DISABLED,
                "Payload preview requires executionEnabled=false.",
            )
        }
        if (PaperTradingExecutionGuard.canExecuteOrders) {
            return rejected(
                PaperOrderPayloadPreviewRejection.EXECUTION_GUARD_ENABLED,
                "Payload preview rejected because the execution guard changed state.",
            )
        }
        if (draft.status !in READY_DRAFT_STATUSES) {
            return rejected(
                PaperOrderPayloadPreviewRejection.DRAFT_NOT_READY,
                "Payload preview requires a local-ready draft.",
            )
        }

        val invalid = when {
            draft.clientDryRunId.isBlank() -> "Linked client dry-run id is required."
            draft.symbol.isBlank() -> "Symbol is required."
            draft.quantity <= 0.0 || !draft.quantity.isFinite() ->
                "Quantity must be positive and finite."
            draft.type == OrderType.LIMIT &&
                (draft.limitPriceUsd == null || draft.limitPriceUsd <= 0.0 ||
                    !draft.limitPriceUsd.isFinite()) ->
                "LIMIT payload preview requires a positive finite limit price."
            draft.estimatedNotionalUsd != null &&
                (draft.estimatedNotionalUsd < 0.0 || !draft.estimatedNotionalUsd.isFinite()) ->
                "Estimated notional must be non-negative and finite."
            else -> null
        }
        if (invalid != null) {
            return rejected(PaperOrderPayloadPreviewRejection.INVALID_DRAFT, invalid)
        }

        val previewId = previewIdFactory().trim()
        if (previewId.isEmpty()) {
            return rejected(
                PaperOrderPayloadPreviewRejection.INVALID_DRAFT,
                "Preview id factory returned an empty id.",
            )
        }

        val status = if (draft.warningMessages.isEmpty()) {
            PaperOrderPayloadPreviewStatus.READY_PREVIEW
        } else {
            PaperOrderPayloadPreviewStatus.READY_PREVIEW_WITH_WARNINGS
        }
        return PaperOrderPayloadPreviewValidation.Valid(
            PaperOrderPayloadPreview(
                previewId = previewId,
                linkedClientDryRunId = draft.clientDryRunId,
                symbol = draft.symbol,
                side = draft.side,
                type = draft.type,
                timeInForce = draft.timeInForce,
                quantity = draft.quantity,
                limitPriceUsd = draft.limitPriceUsd,
                estimatedNotionalUsd = draft.estimatedNotionalUsd,
                priceSource = draft.priceSource,
                priceFreshness = draft.priceFreshness,
                relatedSignalState = draft.relatedSignalState,
                generatedAtEpochMillis = clock().toEpochMilli(),
                status = status,
                warningMessages = draft.warningMessages.toList(),
                payloadFields = PaperOrderPayloadFields(
                    symbol = draft.symbol,
                    side = draft.side.name.lowercase(),
                    type = draft.type.name.lowercase(),
                    timeInForce = draft.timeInForce.name.lowercase(),
                    quantity = draft.quantity,
                    limitPriceUsd = draft.limitPriceUsd,
                ),
                executionEnabled = false,
                endpointPreview = PaperOrderPayloadPreview.ENDPOINT_DISABLED,
                httpMethodPreview = PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED,
            ),
        )
    }

    private fun rejected(
        reason: PaperOrderPayloadPreviewRejection,
        message: String,
    ): PaperOrderPayloadPreviewValidation.Rejected =
        PaperOrderPayloadPreviewValidation.Rejected(reason = reason, message = message)

    private companion object {
        val READY_DRAFT_STATUSES: Set<PaperOrderRequestDraftStatus> = setOf(
            PaperOrderRequestDraftStatus.READY_LOCAL,
            PaperOrderRequestDraftStatus.READY_LOCAL_WITH_WARNINGS,
        )
    }
}
