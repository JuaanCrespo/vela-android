package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2.q append-only local review-queue row.
 *
 * Stores no credential, account id, API key/header, executable
 * endpoint, or server order id. Disabled markers are validated at
 * construction so an unsafe row cannot enter the repository.
 */
@Entity(
    tableName = "paper_order_payload_previews",
    indices = [
        Index(
            value = ["createdAtEpochMillis"],
            name = "ix_paper_payload_preview_created_at",
        ),
        Index(
            value = ["symbol", "createdAtEpochMillis"],
            name = "ix_paper_payload_preview_symbol_created_at",
        ),
        Index(
            value = ["previewId"],
            name = "ix_paper_payload_preview_id",
            unique = true,
        ),
        Index(
            value = ["linkedClientDryRunId"],
            name = "ix_paper_payload_preview_dry_run_id",
        ),
    ],
)
data class PaperOrderPayloadPreviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val previewId: String,
    val linkedClientDryRunId: String,
    val createdAtEpochMillis: Long,
    val symbol: String,
    val side: String,
    val orderType: String,
    val timeInForce: String,
    val quantity: Double,
    val limitPriceUsd: Double?,
    val status: String,
    val estimatedNotionalUsd: Double?,
    val priceSource: String?,
    val priceFreshness: String?,
    val executionEnabled: Boolean,
    val endpointPreview: String,
    val httpMethodPreview: String,
    val warningsSummary: String,
) {
    init {
        require(!executionEnabled) { "Review queue execution must remain disabled." }
        require(endpointPreview == "DISABLED") { "Review queue endpoint must be DISABLED." }
        require(httpMethodPreview == "POST_DISABLED") {
            "Review queue HTTP method must be POST_DISABLED."
        }
    }
}
