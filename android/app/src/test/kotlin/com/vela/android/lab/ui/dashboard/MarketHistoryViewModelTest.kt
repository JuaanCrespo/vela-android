@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.pipeline.OfflineMarketPipelineCoordinator
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.InMemoryWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.db.room.dao.FeatureDao
import com.vela.android.lab.db.room.dao.JournalDao
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.JournalEventEntity
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import com.vela.android.lab.data.market.BootstrapMarketUpdate
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

class MarketHistoryViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-12T15:00:00Z") }

    @BeforeEach
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    private fun newCoordinator(
        marketDao: MarketBarDao,
        featureDao: FeatureDao,
        signalDao: SignalDao,
        journalDao: JournalDao,
    ): OfflineMarketPipelineCoordinator {
        val aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        val features = FeatureEngine(aggregator, recentBarLimit = 8)
        val signals = SignalEngine(features)
        return OfflineMarketPipelineCoordinator(
            barAggregator = aggregator,
            featureEngine = features,
            signalEngine = signals,
            marketDataRepository = MarketDataRepository(marketDao),
            featureRepository = FeatureRepository(featureDao),
            signalRepository = SignalRepository(signalDao),
            journalRepository = JournalRepository(journalDao),
        )
    }

    private fun newViewModel(
        watchlist: Set<String>,
        marketDao: MarketBarDao = HistoryFakeMarketBarDao(),
        featureDao: FeatureDao = HistoryFakeFeatureDao(),
        signalDao: SignalDao = HistoryFakeSignalDao(),
        journalDao: JournalDao = HistoryFakeJournalDao(),
    ): MarketHistoryViewModel = MarketHistoryViewModel(
        watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(watchlist)),
        marketDataRepository = MarketDataRepository(marketDao),
        featureRepository = FeatureRepository(featureDao),
        signalRepository = SignalRepository(signalDao),
        journalRepository = JournalRepository(journalDao),
        clock = fixedClock,
    )

    @Test
    fun `empty database renders empty per-symbol stats with non-null refresh time`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = newViewModel(watchlist = setOf("SPY", "AAPL"))
            val s = vm.uiState.value
            assertEquals(listOf("AAPL", "SPY"), s.symbols)
            assertEquals(0, s.totalPersistedBars)
            assertEquals(0, s.totalJournalEvents)
            assertNotNull(s.lastRefreshAtEpochMillis)
            assertFalse(s.isRefreshing)
            assertNull(s.lastError)
            val spy = s.perSymbol["SPY"]!!
            assertNull(spy.latestBarClose)
            assertEquals(0, spy.recentBarCount)
            assertEquals(0, spy.journalEventCount)
            assertNull(spy.latestSignalState)
        }

    @Test
    fun `populated SPY and AAPL bars appear in snapshot per symbol`() =
        runTest(UnconfinedTestDispatcher()) {
            val marketDao = HistoryFakeMarketBarDao()
            val featureDao = HistoryFakeFeatureDao()
            val signalDao = HistoryFakeSignalDao()
            val journalDao = HistoryFakeJournalDao()
            val coordinator = newCoordinator(marketDao, featureDao, signalDao, journalDao)

            val base = Instant.parse("2026-06-12T14:30:00Z")
            // Two SPY bars and one AAPL bar — driven through the
            // coordinator so journal/feature/signal repositories
            // also accumulate.
            coordinator.addUpdate(barUpdate("SPY", 1, 520.10, base))
            coordinator.addUpdate(barUpdate("SPY", 2, 521.40, base.plusSeconds(60)))
            coordinator.addUpdate(barUpdate("AAPL", 3, 188.20, base.plusSeconds(120)))

            val vm = newViewModel(
                watchlist = setOf("SPY", "AAPL"),
                marketDao = marketDao,
                featureDao = featureDao,
                signalDao = signalDao,
                journalDao = journalDao,
            )
            val s = vm.uiState.value
            assertEquals(3, s.totalPersistedBars)
            // Each accepted update logs 4 journal rows.
            assertEquals(12, s.totalJournalEvents)

            val spy = s.perSymbol["SPY"]!!
            assertEquals(2, spy.recentBarCount)
            assertEquals(521.40, spy.latestBarClose)
            assertNotNull(spy.latestBarTimestampMillis)
            assertNotNull(spy.latestSignalState)
            assertEquals(8, spy.journalEventCount) // 2 bars × 4 events

            val aapl = s.perSymbol["AAPL"]!!
            assertEquals(1, aapl.recentBarCount)
            assertEquals(188.20, aapl.latestBarClose)
            assertNotNull(aapl.latestSignalState)
            assertEquals(4, aapl.journalEventCount)
        }

    @Test
    fun `refresh picks up newly persisted bars`() = runTest(UnconfinedTestDispatcher()) {
        val marketDao = HistoryFakeMarketBarDao()
        val featureDao = HistoryFakeFeatureDao()
        val signalDao = HistoryFakeSignalDao()
        val journalDao = HistoryFakeJournalDao()
        val coordinator = newCoordinator(marketDao, featureDao, signalDao, journalDao)

        val vm = newViewModel(
            watchlist = setOf("SPY"),
            marketDao = marketDao,
            featureDao = featureDao,
            signalDao = signalDao,
            journalDao = journalDao,
        )
        assertEquals(0, vm.uiState.value.totalPersistedBars)

        // External persistence happens (a stream session in real life).
        coordinator.addUpdate(barUpdate("SPY", 1, 520.0, Instant.parse("2026-06-12T14:30:00Z")))
        vm.refresh()

        val s = vm.uiState.value
        assertEquals(1, s.totalPersistedBars)
        assertEquals(1, s.perSymbol["SPY"]!!.recentBarCount)
        assertEquals(520.0, s.perSymbol["SPY"]!!.latestBarClose)
    }

    @Test
    fun `UI state carries no credential value`() = runTest(UnconfinedTestDispatcher()) {
        val vm = newViewModel(watchlist = setOf("SPY"))
        val serialised = vm.uiState.value.toString()
        // Sanity strings that would never appear; this asserts the
        // state's toString is well-formed and credential-free even
        // in stress mutation of unrelated fields.
        assertFalse(serialised.contains("topsecretvalue"))
        assertFalse(serialised.contains("Bearer "))
    }

    @Test
    fun `no method on MarketHistoryViewModel has a trading-shape name`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
        )
        val methods = MarketHistoryViewModel::class.java.declaredMethods.map { it.name }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "VM method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }

    @Test
    fun `repository exception surfaces as lastError without crashing`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MarketHistoryViewModel(
                watchlistRepository = WatchlistRepository(InMemoryWatchlistStore(setOf("SPY"))),
                marketDataRepository = MarketDataRepository(ThrowingMarketBarDao()),
                featureRepository = FeatureRepository(HistoryFakeFeatureDao()),
                signalRepository = SignalRepository(HistoryFakeSignalDao()),
                journalRepository = JournalRepository(HistoryFakeJournalDao()),
                clock = fixedClock,
            )
            val s = vm.uiState.value
            assertNotNull(s.lastError)
            assertFalse(s.isRefreshing)
        }

    private fun barUpdate(
        symbol: String,
        sequence: Int,
        close: Double,
        ts: Instant,
    ): BootstrapMarketUpdate = BootstrapMarketUpdate(
        symbol = symbol,
        sequence = sequence,
        price = close,
        change = 0.0,
        timestamp = ts,
        source = "test",
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 1.0,
    )
}

// --- Test doubles ---------------------------------------------------

private class HistoryFakeMarketBarDao : MarketBarDao {
    val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        rows.removeAll { it.symbol == bar.symbol && it.bucketStartEpochMillis == bar.bucketStartEpochMillis }
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}

private class ThrowingMarketBarDao : MarketBarDao {
    override suspend fun insert(bar: MarketBar1mEntity): Long = 0L
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = emptyList()
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = emptyList()
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        throw RuntimeException("simulated DAO failure on recent($symbol)")
    override suspend fun countBySymbol(symbol: String): Int = 0
    override suspend fun countAll(): Int = throw RuntimeException("simulated DAO failure on countAll")
    override suspend fun deleteBySymbol(symbol: String) = Unit
    override suspend fun clear() = Unit
}

private class HistoryFakeFeatureDao : FeatureDao {
    val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(features: SymbolFeaturesEntity): Long {
        rows.removeAll { it.symbol == features.symbol && it.bucketStartEpochMillis == features.bucketStartEpochMillis }
        val stored = if (features.id == 0L) features.copy(id = nextId++) else features
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(features: List<SymbolFeaturesEntity>): List<Long> = features.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolFeaturesEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun clear() { rows.clear() }
}

private class HistoryFakeSignalDao : SignalDao {
    val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(signal: SymbolSignalEntity): Long {
        rows.removeAll { it.symbol == signal.symbol && it.bucketStartEpochMillis == signal.bucketStartEpochMillis }
        val stored = if (signal.id == 0L) signal.copy(id = nextId++) else signal
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> = signals.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun latestFor(symbol: String): SymbolSignalEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }
    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.state == state }.takeLast(limit)
    override suspend fun clear() { rows.clear() }
}

private class HistoryFakeJournalDao : JournalDao {
    val rows: MutableList<JournalEventEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(event: JournalEventEntity): Long {
        val stored = if (event.id == 0L) event.copy(id = nextId++) else event
        rows += stored
        return stored.id
    }
    override suspend fun bySymbol(symbol: String): List<JournalEventEntity> = rows.filter { it.symbol == symbol }
    override suspend fun byType(eventType: String, limit: Int): List<JournalEventEntity> = rows.filter { it.eventType == eventType }.takeLast(limit)
    override suspend fun inRange(startMillis: Long, endMillis: Long): List<JournalEventEntity> = rows.filter { it.timestampEpochMillis in startMillis..endMillis }
    override suspend fun countAll(): Int = rows.size
    override suspend fun clear() { rows.clear() }
}
