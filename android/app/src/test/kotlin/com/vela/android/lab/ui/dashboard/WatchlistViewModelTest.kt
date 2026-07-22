@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
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
import com.vela.android.lab.data.market.source.MarketDataClient
import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataSource
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WatchlistViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRepo(initial: Set<String> = emptySet()): WatchlistRepository =
        WatchlistRepository(InMemoryWatchlistStore(initial))

    private fun newBridge(client: MarketDataClient): AlpacaTestStreamPipelineBridge {
        val aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        val features = FeatureEngine(aggregator, recentBarLimit = 8)
        val signals = SignalEngine(features)
        val coordinator = OfflineMarketPipelineCoordinator(
            barAggregator = aggregator,
            featureEngine = features,
            signalEngine = signals,
            marketDataRepository = MarketDataRepository(WlFakeMarketBarDao()),
            featureRepository = FeatureRepository(WlFakeFeatureDao()),
            signalRepository = SignalRepository(WlFakeSignalDao()),
            journalRepository = JournalRepository(WlFakeJournalDao()),
        )
        return AlpacaTestStreamPipelineBridge(client, coordinator)
    }

    @Test
    fun `initial state seeds the default watchlist sorted alphabetically`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = WatchlistViewModel(
                repository = newRepo(),
                pipelineBridge = newBridge(WlFakeClient()),
            )
            val s = vm.uiState.value
            assertEquals(listOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY"), s.symbols)
            assertEquals(10, s.maxSymbols)
            assertTrue(s.canAddMore)
        }

    @Test
    fun `add normalizes and shows status`() = runTest(UnconfinedTestDispatcher()) {
        val vm = WatchlistViewModel(newRepo(), newBridge(WlFakeClient()))
        vm.onAddInputChange("tsla")
        vm.add()
        val s = vm.uiState.value
        assertTrue("TSLA" in s.symbols)
        assertEquals("", s.addInput)
        assertEquals("Added TSLA", s.lastStatus)
    }

    @Test
    fun `add rejects invalid input and does not persist`() = runTest(UnconfinedTestDispatcher()) {
        val vm = WatchlistViewModel(newRepo(), newBridge(WlFakeClient()))
        vm.onAddInputChange("BTC/USD")
        vm.add()
        val s = vm.uiState.value
        assertFalse("BTC/USD" in s.symbols)
        assertFalse(s.symbols.any { it.contains('/') })
        assertNotNull(s.lastStatus)
        assertTrue(s.lastStatus!!.contains("not a valid"))
    }

    @Test
    fun `add empty input shows prompt and does not call repository`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = WatchlistViewModel(newRepo(), newBridge(WlFakeClient()))
            vm.onAddInputChange("   ")
            vm.add()
            val s = vm.uiState.value
            assertEquals("Enter a symbol first.", s.lastStatus)
        }

    @Test
    fun `add enforces cap`() = runTest(UnconfinedTestDispatcher()) {
        val tenSymbols = setOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY", "TSLA", "AMZN", "GOOG", "META", "AMD")
        val vm = WatchlistViewModel(newRepo(tenSymbols), newBridge(WlFakeClient()))
        assertEquals(10, vm.uiState.value.symbols.size)
        assertFalse(vm.uiState.value.canAddMore)
        vm.onAddInputChange("NFLX")
        vm.add()
        val s = vm.uiState.value
        assertEquals(10, s.symbols.size)
        assertTrue(s.lastStatus!!.contains("cap"))
    }

    @Test
    fun `remove succeeds and updates the visible list`() = runTest(UnconfinedTestDispatcher()) {
        val vm = WatchlistViewModel(newRepo(), newBridge(WlFakeClient()))
        vm.remove("SPY")
        val s = vm.uiState.value
        assertFalse("SPY" in s.symbols)
        assertEquals("Removed SPY", s.lastStatus)
    }

    @Test
    fun `subscribeSet returns the current symbol set`() = runTest(UnconfinedTestDispatcher()) {
        val vm = WatchlistViewModel(newRepo(setOf("SPY", "QQQ")), newBridge(WlFakeClient()))
        assertEquals(setOf("SPY", "QQQ"), vm.subscribeSet())
    }

    @Test
    fun `bridge emissions mirror into perSymbol map`() = runTest(UnconfinedTestDispatcher()) {
        val client = WlFakeClient()
        val bridge = newBridge(client)
        val vm = WatchlistViewModel(newRepo(setOf("SPY")), bridge)
        bridge.start(this)
        client.emit(
            BootstrapMarketUpdate(
                symbol = "SPY",
                sequence = 1,
                price = 520.0,
                change = 0.0,
                timestamp = Instant.parse("2026-06-11T14:30:00Z"),
                source = "alpaca-iex-stream",
                open = 520.0,
                high = 520.0,
                low = 520.0,
                close = 520.0,
                volume = 1.0,
            ),
        )
        val s = vm.uiState.value
        assertTrue("SPY" in s.perSymbol)
        assertEquals(1, s.perSymbol["SPY"]!!.received)
        bridge.stop()
    }

    @Test
    fun `WatchlistViewModel declares no trading methods`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
        )
        val methods = WatchlistViewModel::class.java.declaredMethods.map { it.name }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "WatchlistViewModel method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}

// --- Test doubles ----------------------------------------------------

private class WlFakeClient : MarketDataClient {
    override val source: MarketDataSource = MarketDataSource.OFFLINE_STUB
    private val _status = MutableStateFlow(MarketDataConnectionStatus.disconnected(source))
    override val connectionStatus: StateFlow<MarketDataConnectionStatus> = _status.asStateFlow()
    private val flow: MutableSharedFlow<BootstrapMarketUpdate> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 32)
    override val updates: SharedFlow<BootstrapMarketUpdate> = flow.asSharedFlow()
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun subscribe(symbols: Set<String>) = Unit
    override suspend fun unsubscribe(symbols: Set<String>) = Unit
    override fun subscribedSymbols(): Set<String> = emptySet()
    suspend fun emit(update: BootstrapMarketUpdate) { flow.emit(update) }
}

private class WlFakeMarketBarDao : MarketBarDao {
    val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        rows.removeAll { it.symbol == bar.symbol && it.bucketStartEpochMillis == bar.bucketStartEpochMillis }
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}

private class WlFakeFeatureDao : FeatureDao {
    val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(features: SymbolFeaturesEntity): Long {
        val stored = if (features.id == 0L) features.copy(id = nextId++) else features
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(features: List<SymbolFeaturesEntity>): List<Long> = features.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolFeaturesEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? = rows.lastOrNull { it.symbol == symbol }
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun clear() { rows.clear() }
}

private class WlFakeSignalDao : SignalDao {
    val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(signal: SymbolSignalEntity): Long {
        val stored = if (signal.id == 0L) signal.copy(id = nextId++) else signal
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> = signals.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun latestFor(symbol: String): SymbolSignalEntity? = rows.lastOrNull { it.symbol == symbol }
    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.state == state }.takeLast(limit)
    override suspend fun clear() { rows.clear() }
}

private class WlFakeJournalDao : JournalDao {
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
