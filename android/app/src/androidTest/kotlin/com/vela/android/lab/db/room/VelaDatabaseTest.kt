package com.vela.android.lab.db.room

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.data.market.SignalState
import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.data.market.SymbolSignal
import com.vela.android.lab.db.journalEvent
import com.vela.android.lab.db.toDomain
import com.vela.android.lab.db.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Instrumented sanity test that exercises the real SQLite-backed
 * Room implementation. Requires an Android device or emulator —
 * runs under `:app:connectedDebugAndroidTest`, **not** `:app:test`.
 *
 * Phase 1.c documents this file as a placeholder; on hosts without
 * a device attached, this test is intentionally left unrun.
 */
@RunWith(AndroidJUnit4::class)
class VelaDatabaseTest {

    private lateinit var db: VelaDatabase

    @Before
    fun setUp() {
        db = VelaDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertedBars_areReturnedInTimestampOrder() = runBlocking {
        val base = Instant.parse("2026-01-01T14:30:00Z")
        val dao = db.marketBarDao()
        dao.insert(sampleBar(base.plusSeconds(120L), 102.0).toEntity())
        dao.insert(sampleBar(base.plusSeconds(0L), 100.0).toEntity())
        dao.insert(sampleBar(base.plusSeconds(60L), 101.0).toEntity())

        val rows = dao.bySymbol("BTC/USD").map { it.toDomain() }

        assertEquals(3, rows.size)
        assertEquals(listOf(100.0, 101.0, 102.0), rows.map { it.close })
    }

    @Test
    fun featuresRoundTrip() = runBlocking {
        val dao = db.featureDao()
        val features = SymbolFeatures(
            symbol = "BTC/USD",
            bucketStart = Instant.parse("2026-01-01T14:30:00Z"),
            shortReturn = 0.01,
            percentChange = 0.005,
            barRange = 0.5,
            direction = "up",
            recentBarCount = 2,
        )
        dao.insert(features.toEntity())
        val stored = dao.latestFor("BTC/USD")?.toDomain()
        assertEquals(features, stored)
    }

    @Test
    fun signalRoundTripPreservesEnumState() = runBlocking {
        val dao = db.signalDao()
        val signal = SymbolSignal(
            symbol = "BTC/USD",
            bucketStart = Instant.parse("2026-01-01T14:30:00Z"),
            state = SignalState.BEARISH,
            score = -3,
            shortReturn = -0.02,
            percentChange = -0.01,
            barRange = 0.8,
            direction = "down",
        )
        dao.insert(signal.toEntity())
        val stored = dao.latestFor("BTC/USD")?.toDomain()
        assertEquals(SignalState.BEARISH, stored?.state)
        assertEquals(signal, stored)
    }

    @Test
    fun journalEventInsertAndQueryByType() = runBlocking {
        val dao = db.journalDao()
        val base = Instant.parse("2026-01-01T14:30:00Z")
        dao.insert(journalEvent("paper_order", base, "SPY"))
        dao.insert(journalEvent("paper_order", base.plusSeconds(60L), "SPY"))
        val rows = dao.byType("paper_order", limit = 10)
        assertEquals(2, rows.size)
        assertNotNull(rows[0].timestampEpochMillis)
    }

    private fun sampleBar(bucketStart: Instant, close: Double): OneMinuteBar = OneMinuteBar(
        symbol = "BTC/USD",
        bucketStart = bucketStart,
        open = close,
        high = close,
        low = close,
        close = close,
        updateCount = 1,
        syntheticVolume = 1.0,
        lastUpdateTime = bucketStart,
    )
}
