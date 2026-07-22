package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessSnapshot
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.submit.PaperManualExecutionFeatureGate
import com.vela.android.lab.data.paper.submit.PaperFinalPriceEvaluation
import com.vela.android.lab.data.paper.submit.PaperManualSubmitApproval
import com.vela.android.lab.data.paper.submit.PaperManualSubmitConfirmation
import com.vela.android.lab.data.paper.submit.PaperManualSubmitExecutor
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGate
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGateDecision
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGateInput
import com.vela.android.lab.data.paper.submit.PaperManualSubmitTokenIssue
import com.vela.android.lab.data.paper.submit.PaperManualSubmitTokenStore
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitRequest
import com.vela.android.lab.state.AppState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Foreground-only Phase 2.v orchestrator. No lifecycle method submits automatically. */
class PaperManualSubmitViewModel(
    private val featureGate: PaperManualExecutionFeatureGate,
    private val gate: PaperManualSubmitGate,
    private val tokenStore: PaperManualSubmitTokenStore,
    private val executor: PaperManualSubmitExecutor,
    private val readOnlyClient: AlpacaPaperReadOnlyClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val priceSnapshotProvider: MarketPriceSnapshotProvider,
    private val previewRepository: PaperOrderPayloadPreviewRepository,
    private val appState: AppState,
    private val clock: () -> Instant = { Instant.now() },
    private val attemptIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clientOrderIdFactory: () -> String = { "vela-${UUID.randomUUID()}" },
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PaperManualSubmitUiState.initial(featureGate.compileTimeEnabled),
    )
    val uiState: StateFlow<PaperManualSubmitUiState> = _uiState.asStateFlow()

    private var preflight: PaperOrderPreflightResult? = null
    private var preview: PaperOrderPayloadPreview? = null
    private var disabledReadiness: PaperExecutionReadinessSnapshot? = null
    private var priceSnapshot: MarketPriceSnapshot? = null
    private var account: PaperAccountSnapshot? = null
    private var accountRefreshedAt: Long? = null
    private var clockSnapshot: PaperClockSnapshot? = null
    private var clockRefreshedAt: Long? = null
    private var reviewQueueMatch: Boolean = false
    private var confirmation: PaperManualSubmitConfirmation? = null
    private var request: PaperOrderSubmitRequest? = null

    fun updateSource(
        preflight: PaperOrderPreflightResult?,
        preview: PaperOrderPayloadPreview?,
        readiness: PaperExecutionReadinessSnapshot?,
    ) {
        val currentPreviewId = this.preview?.previewId
        if (currentPreviewId == preview?.previewId &&
            this.preflight == preflight && this.disabledReadiness == readiness
        ) {
            return
        }
        this.preflight = preflight
        this.preview = preview
        this.disabledReadiness = readiness
        priceSnapshot = null
        account = null
        accountRefreshedAt = null
        clockSnapshot = null
        clockRefreshedAt = null
        reviewQueueMatch = false
        confirmation = null
        request = null
        tokenStore.invalidate()
        _uiState.value = PaperManualSubmitUiState.initial(featureGate.compileTimeEnabled).copy(
            realLocked = appState.realModeLocked,
            previewId = preview?.previewId,
            symbol = preview?.symbol,
            side = preview?.side?.name,
            quantity = preview?.quantity,
            orderType = preview?.type?.name,
            timeInForce = preview?.timeInForce?.name,
            estimatedNotionalUsd = preview?.estimatedNotionalUsd,
            previewPriceUsd = preview?.previewUnitPriceUsd(),
            priceSource = preview?.priceSource,
            priceFreshness = preview?.priceFreshness,
            preflightStatus = preflight?.status?.name,
            readinessStatus = readiness?.status?.name,
            requiredConfirmationText = preview?.let(PaperManualSubmitTokenStore::requiredText)
                .orEmpty(),
        )
    }

    fun armSession() {
        if (!featureGate.compileTimeEnabled || preview == null) return
        _uiState.update { it.copy(sessionArmed = true, lastError = null) }
        refreshSubmitReadiness()
    }

    fun disarmSession() {
        tokenStore.invalidate()
        confirmation = null
        request = null
        _uiState.update {
            it.copy(
                sessionArmed = false,
                confirmationInput = "",
                gateAllowed = false,
                gateReasons = listOf(com.vela.android.lab.data.paper.submit.PaperOrderSubmitError.FEATURE_DISABLED),
                isSubmitting = false,
            )
        }
    }

    fun onWarningAcceptedChange(accepted: Boolean) {
        tokenStore.invalidate()
        confirmation = null
        request = null
        _uiState.update {
            it.copy(
                warningAccepted = accepted,
                confirmationInput = "",
                gateAllowed = false,
            )
        }
        recomputeGate()
    }

    fun onConfirmationInputChange(value: String) {
        _uiState.update { it.copy(confirmationInput = value, lastError = null) }
        val currentPreview = preview
        if (currentPreview == null || value != _uiState.value.requiredConfirmationText) {
            tokenStore.invalidate()
            confirmation = null
            request = null
            recomputeGate()
            return
        }
        val now = clock().toEpochMilli()
        val finalPriceEvaluation = gate.evaluateFinalPrice(currentPreview, priceSnapshot, now)
        _uiState.update { it.withFinalPriceEvaluation(finalPriceEvaluation) }
        if (!finalPriceEvaluation.allowed) {
            tokenStore.invalidate()
            confirmation = null
            request = null
            recomputeGate(now)
            return
        }
        when (val issued = tokenStore.issue(currentPreview, value)) {
            is PaperManualSubmitTokenIssue.Rejected -> {
                confirmation = null
                request = null
            }
            is PaperManualSubmitTokenIssue.Issued -> {
                confirmation = issued.confirmation
                request = PaperOrderSubmitRequest(
                    submitAttemptId = attemptIdFactory(),
                    previewId = currentPreview.previewId,
                    linkedClientDryRunId = currentPreview.linkedClientDryRunId,
                    symbol = currentPreview.symbol,
                    side = currentPreview.side,
                    type = currentPreview.type,
                    timeInForce = currentPreview.timeInForce,
                    quantity = currentPreview.quantity,
                    limitPrice = currentPreview.limitPriceUsd,
                    clientOrderId = clientOrderIdFactory(),
                    generatedAtEpochMillis = clock().toEpochMilli(),
                    confirmationTokenId = issued.confirmation.tokenId,
                )
            }
        }
        recomputeGate()
    }

    fun refreshSubmitReadiness() {
        val currentPreview = preview ?: return
        if (_uiState.value.isRefreshing || !_uiState.value.sessionArmed) return
        tokenStore.invalidate()
        confirmation = null
        request = null
        _uiState.update {
            it.copy(
                isRefreshing = true,
                confirmationInput = "",
                gateAllowed = false,
                lastResult = null,
                lastError = null,
            )
        }
        viewModelScope.launch {
            val credentialsConfigured = credentialsStore.hasCredentials()
            val accountResult = readOnlyClient.fetchAccount()
            val clockResult = readOnlyClient.fetchClock()
            val now = clock().toEpochMilli()
            account = (accountResult as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperAccountSnapshot>)
                ?.value
            accountRefreshedAt = if (account != null) now else null
            clockSnapshot =
                (clockResult as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperClockSnapshot>)
                    ?.value
            clockRefreshedAt = if (clockSnapshot != null) now else null
            priceSnapshot = priceSnapshotProvider.snapshotFor(currentPreview.symbol)
            reviewQueueMatch = previewRepository.byPreviewId(currentPreview.previewId)
                ?.let { row ->
                    row.previewId == currentPreview.previewId &&
                        row.linkedClientDryRunId == currentPreview.linkedClientDryRunId &&
                        row.symbol == currentPreview.symbol &&
                        row.side == currentPreview.side.name &&
                        row.orderType == currentPreview.type.name &&
                        row.timeInForce == currentPreview.timeInForce.name &&
                        row.quantity == currentPreview.quantity &&
                        row.limitPriceUsd == currentPreview.limitPriceUsd &&
                        !row.executionEnabled &&
                        row.endpointPreview == "DISABLED" &&
                        row.httpMethodPreview == "POST_DISABLED"
                } == true
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    realLocked = appState.realModeLocked,
                    credentialsConfigured = credentialsConfigured,
                    accountRefreshedAtEpochMillis = accountRefreshedAt,
                    clockRefreshedAtEpochMillis = clockRefreshedAt,
                    marketOpen = clockSnapshot?.isOpen,
                    lastError = if (account == null || clockSnapshot == null) {
                        "Paper account/clock refresh failed; submit remains blocked."
                    } else {
                        null
                    },
                )
            }
            recomputeGate(now)
        }
    }

    fun submitOnce() {
        val currentRequest = request ?: return
        val currentPreview = preview ?: return
        if (_uiState.value.isSubmitting || !_uiState.value.gateAllowed) return
        val input = gateInput()
        _uiState.update { it.copy(isSubmitting = true, gateAllowed = false, lastError = null) }
        viewModelScope.launch {
            val result = executor.executeOnce(currentRequest, currentPreview, input)
            confirmation = null
            request = null
            tokenStore.invalidate()
            _uiState.update {
                it.copy(
                    sessionArmed = false,
                    isSubmitting = false,
                    confirmationInput = "",
                    gateAllowed = false,
                    lastResult = result,
                    lastError = result.safeErrorMessage,
                )
            }
        }
    }

    private fun recomputeGate(nowEpochMillis: Long = clock().toEpochMilli()) {
        val decision = gate.evaluate(gateInput(nowEpochMillis))
        val finalPriceEvaluation = gate.evaluateFinalPrice(
            preview = preview,
            finalPrice = priceSnapshot,
            nowEpochMillis = nowEpochMillis,
        )
        when (decision) {
            PaperManualSubmitGateDecision.Allowed -> _uiState.update {
                it.withFinalPriceEvaluation(finalPriceEvaluation).copy(
                    gateAllowed = true,
                    gateReasons = emptyList(),
                )
            }
            is PaperManualSubmitGateDecision.Blocked -> _uiState.update {
                it.withFinalPriceEvaluation(finalPriceEvaluation).copy(
                    gateAllowed = false,
                    gateReasons = decision.reasons,
                )
            }
        }
    }

    private fun gateInput(
        nowEpochMillis: Long = clock().toEpochMilli(),
    ): PaperManualSubmitGateInput = PaperManualSubmitGateInput(
        humanApprovalRecorded = PaperManualSubmitApproval.RECORDED,
        sessionArmed = _uiState.value.sessionArmed,
        realLocked = appState.realModeLocked,
        liveEnabled = false,
        autoPaperEnabled = false,
        credentialsConfigured = _uiState.value.credentialsConfigured,
        accountBlocked = account?.accountBlocked == true,
        tradingBlocked = account?.tradingBlocked == true,
        accountRefreshedAtEpochMillis = accountRefreshedAt,
        clockRefreshedAtEpochMillis = clockRefreshedAt,
        marketOpen = clockSnapshot?.isOpen,
        priceSnapshot = priceSnapshot,
        preflight = preflight,
        warningAccepted = _uiState.value.warningAccepted,
        disabledReadinessStatus = disabledReadiness?.status,
        preview = preview,
        reviewQueueMatch = reviewQueueMatch,
        request = request,
        confirmation = confirmation,
        duplicatePreview = false,
        duplicateClientOrderId = false,
        submitInFlight = _uiState.value.isSubmitting,
        nowEpochMillis = nowEpochMillis,
    )

    override fun onCleared() {
        tokenStore.invalidate()
        confirmation = null
        request = null
        super.onCleared()
    }
}

private fun PaperOrderPayloadPreview.previewUnitPriceUsd(): Double? {
    val notional = estimatedNotionalUsd ?: return null
    if (!notional.isFinite() || notional <= 0.0 || !quantity.isFinite() || quantity <= 0.0) {
        return null
    }
    return (notional / quantity).takeIf { it.isFinite() && it > 0.0 }
}

private fun PaperManualSubmitUiState.withFinalPriceEvaluation(
    evaluation: PaperFinalPriceEvaluation,
): PaperManualSubmitUiState = copy(
    previewPriceUsd = evaluation.previewPriceUsd ?: previewPriceUsd,
    finalPriceUsd = evaluation.finalPriceUsd,
    finalPriceSource = evaluation.finalPriceSource,
    finalPriceFreshness = evaluation.finalPriceFreshness,
    finalPriceAgeMillis = evaluation.finalPriceAgeMillis,
    finalPriceRawAgeMillis = evaluation.rawFinalPriceAgeMillis,
    finalPriceFutureSkewToleranceApplied = evaluation.futureSkewToleranceApplied,
    finalPriceAllowedFutureSkewMillis = evaluation.allowedFutureSkewMillis,
    finalPriceDriftPercent = evaluation.driftPercent,
    allowedPriceDriftPercent = evaluation.allowedDriftPercent,
    finalPriceMaxAgeMillis = evaluation.allowedMaxAgeMillis ?: finalPriceMaxAgeMillis,
    finalPriceGateResult = evaluation.result.name,
)
