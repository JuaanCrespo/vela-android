package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import java.time.Instant
import java.util.Locale

/** Reads canonical evidence only; no consolidation, repair or use of the legacy FILLED shortcut. */
class OrderRealizedFillDeriver {
    fun derive(history: CanonicalPaperOrderHistory, decimals: OrderDecimalEvidence? = null): RealizedOrderFill {
        val diagnostics = linkedSetOf<PositionDiagnostic>()
        var inconsistent = false
        var uncertain = false
        fun reject(reason: PositionDiagnostic) { diagnostics += reason; inconsistent = true }
        val identity = history.positionIdentity()
        val side = PositionSide.entries.firstOrNull { it.name == history.side }
        if (!validPositionSymbol(history.symbol)) reject(PositionDiagnostic.INVALID_SYMBOL)
        if (side == null) reject(PositionDiagnostic.INVALID_SIDE)
        if (identity.attemptId.isBlank() || identity.orderSequenceId == null || identity.orderSequenceId <= 0L) {
            reject(PositionDiagnostic.AMBIGUOUS_IDENTITY)
        }
        if (history.ambiguous || history.mappingState != "EXACT") reject(PositionDiagnostic.AMBIGUOUS_IDENTITY)
        if (history.integrityStatus == PaperHistoryIntegrityStatus.INCONSISTENT ||
            history.integrityDiagnostics.any { !it.warningOnly }
        ) reject(PositionDiagnostic.CANONICAL_HISTORY_INCONSISTENT)
        if (PaperHistoryIntegrityDiagnostic.PARTIALLY_FILLED_REGRESSION in history.integrityDiagnostics) {
            diagnostics += PositionDiagnostic.UNKNOWN_LIFECYCLE
            uncertain = true
        }
        if (decimals != null && decimals.identity != identity) reject(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH)

        fun select(legacy: QuantityEvidence, explicit: QuantityEvidence?): QuantityEvidence {
            if (explicit == null) return legacy
            if (explicit == QuantityEvidence.UNKNOWN && legacy == QuantityEvidence.UNKNOWN) return explicit
            if (!explicit.exact || explicit.quantity != legacy.quantity) {
                reject(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH)
            }
            return explicit
        }
        val requested = select(QuantityEvidence.legacy(history.quantity), decimals?.requestedQuantity)
        if (requested.provenance == DecimalProvenance.INVALID ||
            requested.quantity?.let { it <= DecimalQuantity.ZERO } == true
        ) reject(PositionDiagnostic.INVALID_QUANTITY)
        if (requested.quantity == null) { diagnostics += PositionDiagnostic.MISSING_QUANTITY; uncertain = true }

        val observations = history.lifecycleObservations.sortedBy { it.databaseId }
        if (observations.any { it.databaseId <= 0L } ||
            observations.map { it.databaseId }.distinct().size != observations.size
        ) reject(PositionDiagnostic.INVALID_SEQUENCE)
        if (history.currentLifecycle != observations.lastOrNull()) reject(PositionDiagnostic.CURRENT_LIFECYCLE_MISMATCH)
        if (decimals?.observations?.keys?.any { id -> observations.none { it.databaseId == id } } == true) {
            reject(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH)
        }

        val quantities = linkedMapOf<Long, QuantityEvidence>()
        var lastKnown = QuantityEvidence.UNKNOWN
        var completeness = FillCompleteness.UNKNOWN
        var firstTerminal: com.vela.android.lab.data.paper.history.CanonicalPaperLifecycleObservation? = null
        var previousFingerprint: String? = null
        for (observation in observations) {
            val raw = observation.rawStatus
            val terminal = raw in terminalStatuses
            firstTerminal?.let { previous ->
                if (raw != previous.rawStatus || !observation.terminal ||
                    observation.filledQuantity != previous.filledQuantity ||
                    observation.filledAveragePriceUsd != previous.filledAveragePriceUsd ||
                    observation.filledAtIso != previous.filledAtIso
                ) reject(PositionDiagnostic.TERMINAL_CHANGED)
            }
            if (observation.source == "LOCAL_SUBMIT_AUDIT") {
                diagnostics += PositionDiagnostic.SYNTHETIC_ZERO_NOT_EVIDENCE
                val synthetic = QuantityEvidence.legacy(observation.filledQuantity)
                if (observation.status != "SUBMITTED" || raw != "submitted" || observation.terminal ||
                    observation.httpStatusCode != null || lastKnown.quantity != null ||
                    synthetic.provenance == DecimalProvenance.INVALID ||
                    synthetic.quantity?.let { it != DecimalQuantity.ZERO } == true ||
                    decimals?.observations?.containsKey(observation.databaseId) == true
                ) reject(PositionDiagnostic.INVALID_LIFECYCLE)
                continue
            }
            if (observation.source != "ALPACA_PAPER_ORDER_GET" || observation.httpStatusCode !in 200..299 ||
                history.alpacaOrderId.isNullOrBlank() || history.clientOrderId.isNullOrBlank() ||
                observation.submitAuditEntryId == null || observation.submitAuditEntryId != history.auditResultRowId
            ) {
                diagnostics += PositionDiagnostic.INVALID_BROKER_EVIDENCE
                uncertain = true
                completeness = FillCompleteness.UNKNOWN
                continue
            }
            if (raw !in knownStatuses) {
                diagnostics += PositionDiagnostic.UNKNOWN_LIFECYCLE
                uncertain = true
            } else if (observation.status != raw.uppercase(Locale.ROOT) || observation.terminal != terminal) {
                reject(PositionDiagnostic.INVALID_LIFECYCLE)
            }
            if (raw == "new" && observations.takeWhile { it.databaseId < observation.databaseId }
                    .any { it.rawStatus == "partially_filled" }
            ) reject(PositionDiagnostic.INVALID_LIFECYCLE)
            val explicit = decimals?.observations?.get(observation.databaseId)
            if (explicit != null && (explicit.payloadFingerprint.isBlank() ||
                    explicit.payloadFingerprint != observation.payloadFingerprint)
            ) reject(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH)
            val quantity = select(QuantityEvidence.legacy(observation.filledQuantity), explicit?.filledQuantity)
            quantities[observation.databaseId] = quantity
            if (quantity.provenance == DecimalProvenance.INVALID ||
                quantity.quantity?.let { it < DecimalQuantity.ZERO } == true
            ) reject(PositionDiagnostic.INVALID_QUANTITY)
            val amount = quantity.quantity
            if (amount == null) {
                diagnostics += if (terminal) PositionDiagnostic.TERMINAL_QUANTITY_UNKNOWN else PositionDiagnostic.MISSING_QUANTITY
                uncertain = true
                completeness = FillCompleteness.UNKNOWN
            } else {
                if (requested.quantity?.let { amount > it } == true) reject(PositionDiagnostic.EXCESS_FILL)
                if (lastKnown.quantity?.let { amount < it } == true) reject(PositionDiagnostic.DECREASING_FILL)
                if (raw in setOf("new", "rejected") && amount != DecimalQuantity.ZERO) reject(PositionDiagnostic.INVALID_LIFECYCLE)
                if (raw == "partially_filled" &&
                    (amount <= DecimalQuantity.ZERO || requested.quantity?.let { amount >= it } == true)
                ) reject(PositionDiagnostic.INVALID_LIFECYCLE)
                if (raw == "filled" && (amount != requested.quantity || observation.filledAveragePriceUsd == null ||
                        !observation.filledAveragePriceUsd.isFinite() || observation.filledAveragePriceUsd <= 0.0 ||
                        observation.filledAtIso == null || runCatching { Instant.parse(observation.filledAtIso) }.isFailure)
                ) reject(PositionDiagnostic.INVALID_LIFECYCLE)
                if (observation.filledAveragePriceUsd?.let { !it.isFinite() || it <= 0.0 } == true ||
                    observation.filledAtIso?.let { runCatching { Instant.parse(it) }.isFailure } == true
                ) reject(PositionDiagnostic.INVALID_LIFECYCLE)
                lastKnown = quantity
                completeness = if (terminal) FillCompleteness.TERMINAL else FillCompleteness.OPEN
            }
            if (observation.payloadFingerprint == previousFingerprint) diagnostics += PositionDiagnostic.REPEATED_OBSERVATION
            previousFingerprint = observation.payloadFingerprint
            if (firstTerminal == null && terminal) firstTerminal = observation
        }
        if (lastKnown.quantity == null) {
            diagnostics += PositionDiagnostic.MISSING_BROKER_FILL
            uncertain = true
        }
        if (requested.provenance == DecimalProvenance.LEGACY_DOUBLE_DERIVED ||
            quantities.values.any { it.provenance == DecimalProvenance.LEGACY_DOUBLE_DERIVED }
        ) diagnostics += PositionDiagnostic.LEGACY_PRECISION
        if (completeness == FillCompleteness.OPEN) diagnostics += PositionDiagnostic.OPEN_ORDER
        val realized = if (inconsistent || requested.quantity == null) QuantityEvidence.UNKNOWN else lastKnown
        val signed = realized.quantity?.let {
            QuantityEvidence(if (side == PositionSide.SELL) -it else it, realized.provenance)
        } ?: QuantityEvidence.UNKNOWN
        return RealizedOrderFill(
            identity, history.symbol, side, requested, realized, signed,
            when { inconsistent -> FillCompleteness.INCONSISTENT; uncertain -> FillCompleteness.UNKNOWN; else -> completeness },
            when { inconsistent -> PositionIntegrity.INCONSISTENT; uncertain -> PositionIntegrity.UNCERTAIN; else -> PositionIntegrity.RELIABLE },
            diagnostics.toSet(), history.integrityDiagnostics.toSet(), quantities.toMap(),
        )
    }

    private companion object {
        val terminalStatuses = setOf("filled", "canceled", "expired", "rejected")
        val knownStatuses = terminalStatuses + setOf(
            "new", "partially_filled", "done_for_day", "replaced", "pending_cancel", "pending_replace",
            "accepted", "pending_new", "accepted_for_bidding", "stopped", "suspended", "calculated", "held",
        )
    }
}
