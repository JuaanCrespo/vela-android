package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity

/** Phase 2.q append-only review queue: insert plus reads only. */
@Dao
interface PaperOrderPayloadPreviewDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(preview: PaperOrderPayloadPreviewEntity): Long

    @Query("SELECT COUNT(*) FROM paper_order_payload_previews")
    suspend fun countAll(): Int

    @Query(
        "SELECT * FROM paper_order_payload_previews " +
            "ORDER BY createdAtEpochMillis DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<PaperOrderPayloadPreviewEntity>

    @Query(
        "SELECT * FROM paper_order_payload_previews WHERE symbol = :symbol " +
            "ORDER BY createdAtEpochMillis DESC LIMIT :limit",
    )
    suspend fun recentBySymbol(
        symbol: String,
        limit: Int,
    ): List<PaperOrderPayloadPreviewEntity>

    @Query(
        "SELECT * FROM paper_order_payload_previews WHERE previewId = :previewId LIMIT 1",
    )
    suspend fun byPreviewId(previewId: String): PaperOrderPayloadPreviewEntity?
}
