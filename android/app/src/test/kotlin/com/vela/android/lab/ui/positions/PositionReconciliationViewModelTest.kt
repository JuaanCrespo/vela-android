@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vela.android.lab.ui.positions

import androidx.lifecycle.viewModelScope
import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import com.vela.android.lab.data.paper.reconciliation.integration.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PositionReconciliationViewModelTest {
    @BeforeEach fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun restoreDispatcher() { Dispatchers.resetMain() }
    private fun vm(rig: PositionIntegrationRig) = PositionReconciliationViewModel(rig.store(), now = { rig.now })

    @Test fun `failed invalidation leaves durable active anchor and reports sanitized failure`() = runTest {
        val rig = PositionIntegrationRig(); val real = rig.store(); real.refreshManually()
        real.establishBaseline(real.selectBaselineSymbol("SPY"))
        val store = object : PositionReconciliationStore by real {
            override suspend fun invalidateBaseline(anchorId: String) { error("secret unsafe body") }
        }
        val vm = PositionReconciliationViewModel(store, now = { rig.now })
        vm.requestInvalidateBaseline(rig.dao.anchors.keys.single()); vm.confirmInvalidateBaseline()
        assertEquals(PositionUiError.ANCHOR_INVALIDATION_FAILED, vm.uiState.value.error)
        assertEquals("ACTIVE", vm.uiState.value.durable.anchors.single().metadata.status)
        assertEquals(1, rig.dao.anchorEvents.size); assertFalse(vm.uiState.value.toString().contains("secret unsafe"))
    }

    @Test fun `reconstructed last failed attempt stays distinct from last good snapshot`() = runTest {
        val rig = PositionIntegrationRig(); val first = vm(rig); first.refreshPositions()
        val saved = first.uiState.value.durable.latestComplete
        rig.positionsResponse = CaptureHttpResponse(500, "unsafe body"); first.refreshPositions(); rig.requests.clear()
        val second = vm(rig)
        assertEquals(saved, second.uiState.value.durable.latestComplete)
        assertEquals("FAILED", second.uiState.value.durable.lastAttempt!!.metadata.completeness)
        assertTrue(rig.requests.isEmpty()); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `init empty offline state never calls broker`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig)
        assertFalse(vm.uiState.value.loadingLocal); assertNull(vm.uiState.value.durable.latestComplete)
        assertEquals(PositionRefreshState.IDLE, vm.uiState.value.refreshState)
        assertTrue(rig.requests.isEmpty()); assertTrue(rig.dao.reports.isEmpty())
    }

    @Test fun `screen reentry and diagnostic inspection remain offline`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig)
        repeat(3) { vm.loadOffline(); vm.toggleDiagnostics() }
        assertTrue(rig.requests.isEmpty()); assertTrue(rig.dao.reports.isEmpty())
        assertTrue(vm.uiState.value.diagnosticsExpanded)
    }

    @Test fun `success shows only persisted and reloaded snapshot and report`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); rig.events.clear()
        rig.onRequest = { assertNull(vm.uiState.value.durable.latestComplete) }
        vm.refreshPositions()
        assertEquals(PositionRefreshState.SUCCESS, vm.uiState.value.refreshState)
        assertEquals(rig.dao.brokerSnapshots.values.single(), vm.uiState.value.durable.latestComplete!!.metadata)
        assertEquals(rig.dao.reports.values.single(), vm.uiState.value.durable.latestReport!!.metadata)
        assertEquals(listOf("persist-snapshot", "persist-report", "load-overview"), rig.events)
        assertEquals(2, rig.requests.size)
    }

    @Test fun `concurrent refresh click is ignored and button is disabled`() = runTest {
        val rig = PositionIntegrationRig(); val gate = CompletableDeferred<Unit>()
        rig.onRequest = { if (it == PaperCaptureEndpoint.ACCOUNT) gate.await() }
        val vm = vm(rig); vm.refreshPositions()
        assertEquals(PositionRefreshState.REFRESHING, vm.uiState.value.refreshState)
        assertFalse(vm.uiState.value.canRefresh); vm.refreshPositions(); vm.loadOffline()
        assertEquals(1, rig.requests.size); gate.complete(Unit); runCurrent()
        assertEquals(2, rig.requests.size); assertEquals(PositionRefreshState.SUCCESS, vm.uiState.value.refreshState)
    }

    @Test fun `BUSY result is explicit without endless spinner`() = runTest {
        val rig = PositionIntegrationRig()
        val store = object : PositionReconciliationStore by rig.store() {
            override suspend fun refreshManually() = PositionRefreshResult.BUSY
        }
        val vm = PositionReconciliationViewModel(store); vm.refreshPositions()
        assertEquals(PositionRefreshState.BLOCKED, vm.uiState.value.refreshState)
        assertEquals(PositionUiError.BUSY, vm.uiState.value.error); assertTrue(vm.uiState.value.canRefresh)
    }

    @Test fun `failed refresh keeps last complete and report and shows failure separately`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions()
        val old = vm.uiState.value.durable; rig.accountResponse = CaptureHttpResponse(500, "unsafe-secret")
        vm.refreshPositions(); val state = vm.uiState.value
        assertEquals(PositionRefreshState.FAILED, state.refreshState)
        assertEquals(PositionUiError.BROKER_READ_FAILED, state.error)
        assertEquals(old.latestComplete, state.durable.latestComplete); assertEquals(old.latestReport, state.durable.latestReport)
        assertEquals("FAILED", state.durable.lastAttempt!!.metadata.completeness)
        assertFalse(state.toString().contains("unsafe-secret")); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `failed report persistence never displays a new durable report`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); rig.failReport = true
        vm.refreshPositions()
        assertEquals(PositionUiError.RECONCILIATION_PERSISTENCE_FAILED, vm.uiState.value.error)
        assertNotNull(vm.uiState.value.durable.latestComplete); assertNull(vm.uiState.value.durable.latestReport)
        assertEquals("NOT COMPARABLE", vm.uiState.value.rows.single().difference)
    }

    @Test fun `capture persistence failure never invents success`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); rig.failSnapshot = true; vm.refreshPositions()
        assertEquals(PositionUiError.CAPTURE_PERSISTENCE_FAILED, vm.uiState.value.error)
        assertNull(vm.uiState.value.durable.latestComplete); assertNull(vm.uiState.value.durable.latestReport)
    }

    @Test fun `Room reload failure after capture cannot be presented as successful UI`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); rig.failLoad = true; vm.refreshPositions()
        assertEquals(PositionRefreshState.BLOCKED, vm.uiState.value.refreshState)
        assertNull(vm.uiState.value.durable.latestComplete); assertFalse(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.toString().contains("secret unsafe"))
    }

    @Test fun `initial local read failure fails closed and allows later local reload`() = runTest {
        val rig = PositionIntegrationRig(); rig.failLoad = true; val vm = vm(rig)
        assertEquals(PositionUiError.LOCAL_READ_FAILED, vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy); rig.failLoad = false; vm.loadOffline()
        assertNull(vm.uiState.value.error); assertTrue(rig.requests.isEmpty())
    }

    @Test fun `unanchored UI keeps broker five delta two expected unknown and incomparable`() = runTest {
        val rig = PositionIntegrationRig(); rig.addExact(fixture(requested = "2"))
        val vm = vm(rig); vm.refreshPositions(); val row = vm.uiState.value.rows.single()
        assertEquals("5", row.brokerObserved); assertEquals("2", row.knownVelaDelta)
        assertEquals("UNKNOWN", row.expected); assertEquals("NOT COMPARABLE", row.difference)
        assertEquals(PositionReconciliationState.UNANCHORED, row.state)
    }

    @Test fun `select and first establish tap do not create anchor and require final confirmation`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); rig.requests.clear()
        vm.selectBaselineSymbol("SPY"); assertTrue(vm.uiState.value.canEstablish)
        vm.requestEstablishBaseline(); assertTrue(vm.uiState.value.dialog is PositionBaselineDialog.Establish)
        assertTrue(rig.dao.anchors.isEmpty()); assertFalse(vm.uiState.value.canRefresh)
        vm.confirmEstablishBaseline(); assertEquals(1, rig.dao.anchors.size)
        assertNull(vm.uiState.value.dialog); assertEquals("ACTIVE", vm.uiState.value.durable.anchors.single().metadata.status)
        assertTrue(rig.requests.isEmpty()); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `dismiss confirmation makes no write and confirm without dialog is a no-op`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); vm.dismissBaselineDialog(); vm.confirmEstablishBaseline()
        assertTrue(rig.dao.anchors.isEmpty()); assertNull(vm.uiState.value.dialog)
    }

    @Test fun `double confirmation creates exactly one anchor`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); vm.confirmEstablishBaseline(); vm.confirmEstablishBaseline()
        assertEquals(1, rig.dao.anchors.size); assertEquals(1, rig.dao.anchorEvents.size)
    }

    @Test fun `anchor failure does not claim active state or leak exception`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); rig.failAnchor = true; vm.confirmEstablishBaseline()
        assertEquals(PositionUiError.ANCHOR_CREATE_FAILED, vm.uiState.value.error)
        assertTrue(vm.uiState.value.durable.anchors.isEmpty()); assertFalse(vm.uiState.value.toString().contains("secret unsafe"))
    }

    @Test fun `ineligible selection disables anchor action with reason`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.selectBaselineSymbol("SPY")
        assertFalse(vm.uiState.value.canEstablish)
        assertEquals(BaselineBlockedReason.NO_SNAPSHOT, vm.uiState.value.selectedBaseline!!.blockedReason)
        vm.requestEstablishBaseline(); assertNull(vm.uiState.value.dialog)
    }

    @Test fun `active conflict cannot be silently overwritten through UI`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); vm.confirmEstablishBaseline(); vm.selectBaselineSymbol("SPY")
        assertFalse(vm.uiState.value.canEstablish)
        assertEquals(BaselineBlockedReason.ACTIVE_CONFLICT, vm.uiState.value.selectedBaseline!!.blockedReason)
    }

    @Test fun `invalidate requires confirmation and appends local event only`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); vm.confirmEstablishBaseline(); val id = rig.dao.anchors.keys.single(); rig.requests.clear()
        vm.requestInvalidateBaseline(id); assertEquals("ACTIVE", rig.dao.anchors.getValue(id).status)
        assertTrue(vm.uiState.value.dialog is PositionBaselineDialog.Invalidate)
        vm.confirmInvalidateBaseline(); vm.confirmInvalidateBaseline()
        assertEquals("INVALIDATED", vm.uiState.value.durable.anchors.single().metadata.status)
        assertEquals(2, rig.dao.anchorEvents.size); assertTrue(rig.requests.isEmpty())
        vm.requestInvalidateBaseline(id); assertNull(vm.uiState.value.dialog)
    }

    @Test fun `anchor invalidation makes previously saved comparison non comparable without new report`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); vm.selectBaselineSymbol("SPY")
        vm.requestEstablishBaseline(); vm.confirmEstablishBaseline(); vm.refreshPositions()
        assertEquals(PositionReconciliationState.MATCH, vm.uiState.value.rows.single().state)
        val id = rig.dao.anchors.keys.single(); val reports = rig.dao.reports.size
        vm.requestInvalidateBaseline(id); vm.confirmInvalidateBaseline()
        val row = vm.uiState.value.rows.single()
        assertEquals(PositionReconciliationState.ANCHOR_INVALID, row.state)
        assertEquals("NOT COMPARABLE", row.difference); assertEquals("UNKNOWN", row.expected)
        assertEquals(reports, rig.dao.reports.size)
    }

    @Test fun `baseline creation changes coverage display without claiming a new observation`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions()
        val previous = vm.uiState.value.durable.latestReport
        vm.selectBaselineSymbol("SPY"); vm.requestEstablishBaseline(); vm.confirmEstablishBaseline()
        assertEquals(previous, vm.uiState.value.durable.latestReport)
        assertTrue("BASELINE_CHANGED_REFRESH_REQUIRED" in vm.uiState.value.rows.single().diagnostics)
        assertEquals("NOT COMPARABLE", vm.uiState.value.rows.single().difference)
    }

    @Test fun `fresh ViewModel reconstructs state from durable storage with no GET`() = runTest {
        val rig = PositionIntegrationRig(); val original = vm(rig); original.refreshPositions()
        original.selectBaselineSymbol("SPY"); original.requestEstablishBaseline(); original.confirmEstablishBaseline(); original.refreshPositions()
        val saved = original.uiState.value.durable; rig.requests.clear()
        val recreated = vm(rig)
        assertEquals(saved, recreated.uiState.value.durable); assertEquals(original.uiState.value.rows, recreated.uiState.value.rows)
        assertTrue(rig.requests.isEmpty()); assertNull(recreated.uiState.value.dialog)
    }

    @Test fun `staleness is projected locally with no fresh capture or report`() = runTest {
        val rig = PositionIntegrationRig(); val vm = vm(rig); vm.refreshPositions(); rig.requests.clear()
        rig.now += 60_001; vm.loadOffline()
        assertEquals(SnapshotFreshness.STALE, vm.uiState.value.freshness)
        assertEquals(PositionReconciliationState.STALE, vm.uiState.value.rows.single().state)
        assertTrue(rig.requests.isEmpty()); assertEquals(1, rig.dao.reports.size)
    }

    @Test fun `process scope cancellation clears refreshing and releases coordinator lock`() = runTest {
        val rig = PositionIntegrationRig(); val gate = CompletableDeferred<Unit>()
        rig.onRequest = { gate.await() }; val vm = vm(rig); vm.refreshPositions(); vm.viewModelScope.cancel(); runCurrent()
        assertFalse(vm.uiState.value.busy); assertTrue(rig.dao.reports.isEmpty())
        rig.onRequest = {}; assertEquals(PositionRefreshResult.SUCCESS, rig.store().refreshManually())
    }
}
