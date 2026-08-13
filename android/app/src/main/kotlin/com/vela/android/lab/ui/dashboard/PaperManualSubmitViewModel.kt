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
import com.vela.android.lab.data.paper.status.AlpacaPaperOrderStatusReadOnlyClient
import com.vela.android.lab.data.paper.status.AlpacaPaperOrderStatusEndpoint
import com.vela.android.lab.data.paper.status.PaperOrderLifecycleStatus
import com.vela.android.lab.data.paper.status.PaperOrderStatusSnapshot
import com.vela.android.lab.data.paper.status.PaperOrderTrackingRestoreResult
import com.vela.android.lab.data.paper.status.PaperOrderTrackingSource
import com.vela.android.lab.data.paper.status.TrackedPaperOrder
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
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitResult
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitStatus
import com.vela.android.lab.state.AppState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    private val orderStatusClient: AlpacaPaperOrderStatusReadOnlyClient,
    private val orderStatusTrackerRepository: PaperOrderTrackingSource,
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
    private var confirmationTokenIssuedForArmedSession: Boolean = false

    init {
        restoreLatestSubmittedOrder()
    }

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
        val trackingState = _uiState.value
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
            trackedOrder = trackingState.trackedOrder,
            orderStatusSnapshot = trackingState.orderStatusSnapshot,
            orderStatusCheckedAtEpochMillis = trackingState.orderStatusCheckedAtEpochMillis,
            isRefreshingOrderStatus = false,
            orderStatusError = trackingState.orderStatusError,
            newPreparationAllowed = trackingState.newPreparationAllowed,
            orderTrackingRestoreComplete = trackingState.orderTrackingRestoreComplete,
            untrackableSubmittedOrder = trackingState.untrackableSubmittedOrder,
        )
    }

    @Synchronized
    fun armSession() {
        if (!featureGate.compileTimeEnabled || preview == null || hasUnresolvedTrackedOrder() ||
            _uiState.value.sessionArmed || _uiState.value.isSubmitting
        ) return
        confirmationTokenIssuedForArmedSession = false
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
                confirmationExpiresAtEpochMillis = null,
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
                confirmationExpiresAtEpochMillis = null,
                gateAllowed = false,
            )
        }
        recomputeGate()
    }

    @Synchronized
    fun onConfirmationInputChange(value: String) {
        if (!_uiState.value.sessionArmed || confirmationTokenIssuedForArmedSession) return
        _uiState.update {
            it.copy(
                confirmationInput = value,
                confirmationExpiresAtEpochMillis = null,
                lastError = null,
            )
        }
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
                confirmationTokenIssuedForArmedSession = true
                confirmation = issued.confirmation
                _uiState.update {
                    it.copy(
                        confirmationExpiresAtEpochMillis =
                            issued.confirmation.expiresAtEpochMillis,
                    )
                }
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
                confirmationExpiresAtEpochMillis = null,
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
            val trackedOrder = result.toTrackedPaperOrder(currentPreview)
            val existingTracking = _uiState.value
            val ambiguousWithoutTrackableId =
                trackedOrder == null && (
                    result.status == PaperOrderSubmitStatus.SUBMITTED ||
                        result.status == PaperOrderSubmitStatus.FAILED &&
                            !result.mayResetWithoutLifecycleLookup()
                    )
            val replacesExistingTracking = trackedOrder != null || ambiguousWithoutTrackableId
            val previousTrackingResolved =
                existingTracking.orderTrackingRestoreComplete &&
                    !existingTracking.untrackableSubmittedOrder &&
                    (existingTracking.trackedOrder == null ||
                        existingTracking.orderStatusSnapshot?.status
                            ?.allowsNewPreparation() == true)
            confirmation = null
            request = null
            tokenStore.invalidate()
            _uiState.update {
                it.copy(
                    sessionArmed = false,
                    isSubmitting = false,
                    confirmationInput = "",
                    confirmationExpiresAtEpochMillis = null,
                    gateAllowed = false,
                    lastResult = result,
                    lastError = result.safeErrorMessage,
                    trackedOrder = when {
                        trackedOrder != null -> trackedOrder
                        ambiguousWithoutTrackableId -> null
                        else -> existingTracking.trackedOrder
                    },
                    orderStatusSnapshot =
                        if (replacesExistingTracking) null
                        else existingTracking.orderStatusSnapshot,
                    orderStatusCheckedAtEpochMillis =
                        if (replacesExistingTracking) null
                        else existingTracking.orderStatusCheckedAtEpochMillis,
                    isRefreshingOrderStatus = false,
                    orderStatusError = when {
                        ambiguousWithoutTrackableId ->
                            "The latest Paper attempt cannot be tracked safely. " +
                                "Another preparation remains blocked."
                        trackedOrder != null -> null
                        else -> existingTracking.orderStatusError
                    },
                    newPreparationAllowed =
                        result.mayResetWithoutLifecycleLookup() &&
                            !replacesExistingTracking && previousTrackingResolved,
                    orderTrackingRestoreComplete = true,
                    untrackableSubmittedOrder = when {
                        ambiguousWithoutTrackableId -> true
                        trackedOrder != null -> false
                        else -> existingTracking.untrackableSubmittedOrder
                    },
                )
            }
        }
    }

    /** Explicit GET-only lifecycle refresh. Never polls, retries, arms, or submits. */
    fun refreshOrderStatus() {
        val current = _uiState.value
        val trackedOrder = current.trackedOrder ?: return
        val client = orderStatusClient
        if (current.sessionArmed || current.isSubmitting || current.isRefreshing ||
            current.isRefreshingOrderStatus
        ) {
            return
        }
        _uiState.update {
            it.copy(
                isRefreshingOrderStatus = true,
                orderStatusError = null,
            )
        }
        viewModelScope.launch {
            val now = clock().toEpochMilli()
            val result = try {
                client.fetchOrderStatus(trackedOrder.orderId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        orderStatusCheckedAtEpochMillis = now,
                        isRefreshingOrderStatus = false,
                        orderStatusError =
                            "Paper order status lookup failed safely; no retry was attempted.",
                        newPreparationAllowed = false,
                    )
                }
                return@launch
            }
            when (result) {
                is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok -> _uiState.update {
                    it.copy(
                        orderStatusSnapshot = result.value,
                        orderStatusCheckedAtEpochMillis = now,
                        isRefreshingOrderStatus = false,
                        orderStatusError = null,
                        newPreparationAllowed = result.value.status.allowsNewPreparation(),
                    )
                }
                else -> _uiState.update {
                    it.copy(
                        orderStatusCheckedAtEpochMillis = now,
                        isRefreshingOrderStatus = false,
                        orderStatusError = result.safeStatusError(),
                        newPreparationAllowed = false,
                    )
                }
            }
        }
    }

    /**
     * Clears only the active one-shot attempt after a terminal outcome.
     * The append-only audit and the last tracked Alpaca lifecycle remain intact.
     */
    fun canResetForNewPreparation(): Boolean {
        val current = _uiState.value
        return current.lastResult != null && current.newPreparationAllowed &&
            current.orderTrackingRestoreComplete && !current.untrackableSubmittedOrder &&
            !current.sessionArmed && !current.isSubmitting && !current.isRefreshing &&
            !current.isRefreshingOrderStatus
    }

    fun resetForNewPreparation(): Boolean {
        val current = _uiState.value
        if (!canResetForNewPreparation()) return false
        tokenStore.invalidate()
        confirmation = null
        request = null
        preflight = null
        preview = null
        disabledReadiness = null
        priceSnapshot = null
        account = null
        accountRefreshedAt = null
        clockSnapshot = null
        clockRefreshedAt = null
        reviewQueueMatch = false
        _uiState.value = PaperManualSubmitUiState.initial(featureGate.compileTimeEnabled).copy(
            realLocked = appState.realModeLocked,
            trackedOrder = current.trackedOrder,
            orderStatusSnapshot = current.orderStatusSnapshot,
            orderStatusCheckedAtEpochMillis = current.orderStatusCheckedAtEpochMillis,
            orderStatusError = current.orderStatusError,
            newPreparationAllowed = current.newPreparationAllowed,
            orderTrackingRestoreComplete = current.orderTrackingRestoreComplete,
            untrackableSubmittedOrder = current.untrackableSubmittedOrder,
        )
        return true
    }

    private fun restoreLatestSubmittedOrder() {
        viewModelScope.launch {
            val restored = try {
                orderStatusTrackerRepository.latestUnresolved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { current ->
                    if (current.orderTrackingRestoreComplete) current
                    else current.copy(
                        orderTrackingRestoreComplete = false,
                        untrackableSubmittedOrder = true,
                        orderStatusError =
                            "Local Paper audit could not be restored; new attempts remain blocked.",
                        newPreparationAllowed = false,
                    )
                }
                return@launch
            }
            _uiState.update { current ->
                if (current.orderTrackingRestoreComplete) return@update current
                when (restored) {
                    PaperOrderTrackingRestoreResult.None -> current.copy(
                        orderTrackingRestoreComplete = true,
                        untrackableSubmittedOrder = false,
                        orderStatusError = null,
                    )
                    is PaperOrderTrackingRestoreResult.Trackable -> current.copy(
                        trackedOrder = restored.order,
                        orderStatusSnapshot = null,
                        orderStatusCheckedAtEpochMillis = null,
                        orderStatusError = null,
                        newPreparationAllowed = false,
                        orderTrackingRestoreComplete = true,
                        untrackableSubmittedOrder = false,
                    )
                    is PaperOrderTrackingRestoreResult.Untrackable -> current.copy(
                        trackedOrder = null,
                        orderStatusSnapshot = null,
                        orderStatusCheckedAtEpochMillis = null,
                        orderStatusError =
                            "The latest Paper attempt cannot be tracked safely. " +
                                "New attempts remain blocked.",
                        newPreparationAllowed = false,
                        orderTrackingRestoreComplete = true,
                        untrackableSubmittedOrder = true,
                    )
                }
            }
        }
    }

    private fun hasUnresolvedTrackedOrder(): Boolean =
        !_uiState.value.orderTrackingRestoreComplete ||
            _uiState.value.untrackableSubmittedOrder ||
            (_uiState.value.trackedOrder != null && !_uiState.value.newPreparationAllowed)

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

private fun PaperOrderSubmitResult.toTrackedPaperOrder(
    preview: PaperOrderPayloadPreview,
): TrackedPaperOrder? {
    val orderId = alpacaOrderId ?: return null
    if (!AlpacaPaperOrderStatusEndpoint.isCanonicalOrderId(orderId)) return null
    return TrackedPaperOrder(
        orderId = orderId,
        submitAttemptId = submitAttemptId,
        previewId = previewId,
        clientOrderId = clientOrderId,
        symbol = preview.symbol,
        side = preview.side.name,
        quantity = preview.quantity,
        submittedAtEpochMillis = submittedAtEpochMillis,
    )
}

private fun PaperOrderSubmitResult.mayResetWithoutLifecycleLookup(): Boolean = when (status) {
    PaperOrderSubmitStatus.BLOCKED,
    PaperOrderSubmitStatus.REJECTED,
    -> true
    PaperOrderSubmitStatus.FAILED ->
        errorCode == com.vela.android.lab.data.paper.submit.PaperOrderSubmitError.AUDIT_WRITE_FAILED
    PaperOrderSubmitStatus.SUBMITTED -> false
}

private fun PaperOrderLifecycleStatus.allowsNewPreparation(): Boolean = when (this) {
    PaperOrderLifecycleStatus.FILLED,
    PaperOrderLifecycleStatus.CANCELED,
    PaperOrderLifecycleStatus.EXPIRED,
    PaperOrderLifecycleStatus.REJECTED,
    -> true
    else -> false
}

private fun AlpacaPaperOrderStatusReadOnlyClient.FetchResult.safeStatusError(): String = when (this) {
    AlpacaPaperOrderStatusReadOnlyClient.FetchResult.AuthMissing ->
        "Paper credentials are not configured."
    AlpacaPaperOrderStatusReadOnlyClient.FetchResult.InvalidOrderId ->
        "The tracked Paper order id is invalid."
    is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.HttpError ->
        "Alpaca Paper status lookup returned HTTP $statusCode."
    AlpacaPaperOrderStatusReadOnlyClient.FetchResult.NetworkError ->
        "Alpaca Paper status lookup failed on the network; no retry was attempted."
    is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ParseError -> safeMessage
    AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ResponseIdMismatch ->
        "Alpaca Paper status response did not match the tracked order."
    is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok ->
        "Paper order status lookup completed."
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
