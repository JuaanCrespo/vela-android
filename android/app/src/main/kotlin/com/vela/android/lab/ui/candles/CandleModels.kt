package com.vela.android.lab.ui.candles

import com.vela.android.lab.data.market.OneMinuteBar
import java.time.Duration
import java.time.Instant

/** Direction derived only from the persisted one-minute OHLC values. */
enum class CandleDirection(val label: String) {
    BULLISH("Alcista"),
    BEARISH("Bajista"),
    DOJI("Doji"),
}

/**
 * Read-only chart projection of [OneMinuteBar].
 *
 * [volume] is explicitly nullable because the pipeline value is synthetic, not an exchange
 * volume. Invalid volume never causes otherwise coherent OHLC data to be discarded.
 */
data class CandleUiModel(
    val symbol: String,
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double?,
    val direction: CandleDirection,
) {
    val range: Double get() = high - low
    val stableId: String get() = "$symbol:${timestamp.toEpochMilli()}"
}

enum class CandleDataState {
    LOADING,
    EMPTY,
    INSUFFICIENT,
    READY,
    ERROR,
}

enum class CandleFreshness {
    UNKNOWN,
    FRESH,
    STALE,
}

data class CandlesUiState(
    val symbols: List<String> = emptyList(),
    val selectedSymbol: String? = null,
    val candleCount: Int = DEFAULT_CANDLE_COUNT,
    val candles: List<CandleUiModel> = emptyList(),
    val selectedCandle: CandleUiModel? = null,
    val dataState: CandleDataState = CandleDataState.LOADING,
    val freshness: CandleFreshness = CandleFreshness.UNKNOWN,
    val lastBarAgeMillis: Long? = null,
    val lastRefreshAt: Instant? = null,
    val rejectedBarCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isLoading: Boolean get() = dataState == CandleDataState.LOADING
    val isStale: Boolean get() = freshness == CandleFreshness.STALE

    companion object {
        const val DEFAULT_CANDLE_COUNT: Int = 50
        val CANDLE_COUNT_OPTIONS: List<Int> = listOf(30, 50, 100)
        const val SOURCE_LABEL: String = "Room local · pipeline 1m · origen no persistido"
        const val VOLUME_LABEL: String = "Volumen sintético del pipeline"
    }
}

data class CandleMappingResult(
    val candles: List<CandleUiModel>,
    val rejectedCount: Int,
    val sourceCount: Int,
)

/** Pure validation/mapping boundary; it performs no I/O and never fabricates missing OHLC. */
object CandleMapper {

    fun map(bars: List<OneMinuteBar>, limit: Int): CandleMappingResult {
        require(limit in CandlesUiState.CANDLE_COUNT_OPTIONS) {
            "Unsupported candle count: $limit"
        }
        val mapped = bars
            .mapNotNull(::toCandleOrNull)
            .sortedBy(CandleUiModel::timestamp)
            .takeLast(limit)
        return CandleMappingResult(
            candles = mapped,
            rejectedCount = bars.size - bars.count(::hasValidOhlc),
            sourceCount = bars.size,
        )
    }

    fun toCandleOrNull(bar: OneMinuteBar): CandleUiModel? {
        if (!hasValidOhlc(bar)) return null
        val volume = bar.syntheticVolume.takeIf { it.isFinite() && it >= 0.0 }
        return CandleUiModel(
            symbol = bar.symbol,
            timestamp = bar.bucketStart,
            open = bar.open,
            high = bar.high,
            low = bar.low,
            close = bar.close,
            volume = volume,
            direction = when {
                bar.close > bar.open -> CandleDirection.BULLISH
                bar.close < bar.open -> CandleDirection.BEARISH
                else -> CandleDirection.DOJI
            },
        )
    }

    fun hasValidOhlc(bar: OneMinuteBar): Boolean {
        val values = listOf(bar.open, bar.high, bar.low, bar.close)
        if (bar.symbol.isBlank() || values.any { !it.isFinite() || it <= 0.0 }) return false
        if (bar.high < bar.low) return false
        if (bar.high < maxOf(bar.open, bar.close)) return false
        if (bar.low > minOf(bar.open, bar.close)) return false
        return true
    }

    fun freshness(
        latestTimestamp: Instant?,
        now: Instant,
        staleAfter: Duration,
    ): Pair<CandleFreshness, Long?> {
        if (latestTimestamp == null) return CandleFreshness.UNKNOWN to null
        val ageMillis = Duration.between(latestTimestamp, now).toMillis()
        val freshness = if (ageMillis > staleAfter.toMillis()) {
            CandleFreshness.STALE
        } else {
            CandleFreshness.FRESH
        }
        return freshness to ageMillis
    }
}
