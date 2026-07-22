package com.vela.android.lab.data.pipeline

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.market.SignalState
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * End-to-end JVM tests for the offline pipeline coordinator. Uses
 * real repositories backed by pure-Kotlin fake DAOs (same pattern as
 * the Phase 1.c repository tests) so the whole suite runs under
 * `:app:test` without an Android emulator.
 */
class OfflineMarketPipelineCoordinatorTest {

    private val base: Instant = Instant.parse("2026-01-01T14:30:00Z")

    private lateinit var marketDao: FakeMarketBarDao
    private lateinit var featureDao: FakeFeatureDao
    private lateinit var signalDao: FakeSignalDao
    private lateinit var journalDao: FakeJournalDao

    private lateinit var coordinator: OfflineMarketPipelineCoordinator

    @BeforeEach
    fun setUp() {
        marketDao = FakeMarketBarDao()
        featureDao = FakeFeatureDao()
        signalDao = FakeSignalDao()
        journalDao = FakeJournalDao()

        val aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        val features = FeatureEngine(aggregator, recentBarLimit = 8)
        val signals = SignalEngine(features)

        coordinator = OfflineMarketPipelineCoordinator(
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
        price: Double,
        minuteOffset: Int = 0,
        secondOffset: Int = 0,
    ): BootstrapMarketUpdate = BootstrapMarketUpdate(
        symbol = symbol,
        sequence = sequence,
        price = price,
        change = 0.0,
        timestamp = base.plusSeconds((minuteOffset * 60L) + secondOffset.toLong()),
    )

    // 1.

    @Test
    fun `one update creates and persists one bar features and signal`() = runBlocking {
        val result = coordinator.addUpdate(update("BTC/USD", 1, 100.0))

        assertTrue(result.accepted)
        assertNotNull(result.bar)
        assertNotNull(result.features)
        assertNotNull(result.signal)
        assertEquals("BTC/USD", result.symbol)
        assertEquals(4, result.journalEventsRecorded)

        assertEquals(1, marketDao.rows.size)
        assertEquals(1, featureDao.rows.size)
        assertEquals(1, signalDao.rows.size)
    }

    // 2.

    @Test
    fun `two updates in the same minute update the same bar bucket`() = runBlocking {
        coordinator.addUpdate(update("BTC/USD", 1, 100.0))
        coordinator.addUpdate(update("BTC/USD", 2, 101.0, secondOffset = 30))

        // The unique constraint on (symbol, bucketStartEpochMillis) means
        // both writes collapse onto a single row, with the second
        // overwriting the first.
        assertEquals(1, marketDao.rows.size)
        val bar = marketDao.rows.single()
        assertEquals(2, bar.updateCount)
        assertEquals(100.0, bar.open)
        assertEquals(101.0, bar.close)
    }

    // 3.

    @Test
    fun `new minute creates a new persisted bar`() = runBlocking {
        coordinator.addUpdate(update("BTC/USD", 1, 100.0))
        coordinator.addUpdate(update("BTC/USD", 2, 102.0, minuteOffset = 1))

        assertEquals(2, marketDao.rows.size)
        val sortedBars = marketDao.rows.sortedBy { it.bucketStartEpochMillis }
        assertEquals(100.0, sortedBars[0].close)
        assertEquals(102.0, sortedBars[1].close)
    }

    // 4.

    @Test
    fun `features are generated and persisted for every accepted update`() = runBlocking {
        coordinator.addUpdate(update("SPY", 1, 100.0))
        coordinator.addUpdate(update("SPY", 2, 101.0, minuteOffset = 1))

        assertEquals(2, featureDao.rows.size)
        val sorted = featureDao.rows.sortedBy { it.bucketStartEpochMillis }
        // The first bar was open=close → direction "flat".
        assertEquals("flat", sorted[0].direction)
        // The second bar in a new bucket has open == close → "flat", and the
        // recentBarCount becomes 2 once two bars exist in history.
        assertEquals(2, sorted[1].recentBarCount)
    }

    // 5.

    @Test
    fun `signals are generated and persisted with state derived from features`() = runBlocking {
        // First update: flat / score 0 → NEUTRAL.
        val flat = coordinator.addUpdate(update("SPY", 1, 100.0))
        assertEquals(SignalState.NEUTRAL, flat.signal?.state)

        // Send three more updates in the same minute pushing close up.
        coordinator.addUpdate(update("SPY", 2, 100.5, secondOffset = 10))
        coordinator.addUpdate(update("SPY", 3, 101.0, secondOffset = 20))
        // Now move to a new minute with another strong push.
        val strong = coordinator.addUpdate(update("SPY", 4, 102.0, minuteOffset = 1))

        assertNotNull(strong.signal)
        // After the new-minute bar, direction is flat (open=close in new bucket
        // for the first update of the bucket), but shortReturn is positive
        // (102 vs prior 101) so score should still be at least 1 → NEUTRAL.
        // Either NEUTRAL or BULLISH is acceptable per the scoring rules.
        assertTrue(strong.signal!!.state in setOf(SignalState.NEUTRAL, SignalState.BULLISH))

        // 3 of the 4 updates landed in the same minute bucket, so the
        // signal REPLACE on (symbol, bucketStartEpochMillis) collapses
        // them onto a single row; the 4th update opens bucket 1.
        // Persisted rows: one per unique bucket = 2.
        assertEquals(2, signalDao.rows.size)
        // Both rows should be for SPY in chronological bucket order.
        val sortedSignals = signalDao.rows.sortedBy { it.bucketStartEpochMillis }
        assertEquals(listOf("SPY", "SPY"), sortedSignals.map { it.symbol })
    }

    // 6.

    @Test
    fun `BTCUSD and BTC slash USD normalize to the same canonical row`() = runBlocking {
        coordinator.addUpdate(update("btcusd", 1, 50_000.0))
        coordinator.addUpdate(update("BTC/USD", 2, 50_010.0, secondOffset = 30))
        coordinator.addUpdate(update("BTCUSD", 3, 50_020.0, secondOffset = 40))

        // All three writes target the same canonical "BTC/USD" symbol
        // and the same bucket → exactly one row in each table.
        assertEquals(1, marketDao.rows.size)
        assertEquals("BTC/USD", marketDao.rows.single().symbol)

        assertEquals(1, featureDao.rows.size)
        assertEquals("BTC/USD", featureDao.rows.single().symbol)

        assertEquals(1, signalDao.rows.size)
        assertEquals("BTC/USD", signalDao.rows.single().symbol)
    }

    // 7.

    @Test
    fun `journal receives the four expected events for one accepted update`() = runBlocking {
        coordinator.addUpdate(update("BTC/USD", 1, 100.0))

        val recordedTypes = journalDao.rows.map { it.eventType }
        assertEquals(
            listOf(
                PipelineEventTypes.MARKET_UPDATE_RECEIVED,
                PipelineEventTypes.BAR_PERSISTED,
                PipelineEventTypes.FEATURES_PERSISTED,
                PipelineEventTypes.SIGNAL_PERSISTED,
            ),
            recordedTypes,
        )

        val byType = journalDao.rows.groupBy { it.eventType }
        // Every accepted event carries the normalized symbol.
        for ((_, rows) in byType) {
            assertEquals(listOf("BTC/USD"), rows.map { it.symbol })
        }
        // Payloads contain useful diagnostic snippets.
        assertTrue(
            byType[PipelineEventTypes.MARKET_UPDATE_RECEIVED]!!.single().payloadJson!!
                .contains("\"sequence\":1"),
        )
        assertTrue(
            byType[PipelineEventTypes.SIGNAL_PERSISTED]!!.single().payloadJson!!
                .contains("\"state\":\"NEUTRAL\""),
        )
    }

    // 8.

    @Test
    fun `empty or whitespace symbol is rejected with a single journal event`() = runBlocking {
        val empty = coordinator.addUpdate(update("", 99, 50.0))

        assertEquals(false, empty.accepted)
        assertEquals(1, empty.journalEventsRecorded)
        assertNull(empty.bar)
        assertNull(empty.features)
        assertNull(empty.signal)

        assertEquals(0, marketDao.rows.size)
        assertEquals(0, featureDao.rows.size)
        assertEquals(0, signalDao.rows.size)
        assertEquals(1, journalDao.rows.size)
        val rejection = journalDao.rows.single()
        assertEquals(PipelineEventTypes.INVALID_MARKET_UPDATE, rejection.eventType)
        assertNull(rejection.symbol)
        assertTrue(rejection.payloadJson!!.contains("\"reason\":\"empty_symbol\""))

        // Whitespace-only spelling is also rejected.
        val whitespace = coordinator.addUpdate(update("   ", 100, 50.0))
        assertEquals(false, whitespace.accepted)
        assertEquals(2, journalDao.rows.size)
    }

    // Extra invariants

    @Test
    fun `bars persist in chronological order across many updates`() = runBlocking {
        // Insert out of order: minute 2, then 0, then 1.
        coordinator.addUpdate(update("SPY", 1, 102.0, minuteOffset = 2))
        coordinator.addUpdate(update("SPY", 2, 100.0))
        coordinator.addUpdate(update("SPY", 3, 101.0, minuteOffset = 1))

        val bars = marketDao.rows.sortedBy { it.bucketStartEpochMillis }
        assertEquals(listOf(100.0, 101.0, 102.0), bars.map { it.close })
    }

    @Test
    fun `feature and signal rows share the symbol and bucketStart of the bar`() = runBlocking {
        coordinator.addUpdate(update("BTC/USD", 1, 100.0))

        val bar = marketDao.rows.single()
        val features = featureDao.rows.single()
        val signal = signalDao.rows.single()

        assertEquals(bar.symbol, features.symbol)
        assertEquals(bar.symbol, signal.symbol)
        assertEquals(bar.bucketStartEpochMillis, features.bucketStartEpochMillis)
        assertEquals(bar.bucketStartEpochMillis, signal.bucketStartEpochMillis)
    }
}

// --- Fake DAOs (mirror the SQL semantics that Room will implement) --

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
