package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Generic journal event row. Modeled loosely on the Windows
 * `simulation_journal_events` table from `app/db/models.py`, but kept
 * minimal for Phase 1.c — we only need a place to attach
 * arbitrary, type-tagged JSON payloads tied to a (symbol, timestamp).
 *
 * Concrete event types this table will later host include
 * `position_snapshot`, `paper_order`, `auto_paper_decision`, and
 * `risk_decision`. Their concrete schemas land when those features
 * land; for Phase 1.c we just need the column shape and indexes.
 */
@Entity(
    tableName = "journal_events",
    indices = [
        Index(
            value = ["timestampEpochMillis"],
            name = "ix_journal_events_timestamp",
        ),
        Index(
            value = ["symbol", "timestampEpochMillis"],
            name = "ix_journal_events_symbol_timestamp",
        ),
        Index(
            value = ["eventType"],
            name = "ix_journal_events_event_type",
        ),
    ],
)
data class JournalEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String?,
    val eventType: String,
    val timestampEpochMillis: Long,
    val payloadJson: String?,
)
