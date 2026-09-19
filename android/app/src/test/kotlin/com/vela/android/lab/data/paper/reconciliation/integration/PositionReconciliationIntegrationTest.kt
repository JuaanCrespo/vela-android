package com.vela.android.lab.data.paper.reconciliation.integration

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PositionReconciliationIntegrationTest {
    @Test fun `imprecise broker baseline cannot be relabelled exact by proposal construction`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        rig.dao.brokerPositions[0] = rig.dao.brokerPositions[0].copy(decimalProvenance = DecimalProvenance.LEGACY_DOUBLE_DERIVED.name)
        val selection = store.selectBaselineSymbol("SPY")
        assertFalse(selection.eligible); assertEquals(BaselineBlockedReason.INSUFFICIENT_LOCAL_EVIDENCE, selection.blockedReason)
    }

    @Test fun `capture that crosses a local history change is not anchor eligible`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store()
        rig.onRequest = { if (it == PaperCaptureEndpoint.POSITIONS) rig.addExact(fixture()) }
        store.refreshManually()
        assertFalse(store.loadOffline().latestComplete!!.anchorEligible)
        assertEquals(BaselineBlockedReason.SNAPSHOT_INELIGIBLE, store.selectBaselineSymbol("SPY").blockedReason)
    }

    @Test fun `scoped SPY inconsistency does not degrade independent AAPL row`() = runTest {
        val rig = PositionIntegrationRig()
        rig.addExact(fixture("spy", symbol = "SPY", orderSequence = 1, firstObservation = 10))
        rig.addExact(fixture("apple", symbol = "AAPL", orderSequence = 3, firstObservation = 20))
        rig.histories = rig.histories.map { if (it.symbol == "SPY") it.copy(
            integrityStatus = com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus.INCONSISTENT,
        ) else it }
        val store = rig.store(); store.refreshManually()
        val rows = store.loadOffline().latestReport!!.report.rows.associateBy { it.symbol }
        assertEquals(PositionReconciliationState.INCONSISTENT_LOCAL_HISTORY, rows.getValue("SPY").state)
        assertEquals(PositionReconciliationState.UNANCHORED, rows.getValue("AAPL").state)
    }

    @Test fun `exact stable histories produce complete anchor cursors without increasing fill delta`() = runTest {
        val rig = PositionIntegrationRig(); rig.addExact(fixture(requested = "0.7"))
        val store = rig.store(); store.refreshManually()
        val choice = store.selectBaselineSymbol("SPY")
        assertTrue(choice.eligible); assertEquals("0.7", choice.proposal!!.cursors.single().includedFilledQty.quantity.toString())
        store.establishBaseline(choice); store.refreshManually()
        val row = store.loadOffline().latestReport!!.report.rows.single()
        assertEquals("5", row.expectedQty.quantity.toString()); assertEquals("0", row.difference.toString())
        assertEquals(PositionReconciliationState.MATCH, row.state)
    }

    @Test fun `zero baseline is disabled for absent symbol when history scope is not known`() = runTest {
        val rig = PositionIntegrationRig(); rig.histories = listOf(fixture().history)
        val store = rig.store(); store.refreshManually()
        assertFalse(store.selectBaselineSymbol("AAPL").eligible); assertTrue(rig.dao.anchors.isEmpty())
    }

    @Test fun `invalidated anchor is not used for expected quantity on next capture`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        store.establishBaseline(store.selectBaselineSymbol("SPY")); store.invalidateBaseline(rig.dao.anchors.keys.single())
        store.refreshManually(); val row = store.loadOffline().latestReport!!.report.rows.single()
        assertNull(row.expectedQty.quantity); assertNull(row.difference)
        assertEquals(PositionReconciliationState.UNANCHORED, row.state)
    }

    @Test fun `offline opening and reconstruction make no GET and no report`() = runTest {
        val rig = PositionIntegrationRig()
        repeat(3) { assertEquals(DurablePositionOverview(), rig.store().loadOffline()) }
        assertTrue(rig.requests.isEmpty()); assertTrue(rig.dao.reports.isEmpty())
    }

    @Test fun `manual complete capture persists snapshot then local report and reloads`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store()
        assertEquals(PositionRefreshResult.SUCCESS, store.refreshManually())
        val data = store.loadOffline()
        assertEquals(listOf(PaperCaptureEndpoint.ACCOUNT, PaperCaptureEndpoint.POSITIONS), rig.requests)
        assertEquals(data.latestComplete, data.lastAttempt)
        assertEquals(data.latestComplete!!.metadata.snapshotId, data.latestReport!!.metadata.brokerSnapshotId)
        assertEquals(listOf("persist-snapshot", "persist-report", "load-overview"), rig.events)
        assertTrue(rig.fullHistoryReads > 0)
        assertEquals(PositionReconciliationState.UNANCHORED, data.latestReport.report.rows.single().state)
    }

    @Test fun `broker five and known delta two without anchor is not expected two or difference three`() = runTest {
        val rig = PositionIntegrationRig()
        rig.addExact(fixture("a", requested = "1", orderSequence = 1, firstObservation = 10))
        rig.addExact(fixture("b", requested = "1", orderSequence = 3, firstObservation = 20))
        val store = rig.store(); store.refreshManually()
        val row = store.loadOffline().latestReport!!.report.rows.single()
        assertEquals("5", row.brokerQty.quantity.toString()); assertEquals("2", row.knownVelaDelta.quantity.toString())
        assertNull(row.expectedQty.quantity); assertNull(row.difference)
        assertEquals(PositionReconciliationState.UNANCHORED, row.state)
    }

    @Test fun `full history is unbounded and includes partial realized fills`() = runTest {
        val rig = PositionIntegrationRig()
        repeat(105) { rig.addExact(fixture("a$it", requested = "1", observations = listOf("partially_filled" to "0.5"),
            orderSequence = it * 3L + 1, firstObservation = it * 3L + 1)) }
        val store = rig.store(); store.refreshManually()
        val row = store.loadOffline().latestReport!!.report.rows.single()
        assertEquals("52.5", row.knownVelaDelta.quantity.toString())
        assertNull(row.expectedQty.quantity)
        assertEquals(105, rig.histories.size)
    }

    @Test fun `legacy scope is not inferred from current Paper account or fills`() = runTest {
        val rig = PositionIntegrationRig(); rig.histories = listOf(fixture().history)
        val store = rig.store(); store.refreshManually()
        val data = store.loadOffline()
        assertEquals(PositionReconciliationState.UNKNOWN, data.latestReport!!.report.rows.single().state)
        assertTrue(PositionDiagnostic.INCOMPLETE_HISTORY in data.latestReport.report.diagnostics)
        assertFalse(store.selectBaselineSymbol("SPY").eligible)
        assertTrue(rig.dao.decimalEvidence.isEmpty())
    }

    @Test fun `account failure makes only one GET and preserves last good anchors and report`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        store.establishBaseline(store.selectBaselineSymbol("SPY"))
        val before = store.loadOffline(); rig.requests.clear()
        rig.accountResponse = CaptureHttpResponse(401, "unsafe raw body", CaptureDiagnostic.AUTH_FAILURE)
        assertEquals(PositionRefreshResult.BROKER_READ_FAILED, store.refreshManually())
        val after = store.loadOffline()
        assertEquals(listOf(PaperCaptureEndpoint.ACCOUNT), rig.requests)
        assertEquals(before.latestComplete, after.latestComplete); assertEquals(before.latestReport, after.latestReport)
        assertEquals(before.anchors, after.anchors); assertEquals("FAILED", after.lastAttempt!!.metadata.completeness)
        assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `positions failure preserves prior completed capture with original timestamp`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val before = store.loadOffline(); rig.now += 100
        rig.positionsResponse = CaptureHttpResponse(500, "raw body")
        assertEquals(PositionRefreshResult.BROKER_READ_FAILED, store.refreshManually())
        val after = store.loadOffline()
        assertEquals(before.latestComplete, after.latestComplete); assertEquals(before.latestReport, after.latestReport)
        assertNotEquals(after.lastAttempt!!.metadata.completedAtEpochMillis, after.latestComplete!!.metadata.completedAtEpochMillis)
        assertEquals(4, rig.requests.size)
    }

    @Test fun `capture persistence failure creates no report and leaves last good intact`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val before = store.loadOffline(); rig.failSnapshot = true
        assertEquals(PositionRefreshResult.CAPTURE_PERSISTENCE_FAILED, store.refreshManually())
        assertEquals(before, store.loadOffline())
    }

    @Test fun `report persistence failure preserves new COMPLETE and historical report separately`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val old = store.loadOffline(); rig.failReport = true; rig.now += 10
        assertEquals(PositionRefreshResult.RECONCILIATION_PERSISTENCE_FAILED, store.refreshManually())
        val next = store.loadOffline()
        assertNotEquals(old.latestComplete!!.metadata.snapshotId, next.latestComplete!!.metadata.snapshotId)
        assertEquals(old.latestReport, next.latestReport); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `coordinator busy is explicit and does not add GET`() = runTest {
        val rig = PositionIntegrationRig(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        rig.onRequest = { if (it == PaperCaptureEndpoint.ACCOUNT) { entered.complete(Unit); release.await() } }
        val first = async { rig.store().refreshManually() }; entered.await()
        assertEquals(PositionRefreshResult.BUSY, rig.store().refreshManually())
        assertEquals(1, rig.requests.size); release.complete(Unit)
        assertEquals(PositionRefreshResult.SUCCESS, first.await()); assertEquals(2, rig.requests.size)
    }

    @Test fun `eligible anchor persists without another GET or report`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually(); rig.requests.clear()
        val choice = store.selectBaselineSymbol(" spy ")
        assertTrue(choice.eligible); assertEquals("SPY", choice.symbol)
        assertTrue(rig.dao.anchors.isEmpty())
        store.establishBaseline(choice)
        val anchor = store.loadOffline().anchors.single()
        assertEquals("5", anchor.metadata.baselineQty); assertEquals("ACTIVE", anchor.metadata.status)
        assertEquals(listOf("CREATED"), rig.dao.anchorEvents.map { it.type })
        assertTrue(rig.requests.isEmpty()); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `absent selected symbol permits exact zero only via explicit selection`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        assertTrue(rig.dao.anchors.isEmpty())
        val choice = store.selectBaselineSymbol("AAPL")
        assertTrue(choice.eligible); assertEquals("0", choice.proposal!!.baselineQty.quantity.toString())
        store.establishBaseline(choice)
        assertEquals("AAPL", rig.dao.anchors.values.single().symbol); assertEquals(2, rig.requests.size)
    }

    @Test fun `existing active anchor blocks silent overwrite even with stale proposal`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val proposal = store.selectBaselineSymbol("SPY"); store.establishBaseline(proposal)
        assertEquals(BaselineBlockedReason.ACTIVE_CONFLICT, store.selectBaselineSymbol("SPY").blockedReason)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.establishBaseline(proposal) } }
        assertEquals(1, rig.dao.anchors.size)
    }

    @Test fun `stale or unknown clock disables selection and is rechecked at confirmation`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val proposal = store.selectBaselineSymbol("SPY"); rig.now += 60_001
        assertEquals(BaselineBlockedReason.SNAPSHOT_NOT_FRESH, store.selectBaselineSymbol("SPY").blockedReason)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.establishBaseline(proposal) } }
        rig.now = 0
        assertFalse(store.selectBaselineSymbol("SPY").eligible); assertTrue(rig.dao.anchors.isEmpty())
    }

    @Test fun `changed local history rejects the pending anchor`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val choice = store.selectBaselineSymbol("SPY"); rig.addExact(fixture())
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.establishBaseline(choice) } }
        assertTrue(rig.dao.anchors.isEmpty())
    }

    @Test fun `new capture or account change rejects old pending anchor`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val choice = store.selectBaselineSymbol("SPY")
        rig.accountResponse = CaptureHttpResponse(200, """{"id":"22222222-2222-2222-2222-222222222222"}""")
        store.refreshManually()
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.establishBaseline(choice) } }
        assertTrue(rig.dao.anchors.isEmpty())
    }

    @Test fun `no snapshot invalid symbol and missing opaque account disable baseline`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store()
        assertEquals(BaselineBlockedReason.NO_SNAPSHOT, store.selectBaselineSymbol("SPY").blockedReason)
        assertEquals(BaselineBlockedReason.INVALID_SYMBOL, store.selectBaselineSymbol("bad/path").blockedReason)
        store.refreshManually()
        val saved = rig.dao.brokerSnapshots.values.single()
        rig.dao.brokerSnapshots[saved.snapshotId] = saved.copy(accountRef = null)
        assertEquals(BaselineBlockedReason.SNAPSHOT_INELIGIBLE, store.selectBaselineSymbol("SPY").blockedReason)
    }

    @Test fun `invalidate appends MANUAL event without HTTP history reset or new report`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        store.establishBaseline(store.selectBaselineSymbol("SPY")); val anchor = store.loadOffline().anchors.single()
        rig.requests.clear(); store.invalidateBaseline(anchor.metadata.anchorId)
        val after = store.loadOffline().anchors.single()
        assertEquals("INVALIDATED", after.metadata.status); assertEquals("MANUAL", after.metadata.invalidationReason)
        assertEquals(anchor.metadata.baselineQty, after.metadata.baselineQty)
        assertEquals(listOf("CREATED", "INVALIDATED"), rig.dao.anchorEvents.map { it.type })
        assertTrue(rig.requests.isEmpty()); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `mismatch never automatically invalidates baseline`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        store.establishBaseline(store.selectBaselineSymbol("SPY")); rig.now += 10
        rig.positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"6"}]""")
        store.refreshManually(); val after = store.loadOffline()
        assertEquals(PositionReconciliationState.MISMATCH, after.latestReport!!.report.rows.single().state)
        assertEquals(PositionDifferenceCause.UNKNOWN, after.latestReport.report.rows.single().cause)
        assertEquals("ACTIVE", after.anchors.single().metadata.status)
        assertEquals(1, rig.dao.anchorEvents.size)
    }

    @Test fun `fresh store reconstructs same durable evidence without network or engine report`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        store.establishBaseline(store.selectBaselineSymbol("SPY")); store.refreshManually()
        val before = store.loadOffline(); rig.requests.clear(); val count = rig.dao.reports.size
        assertEquals(before, rig.store().loadOffline()); assertTrue(rig.requests.isEmpty()); assertEquals(count, rig.dao.reports.size)
    }
}
