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

/** Clock for the offline demo tests. The generators write the 14:30 minute, which is also the legacy-row minute. */
internal val OFFLINE_DEMO_CLOCK: Instant = Instant.parse("2026-01-01T14:30:00Z")

/**
 * Builds an [OfflineDashboardViewModel] over in-memory DAO fakes. [demoGeneratorsEnabled] is the build-gate value
 * under test. Each test passes it explicitly, so the Debug and Release configuration checks stay separate.
 */
internal class OfflineDemoHarness(demoGeneratorsEnabled: Boolean) {
    val marketDao = DemoBarDao()
    val featureDao = DemoFeatureDao()
    val signalDao = DemoSignalDao()
    val journalDao = DemoJournalDao()

    val viewModel: OfflineDashboardViewModel

    init {
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
            demoGeneratorsEnabled = demoGeneratorsEnabled,
            clock = { OFFLINE_DEMO_CLOCK },
        )
    }
}

// --- Fake DAOs (mirror SQL semantics; count every write and delete attempt) ---

internal class DemoBarDao : MarketBarDao {
    val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    var insertCalls: Int = 0
    var deleteBySymbolCalls: Int = 0
    var clearCalls: Int = 0
    var failInserts: Boolean = false
    private var nextId: Long = 1L

    override suspend fun insert(bar: MarketBar1mEntity): Long {
        insertCalls += 1
        check(!failInserts) { "simulated persistence failure" }
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
        deleteBySymbolCalls += 1
        rows.removeAll { it.symbol == symbol }
    }

    override suspend fun clear() {
        clearCalls += 1
        rows.clear()
    }
}

internal class DemoFeatureDao : FeatureDao {
    val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
    var insertCalls: Int = 0
    var clearCalls: Int = 0
    private var nextId: Long = 1L

    override suspend fun insert(features: SymbolFeaturesEntity): Long {
        insertCalls += 1
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
        clearCalls += 1
        rows.clear()
    }
}

internal class DemoSignalDao : SignalDao {
    val rows: MutableList<SymbolSignalEntity> = mutableListOf()
    var insertCalls: Int = 0
    var clearCalls: Int = 0
    private var nextId: Long = 1L

    override suspend fun insert(signal: SymbolSignalEntity): Long {
        insertCalls += 1
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
        clearCalls += 1
        rows.clear()
    }
}

internal class DemoJournalDao : JournalDao {
    val rows: MutableList<JournalEventEntity> = mutableListOf()
    var insertCalls: Int = 0
    var clearCalls: Int = 0
    private var nextId: Long = 1L

    override suspend fun insert(event: JournalEventEntity): Long {
        insertCalls += 1
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
        clearCalls += 1
        rows.clear()
    }
}
