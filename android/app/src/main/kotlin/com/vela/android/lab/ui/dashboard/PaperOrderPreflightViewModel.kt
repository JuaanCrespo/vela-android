package com.vela.android.lab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
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
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderIntent
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewBuilder
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewValidation
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftBuilder
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftValidation
import com.vela.android.lab.data.paper.preflight.TimeInForce
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.state.AppState
import java.time.Instant
import java.util.UUID
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

    fun onSymbolInputChange(value: String) {
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
            )
        }
    }

    fun onQuantityInputChange(value: String) {
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
            )
        }
    }

    fun onSideChange(side: OrderSide) {
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
            )
        }
    }

    /**
     * Convert the latest approved preflight into an in-memory draft.
     * This method performs no I/O and has no order execution path.
     */
    fun buildLocalDraft() {
        val result = _uiState.value.lastResult
        if (result == null) {
            _uiState.update {
                it.copy(
                    lastDraft = null,
                    lastDraftError = "Run dry-run preflight before building a local draft.",
                )
            }
            return
        }
        when (val validation = draftBuilder.build(result)) {
            is PaperOrderRequestDraftValidation.Valid -> _uiState.update {
                it.copy(
                    lastDraft = validation.draft,
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
            is PaperOrderRequestDraftValidation.Rejected -> _uiState.update {
                it.copy(
                    lastDraft = null,
                    lastDraftError = validation.message,
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
    }

    /**
     * Build and append one local payload preview to the immutable
     * review queue. No HTTP request is created or sent.
     */
    fun buildPayloadPreview() {
        val current = _uiState.value
        if (current.isBuildingPayloadPreview) return
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
            return
        }
        when (val validation = payloadPreviewBuilder.build(draft)) {
            is PaperOrderPayloadPreviewValidation.Rejected -> _uiState.update {
                it.copy(
                    isBuildingPayloadPreview = false,
                    lastPayloadPreview = null,
                    lastPayloadPreviewError = validation.message,
                    isCheckingExecutionReadiness = false,
                    lastExecutionReadiness = null,
                    lastExecutionReadinessError = null,
                    lastDisabledExecutionResult = null,
                )
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
                viewModelScope.launch {
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
                }
            }
        }
    }

    /** Run the local Phase 2.r readiness gate. No network call is made. */
    fun checkExecutionReadiness() {
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
            return
        }
        _uiState.update {
            it.copy(
                isCheckingExecutionReadiness = true,
                lastExecutionReadinessError = null,
                lastDisabledExecutionResult = null,
            )
        }
        viewModelScope.launch {
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
        }
    }

    /**
     * Exercise the disabled local surface. The result is always
     * EXECUTION_DISABLED and no HTTP object or request is created.
     */
    fun attemptDisabledExecution() {
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
    ) {
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
        val account = (client.fetchAccount() as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperAccountSnapshot>)?.value
        val clockSnap = (client.fetchClock() as? AlpacaPaperReadOnlyClient.FetchResult.Ok<PaperClockSnapshot>)?.value
        val positions = (client.fetchPositions() as? AlpacaPaperReadOnlyClient.FetchResult.Ok<List<PaperPositionSnapshot>>)?.value
            ?: emptyList()
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
        val auditError: String? = try {
            auditRepository?.saveDryRun(result)
            onAuditSaved?.invoke()
            null
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
    }
}
