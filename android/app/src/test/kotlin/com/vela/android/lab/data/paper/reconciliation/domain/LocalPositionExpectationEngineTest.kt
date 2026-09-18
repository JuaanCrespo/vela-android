package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LocalPositionExpectationEngineTest {
    private val engine = LocalPositionExpectationEngine()

    @Test fun netsAllOrdersIncludingTerminalPartials() {
        val a = fixture(requested = "2")
        val b = fixture("b", side = "SELL", observations = listOf("canceled" to "0.5"), orderSequence = 20, firstObservation = 30)
        val c = fixture("c", orderSequence = 40, firstObservation = 50)
        val state = engine.evaluate(input(a, b, c)).positions.single()
        assertEquals(QuantityEvidence.decimal("2.5"), state.knownVelaFillDelta)
        assertEquals(3, state.contributingOrderCount)
        assertTrue(state.deltaComplete)
        assertNull(state.expectedAbsoluteQty.quantity)
        assertEquals(PositionCoverage.UNANCHORED, state.coverage)
    }

    @Test fun longToFlatAndLongToShort() {
        listOf("1" to "0", "2" to "-1").forEach { (sell, expected) ->
            val result = engine.evaluate(input(fixture(), fixture("b", side = "SELL", requested = sell, orderSequence = 20, firstObservation = 30)))
            assertEquals(QuantityEvidence.decimal(expected), result.positions.single().knownVelaFillDelta)
        }
        val sell = fixture(side = "SELL", requested = "2")
        assertEquals(QuantityEvidence.decimal("-1"), engine.evaluate(input(sell), listOf(anchor("1"))).positions.single().expectedAbsoluteQty)
    }

    @Test fun partialContributesButBlocksAbsoluteExpectation() {
        val f = fixture(observations = listOf("partially_filled" to "0.4"))
        val state = engine.evaluate(input(f), listOf(anchor())).positions.single()
        assertEquals(QuantityEvidence.decimal("0.4"), state.knownVelaFillDelta)
        assertTrue(state.hasOpenExposure)
        assertNull(state.expectedAbsoluteQty.quantity)
        assertEquals(PositionCoverage.UNCERTAIN, state.coverage)
    }

    @Test fun unknownOrderPreventsVerifiedSubtotalBecomingTotal() {
        val a = fixture()
        val b = fixture("b", observations = listOf("submitted" to "0"), orderSequence = 20, firstObservation = 30)
        val state = engine.evaluate(input(a, b), listOf(anchor())).positions.single()
        assertEquals(QuantityEvidence.decimal("1"), state.knownVelaFillDelta)
        assertFalse(state.deltaComplete)
        assertNull(state.expectedAbsoluteQty.quantity)
        assertEquals(1, state.contributingOrderCount)
    }

    @Test fun baselinePlusNewFills() {
        val state = engine.evaluate(input(fixture(requested = "2")), listOf(anchor("3"))).positions.single()
        assertEquals(QuantityEvidence.decimal("5"), state.expectedAbsoluteQty)
        assertEquals(PositionCoverage.ANCHORED, state.coverage)
    }

    @Test fun cursorSubtractsAlreadyIncludedPartialDespiteClockRollback() {
        val f = fixture(observations = listOf("partially_filled" to "0.4", "filled" to "1"))
        val baseline = anchor("3", cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(f)))
        val state = engine.evaluate(input(f), listOf(baseline)).positions.single()
        assertEquals(QuantityEvidence.decimal("1"), state.knownVelaFillDelta)
        assertEquals(QuantityEvidence.decimal("3.6"), state.expectedAbsoluteQty)
        assertEquals(baseline, state.anchor)
    }

    @Test fun incorporatedOrderAndLaterRepeatedObservationDoNotDoubleCount() {
        val f = fixture(observations = listOf("filled" to "1", "filled" to "1"))
        val baseline = anchor("3", cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(f)))
        val state = engine.evaluate(input(f), listOf(baseline)).positions.single()
        assertEquals(QuantityEvidence.decimal("3"), state.expectedAbsoluteQty)
        assertTrue(PositionDiagnostic.REPEATED_OBSERVATION in state.diagnostics)
    }

    @ParameterizedTest @EnumSource(value = AnchorStatus::class, names = ["INVALIDATED", "SUPERSEDED"])
    fun inactiveAnchorsCannotCreateExpectation(status: AnchorStatus) {
        val state = engine.evaluate(input(fixture()), listOf(anchor().copy(status = status))).positions.single()
        assertNull(state.expectedAbsoluteQty.quantity)
        assertEquals(PositionCoverage.ANCHOR_INVALID, state.coverage)
    }

    @Test fun invalidCursorsCutsAccountsAndMissingHistoryFailClosed() {
        val f = fixture(observations = listOf("partially_filled" to "0.4", "filled" to "1"))
        val valid = anchor(cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(f)))
        val c = valid.cursors.single()
        val badAnchors = listOf(
            valid.copy(accountRef = "other"), valid.copy(cursors = emptyList()), valid.copy(cursors = listOf(c, c)),
            valid.copy(cut = valid.cut.copy(assurance = CutAssurance.UNKNOWN)),
            valid.copy(cursors = listOf(c.copy(includedFilledQty = QuantityEvidence.decimal("0.5")))),
            valid.copy(cursors = listOf(c.copy(observationSequence = 11))),
            valid.copy(cursors = listOf(c.copy(payloadFingerprint = "changed"))),
            valid.copy(cursors = listOf(c.copy(identity = c.identity.copy(side = "SELL")))),
            valid.copy(cut = valid.cut.copy(lifecycleSequenceInclusive = 11)),
            valid.copy(baselineQty = QuantityEvidence.INVALID),
            valid.copy(invalidationReason = AnchorInvalidationReason.MANUAL),
        )
        badAnchors.forEach { baseline ->
            val state = engine.evaluate(input(f), listOf(baseline)).positions.single()
            assertEquals(PositionCoverage.ANCHOR_INVALID, state.coverage, baseline.toString())
            assertNull(state.expectedAbsoluteQty.quantity)
        }
        assertEquals(PositionCoverage.ANCHOR_INVALID, engine.evaluate(input(), listOf(valid)).positions.single().coverage)
        assertEquals(PositionCoverage.ANCHOR_INVALID, engine.evaluate(input(f), listOf(valid, valid)).positions.single().coverage)
    }

    @Test fun inconsistentSpyDoesNotContaminateCleanAapl() {
        val bad = fixture().let { it.copy(history = it.history.copy(integrityStatus = PaperHistoryIntegrityStatus.INCONSISTENT)) }
        val good = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val result = engine.evaluate(input(bad, good), listOf(anchor(), anchor(symbol = "AAPL")))
        assertEquals(PositionIntegrity.INCONSISTENT, result.positions.single { it.symbol == "SPY" }.integrity)
        assertNull(result.positions.single { it.symbol == "SPY" }.expectedAbsoluteQty.quantity)
        assertEquals(QuantityEvidence.decimal("4"), result.positions.single { it.symbol == "AAPL" }.expectedAbsoluteQty)
    }

    @Test fun duplicateIdentityDoesNotNetTwice() {
        val f = fixture()
        val result = engine.evaluate(input(f, f)).positions.single()
        assertEquals(PositionIntegrity.INCONSISTENT, result.integrity)
        assertNull(result.knownVelaFillDelta.quantity)
        assertTrue(PositionDiagnostic.DUPLICATE_IDENTITY in result.diagnostics)
    }

    @Test fun missingScopeOrIncompleteCollectionCannotSupportAnchor() {
        val f = fixture()
        assertNull(engine.evaluate(input(f).copy(completeness = HistoryCompleteness.INCOMPLETE), listOf(anchor())).positions.single().expectedAbsoluteQty.quantity)
        assertNull(engine.evaluate(input(f).copy(accountRef = null), listOf(anchor())).positions.single().expectedAbsoluteQty.quantity)
        val bad = f.copy(history = f.history.copy(symbol = null))
        val result = engine.evaluate(input(bad), listOf(anchor())).positions.single()
        assertEquals(PositionIntegrity.INCONSISTENT, result.integrity)
    }

    @Test fun legacyKnownDeltaCannotBecomeExactExpectation() {
        val state = engine.evaluate(input(fixture(), exact = false), listOf(anchor())).positions.single()
        assertEquals(DecimalQuantity.parse("1"), state.knownVelaFillDelta.quantity)
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, state.knownVelaFillDelta.provenance)
        assertNull(state.expectedAbsoluteQty.quantity)
        assertTrue(PositionDiagnostic.LEGACY_PRECISION in state.diagnostics)
    }

    @Test fun deterministicAcrossCollectionOrderAndPureRepeatCalls() {
        val a = fixture()
        val b = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val first = engine.evaluate(input(a, b))
        assertEquals(first, engine.evaluate(input(b, a)))
        assertEquals(first, engine.evaluate(input(a, b)))
        assertEquals(listOf("AAPL", "SPY"), first.positions.map { it.symbol })
    }

    @Test fun identitiesAndDurableSequencesAreUniqueAcrossOrders() {
        val a = fixture()
        val b = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val variants = listOf(
            b.history.copy(alpacaOrderId = a.history.alpacaOrderId),
            b.history.copy(clientOrderId = a.history.clientOrderId),
            b.history.copy(orderSequenceId = a.history.orderSequenceId),
            b.history.copy(lifecycleObservations = b.history.lifecycleObservations.map { it.copy(databaseId = 10) },
                currentLifecycle = b.history.currentLifecycle!!.copy(databaseId = 10)),
        )
        variants.forEach { history ->
            val result = engine.evaluate(input(a, b.copy(history = history), exact = false))
            assertTrue(result.positions.all { it.integrity == PositionIntegrity.INCONSISTENT })
            assertTrue(result.positions.all { PositionDiagnostic.DUPLICATE_IDENTITY in it.diagnostics })
        }
    }

    @Test fun newOrderWithPreCutObservationIsNotSilentlyIncluded() {
        val state = engine.evaluate(input(fixture()), listOf(anchor(cut = AnchorCoverageCut(0, 10, CutAssurance.CONFIRMED)))).positions.single()
        assertEquals(PositionCoverage.ANCHOR_INVALID, state.coverage)
        assertTrue(PositionDiagnostic.LOCAL_HISTORY_CHANGED in state.diagnostics)
    }

    @Test fun sellCursorSubtractsPreviouslyIncludedQuantityWithCorrectSign() {
        val f = fixture(side = "SELL", observations = listOf("partially_filled" to "0.4", "filled" to "1"))
        val baseline = anchor("1", cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(f)))
        assertEquals(QuantityEvidence.decimal("0.4"), engine.evaluate(input(f), listOf(baseline)).positions.single().expectedAbsoluteQty)
    }

    @Test fun legacyBaselineOrCursorIsNotExactEvenWhenValuesLookEqual() {
        val f = fixture()
        val baselines = listOf(
            anchor().copy(baselineQty = QuantityEvidence.legacy(3.0)),
            anchor(cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED),
                cursors = listOf(cursor(f).copy(includedFilledQty = QuantityEvidence.legacy(1.0)))),
        )
        baselines.forEach {
            val state = engine.evaluate(input(f), listOf(it)).positions.single()
            assertNull(state.expectedAbsoluteQty.quantity)
            assertTrue(PositionDiagnostic.LEGACY_PRECISION in state.diagnostics)
        }
    }

    @Test fun unmatchedDecimalEvidenceAndUnknownSymbolScopeBlockAllPotentiallyAffectedSymbols() {
        val a = fixture()
        val b = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val stray = input(a).copy(decimalEvidence = input(a).decimalEvidence + ("unknown" to b.decimals))
        assertEquals(PositionIntegrity.INCONSISTENT, engine.evaluate(stray).positions.single().integrity)
        val unscoped = a.copy(history = a.history.copy(symbol = null))
        assertEquals(PositionIntegrity.INCONSISTENT, engine.evaluate(input(unscoped, b)).positions.single().integrity)
    }
}
