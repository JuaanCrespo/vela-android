package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity

/**
 * Phase 2.n DAO for the read-mostly `paper_order_dry_run_audits`
 * table. **Insert is the only mutating method.** There is no
 * `update`, `delete`, or `clear` — the audit trail is append-only
 * by design.
 */
@Dao
interface PaperOrderDryRunAuditDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(audit: PaperOrderDryRunAuditEntity): Long

    @Query("SELECT COUNT(*) FROM paper_order_dry_run_audits")
    suspend fun countAll(): Int

    @Query(
        "SELECT * FROM paper_order_dry_run_audits " +
            "ORDER BY createdAtEpochMillis DESC " +
            "LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<PaperOrderDryRunAuditEntity>

    @Query(
        "SELECT * FROM paper_order_dry_run_audits " +
            "WHERE symbol = :symbol " +
            "ORDER BY createdAtEpochMillis DESC " +
            "LIMIT :limit",
    )
    suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderDryRunAuditEntity>
}
