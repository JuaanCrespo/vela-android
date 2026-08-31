package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity

/** Durable identity projection plus append-only lifecycle evidence. */
@Dao
interface PaperOrderReconciliationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReconciliation(entity: PaperOrderReconciliationEntity): Long

    /** Idempotent legacy backfill boundary. A conflict must be read and compared by the caller. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReconciliationIfAbsent(entity: PaperOrderReconciliationEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateReconciliation(entity: PaperOrderReconciliationEntity): Int

    @Query(
        "SELECT * FROM paper_order_reconciliation " +
            "WHERE submitAttemptId = :attemptId LIMIT 1",
    )
    suspend fun reconciliationByAttemptId(attemptId: String): PaperOrderReconciliationEntity?

    /** Stable enumeration only. Ordering is never evidence of identity. */
    @Query("SELECT * FROM paper_order_reconciliation ORDER BY submitAttemptId ASC")
    suspend fun allReconciliations(): List<PaperOrderReconciliationEntity>

    @Query(
        "SELECT * FROM paper_order_reconciliation " +
            "WHERE alpacaOrderId = :orderId ORDER BY submitAttemptId ASC",
    )
    suspend fun reconciliationsByOrderId(orderId: String): List<PaperOrderReconciliationEntity>

    @Query(
        "SELECT * FROM paper_order_reconciliation " +
            "WHERE clientOrderId = :clientOrderId ORDER BY submitAttemptId ASC",
    )
    suspend fun reconciliationsByClientOrderId(
        clientOrderId: String,
    ): List<PaperOrderReconciliationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLifecycleObservation(
        observation: PaperOrderLifecycleObservationEntity,
    ): Long

    @Query(
        "SELECT * FROM paper_order_lifecycle_observation " +
            "WHERE submitAttemptId = :attemptId ORDER BY id ASC",
    )
    suspend fun lifecycleByAttemptId(
        attemptId: String,
    ): List<PaperOrderLifecycleObservationEntity>

    @Query(
        "SELECT * FROM paper_order_lifecycle_observation " +
            "WHERE submitAttemptId = :attemptId ORDER BY id DESC LIMIT 1",
    )
    suspend fun latestLifecycleByAttemptId(
        attemptId: String,
    ): PaperOrderLifecycleObservationEntity?

    @Query("SELECT COUNT(*) FROM paper_order_lifecycle_observation")
    suspend fun lifecycleObservationCount(): Int

    @Query(
        "SELECT submitAttemptId FROM paper_order_reconciliation " +
            "WHERE terminal = 1 " +
            "AND mappingStatus = 'EXACT' " +
            "AND localSubmitResult = 'SUBMITTED' " +
            "AND resetAcknowledgedAtEpochMillis IS NULL " +
            "ORDER BY submitAttemptId ASC",
    )
    suspend fun pendingTerminalResetAttemptIds(): List<String>

    @Query(
        "UPDATE paper_order_reconciliation " +
            "SET resetAcknowledgedAtEpochMillis = :acknowledgedAtEpochMillis " +
            "WHERE submitAttemptId IN (:attemptIds) " +
            "AND terminal = 1 " +
            "AND mappingStatus = 'EXACT' " +
            "AND localSubmitResult = 'SUBMITTED' " +
            "AND resetAcknowledgedAtEpochMillis IS NULL",
    )
    suspend fun acknowledgeTerminalResetsUnchecked(
        attemptIds: List<String>,
        acknowledgedAtEpochMillis: Long,
    ): Int

    /** Acknowledges the complete expected terminal set or rolls the transaction back. */
    @Transaction
    suspend fun acknowledgeTerminalResetsAtomically(
        expectedAttemptIds: List<String>,
        acknowledgedAtEpochMillis: Long,
    ) {
        require(acknowledgedAtEpochMillis >= 0L)
        val expected = expectedAttemptIds.distinct().sorted()
        require(expected.isNotEmpty() && expected.size == expectedAttemptIds.size) {
            "A unique non-empty terminal attempt set is required."
        }
        check(pendingTerminalResetAttemptIds() == expected) {
            "Terminal Paper reset identity changed before acknowledgement."
        }
        check(
            acknowledgeTerminalResetsUnchecked(expected, acknowledgedAtEpochMillis) ==
                expected.size,
        ) {
            "Terminal Paper reset acknowledgement was incomplete."
        }
    }

    /**
     * Commits immutable lifecycle evidence and its latest-known projection together. A missing or
     * mismatched identity rolls the observation insert back instead of leaving divergent state.
     */
    @Transaction
    suspend fun appendLifecycleObservation(
        observation: PaperOrderLifecycleObservationEntity,
        updatedReconciliation: PaperOrderReconciliationEntity,
    ) {
        require(observation.submitAttemptId == updatedReconciliation.submitAttemptId) {
            "Lifecycle observation attempt does not match its reconciliation projection."
        }
        require(observation.alpacaOrderId == updatedReconciliation.alpacaOrderId) {
            "Lifecycle observation order does not match its reconciliation projection."
        }
        insertLifecycleObservation(observation)
        check(updateReconciliation(updatedReconciliation) == 1) {
            "Lifecycle reconciliation identity was not updated."
        }
    }
}
