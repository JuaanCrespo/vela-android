package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.paper.submit.PaperOrderSubmitError
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitResult
import com.vela.android.lab.data.paper.submit.PaperFinalPriceStabilityPolicy
import com.vela.android.lab.data.paper.status.PaperOrderReconciliationSnapshot
import com.vela.android.lab.data.paper.status.PaperOrderReconciliationVerdict
import com.vela.android.lab.data.paper.status.PaperOrderStatusSnapshot
import com.vela.android.lab.data.paper.status.ReconciledPaperOrder

enum class PaperOrderReconciliationUiStatus {
    RESTORING,
    CLEAR,
    EXACT_UNRESOLVED,
    EXACT_TERMINAL,
    MULTIPLE,
    AMBIGUOUS,
}

/** Credential-free foreground state for one manually confirmed Paper attempt. */
data class PaperManualSubmitUiState(
    val compileTimeEnabled: Boolean,
    val sessionArmed: Boolean,
    val paperOnly: Boolean,
    val realLocked: Boolean,
    val liveEnabled: Boolean,
    val autoPaperEnabled: Boolean,
    val previewId: String?,
    val symbol: String?,
    val side: String?,
    val quantity: Double?,
    val orderType: String?,
    val timeInForce: String?,
    val estimatedNotionalUsd: Double?,
    val previewPriceUsd: Double?,
    val priceSource: String?,
    val priceFreshness: String?,
    val finalPriceUsd: Double?,
    val finalPriceSource: String?,
    val finalPriceFreshness: String?,
    val finalPriceAgeMillis: Long?,
    val finalPriceRawAgeMillis: Long?,
    val finalPriceFutureSkewToleranceApplied: Boolean,
    val finalPriceAllowedFutureSkewMillis: Long,
    val finalPriceDriftPercent: Double?,
    val allowedPriceDriftPercent: Double,
    val finalPriceMaxAgeMillis: Long,
    val finalPriceGateResult: String,
    val marketOpen: Boolean?,
    val preflightStatus: String?,
    val readinessStatus: String?,
    val credentialsConfigured: Boolean,
    val accountRefreshedAtEpochMillis: Long?,
    val clockRefreshedAtEpochMillis: Long?,
    val warningAccepted: Boolean,
    val requiredConfirmationText: String,
    val confirmationInput: String,
    val confirmationExpiresAtEpochMillis: Long?,
    val gateAllowed: Boolean,
    val gateReasons: List<PaperOrderSubmitError>,
    val isRefreshing: Boolean,
    val isSubmitting: Boolean,
    val lastResult: PaperOrderSubmitResult?,
    val lastError: String?,
    val reconciliation: PaperOrderReconciliationSnapshot?,
    val reconciliationRestoreComplete: Boolean,
    val isRefreshingOrderStatus: Boolean,
    val isResettingReconciliation: Boolean,
    val orderStatusError: String?,
) {
    /**
     * Fail-closed preparation boundary shared by the visible card and its action callbacks.
     * Keeping this decision on the state prevents a stale/enabled Compose button from being the
     * only protection while persistent reconciliation is still loading or unresolved.
     */
    val blocksNewPaperPreparation: Boolean
        get() = !reconciliationRestoreComplete || reconciliation?.preparationAllowed != true

    val reconciliationUiStatus: PaperOrderReconciliationUiStatus
        get() = when {
            !reconciliationRestoreComplete -> PaperOrderReconciliationUiStatus.RESTORING
            reconciliation == null -> PaperOrderReconciliationUiStatus.AMBIGUOUS
            reconciliation.verdict == PaperOrderReconciliationVerdict.CLEAR ||
                reconciliation.verdict == PaperOrderReconciliationVerdict.READY ->
                PaperOrderReconciliationUiStatus.CLEAR
            reconciliation.verdict == PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED ->
                PaperOrderReconciliationUiStatus.EXACT_UNRESOLVED
            reconciliation.verdict ==
                PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS ->
                PaperOrderReconciliationUiStatus.MULTIPLE
            reconciliation.verdict == PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED &&
                reconciliation.resetEligibleAttemptIds.size == 1 ->
                PaperOrderReconciliationUiStatus.EXACT_TERMINAL
            else -> PaperOrderReconciliationUiStatus.AMBIGUOUS
        }

    val trackedOrder: ReconciledPaperOrder?
        get() = reconciliation?.candidates?.singleOrNull {
            it.unresolvedRemoteOrder || it.terminalResetRequired
        }

    val orderStatusSnapshot: PaperOrderStatusSnapshot?
        get() = trackedOrder?.latestLifecycleSnapshot

    val orderStatusCheckedAtEpochMillis: Long?
        get() = trackedOrder?.lifecycleObservedAtEpochMillis

    val newPreparationAllowed: Boolean
        get() = reconciliationRestoreComplete && reconciliation?.preparationAllowed == true

    val orderTrackingRestoreComplete: Boolean
        get() = reconciliationRestoreComplete

    val untrackableSubmittedOrder: Boolean
        get() = reconciliationUiStatus == PaperOrderReconciliationUiStatus.AMBIGUOUS

    val canRefreshSingleExactOrder: Boolean
        get() = reconciliationUiStatus == PaperOrderReconciliationUiStatus.EXACT_UNRESOLVED &&
            trackedOrder?.mappingExact == true && trackedOrder?.orderId != null

    val resetEligibleAttemptId: String?
        get() = if (reconciliationUiStatus == PaperOrderReconciliationUiStatus.EXACT_TERMINAL) {
            reconciliation?.resetEligibleAttemptIds?.singleOrNull()
        } else {
            null
        }

    companion object {
        fun initial(compileTimeEnabled: Boolean): PaperManualSubmitUiState =
            PaperManualSubmitUiState(
                compileTimeEnabled = compileTimeEnabled,
                sessionArmed = false,
                paperOnly = true,
                realLocked = true,
                liveEnabled = false,
                autoPaperEnabled = false,
                previewId = null,
                symbol = null,
                side = null,
                quantity = null,
                orderType = null,
                timeInForce = null,
                estimatedNotionalUsd = null,
                previewPriceUsd = null,
                priceSource = null,
                priceFreshness = null,
                finalPriceUsd = null,
                finalPriceSource = null,
                finalPriceFreshness = null,
                finalPriceAgeMillis = null,
                finalPriceRawAgeMillis = null,
                finalPriceFutureSkewToleranceApplied = false,
                finalPriceAllowedFutureSkewMillis =
                    PaperFinalPriceStabilityPolicy.DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS,
                finalPriceDriftPercent = null,
                allowedPriceDriftPercent =
                    PaperFinalPriceStabilityPolicy.DEFAULT_MAX_DRIFT_PERCENT,
                finalPriceMaxAgeMillis =
                    PaperFinalPriceStabilityPolicy.DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS,
                finalPriceGateResult = "NOT_CHECKED",
                marketOpen = null,
                preflightStatus = null,
                readinessStatus = null,
                credentialsConfigured = false,
                accountRefreshedAtEpochMillis = null,
                clockRefreshedAtEpochMillis = null,
                warningAccepted = false,
                requiredConfirmationText = "",
                confirmationInput = "",
                confirmationExpiresAtEpochMillis = null,
                gateAllowed = false,
                gateReasons = listOf(PaperOrderSubmitError.FEATURE_DISABLED),
                isRefreshing = false,
                isSubmitting = false,
                lastResult = null,
                lastError = null,
                reconciliation = null,
                reconciliationRestoreComplete = false,
                isRefreshingOrderStatus = false,
                isResettingReconciliation = false,
                orderStatusError = null,
            )
    }
}
