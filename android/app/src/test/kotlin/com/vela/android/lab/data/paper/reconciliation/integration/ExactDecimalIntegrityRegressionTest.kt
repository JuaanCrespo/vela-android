package com.vela.android.lab.data.paper.reconciliation.integration

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Permanent regressions for 2.y.5-C.1. Fixtures and transports are offline only. */
class ExactDecimalIntegrityRegressionTest {
    private val a = "0.100000000000000001"
    private val b = "0.100000000000000002"
    private val tiny = "0.000000000000000001"

    private suspend fun rig(baseline: String = "8"): PositionIntegrationRig = PositionIntegrationRig().also {
        it.histories = listOf(fixture("a").history, fixture("b", orderSequence = 3, firstObservation = 20).history)
        it.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"$baseline"}]""")
        assertEquals(PositionRefreshResult.SUCCESS, it.store().refreshManually())
        val selection = it.store().selectBaselineSymbol("SPY")
        assertEquals(PositionCoverageMode.LEGACY_BOOTSTRAP_V1, selection.proposal!!.coverageMode)
        it.store().establishBaseline(selection)
    }

    private suspend fun local(rig: PositionIntegrationRig): LocalSymbolPositionState {
        val anchor = rig.store().loadOffline().anchors.single().domain()
        return LocalPositionExpectationEngine().evaluate(
            ConsistentPositionHistoryReader(rig.database).read(rig.accountRef), listOf(anchor),
        ).positions.single { it.symbol == "SPY" }
    }

    private suspend fun assertNoAuthoritativeComparison(rig: PositionIntegrationRig) {
        val state = local(rig)
        assertNotEquals(CutAssurance.CONFIRMED, state.coverageMetadata!!.postAnchorCoverageAssurance)
        assertNull(state.expectedAbsoluteQty.quantity)
        // Both the would-be MATCH and would-be MISMATCH must remain non-authoritative.
        for (broker in listOf("8.100000000000000002", "9")) {
            rig.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"$broker"}]""")
            assertEquals(PositionRefreshResult.SUCCESS, rig.store().refreshManually())
            val stored = rig.store().loadOffline().latestReport!!
            val row = stored.report.rows.single()
            assertFalse(row.state in setOf(PositionReconciliationState.MATCH, PositionReconciliationState.MISMATCH))
            assertNull(row.expectedQty.quantity)
            assertNull(row.difference)
            assertTrue(rig.reports.verifyReplay(stored.metadata.reportId))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["canceled", "expired", "filled"])
    fun `distinct exact terminal fills that collapse to same Double are contradictory end to end`(status: String) = runTest {
        assertNotEquals(DecimalQuantity.parse(a), DecimalQuantity.parse(b))
        assertTrue(DecimalQuantity.parse(a) < DecimalQuantity.parse(b))
        assertEquals(a.toDouble(), b.toDouble()) // Reproduction fact only, never the authoritative assertion.
        val post = sameLegacyFingerprint(fixture("post", requested = if (status == "filled") a else "1",
            observations = listOf(status to a, status to b), orderSequence = 100, firstObservation = 100))
        val rig = rig(); rig.addExact(post)
        assertNoAuthoritativeComparison(rig)
        val fill = OrderRealizedFillDeriver().derive(post.history, post.decimals, exactSourceProjection = true)
        assertTrue(PositionDiagnostic.TERMINAL_CHANGED in fill.diagnostics)
        assertEquals(PositionIntegrity.INCONSISTENT, fill.integrity)
        assertNull(fill.realizedQty.quantity)
    }

    @ParameterizedTest @ValueSource(strings = ["canceled", "expired", "filled"])
    fun `identical exact terminal rereads remain valid without historical double count`(status: String) = runTest {
        val post = sameLegacyFingerprint(fixture("post", requested = if (status == "filled") a else "1",
            observations = listOf(status to a, status to a), orderSequence = 100, firstObservation = 100))
        val fill = OrderRealizedFillDeriver().derive(post.history, post.decimals, exactSourceProjection = true)
        assertEquals(PositionIntegrity.RELIABLE, fill.integrity)
        assertFalse(PositionDiagnostic.TERMINAL_CHANGED in fill.diagnostics)
        assertTrue(PositionDiagnostic.REPEATED_OBSERVATION in fill.diagnostics)
        val rig = rig(); rig.addExact(post)
        val state = local(rig)
        assertEquals("2", state.coverageMetadata!!.historicalKnownVelaDelta.quantity.toString())
        assertEquals(a, state.coverageMetadata.postAnchorExactDelta.quantity.toString())
        assertEquals("8.100000000000000001", state.expectedAbsoluteQty.quantity.toString())
        rig.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"8.100000000000000001"}]""")
        assertEquals(PositionRefreshResult.SUCCESS, rig.store().refreshManually())
        val stored = rig.store().loadOffline().latestReport!!
        assertEquals(PositionReconciliationState.MATCH, stored.report.rows.single().state)
        assertTrue(rig.reports.verifyReplay(stored.metadata.reportId))
    }

    @Test fun `same legacy fingerprint does not make distinct exact partial fills repeated`() {
        val post = sameLegacyFingerprint(fixture(observations = listOf("partially_filled" to a, "partially_filled" to b)))
        val fill = OrderRealizedFillDeriver().derive(post.history, post.decimals, exactSourceProjection = true)
        assertEquals(PositionIntegrity.RELIABLE, fill.integrity)
        assertEquals(b, fill.realizedQty.quantity.toString())
        assertFalse(PositionDiagnostic.REPEATED_OBSERVATION in fill.diagnostics)
    }

    @Test fun `V1 retains historical diagnostics and rejects precision outside its strict projection contract`() {
        val post = sameLegacyFingerprint(fixture(observations = listOf("canceled" to a, "canceled" to b)))
        val v1 = OrderRealizedFillDeriver().derive(post.history, post.decimals)
        assertEquals(PositionIntegrity.INCONSISTENT, v1.integrity)
        assertNull(v1.realizedQty.quantity)
        assertEquals(setOf(PositionDiagnostic.DECIMAL_EVIDENCE_MISMATCH, PositionDiagnostic.REPEATED_OBSERVATION), v1.diagnostics)
    }

    @Test fun `exact cumulative decrease hidden by legacy projection fails closed`() = runTest {
        val post = sameLegacyFingerprint(fixture("post", observations = listOf("partially_filled" to b, "canceled" to a),
            orderSequence = 100, firstObservation = 100))
        val fill = OrderRealizedFillDeriver().derive(post.history, post.decimals, exactSourceProjection = true)
        assertTrue(PositionDiagnostic.DECREASING_FILL in fill.diagnostics)
        val rig = rig(); rig.addExact(post); assertNoAuthoritativeComparison(rig)
    }

    @Test fun `exact overfill hidden by legacy projection fails closed`() = runTest {
        val post = fixture("post", requested = a, observations = listOf("canceled" to b), orderSequence = 100, firstObservation = 100)
        val fill = OrderRealizedFillDeriver().derive(post.history, post.decimals, exactSourceProjection = true)
        assertTrue(PositionDiagnostic.EXCESS_FILL in fill.diagnostics)
        val rig = rig(); rig.addExact(post); assertNoAuthoritativeComparison(rig)
    }

    @ParameterizedTest @ValueSource(strings = ["all_missing", "first_missing", "last_missing"])
    fun `missing exact terminal evidence never promotes legacy to confirmed coverage`(missing: String) = runTest {
        val post = fixture("post", observations = listOf("canceled" to a, "canceled" to a), orderSequence = 100, firstObservation = 100)
        val legacy = OrderRealizedFillDeriver().derive(post.history)
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, legacy.realizedQty.provenance)
        val rig = rig(); rig.addExact(post)
        rig.dao.decimalEvidence.removeAll { missing == "all_missing" || it.observationId == if (missing == "first_missing") 100L else 101L }
        assertNoAuthoritativeComparison(rig)
    }

    @Test fun `exact cursor subtraction retains one e minus eighteen and V1 strictness`() {
        assertEquals(DecimalQuantity.parse(tiny), DecimalQuantity.parse(b) - DecimalQuantity.parse(a))
        // V1 only accepts exact sidecars compatible with its existing strict legacy projection gate.
        val post = fixture(requested = "0.000000000000000002",
            observations = listOf("partially_filled" to tiny, "filled" to "0.000000000000000002"))
        val accepted = anchor("8", cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(post)))
        val state = LocalPositionExpectationEngine().evaluate(input(post), listOf(accepted)).positions.single()
        assertEquals(PositionCoverageMode.EXACT_CURSORS_V1, accepted.coverageMode)
        assertEquals("8.000000000000000001", state.expectedAbsoluteQty.quantity.toString())
        val precisionBeyondV1 = fixture(requested = b, observations = listOf("partially_filled" to a, "filled" to b))
        val incompatible = anchor("8", cut = AnchorCoverageCut(1, 10, CutAssurance.CONFIRMED), cursors = listOf(cursor(precisionBeyondV1)))
        assertNull(LocalPositionExpectationEngine().evaluate(input(precisionBeyondV1), listOf(incompatible)).positions.single().expectedAbsoluteQty.quantity)
    }

    @Test fun `broker expected difference of one e minus eighteen is MISMATCH and survives replay`() = runTest {
        val rig = rig(a)
        assertEquals(a, local(rig).expectedAbsoluteQty.quantity.toString())
        rig.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"$b"}]""")
        assertEquals(PositionRefreshResult.SUCCESS, rig.store().refreshManually())
        val stored = rig.store().loadOffline().latestReport!!
        val row = stored.report.rows.single()
        assertEquals(PositionReconciliationState.MISMATCH, row.state)
        assertEquals(tiny, row.difference.toString())
        assertEquals(PositionDifferenceCause.UNKNOWN, row.cause)
        assertTrue(rig.reports.verifyReplay(stored.metadata.reportId))
    }

    @Test fun `manifest exact terminal payload and digest preserve precision beyond legacy projection`() = runTest {
        val rig = PositionIntegrationRig()
        rig.addExact(fixture(observations = listOf("canceled" to a)))
        rig.store().refreshManually()
        val proposal = rig.anchors.prepareBootstrapAnchor("exact-cut", "SPY", rig.snapshots.latestComplete()!!.metadata.snapshotId, rig.now)
        val cut = proposal.bootstrapCut!!
        assertEquals(a, cut.inventory.single().exactTerminalAtCut!!.quantity.toString())
        val changed = cut.copy(inventory = listOf(cut.inventory.single().copy(exactTerminalAtCut = QuantityEvidence.decimal(b))))
        assertNotEquals(BootstrapEvidenceCodec.encode(cut), BootstrapEvidenceCodec.encode(changed))
        assertNotEquals(BootstrapEvidenceCodec.digest(cut), BootstrapEvidenceCodec.digest(changed))
        assertEquals(cut, BootstrapEvidenceCodec.decode(BootstrapEvidenceCodec.encode(cut), BootstrapEvidenceCodec.digest(cut)))
    }

    /** Model the actual canonical fingerprint's Double collision while retaining distinct source sidecars. */
    private fun sameLegacyFingerprint(value: PositionFixture): PositionFixture {
        val rows = value.history.lifecycleObservations.mapIndexed { index, row ->
            row.copy(payloadFingerprint = "same-legacy-projection", samePayloadAsPrevious = index > 0)
        }
        return value.copy(history = value.history.copy(lifecycleObservations = rows, currentLifecycle = rows.last()),
            decimals = value.decimals.copy(observations = value.decimals.observations.mapValues { (_, evidence) ->
                evidence.copy(payloadFingerprint = "same-legacy-projection")
            }))
    }
}
