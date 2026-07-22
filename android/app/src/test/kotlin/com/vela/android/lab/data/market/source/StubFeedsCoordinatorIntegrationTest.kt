@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source

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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves that the read-only boundary client can drive the existing
 * Phase 1.d coordinator without any code in the pipeline knowing
 * about the boundary. The wiring is purely caller-side: collect
 * from the SharedFlow, forward to `coordinator.addUpdate(...)`.
 *
 * Phase 1 behavior is unchanged.
 */
class StubFeedsCoordinatorIntegrationTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

    @Test
    fun `stub-emitted updates persist through the offline coordinator`() =
        runTest(UnconfinedTestDispatcher()) {
            val marketDao = FakeMarketBarDao()
            val featureDao = FakeFeatureDao()
            val signalDao = FakeSignalDao()
            val journalDao = FakeJournalDao()

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

            val client = StubPaperMarketDataClient(clock = fixedClock)
            client.connect()
            client.subscribe(setOf("BTC/USD", "SPY"))

            // Drive the stub directly and forward each emission to
            // the coordinator. In production a coroutine would
            // `client.updates.collect { coordinator.addUpdate(it) }`,
            // but in this test we keep it deterministic.
            coordinator.addUpdate(client.emitDemoUpdate("BTC/USD"))
            coordinator.addUpdate(client.emitDemoUpdate("SPY"))

            // Two bars persisted, one per canonical symbol.
            assertEquals(2, marketDao.rows.size)
            val symbols = marketDao.rows.map { it.symbol }.toSet()
            assertEquals(setOf("BTC/USD", "SPY"), symbols)

            // Each accepted update writes 4 journal events.
            assertEquals(8, journalDao.rows.size)

            // Signals and features mirror the bar count.
            assertEquals(2, featureDao.rows.size)
            assertEquals(2, signalDao.rows.size)

            // Subscription state survived the test.
            assertTrue(client.subscribedSymbols().contains("BTC/USD"))
            assertTrue(client.subscribedSymbols().contains("SPY"))

            // Connection remained CONNECTED throughout.
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
        }
}

// --- Fake DAOs (mirror SQL semantics; private to this test file) ----

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
