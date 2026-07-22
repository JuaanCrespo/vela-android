package com.vela.android.lab.data.market.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketTickBufferTest {

    private fun tick(
        symbol: String,
        bid: Double,
        ask: Double,
        marketMs: Long,
        receivedMs: Long,
        source: String = "alpaca-iex-stream",
    ): MarketTick = MarketTick(
        symbol = symbol,
        bidPrice = bid,
        askPrice = ask,
        marketTimestampMillis = marketMs,
        receivedAtMillis = receivedMs,
        source = source,
    )

    @Test
    fun `initial snapshot is empty`() {
        val buf = MarketTickBuffer()
        val s = buf.snapshot.value
        assertEquals(0, s.totalQuotes)
        assertEquals(0, s.totalBars)
        assertEquals(0, s.droppedOverflow)
        assertEquals(0, s.bufferSize)
        assertNull(s.lastParserError)
        assertTrue(s.perSymbol.isEmpty())
    }

    @Test
    fun `pushQuote populates per-symbol summary with spread + latency`() {
        val buf = MarketTickBuffer()
        buf.pushQuote(tick("SPY", bid = 520.10, ask = 520.20, marketMs = 1_000_000L, receivedMs = 1_000_050L))
        val s = buf.snapshot.value
        assertEquals(1, s.totalQuotes)
        assertEquals(1, s.bufferSize)
        val stats = s.perSymbol["SPY"]
        assertNotNull(stats)
        assertEquals(520.10, stats!!.lastBid)
        assertEquals(520.20, stats.lastAsk)
        // spread is computed in MarketTick.spread = ask - bid
        assertEquals(520.20 - 520.10, stats.spread, 1e-9)
        assertEquals(50L, stats.lastLatencyMillis)
        assertEquals(1, stats.quotesReceived)
        // First quote per symbol has no inter-message delta.
        assertNull(stats.lastInterMessageMillis)
    }

    @Test
    fun `interMessageMillis is computed per-symbol against the prior tick`() {
        val buf = MarketTickBuffer()
        buf.pushQuote(tick("SPY", 520.10, 520.20, marketMs = 1_000_000L, receivedMs = 1_000_050L))
        buf.pushQuote(tick("SPY", 520.15, 520.25, marketMs = 1_000_300L, receivedMs = 1_000_375L))
        val s = buf.snapshot.value
        val stats = s.perSymbol["SPY"]!!
        // 1_000_375 - 1_000_050 = 325 ms
        assertEquals(325L, stats.lastInterMessageMillis)
        assertEquals(2, stats.quotesReceived)
        assertEquals(2, s.totalQuotes)
    }

    @Test
    fun `inter-message clock is separated per symbol`() {
        val buf = MarketTickBuffer()
        buf.pushQuote(tick("SPY", 520.10, 520.20, 1_000_000L, 1_000_050L))
        buf.pushQuote(tick("QQQ", 430.10, 430.20, 1_000_100L, 1_000_150L))
        buf.pushQuote(tick("SPY", 520.15, 520.25, 1_000_200L, 1_000_300L))
        val spyStats = buf.snapshot.value.perSymbol["SPY"]!!
        val qqqStats = buf.snapshot.value.perSymbol["QQQ"]!!
        // SPY's second tick: 1_000_300 - 1_000_050 = 250
        assertEquals(250L, spyStats.lastInterMessageMillis)
        // QQQ has only one tick: null
        assertNull(qqqStats.lastInterMessageMillis)
    }

    @Test
    fun `per-symbol cap drops oldest and increments droppedOverflow`() {
        val buf = MarketTickBuffer(perSymbolCap = 3, totalCap = 100)
        for (i in 1..5) {
            buf.pushQuote(tick("SPY", 520.0 + i, 521.0 + i, marketMs = i.toLong(), receivedMs = (i + 1).toLong()))
        }
        val s = buf.snapshot.value
        assertEquals(5, s.totalQuotes)
        assertEquals(3, s.bufferSize)
        assertEquals(2, s.droppedOverflow)
        // The latest tick wins the per-symbol summary.
        val stats = s.perSymbol["SPY"]!!
        assertEquals(525.0, stats.lastBid)
    }

    @Test
    fun `total cap drops oldest from biggest deque`() {
        val buf = MarketTickBuffer(perSymbolCap = 100, totalCap = 3)
        buf.pushQuote(tick("SPY", 1.0, 1.1, 1L, 2L))
        buf.pushQuote(tick("SPY", 1.1, 1.2, 3L, 4L))
        buf.pushQuote(tick("QQQ", 2.0, 2.1, 5L, 6L))
        // bufferSize = 3, at cap.
        buf.pushQuote(tick("AAPL", 3.0, 3.1, 7L, 8L))
        // bufferSize must still be 3; dropped = 1 (SPY oldest)
        val s = buf.snapshot.value
        assertEquals(4, s.totalQuotes)
        assertEquals(3, s.bufferSize)
        assertEquals(1, s.droppedOverflow)
    }

    @Test
    fun `recordBar advances bars counter for the symbol`() {
        val buf = MarketTickBuffer()
        buf.recordBar("SPY")
        buf.recordBar("SPY")
        buf.recordBar("QQQ")
        val s = buf.snapshot.value
        assertEquals(3, s.totalBars)
        assertEquals(2, s.perSymbol["SPY"]!!.barsReceived)
        assertEquals(1, s.perSymbol["QQQ"]!!.barsReceived)
    }

    @Test
    fun `recordParserError populates lastParserError`() {
        val buf = MarketTickBuffer()
        buf.recordParserError("invalid JSON")
        assertEquals("invalid JSON", buf.snapshot.value.lastParserError)
    }

    @Test
    fun `clear resets the buffer`() {
        val buf = MarketTickBuffer()
        buf.pushQuote(tick("SPY", 520.0, 521.0, 1L, 2L))
        buf.recordBar("SPY")
        buf.recordParserError("oops")
        buf.clear()
        val s = buf.snapshot.value
        assertEquals(0, s.totalQuotes)
        assertEquals(0, s.totalBars)
        assertEquals(0, s.bufferSize)
        assertEquals(0, s.droppedOverflow)
        assertNull(s.lastParserError)
        assertTrue(s.perSymbol.isEmpty())
    }

    @Test
    fun `no method on MarketTickBuffer has a trading-shape name`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
        )
        val names = MarketTickBuffer::class.java.declaredMethods.map { it.name }
        for (name in names) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "buffer method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}
