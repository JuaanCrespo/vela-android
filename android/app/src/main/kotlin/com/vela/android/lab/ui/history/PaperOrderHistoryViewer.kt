package com.vela.android.lab.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.android.lab.data.paper.history.CanonicalPaperLifecycleObservation
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import com.vela.android.lab.ui.theme.LocalVelaColors
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val LEGACY_NOT_RECORDED: String = "Legacy / not recorded"

@Composable
fun PaperOrderHistoryViewer(
    state: PaperOrderHistoryUiState,
    onStatusFilterSelected: (PaperHistoryStatusFilter) -> Unit,
    onSymbolFilterSelected: (String?) -> Unit,
    onSideFilterSelected: (PaperHistorySideFilter) -> Unit,
    onOrderSelected: (String) -> Unit,
    onCloseDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VelaSectionHeader(
            title = "Historial Paper",
            subtitle = "Evidencia canónica local · sólo lectura · sin red",
            trailingPill = "OFFLINE",
            tone = VelaPillTone.Safe,
        )

        when {
            state.isDetailLoading -> PaperHistoryLoading("Leyendo detalle local…")
            state.selectedOrder != null -> PaperHistoryDetail(
                order = state.selectedOrder,
                onClose = onCloseDetails,
            )
            else -> PaperHistoryList(
                state = state,
                onStatusFilterSelected = onStatusFilterSelected,
                onSymbolFilterSelected = onSymbolFilterSelected,
                onSideFilterSelected = onSideFilterSelected,
                onOrderSelected = onOrderSelected,
            )
        }
    }
}

@Composable
private fun PaperHistoryList(
    state: PaperOrderHistoryUiState,
    onStatusFilterSelected: (PaperHistoryStatusFilter) -> Unit,
    onSymbolFilterSelected: (String?) -> Unit,
    onSideFilterSelected: (PaperHistorySideFilter) -> Unit,
    onOrderSelected: (String) -> Unit,
) {
    PaperHistoryFilters(
        state = state,
        onStatusFilterSelected = onStatusFilterSelected,
        onSymbolFilterSelected = onSymbolFilterSelected,
        onSideFilterSelected = onSideFilterSelected,
    )

    Text(
        text = "Mostrando hasta ${state.initialLimit} órdenes · recientes primero",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when {
        state.isLoading -> PaperHistoryLoading("Leyendo historial local…")
        state.error != null -> PaperHistoryErrorCard(state.error)
        state.orders.isEmpty() -> PaperHistoryEmptyState()
        else -> state.orders.forEach { order ->
            PaperHistoryOrderCard(order = order, onSelected = onOrderSelected)
        }
    }
}

@Composable
private fun PaperHistoryFilters(
    state: PaperOrderHistoryUiState,
    onStatusFilterSelected: (PaperHistoryStatusFilter) -> Unit,
    onSymbolFilterSelected: (String?) -> Unit,
    onSideFilterSelected: (PaperHistorySideFilter) -> Unit,
) {
    FilterGroup(label = "Estado") {
        PaperHistoryStatusFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.statusFilter == filter,
                onClick = { onStatusFilterSelected(filter) },
                label = { Text(filter.label) },
            )
        }
    }
    FilterGroup(label = "Símbolo") {
        FilterChip(
            selected = state.symbolFilter == null,
            onClick = { onSymbolFilterSelected(null) },
            label = { Text("Todos") },
        )
        state.availableSymbols.forEach { symbol ->
            FilterChip(
                selected = state.symbolFilter == symbol,
                onClick = { onSymbolFilterSelected(symbol) },
                label = { Text(symbol) },
            )
        }
    }
    FilterGroup(label = "Lado") {
        PaperHistorySideFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.sideFilter == filter,
                onClick = { onSideFilterSelected(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun FilterGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PaperHistoryOrderCard(
    order: CanonicalPaperOrderHistory,
    onSelected: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(order.submitAttemptId) }
            .semantics {
                contentDescription =
                    "Abrir detalle de ${order.symbol ?: "orden Paper histórica"}"
            },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = order.symbol ?: LEGACY_NOT_RECORDED,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                VelaStatusPill(
                    label = paperHistoryFinalStatus(order),
                    tone = lifecycleTone(order),
                )
            }
            Text(
                text = formatPaperHistoryEpoch(paperHistoryTimestamp(order)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaperHistoryValue(
                label = "Orden",
                value = listOf(
                    order.side ?: LEGACY_NOT_RECORDED,
                    formatPaperHistoryNumber(order.quantity),
                    order.orderType ?: LEGACY_NOT_RECORDED,
                    order.timeInForce ?: LEGACY_NOT_RECORDED,
                ).joinToString(" · "),
            )
            PaperHistoryValue(
                label = "Fill promedio",
                value = formatPaperHistoryMoney(order.currentLifecycle?.filledAveragePriceUsd),
            )
            PaperHistoryValue(
                label = "Integridad",
                value = order.integrityStatus.name,
                tone = integrityTone(order.integrityStatus),
            )
            Text(
                text = "Tocá para ver identidad, evidencia y timeline completo.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalVelaColors.current.safe,
            )
        }
    }
}

@Composable
private fun PaperHistoryDetail(
    order: CanonicalPaperOrderHistory,
    onClose: () -> Unit,
) {
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Volver al historial")
    }

    PaperHistoryDetailCard("Identidad durable") {
        PaperHistoryValue("Submit attempt id", order.submitAttemptId)
        PaperHistoryValue("Alpaca order id", order.alpacaOrderId ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Client order id", order.clientOrderId ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Preview id", order.previewId ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Dry-run client id", order.linkedClientDryRunId ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Secuencia durable", order.orderSequenceId?.toString() ?: LEGACY_NOT_RECORDED)
    }

    PaperHistoryDetailCard("Orden Paper registrada") {
        PaperHistoryValue("Símbolo", order.symbol ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Lado", order.side ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Cantidad", formatPaperHistoryNumber(order.quantity))
        PaperHistoryValue("Tipo", order.orderType ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("TIF", order.timeInForce ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Precio límite", formatPaperHistoryMoney(order.limitPriceUsd))
        PaperHistoryValue(
            "Expected position delta",
            formatSignedPositionDelta(order.expectedSignedPositionDelta),
        )
    }

    PaperHistoryDetailCard("Dry-run y submit local") {
        if (
            PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN in
            order.integrityDiagnostics
        ) {
            VelaStatusPill(label = "UNKNOWN LEGACY", tone = VelaPillTone.Warning)
        }
        PaperHistoryValue("Dry-run audit row", order.dryRunAuditRowId?.toString() ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Decisión creada", formatPaperHistoryEpoch(order.decisionCreatedAtEpochMillis))
        PaperHistoryValue("Audit start row", order.auditStartRowId?.toString() ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Audit result row", order.auditResultRowId?.toString() ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Resultado local", order.localSubmitResult ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Resultado registrado", formatPaperHistoryEpoch(order.localSubmitResultAtEpochMillis))
        PaperHistoryValue("HTTP submit", order.submitHttpStatusCode?.toString() ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Estado Alpaca inicial", order.initialAlpacaStatus ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Submitted at Alpaca", order.alpacaSubmittedAtIso ?: LEGACY_NOT_RECORDED)
    }

    PaperHistoryDetailCard("Estado canónico actual") {
        PaperHistoryValue("Estado final", paperHistoryFinalStatus(order), lifecycleTone(order))
        PaperHistoryValue("Terminal", (order.currentLifecycle?.terminal == true).toString())
        PaperHistoryValue("Mapping", order.mappingState ?: LEGACY_NOT_RECORDED)
        PaperHistoryValue("Resolved", order.resolved.toString())
        PaperHistoryValue("Unresolved", order.unresolved.toString())
        PaperHistoryValue("Ambiguous", order.ambiguous.toString())
        PaperHistoryValue(
            "Cantidad filled",
            formatPaperHistoryNumber(order.currentLifecycle?.filledQuantity),
        )
        PaperHistoryValue(
            "Precio promedio filled",
            formatPaperHistoryMoney(order.currentLifecycle?.filledAveragePriceUsd),
        )
        PaperHistoryValue(
            "Filled at",
            order.currentLifecycle?.filledAtIso ?: LEGACY_NOT_RECORDED,
        )
        PaperHistoryValue(
            "Reset acknowledged",
            (order.resetAcknowledgedAtEpochMillis != null).toString(),
        )
        PaperHistoryValue(
            "Reset reconocido",
            formatPaperHistoryEpoch(order.resetAcknowledgedAtEpochMillis),
        )
    }

    PaperHistoryDetailCard("Integridad y diagnósticos") {
        PaperHistoryValue(
            "Integridad",
            order.integrityStatus.name,
            integrityTone(order.integrityStatus),
        )
        if (order.integrityDiagnostics.isEmpty()) {
            Text("Sin diagnósticos.")
        } else {
            order.integrityDiagnostics.forEach { diagnostic ->
                Text(
                    text = "• ${diagnostic.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    PaperHistoryDetailCard("Timeline lifecycle · secuencia DB ascendente") {
        if (order.lifecycleObservations.isEmpty()) {
            Text(
                text = LEGACY_NOT_RECORDED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            order.lifecycleObservations.forEachIndexed { index, observation ->
                PaperLifecycleObservationCard(index + 1, observation)
            }
        }
    }
}

@Composable
private fun PaperLifecycleObservationCard(
    sequence: Int,
    observation: CanonicalPaperLifecycleObservation,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "#$sequence · DB ${observation.databaseId}",
                    fontWeight = FontWeight.SemiBold,
                )
                VelaStatusPill(
                    label = observation.status,
                    tone = if (observation.terminal) VelaPillTone.Safe else VelaPillTone.Neutral,
                )
            }
            PaperHistoryValue("Observado", formatPaperHistoryEpoch(observation.observedAtEpochMillis))
            PaperHistoryValue("Raw status", observation.rawStatus)
            PaperHistoryValue("Terminal", observation.terminal.toString())
            PaperHistoryValue("Filled qty", formatPaperHistoryNumber(observation.filledQuantity))
            PaperHistoryValue("Avg fill", formatPaperHistoryMoney(observation.filledAveragePriceUsd))
            PaperHistoryValue("Filled at", observation.filledAtIso ?: LEGACY_NOT_RECORDED)
            PaperHistoryValue("Fuente", observation.source)
            PaperHistoryValue("HTTP", observation.httpStatusCode?.toString() ?: LEGACY_NOT_RECORDED)
            PaperHistoryValue(
                "Submit audit row",
                observation.submitAuditEntryId?.toString() ?: LEGACY_NOT_RECORDED,
            )
            PaperHistoryValue("Payload fingerprint", observation.payloadFingerprint)
            PaperHistoryValue(
                "Same payload as previous",
                observation.samePayloadAsPrevious.toString(),
            )
            if (observation.samePayloadAsPrevious) {
                VelaStatusPill(
                    label = "Payload repetido · observación preservada",
                    tone = VelaPillTone.Warning,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun PaperHistoryDetailCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun PaperHistoryValue(
    label: String,
    value: String,
    tone: VelaPillTone? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (tone == null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        } else {
            VelaStatusPill(label = value, tone = tone)
        }
    }
}

@Composable
private fun PaperHistoryLoading(label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(label)
        }
    }
}

@Composable
private fun PaperHistoryErrorCard(error: PaperHistoryError) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VelaStatusPill(
                label = when (error.kind) {
                    PaperHistoryErrorKind.LOCAL_DATABASE -> "ERROR DE BASE LOCAL"
                    PaperHistoryErrorKind.CANONICAL_RECONSTRUCTION -> "ERROR CANÓNICO"
                },
                tone = VelaPillTone.Blocked,
            )
            Text(error.userMessage)
            Text(
                text = "No se modificó ni reparó evidencia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PaperHistoryEmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Todavía no hay órdenes Paper históricas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "No hay registros para los filtros actuales. La vista sólo representa " +
                    "evidencia canónica ya persistida.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun paperHistoryFinalStatus(order: CanonicalPaperOrderHistory): String =
    order.currentLifecycle?.status
        ?: order.initialAlpacaStatus
        ?: order.localSubmitResult
        ?: LEGACY_NOT_RECORDED

internal fun paperHistoryTimestamp(order: CanonicalPaperOrderHistory): Long? =
    order.localSubmitResultAtEpochMillis
        ?: order.decisionCreatedAtEpochMillis
        ?: order.currentLifecycle?.observedAtEpochMillis

internal fun formatPaperHistoryEpoch(epochMillis: Long?): String =
    epochMillis?.let {
        PAPER_HISTORY_TIME_FORMAT.format(Instant.ofEpochMilli(it))
    } ?: LEGACY_NOT_RECORDED

internal fun formatPaperHistoryNumber(value: Double?): String =
    value?.let {
        String.format(Locale.US, "%.4f", it).trimEnd('0').trimEnd('.')
    } ?: LEGACY_NOT_RECORDED

internal fun formatPaperHistoryMoney(value: Double?): String =
    value?.let { "$${formatPaperHistoryNumber(it)}" } ?: LEGACY_NOT_RECORDED

internal fun formatSignedPositionDelta(value: Double?): String =
    value?.let {
        val prefix = if (it > 0.0) "+" else ""
        "$prefix${formatPaperHistoryNumber(it)}"
    } ?: LEGACY_NOT_RECORDED

private fun integrityTone(status: PaperHistoryIntegrityStatus): VelaPillTone = when (status) {
    PaperHistoryIntegrityStatus.VALID -> VelaPillTone.Safe
    PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS -> VelaPillTone.Warning
    PaperHistoryIntegrityStatus.INCONSISTENT -> VelaPillTone.Blocked
}

private fun lifecycleTone(order: CanonicalPaperOrderHistory): VelaPillTone = when {
    order.integrityStatus == PaperHistoryIntegrityStatus.INCONSISTENT -> VelaPillTone.Blocked
    order.currentLifecycle?.terminal == true -> VelaPillTone.Safe
    order.unresolved -> VelaPillTone.Warning
    else -> VelaPillTone.Neutral
}

private val PAPER_HISTORY_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss", Locale.forLanguageTag("es-AR"))
        .withZone(ZoneId.systemDefault())
