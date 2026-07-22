package com.vela.android.lab.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.android.lab.ui.theme.VelaPillTone
import com.vela.android.lab.ui.theme.VelaSectionHeader
import com.vela.android.lab.ui.theme.VelaStatusPill

/**
 * IDs form a reviewable contract: only visual/experience preferences are
 * editable. Safety, endpoints, and execution boundaries never appear here.
 */
object VelaSettingsContract {
    val editablePreferenceIds: Set<String> = setOf(
        "density",
        "candle_count",
        "default_symbol",
        "advanced_diagnostics",
        "remember_last_section",
        "time_format",
    )

    val readOnlySafetyIds: Set<String> = setOf(
        "mode",
        "real_locked",
        "paper_only",
        "live_forbidden",
        "auto_paper_disabled",
        "manual_submit_compiled",
        "paper_endpoint",
        "clock_status",
        "build_version",
    )
}

/**
 * Stateless settings screen. Persistence and all operational behavior remain in
 * the caller; selecting a preference only invokes the matching callback.
 */
@Composable
fun VelaSettingsScreen(
    state: VelaPreferencesState,
    availableSymbols: Collection<String>,
    modeLabel: String,
    realLocked: Boolean,
    manualSubmitCompiled: Boolean,
    clockStatus: String,
    buildVersion: String,
    onDensitySelected: (VelaUiDensity) -> Unit,
    onCandleCountSelected: (VelaCandleCount) -> Unit,
    onDefaultSymbolSelected: (String) -> Unit,
    onAdvancedDiagnosticsChanged: (Boolean) -> Unit,
    onRememberLastSectionChanged: (Boolean) -> Unit,
    onTimeFormatSelected: (VelaTimeFormat) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    paperEndpointLabel: String = "paper-api.alpaca.markets",
    modifier: Modifier = Modifier,
    credentialsContent: @Composable () -> Unit = {},
) {
    val preferences = state.preferences
    val symbols = VelaPreferencePolicy.normalizedAvailableSymbols(availableSymbols)
    val spacing = when (preferences.density) {
        VelaUiDensity.COMPACT -> 8.dp
        VelaUiDensity.COMFORTABLE -> 12.dp
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        VelaSectionHeader(
            title = "Configuración",
            subtitle = "Preferencias visuales locales y seguridad read-only",
            trailingPill = if (state.isLoaded) "LOCAL" else "LOADING",
            tone = if (state.isLoaded) VelaPillTone.Safe else VelaPillTone.Neutral,
        )

        if (!state.isLoaded) {
            VelaStatusPill(
                label = "Cargando preferencias locales…",
                tone = VelaPillTone.Neutral,
            )
        }

        SettingsGroup(title = "Apariencia y experiencia") {
            Text(
                text = "Densidad",
                style = MaterialTheme.typography.titleSmall,
            )
            EnumChoice(
                options = listOf(
                    VelaUiDensity.COMPACT to "Compacta",
                    VelaUiDensity.COMFORTABLE to "Cómoda",
                ),
                selected = preferences.density,
                enabled = state.isLoaded,
                onSelected = onDensitySelected,
            )

            Text(
                text = "Cantidad predeterminada de velas",
                style = MaterialTheme.typography.titleSmall,
            )
            EnumChoice(
                options = VelaCandleCount.entries.map { count ->
                    count to count.count.toString()
                },
                selected = preferences.candleCount,
                enabled = state.isLoaded,
                onSelected = onCandleCountSelected,
            )

            VelaSettingRow(
                label = "Símbolo visual predeterminado",
                supportingText = "Se limita a la watchlist existente; no inicia streams.",
            ) {
                Text(
                    text = preferences.defaultSymbolFor(symbols),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (symbols.isEmpty()) {
                Text(
                    text = "Watchlist vacía · se conserva SPY como valor visual seguro.",
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
                        OutlinedButton(
                            onClick = { onDefaultSymbolSelected(symbol) },
                            enabled = state.isLoaded,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription =
                                        "Usar $symbol como símbolo visual predeterminado"
                                },
                        ) {
                            Text(symbol)
                        }
                    }
                }
            }

            ToggleSetting(
                label = "Diagnósticos avanzados",
                supportingText = "Mostrar u ocultar detalle técnico local.",
                checked = preferences.advancedDiagnostics,
                enabled = state.isLoaded,
                onCheckedChange = onAdvancedDiagnosticsChanged,
            )
            ToggleSetting(
                label = "Recordar última sección",
                supportingText = "Sólo restaura destinos allowlisted; Submit no es un destino.",
                checked = preferences.rememberLastSection,
                enabled = state.isLoaded,
                onCheckedChange = onRememberLastSectionChanged,
            )

            Text(
                text = "Formato horario",
                style = MaterialTheme.typography.titleSmall,
            )
            EnumChoice(
                options = listOf(
                    VelaTimeFormat.LOCAL to "Local",
                    VelaTimeFormat.UTC to "UTC",
                ),
                selected = preferences.timeFormat,
                enabled = state.isLoaded,
                onSelected = onTimeFormatSelected,
            )
        }

        SettingsGroup(title = "Seguridad y conexión") {
            VelaReadOnlySetting(
                label = "Mode",
                value = modeLabel,
                tone = if (modeLabel == "READ_ONLY") VelaPillTone.Safe else VelaPillTone.Warning,
            )
            VelaReadOnlySetting(
                label = "REAL",
                value = if (realLocked) "locked" else "UNLOCKED",
                tone = if (realLocked) VelaPillTone.Safe else VelaPillTone.Blocked,
            )
            VelaReadOnlySetting("Paper-only", "true", VelaPillTone.Safe)
            VelaReadOnlySetting("LIVE endpoint", "forbidden", VelaPillTone.Safe)
            VelaReadOnlySetting("Auto Paper", "disabled", VelaPillTone.Safe)
            VelaReadOnlySetting(
                label = "Manual submit compiled",
                value = manualSubmitCompiled.toString(),
                tone = if (manualSubmitCompiled) VelaPillTone.Warning else VelaPillTone.Safe,
            )
            VelaReadOnlySetting("Paper endpoint permitido", paperEndpointLabel)
            VelaReadOnlySetting("Clock", clockStatus)
            VelaReadOnlySetting("Build", buildVersion)
        }

        // The existing secure credentials card is supplied by integration so
        // its encrypted provider and callbacks are reused rather than copied.
        credentialsContent()

        Spacer(modifier = Modifier.height(spacing))
    }
}

@Composable
fun VelaSettingRow(
    label: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun VelaReadOnlySetting(
    label: String,
    value: String,
    tone: VelaPillTone = VelaPillTone.Neutral,
    modifier: Modifier = Modifier,
) {
    VelaSettingRow(
        label = label,
        supportingText = "Bloqueado · sólo lectura",
        modifier = modifier.semantics {
            contentDescription = "$label: $value, sólo lectura"
        },
    ) {
        VelaStatusPill(label = value, tone = tone)
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun ToggleSetting(
    label: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    VelaSettingRow(
        label = label,
        supportingText = supportingText,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun <T> EnumChoice(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected == value,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) },
                    )
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = null,
                    enabled = enabled,
                )
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
