package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.CsvSource

class PositionReconciliationEngineTest {
    private val local = LocalPositionExpectationEngine()
    private val engine = PositionReconciliationEngine()
    private fun expected() = local.evaluate(input(fixture(requested = "2")), listOf(anchor("3")))

    @Test fun historicalPlusTwoIsNotAbsoluteTwo() {
        val state = local.evaluate(input(fixture(requested = "2")))
        val row = engine.reconcile(state, snapshot("SPY" to "5"), SnapshotFreshness.FRESH).rows.single()
        assertEquals(QuantityEvidence.decimal("2"), row.knownVelaDelta)
        assertNull(row.expectedQty.quantity)
        assertNull(row.difference)
        assertEquals(PositionReconciliationState.UNANCHORED, row.state)
    }

    @Test fun brokerOnlyIsUnanchoredNotCorruption() {
        val report = engine.reconcile(local.evaluate(input()), snapshot("SPY" to "5"), SnapshotFreshness.FRESH)
        assertEquals(PositionPresence.BROKER_ONLY, report.rows.single().presence)
        assertEquals(PositionReconciliationState.UNANCHORED, report.rows.single().state)
        assertEquals(1, report.summary.unanchoredCount)
    }

    @ParameterizedTest @CsvSource("5.000, 0, MATCH", "7, 2, MISMATCH", "3, -2, MISMATCH")
    fun exactComparisonAndDifference(observed: String, difference: String, status: PositionReconciliationState) {
        val before = expected()
        val report = engine.reconcile(before, snapshot("SPY" to observed), SnapshotFreshness.FRESH)
        val row = report.rows.single()
        assertEquals(status, row.state)
        assertEquals(DecimalQuantity.parse(difference), row.difference)
        assertEquals(QuantityEvidence.decimal("5"), row.expectedQty)
        assertEquals(AnchorStatus.ACTIVE, before.positions.single().anchor!!.status)
        if (status == PositionReconciliationState.MISMATCH) {
            assertEquals(PositionDifferenceCause.UNKNOWN, row.cause)
            assertTrue(PositionDiagnostic.UNEXPLAINED_POSITION_DIFFERENCE in row.diagnostics)
            assertTrue(PositionDiagnostic.ANCHOR_SHOULD_INVALIDATE in row.diagnostics)
            assertEquals(1, report.summary.mismatchedCount)
        } else assertEquals(1, report.summary.matchedCount)
    }

    @Test fun completeAbsenceMeansZeroNotFailure() {
        val report = engine.reconcile(expected(), snapshot(), SnapshotFreshness.FRESH)
        val row = report.rows.single()
        assertEquals(QuantityEvidence.ZERO, row.brokerQty)
        assertEquals(DecimalQuantity.parse("-5"), row.difference)
        assertEquals(PositionReconciliationState.MISMATCH, row.state)
        assertEquals(PositionPresence.LOCAL_ONLY, row.presence)
        val flat = local.evaluate(input(), listOf(anchor("0")))
        assertEquals(PositionReconciliationState.MATCH, engine.reconcile(flat, snapshot(), SnapshotFreshness.FRESH).rows.single().state)
    }

    @ParameterizedTest @EnumSource(value = BrokerSnapshotCompleteness::class, names = ["PARTIAL", "INVALID", "FAILED"])
    fun incompleteAbsenceNeverMeansZero(completeness: BrokerSnapshotCompleteness) {
        val row = engine.reconcile(expected(), snapshot().copy(completeness = completeness), SnapshotFreshness.FRESH).rows.single()
        assertNull(row.brokerQty.quantity)
        assertNull(row.difference)
        assertEquals(if (completeness == BrokerSnapshotCompleteness.FAILED) PositionReconciliationState.BROKER_READ_FAILED else PositionReconciliationState.UNKNOWN, row.state)
    }

    @Test fun failedSnapshotNeverUsesEvenProvidedRowsAsCurrent() {
        val row = engine.reconcile(expected(), snapshot("SPY" to "5").copy(completeness = BrokerSnapshotCompleteness.FAILED), SnapshotFreshness.FRESH).rows.single()
        assertEquals(PositionReconciliationState.BROKER_READ_FAILED, row.state)
        assertNull(row.brokerQty.quantity)
    }

    @Test fun staleSuppressesDifferenceWithoutInvalidatingAnchor() {
        val state = expected()
        val report = engine.reconcile(state, snapshot("SPY" to "100"), SnapshotFreshness.STALE)
        val row = report.rows.single()
        assertEquals(PositionReconciliationState.STALE, row.state)
        assertNull(row.difference)
        assertEquals(AnchorStatus.ACTIVE, state.positions.single().anchor!!.status)
        assertFalse(PositionDiagnostic.ANCHOR_SHOULD_INVALIDATE in row.diagnostics)
        assertEquals(1, report.summary.unknownCount)
    }

    @Test fun unknownFreshnessNeverCompares() {
        assertEquals(PositionReconciliationState.UNKNOWN, engine.reconcile(expected(), snapshot("SPY" to "5"), SnapshotFreshness.UNKNOWN).rows.single().state)
    }

    @ParameterizedTest @EnumSource(value = AnchorStatus::class, names = ["INVALIDATED", "SUPERSEDED"])
    fun inactiveAnchorIsExplicit(status: AnchorStatus) {
        val state = local.evaluate(input(fixture()), listOf(anchor().copy(status = status)))
        val row = engine.reconcile(state, snapshot("SPY" to "4"), SnapshotFreshness.FRESH).rows.single()
        assertEquals(PositionReconciliationState.ANCHOR_INVALID, row.state)
        assertNull(row.expectedQty.quantity)
        assertNull(row.difference)
    }

    @Test fun accountMismatchCannotRebaselineOrCompare() {
        val row = engine.reconcile(expected(), snapshot("SPY" to "5").copy(accountRef = "other"), SnapshotFreshness.FRESH).rows.single()
        assertEquals(PositionReconciliationState.ANCHOR_INVALID, row.state)
        assertNull(row.expectedQty.quantity)
        assertNull(row.difference)
        assertTrue(PositionDiagnostic.ACCOUNT_CHANGED in row.diagnostics)
    }

    @Test fun legacyPrecisionAndOpenExposurePreventComparison() {
        val states = listOf(
            local.evaluate(input(fixture(requested = "2"), exact = false), listOf(anchor())),
            local.evaluate(input(fixture(observations = listOf("partially_filled" to "0.4"))), listOf(anchor())),
        )
        states.forEach { state ->
            val row = engine.reconcile(state, snapshot("SPY" to "5"), SnapshotFreshness.FRESH).rows.single()
            assertEquals(PositionReconciliationState.UNKNOWN, row.state)
            assertNull(row.difference)
        }
        val broker = snapshot("SPY" to "5").let { it.copy(positions = listOf(it.positions.single().copy(quantity = QuantityEvidence.legacy(5.0)))) }
        assertEquals(PositionReconciliationState.UNKNOWN, engine.reconcile(expected(), broker, SnapshotFreshness.FRESH).rows.single().state)
    }

    @Test fun localInconsistencyIsIsolatedAndSummaryPartitionsRows() {
        val a = fixture().let { it.copy(history = it.history.copy(integrityStatus = PaperHistoryIntegrityStatus.INCONSISTENT)) }
        val b = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val state = local.evaluate(input(a, b), listOf(anchor(), anchor(symbol = "AAPL")))
        val report = engine.reconcile(state, snapshot("SPY" to "4", "AAPL" to "4", "QQQ" to "1"), SnapshotFreshness.FRESH)
        assertEquals(PositionReconciliationState.INCONSISTENT_LOCAL_HISTORY, report.rows.single { it.symbol == "SPY" }.state)
        assertNull(report.rows.single { it.symbol == "SPY" }.expectedQty.quantity)
        assertEquals(PositionReconciliationState.MATCH, report.rows.single { it.symbol == "AAPL" }.state)
        assertEquals(PositionReconciliationSummary(1, 0, 1, 1), report.summary)
    }

    @Test fun malformedBrokerRowsInvalidateWholeSnapshotIncludingAbsence() {
        val valid = snapshot("SPY" to "5")
        val bad = listOf(
            valid.copy(positions = valid.positions + valid.positions),
            valid.copy(positions = listOf(valid.positions.single().copy(side = BrokerPositionSide.SHORT))),
            valid.copy(positions = listOf(valid.positions.single().copy(quantity = QuantityEvidence.INVALID))),
            valid.copy(positions = listOf(valid.positions.single().copy(symbol = "?"))),
            valid.copy(capturedAtEpochMillis = 99), valid.copy(snapshotId = ""),
        )
        bad.forEach {
            val row = engine.reconcile(expected(), it, SnapshotFreshness.FRESH).rows.single()
            assertEquals(PositionReconciliationState.UNKNOWN, row.state)
            assertNull(row.brokerQty.quantity)
            assertNull(row.difference)
        }
    }

    @Test fun emptyPortfolioRetainsFailureDiagnosticsWithoutFakeRows() {
        val state = local.evaluate(input())
        val ok = engine.reconcile(state, snapshot(), SnapshotFreshness.FRESH)
        assertTrue(ok.rows.isEmpty())
        assertEquals(PositionReconciliationSummary(0, 0, 0, 0), ok.summary)
        val failed = engine.reconcile(state, snapshot().copy(completeness = BrokerSnapshotCompleteness.FAILED), SnapshotFreshness.FRESH)
        assertTrue(PositionDiagnostic.BROKER_READ_FAILED in failed.diagnostics)
    }

    @Test fun exactFractionsAndShortsMatchWithoutTolerance() {
        val a = fixture(requested = "0.1")
        val b = fixture("b", requested = "0.2", orderSequence = 20, firstObservation = 30)
        val state = local.evaluate(input(a, b), listOf(anchor("0")))
        assertEquals(PositionReconciliationState.MATCH, engine.reconcile(state, snapshot("SPY" to "0.300"), SnapshotFreshness.FRESH).rows.single().state)
        assertEquals(PositionReconciliationState.MISMATCH, engine.reconcile(state, snapshot("SPY" to "0.3000000001"), SnapshotFreshness.FRESH).rows.single().state)
        val short = local.evaluate(input(fixture(side = "SELL", requested = "2")), listOf(anchor("1")))
        assertEquals(PositionReconciliationState.MATCH, engine.reconcile(short, snapshot("SPY" to "-1"), SnapshotFreshness.FRESH).rows.single().state)
    }

    @Test fun freshDoesNotProveAlignmentWithLocalEvidence() {
        val broker = snapshot("SPY" to "5").copy(localEvidenceAlignment = CutAssurance.UNKNOWN)
        val row = engine.reconcile(expected(), broker, SnapshotFreshness.FRESH).rows.single()
        assertEquals(PositionReconciliationState.UNKNOWN, row.state)
        assertNull(row.difference)
        assertTrue(PositionDiagnostic.BROKER_LOCAL_CUT_UNCONFIRMED in row.diagnostics)
        assertEquals(PositionReconciliationState.UNANCHORED,
            engine.reconcile(local.evaluate(input()), broker, SnapshotFreshness.FRESH).rows.single().state)
    }

    @Test fun failedOrPartialReadsCannotProduceMatchOnOtherSymbols() {
        val a = fixture()
        val b = fixture("b", symbol = "AAPL", orderSequence = 20, firstObservation = 30)
        val state = local.evaluate(input(a, b), listOf(anchor(), anchor(symbol = "AAPL")))
        val report = engine.reconcile(state, snapshot("SPY" to "4").copy(completeness = BrokerSnapshotCompleteness.PARTIAL), SnapshotFreshness.FRESH)
        assertEquals(2, report.summary.unknownCount)
        assertTrue(report.rows.all { it.difference == null && it.brokerQty.quantity == null })
    }

    @Test fun noSharedStateOrAnchorMutationAcrossDifferentReports() {
        val state = expected()
        val first = engine.reconcile(state, snapshot("SPY" to "7"), SnapshotFreshness.FRESH)
        val second = engine.reconcile(state, snapshot("SPY" to "5"), SnapshotFreshness.FRESH)
        assertEquals(PositionReconciliationState.MISMATCH, first.rows.single().state)
        assertEquals(PositionReconciliationState.MATCH, second.rows.single().state)
        assertEquals(first, engine.reconcile(state, snapshot("SPY" to "7"), SnapshotFreshness.FRESH))
        assertEquals(AnchorStatus.ACTIVE, state.positions.single().anchor!!.status)
    }
}
