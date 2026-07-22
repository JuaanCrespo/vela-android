@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaTestStreamMarketDataClient
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

class AlpacaTestStreamViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

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
    ): AlpacaTestStreamMarketDataClient {
        return AlpacaTestStreamMarketDataClient(
            credentialsProvider = SecureAlpacaCredentialsProvider(store),
            webSocketFactory = factory,
            clock = fixedClock,
        )
    }

    /**
     * Builds a real bridge backed by a real coordinator using
     * pure-Kotlin fake DAOs. Each test gets its own isolated DB
     * state and its own bridge instance.
     */
    private fun newBridge(
        client: AlpacaTestStreamMarketDataClient,
    ): Triple<AlpacaTestStreamPipelineBridge, VmFakeMarketBarDao, VmFakeJournalDao> {
        val marketDao = VmFakeMarketBarDao()
        val featureDao = VmFakeFeatureDao()
        val signalDao = VmFakeSignalDao()
        val journalDao = VmFakeJournalDao()
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
    fun `initial state has empty inputs and credentialsConfigured false`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore()
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(
                client = client,
                credentialsStore = store,
                pipelineBridge = bridge,
            )
            val s = viewModel.uiState.value
            assertEquals("", s.keyIdInput)
            assertEquals("", s.secretInput)
            assertFalse(s.credentialsConfigured)
            assertEquals("DISCONNECTED", s.connectionState)
            assertEquals(0, s.barsReceived)
            assertNull(s.lastSaveStatus)
            assertNull(s.lastClearStatus)
            assertEquals(0, s.pipelineReceived)
            assertEquals(0, s.pipelinePersisted)
            assertNull(s.lastPipelineSignalState)
            assertNull(s.lastPipelineError)
        }

    @Test
    fun `credentialsConfigured starts true when store already has values`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
            }
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            assertTrue(viewModel.uiState.value.credentialsConfigured)
        }

    @Test
    fun `onKeyIdInputChange and onSecretInputChange update binding state only`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore()
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            viewModel.onKeyIdInputChange("PKTYPING")
            viewModel.onSecretInputChange("typingsecret")
            val s = viewModel.uiState.value
            assertEquals("PKTYPING", s.keyIdInput)
            assertEquals("typingsecret", s.secretInput)
            assertFalse(s.credentialsConfigured)
            assertNull(store.load())
        }

    @Test
    fun `saveCredentials persists to store and clears the input fields`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore()
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            viewModel.onKeyIdInputChange("PKTYPING1234")
            viewModel.onSecretInputChange("topsecretvalue")
            viewModel.saveCredentials()

            val s = viewModel.uiState.value
            assertEquals("", s.keyIdInput)
            assertEquals("", s.secretInput)
            assertTrue(s.credentialsConfigured)
            assertEquals("Saved.", s.lastSaveStatus)

            val saved = store.load()
            assertNotNull(saved)
            assertEquals("PKTYPING1234", saved!!.keyId)
            assertEquals("topsecretvalue", saved.secret)
        }

    @Test
    fun `saveCredentials with empty inputs shows validation message and does not write`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore()
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            viewModel.saveCredentials()
            val s = viewModel.uiState.value
            assertEquals("Both fields are required.", s.lastSaveStatus)
            assertFalse(s.credentialsConfigured)
            assertNull(store.load())
        }

    @Test
    fun `clearCredentials wipes the store and the input fields`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore().apply {
                save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
            }
            val client = newClient(store, RecordingFactory())
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            assertTrue(viewModel.uiState.value.credentialsConfigured)

            viewModel.onKeyIdInputChange("noise")
            viewModel.onSecretInputChange("alsoNoise")
            viewModel.clearCredentials()

            val s = viewModel.uiState.value
            assertEquals("", s.keyIdInput)
            assertEquals("", s.secretInput)
            assertFalse(s.credentialsConfigured)
            assertEquals("Cleared.", s.lastClearStatus)
            assertNull(store.load())
        }

    @Test
    fun `UI state never carries the saved secret after save`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val store = InMemoryStore()
        val client = newClient(store, RecordingFactory())
        val (bridge, _, _) = newBridge(client)
        val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
        viewModel.onKeyIdInputChange("PKABCDEF1234")
        viewModel.onSecretInputChange("topsecretvalue")
        viewModel.saveCredentials()

        val serialised = viewModel.uiState.value.toString()
        assertFalse(
            serialised.contains("topsecretvalue"),
            "UI state must not retain the saved secret value, but toString contains it",
        )
        assertFalse(serialised.contains("PKABCDEF1234"))
    }

    @Test
    fun `startSmokeTest with no credentials surfaces ERROR and does not open the socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryStore()
            val factory = RecordingFactory()
            val client = newClient(store, factory)
            val (bridge, _, _) = newBridge(client)
            val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
            viewModel.startSmokeTest()
            val s = viewModel.uiState.value
            assertEquals("ERROR", s.connectionState)
            assertEquals(0, factory.openCalls)
        }

    @Test
    fun `happy-path drives CONNECTED, increments barsReceived AND pipeline counters`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val store = InMemoryStore().apply {
            save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
        }
        val factory = RecordingFactory()
        val client = newClient(store, factory)
        val (bridge, marketDao, journalDao) = newBridge(client)
        val viewModel = AlpacaTestStreamViewModel(client, store, bridge)

        viewModel.startSmokeTest()
        factory.deliver("""[{"T":"success","msg":"connected"}]""")
        factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
        factory.deliver(
            """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":250,"t":"2026-01-01T14:31:00Z"}]""",
        )

        val s = viewModel.uiState.value
        assertEquals("CONNECTED", s.connectionState)
        assertEquals("FAKEPACA", s.lastBarSymbol)
        assertEquals(1.1, s.lastBarClose)
        assertEquals(1, s.barsReceived)
        // Phase 2.d: bridge forwarded the bar to the coordinator.
        assertEquals(1, s.pipelineReceived)
        assertEquals(1, s.pipelinePersisted)
        assertNotNull(s.lastPipelineSignalState)
        assertNull(s.lastPipelineError)
        // Coordinator side-effects landed in Room (fake DAO).
        assertEquals(1, marketDao.rows.size)
        assertEquals("FAKEPACA", marketDao.rows.single().symbol)
        assertEquals(4, journalDao.rows.size)
    }

    @Test
    fun `stopSmokeTest closes the socket, stops the bridge, and resets to DISCONNECTED`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val store = InMemoryStore().apply {
            save(AlpacaCredentials("PKABCDEF1234", "sssssss"))
        }
        val factory = RecordingFactory()
        val client = newClient(store, factory)
        val (bridge, _, _) = newBridge(client)
        val viewModel = AlpacaTestStreamViewModel(client, store, bridge)
        viewModel.startSmokeTest()
        factory.deliver("""[{"T":"success","msg":"connected"}]""")
        factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
        viewModel.stopSmokeTest()

        assertEquals("DISCONNECTED", viewModel.uiState.value.connectionState)
        assertTrue(factory.handle.closed)
        assertFalse(bridge.isCollecting)
    }
}

// --- Test doubles -----------------------------------------------------

private class InMemoryStore : SecureAlpacaCredentialsStore {
    private var creds: AlpacaCredentials? = null
    override suspend fun save(credentials: AlpacaCredentials) { creds = credentials }
    override suspend fun load(): AlpacaCredentials? = creds
    override suspend fun clear() { creds = null }
    override suspend fun hasCredentials(): Boolean = creds != null
}

private class RecordingFactory : AlpacaWebSocketFactory {
    var openCalls: Int = 0; private set
    lateinit var listener: AlpacaWebSocketListener; private set
    val handle: RecordingHandle = RecordingHandle()

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

private class RecordingHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}

private class VmFakeMarketBarDao : MarketBarDao {
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

private class VmFakeFeatureDao : FeatureDao {
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

private class VmFakeSignalDao : SignalDao {
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

private class VmFakeJournalDao : JournalDao {
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
