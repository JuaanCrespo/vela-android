@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.candles

import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import java.time.Instant
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

class CandlesViewModelTest {

    private val now = Instant.parse("2026-07-21T14:40:00Z")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial local refresh loads watchlist and latest valid bars`() = runTest {
        val dao = FakeCandlesDao(
            mutableListOf(
                entity("SPY", now.minusSeconds(60), close = 501.0),
                entity("SPY", now.minusSeconds(120), close = 500.0),
            ),
        )

        val state = viewModel(setOf("SPY"), dao).uiState.value

        assertEquals(CandleDataState.READY, state.dataState)
        assertEquals(listOf("SPY"), state.symbols)
        assertEquals("SPY", state.selectedSymbol)
        assertEquals(listOf(500.0, 501.0), state.candles.map { it.close })
        assertEquals(state.candles.last(), state.selectedCandle)
        assertEquals(CandleFreshness.FRESH, state.freshness)
        assertEquals(60_000L, state.lastBarAgeMillis)
        assertEquals(now, state.lastRefreshAt)
    }

    @Test
    fun `empty watchlist is an explicit empty state`() = runTest {
        val vm = viewModel(emptySet(), FakeCandlesDao(), defaults = emptyList())
        val state = vm.uiState.value

        assertEquals(CandleDataState.EMPTY, state.dataState)
        assertTrue(state.symbols.isEmpty())
        assertNull(state.selectedSymbol)
        assertTrue(state.candles.isEmpty())
        assertEquals(CandleFreshness.UNKNOWN, state.freshness)
    }

    @Test
    fun `existing rows with invalid OHLC produce insufficient state`() = runTest {
        val dao = FakeCandlesDao(
            mutableListOf(
                entity("SPY", now.minusSeconds(60), open = 0.0, high = 502.0, low = 0.0),
            ),
        )

        val state = viewModel(setOf("SPY"), dao).uiState.value

        assertEquals(CandleDataState.INSUFFICIENT, state.dataState)
        assertEquals(1, state.rejectedBarCount)
        assertTrue(state.candles.isEmpty())
        assertNull(state.selectedCandle)
    }

    @Test
    fun `two missed minute buckets mark data stale`() = runTest {
        val dao = FakeCandlesDao(
            mutableListOf(entity("SPY", now.minusSeconds(121), close = 500.0)),
        )

        val state = viewModel(setOf("SPY"), dao).uiState.value

        assertEquals(CandleFreshness.STALE, state.freshness)
        assertTrue(state.isStale)
        assertEquals(121_000L, state.lastBarAgeMillis)
    }

    @Test
    fun `symbol count and candle selection actions remain local`() = runTest {
        val rows = mutableListOf<MarketBar1mEntity>()
        repeat(110) { offset ->
            rows += entity(
                "SPY",
                now.minusSeconds((109 - offset) * 60L),
                close = 400.0 + offset,
                high = 511.0,
                low = 399.0,
            )
        }
        rows += entity("AAPL", now.minusSeconds(60), open = 199.0, high = 201.0, low = 198.0, close = 200.0)
        val vm = viewModel(setOf("SPY", "AAPL"), FakeCandlesDao(rows))

        assertEquals("AAPL", vm.uiState.value.selectedSymbol)
        vm.onSymbolSelected("spy")
        assertEquals("SPY", vm.uiState.value.selectedSymbol)
        assertEquals(50, vm.uiState.value.candles.size)

        vm.onCandleCountSelected(100)
        assertEquals(100, vm.uiState.value.candleCount)
        assertEquals(100, vm.uiState.value.candles.size)

        val first = vm.uiState.value.candles.first()
        vm.onCandleSelected(first)
        assertEquals(first, vm.uiState.value.selectedCandle)

        vm.onCandleCountSelected(25)
        assertEquals(100, vm.uiState.value.candleCount)
    }

    @Test
    fun `Room read error is exposed without credential or network state`() = runTest {
        val state = viewModel(setOf("SPY"), ThrowingCandlesDao()).uiState.value

        assertEquals(CandleDataState.ERROR, state.dataState)
        assertEquals("simulated local read failure", state.errorMessage)
        assertFalse(state.errorMessage.orEmpty().contains("Bearer"))
    }

    @Test
    fun `ViewModel surface has no HTTP or trading shaped action`() {
        val forbidden = listOf("http", "connect", "stream", "trade", "order", "submit", "cancel")
        CandlesViewModel::class.java.declaredMethods.forEach { method ->
            forbidden.forEach { token ->
                assertFalse(
                    method.name.lowercase().contains(token),
                    "Unexpected method ${method.name}",
                )
            }
        }
    }

    private fun viewModel(
        symbols: Set<String>,
        dao: MarketBarDao,
        defaults: List<String> = listOf("SPY"),
    ): CandlesViewModel = CandlesViewModel(
        watchlistRepository = WatchlistRepository(
            store = InMemoryWatchlistStore(symbols),
            defaults = defaults,
        ),
        marketDataRepository = MarketDataRepository(dao),
        clock = { now },
    )

    private fun entity(
        symbol: String,
        at: Instant,
        open: Double = 500.0,
        high: Double = maxOf(open, 502.0),
        low: Double = minOf(open, 499.0),
        close: Double = 501.0,
    ): MarketBar1mEntity = MarketBar1mEntity(
        symbol = symbol,
        bucketStartEpochMillis = at.toEpochMilli(),
        open = open,
        high = high,
        low = low,
        close = close,
        updateCount = 2,
        syntheticVolume = 10.0,
        lastUpdateTimeEpochMillis = at.plusSeconds(30).toEpochMilli(),
    )
}

private class FakeCandlesDao(
    private val rows: MutableList<MarketBar1mEntity> = mutableListOf(),
) : MarketBarDao {
    private var nextId = 1L

    override suspend fun insert(bar: MarketBar1mEntity): Long {
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows.removeAll {
            it.symbol == stored.symbol &&
                it.bucketStartEpochMillis == stored.bucketStartEpochMillis
        }
        rows += stored
        return stored.id
    }

    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}

private class ThrowingCandlesDao : MarketBarDao {
    override suspend fun insert(bar: MarketBar1mEntity): Long = 0L
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = emptyList()
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = emptyList()
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        throw IllegalStateException("simulated local read failure")

    override suspend fun countBySymbol(symbol: String): Int = 0
    override suspend fun countAll(): Int = 0
    override suspend fun deleteBySymbol(symbol: String) = Unit
    override suspend fun clear() = Unit
}
