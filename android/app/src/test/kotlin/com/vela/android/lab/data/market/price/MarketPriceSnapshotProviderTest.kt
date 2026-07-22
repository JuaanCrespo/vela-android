@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketTick
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketPriceSnapshotProviderTest {

    private fun newProvider(
        tickBuffer: MarketTickBuffer,
        marketDao: MarketBarDao,
        nowMs: Long = 100_000L,
    ): MarketPriceSnapshotProvider = MarketPriceSnapshotProvider(
        tickBuffer = tickBuffer,
        marketDataRepository = MarketDataRepository(marketDao),
        freshnessPolicy = MarketPriceFreshnessPolicy(),
        clock = { Instant.ofEpochMilli(nowMs) },
    )

    @Test
    fun `live quote mid is the highest-priority source`() = runTest(UnconfinedTestDispatcher()) {
        val buf = MarketTickBuffer()
        buf.pushQuote(
            MarketTick(
                symbol = "SPY",
                bidPrice = 520.10,
                askPrice = 520.20,
                marketTimestampMillis = 99_000L,
                receivedAtMillis = 99_500L,
                source = "alpaca-iex-stream",
            ),
        )
        val provider = newProvider(buf, FakeBarDao(), nowMs = 100_000L)
        val snap = provider.snapshotFor("SPY")
        assertEquals(MarketPriceSource.LIVE_QUOTE_MID, snap.source)
        assertEquals(PriceFreshness.FRESH, snap.freshness)
        // mid = (520.10 + 520.20) / 2 = 520.15
        assertEquals(520.15, requireNotNull(snap.price), 1e-9)
        assertEquals(520.10, snap.bid)
        assertEquals(520.20, snap.ask)
        assertNotNull(snap.ageMillis)
        assertEquals(500L, snap.ageMillis) // 100_000 - 99_500
        assertNull(snap.reason)
    }

    @Test
    fun `falls back to Room bar close when no quote available`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeBarDao().also { dao ->
            runBlocking {
                dao.insert(
                    MarketBar1mEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 90_000L,
                        open = 1.0, high = 1.0, low = 1.0, close = 727.83,
                        updateCount = 1, syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        }
        val provider = newProvider(MarketTickBuffer(), dao, nowMs = 100_000L)
        val snap = provider.snapshotFor("SPY")
        assertEquals(MarketPriceSource.ROOM_BAR_CLOSE, snap.source)
        assertEquals(727.83, snap.price)
        assertNull(snap.bid)
        assertNull(snap.ask)
        assertEquals(10_000L, snap.ageMillis)
        // 10s old room bar is well below the 5-minute threshold → FRESH
        assertEquals(PriceFreshness.FRESH, snap.freshness)
    }

    @Test
    fun `returns MISSING when no quote and no Room bar exist`() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = newProvider(MarketTickBuffer(), FakeBarDao(), nowMs = 100_000L)
            val snap = provider.snapshotFor("SPY")
            assertEquals(MarketPriceSource.NONE, snap.source)
            assertEquals(PriceFreshness.MISSING, snap.freshness)
            assertNull(snap.price)
            assertFalse(snap.hasPrice)
            assertNotNull(snap.reason)
        }

    @Test
    fun `stale Room bar surfaces STALE and a reason string`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeBarDao().also { dao ->
            runBlocking {
                dao.insert(
                    MarketBar1mEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 1_000L,
                        open = 1.0, high = 1.0, low = 1.0, close = 500.0,
                        updateCount = 1, syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        }
        // now - 1_000 = ~10 minutes old → above 5-minute threshold
        val provider = newProvider(MarketTickBuffer(), dao, nowMs = 1_000L + 10L * 60_000L)
        val snap = provider.snapshotFor("SPY")
        assertEquals(MarketPriceSource.ROOM_BAR_CLOSE, snap.source)
        assertEquals(PriceFreshness.STALE, snap.freshness)
        assertNotNull(snap.reason)
        assertTrue(snap.reason!!.contains("Room"))
    }

    @Test
    fun `empty symbol returns MISSING immediately`() = runTest(UnconfinedTestDispatcher()) {
        val provider = newProvider(MarketTickBuffer(), FakeBarDao())
        val snap = provider.snapshotFor("")
        assertEquals(MarketPriceSource.NONE, snap.source)
        assertEquals(PriceFreshness.MISSING, snap.freshness)
    }

    @Test
    fun `symbol is uppercased before lookup`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeBarDao().also { dao ->
            runBlocking {
                dao.insert(
                    MarketBar1mEntity(
                        id = 0, symbol = "SPY",
                        bucketStartEpochMillis = 90_000L,
                        open = 1.0, high = 1.0, low = 1.0, close = 100.0,
                        updateCount = 1, syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        }
        val provider = newProvider(MarketTickBuffer(), dao, nowMs = 100_000L)
        val snap = provider.snapshotFor("spy")
        assertEquals("SPY", snap.symbol)
        assertEquals(100.0, snap.price)
    }

    @Test
    fun `stale live quote (older than 10s) is reported as STALE but still used`() =
        runTest(UnconfinedTestDispatcher()) {
            val buf = MarketTickBuffer()
            buf.pushQuote(
                MarketTick(
                    symbol = "SPY",
                    bidPrice = 100.0, askPrice = 101.0,
                    marketTimestampMillis = 50_000L,
                    receivedAtMillis = 60_000L,
                    source = "alpaca-iex-stream",
                ),
            )
            val provider = newProvider(buf, FakeBarDao(), nowMs = 80_000L)
            val snap = provider.snapshotFor("SPY")
            assertEquals(MarketPriceSource.LIVE_QUOTE_MID, snap.source)
            assertEquals(PriceFreshness.STALE, snap.freshness)
            assertNotNull(snap.price)
        }

    @Test
    fun `provider has no execution-shape method`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition",
            "post", "patch", "delete",
        )
        val methods = MarketPriceSnapshotProvider::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertFalse(
                    lower.contains(bad),
                    "provider method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}

private class FakeBarDao : MarketBarDao {
    private val rows: MutableList<MarketBar1mEntity> = mutableListOf()
    private var nextId: Long = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> = rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }.sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}
