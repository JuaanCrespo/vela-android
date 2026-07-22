package com.vela.android.lab.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Extra color tokens from the UX-0 spec that don't map cleanly to Material 3 semantic slots.
@Immutable
data class VelaExtendedColors(
    val safe: Color,
    val safeContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val blocked: Color,
    val blockedContainer: Color,
    val muted: Color,
    val cardStroke: Color,
    val bannerBrush: Brush,
) {
    companion object {
        // Radial-ish gradient built as a linear approximation of the "signal in dark space" mood.
        private val cockpitBrush: Brush = Brush.verticalGradient(
            listOf(Color(0xFF05111C), Color(0xFF071928), Color(0xFF04101A)),
        )

        fun forScheme(dark: Boolean): VelaExtendedColors = if (dark) {
            VelaExtendedColors(
                safe = Color(0xFF2DE2B7),
                safeContainer = Color(0xFF0E2E2A),
                warning = Color(0xFFFFD7AC),
                warningContainer = Color(0xFF2A1F10),
                blocked = Color(0xFFFFB4BA),
                blockedContainer = Color(0xFF331B1F),
                muted = Color(0xFF87AFC0),
                cardStroke = Color(0xFF1E4D63),
                bannerBrush = cockpitBrush,
            )
        } else {
            VelaExtendedColors(
                safe = Color(0xFF0F7A63),
                safeContainer = Color(0xFFB6F1E1),
                warning = Color(0xFF7A4C0F),
                warningContainer = Color(0xFFFFE5C7),
                blocked = Color(0xFF8A2E37),
                blockedContainer = Color(0xFFFFDADA),
                muted = Color(0xFF4F6377),
                cardStroke = Color(0xFFB5CBD6),
                bannerBrush = Brush.verticalGradient(
                    listOf(Color(0xFFF7FAFC), Color(0xFFE9F1F5)),
                ),
            )
        }
    }
}

val LocalVelaColors = compositionLocalOf { VelaExtendedColors.forScheme(dark = true) }

enum class VelaPillTone { Safe, Warning, Blocked, Neutral }

/**
 * Compact status chip used across the safety banner and card headers.
 * Purely presentational — no state, no side effects.
 */
@Composable
fun VelaStatusPill(
    label: String,
    tone: VelaPillTone = VelaPillTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVelaColors.current
    val (fg, bg) = when (tone) {
        VelaPillTone.Safe -> palette.safe to palette.safeContainer
        VelaPillTone.Warning -> palette.warning to palette.warningContainer
        VelaPillTone.Blocked -> palette.blocked to palette.blockedContainer
        VelaPillTone.Neutral -> palette.muted to MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(7.dp))
            .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Full-width safety banner. Always shows Mode, REAL locked, Paper-only, No live endpoint,
 * and Manual Paper submit compiled state. This is the non-negotiable safety strip from
 * `docs/vela-android-cockpit-ux-spec.md` §E rule 1–4.
 */
@Composable
fun VelaSafetyBanner(
    modeLabel: String,
    realLocked: Boolean,
    manualSubmitCompiled: Boolean?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVelaColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.bannerBrush, RoundedCornerShape(12.dp))
            .border(1.dp, palette.cardStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "VELA · cockpit",
            color = palette.safe,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Read-only lab · Paper-only · No LIVE",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VelaStatusPill(
                label = "Mode · $modeLabel",
                tone = if (modeLabel == "READ_ONLY") VelaPillTone.Safe else VelaPillTone.Warning,
            )
            VelaStatusPill(
                label = if (realLocked) "REAL locked" else "REAL UNLOCKED",
                tone = if (realLocked) VelaPillTone.Safe else VelaPillTone.Blocked,
            )
            VelaStatusPill(label = "Paper-only", tone = VelaPillTone.Safe)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VelaStatusPill(label = "No LIVE endpoint", tone = VelaPillTone.Safe)
            VelaStatusPill(label = "Auto Paper disabled", tone = VelaPillTone.Safe)
            val (compileLabel, compileTone) = when (manualSubmitCompiled) {
                null -> "Manual submit · N/A" to VelaPillTone.Neutral
                false -> "Manual submit compiled=false" to VelaPillTone.Safe
                true -> "Manual submit compiled=true" to VelaPillTone.Warning
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val (compactCompileLabel, compactCompileTone) = when (manualSubmitCompiled) {
            null -> "Manual submit compiled=N/A" to VelaPillTone.Neutral
            false -> "Manual submit compiled=false" to VelaPillTone.Safe
            true -> "Manual submit compiled=true" to VelaPillTone.Warning
        }
        VelaStatusPill(label = compactCompileLabel, tone = compactCompileTone)
    }
}

/**
 * Section header used to visually group a run of cards (Estado / Mercado / Paper / Riesgo /
 * Auditoría / Manual submit). Purely presentational.
 */
@Composable
fun VelaSectionHeader(
    title: String,
    subtitle: String? = null,
    tone: VelaPillTone = VelaPillTone.Neutral,
    trailingPill: String? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVelaColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = title.uppercase(),
                color = palette.safe,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (trailingPill != null) {
            VelaStatusPill(label = trailingPill, tone = tone)
        }
    }
}

/**
 * A visually isolated container for the single mutating action on the dashboard
 * (Manual Paper submit — one-shot). Renders a warning-tinted border and a header pill.
 */
@Composable
fun VelaActionZone(
    title: String,
    subtitle: String,
    armed: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = LocalVelaColors.current
    val strokeColor = if (armed) palette.blocked else palette.warning
    val containerColor = if (armed) palette.blockedContainer else palette.warningContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(2.dp, strokeColor, RoundedCornerShape(12.dp))
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = title,
                        color = strokeColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    VelaStatusPill(
                        label = if (armed) "ARMED" else "SAFE",
                        tone = if (armed) VelaPillTone.Blocked else VelaPillTone.Safe,
                    )
                }
                Text(
                    text = subtitle,
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            content()
        }
    }
}

/**
 * Visual list of gate blockers, styled as chips.
 * Consumes existing state; adds no new signal.
 */
@Composable
fun VelaBlockedReasonList(
    reasons: List<String>,
    modifier: Modifier = Modifier,
) {
    if (reasons.isEmpty()) return
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Gate reasons",
            color = LocalVelaColors.current.blocked,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            reasons.take(3).forEach { reason ->
                VelaStatusPill(label = reason, tone = VelaPillTone.Blocked)
            }
        }
        if (reasons.size > 3) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.drop(3).take(3).forEach { reason ->
                    VelaStatusPill(label = reason, tone = VelaPillTone.Blocked)
                }
            }
        }
    }
}

/**
 * Compact metric tile used inside the safety banner and dashboard tiles.
 * Purely presentational.
 */
@Composable
fun VelaMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: VelaPillTone = VelaPillTone.Neutral,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
) {
    val palette = LocalVelaColors.current
    val accent = when (tone) {
        VelaPillTone.Safe -> palette.safe
        VelaPillTone.Warning -> palette.warning
        VelaPillTone.Blocked -> palette.blocked
        VelaPillTone.Neutral -> palette.muted
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, palette.cardStroke, RoundedCornerShape(10.dp))
            .padding(contentPadding),
    ) {
        Text(
            text = label,
            color = palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Compact, explicit empty state used by read-only screens. */
@Composable
fun VelaEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Freshness badge with a text label so state is never conveyed by color alone. */
@Composable
fun VelaFreshnessBadge(
    label: String,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    VelaStatusPill(
        label = label,
        tone = if (fresh) VelaPillTone.Safe else VelaPillTone.Warning,
        modifier = modifier,
    )
}

/** A visual-only preference row. It never owns or persists state. */
@Composable
fun VelaSettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Locked setting shown for safety-relevant values that UX-2 cannot edit. */
@Composable
fun VelaReadOnlySetting(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
