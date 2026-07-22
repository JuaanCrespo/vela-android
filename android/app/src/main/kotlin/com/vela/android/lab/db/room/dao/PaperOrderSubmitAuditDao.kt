package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity

/** Append-only submit audit: insert and reads only. */
@Dao
interface PaperOrderSubmitAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: PaperOrderSubmitAuditEntity): Long

    @Query("SELECT COUNT(*) FROM paper_order_submit_audit")
    suspend fun countAll(): Int

    @Query(
        "SELECT * FROM paper_order_submit_audit " +
            "ORDER BY submittedAtEpochMillis DESC, id DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<PaperOrderSubmitAuditEntity>

    @Query(
        "SELECT * FROM paper_order_submit_audit WHERE symbol = :symbol " +
            "ORDER BY submittedAtEpochMillis DESC, id DESC LIMIT :limit",
    )
    suspend fun recentBySymbol(symbol: String, limit: Int): List<PaperOrderSubmitAuditEntity>

    @Query(
        "SELECT * FROM paper_order_submit_audit WHERE submitAttemptId = :attemptId " +
            "ORDER BY id ASC",
    )
    suspend fun byAttemptId(attemptId: String): List<PaperOrderSubmitAuditEntity>

    @Query("SELECT COUNT(*) FROM paper_order_submit_audit WHERE previewId = :previewId")
    suspend fun countByPreviewId(previewId: String): Int

    @Query("SELECT COUNT(*) FROM paper_order_submit_audit WHERE clientOrderId = :clientOrderId")
    suspend fun countByClientOrderId(clientOrderId: String): Int
}
