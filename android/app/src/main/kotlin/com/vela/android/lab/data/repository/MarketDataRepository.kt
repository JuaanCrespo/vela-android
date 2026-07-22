package com.vela.android.lab.data.repository

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.toDomain
import com.vela.android.lab.db.toEntity

/**
 * Domain-facing wrapper around [MarketBarDao]. Normalizes input
 * symbols at the boundary so callers can query with `BTC/USD`,
 * `BTCUSD`, or `btcusd` and get the same canonical-keyed rows back.
 *
 * No Android dependency in this class — only the DAO interface, which
 * has fakes for JVM unit tests and a Room-generated implementation
 * for production / instrumented tests.
 */
class MarketDataRepository(private val dao: MarketBarDao) {

    suspend fun persistBar(bar: OneMinuteBar): Long = dao.insert(bar.toEntity())

    suspend fun persistBars(bars: List<OneMinuteBar>): List<Long> =
        dao.insertAll(bars.map { it.toEntity() })

    /**
     * Chronological list (oldest first) of all stored bars for the
     * symbol, mirroring the in-memory aggregator's iteration order.
     */
    suspend fun bars(symbol: String): List<OneMinuteBar> {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return emptyList()
        return dao.bySymbol(normalized).map { it.toDomain() }
    }

    /**
     * Up to [limit] most-recent bars, returned in chronological order
     * (oldest first) to match the aggregator's `recentBars` contract.
     */
    suspend fun recentBars(symbol: String, limit: Int): List<OneMinuteBar> {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty() || limit <= 0) return emptyList()
        return dao.recent(normalized, limit)
            .map { it.toDomain() }
            .reversed()
    }

    suspend fun count(symbol: String): Int {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return 0
        return dao.countBySymbol(normalized)
    }

    suspend fun countAll(): Int = dao.countAll()

    suspend fun clear(symbol: String) {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return
        dao.deleteBySymbol(normalized)
    }

    suspend fun clearAll() = dao.clear()
}
