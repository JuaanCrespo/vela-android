package com.vela.android.lab.data.paper.reconciliation.integration

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import com.vela.android.lab.ui.positions.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

class LegacyBootstrapCoverageTest {
    private suspend fun rig(): PositionIntegrationRig = PositionIntegrationRig().also {
        it.histories = listOf(fixture("a").history, fixture("b", orderSequence = 3, firstObservation = 20).history)
        it.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"8"}]""")
        assertEquals(PositionRefreshResult.SUCCESS, it.store().refreshManually())
    }
    private suspend fun establish(rig: PositionIntegrationRig, symbol: String = "SPY"): PositionAnchor {
        val store = rig.store()
        val choice = store.selectBaselineSymbol(symbol)
        assertTrue(choice.eligible, choice.toString())
        assertEquals(PositionCoverageMode.LEGACY_BOOTSTRAP_V1, choice.proposal!!.coverageMode)
        store.establishBaseline(choice)
        return store.loadOffline().anchors.single().domain()
    }
    private suspend fun state(rig: PositionIntegrationRig, anchor: PositionAnchor) = LocalPositionExpectationEngine()
        .evaluate(ConsistentPositionHistoryReader(rig.database).read(rig.accountRef), listOf(anchor)).positions.single { it.symbol == anchor.symbol }

    @Test fun `legacy A B are absorbed without fake cursors exact evidence or double counting`() = runTest {
        val rig = rig(); val original = rig.histories.toList(); val requests = rig.requests.toList()
        val anchor = establish(rig); val local = state(rig, anchor)
        assertEquals(requests, rig.requests)
        assertEquals(original, rig.histories); assertTrue(rig.dao.decimalEvidence.isEmpty()); assertTrue(rig.dao.anchorCursors.isEmpty())
        assertEquals("2", local.coverageMetadata!!.historicalKnownVelaDelta.quantity.toString())
        assertEquals(DecimalProvenance.LEGACY_DOUBLE_DERIVED, local.coverageMetadata.historicalKnownVelaDelta.provenance)
        assertEquals("0", local.coverageMetadata.postAnchorExactDelta.quantity.toString())
        assertEquals("8", local.expectedAbsoluteQty.quantity.toString()); assertFalse(local.deltaComplete)
        assertEquals(CutAssurance.CONFIRMED, local.coverageMetadata.postAnchorCoverageAssurance)
        assertEquals(PreAnchorHistoryAssurance.LEGACY_UNKNOWN, local.coverageMetadata.preAnchorHistoryAssurance)
        assertEquals(anchor, rig.store().loadOffline().anchors.single().domain())
    }

    @ParameterizedTest @CsvSource("BUY,1,9", "SELL,2,6")
    fun `only exact post cut fill changes expected`(side: String, qty: String, expected: String) = runTest {
        val rig = rig(); val anchor = establish(rig)
        rig.addExact(fixture("post", side = side, requested = qty, orderSequence = 100, firstObservation = 100))
        val local = state(rig, anchor)
        assertEquals(expected, local.expectedAbsoluteQty.quantity.toString())
        assertEquals("2", local.coverageMetadata!!.historicalKnownVelaDelta.quantity.toString())
    }

    @Test fun `post cut raw precision is not reduced to its legacy projection`() = runTest {
        val rig = rig(); val anchor = establish(rig)
        rig.addExact(fixture("post", requested = "0.100000000000000001", orderSequence = 100, firstObservation = 100))
        assertEquals("8.100000000000000001", state(rig, anchor).expectedAbsoluteQty.quantity.toString())
    }

    @ParameterizedTest @CsvSource("8,MATCH,0", "9,MISMATCH,1", "7,MISMATCH,-1")
    fun `bootstrap reconciliation is exact and non mutating`(broker: String, result: String, difference: String) = runTest {
        val rig = rig(); establish(rig); val before = rig.dao.anchors.toMap()
        rig.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"$broker"}]""")
        rig.store().refreshManually()
        val report = rig.store().loadOffline().latestReport!!
        val row = report.report.rows.single()
        assertEquals(result, row.state.name); assertEquals(difference, row.difference.toString())
        assertEquals(POSITION_ENGINE_V2, report.metadata.engineVersion)
        assertEquals(POSITION_POLICY_BOOTSTRAP_V1, report.metadata.policyVersion)
        assertEquals(before, rig.dao.anchors); assertTrue(rig.reports.verifyReplay(report.metadata.reportId))
    }

    @Test fun `QQQ without local orders is isolated from closed legacy SPY and can accept zero`() = runTest {
        val rig = rig(); val anchor = establish(rig, "QQQ")
        assertEquals("0", state(rig, anchor).expectedAbsoluteQty.quantity.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["new", "partially_filled", "submitted", "pending_new"])
    fun `open target exposure including synthetic zero blocks bootstrap`(status: String) = runTest {
        val rig = rig()
        rig.histories += fixture("open", observations = listOf(status to if (status == "partially_filled") "0.4" else "0"), orderSequence = 100, firstObservation = 100).history
        rig.store().refreshManually()
        val selection = rig.store().selectBaselineSymbol("SPY")
        assertFalse(selection.eligible)
        assertEquals(PositionDiagnostic.BOOTSTRAP_OPEN_OR_UNCERTAIN_ORDER, selection.blockedDiagnostic)
    }

    @ParameterizedTest @ValueSource(strings = ["unknown_symbol", "duplicate", "possible_send", "ambiguous"])
    fun `identity and possible send ambiguity are not absorbed`(case: String) = runTest {
        val rig = rig(); val first = rig.histories.first()
        rig.histories = when (case) {
            "unknown_symbol" -> listOf(first.copy(symbol = null))
            "duplicate" -> rig.histories + first.copy(submitAttemptId = "duplicate")
            "possible_send" -> listOf(first.copy(localSubmitResult = "FAILED", resolved = false, unresolved = true))
            else -> listOf(first.copy(ambiguous = true))
        }
        rig.store().refreshManually()
        assertFalse(rig.store().selectBaselineSymbol("SPY").eligible)
        if (case == "unknown_symbol" || case == "duplicate") assertFalse(rig.store().selectBaselineSymbol("QQQ").eligible)
    }

    @Test fun `identical pre cut terminal reread does not add delta or invalidate`() = runTest {
        val rig = rig(); val anchor = establish(rig); val old = rig.histories.first(); val last = old.currentLifecycle!!
        val repeat = last.copy(databaseId = 99, observedAtEpochMillis = 99999, samePayloadAsPrevious = true)
        rig.histories = listOf(old.copy(lifecycleObservations = old.lifecycleObservations + repeat, currentLifecycle = repeat)) + rig.histories.drop(1)
        assertEquals("8", state(rig, anchor).expectedAbsoluteQty.quantity.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["new_fill", "terminal_change", "deleted", "changed_prefix"])
    fun `pre cut new information is never counted as post cut fill`(case: String) = runTest {
        val rig = rig(); val anchor = establish(rig); val old = rig.histories.first(); val last = old.currentLifecycle!!
        val changed = when (case) {
            "new_fill" -> last.copy(databaseId = 99, filledQuantity = 2.0, payloadFingerprint = "new")
            "terminal_change" -> last.copy(databaseId = 99, rawStatus = "canceled", status = "CANCELED", payloadFingerprint = "changed")
            else -> last.copy(payloadFingerprint = "changed")
        }
        rig.histories = if (case == "deleted") rig.histories.drop(1) else listOf(old.copy(
            lifecycleObservations = if (case == "changed_prefix") listOf(changed) else old.lifecycleObservations + changed,
            currentLifecycle = changed)) + rig.histories.drop(1)
        assertNull(state(rig, anchor).expectedAbsoluteQty.quantity)
    }

    @ParameterizedTest @ValueSource(strings = ["missing_decimal", "wrong_account", "late_start", "missing_start", "open"])
    fun `post cut evidence must prove precision account and causal start`(case: String) = runTest {
        val rig = rig(); val anchor = establish(rig)
        val post = fixture("post", orderSequence = 100, firstObservation = 100,
            observations = listOf(if (case == "open") "partially_filled" to "0.4" else "filled" to "1"))
        if (case == "missing_decimal") rig.histories += post.history else rig.addExact(post)
        if (case == "wrong_account") rig.dao.decimalEvidence.replaceAll { it.copy(accountRef = "paper-v1:" + "a".repeat(64)) }
        if (case in setOf("late_start", "missing_start")) rig.histories = rig.histories.map {
            if (it.submitAttemptId == "post") it.copy(auditStartRowId = if (case == "late_start") 1 else null) else it
        }
        assertNull(state(rig, anchor).expectedAbsoluteQty.quantity)
        assertEquals(CutAssurance.UNKNOWN, state(rig, anchor).coverageMetadata!!.postAnchorCoverageAssurance)
    }

    @Test fun `post cut cumulative repeated partials contribute one final total`() = runTest {
        val rig = rig(); val anchor = establish(rig)
        rig.addExact(fixture("post", orderSequence = 100, firstObservation = 100, observations = listOf(
            "partially_filled" to "0.4", "partially_filled" to "0.4", "partially_filled" to "0.7", "filled" to "1")))
        assertEquals("9", state(rig, anchor).expectedAbsoluteQty.quantity.toString())
    }

    @Test fun `late original precision for legacy order is new information not a proven raw duplicate`() = runTest {
        val rig = rig(); val anchor = establish(rig); val old = rig.histories.first(); val last = old.currentLifecycle!!
        val repeated = last.copy(databaseId = 99, observedAtEpochMillis = 99999, samePayloadAsPrevious = true)
        rig.histories = listOf(old.copy(lifecycleObservations = old.lifecycleObservations + repeated, currentLifecycle = repeated)) + rig.histories.drop(1)
        listOf("qty", "filled_qty").forEach { field -> rig.dao.decimalEvidence += com.vela.android.lab.db.room.entities.PaperOrderDecimalEvidenceEntity(
            99, field, old.submitAttemptId, old.alpacaOrderId!!, old.clientOrderId!!, "SPY", "BUY", old.orderSequenceId!!,
            rig.accountRef, "1", "1", "EXACT_DECIMAL", repeated.payloadFingerprint, "ORDER_DECIMAL_SIDECAR_V1", rig.now) }
        assertNull(state(rig, anchor).expectedAbsoluteQty.quantity)
        assertTrue(PositionDiagnostic.BOOTSTRAP_CROSS_CUT_EVIDENCE in state(rig, anchor).diagnostics)
    }

    @Test fun `identity ambiguity on an apparently unrelated symbol is not safely isolated`() = runTest {
        val rig = rig()
        rig.histories = rig.histories.map { it.copy(mappingState = "AMBIGUOUS") }
        rig.store().refreshManually()
        val selection = rig.store().selectBaselineSymbol("QQQ")
        assertFalse(selection.eligible); assertEquals(PositionDiagnostic.AMBIGUOUS_IDENTITY, selection.blockedDiagnostic)
    }

    @Test fun `new raw precision is a duplicate only when the cut actually preserved identical source quantities`() = runTest {
        val rig = PositionIntegrationRig(); val original = fixture()
        rig.addExact(original); rig.store().refreshManually()
        val snapshot = rig.snapshots.latestComplete()!!
        val anchor = rig.anchors.prepareBootstrapAnchor("bootstrap-exact-source", "SPY", snapshot.metadata.snapshotId, rig.now)
        rig.anchors.createAnchor(anchor, snapshot.metadata.snapshotId, CutAssurance.UNKNOWN)
        val last = original.history.currentLifecycle!!; val repeated = last.copy(databaseId = 99, samePayloadAsPrevious = true)
        rig.histories = listOf(original.history.copy(lifecycleObservations = original.history.lifecycleObservations + repeated, currentLifecycle = repeated))
        rig.dao.decimalEvidence += rig.dao.decimalEvidence.toList().map { it.copy(observationId = 99) }
        assertEquals("5", state(rig, anchor).expectedAbsoluteQty.quantity.toString())
    }

    @ParameterizedTest @ValueSource(strings = ["stale", "unknown_account", "incomplete", "count_mismatch", "config_changed"])
    fun `broker and configuration gates remain mandatory for bootstrap`(case: String) = runTest {
        val rig = rig(); val saved = rig.snapshots.latestComplete()!!.metadata
        when (case) {
            "stale" -> rig.now += 60_001
            "config_changed" -> rig.credentials.current = com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials("CHANGED", "CHANGED")
            else -> rig.dao.brokerSnapshots[saved.snapshotId] = when (case) {
                "unknown_account" -> saved.copy(accountRef = null)
                "incomplete" -> saved.copy(completeness = "PARTIAL")
                else -> saved.copy(positionsReceivedCount = saved.positionsReceivedCount + 1)
            }
        }
        assertFalse(rig.store().selectBaselineSymbol("SPY").eligible)
        assertTrue(rig.dao.anchors.isEmpty())
    }

    @ParameterizedTest @ValueSource(strings = ["stale", "history_changed", "open_appeared", "conflict", "account_changed"])
    fun `confirmation revalidates prepared proposal`(case: String) = runTest {
        val rig = rig(); val store = rig.store(); val choice = store.selectBaselineSymbol("SPY")
        when (case) {
            "stale" -> rig.now += 60_001
            "history_changed" -> rig.histories += fixture("new", orderSequence = 100, firstObservation = 100).history
            "open_appeared" -> rig.histories += fixture("new", orderSequence = 100, firstObservation = 100, observations = listOf("submitted" to "0")).history
            "conflict" -> store.establishBaseline(choice)
            else -> { rig.accountResponse = CaptureHttpResponse(200, """{"id":"22222222-2222-2222-2222-222222222222"}"""); store.refreshManually() }
        }
        val count = rig.dao.anchors.size
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.establishBaseline(choice) } }
        assertEquals(count, rig.dao.anchors.size)
    }

    @Test fun `invalidation preserves cut and original reports and appends event`() = runTest {
        val rig = rig(); val beforeV1 = rig.dao.reports.values.single(); val beforeRows = rig.dao.reportRows.toList()
        val anchor = establish(rig); rig.store().refreshManually()
        val bootstrapReport = rig.store().loadOffline().latestReport!!
        val before = rig.dao.anchors.getValue(anchor.anchorId)
        rig.now += 1; rig.store().invalidateBaseline(anchor.anchorId)
        val after = rig.dao.anchors.getValue(anchor.anchorId)
        assertEquals(before.bootstrapCutJson, after.bootstrapCutJson); assertEquals(before.bootstrapCutDigest, after.bootstrapCutDigest)
        assertEquals(before.coverageMode, after.coverageMode); assertEquals(before.baselineQty, after.baselineQty)
        assertEquals(listOf("CREATED", "INVALIDATED"), rig.dao.anchorEvents.map { it.type })
        assertEquals(beforeV1, rig.dao.reports[beforeV1.reportId]); assertEquals(beforeRows, rig.dao.reportRows.filter { it.reportId == beforeV1.reportId })
        assertTrue(rig.reports.verifyReplay(beforeV1.reportId)); assertTrue(rig.reports.verifyReplay(bootstrapReport.metadata.reportId))
    }

    @Test fun `manifest serialization is canonical ordered and independent of locale`() = runTest {
        val rig = rig(); val anchor = establish(rig); val cut = anchor.bootstrapCut!!
        val json = BootstrapEvidenceCodec.encode(cut); val hash = BootstrapEvidenceCodec.digest(cut)
        assertEquals(cut, BootstrapEvidenceCodec.decode(json, hash))
        assertEquals(json, BootstrapEvidenceCodec.encode(cut.copy(inventory = cut.inventory.reversed())))
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("ar-EG")); assertEquals(hash, BootstrapEvidenceCodec.digest(cut)) }
        finally { Locale.setDefault(original) }
        assertThrows(IllegalArgumentException::class.java) { BootstrapEvidenceCodec.decode(json + " ", hash) }
    }

    @ParameterizedTest @ValueSource(strings = ["digest", "json", "mode", "version"])
    fun `tampered manifest metadata fails closed`(case: String) = runTest {
        val rig = rig(); val anchor = establish(rig); val old = rig.dao.anchors.getValue(anchor.anchorId)
        rig.dao.anchors[anchor.anchorId] = when (case) {
            "digest" -> old.copy(bootstrapCutDigest = "0".repeat(64))
            "json" -> old.copy(bootstrapCutJson = old.bootstrapCutJson!!.replace("SPY", "QQQ"))
            "mode" -> old.copy(coverageMode = "EXACT_CURSORS_V1")
            else -> old.copy(coverageMode = "UNKNOWN_V99")
        }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rig.anchors.get(anchor.anchorId)!!.domain() } }
    }

    @Test fun `unsupported replay policy is explicit not latest algorithm fallback`() = runTest {
        val rig = rig(); establish(rig); rig.store().refreshManually()
        val report = rig.store().loadOffline().latestReport!!
        rig.dao.reports[report.metadata.reportId] = report.metadata.copy(policyVersion = "future")
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rig.reports.verifyReplay(report.metadata.reportId) } }
    }

    @Test fun `new UI separates historical and post delta and preserves old report rendering`() = runTest {
        val rig = rig(); val original = positionRows(rig.store().loadOffline(), rig.now, PositionObservationPolicy()).single()
        assertNull(original.coverageMode)
        establish(rig); rig.store().refreshManually()
        val row = positionRows(rig.store().loadOffline(), rig.now, PositionObservationPolicy()).single()
        assertEquals(PositionCoverageMode.LEGACY_BOOTSTRAP_V1, row.coverageMode)
        assertTrue(row.historicalKnownDelta!!.startsWith("2 · LEGACY")); assertEquals("0", row.postAnchorExactDelta)
        assertEquals("8", row.baseline); assertEquals("8", row.expected)
        assertTrue(BOOTSTRAP_CONFIRMATION_WARNING.contains("no certifica ni reconstruye"))
        assertTrue(BOOTSTRAP_CONFIRMATION_WARNING.contains("no envía órdenes"))
    }

    @Test fun `exact mode remains available and explicit repository policy rejects legacy`() = runTest {
        val rig = rig(); val snapshot = rig.snapshots.latestComplete()!!
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { rig.anchors.prepareAnchor("exact", "SPY", snapshot.metadata.snapshotId, CutAssurance.UNKNOWN, rig.now) } }
        val empty = PositionIntegrationRig(); empty.store().refreshManually()
        assertEquals(PositionCoverageMode.EXACT_CURSORS_V1, empty.store().selectBaselineSymbol("SPY").proposal!!.coverageMode)
    }
}
