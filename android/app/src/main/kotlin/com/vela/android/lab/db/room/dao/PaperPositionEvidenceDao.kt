package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.*

/** Insert-only evidence, with one compare-and-set operation for the anchor status projection. */
@Dao
interface PaperPositionEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBrokerSnapshot(row: PaperBrokerSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBrokerPositionSnapshot(row: PaperBrokerPositionSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPositionAnchor(row: PaperPositionAnchorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPositionAnchorCursor(row: PaperPositionAnchorCursorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPositionAnchorEvent(row: PaperPositionAnchorEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPositionReconciliationReport(row: PaperPositionReconciliationReportEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPositionReconciliationRow(row: PaperPositionReconciliationRowEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrderDecimalEvidence(row: PaperOrderDecimalEvidenceEntity)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM paper_broker_snapshot")
    suspend fun nextSnapshotSequence(): Long

    @Query("SELECT * FROM paper_broker_snapshot WHERE snapshotId = :snapshotId")
    suspend fun snapshot(snapshotId: String): PaperBrokerSnapshotEntity?

    @Query("SELECT * FROM paper_broker_snapshot WHERE completeness = 'COMPLETE' AND (:accountRef IS NULL OR accountRef = :accountRef) ORDER BY sequence DESC LIMIT 1")
    suspend fun latestComplete(accountRef: String?): PaperBrokerSnapshotEntity?

    @Query("SELECT * FROM paper_broker_snapshot ORDER BY sequence DESC")
    suspend fun snapshotHistory(): List<PaperBrokerSnapshotEntity>

    @Query("SELECT * FROM paper_position_anchor ORDER BY createdAtEpochMillis ASC, anchorId ASC")
    suspend fun allAnchors(): List<PaperPositionAnchorEntity>

    // Snapshot sequence, not wall-clock time, orders reports from explicit captures.
    @Query("SELECT r.reportId FROM paper_position_reconciliation_report r JOIN paper_broker_snapshot s ON s.snapshotId = r.brokerSnapshotId ORDER BY s.sequence DESC, r.createdAtEpochMillis DESC, r.reportId DESC LIMIT 1")
    suspend fun latestReportId(): String?

    @Query("SELECT * FROM paper_broker_position_snapshot WHERE snapshotId = :snapshotId ORDER BY rowIndex ASC")
    suspend fun positions(snapshotId: String): List<PaperBrokerPositionSnapshotEntity>

    @Query("SELECT * FROM paper_position_anchor WHERE anchorId = :anchorId")
    suspend fun anchor(anchorId: String): PaperPositionAnchorEntity?

    @Query("SELECT * FROM paper_position_anchor WHERE symbol = :symbol AND accountRef = :accountRef AND status = 'ACTIVE'")
    suspend fun activeAnchor(symbol: String, accountRef: String): PaperPositionAnchorEntity?

    @Query("SELECT * FROM paper_position_anchor WHERE symbol = :symbol AND accountRef = :accountRef ORDER BY createdAtEpochMillis ASC, anchorId ASC")
    suspend fun anchorHistory(symbol: String, accountRef: String): List<PaperPositionAnchorEntity>

    @Query("SELECT * FROM paper_position_anchor_cursor WHERE anchorId = :anchorId ORDER BY attemptId ASC")
    suspend fun cursors(anchorId: String): List<PaperPositionAnchorCursorEntity>

    @Query("SELECT * FROM paper_position_anchor_event WHERE anchorId = :anchorId ORDER BY version ASC")
    suspend fun anchorEvents(anchorId: String): List<PaperPositionAnchorEventEntity>

    @Query("UPDATE paper_position_anchor SET status = :status, activeKey = NULL, invalidatedAtEpochMillis = :at, invalidationReason = :reason, version = version + 1 WHERE anchorId = :anchorId AND version = :previousVersion AND status = 'ACTIVE'")
    suspend fun changeAnchorStatus(anchorId: String, previousVersion: Long, status: String, at: Long, reason: String): Int

    @Query("SELECT * FROM paper_position_reconciliation_report WHERE reportId = :reportId")
    suspend fun report(reportId: String): PaperPositionReconciliationReportEntity?

    @Query("SELECT * FROM paper_position_reconciliation_row WHERE reportId = :reportId ORDER BY symbol ASC")
    suspend fun reportRows(reportId: String): List<PaperPositionReconciliationRowEntity>

    @Query("SELECT * FROM paper_order_decimal_evidence WHERE attemptId = :attemptId ORDER BY observationId ASC, field ASC")
    suspend fun decimalEvidence(attemptId: String): List<PaperOrderDecimalEvidenceEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM paper_order_lifecycle_observation")
    suspend fun lifecycleHighWater(): Long

}
