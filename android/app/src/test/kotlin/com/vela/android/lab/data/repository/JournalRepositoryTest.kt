package com.vela.android.lab.data.repository

import com.vela.android.lab.db.room.dao.JournalDao
import com.vela.android.lab.db.room.entities.JournalEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class JournalRepositoryTest {

    private val base: Instant = Instant.parse("2026-01-01T14:30:00Z")

    @Test
    fun `record persists a typed event with normalized symbol`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record(
            eventType = "paper_order",
            timestamp = base,
            symbol = "btcusd",
            payloadJson = """{"side":"buy"}""",
        )

        val rows = repo.forSymbol("BTC/USD")

        assertEquals(1, rows.size)
        assertEquals("paper_order", rows[0].eventType)
        assertEquals("BTC/USD", rows[0].symbol)
    }

    @Test
    fun `byType returns most recent events first`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("paper_order", base.plusSeconds(0L), "SPY")
        repo.record("paper_order", base.plusSeconds(60L), "SPY")
        repo.record("position_snapshot", base.plusSeconds(30L), "SPY")

        val orders = repo.byType("paper_order", limit = 10)

        assertEquals(2, orders.size)
        assertEquals(base.plusSeconds(60L).toEpochMilli(), orders[0].timestampEpochMillis)
        assertEquals(base.plusSeconds(0L).toEpochMilli(), orders[1].timestampEpochMillis)
    }

    @Test
    fun `inRange returns events inside the inclusive bounds ascending`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("system", base.plusSeconds(0L))
        repo.record("system", base.plusSeconds(60L))
        repo.record("system", base.plusSeconds(120L))
        repo.record("system", base.plusSeconds(180L))

        val mid = repo.inRange(base.plusSeconds(60L), base.plusSeconds(120L))

        assertEquals(2, mid.size)
        assertEquals(base.plusSeconds(60L).toEpochMilli(), mid[0].timestampEpochMillis)
        assertEquals(base.plusSeconds(120L).toEpochMilli(), mid[1].timestampEpochMillis)
    }

    @Test
    fun `inRange with reversed bounds returns empty`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("system", base)
        assertEquals(0, repo.inRange(base.plusSeconds(60L), base).size)
    }

    @Test
    fun `byType with blank type or non-positive limit returns empty`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("paper_order", base, "SPY")
        assertEquals(0, repo.byType("", 10).size)
        assertEquals(0, repo.byType("paper_order", 0).size)
    }

    @Test
    fun `forSymbol with empty symbol returns empty`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("system", base, "SPY")
        assertEquals(0, repo.forSymbol("").size)
        assertEquals(0, repo.forSymbol("   ").size)
    }

    @Test
    fun `null symbol is stored when caller omits it`() = runBlocking {
        val repo = JournalRepository(FakeJournalDao())
        repo.record("system", base)
        // No symbol filter would return it; the count reflects insertion.
        assertEquals(1, repo.count())
    }
}

private class FakeJournalDao : JournalDao {
    private val rows: MutableList<JournalEventEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(event: JournalEventEntity): Long {
        val stored = if (event.id == 0L) event.copy(id = nextId++) else event
        rows += stored
        return stored.id
    }

    override suspend fun bySymbol(symbol: String): List<JournalEventEntity> =
        rows.filter { it.symbol == symbol }
            .sortedBy { it.timestampEpochMillis }

    override suspend fun byType(eventType: String, limit: Int): List<JournalEventEntity> =
        rows.filter { it.eventType == eventType }
            .sortedByDescending { it.timestampEpochMillis }
            .take(limit)

    override suspend fun inRange(startMillis: Long, endMillis: Long): List<JournalEventEntity> =
        rows.filter { it.timestampEpochMillis in startMillis..endMillis }
            .sortedBy { it.timestampEpochMillis }

    override suspend fun countAll(): Int = rows.size

    override suspend fun clear() {
        rows.clear()
    }
}
