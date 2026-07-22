@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.db.room.dao.PaperOrderPayloadPreviewDao
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderPayloadPreviewRepositoryTest {

    private fun preview(
        id: String = "preview-1",
        symbol: String = "SPY",
        createdAt: Long = 100L,
        warnings: List<String> = emptyList(),
    ): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
        previewId = id,
        linkedClientDryRunId = "dry-$id",
        symbol = symbol,
        side = OrderSide.BUY,
        type = OrderType.MARKET,
        timeInForce = TimeInForce.DAY,
        quantity = 1.0,
        limitPriceUsd = null,
        estimatedNotionalUsd = 400.25,
        priceSource = "ROOM_BAR_CLOSE",
        priceFreshness = "FRESH",
        relatedSignalState = "NEUTRAL",
        generatedAtEpochMillis = createdAt,
        status = if (warnings.isEmpty()) {
            PaperOrderPayloadPreviewStatus.READY_PREVIEW
        } else {
            PaperOrderPayloadPreviewStatus.READY_PREVIEW_WITH_WARNINGS
        },
        warningMessages = warnings,
        payloadFields = PaperOrderPayloadFields(
            symbol = symbol,
            side = "buy",
            type = "market",
            timeInForce = "day",
            quantity = 1.0,
            limitPriceUsd = null,
        ),
    )

    @Test
    fun `repository inserts exactly one immutable row`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val repository = PaperOrderPayloadPreviewRepository(dao)

            val rowId = repository.savePreview(preview())

            assertEquals(1L, rowId)
            assertEquals(1, repository.countAll())
            val row = dao.rows.single()
            assertEquals("preview-1", row.previewId)
            assertEquals("dry-preview-1", row.linkedClientDryRunId)
            assertEquals("SPY", row.symbol)
            assertEquals("BUY", row.side)
            assertEquals("MARKET", row.orderType)
            assertEquals("DAY", row.timeInForce)
            assertEquals(1.0, row.quantity)
            assertNull(row.limitPriceUsd)
            assertEquals("READY_PREVIEW", row.status)
            assertEquals(400.25, row.estimatedNotionalUsd)
            assertFalse(row.executionEnabled)
            assertEquals("DISABLED", row.endpointPreview)
            assertEquals("POST_DISABLED", row.httpMethodPreview)
        }

    @Test
    fun `repeated previews insert distinct preview id rows`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val repository = PaperOrderPayloadPreviewRepository(dao)
            repository.savePreview(preview(id = "preview-1", createdAt = 100L))
            repository.savePreview(preview(id = "preview-2", createdAt = 200L))

            assertEquals(2, repository.countAll())
            assertEquals(setOf("preview-1", "preview-2"), dao.rows.map { it.previewId }.toSet())
        }

    @Test
    fun `recent returns newest previews first`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = PaperOrderPayloadPreviewRepository(PreviewQueueFakeDao())
            repository.savePreview(preview(id = "old", createdAt = 100L))
            repository.savePreview(preview(id = "new", createdAt = 300L))
            repository.savePreview(preview(id = "middle", createdAt = 200L))

            assertEquals(listOf("new", "middle"), repository.recent(2).map { it.previewId })
        }

    @Test
    fun `recentBySymbol normalizes symbol and keeps queue read only`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = PaperOrderPayloadPreviewRepository(PreviewQueueFakeDao())
            repository.savePreview(preview(id = "spy", symbol = "SPY", createdAt = 100L))
            repository.savePreview(preview(id = "qqq", symbol = "QQQ", createdAt = 200L))

            assertEquals(listOf("spy"), repository.recentBySymbol(" spy ", 10).map { it.previewId })
            assertTrue(repository.recentBySymbol("", 10).isEmpty())
            assertTrue(repository.recent(0).isEmpty())
        }

    @Test
    fun `warning messages persist as local summary`() =
        runTest(UnconfinedTestDispatcher()) {
            val dao = PreviewQueueFakeDao()
            val repository = PaperOrderPayloadPreviewRepository(dao)
            repository.savePreview(preview(warnings = listOf("one", "two")))
            assertEquals("one\ntwo", dao.rows.single().warningsSummary)
        }

    @Test
    fun `entity rejects executable or endpoint-shaped marker values`() {
        val row = preview().toEntity()
        assertThrows(IllegalArgumentException::class.java) {
            row.copy(executionEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            row.copy(endpointPreview = "paper endpoint")
        }
        assertThrows(IllegalArgumentException::class.java) {
            row.copy(httpMethodPreview = "POST")
        }
    }

    @Test
    fun `entity field names contain no credential account API header or password`() {
        val forbidden = listOf(
            "secret", "apikey", "apca", "accountid", "credential", "password", "header",
        )
        val fields = PaperOrderPayloadPreviewEntity::class.java.declaredFields
            .map { it.name.lowercase() }.filterNot { it.contains('$') }
        for (field in fields) {
            for (bad in forbidden) {
                assertFalse(field.contains(bad), "Queue entity field '$field' contains '$bad'")
            }
        }
    }

    @Test
    fun `DAO method set is insert and reads only`() {
        assertEquals(
            setOf("insert", "countAll", "recent", "recentBySymbol", "byPreviewId"),
            PaperOrderPayloadPreviewDao::class.java.declaredMethods.map { it.name }.toSet(),
        )
    }

    @Test
    fun `repository exposes no update delete clear or execution method`() {
        val forbidden = listOf(
            "update", "delete", "clear", "submit", "execute", "cancel", "replace",
            "post", "patch", "closeposition", "account",
        )
        val methods = PaperOrderPayloadPreviewRepository::class.java.declaredMethods
            .map { it.name }.filterNot { it.contains('$') }
        for (method in methods) {
            for (bad in forbidden) {
                assertFalse(method.lowercase().contains(bad), "Repository method '$method' contains '$bad'")
            }
        }
    }
}

internal class PreviewQueueFakeDao : PaperOrderPayloadPreviewDao {
    val rows: MutableList<PaperOrderPayloadPreviewEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(preview: PaperOrderPayloadPreviewEntity): Long {
        require(rows.none { it.previewId == preview.previewId }) { "duplicate previewId" }
        val stored = if (preview.id == 0L) preview.copy(id = nextId++) else preview
        rows += stored
        return stored.id
    }

    override suspend fun countAll(): Int = rows.size

    override suspend fun recent(limit: Int): List<PaperOrderPayloadPreviewEntity> =
        rows.sortedByDescending { it.createdAtEpochMillis }.take(limit)

    override suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderPayloadPreviewEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.createdAtEpochMillis }
            .take(limit)

    override suspend fun byPreviewId(previewId: String): PaperOrderPayloadPreviewEntity? =
        rows.firstOrNull { it.previewId == previewId }
}
