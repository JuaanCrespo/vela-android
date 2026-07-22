package com.vela.android.lab.data.market

/**
 * Port of `SignalEngine` from `app/data/signal_engine.py`.
 *
 * Pure Kotlin. Same scoring rules and thresholds as the Windows
 * project, verbatim:
 *
 *   - direction "up"    → +1, "down"    → -1
 *   - shortReturn  > +0.001 → +1, < -0.001 → -1
 *   - percentChange > +0.001 → +1, < -0.001 → -1
 *   - barRange ≥ 0.5 amplifies the direction bonus by ±1
 *
 *   - score ≥  2 → BULLISH
 *   - score ≤ -2 → BEARISH
 *   - otherwise → NEUTRAL
 */
class SignalEngine(
    val featureEngine: FeatureEngine,
) {
    private val signalsBySymbol: MutableMap<String, SymbolSignal> = mutableMapOf()
    private val signalListeners: MutableList<(SymbolSignal) -> Unit> = mutableListOf()
    private val statusListeners: MutableList<(SignalEngineStatus) -> Unit> = mutableListOf()

    var latestSignal: SymbolSignal? = null
        private set

    fun addSignalListener(listener: (SymbolSignal) -> Unit) {
        signalListeners += listener
    }

    fun addStatusListener(listener: (SignalEngineStatus) -> Unit) {
        statusListeners += listener
    }

    fun signalFor(symbol: String): SymbolSignal? = signalsBySymbol[symbol]

    fun symbols(): List<String> = signalsBySymbol.keys.sorted()

    fun signalCount(): Int = signalsBySymbol.size

    fun status(): SignalEngineStatus = SignalEngineStatus(
        symbolCount = signalsBySymbol.size,
        readySymbols = symbols(),
        latestSignal = latestSignal,
    )

    fun addFeatures(features: SymbolFeatures) {
        val score = scoreFeatures(features)
        val signal = SymbolSignal(
            symbol = features.symbol,
            bucketStart = features.bucketStart,
            state = deriveState(score),
            score = score,
            shortReturn = features.shortReturn,
            percentChange = features.percentChange,
            barRange = features.barRange,
            direction = features.direction,
        )

        signalsBySymbol[features.symbol] = signal
        latestSignal = signal

        for (listener in signalListeners) {
            listener(signal)
        }
        val statusSnapshot = status()
        for (listener in statusListeners) {
            listener(statusSnapshot)
        }
    }

    private fun deriveState(score: Int): SignalState = when {
        score >= 2 -> SignalState.BULLISH
        score <= -2 -> SignalState.BEARISH
        else -> SignalState.NEUTRAL
    }

    private fun scoreFeatures(features: SymbolFeatures): Int {
        var score = 0

        when (features.direction) {
            "up" -> score += 1
            "down" -> score -= 1
        }

        when {
            features.shortReturn > 0.001 -> score += 1
            features.shortReturn < -0.001 -> score -= 1
        }

        when {
            features.percentChange > 0.001 -> score += 1
            features.percentChange < -0.001 -> score -= 1
        }

        if (features.barRange >= 0.5) {
            when (features.direction) {
                "up" -> score += 1
                "down" -> score -= 1
            }
        }

        return score
    }
}
