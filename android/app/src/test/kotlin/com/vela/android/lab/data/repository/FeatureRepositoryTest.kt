package com.vela.android.lab.data.repository

import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.db.room.dao.FeatureDao
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class FeatureRepositoryTest {

    private val base: Instant = Instant.parse("2026-01-01T14:30:00Z")

    private fun features(
        symbol: String = "BTC/USD",
        minuteOffset: Int = 0,
        shortReturn: Double = 0.01,
    ): SymbolFeatures = SymbolFeatures(
        symbol = symbol,
        bucketStart = base.plusSeconds(minuteOffset * 60L),
        shortReturn = shortReturn,
        percentChange = 0.005,
        barRange = 0.5,
        direction = "up",
        recentBarCount = 2,
    )

    @Test
    fun `latestFor returns the most recent bucket for the symbol`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        repo.persist(features(minuteOffset = 0))
        repo.persist(features(minuteOffset = 1, shortReturn = 0.02))
        repo.persist(features(minuteOffset = 2, shortReturn = 0.03))

        val latest = repo.latestFor("BTC/USD")

        assertNotNull(latest)
        assertEquals(0.03, latest!!.shortReturn)
        assertEquals(base.plusSeconds(120L), latest.bucketStart)
    }

    @Test
    fun `BTCUSD spelling resolves to canonical row`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        repo.persist(features(symbol = "BTC/USD"))
        assertEquals(1, repo.forSymbol("BTCUSD").size)
        assertNotNull(repo.latestFor("btcusd"))
    }

    @Test
    fun `forSymbol returns rows in ascending bucket order`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        repo.persist(features(minuteOffset = 5))
        repo.persist(features(minuteOffset = 1))
        repo.persist(features(minuteOffset = 3))

        val rows = repo.forSymbol("BTC/USD")

        assertEquals(
            listOf(
                base.plusSeconds(60L),
                base.plusSeconds(180L),
                base.plusSeconds(300L),
            ),
            rows.map { it.bucketStart },
        )
    }

    @Test
    fun `latestFor returns null when no rows exist`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        assertNull(repo.latestFor("BTC/USD"))
        assertEquals(0, repo.count("BTC/USD"))
    }

    @Test
    fun `persistAll inserts all rows`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        val items = listOf(
            features(minuteOffset = 0),
            features(minuteOffset = 1),
            features(minuteOffset = 2),
        )
        repo.persistAll(items)
        assertEquals(3, repo.count("BTC/USD"))
    }

    @Test
    fun `clear removes everything`() = runBlocking {
        val repo = FeatureRepository(FakeFeatureDao())
        repo.persist(features())
        repo.clear()
        assertEquals(0, repo.count("BTC/USD"))
    }
}

private class FakeFeatureDao : FeatureDao {
    private val rows: MutableList<SymbolFeaturesEntity> = mutableListOf()
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
        rows.filter { it.symbol == symbol }
            .sortedBy { it.bucketStartEpochMillis }

    override suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }
            .take(limit)

    override suspend fun latestFor(symbol: String): SymbolFeaturesEntity? =
        rows.filter { it.symbol == symbol }
            .maxByOrNull { it.bucketStartEpochMillis }

    override suspend fun countBySymbol(symbol: String): Int =
        rows.count { it.symbol == symbol }

    override suspend fun clear() {
        rows.clear()
    }
}
