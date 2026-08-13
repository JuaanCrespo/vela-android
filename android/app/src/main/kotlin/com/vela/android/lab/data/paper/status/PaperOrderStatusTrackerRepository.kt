package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity

/** Safe reference to a previously submitted Paper order, reconstructed from local audit. */
data class TrackedPaperOrder(
    val orderId: String,
    val submitAttemptId: String,
    val previewId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val submittedAtEpochMillis: Long,
)

data class UntrackablePaperSubmission(
    val submitAttemptId: String,
    val previewId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val submittedAtEpochMillis: Long,
    val auditStatus: String,
)

sealed interface PaperOrderTrackingRestoreResult {
    data object None : PaperOrderTrackingRestoreResult
    data class Trackable(val order: TrackedPaperOrder) : PaperOrderTrackingRestoreResult
    data class Untrackable(
        val submission: UntrackablePaperSubmission,
    ) : PaperOrderTrackingRestoreResult
}

fun interface PaperOrderTrackingSource {
    suspend fun latestUnresolved(): PaperOrderTrackingRestoreResult
}

/** Read-only bridge from the append-only submit audit into lifecycle tracking. */
class PaperOrderStatusTrackerRepository(
    private val auditRepository: PaperOrderSubmitAuditRepository,
) : PaperOrderTrackingSource {
    override suspend fun latestUnresolved(): PaperOrderTrackingRestoreResult {
        val latestByAttempt = auditRepository.recent(RECENT_AUDIT_LIMIT)
            .groupBy(PaperOrderSubmitAuditEntity::submitAttemptId)
            .values
            .mapNotNull { events ->
                events.maxByOrNull(PaperOrderSubmitAuditEntity::id)
            }
            .sortedByDescending(PaperOrderSubmitAuditEntity::id)
        for (row in latestByAttempt) {
            when (row.status) {
                BLOCKED, REJECTED -> continue
                SUBMITTED -> {
                    val orderId = row.alpacaOrderId
                    if (orderId != null &&
                        AlpacaPaperOrderStatusEndpoint.isCanonicalOrderId(orderId)
                    ) {
                        return PaperOrderTrackingRestoreResult.Trackable(
                            TrackedPaperOrder(
                                orderId = orderId,
                                submitAttemptId = row.submitAttemptId,
                                previewId = row.previewId,
                                clientOrderId = row.clientOrderId,
                                symbol = row.symbol,
                                side = row.side,
                                quantity = row.quantity,
                                submittedAtEpochMillis = row.submittedAtEpochMillis,
                            ),
                        )
                    }
                    return PaperOrderTrackingRestoreResult.Untrackable(row.toUntrackable())
                }
                else -> return PaperOrderTrackingRestoreResult.Untrackable(row.toUntrackable())
            }
        }
        return PaperOrderTrackingRestoreResult.None
    }

    private fun PaperOrderSubmitAuditEntity.toUntrackable(): UntrackablePaperSubmission =
        UntrackablePaperSubmission(
            submitAttemptId = submitAttemptId,
            previewId = previewId,
            clientOrderId = clientOrderId,
            symbol = symbol,
            side = side,
            quantity = quantity,
            submittedAtEpochMillis = submittedAtEpochMillis,
            auditStatus = status,
        )

    companion object {
        private const val BLOCKED: String = "BLOCKED"
        private const val REJECTED: String = "REJECTED"
        private const val SUBMITTED: String = "SUBMITTED"
        private const val RECENT_AUDIT_LIMIT: Int = Int.MAX_VALUE
    }
}
