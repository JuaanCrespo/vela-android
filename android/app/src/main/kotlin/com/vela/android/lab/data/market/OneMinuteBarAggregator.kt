package com.vela.android.lab.data.market

import com.vela.android.lab.core.normalizeMarketSymbol
import java.time.temporal.ChronoUnit

/**
 * Port of `OneMinuteBarAggregator` from `app/data/bar_aggregator.py`.
 *
 * Pure Kotlin, no Android dependency. The Qt `Signal` emissions are
 * replaced with a minimal listener API (`addBarListener`,
 * `addStatusListener`). The migration map wraps this in `SharedFlow`
 * in a later iteration; that wrapper is not part of Phase 1.b.
 *
 * Behavioral parity with the Windows project is the goal:
 *  - 1-minute bucketing on UTC instants (`truncatedTo(MINUTES)`)
 *  - bar merge keeps open price, max(high), min(low), latest close
 *  - `syntheticVolume` replaces with the update's volume when supplied,
 *    otherwise increments by 1.0
 *  - bounded per-symbol history evicting oldest first
 *  - symbol normalization via [normalizeMarketSymbol]
 */
class OneMinuteBarAggregator(
    val maxBarsPerSymbol: Int = 128,
) {
    private val barsBySymbol: MutableMap<String, ArrayDeque<OneMinuteBar>> = mutableMapOf()
    private val barListeners: MutableList<(OneMinuteBar) -> Unit> = mutableListOf()
    private val statusListeners: MutableList<(BarAggregatorStatus) -> Unit> = mutableListOf()

    var latestBar: OneMinuteBar? = null
        private set

    init {
        require(maxBarsPerSymbol > 0) { "maxBarsPerSymbol must be positive." }
    }

    fun addBarListener(listener: (OneMinuteBar) -> Unit) {
        barListeners += listener
    }

    fun addStatusListener(listener: (BarAggregatorStatus) -> Unit) {
        statusListeners += listener
    }

    fun currentBar(symbol: String): OneMinuteBar? =
        barsBySymbol[normalizeMarketSymbol(symbol)]?.lastOrNull()

    fun recentBars(symbol: String, limit: Int? = null): List<OneMinuteBar> {
        val history = barsBySymbol[normalizeMarketSymbol(symbol)] ?: return emptyList()
        if (limit == null || limit >= history.size) {
            return history.toList()
        }
        if (limit <= 0) return emptyList()
        return history.toList().takeLast(limit)
    }

    fun barCount(symbol: String? = null): Int {
        if (symbol == null) {
            return barsBySymbol.values.sumOf { it.size }
        }
        return barsBySymbol[normalizeMarketSymbol(symbol)]?.size ?: 0
    }

    fun symbols(): List<String> = barsBySymbol.keys.sorted()

    fun status(): BarAggregatorStatus {
        val counts: List<Pair<String, Int>> = symbols().map { symbol ->
            symbol to (barsBySymbol[symbol]?.size ?: 0)
        }
        return BarAggregatorStatus(
            totalBars = counts.sumOf { it.second },
            symbolCount = counts.size,
            maxBarsPerSymbol = maxBarsPerSymbol,
            countsBySymbol = counts,
            latestBar = latestBar,
        )
    }

    fun addUpdate(update: BootstrapMarketUpdate) {
        val symbol = normalizeMarketSymbol(update.symbol)
        val history = barsBySymbol.getOrPut(symbol) { ArrayDeque() }
        val updateTimestamp = update.timestamp
        val bucketStart = updateTimestamp.truncatedTo(ChronoUnit.MINUTES)
        val currentBar = history.lastOrNull()
        val openPrice = update.open ?: update.price
        val highPrice = update.high ?: update.price
        val lowPrice = update.low ?: update.price
        val closePrice = update.close ?: update.price
        val updateVolume = update.volume ?: 1.0

        val newBar: OneMinuteBar = if (currentBar != null && currentBar.bucketStart == bucketStart) {
            val merged = OneMinuteBar(
                symbol = currentBar.symbol,
                bucketStart = currentBar.bucketStart,
                open = currentBar.open,
                high = maxOf(currentBar.high, highPrice),
                low = minOf(currentBar.low, lowPrice),
                close = closePrice,
                updateCount = currentBar.updateCount + 1,
                syntheticVolume = if (update.volume != null) {
                    updateVolume
                } else {
                    currentBar.syntheticVolume + 1.0
                },
                lastUpdateTime = updateTimestamp,
            )
            history[history.size - 1] = merged
            merged
        } else {
            val fresh = OneMinuteBar(
                symbol = symbol,
                bucketStart = bucketStart,
                open = openPrice,
                high = highPrice,
                low = lowPrice,
                close = closePrice,
                updateCount = 1,
                syntheticVolume = updateVolume,
                lastUpdateTime = updateTimestamp,
            )
            history.addLast(fresh)
            while (history.size > maxBarsPerSymbol) {
                history.removeFirst()
            }
            fresh
        }

        latestBar = newBar
        for (listener in barListeners) {
            listener(newBar)
        }
        val statusSnapshot = status()
        for (listener in statusListeners) {
            listener(statusSnapshot)
        }
    }
}
