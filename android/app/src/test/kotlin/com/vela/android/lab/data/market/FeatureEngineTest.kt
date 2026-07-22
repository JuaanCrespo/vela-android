package com.vela.android.lab.data.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs

/**
 * Port of `tests/test_feature_engine.py`. Same scenarios, same
 * expected numbers; wiring goes through the Kotlin listener pattern
 * instead of Qt signals.
 */
class FeatureEngineTest {

    private lateinit var barAggregator: OneMinuteBarAggregator
    private lateinit var featureEngine: FeatureEngine

    @BeforeEach
    fun setUp() {
        barAggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        featureEngine = FeatureEngine(barAggregator, recentBarLimit = 8)
        barAggregator.addBarListener(featureEngine::addBar)
    }

    private fun update(
        symbol: String,
        sequence: Int,
        price: Double,
        minuteOffset: Int = 0,
        secondOffset: Int = 0,
    ): BootstrapMarketUpdate {
        val base = Instant.parse("2026-01-01T14:30:00Z")
        val ts = base.plusSeconds((minuteOffset * 60L) + secondOffset.toLong())
        return BootstrapMarketUpdate(
            symbol = symbol,
            sequence = sequence,
            price = price,
            change = 0.1,
            timestamp = ts,
        )
    }

    private fun assertAlmostEqual(expected: Double, actual: Double, tolerance: Double = 1e-7) {
        assertEquals(true, abs(expected - actual) < tolerance,
            "expected $expected but got $actual (delta ${abs(expected - actual)})")
    }

    @Test
    fun `derives simple features from recent bars`() {
        barAggregator.addUpdate(update("SPY", 1, 100.0))
        barAggregator.addUpdate(update("SPY", 2, 101.0, secondOffset = 20))
        barAggregator.addUpdate(update("SPY", 3, 102.0, minuteOffset = 1))
        barAggregator.addUpdate(update("SPY", 4, 103.0, minuteOffset = 1, secondOffset = 20))

        val features = featureEngine.featuresFor("SPY")

        assertNotNull(features)
        features!!
        assertEquals("SPY", features.symbol)
        assertEquals("up", features.direction)
        assertAlmostEqual(2.0 / 101.0, features.shortReturn)
        assertAlmostEqual(1.0 / 102.0, features.percentChange)
        assertAlmostEqual(1.0, features.barRange)
        assertEquals(2, features.recentBarCount)
    }

    @Test
    fun `first bar features default to flat zero return`() {
        barAggregator.addUpdate(update("QQQ", 1, 105.0))

        val features = featureEngine.featuresFor("QQQ")

        assertNotNull(features)
        features!!
        assertEquals("flat", features.direction)
        assertEquals(0.0, features.shortReturn)
        assertEquals(0.0, features.percentChange)
        assertEquals(0.0, features.barRange)
        assertEquals(1, features.recentBarCount)
    }

    @Test
    fun `status exposes ready symbols and latest features`() {
        barAggregator.addUpdate(update("SPY", 1, 100.0))
        barAggregator.addUpdate(update("QQQ", 2, 105.0))

        val status = featureEngine.status()

        assertEquals(2, status.symbolCount)
        assertEquals(listOf("QQQ", "SPY"), status.readySymbols)
        assertNotNull(status.latestFeatures)
        assertEquals("QQQ", status.latestFeatures!!.symbol)
    }
}
