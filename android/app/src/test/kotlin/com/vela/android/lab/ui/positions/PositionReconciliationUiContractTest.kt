package com.vela.android.lab.ui.positions

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import com.vela.android.lab.data.paper.reconciliation.integration.*
import com.vela.android.lab.ui.navigation.VelaDestination
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class PositionReconciliationUiContractTest {
    @ParameterizedTest @EnumSource(PositionReconciliationState::class)
    fun `all domain result states stay visible without collapsing uncertainty`(result: PositionReconciliationState) = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val saved = store.loadOffline(); val report = saved.latestReport!!
        val row = report.report.rows.single().copy(state = result, expectedQty = QuantityEvidence.decimal("2"), difference = DecimalQuantity.parse("3"))
        val overview = saved.copy(latestReport = report.copy(report = report.report.copy(rows = listOf(row))))
        val displayed = positionRows(overview, rig.now, PositionObservationPolicy()).single()
        assertEquals(result, displayed.state)
        if (result !in setOf(PositionReconciliationState.MATCH, PositionReconciliationState.MISMATCH)) {
            assertEquals("NOT COMPARABLE", displayed.difference)
        }
        if (result == PositionReconciliationState.UNANCHORED) assertEquals("UNKNOWN", displayed.expected)
    }

    @ParameterizedTest @CsvSource("1.0000,1", "0.700000,0.7", "-1.000,-1", "2.50,2.5", "-0.00,0", "0.123456789012345678901,0.123456789012345678901", "9007199254740993,9007199254740993")
    fun `authoritative quantities retain exact decimal text`(input: String, expected: String) {
        assertEquals(expected, quantityText(QuantityEvidence.decimal(input)))
    }

    @Test fun `unknown and invalid quantities are not rendered as zero`() {
        assertEquals("UNKNOWN", quantityText(QuantityEvidence.UNKNOWN)); assertEquals("UNKNOWN", quantityText(QuantityEvidence.INVALID))
    }

    @Test fun `opaque account reference is abbreviated and raw account identifier is never accepted`() {
        val raw = "11111111-1111-1111-1111-111111111111"
        assertEquals("UNKNOWN", safePaperAccountLabel(raw)); assertEquals("UNKNOWN", safePaperAccountLabel(null))
        val label = safePaperAccountLabel("paper-v1:" + "a".repeat(64))
        assertEquals("aaaaaa…aaaaaa", label); assertFalse(label.contains(raw)); assertFalse(label.contains("paper-v1:"))
    }

    @Test fun `unsafe error diagnostics are never rendered verbatim`() {
        assertEquals(listOf("INVALID_DURABLE_DIAGNOSTICS"), safePositionDiagnostics<CaptureDiagnostic>("[\"raw-secret-account-body\"]"))
        assertEquals(listOf("AUTH_FAILURE"), safePositionDiagnostics<CaptureDiagnostic>("[\"AUTH_FAILURE\"]"))
    }

    @Test fun `route is a secondary item under existing More navigation`() {
        assertEquals(VelaDestination.POSITIONS, VelaDestination.fromRoute("posiciones-paper"))
        assertTrue(VelaDestination.POSITIONS in VelaDestination.secondaryDestinations)
        assertEquals(VelaDestination.MORE, VelaDestination.POSITIONS.selectedPrimaryDestination)
        assertEquals(5, VelaDestination.primaryDestinations.size)
    }

    @Test fun `screen route only reloads offline from composition and wires refresh as callback`() {
        val screen = source("ui/positions/PositionReconciliationScreen.kt")
        val route = screen.substringAfter("fun PositionReconciliationRoute(").substringBefore("/** Pure rendering")
        assertTrue(route.contains("LaunchedEffect(viewModel) { viewModel.loadOffline() }"))
        assertFalse(route.contains("refreshPositions()")); assertTrue(route.contains("viewModel::refreshPositions"))
        assertTrue(screen.contains("Button(onClick = onRefresh, enabled = state.canRefresh)"))
    }

    @Test fun `required semantics confirmations warnings and diagnostics are exposed`() {
        val screen = source("ui/positions/PositionReconciliationScreen.kt")
        listOf("NO BROKER SNAPSHOT YET", "Last attempt", "Latest complete snapshot", "Broker observed:", "VELA known delta:",
            "Baseline:", "VELA expected:", "Difference:", "State:", "Provenance:", "Parser:", "Engine:",
            "Snapshot:", "Local history checkpoint:", "Known VELA delta:", "Motivo seleccionado: MANUAL",
            "Aceptar este baseline NO verifica ni reconstruye actividad anterior de la cuenta.",
            "onClick = onConfirmEstablish", "onClick = onConfirmInvalidate", "BROKER_READ_FAILED").forEach {
            assertTrue(screen.contains(it), "Missing contract: $it")
        }
    }

    @Test fun `mismatch wording stays neutral and contains no corrective trading actions`() {
        val presentation = source("ui/positions/PositionReconciliationUiState.kt")
        assertTrue(presentation.contains("Diferencia de posición sin explicación. Causa: UNKNOWN. Revisar baseline."))
        val all = source("ui/positions/PositionReconciliationScreen.kt") + presentation
        listOf("Fix position", "Sync position", "Buy difference", "Sell difference", "Close difference", "Correct portfolio",
            "Resolve automatically", "external trade", "broker bug", "VELA bug", "manual trade").forEach { assertFalse(all.contains(it)) }
    }

    @Test fun `ViewModel has no SQL HTTP parser credentials or execution imports`() {
        val vm = source("ui/positions/PositionReconciliationViewModel.kt")
        listOf("okhttp", "JSONObject", "credentials", "SELECT ", "execSQL", "PaperOrderExecutor", "ManualPaperSubmit",
            "PaperManualSubmit", ".submit.", ".preflight.", "AlpacaPaperReadOnlyClient", "toDouble(").forEach {
            assertFalse(vm.contains(it), "Forbidden VM dependency: $it")
        }
    }

    @Test fun `integration and UI packages have no execution or legacy dashboard dependency`() {
        val sources = listOf("ui/positions", "data/paper/reconciliation/integration").flatMap { directory ->
            sourceRoot().resolve(directory).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.joinToString("\n") { it.readText() }
        listOf("import com.vela.android.lab.data.paper.submit", "import com.vela.android.lab.data.paper.preflight",
            "import com.vela.android.lab.ui.dashboard", "PaperOrderExecutor", "PaperManualSubmitExecutor",
            "PaperManualSubmitTokenStore", "armSession(", "submitOnce(", "toDouble(", "String.format(").forEach { assertFalse(sources.contains(it), it) }
    }

    @Test fun `coordinator is the only integration network entry and report is only produced there`() {
        val store = source("data/paper/reconciliation/integration/PositionReconciliationStore.kt")
        assertEquals(1, Regex("coordinator\\.captureManually\\(\\)").findAll(store).count())
        assertEquals(1, Regex("reports\\.createReport\\(").findAll(store).count())
        val offline = store.substringAfter("override suspend fun loadOffline()").substringBefore("override suspend fun refreshManually()")
        assertFalse(offline.contains("createReport(")); assertFalse(offline.contains("captureManually("))
        val anchors = store.substringAfter("override suspend fun establishBaseline(")
        assertFalse(anchors.contains("createReport(")); assertFalse(anchors.contains("captureManually("))
        assertFalse(store.contains("getLatestN(")); assertFalse(store.contains("getFilledOrders("))
    }

    @Test fun `Application wiring is inert and points only to strict canonical capture path`() {
        val app = source("VelaLabApplication.kt").substringAfter("val positionReconciliationStore by lazy").substringBefore("val database:")
        assertTrue(app.contains("RoomPositionEvidenceDatabase(database)"))
        assertTrue(app.contains("PaperPositionEvidenceCaptureCoordinator(")); assertTrue(app.contains("PaperPositionEvidenceHttpTransport()"))
        assertFalse(app.contains("captureManually()")); assertFalse(app.contains("createReport("))
        assertFalse(app.contains("paperManualSubmit")); assertFalse(app.contains("AlpacaPaperReadOnlyClient"))
    }

    @Test fun `no scheduler service auto refresh or automatic anchor invalidation is integrated`() {
        val sources = source("ui/positions/PositionReconciliationViewModel.kt") + source("data/paper/reconciliation/integration/PositionReconciliationStore.kt")
        listOf("WorkManager", "AlarmManager", "Timer(", "while (", "delay(", "supersedeAnchor(").forEach { assertFalse(sources.contains(it)) }
        assertEquals(1, Regex("anchors\\.invalidateAnchor\\(").findAll(sources).count())
    }

    @Test fun `clock policy distinguishes stale unknown and fresh without I O`() {
        val policy = PositionObservationPolicy(100)
        assertEquals(SnapshotFreshness.FRESH, policy.freshness(100, 200))
        assertEquals(SnapshotFreshness.STALE, policy.freshness(100, 201))
        assertEquals(SnapshotFreshness.UNKNOWN, policy.freshness(100, 99))
        assertEquals(SnapshotFreshness.UNKNOWN, policy.freshness(-1, 100))
    }

    @Test fun `old report freshness uses its capture time not a newer snapshot or report creation time`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually()
        val old = store.loadOffline(); rig.now += 60_001; rig.failReport = true; store.refreshManually()
        val next = store.loadOffline()
        assertNotEquals(old.latestComplete, next.latestComplete)
        assertEquals(PositionReconciliationState.STALE, positionRows(next, rig.now, PositionObservationPolicy()).single().state)
        assertEquals(old.latestReport, next.latestReport)
    }

    @Test fun `superseded anchor cannot render an old saved MATCH as current comparable`() = runTest {
        val rig = PositionIntegrationRig(); val store = rig.store(); store.refreshManually(); store.establishBaseline(store.selectBaselineSymbol("SPY"))
        store.refreshManually(); val saved = store.loadOffline()
        val old = saved.anchors.single(); rig.dao.anchors[old.metadata.anchorId] = old.metadata.copy(status = "SUPERSEDED", activeKey = null)
        val row = positionRows(store.loadOffline(), rig.now, PositionObservationPolicy()).single()
        assertEquals(PositionReconciliationState.ANCHOR_INVALID, row.state); assertEquals("NOT COMPARABLE", row.difference)
    }

    private fun sourceRoot(): File = listOf(File("src/main/kotlin/com/vela/android/lab"), File("app/src/main/kotlin/com/vela/android/lab"))
        .first { it.isDirectory }
    private fun source(path: String) = sourceRoot().resolve(path).readText()
}
