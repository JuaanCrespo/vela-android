@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.pipeline

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.market.source.MarketDataClient
import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataSource
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.db.room.dao.FeatureDao
import com.vela.android.lab.db.room.dao.JournalDao
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.JournalEventEntity
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgePerSymbolRoutingTest {

    private fun newCoordinator(
        marketDao: MarketBarDao = RouteFakeMarketBarDao(),
        featureDao: FeatureDao = RouteFakeFeatureDao(),
        signalDao: SignalDao = RouteFakeSignalDao(),
        journalDao: JournalDao = RouteFakeJournalDao(),
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

    private fun update(
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
        source = "alpaca-iex-stream",
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 1.0,
    )

    @Test
    fun `initial perSymbol map is empty`() {
        val bridge = AlpacaTestStreamPipelineBridge(
            client = RouteFakeMarketDataClient(),
            coordinator = newCoordinator(),
        )
        assertEquals(emptyMap<String, SymbolBridgeStats>(), bridge.state.value.perSymbol)
    }

    @Test
    fun `routes SPY QQQ AAPL into separate per-symbol entries`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = RouteFakeMarketDataClient()
            val marketDao = RouteFakeMarketBarDao()
            val journalDao = RouteFakeJournalDao()
            val bridge = AlpacaTestStreamPipelineBridge(
                client = client,
                coordinator = newCoordinator(marketDao = marketDao, journalDao = journalDao),
            )
            bridge.start(this)

            val base = Instant.parse("2026-06-11T14:30:00Z")
            client.emit(update("SPY", 1, 520.10, base))
            client.emit(update("QQQ", 2, 430.50, base.plusSeconds(60)))
            client.emit(update("AAPL", 3, 188.20, base.plusSeconds(120)))
            client.emit(update("SPY", 4, 520.45, base.plusSeconds(180)))

            val s = bridge.state.value
            assertEquals(4, s.receivedUpdates)
            assertEquals(4, s.persistedUpdates)
            // perSymbol map has exactly the three observed symbols
            assertEquals(setOf("SPY", "QQQ", "AAPL"), s.perSymbol.keys)

            val spy = s.perSymbol["SPY"]!!
            assertEquals(2, spy.received)
            assertEquals(2, spy.persisted)
            assertEquals(520.45, spy.lastClose)

            val qqq = s.perSymbol["QQQ"]!!
            assertEquals(1, qqq.received)
            assertEquals(1, qqq.persisted)
            assertEquals(430.50, qqq.lastClose)

            val aapl = s.perSymbol["AAPL"]!!
            assertEquals(1, aapl.received)
            assertEquals(1, aapl.persisted)
            assertEquals(188.20, aapl.lastClose)

            // Each accepted update writes 4 journal events, so 4 * 4 = 16
            assertEquals(16, journalDao.rows.size)
            // 4 bars in 4 distinct minute buckets across 3 symbols
            assertEquals(4, marketDao.rows.size)
            // And each symbol's bar count is right.
            assertEquals(2, marketDao.rows.count { it.symbol == "SPY" })
            assertEquals(1, marketDao.rows.count { it.symbol == "QQQ" })
            assertEquals(1, marketDao.rows.count { it.symbol == "AAPL" })

            bridge.stop()
        }

    @Test
    fun `stop prevents further per-symbol persistence`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = RouteFakeMarketDataClient()
            val bridge = AlpacaTestStreamPipelineBridge(client, newCoordinator())
            bridge.start(this)
            client.emit(update("SPY", 1, 520.0, Instant.parse("2026-06-11T14:30:00Z")))
            assertEquals(1, bridge.state.value.perSymbol["SPY"]!!.received)

            bridge.stop()
            client.emit(update("SPY", 2, 521.0, Instant.parse("2026-06-11T14:31:00Z")))
            client.emit(update("QQQ", 3, 430.0, Instant.parse("2026-06-11T14:31:00Z")))
            // After stop, no additional entries observed.
            val s = bridge.state.value
            assertEquals(1, s.perSymbol["SPY"]!!.received)
            assertTrue("QQQ" !in s.perSymbol)
        }

    @Test
    fun `one symbol throwing does not break routing for other symbols`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = RouteFakeMarketDataClient()
            val symbolFailDao = SpyOnlyFailingMarketBarDao()
            val bridge = AlpacaTestStreamPipelineBridge(
                client = client,
                coordinator = newCoordinator(marketDao = symbolFailDao),
            )
            bridge.start(this)
            val base = Instant.parse("2026-06-11T14:30:00Z")

            // First: SPY throws inside coordinator.persistBar. Capture
            // lastError before delivering the next symbol (the success
            // path on the next emission intentionally clears lastError
            // because the bridge keeps a "last result" view, not an
            // append-only error log).
            client.emit(update("SPY", 1, 520.0, base))
            val afterSpy = bridge.state.value
            assertEquals(1, afterSpy.receivedUpdates)
            assertNotNull(afterSpy.lastError)
            assertTrue("SPY" !in afterSpy.perSymbol)

            client.emit(update("QQQ", 2, 430.0, base.plusSeconds(60))) // succeeds
            val s = bridge.state.value
            assertEquals(2, s.receivedUpdates)
            assertTrue("QQQ" in s.perSymbol)
            assertEquals(1, s.perSymbol["QQQ"]!!.persisted)
            // SPY still NOT in perSymbol because its update never
            // produced a result the bridge could attribute.
            assertTrue("SPY" !in s.perSymbol)
            bridge.stop()
        }
}

// --- Test doubles ----------------------------------------------------

private class RouteFakeMarketDataClient : MarketDataClient {
    override val source: MarketDataSource = MarketDataSource.OFFLINE_STUB
    private val _status = MutableStateFlow(MarketDataConnectionStatus.disconnected(source))
    override val connectionStatus: StateFlow<MarketDataConnectionStatus> = _status.asStateFlow()
    private val flow: MutableSharedFlow<BootstrapMarketUpdate> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
    override val updates: SharedFlow<BootstrapMarketUpdate> = flow.asSharedFlow()
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun subscribe(symbols: Set<String>) = Unit
    override suspend fun unsubscribe(symbols: Set<String>) = Unit
    override fun subscribedSymbols(): Set<String> = emptySet()
    suspend fun emit(update: BootstrapMarketUpdate) { flow.emit(update) }
}

private class RouteFakeMarketBarDao : MarketBarDao {
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
        rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.takeLast(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}

private class SpyOnlyFailingMarketBarDao : MarketBarDao {
    val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        if (bar.symbol == "SPY") throw RuntimeException("simulated SPY persistence failure")
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = emptyList()
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> = emptyList()
    override suspend fun countBySymbol(symbol: String): Int = 0
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) = Unit
    override suspend fun clear() { rows.clear() }
}

private class RouteFakeFeatureDao : FeatureDao {
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
    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? = rows.lastOrNull { it.symbol == symbol }
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun clear() { rows.clear() }
}

private class RouteFakeSignalDao : SignalDao {
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
    override suspend fun latestFor(symbol: String): SymbolSignalEntity? = rows.lastOrNull { it.symbol == symbol }
    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> = rows.filter { it.state == state }.takeLast(limit)
    override suspend fun clear() { rows.clear() }
}

private class RouteFakeJournalDao : JournalDao {
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
