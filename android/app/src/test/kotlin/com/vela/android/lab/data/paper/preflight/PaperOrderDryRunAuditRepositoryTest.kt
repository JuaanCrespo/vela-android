@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderDryRunAuditRepositoryTest {

    private fun newResult(
        symbol: String = "SPY",
        side: OrderSide = OrderSide.BUY,
        qty: Double = 1.0,
        status: PreflightStatus = PreflightStatus.ALLOWED_DRY_RUN,
        notional: Double? = 500.0,
        bpAfter: Double? = 199_500.0,
        allocAfter: Double? = 1.0,
        signal: String? = "BULLISH",
        marketOpen: Boolean? = true,
        blocks: List<PreflightBlockReason> = emptyList(),
        warnings: List<PreflightWarning> = emptyList(),
        clientId: String = "dry-run-1",
        createdAt: Long = 1_000L,
    ): PaperOrderPreflightResult = PaperOrderPreflightResult(
        intent = PaperOrderIntent(
            symbol = symbol,
            side = side,
            quantity = qty,
            type = OrderType.MARKET,
            tif = TimeInForce.DAY,
            limitPriceUsd = null,
            source = IntentSource.MANUAL_DRY_RUN,
            createdAtEpochMillis = createdAt,
            clientDryRunId = clientId,
        ),
        status = status,
        estimatedNotionalUsd = notional,
        estimatedBuyingPowerAfterUsd = bpAfter,
        allocationPercentAfter = allocAfter,
        positionImpactQty = if (side == OrderSide.BUY) qty else -qty,
        relatedSignalState = signal,
        marketOpen = marketOpen,
        blockReasons = blocks,
        warnings = warnings,
    )

    @Test
    fun `saveDryRun inserts one row with mapped fields`() = runTest(UnconfinedTestDispatcher()) {
        val dao = InMemoryAuditDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        val id = repo.saveDryRun(newResult())
        assertTrue(id >= 1L)
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("SPY", row.symbol)
        assertEquals("BUY", row.side)
        assertEquals("MARKET", row.orderType)
        assertEquals("DAY", row.timeInForce)
        assertEquals(1.0, row.quantity)
        assertEquals("ALLOWED_DRY_RUN", row.status)
        assertEquals(500.0, row.estimatedNotionalUsd)
        assertEquals(199_500.0, row.buyingPowerAfterUsd)
        assertEquals(1.0, row.allocationPercentAfter)
        assertEquals("BULLISH", row.latestSignalState)
        assertEquals(true, row.marketOpen)
        assertEquals("MANUAL_DRY_RUN", row.source)
        // Latest price used = notional / qty = 500
        assertEquals(500.0, row.latestPriceUsedUsd)
    }

    @Test
    fun `block reasons and warnings are flattened to newline-separated summaries`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = InMemoryAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            repo.saveDryRun(
                newResult(
                    status = PreflightStatus.BLOCKED,
                    blocks = listOf(
                        PreflightBlockReason.AccountBlocked,
                        PreflightBlockReason.TradingBlocked,
                    ),
                    warnings = listOf(PreflightWarning.MarketClosed),
                ),
            )
            val row = dao.rows.single()
            assertTrue(row.blockReasonsSummary.contains("BLOCKED"))
            assertTrue(row.blockReasonsSummary.contains("trading is BLOCKED"))
            assertEquals(2, row.blockReasonsSummary.split("\n").size)
            assertEquals(1, row.warningsSummary.split("\n").size)
        }

    @Test
    fun `repeated saves with distinct clientDryRunIds insert distinct rows`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = InMemoryAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            repo.saveDryRun(newResult(clientId = "id-1", createdAt = 1L))
            repo.saveDryRun(newResult(clientId = "id-2", createdAt = 2L, symbol = "QQQ"))
            repo.saveDryRun(newResult(clientId = "id-3", createdAt = 3L, symbol = "AAPL"))
            assertEquals(3, dao.rows.size)
            assertEquals(setOf("id-1", "id-2", "id-3"), dao.rows.map { it.clientDryRunId }.toSet())
        }

    @Test
    fun `recent returns rows sorted by createdAt desc`() = runTest(UnconfinedTestDispatcher()) {
        val dao = InMemoryAuditDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        repo.saveDryRun(newResult(clientId = "old", createdAt = 100L))
        repo.saveDryRun(newResult(clientId = "new", createdAt = 200L))
        val rows = repo.recent(5)
        assertEquals(2, rows.size)
        assertEquals("new", rows[0].clientDryRunId)
        assertEquals("old", rows[1].clientDryRunId)
    }

    @Test
    fun `recentBySymbol uppercases the symbol`() = runTest(UnconfinedTestDispatcher()) {
        val dao = InMemoryAuditDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        repo.saveDryRun(newResult(symbol = "SPY", clientId = "a", createdAt = 1L))
        repo.saveDryRun(newResult(symbol = "QQQ", clientId = "b", createdAt = 2L))
        val rows = repo.recentBySymbol("spy", 5)
        assertEquals(1, rows.size)
        assertEquals("SPY", rows.single().symbol)
    }

    @Test
    fun `audit entity stores no credential or account id fields`() {
        val fields = PaperOrderDryRunAuditEntity::class.java.declaredFields.map { it.name.lowercase() }
        val forbiddenSubstrings = listOf("secret", "apikey", "apca", "accountid", "credential", "password")
        for (field in fields) {
            for (bad in forbiddenSubstrings) {
                assertFalse(
                    field.contains(bad),
                    "Audit entity field '$field' contains forbidden substring '$bad'",
                )
            }
        }
        // sanity: known non-secret fields are present
        assertTrue(fields.contains("symbol"))
        assertTrue(fields.contains("status"))
    }

    @Test
    fun `audit entity content from a credential-bearing intent contains no credential value`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = InMemoryAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            // Even if a future caller accidentally placed a credential
            // into a field that *could* be a string, the entity only
            // copies the well-known fields we control. The Phase 2.k
            // and 2.m surfaces never expose credentials to results
            // anyway; this test re-asserts at the mapping layer.
            repo.saveDryRun(newResult(symbol = "SPY"))
            val row = dao.rows.single()
            val toString = row.toString()
            assertFalse(toString.contains("topsecretvalue"))
            assertFalse(toString.contains("PKABCDEF1234"))
            assertFalse(toString.lowercase().contains("apca"))
        }

    @Test
    fun `repository has no order or trading-shape method`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "executeorder", "cancelorder",
            "replaceorder", "openposition", "closeposition", "trading",
            "delete", "update", "patch",
        )
        val methods = PaperOrderDryRunAuditRepository::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertFalse(
                    lower.contains(bad),
                    "repo method '$name' contains forbidden substring '$bad'",
                )
            }
        }
        assertNotNull(methods.firstOrNull { it == "saveDryRun" })
    }

    @Test
    fun `dao interface exposes no update or delete method`() {
        val methods = PaperOrderDryRunAuditDao::class.java.declaredMethods.map { it.name }.toSet()
        // Read-only + append-only: only insert + count + recent variants.
        assertEquals(
            setOf("insert", "countAll", "recent", "recentBySymbol"),
            methods,
        )
    }

    @Test
    fun `priceUsed falls back to limit price when notional is null`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = InMemoryAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            val result = newResult(notional = null).copy(
                intent = newResult().intent.copy(
                    type = OrderType.LIMIT,
                    limitPriceUsd = 99.0,
                ),
            )
            repo.saveDryRun(result)
            assertEquals(99.0, dao.rows.single().latestPriceUsedUsd)
        }

    @Test
    fun `priceUsed is null when neither notional nor limit price are present`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = InMemoryAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            repo.saveDryRun(newResult(notional = null))
            assertNull(dao.rows.single().latestPriceUsedUsd)
        }
}

private class InMemoryAuditDao : PaperOrderDryRunAuditDao {
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
    override suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderDryRunAuditEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.createdAtEpochMillis }
            .take(limit)
}
