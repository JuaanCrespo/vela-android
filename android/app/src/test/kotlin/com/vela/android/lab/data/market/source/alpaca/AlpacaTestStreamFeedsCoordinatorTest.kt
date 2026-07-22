@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Integration test: drives the Phase 2.b Alpaca test stream client
 * through scripted server messages and verifies that bar updates
 * land in the existing Phase 1.c/1.d Room-backed coordinator.
 */
class AlpacaTestStreamFeedsCoordinatorTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

    @Test
    fun `FAKEPACA bars from the test stream persist through the offline coordinator`() =
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

            val factory = ScriptedWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )

            // Forward every emitted update into the existing offline
            // coordinator. Same pattern a real Phase 2.c wiring would use.
            val pump = launch {
                client.updates.collect { coordinator.addUpdate(it) }
            }

            client.subscribe(setOf("FAKEPACA"))
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.0,"l":1.0,"c":1.0,"v":1,"t":"2026-01-01T14:30:00Z"}]""",
            )
            factory.deliver(
                """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.1,"l":1.0,"c":1.1,"v":2,"t":"2026-01-01T14:31:00Z"}]""",
            )

            // Two bars, one feature row per minute bucket, one signal
            // per bucket, four journal rows per accepted update.
            assertEquals(2, marketDao.rows.size)
            assertEquals(setOf("FAKEPACA"), marketDao.rows.map { it.symbol }.toSet())
            assertEquals(2, featureDao.rows.size)
            assertEquals(2, signalDao.rows.size)
            assertEquals(8, journalDao.rows.size)

            pump.cancel()
        }
}

// --- Test doubles --------------------------------------------------

private class ScriptedWebSocketFactory : AlpacaWebSocketFactory {
    lateinit var listener: AlpacaWebSocketListener
        private set

    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        this.listener = listener
        listener.onOpen()
        return object : AlpacaWebSocketHandle {
            override fun send(text: String): Boolean = true
            override fun close(code: Int, reason: String) = Unit
        }
    }

    fun deliver(payload: String) {
        listener.onMessage(payload)
    }
}

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
