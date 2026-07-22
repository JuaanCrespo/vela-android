package com.vela.android.lab.ui.candles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.android.lab.ui.theme.LocalVelaColors
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only candles screen. [marketOpen] and [connectionState] are display-only snapshots from the
 * existing app shell; navigation here never starts a stream or makes an HTTP request.
 */
@Composable
fun CandlesScreen(
    state: CandlesUiState,
    marketOpen: Boolean?,
    connectionState: String,
    useUtc: Boolean,
    onSymbolSelected: (String) -> Unit,
    onCandleCountSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onCandleSelected: (CandleUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVelaColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VelaSectionHeader(
            title = "Velas",
            subtitle = "OHLC local persistido · solo lectura",
            trailingPill = "1m",
            tone = VelaPillTone.Safe,
        )

        StatusStrip(
            state = state,
            marketOpen = marketOpen,
            connectionState = connectionState,
        )

        Text(
            text = "Símbolo",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        if (state.symbols.isEmpty()) {
            Text(
                text = "La watchlist no contiene símbolos.",
                color = palette.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.symbols, key = { it }) { symbol ->
                    FilterChip(
                        selected = symbol == state.selectedSymbol,
                        onClick = { onSymbolSelected(symbol) },
                        label = { Text(symbol) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Cantidad",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CandlesUiState.CANDLE_COUNT_OPTIONS.forEach { count ->
                        FilterChip(
                            selected = count == state.candleCount,
                            onClick = { onCandleCountSelected(count) },
                            label = { Text(count.toString()) },
                        )
                    }
                }
            }
            Button(onClick = onRefresh, enabled = !state.isLoading) {
                Text("Refresh local")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = CandlesUiState.SOURCE_LABEL,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = latestTimestampLabel(state, useUtc),
                    color = palette.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        when (state.dataState) {
            CandleDataState.LOADING -> CandleMessage(
                title = "Cargando barras locales",
                detail = "Leyendo Room; no se inicia ninguna conexión.",
                showProgress = true,
            )
            CandleDataState.EMPTY -> CandleMessage(
                title = "Sin datos",
                detail = "No hay barras de 1 minuto persistidas para ${state.selectedSymbol ?: "la watchlist"}.",
            )
            CandleDataState.INSUFFICIENT -> CandleMessage(
                title = "Datos OHLC insuficientes",
                detail = "Las ${state.rejectedBarCount} filas disponibles no tienen OHLC positivo y coherente. No se fabricaron velas.",
            )
            CandleDataState.ERROR -> CandleMessage(
                title = "Error read-only",
                detail = state.errorMessage ?: "No fue posible leer las barras locales.",
                error = true,
            )
            CandleDataState.READY -> {
                if (state.isStale) {
                    CandleMessage(
                        title = "Datos stale",
                        detail = "La última barra supera el umbral local de frescura. El gráfico sigue siendo histórico y read-only.",
                    )
                }
                if (state.rejectedBarCount > 0) {
                    Text(
                        text = "${state.rejectedBarCount} fila(s) OHLC inválida(s) fueron omitidas.",
                        color = palette.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                VelaCandlestickChart(
                    candles = state.candles,
                    selectedCandle = state.selectedCandle,
                    useUtc = useUtc,
                    onCandleSelected = onCandleSelected,
                )
                state.selectedCandle?.let { candle ->
                    CandleDetail(candle = candle, useUtc = useUtc)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatusStrip(
    state: CandlesUiState,
    marketOpen: Boolean?,
    connectionState: String,
) {
    val normalizedConnection = connectionState.ifBlank { "UNKNOWN" }.uppercase()
    val freshnessLabel = when (state.freshness) {
        CandleFreshness.FRESH -> "Datos fresh"
        CandleFreshness.STALE -> "Datos stale"
        CandleFreshness.UNKNOWN -> "Frescura N/A"
    }
    val freshnessTone = when (state.freshness) {
        CandleFreshness.FRESH -> VelaPillTone.Safe
        CandleFreshness.STALE -> VelaPillTone.Warning
        CandleFreshness.UNKNOWN -> VelaPillTone.Neutral
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            VelaStatusPill(
                label = when (marketOpen) {
                    true -> "Mercado abierto"
                    false -> "Mercado cerrado"
                    null -> "Mercado N/A"
                },
                tone = if (marketOpen == true) VelaPillTone.Safe else VelaPillTone.Warning,
            )
            VelaStatusPill(
                label = normalizedConnection,
                tone = if (normalizedConnection == "CONNECTED") {
                    VelaPillTone.Safe
                } else {
                    VelaPillTone.Neutral
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            VelaStatusPill(label = freshnessLabel, tone = freshnessTone)
            VelaStatusPill(label = "Solo lectura", tone = VelaPillTone.Safe)
        }
    }
}

@Composable
private fun CandleMessage(
    title: String,
    detail: String,
    showProgress: Boolean = false,
    error: Boolean = false,
) {
    val palette = LocalVelaColors.current
    val accent = if (error) palette.blocked else palette.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column {
            Text(
                text = title,
                color = accent,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CandleDetail(candle: CandleUiModel, useUtc: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            LocalVelaColors.current.cardStroke,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vela seleccionada", style = MaterialTheme.typography.titleSmall)
                VelaStatusPill(
                    label = candle.direction.label,
                    tone = when (candle.direction) {
                        CandleDirection.BULLISH -> VelaPillTone.Safe
                        CandleDirection.BEARISH -> VelaPillTone.Blocked
                        CandleDirection.DOJI -> VelaPillTone.Warning
                    },
                )
            }
            Text(
                text = formatInstant(candle.timestamp, useUtc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            MetricPair("Open", price(candle.open), "High", price(candle.high))
            MetricPair("Low", price(candle.low), "Close", price(candle.close))
            MetricPair("Rango", price(candle.range), "Dirección", candle.direction.label)
            MetricPair(
                "Volume",
                candle.volume?.let(::quantity) ?: "N/D",
                "Tipo",
                CandlesUiState.VOLUME_LABEL,
            )
        }
    }
}

@Composable
private fun MetricPair(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Metric(label = firstLabel, value = firstValue, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(10.dp))
        Metric(label = secondLabel, value = secondValue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun latestTimestampLabel(state: CandlesUiState, useUtc: Boolean): String {
    val latest = state.candles.lastOrNull()?.timestamp ?: return "Último timestamp: N/D"
    val age = state.lastBarAgeMillis?.let { " · age ${formatAge(it)}" }.orEmpty()
    return "Último timestamp: ${formatInstant(latest, useUtc)}$age"
}

private fun formatInstant(value: Instant, useUtc: Boolean): String {
    val zone = if (useUtc) ZoneOffset.UTC else ZoneId.systemDefault()
    val suffix = if (useUtc) " UTC" else " local"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(zone)
        .format(value) + suffix
}

private fun formatAge(ageMillis: Long): String = when {
    ageMillis < 0L -> "futuro ${-ageMillis} ms"
    ageMillis < 1_000L -> "$ageMillis ms"
    ageMillis < 60_000L -> "${ageMillis / 1_000}s"
    else -> "${ageMillis / 60_000}m"
}

private fun price(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun quantity(value: Double): String = String.format(Locale.US, "%.2f", value)
