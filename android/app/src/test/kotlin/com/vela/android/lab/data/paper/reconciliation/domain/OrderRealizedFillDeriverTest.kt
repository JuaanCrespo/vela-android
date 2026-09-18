package com.vela.android.lab.data.paper.reconciliation.domain

import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OrderRealizedFillDeriverTest {
    private val deriver = OrderRealizedFillDeriver()
    private fun derive(f: PositionFixture) = deriver.derive(f.history, f.decimals)

    @Test fun submittedSyntheticZeroDoesNotProveBrokerZero() {
        val result = derive(fixture(observations = listOf("submitted" to "0")))
        assertNull(result.realizedQty.quantity)
        assertEquals(FillCompleteness.UNKNOWN, result.completeness)
        assertTrue(PositionDiagnostic.SYNTHETIC_ZERO_NOT_EVIDENCE in result.diagnostics)
    }

    @Test fun brokerNewZeroIsKnownButOpen() {
        val result = derive(fixture(observations = listOf("new" to "0")))
        assertEquals(DecimalQuantity.ZERO, result.realizedQty.quantity)
        assertEquals(FillCompleteness.OPEN, result.completeness)
    }

    @Test fun cumulativePartialsUseFinalAccumulatedValue() {
        val scenarios = listOf(
            listOf("partially_filled" to "0.4") to "0.4",
            listOf("partially_filled" to "0.4", "partially_filled" to "0.7") to "0.7",
            listOf("partially_filled" to "0.4", "partially_filled" to "0.7", "filled" to "1") to "1",
            listOf("partially_filled" to "0.4", "partially_filled" to "0.4") to "0.4",
        )
        scenarios.forEach { (observations, expected) ->
            val f = fixture(observations = observations)
            val result = derive(f)
            assertEquals(QuantityEvidence.decimal(expected), result.realizedQty)
            assertEquals(PositionIntegrity.RELIABLE, result.integrity)
            assertEquals(observations.size, f.history.lifecycleObservations.size)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["canceled", "expired"])
    fun terminalPartialRemainsRealized(status: String) {
        val result = derive(fixture(observations = listOf("partially_filled" to "0.7", status to "0.7")))
        assertEquals(QuantityEvidence.decimal("0.7"), result.realizedQty)
        assertEquals(FillCompleteness.TERMINAL, result.completeness)
    }

    @Test fun rejectedZeroAndContradictoryFill() {
        assertEquals(QuantityEvidence.ZERO, derive(fixture(observations = listOf("rejected" to "0"))).realizedQty)
        assertEquals(PositionIntegrity.INCONSISTENT, derive(fixture(observations = listOf("rejected" to "0.3"))).integrity)
    }

    @Test fun missingFinalEvidenceNeverInventsTotal() {
        val f = fixture(observations = listOf("partially_filled" to "0.7", "canceled" to null))
        // No explicit decimal exists for the missing final quantity.
        val result = deriver.derive(f.history)
        assertEquals(DecimalQuantity.parse("0.7"), result.realizedQty.quantity)
        assertEquals(FillCompleteness.UNKNOWN, result.completeness)
        assertTrue(PositionDiagnostic.TERMINAL_QUANTITY_UNKNOWN in result.diagnostics)
    }

    @ParameterizedTest @ValueSource(strings = ["BUY", "SELL"])
    fun signUsesRealizedQuantity(side: String) {
        val result = derive(fixture(side = side, requested = "2", observations = listOf("canceled" to "0.7")))
        assertEquals(DecimalQuantity.parse(if (side == "BUY") "0.7" else "-0.7"), result.signedRealizedQty.quantity)
    }

    @Test fun decreasingAndImpossibleQuantitiesFailClosed() {
        listOf(
            listOf("partially_filled" to "0.7", "partially_filled" to "0.4"),
            listOf("filled" to "2"), listOf("partially_filled" to "-0.1"),
            listOf("filled" to "0.5"), listOf("partially_filled" to "0"),
            listOf("partially_filled" to "1"), listOf("new" to "0.2"),
        ).forEach { rows ->
            val result = derive(fixture(observations = rows))
            assertEquals(PositionIntegrity.INCONSISTENT, result.integrity, rows.toString())
            assertNull(result.signedRealizedQty.quantity)
        }
    }

    @Test fun terminalChangesAndRegressionFailClosed() {
        val f = fixture(observations = listOf("filled" to "1", "filled" to "1"))
        val last = f.history.lifecycleObservations.last()
        listOf(
            last.copy(filledQuantity = 0.8), last.copy(filledAveragePriceUsd = 101.0),
            last.copy(filledAtIso = "2026-09-17T12:01:00Z"),
            last.copy(status = "NEW", rawStatus = "new", terminal = false),
        ).forEach { changed ->
            val h = f.history.copy(lifecycleObservations = listOf(f.history.lifecycleObservations.first(), changed), currentLifecycle = changed)
            assertEquals(PositionIntegrity.INCONSISTENT, deriver.derive(h).integrity)
        }
    }

    @Test fun durableIdsNotClockOrCollectionOrderControlDerivation() {
        val f = fixture(observations = listOf("partially_filled" to "0.4", "filled" to "1"))
        val shuffled = f.history.copy(lifecycleObservations = f.history.lifecycleObservations.reversed())
        assertEquals(derive(f), deriver.derive(shuffled, f.decimals))
    }

    @Test fun duplicateSequencesOrWrongCurrentProjectionFailClosed() {
        val f = fixture()
        assertEquals(PositionIntegrity.INCONSISTENT, deriver.derive(f.history.copy(
            lifecycleObservations = f.history.lifecycleObservations + f.history.lifecycleObservations,
        )).integrity)
        assertEquals(PositionIntegrity.INCONSISTENT, deriver.derive(f.history.copy(currentLifecycle = null)).integrity)
    }

    @Test fun canonicalErrorsAndUnknownIdentityArePreserved() {
        val f = fixture()
        val bad = listOf(
            f.history.copy(integrityStatus = PaperHistoryIntegrityStatus.INCONSISTENT,
                integrityDiagnostics = listOf(PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY)),
            f.history.copy(ambiguous = true), f.history.copy(side = "UNKNOWN"),
            f.history.copy(symbol = null), f.history.copy(symbol = "?"), f.history.copy(quantity = Double.NaN),
            f.history.copy(quantity = -1.0), f.history.copy(orderSequenceId = null),
        )
        bad.forEach { assertEquals(PositionIntegrity.INCONSISTENT, deriver.derive(it).integrity) }
        assertTrue(PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY in deriver.derive(bad.first()).canonicalDiagnostics)
    }

    @Test fun legacyIsKnownButNotExact() {
        val result = deriver.derive(fixture().history)
        assertEquals(DecimalQuantity.parse("1"), result.realizedQty.quantity)
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, result.realizedQty.provenance)
        assertEquals(PositionIntegrity.RELIABLE, result.integrity)
    }

    @Test fun badPrecisionBindingsCannotOverrideCanonicalEvidence() {
        val f = fixture()
        listOf(
            f.decimals.copy(identity = f.decimals.identity.copy(attemptId = "other")),
            f.decimals.copy(requestedQuantity = QuantityEvidence.decimal("2")),
            f.decimals.copy(observations = mapOf(10L to DecimalFillObservation("wrong", QuantityEvidence.decimal("1")))),
            f.decimals.copy(observations = mapOf(10L to DecimalFillObservation("a:filled:1", QuantityEvidence.INVALID))),
            f.decimals.copy(observations = f.decimals.observations + (99L to f.decimals.observations.getValue(10L))),
        ).forEach { assertEquals(PositionIntegrity.INCONSISTENT, deriver.derive(f.history, it).integrity) }
    }

    @Test fun unknownStatusOrUntrustedSourceDoesNotProduceCompleteFill() {
        val unknown = deriver.derive(fixture(observations = listOf("future_status" to "0.1")).history)
        assertEquals(FillCompleteness.UNKNOWN, unknown.completeness)
        val f = fixture()
        val row = f.history.currentLifecycle!!.copy(httpStatusCode = 500)
        val result = deriver.derive(f.history.copy(lifecycleObservations = listOf(row), currentLifecycle = row))
        assertNull(result.realizedQty.quantity)
        assertEquals(PositionIntegrity.UNCERTAIN, result.integrity)
    }

    @Test fun missingFinalDecimalRemainsUnknownWithExplicitEvidence() {
        val result = derive(fixture(observations = listOf("partially_filled" to "0.7", "expired" to null)))
        assertEquals(QuantityEvidence.decimal("0.7"), result.realizedQty)
        assertEquals(FillCompleteness.UNKNOWN, result.completeness)
        assertEquals(PositionIntegrity.UNCERTAIN, result.integrity)
    }

    @ParameterizedTest @ValueSource(strings = ["NaN", "Infinity", "-Infinity"])
    fun nonFiniteFillNeverContributes(text: String) {
        val result = derive(fixture(observations = listOf("partially_filled" to text)))
        assertEquals(PositionIntegrity.INCONSISTENT, result.integrity)
        assertNull(result.realizedQty.quantity)
    }

    @Test fun inventedLocalFillAndPartialToNewAreInconsistent() {
        assertEquals(PositionIntegrity.INCONSISTENT, derive(fixture(observations = listOf("submitted" to "0.5"))).integrity)
        assertEquals(PositionIntegrity.INCONSISTENT, derive(fixture(observations = listOf("partially_filled" to "0.4", "new" to "0.4"))).integrity)
    }

    @Test fun rejectedAfterPartialCannotEraseRealizedQuantity() {
        val result = derive(fixture(observations = listOf("partially_filled" to "0.4", "rejected" to "0")))
        assertEquals(PositionIntegrity.INCONSISTENT, result.integrity)
        assertTrue(PositionDiagnostic.DECREASING_FILL in result.diagnostics)
        assertNull(result.signedRealizedQty.quantity)
    }

    @Test fun newMetadataWarningsAreNotAutomaticallyCorruption() {
        val f = fixture()
        val history = f.history.copy(integrityStatus = PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS,
            integrityDiagnostics = listOf(PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN))
        assertEquals(PositionIntegrity.RELIABLE, deriver.derive(history, f.decimals).integrity)
        assertEquals(QuantityEvidence.decimal("1"), deriver.derive(history, f.decimals).realizedQty)
    }
}
