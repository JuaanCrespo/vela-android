package com.vela.android.lab.data.market

/**
 * Port of `FeatureEngine` from `app/data/feature_engine.py`.
 *
 * Pure Kotlin. The Qt `Signal` emissions are replaced with the same
 * minimal listener pattern used in [OneMinuteBarAggregator]. The
 * scoring math, direction labels, and edge cases all mirror the
 * Python implementation.
 *
 *  - `previousClose` is the close of the second-to-last recent bar
 *    when ≥ 2 are available; otherwise it falls back to the current
 *    bar's close (which makes `shortReturn` collapse to zero).
 *  - `direction` is one of "up", "down", "flat" — kept as raw strings
 *    to stay byte-equivalent to the Python journal entries.
 */
class FeatureEngine(
    val barAggregator: OneMinuteBarAggregator,
    val recentBarLimit: Int = 20,
) {
    private val featuresBySymbol: MutableMap<String, SymbolFeatures> = mutableMapOf()
    private val featureListeners: MutableList<(SymbolFeatures) -> Unit> = mutableListOf()
    private val statusListeners: MutableList<(FeatureEngineStatus) -> Unit> = mutableListOf()

    var latestFeatures: SymbolFeatures? = null
        private set

    init {
        require(recentBarLimit > 0) { "recentBarLimit must be positive." }
    }

    fun addFeatureListener(listener: (SymbolFeatures) -> Unit) {
        featureListeners += listener
    }

    fun addStatusListener(listener: (FeatureEngineStatus) -> Unit) {
        statusListeners += listener
    }

    fun featuresFor(symbol: String): SymbolFeatures? = featuresBySymbol[symbol]

    fun symbols(): List<String> = featuresBySymbol.keys.sorted()

    fun featureCount(): Int = featuresBySymbol.size

    fun status(): FeatureEngineStatus = FeatureEngineStatus(
        symbolCount = featuresBySymbol.size,
        readySymbols = symbols(),
        latestFeatures = latestFeatures,
    )

    fun addBar(bar: OneMinuteBar) {
        val recentBars = barAggregator.recentBars(bar.symbol, limit = recentBarLimit)
        val previousClose: Double = if (recentBars.size >= 2) {
            recentBars[recentBars.size - 2].close
        } else {
            bar.close
        }

        val shortReturn: Double = if (previousClose != 0.0) {
            (bar.close - previousClose) / previousClose
        } else {
            0.0
        }

        val percentChange: Double = if (bar.open != 0.0) {
            (bar.close - bar.open) / bar.open
        } else {
            0.0
        }

        val direction: String = when {
            bar.close > bar.open -> "up"
            bar.close < bar.open -> "down"
            else -> "flat"
        }

        val features = SymbolFeatures(
            symbol = bar.symbol,
            bucketStart = bar.bucketStart,
            shortReturn = shortReturn,
            percentChange = percentChange,
            barRange = bar.high - bar.low,
            direction = direction,
            recentBarCount = recentBars.size,
        )

        featuresBySymbol[bar.symbol] = features
        latestFeatures = features

        for (listener in featureListeners) {
            listener(features)
        }
        val statusSnapshot = status()
        for (listener in statusListeners) {
            listener(statusSnapshot)
        }
    }
}
