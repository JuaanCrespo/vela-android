package com.vela.android.lab.ui.positions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vela.android.lab.data.paper.reconciliation.domain.AnchorStatus
import com.vela.android.lab.data.paper.reconciliation.domain.BrokerSnapshotCompleteness
import com.vela.android.lab.data.paper.reconciliation.domain.PositionDiagnostic
import com.vela.android.lab.data.paper.reconciliation.evidence.CaptureDiagnostic
import com.vela.android.lab.data.paper.reconciliation.evidence.StoredBrokerSnapshot
import com.vela.android.lab.data.paper.reconciliation.evidence.readEnumNames
import java.time.Instant

@Composable
fun PositionReconciliationRoute(viewModel: PositionReconciliationViewModel, contentPadding: PaddingValues) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Re-entry refreshes only the local projection. No engine, capture, or network is called.
    LaunchedEffect(viewModel) { viewModel.loadOffline() }
    PositionReconciliationScreen(state, contentPadding, viewModel::refreshPositions,
        viewModel::selectBaselineSymbol, viewModel::requestEstablishBaseline, viewModel::requestInvalidateBaseline,
        viewModel::confirmEstablishBaseline, viewModel::confirmInvalidateBaseline,
        viewModel::dismissBaselineDialog, viewModel::toggleDiagnostics)
}

/** Pure rendering and explicit callbacks. This package has no order-execution capabilities. */
@Composable
fun PositionReconciliationScreen(
    state: PositionReconciliationUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onRequestEstablish: () -> Unit,
    onRequestInvalidate: (String) -> Unit,
    onConfirmEstablish: () -> Unit,
    onConfirmInvalidate: () -> Unit,
    onDismissDialog: () -> Unit,
    onToggleDiagnostics: () -> Unit,
) {
    var symbolInput by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Posiciones Paper", style = MaterialTheme.typography.headlineSmall)
        Text("Reconciliación informativa. Sólo lectura broker; baselines locales explícitos.")
        Button(onClick = onRefresh, enabled = state.canRefresh) { Text("Refresh positions") }
        Text("Refresh: ${state.refreshState}")
        if (state.loadingLocal) Text("Leyendo estado durable local…")
        if (state.workingLocal) Text("Operación local en curso…")
        state.error?.let { Text(it.name, color = MaterialTheme.colorScheme.error) }
        if (state.durable.latestComplete == null) Text("NO BROKER SNAPSHOT YET")
        SnapshotCard("Last attempt", state.durable.lastAttempt)
        SnapshotCard("Latest complete snapshot (histórico)", state.durable.latestComplete)
        Text("Frescura al abrir / última acción: ${state.freshness}")
        if (state.durable.lastAttempt?.metadata?.completeness == BrokerSnapshotCompleteness.FAILED.name) {
            Text("BROKER_READ_FAILED — el intento falló; el último snapshot completo no es una captura nueva.")
        }
        state.durable.latestReport?.let { report ->
            Text("Reporte durable: ${report.metadata.reportId}")
            Text("Observación broker del reporte: ${report.metadata.brokerSnapshotId}")
            Text("Cuenta del reporte: ${safePaperAccountLabel(report.metadata.accountRef)}")
            if (report.metadata.brokerSnapshotId != state.durable.latestComplete?.metadata?.snapshotId) {
                Text("Reporte histórico: NO corresponde al último snapshot completo.")
            }
        }
        if (state.durable.latestReport == null) Text("Sin reporte durable. La comparación se genera sólo con un nuevo refresh manual completo.")
        state.rows.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.symbol, style = MaterialTheme.typography.titleMedium)
                    Text("Broker observed: ${row.brokerObserved}")
                    Text("VELA known delta: ${row.knownVelaDelta}")
                    Text("Delta completo: ${row.knownDeltaComplete}")
                    Text("Baseline: ${row.baseline}")
                    Text("VELA expected: ${row.expected}")
                    Text("Difference: ${row.difference}")
                    Text("State: ${row.state}")
                    Text(row.explanation)
                    if (state.diagnosticsExpanded) {
                        Text("Provenance: ${row.provenance}")
                        Text("Anchor: ${row.anchorId ?: "NONE"}")
                        row.diagnostics.forEach { Text(it) }
                    }
                }
            }
        }
        Text("Baselines locales", style = MaterialTheme.typography.titleMedium)
        state.durable.anchors.filter { it.metadata.status == AnchorStatus.ACTIVE.name }.forEach { anchor ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${anchor.metadata.symbol} — ACTIVE — ${anchor.metadata.baselineQty}")
                    Text("Paper account: ${safePaperAccountLabel(anchor.metadata.accountRef)}")
                    Text("Anchor: ${anchor.metadata.anchorId}")
                    OutlinedButton(onClick = { onRequestInvalidate(anchor.metadata.anchorId) }, enabled = !state.busy && state.dialog == null) {
                        Text("Invalidar baseline")
                    }
                }
            }
        }
        if (state.durable.anchors.none { it.metadata.status == AnchorStatus.ACTIVE.name }) Text("Active anchors: NONE")
        OutlinedTextField(value = symbolInput, onValueChange = { symbolInput = it.take(32) }, label = { Text("Símbolo para baseline") }, singleLine = true)
        OutlinedButton(onClick = { onSelectSymbol(symbolInput) }, enabled = !state.busy && state.dialog == null) { Text("Seleccionar símbolo") }
        Text("La selección es local. Para un símbolo ausente, un snapshot COMPLETE puede acreditar baseline 0; no se acepta automáticamente.")
        state.selectedBaseline?.let { choice ->
            Text("Símbolo seleccionado: ${choice.symbol}")
            choice.blockedReason?.let { Text("Baseline deshabilitado: $it") }
        }
        OutlinedButton(onClick = onRequestEstablish, enabled = state.canEstablish) { Text("Establecer baseline") }
        Text("Después de cambiar un baseline, la comparación autoritativa requiere un futuro Refresh positions.")
        OutlinedButton(onClick = onToggleDiagnostics) { Text(if (state.diagnosticsExpanded) "Ocultar diagnósticos" else "Inspeccionar diagnósticos") }
        if (state.diagnosticsExpanded) {
            state.durable.latestReport?.let { report ->
                Text("Engine: ${report.metadata.engineVersion}; policy: ${report.metadata.policyVersion}")
                Text("Reporte creado: ${timestamp(report.metadata.createdAtEpochMillis)}")
                safePositionDiagnostics<PositionDiagnostic>(report.metadata.diagnosticsJson).forEach { Text(it) }
            }
            state.durable.anchors.forEach { Text("${it.metadata.symbol}: ${it.metadata.status} / ${it.metadata.anchorId}") }
        }
    }
    when (val dialog = state.dialog) {
        is PositionBaselineDialog.Establish -> {
            val proposal = requireNotNull(dialog.selection.proposal)
            val snapshot = requireNotNull(dialog.selection.snapshot).metadata
            AlertDialog(onDismissRequest = onDismissDialog, title = { Text("Confirmar baseline local") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Symbol: ${proposal.symbol}")
                    Text("Paper account: ${safePaperAccountLabel(proposal.accountRef)}")
                    Text("Snapshot: ${snapshot.snapshotId}")
                    Text("Capturado: ${timestamp(snapshot.completedAtEpochMillis)}")
                    Text("Broker baseline quantity: ${quantityText(proposal.baselineQty)}")
                    Text("Local history checkpoint: ${proposal.cut.orderSequenceInclusive} / ${proposal.cut.lifecycleSequenceInclusive}")
                    Text("Coverage: ${proposal.cut.assurance}; cursors: ${proposal.cursors.size}")
                    Text("Known VELA delta: ${quantityText(dialog.selection.knownDelta)}")
                    Text("El baseline es un punto de partida local aceptado, no una reconstrucción de la cuenta.")
                    Text("Aceptar este baseline NO verifica ni reconstruye actividad anterior de la cuenta.")
                }
            }, confirmButton = { TextButton(onClick = onConfirmEstablish, enabled = !state.busy) { Text("Confirmar baseline") } },
                dismissButton = { TextButton(onClick = onDismissDialog) { Text("Volver") } })
        }
        is PositionBaselineDialog.Invalidate -> AlertDialog(onDismissRequest = onDismissDialog,
            title = { Text("Invalidar baseline local") }, text = {
                Column {
                    Text("Symbol: ${dialog.anchor.metadata.symbol}")
                    Text("Baseline: ${dialog.anchor.metadata.baselineQty}")
                    Text("Anchor: ${dialog.anchor.metadata.anchorId}")
                    Text("Motivo seleccionado: MANUAL (única opción).")
                    Text("No se cambia la posición broker ni se borra la historia.")
                }
            }, confirmButton = { TextButton(onClick = onConfirmInvalidate, enabled = !state.busy) { Text("Confirmar invalidación") } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("Volver") } })
        null -> Unit
    }
}

@Composable
private fun SnapshotCard(title: String, snapshot: StoredBrokerSnapshot?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (snapshot == null) Text("NONE") else {
                Text("${snapshot.metadata.completeness} / ${snapshot.metadata.snapshotId}")
                Text("Paper account: ${safePaperAccountLabel(snapshot.metadata.accountRef)}")
                Text("Inicio: ${timestamp(snapshot.metadata.startedAtEpochMillis)}")
                Text("Completado: ${timestamp(snapshot.metadata.completedAtEpochMillis)}")
                Text("Parser: ${snapshot.metadata.parserVersion}")
                safePositionDiagnostics<CaptureDiagnostic>(snapshot.metadata.diagnosticsJson).forEach { Text(it) }
            }
        }
    }
}

private fun timestamp(at: Long): String = Instant.ofEpochMilli(at).toString()

internal inline fun <reified T : Enum<T>> safePositionDiagnostics(json: String): List<String> =
    runCatching { readEnumNames<T>(json).map { it.name }.sorted() }.getOrDefault(listOf("INVALID_DURABLE_DIAGNOSTICS"))
