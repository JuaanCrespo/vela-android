package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity

/** Read-only queries for canonical Paper history. No mutation method belongs in this DAO. */
@Dao
interface PaperOrderHistoryDao {
    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit " +
            "GROUP BY submitAttemptId ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun allAuditAttemptIds(): List<String>

    @Query("SELECT submitAttemptId FROM paper_order_reconciliation ORDER BY submitAttemptId ASC")
    suspend fun allReconciliationAttemptIds(): List<String>

    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit " +
            "WHERE alpacaOrderId = :orderId GROUP BY submitAttemptId " +
            "ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun attemptIdsByOrderId(orderId: String): List<String>

    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit " +
            "WHERE clientOrderId = :clientOrderId GROUP BY submitAttemptId " +
            "ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun attemptIdsByClientOrderId(clientOrderId: String): List<String>

    @Query("SELECT * FROM paper_order_submit_audit WHERE id = :auditRowId LIMIT 1")
    suspend fun auditById(auditRowId: Long): PaperOrderSubmitAuditEntity?

    @Query(
        "SELECT * FROM paper_order_submit_audit WHERE submitAttemptId = :attemptId " +
            "ORDER BY id ASC",
    )
    suspend fun auditsByAttemptId(attemptId: String): List<PaperOrderSubmitAuditEntity>

    @Query(
        "SELECT * FROM paper_order_reconciliation " +
            "WHERE submitAttemptId = :attemptId LIMIT 1",
    )
    suspend fun reconciliationByAttemptId(attemptId: String): PaperOrderReconciliationEntity?

    @Query(
        "SELECT * FROM paper_order_dry_run_audits " +
            "WHERE clientDryRunId = :clientDryRunId LIMIT 1",
    )
    suspend fun dryRunByClientId(clientDryRunId: String): PaperOrderDryRunAuditEntity?

    @Query(
        "SELECT * FROM paper_order_lifecycle_observation " +
            "WHERE submitAttemptId = :attemptId ORDER BY id ASC",
    )
    suspend fun lifecycleByAttemptId(attemptId: String): List<PaperOrderLifecycleObservationEntity>

    @Query(
        "SELECT submitAttemptId FROM paper_order_lifecycle_observation WHERE terminal = 1 " +
            "GROUP BY submitAttemptId ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun terminalCandidateAttemptIds(): List<String>

    @Query(
        "SELECT submitAttemptId FROM paper_order_lifecycle_observation WHERE status = 'FILLED' " +
            "GROUP BY submitAttemptId ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun filledCandidateAttemptIds(): List<String>

    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit WHERE symbol = :symbol " +
            "GROUP BY submitAttemptId ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun attemptIdsBySymbol(symbol: String): List<String>

    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit WHERE side = :side " +
            "GROUP BY submitAttemptId ORDER BY MIN(id) ASC, submitAttemptId ASC",
    )
    suspend fun attemptIdsBySide(side: String): List<String>

    /** Range is based on the local submit-result timestamp: [startInclusive, endExclusive). */
    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit " +
            "WHERE status != 'ATTEMPT_STARTED' " +
            "AND submittedAtEpochMillis >= :startInclusive " +
            "AND submittedAtEpochMillis < :endExclusive " +
            "GROUP BY submitAttemptId ORDER BY MAX(id) ASC, submitAttemptId ASC",
    )
    suspend fun attemptIdsWithinSubmitResultTimeRange(
        startInclusive: Long,
        endExclusive: Long,
    ): List<String>

    /** Newest submit ingestion first; wall-clock values never decide this ordering. */
    @Query(
        "SELECT submitAttemptId FROM paper_order_submit_audit GROUP BY submitAttemptId " +
            "ORDER BY MAX(CASE WHEN status != 'ATTEMPT_STARTED' THEN id ELSE 0 END) DESC, " +
            "MIN(id) DESC, submitAttemptId DESC LIMIT :limit",
    )
    suspend fun latestAttemptIds(limit: Int): List<String>
}
