package com.vela.android.lab.data.repository

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.db.room.dao.FeatureDao
import com.vela.android.lab.db.toDomain
import com.vela.android.lab.db.toEntity

class FeatureRepository(private val dao: FeatureDao) {

    suspend fun persist(features: SymbolFeatures): Long = dao.insert(features.toEntity())

    suspend fun persistAll(features: List<SymbolFeatures>): List<Long> =
        dao.insertAll(features.map { it.toEntity() })

    suspend fun forSymbol(symbol: String): List<SymbolFeatures> {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return emptyList()
        return dao.bySymbol(normalized).map { it.toDomain() }
    }

    suspend fun latestFor(symbol: String): SymbolFeatures? {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return null
        return dao.latestFor(normalized)?.toDomain()
    }

    suspend fun count(symbol: String): Int {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return 0
        return dao.countBySymbol(normalized)
    }

    suspend fun clear() = dao.clear()
}
