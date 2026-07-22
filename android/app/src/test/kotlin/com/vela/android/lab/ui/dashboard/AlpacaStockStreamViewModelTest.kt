@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaStockMarketDataClient
import com.vela.android.lab.data.market.source.alpaca.AlpacaStreamEndpoint
import com.vela.android.lab.data.market.source.alpaca.AlpacaWebSocketFactory
import com.vela.android.lab.data.market.source.alpaca.AlpacaWebSocketHandle
import com.vela.android.lab.data.market.source.alpaca.AlpacaWebSocketListener
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
import com.vela.android.lab.data.pipeline.OfflineMarketPipelineCoordinator
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
import java.util.concurrent.CopyOnWriteArrayList
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

class AlpacaStockStreamViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-03T14:30:00Z") }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newClient(
        store: SecureAlpacaCredentialsStore,
        factory: AlpacaWebSocketFactory,
    ): AlpacaStockMarketDataClient = AlpacaStockMarketDataClient(
        credentialsProvider = SecureAlpacaCredentialsProvider(store),
        webSocketFactory = factory,
        clock = fixedClock,
    )

    private fun newBridge(
        client: AlpacaStockMarketDataClient,
    ): Triple<AlpacaTestStreamPipelineBridge, StockVmFakeMarketBarDao, StockVmFakeJournalDao> {
        val marketDao = StockVmFakeMarketBarDao()
        val featureDao = StockVmFakeFeatureDao()
        val signalDao = StockVmFakeSignalDao()
        val journalDao = StockVmFakeJournalDao()
        val aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        val features = FeatureEngine(aggregator, recentBarLimit = 8)
        val signals = SignalEngine(features)
        val coordinator = OfflineMarketPipelineCoordinator(
            barAggregator = aggregator,
            featureEngine = features,
            signalEngine = signals,
            marketDataRepository = MarketDataRepository(marketDao),
            featureRepository = FeatureRepository(featureDao),
            signalRepository = SignalRepository(signalDao),
            journalRepository = JournalRepository(journalDao),
        )
        return Triple(
            AlpacaTestStreamPipelineBridge(client, coordinator),
            marketDao,
            journalDao,
        )
    }

    @Test
    fun `initial state shows IEX feed and SPY symbol with no telemetry`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore()
            val factory = StockVmRecordingFactory()
            val client = newClient(store, factory)
            val (bridge, _, _) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)

            val s = vm.uiState.value
            assertEquals(AlpacaStreamEndpoint.IEX_STREAM_URL, s.feedUrl)
            assertEquals("SPY", s.symbol)
            assertFalse(s.credentialsConfigured)
            assertEquals("DISCONNECTED", s.connectionState)
            assertFalse(s.subscribed)
            assertEquals(0, s.barsReceived)
            assertEquals(0, s.pipelinePersisted)
            assertNull(s.lastBarSymbol)
            assertNull(s.lastBarClose)
            assertNull(s.lastBarTimestamp)
            assertNull(s.lastError)
        }

    @Test
    fun `credentialsConfigured starts true when store already has values`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
            }
            val client = newClient(store, StockVmRecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)
            assertTrue(vm.uiState.value.credentialsConfigured)
        }

    @Test
    fun `startStream with no credentials surfaces ERROR and does not open the socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore()
            val factory = StockVmRecordingFactory()
            val client = newClient(store, factory)
            val (bridge, _, _) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)
            vm.startStream()

            val s = vm.uiState.value
            assertEquals("ERROR", s.connectionState)
            assertEquals(0, factory.openCalls)
            assertTrue(s.subscribed) // subscribe set is built before connect tries to open
        }

    @Test
    fun `happy-path SPY bar drives CONNECTED, bar count, pipeline persisted, and DAO writes`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
            }
            val factory = StockVmRecordingFactory()
            val client = newClient(store, factory)
            val (bridge, marketDao, journalDao) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)

            vm.startStream()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"b","S":"SPY","o":520.10,"h":521.40,"l":519.80,"c":520.95,"v":12500,"t":"2026-06-03T14:31:00Z"}]""",
            )

            val s = vm.uiState.value
            assertEquals("CONNECTED", s.connectionState)
            assertTrue(s.subscribed)
            assertEquals("SPY", s.lastBarSymbol)
            assertEquals(520.95, s.lastBarClose)
            assertEquals(1, s.barsReceived)
            assertEquals(1, s.pipelinePersisted)
            assertNull(s.lastError)
            assertEquals(1, marketDao.rows.size)
            assertEquals("SPY", marketDao.rows.single().symbol)
            assertEquals(4, journalDao.rows.size)
        }

    @Test
    fun `stopStream closes the socket, stops the bridge, and clears subscribed`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
            }
            val factory = StockVmRecordingFactory()
            val client = newClient(store, factory)
            val (bridge, _, _) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)
            vm.startStream()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            vm.stopStream()

            val s = vm.uiState.value
            assertEquals("DISCONNECTED", s.connectionState)
            assertFalse(s.subscribed)
            assertTrue(factory.handle.closed)
            assertFalse(bridge.isCollecting)
        }

    @Test
    fun `UI state never carries credential values after startStream`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = StockInMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "topsecretvalue"))
            }
            val factory = StockVmRecordingFactory()
            val client = newClient(store, factory)
            val (bridge, _, _) = newBridge(client)
            val vm = AlpacaStockStreamViewModel(client, store, bridge)
            vm.startStream()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            val serialised = vm.uiState.value.toString()
            assertFalse(
                serialised.contains("topsecretvalue"),
                "UI state must not retain the stored secret value, but toString contains it",
            )
            assertFalse(serialised.contains("PKABCDEF1234"))
        }
}

// --- Test doubles -----------------------------------------------------

private class StockInMemoryStore : SecureAlpacaCredentialsStore {
    private var creds: AlpacaCredentials? = null
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class StockVmRecordingFactory : AlpacaWebSocketFactory {
    var openCalls: Int = 0; private set
    lateinit var listener: AlpacaWebSocketListener; private set
    val handle: StockVmRecordingHandle = StockVmRecordingHandle()
    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        openCalls += 1
        this.listener = listener
        listener.onOpen()
        return handle
    }
    fun deliver(payload: String) = listener.onMessage(payload)
}

private class StockVmRecordingHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}

private class StockVmFakeMarketBarDao : MarketBarDao {
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

private class StockVmFakeFeatureDao : FeatureDao {
    val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(features: SymbolFeaturesEntity): Long {
        rows.removeAll { it.symbol == features.symbol && it.bucketStartEpochMillis == features.bucketStartEpochMillis }
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

private class StockVmFakeSignalDao : SignalDao {
    val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(signal: SymbolSignalEntity): Long {
        rows.removeAll { it.symbol == signal.symbol && it.bucketStartEpochMillis == signal.bucketStartEpochMillis }
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

private class StockVmFakeJournalDao : JournalDao {
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
