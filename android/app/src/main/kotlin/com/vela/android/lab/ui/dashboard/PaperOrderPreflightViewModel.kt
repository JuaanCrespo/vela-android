package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.PaperAccountSnapshot
import com.vela.android.lab.data.paper.PaperClockSnapshot
import com.vela.android.lab.data.paper.PaperPositionSnapshot
import com.vela.android.lab.data.paper.preflight.IntentSource
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperDisabledOrderExecutor
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessChecker
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessSnapshot
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderIntent
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewBuilder
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewValidation
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraft
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftBuilder
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftValidation
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import com.vela.android.lab.data.paper.preflight.TimeInForce
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.state.AppState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2.m read-only Paper order **preflight** ViewModel.
 *
 * The class:
 *  - owns the symbol / side / quantity input state;
 *  - builds a [PaperOrderIntent] on demand;
 *  - asks the existing [AlpacaPaperReadOnlyClient] for fresh account
 *    + clock + positions (the same three GETs already locked down);
 *  - runs [PaperOrderPreflightEngine.preflight] locally;
 *  - exposes the [PaperOrderPreflightResult] on UI state.
 *
 * **It does not submit, cancel, replace, or close any order. The
 * Phase 2.m reflection contract test enforces it.**
 *
 * The Refresh action here does not require the user to have first
 * refreshed the paper account card — the VM fetches snapshots
 * itself on each dry-run.
 */
class PaperOrderPreflightViewModel(
    private val engine: PaperOrderPreflightEngine,
    private val client: AlpacaPaperReadOnlyClient,
    private val credentialsStore: SecureAlpacaCredentialsStore,
    private val watchlistRepository: WatchlistRepository,
    private val marketDataRepository: MarketDataRepository,
    private val signalRepository: SignalRepository,
    private val appState: AppState,
    private val auditRepository: PaperOrderDryRunAuditRepository? = null,
    private val onAuditSaved: (suspend () -> Unit)? = null,
    private val priceSnapshotProvider: MarketPriceSnapshotProvider? = null,
    private val payloadPreviewRepository: PaperOrderPayloadPreviewRepository? = null,
    private val onPayloadPreviewSaved: (suspend () -> Unit)? = null,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {

    /** Pure local-only Phase 2.p builder; it has no injected dependencies. */
    private val draftBuilder: PaperOrderRequestDraftBuilder = PaperOrderRequestDraftBuilder()
    private val payloadPreviewBuilder: PaperOrderPayloadPreviewBuilder =
        PaperOrderPayloadPreviewBuilder()
    private val executionReadinessChecker: PaperExecutionReadinessChecker =
        PaperExecutionReadinessChecker()
    private val disabledOrderExecutor: PaperDisabledOrderExecutor =
        PaperDisabledOrderExecutor()

    private val _uiState: MutableStateFlow<PaperOrderPreflightUiState> =
        MutableStateFlow(PaperOrderPreflightUiState.Initial)

    val uiState: StateFlow<PaperOrderPreflightUiState> = _uiState.asStateFlow()

    private data class DryRunOutcome(
        val result: PaperOrderPreflightResult,
        val paperReadOnlyInputsComplete: Boolean,
        val auditPersisted: Boolean,
    )

    fun onSymbolInputChange(value: String) {
        if (_uiState.value.isGuidedPreparationRunning) return
        _uiState.update {
            it.copy(
                symbolInput = value,
                lastInputError = null,
                lastResult = null,
                lastDraft = null,
                lastDraftError = null,
                isBuildingPayloadPreview = false,
                lastPayloadPreview = null,
                lastPayloadPreviewError = null,
                isCheckingExecutionReadiness = false,
                lastExecutionReadiness = null,
                lastExecutionReadinessError = null,
                lastDisabledExecutionResult = null,
                guidedPreparationStage = PaperGuidedPreparationStage.IDLE,
                guidedPreparationError = null,
            )
        }
    }

    fun onQuantityInputChange(value: String) {
        if (_uiState.value.isGuidedPreparationRunning) return
        _uiState.update {
            it.copy(
                quantityInput = value,
                lastInputError = null,
                lastResult = null,
                lastDraft = null,
                lastDraftError = null,
                isBuildingPayloadPreview = false,
                lastPayloadPreview = null,
                lastPayloadPreviewError = null,
                isCheckingExecutionReadiness = false,
                lastExecutionReadiness = null,
                lastExecutionReadinessError = null,
                lastDisabledExecutionResult = null,
                guidedPreparationStage = PaperGuidedPreparationStage.IDLE,
                guidedPreparationError = null,
            )
        }
    }

    fun onSideChange(side: OrderSide) {
        if (_uiState.value.isGuidedPreparationRunning) return
        _uiState.update {
            it.copy(
                side = side,
                lastInputError = null,
                lastResult = null,
                lastDraft = null,
                lastDraftError = null,
                isBuildingPayloadPreview = false,
                lastPayloadPreview = null,
                lastPayloadPreviewError = null,
                isCheckingExecutionReadiness = false,
                lastExecutionReadiness = null,
                lastExecutionReadinessError = null,
                lastDisabledExecutionResult = null,
                guidedPreparationStage = PaperGuidedPreparationStage.IDLE,
                guidedPreparationError = null,
            )
        }
    }

    /**
     * Returns the local preparation surface to its safe starting state without
     * changing the operator's order form. Persisted dry-run/preview audit rows
     * are append-only and are deliberately not touched here.
     */
    fun canResetForNewPreparation(): Boolean {
        val current = _uiState.value
        return listOf(
            current.isGuidedPreparationRunning,
            current.isRunning,
            current.isBuildingPayloadPreview,
            current.isCheckingExecutionReadiness,
        ).none { it }
    }

    fun resetForNewPreparation(): Boolean {
        val current = _uiState.value
        if (!canResetForNewPreparation()) return false

        _uiState.value = PaperOrderPreflightUiState.Initial.copy(
            symbolInput = current.symbolInput,
            side = current.side,
            quantityInput = current.quantityInput,
        )
        return true
    }

    /** Runs the existing read-only/local chain and stops before manual session arming. */
    fun prepareGuidedLocalChain() {
        val current = _uiState.value
        val busy = listOf(
            current.isGuidedPreparationRunning,
            current.isRunning,
            current.isBuildingPayloadPreview,
            current.isCheckingExecutionReadiness,
        ).any { it }
        if (busy) return

        val quantity = current.quantityInput.trim().toDoubleOrNull()
        val symbol = current.symbolInput.trim()
        if (quantity == null) {
            blockGuidedPreparation("Ingresá una cantidad válida.")
            _uiState.update { it.copy(lastInputError = "Ingresá una cantidad válida.") }
            return
        }
        if (symbol.isEmpty()) {
            blockGuidedPreparation("Ingresá un símbolo.")
            _uiState.update { it.copy(lastInputError = "Ingresá un símbolo.") }
            return
        }
        _uiState.update {
            it.copy(
                isGuidedPreparationRunning = true,
                guidedPreparationStage = PaperGuidedPreparationStage.PREFLIGHT,
                guidedPreparationError = null,
                lastInputError = null,
            )
        }
        viewModelScope.launch { runGuidedPreparation(symbol, quantity, current.side) }
    }

    private suspend fun runGuidedPreparation(
        symbol: String,
        quantity: Double,
        side: OrderSide,
    ) {
        try {
            val outcome = runDryRunInternal(symbol, quantity, side)
            val preflightResult = outcome.result
            val preflightFailed = listOf(
                !outcome.paperReadOnlyInputsComplete,
                !outcome.auditPersisted,
                preflightResult.status != PreflightStatus.ALLOWED_DRY_RUN,
                preflightResult.marketOpen != true,
                preflightResult.priceSource != MarketPriceSource.LIVE_QUOTE_MID.name,
                preflightResult.priceFreshness != PriceFreshness.FRESH.name,
                preflightResult.blockReasons.isNotEmpty(),
                preflightResult.warnings.isNotEmpty(),
                _uiState.value.lastAuditError != null,
            ).any { it }
            if (preflightFailed) {
                blockGuidedPreparation("La verificación de cuenta, mercado o precio no pasó.")
                return
            }
            _uiState.update {
                it.copy(guidedPreparationStage = PaperGuidedPreparationStage.DRAFT)
            }
            val draft = buildLocalDraftInternal()
            if (draft?.status != PaperOrderRequestDraftStatus.READY_LOCAL ||
                _uiState.value.lastDraftError != null
            ) {
                blockGuidedPreparation("No se pudo preparar el borrador local.")
                return
            }
            continueGuidedPreparation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            blockGuidedPreparation("La preparación se detuvo por un error interno.")
        }
    }

    private suspend fun continueGuidedPreparation() {
        _uiState.update {
            it.copy(guidedPreparationStage = PaperGuidedPreparationStage.PREVIEW)
        }
        val preview = buildPayloadPreviewAndWait()
        val previewPersisted = preview?.let { previewWasPersisted(it) } == true
        val previewFailed = listOf(
            preview?.status != PaperOrderPayloadPreviewStatus.READY_PREVIEW,
            !previewPersisted,
            _uiState.value.lastPayloadPreviewError != null,
            _uiState.value.isBuildingPayloadPreview,
        ).any { it }
        if (preview == null || previewFailed) {
            blockGuidedPreparation("No se pudo guardar el resumen local.")
            return
        }
        _uiState.update {
            it.copy(guidedPreparationStage = PaperGuidedPreparationStage.READINESS)
        }
        val readiness = checkExecutionReadinessAndWait()
        if (!readinessIsSafelyDisabled(readiness, preview)) {
            blockGuidedPreparation(
                "El control final de seguridad no quedó listo.",
            )
            return
        }
        _uiState.update {
            it.copy(
                isGuidedPreparationRunning = false,
                guidedPreparationStage = PaperGuidedPreparationStage.READY,
                guidedPreparationError = null,
            )
        }
    }

    private suspend fun previewWasPersisted(preview: PaperOrderPayloadPreview): Boolean {
        val row = payloadPreviewRepository?.byPreviewId(preview.previewId) ?: return false
        return listOf(
            row.previewId == preview.previewId,
            row.linkedClientDryRunId == preview.linkedClientDryRunId,
            row.symbol == preview.symbol,
            row.side == preview.side.name,
            row.orderType == preview.type.name,
            row.timeInForce == preview.timeInForce.name,
            row.quantity == preview.quantity,
            !row.executionEnabled,
            row.endpointPreview == PaperOrderPayloadPreview.ENDPOINT_DISABLED,
            row.httpMethodPreview == PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED,
        ).all { it }
    }

    private fun readinessIsSafelyDisabled(
        readiness: PaperExecutionReadinessSnapshot?,
        preview: PaperOrderPayloadPreview,
    ): Boolean = readiness != null &&
        readiness.previewId == preview.previewId &&
        readiness.linkedClientDryRunId == preview.linkedClientDryRunId &&
        readiness.status == PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED &&
        readiness.hasValidPreview && !readiness.executionEnabled && readiness.realLocked &&
        !readiness.paperPostOrdersAllowed && !readiness.liveEndpointAllowed &&
        readiness.autoPaperEnabled == false &&
        readiness.foregroundServiceEnabled == false &&
        readiness.credentialsConfigured

    private fun blockGuidedPreparation(message: String) {
        _uiState.update {
            it.copy(
                isGuidedPreparationRunning = false,
                isRunning = false,
                isBuildingPayloadPreview = false,
                isCheckingExecutionReadiness = false,
                guidedPreparationStage = PaperGuidedPreparationStage.BLOCKED,
                guidedPreparationError = message,
            )
        }
    }

    /**
     * Convert the latest approved preflight into an in-memory draft.
     * This method performs no I/O and has no order execution path.
     */
    fun buildLocalDraft() {
        if (_uiState.value.isGuidedPreparationRunning) return
        clearGuidedPreparationResult()
        buildLocalDraftInternal()
    }

    private fun buildLocalDraftInternal(): PaperOrderRequestDraft? {
        val result = _uiState.value.lastResult
        if (result == null) {
            _uiState.update {
                it.copy(
                    lastDraft = null,
                    lastDraftError = "Run dry-run preflight before building a local draft.",
                )
            }
            return null
        }
        return when (val validation = draftBuilder.build(result)) {
            is PaperOrderRequestDraftValidation.Valid -> {
                _uiState.update { it.copy(
                    lastDraft = validation.draft,
                    lastDraftError = null,
                    isBuildingPayloadPreview = false,
                    lastPayloadPreview = null,
                    lastPayloadPreviewError = null,
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError = null,
                    lastDisabledExecutionResult = null,
                ) }
                validation.draft
            }
            is PaperOrderRequestDraftValidation.Rejected -> {
                _uiState.update { it.copy(
                    lastDraft = null,
                    lastDraftError = validation.message,
                    isBuildingPayloadPreview = false,
                    lastPayloadPreview = null,
                    lastPayloadPreviewError = null,
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError = null,
                    lastDisabledExecutionResult = null,
                ) }
                null
            }
        }
    }

    /**
     * Build and append one local payload preview to the immutable
     * review queue. No HTTP request is created or sent.
     */
    fun buildPayloadPreview() {
        if (_uiState.value.isGuidedPreparationRunning) return
        clearGuidedPreparationResult()
        viewModelScope.launch { buildPayloadPreviewAndWait() }
    }

    private suspend fun buildPayloadPreviewAndWait(): PaperOrderPayloadPreview? {
        val current = _uiState.value
        if (current.isBuildingPayloadPreview) return null
        val draft = current.lastDraft
        if (draft == null) {
            _uiState.update {
                it.copy(
                    lastPayloadPreview = null,
                    lastPayloadPreviewError = "Build a local draft before building a payload preview.",
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError = null,
                    lastDisabledExecutionResult = null,
                )
            }
            return null
        }
        return when (val validation = payloadPreviewBuilder.build(draft)) {
            is PaperOrderPayloadPreviewValidation.Rejected -> {
                _uiState.update { it.copy(
                    isBuildingPayloadPreview = false,
                    lastPayloadPreview = null,
                    lastPayloadPreviewError = validation.message,
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError = null,
                    lastDisabledExecutionResult = null,
                ) }
                null
            }
            is PaperOrderPayloadPreviewValidation.Valid -> {
                val preview = validation.preview
                _uiState.update {
                    it.copy(
                        isBuildingPayloadPreview = true,
                        lastPayloadPreview = preview,
                        lastPayloadPreviewError = null,
                        isCheckingExecutionReadiness = false,
                        lastExecutionReadiness = null,
                        lastExecutionReadinessError = null,
                        lastDisabledExecutionResult = null,
                    )
                }
                val queueError: String? = try {
                    if (payloadPreviewRepository != null) {
                        payloadPreviewRepository.savePreview(preview)
                        onPayloadPreviewSaved?.invoke()
                    }
                    null
                } catch (exc: Exception) {
                    exc.message ?: exc::class.simpleName ?: "Review queue save failed"
                }
                _uiState.update {
                    it.copy(
                        isBuildingPayloadPreview = false,
                        lastPayloadPreview = preview,
                        lastPayloadPreviewError = queueError,
                    )
                }
                preview
            }
        }
    }

    /** Run the local Phase 2.r readiness gate. No network call is made. */
    fun checkExecutionReadiness() {
        if (_uiState.value.isGuidedPreparationRunning) return
        clearGuidedPreparationResult()
        viewModelScope.launch { checkExecutionReadinessAndWait() }
    }

    private suspend fun checkExecutionReadinessAndWait(): PaperExecutionReadinessSnapshot? {
        val preview = _uiState.value.lastPayloadPreview
        if (preview == null) {
            _uiState.update {
                it.copy(
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError =
                        "Build a payload preview before checking execution readiness.",
                    lastDisabledExecutionResult = null,
                )
            }
            return null
        }
        _uiState.update {
            it.copy(
                isCheckingExecutionReadiness = true,
                lastExecutionReadinessError = null,
                lastDisabledExecutionResult = null,
            )
        }
        val credentialsConfigured = credentialsStore.hasCredentials()
        val snapshot = executionReadinessChecker.check(
            preview = preview,
            realLocked = appState.realModeLocked,
            credentialsConfigured = credentialsConfigured,
        )
        _uiState.update {
            it.copy(
                isCheckingExecutionReadiness = false,
                lastExecutionReadiness = snapshot,
                lastExecutionReadinessError = null,
            )
        }
        return snapshot
    }

    /**
     * Exercise the disabled local surface. The result is always
     * EXECUTION_DISABLED and no HTTP object or request is created.
     */
    fun attemptDisabledExecution() {
        if (_uiState.value.isGuidedPreparationRunning) return
        clearGuidedPreparationResult()
        val preview = _uiState.value.lastPayloadPreview
        if (preview == null) {
            _uiState.update {
                it.copy(
                    lastDisabledExecutionResult = null,
                    lastExecutionReadinessError =
                        "Build a payload preview before attempting the disabled surface.",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                lastDisabledExecutionResult =
                    disabledOrderExecutor.attemptDisabledExecution(preview),
                lastExecutionReadinessError = null,
            )
        }
    }

    /**
     * Build a dry-run intent from the current input and run the
     * preflight engine. **Never sends an order.** Network calls are
     * limited to the existing read-only Paper GETs.
     */
    fun runDryRunPreflight() {
        val current = _uiState.value
        if (current.isGuidedPreparationRunning) return
        clearGuidedPreparationResult()
        val qty = current.quantityInput.trim().toDoubleOrNull()
        if (qty == null) {
            _uiState.update {
                it.copy(lastInputError = "Quantity must be a number.")
            }
            return
        }
        val symbol = current.symbolInput.trim()
        if (symbol.isEmpty()) {
            _uiState.update { it.copy(lastInputError = "Symbol is required.") }
            return
        }
        viewModelScope.launch { runDryRunInternal(symbol, qty, current.side) }
    }

    private suspend fun runDryRunInternal(
        symbol: String,
        qty: Double,
        side: OrderSide,
    ): DryRunOutcome {
        _uiState.update {
            it.copy(
                isRunning = true,
                lastInputError = null,
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
        val credentialsConfigured = credentialsStore.hasCredentials()
        val accountFetch = client.fetchAccount()
        val clockFetch = client.fetchClock()
        val positionsFetch = client.fetchPositions()
        val account = (accountFetch as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperAccountSnapshot>)?.value
        val clockSnap = (clockFetch as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperClockSnapshot>)?.value
        val positions = (positionsFetch as? AlpacaPaperReadOnlyClient.FetchResult.Ok<List<PaperPositionSnapshot>>)?.value
            ?: emptyList()
        val paperReadOnlyInputsComplete = listOf(
            account != null,
            account?.status == "ACTIVE",
            account?.tradingBlocked == false,
            account?.accountBlocked == false,
            clockSnap != null,
            positionsFetch is AlpacaPaperReadOnlyClient.FetchResult.Ok<*>,
        ).all { it }
        val watchlist = watchlistRepository.load().toSet()
        val latestBar = marketDataRepository.recentBars(symbol.uppercase(), 1).lastOrNull()
        val latestSignal = signalRepository.latestFor(symbol.uppercase())?.state?.value
        val priceSnapshot: MarketPriceSnapshot? = priceSnapshotProvider?.snapshotFor(symbol)

        val intent = PaperOrderIntent(
            symbol = symbol,
            side = side,
            quantity = qty,
            type = OrderType.MARKET,
            tif = TimeInForce.DAY,
            source = IntentSource.MANUAL_DRY_RUN,
            createdAtEpochMillis = clock().toEpochMilli(),
            clientDryRunId = UUID.randomUUID().toString(),
        )
        val result = engine.preflight(
            intent = intent,
            account = account,
            clockSnap = clockSnap,
            positions = positions,
            latestLocalClose = latestBar?.close,
            latestSignalState = latestSignal,
            watchlist = watchlist,
            appState = appState,
            credentialsConfigured = credentialsConfigured,
            priceSnapshot = priceSnapshot,
        )
        var auditPersisted = false
        val auditError: String? = try {
            auditRepository?.let { repository ->
                repository.saveDryRun(result)
                auditPersisted = true
                onAuditSaved?.invoke()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exc: Exception) {
            exc.message ?: exc::class.simpleName ?: "Audit save failed"
        }

        _uiState.update {
            it.copy(
                isRunning = false,
                lastResult = result,
                lastInputError = null,
                lastAuditError = auditError,
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
        return DryRunOutcome(
            result = result,
            paperReadOnlyInputsComplete = paperReadOnlyInputsComplete,
            auditPersisted = auditPersisted,
        )
    }

    private fun clearGuidedPreparationResult() {
        _uiState.update {
            it.copy(
                guidedPreparationStage = PaperGuidedPreparationStage.IDLE,
                guidedPreparationError = null,
            )
        }
    }
}
