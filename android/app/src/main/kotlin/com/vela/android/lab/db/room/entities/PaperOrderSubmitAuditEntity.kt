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
        Index(value = ["alpacaOrderId"], name = "ix_paper_submit_alpaca_order_id"),
        Index(value = ["submittedAtEpochMillis"], name = "ix_paper_submit_time"),
        Index(
            value = ["side", "submittedAtEpochMillis"],
            name = "ix_paper_submit_side_time",
        ),
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
    /** Sanitized transport evidence. Null for legacy, local-block, or no-response events. */
    val submitHttpStatusCode: Int? = null,
    /** Initial broker lifecycle value from the submit response, when Alpaca supplied one. */
    val initialAlpacaStatus: String? = null,
    /** Alpaca `submitted_at` from the submit response, preserved verbatim when valid. */
    val alpacaSubmittedAtIso: String? = null,
) {
    init {
        require(eventKey.isNotBlank()) { "Submit audit event key is required." }
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(previewId.isNotBlank()) { "Preview id is required." }
        require(clientOrderId.isNotBlank()) { "Client order id is required." }
        require(quantity.isFinite() && quantity > 0.0) { "Audit quantity must be valid." }
        require(confirmationTokenId.isNotBlank()) { "Confirmation token id is required." }
        require(submitHttpStatusCode == null || submitHttpStatusCode in 100..599) {
            "Submit HTTP status must be valid when present."
        }
        require(
            initialAlpacaStatus == null ||
                initialAlpacaStatus.matches(Regex("^[a-z_]{1,40}$")),
        ) { "Initial Alpaca status must be a normalized lifecycle value when present." }
        require(
            alpacaSubmittedAtIso == null ||
                runCatching { java.time.Instant.parse(alpacaSubmittedAtIso) }.isSuccess,
        ) { "Alpaca submitted_at must be a valid instant when present." }
    }
}
