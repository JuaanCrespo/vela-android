@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaperAuditPriceSourceTest {

    private fun result(
        priceSource: String? = "LIVE_QUOTE_MID",
        priceFreshness: String? = "FRESH",
        priceAgeMillis: Long? = 500L,
    ): PaperOrderPreflightResult = PaperOrderPreflightResult(
        intent = PaperOrderIntent(
            symbol = "SPY", side = OrderSide.BUY, quantity = 1.0,
            type = OrderType.MARKET, tif = TimeInForce.DAY,
            source = IntentSource.MANUAL_DRY_RUN,
            createdAtEpochMillis = 1_000L,
            clientDryRunId = "id-1",
        ),
        status = PreflightStatus.ALLOWED_DRY_RUN,
        estimatedNotionalUsd = 500.0,
        estimatedBuyingPowerAfterUsd = 199_500.0,
        allocationPercentAfter = 0.5,
        positionImpactQty = 1.0,
        relatedSignalState = "BULLISH",
        marketOpen = true,
        blockReasons = emptyList(),
        warnings = emptyList(),
        priceSource = priceSource,
        priceFreshness = priceFreshness,
        priceAgeMillis = priceAgeMillis,
    )

    @Test
    fun `repository persists priceSource freshness and age`() = runTest(UnconfinedTestDispatcher()) {
        val dao = PriceSourceAuditDao()
        val repo = PaperOrderDryRunAuditRepository(dao)
        repo.saveDryRun(result())
        val row = dao.rows.single()
        assertEquals("LIVE_QUOTE_MID", row.priceSource)
        assertEquals("FRESH", row.priceFreshness)
        assertEquals(500L, row.priceAgeMillis)
    }

    @Test
    fun `repository persists null price fields when snapshot was absent`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PriceSourceAuditDao()
            val repo = PaperOrderDryRunAuditRepository(dao)
            repo.saveDryRun(
                result(priceSource = null, priceFreshness = null, priceAgeMillis = null),
            )
            val row = dao.rows.single()
            assertNull(row.priceSource)
            assertNull(row.priceFreshness)
            assertNull(row.priceAgeMillis)
        }

    @Test
    fun `audit entity still has no credential or account id fields`() {
        val fields = PaperOrderDryRunAuditEntity::class.java.declaredFields
            .map { it.name.lowercase() }
        val forbidden = listOf("secret", "apikey", "apca", "accountid", "credential", "password")
        for (field in fields) {
            for (bad in forbidden) {
                assertFalse(
                    field.contains(bad),
                    "Audit entity field '$field' contains forbidden substring '$bad'",
                )
            }
        }
    }
}

private class PriceSourceAuditDao : PaperOrderDryRunAuditDao {
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
