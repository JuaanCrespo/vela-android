package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable, credential-free identity and latest-known lifecycle projection for one Paper submit
 * attempt. Nullable identity fields deliberately preserve legacy ambiguity without inventing
 * missing values; [mappingStatus] and [mappingDiagnostic] explain whether the relation is exact.
 *
 * Lifecycle observations remain append-only in [PaperOrderLifecycleObservationEntity]. This row
 * is only the transactionally maintained projection used to restore the foreground UI quickly.
 */
@Entity(
    tableName = "paper_order_reconciliation",
    indices = [
        Index(value = ["alpacaOrderId"], name = "ix_paper_reconciliation_order_id"),
        Index(value = ["clientOrderId"], name = "ix_paper_reconciliation_client_order_id"),
        Index(value = ["mappingStatus"], name = "ix_paper_reconciliation_mapping_status"),
        Index(
            value = ["terminal", "resetAcknowledgedAtEpochMillis"],
            name = "ix_paper_reconciliation_terminal_reset",
        ),
        Index(
            value = ["submittedAtEpochMillis"],
            name = "ix_paper_reconciliation_submitted_at",
        ),
    ],
)
data class PaperOrderReconciliationEntity(
    @PrimaryKey val submitAttemptId: String,
    val attemptStartedAuditEntryId: Long?,
    val submitResultAuditEntryId: Long?,
    val previewId: String?,
    val linkedClientDryRunId: String?,
    val alpacaOrderId: String?,
    val clientOrderId: String?,
    val symbol: String?,
    val side: String?,
    val quantity: Double?,
    val orderType: String?,
    val timeInForce: String?,
    val limitPriceUsd: Double?,
    val submittedAtEpochMillis: Long?,
    val localSubmitResult: String?,
    val mappingStatus: String,
    val mappingDiagnostic: String?,
    val latestLifecycleStatus: String?,
    val latestLifecycleRawStatus: String?,
    val latestLifecycleObservedAtEpochMillis: Long?,
    val terminal: Boolean,
    val filledQuantity: Double?,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
    val lifecycleSource: String?,
    val lifecycleHttpStatusCode: Int?,
    val resetAcknowledgedAtEpochMillis: Long?,
) {
    init {
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(mappingStatus.isNotBlank()) { "Paper reconciliation mapping status is required." }
        require(attemptStartedAuditEntryId == null || attemptStartedAuditEntryId > 0L) {
            "Attempt-start audit identity must be positive when present."
        }
        require(submitResultAuditEntryId == null || submitResultAuditEntryId > 0L) {
            "Submit-result audit identity must be positive when present."
        }
        require(quantity == null || quantity.isFinite() && quantity > 0.0) {
            "Paper reconciliation quantity must be positive and finite when present."
        }
        require(limitPriceUsd == null || limitPriceUsd.isFinite() && limitPriceUsd > 0.0) {
            "Paper reconciliation limit price must be positive and finite when present."
        }
        require(submittedAtEpochMillis == null || submittedAtEpochMillis >= 0L) {
            "Paper reconciliation submit timestamp must be non-negative when present."
        }
        require(
            latestLifecycleObservedAtEpochMillis == null ||
                latestLifecycleObservedAtEpochMillis >= 0L,
        ) {
            "Paper lifecycle observation timestamp must be non-negative when present."
        }
        require(filledQuantity == null || filledQuantity.isFinite() && filledQuantity >= 0.0) {
            "Paper lifecycle filled quantity must be finite and non-negative when present."
        }
        require(
            filledAveragePriceUsd == null ||
                filledAveragePriceUsd.isFinite() && filledAveragePriceUsd > 0.0,
        ) {
            "Paper lifecycle average fill price must be positive and finite when present."
        }
        require(lifecycleHttpStatusCode == null || lifecycleHttpStatusCode in 100..599) {
            "Paper lifecycle HTTP status must be valid when present."
        }
        require(
            resetAcknowledgedAtEpochMillis == null || resetAcknowledgedAtEpochMillis >= 0L,
        ) {
            "Paper reset acknowledgement timestamp must be non-negative when present."
        }
    }
}
