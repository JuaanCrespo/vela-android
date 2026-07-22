package com.vela.android.lab.ui.candles

import com.vela.android.lab.data.market.OneMinuteBar
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandleMapperTest {

    private val base = Instant.parse("2026-07-21T14:30:00Z")

    @Test
    fun `maps complete OHLC without inventing values`() {
        val source = bar(open = 100.0, high = 103.0, low = 99.0, close = 102.0, volume = 7.0)

        val result = CandleMapper.map(listOf(source), limit = 30)

        assertEquals(0, result.rejectedCount)
        assertEquals(1, result.sourceCount)
        val candle = result.candles.single()
        assertEquals(source.symbol, candle.symbol)
        assertEquals(source.bucketStart, candle.timestamp)
        assertEquals(source.open, candle.open)
        assertEquals(source.high, candle.high)
        assertEquals(source.low, candle.low)
        assertEquals(source.close, candle.close)
        assertEquals(source.syntheticVolume, candle.volume)
        assertEquals(4.0, candle.range)
    }

    @Test
    fun `derives bullish bearish and doji direction`() {
        val bullish = CandleMapper.toCandleOrNull(
            bar(open = 100.0, high = 102.0, low = 99.0, close = 101.0),
        )
        val bearish = CandleMapper.toCandleOrNull(
            bar(open = 101.0, high = 102.0, low = 99.0, close = 100.0),
        )
        val doji = CandleMapper.toCandleOrNull(
            bar(open = 100.0, high = 101.0, low = 99.0, close = 100.0),
        )

        assertEquals(CandleDirection.BULLISH, bullish?.direction)
        assertEquals(CandleDirection.BEARISH, bearish?.direction)
        assertEquals(CandleDirection.DOJI, doji?.direction)
    }

    @Test
    fun `rejects non finite non positive and incoherent OHLC`() {
        val rows = listOf(
            bar(open = Double.NaN, high = 101.0, low = 99.0, close = 100.0),
            bar(open = 0.0, high = 101.0, low = 99.0, close = 100.0),
            bar(open = 100.0, high = 99.0, low = 98.0, close = 101.0),
            bar(open = 100.0, high = 101.0, low = 100.5, close = 100.0),
        )

        val result = CandleMapper.map(rows, limit = 30)

        assertTrue(result.candles.isEmpty())
        assertEquals(4, result.rejectedCount)
        rows.forEach { assertFalse(CandleMapper.hasValidOhlc(it)) }
    }

    @Test
    fun `sorts chronologically and keeps the newest supported limit`() {
        val rows = (0 until 35)
            .map { offset ->
                val price = 100.0 + offset
                bar(
                    at = base.plusSeconds(offset * 60L),
                    open = price - 0.5,
                    high = price + 1.0,
                    low = price - 1.0,
                    close = price,
                )
            }
            .reversed()

        val result = CandleMapper.map(rows, limit = 30)

        assertEquals(30, result.candles.size)
        assertEquals(base.plusSeconds(5 * 60L), result.candles.first().timestamp)
        assertEquals(base.plusSeconds(34 * 60L), result.candles.last().timestamp)
        assertEquals(result.candles.sortedBy { it.timestamp }, result.candles)
    }

    @Test
    fun `invalid synthetic volume stays unavailable without discarding OHLC`() {
        val mapped = CandleMapper.toCandleOrNull(bar(volume = -1.0))
        assertNull(mapped?.volume)
        assertEquals(CandleDirection.BULLISH, mapped?.direction)
    }

    @Test
    fun `only 30 50 and 100 candle limits are accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            CandleMapper.map(emptyList(), limit = 25)
        }
        CandlesUiState.CANDLE_COUNT_OPTIONS.forEach { limit ->
            assertTrue(CandleMapper.map(emptyList(), limit).candles.isEmpty())
        }
    }

    @Test
    fun `freshness becomes stale strictly after configured age`() {
        val now = base.plusSeconds(120)
        val atBoundary = CandleMapper.freshness(base, now, Duration.ofSeconds(120))
        val stale = CandleMapper.freshness(base, now.plusMillis(1), Duration.ofSeconds(120))
        val unknown = CandleMapper.freshness(null, now, Duration.ofSeconds(120))

        assertEquals(CandleFreshness.FRESH, atBoundary.first)
        assertEquals(120_000L, atBoundary.second)
        assertEquals(CandleFreshness.STALE, stale.first)
        assertEquals(CandleFreshness.UNKNOWN, unknown.first)
        assertNull(unknown.second)
    }

    private fun bar(
        at: Instant = base,
        open: Double = 100.0,
        high: Double = 103.0,
        low: Double = 99.0,
        close: Double = 102.0,
        volume: Double = 1.0,
    ): OneMinuteBar = OneMinuteBar(
        symbol = "SPY",
        bucketStart = at,
        open = open,
        high = high,
        low = low,
        close = close,
        updateCount = 2,
        syntheticVolume = volume,
        lastUpdateTime = at.plusSeconds(30),
    )
}
