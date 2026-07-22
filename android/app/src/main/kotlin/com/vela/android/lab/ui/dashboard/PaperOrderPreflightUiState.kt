package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.DisabledExecutionResult
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessSnapshot
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraft

/**
 * Phase 2.m read-only UI state for the "Paper order preflight —
 * dry run only" card. Pure data; no Android imports.
 *
 * Carries **no** credential value. The card has no Submit button
 * and no execution control of any kind — `lastResult` is just a
 * hypothetical evaluation.
 */
data class PaperOrderPreflightUiState(
    val symbolInput: String,
    val side: OrderSide,
    val quantityInput: String,
    val isRunning: Boolean,
    val lastResult: PaperOrderPreflightResult?,
    val lastInputError: String?,
    /** Phase 2.n: surfaces a non-fatal audit-save failure without hiding the preflight result. */
    val lastAuditError: String?,
    /** Phase 2.p: in-memory, non-executable draft built from [lastResult]. */
    val lastDraft: PaperOrderRequestDraft?,
    /** Local builder rejection; never a network/server error. */
    val lastDraftError: String?,
    val isBuildingPayloadPreview: Boolean,
    /** Phase 2.q local theoretical payload; never an HTTP request. */
    val lastPayloadPreview: PaperOrderPayloadPreview?,
    /** Builder or local queue error; never a server submission error. */
    val lastPayloadPreviewError: String?,
    val isCheckingExecutionReadiness: Boolean,
    /** Local-only Phase 2.r gate output; contains no credential value. */
    val lastExecutionReadiness: PaperExecutionReadinessSnapshot?,
    val lastExecutionReadinessError: String?,
    /** Always EXECUTION_DISABLED; never represents a network attempt. */
    val lastDisabledExecutionResult: DisabledExecutionResult?,
) {
    companion object {
        val Initial: PaperOrderPreflightUiState = PaperOrderPreflightUiState(
            symbolInput = "",
            side = OrderSide.BUY,
            quantityInput = "",
            isRunning = false,
            lastResult = null,
            lastInputError = null,
            lastAuditError = null,
            lastDraft = null,
            lastDraftError = null,
            isBuildingPayloadPreview = false,
            lastPayloadPreview = null,
            lastPayloadPreviewError = null,
            isCheckingExecutionReadiness = false,
            lastExecutionReadiness = null,
            lastExecutionReadinessError = null,
            lastDisabledExecutionResult = null,
        )
    }
}
