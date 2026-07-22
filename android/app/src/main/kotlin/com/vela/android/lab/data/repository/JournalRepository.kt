package com.vela.android.lab.data.repository

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.db.journalEvent
import com.vela.android.lab.db.room.dao.JournalDao
import com.vela.android.lab.db.room.entities.JournalEventEntity
import java.time.Instant

/**
 * Generic journal repository. Phase 1.c only needs to insert and
 * query events keyed by symbol, type, and timestamp range. Concrete
 * event payload schemas land later (paper orders, auto-paper
 * decisions, risk decisions, etc.).
 */
class JournalRepository(private val dao: JournalDao) {

    suspend fun record(
        eventType: String,
        timestamp: Instant,
        symbol: String? = null,
        payloadJson: String? = null,
    ): Long = dao.insert(
        journalEvent(
            eventType = eventType,
            timestamp = timestamp,
            symbol = symbol,
            payloadJson = payloadJson,
        ),
    )

    suspend fun forSymbol(symbol: String): List<JournalEventEntity> {
        val normalized = normalizeMarketSymbol(symbol)
        if (normalized.isEmpty()) return emptyList()
        return dao.bySymbol(normalized)
    }

    suspend fun byType(eventType: String, limit: Int): List<JournalEventEntity> {
        if (eventType.isBlank() || limit <= 0) return emptyList()
        return dao.byType(eventType, limit)
    }

    suspend fun inRange(start: Instant, end: Instant): List<JournalEventEntity> {
        val startMillis = start.toEpochMilli()
        val endMillis = end.toEpochMilli()
        if (endMillis < startMillis) return emptyList()
        return dao.inRange(startMillis, endMillis)
    }

    suspend fun count(): Int = dao.countAll()

    suspend fun clear() = dao.clear()
}
