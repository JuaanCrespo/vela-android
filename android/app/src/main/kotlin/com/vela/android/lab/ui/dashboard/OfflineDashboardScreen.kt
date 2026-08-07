package com.vela.android.lab.ui.dashboard

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vela.android.lab.data.market.tick.PerSymbolTickStats
import com.vela.android.lab.data.market.tick.TickBufferSnapshot
import com.vela.android.lab.data.paper.RiskFlag
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.PaperTradingExecutionGuard
import com.vela.android.lab.data.paper.submit.AlpacaPaperSubmitEndpoint
import com.vela.android.lab.ui.candles.CandlesViewModel
import com.vela.android.lab.ui.navigation.VelaDestination
import com.vela.android.lab.ui.settings.VelaPreferencesViewModel
import com.vela.android.lab.ui.settings.VelaTimeFormat
import com.vela.android.lab.ui.theme.LocalVelaColors
import com.vela.android.lab.ui.theme.VelaActionZone
import com.vela.android.lab.ui.theme.VelaBlockedReasonList
import com.vela.android.lab.ui.theme.VelaEmptyState
import com.vela.android.lab.ui.theme.VelaLabTheme
import com.vela.android.lab.ui.theme.VelaMetricCard
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSafetyBanner
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDashboardScreen(
    viewModel: OfflineDashboardViewModel,
    alpacaViewModel: AlpacaTestStreamViewModel? = null,
    alpacaStockViewModel: AlpacaStockStreamViewModel? = null,
    watchlistViewModel: WatchlistViewModel? = null,
    historyViewModel: MarketHistoryViewModel? = null,
    paperAccountViewModel: PaperAccountViewModel? = null,
    paperPortfolioRiskViewModel: PaperPortfolioRiskViewModel? = null,
    paperOrderPreflightViewModel: PaperOrderPreflightViewModel? = null,
    paperOrderDryRunAuditViewModel: PaperOrderDryRunAuditViewModel? = null,
    paperOrderPayloadPreviewQueueViewModel: PaperOrderPayloadPreviewQueueViewModel? = null,
    paperManualSubmitViewModel: PaperManualSubmitViewModel? = null,
    candlesViewModel: CandlesViewModel? = null,
    preferencesViewModel: VelaPreferencesViewModel? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val alpacaState = alpacaViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val stockState = alpacaStockViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val watchlistState = watchlistViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val tickSnapshot = alpacaStockViewModel?.tickBufferRef?.snapshot?.collectAsStateWithLifecycle()?.value
    val historyState = historyViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val paperState = paperAccountViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val portfolioRiskState = paperPortfolioRiskViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val preflightState = paperOrderPreflightViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val auditState = paperOrderDryRunAuditViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val payloadPreviewQueueState =
        paperOrderPayloadPreviewQueueViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val manualSubmitState =
        paperManualSubmitViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val candlesState = candlesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
        ?: com.vela.android.lab.ui.candles.CandlesUiState()
    val preferencesState = preferencesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
        ?: com.vela.android.lab.ui.settings.VelaPreferencesState()

    var currentDestination by rememberSaveable { mutableStateOf(VelaDestination.HOME) }
    var restoredDestination by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferencesState.isLoaded) {
        if (preferencesState.isLoaded && !restoredDestination) {
            currentDestination = preferencesState.preferences.lastDestination
            restoredDestination = true
        }
    }
    LaunchedEffect(
        preferencesState.isLoaded,
        preferencesState.preferences.candleCount,
        preferencesState.preferences.defaultSymbol,
        watchlistState?.symbols,
    ) {
        if (preferencesState.isLoaded) {
            candlesViewModel?.onCandleCountSelected(
                preferencesState.preferences.candleCount.count,
            )
            candlesViewModel?.onSymbolSelected(
                preferencesState.preferences.defaultSymbolFor(
                    watchlistState?.symbols.orEmpty(),
                ),
            )
        }
    }

    val context = LocalContext.current
    val manualSessionArmed = manualSubmitState?.sessionArmed == true
    DisposableEffect(context, manualSessionArmed) {
        val window = (context as? Activity)?.window
        if (manualSessionArmed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (manualSessionArmed) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    LaunchedEffect(
        preflightState?.lastPayloadPreview?.previewId,
        preflightState?.lastResult,
        preflightState?.lastExecutionReadiness,
    ) {
        paperManualSubmitViewModel?.updateSource(
            preflight = preflightState?.lastResult,
            preview = preflightState?.lastPayloadPreview,
            readiness = preflightState?.lastExecutionReadiness,
        )
    }
    val navigate: (VelaDestination) -> Unit = { destination ->
        currentDestination = destination
        preferencesViewModel?.onDestinationSelected(destination)
    }
    VelaDashboardSections(
        currentDestination = currentDestination,
        data = VelaDashboardData(
            dashboard = state,
            alpaca = alpacaState,
            stock = stockState,
            watchlist = watchlistState,
            ticks = tickSnapshot,
            history = historyState,
            paper = paperState,
            risk = portfolioRiskState,
            preflight = preflightState,
            dryRunAudit = auditState,
            previewQueue = payloadPreviewQueueState,
            manualPaper = manualSubmitState,
            candles = candlesState,
            preferences = preferencesState,
        ),
        actions = VelaDashboardActions(
            navigate = navigate,
            generateBtc = viewModel::generateBtcUpdate,
            generateSpy = viewModel::generateSpyUpdate,
            clearDemo = viewModel::clearDemoState,
            keyIdChanged = { alpacaViewModel?.onKeyIdInputChange(it) },
            secretChanged = { alpacaViewModel?.onSecretInputChange(it) },
            saveCredentials = { alpacaViewModel?.saveCredentials() },
            clearCredentials = { alpacaViewModel?.clearCredentials() },
            startAlpacaTest = { alpacaViewModel?.startSmokeTest() },
            stopAlpacaTest = { alpacaViewModel?.stopSmokeTest() },
            startStock = {
                alpacaStockViewModel?.refresh()
                val watchlist = watchlistViewModel?.subscribeSet() ?: emptySet()
                alpacaStockViewModel?.startStream(watchlist)
            },
            stopStock = { alpacaStockViewModel?.stopStream() },
            watchlistInputChanged = { watchlistViewModel?.onAddInputChange(it) },
            watchlistAdd = { watchlistViewModel?.add() },
            watchlistRemove = { watchlistViewModel?.remove(it) },
            historyRefresh = { historyViewModel?.refresh() },
            paperRefresh = { paperAccountViewModel?.refresh() },
            riskRefresh = { paperPortfolioRiskViewModel?.refresh() },
            preflightSymbolChanged = { paperOrderPreflightViewModel?.onSymbolInputChange(it) },
            preflightSideChanged = { paperOrderPreflightViewModel?.onSideChange(it) },
            preflightQuantityChanged = { paperOrderPreflightViewModel?.onQuantityInputChange(it) },
            preflightRun = { paperOrderPreflightViewModel?.runDryRunPreflight() },
            preflightBuildDraft = { paperOrderPreflightViewModel?.buildLocalDraft() },
            preflightBuildPreview = { paperOrderPreflightViewModel?.buildPayloadPreview() },
            readinessCheck = { paperOrderPreflightViewModel?.checkExecutionReadiness() },
            disabledExecutionAttempt = { paperOrderPreflightViewModel?.attemptDisabledExecution() },
            dryRunAuditRefresh = { paperOrderDryRunAuditViewModel?.refresh() },
            previewQueueRefresh = { paperOrderPayloadPreviewQueueViewModel?.refresh() },
            manualPaperArm = { paperManualSubmitViewModel?.armSession() },
            manualPaperDisarm = { paperManualSubmitViewModel?.disarmSession() },
            manualPaperRefresh = { paperManualSubmitViewModel?.refreshSubmitReadiness() },
            manualPaperWarningChanged = { paperManualSubmitViewModel?.onWarningAcceptedChange(it) },
            manualPaperConfirmationChanged = {
                paperManualSubmitViewModel?.onConfirmationInputChange(it)
            },
            manualPaperAction = { paperManualSubmitViewModel?.submitOnce() },
            candleSymbolChanged = { candlesViewModel?.onSymbolSelected(it) },
            candleCountChanged = { candlesViewModel?.onCandleCountSelected(it) },
            candlesRefresh = { candlesViewModel?.refresh() },
            candleSelected = { candlesViewModel?.onCandleSelected(it) },
            densityChanged = { preferencesViewModel?.onDensitySelected(it) },
            defaultCandleCountChanged = { preferencesViewModel?.onCandleCountSelected(it) },
            defaultSymbolChanged = {
                preferencesViewModel?.onDefaultSymbolSelected(
                    it,
                    watchlistState?.symbols.orEmpty(),
                )
            },
            advancedDiagnosticsChanged = {
                preferencesViewModel?.onAdvancedDiagnosticsChanged(it)
            },
            rememberLastSectionChanged = {
                preferencesViewModel?.onRememberLastSectionChanged(it)
            },
            timeFormatChanged = { preferencesViewModel?.onTimeFormatSelected(it) },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDashboardContent(
    state: OfflineDashboardUiState,
    alpacaState: AlpacaTestStreamUiState? = null,
    stockState: AlpacaStockStreamUiState? = null,
    watchlistState: WatchlistUiState? = null,
    tickSnapshot: TickBufferSnapshot? = null,
    historyState: MarketHistoryUiState? = null,
    paperState: PaperAccountUiState? = null,
    portfolioRiskState: PaperPortfolioRiskUiState? = null,
    preflightState: PaperOrderPreflightUiState? = null,
    auditState: PaperOrderDryRunAuditUiState? = null,
    payloadPreviewQueueState: PaperOrderPayloadPreviewQueueUiState? = null,
    manualSubmitState: PaperManualSubmitUiState? = null,
    onGenerateBtc: () -> Unit,
    onGenerateSpy: () -> Unit,
    onClear: () -> Unit,
    onKeyIdChange: (String) -> Unit = {},
    onSecretChange: (String) -> Unit = {},
    onSaveCredentials: () -> Unit = {},
    onClearCredentials: () -> Unit = {},
    onStartAlpacaTest: () -> Unit = {},
    onStopAlpacaTest: () -> Unit = {},
    onStartStock: () -> Unit = {},
    onStopStock: () -> Unit = {},
    onWatchlistAddInputChange: (String) -> Unit = {},
    onWatchlistAdd: () -> Unit = {},
    onWatchlistRemove: (String) -> Unit = {},
    onHistoryRefresh: () -> Unit = {},
    onPaperRefresh: () -> Unit = {},
    onPortfolioRiskRefresh: () -> Unit = {},
    onPreflightSymbolInputChange: (String) -> Unit = {},
    onPreflightSideChange: (OrderSide) -> Unit = {},
    onPreflightQuantityInputChange: (String) -> Unit = {},
    onPreflightRun: () -> Unit = {},
    onPreflightBuildDraft: () -> Unit = {},
    onPreflightBuildPayloadPreview: () -> Unit = {},
    onCheckExecutionReadiness: () -> Unit = {},
    onAttemptDisabledExecution: () -> Unit = {},
    onAuditRefresh: () -> Unit = {},
    onPayloadPreviewQueueRefresh: () -> Unit = {},
    onManualSubmitArm: () -> Unit = {},
    onManualSubmitDisarm: () -> Unit = {},
    onManualSubmitRefresh: () -> Unit = {},
    onManualSubmitWarningAccepted: (Boolean) -> Unit = {},
    onManualSubmitConfirmationChange: (String) -> Unit = {},
    onManualSubmitOnce: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "VELA Android Lab") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            VelaSafetyBanner(
                modeLabel = state.modeLabel,
                realLocked = state.realLocked,
                manualSubmitCompiled = manualSubmitState?.compileTimeEnabled,
            )
            Spacer(modifier = Modifier.height(14.dp))
            VelaSectionHeader(
                title = "Estado del sistema",
                subtitle = "Read-only lab · offline demo pipeline",
            )
            Spacer(modifier = Modifier.height(6.dp))
            StatusCard(state)
            Spacer(modifier = Modifier.height(12.dp))
            PipelineCard(state)
            Spacer(modifier = Modifier.height(12.dp))
            CountersCard(state)
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ErrorCard(state.lastError)
            }
            Spacer(modifier = Modifier.height(18.dp))
            VelaSectionHeader(
                title = "Demo / diagnóstico",
                subtitle = "Local demo generators and credential inputs.",
            )
            Spacer(modifier = Modifier.height(6.dp))
            ControlsCard(
                onGenerateBtc = onGenerateBtc,
                onGenerateSpy = onGenerateSpy,
                onClear = onClear,
            )
            if (alpacaState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                AlpacaCredentialsCard(
                    state = alpacaState,
                    onKeyIdChange = onKeyIdChange,
                    onSecretChange = onSecretChange,
                    onSave = onSaveCredentials,
                    onClearCreds = onClearCredentials,
                    onTest = onStartAlpacaTest,
                    onStopTest = onStopAlpacaTest,
                )
            }
            if (stockState != null || watchlistState != null || tickSnapshot != null || historyState != null) {
                Spacer(modifier = Modifier.height(18.dp))
                VelaSectionHeader(
                    title = "Mercado",
                    subtitle = "IEX read-only stream, watchlist and recent candles.",
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (stockState != null) {
                AlpacaStockStreamCard(
                    state = stockState,
                    onStart = onStartStock,
                    onStop = onStopStock,
                )
            }
            if (watchlistState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                WatchlistCard(
                    state = watchlistState,
                    onAddInputChange = onWatchlistAddInputChange,
                    onAdd = onWatchlistAdd,
                    onRemove = onWatchlistRemove,
                )
            }
            if (tickSnapshot != null) {
                Spacer(modifier = Modifier.height(12.dp))
                TickDiagnosticsCard(snapshot = tickSnapshot)
            }
            if (historyState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                MarketHistoryCard(state = historyState, onRefresh = onHistoryRefresh)
            }
            if (paperState != null || portfolioRiskState != null) {
                Spacer(modifier = Modifier.height(18.dp))
                VelaSectionHeader(
                    title = "Paper account · Riesgo",
                    subtitle = "Read-only GETs against paper-api.alpaca.markets. No orders here.",
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (paperState != null) {
                PaperAccountCard(state = paperState, onRefresh = onPaperRefresh)
            }
            if (portfolioRiskState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                PaperPortfolioRiskCard(
                    state = portfolioRiskState,
                    onRefresh = onPortfolioRiskRefresh,
                )
            }
            if (preflightState != null) {
                Spacer(modifier = Modifier.height(18.dp))
                VelaSectionHeader(
                    title = "Paper preflight · dry-run",
                    subtitle = "Local hypothetical preflight. No order will be sent.",
                )
                Spacer(modifier = Modifier.height(6.dp))
                PaperOrderPreflightCard(
                    state = preflightState,
                    onSymbolChange = onPreflightSymbolInputChange,
                    onSideChange = onPreflightSideChange,
                    onQuantityChange = onPreflightQuantityInputChange,
                    onRun = onPreflightRun,
                    onBuildDraft = onPreflightBuildDraft,
                    onBuildPayloadPreview = onPreflightBuildPayloadPreview,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PaperExecutionReadinessCard(
                    state = preflightState,
                    onCheck = onCheckExecutionReadiness,
                    onAttemptDisabled = onAttemptDisabledExecution,
                )
            }
            if (manualSubmitState != null) {
                Spacer(modifier = Modifier.height(18.dp))
                VelaSectionHeader(
                    title = "Manual Paper submit · zona protegida",
                    subtitle = "Single one-shot mutation. Requires arm + typed confirmation.",
                    tone = if (manualSubmitState.sessionArmed) VelaPillTone.Blocked else VelaPillTone.Safe,
                    trailingPill = if (manualSubmitState.sessionArmed) "ARMED" else "SAFE",
                )
                Spacer(modifier = Modifier.height(6.dp))
                VelaActionZone(
                    title = "Manual Paper submit — one-shot",
                    subtitle = "Paper-only. One manually confirmed attempt. No LIVE, REAL, Auto Paper, retry, cancel, replace, or close-position action.",
                    armed = manualSubmitState.sessionArmed,
                ) {
                    PaperManualSubmitCard(
                        state = manualSubmitState,
                        onArm = onManualSubmitArm,
                        onDisarm = onManualSubmitDisarm,
                        onRefresh = onManualSubmitRefresh,
                        onWarningAccepted = onManualSubmitWarningAccepted,
                        onConfirmationChange = onManualSubmitConfirmationChange,
                        onSubmit = onManualSubmitOnce,
                    )
                }
            }
            if (payloadPreviewQueueState != null || auditState != null) {
                Spacer(modifier = Modifier.height(18.dp))
                VelaSectionHeader(
                    title = "Auditoría local",
                    subtitle = "Immutable append-only local logs. No network. No mutation.",
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (payloadPreviewQueueState != null) {
                PaperOrderPayloadPreviewQueueCard(
                    state = payloadPreviewQueueState,
                    onRefresh = onPayloadPreviewQueueRefresh,
                )
            }
            if (auditState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                PaperDryRunAuditCard(state = auditState, onRefresh = onAuditRefresh)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun PaperManualSubmitCard(
    state: PaperManualSubmitUiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRefresh: () -> Unit,
    onWarningAccepted: (Boolean) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Manual Paper submit — one-shot")
            Text(
                text = "Paper-only. One manually confirmed attempt. No LIVE, REAL, Auto Paper, retry, cancel, replace, or close-position action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Manual Paper submit compiled", state.compileTimeEnabled.toString())
            LabeledRow("Manual Paper submit session", if (state.sessionArmed) "ON" else "OFF")
            LabeledRow("Paper-only", state.paperOnly.toString())
            LabeledRow("REAL locked", state.realLocked.toString())
            LabeledRow("LIVE", state.liveEnabled.toString())
            LabeledRow("Auto Paper", state.autoPaperEnabled.toString())
            LabeledRow("Selected preview id", state.previewId ?: "—")
            LabeledRow("Symbol", state.symbol ?: "—")
            LabeledRow("Side", state.side ?: "—")
            LabeledRow("Qty", state.quantity?.toString() ?: "—")
            LabeledRow("Type", state.orderType ?: "—")
            LabeledRow("TIF", state.timeInForce ?: "—")
            LabeledRow("Estimated notional (USD)", formatPrice(state.estimatedNotionalUsd))
            LabeledRow("Preview price (USD)", formatPrice(state.previewPriceUsd))
            LabeledRow("Preview price source", state.priceSource ?: "—")
            LabeledRow("Preview price freshness", state.priceFreshness ?: "—")
            LabeledRow("Final/latest price (USD)", formatPrice(state.finalPriceUsd))
            LabeledRow("Final price source", state.finalPriceSource ?: "—")
            LabeledRow("Final price freshness", state.finalPriceFreshness ?: "—")
            LabeledRow("Final price age (ms)", state.finalPriceAgeMillis?.toString() ?: "—")
            LabeledRow(
                "Final price raw age (ms)",
                state.finalPriceRawAgeMillis?.toString() ?: "—",
            )
            LabeledRow(
                "Future skew tolerance applied",
                state.finalPriceFutureSkewToleranceApplied.toString(),
            )
            LabeledRow(
                "Future skew tolerance (ms)",
                state.finalPriceAllowedFutureSkewMillis.toString(),
            )
            LabeledRow("Final price drift", formatPercent(state.finalPriceDriftPercent))
            LabeledRow("Allowed drift threshold", formatPercent(state.allowedPriceDriftPercent))
            LabeledRow("Final max age (ms)", state.finalPriceMaxAgeMillis.toString())
            LabeledRow("Final price gate", state.finalPriceGateResult)
            LabeledRow("Market open", state.marketOpen?.toString() ?: "—")
            LabeledRow("Preflight", state.preflightStatus ?: "NOT_READY")
            LabeledRow("Readiness", state.readinessStatus ?: "NOT_CHECKED")
            LabeledRow("Submit gate", if (state.gateAllowed) "ALLOWED_ONCE" else "BLOCKED")
            LabeledRow("Submit method", AlpacaPaperSubmitEndpoint.METHOD)
            LabeledRow("Submit endpoint", AlpacaPaperSubmitEndpoint.ORDERS_URL)

            if (!state.compileTimeEnabled) {
                Text(
                    text = "Manual Paper submit is OFF for this build. Set the approved debug-only local flag and rebuild to make session arming available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.gateReasons.isNotEmpty()) {
                Text(
                    text = "Gate reasons: ${state.gateReasons.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (!state.sessionArmed) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onArm,
                    enabled = state.compileTimeEnabled && state.previewId != null,
                ) {
                    Text("Arm manual Paper submit for this session")
                }
            } else {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDisarm,
                    enabled = !state.isSubmitting,
                ) {
                    Text("Disarm manual Paper submit")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefresh,
                    enabled = !state.isRefreshing && !state.isSubmitting,
                ) {
                    Text(if (state.isRefreshing) "Refreshing submit gates…" else "Refresh submit gates")
                }
                if (state.preflightStatus == "WARNING_ONLY") {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onWarningAccepted(!state.warningAccepted) },
                        enabled = !state.isSubmitting,
                    ) {
                        Text(
                            if (state.warningAccepted) "Warnings acknowledged"
                            else "Acknowledge all current warnings",
                        )
                    }
                }
                Text(
                    text = "Required confirmation: ${state.requiredConfirmationText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = state.confirmationInput,
                    onValueChange = onConfirmationChange,
                    label = { Text("Type exact one-shot confirmation") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSubmitting,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSubmit,
                    enabled = state.gateAllowed && !state.isSubmitting,
                ) {
                    Text(if (state.isSubmitting) "Submitting one Paper order…" else "Submit Paper order once")
                }
            }
            state.lastResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                LabeledRow("Submit result", result.status.name)
                LabeledRow("Attempt id", result.submitAttemptId)
                LabeledRow("Client order id", result.clientOrderId)
                LabeledRow("Paper order id", result.alpacaOrderId ?: "—")
            }
            state.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun PaperExecutionReadinessCard(
    state: PaperOrderPreflightUiState,
    onCheck: () -> Unit,
    onAttemptDisabled: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Paper execution readiness — disabled")
            Text(
                text = "Execution is disabled — no order can be sent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Latest preview id", state.lastPayloadPreview?.previewId ?: "—")

            val readiness = state.lastExecutionReadiness
            LabeledRow("Readiness status", readiness?.status?.name ?: "NOT_CHECKED")
            LabeledRow("executionEnabled", readiness?.executionEnabled?.toString() ?: "false")
            LabeledRow("REAL locked", readiness?.realLocked?.toString() ?: "true")
            LabeledRow(
                "Paper POST /orders allowed",
                readiness?.paperPostOrdersAllowed?.toString() ?: "false",
            )
            LabeledRow("LIVE endpoint allowed", readiness?.liveEndpointAllowed?.toString() ?: "false")
            LabeledRow("Auto Paper", readiness?.autoPaperEnabled?.toString() ?: "false")
            LabeledRow(
                "Foreground service",
                readiness?.foregroundServiceEnabled?.toString() ?: "false",
            )
            if (readiness != null) {
                LabeledRow("Credentials configured", readiness.credentialsConfigured.toString())
                if (readiness.blockingReasons.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Reasons: ${readiness.blockingReasons.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (readiness.warnings.isNotEmpty()) {
                    Text(
                        text = "Warnings: ${readiness.warnings.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.lastExecutionReadinessError != null) {
                Text(
                    text = state.lastExecutionReadinessError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheck,
                enabled = state.lastPayloadPreview != null &&
                    !state.isCheckingExecutionReadiness,
            ) {
                Text(
                    if (state.isCheckingExecutionReadiness) "Checking…"
                    else "Check readiness",
                )
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAttemptDisabled,
                enabled = readiness != null,
            ) {
                Text("Attempt disabled execution")
            }
            val disabledResult = state.lastDisabledExecutionResult
            if (disabledResult != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LabeledRow("Disabled attempt result", disabledResult.result.name)
                Text(
                    text = disabledResult.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun PaperOrderPayloadPreviewQueueCard(
    state: PaperOrderPayloadPreviewQueueUiState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Payload review queue — local only")
            Text(
                text = "Immutable append-only local previews. No credentials, account id, " +
                    "API headers, executable endpoint, update, delete, or clear operation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Total previews", state.totalPreviews.toString())
            LabeledRow("Last refresh at", formatEpochMillis(state.lastRefreshAtEpochMillis))
            if (state.lastError != null) {
                Text(
                    text = "Queue error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.recentRows.isEmpty()) {
                Text(
                    text = "No payload previews queued yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                for (row in state.recentRows) {
                    Text(
                        text = "${row.symbol} ${row.side} ${row.quantity} → ${row.status}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${row.endpointPreview} · ${row.httpMethodPreview} · at " +
                            formatEpochMillis(row.createdAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                Text(if (state.isRefreshing) "Refreshing…" else "Refresh preview queue")
            }
        }
    }
}

@Composable
internal fun StatusCard(state: OfflineDashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Status")
            LabeledRow("Mode", state.modeLabel)
            LabeledRow("REAL locked", state.realLocked.toString())
            LabeledRow("Pipeline", state.pipelineLabel)
        }
    }
}

@Composable
internal fun PipelineCard(state: OfflineDashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Last pipeline step")
            LabeledRow("Symbol", state.lastSymbol ?: "—")
            LabeledRow("Price", formatPrice(state.lastPrice))
            LabeledRow("Bar close", formatPrice(state.lastBarClose))
            LabeledRow("Feature direction", state.lastFeatureDirection ?: "—")
            LabeledRow("Signal state", state.lastSignalState ?: "—")
            LabeledRow("Signal score", state.lastSignalScore?.toString() ?: "—")
        }
    }
}

@Composable
internal fun CountersCard(state: OfflineDashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Persistence")
            LabeledRow("Persisted bars", state.persistedBarCount.toString())
            LabeledRow("Journal events", state.journalEventCount.toString())
        }
    }
}

@Composable
internal fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Last error",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
internal fun ControlsCard(
    onGenerateBtc: () -> Unit,
    onGenerateSpy: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Demo controls")
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onGenerateBtc,
            ) {
                Text(text = "Generate demo BTC/USD update")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onGenerateSpy,
            ) {
                Text(text = "Generate demo SPY update")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClear,
            ) {
                Text(text = "Clear local demo state")
            }
        }
    }
}

@Composable
internal fun AlpacaCredentialsCard(
    state: AlpacaTestStreamUiState,
    onKeyIdChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onSave: () -> Unit,
    onClearCreds: () -> Unit,
    onTest: () -> Unit,
    onStopTest: () -> Unit,
    showCredentialEditor: Boolean = true,
    showStreamDiagnostics: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(
                if (showCredentialEditor) "Alpaca Paper Credentials" else "FAKEPACA test stream",
            )
            Text(
                text = if (showCredentialEditor) {
                    "Encrypted local credential storage. Saved values are never displayed."
                } else {
                    "Read-only test stream only. FAKEPACA; no orders, account or live endpoint."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showCredentialEditor) {
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.keyIdInput,
                onValueChange = onKeyIdChange,
                label = { Text("Key ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.secretInput,
                onValueChange = onSecretChange,
                label = { Text("Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            ) {
                Text(text = "Save credentials")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClearCreds,
            ) {
                Text(text = "Clear credentials")
            }

            if (state.lastSaveStatus != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.lastSaveStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastClearStatus != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.lastClearStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LabeledRow("Credentials configured", state.credentialsConfigured.toString())
            if (showStreamDiagnostics) {
            LabeledRow("Connection", state.connectionState)
            LabeledRow("Last bar symbol", state.lastBarSymbol ?: "—")
            LabeledRow("Last bar close", formatPrice(state.lastBarClose))
            LabeledRow("Last bar timestamp", state.lastBarTimestamp ?: "—")
            LabeledRow("Bars received", state.barsReceived.toString())
            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${state.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle("Stream diagnostics")
            LabeledRow("Health phase", state.healthPhase)
            LabeledRow("Reconnect attempts", state.reconnectAttempts.toString())
            LabeledRow("Last message at", formatEpochMillis(state.lastMessageAtEpochMillis))
            LabeledRow("Last connected at", formatEpochMillis(state.lastConnectedAtEpochMillis))
            LabeledRow("Last disconnected at", formatEpochMillis(state.lastDisconnectedAtEpochMillis))
            LabeledRow("Last error type", state.lastErrorType ?: "—")

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle("Alpaca test stream pipeline")
            LabeledRow("FAKEPACA updates received", state.pipelineReceived.toString())
            LabeledRow("FAKEPACA updates persisted", state.pipelinePersisted.toString())
            LabeledRow("Last pipeline signal", state.lastPipelineSignalState ?: "—")
            if (state.lastPipelineError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pipeline error: ${state.lastPipelineError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onTest,
            ) {
                Text(text = "Test Alpaca Market Data")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStopTest,
            ) {
                Text(text = "Stop Alpaca test stream")
            }
            }
        }
    }
}

@Composable
internal fun AlpacaStockStreamCard(
    state: AlpacaStockStreamUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Alpaca real market data — read only")
            Text(
                text = "Read-only IEX market-data feed. Subscribes to a single " +
                    "symbol via bars/quotes. No orders. No account. No live endpoint. " +
                    "No paper-api / api hosts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledRow("Feed", state.feedUrl)
            LabeledRow("Symbol", state.symbol)
            LabeledRow("Credentials configured", state.credentialsConfigured.toString())
            LabeledRow("Connection", state.connectionState)
            LabeledRow("Subscribed", state.subscribed.toString())
            LabeledRow("Bars received", state.barsReceived.toString())
            LabeledRow("Pipeline persisted", state.pipelinePersisted.toString())
            LabeledRow("Last bar symbol", state.lastBarSymbol ?: "—")
            LabeledRow("Last bar close", formatPrice(state.lastBarClose))
            LabeledRow("Last bar timestamp", state.lastBarTimestamp ?: "—")
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            SectionTitle("Stream diagnostics")
            LabeledRow("Health phase", state.healthPhase)
            LabeledRow("Reconnect attempts", state.reconnectAttempts.toString())
            LabeledRow("Last message at", formatEpochMillis(state.lastMessageAtEpochMillis))
            LabeledRow("Last connected at", formatEpochMillis(state.lastConnectedAtEpochMillis))
            LabeledRow("Last disconnected at", formatEpochMillis(state.lastDisconnectedAtEpochMillis))
            LabeledRow("Last error type", state.lastErrorType ?: "—")
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStart,
            ) {
                Text(text = "Start real market data stream")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStop,
            ) {
                Text(text = "Stop real market data stream")
            }
        }
    }
}

@Composable
internal fun WatchlistCard(
    state: WatchlistUiState,
    onAddInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Watchlist — read only")
            Text(
                text = "Routes the IEX stream to up to ${state.maxSymbols} stock symbols. " +
                    "Read-only market data. No orders. No account. No live endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            for (symbol in state.symbols) {
                val stats = state.perSymbol[symbol] ?: WatchlistSymbolStats.Initial
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "received ${stats.received} · persisted ${stats.persisted}" +
                                " · last ${formatPrice(stats.lastClose)}" +
                                " · ${stats.lastSignalState ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { onRemove(symbol) }) {
                        Text(text = "Remove")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.addInput,
                onValueChange = onAddInputChange,
                label = { Text("Add symbol") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAdd,
                enabled = state.canAddMore,
            ) {
                Text(text = if (state.canAddMore) "Add to watchlist" else "Watchlist at cap")
            }
            if (state.lastStatus != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.lastStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PaperDryRunAuditCard(
    state: PaperOrderDryRunAuditUiState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Dry-run audit — local only")
            Text(
                text = "Append-only local Room journal of every preflight dry-run. " +
                    "No credentials, no Alpaca account id, no order endpoint call. " +
                    "Append-only by design (no delete in this phase).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Total dry-runs", state.totalDryRuns.toString())
            LabeledRow("Last refresh at", formatEpochMillis(state.lastRefreshAtEpochMillis))
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.recentRows.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No dry-runs recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                for (row in state.recentRows) {
                    Text(
                        text = "${row.symbol} ${row.side} ${row.quantity} → ${row.status}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val blocks = row.blockReasonsSummary.split("\n").filter { it.isNotBlank() }.size
                    val warnings = row.warningsSummary.split("\n").filter { it.isNotBlank() }.size
                    Text(
                        text = "notional " + formatPrice(row.estimatedNotionalUsd) +
                            " · blocks $blocks · warns $warnings" +
                            " · src ${row.priceSource ?: "—"}" +
                            " · " + (row.priceFreshness ?: "—") +
                            " · at " + formatEpochMillis(row.createdAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                Text(text = if (state.isRefreshing) "Refreshing…" else "Refresh audit")
            }
        }
    }
}

@Composable
internal fun PaperOrderPreflightCard(
    state: PaperOrderPreflightUiState,
    onSymbolChange: (String) -> Unit,
    onSideChange: (OrderSide) -> Unit,
    onQuantityChange: (String) -> Unit,
    onRun: () -> Unit,
    onBuildDraft: () -> Unit,
    onBuildPayloadPreview: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Paper order preflight — dry run only")
            Text(
                text = "Local-only hypothetical preflight. " +
                    "**No order will be sent.** AlpacaHttpClient exposes only " +
                    "executeGet; no submit/cancel/replace path exists. " +
                    "canExecuteOrders = ${PaperTradingExecutionGuard.canExecuteOrders}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.symbolInput,
                onValueChange = onSymbolChange,
                label = { Text("Symbol (e.g. SPY)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onSideChange(OrderSide.BUY) },
                    enabled = state.side != OrderSide.BUY,
                    modifier = Modifier.weight(1f),
                ) { Text("BUY") }
                OutlinedButton(
                    onClick = { onSideChange(OrderSide.SELL) },
                    enabled = state.side != OrderSide.SELL,
                    modifier = Modifier.weight(1f),
                ) { Text("SELL") }
            }
            Text(
                text = "Selected side: ${state.side.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.quantityInput,
                onValueChange = onQuantityChange,
                label = { Text("Quantity") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRun,
                enabled = !state.isRunning,
            ) {
                Text(text = if (state.isRunning) "Running…" else "Run dry-run preflight")
            }
            Text(
                text = "No order will be sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.lastInputError != null) {
                Text(
                    text = state.lastInputError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val result = state.lastResult
            if (result != null) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Preflight result")
                LabeledRow("Status", result.status.name)
                LabeledRow("Symbol", result.intent.symbol)
                LabeledRow("Side", result.intent.side.name)
                LabeledRow("Qty", result.intent.quantity.toString())
                LabeledRow("Estimated notional (USD)", formatPrice(result.estimatedNotionalUsd))
                LabeledRow(
                    "Buying power after (USD)",
                    formatPrice(result.estimatedBuyingPowerAfterUsd),
                )
                LabeledRow(
                    "Allocation after (%)",
                    result.allocationPercentAfter?.let { "%.1f".format(it) } ?: "—",
                )
                LabeledRow("Position impact (qty)", result.positionImpactQty.toString())
                LabeledRow("Related signal", result.relatedSignalState ?: "—")
                LabeledRow("Market open", result.marketOpen?.toString() ?: "—")
                LabeledRow("Price source", result.priceSource ?: "—")
                LabeledRow("Price freshness", result.priceFreshness ?: "—")
                LabeledRow(
                    "Price age (ms)",
                    result.priceAgeMillis?.toString() ?: "—",
                )
                if (result.blockReasons.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionTitle("Block reasons")
                    for (reason in result.blockReasons) {
                        Text(
                            text = "BLOCK: ${reason.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionTitle("Warnings")
                    for (warning in result.warnings) {
                        Text(
                            text = "WARN: ${warning.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBuildDraft,
                    enabled = !state.isRunning,
                ) {
                    Text("Build local draft")
                }
            }
            if (state.lastDraftError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.lastDraftError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val draft = state.lastDraft
            if (draft != null) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Paper order draft — execution disabled")
                Text(
                    text = "Execution disabled — no order can be sent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LabeledRow("Draft status", draft.status.name)
                LabeledRow("Symbol", draft.symbol)
                LabeledRow("Side", draft.side.name)
                LabeledRow("Qty", draft.quantity.toString())
                LabeledRow("Type", draft.type.name)
                LabeledRow("TIF", draft.timeInForce.name)
                if (draft.limitPriceUsd != null) {
                    LabeledRow("Limit price (USD)", formatPrice(draft.limitPriceUsd))
                }
                LabeledRow("Estimated notional (USD)", formatPrice(draft.estimatedNotionalUsd))
                LabeledRow("Price source", draft.priceSource ?: "—")
                LabeledRow("Price freshness", draft.priceFreshness ?: "—")
                LabeledRow("Price age (ms)", draft.priceAgeMillis?.toString() ?: "—")
                LabeledRow("Related signal", draft.relatedSignalState ?: "—")
                LabeledRow("executionEnabled", draft.executionEnabled.toString())
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBuildPayloadPreview,
                    enabled = !state.isBuildingPayloadPreview,
                ) {
                    Text(
                        if (state.isBuildingPayloadPreview) "Building preview…"
                        else "Build payload preview",
                    )
                }
            }
            if (state.lastPayloadPreviewError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.lastPayloadPreviewError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val preview = state.lastPayloadPreview
            if (preview != null) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle("Paper order payload preview — execution disabled")
                Text(
                    text = "Preview only — no HTTP request can be sent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LabeledRow("Preview status", preview.status.name)
                LabeledRow("Preview id", preview.previewId)
                LabeledRow("Symbol", preview.symbol)
                LabeledRow("Side", preview.side.name)
                LabeledRow("Qty", preview.quantity.toString())
                LabeledRow("Type", preview.type.name)
                LabeledRow("TIF", preview.timeInForce.name)
                if (preview.limitPriceUsd != null) {
                    LabeledRow("Limit price (USD)", formatPrice(preview.limitPriceUsd))
                }
                LabeledRow("Estimated notional (USD)", formatPrice(preview.estimatedNotionalUsd))
                LabeledRow("Price source", preview.priceSource ?: "—")
                LabeledRow("Price freshness", preview.priceFreshness ?: "—")
                LabeledRow("Related signal", preview.relatedSignalState ?: "—")
                LabeledRow("payload.symbol", preview.payloadFields.symbol)
                LabeledRow("payload.side", preview.payloadFields.side)
                LabeledRow("payload.type", preview.payloadFields.type)
                LabeledRow("payload.time_in_force", preview.payloadFields.timeInForce)
                LabeledRow("payload.qty", preview.payloadFields.quantity.toString())
                LabeledRow("endpointPreview", preview.endpointPreview)
                LabeledRow("httpMethodPreview", preview.httpMethodPreview)
                LabeledRow("executionEnabled", preview.executionEnabled.toString())
            }
        }
    }
}

@Composable
internal fun PaperPortfolioRiskCard(
    state: PaperPortfolioRiskUiState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Paper portfolio risk — read only")
            Text(
                text = "Aggregated read-only view of paper account + positions + " +
                    "watchlist + local signals. Risk flags are informational only — " +
                    "no orders, no trading, no mutation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Credentials configured", state.credentialsConfigured.toString())
            LabeledRow("Equity (USD)", formatPrice(state.portfolio.equityUsd))
            LabeledRow("Cash (USD)", formatPrice(state.portfolio.cashUsd))
            LabeledRow("Buying power (USD)", formatPrice(state.portfolio.buyingPowerUsd))
            LabeledRow("Gross market value (USD)", formatPrice(state.portfolio.grossMarketValueUsd))
            LabeledRow("Positions count", state.portfolio.positionsCount.toString())
            LabeledRow("Market open", state.portfolio.marketOpen?.toString() ?: "—")
            LabeledRow("Risk flags", state.flags.size.toString())
            LabeledRow("Last refresh at", formatEpochMillis(state.lastRefreshAtEpochMillis))
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.exposures.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SectionTitle("Per-symbol exposure (read-only)")
                for (row in state.exposures) {
                    Text(
                        text = row.symbol,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "qty ${row.qty} · mv ${formatPrice(row.marketValueUsd)} " +
                            "· pnl ${formatPrice(row.unrealizedPlUsd)} " +
                            "· alloc %.1f%% ".format(row.allocationPercent) +
                            "· wl ${row.inWatchlist}" +
                            " · sig ${row.latestSignalState ?: "—"}" +
                            " · close ${formatPrice(row.latestLocalClose)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            if (state.flags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SectionTitle("Risk flags (informational)")
                for (flag in state.flags) {
                    val color = if (flag.severity == RiskFlag.Severity.WARN) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "[${flag.severity.name}] ${flag.code.name}: ${flag.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                Text(text = if (state.isRefreshing) "Refreshing…" else "Refresh portfolio risk")
            }
        }
    }
}

@Composable
internal fun PaperAccountCard(
    state: PaperAccountUiState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Alpaca Paper account — read only")
            Text(
                text = "Read-only GET against paper-api.alpaca.markets/v2/" +
                    "{account, clock, positions}. No orders. No mutation. " +
                    "No LIVE endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Credentials configured", state.credentialsConfigured.toString())
            LabeledRow("Market open", state.marketOpen?.toString() ?: "—")
            LabeledRow("Next open", state.nextOpenIso ?: "—")
            LabeledRow("Next close", state.nextCloseIso ?: "—")
            LabeledRow("Equity (USD)", formatPrice(state.equityUsd))
            LabeledRow("Buying power (USD)", formatPrice(state.buyingPowerUsd))
            LabeledRow("Cash (USD)", formatPrice(state.cashUsd))
            LabeledRow("Portfolio value (USD)", formatPrice(state.portfolioValueUsd))
            LabeledRow("Trading blocked", state.tradingBlocked?.toString() ?: "—")
            LabeledRow("Account blocked", state.accountBlocked?.toString() ?: "—")
            LabeledRow("Pattern day trader", state.patternDayTrader?.toString() ?: "—")
            LabeledRow("Account status", state.accountStatus ?: "—")
            LabeledRow("Positions count", state.positionsCount?.toString() ?: "—")
            LabeledRow("Last refresh at", formatEpochMillis(state.lastRefreshAtEpochMillis))
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.topPositions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SectionTitle("Top positions (read-only)")
                for (row in state.topPositions) {
                    Text(
                        text = "${row.symbol}: qty ${row.qty} · mv " +
                            formatPrice(row.marketValueUsd) +
                            " · pnl " + formatPrice(row.unrealizedPlUsd),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                Text(text = if (state.isRefreshing) "Refreshing…" else "Refresh Paper Account")
            }
        }
    }
}

@Composable
internal fun MarketHistoryCard(
    state: MarketHistoryUiState,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Recent market data — read only")
            Text(
                text = "Read-only snapshot of the persisted Room database. " +
                    "No network. No orders. No account. Tap Refresh to re-query.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledRow("Total persisted bars", state.totalPersistedBars.toString())
            LabeledRow("Total journal events", state.totalJournalEvents.toString())
            LabeledRow("Last refresh at", formatEpochMillis(state.lastRefreshAtEpochMillis))
            if (state.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Refresh error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (state.symbols.isEmpty()) {
                Text(
                    text = "Watchlist is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (symbol in state.symbols) {
                    val row = state.perSymbol[symbol] ?: PerSymbolHistory.empty(symbol)
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "close ${formatPrice(row.latestBarClose)}" +
                            " · bars ${row.recentBarCount}" +
                            " · dir ${row.latestFeatureDirection ?: "—"}" +
                            " · sig ${row.latestSignalState ?: "—"}" +
                            (row.latestSignalScore?.let { " ($it)" } ?: "") +
                            " · jrnl ${row.journalEventCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !state.isRefreshing,
            ) {
                Text(text = if (state.isRefreshing) "Refreshing…" else "Refresh")
            }
        }
    }
}

@Composable
internal fun TickDiagnosticsCard(snapshot: TickBufferSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Tick / quote diagnostics",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                VelaStatusPill(
                    label = "IEX · READ ONLY",
                    tone = VelaPillTone.Safe,
                )
            }
            Text(
                text = "Cotizaciones IEX de solo lectura, separadas por precio, " +
                    "actividad y salud del buffer. Sin ordenes ni API de trading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Resumen del flujo", style = MaterialTheme.typography.titleSmall)
            TickSummaryGrid(snapshot)
            Text(
                text = "El buffer conserva las muestras más recientes. Los ticks " +
                    "antiguos rotados no representan errores de red.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.lastParserError != null) {
                Text(
                    text = "Error de parser: ${snapshot.lastParserError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                VelaStatusPill(
                    label = "Parser OK",
                    tone = VelaPillTone.Safe,
                )
            }
            Text("Cotización por símbolo", style = MaterialTheme.typography.titleSmall)
            if (snapshot.perSymbol.isEmpty()) {
                VelaEmptyState(
                    title = "Sin cotizaciones todavía",
                    message = "Cuando llegue el primer tick IEX, sus métricas aparecerán aquí.",
                )
            } else {
                val sorted = snapshot.perSymbol.toSortedMap()
                for ((sym, stats) in sorted) {
                    TickSymbolDiagnosticsCard(
                        symbol = sym,
                        stats = stats,
                    )
                }
            }
        }
    }
}

@Composable
private fun TickSummaryGrid(snapshot: TickBufferSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VelaMetricCard(
            label = "Cotizaciones",
            value = formatCount(snapshot.totalQuotes),
            modifier = Modifier.weight(1f),
            tone = VelaPillTone.Safe,
        )
        VelaMetricCard(
            label = "Barras",
            value = formatCount(snapshot.totalBars),
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VelaMetricCard(
            label = "Ticks en memoria",
            value = formatCount(snapshot.bufferSize),
            modifier = Modifier.weight(1f),
        )
        VelaMetricCard(
            label = "Historial rotado",
            value = formatCount(snapshot.droppedOverflow),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TickSymbolDiagnosticsCard(
    symbol: String,
    stats: PerSymbolTickStats,
) {
    val hasQuote = stats.quotesReceived > 0 && stats.lastReceivedAtMillis > 0L
    val bid = stats.lastBid.takeIf { hasQuote && it > 0.0 }
    val ask = stats.lastAsk.takeIf { hasQuote && it > 0.0 }
    val midpoint = if (bid != null && ask != null) (bid + ask) / 2.0 else null
    val spread = stats.spread.takeIf { hasQuote }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = symbol,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                VelaStatusPill(
                    label = "Cotizaciones ${formatCount(stats.quotesReceived)}",
                    tone = VelaPillTone.Safe,
                )
            }
            TickMetricPair(
                firstLabel = "Bid",
                firstValue = formatPrice(bid),
                secondLabel = "Ask",
                secondValue = formatPrice(ask),
                firstTone = VelaPillTone.Safe,
                secondTone = VelaPillTone.Safe,
            )
            TickMetricPair(
                firstLabel = "Punto medio",
                firstValue = formatPrice(midpoint),
                secondLabel = "Spread",
                secondValue = formatPrice(spread),
                firstTone = VelaPillTone.Safe,
            )
            DiagnosticDetailRow(
                label = "Latencia mercado a dispositivo",
                value = if (hasQuote) "${stats.lastLatencyMillis} ms" else "—",
            )
            DiagnosticDetailRow(
                label = "Intervalo entre mensajes",
                value = stats.lastInterMessageMillis?.let { "$it ms" } ?: "—",
            )
            DiagnosticDetailRow(
                label = "Timestamp de mercado",
                value = formatEpochMillis(stats.lastQuoteTimestampMillis.takeIf { it > 0L }),
            )
            DiagnosticDetailRow(
                label = "Recibida por VELA",
                value = formatEpochMillis(stats.lastReceivedAtMillis.takeIf { it > 0L }),
            )
            DiagnosticDetailRow(
                label = "Barras recibidas",
                value = formatCount(stats.barsReceived),
            )
        }
    }
}

@Composable
private fun TickMetricPair(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
    firstTone: VelaPillTone = VelaPillTone.Neutral,
    secondTone: VelaPillTone = VelaPillTone.Neutral,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VelaMetricCard(
            label = firstLabel,
            value = firstValue,
            modifier = Modifier.weight(1f),
            tone = firstTone,
        )
        VelaMetricCard(
            label = secondLabel,
            value = secondValue,
            modifier = Modifier.weight(1f),
            tone = secondTone,
        )
    }
}

@Composable
private fun DiagnosticDetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatCount(value: Int): String = String.format(Locale.ROOT, "%,d", value)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatPrice(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

private fun formatPercent(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.4f%%", it) } ?: "—"

private fun formatEpochMillis(value: Long?): String =
    value?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "—"

/** Preview helper. Not part of the test surface. */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun OfflineDashboardPreview() {
    VelaLabTheme {
        OfflineDashboardContent(
            state = OfflineDashboardUiState.Initial.copy(
                lastSymbol = "BTC/USD",
                lastPrice = 50_005.0,
                lastBarClose = 50_005.0,
                lastFeatureDirection = "flat",
                lastSignalState = "NEUTRAL",
                lastSignalScore = 0,
                persistedBarCount = 1,
                journalEventCount = 4,
            ),
            alpacaState = AlpacaTestStreamUiState.Initial,
            onGenerateBtc = {},
            onGenerateSpy = {},
            onClear = {},
        )
    }
}
