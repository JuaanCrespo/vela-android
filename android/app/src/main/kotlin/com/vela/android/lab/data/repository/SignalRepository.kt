package com.vela.android.lab.data.repository

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.SignalState
import com.vela.android.lab.data.market.SymbolSignal
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.toDomain
import com.vela.android.lab.db.toEntity

class SignalRepository(private val dao: SignalDao) {

    suspend fun persist(signal: SymbolSignal): Long = dao.insert(signal.toEntity())

    suspend fun persistAll(signals: List<SymbolSignal>): List<Long> =
        dao.insertAll(signals.map { it.toEntity() })

    suspend fun forSymbol(symbol: String): List<SymbolSignal> {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return emptyList()
        return dao.bySymbol(normalized).map { it.toDomain() }
    }

    suspend fun latestFor(symbol: String): SymbolSignal? {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return null
        return dao.latestFor(normalized)?.toDomain()
    }

    suspend fun byState(state: SignalState, limit: Int): List<SymbolSignal> {
        if (limit <= 0) return emptyList()
        return dao.byState(state.value, limit).map { it.toDomain() }
    }

    suspend fun clear() = dao.clear()
}
