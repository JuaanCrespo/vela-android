@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.history

import com.vela.android.lab.data.paper.history.CanonicalPaperLifecycleObservation
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic
import com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus
import com.vela.android.lab.data.paper.history.PaperOrderHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperOrderHistoryViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty repository becomes a stable empty state`() = runTest {
        val vm = PaperOrderHistoryViewModel(FakePaperHistoryReader())

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.orders.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `load A and B preserves deterministic repository order with equal timestamps`() = runTest {
        val records = listOf(record("order-b"), record("order-a"))
        val reader = FakePaperHistoryReader(latest = records)
        val vm = PaperOrderHistoryViewModel(reader, initialLimit = 2)

        assertEquals(listOf("order-b", "order-a"), vm.uiState.value.orders.map { it.submitAttemptId })
        assertEquals(
            vm.uiState.value.orders[0].localSubmitResultAtEpochMillis,
            vm.uiState.value.orders[1].localSubmitResultAtEpochMillis,
        )
        assertEquals(listOf(2), reader.latestLimits)
    }

    @Test
    fun `initial limit remains enforced against an over-returning source`() = runTest {
        val reader = FakePaperHistoryReader(
            latest = listOf(record("3"), record("2"), record("1")),
        )
        val vm = PaperOrderHistoryViewModel(reader, initialLimit = 2)

        assertEquals(listOf("3", "2"), vm.uiState.value.orders.map { it.submitAttemptId })
    }

    @Test
    fun `filled filter uses dedicated query`() = runTest {
        val filled = record("filled", status = "FILLED", terminal = true)
        val reader = FakePaperHistoryReader(latest = listOf(record("new")), filled = listOf(filled))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onStatusFilterSelected(PaperHistoryStatusFilter.FILLED)

        assertEquals(listOf(filled), vm.uiState.value.orders)
        assertEquals(1, reader.filledCalls)
    }

    @Test
    fun `terminal filter uses dedicated query and excludes nonterminal rows`() = runTest {
        val terminal = record("terminal", status = "CANCELED", terminal = true)
        val reader = FakePaperHistoryReader(
            terminal = listOf(terminal, record("new")),
        )
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onStatusFilterSelected(PaperHistoryStatusFilter.TERMINAL)

        assertEquals(listOf(terminal), vm.uiState.value.orders)
        assertEquals(1, reader.terminalCalls)
    }

    @Test
    fun `unresolved filter stays on bounded local latest evidence`() = runTest {
        val unresolved = record("open", unresolved = true)
        val reader = FakePaperHistoryReader(latest = listOf(unresolved, record("resolved")))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onStatusFilterSelected(PaperHistoryStatusFilter.UNRESOLVED)

        assertEquals(listOf(unresolved), vm.uiState.value.orders)
        assertEquals(2, reader.latestLimits.size)
    }

    @Test
    fun `warnings filter exposes only valid with warnings`() = runTest {
        val warning = record(
            "warning",
            integrity = PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS,
        )
        val reader = FakePaperHistoryReader(latest = listOf(warning, record("valid")))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onStatusFilterSelected(PaperHistoryStatusFilter.WARNINGS)

        assertEquals(listOf(warning), vm.uiState.value.orders)
    }

    @Test
    fun `valid integrity record remains normal canonical evidence`() = runTest {
        val valid = record("valid", integrity = PaperHistoryIntegrityStatus.VALID)
        val vm = PaperOrderHistoryViewModel(FakePaperHistoryReader(latest = listOf(valid)))

        assertEquals(PaperHistoryIntegrityStatus.VALID, vm.uiState.value.orders.single().integrityStatus)
        assertTrue(vm.uiState.value.orders.single().integrityDiagnostics.isEmpty())
    }

    @Test
    fun `inconsistent filter exposes only inconsistent evidence`() = runTest {
        val inconsistent = record(
            "broken",
            integrity = PaperHistoryIntegrityStatus.INCONSISTENT,
        )
        val reader = FakePaperHistoryReader(latest = listOf(record("valid"), inconsistent))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onStatusFilterSelected(PaperHistoryStatusFilter.INCONSISTENT)

        assertEquals(listOf(inconsistent), vm.uiState.value.orders)
    }

    @Test
    fun `symbol filter normalizes and uses symbol query`() = runTest {
        val spy = record("spy", symbol = "SPY")
        val reader = FakePaperHistoryReader(bySymbol = mapOf("SPY" to listOf(spy)))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onSymbolFilterSelected(" spy ")

        assertEquals("SPY", vm.uiState.value.symbolFilter)
        assertEquals(listOf(spy), vm.uiState.value.orders)
        assertEquals(listOf("SPY"), reader.symbolCalls)
    }

    @Test
    fun `side filter uses dedicated side query`() = runTest {
        val sell = record("sell", side = "SELL")
        val reader = FakePaperHistoryReader(bySide = mapOf("SELL" to listOf(sell)))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onSideFilterSelected(PaperHistorySideFilter.SELL)

        assertEquals(listOf(sell), vm.uiState.value.orders)
        assertEquals(listOf("SELL"), reader.sideCalls)
    }

    @Test
    fun `combined filters intersect without reordering repository results`() = runTest {
        val first = record("first", symbol = "SPY", side = "BUY", status = "FILLED", terminal = true)
        val second = record("second", symbol = "AAPL", side = "BUY", status = "FILLED", terminal = true)
        val third = record("third", symbol = "SPY", side = "SELL", status = "FILLED", terminal = true)
        val reader = FakePaperHistoryReader(filled = listOf(first, second, third))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.onSymbolFilterSelected("SPY")
        vm.onSideFilterSelected(PaperHistorySideFilter.BUY)
        vm.onStatusFilterSelected(PaperHistoryStatusFilter.FILLED)

        assertEquals(listOf(first), vm.uiState.value.orders)
    }

    @Test
    fun `detail selection re-reads exact canonical attempt`() = runTest {
        val detail = record("detail", status = "FILLED", terminal = true)
        val reader = FakePaperHistoryReader(byAttempt = mapOf("detail" to detail))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.openDetails(" detail ")

        assertEquals(detail, vm.uiState.value.selectedOrder)
        assertEquals(listOf("detail"), reader.attemptCalls)
    }

    @Test
    fun `selection and back do not mutate or reorder historical list`() = runTest {
        val detail = record("detail")
        val other = record("other")
        val reader = FakePaperHistoryReader(
            latest = listOf(detail, other),
            byAttempt = mapOf("detail" to detail),
        )
        val vm = PaperOrderHistoryViewModel(reader)
        val before = vm.uiState.value.orders
        vm.openDetails("detail")

        vm.closeDetails()

        assertNull(vm.uiState.value.selectedOrder)
        assertEquals(before, vm.uiState.value.orders)
        assertEquals(1, reader.attemptCalls.size)
    }

    @Test
    fun `timeline sequence is exposed exactly as canonical model provides it`() = runTest {
        val detail = record(
            "timeline",
            observations = listOf(observation(4), observation(9, samePayload = true)),
        )
        val reader = FakePaperHistoryReader(byAttempt = mapOf("timeline" to detail))
        val vm = PaperOrderHistoryViewModel(reader)

        vm.openDetails("timeline")

        assertEquals(listOf(4L, 9L), vm.uiState.value.selectedOrder?.lifecycleObservations?.map { it.databaseId })
        assertTrue(vm.uiState.value.selectedOrder?.lifecycleObservations?.last()?.samePayloadAsPrevious == true)
    }

    @Test
    fun `legacy null metadata remains visible and is not classified as load error`() = runTest {
        val legacy = record("legacy", legacy = true, integrity = PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS)
        val vm = PaperOrderHistoryViewModel(FakePaperHistoryReader(latest = listOf(legacy)))

        assertEquals(listOf(legacy), vm.uiState.value.orders)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.orders.single().submitHttpStatusCode)
        assertEquals(PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS, vm.uiState.value.orders.single().integrityStatus)
        assertEquals(
            listOf(PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN),
            vm.uiState.value.orders.single().integrityDiagnostics,
        )
    }

    @Test
    fun `database failure has a distinct local database state`() = runTest {
        val vm = PaperOrderHistoryViewModel(
            FakePaperHistoryReader(failure = LocalDatabaseReadException()),
        )

        assertEquals(PaperHistoryErrorKind.LOCAL_DATABASE, vm.uiState.value.error?.kind)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `canonical reconstruction failure is fail closed`() = runTest {
        val vm = PaperOrderHistoryViewModel(
            FakePaperHistoryReader(failure = IllegalStateException("canonical mismatch")),
        )

        assertEquals(PaperHistoryErrorKind.CANONICAL_RECONSTRUCTION, vm.uiState.value.error?.kind)
        assertTrue(vm.uiState.value.orders.isEmpty())
    }

    @Test
    fun `view model recreation deterministically re-reads bounded local state`() = runTest {
        val reader = FakePaperHistoryReader(latest = listOf(record("same")))

        val first = PaperOrderHistoryViewModel(reader)
        val recreated = PaperOrderHistoryViewModel(reader)

        assertEquals(first.uiState.value.orders, recreated.uiState.value.orders)
        assertEquals(2, reader.latestLimits.size)
    }

    @Test
    fun `public actions have no trading network or repair shape`() {
        val forbidden = listOf(
            "submit", "execute", "cancel", "replace", "closeposition",
            "getalpaca", "refreshalpaca", "repair", "reset",
        )
        val names = PaperOrderHistoryViewModel::class.java.declaredMethods.map { it.name.lowercase() }

        names.forEach { name ->
            forbidden.forEach { term ->
                assertFalse(name.contains(term), "Unexpected action '$name' contains '$term'")
            }
        }
    }
}

private class LocalDatabaseReadException : RuntimeException("database read failed")

private class FakePaperHistoryReader(
    private val latest: List<CanonicalPaperOrderHistory> = emptyList(),
    private val terminal: List<CanonicalPaperOrderHistory> = emptyList(),
    private val filled: List<CanonicalPaperOrderHistory> = emptyList(),
    private val bySymbol: Map<String, List<CanonicalPaperOrderHistory>> = emptyMap(),
    private val bySide: Map<String, List<CanonicalPaperOrderHistory>> = emptyMap(),
    private val byAttempt: Map<String, CanonicalPaperOrderHistory> = emptyMap(),
    private val failure: Throwable? = null,
) : PaperOrderHistoryReader {
    val latestLimits = mutableListOf<Int>()
    val symbolCalls = mutableListOf<String>()
    val sideCalls = mutableListOf<String>()
    val attemptCalls = mutableListOf<String>()
    var terminalCalls: Int = 0
    var filledCalls: Int = 0

    private fun failIfNeeded() {
        failure?.let { throw it }
    }

    override suspend fun getByAttemptId(attemptId: String): CanonicalPaperOrderHistory? {
        failIfNeeded()
        attemptCalls += attemptId
        return byAttempt[attemptId]
    }

    override suspend fun getTerminalOrders(): List<CanonicalPaperOrderHistory> {
        failIfNeeded()
        terminalCalls += 1
        return terminal
    }

    override suspend fun getFilledOrders(): List<CanonicalPaperOrderHistory> {
        failIfNeeded()
        filledCalls += 1
        return filled
    }

    override suspend fun getBySymbol(symbol: String): List<CanonicalPaperOrderHistory> {
        failIfNeeded()
        symbolCalls += symbol
        return bySymbol[symbol].orEmpty()
    }

    override suspend fun getBySide(side: String): List<CanonicalPaperOrderHistory> {
        failIfNeeded()
        sideCalls += side
        return bySide[side].orEmpty()
    }

    override suspend fun getLatestN(limit: Int): List<CanonicalPaperOrderHistory> {
        failIfNeeded()
        latestLimits += limit
        return latest
    }
}

private fun record(
    attemptId: String,
    symbol: String = "SPY",
    side: String = "BUY",
    status: String = "NEW",
    terminal: Boolean = false,
    unresolved: Boolean = false,
    integrity: PaperHistoryIntegrityStatus = PaperHistoryIntegrityStatus.VALID,
    observations: List<CanonicalPaperLifecycleObservation> =
        listOf(observation(1, status, terminal)),
    legacy: Boolean = false,
): CanonicalPaperOrderHistory = CanonicalPaperOrderHistory(
    submitAttemptId = attemptId,
    orderSequenceId = if (legacy) null else 10L,
    linkedClientDryRunId = if (legacy) null else "dry-$attemptId",
    previewId = if (legacy) null else "preview-$attemptId",
    auditStartRowId = if (legacy) null else 10L,
    auditResultRowId = if (legacy) null else 11L,
    alpacaOrderId = if (legacy) null else "order-$attemptId",
    clientOrderId = if (legacy) null else "client-$attemptId",
    symbol = if (legacy) null else symbol,
    side = if (legacy) null else side,
    quantity = if (legacy) null else 1.0,
    orderType = if (legacy) null else "MARKET",
    timeInForce = if (legacy) null else "DAY",
    limitPriceUsd = null,
    dryRunAuditRowId = if (legacy) null else 8L,
    decisionCreatedAtEpochMillis = if (legacy) null else 1_700_000_000_000L,
    localSubmitResult = if (legacy) null else "SUBMITTED",
    localSubmitResultAtEpochMillis = if (legacy) null else 1_700_000_001_000L,
    submitHttpStatusCode = if (legacy) null else 200,
    initialAlpacaStatus = if (legacy) null else "new",
    alpacaSubmittedAtIso = if (legacy) null else "2026-08-07T19:30:00Z",
    lifecycleObservations = if (legacy) emptyList() else observations,
    currentLifecycle = if (legacy) null else observations.lastOrNull(),
    mappingState = if (legacy) null else "EXACT",
    resolved = terminal && integrity != PaperHistoryIntegrityStatus.INCONSISTENT,
    unresolved = unresolved,
    ambiguous = integrity == PaperHistoryIntegrityStatus.INCONSISTENT,
    resetAcknowledgedAtEpochMillis = if (terminal) 1_700_000_002_000L else null,
    integrityStatus = integrity,
    integrityDiagnostics = when (integrity) {
        PaperHistoryIntegrityStatus.VALID -> emptyList()
        PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS ->
            listOf(PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN)
        PaperHistoryIntegrityStatus.INCONSISTENT ->
            listOf(PaperHistoryIntegrityDiagnostic.SOURCE_IDENTITY_MISMATCH)
    },
)

private fun observation(
    id: Long,
    status: String = "NEW",
    terminal: Boolean = false,
    samePayload: Boolean = false,
): CanonicalPaperLifecycleObservation = CanonicalPaperLifecycleObservation(
    databaseId = id,
    observedAtEpochMillis = 1_700_000_000_000L + id,
    status = status,
    rawStatus = status.lowercase(),
    terminal = terminal,
    filledQuantity = if (status == "FILLED") 1.0 else null,
    filledAveragePriceUsd = if (status == "FILLED") 500.0 else null,
    filledAtIso = if (status == "FILLED") "2026-08-07T19:31:00Z" else null,
    source = "LOCAL_SUBMIT_AUDIT",
    httpStatusCode = null,
    submitAuditEntryId = 11L,
    payloadFingerprint = "fingerprint-$id",
    samePayloadAsPrevious = samePayload,
)
