package com.vela.android.lab.data.paper.preflight

/**
 * Phase 2.p pure local order-request draft builder.
 *
 * The builder has no constructor dependencies, performs no I/O,
 * persists nothing, and exposes no submit/cancel/replace/execute
 * operation. It only copies an approved dry-run result into a
 * non-executable [PaperOrderRequestDraft].
 */
class PaperOrderRequestDraftBuilder {

    fun build(result: PaperOrderPreflightResult): PaperOrderRequestDraftValidation {
        if (PaperTradingExecutionGuard.canExecuteOrders) {
            return rejected(
                PaperOrderRequestDraftRejection.EXECUTION_GUARD_ENABLED,
                "Draft rejected because the execution-disabled guard changed state.",
            )
        }
        if (result.status == PreflightStatus.BLOCKED) {
            val detail = result.blockReasons.joinToString("; ") { it.message }
                .ifBlank { "Preflight status is BLOCKED." }
            return rejected(
                PaperOrderRequestDraftRejection.BLOCKED_PREFLIGHT,
                "Local draft rejected: $detail",
            )
        }
        if (result.status !in APPROVED_PREFLIGHT_STATUSES) {
            return rejected(
                PaperOrderRequestDraftRejection.NO_PREFLIGHT_APPROVAL,
                "Local draft requires ALLOWED_DRY_RUN or WARNING_ONLY preflight status.",
            )
        }
        if (result.intent.source != IntentSource.MANUAL_DRY_RUN) {
            return rejected(
                PaperOrderRequestDraftRejection.NON_MANUAL_SOURCE,
                "Local draft requires MANUAL_DRY_RUN intent source.",
            )
        }
        if (result.blockReasons.isNotEmpty()) {
            return rejected(
                PaperOrderRequestDraftRejection.BLOCKED_PREFLIGHT,
                "Local draft rejected because preflight contains block reasons.",
            )
        }

        val intent = result.intent
        val invalid = when {
            intent.clientDryRunId.isBlank() -> "clientDryRunId is required."
            intent.symbol.isBlank() -> "Symbol is required."
            intent.quantity <= 0.0 || !intent.quantity.isFinite() ->
                "Quantity must be positive and finite."
            intent.type == OrderType.LIMIT &&
                (intent.limitPriceUsd == null || intent.limitPriceUsd <= 0.0 ||
                    !intent.limitPriceUsd.isFinite()) ->
                "LIMIT draft requires a positive finite limit price."
            result.estimatedNotionalUsd != null &&
                (result.estimatedNotionalUsd < 0.0 || !result.estimatedNotionalUsd.isFinite()) ->
                "Estimated notional must be non-negative and finite."
            else -> null
        }
        if (invalid != null) {
            return rejected(PaperOrderRequestDraftRejection.INVALID_DRAFT_INPUT, invalid)
        }

        val warningMessages = result.warnings.map { it.message }
        val status = if (warningMessages.isEmpty()) {
            PaperOrderRequestDraftStatus.READY_LOCAL
        } else {
            PaperOrderRequestDraftStatus.READY_LOCAL_WITH_WARNINGS
        }
        return PaperOrderRequestDraftValidation.Valid(
            PaperOrderRequestDraft(
                clientDryRunId = intent.clientDryRunId,
                symbol = intent.symbol,
                side = intent.side,
                type = intent.type,
                timeInForce = intent.tif,
                quantity = intent.quantity,
                limitPriceUsd = intent.limitPriceUsd,
                estimatedNotionalUsd = result.estimatedNotionalUsd,
                priceSource = result.priceSource,
                priceFreshness = result.priceFreshness,
                priceAgeMillis = result.priceAgeMillis,
                relatedSignalState = result.relatedSignalState,
                createdAtEpochMillis = intent.createdAtEpochMillis,
                status = status,
                warningMessages = warningMessages,
                executionEnabled = false,
            ),
        )
    }

    private fun rejected(
        reason: PaperOrderRequestDraftRejection,
        message: String,
    ): PaperOrderRequestDraftValidation.Rejected =
        PaperOrderRequestDraftValidation.Rejected(reason = reason, message = message)

    private companion object {
        val APPROVED_PREFLIGHT_STATUSES: Set<PreflightStatus> = setOf(
            PreflightStatus.ALLOWED_DRY_RUN,
            PreflightStatus.WARNING_ONLY,
        )
    }
}
