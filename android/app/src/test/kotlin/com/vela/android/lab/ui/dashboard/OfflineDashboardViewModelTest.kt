package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JVM-only tests for the offline dashboard ViewModel.
 *
 * The Compose screen itself is not exercised here — Compose UI tests
 * are instrumented and require an emulator or device, which is not
 * attached to this host. The ViewModel + its repository wiring is
 * fully testable on the JVM via the existing fake-DAO pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDashboardViewModelTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

    private lateinit var marketDao: FakeMarketBarDao
    private lateinit var featureDao: FakeFeatureDao
    private lateinit var signalDao: FakeSignalDao
    private lateinit var journalDao: FakeJournalDao

    private lateinit var viewModel: OfflineDashboardViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        marketDao = FakeMarketBarDao()
        featureDao = FakeFeatureDao()
        signalDao = FakeSignalDao()
        journalDao = FakeJournalDao()

        val marketRepo = MarketDataRepository(marketDao)
        val featureRepo = FeatureRepository(featureDao)
        val signalRepo = SignalRepository(signalDao)
        val journalRepo = JournalRepository(journalDao)

        val aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        val features = FeatureEngine(aggregator, recentBarLimit = 8)
        val signals = SignalEngine(features)

        val coordinator = OfflineMarketPipelineCoordinator(
            barAggregator = aggregator,
            featureEngine = features,
            signalEngine = signals,
            marketDataRepository = marketRepo,
            featureRepository = featureRepo,
            signalRepository = signalRepo,
            journalRepository = journalRepo,
        )

        viewModel = OfflineDashboardViewModel(
            coordinator = coordinator,
            marketDataRepository = marketRepo,
            featureRepository = featureRepo,
            signalRepository = signalRepo,
            journalRepository = journalRepo,
            clock = fixedClock,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state shows READ_ONLY mode`() {
        val state = viewModel.uiState.value
        assertEquals("READ_ONLY", state.modeLabel)
    }

    @Test
    fun `initial state shows REAL locked true`() {
        val state = viewModel.uiState.value
        assertTrue(state.realLocked, "REAL must be locked at startup")
    }

    @Test
    fun `initial state shows offline pipeline label`() {
        assertEquals("Offline demo", viewModel.uiState.value.pipelineLabel)
    }

    @Test
    fun `initial state has no last symbol, price, or signal`() {
        val state = viewModel.uiState.value
        assertNull(state.lastSymbol)
        assertNull(state.lastPrice)
        assertNull(state.lastBarClose)
        assertNull(state.lastFeatureDirection)
        assertNull(state.lastSignalState)
        assertNull(state.lastSignalScore)
        assertEquals(0, state.persistedBarCount)
        assertEquals(0, state.journalEventCount)
        assertNull(state.lastError)
    }

    @Test
    fun `demo BTC update changes last symbol to BTC slash USD`() {
        viewModel.generateBtcUpdate()
        val state = viewModel.uiState.value
        assertEquals("BTC/USD", state.lastSymbol)
        assertEquals(50_005.0, state.lastPrice)
    }

    @Test
    fun `demo SPY update changes last symbol to SPY`() {
        viewModel.generateSpyUpdate()
        val state = viewModel.uiState.value
        assertEquals("SPY", state.lastSymbol)
        assertEquals(400.25, state.lastPrice)
    }

    @Test
    fun `demo update produces a signal state`() {
        viewModel.generateBtcUpdate()
        val state = viewModel.uiState.value
        assertNotNull(state.lastSignalState)
        assertNotNull(state.lastSignalScore)
        // First-update signal is NEUTRAL (score 0): direction flat, return 0, range 0.
        assertEquals("NEUTRAL", state.lastSignalState)
    }

    @Test
    fun `persisted bar count increases after a demo update`() {
        viewModel.generateBtcUpdate()
        assertEquals(1, viewModel.uiState.value.persistedBarCount)

        viewModel.generateSpyUpdate()
        assertEquals(2, viewModel.uiState.value.persistedBarCount)
    }

    @Test
    fun `journal event count increases by four per accepted update`() {
        viewModel.generateBtcUpdate()
        assertEquals(4, viewModel.uiState.value.journalEventCount)

        viewModel.generateSpyUpdate()
        assertEquals(8, viewModel.uiState.value.journalEventCount)
    }

    @Test
    fun `clear demo state resets the visible counters and last error`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        // Pre-clear sanity: counters are non-zero.
        assertTrue(viewModel.uiState.value.persistedBarCount > 0)
        assertTrue(viewModel.uiState.value.journalEventCount > 0)

        viewModel.clearDemoState()

        val cleared = viewModel.uiState.value
        assertEquals(OfflineDashboardUiState.Initial, cleared)
        assertEquals(0, cleared.persistedBarCount)
        assertEquals(0, cleared.journalEventCount)
        assertNull(cleared.lastSymbol)
        assertNull(cleared.lastSignalState)
        assertNull(cleared.lastError)

        // And the underlying tables are empty.
        assertEquals(0, marketDao.rows.size)
        assertEquals(0, featureDao.rows.size)
        assertEquals(0, signalDao.rows.size)
        assertEquals(0, journalDao.rows.size)
    }

    @Test
    fun `REAL remains locked across demo activity`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        viewModel.generateBtcUpdate()
        val state = viewModel.uiState.value
        assertTrue(state.realLocked, "demo activity must never flip the REAL lock")
        assertEquals("READ_ONLY", state.modeLabel)
    }

    @Test
    fun `BTC symbol spelling normalizes to canonical BTC slash USD`() {
        // The ViewModel emits "BTC/USD" directly. After normalization
        // the persisted entity also stores the canonical form — verify
        // by inspecting the underlying fake DAO.
        viewModel.generateBtcUpdate()
        val stored = marketDao.rows.single()
        assertEquals("BTC/USD", stored.symbol)
    }
}

// --- Fake DAOs (private to this test, mirror SQL semantics) ----------

private class FakeMarketBarDao : MarketBarDao {
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

    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> =
        bars.map { insert(it) }

    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }

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

private class FakeFeatureDao : FeatureDao {
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

    override suspend fun insertAll(features: List<SymbolFeaturesEntity>): List<Long> =
        features.map { insert(it) }

    override suspend fun bySymbol(symbol: String): List<SymbolFeaturesEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }

    override suspend fun countBySymbol(symbol: String): Int =
        rows.count { it.symbol == symbol }

    override suspend fun clear() {
        rows.clear()
    }
}

private class FakeSignalDao : SignalDao {
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

    override suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long> =
        signals.map { insert(it) }

    override suspend fun bySymbol(symbol: String): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }.sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun latestFor(symbol: String): SymbolSignalEntity? =
        rows.filter { it.symbol == symbol }.maxByOrNull { it.bucketStartEpochMillis }

    override suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity> =
        rows.filter { it.state == state }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun clear() {
        rows.clear()
    }
}

private class FakeJournalDao : JournalDao {
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
