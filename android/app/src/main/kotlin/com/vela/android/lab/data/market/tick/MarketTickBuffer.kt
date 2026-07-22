package com.vela.android.lab.data.market.tick

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 2.i bounded in-memory tick buffer with per-symbol summary
 * statistics. Pure-Kotlin; thread-safe via a single intrinsic lock.
 *
 * - Per-symbol ring buffer of up to [perSymbolCap] most-recent ticks.
 * - Aggregate hard cap of [totalCap] across all symbols so a noisy
 *   market cannot grow unbounded memory.
 * - Maintains a per-symbol summary (`PerSymbolTickStats`) so the UI
 *   only re-renders the small summary, not the whole ring.
 * - Tracks aggregate counts: total quotes, total bars, dropped /
 *   overwritten count when a buffer overflowed, and the last parser
 *   error string.
 *
 * No method on this class submits orders, mutates account state, or
 * performs any trading action.
 */
class MarketTickBuffer(
    private val perSymbolCap: Int = DEFAULT_PER_SYMBOL_CAP,
    private val totalCap: Int = DEFAULT_TOTAL_CAP,
) {

    private val lock: Any = Any()
    private val perSymbol: MutableMap<String, ArrayDeque<MarketTick>> = mutableMapOf()
    private val perSymbolPrior: MutableMap<String, MarketTick> = mutableMapOf()
    private val perSymbolBarCount: MutableMap<String, Int> = mutableMapOf()

    private val _snapshot: MutableStateFlow<TickBufferSnapshot> =
        MutableStateFlow(TickBufferSnapshot.Initial)

    val snapshot: StateFlow<TickBufferSnapshot> = _snapshot.asStateFlow()

    /**
     * Push a parsed quote tick. Maintains the per-symbol deque,
     * enforces caps, advances summary counters, and emits a new
     * snapshot atomically.
     */
    fun pushQuote(tick: MarketTick) {
        val newSnapshot = synchronized(lock) {
            val symbolBuf = perSymbol.getOrPut(tick.symbol) { ArrayDeque() }
            val prior = perSymbolPrior[tick.symbol]
            symbolBuf.addLast(tick)
            var dropped = 0
            while (symbolBuf.size > perSymbolCap) {
                symbolBuf.removeFirst()
                dropped += 1
            }
            // Aggregate cap: drop oldest from the largest deque(s)
            // until total <= totalCap.
            while (perSymbol.values.sumOf { it.size } > totalCap) {
                val biggest = perSymbol.values.maxByOrNull { it.size } ?: break
                if (biggest.isEmpty()) break
                biggest.removeFirst()
                dropped += 1
            }
            perSymbolPrior[tick.symbol] = tick
            buildSnapshot(
                addQuotes = 1,
                addBars = 0,
                addDropped = dropped,
                priorErrorMessage = _snapshot.value.lastParserError,
                updatedSymbol = tick.symbol,
                priorSymbolTick = prior,
            )
        }
        _snapshot.value = newSnapshot
    }

    /**
     * Record that a 1-minute bar was observed for [symbol]. Bars are
     * already routed through the Phase 1.e pipeline coordinator; the
     * tick buffer only counts them here so the UI can show "bars
     * received" alongside the quote stats.
     */
    fun recordBar(symbol: String) {
        val newSnapshot = synchronized(lock) {
            perSymbolBarCount[symbol] = (perSymbolBarCount[symbol] ?: 0) + 1
            buildSnapshot(
                addQuotes = 0,
                addBars = 1,
                addDropped = 0,
                priorErrorMessage = _snapshot.value.lastParserError,
                updatedSymbol = symbol,
                priorSymbolTick = perSymbolPrior[symbol],
            )
        }
        _snapshot.value = newSnapshot
    }

    fun recordParserError(message: String) {
        _snapshot.value = synchronized(lock) {
            _snapshot.value.copy(lastParserError = message)
        }
    }

    /** Clear the buffer + counters. Used by tests and by an optional UI reset. */
    fun clear() {
        synchronized(lock) {
            perSymbol.clear()
            perSymbolPrior.clear()
            perSymbolBarCount.clear()
            _snapshot.value = TickBufferSnapshot.Initial
        }
    }

    private fun buildSnapshot(
        addQuotes: Int,
        addBars: Int,
        addDropped: Int,
        priorErrorMessage: String?,
        updatedSymbol: String?,
        priorSymbolTick: MarketTick?,
    ): TickBufferSnapshot {
        val current = _snapshot.value
        val perSymbolStats: MutableMap<String, PerSymbolTickStats> =
            current.perSymbol.toMutableMap()
        if (updatedSymbol != null) {
            val deque = perSymbol[updatedSymbol]
            val barsForSymbol = perSymbolBarCount[updatedSymbol] ?: 0
            val latest = deque?.lastOrNull()
            if (latest != null) {
                val interMsg: Long? = if (priorSymbolTick != null) {
                    latest.receivedAtMillis - priorSymbolTick.receivedAtMillis
                } else {
                    null
                }
                val priorStats = perSymbolStats[updatedSymbol]
                perSymbolStats[updatedSymbol] = PerSymbolTickStats(
                    lastBid = latest.bidPrice,
                    lastAsk = latest.askPrice,
                    spread = latest.spread,
                    lastQuoteTimestampMillis = latest.marketTimestampMillis,
                    lastReceivedAtMillis = latest.receivedAtMillis,
                    lastLatencyMillis = latest.latencyMillis,
                    lastInterMessageMillis = interMsg,
                    quotesReceived = (priorStats?.quotesReceived ?: 0) + addQuotes,
                    barsReceived = barsForSymbol,
                )
            } else {
                // No quote yet (this was a bar-only record). Reuse prior
                // stats, just refresh barsReceived.
                val priorStats = perSymbolStats[updatedSymbol]
                if (priorStats != null) {
                    perSymbolStats[updatedSymbol] = priorStats.copy(barsReceived = barsForSymbol)
                } else {
                    perSymbolStats[updatedSymbol] = PerSymbolTickStats(
                        lastBid = 0.0,
                        lastAsk = 0.0,
                        spread = 0.0,
                        lastQuoteTimestampMillis = 0L,
                        lastReceivedAtMillis = 0L,
                        lastLatencyMillis = 0L,
                        lastInterMessageMillis = null,
                        quotesReceived = 0,
                        barsReceived = barsForSymbol,
                    )
                }
            }
        }
        return current.copy(
            perSymbol = perSymbolStats,
            totalQuotes = current.totalQuotes + addQuotes,
            totalBars = current.totalBars + addBars,
            droppedOverflow = current.droppedOverflow + addDropped,
            bufferSize = perSymbol.values.sumOf { it.size },
            lastParserError = priorErrorMessage,
        )
    }

    companion object {
        const val DEFAULT_PER_SYMBOL_CAP: Int = 100
        const val DEFAULT_TOTAL_CAP: Int = 1_000
    }
}

/**
 * Aggregate + per-symbol read-only snapshot rendered by the
 * dashboard's tick-diagnostics section.
 */
data class TickBufferSnapshot(
    val perSymbol: Map<String, PerSymbolTickStats>,
    val totalQuotes: Int,
    val totalBars: Int,
    val droppedOverflow: Int,
    val bufferSize: Int,
    val lastParserError: String?,
) {
    companion object {
        val Initial: TickBufferSnapshot = TickBufferSnapshot(
            perSymbol = emptyMap(),
            totalQuotes = 0,
            totalBars = 0,
            droppedOverflow = 0,
            bufferSize = 0,
            lastParserError = null,
        )
    }
}

/**
 * Per-symbol summary kept in the snapshot. Lightweight — re-render
 * cost is tiny so the UI can collect on every push without lag.
 */
data class PerSymbolTickStats(
    val lastBid: Double,
    val lastAsk: Double,
    val spread: Double,
    val lastQuoteTimestampMillis: Long,
    val lastReceivedAtMillis: Long,
    val lastLatencyMillis: Long,
    val lastInterMessageMillis: Long?,
    val quotesReceived: Int,
    val barsReceived: Int,
)
