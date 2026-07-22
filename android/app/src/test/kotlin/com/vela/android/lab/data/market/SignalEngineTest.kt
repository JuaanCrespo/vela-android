package com.vela.android.lab.data.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Port of `tests/test_signal_engine.py`. Threshold semantics match
 * the Windows project byte-for-byte: ±0.001 cutoff for return /
 * percentChange, 0.5 barRange amplifier, score ≥2 → BULLISH,
 * ≤-2 → BEARISH.
 */
class SignalEngineTest {

    private lateinit var barAggregator: OneMinuteBarAggregator
    private lateinit var featureEngine: FeatureEngine
    private lateinit var signalEngine: SignalEngine

    @BeforeEach
    fun setUp() {
        barAggregator = OneMinuteBarAggregator(maxBarsPerSymbol = 8)
        featureEngine = FeatureEngine(barAggregator, recentBarLimit = 8)
        signalEngine = SignalEngine(featureEngine)
    }

    private fun features(
        symbol: String,
        shortReturn: Double,
        percentChange: Double,
        barRange: Double,
        direction: String,
        recentBarCount: Int = 2,
    ): SymbolFeatures = SymbolFeatures(
        symbol = symbol,
        bucketStart = Instant.parse("2026-01-01T14:30:00Z"),
        shortReturn = shortReturn,
        percentChange = percentChange,
        barRange = barRange,
        direction = direction,
        recentBarCount = recentBarCount,
    )

    @Test
    fun `derives bullish signal from positive features`() {
        signalEngine.addFeatures(
            features(
                symbol = "SPY",
                shortReturn = 0.01,
                percentChange = 0.005,
                barRange = 0.8,
                direction = "up",
            )
        )

        val signal = signalEngine.signalFor("SPY")

        assertNotNull(signal)
        signal!!
        assertEquals(SignalState.BULLISH, signal.state)
        assertTrue(signal.score >= 2, "score should be >= 2, was ${signal.score}")
    }

    @Test
    fun `derives bearish signal from negative features`() {
        signalEngine.addFeatures(
            features(
                symbol = "QQQ",
                shortReturn = -0.01,
                percentChange = -0.005,
                barRange = 0.8,
                direction = "down",
            )
        )

        val signal = signalEngine.signalFor("QQQ")

        assertNotNull(signal)
        signal!!
        assertEquals(SignalState.BEARISH, signal.state)
        assertTrue(signal.score <= -2, "score should be <= -2, was ${signal.score}")
    }

    @Test
    fun `mixed features produce neutral signal and status`() {
        signalEngine.addFeatures(
            features(
                symbol = "IWM",
                shortReturn = 0.0001,
                percentChange = 0.0001,
                barRange = 0.1,
                direction = "flat",
            )
        )

        val signal = signalEngine.signalFor("IWM")
        val status = signalEngine.status()

        assertNotNull(signal)
        signal!!
        assertEquals(SignalState.NEUTRAL, signal.state)
        assertEquals(1, status.symbolCount)
        assertEquals(listOf("IWM"), status.readySymbols)
        assertEquals(signal, status.latestSignal)
    }

    @Test
    fun `bar range threshold amplifies direction bonus`() {
        // direction=up, ret/pct just under thresholds, barRange exactly 0.5.
        // Score: +1 (up) + 0 (ret) + 0 (pct) + 1 (range≥0.5 and up) = 2 → BULLISH.
        signalEngine.addFeatures(
            features(
                symbol = "SPY",
                shortReturn = 0.0,
                percentChange = 0.0,
                barRange = 0.5,
                direction = "up",
            )
        )

        val signal = signalEngine.signalFor("SPY")

        assertNotNull(signal)
        assertEquals(SignalState.BULLISH, signal!!.state)
        assertEquals(2, signal.score)
    }

    @Test
    fun `bar range below threshold does not amplify`() {
        // Same setup but barRange = 0.49 → no amplifier. Score 1 → NEUTRAL.
        signalEngine.addFeatures(
            features(
                symbol = "SPY",
                shortReturn = 0.0,
                percentChange = 0.0,
                barRange = 0.49,
                direction = "up",
            )
        )

        val signal = signalEngine.signalFor("SPY")

        assertNotNull(signal)
        assertEquals(SignalState.NEUTRAL, signal!!.state)
        assertEquals(1, signal.score)
    }
}
