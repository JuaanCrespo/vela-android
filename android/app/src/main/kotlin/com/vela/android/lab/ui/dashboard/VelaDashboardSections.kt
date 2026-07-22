package com.vela.android.lab.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vela.android.lab.BuildConfig
import com.vela.android.lab.data.market.tick.TickBufferSnapshot
import com.vela.android.lab.data.paper.RiskFlag
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.ui.candles.CandleUiModel
import com.vela.android.lab.ui.candles.CandlesScreen
import com.vela.android.lab.ui.candles.CandlesUiState
import com.vela.android.lab.ui.navigation.VelaAppShell
import com.vela.android.lab.ui.navigation.VelaDestination
import com.vela.android.lab.ui.navigation.VelaMoreMenu
import com.vela.android.lab.ui.settings.VelaCandleCount
import com.vela.android.lab.ui.settings.VelaPreferencesState
import com.vela.android.lab.ui.settings.VelaSettingsScreen
import com.vela.android.lab.ui.settings.VelaTimeFormat
import com.vela.android.lab.ui.settings.VelaUiDensity
import com.vela.android.lab.ui.theme.VelaActionZone
import com.vela.android.lab.ui.theme.VelaMetricCard
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill
import java.util.Locale

/** Existing Activity-scoped state projected into the UX-2 section shell. */
internal data class VelaDashboardData(
    val dashboard: OfflineDashboardUiState,
    val alpaca: AlpacaTestStreamUiState?,
    val stock: AlpacaStockStreamUiState?,
    val watchlist: WatchlistUiState?,
    val ticks: TickBufferSnapshot?,
    val history: MarketHistoryUiState?,
    val paper: PaperAccountUiState?,
    val risk: PaperPortfolioRiskUiState?,
    val preflight: PaperOrderPreflightUiState?,
    val dryRunAudit: PaperOrderDryRunAuditUiState?,
    val previewQueue: PaperOrderPayloadPreviewQueueUiState?,
    val manualPaper: PaperManualSubmitUiState?,
    val candles: CandlesUiState,
    val preferences: VelaPreferencesState,
)

/**
 * Callback bundle only. Navigation remains state-only and none of these callbacks run merely
 * because a destination is rendered.
 */
internal data class VelaDashboardActions(
    val navigate: (VelaDestination) -> Unit,
    val generateBtc: () -> Unit,
    val generateSpy: () -> Unit,
    val clearDemo: () -> Unit,
    val keyIdChanged: (String) -> Unit,
    val secretChanged: (String) -> Unit,
    val saveCredentials: () -> Unit,
    val clearCredentials: () -> Unit,
    val startAlpacaTest: () -> Unit,
    val stopAlpacaTest: () -> Unit,
    val startStock: () -> Unit,
    val stopStock: () -> Unit,
    val watchlistInputChanged: (String) -> Unit,
    val watchlistAdd: () -> Unit,
    val watchlistRemove: (String) -> Unit,
    val historyRefresh: () -> Unit,
    val paperRefresh: () -> Unit,
    val riskRefresh: () -> Unit,
    val preflightSymbolChanged: (String) -> Unit,
    val preflightSideChanged: (OrderSide) -> Unit,
    val preflightQuantityChanged: (String) -> Unit,
    val preflightRun: () -> Unit,
    val preflightBuildDraft: () -> Unit,
    val preflightBuildPreview: () -> Unit,
    val readinessCheck: () -> Unit,
    val disabledExecutionAttempt: () -> Unit,
    val dryRunAuditRefresh: () -> Unit,
    val previewQueueRefresh: () -> Unit,
    val manualPaperArm: () -> Unit,
    val manualPaperDisarm: () -> Unit,
    val manualPaperRefresh: () -> Unit,
    val manualPaperWarningChanged: (Boolean) -> Unit,
    val manualPaperConfirmationChanged: (String) -> Unit,
    val manualPaperAction: () -> Unit,
    val candleSymbolChanged: (String) -> Unit,
    val candleCountChanged: (Int) -> Unit,
    val candlesRefresh: () -> Unit,
    val candleSelected: (CandleUiModel) -> Unit,
    val densityChanged: (VelaUiDensity) -> Unit,
    val defaultCandleCountChanged: (VelaCandleCount) -> Unit,
    val defaultSymbolChanged: (String) -> Unit,
    val advancedDiagnosticsChanged: (Boolean) -> Unit,
    val rememberLastSectionChanged: (Boolean) -> Unit,
    val timeFormatChanged: (VelaTimeFormat) -> Unit,
)

@Composable
internal fun VelaDashboardSections(
    currentDestination: VelaDestination,
    data: VelaDashboardData,
    actions: VelaDashboardActions,
) {
    val visualPreferences = data.preferences.preferences
    VelaAppShell(
        currentDestination = currentDestination,
        onDestinationSelected = actions.navigate,
        modeLabel = data.dashboard.modeLabel,
        realLocked = data.dashboard.realLocked,
        manualSubmitCompiled = data.manualPaper?.compileTimeEnabled ?: false,
        density = visualPreferences.density,
    ) { contentPadding ->
        when (currentDestination) {
            VelaDestination.HOME -> HomeSection(data, actions, contentPadding)
            VelaDestination.MARKET -> MarketSection(data, actions, contentPadding)
            VelaDestination.CANDLES -> CandlesScreen(
                state = data.candles,
                marketOpen = data.paper?.marketOpen,
                connectionState = data.stock?.connectionState ?: "DISCONNECTED",
                useUtc = visualPreferences.timeFormat == VelaTimeFormat.UTC,
                onSymbolSelected = actions.candleSymbolChanged,
                onCandleCountSelected = actions.candleCountChanged,
                onRefresh = actions.candlesRefresh,
                onCandleSelected = actions.candleSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
            VelaDestination.PAPER -> PaperSection(data, actions, contentPadding)
            VelaDestination.MORE -> VelaMoreMenu(
                currentDestination = currentDestination,
                onDestinationSelected = actions.navigate,
                contentPadding = contentPadding,
                density = visualPreferences.density,
            )
            VelaDestination.RISK -> RiskSection(data, actions, contentPadding)
            VelaDestination.HISTORY -> HistorySection(data, actions, contentPadding)
            VelaDestination.SETTINGS -> SettingsSection(data, actions, contentPadding)
            VelaDestination.DIAGNOSTICS -> DiagnosticsSection(data, actions, contentPadding)
        }
    }
}

@Composable
private fun HomeSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    ScreenColumn(contentPadding, data.preferences.preferences.density) {
        VelaSectionHeader(
            title = "Inicio",
            subtitle = "Cockpit resumido y solo lectura",
            trailingPill = data.dashboard.modeLabel,
            tone = VelaPillTone.Safe,
        )
        val selectedSymbol = selectedSymbol(data)
        val tick = data.ticks?.perSymbol?.get(selectedSymbol)
        val symbolHistory = data.history?.perSymbol?.get(selectedSymbol)
        val lastRefresh = listOfNotNull(
            data.paper?.lastRefreshAtEpochMillis,
            data.risk?.lastRefreshAtEpochMillis,
            data.history?.lastRefreshAtEpochMillis,
        ).maxOrNull()

        SummaryCard("Estado general") {
            MetricGrid(
                "Mercado",
                when (data.paper?.marketOpen) {
                    true -> "ABIERTO"
                    false -> "CERRADO"
                    null -> "N/D"
                },
                "Conexion",
                data.stock?.connectionState ?: "N/D",
                firstTone = if (data.paper?.marketOpen == true) VelaPillTone.Safe else VelaPillTone.Warning,
                secondTone = if (data.stock?.connectionState == "CONNECTED") VelaPillTone.Safe else VelaPillTone.Neutral,
            )
            SummaryRow("Ultimo refresh", formatEpoch(lastRefresh))
            SummaryRow("Mode", data.dashboard.modeLabel)
            SummaryRow("REAL", if (data.dashboard.realLocked) "locked" else "UNLOCKED")
        }

        SummaryCard("Mercado principal") {
            MetricGrid(
                "Simbolo",
                selectedSymbol,
                "Ultimo precio",
                formatDecimal(latestPrice(data, selectedSymbol)),
                firstTone = VelaPillTone.Safe,
                secondTone = VelaPillTone.Safe,
            )
            SummaryRow("Bid / Ask", "${formatDecimal(tick?.lastBid)} / ${formatDecimal(tick?.lastAsk)}")
            SummaryRow(
                "Direccion / senal",
                "${symbolHistory?.latestFeatureDirection ?: data.dashboard.lastFeatureDirection ?: "N/D"} / " +
                    (symbolHistory?.latestSignalState ?: data.dashboard.lastSignalState ?: "N/D"),
            )
            SummaryRow(
                "Timestamp",
                formatEpoch(symbolHistory?.latestBarTimestampMillis),
            )
        }

        SummaryCard("Cuenta Paper read-only") {
            MetricGrid(
                "Equity",
                formatMoney(data.paper?.equityUsd),
                "Buying power",
                formatMoney(data.paper?.buyingPowerUsd),
            )
            SummaryRow("Cash", formatMoney(data.paper?.cashUsd))
            SummaryRow("Positions", data.paper?.positionsCount?.toString() ?: "N/D")
            SummaryRow(
                "Account",
                listOfNotNull(
                    data.paper?.accountStatus,
                    data.paper?.tradingBlocked?.let { "trading_blocked=$it" },
                    data.paper?.accountBlocked?.let { "account_blocked=$it" },
                ).joinToString(" | ").ifBlank { "N/D" },
            )
        }

        SummaryCard("Riesgo resumido") {
            MetricGrid(
                "Flags",
                data.risk?.flags?.size?.toString() ?: "0",
                "Exposicion bruta",
                formatMoney(data.risk?.portfolio?.grossMarketValueUsd),
                firstTone = if (data.risk?.flags?.any { it.severity == RiskFlag.Severity.WARN } == true) {
                    VelaPillTone.Warning
                } else {
                    VelaPillTone.Safe
                },
            )
            SummaryRow(
                "Fuera de watchlist",
                data.risk?.exposures?.count { !it.inWatchlist }?.toString() ?: "0",
            )
            OutlinedButton(
                onClick = { actions.navigate(VelaDestination.RISK) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Ver riesgo")
            }
        }

        SummaryCard("Ultima actividad") {
            SummaryRow(
                "Ultima barra persistida",
                symbolHistory?.let { "${it.symbol} | ${formatEpoch(it.latestBarTimestampMillis)}" } ?: "N/D",
            )
            SummaryRow(
                "Ultimo dry-run",
                data.dryRunAudit?.recentRows?.firstOrNull()?.let {
                    "${it.symbol} | ${it.status} | ${formatEpoch(it.createdAtEpochMillis)}"
                } ?: "N/D",
            )
            SummaryRow(
                "Ultimo preview local",
                data.previewQueue?.recentRows?.firstOrNull()?.let {
                    "${it.symbol} | ${it.status} | ${formatEpoch(it.createdAtEpochMillis)}"
                } ?: "N/D",
            )
            if (data.manualPaper?.lastResult != null) {
                SummaryRow("Ultimo submit auditado", "Resultado disponible en Paper")
            }
        }
    }
}

@Composable
private fun MarketSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    var advancedExpanded by rememberSaveable {
        mutableStateOf(data.preferences.preferences.advancedDiagnostics)
    }
    ScreenColumn(contentPadding, data.preferences.preferences.density) {
        VelaSectionHeader(
            title = "Mercado",
            subtitle = "IEX read-only, watchlist y senales existentes",
            trailingPill = data.stock?.connectionState ?: "N/D",
            tone = if (data.stock?.connectionState == "CONNECTED") VelaPillTone.Safe else VelaPillTone.Neutral,
        )
        SymbolSelector(
            symbols = data.watchlist?.symbols.orEmpty(),
            selected = data.candles.selectedSymbol,
            onSelected = actions.candleSymbolChanged,
        )
        val selected = selectedSymbol(data)
        val tick = data.ticks?.perSymbol?.get(selected)
        val symbolStats = data.watchlist?.perSymbol?.get(selected)
        val symbolHistory = data.history?.perSymbol?.get(selected)
        SummaryCard("Detalle de $selected") {
            MetricGrid(
                "Precio",
                formatDecimal(latestPrice(data, selected)),
                "Bid / Ask",
                "${formatDecimal(tick?.lastBid)} / ${formatDecimal(tick?.lastAsk)}",
                firstTone = VelaPillTone.Safe,
            )
            SummaryRow("Senal", symbolStats?.lastSignalState ?: symbolHistory?.latestSignalState ?: "N/D")
            SummaryRow("Direccion", symbolHistory?.latestFeatureDirection ?: "N/D")
            SummaryRow("Persistidas", symbolStats?.persisted?.toString() ?: "0")
            SummaryRow("Ultima barra", formatEpoch(symbolHistory?.latestBarTimestampMillis))
        }
        data.stock?.let {
            AlpacaStockStreamCard(it, actions.startStock, actions.stopStock)
        }
        data.watchlist?.let {
            WatchlistCard(
                state = it,
                onAddInputChange = actions.watchlistInputChanged,
                onAdd = actions.watchlistAdd,
                onRemove = actions.watchlistRemove,
            )
        }
        OutlinedButton(
            onClick = { advancedExpanded = !advancedExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (advancedExpanded) "Ocultar diagnosticos avanzados" else "Mostrar diagnosticos avanzados")
        }
        if (advancedExpanded) {
            data.ticks?.let { TickDiagnosticsCard(it) }
            data.history?.let { MarketHistoryCard(it, actions.historyRefresh) }
        }
    }
}

@Composable
private fun PaperSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    ScreenColumn(contentPadding, data.preferences.preferences.density, imeAware = true) {
        VelaSectionHeader(
            title = "Paper",
            subtitle = "Cuenta, dry-run y boundary manual protegido",
            trailingPill = if (data.manualPaper?.sessionArmed == true) "ARMED" else "SAFE",
            tone = if (data.manualPaper?.sessionArmed == true) VelaPillTone.Blocked else VelaPillTone.Safe,
        )
        data.paper?.let { PaperAccountCard(it, actions.paperRefresh) }
        data.paper?.let {
            SummaryCard("Estado de mercado y cuenta") {
                SummaryRow("Market open", it.marketOpen?.toString() ?: "N/D")
                SummaryRow("Account status", it.accountStatus ?: "N/D")
                SummaryRow("Trading blocked", it.tradingBlocked?.toString() ?: "N/D")
                SummaryRow("Account blocked", it.accountBlocked?.toString() ?: "N/D")
            }
        }
        data.preflight?.let {
            PaperOrderPreflightCard(
                state = it,
                onSymbolChange = actions.preflightSymbolChanged,
                onSideChange = actions.preflightSideChanged,
                onQuantityChange = actions.preflightQuantityChanged,
                onRun = actions.preflightRun,
                onBuildDraft = actions.preflightBuildDraft,
                onBuildPayloadPreview = actions.preflightBuildPreview,
            )
            PaperExecutionReadinessCard(
                state = it,
                onCheck = actions.readinessCheck,
                onAttemptDisabled = actions.disabledExecutionAttempt,
            )
        }
        data.manualPaper?.let { manual ->
            VelaActionZone(
                title = "Manual Paper submit - one-shot",
                subtitle = "Paper-only. Confirmacion manual; sin LIVE, REAL ni automatizacion.",
                armed = manual.sessionArmed,
            ) {
                PaperManualSubmitCard(
                    state = manual,
                    onArm = actions.manualPaperArm,
                    onDisarm = actions.manualPaperDisarm,
                    onRefresh = actions.manualPaperRefresh,
                    onWarningAccepted = actions.manualPaperWarningChanged,
                    onConfirmationChange = actions.manualPaperConfirmationChanged,
                    onSubmit = actions.manualPaperAction,
                )
            }
        }
        data.previewQueue?.let {
            PaperOrderPayloadPreviewQueueCard(it, actions.previewQueueRefresh)
        }
        data.dryRunAudit?.let {
            PaperDryRunAuditCard(it, actions.dryRunAuditRefresh)
        }
        Spacer(modifier = Modifier.height(56.dp))
    }
}

@Composable
private fun RiskSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    ScreenColumn(contentPadding, data.preferences.preferences.density) {
        VelaSectionHeader(
            title = "Riesgo",
            subtitle = "Proyeccion informativa existente; no crea reglas nuevas",
        )
        val risk = data.risk
        SummaryCard("Clasificacion") {
            val info = risk?.flags?.count { it.severity == RiskFlag.Severity.INFO } ?: 0
            val warnings = risk?.flags?.count { it.severity == RiskFlag.Severity.WARN } ?: 0
            val blockers = listOf(data.paper?.tradingBlocked, data.paper?.accountBlocked).count { it == true }
            MetricGrid("Informativo", info.toString(), "Warnings", warnings.toString())
            SummaryRow("Blockers de cuenta existentes", blockers.toString())
            SummaryRow("Posiciones fuera de watchlist", risk?.exposures?.count { !it.inWatchlist }?.toString() ?: "0")
            SummaryRow("Sin cierre local", risk?.exposures?.count { it.latestLocalClose == null }?.toString() ?: "0")
        }
        risk?.let { PaperPortfolioRiskCard(it, actions.riskRefresh) }
    }
}

@Composable
private fun HistorySection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Mercado", "Dry-runs", "Previews", "Submit audit")
    ScreenColumn(contentPadding, data.preferences.preferences.density) {
        VelaSectionHeader(
            title = "Historial y auditoria",
            subtitle = "Lecturas locales append-only; sin borrar ni mutar",
        )
        TabRow(selectedTabIndex = selectedTab) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label, maxLines = 1) },
                )
            }
        }
        when (selectedTab) {
            0 -> data.history?.let { MarketHistoryCard(it, actions.historyRefresh) }
            1 -> data.dryRunAudit?.let { PaperDryRunAuditCard(it, actions.dryRunAuditRefresh) }
            2 -> data.previewQueue?.let { PaperOrderPayloadPreviewQueueCard(it, actions.previewQueueRefresh) }
            else -> SummaryCard("Submit audit") {
                if (data.manualPaper?.lastResult == null) {
                    Text(
                        text = "Sin auditoria de submit expuesta por el estado UI actual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SummaryRow("Ultimo resultado", "Disponible en la zona Paper protegida")
                }
                Text(
                    text = "No se consultan claves, headers, account id ni texto de confirmacion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    VelaSettingsScreen(
        state = data.preferences,
        availableSymbols = data.watchlist?.symbols.orEmpty(),
        modeLabel = data.dashboard.modeLabel,
        realLocked = data.dashboard.realLocked,
        manualSubmitCompiled = data.manualPaper?.compileTimeEnabled ?: false,
        clockStatus = data.paper?.lastRefreshAtEpochMillis?.let { "Paper clock refreshed" } ?: "Not refreshed",
        buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        onDensitySelected = actions.densityChanged,
        onCandleCountSelected = actions.defaultCandleCountChanged,
        onDefaultSymbolSelected = actions.defaultSymbolChanged,
        onAdvancedDiagnosticsChanged = actions.advancedDiagnosticsChanged,
        onRememberLastSectionChanged = actions.rememberLastSectionChanged,
        onTimeFormatSelected = actions.timeFormatChanged,
        contentPadding = contentPadding,
        credentialsContent = {
            data.alpaca?.let {
                AlpacaCredentialsCard(
                    state = it,
                    onKeyIdChange = actions.keyIdChanged,
                    onSecretChange = actions.secretChanged,
                    onSave = actions.saveCredentials,
                    onClearCreds = actions.clearCredentials,
                    onTest = actions.startAlpacaTest,
                    onStopTest = actions.stopAlpacaTest,
                    showCredentialEditor = true,
                    showStreamDiagnostics = false,
                )
            }
        },
        modifier = Modifier.imePadding(),
    )
}

@Composable
private fun DiagnosticsSection(
    data: VelaDashboardData,
    actions: VelaDashboardActions,
    contentPadding: PaddingValues,
) {
    ScreenColumn(contentPadding, data.preferences.preferences.density) {
        VelaSectionHeader(
            title = "Diagnostico",
            subtitle = "Pipeline, DB y streams fuera del flujo principal",
        )
        ControlsCard(actions.generateBtc, actions.generateSpy, actions.clearDemo)
        StatusCard(data.dashboard)
        PipelineCard(data.dashboard)
        CountersCard(data.dashboard)
        data.dashboard.lastError?.let { ErrorCard(it) }
        data.alpaca?.let {
            AlpacaCredentialsCard(
                state = it,
                onKeyIdChange = actions.keyIdChanged,
                onSecretChange = actions.secretChanged,
                onSave = actions.saveCredentials,
                onClearCreds = actions.clearCredentials,
                onTest = actions.startAlpacaTest,
                onStopTest = actions.stopAlpacaTest,
                showCredentialEditor = false,
                showStreamDiagnostics = true,
            )
        }
        data.ticks?.let { TickDiagnosticsCard(it) }
    }
}

@Composable
private fun ScreenColumn(
    contentPadding: PaddingValues,
    density: VelaUiDensity,
    imeAware: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = if (density == VelaUiDensity.COMPACT) 8.dp else 12.dp
    var modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(contentPadding)
        .padding(horizontal = 16.dp, vertical = spacing)
    if (imeAware) modifier = modifier.imePadding()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun SymbolSelector(
    symbols: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Simbolo visual", style = MaterialTheme.typography.labelMedium)
        if (symbols.isEmpty()) {
            Text(
                "Watchlist vacia",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                symbols.forEach { symbol ->
                    FilterChip(
                        selected = symbol == selected,
                        onClick = { onSelected(symbol) },
                        label = { Text(symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricGrid(
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
        VelaMetricCard(firstLabel, firstValue, Modifier.weight(1f), firstTone)
        VelaMetricCard(secondLabel, secondValue, Modifier.weight(1f), secondTone)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun selectedSymbol(data: VelaDashboardData): String =
    data.candles.selectedSymbol
        ?: data.stock?.symbol
        ?: data.watchlist?.symbols?.firstOrNull()
        ?: data.dashboard.lastSymbol
        ?: "SPY"

private fun latestPrice(data: VelaDashboardData, symbol: String): Double? {
    val tick = data.ticks?.perSymbol?.get(symbol)
    val bid = tick?.lastBid
    val ask = tick?.lastAsk
    return if (bid != null && ask != null && bid > 0.0 && ask > 0.0) {
        (bid + ask) / 2.0
    } else {
        data.watchlist?.perSymbol?.get(symbol)?.lastClose
            ?: data.history?.perSymbol?.get(symbol)?.latestBarClose
            ?: data.stock?.lastBarClose
            ?: data.dashboard.lastPrice
    }
}

private fun formatDecimal(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "N/D"

private fun formatMoney(value: Double?): String =
    value?.let { "USD ${String.format(Locale.ROOT, "%.2f", it)}" } ?: "N/D"

private fun formatEpoch(value: Long?): String =
    value?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "N/D"
