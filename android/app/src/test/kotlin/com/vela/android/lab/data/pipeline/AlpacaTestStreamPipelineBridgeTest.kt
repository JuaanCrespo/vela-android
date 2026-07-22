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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaTestStreamPipelineBridgeTest {

    private fun newCoordinator(
        marketDao: MarketBarDao = BridgeFakeMarketBarDao(),
        featureDao: FeatureDao = BridgeFakeFeatureDao(),
        signalDao: SignalDao = BridgeFakeSignalDao(),
        journalDao: JournalDao = BridgeFakeJournalDao(),
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

    private fun fakepacaUpdate(
        sequence: Int,
        close: Double,
        timestamp: Instant = Instant.parse("2026-06-01T14:30:00Z"),
    ): BootstrapMarketUpdate = BootstrapMarketUpdate(
        symbol = "FAKEPACA",
        sequence = sequence,
        price = close,
        change = 0.0,
        timestamp = timestamp,
        source = "alpaca-test-stream",
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 1.0,
    )

    @Test
    fun `initial state is all zeros and nulls`() {
        val bridge = AlpacaTestStreamPipelineBridge(
            client = BridgeFakeMarketDataClient(),
            coordinator = newCoordinator(),
        )
        val s = bridge.state.value
        assertEquals(0, s.receivedUpdates)
        assertEquals(0, s.persistedUpdates)
        assertNull(s.lastSymbol)
        assertNull(s.lastPrice)
        assertNull(s.lastBarClose)
        assertNull(s.lastSignalState)
        assertEquals(0, s.lastJournalEventsForUpdate)
        assertNull(s.lastError)
        assertFalse(bridge.isCollecting)
    }

    @Test
    fun `start attaches collector and forwards an update to the coordinator`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = BridgeFakeMarketDataClient()
            val marketDao = BridgeFakeMarketBarDao()
            val journalDao = BridgeFakeJournalDao()
            val coordinator = newCoordinator(marketDao = marketDao, journalDao = journalDao)
            val bridge = AlpacaTestStreamPipelineBridge(client, coordinator)

            bridge.start(this)
            assertTrue(bridge.isCollecting)

            client.emit(fakepacaUpdate(sequence = 1, close = 134.65))

            val s = bridge.state.value
            assertEquals(1, s.receivedUpdates)
            assertEquals(1, s.persistedUpdates)
            assertEquals("FAKEPACA", s.lastSymbol)
            assertEquals(134.65, s.lastPrice)
            assertEquals(134.65, s.lastBarClose)
            assertNotNull(s.lastSignalState)
            assertEquals(4, s.lastJournalEventsForUpdate)
            assertNull(s.lastError)

            // Coordinator side-effects landed in the fake DAOs.
            assertEquals(1, marketDao.rows.size)
            assertEquals("FAKEPACA", marketDao.rows.single().symbol)
            assertEquals(4, journalDao.rows.size)

            bridge.stop()
        }

    @Test
    fun `multiple updates increment counters and update last fields`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = BridgeFakeMarketDataClient()
            val marketDao = BridgeFakeMarketBarDao()
            val journalDao = BridgeFakeJournalDao()
            val bridge = AlpacaTestStreamPipelineBridge(
                client = client,
                coordinator = newCoordinator(marketDao = marketDao, journalDao = journalDao),
            )
            bridge.start(this)

            val base = Instant.parse("2026-06-01T14:30:00Z")
            client.emit(fakepacaUpdate(1, 134.65, base))
            client.emit(fakepacaUpdate(2, 135.10, base.plusSeconds(60)))
            client.emit(fakepacaUpdate(3, 135.55, base.plusSeconds(120)))

            val s = bridge.state.value
            assertEquals(3, s.receivedUpdates)
            assertEquals(3, s.persistedUpdates)
            assertEquals(135.55, s.lastPrice)
            assertEquals(135.55, s.lastBarClose)

            // Each accepted update yields 4 journal rows.
            assertEquals(12, journalDao.rows.size)
            // 3 bars in 3 distinct minute buckets.
            assertEquals(3, marketDao.rows.size)

            bridge.stop()
        }

    @Test
    fun `stop cancels the collector so subsequent updates are ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = BridgeFakeMarketDataClient()
            val bridge = AlpacaTestStreamPipelineBridge(client, newCoordinator())
            bridge.start(this)

            client.emit(fakepacaUpdate(1, 100.0))
            assertEquals(1, bridge.state.value.receivedUpdates)

            bridge.stop()
            assertFalse(bridge.isCollecting)

            client.emit(fakepacaUpdate(2, 101.0))
            // After stop, additional emissions are not observed.
            assertEquals(1, bridge.state.value.receivedUpdates)
        }

    @Test
    fun `start is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val client = BridgeFakeMarketDataClient()
        val bridge = AlpacaTestStreamPipelineBridge(client, newCoordinator())
        bridge.start(this)
        bridge.start(this)
        bridge.start(this)

        client.emit(fakepacaUpdate(1, 100.0))
        // If three collectors had been attached we would see receivedUpdates >= 3.
        // The bridge's idempotency keeps it at 1.
        assertEquals(1, bridge.state.value.receivedUpdates)

        bridge.stop()
    }

    @Test
    fun `bridge handles a coordinator exception without breaking the collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = BridgeFakeMarketDataClient()
            val coordinator = newCoordinator(
                marketDao = BridgeThrowingOnceMarketBarDao(),
            )
            val bridge = AlpacaTestStreamPipelineBridge(client, coordinator)
            bridge.start(this)

            // First emission triggers the throw inside coordinator.persistBar.
            client.emit(fakepacaUpdate(1, 100.0))
            val afterError = bridge.state.value
            assertEquals(1, afterError.receivedUpdates)
            assertEquals(0, afterError.persistedUpdates)
            assertNotNull(afterError.lastError)

            // Collector still alive — subsequent emission is processed.
            client.emit(fakepacaUpdate(2, 101.0))
            val afterRecovery = bridge.state.value
            assertEquals(2, afterRecovery.receivedUpdates)
            assertEquals(1, afterRecovery.persistedUpdates)
            assertNull(afterRecovery.lastError)

            bridge.stop()
        }

    @Test
    fun `resetCounters returns state to Initial`() = runTest(UnconfinedTestDispatcher()) {
        val client = BridgeFakeMarketDataClient()
        val bridge = AlpacaTestStreamPipelineBridge(client, newCoordinator())
        bridge.start(this)
        client.emit(fakepacaUpdate(1, 100.0))
        client.emit(fakepacaUpdate(2, 101.0))
        assertEquals(2, bridge.state.value.receivedUpdates)

        bridge.resetCounters()
        assertEquals(AlpacaTestStreamBridgeState.Initial, bridge.state.value)
        bridge.stop()
    }

    // --- Reflection contract: no trading-shaped methods on the bridge

    @TestFactory
    fun `AlpacaTestStreamPipelineBridge declares no trading methods`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "place_order", "buyorder", "sellorder",
            "withdraw", "deposit", "trading", "executeorder", "executetrade",
            "cancelorder", "getaccount", "updateaccount", "openposition",
            "closeposition", "getportfolio", "setbalance", "transferfund",
        )
        val methods = AlpacaTestStreamPipelineBridge::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbidden) {
                    assertFalse(
                        lower.contains(bad),
                        "Bridge method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }
}

// --- Test doubles --------------------------------------------------

private class BridgeFakeMarketDataClient : MarketDataClient {
    override val source: MarketDataSource = MarketDataSource.OFFLINE_STUB
    private val _status = MutableStateFlow(MarketDataConnectionStatus.disconnected(source))
    override val connectionStatus: StateFlow<MarketDataConnectionStatus> = _status.asStateFlow()
    private val flow: MutableSharedFlow<BootstrapMarketUpdate> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 32)
    override val updates: SharedFlow<BootstrapMarketUpdate> = flow.asSharedFlow()

    override suspend fun connect() { _status.value = MarketDataConnectionStatus.connected(source) }
    override suspend fun disconnect() { _status.value = MarketDataConnectionStatus.disconnected(source) }
    override suspend fun subscribe(symbols: Set<String>) = Unit
    override suspend fun unsubscribe(symbols: Set<String>) = Unit
    override fun subscribedSymbols(): Set<String> = emptySet()

    suspend fun emit(update: BootstrapMarketUpdate) { flow.emit(update) }
}

private class BridgeFakeMarketBarDao : MarketBarDao {
    val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(bar: MarketBar1mEntity): Long {
        rows.removeAll {
            it.symbol == bar.symbol && it.bucketStartEpochMillis == bar.bucketStartEpochMillis
        }
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

private class BridgeThrowingOnceMarketBarDao : MarketBarDao {
    private val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var hasThrown = false
    private var nextId: Long = 1L

    override suspend fun insert(bar: MarketBar1mEntity): Long {
        if (!hasThrown) {
            hasThrown = true
            throw RuntimeException("simulated DAO failure")
        }
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

private class BridgeFakeFeatureDao : FeatureDao {
    val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(features: SymbolFeaturesEntity): Long {
        rows.removeAll {
            it.symbol == features.symbol && it.bucketStartEpochMillis == features.bucketStartEpochMillis
        }
        val stored = if (features.id == 0L) features.copy(id = nextId++) else features
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(features: List<SymbolFeaturesEntity>): List<Long> = features.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolFeaturesEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun clear() { rows.clear() }
}

private class BridgeFakeSignalDao : SignalDao {
    val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(signal: SymbolSignalEntity): Long {
        rows.removeAll {
            it.symbol == signal.symbol && it.bucketStartEpochMillis == signal.bucketStartEpochMillis
        }
        val stored = if (signal.id == 0L) signal.copy(id = nextId++) else signal
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> = signals.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }
    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun latestFor(symbol: String): SymbolSignalEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }
    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.state == state }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun clear() { rows.clear() }
}

private class BridgeFakeJournalDao : JournalDao {
    val rows: MutableList<JournalEventEntity> = mutableListOf()
    private var nextId: Long = 1L

    override suspend fun insert(event: JournalEventEntity): Long {
        val stored = if (event.id == 0L) event.copy(id = nextId++) else event
        rows += stored
        return stored.id
    }
    override suspend fun bySymbol(symbol: String): List<JournalEventEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.timestampEpochMillis }
    override suspend fun byType(eventType: String, limit: Int): List<JournalEventEntity> =
        rows.filter { it.eventType == eventType }.sortedByDescending { it.timestampEpochMillis }.take(limit)
    override suspend fun inRange(startMillis: Long, endMillis: Long): List<JournalEventEntity> =
        rows.filter { it.timestampEpochMillis in startMillis..endMillis }.sortedBy { it.timestampEpochMillis }
    override suspend fun countAll(): Int = rows.size
    override suspend fun clear() { rows.clear() }
}
