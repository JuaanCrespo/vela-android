package com.vela.android.lab.data.repository

import com.vela.android.lab.data.market.SignalState
import com.vela.android.lab.data.market.SymbolSignal
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SignalRepositoryTest {

    private val base: Instant = Instant.parse("2026-01-01T14:30:00Z")

    private fun signal(
        symbol: String = "BTC/USD",
        minuteOffset: Int = 0,
        state: SignalState = SignalState.BULLISH,
        score: Int = 3,
    ): SymbolSignal = SymbolSignal(
        symbol = symbol,
        bucketStart = base.plusSeconds(minuteOffset * 60L),
        state = state,
        score = score,
        shortReturn = 0.01,
        percentChange = 0.005,
        barRange = 0.6,
        direction = "up",
    )

    @Test
    fun `state survives insert and query round trip`() = runBlocking {
        val repo = SignalRepository(FakeSignalDao())
        repo.persist(signal(state = SignalState.BEARISH, score = -3))
        val signal = repo.latestFor("BTC/USD")
        assertEquals(SignalState.BEARISH, signal?.state)
        assertEquals(-3, signal?.score)
    }

    @Test
    fun `byState filters by enum and respects descending order`() = runBlocking {
        val repo = SignalRepository(FakeSignalDao())
        repo.persist(signal(minuteOffset = 0, state = SignalState.BULLISH))
        repo.persist(signal(minuteOffset = 1, state = SignalState.NEUTRAL))
        repo.persist(signal(minuteOffset = 2, state = SignalState.BULLISH))

        val bullish = repo.byState(SignalState.BULLISH, limit = 10)

        assertEquals(2, bullish.size)
        assertEquals(base.plusSeconds(120L), bullish[0].bucketStart)
        assertEquals(base.plusSeconds(0L), bullish[1].bucketStart)
    }

    @Test
    fun `BTCUSD and BTC slash USD resolve to the same row`() = runBlocking {
        val repo = SignalRepository(FakeSignalDao())
        repo.persist(signal(symbol = "BTC/USD"))
        assertEquals(1, repo.forSymbol("BTCUSD").size)
        assertEquals(1, repo.forSymbol("btcusd").size)
    }

    @Test
    fun `byState with limit zero returns empty list`() = runBlocking {
        val repo = SignalRepository(FakeSignalDao())
        repo.persist(signal())
        assertEquals(0, repo.byState(SignalState.BULLISH, limit = 0).size)
    }
}

private class FakeSignalDao : SignalDao {
    private val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(signal: SymbolSignalEntity): Long {
        rows.removeAll {
            it.symbol == signal.symbol && it.bucketStartEpochMillis == signal.bucketStartEpochMillis
        }
        val stored = if (signal.id == 0L) signal.copy(id = nextId++) else signal
        rows += stored
        return stored.id
    }

    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> =
        signals.map { insert(it) }

    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }
            .sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun latestFor(symbol: String): SymbolSignalEntity? =
        rows.filter { it.symbol == symbol }
            .maxByOrNull { it.bucketStartEpochMillis }

    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.state == state }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun clear() {
        rows.clear()
    }
}
