package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus

enum class PositionCoverageMode { EXACT_CURSORS_V1, LEGACY_BOOTSTRAP_V1 }
enum class PreAnchorHistoryAssurance { LEGACY_UNKNOWN }
enum class AbsorbedHistoryClassification { TERMINAL_ABSORBED, NO_BROKER_SUBMISSION, UNRELATED_SYMBOL }

data class BootstrapHistoryEntry(
    val history: CanonicalPaperOrderHistory,
    val classification: AbsorbedHistoryClassification,
    val exactRequestedAtCut: QuantityEvidence? = null,
    val exactTerminalAtCut: QuantityEvidence? = null,
)

/** Frozen source evidence, NOT exact quantities reconstructed from old observations. */
data class BootstrapCutManifest(
    val manifestVersion: Int = 1,
    val coverageMode: PositionCoverageMode = PositionCoverageMode.LEGACY_BOOTSTRAP_V1,
    val brokerSnapshotId: String,
    val brokerSequence: Long,
    val accountRef: String,
    val symbol: String,
    val baselineQty: QuantityEvidence,
    val localHistoryDigest: String,
    val submitAuditHighWater: Long,
    val lifecycleHighWater: Long,
    val orderHighWater: Long,
    val capturedAt: Long,
    val acceptedAt: Long,
    val inventory: List<BootstrapHistoryEntry>,
    val preAnchorHistoryAssurance: PreAnchorHistoryAssurance = PreAnchorHistoryAssurance.LEGACY_UNKNOWN,
)

data class PositionCoverageMetadata(
    val coverageMode: PositionCoverageMode,
    val preAnchorHistoryAssurance: PreAnchorHistoryAssurance,
    val postAnchorCoverageAssurance: CutAssurance,
    val historicalKnownVelaDelta: QuantityEvidence,
    val postAnchorExactDelta: QuantityEvidence,
    val bootstrapSnapshotId: String,
    val bootstrapCutDigest: String,
)

class BootstrapEligibilityException(val diagnostic: PositionDiagnostic) : IllegalArgumentException(diagnostic.name)

/** Pure, conservative V1 policy. Never calls an executor or makes a historical quantity exact. */
object BootstrapCoveragePolicy {
    private val deriver = OrderRealizedFillDeriver()

    fun inventory(histories: List<CanonicalPaperOrderHistory>, symbol: String,
        decimals: Map<String, OrderDecimalEvidence> = emptyMap()): List<BootstrapHistoryEntry> {
        globalCheck(histories)
        return histories.sortedBy { it.submitAttemptId }.map { history ->
            val classification = when {
                history.symbol != symbol -> AbsorbedHistoryClassification.UNRELATED_SYMBOL
                noSubmission(history) -> AbsorbedHistoryClassification.NO_BROKER_SUBMISSION
                else -> {
                    val fill = deriver.derive(history)
                    if (history.ambiguous || !history.resolved || history.unresolved ||
                        fill.integrity != PositionIntegrity.RELIABLE || fill.completeness != FillCompleteness.TERMINAL
                    ) throw BootstrapEligibilityException(PositionDiagnostic.BOOTSTRAP_OPEN_OR_UNCERTAIN_ORDER)
                    AbsorbedHistoryClassification.TERMINAL_ABSORBED
                }
            }
            val exact = decimals[history.submitAttemptId]?.takeIf { classification == AbsorbedHistoryClassification.TERMINAL_ABSORBED }
            if (exact != null && deriver.derive(history, exact, exactSourceProjection = true).integrity == PositionIntegrity.INCONSISTENT) {
                throw BootstrapEligibilityException(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH)
            }
            BootstrapHistoryEntry(history, classification, exact?.requestedQuantity,
                exact?.observations?.get(history.currentLifecycle?.databaseId)?.filledQuantity)
        }
    }

    private fun noSubmission(history: CanonicalPaperOrderHistory): Boolean =
        history.localSubmitResult == "BLOCKED" && history.alpacaOrderId == null &&
            history.lifecycleObservations.isEmpty() && !history.ambiguous &&
            history.integrityDiagnostics.none { !it.warningOnly }

    private fun globalCheck(histories: List<CanonicalPaperOrderHistory>) {
        if (histories.any { !validPositionSymbol(it.symbol) }) throw BootstrapEligibilityException(PositionDiagnostic.UNSCOPED_HISTORY)
        val identityErrors = setOf(PaperHistoryIntegrityDiagnostic.SOURCE_IDENTITY_MISMATCH,
            PaperHistoryIntegrityDiagnostic.AMBIGUOUS_RECONCILIATION, PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY,
            PaperHistoryIntegrityDiagnostic.LIFECYCLE_IDENTITY_MISMATCH, PaperHistoryIntegrityDiagnostic.RECONCILIATION_MISSING,
            PaperHistoryIntegrityDiagnostic.MISSING_AUDIT_LINKAGE)
        if (histories.any { it.submitAttemptId.isBlank() || it.mappingState != "EXACT" || it.integrityDiagnostics.any(identityErrors::contains) }) {
            throw BootstrapEligibilityException(PositionDiagnostic.AMBIGUOUS_IDENTITY)
        }
        val keys = listOf<(CanonicalPaperOrderHistory) -> String?>(
            { it.submitAttemptId }, { it.alpacaOrderId }, { it.clientOrderId }, { it.orderSequenceId?.toString() })
        if (keys.any { key -> histories.mapNotNull(key).filter { it.isNotBlank() }.let { it.distinct().size != it.size } } ||
            histories.flatMap { it.lifecycleObservations }.map { it.databaseId }.let { it.distinct().size != it.size }
        ) throw BootstrapEligibilityException(PositionDiagnostic.DUPLICATE_IDENTITY)
    }

    fun evaluate(input: PositionHistoryInput, anchor: PositionAnchor, anchorCount: Int): LocalSymbolPositionState {
        val manifest = anchor.bootstrapCut
        val diagnostics = linkedSetOf<PositionDiagnostic>()
        var historical = QuantityEvidence.UNKNOWN
        var postDelta = QuantityEvidence.UNKNOWN
        var postFills = emptyList<RealizedOrderFill>()
        var valid = true
        try {
            require(manifest != null && manifest.manifestVersion == 1 && manifest.coverageMode == anchor.coverageMode)
            require(anchorCount == 1 && anchor.status == AnchorStatus.ACTIVE && anchor.invalidatedAtEpochMillis == null && anchor.invalidationReason == null)
            require(anchor.cursors.isEmpty() && anchor.anchorId.isNotBlank() && anchor.baselineQty.exact)
            require(manifest.accountRef == anchor.accountRef && input.accountRef == anchor.accountRef && anchor.accountRef.matches(Regex("paper-v1:[0-9a-f]{64}")))
            require(manifest.symbol == anchor.symbol && manifest.baselineQty == anchor.baselineQty && manifest.brokerSnapshotId.isNotBlank())
            require(manifest.brokerSequence > 0 && manifest.localHistoryDigest.matches(Regex("[0-9a-f]{64}")))
            require(anchor.bootstrapCutDigest?.matches(Regex("[0-9a-f]{64}")) == true)
            require(manifest.acceptedAt == anchor.createdAtEpochMillis && manifest.capturedAt >= 0 && manifest.acceptedAt >= manifest.capturedAt)
            require(anchor.cut.assurance == CutAssurance.CONFIRMED && anchor.cut.orderSequenceInclusive == manifest.orderHighWater &&
                anchor.cut.lifecycleSequenceInclusive == manifest.lifecycleHighWater)
            require(manifest.submitAuditHighWater >= manifest.orderHighWater && manifest.lifecycleHighWater >= 0 && manifest.orderHighWater >= 0)
            require(inventory(manifest.inventory.map { it.history }, anchor.symbol).map { it.copy(exactRequestedAtCut = null, exactTerminalAtCut = null) } ==
                manifest.inventory.map { it.copy(exactRequestedAtCut = null, exactTerminalAtCut = null) })
            require(manifest.inventory.all { (it.exactRequestedAtCut == null || it.exactRequestedAtCut.exact) &&
                (it.exactTerminalAtCut == null || it.exactTerminalAtCut.exact) })
            globalCheck(input.histories)
            val original = manifest.inventory.associateBy { it.history.submitAttemptId }
            val historicalFills = manifest.inventory.filter { it.classification == AbsorbedHistoryClassification.TERMINAL_ABSORBED }
                .map { deriver.derive(it.history) }
            historical = sumEvidence(historicalFills.map { it.signedRealizedQty })
            if (historicalFills.any { PositionDiagnostic.LEGACY_PRECISION in it.diagnostics }) diagnostics += PositionDiagnostic.LEGACY_PRECISION
            manifest.inventory.forEach { entry ->
                val old = entry.history
                val current = input.histories.singleOrNull { it.submitAttemptId == old.submitAttemptId }
                    ?: throw BootstrapEligibilityException(PositionDiagnostic.COVERAGE_LOST)
                if (current.positionIdentity() != old.positionIdentity()) throw BootstrapEligibilityException(PositionDiagnostic.AMBIGUOUS_IDENTITY)
                if (entry.classification != AbsorbedHistoryClassification.UNRELATED_SYMBOL) {
                    val prefix = current.lifecycleObservations.filter { it.databaseId <= manifest.lifecycleHighWater }
                    val later = current.lifecycleObservations.filter { it.databaseId > manifest.lifecycleHighWater }
                    val sameRecord = old.copy(lifecycleObservations = current.lifecycleObservations, currentLifecycle = current.currentLifecycle,
                        resetAcknowledgedAtEpochMillis = current.resetAcknowledgedAtEpochMillis,
                        integrityStatus = current.integrityStatus, integrityDiagnostics = current.integrityDiagnostics) == current
                    val terminal = old.currentLifecycle
                    val duplicatesOnly = later.all { next -> terminal != null && next.payloadFingerprint == terminal.payloadFingerprint &&
                        next.copy(databaseId = terminal.databaseId, observedAtEpochMillis = terminal.observedAtEpochMillis,
                            samePayloadAsPrevious = terminal.samePayloadAsPrevious) == terminal }
                    val decimals = input.decimalEvidence[old.submitAttemptId]
                    // New raw precision is not proof of an identical old raw payload. Never equate it through a lossy projection.
                    val exactDuplicates = later.all { observation ->
                        val exact = decimals?.observations?.get(observation.databaseId)
                        exact == null || (entry.exactTerminalAtCut != null && entry.exactRequestedAtCut != null &&
                            exact.filledQuantity == entry.exactTerminalAtCut && decimals.requestedQuantity == entry.exactRequestedAtCut)
                    }
                    if (!sameRecord || prefix != old.lifecycleObservations || !duplicatesOnly || !exactDuplicates ||
                        current.integrityStatus == PaperHistoryIntegrityStatus.INCONSISTENT || current.integrityDiagnostics.any { !it.warningOnly } ||
                        (decimals != null && deriver.derive(current, decimals, exactSourceProjection = true).integrity == PositionIntegrity.INCONSISTENT)) {
                        throw BootstrapEligibilityException(PositionDiagnostic.BOOTSTRAP_CROSS_CUT_EVIDENCE)
                    }
                }
            }
            val newHistories = input.histories.filter { it.submitAttemptId !in original && it.symbol == anchor.symbol }
            postFills = newHistories.filterNot(::noSubmission).map { history ->
                if (history.auditStartRowId == null || history.auditStartRowId <= manifest.submitAuditHighWater ||
                    history.orderSequenceId == null || history.orderSequenceId <= manifest.orderHighWater ||
                    history.lifecycleObservations.any { it.databaseId <= manifest.lifecycleHighWater }) {
                    throw BootstrapEligibilityException(PositionDiagnostic.BOOTSTRAP_CROSS_CUT_EVIDENCE)
                }
                val decimal = input.decimalEvidence[history.submitAttemptId]
                    ?: throw BootstrapEligibilityException(PositionDiagnostic.BOOTSTRAP_POST_EVIDENCE_MISSING)
                val fill = deriver.derive(history, decimal, exactSourceProjection = true)
                if (!history.resolved || history.unresolved || history.localSubmitResult != "SUBMITTED" ||
                    fill.integrity != PositionIntegrity.RELIABLE || fill.completeness != FillCompleteness.TERMINAL ||
                    !fill.realizedQty.exact || PositionDiagnostic.LEGACY_PRECISION in fill.diagnostics) {
                    throw BootstrapEligibilityException(PositionDiagnostic.BOOTSTRAP_POST_EVIDENCE_MISSING)
                }
                fill
            }
            postDelta = sumEvidence(postFills.map { it.signedRealizedQty })
        } catch (failure: IllegalArgumentException) {
            valid = false
            diagnostics += (failure as? BootstrapEligibilityException)?.diagnostic ?: PositionDiagnostic.INVALID_BOOTSTRAP_MANIFEST
        }
        val coverage = PositionCoverageMetadata(PositionCoverageMode.LEGACY_BOOTSTRAP_V1, PreAnchorHistoryAssurance.LEGACY_UNKNOWN,
            if (valid) CutAssurance.CONFIRMED else CutAssurance.UNKNOWN, historical, postDelta,
            manifest?.brokerSnapshotId.orEmpty(), anchor.bootstrapCutDigest.orEmpty())
        return LocalSymbolPositionState(anchor.symbol, historical, false,
            if (valid) sumEvidence(listOf(anchor.baselineQty, postDelta)) else QuantityEvidence.UNKNOWN,
            if (valid) PositionCoverage.ANCHORED else PositionCoverage.ANCHOR_INVALID,
            postFills.size, !valid, if (valid) PositionIntegrity.RELIABLE else PositionIntegrity.UNCERTAIN,
            anchor, diagnostics, postFills, coverage)
    }
}
