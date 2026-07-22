package com.vela.android.lab.db

import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.data.market.SignalState
import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.data.market.SymbolSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Round-trip tests for the domain ↔ entity mappers. These also pin
 * down the "normalize on the way IN" defense the mappers apply.
 */
class MappersTest {

    private val bucket = Instant.parse("2026-01-01T14:30:00Z")
    private val lastUpdate = Instant.parse("2026-01-01T14:30:42Z")

    private fun sampleBar(symbol: String = "BTC/USD") = OneMinuteBar(
        symbol = symbol,
        bucketStart = bucket,
        open = 100.0,
        high = 101.5,
        low = 99.25,
        close = 100.75,
        updateCount = 3,
        syntheticVolume = 3.0,
        lastUpdateTime = lastUpdate,
    )

    private fun sampleFeatures(symbol: String = "BTC/USD") = SymbolFeatures(
        symbol = symbol,
        bucketStart = bucket,
        shortReturn = 0.0123,
        percentChange = 0.0075,
        barRange = 2.25,
        direction = "up",
        recentBarCount = 4,
    )

    private fun sampleSignal(symbol: String = "BTC/USD") = SymbolSignal(
        symbol = symbol,
        bucketStart = bucket,
        state = SignalState.BULLISH,
        score = 3,
        shortReturn = 0.0123,
        percentChange = 0.0075,
        barRange = 2.25,
        direction = "up",
    )

    @Test
    fun `OneMinuteBar round trip preserves every field`() {
        val bar = sampleBar()
        val roundTripped = bar.toEntity().toDomain()
        assertEquals(bar, roundTripped)
    }

    @Test
    fun `OneMinuteBar with null lastUpdateTime survives the round trip`() {
        val bar = sampleBar().copy(lastUpdateTime = null)
        val entity = bar.toEntity()
        assertNull(entity.lastUpdateTimeEpochMillis)
        assertEquals(bar, entity.toDomain())
    }

    @Test
    fun `OneMinuteBar entity stores normalized BASE slash QUOTE symbol`() {
        // Caller supplies a non-canonical symbol; the mapper normalizes
        // it before persistence so queries with any spelling will hit.
        val bar = sampleBar(symbol = "btcusd")
        val entity = bar.toEntity()
        assertEquals("BTC/USD", entity.symbol)
    }

    @Test
    fun `OneMinuteBar entity normalizes an already-canonical symbol unchanged`() {
        val entity = sampleBar(symbol = "BTC/USD").toEntity()
        assertEquals("BTC/USD", entity.symbol)
    }

    @Test
    fun `SymbolFeatures round trip preserves every field`() {
        val features = sampleFeatures()
        assertEquals(features, features.toEntity().toDomain())
    }

    @Test
    fun `SymbolFeatures entity normalizes the symbol`() {
        assertEquals("BTC/USD", sampleFeatures(symbol = "btcusd").toEntity().symbol)
    }

    @Test
    fun `SymbolSignal round trip preserves state enum`() {
        val signal = sampleSignal(symbol = "SPY").copy(state = SignalState.BEARISH)
        val roundTripped = signal.toEntity().toDomain()
        assertEquals(SignalState.BEARISH, roundTripped.state)
        assertEquals(signal, roundTripped)
    }

    @Test
    fun `SymbolSignal entity stores enum string value`() {
        val entity = sampleSignal().copy(state = SignalState.NEUTRAL).toEntity()
        assertEquals("NEUTRAL", entity.state)
    }

    @Test
    fun `SymbolSignal entity normalizes the symbol`() {
        assertEquals("BTC/USD", sampleSignal(symbol = "btcusd").toEntity().symbol)
    }

    @Test
    fun `journalEvent helper normalizes the symbol`() {
        val event = journalEvent(
            eventType = "test",
            timestamp = Instant.parse("2026-02-03T10:00:00Z"),
            symbol = "ethusd",
            payloadJson = "{}",
        )
        assertEquals("ETH/USD", event.symbol)
    }

    @Test
    fun `journalEvent helper keeps null symbol when none is provided`() {
        val event = journalEvent(
            eventType = "system",
            timestamp = Instant.parse("2026-02-03T10:00:00Z"),
        )
        assertNull(event.symbol)
    }

    @Test
    fun `journalEvent helper preserves payload json`() {
        val event = journalEvent(
            eventType = "paper_order",
            timestamp = Instant.parse("2026-02-03T10:00:00Z"),
            symbol = "SPY",
            payloadJson = """{"side":"buy","qty":1}""",
        )
        assertEquals("""{"side":"buy","qty":1}""", event.payloadJson)
        assertEquals(Instant.parse("2026-02-03T10:00:00Z").toEpochMilli(), event.timestampEpochMillis)
    }
}
