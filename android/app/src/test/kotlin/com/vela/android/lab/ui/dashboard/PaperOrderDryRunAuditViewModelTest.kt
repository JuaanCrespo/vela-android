@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.paper.preflight.IntentSource
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderIntent
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightResult
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import com.vela.android.lab.data.paper.preflight.TimeInForce
import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperOrderDryRunAuditViewModelTest {

    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-14T12:00:00Z") }

    private fun result(symbol: String = "SPY", clientId: String = "id-1", createdAt: Long = 1L): PaperOrderPreflightResult =
        PaperOrderPreflightResult(
            intent = PaperOrderIntent(
                symbol = symbol,
                side = OrderSide.BUY,
                quantity = 1.0,
                type = OrderType.MARKET,
                tif = TimeInForce.DAY,
                source = IntentSource.MANUAL_DRY_RUN,
                createdAtEpochMillis = createdAt,
                clientDryRunId = clientId,
            ),
            status = PreflightStatus.ALLOWED_DRY_RUN,
            estimatedNotionalUsd = 500.0,
            estimatedBuyingPowerAfterUsd = 99_500.0,
            allocationPercentAfter = 0.5,
            positionImpactQty = 1.0,
            relatedSignalState = "BULLISH",
            marketOpen = true,
            blockReasons = emptyList(),
            warnings = emptyList(),
        )

    @Test
    fun `initial state on empty database reports zero total`() = runTest(UnconfinedTestDispatcher()) {
        val dao = AuditFakeDao()
        val vm = PaperOrderDryRunAuditViewModel(
            repository = PaperOrderDryRunAuditRepository(dao),
            clock = fixedClock,
        )
        val s = vm.uiState.value
        assertEquals(0, s.totalDryRuns)
        assertTrue(s.recentRows.isEmpty())
        assertNotNull(s.lastRefreshAtEpochMillis)
        assertNull(s.lastError)
        assertFalse(s.isRefreshing)
    }

    @Test
    fun `refresh after inserts surfaces total and recent rows sorted desc`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = AuditFakeDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            val vm = PaperOrderDryRunAuditViewModel(repository = repo, clock = fixedClock)
            repo.saveDryRun(result(symbol = "AAPL", clientId = "a", createdAt = 1L))
            repo.saveDryRun(result(symbol = "SPY", clientId = "b", createdAt = 2L))
            vm.refresh()
            val s = vm.uiState.value
            assertEquals(2, s.totalDryRuns)
            assertEquals(2, s.recentRows.size)
            assertEquals("SPY", s.recentRows[0].symbol)
            assertEquals("AAPL", s.recentRows[1].symbol)
        }

    @Test
    fun `refreshNow callback updates the snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val dao = AuditFakeDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        val vm = PaperOrderDryRunAuditViewModel(repository = repo, clock = fixedClock)
        repo.saveDryRun(result())
        vm.refreshNow()
        assertEquals(1, vm.uiState.value.totalDryRuns)
    }

    @Test
    fun `repository exception surfaces as lastError without crashing`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = ThrowingAuditDao()
            val vm = PaperOrderDryRunAuditViewModel(
                repository = PaperOrderDryRunAuditRepository(dao),
                clock = fixedClock,
            )
            // Force a refresh — initial load already triggered one
            // automatically; the error should be present.
            vm.refresh()
            val s = vm.uiState.value
            assertNotNull(s.lastError)
            assertFalse(s.isRefreshing)
        }

    @Test
    fun `audit VM has no delete or clear method`() {
        val forbidden = listOf(
            "delete", "clear", "drop", "update", "patch",
            "submitorder", "placeorder", "executeorder", "cancelorder",
            "replaceorder", "trading",
        )
        val methods = PaperOrderDryRunAuditViewModel::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertFalse(
                    lower.contains(bad),
                    "audit VM method '$name' contains forbidden substring '$bad'",
                )
            }
        }
        assertTrue(methods.contains("refresh"))
    }

    @Test
    fun `UI state never contains credential or account id fields`() = runTest(UnconfinedTestDispatcher()) {
        val dao = AuditFakeDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        repo.saveDryRun(result())
        val vm = PaperOrderDryRunAuditViewModel(repository = repo, clock = fixedClock)
        vm.refresh()
        val str = vm.uiState.value.toString().lowercase()
        assertFalse(str.contains("apca"))
        assertFalse(str.contains("secret"))
        assertFalse(str.contains("credential"))
        assertFalse(str.contains("accountid"))
    }
}

private class AuditFakeDao : PaperOrderDryRunAuditDao {
    val rows: MutableList<PaperOrderDryRunAuditEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(audit: PaperOrderDryRunAuditEntity): Long {
        val stored = if (audit.id == 0L) audit.copy(id = nextId++) else audit
        rows += stored
        return stored.id
    }
    override suspend fun countAll(): Int = rows.size
    override suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity> =
        rows.sortedByDescending { it.createdAtEpochMillis }.take(limit)
    override suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderDryRunAuditEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.createdAtEpochMillis }.take(limit)
}

private class ThrowingAuditDao : PaperOrderDryRunAuditDao {
    override suspend fun insert(audit: PaperOrderDryRunAuditEntity): Long = throw RuntimeException("simulated insert failure")
    override suspend fun countAll(): Int = throw RuntimeException("simulated count failure")
    override suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity> = throw RuntimeException("simulated recent failure")
    override suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderDryRunAuditEntity> = emptyList()
}
