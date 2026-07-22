package com.vela.android.lab.data.repository

import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Repository-layer tests using a pure-Kotlin fake DAO so the suite
 * runs under `:app:test` (JVM) without an Android emulator.
 */
class MarketDataRepositoryTest {

    private val base: Instant = Instant.parse("2026-01-01T14:30:00Z")

    private fun bar(
        symbol: String = "BTC/USD",
        minuteOffset: Int = 0,
        close: Double = 100.0,
    ): OneMinuteBar = OneMinuteBar(
        symbol = symbol,
        bucketStart = base.plusSeconds(minuteOffset * 60L),
        open = close,
        high = close,
        low = close,
        close = close,
        updateCount = 1,
        syntheticVolume = 1.0,
        lastUpdateTime = base.plusSeconds(minuteOffset * 60L),
    )

    @Test
    fun `persisted bar can be queried back with the same canonical form`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        repo.persistBar(bar(symbol = "BTC/USD"))

        val result = repo.bars("BTC/USD")

        assertEquals(1, result.size)
        assertEquals("BTC/USD", result[0].symbol)
    }

    @Test
    fun `BTCUSD and BTC slash USD resolve to the same row`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        // Caller stores via the canonical form (the aggregator's output).
        repo.persistBar(bar(symbol = "BTC/USD"))

        // Three legal query spellings all hit the same row.
        assertEquals(1, repo.bars("BTC/USD").size)
        assertEquals(1, repo.bars("BTCUSD").size)
        assertEquals(1, repo.bars("btcusd").size)
        assertEquals(1, repo.count("BTCUSD"))
    }

    @Test
    fun `bars are returned in ascending timestamp order`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        // Insert out of order.
        repo.persistBar(bar(minuteOffset = 3, close = 103.0))
        repo.persistBar(bar(minuteOffset = 1, close = 101.0))
        repo.persistBar(bar(minuteOffset = 2, close = 102.0))

        val bars = repo.bars("BTC/USD")

        assertEquals(listOf(101.0, 102.0, 103.0), bars.map { it.close })
    }

    @Test
    fun `recentBars returns up to limit, chronological oldest-first`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        for (i in 0 until 5) {
            repo.persistBar(bar(minuteOffset = i, close = 100.0 + i))
        }

        val recent = repo.recentBars("BTC/USD", limit = 3)

        assertEquals(3, recent.size)
        assertEquals(listOf(102.0, 103.0, 104.0), recent.map { it.close })
    }

    @Test
    fun `persistAll inserts every bar`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        val bars = listOf(
            bar(minuteOffset = 0),
            bar(minuteOffset = 1),
            bar(minuteOffset = 2),
        )
        val ids = repo.persistBars(bars)

        assertEquals(3, ids.size)
        assertEquals(3, repo.count("BTC/USD"))
        assertEquals(3, repo.countAll())
    }

    @Test
    fun `bars with raw equity symbol normalizes uppercase`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        repo.persistBar(bar(symbol = "spy"))
        assertEquals(1, repo.bars("SPY").size)
        assertEquals(1, repo.bars("spy").size)
    }

    @Test
    fun `empty or whitespace symbol returns no rows`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        repo.persistBar(bar(symbol = "SPY"))
        assertEquals(0, repo.bars("").size)
        assertEquals(0, repo.bars("   ").size)
        assertEquals(0, repo.count(""))
    }

    @Test
    fun `clear by symbol only removes that symbol`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        repo.persistBar(bar(symbol = "BTC/USD"))
        repo.persistBar(bar(symbol = "SPY", minuteOffset = 1))
        repo.clear("BTC/USD")
        assertEquals(0, repo.count("BTC/USD"))
        assertEquals(1, repo.count("SPY"))
    }

    @Test
    fun `recentBars with limit zero returns empty list`() = runBlocking {
        val repo = MarketDataRepository(FakeMarketBarDao())
        repo.persistBar(bar())
        assertEquals(0, repo.recentBars("BTC/USD", limit = 0).size)
    }
}

/**
 * Pure-Kotlin fake of the MarketBarDao that mirrors the SQL semantics
 * the Room-generated implementation will exhibit:
 *
 *  - `REPLACE` on (symbol, bucketStartEpochMillis) unique constraint
 *  - ascending order by `bucketStartEpochMillis` for `bySymbol`
 *  - descending order + LIMIT for `recent`
 */
private class FakeMarketBarDao : MarketBarDao {
    private val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(bar: MarketBar1mEntity): Long {
        rows.removeAll {
            it.symbol == bar.symbol && it.bucketStartEpochMillis == bar.bucketStartEpochMillis
        }
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }

    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> =
        bars.map { insert(it) }

    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
            .sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun countBySymbol(symbol: String): Int =
        rows.count { it.symbol == symbol }

    override suspend fun countAll(): Int = rows.size

    override suspend fun deleteBySymbol(symbol: String) {
        rows.removeAll { it.symbol == symbol }
    }

    override suspend fun clear() {
        rows.clear()
    }
}
