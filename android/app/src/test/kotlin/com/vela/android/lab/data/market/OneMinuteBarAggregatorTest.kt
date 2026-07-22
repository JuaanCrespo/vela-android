package com.vela.android.lab.data.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers behaviors that `app/data/bar_aggregator.py` exhibits.
 * There is no dedicated Python test file for the bar aggregator
 * (only the market buffer is tested directly), so these cases come
 * from reading the Python implementation and the FeatureEngine tests
 * that exercise the aggregator indirectly.
 */
class OneMinuteBarAggregatorTest {

    private lateinit var aggregator: OneMinuteBarAggregator

    @BeforeEach
    fun setUp() {
        aggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 4)
    }

    private fun update(
        symbol: String,
        sequence: Int,
        price: Double,
        minuteOffset: Int = 0,
        secondOffset: Int = 0,
        volume: Double? = null,
    ): BootstrapMarketUpdate {
        val base = Instant.parse("2026-01-01T14:30:00Z")
        val ts = base.plusSeconds((minuteOffset * 60L) + secondOffset.toLong())
        return BootstrapMarketUpdate(
            symbol = symbol,
            sequence = sequence,
            price = price,
            change = 0.0,
            timestamp = ts,
            volume = volume,
        )
    }

    @Test
    fun `first update creates a new bar for the symbol`() {
        aggregator.addUpdate(update("SPY", 1, 100.0))

        val bar = aggregator.currentBar("SPY")

        assertNotNull(bar)
        assertEquals("SPY", bar!!.symbol)
        assertEquals(100.0, bar.open)
        assertEquals(100.0, bar.high)
        assertEquals(100.0, bar.low)
        assertEquals(100.0, bar.close)
        assertEquals(1, bar.updateCount)
        assertEquals(1.0, bar.syntheticVolume)
    }

    @Test
    fun `two updates in same minute merge into one bar`() {
        aggregator.addUpdate(update("SPY", 1, 100.0))
        aggregator.addUpdate(update("SPY", 2, 101.0, secondOffset = 20))

        val bar = aggregator.currentBar("SPY")

        assertNotNull(bar)
        assertEquals(100.0, bar!!.open)
        assertEquals(101.0, bar.high)
        assertEquals(100.0, bar.low)
        assertEquals(101.0, bar.close)
        assertEquals(2, bar.updateCount)
        // Volume not supplied — synthetic_volume increments by 1.0.
        assertEquals(2.0, bar.syntheticVolume)
        assertEquals(1, aggregator.barCount("SPY"))
    }

    @Test
    fun `update in a new minute creates a second bar`() {
        aggregator.addUpdate(update("SPY", 1, 100.0))
        aggregator.addUpdate(update("SPY", 2, 102.0, minuteOffset = 1))

        val bars = aggregator.recentBars("SPY")

        assertEquals(2, bars.size)
        assertEquals(100.0, bars[0].close)
        assertEquals(102.0, bars[1].open)
        assertEquals(102.0, bars[1].close)
        assertEquals(2, aggregator.barCount("SPY"))
    }

    @Test
    fun `synthetic volume replaces when update volume supplied`() {
        aggregator.addUpdate(update("SPY", 1, 100.0, volume = 7.0))
        // Same bucket, volume supplied again — replaces, does NOT add.
        aggregator.addUpdate(update("SPY", 2, 101.0, secondOffset = 30, volume = 13.0))

        val bar = aggregator.currentBar("SPY")

        assertNotNull(bar)
        assertEquals(13.0, bar!!.syntheticVolume)
    }

    @Test
    fun `bounded history evicts oldest bars`() {
        // maxBarsPerSymbol = 4 from setUp.
        for (i in 0 until 6) {
            aggregator.addUpdate(update("SPY", i + 1, 100.0 + i, minuteOffset = i))
        }

        val bars = aggregator.recentBars("SPY")

        assertEquals(4, bars.size)
        // First two bars (prices 100, 101) should have been evicted.
        assertEquals(102.0, bars[0].open)
        assertEquals(105.0, bars[3].open)
    }

    @Test
    fun `status sorts symbols alphabetically and tracks latest`() {
        aggregator.addUpdate(update("SPY", 1, 100.0))
        aggregator.addUpdate(update("QQQ", 2, 200.0))

        val status = aggregator.status()

        assertEquals(2, status.symbolCount)
        assertEquals(2, status.totalBars)
        assertEquals(listOf("QQQ" to 1, "SPY" to 1), status.countsBySymbol)
        assertEquals("QQQ", status.latestBar?.symbol)
    }

    @Test
    fun `unknown symbol returns no bars and no current bar`() {
        assertNull(aggregator.currentBar("SPY"))
        assertEquals(emptyList<OneMinuteBar>(), aggregator.recentBars("SPY"))
        assertEquals(0, aggregator.barCount("SPY"))
    }

    @Test
    fun `crypto symbol is normalized to BASE slash QUOTE`() {
        aggregator.addUpdate(update("btcusd", 1, 50000.0))

        val bar = aggregator.currentBar("BTC/USD")

        assertNotNull(bar)
        assertEquals("BTC/USD", bar!!.symbol)
    }

    @Test
    fun `listeners receive bar and status emissions`() {
        val bars = mutableListOf<OneMinuteBar>()
        val statuses = mutableListOf<BarAggregatorStatus>()
        aggregator.addBarListener { bars += it }
        aggregator.addStatusListener { statuses += it }

        aggregator.addUpdate(update("SPY", 1, 100.0))
        aggregator.addUpdate(update("SPY", 2, 101.0, secondOffset = 30))

        assertEquals(2, bars.size)
        assertEquals(2, statuses.size)
        assertTrue(statuses.last().latestBar?.close == 101.0)
    }
}
