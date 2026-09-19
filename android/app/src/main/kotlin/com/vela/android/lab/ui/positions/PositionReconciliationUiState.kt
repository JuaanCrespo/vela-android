package com.vela.android.lab.ui.positions

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import com.vela.android.lab.data.paper.reconciliation.integration.*

enum class PositionRefreshState { IDLE, REFRESHING, SUCCESS, FAILED, BLOCKED }
enum class PositionUiError { LOCAL_READ_FAILED, REFRESH_BLOCKED, BUSY, BROKER_READ_FAILED,
    CAPTURE_PERSISTENCE_FAILED, RECONCILIATION_PERSISTENCE_FAILED, ANCHOR_CREATE_FAILED, ANCHOR_INVALIDATION_FAILED }

sealed interface PositionBaselineDialog {
    data class Establish(val selection: BaselineSelection) : PositionBaselineDialog
    data class Invalidate(val anchor: StoredPositionAnchor) : PositionBaselineDialog
}

data class PositionReconciliationUiState(
    val loadingLocal: Boolean = false,
    val workingLocal: Boolean = false,
    val refreshState: PositionRefreshState = PositionRefreshState.IDLE,
    val durable: DurablePositionOverview = DurablePositionOverview(),
    val rows: List<PositionRowPresentation> = emptyList(),
    val freshness: SnapshotFreshness = SnapshotFreshness.UNKNOWN,
    val selectedBaseline: BaselineSelection? = null,
    val dialog: PositionBaselineDialog? = null,
    val diagnosticsExpanded: Boolean = false,
    val error: PositionUiError? = null,
) {
    val busy: Boolean get() = loadingLocal || workingLocal || refreshState == PositionRefreshState.REFRESHING
    val canRefresh: Boolean get() = !busy && dialog == null
    val canEstablish: Boolean get() = !busy && dialog == null && error == null && selectedBaseline?.eligible == true
}

fun safePaperAccountLabel(accountRef: String?): String =
    if (accountRef?.matches(Regex("paper-v1:[0-9a-f]{64}")) == true) {
        val digest = accountRef.removePrefix("paper-v1:")
        "${digest.take(6)}…${digest.takeLast(6)}"
    } else "UNKNOWN"

fun quantityText(quantity: QuantityEvidence): String = quantity.quantity?.toString() ?: "UNKNOWN"

data class PositionRowPresentation(
    val symbol: String,
    val brokerObserved: String,
    val knownVelaDelta: String,
    val knownDeltaComplete: Boolean,
    val baseline: String,
    val expected: String,
    val difference: String,
    val state: PositionReconciliationState,
    val diagnostics: List<String>,
    val provenance: String,
    val anchorId: String?,
) {
    val explanation: String get() = when (state) {
        PositionReconciliationState.MISMATCH -> "Diferencia de posición sin explicación. Causa: UNKNOWN. Revisar baseline."
        PositionReconciliationState.UNANCHORED -> "Sin baseline: el delta conocido no es la posición esperada."
        PositionReconciliationState.ANCHOR_INVALID -> "Baseline no válido para comparar."
        else -> state.name
    }
}

/** Presentation of a saved report only. Never runs an engine or persists an observation. */
fun positionRows(overview: DurablePositionOverview, now: Long, policy: PositionObservationPolicy): List<PositionRowPresentation> {
    val stored = overview.latestReport
    if (stored == null) return overview.latestComplete?.positions.orEmpty().map {
        PositionRowPresentation(it.symbol, DecimalQuantity.parse(it.qtyCanonicalDecimal).toString(), "UNKNOWN", false,
            "UNKNOWN", "UNKNOWN", "NOT COMPARABLE", PositionReconciliationState.UNKNOWN,
            listOf("NO_DURABLE_REPORT"), it.decimalProvenance, null)
    }
    // A report stays bound to its OWN snapshot, never the newer/failed attempt displayed above it.
    val capturedAt = overview.reportCapturedAt ?: overview.latestComplete?.takeIf {
        it.metadata.snapshotId == stored.metadata.brokerSnapshotId
    }?.metadata?.completedAtEpochMillis
    val age = capturedAt?.let { policy.freshness(it, now) } ?: SnapshotFreshness.UNKNOWN
    return stored.report.rows.map { row ->
        val referenced = overview.anchors.singleOrNull { it.metadata.anchorId == row.anchorId }
        val active = overview.anchors.singleOrNull { it.metadata.symbol == row.symbol &&
            it.metadata.accountRef == stored.metadata.accountRef && it.metadata.status == AnchorStatus.ACTIVE.name }
        val invalid = row.anchorId != null && referenced?.metadata?.status != AnchorStatus.ACTIVE.name
        val changed = active != null && active.metadata.anchorId != row.anchorId
        val currentState = when {
            row.state == PositionReconciliationState.INCONSISTENT_LOCAL_HISTORY -> row.state
            invalid -> PositionReconciliationState.ANCHOR_INVALID
            changed -> PositionReconciliationState.UNKNOWN
            row.state in setOf(PositionReconciliationState.ANCHOR_INVALID, PositionReconciliationState.BROKER_READ_FAILED) -> row.state
            age == SnapshotFreshness.STALE -> PositionReconciliationState.STALE
            age == SnapshotFreshness.UNKNOWN -> PositionReconciliationState.UNKNOWN
            else -> row.state
        }
        val comparable = currentState in setOf(PositionReconciliationState.MATCH, PositionReconciliationState.MISMATCH)
        PositionRowPresentation(row.symbol, quantityText(row.brokerQty), quantityText(row.knownVelaDelta), row.knownDeltaComplete,
            quantityText(row.anchorQty), if (invalid || changed || currentState == PositionReconciliationState.UNANCHORED) "UNKNOWN" else quantityText(row.expectedQty),
            if (comparable) row.difference?.toString() ?: "NOT COMPARABLE" else "NOT COMPARABLE", currentState,
            row.diagnostics.map { it.name }.sorted() + when { invalid -> listOf("ANCHOR_INVALID"); changed -> listOf("BASELINE_CHANGED_REFRESH_REQUIRED"); else -> emptyList() },
            "broker=${row.brokerQty.provenance}; delta=${row.knownVelaDelta.provenance}; baseline=${row.anchorQty.provenance}; expected=${row.expectedQty.provenance}",
            row.anchorId)
    }
}
