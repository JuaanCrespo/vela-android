package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One append-only, sanitized lifecycle observation for a durably identified Paper order.
 * No response body, credentials, request headers, or authorization material is stored.
 */
@Entity(
    tableName = "paper_order_lifecycle_observation",
    foreignKeys = [
        ForeignKey(
            entity = PaperOrderReconciliationEntity::class,
            parentColumns = ["submitAttemptId"],
            childColumns = ["submitAttemptId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["observationKey"],
            name = "ix_paper_lifecycle_observation_key",
            unique = true,
        ),
        Index(
            value = ["submitAttemptId", "id"],
            name = "ix_paper_lifecycle_attempt_id",
        ),
        Index(
            value = ["alpacaOrderId", "id"],
            name = "ix_paper_lifecycle_order_id",
        ),
        Index(value = ["status"], name = "ix_paper_lifecycle_status"),
        Index(
            value = ["observedAtEpochMillis"],
            name = "ix_paper_lifecycle_observed_at",
        ),
        Index(
            value = ["submitAuditEntryId"],
            name = "ix_paper_lifecycle_submit_audit_id",
        ),
    ],
)
data class PaperOrderLifecycleObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val observationKey: String,
    val submitAttemptId: String,
    val alpacaOrderId: String,
    val status: String,
    val rawStatus: String,
    val observedAtEpochMillis: Long,
    val terminal: Boolean,
    val filledQuantity: Double?,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
    val source: String,
    val httpStatusCode: Int?,
    val submitAuditEntryId: Long?,
) {
    init {
        require(observationKey.isNotBlank()) { "Lifecycle observation key is required." }
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(alpacaOrderId.isNotBlank()) { "Alpaca Paper order id is required." }
        require(status.isNotBlank()) { "Paper lifecycle status is required." }
        require(rawStatus.isNotBlank()) { "Paper lifecycle raw status is required." }
        require(observedAtEpochMillis >= 0L) {
            "Paper lifecycle observation timestamp must be non-negative."
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
        require(source.isNotBlank()) { "Paper lifecycle evidence source is required." }
        require(httpStatusCode == null || httpStatusCode in 100..599) {
            "Paper lifecycle HTTP status must be valid when present."
        }
        require(submitAuditEntryId == null || submitAuditEntryId > 0L) {
            "Submit audit identity must be positive when present."
        }
    }
}
