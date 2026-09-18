package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic

enum class PositionSide { BUY, SELL }
enum class FillCompleteness { UNKNOWN, OPEN, TERMINAL, INCONSISTENT }
enum class PositionIntegrity { RELIABLE, UNCERTAIN, INCONSISTENT }
enum class HistoryCompleteness { COMPLETE, INCOMPLETE }
enum class PositionCoverage { UNANCHORED, ANCHORED, UNCERTAIN, ANCHOR_INVALID }

enum class PositionDiagnostic {
    CANONICAL_HISTORY_INCONSISTENT, AMBIGUOUS_IDENTITY, DUPLICATE_IDENTITY,
    INVALID_SYMBOL, INVALID_SIDE, INVALID_QUANTITY, MISSING_QUANTITY, EXCESS_FILL,
    DECREASING_FILL, TERMINAL_CHANGED, INVALID_LIFECYCLE, UNKNOWN_LIFECYCLE,
    INVALID_SEQUENCE, CURRENT_LIFECYCLE_MISMATCH, INVALID_BROKER_EVIDENCE,
    SYNTHETIC_ZERO_NOT_EVIDENCE, MISSING_BROKER_FILL, TERMINAL_QUANTITY_UNKNOWN,
    DECIMAL_EVIDENCE_MISMATCH, LEGACY_PRECISION, REPEATED_OBSERVATION,
    OPEN_ORDER, INCOMPLETE_HISTORY, UNSCOPED_HISTORY, ACCOUNT_CHANGED,
    INVALID_ANCHOR, UNCONFIRMED_CUT, MISSING_CURSOR, INVALID_CURSOR,
    LOCAL_HISTORY_CHANGED, COVERAGE_LOST, BROKER_READ_FAILED, BROKER_SNAPSHOT_INVALID,
    BROKER_SNAPSHOT_PARTIAL, BROKER_SNAPSHOT_STALE, BROKER_FRESHNESS_UNKNOWN,
    BROKER_LOCAL_CUT_UNCONFIRMED,
    BROKER_SYMBOL_ABSENT, UNEXPLAINED_POSITION_DIFFERENCE, ANCHOR_SHOULD_INVALIDATE,
}

data class PositionOrderIdentity(
    val attemptId: String,
    val orderId: String?,
    val clientOrderId: String?,
    val orderSequenceId: Long?,
    val symbol: String?,
    val side: String?,
)

/** Caller-supplied exact evidence, bound to an existing canonical observation, not a parser. */
data class DecimalFillObservation(val payloadFingerprint: String, val filledQuantity: QuantityEvidence)
data class OrderDecimalEvidence(
    val identity: PositionOrderIdentity,
    val requestedQuantity: QuantityEvidence,
    val observations: Map<Long, DecimalFillObservation>,
)

data class RealizedOrderFill(
    val identity: PositionOrderIdentity,
    val symbol: String?,
    val side: PositionSide?,
    val requestedQty: QuantityEvidence,
    val realizedQty: QuantityEvidence,
    val signedRealizedQty: QuantityEvidence,
    val completeness: FillCompleteness,
    val integrity: PositionIntegrity,
    val diagnostics: Set<PositionDiagnostic>,
    val canonicalDiagnostics: Set<PaperHistoryIntegrityDiagnostic>,
    val observationQuantities: Map<Long, QuantityEvidence>,
)

enum class AnchorStatus { ACTIVE, INVALIDATED, SUPERSEDED }
enum class AnchorInvalidationReason {
    ACCOUNT_CHANGED, MANUAL, LOCAL_HISTORY_CHANGED, LOCAL_HISTORY_INCONSISTENT,
    UNEXPLAINED_POSITION_DIFFERENCE, COVERAGE_LOST, UNKNOWN,
}
enum class CutAssurance { CONFIRMED, UNKNOWN }
data class AnchorCoverageCut(
    val orderSequenceInclusive: Long,
    val lifecycleSequenceInclusive: Long,
    val assurance: CutAssurance,
)
data class AnchorOrderCursor(
    val identity: PositionOrderIdentity,
    val includedFilledQty: QuantityEvidence,
    val observationSequence: Long,
    val payloadFingerprint: String,
)
/** Abstract accepted input only. This phase cannot establish a real account baseline. */
data class PositionAnchor(
    val anchorId: String,
    val symbol: String,
    val accountRef: String,
    val baselineQty: QuantityEvidence,
    val cut: AnchorCoverageCut,
    val cursors: List<AnchorOrderCursor>,
    val status: AnchorStatus,
    val createdAtEpochMillis: Long,
    val invalidatedAtEpochMillis: Long? = null,
    val invalidationReason: AnchorInvalidationReason? = null,
)

/** COMPLETE is a caller assertion about the entire scoped history, never a latest-N window. */
data class PositionHistoryInput(
    val accountRef: String?,
    val histories: List<CanonicalPaperOrderHistory>,
    val completeness: HistoryCompleteness,
    val decimalEvidence: Map<String, OrderDecimalEvidence> = emptyMap(),
)
data class LocalSymbolPositionState(
    val symbol: String,
    /** May be a verified subtotal; deltaComplete must be displayed with it. */
    val knownVelaFillDelta: QuantityEvidence,
    val deltaComplete: Boolean,
    val expectedAbsoluteQty: QuantityEvidence,
    val coverage: PositionCoverage,
    val contributingOrderCount: Int,
    val hasOpenExposure: Boolean,
    val integrity: PositionIntegrity,
    val anchor: PositionAnchor?,
    val diagnostics: Set<PositionDiagnostic>,
    val orderFills: List<RealizedOrderFill>,
)
data class LocalExpectedPositionState(
    val accountRef: String?,
    val positions: List<LocalSymbolPositionState>,
    val globalDiagnostics: Set<PositionDiagnostic>,
)

enum class BrokerSnapshotCompleteness { COMPLETE, PARTIAL, INVALID, FAILED }
enum class SnapshotFreshness { FRESH, STALE, UNKNOWN }
enum class BrokerPositionSide { LONG, SHORT, FLAT }
data class BrokerPositionQuantity(
    val symbol: String,
    /** Already signed: long positive, short negative, flat zero. */
    val quantity: QuantityEvidence,
    val side: BrokerPositionSide,
)
/** No transport, storage, market values or clock lookup. Freshness is supplied separately. */
data class BrokerPositionSnapshot(
    val snapshotId: String,
    val accountRef: String?,
    val captureStartedAtEpochMillis: Long,
    val capturedAtEpochMillis: Long,
    val completeness: BrokerSnapshotCompleteness,
    val positions: List<BrokerPositionQuantity>,
    /** Independent of freshness. A recent response alone cannot prove alignment with local fills. */
    val localEvidenceAlignment: CutAssurance = CutAssurance.UNKNOWN,
)
enum class PositionReconciliationState {
    MATCH, MISMATCH, UNANCHORED, UNKNOWN, STALE,
    INCONSISTENT_LOCAL_HISTORY, ANCHOR_INVALID, BROKER_READ_FAILED,
}
enum class PositionPresence { BOTH, BROKER_ONLY, LOCAL_ONLY, NEITHER }
enum class PositionDifferenceCause { UNKNOWN }
data class PositionReconciliationRow(
    val symbol: String,
    val brokerQty: QuantityEvidence,
    val knownVelaDelta: QuantityEvidence,
    val knownDeltaComplete: Boolean,
    val anchorQty: QuantityEvidence,
    val expectedQty: QuantityEvidence,
    val difference: DecimalQuantity?,
    val state: PositionReconciliationState,
    val presence: PositionPresence,
    val diagnostics: Set<PositionDiagnostic>,
    val anchorId: String?,
    val cause: PositionDifferenceCause? = null,
)
data class PositionReconciliationSummary(
    val matchedCount: Int,
    val mismatchedCount: Int,
    val unanchoredCount: Int,
    val unknownCount: Int,
)
data class PositionReconciliationReport(
    val snapshotId: String,
    val rows: List<PositionReconciliationRow>,
    val summary: PositionReconciliationSummary,
    /** Global errors remain visible even if there are no symbols/rows. */
    val diagnostics: Set<PositionDiagnostic>,
)

internal fun validPositionSymbol(symbol: String?): Boolean =
    symbol != null && symbol.matches(Regex("^[A-Z][A-Z0-9.-]{0,31}$"))

internal fun CanonicalPaperOrderHistory.positionIdentity() = PositionOrderIdentity(
    submitAttemptId, alpacaOrderId, clientOrderId, orderSequenceId, symbol, side,
)
