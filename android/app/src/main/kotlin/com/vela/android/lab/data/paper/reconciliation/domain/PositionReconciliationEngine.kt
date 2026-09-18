package com.vela.android.lab.data.paper.reconciliation.domain

/** Observational only: no mutation of anchors, clock reads, callbacks, or inferred cause. */
class PositionReconciliationEngine {
    fun reconcile(
        local: LocalExpectedPositionState,
        broker: BrokerPositionSnapshot,
        freshness: SnapshotFreshness,
    ): PositionReconciliationReport {
        val global = local.globalDiagnostics.toMutableSet()
        if (broker.completeness == BrokerSnapshotCompleteness.FAILED) global += PositionDiagnostic.BROKER_READ_FAILED
        if (broker.completeness == BrokerSnapshotCompleteness.PARTIAL) global += PositionDiagnostic.BROKER_SNAPSHOT_PARTIAL
        val invalid = broker.completeness == BrokerSnapshotCompleteness.INVALID || broker.snapshotId.isBlank() ||
            broker.captureStartedAtEpochMillis < 0L || broker.capturedAtEpochMillis < broker.captureStartedAtEpochMillis ||
            broker.positions.map { it.symbol }.distinct().size != broker.positions.size ||
            broker.positions.any { !validRow(it) } || local.positions.map { it.symbol }.distinct().size != local.positions.size
        if (invalid) global += PositionDiagnostic.BROKER_SNAPSHOT_INVALID
        if (freshness == SnapshotFreshness.STALE) global += PositionDiagnostic.BROKER_SNAPSHOT_STALE
        if (freshness == SnapshotFreshness.UNKNOWN) global += PositionDiagnostic.BROKER_FRESHNESS_UNKNOWN
        val symbols = (local.positions.map { it.symbol } + broker.positions.map { it.symbol }).filter(::validPositionSymbol).toSortedSet()
        val rows = symbols.map { symbol ->
            val state = local.positions.singleOrNull { it.symbol == symbol }
            val position = broker.positions.singleOrNull { it.symbol == symbol }
            val diagnostics = (global + state?.diagnostics.orEmpty()).toMutableSet()
            val brokerUsable = broker.completeness == BrokerSnapshotCompleteness.COMPLETE && !invalid
            val observed = when {
                !brokerUsable -> QuantityEvidence.UNKNOWN
                position != null -> position.quantity
                else -> { diagnostics += PositionDiagnostic.BROKER_SYMBOL_ABSENT; QuantityEvidence.ZERO }
            }
            val anchor = state?.anchor
            val aligned = broker.localEvidenceAlignment == CutAssurance.CONFIRMED
            if (anchor != null && !aligned) diagnostics += PositionDiagnostic.BROKER_LOCAL_CUT_UNCONFIRMED
            val accountMatches = !broker.accountRef.isNullOrBlank() && broker.accountRef == local.accountRef &&
                (anchor == null || broker.accountRef == anchor.accountRef)
            if (anchor != null && !accountMatches) diagnostics += PositionDiagnostic.ACCOUNT_CHANGED
            var expected = state?.expectedAbsoluteQty ?: QuantityEvidence.UNKNOWN
            val localBad = state?.integrity == PositionIntegrity.INCONSISTENT ||
                PositionDiagnostic.UNSCOPED_HISTORY in global || PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH in global
            val anchorBad = anchor != null && (anchor.status != AnchorStatus.ACTIVE || !accountMatches ||
                state.coverage == PositionCoverage.ANCHOR_INVALID)
            if (localBad || anchorBad || anchor == null || state?.coverage != PositionCoverage.ANCHORED) expected = QuantityEvidence.UNKNOWN
            val comparable = expected.exact && observed.exact && state?.integrity == PositionIntegrity.RELIABLE &&
                state.deltaComplete && !state.hasOpenExposure && accountMatches && aligned && global.isEmpty()
            val result = when {
                broker.completeness == BrokerSnapshotCompleteness.FAILED -> PositionReconciliationState.BROKER_READ_FAILED
                localBad -> PositionReconciliationState.INCONSISTENT_LOCAL_HISTORY
                anchorBad -> PositionReconciliationState.ANCHOR_INVALID
                !brokerUsable -> PositionReconciliationState.UNKNOWN
                freshness == SnapshotFreshness.STALE -> PositionReconciliationState.STALE
                freshness != SnapshotFreshness.FRESH || global.isNotEmpty() -> PositionReconciliationState.UNKNOWN
                anchor == null -> PositionReconciliationState.UNANCHORED
                !comparable -> PositionReconciliationState.UNKNOWN
                observed.quantity == expected.quantity -> PositionReconciliationState.MATCH
                else -> PositionReconciliationState.MISMATCH
            }
            val difference = if (result in setOf(PositionReconciliationState.MATCH, PositionReconciliationState.MISMATCH)) {
                requireNotNull(observed.quantity) - requireNotNull(expected.quantity)
            } else null
            if (result == PositionReconciliationState.MISMATCH) {
                diagnostics += PositionDiagnostic.UNEXPLAINED_POSITION_DIFFERENCE
                diagnostics += PositionDiagnostic.ANCHOR_SHOULD_INVALIDATE
            }
            if ((!observed.exact && observed.quantity != null) || (!expected.exact && expected.quantity != null)) {
                diagnostics += PositionDiagnostic.LEGACY_PRECISION
            }
            val localPresent = state != null && (state.orderFills.isNotEmpty() || anchor != null)
            PositionReconciliationRow(
                symbol, observed, state?.knownVelaFillDelta ?: QuantityEvidence.ZERO,
                state?.deltaComplete ?: local.globalDiagnostics.isEmpty(), anchor?.baselineQty ?: QuantityEvidence.UNKNOWN,
                expected, difference, result,
                when { localPresent && position != null -> PositionPresence.BOTH; localPresent -> PositionPresence.LOCAL_ONLY
                    position != null -> PositionPresence.BROKER_ONLY; else -> PositionPresence.NEITHER },
                diagnostics.toSet(), anchor?.anchorId,
                if (result == PositionReconciliationState.MISMATCH) PositionDifferenceCause.UNKNOWN else null,
            )
        }
        return PositionReconciliationReport(
            broker.snapshotId, rows, PositionReconciliationSummary(
                rows.count { it.state == PositionReconciliationState.MATCH },
                rows.count { it.state == PositionReconciliationState.MISMATCH },
                rows.count { it.state == PositionReconciliationState.UNANCHORED },
                rows.count { it.state !in setOf(PositionReconciliationState.MATCH, PositionReconciliationState.MISMATCH, PositionReconciliationState.UNANCHORED) },
            ), global.toSet(),
        )
    }

    private fun validRow(row: BrokerPositionQuantity): Boolean {
        val quantity = row.quantity.quantity ?: return false
        if (!validPositionSymbol(row.symbol)) return false
        return when (row.side) {
            BrokerPositionSide.LONG -> quantity > DecimalQuantity.ZERO
            BrokerPositionSide.SHORT -> quantity < DecimalQuantity.ZERO
            BrokerPositionSide.FLAT -> quantity == DecimalQuantity.ZERO
        }
    }
}
