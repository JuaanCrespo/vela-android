package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * attached to this host. The ViewModel and its repository wiring run
 * on the JVM over the in-memory DAO fakes in `OfflineDemoTestDoubles.kt`.
 *
 * The build gate is passed explicitly. Configuration-specific checks
 * live in `src/testDebug` and `src/testRelease`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDashboardViewModelTest {

    private lateinit var harness: OfflineDemoHarness

    private val viewModel: OfflineDashboardViewModel get() = harness.viewModel
    private val marketDao: DemoBarDao get() = harness.marketDao
    private val featureDao: DemoFeatureDao get() = harness.featureDao
    private val signalDao: DemoSignalDao get() = harness.signalDao
    private val journalDao: DemoJournalDao get() = harness.journalDao

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = OfflineDemoHarness(demoGeneratorsEnabled = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A persisted BTC/USD bar in the generator's minute, as a legacy row would sit in the table. */
    private fun legacyBtcRow(): MarketBar1mEntity = MarketBar1mEntity(
        symbol = "BTC/USD",
        bucketStartEpochMillis = OFFLINE_DEMO_CLOCK.toEpochMilli(),
        open = 48_900.0,
        high = 49_100.0,
        low = 48_800.0,
        close = 49_000.0,
        updateCount = 7,
        syntheticVolume = 3.0,
        lastUpdateTimeEpochMillis = null,
    )

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
        assertNull(state.demoStatus)
        assertTrue(state.demoGeneratorsAvailable)
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

    /** DEMO_RESET_SCOPE_NARROW, case A: the reset keeps every persisted market bar. */
    @Test
    fun `reset demo status preserves market bars`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        val bars = marketDao.rows.toList()
        assertEquals(2, bars.size)

        viewModel.resetDemoStatus()

        assertEquals(bars, marketDao.rows.toList())
    }

    /** DEMO_RESET_SCOPE_NARROW, case B: the reset keeps every persisted feature row. */
    @Test
    fun `reset demo status preserves features`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        val features = featureDao.rows.toList()
        assertTrue(features.isNotEmpty())

        viewModel.resetDemoStatus()

        assertEquals(features, featureDao.rows.toList())
    }

    /** DEMO_RESET_SCOPE_NARROW, case C: the reset keeps every persisted signal row. */
    @Test
    fun `reset demo status preserves signals`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        val signals = signalDao.rows.toList()
        assertTrue(signals.isNotEmpty())

        viewModel.resetDemoStatus()

        assertEquals(signals, signalDao.rows.toList())
    }

    /** DEMO_RESET_SCOPE_NARROW, case D: the reset keeps every journal event. */
    @Test
    fun `reset demo status preserves journal`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        val journal = journalDao.rows.toList()
        assertTrue(journal.isNotEmpty())

        viewModel.resetDemoStatus()

        assertEquals(journal, journalDao.rows.toList())
    }

    /**
     * DEMO_RESET_DURABLE_ATOMICITY_RISK=NONE: the reset performs no durable write or delete on any table. Its only
     * effects are in memory, so a partial durable failure cannot leave the reset half applied.
     */
    @Test
    fun `reset demo status performs no durable write or delete`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()
        val writesBefore = durableCallCounts()

        viewModel.resetDemoStatus()

        assertEquals(writesBefore, durableCallCounts())
    }

    private fun durableCallCounts(): List<Int> = listOf(
        marketDao.insertCalls,
        marketDao.deleteBySymbolCalls,
        marketDao.clearCalls,
        featureDao.insertCalls,
        featureDao.clearCalls,
        signalDao.insertCalls,
        signalDao.clearCalls,
        journalDao.insertCalls,
        journalDao.clearCalls,
    )

    /** LEGACY_MARKET_ROWS_PRESERVED: a legacy row the demo never writes survives demo activity and reset. */
    @Test
    fun `reset demo status leaves seeded legacy market bars unchanged`() {
        val legacy = MarketBar1mEntity(
            symbol = "AAPL",
            bucketStartEpochMillis = java.time.Instant.parse("2025-12-31T20:00:00Z").toEpochMilli(),
            open = 201.1,
            high = 202.4,
            low = 200.9,
            close = 201.75,
            updateCount = 7,
            syntheticVolume = 3.0,
            lastUpdateTimeEpochMillis = null,
        )
        runBlocking { marketDao.insert(legacy) }
        val seeded = marketDao.rows.single { it.symbol == "AAPL" }

        viewModel.generateBtcUpdate()
        viewModel.resetDemoStatus()

        assertEquals(seeded, marketDao.rows.single { it.symbol == "AAPL" })
    }

    /** DEMO_RESET_SCOPE_NARROW: the reset issues no delete and no clear on any table. */
    @Test
    fun `reset demo status issues no delete or clear on any table`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()

        viewModel.resetDemoStatus()

        assertEquals(0, marketDao.deleteBySymbolCalls)
        assertEquals(0, marketDao.clearCalls)
        assertEquals(0, featureDao.clearCalls)
        assertEquals(0, signalDao.clearCalls)
        assertEquals(0, journalDao.clearCalls)
    }

    /** The visible counters are read from storage, so they stay equal to the stored rows after a reset. */
    @Test
    fun `reset demo status keeps the visible counters equal to stored rows`() {
        viewModel.generateBtcUpdate()
        viewModel.generateSpyUpdate()

        viewModel.resetDemoStatus()

        val state = viewModel.uiState.value
        assertTrue(state.persistedBarCount > 0)
        assertEquals(marketDao.rows.size, state.persistedBarCount)
        assertEquals(journalDao.rows.size, state.journalEventCount)
    }

    /** The status reports what was kept, never what was deleted. A reset also clears a stale error. */
    @Test
    fun `reset demo status reports data kept and clears the last error`() {
        marketDao.failInserts = true
        viewModel.generateBtcUpdate()
        assertNotNull(viewModel.uiState.value.lastError)

        marketDao.failInserts = false
        viewModel.resetDemoStatus()

        val state = viewModel.uiState.value
        assertNull(state.lastError)
        val status = state.demoStatus.orEmpty()
        assertTrue(status.contains("kept"), "demo status must say that stored data was kept")
        assertFalse(status.contains("deleted", ignoreCase = true))
        assertFalse(status.contains("cleared", ignoreCase = true))
    }

    @Test
    fun `reset demo status restarts the demo price walk from its initial price`() {
        viewModel.generateBtcUpdate()
        viewModel.generateBtcUpdate()
        assertEquals(50_010.0, viewModel.uiState.value.lastPrice)

        viewModel.resetDemoStatus()
        viewModel.generateBtcUpdate()

        assertEquals(50_005.0, viewModel.uiState.value.lastPrice)
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

    /** RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO at the ViewModel entry point: a closed gate hides the generators. */
    @Test
    fun `closed build gate reports demo generators unavailable`() {
        val closed = OfflineDemoHarness(demoGeneratorsEnabled = false)
        assertFalse(closed.viewModel.uiState.value.demoGeneratorsAvailable)
    }

    /** RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO at the ViewModel entry point: a closed gate writes nothing and calls nothing. */
    @Test
    fun `closed build gate rejects demo generation without any write`() {
        val closed = OfflineDemoHarness(demoGeneratorsEnabled = false)

        closed.viewModel.generateBtcUpdate()
        closed.viewModel.generateSpyUpdate()

        assertEquals(0, closed.marketDao.insertCalls)
        assertEquals(0, closed.featureDao.insertCalls)
        assertEquals(0, closed.signalDao.insertCalls)
        assertEquals(0, closed.journalDao.insertCalls)
        val state = closed.viewModel.uiState.value
        assertNull(state.lastSymbol)
        assertNull(state.lastError)
        assertEquals(0, state.persistedBarCount)
        assertTrue(state.demoStatus.orEmpty().contains("Debug builds"))
    }

    /** The closed gate keeps the reset action, and the reset stays non-destructive. */
    @Test
    fun `closed build gate keeps the reset action available and non-destructive`() {
        val closed = OfflineDemoHarness(demoGeneratorsEnabled = false)

        closed.viewModel.resetDemoStatus()

        assertTrue(closed.viewModel.uiState.value.demoStatus.orEmpty().contains("kept"))
        assertEquals(0, closed.marketDao.deleteBySymbolCalls + closed.marketDao.clearCalls)
        assertEquals(0, closed.featureDao.clearCalls + closed.signalDao.clearCalls + closed.journalDao.clearCalls)
    }

    /** RELEASE_LEGACY_ROW_MUTATION_BY_DEMO=0: a closed gate cannot overwrite a same-minute legacy row. */
    @Test
    fun `closed build gate leaves a same-minute legacy row unchanged`() {
        val closed = OfflineDemoHarness(demoGeneratorsEnabled = false)
        runBlocking { closed.marketDao.insert(legacyBtcRow()) }
        val seeded = closed.marketDao.rows.single()
        val insertsBefore = closed.marketDao.insertCalls

        closed.viewModel.generateBtcUpdate()

        assertEquals(seeded, closed.marketDao.rows.single())
        assertEquals(insertsBefore, closed.marketDao.insertCalls)
    }

    /**
     * DEBUG_DEMO_PERSISTENCE_REMAINS_LEGACY_CONTAMINATING_BY_DESIGN: an open gate writes through the same REPLACE
     * insert, so a same-minute row is replaced. Debug rows stay quarantined with the legacy table, and they never gain
     * trustworthy provenance.
     */
    @Test
    fun `open build gate replaces a same-minute row by design`() {
        val open = OfflineDemoHarness(demoGeneratorsEnabled = true)
        val legacy = legacyBtcRow()
        runBlocking { open.marketDao.insert(legacy) }

        open.viewModel.generateBtcUpdate()

        val stored = open.marketDao.rows.single()
        assertEquals(50_005.0, stored.close)
        assertFalse(stored.close == legacy.close)
    }

    /** DEBUG_DEMO_GENERATOR_AVAILABLE at the ViewModel entry point: an open gate exposes the generators and writes. */
    @Test
    fun `open build gate exposes the demo generators and writes a bar per update`() {
        assertTrue(viewModel.uiState.value.demoGeneratorsAvailable)

        viewModel.generateBtcUpdate()

        assertEquals(1, marketDao.insertCalls)
        assertEquals(1, marketDao.rows.size)
    }
}
