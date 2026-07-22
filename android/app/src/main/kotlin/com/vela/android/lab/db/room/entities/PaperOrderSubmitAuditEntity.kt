package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only sanitized Phase 2.v submit event. Never stores credentials or headers. */
@Entity(
    tableName = "paper_order_submit_audit",
    indices = [
        Index(value = ["eventKey"], name = "ix_paper_submit_event_key", unique = true),
        Index(value = ["submitAttemptId"], name = "ix_paper_submit_attempt_id"),
        Index(value = ["previewId"], name = "ix_paper_submit_preview_id"),
        Index(value = ["clientOrderId"], name = "ix_paper_submit_client_order_id"),
        Index(
            value = ["symbol", "submittedAtEpochMillis"],
            name = "ix_paper_submit_symbol_time",
        ),
    ],
)
data class PaperOrderSubmitAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val eventKey: String,
    val submitAttemptId: String,
    val previewId: String,
    val linkedClientDryRunId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val orderType: String,
    val timeInForce: String,
    val limitPriceUsd: Double?,
    val status: String,
    val alpacaOrderId: String?,
    val submittedAtEpochMillis: Long,
    val safeErrorMessage: String?,
    val priceSource: String,
    val priceFreshness: String,
    val marketOpen: Boolean,
    val confirmationTokenId: String,
) {
    init {
        require(eventKey.isNotBlank()) { "Submit audit event key is required." }
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(previewId.isNotBlank()) { "Preview id is required." }
        require(clientOrderId.isNotBlank()) { "Client order id is required." }
        require(quantity.isFinite() && quantity > 0.0) { "Audit quantity must be valid." }
        require(confirmationTokenId.isNotBlank()) { "Confirmation token id is required." }
    }
}
