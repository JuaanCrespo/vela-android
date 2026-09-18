package com.vela.android.lab.data.paper.reconciliation.domain

/** All inputs are detached values. The caller supplies full account-scoped history and accepted cuts. */
class LocalPositionExpectationEngine(private val deriver: OrderRealizedFillDeriver = OrderRealizedFillDeriver()) {
    fun evaluate(input: PositionHistoryInput, anchors: List<PositionAnchor> = emptyList()): LocalExpectedPositionState {
        val global = linkedSetOf<PositionDiagnostic>()
        if (input.completeness != HistoryCompleteness.COMPLETE) global += PositionDiagnostic.INCOMPLETE_HISTORY
        if (input.histories.any { !validPositionSymbol(it.symbol) }) global += PositionDiagnostic.UNSCOPED_HISTORY
        if (input.decimalEvidence.keys.any { key -> input.histories.none { it.submitAttemptId == key } }) {
            global += PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH
        }
        if (anchors.any { !validPositionSymbol(it.symbol) }) global += PositionDiagnostic.INVALID_ANCHOR
        val duplicates = mutableSetOf<String>()
        listOf<(com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory) -> String?>(
            { it.submitAttemptId }, { it.alpacaOrderId }, { it.clientOrderId },
        ).forEach { key ->
            input.histories.filter { !key(it).isNullOrBlank() }.groupBy(key).values
                .filter { it.size > 1 }.forEach { rows -> duplicates += rows.map { it.submitAttemptId } }
        }
        input.histories.groupBy { it.orderSequenceId }.filterKeys { it != null }.values
            .filter { it.size > 1 }.forEach { rows -> duplicates += rows.map { it.submitAttemptId } }
        input.histories.flatMap { history -> history.lifecycleObservations.map { it.databaseId to history.submitAttemptId } }
            .groupBy { it.first }.values.filter { it.size > 1 }.forEach { rows -> duplicates += rows.map { it.second } }
        val derived = input.histories.map { history ->
            val fill = deriver.derive(history, input.decimalEvidence[history.submitAttemptId])
            if (history.submitAttemptId !in duplicates) fill else fill.copy(
                realizedQty = QuantityEvidence.UNKNOWN, signedRealizedQty = QuantityEvidence.UNKNOWN,
                completeness = FillCompleteness.INCONSISTENT, integrity = PositionIntegrity.INCONSISTENT,
                diagnostics = fill.diagnostics + PositionDiagnostic.DUPLICATE_IDENTITY,
            )
        }
        val symbols = (derived.mapNotNull { it.symbol } + anchors.map { it.symbol })
            .filter(::validPositionSymbol).toSortedSet()
        val states = symbols.map { symbol ->
            val fills = derived.filter { it.symbol == symbol }.sortedWith(
                compareBy<RealizedOrderFill> { it.identity.orderSequenceId }.thenBy { it.identity.attemptId },
            )
            val diagnostics = (global + fills.flatMap { it.diagnostics }).toMutableSet()
            val symbolAnchors = anchors.filter { it.symbol == symbol }
            val anchor = symbolAnchors.firstOrNull()
            val inconsistent = fills.any { it.integrity == PositionIntegrity.INCONSISTENT } ||
                PositionDiagnostic.UNSCOPED_HISTORY in global || PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH in global
            val complete = !inconsistent && input.completeness == HistoryCompleteness.COMPLETE &&
                fills.all { it.integrity == PositionIntegrity.RELIABLE && it.realizedQty.quantity != null }
            val open = fills.any { it.completeness != FillCompleteness.TERMINAL }
            val known = fills.map { it.signedRealizedQty }.filter { it.quantity != null }
            val delta = if (known.isEmpty() && fills.isNotEmpty()) QuantityEvidence.UNKNOWN else sumEvidence(known)
            var expected = QuantityEvidence.UNKNOWN
            var coverage = PositionCoverage.UNANCHORED
            if (anchor != null) {
                val anchorDiagnostics = validateAnchor(anchor, symbolAnchors.size, input, fills)
                diagnostics += anchorDiagnostics
                coverage = when {
                    anchorDiagnostics.any { it != PositionDiagnostic.LEGACY_PRECISION } -> PositionCoverage.ANCHOR_INVALID
                    !complete || open || PositionDiagnostic.LEGACY_PRECISION in diagnostics -> PositionCoverage.UNCERTAIN
                    else -> PositionCoverage.ANCHORED
                }
                if (coverage == PositionCoverage.ANCHORED) {
                    val deltas = fills.map { fill ->
                        val included = anchor.cursors.singleOrNull { it.identity == fill.identity }
                            ?.includedFilledQty?.quantity ?: DecimalQuantity.ZERO
                        val quantity = requireNotNull(fill.realizedQty.quantity) - included
                        QuantityEvidence(
                            if (fill.side == PositionSide.SELL) -quantity else quantity,
                            fill.realizedQty.provenance,
                        )
                    }
                    expected = sumEvidence(listOf(anchor.baselineQty) + deltas)
                }
            }
            LocalSymbolPositionState(
                symbol, delta, complete, expected, coverage, known.size, open,
                when { inconsistent -> PositionIntegrity.INCONSISTENT; !complete || open -> PositionIntegrity.UNCERTAIN; else -> PositionIntegrity.RELIABLE },
                anchor, diagnostics.toSet(), fills.toList(),
            )
        }
        return LocalExpectedPositionState(input.accountRef, states, global.toSet())
    }

    private fun validateAnchor(
        anchor: PositionAnchor,
        count: Int,
        input: PositionHistoryInput,
        fills: List<RealizedOrderFill>,
    ): Set<PositionDiagnostic> {
        val diagnostics = linkedSetOf<PositionDiagnostic>()
        if (count != 1 || anchor.anchorId.isBlank() || anchor.status != AnchorStatus.ACTIVE ||
            anchor.createdAtEpochMillis < 0L || anchor.invalidatedAtEpochMillis != null || anchor.invalidationReason != null ||
            anchor.baselineQty.quantity == null || anchor.cut.orderSequenceInclusive < 0L || anchor.cut.lifecycleSequenceInclusive < 0L
        ) diagnostics += PositionDiagnostic.INVALID_ANCHOR
        if (anchor.accountRef.isBlank() || input.accountRef.isNullOrBlank() || anchor.accountRef != input.accountRef) {
            diagnostics += PositionDiagnostic.ACCOUNT_CHANGED
        }
        if (anchor.cut.assurance != CutAssurance.CONFIRMED) diagnostics += PositionDiagnostic.UNCONFIRMED_CUT
        if (!anchor.baselineQty.exact) diagnostics += PositionDiagnostic.LEGACY_PRECISION
        if (anchor.cursors.map { it.identity.attemptId }.distinct().size != anchor.cursors.size) {
            diagnostics += PositionDiagnostic.INVALID_CURSOR
        }
        anchor.cursors.forEach { cursor ->
            val fill = fills.singleOrNull { it.identity == cursor.identity }
            val history = input.histories.singleOrNull { it.positionIdentity() == cursor.identity }
            val observation = history?.lifecycleObservations?.singleOrNull { it.databaseId == cursor.observationSequence }
            val atCut = history?.lifecycleObservations?.filter { it.databaseId <= anchor.cut.lifecycleSequenceInclusive }
                ?.maxByOrNull { it.databaseId }
            val included = cursor.includedFilledQty.quantity
            if (fill == null || observation == null || observation != atCut ||
                cursor.observationSequence <= 0L || cursor.observationSequence > anchor.cut.lifecycleSequenceInclusive ||
                cursor.identity.orderSequenceId == null || cursor.identity.orderSequenceId > anchor.cut.orderSequenceInclusive ||
                cursor.payloadFingerprint.isBlank() || cursor.payloadFingerprint != observation.payloadFingerprint ||
                included == null || included < DecimalQuantity.ZERO ||
                included != fill.observationQuantities[cursor.observationSequence]?.quantity ||
                fill.realizedQty.quantity?.let { included > it } == true
            ) diagnostics += PositionDiagnostic.INVALID_CURSOR
            if (!cursor.includedFilledQty.exact || fill?.observationQuantities?.get(cursor.observationSequence)?.exact != true) {
                diagnostics += PositionDiagnostic.LEGACY_PRECISION
            }
        }
        fills.forEach { fill ->
            val sequence = fill.identity.orderSequenceId
            val cursor = anchor.cursors.singleOrNull { it.identity == fill.identity }
            if (sequence == null) diagnostics += PositionDiagnostic.COVERAGE_LOST
            else if (sequence <= anchor.cut.orderSequenceInclusive && cursor == null) diagnostics += PositionDiagnostic.MISSING_CURSOR
            else if (sequence > anchor.cut.orderSequenceInclusive) {
                val history = input.histories.singleOrNull { it.positionIdentity() == fill.identity }
                if (cursor != null || history?.lifecycleObservations?.any { it.databaseId <= anchor.cut.lifecycleSequenceInclusive } == true) {
                    diagnostics += PositionDiagnostic.LOCAL_HISTORY_CHANGED
                }
            }
        }
        return diagnostics
    }
}
