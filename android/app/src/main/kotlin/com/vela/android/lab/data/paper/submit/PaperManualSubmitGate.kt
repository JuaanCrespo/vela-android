package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.preflight.PreflightStatus

data class PaperManualSubmitGateInput(
    val humanApprovalRecorded: Boolean,
    val sessionArmed: Boolean,
    val realLocked: Boolean,
    val liveEnabled: Boolean,
    val autoPaperEnabled: Boolean,
    val credentialsConfigured: Boolean,
    val accountBlocked: Boolean,
    val tradingBlocked: Boolean,
    val accountRefreshedAtEpochMillis: Long?,
    val clockRefreshedAtEpochMillis: Long?,
    val marketOpen: Boolean?,
    val priceSnapshot: MarketPriceSnapshot?,
    val preflight: PaperOrderPreflightResult?,
    val warningAccepted: Boolean,
    val disabledReadinessStatus: PaperExecutionReadinessStatus?,
    val preview: PaperOrderPayloadPreview?,
    val reviewQueueMatch: Boolean,
    val request: PaperOrderSubmitRequest?,
    val confirmation: PaperManualSubmitConfirmation?,
    val duplicatePreview: Boolean,
    val duplicateClientOrderId: Boolean,
    val submitInFlight: Boolean,
    val nowEpochMillis: Long,
)

sealed interface PaperManualSubmitGateDecision {
    data object Allowed : PaperManualSubmitGateDecision
    data class Blocked(val reasons: List<PaperOrderSubmitError>) :
        PaperManualSubmitGateDecision
}

/** Pure fail-closed policy. It performs no I/O and cannot call the submit client. */
class PaperManualSubmitGate(
    private val featureGate: PaperManualExecutionFeatureGate,
    private val finalPricePolicy: PaperFinalPriceStabilityPolicy =
        PaperFinalPriceStabilityPolicy(),
    private val maxAccountAgeMillis: Long = 15_000L,
    private val maxClockAgeMillis: Long = 15_000L,
    private val maxPreflightAgeMillis: Long = 60_000L,
) {
    fun evaluate(input: PaperManualSubmitGateInput): PaperManualSubmitGateDecision {
        val reasons = buildList {
            val feature = featureGate.evaluate(input.sessionArmed)
            if (!input.humanApprovalRecorded) add(PaperOrderSubmitError.HUMAN_APPROVAL_MISSING)
            if (!feature.compileTimeEnabled || !feature.sessionArmed) {
                add(PaperOrderSubmitError.FEATURE_DISABLED)
            }
            if (feature.emergencyDisabled) add(PaperOrderSubmitError.EMERGENCY_DISABLED)
            if (!input.realLocked) add(PaperOrderSubmitError.REAL_NOT_LOCKED)
            if (input.liveEnabled) add(PaperOrderSubmitError.LIVE_NOT_DISABLED)
            if (input.autoPaperEnabled) add(PaperOrderSubmitError.AUTO_PAPER_NOT_DISABLED)
            if (!input.credentialsConfigured) add(PaperOrderSubmitError.CREDENTIALS_MISSING)
            if (input.accountBlocked) add(PaperOrderSubmitError.ACCOUNT_BLOCKED)
            if (input.tradingBlocked) add(PaperOrderSubmitError.TRADING_BLOCKED)
            if (!isFresh(input.accountRefreshedAtEpochMillis, input.nowEpochMillis, maxAccountAgeMillis)) {
                add(PaperOrderSubmitError.ACCOUNT_STALE)
            }
            if (!isFresh(input.clockRefreshedAtEpochMillis, input.nowEpochMillis, maxClockAgeMillis)) {
                add(PaperOrderSubmitError.CLOCK_STALE)
            }
            if (input.marketOpen != true) add(PaperOrderSubmitError.MARKET_CLOSED)
            when (evaluateFinalPrice(input.preview, input.priceSnapshot, input.nowEpochMillis).result) {
                PaperFinalPriceGateResult.ALLOWED -> Unit
                PaperFinalPriceGateResult.PRICE_NOT_FRESH ->
                    add(PaperOrderSubmitError.PRICE_NOT_FRESH)
                PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED ->
                    add(PaperOrderSubmitError.PRICE_DRIFT_EXCEEDED)
            }
            val preflight = input.preflight
            if (preflight == null ||
                !isFresh(
                    preflight.intent.createdAtEpochMillis,
                    input.nowEpochMillis,
                    maxPreflightAgeMillis,
                ) ||
                preflight.status == PreflightStatus.BLOCKED ||
                preflight.blockReasons.isNotEmpty()
            ) {
                add(PaperOrderSubmitError.PREFLIGHT_BLOCKED)
            } else if (preflight.status == PreflightStatus.WARNING_ONLY && !input.warningAccepted) {
                add(PaperOrderSubmitError.WARNING_NOT_ACCEPTED)
            }
            if (input.disabledReadinessStatus !=
                PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED
            ) {
                add(PaperOrderSubmitError.READINESS_MISSING)
            }
            if (!input.reviewQueueMatch) add(PaperOrderSubmitError.REVIEW_ROW_MISSING)
            if (!matches(input.preview, input.request, preflight)) {
                add(PaperOrderSubmitError.PREVIEW_MISMATCH)
            }
            val confirmation = input.confirmation
            if (confirmation == null) {
                add(PaperOrderSubmitError.CONFIRMATION_MISSING)
            } else {
                if (input.nowEpochMillis > confirmation.expiresAtEpochMillis) {
                    add(PaperOrderSubmitError.CONFIRMATION_EXPIRED)
                }
                if (!confirmationMatches(confirmation, input.preview, input.request)) {
                    add(PaperOrderSubmitError.CONFIRMATION_MISMATCH)
                }
            }
            if (input.duplicatePreview) add(PaperOrderSubmitError.DUPLICATE_PREVIEW)
            if (input.duplicateClientOrderId) {
                add(PaperOrderSubmitError.DUPLICATE_CLIENT_ORDER_ID)
            }
            if (input.submitInFlight) add(PaperOrderSubmitError.SUBMIT_ALREADY_IN_FLIGHT)
        }.distinct()
        return if (reasons.isEmpty()) {
            PaperManualSubmitGateDecision.Allowed
        } else {
            PaperManualSubmitGateDecision.Blocked(reasons)
        }
    }

    fun evaluateFinalPrice(
        preview: PaperOrderPayloadPreview?,
        finalPrice: MarketPriceSnapshot?,
        nowEpochMillis: Long,
    ): PaperFinalPriceEvaluation = finalPricePolicy.evaluate(
        preview = preview,
        finalPrice = finalPrice,
        nowEpochMillis = nowEpochMillis,
    )

    private fun isFresh(timestamp: Long?, now: Long, maxAge: Long): Boolean =
        timestamp != null && timestamp <= now && now - timestamp <= maxAge

    private fun matches(
        preview: PaperOrderPayloadPreview?,
        request: PaperOrderSubmitRequest?,
        preflight: PaperOrderPreflightResult?,
    ): Boolean = preview != null && request != null && preflight != null &&
        preview.previewId == request.previewId &&
        preview.linkedClientDryRunId == request.linkedClientDryRunId &&
        preview.symbol == request.symbol &&
        preview.side == request.side &&
        preview.type == request.type &&
        preview.timeInForce == request.timeInForce &&
        preview.quantity == request.quantity &&
        preview.limitPriceUsd == request.limitPrice &&
        preflight.intent.clientDryRunId == request.linkedClientDryRunId &&
        preflight.intent.symbol == request.symbol &&
        preflight.intent.side == request.side &&
        preflight.intent.quantity == request.quantity

    private fun confirmationMatches(
        confirmation: PaperManualSubmitConfirmation,
        preview: PaperOrderPayloadPreview?,
        request: PaperOrderSubmitRequest?,
    ): Boolean = preview != null && request != null &&
        confirmation.tokenId == request.confirmationTokenId &&
        confirmation.previewId == preview.previewId &&
        confirmation.symbol == preview.symbol &&
        confirmation.side == preview.side.name &&
        confirmation.quantity == preview.quantity &&
        confirmation.priceSource == preview.priceSource &&
        confirmation.priceFreshness == preview.priceFreshness &&
        confirmation.previewGeneratedAtEpochMillis == preview.generatedAtEpochMillis
}
