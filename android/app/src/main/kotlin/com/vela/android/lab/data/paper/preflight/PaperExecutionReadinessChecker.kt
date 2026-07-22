package com.vela.android.lab.data.paper.preflight

import java.time.Instant

/**
 * Pure local Phase 2.r readiness checker.
 *
 * It receives only a payload preview, the hard-disabled execution
 * guard, the current REAL-lock boolean, and a credential-presence
 * boolean. It has no network, persistence, account, or credential
 * value dependency and cannot create or send an HTTP request.
 */
class PaperExecutionReadinessChecker(
    private val executionGuard: PaperTradingExecutionGuard = PaperTradingExecutionGuard,
    private val clock: () -> Instant = { Instant.now() },
) {

    fun check(
        preview: PaperOrderPayloadPreview,
        realLocked: Boolean,
        credentialsConfigured: Boolean,
    ): PaperExecutionReadinessSnapshot {
        val hasValidPreview = preview.isLocallyValidForReadiness()
        val reasons = buildList {
            add(PaperExecutionReadinessReason.EXECUTION_DISABLED)
            add(PaperExecutionReadinessReason.PAPER_POST_ORDERS_DISABLED)
            add(PaperExecutionReadinessReason.LIVE_ENDPOINT_DISABLED)
            add(PaperExecutionReadinessReason.AUTO_PAPER_DISABLED)
            add(PaperExecutionReadinessReason.FOREGROUND_SERVICE_DISABLED)
            if (!hasValidPreview) {
                add(PaperExecutionReadinessReason.INVALID_PAYLOAD_PREVIEW)
            }
            if (executionGuard.canExecuteOrders) {
                add(PaperExecutionReadinessReason.EXECUTION_GUARD_ENABLED)
            }
            if (!realLocked) {
                add(PaperExecutionReadinessReason.REAL_MODE_NOT_LOCKED)
            }
        }
        val status = when {
            !hasValidPreview -> PaperExecutionReadinessStatus.NOT_READY
            executionGuard.canExecuteOrders || !realLocked ->
                PaperExecutionReadinessStatus.BLOCKED
            else -> PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED
        }
        val warnings = if (credentialsConfigured) {
            emptyList()
        } else {
            listOf(PaperExecutionReadinessReason.CREDENTIALS_NOT_CONFIGURED)
        }

        return PaperExecutionReadinessSnapshot(
            previewId = preview.previewId,
            linkedClientDryRunId = preview.linkedClientDryRunId,
            hasValidPreview = hasValidPreview,
            executionEnabled = false,
            realLocked = realLocked,
            liveEndpointAllowed = false,
            paperPostOrdersAllowed = false,
            autoPaperEnabled = false,
            foregroundServiceEnabled = false,
            credentialsConfigured = credentialsConfigured,
            status = status,
            blockingReasons = reasons,
            warnings = warnings,
            checkedAtEpochMillis = clock().toEpochMilli(),
        )
    }

    private fun PaperOrderPayloadPreview.isLocallyValidForReadiness(): Boolean =
        previewId.isNotBlank() &&
            linkedClientDryRunId.isNotBlank() &&
            symbol.isNotBlank() &&
            quantity.isFinite() && quantity > 0.0 &&
            !executionEnabled &&
            endpointPreview == PaperOrderPayloadPreview.ENDPOINT_DISABLED &&
            httpMethodPreview == PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED &&
            payloadFields.symbol == symbol &&
            payloadFields.side == side.name.lowercase() &&
            payloadFields.type == type.name.lowercase() &&
            payloadFields.timeInForce == timeInForce.name.lowercase() &&
            payloadFields.quantity == quantity &&
            payloadFields.limitPriceUsd == limitPriceUsd
}
